-- Revision info (Hibernate Envers)
CREATE SEQUENCE revinfo_rev_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE revinfo (
  rev        BIGINT      NOT NULL DEFAULT nextval('revinfo_rev_seq') PRIMARY KEY,
  revtstmp   BIGINT      NOT NULL,
  changed_by VARCHAR(255)
);

-- Person audit
CREATE TABLE person_aud (
  rev          BIGINT       NOT NULL REFERENCES revinfo(rev),
  rev_type     SMALLINT     NOT NULL,
  person_id    VARCHAR(36)  NOT NULL,
  name         VARCHAR(100),
  vorname      VARCHAR(100),
  geschlecht   VARCHAR(10),
  geburtsdatum DATE,
  ahv_nummer   VARCHAR(16),
  created_at   TIMESTAMP,
  updated_at   TIMESTAMP,
  PRIMARY KEY (rev, person_id)
);

-- Adresse audit
CREATE TABLE adresse_aud (
  rev          BIGINT       NOT NULL REFERENCES revinfo(rev),
  rev_type     SMALLINT     NOT NULL,
  adress_id    VARCHAR(36)  NOT NULL,
  person_id    VARCHAR(36),
  adress_typ   VARCHAR(25),
  strasse      VARCHAR(255),
  hausnummer   VARCHAR(20),
  plz          VARCHAR(4),
  ort          VARCHAR(100),
  land         VARCHAR(100),
  gueltig_von  DATE,
  gueltig_bis  DATE,
  created_at   TIMESTAMP,
  PRIMARY KEY (rev, adress_id)
);
