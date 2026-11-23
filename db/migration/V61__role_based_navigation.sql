-- 岗位默认配置。已有用户及其角色绑定由管理员在用户管理中维护。
INSERT INTO bike_sys_permission(permission_code, permission_name, permission_type) VALUES
('PI_FINANCE_READ', '查看 PI 收款与财务报表', 'API'),
('PDA_STOCK_LOOKUP', 'PDA 扫码查询整车库存与库位', 'API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name);

INSERT INTO bike_sys_role(role_code, role_name, enabled) VALUES
('BUSINESS_MANAGER', '业务管理员', 1),
('BUSINESS_STAFF', '业务员', 1),
('MANAGEMENT_VIEWER', '负责人／管理层', 1),
('WAREHOUSE_SUPERVISOR', '仓库主管', 1),
('DOCUMENT_CLERK', '文员／单据员', 1),
('FINANCE_VIEWER', '财务人员', 1),
('PDA_RECEIVING', '收货／入库人员', 1),
('PDA_PICKING', '拣货／出库人员', 1),
('PDA_INVENTORY_VIEWER', '库存查询人员', 1),
('PDA_STOCKTAKE', '盘点人员', 0),
('PDA_RELOCATION', '移库／上架人员', 0)
ON DUPLICATE KEY UPDATE role_code=VALUES(role_code);

INSERT INTO bike_sys_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM bike_sys_role r
JOIN bike_sys_permission p ON (
    (r.role_code = 'BUSINESS_MANAGER' AND p.permission_code IN (
        'DASHBOARD_READ', 'PRODUCTION_MANAGE', 'INVENTORY_READ', 'STOCK_READ', 'VEHICLE_READ',
        'TRACE_READ', 'PART_READ', 'PART_WRITE', 'MASTER_READ', 'CUSTOMER_PROFILE_WRITE',
        'ORDER_READ', 'ORDER_WRITE', 'AGENT_INVENTORY_READ', 'AGENT_LOCK_WRITE',
        'PI_READ', 'PI_WRITE', 'PI_LOGISTICS_WRITE', 'PI_EXPORT', 'PI_FINANCE_READ',
        'AFTER_SALES_READ', 'AFTER_SALES_WRITE', 'AFTER_SALES_REVIEW', 'AFTER_SALES_TECH',
        'AFTER_SALES_LOGISTICS', 'AFTER_SALES_SCOPE_ALL', 'CUSTOMER_SCOPE_ALL'
    ))
    OR (r.role_code = 'BUSINESS_STAFF' AND p.permission_code IN (
        'INVENTORY_READ', 'ORDER_READ', 'ORDER_WRITE', 'AGENT_INVENTORY_READ', 'AGENT_LOCK_WRITE',
        'PI_READ', 'PI_WRITE', 'PI_LOGISTICS_WRITE', 'PI_EXPORT'
    ))
    OR (r.role_code = 'MANAGEMENT_VIEWER' AND p.permission_code IN (
        'DASHBOARD_READ', 'INVENTORY_READ', 'STOCK_READ', 'ORDER_READ', 'PI_READ', 'CUSTOMER_SCOPE_ALL'
    ))
    OR (r.role_code = 'WAREHOUSE_SUPERVISOR' AND p.permission_code IN (
        'INVENTORY_READ', 'STOCK_READ', 'TRACE_READ'
    ))
    OR (r.role_code = 'DOCUMENT_CLERK' AND p.permission_code IN ('PART_READ', 'PART_WRITE', 'MASTER_READ', 'STOCK_READ'))
    OR (r.role_code = 'FINANCE_VIEWER' AND p.permission_code IN (
        'ORDER_READ', 'PI_READ', 'PI_FINANCE_READ', 'PI_EXPORT'
    ))
    OR (r.role_code = 'PDA_RECEIVING' AND p.permission_code IN ('STOCK_IN_OPERATE', 'TRACE_READ'))
    OR (r.role_code = 'PDA_PICKING' AND p.permission_code IN ('STOCK_OUT_OPERATE', 'TRACE_READ'))
    OR (r.role_code = 'PDA_INVENTORY_VIEWER' AND p.permission_code = 'PDA_STOCK_LOOKUP')
)
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
