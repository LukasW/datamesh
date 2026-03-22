MODEL (
    name analytics.stg_product_events,
    kind VIEW,
    description 'Staging model: parse raw product event JSON into typed columns. One row per event.'
);

SELECT
    id                                                                    AS surrogate_key,
    event_id,
    topic,
    event_type,
    product_id,
    json_extract_scalar(payload, '$.name')                                AS product_name,
    json_extract_scalar(payload, '$.productLine')                         AS product_line,
    CAST(json_extract_scalar(payload, '$.basePremium') AS DECIMAL(15, 2)) AS base_premium_chf,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)        AS event_at,
    consumed_at
FROM iceberg.product_raw.product_events
WHERE event_type IN ('ProductDefined', 'ProductUpdated', 'ProductDeprecated')
  AND product_id IS NOT NULL
