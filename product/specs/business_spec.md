# Business Specification – Product Service

> **Version:** 1.0.0 · **Last updated:** 2026-03-15  
> **Owner:** Product Management Team  
> **Service port:** 9081

---

## 1. Domain Overview

The **Product Service** is the **system of record for insurance product definitions** in the Sachversicherung platform. It defines what insurance offerings exist, their product line classification, and their base premium. Product data is consumed by the Policy Service (via Kafka read model) to populate the product picker when creating a policy.

The Product Service is upstream of all services that deal with contracts: changes to products are published as Kafka events, and consumers keep their own local read models up to date.

### Bounded Context

```
┌─────────────────────────────────────────────────────────────┐
│                    Product Service                          │
│                                                             │
│  Product (Aggregate Root)                                   │
│                                                             │
│  Publishes: product.v1.* events → Kafka                     │
│  Consumed by: Policy (ProduktSicht read model)              │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Core Responsibilities

| Responsibility | Description |
|---|---|
| **Product catalogue management** | Define, update, and deprecate insurance product offerings |
| **Product line classification** | Classify products into lines (HAUSRAT, HAFTPFLICHT, etc.) |
| **Base premium definition** | Define the starting premium that underwriters use as a basis |
| **Product lifecycle** | Manage the ACTIVE → DEPRECATED state transition |
| **Event publication** | Publish product events to Kafka for downstream read models |

---

## 3. Domain Model

### 3.1 Aggregate Root: `Product`

The `Product` aggregate is the single entry point for all product state changes.

| Field | Type | Description |
|---|---|---|
| `productId` | `UUID` | Surrogate key, system-generated |
| `name` | `String` | Product display name (required) |
| `description` | `String` | Optional free-text description |
| `productLine` | `ProductLine` | Classification of the insurance product |
| `basePremium` | `BigDecimal` | Base annual premium in CHF (must be ≥ 0) |
| `status` | `ProductStatus` | `ACTIVE` or `DEPRECATED` |

### 3.2 Product Lifecycle (`ProductStatus`)

```
ACTIVE ──deprecate()──► DEPRECATED
```

| Status | Description | Allowed operations |
|---|---|---|
| `ACTIVE` | Product is available for new policies | update, deprecate |
| `DEPRECATED` | Product is no longer offered | read only (no new policies) |

**Business rules:**

- `name` and `productLine` are mandatory.
- `basePremium` must be ≥ 0 (zero-premium products are valid for bundled offers).
- Deprecating an already deprecated product throws an `IllegalStateException`.
- Deprecated products remain readable and are retained in the catalogue for historical reference (existing policies continue to reference them).

### 3.3 Enum: `ProductLine`

| Value | Description |
|---|---|
| `HAUSRAT` | Household contents insurance |
| `HAFTPFLICHT` | Personal liability insurance |
| `MOTORFAHRZEUG` | Motor vehicle insurance |
| `REISE` | Travel insurance |
| `RECHTSSCHUTZ` | Legal expenses insurance |

---

## 4. Application Service Use Cases

| Use Case | Method | Description |
|---|---|---|
| Define product | `defineProduct(...)` | Creates a new `ACTIVE` product; publishes `ProductDefined` |
| Update product | `updateProduct(...)` | Updates name, description, product line, base premium; publishes `ProductUpdated` |
| Deprecate product | `deprecateProduct(productId)` | Transitions `ACTIVE → DEPRECATED`; publishes `ProductDeprecated` |
| Delete product | `deleteProduct(productId)` | Hard-deletes a product (use with caution; prefer deprecation) |
| Find by ID | `findById(productId)` | Returns product or throws `ProductNotFoundException` |
| List all products | `listAllProducts()` | Returns full catalogue including deprecated |
| Search products | `searchProducts(name, productLine)` | Filter by name fragment and/or product line |

---

## 5. REST API

Base path: `/api/products`

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/products` | Define a new product |
| `GET` | `/api/products?name=&productLine=` | Search products |
| `GET` | `/api/products/{id}` | Get product by ID |
| `PUT` | `/api/products/{id}` | Update product details |
| `DELETE` | `/api/products/{id}` | Delete a product |
| `POST` | `/api/products/{id}/deprecate` | Deprecate a product |

---

## 6. Kafka Events Published

| Topic | Event Type | Trigger |
|---|---|---|
| `product.v1.defined` | `ProductDefined` | New product saved successfully |
| `product.v1.updated` | `ProductUpdated` | Product details changed |
| `product.v1.deprecated` | `ProductDeprecated` | Product marked as deprecated |

ODC contracts: `src/main/resources/contracts/`

---

## 7. Kafka Events Consumed

The Product Service does **not** consume any Kafka events from other domains. It is a pure **producer** in the event topology.

---

## 8. UI

The Qute-based web UI is served at `/products/` and supports:

- Product list with search (name, product line) and inline creation modal
- Product detail/edit page
- Deprecate action with confirmation

All UI labels, buttons, validation messages, and tooltips are in **German** per the project language policy.

---

## 9. Language Compliance

> ✅ The Product Service is largely compliant with the code-in-English rule.

The domain model (`Product`, `ProductLine`, `ProductStatus`), service methods (`defineProduct`, `updateProduct`, `deprecateProduct`), and event names (`ProductDefined`, `ProductUpdated`, `ProductDeprecated`) are all in English.

**Minor residual issue:**

| Location | Issue | Target |
|---|---|---|
| `ProduktSicht` (in Policy service) | German name for the product read model | `ProductView` |
| `ProduktSichtRepository` (in Policy service) | German name for the repository port | `ProductViewRepository` |

These are tracked in the Policy Service's technical debt section.

---

## 10. Ubiquitous Language

| UI Term (German) | Code Term (English) | Definition |
| :--- | :--- | :--- |
| Produkt | Product | An insurance offering defined by the Product Management team |
| Produktname | productName | The display name of the product |
| Produktlinie | productLine | The classification of the product (e.g. Hausrat, Haftpflicht) |
| Grundprämie | basePremium | The base annual premium in CHF before individual risk factors are applied |
| Aktiv | ACTIVE | A product that is available for issuing new policies |
| Veraltet / Abgekündigt | DEPRECATED | A product no longer offered for new contracts; existing policies remain valid |
| Hausrat | HAUSRAT | Household contents product line |
| Haftpflicht | HAFTPFLICHT | Personal liability product line |
| Motorfahrzeug | MOTORFAHRZEUG | Motor vehicle product line |
| Reise | REISE | Travel insurance product line |
| Rechtsschutz | RECHTSSCHUTZ | Legal expenses product line |
| Produktkatalog | productCatalogue | The full collection of defined insurance products |
| Produkt definieren | defineProduct | The act of creating a new product entry in the catalogue |
| Produkt abkündigen | deprecateProduct | The act of marking a product as no longer available for new policies |
| Underwriter | underwriter | The person responsible for defining products and assessing risk |

