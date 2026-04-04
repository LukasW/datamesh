MODEL (
    name product_silver.product,
    kind INCREMENTAL_BY_UNIQUE_KEY (
        unique_key product_id
    ),
    cron '@daily',
    description 'Current state of every product. Source: ProductState events (ECST).'
);

WITH ranked AS (
    SELECT
        productid                             AS product_id,
        name                                  AS product_name,
        productline                           AS product_line,
        CAST(basepremium AS DECIMAL(15, 2))   AS base_premium_chf,
        status,
        COALESCE(deleted, false)              AS deleted,
        from_iso8601_timestamp(timestamp)      AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY productid
            ORDER BY from_iso8601_timestamp(timestamp) DESC
        )                                     AS rn
    FROM iceberg.product_raw.product_events
    WHERE eventtype IN ('ProductState', 'ProductDefined', 'ProductDeprecated')
      AND productid IS NOT NULL
      AND from_iso8601_timestamp(timestamp) BETWEEN @start_date AND @end_date
)

SELECT
    product_id,
    product_name,
    product_line,
    base_premium_chf,
    status,
    deleted,
    CASE
        WHEN deleted THEN true
        WHEN status = 'DEPRECATED' THEN true
        ELSE false
    END                                       AS is_deprecated,
    updated_at
FROM ranked
WHERE rn = 1
