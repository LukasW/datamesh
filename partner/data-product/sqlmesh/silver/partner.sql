MODEL (
    name partner_silver.partner,
    kind INCREMENTAL_BY_UNIQUE_KEY (
        unique_key partner_id
    ),
    cron '@hourly',
    description 'Current state of every partner. Source: PersonState events (ECST). PII fields remain Vault-encrypted (ADR-009).'
);

WITH ranked AS (
    SELECT
        personid                              AS partner_id,
        name                                  AS family_name,
        firstname                             AS first_name,
        socialsecuritynumber                  AS social_security_number,
        insurednumber                         AS insured_number,
        dateofbirth                           AS date_of_birth,
        gender,
        encrypted,
        deleted,
        from_iso8601_timestamp(timestamp)          AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY personid
            ORDER BY from_iso8601_timestamp(timestamp) DESC
        )                                     AS rn
    FROM iceberg.partner_raw.person_events
    WHERE eventtype = 'PersonState'
      AND personid IS NOT NULL
      AND from_iso8601_timestamp(timestamp) BETWEEN @start_date AND @end_date
)

SELECT
    partner_id,
    family_name,
    first_name,
    social_security_number,
    insured_number,
    date_of_birth,
    gender,
    encrypted,
    COALESCE(deleted, false)                  AS deleted,
    CASE
        WHEN COALESCE(deleted, false) THEN 'DELETED'
        WHEN insured_number IS NOT NULL
             AND insured_number != '' THEN 'INSURED'
        ELSE 'PROSPECT'
    END                                       AS partner_status,
    updated_at
FROM ranked
WHERE rn = 1
