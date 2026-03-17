-- Data quality test: all active policy premiums must be positive.
-- Fails (returns rows) when premium_chf <= 0 for an ACTIVE policy.

SELECT
    policy_id,
    policy_number,
    premium_chf
FROM {{ ref('fact_policies') }}
WHERE policy_status = 'ACTIVE'
  AND (premium_chf IS NULL OR premium_chf <= 0)
