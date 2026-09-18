UPDATE bike_vehicle
SET qr_generated_at = COALESCE(assemble_time, created_at)
WHERE vehicle_qr_code IS NOT NULL
  AND qr_generated_at IS NULL;
