# PI 锁定提示与内销合同入口发布记录

2026-11-21 14:27:06（Asia/Shanghai）完成生产切换，14:27:07 服务器验证通过，随后公网验证通过。

## 版本与变更

- 仓库 `bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`。
- 应用提交 `2b823b0d97d9a825ebf0fbf00eabcb521da18618`，7 个文件，76 行新增、7 行删除，已推送 GitHub。
- PI 无可用 SKU 锁定时展示到期、已转完或释放、其他代理商等原因；允许切换为直接选 SKU 新建 PI，保留客户、代理商及已填单据信息。
- 新增只读 `GET /api/pis/lock-options/summary`，参数为 `customerId`、可选 `agentId`、默认 false 的 `anyAgent`；返回 `totalCount`、`otherAgentCount`、`expiredCount`、`finishedCount`、可选 `lastExpiredAt`。
- 新接口沿用内部用户、`PI_READ`、`PI_WRITE`、`AGENT_LOCK_WRITE` 和客户范围校验。SQL 位于 `PiSeriesMapper.xml`。
- 内销购销合同填写与下载入口限定在订单确认前，确认提示提醒先下载保存；确认后的出库页面不再加载或显示该填写入口。
- 既有接口路径和请求字段保持兼容，无依赖或数据库字段变更，无新增迁移。
- 本轮开始时全部未提交应用代码已纳入；原始业务资料、第三方 SDK、截图及临时输出保留本地。

## 构建验证

- 从固定提交导出独立源码，JDK 21、离线 Maven `verify` 成功。
- Surefire 共 110 个测试类、709 项测试：489 项执行通过，220 项跳过，0 失败、0 错误。
- 前端 TypeScript 检查及 Vite 构建成功，保留已有大 bundle 提示。
- 场景、模板校验和本地草稿 Node 测试 18 项全部通过。
- JAR 仅 7 个应用类或 Mapper XML 条目变化；全部 934 个 JAR 内容条目与本次构建逐项 SHA-256 一致。

## 发布与备份

- Release：`deploy-20261121-2b823b0-pi-lock-guidance`；发布包 `bundle-2b823b0.zip`，1,693,600 字节。
- Bundle SHA-256：`e2a5b7f8e6fdf01c1d56ad3a5baeca8e44be95227addd48ad9cd574ab62bef80`。
- Delta SHA-256：`cc08aa4c77969b6f016606ac653667a0d2cf6110ef0d33534e00aa0d6836fd26`。
- 发布前确认线上版本 `80446f0`，JAR 摘要与上次发布一致，3 个服务 Running、健康 UP、磁盘剩余 18.18 GB。
- 当前目录：`C:\bike\releases\20261121-2b823b0-pi-lock-guidance`。
- 上一目录：`C:\bike\releases\20261120-80446f0-pi-refinements`。
- 旧入口：`C:\bike\current-before-20261121-142635`。
- 备份目录：`C:\bike\backups\20261121-142635-pi-lock-guidance`，数据库、附件、配置、服务配置四份备份摘要全部通过。
- 线上 JAR SHA-256：`498d02ea60bd25efc9fc91f3cc494c513807d65dbdc6d007a3ba04633ddceb39`。
- 内置浏览器云助手任务 `t-bj073r8vpipgcg0`，14:24:12 开始，14:27:08 结束，成功执行、ExitCode 0。
- 发布脚本先校验基线与发布包，备份后切换 current junction；异常时恢复旧入口并重启服务。可回退到上述旧目录，无数据库回退要求；不要用历史备份覆盖发布后的新业务数据。
- 全部旧目录和备份保留，未批量删除文件或目录。

## 生产验证及边界

- `BikeMySQL`、`BikeServer`、`BikeNginx` 均 Running，健康检查 UP，最新启动日志错误数 0。
- 56 个发布文件摘要验证通过；V58、V59 均成功，失败迁移为 0，发布前后迁移状态相同。
- 停服窗口内车辆、订单明细、PI、收款、库存预留汇总保持一致。
- 管理端、PDA 公网首页均 HTTP 200、健康 UP；管理端 46 个及 PDA 3 个资源摘要与发布清单一致。
- 两域名下共 7 类受保护接口匿名访问均为 401，包含新增锁定原因统计接口。
- 内置浏览器刷新后管理端登录页面正常。管理端会话未登录，尚未完成登录后的锁定提示、手动新建 PI 和合同入口业务验收。
- 未创建真实 PI、合同、收款或库存交易；构建和服务验证不等同于完整业务验收。

本地证据目录：`tmp/deploy-pi-lock-guidance-20261121/`，包含固定源码、构建与测试日志、发布包、预检输出、完整服务器输出、公网验证结果和成功截图。
