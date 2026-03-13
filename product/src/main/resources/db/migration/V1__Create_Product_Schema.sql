-- Product Management Schema
-- V1: Initial product table

CREATE TABLE product (
    product_id   VARCHAR(36)     NOT NULL,
    name         VARCHAR(200)    NOT NULL,
    description  VARCHAR(1000),
    product_line VARCHAR(30)     NOT NULL
        CHECK (product_line IN ('HAUSRAT', 'HAFTPFLICHT', 'MOTORFAHRZEUG', 'REISE', 'RECHTSSCHUTZ')),
    base_premium NUMERIC(10, 2)  NOT NULL CHECK (base_premium >= 0),
    status       VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DEPRECATED')),
    created_at   TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_product PRIMARY KEY (product_id)
);

CREATE INDEX idx_product_name ON product (LOWER(name));
CREATE INDEX idx_product_line ON product (product_line);
CREATE INDEX idx_product_status ON product (status);
