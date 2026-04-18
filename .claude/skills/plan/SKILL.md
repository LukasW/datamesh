---
name: plan
description: Issue analysieren und Implementierungsplan erstellen, bevor Code geschrieben wird
argument-hint: <issue-nummer>
disable-model-invocation: true
---

Analysiere Issue #$ARGUMENTS und erstelle einen Implementierungsplan:

## MCP-Server ermitteln

Führe zuerst `git remote get-url origin` aus und wähle den passenden MCP-Server:
- URL enthält `github.com` → **GitHub MCP**
- URL enthält `gitlab.com` oder selbst-gehostetes GitLab → **GitLab MCP**
- Andere URL (z.B. eigene Gitea-Instanz) → **Gitea MCP**

Owner und Repo immer aus der Remote-URL ableiten, NICHT hardcoden.

## Schritte

1. **Issue lesen**: Hole die Issue-Details (Titel, Beschreibung, Labels, Kommentare) via dem ermittelten MCP-Server.

2. **Domain(s) identifizieren**: Welche der Domänen ist betroffen? `billing`, `claims`, `hr-system`, `hr-integration`, `partner`, `policy`, `product`, `sales` — oder Infrastruktur (`infra/`, `scripts/`, Compose-Stack).

3. **Codebase erkunden**: Identifiziere anhand des Issues die relevanten Bereiche:
   - **Hexagonal:** Welche `domain/model`, `domain/port`, `domain/service`, `application`-Klassen sind betroffen?
   - **Adapter:** `infrastructure/persistence` (JPA/Hibernate, Flyway), `infrastructure/messaging` (Kafka/SmallRye), `infrastructure/grpc` (ADR-010), `infrastructure/web` (JAX-RS, Qute, htmx)?
   - **Data Contracts:** Ist ein Kafka-Event betroffen? Welcher ODC unter `{domain}/src/main/resources/contracts/` muss angepasst oder neu angelegt werden? Topic-Naming `{domain}.v{version}.{event_type}`.
   - **PII / ADR-009:** Sind personenbezogene Felder im Event-Payload? Vault-Transit-Encryption (`encrypted: true`) berücksichtigen?
   - **Sync / ADR-010:** Braucht es einen gRPC-Call mit Circuit Breaker/Timeout?
   - **Data Product:** Müssen `data-product/sqlmesh/silver/` oder `data-product/sqlmesh/gold/` Modelle, `data-product/soda/checks.yml`, `data-product/debezium/*.json` oder `infra/sqlmesh/models/gold/analytics/` angepasst werden?
   - Gibt es bestehende ähnliche Implementierungen in anderen Domänen, an denen man sich orientieren kann?
   - Welche Tests existieren bereits für die betroffenen Bereiche (`src/test/java/.../integration/`, Unit-Tests in `domain/`)?

4. **Plan als Datei speichern**: Speichere den Plan als Markdown-Datei im `plan/`-Verzeichnis (Projektroot). Erstelle das Verzeichnis falls nötig.
   - Dateiname: `plan/us-<issue-nummer>-<kurzbeschreibung>.md` (bzw. `plan/task-<issue-nummer>-<kurzbeschreibung>.md` bei technischen Tasks)
   - Verwende **Markdown-Tasks** (`- [ ]`) für alle Implementierungsschritte, damit der Fortschritt trackbar ist
   - Struktur:
     - **Übersicht** (kurze Zusammenfassung des Issues)
     - **Betroffene Domain(s) / Module**
     - **Zu erstellende Dateien** (als Tasks mit Pfad und Zweck)
     - **Zu ändernde Dateien** (als Tasks mit Pfad und Art der Änderung)
     - **Architektur-Entscheide** (Hexagonal-Layer, Event-vs-Sync-Kommunikation, Outbox nötig?, ADR-Referenzen)
     - **Data Contracts & Events** (ODC-YAML, Topic-Name, Schema-Version, PII-Felder gemäss ADR-009)
     - **Data-Product-Impact** (SQLMesh-Modelle, Soda-Checks, Debezium-Sink — falls relevant)
     - **Test-Strategie** (als Tasks: Unit · Integration mit Testcontainers · Contract `DataContractVerificationTest` · Playwright `-Dgroups=playwright` · Soda)
     - **Offene Fragen** (falls Anforderungen unklar sind)

5. **Plan präsentieren und warten**: Zeige dem User den Datei-Pfad und eine Zusammenfassung. Warte auf explizite Bestätigung.
   - Implementiere **nichts** vor der Bestätigung
   - Bei Korrekturen: Plan-Datei anpassen und erneut vorlegen
   - Erst nach „OK", „ja", „go" o.ä.: `/implement $ARGUMENTS` ausführen
