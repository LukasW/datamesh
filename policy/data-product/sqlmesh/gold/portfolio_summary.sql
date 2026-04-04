MODEL (
    name policy_gold.portfolio_summary,
    kind FULL,
    cron '@hourly',
    description 'Active policies grouped by product line and product.'
);

SELECT
    pr.product_line,
    pr.product_name,
    COUNT(p.policy_id)                        AS active_policies,
    SUM(p.premium_chf)                        AS total_premium_chf,
    ROUND(AVG(p.premium_chf), 2)              AS avg_premium_chf,
    MIN(p.coverage_start_date)                AS earliest_coverage_start,
    MAX(p.coverage_start_date)                AS latest_coverage_start
FROM policy_silver.policy p
JOIN product_silver.product pr ON p.product_id = pr.product_id
WHERE p.policy_status = 'ACTIVE'
  AND NOT pr.is_deprecated
GROUP BY pr.product_line, pr.product_name
