---
name: implement
description: Issue umsetzen — Branch erstellen, implementieren, PR erzeugen (optional mit --worktree)
argument-hint: <issue-nummer> [--worktree]
disable-model-invocation: true
---

Setze Issue #$ARGUMENTS um:

> **Worktree-Modus**: Falls `--worktree` angegeben wurde, arbeite in einem isolierten Git-Worktree (siehe Abschnitt unten). Standardmässig wird ein normaler Feature-Branch verwendet.

## MCP-Server ermitteln

Führe zuerst `git remote get-url origin` aus und wähle den passenden MCP-Server:
- URL enthält `github.com` → **GitHub MCP**
- URL enthält `gitlab.com` oder selbst-gehostetes GitLab → **GitLab MCP**
- Andere URL (z.B. eigene Gitea-Instanz) → **Gitea MCP**

Owner und Repo immer aus der Remote-URL ableiten, NICHT hardcoden.

## Schritte

1. **Issue lesen**: Hole die Issue-Details (Titel, Beschreibung, Labels) via dem ermittelten MCP-Server.
   - Issue-Nummer aus `$ARGUMENTS` extrahieren (alles vor einem allfälligen `--worktree` Flag)

2. **Plan sicherstellen**: Prüfe, ob bereits ein Implementierungsplan für dieses Issue existiert.
   - Suche nach Dateien matching `plan/us-<nummer>-*.md` oder `plan/task-<nummer>-*.md` (Glob)
   - **Falls Plan vorhanden**: Plan-Datei lesen und als verbindliche Richtschnur für die Implementierung verwenden. Nicht erneut fragen.
   - **Falls kein Plan vorhanden**: Führe `/plan <nummer>` aus, um einen Plan zu erstellen. Warte auf explizite Bestätigung des Users, bevor weitergemacht wird. Erst nach „OK", „ja", „go" o.ä. weiter mit Schritt 3.
   - **Hinweis:** Das Verzeichnis `plan/` ist via `.gitignore` ausgeschlossen — Pläne bleiben lokal und werden nicht committet.

3. **Issue zuweisen**: Ermittle den eigenen Benutzernamen via MCP (`get_me`) und weise das Issue dir selbst zu.

4. **Branch erstellen**:
   - Slug aus Issue-Titel generieren (lowercase, kebab-case, max 30 Zeichen)
   - `git fetch origin`
   - **Standard (kein `--worktree`)**:
     - `git checkout -b feature/<nummer>-<slug> origin/main`
     - Falls nötig: Maven-Dependencies ziehen (`./mvnw dependency:go-offline` oder einfach beim ersten Build)
   - **Mit `--worktree`**:
     - `git worktree add -b feature/<nummer>-<slug> .claude/worktrees/feature/<nummer>-<slug> origin/main`
     - In den neuen Worktree wechseln

5. **User-Story-Datei anlegen** (nur bei Use Cases, d.h. Issue-Titel beginnt mit `US-`):
   - Prüfe das Verzeichnis `specs/user-stories/` — lege es an falls nicht vorhanden
   - Nummer aus dem Issue-Titel extrahieren (z.B. `US-21` -> 21)
   - Slug aus Issue-Titel generieren (lowercase, kebab-case)
   - Erstelle `specs/user-stories/<nummer>-<slug>.md` mit vollständiger User Story:
     - H1-Titel, ID, Priorität, Schätzung
     - Domain(s) (z.B. `policy`, `claims`)
     - Beschreibung (Als/möchte ich/damit)
     - Akzeptanzkriterien
     - Szenarien (Gherkin: Gegeben sei / Wenn / Dann)
     - Technische Notizen: Hexagonal-Layer, Kafka-Events inkl. ODC-Pfad, ADR-Referenzen (009/010), Data-Product-Impact
     - Aufgaben-Checkliste
   - Leite den Inhalt aus dem Issue-Body und der Codebase-Analyse ab

6. **Implementieren** — strikt nach dem bestätigten Plan und den Projekt-Konventionen aus `CLAUDE.md`:

   a) **Hexagonal Isolation** _(hart)_: `domain/`-Packages dürfen **keine** Framework-Abhängigkeiten haben — kein `@Inject`, kein `@Entity`, kein Jackson. Adapter-Code in `infrastructure/`, Orchestrierung in `application/`.

   b) **Language Policy (ADR-005)**: Code/Logik/Logs/Kafka-Events auf **Englisch**, UI/Qute-Templates/User-Error-Messages auf **Deutsch**.

   c) **Kommunikation zwischen Domänen**:
      - Default: asynchron via Kafka — **Transactional Outbox Pattern** verwenden (kein direkter DB-to-Broker-Write)
      - Topic-Naming: `{domain}.v{version}.{event_type}` (z.B. `policy.v1.issued`)
      - Jedes neue/geänderte Topic braucht einen **ODC-YAML** unter `{domain}/src/main/resources/contracts/`
      - **ADR-009 PII Encryption**: PII-Felder (`name`, `firstName`, `dateOfBirth`, `socialSecurityNumber`) Vault-Transit-verschlüsselt publizieren und mit `"encrypted": true`-Flag versehen. `insuredNumber` bleibt Klartext. Jeder Consumer prüft das Flag und entschlüsselt via Vault Transit.
      - **Read Models**: Wenn Domain A Daten von Domain B braucht, abonniert A das Topic und baut lokales Read-Model auf — **keine Cross-Domain-DB-Zugriffe**.
      - **ADR-010 Synchron**: gRPC nur für reine Query-/Berechnungs-Calls mit mandatorischem Circuit Breaker, Timeout und Graceful Degradation (SmallRye Fault Tolerance). REST synchron nur für IAM (Keycloak).

   d) **Frontend**: Qute + htmx + Bootstrap (SSR), kein SPA. Templates liegen unter `{domain}/src/main/resources/templates/`.

   e) **Data Product**: Wenn Events neu/geändert sind, SQLMesh-Modelle (`{domain}/data-product/sqlmesh/silver/` und `gold/`), Debezium-Sink (`{domain}/data-product/debezium/*.json`) und Soda-Checks (`{domain}/data-product/soda/checks.yml`) entsprechend anpassen. Cross-Domain Gold-Modelle liegen zentral unter `infra/sqlmesh/models/gold/analytics/`.

   f) **Tests schreiben** — passend zur Schicht:
      - **Unit-Tests** für `domain/model` und `domain/service` (reines Java, keine Quarkus-Annotations)
      - **Integration-Tests** (`*IT.java`) mit `@QuarkusTest` + Testcontainers (Postgres, Redpanda/Kafka)
      - **Contract-Tests** via `DataContractVerificationTest` bei ODC-Änderungen
      - **Playwright-Tests** (Gruppe `playwright`) für UI-Flows — nur bei User-Facing Features
      - **Soda-Checks** in `{domain}/data-product/soda/checks.yml` bei Data-Product-Änderungen

7. **Definition of Done** — vor dem Commit sicherstellen:

   **Architektur**
   - [ ] `domain/`-Package enthält keine Framework-Imports (Quarkus/Jackson/JPA)
   - [ ] Kafka-Events laufen via Outbox, nicht direkt aus Service-Code
   - [ ] PII-Felder gemäss ADR-009 verschlüsselt, `encrypted`-Flag gesetzt
   - [ ] gRPC-Calls (falls vorhanden) mit Circuit Breaker/Timeout abgesichert (ADR-010)

   **Data Contracts**
   - [ ] Neue/geänderte Topics haben aktualisiertes ODC-YAML unter `{domain}/src/main/resources/contracts/`
   - [ ] Topic-Naming `{domain}.v{version}.{event_type}` eingehalten
   - [ ] Avro/OpenAPI-Schemas passen zum Event-Payload

   **Tests**
   - [ ] Unit-Tests für neue/geänderte Services und Domain-Logik grün
   - [ ] Integration-Tests (Testcontainers) für neue Adapter grün
   - [ ] `DataContractVerificationTest` grün bei ODC-Änderungen
   - [ ] Playwright-Tests (Gruppe `playwright`) grün bei UI-Features
   - [ ] Soda-Checks grün bei Data-Product-Änderungen

   **Dokumentation** _(nur bei US-Issues)_
   - [ ] User-Story-Datei `specs/user-stories/<nummer>-<slug>.md` vollständig und aktuell
   - [ ] Szenarien in User-Story stimmen mit Issue überein
   - [ ] Aufgaben-Checkliste abgehakt

   **Code-Qualität**
   - [ ] Keine toten Code-Pfade, keine auskommentierten Stellen
   - [ ] Keine TODOs ohne Issue-Referenz
   - [ ] Language Policy eingehalten (Code EN, UI-Labels DE)

8. **Commit erstellen**:
   - Relevante Dateien stagen (kein `git add -A`)
   - Commit-Message: `<typ>(<scope>): <Beschreibung> (#<nummer>)`
     - `scope` = Domain (`policy`, `claims`, …) oder übergreifend (`infra`, `sqlmesh`, `compose`, `contracts`)

9. **Build & Tests prüfen — Fail-Fast-Loop**:

   Durchlaufe die Test-Schichten **nacheinander** (Unit → Integration → Playwright → Contract). Jede Schicht im **Fail-Fast-Modus**: ersten Fehler flicken, Suite neu starten, bis grün. Erst dann weiter zur nächsten Schicht.

   **Loop pro Schicht:**
   1. Suite mit Fail-Fast starten
   2. Ersten Fehler analysieren — Root Cause, kein Batch-Fix
   3. Minimaler Fix (keine Nebenarbeiten, kein Refactoring nebenbei)
   4. Suite neu starten → zurück zu Schritt 1, bis grün
   5. Erst dann nächste Schicht

   **Schicht 1 — Unit-Tests** (schnellstes Feedback):
   - `mvn test -pl <betroffene-module> -Dsurefire.skipAfterFailureCount=1`
   - Einzeltest: `mvn test -pl {module} -Dtest=ClassName#methodName`

   **Schicht 2 — Integration-Tests** (Testcontainers):
   - `mvn verify -pl <betroffene-module> -Pintegration -Dfailsafe.skipAfterFailureCount=1`

   **Schicht 3 — Playwright-Tests** (nur bei UI-Änderungen):
   - `mvn test -pl {module} -Dgroups=playwright`

   **Schicht 4 — Contract-Tests** (bei ODC-Änderungen):
   - `mvn test -Dtest=DataContractVerificationTest`

   **Abschluss-Check**: Gesamt-Build `./build.sh` (baut alle Module und Container-Images) — muss fehlerfrei durchlaufen. Erst dann weiter mit Push/PR.

   **Consumer-Group-Reset** (nur bei Kafka-Consumer-Fixes nötig, Service vorher stoppen):
   ```
   kafka-consumer-groups --bootstrap-server localhost:29092 --group {group} --topic {topic} --reset-offsets --to-earliest --execute
   ```

10. **Push und PR erstellen**:
   - `git push -u origin feature/<nummer>-<slug>`
   - PR via dem ermittelten MCP-Server erstellen:
     - Titel: Issue-Titel
     - Body: `Closes #<nummer>` + Zusammenfassung der Änderungen (inkl. Hinweis auf neue/geänderte Topics, ODC-Contracts, SQLMesh-Modelle falls relevant)
     - Base: `main`
   - **Falls Worktree**: Wechsle NICHT zurück — der Worktree wird von `/ship` aufgeräumt
   - **Falls kein Worktree**: Bleibe auf dem Feature-Branch
