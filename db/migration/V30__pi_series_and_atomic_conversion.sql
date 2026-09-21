CREATE TABLE bike_pi_series (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'PI系列标识',
    pi_no VARCHAR(64) NOT NULL COMMENT '稳定业务编号',
    current_pi_id BIGINT NOT NULL COMMENT '当前执行修订标识',
    next_revision INT NOT NULL DEFAULT 2 COMMENT '下一个可分配修订号',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '系列创建时间',
    PRIMARY KEY (id), UNIQUE KEY uk_pi_series_no (pi_no),
    CONSTRAINT fk_pi_series_current FOREIGN KEY (current_pi_id) REFERENCES bike_pi(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI稳定业务系列';

ALTER TABLE bike_pi ADD COLUMN series_id BIGINT NULL COMMENT '所属PI业务系列' AFTER id;
INSERT INTO bike_pi_series(pi_no,current_pi_id,next_revision,created_at)
SELECT pi_no,id,revision+1,created_at FROM bike_pi;
UPDATE bike_pi p JOIN bike_pi_series s ON s.current_pi_id=p.id SET p.series_id=s.id;
ALTER TABLE bike_pi ADD KEY idx_pi_series (series_id),
    ADD CONSTRAINT fk_pi_series FOREIGN KEY (series_id) REFERENCES bike_pi_series(id);

CREATE TABLE bike_pi_order_link (
    order_id BIGINT NOT NULL COMMENT '履约订单，唯一归属一个PI系列',
    series_id BIGINT NOT NULL COMMENT '所属PI系列',
    created_pi_id BIGINT NOT NULL COMMENT '首次关联的商业修订',
    link_type VARCHAR(32) NOT NULL COMMENT 'SKU_CONVERSION或LEGACY_SOURCE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '关联建立时间',
    PRIMARY KEY (order_id), KEY idx_pi_link_series (series_id,order_id),
    CONSTRAINT fk_pi_link_order FOREIGN KEY (order_id) REFERENCES bike_sales_order(id),
    CONSTRAINT fk_pi_link_series FOREIGN KEY (series_id) REFERENCES bike_pi_series(id),
    CONSTRAINT fk_pi_link_revision FOREIGN KEY (created_pi_id) REFERENCES bike_pi(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PI系列履约订单关联';
INSERT INTO bike_pi_order_link(order_id,series_id,created_pi_id,link_type,created_at)
SELECT source_order_id,series_id,id,'LEGACY_SOURCE',created_at FROM bike_pi WHERE source_order_id IS NOT NULL;

CREATE TABLE bike_pi_conversion_request (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '原子转换请求标识',
    operator_id BIGINT NOT NULL COMMENT '请求操作者',
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等标识',
    payload_hash CHAR(64) NOT NULL COMMENT '规范化请求摘要',
    pi_id BIGINT NOT NULL COMMENT '生成的PI草稿标识',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '转换提交时间',
    PRIMARY KEY (id), UNIQUE KEY uk_pi_conversion_request(operator_id,request_id),
    CONSTRAINT fk_pi_conversion_pi FOREIGN KEY (pi_id) REFERENCES bike_pi(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU锁定生成订单及PI的原子请求';
