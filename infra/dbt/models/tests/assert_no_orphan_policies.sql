-- Data quality test: every policy must reference an existing partner.
-- Fails (returns rows) when a policy.partnerId has no matching person in dim_partner.
-- This cross-domain test is owned by the Policy team and enforced by the platform.

SELECT
    f.policy_id,
    f.partner_id,
    f.policy_number
FROM {{ ref('fact_policies') }} f
LEFT JOIN {{ ref('dim_partner') }} dp ON f.partner_id = dp.person_id
WHERE dp.person_id IS NULL
