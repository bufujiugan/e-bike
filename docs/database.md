# 数据库设计说明

数据库名称为 `bike`，业务表统一使用 `bike_` 前缀。应用通过 Flyway 执行 `bike-server/src/main/resources/db/migration` 下的版本脚本，当前测试库已执行到 V15。

## 第一版范围

- `bike_sys_user`、`bike_sys_role`、`bike_sys_permission`：账号、角色和权限。
- `bike_sys_user_role`、`bike_sys_role_permission`：多对多授权关系。
- `bike_workstation`：PDA 工位定义。
- `bike_product_model`：车型定义，代理商锁库存的业务维度。
- `bike_part`：零部件来料及当前占用状态。
- `bike_vehicle`：整车档案，`frame_sn` 为业务主 ID，`status` 使用规范定义的状态值。
- `bike_vehicle_part_binding`：当前绑定和历史绑定，历史记录不物理删除。
- `bike_inventory_lock`：按车型和数量锁定整车库存，不绑定具体车辆 SN。
- `bike_operation_log`：关键业务操作审计记录。
- V2 补充客户、供应商、代理商、仓库、库位、销售订单、订单明细、入库和出库记录。
- V3 补充订单车辆、返工记录、返工部件明细、登录日志、系统配置和撤销字段。
- V4 为开发环境样例数据迁移；生产环境建议改用独立 seed 流程，避免自动写入演示数据。
- V5 增加用户代理商/工位关联字段并初始化权限种子和管理员授权。
- V6 增加默认锁库存时长配置 `inventory.lock.hours=48`。
- V7 初始化 Web 业务员和 PDA 操作员的默认功能权限。
- V8 增加代理商采购意向、代理商角色和范围权限。
- V9 增加整车二维码生成时间 `qr_generated_at`。
- V10 回填历史整车二维码的生成时间。
- V11 增加车架和零部件 SN 的长度、正则和前缀校验配置；业务入口统一通过 `SnRuleService` 执行，配置缺失时使用受限默认规则。
- V12 仅修复仍处于首次登录状态的开发样例管理员密码哈希，已修改密码的账号不会被覆盖。
- V13 将订单车辆关联的唯一约束改为普通索引，允许撤销出库后再次出库，同时保留每次出库历史关联。
- V14 为全部业务表设置表注释，并为全部业务字段设置中文数据字典注释。
- V15 为 Flyway 内部 `bike_schema_history` 表及其字段设置注释，确保所有 `bike_` 表均有完整注释。

## 并发和唯一性

`bike_part.part_sn`、`bike_vehicle.frame_sn`、`bike_vehicle.vehicle_qr_token` 使用唯一约束。绑定表通过生成列和唯一索引保证同一零部件只能有一条当前绑定、同一车辆同一部件类型只能有一条当前绑定；解绑历史使用 `is_current = 0` 保留。

库存锁定的扣减和释放必须在 Service 事务中配合原子更新或锁机制实现。V1-V3 只创建结构，V4 仅用于当前开发环境样例数据验证。
