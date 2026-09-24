-- Existing published versions remain immutable. Unknown historical contact fields are not inferred.
ALTER TABLE bike_pi_template
    ADD COLUMN seller_country VARCHAR(100) NULL COMMENT 'Published seller country or region; unknown for historical templates',
    ADD COLUMN seller_contact_name VARCHAR(100) NULL COMMENT 'Published seller contact person; unknown for historical templates',
    ADD COLUMN seller_phone VARCHAR(64) NULL COMMENT 'Published seller phone; unknown for historical templates',
    ADD COLUMN seller_email VARCHAR(254) NULL COMMENT 'Published seller email; unknown for historical templates';
