# ADR-005: Sprachpolitik – Code Englisch, UI Deutsch

**Status:** Accepted
**Bereich:** Governance · Code-Qualität

## Kontext

Die Plattform wird in einer deutschsprachigen Organisation entwickelt, soll aber international wartbar bleiben. Ohne klare Regel entstehen Mischsprachen im Codebase (deutsche Klassen + englische Methoden + gemischte Kafka-Events), was Lesbarkeit und Wiederverwendbarkeit verschlechtert.

## Entscheidung

Strikte Trennung nach Schicht:

| Schicht | Sprache | Beispiele |
|---------|---------|-----------|
| Code (Klassen, Methoden, Felder, Logs, Exceptions) | **Englisch** | `PolicyRepository`, `coverageStartDate`, `PersonCreated` |
| UI (Qute-Templates, Labels, Buttons, Fehlermeldungen für Endnutzer) | **Deutsch** | «Police ausstellen», «Bitte Vorname eingeben» |
| Kafka Event Types | **Englisch, PascalCase** | `PolicyIssued` (nicht `PolicyAusgestellt`) |
| Dokumentation (`specs/`, `CLAUDE.md`, ADRs) | **Englisch**, Ausnahme `arc42.md` und ADRs auf Deutsch für Stakeholder-Kommunikation | — |
| ODC-Descriptions, Commit Messages | **Englisch** | — |

## Konsequenzen

* Domänenmodell vollständig in Englisch (`Person`, `Policy`, `Coverage`, `CoverageType.GLASS_BREAKAGE`).
* Qute-Templates vollständig in Deutsch, Endnutzer-Nachrichten in Deutsch.
* Historische Altlasten: Partner- und Policy-Service enthalten teilweise noch deutsche Feldnamen (Risiken R-6, R-7 in arc42.md §11) – werden schrittweise migriert.
* Review-Checkliste: Neue Klassen- und Methodennamen werden auf Englisch geprüft.
