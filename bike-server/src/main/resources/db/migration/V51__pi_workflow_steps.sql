CREATE TABLE bike_pi_workflow_step (
    pi_id BIGINT NOT NULL COMMENT 'PI主键',
    step_order INT NOT NULL COMMENT '流程顺序',
    step_code VARCHAR(40) NOT NULL COMMENT '流程编码',
    step_name VARCHAR(120) NOT NULL COMMENT '流程名称',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/COMPLETED',
    completed_by BIGINT NULL COMMENT '完成操作人',
    completed_at DATETIME(3) NULL COMMENT '完成时间',
    note VARCHAR(500) NULL COMMENT '流程备注',
    PRIMARY KEY (pi_id, step_order),
    UNIQUE KEY uk_bike_pi_workflow_step_code (pi_id, step_code),
    CONSTRAINT fk_bike_pi_workflow_step_pi FOREIGN KEY (pi_id) REFERENCES bike_pi(id),
    CONSTRAINT ck_bike_pi_workflow_step_status CHECK (status IN ('PENDING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI业务流程步骤';

CREATE INDEX idx_bike_pi_workflow_step_status ON bike_pi_workflow_step(status, step_order);
