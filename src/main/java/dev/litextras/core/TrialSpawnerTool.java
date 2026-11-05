package dev.litextras.core;

import dev.litextras.util.ChatOut;
import dev.litextras.util.Lang;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Entfernt im EINZELSPIELER alle Trial-Spawner in einem Umkreis – außer Breeze-Spawnern.
 * Breeze-Erkennung: Die gespeicherten Spawn-Daten des Blocks enthalten "breeze".
 * Verändert die Welt direkt am integrierten Server (nur Einzelspieler). Auf Servern nicht möglich.
 */
public final class TrialSpawnerTool {
    private TrialSpawnerTool() {}

    public static void clear(MinecraftClient mc, int radius) {
        var server = mc.getServer();
        if (server == null || mc.player == null || mc.world == null) {
            ChatOut.warn(Lang.get("trial.spOnly"));
            return;
        }
        final BlockPos center = mc.player.getBlockPos();
        final var dimKey = mc.world.getRegistryKey();
        final int r = Math.max(4, Math.min(160, radius));
        ChatOut.info(Lang.get("trial.start", "r", String.valueOf(r)));
        server.execute(() -> {
            int removed = 0, breeze = 0;
            try {
                ServerWorld world = server.getWorld(dimKey);
                if (world == null) return;
                var lookup = world.getRegistryManager();
                int bottom = world.getBottomY(), top = world.getTopYInclusive();
                int y0 = Math.max(bottom, center.getY() - r), y1 = Math.min(top, center.getY() + r);
                BlockPos.Mutable m = new BlockPos.Mutable();
                for (int x = center.getX() - r; x <= center.getX() + r; x++) {
                    for (int z = center.getZ() - r; z <= center.getZ() + r; z++) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                        for (int y = y0; y <= y1; y++) {
                            m.set(x, y, z);
                            BlockState st = world.getBlockState(m);
                            if (!st.isOf(Blocks.TRIAL_SPAWNER)) continue;
                            boolean isBreeze = false;
                            BlockEntity be = world.getBlockEntity(m);
                            if (be != null) {
                                try {
                                    String nbt = be.createNbt(lookup).toString().toLowerCase(java.util.Locale.ROOT);
                                    isBreeze = nbt.contains("breeze");
                                } catch (Throwable ignored) {
                                }
                            }
                            if (isBreeze) { breeze++; continue; }
                            world.removeBlock(m.toImmutable(), false);
                            removed++;
                        }
                    }
                }
            } catch (Throwable t) {
                dev.litextras.LitematicaExtras.debug("TrialSpawner-Löschen fehlgeschlagen: " + t);
            }
            final int fr = removed, fb = breeze;
            mc.execute(() -> ChatOut.ok(Lang.get("trial.done", "n", String.valueOf(fr), "breeze", String.valueOf(fb))));
        });
    }
}
