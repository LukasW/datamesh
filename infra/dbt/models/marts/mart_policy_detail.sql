-- Cross-domain detail mart: every policy enriched with partner name and product info.
-- Demonstrates column-level lineage across three domain data products.
{{ config(materialized='table') }}

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
FROM {{ ref('fact_policies') }}  f
JOIN {{ ref('dim_partner') }}    dp ON f.partner_id = dp.person_id
JOIN {{ ref('dim_product') }}    pr ON f.product_id = pr.product_id
ORDER BY f.last_event_at DESC NULLS LAST
