ALTER TABLE bike_pi
    ADD COLUMN previous_revision_id BIGINT NULL COMMENT '上一商业修订主键' AFTER series_id,
    ADD KEY idx_bike_pi_previous_revision (previous_revision_id),
    ADD CONSTRAINT fk_bike_pi_previous_revision FOREIGN KEY (previous_revision_id) REFERENCES bike_pi(id);
ALTER TABLE bike_pi_series ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '系列乐观锁版本';
CREATE TABLE bike_pi_revision_request (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'PI修订请求主键', series_id BIGINT NOT NULL COMMENT 'PI系列主键',
 operator_id BIGINT NOT NULL COMMENT '操作者主键', request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等标识',
 payload_hash CHAR(64) NOT NULL COMMENT '请求摘要', pi_id BIGINT NOT NULL COMMENT '生成的修订主键',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', PRIMARY KEY(id),
 UNIQUE KEY uk_pi_revision_request(operator_id,request_id), KEY idx_pi_revision_request_series(series_id,created_at),
 CONSTRAINT fk_pi_revision_request_series FOREIGN KEY(series_id) REFERENCES bike_pi_series(id),
 CONSTRAINT fk_pi_revision_request_pi FOREIGN KEY(pi_id) REFERENCES bike_pi(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI商业修订幂等请求';
