#!/bin/bash
# transform-init.sh – Creates Silver & Gold Iceberg tables via Trino CTAS.
# Runs after Debezium has committed raw data into Iceberg.
set -e

TRINO="http://trino:8086"

# ── Helpers ──────────────────────────────────────────────────────────────────

trino_exec() {
  local SQL="$1"
  local RESP NEXT STATE ERR DATA

  RESP=$(curl -sf -X POST "$TRINO/v1/statement" \
    -H "X-Trino-User: transform-init" \
    -d "$SQL")
  NEXT=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('nextUri',''))" 2>/dev/null)

  while [ -n "$NEXT" ] && [ "$NEXT" != "None" ] && [ "$NEXT" != "" ]; do
    sleep 0.5
    RESP=$(curl -sf "$NEXT")
    STATE=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('stats',{}).get('state',''))" 2>/dev/null)
    ERR=$(echo "$RESP" | python3 -c "import sys,json; e=json.load(sys.stdin).get('error',{}); print(e.get('message',''))" 2>/dev/null)
    NEXT=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('nextUri',''))" 2>/dev/null)

    if [ -n "$ERR" ] && [ "$ERR" != "" ] && [ "$ERR" != "None" ]; then
      echo "  ERROR: $ERR"
      return 1
    fi
  done
  return 0
}

trino_count() {
  local SQL="$1"
  local RESP NEXT DATA

  RESP=$(curl -sf -X POST "$TRINO/v1/statement" \
    -H "X-Trino-User: transform-init" \
    -d "$SQL")
  NEXT=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('nextUri',''))" 2>/dev/null)
  DATA=""

  while [ -n "$NEXT" ] && [ "$NEXT" != "None" ] && [ "$NEXT" != "" ]; do
    sleep 0.3
    RESP=$(curl -sf "$NEXT")
    local D=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin).get('data',[]); print(d[0][0] if d else '')" 2>/dev/null)
    if [ -n "$D" ] && [ "$D" != "" ] && [ "$D" != "None" ]; then DATA="$D"; fi
    NEXT=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('nextUri',''))" 2>/dev/null)
  done
  echo "${DATA:-0}"
}

# ── Wait for raw data ────────────────────────────────────────────────────────

echo "▶ Waiting for Iceberg raw tables to be populated…"
MAX_WAIT=180
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT ]; do
  POLICY_COUNT=$(trino_count "SELECT count(*) FROM iceberg.policy_raw.policy_events" 2>/dev/null || echo "0")
  PRODUCT_COUNT=$(trino_count "SELECT count(*) FROM iceberg.product_raw.product_events" 2>/dev/null || echo "0")
  if [ "${POLICY_COUNT:-0}" -gt 0 ] 2>/dev/null && [ "${PRODUCT_COUNT:-0}" -gt 0 ] 2>/dev/null; then
    echo "  ✓ Raw data available (policy=$POLICY_COUNT, product=$PRODUCT_COUNT)"
    break
  fi
  echo "  Waiting… (policy=$POLICY_COUNT, product=$PRODUCT_COUNT, ${ELAPSED}s/${MAX_WAIT}s)"
  sleep 10
  ELAPSED=$((ELAPSED + 10))
done

if [ $ELAPSED -ge $MAX_WAIT ]; then
  echo "  ⚠ Timeout waiting for raw data – proceeding anyway (schemas will be created)"
fi

# ── Create schemas ───────────────────────────────────────────────────────────

echo ""
echo "▶ Creating Silver & Gold schemas…"
for SCHEMA in partner_silver policy_silver claims_silver billing_silver product_silver \
              hr_silver partner_gold policy_gold claims_gold billing_gold analytics; do
  trino_exec "CREATE SCHEMA IF NOT EXISTS iceberg.${SCHEMA}" && echo "  ✓ $SCHEMA" || true
done

# ── Silver layer ─────────────────────────────────────────────────────────────

echo ""
echo "▶ Creating Silver tables…"

create_table() {
  local NAME="$1"
  local SQL="$2"
  echo -n "  $NAME: "
  trino_exec "DROP TABLE IF EXISTS iceberg.${NAME}" 2>/dev/null
  if trino_exec "CREATE TABLE iceberg.${NAME} AS ${SQL}"; then
    local CNT=$(trino_count "SELECT count(*) FROM iceberg.${NAME}")
    echo "${CNT} rows"
  else
    echo "FAILED"
  fi
}

# -- product_silver.product
create_table "product_silver.product" "
WITH ranked AS (
    SELECT
        productid AS product_id, name AS product_name, productline AS product_line,
        CAST(basepremium AS DECIMAL(15,2)) AS base_premium_chf, status,
        COALESCE(deleted, false) AS deleted,
        from_iso8601_timestamp(timestamp) AS updated_at,
        ROW_NUMBER() OVER (PARTITION BY productid ORDER BY from_iso8601_timestamp(timestamp) DESC) AS rn
    FROM iceberg.product_raw.product_events
    WHERE eventtype IN ('ProductState','ProductDefined','ProductDeprecated') AND productid IS NOT NULL
)
SELECT product_id, product_name, product_line, base_premium_chf, status, deleted,
       CASE WHEN deleted THEN true WHEN status='DEPRECATED' THEN true ELSE false END AS is_deprecated,
       updated_at
FROM ranked WHERE rn = 1"

# -- partner_silver.partner
create_table "partner_silver.partner" "
WITH ranked AS (
    SELECT
        personid AS partner_id, name AS family_name, firstname AS first_name,
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
            ELSE 'PROSPECT' END AS partner_status,
       updated_at
FROM ranked WHERE rn = 1"

# -- policy_silver.policy
create_table "policy_silver.policy" "
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
FROM ranked r LEFT JOIN first_issued fi ON r.policy_id = fi.policy_id WHERE r.rn = 1"

# -- policy_silver.coverage
create_table "policy_silver.coverage" "
SELECT coverageid AS coverage_id, policyid AS policy_id, coveragetype AS coverage_type,
       CAST(insuredamount AS DECIMAL(15,2)) AS insured_amount_chf,
       from_iso8601_timestamp(timestamp) AS created_at
FROM iceberg.policy_raw.policy_events
WHERE eventtype = 'CoverageAdded' AND coverageid IS NOT NULL"

# -- claims_silver.claim
create_table "claims_silver.claim" "
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
FROM ranked r LEFT JOIN first_opened fo ON r.claim_id = fo.claim_id WHERE r.rn = 1"

# -- billing_silver.invoice
create_table "billing_silver.invoice" "
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
FROM ranked r LEFT JOIN first_created fc ON r.invoice_id = fc.invoice_id WHERE r.rn = 1"

# -- hr_silver.employee
create_table "hr_silver.employee" "
WITH ranked AS (
    SELECT employeeid AS employee_id, externalid AS external_id, firstname AS first_name,
           lastname AS last_name, email, jobtitle AS job_title, department,
           orgunitid AS org_unit_id, CAST(entrydate AS DATE) AS entry_date,
           COALESCE(active, true) AS active, COALESCE(deleted, false) AS deleted, version,
           from_iso8601_timestamp(timestamp) AS updated_at,
           ROW_NUMBER() OVER (PARTITION BY employeeid ORDER BY from_iso8601_timestamp(timestamp) DESC NULLS LAST, version DESC NULLS LAST) AS rn
    FROM iceberg.hr_raw.employee_events
    WHERE employeeid IS NOT NULL AND employeeid != ''
      AND (eventtype = 'employee.updated' OR eventtype IS NULL)
)
SELECT employee_id, external_id, first_name, last_name,
       TRIM(COALESCE(first_name,'')||' '||COALESCE(last_name,'')) AS full_name,
       email, job_title, department, org_unit_id, entry_date, active, deleted,
       CASE WHEN deleted THEN 'DELETED' WHEN NOT active THEN 'INACTIVE' ELSE 'ACTIVE' END AS employment_status,
       updated_at
FROM ranked WHERE rn = 1"

# -- hr_silver.org_unit
create_table "hr_silver.org_unit" "
WITH ranked AS (
    SELECT orgunitid AS org_unit_id, externalid AS external_id, name,
           parentorgunitid AS parent_org_unit_id, CAST(level AS INTEGER) AS level,
           COALESCE(active, true) AS active, COALESCE(deleted, false) AS deleted, version,
           from_iso8601_timestamp(timestamp) AS updated_at,
           ROW_NUMBER() OVER (PARTITION BY orgunitid ORDER BY from_iso8601_timestamp(timestamp) DESC NULLS LAST, version DESC NULLS LAST) AS rn
    FROM iceberg.hr_raw.org_unit_events
    WHERE orgunitid IS NOT NULL AND name IS NOT NULL
      AND (eventtype = 'org-unit.updated' OR eventtype IS NULL)
)
SELECT org_unit_id, external_id, name, parent_org_unit_id, level, active, deleted, updated_at
FROM ranked WHERE rn = 1"

# ── Gold layer ───────────────────────────────────────────────────────────────

echo ""
echo "▶ Creating Gold tables…"

# -- claims_gold.claim_detail
create_table "claims_gold.claim_detail" "
SELECT c.claim_id, c.claim_number, c.policy_id, p.policy_number, p.partner_id,
       pa.insured_number, pr.product_name, pr.product_line, c.description, c.claim_date,
       c.status, c.opened_at, c.updated_at
FROM iceberg.claims_silver.claim c
LEFT JOIN iceberg.policy_silver.policy p    ON c.policy_id  = p.policy_id
LEFT JOIN iceberg.partner_silver.partner pa ON p.partner_id = pa.partner_id
LEFT JOIN iceberg.product_silver.product pr ON p.product_id = pr.product_id"

# -- policy_gold.policy_detail
create_table "policy_gold.policy_detail" "
SELECT p.policy_id, p.policy_number, p.policy_status, pa.partner_id, pa.insured_number,
       pa.partner_status, pr.product_name, pr.product_line, p.coverage_start_date,
       p.premium_chf, pr.base_premium_chf,
       ROUND((p.premium_chf - pr.base_premium_chf) / NULLIF(pr.base_premium_chf, 0) * 100, 1) AS premium_delta_pct,
       p.issued_at, p.updated_at
FROM iceberg.policy_silver.policy p
JOIN iceberg.partner_silver.partner pa ON p.partner_id = pa.partner_id
JOIN iceberg.product_silver.product pr ON p.product_id = pr.product_id"

# -- policy_gold.portfolio_summary
create_table "policy_gold.portfolio_summary" "
SELECT pr.product_line, pr.product_name, COUNT(p.policy_id) AS active_policies,
       SUM(p.premium_chf) AS total_premium_chf, ROUND(AVG(p.premium_chf),2) AS avg_premium_chf,
       MIN(p.coverage_start_date) AS earliest_coverage_start,
       MAX(p.coverage_start_date) AS latest_coverage_start
FROM iceberg.policy_silver.policy p
JOIN iceberg.product_silver.product pr ON p.product_id = pr.product_id
WHERE p.policy_status = 'ACTIVE' AND NOT pr.is_deprecated
GROUP BY pr.product_line, pr.product_name"

# -- billing_gold.financial_summary
create_table "billing_gold.financial_summary" "
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
GROUP BY p.policy_id, p.policy_number, p.partner_id, p.product_id, p.premium_chf, p.policy_status, p.issued_at"

# -- analytics.management_kpi
create_table "analytics.management_kpi" "
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
LEFT JOIN iceberg.product_silver.product pr ON pol.product_id = pr.product_id"

# -- analytics.org_hierarchy
create_table "analytics.org_hierarchy" "
SELECT ou.org_unit_id, ou.name AS org_unit_name, ou.level, parent.name AS parent_name,
       COUNT(emp.employee_id) AS employee_count,
       COUNT(CASE WHEN emp.employment_status = 'ACTIVE' THEN 1 END) AS active_employee_count
FROM iceberg.hr_silver.org_unit ou
LEFT JOIN iceberg.hr_silver.org_unit parent ON ou.parent_org_unit_id = parent.org_unit_id
LEFT JOIN iceberg.hr_silver.employee emp ON emp.org_unit_id = ou.org_unit_id AND NOT emp.deleted
WHERE NOT ou.deleted
GROUP BY ou.org_unit_id, ou.name, ou.level, parent.name"

echo ""
echo "✓ Transform init complete."
