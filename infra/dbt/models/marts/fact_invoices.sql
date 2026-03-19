-- Mart model: one row per invoice with current status derived from events.
-- Status logic:
--   InvoiceCreated  → OPEN
--   PaymentReceived → PAID
--   DunningInitiated → OVERDUE (if no PaymentReceived after)
{{ config(materialized='table') }}

WITH latest_event AS (
    SELECT
        invoice_id,
        invoice_number,
        policy_id,
        partner_id,
        billing_cycle,
        total_amount_chf,
        due_date,
        event_type,
        event_at,
        paid_at,
        dunning_level,
        ROW_NUMBER() OVER (
            PARTITION BY invoice_id
            ORDER BY event_at DESC NULLS LAST, consumed_at DESC
        ) AS rn
    FROM {{ ref('stg_billing_events') }}
),

first_created AS (
    SELECT
        invoice_id,
        MIN(event_at) AS created_at
    FROM {{ ref('stg_billing_events') }}
    WHERE event_type = 'InvoiceCreated'
    GROUP BY invoice_id
)

SELECT
    le.invoice_id,
    le.invoice_number,
    le.policy_id,
    le.partner_id,
    le.billing_cycle,
    le.total_amount_chf,
    le.due_date,
    CASE le.event_type
        WHEN 'PaymentReceived'  THEN 'PAID'
        WHEN 'DunningInitiated' THEN 'OVERDUE'
        ELSE                         'OPEN'
    END                                         AS invoice_status,
    le.paid_at,
    le.dunning_level,
    fc.created_at,
    le.event_at                                 AS last_event_at
FROM latest_event le
LEFT JOIN first_created fc ON le.invoice_id = fc.invoice_id
WHERE le.rn = 1
