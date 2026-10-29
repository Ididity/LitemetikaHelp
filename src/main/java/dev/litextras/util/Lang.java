package dev.litextras.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.litextras.LitematicaExtras;
import dev.litextras.config.Cfg;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Alle Texte des Mods. Standard: Deutsch (de), zusätzlich Englisch (en).
 * Eigene Übersetzungen/Änderungen: config/litematica_extras/lang/<sprache>.json – überschreibt einzelne Schlüssel.
 */
public final class Lang {
    private Lang() {}

    private static final Map<String, String> current = new HashMap<>();
    private static final Map<String, String> fallback = new HashMap<>();
    private static String loadedLang = "";

    public static synchronized void reload() {
        current.clear();
        fallback.clear();
        String lang = Cfg.str("general.language");
        loadedLang = lang;
        loadResource("en", fallback);
        loadResource(lang, current);
        Path override = Cfg.manager().dir().resolve("lang").resolve(lang + ".json");
        if (Files.exists(override)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(override, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.entrySet()) current.put(e.getKey(), e.getValue().getAsString());
            } catch (Exception ex) {
                LitematicaExtras.LOG.warn("Eigene Sprachdatei {} fehlerhaft: {}", override, ex.getMessage());
            }
        }
    }

    private static void loadResource(String lang, Map<String, String> into) {
        try (InputStream in = Lang.class.getResourceAsStream("/assets/litematica_extras/lang_mod/" + lang + ".json")) {
            if (in == null) return;
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) into.put(e.getKey(), e.getValue().getAsString());
        } catch (Exception ex) {
            LitematicaExtras.LOG.warn("Sprachdatei {} nicht lesbar: {}", lang, ex.getMessage());
        }
    }

    public static String language() { return loadedLang; }

    public static boolean has(String key) { return current.containsKey(key) || fallback.containsKey(key); }

    /** Text holen; Platzhalter als Paare: get("msg.x", "name", wert, ...). */
    public static String get(String key, Object... kv) {
        String s = current.get(key);
        if (s == null) s = fallback.get(key);
        if (s == null) s = key;
        if (kv.length >= 2) {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
            s = Template.fill(s, m);
        }
        return s;
    }

    /** Übersetzung oder null. */
    public static String opt(String key) {
        String s = current.get(key);
        return s != null ? s : fallback.get(key);
    }
}
