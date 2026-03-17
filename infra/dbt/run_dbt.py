#!/usr/bin/env python3
"""
dbt launcher for Spark/Delta.

Problem: dbt-spark session method calls SparkSession.builder.getOrCreate()
internally. By pre-creating the SparkSession here (with Delta Lake configured
via configure_spark_with_delta_pip), dbt's getOrCreate() returns that existing
session instead of creating a plain one without Delta support.

Additionally registers the raw Delta table so dbt sources can reference it.
"""
import logging
import os
import time

from delta import configure_spark_with_delta_pip
from dbt.cli.main import dbtRunner
from pyspark.sql import SparkSession

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

WAREHOUSE      = os.getenv("SPARK_WAREHOUSE", "/spark-warehouse")
RAW_DELTA_PATH = f"{WAREHOUSE}/raw/person_events"


def setup_spark() -> SparkSession:
    builder = (
        SparkSession.builder
        .appName("dbt-platform")
        .master("local[2]")
        .config("spark.sql.warehouse.dir", f"{WAREHOUSE}/warehouse")
    )
    spark = configure_spark_with_delta_pip(builder).getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    log.info("SparkSession ready (Delta Lake enabled)")
    return spark


def register_sources(spark: SparkSession) -> None:
    spark.sql("CREATE DATABASE IF NOT EXISTS raw")
    spark.sql("CREATE DATABASE IF NOT EXISTS analytics")
    try:
        spark.sql(f"""
            CREATE TABLE IF NOT EXISTS raw.person_events
            USING DELTA LOCATION '{RAW_DELTA_PATH}'
        """)
        log.info("Registered raw.person_events → %s", RAW_DELTA_PATH)
    except Exception as exc:
        log.warning("Could not register raw.person_events (%s) — no data yet?", exc)


def run_dbt() -> None:
    runner = dbtRunner()
    # dbt-spark session method calls SparkSession.builder.getOrCreate(),
    # which returns the Delta-configured session created above.
    result = runner.invoke(["run", "--project-dir", "/usr/app/dbt", "--profiles-dir", "/usr/app/dbt"])
    if result.exception:
        log.error("dbt run failed: %s", result.exception)
    else:
        log.info("dbt run complete")


if __name__ == "__main__":
    spark = setup_spark()
    register_sources(spark)
    run_dbt()

    log.info("Container is alive. Re-run dbt with:  docker exec <container> python3 run_dbt.py")
    while True:
        time.sleep(3600)
