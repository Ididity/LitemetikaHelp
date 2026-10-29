package dev.litextras.mixin.compat;

import dev.litextras.core.BlockComparator;
import dev.litextras.core.CompareResult;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feature 1 im Litematica-Overlay: Wenn unsere zentrale Vergleichslogik einen Block als richtig
 * (exakt oder "ähnlich") bewertet, meldet Litematicas Overlay ihn nicht mehr als falsch.
 * Fehler, die WIR sehen, lässt Litematica unverändert anzeigen.
 *
 * UNSICHER: Zielklasse/Methode "ChunkRendererSchematicVbo#getOverlayType(BlockState, BlockState)".
 * Der CompatMixinPlugin prüft das vorher; passt es nicht, wird dieser Mixin übersprungen.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo", remap = false)
public abstract class LitematicaOverlayTypeMixin {

    @Inject(method = "getOverlayType", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void litextras$overlayType(BlockState stateSchematic, BlockState stateClient, CallbackInfoReturnable<Object> cir) {
        try {
            if (stateSchematic == stateClient) return; // Litematica entscheidet selbst
            CompareResult r = BlockComparator.compare(stateSchematic, stateClient);
            if (r.status().isOk()) {
                Object none = OverlayTypes.none(cir);
                if (none != null) cir.setReturnValue(none);
            }
        } catch (Throwable ignored) {
            // Im Zweifel Litematica entscheiden lassen
        }
    }
}
