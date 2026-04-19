# ADR-011: Medallion-Architektur mit Silver- und Gold-Layer (Trino + SQLMesh)

**Status:** Accepted
**Bereich:** Analytics · Lakehouse

## Kontext

Debezium Iceberg Sinks schreiben rohe Kafka-Event-Payloads 1:1 in den `raw`-Bereich der Iceberg-Warehouse (JSON-basierte Event-Struktur, nested, typ-unsicher, ohne Deduplication). Diese Rohdaten sind:

* direkt nicht abfragefreundlich (JSON-Parsing pro Query),
* nicht deduziert/nicht konsolidiert (pro Aggregat mehrere Event-Zeilen),
* nicht für BI/Superset oder Data-Quality-Checks geeignet,
* für Analysten kognitiv teuer.

Gleichzeitig soll Analytics keine direkten Zugriffe auf operative DBs haben ([ADR-004](adr-004-shared-nothing-databases.md)).

## Entscheidung

Klassische **Medallion-Architektur** mit drei logischen Layern, alle als Iceberg-Tabellen im Nessie-Catalog ([ADR-015](adr-015-nessie-rocksdb-persistence.md)), via Trino abgefragt:

| Layer | Zweck | Inhalt | Pflege |
|-------|-------|--------|--------|
| **raw** | Landing | 1:1 Kafka-Events (Value-Payload + Event-Envelope), pro Topic eine Tabelle | Debezium Iceberg Sinks ([ADR-014](adr-014-one-iceberg-sink-per-topic.md)) |
| **silver** | Typisierung + Current State | Geparste, typisierte Spalten; pro Aggregat **ein** Eintrag (aktueller Zustand), Deduplication via Event-Reihenfolge | SQLMesh-Modelle, domain-owned ([ADR-012](adr-012-domain-owned-data-products.md)) |
| **gold** | Fachlich konsolidiert + aggregiert | Dimensionen, Facts, KPI-Marts, Cross-Domain-Joins | SQLMesh-Modelle (domain-owned für eigene Golds, central `infra/sqlmesh/models/gold/analytics/` für domänenübergreifende) |

**Pipeline-Mechanik:**

* **`transform-init`** (Python-Script gegen Trino) erstellt die Silver- und Gold-Schemas deterministisch via `DROP TABLE IF EXISTS` + `CREATE TABLE AS SELECT`. Läuft einmalig beim Deploy und bei Schema-Änderungen – macht den Layer reproduzierbar und nukleierbar.
* **SQLMesh** ([ADR-013](adr-013-sqlmesh-near-realtime-scheduler.md)) übernimmt inkrementelle Refreshes für near-realtime Updates.
* **Superset** konsumiert ausschliesslich Gold-Modelle (BI-Schicht).
* **Soda Core** prüft Silver und Gold gegen SodaCL-Checks aus den ODCs.

## Konsequenzen

* BI-Konsumenten arbeiten auf sauberen, typisierten Modellen – keine JSON-Parsing-Magie in Dashboards.
* Silver entspricht dem Read-Model der jeweiligen Domäne in analytischer Form; Gold bedient BI-Use-Cases.
* `transform-init` ist nicht-idempotent im Sinne von Row-Preservation (DROP+CTAS) → sichere Strategie für deklarative Schemas, aber nicht für inkrementelle Historie (diese bleibt in `raw`).
* **Wichtig (vgl. Projekt-Memory):** `transform-init` wird nur bei Deploys ausgeführt; Row-Erhalt über Deploys liefert der SQLMesh-Scheduler ([ADR-013](adr-013-sqlmesh-near-realtime-scheduler.md)).
* Nessie-Branching erlaubt nicht-disruptive Schema-Experimente in Feature-Branches des Katalogs.
