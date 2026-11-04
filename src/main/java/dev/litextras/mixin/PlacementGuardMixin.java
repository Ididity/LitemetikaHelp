package dev.litextras.mixin;

import dev.litextras.config.Cfg;
import dev.litextras.core.CompareResult;
import dev.litextras.core.LookTarget;
import dev.litextras.util.ChatOut;
import dev.litextras.util.Lang;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.BlockItem;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Platz-Sperre: Wenn eingeschaltet, kann man nur Blöcke setzen, die laut Plan an die Platzier-Stelle gehören.
 * Falsche Platzierungen werden abgebrochen (nichts wird an den Server geschickt). Greift nur, wenn man einen
 * Block in der Haupthand hält und die Stelle Teil der Schematic ist – Kisten/Hebel mit leerer Hand oder Werkzeug
 * lassen sich normal bedienen. require = 0: notfalls lädt der Mod ohne diese Funktion.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class PlacementGuardMixin {

    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void litextras$onlyCorrect(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
                                       CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (hand != Hand.MAIN_HAND) return;
            if (!Cfg.bool("build.onlyCorrectPlacement")) return;
            if (player == null || !(player.getMainHandStack().getItem() instanceof BlockItem)) return;
            LookTarget.Preview pv = LookTarget.preview();
            if (pv == null || pv.result() == null) return; // nicht Teil des Plans -> normal bauen
            boolean ok = Cfg.bool("build.onlyCorrectIncludeSimilar")
                    ? pv.result().status().isOk()
                    : pv.result().status() == CompareResult.Status.CORRECT;
            if (!ok) {
                cir.setReturnValue(ActionResult.FAIL);
                if (Cfg.bool("build.onlyCorrectMessage")) ChatOut.actionBar(Lang.get("build.blocked"));
            }
        } catch (Throwable ignored) {
        }
    }
}
