ALTER TABLE bike_pi_item
    ADD COLUMN unit VARCHAR(32) NULL COMMENT '计量单位快照，历史未知保留空',
    ADD COLUMN image_attachment_id VARCHAR(36) NULL COMMENT '确认使用的不可变SKU图片附件',
    ADD COLUMN image_sha256 CHAR(64) NULL COMMENT '保存明细时图片内容SHA256，拒绝存储被替换';

ALTER TABLE bike_pi ADD COLUMN other_fee_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '其他明确费用，参与应付总额';
