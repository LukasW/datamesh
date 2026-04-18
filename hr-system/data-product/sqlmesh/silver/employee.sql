MODEL (
    name hr_silver.employee,
    kind INCREMENTAL_BY_UNIQUE_KEY (
        unique_key employee_id
    ),
    cron '@daily',
    description 'Current state of every employee from the HR system.'
);

WITH ranked AS (
    SELECT
        employeeid                            AS employee_id,
        externalid                            AS external_id,
        firstname                             AS first_name,
        lastname                              AS last_name,
        email,
        jobtitle                              AS job_title,
        department,
        orgunitid                             AS org_unit_id,
        CAST(entrydate AS DATE)               AS entry_date,
        COALESCE(active, true)                AS active,
        COALESCE(deleted, false)              AS deleted,
        version,
        from_iso8601_timestamp(timestamp)          AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY employeeid
            ORDER BY from_iso8601_timestamp(timestamp) DESC NULLS LAST,
                     version DESC NULLS LAST
        )                                     AS rn
    FROM iceberg.hr_raw.employee_events
    WHERE employeeid IS NOT NULL
      AND employeeid != ''
      AND (eventtype = 'employee.updated' OR eventtype IS NULL)
      AND from_iso8601_timestamp(timestamp) BETWEEN @start_date AND @end_date
)

SELECT
    employee_id,
    external_id,
    first_name,
    last_name,
    TRIM(COALESCE(first_name, '') || ' ' || COALESCE(last_name, '')) AS full_name,
    email,
    job_title,
    department,
    org_unit_id,
    entry_date,
    active,
    deleted,
    CASE
        WHEN deleted THEN 'DELETED'
        WHEN NOT active THEN 'INACTIVE'
        ELSE 'ACTIVE'
    END                                       AS employment_status,
    updated_at
FROM ranked
WHERE rn = 1
