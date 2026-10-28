package dev.litextras.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.litextras.LitematicaExtras;
import dev.litextras.config.Cfg;
import dev.litextras.config.ConfigSpec;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Registry für KI-Tools. Akzeptiert nur {@link ReadOnlyTool}-Implementierungen.
 * Es gibt im ganzen Mod keine Schreib-Tools und keinen Weg, eines zu registrieren, das als
 * Schreib-Tool deklariert ist.
 */
public final class ToolRegistry {
    private ToolRegistry() {}

    private static final Map<String, ReadOnlyTool> TOOLS = new LinkedHashMap<>();
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{2,40}$");
    /** Namensbestandteile, die auf Schreib-Aktionen hindeuten – werden abgelehnt. */
    private static final List<String> FORBIDDEN = List.of("send", "place", "build", "break", "move", "command", "chat_send",
            "write", "set_", "delete", "attack", "use_", "click", "drop", "teleport", "say");

    public static synchronized void register(ReadOnlyTool tool) {
        String n = tool.name();
        if (n == null || !NAME.matcher(n).matches()) throw new IllegalArgumentException("Ungültiger Tool-Name: " + n);
        for (String f : FORBIDDEN) {
            if (n.contains(f)) throw new IllegalArgumentException("Tool '" + n + "' klingt nach Schreib-Aktion – nur Lese-Tools erlaubt.");
        }
        if (TOOLS.containsKey(n)) LitematicaExtras.LOG.warn("KI-Tool {} wird überschrieben", n);
        TOOLS.put(n, tool);
    }

    public static synchronized Collection<ReadOnlyTool> all() { return List.copyOf(TOOLS.values()); }

    public static synchronized ReadOnlyTool get(String name) { return TOOLS.get(name); }

    public static boolean isEnabled(ReadOnlyTool t) {
        String key = "ai.tools." + t.name() + ".enabled";
        return ConfigSpec.get(key) != null ? Cfg.bool(key) : t.enabledByDefault();
    }

    public static String description(ReadOnlyTool t) {
        String key = "ai.tools." + t.name() + ".description";
        if (ConfigSpec.get(key) != null) {
            String custom = Cfg.str(key);
            if (!custom.isBlank()) return custom;
        }
        return t.description();
    }

    /** Aktive Tools für diese Frage – Haupt-Thread (prüft Singleplayer/Multiplayer). */
    public static List<ReadOnlyTool> activeTools(MinecraftClient mc, List<String> blockedOut) {
        boolean multiplayer = !mc.isInSingleplayer();
        boolean lock = Cfg.bool("ai.lockWorldToolsInMultiplayer");
        boolean safe = Cfg.manager().isSafeProfile();
        List<ReadOnlyTool> out = new ArrayList<>();
        for (ReadOnlyTool t : all()) {
            if (!isEnabled(t)) continue;
            if (t.category() == ReadOnlyTool.Category.PLAYER_OR_WORLD && (safe || (multiplayer && lock))) {
                blockedOut.add(t.name());
                continue;
            }
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ Schema-Helfer
    public static JsonObject schema() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", new JsonObject());
        return o;
    }

    public static JsonObject schema(Object... propTriples) {
        JsonObject o = schema();
        JsonObject props = o.getAsJsonObject("properties");
        for (int i = 0; i + 2 < propTriples.length; i += 3) {
            String name = (String) propTriples[i];
            String type = (String) propTriples[i + 1];
            String desc = (String) propTriples[i + 2];
            JsonObject p = new JsonObject();
            if (type.startsWith("enum:")) {
                p.addProperty("type", "string");
                JsonArray e = new JsonArray();
                for (String v : type.substring(5).split("\\|")) e.add(v);
                p.add("enum", e);
            } else {
                p.addProperty("type", type);
            }
            p.addProperty("description", desc);
            props.add(name, p);
        }
        return o;
    }
}
