package dev.litextras.command;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.litextras.LitematicaExtras;
import dev.litextras.config.Cfg;
import dev.litextras.core.BlockComparator;
import dev.litextras.core.LookTarget;
import dev.litextras.core.SchematicReplacer;
import dev.litextras.hud.Describe;
import dev.litextras.util.ChatOut;
import dev.litextras.util.Lang;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** /lx tausch – Blöcke im Litematica-Plan austauschen. Ändert nur den Plan, nie die Welt. */
public final class ReplaceCommand {
    private ReplaceCommand() {}

    private static String cmd() {
        String root = Cfg.list("commands.root.names").isEmpty() ? "lx" : Cfg.list("commands.root.names").get(0);
        return root + " tausch";
    }

    public static void help() {
        ChatOut.info(Lang.get("replace.help1", "cmd", cmd()));
        ChatOut.info(Lang.get("replace.help2", "cmd", cmd()));
        ChatOut.info(Lang.get("replace.help3", "cmd", cmd()));
    }

    private static Block parse(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        Identifier id = Identifier.tryParse(s);
        if (id == null || !Registries.BLOCK.containsId(id)) return null;
        return Registries.BLOCK.get(id);
    }

    public static void run(String args) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String[] parts = args.trim().split("\\s+");
        Block from, to;
        if (parts.length >= 2) {
            from = parse(parts[0]);
            to = parse(parts[1]);
            if (from == null) { ChatOut.error(Lang.get("replace.unknown", "id", parts[0])); return; }
            if (to == null) { ChatOut.error(Lang.get("replace.unknown", "id", parts[1])); return; }
        } else {
            to = parse(parts[0]);
            if (to == null) { ChatOut.error(Lang.get("replace.unknown", "id", parts[0])); return; }
            LookTarget.Target t = LookTarget.computeNow(mc);
            if (t == null || BlockComparator.isEmpty(t.result().soll())) {
                ChatOut.warn(Lang.get("replace.noTarget", "cmd", cmd()));
                return;
            }
            from = t.result().soll().getBlock();
        }
        if (from == to) { ChatOut.warn(Lang.get("replace.same")); return; }
        SchematicReplacer.Result r = SchematicReplacer.replace(from, to);
        for (String n : r.notes()) ChatOut.warn(n);
        String fromName = Describe.blockName(from.getDefaultState());
        String toName = Describe.blockName(to.getDefaultState());
        if (r.replaced() == 0) {
            if (r.schematics() > 0) ChatOut.warn(Lang.get("replace.none", "from", fromName));
            return;
        }
        ChatOut.ok(Lang.get("replace.done", "n", r.replaced(), "from", fromName, "to", toName, "s", r.schematics(), "cmd", cmd()));
        ChatOut.send(Lang.get("replace.note", "cmd", cmd()), 0xAAAAAA);
        LitematicaExtras.afterConfigChange();
    }

    public static void undo() {
        int n = SchematicReplacer.undo();
        if (n == 0) ChatOut.warn(Lang.get("replace.nothingToUndo"));
        else ChatOut.ok(Lang.get("replace.undone", "n", n));
    }

    public static void save() {
        ChatOut.lines(SchematicReplacer.saveCopy());
    }

    /** Vorschläge: Block-IDs passend zum gerade getippten Wort. */
    public static CompletableFuture<Suggestions> suggest(SuggestionsBuilder b) {
        String input = b.getRemaining().toLowerCase(Locale.ROOT);
        int sp = input.lastIndexOf(' ');
        String prefix = sp >= 0 ? input.substring(0, sp + 1) : "";
        String word = sp >= 0 ? input.substring(sp + 1) : input;
        if (prefix.split("\\s+").length >= 2 && !prefix.isBlank()) return b.buildFuture();
        int count = 0;
        for (Identifier id : Registries.BLOCK.getIds()) {
            String name = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
            if (name.startsWith(word) || (word.length() >= 3 && name.contains(word))) {
                b.suggest(prefix + name);
                if (++count >= 60) break;
            }
        }
        return b.buildFuture();
    }
}
