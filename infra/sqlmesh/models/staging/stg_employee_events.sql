MODEL (
    name analytics.stg_employee_events,
    kind VIEW,
    description 'Staging model: parse raw HR employee JSON payload into typed columns. One row per event.'
);

SELECT
    id                                                              AS surrogate_key,
    json_extract_scalar(payload, '$.employeeId')                    AS employee_id,
    json_extract_scalar(payload, '$.externalId')                    AS external_id,
    topic,
    json_extract_scalar(payload, '$.firstName')                     AS first_name,
    json_extract_scalar(payload, '$.lastName')                      AS last_name,
    json_extract_scalar(payload, '$.email')                         AS email,
    json_extract_scalar(payload, '$.jobTitle')                      AS job_title,
    json_extract_scalar(payload, '$.department')                    AS department,
    json_extract_scalar(payload, '$.orgUnitId')                     AS org_unit_id,
    CAST(json_extract_scalar(payload, '$.entryDate') AS DATE)       AS entry_date,
    CAST(json_extract_scalar(payload, '$.exitDate') AS DATE)        AS exit_date,
    CAST(json_extract_scalar(payload, '$.active') AS BOOLEAN)       AS active,
    CAST(json_extract_scalar(payload, '$.deleted') AS BOOLEAN)      AS deleted,
    CAST(json_extract_scalar(payload, '$.version') AS BIGINT)       AS version,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)  AS event_at,
    consumed_at
FROM iceberg.hr_raw.employee_events
WHERE json_extract_scalar(payload, '$.employeeId') IS NOT NULL
