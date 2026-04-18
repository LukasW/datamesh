# Architectural Problems – Data Mesh Pipeline

> Dokumentation der während Deploy-Verifikation (2026-04-17/18) aufgedeckten architektonischen Schwachstellen in der Iceberg/Nessie/SQLMesh-Pipeline.

## Problem 1: Nessie Catalog ist IN_MEMORY

**Wo:** `docker-compose.yaml:926`

```yaml
environment:
  NESSIE_VERSION_STORE_TYPE: IN_MEMORY
```

**Auswirkung:**

- Jeder Nessie-Neustart (egal ob gewollt oder durch `compose run`) löscht den kompletten Iceberg-Catalog.
- Iceberg-Tables sind nach Nessie-Restart "verwaist": Parquet-Files liegen in MinIO, aber keine Metadata mehr in Nessie → aus Trino/Superset nicht mehr abfragbar.
- Pipeline muss komplett neu durchlaufen (Sinks → Raw → Silver → Gold).

**Root Cause:** IN_MEMORY-Storage ist für Entwicklung vorgesehen, nicht für längere Sessions.

**Empfohlener Fix:**

```yaml
environment:
  NESSIE_VERSION_STORE_TYPE: ROCKSDB
  NESSIE_VERSION_STORE_PERSIST_ROCKS_DATABASE_PATH: /nessie/data
volumes:
  - nessie-data:/nessie/data
```

Alternativ JDBC-Backend mit Postgres-Container.

---

## Problem 2: Iceberg-Sinks teilen sich `control-iceberg` Topic

**Wo:** `{domain}/data-product/debezium/iceberg-sink.json` (alle 7 Sinks)

Alle Sinks nutzen den **Default-Control-Topic** `control-iceberg` (nicht explizit gesetzt). Bei 7 parallelen Sinks entstehen Race Conditions:

- Alle Coordinators senden `START_COMMIT` auf dieselbe Partition `control-iceberg-0`.
- Workers erhalten Events aus dem globalen Stream, filtern per Commit-ID.
- Unter Last oder nach Rebalance verpassen einzelne Coordinators ihre `DATA_WRITTEN`-Antworten → `committed to 0 table(s)`.

**Beobachtung:** Initial committen meist nur 2 von 7 Sinks (partner, policy). Die restlichen brauchen manuellen Delete-Recreate-Zyklus.

**Empfohlener Fix:** Pro Sink eigener Control-Topic

```json
{
  "iceberg.control.topic": "control-iceberg-{domain}"
}
```

---

## Problem 3: `deploy.sh` prüft Raw-Tables ohne Daten-Validierung

**Wo:** `deploy.sh:94-106`

```bash
NESSIE_TABLES=$(curl ... | python3 -c "... sum(1 for e in ... if e['type']=='ICEBERG_TABLE' and '_raw.' in ...)")
if [[ "${NESSIE_TABLES}" -ge 5 ]]; then
  echo "  ✓ Raw tables committed to Nessie (${NESSIE_TABLES} tables)"
```

**Auswirkung:** Zählt nur Table-Metadata in Nessie, nicht `snapshots$` / `files$`. Ein Sink kann eine leere Table auto-createn → Check passiert → transform-init läuft auf leeren Tables → Silver/Gold bleibt leer ohne Fehler.

**Empfohlener Fix:** Trino-basierter Count-Check:

```bash
for table in policy_raw.policy_events partner_raw.person_events ...; do
  count=$(trino-cli --execute "SELECT count(*) FROM iceberg.$table")
  [[ $count -gt 0 ]] || exit 1
done
```

---

## Problem 4: `transform-init.py` hardcoded Container-DNS

**Wo:** `infra/trino/transform-init.py:14,79`

```python
TRINO = "http://trino:8086"
req = urllib.request.Request("http://nessie:19120/api/v2/trees/main/entries")
```

**Auswirkung:** Script läuft nur innerhalb des Compose-Netzwerks. Manuelle Debug-Läufe vom Host erfordern Text-Patching oder Container-exec (Trino-Image hat kein `python3`).

**Empfohlener Fix:** Konfigurierbar über ENV:

```python
TRINO = os.environ.get("TRINO_URL", "http://trino:8086")
NESSIE = os.environ.get("NESSIE_URL", "http://nessie:19120")
```

---

## Problem 5: `compose run --rm transform-init` recreiert Dependencies

**Wo:** `deploy.sh:110`

```bash
${=COMPOSE_CMD} run --rm transform-init
```

**Auswirkung:** `podman compose run` startet alle `depends_on`-Container neu (inkl. Nessie, MinIO, Vault, Trino). Kombiniert mit Problem 1 zerstört das den gerade aufgebauten Raw-Layer.

**Empfohlener Fix:** Entweder

- (a) `transform-init` als regulären Service mit `profiles: [tools]` laufen lassen (nicht via `run`), oder
- (b) Python-Script direkt im Trino-Container ausführen: `podman exec datamesh-trino-1 python3 /scripts/transform-init.py` — setzt voraus, dass Python im Trino-Image verfügbar ist.

---

## Problem 6: Consumer-Group-Offsets überleben Sink-Delete

**Wo:** Kafka Consumer Groups `connect-iceberg-sink-*`

**Auswirkung:** Bei einem Delete/Recreate-Zyklus eines Iceberg-Sinks bleiben die Consumer-Group-Offsets in `__consumer_offsets` liegen. Der neu erstellte Sink wird derselben Group zugeordnet und rezipiert die alten (am Topic-Ende liegenden) Offsets → kein Re-Read möglich, Sink committet 0 Tables ohne Fehlerhinweis.

**Beobachtetes Verhalten während Deploy-Verifikation:**

```text
CURRENT-OFFSET=10  LOG-END-OFFSET=10  LAG=0   ← neue Sink-Instanz übernimmt alte Position
```

**Empfohlener Fix:** `deploy.sh` bzw. ein Repair-Skript muss beim Sink-Reset die Consumer-Groups explizit mitlöschen:

```bash
podman exec datamesh-kafka-1 kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --delete --group "connect-iceberg-sink-${domain}"
```

---

## Zusammenfassung

Die Pipeline ist im Happy Path funktional, aber **nicht robust gegen Teilfehler oder Container-Restarts**. Die kritische Kette ist:

```text
Services → Kafka Topics → Outbox Debezium → Iceberg-Sinks (control-iceberg) → Nessie (IN_MEMORY) → Trino → transform-init → Silver/Gold
```

Jeder dieser Schritte hat mindestens einen Single Point of Failure ohne automatische Recovery. Priorität der Fixes:

1. **Nessie auf ROCKSDB** (Problem 1) – höchste Wirkung, minimaler Aufwand.
2. **Daten-Validierung in deploy.sh** (Problem 3) – verhindert grüne Deploys mit leerer Pipeline.
3. **Consumer-Group-Cleanup-Skript** (Problem 6) – ermöglicht sauberen Sink-Reset ohne `compose down -v`.
4. **`iceberg.control.topic` pro Sink** (Problem 2) – erhöht Startup-Stabilität.
