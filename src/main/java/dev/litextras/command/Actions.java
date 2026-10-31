package dev.litextras.command;

import dev.litextras.LitematicaExtras;
import dev.litextras.config.Cfg;
import dev.litextras.core.CompareResult;
import dev.litextras.core.ErrorScanner;
import dev.litextras.core.LitematicaBridge;
import dev.litextras.gui.ConfigScreen;
import dev.litextras.hud.HudPanel;
import dev.litextras.hud.HudRenderer;
import dev.litextras.render.SeeThroughRenderer;
import dev.litextras.util.ChatOut;
import dev.litextras.util.Lang;
import dev.litextras.util.Template;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Locale;

/**
 * Alle internen Mod-Funktionen, die Befehle, Presets und Hotkeys auslösen können.
 * Es gibt hier bewusst KEINE Funktion, die Chat/Befehle an den Server schickt, etwas platziert
 * oder den Spieler bewegt – das ist hart ausgeschlossen.
 */
public final class Actions {
    private Actions() {}

    public static final List<String> ALLOWED = List.of("missing_all", "missing_layer", "missing_near", "status", "toggle_similar",
            "toggle_seethrough", "toggle_hud", "toggle_layerlist", "reload", "open_config", "profile:<name>");

    public static boolean isAllowed(String action) {
        if (action == null) return false;
        String a = action.toLowerCase(Locale.ROOT);
        return a.startsWith("profile:") || ALLOWED.contains(a);
    }

    public static void run(String action) {
        String a = action.toLowerCase(Locale.ROOT).trim();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (a.startsWith("profile:")) {
            ChatOut.lines(Cfg.manager().switchProfile(action.substring(8).trim()));
            LitematicaExtras.afterConfigChange();
            return;
        }
        switch (a) {
            case "missing_all" -> missing(ErrorScanner.Scope.ALL);
            case "missing_layer" -> missing(ErrorScanner.Scope.LAYER);
            case "missing_near" -> missing(ErrorScanner.Scope.NEAR);
            case "status" -> status();
            case "toggle_similar" -> toggle("compare.similar.enabled", "msg.similar");
            case "toggle_seethrough" -> toggleSeeThrough();
            case "toggle_hud" -> toggle("hud.enabled", "msg.hud");
            case "toggle_layerlist" -> toggle("hud.layerList.enabled", "msg.layerList");
            case "reload" -> LitematicaExtras.reload(true);
            case "open_config" -> mc.send(() -> mc.setScreen(new ConfigScreen(mc.currentScreen)));
            default -> ChatOut.error(Lang.get("msg.unknownAction", "action", action, "allowed", String.join(", ", ALLOWED)));
        }
    }

    public static void toggle(String key, String langKey) {
        boolean now = Cfg.manager().toggleRuntime(key);
        ChatOut.send(Lang.get(langKey) + ": " + Lang.get(now ? "msg.on" : "msg.off"), now ? 0x55FF55 : 0xAAAAAA);
        LitematicaExtras.afterConfigChange();
    }

    public static void set(String key, boolean value, String langKey, boolean announce) {
        if (Cfg.bool(key) == value) return;
        Cfg.manager().setRuntime(key, new com.google.gson.JsonPrimitive(value));
        if (announce) ChatOut.send(Lang.get(langKey) + ": " + Lang.get(value ? "msg.on" : "msg.off"), value ? 0x55FF55 : 0xAAAAAA);
        LitematicaExtras.afterConfigChange();
    }

    public static void toggleSeeThrough() {
        if (Cfg.manager().isSafeProfile()) {
            ChatOut.warn(Lang.get("msg.safeProfileBlocks"));
            return;
        }
        toggle("seeThrough.active", "msg.seeThrough");
        String b = Cfg.str("seeThrough.backend");
        if (!"own".equals(b) && !LitematicaBridge.hasRenderThrough()) ChatOut.warn(Lang.get("msg.noLitematicaRenderThrough"));
    }

    /** Fehlliste direkt aus dem Mod (ohne KI) – nur im lokalen Chat. */
    public static void missing(ErrorScanner.Scope scope) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (LitematicaBridge.schematicWorld() == null || ErrorScanner.placements().isEmpty() && LitematicaBridge.placements().isEmpty()) {
            ChatOut.warn(Lang.get("msg.noPlacement"));
            return;
        }
        if (scope == ErrorScanner.Scope.LAYER && !LitematicaBridge.layer().restricting()) {
            ChatOut.warn(Lang.get("layer.notActive"));
            scope = ErrorScanner.Scope.NEAR;
        }
        ChatOut.info(Lang.get("msg.calculating"));
        final ErrorScanner.Scope sc = scope;
        ErrorScanner.request(scope, mc).whenComplete((r, err) -> mc.execute(() -> {
            if (err != null || r == null) {
                ChatOut.error(Lang.get("msg.scanFailed"));
                return;
            }
            printMissing(r, sc);
        }));
    }

    private static void printMissing(ErrorScanner.ScanResult r, ErrorScanner.Scope scope) {
        ChatOut.send(Lang.get("msg.missingHeader", "scope", r.label(), "errors", r.errorCount(),
                "missing", r.count(CompareResult.Status.MISSING), "wrong", r.count(CompareResult.Status.WRONG_TYPE),
                "state", r.count(CompareResult.Status.WRONG_STATE), "extra", r.count(CompareResult.Status.EXTRA),
                "percent", String.format("%.1f", r.progressPercent())), 0xFFFF55);
        if (r.truncated()) ChatOut.warn(Lang.get("msg.truncated"));
        if (r.unloaded() > 0) ChatOut.send(Lang.get("msg.unloaded", "n", r.unloaded()), 0xAAAAAA);
        List<HudRenderer.Row> rows = HudRenderer.rows(r.needs(), HudRenderer.inventory());
        int limit = 20;
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            HudPanel.Line l = HudRenderer.rowLine(rows.get(i), "templates.chatMissingLine");
            ChatOut.segments(l.segs());
        }
        if (rows.size() > limit) ChatOut.send(Lang.get("layer.more", "n", rows.size() - limit), 0xAAAAAA);
        if (rows.isEmpty() && r.errorCount() == 0) ChatOut.ok(Lang.get("layer.done"));
    }

    public static void status() {
        LitematicaBridge.LayerInfo li = LitematicaBridge.layer();
        ChatOut.info(Lang.get("msg.statusLine1", "profile", Cfg.manager().activeProfileName(),
                "placements", LitematicaBridge.placements().size(), "layer", li.label()));
        ChatOut.info(Lang.get("msg.statusLine2",
                "similar", Lang.get(Cfg.bool("compare.similar.enabled") ? "msg.on" : "msg.off"),
                "seethrough", Lang.get(SeeThroughRenderer.active() ? "msg.on" : "msg.off"),
                "ai", Lang.get(Cfg.bool("ai.enabled") ? "msg.on" : "msg.off")));
        ChatOut.send("Overlay-Integration: " + dev.litextras.compat.CompatMixinPlugin.overlayStatus
                + " | Litematica-Abfragen: " + dev.litextras.compat.OverlayTypes.CALLS.get()
                + " | davon als ähnlich/richtig entfernt: " + dev.litextras.compat.OverlayTypes.CLEARED.get()
                + (dev.litextras.compat.OverlayTypes.lastError != null ? " | Fehler: " + dev.litextras.compat.OverlayTypes.lastError : ""), 0xAAAAAA);
        ChatOut.send("Inventar-Markierung: " + (dev.litextras.hud.HudRenderer.INV_RENDERS.get() > 0 ? "aktiv" : "noch nicht gesehen (Inventar einmal öffnen)"), 0xAAAAAA);
        ErrorScanner.ScanResult r = ErrorScanner.latest(ErrorScanner.Scope.LAYER);
        if (r != null && li.restricting()) {
            ChatOut.info(Lang.get("msg.statusLayer", "layer", r.label(), "percent", String.format("%.1f", r.progressPercent()), "errors", r.errorCount()));
        }
    }

    /** Text für Platzhalter in KI-Presets. */
    public static String scopeText() {
        return LitematicaBridge.layer().restricting() ? Lang.get("scope.layer") : Lang.get("scope.schematic");
    }

    public static String fillPreset(String tpl, String args) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("scope", scopeText());
        m.put("args", args == null ? "" : args);
        return Template.fill(tpl, m).trim();
    }
}
