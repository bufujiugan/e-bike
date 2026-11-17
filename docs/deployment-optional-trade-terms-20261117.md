# PI 贸易术语选填发布记录

2026-11-17 11:31:53（Asia/Shanghai）完成生产切换，11:31:55 完成服务器验收，随后完成公网资源与匿名访问检查。

## 版本与范围

- GitHub：`bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`。
- 应用提交：`f6c1a5406219c2760a9d6cd963e6bd94f7c1df37`，已推送 GitHub。
- GitHub Release：`deploy-20261117-f6c1a54-optional-trade-terms`，发布资源为 `bundle-f6c1a54.zip`。
- 管理后台的“贸易术语”改为选填，移除前端必填标记和提交校验；提示欧盟地区通常使用卡车配送或客户自提，非欧盟地区涉及报关时再填写 DDP 等术语。
- 后端外销 PI 确认不再强制要求 `tradeTerms`；买方国家和卖方国家仍为必填，原 Controller API、DTO 字段和数据库字段保持兼容。
- 本次没有数据库迁移，不修改历史 PI 数据，不改变欧洲销售、收款、库存和出库规则。

## 构建与测试

- 前端生产构建通过。
- `PiServiceTest` 38 项通过，新增贸易术语为空可确认、买卖方国家仍必填的覆盖。
- 后端完整验证共 647 项：428 通过、219 项按环境条件跳过、0 失败／错误。
- 发布前核对生产基线：当前 release 为 `C:\bike\releases\20261116-227584f-europe-sales`，提交为 `227584fedb70aea151610d21ea4a71fbd1e3b274`，JAR SHA-256 为 `96b93a2fbc1334bf02043e23e6e6a4fe5a53bc0d2a979097c4b263ee686093cc`；V58 成功、失败迁移 0、欧洲业务表 3 张。

## 发布位置与校验值

- 新版本：`C:\bike\releases\20261117-f6c1a54-optional-trade-terms`。
- 旧版本：`C:\bike\releases\20261116-227584f-europe-sales`。
- 当前入口：`C:\bike\current`；旧入口：`C:\bike\current-before-20261117-113125`。
- 备份：`C:\bike\backups\20261117-113125-optional-trade-terms`。
- Bundle SHA-256：`2d2bf435831fb89566bd19edede236c43b9e5094996ac98e46d1024948a9c681`。
- Delta SHA-256：`a669aa4c3dae4f4f31ed984d489989063dd492447bd00662ab68e7732d88dad1`。
- 部署脚本 SHA-256：`fa1b3e0872399ca850c5d68c252cd80e2ddd6613128e8701763ddca784ed3ad7`。
- 验收脚本 SHA-256：`06577747b203923d409e572ce936b5f2cc4938664bcc1566a649dd254d071b70`。
- 线上 JAR SHA-256：`c03f184f1da7278d301daaba85e2d61455bf44dbbd42f2ac70abdd2541cbc84b`。
- 云助手预检任务：`t-bj073cj7zlj8a2o`；正式部署任务：`t-bj073cjjq65ce80`，成功执行。

## 线上验收

- `BikeMySQL`、`BikeServer`、`BikeNginx` 均为 Running；启动日志 ERROR／Exception 为 0。
- `ebike.nfwms.com`、`pda.nfwms.com` 首页 HTTP 200，健康状态均为 UP。
- 公网管理端 44 个静态文件、PDA 端 3 个静态文件与发布清单 SHA-256 一致。
- `/api/europe/factory-orders`、`/api/pis`、`/api/pis/warehouse-options`、`/api/pi-fulfillment-profiles` 匿名访问均返回 401。
- 部署前后车辆、订单、PI、收款和库存占用业务快照一致；54 个发布文件、4 份备份散列校验通过。
- 本机保留的管理员初始密码已失效，本次没有修改生产账号密码，也没有创建或保存真实 PI；因此已完成静态资源、服务、接口保护和后端规则验证，未完成登录后的页面交互验收。

## 回退与证据

本次没有新增迁移。若新版本出现问题，先停止相关写入并核对新版本上线后的业务数据，再将 `C:\bike\current` 切回旧 release 并重启服务；部署脚本异常时会自动恢复旧入口。回退不得删除 release 或备份目录。

服务器版本目录保存 `deployment-result.json`、`release-manifest.json`、`verification-20261117.json`。本地发布材料及公网验收结果位于 `tmp/deploy-trade-terms-20261117/`，其中 `public-verification.json` 记录公网校验结果。
