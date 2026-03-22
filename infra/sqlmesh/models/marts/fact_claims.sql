MODEL (
    name analytics.fact_claims,
    kind FULL,
    cron '@hourly',
    description 'One row per claim with current status, settlement amount, and partner city.'
);

WITH latest_event AS (
    SELECT
        claim_id,
        claim_number,
        policy_id,
        description,
        claim_date,
        status,
        settlement_amount_chf,
        event_at,
        ROW_NUMBER() OVER (
            PARTITION BY claim_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        ) AS rn
    FROM analytics.stg_claims_events
)

SELECT
    le.claim_id,
    le.claim_number,
    le.policy_id,
    fp.partner_id,
    le.description,
    le.claim_date,
    le.status,
    le.settlement_amount_chf,
    pa.postal_code,
    pa.city,
    le.event_at                  AS last_event_at
FROM latest_event le
LEFT JOIN analytics.fact_policies  fp ON le.policy_id = fp.policy_id
LEFT JOIN analytics.dim_partner_address pa ON fp.partner_id = pa.person_id
WHERE le.rn = 1
