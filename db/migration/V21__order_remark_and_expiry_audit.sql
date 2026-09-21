ALTER TABLE bike_sales_order ADD COLUMN remark VARCHAR(500) NULL COMMENT '订单备注';
ALTER TABLE bike_order_reservation ADD COLUMN expiry_audited_at DATETIME(3) NULL COMMENT '本次占用到期审计时间，续占用重置';
