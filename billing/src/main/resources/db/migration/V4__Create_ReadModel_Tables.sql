-- Local read models built from consumed Kafka events (Data Outside pattern).
-- billing-service is the sole writer of these tables; no cross-domain DB access.

CREATE TABLE policyholder_view (
    partner_id   VARCHAR(36)    NOT NULL PRIMARY KEY,
    name         VARCHAR(255)   NOT NULL,
    upserted_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_policyholder_name ON policyholder_view (name);
