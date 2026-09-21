CREATE TABLE bike_after_sales_company_profile (
    agent_id BIGINT PRIMARY KEY COMMENT '经销商公司ID',
    contact_name VARCHAR(100) NULL COMMENT '默认联系人',
    contact_phone VARCHAR(64) NULL COMMENT '默认联系电话',
    contact_email VARCHAR(254) NULL COMMENT '默认Email',
    country_code CHAR(2) NULL COMMENT 'ISO两位国家代码',
    region VARCHAR(100) NULL COMMENT '省或州',
    city VARCHAR(100) NULL COMMENT '城市',
    address_line VARCHAR(500) NULL COMMENT '详细地址',
    postal_code VARCHAR(32) NULL COMMENT '邮政编码',
    updated_by BIGINT NULL COMMENT '最后修改人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
    CONSTRAINT fk_bike_after_sales_profile_agent FOREIGN KEY(agent_id) REFERENCES bike_agent(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经销商售后联系资料';

CREATE TABLE bike_after_sales_sales_agent (
    sales_user_id BIGINT NOT NULL COMMENT '内部销售用户ID',
    agent_id BIGINT NOT NULL COMMENT '负责经销商公司ID',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否有效',
    updated_by BIGINT NULL COMMENT '最后修改人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
    PRIMARY KEY(sales_user_id,agent_id),
    KEY idx_after_sales_sales_agent(agent_id,enabled),
    CONSTRAINT ck_bike_after_sales_sales_enabled CHECK(enabled IN(0,1)),
    CONSTRAINT fk_bike_after_sales_sales_user FOREIGN KEY(sales_user_id) REFERENCES bike_sys_user(id),
    CONSTRAINT fk_bike_after_sales_sales_agent FOREIGN KEY(agent_id) REFERENCES bike_agent(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售负责经销商范围';

CREATE TABLE bike_after_sales_case_contact (
    case_id BIGINT PRIMARY KEY COMMENT '售后案例ID',
    company_name VARCHAR(150) NULL COMMENT '创建时公司名称快照',
    contact_name VARCHAR(100) NULL COMMENT '案例联系人快照',
    contact_phone VARCHAR(64) NULL COMMENT '案例联系电话快照',
    contact_email VARCHAR(254) NULL COMMENT '案例Email快照',
    country_code CHAR(2) NULL COMMENT '案例国家快照',
    region VARCHAR(100) NULL COMMENT '案例省州快照',
    city VARCHAR(100) NULL COMMENT '案例城市快照',
    address_line VARCHAR(500) NULL COMMENT '案例详细地址快照',
    postal_code VARCHAR(32) NULL COMMENT '案例邮编快照',
    end_customer_purchase_date DATE NULL COMMENT '最终客户购买日期',
    snapshot_source VARCHAR(32) NOT NULL COMMENT 'PROFILE、MASTER、MANUAL或MIGRATED_CURRENT',
    assignee_name VARCHAR(200) NULL COMMENT '派单时负责人姓名及账号快照',
    CONSTRAINT ck_bike_after_sales_contact_source CHECK(snapshot_source IN('PROFILE','MASTER','MANUAL','MIGRATED_CURRENT')),
    CONSTRAINT fk_bike_after_sales_contact_case FOREIGN KEY(case_id) REFERENCES bike_after_sales_case(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后案例联系资料历史快照';

INSERT INTO bike_after_sales_case_contact(case_id,company_name,contact_name,contact_phone,snapshot_source,assignee_name)
SELECT c.id,COALESCE(a.agent_name,cu.customer_name),COALESCE(a.contact_name,cu.contact_name),COALESCE(a.contact_phone,cu.contact_phone),'MIGRATED_CURRENT',CONCAT(u.display_name,' (',u.username,')')
FROM bike_after_sales_case c LEFT JOIN bike_agent a ON a.id=c.agent_id LEFT JOIN bike_customer cu ON cu.id=c.customer_id LEFT JOIN bike_sys_user u ON u.id=c.assignee_id;

CREATE INDEX idx_after_sales_assignee_status ON bike_after_sales_case(assignee_id,status,updated_at);

INSERT INTO bike_sys_permission(permission_code,permission_name,permission_type) VALUES
('AFTER_SALES_SCOPE_ALL','查看全部售后公司范围','API'),
('AFTER_SALES_COMPANY_MANAGE','维护售后公司资料及销售范围','API'),
('AFTER_SALES_ASSIGN','分配售后负责人','API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type);

-- Preserve existing operational roles only. READ-only roles are intentionally not granted global scope.
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT DISTINCT rp.role_id,scope.id FROM bike_sys_role_permission rp
JOIN bike_sys_permission old_permission ON old_permission.id=rp.permission_id
JOIN bike_sys_permission scope ON scope.permission_code='AFTER_SALES_SCOPE_ALL'
WHERE old_permission.permission_code IN('AFTER_SALES_REVIEW','AFTER_SALES_TECH','AFTER_SALES_LOGISTICS')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

INSERT INTO bike_sys_role(role_code,role_name,enabled) VALUES('AFTER_SALES_SALES','售后销售只读',1)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name);
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p
WHERE r.role_code='AFTER_SALES_SALES' AND p.permission_code='AFTER_SALES_READ'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
