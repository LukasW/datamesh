CREATE TABLE outbox (
    id             UUID         NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    topic          VARCHAR(256) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_created_at ON outbox (created_at);
