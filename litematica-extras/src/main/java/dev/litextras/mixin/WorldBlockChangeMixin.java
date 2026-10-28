package dev.litextras.mixin;

import dev.litextras.core.ErrorScanner;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Meldet Blockänderungen in der ECHTEN Client-Welt an den Scanner, damit Caches nur bei
 * Änderungen neu rechnen. Rein beobachtend – verändert nichts. require = 0: falls die
 * Methodensignatur in einer Version anders ist, lädt der Mod trotzdem (dann greift der
 * zeitgesteuerte Neu-Scan aus scan.fullRescanSeconds).
 */
@Mixin(World.class)
public abstract class WorldBlockChangeMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("RETURN"), require = 0)
    private void litextras$onSetBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && (Object) this instanceof ClientWorld) {
            ErrorScanner.onBlockChanged(pos);
        }
    }
}
