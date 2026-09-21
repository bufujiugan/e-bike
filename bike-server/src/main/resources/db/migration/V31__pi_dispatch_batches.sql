CREATE TABLE bike_stock_out_batch (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '出库批次主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '服务端生成的真实出库批次号',
    series_id BIGINT NOT NULL COMMENT '履约PI系列',
    execution_pi_id BIGINT NULL COMMENT '执行时商业修订，历史单车回填为空',
    customer_id BIGINT NOT NULL COMMENT '发货客户',
    request_id VARCHAR(64) NOT NULL COMMENT '请求幂等标识或明确标记的历史流水标识',
    payload_hash CHAR(64) NULL COMMENT '规范化请求摘要，历史回填为空',
    source_type VARCHAR(32) NOT NULL COMMENT 'PI_COMMAND、ORDER_COMMAND或LEGACY_SINGLE',
    operator_id BIGINT NOT NULL COMMENT '操作者，历史未知为0',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '实际出库时间',
    PRIMARY KEY(id), UNIQUE KEY uk_dispatch_no(batch_no),
    UNIQUE KEY uk_dispatch_request(series_id,operator_id,request_id),
    CONSTRAINT fk_dispatch_series FOREIGN KEY(series_id) REFERENCES bike_pi_series(id),
    CONSTRAINT fk_dispatch_pi FOREIGN KEY(execution_pi_id) REFERENCES bike_pi(id),
    CONSTRAINT fk_dispatch_customer FOREIGN KEY(customer_id) REFERENCES bike_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI真实出库请求批次，状态由有效流水计算';

CREATE TABLE bike_stock_out_batch_item (
    stock_out_id BIGINT NOT NULL COMMENT '原逐车出库流水',
    batch_id BIGINT NOT NULL COMMENT '出库请求批次',
    sku_id BIGINT NULL COMMENT '实际履约SKU，历史未识别为空',
    PRIMARY KEY(stock_out_id), KEY idx_dispatch_item_batch(batch_id,stock_out_id),
    CONSTRAINT fk_dispatch_item_stock FOREIGN KEY(stock_out_id) REFERENCES bike_stock_out_record(id),
    CONSTRAINT fk_dispatch_item_batch FOREIGN KEY(batch_id) REFERENCES bike_stock_out_batch(id),
    CONSTRAINT fk_dispatch_item_sku FOREIGN KEY(sku_id) REFERENCES bike_production_sku(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='真实批次逐车关联及SKU执行快照';

-- 不按相近时间推测原批量请求，历史每条流水独立并明确标记。
INSERT INTO bike_stock_out_batch(batch_no,series_id,execution_pi_id,customer_id,request_id,source_type,operator_id,created_at)
SELECT CONCAT('LEGACY-',so.id),l.series_id,NULL,so.customer_id,CONCAT('legacy-stock-',so.id),'LEGACY_SINGLE',COALESCE(so.operator_id,0),so.operation_time
FROM bike_stock_out_record so JOIN bike_pi_order_link l ON l.order_id=so.order_id;
INSERT INTO bike_stock_out_batch_item(stock_out_id,batch_id,sku_id)
SELECT so.id,b.id,pv.sku_id FROM bike_stock_out_record so JOIN bike_stock_out_batch b ON b.batch_no=CONCAT('LEGACY-',so.id)
LEFT JOIN bike_production_vehicle pv ON pv.vehicle_id=so.vehicle_id;
