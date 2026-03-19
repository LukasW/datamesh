# CSS Sachversicherung – Data Mesh Platform

A property insurance platform built on **Domain-Driven Design**, **Hexagonal Architecture**, and **Data Mesh** principles. Three autonomous Quarkus services publish domain events to Kafka; an analytics platform layer consumes those events for cross-domain reporting and governance.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Domain Services (SCS)                                           │
│  partner :9080  ──┐                                             │
│  product :9081  ──┼──► Kafka :9092 ──► platform-consumer       │
│  policy  :9082  ──┘    (Outbox/CDC)    └──► platform-db :5435  │
└─────────────────────────────────────────────────────────────────┘
        │ Debezium CDC (WAL → Kafka topics)
        ▼
┌─────────────────────────────────────────────────────────────────┐
│ Analytics & Governance Platform                                 │
│  dbt (staging → marts)    Spark Structured Streaming            │
│  portal :8090             governance (lint / compat / freshness)│
│  Airflow :8091 (scheduling)                                     │
└─────────────────────────────────────────────────────────────────┘
        │ ODC metadata + Kafka schema ingestion
        ▼
┌─────────────────────────────────────────────────────────────────┐
│ Metadata Platform                                               │
│  DataHub GMS :8080        DataHub Frontend :9002                │
│  Elasticsearch :9200      MySQL (DataHub store)                 │
│  datahub-ingest (auto, on startup)                              │
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
| Policy (Contract Lifecycle) | http://localhost:9082 | http://localhost:9082/swagger-ui | 5007 |

### Infrastructure

| Component | URL | Purpose |
| --- | --- | --- |
| AKHQ (Kafka UI) | http://localhost:8085 | Browse topics, consumer groups, schemas |
| Schema Registry | http://localhost:8081/subjects | Avro schema catalogue |
| Debezium Connect | http://localhost:8083/connectors | CDC connector status |

### Analytics & Governance

| Component | URL | Purpose |
| --- | --- | --- |
| Data Product Portal | http://localhost:8090 | Product catalogue, lineage, governance dashboard |
| Data Product Portal – Demo | http://localhost:8090/demo | Live cross-domain analytics (mart_portfolio_summary) |
| Data Product Portal – Lineage | http://localhost:8090/lineage | Cross-domain lineage graph (D3.js) |
| Data Product Portal – Governance | http://localhost:8090/governance | Schema compat, ODC quality scores |
| Airflow | http://localhost:8091 | DAG scheduling, dbt orchestration, pipeline monitoring |
| DataHub Frontend | [http://localhost:9002](http://localhost:9002) | Enterprise metadata catalogue (Topics, ODC, dbt lineage) |
| DataHub GMS API | [http://localhost:8080](http://localhost:8080) | DataHub metadata REST API |
| Elasticsearch | [http://localhost:9200](http://localhost:9200) | DataHub search & graph index |

### Databases (direct access via psql / DBeaver)

| Database | Host | Port | User | DB name |
| --- | --- | --- | --- | --- |
| partner_db | localhost | 5432 | partner_user | partner_db |
| product_db | localhost | 5433 | product_user | product_db |
| policy_db | localhost | 5434 | policy_user | policy_db |
| platform_db (analytics) | localhost | 5435 | platform_user | platform_db |
| airflow_db (Airflow metadata) | localhost | (internal only) | airflow | airflow |

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
cd partner && mvn test -Dtest=PersonApplicationServiceTest

# Integration tests (Testcontainers – requires running Podman/Docker)
cd partner && mvn verify -Pintegration
```

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
| `policy.v1.issued` | policy | PolicyIssued | DRAFT → ACTIVE |
| `policy.v1.changed` | policy | PolicyChanged | Details updated |
| `policy.v1.cancelled` | policy | PolicyCancelled | ACTIVE → CANCELLED |
| `policy.v1.coverage-added` | policy | CoverageAdded | Coverage added |
| `policy.v1.coverage-removed` | policy | CoverageRemoved | Coverage removed |

All topics are described by an Open Data Contract (ODC) YAML under `{domain}/src/main/resources/contracts/`.

---

## DataHub

DataHub is fully integrated into the main stack and starts automatically with `podman compose up`. No separate setup required.

**What happens on startup:**

1. `datahub-mysql` + `datahub-elasticsearch` start as DataHub's storage backend
2. `datahub-kafka-setup` creates DataHub's internal Kafka topics on the existing broker
3. `datahub-upgrade` runs DB migrations
4. `datahub-gms` (GMS core service) becomes healthy
5. `datahub-ingest` runs automatically and ingests Kafka schemas + ODC metadata
6. `datahub-frontend` serves the UI at [http://localhost:9002](http://localhost:9002)

**Login:** `datahub` / `datahub` (DataHub default credentials)

**DataHub reuses the existing Kafka and Schema Registry — no extra ZooKeeper or Kafka instance needed.**

**Manual re-ingestion** (e.g. after adding new ODC contracts):

```bash
# Trigger ingestion container manually
podman compose run --rm datahub-ingest

# Or run the local ingestion script (requires pip install -r infra/datahub/requirements.txt)
./infra/datahub/ingest.sh
```

---

## Project Structure

```
datamesh/
├── partner/          Partner/Customer Management (SCS, :9080)
├── product/          Product Management (SCS, :9081)
├── policy/           Policy Management (SCS, :9082)
├── specs/            Architecture documentation (arc42.md)
├── infra/
│   ├── debezium/     CDC connector configs (partner + product outbox)
│   ├── platform/     Kafka → platform_db consumer (Python)
│   ├── dbt/          Staging + mart models (cross-domain analytics)
│   ├── spark/        Spark Structured Streaming (Delta Lake)
│   ├── governance/   ODC linting, schema compat, freshness checks
│   ├── portal/       Data Product Portal (FastAPI, :8090)
│   └── datahub/      DataHub ingestion recipes + ODC metadata script
├── docker-compose.yaml
├── build.sh
├── pom.xml           Maven parent (Quarkus 3.32.3, Java 21)
└── .env.example      Environment variable template
```

---

## Key Architecture Decisions

| ADR | Decision |
| --- | --- |
| ADR-001 | Kafka is the only cross-domain integration channel; no direct DB access |
| ADR-002 | Every Kafka topic has an ODC; breaking changes require a new topic version |
| ADR-003 | REST only for synchronous exceptions (Claims→Policy coverage check, IAM auth) |
| ADR-004 | Shared Nothing – no cross-domain DB joins; read models from Kafka events |
| ADR-005 | Code in English; UI strings in German; technical docs in English |
| ADR-006 | Transactional Outbox Pattern via Debezium CDC for at-least-once delivery |
| ADR-007 | Event-Carried State Transfer via `person.v1.state` (compacted topic) |

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

Refer to `docker-compose.prod.yaml` for a 3-broker KRaft configuration suitable for production-like environments.

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
| Runtime | Java 21 (Virtual Threads) |
| Framework | Quarkus 3.32.3 |
| Async Messaging | Apache Kafka (KRaft, Confluent 7.5.0) |
| Schema Registry | Confluent Schema Registry (Avro) |
| CDC | Debezium PostgreSQL connector |
| Persistence | PostgreSQL 16 (one DB per domain) |
| UI | Quarkus Qute + Bootstrap + htmx |
| Build | Maven (multi-module) |
| Container | Podman + podman-compose |
| Analytics | dbt + Spark Structured Streaming |
| Governance | Custom Python scripts (lint, compat, freshness) |
| Data Portal | FastAPI + Jinja2 |
| Metadata Catalogue | DataHub (optional) |
