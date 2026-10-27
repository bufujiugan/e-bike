CREATE TABLE bike_production_work_order (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '生产工单主键',
    request_id VARCHAR(64) NOT NULL COMMENT '创建请求幂等标识',
    work_order_no VARCHAR(64) NOT NULL COMMENT '生产工单号',
    sku_id BIGINT NOT NULL COMMENT '商品 SKU 主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '生产批次',
    planned_quantity INT NOT NULL COMMENT '计划生产数量',
    status VARCHAR(20) NOT NULL DEFAULT 'RELEASED' COMMENT '工单状态：RELEASED、PAUSED、COMPLETED',
    created_by VARCHAR(64) NOT NULL COMMENT '创建账号',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_work_order_request (request_id),
    UNIQUE KEY uk_production_work_order_no (work_order_no),
    KEY idx_production_work_order_status (status, updated_at),
    KEY idx_production_work_order_sku_batch (sku_id, batch_no),
    CONSTRAINT fk_production_work_order_sku FOREIGN KEY (sku_id) REFERENCES bike_production_sku(id),
    CONSTRAINT ck_production_work_order_quantity CHECK (planned_quantity > 0),
    CONSTRAINT ck_production_work_order_status CHECK (status IN ('RELEASED','PAUSED','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批量生产工单';

INSERT INTO bike_production_work_order(
    request_id, work_order_no, sku_id, batch_no, planned_quantity, status, created_by, created_at, updated_at)
SELECT
    CONCAT('MIGRATED-', t.sku_id, '-', LEFT(MD5(COALESCE(t.batch_no, '')), 24)),
    CONCAT('HIST-', t.sku_id, '-', LEFT(MD5(COALESCE(t.batch_no, '')), 16)),
    t.sku_id,
    COALESCE(t.batch_no, '历史未填写'),
    COUNT(*),
    CASE WHEN SUM(t.status = 'OPEN') > 0 THEN 'RELEASED' ELSE 'COMPLETED' END,
    'migration',
    MIN(t.created_at),
    MAX(t.updated_at)
FROM bike_production_task t
GROUP BY t.sku_id, t.batch_no;

ALTER TABLE bike_production_task
    ADD COLUMN work_order_id BIGINT NULL COMMENT '所属生产工单' AFTER id,
    ADD KEY idx_production_task_work_order (work_order_id, status, updated_at),
    ADD CONSTRAINT fk_production_task_work_order FOREIGN KEY (work_order_id) REFERENCES bike_production_work_order(id);

UPDATE bike_production_task t
JOIN bike_production_work_order w
  ON w.sku_id = t.sku_id
 AND w.request_id LIKE 'MIGRATED-%'
 AND (w.batch_no = t.batch_no OR (t.batch_no IS NULL AND w.batch_no = '历史未填写'))
SET t.work_order_id = w.id;
