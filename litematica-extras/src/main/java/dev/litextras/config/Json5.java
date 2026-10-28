package dev.litextras.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimaler JSON5-Leser/Schreiber auf Basis von Gson (liegt Minecraft bei, keine Zusatz-Bibliothek).
 * Lesen: Gson im "lenient"-Modus versteht // und /* *&#47; Kommentare, Namen ohne Anführungszeichen, einfache Anführungszeichen.
 * Schreiben: wir erzeugen die Datei selbst, damit jeder Eintrag seinen Kommentar bekommt.
 */
public final class Json5 {
    private Json5() {}

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    /** Fehler mit verständlicher Zeilenangabe. */
    public static final class ParseException extends Exception {
        public ParseException(String msg) { super(msg); }
    }

    @SuppressWarnings("deprecation")
    public static JsonObject parse(String text) throws ParseException {
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(true);
            JsonElement el = JsonParser.parseReader(reader);
            if (el == null || !el.isJsonObject()) throw new ParseException("Die Datei muss mit { beginnen und ein Objekt enthalten.");
            return el.getAsJsonObject();
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // Gson-Meldungen enthalten "line X column Y" -> auf Deutsch hervorheben
            m = m.replaceFirst("^[\\w.$]+(Exception|Error): ", "");
            m = m.replace("at line", "in Zeile").replace("column", "Spalte").replace("path", "Pfad");
            int idx = m.indexOf("See https://");
            if (idx > 0) m = m.substring(0, idx).trim();
            throw new ParseException(m);
        }
    }

    public static String compact(JsonElement e) { return COMPACT.toJson(e); }

    /** Wert an einem Punkt-Pfad lesen, z. B. "hud.lookedAt.scale". */
    public static JsonElement getPath(JsonObject root, String dotted) {
        String[] parts = dotted.split("\\.");
        JsonElement cur = root;
        for (String p : parts) {
            if (cur == null || !cur.isJsonObject()) return null;
            cur = cur.getAsJsonObject().get(p);
        }
        return cur;
    }

    // ------------------------------------------------------------------ Schreiben
    private static final class Node {
        final Map<String, Node> children = new LinkedHashMap<>();
        ConfigSpec.Entry entry;
        JsonElement value;
    }

    /**
     * Schreibt die übergebenen Werte (Punkt-Pfad -> Wert) in Spec-Reihenfolge als JSON5 mit Kommentaren.
     * Werte ohne Spec-Eintrag (z. B. "configVersion") werden vorne angehängt.
     */
    public static String write(Map<String, JsonElement> values, String headerComment) {
        Node root = new Node();
        Map<String, JsonElement> rest = new LinkedHashMap<>(values);
        for (ConfigSpec.Entry e : ConfigSpec.entries().values()) {
            if (!values.containsKey(e.key())) continue;
            insert(root, e.key(), e, values.get(e.key()));
            rest.remove(e.key());
        }
        Node top = new Node();
        for (Map.Entry<String, JsonElement> r : rest.entrySet()) insert(top, r.getKey(), null, r.getValue());
        top.children.putAll(root.children);

        StringBuilder sb = new StringBuilder();
        if (headerComment != null) {
            for (String line : headerComment.split("\n")) sb.append("// ").append(line).append('\n');
        }
        writeObject(sb, top, "", 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void insert(Node root, String key, ConfigSpec.Entry e, JsonElement v) {
        String[] parts = key.split("\\.");
        Node cur = root;
        for (String p : parts) cur = cur.children.computeIfAbsent(p, k -> new Node());
        cur.entry = e;
        cur.value = v;
    }

    private static void writeObject(StringBuilder sb, Node node, String path, int indent) {
        sb.append("{\n");
        int i = 0;
        int n = node.children.size();
        for (Map.Entry<String, Node> c : node.children.entrySet()) {
            String childPath = path.isEmpty() ? c.getKey() : path + "." + c.getKey();
            Node child = c.getValue();
            String pad = "  ".repeat(indent + 1);
            String comment = child.entry != null ? child.entry.comment() : ConfigSpec.sectionComments().get(childPath);
            if (comment != null && !comment.isEmpty()) {
                if (child.entry == null) sb.append('\n');
                for (String line : comment.split("\n")) sb.append(pad).append("// ").append(line).append('\n');
                if (child.entry != null && child.entry.type() == ConfigSpec.Type.ENUM) {
                    sb.append(pad).append("// Erlaubt: ").append(String.join(", ", child.entry.options())).append('\n');
                }
                if (child.entry != null && (child.entry.type() == ConfigSpec.Type.INT || child.entry.type() == ConfigSpec.Type.DOUBLE)) {
                    sb.append(pad).append("// Bereich: ").append(fmt(child.entry.min())).append(" bis ").append(fmt(child.entry.max()))
                            .append(" (Standard: ").append(compact(child.entry.def())).append(")").append('\n');
                }
            }
            sb.append(pad).append('"').append(c.getKey()).append("\": ");
            if (child.value != null && child.children.isEmpty()) {
                sb.append(indentValue(child.value, indent + 1));
            } else {
                writeObject(sb, child, childPath, indent + 1);
            }
            if (++i < n) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ".repeat(indent)).append('}');
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String indentValue(JsonElement v, int indent) {
        String s = (v.isJsonArray() && v.getAsJsonArray().size() <= 8 && allPrimitive(v)) ? compact(v) : PRETTY.toJson(v);
        if (!s.contains("\n")) return s;
        String pad = "  ".repeat(indent);
        return s.replace("\n", "\n" + pad);
    }

    private static boolean allPrimitive(JsonElement v) {
        for (JsonElement e : v.getAsJsonArray()) if (!e.isJsonPrimitive()) return false;
        return true;
    }
}
