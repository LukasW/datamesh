-- Local read model for Partner data, materialized from person.v1.state Kafka events.
-- Used for FNOL partner search without a synchronous call to Partner or Policy service.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE partner_search_view
(
    partner_id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    last_name               VARCHAR(255) NOT NULL,
    first_name              VARCHAR(255) NOT NULL,
    date_of_birth           DATE,
    social_security_number  VARCHAR(16),
    insured_number          VARCHAR(11),
    upserted_at             TIMESTAMP    NOT NULL DEFAULT now()
);

-- Trigram GIN index for performant ILIKE search on 10M+ rows.
CREATE INDEX idx_partner_search_trgm
    ON partner_search_view
    USING GIN ((last_name || ' ' || first_name) gin_trgm_ops);

-- B-Tree index for exact AHV-Nummer lookup.
CREATE INDEX idx_partner_search_ssn
    ON partner_search_view (social_security_number)
    WHERE social_security_number IS NOT NULL;

-- B-Tree index for exact insured-number lookup (VN-XXXXXXXX).
CREATE INDEX idx_partner_search_insured_number
    ON partner_search_view (insured_number)
    WHERE insured_number IS NOT NULL;

-- B-Tree index for date-of-birth filter.
CREATE INDEX idx_partner_search_dob
    ON partner_search_view (date_of_birth);

