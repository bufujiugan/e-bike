CREATE TABLE bike_production_scan_failure (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '失败记录主键',
    task_id VARCHAR(64) NOT NULL COMMENT '请求任务号',
    scanned_code VARCHAR(255) NOT NULL COMMENT '输入编码',
    expected_type VARCHAR(32) NULL COMMENT '选择的编码类型',
    operator_name VARCHAR(100) NOT NULL COMMENT '操作账号',
    error_code VARCHAR(64) NOT NULL COMMENT '失败代码',
    reason VARCHAR(500) NOT NULL COMMENT '失败原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    INDEX idx_scan_failure_task (task_id, id),
    INDEX idx_scan_failure_code (scanned_code, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生产失败扫码审计';
