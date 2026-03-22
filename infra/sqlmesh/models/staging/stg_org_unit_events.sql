MODEL (
    name analytics.stg_org_unit_events,
    kind VIEW,
    description 'Staging model: parse raw HR organization unit JSON payload into typed columns.'
);

SELECT
    id                                                              AS surrogate_key,
    json_extract_scalar(payload, '$.orgUnitId')                     AS org_unit_id,
    json_extract_scalar(payload, '$.externalId')                    AS external_id,
    topic,
    json_extract_scalar(payload, '$.name')                          AS name,
    json_extract_scalar(payload, '$.parentOrgUnitId')               AS parent_org_unit_id,
    json_extract_scalar(payload, '$.managerEmployeeId')             AS manager_employee_id,
    CAST(json_extract_scalar(payload, '$.level') AS INTEGER)        AS level,
    CAST(json_extract_scalar(payload, '$.active') AS BOOLEAN)       AS active,
    CAST(json_extract_scalar(payload, '$.deleted') AS BOOLEAN)      AS deleted,
    CAST(json_extract_scalar(payload, '$.version') AS BIGINT)       AS version,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)  AS event_at,
    consumed_at
FROM iceberg.hr_raw.org_unit_events
WHERE json_extract_scalar(payload, '$.orgUnitId') IS NOT NULL
