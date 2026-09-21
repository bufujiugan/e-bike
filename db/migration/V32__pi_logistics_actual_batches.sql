ALTER TABLE bike_pi_logistics
    ADD stock_out_batch_id BIGINT NULL COMMENT '明确关联的真实出库批次',
    ADD link_type VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNLINKED' COMMENT '旧手工记录或ACTUAL_BATCH或PLAN_UNLINKED',
    ADD revision INT NOT NULL DEFAULT 1 COMMENT '物流资料修订号',
    ADD shipped_qty_snapshot INT NULL COMMENT '确认实际发运时的有效出库台数',
    ADD revoked_at DATETIME(3) NULL COMMENT '最近物流撤回时间',
    ADD revoke_reason VARCHAR(500) NULL COMMENT '最近物流撤回原因',
    ADD KEY idx_logistics_actual_batch(stock_out_batch_id,status),
    ADD CONSTRAINT fk_logistics_actual_batch FOREIGN KEY(stock_out_batch_id) REFERENCES bike_stock_out_batch(id);

CREATE TABLE bike_pi_logistics_history (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '物流修订历史标识',
    logistics_id BIGINT NOT NULL COMMENT '物流记录',
    revision INT NOT NULL COMMENT '保存后的修订号',
    action VARCHAR(32) NOT NULL COMMENT 'CREATE、UPDATE、WITHDRAW或MIGRATED_CURRENT',
    snapshot JSON NOT NULL COMMENT '该次物流字段及有效数量快照',
    operator_id BIGINT NULL COMMENT '操作者，迁移未知为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '历史记录实际保存时间',
    PRIMARY KEY(id), UNIQUE KEY uk_logistics_history(logistics_id,revision),
    CONSTRAINT fk_logistics_history FOREIGN KEY(logistics_id) REFERENCES bike_pi_logistics(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流修订留存，旧记录只保存迁移当时内容';
INSERT INTO bike_pi_logistics_history(logistics_id,revision,action,snapshot)
SELECT id,1,'MIGRATED_CURRENT',JSON_OBJECT('batchNo',batch_no,'carrier',carrier,'trackingNo',tracking_no,'status',status,'shippedAt',shipped_at,'remark',remark,'linkType','LEGACY_UNLINKED') FROM bike_pi_logistics;

CREATE TABLE bike_pi_logistics_request (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '物流命令标识',
    series_id BIGINT NOT NULL COMMENT 'PI系列及幂等范围',
    operator_id BIGINT NOT NULL COMMENT '操作用户',
    request_id VARCHAR(64) NOT NULL COMMENT '调用方幂等键',
    payload_hash CHAR(64) NOT NULL COMMENT '规范命令摘要',
    logistics_id BIGINT NOT NULL COMMENT '命令结果物流记录',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '命令提交时间',
    PRIMARY KEY(id), UNIQUE KEY uk_logistics_request(series_id,operator_id,request_id),
    CONSTRAINT fk_logistics_request_series FOREIGN KEY(series_id) REFERENCES bike_pi_series(id),
    CONSTRAINT fk_logistics_request_result FOREIGN KEY(logistics_id) REFERENCES bike_pi_logistics(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流创建修改撤回幂等记录';
