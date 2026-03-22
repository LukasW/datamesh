MODEL (
    name analytics.dim_partner,
    kind FULL,
    cron '@hourly',
    description 'Latest known state of every partner (person). Last-write-wins by event_at.'
);

WITH ranked AS (
    SELECT
        person_id,
        family_name,
        first_name,
        TRIM(first_name || ' ' || family_name)  AS full_name,
        social_security_number,
        insured_number,
        date_of_birth,
        event_at                                AS last_event_at,
        ROW_NUMBER() OVER (
            PARTITION BY person_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        )                                       AS rn
    FROM analytics.stg_person_events
)

SELECT
    person_id,
    family_name,
    first_name,
    full_name,
    social_security_number,
    insured_number,
    CASE WHEN insured_number IS NOT NULL THEN 'INSURED' ELSE 'PROSPECT' END AS insurance_status,
    date_of_birth,
    last_event_at
FROM ranked
WHERE rn = 1
