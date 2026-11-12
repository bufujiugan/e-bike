# 普通销售订单按有效限时占用出库

日期：2026-11-12。基于 `codex/pi-business-flow-20261103` 的 `52ef913`。实际提交与上线结果以对应部署记录为准。

## 业务规则与问题

普通销售订单已确认、本单库存占用有效、车辆已入库，且车型、SKU、剩余数量与库存保护校验通过时，可按订单直接出库。

排查订单 `SOA9F7D12E99A646699C` 时，线上车辆已于 2026-11-12 10:55:03 入库。订单处于 `TIMED_ACTIVE`，占用截止时间为 2026-11-14 10:56:29，但出库履约快照没有计算 SKU 限时占用的本单数量，错误地拒绝了该订单。后续 PI 校验还会强制未关联 PI 的订单建立 PI。

## 修改范围

- `bike-server/src/main/java/com/bike/server/order/OrderInventoryGuard.java`：限时订单使用包含所有有效占用的车型库存快照，本单数量加回，检查占用有效性和实际可出数量；保留已付款订单原有优先规则。
- `bike-server/src/main/java/com/bike/server/stock/SkuInventoryGuard.java`：限时订单的 SKU 库存检查使用相同口径，避免车型总量充足时挤占其他同 SKU 占用。
- `bike-server/src/main/java/com/bike/server/stock/SkuInventoryMapper.java`、`bike-server/src/main/resources/mapper/SkuInventoryMapper.xml`：增加按订单读取占用保留方式的方法，SQL 保持在 Mapper.xml。
- `bike-server/src/main/java/com/bike/server/pi/PiShipmentGuard.java`：未关联 PI 的普通订单允许按本单有效占用出库；缺少有效占用仍拦截；已关联 PI 的订单继续执行客户、放行、SKU、仓库等原有规则。

没有变更 Controller API、DTO、数据库结构或生产数据。正式出库沿用生产互斥锁、订单与车辆锁、条件更新及原事务。新增规则同时用于扫码预校验和最终提交，最终提交重新检查。

## 验证方式

- `OrderInventoryGuardTest`、`PiShipmentGuardTest`：有效限时占用、本单数量、其他占用、过期、无 PI 且无有效占用、关联 PI 放行。
- `TimedReservationShipmentMysqlTest`：真实 MySQL 下执行校验、正式出库、撤销恢复、过期与释放拦截、车型与 SKU 库存保护、批量失败回滚、并发重复出库。
- `PaidOrderReservationMysqlTest`、`PiMysqlTest`、`SkuCommitmentMysqlTest`、`PaymentPriorityMysqlTest`：回归收款占用、PI 流程、SKU 承诺和付款优先。
- 修正相关旧测试夹具：补充履约快照所需的 `scope_type`，库存列断言包含现有的车型、仓库及库位 ID 列。

测试数据库为本机独立库 `bike_timed_shipment_test_20261112`。构建在 `tmp/timed-order-shipment-20261112/` 的隔离源码副本中执行，未使用线上业务库做写入测试。

验证结果：

- 最终离线 Maven `verify` 成功：运行 400 项，397 项通过、3 项外部接口验收因环境未配置跳过，0 失败、0 错误。覆盖全部非 MySQL 测试类及新增 6 项真实 MySQL 出库测试。
- 相关 MySQL 回归共 44 项通过：收款占用 11 项、PI 流程 9 项、SKU 承诺 11 项、付款优先 7 项、限时占用出库 6 项。新增 6 项与上述最终构建有重叠，不重复累计。
- 原有测试夹具及断言修正后重跑通过；最终构建使用的 13 个变更源码与测试文件已逐个校验散列，与工作区一致。
- `git diff --check` 通过。

日志：`tmp/timed-order-shipment-20261112/final-verify.log`、`mysql-recheck.log`。付款优先 7 项的通过结果保留在首次运行的 `mysql-tests.log`，该次运行中的其他失败已在修正夹具后重跑通过。

后端包：`tmp/timed-order-shipment-20261112/final/bike-server/target/bike-server-0.1.0-SNAPSHOT.jar`。

SHA-256：`703349f787489ed7048fc8f660a56ce5e3c744cb9bae4519efb572a841a08b15`。

## 生效与回退

需要发布后端后才在线上生效，无数据库迁移。回退后端会恢复原来的出库限制；发布后产生的出库记录应保留，不应使用旧数据库备份覆盖。
