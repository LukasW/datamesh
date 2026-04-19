# ADR-006: Transactional Outbox via Debezium CDC

**Status:** Accepted
**Bereich:** Messaging · Konsistenz
**Pilot-Domäne:** Partner Service (Pattern wird inkrementell auf alle Domänen ausgerollt)

## Kontext

Der ursprüngliche Ansatz einiger Domänen-Services publizierte Kafka-Events **direkt nach** dem Datenbank-Commit (klassisches Dual-Write-Problem):

* Fiel der Kafka-Publish fehl, war das Event verloren, obwohl die DB-Transaktion committed war.
* Fiel der DB-Commit fehl, war bereits ein nicht-existenter Zustand publiziert.

Dies verletzt at-least-once Delivery und widerspricht dem Qualitätsziel «Ausfallsicherheit» (Priorität 4).

## Entscheidung

Jede Domäne schreibt Domain-Events atomar in eine `outbox`-Tabelle **innerhalb derselben DB-Transaktion** wie die fachlichen Daten. Ein Debezium-Connect-Cluster liest neue Zeilen via PostgreSQL WAL (logical replication, `wal_level=logical`) und publiziert sie an die Kafka-Topics. Der Application-Service hat **keine** direkte Kafka-Producer-Abhängigkeit mehr.

```
PersonApplicationService
  └─ outbox INSERT (same TX as domain entity)
       └─ PostgreSQL WAL (wal_level=logical)
            └─ Debezium Connect (EventRouter SMT, topic routing)
                 └─ Kafka topics  person.v1.*
```

Outbox-Struktur: `id`, `aggregate_type`, `aggregate_id`, `event_type`, `topic`, `payload (jsonb)`, `headers (jsonb)`, `created_at`. EventRouter SMT mapped `topic` auf das Kafka-Ziel-Topic.

## Konsequenzen

* Garantierte at-least-once Delivery – keine Events verloren.
* End-to-End-Latenz steigt leicht (WAL-Polling-Intervall Debezium, typisch < 500 ms).
* Debezium-Connect ist eine zusätzliche Infrastrukturkomponente (Container, Port 8083).
* Producer-Services verlieren `quarkus-messaging-kafka` als Producer-Abhängigkeit (Consumer weiterhin erlaubt).
* Consumer müssen idempotent sein (siehe PR #17 / #23 für Billing).
* Wird schrittweise auf alle Domänen ausgerollt; zusätzliche Debezium-Sinks nach Iceberg pro Topic ([ADR-014](adr-014-one-iceberg-sink-per-topic.md)).
