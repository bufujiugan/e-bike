# PI 发票版式修正（2026-11-11）

状态：本地代码和版面验证完成，未提交、未部署，不修改原始客户 Excel 或现有归档文件。

## 问题与修改

原 `PI_V4` 的 Excel 已使用五列参考发票，PDF 却仍使用旧的逐行双语列表。新版 `PiReferencePdfRenderer` 独立实现 `PI_V5`，使直接导出 PDF 与 Excel 打印在纸张方向、栏目、信息分区上对齐。两者仍使用各自排版引擎，字体度量和具体分页不承诺像素一致。

- A4 竖版、右上方品牌标识、居中标题、左右买卖方、蓝色订单信息栏。
- 五列商品网格、合并 A:D 位置的合计标签、右对齐金额；条款缩进，收款资料独立左对齐。
- 完整显示联系人、联系方式、长单号、长地址、商品图片、条款；按可用空间分页，商品页重复表头，页脚显示 PI 号、版本和页码。
- 金额直接读取快照，未重算或改变价格；品牌仅适用于既有 Teewing 卖方识别规则。
- 不从客户示例复制业务数据、签名或银行账户。当前结构没有独立的 SWIFT、银行地址、Bank Code、Branch Code 字段，本次不新增这些字段或硬编码其内容。

## 调用链与兼容

`PiTemplateService.selectRenderer/publicSnapshot → PiDocumentService → PiReferencePdfRenderer`；后台归档由 `PiExportWorker` 使用任务内保存的版本和快照渲染。

新导出映射：`DOMESTIC_V3`、`EXPORT_V3`、`INVOICE_V1 → PI_V5`；`STANDARD_V3 → PI_V3`。历史 `PI_V1`–`PI_V4` 与 `PI_INVOICE_V1` 的 PDF 路径保持原实现，重试已存在的归档任务继续读取原快照，不自动换版。

`PiView` 只新增可选返回字段 `sourceOrderNo`；`PiMapper.xml` 在已有来源订单连接上选择业务订单号，归档 allowlist 将其固化到 JSON。没有新增数据库列或改变 Controller 请求。旧快照缺失该字段时留空，不用内部 ID 代替。旧版 Excel 的订单号栏继续保持原行为。

确认 PI 时继续在原事务内保存归档快照，异步任务不回查可变模板或订单资料。本次不新增事务、缓存、消息或调度规则。代码回滚可恢复模板版本映射；如已生成 PI_V5 待处理任务，回滚包仍需保留 PI_V5 渲染支持。

## 验证

- 单件示例：相同快照生成直接 PDF 与 Excel，经 LibreOffice 打印为 PDF，两者均 1 页；人工核对抬头、网格、金额、条款和银行资料。
- 50 商品：直接 PDF 共 6 页，已检查全部页面，商品编号、金额、表头和页码完整。
- 测试覆盖长联系人/地址/单号、高精度金额、不混用品牌、商品图片、历史版式、归档快照和后台 PDF/XLSX 写入。
- 定向 32 项：22 通过，10 项 MySQL 测试因环境条件不满足而跳过。
- 全量 584 项：374 通过，210 项需外部环境的测试跳过，0 失败。未进行生产接口、数据库或在线导出验收。

预览目录：`output/pi-v5-verified/`；`pi-v5-invoice-preview.pdf` 与同名 `.xlsx` 使用测试数据，不是真实交易单据。`excel-print/` 是 Excel 打印结果；`multipage/PI_V5.pdf` 为多页测试。

验证日志：`output/pi-invoice-v5-revision-test-20261111.log`、`output/pi-invoice-v5-regression-20261111.log`。
