-- Policy (insurance contract)
CREATE TABLE policy (
  policy_id            VARCHAR(36)    PRIMARY KEY,
  policy_nummer        VARCHAR(50)    NOT NULL UNIQUE,
  partner_id           VARCHAR(36)    NOT NULL,
  produkt_id           VARCHAR(36)    NOT NULL,
  status               VARCHAR(20)    NOT NULL DEFAULT 'ENTWURF'
                         CHECK (status IN ('ENTWURF','AKTIV','GEKUENDIGT','ABGELAUFEN')),
  versicherungsbeginn  DATE           NOT NULL,
  versicherungsende    DATE,
  praemie              NUMERIC(12,2)  NOT NULL,
  selbstbehalt         NUMERIC(12,2)  NOT NULL DEFAULT 0,
  created_at           TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Deckung (coverage – child of Policy)
CREATE TABLE deckung (
  deckung_id           VARCHAR(36)    PRIMARY KEY,
  policy_id            VARCHAR(36)    NOT NULL REFERENCES policy(policy_id) ON DELETE CASCADE,
  deckungstyp          VARCHAR(30)    NOT NULL
                         CHECK (deckungstyp IN ('HAFTPFLICHT','KASKOSCHADEN','GLASBRUCH',
                                                'ELEMENTAR','DIEBSTAHL','GEBAEUDE','HAUSRAT')),
  versicherungssumme   NUMERIC(15,2)  NOT NULL,
  created_at           TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (policy_id, deckungstyp)
);

-- Indices
CREATE INDEX idx_policy_partner         ON policy(partner_id);
CREATE INDEX idx_policy_produkt         ON policy(produkt_id);
CREATE INDEX idx_policy_status          ON policy(status);
CREATE INDEX idx_policy_nummer          ON policy(policy_nummer);
CREATE INDEX idx_deckung_policy         ON deckung(policy_id);

