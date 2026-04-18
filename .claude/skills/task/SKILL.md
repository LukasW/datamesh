---
name: task
description: Strukturiertes Issue aus einer Beschreibung erstellen (Use Case oder technischer Task)
argument-hint: <beschreibung>
disable-model-invocation: true
---

Erstelle ein strukturiertes Issue basierend auf der folgenden Beschreibung:

**Beschreibung:** $ARGUMENTS

## MCP-Server ermitteln

Führe zuerst `git remote get-url origin` aus und wähle den passenden MCP-Server:
- URL enthält `github.com` → **GitHub MCP**
- URL enthält `gitlab.com` oder selbst-gehostetes GitLab → **GitLab MCP**
- Andere URL (z.B. eigene Gitea-Instanz) → **Gitea MCP**

Owner und Repo immer aus der Remote-URL ableiten, NICHT hardcoden.

## Schritt 0: Typ bestimmen

Entscheide anhand der Beschreibung, ob es sich um einen **Use Case** (fachliches Feature) oder einen **technischen Task** (Refactoring, Contract, SQLMesh-Modell, CI/CD, Dependency-Update, Tech Debt etc.) handelt.

- **Use Case:** Fachliche Funktionalität für einen Business-User (Sachbearbeiter, Kunde, Broker), neues Feature, User Story
  -> Weiter mit **Pfad A: Use Case**
- **Technischer Task:** Refactoring, Tests, Infrastruktur, Build, Data Contracts, SQLMesh-Modelle, Data Quality (Soda), Debezium-Sinks, Compose-Stack, Keycloak-Config, Trino/Iceberg-Setup
  -> Weiter mit **Pfad B: Technischer Task**

Falls die Beschreibung eine bestehende User-Story-Datei referenziert (z.B. `us-13`), lies diese Datei aus `specs/user-stories/` und verwende deren Inhalt.

---

## Pfad A: Use Case

### 1. Domain zuordnen

Ermittle aus der Beschreibung die betroffene(n) Domain(s): `billing`, `claims`, `hr-system`, `hr-integration`, `partner`, `policy`, `product`, `sales`. Die Domain gehört in den Titel als Scope.

### 2. Issue erstellen

- Owner/Repo aus `git remote get-url origin` ableiten
- **Titel:** `US-<issue-nummer>: [<domain>] [Prägnanter Titel]` (max. 70 Zeichen)
- Die Issue-Nummer des neu erstellten Issues dient direkt als US-Nummer — keine separate Zählung nötig
- **Body** mit folgendem Aufbau:

```markdown
## Beschreibung

Als [Rolle: Sachbearbeiter / Kunde / Broker / Data Analyst / System]

möchte ich [Ziel/Aktion],

damit [Nutzer-Nutzen/Geschäftswert].

## Akzeptanzkriterien

- [Kriterium 1]
- [Kriterium 2]
- [Kriterium 3]
(alle relevanten Kriterien auflisten — inkl. Data-Mesh-Aspekte falls relevant, z.B. "Event X wird auf Topic Y publiziert")

## Szenarien

### Szenario 1: [Name — Happy Path]
- **Gegeben sei** [Ausgangszustand]
- **Wenn** [Aktion]
- **Dann** [erwartetes Ergebnis]

### Szenario 2: [Name — Fehlerfall / Edge Case]
- **Gegeben sei** [Ausgangszustand]
- **Wenn** [Aktion]
- **Dann** [erwartetes Ergebnis]

## Technische Notizen / Abhängigkeiten

- **Domain(s):** [policy / claims / ...]
- **Betroffene Schichten:** [domain/model · domain/service · domain/port · infrastructure/persistence · infrastructure/messaging · infrastructure/grpc · infrastructure/web · application]
- **Kafka-Events:** [neue/geänderte Topics inkl. ODC-Contract unter `src/main/resources/contracts/`]
- **PII / Vault:** [ADR-009 relevant? Welche Felder sind encrypted?]
- **Sync-Calls:** [gRPC gemäss ADR-010 nötig? Welcher Circuit Breaker?]
- **Data Product:** [SQLMesh-Modelle silver/gold, Soda-Checks, Debezium-Sink zu aktualisieren?]
- **Abhängig von US-XX, falls zutreffend**
```

- **Labels:** `enhancement`, zusätzlich Domain-Label (z.B. `domain:policy`) falls vorhanden

**Hinweis:** Die lokale User-Story-Datei (`specs/user-stories/<issue-nummer>-<slug>.md`) wird erst bei der Implementierung via `/implement` angelegt.

### 3. Ausgabe

Gib dem Benutzer zurück:
- Die Issue-Nummer (= US-Nummer), z.B. `US-93`
- Den Issue-Link

---

## Pfad B: Technischer Task

Für technische Tasks wird **keine** lokale User-Story-Datei angelegt.

### 1. Issue erstellen

- Owner/Repo aus `git remote get-url origin` ableiten
- **Titel:** `Task: [<scope>] [Prägnanter Titel]` (max. 70 Zeichen)
  - `scope` = Domain (`policy`, `claims`, …) oder Infrastruktur-Bereich (`infra`, `sqlmesh`, `compose`, `keycloak`, `superset`, `trino`, `vault`, `ci`)
- **Body** mit folgendem Aufbau:

```markdown
## Beschreibung

[Was ist das Problem / was soll verbessert werden?]

## Motivation

[Warum ist das nötig? Welches technische/architektonische Problem löst es? Verweis auf ADR, falls relevant.]

## Umsetzung

- [Schritt 1]
- [Schritt 2]
- [Schritt 3]

## Betroffene Bereiche

- **Modul(e):** [billing / claims / hr-system / hr-integration / partner / policy / product / sales / infra]
- **Schicht(en):** [domain / application / infrastructure / data-product (sqlmesh/soda/debezium) / compose / frontend (Qute+htmx)]
- **Betroffene Dateien/Module:** [falls erkennbar]
- **Abhängig von Issue #YYY, falls zutreffend**

## Aufgaben

- [ ] [Teilaufgabe 1]
- [ ] [Teilaufgabe 2]
- [ ] Tests anpassen/schreiben (Unit / Integration / Contract / Playwright, je nach Schicht)
- [ ] ODC-Contracts aktualisieren, falls Event-Schemas betroffen
- [ ] Code Review
```

- **Labels:** `maintenance` oder `bug` je nach Kontext, zusätzlich passendes Scope-Label

### 2. Ausgabe

Gib dem Benutzer die Issue-Nummer und den Link zurück.

---

## Wichtig

- **Use Cases:** Leite Szenarien logisch ab (Happy Path, Edge Cases, Fehlerfälle, Event-Publishing)
- Verwende konsequent die Gherkin-Schlüsselwörter: Gegeben sei / Und / Wenn / Dann / Und
- Akzeptanzkriterien sind **prüfbare Aussagen**, keine Wiederholung der Szenarien
- Technische Notizen orientieren sich an der Projekt-Architektur (siehe `CLAUDE.md` — Hexagonal, Data Mesh, ODC, ADR-009/010)
- Language Policy (ADR-005): Issue-Beschreibung auf **Deutsch**, Code-Begriffe/Event-Namen auf **Englisch**
- Titel: kurz und prägnant (max. 70 Zeichen)
- Falls die Beschreibung eine bestehende `us-XX` referenziert: Datei aus `specs/user-stories/` lesen und daraus Issue erstellen
- Die lokale User-Story-Datei wird **nicht** von `/task` angelegt — das übernimmt `/implement`
