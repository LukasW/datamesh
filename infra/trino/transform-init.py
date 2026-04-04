#!/usr/bin/env python3
"""transform-init.py – Creates Silver & Gold Iceberg tables via Trino.

Reads from domain PostgreSQL databases (via Trino PostgreSQL catalogs or
Iceberg raw tables) and materialises Silver/Gold Iceberg tables.
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error

TRINO = "http://trino:8086"


def trino_exec(sql: str, quiet: bool = False) -> int | None:
    """Execute SQL on Trino, return row count for CTAS or None on error."""
    data = sql.encode("utf-8")
    req = urllib.request.Request(
        f"{TRINO}/v1/statement",
        data=data,
        headers={"X-Trino-User": "transform-init"},
    )
    try:
        resp = urllib.request.urlopen(req)
    except urllib.error.URLError as e:
        if not quiet:
            print(f"  ERROR connecting to Trino: {e}")
        return None
    body = json.loads(resp.read())
    next_uri = body.get("nextUri")
    result_data = body.get("data")

    while next_uri:
        time.sleep(0.4)
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
            return None
        if body.get("data"):
            result_data = body["data"]
        next_uri = body.get("nextUri")

    if result_data and len(result_data) > 0 and len(result_data[0]) > 0:
        try:
            return int(result_data[0][0])
        except (ValueError, TypeError):
            pass
    return 0


def trino_count(sql: str) -> int:
    r = trino_exec(sql, quiet=True)
    return r if r is not None else 0


def create_table(name: str, sql: str):
    print(f"  {name}: ", end="", flush=True)
    trino_exec(f"DROP TABLE IF EXISTS iceberg.{name}", quiet=True)
    result = trino_exec(f"CREATE TABLE iceberg.{name} AS {sql}")
    if result is not None:
        cnt = trino_count(f"SELECT count(*) FROM iceberg.{name}")
        print(f"{cnt} rows")
    else:
        print("FAILED")


def has_raw_table(schema: str) -> bool:
    """Check if an Iceberg raw schema exists in Nessie."""
    try:
        req = urllib.request.Request("http://nessie:19120/api/v2/trees/main/entries")
        resp = urllib.request.urlopen(req)
        entries = json.loads(resp.read()).get("entries", [])
        for e in entries:
            if e["type"] == "NAMESPACE" and e["name"]["elements"] == [schema]:
                return True
    except Exception:
        pass
    return False


# ── Determine data source strategy ──────────────────────────────────────────

print("▶ Checking available data sources…")

# Wait for Iceberg raw data (from Debezium sinks)
max_wait = 300
elapsed = 0
raw_available = False
while elapsed < max_wait:
    raw_schemas = sum(1 for s in ["policy_raw", "product_raw", "claims_raw",
                                   "billing_raw", "partner_raw"]
                      if has_raw_table(s))
    if raw_schemas >= 4:
        # Also verify data exists
        policy_cnt = trino_count("SELECT count(*) FROM iceberg.policy_raw.policy_events")
        product_cnt = trino_count("SELECT count(*) FROM iceberg.product_raw.product_events")
        if policy_cnt > 0 and product_cnt > 0:
            print(f"  ✓ Iceberg raw data available ({raw_schemas} schemas, policy={policy_cnt}, product={product_cnt})")
            raw_available = True
            break
    print(f"  Waiting… ({raw_schemas}/5 raw schemas in Nessie, {elapsed}s/{max_wait}s)")
    time.sleep(10)
    elapsed += 10

if not raw_available:
    print(f"  ⚠ Timeout – raw data not fully available after {max_wait}s")
    print("  Creating schemas only (tables will be empty)")

# ── Create schemas ───────────────────────────────────────────────────────────

print("\n▶ Creating Silver & Gold schemas…")
for schema in [
    "partner_silver", "policy_silver", "claims_silver", "billing_silver",
    "product_silver", "hr_silver", "partner_gold", "policy_gold",
    "claims_gold", "billing_gold", "analytics",
]:
    trino_exec(f"CREATE SCHEMA IF NOT EXISTS iceberg.{schema}", quiet=True)
    print(f"  ✓ {schema}")

if not raw_available:
    print("\n✓ Transform init complete (schemas only – no raw data available).")
    sys.exit(0)

# ── Silver layer ─────────────────────────────────────────────────────────────

print("\n▶ Creating Silver tables…")

create_table("product_silver.product", """
WITH ranked AS (
    SELECT productid AS product_id, name AS product_name, productline AS product_line,
        CAST(basepremium AS DECIMAL(15,2)) AS base_premium_chf, status,
        COALESCE(deleted, false) AS deleted, from_iso8601_timestamp(timestamp) AS updated_at,
        ROW_NUMBER() OVER (PARTITION BY productid ORDER BY from_iso8601_timestamp(timestamp) DESC) AS rn
    FROM iceberg.product_raw.product_events
    WHERE eventtype IN ('ProductState','ProductDefined','ProductDeprecated') AND productid IS NOT NULL
)
SELECT product_id, product_name, product_line, base_premium_chf, status, deleted,
    CASE WHEN deleted THEN true WHEN status='DEPRECATED' THEN true ELSE false END AS is_deprecated, updated_at
FROM ranked WHERE rn = 1
""")

create_table("partner_silver.partner", """
WITH ranked AS (
    SELECT personid AS partner_id, name AS family_name, firstname AS first_name,
        socialsecuritynumber AS social_security_number, insurednumber AS insured_number,
        dateofbirth AS date_of_birth, gender, encrypted, deleted,
        from_iso8601_timestamp(timestamp) AS updated_at,
        ROW_NUMBER() OVER (PARTITION BY personid ORDER BY from_iso8601_timestamp(timestamp) DESC) AS rn
    FROM iceberg.partner_raw.person_events
    WHERE eventtype = 'PersonState' AND personid IS NOT NULL
)
SELECT partner_id, family_name, first_name, social_security_number, insured_number,
    date_of_birth, gender, encrypted, COALESCE(deleted, false) AS deleted,
    CASE WHEN COALESCE(deleted, false) THEN 'DELETED'
         WHEN insured_number IS NOT NULL AND insured_number != '' THEN 'INSURED'
         ELSE 'PROSPECT' END AS partner_status, updated_at
FROM ranked WHERE rn = 1
""")

create_table("policy_silver.policy", """
WITH ranked AS (
    SELECT policyid AS policy_id, policynumber AS policy_number, partnerid AS partner_id,
        productid AS product_id, CAST(coveragestartdate AS DATE) AS coverage_start_date,
        CAST(premium AS DECIMAL(15,2)) AS premium_chf, eventtype,
        from_iso8601_timestamp(timestamp) AS event_at,
        ROW_NUMBER() OVER (PARTITION BY policyid ORDER BY from_iso8601_timestamp(timestamp) DESC) AS rn
    FROM iceberg.policy_raw.policy_events
    WHERE eventtype IN ('PolicyIssued','PolicyCancelled','PolicyChanged') AND policyid IS NOT NULL
),
first_issued AS (
    SELECT policyid AS policy_id, MIN(from_iso8601_timestamp(timestamp)) AS issued_at
    FROM iceberg.policy_raw.policy_events WHERE eventtype='PolicyIssued' GROUP BY policyid
)
SELECT r.policy_id, r.policy_number, r.partner_id, r.product_id, r.coverage_start_date, r.premium_chf,
    CASE r.eventtype WHEN 'PolicyCancelled' THEN 'CANCELLED' ELSE 'ACTIVE' END AS policy_status,
    fi.issued_at, r.event_at AS updated_at
FROM ranked r LEFT JOIN first_issued fi ON r.policy_id = fi.policy_id WHERE r.rn = 1
""")

create_table("policy_silver.coverage", """
SELECT coverageid AS coverage_id, policyid AS policy_id, coveragetype AS coverage_type,
    CAST(insuredamount AS DECIMAL(15,2)) AS insured_amount_chf,
    from_iso8601_timestamp(timestamp) AS created_at
FROM iceberg.policy_raw.policy_events
WHERE eventtype = 'CoverageAdded' AND coverageid IS NOT NULL
""")

create_table("claims_silver.claim", """
WITH ranked AS (
    SELECT claimid AS claim_id, claimnumber AS claim_number, policyid AS policy_id,
        description, CAST(claimdate AS DATE) AS claim_date, status, eventtype,
        from_iso8601_timestamp(timestamp) AS event_at,
        ROW_NUMBER() OVER (PARTITION BY claimid ORDER BY from_iso8601_timestamp(timestamp) DESC) AS rn
    FROM iceberg.claims_raw.claims_events
    WHERE eventtype IN ('ClaimOpened','ClaimSettled') AND claimid IS NOT NULL
),
first_opened AS (
    SELECT claimid AS claim_id, MIN(from_iso8601_timestamp(timestamp)) AS opened_at
    FROM iceberg.claims_raw.claims_events WHERE eventtype='ClaimOpened' GROUP BY claimid
)
SELECT r.claim_id, r.claim_number, r.policy_id, r.description, r.claim_date, r.status,
    fo.opened_at, r.event_at AS updated_at
FROM ranked r LEFT JOIN first_opened fo ON r.claim_id = fo.claim_id WHERE r.rn = 1
""")

create_table("billing_silver.invoice", """
WITH ranked AS (
    SELECT invoiceid AS invoice_id, invoicenumber AS invoice_number, policyid AS policy_id,
        partnerid AS partner_id, policynumber AS policy_number, billingcycle AS billing_cycle,
        CAST(totalamount AS DECIMAL(15,2)) AS total_amount_chf, CAST(duedate AS DATE) AS due_date,
        eventtype, CAST(amountpaid AS DECIMAL(15,2)) AS amount_paid_chf,
        CAST(paidat AS DATE) AS paid_at, dunninglevel AS dunning_level,
        from_iso8601_timestamp(timestamp) AS event_at,
        ROW_NUMBER() OVER (PARTITION BY invoiceid ORDER BY from_iso8601_timestamp(timestamp) DESC) AS rn
    FROM iceberg.billing_raw.billing_events
    WHERE eventtype IN ('InvoiceCreated','PaymentReceived','DunningInitiated','PayoutTriggered') AND invoiceid IS NOT NULL
),
first_created AS (
    SELECT invoiceid AS invoice_id, MIN(from_iso8601_timestamp(timestamp)) AS created_at
    FROM iceberg.billing_raw.billing_events WHERE eventtype='InvoiceCreated' GROUP BY invoiceid
)
SELECT r.invoice_id, r.invoice_number, r.policy_id, r.partner_id, r.policy_number, r.billing_cycle,
    r.total_amount_chf, r.due_date,
    CASE r.eventtype WHEN 'PaymentReceived' THEN 'PAID' WHEN 'DunningInitiated' THEN 'OVERDUE'
         WHEN 'PayoutTriggered' THEN 'PAYOUT' ELSE 'OPEN' END AS invoice_status,
    r.amount_paid_chf, r.paid_at, r.dunning_level, fc.created_at, r.event_at AS updated_at
FROM ranked r LEFT JOIN first_created fc ON r.invoice_id = fc.invoice_id WHERE r.rn = 1
""")

create_table("hr_silver.employee", """
WITH ranked AS (
    SELECT employeeid AS employee_id, externalid AS external_id, firstname AS first_name,
        lastname AS last_name, email, jobtitle AS job_title, department,
        orgunitid AS org_unit_id, CAST(entrydate AS DATE) AS entry_date,
        COALESCE(active, true) AS active, COALESCE(deleted, false) AS deleted, version,
        from_iso8601_timestamp(timestamp) AS updated_at,
        ROW_NUMBER() OVER (PARTITION BY employeeid ORDER BY from_iso8601_timestamp(timestamp) DESC NULLS LAST, version DESC NULLS LAST) AS rn
    FROM iceberg.hr_raw.employee_events WHERE employeeid IS NOT NULL AND employeeid != ''
)
SELECT employee_id, external_id, first_name, last_name,
    TRIM(COALESCE(first_name,'')||' '||COALESCE(last_name,'')) AS full_name,
    email, job_title, department, org_unit_id, entry_date, active, deleted,
    CASE WHEN deleted THEN 'DELETED' WHEN NOT active THEN 'INACTIVE' ELSE 'ACTIVE' END AS employment_status, updated_at
FROM ranked WHERE rn = 1
""")

create_table("hr_silver.org_unit", """
WITH ranked AS (
    SELECT orgunitid AS org_unit_id, externalid AS external_id, name,
        parentorgunitid AS parent_org_unit_id, CAST(level AS INTEGER) AS level,
        COALESCE(active, true) AS active, COALESCE(deleted, false) AS deleted, version,
        from_iso8601_timestamp(timestamp) AS updated_at,
        ROW_NUMBER() OVER (PARTITION BY orgunitid ORDER BY from_iso8601_timestamp(timestamp) DESC NULLS LAST, version DESC NULLS LAST) AS rn
    FROM iceberg.hr_raw.org_unit_events
    WHERE orgunitid IS NOT NULL AND name IS NOT NULL AND eventtype IN ('OrgUnitState','OrgUnitChanged')
)
SELECT org_unit_id, external_id, name, parent_org_unit_id, level, active, deleted, updated_at
FROM ranked WHERE rn = 1
""")

# ── Gold layer ───────────────────────────────────────────────────────────────

print("\n▶ Creating Gold tables…")

create_table("claims_gold.claim_detail", """
SELECT c.claim_id, c.claim_number, c.policy_id, p.policy_number, p.partner_id,
    pa.insured_number, pr.product_name, pr.product_line, c.description, c.claim_date,
    c.status, c.opened_at, c.updated_at
FROM iceberg.claims_silver.claim c
LEFT JOIN iceberg.policy_silver.policy p    ON c.policy_id  = p.policy_id
LEFT JOIN iceberg.partner_silver.partner pa ON p.partner_id = pa.partner_id
LEFT JOIN iceberg.product_silver.product pr ON p.product_id = pr.product_id
""")

create_table("policy_gold.policy_detail", """
SELECT p.policy_id, p.policy_number, p.policy_status, pa.partner_id, pa.insured_number,
    pa.partner_status, pr.product_name, pr.product_line, p.coverage_start_date,
    p.premium_chf, pr.base_premium_chf,
    ROUND((p.premium_chf - pr.base_premium_chf) / NULLIF(pr.base_premium_chf, 0) * 100, 1) AS premium_delta_pct,
    p.issued_at, p.updated_at
FROM iceberg.policy_silver.policy p
JOIN iceberg.partner_silver.partner pa ON p.partner_id = pa.partner_id
JOIN iceberg.product_silver.product pr ON p.product_id = pr.product_id
""")

create_table("policy_gold.portfolio_summary", """
SELECT pr.product_line, pr.product_name, COUNT(p.policy_id) AS active_policies,
    SUM(p.premium_chf) AS total_premium_chf, ROUND(AVG(p.premium_chf),2) AS avg_premium_chf,
    MIN(p.coverage_start_date) AS earliest_coverage_start, MAX(p.coverage_start_date) AS latest_coverage_start
FROM iceberg.policy_silver.policy p
JOIN iceberg.product_silver.product pr ON p.product_id = pr.product_id
WHERE p.policy_status = 'ACTIVE' AND NOT pr.is_deprecated
GROUP BY pr.product_line, pr.product_name
""")

create_table("billing_gold.financial_summary", """
SELECT p.policy_id, p.policy_number, p.partner_id, p.product_id, p.premium_chf AS annual_premium_chf,
    p.policy_status, p.issued_at, COUNT(i.invoice_id) AS total_invoices,
    COUNT(CASE WHEN i.invoice_status='OPEN' THEN 1 END) AS open_invoices,
    COUNT(CASE WHEN i.invoice_status='PAID' THEN 1 END) AS paid_invoices,
    COUNT(CASE WHEN i.invoice_status='OVERDUE' THEN 1 END) AS overdue_invoices,
    COALESCE(SUM(i.total_amount_chf), 0) AS total_billed_chf,
    COALESCE(SUM(CASE WHEN i.invoice_status='PAID' THEN i.total_amount_chf ELSE 0 END), 0) AS total_collected_chf,
    COALESCE(SUM(CASE WHEN i.invoice_status IN ('OPEN','OVERDUE') THEN i.total_amount_chf ELSE 0 END), 0) AS total_outstanding_chf,
    CASE WHEN COUNT(CASE WHEN i.invoice_status='OVERDUE' THEN 1 END) > 0 THEN 'AT_RISK'
         WHEN COUNT(CASE WHEN i.invoice_status='OPEN' THEN 1 END) > 0 THEN 'PENDING'
         ELSE 'CURRENT' END AS collection_status
FROM iceberg.policy_silver.policy p
LEFT JOIN iceberg.billing_silver.invoice i ON p.policy_id = i.policy_id
GROUP BY p.policy_id, p.policy_number, p.partner_id, p.product_id, p.premium_chf, p.policy_status, p.issued_at
""")

create_table("analytics.management_kpi", """
SELECT CURRENT_DATE AS report_date,
    COUNT(DISTINCT pol.partner_id) AS total_partners,
    COUNT(DISTINCT CASE WHEN pol.policy_status='ACTIVE' THEN pol.policy_id END) AS active_policies,
    SUM(CASE WHEN pol.policy_status='ACTIVE' THEN pol.premium_chf ELSE 0 END) AS total_portfolio_premium_chf,
    AVG(CASE WHEN pol.policy_status='ACTIVE' THEN pol.premium_chf END) AS avg_premium_chf,
    COUNT(DISTINCT pr.product_id) AS active_products,
    COUNT(DISTINCT CASE WHEN pol.coverage_start_date >= CURRENT_DATE - INTERVAL '30' DAY THEN pol.policy_id END) AS new_policies_last_30d,
    COUNT(DISTINCT CASE WHEN pol.policy_status='CANCELLED'
          AND pol.updated_at >= CAST(CURRENT_DATE - INTERVAL '30' DAY AS TIMESTAMP) THEN pol.policy_id END) AS cancelled_last_30d
FROM iceberg.policy_silver.policy pol
LEFT JOIN iceberg.partner_silver.partner pa ON pol.partner_id = pa.partner_id
LEFT JOIN iceberg.product_silver.product pr ON pol.product_id = pr.product_id
""")

create_table("analytics.org_hierarchy", """
SELECT ou.org_unit_id, ou.name AS org_unit_name, ou.level, parent.name AS parent_name,
    COUNT(emp.employee_id) AS employee_count,
    COUNT(CASE WHEN emp.employment_status = 'ACTIVE' THEN 1 END) AS active_employee_count
FROM iceberg.hr_silver.org_unit ou
LEFT JOIN iceberg.hr_silver.org_unit parent ON ou.parent_org_unit_id = parent.org_unit_id
LEFT JOIN iceberg.hr_silver.employee emp ON emp.org_unit_id = ou.org_unit_id AND NOT emp.deleted
WHERE NOT ou.deleted
GROUP BY ou.org_unit_id, ou.name, ou.level, parent.name
""")

print("\n✓ Transform init complete.")
