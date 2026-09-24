CREATE TABLE bike_pi_revision_source (
    pi_id BIGINT NOT NULL,
    lock_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    lock_no VARCHAR(64) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    order_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (pi_id,lock_id),
    UNIQUE KEY uk_pi_revision_source_order (order_id),
    CONSTRAINT fk_pi_revision_source_pi FOREIGN KEY (pi_id) REFERENCES bike_pi(id),
    CONSTRAINT fk_pi_revision_source_lock FOREIGN KEY (lock_id) REFERENCES bike_inventory_lock(id),
    CONSTRAINT fk_pi_revision_source_sku FOREIGN KEY (sku_id) REFERENCES bike_production_sku(id),
    CONSTRAINT fk_pi_revision_source_order FOREIGN KEY (order_id) REFERENCES bike_sales_order(id),
    CONSTRAINT ck_pi_revision_source_quantity CHECK (quantity > 0 AND quantity <= 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
