"""
DAG: platform.dbt_transform
Schedule: @hourly
Owner: platform@css.ch

Runs dbt transformations against the platform database on an hourly schedule.
This DAG is the single entry point for all dbt runs and replaces the former
one-shot dbt container in docker-compose.

Dependency chain:
  platform-consumer (writes raw Kafka events into platform-db)
    -> this DAG (transforms raw tables into staging + mart models)
      -> platform.portfolio_report  (downstream, consumes dbt marts)
      -> platform.management_report (downstream, consumes dbt marts)

Downstream DAGs should use an ExternalTaskSensor on task_id="dbt_run" from
this DAG to ensure models are fresh before building reports.

Pipeline:
  1. dbt_run   – Full dbt run (all models) against the platform target
  2. dbt_test  – Run dbt tests to validate model quality
"""
import os
import subprocess
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator

default_args = {
    "owner": "platform",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
    "email_on_failure": False,
}

DBT_PROJECT_DIR = "/opt/dbt"
DBT_PROFILES_DIR = "/opt/dbt"
DBT_TARGET = "platform"

with DAG(
    dag_id="platform.dbt_transform",
    default_args=default_args,
    schedule_interval="@hourly",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["platform", "dbt", "transform"],
    doc_md=__doc__,
) as dag:

    # Task 1: dbt run – execute all models
    dbt_run = BashOperator(
        task_id="dbt_run",
        bash_command=(
            f"dbt run "
            f"--project-dir {DBT_PROJECT_DIR} "
            f"--profiles-dir {DBT_PROFILES_DIR} "
            f"--target {DBT_TARGET}"
        ),
    )

    # Task 2: dbt test – validate model output quality
    dbt_test = BashOperator(
        task_id="dbt_test",
        bash_command=(
            f"dbt test "
            f"--project-dir {DBT_PROJECT_DIR} "
            f"--profiles-dir {DBT_PROFILES_DIR} "
            f"--target {DBT_TARGET}"
        ),
    )

    dbt_run >> dbt_test
