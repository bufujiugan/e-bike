-- 生产库只初始化账号、角色和工位，不写入开发业务样例。
-- 管理员密码由 BIKE_ADMIN_PASSWORD 在应用启动后初始化，并保持首次登录改密状态。
INSERT INTO bike_sys_role(role_code, role_name, enabled)
VALUES ('SUPER_ADMIN', '超级管理员', 1),
       ('WEB_OPERATOR', 'Web业务员', 1),
       ('PDA_OPERATOR', 'PDA操作员', 1)
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), enabled = VALUES(enabled);

INSERT INTO bike_sys_user(username, password_hash, display_name, user_type, enabled, must_change_password)
VALUES ('admin', '$2a$10$7EqJtq98hPqEX7fNZaFWoO9n5fY2X4j7M7qK0X6L2Jf4rXn6m5k7e', '系统管理员', 'WEB_ADMIN', 1, 1)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), enabled = VALUES(enabled);

INSERT INTO bike_workstation(workstation_code, workstation_name, workstation_type)
VALUES ('WS-ASSEMBLY', '总装工位', 'ASSEMBLY'),
       ('WS-STOCK-IN', '仓库入库', 'STOCK_IN'),
       ('WS-STOCK-OUT', '仓库出库', 'STOCK_OUT'),
       ('WS-REWORK', '返工工位', 'REWORK')
ON DUPLICATE KEY UPDATE workstation_name = VALUES(workstation_name), enabled = 1;
