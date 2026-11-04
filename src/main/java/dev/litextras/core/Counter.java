package dev.litextras.core;

import dev.litextras.config.Cfg;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durchsatz-Zähler wie der Counter von Carpet: Ein Behälter (Kiste/Barrel/Trichter/Shulker) wird als
 * Zähler festgelegt. Alles, was dort hineinkommt, wird gezählt und als "pro Stunde, wenn es so weitergeht"
 * hochgerechnet.
 *
 * Wichtig (nur-lesend, serverfreundlich): Der Client kennt den Inhalt eines Behälters nur, solange er
 * GEÖFFNET ist. Darum zählt der Zähler, während du den Behälter offen hast (z. B. AFK mit offener Kiste).
 * Die Zeit läuft über Client-Ticks – dadurch pausiert sie automatisch im Esc-Menü/ohne geladene Welt und
 * läuft weiter, sobald du wieder drin bist. Es wird NICHTS am Server verändert.
 */
public final class Counter {
    private Counter() {}

    private static BlockPos counterPos = null;       // festgelegter Zähler-Block
    private static BlockPos lastLookedPos = null;    // zuletzt angeschauter Block (vor dem Öffnen)
    private static boolean counterOpen = false;      // war der Zähler-Behälter letzten Tick offen?
    private static boolean haveBaseline = false;
    private static final Map<Item, Integer> lastSeen = new HashMap<>();

    private static boolean running = false;
    private static long elapsedTicks = 0;            // nur hochgezählt, solange "running"
    private static long totalCounted = 0;
    private static final Map<Item, Long> counted = new LinkedHashMap<>();

    public static boolean enabled() { return Cfg.bool("counter.enabled"); }
    public static boolean running() { return running; }
    public static boolean isSet() { return counterPos != null; }
    public static long total() { return totalCounted; }

    /** Items pro Stunde, wenn es im bisherigen Tempo weitergeht. */
    public static double perHour() {
        if (elapsedTicks <= 0) return 0;
        return totalCounted * 72000.0 / elapsedTicks;
    }

    public static double perHour(Item item) {
        if (elapsedTicks <= 0) return 0;
        return counted.getOrDefault(item, 0L) * 72000.0 / elapsedTicks;
    }

    public static long elapsedSeconds() { return elapsedTicks / 20; }

    public static Map<Item, Long> counted() { return counted; }

    // ---------------------------------------------------------------- Verkaufswert / Geld
    public static double sellMultiplier() {
        double m = Cfg.dbl("counter.sellMultiplier");
        return m <= 0 ? 1.0 : m;
    }

    public static double itemValue(Item item) {
        try {
            String id = net.minecraft.registry.Registries.ITEM.getId(item).toString();
            com.google.gson.JsonObject o = Cfg.jsonObject("counter.itemValues");
            if (o.has(id)) return o.get(id).getAsDouble();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    public static boolean anyValueSet() {
        try {
            return Cfg.jsonObject("counter.itemValues").size() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Verkaufswert der bisher gezählten Items (mit Multiplikator). */
    public static double moneyTotal() {
        double sum = 0;
        for (Map.Entry<Item, Long> e : counted.entrySet()) sum += e.getValue() * itemValue(e.getKey());
        return sum * sellMultiplier();
    }

    /** Verkaufswert pro Stunde, wenn es so weitergeht. */
    public static double moneyPerHour() {
        if (elapsedTicks <= 0) return 0;
        return moneyTotal() * 72000.0 / elapsedTicks;
    }

    /** Preis pro Item für das angegebene Item setzen (0 = entfernen). */
    public static void setItemValue(Item item, double value) {
        try {
            String id = net.minecraft.registry.Registries.ITEM.getId(item).toString();
            com.google.gson.JsonObject o = Cfg.jsonObject("counter.itemValues").deepCopy();
            if (value <= 0) o.remove(id);
            else o.addProperty(id, value);
            Cfg.manager().setPersistent("counter.itemValues", o);
        } catch (Throwable ignored) {
        }
    }

    public static void setSellMultiplier(double m) {
        Cfg.manager().setPersistent("counter.sellMultiplier", new com.google.gson.JsonPrimitive(Math.max(0, m)));
    }

    /** Zähler auf den gerade angeschauten Block setzen. */
    public static boolean setHere(MinecraftClient mc) {
        BlockPos p = lookedBlock(mc);
        if (p == null) return false;
        counterPos = p;
        Cfg.manager().setPersistent("counter.enabled", new com.google.gson.JsonPrimitive(true));
        reset();
        return true;
    }

    public static void reset() {
        running = false;
        elapsedTicks = 0;
        totalCounted = 0;
        counted.clear();
        lastSeen.clear();
        haveBaseline = false;
    }

    public static void setActive(boolean on) {
        Cfg.manager().setPersistent("counter.enabled", new com.google.gson.JsonPrimitive(on));
    }

    private static BlockPos lookedBlock(MinecraftClient mc) {
        HitResult h = mc.crosshairTarget;
        if (h instanceof BlockHitResult bhr && h.getType() == HitResult.Type.BLOCK) return bhr.getBlockPos();
        return null;
    }

    /** Leeren-Modus nur im Einzelspieler möglich (dort ist dein Spiel selbst der Server). */
    public static boolean clearMode(MinecraftClient mc) {
        return !dev.litextras.Edition.LEGIT && Cfg.bool("counter.clearContainer") && mc.getServer() != null;
    }

    public static boolean clearModeConfigured() { return Cfg.bool("counter.clearContainer"); }

    /** Haupt-Thread, jeden Tick (nur wenn die Welt geladen ist – dadurch pausiert alles im Esc-Menü). */
    public static void tick(MinecraftClient mc) {
        if (!enabled() || mc.player == null) return;

        // Merke dir, welchen Block du anschaust, solange kein Menü offen ist (das ist der Behälter, den du gleich öffnest).
        if (mc.currentScreen == null) {
            BlockPos look = lookedBlock(mc);
            if (look != null) lastLookedPos = look;
        }

        if (clearMode(mc) && counterPos != null) {
            // Einzelspieler: Behälter direkt am integrierten Server lesen, ALLES zählen und leeren (wie Carpet).
            sampleAndClearServer(mc);
        } else {
            // Mehrspieler / Leeren aus: zählen, solange der Behälter geöffnet ist.
            boolean open = counterPos != null
                    && mc.currentScreen instanceof HandledScreen<?> hs
                    && !(hs.getScreenHandler() == null)
                    && counterPos.equals(lastLookedPos);
            if (open) {
                if (!counterOpen) haveBaseline = false; // frisch geöffnet -> erst Grundstand merken
                sample(((HandledScreen<?>) mc.currentScreen).getScreenHandler(), mc.player.getInventory());
            }
            counterOpen = open;
        }

        if (running) elapsedTicks++;
    }

    /** Einzelspieler: Inhalt des Behälters am Server-Thread lesen, zählen und löschen. */
    private static void sampleAndClearServer(MinecraftClient mc) {
        var server = mc.getServer();
        if (server == null || mc.world == null) return;
        final BlockPos pos = counterPos;
        server.execute(() -> {
            try {
                var world = server.getWorld(mc.world.getRegistryKey());
                if (world == null) return;
                var be = world.getBlockEntity(pos);
                if (!(be instanceof Inventory inv)) return;
                Map<Item, Integer> got = new HashMap<>();
                int size = inv.size();
                for (int i = 0; i < size; i++) {
                    ItemStack st = inv.getStack(i);
                    if (st == null || st.isEmpty()) continue;
                    got.merge(st.getItem(), st.getCount(), Integer::sum);
                }
                if (got.isEmpty()) return;
                inv.clear();
                inv.markDirty();
                be.markDirty();
                // Zählung auf dem Hauptthread anwenden
                mc.execute(() -> addCounts(got));
            } catch (Throwable ignored) {
            }
        });
    }

    /** Alles Gezählte dazurechnen (im Leeren-Modus kommt alles als Zuwachs). */
    private static synchronized void addCounts(Map<Item, Integer> got) {
        for (Map.Entry<Item, Integer> e : got.entrySet()) {
            if (!running) running = true;
            counted.merge(e.getKey(), (long) e.getValue(), Long::sum);
            totalCounted += e.getValue();
        }
    }

    /** Behälter-Slots lesen (alle Slots, die NICHT zum Spielerinventar gehören) und Zuwächse zählen. */
    private static void sample(ScreenHandler handler, PlayerInventory playerInv) {
        if (handler == null || handler.slots == null) return;
        Map<Item, Integer> now = new HashMap<>();
        try {
            for (Slot s : handler.slots) {
                if (s == null || s.inventory == playerInv) continue; // Spielerinventar überspringen
                ItemStack st = s.getStack();
                if (st == null || st.isEmpty()) continue;
                now.merge(st.getItem(), st.getCount(), Integer::sum);
            }
        } catch (Throwable t) {
            return;
        }
        if (!haveBaseline) {
            lastSeen.clear();
            lastSeen.putAll(now);
            haveBaseline = true;
            return;
        }
        for (Map.Entry<Item, Integer> e : now.entrySet()) {
            int prev = lastSeen.getOrDefault(e.getKey(), 0);
            int delta = e.getValue() - prev;
            if (delta > 0) {
                if (!running) { running = true; } // automatischer Start beim ersten Item
                counted.merge(e.getKey(), (long) delta, Long::sum);
                totalCounted += delta;
            }
        }
        lastSeen.clear();
        lastSeen.putAll(now);
    }

    /** Die am meisten gezählten Items zuerst (für die HUD-Anzeige). */
    public static List<Map.Entry<Item, Long>> topItems(int max) {
        List<Map.Entry<Item, Long>> list = new java.util.ArrayList<>(counted.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list.size() > max ? list.subList(0, max) : list;
    }
}
