-- Local read model for Policy data, materialized from policy.v1.issued Kafka events.
-- Used for FNOL coverage checks without a synchronous REST call to the Policy service (ADR-008).
CREATE TABLE policy_snapshot
(
    policy_id           VARCHAR(36)    NOT NULL PRIMARY KEY,
    policy_number       VARCHAR(50)    NOT NULL,
    partner_id          VARCHAR(36)    NOT NULL,
    product_id          VARCHAR(36)    NOT NULL,
    coverage_start_date DATE           NOT NULL,
    premium             DECIMAL(12, 2) NOT NULL,
    upserted_at         TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE INDEX idx_policy_snapshot_partner ON policy_snapshot (partner_id);
