# 普通订单有效限时占用出库修复发布

## 发布结果

2026-11-12 12:07:02（北京时间）完成后端发布，12:07:35 完成服务和资产复核，随后外网目标订单预校验通过。

- GitHub 仓库：`bufujiugan/e-bike`。
- 分支：`codex/pi-business-flow-20261103`。
- 已先推送并核验远程修复提交：`fd53ac6a42a2de8d680b6e9dd05b666b835e8764`。
- 阿里云实例：`i-2ze5xv8sh0zix3w4szuc`，通过实例 metadata 核验。
- 新版本：`C:\bike\releases\20261112-fd53ac6-timed-order-shipment`。
- 原版本：`C:\bike\releases\20261111-52ef913-role-account-guide`。
- `C:\bike\current` 已指向新版本。
- 后端包 SHA-256：`703349f787489ed7048fc8f660a56ce5e3c744cb9bae4519efb572a841a08b15`。

普通订单已确认、本单占用有效且车辆已入库时，可在车型、SKU、数量及库存保护校验通过后出库。已关联 PI 的订单保留 PI 放行规则。源码和回归细节见 `docs/fix-timed-order-shipment-20261112.md`。

## 发布范围与备份

仅发布后端。对比原发布清单的 878 个 JAR 条目，仅以下 5 个条目变化：`OrderInventoryGuard.class`、`PiShipmentGuard.class`、`SkuInventoryGuard.class`、`SkuInventoryMapper.class`、`mapper/SkuInventoryMapper.xml`。依赖、其他类及数据库迁移没有变化。

沿用现网 web、pda、deploy 的 50 个文件，切换前后逐个散列验证通过。工作区未提交的 PI 前端修改及附件未纳入本次发布。

- 备份目录：`C:\bike\backups\20261112-120633-timed-order-shipment`。
- 备份包含 `bike.sql`、`storage.zip`、`config.zip`、`services.zip`，均已复核 SHA-256；目录访问限于管理员和 SYSTEM。
- 旧入口保留为 `C:\bike\current-before-20261112-120633`。
- 停止入口和应用服务后备份，切换并健康检查通过后恢复入口。
- 发布前后业务快照一致；迁移状态 `migration=57:1`、`failed=0`。

## 验证

- 本地最终 Maven verify：400 项，397 通过，3 项因缺少外部验收环境跳过，0 失败或错误。相关真实 MySQL 回归 44 项通过，其中 6 项与最终 verify 重叠。
- `BikeMySQL`、`BikeServer`、`BikeNginx` 均 Running，健康状态 UP。
- 管理后台与 PDA 域名路由首页均返回 200，受保护接口匿名访问均返回 401。
- 本次启动日志 ERROR/Exception 数量为 0。
- 外网通过管理后台域名调用出库预校验，订单 `SOA9F7D12E99A646699C`（ID 11）、车辆 `YCB040163TAUM00001`（ID 17）返回 `valid=true`、`可办理出库`。
- 验收时订单仍为 CONFIRMED、TIMED_ACTIVE，数量 1、已出库 0；占用到期时间为 2026-11-14 10:56:29.097；车辆仍为已入库。
- 预校验前后订单详情和车辆追溯数据完全一致。未提交正式出库，未代替现场扫码及实物交付验收。

服务器发布证据位于新版本目录中的 `release-manifest.json`、`release-metadata.json`、`deployment-result.json`、`verification-20261112.json`。本地运行证据位于 `tmp/deploy-timed-20261112/`。

## 回退

本次无数据库迁移。需要回退时停止入口与后端服务，核实 current 仍指向上述新版本后，将该 junction 重命名保留，再将旧入口重命名为 current，启动后端并验证健康，再恢复入口。回退会恢复原来的限时占用出库限制。上线后产生的业务记录应保留，不使用发布前数据库备份覆盖。
