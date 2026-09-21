# SKU库存承诺（V28）

此页记录已实现的软件行为及验收入口。多车型PI聚合履约、锁→订单→PI一次转换、正式修订仍见 `pi-completion-design.md`，不因SKU基础完成而自动完成。

## 库存与兼容规则

- 旧锁定/订单占用保留 `MODEL_LEGACY`，原数量、期限、V25付款保留状态不变，不从车型猜测SKU。
- 新锁定必须指定客户和真实启用SKU。车型从SKU读取；代理商可空，表示直接客户。一次请求最多100个不同SKU，任一失败则整批锁定、请求标识与日志回滚。
- `GET /api/inventory/skus` 返回每SKU在库、隔离、临锁、订单占用、净可用、缺口以及所属车型未分配历史承诺。无生产SKU证据的旧车辆只计车型物理库存，不计任一SKU物理库存。
- 新增承诺需同时满足：具体SKU合格库存减其全部有效承诺足够；车型全部合格库存减所有新旧承诺足够。`maxNewLockQty` 是这两个剩余数的非负最小值，各行上限不能相加为可售总数。
- 型号汇总原共享 `commitments` 只计一次锁定行或订单占用头；SKU精度明细用于第二道门禁，不再次加到车型总占用。SKU缺口在SKU页面、原库存表、库存汇总、看板及库存Excel中独立显示，其他SKU富余不能用于发出错误规格。
- SKU锁转单仍创建兼容的单车型订单，新增占用明细固定来源SKU，客户不能改变，原截止不延长。原有只增加转换计数的旧`/convert`入口仍拒绝。
- 原库存出库预检和真实出库逐车检查SKU，旧无PI/车型订单同样不能消耗其他SKU承诺。整批中最后一车失败时，前车出库、流水、已发明细及占用一并回滚。
- 撤销入库也核验该车SKU和车型承诺；实际出库/撤销同步SKU已发数。取消订单、取消PI和关闭剩余统一关闭SKU剩余，关闭数不伪装为已发数。
- V25收款保留、到期需显式重占规则不变。重占同时检查每SKU，不能用蓝车替代红车；已付款保留不因续期入口降级为限时。订单已关闭时，撤销出库直接明确拒绝，不提示重新占用。
- 关联PI输入与来源固定SKU订单逐SKU数量一致，拒绝仅车型合计相等但规格不同的PI。

## 接口

### 查询SKU库存

`GET /api/inventory/skus?modelId=4&page=1&pageSize=20`

权限：Web `INVENTORY_READ` 或 `AGENT_INVENTORY_READ`。代理商强制使用会话绑定代理和当前启用车型授权过滤，客户端不能传入另一个代理；PDA不可访问本业务页面。每页上限100。

每条返回：`skuId/modelId/skuCode/productName/specification/modelName/physicalQty/quarantinedQty/temporaryLockedQty/orderReservedQty/availableQty/shortageQty/legacyModelCommittedQty/modelAvailableQty/maxNewLockQty`。是全仓库口径，不支持拿单个仓库物理量扣掉全局承诺而冒充仓库精确占用。

### 原库存接口与看板的口径

`GET /api/inventory` 保留原字段和按库位展示分摊。原 `availableQty` 只表示该行按车型承诺分摊后的非负净余额，页面/Excel列名现为“分摊可新增上限”；该值不能承诺某一具体SKU，也不能证明当前库位有可售的特定规格车辆。

每行新增三个**全车型指标**，即便请求带warehouseId过滤也从全仓库计算：

| 字段 | 公式与含义 |
| --- | --- |
| `skuShortageQty` | `SUM(每SKU MAX(SKU有效承诺-SKU合格在库,0))`，在SKU内先截断再累加，蓝车富余不会冲掉红车缺口 |
| `legacyModelCommittedQty` | 该车型全部有效承诺减已有精确SKU的有效承诺，历史未分配承诺仅计一次 |
| `modelNewCommitmentQty` | `MAX(全车型合格在库-全部新旧有效承诺,0)`，是车型新增承诺的保守总预算；还必须通过具体SKU门禁 |

同车型跨库位返回时，末三个字段会重复，页面与Excel列名明确“（不累计）”。`GET /api/inventory/summary` 和看板使用先按车型去重再求和的同一个共享SQL。原 `shortageQty` 是车型总量净缺口；`skuShortageQty` 是SKU结构缺口，两者可能重叠，**不能相加**作为缺货总台数。

例如红SKU合格0、红承诺1，蓝SKU合格2、未知SKU旧车1，另有未分配车型承诺1：SKU红缺口仍为1；车型新增承诺预算为`3-1-1=1`；蓝SKU净空闲2，但蓝本次最大新增锁定只能为`min(2,1)=1`。未知SKU车不增加任何具体SKU可锁数量。按某一库位筛选不会重新分配这些全车型承诺。

### 原子创建SKU锁定

`POST /api/sku-locks`

```json
{
  "requestId": "业务操作唯一标识",
  "customerId": 1,
  "agentId": null,
  "expiresAt": null,
  "remark": "本次客户备货",
  "items": [{ "skuId": 10, "quantity": 1 }, { "skuId": 11, "quantity": 2 }]
}
```

权限：内部Web `AGENT_LOCK_WRITE`；代理/PDA即使误授该权限也拒绝此客户锁定接口。代理原授权车型锁定入口仍兼容；尚无代理—客户授权主表，不能把客户端传入客户当作代理授权。

数量每SKU1至1,000,000，重复SKU拒绝。未指定截止采用系统 `inventory.lock.hours`（异常兜底48小时），显式截止必须在数据库当前时间之后、720小时以内。默认截止在取得库存互斥后计算。

幂等范围为操作人+requestId；按SKU排序后生成规范化请求SHA256，相同请求返回原锁定行（包括当前转换/释放状态），同键不同内容返回 `SKU_LOCK_REQUEST_CONFLICT`。失败事务不消耗请求标识。返回 `InventoryLockView[]`，新增 `customerId/skuId/skuCode/scopeType`，与原 `GET /api/inventory-locks`、释放及转单接口兼容。

### 转单与后续作业

沿用 `POST /api/inventory-locks/{id}/orders`，保留requestId幂等和部分转单。SKU锁定的customerId必须与来源一致，SKU不在客户端请求中重选。来源剩余锁定与新订单占用数量守恒。需内部 `AGENT_LOCK_WRITE + ORDER_WRITE`。

错误码包括 `SKU_INVENTORY_NOT_ENOUGH`（具体SKU不足）、`INVENTORY_NOT_ENOUGH`（车型历史承诺不足）、`LOCK_CUSTOMER_MISMATCH`、`ORDER_SKU_MISMATCH`、`SKU_INVENTORY_LOCK_CONFLICT`。库存读结果只是当时快照，提交仍在数据库互斥下重新判断。

## 页面与验证

Web `/sku-inventory` 提供车型筛选、SKU分页、缺口、跨页多SKU选择、客户/可空代理选择、批量锁定和相同内容重试。保存后显示真实锁定号并进入现有库存锁定流程；转单窗口展示固定SKU，来源客户预填且不可改。

`SkuCommitmentMysqlTest` 使用随机前缀InnoDB表执行真实V28迁移、生产Mapper、ProductionGuard和真实服务事务，覆盖SKU不可替代、最后一台并发、批量回滚、新旧承诺共存、直接客户、固定SKU转换、真实出库/撤销/撤销入库、错误后车整批回滚、到期重占、付款保持、隔离缺口、代理授权撤销、关闭余量。

`SkuInventoryAccessTest` 验证代理范围、分页上限、无绑定/PDA拒绝、内部写入口拒绝代理和PDA。`PiServiceTest.fixedSkuSourceOrderRejectsAnotherSkuOfTheSameModel` 验证PI来源精度一致性。完整上线仍需四镜像迁移、现有回归、真实HTTP和普通账号UI最终验收。
