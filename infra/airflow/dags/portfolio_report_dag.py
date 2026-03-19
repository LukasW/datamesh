"""
DAG: platform.portfolio_report
Schedule: hourly
Owner: platform@css.ch

Pipeline:
  1. KafkaLagSensor     – Skip when no new events since last run
  2. dbt build          – Staging + Marts (tag:report models only)
  3. QualityGate        – Fails if dbt tests return errors
  4. GovernanceCheck    – ODC Lint + Schema Registry Freshness
  5. ReportMaterialize  – Writes dated report snapshot to analytics schema
  6. DataHubLineage     – Pushes lineage to DataHub (when reachable)
  7. PortalRefresh      – Sets flag in platform_db → Portal shows "fresh"
"""
import logging
import os
import subprocess
from datetime import datetime, timedelta

import psycopg2
from airflow import DAG
from airflow.operators.python import PythonOperator, ShortCircuitOperator

PLATFORM_DB_URL = os.getenv("PLATFORM_DB_URL")
KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")

default_args = {
    "owner": "platform",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
    "email_on_failure": False,
}


# ---------------------------------------------------------------------------
# Task functions
# ---------------------------------------------------------------------------

def check_kafka_lag(**context) -> bool:
    """Returns True when the platform-consumer group has lag (new events to process)."""
    from kafka.admin import KafkaAdminClient
    try:
        admin = KafkaAdminClient(bootstrap_servers=KAFKA_BOOTSTRAP)
        offsets = admin.list_consumer_group_offsets("platform-consumer")
        has_lag = any(v.offset > 0 for v in offsets.values())
        admin.close()
        return has_lag
    except Exception as exc:
        # On connection error → proceed anyway so the pipeline doesn't silently stall
        logging.warning("Kafka lag check failed (proceeding): %s", exc)
        return True


def run_dbt_build(**context):
    """Runs dbt build --select tag:report (compile + run + test in one step)."""
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
    context["ti"].xcom_push(key="dbt_returncode", value=result.returncode)
    context["ti"].xcom_push(key="dbt_stdout", value=result.stdout[-4000:])
    if result.returncode != 0:
        raise RuntimeError(f"dbt build failed:\n{result.stderr[-2000:]}")


def assert_dbt_tests_passed(**context):
    """Quality Gate: fails if dbt tests reported errors or premium <= 0 rows exist."""
    returncode = context["ti"].xcom_pull(
        task_ids="dbt_build_report_models", key="dbt_returncode"
    )
    stdout = (
        context["ti"].xcom_pull(task_ids="dbt_build_report_models", key="dbt_stdout") or ""
    )

    if "ERROR" in stdout or returncode != 0:
        raise ValueError(
            "Quality Gate failed: dbt tests have errors. "
            "Report will NOT be published with inconsistent data."
        )

    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    cur.execute(
        "SELECT COUNT(*) FROM analytics.fact_policies WHERE premium_chf <= 0"
    )
    bad_rows = cur.fetchone()[0]
    conn.close()
    if bad_rows > 0:
        raise ValueError(
            f"Quality Gate failed: {bad_rows} policies with premium <= 0"
        )


def run_governance_checks(**context):
    """ODC Lint + Schema Registry Freshness (advisory – warning only, not a hard stop)."""
    result = subprocess.run(
        ["python3", "/opt/governance/lint-contracts.py"],
        capture_output=True,
        text=True,
    )
    context["ti"].xcom_push(key="lint_output", value=result.stdout[-2000:])
    if result.returncode != 0:
        logging.warning("ODC lint warnings:\n%s", result.stdout)


def materialize_portfolio_snapshot(**context):
    """
    Writes a dated snapshot of mart_portfolio_summary into
    analytics.portfolio_report_history for historical comparisons.
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
    Uses the existing DataHub ingest script (infra/datahub/ingest.sh).
    Non-critical: errors are logged, pipeline continues.
    """
    try:
        subprocess.run(
            ["/bin/sh", "/opt/datahub/ingest.sh"],
            timeout=30,
            capture_output=True,
        )
    except Exception as exc:
        logging.warning("DataHub lineage push failed (non-critical): %s", exc)


def set_portal_refresh_flag(**context):
    """Sets last_report_at in platform_db so the portal /report shows a fresh timestamp."""
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS analytics.platform_meta (key TEXT PRIMARY KEY, value TEXT);
        INSERT INTO analytics.platform_meta (key, value) VALUES ('last_report_at', NOW()::TEXT)
        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
    """)
    conn.commit()
    conn.close()


# ---------------------------------------------------------------------------
# DAG definition
# ---------------------------------------------------------------------------

with DAG(
    dag_id="platform.portfolio_report",
    default_args=default_args,
    schedule_interval="@hourly",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["platform", "report", "cross-domain"],
) as dag:

    # Task 1: ShortCircuit – skip entire pipeline when no new events
    kafka_lag_check = ShortCircuitOperator(
        task_id="check_kafka_lag",
        python_callable=check_kafka_lag,
    )

    # Task 2: dbt build (compile + run + test for tag:report models)
    dbt_build = PythonOperator(
        task_id="dbt_build_report_models",
        python_callable=run_dbt_build,
    )

    # Task 3: Quality Gate – abort on dbt test errors or bad data
    quality_gate = PythonOperator(
        task_id="quality_gate",
        python_callable=assert_dbt_tests_passed,
    )

    # Task 4: Governance check (ODC lint, advisory)
    governance_check = PythonOperator(
        task_id="governance_check",
        python_callable=run_governance_checks,
    )

    # Task 5: Write dated snapshot to analytics.portfolio_report_history
    materialize_report = PythonOperator(
        task_id="materialize_report",
        python_callable=materialize_portfolio_snapshot,
    )

    # Task 6: Push lineage metadata to DataHub
    datahub_push = PythonOperator(
        task_id="push_datahub_lineage",
        python_callable=push_lineage_to_datahub,
    )

    # Task 7: Set portal refresh flag
    portal_refresh = PythonOperator(
        task_id="portal_refresh_flag",
        python_callable=set_portal_refresh_flag,
    )

    # Dependencies
    kafka_lag_check >> dbt_build >> quality_gate >> governance_check
    governance_check >> [materialize_report, datahub_push]
    materialize_report >> portal_refresh
