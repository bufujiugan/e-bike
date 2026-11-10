# PI 本机草稿与列表 ID 发布记录

2026-11-10 17:33:04（Asia/Shanghai）完成服务器版本切换，随后完成接口和浏览器验收。

## 发布版本

- 仓库：`bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`。
- 应用提交：`4c121ac7c6139e67b0a68fc1cc54e8762f816bac`。
- 发布后端及管理后台：PI 本机草稿自动/手动保存、退出保留和恢复；通用及独立业务列表 ID 展示。
- PDA 网站使用已验证的原有构建，3 个文件与发布前完全一致。没有数据库迁移、账号密码变更或原生 APK 发布。

## 连接、备份与切换

- 实例：`i-2ze5xv8sh0zix3w4szuc`，公网 `59.110.166.139`。
- 使用 SSH 连接 Administrator，核验主机指纹与历史部署记录一致。凭据仅用于认证，未写入仓库或发布文件。
- 原版本：`C:\bike\releases\20261107-a279cda-fulfillment-delta`，原 JAR SHA-256 与历史记录一致。
- 新版本：`C:\bike\releases\20261110-4c121ac-drafts-list-ids`，`C:\bike\current` 已切换到此目录。
- 原入口：`C:\bike\current-before-20261110-173234`，保留用于回退。
- 备份：`C:\bike\backups\20261110-173234-drafts-list-ids`，包含 `bike.sql`、`storage.zip`、`config.zip`、`services.zip`、校验清单和前后业务快照。目录 ACL 限 SYSTEM 与 Administrators。
- 停止 Nginx、停止后端后备份，切换入口，启动后端；健康检查、数据库版本及业务快照通过后恢复 Nginx。
- 切换前后车辆、订单商品数量、PI、确认收款金额、库存占用及 hold_mode 分组快照一致。数据库保持 V57，失败迁移数 0。当前履约配置有 1 条。

## 构建和文件校验

- 后端离线 Maven `verify` 通过：564 项，354 项执行通过，210 项因外部环境条件跳过，0 失败、0 错误。
- 前端生产构建通过；PI 本机草稿存储测试 9 项通过。
- 增量包 1,821,494 字节，仅 4 个 JAR 条目发生变化，依赖与数据库迁移文件未变。
- 增量包 SHA-256：`50430b61cb408ab581c02801d862b21211d6a35cace67161bb7e4a65e5f6f3c3`。
- 服务器重建 JAR 的 855 个文件条目与本地构建逐项 SHA-256 一致；45 个前端和部署文件验证通过。
- 线上 JAR SHA-256：`f9ad3db835bc0fcdb076d581365d82dc27999902d49b00240dc87abb079eb160`。JAR 容器元数据可能与本地不同，条目内容一致。

## 线上验收

- `BikeMySQL`、`BikeServer`、`BikeNginx` 均 Running；本次启动日志 ERROR / Exception 数为 0。
- 两站首页 HTTP 200、健康 UP，登录成功；公网后台 35 个资源文件和 PDA 3 个文件与发布清单逐项匹配。
- 28 个通用列表接口全部成功返回 ID 列；24 个有数据的模块按实际 ID 搜索通过。报关、出库、返工、采购意向 4 个列表为空，已验证空列表响应和 ID 列声明。
- 生产库存、隔离零件、客户授权、上传核对和 PI 列表接口读取成功。
- 4 份备份文件重新核验 SHA-256 通过。
- 真实浏览器验证供应商列表及 PI 列表 ID；在未选客户/SKU的表单填写测试单价，保存本机草稿、退出、刷新后恢复，单价和步骤一致。验收草稿已通过“放弃并离开”清理，未正式创建 PI、订单或收款记录。
- 浏览器错误和警告日志为空，验收账号已退出登录。
- 服务器 `credentials.json` 中留存的应用 admin 凭据已失效；改用用户提供的当前业务凭据后验收通过。未修改该配置或任何账号密码。
- 本地截图：`output/playwright/deployed-supplier-ids-20261110.jpg`、`output/playwright/deployed-pi-draft-restored-20261110.jpg`。
- 本地构建与核验证据：`tmp/deploy-20261110/`；服务器发布目录中保存 `prepared.json`、`deployment-result.json`、`verification-20261110.json`。

## 使用与回退

PI 本机草稿仅在当前站点、浏览器和账号中恢复，不支持跨设备同步；清理浏览器站点数据会移除草稿。

本次无数据库迁移，原版本及原入口完整保留。需要回退时，停 Nginx 和后端，核实两个 junction 的目标及绝对路径均位于 `C:\bike`；将当前入口移到新的保留名称，将旧入口移回 `C:\bike\current`，启动后端并确认健康后恢复 Nginx。保留数据库与附件原位，不用备份覆盖发布后新增业务，也不删除发布目录。
