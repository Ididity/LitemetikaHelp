package dev.litextras.core;

import dev.litextras.LitematicaExtras;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Vec3i;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Baut den Plan wirklich um: ersetzt in der geladenen Litematica-Schematic alle Blöcke vom Typ A durch Typ B
 * (z. B. Eichenholz durch Fichtenholz). Ausrichtung und andere Eigenschaften werden übernommen, wenn der
 * neue Block sie auch hat (Treppe nach Norden bleibt Treppe nach Norden).
 *
 * Die Änderung betrifft die Schematic im Speicher. Die Datei auf der Festplatte bleibt unverändert,
 * bis man sie mit {@link #saveCopy()} als neue Datei speichert. Rückgängig mit {@link #undo()}.
 * Nur Haupt-Thread.
 */
public final class SchematicReplacer {
    private SchematicReplacer() {}

    public record Result(int replaced, int schematics, List<String> notes) {}

    private record Change(LitematicaBlockStateContainer container, int x, int y, int z, BlockState old) {}

    private static List<Change> lastChanges = List.of();
    private static Set<LitematicaSchematic> lastSchematics = Set.of();

    /** Betroffene Schematics: die ausgewählte Platzierung, sonst alle aktiven. */
    private static Set<LitematicaSchematic> targets() {
        Set<LitematicaSchematic> out = new LinkedHashSet<>();
        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        SchematicPlacement sel = mgr.getSelectedSchematicPlacement();
        if (sel != null && sel.getSchematic() != null) {
            out.add(sel.getSchematic());
            return out;
        }
        for (SchematicPlacement p : mgr.getAllSchematicsPlacements()) {
            if (p.isEnabled() && p.getSchematic() != null) out.add(p.getSchematic());
        }
        return out;
    }

    public static Result replace(Block from, Block to) {
        List<String> notes = new ArrayList<>();
        Set<LitematicaSchematic> schems = targets();
        if (schems.isEmpty()) {
            notes.add("Keine Litematica-Platzierung geladen.");
            return new Result(0, 0, notes);
        }
        List<Change> changes = new ArrayList<>();
        Map<BlockState, BlockState> cache = new IdentityHashMap<>();
        int skipped = 0;
        for (LitematicaSchematic schem : schems) {
            for (String region : schem.getAreas().keySet()) {
                LitematicaBlockStateContainer c = schem.getSubRegionContainer(region);
                if (c == null) continue;
                Vec3i size = c.getSize();
                for (int y = 0; y < size.getY(); y++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        for (int x = 0; x < size.getX(); x++) {
                            BlockState old = c.get(x, y, z);
                            if (old == null || old.getBlock() != from) continue;
                            BlockState neu = cache.computeIfAbsent(old, o -> convert(o, to));
                            if (neu == null) { skipped++; continue; }
                            c.set(x, y, z, neu);
                            changes.add(new Change(c, x, y, z, old));
                        }
                    }
                }
            }
        }
        if (skipped > 0) notes.add(skipped + " Blöcke konnten nicht umgewandelt werden und blieben unverändert.");
        lastChanges = changes;
        lastSchematics = schems;
        refresh(schems);
        return new Result(changes.size(), schems.size(), notes);
    }

    /** Letzten Tausch rückgängig machen. */
    public static int undo() {
        List<Change> ch = lastChanges;
        if (ch.isEmpty()) return 0;
        for (int i = ch.size() - 1; i >= 0; i--) {
            Change c = ch.get(i);
            c.container().set(c.x(), c.y(), c.z(), c.old());
        }
        lastChanges = List.of();
        refresh(lastSchematics);
        return ch.size();
    }

    /** Speichert die geänderte Schematic als NEUE Datei "<name>_getauscht.litematic" neben der alten. */
    public static List<String> saveCopy() {
        List<String> msgs = new ArrayList<>();
        for (LitematicaSchematic s : targets()) {
            Path f = s.getFile();
            if (f == null) {
                msgs.add("Schematic hat keine Datei (nur im Speicher) – nicht gespeichert.");
                continue;
            }
            String name = f.getFileName().toString().replaceAll("\\.litematic$", "") + "_getauscht";
            boolean ok;
            try {
                ok = s.writeToFile(f.getParent(), name, false);
            } catch (Throwable t) {
                ok = false;
            }
            msgs.add(ok ? "Gespeichert als " + name + ".litematic" : "Speichern fehlgeschlagen (gibt es " + name + ".litematic schon?).");
        }
        return msgs;
    }

    /** Neuer Zustand mit allen übertragbaren Eigenschaften des alten. */
    static BlockState convert(BlockState old, Block to) {
        BlockState s = to.getDefaultState();
        for (Property<?> p : old.getProperties()) {
            Property<?> target = BlockComparator.findByName(s, p.getName());
            if (target == null) continue;
            s = copy(s, target, BlockComparator.valueName(old, p));
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState s, Property<T> prop, String value) {
        Optional<T> v = prop.parse(value);
        return v.isPresent() ? s.with(prop, v.get()) : s;
    }

    /** Litematica neu zeichnen lassen und Materialliste neu berechnen. */
    private static void refresh(Set<LitematicaSchematic> schems) {
        try {
            SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
            for (LitematicaSchematic s : schems) mgr.markAllPlacementsOfSchematicForRebuild(s);
            for (SchematicPlacement p : mgr.getAllSchematicsPlacements()) {
                if (!schems.contains(p.getSchematic())) continue;
                MaterialListBase ml = p.getMaterialList();
                if (ml != null) ml.reCreateMaterialList();
            }
            MaterialListBase global = DataManager.getMaterialList();
            if (global != null) global.reCreateMaterialList();
        } catch (Throwable t) {
            LitematicaExtras.debug("Refresh nach Tausch fehlgeschlagen: " + t);
        }
        ErrorScanner.invalidateAll();
    }
}
