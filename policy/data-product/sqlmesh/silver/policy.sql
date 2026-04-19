MODEL (
    name policy_silver.policy,
    kind INCREMENTAL_BY_UNIQUE_KEY (
        unique_key policy_id,
        when_matched (
            WHEN MATCHED AND source.updated_at > target.updated_at THEN UPDATE SET
                target.policy_number = source.policy_number,
                target.partner_id = source.partner_id,
                target.product_id = source.product_id,
                target.coverage_start_date = source.coverage_start_date,
                target.premium_chf = source.premium_chf,
                target.policy_status = source.policy_status,
                target.issued_at = COALESCE(target.issued_at, source.issued_at),
                target.updated_at = source.updated_at
        )
    ),
    cron '*/5 * * * *',
    description 'Current state of every policy. Derived from PolicyIssued/PolicyCancelled events.'
);

WITH ranked AS (
    SELECT
        policyid                              AS policy_id,
        policynumber                          AS policy_number,
        partnerid                             AS partner_id,
        productid                             AS product_id,
        CAST(coveragestartdate AS DATE)       AS coverage_start_date,
        CAST(premium AS DECIMAL(15, 2))       AS premium_chf,
        eventtype,
        from_iso8601_timestamp(timestamp)          AS event_at,
        ROW_NUMBER() OVER (
            PARTITION BY policyid
            ORDER BY from_iso8601_timestamp(timestamp) DESC
        )                                     AS rn
    FROM iceberg.policy_raw.policy_events
    WHERE eventtype IN ('PolicyIssued', 'PolicyCancelled', 'PolicyChanged')
      AND policyid IS NOT NULL
      AND from_iso8601_timestamp(timestamp) BETWEEN CAST(@start_ts AS TIMESTAMP(6) WITH TIME ZONE) AND CAST(@end_ts AS TIMESTAMP(6) WITH TIME ZONE)
),

first_issued AS (
    SELECT
        policyid                              AS policy_id,
        MIN(from_iso8601_timestamp(timestamp))     AS issued_at
    FROM iceberg.policy_raw.policy_events
    WHERE eventtype = 'PolicyIssued'
    GROUP BY policyid
)

SELECT
    r.policy_id,
    r.policy_number,
    r.partner_id,
    r.product_id,
    r.coverage_start_date,
    r.premium_chf,
    CASE r.eventtype
        WHEN 'PolicyCancelled' THEN 'CANCELLED'
        ELSE 'ACTIVE'
    END                                       AS policy_status,
    fi.issued_at,
    r.event_at                                AS updated_at
FROM ranked r
LEFT JOIN first_issued fi ON r.policy_id = fi.policy_id
WHERE r.rn = 1
