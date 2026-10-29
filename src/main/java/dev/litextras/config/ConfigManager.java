package dev.litextras.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.litextras.LitematicaExtras;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Lädt, prüft, ergänzt, migriert und speichert die Config. Verwaltet Profile, Laufzeit-Umschalter
 * (Hotkeys), Export/Import und den getrennten Speicher für API-Keys.
 *
 * Effektiver Wert = Laufzeit-Umschalter  >  aktives Profil  >  config.json5  >  Standardwert.
 */
public final class ConfigManager {

    public static final Set<String> HARD_SAFE_PROFILE_NAMES = Set.of("sicher", "safe");

    private final Path dir;
    private final Path mainFile;
    private final Path secretsFile;
    private final Path stateFile;
    private final Path profilesDir;
    private final Path exportDir;

    private final Map<String, JsonElement> main = new LinkedHashMap<>();
    private final Map<String, JsonElement> profile = new LinkedHashMap<>();
    private final Map<String, JsonElement> runtime = new LinkedHashMap<>();
    private volatile Map<String, JsonElement> effective = new LinkedHashMap<>();

    private JsonObject secrets = new JsonObject();
    private JsonObject state = new JsonObject();
    private final List<String> lastMessages = new ArrayList<>();
    private long generation = 0;

    public ConfigManager() {
        this.dir = FabricLoader.getInstance().getConfigDir().resolve("litematica_extras");
        this.mainFile = dir.resolve("config.json5");
        this.secretsFile = dir.resolve("secrets.json5");
        this.stateFile = dir.resolve("state.json");
        this.profilesDir = dir.resolve("profiles");
        this.exportDir = dir.resolve("export");
    }

    public Path dir() { return dir; }
    public Path exportDir() { return exportDir; }
    /** Wird bei jeder Änderung erhöht – Caches vergleichen damit, ob sie neu rechnen müssen. */
    public long generation() { return generation; }
    public List<String> lastMessages() { return List.copyOf(lastMessages); }

    // ================================================================== Laden
    public synchronized List<String> load() {
        lastMessages.clear();
        runtime.clear();
        try {
            Files.createDirectories(dir);
            Files.createDirectories(profilesDir);
            Files.createDirectories(exportDir);
        } catch (IOException e) {
            lastMessages.add("Config-Ordner konnte nicht angelegt werden: " + e.getMessage());
        }
        loadState();
        loadSecrets();
        loadMain();
        ensureDefaultProfiles();
        loadProfile(activeProfileName());
        rebuild();
        return List.copyOf(lastMessages);
    }

    private void loadMain() {
        Map<String, JsonElement> loaded = new LinkedHashMap<>();
        boolean needsRewrite = false;
        if (!Files.exists(mainFile)) {
            needsRewrite = true;
            lastMessages.add("Neue Config-Datei angelegt: " + mainFile.getFileName());
        } else {
            try {
                JsonObject root = Json5.parse(Files.readString(mainFile, StandardCharsets.UTF_8));
                int version = root.has("configVersion") && root.get("configVersion").isJsonPrimitive()
                        ? root.get("configVersion").getAsInt() : 0;
                if (version < ConfigSpec.CONFIG_VERSION) {
                    migrate(root, version);
                    needsRewrite = true;
                    lastMessages.add("Config von Version " + version + " auf " + ConfigSpec.CONFIG_VERSION + " migriert.");
                }
                for (ConfigSpec.Entry e : ConfigSpec.entries().values()) {
                    JsonElement raw = Json5.getPath(root, e.key());
                    if (raw == null) {
                        needsRewrite = true;
                        continue;
                    }
                    JsonElement ok = validate(e, raw, "config.json5");
                    if (ok != null) loaded.put(e.key(), ok);
                }
            } catch (Json5.ParseException ex) {
                lastMessages.add("Fehler in config.json5: " + ex.getMessage() + " – es werden die bisherigen Werte bzw. Standardwerte benutzt. Die Datei wurde NICHT überschrieben.");
                if (!main.isEmpty()) return; // letzte gute Werte behalten
                main.clear();
                return;
            } catch (IOException ex) {
                lastMessages.add("config.json5 konnte nicht gelesen werden: " + ex.getMessage());
                return;
            }
        }
        main.clear();
        main.putAll(loaded);
        if (needsRewrite) {
            int added = 0;
            Map<String, JsonElement> full = new LinkedHashMap<>();
            full.put("configVersion", new JsonPrimitive(ConfigSpec.CONFIG_VERSION));
            for (ConfigSpec.Entry e : ConfigSpec.entries().values()) {
                JsonElement v = main.get(e.key());
                if (v == null) { v = e.def().deepCopy(); added++; }
                full.put(e.key(), v);
            }
            writeFile(mainFile, Json5.write(full, HEADER));
            if (added > 0 && Files.exists(mainFile)) lastMessages.add(added + " fehlende Einträge mit Standardwerten ergänzt.");
        }
    }

    private static final String HEADER = """
            Litematica Extras – Einstellungen (JSON5: Kommentare mit // sind erlaubt)
            Nach dem Speichern im Spiel '/lx reload' eingeben oder den Hotkey (Standard Alt+R) drücken.
            Fehlende Einträge werden automatisch ergänzt. Bei Tippfehlern erscheint eine Meldung mit Zeile.
            Profile (z. B. 'sicher') liegen in profiles/ und überschreiben nur die dort eingetragenen Werte.
            API-Keys gehören NICHT hierher, sondern in secrets.json5 (wird nie exportiert).""";

    /** Platz für spätere Migrationen: alte Schlüssel umbenennen usw. */
    private void migrate(JsonObject root, int fromVersion) {
        // Version 0 -> 1: nichts umzubenennen (erste Version).
        root.addProperty("configVersion", ConfigSpec.CONFIG_VERSION);
    }

    /** Prüft Typ und Grenzen. Gibt null zurück (= Standardwert) und protokolliert eine Meldung bei Fehlern. */
    private JsonElement validate(ConfigSpec.Entry e, JsonElement raw, String source) {
        String where = source + ": '" + e.key() + "'";
        try {
            switch (e.type()) {
                case BOOL -> {
                    if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean()) return raw;
                    lastMessages.add(where + " erwartet true oder false – Standardwert " + Json5.compact(e.def()) + " benutzt.");
                    return null;
                }
                case INT, DOUBLE -> {
                    if (!(raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber())) {
                        lastMessages.add(where + " erwartet eine Zahl – Standardwert " + Json5.compact(e.def()) + " benutzt.");
                        return null;
                    }
                    double d = raw.getAsDouble();
                    if (d < e.min() || d > e.max()) {
                        double c = Math.max(e.min(), Math.min(e.max(), d));
                        lastMessages.add(where + " liegt außerhalb " + e.min() + "–" + e.max() + " – auf " + c + " begrenzt.");
                        return e.type() == ConfigSpec.Type.INT ? new JsonPrimitive((int) Math.round(c)) : new JsonPrimitive(c);
                    }
                    return e.type() == ConfigSpec.Type.INT ? new JsonPrimitive((int) Math.round(d)) : raw;
                }
                case STRING -> {
                    if (raw.isJsonPrimitive()) return new JsonPrimitive(raw.getAsString());
                    lastMessages.add(where + " erwartet einen Text – Standardwert benutzt.");
                    return null;
                }
                case ENUM -> {
                    if (raw.isJsonPrimitive()) {
                        String s = raw.getAsString().toLowerCase(Locale.ROOT);
                        if (e.options().contains(s)) return new JsonPrimitive(s);
                    }
                    lastMessages.add(where + " muss einer von " + e.options() + " sein – Standardwert " + Json5.compact(e.def()) + " benutzt.");
                    return null;
                }
                case COLOR -> {
                    if (raw.isJsonPrimitive() && Colors.parse(raw.getAsString()) != null) return raw;
                    lastMessages.add(where + " ist keine gültige Farbe (#RRGGBB oder #AARRGGBB) – Standardwert benutzt.");
                    return null;
                }
                case STRING_LIST -> {
                    if (raw.isJsonArray()) {
                        JsonArray out = new JsonArray();
                        for (JsonElement x : raw.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
                        return out;
                    }
                    lastMessages.add(where + " erwartet eine Liste wie [\"a\", \"b\"] – Standardwert benutzt.");
                    return null;
                }
                case JSON -> {
                    if (raw.isJsonArray() == e.def().isJsonArray() && raw.isJsonObject() == e.def().isJsonObject()) return raw;
                    lastMessages.add(where + " hat das falsche Format (erwartet " + (e.def().isJsonArray() ? "Liste [...]" : "Objekt {...}") + ") – Standardwert benutzt.");
                    return null;
                }
            }
        } catch (Exception ex) {
            lastMessages.add(where + ": " + ex.getMessage() + " – Standardwert benutzt.");
        }
        return null;
    }

    // ================================================================== Profile
    public String activeProfileName() {
        return state.has("activeProfile") ? state.get("activeProfile").getAsString() : "bauen";
    }

    public boolean isSafeProfile() {
        return HARD_SAFE_PROFILE_NAMES.contains(activeProfileName().toLowerCase(Locale.ROOT));
    }

    public List<String> profileNames() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(profilesDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json5"))
                    .forEach(p -> names.add(p.getFileName().toString().replace(".json5", "")));
        } catch (IOException ignored) {}
        names.sort(String::compareTo);
        return names;
    }

    public synchronized List<String> switchProfile(String name) {
        lastMessages.clear();
        if (!profileNames().contains(name)) {
            lastMessages.add("Profil '" + name + "' gibt es nicht. Vorhanden: " + profileNames());
            return List.copyOf(lastMessages);
        }
        state.addProperty("activeProfile", name);
        saveState();
        loadProfile(name);
        rebuild();
        lastMessages.add("Profil gewechselt: " + name);
        return List.copyOf(lastMessages);
    }

    public synchronized List<String> cycleProfile() {
        List<String> names = profileNames();
        if (names.isEmpty()) return List.of("Keine Profile vorhanden.");
        int i = names.indexOf(activeProfileName());
        return switchProfile(names.get((i + 1) % names.size()));
    }

    private void loadProfile(String name) {
        profile.clear();
        Path f = profilesDir.resolve(name + ".json5");
        if (!Files.exists(f)) return;
        try {
            JsonObject root = Json5.parse(Files.readString(f, StandardCharsets.UTF_8));
            for (ConfigSpec.Entry e : ConfigSpec.entries().values()) {
                JsonElement raw = Json5.getPath(root, e.key());
                if (raw == null) continue;
                JsonElement ok = validate(e, raw, "profiles/" + name + ".json5");
                if (ok != null) profile.put(e.key(), ok);
            }
        } catch (Json5.ParseException ex) {
            lastMessages.add("Fehler in profiles/" + name + ".json5: " + ex.getMessage() + " – Profil wird ignoriert.");
        } catch (IOException ex) {
            lastMessages.add("Profil " + name + " nicht lesbar: " + ex.getMessage());
        }
    }

    private void ensureDefaultProfiles() {
        writeProfileIfMissing("bauen", "Standardprofil zum Bauen. Leer = alle Werte aus config.json5.", Map.of());
        Map<String, JsonElement> mp = new LinkedHashMap<>();
        mp.put("ai.lockWorldToolsInMultiplayer", new JsonPrimitive(true));
        mp.put("ai.privacy.coordinates", new JsonPrimitive("rounded"));
        mp.put("ai.privacy.anonymizePlayerNames", new JsonPrimitive(true));
        mp.put("hud.neededItem.autoSwitch", new JsonPrimitive(false));
        writeProfileIfMissing("multiplayer_sicher", "Für Server: zurückhaltende KI-Daten, kein Auto-Slotwechsel.", mp);
        Map<String, JsonElement> cr = new LinkedHashMap<>();
        cr.put("hud.neededItem.enabled", new JsonPrimitive(false));
        cr.put("compare.similar.enabled", new JsonPrimitive(false));
        writeProfileIfMissing("creative", "Creative: keine Item-Suche nötig, exakter Vergleich.", cr);
        Map<String, JsonElement> safe = new LinkedHashMap<>();
        safe.put("seeThrough.active", new JsonPrimitive(false));
        for (String t : new String[]{"get_nearby_players", "get_online_players", "get_nearby_entities", "find_block", "get_recent_chat"}) {
            safe.put("ai.tools." + t + ".enabled", new JsonPrimitive(false));
        }
        writeProfileIfMissing("sicher", "Profil 'sicher': Spieler-/Umgebungs-Tools und Durchsicht-Modus sind AUS.\n"
                + "Das wird vom Mod zusätzlich fest erzwungen, auch wenn hier etwas anderes steht.", safe);
    }

    private void writeProfileIfMissing(String name, String comment, Map<String, JsonElement> values) {
        Path f = profilesDir.resolve(name + ".json5");
        if (Files.exists(f)) return;
        writeFile(f, Json5.write(values, "Profil '" + name + "'. " + comment
                + "\nNur eingetragene Werte überschreiben config.json5. Gleiche Schlüssel wie dort, z. B. \"hud\": { \"lookedAt\": { \"scale\": 1.5 } }"));
    }

    // ================================================================== Laufzeit-Umschalter
    public synchronized boolean toggleRuntime(String key) {
        boolean now = !Cfg.bool(key);
        runtime.put(key, new JsonPrimitive(now));
        rebuild();
        return now;
    }

    public synchronized void setRuntime(String key, JsonElement value) {
        runtime.put(key, value);
        rebuild();
    }

    private void rebuild() {
        Map<String, JsonElement> eff = new LinkedHashMap<>();
        for (ConfigSpec.Entry e : ConfigSpec.entries().values()) {
            JsonElement v = runtime.get(e.key());
            if (v == null) v = profile.get(e.key());
            if (v == null) v = main.get(e.key());
            if (v == null) v = e.def();
            eff.put(e.key(), v);
        }
        if (isSafeProfile()) {
            // Fest erzwungen, nicht per Config abschaltbar
            eff.put("seeThrough.active", new JsonPrimitive(false));
            for (String t : new String[]{"get_nearby_players", "get_online_players", "get_nearby_entities", "find_block", "get_recent_chat"}) {
                eff.put("ai.tools." + t + ".enabled", new JsonPrimitive(false));
            }
        }
        effective = eff;
        generation++;
    }

    JsonElement raw(String key) { return effective.get(key); }

    // ================================================================== Secrets (API-Keys)
    private void loadSecrets() {
        if (!Files.exists(secretsFile)) {
            JsonObject keys = new JsonObject();
            keys.addProperty("anthropic", "");
            keys.addProperty("openai", "");
            keys.addProperty("gemini", "");
            keys.addProperty("openai_compatible", "");
            JsonObject root = new JsonObject();
            root.add("apiKeys", keys);
            secrets = root;
            String text = "// API-Keys für Feature 6. Diese Datei NIE weitergeben.\n"
                    + "// Sie wird nie exportiert, nie geloggt und nie an andere Dienste als den gewählten Anbieter gesendet.\n"
                    + "// Ollama braucht keinen Key.\n"
                    + new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
            writeFile(secretsFile, text);
            return;
        }
        try {
            secrets = Json5.parse(Files.readString(secretsFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            lastMessages.add("secrets.json5 ist fehlerhaft (" + e.getMessage() + ").");
            secrets = new JsonObject();
        }
    }

    /** Nur für den KI-Client. Gibt niemals Text zurück, der irgendwo angezeigt werden darf. */
    public String apiKey(String provider) {
        String env = Cfg.str("ai.apiKeyEnvVar");
        if (env != null && !env.isBlank()) {
            String v = System.getenv(env.trim());
            if (v != null && !v.isBlank()) return v.trim();
        }
        JsonElement keys = secrets.get("apiKeys");
        if (keys != null && keys.isJsonObject()) {
            JsonElement k = keys.getAsJsonObject().get(provider);
            if (k != null && k.isJsonPrimitive()) return k.getAsString().trim();
        }
        return "";
    }

    // ================================================================== State
    private void loadState() {
        try {
            if (Files.exists(stateFile)) state = Json5.parse(Files.readString(stateFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            state = new JsonObject();
        }
    }

    private void saveState() {
        writeFile(stateFile, new GsonBuilder().setPrettyPrinting().create().toJson(state));
    }

    public boolean aiPrivacyAcknowledged() {
        return state.has("aiPrivacyAcknowledged") && state.get("aiPrivacyAcknowledged").getAsBoolean();
    }

    public void acknowledgeAiPrivacy() {
        state.addProperty("aiPrivacyAcknowledged", true);
        saveState();
    }

    // ================================================================== Export / Import
    public synchronized String exportConfig(String name) {
        JsonObject out = new JsonObject();
        out.addProperty("litematicaExtrasExport", 1);
        out.addProperty("configVersion", ConfigSpec.CONFIG_VERSION);
        JsonObject cfg = new JsonObject();
        for (Map.Entry<String, JsonElement> e : main.entrySet()) cfg.add(e.getKey(), e.getValue());
        out.add("config", cfg);
        JsonObject profs = new JsonObject();
        for (String p : profileNames()) {
            try {
                profs.add(p, Json5.parse(Files.readString(profilesDir.resolve(p + ".json5"), StandardCharsets.UTF_8)));
            } catch (Exception ignored) {}
        }
        out.add("profiles", profs);
        stripSecrets(out); // API-Keys sind ohnehin nicht in der Config – doppelt abgesichert
        String file = (name == null || name.isBlank())
                ? "export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + ".json5"
                : sanitizeFileName(name) + (name.endsWith(".json5") ? "" : ".json5");
        Path target = exportDir.resolve(file);
        writeFile(target, "// Litematica Extras – Export (ohne API-Keys)\n" + new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out) + "\n");
        return target.toString();
    }

    public synchronized List<String> importConfig(String name) {
        List<String> msgs = new ArrayList<>();
        Path src = exportDir.resolve(sanitizeFileName(name) + (name.endsWith(".json5") ? "" : ".json5"));
        if (!Files.exists(src)) {
            msgs.add("Datei nicht gefunden: " + src + " (Dateien in den Ordner export/ legen).");
            return msgs;
        }
        try {
            JsonObject root = Json5.parse(Files.readString(src, StandardCharsets.UTF_8));
            stripSecrets(root);
            JsonObject cfg = root.has("config") && root.get("config").isJsonObject() ? root.getAsJsonObject("config") : new JsonObject();
            Map<String, JsonElement> full = new LinkedHashMap<>();
            full.put("configVersion", new JsonPrimitive(ConfigSpec.CONFIG_VERSION));
            int n = 0;
            lastMessages.clear();
            for (ConfigSpec.Entry e : ConfigSpec.entries().values()) {
                JsonElement raw = cfg.has(e.key()) ? cfg.get(e.key()) : Json5.getPath(cfg, e.key());
                JsonElement ok = raw == null ? null : validate(e, raw, src.getFileName().toString());
                if (ok != null) n++;
                full.put(e.key(), ok != null ? ok : (main.containsKey(e.key()) ? main.get(e.key()) : e.def()));
            }
            msgs.addAll(lastMessages);
            writeFile(mainFile, Json5.write(full, HEADER));
            if (root.has("profiles") && root.get("profiles").isJsonObject()) {
                for (Map.Entry<String, JsonElement> p : root.getAsJsonObject("profiles").entrySet()) {
                    if (!p.getValue().isJsonObject()) continue;
                    writeFile(profilesDir.resolve(sanitizeFileName(p.getKey()) + ".json5"),
                            "// Importiertes Profil\n" + new GsonBuilder().setPrettyPrinting().create().toJson(p.getValue()) + "\n");
                }
            }
            msgs.add(n + " Einstellungen importiert. Lade neu ...");
            msgs.addAll(load());
        } catch (Exception e) {
            msgs.add("Import fehlgeschlagen: " + e.getMessage());
        }
        return msgs;
    }

    private static void stripSecrets(JsonObject o) {
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (k.contains("apikey") || k.contains("api_key") || k.contains("secret") || k.contains("token") && !k.contains("maxtokens") && !k.contains("showtoken")) {
                remove.add(e.getKey());
            } else if (e.getValue().isJsonObject()) {
                stripSecrets(e.getValue().getAsJsonObject());
            }
        }
        remove.forEach(o::remove);
    }

    private static String sanitizeFileName(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.json5$", "");
    }

    private void writeFile(Path p, String text) {
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LitematicaExtras.LOG.error("Konnte {} nicht schreiben: {}", p.getFileName(), e.getMessage());
            lastMessages.add("Konnte " + p.getFileName() + " nicht schreiben: " + e.getMessage());
        }
    }
}
