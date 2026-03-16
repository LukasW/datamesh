-- Update geschlecht check constraint to use English enum values
ALTER TABLE person DROP CONSTRAINT person_geschlecht_check;
UPDATE person SET geschlecht = 'MALE'   WHERE geschlecht = 'MAENNLICH';
UPDATE person SET geschlecht = 'FEMALE' WHERE geschlecht = 'WEIBLICH';
UPDATE person SET geschlecht = 'DIVERSE' WHERE geschlecht = 'DIVERS';
ALTER TABLE person ADD CONSTRAINT person_geschlecht_check CHECK (geschlecht IN ('MALE','FEMALE','DIVERSE'));

-- Update adress_typ check constraint to use English enum values
ALTER TABLE adresse DROP CONSTRAINT adresse_adress_typ_check;
UPDATE adresse SET adress_typ = 'RESIDENCE'      WHERE adress_typ = 'WOHNADRESSE';
UPDATE adresse SET adress_typ = 'CORRESPONDENCE' WHERE adress_typ = 'KORRESPONDENZADRESSE';
UPDATE adresse SET adress_typ = 'DELIVERY'       WHERE adress_typ = 'ZUSTELLADRESSE';
ALTER TABLE adresse ADD CONSTRAINT adresse_adress_typ_check CHECK (adress_typ IN ('RESIDENCE','CORRESPONDENCE','DELIVERY'));
