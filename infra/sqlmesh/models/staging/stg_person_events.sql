MODEL (
    name analytics.stg_person_events,
    kind VIEW,
    description 'Staging model: parse raw JSON payload into typed columns. One row per event.'
);

SELECT
    id                                                          AS surrogate_key,
    event_id,
    topic,
    event_type,
    person_id,
    json_extract_scalar(payload, '$.name')                      AS family_name,
    json_extract_scalar(payload, '$.firstName')                 AS first_name,
    json_extract_scalar(payload, '$.socialSecurityNumber')      AS social_security_number,
    CAST(json_extract_scalar(payload, '$.dateOfBirth') AS DATE) AS date_of_birth,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP) AS event_at,
    consumed_at
FROM iceberg.partner_raw.person_events
WHERE event_type IN ('PersonCreated', 'PersonUpdated')
  AND person_id IS NOT NULL
