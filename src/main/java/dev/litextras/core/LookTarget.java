package dev.litextras.core;

import dev.litextras.config.Cfg;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Bestimmt den angeschauten Block (Feature 2/3) und die Platzier-Vorschau.
 * Raycast in BEIDE Welten: echte Welt (vorhandene Blöcke) und Schematic-Welt (fehlende Blöcke).
 * Der nähere Treffer gewinnt. Läuft auf dem Haupt-Thread, aber nur alle N Ticks (Config).
 *
 * Statt Litematicas RayTraceUtils (Signatur ändert sich zwischen Versionen) wird der Vanilla-Raycast
 * direkt auf Litematicas Schematic-Welt angewendet – gleiches Ergebnis, stabilere API.
 */
public final class LookTarget {
    private LookTarget() {}

    public record Target(BlockPos pos, CompareResult result, Direction side) {}

    public record Preview(BlockPos pos, BlockState wouldPlace, CompareResult result, String problemKey) {}

    private static volatile Target current;
    private static volatile Preview preview;
    private static int counter = 0;

    public static Target current() { return current; }
    public static Preview preview() { return preview; }

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) { current = null; preview = null; return; }
        if (++counter % Math.max(1, Cfg.integer("hud.updateIntervalTicks")) != 0) return;
        World schem = LitematicaBridge.schematicWorld();
        current = computeTarget(mc, schem);
        preview = computePreview(mc, schem);
    }

    /** Sofort neu berechnen (z. B. für die KI). Haupt-Thread. */
    public static Target computeNow(MinecraftClient mc) {
        return computeTarget(mc, LitematicaBridge.schematicWorld());
    }

    private static Target computeTarget(MinecraftClient mc, World schem) {
        ClientPlayerEntity p = mc.player;
        BlockHitResult vanilla = mc.crosshairTarget instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
        BlockHitResult schemHit = null;
        if (schem != null) {
            double reach = p.getBlockInteractionRange();
            Vec3d eye = p.getCameraPosVec(1.0f);
            Vec3d end = eye.add(p.getRotationVec(1.0f).multiply(reach));
            try {
                BlockHitResult h = schem.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
                if (h != null && h.getType() == HitResult.Type.BLOCK) schemHit = h;
            } catch (Throwable ignored) {}
        }
        BlockHitResult use;
        if (vanilla == null) use = schemHit;
        else if (schemHit == null) use = vanilla;
        else {
            Vec3d eye = p.getCameraPosVec(1.0f);
            use = schemHit.getPos().squaredDistanceTo(eye) + 1.0E-4 < vanilla.getPos().squaredDistanceTo(eye) ? schemHit : vanilla;
        }
        if (use == null) return null;
        BlockPos pos = use.getBlockPos();
        BlockState soll = LitematicaBridge.schematicState(schem, pos);
        BlockState ist = mc.world.getBlockState(pos);
        CompareResult r = BlockComparator.compare(soll, ist);
        if (Cfg.bool("hud.lookedAt.onlyInSchematic")
                && (r.status() == CompareResult.Status.NONE || !insidePlacement(pos))) return null;
        return new Target(pos.toImmutable(), r, use.getSide());
    }

    /** Liegt die Position in einem aktiven Litematica-Bereich? Außerhalb wird nichts angezeigt. */
    public static boolean insidePlacement(BlockPos pos) {
        for (LitematicaBridge.PlacementInfo p : ErrorScanner.placements()) {
            if (!p.enclosing().contains(pos.getX(), pos.getY(), pos.getZ())) continue;
            for (LitematicaBridge.IntBox b : p.boxes()) {
                if (b.contains(pos.getX(), pos.getY(), pos.getZ())) return true;
            }
        }
        return false;
    }

    /** Liegt die Position in einem aktiven Litematica-Bereich? Außerhalb wird nichts angezeigt. */
    public static boolean insidePlacement(BlockPos pos) {
        for (LitematicaBridge.PlacementInfo p : ErrorScanner.placements()) {
            if (!p.enclosing().contains(pos.getX(), pos.getY(), pos.getZ())) continue;
            for (LitematicaBridge.IntBox b : p.boxes()) {
                if (b.contains(pos.getX(), pos.getY(), pos.getZ())) return true;
            }
        }
        return false;
    }

    /**
     * Platzier-Vorschau: baut einen ItemPlacementContext aus Blickrichtung, angeklickter Seite und
     * Trefferpunkt (wie beim echten Rechtsklick) und fragt block.getPlacementState(ctx).
     * Es wird NICHTS platziert – das ist eine reine Berechnung.
     */
    private static Preview computePreview(MinecraftClient mc, World schem) {
        if (!Cfg.bool("hud.preview.enabled") || schem == null) return null;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        ClientPlayerEntity p = mc.player;
        ItemStack stack = p.getMainHandStack();
        if (!(stack.getItem() instanceof BlockItem bi)) {
            return null;
        }
        try {
            ItemPlacementContext ctx = new ItemPlacementContext(p, Hand.MAIN_HAND, stack, hit);
            ItemPlacementContext adj = bi.getPlacementContext(ctx);
            if (adj == null || !adj.canPlace()) return new Preview(ctx.getBlockPos(), null, null, "preview.cannotPlace");
            BlockPos pos = adj.getBlockPos();
            BlockState would = bi.getBlock().getPlacementState(adj);
            if (would == null) return new Preview(pos, null, null, "preview.cannotPlace");
            BlockState soll = LitematicaBridge.schematicState(schem, pos);
            if (BlockComparator.isEmpty(soll)) {
                if (!insidePlacement(pos)) return null; // außerhalb der Schematic: keine Vorschau
                return new Preview(pos, would, null, "preview.notInPlan");
            }
            return new Preview(pos.toImmutable(), would, BlockComparator.compare(soll, would), null);
        } catch (Throwable t) {
            return null;
        }
    }
}
