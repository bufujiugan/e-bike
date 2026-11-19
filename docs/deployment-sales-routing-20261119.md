# 销售单据分流与模板绑定发布记录

2026-11-19 17:25:03（Asia/Shanghai）完成最终生产切换，17:25:05 服务器校验通过，随后完成公网与内置浏览器业务入口复验。

## 版本与业务范围

- 仓库 `bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`。
- 业务调整提交 `0b5b2c8e3d34ff392f396c45824c3cf12efabafe`；历史关联合同查询修正提交 `b029be02a4770e64cfcc43d01f2419fd1d7eea01`。两笔均已推送 GitHub。
- 销售订单内销办理购销合同，采用 `10.23金轮信德-广州塔威 购销合同(1).xlsx` 的脱敏模板资源。
- 销售订单外销使用 PI，固定 `PI_V5` 英国五列参考版式（`260728英国builtPI.xlsx`）；欧洲内销、外销固定 `PI_EUROPE_V1` France 版式（`France_1104_PI.xlsx`）。
- 普通内销不再显示无关联的 PI 入口；明确属于国内内销的历史关联订单允许补开合同，既有 PI 履约入口及收款、库存、放行约束继续保留。
- 新增兼容响应字段 `hasLinkedPi`，原 API、请求字段、权限和数据库结构不变，无数据库迁移。已归档及入队文件保留原快照和渲染版本。
- 原始 Excel、SDK、截图及临时验证产物保留本地，不纳入源码提交。

## 构建与回归

- 从固定提交导出源码独立构建。`0b5b2c8` 完整离线 Maven `verify`：697 项，478 通过、219 因数据库环境跳过，0 失败、0 错误。
- `b029be0` 完整离线 Maven `verify`：698 项，479 通过、219 因数据库环境跳过，0 失败、0 错误。
- 前端 TypeScript / Vite 构建成功；场景、模板、草稿 Node 测试共 18 项通过。修正版只改 Mapper 和回归测试，核对前端源码完全相同后复用已验证静态产物。
- 本地三场景 Java 渲染器样张、Excel/PDF 版式与金额验证见 `sales-document-routing-20261119.md`。

## 现场发现与修正

- 首次发布 `0b5b2c8` 于 17:17:55 切换，17:17:57 服务与资产校验通过；云任务 `t-bj073kj9x9x0nwg`，耗时 2 分 11 秒。
- 浏览器进一步打开历史 PI 关联订单时，合同准备接口出现“系统暂时无法处理请求”。普通无关联内销的合同入口及表单正常。
- 根因：`OrderContractMapper.selectLinkedPiScenarios` 将 `PiMapper` 的输出别名 `document_json` 当成物理列使用，真实字段为 `document_snapshot`；原单元测试使用 Mock Mapper，未覆盖 SQL 执行。
- 修正版使用真实快照字段，并将 JSON null 销售渠道规范为 SQL NULL；新增 Mapper 查询引用与迁移定义交叉校验，防止再次引用 DTO/查询别名。
- 不将首次服务健康或页面提示检查等同于业务验收，最终以修正版及同一真实关联订单复验为准。

## 发布包与恢复

- 最终 Release：`deploy-20261119-b029be0-sales-routing`，资源 `bundle-b029be0.zip`，1,669,558 字节。
- Bundle SHA-256：`fe514fe4e6325834b85899ecce78ce4f3842ad90d69d67d6b567fdcdc2f34f11`。
- Delta SHA-256：`63a938978567698e9c60f05f71d05ee9c315fa6e63d5179cbdd424fc96ea6a33`。
- 最终版本目标目录：`C:\bike\releases\20261119-b029be0-sales-routing`。
- 最终云助手任务 `t-bj073kjz3vpt88w`，成功执行、退出码 0，耗时 1 分 28 秒；已读取完整部署输出确认实际切换。
- 最终 JAR SHA-256：`289467179b087fefbd4f76299461105afd5b739b64b07a258d72789159779558`。
- 最终发布前备份：`C:\bike\backups\20261119-172427-sales-routing`；紧邻上一版本入口：`C:\bike\current-before-20261119-172427`。
- 初次上线前稳定版本 `C:\bike\releases\20261119-dbcf3a7-europe-pi-france` 及入口 `C:\bike\current-before-20261119-171718` 已保留。
- 初次备份 `C:\bike\backups\20261119-171718-sales-routing`；数据库、附件、配置、服务配置四份备份校验通过。
- 修正版基于 `0b5b2c8`，JAR 仅 Mapper XML 一个条目变化；917 个 JAR 条目逐项验证，56 个发布文件校验摘要。
- 回退恢复版本入口并重启服务；不要以历史数据库备份覆盖上线后新增业务数据。无文件批量删除。

## 生产核验

- `BikeMySQL`、`BikeServer`、`BikeNginx` 均 Running，最新启动日志错误 0；健康检查 UP。
- 停服窗口前后订单、车辆、PI、收款、库存占用业务聚合一致；V58 成功、失败迁移 0、欧洲业务表 3 张，迁移状态未变化。
- 公网管理端和 PDA 首页 HTTP 200，46 个管理端资源、3 个 PDA 资源摘要与清单一致；两个域名下合同、PI、欧洲订单、仓库、履约配置等接口匿名访问均为 401。
- 内置浏览器普通内销订单 `SO6F3E09F4359246B881`：显示“生成购销合同”，无 PI 操作入口；展开后显示指定模板说明和正确客户、车型、数量。
- 修正版复验此前报错的历史内销关联订单 `SO5FB949EE6980412398`：合同入口正常，同时保留“查看已关联 PI 与履约”，没有系统错误。
- 欧洲外销关联订单 `SO0996C71BB1DE4B988F`：显示归属限制说明，无国内合同按钮，保留 PI 履约入口。
- 销售订单外销显示 `260728英国builtPI` 固定模板提示；欧洲内销、外销均显示 `France_1104_PI` 固定模板提示，对应 PI 工作台正常加载。
- 浏览器未捕获 error；核验仅查看列表、办理入口及合同表单，未提交生产合同、PI、收款或出库数据，不代表真实交易与现场出库全流程验收。已关闭核验对话框。
- 截图 `output/playwright/sales-routing-final-live-20261119.png`、`sales-routing-linked-domestic-live-20261119.png`、`sales-routing-fix-deploy-20261119.png` 及各场景截图保留本地。

本地证据目录：`tmp/deploy-sales-routing-20261119/`、`tmp/deploy-sales-routing-fix-20261119/`。
