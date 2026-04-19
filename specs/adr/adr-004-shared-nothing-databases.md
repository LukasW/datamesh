# ADR-004: Shared Nothing – eine PostgreSQL pro Domäne

**Status:** Accepted
**Bereich:** Persistence · Datensouveränität

## Kontext

Geteilte Datenbanken sind das klassische Anti-Pattern gegen Team-Autonomie: Schema-Änderungen in einer Tabelle brechen beliebig viele Consumer; Reporting-Queries blockieren operative Workloads; Owner-Ship ist unklar. Data Mesh erfordert explizite Datensouveränität pro Domäne.

## Entscheidung

Jede Domäne betreibt ihre **eigene PostgreSQL-Instanz** (in Compose/K8s: dedizierte Container/Pods + persistent Volume). Keine Domäne darf direkt auf eine DB einer anderen Domäne zugreifen – weder lesend noch schreibend.

Cross-Domain-Datenbedarf wird abgedeckt durch:

* **Event-basierte Read-Models** pro konsumierender Domäne (z. B. `policy_snapshot` im Claims-Service, `partner_sicht` im Policy-Service),
* **Analytische Abfragen** über den Lakehouse-Layer (Iceberg + Trino, [ADR-011](adr-011-medallion-silver-gold.md)), nicht gegen operative DBs,
* **Synchrone Queries** ausschliesslich in den Ausnahmefällen [ADR-003](adr-003-rest-only-for-iam.md) (REST/IAM) und [ADR-010](adr-010-grpc-for-synchronous-domain-calls.md) (gRPC/Berechnung).

## Konsequenzen

* Keine Cross-Domain-JOINs auf operativer Ebene – der Analytics-Layer übernimmt diese Aufgabe.
* Datenduplizierung in Read-Models wird akzeptiert (Eventual Consistency via Kafka).
* Betriebsaufwand: Jede Domäne verwaltet ihren eigenen DB-Lifecycle (Migrations, Backup, Monitoring).
* `wal_level=logical` ist Voraussetzung für [ADR-006](adr-006-transactional-outbox-debezium.md).
