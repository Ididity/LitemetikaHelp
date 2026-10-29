package dev.litextras.gui;

import com.google.gson.JsonPrimitive;
import dev.litextras.config.Cfg;
import dev.litextras.config.ConfigSpec;
import dev.litextras.util.Lang;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Einstellungsfenster für Tasten: Hotkey anklicken, gewünschte Taste(n) drücken, loslassen – gespeichert.
 * Esc während der Aufnahme = Hotkey ohne Taste. Rechts daneben: halten/umschalten.
 * Die Tastenerkennung fragt GLFW direkt ab (stabil über Minecraft-Versionen).
 */
public class HotkeyScreen extends Screen {
    private final Screen parent;
    private String capturing = null;
    private final Set<Integer> seen = new LinkedHashSet<>();
    private boolean anyDown = false;
    private int page = 0;
    private static final int PER_PAGE = 8;

    private static final Map<Integer, String> KEY_NAMES = new HashMap<>();
    static {
        for (Field f : GLFW.class.getFields()) {
            if (!f.getName().startsWith("GLFW_KEY_") || f.getType() != int.class) continue;
            try {
                int code = f.getInt(null);
                if (code > 0 && !f.getName().equals("GLFW_KEY_LAST") && !f.getName().equals("GLFW_KEY_UNKNOWN")) {
                    KEY_NAMES.putIfAbsent(code, f.getName().substring("GLFW_KEY_".length()));
                }
            } catch (Exception ignored) {}
        }
    }

    public HotkeyScreen(Screen parent) {
        super(Text.literal("Litematica Extras – " + Lang.get("gui.hotkeys")));
        this.parent = parent;
    }

    private static List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (String k : ConfigSpec.entries().keySet()) {
            if (k.startsWith("hotkeys.") && k.endsWith(".keys")) ids.add(k.substring(8, k.length() - 5));
        }
        return ids;
    }

    private static String label(String id) {
        String c = ConfigSpec.sectionComments().get("hotkeys." + id);
        if (c == null) return id;
        int cut = c.indexOf(" – ");
        String s = cut > 0 ? c.substring(0, cut) : c;
        return s.length() > 34 ? s.substring(0, 33) + "…" : s;
    }

    @Override
    protected void init() {
        List<String> ids = ids();
        int left = this.width / 2 - 155;
        int y = 36;
        int from = page * PER_PAGE, to = Math.min(ids.size(), from + PER_PAGE);
        for (int i = from; i < to; i++) {
            String id = ids.get(i);
            String keys = Cfg.str("hotkeys." + id + ".keys");
            String shown = id.equals(capturing) ? Lang.get("gui.pressKeys")
                    : label(id) + ": " + (keys.isBlank() ? Lang.get("gui.none") : keys);
            addDrawableChild(ButtonWidget.builder(Text.literal(shown), b -> {
                capturing = id;
                seen.clear();
                anyDown = false;
                clearAndInit();
            }).dimensions(left, y, 230, 20).build());
            boolean hold = "hold".equals(Cfg.str("hotkeys." + id + ".mode"));
            addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get(hold ? "gui.mode.hold" : "gui.mode.toggle")), b -> {
                Cfg.manager().setPersistent("hotkeys." + id + ".mode", new JsonPrimitive(hold ? "toggle" : "hold"));
                clearAndInit();
            }).dimensions(left + 234, y, 76, 20).build());
            y += 22;
        }
        int pages = (ids.size() + PER_PAGE - 1) / PER_PAGE;
        y += 6;
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.page", "n", page + 1, "of", pages)), b -> {
                page = (page + 1) % pages;
                capturing = null;
                clearAndInit();
            }).dimensions(left, y, 150, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.back")), b -> close())
                .dimensions(left + 160, y, 150, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        pollCapture();
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(Lang.get("gui.hotkeysTitle")), this.width / 2, 15, 0xFFFFFFFF);
    }

    /** Tasten abfragen, solange aufgenommen wird. Fertig, sobald alle gedrückten Tasten wieder losgelassen sind. */
    private void pollCapture() {
        if (capturing == null || this.client == null || this.client.getWindow() == null) return;
        long h = this.client.getWindow().getHandle();
        boolean down = false;
        for (int code : KEY_NAMES.keySet()) {
            if (GLFW.glfwGetKey(h, code) == GLFW.GLFW_PRESS) {
                down = true;
                seen.add(code);
            }
        }
        if (down) anyDown = true;
        if (anyDown && !down && !seen.isEmpty()) {
            String spec;
            if (seen.size() == 1 && seen.contains(GLFW.GLFW_KEY_ESCAPE)) spec = "";
            else {
                List<String> mods = new ArrayList<>(), keys = new ArrayList<>();
                for (int c : seen) {
                    if (c == GLFW.GLFW_KEY_ESCAPE) continue;
                    String n = KEY_NAMES.get(c);
                    if (n.contains("SHIFT") || n.contains("CONTROL") || n.contains("ALT") || n.contains("SUPER")) mods.add(n);
                    else keys.add(n);
                }
                mods.addAll(keys);
                spec = String.join("+", mods);
            }
            Cfg.manager().setPersistent("hotkeys." + capturing + ".keys", new JsonPrimitive(spec));
            capturing = null;
            seen.clear();
            anyDown = false;
            clearAndInit();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return capturing == null; }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
