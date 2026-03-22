MODEL (
    name analytics.stg_claims_events,
    kind VIEW,
    description 'Staging model: parse raw claims event JSON into typed columns. One row per event.'
);

SELECT
    id                                                                          AS surrogate_key,
    event_id,
    topic,
    event_type,
    json_extract_scalar(payload, '$.claimId')                                   AS claim_id,
    json_extract_scalar(payload, '$.policyId')                                  AS policy_id,
    json_extract_scalar(payload, '$.claimNumber')                               AS claim_number,
    json_extract_scalar(payload, '$.description')                               AS description,
    CAST(json_extract_scalar(payload, '$.claimDate') AS DATE)                   AS claim_date,
    json_extract_scalar(payload, '$.status')                                    AS status,
    CAST(json_extract_scalar(payload, '$.settlementAmount') AS DECIMAL(15, 2))  AS settlement_amount_chf,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)              AS event_at,
    consumed_at
FROM iceberg.claims_raw.claims_events
WHERE event_type IN ('ClaimOpened', 'ClaimSettled')
  AND json_extract_scalar(payload, '$.claimId') IS NOT NULL
