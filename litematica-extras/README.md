# Litematica Extras (Minecraft 1.21.11, Fabric)

Clientseitiges Addon für Litematica. Kein Fork, keine Server-Teile, keine Eingriffe in die Spielmechanik.

| Feature | Kurz |
|---|---|
| 1 | „Ähnliche Blöcke akzeptieren“: Typ gelockert über Gruppen (Tags/IDs) + „alle soliden Blöcke ohne Redstone“, Properties bleiben streng |
| 2 | HUD unten links: Soll/Ist inkl. Properties, falsche Properties einzeln rot, Platzier-Vorschau *vor* dem Setzen |
| 3 | Benötigtes Item markieren (Hotbar-Rahmen, sonst „Inventar, Slot 14“, sonst rot), optional Auto-Slotwechsel |
| 4 | Fehlliste der aktuellen Litematica-Ebene im HUD mit Bestand/Fehlbetrag |
| 5 | Fehler durch Blöcke hindurch sehen (nur Fehler, Reichweite, Fade, Box-Limit, nur aktuelle Ebene) |
| 6 | KI-Assistent `/ai <Frage>` mit Tool-Calling, rein lesend, standardmäßig AUS |

Alle Anzeigen benutzen **eine** Vergleichslogik (`core/BlockComparator.java`) und widersprechen sich daher nicht.

---

## 1. Was Litematica selbst schon kann (ehrlich)

| Wunsch | Litematica (soweit ich weiß) | Was dieser Mod macht |
|---|---|---|
| Ähnliche Blöcke | Keinen solchen Modus. Overlay/Verifier vergleichen exakt. | Eigene Gruppenlogik; per Mixin auch im **Litematica-Overlay** (wenn die Methode in deiner Version passt, siehe Willkommensmeldung „Overlay-Integration: aktiv“). Litematicas **Verifier-Fenster** wird **nicht** umgebogen – nutze stattdessen `/missing`. |
| Soll/Ist-Anzeige | Hat ein „Block-Info-Overlay“ beim Anschauen. | Eigene Anzeige mit Vorlagen, Einzel-Färbung je Property, Platzier-Vorschau. |
| Richtig drehen | „Easy Place“ setzt den richtigen Zustand automatisch. | Vorschau zeigt nur an, setzt nichts. |
| Item finden | „Pick Block“-Hotkey holt das Item in die Hand. | HUD-Markierung bei geschlossenem Inventar. |
| Materialliste pro Ebene | Ich bin **nicht sicher**, ob die Materialliste nach der gerenderten Ebene zählen kann – meines Wissens zählt sie pro Platzierung/Bereich. | Eigene Zählung pro Ebene mit derselben Vergleichslogik. |
| Durch Blöcke sehen | Hat eine Option „Overlay durch Blöcke rendern“ (Visuals). | `seeThrough.backend`: `own` (eigener Renderer), `litematica` (schaltet Litematicas Option) oder `both`. |

## 2. Bewusst anders gelöst als im Prompt – und warum

- **Hotkeys und Einstellungsfenster ohne Malilib-API.** Malilibs Config-/Hotkey-API ändert sich zwischen Versionen; ein Fehler dort würde den ganzen Mod am Laden hindern. Eigene Hotkeys können trotzdem Mehrfach-Kombinationen und *toggle/hold*. Es gibt ein kleines Einstellungsfenster (`/lx config`, Alt+O) für die wichtigsten Schalter, alles andere steht kommentiert in der Config.
- **Feature 5 als Bildschirm-Projektion.** Die 3D-Render-Pipeline wurde in 1.21.5–1.21.11 komplett umgebaut. Statt eines fehleranfälligen Welt-Renderers „ohne Tiefentest“ werden die Fehler-Kästen auf den Bildschirm projiziert und als Linien gezeichnet – das ist automatisch „durch alles hindurch“. Einschränkung: In der 3rd-Person-Ansicht stimmt die Lage nicht exakt.
- **Raycast** nicht über Litematicas `RayTraceUtils` (Signatur wechselt), sondern Vanilla-Raycast direkt auf Litematicas Schematic-Welt – gleiches Ergebnis.
- **Befehlsnamen** werden beim Betreten einer Welt registriert. Umbenennungen greifen nach erneutem Betreten. Konflikte mit Vanilla-Befehlen und anderen Client-Mods werden gemeldet; Konflikte mit **Server-Plugin-Befehlen** lassen sich clientseitig nicht zuverlässig erkennen.
- **Hotkey-Umschalter** (z. B. Durchsicht an) gelten bis zum nächsten `/lx reload` oder Neustart; dauerhaft stellst du sie in der Config oder im Profil ein.

---

## 3. Bauen – Weg A: ohne etwas zu installieren (GitHub)

1. Auf github.com ein Konto anlegen → **New repository** (z. B. `litematica-extras`, „Private“ ist ok).
2. Auf der Repo-Seite **„uploading an existing file“** → den **Inhalt** dieses Ordners (nicht den Ordner selbst) hineinziehen, inkl. des versteckten Ordners `.github` → **Commit**.
   *Tipp Mac:* Im Finder `Cmd+Shift+.` zeigt versteckte Ordner an.
   Falls `.github` trotzdem fehlt: Reiter **Actions** → „set up a workflow yourself“ → Inhalt von `.github/workflows/build.yml` hineinkopieren → Commit.
3. Reiter **Actions** → der Lauf „Mod bauen“ startet automatisch (ggf. einmal „Enable workflows“ klicken).
4. Nach ca. 3–6 Minuten: Lauf anklicken → unten unter **Artifacts** `litematica-extras-jar` herunterladen → entpacken → `litematica-extras-1.0.0.jar`.

## 3. Bauen – Weg B: auf dem eigenen Rechner

1. Java 21 installieren (z. B. „Temurin 21“ von adoptium.net).
2. Terminal im Projektordner öffnen:
   - Mac/Linux: `./gradlew build` (falls „permission denied“: vorher `chmod +x gradlew`)
   - Windows: `gradlew.bat build`
3. Ergebnis: `build/libs/litematica-extras-1.0.0.jar`

Beim ersten Build werden die passenden Versionen von Fabric Loader, Yarn, Fabric API, Litematica und Malilib für 1.21.11 **automatisch** ermittelt und in `versions.lock.properties` festgeschrieben. Alternativ kannst du deine eigenen `litematica-…jar` und `malilib-…jar` in den Ordner `libs/` legen – dann werden genau diese benutzt.

## 4. Installieren

In den `mods`-Ordner deiner 1.21.11-Fabric-Installation: **Fabric API**, **MaLiLib**, **Litematica** und `litematica-extras-1.0.0.jar`.

## 5. Erste Schritte im Spiel

| Befehl / Hotkey | Wirkung |
|---|---|
| `/lx help` | alle Befehle |
| `/lx config` · **Alt+O** | Einstellungsfenster |
| `/lx reload` · **Alt+R** | Config ohne Neustart übernehmen |
| `/lx similar` · **Alt+J** | Ähnliche Blöcke an/aus |
| `/lx xray` · **Alt+K** | Fehler durch Blöcke sehen an/aus |
| **Alt+L** | Ebenenliste an/aus |
| `/missing` · `/fehlt` · **Alt+M** | Fehlliste im Chat (`/missing all`, `/missing layer`, `/missing near`) |
| `/lx profile sicher` · **Alt+P** | Profil wechseln |
| `/lx export` / `/lx import <datei>` | Config teilen (ohne API-Keys) |
| `/ai <Frage>` · `/frag` | KI (erst `ai.enabled: true`, Key in `secrets.json5`) |

Dateien: `config/litematica_extras/` → `config.json5` (alles, kommentiert), `profiles/`, `secrets.json5` (nur API-Keys), `ai_system_prompt.txt` (Stil der KI), `lang/de.json` (eigene Texte, optional), `export/`.
Eine vollständige Beispiel-Config mit allen Standardwerten liegt in `docs/config.example.json5`.

**Hinweis Server:** Spieler orten, Blöcke suchen und Entities auflisten (`get_nearby_players`, `find_block`, `get_nearby_entities`) gelten auf vielen Servern als Cheat und können zum Bann führen – auch rein clientseitig. Diese Tools sind standardmäßig aus und im Multiplayer zusätzlich gesperrt. Für die Einhaltung der Serverregeln bist du selbst verantwortlich. Gleiches gilt sinngemäß für den Durchsicht-Modus.

---

## 6. Was ich nicht testen konnte – bitte prüfen

Der Java-Code wurde gegen nachgebaute Schnittstellen kompiliert (Syntax/Typen geprüft), aber **nicht** gegen das echte Minecraft 1.21.11. Stellen mit möglichen Namensänderungen sind im Code mit `// UNSICHER:` markiert. Am wahrscheinlichsten hakt es hier:

| Datei | Mögliches Problem |
|---|---|
| `core/LitematicaBridge.java` | Litematica-Klassen/Methoden (`DataManager`, `LayerRange`, `getSubRegionBoxes`). **Alle** Litematica-Zugriffe sind in dieser einen Datei. |
| `LitematicaExtras.java` | `HudElementRegistry` (Fabric API ≥ 1.21.6) |
| `hud/HudRenderer.java` | `RenderTickCounter.getTickProgress`, `PlayerInventory.get/setSelectedSlot` |
| `hud/HudPanel.java`, `render/SeeThroughRenderer.java` | `DrawContext.getMatrices()` (2D-Matrix seit 1.21.6) |
| `ai/BuiltinTools.java` | `World.getTimeOfDay`, `getTopYInclusive`, `Registries.BLOCK.get` |
| `mixin/compat/…` | Litematicas `getOverlayType` – wird vorher geprüft und notfalls übersprungen (kein Absturz) |
| `build.gradle` / `gradle.properties` | `loom_version` – falls „Plugin not found“, andere Loom-Version eintragen |

**Im Spiel testen:**
1. Willkommensmeldung erscheint? Steht dort „Overlay-Integration: aktiv“?
2. Treppe falsch herum setzen → HUD: „Typ ok, Richtung falsch“, nur die Richtung rot.
3. Andere Treppe setzen, `Alt+J` → wird grün „Ähnlich akzeptiert“, auch das Litematica-Overlay meldet ihn nicht mehr.
4. Block in der Hand, auf Nachbarblock zielen → Vorschau-Zeile „würde richtig/falsch sitzen“.
5. Item nur im Inventar → „… (Inventar, Slot N)“; in der Hotbar → Rahmen um den Slot.
6. Litematica Ebenen-Modus „Einzelne Ebene“ → Liste oben links; Blöcke setzen → Liste aktualisiert sich nach ~1,5 s.
7. `Alt+K` → farbige Rahmen um Fehler, auch hinter Wänden; im Ebenen-Modus nur die Ebene.
8. Tippfehler in `config.json5` einbauen, `/lx reload` → Meldung mit Zeile, kein Absturz.

## 7. Fehler an Claude zurückgeben

- **Build-Fehler:** Die ersten ~30 Zeilen mit `error:` aus dem Build-Log kopieren (GitHub: Lauf → Job „build“ → Schritt „Bauen“). Dazu `versions.lock.properties` (GitHub: Artifact „versions-lock“).
- **Absturz/Ladefehler:** `.minecraft/crash-reports/crash-….txt` oder `.minecraft/logs/latest.log` (Abschnitt mit „LitematicaExtras“ / „Mixin“).
- **Falsches Verhalten:** Was du gemacht hast, was du erwartet hast, was angezeigt wurde, und ob `general.debugLog: true` etwas im Log zeigt.

Vorlage: *„Build für 1.21.11 schlägt fehl. versions.lock: … Fehler: … (Datei, Zeile). Bitte die betroffene Datei korrigiert komplett zurückgeben.“*

Am schnellsten geht das Iterieren mit **Claude Code**: Dort kann Claude den Build selbst ausführen und Fehler direkt beheben.

---

## 8. Eigene KI-Tools ergänzen

Siehe `ai/ReadOnlyTool.java` – ein Beispiel steht im Kommentar. Die Registry akzeptiert nur Lese-Tools und lehnt Namen ab, die nach Schreib-Aktionen klingen (`send`, `place`, `command` …). Ehrlicher Hinweis: Java kann nicht technisch erzwingen, dass fremder Tool-Code nichts verändert; die Schnittstelle bietet aber keine Schreibfunktionen an, und der Mod selbst hat keinen Codepfad, der Chat/Befehle sendet oder etwas platziert.
