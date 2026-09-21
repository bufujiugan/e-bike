-- 旧车型承诺保留原数量、截止和付款保留状态；不推测历史 SKU。
ALTER TABLE bike_inventory_lock
  MODIFY agent_id BIGINT NULL COMMENT '代理商；直接客户锁定可为空',
  ADD customer_id BIGINT NULL COMMENT 'SKU锁定所属客户',
  ADD sku_id BIGINT NULL COMMENT '精确SKU；历史车型锁定为空',
  ADD scope_type VARCHAR(16) NOT NULL DEFAULT 'MODEL_LEGACY' COMMENT 'MODEL_LEGACY或SKU',
  ADD lock_request_id BIGINT NULL COMMENT '原子批量锁定请求',
  ADD KEY idx_lock_sku_expiry(sku_id,status,expire_time),
  ADD CONSTRAINT fk_lock_customer FOREIGN KEY(customer_id) REFERENCES bike_customer(id),
  ADD CONSTRAINT fk_lock_sku FOREIGN KEY(sku_id) REFERENCES bike_production_sku(id),
  ADD CONSTRAINT ck_lock_scope CHECK ((scope_type='MODEL_LEGACY' AND sku_id IS NULL) OR (scope_type='SKU' AND sku_id IS NOT NULL AND customer_id IS NOT NULL));

CREATE TABLE bike_sku_lock_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  request_id VARCHAR(64) NOT NULL COMMENT '调用方幂等标识',
  operator_id BIGINT NOT NULL COMMENT '幂等范围所属操作人',
  payload_hash CHAR(64) NOT NULL COMMENT '规范化请求SHA256',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  UNIQUE KEY uk_sku_lock_request(operator_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多SKU锁定原子请求及重试';

ALTER TABLE bike_inventory_lock
  ADD KEY idx_lock_request(lock_request_id),
  ADD CONSTRAINT fk_lock_request FOREIGN KEY(lock_request_id) REFERENCES bike_sku_lock_request(id);

ALTER TABLE bike_order_reservation
  ADD scope_type VARCHAR(16) NOT NULL DEFAULT 'MODEL_LEGACY' COMMENT '车型兼容承诺或精确SKU明细',
  ADD CONSTRAINT ck_reservation_scope CHECK(scope_type IN ('MODEL_LEGACY','SKU'));

CREATE TABLE bike_order_reservation_item (
  order_id BIGINT NOT NULL COMMENT '单车型履约订单',
  sku_id BIGINT NOT NULL COMMENT '固定履约SKU',
  ordered_qty INT NOT NULL COMMENT '本SKU承诺总量',
  shipped_qty INT NOT NULL DEFAULT 0 COMMENT '有效实际出库量',
  closed_qty INT NOT NULL DEFAULT 0 COMMENT '主动关闭剩余量',
  PRIMARY KEY(order_id,sku_id),
  KEY idx_reservation_item_sku(sku_id,order_id),
  CONSTRAINT fk_reservation_item_order FOREIGN KEY(order_id) REFERENCES bike_order_reservation(order_id),
  CONSTRAINT fk_reservation_item_sku FOREIGN KEY(sku_id) REFERENCES bike_production_sku(id),
  CONSTRAINT ck_reservation_item_qty CHECK(ordered_qty>0 AND shipped_qty>=0 AND closed_qty>=0 AND shipped_qty+closed_qty<=ordered_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU精度占用；总量在车型占用头中仅计一次';
