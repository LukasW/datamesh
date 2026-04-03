# Business Specification – Policy Service

> **Version:** 1.0.0 · **Last updated:** 2026-03-15  
> **Owner:** Policy Management Team  
> **Service port:** 9082

---

## 1. Domain Overview

The **Policy Service** is the **system of record for insurance contracts** (Policen) in the Sachversicherung platform. It manages the complete lifecycle of a policy from draft creation through activation to cancellation, and owns all coverage definitions attached to a policy. The Policy Service is a core domain: its events (`PolicyIssued`, `PolicyCancelled`, `PolicyChanged`) are consumed by Billing, Claims, and Sales.

### Bounded Context

```
┌─────────────────────────────────────────────────────────────┐
│                     Policy Service                          │
│                                                             │
│  Policy (Aggregate Root)                                    │
│    └── Deckung[] (Coverage Entity)                          │
│                                                             │
│  Read Models (from Kafka):                                  │
│    PartnerSicht  ← person.v1.created / person.v1.updated    │
│    ProduktSicht  ← product.v1.defined / product.v1.updated  │
│                                                             │
│  Publishes: policy.v1.* events → Kafka                      │
│  Consumed by: Billing, Claims, Sales                        │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Core Responsibilities

| Responsibility | Description |
|---|---|
| **Policy lifecycle** | Manage the ENTWURF → AKTIV → GEKUENDIGT/ABGELAUFEN state machine |
| **Coverage management** | Add and remove coverage types (`Deckungstyp`) from a policy |
| **Policy numbering** | Generate and own unique policy numbers |
| **Partner & product read models** | Materialize local read models from Kafka to avoid cross-service queries |
| **Event publication** | Publish domain events to Kafka for downstream consumers |
| **Coverage query (REST)** | Serve synchronous coverage queries from the Claims Service (ADR-003) |
| **Premium calculation (gRPC, outbound)** | Request risk-adjusted premium from Product Service via gRPC before saving a policy (ADR-010) |
| **Audit trail** | Full audit log of all mutations via Hibernate Envers |

---

## 3. Domain Model

### 3.1 Aggregate Root: `Policy`

The `Policy` aggregate is the sole entry point for all contract state changes.

| Field | Type | Description |
|---|---|---|
| `policyId` | `UUID` | Surrogate key, system-generated |
| `policyNummer` | `String` | Human-readable policy number (e.g., `POL-00042`) |
| `partnerId` | `String` | Reference to the policyholder (from Partner domain) |
| `produktId` | `String` | Reference to the product definition (from Product domain) |
| `status` | `PolicyStatus` | Current lifecycle state |
| `versicherungsbeginn` | `LocalDate` | Effective start date (required) |
| `versicherungsende` | `LocalDate` | Expiry date (`null` = open-ended) |
| `praemie` | `BigDecimal` | Annual premium in CHF (must be > 0) |
| `selbstbehalt` | `BigDecimal` | Deductible in CHF (must be ≥ 0) |
| `deckungen` | `List<Deckung>` | Coverages attached to this policy |

### 3.2 Policy Lifecycle (`PolicyStatus`)

```
ENTWURF ──aktivieren()──► AKTIV ──kuendigen()──► GEKUENDIGT
                               └──(expiry date)──► ABGELAUFEN
```

| Status | Description | Allowed operations |
|---|---|---|
| `ENTWURF` | Draft – not yet in force | update, activate, delete |
| `AKTIV` | Active contract | update details, add/remove coverage, cancel |
| `GEKUENDIGT` | Cancelled | read only |
| `ABGELAUFEN` | Expired (end date passed) | read only |

**Business rules:**

- Only `ENTWURF` policies can be activated.
- Only `AKTIV` policies can be cancelled.
- `GEKUENDIGT` and `ABGELAUFEN` policies are immutable.
- `versicherungsbeginn` must not be after `versicherungsende`.
- `praemie` must be > 0; `selbstbehalt` must be ≥ 0.
- Each `Deckungstyp` may appear at most **once** per policy.

### 3.3 Entity: `Deckung` (Coverage)

A coverage defines what is insured within a policy and for which sum.

| Field | Type | Description |
|---|---|---|
| `deckungId` | `UUID` | Surrogate key |
| `policyId` | `UUID` | Owning policy |
| `deckungstyp` | `Deckungstyp` | Type of coverage (see below) |
| `versicherungssumme` | `BigDecimal` | Insured sum in CHF (must be > 0) |

### 3.4 Enum: `Deckungstyp` (Coverage Type)

| Value | Description |
|---|---|
| `HAFTPFLICHT` | Liability coverage |
| `KASKOSCHADEN` | Comprehensive vehicle damage (kasko) |
| `GLASBRUCH` | Glass breakage |
| `ELEMENTAR` | Natural hazards (storm, flood, avalanche) |
| `DIEBSTAHL` | Theft |
| `GEBAEUDE` | Building / real estate |
| `HAUSRAT` | Household contents |

### 3.5 Read Models

| Model | Source Events | Purpose |
|---|---|---|
| `PartnerSicht` | `person.v1.created`, `person.v1.updated` | Partner picker in UI; display partner name on policy list |
| `ProduktSicht` | `product.v1.defined`, `product.v1.updated`, `product.v1.deprecated` | Product dropdown in UI; display product name on policy |

These read models are stored in the Policy Service's own PostgreSQL database and are **never** queried from other services (ADR-001 compliance).

---

## 4. Application Service Use Cases

| Use Case | Method | Description |
|---|---|---|
| Create policy draft | `createPolicy(...)` | Creates a new policy in `ENTWURF` status; returns `policyId` |
| Activate policy | `aktivierePolicy(policyId)` | Transitions `ENTWURF → AKTIV`; publishes `PolicyIssued` |
| Cancel policy | `kuendigePolicy(policyId)` | Transitions `AKTIV → GEKUENDIGT`; publishes `PolicyCancelled` |
| Update policy details | `updatePolicyDetails(...)` | Updates premium, dates, product; publishes `PolicyChanged` |
| Add coverage | `addDeckung(...)` | Adds a coverage to the policy; enforces uniqueness per type; publishes `CoverageAdded` |
| Remove coverage | `removeDeckung(...)` | Removes a coverage; publishes `CoverageRemoved` |
| Find by ID | `findById(policyId)` | Returns policy or throws `PolicyNotFoundException` |
| Find by number | `findByPolicyNummer(...)` | Lookup by human-readable policy number |
| Search policies | `searchPolicen(...)` | Filter by number, partner, status |
| Search partners | `searchPartnerSichten(...)` | Partner-picker: name-search against local read model (max 20) |
| List active products | `getActiveProdukte()` | Product dropdown data from local read model |

---

## 5. REST API

Base path: `/api/policen`

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/policen` | Create policy draft |
| `GET` | `/api/policen?policyNummer=&partnerId=&status=` | Search policies |
| `GET` | `/api/policen/{id}` | Get policy by ID |
| `PUT` | `/api/policen/{id}` | Update policy details |
| `DELETE` | `/api/policen/{id}` | Delete draft policy |
| `POST` | `/api/policen/{id}/aktivieren` | Activate policy |
| `POST` | `/api/policen/{id}/kuendigen` | Cancel policy |
| `GET` | `/api/policen/{id}/deckungen` | List coverages |
| `POST` | `/api/policen/{id}/deckungen` | Add coverage |
| `DELETE` | `/api/policen/{id}/deckungen/{deckungId}` | Remove coverage |

---

## 6. Kafka Events Published

| Topic | Event Type | Trigger |
|---|---|---|
| `policy.v1.issued` | `PolicyIssued` | Policy activated (`ENTWURF → AKTIV`) |
| `policy.v1.cancelled` | `PolicyCancelled` | Policy cancelled (`AKTIV → GEKUENDIGT`) |
| `policy.v1.changed` | `PolicyChanged` | Policy details updated |
| `policy.v1.coverage-added` | `CoverageAdded` | Coverage added to a policy |
| `policy.v1.coverage-removed` | `CoverageRemoved` | Coverage removed from a policy |

ODC contracts: `src/main/resources/contracts/`

---

## 7. Kafka Events Consumed

| Topic | Event Type | Action |
|---|---|---|
| `person.v1.created` | `PersonCreated` | Upsert `PartnerSicht` read model |
| `person.v1.updated` | `PersonUpdated` | Update `PartnerSicht` read model |
| `product.v1.defined` | `ProductDefined` | Upsert `ProduktSicht` read model |
| `product.v1.updated` | `ProductUpdated` | Update `ProduktSicht` read model |
| `product.v1.deprecated` | `ProductDeprecated` | Mark `ProduktSicht` as inactive |

---

## 8. Synchronous Dependencies

### 8.1 Coverage Query (REST, ADR-003) – Inbound

The Policy Service exposes a **coverage-check endpoint** for synchronous queries from the Claims Service during FNOL (First Notice of Loss). This is the only inbound synchronous dependency and is permitted per ADR-003.

Claims Service queries: `GET /api/policen/{id}` → returns current coverage scope and deductible.

### 8.2 Premium Calculation (gRPC, ADR-010) – Outbound

The Policy Service calls the Product Service's gRPC endpoint to calculate the risk-adjusted premium before creating or updating a policy.

**Call flow:** Policy Service → `product-service:9181` → `PremiumCalculation.CalculatePremium`

**Input:** product ID, product line, policyholder age, postal code, selected coverage types.

**Output:** premium breakdown (base, risk surcharge, coverage surcharge, discount, total) and a calculation ID for audit.

**Fault tolerance:**
- `@CircuitBreaker(requestVolumeThreshold=4, failureRatio=0.5, delay=10s)`
- `@Timeout(2000ms)`
- `@Retry(maxRetries=2, delay=500ms)`

**Graceful degradation:** If the Product Service is unreachable, the policy creation/update is aborted with a user-facing error message:
> «Die Prämienberechnung ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.»

---

## 9. UI

The Qute-based web UI is served at `/policen/` and supports:

- Policy list with search filters (number, partner, status)
- Inline policy creation modal (partner picker + product picker)
- Policy detail/edit page: personal data form + coverage cards
- Partner picker widget (htmx live search against `PartnerSicht` read model, max 20 results, 300ms debounce)
- Coverage add/remove per policy

All UI labels, buttons, validation messages, and tooltips are in **German** per the project language policy.

---

## Read-Model Bootstrapping

The Policy Service materializes two local read models from upstream domains: **PartnerView** (from the Partner Service) and **ProductView** (from the Product Service). On a fresh deployment with an empty database, these read models must be populated before the policy UI can offer partner and product selection.

### Bootstrap Procedure

1. **Configure state topic consumers** with `auto.offset.reset=earliest`:
   - `person.v1.state` (compacted) — provides the full current state of every person, including all addresses. Each record is keyed by person UUID. Tombstone events (`deleted=true`) indicate removed persons.
   - `product.v1.state` (compacted) — provides the full current state of every product, including its status. Each record is keyed by product UUID. Null-value tombstones indicate hard-deleted products.

2. **Consume both state topics to completion.** Once the consumer has caught up to the end of each topic, the local PartnerView and ProductView tables contain a complete snapshot of the upstream domains.

3. **Switch to incremental event topics** for ongoing updates:

   | Read Model | Event Topics |
   |---|---|
   | PartnerView | `person.v1.created`, `person.v1.updated`, `person.v1.deleted` |
   | ProductView | `product.v1.defined`, `product.v1.updated`, `product.v1.deprecated` |

### Operational Notes

- On a **fresh deployment**, the Policy Service UI will show empty partner/product pickers until the state topics have been fully consumed. The bootstrap typically completes within seconds for catalogues of moderate size.
- On **redeployment with existing data**, the state topic consumers detect that the read model tables are already populated. Upsert semantics ensure that re-consuming state records is idempotent and does not cause duplicates.
- The state topics are the authoritative source for bootstrapping. The Policy Service never queries the Partner or Product REST APIs to populate its read models (ADR-001 compliance).

---

## 10. Technical Debt – Language Inconsistency

> ⚠️ The Policy Service domain model uses a mix of English and German identifiers. This is tracked as technical debt.

| Location | Issue | Target (English) |
|---|---|---|
| `Policy` fields | `policyNummer`, `produktId`, `versicherungsbeginn`, `versicherungsende`, `praemie`, `selbstbehalt`, `deckungen` | `policyNumber`, `productId`, `coverageStartDate`, `coverageEndDate`, `premium`, `deductible`, `coverages` |
| `Deckung` fields | `deckungId`, `deckungstyp`, `versicherungssumme` | `coverageId`, `coverageType`, `insuredAmount` |
| Class names | `Deckung`, `Deckungstyp`, `PartnerSicht`, `ProduktSicht` | `Coverage`, `CoverageType`, `PartnerView`, `ProductView` |
| Enum values | `ENTWURF`, `AKTIV`, `GEKUENDIGT`, `ABGELAUFEN` | `DRAFT`, `ACTIVE`, `CANCELLED`, `EXPIRED` |
| Enum values | `HAFTPFLICHT`, `KASKOSCHADEN`, `GLASBRUCH`, `ELEMENTAR`, `DIEBSTAHL`, `GEBAEUDE`, `HAUSRAT` | `LIABILITY`, `COMPREHENSIVE`, `GLASS_BREAKAGE`, `NATURAL_HAZARD`, `THEFT`, `BUILDING`, `HOUSEHOLD_CONTENTS` |
| Method names | `aktivieren()`, `kuendigen()`, `aktivierePolicy()`, `kuendigePolicy()` | `activate()`, `cancel()`, `activatePolicy()`, `cancelPolicy()` |
| Event publisher methods | `publishDeckungHinzugefuegt`, `publishDeckungEntfernt` | `publishCoverageAdded`, `publishCoverageRemoved` |

Remediation requires a coordinated refactoring, a migration of the ODC contracts, and alignment with all downstream consumers.

---

## 11. Ubiquitous Language

| UI Term (German) | Code Term (English) | Definition |
| :--- | :--- | :--- |
| Police / Versicherungsvertrag | Policy | An insurance contract between the insurer and the policyholder |
| Policennummer | policyNumber | Human-readable unique identifier for a policy (e.g. `POL-00042`) |
| Versicherungsnehmer | policyholder | The person who holds (and pays for) the insurance contract |
| Versicherte Person | insuredPerson | The person covered by the policy (may differ from policyholder) |
| Entwurf | DRAFT | Initial state of a policy before it is activated |
| Aktiv | ACTIVE | A policy that is currently in force |
| Gekündigt | CANCELLED | A policy that has been terminated before expiry |
| Abgelaufen | EXPIRED | A policy that has passed its end date |
| Prämie | premium | The annual fee paid by the policyholder |
| Selbstbehalt | deductible | The portion of a claim the policyholder bears themselves |
| Deckung | coverage | A specific risk covered within a policy (e.g. liability, theft) |
| Deckungstyp | coverageType | The classification of coverage (e.g. HAFTPFLICHT, HAUSRAT) |
| Versicherungssumme | insuredAmount | The maximum amount the insurer pays for a coverage |
| Versicherungsbeginn | coverageStartDate | The date from which the policy is in force |
| Versicherungsende | coverageEndDate | The date on which the policy expires (`null` = open-ended) |
| Haftpflicht | LIABILITY | Coverage for damage caused to third parties |
| Hausrat | HOUSEHOLD_CONTENTS | Coverage for personal belongings inside a home |
| Gebäude | BUILDING | Coverage for the physical structure of a building |
| Elementar | NATURAL_HAZARD | Coverage for storm, flood, avalanche, and similar events |
| Kaskoschaden | COMPREHENSIVE | Comprehensive vehicle damage coverage |
| Glasbruch | GLASS_BREAKAGE | Coverage for broken glass |
| Diebstahl | THEFT | Coverage for stolen property |
| Underwriter | underwriter | The insurance professional who assesses risk and issues policies |
| Police ausstellen | issuePolicy | The act of activating a policy draft (DRAFT → ACTIVE) |

