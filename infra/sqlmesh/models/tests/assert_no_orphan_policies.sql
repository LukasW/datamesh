AUDIT (
    name assert_no_orphan_policies,
    description 'Validates referential integrity: every policy must have a matching partner.'
);

SELECT f.policy_id
FROM analytics.fact_policies f
LEFT JOIN analytics.dim_partner dp ON f.partner_id = dp.person_id
WHERE dp.person_id IS NULL
