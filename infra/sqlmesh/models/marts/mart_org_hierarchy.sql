MODEL (
    name analytics.mart_org_hierarchy,
    kind FULL,
    cron '@hourly',
    description 'Flattened organizational hierarchy with employee counts per unit.'
);

SELECT
    ou.org_unit_id,
    ou.name                                     AS org_unit_name,
    ou.level,
    parent.name                                 AS parent_name,
    mgr.full_name                               AS manager_name,
    COUNT(emp.employee_id)                      AS employee_count,
    COUNT(CASE WHEN emp.employment_status = 'ACTIVE' THEN 1 END)  AS active_employee_count
FROM analytics.dim_org_unit ou
LEFT JOIN analytics.dim_org_unit parent
    ON ou.parent_org_unit_id = parent.org_unit_id
LEFT JOIN analytics.dim_employee mgr
    ON ou.manager_employee_id = mgr.employee_id
LEFT JOIN analytics.dim_employee emp
    ON emp.org_unit_id = ou.org_unit_id
    AND NOT emp.deleted
WHERE NOT ou.deleted
GROUP BY
    ou.org_unit_id, ou.name, ou.level,
    parent.name, mgr.full_name
