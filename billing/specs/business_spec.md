# Business Specification – Billing & Collection Service

> **Version:** 0.1.0 · **Last updated:** 2026-03-19
> **Owner:** Billing & Collection Team
> **Status:** Planned, not yet implemented

---

## 1. Domain Overview

The **Billing & Collection Service** is a **supporting domain** responsible for the financial lifecycle of insurance contracts. It handles invoicing, payment tracking, dunning, and payouts triggered by policy and claims events. The service consumes domain events from Policy and Claims via Kafka and owns all billing-related data exclusively.

### Bounded Context

```
┌─────────────────────────────────────────────────────────────┐
│                 Billing & Collection Service                  │
│                                                              │
│  Invoice (Aggregate Root)                                    │
│    ├── InvoiceLineItem[] (Entity)                            │
│    └── PaymentAllocation[] (Entity)                          │
│                                                              │
│  DunningCase (Aggregate Root)                                │
│    └── DunningStep[] (Entity)                                │
│                                                              │
│  Payout (Aggregate Root)                                     │
│                                                              │
│  Consumes: policy.v1.issued, policy.v1.cancelled,            │
│            claims.v1.settled                                  │
│  Publishes: billing.v1.invoice-created,                      │
│             billing.v1.payment-received,                      │
│             billing.v1.dunning-initiated,                     │
│             billing.v1.payout-triggered                       │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Core Responsibilities

| Responsibility | Description |
|---|---|
| **Invoicing (Fakturierung)** | Generate premium invoices when a policy is issued or renewed. Support annual, semi-annual, quarterly, and monthly billing cycles. |
| **Payment receipt (Zahlungseingang)** | Record incoming payments and allocate them to open invoices. Detect overpayments and underpayments. |
| **Dunning (Mahnwesen)** | Initiate and escalate dunning processes for overdue invoices. Support configurable dunning levels (reminder, first warning, final warning, collection). |
| **Payouts (Auszahlungen)** | Trigger claim settlement payouts to policyholders when a ClaimSettled event is received. |
| **Premium splitting** | Split annual premiums into installments according to the selected billing cycle. |
| **Audit trail** | Full audit log of all financial mutations via Hibernate Envers. |

---

## 3. Kafka Integration

### Consumed Events

| Topic | Source Domain | Trigger |
|---|---|---|
| `policy.v1.issued` | Policy | Create initial premium invoice |
| `policy.v1.cancelled` | Policy | Cancel open invoices, calculate pro-rata refund |
| `claims.v1.settled` | Claims | Trigger payout to policyholder |
| `person.v1.state` | Partner | Maintain local read model for policyholder address and bank details |

### Published Events (Reserved)

| Topic | Description |
|---|---|
| `billing.v1.invoice-created` | Invoice generated for a policy premium |
| `billing.v1.payment-received` | Payment successfully allocated to an invoice |
| `billing.v1.dunning-initiated` | Dunning process started for overdue invoice |
| `billing.v1.payout-triggered` | Payout initiated for a settled claim |

---

## 4. Ubiquitous Language

| German (UI) | English (Code) | Context |
|---|---|---|
| Rechnung | Invoice | Premium invoice for a policy |
| Rechnungsposition | InvoiceLineItem | Single line on an invoice |
| Zahlung | Payment | Incoming payment from policyholder |
| Zahlungseingang | PaymentReceipt | Record of a received payment |
| Mahnung | DunningNotice | Overdue payment reminder |
| Mahnstufe | DunningLevel | Escalation level (REMINDER, FIRST_WARNING, FINAL_WARNING, COLLECTION) |
| Auszahlung | Payout | Claim settlement payment to policyholder |
| Prämie | Premium | Insurance fee |
| Ratenzahlung | Installment | Premium split into periodic payments |
| Fakturierung | Invoicing | Process of generating invoices |
| Mahnwesen | Dunning | Process of collecting overdue payments |

---

## 5. Implementation Notes

This service is **planned but not yet implemented**. When implementation begins, it should follow the same architectural patterns as the existing services:

- Hexagonal Architecture (domain model free of framework dependencies)
- Transactional Outbox Pattern via Debezium CDC
- Own PostgreSQL instance (`billing_db`)
- Quarkus with Qute/htmx for the UI (German labels)
- ODC YAML contracts for all published Kafka topics
