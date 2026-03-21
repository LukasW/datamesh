# Test Data Concept – Sachversicherung Datamesh Platform

> This document describes the concept for the `--test-data` switch in `build.sh`.
> It covers which data must be created in which order, which REST calls to use,
> and how Kafka State Transfer topics must be populated.

---

## Overview

The `--test-data` flag seeds all five services with a coherent, realistic dataset.
It is intended for local development and demo environments only.

Implementation: a standalone shell script `scripts/seed-test-data.sh`, invoked by `build.sh`
after the full stack is healthy.

---

## Dependency Order

Services are not independent: Kafka events flow downstream and populate read models before
certain operations are possible. The seeding script must strictly follow this order:

```
1. Partner Service   → publishes: person.v1.created, person.v1.state
                     → consumed by: Policy, Claims, Billing

2. Product Service   → publishes: product.v1.defined, product.v1.state
                     → consumed by: Policy

3. Policy Service    → consumes person + product read models
                     → publishes: policy.v1.issued
                     → consumed by: Claims (PolicySnapshot), Billing (Invoice)

4. ── wait for Kafka propagation ──

5. Claims Service    → requires PolicySnapshot (from policy.v1.issued)
                     → publishes: claims.v1.opened, claims.v1.settled
                     → consumed by: Billing (Payout)

6. Billing Service   → invoices are auto-created via policy.v1.issued consumption
                     → explicit REST calls only for payments, dunning
```

---

## Kafka State Transfer Topics

The platform uses two **compacted** Event-Carried State Transfer (ECST) topics:

| Topic | Key | Producer | Consumers |
|---|---|---|---|
| `person.v1.state` | `personId` | Partner Service | Policy, Billing, Claims |
| `product.v1.state` | `productId` | Product Service | Policy |

These topics are **not** separate from the regular domain event flow – Partner and Product
services publish a full state snapshot to these topics on every write (same outbox transaction).
Debezium CDC ensures at-least-once delivery.

**The seeding script does not need to publish to these topics directly.**
Creating persons and products via REST is sufficient; the Outbox + Debezium pipeline
fills the compacted topics automatically.

What the script must ensure:
- A health wait loop after creating persons and products before moving on to policies.
- A second wait after creating and activating policies, before creating claims.

---

## Dataset Description

### Persons (Partner Service, Port 9080)

22 persons covering all three Swiss linguistic regions and a broad age range.
Each person gets one `RESIDENCE` address.

| #   | Name                  | Gender  | Date of Birth | AHV (SSN)           | City (PLZ)          |
|-----|-----------------------|---------|---------------|---------------------|---------------------|
| P01 | Hans Müller           | MALE    | 1975-06-15    | 756.1234.5678.97    | Zürich (8001)       |
| P02 | Franziska Weber       | FEMALE  | 1990-03-28    | 756.9876.5432.10    | Bern (3000)         |
| P03 | Marco Rossi           | MALE    | 1983-11-02    | 756.5555.1111.23    | Lugano (6900)       |
| P04 | Sophie Dubois         | FEMALE  | 1988-07-14    | 756.2345.6789.04    | Genf (1201)         |
| P05 | Thomas Keller         | MALE    | 1965-02-20    | 756.3456.7890.15    | Basel (4001)        |
| P06 | Sabrina Meier         | FEMALE  | 2000-09-11    | 756.4567.8901.26    | Winterthur (8400)   |
| P07 | Luca Bernasconi       | MALE    | 1978-04-30    | 756.5678.9012.37    | Locarno (6600)      |
| P08 | Claudine Favre        | FEMALE  | 1955-12-03    | 756.6789.0123.48    | Lausanne (1003)     |
| P09 | Peter Zimmermann      | MALE    | 1992-08-18    | 756.7890.1234.59    | St. Gallen (9000)   |
| P10 | Anna Steiner          | FEMALE  | 1948-05-25    | 756.8901.2345.60    | Luzern (6000)       |
| P11 | Nicolas Blanc         | MALE    | 1986-01-09    | 756.9012.3456.71    | Fribourg (1700)     |
| P12 | Ursula Brunner        | FEMALE  | 1971-11-17    | 756.0123.4567.82    | Aarau (5000)        |
| P13 | David Schmid          | MALE    | 1994-06-22    | 756.1122.3344.93    | Winterthur (8401)   |
| P14 | Laura Frei            | FEMALE  | 2002-03-08    | 756.2233.4455.04    | Biel/Bienne (2500)  |
| P15 | Giorgio Ferretti      | MALE    | 1969-09-14    | 756.3344.5566.15    | Bellinzona (6500)   |
| P16 | Martina Huber         | FEMALE  | 1980-07-03    | 756.4455.6677.26    | Zürich (8002)       |
| P17 | Alain Morel           | MALE    | 1958-04-26    | 756.5566.7788.37    | Neuchâtel (2000)    |
| P18 | Barbara Kälin         | FEMALE  | 1995-12-31    | 756.6677.8899.48    | Zug (6300)          |
| P19 | Stefan Wolf           | MALE    | 1977-08-07    | 756.7788.9900.59    | Thun (3600)         |
| P20 | Céline Dupont         | FEMALE  | 1984-02-19    | 756.8899.0011.60    | Sion (1950)         |
| P21 | Reto Caluori          | MALE    | 1991-10-05    | 756.9900.1122.71    | Chur (7000)         |
| P22 | Heidi Baumann         | FEMALE  | 1962-03-22    | 756.0011.2233.82    | Solothurn (4500)    |

Persons P13–P22 have no policy (realistic: registered partners without active contract).
P05 (Keller) holds two concurrent policies to test the multi-policy scenario.
P08 (Favre) holds an older policy with a historical coverage start date.

### Products (Product Service, Port 9081)

| #   | Name                      | Line                | Base Premium (CHF) |
|-----|---------------------------|---------------------|--------------------|
| PR1 | Hausrat Basis             | HOUSEHOLD_CONTENTS  | 150.00             |
| PR2 | Haftpflicht Privat        | LIABILITY           | 200.00             |
| PR3 | Motorfahrzeug Vollkasko   | MOTOR_VEHICLE       | 980.00             |
| PR4 | Reiseversicherung         | TRAVEL              | 85.00              |
| PR5 | Rechtsschutz              | LEGAL_EXPENSES      | 320.00             |

### Policies (Policy Service, Port 9082)

All policies are created as DRAFT, given coverages, then activated.
Exceptions are noted in the Status column.

| #     | Policyholder      | Product | Coverage Start | Premium (CHF) | Deductible (CHF) | Final Status |
|-------|-------------------|---------|----------------|---------------|------------------|--------------|
| POL01 | P01 Müller        | PR1     | 2024-01-01     | 450.00        | 200.00           | ACTIVE       |
| POL02 | P02 Weber         | PR2     | 2024-03-01     | 380.00        | 0.00             | ACTIVE       |
| POL03 | P03 Rossi         | PR3     | 2023-06-15     | 1200.00       | 500.00           | CANCELLED    |
| POL04 | P04 Dubois        | PR2     | 2024-06-01     | 360.00        | 0.00             | ACTIVE       |
| POL05 | P05 Keller        | PR1     | 2022-01-01     | 520.00        | 300.00           | ACTIVE       |
| POL06 | P05 Keller        | PR3     | 2023-03-15     | 1450.00       | 1000.00          | ACTIVE       |
| POL07 | P06 Meier         | PR1     | 2025-01-01     | 410.00        | 200.00           | ACTIVE       |
| POL08 | P07 Bernasconi    | PR3     | 2021-09-01     | 1100.00       | 500.00           | ACTIVE       |
| POL09 | P08 Favre         | PR1     | 2019-04-01     | 390.00        | 100.00           | ACTIVE       |
| POL10 | P09 Zimmermann    | PR2     | 2024-09-01     | 350.00        | 0.00             | ACTIVE       |
| POL11 | P10 Steiner       | PR1     | 2023-07-01     | 430.00        | 150.00           | ACTIVE       |
| POL12 | P11 Blanc         | PR3     | 2024-02-01     | 1300.00       | 500.00           | ACTIVE       |
| POL13 | P12 Brunner       | PR4     | 2025-03-01     | 85.00         | 0.00             | ACTIVE       |
| POL14 | P16 Huber         | PR5     | 2024-05-01     | 320.00        | 0.00             | ACTIVE       |
| POL15 | P19 Wolf          | PR1     | 2024-11-01     | 480.00        | 200.00           | ACTIVE       |

### Claims (Claims Service, Port 9083)

| #   | Policy | Description                                    | Claim Date | Final Status |
|-----|--------|------------------------------------------------|------------|--------------|
| C01 | POL01  | Wasserschaden im Keller                        | 2024-03-10 | SETTLED      |
| C02 | POL02  | Sachschaden an Drittperson beim Velofahren     | 2024-04-05 | IN_REVIEW    |
| C03 | POL05  | Einbruchdiebstahl, Schmuck und Elektronik      | 2024-08-22 | SETTLED      |
| C04 | POL06  | Kollisionsschaden Heckbereich                  | 2024-10-01 | IN_REVIEW    |
| C05 | POL09  | Sturmschaden Fensterscheibe                    | 2025-01-14 | OPEN         |
| C06 | POL12  | Totalschaden nach Unfall                       | 2024-12-03 | REJECTED     |
| C07 | POL11  | Leitungswasser – Küchenmöbel beschädigt        | 2025-02-28 | OPEN         |
| C08 | POL08  | Glasbruch Windschutzscheibe                    | 2024-07-11 | SETTLED      |

Settled claims trigger `claims.v1.settled` → Billing payout. C06 is rejected to test the
rejection path. C05 and C07 are brand-new (OPEN) for demo of the FNOL state.

### Billing (Billing Service, Port 9084)

Invoices are created automatically by the Billing Service consuming `policy.v1.issued`.
Additional REST calls simulate diverse billing states:

| Invoice for | Action                  | Resulting Status | Notes                                 |
|-------------|-------------------------|------------------|---------------------------------------|
| POL01       | `pay`                   | PAID             | Müller pays on time                   |
| POL04       | `pay`                   | PAID             | Dubois pays on time                   |
| POL05       | `pay`                   | PAID             | Keller (Hausrat) pays                 |
| POL06       | `pay`                   | PAID             | Keller (Motor) pays                   |
| POL08       | `pay`                   | PAID             | Bernasconi pays                       |
| POL09       | `pay`                   | PAID             | Favre pays (long-standing customer)   |
| POL07       | `dun`                   | OVERDUE          | Meier misses payment → reminder       |
| POL11       | `dun` (twice)           | OVERDUE          | Steiner → first warning level         |
| POL02       | *(no action)*           | OPEN             | Weber invoice pending                 |
| POL10       | *(no action)*           | OPEN             | Zimmermann invoice pending            |
| POL03       | auto-cancelled by event | CANCELLED        | Rossi policy was cancelled            |

---

## REST Call Sequence

Below is the exact sequence the script must execute.
All requests use `Content-Type: application/json`.
IDs returned by POST responses are captured in shell variables for downstream calls.

```
# ── 1. PARTNER SERVICE ─────────────────────────────────────────────────────

POST http://localhost:9080/api/persons
  { "name": "Müller", "firstName": "Hans", "gender": "MALE",
    "dateOfBirth": "1975-06-15", "socialSecurityNumber": "756.1234.5678.97" }
  → capture: P01_ID

POST http://localhost:9080/api/persons/{P01_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Hauptstrasse", "houseNumber": "42",
    "postalCode": "8001", "city": "Zürich", "land": "Schweiz", "validFrom": "2020-01-01" }

POST http://localhost:9080/api/persons
  { "name": "Weber", "firstName": "Franziska", "gender": "FEMALE",
    "dateOfBirth": "1990-03-28", "socialSecurityNumber": "756.9876.5432.10" }
  → capture: P02_ID

POST http://localhost:9080/api/persons/{P02_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Bahnhofstrasse", "houseNumber": "7",
    "postalCode": "3000", "city": "Bern", "land": "Schweiz", "validFrom": "2021-06-01" }

POST http://localhost:9080/api/persons
  { "name": "Rossi", "firstName": "Marco", "gender": "MALE",
    "dateOfBirth": "1983-11-02", "socialSecurityNumber": "756.5555.1111.23" }
  → capture: P03_ID

POST http://localhost:9080/api/persons/{P03_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Via Cantonale", "houseNumber": "12",
    "postalCode": "6900", "city": "Lugano", "land": "Schweiz", "validFrom": "2019-03-15" }

POST http://localhost:9080/api/persons
  { "name": "Dubois", "firstName": "Sophie", "gender": "FEMALE",
    "dateOfBirth": "1988-07-14", "socialSecurityNumber": "756.2345.6789.04" }
  → capture: P04_ID

POST http://localhost:9080/api/persons/{P04_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Rue de Rive", "houseNumber": "3",
    "postalCode": "1201", "city": "Genf", "land": "Schweiz", "validFrom": "2022-09-01" }

POST http://localhost:9080/api/persons
  { "name": "Keller", "firstName": "Thomas", "gender": "MALE",
    "dateOfBirth": "1965-02-20", "socialSecurityNumber": "756.3456.7890.15" }
  → capture: P05_ID

POST http://localhost:9080/api/persons/{P05_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Freie Strasse", "houseNumber": "18",
    "postalCode": "4001", "city": "Basel", "land": "Schweiz", "validFrom": "2010-04-01" }

POST http://localhost:9080/api/persons
  { "name": "Meier", "firstName": "Sabrina", "gender": "FEMALE",
    "dateOfBirth": "2000-09-11", "socialSecurityNumber": "756.4567.8901.26" }
  → capture: P06_ID

POST http://localhost:9080/api/persons/{P06_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Marktgasse", "houseNumber": "5",
    "postalCode": "8400", "city": "Winterthur", "land": "Schweiz", "validFrom": "2023-01-15" }

POST http://localhost:9080/api/persons
  { "name": "Bernasconi", "firstName": "Luca", "gender": "MALE",
    "dateOfBirth": "1978-04-30", "socialSecurityNumber": "756.5678.9012.37" }
  → capture: P07_ID

POST http://localhost:9080/api/persons/{P07_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Piazza Grande", "houseNumber": "1",
    "postalCode": "6600", "city": "Locarno", "land": "Schweiz", "validFrom": "2015-07-01" }

POST http://localhost:9080/api/persons
  { "name": "Favre", "firstName": "Claudine", "gender": "FEMALE",
    "dateOfBirth": "1955-12-03", "socialSecurityNumber": "756.6789.0123.48" }
  → capture: P08_ID

POST http://localhost:9080/api/persons/{P08_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Avenue du Léman", "houseNumber": "22",
    "postalCode": "1003", "city": "Lausanne", "land": "Schweiz", "validFrom": "2005-06-01" }

POST http://localhost:9080/api/persons
  { "name": "Zimmermann", "firstName": "Peter", "gender": "MALE",
    "dateOfBirth": "1992-08-18", "socialSecurityNumber": "756.7890.1234.59" }
  → capture: P09_ID

POST http://localhost:9080/api/persons/{P09_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Marktplatz", "houseNumber": "9",
    "postalCode": "9000", "city": "St. Gallen", "land": "Schweiz", "validFrom": "2020-08-01" }

POST http://localhost:9080/api/persons
  { "name": "Steiner", "firstName": "Anna", "gender": "FEMALE",
    "dateOfBirth": "1948-05-25", "socialSecurityNumber": "756.8901.2345.60" }
  → capture: P10_ID

POST http://localhost:9080/api/persons/{P10_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Weinmarkt", "houseNumber": "11",
    "postalCode": "6000", "city": "Luzern", "land": "Schweiz", "validFrom": "1998-01-01" }

POST http://localhost:9080/api/persons
  { "name": "Blanc", "firstName": "Nicolas", "gender": "MALE",
    "dateOfBirth": "1986-01-09", "socialSecurityNumber": "756.9012.3456.71" }
  → capture: P11_ID

POST http://localhost:9080/api/persons/{P11_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Rue de Lausanne", "houseNumber": "14",
    "postalCode": "1700", "city": "Fribourg", "land": "Schweiz", "validFrom": "2018-05-01" }

POST http://localhost:9080/api/persons
  { "name": "Brunner", "firstName": "Ursula", "gender": "FEMALE",
    "dateOfBirth": "1971-11-17", "socialSecurityNumber": "756.0123.4567.82" }
  → capture: P12_ID

POST http://localhost:9080/api/persons/{P12_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Laurenzenvorstadt", "houseNumber": "33",
    "postalCode": "5000", "city": "Aarau", "land": "Schweiz", "validFrom": "2014-11-01" }

POST http://localhost:9080/api/persons
  { "name": "Schmid", "firstName": "David", "gender": "MALE",
    "dateOfBirth": "1994-06-22", "socialSecurityNumber": "756.1122.3344.93" }
  → capture: P13_ID

POST http://localhost:9080/api/persons/{P13_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Technikumstrasse", "houseNumber": "71",
    "postalCode": "8401", "city": "Winterthur", "land": "Schweiz", "validFrom": "2022-10-01" }

POST http://localhost:9080/api/persons
  { "name": "Frei", "firstName": "Laura", "gender": "FEMALE",
    "dateOfBirth": "2002-03-08", "socialSecurityNumber": "756.2233.4455.04" }
  → capture: P14_ID

POST http://localhost:9080/api/persons/{P14_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Neumarktstrasse", "houseNumber": "6",
    "postalCode": "2500", "city": "Biel/Bienne", "land": "Schweiz", "validFrom": "2024-01-01" }

POST http://localhost:9080/api/persons
  { "name": "Ferretti", "firstName": "Giorgio", "gender": "MALE",
    "dateOfBirth": "1969-09-14", "socialSecurityNumber": "756.3344.5566.15" }
  → capture: P15_ID

POST http://localhost:9080/api/persons/{P15_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Viale Stazione", "houseNumber": "4",
    "postalCode": "6500", "city": "Bellinzona", "land": "Schweiz", "validFrom": "2007-03-01" }

POST http://localhost:9080/api/persons
  { "name": "Huber", "firstName": "Martina", "gender": "FEMALE",
    "dateOfBirth": "1980-07-03", "socialSecurityNumber": "756.4455.6677.26" }
  → capture: P16_ID

POST http://localhost:9080/api/persons/{P16_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Seestrasse", "houseNumber": "88",
    "postalCode": "8002", "city": "Zürich", "land": "Schweiz", "validFrom": "2017-09-01" }

POST http://localhost:9080/api/persons
  { "name": "Morel", "firstName": "Alain", "gender": "MALE",
    "dateOfBirth": "1958-04-26", "socialSecurityNumber": "756.5566.7788.37" }
  → capture: P17_ID

POST http://localhost:9080/api/persons/{P17_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Rue du Pommier", "houseNumber": "2",
    "postalCode": "2000", "city": "Neuchâtel", "land": "Schweiz", "validFrom": "2003-07-01" }

POST http://localhost:9080/api/persons
  { "name": "Kälin", "firstName": "Barbara", "gender": "FEMALE",
    "dateOfBirth": "1995-12-31", "socialSecurityNumber": "756.6677.8899.48" }
  → capture: P18_ID

POST http://localhost:9080/api/persons/{P18_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Alpenstrasse", "houseNumber": "17",
    "postalCode": "6300", "city": "Zug", "land": "Schweiz", "validFrom": "2021-03-15" }

POST http://localhost:9080/api/persons
  { "name": "Wolf", "firstName": "Stefan", "gender": "MALE",
    "dateOfBirth": "1977-08-07", "socialSecurityNumber": "756.7788.9900.59" }
  → capture: P19_ID

POST http://localhost:9080/api/persons/{P19_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Bälliz", "houseNumber": "30",
    "postalCode": "3600", "city": "Thun", "land": "Schweiz", "validFrom": "2012-06-01" }

POST http://localhost:9080/api/persons
  { "name": "Dupont", "firstName": "Céline", "gender": "FEMALE",
    "dateOfBirth": "1984-02-19", "socialSecurityNumber": "756.8899.0011.60" }
  → capture: P20_ID

POST http://localhost:9080/api/persons/{P20_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Rue du Grand-Pont", "houseNumber": "8",
    "postalCode": "1950", "city": "Sion", "land": "Schweiz", "validFrom": "2019-11-01" }

POST http://localhost:9080/api/persons
  { "name": "Caluori", "firstName": "Reto", "gender": "MALE",
    "dateOfBirth": "1991-10-05", "socialSecurityNumber": "756.9900.1122.71" }
  → capture: P21_ID

POST http://localhost:9080/api/persons/{P21_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Grabenstrasse", "houseNumber": "15",
    "postalCode": "7000", "city": "Chur", "land": "Schweiz", "validFrom": "2023-05-01" }

POST http://localhost:9080/api/persons
  { "name": "Baumann", "firstName": "Heidi", "gender": "FEMALE",
    "dateOfBirth": "1962-03-22", "socialSecurityNumber": "756.0011.2233.82" }
  → capture: P22_ID

POST http://localhost:9080/api/persons/{P22_ID}/addresses
  { "addressType": "RESIDENCE", "street": "Hauptgasse", "houseNumber": "24",
    "postalCode": "4500", "city": "Solothurn", "land": "Schweiz", "validFrom": "2001-09-01" }


# ── 2. PRODUCT SERVICE ──────────────────────────────────────────────────────

POST http://localhost:9081/api/products
  { "name": "Hausrat Basis", "description": "Basic household contents insurance",
    "productLine": "HOUSEHOLD_CONTENTS", "basePremium": 150.00 }
  → capture: PR1_ID

POST http://localhost:9081/api/products
  { "name": "Haftpflicht Privat", "description": "Personal liability coverage",
    "productLine": "LIABILITY", "basePremium": 200.00 }
  → capture: PR2_ID

POST http://localhost:9081/api/products
  { "name": "Motorfahrzeug Vollkasko", "description": "Comprehensive motor vehicle coverage",
    "productLine": "MOTOR_VEHICLE", "basePremium": 980.00 }
  → capture: PR3_ID

POST http://localhost:9081/api/products
  { "name": "Reiseversicherung", "description": "Travel insurance with cancellation coverage",
    "productLine": "TRAVEL", "basePremium": 85.00 }
  → capture: PR4_ID

POST http://localhost:9081/api/products
  { "name": "Rechtsschutz", "description": "Legal expenses insurance",
    "productLine": "LEGAL_EXPENSES", "basePremium": 320.00 }
  → capture: PR5_ID


# ── wait: Policy Service read models must be populated from Kafka ────────
# wait loop: poll until PartnerView and ProductView are non-empty


# ── 3. POLICY SERVICE ───────────────────────────────────────────────────────

# POL01 – Müller / Hausrat
POST http://localhost:9082/api/policies
  { "partnerId": "{P01_ID}", "productId": "{PR1_ID}",
    "coverageStartDate": "2024-01-01", "premium": 450.00, "deductible": 200.00 }
  → capture: POL01_ID
POST http://localhost:9082/api/policies/{POL01_ID}/coverages
  { "coverageType": "HOUSEHOLD_CONTENTS", "insuredAmount": 50000.00 }
POST http://localhost:9082/api/policies/{POL01_ID}/activate

# POL02 – Weber / Haftpflicht
POST http://localhost:9082/api/policies
  { "partnerId": "{P02_ID}", "productId": "{PR2_ID}",
    "coverageStartDate": "2024-03-01", "premium": 380.00, "deductible": 0.00 }
  → capture: POL02_ID
POST http://localhost:9082/api/policies/{POL02_ID}/coverages
  { "coverageType": "LIABILITY", "insuredAmount": 5000000.00 }
POST http://localhost:9082/api/policies/{POL02_ID}/activate

# POL03 – Rossi / Motorfahrzeug → will be cancelled
POST http://localhost:9082/api/policies
  { "partnerId": "{P03_ID}", "productId": "{PR3_ID}",
    "coverageStartDate": "2023-06-15", "premium": 1200.00, "deductible": 500.00 }
  → capture: POL03_ID
POST http://localhost:9082/api/policies/{POL03_ID}/coverages
  { "coverageType": "COMPREHENSIVE", "insuredAmount": 35000.00 }
POST http://localhost:9082/api/policies/{POL03_ID}/activate
POST http://localhost:9082/api/policies/{POL03_ID}/cancel

# POL04 – Dubois / Haftpflicht
POST http://localhost:9082/api/policies
  { "partnerId": "{P04_ID}", "productId": "{PR2_ID}",
    "coverageStartDate": "2024-06-01", "premium": 360.00, "deductible": 0.00 }
  → capture: POL04_ID
POST http://localhost:9082/api/policies/{POL04_ID}/coverages
  { "coverageType": "LIABILITY", "insuredAmount": 3000000.00 }
POST http://localhost:9082/api/policies/{POL04_ID}/activate

# POL05 – Keller / Hausrat (multi-policy person, first policy)
POST http://localhost:9082/api/policies
  { "partnerId": "{P05_ID}", "productId": "{PR1_ID}",
    "coverageStartDate": "2022-01-01", "premium": 520.00, "deductible": 300.00 }
  → capture: POL05_ID
POST http://localhost:9082/api/policies/{POL05_ID}/coverages
  { "coverageType": "HOUSEHOLD_CONTENTS", "insuredAmount": 80000.00 }
POST http://localhost:9082/api/policies/{POL05_ID}/coverages
  { "coverageType": "THEFT", "insuredAmount": 15000.00 }
POST http://localhost:9082/api/policies/{POL05_ID}/activate

# POL06 – Keller / Motorfahrzeug (multi-policy person, second policy)
POST http://localhost:9082/api/policies
  { "partnerId": "{P05_ID}", "productId": "{PR3_ID}",
    "coverageStartDate": "2023-03-15", "premium": 1450.00, "deductible": 1000.00 }
  → capture: POL06_ID
POST http://localhost:9082/api/policies/{POL06_ID}/coverages
  { "coverageType": "COMPREHENSIVE", "insuredAmount": 42000.00 }
POST http://localhost:9082/api/policies/{POL06_ID}/activate

# POL07 – Meier / Hausrat → invoice will go overdue (dunning)
POST http://localhost:9082/api/policies
  { "partnerId": "{P06_ID}", "productId": "{PR1_ID}",
    "coverageStartDate": "2025-01-01", "premium": 410.00, "deductible": 200.00 }
  → capture: POL07_ID
POST http://localhost:9082/api/policies/{POL07_ID}/coverages
  { "coverageType": "HOUSEHOLD_CONTENTS", "insuredAmount": 30000.00 }
POST http://localhost:9082/api/policies/{POL07_ID}/activate

# POL08 – Bernasconi / Motorfahrzeug
POST http://localhost:9082/api/policies
  { "partnerId": "{P07_ID}", "productId": "{PR3_ID}",
    "coverageStartDate": "2021-09-01", "premium": 1100.00, "deductible": 500.00 }
  → capture: POL08_ID
POST http://localhost:9082/api/policies/{POL08_ID}/coverages
  { "coverageType": "COMPREHENSIVE", "insuredAmount": 28000.00 }
POST http://localhost:9082/api/policies/{POL08_ID}/activate

# POL09 – Favre / Hausrat (long-standing customer, historical start date)
POST http://localhost:9082/api/policies
  { "partnerId": "{P08_ID}", "productId": "{PR1_ID}",
    "coverageStartDate": "2019-04-01", "premium": 390.00, "deductible": 100.00 }
  → capture: POL09_ID
POST http://localhost:9082/api/policies/{POL09_ID}/coverages
  { "coverageType": "HOUSEHOLD_CONTENTS", "insuredAmount": 45000.00 }
POST http://localhost:9082/api/policies/{POL09_ID}/coverages
  { "coverageType": "NATURAL_HAZARD", "insuredAmount": 45000.00 }
POST http://localhost:9082/api/policies/{POL09_ID}/activate

# POL10 – Zimmermann / Haftpflicht
POST http://localhost:9082/api/policies
  { "partnerId": "{P09_ID}", "productId": "{PR2_ID}",
    "coverageStartDate": "2024-09-01", "premium": 350.00, "deductible": 0.00 }
  → capture: POL10_ID
POST http://localhost:9082/api/policies/{POL10_ID}/coverages
  { "coverageType": "LIABILITY", "insuredAmount": 2000000.00 }
POST http://localhost:9082/api/policies/{POL10_ID}/activate

# POL11 – Steiner / Hausrat → invoice will go overdue (escalated dunning)
POST http://localhost:9082/api/policies
  { "partnerId": "{P10_ID}", "productId": "{PR1_ID}",
    "coverageStartDate": "2023-07-01", "premium": 430.00, "deductible": 150.00 }
  → capture: POL11_ID
POST http://localhost:9082/api/policies/{POL11_ID}/coverages
  { "coverageType": "HOUSEHOLD_CONTENTS", "insuredAmount": 55000.00 }
POST http://localhost:9082/api/policies/{POL11_ID}/activate

# POL12 – Blanc / Motorfahrzeug (claim will be rejected)
POST http://localhost:9082/api/policies
  { "partnerId": "{P11_ID}", "productId": "{PR3_ID}",
    "coverageStartDate": "2024-02-01", "premium": 1300.00, "deductible": 500.00 }
  → capture: POL12_ID
POST http://localhost:9082/api/policies/{POL12_ID}/coverages
  { "coverageType": "COMPREHENSIVE", "insuredAmount": 22000.00 }
POST http://localhost:9082/api/policies/{POL12_ID}/activate

# POL13 – Brunner / Reiseversicherung
POST http://localhost:9082/api/policies
  { "partnerId": "{P12_ID}", "productId": "{PR4_ID}",
    "coverageStartDate": "2025-03-01", "premium": 85.00, "deductible": 0.00 }
  → capture: POL13_ID
POST http://localhost:9082/api/policies/{POL13_ID}/coverages
  { "coverageType": "LIABILITY", "insuredAmount": 500000.00 }
POST http://localhost:9082/api/policies/{POL13_ID}/activate

# POL14 – Huber / Rechtsschutz
POST http://localhost:9082/api/policies
  { "partnerId": "{P16_ID}", "productId": "{PR5_ID}",
    "coverageStartDate": "2024-05-01", "premium": 320.00, "deductible": 0.00 }
  → capture: POL14_ID
POST http://localhost:9082/api/policies/{POL14_ID}/coverages
  { "coverageType": "LIABILITY", "insuredAmount": 250000.00 }
POST http://localhost:9082/api/policies/{POL14_ID}/activate

# POL15 – Wolf / Hausrat
POST http://localhost:9082/api/policies
  { "partnerId": "{P19_ID}", "productId": "{PR1_ID}",
    "coverageStartDate": "2024-11-01", "premium": 480.00, "deductible": 200.00 }
  → capture: POL15_ID
POST http://localhost:9082/api/policies/{POL15_ID}/coverages
  { "coverageType": "HOUSEHOLD_CONTENTS", "insuredAmount": 60000.00 }
POST http://localhost:9082/api/policies/{POL15_ID}/coverages
  { "coverageType": "GLASS_BREAKAGE", "insuredAmount": 10000.00 }
POST http://localhost:9082/api/policies/{POL15_ID}/activate


# ── wait: Claims must have consumed policy.v1.issued to build PolicySnapshots ─


# ── 4. CLAIMS SERVICE ───────────────────────────────────────────────────────

# C01 – Müller: Wasserschaden → SETTLED
POST http://localhost:9083/api/claims
  { "policyId": "{POL01_ID}", "description": "Wasserschaden im Keller",
    "claimDate": "2024-03-10" }
  → capture: C01_ID
POST http://localhost:9083/api/claims/{C01_ID}/review
POST http://localhost:9083/api/claims/{C01_ID}/settle

# C02 – Weber: Sachschaden → IN_REVIEW
POST http://localhost:9083/api/claims
  { "policyId": "{POL02_ID}", "description": "Sachschaden an Drittperson beim Velofahren",
    "claimDate": "2024-04-05" }
  → capture: C02_ID
POST http://localhost:9083/api/claims/{C02_ID}/review

# C03 – Keller (Hausrat): Einbruchdiebstahl → SETTLED
POST http://localhost:9083/api/claims
  { "policyId": "{POL05_ID}", "description": "Einbruchdiebstahl, Schmuck und Elektronik",
    "claimDate": "2024-08-22" }
  → capture: C03_ID
POST http://localhost:9083/api/claims/{C03_ID}/review
POST http://localhost:9083/api/claims/{C03_ID}/settle

# C04 – Keller (Motor): Kollisionsschaden → IN_REVIEW
POST http://localhost:9083/api/claims
  { "policyId": "{POL06_ID}", "description": "Kollisionsschaden Heckbereich",
    "claimDate": "2024-10-01" }
  → capture: C04_ID
POST http://localhost:9083/api/claims/{C04_ID}/review

# C05 – Favre: Sturmschaden → OPEN (fresh FNOL)
POST http://localhost:9083/api/claims
  { "policyId": "{POL09_ID}", "description": "Sturmschaden Fensterscheibe",
    "claimDate": "2025-01-14" }
  → capture: C05_ID

# C06 – Blanc (Motor): Totalschaden → REJECTED
POST http://localhost:9083/api/claims
  { "policyId": "{POL12_ID}", "description": "Totalschaden nach Unfall",
    "claimDate": "2024-12-03" }
  → capture: C06_ID
POST http://localhost:9083/api/claims/{C06_ID}/review
POST http://localhost:9083/api/claims/{C06_ID}/reject

# C07 – Steiner: Leitungswasser → OPEN (fresh FNOL)
POST http://localhost:9083/api/claims
  { "policyId": "{POL11_ID}", "description": "Leitungswasser – Küchenmöbel beschädigt",
    "claimDate": "2025-02-28" }
  → capture: C07_ID

# C08 – Bernasconi (Motor): Glasbruch → SETTLED
POST http://localhost:9083/api/claims
  { "policyId": "{POL08_ID}", "description": "Glasbruch Windschutzscheibe",
    "claimDate": "2024-07-11" }
  → capture: C08_ID
POST http://localhost:9083/api/claims/{C08_ID}/review
POST http://localhost:9083/api/claims/{C08_ID}/settle


# ── wait: Billing must have consumed policy.v1.issued to create invoices ─────


# ── 5. BILLING SERVICE ──────────────────────────────────────────────────────

# Paid invoices
GET http://localhost:9084/api/invoices?policyId={POL01_ID}  → capture: INV01_ID
POST http://localhost:9084/api/invoices/{INV01_ID}/pay

GET http://localhost:9084/api/invoices?policyId={POL04_ID}  → capture: INV04_ID
POST http://localhost:9084/api/invoices/{INV04_ID}/pay

GET http://localhost:9084/api/invoices?policyId={POL05_ID}  → capture: INV05_ID
POST http://localhost:9084/api/invoices/{INV05_ID}/pay

GET http://localhost:9084/api/invoices?policyId={POL06_ID}  → capture: INV06_ID
POST http://localhost:9084/api/invoices/{INV06_ID}/pay

GET http://localhost:9084/api/invoices?policyId={POL08_ID}  → capture: INV08_ID
POST http://localhost:9084/api/invoices/{INV08_ID}/pay

GET http://localhost:9084/api/invoices?policyId={POL09_ID}  → capture: INV09_ID
POST http://localhost:9084/api/invoices/{INV09_ID}/pay

# Overdue + dunning: Meier → one dunning level (reminder)
GET http://localhost:9084/api/invoices?policyId={POL07_ID}  → capture: INV07_ID
POST http://localhost:9084/api/invoices/{INV07_ID}/dun

# Overdue + dunning: Steiner → two dunning levels (first warning)
GET http://localhost:9084/api/invoices?policyId={POL11_ID}  → capture: INV11_ID
POST http://localhost:9084/api/invoices/{INV11_ID}/dun
POST http://localhost:9084/api/invoices/{INV11_ID}/dun

# Open invoices: POL02, POL10, POL12, POL13, POL14, POL15 – no action required
# Cancelled invoice: POL03 – auto-cancelled by policy.v1.cancelled consumption
```

---

## Wait / Health Check Strategy

Kafka propagation is asynchronous. The script uses polling loops after each phase:

```
wait_for_read_model() {
  # Poll a lightweight endpoint that indicates the read model is populated.
  # For Policy Service: check that partnerId is known (GET /api/persons/{id} on the read model).
  # For Claims Service: poll until PolicySnapshot exists via an internal health endpoint,
  #   or simply retry the claim creation with backoff until it succeeds.
}
```

Proposed timeouts per wait phase:

| After phase | Wait for | Max wait |
|---|---|---|
| Partner + Product creation | Policy read models populated | 30 s |
| Policy activation (policy.v1.issued) | Claims PolicySnapshot + Billing Invoice | 30 s |
| Claim settlement (claims.v1.settled) | Billing Payout event | 15 s |

---

## build.sh Integration

```zsh
--test-data    Seed all services with test data after stack is healthy
               Implies -d (daemon mode). Requires a running stack.
```

The flag:
1. Sets `CREATE_TEST_DATA=true`
2. Forces `DAEMON_MODE=true` (stack must be up and detached)
3. After Debezium connector registration, calls `scripts/seed-test-data.sh`

`seed-test-data.sh` is idempotent where the APIs allow it (upsert semantics, or skip if
data already exists). For services without upsert, it prints a warning and continues.

---

## Kafka Topics Populated After Full Seed Run

| Topic | Messages | Notes |
|---|---|---|
| `person.v1.created` | 22 | One per person |
| `person.v1.address-added` | 22 | One per person |
| `person.v1.state` | 22 (compacted) | ECST snapshot, keyed by personId |
| `product.v1.defined` | 5 | One per product |
| `product.v1.state` | 5 (compacted) | ECST snapshot, keyed by productId |
| `policy.v1.issued` | 15 | All 15 policies before any cancel |
| `policy.v1.coverage-added` | 19 | POL05, POL09, POL15 have 2 coverages each |
| `policy.v1.cancelled` | 1 | POL03 cancellation |
| `claims.v1.opened` | 8 | C01–C08 |
| `claims.v1.settled` | 3 | C01, C03, C08 |
| `billing.v1.invoice-created` | 15 | Auto-created by Billing on policy.v1.issued |
| `billing.v1.payment-received` | 6 | POL01, POL04, POL05, POL06, POL08, POL09 |
| `billing.v1.dunning-initiated` | 3 | POL07 (×1), POL11 (×2) |
| `billing.v1.payout-triggered` | 3 | C01, C03, C08 settlements |

---

## Out of Scope

- Keycloak users/roles – admin credentials are bootstrapped via Keycloak realm import already.
- Debezium connector registration – handled by the existing `build.sh` logic.
- Spark / DataHub / Airflow seed data – separate concern.
