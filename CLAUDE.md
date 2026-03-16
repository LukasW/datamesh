# CLAUDE.md – Sachversicherung Datamesh Platform

> This file is the **primary reference** for AI assistants (Claude, GitHub Copilot) and
> new contributors. Read it fully before generating or reviewing code.

---

## Project Overview

A modern property insurance (Sachversicherung) platform built on **Domain-Driven Design**,
**Hexagonal Architecture**, **Clean Code**, **Self-Contained Systems (SCS)**, and
**Data Mesh** principles. Each domain is an autonomous, independently deployable Quarkus service.

---

## Tech Stack

| Component | Technology |
|---|---|
| Runtime | Java 21+ with Virtual Threads |
| Framework | Quarkus |
| Async Integration | Apache Kafka (SmallRye Reactive Messaging) |
| Sync Integration | REST (exceptions only) |
| Persistence | PostgreSQL (one DB per domain, JPA/Hibernate Envers) |
| UI | Quarkus Qute + Bootstrap + htmx (server-side, no SPA) |
| Auth | Keycloak (OIDC/OAuth2), Quarkus OIDC extension |
| Data Contracts | Open Data Contract (ODC) YAML |
| Schemas | Avro/Protobuf via Schema Registry |
| Build | Maven (multi-module) |
| Container | Podman + podman-compose |

---

## Build Commands

### Build all services (skip tests, build images, restart compose)
```zsh
./build.sh
```

### Build all services + run tests, restart compose detached
```zsh
./build.sh -t -d
```

### Build a single service (no image)
```zsh
cd partner   # or policy, product
mvn clean package -DskipTests
```

### Build a single service with container image
```zsh
cd partner
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.additional-tags=latest
```

### Start the full stack (images must be built first)
```zsh
podman compose up
```

---

## Test Commands

### Run all unit tests for one service
```zsh
cd partner   # or policy, product
mvn test
```

### Run integration tests (Testcontainers – requires Docker/Podman)
```zsh
cd partner
mvn verify -Pintegration
```

### Run all tests across all modules (from project root)
```zsh
mvn test
```

### Run a specific test class
```zsh
cd partner
mvn test -Dtest=PersonApplicationServiceTest
```

---

## Service URLs (local dev)

| Service | URL | Swagger |
|---|---|---|
| Partner Service | http://localhost:9080 | http://localhost:9080/swagger-ui |
| Product Service | http://localhost:9081 | http://localhost:9081/swagger-ui |
| Policy Service | http://localhost:9082 | http://localhost:9082/swagger-ui |

---

## Project Rules (Non-Negotiable)

### Language Policy (ADR-005)

> This rule must be enforced strictly. AI assistants must follow it without exception.

| Layer | Language | Examples |
|---|---|---|
| **Code** (classes, methods, fields, comments, logs, exceptions, DB columns, API paths) | **English** | `PolicyRepository`, `coverageStartDate`, `publishPolicyIssued()` |
| **UI** (Qute template labels, buttons, error messages, tooltips, placeholders) | **German** | `«Police ausstellen»`, `«Bitte Vorname eingeben»` |
| **Documentation** (`specs/`, `README.md`, `CLAUDE.md`, ODC YAML descriptions) | **English** | This file |
| **Architecture doc** (`specs/arc42.md`) | **German** | Navigation anchor for the German-speaking organisation |

**Rules for AI code generation:**
- Method names, variable names, field names, class names → **English**.
- Exception messages thrown by domain/application services → **English**.
- Strings in HTML Qute templates (labels, buttons, error feedback) → **German**.
- Kafka event type names → English PascalCase (`PolicyIssued`, not `PolicyAusgestellt`).
- ODC field `description` values → **English**.
- Log messages → **English**.

### Architecture Rules

1. **No shared databases.** Each domain has its own PostgreSQL instance. Cross-domain queries go through Kafka events or REST — never direct DB access.

2. **Kafka first.** All domain integration is asynchronous via Kafka. Direct service-to-service calls are forbidden except where listed below.

3. **REST only for synchronous exceptions.**
   - Claims → Policy: coverage check during FNOL
   - Any service → IAM: auth token validation
   Always use `SmallRye Fault Tolerance` with Circuit Breaker for REST calls.

4. **Every Kafka topic requires an ODC.** Store as `src/main/resources/contracts/{topic}.odcontract.yaml`. Breaking changes → new major version (e.g. `policy.v2.issued`).

5. **Hexagonal architecture inside each service.** Domain logic (`domain/model/`, `domain/service/`) must have **zero** framework dependencies (no `@Inject`, no JPA annotations).

6. **Outbox Pattern for Kafka publishing.** Never dual-write (DB + Kafka directly). Use the transactional outbox table + Debezium/CDC for at-least-once delivery.

---

## Domains

### Core Domains
- **Product Management** – product/coverage definitions → [`product/specs/business_spec.md`](product/specs/business_spec.md)
- **Policy Management** – contract lifecycle → [`policy/specs/business_spec.md`](policy/specs/business_spec.md)
- **Claims Management** – damage processing *(service not yet implemented)*

### Supporting Domains
- **Partner/Customer Management** – natural person registry → [`partner/specs/business_spec.md`](partner/specs/business_spec.md)
- **Billing & Collection** – invoicing and payouts *(not yet implemented)*
- **Sales & Distribution** – offers and broker channel *(not yet implemented)*

### Generic Domains
- **Document Management (DMS)** – REST-based document storage
- **IAM (Keycloak)** – authentication, roles: `UNDERWRITER`, `CLAIMS_AGENT`, `BROKER`, `ADMIN`

---

## Project Structure (per service)

```
{domain}/
├── specs/
│   └── business_spec.md        ← Domain business spec (English)
├── src/main/java/ch/css/{domain}/
│   ├── domain/
│   │   ├── model/              ← Aggregates, Entities, Value Objects (no framework)
│   │   ├── service/            ← Application Services
│   │   └── port/
│   │       ├── in/             ← Use Case Ports (Commands/Queries)
│   │       └── out/            ← RepositoryPort, EventPublisherPort
│   └── infrastructure/
│       ├── persistence/        ← JPA/Hibernate adapters
│       ├── messaging/          ← Kafka Producer/Consumer
│       ├── api/                ← REST Server/Client
│       └── web/                ← Qute templates + REST controllers
├── src/main/resources/
│   ├── templates/              ← Qute HTML templates (UI text in German)
│   └── contracts/              ← ODC YAML files
└── src/test/
    ├── domain/                 ← Unit tests (pure domain, no mocks of domain logic)
    └── integration/            ← @QuarkusIntegrationTest (real DB, real Kafka)
```

---

## Kafka Topic Naming Convention

```
{domain}.v{version}.{event-name}

Examples:
  policy.v1.issued
  policy.v1.cancelled
  claims.v1.opened
  claims.v1.settled
  partner.v1.created
  product.v1.defined
```

---

## Key Patterns

- **Aggregate-first:** All state changes go through Aggregates.
- **Eventual Consistency:** UIs must handle async; use optimistic updates or polling.
- **RBAC:** Use `@RolesAllowed` on Application Service methods, not in adapters.
- **Read models:** Materialize cross-domain data from consumed Kafka events; never query foreign DBs.
- **ODC quality checks:** Include SodaCL specs in every ODC (null checks, duplicate checks).

---

## Quality Goals (Priority Order)

1. **Autonomy** – teams deploy independently, zero coordination needed
2. **Data Sovereignty** – domain is sole owner/writer of its data
3. **Scalability** – domains scale independently (especially Policy, Claims)
4. **Resilience** – one domain failing must not cascade
5. **Auditability** – full event log, 7-year Kafka retention, 100% mutation coverage

---

## Key ADRs

| ADR | Decision |
|---|---|
| ADR-001 | Kafka is the only integration channel; no direct DB cross-access |
| ADR-002 | Every Kafka topic has an ODC; breaking changes = new topic version |
| ADR-003 | REST only for coverage check (Claims→Policy) and IAM auth |
| ADR-004 | Shared Nothing – no cross-domain DB joins; use read models from events |
| ADR-005 | Code in English; UI strings in German; technical docs in English |

---

## Testing

- **Unit tests** in `src/test/domain/` — test domain logic in isolation, no Quarkus, no DB
- **Integration tests** in `src/test/integration/` — use `@QuarkusIntegrationTest` with Testcontainers (real PostgreSQL, real Kafka)
- Do **not** mock the database or Kafka in integration tests

---

## Ubiquitous Language (Quick Reference)

| German (UI) | English (Code) | Context |
|---|---|---|
| Police | Policy | Insurance contract |
| Schaden / Schadenfall | Claim | Damage event |
| Prämie | Premium | Insurance fee |
| Selbstbehalt | Deductible | Customer's share |
| Deckung | Coverage | What is insured |
| Underwriter | Underwriter | Policy issuer |
| Sachbearbeiter | ClaimsAgent | Claims processor |
| Versicherungsnehmer | Policyholder | Customer holding the contract |
| Person | Person | Natural person registered in the system |
| Produkt | Product | Insurance product offering |

Full ubiquitous language per domain: see the `specs/business_spec.md` of each service.

---

## Definition of Done

- Code compiles (`mvn clean package -DskipTests`)
- Unit tests pass (`mvn test`)
- `specs/business_spec.md` updated if business logic changed
- ODC YAML updated if Kafka event schema changed
- `specs/arc42.md` updated if a new service or ADR was added
