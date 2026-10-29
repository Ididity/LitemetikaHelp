package dev.litextras.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.litextras.config.Cfg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 1: Äquivalenzgruppen ("ähnliche Blöcke"). Gruppen kommen aus der Config (Tags oder IDs).
 * Zusätzlich die Pseudo-Gruppe "solide Blöcke": voller Quader als Kollisionsmodell und keine
 * Redstone-Eigenschaften (Signalquelle, Comparator-Ausgang, BlockEntity) und nicht auf der Blacklist.
 */
public final class EquivalenceGroups {
    private EquivalenceGroups() {}

    public enum PropertyMode { STRICT, ORIENTATION_ONLY, IGNORE }

    public record Group(String name, PropertyMode mode, Set<String> allowMissing, List<Matcher> members) {}

    /** Ein Gruppenmitglied: Tag oder einzelne Block-ID. */
    public record Matcher(TagKey<Block> tag, Identifier id) {
        boolean matches(BlockState s) {
            if (tag != null) return s.isIn(tag);
            return Registries.BLOCK.getId(s.getBlock()).equals(id);
        }
    }

    public static final Group SOLID = new Group("Solide Blöcke", PropertyMode.STRICT, Set.of(), List.of());

    private static volatile List<Group> groups = List.of();
    private static volatile List<Matcher> solidBlacklist = List.of();
    private static volatile Group solidGroup = SOLID;
    private static final ConcurrentHashMap<Block, Integer> GROUP_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockState, Boolean> SOLID_CACHE = new ConcurrentHashMap<>();
    private static long builtGeneration = -1;
    private static final List<String> problems = new ArrayList<>();

    /** Aus Config neu aufbauen (bei Reload/Weltbeitritt, weil Tags vom Server kommen). */
    public static synchronized List<String> rebuild() {
        problems.clear();
        List<Group> gs = new ArrayList<>();
        for (JsonElement el : Cfg.jsonArray("compare.groups")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("enabled") && !o.get("enabled").getAsBoolean()) continue;
            String name = o.has("name") ? o.get("name").getAsString() : "Gruppe " + (gs.size() + 1);
            PropertyMode mode = parseMode(o.has("propertyMode") ? o.get("propertyMode").getAsString() : "strict", name);
            Set<String> allow = new HashSet<>();
            if (o.has("allowMissingProperties") && o.get("allowMissingProperties").isJsonArray()) {
                for (JsonElement a : o.getAsJsonArray("allowMissingProperties")) allow.add(a.getAsString());
            }
            List<Matcher> members = new ArrayList<>();
            if (o.has("members") && o.get("members").isJsonArray()) {
                for (JsonElement m : o.getAsJsonArray("members")) {
                    Matcher mt = parseMatcher(m.getAsString(), "Gruppe '" + name + "'");
                    if (mt != null) members.add(mt);
                }
            }
            gs.add(new Group(name, mode, Set.copyOf(allow), List.copyOf(members)));
        }
        List<Matcher> bl = new ArrayList<>();
        for (String s : Cfg.list("compare.similar.solidBlacklist")) {
            Matcher m = parseMatcher(s, "solidBlacklist");
            if (m != null) bl.add(m);
        }
        groups = List.copyOf(gs);
        solidBlacklist = List.copyOf(bl);
        solidGroup = new Group(SOLID.name(), SOLID.mode(), Set.copyOf(Cfg.list("compare.similar.solidAllowMissingProperties")), List.of());
        GROUP_CACHE.clear();
        SOLID_CACHE.clear();
        builtGeneration = Cfg.generation();
        return List.copyOf(problems);
    }

    public static void ensureBuilt() {
        if (builtGeneration != Cfg.generation()) rebuild();
    }

    /** Nach Weltwechsel (andere Tags) Caches leeren. */
    public static void clearCaches() {
        GROUP_CACHE.clear();
        SOLID_CACHE.clear();
    }

    private static PropertyMode parseMode(String s, String group) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "strict", "streng" -> PropertyMode.STRICT;
            case "orientation_only", "orientation", "ausrichtung" -> PropertyMode.ORIENTATION_ONLY;
            case "ignore", "locker", "none" -> PropertyMode.IGNORE;
            default -> {
                problems.add("Gruppe '" + group + "': unbekannter propertyMode '" + s + "', benutze strict.");
                yield PropertyMode.STRICT;
            }
        };
    }

    private static Matcher parseMatcher(String raw, String where) {
        String s = raw.trim();
        try {
            if (s.startsWith("#")) {
                return new Matcher(TagKey.of(RegistryKeys.BLOCK, Identifier.of(s.substring(1))), null);
            }
            Identifier id = Identifier.of(s);
            if (!Registries.BLOCK.containsId(id)) {
                problems.add(where + ": Block '" + s + "' unbekannt – ignoriert.");
                return null;
            }
            return new Matcher(null, id);
        } catch (Exception e) {
            problems.add(where + ": ungültiger Eintrag '" + s + "' – ignoriert.");
            return null;
        }
    }

    /** Gruppe eines Blocks (über dessen Standard-State), oder null. Index -1 = keine Gruppe. */
    public static Group groupOf(Block block) {
        List<Group> gs = groups;
        int idx = GROUP_CACHE.computeIfAbsent(block, b -> {
            BlockState s = b.getDefaultState();
            for (int i = 0; i < gs.size(); i++) {
                for (Matcher m : gs.get(i).members()) if (m.matches(s)) return i;
            }
            return -1;
        });
        return idx >= 0 && idx < gs.size() ? gs.get(idx) : null;
    }

    /**
     * Gemeinsame Gruppe von Soll und Ist, oder null.
     * Reihenfolge: zuerst benannte Gruppen, dann "solide Blöcke".
     */
    public static Group commonGroup(BlockState soll, BlockState ist) {
        Group a = groupOf(soll.getBlock());
        if (a != null && a == groupOf(ist.getBlock())) return a;
        if (Cfg.bool("compare.similar.solidBlocks") && isPlainSolid(soll) && isPlainSolid(ist)) {
            return solidGroup;
        }
        return null;
    }

    /** Für die Item-Suche: dürfen diese beiden Block-Typen einander ersetzen? */
    public static boolean sameGroupType(Block soll, Block candidate) {
        if (soll == candidate) return true;
        Group a = groupOf(soll);
        if (a != null && a == groupOf(candidate)) return true;
        return Cfg.bool("compare.similar.solidBlocks") && isPlainSolid(soll.getDefaultState()) && isPlainSolid(candidate.getDefaultState());
    }

    /** Voller Quader (Kollision), keine Redstone-Eigenschaften, keine BlockEntity, nicht auf Blacklist. */
    public static boolean isPlainSolid(BlockState s) {
        return SOLID_CACHE.computeIfAbsent(s, st -> {
            try {
                if (st.isAir()) return false;
                if (!Block.isShapeFullCube(st.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN))) return false;
                // Muss Redstone so leiten wie ein normaler Vollblock (Minecrafts eigene Prüfung). Glas, Eis, Laub,
                // Seelensand usw. leiten nicht und gelten deshalb nie als "gleich" zu Stein & Co.
                if (!st.isSolidBlock(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) return false;
                if (st.emitsRedstonePower()) return false;
                if (st.hasComparatorOutput()) return false;
                if (st.hasBlockEntity()) return false;
                for (Matcher m : solidBlacklist) if (m.matches(st)) return false;
                return true;
            } catch (Throwable t) {
                return false;
            }
        });
    }
}
