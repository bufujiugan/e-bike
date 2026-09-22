ALTER TABLE bike_after_sales_shipment
    ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '物流乐观版本',
    ADD COLUMN warehouse_name VARCHAR(128) NULL COMMENT '发货仓库快照',
    ADD COLUMN recipient VARCHAR(100) NULL COMMENT '收货人快照',
    ADD COLUMN phone VARCHAR(64) NULL COMMENT '收货电话快照',
    ADD COLUMN country_code CHAR(2) NULL COMMENT '收货国家快照',
    ADD COLUMN region VARCHAR(100) NULL COMMENT '收货省州快照',
    ADD COLUMN city VARCHAR(100) NULL COMMENT '收货城市快照',
    ADD COLUMN address_line VARCHAR(500) NULL COMMENT '收货详细地址快照',
    ADD COLUMN postal_code VARCHAR(32) NULL COMMENT '收货邮编快照',
    ADD COLUMN expected_arrival_date DATE NULL COMMENT '预计到达日期',
    ADD COLUMN logistics_status VARCHAR(32) NULL COMMENT 'IN_TRANSIT/EXCEPTION/DELIVERED',
    ADD COLUMN logistics_note VARCHAR(1000) NULL COMMENT '公开物流说明',
    ADD COLUMN delivered_at DATETIME(3) NULL COMMENT '签收登记时间',
    ADD COLUMN cancelled_reason VARCHAR(1000) NULL COMMENT '计划取消原因',
    ADD CONSTRAINT ck_bike_after_sales_shipping_revision CHECK(revision>0),
    ADD CONSTRAINT ck_bike_after_sales_shipping_logistics CHECK(logistics_status IS NULL OR logistics_status IN('IN_TRANSIT','EXCEPTION','DELIVERED')),
    DROP CHECK ck_bike_after_sales_shipment_status,
    ADD CONSTRAINT ck_bike_after_sales_shipment_status CHECK(status IN('PLANNED','SHIPPED','CANCELLED'));

-- Existing dispatch facts are preserved. Unknown historical addresses/warehouses stay NULL.
-- Do not infer whether a historical shipped parcel is still in transit or already delivered.

ALTER TABLE bike_after_sales_return
    DROP CHECK ck_bike_after_sales_return_status,
    ADD CONSTRAINT ck_bike_after_sales_return_status CHECK(status IN('REQUIRED','WAITING_CUSTOMER','IN_TRANSIT','RECEIVED','INSPECTED','SCRAPPED','COMPLETED','WAIVED','CANCELLED'));
