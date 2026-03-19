# Business Specification – Sales & Distribution Service

> **Version:** 0.1.0 · **Last updated:** 2026-03-19
> **Owner:** Sales & Distribution Team
> **Status:** Planned, not yet implemented

---

## 1. Domain Overview

The **Sales & Distribution Service** is a **supporting domain** responsible for the sales funnel of insurance products. It manages the creation and lifecycle of offers, broker-mediated distribution, and the handoff to the Policy domain when an offer is accepted. The service consumes product definitions from the Product domain and partner data from the Partner domain via Kafka.

### Bounded Context

```
┌─────────────────────────────────────────────────────────────┐
│                 Sales & Distribution Service                  │
│                                                              │
│  Offer (Aggregate Root)                                      │
│    ├── OfferLineItem[] (Entity)                              │
│    └── OfferValidity (Value Object)                          │
│                                                              │
│  BrokerAssignment (Aggregate Root)                           │
│                                                              │
│  Consumes: product.v1.defined, person.v1.state               │
│  Publishes: sales.v1.offer-created,                          │
│             sales.v1.offer-accepted,                          │
│             sales.v1.offer-rejected,                          │
│             sales.v1.offer-expired                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Core Responsibilities

| Responsibility | Description |
|---|---|
| **Offer management (Angebote)** | Create, price, and manage insurance offers based on product definitions. Offers have a configurable validity period after which they expire automatically. |
| **Broker distribution (Maklervermittlung)** | Assign offers and customers to brokers. Track broker commissions and distribution channels. |
| **Offer acceptance** | When a customer or broker accepts an offer, publish an `OfferAccepted` event that triggers policy creation in the Policy domain. |
| **Offer lifecycle** | Track offer states: DRAFT, SUBMITTED, ACCEPTED, REJECTED, EXPIRED. |
| **Premium calculation** | Calculate indicative premiums for offers using product definitions and risk parameters. |
| **Audit trail** | Full audit log of all offer mutations via Hibernate Envers. |

---

## 3. Kafka Integration

### Consumed Events

| Topic | Source Domain | Trigger |
|---|---|---|
| `product.v1.defined` | Product | Maintain local read model of available products and pricing rules |
| `person.v1.state` | Partner | Maintain local read model for customer data used in offers |

### Published Events (Reserved)

| Topic | Description |
|---|---|
| `sales.v1.offer-created` | New offer created for a prospective policyholder |
| `sales.v1.offer-accepted` | Offer accepted by customer/broker, triggers policy creation |
| `sales.v1.offer-rejected` | Offer explicitly rejected by customer |
| `sales.v1.offer-expired` | Offer validity period elapsed without acceptance |

---

## 4. Ubiquitous Language

| German (UI) | English (Code) | Context |
|---|---|---|
| Angebot | Offer | Insurance offer/quote for a prospective policyholder |
| Angebotsposition | OfferLineItem | Single coverage line within an offer |
| Gültigkeit | OfferValidity | Time window during which the offer can be accepted |
| Makler | Broker | Insurance intermediary distributing products |
| Maklervermittlung | BrokerDistribution | Assignment of offers to broker channels |
| Provision | Commission | Broker fee for mediating a sale |
| Vertriebskanal | DistributionChannel | Channel through which the product is sold (DIRECT, BROKER, ONLINE) |
| Offerte | Quote | Synonym for Angebot in some contexts |
| Annahme | Acceptance | Customer accepting the offer |
| Ablehnung | Rejection | Customer rejecting the offer |

---

## 5. Implementation Notes

This service is **planned but not yet implemented**. When implementation begins, it should follow the same architectural patterns as the existing services:

- Hexagonal Architecture (domain model free of framework dependencies)
- Transactional Outbox Pattern via Debezium CDC
- Own PostgreSQL instance (`sales_db`)
- Quarkus with Qute/htmx for the UI (German labels)
- ODC YAML contracts for all published Kafka topics
- IAM integration with `BROKER` role for broker-specific views
