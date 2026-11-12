# PI 模板调整功能部署记录

2026-11-12 13:59:18（北京时间）完成部署，13:59:43 完成服务复核，随后完成外网资源和浏览器验收。

## 代码与发布版本

- 仓库 `bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`。
- 先提交并推送全部剩余代码修改和功能说明，再部署；功能提交 `58efaf3ece9d548341d5f926b60b67aba185f8ef`，GitHub 远程 SHA 已核验。
- 新发布目录 `C:\bike\releases\20261112-58efaf3-pi-template-adjustment`，current 已指向该目录。
- 上一版本 `C:\bike\releases\20261112-fd53ac6-timed-order-shipment`。
- 后端继续使用 `fd53ac6` 出库修复，JAR SHA-256 为 `703349f787489ed7048fc8f660a56ce5e3c744cb9bae4519efb572a841a08b15`。
- 全部前端源码通过 `npm run build` 生成 40 个 web 文件，替换完整 web 目录；PDA 和部署配置沿用线上版本。
- web 包 SHA-256：`7b09ac5bf08be0d25f42afef112f7932922730ccc7e2888708f362a0ce77ad87`。
- 用户确认原始 SDK、Excel/PDF、截图和临时诊断文件保留本地。提交后已跟踪文件无剩余修改。

## 备份与一致性

备份位于 `C:\bike\backups\20261112-135852-pi-template-adjustment`，包含数据库、storage、config、services 四个备份，散列复核通过。旧入口保留为 `C:\bike\current-before-20261112-135852`。

切换前后业务快照一致。无数据库迁移，状态仍为 `migration=57:1`、`failed=0`。新目录共 50 个 web/pda/deploy 文件散列验证通过，后端 JAR 与上一版本一致。

## 验收结果

- 前端类型检查和生产构建通过；构建保留现有大 chunk 提示。
- 本地浏览器完成模板资料回填、模板编码固定、新版本号和模拟保存请求校验；模拟请求被拦截，未创建线上测试模板。
- 外网首页、入口 JS、PI JS、PI CSS 的实际响应散列与构建文件一致。
- 线上实际进入“PI 与履约 → 模板与银行资料 → 调整模板”，验证资料回填、新版本号及“保存为新版本”可用；390px 移动端渲染稳定后无页面横向溢出，表单可滚动、按钮可见。取消编辑收尾，未修改线上模板资料。
- 线上浏览器控制台无错误。
- BikeMySQL、BikeServer、BikeNginx 均 Running；两域首页 200、健康 UP；受保护接口匿名访问 401；启动日志无 ERROR/Exception。
- 发布保留普通订单按有效限时占用出库能力；本次未实际办理业务出库。

服务器证据：新版本目录内的 `release-manifest.json`、`release-metadata.json`、`deployment-result.json`、`verification-20261112.json`。本地证据：`tmp/deploy-template-20261112/`。

## 回退

如需回退，停止入口和后端服务，核实 current 指向本次版本后重命名保留，将旧入口恢复为 current，启动后端并通过健康检查后恢复入口。回退只撤回本次模板前端功能，上一版本仍保留出库修复。保留上线后的业务数据，不用旧数据库备份覆盖。
