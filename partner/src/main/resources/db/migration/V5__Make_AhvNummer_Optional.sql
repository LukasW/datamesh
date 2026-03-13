-- AHV-Nummer is now optional (nullable) for natural persons
ALTER TABLE person ALTER COLUMN ahv_nummer DROP NOT NULL;

