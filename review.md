# Architecture Review: Sachversicherung Datamesh Platform

**Datum:** 2026-04-03
**Scope:** DDD, Hexagonal Architecture, Data Mesh, Tech Stack, Integrations

---

## Teil I -- IST-Analyse

---

### 1. Projektueberblick

Die Plattform ist eine Sachversicherungs-Applikation, aufgebaut als Mono-Repo mit 7 eigenstaendigen Quarkus-Services. Sie kombiniert Domain-Driven Design, Hexagonal Architecture und Data Mesh. Die analytische Pipeline fuehrt von operativen Datenbanken ueber Kafka und Iceberg bis hin zu Self-Service BI mit Superset.

---

### 2. Domain Services

#### 2.1 Bounded Contexts und Aggregate Roots

| Domain | Aggregate Root | Entities / Value Objects | Status |
| --- | --- | --- | --- |
| **Partner** | `Person` | `Address`, `PersonId`, `InsuredNumber`, `SocialSecurityNumber`, `Gender` | Vollstaendig |
| **Product** | `Product` | `ProductId`, `ProductLine`, `PremiumCalculation`, `RiskProfile` | Vollstaendig |
| **Policy** | `Policy` | `Coverage`, `PolicyId`, `CoverageId`, `CoverageType`, `PremiumCalculationResult` | Vollstaendig |
| **Claims** | `Claim` | `ClaimId`, `ClaimStatus`, `PolicySnapshot`, `PartnerSearchView` | Vollstaendig |
| **Billing** | `Invoice` | `InvoiceLineItem`, `DunningCase`, `InvoiceId`, `BillingCycle`, `PolicyholderView` | Vollstaendig |
| **HR System** | `Employee`, `OrganizationUnit` | Vereinfachtes Modell (OData-Stub) | Vereinfacht |
| **HR Integration** | -- | Apache Camel Sync-Bridge (OData -> Kafka) | Spezialservice |
| **Sales** | -- | -- | Nur Specs, keine Implementation |

#### 2.2 Package-Struktur pro Domain

```text
ch.yuno.{domain}/
  domain/
    model/        Aggregate Roots, Entities, Value Objects
    service/      Command- und Query-Services
    port/out/     Outbound-Port-Interfaces
  infrastructure/
    persistence/  JPA-Entities, Repository-Adapter
    messaging/    Kafka Consumer/Producer, ACL-Translator
    messaging/outbox/  OutboxEvent-Klasse
    messaging/acl/     Anti-Corruption Layer
    web/          REST-Adapter, Qute-UI-Controller
    grpc/         gRPC-Adapter (nur Policy + Product)
    vault/        Vault-Encryption-Adapter (nur Partner + Claims)
    dev/          DevDataInitializer (Test-Seed)
    persistence/audit/  Hibernate Envers (Partner + Policy)
```

**Besonderheit:** Ein `application/` Package existiert in keinem der Services. Die Use-Case-Orchestrierung liegt direkt in `domain/service/`.

#### 2.3 Domain Models (Pure Java)

Die Aggregate Roots sind framework-frei implementiert:

- **`Person.java`** -- Verwaltet Adressen (Add/Update/Remove), Overlap-Erkennung, InsuredNumber-Zuweisung. Validierung im Konstruktor.
- **`Policy.java`** -- Status-Lifecycle (DRAFT -> ACTIVE -> CANCELLED/EXPIRED), Coverage-Management, Detail-Updates mit Invarianten.
- **`Claim.java`** -- Status-Lifecycle (OPEN -> IN_REVIEW -> SETTLED/REJECTED), FNOL-Pattern.
- **`Invoice.java`** -- Billing-Cycle, Payment-Recording, Dunning-Eskalation.
- **`Product.java`** -- Produktdefinition, Status-Management.

Keine der Model-Klassen hat Framework-Annotations (`@Entity`, `@Inject`, etc.).

#### 2.4 Domain Services (Command/Query)

Jede Domain hat Command- und Query-Services in `domain/service/`:

| Domain | Command Service | Query Service |
| --- | --- | --- |
| Partner | `PersonCommandService` | `PersonQueryService` |
| Policy | `PolicyCommandService` | `PolicyQueryService` |
| Product | `ProductCommandService` | `ProductQueryService` |
| Claims | `ClaimApplicationService` (kombiniert) | -- |
| Billing | `InvoiceCommandService` | `InvoiceQueryService` |

Alle Command Services verwenden:

- `@ApplicationScoped` (CDI Scope)
- `@Inject` (Dependency Injection)
- `@Transactional` (JTA)
- Imports aus `infrastructure.messaging` (`OutboxEvent`, `*EventPayloadBuilder`)

Claims zusaetzlich: `@RolesAllowed` (Security Annotation).

#### 2.5 Ports und Adapter

**Outbound Ports (domain/port/out/):**

| Port-Interface | Implementierung | Domain |
| --- | --- | --- |
| `PersonRepository` | `PersonJpaAdapter` | Partner |
| `PolicyRepository` | `PolicyJpaAdapter` | Policy |
| `ClaimRepository` | `ClaimJpaAdapter` | Claims |
| `InvoiceRepository` | `InvoiceJpaAdapter` | Billing |
| `ProductRepository` | `ProductJpaAdapter` | Product |
| `OutboxRepository` | `OutboxJpaAdapter` | Alle 5 Domains |
| `PiiEncryptor` | `VaultPiiEncryptor` | Partner |
| `PiiDecryptor` | `VaultPiiDecryptor` | Claims |
| `PremiumCalculationPort` | `PremiumCalculationGrpcAdapter` | Policy |
| `InsuredNumberGenerator` | `InsuredNumberSequenceAdapter` | Partner |
| `PolicyNumberPort` | `PolicyNumberGenerator` | Policy |
| `PartnerViewRepository` | `PartnerViewJpaAdapter` | Policy |
| `ProductViewRepository` | `ProductViewJpaAdapter` | Policy |
| `PolicySnapshotRepository` | `PolicySnapshotJpaAdapter` | Claims |
| `PartnerSearchViewRepository` | `PartnerSearchViewJpaAdapter` | Claims |
| `PolicyholderViewRepository` | `PolicyholderViewJpaAdapter` | Billing |
| `DunningCaseRepository` | `DunningCaseJpaAdapter` | Billing |

**Inbound Ports:** Nicht als eigene Interfaces definiert. Die REST-Adapter und Kafka-Consumer rufen die Domain Services direkt auf.

#### 2.6 Domain Events

Events sind als Records/Klassen in `domain/model/events/` modelliert:

- **Partner:** `PersonCreatedEvent`, `PersonUpdatedEvent`, `PersonDeletedEvent`, `AddressAddedEvent`, `AddressUpdatedEvent`
- **Policy:** `PolicyIssuedEvent`, `PolicyCancelledEvent`, `PolicyChangedEvent`, `CoverageAddedEvent`, `CoverageRemovedEvent`
- **Product:** `ProductDefinedEvent`, `ProductUpdatedEvent`, `ProductDeprecatedEvent`
- **Claims/Billing:** Events werden direkt via PayloadBuilder serialisiert (keine eigene Event-Klasse).

---

### 3. Kommunikationsmuster

#### 3.1 Asynchron -- Kafka (Transactional Outbox)

Alle 5 Haupt-Domains publizieren Events ueber die Transactional Outbox:

1. Domain Service schreibt `OutboxEvent` in die Outbox-Tabelle (gleiche TX wie Geschaeftslogik)
2. Debezium CDC liest WAL-Changes der Outbox-Tabelle
3. Debezium EventRouter routet nach `topic`-Feld ins richtige Kafka-Topic

**Kafka-Topics:**

| Domain | Topics | Compaction |
| --- | --- | --- |
| Partner | `person.v1.created`, `person.v1.updated`, `person.v1.deleted`, `person.v1.address-added`, `person.v1.address-updated`, `person.v1.state` | `state` = compact |
| Product | `product.v1.defined`, `product.v1.updated`, `product.v1.deprecated`, `product.v1.state` | `state` = compact |
| Policy | `policy.v1.issued`, `policy.v1.cancelled`, `policy.v1.changed`, `policy.v1.coverage-added`, `policy.v1.coverage-removed` | -- |
| Claims | `claims.v1.opened`, `claims.v1.settled` | -- |
| Billing | `billing.v1.invoice-created`, `billing.v1.payment-received`, `billing.v1.dunning-initiated`, `billing.v1.payout-triggered` | -- |
| HR | `hr.v1.employee.state`, `hr.v1.employee.changed`, `hr.v1.org-unit.state`, `hr.v1.org-unit.changed` | `state` = compact |

**Dead Letter Queues:** `partner-person-created-dlq`, `product-defined-dlq`, `billing-policy-issued-dlq`, `billing-claims-settled-dlq`, `hr-integration-dlq`, etc.

#### 3.2 Event-Carried State Transfer (Read Models)

| Consumer | Source Topic | Lokales Read Model | Zweck |
| --- | --- | --- | --- |
| Policy | `person.v1.created/updated` | `PartnerView` | Partner-Name in Policy-UI |
| Policy | `product.v1.defined/updated/deprecated` | `ProductView` | Produkt-Auswahl |
| Claims | `person.v1.state` | `PartnerSearchView` | Partner-Suche bei FNOL |
| Claims | `policy.v1.issued` | `PolicySnapshot` | Coverage-Pruefung |
| Billing | `person.v1.state` | `PolicyholderView` | Rechnungsadresse |
| Billing | `policy.v1.issued/cancelled` | -- | Invoice-Erstellung/Storno |
| Billing | `claims.v1.settled` | -- | Payout-Trigger |
| Partner | `policy.v1.issued` | -- | InsuredNumber-Zuweisung |

**Anti-Corruption Layer (ACL):**

- `policy/infrastructure/messaging/acl/PartnerEventTranslator` -- Uebersetzt Partner-Events in Policy-Domain-Sprache
- `policy/infrastructure/messaging/acl/ProductEventTranslator` -- Uebersetzt Product-Events
- `billing/infrastructure/messaging/acl/PolicyEventTranslator` -- Uebersetzt Policy-Events fuer Billing
- `claims/infrastructure/messaging/acl/PersonStateEventTranslator` -- Uebersetzt Person-State fuer Claims

#### 3.3 Synchron -- gRPC (ADR-010)

- **Einziger synchroner Call:** Policy Service -> Product Service fuer Praemienberechnung
- **Proto:** `premium_calculation.proto` (in `policy/src/main/proto/` und `product/src/main/proto/`)
- **Service:** `PremiumCalculation.CalculatePremium(Request) -> Response`
- **Ports:** Product gRPC-Server auf 9181, Policy gRPC-Client
- **Fault Tolerance:** `PremiumCalculationGrpcAdapter` mit SmallRye `@CircuitBreaker`, `@Timeout(2s)`, `@Retry(2x, 500ms delay)`

---

### 4. Persistence

#### 4.1 Datenbanken

Jede Domain hat eine eigene PostgreSQL-17-Instanz:

| Domain | DB Name | Port | WAL Logical | Flyway |
| --- | --- | --- | --- | --- |
| Partner | `partner_db` | 5432 | Ja | Ja |
| Product | `product_db` | 5433 | Ja | Ja |
| Policy | `policy_db` | 5434 | Ja | Ja |
| Claims | `claims_db` | 5435 | Ja | Ja |
| Billing | `billing_db` | 5436 | Ja | Ja |
| HR System | `hr_db` | 5437 | Nein | Ja |

**Audit:** Partner und Policy nutzen Hibernate Envers mit `*_aud`-Tabellen und Custom Revision Entities.

#### 4.2 Outbox-Tabelle (pro Domain)

Jede Domain hat eine `public.outbox`-Tabelle mit den Feldern: `id`, `aggregate_type`, `aggregate_id`, `event_type`, `topic`, `payload`. Debezium CDC liest diese via PostgreSQL WAL.

---

### 5. Data Contracts (ODC)

Jede Domain definiert Open Data Contracts in `src/main/resources/contracts/`:

| Domain | Contracts |
| --- | --- |
| Partner | `person.v1.created`, `person.v1.updated`, `person.v1.deleted`, `person.v1.address-added`, `person.v1.address-updated`, `person.v1.state`, `partner.v1.created`, `partner.v1.updated` |
| Product | `product.v1.defined`, `product.v1.updated`, `product.v1.deprecated`, `product.v1.state` |
| Policy | `policy.v1.issued`, `policy.v1.cancelled`, `policy.v1.changed`, `policy.v1.coverage-added`, `policy.v1.coverage-removed` |
| Claims | `claims.v1.opened`, `claims.v1.settled` |
| Billing | `billing.v1.invoice-created`, `billing.v1.payment-received`, `billing.v1.dunning-initiated`, `billing.v1.payout-triggered` |
| HR Integration | `hr.v1.employee.state`, `hr.v1.employee.changed`, `hr.v1.org-unit.state`, `hr.v1.org-unit.changed` |

**Contract-Inhalt (Beispiel `person.v1.state`):**

- `apiVersion: v2.2.2`, `kind: DataContract`
- Owner: Partner Management Team (`team-partner@yuno.ch`)
- SLA: Freshness 5m, Availability 99.9%, Quality Score 0.98
- Tags: `pii`, `gdpr-subject`
- Schema: Vollstaendige Felddefinitionen mit Typen, Enums, Beschreibungen
- Access Patterns: Kafka Consumer Config
- Quality: SodaCL Checks eingebettet

**Avro Schemas** sind im Parent POM referenziert (`avro.version: 1.12.1`, `confluent.version: 7.9.2`) und in `src/main/resources/contracts/schemas/` vorhanden. Die tatsaechliche Event-Serialisierung erfolgt jedoch in JSON ueber `StringConverter`.

---

### 6. Analytische Pipeline (Data Mesh Lakehouse)

#### 6.1 Architektur

```text
Kafka Topics (Operational Events)
    |
    v  Debezium Iceberg Sink Connector
Iceberg Bronze (*_raw.* tables)     -- Rohe Events als Parquet in MinIO
    |
    v  SQLMesh Silver Models
Iceberg Silver (*_silver.* tables)  -- Dedupliziert, typisiert, domain-owned
    |
    v  SQLMesh Gold Models
Iceberg Gold (*_gold.* tables)      -- Cross-Domain JOINs, business-ready
    |
    v  Trino (Federated SQL)
Apache Superset (Dashboards)
```

#### 6.2 Iceberg Sink Connectors

Pro Domain ein Connector in `infra/debezium/`:

| Connector | Topics Regex | Ziel-Tabelle |
| --- | --- | --- |
| `iceberg-sink-partner` | `person\.v1\..*` | `partner_raw.person_events` |
| `iceberg-sink-product` | `product\..*` | `product_raw.product_events` |
| `iceberg-sink-policy` | `policy\..*` | `policy_raw.policy_events` |
| `iceberg-sink-claims` | `claims\..*` | `claims_raw.claims_events` |
| `iceberg-sink-billing` | `billing\..*` | `billing_raw.billing_events` |
| `iceberg-sink-hr-employee` | `hr\.v1\.employee\..*` | `hr_raw.employee_events` |
| `iceberg-sink-hr-orgunit` | `hr\.v1\.org-unit\..*` | `hr_raw.org_unit_events` |

Config: Nessie Catalog, S3 via MinIO, Auto-Create + Schema-Evolution enabled, JSON Converter.

#### 6.3 SQLMesh Transformationen

**Config:** Trino Gateway, DuckDB State-DB, `@hourly` Cron, alle Models `kind FULL`.

**Silver Layer (Entity Tables):**

| Model | Source | Logik |
| --- | --- | --- |
| `partner_silver.partner` | `partner_raw.person_events` | ROW_NUMBER() nach Timestamp, neuester State |
| `partner_silver.address` | `partner_raw.person_events` | Adressen aus JSON-Array extrahiert |
| `policy_silver.policy` | `policy_raw.policy_events` | PolicyIssued/Cancelled/Changed, Status-Ableitung |
| `policy_silver.coverage` | `policy_raw.policy_events` | CoverageAdded/Removed Events |
| `claims_silver.claim` | `claims_raw.claims_events` | ClaimOpened/Settled Events |
| `billing_silver.invoice` | `billing_raw.billing_events` | InvoiceCreated/PaymentReceived Events |
| `product_silver.product` | `product_raw.product_events` | ProductDefined/Updated Events |
| `hr_silver.employee` | `hr_raw.employee_events` | Neuester Employee-State |
| `hr_silver.org_unit` | `hr_raw.org_unit_events` | Neuester OrgUnit-State |

**Gold Layer (Enriched / Cross-Domain):**

| Model | JOINs | Zweck |
| --- | --- | --- |
| `policy_gold.policy_detail` | policy + partner + product (INNER JOIN) | Policy angereichert mit Partner-/Produkt-Info |
| `policy_gold.portfolio_summary` | Aggregation ueber policy_detail | Portfolio-KPIs |
| `claims_gold.claim_detail` | claim + policy + partner + address | Claim-Vollansicht |
| `billing_gold.financial_summary` | Aggregation ueber invoices | Finanz-KPIs |
| `partner_gold.partner_decrypted` | partner + vault_decrypt UDF | PII-Entschluesselung |
| `analytics.management_kpi` | Cross-Domain Aggregation | Management-Dashboard |
| `analytics.org_hierarchy` | org_unit + employee (LEFT JOIN) | Org-Baum mit Mitarbeiterzahlen |

#### 6.4 Data Quality (Soda Core)

Checks in `infra/soda/checks/` pro Domain auf Raw- und Silver-Ebene:

| Domain | Raw Checks | Silver Checks |
| --- | --- | --- |
| Partner | Row count, Duplicate eventid, Not-null personid | Duplicate partner_id, InsuredNumber Regex (`VN-\w{8}`) |
| Product | Row count, Duplicate eventid | Row count, Duplicate product_id |
| Policy | Row count, Duplicate eventid, Not-null policyid | Duplicate policy_id, Valid status enum `[ACTIVE, CANCELLED]` |
| Billing | Row count, Duplicate eventid, Not-null invoiceid | Duplicate invoice_id, Not-null policy_id, Valid status enum |
| HR | Row count (employee + org_unit) | Email Regex, Employment status enum, Org hierarchy check |

**Soda Config:** Trino-Connection auf `iceberg` Catalog, User `soda`.

---

### 7. Infrastruktur-Plattform

#### 7.1 Plattform-Komponenten

| Komponente | Technologie | Version | Port | Zweck |
| --- | --- | --- | --- | --- |
| **Messaging** | Apache Kafka (KRaft) | 7.9.2 | 9092 | Event Streaming |
| **Schema Registry** | Confluent Schema Registry | 7.9.2 | 8081 | Schema-Verwaltung |
| **Kafka UI** | AKHQ | 0.25.1 | 8085 | Kafka Administration |
| **CDC** | Debezium Connect | Custom Build | 8083 | Outbox CDC + Iceberg Sink |
| **Object Storage** | MinIO | Latest | 9000/9001 | S3-kompatibel fuer Iceberg |
| **Iceberg Catalog** | Project Nessie | 0.107.4 | 19120 | Git-like Catalog |
| **Query Engine** | Trino | 474 | 8086 | Federated SQL |
| **Transformations** | SQLMesh | Custom Build | -- | Silver/Gold Modelle |
| **Data Quality** | Soda Core | Custom Build | -- | SodaCL Checks |
| **BI** | Apache Superset | Custom Build | 8088 | Self-Service Dashboards |
| **Metadata Catalog** | OpenMetadata | 1.7.2 | 8585 | Data Discovery |
| **IAM** | Keycloak | 26.5.2 | 8280 | OIDC/SSO |
| **Secrets** | HashiCorp Vault | 1.19 | 8200 | PII Encryption (Transit) |
| **Metrics** | Prometheus | Latest | 9090 | Metriken-Sammlung |
| **Dashboards** | Grafana | 12.4.2 | 3000 | Observability |
| **Tracing** | Jaeger | 2.16.0 | 16686 | Distributed Tracing |

#### 7.2 Security (Keycloak)

- **Realm:** `yuno`
- **Rollen:** `UNDERWRITER`, `CLAIMS_AGENT`, `BROKER`, `ADMIN`
- **Clients:** Pro Service ein OIDC-Client (`partner-service`, `policy-service`, etc.)
- **Features:** Brute-Force-Schutz, Standard-Flow + Direct-Access-Grants + Service-Accounts
- **Quarkus-Integration:** `quarkus-oidc` in allen Services, `@RolesAllowed` in Claims

#### 7.3 PII-Schutz (Vault + ADR-009)

- **Vault Transit Engine:** Verschluesselt `name`, `firstName`, `dateOfBirth`, `socialSecurityNumber` pro Person
- **Crypto-Shredding:** Bei Person-Loeschung wird der Vault-Key geloescht -> alle PII in Kafka und Iceberg permanent unlesbar
- **Trino Vault UDF:** Custom Plugin (`infra/trino/vault-udf/`) mit `vault_decrypt(person_id, ciphertext)` Funktion fuer analytische Queries
- **`insuredNumber`** ist explizit kein PII und bleibt im Klartext

#### 7.4 Observability

- Alle Quarkus-Services exportieren Prometheus-Metriken via `/q/metrics`
- OpenTelemetry Traces an Jaeger (OTLP Port 4317)
- Grafana Dashboard `datamesh-overview.json` provisioniert
- Prometheus scrapet alle 6 Domain-Services

#### 7.5 Deployment

**Docker Compose (Entwicklung):**

- Einzelne `docker-compose.yaml` (ca. 1400 Zeilen, ~46KB)
- `./build.sh` baut alle Images (Maven + Custom Dockerfiles)
- `./deploy.sh` startet den Stack mit optionalem Test-Data-Seeding
- Podman- und Docker-kompatibel

**Kubernetes (Staging/Prod):**

- KinD Cluster-Config in `infra/k8s/kind-config.yaml`
- Kustomize-basiert (`kustomization.yaml`)
- Manifests: `databases.yaml`, `messaging.yaml`, `lakehouse.yaml`, `security.yaml`, `governance.yaml`, `observability.yaml`, `transformation.yaml`
- Init-Jobs: Schema-Registry, Debezium-Connector, Seed-Data, Soda-Checks
- Ingress: NGINX Ingress Controller mit `*.localhost` Routing

**CI/CD:**

- `.github/workflows/contract-check.yml` -- ODC Contract Linting, Schema Backward Compatibility, Version Verification
- Kein Build/Test Workflow fuer die Services sichtbar

---

### 8. Build und Dependencies

#### 8.1 Maven-Struktur

- **Parent POM** (`pom.xml`): `ch.yuno:datamesh-parent:1.0.0-SNAPSHOT`
- **Modules:** `partner`, `product`, `policy`, `claims`, `billing`, `hr-system`, `hr-integration`
- **BOM:** Quarkus 3.34.2, Avro 1.12.1, Confluent 7.9.2
- **Java:** Release 25
- **Container:** `quarkus-container-image-podman`

#### 8.2 Service-spezifische Dependencies

| Service | Besondere Dependencies |
| --- | --- |
| Partner | `quarkus-hibernate-envers`, `quarkus-messaging-kafka` |
| Product | `quarkus-grpc` (Server), `quarkus-messaging-kafka` |
| Policy | `quarkus-grpc` (Client), `quarkus-smallrye-fault-tolerance`, `quarkus-hibernate-envers`, `quarkus-messaging-kafka` |
| Claims | `quarkus-messaging-kafka`, `quarkus-test-security`, Playwright 1.44.0 (Test) |
| Billing | `quarkus-vertx-http`, `quarkus-messaging-kafka` |
| HR System | Minimal (Stub-Service mit OData-API) |
| HR Integration | Apache Camel Quarkus BOM (`camel-quarkus-http`, `camel-quarkus-kafka`, `camel-quarkus-timer`) |

#### 8.3 Service-Ports

| Service | HTTP | gRPC |
| --- | --- | --- |
| Partner | 9080 | -- |
| Product | 9081 | 9181 |
| Policy | 9082 | -- |
| Claims | 9083 | -- |
| Billing | 9084 | -- |
| HR System | 9085 | -- |
| HR Integration | 9086 | -- |

---

### 9. Testing

| Kategorie | Anzahl Dateien | Domains |
| --- | --- | --- |
| Unit Tests (Domain Model) | ~12 | Partner, Policy, Product, Billing, Claims |
| Unit Tests (Domain Service) | ~5 | Partner, Policy, Product, Billing, Claims |
| Integration Tests | ~9 | Partner, Policy, Product |
| Playwright UI Tests | ~3 (+2 Page Objects) | Claims |
| ACL Translator Tests | ~2 | Policy |
| HR Integration Tests | ~3 | HR Integration |
| **Total** | **~41** | |

**Test-Infrastruktur:**

- Quarkus `@QuarkusTest` mit eingebetteter DB
- Mockito fuer Domain-Service-Tests
- REST Assured fuer REST-Adapter-Tests
- Playwright fuer Claims-UI-Tests
- `MockPiiEncryptor` fuer Partner-Tests ohne Vault
- Flyway-Migration-Tests vorhanden (Partner, Policy, Product)

---

## Teil II -- Bewertung

---

### 10. DDD und Hexagonal Architecture

**Rating-Skala:** Excellent / Good / Needs Improvement / Critical

#### 10.1 Domain Models -- Excellent

Die Aggregate Roots sind vorbildlich implementiert:

- Pure Java ohne jede Framework-Annotation
- Business-Logik direkt im Aggregate (`Person.addAddress()`, `Policy.activate()`, `Invoice.recordPayment()`)
- Validierung in Konstruktoren und Business Methods
- Saubere Invarianten-Durchsetzung (z.B. nur DRAFT-Policies aktivierbar)
- Value Objects konsequent eingesetzt

#### 10.2 Bounded Contexts -- Good

- Klare fachliche Trennung der 5 Haupt-Domains
- Jede Domain mit eigener Datenbank, eigenem Messaging, eigener UI
- Ubiquitous Language konsistent (EN Code, DE UI)

Schwaechen:

- `Partner` als Bounded-Context-Name vs. `Person` als Code-Realitaet fuehrt zu Verwirrung
- `PageRequest`/`PageResult` in 3 Domains dupliziert (kein Shared Kernel)
- Read Models (`PartnerView`, `ProductView`, etc.) haben keine einheitliche Abstraktion

#### 10.3 Hexagonal Isolation -- Critical

**Alle Domain Command/Query Services verletzen die Hexagonal-Regel.** Die CLAUDE.md definiert:

> *"Die domain-Packages duerfen keine Framework-Abhaengigkeiten haben."*

Befund in allen 5 Domains (`PersonCommandService`, `PolicyCommandService`, `ClaimApplicationService`, `InvoiceCommandService`, etc.):

```java
// In domain/service/ -- sollte pure Java sein
import ch.yuno.partner.infrastructure.messaging.PersonEventPayloadBuilder;  // Infrastructure!
import ch.yuno.partner.infrastructure.messaging.outbox.OutboxEvent;         // Infrastructure!
import jakarta.enterprise.context.ApplicationScoped;                        // Framework!
import jakarta.inject.Inject;                                               // Framework!
import jakarta.transaction.Transactional;                                   // Framework!
```

Die Services sind de facto Application Services, die im falschen Package liegen. Die `application/`-Schicht aus der CLAUDE.md-Spezifikation existiert nicht.

**Empfohlene Korrektur:**

```text
domain/service/       Reine Business-Logik ohne Framework (z.B. PersonDomainService)
application/          Use Cases mit @ApplicationScoped, @Transactional, Outbox-Logik
infrastructure/       Adapter (bleibt wie ist)
```

#### 10.4 Port/Adapter Pattern -- Needs Improvement

- Outbound Ports korrekt als Interfaces in `domain/port/out/`
- Inbound Ports fehlen (`domain/port/in/` existiert nicht)
- REST-Adapter und Kafka-Consumer rufen Domain Services direkt auf, ohne Use-Case-Interface

#### 10.5 Kommunikationsmuster -- Excellent

- Transactional Outbox konsistent in allen Domains
- Anti-Corruption Layer in Policy, Claims, Billing
- Event-Carried State Transfer fuer Read Models
- gRPC mit Circuit Breaker, Timeout, Retry korrekt implementiert
- Keine Cross-Domain DB-Zugriffe, keine geteilte Datenbank

---

### 11. Data Mesh Prinzipien

#### 11.1 Domain Ownership -- Good

Staerken:

- Jede Domain besitzt ihre PostgreSQL-DB
- Kafka-Topics nach Domain benannt
- ODC Contracts leben bei der Domain
- Debezium Outbox-Connector pro Domain

Schwaechen:

- **Iceberg Sink Connectors** zentral in `infra/debezium/`, nicht bei der Domain
- **SQLMesh Transformationen** vollstaendig zentral in `infra/sqlmesh/`
- **Soda Quality Checks** zentral in `infra/soda/checks/`
- Das widerspricht dem Prinzip "The team that owns the domain owns the data product"

#### 11.2 Data as a Product -- Good

Staerken:

- ODC Contracts vollstaendig (SLA, Owner, Tags, Schema, Access Patterns, Quality)
- Event-Carried State Transfer korrekt implementiert
- Compacted Topics fuer State-Events

Schwaechen:

- Contracts decken nur Kafka-Topics ab, nicht die Iceberg Silver/Gold Tables
- Kein Data Product Manifest fuer die analytischen Tabellen
- Contract-Check Workflow existiert, aber kein automatisiertes Contract Testing gegen Live-Events

#### 11.3 Self-Serve Data Platform -- Good

Beeindruckend umfassende Plattform mit 16+ Infrastruktur-Komponenten. Alles von Messaging ueber Lakehouse bis BI und Observability ist abgedeckt.

Schwaeche: Kein Self-Service-Provisioning fuer neue Domains. Jede neue Domain erfordert manuelle Aenderungen in 5+ zentralen Config-Dateien (docker-compose, Debezium, SQLMesh, Soda, Superset).

#### 11.4 Federated Computational Governance -- Needs Improvement

Vorhanden:

- Soda Quality Checks
- ODC Contracts mit SLAs
- OpenMetadata fuer PII-Tagging
- Keycloak RBAC

Fehlend:

- Kein automatisiertes Governance Gate vor Deployment
- Kein Data Quality Score Dashboard
- Keine Schema Evolution Policy
- Kein Data Lineage Tracking in OpenMetadata (SQLMesh hat Lineage, aber sie fliesst nicht weiter)

---

### 12. Tech Stack -- Good

- **Java 25, Quarkus 3.34.2** -- Cutting Edge, aktuell
- **Kafka KRaft 7.9.2** -- Modern, kein Zookeeper
- **Postgres 17** -- Aktuell, WAL Logical fuer CDC
- **Iceberg + Nessie** -- Modernes Lakehouse mit Git-like Versionierung
- **SQLMesh** -- Gute Wahl fuer inkrementelle Modelle

**Problem:** Avro ist im POM deklariert, aber Events werden als JSON serialisiert. Inkonsistenz zwischen Dokumentation und Realitaet.

---

### 13. Data Pipeline -- Good

Staerken:

- Klare Medallion Architecture (Bronze/Silver/Gold)
- SQLMesh Models mit sauberer Deduplication
- Gold Models mit sinnvollen Cross-Domain JOINs
- Vault UDF fuer PII-Decryption in Trino

Schwaechen:

- Alle SQLMesh Models sind `kind FULL` (kein Incremental Processing)
- `@hourly` Cron fuer alle Models (kein differenziertes SLA)
- `policy_gold.policy_detail` verwendet INNER JOIN (Policies ohne Partner/Product fallen raus)
- Keine Freshness Checks in Soda

---

### 14. Security & Privacy -- Good

Staerken:

- Vault Transit Engine fuer PII
- Crypto-Shredding bei Person-Loeschung
- Keycloak OIDC + RBAC
- Trino Vault UDF fuer analytische PII-Entschluesselung

Schwaechen:

- Superset nutzt DB-Auth statt Keycloak OIDC (Dev-Gap)
- DB-Passwoerter via Env-Vars, nicht via Vault
- AKHQ JWT-Secret mit Default-Wert

---

### 15. Testing -- Needs Improvement

Staerken:

- Domain-Model-Tests vorhanden
- Integration Tests fuer Partner, Policy, Product
- Playwright UI Tests fuer Claims
- ACL Translator Tests

Schwaechen:

- Billing, Claims, HR haben keine Integration Tests
- Kein Contract Testing (Pact) zwischen Services
- Kein Consumer-Driven Contract Test fuer Kafka Events
- Kein E2E Test ueber die komplette Event-Kette
- `DataContractVerificationTest` (CLAUDE.md) existiert nicht

---

### 16. Build & Deployment -- Needs Improvement

- Mono-Repo mit Parent POM erschwert unabhaengiges Deployment
- Kein Build/Test CI/CD-Workflow (nur Contract-Check)
- Monolithische `docker-compose.yaml` (~46KB)
- K8s-Manifests vorhanden aber keine Helm Charts

---

## Teil III -- Priorisierte Verbesserungen

---

### P0 -- Kritisch

1. **Hexagonal Isolation reparieren:** Domain Services aus `domain/service/` in `application/` verschieben. Infrastructure-Imports (`OutboxEvent`, `*EventPayloadBuilder`) durch Outbound-Port-Interfaces ersetzen (z.B. `EventPublisher` in `domain/port/out/`).

2. **Application Layer einfuehren:** `application/` Package mit Use-Case-Klassen (`@ApplicationScoped`, `@Transactional`). Domain Services bleiben pure Java.

### P1 -- Hoch

3. **Domain-Owned Data Products:** SQLMesh Models, Soda Checks und Iceberg Sink Configs in die jeweilige Domain verschieben (`partner/data-product/`, `policy/data-product/`).

4. **Schema Consistency:** Entscheidung Avro vs. JSON treffen. Entweder Avro konsequent einsetzen oder Dependencies entfernen.

5. **CI/CD Pipeline:** Build/Test Workflow fuer die Services ergaenzen. Contract-Check existiert bereits.

### P2 -- Mittel

6. **Inbound Ports:** `domain/port/in/` mit Use-Case-Interfaces (z.B. `CreatePersonUseCase`, `ActivatePolicyUseCase`).

7. **Incremental SQLMesh Models:** Silver Models auf `INCREMENTAL_BY_TIME_RANGE` umstellen.

8. **Contract Testing:** Consumer-Driven Contract Tests fuer Kafka-Events einfuehren.

9. **Policy Number Generation:** `ThreadLocalRandom` ist nicht kollisionssicher bei Horizontal Scaling. DB-Sequence nutzen (wie bei `InsuredNumber`).

10. **Gold Layer JOINs:** `policy_gold.policy_detail` auf LEFT JOIN umstellen, damit Policies ohne Partner/Product nicht verloren gehen.

### P3 -- Niedrig

11. **docker-compose aufteilen:** Core-Infra / Domain-Services / Analytics-Stack trennen.

12. **Helm Charts fuer K8s:** Parametrisierbare Deployments.

13. **Data Lineage in OpenMetadata:** SQLMesh-Lineage automatisiert exportieren.

14. **Superset auf Keycloak OIDC** umstellen.

15. **Soda Freshness Checks** und Cross-Domain Referential Integrity Checks ergaenzen.

---

## Architektur-Diagramm (Ist-Zustand)

```text
                    +------------------+
                    |    Keycloak      |
                    |   (OIDC/SSO)    |
                    +--------+---------+
                             |
     +-----------+-----------+-----------+-----------+-----------+
     |           |           |           |           |           |
+----v----+ +----v----+ +----v----+ +----v----+ +----v----+ +---v------+
| Partner | | Product | | Policy  | | Claims  | | Billing | |HR-System |
| :9080   | | :9081   | | :9082   | | :9083   | | :9084   | | + Integ. |
+-+---+---+ +-+---+---+ +-+---+---+ +-+---+---+ +-+---+---+ +-+-------+
  |   |       |   |       |   |       |   |       |   |       |
  |  [DB]     |  [DB]     |  [DB]     |  [DB]     |  [DB]     |  [DB]
  |           |    gRPC    |           |           |           |
  |           |<-----------+           |           |           |
  |           |  :9181                 |           |           |
  +------+----+------+-------+--------+------+----+-----------+
         |           |       |               |
         v           v       v               v
   +-----+------------------------------------------+
   |              Apache Kafka (KRaft)               |
   |  person.v1.* | product.v1.* | policy.v1.*      |
   |  claims.v1.* | billing.v1.* | hr.v1.*          |
   +----+-----------+------+------------------------+
        |           |      |
        v           v      v
   +----+---+  +----+--+  ++--------+
   |Debezium|  |Iceberg |  |Schema  |
   |Outbox  |  |Sink    |  |Registry|
   +--------+  +----+---+  +--------+
                    |
              +-----v-------+
              | MinIO (S3)  |
              | + Nessie    |
              | + Iceberg   |
              +------+------+
                     |
              +------v------+
              |    Trino    |
              |   :8086     |
              +------+------+
                     |
         +-----------+-----------+
         |           |           |
   +-----v---+ +----v----+ +----v--------+
   | SQLMesh | |  Soda   | |  Superset   |
   |         | |         | |   :8088     |
   +---------+ +---------+ +-------------+
```

---

## Fazit

Die Plattform zeigt ein **hohes Architektur-Ambitionsniveau** und setzt viele moderne Patterns korrekt um. Die groesste Schwaeche ist die **fehlende Hexagonal-Isolation** der Domain Services -- ein systematischer Fehler, der sich durch alle 5 Haupt-Domains zieht, aber strukturell einfach zu beheben ist.

Das Data Mesh ist **technisch gut aufgestellt** (Lakehouse, Quality Checks, Contracts), aber organisatorisch noch **zentralisiert** (zentrale SQLMesh/Soda Configs). Der Uebergang zu echtem Domain-Owned Data Product erfordert primaer ein Umstrukturieren der Config-Dateien, nicht der Technologie.

**Top 3 Quick Wins:**

1. Domain Services in Application Layer verschieben
2. Avro vs. JSON Entscheidung bereinigen
3. SQLMesh Models auf Incremental umstellen
