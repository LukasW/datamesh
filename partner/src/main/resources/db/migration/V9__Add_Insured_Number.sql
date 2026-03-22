-- Add insured number column to person table.
-- Nullable: a person only gets an insured number when their first policy is activated.
-- UNIQUE: no two persons can share the same insured number.
ALTER TABLE person ADD COLUMN insured_number VARCHAR(11) UNIQUE;
-- Sequence for generating insured numbers (VN-XXXXXXXX).
-- START WITH 1 ensures the first number is VN-00000001.
CREATE SEQUENCE insured_number_seq START WITH 1 INCREMENT BY 1 NO CYCLE;
-- Audit table mirror (Hibernate Envers).
ALTER TABLE person_aud ADD COLUMN insured_number VARCHAR(11);
