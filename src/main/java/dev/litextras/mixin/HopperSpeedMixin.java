package dev.litextras.mixin;

import dev.litextras.config.Cfg;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * Schnellere Trichter NUR im Einzelspieler (dein Spiel ist dort selbst der Server; auf echten Servern
 * läuft dieser Client-Mod nicht, dort ändert sich nichts).
 *
 * Zwei Hebel, beide duplikationssicher:
 *  1) itemsPerTransfer: pro Zyklus wird der normale 1-Item-Transfer bis zu N-mal ausgeführt. So springen
 *     z. B. 10 Items auf einmal, aber jedes einzeln über den Vanilla-Weg – es kann nichts dupliziert werden.
 *     (Die Menge direkt zu erhöhen wäre unsicher: HopperBlockEntity.insert legt bei einem Teil-Fehlschlag
 *     den vollen Stapel zurück und würde die bereits eingefügten Items doppeln.)
 *  2) transferCooldown: Wartezeit zwischen den Zyklen (Vanilla 8 Ticks).
 *
 * require = 0 / @Shadow mit require: ändert MC die Methoden, lädt der Mod trotzdem, nur ohne Beschleunigung.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperSpeedMixin {

    @Shadow
    private static boolean insert(World world, BlockPos pos, HopperBlockEntity blockEntity) {
        throw new AssertionError();
    }

    @Shadow
    public static boolean extract(World world, Hopper hopper) {
        throw new AssertionError();
    }

    private static boolean litextras$fast() {
        try {
            return Cfg.bool("hopper.fastTransfer") && MinecraftClient.getInstance().getServer() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Pro Zyklus mehrmals transferieren -> sichtbarer Stapel-Sprung, aber dupe-sicher. */
    @Inject(method = "insertAndExtract", at = @At("RETURN"), require = 0)
    private static void litextras$moreTransfers(World world, BlockPos pos, BlockState state, HopperBlockEntity be,
                                                BooleanSupplier booleanSupplier, CallbackInfoReturnable<Boolean> cir) {
        if (!litextras$fast()) return;
        int n;
        try {
            n = Cfg.integer("hopper.itemsPerTransfer");
        } catch (Throwable t) {
            return;
        }
        for (int k = 1; k < n; k++) {
            boolean did = false;
            try {
                did |= extract(world, be);
                did |= insert(world, pos, be);
            } catch (Throwable t) {
                return;
            }
            if (!did) break; // nichts mehr zu bewegen
        }
    }

    /** Kürzere Wartezeit zwischen den Zyklen. */
    @ModifyArg(method = "insertAndExtract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/entity/HopperBlockEntity;setTransferCooldown(I)V"), index = 0, require = 0)
    private static int litextras$cooldown(int original) {
        try {
            if (original <= 0 || !litextras$fast()) return original;
            int c = Cfg.integer("hopper.transferCooldown");
            return Math.max(0, Math.min(original, c));
        } catch (Throwable t) {
            return original;
        }
    }
}
