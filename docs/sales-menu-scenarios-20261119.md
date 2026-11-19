# 国内与捷克销售入口调整

日期：2026-11-19。销售场景与购销合同提交 `53c39bf` 于 13:55:59（Asia/Shanghai）部署生产；捷克菜单归属更正提交 `c043fc5` 于 15:29:00 部署生产。发布及线上核验记录分别见 `docs/deployment-sales-contract-20261119.md` 和 `docs/deployment-czech-menu-20261119.md`。

后续本地调整见 `docs/sales-document-routing-20261119.md`：国内外销固定英国 built PI 参考版式；明确属于国内内销的历史 PI 关联订单可生成购销合同。下文“已关联任何 PI 不可导出”为原发布版本行为，不再代表本地新逻辑。

## 业务入口

| 菜单 | 销售类型 | 办理方式 |
| --- | --- | --- |
| 销售订单 | 内销 | 在订单办理中填写合同信息，按指定模板生成并下载购销合同 `.xlsx`，不生成 PI |
| 销售订单 | 外销 | 新增“外销 · PI”，复用 PI 开单、修订、收款、放行、物流及单据导出 |
| 欧洲销售与交付 → 正常 PI 销售 | 捷克内销 | 新增“捷克内销 · PI”，复用内销商品、价格、税费和付款字段，单据仍走 PI 流程 |
| 欧洲销售与交付 → 正常 PI 销售 | 捷克外销 | “捷克外销 · PI”，沿用已有外销 PI 能力 |

国内外销默认 USD，捷克内外销默认 EUR，币种均可修改。国内外销默认国内出货，捷克默认非国内出货；实际出货地点与仓库仍由经办人核对选择。销售入口与实际出货地点是独立属性。

新单使用既有已发布模板和适用仓库的履约配置，未创建虚构银行、税率或商务配置。未改变原收款门槛、真实库存占用、合同要求及放行规则，也未默认启用简化审批。

## 实现与兼容

- `bike-web/src/views/BusinessModulePage.vue`：国内内销与外销切换，保留原订单列表及办理操作。
- `bike-web/src/components/CzechPiWorkspace.vue`：在 `EuropeSalesWorkspace.vue` 的正常 PI 销售中提供捷克内销、外销及全部／历史 PI 入口，全部按欧洲出货仓筛选；`App.vue` 的 PI 与履约菜单恢复通用 PI 工作台，代理商仍使用原只读能力。
- `bike-web/src/pi-sales-scenario.ts`、`PiWorkspace.vue`、`pi-api.ts`：统一表单默认值、分页／导出筛选、本机草稿归属判断。场景入口固定贸易类型，保留可编辑币种及实际出货地点。
- `PiBusinessTerms.salesChannel`：新增可选 JSON 字段，`CHINA_SALES` 表示销售订单菜单，`CZECH_PI` 表示欧洲销售与交付中的捷克 PI（保留原标识兼容已保存数据）。旧 Java 构造器保留，缺失字段仍可反序列化，不写入 null 字段。
- `PiBusinessServiceImpl.validateTerms`：国内销售入口只允许外销 PI；内销沿原购销合同业务办理。捷克支持两种贸易类型。
- `PiServiceImpl.listBySalesScenario`：保留账号权限、客户范围、代理商范围与财务脱敏；新增条件在 `PiMapper.xml` 中实施，分页及总数采用相同条件。
- `GET /api/pis`、`GET /api/pis/export.xlsx`：新增可选查询参数 `tradeType`、`salesChannel`，省略参数保持原查询能力。导出每一页保持相同筛选。
- 历史没有 `salesChannel` 的 PI 保持兼容，可在通用 PI 工作台查询，也可在欧洲销售所选出货仓下按捷克对应贸易类型及“全部／历史 PI”查询。未批量更新历史快照。缺失入口标识的本机草稿采用相同兼容规则。
- 修订继承原入口标识，并纳入既有履约条款一致性校验。列表筛选不作为授权边界。

没有新增数据库列、迁移脚本、缓存、MQ、定时任务或异步处理。写入仍沿用 PI 保存事务及审计；库存、收款和放行复用原流程。

## 内销购销合同文件补齐

- 入口：销售订单 → 内销 → 办理订单 → 生成购销合同 → 填写并核对 → 生成并下载购销合同（Excel）。
- 新增 `OrderContractController`、`OrderContractService` / `OrderContractServiceImpl`、`OrderContractWorkbook`、`OrderContractRequest` 和 `OrderContractMapper` / XML；前端新增 `OrderPurchaseContract.vue`，由 `OrderJourney.vue` 挂载。
- `GET /api/orders/{orderId}/purchase-contract` 返回适用性和待填写表单；`POST /api/orders/{orderId}/purchase-contract/export.xlsx` 接收合同信息并返回 Excel。均为新增接口，原订单 API、DTO、数据库字段保持兼容。回退时可移除新界面及接口，无需数据回滚。
- 模板资源 `bike-server/src/main/resources/templates/domestic-purchase-contract.xlsx` 派生自用户指定 Excel，保留工作表、44 处合并、列宽、边框和合同区块，清除历史公司、联系人、价格、日期及共享字符串中的历史数据；用户原文件保持原样。
- 客户、车型、数量来自授权后的订单；普通订单当前仅含一个车型，因此填入第一条商品行，其他商品行留空。供方、合同号、单价等明确填写，不复用历史示例。人民币金额重建为正确的逐行计算公式，并写入计算缓存；清除模板的错行及 `#REF!` 引用。可选 USD、成本价格填入后会显示在合同中。
- 条款沿用模板文字并允许修改，签订日期自动同步各日期区；供方同步付款公司及地址。签章区域留空。用户输入按文本写入，银行账号保留前导零，不能变成 Excel 公式。
- 内部账号需同时有 `ORDER_READ`、`ORDER_WRITE`，继续执行订单客户范围校验；代理商、客户、PDA 不可使用。已关联任何 PI（来源订单及履约关联两条关系）、作废或关闭订单不能走此出口。
- 使用只读、可重复读事务取得一致订单快照；校验订单修改时间、车型 ID、数量及需方，旧表单遇到变更会拒绝导出。合同生成不修改库存、订单状态或收款信息，没有后台任务和部分写入补偿问题。
- 当前是按单生成文件：填写内容仅保留当前窗口（收起再展开可保留），刷新/关闭后不会服务器归档；界面已明确提醒保存下载文件。未创建合同审批、签章或归档业务。

发布时应先发布兼容后端，再发布前端。回退界面可恢复旧前端，同时保留支持新 JSON 字段的后端，避免旧版本读写新快照产生字段丢失；不删除或重写已有单据。若需整体回退后端，应先验证旧版本对新字段的读取和保存兼容性。

## 验证

- 前端 `npm run build`：类型检查及生产构建通过。
- Node 测试：场景规则、本机草稿和模板校验共 17 项通过。
- 后端 PI 测试：208 项，166 通过，42 项需数据库环境的测试跳过，无失败或错误。
- 新增覆盖：入口与出货地点相互独立、旧 JSON 兼容、国内内销误走 PI 拦截、分页／总数条件一致、客户与代理商 SQL 限制、财务脱敏、101 条跨页导出。
- 内置浏览器：本地销售订单切换、国内外销新建、捷克内销新建及默认值已检查；未提交真实 PI、收款或出库记录。浏览器检查不代表生产发布或真实履约验收。

日志：`output/sales-scenarios-backend-final-20261119.log`、`output/sales-scenarios-web-build-20261119.log`、`output/sales-scenarios-web-tests-20261119.log`。

内销购销合同补充验证：

- 后端合同、订单服务及库存编辑保护回归共 25 项通过，无跳过；覆盖 Excel 实际响应、嵌套参数校验、权限、客户范围、PI 关联拦截、订单变更、人民币精度、共享字符串历史数据清理、银行账号前导零及公式输入安全。
- Java 导出虚构订单后，用 LibreOffice 打印为一页 PDF 并检查渲染图；条款、双方信息、签章区均显示，3 × 1,234.56 = 3,703.68，未出现公式错误。打印 PDF 仅作验证，产品下载格式为 `.xlsx`。
- 隔离的前端组件测试使用模拟接口与 Java 生成样例，内置浏览器验证空字段拦截、1234.56 小数单价、金额合计和下载提示；390px 宽度下内容宽度也是 390px，无横向溢出。未连接真实订单后端进行业务写入，不代表生产验收。
- 合同表单增加 `step=0.01` 和受控表单校验，修复浏览器原生整数步长阻止小数金额提交的问题。
- 日志：`output/contract-tests-20261119.log`、`output/contract-web-build-20261119.log`；版式验证：`output/contract-preview.pdf`、`output/contract-preview-1.png`；界面截图：`output/playwright/order-contract-desktop-20261119.png`、`output/playwright/order-contract-mobile-20261119.png`。

## 菜单归属更正

用户于 2026-11-19 更正：捷克内销、外销入口属于“欧洲销售与交付”，不属于“PI 与履约”。本次仅调整前端组件挂载，保留欧洲正常 PI 的仓库约束、简化办理及向 Totem 付款能力，工厂直签内外销入口保持原功能；销售订单内销购销合同和国内外销 PI 不变。后端接口、JSON 标识及历史数据无需迁移。
