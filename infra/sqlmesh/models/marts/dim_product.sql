MODEL (
    name analytics.dim_product,
    kind FULL,
    cron '@hourly',
    description 'Latest known state of every product. Deprecated flag from last event type.'
);

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
    FROM analytics.stg_product_events
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
