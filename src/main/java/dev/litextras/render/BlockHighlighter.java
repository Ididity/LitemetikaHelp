package dev.litextras.render;

import dev.litextras.config.Cfg;
import dev.litextras.config.Colors;
import dev.litextras.core.BlockComparator;
import dev.litextras.core.CompareResult;
import dev.litextras.core.ErrorScanner;
import dev.litextras.core.LitematicaBridge;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Blöcke leuchten lassen": ausgewählte Blöcke werden durch Wände hindurch umrandet.
 *  - Plan-Modus: Stellen, an denen der Block laut Litematica-Plan hingehört (blau = fehlt, rot = falsch, grün = richtig).
 *  - Welt-Modus: echte Blöcke in der Welt – NUR in Einzelspieler-Welten, auf Servern automatisch aus (wäre X-Ray).
 * Gesucht wird im Hintergrund, gezeichnet wird wie bei der Durchsicht (Feature 5).
 */
public final class BlockHighlighter {
    private BlockHighlighter() {}

    public record Hit(BlockPos pos, int color, boolean world) {}

    private static volatile List<Hit> hits = List.of();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static long lastStart = 0;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LitematicaExtras-Highlight");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public static boolean active() { return Cfg.bool("highlight.active"); }

    /**
     * Welt-Modus: in Einzelspieler-Welten immer, auf Servern nur, wenn deren Adresse in
     * highlight.worldAllowedServers steht (eigener Server, auf dem das erlaubt ist).
     * DonutSMP und andere große öffentliche Server bleiben immer gesperrt.
     */
    public static boolean worldModeAllowed(MinecraftClient mc) {
        if (mc == null) return false;
        if (mc.isInSingleplayer()) return true;
        String addr = currentServer(mc);
        if (addr.isEmpty()) return false;
        for (String blocked : BLOCKED) if (addr.contains(blocked)) return false;
        for (String s : Cfg.list("highlight.worldAllowedServers")) {
            String a = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (!a.isEmpty() && (addr.equals(a) || addr.startsWith(a + ":"))) return true;
        }
        return false;
    }

    private static final List<String> BLOCKED = List.of("donutsmp", "hypixel", "minemen", "cubecraft", "mineplex", "gommehd", "griefergames");

    public static String currentServer(MinecraftClient mc) {
        try {
            var e = mc.getCurrentServerEntry();
            return e == null || e.address == null ? "" : e.address.trim().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    public static int count() { return hits.size(); }
    public static List<Hit> hits() { return hits; }

    private static Set<Block> blocks(String key) {
        Set<Block> out = new HashSet<>();
        for (String s : Cfg.list(key)) {
            try {
                Identifier id = Identifier.of(s.trim());
                if (Registries.BLOCK.containsId(id)) out.add(Registries.BLOCK.get(id));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Haupt-Thread, jeden Tick. */
    public static void tick(MinecraftClient mc) {
        if (!active() || mc.world == null || mc.player == null) {
            if (!hits.isEmpty()) hits = List.of();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStart < Cfg.integer("highlight.updateIntervalMs")) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        lastStart = now;

        Set<Block> plan = blocks("highlight.planBlocks");
        Set<Block> world = worldModeAllowed(mc) ? blocks("highlight.worldBlocks") : Set.of();
        if (plan.isEmpty() && world.isEmpty()) {
            hits = List.of();
            RUNNING.set(false);
            return;
        }
        BlockPos center = mc.player.getBlockPos();
        int r = Cfg.integer("highlight.range");
        int max = Cfg.integer("highlight.maxBoxes");
        boolean showCorrect = Cfg.bool("highlight.showCorrect");
        int cMiss = Cfg.color("highlight.colorMissing"), cWrong = Cfg.color("highlight.colorWrong"),
                cOk = Cfg.color("highlight.colorCorrect"), cWorld = Cfg.color("highlight.colorWorld");
        World schem = plan.isEmpty() ? null : LitematicaBridge.schematicWorld();
        List<LitematicaBridge.PlacementInfo> placements = ErrorScanner.placements();
        ClientWorld client = mc.world;
        int bottom = client.getBottomY(), top = client.getTopYInclusive();

        EXEC.execute(() -> {
            try {
                List<Hit> out = new ArrayList<>();
                LitematicaBridge.IntBox cube = new LitematicaBridge.IntBox(center.getX() - r, Math.max(bottom, center.getY() - r), center.getZ() - r,
                        center.getX() + r, Math.min(top, center.getY() + r), center.getZ() + r);
                BlockPos.Mutable m = new BlockPos.Mutable();
                // ---- Plan
                if (schem != null) {
                    outer:
                    for (LitematicaBridge.PlacementInfo p : placements) {
                        for (LitematicaBridge.IntBox b0 : p.boxes()) {
                            LitematicaBridge.IntBox b = b0.intersect(cube);
                            if (b == null) continue;
                            for (int x = b.minX(); x <= b.maxX(); x++) for (int z = b.minZ(); z <= b.maxZ(); z++) {
                                if (!client.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
                                for (int y = b.minY(); y <= b.maxY(); y++) {
                                    m.set(x, y, z);
                                    BlockState soll = schem.getBlockState(m);
                                    if (soll == null || !plan.contains(soll.getBlock())) continue;
                                    CompareResult res = BlockComparator.compare(soll, client.getBlockState(m));
                                    int col;
                                    if (res.status().isOk()) { if (!showCorrect) continue; col = cOk; }
                                    else col = res.status() == CompareResult.Status.MISSING ? cMiss : cWrong;
                                    out.add(new Hit(m.toImmutable(), col, false));
                                    if (out.size() >= max * 4) break outer;
                                }
                            }
                        }
                    }
                }
                // ---- Welt (nur Einzelspieler)
                if (!world.isEmpty()) {
                    for (int x = cube.minX(); x <= cube.maxX(); x++) for (int z = cube.minZ(); z <= cube.maxZ(); z++) {
                        if (!client.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
                        for (int y = cube.minY(); y <= cube.maxY(); y++) {
                            m.set(x, y, z);
                            if (world.contains(client.getBlockState(m).getBlock())) {
                                out.add(new Hit(m.toImmutable(), cWorld, true));
                                if (out.size() >= max * 8) break;
                            }
                        }
                    }
                }
                // nächste zuerst
                out.sort((a, b) -> Double.compare(a.pos().getSquaredDistance(center), b.pos().getSquaredDistance(center)));
                hits = out.size() > max ? List.copyOf(out.subList(0, max)) : List.copyOf(out);
            } catch (Throwable t) {
                dev.litextras.LitematicaExtras.debug("Leuchten-Suche fehlgeschlagen: " + t);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    public static void render(DrawContext ctx, float tickProgress) {
        List<Hit> list = hits;
        if (!active() || list.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.world == null) return;
        boolean worldOk = worldModeAllowed(mc);
        Vec3d eye = cam.getCameraPosVec(tickProgress);
        SeeThroughRenderer.Projector pr = new SeeThroughRenderer.Projector(eye, cam.getYaw(tickProgress), cam.getPitch(tickProgress),
                mc.options.getFov().getValue(), ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        float lw = (float) Cfg.dbl("highlight.lineWidth");
        String style = Cfg.str("highlight.style");
        int range = Cfg.integer("highlight.range");
        for (Hit h : list) {
            if (!worldOk && h.world()) continue; // Sicherheitsnetz: auf Servern nie Welt-Treffer zeigen
            double dx = h.pos().getX() + 0.5 - eye.x, dy = h.pos().getY() + 0.5 - eye.y, dz = h.pos().getZ() + 0.5 - eye.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double alpha = Math.max(0.35, 1.0 - dist / (range * 1.5));
            SeeThroughRenderer.drawBox(ctx, pr, h.pos(), Colors.withAlphaFactor(h.color(), alpha), lw, style);
        }
    }
}
