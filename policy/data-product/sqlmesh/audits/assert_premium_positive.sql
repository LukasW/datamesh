AUDIT (
    name assert_premium_positive
);

SELECT policy_id
FROM policy_silver.policy
WHERE policy_status = 'ACTIVE'
  AND (premium_chf IS NULL OR premium_chf <= 0)
