ALTER TABLE bike_order_reservation
    ADD COLUMN hold_mode VARCHAR(16) NOT NULL DEFAULT 'TIMED' COMMENT '占用方式：TIMED限时、PAID收款达标待人工关闭、RELEASED已释放',
    ADD COLUMN payment_hold_pi_id BIGINT NULL COMMENT '转为收款达标保留的PI主键',
    ADD COLUMN payment_held_at DATETIME(3) NULL COMMENT '数据库确认保留时间',
    ADD COLUMN payment_held_by BIGINT NULL COMMENT '保留确认人',
    ADD COLUMN payment_held_amount DECIMAL(18,2) NULL COMMENT '转保留时已确认累计收款快照',
    ADD COLUMN released_at DATETIME(3) NULL COMMENT '主动释放占用时间',
    ADD KEY idx_reservation_hold_expiry (hold_mode,expires_at,expiry_audited_at),
    ADD CONSTRAINT fk_reservation_payment_pi FOREIGN KEY (payment_hold_pi_id) REFERENCES bike_pi(id),
    ADD CONSTRAINT ck_reservation_hold_mode CHECK (hold_mode IN ('TIMED','PAID','RELEASED'));

-- 旧记录全部保留原限时口径；不依据现有收款倒推或复活已经到期的占用。
-- PAID表示已确认满足该PI约定的保留库存条件，剩余占用由取消/关闭/实际出库处理。
