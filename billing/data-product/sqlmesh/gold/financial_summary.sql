MODEL (
    name billing_gold.financial_summary,
    kind FULL,
    cron '@daily',
    description 'Invoice aggregation per policy for financial reporting.'
);

SELECT
    p.policy_id,
    p.policy_number,
    p.partner_id,
    p.product_id,
    p.premium_chf                                    AS annual_premium_chf,
    p.policy_status,
    p.issued_at,
    COUNT(i.invoice_id)                              AS total_invoices,
    COUNT(CASE WHEN i.invoice_status = 'OPEN' THEN 1 END)    AS open_invoices,
    COUNT(CASE WHEN i.invoice_status = 'PAID' THEN 1 END)    AS paid_invoices,
    COUNT(CASE WHEN i.invoice_status = 'OVERDUE' THEN 1 END) AS overdue_invoices,
    COALESCE(SUM(i.total_amount_chf), 0)             AS total_billed_chf,
    COALESCE(SUM(CASE WHEN i.invoice_status = 'PAID'
        THEN i.total_amount_chf ELSE 0 END), 0)     AS total_collected_chf,
    COALESCE(SUM(CASE WHEN i.invoice_status IN ('OPEN', 'OVERDUE')
        THEN i.total_amount_chf ELSE 0 END), 0)     AS total_outstanding_chf,
    CASE
        WHEN COUNT(CASE WHEN i.invoice_status = 'OVERDUE' THEN 1 END) > 0 THEN 'AT_RISK'
        WHEN COUNT(CASE WHEN i.invoice_status = 'OPEN' THEN 1 END) > 0    THEN 'PENDING'
        ELSE 'CURRENT'
    END                                              AS collection_status
FROM policy_silver.policy p
LEFT JOIN billing_silver.invoice i ON p.policy_id = i.policy_id
GROUP BY p.policy_id, p.policy_number, p.partner_id, p.product_id,
         p.premium_chf, p.policy_status, p.issued_at
