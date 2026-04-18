---
name: fix-e2e
description: Iterativ alle Playwright-Tests einer Domäne grün bekommen — mvn fail-fast, scheiternden Test flicken, neu starten
argument-hint: <modul>
disable-model-invocation: true
---

Ziel: Playwright-Tests einer Domäne (`$ARGUMENTS` = z.B. `policy`, `claims`, `partner`, `billing`, …) komplett grün bekommen. Optional: `mvn clean install` übers gesamte Repo inkl. aller `-Dgroups=playwright` Suites.

## Ablauf

Wiederhole diese Schleife, bis die Playwright-Suite des Moduls grün durchläuft:

1. **Starten**: Im Hintergrund ausführen (background command):
   - Einzelnes Modul: `mvn test -pl $ARGUMENTS -Dgroups=playwright -Dsurefire.skipAfterFailureCount=1`
   - Alle Module: `mvn clean install -Dsurefire.skipAfterFailureCount=1 -Dfailsafe.skipAfterFailureCount=1`

   Fail-Fast (`skipAfterFailureCount=1`) sorgt dafür, dass Maven beim ersten gescheiterten Test abbricht — so kann der Root Cause sauber isoliert werden.

2. **Beobachten**: Output ist wegen Background-Puffer verzögert. Warte auf die task-notification oder prüfe Zwischenstand via:
   - `{module}/target/surefire-reports/` (Unit/Playwright-Tests)
   - `{module}/target/failsafe-reports/` (Integration-Tests mit `-Pintegration`)
   - `{module}/target/quarkus.log` für Backend-Logs

3. **Report**: Sobald der Build abbricht (oder fertig ist), gib dem User:
   - **Welche Tests sind durchgelaufen?** (aus den `[INFO] Tests run: N, Failures: 0, Errors: 0` Zeilen)
   - **Welcher Test ist gescheitert?** (Klassenname + Testmethode)
   - **Art des Fehlers**: Playwright-Timeout, Assertion-Failure, Strict-Mode-Violation, Quarkus-Startup-Fehler, Testcontainer-Port-Clash, Kafka-Consumer-Lag
   - **Fehlermeldung**: Die relevanten Zeilen aus dem Stacktrace

4. **Flicken**: Analysiere den Fehler und flicke **ausschliesslich** den gescheiterten Test (bzw. die zugehörige Qute-Template- oder Controller-Code-Stelle).
   - **Playwright-Timeout**: Locator prüfen — bevorzugt `role`-based (`getByRole('button', { name: 'Speichern' })`) oder `data-testid`-Attribute, keine fragilen CSS-Klassen. htmx-Interaktionen brauchen oft ein `waitFor()` auf `hx-target`-Swap.
   - **Strict-Mode-Violation**: Locator engeren Scope geben, `{ exact: true }` nutzen oder `.first()` / `.nth(n)`.
   - **Assertion-Failure**: Erwartungswert mit tatsächlicher Qute-Template-Struktur abgleichen (deutsche Labels gem. ADR-005).
   - **Quarkus-Startup-Fehler**: `target/quarkus.log` prüfen — häufig Keycloak/Vault-Abhängigkeit in `application-test.yml` falsch konfiguriert.
   - **Testcontainer-Port-Clash**: `podman ps` prüfen und verwaisten Container stoppen.
   - **Kafka-Consumer-Lag / Event-Order**: Outbox-Relay und Konsumer-Offset prüfen; ggf. `kafka-consumer-groups ... --reset-offsets --to-earliest --execute` (Service vorher stoppen).
   - **Nicht** andere (grüne) Tests mitändern — nur den einen, der gerade failed.

5. **Neu starten**: Zurück zu Schritt 1. Wenn derselbe Test wieder failed mit demselben Fehler → tiefer analysieren (Seite per Playwright MCP anschauen, DB-Stand via Trino/psql dumpen, Kafka-Topic-Content mit `kafka-console-consumer` inspizieren). Wenn ein neuer Test failed → normaler nächster Iterationsschritt.

## Nützliche Debug-Shortcuts

- **Einzelner Playwright-Test**: `mvn test -pl <modul> -Dgroups=playwright -Dtest=ClassName#methodName`
- **Quarkus Dev Mode** mit Live-Reload (manuelle Reproduktion): im Modul-Verzeichnis `mvn quarkus:dev` — danach via Browser oder Playwright MCP gegen `http://localhost:<port>` testen.
- **Live-Inspection via Playwright MCP**: `mcp__plugin_playwright_playwright__browser_navigate` + `browser_evaluate` gegen den laufenden Quarkus zeigt den tatsächlichen DOM-Zustand inkl. htmx-Swap-Ergebnis.
- **Compose-Stack inspizieren**: `./deploy-compose.sh -d` für vollen Stack (Kafka/Postgres/Trino/Superset/Vault/Keycloak); einzelne Services via `podman logs <container>`.
- **Contract-Verifier separat**: `mvn test -Dtest=DataContractVerificationTest` — stellt sicher, dass Event-Payloads zum ODC passen. Bei Event-Schema-Drift hier zuerst prüfen, bevor Playwright-Tests geflickt werden.

## Commit & Abschluss

Nur dann einen Commit erstellen, wenn entweder die gezielte Modul-Suite (`mvn test -pl <modul> -Dgroups=playwright`) ODER `mvn clean install` über alle Module ohne Fail-Fast-Flag grün durchläuft. Commit-Message: `fix(<domain>): <kurze Beschreibung> (#<issue>)`. Nicht pushen ohne explizite User-Freigabe.
