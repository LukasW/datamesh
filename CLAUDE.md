# CLAUDE.md – Sachversicherung Datamesh Platform

## Project Overview

A modern property insurance (Sachversicherung) platform built on **Domain-Driven Design**, **Hexagonal Architecture**, **Clean Code**, **Self-Contained Systems (SCS)**, and **Data Mesh** principles. Each domain is an autonomous, independently deployable Quarkus service.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Runtime | Java 21+ with Virtual Threads |
| Framework | Quarkus |
| Async Integration | Apache Kafka (SmallRye Reactive Messaging) |
| Sync Integration | REST (exceptions only) |
| Persistence | PostgreSQL (one DB per domain, JPA/Panache) |
| UI | Quarkus Qute + Bootstrap + htmx (server-side, no SPA) |
| Auth | Keycloak (OIDC/OAuth2), Quarkus OIDC extension |
| Data Contracts | Open Data Contract (ODC) YAML |
| Schemas | Avro/Protobuf via Schema Registry |

## Domains

### Core Domains
- **Product Management** – product/coverage definitions (`ProductDefined`)
- **Policy Management** – contract lifecycle (`PolicyIssued`, `PolicyCancelled`, `PolicyChanged`)
- **Claims Management** – damage processing (`ClaimOpened`, `ClaimSettled`, `ClaimRejected`)

### Supporting Domains
- **Partner/Customer Management** – (`PartnerCreated`, `PartnerUpdated`)
- **Billing & Collection** – invoicing and payouts
- **Sales & Distribution** – offers and broker channel (`OfferAccepted`)

### Generic Domains
- **Document Management (DMS)** – REST-based document storage
- **IAM (Keycloak)** – authentication, roles: `UNDERWRITER`, `CLAIMS_AGENT`, `BROKER`, `ADMIN`

## Architecture Rules (Non-Negotiable)

1. **No shared databases.** Each domain has its own PostgreSQL instance. Cross-domain queries go through Kafka events or REST — never direct DB access.

2. **Kafka first.** All domain integration is asynchronous via Kafka. Direct service-to-service calls are forbidden except where noted below.

3. **REST only for synchronous exceptions.** Currently only:
   - Claims → Policy (coverage check during FNOL)
   - Any service → IAM (auth token validation)
   Use `SmallRye Fault Tolerance` with Circuit Breaker for all REST calls.

4. **Every Kafka topic needs an ODC.** Store as `src/main/resources/contracts/{topic}.odcontract.yaml`. Breaking changes require a new major version (e.g. `policy.v2.issued`).

5. **Hexagonal architecture inside each service.** Domain logic must have zero framework dependencies.

6. **Outbox Pattern for Kafka publishing.** Never dual-write (DB + Kafka directly). Use the transactional outbox table + Debezium/CDC for at-least-once delivery.

## Project Structure (per service)

```
{domain}-service/
├── src/main/java/com/insurance/{domain}/
│   ├── domain/
│   │   ├── model/          ← Aggregates, Entities, Value Objects (no framework)
│   │   ├── service/        ← Application Services
│   │   └── port/
│   │       ├── in/         ← Use Case Ports (Commands/Queries)
│   │       └── out/        ← RepositoryPort, EventPublisherPort
│   └── infrastructure/
│       ├── persistence/    ← JPA/Panache adapters
│       ├── messaging/      ← Kafka Producer/Consumer
│       ├── api/            ← REST Server/Client
│       └── web/            ← Qute templates + REST controllers
├── src/main/resources/
│   ├── templates/          ← Qute HTML templates
│   └── contracts/          ← ODC YAML files
└── src/test/
    ├── domain/             ← Unit tests (pure domain, no mocks of domain logic)
    └── integration/        ← @QuarkusIntegrationTest (real DB, real Kafka via Testcontainers)
```

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

## Key Patterns to Follow

- **Aggregate-first:** All state changes go through Aggregates (`apply(DomainEvent)`).
- **Eventual Consistency:** UIs must handle async; use optimistic updates or polling where needed.
- **RBAC:** Use `@RolesAllowed` on Application Service methods, not in adapters.
- **No direct DB queries across domain boundaries** — materialize read models from consumed events.
- **ODC quality checks:** Include SodaCL quality specs in every ODC (null checks, duplicate checks).

## Quality Goals (Priority Order)

1. **Autonomy** – teams deploy independently, zero coordination needed
2. **Data Sovereignty** – domain is sole owner/writer of its data
3. **Scalability** – domains scale independently (especially Policy, Claims)
4. **Resilience** – one domain failing must not cascade
5. **Auditability** – full event log, 7-year Kafka retention, 100% mutation coverage

## Key ADRs

| ADR | Decision |
|-----|----------|
| ADR-001 | Kafka is the only integration channel; no direct DB cross-access |
| ADR-002 | Every Kafka topic has an ODC; breaking changes = new topic version |
| ADR-003 | REST only for coverage check (Claims→Policy) and IAM auth |
| ADR-004 | Shared Nothing – no cross-domain DB joins; use read models from events |
| ADR-005 | Code should be in english |

## Testing

- **Unit tests** in `src/test/domain/` — test domain logic in isolation, no Quarkus, no DB
- **Integration tests** in `src/test/integration/` — use `@QuarkusIntegrationTest` with Testcontainers (real PostgreSQL, real Kafka)
- Do not mock the database or Kafka in integration tests

## Domain Language (Ubiquitous Language)

| German | English | Context |
|--------|---------|---------|
| Police | Policy | Insurance contract |
| Schaden / Schadenfall | Claim | Damage event |
| Prämie | Premium | Insurance fee |
| Selbstbehalt | Deductible | Customer's share |
| Deckung | Coverage | What is insured |
| Underwriter | Underwriter | Policy issuer |
| Sachbearbeiter | Claims Agent | Claims processor |
| Versicherungsnehmer | Policyholder | Customer |

## Definition of Done

- Code kompiliert
- Tests laufen durch
- Dokumentation ist angepasst (spec/* und CLAUDE.md)
