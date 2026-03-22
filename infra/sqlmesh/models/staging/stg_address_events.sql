MODEL (
    name analytics.stg_address_events,
    kind VIEW,
    description 'Staging model: parse raw address-added events. One row per address event.'
);

SELECT
    id                                                                    AS surrogate_key,
    event_id,
    person_id,
    json_extract_scalar(payload, '$.addressId')                           AS address_id,
    json_extract_scalar(payload, '$.addressType')                         AS address_type,
    json_extract_scalar(payload, '$.street')                              AS street,
    json_extract_scalar(payload, '$.houseNumber')                         AS house_number,
    json_extract_scalar(payload, '$.postalCode')                          AS postal_code,
    json_extract_scalar(payload, '$.city')                                AS city,
    json_extract_scalar(payload, '$.land')                                AS country,
    CAST(json_extract_scalar(payload, '$.validFrom') AS DATE)             AS valid_from,
    CAST(json_extract_scalar(payload, '$.validTo') AS DATE)               AS valid_to,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)        AS event_at,
    consumed_at
FROM iceberg.partner_raw.person_events
WHERE event_type = 'AddressAdded'
  AND person_id IS NOT NULL
