# API 说明

统一响应结构：

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {}
}
```

当前已提供：

- `POST /api/auth/login`：数据库用户登录，正式运行不按配置账号兜底，返回 JWT Bearer Token；成功/失败写入登录日志。响应中的 `mustChangePassword=true` 表示首次登录账号必须先修改密码。
- `GET /api/auth/context`：返回当前用户的角色、权限、工位范围、代理商范围和 `mustChangePassword` 状态；首次登录期间仅允许访问认证上下文和密码修改接口，其他业务接口返回 `403 PASSWORD_CHANGE_REQUIRED`。
- `POST /api/auth/logout`、`GET /api/auth/me`、`PUT /api/auth/password`：登录会话和密码管理。
- `GET /api/dashboard/summary`、`GET /api/dashboard/trend?days=7`：返回运营总览统计、整车状态分布、最近操作和趋势数据。
- `GET /api/modules/{module}?keyword=&page=1&pageSize=100`：返回 Web 管理后台模块列表数据，支持后端关键字过滤和分页（`pageSize` 最大 100，数据源不再被固定截断为 100 行）；整车档案列表包含四类部件、生命周期时间、客户/订单和库位字段，库存列表包含入库日期；`agents` 对应 `/master/agents` 代理商基础资料，`agent-dashboard` 对应 `/agent/dashboard` 授权车型和锁库概览；除核心模块外，支持 `stock-in`、`stock-out`、`locations`、`inventory-locks`、`rework`、`config`、`operation-logs` 以及 `/agent/*` 专用页面对应的 `agent-inventory`、`agent-locks`、`purchase-intents` 别名。列表字段和行数据由数据库查询结果生成，代理商账号按自身 agent 范围过滤，响应包含 `page`、`pageSize`、`total` 元数据。
- `GET/POST/PUT /api/parts`：零部件分页查询、按 SN 查询、新增和编辑。
- `GET/POST /api/vehicles`、`GET /api/vehicles/by-frame/{frameSn}`、`GET /api/vehicles/by-qr/{qrCode}`、`GET /api/vehicles/{id}/qr`、`GET /api/vehicles/{id}/qr/image`、`POST /api/vehicles/{id}/qr/regenerate`：整车档案和二维码；二维码查询返回 `qrCode`、`qrToken`、`qrVersion` 和持久化的 `generatedAt`，`/qr/image` 返回鉴权后的 PNG 文件用于下载或打印，二维码重新生成仅允许 `SUPER_ADMIN`。
- `POST /api/assembly/validate-frame`、`POST /api/assembly/validate-part`、`POST /api/assembly/submit`、`GET /api/assembly/{frameSn}`：四码校验、事务组装和结果查询。
- `POST /api/stock-in[/validate]`、`POST /api/stock-in/{id}/cancel`、`GET /api/stock-in`：入库、校验、撤销和查询。
- `POST /api/stock-out[/validate]`、`POST /api/stock-out/{id}/cancel`、`GET /api/stock-out`：关联客户/订单的批量出库、校验、撤销和查询；扫码校验必须同时携带 `customerId`、`orderId`，后端会即时校验订单状态、剩余数量和车型匹配，批量提交时仍会在事务内再次校验。
- `GET /api/inventory`、`GET /api/inventory/summary`、`GET /api/inventory/by-model`：按车型、仓库查询物理、锁定和可用库存。锁库业务按车型数量执行，不绑定库位；当同一车型分布在多个库位时，明细接口按稳定的仓库/库位顺序分摊锁定量到展示行，避免重复显示，汇总口径仍以车型锁库记录为准。
- `GET/POST /api/orders`、`GET /api/orders/{id}`、`GET /api/orders/by-no/{orderNo}`、`PUT /api/orders/{id}`、`POST /api/orders/{id}/confirm`、`POST /api/orders/{id}/cancel`：销售订单；订单详情额外返回有效 `shippedVehicles` 列表，已撤销出库记录不包含在内。
- `GET/POST /api/inventory-locks`、`POST /api/inventory-locks/{id}/release`、`POST /api/inventory-locks/{id}/convert`：按车型数量锁库。
- `GET/POST /api/rework`：返工换件并保留历史绑定。
- `GET /api/trace/frame/{frameSn}`、`GET /api/trace/part/{partSn}`：正向和反向追溯；响应包含整车、绑定历史、操作事件、入库记录、出库记录、返工记录及部件关联车辆。反向追溯不会丢失当前/历史整车对应的出入库和返工记录。
- `GET/POST /api/master/{type}`、`PUT /api/master/{type}/{id}`、`PUT /api/master/{type}/{id}/status`、`GET/POST /api/master/warehouses/{warehouseId}/locations`、`PUT /api/master/locations/{id}`、`PUT /api/master/locations/{id}/status`：车型、客户、供应商、代理商、仓库和库位基础资料；编辑接口用于更新编码、名称及联系人资料，状态仍由独立状态接口控制，库位启用时同时校验所属仓库处于启用状态。
- `GET/POST /api/users`、`PUT /api/users/{id}`、`GET /api/users/{id}/roles`、`PUT /api/users/{id}/roles`、`PUT /api/users/{id}/status`、`GET/POST /api/roles`、`GET/PUT /api/roles/{id}/permissions`、`GET /api/roles/permissions`、`PUT /api/roles/{id}/status`、`GET/POST/PUT /api/workstations`、`PUT /api/workstations/{id}/status`：系统基础管理。
- `GET/PUT /api/system-config/{configKey}`、`GET /api/system-config`：系统配置管理；`PUT /api/auth/password` 会校验当前登录账号并持久化数据库密码。SN 校验相关配置（`sn.frame.*`、`sn.part.*` 及按部件类型覆盖项）由后端业务入口统一读取和校验。
- `GET /api/agent/inventory`、`GET/POST /api/agent/inventory-locks`、`POST /api/agent/inventory-locks/{id}/release`、`POST /api/agent/inventory-locks/{id}/convert?quantity={quantity}`：代理商账号范围内的库存和锁库。代理商创建锁库时 `agentId` 可省略，后端从 JWT 绑定代理商确定；超级管理员创建时仍需提供 `agentId`。
- `GET/POST /api/agent/purchase-intents`：代理商采购意向查询和提交；代理商提交时 `agentId` 可省略并从 JWT 取值，超级管理员需提供 `agentId`；提交时后端强制校验当前代理商对车型存在有效锁库授权。
- `POST /api/parts/import`、`POST /api/master/{type}/import`、`POST /api/orders/import`：Excel 逐行导入，返回总行数、成功数、失败行和原因。
- `GET /api/export/{module}`：导出整车、零部件、库存、出入库、订单、返工、追溯、报关和操作日志等模块 xlsx。
- Web 模块列表的订单、库存锁定、入库和出库行操作分别调用订单确认/作废、锁定释放/转订单、入库撤销和出库撤销接口，操作原因通过接口参数传递并由后端再次校验状态与权限。
- `GET /actuator/health`：服务健康检查。
- `/v3/api-docs`：OpenAPI 文档。
- `/swagger-ui.html`：Swagger UI。

除登录、健康检查和 OpenAPI 资源外，其他接口默认需要 JWT；系统管理写接口要求 `SUPER_ADMIN` 角色，代理商路由要求 `WEB_AGENT` 角色，组装/入库/出库/返工还会校验绑定工位。业务接口由 Service 承载流程，Controller 不直接访问 Mapper。

## 查询数据链路

Web 登录后使用 JWT 调用 Dashboard、模块、基础资料和详情查询接口，表格数据由数据库查询结果生成；Web 已提供零部件/订单新增与编辑表单（客户、代理商、车型和供应商均由后端列表选择）、用户/角色/工位编辑、状态和权限配置、可导入模块的 xlsx 上传、详情弹窗和后端 xlsx 导出，PDA 负责组装、入库、出库和追溯扫码。

## 登录凭证行为更新（V18）

`POST /api/auth/logout` 持久化撤销当前 Bearer 凭证；成功后原凭证访问业务和身份接口返回401，其他独立登录不受影响。`PUT /api/auth/password` 成功后该用户所有旧凭证失效，前端需清除本地会话并要求新密码登录。账号启停同样更新凭证版本，重新启用不会恢复旧会话。

`GET /api/auth/me` 保留既有响应字段，但返回本次请求的凭证及剩余有效秒数，不延长有效期。V18前签发的凭证需重新登录。退出发生网络错误时客户端应提示重试，不能把清除本地存储当作服务端撤销成功。

## 代理商车型授权（V19）

- `GET /api/master/agents/{agentId}/model-access`：管理员读取授权车型ID数组。
- `PUT /api/master/agents/{agentId}/model-access`：管理员提交 `{ "modelIds": [1,2] }` 替换授权；空数组撤销全部，非法/重复/停用车型拒绝。成功返回保存后的ID数组并记录权限审计。
- 代理商车型选项、库存、锁定创建和采购意向以独立授权为准；库存可用量扣除该车型所有代理商有效锁定总数。撤销车型授权不删除旧锁定，仍允许所属代理商查历史及释放。新部署需管理员明确配置，不能由代理商自行先锁定取得权限。

## 锁定转订单与库存占用（V20–V22）

- `POST /api/inventory-locks/{id}/orders`：内部 Web，同时要求 ORDER_WRITE、AGENT_LOCK_WRITE。请求 `{customerId,quantity,requestId,plannedShipDate}`，requestId 为8–64位标识，同一来源锁及标识重复返回同一订单，变更数量/客户/日期则拒绝。订单为草稿，占用截止继承来源锁。旧 convert 接口已禁止写入。
- `GET /api/inventory-locks/{id}/orders?page=1&pageSize=20`：分页关联订单。要求 ORDER_READ 及锁定读取权限，代理商仅自己的来源锁。包含已取消/关闭历史，不隐藏已处理订单。
- `POST /api/orders/{id}/reservation/renew`：`{hours:48,reason:"重新确认库存"}`；1–720小时，要求内部 Web、ORDER_WRITE、AGENT_LOCK_WRITE。按当前合格库存重新竞争未发数量。无来源占用或已取消/关闭拒绝。
- `POST /api/orders/{id}/close-remainder`：`{reason:"不再发货"}`；内部 Web、ORDER_WRITE，仅部分发货订单；状态 CLOSED，释放剩余占用，保留原订单量和发货历史，关闭后禁止撤销旧出库重新打开订单。
- 订单查询增加 sourceLockNo、reservationExpiresAt、reservedQty；V21 remark 持久化并返回。当前仍为单车型订单，非多SKU PI。
- 有效占用在查询时实时判断，到期即不计入承诺。后台每分钟（可配置）最多200单补记 ORDER_RESERVATION_EXPIRE 审计，同一到期周期不重复记录。续占用重置审计标记。

## 库存分项与缺口

`GET /api/inventory`、`/api/inventory/by-model`、代理商库存查询保持分页协议，新增 temporaryLockedQty（临时锁）、orderReservedQty（订单占用）、shortageQty（库存缺口）；lockedQty兼容字段为两类承诺之和。分页和总数在数据库执行，仓库筛选不重新分摊车型承诺。

`GET /api/inventory/summary`返回上述分项及physicalQty、quarantinedQty、availableQty；可售量和缺口逐车型计算后汇总。通用库存列表/Excel及看板使用同一SQL口径。库位分摊先临时锁、后订单，仅为展示顺序，不表示发货优先级；超出合格物理量的承诺显示在该车型最后一个库位的缺口。无在库车辆的车型如仍有承诺，保留一行仓库为空的缺口记录。


V22 增加 PRODUCTION_MANAGE 生产管理功能权限。生产管理写接口按该权限检查，代理商和 PDA 仍被内部生产管理边界拒绝。

## PI 基础（V24）

- `GET /api/pis`：按 keyword/status 分页；`GET /api/pis/{id}`：明细。要求 `PI_READ`；代理商仅可读取自己的已发布单据，财务金额和收款记录另行脱敏。
- `GET /api/pis/options?keyword=`：业务员搜索客户、代理商、SKU 和来源订单，要求 `PI_WRITE`。
- `POST /api/pis`、`PUT /api/pis/{id}`：创建及完整编辑草稿，要求内部 Web 与 `PI_WRITE`。商品必须关联真实 SKU；价格和金额使用最多两位小数的十进制字符串，保存客户、商品和条款快照。关联来源订单不可改绑，也不能从旧订单接口改数量绕过 PI。
- `POST /api/pis/{id}/confirm`：校验必要单据资料后确认；`POST /api/pis/{id}/cancel`：`{reason}`，有收款或实际出库时拒绝直接取消。
- `POST /api/pis/{id}/payments`：`{requestId,amount,currencyCode,receivedAt?,voucherObjectKey?,remark?}`。要求独立 `PI_PAYMENT_CONFIRM`；同一 requestId 的相同请求返回原结果，冲突内容拒绝。V24 的 voucherObjectKey 仅为引用字段，不代表已有收款凭证上传能力。
- `POST /api/pis/{id}/release`：`{reason}`，要求独立 `PI_RELEASE` 且累计到账满足 `shipmentRequiredAmount`。足额到账不会自行发货；定金门槛 `depositRequiredAmount` 与发货门槛分开。
- `POST /api/pis/{id}/logistics`：物流登记，要求 `PI_LOGISTICS_WRITE`；当前 V24 物流批次是业务录入，尚未关联实际出库批次。
- `GET /api/pis/{id}/export.pdf`、`GET /api/pis/{id}/export.xlsx`：按读取范围与财务脱敏规则导出。PDF 使用随包中文字体；Excel 金额以精确十进制文本保存。当前为通用版式，正式客户模板待配置和确认。

已关联 PI 的 `/api/stock-out[/validate]` 同时检查人工放行、发货款门槛、真实 SKU 和剩余数量；批量失败整笔回滚。来源订单在 V24 仍是单车型，可以包含该车型下多个 SKU。

## 售后基础（V23）

- `/api/after-sales/cases`：GET 分页、POST 草稿；`/{id}`：GET 详情、PUT 编辑；`/options`：查询可选客户、车型、车辆资料。
- `POST /{id}/submit`、`/{id}/review`、`/{id}/solution`、`/{id}/process`：提交、审核、技术方案与开始处理；审核按明确状态机执行，保存方案不会绕过审批。
- `GET/POST /{id}/messages`：公共或内部留言。内部留言仅内部人员可见，留言写入有独立权限。
- `GET/POST /{id}/shipments`、`POST /{id}/shipments/{shipmentId}/dispatch`：计划补件与实际发货分开登记。
- `GET /{id}/history`、`POST /{id}/resolve`、`POST /{id}/close`：历史、解决和归档；终态不允许任意重开。

以上相对路径均以 `/api/after-sales/cases` 开始。读取、写入、审核、技术和发货各有独立权限；代理商仅自己的案例和车辆/授权车型，外部响应隐藏内部信息，PDA 不能执行内部售后操作。V23 基础不等同于完整附件、公司成员、销售归属和通知能力，后续版本验证见实施台账。

## 售后附件与公司范围（V26 / V29）

- `GET/POST /api/after-sales/cases/{id}/attachments`：列表或多文件上传；POST 使用 multipart `files`，查询参数为 `visibility`、`category` 和可选 `messageId`。留言关联必须属于当前案例、当前用户且可见范围一致。每批最多5个，单视频50 MB、其余10 MB，总计150 MB。
- `GET /api/after-sales/cases/{id}/attachments/{attachmentId}/download`：按案例与附件可见性授权下载；不返回存储对象键。支持 JPEG/PNG、PDF、H.264 MP4、UTF-8 TXT/LOG/CSV/JSON，按真实内容校验；MP4解析/首帧解码在有时限和内存上限的独立子JVM执行。
- `GET /api/after-sales/companies`、`GET /api/after-sales/companies/{id}`：当前账号范围内公司联系资料。外部只读自己公司；内部有 `AFTER_SALES_SCOPE_ALL` 才是全公司范围，其余按有效销售分配过滤。
- `PUT /api/after-sales/companies/{id}`：保存 `contactName/contactPhone/contactEmail/countryCode/region/city/addressLine/postalCode`。必须为内部 `AFTER_SALES_COMPANY_MANAGE` 且有该公司范围，不改变公共主数据写权限。
- `GET/PUT /api/after-sales/companies/{id}/salespeople`：负责销售查询/替换，PUT `{userIds:[...]}`；同上管理权限及公司范围。只接受启用且有售后读取权限的内部账号。
- `GET /api/after-sales/companies/{id}/members`、`/{id}/history`：同上管理权限及公司范围；分别返回公司员工基本资料和最近200条公司管理变更动作、操作人、时间。history 不包含前后审计JSON。
- `GET /api/after-sales/companies/staff-options`：供内部公司管理或派单人员选取销售/技术人员，排除外部和PDA身份。
- `PUT /api/after-sales/cases/{id}/assignee`：`{assigneeId:number|null}`；内部 `AFTER_SALES_ASSIGN` 且有案例范围。目标必须启用、具读取和技术/审核权限并已有该公司访问范围；此操作不自动扩权，终态拒绝派单。

案例创建/编辑新增可选 `contact` 联系资料对象与 `endCustomerPurchaseDate`。创建时未提交 contact 则复制公司默认资料；案例中的联系人和地址不会随公司资料更新而改变。外部账号即便误授 SCOPE_ALL、公司管理或派单权限也仍只读自身公司且不能执行内部管理动作。只读销售撤销负责关系后，下次请求即重新检查列表、详情、历史、附件和下载范围。V29 代码与测试验收状态以售后实施记录为准。


## 售后查询、看板及旧件退回（V34）

`GET /api/after-sales/cases` 新增可选筛选：`companyName`、`contactName`、`frameSn`、`modelName`、`faultType`、`countryCode`（两位国家代码）、`assigneeName`、`createdFrom`、`createdTo`（YYYY-MM-DD，含结束当日）、`queue`。名称为包含查询，`%`、`_` 按字面字符匹配。公司/销售范围从登录身份计算，不能用请求参数扩大。

`GET /api/after-sales/cases/dashboard` 接受相同筛选，返回 total、pending、review、waitingCustomer、processing、waitingShipment、shipped、completed、completedThisMonth。看板忽略 status 与 queue，保留其他条件。对应列表快捷分类为 PENDING、REVIEW、WAITING_CUSTOMER、PROCESSING、WAITING_SHIPMENT、SHIPPED、COMPLETED、COMPLETED_MONTH。待发货只计有真实 PLANNED 配件的有效案例，每个案例只计一次；完成以 CLOSED 为准，本月使用服务器日历月的 closed_at 区间，RESOLVED 尚未归档不计完成。

`GET /api/after-sales/cases/{caseId}/returns` 返回每项需退回配件的独立处理记录和 revision。无需退回的补发条目不建退回记录。

`POST /api/after-sales/cases/{caseId}/returns/{shipmentId}` 必须提交当前 `revision` 和 `action`：

| action | 权限与前置状态 | 必填业务资料 |
| --- | --- | --- |
| REQUEST | 内部 AFTER_SALES_LOGISTICS；REQUIRED / WAITING_CUSTOMER | warehouseName、recipient、phone、addressLine、postalCode |
| SEND | 本公司外部 AFTER_SALES_WRITE，或内部 AFTER_SALES_REVIEW / AFTER_SALES_LOGISTICS；WAITING_CUSTOMER | carrier、trackingNo |
| RECEIVE | 内部 AFTER_SALES_LOGISTICS；IN_TRANSIT | 核对实物后确认 |
| INSPECT | 内部 AFTER_SALES_TECH；RECEIVED | inspectionResult（客户可见） |
| SCRAP | 内部 AFTER_SALES_REVIEW；INSPECTED | reason（客户可见） |
| COMPLETE | 内部 AFTER_SALES_REVIEW；INSPECTED / SCRAPPED | reason（客户可见） |
| WAIVE | 内部 AFTER_SALES_REVIEW；REQUIRED / WAITING_CUSTOMER | reason（客户可见），不允许豁免已经寄出的旧件 |

所有动作还须 AFTER_SALES_READ 和案例范围。外部误授内部权限仍不能执行内部动作。案例 DRAFT/SUBMITTED/UNDER_REVIEW/NEEDS_INFO/CLOSED/REJECTED 时禁止退回写操作。过期 revision 返回 `AFTER_SALES_RETURN_CONFLICT`，刷新后按实际状态继续；无匹配条目返回 `AFTER_SALES_RETURN_NOT_FOUND`。案例归档前检查所有要求退回的条目已 COMPLETED 或 WAIVED，否则返回 `AFTER_SALES_RETURN_PENDING`。案例行锁、版本更新、收件兼容时间戳与审计同事务提交；审计失败全部回滚。

## 历史转换对账

`GET /api/inventory-locks/legacy-conversions?page=1&pageSize=20` 供内部 Web 的订单读取人员只读查询旧版锁定记录：`converted_qty>0` 且没有 `bike_order_reservation` 来源关联的记录会被列出。该接口分页并要求内部 Web、`ORDER_READ`；代理商和 PDA 拒绝访问。它只提供待人工核对的锁定号、代理商、车型、数量、状态和备注，不自动猜测客户、不自动补建订单、不修改库存。

## PI 收款与来源占用状态（V25）

PI 列表、详情、确认和收款响应增加 `reservationStatus`。订单查询增加 `reservationStatus`、`reservationHoldMode`、`reservationHeldAt`，内部原 `reservationExpiresAt` 保留原始截止时间。状态为 `NONE`、`TIMED_ACTIVE`、`PAID_HELD`、`EXPIRED_REQUIRES_REACQUIRE`、`RELEASED`、`FULFILLED`；代理商的两种有效状态统一返回 `ACTIVE`，订单保留方式、保留时间和截止时间一律脱敏。界面须按状态判断有效性，不能对 `PAID_HELD` 仅比较旧截止时间后显示到期。

累计收款达到保留门槛时，只有尚未到期的限时占用才转为收款达标保留。到期后收款请求正常保存金额，响应提示需要重新占用；同一 requestId 重试不再次尝试保留。零门槛 PI 在确认时执行同样的到期判断。

重新占用沿用 `POST /api/orders/{id}/reservation/renew` 和 `{hours,reason}`，要求内部 Web、`ORDER_WRITE`、`AGENT_LOCK_WRITE`。接口按当前合格库存重新校验，关联已确认 PI 且收款达标时转为 `PAID`；已是 `PAID` 则保持原样。已主动释放、PI 已取消、订单已取消或关闭时拒绝重新打开。`SHIPPED` 的限时占用可续期供受控撤销出库使用，当前未发量仍为零。

`POST /api/pis/{id}/release` 增加来源占用有效性检查：来源占用失效时返回 `ORDER_RESERVATION_EXPIRED`，即使发货收款门槛已达成也不能放行。没有来源订单占用的 PI 仍遵循原发货门槛与人工放行规则。

通用操作日志、追溯列表、看板最近操作及相应 Excel 搜索/导出也执行财务事件权限过滤。没有内部 `PI_PAYMENT_CONFIRM` 或 `PI_RELEASE` 权限的岗位不能从这些入口搜索收款备注、放行理由或收款保留事件；管理员可审计完整记录。

## 仓库可选订单分页

`GET /api/orders/stock-out-options?customerId=&keyword=&page=1&pageSize=20` 供普通 Web 仓管和 PDA 出库岗位选择订单；必须具备 `STOCK_OUT_OPERATE` 及 `STOCK_OUT` 工位，代理商即便误配相同权限仍被拒绝。按客户和可发状态在数据库筛选，再执行搜索、分页与总数查询。返回仅包含订单号、客户 ID、车型、状态和已发/约定数量的最小数据，不包含 PI 财务信息。

H5 使用该接口，可搜索、翻页，翻页保留已选订单；切换客户会清除旧订单及扫描队列。避免旧页面仅加载前100个所有状态订单，导致可发订单被新草稿挤出选项的问题。最终扫码与出库仍执行完整事务校验。

## 售后方案版本与仓库边界（V35）

`POST /api/after-sales/cases/{id}/solution` 保存新版本：previousVersionId须为当前版本，初次为空；填写technicalJudgment、solution、faultCause、customerActions、repairNotes、warrantyStatus、currency、chargeReason及parts（partCode/partName/quantity/unitCharge/returnRequired）。收费精确两位小数且须说明责任。

`POST .../solution/confirm {versionId}` 由技术确认；审批请求须含solutionVersionId。`GET .../solution/versions` 按公司范围返回历史，内部备注按权限隐藏。REVISE审核动作附原因和当前版本，将案例返回审核；补发必须选择当前批准solutionItemId，案例内同编号累计不超量。`POST .../shipments/{shipmentId}/approval-link {itemId,reason}` 仅审核人员可把旧补发关联当前草稿条目，再次确认审批。

纯仓库只可读取最新批准且有配件或已有补发的案例；列表/count/看板/详情共用条件，仍受公司范围限制。响应隐藏车辆故障、技术方案、费用、Email、留言与非物流历史，附件/版本历史/留言独立接口拒绝，敏感字段筛选拒绝、关键词不匹配车辆故障。派单要求读取和派单权限且不能由纯仓库调用。

## 售后完整发运（V36）

补发请求新增shipping，包含warehouseName、recipient、phone、countryCode、region、city、addressLine、postalCode、expectedArrivalDate、shippedAt。计划未指定时复制案例收货资料；实际发货须完整仓库、收货人、电话、国家和地址。邮编允许为空，实际时间留空使用当前时间，禁止未来发货、预计到达早于发货。

`POST .../shipments/{shipmentId}/dispatch` 必须携带当前revision，可提供shipping确认实际收货快照。过期页面不能覆盖他人已修改地址。发货后地址和时间保留，不能通过物流更新改写。

`POST .../shipments/{shipmentId}/logistics` 接收revision/action/note及操作对应字段：SAVE保存计划shipping；TRACK更新IN_TRANSIT/EXCEPTION/DELIVERED及expectedArrivalDate；CORRECT以原因更正carrier/trackingNo；CANCEL取消未发计划。TRACK/CORRECT/CANCEL说明必填，客户可见；当前仅手工登记，不声称已接承运商追踪接口。已签收不允许回退。

取消保留原记录，批准数量重新可安排；只联动取消尚未启动的旧件记录。已启动旧件退回或已发货不能取消。取消后若仍有未安排批准配件，解决门禁仍生效。全部动作按案例加锁、revision冲突拒绝、审计原子提交。旧数据仓库、地址和当前物流状态未知则保持空，不猜历史。
