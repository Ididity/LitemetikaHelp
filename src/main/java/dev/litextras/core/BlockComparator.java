package dev.litextras.core;

import dev.litextras.config.Cfg;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.state.property.Property;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DIE zentrale Vergleichslogik Soll (Schematic) gegen Ist (Welt).
 * Overlay-Mixin, HUD, Item-Markierung, Ebenenliste, Durchsicht, /missing und KI benutzen alle
 * ausschließlich diese Klasse – dadurch können die Anzeigen sich nicht widersprechen.
 *
 * Zwei Prüfebenen:
 *  (a) Blocktyp: exakt, oder – wenn "Ähnliche Blöcke" an ist – gleiche Äquivalenzgruppe.
 *  (b) Properties: standardmäßig STRENG (Ausrichtung, half/type/shape/face, waterlogged, open/hinge/…),
 *      außer Ignorierliste bzw. propertyMode der Gruppe.
 *
 * Thread-sicher (wird auch vom Hintergrund-Scanner benutzt).
 */
public final class BlockComparator {
    private BlockComparator() {}

    private static volatile Settings settings = null;

    /** Momentaufnahme der relevanten Config-Werte – einmal pro Config-Generation gebaut. */
    private record Settings(long generation, boolean similar, boolean ignoreAlways, boolean reportExtra,
                            Set<String> ignored, Set<String> orientation) {}

    private static Settings settings() {
        Settings s = settings;
        long gen = Cfg.generation();
        if (s == null || s.generation != gen) {
            EquivalenceGroups.ensureBuilt();
            s = new Settings(gen,
                    Cfg.bool("compare.similar.enabled"),
                    Cfg.bool("compare.ignoredPropertiesAlsoWhenExact"),
                    Cfg.bool("compare.reportExtra"),
                    Set.copyOf(Cfg.list("compare.ignoredProperties")),
                    Set.copyOf(Cfg.list("compare.orientationProperties")));
            settings = s;
        }
        return s;
    }

    public static boolean similarEnabled() { return settings().similar; }

    /** Luft, oder nur eine Flüssigkeit (Wasser/Lava als eigener Block) – zählt als "leer". */
    public static boolean isEmpty(BlockState s) {
        return s == null || s.isAir() || s.getBlock() instanceof FluidBlock;
    }

    public static CompareResult compare(BlockState soll, BlockState ist) {
        Settings st = settings();
        boolean sollEmpty = isEmpty(soll);
        boolean istEmpty = isEmpty(ist);

        if (sollEmpty) {
            if (istEmpty || !st.reportExtra) return new CompareResult(CompareResult.Status.NONE, soll, ist, List.of(), null);
            return new CompareResult(CompareResult.Status.EXTRA, soll, ist, List.of(), null);
        }
        if (istEmpty) return new CompareResult(CompareResult.Status.MISSING, soll, ist, List.of(), null);

        // ---- (a) Blocktyp
        boolean sameBlock = soll.getBlock() == ist.getBlock();
        EquivalenceGroups.Group group = null;
        boolean typeOk = sameBlock;
        if (!sameBlock && st.similar) {
            group = EquivalenceGroups.commonGroup(soll, ist);
            typeOk = group != null;
        }
        if (sameBlock && soll == ist) {
            return new CompareResult(CompareResult.Status.CORRECT, soll, ist, List.of(), null);
        }

        // ---- (b) Properties
        EquivalenceGroups.PropertyMode mode = group != null ? group.mode() : EquivalenceGroups.PropertyMode.STRICT;
        boolean useIgnore = st.similar || st.ignoreAlways;
        Set<String> allowMissing = group != null ? group.allowMissing() : Set.of();
        List<CompareResult.PropDiff> diffs = propertyDiffs(soll, ist, mode, useIgnore ? st.ignored : Set.of(), st.orientation, allowMissing);

        // Truhen: Blickrichtung egal. Wichtig ist nur einzeln/doppelt und – bei doppelt – dass die zweite
        // Hälfte auf der richtigen Seite liegt (die Doppeltruhe also an der richtigen Stelle steht).
        if (typeOk && Cfg.bool("compare.chestIgnoreFacing") && soll.getBlock() instanceof ChestBlock && ist.getBlock() instanceof ChestBlock) {
            diffs = chestDiffs(soll, ist, diffs);
        }
        if (!typeOk) {
            return new CompareResult(CompareResult.Status.WRONG_TYPE, soll, ist, diffs, null);
        }
        if (!diffs.isEmpty()) {
            return new CompareResult(CompareResult.Status.WRONG_STATE, soll, ist, diffs, group != null ? group.name() : null);
        }
        return new CompareResult(sameBlock ? CompareResult.Status.CORRECT : CompareResult.Status.SIMILAR, soll, ist, List.of(),
                group != null ? group.name() : null);
    }

    /**
     * Vergleicht nur Properties, die der Soll-Block hat.
     * - Beide haben die Property: Werte müssen gleich sein.
     * - Nur Soll hat sie: Abweichung, außer die Gruppe erlaubt das (allowMissingProperties).
     * - Nur Ist hat sie: wird nicht bewertet (z. B. Stein-Soll, Stamm-Ist: axis egal).
     */
    public static List<CompareResult.PropDiff> propertyDiffs(BlockState soll, BlockState ist, EquivalenceGroups.PropertyMode mode,
                                                             Set<String> ignored, Set<String> orientation, Set<String> allowMissing) {
        if (mode == EquivalenceGroups.PropertyMode.IGNORE) return List.of();
        List<CompareResult.PropDiff> out = new ArrayList<>(2);
        Set<String> seen = new HashSet<>();
        for (Property<?> p : soll.getProperties()) {
            String name = p.getName();
            if (!seen.add(name)) continue;
            if (ignored.contains(name)) continue;
            if (mode == EquivalenceGroups.PropertyMode.ORIENTATION_ONLY && !orientation.contains(name)) continue;
            String expected = valueName(soll, p);
            Property<?> other = findByName(ist, name);
            if (other == null) {
                if (allowMissing.contains(name) || allowMissing.contains("*")) continue;
                out.add(new CompareResult.PropDiff(name, expected, "—"));
                continue;
            }
            String actual = valueName(ist, other);
            if (!expected.equals(actual)) out.add(new CompareResult.PropDiff(name, expected, actual));
        }
        return out;
    }

    private static List<CompareResult.PropDiff> chestDiffs(BlockState soll, BlockState ist, List<CompareResult.PropDiff> diffs) {
        List<CompareResult.PropDiff> out = new ArrayList<>(diffs.size());
        for (CompareResult.PropDiff d : diffs) if (!d.name().equals("facing") && !d.name().equals("type")) out.add(d);
        String ts = prop(soll, "type"), ti = prop(ist, "type");
        if (ts == null || ti == null) return diffs;
        boolean sDouble = !ts.equals("single"), iDouble = !ti.equals("single");
        if (sDouble != iDouble) {
            out.add(new CompareResult.PropDiff("type", sDouble ? "doppelt" : "einzeln", iDouble ? "doppelt" : "einzeln"));
        } else if (sDouble) {
            String ps = partnerSide(prop(soll, "facing"), ts), pi = partnerSide(prop(ist, "facing"), ti);
            if (ps != null && !ps.equals(pi)) out.add(new CompareResult.PropDiff("zweite Hälfte", ps, pi == null ? "—" : pi));
        }
        return out;
    }

    private static String prop(BlockState s, String name) {
        Property<?> p = findByName(s, name);
        return p == null ? null : valueName(s, p);
    }

    /** Richtung, in der die zweite Truhenhälfte liegt (wie Minecrafts ChestBlock.getFacing). */
    static String partnerSide(String facing, String type) {
        if (facing == null) return null;
        String[] cw = {"north", "east", "south", "west"};
        int i = java.util.Arrays.asList(cw).indexOf(facing);
        if (i < 0) return null;
        if (type.equals("left")) return cw[(i + 1) % 4];
        if (type.equals("right")) return cw[(i + 3) % 4];
        return null;
    }

    public static Property<?> findByName(BlockState s, String name) {
        for (Property<?> p : s.getProperties()) if (p.getName().equals(name)) return p;
        return null;
    }

    public static String valueName(BlockState s, Property<?> p) {
        return name(s, p);
    }

    private static <T extends Comparable<T>> String name(BlockState s, Property<T> p) {
        return p.name(s.get(p));
    }

    /** Alle Properties eines States in Reihenfolge (für die HUD-Anzeige). */
    public static List<String[]> allProps(BlockState s) {
        List<String[]> out = new ArrayList<>();
        for (Property<?> p : s.getProperties()) out.add(new String[]{p.getName(), valueName(s, p)});
        return out;
    }

    public static boolean isIgnored(String prop) {
        Settings st = settings();
        return (st.similar || st.ignoreAlways) && st.ignored.contains(prop);
    }
}
