CREATE TABLE bike_file_upload (
    object_key VARCHAR(255) NOT NULL COMMENT '服务端生成的存储键，不作为公开下载地址',
    purpose VARCHAR(32) NOT NULL COMMENT '附件业务类型',
    business_id VARCHAR(64) NOT NULL COMMENT '上传目标业务标识快照，不设外键以独立提交上传意图',
    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名或归档文件名',
    content_type VARCHAR(128) NOT NULL COMMENT '已验证的文件类型',
    file_size BIGINT NOT NULL COMMENT '预期文件字节数',
    sha256 CHAR(64) NOT NULL COMMENT '已验证文件内容摘要',
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待绑定 BOUND已绑定 REVIEW待核对 CLEANUP_PENDING待清理 CLEANED已清理',
    object_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT '最近核对的文件状态 UNKNOWN PRESENT MISSING',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '并发修改版本',
    created_by BIGINT NULL COMMENT '上传人员标识快照，后台任务为空',
    created_name VARCHAR(100) NOT NULL COMMENT '上传人员账号或system',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传意图独立提交时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后状态变更时间',
    checked_at DATETIME(3) NULL COMMENT '最近实际核对时间',
    error_code VARCHAR(64) NULL COMMENT '固定错误代码，不保存凭据或底层异常详情',
    review_reason VARCHAR(500) NULL COMMENT '人工核对或清理原因',
    reviewed_by BIGINT NULL COMMENT '核对人员标识快照',
    PRIMARY KEY(object_key),
    KEY idx_file_upload_review(state,created_at),
    CONSTRAINT ck_file_upload_state CHECK (state IN ('PENDING','BOUND','REVIEW','CLEANUP_PENDING','CLEANED')),
    CONSTRAINT ck_file_upload_object_state CHECK (object_state IN ('UNKNOWN','PRESENT','MISSING')),
    CONSTRAINT ck_file_upload_size CHECK (file_size > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上传及绑定失败的持久化核对记录';

CREATE TABLE bike_file_upload_review (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '核对历史主键',
    object_key VARCHAR(255) NOT NULL COMMENT '被核对的上传记录',
    action VARCHAR(32) NOT NULL COMMENT 'RECONCILE人工核对或CLEANUP_REQUEST清理申请',
    before_state VARCHAR(24) NOT NULL COMMENT '操作前状态',
    after_state VARCHAR(24) NOT NULL COMMENT '操作后状态',
    before_version BIGINT NOT NULL COMMENT '操作前版本',
    reason VARCHAR(500) NOT NULL COMMENT '操作原因',
    operator_id BIGINT NULL COMMENT '操作人员标识快照',
    operator_name VARCHAR(100) NOT NULL COMMENT '操作人员账号快照',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间',
    PRIMARY KEY(id), KEY idx_file_review_key(object_key,id),
    CONSTRAINT fk_file_review_upload FOREIGN KEY(object_key) REFERENCES bike_file_upload(object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上传核对及清理申请历史';

INSERT INTO bike_sys_permission(permission_code,permission_name,permission_type)
VALUES('FILE_REVIEW_MANAGE','全业务上传失败核对及未绑定文件清理（无内容下载权）','API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type);

-- Reference checks must not scan the entire attachment history for every commit/review.
CREATE INDEX idx_production_attachment_object ON bike_production_attachment(object_key);
CREATE INDEX idx_pi_confirmation_object ON bike_pi_confirmation_attachment(object_key);
CREATE INDEX idx_pi_payment_attachment_object ON bike_pi_payment_attachment(object_key);
CREATE INDEX idx_pi_logistics_attachment_object ON bike_pi_logistics_attachment(object_key);
CREATE INDEX idx_after_sales_attachment_object ON bike_after_sales_attachment(object_key);
CREATE INDEX idx_pi_export_pdf_object ON bike_pi_export(pdf_key);
CREATE INDEX idx_pi_export_xlsx_object ON bike_pi_export(xlsx_key);
CREATE INDEX idx_pi_payment_legacy_object ON bike_pi_payment(voucher_object_key);
