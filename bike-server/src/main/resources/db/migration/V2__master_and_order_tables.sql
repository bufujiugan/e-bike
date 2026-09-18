CREATE TABLE bike_customer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_code VARCHAR(64) NOT NULL,
    customer_name VARCHAR(150) NOT NULL,
    contact_name VARCHAR(100) NULL,
    contact_phone VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_customer_code (customer_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_supplier (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_code VARCHAR(64) NOT NULL,
    supplier_name VARCHAR(150) NOT NULL,
    contact_name VARCHAR(100) NULL,
    contact_phone VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_supplier_code (supplier_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_agent (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_code VARCHAR(64) NOT NULL,
    agent_name VARCHAR(150) NOT NULL,
    contact_name VARCHAR(100) NULL,
    contact_phone VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_agent_code (agent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_warehouse (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    warehouse_code VARCHAR(64) NOT NULL,
    warehouse_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_warehouse_code (warehouse_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_warehouse_location (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL,
    location_code VARCHAR(64) NOT NULL,
    location_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_location_warehouse_code (warehouse_id, location_code),
    CONSTRAINT fk_bike_location_warehouse FOREIGN KEY (warehouse_id) REFERENCES bike_warehouse (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_sales_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    planned_ship_date DATE NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_sales_order_no (order_no),
    KEY idx_bike_sales_order_customer_status (customer_id, status),
    CONSTRAINT fk_bike_sales_order_customer FOREIGN KEY (customer_id) REFERENCES bike_customer (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_sales_order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_model_id BIGINT NOT NULL,
    ordered_qty INT NOT NULL,
    shipped_qty INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_order_item_model (order_id, product_model_id),
    CONSTRAINT fk_bike_order_item_order FOREIGN KEY (order_id) REFERENCES bike_sales_order (id),
    CONSTRAINT fk_bike_order_item_model FOREIGN KEY (product_model_id) REFERENCES bike_product_model (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_stock_in_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_in_no VARCHAR(64) NOT NULL,
    vehicle_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    operator_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    operation_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_stock_in_no (stock_in_no),
    KEY idx_bike_stock_in_vehicle_time (vehicle_id, operation_time),
    CONSTRAINT fk_bike_stock_in_vehicle FOREIGN KEY (vehicle_id) REFERENCES bike_vehicle (id),
    CONSTRAINT fk_bike_stock_in_warehouse FOREIGN KEY (warehouse_id) REFERENCES bike_warehouse (id),
    CONSTRAINT fk_bike_stock_in_location FOREIGN KEY (location_id) REFERENCES bike_warehouse_location (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_stock_out_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_out_no VARCHAR(64) NOT NULL,
    vehicle_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    operator_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    operation_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_stock_out_no (stock_out_no),
    KEY idx_bike_stock_out_vehicle_time (vehicle_id, operation_time),
    CONSTRAINT fk_bike_stock_out_vehicle FOREIGN KEY (vehicle_id) REFERENCES bike_vehicle (id),
    CONSTRAINT fk_bike_stock_out_customer FOREIGN KEY (customer_id) REFERENCES bike_customer (id),
    CONSTRAINT fk_bike_stock_out_order FOREIGN KEY (order_id) REFERENCES bike_sales_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_inventory_lock_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    lock_id BIGINT NOT NULL,
    product_model_id BIGINT NOT NULL,
    lock_qty INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_bike_lock_detail_lock FOREIGN KEY (lock_id) REFERENCES bike_inventory_lock (id),
    CONSTRAINT fk_bike_lock_detail_model FOREIGN KEY (product_model_id) REFERENCES bike_product_model (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
