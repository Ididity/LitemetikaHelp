package dev.litextras.hud;

import dev.litextras.ai.AiService;
import dev.litextras.config.Cfg;
import dev.litextras.core.BlockComparator;
import dev.litextras.core.CompareResult;
import dev.litextras.core.ErrorScanner;
import dev.litextras.core.InventoryIndex;
import dev.litextras.core.ItemMapping;
import dev.litextras.core.LitematicaBridge;
import dev.litextras.core.LookTarget;
import dev.litextras.render.SeeThroughRenderer;
import dev.litextras.util.Lang;
import dev.litextras.util.Template;
import dev.litextras.util.Template.Seg;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Zeichnet alle HUD-Elemente des Mods. Rechnet selbst fast nichts – liest nur Caches. */
public final class HudRenderer {
    private HudRenderer() {}

    private static volatile InventoryIndex inventory = InventoryIndex.EMPTY;
    private static BlockPos autoSwitchPos = null;
    private static long autoSwitchDue = 0;
    private static boolean autoSwitchDone = false;

    public static InventoryIndex inventory() { return inventory; }

    /** Haupt-Thread, jeden Tick. */
    public static void tick(MinecraftClient mc) {
        inventory = InventoryIndex.capture(mc);
        handleAutoSwitch(mc);
    }

    public static void render(DrawContext ctx, RenderTickCounter counter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        if (!Cfg.bool("hud.enabled")) return;
        if (mc.currentScreen instanceof dev.litextras.gui.HudEditorScreen) return; // Editor zeichnet selbst
        handleAutoSwitch(mc); // jedes Bild statt nur jeden Tick -> sofortiger Wechsel
        try {
            if (Cfg.bool("hud.realtime")) LookTarget.refreshNow(mc); // jedes Bild neu = keine Verzögerung
            SeeThroughRenderer.render(ctx, counter.getTickProgress(false));
            dev.litextras.render.BlockHighlighter.render(ctx, counter.getTickProgress(false));
            dev.litextras.render.Tracers.render(ctx, counter.getTickProgress(false));
            if (HudPanel.enabled("lookedAt")) HudPanel.draw(ctx, "lookedAt", lookedAtLines());
            if (HudPanel.enabled("preview")) HudPanel.draw(ctx, "preview", previewLines());
            if (HudPanel.enabled("neededItem")) neededItem(ctx);
            if (HudPanel.enabled("layerList")) HudPanel.draw(ctx, "layerList", layerLines());
            if (HudPanel.enabled("status")) HudPanel.draw(ctx, "status", statusLines());
            if (HudPanel.enabled("aiAnswer")) HudPanel.draw(ctx, "aiAnswer", aiLines(mc));
        } catch (Throwable t) {
            // HUD darf das Spiel nie abstürzen lassen
            dev.litextras.LitematicaExtras.debug("HUD-Fehler: " + t);
        }
    }

    // ================================================================== Feature 2: Soll/Ist
    private static List<HudPanel.Line> lookedAtLines() {
        LookTarget.Target t = LookTarget.current();
        if (t == null) return List.of();
        CompareResult r = t.result();
        int text = Cfg.color("colors.text");
        int wrong = Cfg.color("colors.wrong");
        int warn = Cfg.color("colors.warning");
        int stColor = Describe.statusColor(r.status());
        boolean onlyProps = Cfg.bool("hud.lookedAt.onlyWrongPropsRed");

        Map<String, Object> vars = new HashMap<>();
        vars.put("status", List.of(new Seg(Describe.statusText(r), stColor)));
        vars.put("pos", t.pos().getX() + ", " + t.pos().getY() + ", " + t.pos().getZ());
        vars.put("abweichungen", List.of(new Seg(Describe.diffText(r), wrong)));
        vars.put("soll", Describe.statePlain(r.soll()));
        vars.put("ist", Describe.statePlain(r.ist()));

        List<HudPanel.Line> lines = new ArrayList<>();
        for (String id : Cfg.list("hud.lookedAt.lines")) {
            switch (id) {
                case "status" -> lines.add(HudPanel.Line.text(Template.segments(Cfg.str("templates.status"), vars, text)));
                case "soll" -> {
                    if (BlockComparator.isEmpty(r.soll())) break;
                    Map<String, Object> v = new HashMap<>(vars);
                    v.put("block", List.of(new Seg(Describe.blockName(r.soll()), text)));
                    v.put("props", Describe.propSegments(r.soll(), r, text, onlyProps ? warn : text, true));
                    lines.add(lineMaybeColored(Template.segments(Cfg.str("templates.soll"), v, text), onlyProps, stColor));
                }
                case "ist" -> {
                    Map<String, Object> v = new HashMap<>(vars);
                    int blockColor = r.status() == CompareResult.Status.WRONG_TYPE || r.status() == CompareResult.Status.EXTRA ? wrong
                            : r.status() == CompareResult.Status.SIMILAR ? Cfg.color("colors.similar")
                            : r.status() == CompareResult.Status.MISSING ? Cfg.color("colors.missing") : text;
                    v.put("block", List.of(new Seg(Describe.blockName(r.ist()), blockColor)));
                    v.put("props", Describe.propSegments(r.ist(), r, text, wrong, false));
                    lines.add(lineMaybeColored(Template.segments(Cfg.str("templates.ist"), v, text), onlyProps, stColor));
                }
                case "diff" -> {
                    if (!r.diffs().isEmpty()) lines.add(HudPanel.Line.text(Template.segments(Cfg.str("templates.diff"), vars, text)));
                }
                case "compact" -> lines.add(HudPanel.Line.text(Template.segments(Cfg.str("templates.compact"), vars, stColor)));
                case "zustand", "state" -> {
                    HudPanel.Line l = stateLine(r.ist());
                    if (l != null) lines.add(l);
                }
                case "pos" -> lines.add(HudPanel.Line.text(List.of(new Seg((String) vars.get("pos"), Cfg.color("colors.neutral")))));
                default -> {}
            }
        }
        return lines;
    }

    /** Live-Zustand des echten Blocks: gepowert, ausgefahren, an, offen, Signalstärke usw. */
    private static final List<String> STATE_PROPS = List.of("powered", "extended", "lit", "open", "triggered", "enabled",
            "power", "locked", "inverted", "occupied", "has_book", "has_record", "unstable");

    private static HudPanel.Line stateLine(BlockState ist) {
        if (BlockComparator.isEmpty(ist)) return null;
        int on = Cfg.color("colors.correct"), off = Cfg.color("colors.neutral"), text = Cfg.color("colors.text");
        List<Seg> segs = new ArrayList<>();
        for (String name : STATE_PROPS) {
            var p = BlockComparator.findByName(ist, name);
            if (p == null) continue;
            String v = BlockComparator.valueName(ist, p);
            String label = Lang.opt("state." + name);
            if (label == null) label = name;
            segs.add(new Seg(segs.isEmpty() ? Lang.get("state.prefix") : ", ", text));
            if (v.equals("true") || v.equals("false")) {
                boolean b = v.equals("true");
                segs.add(new Seg(label + " " + (b ? "✔" : "✘"), b ? on : off));
            } else {
                boolean b = !v.equals("0");
                segs.add(new Seg(label + " " + v, b ? on : off));
            }
        }
        return segs.isEmpty() ? null : HudPanel.Line.text(segs);
    }

    private static HudPanel.Line lineMaybeColored(List<Seg> segs, boolean onlyProps, int color) {
        if (onlyProps) return HudPanel.Line.text(segs);
        List<Seg> out = new ArrayList<>();
        for (Seg s : segs) out.add(new Seg(s.text(), color));
        return HudPanel.Line.text(out);
    }

    // ================================================================== Feature 2: Vorschau
    private static List<HudPanel.Line> previewLines() {
        LookTarget.Preview p = LookTarget.preview();
        if (p == null) return List.of();
        int text = Cfg.color("colors.text");
        String result;
        int color;
        if (p.problemKey() != null) {
            result = Lang.get(p.problemKey());
            color = Cfg.color("colors.neutral");
        } else {
            CompareResult r = p.result();
            boolean ok = r.status().isOk();
            color = ok ? Cfg.color("colors.correct") : Cfg.color("colors.wrong");
            result = ok ? Lang.get("preview.ok") : (r.status() == CompareResult.Status.WRONG_TYPE
                    ? Lang.get("preview.wrongBlock", "block", Describe.blockName(r.soll()))
                    : Lang.get("preview.wrong", "diff", Describe.diffText(r)));
        }
        Map<String, Object> v = new HashMap<>();
        v.put("result", List.of(new Seg(result, color)));
        v.put("pos", p.pos().getX() + ", " + p.pos().getY() + ", " + p.pos().getZ());
        return List.of(HudPanel.Line.text(Template.segments(Cfg.str("templates.preview"), v, text)));
    }

    // ================================================================== Feature 3: Benötigtes Item
    private static void neededItem(DrawContext ctx) {
        LookTarget.Target t = LookTarget.current();
        if (t == null || BlockComparator.isEmpty(t.result().soll())) return;
        if (Cfg.bool("hud.neededItem.onlyWhenIncorrect") && !t.result().isError()) return;
        BlockState soll = t.result().soll();
        Item need = ItemMapping.primary(soll);
        if (need == null) return;
        InventoryIndex inv = inventory;
        List<InventoryIndex.SlotStack> matches = inv.matchingSlots(soll);
        int text = Cfg.color("colors.text");
        List<HudPanel.Line> lines = new ArrayList<>();

        if (matches.isEmpty()) {
            Map<String, Object> v = new HashMap<>();
            v.put("item", Describe.itemName(need));
            lines.add(new HudPanel.Line(new ItemStack(need), Template.segments(Cfg.str("templates.neededMissing"), v, Cfg.color("colors.wrong"))));
        } else {
            for (InventoryIndex.SlotStack s : matches) {
                Map<String, Object> v = new HashMap<>();
                v.put("item", Describe.itemName(s.item()));
                String tpl;
                if (s.slot() <= 8) {
                    tpl = Cfg.str("templates.neededHotbar");
                    v.put("slot", s.slot() + 1);
                } else if (s.slot() == 40) {
                    tpl = Cfg.str("templates.neededInventory");
                    v.put("slot", Lang.get("slot.offhand"));
                } else {
                    tpl = Cfg.str("templates.neededInventory");
                    v.put("slot", s.slot());
                }
                boolean exact = ItemMapping.isExact(soll, s.item());
                if (!exact) tpl = tpl + Lang.get("needed.similarSuffix");
                int c = !exact ? Cfg.color("colors.similarItem") : s.slot() <= 8 ? Cfg.color("colors.correct") : Cfg.color("colors.warning");
                lines.add(new HudPanel.Line(new ItemStack(s.item()), Template.segments(tpl, v, c)));
            }
        }
        // Bei Hotbar-Treffern reicht die Markierung; Textkasten nur zeigen, wenn nicht alles in der Hotbar ist
        boolean anyHotbar = matches.stream().anyMatch(s -> s.slot() <= 8);
        if (!anyHotbar || matches.size() > 1 || !Cfg.bool("hud.neededItem.highlightHotbar")) {
            HudPanel.draw(ctx, "neededItem", lines);
        }
        if (Cfg.bool("hud.neededItem.highlightHotbar")) {
            int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
            for (InventoryIndex.SlotStack s : matches) {
                if (s.slot() > 8) continue;
                boolean exact = ItemMapping.isExact(soll, s.item());
                int border = Cfg.color(exact ? "colors.hotbarHighlight" : "colors.similarItemHighlight");
                int fill = Cfg.color(exact ? "colors.hotbarHighlightFill" : "colors.similarItemHighlightFill");
                int x = sw / 2 - 91 + s.slot() * 20 + 1;
                int y = sh - 22 + 1;
                ctx.fill(x + 1, y + 1, x + 19, y + 19, fill);
                ctx.fill(x, y, x + 20, y + 1, border);
                ctx.fill(x, y + 19, x + 20, y + 20, border);
                ctx.fill(x, y, x + 1, y + 20, border);
                ctx.fill(x + 19, y, x + 20, y + 20, border);
            }
        }
    }

    /** Zähler, ob die Inventar-Markierung wirklich gezeichnet wird (für /lx status). */
    public static final java.util.concurrent.atomic.AtomicLong INV_RENDERS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Slot-Index -> exakt(true)/ähnlich(false) für die Inventar-Markierung (leer = nichts markieren).
     * Markiert wird: das Item für den angeschauten Block UND alle Items, die in der aktuellen Litematica-Ebene
     * noch fehlen. Exakt richtig = grün, nur ähnlich = gelb (grün gewinnt).
     */
    public static Map<Integer, Boolean> inventoryMarks() {
        if (!Cfg.bool("hud.neededItem.highlightInventory")) return Map.of();
        InventoryIndex inv = InventoryIndex.capture(MinecraftClient.getInstance());
        Map<Integer, Boolean> out = new HashMap<>();
        LookTarget.Target t = LookTarget.current();
        if (t != null && !BlockComparator.isEmpty(t.result().soll())
                && (!Cfg.bool("hud.neededItem.onlyWhenIncorrect") || t.result().isError())) {
            mark(out, inv, t.result().soll());
        }
        if (Cfg.bool("hud.neededItem.highlightLayerNeeds")) {
            LitematicaBridge.LayerInfo li = ErrorScanner.layer();
            ErrorScanner.ScanResult r = ErrorScanner.listShowsAll() ? ErrorScanner.latest(ErrorScanner.Scope.ALL)
                    : li.restricting() ? ErrorScanner.latest(ErrorScanner.Scope.LAYER) : null;
            if (r != null) {
                for (ErrorScanner.NeedEntry n : r.needs().values()) if (n.sample() != null) mark(out, inv, n.sample());
            }
        }
        return out;
    }

    private static void mark(Map<Integer, Boolean> out, InventoryIndex inv, BlockState soll) {
        for (InventoryIndex.SlotStack s : inv.matchingSlots(soll)) {
            boolean exact = ItemMapping.isExact(soll, s.item());
            out.merge(s.slot(), exact, Boolean::logicalOr);
        }
    }

    public static int[] markColors(boolean exact) {
        return exact ? new int[]{Cfg.color("colors.hotbarHighlight"), Cfg.color("colors.hotbarHighlightFill")}
                : new int[]{Cfg.color("colors.similarItemHighlight"), Cfg.color("colors.similarItemHighlightFill")};
    }

    /** Optional: automatisch auf den passenden Hotbar-Slot wechseln (wie Mausrad – nur Client-Auswahl). */
    private static void handleAutoSwitch(MinecraftClient mc) {
        if (!Cfg.bool("hud.neededItem.autoSwitch") || mc.player == null || mc.currentScreen != null) return;
        LookTarget.Target t = LookTarget.current();
        // Nur wechseln, wenn der Block FEHLT (Luft). Steht dort ein falscher Block, will man ihn abbauen –
        // dann bleibt das Werkzeug in der Hand. Auch während man die Angriffstaste hält, nie wechseln.
        if (t == null || t.result().status() != CompareResult.Status.MISSING || BlockComparator.isEmpty(t.result().soll())) { autoSwitchPos = null; return; }
        long now = System.currentTimeMillis();
        long delay = Cfg.integer("hud.neededItem.autoSwitchDelayTicks") * 50L;
        if (mc.options != null && mc.options.attackKey.isPressed()) { autoSwitchPos = null; return; }
        if (!t.pos().equals(autoSwitchPos)) {
            autoSwitchPos = t.pos();
            autoSwitchDue = now + delay;
            autoSwitchDone = false;
        }
        if (autoSwitchDone || now < autoSwitchDue) return;
        var invObj = mc.player.getInventory();
        int selected = invObj.getSelectedSlot();
        BlockState soll = t.result().soll();
        net.minecraft.item.Item held = invObj.getStack(selected).getItem();
        if (ItemMapping.isExact(soll, held)) { autoSwitchDone = true; return; }
        int target = -1;
        for (InventoryIndex.SlotStack s : InventoryIndex.capture(mc).matchingSlots(soll)) { // frisch, nicht vom letzten Tick
            if (s.slot() <= 8) { target = s.slot(); break; }
        }
        if (target >= 0 && target != selected) {
            // Hält man schon ein ähnliches Item, nur wechseln, wenn es ein EXAKTES gibt
            boolean heldSimilar = ItemMapping.matches(soll, held);
            if (!heldSimilar || ItemMapping.isExact(soll, invObj.getStack(target).getItem())) invObj.setSelectedSlot(target);
        }
        autoSwitchDone = true;
    }

    // ================================================================== Feature 4: Ebenenliste
    private static List<HudPanel.Line> layerLines() {
        LitematicaBridge.LayerInfo li = ErrorScanner.layer();
        int text = Cfg.color("colors.text");
        boolean all = ErrorScanner.listShowsAll();
        if (all) {
            ErrorScanner.ScanResult ra = ErrorScanner.latest(ErrorScanner.Scope.ALL);
            if (ErrorScanner.placements().isEmpty()) return List.of();
            if (ra == null || ra.configGeneration() != Cfg.generation()) {
                return List.of(HudPanel.Line.text(List.of(new Seg(Lang.get("list.all") + ": " + Lang.get("layer.calculating"), Cfg.color("colors.neutral")))));
            }
            return needLines(ra, Lang.get("list.all") + (ra.unloaded() > 0 ? " " + Lang.get("list.loadedOnly") : ""));
        }
        if (!li.restricting()) {
            if (Cfg.bool("hud.layerList.onlyInLayerMode")) return List.of();
            return List.of(HudPanel.Line.text(List.of(new Seg(Lang.get("layer.notActive"), Cfg.color("colors.neutral")))));
        }
        ErrorScanner.ScanResult r = ErrorScanner.latest(ErrorScanner.Scope.LAYER);
        if (r == null || !r.label().equals(li.label()) || r.configGeneration() != Cfg.generation()) {
            // Ergebnis gehört zu einer anderen Ebene/Einstellung -> nicht anzeigen, sondern auf neuen Scan warten
            return List.of(HudPanel.Line.text(List.of(new Seg(li.label() + ": " + Lang.get("layer.calculating"), Cfg.color("colors.neutral")))));
        }
        return needLines(r, r.label());
    }

    private static List<HudPanel.Line> needLines(ErrorScanner.ScanResult r, String label) {
        List<HudPanel.Line> lines = new ArrayList<>();
        Map<String, Object> hv = new HashMap<>();
        hv.put("layer", label);
        hv.put("count", r.errorCount());
        hv.put("percent", String.format("%.0f", r.progressPercent()));
        lines.add(HudPanel.Line.text(Template.segments(Cfg.str("templates.layerHeader"), hv,
                r.errorCount() == 0 ? Cfg.color("colors.correct") : Cfg.color("colors.warning"))));
        if (r.errorCount() == 0) {
            lines.add(HudPanel.Line.text(List.of(new Seg(Lang.get("layer.done"), Cfg.color("colors.correct")))));
            return lines;
        }
        List<Row> rows = rows(r.needs(), inventory);
        int max = Cfg.integer("hud.layerList.maxLines") - 1;
        int shown = 0;
        for (Row row : rows) {
            if (shown >= max - 1 && rows.size() > max) break;
            lines.add(rowLine(row, "templates.layerLine"));
            shown++;
        }
        if (rows.size() > shown) {
            lines.add(HudPanel.Line.text(List.of(new Seg(Lang.get("layer.more", "n", rows.size() - shown), Cfg.color("colors.neutral")))));
        }
        if (r.count(CompareResult.Status.EXTRA) > 0) {
            lines.add(HudPanel.Line.text(List.of(new Seg(Lang.get("layer.extra", "n", r.count(CompareResult.Status.EXTRA)), Cfg.color("colors.extra")))));
        }
        return lines;
    }

    /** Eine Zeile der Bedarfsliste (auch für /missing und KI genutzt). */
    public record Row(ErrorScanner.NeedEntry need, String name, int have) {
        public int shortfall() { return Math.max(0, need.need() - have); }
    }

    public static List<Row> rows(Map<String, ErrorScanner.NeedEntry> needs, InventoryIndex inv) {
        List<Row> rows = new ArrayList<>();
        for (ErrorScanner.NeedEntry n : needs.values()) {
            int have = n.label() != null ? inv.countMatching(n.sample()) : inv.count(n.item());
            String name = n.label() != null ? Lang.get("layer.groupName", "group", n.label(), "item", Describe.itemName(n.item()))
                    : Describe.itemName(n.item());
            rows.add(new Row(n, name, have));
        }
        Comparator<Row> cmp = switch (Cfg.str("hud.layerList.sort")) {
            case "name" -> Comparator.comparing(Row::name);
            case "amount" -> Comparator.comparingInt((Row x) -> x.need().need()).reversed().thenComparing(Row::name);
            default -> Comparator.comparingInt(Row::shortfall).reversed()
                    .thenComparing(Comparator.comparingInt((Row x) -> x.need().need()).reversed()).thenComparing(Row::name);
        };
        rows.sort(cmp);
        return rows;
    }

    public static HudPanel.Line rowLine(Row row, String templateKey) {
        boolean enough = row.have() >= row.need().need();
        int color = enough ? Cfg.color("colors.correct") : Cfg.color("colors.wrong");
        Map<String, Object> v = new HashMap<>();
        v.put("item", row.name());
        v.put("have", row.have());
        v.put("need", row.need().need());
        v.put("fehl", row.shortfall());
        Map<String, String> sv = new HashMap<>();
        sv.put("fehl", String.valueOf(row.shortfall()));
        v.put("fehltext", enough ? "" : Template.fill(Cfg.str("templates.layerShortfall"), sv));
        return new HudPanel.Line(new ItemStack(row.need().item()), Template.segments(Cfg.str(templateKey), v, color));
    }

    // ================================================================== Status
    private static List<HudPanel.Line> statusLines() {
        List<HudPanel.Line> lines = new ArrayList<>();
        int n = Cfg.color("colors.neutral");
        String profile = Cfg.manager().activeProfileName();
        lines.add(HudPanel.Line.text(List.of(new Seg(Lang.get("status.profile", "name", profile), n))));
        if (BlockComparator.similarEnabled()) lines.add(HudPanel.Line.text(List.of(new Seg(Lang.get("status.similarOn"), Cfg.color("colors.similar")))));
        if (SeeThroughRenderer.active()) lines.add(HudPanel.Line.text(List.of(new Seg(Lang.get("status.seeThroughOn"), Cfg.color("colors.warning")))));
        if (Cfg.bool("ai.enabled") && AiService.busy()) lines.add(HudPanel.Line.text(List.of(new Seg(Lang.get("ai.thinking"), Cfg.color("colors.warning")))));
        return lines;
    }

    // ================================================================== Feature 6: KI-Antwort
    private static List<HudPanel.Line> aiLines(MinecraftClient mc) {
        AiService.Answer a = AiService.lastHudAnswer();
        if (a == null) return List.of();
        if (System.currentTimeMillis() - a.time() > Cfg.integer("hud.aiAnswer.showSeconds") * 1000L) return List.of();
        int width = Cfg.integer("hud.aiAnswer.wrapWidth");
        List<HudPanel.Line> lines = new ArrayList<>();
        int color = a.error() ? Cfg.color("colors.wrong") : Cfg.color("colors.text");
        for (String para : a.text().split("\n")) {
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ")) {
                String test = cur.length() == 0 ? word : cur + " " + word;
                if (mc.textRenderer.getWidth(test) > width && cur.length() > 0) {
                    lines.add(HudPanel.Line.text(List.of(new Seg(cur.toString(), color))));
                    cur = new StringBuilder(word);
                } else {
                    cur = new StringBuilder(test);
                }
            }
            if (cur.length() > 0) lines.add(HudPanel.Line.text(List.of(new Seg(cur.toString(), color))));
        }
        return lines;
    }
}
