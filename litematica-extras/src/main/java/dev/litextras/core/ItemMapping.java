package dev.litextras.core;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zuordnung Block-State -> benötigtes Item (inkl. Anzahl).
 * - Luft, Flüssigkeiten, Feuer, Portale usw.: kein Item.
 * - Mehrteiler: obere Tür-/Pflanzenhälfte und Bett-Kopfteil zählen 0 (nur das untere/Fußteil zählt 1).
 * - Doppelstufe: 2 Stufen. Kerzen, Seegurken, Schildkröteneier, Schneeschichten, Blüten: Anzahl aus der Property.
 * Thread-sicher.
 */
public final class ItemMapping {
    private ItemMapping() {}

    public record ItemReq(Item item, int count) {}

    private static final ConcurrentHashMap<BlockState, List<ItemReq>> CACHE = new ConcurrentHashMap<>();

    /** Blöcke ohne Item. */
    private static final Set<Block> NO_ITEM = Set.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.MOVING_PISTON, Blocks.PISTON_HEAD, Blocks.BUBBLE_COLUMN,
            Blocks.WATER, Blocks.LAVA);

    /** Property-Namen, deren Wert die Item-Anzahl angibt. */
    private static final Set<String> COUNT_PROPS = Set.of("candles", "pickles", "eggs", "flower_amount", "segment_amount");

    /** Blöcke, deren Item anders heißt bzw. die kein direktes BlockItem haben (ID -> ID). */
    private static final Map<String, String> FALLBACK = new HashMap<>();
    static {
        FALLBACK.put("minecraft:farmland", "minecraft:dirt");
        FALLBACK.put("minecraft:dirt_path", "minecraft:dirt_path");
        FALLBACK.put("minecraft:kelp_plant", "minecraft:kelp");
        FALLBACK.put("minecraft:cave_vines", "minecraft:glow_berries");
        FALLBACK.put("minecraft:cave_vines_plant", "minecraft:glow_berries");
        FALLBACK.put("minecraft:twisting_vines_plant", "minecraft:twisting_vines");
        FALLBACK.put("minecraft:weeping_vines_plant", "minecraft:weeping_vines");
        FALLBACK.put("minecraft:bamboo_sapling", "minecraft:bamboo");
        FALLBACK.put("minecraft:big_dripleaf_stem", "minecraft:big_dripleaf");
        FALLBACK.put("minecraft:pumpkin_stem", "minecraft:pumpkin_seeds");
        FALLBACK.put("minecraft:attached_pumpkin_stem", "minecraft:pumpkin_seeds");
        FALLBACK.put("minecraft:melon_stem", "minecraft:melon_seeds");
        FALLBACK.put("minecraft:attached_melon_stem", "minecraft:melon_seeds");
        FALLBACK.put("minecraft:tall_seagrass", "minecraft:seagrass");
        FALLBACK.put("minecraft:powder_snow", "minecraft:powder_snow_bucket");
        FALLBACK.put("minecraft:redstone_wire", "minecraft:redstone");
        FALLBACK.put("minecraft:tripwire", "minecraft:string");
        FALLBACK.put("minecraft:frosted_ice", "minecraft:ice");
    }

    public static List<ItemReq> required(BlockState s) {
        if (s == null) return List.of();
        return CACHE.computeIfAbsent(s, ItemMapping::compute);
    }

    /** Erstes benötigtes Item oder null. */
    public static Item primary(BlockState s) {
        List<ItemReq> r = required(s);
        return r.isEmpty() ? null : r.get(0).item();
    }

    private static List<ItemReq> compute(BlockState s) {
        Block b = s.getBlock();
        if (s.isAir() || NO_ITEM.contains(b) || b instanceof FluidBlock) return List.of();

        // Mehrteiler: nur die "Haupt"-Hälfte zählt
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return List.of();
        if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD) return List.of();

        Item item = itemFor(b);
        if (item == null || item == Items.AIR) return List.of();

        int count = 1;
        if (s.contains(Properties.SLAB_TYPE) && s.get(Properties.SLAB_TYPE) == SlabType.DOUBLE) count = 2;
        if (b == Blocks.SNOW && s.contains(Properties.LAYERS)) count = s.get(Properties.LAYERS);
        for (Property<?> p : s.getProperties()) {
            if (COUNT_PROPS.contains(p.getName())) {
                try {
                    count = Integer.parseInt(BlockComparator.valueName(s, p));
                } catch (NumberFormatException ignored) {}
            }
        }
        return List.of(new ItemReq(item, Math.max(1, count)));
    }

    private static Item itemFor(Block b) {
        Identifier id = Registries.BLOCK.getId(b);
        String fb = FALLBACK.get(id.toString());
        if (fb != null) {
            Identifier fid = Identifier.of(fb);
            if (Registries.ITEM.containsId(fid)) return Registries.ITEM.get(fid);
        }
        Item i = b.asItem();
        if (i != null && i != Items.AIR) return i;
        // Topfpflanzen -> Blumentopf
        if (id.getPath().startsWith("potted_")) return Items.FLOWER_POT;
        return null;
    }

    /** Passt dieses Item zum Soll-State? Mit "Ähnliche Blöcke" auch Items derselben Gruppe. */
    public static boolean matches(BlockState soll, Item candidate) {
        if (candidate == null || candidate == Items.AIR) return false;
        Item need = primary(soll);
        if (need == candidate) return true;
        if (BlockComparator.similarEnabled() && candidate instanceof BlockItem bi) {
            return EquivalenceGroups.sameGroupType(soll.getBlock(), bi.getBlock());
        }
        return false;
    }

    public static void clearCache() { CACHE.clear(); }
}
