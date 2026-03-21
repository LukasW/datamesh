-- V1: Core Claims schema
CREATE TABLE claim
(
    claim_id     VARCHAR(36)  NOT NULL PRIMARY KEY,
    claim_number VARCHAR(64)  NOT NULL UNIQUE,
    policy_id    VARCHAR(36)  NOT NULL,
    description  TEXT         NOT NULL,
    claim_date   DATE         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE INDEX idx_claim_policy_id ON claim (policy_id);
CREATE INDEX idx_claim_status    ON claim (status);
