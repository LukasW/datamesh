-- Mart model: latest known state of every product.
-- A product is marked deprecated when its last event is ProductDeprecated.
{{ config(materialized='table') }}

WITH ranked AS (
    SELECT
        product_id,
        product_name,
        product_line,
        base_premium_chf,
        event_type,
        event_at                                AS last_event_at,
        ROW_NUMBER() OVER (
            PARTITION BY product_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        )                                       AS rn
    FROM {{ ref('stg_product_events') }}
)

SELECT
    product_id,
    product_name,
    product_line,
    base_premium_chf,
    (event_type = 'ProductDeprecated')          AS is_deprecated,
    last_event_at
FROM ranked
WHERE rn = 1
