# ADR-013: Near-Realtime Silver/Gold via langlaufenden SQLMesh-Scheduler

**Status:** Accepted
**Bereich:** Analytics · Pipeline-Orchestration
**Eingeführt in:** PR #28 / #30 (`feat(infra): add SQLMesh long-running scheduler for near-realtime silver/gold`)

## Kontext

Die [Medallion-Pipeline](adr-011-medallion-silver-gold.md) braucht zwei komplementäre Pflege-Mechaniken:

1. **Deterministisches Bootstrapping / Schema-Rebuild:** Nach Schema-Änderungen (neue Spalte, Typänderung) muss die Silver-/Gold-Tabelle konsistent neu aufgebaut werden. Das übernimmt `transform-init` mit DROP+CTAS (nur beim Deploy).
2. **Inkrementelle Aktualisierung zur Laufzeit:** Zwischen Deploys fliessen neue Events durch den `raw`-Layer → Silver und Gold müssen fortlaufend aufgefrischt werden, ohne ganze Tabellen zu rebuilden.

Airflow als Orchestrator wurde bewertet und verworfen (Overhead, eigener DB-Cluster, Deploy-Komplexität für den aktuellen Pipeline-Umfang).

## Entscheidung

Ein **langlaufender SQLMesh-Scheduler-Container** (Service `sqlmesh-scheduler` in `docker-compose.yaml`) ruft in einer Endlosschleife periodisch `sqlmesh run` auf:

```yaml
command:
  - |
    INTERVAL="${SQLMESH_RUN_INTERVAL_SECONDS:-60}"
    while true; do
      sqlmesh run || echo "  sqlmesh run failed (will retry)"
      sleep "$INTERVAL"
    done
```

* **Default-Intervall:** 60 Sekunden (via `SQLMESH_RUN_INTERVAL_SECONDS` konfigurierbar).
* **State-Backend:** Shared named volume `sqlmesh-state` (SQLite bei Compose, für Produktion gegen ein DB-Backend tauschbar).
* **Modell-Mounts:** Alle Domain-eigenen Silver/Gold-Ordner + der zentrale Analytics-Ordner werden read-only eingebunden ([ADR-012](adr-012-domain-owned-data-products.md)).
* **Lifecycle:**
  * `sqlmesh-init` (Compose-Profile `tools`) muss **einmalig** gelaufen sein, um den Model-State zu initialisieren – davor ist `sqlmesh run` ein No-Op. Bootstrap wird via `deploy-compose.sh` gesteuert.
  * `sqlmesh-scheduler` startet nach `trino` (healthy) und `transform-init` (completed successfully) und bleibt per `restart: unless-stopped` dauerhaft im Stack.
* **Fehlertoleranz:** Jeder Tick ist unabhängig – schlägt `sqlmesh run` fehl (fehlende Raw-Tabellen, transiente Trino-Fehler), retried die nächste Iteration.
* **Ressourcen-Cap:** `cpus: 1.0`, `memory: 1G` verhindert Runaway-Verbrauch im Dev-Host.

## Konsequenzen

* Silver/Gold-Daten sind innerhalb von ~60 Sekunden nach Event-Eintreffen in Superset sichtbar (near-realtime).
* Pipeline-State lebt im Volume `sqlmesh-state` – bei `docker compose down -v` geht er verloren und wird via `sqlmesh-init` neu aufgebaut.
* Kein zusätzlicher Orchestrator-Service nötig (Airflow, Dagster, Prefect).
* Pro Tick laufen **alle** inkrementellen Modelle, auch wenn nur ein Topic neue Events hat – SQLMesh dedupliziert das intern via `@INCREMENTAL`-Filter.
* Skaliert gut bis Mid-Volume-Workloads; bei deutlich höherem Durchsatz Upgrade auf SQLMesh auf Kubernetes + dedicated state backend.
* **Wichtig (Projekt-Memory):** DROP+CTAS für Silver/Gold läuft nur in `transform-init`; der Scheduler arbeitet ausschliesslich inkrementell.
