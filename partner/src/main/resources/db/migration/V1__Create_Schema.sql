-- Partner
CREATE TABLE partner (
  partner_id    VARCHAR(36) PRIMARY KEY,
  firmenname    VARCHAR(255) NOT NULL,
  partner_type  VARCHAR(50) NOT NULL CHECK (partner_type IN ('VERTRIEBSPARTNER','LIEFERANT','TECHNOLOGIEPARTNER')),
  status        VARCHAR(50) NOT NULL DEFAULT 'LEAD' CHECK (status IN ('LEAD','AKTIV','INAKTIV')),
  strasse       VARCHAR(255),
  hausnummer    VARCHAR(20),
  plz           VARCHAR(10),
  ort           VARCHAR(100),
  land          VARCHAR(100),
  website       VARCHAR(500),
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Kontaktperson
CREATE TABLE kontaktperson (
  kontakt_id    VARCHAR(36) PRIMARY KEY,
  partner_id    VARCHAR(36) NOT NULL REFERENCES partner(partner_id) ON DELETE CASCADE,
  vorname       VARCHAR(100) NOT NULL,
  nachname      VARCHAR(100) NOT NULL,
  rolle         VARCHAR(100),
  email         VARCHAR(255),
  telefon       VARCHAR(50),
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Vertrag
CREATE TABLE vertrag (
  vertrags_id   VARCHAR(36) PRIMARY KEY,
  partner_id    VARCHAR(36) NOT NULL REFERENCES partner(partner_id) ON DELETE CASCADE,
  vertrags_typ  VARCHAR(50) NOT NULL CHECK (vertrags_typ IN ('NDA','RAHMENVERTRAG','RESELLER_VERTRAG')),
  startdatum    DATE NOT NULL,
  enddatum      DATE,
  status        VARCHAR(50) NOT NULL DEFAULT 'IN_VERHANDLUNG' CHECK (status IN ('IN_VERHANDLUNG','UNTERZEICHNET','ABGELAUFEN')),
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Interaktion
CREATE TABLE interaktion (
  interaktions_id   VARCHAR(36) PRIMARY KEY,
  partner_id        VARCHAR(36) NOT NULL REFERENCES partner(partner_id) ON DELETE CASCADE,
  datum             TIMESTAMP NOT NULL,
  art               VARCHAR(50) NOT NULL CHECK (art IN ('E_MAIL','TELEFONAT','MEETING')),
  beschreibung      TEXT NOT NULL,
  naechste_schritte TEXT,
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Audit
CREATE TABLE partner_audit (
  audit_id    BIGSERIAL PRIMARY KEY,
  partner_id  VARCHAR(36) NOT NULL REFERENCES partner(partner_id) ON DELETE CASCADE,
  action      VARCHAR(50),
  old_values  JSONB,
  new_values  JSONB,
  changed_by  VARCHAR(255),
  changed_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indices
CREATE INDEX idx_partner_firmenname      ON partner(firmenname);
CREATE INDEX idx_partner_type            ON partner(partner_type);
CREATE INDEX idx_partner_status          ON partner(status);
CREATE INDEX idx_kontaktperson_partner   ON kontaktperson(partner_id);
CREATE INDEX idx_vertrag_partner         ON vertrag(partner_id);
CREATE INDEX idx_interaktion_partner     ON interaktion(partner_id);
CREATE INDEX idx_interaktion_datum       ON interaktion(datum);
CREATE INDEX idx_partner_audit_partner   ON partner_audit(partner_id);
