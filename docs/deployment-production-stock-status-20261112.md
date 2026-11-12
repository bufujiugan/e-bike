# 生产单库存状态标识发布

2026-11-12 14:27:36（北京时间）完成部署，随后完成服务、线上 API 和浏览器验收。

## 发布信息

- GitHub：`bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`。
- 功能提交 `9c5547c535fbdd3be3dc4a5a5d528e11fe591f06`，先推送并核对远程 SHA，再发布。
- 阿里云实例 `i-2ze5xv8sh0zix3w4szuc`。
- 新版本 `C:\bike\releases\20261112-9c5547c-production-stock-status`，current 已切换。
- 上一版本 `C:\bike\releases\20261112-58efaf3-pi-template-adjustment`；旧入口保留为 `C:\bike\current-before-20261112-142709`。
- JAR SHA-256：`da183bcf74c9d4f0e35d97810e79f2e7c75a3f320c7b4b7f4ec14a6a5658cc51`。
- 发布包 SHA-256：`74089553ed8b1a2a74f6a3a4b85db24d669b5b5d4ab96c29ae0fcd79ebf1cb10`。

部署完整 web 构建及后端 JAR，保留现网 PDA 和 deploy 目录。后端与上一版本仅 `mapper/ProductionMapper.xml` 不同，保留此前有效锁定订单出库及模板调整功能。接口只新增车辆库存字段，无数据库迁移。

## 备份和一致性

备份目录 `C:\bike\backups\20261112-142709-production-stock-status`，包含数据库、storage、config、services，四份备份散列均已核验。发布前后业务快照一致，迁移仍为 V57、失败数为 0。新版本 50 个 web/pda/deploy 文件散列通过，3 个服务均 Running，两站首页 200、健康 UP，受保护接口匿名访问 401，启动日志无 ERROR/Exception。

## 验证结果

- 前端类型检查和生产构建通过；隔离副本后端 verify 609 项，391 通过、218 项因外部环境未配置跳过，0 失败、0 错误。
- 本机独立 MySQL 生产模块 5 项回归全部通过，覆盖同一任务的待入库、已入库和已出库字段更新。
- 浏览器拦截模拟验证：已入库／已出库隐藏表单、待入库显示表单、模拟提交后自动刷新、切换后重新选择、状态未知及详情读取失败阻止入库、390px 手机布局。未向线上提交模拟业务写入。
- 线上实际检查生产单 `WO-20261112-103507-5832D6`、车辆 `YCB040163TAUM00001`：列表和详情均返回 vehicleStatus=2，与车辆档案一致；仓库为 `仓库-2026110502`、库位为 `库位-2026110502`、入库时间为 `2026-11-12 10:55:03`。
- 线上浏览器显示“库存：已入库”和“生产：四码已完成”，已入库说明、实际仓库／库位／时间均可见，“确认本车入库”表单不存在；刷新车辆列表后仍显示正确状态。
- 验收未实际办理入库或出库，未修改业务记录。

源码与兼容说明见 `docs/production-stock-status-20261112.md`。本地证据位于 `tmp/production-stock-status-20261112/` 和 `tmp/deploy-stock-status-20261112/`，截图留在本地 output/playwright。服务器证据位于新版本目录内的发布清单、metadata、deployment-result 和 verification 文件。

## 回退

停止入口和后端，核实 current 仍指向本次版本后重命名保留，恢复旧入口，启动后端并验证健康，再恢复入口。需一起回退前后端：新前端连接旧后端缺少库存字段时会阻止入库。不得用发布前数据库备份覆盖上线后的业务数据。
