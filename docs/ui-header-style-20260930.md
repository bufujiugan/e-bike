# 头部裁切修复与全站样式统一

## 需求与原因

依据本轮问题截图及图2（`docs/ui-reference/10-operations-command-center.png`），修复首页头部裁切，并将相同风格应用到其他页面。

原首页 `.cc-hero-art` 以 `background-size: cover` 填充横向区域，区域比例变化会放大并裁去图片上下边缘。其他页面使用独立 Hero 和覆盖在场景上的顶栏，样式及空间分配与首页不同。

## 实施范围

- [x] `PageHero.vue`：场景改为 img，保留原比例并限制在 Hero 内，统一标题、副标题、品牌文字和右侧信息区域，提供 aside 插槽。
- [x] `DashboardHero.vue`：复用 PageHero，插槽显示真实日期与运营数据加载状态。
- [x] `AppLayout.vue`、`TopHeader.vue`：共用 52px 独立顶栏和动态面包屑，保留功能搜索导航。
- [x] `Sidebar.vue`：统一深蓝导航、品牌文字和底部场景装饰，保留滚动、权限过滤和折叠功能。
- [x] `manufacturing-shell.css`、`main.ts`：统一 180px Hero、彩色指标图标、浅蓝背景、表格和卡片边框，包含响应式规则。

主要页面：首页、生产中心、组装进度、整车档案、库存管理、SKU 库存锁定、入库记录、出库记录、出入库记录、销售订单。PI、售后、追溯、系统管理等页面同时复用公共外壳；其专用业务内容保持原有结构。

本轮只调整前端展示，不修改业务接口、参数、权限、路由及数据库。页面继续调用现有 API 查询已入库的演示数据；演示记录不是工厂实绩。没有重新执行造数或业务写入。

## 本轮验证

| 检查 | 结果 |
| --- | --- |
| TypeScript / Vite 构建 | 通过；保留既有 bundle 超过 500KB 提示 |
| 业务契约核对 | 82 项通过，包括原 API 参数、业务函数、字段、操作和路由 |
| 10 主页面 × 3 档桌面尺寸 | 1440×900、1680×1050、1920×1080，共30组通过；无页面横向溢出，侧栏220px，单个主标题 |
| 场景与右侧信息卡 | 30组图片加载成功，图片尺寸比例误差小于0.02，图片和右侧卡均处于Hero边界内 |
| 主页面运行 | 本次30组访问无JS异常、无API失败；此结论不含生产异常隔离子Tab与PI统计接口 |
| 首页交互 | 16条作业中任务筛选、真实任务详情、三类趋势切换、4条失败扫码记录、补充指标展开、刷新及跳转返回通过 |
| 公共外壳扩展检查 | PI、售后、追溯、用户管理在1440宽度无整页溢出；标题与面包屑一致，顶栏没有覆盖Hero |
| 公共导航与分页 | 侧栏可滚动至系统配置，顶部搜索可进入销售订单，订单第二页真实返回10条，前进后退正常；无业务写请求 |
| 差异检查 | `git diff --check` 通过；既有工作区变更保留，未提交 |

使用 [Playwright 技能](C:/Users/DELL/.codex/skills/playwright/SKILL.md) 完成真实浏览器检查与截图。人工查看了首页1440/1920、生产中心1440、销售订单1440和PI1440截图。

## 独立环境问题

扩展页检查的布局和导航断言通过，但API总检查未通过：`GET /api/pis/dashboard` 返回500。2026-09-30 17:57:35本机后端日志定位到 `PiReportMapper.xml`，MySQL1271，`Illegal mix of collations for operation 'UNION'`。PI页面保留真实错误与重试按钮，本轮未修改后端或掩盖错误。

此前已记录的生产“异常隔离”接口500仍不属于本轮已修复事项。独立账户服务端权限、实物扫码、打印设备不在本轮视觉验收范围。

## 查看结果

- 本机入口：<http://127.0.0.1:5173/dashboard>。现有Caddy已读取新构建的 `bike-web/dist`。
- 首页：`output/playwright/sm-dashboard-1440.png`、`sm-dashboard-1920.png`。
- 业务页：`output/playwright/sm-{route}-{1440,1680,1920}.png`。
- 扩展页：`output/playwright/unified-{pis,after-sales,trace,system-users}-1440.png`。
- 本地检查脚本：`tmp/ui-responsive-check.js`、`tmp/check-unified-shell.js`、`tmp/check-command-interactions.js`、`tmp/ui-contract-check.mjs`。
