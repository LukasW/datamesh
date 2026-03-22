-- Add insured number to partner_sicht (PartnerView read model).
-- Nullable: not all partners have an insured number yet.
ALTER TABLE partner_sicht ADD COLUMN insured_number VARCHAR(11);
-- Index for search by insured number.
CREATE INDEX idx_partner_sicht_insured_number
    ON partner_sicht (insured_number)
    WHERE insured_number IS NOT NULL;
