# 客户门户、欧洲订单与 PI 单据发布记录

2026-11-11 20:26:03（Asia/Shanghai）完成生产版本切换，随后完成服务、静态资源及浏览器登录页面检查。

## 源码和发布范围

- 仓库：`bufujiugan/e-bike`，分支：`codex/pi-business-flow-20261103`。
- 应用提交：`6c200e988c93d1e3d9725ee2e2eb5f8188d6cef7`，已推送并核对 GitHub 远程分支 SHA。
- 本次源码提交含 154 个文件：后端及后台改动、新增客户门户源码、欧洲订单入口、采购合同关联、PI 费用输入与 PI_V5 单据渲染，以及此前未跟踪的 PDA 厂商示例源码和配套资源。
- 发布后端与管理后台。PDA 网页沿用已验证的原有构建，3 个文件散列保持一致；未重新打包或发布原生 APK。
- 无新增数据库迁移，依赖 JAR、SQL 迁移和 Java V57 迁移的条目内容均保持一致。
- 客户原始 PDF/Excel、SDK 压缩包/APK、历史截图及临时文件未作为源码提交；文件均留在本地，未删除。服务器密码、数据库配置及其他凭据未写入仓库或发布包。

## 构建与核验

- 后端使用 JDK 21 与离线 Maven，在 `tmp/deploy-20261111/source/bike-server` 独立构建成功。
- 全量测试 591 项：381 项执行通过、210 项因外部环境条件跳过，0 失败、0 错误。首次在原 target 中打包遇到已有 JAR 占用，因此改用独立目录重新执行全量 verify；未停止本地开发服务。
- 管理后台 `vue-tsc --noEmit && vite build` 成功；保留 Vite 原有大包提示。
- PI 本机草稿与浏览器 UUID 测试共 15 项全部通过。
- 增量包大小 2,006,759 字节，SHA-256：`b0f551f4529128e88ab2b91dd021f481b1e46b975054fe704a4e8e85522f8119`。
- 52 个 JAR 条目变更；服务器重建后全部 878 个文件条目与本地构建逐个 SHA-256 一致，50 个前端及部署文件全部校验通过。
- 线上 JAR SHA-256：`ceb44bf3a14bfc6eb5068887b69ba5dbee3ae4198afe9fd9893e59578dc8d886`。ZIP 容器元数据可能与本地不同，条目内容一致。

## 服务器、备份与切换

- 阿里云实例：`i-2ze5xv8sh0zix3w4szuc`，公网 IP：`59.110.166.139`。
- SSH 主机指纹与历史记录一致；使用用户提供的 Administrator 凭据，仅在内存中认证。
- 原版本：`C:\bike\releases\20261110-4c121ac-drafts-list-ids`。
- 新版本：`C:\bike\releases\20261111-6c200e9-customer-pi`。
- 当前入口：`C:\bike\current`，已指向新版本。
- 旧入口保留：`C:\bike\current-before-20261111-202535`。
- 备份：`C:\bike\backups\20261111-202535-customer-pi`，包含 `bike.sql`、`storage.zip`、`config.zip`、`services.zip`、备份散列和发布前后业务快照；ACL 限 SYSTEM 与 Administrators。
- 按 Nginx → 后端顺序停止，备份后切换 junction，启动后端。健康、迁移状态与业务快照通过后恢复 Nginx。未删除旧目录、改密码或覆盖数据库。
- 切换前后车辆、订单商品数量、PI、确认收款金额、库存占用及 hold_mode 分组快照一致。数据库保持 V57，失败迁移数 0，履约配置 1 条。

## 上线验证与边界

- `BikeMySQL`、`BikeServer`、`BikeNginx` 均 Running；新启动段日志 ERROR / Exception 数为 0。
- 管理后台及 PDA 首页 HTTP 200，健康接口均为 UP。
- 公网管理后台 40 个文件与 PDA 3 个文件逐个散列匹配发布清单。
- 两个站点的 `/api/pis`、`/api/pis/warehouse-options`、`/api/customer/company`、`/api/customer-order-notifications` 匿名访问均返回 401。
- 4 份备份文件再次校验散列通过。
- 真实浏览器检查管理后台登录页，390px 移动端没有页面横向溢出，控制台无错误或警告。
- 本次未使用失效的历史 admin 密码反复尝试登录，未修改账号密码；业务账号登录后的客户下单、欧洲仓库筛选及 PI 导出线上端到端流程尚未验收。没有为验收创建真实订单、PI 或收款记录。
- 原生 PDA 扫码、打印及物理设备验收不在本次验证结论内。

## 回退

停 Nginx 与后端后，先核实当前及旧入口为预期 junction，且绝对路径均位于 `C:\bike`；将当前入口改名保留，再将旧入口恢复为 `C:\bike\current`，启动后端、检查健康并恢复 Nginx。不要用备份覆盖发布后的新增业务，也不要批量删除版本目录。

本次没有数据库迁移，但新版本可能在业务开放后生成 PI_V5 异步归档任务。此时回退旧应用前必须核对待处理任务；回退包应保留 PI_V5 渲染支持，避免旧代码无法处理新任务。

本地证据：`tmp/deploy-20261111/`。服务器新发布目录保留 `prepared.json`、`deployment-result.json`、`verification-20261111.json`。浏览器截图：`output/playwright/deployed-login-20261111.png`、`output/playwright/deployed-login-mobile-20261111.png`。
