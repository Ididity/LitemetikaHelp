package dev.litextras.core;

import dev.litextras.LitematicaExtras;
import dev.litextras.config.Cfg;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hintergrund-Prüfung vieler Positionen (Ebene, Umgebung, ganze Schematic) mit Caching.
 * - Läuft in einem eigenen Thread, nie im Render-Thread.
 * - Neu gerechnet wird nur bei Änderungen: Ebene/Platzierung, Config, Blockänderung in der Welt,
 *   Bewegung (Umgebung) – und gedrosselt nach den Intervallen aus der Config.
 *
 * Hinweis: Das Lesen der Welten aus einem Nebenthread ist bei Minecraft-Mods üblich, aber nicht
 * 100 % garantiert konsistent. Fehler beim Lesen werden abgefangen und der Scan später wiederholt.
 */
public final class ErrorScanner {
    private ErrorScanner() {}

    public enum Scope { LAYER, NEAR, ALL }

    public record ErrorEntry(BlockPos pos, CompareResult result, double distSq) {}

    /** Bedarf für eine Item-Zeile. key = "i:<item>" oder "g:<gruppe>" (bei "Ähnliche Blöcke"). */
    public record NeedEntry(String key, Item item, BlockState sample, String label, int need) {}

    public record ScanResult(Scope scope, String label, long finishedAt, long durationMs, BlockPos center,
                             int checked, int unloaded, boolean truncated,
                             Map<CompareResult.Status, Integer> byStatus,
                             List<ErrorEntry> errors, Map<String, NeedEntry> needs,
                             Map<String, NeedEntry> totalRequired, long configGeneration) {
        public int count(CompareResult.Status s) { return byStatus.getOrDefault(s, 0); }
        public int errorCount() {
            return count(CompareResult.Status.MISSING) + count(CompareResult.Status.WRONG_TYPE)
                    + count(CompareResult.Status.WRONG_STATE) + count(CompareResult.Status.EXTRA);
        }
        public int okCount() { return count(CompareResult.Status.CORRECT) + count(CompareResult.Status.SIMILAR); }
        /** Fortschritt in Prozent: richtige / (richtige + fehlende + falsche). */
        public double progressPercent() {
            int total = okCount() + count(CompareResult.Status.MISSING) + count(CompareResult.Status.WRONG_TYPE) + count(CompareResult.Status.WRONG_STATE);
            return total == 0 ? 100.0 : okCount() * 100.0 / total;
        }
    }

    private record Job(Scope scope, List<LitematicaBridge.IntBox> boxes, LitematicaBridge.LayerInfo layer, boolean layerFilter,
                       BlockPos center, int radius, World schematic, ClientWorld client, int maxBlocks, boolean computeTotals,
                       boolean includeWrongType, boolean includeWrongState, String label) {}

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LitematicaExtras-Scanner");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final Map<Scope, ScanResult> LATEST = new EnumMap<>(Scope.class);
    private static final Map<Scope, AtomicBoolean> RUNNING = new EnumMap<>(Scope.class);
    private static final Map<Scope, Long> LAST_START = new EnumMap<>(Scope.class);
    static {
        for (Scope s : Scope.values()) {
            RUNNING.put(s, new AtomicBoolean(false));
            LAST_START.put(s, 0L);
        }
    }

    private static volatile boolean blockChanged = false;
    private static int lastStateHash = 0;
    private static long lastConfigGen = -1;
    private static BlockPos lastNearCenter = BlockPos.ORIGIN;
    private static List<LitematicaBridge.PlacementInfo> placements = List.of();
    private static LitematicaBridge.LayerInfo layer = new LitematicaBridge.LayerInfo("all", net.minecraft.util.math.Direction.Axis.Y, 0, 0, false);
    private static int tickCounter = 0;
    private static boolean dirtyLayer = true, dirtyNear = true;

    // ------------------------------------------------------------------ öffentliche API
    public static synchronized ScanResult latest(Scope s) { return LATEST.get(s); }
    public static List<LitematicaBridge.PlacementInfo> placements() { return placements; }
    public static LitematicaBridge.LayerInfo layer() { return layer; }

    /** Vom Block-Änderungs-Mixin aufgerufen (beliebiger Thread). */
    public static void onBlockChanged(BlockPos pos) {
        blockChanged = true;
    }

    public static void invalidateAll() {
        dirtyLayer = true;
        dirtyNear = true;
        synchronized (ErrorScanner.class) { LATEST.clear(); }
    }

    /** Haupt-Thread, jeden Tick. */
    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        tickCounter++;
        if (tickCounter % 10 == 0) {
            placements = LitematicaBridge.placements();
        }
        layer = LitematicaBridge.layer();
        int h = LitematicaBridge.stateHash(placements, layer);
        if (h != lastStateHash) {
            lastStateHash = h;
            dirtyLayer = true;
            dirtyNear = true;
        }
        if (Cfg.generation() != lastConfigGen) {
            lastConfigGen = Cfg.generation();
            dirtyLayer = true;
            dirtyNear = true;
        }
        if (blockChanged) {
            blockChanged = false;
            dirtyLayer = true;
            dirtyNear = true;
        }
        long now = System.currentTimeMillis();
        long fullRescan = Cfg.integer("scan.fullRescanSeconds") * 1000L;

        // ---- Ebene (Feature 4 + Durchsicht im Layer-Modus)
        boolean needLayer = layer.restricting() && (Cfg.bool("hud.layerList.enabled") || seeThroughUsesLayer());
        if (needLayer) {
            long interval = Math.max(Cfg.integer("hud.layerList.updateIntervalMs"), Cfg.integer("scan.minIntervalMs"));
            long since = now - LAST_START.get(Scope.LAYER);
            if ((dirtyLayer && since >= interval) || since >= fullRescan) {
                if (start(Scope.LAYER, mc, false)) dirtyLayer = false;
            }
        }
        // ---- Umgebung (Feature 5)
        boolean needNear = dev.litextras.render.SeeThroughRenderer.ownRendererActive() && !seeThroughUsesLayer();
        if (needNear) {
            BlockPos p = mc.player.getBlockPos();
            if (p.getSquaredDistance(lastNearCenter) > 64) dirtyNear = true;
            long interval = Math.max(Cfg.integer("seeThrough.updateIntervalMs"), Cfg.integer("scan.minIntervalMs"));
            long since = now - LAST_START.get(Scope.NEAR);
            if ((dirtyNear && since >= interval) || since >= fullRescan) {
                if (start(Scope.NEAR, mc, false)) {
                    dirtyNear = false;
                    lastNearCenter = p;
                }
            }
        }
    }

    public static boolean seeThroughUsesLayer() {
        return dev.litextras.render.SeeThroughRenderer.ownRendererActive() && layer.restricting() && Cfg.bool("seeThrough.onlyCurrentLayer");
    }

    /** Scan auf Anfrage (z. B. /missing all, KI). Haupt-Thread. */
    public static CompletableFuture<ScanResult> request(Scope scope, MinecraftClient mc) {
        Job job = buildJob(scope, mc, scope == Scope.ALL);
        if (job == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            ScanResult r = run(job);
            if (r != null) synchronized (ErrorScanner.class) { LATEST.put(scope, r); }
            return r;
        }, EXEC);
    }

    // ------------------------------------------------------------------ intern
    private static boolean start(Scope scope, MinecraftClient mc, boolean totals) {
        AtomicBoolean running = RUNNING.get(scope);
        if (!running.compareAndSet(false, true)) return false;
        Job job = buildJob(scope, mc, totals);
        if (job == null) {
            running.set(false);
            synchronized (ErrorScanner.class) { LATEST.remove(scope); }
            return true;
        }
        LAST_START.put(scope, System.currentTimeMillis());
        EXEC.execute(() -> {
            try {
                ScanResult r = run(job);
                if (r != null) synchronized (ErrorScanner.class) { LATEST.put(scope, r); }
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    private static Job buildJob(Scope scope, MinecraftClient mc, boolean totals) {
        World schem = LitematicaBridge.schematicWorld();
        if (schem == null || mc.world == null || mc.player == null) return null;
        List<LitematicaBridge.PlacementInfo> pl = LitematicaBridge.placements();
        placements = pl;
        LitematicaBridge.LayerInfo li = LitematicaBridge.layer();
        if (pl.isEmpty()) return null;
        BlockPos center = mc.player.getBlockPos();
        int radius = scope == Scope.NEAR ? Math.max(Cfg.integer("scan.nearRadius"), Cfg.integer("seeThrough.range")) : Integer.MAX_VALUE;
        boolean layerFilter = scope == Scope.LAYER;
        List<LitematicaBridge.IntBox> boxes = new ArrayList<>();
        for (LitematicaBridge.PlacementInfo p : pl) {
            for (LitematicaBridge.IntBox b : p.boxes()) {
                LitematicaBridge.IntBox x = b;
                if (layerFilter) x = li.clip(x);
                if (x != null && scope == Scope.NEAR) {
                    x = x.intersect(new LitematicaBridge.IntBox(center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                            center.getX() + radius, center.getY() + radius, center.getZ() + radius));
                }
                if (x != null) boxes.add(x);
            }
        }
        String label = switch (scope) {
            case LAYER -> li.label();
            case NEAR -> "Umgebung (" + radius + " Blöcke)";
            case ALL -> "Ganze Schematic";
        };
        return new Job(scope, boxes, li, layerFilter, center, radius, schem, mc.world, Cfg.integer("scan.maxBlocksPerScan"), totals,
                Cfg.bool("hud.layerList.includeWrongType"), Cfg.bool("hud.layerList.includeWrongState"), label);
    }

    private static ScanResult run(Job job) {
        long t0 = System.currentTimeMillis();
        long gen = Cfg.generation();
        Map<CompareResult.Status, Integer> byStatus = new EnumMap<>(CompareResult.Status.class);
        List<ErrorEntry> errors = new ArrayList<>();
        Map<String, NeedEntry> needs = new HashMap<>();
        Map<String, NeedEntry> totals = job.computeTotals ? new HashMap<>() : Map.of();
        int checked = 0, unloaded = 0;
        boolean truncated = false;
        long r2 = job.radius == Integer.MAX_VALUE ? Long.MAX_VALUE : (long) job.radius * job.radius;
        BlockPos.Mutable m = new BlockPos.Mutable();
        try {
            outer:
            for (LitematicaBridge.IntBox b : job.boxes) {
                for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
                    for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                        int x0 = Math.max(b.minX(), cx << 4), x1 = Math.min(b.maxX(), (cx << 4) + 15);
                        int z0 = Math.max(b.minZ(), cz << 4), z1 = Math.min(b.maxZ(), (cz << 4) + 15);
                        if (!job.client.getChunkManager().isChunkLoaded(cx, cz)) {
                            unloaded += (x1 - x0 + 1) * (z1 - z0 + 1) * b.sizeY();
                            continue;
                        }
                        for (int y = b.minY(); y <= b.maxY(); y++) {
                            for (int x = x0; x <= x1; x++) {
                                for (int z = z0; z <= z1; z++) {
                                    if (checked >= job.maxBlocks) { truncated = true; break outer; }
                                    long dx = x - job.center.getX(), dy = y - job.center.getY(), dz = z - job.center.getZ();
                                    long d2 = dx * dx + dy * dy + dz * dz;
                                    if (d2 > r2) continue;
                                    if (job.layerFilter && !job.layer.contains(x, y, z)) continue;
                                    m.set(x, y, z);
                                    BlockState soll = LitematicaBridge.schematicState(job.schematic, m);
                                    BlockState ist = job.client.getBlockState(m);
                                    checked++;
                                    if (job.computeTotals && !BlockComparator.isEmpty(soll)) addNeed(totals, soll);
                                    CompareResult res = BlockComparator.compare(soll, ist);
                                    byStatus.merge(res.status(), 1, Integer::sum);
                                    if (!res.isError()) continue;
                                    if (errors.size() < 50_000) errors.add(new ErrorEntry(m.toImmutable(), res, d2));
                                    switch (res.status()) {
                                        case MISSING -> addNeed(needs, soll);
                                        case WRONG_TYPE -> { if (job.includeWrongType) addNeed(needs, soll); }
                                        case WRONG_STATE -> { if (job.includeWrongState) addNeed(needs, soll); }
                                        default -> {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LitematicaExtras.debug("Scan " + job.scope + " abgebrochen (wird wiederholt): " + t);
            return null;
        }
        errors.sort(Comparator.comparingDouble(ErrorEntry::distSq));
        long dur = System.currentTimeMillis() - t0;
        return new ScanResult(job.scope, job.label, System.currentTimeMillis(), dur, job.center, checked, unloaded, truncated,
                byStatus, List.copyOf(errors), Map.copyOf(needs), Map.copyOf(totals), gen);
    }

    private static void addNeed(Map<String, NeedEntry> map, BlockState soll) {
        for (ItemMapping.ItemReq req : ItemMapping.required(soll)) {
            String key;
            String label;
            EquivalenceGroups.Group g = BlockComparator.similarEnabled() ? EquivalenceGroups.groupOf(soll.getBlock()) : null;
            if (g != null) {
                key = "g:" + g.name();
                label = g.name();
            } else {
                key = "i:" + Registries.ITEM.getId(req.item());
                label = null;
            }
            NeedEntry old = map.get(key);
            if (old == null) map.put(key, new NeedEntry(key, req.item(), soll, label, req.count()));
            else map.put(key, new NeedEntry(key, old.item(), old.sample(), old.label(), old.need() + req.count()));
        }
    }
}
