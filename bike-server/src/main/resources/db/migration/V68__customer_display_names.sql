UPDATE bike_sys_role
SET role_name = 'Web客户业务'
WHERE role_code = 'WEB_AGENT' AND role_name = 'Web代理商';

UPDATE bike_sys_permission
SET permission_name = '查看客户库存'
WHERE permission_code = 'AGENT_INVENTORY_READ' AND permission_name = '查看代理商库存';

UPDATE bike_sys_permission
SET permission_name = '维护客户锁库'
WHERE permission_code = 'AGENT_LOCK_WRITE' AND permission_name = '维护代理商锁库';

UPDATE bike_system_config
SET remark = '客户锁库默认有效时长（小时）'
WHERE config_key = 'inventory.lock.hours' AND remark = '代理商锁库存默认有效时长（小时）';
