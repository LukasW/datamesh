#!/usr/bin/env python3
"""export-sqlmesh-lineage.py – Push the SQLMesh model DAG to OpenMetadata.

Runs inside the `datamesh/sqlmesh` image (has `sqlmesh[trino]` + Python 3.12).
For each model, walks ``depends_on`` and PUTs a table-level lineage edge to
OpenMetadata. Column-level lineage is added when SQLMesh can resolve it.

Environment:
  OM_URL            OpenMetadata base URL (internal), e.g. http://openmetadata-server:8585
  OM_TOKEN          Bearer token (ingestion-bot or admin login)
  OM_TRINO_SERVICE  Service name registered in OpenMetadata (default: trino-iceberg)
  OM_TRINO_DATABASE Database name within that service (default: iceberg)
  SQLMESH_PATH      Path to SQLMesh project (default: /sqlmesh)
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

import sqlglot
from sqlglot import exp
from sqlglot.lineage import lineage as sqlglot_lineage
from sqlmesh import Context

OM_URL = os.environ["OM_URL"].rstrip("/")
OM_TOKEN = os.environ["OM_TOKEN"]
SERVICE = os.environ.get("OM_TRINO_SERVICE", "trino-iceberg")
DATABASE = os.environ.get("OM_TRINO_DATABASE", "iceberg")
SQLMESH_PATH = os.environ.get("SQLMESH_PATH", "/sqlmesh")

HEADERS = {
    "Authorization": f"Bearer {OM_TOKEN}",
    "Content-Type": "application/json",
}

_id_cache: dict[str, str | None] = {}


def resolve_table_id(fqn: str) -> str | None:
    """Return the OpenMetadata table UUID for an FQN, or None if unknown."""
    if fqn in _id_cache:
        return _id_cache[fqn]
    url = f"{OM_URL}/api/v1/tables/name/{urllib.parse.quote(fqn, safe='')}"
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            tid = json.loads(resp.read()).get("id")
    except urllib.error.HTTPError:
        tid = None
    except urllib.error.URLError:
        tid = None
    _id_cache[fqn] = tid
    return tid


def table_fqn(name: str) -> str:
    parts = [p for p in name.replace('"', "").split(".") if p]
    if len(parts) == 3:
        _, schema, table = parts
    elif len(parts) == 2:
        schema, table = parts
    else:
        return ""
    return f"{SERVICE}.{DATABASE}.{schema}.{table}"


def put_edge(parent_fqn: str, child_fqn: str, column_lineage: list[dict]) -> str:
    """Push one lineage edge. Returns 'ok', 'missing', or 'error'."""
    parent_id = resolve_table_id(parent_fqn)
    child_id = resolve_table_id(child_fqn)
    if not parent_id or not child_id:
        which = []
        if not parent_id:
            which.append(f"parent {parent_fqn}")
        if not child_id:
            which.append(f"child {child_fqn}")
        print(f"  ↷ skip {parent_fqn} → {child_fqn} (not in OpenMetadata: {', '.join(which)})")
        return "missing"
    details: dict = {
        "description": "SQLMesh declared dependency",
        "source": "Manual",
    }
    if column_lineage:
        details["columnsLineage"] = column_lineage
    payload = {
        "edge": {
            "fromEntity": {"type": "table", "id": parent_id},
            "toEntity": {"type": "table", "id": child_id},
            "lineageDetails": details,
        }
    }
    req = urllib.request.Request(
        f"{OM_URL}/api/v1/lineage",
        method="PUT",
        data=json.dumps(payload).encode("utf-8"),
        headers=HEADERS,
    )
    try:
        urllib.request.urlopen(req, timeout=15)
        return "ok"
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:180]
        print(f"  ⚠ {parent_fqn} → {child_fqn}: HTTP {e.code} {body}")
        return "error"
    except urllib.error.URLError as e:
        print(f"  ⚠ {parent_fqn} → {child_fqn}: {e}")
        return "error"


def _normalize_table_name(table_node: exp.Table) -> str:
    """Convert a sqlglot Table expression into `schema.table` (catalog dropped)."""
    schema = table_node.args.get("db")
    name = table_node.args.get("this")
    if not name:
        return ""
    sch = schema.name if schema else ""
    nm = name.name if hasattr(name, "name") else str(name)
    return f"{sch}.{nm}" if sch else nm


def _column_schema_from_model(model) -> dict:
    """Build a sqlglot schema dict: {table: {col: type}} for all parents."""
    schema: dict = {}
    for dep in getattr(model, "depends_on", set()) or set():
        parts = dep.replace('"', "").split(".")
        if len(parts) == 3:
            _, sch, tbl = parts
        elif len(parts) == 2:
            sch, tbl = parts
        else:
            continue
        # SQLMesh doesn't expose parent schemas cheaply here; use empty dict
        # so sqlglot still recognises the table without knowing column types.
        schema.setdefault(sch, {}).setdefault(tbl, {})
    return schema


def column_lineage_for(ctx: Context, model, parent_name: str) -> list[dict]:
    """Resolve column-level lineage via sqlglot on the rendered model SQL."""
    try:
        rendered = model.render_query()
    except Exception:
        return []
    if rendered is None:
        return []
    sql = rendered.sql(dialect="trino")
    parent_fqn = table_fqn(parent_name)
    if not parent_fqn:
        return []
    child_fqn = table_fqn(model.name)
    sources_by_output: dict[str, list[str]] = {}
    output_cols = list(getattr(model, "columns_to_types", {}) or {})
    for col in output_cols:
        try:
            node = sqlglot_lineage(col, sql, dialect="trino")
        except Exception:
            continue
        for leaf in node.walk():
            src = leaf.source
            if not isinstance(src, exp.Table):
                continue
            leaf_table = _normalize_table_name(src)
            leaf_parent_fqn = table_fqn(leaf_table)
            if leaf_parent_fqn != parent_fqn:
                continue
            # leaf.name may be alias-qualified like `"p"."policy_id"` —
            # strip the alias and quoting to get the plain column name.
            src_col = leaf.name.split(".")[-1].strip('"')
            if not src_col:
                continue
            sources_by_output.setdefault(col, []).append(f"{leaf_parent_fqn}.{src_col}")
    out: list[dict] = []
    for col, srcs in sources_by_output.items():
        # dedupe while preserving order
        seen: set[str] = set()
        unique = [s for s in srcs if not (s in seen or seen.add(s))]
        out.append({
            "fromColumns": unique,
            "toColumn": f"{child_fqn}.{col}",
        })
    return out


def main() -> int:
    ctx = Context(paths=SQLMESH_PATH)
    ok = 0
    fail = 0
    missing = 0
    skipped = 0
    for model in ctx.models.values():
        child_fqn = table_fqn(model.name)
        if not child_fqn:
            skipped += 1
            continue
        for parent in getattr(model, "depends_on", set()) or set():
            parent_fqn = table_fqn(parent)
            if not parent_fqn or parent_fqn == child_fqn:
                skipped += 1
                continue
            cols = column_lineage_for(ctx, model, parent)
            outcome = put_edge(parent_fqn, child_fqn, cols)
            if outcome == "ok":
                ok += 1
            elif outcome == "missing":
                missing += 1
            else:
                fail += 1
    print(f"✓ Lineage edges: {ok} ok / {fail} failed / {missing} missing / {skipped} skipped")
    return 1 if fail and ok == 0 else 0


if __name__ == "__main__":
    sys.exit(main())
