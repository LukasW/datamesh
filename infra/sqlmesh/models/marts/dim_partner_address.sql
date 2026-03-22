MODEL (
    name analytics.dim_partner_address,
    kind FULL,
    cron '@hourly',
    description 'Current RESIDENCE address per partner. Last-write-wins for address events.'
);

WITH ranked AS (
    SELECT
        person_id,
        postal_code,
        city,
        event_at,
        ROW_NUMBER() OVER (
            PARTITION BY person_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        ) AS rn
    FROM analytics.stg_address_events
    WHERE address_type = 'RESIDENCE'
)

SELECT
    person_id,
    postal_code,
    city
FROM ranked
WHERE rn = 1
