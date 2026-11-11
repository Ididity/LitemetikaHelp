package dev.litextras.render;

import dev.litextras.config.Cfg;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * CartPvP (nur Profil 'full'): Steckt ein Gegner in einem Spinnennetz (Cobweb), wird der Teil seiner Hitbox,
 * der AUSSERHALB des Netzes liegt, blau umrandet – durch Wände und flüssig (jedes Bild, interpolierte Position).
 * So siehst du genau den Teil, auf den du zielen kannst. Rein zeichnend, verändert nichts.
 */
public final class CobwebEsp {
    private CobwebEsp() {}

    public static void render(DrawContext ctx, float tickProgress) {
        if (!dev.litextras.Edition.fullFeatures() || !Cfg.bool("cobweb.esp")) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.world == null || mc.player == null) return;
        Vec3d eye = cam.getCameraPosVec(tickProgress);
        SeeThroughRenderer.Projector pr = new SeeThroughRenderer.Projector(eye, cam.getYaw(tickProgress), cam.getPitch(tickProgress),
                mc.options.getFov().getValue(), ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        int col = Cfg.color("cobweb.color");
        float lw = (float) Cfg.dbl("highlight.lineWidth");
        int range = Cfg.integer("cobweb.range");
        double range2 = (double) range * range;
        boolean players = Cfg.bool("cobweb.players"), mobs = Cfg.bool("cobweb.mobs");

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity) || e == mc.player || e == cam || !e.isAlive()) continue;
            boolean isPlayer = e instanceof net.minecraft.entity.player.PlayerEntity;
            if (isPlayer ? !players : !mobs) continue;
            Vec3d lp = e.getLerpedPos(tickProgress);
            if (lp.squaredDistanceTo(eye) > range2) continue;
            Box b = e.getBoundingBox();
            if (b == null) continue;
            // Aktuelle (nicht interpolierte) Position aus der Box ableiten (mapping-unabhängig, kein getPos nötig).
            double curX = (b.minX + b.maxX) / 2.0, curY = b.minY, curZ = (b.minZ + b.maxZ) / 2.0;
            double ox = lp.x - curX, oy = lp.y - curY, oz = lp.z - curZ;
            double minX = b.minX + ox, minY = b.minY + oy, minZ = b.minZ + oz;
            double maxX = b.maxX + ox, maxY = b.maxY + oy, maxZ = b.maxZ + oz;

            int cx0 = (int) Math.floor(minX), cx1 = (int) Math.floor(maxX - 1e-6);
            int cy0 = (int) Math.floor(minY), cy1 = (int) Math.floor(maxY - 1e-6);
            int cz0 = (int) Math.floor(minZ), cz1 = (int) Math.floor(maxZ - 1e-6);

            // Steckt die Hitbox überhaupt in einem Netz?
            boolean inWeb = false;
            BlockPos.Mutable m = new BlockPos.Mutable();
            outer:
            for (int x = cx0; x <= cx1; x++)
                for (int y = cy0; y <= cy1; y++)
                    for (int z = cz0; z <= cz1; z++) {
                        m.set(x, y, z);
                        if (mc.world.getBlockState(m).isOf(Blocks.COBWEB)) { inWeb = true; break outer; }
                    }
            if (!inWeb) continue;

            // Teile der Hitbox in Zellen OHNE Netz blau umranden.
            for (int x = cx0; x <= cx1; x++)
                for (int y = cy0; y <= cy1; y++)
                    for (int z = cz0; z <= cz1; z++) {
                        m.set(x, y, z);
                        if (mc.world.getBlockState(m).isOf(Blocks.COBWEB)) continue; // im Netz -> nicht malen
                        double ix0 = Math.max(minX, x), ix1 = Math.min(maxX, x + 1);
                        double iy0 = Math.max(minY, y), iy1 = Math.min(maxY, y + 1);
                        double iz0 = Math.max(minZ, z), iz1 = Math.min(maxZ, z + 1);
                        if (ix1 - ix0 > 0.01 && iy1 - iy0 > 0.01 && iz1 - iz0 > 0.01) {
                            SeeThroughRenderer.drawAabb(ctx, pr, ix0, iy0, iz0, ix1, iy1, iz1, col, lw);
                        }
                    }
        }
    }
}
