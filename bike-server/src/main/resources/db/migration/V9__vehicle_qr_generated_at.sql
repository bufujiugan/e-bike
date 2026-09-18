ALTER TABLE bike_vehicle
    ADD COLUMN qr_generated_at DATETIME(3) NULL AFTER vehicle_qr_token;
