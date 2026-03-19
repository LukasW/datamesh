# Architectural Review – CSS Sachversicherung Data Mesh Platform

**Date:** 2026-03-19
**Reviewer:** Critical Software Architect
**Scope:** Full codebase review (partner, product, policy, infra)
**Methodology:** Data Mesh, Clean Architecture, Clean Code, DDD (Strategic + Tactical)

---

## Executive Summary

The project demonstrates a solid conceptual foundation: hexagonal architecture is consistently applied, bounded contexts are well-defined, and the Data Mesh governance layer (ODC, DataHub, Schema Registry) goes beyond what most projects attempt. However, several **critical inconsistencies** undermine the architecture's reliability guarantees, and a number of tactical DDD and Clean Code violations weaken the domain model.

The findings below are grouped by severity. Fix the **Critical** issues before going to production. The others are technical debt that will accumulate cost over time.

---

## 1. Critical Issues

### 1.1 Inconsistent Kafka Publishing Strategy – No Outbox in Policy Service

**Problem:** Partner and Product use the Transactional Outbox + Debezium CDC pattern (correct). The Policy service publishes **directly** to Kafka from within the application service – without an outbox table. This is a textbook **dual-write anti-pattern**.

```
// PolicyApplicationService → PolicyKafkaAdapter → Emitter<GenericRecord>
// DB commit and Kafka send are NOT in the same transaction
```

If the Kafka broker is unavailable, or if the JVM crashes after the DB commit but before the Kafka send completes, the event is **silently lost**. There is no compensation, no retry, and no idempotency guarantee.

**Impact:** Lost policy events break the `PartnerView`/`ProductView` read models in any downstream consumer that starts later. ADR-002 (event at-least-once delivery) is violated.

**Fix:** Apply the same Outbox + Debezium pattern used by Partner and Product. Alternatively, implement a persistent retry mechanism with a separate `policy_outbox` table and publish the Avro record via Debezium using the Avro converter (instead of the String converter).

---

### 1.2 ODC Contracts Claim `format: AVRO` But Events Are Published as JSON

**Problem:** The ODC contracts for Partner and Product declare:

```yaml
spec:
  format: AVRO
  schemaRegistry: http://schema-registry:8081
```

In reality, Debezium is configured with `"value.converter": "org.apache.kafka.connect.storage.StringConverter"`. The actual wire format is **plain JSON string**, not Avro. The Schema Registry is **not used** for Partner or Product events.

**Impact:** Downstream consumers reading the ODC contract and expecting Avro deserialization will fail. The governance checker (`lint-contracts.py`) is validating a contract that does not match reality. This invalidates the entire contract-first governance promise.

**Fix:** Either:
- Switch Debezium to use `io.confluent.connect.avro.AvroConverter` with the schema registry (preferred – aligns with Policy), or
- Correct the ODC contracts to `format: JSON` and remove the `schemaRegistry` reference.

Inconsistency between contract and wire format is more dangerous than either choice individually.

---

### 1.3 Avro Schemas Defined as Inline Java Code (No `.avsc` Files)

**Problem:** The Policy service builds all Avro schemas inline using `SchemaBuilder`:

```java
private static final Schema POLICY_ISSUED_SCHEMA = SchemaBuilder.record("PolicyIssued")
    .namespace("ch.css.policy.events")
    .fields()
    .requiredString("eventId")
    // ...
    .endRecord();
```

There are no `.avsc` schema files. The Schema Registry receives schemas generated at runtime from Java code that is not under schema-level version control.

**Impact:**
- Schema evolution cannot be reviewed in a pull request as a schema diff
- The ODC contract cannot reference a stable schema file
- Breaking changes are invisible until runtime
- Schema backward-compatibility becomes a runtime surprise, not a build-time check

**Fix:** Define schemas as `.avsc` files in `src/main/avro/`. Use the Avro Maven plugin to generate Java classes. Reference the schema file from the ODC contract. Register schemas in CI/CD before deployment.

---

### 1.4 Database Column Names Violate ADR-005 (German Identifiers in Code)

**Problem:** Flyway migrations contain German database column names across all services:

| Service | German Column Names |
|---------|-------------------|
| Partner | `vorname`, `geburtsdatum`, `ahv_nummer`, `adress_typ`, `strasse`, `hausnummer`, `plz`, `gueltig_von`, `gueltig_bis` |
| Policy | `policy_nummer`, `produkt_id`, `versicherungsbeginn`, `versicherungsende`, `praemie`, `selbstbehalt`, `deckung`, `deckungstyp`, `versicherungssumme` |

ADR-005 states clearly: **"DB columns → English"**. These are code artifacts, not UI strings. The JPA entity mappings (`@Column(name = "vorname")`) propagate this into the Java infrastructure layer.

**Impact:** The migration history (V6 for partner, V4 for policy) shows enum values were already renamed from German to English once. Column name migration is more invasive and will require careful Flyway migrations, but leaving it creates a permanent violation of the project's own ADR.

**Fix:** Add Flyway migrations to rename all German column names to English equivalents. Example:
```sql
-- V8__Rename_German_Columns_To_English.sql
ALTER TABLE person RENAME COLUMN vorname TO first_name;
ALTER TABLE person RENAME COLUMN geburtsdatum TO date_of_birth;
-- ...
```

---

### 1.5 REST API Paths Are in German – ADR-005 Violation

**Problem:** REST paths are technical identifiers, not UI strings. They are code artifacts and must follow ADR-005 (English):

```
/api/personen      → should be /api/persons
/api/policen       → should be /api/policies
/api/produkte      → should be /api/products
/api/personen/{id}/adressen  → should be /api/persons/{id}/addresses
/api/policen/{id}/deckungen  → should be /api/policies/{id}/coverages
/api/policen/{id}/aktivieren → should be /api/policies/{id}/activate
/api/policen/{id}/kuendigen  → should be /api/policies/{id}/cancel
```

**Impact:** External consumers (claims service, broker API, integration tests) couple to German URL paths. Any future migration will be a breaking API change.

**Fix:** Rename all REST paths to English. Expose German labels only in the Qute templates (which is already correct).

---

### 1.6 No Integration Tests Exist

**Problem:** The architecture plan mentions integration tests using `@QuarkusIntegrationTest` with Testcontainers. These do not exist. The test coverage consists only of unit tests with mocked repositories.

**Impact:** The following are untested end-to-end:
- Flyway migration correctness
- JPA entity mappings (including the German column names)
- Kafka consumer behavior under duplicate delivery
- Debezium outbox → Kafka round trip
- Avro serialization/deserialization against Schema Registry
- Policy read model materialization from partner/product events

The system cannot be called production-ready without integration tests.

**Fix:** Create `src/test/integration/` in each service. Implement at minimum:
- One Flyway migration smoke test per service
- One round-trip test: domain write → outbox → Kafka consumer reads → read model updated
- One aggregate state machine test through the full stack

---

### 1.7 No Dead Letter Queue (DLQ) Strategy

**Problem:** The Kafka consumers (`PartnerEventConsumer`, `ProductEventConsumer`) have no error handling for processing failures (poison pills, schema mismatch, null pointer). SmallRye Reactive Messaging will stop the consumer channel on unhandled exceptions.

**Impact:** A single malformed event will halt the policy service's ability to materialize read models from partner/product events permanently (until the service restarts and the poison pill is skipped manually).

**Fix:** Add `@Blocking` + fault tolerance handling. Configure a DLQ topic per consumer:
```properties
mp.messaging.incoming.partner-person-created.failure-strategy=dead-letter-queue
mp.messaging.incoming.partner-person-created.dead-letter-queue.topic=partner-person-created-dlq
```

---

## 2. Architecture Issues

### 2.1 Command/Query Separation Missing in Application Services

**Problem:** `PolicyApplicationService` mixes command methods (mutations) with query methods:

```java
// Commands – correct
activatePolicy(), cancelPolicy(), addCoverage()

// Queries mixed in – incorrect
searchPolicies()
getPartnerViewsMap()
searchPartnerViews(nameQuery)
getActiveProducts()
getProductViewsMap()
```

This violates the Single Responsibility Principle and makes the service impossible to scale differently for read vs. write workloads. The same applies to `PersonApplicationService` and `ProductApplicationService`.

**Fix:** Separate into `PolicyCommandService` (mutations + event publishing) and `PolicyQueryService` (read-only, can bypass ports if needed). This is the first step toward CQRS if the system scales.

---

### 2.2 Read Model Bootstrapping Not Addressed

**Problem:** The `PartnerView` and `ProductView` tables in the policy database are populated from Kafka events. If the policy service is deployed fresh (or its DB is wiped), it must replay all historical partner/product events from Kafka to rebuild these views.

- Kafka default retention may not guarantee all events are available
- The `person.v1.state` compacted topic exists (correct!) but no consumer uses it for bootstrapping
- The `product.v1.*` events do not appear to have a compacted state topic

**Impact:** A fresh policy service deployment with an empty DB and a Kafka cluster whose early messages have been deleted will have an incomplete `PartnerView` (no historical partners) and silently produce wrong results.

**Fix:**
- Add a `product.v1.state` compacted topic alongside the existing product event topics
- Implement a startup bootstrapping procedure in policy service: read the compacted state topics first, then switch to the event topics (using consumer group offsets)
- Document the bootstrapping procedure in the service's `business_spec.md`

---

### 2.3 Platform-DB Is a Shared Database – Data Mesh Anti-Pattern

**Problem:** The `platform-db` is a single PostgreSQL instance shared across all analytics consumers. All domain events land in the same database, managed by a single Python consumer process and a single dbt project.

In Data Mesh, the analytics layer should be composed of **autonomous data products**, each with their own storage, SLA, and ownership. A shared central database is the analytics equivalent of the shared operational database that Data Mesh was designed to replace.

**Impact:**
- The platform team becomes a bottleneck for every domain's analytics
- Schema changes in one domain's events can break the shared dbt models
- No domain can independently deploy analytics changes

**Fix:** Evolve toward domain-owned analytical data products:
1. Each domain service writes its events to domain-scoped schemas within platform-db as an interim step
2. Long-term: provide a self-serve data platform (e.g., separate schemas per domain, or separate databases accessed via data lake federation)
3. Separate dbt projects per domain, orchestrated by Airflow but independently deployable

---

### 2.4 Keycloak Not Present in docker-compose

**Problem:** CLAUDE.md lists Keycloak as a core component with roles `UNDERWRITER`, `CLAIMS_AGENT`, `BROKER`, `ADMIN`. ADR-003 and the architecture require Keycloak for all auth. No Keycloak service is present in `docker-compose.yaml`.

**Impact:**
- `@RolesAllowed` annotations (mentioned in CLAUDE.md) cannot be tested
- The platform has no authentication in the development environment
- Any security testing is impossible locally

**Fix:** Add a Keycloak service to `docker-compose.yaml` with a pre-configured realm (`css`), clients for each service, and the four roles. Add an initialization job that imports the realm JSON.

---

### 2.5 Single Kafka Broker – No Fault Tolerance

**Problem:** The Kafka cluster is a single broker (`KRaft` mode, single node). All topics have `replication.factor=1` (implied by single broker).

**Impact:** Any Kafka restart causes full message delivery interruption. Data loss is possible if the broker disk fails. This is not acceptable for a system with 7-year retention requirements (ADR).

**Fix:** For local development, a single broker is acceptable. But the docker-compose configuration should document that production requires a minimum 3-broker cluster. Add environment-specific compose files (`docker-compose.prod.yaml`) that reflect production topology.

---

### 2.6 dbt Runs as One-Shot at Startup – Incorrect Lifecycle

**Problem:** `dbt` is a Docker service that runs once at container startup and exits. This means transformations only run at deployment time, not on schedule.

**Impact:** The `platform-db` analytics tables will contain stale data between deployments. Any data pipeline requiring freshness < 24 hours cannot be met by this approach.

**Fix:** Remove `dbt` from `docker-compose.yaml` as a standalone service. Trigger dbt runs exclusively from Airflow DAGs (`DbtOperator` or `BashOperator`). This is the correct architectural separation: Airflow orchestrates, dbt transforms.

---

## 3. DDD Tactical Issues

### 3.1 `OutboxEvent` Is an Infrastructure Concern in the Domain Layer

**Problem:** `OutboxEvent` is placed in `domain/model/` alongside true domain objects (`Person`, `Policy`, `Product`). However, `OutboxEvent` is a technical infrastructure artifact for achieving at-least-once delivery – it has no business meaning.

```
domain/model/
├── Person.java          ← Domain object ✓
├── Address.java         ← Domain object ✓
├── SocialSecurityNumber.java ← Value Object ✓
└── OutboxEvent.java     ← Infrastructure concern ✗
```

**Fix:** Move `OutboxEvent` to `infrastructure/messaging/outbox/` or `infrastructure/persistence/outbox/`. The domain layer should be unaware of outbox mechanics.

---

### 3.2 `PersonEventPayloadBuilder` Couples Domain to JSON Serialization

**Problem:** `PersonEventPayloadBuilder` (placed in `domain/service/`) builds JSON event payloads and knows topic names like `"person.v1.created"`. This introduces serialization concerns (Jackson/JSON) and infrastructure routing concerns (topic names) into the domain layer.

**Fix:** Move `PersonEventPayloadBuilder` to `infrastructure/messaging/`. The domain service should emit a strongly-typed domain event (e.g., `PersonCreatedEvent`) and the infrastructure layer translates it to a wire format.

---

### 3.3 Aggregate Identity Handling Is Inconsistent

**Problem:** `Policy` uses `String` for `partnerId` and `productId` (cross-aggregate references), which is correct DDD practice. However, `Person` uses `UUID` for its own identity but `String` is used for all cross-domain references in Policy. There is no type-safe wrapper (e.g., `PartnerId`, `ProductId`) to prevent accidental ID mix-up.

**Fix:** Introduce typed ID value objects:
```java
public record PartnerId(UUID value) {}
public record ProductId(UUID value) {}
public record PolicyId(UUID value) {}
```

This prevents bugs like `new Policy(productId, partnerId, ...)` where arguments are accidentally transposed.

---

### 3.4 Policy Number Generation Strategy Is Undefined

**Problem:** The policy number format `POL-YYYY-NNNN` is documented, but the generation strategy is not visible. If it relies on a database sequence, concurrent activations may produce gaps or collisions under load. If it is application-generated, there is a race condition.

**Fix:** Document and implement explicitly. A database sequence with year reset is appropriate. Example:
```sql
CREATE SEQUENCE policy_number_seq START 1 INCREMENT 1;
```
Wrapped in a domain service that formats the result.

---

### 3.5 No Domain Events as First-Class Objects

**Problem:** The domain model does not define domain events (e.g., `PolicyIssued`, `PersonCreated`) as explicit Java types. Instead, string literals like `"PolicyIssued"`, `"PersonCreated"` are used as event type identifiers, and payload construction is delegated to builder utilities.

**Impact:** Event types are not discoverable via IDE tooling, cannot be tested independently, and have no compile-time safety.

**Fix:** Define domain events as value objects or records:
```java
public record PolicyIssuedEvent(
    PolicyId policyId,
    String policyNumber,
    PartnerId partnerId,
    LocalDate coverageStartDate,
    BigDecimal premium,
    Instant occurredAt
) {}
```

The application service emits these; the infrastructure layer serializes them to JSON or Avro.

---

## 4. Clean Code Issues

### 4.1 Docker Image Names Are Personal – Not Project-Scoped

**Problem:** Container images are named with a personal registry prefix:
```yaml
image: lukasweibel/person-service:latest
image: lukasweibel/product-service:latest
image: lukasweibel/policy-service:latest
```

**Fix:** Use an organization-scoped registry path:
```yaml
image: css/partner-service:latest
image: css/product-service:latest
image: css/policy-service:latest
```

Parameterize the registry URL via `.env` for CI/CD flexibility.

---

### 4.2 `latest` Tag in Production Compose Is Dangerous

**Problem:** All domain services reference the `latest` tag. In a compose file that could be used for staging or production, `latest` prevents reproducible deployments.

**Fix:** Use explicit version tags (e.g., `1.0.0` or a Git SHA). `latest` may be acceptable in dev-only compose configurations if clearly labeled.

---

### 4.3 Search Endpoints Have No Pagination

**Problem:** All search methods (`searchPersons`, `listAllProducts`, `searchPolicies`) return `List<T>` without pagination. In production:
- `listAllProducts()` returns all products
- `searchPersons()` could return thousands of results

**Fix:** Add `PageRequest` / `PageResult<T>` wrappers. Expose `?page=0&size=20` query parameters on all collection endpoints.

---

### 4.4 Maven Compiler Target Is Java 25 (Not Yet Released)

**Problem:** The parent `pom.xml` sets the compiler to Java 25. As of early 2026, Java 25 is not a GA release. Java 21 is the current LTS.

**Fix:** Set `<java.version>21</java.version>` in the parent POM. Use virtual threads via `quarkus.virtual-threads.enabled=true` (Quarkus handles this transparently with Java 21).

---

### 4.5 No Observability Infrastructure

**Problem:** There is no Prometheus, Grafana, or OpenTelemetry configuration. Quarkus provides Micrometer metrics out-of-the-box. The current setup relies only on AKHQ for Kafka monitoring.

**Fix:** Add to `docker-compose.yaml`:
- Prometheus (scrapes Quarkus `/q/metrics` endpoints)
- Grafana (pre-built dashboards for Kafka consumer lag, JVM metrics, DB connection pool)

Add to each service `pom.xml`:
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

---

### 4.6 No `.env` Validation or Startup Fail-Fast

**Problem:** Environment variables like `KAFKA_BOOTSTRAP_SERVERS`, `DATABASE_PASSWORD`, `SCHEMA_REGISTRY_URL` use default fallbacks (`${VAR:default}`). In production, a misconfigured environment (empty `SCHEMA_REGISTRY_URL`) silently falls back to `http://localhost:8081`, which will fail at runtime rather than at startup.

**Fix:** For production profiles, use `${VAR}` (without default) so that Quarkus fails fast on startup with a clear error if a required variable is missing. Reserve defaults only for dev profile.

---

## 5. Data Mesh Principles

### 5.1 Data Product Discoverability Is Incomplete

**Problem:** DataHub is integrated for metadata, and the Data Product Portal exists. However:
- The portal reads ODC contracts that are stored in service `src/main/resources/` (internal classpath resources), mounted as Docker volumes at runtime
- There is no CI/CD step that publishes contracts to a central contract registry on merge
- ODC versions are not tied to service versions

**Fix:** Treat ODC contracts as independently versioned artifacts:
1. Publish contracts to a dedicated Git repository or artifact registry on merge
2. Add a CI step that validates schema backward-compatibility before merge (not just at startup)
3. Display contract version alongside service version in DataHub

---

### 5.2 No Self-Serve Data Access for Downstream Consumers

**Problem:** Data Mesh requires that domain teams can independently discover and access data products without coordination. Currently:
- Consumers must know Kafka broker addresses and consumer group IDs manually
- There is no service account provisioning for new consumers
- DataHub shows metadata but provides no access provisioning

**Fix:** Define a `dataProduct` section in each ODC contract that specifies access patterns:
```yaml
dataProduct:
  accessPatterns:
    - type: kafka-consumer
      topic: person.v1.created
      sampleConsumerConfig: contracts/samples/person-consumer.properties
```

---

### 5.3 `person.v1.state` Compacted Topic Has No Documented Bootstrap Process

**Problem:** The compacted state topic `person.v1.state` exists (documented in ODC) but its consumer strategy is not defined. It is unclear:
- When a new service subscribes, does it read state first, then switch to event topics?
- How does the policy service bootstrap `PartnerView` from this topic?
- What is the ordering guarantee between the state topic and the event topics?

**Fix:** Document the bootstrap protocol in `partner/specs/business_spec.md`:
1. New consumer reads `person.v1.state` from `earliest` until caught up
2. Consumer switches to `person.v1.created` / `person.v1.updated` event topics
3. Offset watermark from step 1 is used to skip already-applied events in step 2

---

## 6. Strategic DDD Issues

### 6.1 Claims Domain Is Absent – Architecture Decisions Cannot Be Validated

**Problem:** Claims is listed as a core domain, and ADR-003 defines a specific synchronous REST call (Claims → Policy for coverage check). Without the Claims service, this ADR cannot be validated. The `SmallRye Fault Tolerance` circuit breaker (required by CLAUDE.md) is untested.

**Fix:** Create a minimal Claims bounded context stub:
- Define `claims/specs/business_spec.md`
- Add `claims/` module to Maven parent POM
- Implement `CoverageCheckPort` (REST client to Policy service) with `@CircuitBreaker`
- Define at least `claims.v1.opened` and `claims.v1.settled` Kafka topics with ODC contracts

Without this, one of the four declared bounded contexts is missing from the codebase.

---

### 6.2 No Anti-Corruption Layer Between Policy and Partner/Product Events

**Problem:** The `PartnerEventConsumer` directly maps Debezium JSON payloads to `PartnerView` using string field parsing (Jackson). If the partner event schema changes (even in a backward-compatible way), the consumer silently breaks or silently ignores new fields.

**Fix:** Introduce an explicit Anti-Corruption Layer (ACL) in the policy infrastructure:
```java
// infrastructure/messaging/acl/PartnerEventTranslator.java
public class PartnerEventTranslator {
    public PartnerView translate(PersonCreatedPayload payload) { ... }
}
```

This isolates the translation concern, makes it testable, and clearly marks the boundary where partner domain language is converted to policy domain language.

---

### 6.3 `billing` and `sales` Domains Are Not Even Stubbed

**Problem:** The CLAUDE.md mentions Billing & Collection and Sales & Distribution as planned domains. Neither has a stub, a spec, or an ODC contract.

**Fix:** Even if not implemented, add:
- `billing/specs/business_spec.md` and `sales/specs/business_spec.md` with placeholder specs
- Entries in `specs/arc42.md` with rationale for why they are deferred
- Kafka topic names reserved in the naming convention (e.g., `billing.v1.*`, `sales.v1.*`)

This prevents other domains from accidentally consuming names that will be used by future domains.

---

## 7. Summary Table

| # | Finding | Severity | Effort | Priority |
|---|---------|----------|--------|----------|
| 1.1 | No Outbox in Policy – dual-write risk | Critical | Medium | P0 |
| 1.2 | ODC claims Avro but wire format is JSON | Critical | Medium | P0 |
| 1.3 | Avro schemas inline in Java code | Critical | Medium | P0 |
| 1.4 | German column names violate ADR-005 | Critical | Medium | P0 |
| 1.5 | German REST paths violate ADR-005 | Critical | Low | P0 |
| 1.6 | No integration tests exist | Critical | High | P0 |
| 1.7 | No Dead Letter Queue strategy | Critical | Low | P1 |
| 2.1 | Command/Query not separated | Architecture | Medium | P1 |
| 2.2 | Read model bootstrapping undefined | Architecture | Medium | P1 |
| 2.3 | Platform-DB is a shared DB anti-pattern | Architecture | High | P2 |
| 2.4 | Keycloak missing from docker-compose | Architecture | Low | P1 |
| 2.5 | Single Kafka broker – no fault tolerance | Architecture | Low | P1 |
| 2.6 | dbt runs at startup (wrong lifecycle) | Architecture | Low | P1 |
| 3.1 | OutboxEvent in domain layer | DDD Tactical | Low | P2 |
| 3.2 | EventPayloadBuilder couples domain to JSON | DDD Tactical | Low | P2 |
| 3.3 | No typed ID value objects | DDD Tactical | Low | P2 |
| 3.4 | Policy number generation undefined | DDD Tactical | Low | P2 |
| 3.5 | No domain events as first-class types | DDD Tactical | Medium | P2 |
| 4.1 | Personal Docker image names | Clean Code | Low | P2 |
| 4.2 | `latest` tag in compose | Clean Code | Low | P2 |
| 4.3 | No pagination on search endpoints | Clean Code | Low | P1 |
| 4.4 | Java 25 compiler (unreleased) | Clean Code | Low | P1 |
| 4.5 | No observability (metrics/tracing) | Clean Code | Medium | P2 |
| 4.6 | No env var fail-fast in prod | Clean Code | Low | P1 |
| 5.1 | Data product discoverability incomplete | Data Mesh | Medium | P2 |
| 5.2 | No self-serve access provisioning | Data Mesh | High | P3 |
| 5.3 | State topic bootstrap process undocumented | Data Mesh | Low | P2 |
| 6.1 | Claims domain absent | Strategic DDD | High | P1 |
| 6.2 | No ACL between Policy and Partner/Product | Strategic DDD | Low | P2 |
| 6.3 | Billing/Sales domains not even stubbed | Strategic DDD | Low | P3 |

---

## 8. What Is Done Well

To provide a balanced picture – the following are genuinely well-executed and should be preserved:

- **Hexagonal architecture** is applied consistently. The domain layer has zero framework dependencies in all three services.
- **Transactional Outbox via Debezium** in Partner and Product is a correct, battle-tested implementation of at-least-once delivery.
- **SocialSecurityNumber EAN-13 validation** in the domain model is an excellent example of value object invariant enforcement.
- **Temporal address management** (auto-clipping overlapping addresses) in the Person aggregate is sophisticated and correct.
- **ODC contracts with SodaCL quality checks and SLA** go further than most data mesh implementations in practice.
- **Compacted `person.v1.state` topic** shows awareness of the consumer bootstrapping problem (even if the solution is not yet fully implemented).
- **KRaft Kafka** (no ZooKeeper) is the correct modern choice.
- **Flyway + Hibernate Envers** combination provides both schema versioning and full audit history.
- **Policy read models** (`PartnerView`, `ProductView`) correctly implement cross-domain data sharing without violating data sovereignty.
- **DataHub integration** for metadata lineage is above-average investment in governance.
- **ADR-005 language policy** is a well-reasoned, clearly documented decision (even if not fully enforced in the DB schema and REST paths).

---

*This report was generated by static code analysis and architecture review. It does not replace runtime profiling, security testing, or load testing.*
