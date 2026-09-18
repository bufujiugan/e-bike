ALTER TABLE bike_stock_in_record
    ADD COLUMN cancel_reason VARCHAR(500) NULL,
    ADD COLUMN cancelled_by BIGINT NULL,
    ADD COLUMN cancelled_at DATETIME(3) NULL;

ALTER TABLE bike_sales_order ADD COLUMN agent_id BIGINT NULL AFTER customer_id;

ALTER TABLE bike_stock_out_record
    ADD COLUMN cancel_reason VARCHAR(500) NULL,
    ADD COLUMN cancelled_by BIGINT NULL,
    ADD COLUMN cancelled_at DATETIME(3) NULL;

CREATE TABLE bike_sales_order_vehicle (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    stock_out_record_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_order_vehicle (order_id, vehicle_id),
    CONSTRAINT fk_bike_order_vehicle_order FOREIGN KEY (order_id) REFERENCES bike_sales_order (id),
    CONSTRAINT fk_bike_order_vehicle_vehicle FOREIGN KEY (vehicle_id) REFERENCES bike_vehicle (id),
    CONSTRAINT fk_bike_order_vehicle_stock_out FOREIGN KEY (stock_out_record_id) REFERENCES bike_stock_out_record (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_rework_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rework_no VARCHAR(64) NOT NULL,
    vehicle_id BIGINT NOT NULL,
    frame_sn VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    operator_id BIGINT NULL,
    operation_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_rework_no (rework_no),
    KEY idx_bike_rework_frame_time (frame_sn, operation_time),
    CONSTRAINT fk_bike_rework_vehicle FOREIGN KEY (vehicle_id) REFERENCES bike_vehicle (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_rework_part_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rework_id BIGINT NOT NULL,
    part_type VARCHAR(32) NOT NULL,
    old_part_id BIGINT NULL,
    old_part_sn VARCHAR(128) NULL,
    new_part_id BIGINT NOT NULL,
    new_part_sn VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_bike_rework_detail_rework FOREIGN KEY (rework_id) REFERENCES bike_rework_record (id),
    CONSTRAINT fk_bike_rework_detail_new_part FOREIGN KEY (new_part_id) REFERENCES bike_part (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_sys_login_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    success TINYINT NOT NULL,
    client_ip VARCHAR(64) NULL,
    failure_reason VARCHAR(255) NULL,
    login_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_bike_login_log_username_time (username, login_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_system_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(128) NOT NULL,
    config_value VARCHAR(500) NOT NULL,
    remark VARCHAR(500) NULL,
    updated_by BIGINT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_system_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
