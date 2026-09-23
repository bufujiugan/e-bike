CREATE TABLE bike_pi_logistics_attachment (
    id VARCHAR(36) PRIMARY KEY COMMENT '附件ID',
    logistics_id BIGINT NOT NULL COMMENT '真实物流记录ID',
    request_id VARCHAR(64) NOT NULL COMMENT '物流记录内上传幂等标识',
    object_key VARCHAR(512) NOT NULL COMMENT '受保护存储键',
    file_name VARCHAR(255) NOT NULL COMMENT '安全原始文件名',
    content_type VARCHAR(100) NOT NULL COMMENT '验证后的文件类型',
    file_size BIGINT NOT NULL COMMENT '实际字节数',
    sha256 CHAR(64) NOT NULL COMMENT '文件内容哈希',
    visibility VARCHAR(16) NOT NULL COMMENT 'CUSTOMER/INTERNAL',
    created_by BIGINT NULL COMMENT '上传用户',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
    UNIQUE KEY uk_pi_logistics_attachment_request(logistics_id,request_id),
    INDEX idx_pi_logistics_attachment_list(logistics_id,created_at,id),
    CONSTRAINT fk_pi_logistics_attachment_parent FOREIGN KEY(logistics_id) REFERENCES bike_pi_logistics(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
