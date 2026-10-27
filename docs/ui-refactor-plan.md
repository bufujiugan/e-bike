# Smart Manufacturing OS UI 重构计划

日期：2026-09-30。依据用户确认的九张最终效果图，仅修改 Web 表现层。

## 不变边界

- 保留全部 URL、API 路径/参数/返回结构、权限条件、表格字段与业务按钮行为。
- 不修改后端、数据库、PDA/H5、库存规则、订单状态、写入幂等逻辑。
- 缺失指标显示 `—` 与原因；真实零显示 0；空结果、错误、无权限分别展示。
- 当前页统计明确标注范围，不冒充全量。车型重复承诺与 SKU 新增锁定上限不累计。
- 组装四码扫码进度不映射为七道工序完成率；无工序事件的流程仅作说明。
- 不删除文件，不处理既有两张未跟踪运行截图，不提交或推送 Git。

## 修改范围

- `bike-web/src/App.vue`：提取布局、接入七个专用展示页面，保留业务事件与路由。
- `bike-web/src/main.ts`、`style.css`、`production.css`：接入统一主题，保留非目标业务样式。
- `components/ProductionManager.vue`、`SkuInventoryPanel.vue`、`AppIcon.vue`：原位接入设计系统。
- 新增 `components/layout`、`dashboard`、`business`、`charts`、`table`、`common`。
- 新增 `views` 展示页面、`config/page-presentation.ts`、`styles` 与纯展示数据适配。
- 归档 `docs/ui-reference`；Hero 仅使用用户参考图中的场景裁切，不使用整个页面截图冒充 UI。

## 实施检查表

- [x] Phase 1：Tokens、Layout、Sidebar、Header、Hero、指标、流程、表格、空状态；构建与路由/API 基线。
- [x] Phase 2：生产中心八入口、流程、当前任务、任务列表。
- [x] Phase 3：组装进度、整车档案。
- [x] Phase 4：库存管理、SKU 库存锁定。
- [x] Phase 5：入库、出库、出入库。
- [x] Phase 6：销售订单。
- [x] 类型检查与生产构建；真实浏览器九页和关键交互。
- [x] 1440×900、1680×1050、1920×1080 截图与页面溢出检查。
- [x] 空态、错误、权限源码、原有操作回归及差异复核；未提交实际业务写入。

交付边界：异常隔离子 Tab 的既有读取接口返回 500，已只读定位并保留失败/重试 UI，不越界修改后端或数据库。详见 `ui-refactor-acceptance.md`。

## 数据限制

通用模块仅支持 keyword/page/pageSize，SKU 查询仅支持 modelId/page/pageSize。不会添加无效服务器筛选参数。Dashboard 汇总/趋势仅向有 DASHBOARD_READ 的内部账号调用，并注明与列表搜索无联动。无法得到全量的数据按当前页展示或标记未提供，不遍历分页制造全量统计。趋势、产线节拍、追溯命中率等缺少数据时不演示造数。

## 验证方法

使用现有 vue-tsc/Vite 构建，Playwright CLI 浏览器截图与真实读取验证；生产函数、请求参数、权限与字段清单对照。不会把历史验收或测试夹具冒充本次真实业务证明。未经测试数据授权，不提交新增、锁定、出入库或订单修改。
