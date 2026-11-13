# PDA 总码自动加入与 PI 扫码事务修复

## 变更与版本

- 分支：`codex/pi-business-flow-20261103`。
- PDA 提交：`dedd659f61b665282c858e6bb44968d2e47cd57e`，完整总码输入稳定 200ms 后自动校验加入，适用于按订单出库及 PI 多车型整批出库。仍需点击确认按钮才实际出库。
- 总码必须具有完整的车辆 ID 和 32 位令牌；半截输入不会自动请求。保留回车与“加入”作为手动重试入口，校验失败不无限重试。
- 校验期间阻止重复请求和出库提交；切换订单、模式或退出后丢弃旧校验结果。
- 后端提交：`865fefe2893b642665982441fee7a7faf5e74442`，为 `StockServiceImpl.validateStockOut` 补充事务，并在读取车辆前使用现有生产锁，与正式出库保持一致的锁顺序。接口、DTO、SQL 和出库条件均不变。

## 验收中发现的后端阻塞

PDA 更新后，不按回车已经能够触发校验；但在库车辆按 PI 关联订单校验时，服务器日志出现 `IllegalTransactionStateException: No existing transaction found for transaction marked with propagation 'mandatory'`。

调用链为 `StockServiceImpl.validateStockOut → PiShipmentGuard.requireReleased → PiBusinessServiceImpl.requireShipment → PiOrderBindingServiceImpl.requireConsistentFulfillment`。最后一个方法要求既有事务；原扫码校验没有事务。补充事务使履约检查可以正常执行，未放宽库存、订单、凭证或放行校验。校验事务只读取及加锁，不执行库存更新。

## 测试

- PDA TypeScript 检查与 Vite 构建通过。
- `tools/acceptance/pda-auto-scan.test.mjs` 5 项通过，使用实际 Vue 响应式逻辑与组件脚本，覆盖逐字输入、粘贴、自动与回车竞争、重复码、失败重试、切换订单、卸载取消、PI 模式及禁止自动提交出库。
- 后端 `StockServiceTest`、`PiShipmentGuardTest`、`OrderInventoryGuardTest` 合计 32 项通过，无失败或跳过。
- 新增 Spring 事务代理回归测试：模拟下游 MANDATORY 事务要求，验证校验成功、锁顺序、事务释放及不调用库存更新。
- 后端离线打包成功；与上一版本相比仅 `StockServiceImpl.class` 内容变化。发布时全部 878 个 JAR 文件条目逐项验证。

## 发布过程

- 2026-11-13 14:37:25（Asia/Shanghai）先发布 PDA 静态更新至 `C:\bike\releases\20261113-dedd659-pda-auto-scan`。此阶段未重启后端，3 个 PDA 文件散列校验通过。
- PDA 包 SHA-256：`aa08f7d4795ce38e8618cc7e6a3f75d0b7b59fad0356ddee8249601dce21324c`。
- 后续事务补丁使用 `20261113-865fefe-scan-transaction` 版本；保留已经更新的 PDA 和管理后台。
- 事务补丁于 2026-11-13 14:47:28（Asia/Shanghai）切换成功；当前入口为 `C:\bike\current`，旧入口保留为 `C:\bike\current-before-20261113-144654`。
- 备份目录：`C:\bike\backups\20261113-144654-scan-transaction`；发布前后业务快照一致，数据库仍为 V57、失败迁移 0。此时履约配置数量为 2，来自发布前已有业务状态。
- 线上 JAR SHA-256：`2c446b1375d1942c0c63190126580139e0e74e35ddd19d5f6ee25cf5803ad6fb`。容器元数据不同于本地原包，全部 878 个文件条目内容与本地构建一致。
- 后端发布 bundle SHA-256：`bd8dd6d2085987694f57581edb6e055834cb57cf0a727b8d1cea7c7ce24c4def`，58,985 字节；脚本 SHA-256：`92660cb7b4ca2bf9bd598f6a6f2c188d55157c11e14f0007be3a11215020fbcd`。
- 发布通过已登录的 ECS 云助手执行。首次静态发布的清单下载超时发生于切换前；随后通过云助手传入同一清单和脚本并核验散列后发布。
- 无数据库迁移，不删除历史发布目录或用户本地文件。后端切换前备份数据库、附件及配置，并对比发布前后业务快照。

## 线上验证与边界

- 静态发布后，公网管理后台 40 文件与 PDA 3 文件散列匹配；两站首页 200、健康 UP、匿名校验接口 401。
- 原问题车辆的追溯记录显示，其在本次更新前已于 2026-11-13 14:25:29 完成出库。后续验收再次输入该码，自动校验按既有规则拒绝重复出库。
- 线上验收只调用车辆校验、追溯和列表读取，不点击实际确认出库，不修改真实 PI、收付款或库存。
- 事务补丁上线后，用同一生产单的另一台在库车辆复测：只填入完整总码，不按 Enter、不点击“加入”，校验成功后输入框自动清空，“已扫描数量”变为 1 台，“确认批量出库”变为可用。随后刷新清除临时清单，未提交出库。
- 成功截图：`output/playwright/pda-auto-scan-success-20261113.jpg`。
- 14:52:48 最终服务器验收通过：三个服务均为 Running，50 个发布文件及 4 份备份散列匹配；两站首页 200、健康 UP、匿名业务接口 401；最新启动段错误数为 0，发布前后业务快照一致。报告保存在发布目录的 `verification-20261113.json`，本地副本位于 `tmp/deploy-scan-transaction-20261113/verification.json`。
- 浏览器验证不等于实体扫码枪或原生 PDA APK 验收；本次发布的是 PDA 网页。

## 证据与回退

本地发布证据分别位于 `tmp/deploy-pda-auto-scan-20261113/`、`tmp/deploy-scan-transaction-20261113/`。服务器发布目录保留清单与 `deployment-result.json`。验收截图只保留本地，不提交业务截图。

回退前停 Nginx 和后端，核实当前与旧入口为预期 junction，目标均位于 `C:\bike` 内；改名保留新入口，再将发布记录中的旧入口恢复为 `C:\bike\current`，启动后端、核验健康后恢复 Nginx。不用数据库备份覆盖发布后新增业务，不批量删除目录。若回退事务补丁，将恢复原 PI 扫码校验缺少事务的问题。
