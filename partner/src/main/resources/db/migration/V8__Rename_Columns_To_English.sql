-- ADR-005: rename all German column and table names to English.

-- ── person: column renames ────────────────────────────────────────────────────
ALTER TABLE person RENAME COLUMN vorname      TO first_name;
ALTER TABLE person RENAME COLUMN geschlecht   TO gender;
ALTER TABLE person RENAME COLUMN geburtsdatum TO date_of_birth;
ALTER TABLE person RENAME COLUMN ahv_nummer   TO social_security_number;

-- ── person_aud: mirror column renames ────────────────────────────────────────
ALTER TABLE person_aud RENAME COLUMN vorname      TO first_name;
ALTER TABLE person_aud RENAME COLUMN geschlecht   TO gender;
ALTER TABLE person_aud RENAME COLUMN geburtsdatum TO date_of_birth;
ALTER TABLE person_aud RENAME COLUMN ahv_nummer   TO social_security_number;

-- ── adresse → address: table and column renames ───────────────────────────────
ALTER TABLE adresse RENAME TO address;
ALTER TABLE address RENAME COLUMN adress_id   TO address_id;
ALTER TABLE address RENAME COLUMN adress_typ  TO address_type;
ALTER TABLE address RENAME COLUMN strasse     TO street;
ALTER TABLE address RENAME COLUMN hausnummer  TO house_number;
ALTER TABLE address RENAME COLUMN plz         TO postal_code;
ALTER TABLE address RENAME COLUMN ort         TO city;
ALTER TABLE address RENAME COLUMN gueltig_von TO valid_from;
ALTER TABLE address RENAME COLUMN gueltig_bis TO valid_to;

-- ── adresse_aud → address_aud: mirror renames ─────────────────────────────────
ALTER TABLE adresse_aud RENAME TO address_aud;
ALTER TABLE address_aud RENAME COLUMN adress_id   TO address_id;
ALTER TABLE address_aud RENAME COLUMN adress_typ  TO address_type;
ALTER TABLE address_aud RENAME COLUMN strasse     TO street;
ALTER TABLE address_aud RENAME COLUMN hausnummer  TO house_number;
ALTER TABLE address_aud RENAME COLUMN plz         TO postal_code;
ALTER TABLE address_aud RENAME COLUMN ort         TO city;
ALTER TABLE address_aud RENAME COLUMN gueltig_von TO valid_from;
ALTER TABLE address_aud RENAME COLUMN gueltig_bis TO valid_to;
