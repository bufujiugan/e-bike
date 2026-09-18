CREATE TABLE bike_sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    user_type VARCHAR(32) NOT NULL DEFAULT 'WEB_ADMIN',
    enabled TINYINT NOT NULL DEFAULT 1,
    must_change_password TINYINT NOT NULL DEFAULT 1,
    last_login_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_sys_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_sys_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_sys_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    permission_code VARCHAR(100) NOT NULL,
    permission_name VARCHAR(100) NOT NULL,
    permission_type VARCHAR(32) NOT NULL DEFAULT 'API',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_sys_permission_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_bike_user_role_user FOREIGN KEY (user_id) REFERENCES bike_sys_user (id),
    CONSTRAINT fk_bike_user_role_role FOREIGN KEY (role_id) REFERENCES bike_sys_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_sys_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_bike_role_permission_role FOREIGN KEY (role_id) REFERENCES bike_sys_role (id),
    CONSTRAINT fk_bike_role_permission_permission FOREIGN KEY (permission_id) REFERENCES bike_sys_permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_workstation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workstation_code VARCHAR(64) NOT NULL,
    workstation_name VARCHAR(100) NOT NULL,
    workstation_type VARCHAR(32) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_workstation_code (workstation_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_product_model (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    model_code VARCHAR(64) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_product_model_code (model_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_part (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    part_sn VARCHAR(128) NOT NULL,
    part_type VARCHAR(32) NOT NULL,
    supplier_id BIGINT NULL,
    batch_no VARCHAR(64) NULL,
    qc_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    bind_status VARCHAR(32) NOT NULL DEFAULT 'UNBOUND',
    current_vehicle_id BIGINT NULL,
    current_frame_sn VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
    inbound_time DATETIME(3) NULL,
    remark VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_part_sn (part_sn),
    KEY idx_bike_part_type_status (part_type, status),
    KEY idx_bike_part_current_vehicle (current_vehicle_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_vehicle (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    frame_sn VARCHAR(128) NOT NULL,
    model_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    vehicle_qr_code VARCHAR(255) NULL,
    vehicle_qr_token VARCHAR(128) NULL,
    qr_version INT NOT NULL DEFAULT 1,
    qr_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    assemble_operator_id BIGINT NULL,
    assemble_time DATETIME(3) NULL,
    current_warehouse_id BIGINT NULL,
    current_location_id BIGINT NULL,
    stock_in_time DATETIME(3) NULL,
    stock_out_time DATETIME(3) NULL,
    current_customer_id BIGINT NULL,
    current_order_no VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_vehicle_frame_sn (frame_sn),
    UNIQUE KEY uk_bike_vehicle_qr_token (vehicle_qr_token),
    KEY idx_bike_vehicle_status_model (status, model_id),
    KEY idx_bike_vehicle_order (current_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_vehicle_part_binding (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    vehicle_id BIGINT NOT NULL,
    frame_sn VARCHAR(128) NOT NULL,
    part_id BIGINT NOT NULL,
    part_sn VARCHAR(128) NOT NULL,
    part_type VARCHAR(32) NOT NULL,
    bind_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    unbind_time DATETIME(3) NULL,
    bind_operator_id BIGINT NULL,
    unbind_operator_id BIGINT NULL,
    bind_reason VARCHAR(255) NULL,
    unbind_reason VARCHAR(255) NULL,
    is_current TINYINT NOT NULL DEFAULT 1,
    current_part_key BIGINT GENERATED ALWAYS AS (CASE WHEN is_current = 1 THEN part_id ELSE NULL END) STORED,
    current_vehicle_type_key VARCHAR(200) GENERATED ALWAYS AS (CASE WHEN is_current = 1 THEN CONCAT(vehicle_id, ':', part_type) ELSE NULL END) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_bike_binding_current_part (current_part_key),
    UNIQUE KEY uk_bike_binding_current_vehicle_type (current_vehicle_type_key),
    KEY idx_bike_binding_frame_sn (frame_sn),
    KEY idx_bike_binding_part_sn (part_sn),
    KEY idx_bike_binding_vehicle (vehicle_id),
    CONSTRAINT fk_bike_binding_vehicle FOREIGN KEY (vehicle_id) REFERENCES bike_vehicle (id),
    CONSTRAINT fk_bike_binding_part FOREIGN KEY (part_id) REFERENCES bike_part (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_inventory_lock (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    lock_no VARCHAR(64) NOT NULL,
    agent_id BIGINT NOT NULL,
    product_model_id BIGINT NOT NULL,
    lock_qty INT NOT NULL,
    released_qty INT NOT NULL DEFAULT 0,
    converted_qty INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'LOCKED',
    expire_time DATETIME(3) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    released_at DATETIME(3) NULL,
    remark VARCHAR(500) NULL,
    UNIQUE KEY uk_bike_inventory_lock_no (lock_no),
    KEY idx_bike_inventory_lock_model_status (product_model_id, status),
    KEY idx_bike_inventory_lock_agent_status (agent_id, status),
    KEY idx_bike_inventory_lock_expire (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bike_operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    business_type VARCHAR(64) NOT NULL,
    business_id VARCHAR(64) NULL,
    action VARCHAR(64) NOT NULL,
    frame_sn VARCHAR(128) NULL,
    before_data JSON NULL,
    after_data JSON NULL,
    operator_id BIGINT NULL,
    operator_name VARCHAR(100) NULL,
    client_ip VARCHAR(64) NULL,
    operation_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    remark VARCHAR(500) NULL,
    KEY idx_bike_operation_log_frame_time (frame_sn, operation_time),
    KEY idx_bike_operation_log_type_time (business_type, operation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
