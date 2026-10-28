package dev.litextras.config;

public final class Colors {
    private Colors() {}

    /** "#RRGGBB" -> 0xFFRRGGBB, "#AARRGGBB" -> 0xAARRGGBB. null bei ungültigem Wert. */
    public static Integer parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        try {
            if (t.length() == 6) return 0xFF000000 | Integer.parseUnsignedInt(t, 16);
            if (t.length() == 8) return (int) Long.parseLong(t, 16);
        } catch (NumberFormatException ignored) {}
        return null;
    }

    public static int withAlphaFactor(int argb, double factor) {
        int a = (argb >>> 24) & 0xFF;
        int na = (int) Math.max(0, Math.min(255, Math.round(a * factor)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }
}
