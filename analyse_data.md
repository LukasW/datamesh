# Datamesh Kubernetes Analyse – Analytics, Governance & Lineage

> **Datum:** 2026-03-23
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
| **Lakehouse** | MinIO | StatefulSet | ✅ Deployed |
| | Nessie | Deployment | ✅ Deployed |
| | Trino | Deployment | ✅ Deployed |
| **Transformation** | SQLMesh | - | ❌ **NICHT deployed** |
| **Data Quality** | Soda Core | - | ❌ **NICHT deployed** |
| **Governance** | OpenMetadata Server | Deployment | ✅ Deployed |
| | OpenMetadata Ingestion (Airflow) | Deployment | ✅ Deployed |
| | Marquez | Deployment | ✅ Deployed |
| | Marquez Web UI | Deployment | ✅ Deployed |
| **BI** | Superset | Deployment | ✅ Deployed |
| **Messaging** | Kafka (KRaft) | StatefulSet | ✅ Deployed |
| | Schema Registry | Deployment | ✅ Deployed |
| | Debezium Connect | Deployment | ✅ Deployed |

---

## 2. Zielerfüllung

### Ziel 1: Queries in Apache Superset über Kafka Topics und HR-Daten (Iceberg)

**Status: ⚠️ Teilweise – Pipeline ist architektonisch korrekt, aber Daten fehlen**

**Was funktioniert (konfiguriert):**
- Superset ist deployed mit custom Image (`yuno/superset:local`) inkl. Trino-Driver
- Init-Script (`superset-init.sh`) erstellt automatisch die Trino-Datasource: `trino://trino@trino:8086/iceberg`
- Schema-Discovery erlaubt für `analytics`, `partner_raw`, `product_raw`, `policy_raw`, `billing_raw`, `claims_raw`
- Trino Iceberg-Catalog via Nessie ist konfiguriert

**Was NICHT funktioniert (Probleme):**
1. **SQLMesh ist nicht in K8s deployed** – Es gibt ein Dockerfile und Modelle, aber kein K8s Deployment/Job. Die `analytics.*` Schemas (staging views, dim/fact/mart Tabellen) werden nie erstellt.
2. **Trino Vault UDF Plugin ist nicht gemountet** – Das Plugin-JAR wird gebaut (`infra/trino/vault-udf/`), aber das K8s Trino-Deployment mountet es nicht. Die `vault_decrypt()` Funktion in den SQLMesh-Staging-Modellen (`stg_person_events`, `stg_address_events`) würde fehlschlagen.
3. **Nessie verwendet IN_MEMORY Storage** – Bei jedem Nessie-Pod-Restart gehen alle Iceberg-Tabellen-Metadaten verloren. Parquet-Files in MinIO bleiben erhalten, aber Trino kann sie nicht mehr finden.

### Ziel 2: Open Data Contracts in OpenMetadata

**Status: ❌ Nicht erfüllt**

**Was funktioniert:**
- OpenMetadata Server + Ingestion (Airflow) sind deployed
- OpenSearch (Elasticsearch) ist deployed als Search-Backend
- Airflow-Datenbank wird per `init-airflow-db.sql` erstellt
- JSON-Konfigurationsdateien existieren:
  - `trino-ingestion.json` (Trino/Iceberg-Metadaten)
  - `kafka-ingestion.json` (Kafka-Topic-Metadaten)
  - `odc-ingestion.json` (ODC YAML Contracts)
  - `pii-classification.json` (PII-Tagging)
  - `marquez-lineage.json` (Lineage-Integration)

**Was NICHT funktioniert:**
1. **Ingestion-Pipelines werden NICHT registriert** – Die JSON-Dateien liegen in `infra/openmetadata/`, aber es gibt keinen K8s Job oder Init-Container, der diese via OpenMetadata REST API registriert. Die Dateien werden nicht einmal als ConfigMap gemountet.
2. **ODC-Ingestion ist ein Custom-Konzept** – OpenMetadata hat keinen nativen ODC-Ingestion-Typ. `odc-ingestion.json` hat `serviceType: "metadata"`, was kein Standard-OpenMetadata-Connector ist. Die Mapping-Rules (`$.dataProduct.outputPort.topic` etc.) erfordern einen Custom Ingestion Workflow.
3. **PII-Classification ist nicht automatisiert** – `pii-classification.json` definiert Tags und Table/Column-Zuweisungen, wird aber nie über die OpenMetadata API angewendet.

### Ziel 3: Daten-Lineage sichtbar

**Status: ⚠️ Teilweise – Infrastruktur steht, aber kaum Lineage-Daten fliessen**

**Wo Lineage sichtbar ist (theoretisch):**
- **Marquez Web UI**: `http://marquez.localhost` – zeigt Lineage-Graphen aus OpenLineage-Events
- **OpenMetadata**: Hätte Lineage via Marquez-Integration (`marquez-lineage.json`), aber Pipeline ist nicht registriert

**Lineage-Quellen:**
| Quelle | Emittiert Lineage? | Ziel | Status |
|---|---|---|---|
| Debezium Connect | via OpenLineage Java lib | Marquez | ⚠️ JAR ist im Image, aber `OPENLINEAGE_URL` reicht nicht für alle Connector-Typen |
| Iceberg Sink Connector | via OpenLineage Java lib | Marquez | ⚠️ Gleiche Einschränkung |
| SQLMesh | via `openlineage.transport` Config | Marquez | ❌ SQLMesh läuft nicht in K8s |
| Product Service (gRPC) | QUARKUS_OTEL → Jaeger | Jaeger (Tracing) | ✅ Traces, aber keine Daten-Lineage |
| Policy Service (gRPC Client) | QUARKUS_OTEL → Jaeger | Jaeger | ✅ Traces |

**Problem:** OpenLineage-Integration in Kafka Connect ist nicht trivial. Die OpenLineage Java JARs sind im Debezium-Image installiert, und `OPENLINEAGE_URL` ist gesetzt, aber:
- Der Debezium PostgreSQL Connector hat **keine native OpenLineage-Integration** – die JARs allein reichen nicht
- Der Iceberg Sink Connector hat ebenfalls **keine automatische OpenLineage-Emission** – es müsste ein Kafka Connect OpenLineage Listener Plugin konfiguriert werden

---

## 3. Aktualität der Daten

### Analytics-Daten (Superset)

| Schicht | Latenz | Aktualisierung | Status |
|---|---|---|---|
| Outbox → Kafka | ~Sekunden | Echtzeit (CDC) | ✅ Wenn Debezium-Connector läuft |
| Kafka → Iceberg (raw) | ~10 Sekunden | `commit.interval-ms: 10000` | ✅ Wenn Iceberg Sink läuft |
| Iceberg raw → staging | View (live) | Trino-View-Auflösung bei Query | ❌ Views existieren nicht (SQLMesh) |
| Staging → marts (dim/fact) | @hourly | SQLMesh cron: `@hourly` | ❌ SQLMesh läuft nicht |
| Marts → Superset | Live | SQL-at-Query-Time via Trino | ❌ Keine Marts vorhanden |

**Theoretische End-to-End-Latenz:** ~10-15 Sekunden (raw) bis max. 1 Stunde (marts)
**Aktuelle Realität:** Keine Analytics-Daten verfügbar, weil SQLMesh nicht deployed ist und die Raw-Tabellen möglicherweise nicht existieren (Nessie IN_MEMORY).

### Governance-Daten (OpenMetadata)

| Datenquelle | Aktualisierung | Status |
|---|---|---|
| Trino/Iceberg Tabellen-Metadaten | On-demand (Ingestion Pipeline) | ❌ Pipeline nicht registriert |
| Kafka Topics | On-demand | ❌ Pipeline nicht registriert |
| PII-Klassifikation | Manuell / Pipeline | ❌ Nicht angewendet |
| ODC Contracts | Custom Pipeline | ❌ Kein Standard-Connector |
| Lineage (via Marquez) | `syncInterval: 5m` | ❌ Pipeline nicht registriert |

---

## 4. Informationsquellen pro System

### Analytics-System (Superset / Trino)

| Information | Wo? | Verfügbar? |
|---|---|---|
| Partner-Stammdaten (entschlüsselt) | `analytics.dim_partner` | ❌ |
| Partner-Adressen | `analytics.dim_partner_address` | ❌ |
| Produkt-Katalog | `analytics.dim_product` | ❌ |
| Policen-Details | `analytics.fact_policies`, `analytics.mart_policy_detail` | ❌ |
| Schadensfälle | `analytics.fact_claims` | ❌ |
| Rechnungen | `analytics.fact_invoices` | ❌ |
| Portfolio-Übersicht | `analytics.mart_portfolio_summary` | ❌ |
| Management-KPIs | `analytics.mart_management_kpi` | ❌ |
| Finanz-Übersicht | `analytics.mart_financial_summary` | ❌ |
| HR Mitarbeiter | `analytics.dim_employee` | ❌ |
| HR Org-Einheiten | `analytics.dim_org_unit`, `analytics.mart_org_hierarchy` | ❌ |
| Raw Events (alle Domains) | `{domain}_raw.*_events` | ⚠️ Nur wenn Iceberg Sink + Nessie funktionieren |

### Governance-System (OpenMetadata)

| Information | Wo? | Verfügbar? |
|---|---|---|
| Tabellen-Metadaten (Iceberg) | OpenMetadata → Trino Ingestion | ❌ |
| Kafka-Topic-Metadaten | OpenMetadata → Kafka Ingestion | ❌ |
| PII-Tags auf Spalten | OpenMetadata → PII Classification | ❌ |
| Daten-Lineage | OpenMetadata → Marquez Integration | ❌ |
| Data Contracts (ODC) | OpenMetadata → Custom Ingestion | ❌ |
| Data Quality Results | OpenMetadata → Soda Integration | ❌ |

### Lineage-System (Marquez)

| Lineage-Pfad | Wo sichtbar? | Verfügbar? |
|---|---|---|
| Outbox → Kafka Topic | Marquez Web UI | ❌ (OpenLineage nicht aktiv in Debezium) |
| Kafka → Iceberg Table | Marquez Web UI | ❌ (OpenLineage nicht aktiv in Iceberg Sink) |
| Raw → Staging → Marts | Marquez Web UI | ❌ (SQLMesh nicht deployed) |
| gRPC: Policy → Product | Jaeger UI (Tracing) | ✅ (aber Tracing, nicht Daten-Lineage) |

---

## 5. Fehleranalyse: Warum werden keine Daten in Superset und OpenMetadata angezeigt?

### Problem 1: SQLMesh ist nicht in K8s deployed (KRITISCH)

**Symptom:** Keine `analytics.*` Tabellen in Trino, Superset zeigt keine Daten
**Ursache:** Es existiert ein Dockerfile (`infra/sqlmesh/Dockerfile`) und SQLMesh-Modelle, aber:
- Kein K8s Deployment, Job oder CronJob für SQLMesh
- Kein Image-Build für SQLMesh in `build.sh` (nur `debezium-connect` und `superset`)
- Kein Image-Load in `deploy.sh`

**Auswirkung:** Die gesamte Transformation-Schicht (staging views, dimensions, facts, marts) fehlt. Superset hat zwar die Trino-Datasource, aber keine queryable Tabellen im `analytics` Schema.

**Fix:**
1. SQLMesh Image in `build.sh` bauen
2. K8s Job oder CronJob erstellen (einmalig `sqlmesh plan --auto-apply`, dann periodisch)
3. Image in `deploy.sh` laden

### Problem 2: Trino Vault UDF Plugin nicht gemountet (KRITISCH)

**Symptom:** `vault_decrypt()` Funktion nicht verfügbar in Trino
**Ursache:** Das Trino K8s Deployment mountet nur `trino-config` und `trino-catalog` ConfigMaps. Das Vault UDF Plugin JAR (`trino-vault-decrypt-1.0.0-SNAPSHOT.jar`) wird nirgends in den Trino Container injiziert.

**Auswirkung:** Alle SQLMesh-Staging-Modelle, die `vault_decrypt()` verwenden (`stg_person_events`, `stg_address_events`), würden fehlschlagen. PII-Felder (Name, Geburtsdatum, AHV-Nummer, Adressen) können nicht entschlüsselt werden.

**Fix:**
1. Trino als Custom Image bauen (Plugin-JAR in `/usr/lib/trino/plugin/vault/` kopieren)
2. Oder: Plugin-JAR als ConfigMap/PVC mounten und `plugin.dir` in Trino konfigurieren

### Problem 3: Nessie IN_MEMORY Storage (KRITISCH)

**Symptom:** Nach Nessie-Restart sind alle Iceberg-Tabellen weg
**Ursache:** `NESSIE_VERSION_STORE_TYPE: IN_MEMORY` in `lakehouse.yaml`

**Auswirkung:** Nessie verliert alle Catalog-Metadaten bei Pod-Restart. Parquet-Files in MinIO bleiben, aber Trino kann sie nicht mehr referenzieren. Die Iceberg Sink Connectors müssten alle Tabellen neu erstellen.

**Fix:**
1. Nessie auf `JDBC` oder `ROCKSDB` Backend umstellen
2. Für JDBC: Eigene PostgreSQL-Datenbank hinzufügen
3. Für RocksDB: PVC mounten

### Problem 4: OpenMetadata Ingestion-Pipelines nicht registriert (KRITISCH)

**Symptom:** OpenMetadata zeigt keine Tabellen, Topics oder Lineage
**Ursache:** Die Ingestion-Konfigurationen (`trino-ingestion.json`, `kafka-ingestion.json`, etc.) liegen nur als Dateien in `infra/openmetadata/` vor. Es gibt keinen Init-Job, der diese via `POST /api/v1/services/ingestionPipelines` in OpenMetadata registriert.

**Auswirkung:** OpenMetadata hat keine Datenquellen konfiguriert und führt keine Ingestion aus. Alle Governance-Features (Tabellen-Discovery, PII-Tags, Lineage, Topic-Metadaten) sind leer.

**Fix:**
1. Init-Job erstellen, der nach OpenMetadata-Start:
   a. Trino Database Service erstellt
   b. Kafka Messaging Service erstellt
   c. Ingestion Pipelines registriert
   d. Initialen Ingestion-Run triggert
2. Die JSON-Dateien in die OpenMetadata-API-Struktur (`CreateIngestionPipeline`) konvertieren

### Problem 5: OpenLineage nicht aktiv in Kafka Connect (MITTEL)

**Symptom:** Marquez zeigt keine Lineage-Events
**Ursache:** Die OpenLineage Java JARs sind im Debezium-Image installiert und `OPENLINEAGE_URL` ist gesetzt, aber:
- Debezium PostgreSQL Connector unterstützt OpenLineage nicht nativ
- Es fehlt der `OpenLineageConnectorListener` in der Connect-Worker-Konfiguration
- Der Iceberg Sink Connector braucht explizite OpenLineage-Integration

**Fix:**
1. `CONNECT_CONNECTOR_CLIENT_CONFIG_OVERRIDE_POLICY: All` setzen
2. OpenLineage Transport Listener konfigurieren
3. Alternativ: Lineage extern via Custom Job aus Connector-Metadaten ableiten

### Problem 6: Airflow-Datenbank-Initialisierung (MITTEL)

**Symptom:** OpenMetadata Ingestion (Airflow) kann möglicherweise nicht starten
**Ursache:** Die `init-airflow-db.sql` erstellt die `airflow` Datenbank, aber:
- Das Script verwendet `CREATE DATABASE airflow` – dies funktioniert nur beim ersten Start der openmetadata-db
- Die Airflow-Connection-URL zeigt auf `postgresql+psycopg2://openmetadata:openmetadata@openmetadata-db:5432/airflow`
- Die ConfigMap `openmetadata-db-init` wird zwar erstellt (in `deploy.sh`), aber ob sie beim richtigen Zeitpunkt (initdb) gemountet ist, hängt davon ab, ob die PVC leer ist

**Fix:** Airflow-DB-Erstellung verifizieren; ggf. als initContainer in der Airflow-Deployment lösen.

### Problem 7: Soda Core nicht in K8s deployed (NIEDRIG)

**Symptom:** Keine Data Quality Checks werden ausgeführt
**Ursache:** Dockerfile und Check-Definitionen existieren, aber kein K8s CronJob

**Auswirkung:** Data Quality wird nicht validiert. Keine Freshness-, Uniqueness- oder Null-Checks.

**Fix:** K8s CronJob erstellen, der periodisch `soda scan` ausführt.

### Problem 8: HR Topics fehlen in Kafka-Ingestion (NIEDRIG)

**Symptom:** HR-Topics werden in OpenMetadata nicht entdeckt
**Ursache:** `kafka-ingestion.json` filtert nur `person.v1.*`, `product.v1.*`, `policy.v1.*`, `claims.v1.*`, `billing.v1.*` – die `hr.v1.*` Topics fehlen im Filter.

**Fix:** `"hr\\.v1\\..*"` zum `topicFilterPattern.includes` Array in `kafka-ingestion.json` hinzufügen.

### Problem 9: Superset hat keine vorkonfigurierten Dashboards (NIEDRIG)

**Symptom:** Superset zeigt nach Login eine leere Dashboard-Liste
**Ursache:** Das Init-Script erstellt nur die Trino-Datasource, aber keine Dashboards, Charts oder Datasets. Selbst wenn die Tabellen existieren würden, müsste der User alles manuell erstellen.

**Fix:** Dashboard-JSON exportieren und im Init-Script via `superset import-dashboards` laden.

---

## 6. Zusammenfassung der Fehler (priorisiert)

| # | Severity | Problem | Betroffene Systeme |
|---|---|---|---|
| 1 | 🔴 KRITISCH | SQLMesh nicht deployed – keine Analytics-Tabellen | Superset, Trino |
| 2 | 🔴 KRITISCH | Trino Vault UDF nicht gemountet – PII-Entschlüsselung unmöglich | Trino, SQLMesh, Superset |
| 3 | 🔴 KRITISCH | Nessie IN_MEMORY – Tabellen-Metadaten gehen bei Restart verloren | Trino, Iceberg, alle Analytics |
| 4 | 🔴 KRITISCH | OpenMetadata Ingestion-Pipelines nicht registriert – Governance leer | OpenMetadata |
| 5 | 🟡 MITTEL | OpenLineage nicht aktiv in Kafka Connect – keine Lineage in Marquez | Marquez, OpenMetadata |
| 6 | 🟡 MITTEL | Airflow-DB-Initialisierung unsicher | OpenMetadata Ingestion |
| 7 | 🟢 NIEDRIG | Soda Core nicht deployed – keine Quality Checks | Data Quality |
| 8 | 🟢 NIEDRIG | HR-Topics fehlen in OpenMetadata Kafka-Ingestion Filter | OpenMetadata |
| 9 | 🟢 NIEDRIG | Keine vorkonfigurierten Superset Dashboards | Superset |

---

## 7. Empfohlene Reihenfolge zur Behebung

1. **Nessie auf persistenten Storage umstellen** (JDBC/RocksDB) – Grundvoraussetzung für stabile Iceberg-Tabellen
2. **Trino mit Vault UDF Plugin als Custom Image deployen** – Nötig für PII-Entschlüsselung
3. **SQLMesh als K8s CronJob deployen** – Erstellt die Analytics-Schicht
4. **OpenMetadata Init-Job erstellen** – Registriert Ingestion-Pipelines via API
5. **OpenLineage in Kafka Connect aktivieren** – Lineage-Events an Marquez
6. **Soda Core als CronJob deployen** – Quality Monitoring
7. **HR-Topics in OpenMetadata aufnehmen** – Vollständige Governance
8. **Superset Dashboards provisionieren** – Sofortige Sichtbarkeit nach Fix

---

## 8. Referenz: Wo finde ich was?

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
| Wo kann ich SQL-Queries machen? | Superset SQL Lab | `http://superset.localhost` (Datasource vorhanden, Tabellen fehlen) |
| Wo ist der Nessie Catalog? | Nessie API | `http://nessie.localhost/api/v2/trees` |
| Wo sind die ODC Contracts? | Im Code | `{domain}/src/main/resources/contracts/*.odcontract.yaml` |
| Wo sind die SQLMesh-Modelle? | Im Code | `infra/sqlmesh/models/` (staging + marts) |
| Wo sind die Soda-Checks? | Im Code | `infra/soda/checks/` |
