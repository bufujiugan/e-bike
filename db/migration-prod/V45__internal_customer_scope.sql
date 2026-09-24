ALTER TABLE bike_sys_user ADD COLUMN customer_scope_version BIGINT NOT NULL DEFAULT 0 COMMENT '客户授权范围版本';
CREATE TABLE bike_user_customer_scope (
    user_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    updated_by BIGINT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(user_id,customer_id),
    KEY idx_customer_scope_customer(customer_id,user_id),
    CONSTRAINT fk_customer_scope_user FOREIGN KEY(user_id) REFERENCES bike_sys_user(id),
    CONSTRAINT fk_customer_scope_customer FOREIGN KEY(customer_id) REFERENCES bike_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部账号客户授权范围';
INSERT INTO bike_sys_permission(permission_code,permission_name,permission_type) VALUES
('CUSTOMER_SCOPE_ALL','访问全部客户业务范围','API'),
('CUSTOMER_SCOPE_MANAGE','管理内部客户授权范围','API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type);
-- Do not grant global scope to existing sales/operator roles.
-- Assign this independent role explicitly to staff who need all customers.
INSERT INTO bike_sys_role(role_code,role_name,enabled) VALUES('CUSTOMER_ALL','全部客户范围（需显式分配）',1)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name);
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p
WHERE r.role_code='CUSTOMER_ALL' AND p.permission_code='CUSTOMER_SCOPE_ALL'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
