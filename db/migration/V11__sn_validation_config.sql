INSERT INTO bike_system_config(config_key, config_value, remark)
VALUES
    ('sn.frame.min-length', '3', '车架 SN 最小长度'),
    ('sn.frame.max-length', '128', '车架 SN 最大长度'),
    ('sn.frame.regex', '^[A-Z0-9][A-Z0-9_-]{2,127}$', '车架 SN 格式（正则表达式）'),
    ('sn.part.min-length', '3', '零部件 SN 最小长度'),
    ('sn.part.max-length', '128', '零部件 SN 最大长度'),
    ('sn.part.regex', '^[A-Z0-9][A-Z0-9_-]{2,127}$', '零部件 SN 格式（正则表达式）')
ON DUPLICATE KEY UPDATE remark = VALUES(remark);
