CREATE TABLE IF NOT EXISTS bike_pi_payment_attachment (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '凭证附件标识',
    payment_id BIGINT NOT NULL COMMENT '真实到账记录主键',
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '到账记录内的上传幂等标识',
    object_key VARCHAR(300) NOT NULL COMMENT '服务端生成的私有存储键',
    file_name VARCHAR(200) NOT NULL COMMENT '经过安全校验的原文件名',
    content_type VARCHAR(64) NOT NULL COMMENT '实际文件类型',
    file_size BIGINT NOT NULL COMMENT '实际文件字节数',
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原文件SHA256',
    created_by BIGINT NULL COMMENT '上传人',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
    UNIQUE KEY uk_bike_pi_receipt_request (payment_id,request_id),
    KEY idx_bike_pi_receipt_created (payment_id,created_at,id),
    CONSTRAINT fk_bike_pi_receipt_payment FOREIGN KEY (payment_id) REFERENCES bike_pi_payment(id),
    CONSTRAINT ck_bike_pi_receipt_size CHECK (file_size > 0 AND file_size <= 10485760),
    CONSTRAINT ck_bike_pi_receipt_type CHECK (content_type IN ('image/jpeg','image/png','application/pdf'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI真实到账记录的私有凭证附件';
