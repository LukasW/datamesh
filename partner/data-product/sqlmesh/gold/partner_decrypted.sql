MODEL (
    name partner_gold.partner_decrypted,
    kind FULL,
    cron '@hourly',
    description 'Partner with decrypted PII fields via Vault Transit.'
);

SELECT
    partner_id,
    CASE WHEN encrypted
         THEN vault_decrypt(partner_id, family_name)
         ELSE family_name
    END                                       AS family_name,
    CASE WHEN encrypted
         THEN vault_decrypt(partner_id, first_name)
         ELSE first_name
    END                                       AS first_name,
    CASE WHEN encrypted
         THEN TRIM(
             COALESCE(vault_decrypt(partner_id, first_name), '') || ' ' ||
             COALESCE(vault_decrypt(partner_id, family_name), '')
         )
         ELSE TRIM(COALESCE(first_name, '') || ' ' || COALESCE(family_name, ''))
    END                                       AS full_name,
    CASE WHEN encrypted
         THEN vault_decrypt(partner_id, social_security_number)
         ELSE social_security_number
    END                                       AS social_security_number,
    insured_number,
    CASE WHEN encrypted
         THEN CAST(vault_decrypt(partner_id, date_of_birth) AS DATE)
         ELSE CAST(date_of_birth AS DATE)
    END                                       AS date_of_birth,
    gender,
    partner_status,
    updated_at
FROM partner_silver.partner
WHERE NOT deleted
