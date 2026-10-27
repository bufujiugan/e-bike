ALTER TABLE bike_production_sku
    ADD COLUMN created_at DATETIME(3) NULL DEFAULT NULL COMMENT '创建时间，历史未记录为空',
    ADD COLUMN updated_at DATETIME(3) NULL DEFAULT NULL COMMENT '修改时间，历史未记录为空';
ALTER TABLE bike_production_sku
    MODIFY COLUMN created_at DATETIME(3) NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，历史未记录为空',
    MODIFY COLUMN updated_at DATETIME(3) NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间，历史未记录为空';

ALTER TABLE bike_production_template
    ADD COLUMN created_at DATETIME(3) NULL DEFAULT NULL COMMENT '创建时间，历史未记录为空',
    ADD COLUMN updated_at DATETIME(3) NULL DEFAULT NULL COMMENT '修改时间，历史未记录为空';
ALTER TABLE bike_production_template
    MODIFY COLUMN created_at DATETIME(3) NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，历史未记录为空',
    MODIFY COLUMN updated_at DATETIME(3) NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间，历史未记录为空';

ALTER TABLE bike_production_issue ADD COLUMN updated_at DATETIME(3) NULL COMMENT '修改时间';
UPDATE bike_production_issue SET updated_at=COALESCE(resolved_at,created_at) WHERE updated_at IS NULL;
ALTER TABLE bike_production_issue MODIFY COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间';

ALTER TABLE bike_production_print ADD COLUMN updated_at DATETIME(3) NULL COMMENT '修改时间';
UPDATE bike_production_print SET updated_at=COALESCE(finished_at,claimed_at,created_at) WHERE updated_at IS NULL;
ALTER TABLE bike_production_print MODIFY COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间';

ALTER TABLE bike_production_scan_failure ADD COLUMN updated_at DATETIME(3) NULL COMMENT '修改时间';
UPDATE bike_production_scan_failure SET updated_at=created_at WHERE updated_at IS NULL;
ALTER TABLE bike_production_scan_failure MODIFY COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间';

ALTER TABLE bike_inventory_lock ADD COLUMN updated_at DATETIME(3) NULL COMMENT '修改时间';
UPDATE bike_inventory_lock SET updated_at=COALESCE(released_at,created_at) WHERE updated_at IS NULL;
ALTER TABLE bike_inventory_lock MODIFY COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间';

ALTER TABLE bike_system_config ADD COLUMN created_at DATETIME(3) NULL DEFAULT NULL COMMENT '创建时间，历史未记录为空' AFTER updated_by;
ALTER TABLE bike_system_config MODIFY COLUMN created_at DATETIME(3) NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，历史未记录为空';

CREATE INDEX idx_production_sku_created ON bike_production_sku(created_at,id);
CREATE INDEX idx_production_template_created ON bike_production_template(created_at,id);
CREATE INDEX idx_production_issue_created ON bike_production_issue(created_at,id);
CREATE INDEX idx_production_print_created ON bike_production_print(created_at,id);
CREATE INDEX idx_scan_failure_created ON bike_production_scan_failure(created_at,id);
CREATE INDEX idx_inventory_lock_created ON bike_inventory_lock(created_at,id);
CREATE INDEX idx_production_work_order_created ON bike_production_work_order(created_at,id);
CREATE INDEX idx_production_task_created ON bike_production_task(created_at,id);
CREATE INDEX idx_system_config_created ON bike_system_config(created_at,id);
