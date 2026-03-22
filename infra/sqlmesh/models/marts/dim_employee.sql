MODEL (
    name analytics.dim_employee,
    kind FULL,
    cron '@hourly',
    description 'Latest known state of every employee from the HR system. Last-write-wins by event_at.'
);

WITH ranked AS (
    SELECT
        employee_id,
        external_id,
        first_name,
        last_name,
        TRIM(first_name || ' ' || last_name)  AS full_name,
        email,
        job_title,
        department,
        org_unit_id,
        entry_date,
        exit_date,
        active,
        deleted,
        event_at                               AS last_event_at,
        ROW_NUMBER() OVER (
            PARTITION BY employee_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        )                                      AS rn
    FROM analytics.stg_employee_events
)

SELECT
    employee_id,
    external_id,
    first_name,
    last_name,
    full_name,
    email,
    job_title,
    department,
    org_unit_id,
    entry_date,
    exit_date,
    active,
    deleted,
    CASE
        WHEN deleted THEN 'DELETED'
        WHEN NOT active THEN 'INACTIVE'
        WHEN exit_date IS NOT NULL AND exit_date <= CURRENT_DATE THEN 'EXITED'
        ELSE 'ACTIVE'
    END AS employment_status,
    last_event_at
FROM ranked
WHERE rn = 1
