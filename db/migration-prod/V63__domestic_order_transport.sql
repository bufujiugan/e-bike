ALTER TABLE bike_sales_order
    ADD COLUMN transport_arrangement VARCHAR(24) NULL COMMENT '内销运输安排',
    ADD COLUMN transport_remark VARCHAR(500) NULL COMMENT '内销运输备注';
