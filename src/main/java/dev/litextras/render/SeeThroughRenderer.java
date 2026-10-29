package dev.litextras.render;

import dev.litextras.config.Cfg;
import dev.litextras.config.Colors;
import dev.litextras.core.CompareResult;
import dev.litextras.core.ErrorScanner;
import dev.litextras.core.LitematicaBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Feature 5: Fehler durch Blöcke hindurch sehen.
 *
 * Warum so? Die 3D-Render-Pipeline wurde in 1.21.5–1.21.11 stark umgebaut (RenderPipelines,
 * RenderLayer-Erzeugung, WorldRenderEvents). Ein eigener Welt-Renderer "ohne Tiefentest" wäre die
 * fehleranfälligste Stelle des ganzen Mods. Stattdessen projizieren wir die Fehler-Kästen selbst auf
 * den Bildschirm und zeichnen sie als HUD-Linien. Das ist von Natur aus "durch alles hindurch",
 * nutzt nur stabile APIs und kann Reichweite, Fade, Box-Limit, Farben und Linienstärke.
 *
 * Optional (seeThrough.backend = litematica/both) wird zusätzlich Litematicas eigene Option
 * "Overlay durch Blöcke rendern" umgeschaltet.
 */
public final class SeeThroughRenderer {
    private SeeThroughRenderer() {}

    private static boolean litematicaStateApplied = false;

    public static boolean active() { return Cfg.bool("seeThrough.active"); }

    public static boolean ownRendererActive() {
        String b = Cfg.str("seeThrough.backend");
        return active() && ("own".equals(b) || "both".equals(b));
    }

    /** Haupt-Thread, jeden Tick: Litematica-Option synchron halten. */
    public static void tick() {
        String b = Cfg.str("seeThrough.backend");
        boolean wantLitematica = active() && ("litematica".equals(b) || "both".equals(b));
        if (wantLitematica != litematicaStateApplied) {
            if (LitematicaBridge.setRenderThrough(wantLitematica) || !wantLitematica) litematicaStateApplied = wantLitematica;
        }
    }

    public static void render(DrawContext ctx, float tickProgress) {
        if (!ownRendererActive()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.world == null) return;

        ErrorScanner.ScanResult scan = ErrorScanner.seeThroughUsesLayer()
                ? ErrorScanner.latest(ErrorScanner.Scope.LAYER)
                : ErrorScanner.latest(ErrorScanner.Scope.NEAR);
        if (scan == null) return;

        // ---- Kamera (Ego-Perspektive). UNSICHER: In 3rd-Person stimmt die Projektion nicht exakt.
        Vec3d eye = cam.getCameraPosVec(tickProgress);
        float yaw = cam.getYaw(tickProgress);
        float pitch = cam.getPitch(tickProgress);
        double fov = mc.options.getFov().getValue();
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        Projector pr = new Projector(eye, yaw, pitch, fov, sw, sh);

        int range = Cfg.integer("seeThrough.range");
        double range2 = (double) range * range;
        int maxBoxes = Cfg.integer("seeThrough.maxBoxes");
        double fadeStart = Cfg.integer("seeThrough.fadeStart");
        double minAlpha = Cfg.dbl("seeThrough.minAlpha");
        float lw = (float) Cfg.dbl("seeThrough.lineWidth");
        String style = Cfg.str("seeThrough.style");
        boolean sMissing = Cfg.bool("seeThrough.showMissing"), sType = Cfg.bool("seeThrough.showWrongType"),
                sState = Cfg.bool("seeThrough.showWrongState"), sExtra = Cfg.bool("seeThrough.showExtra");
        int cMissing = Cfg.color("seeThrough.colorMissing"), cType = Cfg.color("seeThrough.colorWrongType"),
                cState = Cfg.color("seeThrough.colorWrongState"), cExtra = Cfg.color("seeThrough.colorExtra");

        int drawn = 0;
        List<ErrorScanner.ErrorEntry> list = scan.errors();
        for (ErrorScanner.ErrorEntry e : list) {
            if (drawn >= maxBoxes) break;
            CompareResult.Status st = e.result().status();
            int color;
            switch (st) {
                case MISSING -> { if (!sMissing) continue; color = cMissing; }
                case WRONG_TYPE -> { if (!sType) continue; color = cType; }
                case WRONG_STATE -> { if (!sState) continue; color = cState; }
                case EXTRA -> { if (!sExtra) continue; color = cExtra; }
                default -> { continue; }
            }
            BlockPos p = e.pos();
            double dx = p.getX() + 0.5 - eye.x, dy = p.getY() + 0.5 - eye.y, dz = p.getZ() + 0.5 - eye.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > range2) continue;
            double dist = Math.sqrt(d2);
            double alpha = 1.0;
            if (dist > fadeStart && range > fadeStart) alpha = 1.0 - (dist - fadeStart) / (range - fadeStart) * (1.0 - minAlpha);
            int col = Colors.withAlphaFactor(color, Math.max(minAlpha, alpha));
            if (drawBox(ctx, pr, p, col, lw, style)) drawn++;
        }
    }

    // ------------------------------------------------------------------ Zeichnen
    private static final double[][] CORNERS = {
            {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}, {0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}};
    private static final int[][] EDGES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    private static boolean drawBox(DrawContext ctx, Projector pr, BlockPos p, int color, float lw, String style) {
        double inset = 0.02;
        float[][] s = new float[8][];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double cx = p.getX() + (CORNERS[i][0] == 0 ? inset : 1 - inset);
            double cy = p.getY() + (CORNERS[i][1] == 0 ? inset : 1 - inset);
            double cz = p.getZ() + (CORNERS[i][2] == 0 ? inset : 1 - inset);
            s[i] = pr.project(cx, cy, cz);
            if (s[i] == null) return false; // teilweise hinter der Kamera -> überspringen
            minX = Math.min(minX, s[i][0]); minY = Math.min(minY, s[i][1]);
            maxX = Math.max(maxX, s[i][0]); maxY = Math.max(maxY, s[i][1]);
        }
        if (maxX < 0 || maxY < 0 || minX > pr.sw || minY > pr.sh) return false; // außerhalb
        switch (style) {
            case "box" -> {
                line(ctx, minX, minY, maxX, minY, lw, color);
                line(ctx, maxX, minY, maxX, maxY, lw, color);
                line(ctx, maxX, maxY, minX, maxY, lw, color);
                line(ctx, minX, maxY, minX, minY, lw, color);
            }
            case "marker" -> {
                float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f;
                float r = Math.max(1.5f, Math.min(6f, (maxX - minX) / 4f));
                ctx.fill((int) (cx - r), (int) (cy - r), (int) Math.ceil(cx + r), (int) Math.ceil(cy + r), color);
            }
            default -> {
                for (int[] e : EDGES) line(ctx, s[e[0]][0], s[e[0]][1], s[e[1]][0], s[e[1]][1], lw, color);
            }
        }
        return true;
    }

    /** Linie als gedrehtes, dünnes Rechteck (DrawContext respektiert die 2D-Matrix). */
    private static void line(DrawContext ctx, float x1, float y1, float x2, float y2, float w, int color) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x1, y1);
        m.rotate((float) Math.atan2(dy, dx));
        // fill() nimmt int-Koordinaten -> für Sub-Pixel-Breite skalieren
        m.scale(len / 64f, w);
        ctx.fill(0, 0, 64, 1, color);
        m.popMatrix();
    }

    /** Einfache Perspektiv-Projektion passend zu Minecrafts Kamera (Yaw/Pitch in Grad, vertikales FOV). */
    private static final class Projector {
        final double ex, ey, ez;
        final double fx, fy, fz, rx, ry, rz, ux, uy, uz;
        final double tanHalf, aspect;
        final int sw, sh;

        Projector(Vec3d eye, float yawDeg, float pitchDeg, double fovDeg, int sw, int sh) {
            this.ex = eye.x; this.ey = eye.y; this.ez = eye.z;
            double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
            // Blickrichtung wie Entity#getRotationVector
            fx = -Math.sin(yaw) * Math.cos(pitch);
            fy = -Math.sin(pitch);
            fz = Math.cos(yaw) * Math.cos(pitch);
            // Rechts-Vektor (Blick nach Süden = +Z -> rechts = -X)
            rx = -Math.cos(yaw); ry = 0; rz = -Math.sin(yaw);
            // Oben = rechts x vorne
            ux = ry * fz - rz * fy;
            uy = rz * fx - rx * fz;
            uz = rx * fy - ry * fx;
            tanHalf = Math.tan(Math.toRadians(MathHelper.clamp(fovDeg, 10, 170)) / 2.0);
            this.sw = sw; this.sh = sh;
            aspect = (double) sw / Math.max(1, sh);
        }

        float[] project(double x, double y, double z) {
            double dx = x - ex, dy = y - ey, dz = z - ez;
            double depth = dx * fx + dy * fy + dz * fz;
            if (depth < 0.05) return null;
            double sx = (dx * rx + dy * ry + dz * rz) / (depth * tanHalf * aspect);
            double sy = (dx * ux + dy * uy + dz * uz) / (depth * tanHalf);
            return new float[]{(float) ((1 + sx) / 2 * sw), (float) ((1 - sy) / 2 * sh)};
        }
    }
}
