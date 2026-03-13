-- Revision info (Hibernate Envers)
CREATE SEQUENCE revinfo_rev_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE revinfo (
  rev        BIGINT      NOT NULL DEFAULT nextval('revinfo_rev_seq') PRIMARY KEY,
  revtstmp   BIGINT      NOT NULL,
  changed_by VARCHAR(255)
);

-- Policy audit
CREATE TABLE policy_aud (
  rev                  BIGINT       NOT NULL REFERENCES revinfo(rev),
  rev_type             SMALLINT     NOT NULL,
  policy_id            VARCHAR(36)  NOT NULL,
  policy_nummer        VARCHAR(50),
  partner_id           VARCHAR(36),
  produkt_id           VARCHAR(36),
  status               VARCHAR(20),
  versicherungsbeginn  DATE,
  versicherungsende    DATE,
  praemie              NUMERIC(12,2),
  selbstbehalt         NUMERIC(12,2),
  created_at           TIMESTAMP,
  updated_at           TIMESTAMP,
  PRIMARY KEY (rev, policy_id)
);

-- Deckung audit
CREATE TABLE deckung_aud (
  rev                  BIGINT       NOT NULL REFERENCES revinfo(rev),
  rev_type             SMALLINT     NOT NULL,
  deckung_id           VARCHAR(36)  NOT NULL,
  policy_id            VARCHAR(36),
  deckungstyp          VARCHAR(30),
  versicherungssumme   NUMERIC(15,2),
  created_at           TIMESTAMP,
  PRIMARY KEY (rev, deckung_id)
);

