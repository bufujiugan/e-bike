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

- `POST /api/auth/login`：数据库用户登录，开发管理员配置作为兜底，返回 JWT Bearer Token；成功/失败写入登录日志。响应中的 `mustChangePassword=true` 表示首次登录账号必须先修改密码。
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
