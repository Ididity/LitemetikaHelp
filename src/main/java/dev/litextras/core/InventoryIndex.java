package dev.litextras.core;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Momentaufnahme des Inventars (Haupt-Thread), damit Hintergrund-Berechnungen sicher darauf zugreifen.
 * Slots: 0–8 Hotbar, 9–35 Hauptinventar, 36–39 Rüstung, 40 Offhand.
 */
public final class InventoryIndex {

    public record SlotStack(int slot, Item item, int count) {}

    private final List<SlotStack> stacks;
    private final Map<Item, Integer> totals;
    private final int hash;

    private InventoryIndex(List<SlotStack> stacks, Map<Item, Integer> totals, int hash) {
        this.stacks = stacks;
        this.totals = totals;
        this.hash = hash;
    }

    public static final InventoryIndex EMPTY = new InventoryIndex(List.of(), Map.of(), 0);

    /** Nur Haupt-Thread. */
    public static InventoryIndex capture(MinecraftClient mc) {
        if (mc.player == null) return EMPTY;
        PlayerInventory inv = mc.player.getInventory();
        List<SlotStack> list = new ArrayList<>();
        Map<Item, Integer> totals = new HashMap<>();
        int h = 1;
        int size = Math.min(inv.size(), 41);
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) continue;
            list.add(new SlotStack(i, st.getItem(), st.getCount()));
            if (i <= 35 || i == 40) totals.merge(st.getItem(), st.getCount(), Integer::sum);
            h = 31 * h + i * 977 + System.identityHashCode(st.getItem()) * 31 + st.getCount();
        }
        return new InventoryIndex(List.copyOf(list), Map.copyOf(totals), h);
    }

    public List<SlotStack> stacks() { return stacks; }
    public int hash() { return hash; }

    public int count(Item item) { return totals.getOrDefault(item, 0); }

    /** Anzahl aller Items, die zum Soll-State passen (mit "Ähnliche Blöcke" inkl. Gruppe). */
    public int countMatching(BlockState soll) {
        int n = 0;
        for (Map.Entry<Item, Integer> e : totals.entrySet()) if (ItemMapping.matches(soll, e.getKey())) n += e.getValue();
        return n;
    }

    /** Alle passenden Slots: zuerst exakt richtige Items, danach nur ähnliche (jeweils Hotbar, Inventar, Offhand). */
    public List<SlotStack> matchingSlots(BlockState soll) {
        List<SlotStack> exact = new ArrayList<>(), similar = new ArrayList<>();
        for (SlotStack s : stacks) {
            if (!(s.slot() <= 35 || s.slot() == 40) || !ItemMapping.matches(soll, s.item())) continue;
            (ItemMapping.isExact(soll, s.item()) ? exact : similar).add(s);
        }
        exact.addAll(similar);
        return exact;
    }
}
