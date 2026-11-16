# 欧洲销售三场景发布记录

2026-11-16 20:18:14（Asia/Shanghai）完成生产切换，20:18:15 完成服务器验收，随后完成公网资源与登录页面检查。

## 版本与范围

- GitHub：`bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`。
- 应用提交：`227584fedb70aea151610d21ea4a71fbd1e3b274`，已推送并核对远程分支 SHA。
- 本次应用提交共 47 个源码、测试和文档文件，包含欧洲销售三场景、款项台账、简化 PI、外销配置指引及取消后付款固定到期日限制。
- 捷克当地内销的 VAT 金额由用户填写，不设默认税率、不自动计算。两种工厂直签均先确认工厂已收款，再提货。
- 管理后台和后端更新，PDA 静态页面沿用原发布内容。原接口路径不变，PI 新增可选 `workflowMode`；新增 V58 及三张业务表。
- 原始合同、PDF、Excel、SDK、截图与临时错误文件保留本地，未作为应用源码提交。

## 构建、迁移与备份

- 后端回归 645 项：427 通过、218 环境条件测试跳过、0 失败／错误；前端生产构建和原 PI 草稿 9 项回归通过。发布 JAR 单独完成 Maven 离线打包。
- 通过内置浏览器、用户已登录的阿里云 ECS 云助手执行部署，目标为北京 Ebike 实例 `i-2ze5xv8sh0zix3w4szuc`。
- 服务器校验全部 899 个 JAR 文件条目与 54 个静态／部署文件。
- 停服前，将生产数据库备份恢复到服务器独立库 `bike_europe_rehearsal_20261116`，用待发布 JAR 的 Flyway 验证并执行 V58，通过后才开始停服切换。恢复副本保留服务器本地，不导出到 GitHub。
- 停止 Nginx 与后端后，备份数据库、附件、配置和服务配置；4 份备份文件散列复核通过，备份目录限制为 SYSTEM 与 Administrators。
- 正式库 V58 执行成功，失败迁移 0，新增欧洲业务表 3 张。发布前后车辆、订单、PI、收款及库存占用业务快照一致。

## 发布位置与校验值

- 新版本：`C:\bike\releases\20261116-227584f-europe-sales`。
- 旧版本：`C:\bike\releases\20261113-d376c0b-pi-template-arrival`。
- 当前入口：`C:\bike\current`；旧入口：`C:\bike\current-before-20261116-201731`。
- 备份：`C:\bike\backups\20261116-201731-europe-sales`。
- GitHub Release：`deploy-20261116-227584f-europe-sales`；使用最终包 **`bundle-227584f-v3.zip`**。
- 最终包 SHA-256：`029bdb8a4ed4f8160dd7f2c1c1ea96f7c3d694b7cae506a9eeb68abf0de515d2`。
- 最终部署脚本 SHA-256：`f700328ec8a37e6a4ee8ed6081366657818f68ceb9c872de089f20d579baf2d7`。
- 线上 JAR SHA-256：`96b93a2fbc1334bf02043e23e6e6a4fe5a53bc0d2a979097c4b263ee686093cc`；ZIP 容器元数据与本地打包不同，899 个文件条目内容逐项一致。
- 成功任务：`t-bj073aa4aagh1xc`，退出码 0。前两次被旧版本目录检查及脚本换行哈希检查拦截，均发生在停服和改库之前；修正后没有跳过任何校验。

## 线上验收

- `BikeMySQL`、`BikeServer`、`BikeNginx` 均 Running；本次启动日志 ERROR／Exception 为 0。
- `ebike.nfwms.com`、`pda.nfwms.com` 首页 HTTP 200，健康状态 UP。
- 公网 Web 44 个文件、PDA 3 个文件与发布清单 SHA-256 一致。
- 新 `/api/europe/factory-orders` 与原 PI、仓库、履约配置等接口匿名访问返回 401。
- 内置浏览器登录后，欧洲销售与交付的三个入口、两种直签列表、新建直签表单均正常；VAT 金额为空可手填，付款日期选填，原操作说明新增“外销／欧洲销售”栏目。
- 页面控制台错误为 0。本次线上检查没有保存新订单、PI、收付款或出库记录，也没有进行实体 PDA 扫码／打印验收。

## 回退与证据

V58 已执行，且新单可能使用 `USER_CONFIRMED` 模式，不能直接切回旧 JAR 或删除已执行迁移。出现问题时先暂停新流程写入，保留新表和备份，优先使用兼容修复；若必须恢复上线前数据库，应先停写并核对上线后新增业务，避免覆盖新增数据。不得批量删除发布目录。

服务器版本目录保存 `deployment-result.json`、`release-manifest.json`、`verification-20261116.json`。本地发布材料和公网验收结果位于 `tmp/deploy-europe-20261116/`，线上入口截图为 `output/playwright/europe-sales-live-20261116.jpg`。
