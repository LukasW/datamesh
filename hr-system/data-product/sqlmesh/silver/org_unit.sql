MODEL (
    name hr_silver.org_unit,
    kind INCREMENTAL_BY_UNIQUE_KEY (
        unique_key org_unit_id
    ),
    cron '@daily',
    description 'Current state of every organizational unit from the HR system.'
);

WITH ranked AS (
    SELECT
        orgunitid                             AS org_unit_id,
        externalid                            AS external_id,
        name,
        parentorgunitid                       AS parent_org_unit_id,
        CAST(level AS INTEGER)                AS level,
        COALESCE(active, true)                AS active,
        COALESCE(deleted, false)              AS deleted,
        version,
        from_iso8601_timestamp(timestamp)          AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY orgunitid
            ORDER BY from_iso8601_timestamp(timestamp) DESC NULLS LAST,
                     version DESC NULLS LAST
        )                                     AS rn
    FROM iceberg.hr_raw.org_unit_events
    WHERE orgunitid IS NOT NULL
      AND name IS NOT NULL
      AND eventtype IN ('OrgUnitState', 'OrgUnitChanged')
      AND from_iso8601_timestamp(timestamp) BETWEEN @start_date AND @end_date
)

SELECT
    org_unit_id,
    external_id,
    name,
    parent_org_unit_id,
    level,
    active,
    deleted,
    updated_at
FROM ranked
WHERE rn = 1
