# Data Mesh Prototype – CDO Pitch Plan

> **Goal:** Convince the CDO that Data Mesh with Federated Computational Governance is the right
> architecture for CSS Sachversicherung. This prototype runs entirely locally and demonstrates all
> four Data Mesh principles with real, runnable code.

---

## Why This Prototype Will Convince a CDO

The classic pitch fails because it stays abstract. This prototype avoids that trap:

| Principle | What we show | How we prove it |
| --- | --- | --- |
| **Domain Ownership** | Each service team publishes its own data product | Partner, Policy, Product each have independent contracts + quality SLAs |
| **Data as a Product** | ODC-governed Kafka topics with schema, SLA, quality checks | `person.v1.state`, `policy.v1.issued` as first-class products with metadata portals |
| **Self-Serve Platform** | One-command bootstrap of the full stack | `podman compose up` → Kafka, Schema Registry, dbt, Spark, Portal all running |
| **Federated Computational Governance** | Governance rules enforced as code, not process | Schema Registry rejects non-compliant schemas; dbt tests fail the pipeline; ODC quality checks run automatically |

**The killer demo:** Show a cross-domain analytics query (active policies per partner city, premium per product line) that is **impossible** with the old centralized monolith approach, yet trivially composable in Data Mesh because every domain publishes its data as a product.

---

## What Already Exists (the foundation)

```text
✅ Three autonomous services  →  partner (9080), product (9081), policy (9082)
✅ Transactional Outbox + Debezium CDC
✅ 16 ODC YAML contracts + 13 Avro schemas
✅ Kafka + Schema Registry (KRaft, no Zookeeper)
✅ AKHQ UI for Kafka monitoring (port 8085)
✅ Spark Structured Streaming  →  Delta Lake (person events)
✅ dbt layer  →  stg_person_events + dim_partner
✅ Platform consumer (Kafka → PostgreSQL raw layer)
✅ SodaCL quality checks in every ODC file
✅ Isolated PostgreSQL per domain (true data sovereignty)
```

The foundation is solid. We extend it with four targeted additions.

---

## The Four Additions (Prioritised by CDO Impact)

### Addition 1 – Data Product Manifests (ODC Upgrade)

**What:** Upgrade every ODC contract from "schema doc" to a real data product manifest.

**Why it matters for the CDO:** Makes the "data as a product" principle tangible and auditable.

**Changes per ODC file:**

```yaml
# Add these sections to every *.odcontract.yaml

dataProduct:
  owner: team-partner@css.ch         # or team-policy, team-product
  domain: partner                    # bounded context
  outputPort: kafka                  # kafka | rest | delta | dbt-mart
  sla:
    freshness: 5m                    # max lag from event to topic
    availability: 99.9%
    qualityScore: 0.98               # dbt test pass rate target

tags:
  - pii                              # GDPR-relevant fields flagged
  - gdpr-subject                     # for right-to-erasure tracking
```

**Files to update:** All 16 ODC files in `partner/`, `policy/`, `product/` resources/contracts/

---

### Addition 2 – Data Product Portal (New Service: `infra/portal`)

**What:** A lightweight read-only web UI that renders all data products, their lineage, and quality status.

**Why it matters for the CDO:** The CDO can *see* every data product in the mesh without opening Confluence.

**Tech stack:** Python + FastAPI + Jinja2 templates (no build step, starts in seconds).

**Architecture:**

```text
infra/portal/
├── Dockerfile
├── main.py                  ←  FastAPI app, reads ODC files + dbt test results
├── templates/
│   ├── index.html           ←  Data Product Catalogue (all 3 domains)
│   ├── product.html         ←  Single data product detail page
│   └── lineage.html         ←  Cross-domain lineage graph (D3.js)
└── requirements.txt
```

**Portal pages:**

**1. Data Product Catalogue** (`/`)

- Table of all 16 data products (name, domain, owner, output port, tags, SLA, quality score)
- Filter by domain / tag
- Click → detail page

**2. Data Product Detail** (`/products/{topic}`)

- Schema fields (from Avro)
- SLA commitments (from ODC)
- Last 24h quality check results (from dbt test output)
- Sample event payload (last message from Kafka)

**3. Lineage Graph** (`/lineage`)

- Visual graph: Partner → Policy (person events consumed), Product → Policy
- Shows cross-domain dependencies as data product subscriptions, not service coupling
- Key CDO talking point: "The policy team depends on the *data product*, not on the partner team's deployment"

**4. Governance Dashboard** (`/governance`)

- Schema Registry: all registered schemas + compatibility levels
- dbt test pass rates per domain
- SodaCL check results per topic
- Alerts: any topic with quality score < SLA threshold

**Implementation effort:** ~300 lines Python + simple HTML templates.

---

### Addition 3 – Federated Computational Governance Pipeline

**What:** Automated governance checks that run on every build/deploy, encoded as code.

**Why it matters for the CDO:** Demonstrates that governance scales without a central data team bottleneck.

**Three governance layers:**

#### Layer 1 – Schema Governance (Schema Registry)

Already in place. Extend it:

- Set compatibility mode to `FULL_TRANSITIVE` for all schemas (backward + forward compatible)
- Add `schema-compat-check.sh`: CI-runnable script that validates every `.avsc` against the Registry

```bash
# infra/governance/schema-compat-check.sh
# For each *.avsc file: POST to /compatibility/subjects/{subject}/versions/latest
# Fail if compatibility is violated → blocks deployment
```

#### Layer 2 – Data Quality Governance (dbt tests)

Extend dbt to cover all three domains.

**New dbt models:**

```text
infra/dbt/models/
├── staging/
│   ├── stg_person_events.sql        (exists)
│   ├── stg_policy_events.sql        (NEW – policy.v1.issued + policy.v1.cancelled)
│   └── stg_product_events.sql       (NEW – product.v1.defined + product.v1.updated)
├── marts/
│   ├── dim_partner.sql              (exists)
│   ├── dim_product.sql              (NEW – latest product state per productId)
│   ├── fact_policies.sql            (NEW – one row per active policy)
│   └── mart_portfolio_summary.sql   (NEW – cross-domain: active policies per partner city and product line)
└── tests/
    ├── assert_no_orphan_policies.sql (NEW – every policy.partnerId must exist in dim_partner)
    └── assert_premium_positive.sql   (NEW – all premium values > 0)
```

**mart_portfolio_summary.sql** – this is the killer CDO query:

```sql
-- Cross-domain analytics: impossible in a monolith without God schema
-- Possible in Data Mesh because every domain publishes its data product
SELECT
    p.city                  AS partner_city,
    pr.product_line,
    COUNT(f.policy_id)      AS active_policies,
    SUM(f.premium_chf)      AS total_premium_chf,
    AVG(f.premium_chf)      AS avg_premium_chf
FROM fact_policies f
JOIN dim_partner   p  ON f.partner_id = p.person_id
JOIN dim_product   pr ON f.product_id = pr.product_id
WHERE f.policy_status = 'ACTIVE'
GROUP BY 1, 2
ORDER BY 4 DESC
```

#### Layer 3 – Contract Governance (ODC Linting)

```text
infra/governance/
├── lint-contracts.py        ←  validates all ODC files: required fields, SLA defined, owner set
├── check-freshness.py       ←  queries Schema Registry + Kafka lag → alerts if SLA breached
└── governance-report.html   ←  generated HTML report (input to Portal)
```

**The federation principle in action:**

- Each domain team is responsible for *their* ODC files, tests, and quality scores
- The platform runs lint-contracts.py as a pre-merge check (simulated locally as a pre-commit hook)
- No central team writes the domain's tests – each team owns their governance code

---

### Addition 4 – Cross-Domain Analytics Demo (The "Wow" Moment)

**What:** A live-running pipeline that consumes from all three domains and produces a business intelligence view.

**Why it matters for the CDO:** Proves business value immediately. This is the "show, don't tell" moment.

**Extended Platform Consumer** (`infra/platform/consumer.py`):

- Currently only consumes `person.v1.*`
- Extend to also consume `product.v1.*` and `policy.v1.*`
- Write to three raw tables: `raw.person_events`, `raw.product_events`, `raw.policy_events`

**Extended dbt run** (`infra/dbt/run_dbt.py`):

- Run full dbt build: staging → marts → cross-domain mart
- Output: `mart_portfolio_summary` table in platform_db

**Demo UI in Portal** (`/demo`):

- Live table: Active policies by city and product line
- Shows real-time updates as policy events arrive
- One-sentence callout: "This view joins data from three independent domain teams. No central data warehouse. No data copy. No schema negotiation. Just data products."

---

## Implementation Sequence

### Step 0 – Baseline (already works)

```bash
podman compose up
# → Kafka, 3 services, Debezium, Schema Registry, AKHQ, Spark, dbt, platform consumer
```

### Step 1 – Upgrade ODC files (Day 1, ~2h)

For each of the 16 ODC files, add:

- `dataProduct.owner`, `dataProduct.domain`, `dataProduct.outputPort`
- `sla.freshness`, `sla.availability`, `sla.qualityScore`
- `tags` (pii, gdpr-subject where applicable)

Files:

```text
partner/src/main/resources/contracts/
  person.v1.created.odcontract.yaml
  person.v1.updated.odcontract.yaml
  person.v1.deleted.odcontract.yaml
  person.v1.state.odcontract.yaml       ← showcase: compacted state topic
  person.v1.address-added.odcontract.yaml
  person.v1.address-updated.odcontract.yaml

policy/src/main/resources/contracts/
  policy.v1.issued.odcontract.yaml
  policy.v1.changed.odcontract.yaml
  policy.v1.cancelled.odcontract.yaml
  policy.v1.coverage-added.odcontract.yaml
  policy.v1.coverage-removed.odcontract.yaml

product/src/main/resources/contracts/
  product.v1.defined.odcontract.yaml
  product.v1.updated.odcontract.yaml
  product.v1.deprecated.odcontract.yaml
```

### Step 2 – Extend Platform Consumer (Day 1, ~1h)

File: `infra/platform/consumer.py`

- Add `product.v1.defined`, `product.v1.updated` → `raw.product_events`
- Add `policy.v1.issued`, `policy.v1.cancelled` → `raw.policy_events`
- Update `infra/platform/init.sql` with new tables

### Step 3 – Extend dbt (Day 2, ~3h)

New files:

```text
infra/dbt/models/staging/stg_product_events.sql
infra/dbt/models/staging/stg_policy_events.sql
infra/dbt/models/marts/dim_product.sql
infra/dbt/models/marts/fact_policies.sql
infra/dbt/models/marts/mart_portfolio_summary.sql
infra/dbt/models/tests/assert_no_orphan_policies.sql
infra/dbt/models/tests/assert_premium_positive.sql
infra/dbt/models/sources.yml                        ← add product_events, policy_events sources
```

### Step 4 – Governance Layer (Day 2, ~2h)

New files:

```text
infra/governance/
├── lint-contracts.py
├── schema-compat-check.sh
├── check-freshness.py
└── README.md
```

Add to `docker-compose.yaml`:

```yaml
governance:
  build: ./infra/governance
  command: python lint-contracts.py && python check-freshness.py
  volumes:
    - ./partner/src/main/resources/contracts:/contracts/partner:ro
    - ./policy/src/main/resources/contracts:/contracts/policy:ro
    - ./product/src/main/resources/contracts:/contracts/product:ro
  depends_on: [schema-registry]
```

### Step 5 – Data Product Portal (Day 3, ~4h)

New files:

```text
infra/portal/
├── Dockerfile
├── main.py
├── templates/
│   ├── base.html
│   ├── index.html
│   ├── product.html
│   ├── lineage.html
│   └── governance.html
└── requirements.txt
```

Add to `docker-compose.yaml` on port 8090.

---

## Updated docker-compose Services Overview

```text
kafka           :9092   Kafka KRaft broker
schema-registry :8081   Confluent Schema Registry
akhq            :8085   Kafka UI
partner-db      :5432   PostgreSQL (partner domain)
product-db      :5433   PostgreSQL (product domain)
policy-db       :5434   PostgreSQL (policy domain)
platform-db     :5435   PostgreSQL (analytics / dbt target)  ← confirm port
partner         :9080   Partner Quarkus service
product         :9081   Product Quarkus service
policy          :9082   Policy Quarkus service
debezium        :8083   Debezium Connect
spark                   PySpark → Delta Lake warehouse
dbt                     dbt build (runs on schedule)
platform                Platform Kafka consumer
portal          :8090   Data Product Portal (NEW)
governance              Governance checks (NEW, runs at startup)
```

---

## CDO Demo Walkthrough (30-Minute Slot)

### Act 1 – The Problem (5 min)

"In a monolith, the data team is a bottleneck. Every analytics use case requires a ticket, a schema negotiation, and a three-week data pipeline project."

### Act 2 – The Platform (10 min)

1. Open AKHQ (`:8085`) → show 11 live Kafka topics, Schema Registry with Avro schemas
2. Open Portal (`:8090`) → Data Product Catalogue with all domains, owners, SLAs, tags
3. Click `person.v1.state` → show the compacted state topic: "The Partner team publishes the full person state as a data product. Any team can bootstrap their read model from this topic without calling the Partner team."
4. Open the Lineage page → show Policy consuming from both Partner and Product data products

### Act 3 – Federated Governance (10 min)

1. Open Portal → Governance Dashboard → show all schema compatibility checks green
2. Open dbt output → show `assert_no_orphan_policies` test result → "The policy team wrote this test themselves. The platform runs it automatically. No central data team required."
3. Show `lint-contracts.py` output → all ODC files have owners, SLAs, quality targets
4. Live demonstration: modify an Avro schema to break backward compatibility → schema-compat-check.sh fails → "The platform prevents data contract violations without a human reviewer"

### Act 4 – Business Value (5 min)

1. Open Portal → Demo page → `mart_portfolio_summary` table
2. "Active policies by city and product line. This joins data from three independent teams. No central warehouse. No ETL ticket. The data arrived here because each team published their data as a product."
3. Show the dbt SQL (5 lines) that produced this view
4. Close with: "When the Claims service is added, the claims data product appears in the catalogue automatically. The analytics team subscribes to it. No coordination needed."

---

## Key Architectural Talking Points

### "How is this different from a data warehouse?"

- In a warehouse, a *central team* extracts data from operational systems → bottleneck, stale data, coupling
- In Data Mesh, the *domain team* publishes their data product → fresh data, domain expertise baked in, no coupling

### "How do we avoid chaos without a central data team?"

- Governance is **computational**, not manual: Schema Registry enforces Avro compatibility, dbt tests enforce cross-domain contracts, ODC linter enforces metadata quality
- Each team *owns* their governance code; the platform *runs* it automatically
- This is **Federated Computational Governance**: federation = distributed responsibility, computational = enforced by code

### "What's the operational model?"

- **Platform team** maintains: Kafka, Schema Registry, dbt runner, Portal, governance tools
- **Domain teams** maintain: their ODC files, their dbt staging models, their Avro schemas
- Neither team can override the other's responsibility

### "What about GDPR / PII?"

- PII fields are tagged in every ODC file (`tags: [pii]`)
- The Portal shows which data products contain PII
- `person.v1.state` with compacted retention enables right-to-erasure via tombstone events (show in Avro schema: `deleted: boolean`)

---

## Success Criteria for the Demo

| Criterion | How to verify |
| --- | --- |
| All three services publish events independently | AKHQ shows messages on all 11 topics |
| Policy service has no DB access to partner or product | docker network inspection, no cross-db env vars |
| Cross-domain mart query runs without service calls | `mart_portfolio_summary` populated from dbt (offline, no live service calls) |
| Schema break is rejected automatically | `schema-compat-check.sh` exits non-zero on invalid .avsc |
| ODC contract with missing owner is rejected | `lint-contracts.py` exits non-zero |
| Every data product has a visible owner and SLA | Portal catalogue page renders all 16 products |

---

## File Checklist

### ODC Upgrades (16 files, Step 1)

- [ ] `partner/src/main/resources/contracts/person.v1.created.odcontract.yaml`
- [ ] `partner/src/main/resources/contracts/person.v1.updated.odcontract.yaml`
- [ ] `partner/src/main/resources/contracts/person.v1.deleted.odcontract.yaml`
- [ ] `partner/src/main/resources/contracts/person.v1.state.odcontract.yaml`
- [ ] `partner/src/main/resources/contracts/person.v1.address-added.odcontract.yaml`
- [ ] `partner/src/main/resources/contracts/person.v1.address-updated.odcontract.yaml`
- [ ] `policy/src/main/resources/contracts/policy.v1.issued.odcontract.yaml`
- [ ] `policy/src/main/resources/contracts/policy.v1.changed.odcontract.yaml`
- [ ] `policy/src/main/resources/contracts/policy.v1.cancelled.odcontract.yaml`
- [ ] `policy/src/main/resources/contracts/policy.v1.coverage-added.odcontract.yaml`
- [ ] `policy/src/main/resources/contracts/policy.v1.coverage-removed.odcontract.yaml`
- [ ] `product/src/main/resources/contracts/product.v1.defined.odcontract.yaml`
- [ ] `product/src/main/resources/contracts/product.v1.updated.odcontract.yaml`
- [ ] `product/src/main/resources/contracts/product.v1.deprecated.odcontract.yaml`

### New Infrastructure Files (Steps 2-5)

- [ ] `infra/platform/consumer.py` (extend for policy + product events)
- [ ] `infra/platform/init.sql` (add product_events, policy_events tables)
- [ ] `infra/dbt/models/staging/stg_product_events.sql`
- [ ] `infra/dbt/models/staging/stg_policy_events.sql`
- [ ] `infra/dbt/models/marts/dim_product.sql`
- [ ] `infra/dbt/models/marts/fact_policies.sql`
- [ ] `infra/dbt/models/marts/mart_portfolio_summary.sql`
- [ ] `infra/dbt/models/tests/assert_no_orphan_policies.sql`
- [ ] `infra/dbt/models/tests/assert_premium_positive.sql`
- [ ] `infra/dbt/models/sources.yml` (add product_events, policy_events)
- [ ] `infra/governance/lint-contracts.py`
- [ ] `infra/governance/schema-compat-check.sh`
- [ ] `infra/governance/check-freshness.py`
- [ ] `infra/governance/Dockerfile`
- [ ] `infra/portal/main.py`
- [ ] `infra/portal/templates/index.html`
- [ ] `infra/portal/templates/product.html`
- [ ] `infra/portal/templates/lineage.html`
- [ ] `infra/portal/templates/governance.html`
- [ ] `infra/portal/templates/demo.html`
- [ ] `infra/portal/Dockerfile`
- [ ] `infra/portal/requirements.txt`
- [ ] `docker-compose.yaml` (add portal, governance services)

---

## Effort Estimate

| Phase | Task | Effort |
| --- | --- | --- |
| Step 1 | Upgrade 16 ODC files | ~2h |
| Step 2 | Extend platform consumer (2 new domains) | ~1h |
| Step 3 | 7 new dbt models + sources + tests | ~3h |
| Step 4 | 3 governance scripts + Dockerfile | ~2h |
| Step 5 | Portal: FastAPI + 5 HTML templates | ~4h |
| **Total** | | **~12h** |

---

## DataHub: Local Demo & Production Path

### Kann DataHub lokal für die Demo laufen?

**Ja.** DataHub hat ein offizielles `docker quickstart` und läuft vollständig lokal. Es ist aber ein deutlich schwererer Stack als das custom Portal.

```bash
# DataHub CLI installieren
pip install acryl-datahub

# Lokalen Stack starten (~5–10 Min Startup)
datahub docker quickstart
# → öffnet http://localhost:9002  (DataHub UI)
```

**Resource-Anforderungen lokal:**

| Ressource | Minimum | Empfohlen |
| --- | --- | --- |
| RAM | 8 GB | 12 GB |
| CPU | 4 Cores | 6+ Cores |
| Disk | 10 GB | 20 GB |

**DataHub-Eigenkomponenten** (kommen on top unseres Stacks):

- Elasticsearch (Suchindex für Metadaten)
- MySQL (DataHub-interne Metadaten)
- DataHub GMS (Backend API)
- DataHub Frontend (React SPA)
- DataHub Actions (Event-basierte Automatisierung)
- DataHub Kafka (eigener interner Kafka — separiert von unserem)

> **Empfehlung für die Demo:** Den custom Portal (Step 5) als Default verwenden (startet in Sekunden, braucht ~200 MB RAM).
> DataHub nur dann starten wenn der CDO explizit nach einem Enterprise-Katalog fragt — dann als Live-Bonus zeigen.

---

### DataHub-Ingestion für dieses Projekt

Die zwei nativen Konnektoren, die auf unsere Infrastruktur passen:

#### 1. Kafka Schema Registry → DataHub

Liest alle registrierten Avro-Schemas und erstellt automatisch Dataset-Einträge pro Topic.

```yaml
# infra/datahub/recipe_kafka.yaml
source:
  type: kafka
  config:
    connection:
      bootstrap: "localhost:9092"
      schema_registry_url: "http://localhost:8081"

sink:
  type: datahub-rest
  config:
    server: "http://localhost:8080"
```

```bash
datahub ingest -c infra/datahub/recipe_kafka.yaml
```

**Resultat:** Alle 16 Topics erscheinen automatisch im DataHub-Katalog mit ihren Avro-Schemas, Schema-History und Compatibility-Level.

#### 2. dbt → DataHub

Liest das dbt `manifest.json` und `catalog.json` nach jedem dbt-Run und publiziert Modelle, Tests, Lineage.

```yaml
# infra/datahub/recipe_dbt.yaml
source:
  type: dbt
  config:
    manifest_path: "/dbt/target/manifest.json"
    catalog_path: "/dbt/target/catalog.json"
    sources_path: "/dbt/target/sources.json"
    target_platform: postgres

sink:
  type: datahub-rest
  config:
    server: "http://localhost:8080"
```

**Resultat in DataHub:**

- Lineage: `raw.person_events` → `stg_person_events` → `dim_partner` → `mart_portfolio_summary`
- dbt-Tests (`assert_no_orphan_policies`) erscheinen als Data Quality Assertions
- Jeder Modell-Owner aus `dbt` wird als DataHub-Owner übernommen

#### ~~3. PostgreSQL → DataHub~~ — VERBOTEN

> **Architekturprinzip: Data Inside vs. Data Outside**
>
> Die operativen Datenbanken (`partner_db`, `product_db`, `policy_db`) sind **Data Inside** —
> sie gehören ausschliesslich dem jeweiligen Domain-Team und sind hinter der Domänengrenze verborgen.
> Kein externer Prozess (DataHub, dbt, Spark, Analytics) darf direkt auf diese DBs zugreifen.
>
> **Einziger erlaubter Ausgang: Kafka-Topics (Data Outside).**
> Daten verlassen eine Domäne nur über ihre publizierten Data Products (Kafka-Topics mit ODC-Contract).
>
> Ein direkter DB-Zugriff würde die Domänenautonomie brechen (Schema-Änderungen würden externe
> Konsumenten kaputt machen), das Outbox-Pattern umgehen (keine Garantie auf konsistente Events)
> und Federated Governance aushebeln (kein ODC-Contract, kein Avro-Schema, keine SLA).

DataHub erhält Metadaten **ausschliesslich** über:

1. Kafka Schema Registry (Avro-Schemas der Topics)
2. dbt manifest/catalog (Transformations-Lineage)

Die operativen DB-Schemas sind für DataHub **nicht sichtbar** — das ist korrekt und gewollt.

---

### Was DataHub in der Demo zeigt (das custom Portal nicht kann)

| Feature | Custom Portal | DataHub |
| --- | --- | --- |
| Data Product Catalogue | ✅ (ODC-basiert) | ✅ (automatisch aus Kafka + dbt) |
| Lineage Graph | ✅ (manuell in D3.js) | ✅ (automatisch, interaktiv, Column-level) |
| Schema History / Versions | ❌ | ✅ |
| Impact Analysis ("Wer nutzt diesen Topic?") | ❌ | ✅ |
| Business Glossary (Ubiquitous Language) | ❌ | ✅ (z.B. "Policy = Police") |
| PII / GDPR Tagging mit Policy-Enforcement | ❌ | ✅ |
| Data Quality Assertions (dbt Tests) | ✅ (Ausgabe als HTML) | ✅ (nativ, mit Trend-History) |
| Ownership & Stewardship Workflows | ❌ | ✅ |
| Suche über alle Metadaten | ❌ | ✅ |

**Der stärkste DataHub-Demo-Moment für den CDO:**
Column-Level Lineage — zeigen dass das Feld `premium` in `mart_portfolio_summary` aus `policy.v1.issued.premium`
über `raw.policy_events.payload` → `stg_policy_events.premium_chf` → `fact_policies.premium_chf` kam.
Das ist der Beweis für lückenlose Datenherkunft ohne manuelle Dokumentation.

---

### Integration DataHub in docker-compose.yaml

Für die Demo-Option mit DataHub: DataHub als separates Compose-File, damit unser Haupt-Stack nicht beeinflusst wird.

```yaml
# infra/datahub/docker-compose.override.yaml
# Starten mit: podman compose -f docker-compose.yaml -f infra/datahub/docker-compose.override.yaml up
services:
  datahub-quickstart:
    image: linkedin/datahub-quickstart:head
    ports:
      - "9002:9002"   # DataHub Frontend
      - "8080:8080"   # DataHub GMS API
    environment:
      DATAHUB_GMS_HOST: datahub-gms
      DATAHUB_KAFKA_BOOTSTRAP: kafka:9092
    volumes:
      - datahub-data:/var/datahub
```

> Realistischer ist der offizielle `datahub docker quickstart` Befehl, der sein eigenes Compose-File verwaltet.
> Dann `datahub ingest` aus dem jeweiligen Recipe aufrufen.

---

### DataHub als Produktionspfad

Nach dem Prototype-Erfolg beim CDO ist der Übergang zu DataHub in Produktion klar:

| Prototype (jetzt) | Produktion (DataHub) |
| --- | --- |
| `infra/portal/main.py` | DataHub Frontend |
| `infra/governance/lint-contracts.py` | DataHub Assertions + Actions |
| `infra/governance/schema-compat-check.sh` | DataHub Schema Registry Integration |
| ODC YAML als Metadaten-Store | DataHub Metadata Graph (Neo4j/Elasticsearch) |
| Manuelle `datahub ingest` Aufrufe | DataHub Managed Ingestion (UI-konfigurierbar) |
| `infra/dbt/` lokal | dbt Cloud / dbt Core CI mit DataHub-Sink |

**Migration:** Die ODC-Felder (`dataProduct.owner`, `sla`, `tags`) können direkt per DataHub Ingestion-Recipe
als Custom Properties auf die jeweiligen Dataset-Entities gemappt werden — kein Informationsverlust.

**Empfohlene Produktions-Hosting-Option:** DataHub Cloud (Acryl) oder self-hosted auf Kubernetes.
Bei CSS-Grösse (~3–10 Domains) ist self-hosted auf einem dedizierten Node ausreichend.

---

### Updated Effort Estimate (mit DataHub-Option)

| Phase | Task | Effort |
| --- | --- | --- |
| Step 1 | Upgrade 16 ODC files | ~2h |
| Step 2 | Extend platform consumer (2 new domains) | ~1h |
| Step 3 | 7 new dbt models + sources + tests | ~3h |
| Step 4 | 3 governance scripts + Dockerfile | ~2h |
| Step 5a | Portal (Default): FastAPI + 5 HTML templates | ~4h |
| Step 5b | DataHub (Optional): 2 Ingestion Recipes + Override Compose | ~2h |
| **Total Default** | | **~12h** |
| **Total mit DataHub** | | **~14h** |

> Step 5a und 5b schliessen sich nicht aus — beides kann parallel laufen.
> Für die Demo: Portal zeigen, DataHub als "Und so sieht das in Produktion aus" live zeigen.

---

*This document is the implementation plan for the CSS Data Mesh CDO prototype.*
*Update the checklist as items are completed.*
