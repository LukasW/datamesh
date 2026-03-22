MODEL (
    name analytics.assert_no_orphan_employees,
    kind VIEW,
    description 'Test assertion: no employee references a non-existent org unit.'
);

SELECT
    emp.employee_id,
    emp.org_unit_id
FROM analytics.dim_employee emp
LEFT JOIN analytics.dim_org_unit ou
    ON emp.org_unit_id = ou.org_unit_id
WHERE emp.org_unit_id IS NOT NULL
  AND ou.org_unit_id IS NULL
  AND NOT emp.deleted
