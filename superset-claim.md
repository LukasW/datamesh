# Anleitung: Schadenfall-Report in Apache Superset

> **Ziel:** Ein Report in Superset, der pro Ort (Stadt) die **Anzahl Schadenfälle** und die **Summe der Auszahlungskosten** anzeigt.

---

## Voraussetzungen

Bevor der Report in Superset erstellt werden kann, müssen die benötigten Daten im Analytics-Layer (SQLMesh → Trino) verfügbar sein. Aktuell fehlen:

1. **Staging-Modell für Claims-Events** (`stg_claims_events`)
2. **Staging-Modell für Partner-Adressen** (`stg_address_events`)
3. **Erweiterung `dim_partner`** um `city` und `postalCode`
4. **Fact-Tabelle `fact_claims`** mit JOIN auf Partner-Adresse

---

## Teil 1: SQLMesh-Modelle vorbereiten

### 1.1 Staging: Claims-Events

Datei: `infra/sqlmesh/models/staging/stg_claims_events.sql`

```sql
MODEL (
    name analytics.stg_claims_events,
    kind VIEW,
    description 'Staging model: parse raw claims event JSON into typed columns. One row per event.'
);

SELECT
    id                                                                          AS surrogate_key,
    event_id,
    topic,
    event_type,
    json_extract_scalar(payload, '$.claimId')                                   AS claim_id,
    json_extract_scalar(payload, '$.policyId')                                  AS policy_id,
    json_extract_scalar(payload, '$.claimNumber')                               AS claim_number,
    json_extract_scalar(payload, '$.description')                               AS description,
    CAST(json_extract_scalar(payload, '$.claimDate') AS DATE)                   AS claim_date,
    json_extract_scalar(payload, '$.status')                                    AS status,
    CAST(json_extract_scalar(payload, '$.settlementAmount') AS DECIMAL(15, 2))  AS settlement_amount_chf,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)              AS event_at,
    consumed_at
FROM iceberg.claims_raw.claims_events
WHERE event_type IN ('ClaimOpened', 'ClaimSettled')
  AND json_extract_scalar(payload, '$.claimId') IS NOT NULL
```

### 1.2 Staging: Partner-Adressen

Datei: `infra/sqlmesh/models/staging/stg_address_events.sql`

```sql
MODEL (
    name analytics.stg_address_events,
    kind VIEW,
    description 'Staging model: parse raw address-added events. One row per address event.'
);

SELECT
    id                                                                    AS surrogate_key,
    event_id,
    person_id,
    json_extract_scalar(payload, '$.addressId')                           AS address_id,
    json_extract_scalar(payload, '$.addressType')                         AS address_type,
    json_extract_scalar(payload, '$.street')                              AS street,
    json_extract_scalar(payload, '$.houseNumber')                         AS house_number,
    json_extract_scalar(payload, '$.postalCode')                          AS postal_code,
    json_extract_scalar(payload, '$.city')                                AS city,
    json_extract_scalar(payload, '$.land')                                AS country,
    CAST(json_extract_scalar(payload, '$.validFrom') AS DATE)             AS valid_from,
    CAST(json_extract_scalar(payload, '$.validTo') AS DATE)               AS valid_to,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)        AS event_at,
    consumed_at
FROM iceberg.partner_raw.person_events
WHERE event_type = 'AddressAdded'
  AND person_id IS NOT NULL
```

> **Hinweis:** Address-Events landen im selben Iceberg-Topic `partner_raw.person_events` wie Person-Events, da der Debezium-Sink alle `person.v1.*`-Topics in eine Tabelle schreibt. Je nach Sink-Konfiguration muss ggf. ein separater Connector geprüft werden.

### 1.3 Erweiterung: `dim_partner` um Adresse

Alternativ kann ein separates `dim_partner_address` erstellt werden, um die Hexagonal-Trennung beizubehalten:

Datei: `infra/sqlmesh/models/marts/dim_partner_address.sql`

```sql
MODEL (
    name analytics.dim_partner_address,
    kind FULL,
    cron '@hourly',
    description 'Current RESIDENCE address per partner. Last-write-wins for address events.'
);

WITH ranked AS (
    SELECT
        person_id,
        postal_code,
        city,
        event_at,
        ROW_NUMBER() OVER (
            PARTITION BY person_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        ) AS rn
    FROM analytics.stg_address_events
    WHERE address_type = 'RESIDENCE'
)

SELECT
    person_id,
    postal_code,
    city
FROM ranked
WHERE rn = 1
```

### 1.4 Fact-Tabelle: `fact_claims`

Datei: `infra/sqlmesh/models/marts/fact_claims.sql`

```sql
MODEL (
    name analytics.fact_claims,
    kind FULL,
    cron '@hourly',
    description 'One row per claim with current status, settlement amount, and partner city.'
);

WITH latest_event AS (
    SELECT
        claim_id,
        claim_number,
        policy_id,
        description,
        claim_date,
        status,
        settlement_amount_chf,
        event_at,
        ROW_NUMBER() OVER (
            PARTITION BY claim_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        ) AS rn
    FROM analytics.stg_claims_events
)

SELECT
    le.claim_id,
    le.claim_number,
    le.policy_id,
    fp.partner_id,
    le.description,
    le.claim_date,
    le.status,
    le.settlement_amount_chf,
    pa.postal_code,
    pa.city,
    le.event_at                  AS last_event_at
FROM latest_event le
LEFT JOIN analytics.fact_policies  fp ON le.policy_id = fp.policy_id
LEFT JOIN analytics.dim_partner_address pa ON fp.partner_id = pa.person_id
WHERE le.rn = 1
```

### 1.5 SQLMesh ausführen

```bash
cd infra/sqlmesh
sqlmesh plan --auto-apply
```

Anschliessend prüfen, ob die Tabellen in Trino verfügbar sind:

```sql
-- In Trino CLI oder Superset SQL Lab
SELECT city, COUNT(*) AS claim_count, SUM(settlement_amount_chf) AS total_cost
FROM iceberg.analytics.fact_claims
WHERE status = 'SETTLED'
GROUP BY city
ORDER BY total_cost DESC;
```

---

## Teil 2: Report in Apache Superset erstellen

### 2.1 Superset öffnen

1. Browser öffnen: **http://localhost:8088**
2. Login: `admin` / `admin` (Standard-Dev-Credentials)

### 2.2 Dataset registrieren

1. Im Menü: **Data → Datasets → + Dataset**
2. Konfiguration:
   - **Database:** `Trino Iceberg` (vorregistriert)
   - **Schema:** `analytics`
   - **Table:** `fact_claims`
3. **Add** klicken

### 2.3 Chart erstellen – Tabelle (Übersicht)

1. Im Menü: **Charts → + Chart**
2. Dataset: `fact_claims` auswählen
3. Chart-Typ: **Table** wählen

#### Konfiguration:

| Feld | Wert |
|---|---|
| **Dimensions** | `city` |
| **Metrics** | `COUNT(*)` → Alias: `Anzahl Schadenfälle` |
| | `SUM(settlement_amount_chf)` → Alias: `Gesamtkosten (CHF)` |
| **Filters** | `status = 'SETTLED'` (nur abgeschlossene Fälle mit Auszahlung) |
| **Sort By** | `Gesamtkosten (CHF)` DESC |

4. **Run Query** klicken → Vorschau prüfen
5. **Save** → Name: `Schadenfälle pro Ort`

### 2.4 Chart erstellen – Balkendiagramm (Visualisierung)

1. **Charts → + Chart**
2. Dataset: `fact_claims`
3. Chart-Typ: **Bar Chart** wählen

#### Konfiguration:

| Feld | Wert |
|---|---|
| **X-Axis** | `city` |
| **Metrics** | `COUNT(*)` (linke Y-Achse) |
| | `SUM(settlement_amount_chf)` (rechte Y-Achse) |
| **Filters** | `status = 'SETTLED'` |
| **Sort** | By metric, descending |
| **Row Limit** | `20` (Top 20 Orte) |

4. **Run Query** → **Save** → Name: `Schadenfälle pro Ort – Balkendiagramm`

### 2.5 Dashboard zusammenstellen

1. Im Menü: **Dashboards → + Dashboard**
2. Name: `Schadenanalyse nach Ort`
3. **Edit Dashboard** klicken
4. Aus der Chart-Liste rechts beide Charts per Drag & Drop ins Layout ziehen:
   - Oben: Balkendiagramm (volle Breite)
   - Unten: Tabelle (volle Breite)
5. Optional: **Filter Box** hinzufügen für `status` und `claim_date`-Range
6. **Save**

### 2.6 Optional: SQL Lab für Ad-hoc-Queries

Für explorative Analysen ohne vordefinierten Chart:

1. **SQL Lab → SQL Editor**
2. Database: `Trino Iceberg`, Schema: `analytics`
3. Query:

```sql
SELECT
    city                                AS "Ort",
    postal_code                         AS "PLZ",
    COUNT(*)                            AS "Anzahl Schadenfälle",
    SUM(settlement_amount_chf)          AS "Gesamtkosten (CHF)",
    ROUND(AVG(settlement_amount_chf), 2) AS "Durchschnitt (CHF)"
FROM iceberg.analytics.fact_claims
WHERE status = 'SETTLED'
  AND city IS NOT NULL
GROUP BY city, postal_code
ORDER BY "Gesamtkosten (CHF)" DESC
```

---

## Datenfluss (End-to-End)

```
Claims-Service                  Kafka                    Iceberg/MinIO
┌──────────────┐    claims.v1.settled    ┌───────────────────┐
│ Claim.settle()│ ──→ Outbox ──→ Kafka ──→│ claims_raw.       │
└──────────────┘                         │ claims_events     │
                                         └────────┬──────────┘
Partner-Service                                    │
┌──────────────┐    person.v1.address-added        │
│ Address added │ ──→ Outbox ──→ Kafka ──→ partner_raw       │
└──────────────┘                                   │
                                                   ▼
                              SQLMesh (Trino)
                    ┌─────────────────────────────────┐
                    │ stg_claims_events                │
                    │ stg_address_events               │
                    │       ↓            ↓             │
                    │ fact_claims ← dim_partner_address │
                    └──────────────┬──────────────────┘
                                   │
                                   ▼
                           Apache Superset
                    ┌─────────────────────────┐
                    │ Dataset: fact_claims     │
                    │ Chart: Bar / Table       │
                    │ Dashboard: Schadenanalyse│
                    └─────────────────────────┘
```

---

## Zusammenfassung

| Schritt | Was | Wo |
|---|---|---|
| 1 | Claims-Staging-Modell erstellen | `infra/sqlmesh/models/staging/` |
| 2 | Adress-Staging-Modell erstellen | `infra/sqlmesh/models/staging/` |
| 3 | `dim_partner_address` erstellen | `infra/sqlmesh/models/marts/` |
| 4 | `fact_claims` erstellen | `infra/sqlmesh/models/marts/` |
| 5 | `sqlmesh plan --auto-apply` | Terminal |
| 6 | Dataset in Superset registrieren | http://localhost:8088 |
| 7 | Charts + Dashboard erstellen | Superset UI |
