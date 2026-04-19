MODEL (
    name claims_silver.claim,
    kind INCREMENTAL_BY_UNIQUE_KEY (
        unique_key claim_id,
        when_matched (
            WHEN MATCHED AND source.updated_at > target.updated_at THEN UPDATE SET
                target.claim_number = source.claim_number,
                target.policy_id = source.policy_id,
                target.description = source.description,
                target.claim_date = source.claim_date,
                target.status = source.status,
                target.opened_at = COALESCE(target.opened_at, source.opened_at),
                target.updated_at = source.updated_at
        )
    ),
    cron '@hourly',
    description 'Current state of every claim. Derived from ClaimOpened/ClaimUnderReview/ClaimSettled/ClaimRejected events.'
);

WITH ranked AS (
    SELECT
        claimid                               AS claim_id,
        claimnumber                           AS claim_number,
        policyid                              AS policy_id,
        description,
        CAST(claimdate AS DATE)               AS claim_date,
        status,
        eventtype,
        from_iso8601_timestamp(timestamp)          AS event_at,
        ROW_NUMBER() OVER (
            PARTITION BY claimid
            ORDER BY from_iso8601_timestamp(timestamp) DESC
        )                                     AS rn
    FROM iceberg.claims_raw.claims_events
    WHERE eventtype IN ('ClaimOpened', 'ClaimUnderReview', 'ClaimSettled', 'ClaimRejected')
      AND claimid IS NOT NULL
      AND from_iso8601_timestamp(timestamp) BETWEEN CAST(@start_ts AS TIMESTAMP(6) WITH TIME ZONE) AND CAST(@end_ts AS TIMESTAMP(6) WITH TIME ZONE)
),

first_opened AS (
    SELECT
        claimid                               AS claim_id,
        MIN(from_iso8601_timestamp(timestamp))     AS opened_at
    FROM iceberg.claims_raw.claims_events
    WHERE eventtype = 'ClaimOpened'
    GROUP BY claimid
)

SELECT
    r.claim_id,
    r.claim_number,
    r.policy_id,
    r.description,
    r.claim_date,
    r.status,
    fo.opened_at,
    r.event_at                                AS updated_at
FROM ranked r
LEFT JOIN first_opened fo ON r.claim_id = fo.claim_id
WHERE r.rn = 1
