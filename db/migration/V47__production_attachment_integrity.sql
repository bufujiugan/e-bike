-- Historical metadata stays unknown; do not claim historical content was verified.
ALTER TABLE bike_production_attachment
    ADD COLUMN file_name VARCHAR(200) NULL COMMENT 'Original validated file name; unknown for legacy files',
    ADD COLUMN sha256 CHAR(64) NULL COMMENT 'SHA-256 of validated uploaded bytes; unknown for legacy files',
    ADD COLUMN request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Client idempotency key within the issue or SKU',
    ADD UNIQUE KEY uk_production_attachment_issue_request(issue_id,request_id),
    ADD UNIQUE KEY uk_production_attachment_sku_request(sku_id,request_id);

ALTER TABLE bike_pi_payment_attachment
    DROP CHECK ck_bike_pi_receipt_size,
    DROP CHECK ck_bike_pi_receipt_type,
    MODIFY COLUMN content_type VARCHAR(100) NOT NULL COMMENT 'Validated image or document MIME type',
    ADD CONSTRAINT ck_bike_pi_receipt_size CHECK (file_size > 0 AND
        ((content_type IN ('image/jpeg','image/png','image/webp') AND file_size <= 20971520)
        OR (content_type IN ('application/pdf','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet') AND file_size <= 52428800))),
    ADD CONSTRAINT ck_bike_pi_receipt_type CHECK (content_type IN ('image/jpeg','image/png','image/webp','application/pdf','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'));
