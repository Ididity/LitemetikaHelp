package dev.litextras.mixin;

import dev.litextras.config.Cfg;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Schnellere Trichter NUR im Einzelspieler (dein Spiel ist dort selbst der Server; auf echten Servern
 * läuft dieser Client-Mod nicht, dort ändert sich nichts).
 *
 * Statt die Menge pro Transfer zu erhöhen (das würde Items duplizieren, wenn der Zielbehälter nicht alle
 * auf einmal annimmt – siehe HopperBlockEntity.insert, das im Fehlerfall den vollen Stapel wiederherstellt),
 * verkürzen wir die Wartezeit (transferCooldown) zwischen den Transfers. Der Trichter bewegt weiter 1 Item
 * pro Zyklus, aber viel öfter. Vanilla sind 8 Ticks (≈2,5 Items/s); 2 Ticks ≈ 10 Items/s.
 *
 * require = 0: ändert MC die Methode, lädt der Mod trotzdem, nur ohne diese Beschleunigung.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperSpeedMixin {

    @ModifyArg(method = "insertAndExtract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/entity/HopperBlockEntity;setTransferCooldown(I)V"), index = 0, require = 0)
    private static int litextras$cooldown(int original) {
        try {
            if (original <= 0) return original;                  // 0 nicht verändern (interne Sonderfälle)
            if (!Cfg.bool("hopper.fastTransfer")) return original;
            if (MinecraftClient.getInstance().getServer() == null) return original; // nur Einzelspieler
            int c = Cfg.integer("hopper.transferCooldown");
            return Math.max(0, Math.min(original, c));           // nie langsamer als Vanilla machen
        } catch (Throwable t) {
            return original;
        }
    }
}
