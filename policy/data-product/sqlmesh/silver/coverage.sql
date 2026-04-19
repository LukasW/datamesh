MODEL (
    name policy_silver.coverage,
    kind INCREMENTAL_BY_TIME_RANGE (
        time_column created_at
    ),
    cron '@hourly',
    description 'All coverages added to policies. Append-only from CoverageAdded events.'
);

SELECT
    coverageid                                AS coverage_id,
    policyid                                  AS policy_id,
    coveragetype                              AS coverage_type,
    CAST(insuredamount AS DECIMAL(15, 2))     AS insured_amount_chf,
    from_iso8601_timestamp(timestamp)              AS created_at
FROM iceberg.policy_raw.policy_events
WHERE eventtype = 'CoverageAdded'
  AND coverageid IS NOT NULL
  AND from_iso8601_timestamp(timestamp) BETWEEN CAST(@start_ts AS TIMESTAMP(6) WITH TIME ZONE) AND CAST(@end_ts AS TIMESTAMP(6) WITH TIME ZONE)
