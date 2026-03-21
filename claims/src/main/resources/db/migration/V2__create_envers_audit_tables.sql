-- V2: Hibernate Envers audit tables
-- revinfo_seq: Hibernate Envers 6 expects this name (allocation size 50)
CREATE SEQUENCE IF NOT EXISTS revinfo_seq START WITH 1 INCREMENT BY 50 NO CYCLE;

CREATE TABLE revinfo
(
    rev      INTEGER      NOT NULL DEFAULT nextval('revinfo_seq') PRIMARY KEY,
    revtstmp BIGINT
);

CREATE TABLE claim_aud
(
    claim_id     VARCHAR(36) NOT NULL,
    rev          INTEGER     NOT NULL REFERENCES revinfo (rev),
    rev_type     SMALLINT,
    claim_number VARCHAR(64),
    policy_id    VARCHAR(36),
    description  TEXT,
    claim_date   DATE,
    status       VARCHAR(20),
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    PRIMARY KEY (claim_id, rev)
);
