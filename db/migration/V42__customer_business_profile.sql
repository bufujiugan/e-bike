CREATE TABLE bike_customer_profile (
    customer_id BIGINT PRIMARY KEY,
    address VARCHAR(1000) NULL,
    country VARCHAR(100) NULL,
    email VARCHAR(254) NULL,
    preferred_currency VARCHAR(3) NULL,
    business_owner VARCHAR(150) NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_by BIGINT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_customer_profile_customer FOREIGN KEY(customer_id) REFERENCES bike_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO bike_sys_permission(permission_code,permission_name,permission_type)
VALUES('CUSTOMER_PROFILE_WRITE','维护客户商务资料','BUTTON');
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p ON p.permission_code='CUSTOMER_PROFILE_WRITE'
WHERE r.role_code='SUPER_ADMIN';
