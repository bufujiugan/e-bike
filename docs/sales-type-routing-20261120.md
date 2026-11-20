# 销售类型选择与单据模板按原件重做

日期：2026-11-20。本地实现与验证，未部署生产，未提交 Git。

## 背景

客户反馈两个问题：

1. 通用"PI与履约"建的内销 CNY PI 没有销售入口标识，系统回落到英国 PI 版式并写成"DOMESTIC / CNY"。
2. 销售订单没有内外销字段。客户锁定记录"转订单"后，只要没关联 PI 就当内销，直接出购销合同；合同按钮还放在第 2 步"核对放行与出库"。

本次决定：内外销由经办人明确选择，系统不根据客户、币种或有无 PI 猜测。

## 入口与模板

| 销售类型 | 选择位置 | 单据 | 模板 |
| --- | --- | --- | --- |
| 内销 · 购销合同（DOMESTIC） | 锁定转单三选一 / 销售订单 → 内销新建 / 订单办理补选 | 购销合同 Excel | `10.23金轮信德-广州塔威 购销合同(1).xlsx`（不变） |
| 外销 · PI（EXPORT） | 锁定转单三选一 → 销售订单 → 外销 | PI Excel / PDF（`PI_V6`） | `260728英国builtPI.xlsx` 原件底板 |
| 欧洲 · PI（EUROPE） | 锁定转单三选一 → 欧洲销售与交付 → 正常 PI 销售 | PI Excel / PDF（`PI_EUROPE_V2`） | `France_1104_PI.xlsx` |

## 订单销售类型

- V59 迁移：`bike_sales_order.sales_type`（可空）。迁移不回填历史数据。
- 生效类型（`OrderMapper.xml effectiveSalesType`，与 `SalesType.ofPi` 规则一致）的判断顺序：
  1. 经办人明确选择的类型；
  2. 关联欧洲工厂直签的订单，归为 EUROPE；
  3. 按最新关联 PI 判断：`CZECH_PI` 为 EUROPE，`CHINA_SALES` 为 EXPORT，非国内出货为 EUROPE，外销为 EXPORT，其余为 DOMESTIC；
  4. 都不满足时为空，即"待选类型"。
- 锁定页"转订单 / PI"先三选一：
  - 内销：在锁定页直接生成订单。接口 `POST /api/inventory-locks/{id}/orders` 新增 `salesType`，必须为 `DOMESTIC`；缺失时返回 `ORDER_SALES_TYPE_REQUIRED`，外销或欧洲返回 `LOCK_CONVERT_USE_PI`。
  - 外销、欧洲：跳转到对应菜单，自动打开"从 SKU 锁定生成 PI"，并带入客户和这条锁定。PI 转换生成的履约订单按 PI 入口记录类型。
- 待选类型订单在订单办理中补选一次（`POST /api/orders/{id}/sales-type`），选定后不可改。确认订单前必须已有类型（`ORDER_SALES_TYPE_REQUIRED`）。
- 列表与关联：
  - 销售订单 → 内销列表只显示内销和待选类型订单（`/api/modules/orders?salesType=DOMESTIC,PENDING`），并新增"销售类型"列。
  - 列表行上的"确认"快捷按钮已移除，统一进入订单办理。
  - 外销 / 欧洲新建 PI 只能关联同类型订单；后端在关联及确认时校验（`PI_ORDER_SALES_TYPE_MISMATCH`）。

## 订单办理顺序

- 内销订单第一步"生成购销合同 → 确认订单"。确认前会提醒本窗口是否已生成合同，但不卡出库。
- 确认后，第 2 步只保留放行和出库提示。需要时可在"订单与来源"里重新生成或下载合同。
- 外销 / 欧洲订单不显示购销合同，提供"前往生成 PI 并关联本订单"。

## PI 入口收口与历史归类

- 通用"PI与履约"和工作台 PI 页不再提供"新建 PI""从 SKU 锁定生成 PI"，只办理已有单据。
- `POST /api/pis` 与 `/api/pis/from-locks` 要求 `document.business.salesChannel`，否则返回 `PI_SALES_CHANNEL_REQUIRED`。
- 草稿预览和新归档按销售类型选版式：欧洲为 `PI_EUROPE_V2`，外销为 `PI_V6`，其余（无入口标识的国内内销）为 `PI_DOMESTIC_CONTRACT_V1`，即用购销合同版式输出。已入队或已归档的文件保留原版本（`PI_V5`、`PI_EUROPE_V1` 等渲染器未改）。

## 版式

- `PiUkInvoiceRenderer`（PI_V6）读取 `templates/uk-built-pi.xlsx`。该文件由原件清除示例客户、单号、价格和银行资料后得到，并去掉图片；卖方为 Teewing 时按原锚点加入裁切后的 logo。
  - 去掉 Revision/Status 列和商品图片。
  - 恢复 FOB 行、富文本粗体标签，以及 Bank Address / SWIFT / Bank Code / Branch Code。
  - 折扣、税、运费、其他费用只在有金额时追加。付款与交付条款、备注列在银行资料之后。
- `PiEuropeDocumentRendererV2`（PI_EUROPE_V2）：
  - 修复开单日期被"R1 / DRAFT"覆盖的问题，编号写作"No."。
  - 恢复 KVK-No. / VAT / IBAN（非 IBAN 格式标注 Account）/ SWIFT。
  - 补买方 VAT 和 Dispatch Address，保留"Downpayment invoices"。
- `PiDomesticContractRenderer`：按购销合同模板输出历史内销 PI。商品超过 9 行时自动插行；报关型号、外观留空；合计直接写入成交总额。
- `PiSheetPdfRenderer`：按工作表的列宽、行高、合并、填充、边框、对齐、富文本粗体和图片输出 PDF，与 Excel 版面一致。
  - 直接读取 borderId，兼容 WPS 文件缺少 applyBorder 的情况。
  - 只在商品区跨页时重复表头；略超一页时缩放为单页，Excel 同步设为"调整为 1 页"。

## 新增资料项（V59）

- `bike_pi_template` 新增：`bank_address`、`swift_code`、`bank_code`、`branch_code`、`seller_registration_no`、`seller_vat_no`（均可空），在"模板与银行资料"中选填。
- PI 快照 JSON 新增：`bankAddress`、`swiftCode`、`bankCode`、`branchCode`、`sellerRegistrationNo`、`sellerVatNo`、`buyerVatNo`、`dispatchAddress`、`productCategory`。为空时不写入 JSON，历史快照和请求哈希不变。
- 卖方登记号 / VAT 随模板固定；银行附加资料与银行账户一样，可按本单调整。
- **上线后需发布含新银行与税号资料的新模板版本**，否则对应栏目留空。

## 兼容与回退

- 原接口路径不变，新增参数都是可选或带兼容构造器。`SalesOrderCreateRequest`、`LockOrderRequest`、`PiTemplate`、`PiTemplateRequest`、`PiDocumentSnapshot` 均保留旧构造器。
- 发布顺序：先发布后端（含 V59），再发布前端。旧前端转单时不传 `salesType`，会被拒绝，因此前后端须同批发布。
- 回退应用即可，V59 新增列可保留，旧版本忽略这些列。

## 验证

- 后端全量单元测试：707 项，0 失败，220 项依赖环境的测试跳过。
- 本机 MySQL 8.4.6：
  - 新增 `SalesDocumentRoutingMysqlTest` 通过。覆盖 59 个迁移、生效类型推断、列表与模块筛选计数、一次性补选、模板新列映射、PI 订单选项类型。
  - 原 MySQL 夹具测试：SkuCommitment、PiOrderBinding、PiSkuRevision、PiQuantityRevision、PaymentPriority、InventoryProjection 通过。
  - `PiConversionMysqlTest.differentModelsConvertAndPayTogether...` 的 1 项失败在 HEAD 原代码上同样存在，与本次修改无关。
- 前端：`vue-tsc` 与 `vite build` 通过；场景、本机草稿、模板校验 Node 测试 18 项通过。
- 本机端到端：后端 jar + MySQL + Vite + Chromium，覆盖以下路径：
  - 锁定三选一；
  - 内销建单后第一步出合同，再确认；
  - 外销跳转并自动带入锁定；
  - 历史订单显示待选类型面板；
  - 通用 PI 页无新建入口；
  - API 拒绝未选类型转单、二次改类型和无入口新建 PI。
- 证据目录 `tmp/sales-type-routing-20261120/`：
  - `previews/`：三种版式 Excel、PDF 与渲染图；
  - `compare/`：与原件并排对比；
  - `screenshots/`：界面截图；
  - 测试日志。

## 未覆盖

- 未部署生产，未使用真实客户数据。
- 未用 Microsoft Excel 或 WPS 实机打开核对字体；PDF 使用内置 Noto Sans SC / Helvetica 字体，与原件的微软雅黑 / 等线有字形差异。
