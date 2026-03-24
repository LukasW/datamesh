MODEL (
    name analytics.stg_address_events,
    kind VIEW,
    description 'Staging model: parse raw address-added events. One row per address event. PII fields are decrypted via Vault Transit (ADR-009).'
);

SELECT
    id                                                                    AS surrogate_key,
    event_id,
    person_id,
    json_extract_scalar(payload, '$.addressId')                           AS address_id,
    json_extract_scalar(payload, '$.addressType')                         AS address_type,
    CASE WHEN json_extract_scalar(payload, '$.encrypted') = 'true'
         THEN vault_decrypt(person_id, json_extract_scalar(payload, '$.street'))
         ELSE json_extract_scalar(payload, '$.street')
    END                                                                   AS street,
    CASE WHEN json_extract_scalar(payload, '$.encrypted') = 'true'
         THEN vault_decrypt(person_id, json_extract_scalar(payload, '$.houseNumber'))
         ELSE json_extract_scalar(payload, '$.houseNumber')
    END                                                                   AS house_number,
    CASE WHEN json_extract_scalar(payload, '$.encrypted') = 'true'
         THEN vault_decrypt(person_id, json_extract_scalar(payload, '$.postalCode'))
         ELSE json_extract_scalar(payload, '$.postalCode')
    END                                                                   AS postal_code,
    CASE WHEN json_extract_scalar(payload, '$.encrypted') = 'true'
         THEN vault_decrypt(person_id, json_extract_scalar(payload, '$.city'))
         ELSE json_extract_scalar(payload, '$.city')
    END                                                                   AS city,
    CASE WHEN json_extract_scalar(payload, '$.encrypted') = 'true'
         THEN vault_decrypt(person_id, json_extract_scalar(payload, '$.land'))
         ELSE json_extract_scalar(payload, '$.land')
    END                                                                   AS country,
    CAST(json_extract_scalar(payload, '$.validFrom') AS DATE)             AS valid_from,
    CAST(json_extract_scalar(payload, '$.validTo') AS DATE)               AS valid_to,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)        AS event_at,
    consumed_at
FROM iceberg.partner_raw.person_events
WHERE event_type = 'AddressAdded'
  AND person_id IS NOT NULL
