ALTER TABLE bike_after_sales_attachment
    ADD COLUMN message_id BIGINT NULL COMMENT '关联留言ID；空值表示直接属于案例' AFTER case_id,
    ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'OTHER' COMMENT '附件用途：故障、序列号、购买凭证、诊断或技术文件' AFTER visibility,
    ADD KEY idx_bike_after_sales_attachment_message (message_id, id),
    ADD KEY idx_bike_after_sales_attachment_visibility (case_id, visibility, id),
    ADD CONSTRAINT fk_bike_after_sales_attachment_message FOREIGN KEY (message_id) REFERENCES bike_after_sales_message(id),
    ADD CONSTRAINT ck_bike_after_sales_attachment_category CHECK (category IN ('FAULT_IMAGE','FAULT_VIDEO','FRAME_SN','MOTOR_SN','BATTERY_SN','DISPLAY_SN','PURCHASE_PROOF','DIAGNOSTIC','TECHNICAL','OTHER'));
