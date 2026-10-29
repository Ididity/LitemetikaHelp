package dev.litextras.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.litextras.LitematicaExtras;
import dev.litextras.ai.AiService;
import dev.litextras.config.Cfg;
import dev.litextras.core.ErrorScanner;
import dev.litextras.util.ChatOut;
import dev.litextras.util.Lang;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Registriert alle Befehle mit frei einstellbaren Namen + Aliasen. Jeder Befehl/Unterbefehl einzeln abschaltbar.
 * Alle Befehle sind CLIENT-Befehle: Sie werden lokal ausgeführt und erreichen den Server nie.
 */
public final class CommandRegistry {
    private CommandRegistry() {}

    /** Bekannte Vanilla-Befehle für die Konfliktprüfung. */
    private static final Set<String> VANILLA = Set.of("advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear", "clone",
            "damage", "data", "datapack", "debug", "defaultgamemode", "deop", "dialog", "difficulty", "effect", "enchant", "execute",
            "experience", "xp", "fetchprofile", "fill", "fillbiome", "forceload", "function", "gamemode", "gamerule", "give", "help", "item",
            "jfr", "kick", "kill", "list", "locate", "loot", "me", "msg", "tell", "w", "op", "pardon", "pardon-ip", "particle", "perf",
            "place", "playsound", "publish", "random", "recipe", "reload", "return", "ride", "rotate", "save-all", "save-off", "save-on",
            "say", "schedule", "scoreboard", "seed", "setblock", "setidletimeout", "setworldspawn", "spawnpoint", "spectate",
            "spreadplayers", "stop", "stopsound", "summon", "tag", "team", "teammsg", "tm", "teleport", "tp", "tellraw", "test", "tick",
            "time", "title", "transfer", "trigger", "version", "waypoint", "weather", "whitelist", "worldborder");

    private static final List<String> conflicts = new ArrayList<>();
    private static final Set<String> registered = new LinkedHashSet<>();

    public static List<String> conflicts() { return List.copyOf(conflicts); }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        conflicts.clear();
        registered.clear();

        // ---- Präfix-Befehl mit Unterbefehlen
        if (Cfg.bool("commands.root.enabled")) {
            for (String name : Cfg.list("commands.root.names")) {
                LiteralArgumentBuilder<FabricClientCommandSource> root = literal(name).executes(c -> { help(); return 1; });
                addSubcommands(root);
                add(d, name, root);
            }
        }

        // ---- /missing
        if (Cfg.bool("commands.missing.enabled")) {
            for (String name : Cfg.list("commands.missing.names")) {
                add(d, name, literal(name)
                        .executes(c -> { Actions.run(ErrorScanner.layer().restricting() ? "missing_layer" : "missing_near"); return 1; })
                        .then(literal("all").executes(c -> { Actions.run("missing_all"); return 1; }))
                        .then(literal("alles").executes(c -> { Actions.run("missing_all"); return 1; }))
                        .then(literal("layer").executes(c -> { Actions.run("missing_layer"); return 1; }))
                        .then(literal("ebene").executes(c -> { Actions.run("missing_layer"); return 1; }))
                        .then(literal("near").executes(c -> { Actions.run("missing_near"); return 1; }))
                        .then(literal("umgebung").executes(c -> { Actions.run("missing_near"); return 1; })));
            }
        }

        // ---- /ai <frage>
        if (Cfg.bool("commands.ai.enabled")) {
            for (String name : Cfg.list("commands.ai.names")) {
                add(d, name, literal(name)
                        .executes(c -> { ChatOut.info(Lang.get("ai.usage", "cmd", name)); return 1; })
                        .then(argument("frage", StringArgumentType.greedyString())
                                .executes(c -> { AiService.ask(StringArgumentType.getString(c, "frage")); return 1; })));
            }
        }

        // ---- Eigene Kurzbefehle (Presets)
        for (JsonElement el : Cfg.jsonArray("presets.list")) {
            if (!el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            if (!p.has("name")) continue;
            List<String> names = new ArrayList<>();
            names.add(p.get("name").getAsString());
            if (p.has("aliases") && p.get("aliases").isJsonArray()) for (JsonElement a : p.getAsJsonArray("aliases")) names.add(a.getAsString());
            String action = p.has("action") ? p.get("action").getAsString() : null;
            String aiPrompt = p.has("aiPrompt") ? p.get("aiPrompt").getAsString() : null;
            if (action != null && !Actions.isAllowed(action)) {
                conflicts.add(Lang.get("msg.presetInvalid", "name", names.get(0), "action", action));
                continue;
            }
            if (action == null && aiPrompt == null) continue;
            for (String n : names) {
                add(d, n, literal(n)
                        .executes(c -> { runPreset(action, aiPrompt, ""); return 1; })
                        .then(argument("args", StringArgumentType.greedyString())
                                .executes(c -> { runPreset(action, aiPrompt, StringArgumentType.getString(c, "args")); return 1; })));
            }
        }
    }

    private static void runPreset(String action, String aiPrompt, String args) {
        if (action != null) Actions.run(action);
        else AiService.ask(Actions.fillPreset(aiPrompt, args));
    }

    private static void add(CommandDispatcher<FabricClientCommandSource> d, String rawName, LiteralArgumentBuilder<FabricClientCommandSource> node) {
        String name = rawName.toLowerCase(Locale.ROOT).trim();
        if (name.isEmpty() || name.contains(" ")) {
            conflicts.add(Lang.get("msg.badCommandName", "name", rawName));
            return;
        }
        if (registered.contains(name)) {
            conflicts.add(Lang.get("msg.duplicateCommand", "name", name));
            return;
        }
        if (VANILLA.contains(name)) conflicts.add(Lang.get("msg.vanillaConflict", "name", name));
        else if (d.getRoot().getChild(name) != null) conflicts.add(Lang.get("msg.modConflict", "name", name));
        registered.add(name);
        d.register(node);
    }

    private static boolean subEnabled(String id) {
        return !Cfg.list("commands.disabledSubcommands").contains(id);
    }

    private static List<String> subNames(String id) {
        JsonObject o = Cfg.jsonObject("commands.subcommands");
        if (!o.has(id)) o = dev.litextras.config.ConfigSpec.get("commands.subcommands").def().getAsJsonObject();
        List<String> out = new ArrayList<>();
        if (o.has(id) && o.get(id).isJsonArray()) for (JsonElement e : o.getAsJsonArray(id)) out.add(e.getAsString());
        if (out.isEmpty()) out.add(id);
        return out;
    }

    private interface Sub { void build(LiteralArgumentBuilder<FabricClientCommandSource> node); }

    private static void sub(LiteralArgumentBuilder<FabricClientCommandSource> root, String id, Sub builder) {
        if (!subEnabled(id)) return;
        for (String n : subNames(id)) {
            LiteralArgumentBuilder<FabricClientCommandSource> node = literal(n);
            builder.build(node);
            root.then(node);
        }
    }

    private static void addSubcommands(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        sub(root, "reload", n -> n.executes(c -> { LitematicaExtras.reload(true); return 1; }));
        sub(root, "config", n -> n.executes(c -> { Actions.run("open_config"); return 1; }));
        sub(root, "status", n -> n.executes(c -> { Actions.status(); return 1; }));
        sub(root, "similar", n -> n.executes(c -> { Actions.run("toggle_similar"); return 1; }));
        sub(root, "seethrough", n -> n.executes(c -> { Actions.run("toggle_seethrough"); return 1; }));
        sub(root, "layer", n -> n.executes(c -> { Actions.run("missing_layer"); return 1; }));
        sub(root, "help", n -> n.executes(c -> { help(); return 1; }));
        sub(root, "aiconfirm", n -> n.executes(c -> { AiService.confirmPrivacy(); return 1; }));
        sub(root, "voice", n -> n
                .executes(c -> { voiceHelp(); return 1; })
                .then(literal("installieren").executes(c -> { dev.litextras.voice.VoiceManager.install(true); return 1; }))
                .then(literal("install").executes(c -> { dev.litextras.voice.VoiceManager.install(true); return 1; }))
                .then(literal("an").executes(c -> { setVoice(true); return 1; }))
                .then(literal("aus").executes(c -> { setVoice(false); return 1; }))
                .then(literal("test").executes(c -> { dev.litextras.voice.VoiceManager.speak(Lang.get("voice.hello")); return 1; }))
                .then(literal("stopp").executes(c -> { dev.litextras.voice.VoiceManager.stopSpeaking(); return 1; }))
                .then(literal("mikros").executes(c -> { listMics(); return 1; }))
                .then(literal("mikro").then(argument("nummer", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 99))
                        .executes(c -> { setMic(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "nummer")); return 1; })))
                .then(literal("maedchen").executes(c -> { persona("maedchen"); return 1; }))
                .then(literal("junge").executes(c -> { persona("junge"); return 1; }))
                .then(literal("nett").executes(c -> { personality("nett"); return 1; }))
                .then(literal("frech").executes(c -> { personality("frech"); return 1; }))
                .then(literal("derb").executes(c -> { personality("derb"); return 1; }))
                .then(argument("name", StringArgumentType.word())
                        .suggests((c, b) -> {
                            for (String v : new String[]{"de_DE-kerstin-low", "de_DE-ramona-low", "de_DE-eva_k-x_low", "de_DE-thorsten-high", "de_DE-thorsten-medium"}) b.suggest(v);
                            return b.buildFuture();
                        })
                        .executes(c -> { dev.litextras.voice.VoiceManager.setVoice(StringArgumentType.getString(c, "name")); return 1; })));
        sub(root, "replace", n -> n
                .executes(c -> { ReplaceCommand.help(); return 1; })
                .then(literal("zurueck").executes(c -> { ReplaceCommand.undo(); return 1; }))
                .then(literal("undo").executes(c -> { ReplaceCommand.undo(); return 1; }))
                .then(literal("speichern").executes(c -> { ReplaceCommand.save(); return 1; }))
                .then(literal("save").executes(c -> { ReplaceCommand.save(); return 1; }))
                .then(argument("bloecke", StringArgumentType.greedyString())
                        .suggests((c, b) -> ReplaceCommand.suggest(b))
                        .executes(c -> { ReplaceCommand.run(StringArgumentType.getString(c, "bloecke")); return 1; })));
        sub(root, "profile", n -> n
                .executes(c -> {
                    ChatOut.info(Lang.get("msg.profiles", "active", Cfg.manager().activeProfileName(), "all", String.join(", ", Cfg.manager().profileNames())));
                    return 1;
                })
                .then(argument("name", StringArgumentType.word())
                        .suggests((c, b) -> {
                            for (String p : Cfg.manager().profileNames()) b.suggest(p);
                            return b.buildFuture();
                        })
                        .executes(c -> { Actions.run("profile:" + StringArgumentType.getString(c, "name")); return 1; })));
        sub(root, "export", n -> n
                .executes(c -> { export(null); return 1; })
                .then(argument("datei", StringArgumentType.word()).executes(c -> { export(StringArgumentType.getString(c, "datei")); return 1; })));
        sub(root, "import", n -> n
                .then(argument("datei", StringArgumentType.word())
                        .suggests((c, b) -> {
                            try (var s = java.nio.file.Files.list(Cfg.manager().exportDir())) {
                                s.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".json5")).forEach(f -> b.suggest(f.replace(".json5", "")));
                            } catch (Exception ignored) {}
                            return b.buildFuture();
                        })
                        .executes(c -> {
                            ChatOut.lines(Cfg.manager().importConfig(StringArgumentType.getString(c, "datei")));
                            LitematicaExtras.afterConfigChange();
                            return 1;
                        })));
    }

    private static void voiceHelp() {
        ChatOut.info(Lang.get("voice.help1"));
        ChatOut.info(Lang.get("voice.help2", "key", dev.litextras.util.Hotkeys.describe("pushToTalk")));
        ChatOut.info(Lang.get("voice.help3"));
        ChatOut.send(Lang.get("voice.status", "tts", Lang.get(dev.litextras.voice.VoiceManager.ttsReady() ? "msg.on" : "msg.off"),
                "stt", Lang.get(dev.litextras.voice.VoiceManager.sttReady() ? "msg.on" : "msg.off")), 0xAAAAAA);
    }

    private static void listMics() {
        var mics = dev.litextras.voice.VoiceManager.microphones();
        String cur = Cfg.str("voice.micName");
        ChatOut.info(Lang.get("voice.micList"));
        ChatOut.info("0: " + Lang.get("voice.micDefault") + (cur.isBlank() ? "  ✔" : ""));
        for (int i = 0; i < mics.size(); i++) {
            String n = mics.get(i).getName();
            ChatOut.info((i + 1) + ": " + n + (n.equals(cur) ? "  ✔" : ""));
        }
    }

    private static void setMic(int nr) {
        var mics = dev.litextras.voice.VoiceManager.microphones();
        String name = nr == 0 || nr > mics.size() ? "" : mics.get(nr - 1).getName();
        Cfg.manager().setPersistent("voice.micName", new com.google.gson.JsonPrimitive(name));
        ChatOut.ok(Lang.get("voice.micSet", "name", name.isBlank() ? Lang.get("voice.micDefault") : name));
    }

    private static void persona(String p) {
        Cfg.manager().setPersistent("ai.persona", new com.google.gson.JsonPrimitive(p));
        AiService.resetSession();
        ChatOut.ok(Lang.get("gui.persona") + ": " + Lang.get("junge".equals(p) ? "gui.boy" : "gui.girl"));
        dev.litextras.voice.VoiceManager.personaChanged();
    }

    private static void personality(String p) {
        Cfg.manager().setPersistent("ai.personality", new com.google.gson.JsonPrimitive(p));
        ChatOut.ok(Lang.get("gui.personality") + ": " + Lang.get("derb".equals(p) ? "gui.crude" : "frech".equals(p) ? "gui.cheeky" : "gui.nice"));
    }

    private static void setVoice(boolean on) {
        Cfg.manager().setRuntime("voice.ttsEnabled", new com.google.gson.JsonPrimitive(on));
        if (!on) dev.litextras.voice.VoiceManager.stopSpeaking();
        ChatOut.info(Lang.get("voice.ttsToggle") + ": " + Lang.get(on ? "msg.on" : "msg.off"));
    }

    private static void export(String name) {
        String path = Cfg.manager().exportConfig(name);
        ChatOut.ok(Lang.get("msg.exported", "path", path));
    }

    public static void help() {
        String p = Cfg.list("commands.root.names").isEmpty() ? "lx" : Cfg.list("commands.root.names").get(0);
        ChatOut.send(Lang.get("help.title"), 0x55FFFF);
        ChatOut.info("/" + p + " " + subNames("reload").get(0) + " – " + Lang.get("help.reload"));
        ChatOut.info("/" + p + " " + subNames("config").get(0) + " – " + Lang.get("help.config"));
        ChatOut.info("/" + p + " " + subNames("status").get(0) + " – " + Lang.get("help.status"));
        ChatOut.info("/" + p + " " + subNames("similar").get(0) + " – " + Lang.get("help.similar"));
        ChatOut.info("/" + p + " " + subNames("seethrough").get(0) + " – " + Lang.get("help.seethrough"));
        ChatOut.info("/" + p + " " + subNames("profile").get(0) + " <name> – " + Lang.get("help.profile"));
        ChatOut.info("/" + p + " " + subNames("voice").get(0) + " – " + Lang.get("help.voice"));
        ChatOut.info("/" + p + " " + subNames("replace").get(0) + " <von> <zu> – " + Lang.get("help.replace"));
        ChatOut.info("/" + p + " " + subNames("export").get(0) + " / " + subNames("import").get(0) + " – " + Lang.get("help.export"));
        if (!Cfg.list("commands.missing.names").isEmpty())
            ChatOut.info("/" + Cfg.list("commands.missing.names").get(0) + " [all|layer|near] – " + Lang.get("help.missing"));
        if (!Cfg.list("commands.ai.names").isEmpty())
            ChatOut.info("/" + Cfg.list("commands.ai.names").get(0) + " <frage> – " + Lang.get("help.ai"));
        ChatOut.send(Lang.get("help.hotkeys", "similar", Cfg.str("hotkeys.toggleSimilar.keys"), "see", Cfg.str("hotkeys.toggleSeeThrough.keys"),
                "reload", Cfg.str("hotkeys.reloadConfig.keys"), "config", Cfg.str("hotkeys.openConfig.keys")), 0xAAAAAA);
    }

    /** Beim Betreten der Welt: Konflikte melden. */
    public static void reportConflicts() {
        if (!Cfg.bool("commands.warnOnConflicts")) return;
        for (String c : conflicts) ChatOut.warn(c);
    }

    @SuppressWarnings("unused")
    private static int noop(CommandContext<FabricClientCommandSource> c) { return 1; }

    static MinecraftClient mc() { return MinecraftClient.getInstance(); }
}
