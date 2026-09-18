INSERT INTO bike_sys_permission(permission_code, permission_name, permission_type) VALUES
('STOCK_READ','查看出入库记录','API'),
('VEHICLE_READ','查看整车档案','API'),
('PART_READ','查看零部件','API'),
('MASTER_READ','查看基础资料','API'),
('USER_READ','查看用户','API'),
('ROLE_READ','查看角色权限','API'),
('WORKSTATION_READ','查看工位','API'),
('PURCHASE_INTENT_WRITE','维护采购意向','API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), permission_type=VALUES(permission_type);

INSERT INTO bike_sys_role(role_code, role_name, enabled) VALUES ('WEB_AGENT', 'Web代理商', 1)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), enabled=VALUES(enabled);

INSERT INTO bike_sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM bike_sys_role r JOIN bike_sys_permission p
WHERE r.role_code='WEB_OPERATOR' AND p.permission_code IN ('STOCK_READ','VEHICLE_READ','PART_READ','MASTER_READ','DATA_IMPORT','DATA_EXPORT')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

INSERT INTO bike_sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM bike_sys_role r JOIN bike_sys_permission p
WHERE r.role_code='WEB_AGENT' AND p.permission_code IN ('AGENT_INVENTORY_READ','AGENT_LOCK_WRITE','ORDER_READ','PURCHASE_INTENT_WRITE')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

CREATE TABLE IF NOT EXISTS bike_agent_purchase_intent (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    product_model_id BIGINT NOT NULL,
    intent_qty INT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    expire_time DATETIME(3) NULL,
    remark VARCHAR(500) NULL,
    created_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_bike_purchase_intent_agent_status (agent_id, status),
    CONSTRAINT fk_bike_purchase_intent_agent FOREIGN KEY (agent_id) REFERENCES bike_agent(id),
    CONSTRAINT fk_bike_purchase_intent_model FOREIGN KEY (product_model_id) REFERENCES bike_product_model(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
