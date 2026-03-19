-- ADR-005: rename all German column and table names to English.

-- ── policy: column renames ────────────────────────────────────────────────────
ALTER TABLE policy RENAME COLUMN policy_nummer       TO policy_number;
ALTER TABLE policy RENAME COLUMN produkt_id          TO product_id;
ALTER TABLE policy RENAME COLUMN versicherungsbeginn TO coverage_start_date;
ALTER TABLE policy RENAME COLUMN versicherungsende   TO coverage_end_date;
ALTER TABLE policy RENAME COLUMN praemie             TO premium;
ALTER TABLE policy RENAME COLUMN selbstbehalt        TO deductible;

-- ── policy_aud: mirror column renames ────────────────────────────────────────
ALTER TABLE policy_aud RENAME COLUMN policy_nummer       TO policy_number;
ALTER TABLE policy_aud RENAME COLUMN produkt_id          TO product_id;
ALTER TABLE policy_aud RENAME COLUMN versicherungsbeginn TO coverage_start_date;
ALTER TABLE policy_aud RENAME COLUMN versicherungsende   TO coverage_end_date;
ALTER TABLE policy_aud RENAME COLUMN praemie             TO premium;
ALTER TABLE policy_aud RENAME COLUMN selbstbehalt        TO deductible;

-- ── deckung → coverage: table and column renames ─────────────────────────────
ALTER TABLE deckung RENAME TO coverage;
ALTER TABLE coverage RENAME COLUMN deckung_id        TO coverage_id;
ALTER TABLE coverage RENAME COLUMN deckungstyp       TO coverage_type;
ALTER TABLE coverage RENAME COLUMN versicherungssumme TO insured_amount;

-- ── deckung_aud → coverage_aud: mirror renames ───────────────────────────────
ALTER TABLE deckung_aud RENAME TO coverage_aud;
ALTER TABLE coverage_aud RENAME COLUMN deckung_id        TO coverage_id;
ALTER TABLE coverage_aud RENAME COLUMN deckungstyp       TO coverage_type;
ALTER TABLE coverage_aud RENAME COLUMN versicherungssumme TO insured_amount;
