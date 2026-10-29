package dev.litextras;

import dev.litextras.ai.AiService;
import dev.litextras.command.Actions;
import dev.litextras.command.CommandRegistry;
import dev.litextras.config.Cfg;
import dev.litextras.config.ConfigManager;
import dev.litextras.core.EquivalenceGroups;
import dev.litextras.core.ErrorScanner;
import dev.litextras.core.ItemMapping;
import dev.litextras.core.LookTarget;
import dev.litextras.hud.HudRenderer;
import dev.litextras.compat.CompatMixinPlugin;
import dev.litextras.render.SeeThroughRenderer;
import dev.litextras.util.ChatOut;
import dev.litextras.util.Hotkeys;
import dev.litextras.util.Lang;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Einstiegspunkt. Verdrahtet Config, Befehle, Hotkeys, Tick-Logik und HUD.
 * Rein clientseitig: kein Server-Code, keine Pakete, keine Eingriffe in die Spielmechanik.
 */
public class LitematicaExtras implements ClientModInitializer {
    public static final String MOD_ID = "litematica_extras";
    public static final Logger LOG = LoggerFactory.getLogger("LitematicaExtras");

    private static ConfigManager config;

    @Override
    public void onInitializeClient() {
        config = new ConfigManager();
        Cfg.init(config);
        List<String> msgs = config.load();
        Lang.reload();
        EquivalenceGroups.rebuild();
        for (String m : msgs) LOG.info("[Config] {}", m);

        afterConfigChange(); // Signatur der Vergleichsregeln merken
        registerHotkeys();
        AiService.init(); // eingebaute Lese-Tools registrieren (eigene Tools können danach ergänzt werden)

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> CommandRegistry.register(dispatcher));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                Hotkeys.tick(mc);
                if (mc.world == null) return;
                SeeThroughRenderer.tick();
                LookTarget.tick(mc);
                HudRenderer.tick(mc);
                ErrorScanner.tick(mc);
            } catch (Throwable t) {
                debug("Tick-Fehler: " + t);
            }
        });

        // UNSICHER: HudElementRegistry gibt es seit Fabric API für 1.21.6. Falls nicht vorhanden -> Fehlermeldung schicken.
        HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), HudRenderer::render);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(() -> {
            EquivalenceGroups.rebuild(); // Tags kommen vom Server
            ItemMapping.clearCache();
            ErrorScanner.invalidateAll();
            if (Cfg.bool("general.startupMessage")) {
                ChatOut.send(Lang.get("msg.welcome", "cmd", firstRoot(), "overlay", CompatMixinPlugin.overlayStatus), 0xAAAAAA);
            }
            for (String m : config.lastMessages()) ChatOut.warn(m);
            CommandRegistry.reportConflicts();
            if (Cfg.bool("voice.autoInstall") && (Cfg.bool("voice.ttsEnabled") || Cfg.bool("voice.sttEnabled"))
                    && !(dev.litextras.voice.VoiceManager.ttsReady() && dev.litextras.voice.VoiceManager.sttReady())) {
                dev.litextras.voice.VoiceManager.install(false);
            }
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            ErrorScanner.invalidateAll();
            AiService.resetSession();
        });

        LOG.info("Litematica Extras geladen (Overlay-Kompatibilität: {})", CompatMixinPlugin.overlayStatus);
    }

    private static String firstRoot() {
        List<String> r = Cfg.list("commands.root.names");
        return r.isEmpty() ? "lx" : r.get(0);
    }

    private static void registerHotkeys() {
        Hotkeys.register("toggleSimilar", down -> {
            if (Hotkeys.isHold("toggleSimilar")) Actions.set("compare.similar.enabled", down, "msg.similar", false);
            else if (down) Actions.run("toggle_similar");
        });
        Hotkeys.register("toggleSeeThrough", down -> {
            if (Hotkeys.isHold("toggleSeeThrough")) {
                if (!config.isSafeProfile()) Actions.set("seeThrough.active", down, "msg.seeThrough", false);
            } else if (down) Actions.run("toggle_seethrough");
        });
        Hotkeys.register("toggleHud", down -> {
            if (Hotkeys.isHold("toggleHud")) Actions.set("hud.enabled", down, "msg.hud", false);
            else if (down) Actions.run("toggle_hud");
        });
        Hotkeys.register("toggleLayerList", down -> {
            if (Hotkeys.isHold("toggleLayerList")) Actions.set("hud.layerList.enabled", down, "msg.layerList", false);
            else if (down) Actions.run("toggle_layerlist");
        });
        Hotkeys.register("cycleProfile", down -> {
            if (down) {
                ChatOut.lines(config.cycleProfile());
                afterConfigChange();
            }
        });
        Hotkeys.register("reloadConfig", down -> { if (down) reload(true); });
        Hotkeys.register("openConfig", down -> { if (down) Actions.run("open_config"); });
        Hotkeys.register("pushToTalk", dev.litextras.voice.VoiceManager::pushToTalk);
        Hotkeys.register("stopSpeaking", down -> { if (down) dev.litextras.voice.VoiceManager.stopSpeaking(); });
        Hotkeys.register("missingList", down -> {
            if (down) Actions.run(ErrorScanner.layer().restricting() ? "missing_layer" : "missing_near");
        });
    }

    /** /lx reload bzw. Hotkey: Config ohne Neustart übernehmen. */
    public static void reload(boolean announce) {
        List<String> msgs = config.load();
        afterConfigChange();
        List<String> groupProblems = EquivalenceGroups.rebuild();
        if (announce) {
            if (msgs.isEmpty() && groupProblems.isEmpty()) ChatOut.ok(Lang.get("msg.reloaded"));
            else {
                ChatOut.lines(msgs);
                for (String g : groupProblems) ChatOut.warn(g);
                ChatOut.info(Lang.get("msg.reloadedWithNotes"));
            }
            ChatOut.send(Lang.get("msg.reloadCommandsHint"), 0xAAAAAA);
        }
    }

    /** Nach jeder Config-Änderung: abhängige Caches erneuern. */
    private static String lastCompareSignature = "";

    public static void afterConfigChange() {
        Lang.reload();
        EquivalenceGroups.ensureBuilt();
        ErrorScanner.invalidateAll();
        // Haben sich die Vergleichsregeln geändert? Dann Litematicas Overlay neu aufbauen lassen,
        // damit "ähnliche" Blöcke auch dort sofort nicht mehr markiert werden.
        String sig = Cfg.bool("compare.similar.enabled") + "|" + Cfg.bool("compare.similar.solidBlocks") + "|"
                + Cfg.list("compare.ignoredProperties") + "|" + Cfg.bool("compare.ignoredPropertiesAlsoWhenExact") + "|"
                + Cfg.jsonArray("compare.groups") + "|" + Cfg.list("compare.similar.solidBlacklist") + "|" + Cfg.bool("compare.reportExtra");
        if (!sig.equals(lastCompareSignature)) {
            boolean first = lastCompareSignature.isEmpty();
            lastCompareSignature = sig;
            if (!first) mc().execute(dev.litextras.core.LitematicaBridge::rebuildOverlay);
        }
    }

    public static void debug(String msg) {
        if (Cfg.manager() != null && Cfg.bool("general.debugLog")) LOG.info("[debug] {}", msg);
    }

    public static MinecraftClient mc() { return MinecraftClient.getInstance(); }
}
