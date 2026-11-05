package dev.litextras;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Welche Ausgabe der Mod läuft: die volle oder die "Legit"-Edition (ohne KI und ohne ESP wie
 * Durchsicht, Blöcke-Leuchten und Tracer – für Server, auf denen so etwas verboten ist).
 *
 * Die Edition steht in der Ressource assets/litematica_extras/edition.txt (vom Build gesetzt).
 * In der Legit-Edition sind diese Funktionen hart abgeschaltet und lassen sich auch per Config nicht
 * einschalten – egal, was in der config.json5 steht.
 */
public final class Edition {
    private Edition() {}

    public static final boolean LEGIT = read();

    private static boolean read() {
        try (InputStream in = Edition.class.getResourceAsStream("/assets/litematica_extras/edition.txt")) {
            if (in == null) return false;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim().equalsIgnoreCase("legit");
        } catch (Throwable t) {
            return false;
        }
    }

    public static String name() { return LEGIT ? "Legit" : "Voll"; }

    /**
     * Sind die Nicht-Legit-Funktionen (KI/Sprache, freies Welt-Xray, Spieler-/Mob-Tracer) freigeschaltet?
     * Legit-Edition: nie. Volle Edition: nur wenn edition.fullUnlocked true ist (Profil "full").
     * Sonst verhält sich auch die volle Mod wie Legit und die Optionen sind ausgeblendet.
     */
    public static boolean fullFeatures() {
        if (LEGIT) return false;
        try {
            return dev.litextras.config.Cfg.bool("edition.fullUnlocked");
        } catch (Throwable t) {
            return false;
        }
    }
}
