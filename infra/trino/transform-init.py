#!/usr/bin/env python3
"""transform-init.py – Creates Silver, Gold and Analytics Iceberg schemas.

Schemas only — Silver/Gold tables are owned by SQLMesh (see
``infra/sqlmesh/`` and ``{domain}/data-product/sqlmesh/``). The SQLMesh
scheduler (compose service ``sqlmesh-scheduler``) materialises the tables
incrementally per their declared cron, so this script never issues
``CREATE TABLE AS SELECT``.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

TRINO = os.environ.get("TRINO_URL", "http://trino:8086")

SCHEMAS = [
    "partner_silver", "policy_silver", "claims_silver", "billing_silver",
    "product_silver", "hr_silver",
    "partner_gold", "policy_gold", "claims_gold", "billing_gold", "hr_gold",
    "analytics",
]


def trino_exec(sql: str, quiet: bool = False) -> bool:
    """Execute a single SQL statement on Trino. Returns True on success."""
    req = urllib.request.Request(
        f"{TRINO}/v1/statement",
        data=sql.encode("utf-8"),
        headers={"X-Trino-User": "transform-init"},
    )
    try:
        resp = urllib.request.urlopen(req)
    except urllib.error.URLError as e:
        if not quiet:
            print(f"  ERROR connecting to Trino: {e}")
        return False

    body = json.loads(resp.read())
    next_uri = body.get("nextUri")
    while next_uri:
        time.sleep(0.2)
        try:
            resp = urllib.request.urlopen(next_uri)
        except urllib.error.URLError:
            time.sleep(1)
            continue
        body = json.loads(resp.read())
        err = body.get("error", {}).get("message")
        if err:
            if not quiet:
                print(f"  ERROR: {err}")
            return False
        next_uri = body.get("nextUri")
    return True


print("▶ Creating Silver, Gold and Analytics schemas in Iceberg…")
failures = 0
for schema in SCHEMAS:
    ok = trino_exec(f"CREATE SCHEMA IF NOT EXISTS iceberg.{schema}", quiet=True)
    print(f"  {'✓' if ok else '✗'} {schema}")
    if not ok:
        failures += 1

if failures:
    print(f"\n✗ Schema init incomplete: {failures}/{len(SCHEMAS)} failed.")
    sys.exit(1)

print("\n✓ Schemas ready. SQLMesh owns the Silver/Gold tables — see "
      "`sqlmesh-init` and `sqlmesh-scheduler` services.")
