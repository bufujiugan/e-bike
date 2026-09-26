# E-BIKE 聚合码与 Web UI 验收记录（2026-09-25）

## 结果与范围

在现有代码上续接完成，全程保留既有未提交修改。Vue 3、TypeScript、Vite、Element Plus 架构不变。生产中心真实页面为 bike-web/src/components/ProductionManager.vue，使用既有 /api/production/* 接口。公共布局及登录在 App.vue，公共主题在 style.css，生产专用样式在 production.css。H5 保留扫码操作端布局，仅接入聚合码及追溯修正。

## UI

- Sidebar：深蓝品牌区、权限过滤的可折叠菜单分组、当前页面高亮；所有原业务菜单保留。
- Header：白色标题区、路径、页面搜索、真实用户与最近加载时间。
- Login：参考图车辆工厂场景、E-BIKE 智慧管理系统标题、四项业务图标、真实账号密码表单。
- Production：Hero、8 个图标 Tab、SKU/状态/日期筛选、搜索/重置/刷新、新建任务入口、五种编码、状态圆点、右侧操作、中文分页。
- Shared tables：有限列宽、完整编码悬停提示、固定操作栏及表头、表格内滚动；详情改为中文字段及真实嵌套明细表，优先显示聚合码。
- Permissions：分组 checkbox 树、原权限回显及父子选择，提交仍为原权限叶子数值 ID。

## 业务与接口边界

视觉重构没有更改 API 地址、请求方式、路由、登录 Token 或后台授权。Web 创建任务复用现有 PDA 创建协议及权限。聚合码业务作为独立范围保留并补齐：返工/售后增量接收聚合码，记录返回补充真实聚合码；生产 keyword 增加聚合码匹配；PartView 增加 currentVehicleQrCode。因此整个工作区并非“没有任何后端或 DTO 修改”，但没有数据库结构变更、接口路径迁移或权限放宽。兼容原车架 API，保留历史记录，不自动虚构关联。

完成状态倒序及修改时间倒序、五码展示沿用此前实现。SKU 表示商品规格/库存管理单位，同一车型的颜色、电池或配置组合可对应不同 SKU；聚合码用于标识具体一台整车，两者用途不同。

发货后业务附件仍保留；历史未登记对象继续作为单独盘点事项，没有自动清理。

## 验证

| 检查 | 结果 |
| --- | --- |
| 既有后台全量及夹具修复重跑汇总 | 454 项，449 通过、5 条件跳过、0 失败/错误 |
| 最新生产 MySQL/服务/自定义角色读取测试 | 44 项通过，与汇总重叠，不重复累加 |
| Web/H5 类型检查、生产构建 | 通过；Web 主 bundle 保留 >500KB 体积提示 |
| Java 打包、当前服务 health | 通过 / UP |
| 独立 lint | 项目未配置 |
| 全站浏览器 | 33 条页面路径；无 JS 异常、失败 API、数据错误、页面横向溢出 |
| 生产真实业务 | 搜索、SKU/状态/日期筛选、分页、详情、创建/幂等/取消、8 Tab、空态、加载、网络失败恢复通过 |
| 生产权限 | 真实 MANAGER 账号没有组装权限时创建/异常入口不可用 |
| 聚合码收尾 | 13 项 API/UI/尺寸检查通过：完整码/局部码/组合过滤、绑定关联、详情与订单明细、关联车辆追溯 |
| H5 追溯 | 登录、默认聚合码、部件历史、关联车辆 QR 跳转 4 项通过 |
| 桌面适配 | 1920×1080、1680×1050、1600×900、1440×900、1366×768；另测 390×844 |
| 差异检查 | git diff --check 通过，无回滚；相同值的表格 key 已修正；无新增 npm 依赖 |

此前首次测试中 3 项精简 MySQL 夹具缺字段/表的问题已修复并重跑，不将首次失败隐藏为从未发生。浏览器证据使用真实后端；网络失败测试仅中断一次请求，不替换业务数据。

## 参考图差异与交付界限

保留真实菜单、字段及操作，因数据列多而采用表格内滚动。分页沿用真实 50 条/页；没有伪造参考图的批量勾选、More 操作或无对应能力的 20 条分页。登录使用参考场景的本地裁图，不包含图中完整建筑背景；UI 使用实际表单，响应式比例与参考静图有所不同。

运行地址：Web http://127.0.0.1:5173/production，H5 http://127.0.0.1:5174，服务 http://127.0.0.1:18182。当前数据为真实接口管理的隔离验收数据，非正式客户生产数据；本次不代表扫码枪、打印机、生产运维及正式发布已验收。代码保留为未提交修改。

## 验收证据

- output/ui-verification.json：33 页面及权限树。
- output/production-functional-verification.json、production-permission-verification.json：生产功能及权限。
- output/qr-final-verification.json、h5-qr-verification.json：聚合码收尾。
- output/login-final.png、vehicles-final.png、vehicle-detail-final.png、order-detail-final.png、h5-trace-final.png：本次视觉复查。
- output/production-final.png、production-{1920,1680,1600,1440,1366,390}.png：生产页适配。
- output/qr-search-tests.log、qr-final-package.log、web-final-build.log：工程证据。

## 修改文件（相对仓库根目录）

- bike-h5/src/App.vue
- bike-h5/src/components/PiDispatchPanel.vue
- bike-h5/src/components/ProductionStation.vue
- bike-h5/src/components/TraceDetails.vue
- bike-server/src/main/java/com/bike/server/aftersales/AfterSalesCaseCreateRequest.java
- bike-server/src/main/java/com/bike/server/aftersales/AfterSalesCaseFilter.java
- bike-server/src/main/java/com/bike/server/aftersales/AfterSalesCaseView.java
- bike-server/src/main/java/com/bike/server/aftersales/AfterSalesMapper.java
- bike-server/src/main/java/com/bike/server/aftersales/AfterSalesServiceImpl.java
- bike-server/src/main/java/com/bike/server/module/ModuleServiceImpl.java
- bike-server/src/main/java/com/bike/server/order/OrderVehicleView.java
- bike-server/src/main/java/com/bike/server/part/PartView.java
- bike-server/src/main/java/com/bike/server/production/ProductionContracts.java
- bike-server/src/main/java/com/bike/server/production/ProductionMapper.java
- bike-server/src/main/java/com/bike/server/production/ProductionServiceImpl.java
- bike-server/src/main/java/com/bike/server/rework/ReworkMapper.java
- bike-server/src/main/java/com/bike/server/rework/ReworkRequest.java
- bike-server/src/main/java/com/bike/server/rework/ReworkServiceImpl.java
- bike-server/src/main/java/com/bike/server/rework/ReworkView.java
- bike-server/src/main/java/com/bike/server/stock/StockRecordView.java
- bike-server/src/main/resources/mapper/AfterSalesMapper.xml
- bike-server/src/main/resources/mapper/DashboardMapper.xml
- bike-server/src/main/resources/mapper/ModuleMapper.xml
- bike-server/src/main/resources/mapper/OrderMapper.xml
- bike-server/src/main/resources/mapper/PartMapper.xml
- bike-server/src/main/resources/mapper/PiDispatchMapper.xml
- bike-server/src/main/resources/mapper/ProductionMapper.xml
- bike-server/src/main/resources/mapper/ReworkMapper.xml
- bike-server/src/main/resources/mapper/StockMapper.xml
- bike-server/src/test/java/com/bike/server/aftersales/AfterSalesMysqlTest.java
- bike-server/src/test/java/com/bike/server/module/AuditPrivacyMysqlTest.java
- bike-server/src/test/java/com/bike/server/pi/PiMysqlTest.java
- bike-server/src/test/java/com/bike/server/production/ProductionAttachmentMysqlTest.java
- bike-server/src/test/java/com/bike/server/production/ProductionMysqlTest.java
- bike-server/src/test/java/com/bike/server/production/ProductionServiceTest.java
- bike-server/src/test/java/com/bike/server/rework/ReworkServiceTest.java
- bike-web/src/App.vue
- bike-web/src/after-sales-api.ts
- bike-web/src/components/AfterSalesWorkspace.vue
- bike-web/src/components/PiWorkspace.vue
- bike-web/src/components/ProductionManager.vue
- bike-web/src/components/TraceDetails.vue
- bike-web/src/components/TraceQuery.vue
- bike-web/src/main.ts
- bike-web/src/pi-api.ts
- bike-web/src/style.css
- docs/api.md
- docs/progress.md

## 新增文件

- bike-server/src/test/java/com/bike/server/aftersales/AfterSalesAggregateCodeMysqlTest.java
- bike-web/public/images/bike-factory.jpg
- bike-web/src/components/AppIcon.vue
- bike-web/src/components/RecordDetails.vue
- bike-web/src/production.css
- docs/ui-acceptance-20260925.md
