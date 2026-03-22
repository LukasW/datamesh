MODEL (
    name analytics.fact_policies,
    kind FULL,
    cron '@hourly',
    description 'One row per policy with current status derived from events.'
);

WITH latest_event AS (
    SELECT
        policy_id,
        policy_number,
        partner_id,
        product_id,
        coverage_start_date,
        premium_chf,
        event_type,
        event_at,
        ROW_NUMBER() OVER (
            PARTITION BY policy_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        ) AS rn
    FROM analytics.stg_policy_events
),

first_issued AS (
    SELECT
        policy_id,
        MIN(event_at) AS first_issued_at
    FROM analytics.stg_policy_events
    WHERE event_type = 'PolicyIssued'
    GROUP BY policy_id
)

SELECT
    le.policy_id,
    le.policy_number,
    le.partner_id,
    le.product_id,
    le.coverage_start_date,
    le.premium_chf,
    CASE le.event_type
        WHEN 'PolicyCancelled' THEN 'CANCELLED'
        ELSE 'ACTIVE'
    END                                         AS policy_status,
    fi.first_issued_at,
    le.event_at                                 AS last_event_at
FROM latest_event le
LEFT JOIN first_issued fi ON le.policy_id = fi.policy_id
WHERE le.rn = 1
