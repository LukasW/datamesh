-- Staging model: parse raw product event JSON into typed columns.
-- One row per event (deduplication happens in the mart).
{{ config(materialized='view') }}

SELECT
    id                                                          AS surrogate_key,
    event_id,
    topic,
    event_type,
    product_id,
    (payload::jsonb) ->> 'name'                                AS product_name,
    (payload::jsonb) ->> 'productLine'                         AS product_line,
    ((payload::jsonb) ->> 'basePremium')::numeric              AS base_premium_chf,
    ((payload::jsonb) ->> 'timestamp')::timestamptz            AS event_at,
    consumed_at
FROM {{ source('product_raw', 'product_events') }}
WHERE event_type IN ('ProductDefined', 'ProductUpdated', 'ProductDeprecated')
  AND product_id IS NOT NULL
