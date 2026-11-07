# 统一履约配置与 PI 导出发布记录

2026-11-07 15:58:17（Asia/Shanghai）完成生产部署。

## 版本与范围

- GitHub：`bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`，未合并 `main`。
- 应用提交：`a279cdafc5d44c9f7fd20a3c876c2e4da69b5641`。
- 变更包含统一履约配置、后付款库存保留、单据流程和 PI Excel 参考版式；补齐原生 PDA 项目源码及依赖。服务器发布后端、管理后台和 PDA 网站。原生 APK 未在实体 PDA 上安装或验收。
- GitHub 发布包标签：`deploy-20261107-a279cda-fulfillment`（预发布）。仅包含构建产物及部署脚本，不包含生产数据、备份或凭据。

## 连接与目录

- 服务器：阿里云北京 Windows Server，实例 `i-2ze5xv8sh0zix3w4szuc`，公网 `59.110.166.139`。
- 本次通过已登录的阿里云 RAM 控制台 → ECS → 实例 → 定时与自动化任务 → 命令执行结果 → 发送 PowerShell 命令完成。SSH 指纹核验一致，但本地密钥认证失败；未修改 SSH 权限或服务器密码。
- 管理后台：`http://ebike.nfwms.com`；PDA 网站：`http://pda.nfwms.com`。
- 当前入口：`C:\bike\current`，指向 `C:\bike\releases\20261107-a279cda-fulfillment-delta`。
- 原版本：`C:\bike\releases\20261105-6913d35-pi-binding`。
- 原入口保留：`C:\bike\current-before-20261107-155749`。
- 备份：`C:\bike\backups\20261107-155749-fulfillment`，包含 `bike.sql`、`storage.zip`、`config.zip`、`services.zip`、SHA-256 清单和前后业务快照。备份目录仅 SYSTEM 与 Administrators 可访问。

## 构建与发布校验

- 后端离线 Maven `verify` 成功：562 项，352 项执行通过，210 项依外部环境条件跳过，0 失败、0 错误。未把跳过项计作通过。
- 管理后台和 H5 的类型检查、生产构建成功。后台仍有打包体积提示。
- 原生 PDA 普通测试 43 通过、1 跳过；随后运行硬件协议模拟及 AAR 集成回放，44 项应用测试全部通过，协议、队列和分帧模拟通过。这不代表实体硬件验收。
- 本次未重新运行独立 MySQL 35 项验收；此前同日的隔离库结果见统一履约说明。本次在生产实际执行并核验 V57。
- 完整发布包传输较慢，停止该下载后采用约 1.9 MB 的增量包。原始完整包的未完成下载保留在 downloads，不作为发布依据。
- 增量包 SHA-256：`cab8bd0372dbeba87b9315c88dfd74076679d5043528bae7aba94ae8c8fa54a8`。
- 以已核验的 `6913d35` 线上 JAR 重建，变化 41 个条目、无删除条目；重建后 855 个文件条目的 SHA-256 全部匹配本地构建，45 个前端与部署文件校验通过。
- 线上 JAR SHA-256：`ac053c811b86a6a97727c16c4a1d12c00427dd39f02b41c94a8ab0830f3c7dbf`。ZIP 容器元数据与本地不同，逐条目内容一致。
- 停 Nginx、停后端后备份，切换 junction，启动后端并验证迁移和业务快照，再恢复 Nginx。业务快照比较覆盖车辆、订单商品数量、PI、确认到账金额、库存占用数量及 hold_mode 分组。
- V57 成功，失败迁移数 0；新增配置表为空。前后业务快照完全一致。
- `BikeMySQL`、`BikeServer`、`BikeNginx` 均 Running；健康 UP；本次启动日志核验 ERROR / Exception 数为 0；4 个备份文件重新校验通过。

## 公网与浏览器验收

- 两域名首页 HTTP 200、健康 UP、管理员 API 登录成功，角色为 `SUPER_ADMIN`。
- 公网管理后台 35 个文件、PDA 网站 3 个文件与本地构建逐文件 SHA-256 匹配。
- 历史 PI 1 保持 `CONFIRMED`，关联订单 1，到账 `25000.00`，库存状态 `PAID_HELD`；流程查询正常。
- PDA `/api/orders/stock-out-options?customerId=2` 正常。首次校验脚本遗漏必填 `customerId` 返回 400，补齐参数后通过；无需修改应用。
- 真实浏览器登录两站成功；后台 PI 列表显示原有 2 条单据，“履约配置”正常打开，“新建 PI”显示配置必填，退出未保存；PDA 出库页正常加载客户与 3 条待出库订单。
- 两站浏览器错误及警告日志为空。未新增测试 PI、订单、收款、出库或履约配置；登录会产生正常审计。
- 截图：`output/playwright/fulfillment-production-20261107.png`、`output/playwright/pda-production-20261107.png`（本地保存）。
- 本地发布与校验证据：`tmp/deploy-20261107/`；服务器结果：当前发布目录下 `deployment-result.json`、`prepared.json`。

## 使用前配置与回退

线上当前无已发布履约配置。新建 PI 前，需要管理员在“PI 与履约 → 履约配置”依据实际付款、交付、模板及收款主体发布配置。未替用户虚构商业规则或银行资料；历史无配置单据保持兼容。

切换脚本在恢复公网入口前失败会恢复原应用入口；本次未触发回退。V57 增加配置表并扩展 CHECK 约束，不删除历史数据。迁移属于 MySQL DDL，不能把应用回退当成数据库事务回滚。

后续回退前须先停止新增后付款业务，并核对是否已有 `CREDIT` / `CREDIT_HELD` 占用。存在这些状态时，不能直接回退到不识别它们的旧服务，也不能将其改成已收款。确认兼容后，按顺序停 Nginx、停后端，验证两个 junction 的目标，将当前入口移到新的保留名称，再将已保存旧入口移回 `C:\bike\current`；健康 UP 后恢复 Nginx。保留数据库、附件和发布目录，不用旧备份覆盖上线后新增业务。
