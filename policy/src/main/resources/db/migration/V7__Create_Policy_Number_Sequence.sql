-- Explicit sequence for generating unique policy numbers.
-- Replaces the random retry approach with a deterministic, gap-free sequence.
CREATE SEQUENCE policy_number_seq START 1 INCREMENT 1;
