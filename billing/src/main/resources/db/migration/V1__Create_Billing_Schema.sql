-- Billing & Collection Service – Core Schema
-- Follows Hexagonal Architecture: only billing-service writes to these tables.

CREATE TABLE invoice (
    invoice_id     VARCHAR(36)    NOT NULL PRIMARY KEY,
    invoice_number VARCHAR(64)    NOT NULL UNIQUE,
    policy_id      VARCHAR(36)    NOT NULL,
    policy_number  VARCHAR(64)    NOT NULL,
    partner_id     VARCHAR(36)    NOT NULL,
    status         VARCHAR(20)    NOT NULL DEFAULT 'OPEN',  -- OPEN | PAID | OVERDUE | CANCELLED
    billing_cycle  VARCHAR(20)    NOT NULL,                  -- ANNUAL | SEMI_ANNUAL | QUARTERLY | MONTHLY
    total_amount   NUMERIC(12,2)  NOT NULL,
    invoice_date   DATE           NOT NULL,
    due_date       DATE           NOT NULL,
    paid_at        DATE,
    cancelled_at   DATE,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE invoice_line_item (
    line_item_id  VARCHAR(36)    NOT NULL PRIMARY KEY,
    invoice_id    VARCHAR(36)    NOT NULL REFERENCES invoice(invoice_id) ON DELETE CASCADE,
    description   VARCHAR(255)   NOT NULL,
    amount        NUMERIC(12,2)  NOT NULL
);

CREATE TABLE dunning_case (
    dunning_case_id VARCHAR(36)   NOT NULL PRIMARY KEY,
    invoice_id      VARCHAR(36)   NOT NULL REFERENCES invoice(invoice_id),
    level           VARCHAR(20)   NOT NULL DEFAULT 'REMINDER',  -- REMINDER | FIRST_WARNING | FINAL_WARNING | COLLECTION
    initiated_at    DATE          NOT NULL DEFAULT CURRENT_DATE,
    escalated_at    DATE
);

CREATE INDEX idx_invoice_policy_id   ON invoice (policy_id);
CREATE INDEX idx_invoice_partner_id  ON invoice (partner_id);
CREATE INDEX idx_invoice_status      ON invoice (status);
CREATE INDEX idx_invoice_due_date    ON invoice (due_date);
CREATE INDEX idx_invoice_number      ON invoice (invoice_number);
CREATE INDEX idx_line_item_invoice   ON invoice_line_item (invoice_id);
CREATE INDEX idx_dunning_invoice     ON dunning_case (invoice_id);
