package dev.litextras.compat;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Holt Litematicas OverlayType.NONE per Reflection (Klassenname kann je Version leicht anders sein). */
public final class OverlayTypes {
    private OverlayTypes() {}

    private static volatile Object none;
    /** Diagnose: wie oft Litematica gefragt hat und wie oft wir "keine Markierung" geliefert haben. */
    public static final java.util.concurrent.atomic.AtomicLong CALLS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CLEARED = new java.util.concurrent.atomic.AtomicLong();
    public static volatile String lastError = null;
    private static volatile boolean tried;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object none(CallbackInfoReturnable<?> cir) {
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
