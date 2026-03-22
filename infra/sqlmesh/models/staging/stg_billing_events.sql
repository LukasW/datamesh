MODEL (
    name analytics.stg_billing_events,
    kind VIEW,
    description 'Staging model: parse raw billing event JSON into typed columns. One row per event.'
);

SELECT
    id                                                                        AS surrogate_key,
    event_id,
    topic,
    event_type,
    invoice_id,
    policy_id,
    partner_id,
    json_extract_scalar(payload, '$.invoiceNumber')                           AS invoice_number,
    json_extract_scalar(payload, '$.billingCycle')                            AS billing_cycle,
    CAST(json_extract_scalar(payload, '$.totalAmount') AS DECIMAL(15, 2))     AS total_amount_chf,
    CAST(json_extract_scalar(payload, '$.amountPaid') AS DECIMAL(15, 2))      AS amount_paid_chf,
    CAST(json_extract_scalar(payload, '$.dueDate') AS DATE)                   AS due_date,
    CAST(json_extract_scalar(payload, '$.paidAt') AS DATE)                    AS paid_at,
    json_extract_scalar(payload, '$.dunningLevel')                            AS dunning_level,
    CAST(json_extract_scalar(payload, '$.timestamp') AS TIMESTAMP)            AS event_at,
    consumed_at
FROM iceberg.billing_raw.billing_events
WHERE event_type IN ('InvoiceCreated', 'PaymentReceived', 'DunningInitiated', 'PayoutTriggered')
  AND invoice_id IS NOT NULL
