CREATE TABLE bike_part_price (
    part_id BIGINT NOT NULL COMMENT '零部件主键',
    sellable TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否对客户展示并可售',
    unit_price DECIMAL(18,2) NULL COMMENT '客户价目单价',
    currency CHAR(3) NULL COMMENT 'ISO 4217 币种',
    PRIMARY KEY (part_id),
    KEY idx_bike_part_price_sellable (sellable, part_id),
    CONSTRAINT fk_bike_part_price_part FOREIGN KEY (part_id) REFERENCES bike_part(id),
    CONSTRAINT ck_bike_part_price_amount CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT ck_bike_part_price_currency CHECK ((unit_price IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_bike_part_price_sale CHECK (sellable = 0 OR unit_price IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='零部件客户价目';

INSERT INTO bike_sys_permission(permission_code, permission_name, permission_type)
VALUES ('PART_PRICING_MANAGE', '维护零件可售状态、单价与币种', 'API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name);

INSERT INTO bike_sys_role_permission(role_id, permission_id)
SELECT role.id, permission.id FROM bike_sys_role role
JOIN bike_sys_permission permission ON permission.permission_code='PART_PRICING_MANAGE'
WHERE role.role_code='SUPER_ADMIN'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
