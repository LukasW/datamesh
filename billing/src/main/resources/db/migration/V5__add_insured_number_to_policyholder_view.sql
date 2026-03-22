-- Add insured number to policyholder_view for display on invoices.
ALTER TABLE policyholder_view ADD COLUMN insured_number VARCHAR(11);
-- Index for search by insured number.
CREATE INDEX idx_policyholder_view_insured_number
    ON policyholder_view (insured_number)
    WHERE insured_number IS NOT NULL;
