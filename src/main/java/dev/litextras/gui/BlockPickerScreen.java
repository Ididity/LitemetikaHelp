package dev.litextras.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import dev.litextras.config.Cfg;
import dev.litextras.render.BlockHighlighter;
import dev.litextras.util.Lang;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Auswahl der Blöcke, die durch Wände leuchten sollen.
 * Plan = Blöcke aus den geladenen Schematics (mit Anzahl), Welt = alle Blöcke (nur Einzelspieler wirksam).
 */
public class BlockPickerScreen extends Screen {
    private final Screen parent;
    private boolean worldMode;
    private int page = 0;
    private String query = "";
    private TextFieldWidget search;
    private List<Entry> all = null;
    private CompletableFuture<List<Entry>> loading;

    private record Entry(String id, String name, int count) {}

    public BlockPickerScreen(Screen parent) {
        super(Text.literal("Blöcke leuchten lassen"));
        this.parent = parent;
        this.worldMode = "world".equals(Cfg.str("highlight.pickerMode"));
    }

    private String key() { return worldMode ? "highlight.worldBlocks" : "highlight.planBlocks"; }

    private void load() {
        all = null;
        boolean w = worldMode;
        loading = CompletableFuture.supplyAsync(() -> w ? worldEntries() : planEntries());
    }

    private static List<Entry> worldEntries() {
        List<Entry> out = new ArrayList<>();
        for (Identifier id : Registries.BLOCK.getIds()) {
            Block b = Registries.BLOCK.get(id);
            if (b == null || b.getDefaultState().isAir()) continue;
            out.add(new Entry(id.toString(), b.getName().getString(), -1));
        }
        out.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static List<Entry> planEntries() {
        Map<Block, Integer> counts = new IdentityHashMap<>();
        try {
            Set<LitematicaSchematic> schems = new LinkedHashSet<>();
            for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
                if (p.isEnabled() && p.getSchematic() != null) schems.add(p.getSchematic());
            }
            Map<BlockState, Block> cache = new HashMap<>();
            for (LitematicaSchematic s : schems) {
                for (String region : s.getAreas().keySet()) {
                    LitematicaBlockStateContainer c = s.getSubRegionContainer(region);
                    if (c == null) continue;
                    Vec3i size = c.getSize();
                    for (int y = 0; y < size.getY(); y++) for (int z = 0; z < size.getZ(); z++) for (int x = 0; x < size.getX(); x++) {
                        BlockState st = c.get(x, y, z);
                        if (st == null || st.isAir()) continue;
                        counts.merge(st.getBlock(), 1, Integer::sum);
                    }
                }
            }
        } catch (Throwable t) {
            dev.litextras.LitematicaExtras.debug("Blockliste aus Plan fehlgeschlagen: " + t);
        }
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<Block, Integer> e : counts.entrySet()) {
            out.add(new Entry(Registries.BLOCK.getId(e.getKey()).toString(), e.getKey().getName().getString(), e.getValue()));
        }
        out.sort(Comparator.comparingInt((Entry e) -> -e.count()).thenComparing(Entry::name));
        return out;
    }

    private List<Entry> filtered() {
        if (all == null) return List.of();
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return all;
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (e.name().toLowerCase(Locale.ROOT).contains(q) || e.id().contains(q)) out.add(e);
        return out;
    }

    private int rows() { return Math.max(3, (this.height - 130) / 22); }

    @Override
    protected void init() {
        if (all == null && loading == null) load();
        int cx = this.width / 2;
        int colW = 150, gap = 6, left = cx - colW - gap / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("highlight.active") + ": " + Lang.get(BlockHighlighter.active() ? "msg.on" : "msg.off")), b -> {
            Cfg.manager().setPersistent("highlight.active", new JsonPrimitive(!BlockHighlighter.active()));
            clearAndInit();
        }).dimensions(left, 22, colW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get(worldMode ? "highlight.modeWorld" : "highlight.modePlan")), b -> {
            worldMode = !worldMode;
            Cfg.manager().setPersistent("highlight.pickerMode", new JsonPrimitive(worldMode ? "world" : "plan"));
            page = 0;
            loading = null;
            all = null;
            clearAndInit();
        }).dimensions(left + colW + gap, 22, colW, 20).build());

        search = new TextFieldWidget(this.textRenderer, left, 46, colW * 2 + gap, 18, Text.literal("Suche"));
        search.setText(query);
        search.setChangedListener(s -> { query = s; page = 0; clearAndInit(); });
        addDrawableChild(search);
        setFocused(search);

        List<Entry> list = filtered();
        Set<String> selected = new LinkedHashSet<>(Cfg.list(key()));
        int perPage = rows() * 2;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;
        int y0 = 70;
        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= list.size()) break;
            Entry e = list.get(idx);
            boolean on = selected.contains(e.id());
            String label = (on ? "§a✔ " : "§7") + e.name() + (e.count() >= 0 ? " §8(" + e.count() + ")" : "");
            int x = left + (i % 2) * (colW + gap);
            int y = y0 + (i / 2) * 22;
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                Set<String> cur = new LinkedHashSet<>(Cfg.list(key()));
                if (!cur.remove(e.id())) cur.add(e.id());
                JsonArray arr = new JsonArray();
                for (String s : cur) arr.add(s);
                Cfg.manager().setPersistent(key(), arr);
                if (!cur.isEmpty() && !BlockHighlighter.active()) Cfg.manager().setPersistent("highlight.active", new JsonPrimitive(true));
                clearAndInit();
            }).dimensions(x, y, colW, 20).build());
        }

        int by = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { if (page > 0) { page--; clearAndInit(); } })
                .dimensions(left, by, 30, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page++; clearAndInit(); })
                .dimensions(left + 34, by, 30, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("highlight.clear")), b -> {
            Cfg.manager().setPersistent(key(), new JsonArray());
            clearAndInit();
        }).dimensions(left + 70, by, 110, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.done")), b -> close())
                .dimensions(left + colW * 2 + gap - 110, by, 110, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (all == null && loading != null && loading.isDone()) {
            all = loading.getNow(List.of());
            clearAndInit();
        }
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFFFF);
        List<Entry> list = filtered();
        int perPage = rows() * 2;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        String info;
        if (all == null) info = Lang.get("highlight.loading");
        else if (list.isEmpty()) info = Lang.get(worldMode ? "highlight.noMatch" : "highlight.noPlan");
        else info = Lang.get("highlight.page", "page", page + 1, "pages", pages, "n", Cfg.list(key()).size());
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(info), this.width / 2, this.height - 42, 0xFFAAAAAA);
        if (worldMode && !BlockHighlighter.worldModeAllowed(this.client)) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(Lang.get("highlight.worldOnlySp")), this.width / 2, this.height - 54, 0xFFFF5555);
        }
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
