"""
Data Product Portal – FastAPI application for the CSS Data Mesh prototype.

Routes:
  GET /              → Data Product Catalogue (all domains)
  GET /products/{t}  → Single data product detail
  GET /lineage       → Cross-domain lineage graph (D3.js)
  GET /governance    → Governance dashboard (Schema Registry status)
  GET /demo          → Cross-domain analytics demo (mart_portfolio_summary)
"""
import os
from pathlib import Path
from typing import Any

import psycopg2
import requests
import yaml
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates

app = FastAPI(title="CSS Data Mesh – Data Product Portal")
templates = Jinja2Templates(directory=Path(__file__).parent / "templates")

SCHEMA_REGISTRY_URL = os.getenv("SCHEMA_REGISTRY_URL", "http://schema-registry:8081").rstrip("/")
PLATFORM_DB_URL = os.getenv(
    "PLATFORM_DB_URL",
    "postgresql://platform_user:platform_pass@platform-db:5432/platform_db",
)

# Resolve contract directories
_CONTRACT_ROOT = Path("/contracts")
_REPO_ROOT = Path(__file__).parent.parent.parent
if _CONTRACT_ROOT.exists():
    _CONTRACT_DIRS = {
        "partner": _CONTRACT_ROOT / "partner",
        "policy":  _CONTRACT_ROOT / "policy",
        "product": _CONTRACT_ROOT / "product",
    }
else:
    _CONTRACT_DIRS = {
        d: _REPO_ROOT / d / "src" / "main" / "resources" / "contracts"
        for d in ("partner", "policy", "product")
    }

# ---------------------------------------------------------------------------
# Data loading helpers
# ---------------------------------------------------------------------------

def _get_registry(path: str) -> Any:
    try:
        r = requests.get(f"{SCHEMA_REGISTRY_URL}{path}", timeout=3)
        r.raise_for_status()
        return r.json()
    except Exception:
        return None


def _registered_subjects() -> set[str]:
    subjects = _get_registry("/subjects")
    return set(subjects) if subjects else set()


def _parse_odc(path: Path, domain: str, subjects: set[str]) -> dict:
    try:
        doc = yaml.safe_load(path.read_text())
    except Exception:
        return {}

    api = str(doc.get("apiVersion", ""))
    is_v2 = api.startswith("v2")

    if is_v2:
        info = doc.get("info", {}) or {}
        servers_raw = doc.get("servers") or []
        # servers may be a list or (rarely) a bare dict — normalise to a single dict
        if isinstance(servers_raw, list):
            server = servers_raw[0] if servers_raw else {}
        elif isinstance(servers_raw, dict):
            server = servers_raw
        else:
            server = {}
        topic = server.get("topic", path.stem)
        schema_fields = (doc.get("schema") or {}).get("fields", [])
        sla = info.get("sla", {}) or {}
        dp_owner = (
            (info.get("contact") or {}).get("email")
            or info.get("owner", "")
        )
        dp_tags = info.get("tags", []) or []
        output_port = server.get("type", "kafka") or "kafka"
        quality_raw = doc.get("quality") or {}
        if isinstance(quality_raw, dict):
            spec_val = quality_raw.get("specification", "")
            quality_text = spec_val if isinstance(spec_val, str) else str(spec_val)
        else:
            quality_text = ""
    else:
        spec = doc.get("spec", {}) or {}
        topic = spec.get("topic", path.stem)
        schema_fields = (spec.get("schema") or {}).get("fields", [])
        dp = doc.get("dataProduct", {}) or {}
        sla = dp.get("sla", {}) or {}
        dp_owner = dp.get("owner", "")
        dp_tags = dp.get("tags", []) or []
        output_port = dp.get("outputPort", "kafka") or "kafka"
        quality_list = spec.get("quality") or []
        if isinstance(quality_list, list) and quality_list:
            quality_text = (quality_list[0] or {}).get("checks", "")
        else:
            quality_text = ""

    subject = f"{topic}-value"
    return {
        "topic":         topic,
        "domain":        domain,
        "owner":         dp_owner,
        "output_port":   output_port,
        "sla_freshness": sla.get("freshness", "—"),
        "sla_availability": sla.get("availability", "—"),
        "quality_score": sla.get("qualityScore"),
        "tags":          dp_tags,
        "field_count":   len(schema_fields),
        "fields":        schema_fields,
        "quality_text":  quality_text,
        "schema_registered": subject in subjects,
    }


def load_data_products() -> list[dict]:
    subjects = _registered_subjects()
    products = []
    for domain, directory in _CONTRACT_DIRS.items():
        if not directory.exists():
            continue
        for path in sorted(directory.glob("*.odcontract.yaml")):
            dp = _parse_odc(path, domain, subjects)
            if dp:
                products.append(dp)
    return products


def load_lineage() -> list[dict]:
    return [
        {"from": "partner-db",          "to": "person.v1.created",        "type": "outbox"},
        {"from": "partner-db",          "to": "person.v1.updated",         "type": "outbox"},
        {"from": "partner-db",          "to": "person.v1.deleted",         "type": "outbox"},
        {"from": "partner-db",          "to": "person.v1.state",           "type": "outbox"},
        {"from": "partner-db",          "to": "person.v1.address-added",   "type": "outbox"},
        {"from": "product-db",          "to": "product.v1.defined",        "type": "outbox"},
        {"from": "product-db",          "to": "product.v1.updated",        "type": "outbox"},
        {"from": "product-db",          "to": "product.v1.deprecated",     "type": "outbox"},
        {"from": "policy-service",      "to": "policy.v1.issued",          "type": "direct"},
        {"from": "policy-service",      "to": "policy.v1.cancelled",       "type": "direct"},
        {"from": "policy-service",      "to": "policy.v1.changed",         "type": "direct"},
        {"from": "person.v1.created",   "to": "raw.person_events",         "type": "kafka"},
        {"from": "person.v1.updated",   "to": "raw.person_events",         "type": "kafka"},
        {"from": "person.v1.state",     "to": "raw.person_state",          "type": "kafka"},
        {"from": "product.v1.defined",  "to": "raw.product_events",        "type": "kafka"},
        {"from": "product.v1.updated",  "to": "raw.product_events",        "type": "kafka"},
        {"from": "product.v1.deprecated","to": "raw.product_events",       "type": "kafka"},
        {"from": "policy.v1.issued",    "to": "raw.policy_events",         "type": "kafka"},
        {"from": "policy.v1.cancelled", "to": "raw.policy_events",         "type": "kafka"},
        {"from": "policy.v1.changed",   "to": "raw.policy_events",         "type": "kafka"},
        {"from": "raw.person_events",   "to": "stg_person_events",         "type": "dbt"},
        {"from": "raw.person_state",    "to": "dim_partner",               "type": "dbt"},
        {"from": "raw.product_events",  "to": "stg_product_events",        "type": "dbt"},
        {"from": "raw.policy_events",   "to": "stg_policy_events",         "type": "dbt"},
        {"from": "stg_person_events",   "to": "dim_partner",               "type": "dbt"},
        {"from": "stg_product_events",  "to": "dim_product",               "type": "dbt"},
        {"from": "stg_policy_events",   "to": "fact_policies",             "type": "dbt"},
        {"from": "dim_partner",         "to": "mart_portfolio_summary",    "type": "dbt"},
        {"from": "dim_product",         "to": "mart_portfolio_summary",    "type": "dbt"},
        {"from": "fact_policies",       "to": "mart_portfolio_summary",    "type": "dbt"},
        {"from": "dim_partner",         "to": "mart_policy_detail",        "type": "dbt"},
        {"from": "dim_product",         "to": "mart_policy_detail",        "type": "dbt"},
        {"from": "fact_policies",       "to": "mart_policy_detail",        "type": "dbt"},
    ]


def load_governance_status() -> dict:
    subjects = _get_registry("/subjects")
    if subjects is None:
        return {"connected": False, "subjects": [], "total": 0, "warnings": 0}

    rows = []
    warnings = 0
    for subject in sorted(subjects):
        versions = _get_registry(f"/subjects/{subject}/versions") or []
        cfg = _get_registry(f"/config/{subject}") or {}
        compat = cfg.get("compatibilityLevel", "INHERITED")
        status = "OK" if compat in ("FULL_TRANSITIVE", "FULL") else "WARN" if compat != "NONE" else "ERROR"
        if status != "OK":
            warnings += 1
        rows.append({"subject": subject, "versions": len(versions), "compat": compat, "status": status})

    return {"connected": True, "subjects": rows, "total": len(subjects), "warnings": warnings}


def load_demo_data() -> tuple[list[dict], str]:
    sql = """
SELECT product_line, product_name, active_policies,
       total_premium_chf, avg_premium_chf
FROM analytics.mart_portfolio_summary
ORDER BY total_premium_chf DESC NULLS LAST
"""
    try:
        conn = psycopg2.connect(PLATFORM_DB_URL, connect_timeout=3)
        cur = conn.cursor()
        cur.execute(sql)
        cols = [d[0] for d in cur.description]
        rows = [dict(zip(cols, row)) for row in cur.fetchall()]
        cur.close()
        conn.close()
        return rows, None
    except Exception as exc:
        return [], str(exc)


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    products = load_data_products()
    domains = sorted({p["domain"] for p in products})
    pii_count = sum(1 for p in products if "pii" in p.get("tags", []))
    sla_ok = sum(1 for p in products if p.get("quality_score") and p["quality_score"] >= 0.95)
    return templates.TemplateResponse("index.html", {
        "request": request,
        "data_products": products,
        "domains": domains,
        "total": len(products),
        "pii_count": pii_count,
        "sla_ok": sla_ok,
    })


@app.get("/products/{topic:path}", response_class=HTMLResponse)
def product_detail(request: Request, topic: str):
    products = load_data_products()
    product = next((p for p in products if p["topic"] == topic), None)
    return templates.TemplateResponse("product.html", {
        "request": request,
        "product": product,
        "topic": topic,
    })


@app.get("/lineage", response_class=HTMLResponse)
def lineage(request: Request):
    import json
    edges = load_lineage()
    nodes_set = set()
    for e in edges:
        nodes_set.add(e["from"])
        nodes_set.add(e["to"])

    def node_type(n: str) -> str:
        if n.endswith("-db"):         return "database"
        if n.endswith("-service"):    return "service"
        if "." in n and not n.startswith("raw.") and not n.startswith("stg_") \
           and not n.startswith("dim_") and not n.startswith("fact_") \
           and not n.startswith("mart_"):
            return "topic"
        if n.startswith("raw."):      return "raw"
        if n.startswith("stg_"):      return "staging"
        return "mart"

    nodes = [{"id": n, "type": node_type(n)} for n in sorted(nodes_set)]
    return templates.TemplateResponse("lineage.html", {
        "request": request,
        "nodes_json": json.dumps(nodes),
        "edges_json": json.dumps(edges),
    })


@app.get("/governance", response_class=HTMLResponse)
def governance(request: Request):
    status = load_governance_status()
    products = load_data_products()
    return templates.TemplateResponse("governance.html", {
        "request": request,
        "governance": status,
        "data_products": products,
    })


@app.get("/demo", response_class=HTMLResponse)
def demo(request: Request):
    rows, error = load_demo_data()
    return templates.TemplateResponse("demo.html", {
        "request": request,
        "rows": rows,
        "error": error,
    })
