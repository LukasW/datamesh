MODEL (
    name analytics.stg_policy_events,
    kind VIEW,
    description 'Staging model: parse raw policy event JSON into typed columns. One row per event.'
);

SELECT
    id                                                                    AS surrogate_key,
    event_id,
    topic,
    event_type,
    policy_id,
    partner_id,
    product_id,
    json_extract_scalar(payload, '$.policyNumber')                        AS policy_number,
    CAST(json_extract_scalar(payload, '$.coverageStartDate') AS DATE)     AS coverage_start_date,
    CAST(json_extract_scalar(payload, '$.premium') AS DECIMAL(15, 2))     AS premium_chf,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)        AS event_at,
    consumed_at
FROM iceberg.policy_raw.policy_events
WHERE event_type IN ('PolicyIssued', 'PolicyCancelled', 'PolicyChanged')
  AND policy_id IS NOT NULL
