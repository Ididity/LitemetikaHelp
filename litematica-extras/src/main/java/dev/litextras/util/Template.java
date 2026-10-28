package dev.litextras.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Vorlagen mit Platzhaltern in geschweiften Klammern, z. B. "Soll: {block}{props}". */
public final class Template {
    private Template() {}

    /** Ein farbiges Textstück. */
    public record Seg(String text, int color) {}

    public static String fill(String template, Map<String, String> vars) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) { sb.append(template, i, template.length()); break; }
            int close = template.indexOf('}', open);
            if (close < 0) { sb.append(template, i, template.length()); break; }
            sb.append(template, i, open);
            String name = template.substring(open + 1, close);
            String v = vars.get(name);
            sb.append(v != null ? v : template.substring(open, close + 1));
            i = close + 1;
        }
        return sb.toString();
    }

    /**
     * Wie fill, aber Werte dürfen bereits eingefärbte Stücke (List&lt;Seg&gt;) sein.
     * Andere Werte werden mit toString() in der Standardfarbe eingesetzt.
     */
    @SuppressWarnings("unchecked")
    public static List<Seg> segments(String template, Map<String, Object> vars, int defaultColor) {
        List<Seg> out = new ArrayList<>();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            int close = open < 0 ? -1 : template.indexOf('}', open);
            if (open < 0 || close < 0) { add(out, template.substring(i), defaultColor); break; }
            add(out, template.substring(i, open), defaultColor);
            String name = template.substring(open + 1, close);
            Object v = vars.get(name);
            if (v instanceof List<?> l) {
                for (Object o : l) if (o instanceof Seg s) out.add(s);
            } else if (v instanceof Seg s) {
                out.add(s);
            } else if (v != null) {
                add(out, String.valueOf(v), defaultColor);
            } else {
                add(out, template.substring(open, close + 1), defaultColor);
            }
            i = close + 1;
        }
        return out;
    }

    private static void add(List<Seg> out, String text, int color) {
        if (!text.isEmpty()) out.add(new Seg(text, color));
    }

    public static String plain(List<Seg> segs) {
        StringBuilder sb = new StringBuilder();
        for (Seg s : segs) sb.append(s.text());
        return sb.toString();
    }
}
