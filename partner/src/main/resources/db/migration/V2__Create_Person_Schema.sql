-- Person
CREATE TABLE person (
  person_id       VARCHAR(36)  PRIMARY KEY,
  name            VARCHAR(100) NOT NULL,
  vorname         VARCHAR(100) NOT NULL,
  geschlecht      VARCHAR(10)  NOT NULL CHECK (geschlecht IN ('MAENNLICH','WEIBLICH','DIVERS')),
  geburtsdatum    DATE         NOT NULL,
  ahv_nummer      VARCHAR(16)  NOT NULL UNIQUE,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Adresse (temporal, owned by Person)
CREATE TABLE adresse (
  adress_id       VARCHAR(36)  PRIMARY KEY,
  person_id       VARCHAR(36)  NOT NULL REFERENCES person(person_id) ON DELETE CASCADE,
  adress_typ      VARCHAR(25)  NOT NULL CHECK (adress_typ IN ('WOHNADRESSE','KORRESPONDENZADRESSE','ZUSTELLADRESSE')),
  strasse         VARCHAR(255) NOT NULL,
  hausnummer      VARCHAR(20)  NOT NULL,
  plz             VARCHAR(4)   NOT NULL,
  ort             VARCHAR(100) NOT NULL,
  land            VARCHAR(100) NOT NULL DEFAULT 'Schweiz',
  gueltig_von     DATE         NOT NULL,
  gueltig_bis     DATE,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indices
CREATE INDEX idx_person_name           ON person(name, vorname);
CREATE INDEX idx_person_ahv            ON person(ahv_nummer);
CREATE INDEX idx_adresse_person        ON adresse(person_id);
CREATE INDEX idx_adresse_typ_gueltig   ON adresse(person_id, adress_typ, gueltig_von, gueltig_bis);
