-- Staging model: parse raw policy event JSON into typed columns.
-- One row per event (deduplication happens in the mart).
{{ config(materialized='view') }}

SELECT
    id                                                              AS surrogate_key,
    event_id,
    topic,
    event_type,
    policy_id,
    partner_id,
    product_id,
    (payload::jsonb) ->> 'policyNumber'                            AS policy_number,
    ((payload::jsonb) ->> 'coverageStartDate')::date               AS coverage_start_date,
    ((payload::jsonb) ->> 'premium')::numeric                      AS premium_chf,
    ((payload::jsonb) ->> 'timestamp')::timestamptz                AS event_at,
    consumed_at
FROM {{ source('raw', 'policy_events') }}
WHERE event_type IN ('PolicyIssued', 'PolicyCancelled', 'PolicyChanged')
  AND policy_id IS NOT NULL
