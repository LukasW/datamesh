MODEL (
    name analytics.mart_portfolio_summary,
    kind FULL,
    cron '@hourly',
    description 'Cross-domain: active policies grouped by product. Data Mesh killer demo.'
);

SELECT
    pr.product_line,
    pr.product_name,
    COUNT(f.policy_id)                          AS active_policies,
    SUM(f.premium_chf)                          AS total_premium_chf,
    ROUND(AVG(f.premium_chf), 2)               AS avg_premium_chf,
    MIN(f.coverage_start_date)                  AS earliest_coverage_start,
    MAX(f.coverage_start_date)                  AS latest_coverage_start
FROM analytics.fact_policies f
JOIN analytics.dim_product pr ON f.product_id = pr.product_id
WHERE f.policy_status = 'ACTIVE'
  AND NOT pr.is_deprecated
GROUP BY pr.product_line, pr.product_name
ORDER BY total_premium_chf DESC NULLS LAST
