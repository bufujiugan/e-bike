CREATE TABLE IF NOT EXISTS bike_pi (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    pi_no VARCHAR(64) NOT NULL COMMENT 'PI编号',
    revision INT NOT NULL DEFAULT 1 COMMENT '修订版本',
    customer_id BIGINT NOT NULL COMMENT '客户主键',
    customer_code VARCHAR(64) NOT NULL COMMENT '客户编码快照',
    customer_name VARCHAR(150) NOT NULL COMMENT '客户名称快照',
    agent_id BIGINT NULL COMMENT '代理商主键',
    trade_type VARCHAR(32) NOT NULL DEFAULT 'DOMESTIC' COMMENT '内销或外销类型',
    currency_code VARCHAR(16) NOT NULL DEFAULT 'CNY' COMMENT 'ISO币种代码',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '业务状态',
    payment_status VARCHAR(32) NOT NULL DEFAULT 'UNPAID' COMMENT '累计收款状态',
    release_status VARCHAR(32) NOT NULL DEFAULT 'BLOCKED' COMMENT '授权发货放行状态',
    planned_ship_date DATE NULL COMMENT '计划发货日期',
    subtotal DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '商品原价小计',
    discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '折扣金额',
    tax_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '税费金额',
    freight_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '运费金额',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT 'PI应付金额',
    deposit_required_amount DECIMAL(18,2) NOT NULL COMMENT '明确约定的保留库存收款金额',
    shipment_required_amount DECIMAL(18,2) NOT NULL COMMENT '明确约定的发货收款金额',
    document_snapshot JSON NULL COMMENT '商业条款与单据模板快照',
    source_order_id BIGINT NULL COMMENT '来源销售订单主键',
    remark VARCHAR(1000) NULL COMMENT '备注',
    created_by BIGINT NULL COMMENT '创建人',
    confirmed_by BIGINT NULL COMMENT '确认人',
    released_by BIGINT NULL COMMENT '放行人',
    released_at DATETIME(3) NULL COMMENT '授权放行时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE KEY uk_bike_pi_no_revision (pi_no,revision),
    UNIQUE KEY uk_bike_pi_source_order (source_order_id),
    KEY idx_bike_pi_customer_status (customer_id,status),
    KEY idx_bike_pi_agent_status (agent_id,status),
    CONSTRAINT fk_bike_pi_customer FOREIGN KEY (customer_id) REFERENCES bike_customer(id),
    CONSTRAINT fk_bike_pi_agent FOREIGN KEY (agent_id) REFERENCES bike_agent(id),
    CONSTRAINT fk_bike_pi_order FOREIGN KEY (source_order_id) REFERENCES bike_sales_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI单据与发布快照';

CREATE TABLE IF NOT EXISTS bike_pi_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    pi_id BIGINT NOT NULL COMMENT 'PI主键',
    sku_id BIGINT NOT NULL COMMENT '商品SKU主键',
    product_model_id BIGINT NOT NULL COMMENT '所属车型主键',
    sku_code VARCHAR(64) NOT NULL COMMENT 'SKU编码快照',
    product_name VARCHAR(200) NOT NULL COMMENT '商品名称快照',
    specification VARCHAR(500) NULL COMMENT '商品规格快照',
    model_name VARCHAR(100) NOT NULL COMMENT '车型名称快照',
    ordered_qty INT NOT NULL COMMENT '约定商品数量',
    unit_price DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '商品单价',
    discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '折扣金额',
    tax_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '税费金额',
    line_total DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '明细折税后金额',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    UNIQUE KEY uk_bike_pi_item_sku (pi_id,sku_id),
    CONSTRAINT fk_bike_pi_item_pi FOREIGN KEY (pi_id) REFERENCES bike_pi(id),
    CONSTRAINT fk_bike_pi_item_model FOREIGN KEY (product_model_id) REFERENCES bike_product_model(id),
    CONSTRAINT fk_bike_pi_item_sku FOREIGN KEY (sku_id) REFERENCES bike_production_sku(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI商品SKU明细快照';

CREATE TABLE IF NOT EXISTS bike_pi_payment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    pi_id BIGINT NOT NULL COMMENT 'PI主键',
    request_id VARCHAR(64) NOT NULL COMMENT '收款幂等请求标识',
    amount DECIMAL(18,2) NOT NULL COMMENT '到账金额',
    currency_code VARCHAR(16) NOT NULL COMMENT 'ISO币种代码',
    voucher_object_key VARCHAR(500) NULL COMMENT '收款凭证存储键',
    status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED' COMMENT '业务状态',
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '实际到账时间',
    confirmed_by BIGINT NULL COMMENT '确认人',
    remark VARCHAR(500) NULL COMMENT '备注',
    CONSTRAINT fk_bike_pi_payment_pi FOREIGN KEY (pi_id) REFERENCES bike_pi(id),
    KEY idx_bike_pi_payment_pi_time (pi_id,received_at),
    UNIQUE KEY uk_bike_pi_payment_request (pi_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI收款确认与幂等记录';

CREATE TABLE IF NOT EXISTS bike_pi_logistics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    pi_id BIGINT NOT NULL COMMENT 'PI主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '物流批次编号',
    carrier VARCHAR(128) NULL COMMENT '承运商',
    tracking_no VARCHAR(128) NULL COMMENT '运单号',
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNED' COMMENT '业务状态',
    shipped_at DATETIME(3) NULL COMMENT '物流发货时间',
    remark VARCHAR(500) NULL COMMENT '备注',
    created_by BIGINT NULL COMMENT '创建人',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    UNIQUE KEY uk_bike_pi_logistics_batch (pi_id,batch_no),
    CONSTRAINT fk_bike_pi_logistics_pi FOREIGN KEY (pi_id) REFERENCES bike_pi(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI物流批次';

INSERT INTO bike_sys_permission(permission_code,permission_name,permission_type) VALUES
('PI_READ','查看 PI','API'),('PI_WRITE','维护 PI','API'),('PI_PAYMENT_CONFIRM','确认 PI 收款','API'),('PI_RELEASE','放行 PI 发货','API'),('PI_LOGISTICS_WRITE','维护 PI 物流','API'),('PI_EXPORT','导出 PI 文件','API')
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type);
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p WHERE r.role_code='WEB_OPERATOR' AND p.permission_code IN ('PI_READ','PI_WRITE','PI_LOGISTICS_WRITE','PI_EXPORT') ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
INSERT INTO bike_sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p WHERE r.role_code='WEB_AGENT' AND p.permission_code='PI_READ' ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);



