package dev.litextras.render;

import dev.litextras.config.Cfg;
import dev.litextras.config.Colors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Tracer: Linien von der Bildschirmmitte zu Zielen.
 *  - zu leuchtenden Blöcken (siehe BlockHighlighter): Plan-Blöcke überall,
 *  - zu Spielern und Monstern: nur in Einzelspieler-Welten oder auf freigegebenen Servern
 *    (gleiche Regel wie der Welt-Modus beim Leuchten).
 */
public final class Tracers {
    private Tracers() {}

    public static boolean active() { return Cfg.bool("tracers.active"); }

    public static void render(DrawContext ctx, float tickProgress) {
        if (!active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.world == null || mc.player == null) return;
        Vec3d eye = cam.getCameraPosVec(tickProgress);
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        SeeThroughRenderer.Projector pr = new SeeThroughRenderer.Projector(eye, cam.getYaw(tickProgress), cam.getPitch(tickProgress),
                mc.options.getFov().getValue(), sw, sh);
        float cx = sw / 2f, cy = sh / 2f;
        float lw = (float) Cfg.dbl("tracers.lineWidth");
        int range = Cfg.integer("tracers.range");
        double range2 = (double) range * range;
        int max = Cfg.integer("tracers.max");
        int drawn = 0;

        // ---- Blöcke
        if (Cfg.bool("tracers.blocks") && BlockHighlighter.active()) {
            boolean worldOk = BlockHighlighter.worldModeAllowed(mc);
            for (BlockHighlighter.Hit h : BlockHighlighter.hits()) {
                if (drawn >= max) break;
                if (h.world() && !worldOk) continue;
                double x = h.pos().getX() + 0.5, y = h.pos().getY() + 0.5, z = h.pos().getZ() + 0.5;
                if (sq(x - eye.x, y - eye.y, z - eye.z) > range2) continue;
                float[] p = pr.projectOrEdge(x, y, z);
                SeeThroughRenderer.line(ctx, cx, cy, p[0], p[1], lw, Colors.withAlphaFactor(h.color(), 0.8));
                drawn++;
            }
        }

        // ---- Spieler / Monster (nur wo erlaubt)
        boolean players = Cfg.bool("tracers.players"), mobs = Cfg.bool("tracers.mobs");
        if ((players || mobs) && BlockHighlighter.worldModeAllowed(mc)) {
            int cP = Cfg.color("tracers.colorPlayer"), cM = Cfg.color("tracers.colorMob");
            for (Entity e : mc.world.getEntities()) {
                if (drawn >= max) break;
                if (e == mc.player || e == cam || !e.isAlive()) continue;
                int col;
                if (e instanceof PlayerEntity) { if (!players) continue; col = cP; }
                else if (e instanceof Monster && e instanceof LivingEntity) { if (!mobs) continue; col = cM; }
                else continue;
                Vec3d pos = e.getLerpedPos(tickProgress);
                double x = pos.x, y = pos.y + e.getHeight() / 2.0, z = pos.z;
                if (sq(x - eye.x, y - eye.y, z - eye.z) > range2) continue;
                float[] p = pr.projectOrEdge(x, y, z);
                SeeThroughRenderer.line(ctx, cx, cy, p[0], p[1], lw, col);
                drawn++;
            }
        }
    }

    private static double sq(double a, double b, double c) { return a * a + b * b + c * c; }
}
