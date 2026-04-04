# CLAUDE.md – Sachversicherung Datamesh Platform

> **Primary Reference** für AI-Assistenten und Contributor. Vor jeder Code-Generierung oder Review vollständig lesen.

## Project Overview
Eine moderne Sachversicherungs-Plattform basierend auf **Domain-Driven Design (DDD)**, **Hexagonal Architecture**, **Clean Code**, **Self-Contained Systems (SCS)** und **Data Mesh**.
* **Autonomie:** Jede Domain ist ein eigenständiger, unabhängig deploybarer Quarkus-Service.
* **Data Mesh:** Jede Domain besitzt ihre Daten (Sovereignty) und stellt sie via Data Contracts bereit.



---

## Tech Stack & Ecosystem

| Komponente | Technologie | Details |
|---|---|---|
| **Runtime** | Java 25 (LTS) | Fokus auf Virtual Threads (Project Loom) |
| **Framework** | Quarkus 3.x+ | Reactive Core, ArC DI |
| **Messaging** | Apache Kafka | SmallRye Reactive Messaging |
| **Persistence** | PostgreSQL | Ein DB-Cluster pro Domain; Hibernate Envers für Audit |
| **Analytical Storage** | Apache Iceberg on MinIO/S3 | Parquet files, Project Nessie catalog |
| **Query Engine** | Trino | Federated SQL on Iceberg tables |
| **Transformations** | SQLMesh | Incremental models on Iceberg via Trino |
| **BI / Dashboards** | Apache Superset | Self-Service BI on Trino, Keycloak SSO, Row-Level Security |
| **Metadata Catalog** | OpenMetadata | Self-service catalog, PII tags, retention |
| **Data Quality** | Soda Core | Contract testing via SodaCL on Trino |
| **Data Privacy** | HashiCorp Vault | Crypto-Shredding per ADR-009 |
| **Frontend** | Qute + htmx + Bootstrap | Server-side Rendering (SSR), kein schwerfälliges SPA-Framework |
| **Security** | Keycloak (OIDC) | Quarkus Security OIDC |
| **Data Contracts** | Open Data Contract (ODC) | YAML-basiert in `src/main/resources/contracts/` |
| **API/Schemas** | Avro / OpenAPI | Registry-gestützt |
| **Sync Communication** | gRPC (Quarkus gRPC) | Nur für ADR-010-konforme Spezialfälle (Berechnungen) |
| **Fault Tolerance** | SmallRye Fault Tolerance | Circuit Breaker, Timeout, Retry für gRPC-Calls |
| **Infrastructure** | Podman / Compose | Rootless Container-Management |

---

## Tooling & Commands

### Build & Lifecycle
* **Images bauen:** `./build.sh` (Kompiliert alle Module, baut Container Images).
* **Deploy (Compose):** `./deploy-compose.sh -d` (Baut Images + startet Docker Compose Stack).
* **Single Service (Dev):** `mvn quarkus:dev` (im Service-Verzeichnis).
* **Container Image:** `mvn clean package -Dquarkus.container-image.build=true`.

### Testing
* **Unit Tests:** `mvn test` (Fokus auf `domain/model` und `domain/service`).
* **Integration:** `mvn verify -Pintegration` (Nutzt **Testcontainers** für Postgres & Redpanda/Kafka).
* **Contract Testing:** `mvn test -Dtest=DataContractVerificationTest` (Validiert ODC-Compliance).
* **Single Test:** `mvn test -pl {module} -Dtest=ClassName#methodName`.
* **Playwright only:** `mvn test -pl {module} -Dgroups=playwright`.
* **Consumer-Group Reset (nach Consumer-Fix):** `kafka-consumer-groups --bootstrap-server localhost:29092 --group {group} --topic {topic} --reset-offsets --to-earliest --execute` (Service vorher stoppen).

---

## Project Rules (Non-Negotiable)

### 1. Language Policy (ADR-005)
* **Code & Logik:** **Englisch** (Classes, Methods, Variables, Logs, Kafka Events, ODC Descriptions).
* **UI / Labels:** **Deutsch** (Qute Templates, Error Messages für Endnutzer).
* **Dokumentation:** **Englisch** (Ausnahme: `arc42.md` für Stakeholder-Kommunikation auf Deutsch).

### 2. Architecture Constraints
* **Hexagonal Isolation:** Die `domain`-Packages dürfen **keine** Framework-Abhängigkeiten haben (kein `@Inject`, kein `@Entity`, kein Jackson).
* **No Shared State:** Keine Cross-Domain DB-Zugriffe. Kommunikation erfolgt zu 95% via Kafka.
* **Transactional Outbox:** Jeder Kafka-Event muss über die Outbox-Tabelle laufen, um Konsistenz zwischen DB und Message Broker zu garantieren.
* **ADR-009 PII Encryption:** `person.v1.state` Events enthalten Vault-Transit-verschlüsselte PII-Felder (`name`, `firstName`, `dateOfBirth`, `socialSecurityNumber`). Jeder neue Consumer muss das `"encrypted": true` Flag prüfen und via Vault Transit entschlüsseln. `insuredNumber` ist **nicht** PII und bleibt im Klartext.
* **Synchrone Ausnahmen (ADR-010):** gRPC-Calls sind erlaubt für reine Query-/Berechnungs-Use-Cases mit mandatorischem Circuit Breaker, Timeout und Graceful Degradation. Aktuell: Prämienberechnung (Policy → Product). REST nur für IAM (Keycloak).

### 3. Data Mesh & Contracts
* Jedes Kafka-Topic benötigt einen **Open Data Contract (ODC)**.
* Topic-Naming: `{domain}.v{version}.{event_type}` (z.B. `policy.v1.issued`).
* **Read Models:** Wenn Domain A Daten von Domain B benötigt, abonniert A das Topic von B und baut ein lokales Read-Model auf (Projections).

---

## Directory Structure (Standardized)

```text
{domain}/
├── src/main/java/ch/yuno/{domain}/
│   ├── domain/                <-- PURE JAVA (No Frameworks)
│   │   ├── model/             <-- Aggregates, Entities, Value Objects
│   │   ├── service/           <-- Domain Services (Business Logic)
│   │   └── port/              <-- Inbound/Outbound Interfaces
│   ├── infrastructure/        <-- ADAPTERS (Framework Dependent)
│   │   ├── persistence/       <-- JPA/Hibernate, Repositories
│   │   ├── messaging/         <-- Kafka Consumer/Producer (SmallRye)
│   │   ├── grpc/              <-- gRPC Server/Client Adapters (ADR-010)
│   │   └── web/               <-- JAX-RS Resources, Qute Controller, htmx
│   └── application/           <-- Orchestration & Use Cases
├── data-product/              <-- DOMAIN-OWNED DATA PRODUCT
│   ├── sqlmesh/
│   │   ├── silver/            <-- Silver layer models (event → current state)
│   │   ├── gold/              <-- Gold layer models (enriched/aggregated)
│   │   ├── audits/            <-- SQLMesh audit assertions
│   │   └── tests/             <-- SQLMesh test assertions
│   ├── soda/
│   │   └── checks.yml         <-- SodaCL data quality checks
│   └── debezium/
│       └── iceberg-sink.json  <-- Iceberg sink connector config
└── src/main/resources/
    ├── templates/             <-- Qute HTML (Deutsch)
    └── contracts/             <-- ODC YAML Definitions

infra/sqlmesh/models/gold/analytics/   <-- Cross-domain gold models (central)
```

---

## Ubiquitous Language Mapping

| Begriff (DE - UI) | Term (EN - Code) | Definition |
|---|---|---|
| **Versicherungspolice** | `Policy` | Der aktive Vertrag |
| **Deckung** | `Coverage` | Umfang der versicherten Risiken |
| **Schadenmeldung** | `Claim` | Eintritt eines Versicherungsfalls |
| **Prämie** | `Premium` | Kosten für den Versicherungsschutz |
| **Partner / Kunde** | `Partner` | Natürliche oder juristische Person |
| **Selbstbehalt** | `Deductible` | Eigenanteil des Kunden im Schadenfall |
| **Prämienberechnung** | `PremiumCalculation` | Berechnung der risikobasierten Prämie (gRPC, ADR-010) |
| **Risikoprofil** | `RiskProfile` | Alter, PLZ, Kanton des Versicherungsnehmers |

---

## Context7 Integration (AI Instructions)
Bei der Generierung von Code oder Konfigurationen sind folgende Quellen/Standards immer zu berücksichtigen:
1.  **Java 25 Features:** Nutze modernste Patterns (Pattern Matching, Records, Virtual Threads).
2.  **Quarkus Best Practices:** Nutze `Uni`/`Multi` für reaktive Streams, sofern sinnvoll.
3.  **ODC Spec:** Generiere bei neuen Topics immer den passenden YAML-Contract nach `Open Data Contract` Standard.

