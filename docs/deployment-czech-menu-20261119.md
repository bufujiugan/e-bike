# 捷克销售菜单归属更正发布记录

2026-11-19 15:29:00（Asia/Shanghai）完成生产切换，15:29:02 服务器校验通过。

## 版本与范围

- 仓库：`bufujiugan/e-bike`；分支：`codex/pi-business-flow-20261103`。
- 应用提交：`c043fc5ed48b79bed33a5a6c20f91db02124cb63`，已推送 GitHub。
- 用户更正捷克业务菜单归属：捷克内销、外销移至“欧洲销售与交付 → 正常 PI 销售”，两者均使用 PI。
- “PI 与履约”恢复通用 PI 工作台；欧洲原有工厂直签内销、外销继续保留。
- 仅调整 `App.vue`、`EuropeSalesWorkspace.vue`、`CzechPiWorkspace.vue` 组件挂载及文档。欧洲出货仓限制、简化办理和付 Totem 能力沿用原逻辑。
- 国内销售订单内销购销合同和外销 PI 不变。未修改后端、数据库、PDA，也未迁移或重写历史单据；`CZECH_PI` 标识保持兼容。
- 从应用提交独立 `git archive` 构建，其他窗口未提交改动不包含在发布包中。

## 发布与回退位置

- GitHub Release：`deploy-20261119-c043fc5-czech-menu`；资源：`bundle-c043fc5.zip`。
- 新版本：`C:\bike\releases\20261119-c043fc5-czech-menu`。
- 上一版本：`C:\bike\releases\20261119-53c39bf-sales-contract`。
- 当前入口：`C:\bike\current`；旧入口：`C:\bike\current-before-20261119-152833`。
- 备份：`C:\bike\backups\20261119-152833-czech-menu`。
- Bundle SHA-256：`b2b7417045eb1f089c45a3cc300d8973d39b5789d2a6b8ec52d20fc055eb319d`。
- 线上 JAR SHA-256：`5456be659674c2414c772120ce2fb35dbf6e85aa649e0a8bc89861fc734acd25`，与上一版本一致。
- 云助手成功任务：`t-bj073k9mhvm2v40`，退出码 0。
- 前两次任务 `t-bj073k90o4sx69s`、`t-bj073k9efkmwlq8` 在 GitHub 下载阶段因网络连接失败退出，未执行停服或切换。成功任务通过 GitHub 官方 API 获取同一资源，校验 SHA-256 后部署。
- 部署校验基准版本和文件，备份数据库、附件、配置及服务后切换；失败恢复旧入口，所有旧目录与备份保留。
- 本次仅前端变更，需要回退时可恢复上一版本入口；不要恢复数据库备份覆盖上线后的业务数据。

## 验证结果与边界

- 独立前端生产构建通过；场景、本机草稿及模板校验共 17 项 Node 测试通过。
- 服务器 `BikeMySQL`、`BikeServer`、`BikeNginx` 均 Running；启动日志错误数 0。
- 54 个发布文件和 4 份备份摘要核验通过；停服期间部署前后业务聚合快照一致。
- 数据库迁移状态 V58 成功、失败迁移 0、欧洲业务表 3 张，发布前后一致。
- 公网管理端及 PDA 首页 HTTP 200、健康 UP，分别 44 和 3 个静态资源 SHA-256 与清单一致。
- 两域名下欧洲工厂订单、PI、出货仓选项及履约配置接口匿名访问均返回 401。
- 内置浏览器在已登录生产页面核验：“欧洲销售与交付 → 正常 PI 销售”显示捷克内销、捷克外销及全部／历史 PI；默认外销，按所选出货仓查询。
- 内销和外销的新建 PI 分别固定对应贸易类型，默认 EUR、非国内出货，并带入且固定所选仓库；沿用欧洲简化办理提示。
- “PI 与履约”显示通用工作台及业务待办，无捷克场景切换。工厂直签内销、外销页面均正常打开。
- 浏览器未捕获 error；未保存新 PI、订单、付款或出库记录。只读页面和默认值检查不代表真实交易与履约全流程验收。

线上截图：`output/playwright/czech-menu-live-20261119.png`。云助手截图：`output/playwright/czech-menu-deploy-20261119.png`。

本地材料：`tmp/deploy-czech-menu-20261119/`，包括完整服务器输出 `server-output.txt` 和公网校验 `public-verification.json`。服务器新版本目录保留 `deployment-result.json`、`release-manifest.json` 及 `verification-20261119.json`。
