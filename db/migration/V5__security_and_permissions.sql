ALTER TABLE bike_sys_user ADD COLUMN agent_id BIGINT NULL AFTER user_type;
ALTER TABLE bike_sys_user ADD COLUMN workstation_id BIGINT NULL AFTER agent_id;
ALTER TABLE bike_sys_user ADD KEY idx_bike_sys_user_agent (agent_id);
ALTER TABLE bike_sys_user ADD KEY idx_bike_sys_user_workstation (workstation_id);

INSERT INTO bike_sys_permission(permission_code, permission_name, permission_type) VALUES
('DASHBOARD_READ','查看 Dashboard','API'),
('ASSEMBLY_OPERATE','执行组装','API'),
('STOCK_IN_OPERATE','执行入库','API'),
('STOCK_OUT_OPERATE','执行出库','API'),
('INVENTORY_READ','查看库存','API'),
('ORDER_READ','查看订单','API'),
('ORDER_WRITE','维护订单','API'),
('AGENT_INVENTORY_READ','查看代理商库存','API'),
('AGENT_LOCK_WRITE','维护代理商锁库','API'),
('REWORK_OPERATE','执行返工','API'),
('TRACE_READ','查看追溯','API'),
('SYSTEM_CONFIG_WRITE','维护系统配置','API'),
('PERMISSION_WRITE','维护权限','API'),
('DATA_IMPORT','导入数据','API'),
('DATA_EXPORT','导出数据','API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type);

INSERT INTO bike_sys_user_role(user_id, role_id)
SELECT u.id, r.id FROM bike_sys_user u JOIN bike_sys_role r ON r.role_code='SUPER_ADMIN'
WHERE u.username='admin'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

INSERT INTO bike_sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM bike_sys_role r CROSS JOIN bike_sys_permission p WHERE r.role_code='SUPER_ADMIN'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
