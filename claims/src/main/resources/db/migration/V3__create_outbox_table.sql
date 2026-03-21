-- V3: Transactional Outbox for Debezium CDC
CREATE TABLE outbox
(
    id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(36)  NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    topic          VARCHAR(256) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT now()
);
