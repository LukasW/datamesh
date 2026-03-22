MODEL (
    name analytics.dim_org_unit,
    kind FULL,
    cron '@hourly',
    description 'Latest known state of every organizational unit from the HR system.'
);

WITH ranked AS (
    SELECT
        org_unit_id,
        external_id,
        name,
        parent_org_unit_id,
        manager_employee_id,
        level,
        active,
        deleted,
        event_at                               AS last_event_at,
        ROW_NUMBER() OVER (
            PARTITION BY org_unit_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        )                                      AS rn
    FROM analytics.stg_org_unit_events
)

SELECT
    org_unit_id,
    external_id,
    name,
    parent_org_unit_id,
    manager_employee_id,
    level,
    active,
    deleted,
    last_event_at
FROM ranked
WHERE rn = 1
