package dev.litextras.mixin.compat;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/**
 * Prüft VOR dem Anwenden, ob Litematicas Overlay-Methode in dieser Version noch so aussieht wie erwartet.
 * Wenn nicht, wird der Mixin einfach übersprungen (kein Absturz). Dann zeigt Litematicas eigenes Overlay
 * "ähnliche" Blöcke weiter als falsch – alle Anzeigen DIESES Mods bleiben aber korrekt.
 */
public class CompatMixinPlugin implements IMixinConfigPlugin {

    public static volatile boolean overlayMixinApplied = false;
    public static volatile String overlayStatus = "nicht geprüft";
    /** Vollständiger Klassenname von Litematicas OverlayType-Enum (aus der Methodensignatur gelesen). */
    public static volatile String overlayTypeClassName = null;

    static final String OVERLAY_CLASS = "fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo";

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.endsWith("LitematicaOverlayTypeMixin")) return true;
        try {
            ClassNode node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
            for (MethodNode m : node.methods) {
                if (!m.name.equals("getOverlayType")) continue;
                if ((m.access & Opcodes.ACC_STATIC) != 0) {
                    overlayStatus = "getOverlayType ist static – Mixin übersprungen";
                    return false;
                }
                Type[] args = Type.getArgumentTypes(m.desc);
                Type ret = Type.getReturnType(m.desc);
                if (args.length == 2 && args[0].equals(args[1]) && ret.getSort() == Type.OBJECT && ret.getInternalName().contains("OverlayType")) {
                    overlayStatus = "aktiv";
                    overlayTypeClassName = ret.getClassName();
                    overlayMixinApplied = true;
                    return true;
                }
            }
            overlayStatus = "getOverlayType(BlockState, BlockState) nicht gefunden – Mixin übersprungen";
        } catch (Throwable t) {
            // Prüfung selbst nicht möglich -> trotzdem anwenden; der Mixin ist mit require=0 ungefährlich
            overlayStatus = "aktiv (ungeprüft)";
            overlayMixinApplied = true;
            return true;
        }
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
