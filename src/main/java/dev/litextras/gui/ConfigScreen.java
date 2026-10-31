package dev.litextras.gui;

import dev.litextras.LitematicaExtras;
import dev.litextras.command.Actions;
import dev.litextras.config.Cfg;
import dev.litextras.util.Lang;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.List;

/**
 * Kleines In-Game-Einstellungsfenster für die wichtigsten Schalter.
 * Alles andere (Farben, Vorlagen, Gruppen, Hotkeys …) steht kommentiert in config.json5 –
 * Knopf "Ordner öffnen" + "Neu laden" macht das bequem.
 *
 * Hinweis: Bewusst kein Malilib-Config-GUI, weil sich dessen API zwischen Versionen ändert
 * und das den ganzen Mod am Laden hindern könnte.
 */
public class ConfigScreen extends Screen {
    private final Screen parent;
    private int page = 0;

    private record Toggle(String key, String langKey) {}

    private static final List<Toggle> TOGGLES = List.of(
            new Toggle("hud.enabled", "gui.hud"),
            new Toggle("compare.similar.enabled", "gui.similar"),
            new Toggle("compare.similar.solidBlocks", "gui.solid"),
            new Toggle("seeThrough.active", "gui.seeThrough"),
            new Toggle("hud.lookedAt.enabled", "gui.lookedAt"),
            new Toggle("hud.preview.enabled", "gui.preview"),
            new Toggle("hud.neededItem.enabled", "gui.neededItem"),
            new Toggle("hud.neededItem.autoSwitch", "gui.autoSwitch"),
            new Toggle("hud.layerList.enabled", "gui.layerList"),
            new Toggle("hud.status.enabled", "gui.status"),
            new Toggle("compare.reportExtra", "gui.reportExtra"),
            new Toggle("ai.enabled", "gui.ai"),
            new Toggle("ai.lockWorldToolsInMultiplayer", "gui.aiLock"),
            new Toggle("ai.showTokenUsage", "gui.aiTokens"));

    private static final int PER_PAGE = 10;

    public ConfigScreen(Screen parent) {
        super(Text.literal("Litematica Extras"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int colW = 150, gap = 6;
        int left = this.width / 2 - colW - gap / 2;
        int top = 40;
        int from = page * PER_PAGE;
        int to = Math.min(TOGGLES.size(), from + PER_PAGE);
        for (int i = from; i < to; i++) {
            Toggle t = TOGGLES.get(i);
            int idx = i - from;
            int x = left + (idx % 2) * (colW + gap);
            int y = top + (idx / 2) * 24;
            addDrawableChild(ButtonWidget.builder(label(t), b -> {
                if (t.key().equals("seeThrough.active")) Actions.toggleSeeThrough();
                else {
                    Cfg.manager().toggleRuntime(t.key());
                    LitematicaExtras.afterConfigChange();
                }
                b.setMessage(label(t));
            }).dimensions(x, y, colW, 20).build());
        }
        int by = top + 5 * 24 + 8;
        int pages = (TOGGLES.size() + PER_PAGE - 1) / PER_PAGE;
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.page", "n", page + 1, "of", pages)), b -> {
                page = (page + 1) % pages;
                clearAndInit();
            }).dimensions(this.width / 2 - 50, by, 100, 20).build());
            by += 24;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.profile", "name", Cfg.manager().activeProfileName())), b -> {
            Cfg.manager().cycleProfile();
            LitematicaExtras.afterConfigChange();
            clearAndInit();
        }).dimensions(left, by, colW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.reload")), b -> {
            LitematicaExtras.reload(true);
            clearAndInit();
        }).dimensions(left + colW + gap, by, colW, 20).build());
        by += 24;
        boolean boy = "junge".equals(Cfg.str("ai.persona"));
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.persona") + ": " + Lang.get(boy ? "gui.boy" : "gui.girl")), b -> {
            Cfg.manager().setPersistent("ai.persona", new com.google.gson.JsonPrimitive(boy ? "maedchen" : "junge"));
            dev.litextras.ai.AiService.resetSession();
            dev.litextras.voice.VoiceManager.personaChanged();
            clearAndInit();
        }).dimensions(left, by, colW, 20).build());
        String art = Cfg.str("ai.personality");
        String artLabel = "derb".equals(art) ? "gui.crude" : "frech".equals(art) ? "gui.cheeky" : "gui.nice";
        String next = "nett".equals(art) ? "frech" : "frech".equals(art) ? "derb" : "nett";
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.personality") + ": " + Lang.get(artLabel)), b -> {
            Cfg.manager().setPersistent("ai.personality", new com.google.gson.JsonPrimitive(next));
            clearAndInit();
        }).dimensions(left + colW + gap, by, colW, 20).build());
        by += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.hotkeys")), b -> {
            if (this.client != null) this.client.setScreen(new HotkeyScreen(this));
        }).dimensions(left, by, colW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.hudEditor")), b -> {
            if (this.client != null) this.client.setScreen(new HudEditorScreen(this));
        }).dimensions(left + colW + gap, by, colW, 20).build());
        by += 24;
        boolean allScope = dev.litextras.core.ErrorScanner.listShowsAll();
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.listScope") + ": " + Lang.get(allScope ? "list.all" : "list.layer")), b -> {
            dev.litextras.LitematicaExtras.setListScope(allScope ? "layer" : "all");
            clearAndInit();
        }).dimensions(left, by, colW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.highlight")), b -> {
            if (this.client != null) this.client.setScreen(new BlockPickerScreen(this));
        }).dimensions(left + colW + gap, by, colW, 20).build());
        by += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.openFolder")), b ->
                Util.getOperatingSystem().open(Cfg.manager().dir().toFile())).dimensions(left, by, colW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.done")), b -> close())
                .dimensions(left + colW + gap, by, colW, 20).build());
    }

    private static Text label(Toggle t) {
        boolean on = Cfg.bool(t.key());
        return Text.literal(Lang.get(t.langKey()) + ": " + Lang.get(on ? "msg.on" : "msg.off"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(Lang.get("gui.hint")), this.width / 2, 27, 0xFFAAAAAA);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
