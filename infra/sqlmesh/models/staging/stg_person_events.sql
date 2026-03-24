MODEL (
    name analytics.stg_person_events,
    kind VIEW,
    description 'Staging model: parse raw JSON payload into typed columns. One row per event. PII fields are decrypted via Vault Transit (ADR-009).'
);

SELECT
    id                                                          AS surrogate_key,
    event_id,
    topic,
    event_type,
    person_id,
    CASE WHEN json_extract_scalar(payload, '$.encrypted') = 'true'
         THEN vault_decrypt(person_id, json_extract_scalar(payload, '$.name'))
         ELSE json_extract_scalar(payload, '$.name')
    END                                                         AS family_name,
    CASE WHEN json_extract_scalar(payload, '$.encrypted') = 'true'
         THEN vault_decrypt(person_id, json_extract_scalar(payload, '$.firstName'))
         ELSE json_extract_scalar(payload, '$.firstName')
    END                                                         AS first_name,
    CASE WHEN json_extract_scalar(payload, '$.encrypted') = 'true'
         THEN vault_decrypt(person_id, json_extract_scalar(payload, '$.socialSecurityNumber'))
         ELSE json_extract_scalar(payload, '$.socialSecurityNumber')
    END                                                         AS social_security_number,
    -- insuredNumber is NOT PII – never encrypted (ADR-009)
    json_extract_scalar(payload, '$.insuredNumber')             AS insured_number,
    CASE WHEN json_extract_scalar(payload, '$.encrypted') = 'true'
         THEN CAST(vault_decrypt(person_id, json_extract_scalar(payload, '$.dateOfBirth')) AS DATE)
         ELSE CAST(json_extract_scalar(payload, '$.dateOfBirth') AS DATE)
    END                                                         AS date_of_birth,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP) AS event_at,
    consumed_at
FROM iceberg.partner_raw.person_events
WHERE event_type IN ('PersonCreated', 'PersonUpdated')
  AND person_id IS NOT NULL
