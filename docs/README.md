# 项目资料总索引

从 [项目 README](../README.md) 开始运行系统。本目录保留需求演变，不把较早计划覆盖为当前交付事实。

## 需求、会议与沟通

| 材料 | 用途与状态 |
| --- | --- |
| [项目开发任务书](../bike_codex_project_spec.md) | 原始实施基准；后续已确认事项见决策记录 |
| [客户会议需求 DOC](requirements/meeting-requirements.doc) | 初始业务原件，使用 Word/LibreOffice 打开 |
| [业务流程图片](requirements/business-overview.png) | 初始流程原件 |
| [原始进度计划 PDF](requirements/project-schedule.pdf) | 计划参考，不是交付进度证明 |
| [原版功能清单 PDF](requirements/E-Bike生产库存管理系统_项目进度与功能清单_原版.pdf) | 从用户提供的桌面原件补归档，可能与原始计划同版 |
| [售后需求原文](requirements/售后案例申报与查询管理系统需求.txt) | 用户粘贴的新增需求，尚未完整实现 |
| [售后增补功能清单 V1.1](../output/pdf/E-Bike生产库存管理系统_项目进度与功能清单_售后需求增补版_V1.1_20260909.pdf) | 2026-09-09，14页，范围讨论材料 |
| [客户沟通与谈判提纲](../output/pdf/E-Bike项目_客户沟通与谈判提纲_重点加粗版_20260914.pdf) | 2026-09-14，6页，内部会谈准备材料 |
| [09-15 会议纪要 PDF](requirements/生产及业务系统开发规划讨论_2026-09-15.pdf) | 7页，AI纪要原件，需结合用户明确确认使用 |
| [需求沟通记录](communication/需求沟通记录.md) | 按日期整理用户业务要求，已去除凭据、工具内容和重复续跑 |
| [需求决策与版本关系](communication/需求决策与版本关系.md) | 优先级、确认结果、阶段与待确认事项 |
| [需求冲突记录](requirement-conflicts.md) | 两项初始冲突及采纳结果 |
| [生产与 PI PRD](生产与PI订单接入_PRD_v1.0.md) | 业务流程、资源建议、功能和待办；含下一阶段规划 |
| [项目建设方案 Markdown](生产与PI订单系统_项目建设方案.md) / [PDF](生产与PI订单系统_项目建设方案.pdf) | 面向客户的服务器配置与功能清单，不是已签合同 |

## 设计、开发与交付

- [架构与模块](architecture.md)：技术边界、模块定位、事务与迁移。
- [业务规则](business-rules.md)、[权限设计](permission.md)、[数据库](database.md)、[接口清单](api.md)。
- [部署指南](deployment.md)、[开发协作](../CONTRIBUTING.md)、[配置模板](../.env.example)。
- [实施与验收台账](completion-worklog.md)、[当前验收矩阵](current-acceptance-matrix.md)：最新实现、验证证据与未完成事项。
- [上传绑定失败核对](file-upload-reconciliation.md)：V49状态、权限、清理边界及历史残留盘点。
- [开发进度](progress.md)、[测试用例](test-cases.md)：历史实施记录。
- [生产端实施任务](生产端实施任务.md)、[生产端验收与部署](生产端验收与部署.md)：09-16 实现与历史验证。
- [生产端需求核查报告](生产端需求核查报告_2026-09-16.md)：后续发现的页面及业务缺口，验收前必须处理。
- [本次归档任务](项目归档任务.md)、[本次验证记录](verification-2026-09-18.md)。

## 原型与证据

- [单文件交互原型](../E-Bike_高保真交互原型.html)：下载后浏览器打开；GitHub 文件页本身不运行 HTML。
- [原型说明](../output/prototype/使用说明.md)、[原型源码](../output/prototype/index.html)、[原型总览](../output/prototype/E-Bike_高保真原型总览.png)、[原型打包文件](../output/prototype/E-Bike_高保真原型与界面图.zip)。
- [16张原型界面图](../output/playwright/ebike-hifi)、[历史运行截图](../output/playwright)：图片仅表示对应时间的页面，不证明功能全部验收。
- [历史验收证据](evidence/README.md)、[材料制作脚本](archive-tools/README.md)。
- [归档清单与 SHA-256](archive-manifest.json)：记录代码、需求、文档、原型和证据路径及哈希。

## 保留与排除规则

提交源代码、锁文件、迁移、需求原件、沟通整理、交付 PDF、原型、选定截图及脱敏测试证据。保留原有相对路径以减少旧文档失效。

不提交真实数据库/备份、账号密码、JWT、COS 凭据、`.env`、浏览器会话、日志、安装包、依赖目录和编译产物；本机原件仍保留。完整工具执行日志不是需求沟通材料。历史临时截图若已无法找到，不伪造补齐。

公开仓库不等于授予商业使用许可；当前未指定开源许可证，使用授权应向权利人确认。
