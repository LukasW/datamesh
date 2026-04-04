MODEL (
    name policy_gold.policy_detail,
    kind FULL,
    cron '@hourly',
    description 'Policies enriched with partner insuredNumber and product info.'
);

SELECT
    p.policy_id,
    p.policy_number,
    p.policy_status,
    pa.partner_id,
    pa.insured_number,
    pa.partner_status,
    pr.product_name,
    pr.product_line,
    p.coverage_start_date,
    p.premium_chf,
    pr.base_premium_chf,
    ROUND(
        (p.premium_chf - pr.base_premium_chf) / NULLIF(pr.base_premium_chf, 0) * 100, 1
    )                                         AS premium_delta_pct,
    p.issued_at,
    p.updated_at
FROM policy_silver.policy p
JOIN partner_silver.partner pa ON p.partner_id = pa.partner_id
JOIN product_silver.product pr ON p.product_id = pr.product_id
