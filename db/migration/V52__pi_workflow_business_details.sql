ALTER TABLE bike_pi_workflow_step
    ADD COLUMN detail_json TEXT NULL COMMENT '步骤结构化业务明细' AFTER note;

CREATE TABLE bike_pi_workflow_update (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    pi_id BIGINT NOT NULL COMMENT 'PI主键',
    step_code VARCHAR(40) NOT NULL COMMENT '流程编码',
    detail_json TEXT NOT NULL COMMENT '结构化跟进明细',
    note VARCHAR(500) NULL COMMENT '补充说明',
    created_by BIGINT NULL COMMENT '操作人',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_bike_pi_workflow_update_pi (pi_id,step_code,created_at),
    CONSTRAINT fk_bike_pi_workflow_update_pi FOREIGN KEY (pi_id) REFERENCES bike_pi(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI流程跟进记录';
