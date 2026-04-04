# Detaillierter Umsetzungsplan: Bronze / Silver / Gold

## Architektur-Uebersicht

```
Bronze (Raw Events)              Silver (Entity-Tabellen)            Gold (Konsumoptimiert)
Append-only, unveraendert        Dedupliziert, typisiert             Aggregiert, angereichert
Domain-owned                     Domain-owned                        Domain-owned + Cross-Domain

partner_raw.person_events   -->  partner_silver.partner         -->  partner_gold.partner_decrypted (VIEW)
                                 partner_silver.address
policy_raw.policy_events    -->  policy_silver.policy           -->  policy_gold.policy_detail
                                 policy_silver.coverage               policy_gold.portfolio_summary
claims_raw.claims_events    -->  claims_silver.claim            -->  claims_gold.claim_detail
billing_raw.billing_events  -->  billing_silver.invoice         -->  billing_gold.financial_summary
product_raw.product_events  -->  product_silver.product
hr_raw.employee_events      -->  hr_silver.employee             -->  hr_gold.org_hierarchy
hr_raw.org_unit_events      -->  hr_silver.org_unit
                                                                     analytics.management_kpi (Cross-Domain)
```

---

## Phase 1: Infrastruktur-Fixes

### 1.1 HR Iceberg-Sink aufteilen

**Problem:** `iceberg-sink-hr.json` schreibt ALLE `hr.v1.*` Events in BEIDE Tabellen (employee_events + org_unit_events). Ursache: `iceberg.tables` listet zwei Tabellen ohne `route-field`.

**Aktion:** `infra/debezium/iceberg-sink-hr.json` loeschen, zwei neue Dateien erstellen:

**`infra/debezium/iceberg-sink-hr-employee.json`**
```json
{
  "name": "iceberg-sink-hr-employee",
  "config": {
    "connector.class": "org.apache.iceberg.connect.IcebergSinkConnector",
    "tasks.max": "1",
    "topics.regex": "hr\\.v1\\.employee\\..*",
    "iceberg.catalog.type": "nessie",
    "iceberg.catalog.uri": "http://nessie:19120/api/v2",
    "iceberg.catalog.ref": "main",
    "iceberg.catalog.warehouse": "s3://warehouse/",
    "iceberg.catalog.s3.endpoint": "http://minio:9000",
    "iceberg.catalog.s3.access-key-id": "minioadmin",
    "iceberg.catalog.s3.secret-access-key": "minioadmin",
    "iceberg.catalog.s3.path-style-access": "true",
    "iceberg.catalog.io-impl": "org.apache.iceberg.aws.s3.S3FileIO",
    "iceberg.catalog.s3.region": "us-east-1",
    "iceberg.tables": "hr_raw.employee_events",
    "iceberg.tables.auto-create-enabled": "true",
    "iceberg.tables.evolve-schema-enabled": "true",
    "iceberg.control.commit.interval-ms": "10000",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false",
    "consumer.auto.offset.reset": "earliest",
    "consumer.session.timeout.ms": "60000",
    "consumer.heartbeat.interval.ms": "10000"
  }
}
```

**`infra/debezium/iceberg-sink-hr-orgunit.json`**
```json
{
  "name": "iceberg-sink-hr-orgunit",
  "config": {
    "connector.class": "org.apache.iceberg.connect.IcebergSinkConnector",
    "tasks.max": "1",
    "topics.regex": "hr\\.v1\\.org-unit\\..*",
    "iceberg.catalog.type": "nessie",
    "iceberg.catalog.uri": "http://nessie:19120/api/v2",
    "iceberg.catalog.ref": "main",
    "iceberg.catalog.warehouse": "s3://warehouse/",
    "iceberg.catalog.s3.endpoint": "http://minio:9000",
    "iceberg.catalog.s3.access-key-id": "minioadmin",
    "iceberg.catalog.s3.secret-access-key": "minioadmin",
    "iceberg.catalog.s3.path-style-access": "true",
    "iceberg.catalog.io-impl": "org.apache.iceberg.aws.s3.S3FileIO",
    "iceberg.catalog.s3.region": "us-east-1",
    "iceberg.tables": "hr_raw.org_unit_events",
    "iceberg.tables.auto-create-enabled": "true",
    "iceberg.tables.evolve-schema-enabled": "true",
    "iceberg.control.commit.interval-ms": "10000",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false",
    "consumer.auto.offset.reset": "earliest",
    "consumer.session.timeout.ms": "60000",
    "consumer.heartbeat.interval.ms": "10000"
  }
}
```

**Betroffene Dateien:**
- `infra/debezium/iceberg-sink-hr.json` --> loeschen
- `infra/debezium/iceberg-sink-hr-employee.json` --> neu
- `infra/debezium/iceberg-sink-hr-orgunit.json` --> neu
- `docker-compose.yaml` --> Volume-Mounts anpassen (iceberg-init Service)
- `infra/k8s/deploy.sh` --> ConfigMap anpassen
- `infra/k8s/init-jobs.yaml` --> for-Loop anpassen

### 1.2 SQLMesh config.yaml anpassen

Schema-Default `analytics` entfernen, da Silver/Gold in eigenen Schemas leben:

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

Kein `schema:` Default — jedes Modell definiert sein Schema im `name` Feld.

---

## Phase 2: Silver-Modelle

Alle Silver-Modelle: `kind FULL`, `cron '@hourly'`, materialisiert in Iceberg.
PII bleibt verschluesselt (vault:v1:...). Entschluesselung erst in Gold-Views.

### Verzeichnisstruktur

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
```

---

### 2.1 `silver/partner/partner.sql`

Quelle: `PersonState` Events (vollstaendiger Snapshot mit allen Feldern).

```sql
MODEL (
    name partner_silver.partner,
    kind FULL,
    cron '@hourly',
    description 'Current state of every partner. Source: PersonState events (ECST). PII fields remain Vault-encrypted (ADR-009).'
);

WITH ranked AS (
    SELECT
        personid                              AS partner_id,
        name                                  AS family_name,
        firstname                             AS first_name,
        socialsecuritynumber                  AS social_security_number,
        insurednumber                         AS insured_number,
        dateofbirth                           AS date_of_birth,
        gender,
        encrypted,
        deleted,
        CAST(timestamp AS TIMESTAMP)          AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY personid
            ORDER BY CAST(timestamp AS TIMESTAMP) DESC
        )                                     AS rn
    FROM iceberg.partner_raw.person_events
    WHERE eventtype = 'PersonState'
      AND personid IS NOT NULL
)

SELECT
    partner_id,
    family_name,
    first_name,
    social_security_number,
    insured_number,
    date_of_birth,
    gender,
    encrypted,
    COALESCE(deleted, false)                  AS deleted,
    CASE
        WHEN COALESCE(deleted, false) THEN 'DELETED'
        WHEN insured_number IS NOT NULL
             AND insured_number != '' THEN 'INSURED'
        ELSE 'PROSPECT'
    END                                       AS partner_status,
    updated_at
FROM ranked
WHERE rn = 1
```

**Spalten:**
| Spalte | Typ | Beschreibung |
|---|---|---|
| partner_id | VARCHAR | PK, UUID |
| family_name | VARCHAR | PII (vault-encrypted) |
| first_name | VARCHAR | PII (vault-encrypted) |
| social_security_number | VARCHAR | PII (vault-encrypted) |
| insured_number | VARCHAR | Klartext (kein PII, ADR-009) |
| date_of_birth | VARCHAR | PII (vault-encrypted) |
| gender | VARCHAR | MALE / FEMALE / OTHER / UNKNOWN |
| encrypted | BOOLEAN | true wenn PII-Felder verschluesselt |
| deleted | BOOLEAN | Tombstone-Marker (Crypto-Shredding) |
| partner_status | VARCHAR | INSURED / PROSPECT / DELETED |
| updated_at | TIMESTAMP | Letzter Event-Zeitpunkt |

---

### 2.2 `silver/partner/address.sql`

Quelle: `PersonState.addresses` Array (UNNEST) fuer Konsistenz mit Partner-Snapshot.

```sql
MODEL (
    name partner_silver.address,
    kind FULL,
    cron '@hourly',
    description 'Current addresses per partner, extracted from PersonState.addresses array. PII fields remain Vault-encrypted.'
);

WITH latest_state AS (
    SELECT
        personid                              AS partner_id,
        encrypted,
        addresses,
        CAST(timestamp AS TIMESTAMP)          AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY personid
            ORDER BY CAST(timestamp AS TIMESTAMP) DESC
        )                                     AS rn
    FROM iceberg.partner_raw.person_events
    WHERE eventtype = 'PersonState'
      AND personid IS NOT NULL
      AND addresses IS NOT NULL
)

SELECT
    addr.addressid                            AS address_id,
    ls.partner_id,
    addr.addresstype                          AS address_type,
    addr.street,
    addr.housenumber                          AS house_number,
    addr.postalcode                           AS postal_code,
    addr.city,
    addr.land                                 AS country,
    CAST(addr.validfrom AS DATE)              AS valid_from,
    ls.encrypted,
    ls.updated_at
FROM latest_state ls
CROSS JOIN UNNEST(ls.addresses) AS t(addr)
WHERE ls.rn = 1
```

**Spalten:**
| Spalte | Typ | Beschreibung |
|---|---|---|
| address_id | VARCHAR | PK, UUID |
| partner_id | VARCHAR | FK zu partner |
| address_type | VARCHAR | RESIDENCE / CORRESPONDENCE / DELIVERY |
| street | VARCHAR | PII (vault-encrypted) |
| house_number | VARCHAR | PII (vault-encrypted) |
| postal_code | VARCHAR | PII (vault-encrypted) |
| city | VARCHAR | PII (vault-encrypted) |
| country | VARCHAR | PII (vault-encrypted) |
| valid_from | DATE | Gueltigkeitsbeginn |
| encrypted | BOOLEAN | true wenn PII-Felder verschluesselt |
| updated_at | TIMESTAMP | Zeitpunkt des PersonState-Snapshots |

---

### 2.3 `silver/policy/policy.sql`

Quelle: `PolicyIssued` und `PolicyCancelled` Events.

```sql
MODEL (
    name policy_silver.policy,
    kind FULL,
    cron '@hourly',
    description 'Current state of every policy. Derived from PolicyIssued/PolicyCancelled events.'
);

WITH ranked AS (
    SELECT
        policyid                              AS policy_id,
        policynumber                          AS policy_number,
        partnerid                             AS partner_id,
        productid                             AS product_id,
        CAST(coveragestartdate AS DATE)       AS coverage_start_date,
        CAST(premium AS DECIMAL(15, 2))       AS premium_chf,
        eventtype,
        CAST(timestamp AS TIMESTAMP)          AS event_at,
        ROW_NUMBER() OVER (
            PARTITION BY policyid
            ORDER BY CAST(timestamp AS TIMESTAMP) DESC
        )                                     AS rn
    FROM iceberg.policy_raw.policy_events
    WHERE eventtype IN ('PolicyIssued', 'PolicyCancelled', 'PolicyChanged')
      AND policyid IS NOT NULL
),

first_issued AS (
    SELECT
        policyid                              AS policy_id,
        MIN(CAST(timestamp AS TIMESTAMP))     AS issued_at
    FROM iceberg.policy_raw.policy_events
    WHERE eventtype = 'PolicyIssued'
    GROUP BY policyid
)

SELECT
    r.policy_id,
    r.policy_number,
    r.partner_id,
    r.product_id,
    r.coverage_start_date,
    r.premium_chf,
    CASE r.eventtype
        WHEN 'PolicyCancelled' THEN 'CANCELLED'
        ELSE 'ACTIVE'
    END                                       AS policy_status,
    fi.issued_at,
    r.event_at                                AS updated_at
FROM ranked r
LEFT JOIN first_issued fi ON r.policy_id = fi.policy_id
WHERE r.rn = 1
```

**Spalten:**
| Spalte | Typ | Beschreibung |
|---|---|---|
| policy_id | VARCHAR | PK, UUID |
| policy_number | VARCHAR | Geschaeftsnummer (z.B. POL-2026-8997) |
| partner_id | VARCHAR | FK zu partner |
| product_id | VARCHAR | FK zu product |
| coverage_start_date | DATE | Deckungsbeginn |
| premium_chf | DECIMAL(15,2) | Jaehrl. Praemie in CHF |
| policy_status | VARCHAR | ACTIVE / CANCELLED |
| issued_at | TIMESTAMP | Erstausstellung |
| updated_at | TIMESTAMP | Letzter Event-Zeitpunkt |

---

### 2.4 `silver/policy/coverage.sql`

Quelle: `CoverageAdded` Events.

```sql
MODEL (
    name policy_silver.coverage,
    kind FULL,
    cron '@hourly',
    description 'All coverages added to policies.'
);

SELECT
    coverageid                                AS coverage_id,
    policyid                                  AS policy_id,
    coveragetype                              AS coverage_type,
    CAST(insuredamount AS DECIMAL(15, 2))     AS insured_amount_chf,
    CAST(timestamp AS TIMESTAMP)              AS created_at
FROM iceberg.policy_raw.policy_events
WHERE eventtype = 'CoverageAdded'
  AND coverageid IS NOT NULL
```

**Spalten:**
| Spalte | Typ | Beschreibung |
|---|---|---|
| coverage_id | VARCHAR | PK, UUID |
| policy_id | VARCHAR | FK zu policy |
| coverage_type | VARCHAR | z.B. HOUSEHOLD_CONTENTS, LIABILITY |
| insured_amount_chf | DECIMAL(15,2) | Versicherungssumme in CHF |
| created_at | TIMESTAMP | Event-Zeitpunkt |

---

### 2.5 `silver/claims/claim.sql`

Quelle: `ClaimOpened` und `ClaimSettled` Events.

```sql
MODEL (
    name claims_silver.claim,
    kind FULL,
    cron '@hourly',
    description 'Current state of every claim. Derived from ClaimOpened/ClaimSettled events.'
);

WITH ranked AS (
    SELECT
        claimid                               AS claim_id,
        claimnumber                           AS claim_number,
        policyid                              AS policy_id,
        description,
        CAST(claimdate AS DATE)               AS claim_date,
        status,
        eventtype,
        CAST(timestamp AS TIMESTAMP)          AS event_at,
        ROW_NUMBER() OVER (
            PARTITION BY claimid
            ORDER BY CAST(timestamp AS TIMESTAMP) DESC
        )                                     AS rn
    FROM iceberg.claims_raw.claims_events
    WHERE eventtype IN ('ClaimOpened', 'ClaimSettled')
      AND claimid IS NOT NULL
),

first_opened AS (
    SELECT
        claimid                               AS claim_id,
        MIN(CAST(timestamp AS TIMESTAMP))     AS opened_at
    FROM iceberg.claims_raw.claims_events
    WHERE eventtype = 'ClaimOpened'
    GROUP BY claimid
)

SELECT
    r.claim_id,
    r.claim_number,
    r.policy_id,
    r.description,
    r.claim_date,
    r.status,
    fo.opened_at,
    r.event_at                                AS updated_at
FROM ranked r
LEFT JOIN first_opened fo ON r.claim_id = fo.claim_id
WHERE r.rn = 1
```

**Spalten:**
| Spalte | Typ | Beschreibung |
|---|---|---|
| claim_id | VARCHAR | PK, UUID |
| claim_number | VARCHAR | Geschaeftsnummer (z.B. CLM-20240310-8929) |
| policy_id | VARCHAR | FK zu policy |
| description | VARCHAR | Schadenbeschreibung |
| claim_date | DATE | Schadendatum |
| status | VARCHAR | OPEN / IN_REVIEW / SETTLED / REJECTED |
| opened_at | TIMESTAMP | Ersterfassung |
| updated_at | TIMESTAMP | Letzter Event-Zeitpunkt |

---

### 2.6 `silver/billing/invoice.sql`

Quelle: `InvoiceCreated`, `PaymentReceived`, `DunningInitiated`, `PayoutTriggered` Events.

```sql
MODEL (
    name billing_silver.invoice,
    kind FULL,
    cron '@hourly',
    description 'Current state of every invoice. Status derived from latest billing event.'
);

WITH ranked AS (
    SELECT
        invoiceid                             AS invoice_id,
        invoicenumber                         AS invoice_number,
        policyid                              AS policy_id,
        partnerid                             AS partner_id,
        policynumber                          AS policy_number,
        billingcycle                           AS billing_cycle,
        CAST(totalamount AS DECIMAL(15, 2))   AS total_amount_chf,
        CAST(duedate AS DATE)                 AS due_date,
        eventtype,
        CAST(amountpaid AS DECIMAL(15, 2))    AS amount_paid_chf,
        CAST(paidat AS DATE)                  AS paid_at,
        dunninglevel                          AS dunning_level,
        CAST(timestamp AS TIMESTAMP)          AS event_at,
        ROW_NUMBER() OVER (
            PARTITION BY invoiceid
            ORDER BY CAST(timestamp AS TIMESTAMP) DESC
        )                                     AS rn
    FROM iceberg.billing_raw.billing_events
    WHERE eventtype IN ('InvoiceCreated', 'PaymentReceived', 'DunningInitiated', 'PayoutTriggered')
      AND invoiceid IS NOT NULL
),

first_created AS (
    SELECT
        invoiceid                             AS invoice_id,
        MIN(CAST(timestamp AS TIMESTAMP))     AS created_at
    FROM iceberg.billing_raw.billing_events
    WHERE eventtype = 'InvoiceCreated'
    GROUP BY invoiceid
)

SELECT
    r.invoice_id,
    r.invoice_number,
    r.policy_id,
    r.partner_id,
    r.policy_number,
    r.billing_cycle,
    r.total_amount_chf,
    r.due_date,
    CASE r.eventtype
        WHEN 'PaymentReceived'  THEN 'PAID'
        WHEN 'DunningInitiated' THEN 'OVERDUE'
        WHEN 'PayoutTriggered'  THEN 'PAYOUT'
        ELSE                         'OPEN'
    END                                       AS invoice_status,
    r.amount_paid_chf,
    r.paid_at,
    r.dunning_level,
    fc.created_at,
    r.event_at                                AS updated_at
FROM ranked r
LEFT JOIN first_created fc ON r.invoice_id = fc.invoice_id
WHERE r.rn = 1
```

**Spalten:**
| Spalte | Typ | Beschreibung |
|---|---|---|
| invoice_id | VARCHAR | PK, UUID |
| invoice_number | VARCHAR | Geschaeftsnummer |
| policy_id | VARCHAR | FK zu policy |
| partner_id | VARCHAR | FK zu partner |
| policy_number | VARCHAR | Policennummer (denormalisiert) |
| billing_cycle | VARCHAR | ANNUAL / QUARTERLY / MONTHLY |
| total_amount_chf | DECIMAL(15,2) | Rechnungsbetrag |
| due_date | DATE | Faelligkeitsdatum |
| invoice_status | VARCHAR | OPEN / PAID / OVERDUE / PAYOUT |
| amount_paid_chf | DECIMAL(15,2) | Bezahlter Betrag (null wenn offen) |
| paid_at | DATE | Zahlungsdatum |
| dunning_level | VARCHAR | REMINDER / FIRST_NOTICE / ... |
| created_at | TIMESTAMP | Rechnungserstellung |
| updated_at | TIMESTAMP | Letzter Event-Zeitpunkt |

---

### 2.7 `silver/product/product.sql`

Quelle: `ProductState` Events (ECST Snapshot, analog zu PersonState).

```sql
MODEL (
    name product_silver.product,
    kind FULL,
    cron '@hourly',
    description 'Current state of every product. Source: ProductState events (ECST).'
);

WITH ranked AS (
    SELECT
        productid                             AS product_id,
        name                                  AS product_name,
        productline                           AS product_line,
        CAST(basepremium AS DECIMAL(15, 2))   AS base_premium_chf,
        status,
        COALESCE(deleted, false)              AS deleted,
        CAST(timestamp AS TIMESTAMP)          AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY productid
            ORDER BY CAST(timestamp AS TIMESTAMP) DESC
        )                                     AS rn
    FROM iceberg.product_raw.product_events
    WHERE eventtype IN ('ProductState', 'ProductDefined', 'ProductDeprecated')
      AND productid IS NOT NULL
)

SELECT
    product_id,
    product_name,
    product_line,
    base_premium_chf,
    status,
    deleted,
    CASE
        WHEN deleted THEN true
        WHEN status = 'DEPRECATED' THEN true
        ELSE false
    END                                       AS is_deprecated,
    updated_at
FROM ranked
WHERE rn = 1
```

**Spalten:**
| Spalte | Typ | Beschreibung |
|---|---|---|
| product_id | VARCHAR | PK, UUID |
| product_name | VARCHAR | Produktname (z.B. Hausrat Basis) |
| product_line | VARCHAR | HOUSEHOLD_CONTENTS / LIABILITY / VEHICLE / TRAVEL |
| base_premium_chf | DECIMAL(15,2) | Basisraemie |
| status | VARCHAR | ACTIVE / DEPRECATED |
| deleted | BOOLEAN | Tombstone-Marker |
| is_deprecated | BOOLEAN | Abgeleitet aus status/deleted |
| updated_at | TIMESTAMP | Letzter Event-Zeitpunkt |

---

### 2.8 `silver/hr/employee.sql`

Quelle: `hr_raw.employee_events`. Filtert auf Rows mit `employeeid` (schliesst OrgUnit-Events aus, die wegen des Routing-Bugs in derselben Tabelle landen).

```sql
MODEL (
    name hr_silver.employee,
    kind FULL,
    cron '@hourly',
    description 'Current state of every employee from the HR system.'
);

WITH ranked AS (
    SELECT
        employeeid                            AS employee_id,
        externalid                            AS external_id,
        firstname                             AS first_name,
        lastname                              AS last_name,
        email,
        jobtitle                              AS job_title,
        department,
        orgunitid                             AS org_unit_id,
        CAST(entrydate AS DATE)               AS entry_date,
        COALESCE(active, true)                AS active,
        COALESCE(deleted, false)              AS deleted,
        version,
        CAST(timestamp AS TIMESTAMP)          AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY employeeid
            ORDER BY CAST(timestamp AS TIMESTAMP) DESC NULLS LAST,
                     version DESC NULLS LAST
        )                                     AS rn
    FROM iceberg.hr_raw.employee_events
    WHERE employeeid IS NOT NULL
      AND employeeid != ''
)

SELECT
    employee_id,
    external_id,
    first_name,
    last_name,
    TRIM(COALESCE(first_name, '') || ' ' || COALESCE(last_name, '')) AS full_name,
    email,
    job_title,
    department,
    org_unit_id,
    entry_date,
    active,
    deleted,
    CASE
        WHEN deleted THEN 'DELETED'
        WHEN NOT active THEN 'INACTIVE'
        ELSE 'ACTIVE'
    END                                       AS employment_status,
    updated_at
FROM ranked
WHERE rn = 1
```

**Spalten:**
| Spalte | Typ | Beschreibung |
|---|---|---|
| employee_id | VARCHAR | PK, UUID (deterministisch aus externalId) |
| external_id | VARCHAR | Original-ID aus HR-COTS |
| first_name | VARCHAR | Vorname |
| last_name | VARCHAR | Nachname |
| full_name | VARCHAR | Abgeleitet: first_name + last_name |
| email | VARCHAR | E-Mail |
| job_title | VARCHAR | Stellenbezeichnung |
| department | VARCHAR | Abteilung |
| org_unit_id | VARCHAR | FK zu org_unit |
| entry_date | DATE | Eintrittsdatum |
| active | BOOLEAN | Aktiv-Flag |
| deleted | BOOLEAN | GDPR-Loeschmarker |
| employment_status | VARCHAR | ACTIVE / INACTIVE / DELETED |
| updated_at | TIMESTAMP | Letzter Event-Zeitpunkt |

---

### 2.9 `silver/hr/org_unit.sql`

Quelle: `hr_raw.org_unit_events`. Filtert auf Rows mit `orgunitid` und `name` (schliesst Employee-Events aus).

```sql
MODEL (
    name hr_silver.org_unit,
    kind FULL,
    cron '@hourly',
    description 'Current state of every organizational unit from the HR system.'
);

WITH ranked AS (
    SELECT
        orgunitid                             AS org_unit_id,
        externalid                            AS external_id,
        name,
        parentorgunitid                       AS parent_org_unit_id,
        CAST(level AS INTEGER)                AS level,
        COALESCE(active, true)                AS active,
        COALESCE(deleted, false)              AS deleted,
        version,
        CAST(timestamp AS TIMESTAMP)          AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY orgunitid
            ORDER BY CAST(timestamp AS TIMESTAMP) DESC NULLS LAST,
                     version DESC NULLS LAST
        )                                     AS rn
    FROM iceberg.hr_raw.org_unit_events
    WHERE orgunitid IS NOT NULL
      AND name IS NOT NULL
      AND (employeeid IS NULL OR employeeid = '')
)

SELECT
    org_unit_id,
    external_id,
    name,
    parent_org_unit_id,
    level,
    active,
    deleted,
    updated_at
FROM ranked
WHERE rn = 1
```

**Spalten:**
| Spalte | Typ | Beschreibung |
|---|---|---|
| org_unit_id | VARCHAR | PK (z.B. OU-ROOT, OU-IT-001) |
| external_id | VARCHAR | Original-ID aus HR-COTS |
| name | VARCHAR | Name der Organisationseinheit |
| parent_org_unit_id | VARCHAR | FK zu uebergeordneter OrgUnit |
| level | INTEGER | Hierarchiestufe |
| active | BOOLEAN | Aktiv-Flag |
| deleted | BOOLEAN | Loeschmarker |
| updated_at | TIMESTAMP | Letzter Event-Zeitpunkt |

---

## Phase 3: Gold-Modelle

Gold-Tabellen sind entweder materialisiert (`kind FULL`) fuer Performance oder Views (`kind VIEW`) fuer on-demand Entschluesselung.

### Verzeichnisstruktur

```
infra/sqlmesh/models/
  gold/
    partner/
      partner_decrypted.sql        (VIEW - on-demand PII-Entschluesselung)
    policy/
      policy_detail.sql            (FULL - Cross-Domain)
      portfolio_summary.sql        (FULL - Aggregation)
    claims/
      claim_detail.sql             (FULL - Cross-Domain)
    billing/
      financial_summary.sql        (FULL - Aggregation)
    analytics/
      management_kpi.sql           (FULL - Cross-Domain KPIs)
      org_hierarchy.sql            (FULL - HR Hierarchie)
```

---

### 3.1 `gold/partner/partner_decrypted.sql`

VIEW mit on-demand PII-Entschluesselung via `vault_decrypt()`. Nicht materialisiert, damit Crypto-Shredding wirksam bleibt.

```sql
MODEL (
    name partner_gold.partner_decrypted,
    kind VIEW,
    description 'Partner with decrypted PII fields (on-demand via Vault Transit). VIEW ensures crypto-shredding remains effective.'
);

SELECT
    partner_id,
    CASE WHEN encrypted
         THEN vault_decrypt(partner_id, family_name)
         ELSE family_name
    END                                       AS family_name,
    CASE WHEN encrypted
         THEN vault_decrypt(partner_id, first_name)
         ELSE first_name
    END                                       AS first_name,
    CASE WHEN encrypted
         THEN TRIM(
             COALESCE(vault_decrypt(partner_id, first_name), '') || ' ' ||
             COALESCE(vault_decrypt(partner_id, family_name), '')
         )
         ELSE TRIM(COALESCE(first_name, '') || ' ' || COALESCE(family_name, ''))
    END                                       AS full_name,
    CASE WHEN encrypted
         THEN vault_decrypt(partner_id, social_security_number)
         ELSE social_security_number
    END                                       AS social_security_number,
    insured_number,
    CASE WHEN encrypted
         THEN CAST(vault_decrypt(partner_id, date_of_birth) AS DATE)
         ELSE CAST(date_of_birth AS DATE)
    END                                       AS date_of_birth,
    gender,
    partner_status,
    updated_at
FROM partner_silver.partner
WHERE NOT deleted
```

---

### 3.2 `gold/claims/claim_detail.sql`

Cross-Domain: Claim + Policy + Partner(encrypted) + Adresse(encrypted).

```sql
MODEL (
    name claims_gold.claim_detail,
    kind FULL,
    cron '@hourly',
    description 'Claims enriched with policy, partner insuredNumber, and product info.'
);

SELECT
    c.claim_id,
    c.claim_number,
    c.policy_id,
    p.policy_number,
    p.partner_id,
    pa.insured_number,
    pr.product_name,
    pr.product_line,
    c.description,
    c.claim_date,
    c.status,
    c.opened_at,
    c.updated_at
FROM claims_silver.claim c
LEFT JOIN policy_silver.policy p   ON c.policy_id = p.policy_id
LEFT JOIN partner_silver.partner pa ON p.partner_id = pa.partner_id
LEFT JOIN product_silver.product pr ON p.product_id = pr.product_id
```

---

### 3.3 `gold/billing/financial_summary.sql`

Aggregation: Invoices pro Policy.

```sql
MODEL (
    name billing_gold.financial_summary,
    kind FULL,
    cron '@daily',
    description 'Invoice aggregation per policy for financial reporting.'
);

SELECT
    p.policy_id,
    p.policy_number,
    p.partner_id,
    p.product_id,
    p.premium_chf                                    AS annual_premium_chf,
    p.policy_status,
    p.issued_at,
    COUNT(i.invoice_id)                              AS total_invoices,
    COUNT(CASE WHEN i.invoice_status = 'OPEN' THEN 1 END)    AS open_invoices,
    COUNT(CASE WHEN i.invoice_status = 'PAID' THEN 1 END)    AS paid_invoices,
    COUNT(CASE WHEN i.invoice_status = 'OVERDUE' THEN 1 END) AS overdue_invoices,
    COALESCE(SUM(i.total_amount_chf), 0)             AS total_billed_chf,
    COALESCE(SUM(CASE WHEN i.invoice_status = 'PAID'
        THEN i.total_amount_chf ELSE 0 END), 0)     AS total_collected_chf,
    COALESCE(SUM(CASE WHEN i.invoice_status IN ('OPEN', 'OVERDUE')
        THEN i.total_amount_chf ELSE 0 END), 0)     AS total_outstanding_chf,
    CASE
        WHEN COUNT(CASE WHEN i.invoice_status = 'OVERDUE' THEN 1 END) > 0 THEN 'AT_RISK'
        WHEN COUNT(CASE WHEN i.invoice_status = 'OPEN' THEN 1 END) > 0    THEN 'PENDING'
        ELSE 'CURRENT'
    END                                              AS collection_status
FROM policy_silver.policy p
LEFT JOIN billing_silver.invoice i ON p.policy_id = i.policy_id
GROUP BY p.policy_id, p.policy_number, p.partner_id, p.product_id,
         p.premium_chf, p.policy_status, p.issued_at
```

---

### 3.4 `gold/policy/policy_detail.sql`

Cross-Domain: Policy + Partner(insuredNumber) + Product.

```sql
MODEL (
    name policy_gold.policy_detail,
    kind FULL,
    cron '@hourly',
    description 'Policies enriched with partner insuredNumber and product info.'
);

SELECT
    p.policy_id,
    p.policy_number,
    p.policy_status,
    pa.partner_id,
    pa.insured_number,
    pa.partner_status,
    pr.product_name,
    pr.product_line,
    p.coverage_start_date,
    p.premium_chf,
    pr.base_premium_chf,
    ROUND(
        (p.premium_chf - pr.base_premium_chf) / NULLIF(pr.base_premium_chf, 0) * 100, 1
    )                                         AS premium_delta_pct,
    p.issued_at,
    p.updated_at
FROM policy_silver.policy p
JOIN partner_silver.partner pa ON p.partner_id = pa.partner_id
JOIN product_silver.product pr ON p.product_id = pr.product_id
```

---

### 3.5 `gold/policy/portfolio_summary.sql`

Aggregation: Aktive Policen pro Produkt.

```sql
MODEL (
    name policy_gold.portfolio_summary,
    kind FULL,
    cron '@hourly',
    description 'Active policies grouped by product line and product.'
);

SELECT
    pr.product_line,
    pr.product_name,
    COUNT(p.policy_id)                        AS active_policies,
    SUM(p.premium_chf)                        AS total_premium_chf,
    ROUND(AVG(p.premium_chf), 2)              AS avg_premium_chf,
    MIN(p.coverage_start_date)                AS earliest_coverage_start,
    MAX(p.coverage_start_date)                AS latest_coverage_start
FROM policy_silver.policy p
JOIN product_silver.product pr ON p.product_id = pr.product_id
WHERE p.policy_status = 'ACTIVE'
  AND NOT pr.is_deprecated
GROUP BY pr.product_line, pr.product_name
```

---

### 3.6 `gold/analytics/management_kpi.sql`

Cross-Domain KPI-Report.

```sql
MODEL (
    name analytics.management_kpi,
    kind FULL,
    cron '@daily',
    description 'Cross-domain management KPI report.'
);

SELECT
    CURRENT_DATE                                                             AS report_date,
    COUNT(DISTINCT pol.partner_id)                                           AS total_partners,
    COUNT(DISTINCT CASE WHEN pol.policy_status = 'ACTIVE'
        THEN pol.policy_id END)                                              AS active_policies,
    SUM(CASE WHEN pol.policy_status = 'ACTIVE'
        THEN pol.premium_chf ELSE 0 END)                                     AS total_portfolio_premium_chf,
    AVG(CASE WHEN pol.policy_status = 'ACTIVE'
        THEN pol.premium_chf END)                                            AS avg_premium_chf,
    COUNT(DISTINCT pr.product_id)                                            AS active_products,
    COUNT(DISTINCT CASE WHEN pol.coverage_start_date >= CURRENT_DATE - INTERVAL '30' DAY
        THEN pol.policy_id END)                                              AS new_policies_last_30d,
    COUNT(DISTINCT CASE WHEN pol.policy_status = 'CANCELLED'
        AND pol.updated_at >= CAST(CURRENT_DATE - INTERVAL '30' DAY AS TIMESTAMP)
        THEN pol.policy_id END)                                              AS cancelled_last_30d
FROM policy_silver.policy pol
LEFT JOIN partner_silver.partner pa  ON pol.partner_id = pa.partner_id
LEFT JOIN product_silver.product pr  ON pol.product_id = pr.product_id
```

---

### 3.7 `gold/analytics/org_hierarchy.sql`

HR Organisationshierarchie mit Mitarbeiterzahlen.

```sql
MODEL (
    name analytics.org_hierarchy,
    kind FULL,
    cron '@hourly',
    description 'Flattened organizational hierarchy with employee counts per unit.'
);

SELECT
    ou.org_unit_id,
    ou.name                                     AS org_unit_name,
    ou.level,
    parent.name                                 AS parent_name,
    COUNT(emp.employee_id)                      AS employee_count,
    COUNT(CASE WHEN emp.employment_status = 'ACTIVE' THEN 1 END) AS active_employee_count
FROM hr_silver.org_unit ou
LEFT JOIN hr_silver.org_unit parent
    ON ou.parent_org_unit_id = parent.org_unit_id
LEFT JOIN hr_silver.employee emp
    ON emp.org_unit_id = ou.org_unit_id
    AND NOT emp.deleted
WHERE NOT ou.deleted
GROUP BY
    ou.org_unit_id, ou.name, ou.level, parent.name
```

---

## Phase 4: Superset-Datasets aktualisieren

`infra/superset/superset-init.sh` — Datasets auf Silver/Gold umstellen:

| Dataset Name | Alt (Bronze direkt) | Neu (Silver/Gold) |
|---|---|---|
| Claims Events | `SELECT eventtype, ... FROM claims_raw.claims_events` | `SELECT * FROM claims_silver.claim` |
| Policy Events | `SELECT eventtype, ... FROM policy_raw.policy_events` | `SELECT * FROM policy_silver.policy` |
| Billing Events | `SELECT eventtype, ... FROM billing_raw.billing_events` | `SELECT * FROM billing_silver.invoice` |
| Partner Events | `SELECT eventtype, ... FROM partner_raw.person_events` | `SELECT * FROM partner_silver.partner` |
| Product Events | `SELECT eventtype, ... FROM product_raw.product_events` | `SELECT * FROM product_silver.product` |

Charts anpassen:
- "Schaeden nach Ereignistyp" → "Schaeden nach Status" (`GROUP BY status`)
- "Policen nach Ereignistyp" → "Policen nach Status" (`GROUP BY policy_status`)
- Neue Charts auf Gold-Tabellen (claim_detail, policy_detail, financial_summary)

---

## Phase 5: Soda-Checks aktualisieren

`infra/soda/checks/` — Checks auf Silver-Tabellen umstellen:

**Zu aendernde Referenzen:**
| Alt | Neu |
|---|---|
| `analytics.dim_partner` | `partner_silver.partner` |
| `analytics.dim_employee` | `hr_silver.employee` |
| `analytics.dim_org_unit` | `hr_silver.org_unit` |
| `analytics.mart_org_hierarchy` | `analytics.org_hierarchy` |

Bronze-Checks (`*_raw.*_events`) bleiben, pruefen aber die tatsaechlichen Spaltennamen:
- `event_id` → `eventid`
- `consumed_at` → entfernen (Spalte existiert nicht)
- `invoice_id` → `invoiceid`
- `person_id` → `personid`
- `policy_id` → `policyid`
- `product_id` → `productid`
- `employee_id` → `employeeid`
- `org_unit_id` → `orgunitid`

---

## Phase 6: Alte Modelle loeschen

Alle Dateien unter `infra/sqlmesh/models/staging/` und `infra/sqlmesh/models/marts/` loeschen:

**Staging (8 Dateien):**
- `stg_address_events.sql` → ersetzt durch `partner_silver.address`
- `stg_billing_events.sql` → ersetzt durch `billing_silver.invoice`
- `stg_claims_events.sql` → ersetzt durch `claims_silver.claim`
- `stg_employee_events.sql` → ersetzt durch `hr_silver.employee`
- `stg_org_unit_events.sql` → ersetzt durch `hr_silver.org_unit`
- `stg_person_events.sql` → ersetzt durch `partner_silver.partner`
- `stg_policy_events.sql` → ersetzt durch `policy_silver.policy`
- `stg_product_events.sql` → ersetzt durch `product_silver.product`

**Marts (13 Dateien):**
- `dim_employee.sql` → ersetzt durch `hr_silver.employee`
- `dim_org_unit.sql` → ersetzt durch `hr_silver.org_unit`
- `dim_partner.sql` → ersetzt durch `partner_silver.partner`
- `dim_partner_address.sql` → ersetzt durch `partner_silver.address`
- `dim_product.sql` → ersetzt durch `product_silver.product`
- `fact_claims.sql` → ersetzt durch `claims_silver.claim`
- `fact_invoices.sql` → ersetzt durch `billing_silver.invoice`
- `fact_policies.sql` → ersetzt durch `policy_silver.policy`
- `mart_financial_summary.sql` → ersetzt durch `billing_gold.financial_summary`
- `mart_management_kpi.sql` → ersetzt durch `analytics.management_kpi`
- `mart_org_hierarchy.sql` → ersetzt durch `analytics.org_hierarchy`
- `mart_policy_detail.sql` → ersetzt durch `policy_gold.policy_detail`
- `mart_portfolio_summary.sql` → ersetzt durch `policy_gold.portfolio_summary`

**Tests (3 Dateien):** Beibehalten, aber Referenzen anpassen:
- `assert_no_orphan_employees.sql` → `hr_silver.employee` / `hr_silver.org_unit`
- `assert_no_orphan_policies.sql` → `policy_silver.policy` / `partner_silver.partner`
- `assert_premium_positive.sql` → `policy_silver.policy`

---

## Umsetzungsreihenfolge

```
Phase 1: Infrastruktur-Fixes
  1.1  HR Iceberg-Sink aufteilen (2 neue JSON, docker-compose, k8s)
  1.2  SQLMesh config.yaml anpassen (Schema-Default entfernen)

Phase 2: Silver-Modelle erstellen (9 neue SQL-Dateien)
  2.1  partner_silver.partner
  2.2  partner_silver.address
  2.3  policy_silver.policy
  2.4  policy_silver.coverage
  2.5  claims_silver.claim
  2.6  billing_silver.invoice
  2.7  product_silver.product
  2.8  hr_silver.employee
  2.9  hr_silver.org_unit

Phase 3: Gold-Modelle erstellen (7 neue SQL-Dateien)
  3.1  partner_gold.partner_decrypted (VIEW)
  3.2  claims_gold.claim_detail
  3.3  billing_gold.financial_summary
  3.4  policy_gold.policy_detail
  3.5  policy_gold.portfolio_summary
  3.6  analytics.management_kpi
  3.7  analytics.org_hierarchy

Phase 4: Superset-Datasets aktualisieren
  4.1  superset-init.sh Datasets und Charts anpassen

Phase 5: Soda-Checks aktualisieren
  5.1  Bronze-Checks: Spaltennamen fixen
  5.2  Silver-Checks: Referenzen auf neue Tabellen

Phase 6: Alte Modelle loeschen
  6.1  staging/ Verzeichnis loeschen (8 Dateien)
  6.2  marts/ Verzeichnis loeschen (13 Dateien)
  6.3  tests/ anpassen (3 Dateien)

Phase 7: Deployment & Verifikation
  7.1  ./deploy-compose.sh -d --test-data
  7.2  sqlmesh plan --auto-apply
  7.3  Trino: Silver-Tabellen pruefen
  7.4  Superset: Charts pruefen
  7.5  Soda: Quality-Checks ausfuehren
```

---

## Dateien-Uebersicht (Aenderungen)

| Aktion | Datei | Phase |
|---|---|---|
| LOESCHEN | `infra/debezium/iceberg-sink-hr.json` | 1.1 |
| NEU | `infra/debezium/iceberg-sink-hr-employee.json` | 1.1 |
| NEU | `infra/debezium/iceberg-sink-hr-orgunit.json` | 1.1 |
| AENDERN | `docker-compose.yaml` (iceberg-init volumes) | 1.1 |
| AENDERN | `infra/k8s/deploy.sh` (ConfigMap) | 1.1 |
| AENDERN | `infra/k8s/init-jobs.yaml` (for-loop) | 1.1 |
| AENDERN | `infra/sqlmesh/config.yaml` | 1.2 |
| NEU | `infra/sqlmesh/models/silver/partner/partner.sql` | 2.1 |
| NEU | `infra/sqlmesh/models/silver/partner/address.sql` | 2.2 |
| NEU | `infra/sqlmesh/models/silver/policy/policy.sql` | 2.3 |
| NEU | `infra/sqlmesh/models/silver/policy/coverage.sql` | 2.4 |
| NEU | `infra/sqlmesh/models/silver/claims/claim.sql` | 2.5 |
| NEU | `infra/sqlmesh/models/silver/billing/invoice.sql` | 2.6 |
| NEU | `infra/sqlmesh/models/silver/product/product.sql` | 2.7 |
| NEU | `infra/sqlmesh/models/silver/hr/employee.sql` | 2.8 |
| NEU | `infra/sqlmesh/models/silver/hr/org_unit.sql` | 2.9 |
| NEU | `infra/sqlmesh/models/gold/partner/partner_decrypted.sql` | 3.1 |
| NEU | `infra/sqlmesh/models/gold/claims/claim_detail.sql` | 3.2 |
| NEU | `infra/sqlmesh/models/gold/billing/financial_summary.sql` | 3.3 |
| NEU | `infra/sqlmesh/models/gold/policy/policy_detail.sql` | 3.4 |
| NEU | `infra/sqlmesh/models/gold/policy/portfolio_summary.sql` | 3.5 |
| NEU | `infra/sqlmesh/models/gold/analytics/management_kpi.sql` | 3.6 |
| NEU | `infra/sqlmesh/models/gold/analytics/org_hierarchy.sql` | 3.7 |
| AENDERN | `infra/superset/superset-init.sh` | 4.1 |
| AENDERN | `infra/soda/checks/billing.yml` | 5.1 |
| AENDERN | `infra/soda/checks/hr.yml` | 5.1-5.2 |
| AENDERN | `infra/soda/checks/partner.yml` | 5.1-5.2 |
| AENDERN | `infra/soda/checks/policy.yml` | 5.1 |
| AENDERN | `infra/soda/checks/product.yml` | 5.1 |
| LOESCHEN | `infra/sqlmesh/models/staging/` (8 Dateien) | 6.1 |
| LOESCHEN | `infra/sqlmesh/models/marts/` (13 Dateien) | 6.2 |
| AENDERN | `infra/sqlmesh/models/tests/` (3 Dateien) | 6.3 |
