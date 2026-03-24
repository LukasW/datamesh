# Datamesh Kubernetes Analyse – Analytics, Governance & Lineage

> **Datum:** 2026-03-24
> **Scope:** Nur Kubernetes-Deployment (`infra/k8s/`), keine Docker-Compose-Analyse

---

## 1. Architektur-Übersicht (Ist-Zustand)

### Datenfluss-Pipeline

```
Domain-Services (Quarkus)
  │  INSERT → public.outbox (Transactional Outbox)
  ▼
Debezium PostgreSQL CDC (pgoutput, logical replication)
  │  Outbox Event Router → dynamisches Topic-Routing
  ▼
Kafka Topics (person.v1.*, product.v1.*, policy.v1.*, claims.v1.*, billing.v1.*, hr.v1.*)
  │
  ├──→ Iceberg Sink Connector (IcebergSinkConnector)
  │       │  JSON → Parquet, auto-create tables, schema evolution
  │       ▼
  │    MinIO S3 (s3://warehouse/)  ←── Nessie Catalog (Git-like Iceberg metadata)
  │       │
  │       ▼
  │    Trino (Federated SQL, Iceberg Connector via Nessie)
  │       │
  │       ├──→ SQLMesh (incremental Transformationen: raw → staging → marts)
  │       │       Output: analytics.dim_*, analytics.fact_*, analytics.mart_*
  │       │
  │       ├──→ Superset (BI Dashboards via Trino-Datasource)
  │       │
  │       └──→ Soda Core (Data Quality Checks)
  │
  ├──→ OpenMetadata (Kafka Ingestion → Topic-Metadaten)
  │
  └──→ Read Models in anderen Domains (z.B. Policy liest Partner-Events)

Synchrone Kommunikation:
  Policy ──gRPC──→ Product (Prämienberechnung, ADR-010)

Lineage:
  Debezium/Iceberg → OpenLineage → Marquez
  SQLMesh → OpenLineage → Marquez
  Marquez Web UI (marquez.localhost)
```

### Deployed Components (K8s)

| Kategorie | Komponente | K8s Resource | Status |
|---|---|---|---|
| **Lakehouse** | MinIO | StatefulSet (5Gi PVC) | Deployed |
| | Nessie | Deployment (IN_MEMORY) | Deployed, aber flüchtig |
| | Trino | Deployment (1536Mi) | Deployed |
| **Transformation** | SQLMesh | - | **NICHT deployed** |
| **Data Quality** | Soda Core | - | **NICHT deployed** |
| **Governance** | OpenMetadata Server | Deployment (2Gi) | Deployed |
| | OpenMetadata Ingestion (Airflow) | Deployment (2Gi) | Deployed |
| | Marquez | Deployment (512Mi) | Deployed |
| | Marquez Web UI | Deployment (256Mi) | Deployed |
| **BI** | Superset | Deployment (512Mi) | Deployed |
| **Messaging** | Kafka (KRaft) | StatefulSet (1Gi) | Deployed |
| | Schema Registry | Deployment (256Mi) | Deployed |
| | Debezium Connect | Deployment (1280Mi) | Deployed |
| **Security** | Keycloak | Deployment (512Mi) | Deployed |
| | Vault | Deployment (256Mi) | Deployed |
| **Observability** | Prometheus | Deployment (256Mi) | Deployed |
| | Grafana | Deployment (256Mi) | Deployed |
| | Jaeger | Deployment (256Mi) | Deployed |

### Init-Jobs (K8s)

| Job | Aufgabe | Status |
|---|---|---|
| `vault-init` | Transit Engine aktivieren | OK |
| `minio-init` | `warehouse` Bucket erstellen | OK |
| `kafka-init` | Topics erstellen (state, events, DLQ) | OK |
| `debezium-init` | 5 Outbox CDC Connectors registrieren | OK |
| `iceberg-init` | 6 Iceberg Sink Connectors registrieren | OK |
| `seed-data` | Test-Daten via REST APIs einspielen | OK |
| **openmetadata-init** | **Services, Pipelines, PII Tags registrieren** | **FEHLT** |
| **schema-registry-init** | **JSON Schemas registrieren** | **FEHLT** |
| **sqlmesh-init** | **sqlmesh plan --auto-apply** | **FEHLT** |

---

## 2. Zielerfüllung

### Ziel 1: Queries in Apache Superset über Kafka Topics und HR-Daten (Iceberg)

**Status: NICHT ERFÜLLT**

**Was funktioniert (konfiguriert):**
- Superset ist deployed mit Custom-Image (`yuno/superset:local`) inkl. Trino-Driver (`trino` pip package)
- Init-Script (`superset-init.sh`) erstellt automatisch die Trino-Datasource: `trino://trino@trino:8086/iceberg`
- Schema-Discovery erlaubt (`allow_multi_schema_metadata_fetch: true`)
- Trino Iceberg-Catalog via Nessie ist konfiguriert
- Iceberg Sink Connectors für alle 6 Domains sind via `iceberg-init` Job registriert
- SQLMesh-Modelle definieren vollständige Analytics-Schicht (8 staging, 7 dims/facts, 5 marts)

**Was NICHT funktioniert:**
1. **Trino war nicht via Ingress erreichbar** (HTTP 406) → BEHOBEN (`http-server.process-forwarded=true`)
2. **SQLMesh ist nicht in K8s deployed** → Kein K8s Job/CronJob. `analytics.*` Schema (staging, dim/fact/mart) wird nie erstellt.
3. **Trino Vault UDF Plugin nicht gemountet** → `vault_decrypt()` Funktion nicht verfügbar. PII-Entschlüsselung in `stg_person_events` und `stg_address_events` schlägt fehl.
4. **Nessie IN_MEMORY** → Bei Pod-Restart gehen alle Iceberg-Metadaten verloren. Raw-Tabellen müssten komplett neu aufgebaut werden.

**Betroffene Tabellen (alle nicht vorhanden):**

| Schema | Tabellen |
|---|---|
| `partner_raw` | `person_events` |
| `product_raw` | `product_events` |
| `policy_raw` | `policy_events` |
| `claims_raw` | `claims_events` |
| `billing_raw` | `billing_events` |
| `hr_raw` | `employee_events`, `org_unit_events` |
| `analytics` | `stg_*`, `dim_partner`, `dim_product`, `dim_employee`, `dim_org_unit`, `dim_partner_address`, `fact_policies`, `fact_claims`, `fact_invoices`, `mart_portfolio_summary`, `mart_management_kpi`, `mart_financial_summary`, `mart_policy_detail`, `mart_org_hierarchy` |

### Ziel 2: Open Data Contracts in OpenMetadata

**Status: NICHT ERFÜLLT**

**Was funktioniert:**
- OpenMetadata Server + Ingestion (Airflow) + OpenSearch sind deployed
- Airflow-DB wird via `init-airflow-db.sql` (ConfigMap → initdb) erstellt
- Ingestion-Konfigurationen existieren als JSON-Dateien:
  - `kafka-ingestion.json` – Kafka Topics
  - `trino-ingestion.json` – Iceberg Tables
  - `odc-ingestion.json` – ODC Contracts (Custom-Konzept)
  - `pii-classification.json` – PII Tags & Retention
  - `marquez-lineage.json` – Lineage-Import

**Was NICHT funktioniert:**
1. **Kein OpenMetadata-Init-Job im K8s-Deployment** – `deploy.sh` erstellt zwar die ConfigMaps, führt aber NICHT die äquivalenten Schritte von `scripts/init-openmetadata.sh` aus:
   - Keine Service-Registration (Kafka, Trino)
   - Keine Ingestion-Pipeline-Erstellung via Airflow
   - Keine PII-Tag-Erstellung
   - Keine Airflow-DAG-Deployments
2. **ODC-Ingestion ist ein Custom-Konzept** – OpenMetadata hat keinen nativen ODC-Connector. `odc-ingestion.json` erfordert einen Custom-Workflow.
3. **HR-Topics fehlen im Kafka-Ingestion-Filter** – `kafka-ingestion.json` enthält kein `hr.v1.*` Pattern.

**Vergleich Docker-Compose vs. K8s:**

| Schritt | Docker-Compose (`compose.sh`) | K8s (`deploy.sh`) |
|---|---|---|
| Services registrieren | `scripts/init-openmetadata.sh` (Zeile 117) | **FEHLT** |
| Ingestion-Pipelines erstellen | init-openmetadata.sh Schritt 6 | **FEHLT** |
| PII Tags erstellen | init-openmetadata.sh Schritt 5 | **FEHLT** |
| Schema-Registration | `scripts/register-schemas.sh` (Zeile 114) | **FEHLT** |

### Ziel 3: Daten-Lineage sichtbar

**Status: TEILWEISE – Infrastruktur steht, aber kaum Lineage-Daten**

**Wo Lineage sichtbar sein sollte:**
- **Marquez Web UI**: `http://marquez.localhost` – primäre Lineage-Visualisierung
- **OpenMetadata**: `http://openmetadata.localhost` – via Marquez-Integration (Pipeline fehlt)

**Lineage-Quellen:**

| Quelle | Ziel | Mechanismus | Status |
|---|---|---|---|
| Debezium CDC | Marquez | `OPENLINEAGE_URL` env + Java JARs | OpenLineage JARs im Image, aber Debezium hat keine native Integration |
| Iceberg Sink | Marquez | `OPENLINEAGE_URL` env | Gleiche Einschränkung – kein Connector-Listener konfiguriert |
| SQLMesh | Marquez | `openlineage.transport.url` in config.yaml | **SQLMesh nicht deployed** |
| Product Service | Marquez | `OPENLINEAGE_URL` env | Konfiguriert im K8s Deployment |
| gRPC Calls | Jaeger | OTLP Tracing | Funktioniert (aber Tracing != Daten-Lineage) |

---

## 3. Aktualität der Daten

### Analytics-Daten (Superset via Trino)

| Schicht | Latenz (Soll) | Aktualisierung | Status K8s |
|---|---|---|---|
| Domain DB → Kafka | ~Sekunden | Echtzeit (CDC) | OK, wenn Debezium-Connectors laufen |
| Kafka → Iceberg (raw) | ~10 Sekunden | `commit.interval-ms: 10000` | OK, wenn Iceberg Sink Connectors laufen |
| Iceberg raw → analytics staging | View (live) | Trino-View bei Query-Time | FEHLT (SQLMesh) |
| Staging → marts (dim/fact) | @hourly | SQLMesh Cron | FEHLT (SQLMesh) |
| Marts → Superset | Live | SQL-at-Query-Time via Trino | FEHLT (keine Marts) |

**Theoretische End-to-End-Latenz:** ~10-15s (raw) bis max. 1h (marts)
**Aktuelle Realität:** Keine Analytics-Daten, da SQLMesh fehlt und Iceberg-Tabellen möglicherweise nicht persistiert sind (Nessie IN_MEMORY).

### Governance-Daten (OpenMetadata)

| Datenquelle | Geplante Aktualisierung | Mechanismus | Status K8s |
|---|---|---|---|
| Trino/Iceberg Metadaten | Alle 6h | Airflow Pipeline | FEHLT – Pipeline nicht registriert |
| Kafka Topics | Alle 6h | Airflow Pipeline | FEHLT – Pipeline nicht registriert |
| PII-Klassifikation | Einmalig | init-openmetadata.sh | FEHLT – Script nicht ausgeführt |
| ODC Contracts | Custom | odc-ingestion.json | FEHLT – Kein nativer Connector |
| Lineage (Marquez) | Alle 5min | marquez-lineage.json | FEHLT – Pipeline nicht registriert |

---

## 4. Informationsquellen pro System

### Analytics

| Information | Tabelle/Schema | System | Verfügbar? |
|---|---|---|---|
| Partner-Stammdaten (entschlüsselt) | `analytics.dim_partner` | Superset / Trino | Nein |
| Partner-Adressen | `analytics.dim_partner_address` | Superset / Trino | Nein |
| Produkt-Katalog | `analytics.dim_product` | Superset / Trino | Nein |
| Policen-Details | `analytics.fact_policies`, `mart_policy_detail` | Superset / Trino | Nein |
| Schadensfälle | `analytics.fact_claims` | Superset / Trino | Nein |
| Rechnungen | `analytics.fact_invoices` | Superset / Trino | Nein |
| Portfolio-KPIs | `analytics.mart_portfolio_summary` | Superset / Trino | Nein |
| Management-KPIs | `analytics.mart_management_kpi` | Superset / Trino | Nein |
| Finanz-Übersicht | `analytics.mart_financial_summary` | Superset / Trino | Nein |
| HR Mitarbeiter | `analytics.dim_employee` | Superset / Trino | Nein |
| HR Org-Einheiten | `analytics.dim_org_unit`, `mart_org_hierarchy` | Superset / Trino | Nein |
| Raw Events (alle Domains) | `{domain}_raw.*_events` | Trino | Nein (Nessie IN_MEMORY) |

### Governance

| Information | System | URL | Verfügbar? |
|---|---|---|---|
| Tabellen-Metadaten (Iceberg) | OpenMetadata | `http://openmetadata.localhost` | Nein |
| Kafka-Topic-Metadaten | OpenMetadata | `http://openmetadata.localhost` | Nein |
| PII-Tags auf Spalten | OpenMetadata | `http://openmetadata.localhost` | Nein |
| Data Contracts (ODC) | OpenMetadata | `http://openmetadata.localhost` | Nein |
| Daten-Lineage (Graphen) | Marquez Web | `http://marquez.localhost` | Nein (keine Events) |
| Request-Tracing | Jaeger | `http://jaeger.localhost` | **Ja** |
| Metriken (HTTP, JVM, Kafka) | Grafana | `http://grafana.localhost` | **Ja** |

---

## 5. Fehleranalyse: Warum keine Daten in Superset und OpenMetadata?

### Problem 1: SQLMesh nicht in K8s deployed (KRITISCH)

**Symptom:** Kein `analytics`-Schema in Trino → Superset hat keine brauchbaren Tabellen
**Ursache:** Dockerfile (`infra/sqlmesh/Dockerfile`) und 24 SQL-Modelle existieren, aber:
- Kein K8s Deployment, Job oder CronJob
- SQLMesh-Image wird in `build.sh` nicht gebaut
- Kein Image-Load in `deploy.sh`

**Auswirkung:** Gesamte Transformation-Schicht fehlt (8 staging + 7 dim/fact + 5 marts + 3 tests).

### Problem 2: Trino Vault UDF Plugin nicht gemountet (KRITISCH)

**Symptom:** `vault_decrypt()` Funktion nicht verfügbar
**Ursache:** Das Plugin-JAR (`infra/trino/vault-udf/target/`) wird in keinem Volume/ConfigMap in den Trino-Container gemountet.
**Auswirkung:** `stg_person_events` und `stg_address_events` (SQLMesh) schlagen fehl → keine PII-Entschlüsselung → fehlerhafte Dimensions.

### Problem 3: Nessie IN_MEMORY Storage (KRITISCH)

**Symptom:** Alle Iceberg-Metadaten gehen bei Nessie-Restart verloren
**Ursache:** `NESSIE_VERSION_STORE_TYPE: IN_MEMORY` in `lakehouse.yaml`
**Auswirkung:** Instabiler Iceberg-Catalog. Parquet-Files in MinIO bleiben, aber Trino kann sie nicht referenzieren.

### Problem 4: OpenMetadata Init fehlt (KRITISCH)

**Symptom:** OpenMetadata UI komplett leer – keine Topics, keine Tabellen, keine Tags
**Ursache:** `deploy.sh` führt `scripts/init-openmetadata.sh` nicht aus (nur `compose.sh` tut das).
**Fehlende Schritte:**
1. Kafka Messaging Service registrieren
2. Trino Database Service registrieren
3. Ingestion-Pipelines erstellen und in Airflow deployen
4. PII Classification + Tags anlegen
5. Initialen Ingestion-Run triggern

### Problem 5: Schema Registry nicht initialisiert (MITTEL)

**Symptom:** Schema Registry hat keine JSON-Schemas für die Domain-Topics
**Ursache:** `scripts/register-schemas.sh` wird nicht im K8s-Deployment aufgerufen.
**Auswirkung:** OpenMetadata Kafka-Ingestion hat keine Schema-Informationen → Topic-Metadaten unvollständig.

### Problem 6: OpenLineage nicht aktiv in Kafka Connect (MITTEL)

**Symptom:** Marquez zeigt keine Lineage-Events von Debezium/Iceberg Sink
**Ursache:** OpenLineage Java JARs sind installiert, `OPENLINEAGE_URL` gesetzt, aber:
- Kein `CONNECT_CONNECTOR_CLIENT_CONFIG_OVERRIDE_POLICY: All`
- Kein OpenLineage Connector Listener konfiguriert
- Debezium PostgreSQL Connector hat keine native OpenLineage-Integration

### Problem 7: HR Topics fehlen in Kafka-Ingestion-Filter (NIEDRIG)

**Symptom:** `hr.v1.*` Topics nicht in OpenMetadata sichtbar
**Ursache:** `kafka-ingestion.json` und `init-openmetadata.sh` filtern nur 5 Domains, nicht HR.
**Fix:** `"hr\\.v1\\..*"` zum Filter hinzufügen.

### Problem 8: Trino X-Forwarded-For (BEHOBEN)

**Symptom:** HTTP ERROR 406 bei `http://trino.localhost`
**Fix:** `http-server.process-forwarded=true` in `config.properties` hinzugefügt.

---

## 6. Zusammenfassung der Fehler (priorisiert)

| # | Severity | Problem | Betroffene Systeme |
|---|---|---|---|
| 1 | KRITISCH | SQLMesh nicht deployed – keine Analytics-Tabellen | Superset, Trino |
| 2 | KRITISCH | Trino Vault UDF nicht gemountet – PII-Entschlüsselung unmöglich | Trino, SQLMesh |
| 3 | KRITISCH | Nessie IN_MEMORY – Metadaten flüchtig bei Restart | Trino, Iceberg |
| 4 | KRITISCH | OpenMetadata Init fehlt – Governance komplett leer | OpenMetadata |
| 5 | MITTEL | Schema Registry nicht initialisiert | Schema Registry, OpenMetadata |
| 6 | MITTEL | OpenLineage nicht aktiv in Kafka Connect | Marquez, Lineage |
| 7 | NIEDRIG | HR Topics fehlen im OpenMetadata-Filter | OpenMetadata |
| 8 | BEHOBEN | Trino X-Forwarded-For Header | Trino |

---

## 7. Referenz: Wo finde ich was?

| Frage | System | URL |
|---|---|---|
| Welche Kafka Topics gibt es? | AKHQ | `http://akhq.localhost` |
| Welche Iceberg-Tabellen gibt es? | Trino UI | `http://trino.localhost` |
| Was steht in den Parquet-Files? | MinIO Console | `http://minio.localhost` |
| Welchen Status haben die Debezium-Connectors? | Debezium REST | `http://debezium.localhost/connectors` |
| Wo sind meine Daten dokumentiert? | OpenMetadata | `http://openmetadata.localhost` (aktuell leer) |
| Wo sehe ich Daten-Lineage? | Marquez Web UI | `http://marquez.localhost` (aktuell leer) |
| Wo sehe ich Request-Tracing? | Jaeger | `http://jaeger.localhost` |
| Wo sehe ich Metriken? | Grafana/Prometheus | `http://grafana.localhost` |
| Wo kann ich SQL-Queries machen? | Superset SQL Lab | `http://superset.localhost` (Tabellen fehlen) |
| Wo ist der Nessie Catalog? | Nessie API | `http://nessie.localhost/api/v2/trees` |
| Wo sind die ODC Contracts? | Im Code | `{domain}/src/main/resources/contracts/*.odcontract.yaml` |
| Wo sind die SQLMesh-Modelle? | Im Code | `infra/sqlmesh/models/` (staging + marts) |
