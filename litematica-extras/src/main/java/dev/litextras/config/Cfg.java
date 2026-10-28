package dev.litextras.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Typisierter Zugriff auf die effektiven Config-Werte. Fällt immer auf den Standardwert zurück. */
public final class Cfg {
    private Cfg() {}

    private static ConfigManager manager;

    public static void init(ConfigManager m) { manager = m; }
    public static ConfigManager manager() { return manager; }
    public static long generation() { return manager == null ? 0 : manager.generation(); }

    private static JsonElement get(String key) {
        JsonElement v = manager == null ? null : manager.raw(key);
        if (v == null) {
            ConfigSpec.Entry e = ConfigSpec.get(key);
            if (e == null) throw new IllegalArgumentException("Unbekannter Config-Schlüssel: " + key);
            v = e.def();
        }
        return v;
    }

    public static boolean bool(String key) {
        try { return get(key).getAsBoolean(); } catch (Exception e) { return ConfigSpec.get(key).def().getAsBoolean(); }
    }

    public static int integer(String key) {
        try { return get(key).getAsInt(); } catch (Exception e) { return ConfigSpec.get(key).def().getAsInt(); }
    }

    public static double dbl(String key) {
        try { return get(key).getAsDouble(); } catch (Exception e) { return ConfigSpec.get(key).def().getAsDouble(); }
    }

    public static String str(String key) {
        try { return get(key).getAsString(); } catch (Exception e) { return ConfigSpec.get(key).def().getAsString(); }
    }

    public static int color(String key) {
        Integer c = Colors.parse(str(key));
        if (c == null) c = Colors.parse(ConfigSpec.get(key).def().getAsString());
        return c == null ? 0xFFFFFFFF : c;
    }

    public static List<String> list(String key) {
        List<String> out = new ArrayList<>();
        JsonElement v = get(key);
        if (v.isJsonArray()) for (JsonElement e : v.getAsJsonArray()) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    public static JsonArray jsonArray(String key) {
        JsonElement v = get(key);
        return v.isJsonArray() ? v.getAsJsonArray() : new JsonArray();
    }

    public static JsonObject jsonObject(String key) {
        JsonElement v = get(key);
        return v.isJsonObject() ? v.getAsJsonObject() : new JsonObject();
    }
}
