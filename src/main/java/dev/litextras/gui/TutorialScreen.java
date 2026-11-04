package dev.litextras.gui;

import dev.litextras.config.Cfg;
import dev.litextras.util.Lang;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Kleines Tutorial: erklärt jede Funktion kurz, mit der aktuell eingestellten Taste dazu.
 * Reiner Lesebildschirm, scrollbar mit Mausrad und Pfeiltasten.
 */
public class TutorialScreen extends Screen {
    private final Screen parent;
    private int scroll = 0;
    private List<Line> lines = List.of();

    private record Line(String text, int color, boolean head) {}

    public TutorialScreen(Screen parent) {
        super(Text.literal("Litematica Extras – " + Lang.get("gui.tutorial")));
        this.parent = parent;
    }

    private static String key(String id) {
        String k = Cfg.str("hotkeys." + id + ".keys");
        return k == null || k.isBlank() ? "—" : k;
    }

    private void build() {
        List<Line> l = new ArrayList<>();
        int head = 0xFFFFFF55, txt = 0xFFCCCCCC, key = 0xFF55FFFF;
        l.add(new Line("Litematica Extras – was alles geht", head, true));
        l.add(new Line("Alle Tasten sind frei belegbar: Einstellungen (" + key("openConfig") + ") -> \"Tasten belegen\".", txt, false));
        l.add(new Line("", txt, false));

        sec(l, "Feature 1 – Ähnliche Blöcke");
        txtln(l, "Akzeptiert gleichwertige Blöcke (Steinsorten, Holzarten, Schilder, Blöcke ohne Hitbox …).");
        txtln(l, "Glas, Eis, Obsidian, Kolben u. Ä. zählen bewusst NICHT als gleich (Redstone/Kolben).");
        txtln(l, "An/Aus: " + key("toggleSimilar"));

        sec(l, "Feature 2 – Soll/Ist unten links");
        txtln(l, "Zeigt für den angeschauten Block: Soll, Ist, Abweichungen und den Live-Zustand");
        txtln(l, "(gepowert, ausgefahren, an …). Dazu eine Platzier-Vorschau, wohin der Rechtsklick setzt.");

        sec(l, "Feature 3 – Benötigtes Item");
        txtln(l, "Zeigt und markiert das passende Item (grün = exakt, gelb = ähnlich), auch im Inventar.");
        txtln(l, "Auto-Switch wechselt auf das Item für die Platzier-Stelle (nicht den angeschauten Block).");

        sec(l, "Feature 4 – Materialliste");
        txtln(l, "Was in der Ebene bzw. insgesamt noch fehlt. Umschalten Ebene/Insgesamt: " + key("toggleListScope"));
        txtln(l, "Aktualisiert sich automatisch, wenn du Blöcke setzt.");

        sec(l, "Feature 5 – Durch Wände sehen");
        txtln(l, "Fehler (fehlt/falsch) als Umrisse durch Blöcke. An/Aus: " + key("toggleSeeThrough"));

        sec(l, "Blöcke leuchten lassen");
        txtln(l, "Bestimmte Blöcke durch Wände markieren. Menü: /lx leuchten. An/Aus: " + key("toggleHighlight"));
        txtln(l, "Plan-Modus überall; Welt-Modus nur Einzelspieler oder freigegebene Server (/lx leuchten erlauben).");

        sec(l, "Tracer");
        txtln(l, "Linien von der Mitte zu Zielen. An/Aus: " + key("toggleTracers"));

        sec(l, "Durchsatz-Zähler");
        txtln(l, "Behälter anschauen + /lx zaehler hier. Zeigt Items/Stunde; mit /lx zaehler wert <preis>");
        txtln(l, "und /lx zaehler multi <faktor> auch Geld/Stunde. Reset: " + key("counterReset"));
        txtln(l, "Einzelspieler: leert den Behälter wie Carpet (Einstellung). Schnelle Trichter ebenso.");

        sec(l, "Platz-Sperre");
        txtln(l, "Nur richtige Blöcke setzbar, falsche werden abgebrochen. An/Aus: " + key("toggleOnlyCorrect"));
        txtln(l, "(Zum Kisten-Öffnen leere Hand/Werkzeug nehmen oder kurz ausschalten.)");
        txtln(l, "Höhen-Sperre: nur auf einer Y-Höhe setzen. An/Aus: " + key("toggleOnlyHeight") + "   Höhe: /lx hoehe hier");

        sec(l, "Haken unterm Fadenkreuz");
        txtln(l, "Kleiner grüner Haken, wenn der Block richtig sitzen würde (einschaltbar).");

        sec(l, "KI & Stimme");
        txtln(l, "/ai <frage> oder Sprechen-Taste: " + key("pushToTalk") + ". Antwort rechts im HUD.");

        sec(l, "Weitere Tasten");
        txtln(l, "HUD verschieben/vergrößern: " + key("hudEditor") + "   |   HUD an/aus: " + key("toggleHud"));
        txtln(l, "Einstellungen: " + key("openConfig") + "   |   dieses Tutorial: " + key("openTutorial"));
        this.lines = l;
    }

    private static void sec(List<Line> l, String t) {
        l.add(new Line("", 0xFFCCCCCC, false));
        l.add(new Line(t, 0xFFFFFF55, true));
    }

    private static void txtln(List<Line> l, String t) {
        l.add(new Line(t, 0xFFCCCCCC, false));
    }

    @Override
    protected void init() {
        build();
        addDrawableChild(ButtonWidget.builder(Text.literal(Lang.get("gui.done")), b -> close())
                .dimensions(this.width / 2 - 60, this.height - 26, 120, 20).build());
    }

    private int maxScroll() {
        int lineH = 11;
        int visible = this.height - 70;
        int total = lines.size() * lineH;
        return Math.max(0, total - visible);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - v * 22));
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);
        int x = this.width / 2 - 230;
        int y = 30 - scroll;
        int lineH = 11;
        for (Line ln : lines) {
            if (y > 22 && y < this.height - 30 && !ln.text().isEmpty()) {
                ctx.drawText(this.textRenderer, ln.text(), x, y, ln.color(), ln.head());
            }
            y += lineH;
        }
        // Scroll-Hinweis
        if (maxScroll() > 0) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("↕ scrollen"), this.width / 2, this.height - 40, 0xFF888888);
        }
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
