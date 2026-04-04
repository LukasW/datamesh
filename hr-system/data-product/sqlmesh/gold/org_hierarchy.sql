MODEL (
    name analytics.org_hierarchy,
    kind FULL,
    cron '@daily',
    description 'Flattened organizational hierarchy with employee counts per unit.'
);

SELECT
    ou.org_unit_id,
    ou.name                                     AS org_unit_name,
    ou.level,
    parent.name                                 AS parent_name,
    COUNT(emp.employee_id)                      AS employee_count,
    COUNT(CASE WHEN emp.employment_status = 'ACTIVE' THEN 1 END) AS active_employee_count
FROM hr_silver.org_unit ou
LEFT JOIN hr_silver.org_unit parent
    ON ou.parent_org_unit_id = parent.org_unit_id
LEFT JOIN hr_silver.employee emp
    ON emp.org_unit_id = ou.org_unit_id
    AND NOT emp.deleted
WHERE NOT ou.deleted
GROUP BY
    ou.org_unit_id, ou.name, ou.level, parent.name
