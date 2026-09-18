INSERT INTO bike_system_config(config_key,config_value,remark)
VALUES ('inventory.lock.hours','48','代理商锁库存默认有效时长（小时）')
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),remark=VALUES(remark);
