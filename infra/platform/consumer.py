#!/usr/bin/env python3
"""
Platform Consumer: ingests domain events from Kafka into the platform database.
Acts as the raw ingestion layer (simulating a Delta Live Table ingestion job).

Topics consumed:
  Partner domain  → raw.person_events    (person.v1.created, person.v1.updated)
  Partner domain  → raw.person_state     (person.v1.state – ECST compacted topic)
  Product domain  → raw.product_events   (product.v1.defined, product.v1.updated, product.v1.deprecated)
  Policy domain   → raw.policy_events    (policy.v1.issued, policy.v1.cancelled, policy.v1.changed)

Architecture note: ONLY Kafka topics (Data Outside) are consumed. The domain databases
(partner_db, product_db, policy_db) are Data Inside and are never accessed from here.
"""
import json
import logging
import os
import time

import psycopg2
from kafka import KafkaConsumer
from kafka.errors import NoBrokersAvailable

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

PARTNER_TOPICS = ["person.v1.created", "person.v1.updated"]
PARTNER_STATE_TOPICS = ["person.v1.state"]
PRODUCT_TOPICS = ["product.v1.defined", "product.v1.updated", "product.v1.deprecated"]
POLICY_TOPICS = ["policy.v1.issued", "policy.v1.cancelled", "policy.v1.changed"]

ALL_TOPICS = PARTNER_TOPICS + PARTNER_STATE_TOPICS + PRODUCT_TOPICS + POLICY_TOPICS

BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
GROUP_ID = os.getenv("CONSUMER_GROUP_ID", "platform-consumer")
PLATFORM_DB_URL = os.getenv(
    "PLATFORM_DB_URL",
    "postgresql://platform_user:platform_pass@platform-db:5432/platform_db",
)

INSERT_PERSON_SQL = """
INSERT INTO raw.person_events (event_id, topic, event_type, person_id, payload)
VALUES (%s, %s, %s, %s, %s)
ON CONFLICT (event_id) DO NOTHING
"""

UPSERT_PERSON_STATE_SQL = """
INSERT INTO raw.person_state (person_id, city, postal_code, deleted, payload)
VALUES (%s, %s, %s, %s, %s)
ON CONFLICT (person_id) DO UPDATE SET
    city        = EXCLUDED.city,
    postal_code = EXCLUDED.postal_code,
    deleted     = EXCLUDED.deleted,
    payload     = EXCLUDED.payload,
    consumed_at = NOW()
"""

INSERT_PRODUCT_SQL = """
INSERT INTO raw.product_events (event_id, topic, event_type, product_id, payload)
VALUES (%s, %s, %s, %s, %s)
ON CONFLICT (event_id) DO NOTHING
"""

INSERT_POLICY_SQL = """
INSERT INTO raw.policy_events (event_id, topic, event_type, policy_id, partner_id, product_id, payload)
VALUES (%s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (event_id) DO NOTHING
"""


def wait_for_kafka() -> KafkaConsumer:
    while True:
        try:
            consumer = KafkaConsumer(
                *ALL_TOPICS,
                bootstrap_servers=BOOTSTRAP_SERVERS,
                group_id=GROUP_ID,
                auto_offset_reset="earliest",
                enable_auto_commit=True,
                value_deserializer=lambda m: m.decode("utf-8", errors="replace"),
                consumer_timeout_ms=-1,
            )
            log.info("Connected to Kafka %s, topics=%s", BOOTSTRAP_SERVERS, ALL_TOPICS)
            return consumer
        except NoBrokersAvailable:
            log.warning("Kafka not available yet, retrying in 5s...")
            time.sleep(5)


def wait_for_db() -> psycopg2.extensions.connection:
    while True:
        try:
            conn = psycopg2.connect(PLATFORM_DB_URL)
            conn.autocommit = True
            log.info("Connected to platform-db")
            return conn
        except psycopg2.OperationalError as exc:
            log.warning("platform-db not available (%s), retrying in 5s...", exc)
            time.sleep(5)


def _residence_city(addresses: list) -> tuple:
    """Extract city and postal_code from the RESIDENCE address."""
    for addr in addresses:
        if addr.get("addressType") == "RESIDENCE":
            return addr.get("city"), addr.get("postalCode")
    # Fall back to first address if no RESIDENCE found
    if addresses:
        return addresses[0].get("city"), addresses[0].get("postalCode")
    return None, None


def process(cur, topic: str, raw_value: str) -> None:
    try:
        data = json.loads(raw_value)
    except (json.JSONDecodeError, UnicodeDecodeError):
        log.debug("Skipping non-JSON message on topic %s", topic)
        return

    event_id = data.get("eventId")
    event_type = data.get("eventType")

    if topic in PARTNER_TOPICS:
        if not event_id:
            return
        cur.execute(INSERT_PERSON_SQL, (
            event_id, topic, event_type, data.get("personId"), raw_value,
        ))
        log.info("Ingested %s | person_id=%s", event_type, data.get("personId"))

    elif topic in PARTNER_STATE_TOPICS:
        person_id = data.get("personId")
        if not person_id:
            return
        deleted = data.get("deleted", False)
        city, postal_code = _residence_city(data.get("addresses") or [])
        cur.execute(UPSERT_PERSON_STATE_SQL, (
            person_id, city, postal_code, deleted, raw_value,
        ))
        log.info("Upserted person.v1.state | person_id=%s deleted=%s city=%s", person_id, deleted, city)

    elif topic in PRODUCT_TOPICS:
        if not event_id:
            return
        cur.execute(INSERT_PRODUCT_SQL, (
            event_id, topic, event_type, data.get("productId"), raw_value,
        ))
        log.info("Ingested %s | product_id=%s", event_type, data.get("productId"))

    elif topic in POLICY_TOPICS:
        if not event_id:
            return
        cur.execute(INSERT_POLICY_SQL, (
            event_id, topic, event_type,
            data.get("policyId"), data.get("partnerId"), data.get("productId"),
            raw_value,
        ))
        log.info("Ingested %s | policy_id=%s", event_type, data.get("policyId"))


def main() -> None:
    conn = wait_for_db()
    consumer = wait_for_kafka()
    cur = conn.cursor()
    log.info("Platform consumer running, waiting for events...")
    try:
        for message in consumer:
            process(cur, message.topic, message.value)
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    main()
