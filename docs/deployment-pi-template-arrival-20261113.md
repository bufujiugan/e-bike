# PI 模板校验与到仓流程发布记录

2026-11-13 17:46:00（Asia/Shanghai）完成应用切换，随后服务器验收和公网静态资源核验通过。

## 代码与发布范围

- 分支：`codex/pi-business-flow-20261103`。
- 应用提交：`d376c0b6d0b4f3e1707ae96055888e80c7573800`，已推送并核对 GitHub 分支 SHA 一致。
- GitHub Release：`deploy-20261113-d376c0b-pi-template-arrival`。
- 提交本地全部待发布的项目源码、回归测试及既有业务清理运维记录，共 13 个文件。
- PI 模板卡片支持直接调整模板，保存新版本；履约配置指定模板时提供切换指引。
- 模板发布增加中文字段校验、具体错误定位及重复版本提示。
- 到达货代仓库时填写实际到仓日期和物流单号，不再强制选择到货图片凭证；历史已完成步骤及其附件数据保留。
- 更新管理后台及后端，PDA 沿用此前已发布版本。无数据库迁移，无接口字段删除。
- SDK、原始 PDF/Excel、截图和临时错误文件保留本地，未纳入代码提交。

## 构建与验证

- 管理后台 TypeScript 检查与生产构建通过。
- 前端回归 29 项全部通过，覆盖模板字段校验、PI 本地草稿、发货金额选填和 PDA 自动扫码。
- JDK 21 离线 Maven `verify` 成功：620 项，402 项通过、218 项因环境条件跳过，0 失败、0 错误。跳过项不代表真实 MySQL 集成场景已验证。
- 后端相较上一版仅 4 个 JAR 条目变化：`PiTemplateRequest.class`、`PiWorkflowServiceImpl.class`、`PiWorkflowStepCode.class` 和 `PiWorkflowMapper.xml`。
- 服务器逐项核验全部 878 个 JAR 文件条目及 50 个静态/部署文件。
- 发布包 `bundle-d376c0b.zip` 共 1,625,676 字节，SHA-256：`1d241e0593572bfa500e657c886e8bf7b6a30413804e7de57bb59627d2fbcf3d`。
- 线上 JAR SHA-256：`9202bc22f4cd35793d9db0791273d94840d7eb80bdf10380a88a390735e0d05b`；ZIP 容器元数据与本地原包不同，全部条目内容一致。

## 切换与备份

- 通过已登录的阿里云 ECS 云助手执行。
- 新版本目录：`C:\bike\releases\20261113-d376c0b-pi-template-arrival`。
- 旧版本目录：`C:\bike\releases\20261113-865fefe-scan-transaction`。
- 当前入口：`C:\bike\current`，旧入口保留为 `C:\bike\current-before-20261113-174529`。
- 备份：`C:\bike\backups\20261113-174529-pi-template-arrival`，包含数据库、附件、配置、服务配置及发布前后业务快照；4 份备份散列复核通过，权限限制为 SYSTEM 与 Administrators。
- 发布包和内容校验通过后停止 Nginx、后端并备份，再切换入口；健康检查与业务快照校验通过后恢复 Nginx。
- 发布前后业务快照一致；数据库保持 V57，失败迁移 0，履约配置数量 5。
- 首次 GitHub 入口下载连接失败，第二次资源直达链接过期，均发生在停服前。重新获取并核验有效期的直达链接后成功下载，仍核验同一发布包 SHA-256。
- 成功任务：`t-bj072z70zsvgav4`，退出码 0，云助手显示成功执行。

## 线上验收

- `BikeMySQL`、`BikeServer`、`BikeNginx` 全部 Running；最新启动段 ERROR / Exception 数 0。
- 管理后台与 PDA 首页 HTTP 200，健康 UP；代表性业务接口匿名访问返回 401。
- 公网管理后台 40 个文件、PDA 3 个文件与发布清单散列一致。
- 已登录后台、PI 页面及模板列表加载成功；在未保存的新建模板表单输入中文模板编码，出现具体字段格式提示，随后取消并关闭表单。
- 未新增模板、PI、收款、库存或出库记录。到仓业务提交及实体 PDA 扫码打印未做线上真实业务验收。

## 回退与证据

回退前先停止 Nginx 和后端，验证当前及旧入口均为预期 junction，目标在 `C:\bike\releases` 内；改名保留新入口，将旧入口恢复为 `C:\bike\current`，启动后端并验证健康后恢复 Nginx。本次无迁移，不应以备份覆盖发布后新增业务，不批量删除目录。

服务器新版本目录保存 `deployment-result.json`、`release-manifest.json`、`verification-20261113.json`。本地构建、脚本、发布包、云助手输出及公网校验证据在 `tmp/deploy-all-20261113/`，页面截图保留本地。部署文档提交不改变应用内容。
