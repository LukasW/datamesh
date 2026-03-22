MODEL (
    name analytics.mart_financial_summary,
    kind FULL,
    cron '@daily',
    description 'Cross-domain financial summary joining Policy and Billing data products.'
);

WITH billing AS (
    SELECT
        policy_id,
        COUNT(*)                                                              AS total_invoices,
        COUNT(CASE WHEN invoice_status = 'OPEN' THEN 1 END)                  AS open_invoices,
        COUNT(CASE WHEN invoice_status = 'PAID' THEN 1 END)                  AS paid_invoices,
        COUNT(CASE WHEN invoice_status = 'OVERDUE' THEN 1 END)               AS overdue_invoices,
        COUNT(CASE WHEN invoice_status = 'CANCELLED' THEN 1 END)             AS cancelled_invoices,
        SUM(total_amount_chf)                                                 AS total_billed_chf,
        SUM(CASE WHEN invoice_status = 'PAID' THEN total_amount_chf ELSE 0 END) AS total_collected_chf,
        SUM(CASE WHEN invoice_status IN ('OPEN', 'OVERDUE') THEN total_amount_chf ELSE 0 END) AS total_outstanding_chf
    FROM analytics.fact_invoices
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
    FROM analytics.fact_policies
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
