package dev.litextras.core;

import dev.litextras.util.ChatOut;
import dev.litextras.util.Lang;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * Setzt im EINZELSPIELER unter alle Blöcke eines bestimmten Typs (bei denen die Zelle direkt darunter leer ist)
 * Smooth Stone. Der Zieltyp ist der Block, den du gerade anschaust. Nur Einzelspieler (verändert die Welt
 * direkt am integrierten Server). Auf Servern nicht möglich.
 */
public final class FillUnderTool {
    private FillUnderTool() {}

    public static void run(MinecraftClient mc, int radius) {
        var server = mc.getServer();
        if (server == null || mc.player == null || mc.world == null) {
            ChatOut.warn(Lang.get("fillunder.spOnly"));
            return;
        }
        // Zieltyp = angeschauter Block
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr) || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            ChatOut.warn(Lang.get("fillunder.lookAt"));
            return;
        }
        final Block target = mc.world.getBlockState(bhr.getBlockPos()).getBlock();
        final Block fill = fillBlock();
        final BlockPos center = mc.player.getBlockPos();
        final var dimKey = mc.world.getRegistryKey();
        final int r = Math.max(2, Math.min(256, radius));
        ChatOut.info(Lang.get("fillunder.start", "block", target.getName().getString(), "r", String.valueOf(r)));
        server.execute(() -> {
            int placed = 0;
            try {
                ServerWorld world = server.getWorld(dimKey);
                if (world == null) return;
                BlockState fillState = fill.getDefaultState();
                int bottom = world.getBottomY(), top = world.getTopYInclusive();
                BlockPos.Mutable m = new BlockPos.Mutable();
                for (int x = center.getX() - r; x <= center.getX() + r; x++) {
                    for (int z = center.getZ() - r; z <= center.getZ() + r; z++) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                        for (int y = Math.max(bottom + 1, center.getY() - r); y <= Math.min(top, center.getY() + r); y++) {
                            m.set(x, y, z);
                            if (!world.getBlockState(m).isOf(target)) continue;
                            BlockPos below = new BlockPos(x, y - 1, z);
                            BlockState bs = world.getBlockState(below);
                            if (bs.isAir() || bs.isReplaceable()) {
                                world.setBlockState(below, fillState);
                                placed++;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                dev.litextras.LitematicaExtras.debug("FillUnder fehlgeschlagen: " + t);
            }
            final int fp = placed;
            mc.execute(() -> ChatOut.ok(Lang.get("fillunder.done", "n", String.valueOf(fp))));
        });
    }

    private static Block fillBlock() {
        try {
            Identifier id = Identifier.of(dev.litextras.config.Cfg.str("fillunder.block").trim());
            if (Registries.BLOCK.containsId(id)) return Registries.BLOCK.get(id);
        } catch (Throwable ignored) {
        }
        return Blocks.SMOOTH_STONE;
    }
}
