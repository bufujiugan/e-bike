-- Flyway 元数据表也补充注释，便于数据库字典完整展示。
ALTER TABLE bike_schema_history COMMENT='Flyway 数据库迁移版本历史表',
    MODIFY COLUMN installed_rank INT NOT NULL COMMENT '迁移安装顺序',
    MODIFY COLUMN version VARCHAR(50) NULL COMMENT '迁移版本号',
    MODIFY COLUMN description VARCHAR(200) NOT NULL COMMENT '迁移描述',
    MODIFY COLUMN type VARCHAR(20) NOT NULL COMMENT '迁移类型',
    MODIFY COLUMN script VARCHAR(1000) NOT NULL COMMENT '迁移脚本名称',
    MODIFY COLUMN checksum INT NULL COMMENT '迁移脚本校验和',
    MODIFY COLUMN installed_by VARCHAR(100) NOT NULL COMMENT '执行迁移的数据库用户',
    MODIFY COLUMN installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '迁移执行时间',
    MODIFY COLUMN execution_time INT NOT NULL COMMENT '迁移执行耗时（毫秒）',
    MODIFY COLUMN success TINYINT(1) NOT NULL COMMENT '迁移是否成功：1 是，0 否';
