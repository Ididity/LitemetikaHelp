package dev.litextras.render;

import dev.litextras.config.Cfg;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * CartPvP (nur Profil 'full'): Blockt ein Gegner mit dem Schild, wird der NICHT gedeckte Teil seiner Hitbox
 * (die Seite, die von seiner Blickrichtung wegzeigt – da blockt der Schild nicht) blau umrandet, durch Wände
 * und flüssig. So siehst du, wo du trotz Schild triffst. Rein zeichnend.
 */
public final class ShieldEsp {
    private ShieldEsp() {}

    public static void render(DrawContext ctx, float tickProgress) {
        if (!dev.litextras.Edition.fullFeatures() || !Cfg.bool("shield.esp")) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.world == null || mc.player == null) return;
        Vec3d eye = cam.getCameraPosVec(tickProgress);
        SeeThroughRenderer.Projector pr = new SeeThroughRenderer.Projector(eye, cam.getYaw(tickProgress), cam.getPitch(tickProgress),
                mc.options.getFov().getValue(), ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        int col = Cfg.color("shield.color");
        float lw = (float) Cfg.dbl("highlight.lineWidth");
        int range = Cfg.integer("shield.range");
        double range2 = (double) range * range;
        boolean players = Cfg.bool("shield.players"), mobs = Cfg.bool("shield.mobs");

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || e == mc.player || e == cam || !e.isAlive()) continue;
            if (!le.isBlocking()) continue;
            boolean isPlayer = e instanceof net.minecraft.entity.player.PlayerEntity;
            if (isPlayer ? !players : !mobs) continue;
            Vec3d lp = e.getLerpedPos(tickProgress);
            if (lp.squaredDistanceTo(eye) > range2) continue;
            Box b = e.getBoundingBox();
            if (b == null) continue;
            double curX = (b.minX + b.maxX) / 2.0, curY = b.minY, curZ = (b.minZ + b.maxZ) / 2.0;
            double ox = lp.x - curX, oy = lp.y - curY, oz = lp.z - curZ;
            double minX = b.minX + ox, minY = b.minY + oy, minZ = b.minZ + oz;
            double maxX = b.maxX + ox, maxY = b.maxY + oy, maxZ = b.maxZ + oz;

            // Blickrichtung -> Schild deckt die Vorderseite. Ungedeckt ist die Rückseite entlang der Hauptachse.
            double yaw = Math.toRadians(e.getYaw(tickProgress));
            double lookX = -Math.sin(yaw), lookZ = Math.cos(yaw);
            if (Math.abs(lookX) >= Math.abs(lookZ)) {
                double cx = (minX + maxX) / 2.0;
                if (lookX > 0) maxX = cx; else minX = cx; // hintere Hälfte behalten
            } else {
                double cz = (minZ + maxZ) / 2.0;
                if (lookZ > 0) maxZ = cz; else minZ = cz;
            }
            SeeThroughRenderer.drawAabb(ctx, pr, minX, minY, minZ, maxX, maxY, maxZ, col, lw);
        }
    }
}
