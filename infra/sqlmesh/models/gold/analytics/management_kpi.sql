MODEL (
    name analytics.management_kpi,
    kind FULL,
    cron '@daily',
    description 'Cross-domain management KPI report.'
);

SELECT
    CURRENT_DATE                                                             AS report_date,
    COUNT(DISTINCT pol.partner_id)                                           AS total_partners,
    COUNT(DISTINCT CASE WHEN pol.policy_status = 'ACTIVE'
        THEN pol.policy_id END)                                              AS active_policies,
    SUM(CASE WHEN pol.policy_status = 'ACTIVE'
        THEN pol.premium_chf ELSE 0 END)                                     AS total_portfolio_premium_chf,
    AVG(CASE WHEN pol.policy_status = 'ACTIVE'
        THEN pol.premium_chf END)                                            AS avg_premium_chf,
    COUNT(DISTINCT pr.product_id)                                            AS active_products,
    COUNT(DISTINCT CASE WHEN pol.coverage_start_date >= CURRENT_DATE - INTERVAL '30' DAY
        THEN pol.policy_id END)                                              AS new_policies_last_30d,
    COUNT(DISTINCT CASE WHEN pol.policy_status = 'CANCELLED'
        AND pol.updated_at >= CAST(CURRENT_DATE - INTERVAL '30' DAY AS TIMESTAMP)
        THEN pol.policy_id END)                                              AS cancelled_last_30d
FROM policy_silver.policy pol
LEFT JOIN partner_silver.partner pa  ON pol.partner_id = pa.partner_id
LEFT JOIN product_silver.product pr  ON pol.product_id = pr.product_id
