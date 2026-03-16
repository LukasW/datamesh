-- Migrate status and coverage-type enum values from German to English (ADR-005).
-- Existing rows are updated first, then the check constraints are replaced.

-- ── policy.status ────────────────────────────────────────────────────────────

UPDATE policy SET status = 'DRAFT'     WHERE status = 'ENTWURF';
UPDATE policy SET status = 'ACTIVE'    WHERE status = 'AKTIV';
UPDATE policy SET status = 'CANCELLED' WHERE status = 'GEKUENDIGT';
UPDATE policy SET status = 'EXPIRED'   WHERE status = 'ABGELAUFEN';

ALTER TABLE policy DROP CONSTRAINT policy_status_check;
ALTER TABLE policy ALTER COLUMN status SET DEFAULT 'DRAFT';
ALTER TABLE policy ADD CONSTRAINT policy_status_check
    CHECK (status IN ('DRAFT','ACTIVE','CANCELLED','EXPIRED'));

-- ── deckung.deckungstyp ───────────────────────────────────────────────────────

UPDATE deckung SET deckungstyp = 'LIABILITY'          WHERE deckungstyp = 'HAFTPFLICHT';
UPDATE deckung SET deckungstyp = 'COMPREHENSIVE'      WHERE deckungstyp = 'KASKOSCHADEN';
UPDATE deckung SET deckungstyp = 'GLASS_BREAKAGE'     WHERE deckungstyp = 'GLASBRUCH';
UPDATE deckung SET deckungstyp = 'NATURAL_HAZARD'     WHERE deckungstyp = 'ELEMENTAR';
UPDATE deckung SET deckungstyp = 'THEFT'              WHERE deckungstyp = 'DIEBSTAHL';
UPDATE deckung SET deckungstyp = 'BUILDING'           WHERE deckungstyp = 'GEBAEUDE';
UPDATE deckung SET deckungstyp = 'HOUSEHOLD_CONTENTS' WHERE deckungstyp = 'HAUSRAT';

ALTER TABLE deckung DROP CONSTRAINT deckung_deckungstyp_check;
ALTER TABLE deckung ADD CONSTRAINT deckung_deckungstyp_check
    CHECK (deckungstyp IN ('LIABILITY','COMPREHENSIVE','GLASS_BREAKAGE',
                           'NATURAL_HAZARD','THEFT','BUILDING','HOUSEHOLD_CONTENTS'));
