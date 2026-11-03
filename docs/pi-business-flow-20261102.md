# PI 业务流程调整（2026-11-02）

## 范围

按用户提供流程图调整本地代码。复用 PI、订单占用、流程明细、收款附件、物流附件和出库批次；本次不增加数据库表、不增加迁移、不引入通用工作流引擎。

- 每张 PI 必选一个出库仓库；不同仓库分别建立 PI。是否国内出货独立于内销/外销。
- 内销收款主体广塔、外销香港；银行、账户、收款人可在本单修改，保存快照。已确认单据通过修订修改账户，仓库、出货来源和主体不能更换。
- 先确认 PI、销售合同号和收付款表，再登记真实到账。沿用 PI 金额、币种、付款记录作为收付款台账，不另建财务系统。
- 累计到账达到 shipmentRequiredAmount 后按仓库及 SKU 校验库存，再转为 PAID 占用。原 depositRequiredAmount 保留作为定金约定字段；本流程不以该字段提前占用指定仓库。
- 普通 PI 收款达标后自动建立单 SKU 履约子单；复用既有订单占用。已有精确 SKU 履约关系不重复创建。库存不足保留到账记录，补库存后可重试。
- 使用现有库存互斥和事务；同时校验仓库 SKU、全局 SKU、车型承诺。库存列表中的 PI 已付款占用归属指定仓库，再按库位展示。
- 国内出货须登记 SAVA/银三环购销合同；境外出货不适用。出库还检查合同、收款凭证、按仓占用、放行状态及车辆仓库。
- H5 与原生 PDA 出库增加备注；后台出库记录可查看。原生页面只调整源码，未重打 APK。
- 出库后区分客户/我方联系货代，登记仓库地址和收货联系人。我方送仓须承运商、单号和费用；客户安排可按可获取信息填写，未知信息须说明。
- 到货代仓须到仓日期和本 PI 物流附件；发运沿用真实出库批次。全部出库、全部批次登记发运后才能确认整单签收。
- 修订重新确认销售合同与台账；购销合同和货代基础信息可继承。签收不从旧版本直接继承。
- 撤回物流或出库后，到仓、物流跟踪、签收恢复待确认；原说明保留，操作者须再次核对。
- 生产跟进保留为可选记录，不作为必经发货步骤。

## 主要代码

- bike-server/src/main/java/com/bike/server/pi/PiBusinessServiceImpl.java：仓库、单据、付款占用、物流事实校验。
- bike-server/src/main/resources/mapper/PiBusinessMapper.xml：新增数据库访问，SQL 全部在 Mapper XML。
- bike-server/src/main/java/com/bike/server/pi/PiWorkflowServiceImpl.java：流程顺序、分支、真实业务事实与修订继承。
- bike-server/src/main/java/com/bike/server/pi/PiServiceImpl.java：PI 保存、收款、修订、放行接线。
- bike-server/src/main/resources/mapper/InventoryPolicyMapper.xml：仓库占用展示。
- bike-web/src/components/PiWorkspace.vue、PiWorkflowPanel.vue：建单、账户信息与流程表单。
- bike-h5/src/App.vue；NF6801UNIAPP/bike-pda-app/pages/stock-out/stock-out.nvue：出库备注。

## 历史资料和兼容

接口路径不变，DTO 以可选字段增补；Java 旧构造器保留。业务必填要求加强，旧客户端缺少仓库等资料时会返回明确错误，需要同步更新 Web。

已有 PI 缺少仓库、是否国内出货、收款主体时须通过修订补录；不自动猜测。旧的流程“已完成”但缺少合同或货代证据时可重新补齐。缺少精确 SKU 的历史车型来源订单不能自动转为按仓锁定，须先核对/建立准确履约关系，不能用换仓或猜 SKU 绕过。

复用已转换库存锁作为自动履约子单的来源，满足既有 source_lock_id 约束；它不新增临时占用。原定金字段保留，不改变已有金额、付款记录。收款凭证先登记到账后上传，放行出库前必须补齐。

## 验证

最终结果以 output/pi-workflow-tests.log、pi-workflow-web-build.log、pi-workflow-h5-build.log、pi-workflow-native-tests.log 为准。

- Java 21 离线 Maven 全测试：共 501 项，307 项通过、194 项跳过，失败和错误均为 0；包含 Mapper XML 解析及业务边界单测。
- Web、H5 TypeScript 检查及生产构建通过；Web 保留现有大分包警告。
- 原生 PDA 33 项测试通过；未重新打包或真机验收。
- Playwright 本地组件夹具验证：合同空表单拒绝、完整合同确认、我方运输必填、客户自办运输未知信息说明、390px 手机表单换行。使用真实 Vue 组件和模拟数据，不等于真实后端端到端验收。
- 本机未配置 BIKE_MYSQL_TEST_URL 等集成环境，MySQL/外部服务集成测试跳过；新 SQL 未宣称真实 MySQL 执行通过。
- 2026-11-02 初次验证时未提交、未推送、未部署，未改生产数据库；后续发布状态以部署记录为准。上线前仍需在测试数据库跑完整 PI 收款—出库—物流—签收及撤销链路。
- 本次提交包含原生 PDA 出库页面源码；完整原生工程与厂商 SDK 未纳入本次提交，不作为独立 APK 构建交付。
