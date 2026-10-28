CREATE TABLE bike_sku_image_draft (
    draft_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '新增 SKU 图片草稿会话标识',
    created_by VARCHAR(64) NOT NULL COMMENT '创建账号',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后上传时间',
    PRIMARY KEY (draft_token),
    KEY idx_sku_image_draft_cleanup (updated_at, draft_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='新增 SKU 自动上传图片草稿会话';

ALTER TABLE bike_production_attachment
    ADD COLUMN draft_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '新增 SKU 保存前的图片草稿会话' AFTER sku_id,
    ADD UNIQUE KEY uk_production_attachment_draft_request (draft_token, request_id),
    ADD KEY idx_production_attachment_draft (draft_token, created_at, id),
    ADD CONSTRAINT fk_production_attachment_draft FOREIGN KEY (draft_token) REFERENCES bike_sku_image_draft(draft_token);
