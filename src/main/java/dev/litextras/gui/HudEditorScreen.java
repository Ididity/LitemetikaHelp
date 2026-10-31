package dev.litextras.gui;

import com.google.gson.JsonPrimitive;
import dev.litextras.config.Cfg;
import dev.litextras.config.ConfigSpec;
import dev.litextras.hud.HudPanel;
import dev.litextras.util.Lang;
import dev.litextras.util.Template.Seg;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interaktiver HUD-Editor: Alle Anzeigen werden mit Beispieltext gezeigt.
 * Linke Maustaste ziehen = verschieben, rechte Maustaste hoch/runter ziehen oder +/- = Größe.
 * Gespeichert wird beim Loslassen dauerhaft in config.json5.
 *
 * Maus und Tasten werden direkt über GLFW abgefragt – unabhängig von den Eingabe-Methoden,
 * die sich zwischen Minecraft-Versionen ändern.
 */
public class HudEditorScreen extends Screen {
    private final Screen parent;
    private String selected = "lookedAt";
    private String dragging = null;
    private boolean resizing = false;
    private double grabDx, grabDy, startMouseY, startScale;
    private boolean lmbWas, rmbWas, plusWas, minusWas;
    private final Map<String, Float> pendingX = new HashMap<>(), pendingY = new HashMap<>();

    public HudEditorScreen(Screen parent) {
        super(Text.literal("HUD-Editor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("hudedit.smaller")), b -> changeScale(-0.1))
                .dimensions(cx - 205, y, 80, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("hudedit.bigger")), b -> changeScale(0.1))
                .dimensions(cx - 120, y, 80, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("hudedit.toggle")), b -> {
            String k = "hud." + selected + ".enabled";
            Cfg.manager().setPersistent(k, new JsonPrimitive(!Cfg.bool(k)));
        }).dimensions(cx - 35, y, 70, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("hudedit.reset")), b -> reset())
                .dimensions(cx + 40, y, 80, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("hudedit.done")), b -> close())
                .dimensions(cx + 125, y, 80, 20).build());
    }

    private static List<HudPanel.Line> sample(String id) {
        int w = 0xFFFFFFFF, g = Cfg.color("colors.correct"), r = Cfg.color("colors.wrong"), n = Cfg.color("colors.neutral");
        return switch (id) {
            case "lookedAt" -> List.of(
                    HudPanel.Line.text(List.of(new Seg("Typ ok, Richtung falsch", r))),
                    HudPanel.Line.text(List.of(new Seg("Soll: Eichentreppe, Richtung: Norden", w))),
                    HudPanel.Line.text(List.of(new Seg("Ist: Eichentreppe, Richtung: ", w), new Seg("Osten", r))),
                    HudPanel.Line.text(List.of(new Seg("Zustand: ", w), new Seg("gepowert ✔", g), new Seg(", ", w), new Seg("ausgefahren ✘", n))));
            case "preview" -> List.of(HudPanel.Line.text(List.of(new Seg("Vorschau: ", w), new Seg("würde richtig sitzen ✔", g))));
            case "neededItem" -> List.of(new HudPanel.Line(new ItemStack(Items.OAK_STAIRS), List.of(new Seg("Eichentreppe (Inventar, Slot 14)", Cfg.color("colors.warning")))));
            case "layerList" -> List.of(
                    HudPanel.Line.text(List.of(new Seg("Ebene Y=64: 12 Fehler", Cfg.color("colors.warning")))),
                    new HudPanel.Line(new ItemStack(Items.STONE_BRICKS), List.of(new Seg("Steinziegel 20/32 (-12)", r))),
                    new HudPanel.Line(new ItemStack(Items.OAK_PLANKS), List.of(new Seg("Eichenbretter 64/40", g))));
            case "status" -> List.of(HudPanel.Line.text(List.of(new Seg("Profil: bauen", n))), HudPanel.Line.text(List.of(new Seg("≈ Ähnliche Blöcke", Cfg.color("colors.similar")))));
            case "aiAnswer" -> List.of(HudPanel.Line.text(List.of(new Seg("[KI] Dir fehlen noch 12 Steinziegel.", w))));
            default -> List.of(HudPanel.Line.text(List.of(new Seg(id, w))));
        };
    }

    private static String name(String id) {
        String t = Lang.opt("hud.name." + id);
        return t != null ? t : id;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        handleInput(mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);
        for (String id : HudPanel.IDS) {
            if (ConfigSpec.get("hud." + id + ".enabled") == null) continue;
            boolean on = Cfg.bool("hud." + id + ".enabled");
            HudPanel.draw(ctx, id, sample(id));
            float[] r = HudPanel.LAST_RECT.get(id);
            if (r == null) continue;
            int col = id.equals(selected) ? 0xFFFFFF55 : (on ? 0xAAFFFFFF : 0x66FF5555);
            outline(ctx, (int) r[0] - 1, (int) r[1] - 1, (int) (r[0] + r[2]) + 1, (int) (r[1] + r[3]) + 1, col);
            ctx.drawText(this.textRenderer, name(id) + (on ? "" : " (aus)"), (int) r[0], (int) r[1] - 10, col, true);
        }
        int pct = (int) Math.round(Cfg.dbl("hud." + selected + ".scale") * 100);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(Lang.get("hudedit.selected", "name", name(selected), "pct", pct)),
                this.width / 2, this.height - 52, 0xFFFFFF55);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(Lang.get("hudedit.help")), this.width / 2, this.height - 40, 0xFFAAAAAA);
    }

    private static void outline(DrawContext ctx, int x1, int y1, int x2, int y2, int c) {
        ctx.fill(x1, y1, x2, y1 + 1, c);
        ctx.fill(x1, y2 - 1, x2, y2, c);
        ctx.fill(x1, y1, x1 + 1, y2, c);
        ctx.fill(x2 - 1, y1, x2, y2, c);
    }

    private String hit(double mx, double my) {
        for (int i = HudPanel.IDS.size() - 1; i >= 0; i--) {
            String id = HudPanel.IDS.get(i);
            float[] r = HudPanel.LAST_RECT.get(id);
            if (r != null && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] - 10 && my <= r[1] + r[3]) return id;
        }
        return null;
    }

    private void handleInput(int mx, int my) {
        if (this.client == null || this.client.getWindow() == null) return;
        long h = this.client.getWindow().getHandle();
        boolean lmb = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rmb = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean plus = GLFW.glfwGetKey(h, GLFW.GLFW_KEY_EQUAL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_KP_ADD) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_BRACKET) == GLFW.GLFW_PRESS;
        boolean minus = GLFW.glfwGetKey(h, GLFW.GLFW_KEY_MINUS) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_KP_SUBTRACT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_SLASH) == GLFW.GLFW_PRESS;

        // Linke Maustaste: auswählen und ziehen (nicht über den Knöpfen unten)
        if (lmb && !lmbWas && my < this.height - 56) {
            String id = hit(mx, my);
            if (id != null) {
                selected = id;
                dragging = id;
                float[] r = HudPanel.LAST_RECT.get(id);
                grabDx = mx - r[0];
                grabDy = my - r[1];
            }
        }
        if (lmb && dragging != null) {
            float[] r = HudPanel.LAST_RECT.get(dragging);
            float nx = (float) Math.max(0, Math.min(this.width - (r != null ? r[2] : 10), mx - grabDx));
            float ny = (float) Math.max(0, Math.min(this.height - (r != null ? r[3] : 10), my - grabDy));
            Cfg.manager().setRuntime("hud." + dragging + ".anchor", new JsonPrimitive("top_left"));
            Cfg.manager().setRuntime("hud." + dragging + ".offsetX", new JsonPrimitive(Math.round(nx)));
            Cfg.manager().setRuntime("hud." + dragging + ".offsetY", new JsonPrimitive(Math.round(ny)));
            pendingX.put(dragging, nx);
            pendingY.put(dragging, ny);
        }
        if (!lmb && lmbWas && dragging != null) {
            String id = dragging;
            dragging = null;
            if (pendingX.containsKey(id)) {
                Cfg.manager().setPersistent("hud." + id + ".anchor", new JsonPrimitive("top_left"));
                Cfg.manager().setPersistent("hud." + id + ".offsetX", new JsonPrimitive(Math.round(pendingX.remove(id))));
                Cfg.manager().setPersistent("hud." + id + ".offsetY", new JsonPrimitive(Math.round(pendingY.remove(id))));
            }
        }

        // Rechte Maustaste: hoch/runter ziehen = Größe
        if (rmb && !rmbWas) {
            String id = hit(mx, my);
            if (id != null) selected = id;
            resizing = true;
            startMouseY = my;
            startScale = Cfg.dbl("hud." + selected + ".scale");
        }
        if (rmb && resizing) {
            double s = clampScale(startScale + (startMouseY - my) / 100.0);
            Cfg.manager().setRuntime("hud." + selected + ".scale", new JsonPrimitive(Math.round(s * 100) / 100.0));
        }
        if (!rmb && rmbWas && resizing) {
            resizing = false;
            Cfg.manager().setPersistent("hud." + selected + ".scale", new JsonPrimitive(Cfg.dbl("hud." + selected + ".scale")));
        }

        if (plus && !plusWas) changeScale(0.1);
        if (minus && !minusWas) changeScale(-0.1);
        lmbWas = lmb;
        rmbWas = rmb;
        plusWas = plus;
        minusWas = minus;
    }

    private static double clampScale(double s) { return Math.max(0.25, Math.min(4.0, s)); }

    private void changeScale(double d) {
        double s = clampScale(Cfg.dbl("hud." + selected + ".scale") + d);
        Cfg.manager().setPersistent("hud." + selected + ".scale", new JsonPrimitive(Math.round(s * 100) / 100.0));
    }

    private void reset() {
        for (String suffix : new String[]{".anchor", ".offsetX", ".offsetY", ".scale"}) {
            String k = "hud." + selected + suffix;
            Cfg.manager().setPersistent(k, ConfigSpec.get(k).def().deepCopy());
        }
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
