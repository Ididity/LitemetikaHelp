package dev.litextras.ai;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Hilfsmittel für Tools: Zugriff auf den Haupt-Thread mit Zeitlimit und Datenschutz-Funktionen. */
public final class ToolContext {
    private final Map<String, String> anonymized = new HashMap<>();

    /** Führt den Code im Spiel-Thread aus (dort sind Welt/Inventar sicher lesbar), max. 5 Sekunden. */
    public <T> T onMainThread(Supplier<T> s) throws Exception {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isOnThread()) return s.get();
        return mc.submit(s).get(5, TimeUnit.SECONDS);
    }

    /** Spielername ggf. anonymisieren ("Spieler 1" …) – innerhalb einer Frage konsistent. */
    public String playerName(String real) {
        if (!dev.litextras.config.Cfg.bool("ai.privacy.anonymizePlayerNames")) return Privacy.untrustedText(real);
        return anonymized.computeIfAbsent(real, r -> "Spieler " + (anonymized.size() + 1));
    }

    /** Koordinaten gemäß Datenschutz-Einstellung (exakt, gerundet oder ausgeblendet). */
    public JsonObject coords(double x, double y, double z) {
        return Privacy.coords(x, y, z);
    }
}
