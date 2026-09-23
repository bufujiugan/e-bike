ALTER TABLE bike_pi_payment
    ADD COLUMN payment_method VARCHAR(32) NULL COMMENT '到账方式，历史未知为空',
    ADD COLUMN payment_method_note VARCHAR(200) NULL COMMENT '其他到账方式说明',
    ADD COLUMN recorded_at DATETIME(3) NULL COMMENT '系统登记时间，历史未知为空',
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 COMMENT '更正乐观版本',
    ADD COLUMN reversal_request_id VARCHAR(64) NULL COMMENT '本记录撤回幂等标识',
    ADD COLUMN reversal_reason VARCHAR(500) NULL COMMENT '错误登记撤回原因，不代表退款',
    ADD COLUMN reversed_by BIGINT NULL COMMENT '撤回人',
    ADD COLUMN reversed_at DATETIME(3) NULL COMMENT '撤回登记时间';

INSERT INTO bike_sys_permission(permission_code,permission_name,permission_type)
VALUES('PI_PAYMENT_REVERSE','撤回PI错误收款登记','BUTTON');
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p ON p.permission_code='PI_PAYMENT_REVERSE'
WHERE r.role_code='SUPER_ADMIN';
