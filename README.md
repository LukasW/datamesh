# Yuno Sachversicherung – Data Mesh Platform

A property insurance platform built on **Domain-Driven Design**, **Hexagonal Architecture**, and **Data Mesh** principles. Five autonomous Quarkus domain services plus an external HR system stub publish domain events to Kafka; an analytics platform layer consumes those events for cross-domain reporting and governance.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Domain Services (SCS)                                           │
│  partner :9080  ──┐                                             │
│  product :9081  ──┤                                             │
│  policy  :9082  ──┼──► Kafka :9092 ──► Iceberg Sink            │
│  claims  :9083  ──┤    (Outbox/CDC)    └──► MinIO (Parquet)    │
│  billing :9084  ──┘                                             │
├─────────────────────────────────────────────────────────────────┤
│ External Systems                                                │
│  hr-system :9085 ──► hr-integration :9086 (Camel) ──► Kafka    │
│                      (OData polling → ECST + Change Topics)     │
└─────────────────────────────────────────────────────────────────┘
        │ Kafka → Iceberg Sink → Parquet on MinIO
        ▼
┌─────────────────────────────────────────────────────────────────┐
│ Lakehouse & Analytics                                           │
│  Trino :8086 (Federated SQL on Iceberg)                        │
│  SQLMesh (staging → marts, incremental)                        │
│  Superset :8088 (BI dashboards, Keycloak SSO)                  │
│  Soda Core (data quality checks via SodaCL)                    │
└─────────────────────────────────────────────────────────────────┘
        │ OpenLineage + Metadata Ingestion
        ▼
┌─────────────────────────────────────────────────────────────────┐
│ Governance & Metadata                                           │
│  OpenMetadata :8585 (catalog, PII tags, lineage)               │
│  Marquez :5050 (OpenLineage lineage server)                    │
│  Vault :8200 (crypto-shredding, ADR-009)                       │
└─────────────────────────────────────────────────────────────────┘
```

Full architecture documentation: [specs/arc42.md](specs/arc42.md)

---

## Dev Mode – Service URLs

### Domain Services

| Service | UI | Swagger / OpenAPI | Debug Port |
| --- | --- | --- | --- |
| Partner (Person Management) | http://localhost:9080 | http://localhost:9080/swagger-ui | 5005 |
| Product (Insurance Products) | http://localhost:9081 | http://localhost:9081/swagger-ui | 5006 |
| Product gRPC (Premium Calculation) | grpc://localhost:9181 | — | — |
| Policy (Contract Lifecycle) | http://localhost:9082 | http://localhost:9082/swagger-ui | 5007 |
| Claims (FNOL & Claim Lifecycle) | http://localhost:9083 | http://localhost:9083/swagger-ui | 5008 |
| Billing & Collection | http://localhost:9084 | http://localhost:9084/swagger-ui | 5009 |

### External Systems & Integration

| Service | UI | API | Notes |
| --- | --- | --- | --- |
| HR-System (COTS Stub) | http://localhost:9085/mitarbeiter | http://localhost:9085/odata/Employees | Simulated external HR system |
| HR-Integration (Camel) | — | http://localhost:9086/q/health | OData → Kafka bridge |

### Infrastructure

| Component | URL | Purpose |
| --- | --- | --- |
| AKHQ (Kafka UI) | http://localhost:8085 | Browse topics, consumer groups, schemas |
| Schema Registry | http://localhost:8081/subjects | Avro schema catalogue |
| Debezium Connect | http://localhost:8083/connectors | CDC connector status |

### Lakehouse & Analytics

| Component | URL | Purpose |
| --- | --- | --- |
| MinIO Console | http://localhost:9001 | S3-compatible object store (Iceberg Parquet files) |
| Trino | http://localhost:8086 | Federated SQL on Iceberg tables |
| Superset | http://localhost:8088 | Self-Service BI dashboards (Keycloak SSO) |

### Governance & Metadata

| Component | URL | Purpose |
| --- | --- | --- |
| OpenMetadata | http://localhost:8585 | Data catalog, PII tagging, lineage |
| Marquez | http://localhost:5050 | OpenLineage lineage server |
| Marquez Web | http://localhost:3001 | Lineage visualization UI |

### Databases (direct access via psql / DBeaver)

| Database | Host | Port | User | DB name |
| --- | --- | --- | --- | --- |
| partner_db | localhost | 5432 | partner_user | partner_db |
| product_db | localhost | 5433 | product_user | product_db |
| policy_db | localhost | 5434 | policy_user | policy_db |
| billing_db | localhost | 5436 | billing_user | billing_db |
| claims_db | localhost | 5437 | claims_user | claims_db |
| hr_db | localhost | 5438 | hr_user | hr_db |

---

## Prerequisites

- **Java 21+** (virtual threads required)
- **Maven 3.9+**
- **Podman** with `podman-compose` (or Docker / Docker Compose)
- **Python 3.12+** (for DataHub ingestion scripts only)

---

## Getting Started

### 1. Configure environment

```bash
cp .env.example .env
# Edit .env – set passwords for all four databases
```

### 2. Build all services and start the stack

```bash
./build.sh
```

This runs `mvn clean package`, builds container images, and starts `podman compose up`.

**Options:**

```bash
./build.sh -t          # Include tests
./build.sh -d          # Start compose in detached (background) mode
./build.sh -t -d       # Tests + detached
./build.sh --delete-volumes  # Wipe volumes on restart (fresh DB)
```

### 3. Build a single service

```bash
cd partner   # or product, policy
mvn clean package -DskipTests

# With container image:
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.additional-tags=latest
```

### 4. Start the compose stack (images must exist)

```bash
podman compose up
# or detached:
podman compose up -d
```

---

## Testing

```bash
# All unit tests (all modules)
mvn test

# Single service
cd partner && mvn test

# Specific test class
cd partner && mvn test -Dtest=PersonCommandServiceTest

# Integration tests (Testcontainers – requires running Podman/Docker)
cd partner && mvn verify -Pintegration
```

Full testing guide (German): [docs/testing-guide-de.md](docs/testing-guide-de.md)

---

## Kafka Topics

| Topic | Domain | Event Type | Notes |
| --- | --- | --- | --- |
| `person.v1.created` | partner | PersonCreated | Delta event |
| `person.v1.updated` | partner | PersonUpdated | Delta event |
| `person.v1.deleted` | partner | PersonDeleted | Delta event |
| `person.v1.address-added` | partner | AddressAdded | Delta event |
| `person.v1.address-updated` | partner | AddressUpdated | Delta event |
| `person.v1.state` | partner | PersonState | **Compacted** – ECST full state per person |
| `product.v1.defined` | product | ProductDefined | Delta event |
| `product.v1.updated` | product | ProductUpdated | Delta event |
| `product.v1.deprecated` | product | ProductDeprecated | Delta event |
| `product.v1.state` | product | ProductState | **Compacted** – ECST full state per product |
| `policy.v1.issued` | policy | PolicyIssued | DRAFT → ACTIVE |
| `policy.v1.changed` | policy | PolicyChanged | Details updated |
| `policy.v1.cancelled` | policy | PolicyCancelled | ACTIVE → CANCELLED |
| `policy.v1.coverage-added` | policy | CoverageAdded | Coverage added |
| `policy.v1.coverage-removed` | policy | CoverageRemoved | Coverage removed |
| `claims.v1.opened` | claims | ClaimsOpened | FNOL – First Notice of Loss *(planned)* |
| `claims.v1.settled` | claims | ClaimsSettled | Claim settled or rejected *(planned)* |

| `hr.v1.employee.state` | hr | EmployeeState | **Compacted** – ECST full state per employee |
| `hr.v1.employee.changed` | hr | EmployeeChanged | Delta event (created/updated) |
| `hr.v1.org-unit.state` | hr | OrgUnitState | **Compacted** – ECST full state per org unit |
| `hr.v1.org-unit.changed` | hr | OrgUnitChanged | Delta event (created/updated) |
| `hr-integration-dlq` | hr | — | Dead-letter queue for hr-integration |

All topics are described by an Open Data Contract (ODC) YAML under `{domain}/src/main/resources/contracts/`.

---

## Metadata & Governance

**OpenMetadata** provides data discovery, PII tagging, and quality dashboards at http://localhost:8585. Two ingestion pipelines run every 6 hours:
- **kafka-metadata-ingestion** — discovers all `*.v1.*` topics
- **trino-metadata-ingestion** — discovers Iceberg tables in all raw + analytics schemas

**Marquez** (http://localhost:5050) tracks end-to-end data lineage via OpenLineage events from Debezium Connect and SQLMesh.

**Soda Core** runs SodaCL quality checks against Iceberg tables via Trino (null rates, duplicates, freshness).

---

## Project Structure

```
datamesh/
├── partner/          Partner/Customer Management (SCS, :9080)
├── product/          Product Management (SCS, :9081)
├── policy/           Policy Management (SCS, :9082)
├── claims/           Claims Management (SCS, :9083)
├── billing/          Billing & Collection (SCS, :9084 – invoicing, dunning, payouts)
├── hr-system/        External HR System Stub (COTS, :9085 – OData + CRUD UI)
├── hr-integration/   HR Integration Bridge (Camel, :9086 – OData → Kafka)
├── sales/            Sales & Distribution (spec only, not yet implemented)
├── specs/            Architecture documentation (arc42.md)
├── infra/
│   ├── debezium/     CDC connector configs + Iceberg Sink Connectors
│   ├── sqlmesh/      SQLMesh incremental models (staging → marts on Iceberg/Trino)
│   ├── soda/         Soda Core quality checks (SodaCL on Trino)
│   ├── trino/        Trino catalog configuration (Iceberg on Nessie/MinIO)
│   ├── superset/     Apache Superset configuration (Keycloak SSO)
│   ├── keycloak/     Keycloak realm import (yuno-realm.json)
│   ├── prometheus/   Prometheus scrape configs
│   └── grafana/      Grafana dashboards + datasource provisioning
├── docker-compose.yaml
├── build.sh
├── pom.xml           Maven parent (Quarkus 3.32.3, Java 25)
└── .env.example      Environment variable template
```

---

## Key Architecture Decisions

| ADR | Decision |
| --- | --- |
| ADR-001 | Kafka is the only cross-domain integration channel; no direct DB access |
| ADR-002 | Every Kafka topic has an ODC; breaking changes require a new topic version |
| ADR-003 | REST only for IAM authentication (Keycloak); domain queries via Kafka read models |
| ADR-004 | Shared Nothing – no cross-domain DB joins; read models from Kafka events |
| ADR-005 | Code in English; UI strings in German; technical docs in English |
| ADR-006 | Transactional Outbox Pattern via Debezium CDC for at-least-once delivery |
| ADR-007 | Event-Carried State Transfer via `person.v1.state` (compacted topic) |
| ADR-008 | Coverage check via local policy snapshot (no REST) – Claims fully autonomous |
| ADR-010 | gRPC for synchronous calculations (Policy→Product premium) with mandatory Circuit Breaker |

Full ADR details: [specs/arc42.md](specs/arc42.md#9-architekturentscheidungen-adrs)

---

## Production Setup

### Kafka Cluster Requirements

A production deployment requires a minimum of **3 Kafka brokers** for fault tolerance. Key broker-level settings:

| Setting | Value | Rationale |
| --- | --- | --- |
| `default.replication.factor` | 3 | Every partition has 3 replicas across brokers |
| `min.insync.replicas` | 2 | Writes succeed only when 2 of 3 replicas acknowledge, preventing data loss |
| Number of brokers | >= 3 | Tolerates 1 broker failure without data loss or unavailability |

For production deployments, configure the three Kafka brokers as separate services using the same KRaft settings shown in `docker-compose.yaml`, but with separate `KAFKA_NODE_ID` values (1, 2, 3) and `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@broker1:9093,2@broker2:9093,3@broker3:9093`.

### Required Environment Variables

Each service requires the following environment variables to be set:

| Variable | Scope | Description |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | All services | Comma-separated list of Kafka broker addresses (e.g. `broker1:9092,broker2:9092,broker3:9092`) |
| `SCHEMA_REGISTRY_URL` | All services | URL of the Confluent Schema Registry (e.g. `http://schema-registry:8081`) |
| `KEYCLOAK_URL` | All services | Base URL of the Keycloak instance (e.g. `https://keycloak.example.com`) |
| `OIDC_CLIENT_SECRET` | All services | OIDC client secret for Keycloak authentication |
| `DATABASE_URL` | Per service | JDBC connection URL (e.g. `jdbc:postgresql://db-host:5432/partner_db`) |
| `DATABASE_USER` | Per service | Database username |
| `DATABASE_PASSWORD` | Per service | Database password |

Each domain service (partner, product, policy) requires its own `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` pointing to its dedicated PostgreSQL instance.

### Topic Configuration

| Topic Type | Partitions | Replication Factor | Additional Settings |
| --- | --- | --- | --- |
| Event topics (e.g. `policy.v1.issued`) | 3 | 3 | Default retention |
| State topics / compacted (e.g. `person.v1.state`) | 3 | 3 | `cleanup.policy=compact` |
| DLQ topics (dead-letter queues) | 1 | 3 | Default retention |

---

## Tech Stack

| Layer | Technology |
| --- | --- |
| Runtime | Java 25 (Virtual Threads) |
| Framework | Quarkus 3.32.3 |
| Async Messaging | Apache Kafka (KRaft, Confluent 7.5.0) |
| Sync Communication | gRPC (Quarkus gRPC, Protobuf) – ADR-010 |
| Integration | Apache Camel (Quarkus) – HR OData bridge |
| Fault Tolerance | MicroProfile Fault Tolerance (SmallRye) |
| Schema Registry | Confluent Schema Registry (Avro) |
| CDC | Debezium PostgreSQL connector |
| Persistence | PostgreSQL 16 (one DB per domain) |
| Analytical Storage | Apache Iceberg on MinIO/S3 (Parquet, Nessie catalog) |
| Query Engine | Trino (Federated SQL on Iceberg) |
| Transformations | SQLMesh (incremental models on Iceberg via Trino) |
| Data Quality | Soda Core (SodaCL on Trino) |
| BI / Dashboards | Apache Superset (Keycloak SSO, Row-Level Security) |
| Metadata Catalogue | OpenMetadata |
| Data Lineage | OpenLineage / Marquez |
| Crypto-Shredding | HashiCorp Vault (Transit engine, ADR-009) |
| UI | Quarkus Qute + Bootstrap + htmx |
| Build | Maven (multi-module) |
| Container | Podman + podman-compose |
