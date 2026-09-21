-- First after-sales schema. Scope is the existing agent/customer association, not a company membership model.
CREATE TABLE bike_after_sales_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    case_no VARCHAR(64) NOT NULL COMMENT '唯一售后案例编号',
    customer_id BIGINT NULL COMMENT '关联客户ID',
    agent_id BIGINT NULL COMMENT '关联代理商ID',
    vehicle_id BIGINT NULL COMMENT '经归属验证的车辆ID',
    frame_sn VARCHAR(128) NULL COMMENT '客户填写或关联车辆的车架号',
    product_model_id BIGINT NULL COMMENT '产品型号ID',
    motor_sn VARCHAR(128) NULL COMMENT '电机序列号',
    battery_sn VARCHAR(128) NULL COMMENT '电池序列号',
    display_sn VARCHAR(128) NULL COMMENT '仪表序列号',
    purchase_date DATE NULL COMMENT '客户购买日期',
    failure_time DATETIME(3) NULL COMMENT '故障发生时间',
    fault_type VARCHAR(64) NULL COMMENT '故障类型',
    fault_part VARCHAR(128) NULL COMMENT '故障部件',
    error_code VARCHAR(128) NULL COMMENT '故障代码',
    description TEXT NOT NULL COMMENT '问题描述',
    attempted_repair TEXT NULL COMMENT '客户已尝试维修方法',
    warranty_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '保修责任状态',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '当前业务状态',
    assignee_id BIGINT NULL COMMENT '售后负责人用户ID',
    technical_judgment TEXT NULL COMMENT '客户可见技术判断',
    solution TEXT NULL COMMENT '客户可见维修方案',
    internal_note TEXT NULL COMMENT '仅内部审核技术岗位可见备注',
    rejection_reason VARCHAR(1000) NULL COMMENT '拒绝或要求补充资料原因',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    submitted_at DATETIME(3) NULL COMMENT '首次提交时间',
    closed_at DATETIME(3) NULL COMMENT '归档时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
    UNIQUE KEY uk_bike_after_sales_case_no (case_no),
    KEY idx_bike_after_sales_case_scope (customer_id, agent_id, status),
    KEY idx_bike_after_sales_case_frame (frame_sn),
    KEY idx_bike_after_sales_case_status_time (status, updated_at),
    KEY idx_bike_after_sales_agent_status_time (agent_id, status, updated_at),
    CONSTRAINT ck_bike_after_sales_owner CHECK (customer_id IS NOT NULL OR agent_id IS NOT NULL),
    CONSTRAINT ck_bike_after_sales_status CHECK (status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','NEEDS_INFO','APPROVED','IN_PROGRESS','WAITING_PARTS','SHIPPED','RESOLVED','CLOSED','REJECTED')),
    CONSTRAINT ck_bike_after_sales_warranty CHECK (warranty_status IN ('PENDING','UNKNOWN','IN_WARRANTY','OUT_OF_WARRANTY')),
    CONSTRAINT ck_bike_after_sales_text_lengths CHECK (CHAR_LENGTH(description) BETWEEN 1 AND 5000 AND (attempted_repair IS NULL OR CHAR_LENGTH(attempted_repair) <= 2000) AND (technical_judgment IS NULL OR CHAR_LENGTH(technical_judgment) <= 4000) AND (solution IS NULL OR CHAR_LENGTH(solution) <= 4000) AND (internal_note IS NULL OR CHAR_LENGTH(internal_note) <= 4000)),
    CONSTRAINT fk_bike_after_sales_customer FOREIGN KEY (customer_id) REFERENCES bike_customer(id),
    CONSTRAINT fk_bike_after_sales_agent FOREIGN KEY (agent_id) REFERENCES bike_agent(id),
    CONSTRAINT fk_bike_after_sales_vehicle FOREIGN KEY (vehicle_id) REFERENCES bike_vehicle(id),
    CONSTRAINT fk_bike_after_sales_model FOREIGN KEY (product_model_id) REFERENCES bike_product_model(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后案例及维修方案';

CREATE TABLE bike_after_sales_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    case_id BIGINT NOT NULL COMMENT '所属售后案例ID',
    author_id BIGINT NULL COMMENT '留言人用户ID',
    visibility VARCHAR(32) NOT NULL DEFAULT 'CUSTOMER' COMMENT '可见范围：CUSTOMER或INTERNAL',
    body VARCHAR(4000) NOT NULL COMMENT '留言正文',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    KEY idx_bike_after_sales_message_case_time (case_id, created_at),
    CONSTRAINT ck_bike_after_sales_message_visibility CHECK (visibility IN ('CUSTOMER','INTERNAL')),
    CONSTRAINT fk_bike_after_sales_message_case FOREIGN KEY (case_id) REFERENCES bike_after_sales_case(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后案例留言';

CREATE TABLE bike_after_sales_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    case_id BIGINT NOT NULL COMMENT '所属售后案例ID',
    object_key VARCHAR(500) NOT NULL COMMENT '附件存储对象键',
    file_name VARCHAR(255) NOT NULL COMMENT '原始附件文件名',
    content_type VARCHAR(128) NOT NULL COMMENT '附件内容类型',
    file_size BIGINT NOT NULL COMMENT '附件字节数',
    sha256 VARCHAR(64) NULL COMMENT '附件SHA256摘要',
    visibility VARCHAR(32) NOT NULL DEFAULT 'CUSTOMER' COMMENT '可见范围：CUSTOMER或INTERNAL',
    uploaded_by BIGINT NULL COMMENT '上传人用户ID',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    KEY idx_bike_after_sales_attachment_case (case_id, created_at),
    CONSTRAINT ck_bike_after_sales_attachment_visibility CHECK (visibility IN ('CUSTOMER','INTERNAL')),
    CONSTRAINT ck_bike_after_sales_attachment_size CHECK (file_size > 0),
    CONSTRAINT fk_bike_after_sales_attachment_case FOREIGN KEY (case_id) REFERENCES bike_after_sales_case(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后附件元数据';

CREATE TABLE bike_after_sales_shipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    case_id BIGINT NOT NULL COMMENT '所属售后案例ID',
    part_name VARCHAR(255) NOT NULL COMMENT '补发配件名称',
    quantity INT NOT NULL COMMENT '补发配件数量',
    carrier VARCHAR(128) NULL COMMENT '承运商名称',
    tracking_no VARCHAR(128) NULL COMMENT '物流运单号',
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNED' COMMENT '当前业务状态',
    shipped_at DATETIME(3) NULL COMMENT '实际发货登记时间',
    old_part_return_required TINYINT NOT NULL DEFAULT 0 COMMENT '是否要求退回旧件',
    old_part_return_received_at DATETIME(3) NULL COMMENT '旧件收到时间',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
    KEY idx_bike_after_sales_shipment_case (case_id, status),
    CONSTRAINT ck_bike_after_sales_shipment_qty CHECK (quantity > 0),
    CONSTRAINT ck_bike_after_sales_shipment_status CHECK (status IN ('PLANNED','SHIPPED')),
    CONSTRAINT ck_bike_after_sales_shipment_dispatch CHECK (status != 'SHIPPED' OR (carrier IS NOT NULL AND CHAR_LENGTH(TRIM(carrier)) > 0 AND tracking_no IS NOT NULL AND CHAR_LENGTH(TRIM(tracking_no)) > 0 AND shipped_at IS NOT NULL)),
    CONSTRAINT fk_bike_after_sales_shipment_case FOREIGN KEY (case_id) REFERENCES bike_after_sales_case(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后补发配件及发货记录';

INSERT INTO bike_sys_permission(permission_code, permission_name, permission_type) VALUES
('AFTER_SALES_READ','查看售后案例','API'),
('AFTER_SALES_WRITE','提交售后案例','API'),
('AFTER_SALES_REVIEW','审核售后案例','API'),
('AFTER_SALES_TECH','维护售后技术方案','API'),
('AFTER_SALES_LOGISTICS','维护售后补发物流','API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), permission_type=VALUES(permission_type);

INSERT INTO bike_sys_role(role_code, role_name, enabled) VALUES
('AFTER_SALES_STAFF','售后人员',1),
('AFTER_SALES_TECH','售后技术人员',1),
('AFTER_SALES_WAREHOUSE','售后仓库人员',1)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), enabled=VALUES(enabled);

INSERT INTO bike_sys_role_permission(role_id, permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p
WHERE r.role_code='WEB_AGENT' AND p.permission_code IN ('AFTER_SALES_READ','AFTER_SALES_WRITE')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
INSERT INTO bike_sys_role_permission(role_id, permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p
WHERE r.role_code='AFTER_SALES_STAFF' AND p.permission_code IN ('AFTER_SALES_READ','AFTER_SALES_WRITE','AFTER_SALES_REVIEW','AFTER_SALES_TECH','AFTER_SALES_LOGISTICS')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
INSERT INTO bike_sys_role_permission(role_id, permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p
WHERE r.role_code='AFTER_SALES_TECH' AND p.permission_code IN ('AFTER_SALES_READ','AFTER_SALES_TECH')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
INSERT INTO bike_sys_role_permission(role_id, permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p
WHERE r.role_code='AFTER_SALES_WAREHOUSE' AND p.permission_code IN ('AFTER_SALES_READ','AFTER_SALES_LOGISTICS')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
