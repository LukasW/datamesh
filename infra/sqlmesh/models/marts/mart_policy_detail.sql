MODEL (
    name analytics.mart_policy_detail,
    kind FULL,
    cron '@hourly',
    description 'Cross-domain detail: every policy enriched with partner name and product info.'
);

SELECT
    f.policy_id,
    f.policy_number,
    f.policy_status,
    dp.full_name                                AS partner_name,
    dp.person_id                                AS partner_id,
    pr.product_name,
    pr.product_line,
    f.coverage_start_date,
    f.premium_chf,
    pr.base_premium_chf,
    ROUND(
        (f.premium_chf - pr.base_premium_chf) / NULLIF(pr.base_premium_chf, 0) * 100, 1
    )                                           AS premium_delta_pct,
    f.first_issued_at,
    f.last_event_at
FROM analytics.fact_policies f
JOIN analytics.dim_partner dp ON f.partner_id = dp.person_id
JOIN analytics.dim_product pr ON f.product_id = pr.product_id
ORDER BY f.last_event_at DESC NULLS LAST
