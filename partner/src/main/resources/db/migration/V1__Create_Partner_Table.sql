-- Flyway Migration: V1__Create_Partner_Table.sql
-- Initial schema for Partner Service

CREATE TABLE IF NOT EXISTS partner (
    partner_id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(20),
    street VARCHAR(255),
    city VARCHAR(100),
    postal_code VARCHAR(10),
    country VARCHAR(100),
    partner_type VARCHAR(50) NOT NULL CHECK (partner_type IN ('CUSTOMER', 'BROKER', 'AGENT', 'SUPPLIER')),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for search optimization
CREATE INDEX idx_partner_name ON partner (name);
CREATE INDEX idx_partner_email ON partner (email);
CREATE INDEX idx_partner_type ON partner (partner_type);
CREATE INDEX idx_partner_status ON partner (status);

-- Audit table for compliance
CREATE TABLE IF NOT EXISTS partner_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    partner_id VARCHAR(36) NOT NULL,
    action VARCHAR(50) NOT NULL,
    old_values JSONB,
    new_values JSONB,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_partner_audit FOREIGN KEY (partner_id) REFERENCES partner (partner_id) ON DELETE CASCADE
);

CREATE INDEX idx_partner_audit_partner_id ON partner_audit (partner_id);
CREATE INDEX idx_partner_audit_changed_at ON partner_audit (changed_at);
