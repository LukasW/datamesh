# Architecture Decision Records (ADRs)

Diese Übersicht listet alle Architekturentscheidungen der Sachversicherungs-Datamesh-Plattform. Jede ADR folgt dem MADR-Muster (Status · Kontext · Entscheidung · Konsequenzen). Abgelöste Entscheidungen bleiben als Teil der Historie dokumentiert.

> Gesamtarchitektur-Kontext: [`../arc42.md`](../arc42.md)
> Projekt-Leitplanken (Tech Stack, Regeln): [`../../CLAUDE.md`](../../CLAUDE.md)

## Index

| ID | Titel | Status | Bereich |
|----|-------|--------|---------|
| [ADR-001](adr-001-async-integration-via-kafka.md) | Asynchrone Integration via Kafka | Accepted | Integration |
| [ADR-002](adr-002-open-data-contract.md) | Open Data Contract als verbindlicher Vertrag | Accepted | Data Mesh |
| [ADR-003](adr-003-rest-only-for-iam.md) | REST ausschliesslich für IAM-Authentifizierung | Updated (ersetzt alte Fassung) | Integration |
| [ADR-004](adr-004-shared-nothing-databases.md) | Shared Nothing – eine PostgreSQL pro Domäne | Accepted | Persistence |
| [ADR-005](adr-005-language-policy.md) | Sprachpolitik – Code Englisch, UI Deutsch | Accepted | Governance |
| [ADR-006](adr-006-transactional-outbox-debezium.md) | Transactional Outbox via Debezium CDC | Accepted | Messaging |
| [ADR-007](adr-007-ecst-person-state.md) | Event-Carried State Transfer via compacted State Topics | Accepted | Data Mesh |
| [ADR-008](adr-008-coverage-check-local-snapshot.md) | Deckungsprüfung via lokalem Policy-Snapshot | Accepted | Autonomy |
| [ADR-009](adr-009-crypto-shredding-pii.md) | Crypto-Shredding für PII-Felder in Kafka-Events | Proposed | Privacy |
| [ADR-010](adr-010-grpc-for-synchronous-domain-calls.md) | gRPC für synchrone Domänen-Calls in Spezialfällen | Accepted | Integration |
| [ADR-011](adr-011-medallion-silver-gold.md) | Medallion-Architektur mit Silver- und Gold-Layer (Trino + SQLMesh) | Accepted | Analytics |
| [ADR-012](adr-012-domain-owned-data-products.md) | Domain-owned Data Products im Repository-Layout | Accepted | Data Mesh |
| [ADR-013](adr-013-sqlmesh-near-realtime-scheduler.md) | Near-Realtime Silver/Gold via langlaufenden SQLMesh-Scheduler | Accepted | Analytics |
| [ADR-014](adr-014-one-iceberg-sink-per-topic.md) | One Iceberg Sink Connector pro Topic | Accepted | Lakehouse |
| [ADR-015](adr-015-nessie-rocksdb-persistence.md) | Persistenter Nessie-Katalog via RocksDB | Accepted | Lakehouse |

## Konventionen

* **Status:** `Proposed` → `Accepted` → `Superseded by ADR-XYZ` · abgelöste Fassungen verbleiben im Repo.
* **Dateibenennung:** `adr-NNN-kebab-case-title.md` (fortlaufende 3-stellige ID).
* **Sprache:** Deutsch (Stakeholder-Dokumentation; folgt ADR-005-Ausnahme für arc42).
* **Pflege:** Neue ADRs werden via PR eingebracht, Index hier ergänzt, optional in [`arc42.md` §9](../arc42.md) referenziert.
