-- Staging model: parse raw JSON payload into typed columns.
-- One row per event (no deduplication here – that happens in the mart).
{{ config(materialized='view') }}

SELECT
    id                                                          AS surrogate_key,
    event_id,
    topic,
    event_type,
    person_id,
    (payload::jsonb) ->> 'name'                                AS family_name,
    (payload::jsonb) ->> 'firstName'                           AS first_name,
    (payload::jsonb) ->> 'socialSecurityNumber'                AS social_security_number,
    ((payload::jsonb) ->> 'dateOfBirth')::date                 AS date_of_birth,
    ((payload::jsonb) ->> 'timestamp')::timestamptz            AS event_at,
    consumed_at
FROM {{ source('partner_raw', 'person_events') }}
WHERE event_type IN ('PersonCreated', 'PersonUpdated')
  AND person_id IS NOT NULL
