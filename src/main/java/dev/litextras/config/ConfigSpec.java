package dev.litextras.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zentrale Definition ALLER Einstellungen: Schlüssel, Typ, Standardwert, Kommentar, Grenzen.
 * Die Config-Datei wird aus dieser Liste erzeugt (inkl. Kommentaren); fehlende Einträge
 * werden automatisch ergänzt. Nichts Veränderbares steht anderswo fest im Code.
 */
public final class ConfigSpec {
    private ConfigSpec() {}

    public static final int CONFIG_VERSION = 11;

    public enum Type { BOOL, INT, DOUBLE, STRING, ENUM, COLOR, STRING_LIST, JSON }

    public record Entry(String key, Type type, JsonElement def, String comment,
                        double min, double max, List<String> options) {}

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    public static Map<String, Entry> entries() { return Collections.unmodifiableMap(ENTRIES); }
    public static Map<String, String> sectionComments() { return Collections.unmodifiableMap(SECTIONS); }
    public static Entry get(String key) { return ENTRIES.get(key); }

    // ------------------------------------------------------------------ Builder
    private static void section(String prefix, String comment) { SECTIONS.put(prefix, comment); }

    private static void bool(String k, boolean d, String c) {
        ENTRIES.put(k, new Entry(k, Type.BOOL, new JsonPrimitive(d), c, 0, 0, List.of()));
    }
    private static void integer(String k, int d, int min, int max, String c) {
        ENTRIES.put(k, new Entry(k, Type.INT, new JsonPrimitive(d), c, min, max, List.of()));
    }
    private static void dbl(String k, double d, double min, double max, String c) {
        ENTRIES.put(k, new Entry(k, Type.DOUBLE, new JsonPrimitive(d), c, min, max, List.of()));
    }
    private static void str(String k, String d, String c) {
        ENTRIES.put(k, new Entry(k, Type.STRING, new JsonPrimitive(d), c, 0, 0, List.of()));
    }
    private static void enm(String k, String d, String c, String... options) {
        ENTRIES.put(k, new Entry(k, Type.ENUM, new JsonPrimitive(d), c, 0, 0, Arrays.asList(options)));
    }
    private static void color(String k, String d, String c) {
        ENTRIES.put(k, new Entry(k, Type.COLOR, new JsonPrimitive(d), c, 0, 0, List.of()));
    }
    private static void list(String k, String c, String... d) {
        JsonArray a = new JsonArray();
        for (String s : d) a.add(s);
        ENTRIES.put(k, new Entry(k, Type.STRING_LIST, a, c, 0, 0, List.of()));
    }
    private static void json(String k, String jsonDefault, String c) {
        ENTRIES.put(k, new Entry(k, Type.JSON, JsonParser.parseString(jsonDefault), c, 0, 0, List.of()));
    }

    private static final String ANCHORS_COMMENT = "Ecke: top_left, top_center, top_right, middle_left, center, middle_right, bottom_left, bottom_center, bottom_right";

    private static void hudElement(String id, String title, boolean enabled, String anchor, int ox, int oy, int maxLines) {
        section("hud." + id, title);
        bool("hud." + id + ".enabled", enabled, "Dieses HUD-Element anzeigen.");
        enm("hud." + id + ".anchor", anchor, ANCHORS_COMMENT,
                "top_left", "top_center", "top_right", "middle_left", "center", "middle_right", "bottom_left", "bottom_center", "bottom_right");
        integer("hud." + id + ".offsetX", ox, -4000, 4000, "Pixel-Versatz horizontal (positiv = nach rechts).");
        integer("hud." + id + ".offsetY", oy, -4000, 4000, "Pixel-Versatz vertikal (positiv = nach unten).");
        dbl("hud." + id + ".scale", 1.0, 0.25, 4.0, "Größe/Schriftgröße (1.0 = normal).");
        bool("hud." + id + ".background", true, "Hintergrundkasten zeichnen.");
        color("hud." + id + ".backgroundColor", "#90000000", "Hintergrundfarbe #AARRGGBB (AA = Transparenz).");
        integer("hud." + id + ".padding", 3, 0, 20, "Innenabstand in Pixeln.");
        integer("hud." + id + ".maxLines", maxLines, 1, 100, "Maximale Anzahl Zeilen.");
    }

    private static void hotkey(String id, String keys, String mode, String comment) {
        section("hotkeys." + id, comment);
        bool("hotkeys." + id + ".enabled", true, "Hotkey aktiv.");
        str("hotkeys." + id + ".keys", keys,
                "Tastenkombination, mit + verbunden. Namen wie in GLFW: A-Z, 0-9, F1-F25, LEFT_ALT, RIGHT_ALT, LEFT_CONTROL, LEFT_SHIFT,\n"
                        + "LEFT_SUPER (Cmd/Windows), KP_1, TAB, GRAVE_ACCENT, MOUSE_4 ... Leer = kein Hotkey.");
        enm("hotkeys." + id + ".mode", mode, "toggle = Drücken schaltet um, hold = nur aktiv solange gedrückt.", "toggle", "hold");
    }

    private static void aiTool(String name, boolean enabled, String what) {
        bool("ai.tools." + name + ".enabled", enabled, what);
        str("ai.tools." + name + ".description", "", "Eigene Tool-Beschreibung für die KI (leer = Standard).");
    }

    private static String glassPanes() {
        StringBuilder sb = new StringBuilder("\"minecraft:glass_pane\"");
        for (String c : COLORS) sb.append(", \"minecraft:").append(c).append("_stained_glass_pane\"");
        return sb.toString();
    }
    private static String concrete() {
        StringBuilder sb = new StringBuilder();
        for (String c : COLORS) { if (sb.length() > 0) sb.append(", "); sb.append("\"minecraft:").append(c).append("_concrete\""); }
        return sb.toString();
    }
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    static {
        // =================================================================== ALLGEMEIN
        section("general", "Allgemeine Einstellungen");
        enm("general.language", "de", "Sprache aller Texte des Mods (de oder en). Eigene Texte: config/litematica_extras/lang/<sprache>.json", "de", "en");
        bool("general.startupMessage", true, "Beim Betreten einer Welt kurz den Status des Mods im Chat (nur lokal) anzeigen.");
        bool("general.debugLog", false, "Ausführlichere Log-Ausgaben (nie mit API-Key).");

        // =================================================================== BEFEHLE
        section("commands", "Befehle. Der erste Name ist der Hauptname, alle weiteren sind Aliase.\n"
                + "Änderungen an Namen greifen nach erneutem Betreten der Welt / des Servers.");
        list("commands.root.names", "Präfix-Befehl, z. B. /lx reload. Erster Eintrag = Präfix.", "lx", "litextras");
        bool("commands.root.enabled", true, "Präfix-Befehl aktiv.");
        list("commands.missing.names", "Fehlliste ohne KI.", "missing", "fehlt");
        bool("commands.missing.enabled", true, "Befehl aktiv.");
        list("commands.ai.names", "KI-Frage (Feature 6).", "ai", "frag");
        bool("commands.ai.enabled", true, "Befehl aktiv (KI selbst ist zusätzlich über ai.enabled geschaltet).");
        json("commands.subcommands", """
                {
                  "reload": ["reload", "neuladen"],
                  "config": ["config", "einstellungen"],
                  "profile": ["profile", "profil"],
                  "export": ["export"],
                  "import": ["import"],
                  "status": ["status"],
                  "similar": ["similar", "aehnlich"],
                  "seethrough": ["xray", "durchsicht"],
                  "layer": ["layer", "ebene"],
                  "aiconfirm": ["ai-confirm", "ki-bestaetigen"],
                  "replace": ["tausch", "replace"],
                  "voice": ["stimme", "voice"],
                  "hudedit": ["hud", "hudedit"],
                  "listscope": ["liste", "list"],
                  "help": ["help", "hilfe"]
                }""", "Unterbefehle des Präfix-Befehls: interne ID -> Liste von Namen (erster = Hauptname).");
        list("commands.disabledSubcommands", "IDs von Unterbefehlen, die ausgeschaltet sein sollen, z. B. [\"import\"].");
        bool("commands.warnOnConflicts", true, "Beim Betreten melden, wenn ein Befehlsname mit Vanilla/Server/anderen Mods kollidiert.");

        section("presets", "Eigene Kurzbefehle. Sie lösen NUR interne Mod-Funktionen oder KI-Fragen aus.\n"
                + "Server-Befehle, Chatnachrichten, Platzieren oder Bewegen sind fest verboten und nicht freischaltbar.");
        json("presets.list", """
                [
                  {"name": "ebenenliste", "aliases": ["el"], "action": "missing_layer"},
                  {"name": "fortschritt", "aliases": [], "action": "status"},
                  {"name": "naechster", "aliases": [], "aiPrompt": "Wo ist der nächste Spieler?"},
                  {"name": "wasfehlt", "aliases": [], "aiPrompt": "Welche Items fehlen mir für die {scope}? Antworte als kurze Liste."}
                ]""", "Jeder Eintrag: name, aliases, und ENTWEDER action ODER aiPrompt.\n"
                + "Erlaubte actions: missing_all, missing_layer, missing_near, status, toggle_similar, toggle_seethrough,\n"
                + "toggle_hud, toggle_layerlist, reload, open_config, profile:<name>.\n"
                + "Platzhalter in aiPrompt: {scope} (aktuelle Ebene oder Schematic), {args} (Text nach dem Befehl).");

        // =================================================================== HOTKEYS
        section("hotkeys", "Hotkeys (nur aktiv, wenn kein Menü/Chat offen ist).");
        hotkey("toggleSimilar", "LEFT_ALT+J", "toggle", "Feature 1: Ähnliche Blöcke akzeptieren an/aus");
        hotkey("toggleSeeThrough", "LEFT_ALT+K", "toggle", "Feature 5: Fehler durch Blöcke sehen an/aus");
        hotkey("toggleHud", "LEFT_ALT+H", "toggle", "Gesamtes HUD des Mods an/aus");
        hotkey("toggleLayerList", "LEFT_ALT+L", "toggle", "Feature 4: Ebenen-Fehlliste an/aus");
        hotkey("cycleProfile", "LEFT_ALT+P", "toggle", "Nächstes Config-Profil");
        hotkey("reloadConfig", "LEFT_ALT+R", "toggle", "Config neu laden");
        hotkey("openConfig", "LEFT_ALT+O", "toggle", "Einstellungs-Fenster öffnen");
        hotkey("missingList", "LEFT_ALT+M", "toggle", "Fehlliste (Ebene oder Umgebung) im Chat anzeigen");
        hotkey("pushToTalk", "LEFT_ALT+V", "hold", "Gedrückt halten und sprechen – die KI bekommt deine Frage per Stimme");
        hotkey("hudEditor", "LEFT_ALT+U", "toggle", "HUD-Editor öffnen (Anzeigen verschieben und vergrößern)");
        hotkey("toggleListScope", "LEFT_ALT+B", "toggle", "Materialliste umschalten: aktuelle Ebene <-> ganze Schematic");
        hotkey("stopSpeaking", "LEFT_ALT+X", "toggle", "Sprachausgabe sofort stoppen");

        // =================================================================== FARBEN & TEXTE
        section("colors", "Farben im Format #RRGGBB oder #AARRGGBB");
        color("colors.correct", "#55FF55", "Richtig");
        color("colors.similar", "#A8FFA8", "Ähnlich (akzeptiert)");
        color("colors.wrong", "#FF5555", "Falsch");
        color("colors.missing", "#55AAFF", "Fehlt");
        color("colors.extra", "#FF55FF", "Zu viel (Block steht, wo Luft geplant ist)");
        color("colors.warning", "#FFFF55", "Warnung");
        color("colors.text", "#FFFFFF", "Normaler Text");
        color("colors.neutral", "#AAAAAA", "Nebeninfo");
        color("colors.hotbarHighlight", "#C055FF55", "Rahmen um passenden Hotbar-Slot");
        color("colors.hotbarHighlightFill", "#4055FF55", "Hintergrund des passenden Hotbar-Slots");
        color("colors.similarItem", "#FFFF55", "Text für Items, die nur ähnlich (nicht exakt) passen");
        color("colors.similarItemHighlight", "#C0FFFF55", "Rahmen um Slots mit nur ähnlichem Item (gelb)");
        color("colors.similarItemHighlightFill", "#40FFFF55", "Hintergrund von Slots mit nur ähnlichem Item");
        bool("hud.neededItem.highlightInventory", true, "Passende Slots auch im geöffneten Inventar markieren (grün = exakt, gelb = ähnlich).");
        bool("hud.neededItem.highlightLayerNeeds", true, "Im Inventar zusätzlich alle Items markieren, die in der aktuellen Ebene noch fehlen.");

        section("templates", "Textvorlagen. Platzhalter in geschweiften Klammern.\n"
                + "Allgemein: {soll}, {ist}, {abweichungen}, {block}, {props}, {status}, {item}, {slot}, {need}, {have}, {fehl}, {layer}, {count}, {result}, {pos}");
        str("templates.status", "{status}", "Statuszeile, z. B. 'Typ ok, Richtung falsch'.");
        str("templates.soll", "Soll: {block}{props}", "Soll-Zeile. {props} wird pro Property einzeln eingefärbt.");
        str("templates.ist", "Ist: {block}{props}", "Ist-Zeile.");
        str("templates.diff", "Abweichungen: {abweichungen}", "Zeile mit allen Abweichungen.");
        str("templates.compact", "Soll: {soll} / Ist: {ist}", "Kompakte Einzeile (Zeilen-ID 'compact').");
        str("templates.propsPrefix", ", ", "Text vor der ersten Property.");
        str("templates.propFormat", "{name}: {value}", "Format einer Property.");
        str("templates.propSeparator", ", ", "Trenner zwischen Properties.");
        str("templates.preview", "Vorschau: {result}", "Platzier-Vorschau (Feature 2).");
        str("templates.neededHotbar", "{item} (Hotbar {slot})", "Feature 3: Item in der Hotbar.");
        str("templates.neededInventory", "{item} (Inventar, Slot {slot})", "Feature 3: Item nur im Inventar.");
        str("templates.neededMissing", "{item} fehlt", "Feature 3: Item nicht vorhanden.");
        str("templates.layerHeader", "{layer}: {count} Fehler", "Feature 4: Kopfzeile.");
        str("templates.layerLine", "{item} {have}/{need}{fehltext}", "Feature 4: Zeile. {fehltext} wird nur bei Fehlbetrag gefüllt.");
        str("templates.layerShortfall", " (-{fehl})", "Feature 4: Text für Fehlbetrag.");
        str("templates.chatMissingLine", "{need}x {item} (hast {have}){fehltext}", "Fehlliste im Chat (/missing).");

        // =================================================================== HUD
        section("hud", "HUD-Grundeinstellungen");
        bool("hud.enabled", true, "Gesamtes HUD des Mods.");
        bool("hud.textShadow", true, "Text mit Schatten.");
        bool("hud.realtime", true, "Anzeige jedes Bild aktualisieren (folgt dem Blick sofort). Aus = alle updateIntervalTicks.");
        integer("hud.updateIntervalTicks", 1, 1, 40, "Wie oft (in Ticks) der angeschaute Block neu bewertet wird. 20 Ticks = 1 Sekunde.");
        hudElement("lookedAt", "Feature 2: Soll/Ist des angeschauten Blocks", true, "bottom_left", 4, -4, 8);
        list("hud.lookedAt.lines", "Reihenfolge/Sichtbarkeit der Zeilen: status, soll, ist, diff, zustand (gepowert/ausgefahren/an …), compact, pos", "status", "soll", "ist", "diff", "zustand");
        bool("hud.lookedAt.onlyInSchematic", true, "Nur anzeigen, wenn der Block zur Schematic gehört (oder dort stört).");
        bool("hud.lookedAt.onlyWrongPropsRed", true, "Nur falsche Properties rot färben (sonst ganze Zeile).");
        bool("hud.lookedAt.showAllProps", false, "Auch ignorierte Properties anzeigen.");
        hudElement("preview", "Feature 2: Platzier-Vorschau (vor dem Platzieren)", true, "bottom_left", 4, -80, 3);
        bool("hud.preview.onlyWithBlockInHand", true, "Nur anzeigen, wenn ein Block in der Hand ist.");
        hudElement("neededItem", "Feature 3: Benötigtes Item", true, "bottom_center", 0, -60, 4);
        bool("hud.neededItem.highlightHotbar", true, "Passenden Hotbar-Slot einrahmen.");
        bool("hud.neededItem.onlyWhenIncorrect", true, "Nur anzeigen, wenn der angeschaute Block fehlt oder falsch ist.");
        bool("hud.neededItem.autoSwitch", false, "Automatisch auf den passenden Hotbar-Slot wechseln.");
        integer("hud.neededItem.autoSwitchDelayTicks", 0, 0, 40, "Wartezeit in Ticks (1 Tick = 50 ms), bevor automatisch gewechselt wird. 0 = sofort.");
        hudElement("layerList", "Feature 4: Fehlliste der aktuellen Ebene", true, "top_left", 4, 40, 12);
        enm("hud.layerList.scope", "layer", "Was die Liste zeigt: layer = nur die aktuelle Litematica-Ebene, all = die ganze Schematic (insgesamt).\n"
                + "Umschalten: Hotkey (Standard Alt+B), /lx liste ebene|gesamt oder Knopf in den Einstellungen.", "layer", "all");
        integer("hud.layerList.allUpdateIntervalMs", 1000, 200, 60000, "Neuberechnung im Modus 'insgesamt' (größer, weil mehr Blöcke).");
        bool("litematica.autoRefreshMaterialList", true, "Litematicas eigene Materialliste automatisch neu zählen, wenn du in der Schematic Blöcke setzt oder abbaust.");
        integer("litematica.materialListRefreshMs", 1000, 250, 60000, "Wartezeit nach der letzten Blockänderung, bevor Litematicas Materialliste neu gezählt wird.");
        bool("hud.layerList.onlyInLayerMode", true, "Nur zeigen, wenn Litematica auf 'Einzelne Ebene' oder 'Ebenenbereich' steht.");
        enm("hud.layerList.sort", "shortfall", "Sortierung: shortfall (Fehlbetrag), amount (Menge), name", "shortfall", "amount", "name");
        integer("hud.layerList.updateIntervalMs", 400, 100, 60000, "Gedrosselte Neuberechnung in Millisekunden.");
        bool("hud.layerList.includeWrongState", false, "Falsch gedrehte Blöcke als zusätzlichen Materialbedarf zählen (Standard aus: beim Abbauen bekommst du das Item zurück).");
        bool("hud.layerList.includeWrongType", true, "Falsche Blöcke mitzählen.");
        hudElement("status", "Kleine Statusanzeige (aktive Modi, Profil)", true, "top_right", -4, 4, 4);
        hudElement("aiAnswer", "Feature 6: KI-Antwort im HUD", true, "middle_right", -4, 0, 14);
        integer("hud.aiAnswer.showSeconds", 30, 3, 600, "Wie lange die Antwort sichtbar bleibt.");
        integer("hud.aiAnswer.wrapWidth", 220, 80, 600, "Zeilenumbruch-Breite in Pixeln.");

        // =================================================================== FEATURE 1
        section("compare", "Vergleichslogik Soll (Schematic) gegen Ist (Welt) – gilt für ALLE Anzeigen.");
        bool("compare.similar.enabled", true, "Feature 1: Ähnliche Blöcke akzeptieren (Typ wird gelockert, Properties bleiben streng).");
        bool("compare.similar.solidBlocks", true, "Alle soliden Vollblöcke, die Redstone leiten und keine eigenen Redstone-Funktionen haben, gelten untereinander als gleichwertig (Glas, Eis, Laub usw. nie).");
        list("compare.similar.solidBlacklist", "Diese Blöcke/Tags zählen NIE als 'solider Block' (zusätzlich zur automatischen Redstone-/BlockEntity-Prüfung).",
                "minecraft:observer", "minecraft:piston", "minecraft:sticky_piston", "minecraft:redstone_block", "minecraft:target",
                "minecraft:dispenser", "minecraft:dropper", "minecraft:note_block", "minecraft:tnt", "minecraft:redstone_lamp",
                "minecraft:slime_block", "minecraft:honey_block", "minecraft:crafter", "minecraft:spawner", "#minecraft:leaves",
                "#minecraft:shulker_boxes", "minecraft:budding_amethyst");
        list("compare.similar.solidAllowMissingProperties", "Properties, die beim Tausch zweier solider Blöcke fehlen dürfen (z. B. \"axis\" für Stamm -> Stein).");
        list("compare.ignoredProperties", "Diese Properties werden NIE verglichen. waterlogged ist absichtlich NICHT drin, kann aber ergänzt werden.",
                "powered", "lit", "age", "distance", "persistent", "snowy");
        bool("compare.ignoredPropertiesAlsoWhenExact", true, "Die Ignorierliste auch dann anwenden, wenn 'Ähnliche Blöcke' aus ist.");
        list("compare.orientationProperties", "Was als 'Ausrichtung' gilt (für propertyMode orientation_only).",
                "facing", "axis", "rotation", "orientation", "half", "type", "shape", "face", "hinge", "attachment", "horizontal_facing", "vertical_direction");
        bool("compare.chestIgnoreFacing", true, "Truhen: Blickrichtung egal – nur einzeln/doppelt und die Lage der zweiten Hälfte zählen.");
        bool("compare.reportExtra", true, "Blöcke, wo die Schematic Luft hat, als Fehler ('zu viel') melden.");
        json("compare.groups", """
                [
                  {"name": "Treppen", "members": ["#minecraft:stairs"], "propertyMode": "strict"},
                  {"name": "Stufen", "members": ["#minecraft:slabs"], "propertyMode": "strict"},
                  {"name": "Holzzäune", "members": ["#minecraft:wooden_fences"], "propertyMode": "strict"},
                  {"name": "Zauntore", "members": ["#minecraft:fence_gates"], "propertyMode": "strict"},
                  {"name": "Mauern", "members": ["#minecraft:walls"], "propertyMode": "strict"},
                  {"name": "Holztüren", "members": ["#minecraft:wooden_doors"], "propertyMode": "strict"},
                  {"name": "Holzfalltüren", "members": ["#minecraft:wooden_trapdoors"], "propertyMode": "strict"},
                  {"name": "Schilder", "members": ["#minecraft:standing_signs", "#minecraft:wall_signs"], "propertyMode": "strict"},
                  {"name": "Hängeschilder", "members": ["#minecraft:ceiling_hanging_signs", "#minecraft:wall_hanging_signs"], "propertyMode": "strict"},
                  {"name": "Holzknöpfe", "members": ["#minecraft:wooden_buttons"], "propertyMode": "strict"},
                  {"name": "Holzdruckplatten", "members": ["#minecraft:wooden_pressure_plates"], "propertyMode": "strict"},
                  {"name": "Stämme", "members": ["#minecraft:logs"], "propertyMode": "strict"},
                  {"name": "Bretter", "members": ["#minecraft:planks"], "propertyMode": "strict"},
                  {"name": "Wolle", "members": ["#minecraft:wool"], "propertyMode": "strict"},
                  {"name": "Teppiche", "members": ["#minecraft:wool_carpets"], "propertyMode": "strict"},
                  {"name": "Terrakotta", "members": ["#minecraft:terracotta"], "propertyMode": "strict"},
                  {"name": "Glas", "members": ["#minecraft:impermeable"], "propertyMode": "strict"},
                  {"name": "Glasscheiben", "members": [GLASS_PANES], "propertyMode": "strict"},
                  {"name": "Beton", "members": [CONCRETE], "propertyMode": "strict"},
                  {"name": "Laub", "members": ["#minecraft:leaves"], "propertyMode": "strict"},
                  {"name": "Eis", "members": ["minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice"], "propertyMode": "strict"}
                ]""".replace("GLASS_PANES", glassPanes()).replace("CONCRETE", concrete()),
                "Äquivalenzgruppen für Feature 1. members: Block-IDs oder Tags (#minecraft:stairs).\n"
                        + "propertyMode: strict (alle Properties außer Ignorierliste), orientation_only (nur Ausrichtung/Position), ignore (keine).\n"
                        + "Optional: allowMissingProperties: [\"axis\"] = diese dürfen beim Ist-Block fehlen. Optional: enabled: false.");

        // =================================================================== SCAN / CACHE
        section("scan", "Hintergrund-Berechnung (läuft nie im Render-Thread)");
        integer("scan.nearRadius", 48, 8, 256, "Radius um den Spieler für 'Umgebung' (Durchsicht, /missing near).");
        integer("scan.maxBlocksPerScan", 3000000, 10000, 50000000, "Obergrenze geprüfter Positionen pro Durchlauf (Schutz vor Rucklern).");
        integer("scan.minIntervalMs", 150, 50, 60000, "Mindestabstand zwischen zwei automatischen Scans.");
        integer("scan.fullRescanSeconds", 10, 1, 600, "Zusätzlich spätestens nach so vielen Sekunden neu prüfen (falls eine Änderung nicht gemeldet wurde).");

        // =================================================================== FEATURE 5
        section("seeThrough", "Feature 5: Fehler durch Blöcke hindurch sehen");
        bool("seeThrough.active", false, "Modus beim Start aktiv (umschalten per Hotkey).");
        enm("seeThrough.backend", "own", "own = eigener Renderer (nur Fehler, mit Reichweite/Fade/Limit), litematica = Litematicas eigene\n"
                + "'Overlay durch Blöcke'-Option umschalten, both = beides.", "own", "litematica", "both");
        integer("seeThrough.range", 48, 4, 256, "Reichweite in Blöcken.");
        integer("seeThrough.maxBoxes", 300, 1, 5000, "Maximal gezeichnete Kästen (die nächsten zuerst).");
        integer("seeThrough.fadeStart", 24, 0, 256, "Ab dieser Entfernung wird ausgeblendet.");
        dbl("seeThrough.minAlpha", 0.2, 0.0, 1.0, "Minimale Deckkraft am Rand der Reichweite.");
        dbl("seeThrough.lineWidth", 1.0, 0.5, 6.0, "Linienstärke in GUI-Pixeln.");
        enm("seeThrough.style", "wire", "wire = Umrisslinien des Blocks, box = 2D-Rahmen, marker = kleines Quadrat", "wire", "box", "marker");
        bool("seeThrough.onlyCurrentLayer", true, "Im Ebenen-Modus von Litematica nur Fehler der aktuellen Ebene zeigen.");
        bool("seeThrough.showMissing", true, "Fehlende Blöcke zeigen.");
        bool("seeThrough.showWrongType", true, "Falsche Blöcke zeigen.");
        bool("seeThrough.showWrongState", true, "Falsch ausgerichtete Blöcke zeigen.");
        bool("seeThrough.showExtra", false, "Überzählige Blöcke zeigen.");
        integer("seeThrough.updateIntervalMs", 300, 100, 60000, "Wie oft die Fehlerliste neu berechnet wird.");
        color("seeThrough.colorMissing", "#FF55AAFF", "Farbe fehlend");
        color("seeThrough.colorWrongType", "#FFFF5555", "Farbe falscher Block");
        color("seeThrough.colorWrongState", "#FFFFAA00", "Farbe falsch ausgerichtet");
        color("seeThrough.colorExtra", "#FFFF55FF", "Farbe zu viel");

        // =================================================================== FEATURE 6 KI
        section("ai", "Feature 6: KI-Assistent. Standardmäßig AUS. Sendet Spieldaten an einen externen Dienst!");
        bool("ai.enabled", true, "KI-Assistent aktivieren. Beim ersten Mal erscheint ein Datenschutz-Hinweis.");
        enm("ai.provider", "ollama", "Anbieter: anthropic, openai, gemini, ollama, openai_compatible (eigener Server mit OpenAI-Schnittstelle)",
                "anthropic", "openai", "gemini", "ollama", "openai_compatible");
        str("ai.model", "llama3.2", "Modellname. Leer = Standard des Anbieters (anthropic: claude-haiku-5-5, ollama: llama3.1, sonst bitte eintragen).");
        str("ai.endpoint", "", "Eigene Endpoint-URL. Leer = Standard des Anbieters. Ollama: http://localhost:11434/v1/chat/completions");
        str("ai.apiKeyEnvVar", "", "Optional: Name einer Umgebungsvariable, aus der der API-Key gelesen wird (statt secrets.json5).");
        dbl("ai.temperature", 0.3, 0.0, 2.0, "Kreativität (0 = sachlich).");
        bool("ai.sendTemperature", true, "Temperatur mitsenden (manche Modelle lehnen sie ab).");
        integer("ai.maxTokens", 700, 50, 16000, "Maximale Antwortlänge in Tokens.");
        integer("ai.timeoutSeconds", 40, 5, 300, "Zeitlimit pro Anfrage.");
        integer("ai.rateLimitPerMinute", 6, 1, 120, "Maximale Fragen pro Minute.");
        integer("ai.maxToolCalls", 5, 0, 30, "Maximale Tool-Aufrufe pro Frage.");
        enm("ai.persona", "maedchen", "Wer redet mit dir: maedchen (Frauenstimme) oder junge (Männerstimme).", "maedchen", "junge");
        enm("ai.personality", "nett", "Art: nett (herzlich), frech (neckt im Spaß) oder derb (roastet mit Schimpfwörtern – nur für Erwachsene).", "nett", "frech", "derb");
        str("ai.nameGirl", "Mia", "Name, wenn persona = maedchen.");
        str("ai.nameBoy", "Max", "Name, wenn persona = junge.");
        integer("ai.memoryTurns", 6, 0, 30, "Wie viele vorherige Fragen/Antworten die KI sich im Gespräch merkt (0 = keine).");
        str("ai.answerLanguage", "Deutsch", "Antwortsprache.");
        enm("ai.answerLength", "kurz", "Antwortlänge: kurz, mittel, ausfuehrlich", "kurz", "mittel", "ausfuehrlich");
        bool("ai.showTokenUsage", false, "Verbrauchte Tokens nach jeder Antwort anzeigen.");
        enm("ai.output", "chat", "Antwort anzeigen in: chat (nur lokal), hud, both", "chat", "hud", "both");
        bool("ai.lockWorldToolsInMultiplayer", false, "Spieler- und Umgebungs-Tools im Multiplayer sperren (empfohlen!).");
        integer("ai.topN", 15, 1, 100, "Maximale Einträge in Tool-Ergebnissen.");
        integer("ai.playerRadius", 64, 4, 256, "Radius für get_nearby_players.");
        integer("ai.entityRadius", 48, 4, 256, "Radius für get_nearby_entities.");
        integer("ai.findBlockRadius", 48, 4, 192, "Radius für find_block.");
        integer("ai.findBlockMaxMs", 250, 20, 5000, "Rechenzeit-Grenze für find_block.");
        integer("ai.chatLines", 10, 1, 50, "Anzahl Zeilen für get_recent_chat.");
        section("voice", "Stimme: Frauenstimme liest KI-Antworten vor (Piper), Sprechen per Taste (Whisper). Alles lokal, kostenlos.\n"
                + "Einmalig: /lx stimme installieren (lädt ca. 250 MB, passiert beim ersten Weltbeitritt automatisch).");
        bool("voice.ttsEnabled", true, "KI-Antworten vorlesen.");
        str("voice.voiceName", "de_DE-kerstin-low", "Stimme. Frauenstimmen: de_DE-kerstin-low, de_DE-ramona-low, de_DE-eva_k-x_low. Wechseln: /lx stimme <name>");
        str("voice.maleVoice", "de_DE-thorsten-medium", "Männerstimme (für persona = junge). Weitere: de_DE-thorsten-high, de_DE-karlsson-low");
        integer("voice.speaker", -1, -1, 999, "Sprecher-Nummer bei Stimmen mit mehreren Sprechern (z. B. de_DE-mls-medium). -1 = Standard. Ausprobieren: /lx stimme sprecher <Nr>");
        dbl("voice.expressiveness", 0.6, 0.0, 1.2, "Lebendigkeit der Stimme (0 = monoton, 0.6 = natürlich, 1 = sehr lebhaft).");
        dbl("voice.speed", 1.1, 0.5, 2.0, "Sprechtempo (1.0 = normal, größer = schneller).");
        dbl("voice.volume", 1.0, 0.05, 1.0, "Lautstärke.");
        integer("voice.maxSpeakChars", 600, 50, 5000, "Höchstens so viele Zeichen vorlesen.");
        bool("voice.sttEnabled", true, "Mit der Stimme fragen (Taste gedrückt halten, siehe hotkeys.pushToTalk).");
        enm("voice.sttModel", "small", "Spracherkennungs-Modell: tiny (schnell), base (Standard), small (genauer, langsamer)", "tiny", "base", "small");
        str("voice.micName", "", "Mikrofon (leer = Windows-Standard). Liste: /lx stimme mikros, auswählen: /lx stimme mikro <Nummer>");
        str("voice.sttLanguage", "de", "Sprache der Spracherkennung (de, en, auto).");
        integer("voice.maxRecordSeconds", 20, 2, 120, "Maximale Aufnahmelänge.");
        bool("voice.autoInstall", true, "Fehlende Sprachprogramme beim ersten Weltbeitritt automatisch herunterladen.");

        section("ai.privacy", "Was darf an die KI gesendet werden?");
        enm("ai.privacy.coordinates", "exact", "Koordinaten: exact, rounded, hidden", "exact", "rounded", "hidden");
        integer("ai.privacy.roundTo", 10, 2, 1000, "Rundung bei 'rounded'.");
        bool("ai.privacy.anonymizePlayerNames", false, "Spielernamen durch 'Spieler 1', 'Spieler 2' ersetzen.");
        bool("ai.privacy.sendServerAddress", false, "Servername/-adresse an die KI senden.");
        bool("ai.privacy.sendInventory", true, "Inventarinhalt senden.");
        bool("ai.privacy.sendSchematicNames", true, "Namen der Schematics senden.");
        bool("ai.privacy.sendCustomNames", false, "Umbenannte Item-/Mob-Namen senden (als unvertraute Daten markiert).");
        section("ai.tools", "Tools einzeln an/aus. Standard: nur Bau-Tools an.");
        aiTool("get_missing_blocks", true, "Fehlende/falsche Blöcke nach Item gezählt.");
        aiTool("get_layer_status", true, "Layer-Modus, Ebene, Fortschritt.");
        aiTool("get_looked_at_block", true, "Soll/Ist des angeschauten Blocks.");
        aiTool("get_schematic_info", true, "Name, Größe, Position, Fortschritt der Schematic(s).");
        aiTool("get_material_list", true, "Gesamtbedarf im Vergleich zum Inventar.");
        aiTool("get_inventory", true, "Inventar (Hotbar, Offhand, Rüstung).");
        aiTool("get_player_status", true, "Eigene Position, Blickrichtung, Gesundheit ...");
        aiTool("get_nearby_players", true, "Geladene Spieler in Reichweite. ACHTUNG: auf vielen Servern als Cheat gewertet!");
        aiTool("get_online_players", true, "Namen aus der Tab-Liste.");
        aiTool("get_world_info", true, "Zeit, Wetter, Biom, Dimension ...");
        aiTool("get_nearby_entities", true, "Mobs/Items/Tiere in Reichweite. ACHTUNG: auf vielen Servern als Cheat gewertet!");
        aiTool("find_block", true, "Blocktyp in geladenen Chunks suchen. ACHTUNG: auf vielen Servern als Cheat gewertet!");
        aiTool("get_server_info", true, "Server, Ping, Spielerzahl, Version.");
        aiTool("get_recent_chat", true, "Letzte Chatzeilen (als unvertraute Daten).");
    }
}
