# Business Specification – Partner Service

> **Version:** 1.0.0 · **Last updated:** 2026-03-15  
> **Owner:** Partner/Customer Management Team  
> **Service port:** 9080

---

## 1. Domain Overview

The **Partner Service** is the **system of record for natural persons** in the Sachversicherung platform. It manages the complete lifecycle of a person as an insurance partner (policyholder, insured person, or contact). The domain is authoritative for identity, personal data, and address history. All other services that need partner data consume it via Kafka read models — they never write to or query this service's database directly.

### Bounded Context

```
┌─────────────────────────────────────────────────────────────┐
│                     Partner Service                         │
│                                                             │
│  Person (Aggregate Root)                                    │
│    ├── SocialSecurityNumber (Value Object)                  │
│    └── Address[] (Entity, temporal)                         │
│                                                             │
│  Writes: outbox table (same TX as business data)            │
│    └── Debezium CDC → Kafka topics person.v1.*              │
│  Consumed by: Policy, Claims, Billing, Sales                │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Core Responsibilities

| Responsibility | Description |
|---|---|
| **Person lifecycle** | Create, update, and soft-delete natural persons |
| **Identity validation** | Validate and ensure uniqueness of AHV numbers (Swiss social security, EAN-13) |
| **Temporal address management** | Maintain address history per person with non-overlapping validity periods |
| **Event publication** | Publish domain events to Kafka so downstream services can materialize their own read models |
| **Audit trail** | Full audit log of all mutations via Hibernate Envers |

---

## 3. Domain Model

### 3.1 Aggregate Root: `Person`

The `Person` aggregate is the only entry point for all state changes. It enforces all business invariants.

| Field | Type | Description |
|---|---|---|
| `personId` | `UUID` | Surrogate key, system-generated |
| `name` | `String` | Family name (required) |
| `vorname` | `String` | Given name (required) |
| `geschlecht` | `Geschlecht` | Gender enum: `MAENNLICH`, `WEIBLICH`, `DIVERS` |
| `geburtsdatum` | `LocalDate` | Date of birth (required) |
| `ahvNummer` | `AhvNummer` | Swiss social security number (optional, unique per system) |
| `adressen` | `List<Adresse>` | Temporal address history, managed by the aggregate |

**Business rules enforced by the aggregate:**

- `name`, `vorname`, `geschlecht`, and `geburtsdatum` are mandatory on creation.
- `ahvNummer` is optional but must be globally unique if provided.
- An AHV number with invalid EAN-13 checksum is rejected at construction.
- When a new address of a given type is added, the aggregate automatically resolves overlaps with existing addresses of the same type (clip, shift, or remove the older entry).

### 3.2 Value Object: `AhvNummer`

Represents the Swiss social security number (13 digits, starting with `756`, EAN-13 checksum).  
Stored as 13 raw digits internally; displayed as `756.XXXX.XXXX.XX`.

### 3.3 Entity: `Adresse`

A temporal address owned by a `Person`.

| Field | Type | Description |
|---|---|---|
| `adressId` | `UUID` | Surrogate key |
| `adressTyp` | `AdressTyp` | `WOHNADRESSE`, `KORRESPONDENZADRESSE`, `ZUSTELLADRESSE` |
| `strasse` | `String` | Street name (required) |
| `hausnummer` | `String` | House number (required) |
| `plz` | `String` | Swiss postal code – exactly 4 digits |
| `ort` | `String` | City / municipality (required) |
| `land` | `String` | Country (defaults to `Schweiz`) |
| `gueltigVon` | `LocalDate` | Validity start (required) |
| `gueltigBis` | `LocalDate` | Validity end (`null` = indefinite / open end) |

**Business rules:**

- `gueltigVon` must not be after `gueltigBis`.
- PLZ must match exactly 4 digits (Swiss format).
- At most one address of each `AdressTyp` should be valid at any given point in time. The aggregate enforces this via the overlap-resolution algorithm.

---

## 4. Application Service Use Cases

| Use Case | Method | Description |
|---|---|---|
| Create person | `createPerson(...)` | Creates a new `Person`; validates AHV uniqueness; publishes `PersonCreated` |
| Update personal data | `updatePersonalien(...)` | Updates name, given name, gender, date of birth; publishes `PersonUpdated` |
| Delete person | `deletePerson(personId)` | Removes the person record; publishes `PersonDeleted` |
| Find by ID | `findById(personId)` | Returns a single person or throws `PersonNotFoundException` |
| Search persons | `searchPersonen(...)` | At least one filter criterion required; raises error otherwise |
| Add address | `addAdresse(...)` | Adds a new temporal address; resolves overlaps; publishes `AddressAdded` |
| Update address validity | `updateAdressGueltigkeit(...)` | Adjusts `gueltigVon`/`gueltigBis`; resolves overlaps; publishes `AddressUpdated` |
| Delete address | `deleteAdresse(personId, adressId)` | Removes a specific address entry |

---

## 5. REST API

Base path: `/api/personen`

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/personen` | Create a person |
| `GET` | `/api/personen?name=&vorname=&ahv=&geburtsdatum=` | Search (at least one param required) |
| `GET` | `/api/personen/{id}` | Get person by ID |
| `PUT` | `/api/personen/{id}` | Update personal data |
| `DELETE` | `/api/personen/{id}` | Delete a person |
| `GET` | `/api/personen/{id}/adressen` | List addresses (optional `?typ=&aktuell=`) |
| `POST` | `/api/personen/{id}/adressen` | Add address |
| `PUT` | `/api/personen/{id}/adressen/{adressId}/gueltigkeit` | Update address validity |
| `DELETE` | `/api/personen/{id}/adressen/{adressId}` | Remove address |

---

## 6. Kafka Events Published

Events are delivered via the **Transactional Outbox Pattern**: `PersonApplicationService` writes an `OutboxEvent` row to the `outbox` table within the same database transaction as the business data. Debezium Connect reads new rows from the PostgreSQL WAL and publishes them to the corresponding Kafka topic. This guarantees at-least-once delivery without dual-write risk.

| Topic | Event Type | Trigger |
|---|---|---|
| `person.v1.created` | `PersonCreated` | New person successfully saved |
| `person.v1.updated` | `PersonUpdated` | Personal data (name, etc.) changed |
| `person.v1.deleted` | `PersonDeleted` | Person record removed |
| `person.v1.address-added` | `AddressAdded` | New address added to a person |
| `person.v1.address-updated` | `AddressUpdated` | Address validity period modified |
| `person.v1.state` | `PersonState` | Full state snapshot on every mutation (compacted) |

### 6.1 Read-Model Bootstrap Protocol (State Topic)

The `person.v1.state` topic implements the **Event-Carried State Transfer** pattern using Kafka log compaction. Every mutation (`createPerson`, `updatePersonalData`, `deletePerson`, `addAddress`, `updateAddressValidity`, `deleteAddress`) writes a second outbox entry containing the full current state of the person (including all addresses) to this compacted topic, keyed by `personId`.

**Bootstrap procedure for downstream consumers:**

1. Start a new consumer group (or reset offsets to `earliest`) on `person.v1.state`.
2. Consume from offset 0. Because the topic uses `cleanup.policy=compact`, Kafka retains only the latest message per `personId`. This gives the consumer the full current partner registry in a single pass.
3. Once caught up, continue consuming in real time. Each incoming message replaces the previous state for that `personId` in the local materialized view.
4. Messages with `"deleted": true` are semantic tombstones. Remove the corresponding `personId` from the local view.

**Advantages over replaying event topics:**

- No need to replay and reduce the full `person.v1.created` / `person.v1.updated` / `person.v1.deleted` event history.
- Consumers can bootstrap in seconds regardless of how many historical events exist.
- The state topic is idempotent: applying the same message twice produces the same result.

**Outbox table schema** (`public.outbox`):

| Column | Type | Description |
|---|---|---|
| `id` | `UUID` | Event ID (PK) |
| `aggregate_type` | `VARCHAR(64)` | Always `"person"` |
| `aggregate_id` | `VARCHAR(64)` | Person UUID |
| `event_type` | `VARCHAR(128)` | e.g. `PersonCreated` |
| `topic` | `VARCHAR(256)` | Target Kafka topic |
| `payload` | `TEXT` | JSON event payload |
| `created_at` | `TIMESTAMPTZ` | Write timestamp |

ODC contracts: `src/main/resources/contracts/`

---

## 7. Consumed Events

The Partner Service does **not** consume any Kafka events from other domains. It is a pure **producer** in the event topology.

---

## 8. Read Model in Policy Service

The Policy Service materializes a local `PartnerSicht` (partner view) read model by consuming `person.v1.created` and `person.v1.updated` events. This enables the partner-picker UI widget in the policy form without any synchronous REST call to the Partner Service (ADR-001 compliance).

---

## 9. UI

The Qute-based web UI is served at `/personen/` and supports:

- Person list with search and inline creation modal
- Person detail/edit page with personal data form
- Address card per address type with add/edit/delete actions
- Address validity editing (date picker per address)

All UI labels, buttons, validation messages, and tooltips are in **German** per the project language policy.

---

## Consumer Bootstrap Protocol

New consumers that need the full current state of all persons should follow this two-phase approach:

### Phase 1: Initial Bootstrap

Consume the **`person.v1.state`** topic with `auto.offset.reset=earliest`. This is a **compacted topic** where each key (person ID) retains only the latest value. It provides the full current state of every person, including all addresses. This allows a new consumer to build a complete read model without replaying the entire event history.

- **Key:** Person UUID
- **Value:** Full person state (all fields + nested address list)
- **Tombstone events:** Records where `deleted=true` indicate the person has been removed from the system. Consumers should delete the corresponding entry from their local read model.

### Phase 2: Incremental Updates

After the initial bootstrap is complete (i.e., the consumer has caught up to the end of the `person.v1.state` topic), switch to the granular event topics for incremental updates:

| Topic | Purpose |
|---|---|
| `person.v1.created` | A new person was registered |
| `person.v1.updated` | Personal data (name, gender, date of birth, AHV number) changed |
| `person.v1.deleted` | A person was removed |
| `person.v1.address-added` | A new address was added to a person |
| `person.v1.address-updated` | An address validity period was modified |

This two-phase protocol ensures that consumers can be deployed at any time (even long after the system has been running) and still obtain a complete, consistent view of all persons without requiring the Partner Service to re-publish historical events.

---

## 10. Technical Debt – Resolved

The language inconsistency (German domain model) that previously existed has been resolved:

- Class names migrated: `Adresse` → `Address`, `AhvNummer` → `SocialSecurityNumber`, `Geschlecht` → `Gender`, `AdressTyp` → `AddressType`
- Enum values migrated via `V6__Rename_Enum_Values.sql`: `MAENNLICH` → `MALE`, `WOHNADRESSE` → `RESIDENCE`, etc.
- Event publishing migrated from direct Kafka (`PersonKafkaAdapter`) to Transactional Outbox via Debezium CDC
- ODC field names updated to match actual English JSON payload fields

---

## 11. Ubiquitous Language

| UI Term (German) | Code Term (English) | Definition |
| :--- | :--- | :--- |
| Person | Person | Natural person registered as a partner in the insurance system |
| Vorname | firstName | Given (first) name of the person |
| Name / Nachname | lastName | Family (last) name of the person |
| Geschlecht | gender | Person's gender; values: male, female, diverse |
| Geburtsdatum | dateOfBirth | Date of birth of the person |
| AHV-Nummer | socialSecurityNumber | Swiss social security number (13 digits, EAN-13, starts with 756) |
| Adresse | address | A physical address associated with a person |
| Wohnadresse | residenceAddress | Primary home/residential address |
| Korrespondenzadresse | correspondenceAddress | Address for official mail and correspondence |
| Zustelladresse | deliveryAddress | Address for physical deliveries |
| Gültig von | validFrom | Start date of an address's validity period |
| Gültig bis | validTo | End date of an address's validity period; `null` means indefinite |
| Adressverlauf | addressHistory | Chronological list of all addresses of a given type for a person |
| Aktuelle Adresse | currentAddress | The address of a given type that is valid today |
| Sachbearbeiter | claimsAgent | Back-office staff processing claims (role name) |
| Versicherungsnehmer | policyholder | The person who holds an insurance policy |

