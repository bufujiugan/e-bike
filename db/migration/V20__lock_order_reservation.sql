CREATE TABLE bike_order_reservation (
  order_id BIGINT NOT NULL COMMENT '由锁定生成的订单',
  source_lock_id BIGINT NOT NULL COMMENT '来源锁定',
  request_id VARCHAR(64) NOT NULL COMMENT '转单幂等请求标识',
  model_id BIGINT NOT NULL COMMENT '占用车型',
  reserved_qty INT NOT NULL COMMENT '初始转换数量',
  expires_at DATETIME(3) NOT NULL COMMENT '沿用来源锁定截止时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '转单时间',
  PRIMARY KEY(order_id),
  UNIQUE KEY uk_reservation_request(source_lock_id,request_id),
  KEY idx_reservation_model_expiry(model_id,expires_at),
  CONSTRAINT fk_reservation_order FOREIGN KEY(order_id) REFERENCES bike_sales_order(id),
  CONSTRAINT fk_reservation_lock FOREIGN KEY(source_lock_id) REFERENCES bike_inventory_lock(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='锁定转订单的可追踪库存占用';
