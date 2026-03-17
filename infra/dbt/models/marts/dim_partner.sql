-- Mart model: latest known state of every partner (person).
-- Applies last-write-wins by event_at, then consumed_at as tiebreaker.
{{ config(materialized='table') }}

WITH ranked AS (
    SELECT
        person_id,
        family_name,
        first_name,
        TRIM(first_name || ' ' || family_name)  AS full_name,
        social_security_number,
        date_of_birth,
        event_at                                AS last_event_at,
        ROW_NUMBER() OVER (
            PARTITION BY person_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        )                                       AS rn
    FROM {{ ref('stg_person_events') }}
)

SELECT
    person_id,
    family_name,
    first_name,
    full_name,
    social_security_number,
    date_of_birth,
    last_event_at
FROM ranked
WHERE rn = 1
