# 欧洲 PI France 版式发布记录

2026-11-19 16:15:50（Asia/Shanghai）完成生产切换，16:15:52 服务器校验通过。

## 版本与范围

- 仓库 `bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`。
- 应用提交 `dbcf3a7bfdfbf738bdab6c69414d5499888681e1`，包含 `7b7524f` 的欧洲 PI 版式和 `dbcf3a7` 的长地址布局修正，均已推送 GitHub。
- 上线基准为 `438a844b02c3d8e4b043042b8fb2061c4af92c38`；保留其主题、账号装饰和真实出库校验功能。后续两笔提交 `c4e3a86`、`c351e96` 只修改上一轮发布记录，不影响应用产物。
- 欧洲销售与交付的内销、外销 PI 使用 `France_1104_PI.xlsx` 的版式。卖方、银行、客户、商品及金额沿用本单快照，模板示例数据不进入输出。
- 通过 `PI_EUROPE_V1` 固化新归档版式；既有归档和已入队任务保持原版本及文件。缺少独立快照字段的 VAT 注册号、KVK、SWIFT、买方银行和发货地址留空。
- 国内销售订单内销购销合同不受影响。本次没有修改 API、DTO、数据库字段或迁移脚本。
- 实现和字段规则见 `docs/europe-pi-france-template-20261119.md`。

## 构建验证

- 从固定提交独立导出源码构建，避免其他窗口改动混入产物。
- `7b7524f` 完整离线 Maven `verify`：690 项，471 项通过、219 项因数据库环境跳过，失败和错误均为 0。
- `dbcf3a7` 长地址修正后完成相关测试及打包：23 项全部通过，无跳过。验证长中文地址、长描述以及 Excel 行高限制。
- 前端 TypeScript/Vite 生产构建通过；场景、草稿和模板 Node 测试 17 项全部通过。
- 虚构样例 Excel 经 LibreOffice 实际转 PDF 为 1 页，产品 PDF 为 1 页，均完成视觉检查；50 商品分页测试通过。
- 样例总额 336.70，与保存的行折扣、行税、订单折扣、订单税、运费及其他费用一致。
- 没有创建生产 PI、付款或出库单据；以上不代表真实交易及现场出库全流程验收。

## 发布与回退

- GitHub Release：`deploy-20261119-dbcf3a7-europe-pi-france`；资源 `bundle-dbcf3a7.zip`，1,724,001 字节。
- Bundle SHA-256：`1dbd0a604aeb21449b0cf756385e81852101036424cc556007fdb1520e2dcd35`。
- Delta SHA-256：`791181446d890c2998b26b54f14edcf0c65d5aa6211900ca65a8f32fe9ef6dfc`。
- 当前目录：`C:\bike\releases\20261119-dbcf3a7-europe-pi-france`。
- 前一版本：`C:\bike\releases\20261119-438a844-all-changes`；原入口保留为 `C:\bike\current-before-20261119-161513`。
- 备份：`C:\bike\backups\20261119-161513-europe-pi-france`，包含数据库、附件、配置和服务配置，4 份备份摘要验证通过。
- 线上 JAR SHA-256：`6489278a23b5eff2e11965224dbdbcfbf134c46acf9e4d4c03a24ad0de9f09c4`。
- 首次云任务 `t-bj073kdf4eowr9c` 返回 0 但没有部署输出；只读任务 `t-bj073kdmifnlybk` 确认仍为原版本、目标 release 不存在。规范化启动脚本编码后重新下发，没有把首次状态当作发布成功。
- 实际部署任务 `t-bj073kdqc1i8jr4` 成功，退出码 0，耗时 2 分 14 秒。通过官方 GitHub API 下载并验证包摘要，再备份、切换和验收。
- JAR 仅 8 个预期条目发生变化，916 个条目均核对摘要，56 个发布文件与清单一致。
- 回退恢复上一版本入口并重启服务；所有旧目录和备份保留。不要用历史数据库备份覆盖上线后新增业务数据。

## 线上验收

- `BikeMySQL`、`BikeServer`、`BikeNginx` 全部 Running；最新启动日志错误数为 0。
- 停服期间部署前后的业务聚合快照一致；V58 成功、失败迁移 0、欧洲业务表 3 张，迁移状态没有变化。
- 公网管理端及 PDA 首页均 HTTP 200、健康 UP；管理端 46 个静态资源、PDA 3 个静态资源摘要与发布清单一致。
- 两域名下装饰图片、购销合同、欧洲订单、PI、仓库及履约配置接口匿名访问均为 401。
- 内置浏览器刷新后欧洲外销列表正常，内销与外销新建表单的贸易类型分别为内销和外销，均为 EUR 且不适用国内购销合同。
- 两个新建表单 DOM 均包含 France_1104 固定版式说明。未填写必填项时第二步被正常拦截，因此没有把隐藏的说明文本当作第二步可见页面验收。未填写、保存或确认生产单据。
- 浏览器未捕获 error；打开的空白新建表单已关闭。
- 本地证据目录：`tmp/europe-pi-template-20261119/`，包含发布清单、完整服务器输出、`deployment-result.json`、`server-verification.json`、`public-verification.json` 及样例 Excel/PDF。
- 截图：`output/playwright/europe-pi-france-deploy-20261119.png`、`output/playwright/europe-pi-france-live-20261119.png`；仅保存在本地。
