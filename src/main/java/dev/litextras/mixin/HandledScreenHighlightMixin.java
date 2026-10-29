package dev.litextras.mixin;

import dev.litextras.hud.HudRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Markiert im geöffneten Inventar (und Kisten usw.) die Slots des Spielers, deren Item zum
 * angeschauten Plan-Block passt: grün = exakt richtig, gelb = nur ähnlich. Nur Zeichnen, keine Klicks.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenHighlightMixin {
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow @Final protected ScreenHandler handler;

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void litextras$highlight(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        try {
            Map<Integer, Boolean> marks = HudRenderer.inventoryMarks();
            if (marks.isEmpty() || handler == null) return;
            for (Slot s : handler.slots) {
                if (!(s.inventory instanceof PlayerInventory)) continue;
                Boolean exact = marks.get(s.getIndex());
                if (exact == null) continue;
                int[] c = HudRenderer.markColors(exact);
                int sx = x + s.x, sy = y + s.y;
                ctx.fill(sx, sy, sx + 16, sy + 16, c[1]);
                ctx.fill(sx - 1, sy - 1, sx + 17, sy, c[0]);
                ctx.fill(sx - 1, sy + 16, sx + 17, sy + 17, c[0]);
                ctx.fill(sx - 1, sy, sx, sy + 16, c[0]);
                ctx.fill(sx + 16, sy, sx + 17, sy + 16, c[0]);
            }
        } catch (Throwable ignored) {
        }
    }
}
