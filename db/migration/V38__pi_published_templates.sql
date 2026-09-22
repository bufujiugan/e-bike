CREATE TABLE bike_pi_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_code VARCHAR(64) NOT NULL,
    template_version VARCHAR(32) NOT NULL,
    trade_type VARCHAR(16) NOT NULL,
    layout_code VARCHAR(32) NOT NULL COMMENT 'DOMESTIC_V1/EXPORT_V1 built-in renderers only',
    seller_name VARCHAR(200) NOT NULL,
    seller_address VARCHAR(1000) NOT NULL,
    seller_contact VARCHAR(300) NOT NULL,
    bank_name VARCHAR(200) NOT NULL,
    bank_account VARCHAR(200) NOT NULL,
    beneficiary VARCHAR(200) NOT NULL,
    publish_reason VARCHAR(500) NOT NULL,
    published_by BIGINT NULL,
    published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_pi_template_version(template_code,template_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO bike_sys_permission(permission_code,permission_name,permission_type)
VALUES('PI_TEMPLATE_MANAGE','发布PI模板及银行资料','BUTTON');
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p ON p.permission_code='PI_TEMPLATE_MANAGE'
WHERE r.role_code='SUPER_ADMIN';
