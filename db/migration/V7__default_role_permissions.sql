INSERT INTO bike_sys_permission(permission_code,permission_name,permission_type) VALUES
('PART_WRITE','维护零部件','API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name);
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p ON p.permission_code IN ('DASHBOARD_READ','INVENTORY_READ','ORDER_READ','TRACE_READ','AGENT_INVENTORY_READ') WHERE r.role_code='WEB_OPERATOR'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p ON p.permission_code IN ('ASSEMBLY_OPERATE','STOCK_IN_OPERATE','STOCK_OUT_OPERATE','REWORK_OPERATE','TRACE_READ') WHERE r.role_code='PDA_OPERATOR'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
