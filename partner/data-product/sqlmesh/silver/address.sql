MODEL (
    name partner_silver.address,
    kind FULL,
    cron '@daily',
    description 'Current addresses per partner, extracted from PersonState.addresses array. PII fields remain Vault-encrypted.'
);

WITH latest_state AS (
    SELECT
        personid                              AS partner_id,
        encrypted,
        addresses,
        from_iso8601_timestamp(timestamp)          AS updated_at,
        ROW_NUMBER() OVER (
            PARTITION BY personid
            ORDER BY from_iso8601_timestamp(timestamp) DESC
        )                                     AS rn
    FROM iceberg.partner_raw.person_events
    WHERE eventtype = 'PersonState'
      AND personid IS NOT NULL
      AND addresses IS NOT NULL
)

SELECT
    a_address_id                              AS address_id,
    ls.partner_id,
    a_address_type                            AS address_type,
    a_street                                  AS street,
    a_house_number                            AS house_number,
    a_postal_code                             AS postal_code,
    a_city                                    AS city,
    a_land                                    AS country,
    CAST(a_valid_from AS DATE)                AS valid_from,
    ls.encrypted,
    ls.updated_at
FROM latest_state ls
CROSS JOIN UNNEST(ls.addresses) AS t(a_city, a_address_type, a_street, a_postal_code, a_house_number, a_land, a_valid_from, a_address_id)
WHERE ls.rn = 1
