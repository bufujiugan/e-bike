# 欧洲销售三场景与本地实现说明

更新日期：2026-11-16。按客户确认口径，Totem／工厂直签的当地内销和外销都先付款后提货。已于当日 20:18（Asia/Shanghai）发布生产，版本及验收详见 [部署记录](deployment-europe-sales-20261116.md)。

## 操作入口

- 销售与交付 → **欧洲销售与交付**（原 `/europe-orders` 地址保留）。页面分为正常 PI 销售、捷克代理当地内销、工厂直签外销三个入口。
- 页面右上角 **操作说明 → 外销／欧洲销售**，也可点击欧洲页面的“查看外销操作说明”。
- 正常 PI 仍可从原 **PI 与履约** 办理。历史单据及原入口的新单保留原规则，欧洲入口新建 PI 默认简化办理。

## 三种办理方式

| 场景 | 单据与款项 | 办理顺序 |
| --- | --- | --- |
| 正常 PI | 业务员制作 PI；客户付款给销售侧；王总付 Totem 单独记账 | 按约定选择先收款或货到付款，跟进备货、交付、收款及付 Totem |
| 捷克代理当地内销 | 工厂合同，按客户确认的业务口径含 VAT；客户直接付工厂 | 合同 → 备货 → 确认工厂已收款 → 提货交接 → 结案 |
| 工厂直签外销 | 工厂合同，按合同不含 VAT；客户直接付工厂 | 合同 → 备货 → 确认工厂已收款 → 提货交接 → 结案 |

工厂直签无需业务员 PI。新单先选客户、币种，合同号、商品说明、金额、参考信息等可后补。VAT 由经办人按工厂合同填写，系统不猜税率。

点击“确认下一步”记录经办人、时间和说明。缺少附件或款项明细不阻断流程；两种工厂直签在提货前均须人工确认工厂已收款。流程确认不会生成资金流水、替代真实扣库存，也不会自动完成实际出库。

已完成的工厂步骤可以“恢复待核对”；撤回收款确认会同时恢复提货与结案待核对。更换客户、币种、工厂或业务类型会使原收款和提货确认失效，资料仍可保存；已有款项后不允许换客户或币种，已有出库则保留原客户、仓库、业务类型及已出库订单关联。

### 实际款项

- PI 客户收款沿用原“收款与放行”。“向 Totem 付款”记录另一方向的款项，各 PI 修订共用系列台账。
- 直签“工厂实际到账”只记录客户付工厂的款项。它与人工“确认工厂已收款”分别记录，不计入我方 PI 收款。
- 实际金额与发生时间须如实填写，币种与所属单据一致。金额以十进制字符串传输，前端以整数分汇总，避免大额精度丢失。
- 收付方、凭证号／参考号和说明可以“补录”；金额、币种或日期错误应撤回重录。撤回保留原记录和原因，不删除流水。此处提供凭证参考信息补录，未新增凭证文件上传功能。
- 撤回工厂款项会将收款、提货和结案恢复待核对；页面同步流程版本，并保留正在填写的表单。已实际完成的出库记录不会因此撤销。

### 使用系统库存

从“库存与锁定”建立普通销售订单，再关联到工厂直签记录；在直签详情可直接打开原订单办理组件。出库沿用原 Web/PDA 能力，继续校验实际车辆、仓库、SKU、数量、库存占用和权限。工厂直接交货且不经过本系统库存时，可以只记交接。

同一销售订单不能同时归入 PI 与工厂直签，也不能关联两个工厂直签记录。新建 PI、确认 PI、实际出库分别保护关联边界，避免重复占用和误出库。

## 代码及接口影响

- `bike-web/src/components/EuropeSalesWorkspace.vue`：三个入口；`FactorySalesWorkspace.vue`：直签表单、流程及订单联动；`SalesSettlementPanel.vue`：实际款项；`PiManualWorkflowPanel.vue`：简化 PI 确认。
- `EuropeSalesGuide.vue` 嵌入原 `ProductionFulfillmentGuide.vue`；相关原流程图文字同步区分 PI 与工厂直签。
- 后端新增 `com.bike.server.europe`，Controller 接收请求，ServiceImpl 编排事务和权限，Mapper.xml 访问数据库。`FactorySalesShipmentGuard` 只检查已关联直签业务的订单。
- `PiManualWorkflow` 仅在快照显式包含 `document.business.workflowMode=USER_CONFIRMED` 时启用；缺省、null、STANDARD 仍按原规则。已发布单据的模式不能在修订中静默切换。
- 简化 PI 可人工确认跟进步骤，无附件或金额前置审批。真实放行仍需 `PI_RELEASE`，使用现有 CREDIT 库存占用能力保留库存，仍校验实际可用数量，不伪造收款。
- 原 Controller 路径与方法不变。PiBusinessTerms 新增可选 JSON 字段 `workflowMode`，保留旧构造器；未删除旧字段、修改已有枚举值或原表字段。

新增接口：

| 方法 | `/api/europe` 下的路径 | 用途 |
| --- | --- | --- |
| GET / POST | `/factory-orders` | 列表／新建 |
| GET / PUT | `/factory-orders/{id}` | 查询／补录资料 |
| POST | `/factory-orders/{id}/steps/{step}` | 确认或恢复步骤，带 revision |
| GET / POST | `/settlements/{type}/{subjectId}` | 实际款项列表／登记 |
| PUT | `/settlements/{type}/{subjectId}/{entryId}/supplement` | 补录款项参考信息 |
| POST | `/settlements/{type}/{subjectId}/{entryId}/reverse` | 撤回错误登记 |

`type` 为 FACTORY 或 PI；PI 的接口 subjectId 使用 PI ID，后端映射到 seriesId。直签读写复用 PI_READ／PI_WRITE；财务读使用 PI_PAYMENT_CONFIRM、PI_RELEASE 或 PI_PAYMENT_REVERSE，登记／补录使用 PI_PAYMENT_CONFIRM，撤回使用 PI_PAYMENT_REVERSE。均要求内部 Web 账号及客户授权范围。

## 数据库与一致性

V58 Java 迁移由 `ProductionMigrationConfig` 注册，结构 SQL 位于 `EuropeSalesSchemaMapper.xml`。新增三表：

- `bike_factory_sales_order`：合同资料、步骤确认、revision 和新建 requestId。
- `bike_factory_sales_order_link`：直签与原销售订单关联，order_id 唯一。
- `bike_sales_settlement`：工厂到账／付 Totem 流水，主体内 requestId 唯一。

资料更新、步骤和审计同事务；直签 revision 检查防止旧页面覆盖。实际出库、直签关联和收款确认撤回共用库存互斥锁，避免提货检查与收款撤回交错。财务登记按主体行锁串行化，PI 跨修订按系列锁；重试 requestId 防重复，撤回幂等。没有新增缓存、MQ、定时任务或异步资金操作。

上线时先备份数据库，先完成 V58 再开放新前端。出现问题时先关闭新入口并保留兼容后端与三张业务表；不要删表或移除已执行迁移。若必须回到旧后端，应停止新流程写入并核对新模式单据，选择兼容补丁或恢复上线前备份，避免旧版本把新单据按原规则办理。回滚生产属于单独发布操作，本次未执行。

## 验证记录

- 后端全量测试：645 项，427 通过，218 环境条件测试跳过，0 失败／错误。包括原 PI、订单、出库测试及新增直签用例。
- 本地独立 MySQL 库 `bike_europe_migration_test_20261116`：V58 迁移、Flyway 校验、订单读写／版本冲突、关联查询、精确金额、款项补录和重复撤回通过；夹具与流水事务回滚，不写真实业务库。
- 前端 `vue-tsc --noEmit && vite build` 通过；原 PI 本地草稿 9 项测试通过。
- Codex 内置浏览器、模拟 API：三入口、无附件推进、直签先款后提货、完整结案、款项补录、撤回同步并保留未保存说明、原操作说明新栏目、390px 页面与表单宽度已检查。
- 开发阶段浏览器使用模拟接口；发布后另完成真实生产入口、直签列表、空白表单与操作说明只读验收，未创建真实业务记录。未进行完整真实收付款／出库流程或实体 PDA/扫码设备验收。

日志位于 `output/europe-sales-backend-final-tests-20261116.log`、`output/europe-sales-mysql-tests-20261116.log`、`output/europe-sales-web-build-20261116.log`。截图在 `output/playwright/europe-*-final-20261116.jpg`；客户确认图为 `output/europe-sales-sequence-20261116-v3/`，第二、第三种已改为先付款后提货。
