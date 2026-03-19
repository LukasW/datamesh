-- Hibernate Envers audit tables for Invoice (financial audit trail).

CREATE SEQUENCE IF NOT EXISTS revinfo_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE revinfo (
    rev      INTEGER      NOT NULL PRIMARY KEY DEFAULT nextval('revinfo_seq'),
    revtstmp BIGINT
);

CREATE TABLE invoice_aud (
    invoice_id     VARCHAR(36)    NOT NULL,
    rev            INTEGER        NOT NULL REFERENCES revinfo(rev),
    rev_type       SMALLINT,
    invoice_number VARCHAR(64),
    policy_id      VARCHAR(36),
    policy_number  VARCHAR(64),
    partner_id     VARCHAR(36),
    status         VARCHAR(20),
    billing_cycle  VARCHAR(20),
    total_amount   NUMERIC(12,2),
    invoice_date   DATE,
    due_date       DATE,
    paid_at        DATE,
    cancelled_at   DATE,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    PRIMARY KEY (invoice_id, rev)
);
