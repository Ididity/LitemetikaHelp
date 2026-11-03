package dev.litextras.mixin;

import dev.litextras.config.Cfg;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Schnellere Trichter NUR im Einzelspieler: statt 1 Item pro Transfer werden bis zu N Items auf einmal
 * bewegt (hopper.itemsPerTransfer). Greift in HopperBlockEntity.insert/extract an der Stelle, wo normal
 * removeStack(slot, 1) aufgerufen wird. require = 0: ändert sich die Methode in einer MC-Version, lädt der
 * Mod trotzdem, nur ohne diese Beschleunigung.
 *
 * Auf einem echten Server läuft dieser Client-Mod nicht; die Trichter dort bleiben also unverändert.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperSpeedMixin {

    @ModifyArg(method = "insert", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/inventory/Inventory;removeStack(II)Lnet/minecraft/item/ItemStack;"), index = 1, require = 0)
    private static int litextras$insertAmount(int original) {
        return litextras$amount(original);
    }

    @ModifyArg(method = "extract(Lnet/minecraft/block/entity/Hopper;Lnet/minecraft/inventory/Inventory;ILnet/minecraft/util/math/Direction;)Lnet/minecraft/item/ItemStack;",
            at = @At(value = "INVOKE",
            target = "Lnet/minecraft/inventory/Inventory;removeStack(II)Lnet/minecraft/item/ItemStack;"), index = 1, require = 0)
    private static int litextras$extractAmount(int original) {
        return litextras$amount(original);
    }

    private static int litextras$amount(int original) {
        try {
            if (original != 1) return original; // nur die 1er-Einzeltransfers hochsetzen
            if (!Cfg.bool("hopper.fastTransfer")) return 1;
            if (MinecraftClient.getInstance().getServer() == null) return 1; // nur Einzelspieler
            int n = Cfg.integer("hopper.itemsPerTransfer");
            return Math.max(1, Math.min(64, n));
        } catch (Throwable t) {
            return original;
        }
    }
}
