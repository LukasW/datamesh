ALTER TABLE product
    DROP CONSTRAINT product_product_line_check;

ALTER TABLE product
    ADD CONSTRAINT product_product_line_check
        CHECK (product_line IN ('HOUSEHOLD_CONTENTS', 'LIABILITY', 'MOTOR_VEHICLE', 'TRAVEL', 'LEGAL_EXPENSES'));
