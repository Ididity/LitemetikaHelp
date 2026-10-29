package dev.litextras.hud;

import dev.litextras.config.Cfg;
import dev.litextras.util.Template.Seg;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * HUD-Gerüst: zeichnet einen Kasten mit Zeilen (optional mit Item-Icon) an eine frei
 * einstellbare Stelle (Ecke + Pixel-Versatz), mit Skalierung, Hintergrund und Transparenz.
 * Jedes HUD-Element hat seinen eigenen Config-Abschnitt hud.<id>.*
 */
public final class HudPanel {
    private HudPanel() {}

    public record Line(ItemStack icon, List<Seg> segs) {
        public static Line text(List<Seg> segs) { return new Line(null, segs); }
    }

    /** Zuletzt gezeichnete Position/Größe je HUD-Element (für den HUD-Editor). x, y, Breite, Höhe. */
    public static final java.util.Map<String, float[]> LAST_RECT = new java.util.concurrent.ConcurrentHashMap<>();

    public static final List<String> IDS = List.of("lookedAt", "preview", "neededItem", "layerList", "status", "aiAnswer");

    private static final int TEXT_H = 10;
    private static final int ICON_H = 18;

    public static boolean enabled(String id) {
        return Cfg.bool("hud.enabled") && Cfg.bool("hud." + id + ".enabled");
    }

    public static void draw(DrawContext ctx, String id, List<Line> lines) {
        if (lines == null || lines.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        int max = Cfg.integer("hud." + id + ".maxLines");
        if (lines.size() > max) lines = lines.subList(0, max);
        float scale = (float) Cfg.dbl("hud." + id + ".scale");
        int pad = Cfg.integer("hud." + id + ".padding");
        boolean shadow = Cfg.bool("hud.textShadow");

        int w = 0, h = 0;
        for (Line l : lines) {
            int lw = 0;
            for (Seg s : l.segs()) lw += tr.getWidth(s.text());
            if (l.icon() != null) lw += 18;
            w = Math.max(w, lw);
            h += l.icon() != null ? ICON_H : TEXT_H;
        }
        w += pad * 2;
        h += pad * 2 - 1;

        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        float pw = w * scale, ph = h * scale;
        String anchor = Cfg.str("hud." + id + ".anchor");
        float x, y;
        if (anchor.endsWith("left")) x = 0;
        else if (anchor.endsWith("right")) x = sw - pw;
        else x = (sw - pw) / 2f;
        if (anchor.startsWith("top")) y = 0;
        else if (anchor.startsWith("bottom")) y = sh - ph;
        else y = (sh - ph) / 2f;
        x += Cfg.integer("hud." + id + ".offsetX");
        y += Cfg.integer("hud." + id + ".offsetY");
        LAST_RECT.put(id, new float[]{x, y, pw, ph});

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        if (Cfg.bool("hud." + id + ".background")) ctx.fill(0, 0, w, h, Cfg.color("hud." + id + ".backgroundColor"));
        int cy = pad;
        for (Line l : lines) {
            int cx = pad;
            int lh = l.icon() != null ? ICON_H : TEXT_H;
            if (l.icon() != null) {
                ctx.drawItem(l.icon(), cx, cy);
                cx += 18;
            }
            int ty = cy + (lh - 8) / 2;
            for (Seg s : l.segs()) {
                int color = (s.color() >>> 24) == 0 ? (s.color() | 0xFF000000) : s.color();
                ctx.drawText(tr, s.text(), cx, ty, color, shadow);
                cx += tr.getWidth(s.text());
            }
            cy += lh;
        }
        m.popMatrix();
    }
}
