"""
DAG: platform.management_report
Schedule: daily at 06:00 UTC
Owner: platform@css.ch

Upstream: platform.dbt_transform -> platform.portfolio_report -> this DAG
  (dbt models are guaranteed fresh via the transitive dependency through
   portfolio_report, which waits for dbt_transform before running.)

Pipeline:
  1. WaitForPortfolioReport  – ExternalTaskSensor on portfolio_report
  2. dbt build               – tag:mgmt-report models
  3. CrossDomainQualityGate  – Consistency checks across domains
  4. ManagementSnapshot      – Daily snapshot in analytics.management_report_history
  5. PDFExport               – Optional: generate PDF snapshot
  6. DataHubLineage          – Push lineage for management models
  7. PortalRefresh           – Set management_report_at in platform_meta
"""
import logging
import os
import subprocess
from datetime import datetime, timedelta

import psycopg2
from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.sensors.external_task import ExternalTaskSensor

PLATFORM_DB_URL = os.getenv("PLATFORM_DB_URL")

default_args = {
    "owner": "platform",
    "retries": 1,
    "retry_delay": timedelta(minutes=10),
    "email_on_failure": False,
    "depends_on_past": True,  # no gap report if previous day's run is missing
}


# ---------------------------------------------------------------------------
# Task functions
# ---------------------------------------------------------------------------

def run_dbt_build_mgmt(**context):
    """dbt build for all tag:mgmt-report models."""
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
    Cross-domain consistency checks:
      1. Every active policy must have a known partner.
      2. Every active policy must be linked to a known product.
    """
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()

    # Check 1: active policies without a known partner
    cur.execute("""
        SELECT COUNT(*) FROM analytics.fact_policies fp
        LEFT JOIN analytics.dim_partner dp ON fp.partner_id = dp.partner_id
        WHERE fp.status = 'ACTIVE' AND dp.partner_id IS NULL
    """)
    orphan_policies = cur.fetchone()[0]

    # Check 2: active policies without a known product
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
    """Writes daily KPI snapshot to analytics.management_report_history."""
    conn = psycopg2.connect(PLATFORM_DB_URL)
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS analytics.management_report_history (
            report_date                  DATE        NOT NULL,
            total_partners               BIGINT,
            active_policies              BIGINT,
            total_portfolio_premium_chf  NUMERIC,
            avg_premium_chf              NUMERIC,
            active_products              BIGINT,
            new_policies_last_30d        BIGINT,
            cancelled_last_30d           BIGINT,
            created_at                   TIMESTAMPTZ DEFAULT NOW(),
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
    Renders the management report as PDF via export_pdf.py and stores it in
    analytics.report_files.  Non-critical: errors are logged, pipeline continues.
    """
    try:
        result = subprocess.run(
            [
                "python3", "/opt/portal/export_pdf.py",
                "--date", context["ds"],
                "--output", f"/tmp/management_report_{context['ds']}.pdf",
            ],
            capture_output=True,
            text=True,
            timeout=120,
        )
        if result.returncode != 0:
            logging.warning("PDF export failed (non-critical): %s", result.stderr)
            return

        with open(f"/tmp/management_report_{context['ds']}.pdf", "rb") as f:
            pdf_bytes = f.read()

        conn = psycopg2.connect(PLATFORM_DB_URL)
        cur = conn.cursor()
        cur.execute("""
            CREATE TABLE IF NOT EXISTS analytics.report_files (
                report_date  DATE PRIMARY KEY,
                report_type  TEXT,
                file_name    TEXT,
                content      BYTEA,
                created_at   TIMESTAMPTZ DEFAULT NOW()
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


def set_management_report_flag(**context):
    """Sets management_report_at in platform_meta → portal /management-report shows fresh timestamp."""
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


# ---------------------------------------------------------------------------
# DAG definition
# ---------------------------------------------------------------------------

with DAG(
    dag_id="platform.management_report",
    default_args=default_args,
    schedule_interval="0 6 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["platform", "report", "cross-domain", "management"],
) as dag:

    # Task 1: Wait for the most recent hourly portfolio_report run
    wait_for_portfolio = ExternalTaskSensor(
        task_id="wait_for_portfolio_report",
        external_dag_id="platform.portfolio_report",
        external_task_id="materialize_report",
        # Accept a successful run within the last 2 hours
        execution_delta=timedelta(hours=2),
        timeout=3600,
        poke_interval=120,
        mode="reschedule",  # releases worker slot while waiting
    )

    # Task 2: dbt build for management models
    dbt_build_mgmt = PythonOperator(
        task_id="dbt_build_mgmt_models",
        python_callable=run_dbt_build_mgmt,
    )

    # Task 3: Cross-domain consistency gate
    cross_domain_gate = PythonOperator(
        task_id="cross_domain_quality_gate",
        python_callable=assert_cross_domain_consistency,
    )

    # Task 4: Write daily KPI snapshot
    mgmt_snapshot = PythonOperator(
        task_id="materialize_management_snapshot",
        python_callable=materialize_management_snapshot,
    )

    # Task 5 + 6: Parallel – PDF export and DataHub lineage
    pdf_export = PythonOperator(
        task_id="export_management_pdf",
        python_callable=export_management_report_pdf,
    )

    datahub_push = PythonOperator(
        task_id="push_datahub_lineage_mgmt",
        python_callable=push_lineage_to_datahub,
    )

    # Task 7: Set portal flag
    portal_refresh = PythonOperator(
        task_id="portal_refresh_management_flag",
        python_callable=set_management_report_flag,
    )

    # Dependencies
    wait_for_portfolio >> dbt_build_mgmt >> cross_domain_gate >> mgmt_snapshot
    mgmt_snapshot >> [pdf_export, datahub_push]
    pdf_export >> portal_refresh
