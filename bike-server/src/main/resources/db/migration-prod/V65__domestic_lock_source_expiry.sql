ALTER TABLE bike_domestic_order_lock_source
  ADD COLUMN renewed_expires_at DATETIME(3) NULL COMMENT '最近一次订单续占用截止',
  ADD COLUMN shipped_qty INT NOT NULL DEFAULT 0,
  ADD COLUMN expiry_audited_at DATETIME(3) NULL,
  ADD KEY idx_domestic_source_sku(order_id,sku_id),
  ADD CONSTRAINT chk_domestic_source_shipped_qty CHECK(shipped_qty BETWEEN 0 AND quantity);

CREATE TABLE bike_domestic_stock_out_source (
  stock_out_id BIGINT NOT NULL PRIMARY KEY,
  order_id BIGINT NOT NULL,
  lock_id BIGINT NOT NULL,
  CONSTRAINT fk_domestic_stock_out_record FOREIGN KEY(stock_out_id) REFERENCES bike_stock_out_record(id),
  CONSTRAINT fk_domestic_stock_out_source FOREIGN KEY(order_id,lock_id) REFERENCES bike_domestic_order_lock_source(order_id,lock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内销出库对应的库存锁定来源';
