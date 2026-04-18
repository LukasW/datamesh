MODEL (
    name billing_silver.invoice,
    kind INCREMENTAL_BY_UNIQUE_KEY (
        unique_key invoice_id
    ),
    cron '@hourly',
    description 'Current state of every invoice. Status derived from latest billing event.'
);

WITH ranked AS (
    SELECT
        invoiceid                             AS invoice_id,
        invoicenumber                         AS invoice_number,
        policyid                              AS policy_id,
        partnerid                             AS partner_id,
        policynumber                          AS policy_number,
        billingcycle                          AS billing_cycle,
        CAST(totalamount AS DECIMAL(15, 2))   AS total_amount_chf,
        CAST(duedate AS DATE)                 AS due_date,
        eventtype,
        CAST(amountpaid AS DECIMAL(15, 2))    AS amount_paid_chf,
        CAST(paidat AS DATE)                  AS paid_at,
        dunninglevel                          AS dunning_level,
        from_iso8601_timestamp(timestamp)          AS event_at,
        ROW_NUMBER() OVER (
            PARTITION BY invoiceid
            ORDER BY from_iso8601_timestamp(timestamp) DESC
        )                                     AS rn
    FROM iceberg.billing_raw.billing_events
    WHERE eventtype IN ('InvoiceCreated', 'PaymentReceived', 'DunningInitiated', 'PayoutTriggered')
      AND invoiceid IS NOT NULL
      AND from_iso8601_timestamp(timestamp) BETWEEN @start_date AND @end_date
),

first_created AS (
    SELECT
        invoiceid                             AS invoice_id,
        MIN(from_iso8601_timestamp(timestamp))     AS created_at
    FROM iceberg.billing_raw.billing_events
    WHERE eventtype = 'InvoiceCreated'
    GROUP BY invoiceid
)

SELECT
    r.invoice_id,
    r.invoice_number,
    r.policy_id,
    r.partner_id,
    r.policy_number,
    r.billing_cycle,
    r.total_amount_chf,
    r.due_date,
    CASE r.eventtype
        WHEN 'PaymentReceived'  THEN 'PAID'
        WHEN 'DunningInitiated' THEN 'OVERDUE'
        WHEN 'PayoutTriggered'  THEN 'PAYOUT'
        ELSE                         'OPEN'
    END                                       AS invoice_status,
    r.amount_paid_chf,
    r.paid_at,
    r.dunning_level,
    fc.created_at,
    r.event_at                                AS updated_at
FROM ranked r
LEFT JOIN first_created fc ON r.invoice_id = fc.invoice_id
WHERE r.rn = 1
