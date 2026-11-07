# 统一履约需求复核与修复验收（2026-11-07）

## 当前结论

本轮发现的三个功能缺口均已修复，并完成隔离本地环境的真实后端闭环验证。统一配置按付款方式、交付责任、仓库、模板及单据要求组合，不按国家添加专用流程。新建 PI 必须选择配置；无配置的历史单据保持兼容。

前一版报告中的“尚未完成”描述的是修复前状态。以下为修复后的结果。未部署生产，未操作生产数据库。

## 原问题与修复

| 复核发现 | 已修复行为 | 主要代码 |
| --- | --- | --- |
| 模板仅有固定旧格式 | 发布支持 STANDARD_V3 标准明细表与 INVOICE_V1 双栏发票，均可用于内销／外销；旧归档保持其冻结版本 | PiTemplateRequest、PiTemplateService、PiDocumentService、PiExportWorker、pi-template-layouts.ts、PiTemplates.vue |
| 新单可不选配置退回旧流程 | 新建 PI 和锁定转 PI 共用建单校验；新单或移除已有配置时拒绝缺配置，历史无配置单据兼容 | PiFulfillmentProfileServiceImpl.validateSelection、PiServiceImpl.persistDraft、PiWorkspace.vue |
| 已到账后付款单确认修订被误报库存不足 | 确认修订与重新授权分开；无到账／部分到账／全额到账均可合法修订，旧授权不继承，未授权放行报 PI_CREDIT_APPROVAL_REQUIRED | PiServiceImpl.confirm/release、PiBusinessServiceImpl.requirePaymentAuthorization |
| 说明与预锁规则不一致 | 明确预锁可超过库存，正式保留重新校验；说明、流程图及角色时序同步配置和修订授权规则 | ProductionFulfillmentGuide.vue、ProductionFulfillmentDiagrams.vue、productionFulfillmentSequence.ts |
| 双栏 Excel 打印页脚裁切 | 调整页脚边距，打印预览中单号与页码完整 | PiReferenceExcelRenderer.configureSheet |

## 真实接口闭环

真实 Spring Boot 后端、隔离 MySQL 8.4、本地文件存储；模拟客户、仓库、SKU、库存车辆和收款资料。使用现有 HTTP 接口，不以接口 Mock 替代业务验收。空库执行 57 个迁移成功。

最终完整运行 `FF82D334`：

| 付款／交付方式 | 最终 PI | 结果 |
| --- | --- | --- |
| 预付／我方运输 | 32 | 完成实际出库、送货代仓、物流、单据、结案；撤回收款后恢复待结案，补回到账后重新结案 |
| 预付／客户提货 | 33 | 跳过我方送货代仓；无承运单号时可如实说明提货；完整结案与收款撤回恢复通过 |
| 后付款／我方运输 | 35 | 全额到账后创建修订并确认；重新授权、备货、出库、单据及结案通过 |
| 后付款／客户提货 | 37 | 部分到账后创建修订并确认；重新授权、出库、补足到账、单据及结案通过 |

附加边界 PI 39：配置停用后原单继续办理、新单拒绝；无到账将数量从 1 调为 2，修订确认成功并重新授权。继续修订为 1000 时库存不足，确认失败；新修订保持 DRAFT、原修订保持 CONFIRMED、订单数量仍为 2、CREDIT_HELD 占用保留。

其它负向检查：新单缺配置、清空已有配置、未授权保留库存／放行、缺备货放行、缺 CI／RD／CMR 结案均被拒绝。后付款授权不产生虚假收款。

证据：`output/unified-acceptance/FF82D334/results.json`、`tmp/unified-http-acceptance.log`；可复跑脚本 `tmp/unified-http-acceptance.py`。新增 SQL fixture 位于 `bike-server/src/test/resources/mapper/PiFulfillmentAcceptanceMapper.xml`，仅用于本地隔离验收。

## 自动化与文件验收

- 后端完整 verify：562 项，352 项执行通过，210 项因外部环境条件跳过，0 失败／错误。`tmp/unified-complete-verify.log`。
- 独立 MySQL：35 项全部通过，覆盖配置库存、V57 校验、模板发布归档、付款优先、订单绑定、收款撤回与数量修订。`tmp/unified-complete-mysql.log`。
- 最后一次页脚调整后，导出相关 16 项回归通过。`tmp/unified-export-final.log`。
- 新增持久回归：`PiFulfillmentRegressionTest`、`PiSelectableLayoutTest`。两种版式分别验证 50 条商品、跨页保留、金额及条款完整；PDF 12 页预览已检查。
- 四组合的 4 份 PDF 与 4 份 Excel，金额、币种、SKU 和收款资料一致，Excel 无公式注入。`output/unified-acceptance/FF82D334/export-checks.json`。标准／双栏版式均经打印渲染检查；页脚修复预览在 `output/unified-acceptance/footer-fix/preview.png`。
- 前端 TypeScript 与 Vite 生产构建成功，保留现有大包体积提示。`tmp/unified-web-acceptance-final.log`。

## 页面与 Mock 验收

- 真实登录和真实数据下，内销双栏模板通过页面实际发布，验证版式不绑定国家或贸易类型。发布的模拟模板编码为 `MOCK_UI_1794035914739`。
- 新建 PI 缺配置被拦截；选择后付款客户提货配置后自动带入仓库及模板，付款到期日必填；390px 无页面横向溢出。`tmp/unified-real-draft-results.log`。
- 打开最终后付款客户提货单的实际详情页，显示“交付、结算及所需单据已核对，业务已结案”，16 个步骤完成；截图 `output/playwright/unified-real-closed.png`。
- 操作说明内 18 个链接（含本机 PDA 链接）设置新标签页；实际点击履约配置入口打开新标签页，原说明与滚动位置保留。`tmp/unified-real-guide-results.log`。
- 真实接口通过后追加接口 Mock：模拟配置服务失败与空配置，验证失败提示、重试、无旧选项残留以及禁止绕开配置建单。`tmp/unified-mock-results.log`。
- 截图：`output/playwright/unified-real-*.png`、`output/playwright/unified-mock-config-empty.png`。

## 交付边界

用户提供的首张图片可见内容是“是否国内出货”选项，不包含捷克完整 PI 样单。当前已提供统一可选版式能力，不能据此宣称逐项复刻了未知的捷克正式样单；特定字段和排版仍须取得样单后核对。

CI／RD／CMR 支持附件、发送／回收登记和结案校验，文件需经办人实际制作与发送；不会自动生成正式单据或发送邮件。当前按 PI 修订及节点关联，未扩展每批次独立单据台账。

所有验收均在本地，模拟扫码不等于实际扫描枪、打印机或仓库交接验收。生产部署和正式主体／银行配置仍未执行。升级、兼容及回退说明见 `unified-fulfillment-configuration-20261107.md`。
