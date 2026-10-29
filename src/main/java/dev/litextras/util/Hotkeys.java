package dev.litextras.util;

import dev.litextras.LitematicaExtras;
import dev.litextras.config.Cfg;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Eigenes, schlankes Hotkey-System: frei belegbare Mehrfach-Kombinationen (z. B. LEFT_ALT+J),
 * pro Hotkey "toggle" oder "hold". Bewusst ohne Malilib-API, damit es nicht an
 * Malilib-Versionsänderungen bricht. Abgefragt wird nur, wenn kein Menü/Chat offen ist.
 */
public final class Hotkeys {
    private Hotkeys() {}

    /** onChange(true) beim Drücken; bei "hold" zusätzlich onChange(false) beim Loslassen. */
    private record Binding(String id, Consumer<Boolean> onChange) {}

    private static final Map<String, Binding> BINDINGS = new LinkedHashMap<>();
    private static final Map<String, int[]> PARSED = new LinkedHashMap<>();
    private static final Map<String, Boolean> WAS_DOWN = new LinkedHashMap<>();
    private static long parsedGeneration = -1;

    public static void register(String id, Consumer<Boolean> onChange) {
        BINDINGS.put(id, new Binding(id, onChange));
    }

    public static String describe(String id) {
        return Cfg.str("hotkeys." + id + ".keys");
    }

    public static void tick(MinecraftClient mc) {
        if (mc.getWindow() == null) return;
        if (parsedGeneration != Cfg.generation()) reparse();
        boolean blocked = mc.currentScreen != null;
        long handle = mc.getWindow().getHandle();
        for (Binding b : BINDINGS.values()) {
            int[] codes = PARSED.get(b.id());
            boolean enabled = Cfg.bool("hotkeys." + b.id() + ".enabled");
            boolean down = enabled && !blocked && codes != null && codes.length > 0 && allDown(handle, codes);
            boolean was = WAS_DOWN.getOrDefault(b.id(), false);
            boolean hold = "hold".equals(Cfg.str("hotkeys." + b.id() + ".mode"));
            if (down && !was) safeRun(b, true);
            else if (!down && was && hold) safeRun(b, false);
            WAS_DOWN.put(b.id(), down);
        }
    }

    public static boolean isHold(String id) {
        return "hold".equals(Cfg.str("hotkeys." + id + ".mode"));
    }

    private static void safeRun(Binding b, boolean state) {
        try {
            b.onChange().accept(state);
        } catch (Exception e) {
            LitematicaExtras.LOG.error("Hotkey {} fehlgeschlagen", b.id(), e);
        }
    }

    private static boolean allDown(long handle, int[] codes) {
        for (int c : codes) {
            if (c < 0) {
                if (GLFW.glfwGetMouseButton(handle, -c - 1) != GLFW.GLFW_PRESS) return false;
            } else if (GLFW.glfwGetKey(handle, c) != GLFW.GLFW_PRESS) {
                return false;
            }
        }
        return true;
    }

    private static void reparse() {
        PARSED.clear();
        for (String id : BINDINGS.keySet()) {
            String spec = Cfg.str("hotkeys." + id + ".keys");
            int[] codes = parse(spec);
            if (codes == null && spec != null && !spec.isBlank()) {
                ChatOut.warn(Lang.get("msg.hotkeyInvalid", "id", id, "keys", spec));
            }
            PARSED.put(id, codes);
        }
        parsedGeneration = Cfg.generation();
    }

    /** "LEFT_ALT+J" -> GLFW-Codes. Maustasten als negative Zahlen. null bei Fehler. */
    public static int[] parse(String spec) {
        if (spec == null || spec.isBlank()) return new int[0];
        List<Integer> out = new ArrayList<>();
        for (String part : spec.split("\\+")) {
            String p = part.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (p.isEmpty()) continue;
            if (p.startsWith("MOUSE_")) {
                try {
                    int n = Integer.parseInt(p.substring(6));
                    if (n < 1 || n > 8) return null;
                    out.add(-n); // MOUSE_1 -> Button 0 -> -1
                    continue;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            // Bequeme Kurzformen
            switch (p) {
                case "ALT" -> p = "LEFT_ALT";
                case "CTRL", "STRG", "CONTROL" -> p = "LEFT_CONTROL";
                case "SHIFT" -> p = "LEFT_SHIFT";
                case "CMD", "SUPER", "WIN" -> p = "LEFT_SUPER";
                default -> {}
            }
            try {
                out.add(GLFW.class.getField("GLFW_KEY_" + p).getInt(null));
            } catch (Exception e) {
                return null;
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }
}
