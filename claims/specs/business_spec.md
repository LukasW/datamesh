# Claims Management (Schadenmanagement) - Business Specification

> **Version:** 1.1.0 · **Last updated:** 2026-03-20
> **Owner:** Claims Management Team
> **Status:** Implemented – PostgreSQL, Outbox/Debezium, OIDC, Qute UI

---

## Domain Overview

The Claims Management domain handles the lifecycle of insurance claims (Schadenfaelle)
within the property insurance platform. A claim is initiated when a policyholder reports
damage (First Notice of Loss), and progresses through review, coverage verification,
and settlement or rejection.

---

## Core Concepts

### First Notice of Loss (FNOL / Schadenmeldung)

The entry point for any claim. A policyholder or claims agent reports a damage event
referencing an existing policy. The system:

1. Accepts the damage report with a description and claim date.
2. Performs a **coverage check** against the local **PolicySnapshot read model** (ADR-008).
3. If a policy snapshot exists, creates a new claim in `OPEN` status.
4. If no snapshot is found (policy unknown or not yet received), the claim is rejected immediately.

### Claims Processing (Regulierung)

Once a claim is opened, a claims agent (`CLAIMS_AGENT` role) reviews the claim:

- Assess the damage and estimate repair/replacement costs.
- Request additional documentation if needed.
- Transition the claim to `IN_REVIEW`, `SETTLED`, or `REJECTED`.

### Coverage Check (Deckungspruefung)

The coverage check runs **fully autonomously** against the **local `policy_snapshot` table**
inside the Claims service database. This read model is materialised by consuming
`policy.v1.issued` events from Kafka (ADR-008).

No synchronous REST call to the Policy service is made. The Claims service is therefore
independent of Policy service availability during FNOL.

### PolicySnapshot Read Model

| Field | Type | Description |
| --- | --- | --- |
| `policyId` | UUID | Primary key |
| `policyNumber` | String | Human-readable policy number |
| `partnerId` | UUID | Reference to the Partner domain |
| `productId` | UUID | Reference to the Product domain |
| `coverageStartDate` | Date | Start of coverage |
| `premium` | Decimal | Annual premium in CHF |

---

## Claim Lifecycle (Status Transitions)

```
  FNOL received
       |
       v
    [OPEN] ──────> [IN_REVIEW] ──────> [SETTLED]
       |                |
       v                v
  [REJECTED]       [REJECTED]
```

### Claim Statuses

| Status       | Description (English)                          | German (UI)       |
|--------------|------------------------------------------------|-------------------|
| `OPEN`       | Claim created, awaiting review                 | Offen             |
| `IN_REVIEW`  | Under assessment by claims agent               | In Bearbeitung    |
| `SETTLED`    | Claim approved and payout initiated            | Reguliert         |
| `REJECTED`   | Claim denied (no coverage or invalid)          | Abgelehnt         |

---

## Ubiquitous Language

| German (UI)          | English (Code)     | Description                                      |
|----------------------|--------------------|--------------------------------------------------|
| Schadenfall          | Claim              | An insurance claim for reported damage            |
| Schadenmeldung       | First Notice of Loss (FNOL) | Initial damage report from policyholder |
| Schadennummer        | Claim Number       | Unique business identifier for a claim            |
| Deckungspruefung     | Coverage Check     | Verification that the policy covers the damage    |
| Regulierung          | Settlement         | Process of approving and paying out a claim       |
| Sachbearbeiter       | Claims Agent       | Person responsible for processing the claim       |
| Schadensdatum        | Claim Date         | Date when the damage occurred                     |
| Polizzen-Snapshot    | PolicySnapshot     | Local read model of a policy, built from Kafka events |

---

## Kafka Events

### Published

| Topic                   | Description                                  |
|-------------------------|----------------------------------------------|
| `claims.v1.opened`      | Published when a new claim is created (FNOL) |
| `claims.v1.settled`     | Published when a claim is settled            |

Events are published via the **Transactional Outbox Pattern** (Debezium CDC).
The `claims.v1.settled` event triggers a payout in the Billing & Collection service.

### Consumed

| Topic                   | Purpose                                                |
|-------------------------|--------------------------------------------------------|
| `policy.v1.issued`      | Materialises the local `policy_snapshot` read model (ADR-008) |

Consumer group: `claims-service-policy`

---

## REST API

### POST /api/claims

Create a new claim (FNOL).

**Request body:**
```json
{
  "policyId": "uuid-string",
  "description": "Water damage in basement",
  "claimDate": "2026-03-15"
}
```

**Response (201 Created):**
```json
{
  "claimId": "uuid-string",
  "policyId": "uuid-string",
  "claimNumber": "CLM-20260315-A1B2",
  "description": "Water damage in basement",
  "claimDate": "2026-03-15",
  "status": "OPEN",
  "createdAt": "2026-03-15T10:30:00Z"
}
```

**Response (409 Conflict):** If no `policy_snapshot` exists for the given `policyId`
(policy unknown or not yet received via Kafka).

### GET /api/claims/{claimId}

Retrieve a claim by its ID.

### GET /api/claims?policyId={policyId}

List all claims for a given policy.

### POST /api/claims/{claimId}/review

Transition a claim from `OPEN` to `IN_REVIEW`.

### POST /api/claims/{claimId}/settle

Transition a claim from `IN_REVIEW` to `SETTLED`. Publishes `claims.v1.settled`.

### POST /api/claims/{claimId}/reject

Transition a claim from `OPEN` or `IN_REVIEW` to `REJECTED`.

---

## Web UI

Available at `http://localhost:9083/claims`. Features:

- **Übersichtsliste** – sortable table of all claims with search by police ID and status filter.
- **Neue Schadenmeldung** (`/claims/neu`) – full-page form to manually enter a First Notice of Loss (policyId, claimDate, description). Validates policy snapshot presence on submit.
- **Inline-Bearbeitung** – for `OPEN` claims: "Ändern" button swaps the table row with an inline edit form (htmx). Allows updating description and claimDate without leaving the list page.
- **Statusübergänge** (htmx inline):
  - `OPEN` → "In Bearbeitung" (review), "Ablehnen"
  - `IN_REVIEW` → "Regulieren" (settle), "Ablehnen"

---

## Integration Points

| Direction               | Service  | Protocol | Purpose                                   | ADR     |
|-------------------------|----------|----------|-------------------------------------------|---------|
| ← Kafka (consumed)      | Policy   | Kafka    | Materialise `policy_snapshot` read model  | ADR-008 |
| → Kafka (published)     | Billing  | Kafka    | `claims.v1.settled` triggers payout       | ADR-001 |
| Claims → Outbox → Kafka | Debezium | CDC/WAL  | At-least-once event delivery              | ADR-006 |

---

## Roles

| Role            | Permissions                                    |
|-----------------|------------------------------------------------|
| `CLAIMS_AGENT`  | Open claims, review, settle, reject            |
| `UNDERWRITER`   | View claims (read-only)                        |
| `ADMIN`         | Full access                                    |
