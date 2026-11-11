# 欧洲订单独立入口

2026-11-11：完成本地开发和验证，尚未提交、推送或部署。

## 使用方式

- 菜单：销售与交付 → 欧洲订单；路径：`/europe-orders`，可直接打开或收藏。
- 第一次进入选择实际的捷克出货仓。系统按仓库 ID 查询 PI，不按客户国家或仓库名称猜测归属。当前仓库基础资料没有明确的国家分类，因此下拉框展示可选仓库，由经办人确认真实仓库。
- 仓库选择仅在当前站点、当前浏览器、当前账号保存；更换设备、浏览器或清除站点数据后需重新选择。
- 未选仓库或仓库加载失败时不查询全部 PI。关键词、状态、分页、重置和列表导出均保留当前仓库范围。
- 新建 PI 自动带入所选出货仓和“非国内出货”；该入口中固定这两个字段。付款规则、贸易类型、币种、模板、收款主体仍按实际业务和履约配置核对，不自动虚构商业资料。
- 履约配置仅显示适用于该仓库的配置；本机草稿仅展示属于该仓库的草稿。原“PI 与履约”入口仍可查看原有范围及全部本机草稿。
- 停用仓库可查询历史单据，但不能在该仓库新建 PI。没有仓库信息的历史 PI 仍从原入口核对。
- 欧洲页复用原 PI 详情、收款、库存保留、出库、物流、附件和结案逻辑。通用全仓业务看板不在欧洲页展示，避免混淆统计范围。

## 权限与接口

入口面向有 PI_READ 的内部业务账号；操作按钮继续使用原有权限。查询保留客户授权范围、代理商范围和财务脱敏，不因仓库筛选增加数据权限。

- GET /api/pis：增加可选 warehouseId 参数。
- GET /api/pis/export.xlsx：增加同名可选参数，全部导出分页均沿用该范围。
- GET /api/pis/warehouse-options：内部 PI_READ 账号可读取仓库选项，包含已停用仓库用于历史查询。
- 原列表及 Service 调用方式保留；不传 warehouseId 时行为不变。无数据库迁移，不新增订单或履约状态。

## 修改位置

- bike-web/src/App.vue：导航、路由、权限及复用页面。
- bike-web/src/components/PiWorkspace.vue：仓库偏好、列表/导出范围、新建默认值、草稿范围、请求失效处理及页面布局。
- bike-web/src/pi-api.ts：可选查询参数和仓库选项接口。
- PiController / PiService / PiServiceImpl / PiMapper / PiMapper.xml：仓库筛选、列表计数及只读仓库选项；SQL 位于 Mapper.xml。
- PiReportController / PiReportService：按相同仓库范围分页导出。
- bike-server/src/test/java/com/bike/server/pi/PiWarehouseQueryTest.java：仓库查询、权限、SQL 组合和跨页导出回归。

## 验证与边界

- Java 21 离线 Maven：PiWarehouseQueryTest、PiServiceTest、MapperResourcesTest 共 36 项通过，0 失败、0 跳过。包含 7 项新增回归，以及生成的 Mapper SQL 保留客户/代理商范围、101 条跨页导出检查。
- 前端 vue-tsc 与 Vite 生产构建通过，仍有原有大包提示。
- 本机草稿存储测试 9 项全部通过。
- Playwright 使用真实 Vue 页面与模拟 API 验证：独立菜单、未选仓库、选择后查询、重置、导出参数、新单仓库带入、刷新恢复、原入口切换、只读账号、账号偏好隔离、停用仓库、加载失败不回退全量查询。
- 1440px 与 390px 页面检查通过，手机无页面横向溢出；明细表沿用原表格内部横向滚动。
- 验证记录：tmp/europe-backend-tests.log、tmp/europe-web-final-build.log、tmp/europe-browser-boundaries.log；截图位于 output/playwright/europe-orders-1440-20261111.png、europe-orders-390-20261111.png、europe-order-create-20261111.png。
- 本次未连接生产库或写入真实业务数据；未声称完成真实 MySQL / 线上端到端验收。截图中的仓库和订单均为验收模拟数据。

## 发布与回退

前后端需一同发布：旧后端会忽略新的筛选参数，不能单独上线新前端。发布后应以真实仓库和账号核验列表、导出范围及权限。回退到发布前前后端版本即可，无数据迁移回退；浏览器仓库偏好不影响真实单据。

工作区原有未提交改动继续保留；本功能尚未形成独立提交，发布时须核对实际变更范围。
