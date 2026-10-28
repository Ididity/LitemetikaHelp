package dev.litextras.hud;

import dev.litextras.config.Cfg;
import dev.litextras.core.BlockComparator;
import dev.litextras.core.CompareResult;
import dev.litextras.util.Lang;
import dev.litextras.util.Template;
import dev.litextras.util.Template.Seg;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lesbare Texte für Zustände, Properties und Status – überall gleich (HUD, Chat, KI). */
public final class Describe {
    private Describe() {}

    public static String blockName(BlockState s) {
        if (s == null || s.isAir()) return Lang.get("block.air");
        return s.getBlock().getName().getString();
    }

    public static String itemName(Item i) {
        return i == null ? "?" : i.getName().getString();
    }

    public static String propName(String p) {
        String t = Lang.opt("prop." + p);
        return t != null ? t : p;
    }

    public static String valueName(String v) {
        String t = Lang.opt("val." + v);
        return t != null ? t : v;
    }

    public static int statusColor(CompareResult.Status s) {
        return switch (s) {
            case CORRECT -> Cfg.color("colors.correct");
            case SIMILAR -> Cfg.color("colors.similar");
            case MISSING -> Cfg.color("colors.missing");
            case WRONG_TYPE, WRONG_STATE -> Cfg.color("colors.wrong");
            case EXTRA -> Cfg.color("colors.extra");
            case NONE -> Cfg.color("colors.neutral");
        };
    }

    /** z. B. "Typ ok, Richtung falsch, waterlogged falsch" */
    public static String statusText(CompareResult r) {
        return switch (r.status()) {
            case CORRECT -> Lang.get("status.correct");
            case SIMILAR -> Lang.get("status.similar", "group", r.groupName() == null ? "" : r.groupName());
            case MISSING -> Lang.get("status.missing");
            case EXTRA -> Lang.get("status.extra");
            case NONE -> Lang.get("status.none");
            case WRONG_TYPE -> r.diffs().isEmpty() ? Lang.get("status.wrongType")
                    : Lang.get("status.wrongType") + ", " + diffNames(r);
            case WRONG_STATE -> Lang.get("status.typeOk") + ", " + diffNames(r);
        };
    }

    private static String diffNames(CompareResult r) {
        List<String> parts = new ArrayList<>();
        for (CompareResult.PropDiff d : r.diffs()) parts.add(Lang.get("status.propWrong", "prop", propName(d.name())));
        return String.join(", ", parts);
    }

    /** "Richtung: Norden → Osten, half: oben → unten" */
    public static String diffText(CompareResult r) {
        List<String> parts = new ArrayList<>();
        for (CompareResult.PropDiff d : r.diffs()) {
            parts.add(propName(d.name()) + ": " + valueName(d.expected()) + " → " + valueName(d.actual()));
        }
        return String.join(", ", parts);
    }

    /** Properties eines States als farbige Segmente; falsche Properties in wrongColor. */
    public static List<Seg> propSegments(BlockState s, CompareResult r, int normal, int wrongColor, boolean isSoll) {
        List<Seg> out = new ArrayList<>();
        if (s == null || s.isAir()) return out;
        Set<String> wrong = new HashSet<>();
        for (CompareResult.PropDiff d : r.diffs()) wrong.add(d.name());
        boolean showAll = Cfg.bool("hud.lookedAt.showAllProps");
        String fmt = Cfg.str("templates.propFormat");
        String sep = Cfg.str("templates.propSeparator");
        boolean first = true;
        for (String[] kv : BlockComparator.allProps(s)) {
            if (!showAll && BlockComparator.isIgnored(kv[0])) continue;
            Map<String, String> m = new HashMap<>();
            m.put("name", propName(kv[0]));
            m.put("value", valueName(kv[1]));
            String text = Template.fill(fmt, m);
            out.add(new Seg(first ? Cfg.str("templates.propsPrefix") : sep, normal));
            out.add(new Seg(text, wrong.contains(kv[0]) ? wrongColor : normal));
            first = false;
        }
        return out;
    }

    /** Ein Zustand als Klartext für Chat/KI, z. B. "Eichentreppe (Richtung: Norden, half: unten)". */
    public static String statePlain(BlockState s) {
        if (s == null || s.isAir()) return Lang.get("block.air");
        StringBuilder sb = new StringBuilder(blockName(s));
        List<String[]> props = BlockComparator.allProps(s);
        if (!props.isEmpty()) {
            sb.append(" (");
            boolean first = true;
            for (String[] kv : props) {
                if (BlockComparator.isIgnored(kv[0])) continue;
                if (!first) sb.append(", ");
                sb.append(propName(kv[0])).append(": ").append(valueName(kv[1]));
                first = false;
            }
            sb.append(')');
        }
        return sb.toString().replace(" ()", "");
    }
}
