MODEL (
    name analytics.assert_no_orphan_employees,
    kind FULL,
    cron '@daily',
    description 'Test assertion: no employee references a non-existent org unit.'
);

SELECT
    emp.employee_id,
    emp.org_unit_id
FROM hr_silver.employee emp
LEFT JOIN hr_silver.org_unit ou
    ON emp.org_unit_id = ou.org_unit_id
WHERE emp.org_unit_id IS NOT NULL
  AND ou.org_unit_id IS NULL
  AND NOT emp.deleted
