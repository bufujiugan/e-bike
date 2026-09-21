-- 只注册可配置权限，不扩大任何既有岗位授权；管理员按实际职责授予。
INSERT INTO bike_sys_permission(permission_code,permission_name,permission_type)
VALUES('PRODUCTION_MANAGE','生产管理（SKU、标签、隔离处置、旧件复检）','API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type);
