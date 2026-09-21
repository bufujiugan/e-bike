CREATE TABLE bike_agent_model_access (
  agent_id BIGINT NOT NULL COMMENT '代理商主键',
  model_id BIGINT NOT NULL COMMENT '允许查看和锁定的车型主键',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '授权是否启用',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后授权变更时间',
  PRIMARY KEY(agent_id,model_id),
  CONSTRAINT fk_agent_model_access_agent FOREIGN KEY(agent_id) REFERENCES bike_agent(id),
  CONSTRAINT fk_agent_model_access_model FOREIGN KEY(model_id) REFERENCES bike_product_model(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理商独立车型查看及锁库授权';

-- 仅继承迁移时仍有效的旧访问范围；不从已过期/已释放历史扩大授权。
INSERT INTO bike_agent_model_access(agent_id,model_id)
SELECT DISTINCT l.agent_id,l.product_model_id FROM bike_inventory_lock l
JOIN bike_agent a ON a.id=l.agent_id AND a.status='ENABLED'
JOIN bike_product_model m ON m.id=l.product_model_id AND m.enabled=1
WHERE l.status IN ('LOCKED','PARTIAL_CONVERTED') AND l.expire_time>NOW()
AND l.lock_qty>l.released_qty+l.converted_qty;
