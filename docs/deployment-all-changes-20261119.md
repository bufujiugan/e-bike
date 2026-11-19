# 未提交应用改动集中发布记录

2026-11-19 15:55:39（Asia/Shanghai）完成生产切换，15:55:41 服务器校验通过。

## 版本与范围

- 仓库：`bufujiugan/e-bike`；分支：`codex/pi-business-flow-20261103`。
- 应用提交：`438a844b02c3d8e4b043042b8fb2061c4af92c38`，28 个文件，已推送 GitHub。
- 包含任务开始时全部未提交的应用代码、前端资源及设计文档：奶油蜜桃主题、账号装饰图片和 PI 实际出库校验。
- `PiWorkflowServiceImpl` 在人工确认和已完成提前返回之前检查真实出库；列表也以实际出库状态显示出库及后续节点。收款登记入口保持独立。
- `AccountDecorationController`、`AccountDecorationService`、`AccountDecorationServiceImpl` 提供当前账号装饰图片读取、保存与恢复默认，路径来自认证用户 ID。图片规范化后在持久 storage 中原子替换，无数据库迁移。
- 新增 `/api/auth/decoration` 的 GET、PUT、DELETE；既有 API 和 DTO 保持兼容。整体回退需同时恢复前后端，新增装饰文件可保留。
- 前端更新工作台、公共横幅、侧栏、账号菜单、图表颜色与主题样式。
- 原始合同、SDK 安装资料及压缩包、历史截图、`c.method` 调试输出保留本地，不作为应用源码提交。
- 提交固定后，另一窗口开始修改 PI 导出，并在 15:56、15:58 形成 `7b7524f`（欧洲 PI 法国参考版式）和 `dbcf3a7`（长地址行布局）两笔提交。它们随本分支最终推送进入 GitHub，但未进入本次已构建、验证和上线的 `438a844`；本次部署完成时受跟踪文件工作区已干净。线上版本与 GitHub 分支最新提交有此明确差别，后续部署应单独构建和验证这些新增功能。

## 构建和验证

- 从提交独立 `git archive` 构建，避免并行工作区修改混入发布产物。
- JDK 21，离线 Maven `verify` 成功：686 项测试，467 项通过、219 项跳过，失败及错误均为 0。跳过项不代表通过生产数据库验收。
- 本次相关测试：`AccountDecorationServiceTest` 5 项、`PiBusinessServiceTest` 24 项、`PiFulfillmentProfileTest` 9 项、`PiWorkflowServiceTest` 36 项全部通过。
- Vue TypeScript 检查及 Vite 生产构建成功，保留已有大 bundle 提示。
- 扩展 Node 检查 38 项，37 项通过、1 项失败。失败为 `pi-shipment-optional.test.mjs` 的旧测试上下文遗漏 `manualWorkflow`；在上一生产版本 `53c39bf` 的独立源码中复现同一错误，本次与上一版本的该测试及 `PiWorkspace.vue` 完全一致。未将该失败算作通过。
- PDA 测试首次因独立源码目录缺少依赖链接而未加载，补齐本地依赖链接后 5 项全部通过。
- 新 JAR 与上一线上清单相比只有 6 个预期类条目变更；913 个条目均纳入摘要核验，依赖和数据库迁移文件无变化。
- 本地材料：`tmp/deploy-all-20261119/`，包含独立源码、构建日志、测试汇总、发布脚本、清单和校验输出。

## 发布包与回退

- GitHub Release：`deploy-20261119-438a844-all-changes`；资源：`bundle-438a844.zip`，1,691,283 字节。
- Bundle SHA-256：`c291638711ac5e19ba274a00855b6b2d8df554259da2ec231fd4d2d2eb543690`。
- Delta SHA-256：`b7a6b39c45fe2bc6f28a0c34bcb1f978120f214e385424314964a5936be485b3`。
- 目标版本：`C:\bike\releases\20261119-438a844-all-changes`。
- 原版本：`C:\bike\releases\20261119-c043fc5-czech-menu`。
- 备份：`C:\bike\backups\20261119-155503-all-changes`；旧入口：`C:\bike\current-before-20261119-155503`。
- 线上 JAR SHA-256：`69d917f17a6d311892962cdc810639fc6c1bd1014ce5bedd5e7a3c2becda9f27`。
- 预检任务 `t-bj073kbktyj6ha8` 成功，确认原版本、JAR 摘要、健康 UP、三个服务 Running，磁盘剩余 19.34 GB。
- 部署任务 `t-bj073kbwfz5yrcw` 成功，退出码 0，耗时 2 分 35 秒；GitHub 下载通过官方 API 获取并校验散列。
- 发布脚本先核验旧版本及包散列，准备独立发布目录，再停应用备份数据库、附件、配置和服务；切换 junction 后比较迁移状态和业务聚合快照，失败恢复旧入口。所有旧目录和备份保留。
- 回退使用旧版本入口；不要恢复历史数据库备份覆盖上线后的业务数据。

## 线上验收

- `BikeMySQL`、`BikeServer`、`BikeNginx` 全部 Running；最新启动日志错误数为 0。
- 56 个发布文件与 4 份备份摘要通过核验，913 个 JAR 条目与构建清单一致。
- 停服期间部署前后业务聚合快照一致，V58 成功、失败迁移 0、欧洲业务表 3 张，发布前后迁移状态一致。
- 公网管理端及 PDA 首页均 HTTP 200、健康 UP；管理端 46 个静态资源、PDA 3 个静态资源 SHA-256 与发布清单一致。
- 两域名下装饰图片、购销合同、欧洲订单、PI、出货仓及履约配置接口匿名访问均为 401。
- 内置浏览器沿用已登录 admin 会话完成运营页读取，数据加载正常。主题色为 `#b44e36`，两处默认装饰图片加载成功，naturalWidth 为 140。
- 账号菜单“更换装饰图片”弹窗正常，显示当前账号、默认大图和侧栏预览；没有修改图片时保存禁用，未显示读取错误。本次未上传或重置生产账号图片。
- 未出库的现有外销 PI（ID 20）列表显示已发 0、未发 1；详情履约进度 4/7、57%，交付、收款跟进及结案保持待完成。此次只读检查，没有点击“确认并下一步”。
- 新版工作台、运营页、PI 列表和详情正常加载，浏览器未捕获 error。未创建、修改或推进业务单据；不代表真实扫码、出库、收款全流程验收。
- 云助手截图：`output/playwright/all-changes-deploy-20261119.png`；装饰弹窗：`output/playwright/all-changes-decoration-live-20261119.png`。
- 工作台和 PI 详情截图：`output/playwright/all-changes-workbench-live-20261119.png`、`output/playwright/all-changes-pi-live-20261119.png`，截图仅保存在本地。
- 完整服务器输出、部署结果、服务器校验及公网校验保存在本地发布材料目录；服务器新目录也保留对应 JSON 文件。
