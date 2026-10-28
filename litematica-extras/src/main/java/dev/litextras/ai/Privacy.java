package dev.litextras.ai;

import com.google.gson.JsonObject;
import dev.litextras.config.Cfg;

/** Datenschutz und Prompt-Injection-Schutz für alles, was an die KI geht. */
public final class Privacy {
    private Privacy() {}

    public static JsonObject coords(double x, double y, double z) {
        JsonObject o = new JsonObject();
        String mode = Cfg.str("ai.privacy.coordinates");
        switch (mode) {
            case "hidden" -> o.addProperty("hidden", "Koordinaten werden laut Einstellung nicht gesendet");
            case "rounded" -> {
                int r = Cfg.integer("ai.privacy.roundTo");
                o.addProperty("x", Math.round(x / r) * r);
                o.addProperty("y", Math.round(y / r) * r);
                o.addProperty("z", Math.round(z / r) * r);
                o.addProperty("rounded_to", r);
            }
            default -> {
                o.addProperty("x", (int) Math.floor(x));
                o.addProperty("y", (int) Math.floor(y));
                o.addProperty("z", (int) Math.floor(z));
            }
        }
        return o;
    }

    /**
     * Text aus der Welt (Schilder, Namen, Chat, Item-Namen, Bücher, Schematic-Namen) ist UNVERTRAUT.
     * Er wird gekürzt, von Steuerzeichen befreit und als Daten markiert.
     */
    public static String untrustedText(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").replace("<", "‹").replace(">", "›");
        if (t.length() > 120) t = t.substring(0, 120) + "…";
        return "«DATEN:" + t + "»";
    }

    public static String direction(double dx, double dz) {
        // Minecraft: -Z = Norden, +X = Osten
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        if (ang < 0) ang += 360;
        String[] dirs = {"Norden", "Nordosten", "Osten", "Südosten", "Süden", "Südwesten", "Westen", "Nordwesten"};
        return dirs[(int) Math.round(ang / 45.0) % 8];
    }
}
