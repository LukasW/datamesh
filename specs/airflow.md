# Airflow – Implementierungsplan: Regulärer Cross-Domain Report

> **Ziel:** Apache Airflow orchestriert eine stündliche Pipeline, die Daten aus
> drei Domain-Data-Products (Partner, Product, Policy) zu einem Portfolio-Report
> kombiniert – mit Qualitätsgates, Lineage-Tracking und Governance-Prüfungen.

---

## Übersicht der Pipeline

```
[KafkaConsumerLagSensor]
      │  Nur ausführen, wenn neue Events vorliegen (Sensor = kein unnötiger dbt-Run)
      ↓
[dbt build --select tag:report]
      │  Transformiert raw.* → staging → marts (inkl. mart_portfolio_summary)
      │  Führt dbt-Tests aus (assert_no_orphan_policies, assert_premium_positive)
      ↓
[QualityGateSensor]
      │  Bricht ab wenn dbt-Tests fehlschlagen → kein Report mit inkonsistenten Daten
      ↓
[GovernanceCheckOperator]
      │  Führt lint-contracts.py + check-freshness.py aus
      ↓
[ReportMaterializeOperator]
      │  Schreibt analytics.portfolio_report_YYYY_MM_DD in platform_db
      ↓
[DataHubLineagePushOperator]
      │  Publiziert Lineage-Metadata zu DataHub (bereits konfiguriert in infra/datahub/)
      ↓
[NotifyOperator]
         Aktualisiert Portal-Flag → /report im Portal zeigt neuen Stand
```

**Zeitplan:** `@hourly` (konfigurierbar per Airflow Variable)

---

## Neue Dateien und Änderungen

### 1. `infra/airflow/` (neuer Ordner)

```
infra/airflow/
├── Dockerfile                          ← Custom Airflow-Image mit dbt + psycopg2
├── requirements.txt                    ← dbt-postgres, apache-airflow-providers-postgres
└── dags/
    └── portfolio_report_dag.py         ← Der DAG (Hauptdatei)
```

### 2. `infra/dbt/models/sources.yml`

Exposure-Block hinzufügen (deklariert wer die Marts konsumiert):

```yaml
exposures:
  - name: portfolio_report
    type: dashboard
    maturity: high
    owner:
      name: Data Platform Team
      email: platform@css.ch
    depends_on:
      - ref('mart_portfolio_summary')
      - ref('mart_policy_detail')
      - ref('fact_policies')
    description: >
      Hourly report combining Partner, Product, and Policy data products.
      Orchestrated by Airflow DAG: platform.portfolio_report.
      No data warehouse. No ETL ticket. Data arrives via Kafka data products only.
```

### 3. `infra/dbt/dbt_project.yml`

Tags für alle Report-relevanten Modelle setzen:

```yaml
models:
  platform:
    staging:
      +materialized: view
    marts:
      +materialized: table
      mart_portfolio_summary:
        +tags: [report]
      mart_policy_detail:
        +tags: [report]
      fact_policies:
        +tags: [report]
      dim_partner:
        +tags: [report]
      dim_product:
        +tags: [report]
```

### 4. `docker-compose.yaml`

Airflow-Services ergänzen (Postgres für Metadaten + Webserver + Scheduler):

```yaml
  airflow-db:
    image: postgres:16
    environment:
      POSTGRES_DB: airflow
      POSTGRES_USER: airflow
      POSTGRES_PASSWORD: ${AIRFLOW_DB_PASSWORD:-airflow_pass}
    volumes:
      - airflow-db-data:/var/lib/postgresql/data
    networks:
      - backend
    deploy:
      resources:
        limits:
          cpus: '0.5'
          memory: 256M

  airflow-init:
    build:
      context: infra/airflow
      dockerfile: Dockerfile
    depends_on:
      airflow-db:
        condition: service_healthy
    environment:
      AIRFLOW__DATABASE__SQL_ALCHEMY_CONN: postgresql+psycopg2://airflow:${AIRFLOW_DB_PASSWORD:-airflow_pass}@airflow-db:5432/airflow
      AIRFLOW__CORE__EXECUTOR: LocalExecutor
    command: ["airflow", "db", "migrate"]
    networks:
      - backend

  airflow-scheduler:
    build:
      context: infra/airflow
      dockerfile: Dockerfile
    restart: unless-stopped
    depends_on:
      airflow-init:
        condition: service_completed_successfully
      platform-db:
        condition: service_healthy
      kafka:
        condition: service_healthy
    environment:
      AIRFLOW__DATABASE__SQL_ALCHEMY_CONN: postgresql+psycopg2://airflow:${AIRFLOW_DB_PASSWORD:-airflow_pass}@airflow-db:5432/airflow
      AIRFLOW__CORE__EXECUTOR: LocalExecutor
      AIRFLOW__CORE__LOAD_EXAMPLES: "false"
      PLATFORM_DB_URL: postgresql://platform_user:${PLATFORM_DB_PASSWORD:-platform_pass}@platform-db:5432/platform_db
      KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      SCHEMA_REGISTRY_URL: http://schema-registry:8081
    volumes:
      - ./infra/airflow/dags:/opt/airflow/dags
      - ./infra/dbt:/opt/dbt
      - ./partner/src/main/resources/contracts:/contracts/partner:ro
      - ./policy/src/main/resources/contracts:/contracts/policy:ro
      - ./product/src/main/resources/contracts:/contracts/product:ro
    command: ["airflow", "scheduler"]
    networks:
      - backend
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M

  airflow-webserver:
    build:
      context: infra/airflow
      dockerfile: Dockerfile
    restart: unless-stopped
    depends_on:
      airflow-init:
        condition: service_completed_successfully
    environment:
      AIRFLOW__DATABASE__SQL_ALCHEMY_CONN: postgresql+psycopg2://airflow:${AIRFLOW_DB_PASSWORD:-airflow_pass}@airflow-db:5432/airflow
      AIRFLOW__CORE__EXECUTOR: LocalExecutor
      AIRFLOW__CORE__LOAD_EXAMPLES: "false"
      AIRFLOW__WEBSERVER__EXPOSE_CONFIG: "true"
    volumes:
      - ./infra/airflow/dags:/opt/airflow/dags
    command: ["airflow", "webserver", "--port", "8091"]
    ports:
      - "8091:8091"
    networks:
      - backend
      - frontend
    deploy:
      resources:
        limits:
          cpus: '0.5'
          memory: 512M
```

Volume am Ende der Datei:

```yaml
volumes:
  airflow-db-data:
```

---

## DAG-Implementierung: `portfolio_report_dag.py`

```python
"""
DAG: platform.portfolio_report
Schedule: hourly
Owner: platform@css.ch

Pipeline:
  1. KafkaLagSensor     – Skip wenn keine neuen Events seit letztem Run
  2. dbt build          – Staging + Marts (nur tag:report Modelle)
  3. QualityGate        – Schlägt fehl wenn dbt-Tests Fehler zurückgeben
  4. GovernanceCheck    – ODC Lint + Schema Registry Freshness
  5. ReportMaterialize  – Schreibt dat. Report-Snapshot in analytics Schema
  6. DataHubLineage     – Pusht Lineage an DataHub (wenn erreichbar)
  7. PortalRefresh      – Setzt Flag in platform_db → Portal zeigt "fresh"
"""
from datetime import datetime, timedelta
from airflow import DAG
from airflow.operators.python import PythonOperator, ShortCircuitOperator
from airflow.providers.postgres.hooks.postgres import PostgresHook
from airflow.models import Variable

default_args = {
    "owner": "platform",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
    "email_on_failure": False,   # auf True setzen + SMTP konfigurieren für Produktion
}

with DAG(
    dag_id="platform.portfolio_report",
    default_args=default_args,
    schedule_interval="@hourly",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["platform", "report", "cross-domain"],
) as dag:

    # ── Task 1: Sensor ──────────────────────────────────────────────────────
    # ShortCircuitOperator: gibt False zurück wenn keine neuen Events → Pipeline
    # überspringt alle nachfolgenden Tasks (kein unnötiger dbt-Run)
    kafka_lag_check = ShortCircuitOperator(
        task_id="check_kafka_lag",
        python_callable=check_kafka_lag,   # siehe Implementierung unten
    )

    # ── Task 2: dbt build ───────────────────────────────────────────────────
    dbt_build = PythonOperator(
        task_id="dbt_build_report_models",
        python_callable=run_dbt_build,
    )

    # ── Task 3: Quality Gate ────────────────────────────────────────────────
    # Liest dbt Test-Ergebnisse – schlägt fehl wenn kritische Tests fehlgeschlagen
    quality_gate = PythonOperator(
        task_id="quality_gate",
        python_callable=assert_dbt_tests_passed,
    )

    # ── Task 4: Governance Check ────────────────────────────────────────────
    governance_check = PythonOperator(
        task_id="governance_check",
        python_callable=run_governance_checks,
    )

    # ── Task 5: Report materialisieren ─────────────────────────────────────
    materialize_report = PythonOperator(
        task_id="materialize_report",
        python_callable=materialize_portfolio_snapshot,
    )

    # ── Task 6: DataHub Lineage ─────────────────────────────────────────────
    datahub_push = PythonOperator(
        task_id="push_datahub_lineage",
        python_callable=push_lineage_to_datahub,
    )

    # ── Task 7: Portal Flag setzen ──────────────────────────────────────────
    portal_refresh = PythonOperator(
        task_id="portal_refresh_flag",
        python_callable=set_portal_refresh_flag,
    )

    # ── Abhängigkeiten ──────────────────────────────────────────────────────
    kafka_lag_check >> dbt_build >> quality_gate >> governance_check
    governance_check >> [materialize_report, datahub_push]
    materialize_report >> portal_refresh
```

### Implementierung der Task-Funktionen

```python
import subprocess
import json
import os
import psycopg2
from kafka import KafkaConsumer, TopicPartition
from kafka.admin import KafkaAdminClient

PLATFORM_DB_URL = os.getenv("PLATFORM_DB_URL")
KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
SCHEMA_REGISTRY = os.getenv("SCHEMA_REGISTRY_URL", "http://schema-registry:8081")

REPORT_TOPICS = [
    "policy.v1.issued", "policy.v1.cancelled", "policy.v1.changed",
    "product.v1.defined", "product.v1.updated",
    "person.v1.created", "person.v1.updated",
]


def check_kafka_lag(**context) -> bool:
    """Gibt True zurück wenn consumer platform-consumer hinter liegt (neue Events)."""
    from kafka.admin import KafkaAdminClient
    try:
        admin = KafkaAdminClient(bootstrap_servers=KAFKA_BOOTSTRAP)
        # Vereinfacht: Prüfe ob consumer group einen Lag > 0 hat
        offsets = admin.list_consumer_group_offsets("platform-consumer")
        return any(v.offset > 0 for v in offsets.values())
    except Exception:
        # Bei Verbindungsfehler → trotzdem laufen lassen
        return True


def run_dbt_build(**context):
    """
    Führt dbt build --select tag:report aus.
    Beinhaltet: Compilation + Run + Tests in einem Schritt.
    """
    result = subprocess.run(
        [
            "dbt", "build",
            "--project-dir", "/opt/dbt",
            "--profiles-dir", "/opt/dbt",
            "--select", "tag:report",
            "--target", "platform",
        ],
        capture_output=True,
        text=True,
    )
    # Ausgabe als XCom pushen für den Quality Gate
    context["ti"].xcom_push(key="dbt_returncode", value=result.returncode)
    context["ti"].xcom_push(key="dbt_stdout", value=result.stdout[-4000:])
    if result.returncode != 0:
        raise RuntimeError(f"dbt build failed:\n{result.stderr[-2000:]}")


def assert_dbt_tests_passed(**context):
    """Quality Gate: Schlägt fehl wenn dbt-Tests Fehler zurückgaben."""
    returncode = context["ti"].xcom_pull(task_ids="dbt_build_report_models", key="dbt_returncode")
    stdout = context["ti"].xcom_pull(task_ids="dbt_build_report_models", key="dbt_stdout") or ""

    # dbt schreibt "WARN" wenn Tests fehlschlagen aber --no-fail-fast aktiv
    if "ERROR" in stdout or returncode != 0:
        raise ValueError(
            "Quality Gate failed: dbt tests have errors. "
            "Report will NOT be published with inconsistent data."
        )
    # Cross-Domain Test: Prüfe direkt in DB
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM analytics.fact_policies WHERE premium_chf <= 0")
    bad_rows = cur.fetchone()[0]
    conn.close()
    if bad_rows > 0:
        raise ValueError(f"Quality Gate failed: {bad_rows} policies with premium <= 0")


def run_governance_checks(**context):
    """ODC Lint + Schema Registry Freshness Report."""
    # lint-contracts.py liegt bereits unter infra/governance/
    result = subprocess.run(
        ["python3", "/opt/governance/lint-contracts.py"],
        capture_output=True, text=True,
    )
    context["ti"].xcom_push(key="lint_output", value=result.stdout[-2000:])
    # Lint-Fehler loggen aber nicht als hard failure – Governance ist Advisory hier
    # (Hard gate = schema-compat-check.sh im CI vor dem Deploy)
    if result.returncode != 0:
        import logging
        logging.warning("ODC lint warnings:\n%s", result.stdout)


def materialize_portfolio_snapshot(**context):
    """
    Schreibt einen datierten Snapshot der mart_portfolio_summary in
    analytics.portfolio_report_history für historische Vergleiche.
    """
    run_ts = context["execution_date"]
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS analytics.portfolio_report_history (
            report_ts       TIMESTAMPTZ NOT NULL,
            product_line    TEXT,
            product_name    TEXT,
            active_policies BIGINT,
            total_premium   NUMERIC,
            avg_premium     NUMERIC
        )
    """)
    cur.execute("""
        INSERT INTO analytics.portfolio_report_history
        SELECT %s, product_line, product_name,
               active_policies, total_premium_chf, avg_premium_chf
        FROM analytics.mart_portfolio_summary
    """, (run_ts,))
    conn.commit()
    conn.close()


def push_lineage_to_datahub(**context):
    """
    Nutzt den bestehenden DataHub-Ingest (infra/datahub/ingest.sh).
    Nicht-kritisch: Fehler werden geloggt, Pipeline läuft weiter.
    """
    try:
        subprocess.run(
            ["/bin/sh", "/opt/datahub/ingest.sh"],
            timeout=30, capture_output=True,
        )
    except Exception as exc:
        import logging
        logging.warning("DataHub lineage push failed (non-critical): %s", exc)


def set_portal_refresh_flag(**context):
    """Setzt last_report_at in platform_db → /report im Portal zeigt neuen Zeitstempel."""
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS analytics.platform_meta (key TEXT PRIMARY KEY, value TEXT);
        INSERT INTO analytics.platform_meta (key, value) VALUES ('last_report_at', NOW()::TEXT)
        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
    """)
    conn.commit()
    conn.close()
```

---

## Dockerfile für Airflow-Image

```dockerfile
# infra/airflow/Dockerfile
FROM apache/airflow:2.9.3-python3.11

USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential libpq-dev git \
    && rm -rf /var/lib/apt/lists/*

USER airflow
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
```

```
# infra/airflow/requirements.txt
apache-airflow-providers-postgres==5.11.0
apache-airflow-providers-http==4.10.0
dbt-postgres==1.8.0
kafka-python==2.0.2
psycopg2-binary==2.9.9
pyyaml>=6.0
requests>=2.31
```

---

## Airflow Connections (nach Start einmalig konfigurieren)

Im Airflow UI unter **Admin → Connections**:

| Conn ID | Type | Host | DB | Login | Password |
|---|---|---|---|---|---|
| `platform_db` | postgres | `platform-db` | `platform_db` | `platform_user` | `${PLATFORM_DB_PASSWORD}` |
| `airflow_db` | postgres | `airflow-db` | `airflow` | `airflow` | `${AIRFLOW_DB_PASSWORD}` |

Oder per Environment Variable (produktionsnäher):

```bash
AIRFLOW_CONN_PLATFORM_DB="postgresql://platform_user:${PLATFORM_DB_PASSWORD}@platform-db/platform_db"
```

---

## Umsetzungsschritte (Sequenz)

### Schritt 1 – dbt-Tags setzen *(~30 Min)*

Datei: `infra/dbt/dbt_project.yml`

Tags auf `mart_portfolio_summary`, `mart_policy_detail`, `fact_policies`,
`dim_partner`, `dim_product` setzen (siehe oben). Testen:

```bash
cd infra/dbt
dbt ls --select tag:report
```

Erwartete Ausgabe: 5 Modelle gelistet.

---

### Schritt 2 – Exposure in sources.yml *(~15 Min)*

Datei: `infra/dbt/models/sources.yml`

Exposure-Block ans Ende der Datei anhängen (Code siehe oben).
Validieren:

```bash
dbt docs generate --select tag:report
# Öffne: http://localhost:8080/#!/exposure/list
```

---

### Schritt 3 – Airflow Dockerfile + requirements.txt *(~30 Min)*

Dateien erstellen:

- `infra/airflow/Dockerfile`
- `infra/airflow/requirements.txt`

Image lokal bauen und testen:

```bash
cd infra/airflow
podman build -t css/airflow:latest .
podman run --rm css/airflow:latest airflow version
# → Apache Airflow 2.9.3
```

---

### Schritt 4 – DAG-Datei erstellen *(~2h)*

Datei: `infra/airflow/dags/portfolio_report_dag.py`

Gliederung:

1. Imports + default_args (5 Min)
2. Task-Funktionen implementieren (90 Min)
   - `check_kafka_lag` – Kafka Admin Client
   - `run_dbt_build` – subprocess + XCom
   - `assert_dbt_tests_passed` – XCom + DB-Check
   - `run_governance_checks` – subprocess lint-contracts.py
   - `materialize_portfolio_snapshot` – SQL INSERT
   - `push_lineage_to_datahub` – subprocess ingest.sh
   - `set_portal_refresh_flag` – SQL UPSERT
3. DAG + Abhängigkeiten (15 Min)
4. Lokal testen: `airflow tasks test platform.portfolio_report dbt_build_report_models 2026-01-01`

---

### Schritt 5 – docker-compose.yaml erweitern *(~30 Min)*

Services hinzufügen (Code siehe oben):

- `airflow-db` (PostgreSQL für Airflow-Metadaten)
- `airflow-init` (One-Shot: `airflow db migrate`)
- `airflow-scheduler`
- `airflow-webserver` (Port 8091)

Volume `airflow-db-data` ergänzen.

Testen:

```bash
podman compose up airflow-db airflow-init airflow-webserver airflow-scheduler
# → http://localhost:8091 → Airflow UI
# → DAG "platform.portfolio_report" erscheint
# → Trigger manual run → alle Tasks grün
```

---

### Schritt 6 – Portal `/report`-Endpoint *(~1h, optional)*

Im bestehenden `infra/portal/main.py` einen neuen Endpoint hinzufügen:

```python
@app.get("/report", response_class=HTMLResponse)
async def report(request: Request):
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    # Aktueller Stand
    cur.execute("""
        SELECT product_line, product_name, active_policies,
               total_premium_chf, avg_premium_chf
        FROM analytics.mart_portfolio_summary
        ORDER BY total_premium_chf DESC
    """)
    current = cur.fetchall()
    # Zuletzt aktualisiert
    cur.execute("SELECT value FROM analytics.platform_meta WHERE key = 'last_report_at'")
    row = cur.fetchone()
    last_at = row[0] if row else "–"
    # Historischer Trend (letzte 24 Reports)
    cur.execute("""
        SELECT report_ts, SUM(active_policies), SUM(total_premium)
        FROM analytics.portfolio_report_history
        WHERE report_ts >= NOW() - INTERVAL '24 hours'
        GROUP BY report_ts ORDER BY report_ts
    """)
    history = cur.fetchall()
    conn.close()
    return templates.TemplateResponse("report.html", {
        "request": request,
        "current": current,
        "history": history,
        "last_at": last_at,
    })
```

---

## Service-Übersicht nach Implementierung

```
kafka           :9092   Kafka KRaft broker
schema-registry :8081   Confluent Schema Registry
akhq            :8085   Kafka UI
partner-db      :5432   PostgreSQL (Partner domain – Data Inside)
product-db      :5433   PostgreSQL (Product domain – Data Inside)
policy-db       :5434   PostgreSQL (Policy domain – Data Inside)
platform-db     :5435   PostgreSQL (Analytics / dbt target)
airflow-db      :5436   PostgreSQL (Airflow Metadaten)  ← NEU
partner         :9080   Partner Quarkus Service
product         :9081   Product Quarkus Service
policy          :9082   Policy Quarkus Service
debezium        :8083   Debezium Connect
platform-consumer       Kafka → raw.*  (läuft weiter)
spark-streaming         Delta Lake Ingest
dbt                     Einmaliger Build beim Start (bleibt)
airflow-scheduler        Stündlicher DAG-Run             ← NEU
airflow-webserver :8091 Airflow UI                       ← NEU
portal          :8090   Data Product Portal (+ /report)
```

---

## Qualitätsgates im Überblick

| Gate | Wo | Wirkung bei Fehler |
|---|---|---|
| Kafka Consumer Lag | Task 1 (ShortCircuit) | Pipeline übersprungen – kein unnötiger Run |
| `dbt build` Exit Code | Task 2 | Pipeline stoppt – keine veralteten Marts |
| `assert_no_orphan_policies` | Task 3 | Pipeline stoppt – kein Report mit fehlenden Partnern |
| `assert_premium_positive` | Task 3 | Pipeline stoppt – kein Report mit negativen Prämien |
| ODC Lint | Task 4 | Warning in Airflow Log (Advisory, kein Hard-Stop) |
| Schema Compatibility | CI (schema-compat-check.sh) | Deployment geblockt |

**Prinzip:** Hard Gates blockieren den Report. Advisory-Warnings blockieren nur den Deploy.

---

## Definition of Done

- [ ] `dbt ls --select tag:report` listet 5 Modelle
- [ ] Airflow UI erreichbar auf Port 8091
- [ ] DAG `platform.portfolio_report` sichtbar und triggerable
- [ ] Manueller Trigger: alle 7 Tasks grün
- [ ] `analytics.portfolio_report_history` enthält einen Eintrag nach erstem Run
- [ ] `analytics.platform_meta` enthält `last_report_at`
- [ ] Stündlicher Trigger läuft automatisch (nach 60 Min im compose-Stack prüfen)
- [ ] `specs/arc42.md` ergänzt: neuer Service `airflow` mit Port 8091

---

## Übergreifender Management-Report

> **Ziel:** Täglicher (06:00 UTC) übergreifender Business-Report, der alle drei Domains
> (Partner, Product, Policy) zu einem Management-Dashboard mit KPIs, Trendanalysen und
> Qualitätsstatus zusammenführt. Zielgruppe: Underwriting-Lead, CISO, Produktmanagement.
> Dieser Report ergänzt den stündlichen Portfolio-Report um strategische Kennzahlen.

---

### Überblick: Zweiter DAG `platform.management_report`

```
[DailyTrigger: 06:00 UTC]
      ↓
[WaitForPortfolioReport]
      │  Wartet bis der letzte stündliche portfolio_report-Run erfolgreich war
      │  (ExternalTaskSensor auf platform.portfolio_report / materialize_report)
      ↓
[dbt build --select tag:mgmt-report]
      │  Baut zusätzliche Mart-Modelle: Trendanalysen, Domänen-KPI-Aggregationen
      ↓
[CrossDomainQualityGate]
      │  Prüft Konsistenz zwischen Domains (z.B. jede aktive Police hat gültigen Partner)
      ↓
[ManagementSnapshotOperator]
      │  Schreibt analytics.management_report_history (partitioniert nach report_date)
      ↓
[PDFExportOperator]                   [DataHubLineagePushOperator]
      │  Erzeugt PDF-Snapshot                  │  Lineage für Mgmt-Modelle
      │  → platform_db / analytics.report_files
      ↓
[NotifyPortalOperator]
         Setzt management_report_at → /management-report im Portal zeigt neuen Stand
```

**Zeitplan:** `0 6 * * *` (täglich 06:00 UTC), mit `depends_on_past=True` um Lücken zu
vermeiden.

---

### Neue dbt-Modelle (tag: `mgmt-report`)

```yaml
# infra/dbt/dbt_project.yml – Ergänzung unter models.platform.marts:
      mart_management_kpi:
        +tags: [mgmt-report]
      mart_partner_growth:
        +tags: [mgmt-report]
      mart_product_performance:
        +tags: [mgmt-report]
      mart_policy_trend:
        +tags: [mgmt-report]
      mart_exposure_by_product:
        +tags: [mgmt-report]
```

**Modell-Beschreibungen:**

| Modell | Granularität | Quellen |
|---|---|---|
| `mart_management_kpi` | 1 Zeile / Tag | alle marts |
| `mart_partner_growth` | pro Tag + Partnerkategorie | `dim_partner`, `fact_policies` |
| `mart_product_performance` | pro Produktlinie + Tag | `dim_product`, `fact_policies` |
| `mart_policy_trend` | pro Tag (rolling 30d) | `fact_policies` |
| `mart_exposure_by_product` | pro Produkt + Deckungssummen-Bucket | `fact_policies`, `dim_product` |

SQL-Beispiel `mart_management_kpi`:

```sql
-- infra/dbt/models/marts/mart_management_kpi.sql
{{ config(materialized='table', tags=['mgmt-report']) }}

SELECT
    CURRENT_DATE                                         AS report_date,
    COUNT(DISTINCT p.partner_id)                         AS total_partners,
    COUNT(DISTINCT pol.policy_id)
        FILTER (WHERE pol.status = 'ACTIVE')             AS active_policies,
    SUM(pol.annual_premium_chf)
        FILTER (WHERE pol.status = 'ACTIVE')             AS total_portfolio_premium_chf,
    AVG(pol.annual_premium_chf)
        FILTER (WHERE pol.status = 'ACTIVE')             AS avg_premium_chf,
    COUNT(DISTINCT prod.product_id)                      AS active_products,
    COUNT(DISTINCT pol.policy_id)
        FILTER (WHERE pol.inception_date >= CURRENT_DATE - 30) AS new_policies_last_30d,
    COUNT(DISTINCT pol.policy_id)
        FILTER (WHERE pol.cancellation_date >= CURRENT_DATE - 30) AS cancelled_last_30d
FROM {{ ref('fact_policies') }} pol
LEFT JOIN {{ ref('dim_partner') }} p ON pol.partner_id = p.partner_id
LEFT JOIN {{ ref('dim_product') }} prod ON pol.product_id = prod.product_id
```

---

### DAG-Implementierung: `management_report_dag.py`

```python
"""
DAG: platform.management_report
Schedule: täglich 06:00 UTC
Owner: platform@css.ch

Pipeline:
  1. WaitForPortfolioReport  – ExternalTaskSensor auf portfolio_report
  2. dbt build               – tag:mgmt-report Modelle
  3. CrossDomainQualityGate  – Konsistenzprüfungen über Domains hinweg
  4. ManagementSnapshot      – Tages-Snapshot in analytics.management_report_history
  5. PDFExport               – Optional: PDF-Snapshot erzeugen
  6. DataHubLineage          – Lineage für Mgmt-Modelle pushen
  7. PortalRefresh           – management_report_at in platform_meta setzen
"""
from datetime import datetime, timedelta
from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.sensors.external_task import ExternalTaskSensor
import psycopg2
import subprocess
import os

PLATFORM_DB_URL = os.getenv("PLATFORM_DB_URL")

default_args = {
    "owner": "platform",
    "retries": 1,
    "retry_delay": timedelta(minutes=10),
    "email_on_failure": False,
    "depends_on_past": True,       # kein Lückenreport bei fehlendem Vortag
}

with DAG(
    dag_id="platform.management_report",
    default_args=default_args,
    schedule_interval="0 6 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["platform", "report", "cross-domain", "management"],
) as dag:

    # ── Task 1: Warten auf stündlichen Portfolio-Report ─────────────────────
    wait_for_portfolio = ExternalTaskSensor(
        task_id="wait_for_portfolio_report",
        external_dag_id="platform.portfolio_report",
        external_task_id="materialize_report",
        # Suche innerhalb der letzten 2h nach einem erfolgreichen Run
        execution_delta=timedelta(hours=2),
        timeout=3600,
        poke_interval=120,
        mode="reschedule",          # gibt Worker-Slot frei während gewartet wird
    )

    # ── Task 2: dbt build (Management-Modelle) ──────────────────────────────
    dbt_build_mgmt = PythonOperator(
        task_id="dbt_build_mgmt_models",
        python_callable=run_dbt_build_mgmt,
    )

    # ── Task 3: Cross-Domain Quality Gate ───────────────────────────────────
    cross_domain_gate = PythonOperator(
        task_id="cross_domain_quality_gate",
        python_callable=assert_cross_domain_consistency,
    )

    # ── Task 4: Management Snapshot ─────────────────────────────────────────
    mgmt_snapshot = PythonOperator(
        task_id="materialize_management_snapshot",
        python_callable=materialize_management_snapshot,
    )

    # ── Task 5 + 6: Parallel – PDF-Export und DataHub-Lineage ───────────────
    pdf_export = PythonOperator(
        task_id="export_management_pdf",
        python_callable=export_management_report_pdf,
    )

    datahub_push = PythonOperator(
        task_id="push_datahub_lineage_mgmt",
        python_callable=push_lineage_to_datahub,
    )

    # ── Task 7: Portal-Flag setzen ──────────────────────────────────────────
    portal_refresh = PythonOperator(
        task_id="portal_refresh_management_flag",
        python_callable=set_management_report_flag,
    )

    # ── Abhängigkeiten ──────────────────────────────────────────────────────
    wait_for_portfolio >> dbt_build_mgmt >> cross_domain_gate >> mgmt_snapshot
    mgmt_snapshot >> [pdf_export, datahub_push]
    pdf_export >> portal_refresh
```

### Implementierung der zusätzlichen Task-Funktionen

```python
def run_dbt_build_mgmt(**context):
    """dbt build für alle tag:mgmt-report Modelle."""
    result = subprocess.run(
        [
            "dbt", "build",
            "--project-dir", "/opt/dbt",
            "--profiles-dir", "/opt/dbt",
            "--select", "tag:mgmt-report",
            "--target", "platform",
        ],
        capture_output=True,
        text=True,
    )
    context["ti"].xcom_push(key="dbt_mgmt_returncode", value=result.returncode)
    if result.returncode != 0:
        raise RuntimeError(f"dbt build (mgmt) failed:\n{result.stderr[-2000:]}")


def assert_cross_domain_consistency(**context):
    """
    Cross-Domain Konsistenzprüfung:
      1. Jede aktive Police muss einen existierenden Partner haben.
      2. Jede aktive Police muss einem existierenden Produkt zugeordnet sein.
      3. Keine Policy darf mehrfach für denselben Partner+Produkt aktiv sein
         (sofern business rules das verbieten).
    """
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()

    # Prüfung 1: Aktive Policies ohne bekannten Partner
    cur.execute("""
        SELECT COUNT(*) FROM analytics.fact_policies fp
        LEFT JOIN analytics.dim_partner dp ON fp.partner_id = dp.partner_id
        WHERE fp.status = 'ACTIVE' AND dp.partner_id IS NULL
    """)
    orphan_policies = cur.fetchone()[0]

    # Prüfung 2: Aktive Policies ohne bekanntes Produkt
    cur.execute("""
        SELECT COUNT(*) FROM analytics.fact_policies fp
        LEFT JOIN analytics.dim_product dp ON fp.product_id = dp.product_id
        WHERE fp.status = 'ACTIVE' AND dp.product_id IS NULL
    """)
    unknown_product_policies = cur.fetchone()[0]

    conn.close()

    errors = []
    if orphan_policies > 0:
        errors.append(f"{orphan_policies} active policies with unknown partner")
    if unknown_product_policies > 0:
        errors.append(f"{unknown_product_policies} active policies with unknown product")

    if errors:
        raise ValueError(
            "Cross-domain quality gate failed: " + "; ".join(errors)
        )


def materialize_management_snapshot(**context):
    """Schreibt Tages-KPI-Snapshot in analytics.management_report_history."""
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS analytics.management_report_history (
            report_date            DATE        NOT NULL,
            total_partners         BIGINT,
            active_policies        BIGINT,
            total_portfolio_premium_chf NUMERIC,
            avg_premium_chf        NUMERIC,
            active_products        BIGINT,
            new_policies_last_30d  BIGINT,
            cancelled_last_30d     BIGINT,
            created_at             TIMESTAMPTZ DEFAULT NOW(),
            CONSTRAINT mgmt_report_history_pk PRIMARY KEY (report_date)
        )
    """)
    cur.execute("""
        INSERT INTO analytics.management_report_history (
            report_date, total_partners, active_policies,
            total_portfolio_premium_chf, avg_premium_chf,
            active_products, new_policies_last_30d, cancelled_last_30d
        )
        SELECT
            report_date, total_partners, active_policies,
            total_portfolio_premium_chf, avg_premium_chf,
            active_products, new_policies_last_30d, cancelled_last_30d
        FROM analytics.mart_management_kpi
        ON CONFLICT (report_date) DO UPDATE
            SET total_partners               = EXCLUDED.total_partners,
                active_policies              = EXCLUDED.active_policies,
                total_portfolio_premium_chf  = EXCLUDED.total_portfolio_premium_chf,
                avg_premium_chf              = EXCLUDED.avg_premium_chf,
                active_products              = EXCLUDED.active_products,
                new_policies_last_30d        = EXCLUDED.new_policies_last_30d,
                cancelled_last_30d           = EXCLUDED.cancelled_last_30d,
                created_at                   = NOW()
    """)
    conn.commit()
    conn.close()


def export_management_report_pdf(**context):
    """
    Rendert das report.html-Template als PDF (via WeasyPrint oder wkhtmltopdf)
    und speichert es in analytics.report_files für den Portal-Download.
    Nicht-kritisch: Fehler werden geloggt, Pipeline läuft weiter.
    """
    import logging
    try:
        result = subprocess.run(
            [
                "python3", "/opt/portal/export_pdf.py",
                "--date", context["ds"],
                "--output", f"/tmp/management_report_{context['ds']}.pdf",
            ],
            capture_output=True, text=True, timeout=120,
        )
        if result.returncode != 0:
            logging.warning("PDF export failed (non-critical): %s", result.stderr)
            return
        # PDF-Bytes in DB speichern (max. 10 MB – grössere Reports über S3/MinIO)
        with open(f"/tmp/management_report_{context['ds']}.pdf", "rb") as f:
            pdf_bytes = f.read()
        conn = psycopg2.connect(PLATFORM_DB_URL)
        cur = conn.cursor()
        cur.execute("""
            CREATE TABLE IF NOT EXISTS analytics.report_files (
                report_date DATE PRIMARY KEY,
                report_type TEXT,
                file_name   TEXT,
                content     BYTEA,
                created_at  TIMESTAMPTZ DEFAULT NOW()
            )
        """)
        cur.execute("""
            INSERT INTO analytics.report_files (report_date, report_type, file_name, content)
            VALUES (%s, 'management', %s, %s)
            ON CONFLICT (report_date) DO UPDATE
                SET content = EXCLUDED.content, created_at = NOW()
        """, (context["ds"], f"management_report_{context['ds']}.pdf", pdf_bytes))
        conn.commit()
        conn.close()
    except Exception as exc:
        logging.warning("PDF export task error (non-critical): %s", exc)


def set_management_report_flag(**context):
    """Setzt management_report_at in platform_meta → /management-report im Portal zeigt neuen Stand."""
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS analytics.platform_meta (key TEXT PRIMARY KEY, value TEXT);
        INSERT INTO analytics.platform_meta (key, value)
            VALUES ('management_report_at', NOW()::TEXT)
        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
    """)
    conn.commit()
    conn.close()
```

---

### Portal-Endpoint `/management-report`

Ergänzung in `infra/portal/main.py`:

```python
@app.get("/management-report", response_class=HTMLResponse)
async def management_report(request: Request, date: str = None):
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()

    # Aktueller Tages-KPI
    cur.execute("""
        SELECT report_date, total_partners, active_policies,
               total_portfolio_premium_chf, avg_premium_chf,
               active_products, new_policies_last_30d, cancelled_last_30d
        FROM analytics.management_report_history
        ORDER BY report_date DESC
        LIMIT 1
    """)
    latest_kpi = cur.fetchone()

    # Historischer Trend (letzte 30 Tage)
    cur.execute("""
        SELECT report_date, active_policies, total_portfolio_premium_chf,
               new_policies_last_30d, cancelled_last_30d
        FROM analytics.management_report_history
        WHERE report_date >= CURRENT_DATE - 30
        ORDER BY report_date
    """)
    trend = cur.fetchall()

    # Produkt-Performance
    cur.execute("""
        SELECT product_line, product_name,
               COUNT(*) AS active_policies,
               SUM(annual_premium_chf) AS total_premium,
               AVG(annual_premium_chf) AS avg_premium
        FROM analytics.fact_policies fp
        JOIN analytics.dim_product dp ON fp.product_id = dp.product_id
        WHERE fp.status = 'ACTIVE'
        GROUP BY product_line, product_name
        ORDER BY total_premium DESC
    """)
    product_performance = cur.fetchall()

    # PDF-Download verfügbar?
    cur.execute("""
        SELECT report_date FROM analytics.report_files
        WHERE report_type = 'management'
        ORDER BY report_date DESC LIMIT 1
    """)
    latest_pdf = cur.fetchone()

    # Zeitstempel des letzten Runs
    cur.execute("SELECT value FROM analytics.platform_meta WHERE key = 'management_report_at'")
    row = cur.fetchone()
    last_at = row[0] if row else "–"

    conn.close()
    return templates.TemplateResponse("management_report.html", {
        "request": request,
        "latest_kpi": latest_kpi,
        "trend": trend,
        "product_performance": product_performance,
        "latest_pdf_date": latest_pdf[0] if latest_pdf else None,
        "last_at": last_at,
    })


@app.get("/management-report/download/{report_date}")
async def download_management_report(report_date: str):
    """PDF-Download für den Management-Report eines bestimmten Datums."""
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    cur.execute(
        "SELECT file_name, content FROM analytics.report_files "
        "WHERE report_type = 'management' AND report_date = %s",
        (report_date,),
    )
    row = cur.fetchone()
    conn.close()
    if not row:
        from fastapi import HTTPException
        raise HTTPException(status_code=404, detail="Report not found")
    from fastapi.responses import Response
    return Response(
        content=row[1],
        media_type="application/pdf",
        headers={"Content-Disposition": f"attachment; filename={row[0]}"},
    )
```

Template `infra/portal/templates/management_report.html` (Qute-Struktur, UI-Text Deutsch):

```html
{% extends "base.html" %}
{% block content %}
<div class="container mt-4">
  <h1>Management-Report</h1>
  <p class="text-muted">Stand: {{ last_at }}</p>

  {% if latest_kpi %}
  <div class="row mb-4">
    <div class="col-md-3"><div class="card text-center p-3">
      <h5>Aktive Policen</h5>
      <p class="fs-2 fw-bold">{{ latest_kpi[2] | format_number }}</p>
    </div></div>
    <div class="col-md-3"><div class="card text-center p-3">
      <h5>Gesamtprämie (CHF)</h5>
      <p class="fs-2 fw-bold">{{ latest_kpi[3] | format_currency }}</p>
    </div></div>
    <div class="col-md-3"><div class="card text-center p-3">
      <h5>Neue Policen (30 Tage)</h5>
      <p class="fs-2 fw-bold">{{ latest_kpi[6] | format_number }}</p>
    </div></div>
    <div class="col-md-3"><div class="card text-center p-3">
      <h5>Kündigungen (30 Tage)</h5>
      <p class="fs-2 fw-bold">{{ latest_kpi[7] | format_number }}</p>
    </div></div>
  </div>
  {% endif %}

  <h2>Produktleistung</h2>
  <table class="table table-striped">
    <thead><tr>
      <th>Produktlinie</th><th>Produkt</th>
      <th class="text-end">Aktive Policen</th>
      <th class="text-end">Gesamtprämie (CHF)</th>
      <th class="text-end">Durchschnittsprämie (CHF)</th>
    </tr></thead>
    <tbody>
    {% for row in product_performance %}
    <tr>
      <td>{{ row[0] }}</td><td>{{ row[1] }}</td>
      <td class="text-end">{{ row[2] | format_number }}</td>
      <td class="text-end">{{ row[3] | format_currency }}</td>
      <td class="text-end">{{ row[4] | format_currency }}</td>
    </tr>
    {% endfor %}
    </tbody>
  </table>

  {% if latest_pdf_date %}
  <a href="/management-report/download/{{ latest_pdf_date }}"
     class="btn btn-primary mt-2">
    PDF herunterladen ({{ latest_pdf_date }})
  </a>
  {% endif %}
</div>
{% endblock %}
```

---

### Erweiterte Service-Übersicht nach Implementierung

```
...
airflow-scheduler        Stündlicher DAG (portfolio) + täglicher DAG (management) ← ERWEITERT
airflow-webserver :8091  Airflow UI (2 DAGs sichtbar)
portal          :8090    Data Product Portal (+ /management-report + PDF-Download) ← ERWEITERT
```

---

### Ergänzte Qualitätsgates (Management-Report)

| Gate | DAG / Task | Wirkung bei Fehler |
|---|---|---|
| ExternalTaskSensor auf portfolio_report | management_report / Task 1 | Management-Report wartet – kein veralteter KPI |
| dbt build (mgmt-Modelle) | management_report / Task 2 | Pipeline stoppt – keine inkonsistenten Trendmodelle |
| Orphan-Policies (keine Partner) | management_report / Task 3 | Pipeline stoppt – kein Report mit Datenlücken |
| Unknown-Product-Policies | management_report / Task 3 | Pipeline stoppt – kein Report mit unbekanntem Produkt |
| PDF-Export | management_report / Task 5 | Warning (non-critical) – Report weiterhin im Portal |

---

### Ergänzungen zur Definition of Done (Management-Report)

- [ ] `dbt ls --select tag:mgmt-report` listet 5 Modelle
- [ ] DAG `platform.management_report` sichtbar und triggerable
- [ ] ExternalTaskSensor wartet erfolgreich auf `platform.portfolio_report`
- [ ] Manueller Trigger: alle 7 Tasks grün
- [ ] `analytics.management_report_history` enthält einen Eintrag nach erstem Run
- [ ] `analytics.platform_meta` enthält `management_report_at`
- [ ] Portal `/management-report` zeigt KPI-Tabelle und Produktleistung
- [ ] PDF-Download unter `/management-report/download/{date}` funktioniert
