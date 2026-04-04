# Plan: Bronze / Silver / Gold Medallion Architecture

## Ausgangslage

### Problem
Die bestehenden SQLMesh-Staging-Modelle sind **kaputt**. Sie referenzieren Spalten (`id`, `event_id`, `topic`, `payload`, `consumed_at`), die in den Iceberg-Tabellen nicht existieren. Die Bronze-Tabellen haben flache Spalten direkt aus dem JSON-Payload (z.B. `eventtype`, `claimid`, `eventid`).

### Aktuelles Bronze-Schema (tatsaechlich in Iceberg)

| Tabelle | Event-Typen | Spalten (Beispiel) |
|---|---|---|
| `claims_raw.claims_events` | ClaimOpened, ClaimSettled | eventid, eventtype, claimid, claimnumber, policyid, status, claimdate, description, timestamp |
| `policy_raw.policy_events` | PolicyIssued, CoverageAdded, PolicyCancelled | eventid, eventtype, policyid, policynumber, partnerid, productid, coverageid, coveragetype, insuredamount, coveragestartdate, premium, timestamp |
| `partner_raw.person_events` | PersonCreated, PersonUpdated, PersonState, AddressAdded | eventid, eventtype, personid, name, firstname, socialsecuritynumber, dateofbirth, insurednumber, encrypted, city, street, postalcode, housenumber, addresstype, addressid, land, validfrom, deleted, gender, addresses |
| `billing_raw.billing_events` | InvoiceCreated, PaymentReceived, DunningInitiated | eventid, eventtype, invoiceid, invoicenumber, policyid, policynumber, partnerid, totalamount, billingcycle, duedate, amountpaid, paidat, dunningcaseid, dunninglevel, timestamp |
| `product_raw.product_events` | ProductDefined, ProductState | eventid, eventtype, productid, name, productline, basepremium, status, deleted, timestamp |
| `hr_raw.employee_events` | (leer), employee.updated | eventid, eventtype, employeeid, externalid, firstname, lastname, email, jobtitle, department, orgunitid, entrydate, active, deleted, version, timestamp |
| `hr_raw.org_unit_events` | (leer), org-unit.updated | eventid, eventtype, orgunitid, externalid, name, parentorgunitid, level, active, deleted, version, timestamp |

**Hinweis:** Alle Spaltennamen sind lowercase (Iceberg-Sink normalisiert camelCase zu lowercase).

---

## Ziel-Architektur

```
Bronze (Raw)                    Silver (Cleaned)                Gold (Consumption)
{domain}_raw.*_events    -->    {domain}_silver.{entity}  -->   {domain}_gold.{table}
                                                                analytics.{cross_domain}
```

Jede Domain besitzt ihren eigenen Bronze->Silver->Gold-Pfad (Data Mesh Sovereignty).

---

## Schritt 1: Iceberg-Namespaces anlegen

Neue Namespaces pro Domain fuer Silver und Gold:

```
partner_silver, partner_gold
policy_silver, policy_gold
claims_silver, claims_gold
billing_silver, billing_gold
product_silver, product_gold
hr_silver, hr_gold
```

Plus `analytics` fuer cross-domain Gold-Marts (bleibt wie bisher).

**Umsetzung:** Trino-Init-SQL oder SQLMesh erstellt Schemas automatisch bei der ersten Materialisierung.

---

## Schritt 2: Silver-Modelle (Entity-Tabellen)

Silver = Deduplizierte, typisierte Entity-Tabellen. Jede Tabelle zeigt den **aktuellen Zustand** einer Entitaet (Last-Write-Wins ueber Events).

### 2.1 Partner Domain

**`partner_silver.partner`** (ersetzt `dim_partner`)
- Quelle: `partner_raw.person_events` WHERE `eventtype IN ('PersonCreated', 'PersonUpdated', 'PersonState')`
- Deduplizierung: `ROW_NUMBER() OVER (PARTITION BY personid ORDER BY timestamp DESC)`
- PII-Entschluesselung via `vault_decrypt()` (ADR-009)
- Spalten: `partner_id`, `family_name`, `first_name`, `full_name`, `social_security_number`, `insured_number`, `date_of_birth`, `gender`, `insurance_status`, `updated_at`

**`partner_silver.address`** (ersetzt `dim_partner_address`)
- Quelle: `partner_raw.person_events` WHERE `eventtype = 'AddressAdded'`
- Deduplizierung: Latest per `personid` + `addresstype`
- Spalten: `address_id`, `partner_id`, `address_type`, `street`, `house_number`, `postal_code`, `city`, `country`, `valid_from`, `updated_at`

### 2.2 Policy Domain

**`policy_silver.policy`** (ersetzt `fact_policies`)
- Quelle: `policy_raw.policy_events` WHERE `eventtype IN ('PolicyIssued', 'PolicyCancelled')`
- Deduplizierung: Latest per `policyid`
- Spalten: `policy_id`, `policy_number`, `partner_id`, `product_id`, `coverage_start_date`, `premium_chf`, `policy_status`, `issued_at`, `updated_at`

**`policy_silver.coverage`** (NEU)
- Quelle: `policy_raw.policy_events` WHERE `eventtype = 'CoverageAdded'`
- Spalten: `coverage_id`, `policy_id`, `coverage_type`, `insured_amount_chf`, `created_at`

### 2.3 Claims Domain

**`claims_silver.claim`** (ersetzt `fact_claims`)
- Quelle: `claims_raw.claims_events`
- Deduplizierung: Latest per `claimid`
- Spalten: `claim_id`, `claim_number`, `policy_id`, `description`, `claim_date`, `status`, `updated_at`

### 2.4 Billing Domain

**`billing_silver.invoice`** (ersetzt `fact_invoices`)
- Quelle: `billing_raw.billing_events`
- Deduplizierung: Latest per `invoiceid`
- Spalten: `invoice_id`, `invoice_number`, `policy_id`, `partner_id`, `policy_number`, `billing_cycle`, `total_amount_chf`, `due_date`, `invoice_status`, `paid_at`, `dunning_level`, `created_at`, `updated_at`

### 2.5 Product Domain

**`product_silver.product`** (ersetzt `dim_product`)
- Quelle: `product_raw.product_events` WHERE `eventtype IN ('ProductDefined', 'ProductState')`
- Deduplizierung: Latest per `productid`
- Spalten: `product_id`, `product_name`, `product_line`, `base_premium_chf`, `status`, `is_deprecated`, `updated_at`

### 2.6 HR Domain

**`hr_silver.employee`** (ersetzt `dim_employee`)
- Quelle: `hr_raw.employee_events`
- Deduplizierung: Latest per `employeeid`
- Spalten: `employee_id`, `external_id`, `first_name`, `last_name`, `full_name`, `email`, `job_title`, `department`, `org_unit_id`, `entry_date`, `active`, `deleted`, `employment_status`, `updated_at`

**`hr_silver.org_unit`** (ersetzt `dim_org_unit`)
- Quelle: `hr_raw.org_unit_events`
- Deduplizierung: Latest per `orgunitid`
- Spalten: `org_unit_id`, `external_id`, `name`, `parent_org_unit_id`, `level`, `active`, `deleted`, `updated_at`

---

## Schritt 3: Gold-Modelle (Konsumoptimiert)

Gold = Aggregierte, angereicherte Tabellen fuer spezifische Use Cases. Cross-Domain-Joins erlaubt.

### 3.1 Domain-eigene Gold-Tabellen

**`claims_gold.claim_detail`**
- Claim + Policy + Partner + Adresse (Cross-Domain Join)
- Fuer Claims-Sachbearbeiter und Reporting

**`billing_gold.financial_summary`** (ersetzt `mart_financial_summary`)
- Invoice-Aggregation pro Policy

**`policy_gold.policy_detail`** (ersetzt `mart_policy_detail`)
- Policy + Partner + Product angereichert

**`policy_gold.portfolio_summary`** (ersetzt `mart_portfolio_summary`)
- Aktive Policen gruppiert nach Produkt

### 3.2 Cross-Domain Analytics (Schema: `analytics`)

**`analytics.management_kpi`** (ersetzt `mart_management_kpi`)
- KPI-Aggregation ueber alle Domains

**`analytics.org_hierarchy`** (ersetzt `mart_org_hierarchy`)
- Org-Hierarchie mit Mitarbeiterzahlen

---

## Schritt 4: Superset-Datasets aktualisieren

Die `superset-init.sh` Datasets muessen auf die Silver-Tabellen zeigen statt auf Bronze:

| Alt (Bronze direkt) | Neu (Silver) |
|---|---|
| `SELECT eventtype, ... FROM claims_raw.claims_events` | `SELECT * FROM claims_silver.claim` |
| `SELECT eventtype, ... FROM policy_raw.policy_events` | `SELECT * FROM policy_silver.policy` |
| `SELECT eventtype, ... FROM billing_raw.billing_events` | `SELECT * FROM billing_silver.invoice` |
| `SELECT eventtype, ... FROM partner_raw.person_events` | `SELECT * FROM partner_silver.partner` |
| `SELECT eventtype, ... FROM product_raw.product_events` | `SELECT * FROM product_silver.product` |

---

## Schritt 5: SQLMesh-Konfiguration anpassen

### `config.yaml`

Neue Gateway-Konfiguration mit Schema-Mapping, damit SQLMesh die Tabellen in die richtigen Iceberg-Namespaces schreibt:

```yaml
gateways:
  trino:
    connection:
      type: trino
      host: trino
      port: 8086
      catalog: iceberg
      user: sqlmesh

default_gateway: trino

model_defaults:
  dialect: trino
  start: "2024-01-01"
  cron: "@hourly"
```

Die Schema-Zuordnung erfolgt ueber den `name` im MODEL-Block:
- `name partner_silver.partner` --> wird zu `iceberg.partner_silver.partner`
- `name claims_gold.claim_detail` --> wird zu `iceberg.claims_gold.claim_detail`
- `name analytics.management_kpi` --> bleibt bei `iceberg.analytics.management_kpi`

---

## Schritt 6: Bestehende Staging-Modelle loeschen

Alle `stg_*` Modelle werden entfernt. Die Silver-Tabellen uebernehmen deren Rolle (Typkonvertierung, Deduplizierung, Validierung) direkt.

| Loeschen | Ersetzt durch |
|---|---|
| `stg_person_events.sql` | `partner_silver.partner` |
| `stg_address_events.sql` | `partner_silver.address` |
| `stg_policy_events.sql` | `policy_silver.policy` + `policy_silver.coverage` |
| `stg_claims_events.sql` | `claims_silver.claim` |
| `stg_billing_events.sql` | `billing_silver.invoice` |
| `stg_product_events.sql` | `product_silver.product` |
| `stg_employee_events.sql` | `hr_silver.employee` |
| `stg_org_unit_events.sql` | `hr_silver.org_unit` |

Bestehende Mart-Modelle (dim_*, fact_*) werden durch Gold-Modelle ersetzt.

---

## Verzeichnisstruktur (Neu)

```
infra/sqlmesh/models/
  silver/
    partner/
      partner.sql
      address.sql
    policy/
      policy.sql
      coverage.sql
    claims/
      claim.sql
    billing/
      invoice.sql
    product/
      product.sql
    hr/
      employee.sql
      org_unit.sql
  gold/
    claims/
      claim_detail.sql
    billing/
      financial_summary.sql
    policy/
      policy_detail.sql
      portfolio_summary.sql
    analytics/
      management_kpi.sql
      org_hierarchy.sql
  tests/
      assert_no_orphan_employees.sql
      assert_no_orphan_policies.sql
      assert_premium_positive.sql
```

---

## Reihenfolge der Umsetzung

1. **Silver-Modelle erstellen** (keine Abhaengigkeiten untereinander)
2. **Gold-Modelle erstellen** (abhaengig von Silver)
3. **Alte Staging- und Mart-Modelle loeschen**
4. **SQLMesh config.yaml anpassen** (Schema-Default entfernen)
5. **Superset-Init aktualisieren** (Datasets auf Silver/Gold zeigen)
6. **`sqlmesh plan --auto-apply` ausfuehren**
7. **Superset-Charts testen**

---

## Geklarte Fragen

### 1. PII-Entschluesselung: Silver bleibt verschluesselt, Gold entschluesselt

**Entscheid:** PII bleibt in Silver **verschluesselt** (vault:v1:...). Entschluesselung via `vault_decrypt()` erfolgt erst in Gold-Views oder on-demand in SQL Lab.

**Begruendung:**
- Silver ist materialisiert (`kind FULL`). Entschluesselte Daten wuerden im Klartext in Iceberg/MinIO auf Disk liegen.
- Crypto-Shredding (ADR-009) waere wirkungslos, wenn die entschluesselten Werte bereits materialisiert sind.
- Die `vault_decrypt()` UDF existiert als Trino Scalar Function und ruft pro Wert die Vault Transit API auf — funktioniert in Views/Queries ohne Materialisierung.
- Gold-Views (`kind VIEW`) entschluesseln on-demand, Vault-Keys koennen jederzeit geloescht werden (Crypto-Shredding bleibt wirksam).

**Konsequenz fuer Silver-Modelle:**
- `partner_silver.partner` speichert `name`, `firstname`, `socialsecuritynumber`, `dateofbirth` weiterhin als verschluesselten Vault-Ciphertext.
- `partner_silver.partner` hat eine Spalte `encrypted BOOLEAN` zur Kennzeichnung.
- Nur `insurednumber` (kein PII laut ADR-009) bleibt im Klartext.
- `partner_gold.partner_decrypted` wird ein VIEW mit `vault_decrypt()` Aufrufen.

### 2. PersonState als primaere Quelle fuer Partner Silver

**Entscheid:** `PersonState` ist die **einzige Quelle** fuer `partner_silver.partner`.

**Begruendung (Datenanalyse):**
| Event | name | firstName | insuredNumber | gender | dateOfBirth | addresses |
|---|---|---|---|---|---|---|
| PersonCreated | ja | ja | nein | nein | ja | nein |
| PersonUpdated | ja | ja | ja | nein | nein | nein |
| **PersonState** | **ja** | **ja** | **ja** | **ja** | **ja** | **ja (array)** |

`PersonState` ist der kanonische Snapshot — er enthaelt alle Felder inklusive `gender`, `insurednumber` und das verschachtelte `addresses` Array. `PersonCreated` und `PersonUpdated` sind partielle Events, die nie den vollen Zustand tragen.

**Konsequenz:**
- `partner_silver.partner`: `WHERE eventtype = 'PersonState'`, dedupliziert per `personid` (neuester Timestamp gewinnt).
- `partner_silver.address`: Aus `PersonState.addresses` Array (UNNEST) ODER aus `AddressAdded` Events. Da `PersonState` bereits Adressen enthaelt, bevorzugen wir `PersonState` fuer Konsistenz.

### 3. HR Events: Routing-Bug und leere Event-Typen

**Befund:**
- Beide HR-Tabellen (`employee_events`, `org_unit_events`) enthalten **identische Daten** — alle HR-Events gemischt.
- Ursache: Iceberg-Sink Config `"iceberg.tables": "hr_raw.employee_events,hr_raw.org_unit_events"` ohne `iceberg.tables.route-field` → alle Messages gehen in BEIDE Tabellen.
- 4 Kafka-Topics existieren: `hr.v1.employee.changed`, `hr.v1.employee.state`, `hr.v1.org-unit.changed`, `hr.v1.org-unit.state`.
- Rows mit leerem `eventtype` (11 Stueck) sind State-Snapshots aus `.state` Topics — das HR-COTS-System setzt kein `eventType` Feld bei State-Events.
- `employee.updated` und `org-unit.updated` kommen aus den `.changed` Topics.

**Entscheid:** Zwei getrennte Iceberg-Sink-Connectoren fuer HR.

**Umsetzung:**
1. `iceberg-sink-hr.json` aufteilen in:
   - `iceberg-sink-hr-employee.json`: `topics.regex: "hr\\.v1\\.employee\\..*"` → `hr_raw.employee_events`
   - `iceberg-sink-hr-orgunit.json`: `topics.regex: "hr\\.v1\\.org-unit\\..*"` → `hr_raw.org_unit_events`
2. Silver-Modelle filtern zusaetzlich:
   - `hr_silver.employee`: `WHERE employeeid IS NOT NULL AND employeeid != ''`
   - `hr_silver.org_unit`: `WHERE orgunitid IS NOT NULL AND name IS NOT NULL AND (employeeid IS NULL OR employeeid = '')`
3. State-Events (leerer `eventtype`) werden als gueltiger Zustand behandelt — der `eventtype` wird in Silver nicht benoetigt, da Last-Write-Wins ueber `timestamp` laeuft.
