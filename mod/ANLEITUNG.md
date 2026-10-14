# Blockplan 1.1.2 – Anleitung

## Update von 1.0.x auf 1.1.0 (einmalig)

1. Minecraft schließen. Alle schwarzen cmd-Fenster schließen (sonst blockiert Gradle Dateien).
2. Den alten entpackten Ordner `blockplan-mod` löschen.
3. **ENTPACKEN:** `blockplan-mod.zip` → Rechtsklick → „Alle extrahieren…“, z. B. auf den Desktop.
4. In den Ordner klicken, bis du `gradlew.bat` siehst → oben in die Adresszeile `cmd` → Enter.
5. `gradlew.bat build` eingeben und warten, bis „BUILD SUCCESSFUL“ kommt.
6. Im `mods`-Ordner deines 26.3-Profils die alte `blockplan-1.0.1.jar` **löschen**.
7. Die neue `build\libs\blockplan-1.1.2.jar` in den `mods`-Ordner kopieren (**nicht entpacken**).
8. Minecraft starten, Welt öffnen, im Chat `/blockplan version` eingeben. Dort muss 1.1.2 stehen.

Dein Plan und dein Platz bleiben erhalten (die liegen in `config\blockplan`).

## Was 1.1.0 neu macht

- **Texturen automatisch:** Die Website holt Texturen und Blockformen direkt aus Minecraft. Keine .jar mehr suchen.
- **Website-Updates automatisch:** Beim Öffnen einer Welt schaut die Mod auf GitHub (`randomghg/blockplan`),
  ob es eine neue Website gibt, und speichert sie selbst. Du drückst nur noch F5 im Browser.
  - Sofort prüfen: `/blockplan update`
  - Abschalten: im Ordner `config\blockplan` eine leere Datei `kein-update` anlegen.
- **Ketten werden richtig erkannt:** Stans Welt nennt sie noch `chain`, Minecraft 26.3 `iron_chain`. Die Mod kennt jetzt beide.

## Baupläne (Website, Tab „Baupläne“)

- Links die Liste: Kanonen, Kleine Kanonen, Festungskanonen, Gipfelgebäude, Schiffe. Die sind schon markiert.
- Ein Objekt anklicken: Anleitung Schicht für Schicht in 2D oder 3D, mit Material für die Schicht und fürs ganze Objekt.
- „Blockgenau gleich wie …“: Diese Objekte sind gleich gebaut, eventuell gespiegelt oder gedreht.
- In der 3D-Ansicht: Maus über ein Objekt → gelber Rahmen. Klicken öffnet den Bauplan.
- **Eigenes Objekt markieren:** Tab „Schichten“ → auf die unterste Schicht des Objekts gehen → „Objekt markieren“ →
  Rechteck aufziehen → Name und Kategorie eingeben → „+ Gleiche suchen“ findet die Kopien im ganzen Bau.

## Befehle im Spiel
- `/blockplan` – Stand und Fortschritt
- `/blockplan web` – Adresse der Website
- `/blockplan anker hier` oder `/blockplan anker x y z` – Nord-West-Ecke unten setzen
- `/blockplan drehen <0|90|180|270>` – Plan drehen
- `/blockplan check` – was gehört an den Block, auf den du schaust
- `/blockplan warnungen an|aus`
- `/blockplan update` – Website sofort von GitHub holen
- `/blockplan version` – Versionen und letzter Update-Stand

## Platz in deiner Welt (Marineford)
Mitte X -3400, Z 200 → Ecke X -3760, Z -205. Y = -56 (dann liegen die Schiffe wie bei Stan auf Höhe des Meeresspiegels).
Auf der Website unter „Platz in deiner Welt“ eintragen und „Platz speichern“.
