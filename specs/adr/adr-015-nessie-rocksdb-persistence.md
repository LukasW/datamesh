# ADR-015: Persistenter Nessie-Katalog via RocksDB

**Status:** Accepted
**Bereich:** Lakehouse · Metadaten-Persistenz
**Eingeführt in:** PR #2 / #9 (`fix(infra): persist Nessie catalog with RocksDB backend`)

## Kontext

Project Nessie dient als versionierter Iceberg-Katalog (Git-ähnliche Semantik für Tabellen-Metadaten, Branching, Tagging). In der ursprünglichen Compose-Konfiguration lief Nessie mit dem In-Memory-Backend:

* **Jeder Container-Restart verlor den gesamten Katalog** (Tabellen-Definitionen, Snapshots, Branch-History).
* Folgen: Iceberg-Sinks mussten Tabellen neu erstellen, Trino-Queries scheiterten bis Transform-Init durchgelaufen war, Superset-Dashboards brachen.
* Dev-/Integrations-Experience war inakzeptabel; Daten-Verlust bei Produktions-Wartungsarbeiten wäre katastrophal.

## Entscheidung

Nessie wird mit dem **RocksDB-Backend** konfiguriert und speichert den Katalog auf einem persistent Docker-Volume:

```yaml
nessie:
  environment:
    QUARKUS_PROFILE: prod
    NESSIE_VERSION_STORE_TYPE: ROCKSDB
    NESSIE_VERSION_STORE_PERSIST_ROCKS_DATABASE_PATH: /nessie/data
  volumes:
    - nessie-rocksdb:/nessie/data
```

* **Volume:** Named Volume `nessie-rocksdb`, überlebt `docker compose down` (nicht `down -v`).
* **Konsistenz:** RocksDB-Snapshots bleiben mit den Iceberg-Parquet-Dateien in MinIO synchron, solange Volumes zusammengehörig verwaltet werden.
* **Backup-Pfad:** RocksDB-Verzeichnis + MinIO-Bucket müssen gemeinsam gesichert werden (sonst Orphan-Snapshots).

## Konsequenzen

* Catalog-State überlebt Restarts und Neubauten (ausser `down -v` oder explizitem Volume-Löschen).
* Entwickler-Workflow für Iterationen am Lakehouse wurde dramatisch stabiler.
* Für Produktion ist ein Upgrade auf ein HA-Backend (z. B. PostgreSQL-basiertes `JDBC2`-Backend) geplant – RocksDB ist ein lokaler Store und unterstützt kein Multi-Node-Nessie.
* Restore-Runbook muss Volume- und Bucket-Restore atomar dokumentieren.
