ALTER TABLE bike_order_reservation MODIFY source_lock_id BIGINT NULL COMMENT '兼容单来源锁定，手工建单为空';

CREATE TABLE bike_domestic_sku_order (
  order_id BIGINT NOT NULL PRIMARY KEY,
  operator_id BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  UNIQUE KEY uk_domestic_sku_order_request(operator_id,request_id),
  CONSTRAINT fk_domestic_sku_order_reservation FOREIGN KEY(order_id) REFERENCES bike_order_reservation(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内销SKU订单及幂等请求';

CREATE TABLE bike_domestic_order_lock_source (
  order_id BIGINT NOT NULL,
  lock_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  quantity INT NOT NULL CHECK(quantity>0),
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY(order_id,lock_id),
  KEY idx_domestic_order_lock_source(lock_id,order_id),
  CONSTRAINT fk_domestic_source_order FOREIGN KEY(order_id) REFERENCES bike_domestic_sku_order(order_id),
  CONSTRAINT fk_domestic_source_lock FOREIGN KEY(lock_id) REFERENCES bike_inventory_lock(id),
  CONSTRAINT fk_domestic_source_sku FOREIGN KEY(order_id,sku_id) REFERENCES bike_order_reservation_item(order_id,sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内销订单逐笔库存锁定来源';
