CREATE TABLE bike_pi_confirmation_attachment (
    id VARCHAR(36) PRIMARY KEY COMMENT '附件ID',
    pi_id BIGINT NOT NULL COMMENT '所属PI修订，确认附件不跨修订复用',
    request_id VARCHAR(64) NOT NULL COMMENT '上传幂等标识',
    object_key VARCHAR(512) NOT NULL COMMENT '受保护存储键',
    file_name VARCHAR(255) NOT NULL COMMENT '安全原始文件名',
    content_type VARCHAR(100) NOT NULL COMMENT '验证后的类型',
    file_size BIGINT NOT NULL COMMENT '文件字节数',
    sha256 CHAR(64) NOT NULL COMMENT '文件内容哈希',
    visibility VARCHAR(16) NOT NULL COMMENT 'CUSTOMER/INTERNAL',
    created_by BIGINT NULL COMMENT '上传用户',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
    UNIQUE KEY uk_pi_confirmation_request(pi_id,request_id),
    INDEX idx_pi_confirmation_list(pi_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
