AUDIT (
    name assert_no_orphan_policies
);

SELECT p.policy_id
FROM policy_silver.policy p
LEFT JOIN partner_silver.partner pa ON p.partner_id = pa.partner_id
WHERE pa.partner_id IS NULL
