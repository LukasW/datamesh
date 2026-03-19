{{ config(materialized='table', tags=['mgmt-report']) }}

SELECT
    CURRENT_DATE                                                             AS report_date,
    COUNT(DISTINCT p.partner_id)                                            AS total_partners,
    COUNT(DISTINCT pol.policy_id)
        FILTER (WHERE pol.status = 'ACTIVE')                                AS active_policies,
    SUM(pol.annual_premium_chf)
        FILTER (WHERE pol.status = 'ACTIVE')                                AS total_portfolio_premium_chf,
    AVG(pol.annual_premium_chf)
        FILTER (WHERE pol.status = 'ACTIVE')                                AS avg_premium_chf,
    COUNT(DISTINCT prod.product_id)                                         AS active_products,
    COUNT(DISTINCT pol.policy_id)
        FILTER (WHERE pol.inception_date >= CURRENT_DATE - 30)              AS new_policies_last_30d,
    COUNT(DISTINCT pol.policy_id)
        FILTER (WHERE pol.cancellation_date >= CURRENT_DATE - 30)           AS cancelled_last_30d
FROM {{ ref('fact_policies') }} pol
LEFT JOIN {{ ref('dim_partner') }} p    ON pol.partner_id  = p.partner_id
LEFT JOIN {{ ref('dim_product') }}  prod ON pol.product_id = prod.product_id
