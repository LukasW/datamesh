-- Platform database schema
-- Raw layer: events as-received from Kafka (Data Outside only – no direct domain DB access)
-- Analytics layer: dbt-managed transformed models

CREATE SCHEMA IF NOT EXISTS partner_raw;
CREATE SCHEMA IF NOT EXISTS product_raw;
CREATE SCHEMA IF NOT EXISTS policy_raw;
CREATE SCHEMA IF NOT EXISTS billing_raw;
CREATE SCHEMA IF NOT EXISTS analytics;

-- Partner domain: person lifecycle events
CREATE TABLE IF NOT EXISTS partner_raw.person_events (
    id          BIGSERIAL    PRIMARY KEY,
    event_id    VARCHAR(128) NOT NULL UNIQUE,
    topic       VARCHAR(255) NOT NULL,
    event_type  VARCHAR(128),
    person_id   VARCHAR(128),
    payload     TEXT         NOT NULL,
    consumed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_person_events_person_id   ON partner_raw.person_events (person_id);
CREATE INDEX IF NOT EXISTS idx_person_events_event_type  ON partner_raw.person_events (event_type);
CREATE INDEX IF NOT EXISTS idx_person_events_consumed_at ON partner_raw.person_events (consumed_at);

-- Partner domain: person.v1.state (Event-Carried State Transfer, compacted topic)
-- One row per person – upserted on every new state message
CREATE TABLE IF NOT EXISTS partner_raw.person_state (
    id          BIGSERIAL    PRIMARY KEY,
    person_id   VARCHAR(128) NOT NULL UNIQUE,
    city        VARCHAR(255),
    postal_code VARCHAR(20),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    payload     TEXT         NOT NULL,
    consumed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_person_state_city ON partner_raw.person_state (city);

-- Product domain: product lifecycle events
CREATE TABLE IF NOT EXISTS product_raw.product_events (
    id          BIGSERIAL    PRIMARY KEY,
    event_id    VARCHAR(128) NOT NULL UNIQUE,
    topic       VARCHAR(255) NOT NULL,
    event_type  VARCHAR(128),
    product_id  VARCHAR(128),
    payload     TEXT         NOT NULL,
    consumed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_product_events_product_id  ON product_raw.product_events (product_id);
CREATE INDEX IF NOT EXISTS idx_product_events_event_type  ON product_raw.product_events (event_type);
CREATE INDEX IF NOT EXISTS idx_product_events_consumed_at ON product_raw.product_events (consumed_at);

-- Policy domain: policy lifecycle events
CREATE TABLE IF NOT EXISTS policy_raw.policy_events (
    id          BIGSERIAL    PRIMARY KEY,
    event_id    VARCHAR(128) NOT NULL UNIQUE,
    topic       VARCHAR(255) NOT NULL,
    event_type  VARCHAR(128),
    policy_id   VARCHAR(128),
    partner_id  VARCHAR(128),
    product_id  VARCHAR(128),
    payload     TEXT         NOT NULL,
    consumed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_policy_events_policy_id   ON policy_raw.policy_events (policy_id);
CREATE INDEX IF NOT EXISTS idx_policy_events_partner_id  ON policy_raw.policy_events (partner_id);
CREATE INDEX IF NOT EXISTS idx_policy_events_product_id  ON policy_raw.policy_events (product_id);
CREATE INDEX IF NOT EXISTS idx_policy_events_event_type  ON policy_raw.policy_events (event_type);
CREATE INDEX IF NOT EXISTS idx_policy_events_consumed_at ON policy_raw.policy_events (consumed_at);

-- Billing domain: invoice and payment lifecycle events
CREATE TABLE IF NOT EXISTS billing_raw.billing_events (
    id          BIGSERIAL    PRIMARY KEY,
    event_id    VARCHAR(128) NOT NULL UNIQUE,
    topic       VARCHAR(255) NOT NULL,
    event_type  VARCHAR(128),
    invoice_id  VARCHAR(128),
    policy_id   VARCHAR(128),
    partner_id  VARCHAR(128),
    payload     TEXT         NOT NULL,
    consumed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_billing_events_invoice_id  ON billing_raw.billing_events (invoice_id);
CREATE INDEX IF NOT EXISTS idx_billing_events_policy_id   ON billing_raw.billing_events (policy_id);
CREATE INDEX IF NOT EXISTS idx_billing_events_partner_id  ON billing_raw.billing_events (partner_id);
CREATE INDEX IF NOT EXISTS idx_billing_events_event_type  ON billing_raw.billing_events (event_type);
CREATE INDEX IF NOT EXISTS idx_billing_events_consumed_at ON billing_raw.billing_events (consumed_at);
