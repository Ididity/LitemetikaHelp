package dev.litextras.render;

import dev.litextras.config.Cfg;
import dev.litextras.config.Colors;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markiert durch Wände Breeze-Trial-Spawner, in deren Aktivierungsreichweite du gerade bist
 * (also wo Breezes auf dich spawnen können – egal ob gerade auf Cooldown oder nicht).
 *
 * Nur Einzelspieler: Ob ein Trial-Spawner Breezes spawnt, steht in seinen Spawn-Daten, die der Client
 * nur über den integrierten Server lesen kann. Auf einem Server passiert nichts.
 */
public final class BreezeMarker {
    private BreezeMarker() {}

    private static final Pattern RANGE = Pattern.compile("required_player_range:\\s*(\\d+)");

    private static volatile List<BlockPos> marks = List.of();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static long lastStart = 0;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LitematicaExtras-Breeze");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public static boolean active() { return !dev.litextras.Edition.LEGIT && Cfg.bool("highlight.breezeSpawner"); }

    public static void tick(MinecraftClient mc) {
        if (!active() || mc.getServer() == null || mc.player == null || mc.world == null) {
            if (!marks.isEmpty()) marks = List.of();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStart < 250) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        lastStart = now;
        final var server = mc.getServer();
        final BlockPos center = mc.player.getBlockPos();
        final var dimKey = mc.world.getRegistryKey();
        final int scan = Cfg.integer("highlight.breezeRange");
        EXEC.execute(() -> {
            List<BlockPos> out = new ArrayList<>();
            try {
                ServerWorld world = server.getWorld(dimKey);
                if (world != null) {
                    var lookup = world.getRegistryManager();
                    int bottom = world.getBottomY(), top = world.getTopYInclusive();
                    BlockPos.Mutable m = new BlockPos.Mutable();
                    for (int x = center.getX() - scan; x <= center.getX() + scan; x++) {
                        for (int z = center.getZ() - scan; z <= center.getZ() + scan; z++) {
                            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                            for (int y = Math.max(bottom, center.getY() - scan); y <= Math.min(top, center.getY() + scan); y++) {
                                m.set(x, y, z);
                                BlockState st = world.getBlockState(m);
                                if (!st.isOf(Blocks.TRIAL_SPAWNER)) continue;
                                BlockEntity be = world.getBlockEntity(m);
                                if (be == null) continue;
                                String nbt;
                                try {
                                    nbt = be.createNbt(lookup).toString();
                                } catch (Throwable t) {
                                    continue;
                                }
                                if (!nbt.toLowerCase(java.util.Locale.ROOT).contains("breeze")) continue;
                                int range = 14; // Standard-Aktivierungsreichweite
                                Matcher mm = RANGE.matcher(nbt);
                                if (mm.find()) try { range = Integer.parseInt(mm.group(1)); } catch (Exception ignored) {}
                                // Mittelpunkt des Spawners; bist du innerhalb der Reichweite?
                                double dx = x + 0.5 - (center.getX() + 0.5), dy = y + 0.5 - (center.getY() + 0.5), dz = z + 0.5 - (center.getZ() + 0.5);
                                if (dx * dx + dy * dy + dz * dz <= (double) range * range) out.add(m.toImmutable());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                marks = List.copyOf(out);
                RUNNING.set(false);
            }
        });
    }

    public static void render(DrawContext ctx, float tickProgress) {
        List<BlockPos> list = marks;
        if (!active() || list.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.world == null) return;
        Vec3d eye = cam.getCameraPosVec(tickProgress);
        SeeThroughRenderer.Projector pr = new SeeThroughRenderer.Projector(eye, cam.getYaw(tickProgress), cam.getPitch(tickProgress),
                mc.options.getFov().getValue(), ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        float lw = (float) Cfg.dbl("highlight.lineWidth");
        int col = Cfg.color("highlight.breezeColor");
        for (BlockPos p : list) SeeThroughRenderer.drawBox(ctx, pr, p, Colors.withAlphaFactor(col, 0.9), lw, "wire");
    }
}
