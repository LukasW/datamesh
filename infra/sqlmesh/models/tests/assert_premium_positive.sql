AUDIT (
    name assert_premium_positive,
    description 'Validates that active policies have positive premiums.'
);

SELECT policy_id
FROM analytics.fact_policies
WHERE policy_status = 'ACTIVE'
  AND (premium_chf IS NULL OR premium_chf <= 0)
