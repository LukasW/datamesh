-- Staging model: parse raw billing event JSON into typed columns.
-- One row per event (deduplication happens in the mart).
{{ config(materialized='view') }}

SELECT
    id                                                              AS surrogate_key,
    event_id,
    topic,
    event_type,
    invoice_id,
    policy_id,
    partner_id,
    (payload::jsonb) ->> 'invoiceNumber'                           AS invoice_number,
    (payload::jsonb) ->> 'billingCycle'                            AS billing_cycle,
    ((payload::jsonb) ->> 'totalAmount')::numeric                  AS total_amount_chf,
    ((payload::jsonb) ->> 'amountPaid')::numeric                   AS amount_paid_chf,
    ((payload::jsonb) ->> 'dueDate')::date                         AS due_date,
    ((payload::jsonb) ->> 'paidAt')::date                          AS paid_at,
    (payload::jsonb) ->> 'dunningLevel'                            AS dunning_level,
    ((payload::jsonb) ->> 'timestamp')::timestamptz                AS event_at,
    consumed_at
FROM {{ source('billing_raw', 'billing_events') }}
WHERE event_type IN ('InvoiceCreated', 'PaymentReceived', 'DunningInitiated', 'PayoutTriggered')
  AND invoice_id IS NOT NULL
