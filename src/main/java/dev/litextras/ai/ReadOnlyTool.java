package dev.litextras.ai;

import com.google.gson.JsonObject;

/**
 * Schnittstelle für KI-Tools. Es gibt NUR Lese-Tools: Ein Tool liefert Informationen als kompakten
 * JSON-Text zurück und darf nichts verändern (keine Befehle, kein Chat, nichts platzieren/bewegen,
 * keine Pakete an den Server).
 *
 * Eigene Tools nachrüsten (z. B. in einem eigenen kleinen Addon-Mod):
 * <pre>
 *   ToolRegistry.register(new ReadOnlyTool() {
 *       public String name() { return "get_my_info"; }
 *       public String description() { return "Liefert ..."; }
 *       public JsonObject parameters() { return ToolRegistry.schema(); }
 *       public Category category() { return Category.BUILD; }
 *       public String execute(JsonObject args, ToolContext ctx) throws Exception {
 *           return ctx.onMainThread(() -> "{\"ok\":true}");
 *       }
 *   });
 * </pre>
 */
public interface ReadOnlyTool {

    enum Category {
        /** Bau/Schematic/Inventar – Standard erlaubt. */
        BUILD,
        /** Spieler orten, Umgebung durchsuchen, Chat – im Multiplayer standardmäßig gesperrt, im Profil "sicher" immer aus. */
        PLAYER_OR_WORLD,
        /** Sonstige Infos über die eigene Spielsitzung. */
        INFO
    }

    String name();

    /** Beschreibung für die KI: was das Tool liefert UND was es nicht wissen kann. */
    String description();

    /** JSON-Schema der Parameter (type: object). */
    JsonObject parameters();

    Category category();

    /** Standardwert, falls es für dieses Tool keinen Eintrag ai.tools.<name>.enabled gibt (eigene Tools). */
    default boolean enabledByDefault() { return false; }

    /** Wird im KI-Hintergrundthread aufgerufen. Spieldaten nur über ctx.onMainThread(...) lesen. */
    String execute(JsonObject args, ToolContext ctx) throws Exception;
}
