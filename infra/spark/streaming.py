#!/usr/bin/env python3
"""
Spark Structured Streaming: consumes Partner events from Kafka,
parses JSON, and writes to a Delta Lake table.

Data flow: Kafka (person.v1.created / person.v1.updated)
              → /spark-warehouse/raw/person_events  (Delta)
"""
import logging
import os

from pyspark.sql import SparkSession
from pyspark.sql.functions import col, current_timestamp, from_json
from pyspark.sql.types import StringType, StructField, StructType

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
WAREHOUSE      = os.getenv("SPARK_WAREHOUSE", "/spark-warehouse")
DELTA_PATH     = f"{WAREHOUSE}/raw/person_events"
CHECKPOINT     = f"{WAREHOUSE}/checkpoints/person_events"

PAYLOAD_SCHEMA = StructType([
    StructField("eventId",              StringType(), True),
    StructField("eventType",            StringType(), True),
    StructField("personId",             StringType(), True),
    StructField("name",                 StringType(), True),
    StructField("firstName",            StringType(), True),
    StructField("socialSecurityNumber", StringType(), True),
    StructField("dateOfBirth",          StringType(), True),
    StructField("timestamp",            StringType(), True),
])


def build_spark() -> SparkSession:
    return (
        SparkSession.builder
        .appName("partner-event-streaming")
        .master("local[2]")
        .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
        .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
        .config("spark.sql.warehouse.dir", f"{WAREHOUSE}/warehouse")
        .config("spark.databricks.delta.schema.autoMerge.enabled", "true")
        .getOrCreate()
    )


def main() -> None:
    spark = build_spark()
    spark.sparkContext.setLogLevel("WARN")
    log.info("SparkSession ready. Streaming to Delta: %s", DELTA_PATH)

    raw = (
        spark.readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", KAFKA_BOOTSTRAP)
        .option("subscribe", "person.v1.created,person.v1.updated")
        .option("startingOffsets", "earliest")
        .option("failOnDataLoss", "false")
        .load()
    )

    parsed = (
        raw
        .withColumn("payload", col("value").cast(StringType()))
        .withColumn("data",    from_json(col("payload"), PAYLOAD_SCHEMA))
        .select(
            col("topic"),
            col("data.eventId")             .alias("event_id"),
            col("data.eventType")           .alias("event_type"),
            col("data.personId")            .alias("person_id"),
            col("data.name")                .alias("family_name"),
            col("data.firstName")           .alias("first_name"),
            col("data.socialSecurityNumber").alias("social_security_number"),
            col("data.dateOfBirth")         .alias("date_of_birth"),
            col("data.timestamp")           .alias("event_at"),
            col("payload"),
            current_timestamp()             .alias("consumed_at"),
        )
        .filter(col("event_id").isNotNull())
    )

    query = (
        parsed.writeStream
        .format("delta")
        .outputMode("append")
        .option("checkpointLocation", CHECKPOINT)
        .option("mergeSchema", "true")
        .start(DELTA_PATH)
    )

    query.awaitTermination()


if __name__ == "__main__":
    main()
