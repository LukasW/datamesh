#!/usr/bin/env python3
"""export-superset-lineage.py – Push Superset dashboard → Trino table lineage to OpenMetadata.

Workaround for OM 1.12.4 ↔ Superset 6.0.1 connector drift: the bundled
Superset source ingests dashboard entities but fails to extract charts or
dataset-linked lineage. This script reads Superset's REST API directly,
parses each virtual dataset's SQL with sqlglot to resolve the physical Trino
table, then PUTs lineage edges ``trino-iceberg.iceberg.<schema>.<table> →
superset.<dashboard_id>`` into OpenMetadata.

Environment:
  OM_URL           OpenMetadata base URL (default http://localhost:8585)
  OM_TOKEN         OpenMetadata bearer token (admin login if not provided)
  SUPERSET_URL     Superset base URL (default http://localhost:8088)
  SUPERSET_USER    Superset admin user (default admin)
  SUPERSET_PASS    Superset admin password (default admin)
  OM_TRINO_SERVICE Trino service name in OM (default trino-iceberg)
  OM_TRINO_DB      Trino database in OM (default iceberg)
"""
from __future__ import annotations

import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

import sqlglot
from sqlglot import exp

OM_URL = os.environ.get("OM_URL", "http://localhost:8585").rstrip("/")
SUPERSET_URL = os.environ.get("SUPERSET_URL", "http://localhost:8088").rstrip("/")
SUPERSET_USER = os.environ.get("SUPERSET_USER", "admin")
SUPERSET_PASS = os.environ.get("SUPERSET_PASS", "admin")
OM_SERVICE = os.environ.get("OM_TRINO_SERVICE", "trino-iceberg")
OM_DATABASE = os.environ.get("OM_TRINO_DB", "iceberg")
SUPERSET_SERVICE = os.environ.get("OM_SUPERSET_SERVICE", "superset")


def _http(url: str, method: str = "GET", headers: dict | None = None,
          body: bytes | None = None, timeout: int = 15) -> tuple[int, bytes]:
    req = urllib.request.Request(url, method=method, headers=headers or {}, data=body)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def _om_token() -> str:
    tok = os.environ.get("OM_TOKEN")
    if tok:
        return tok
    pwd = base64.b64encode(b"admin").decode()
    code, body = _http(
        f"{OM_URL}/api/v1/users/login",
        "POST",
        {"Content-Type": "application/json"},
        json.dumps({"email": "admin@open-metadata.org", "password": pwd}).encode(),
    )
    if code >= 400:
        raise SystemExit(f"OM login failed: {code} {body[:200]!r}")
    return json.loads(body)["accessToken"]


def _superset_token() -> str:
    code, body = _http(
        f"{SUPERSET_URL}/api/v1/security/login",
        "POST",
        {"Content-Type": "application/json"},
        json.dumps({"username": SUPERSET_USER, "password": SUPERSET_PASS,
                    "provider": "db", "refresh": True}).encode(),
    )
    if code >= 400:
        raise SystemExit(f"Superset login failed: {code} {body[:200]!r}")
    return json.loads(body)["access_token"]


def _get_json(url: str, auth: dict, timeout: int = 15) -> dict:
    code, body = _http(url, "GET", auth, timeout=timeout)
    if code >= 400:
        raise RuntimeError(f"GET {url}: HTTP {code} {body[:160]!r}")
    return json.loads(body)


def extract_tables(sql: str) -> list[tuple[str, str]]:
    """Parse SQL and return list of (schema, table) for referenced physical tables."""
    try:
        tree = sqlglot.parse_one(sql, read="trino")
    except Exception:
        return []
    out: list[tuple[str, str]] = []
    for t in tree.find_all(exp.Table):
        sch = t.args.get("db")
        nm = t.args.get("this")
        if not nm:
            continue
        schema = sch.name if sch else ""
        table = nm.name if hasattr(nm, "name") else str(nm)
        if schema and table:
            out.append((schema, table))
    return out


def resolve_om_table_id(fqn: str, auth: dict, cache: dict) -> str | None:
    if fqn in cache:
        return cache[fqn]
    code, body = _http(
        f"{OM_URL}/api/v1/tables/name/{urllib.parse.quote(fqn, safe='')}",
        "GET", auth,
    )
    tid = json.loads(body).get("id") if code < 400 else None
    cache[fqn] = tid
    return tid


def resolve_om_dashboard_id(name: str, auth: dict) -> str | None:
    fqn = f"{SUPERSET_SERVICE}.{name}"
    code, body = _http(
        f"{OM_URL}/api/v1/dashboards/name/{urllib.parse.quote(fqn, safe='')}",
        "GET", auth,
    )
    return json.loads(body).get("id") if code < 400 else None


def put_edge(table_id: str, dashboard_id: str, auth: dict) -> bool:
    payload = {
        "edge": {
            "fromEntity": {"type": "table", "id": table_id},
            "toEntity": {"type": "dashboard", "id": dashboard_id},
            "lineageDetails": {
                "description": "Superset dashboard queries this table",
                "source": "DashboardLineage",
            },
        }
    }
    code, body = _http(
        f"{OM_URL}/api/v1/lineage", "PUT",
        {**auth, "Content-Type": "application/json"},
        json.dumps(payload).encode(),
    )
    if code >= 400:
        print(f"  ⚠ lineage PUT HTTP {code}: {body[:160]!r}")
        return False
    return True


def main() -> int:
    print(f"▶ Superset → OpenMetadata lineage export")
    om_auth = {"Authorization": f"Bearer {_om_token()}"}
    ss_auth = {"Authorization": f"Bearer {_superset_token()}"}

    # 1. Fetch all datasets → map dataset_id → list[(schema, table)]
    ds_list = _get_json(f"{SUPERSET_URL}/api/v1/dataset/?q=(page_size:200)", ss_auth)
    dataset_tables: dict[int, list[tuple[str, str]]] = {}
    for ds in ds_list.get("result", []):
        detail = _get_json(f"{SUPERSET_URL}/api/v1/dataset/{ds['id']}", ss_auth)["result"]
        sql = detail.get("sql")
        if sql:
            dataset_tables[ds["id"]] = extract_tables(sql)
        else:
            # physical dataset
            sch = detail.get("schema") or ""
            tbl = detail.get("table_name") or ""
            if sch and tbl:
                dataset_tables[ds["id"]] = [(sch, tbl)]
    print(f"  datasets resolved: {len(dataset_tables)}")

    # 2. Fetch all charts → map chart_id → dataset_id
    ch_list = _get_json(f"{SUPERSET_URL}/api/v1/chart/?q=(page_size:500)", ss_auth)
    chart_to_ds: dict[int, int] = {
        c["id"]: c.get("datasource_id")
        for c in ch_list.get("result", [])
        if c.get("datasource_type") == "table" and c.get("datasource_id")
    }
    print(f"  charts: {len(chart_to_ds)}")

    # 3. Fetch all dashboards, then per-dashboard charts to get slice ids
    dash_list = _get_json(
        f"{SUPERSET_URL}/api/v1/dashboard/?q=(page_size:200)", ss_auth
    )
    table_id_cache: dict[str, str | None] = {}
    total_edges = 0
    total_skipped = 0
    for d in dash_list.get("result", []):
        dash_id_om = resolve_om_dashboard_id(str(d["id"]), om_auth)
        if not dash_id_om:
            print(f"  ↷ dashboard '{d['dashboard_title']}' not in OM — run OM dashboard ingestion first")
            continue
        # Superset: list charts on this dashboard
        chs = _get_json(
            f"{SUPERSET_URL}/api/v1/dashboard/{d['id']}/charts", ss_auth
        ).get("result", [])
        sources: set[tuple[str, str]] = set()
        for c in chs:
            ds_id = c.get("form_data", {}).get("datasource", "").split("__")[0]
            try:
                ds_id_int = int(ds_id)
            except (TypeError, ValueError):
                ds_id_int = chart_to_ds.get(c.get("slice_id") or c.get("id"))
            if not ds_id_int:
                continue
            for st in dataset_tables.get(ds_id_int, []):
                sources.add(st)
        print(f"  dashboard '{d['dashboard_title']}' → {len(sources)} source tables")
        for schema, table in sources:
            fqn = f"{OM_SERVICE}.{OM_DATABASE}.{schema}.{table}"
            tid = resolve_om_table_id(fqn, om_auth, table_id_cache)
            if not tid:
                print(f"    ↷ {fqn} not in OM")
                total_skipped += 1
                continue
            if put_edge(tid, dash_id_om, om_auth):
                total_edges += 1
                print(f"    ✓ {schema}.{table} → {d['dashboard_title']}")
            else:
                total_skipped += 1
    print(f"✓ edges pushed: {total_edges}, skipped: {total_skipped}")
    return 0 if total_edges > 0 or total_skipped == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
