# Claims Management (Schadenmanagement) - Business Specification

> **Status:** Stub implementation - core domain model and REST API only.
> No persistence layer or Kafka integration yet.

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
2. Performs a **coverage check** against the Policy service (synchronous REST call with circuit breaker).
3. If coverage is confirmed, creates a new claim in `OPEN` status.
4. If coverage is denied, the claim is rejected immediately.

### Claims Processing (Regulierung)

Once a claim is opened, a claims agent (`CLAIMS_AGENT` role) reviews the claim:

- Assess the damage and estimate repair/replacement costs.
- Request additional documentation if needed.
- Transition the claim to `IN_REVIEW`, `SETTLED`, or `REJECTED`.

> **Note:** Claims processing workflow is not yet implemented in this stub.

### Coverage Check (Deckungspruefung)

A synchronous REST call from Claims to the Policy service (`/api/policies/{policyId}`)
to verify that the referenced policy is active and covers the reported damage type.
This is one of the explicitly allowed REST integrations per ADR-003.

The call is protected by a **circuit breaker** (SmallRye Fault Tolerance) to ensure
resilience if the Policy service is unavailable.

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

---

## Kafka Events (Planned)

| Topic                   | Description                                  |
|-------------------------|----------------------------------------------|
| `claims.v1.opened`      | Published when a new claim is created (FNOL) |
| `claims.v1.settled`     | Published when a claim is settled            |

> **Note:** Kafka event publishing is not yet implemented. ODC contracts are provided
> as placeholders for the future implementation.

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

### GET /api/claims/{claimId}

Retrieve a claim by its ID.

---

## Integration Points

| Direction         | Service | Protocol | Purpose                    | ADR   |
|-------------------|---------|----------|----------------------------|-------|
| Claims -> Policy  | Policy  | REST     | Coverage check during FNOL | ADR-003 |

---

## Roles

| Role            | Permissions                                    |
|-----------------|------------------------------------------------|
| `CLAIMS_AGENT`  | Open claims, review, settle, reject            |
| `UNDERWRITER`   | View claims (read-only)                        |
| `ADMIN`         | Full access                                    |
