# PI 订单关联与确认校验（2026-11-05）

## 问题与行为

原开单页面将来源订单放在可选折叠区域，独立创建的 PI 可以漏掉已存在的订单；确认 PI 未完整复核关联订单的客户、代理商及精确 SKU。原订单占用和 PI 履约因此可能脱节。

现在新建 PI 默认选择“关联已有订单”，选择客户和订单后自动带入原代理商、SKU、数量及计划发货日，限制修改原履约身份和商品数量。锁单转 PI 保留原有原子转换流程。确无原订单时，操作人须明确选择“无原订单的新业务”，由既有收款流程在金额达标且库存充足后创建关联履约订单。

未关联订单、无收款/发货的首次草稿可以补绑一次原订单；已绑定草稿不能换绑，修订仍按原系列处理。来源主字段和关联表在同一个事务中保存，绑定已有 PI 的订单会失败并回滚。

确认时验证关联表、客户、代理商、精确 SKU 和数量、首次确认的订单状态及占用有效期。有履约订单的 PI 会核验指定仓库的待发数量，并扣除其他 PI 已付款占用；不足时返回可用量和待发量。该检查不新增占用，正式收款占库仍复核实时库存。明确的新业务可以在无库存时先确认商业单据，实际占库继续受收款流程约束。

放行和实物出库再次复核履约身份及 SKU 数量；实际车辆的仓库校验、合同、凭证、收款门槛保持原有约束。

## 文件和职责

- `bike-server/src/main/java/com/bike/server/pi/PiOrderBindingService.java`、`PiOrderBindingServiceImpl.java`：关联、身份、明细、状态和仓库一致性校验。
- `bike-server/src/main/java/com/bike/server/pi/PiServiceImpl.java`：草稿首次补绑、确认事务内校验、关联来源订单随 PI 确认；保留已有付款优先占库修改。
- `bike-server/src/main/java/com/bike/server/pi/PiBusinessServiceImpl.java`：放行/出库前复核订单关联。
- `bike-server/src/main/java/com/bike/server/pi/PiBusinessTerms.java`：增加可选 `orderSource`，保留旧构造方法。
- `bike-server/src/main/resources/mapper/PiMapper.xml`：订单选项补充精确 SKU、数量、代理商名称、计划发货日期；草稿更新保存来源订单。
- `bike-web/src/pi-api.ts`、`bike-web/src/components/PiWorkspace.vue`：显式来源选择、自动带入、字段限制、未选订单提示、旧草稿补绑。
- `PiOrderBindingServiceTest`、`PiOrderBindingMysqlTest`、`mapper/PiOrderBindingSqlFixture.xml`：回归验证，新增 SQL 均在 Mapper XML 中。

## 兼容与事务

接口路径、已有 DTO 字段及数据库结构不变。新增 `document.business.orderSource` 为可选字段（`EXISTING_ORDER` / `NEW_BUSINESS`），空值不序列化，保持历史锁单转换的请求哈希。历史已关联单据不要求补填标记；历史未关联草稿可以读取和编辑，但确认前必须补绑或明确新业务来源。旧调用方创建无订单 PI 后直接确认将得到 `PI_ORDER_SOURCE_REQUIRED`，需要适配来源选择。

新校验由 Spring 注入并使用 `MANDATORY` 事务，复用现有库存互斥锁、订单/占用/PI 锁。并发绑定通过原有库存锁和关联表唯一约束串行化，失败会回滚。修订在数量调整后复核最终履约数量，允许保留已发数量及已经关闭的历史零数量行。

回滚应用时恢复对应后端和前端版本即可，无 DDL 回滚。已创建的有效来源字段和关联表记录应保留，不通过删除业务关系回滚。旧版本将不再提供此次确认前校验；回滚前应在测试环境验证它读取新增可选 JSON 字段的兼容性。

## 验证与边界

- 后端离线 `mvn verify`：325 项实际执行通过，206 项依赖环境的测试跳过（该次统计共 531 项）。
- 独立本地 MySQL 8.4：订单绑定、锁单转换、数量修订、修订生命周期及付款优先占库共 27 个不同场景通过，未访问生产数据库。
- 包含原 5 台复用、重复收款幂等、并发绑定唯一性、旧草稿补绑、保存后代理商/数量变化、过期占用、新业务收款自动建单、仓库不足回滚及补足库存后确认。
- 前端 TypeScript 检查与生产构建通过。
- Playwright 使用真实 `PiWorkspace` 组件和本地模拟接口验证：默认关联订单、自动带入且禁改、漏选来源阻止下一步、旧草稿补绑提交 `sourceOrderId=1` / `agentId=2` / SKU 数量 5。
- 截图：`output/playwright/pi-order-binding-existing-20261105.png`。
- 开发验证阶段保留原有工作区修改，未部署线上，也未改动生产数据。随后于 2026-11-05 16:19 完成生产发布，详见 [发布记录](deployment-pi-order-binding-20261105.md)。

详细日志、变更前本地副本及浏览器验证页保存在 `tmp/pi-order-binding-20261105/`。
