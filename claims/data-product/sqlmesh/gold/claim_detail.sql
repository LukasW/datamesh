MODEL (
    name claims_gold.claim_detail,
    kind FULL,
    cron '@hourly',
    description 'Claims enriched with policy, partner insuredNumber, and product info.'
);

SELECT
    c.claim_id,
    c.claim_number,
    c.policy_id,
    p.policy_number,
    p.partner_id,
    pa.insured_number,
    pr.product_name,
    pr.product_line,
    c.description,
    c.claim_date,
    c.status,
    c.opened_at,
    c.updated_at
FROM claims_silver.claim c
LEFT JOIN policy_silver.policy p    ON c.policy_id = p.policy_id
LEFT JOIN partner_silver.partner pa ON p.partner_id = pa.partner_id
LEFT JOIN product_silver.product pr ON p.product_id = pr.product_id
