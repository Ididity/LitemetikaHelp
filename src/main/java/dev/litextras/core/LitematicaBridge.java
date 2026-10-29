package dev.litextras.core;

import dev.litextras.LitematicaExtras;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ALLE Zugriffe auf Litematica laufen über diese eine Klasse. Wenn sich Litematicas API ändert,
 * muss nur diese Datei angepasst werden.
 *
 * UNSICHER (Litematica 0.26.x für 1.21.11): Die verwendeten Klassen/Methoden existieren seit vielen
 * Versionen (DataManager, SchematicWorldHandler, LayerRange, SchematicPlacement.getSubRegionBoxes).
 * Falls der Build hier scheitert, bitte die Fehlermeldung schicken.
 */
public final class LitematicaBridge {
    private LitematicaBridge() {}

    // ------------------------------------------------------------------ Datentypen
    public record IntBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static IntBox of(BlockPos a, BlockPos b) {
            return new IntBox(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }
        public long volume() { return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1); }
        public boolean contains(int x, int y, int z) { return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ; }
        public IntBox intersect(IntBox o) {
            int a = Math.max(minX, o.minX), b = Math.max(minY, o.minY), c = Math.max(minZ, o.minZ);
            int d = Math.min(maxX, o.maxX), e = Math.min(maxY, o.maxY), f = Math.min(maxZ, o.maxZ);
            return (a > d || b > e || c > f) ? null : new IntBox(a, b, c, d, e, f);
        }
        public int sizeX() { return maxX - minX + 1; }
        public int sizeY() { return maxY - minY + 1; }
        public int sizeZ() { return maxZ - minZ + 1; }
    }

    public record PlacementInfo(String name, String schematicName, BlockPos origin, IntBox enclosing, List<IntBox> boxes) {}

    /** Momentaufnahme des Layer-Modus (thread-sicher verwendbar). */
    public record LayerInfo(String mode, Direction.Axis axis, long min, long max, boolean restricting) {
        public boolean contains(int x, int y, int z) {
            if (!restricting) return true;
            long v = switch (axis) { case X -> x; case Y -> y; case Z -> z; };
            return v >= min && v <= max;
        }
        public IntBox clip(IntBox b) {
            if (!restricting) return b;
            int lo = (int) Math.max(Integer.MIN_VALUE, min), hi = (int) Math.min(Integer.MAX_VALUE, max);
            return switch (axis) {
                case X -> b.intersect(new IntBox(lo, b.minY(), b.minZ(), hi, b.maxY(), b.maxZ()));
                case Y -> b.intersect(new IntBox(b.minX(), lo, b.minZ(), b.maxX(), hi, b.maxZ()));
                case Z -> b.intersect(new IntBox(b.minX(), b.minY(), lo, b.maxX(), b.maxY(), hi));
            };
        }
        public String label() {
            String ax = axis.asString().toUpperCase();
            if (!restricting) return "Alle Ebenen";
            if (min == max) return "Ebene " + ax + "=" + min;
            String lo = min <= -RANGE ? "…" : String.valueOf(min);
            String hi = max >= RANGE ? "…" : String.valueOf(max);
            return "Ebenen " + ax + "=" + lo + "–" + hi;
        }
    }

    private static final long RANGE = 30_000_000L;

    // ------------------------------------------------------------------ Welt der Schematic
    /** Die "Geisterwelt" mit den Soll-Blöcken, oder null. */
    public static World schematicWorld() {
        try {
            return SchematicWorldHandler.getSchematicWorld();
        } catch (Throwable t) {
            return null;
        }
    }

    public static BlockState schematicState(World schematicWorld, BlockPos pos) {
        if (schematicWorld == null) return Blocks.AIR.getDefaultState();
        try {
            return schematicWorld.getBlockState(pos);
        } catch (Throwable t) {
            return Blocks.AIR.getDefaultState();
        }
    }

    // ------------------------------------------------------------------ Platzierungen
    /** Nur auf dem Haupt-Thread aufrufen. */
    public static List<PlacementInfo> placements() {
        List<PlacementInfo> out = new ArrayList<>();
        try {
            for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
                if (!p.isEnabled()) continue;
                List<IntBox> boxes = new ArrayList<>();
                Map<String, Box> map = p.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED);
                for (Box b : map.values()) {
                    if (b.getPos1() == null || b.getPos2() == null) continue;
                    boxes.add(IntBox.of(b.getPos1(), b.getPos2()));
                }
                if (boxes.isEmpty()) continue;
                int a = Integer.MAX_VALUE, bb = Integer.MAX_VALUE, c = Integer.MAX_VALUE, d = Integer.MIN_VALUE, e = Integer.MIN_VALUE, f = Integer.MIN_VALUE;
                for (IntBox x : boxes) {
                    a = Math.min(a, x.minX()); bb = Math.min(bb, x.minY()); c = Math.min(c, x.minZ());
                    d = Math.max(d, x.maxX()); e = Math.max(e, x.maxY()); f = Math.max(f, x.maxZ());
                }
                String schemName = "";
                try {
                    schemName = p.getSchematic().getMetadata().getName();
                } catch (Throwable ignored) {}
                out.add(new PlacementInfo(p.getName(), schemName, p.getOrigin(), new IntBox(a, bb, c, d, e, f), boxes));
            }
        } catch (Throwable t) {
            LitematicaExtras.debug("placements() fehlgeschlagen: " + t);
        }
        return out;
    }

    // ------------------------------------------------------------------ Layer
    /** Nur auf dem Haupt-Thread aufrufen. LayerRange/LayerMode liegen seit Malilib 0.27 in fi.dy.masa.malilib.util. */
    public static LayerInfo layer() {
        try {
            LayerRange r = DataManager.getRenderLayerRange();
            LayerMode mode = r.getLayerMode();
            Direction.Axis axis = axisOf(r);
            switch (mode) {
                case SINGLE_LAYER -> {
                    int v = r.getLayerSingle();
                    return new LayerInfo("single", axis, v, v, true);
                }
                case LAYER_RANGE -> {
                    return new LayerInfo("range", axis, r.getLayerRangeMin(), r.getLayerRangeMax(), true);
                }
                case ALL_ABOVE -> {
                    return new LayerInfo("above", axis, r.getLayerAbove(), RANGE, true);
                }
                case ALL_BELOW -> {
                    return new LayerInfo("below", axis, -RANGE, r.getLayerBelow(), true);
                }
                default -> {
                    return new LayerInfo("all", axis, -RANGE, RANGE, false);
                }
            }
        } catch (Throwable t) {
            return new LayerInfo("all", Direction.Axis.Y, -RANGE, RANGE, false);
        }
    }

    /** Achse per Reflection (Methodenname je nach Malilib-Version), Standard Y. */
    private static Direction.Axis axisOf(Object range) {
        for (String m : new String[]{"getAxis", "getLayerAxis"}) {
            try {
                Object a = range.getClass().getMethod(m).invoke(range);
                if (a instanceof Direction.Axis ax) return ax;
            } catch (Throwable ignored) {}
        }
        try {
            for (java.lang.reflect.Field f : range.getClass().getDeclaredFields()) {
                if (f.getType() == Direction.Axis.class) {
                    f.setAccessible(true);
                    Object a = f.get(range);
                    if (a instanceof Direction.Axis ax) return ax;
                }
            }
        } catch (Throwable ignored) {}
        return Direction.Axis.Y;
    }

    /** Fingerabdruck von Platzierungen + Layer – ändert er sich, wird neu gescannt. */
    public static int stateHash(List<PlacementInfo> placements, LayerInfo layer) {
        return Objects.hash(placements, layer);
    }

    // ------------------------------------------------------------------ "Durch Blöcke sehen" von Litematica
    private static Object renderThroughOption;
    private static boolean renderThroughLookedUp;

    /**
     * Litematica hat (seit langem) die Option "Overlay durch Blöcke rendern" (Configs.Visuals).
     * Zugriff per Reflection, damit ein umbenanntes Feld den Build nicht bricht.
     * UNSICHER: Feldname. Wir suchen nach mehreren Namen und nach "THROUGH" im Namen.
     */
    private static Object renderThroughOption() {
        if (renderThroughLookedUp) return renderThroughOption;
        renderThroughLookedUp = true;
        try {
            Class<?> visuals = Class.forName("fi.dy.masa.litematica.config.Configs$Visuals");
            for (String n : new String[]{"SCHEMATIC_OVERLAY_RENDER_THROUGH", "RENDER_SCHEMATIC_OVERLAY_THROUGH", "OVERLAY_RENDER_THROUGH"}) {
                try {
                    renderThroughOption = visuals.getField(n).get(null);
                    return renderThroughOption;
                } catch (NoSuchFieldException ignored) {}
            }
            for (Field f : visuals.getFields()) {
                if (f.getName().contains("OVERLAY") && f.getName().contains("THROUGH")) {
                    renderThroughOption = f.get(null);
                    return renderThroughOption;
                }
            }
        } catch (Throwable t) {
            LitematicaExtras.debug("Litematica Render-Through-Option nicht gefunden: " + t);
        }
        return null;
    }

    public static boolean hasRenderThrough() { return renderThroughOption() != null; }

    public static boolean setRenderThrough(boolean value) {
        Object o = renderThroughOption();
        if (o == null) return false;
        try {
            Method m = o.getClass().getMethod("setBooleanValue", boolean.class);
            m.invoke(o, value);
            return true;
        } catch (Throwable t) {
            LitematicaExtras.debug("setRenderThrough fehlgeschlagen: " + t);
            return false;
        }
    }
}
