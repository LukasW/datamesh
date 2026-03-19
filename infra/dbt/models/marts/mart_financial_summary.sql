-- Mart model: cross-domain financial summary (Policy + Billing data products).
-- Demonstrates Data Mesh: joins data from two domains via Kafka-ingested events only.
-- No direct DB access to policy_db or billing_db.
{{ config(materialized='table', tags=['mgmt-report']) }}

WITH billing AS (
    SELECT
        policy_id,
        COUNT(*)                                                AS total_invoices,
        COUNT(*) FILTER (WHERE invoice_status = 'OPEN')        AS open_invoices,
        COUNT(*) FILTER (WHERE invoice_status = 'PAID')        AS paid_invoices,
        COUNT(*) FILTER (WHERE invoice_status = 'OVERDUE')     AS overdue_invoices,
        COUNT(*) FILTER (WHERE invoice_status = 'CANCELLED')   AS cancelled_invoices,
        SUM(total_amount_chf)                                  AS total_billed_chf,
        SUM(total_amount_chf) FILTER (WHERE invoice_status = 'PAID')    AS total_collected_chf,
        SUM(total_amount_chf) FILTER (WHERE invoice_status IN ('OPEN', 'OVERDUE')) AS total_outstanding_chf
    FROM {{ ref('fact_invoices') }}
    GROUP BY policy_id
),

policies AS (
    SELECT
        policy_id,
        policy_number,
        partner_id,
        product_id,
        premium_chf,
        policy_status,
        first_issued_at
    FROM {{ ref('fact_policies') }}
)

SELECT
    p.policy_id,
    p.policy_number,
    p.partner_id,
    p.product_id,
    p.premium_chf                                    AS annual_premium_chf,
    p.policy_status,
    p.first_issued_at,
    COALESCE(b.total_invoices, 0)                    AS total_invoices,
    COALESCE(b.open_invoices, 0)                     AS open_invoices,
    COALESCE(b.paid_invoices, 0)                     AS paid_invoices,
    COALESCE(b.overdue_invoices, 0)                  AS overdue_invoices,
    COALESCE(b.total_billed_chf, 0)                  AS total_billed_chf,
    COALESCE(b.total_collected_chf, 0)               AS total_collected_chf,
    COALESCE(b.total_outstanding_chf, 0)             AS total_outstanding_chf,
    CASE
        WHEN COALESCE(b.overdue_invoices, 0) > 0 THEN 'AT_RISK'
        WHEN COALESCE(b.open_invoices, 0) > 0    THEN 'PENDING'
        ELSE                                          'CURRENT'
    END                                              AS collection_status
FROM policies p
LEFT JOIN billing b ON p.policy_id = b.policy_id
