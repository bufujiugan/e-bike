ALTER TABLE bike_sys_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0 COMMENT '登录凭证版本，改密或停用时递增';
CREATE TABLE bike_revoked_token (
    token_id VARCHAR(36) NOT NULL COMMENT '已退出登录的JWT唯一标识',
    expires_at DATETIME(3) NOT NULL COMMENT '原凭证过期时间',
    PRIMARY KEY (token_id),
    KEY idx_revoked_token_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已撤销登录凭证，过期后可清理';
