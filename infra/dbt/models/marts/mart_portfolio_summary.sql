-- Cross-domain mart: active policies joined with partner and product data.
-- This query is the Data Mesh "killer demo":
--   - dim_partner comes from the Partner domain's data product (person.v1.* events)
--   - dim_product  comes from the Product domain's data product (product.v1.* events)
--   - fact_policies comes from the Policy domain's data product (policy.v1.* events)
-- No direct database access to any domain DB. All data arrived via Kafka (Data Outside).
{{ config(materialized='table') }}

SELECT
    pr.product_line,
    pr.product_name,
    COUNT(f.policy_id)                          AS active_policies,
    SUM(f.premium_chf)                          AS total_premium_chf,
    ROUND(AVG(f.premium_chf), 2)               AS avg_premium_chf,
    MIN(f.coverage_start_date)                  AS earliest_coverage_start,
    MAX(f.coverage_start_date)                  AS latest_coverage_start
FROM {{ ref('fact_policies') }} f
JOIN {{ ref('dim_product') }}  pr ON f.product_id  = pr.product_id
WHERE f.policy_status = 'ACTIVE'
  AND NOT pr.is_deprecated
GROUP BY pr.product_line, pr.product_name
ORDER BY total_premium_chf DESC NULLS LAST
