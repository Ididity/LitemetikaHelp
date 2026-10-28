package dev.litextras.mixin.compat;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Holt Litematicas OverlayType.NONE per Reflection (Klassenname kann je Version leicht anders sein). */
final class OverlayTypes {
    private OverlayTypes() {}

    private static volatile Object none;
    private static volatile boolean tried;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object none(CallbackInfoReturnable<?> cir) {
        if (tried) return none;
        tried = true;
        String[] candidates = {
                CompatMixinPlugin.overlayTypeClassName,
                "fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo$OverlayRenderType",
                "fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo$OverlayType",
                "fi.dy.masa.litematica.render.schematic.OverlayType",
                "fi.dy.masa.litematica.util.OverlayType",
        };
        for (String c : candidates) {
            if (c == null) continue;
            try {
                Class cls = Class.forName(c);
                if (cls.isEnum()) {
                    none = Enum.valueOf(cls, "NONE");
                    return none;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
