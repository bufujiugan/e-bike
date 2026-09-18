# Bike 项目开发任务书（Codex 直接执行版）

> 项目名称：`bike`  
> 项目目录：`F:/bike`  
> 项目类型：E-Bike 生产组装扫码、库存、出入库、代理商库存协同、返工与全生命周期追溯系统  
> 开发模式：前后端分离  
> 后端：Java + Spring Boot 常用企业级技术栈  
> Web/H5：采用当前稳定版主流前端框架实现，优先兼容安卓 PDA、手机浏览器、PC 浏览器  
> 数据库：MySQL  
> 缓存/中间件：Redis（按需使用）  
> 部署方式：私有化部署，支持 Windows/Linux 服务器  
> 核心要求：代码必须可运行、可部署、可维护，不允许只生成 Demo 或大量占位代码。

---

# 1. Codex 总执行指令

请直接在 `F:/bike` 目录内完成本项目开发。

## 1.1 开始开发前

请先执行以下动作：

1. 检查 `F:/bike` 当前目录内容。
2. 如果已经存在代码：
   - 先阅读项目目录结构；
   - 阅读 `README.md`；
   - 阅读 Maven/Gradle、前端依赖、数据库脚本；
   - 分析当前代码编程风格、命名规范、异常体系、接口结构；
   - 后续代码严格沿用当前项目风格；
   - 不允许无理由推翻已有架构。
3. 如果项目为空，则按照本文档初始化完整项目。
4. 不要一次性生成大量无法运行的代码。
5. 每完成一个阶段，都必须：
   - 编译后端；
   - 构建前端；
   - 运行核心测试；
   - 修复编译错误；
   - 更新开发进度文档；
   - 确保当前阶段可运行。

---

# 2. 项目总体目标

建设一套 E-Bike 生产库存管理与追溯系统。

系统围绕每一台整车建立唯一档案。

整车由以下核心编码构成：

- 车架 SN
- 电机 SN
- 电池 SN
- 仪表 SN

系统要求：

1. 车架 SN 作为整车业务主 ID。
2. 电机、电池、仪表作为整车子部件。
3. 四码完成绑定后，自动生成【整车总二维码】。
4. 整车总二维码用于后续入库、出库、快速查询。
5. 整车总二维码不得替代车架 SN 的业务主键地位。
6. 电机、电池、仪表不得同时绑定第二台整车。
7. 所有核心业务状态由后端统一控制。
8. 所有关键业务操作必须保留操作日志。
9. 返工换件不得覆盖旧数据，必须保留完整历史。
10. 支持 Web 管理后台。
11. 支持 PDA/H5 工位扫码。
12. 支持代理商库存查看与库存锁定。
13. 支持客户、订单、出库管理。
14. 支持正向追溯、反向追溯。
15. 支持私有服务器部署。
16. 支持 Excel 导入、导出。
17. 支持后续扩展 ERP/WMS/MES 接口，但本期不实现第三方系统接口。

---

# 3. 推荐技术栈

如当前目录为空，默认使用以下技术栈。

## 3.1 后端

- Java 21
- Spring Boot 3.x 当前稳定版本
- Spring Web
- Spring Validation
- Spring Security
- JWT 登录认证
- MyBatis-Plus
- MySQL 8.x
- Redis
- Flyway
- Lombok
- MapStruct（如确实有必要）
- Jackson
- Apache POI / EasyExcel
- OpenAPI 3 / Swagger
- Maven

要求：

- 禁止使用已废弃组件；
- 不使用 beta / RC 版本；
- 优先选择当前稳定版本；
- 所有依赖版本集中管理；
- 配置分环境；
- 不将密码、密钥硬编码在源码中。

---

# 4. 前端技术栈

## 4.1 Web 管理后台

采用：

- Vue 3
- TypeScript
- Vite
- Element Plus
- Vue Router
- Pinia
- Axios
- ECharts（仅用于 Dashboard）

要求：

- 使用当前稳定版本；
- 使用 Composition API；
- 使用 TypeScript；
- 页面响应式布局；
- 后台 PC 优先；
- 封装统一请求层；
- 封装统一错误处理；
- 封装权限指令/权限组件；
- 不要把权限判断散落写死。

---

## 4.2 PDA / H5

PDA 端采用与 Vue 3 兼容的当前稳定 H5 技术方案实现。

推荐：

- Vue 3
- TypeScript
- Vite
- Vant 当前稳定版
- Pinia
- Vue Router
- Axios
- PWA 支持（可选，但架构预留）

目标设备：

- 安卓 PDA
- 安卓手机
- 手机浏览器
- 工业扫码枪模拟键盘输入

PDA 页面要求：

- 大字体；
- 大按钮；
- 单手操作；
- 自动聚焦扫码输入框；
- 扫码完成自动校验；
- 扫码成功明显绿色反馈；
- 扫码失败明显红色提示；
- 重复扫码给出明确原因；
- 尽量避免手动输入；
- 支持扫码枪通过回车触发；
- 页面禁止因软键盘导致核心按钮不可见。

第一期不开发原生 Android APP。

---

# 5. 项目目录建议

如果项目为空，建议：

```text
F:/bike
│
├─ bike-server
│  ├─ src
│  ├─ pom.xml
│  └─ ...
│
├─ bike-web
│  ├─ src
│  ├─ package.json
│  └─ ...
│
├─ bike-h5
│  ├─ src
│  ├─ package.json
│  └─ ...
│
├─ db
│  ├─ migration
│  ├─ init
│  └─ mock
│
├─ deploy
│  ├─ nginx
│  ├─ windows
│  ├─ linux
│  └─ backup
│
├─ docs
│  ├─ database.md
│  ├─ api.md
│  ├─ business-rules.md
│  ├─ deployment.md
│  ├─ permission.md
│  ├─ test-cases.md
│  └─ progress.md
│
└─ README.md
```

---

# 6. 核心业务流程

```text
零部件来料
    ↓
零部件录入 / Excel 导入
    ↓
车架进入生产
    ↓
PDA 扫车架
    ↓
PDA 扫电机
    ↓
PDA 扫电池
    ↓
PDA 扫仪表
    ↓
系统逐项校验
    ↓
提交组装
    ↓
建立整车档案
    ↓
建立零部件绑定关系
    ↓
生成整车总二维码
    ↓
状态：待组装 → 组装完成
    ↓
PDA【入库页面】
    ↓
扫描整车总二维码
    ↓
选择仓库 / 库位
    ↓
状态：组装完成 → 已入库
    ↓
形成库存
    ↓
订单 / 客户
    ↓
PDA【出库页面】
    ↓
扫描整车总二维码
    ↓
批量确认出库
    ↓
状态：已入库 → 已出库
    ↓
形成完整整车档案
    ↓
售后查询 / 返工 / 换件 / 追溯
```

---

# 7. 整车状态机

状态建议：

```text
0 = 待组装
1 = 组装完成
2 = 已入库
3 = 已出库
4 = 返工中
5 = 售后维修
6 = 报废
```

正常状态流转：

```text
待组装
  ↓
组装完成
  ↓
已入库
  ↓
已出库
```

后端必须禁止：

```text
待组装 → 已入库
待组装 → 已出库
组装完成 → 已出库
已出库 → 已出库
```

异常状态回退必须通过专门业务：

- 撤销入库
- 撤销出库
- 返工
- 售后维修
- 报废

禁止前端直接更新 `status`。

---

# 8. 核心业务规则

## 8.1 车架规则

- `frame_sn` 全系统唯一。
- `frame_sn` 是整车业务主 ID。
- 不允许重复建档。
- 车架可以先建档再组装。
- 整车二维码损坏可以重新生成/补打，不影响 `frame_sn`。

---

## 8.2 部件规则

部件至少支持：

```text
MOTOR
BATTERY
DISPLAY
```

系统架构必须支持未来扩展：

```text
LIGHT
CONTROLLER
BRAKE
OTHER
```

不得把三个部件类型写死在大量业务代码中。

---

## 8.3 防重复绑定

同一个：

- 电机 SN
- 电池 SN
- 仪表 SN

不能同时存在两条有效绑定。

要求双层保护：

### 业务层

提交前检查：

```text
该部件是否已绑定其他车架
```

### 数据库层

必须增加数据库级唯一性保护。

必须考虑两个 PDA 同时扫描同一个部件的并发情况。

---

## 8.4 扫码类型校验

当前要求扫描电机时：

如果扫描到电池码：

必须提示：

```text
当前应扫描电机二维码，检测到电池二维码，请重新扫描。
```

二维码/SN 校验至少包括：

- 去除首尾空格
- 空值
- 长度
- 格式
- 类型
- SN 前缀
- 是否存在
- 当前状态
- 是否已绑定

SN 规则做成系统参数，不要写死。

---

# 9. 整车总二维码

四码绑定成功后生成整车总二维码。

建议二维码内容：

```text
BIKE:{vehicleId}:{token}
```

不要直接将完整业务数据全部明文塞入二维码。

数据库字段至少：

```text
vehicle_id
frame_sn
vehicle_qr_code
vehicle_qr_token
qr_version
qr_status
generated_at
```

功能：

- 自动生成；
- 查看；
- 下载；
- 打印；
- 补打；
- 重新生成；
- 查询对应整车。

第一期不做打印机 SDK 深度集成。

---

# 10. 零部件来料管理

字段：

```text
id
part_sn
part_type
supplier_id
batch_no
qc_status
bind_status
current_vehicle_id
current_frame_sn
status
inbound_time
remark
created_at
updated_at
```

功能：

- 新增
- 编辑
- 查询
- SN 精确搜索
- 批次搜索
- 供应商搜索
- Excel 批量导入
- Excel 导出
- 查看当前绑定整车
- 查看历史绑定
- 查看使用状态

---

# 11. 整车档案

字段：

```text
id
frame_sn
model_id
status
vehicle_qr_code
vehicle_qr_token
assemble_operator_id
assemble_time
current_warehouse_id
current_location_id
stock_in_time
stock_out_time
current_customer_id
current_order_no
created_at
updated_at
```

Web 页面展示：

```text
整车编号
车架 SN
电机 SN
电池 SN
仪表 SN
整车状态
车型
组装时间
入库时间
出库时间
客户
订单
仓库
库位
```

支持查询：

- 车架 SN
- 电机 SN
- 电池 SN
- 仪表 SN
- 整车二维码
- 客户
- 订单
- 状态
- 日期
- 车型

---

# 12. PDA 组装扫码

PDA 页面顺序：

```text
1. 车架
2. 电机
3. 电池
4. 仪表
5. 确认组装
```

页面示例：

```text
车架    FRAME202609001    ✓
电机    MOTOR202609001    ✓
电池    BAT202609001      ✓
仪表    DISPLAY202609001  ✓

[确认组装]
```

每一步扫码后立即调用后端校验。

后端返回：

- 是否成功
- SN
- 类型
- 状态
- 错误原因

最终提交必须使用事务：

1. 创建/更新整车档案
2. 创建零部件绑定
3. 标记零部件为已占用
4. 更新整车状态
5. 写组装时间
6. 写组装人员
7. 生成整车总二维码
8. 写操作日志

任一步失败全部回滚。

---

# 13. 入库

必须从 PDA【入库】页面执行。

流程：

```text
进入入库
↓
扫码整车总二维码
↓
读取整车
↓
校验 status = 组装完成
↓
选择仓库
↓
选择库位
↓
确认入库
↓
生成入库记录
↓
整车 status = 已入库
```

禁止：

- 未组装完成入库；
- 重复入库；
- 已出库车辆直接入库。

---

# 14. 出库

必须从 PDA【出库】页面执行。

推荐批量出库：

```text
选择客户
↓
选择订单
↓
连续扫描整车总二维码
↓
逐台校验
↓
实时显示：
订单数量
已扫描数量
剩余数量
↓
确认出库
```

校验：

- 整车存在；
- 状态必须为已入库；
- 不允许重复扫码同一车；
- 不允许已出库车辆再次出库。

完成后记录：

```text
outbound_no
customer_id
order_id
vehicle_id
frame_sn
operator_id
stock_out_time
```

---

# 15. 库存

系统库存核心概念：

```text
物理库存
锁定库存
可用库存
```

物理库存：

```text
整车状态 = 已入库
```

可用库存：

```text
可用库存 = 物理库存 - 当前有效锁定库存
```

后台支持：

- 总库存
- 车型库存
- 仓库库存
- 库位库存
- 锁定库存
- 可用库存
- 库龄
- 入库日期

---

# 16. 代理商账号体系

Web 端增加代理商账号。

管理员可以：

- 新建代理商
- 禁用代理商
- 开通账号
- 禁用账号
- 设置库存查看范围
- 设置车型查看范围
- 设置功能权限

代理商只能查看自己有权限的数据。

必须防止：

- A 代理查看 B 代理锁定数据
- A 代理查看 B 代理采购记录
- 越权访问未授权库存

---

# 17. 代理商库存锁定

第一版定义：

> 代理商按【产品/车型 + 数量】锁库存。

暂不在代理商锁库存阶段锁具体车辆 SN。

确认订单/出库阶段，再匹配具体整车。

字段建议：

```text
id
lock_no
agent_id
product_model_id
lock_qty
released_qty
converted_qty
status
expire_time
created_by
created_at
released_at
remark
```

状态：

```text
LOCKED
PARTIAL_CONVERTED
CONVERTED
RELEASED
EXPIRED
CANCELLED
```

要求：

- 防止超卖；
- 支持锁定超时；
- 默认锁定时长可配置；
- 建议默认 48 小时；
- 支持代理主动释放；
- 支持业务员/管理员按权限释放；
- 所有释放记录保留历史；
- 必须支持高并发下库存一致性。

推荐通过：

- 数据库事务
- 原子 UPDATE
- 乐观锁或悲观锁

保证库存不能锁成负数。

---

# 18. 销售订单

字段：

```text
id
order_no
customer_id
agent_id
product_model_id
order_qty
shipped_qty
status
planned_ship_date
remark
created_at
updated_at
```

状态：

```text
DRAFT
CONFIRMED
PARTIAL_SHIPPED
SHIPPED
CANCELLED
```

功能：

- 新建
- 编辑
- 作废
- Excel 导入
- 查询
- 订单详情
- 已出库车辆列表
- 出库进度

---

# 19. 返工 / 换件

必须支持：

- 电机换件
- 电池换件
- 仪表换件

流程：

```text
输入/扫描车架
↓
读取当前部件
↓
进入返工
↓
选择更换类型
↓
扫描新部件
↓
校验新部件是否可用
↓
输入返工原因
↓
确认
↓
旧绑定失效
↓
新绑定生效
↓
记录日志
```

禁止直接覆盖旧 SN。

---

# 20. 零部件绑定历史

独立表：

```text
vehicle_part_binding
```

建议字段：

```text
id
vehicle_id
frame_sn
part_id
part_sn
part_type
bind_time
unbind_time
bind_operator_id
unbind_operator_id
bind_reason
unbind_reason
is_current
created_at
updated_at
```

要求：

- 历史不能物理删除；
- 当前绑定 `is_current = 1`；
- 解绑后设为 `0`；
- 新部件重新创建绑定记录。

---

# 21. 撤销入库

有权限人员才能操作。

```text
已入库
↓
填写撤销原因
↓
确认
↓
组装完成
```

原入库记录：

- 不删除；
- 标记为撤销；
- 写撤销人；
- 写撤销时间；
- 写撤销原因。

---

# 22. 撤销出库

```text
已出库
↓
填写撤销原因
↓
确认
↓
已入库
```

原出库记录：

- 不删除；
- 标记为撤销；
- 保留原客户；
- 保留原订单；
- 保存撤销日志。

---

# 23. 正向追溯

输入：

```text
车架 SN
```

返回完整档案：

```text
整车
├─ 当前电机
├─ 当前电池
├─ 当前仪表
├─ 历史部件
├─ 组装时间
├─ 组装人员
├─ 入库记录
├─ 仓库
├─ 库位
├─ 出库记录
├─ 客户
├─ 订单
├─ 返工记录
└─ 操作日志
```

---

# 24. 反向追溯

支持输入：

```text
电机 SN
电池 SN
仪表 SN
```

返回：

```text
零部件
↓
当前整车 / 历史整车
↓
车架 SN
↓
组装信息
↓
订单
↓
客户
↓
出库时间
```

---

# 25. 工位权限

管理员可配置：

- 车架工位
- 电机工位
- 电池工位
- 仪表工位
- 车灯工位
- 总装工位
- 仓库入库
- 仓库出库
- 返工工位

权限模型：

```text
用户
↓
角色
↓
工位
↓
功能权限
```

不同工位：

- 只能进入自己的 PDA 页面；
- 只能执行自己允许的操作；
- 禁止跨工位操作。

---

# 26. Web 角色

至少：

## 超级管理员

全权限。

## Web 业务员

可：

- 查看库存
- 查看锁定库存
- 查看代理商
- 查看订单
- 查看出库业务

不可：

- 改系统权限
- 改生产绑定关系

## Web 代理商

可：

- 查看授权库存
- 锁定库存
- 释放锁定
- 查看自己的历史
- 发起采购意向

不可：

- 查看其他代理商
- 修改生产数据
- 查看后台配置

## PDA 操作员

只执行对应工位操作。

---

# 27. 操作日志

所有关键业务必须记录。

业务类型至少：

```text
LOGIN
ASSEMBLE
PART_BIND
PART_UNBIND
REWORK
STOCK_IN
STOCK_IN_CANCEL
STOCK_OUT
STOCK_OUT_CANCEL
INVENTORY_LOCK
INVENTORY_RELEASE
ORDER_CREATE
ORDER_UPDATE
DATA_IMPORT
DATA_EXPORT
USER_CHANGE
PERMISSION_CHANGE
```

字段：

```text
id
business_type
business_id
action
frame_sn
before_data
after_data
operator_id
operator_name
client_ip
operation_time
remark
```

---

# 28. Excel 导入导出

## 导入

- 零部件
- 客户
- 代理商
- 订单

导入结果必须返回：

```text
总行数
成功数
失败数
失败行
失败原因
```

不能因为一行错误导致整个文件无法知道原因。

## 导出

- 整车
- 零部件
- 当前库存
- 入库记录
- 出库记录
- 订单
- 返工
- 追溯
- 报关数据
- 操作日志

---

# 29. Dashboard

至少显示：

```text
今日组装
今日入库
今日出库
当前物理库存
当前锁定库存
当前可用库存
待组装
组装完成未入库
已入库
已出库
```

图表以准确、清晰为主。

不要为了图表做复杂数据仓库。

---

# 30. 数据库核心表

至少需要：

```text
sys_user
sys_role
sys_permission
sys_user_role
sys_role_permission
sys_login_log

workstation

customer
agent
supplier
product_model

warehouse
warehouse_location

part
vehicle
vehicle_part_binding

stock_in_record
stock_out_record

sales_order
sales_order_item
sales_order_vehicle

inventory_lock
inventory_lock_detail

rework_record
rework_part_detail

operation_log
system_config
```

Codex 需要根据最终业务进一步补齐合理字段、索引、唯一约束、外键关系。

---

# 31. 数据库索引要求

至少考虑：

```text
vehicle.frame_sn
vehicle.vehicle_qr_token

part.part_sn

vehicle_part_binding.frame_sn
vehicle_part_binding.part_sn
vehicle_part_binding.vehicle_id

stock_in_record.frame_sn
stock_out_record.frame_sn

sales_order.order_no

inventory_lock.lock_no

operation_log.frame_sn
operation_log.operation_time
```

高频查询必须有索引。

---

# 32. API 响应规范

统一：

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {}
}
```

分页：

```json
{
  "code": "SUCCESS",
  "message": "查询成功",
  "data": {
    "records": [],
    "page": 1,
    "pageSize": 20,
    "total": 0
  }
}
```

---

# 33. 业务错误码

至少：

```text
VEHICLE_NOT_FOUND
FRAME_ALREADY_EXISTS
PART_NOT_FOUND
PART_TYPE_MISMATCH
PART_ALREADY_BOUND
PART_NOT_AVAILABLE
VEHICLE_NOT_ASSEMBLED
VEHICLE_ALREADY_STOCKED_IN
VEHICLE_NOT_IN_STOCK
VEHICLE_ALREADY_STOCKED_OUT
INVALID_STATUS_TRANSITION
ORDER_NOT_FOUND
INVENTORY_NOT_ENOUGH
INVENTORY_LOCK_CONFLICT
PERMISSION_DENIED
INVALID_QR_CODE
```

禁止所有业务异常都返回 HTTP 500。

---

# 34. 后端接口建议

## Auth

```text
POST /api/auth/login
POST /api/auth/logout
GET  /api/auth/me
PUT  /api/auth/password
```

## 用户

```text
GET  /api/users
POST /api/users
PUT  /api/users/{id}
PUT  /api/users/{id}/status
```

## 角色

```text
GET  /api/roles
POST /api/roles
PUT  /api/roles/{id}
PUT  /api/roles/{id}/permissions
```

## 零部件

```text
GET  /api/parts
GET  /api/parts/{id}
GET  /api/parts/by-sn/{sn}
POST /api/parts
PUT  /api/parts/{id}
POST /api/parts/import
GET  /api/parts/export
```

## 整车

```text
GET /api/vehicles
GET /api/vehicles/{id}
GET /api/vehicles/by-frame/{frameSn}
GET /api/vehicles/by-qr/{qrCode}
GET /api/vehicles/{id}/trace
```

## 组装

```text
POST /api/assembly/validate-frame
POST /api/assembly/validate-part
POST /api/assembly/submit
GET  /api/assembly/{frameSn}
```

## 二维码

```text
GET  /api/vehicles/{id}/qr
POST /api/vehicles/{id}/qr/regenerate
```

## 入库

```text
POST /api/stock-in/validate
POST /api/stock-in
POST /api/stock-in/{id}/cancel
GET  /api/stock-in
```

## 出库

```text
POST /api/stock-out/validate
POST /api/stock-out
POST /api/stock-out/{id}/cancel
GET  /api/stock-out
```

## 库存

```text
GET /api/inventory
GET /api/inventory/summary
GET /api/inventory/by-model
```

## 订单

```text
GET  /api/orders
POST /api/orders
PUT  /api/orders/{id}
GET  /api/orders/{id}
POST /api/orders/import
```

## 库存锁定

```text
GET  /api/inventory-locks
POST /api/inventory-locks
POST /api/inventory-locks/{id}/release
POST /api/inventory-locks/{id}/convert
```

## 代理商

```text
GET /api/agent/inventory
GET /api/agent/inventory-locks
POST /api/agent/inventory-locks
POST /api/agent/inventory-locks/{id}/release
```

## 返工

```text
GET  /api/rework
POST /api/rework
GET  /api/rework/{id}
```

## 追溯

```text
GET /api/trace/frame/{frameSn}
GET /api/trace/part/{partSn}
```

## Dashboard

```text
GET /api/dashboard/summary
GET /api/dashboard/trend
```

---

# 35. Web 页面清单

```text
/login

/dashboard

/system/users
/system/roles
/system/workstations
/system/config

/master/customers
/master/agents
/master/suppliers
/master/models
/master/warehouses
/master/locations

/parts
/vehicles

/inventory
/stock-in
/stock-out

/orders
/inventory-locks

/rework
/trace
/operation-logs
```

---

# 36. 代理商页面

```text
/agent/dashboard
/agent/inventory
/agent/locks
/agent/purchase-intents
```

---

# 37. PDA 页面

```text
/pda/login
/pda/home
/pda/assembly
/pda/stock-in
/pda/stock-out
/pda/rework
/pda/trace
```

---

# 38. 事务要求

以下业务必须事务执行：

- 组装
- 绑定
- 换件
- 入库
- 撤销入库
- 出库
- 撤销出库
- 库存锁定
- 库存释放
- 库存锁定转订单
- 批量出库

任何一步失败必须整体回滚。

---

# 39. 删除策略

生产业务数据禁止物理删除。

以下表尤其禁止直接 DELETE：

- vehicle
- part
- vehicle_part_binding
- stock_in_record
- stock_out_record
- sales_order
- inventory_lock
- rework_record
- operation_log

使用：

- 状态
- 作废
- 逻辑删除
- 撤销记录
- 历史表

保证全程可追溯。

---

# 40. 安全要求

必须：

- Spring Security
- JWT
- BCrypt
- 接口鉴权
- 数据权限
- 防越权
- 参数校验
- SQL 注入防护
- 文件上传类型限制
- Excel 文件大小限制
- 登录失败合理限制
- 敏感配置使用环境变量
- 生产环境关闭详细错误堆栈

代理商数据隔离必须后端实现，不能只靠前端隐藏。

---

# 41. Mock 数据

开发环境初始化至少生成：

## 管理员

```text
username: admin
```

开发环境可设置临时密码。

生产环境首次登录强制修改密码。

## 示例零部件

```text
FRAME202609001
MOTOR202609001
BAT202609001
DISPLAY202609001
```

## 客户

```text
ABC Bike Co., Ltd.
```

## 订单

```text
SO202609001
```

## 仓库

```text
成品仓
A区
A-01-01
```

---

# 42. 测试要求

必须至少覆盖以下场景。

## 组装

1. 正常组装
2. 重复车架
3. 重复电机
4. 重复电池
5. 重复仪表
6. 错误类型扫码
7. 不存在 SN
8. 已报废零件
9. 两个工位同时绑定同一个电池

## 入库

10. 正常入库
11. 未组装入库
12. 重复入库
13. 已出库车辆入库

## 出库

14. 正常出库
15. 未入库出库
16. 重复出库
17. 批量出库部分车辆异常
18. 同一车重复扫码

## 返工

19. 正常换电池
20. 新电池已被占用
21. 换件后历史可查

## 库存锁定

22. 正常锁库存
23. 库存不足
24. 两个代理并发抢库存
25. 自动过期
26. 手工释放
27. 越权释放

## 权限

28. 业务员访问管理员接口
29. 代理 A 查看代理 B 数据
30. PDA 工位跨工位访问

## 导入

31. 正常 Excel
32. 部分行失败
33. 重复 SN
34. 非法格式

---

# 43. 项目进度计划

内部目标：

> 1.5 个月完成开发，2 个月完成正式交付。

如从 `2026-09-07` 开始：

## 第一阶段：2026-09-07 ～ 2026-09-11

完成：

- 项目初始化
- 技术架构
- 数据库第一版
- 登录
- JWT
- 权限框架
- README
- Flyway

里程碑：

> 项目可启动，基础框架完成。

---

## 第二阶段：2026-09-12 ～ 2026-09-18

完成：

- 用户
- 角色
- 工位
- 基础资料
- 客户
- 供应商
- 代理商
- 仓库
- 库位
- 零部件
- Excel 导入基础能力

里程碑：

> Web 基础管理功能完成。

---

## 第三阶段：2026-09-19 ～ 2026-09-25

完成：

- 整车档案
- PDA 组装
- 四码关联
- 防重复绑定
- 扫码防错
- 整车总二维码
- 组装状态机

里程碑：

> 组装生产核心链路完成。

---

## 第四阶段：2026-09-26 ～ 2026-10-02

完成：

- PDA 入库
- 仓库
- 库位
- 库存
- PDA 出库
- 客户
- 订单
- 批量出库
- 操作日志

里程碑：

> 生产 → 入库 → 库存 → 出库主流程完成。

---

## 第五阶段：2026-10-03 ～ 2026-10-09

完成：

- 代理商账号
- 代理商数据权限
- 库存锁定
- 库存释放
- 锁定超时
- 业务员页面
- 代理商库存页面

里程碑：

> 代理商库存协同完成。

---

## 第六阶段：2026-10-10 ～ 2026-10-21

完成：

- 正向追溯
- 反向追溯
- 返工换件
- 撤销入库
- 撤销出库
- Dashboard
- 全部 Excel 导出
- 报关数据
- 权限补全
- 自动化测试

里程碑：

> 全部计划业务功能开发完成。

---

## 第七阶段：2026-10-22 ～ 2026-10-28

完成：

- 全流程联调
- PDA 真机测试
- 扫码枪测试
- 并发测试
- 权限测试
- 数据一致性测试
- Bug 修复

里程碑：

> 系统进入可试运行状态。

---

## 第八阶段：2026-10-29 ～ 2026-11-03

完成：

- 客户试运行
- 现场问题修复
- 数据导入检查
- 服务器部署
- 数据库备份
- 使用说明

里程碑：

> 正式验收准备完成。

---

## 正式交付：2026-11-06 前

完成：

- 生产环境上线
- 数据库初始化
- 管理员账号
- 培训
- 操作手册
- 部署说明
- 数据备份说明
- 源代码整理
- 最终验收

---

# 44. Codex 开发执行顺序

不要一次把全部系统同时铺开。

严格按照以下顺序：

```text
01 项目初始化
02 数据库
03 登录与权限
04 基础资料
05 零部件
06 整车
07 PDA 组装
08 四码关联
09 总二维码
10 入库
11 库存
12 订单
13 出库
14 操作日志
15 代理商
16 库存锁定
17 返工
18 追溯
19 报表
20 测试
21 部署
```

每一步完成后必须保证可编译、可运行。

---

# 45. 编码要求

要求：

- Controller 不写复杂业务；
- 核心业务进入 Service；
- DTO / VO / Entity 分离；
- 枚举统一管理；
- 错误码统一管理；
- 状态机不要散落 if/else；
- 事务边界清晰；
- SQL 可读；
- 关键逻辑写必要注释；
- 不要为了“设计模式”过度抽象；
- 优先简单、清晰、稳定；
- 所有核心业务必须有测试；
- 禁止在 Controller 中直接操作 Mapper；
- 禁止 Magic Number；
- 禁止 Magic String；
- 时间统一使用明确时区策略；
- 数据库时间字段规范统一。

---

# 46. Codex 每次任务完成后的输出格式

每完成一个开发任务，请返回：

```text
【本次完成】
1.
2.
3.

【修改文件】
1.
2.
3.

【数据库变更】
...

【新增接口】
...

【测试结果】
后端：
前端：
测试：

【当前可验证步骤】
1.
2.
3.

【尚未完成】
1.
2.

【下一步建议】
...
```

同时更新：

```text
docs/progress.md
```

---

# 47. 第一次执行任务

Codex 第一次收到本文档后，请不要先一次性写完整业务。

第一轮只完成：

1. 检查 `F:/bike`
2. 如果已有项目，分析当前项目
3. 如果为空，创建三端：
   - `bike-server`
   - `bike-web`
   - `bike-h5`
4. 创建 MySQL 数据库初始化结构第一版
5. 完成用户、角色、权限基础表
6. 完成整车、零部件、绑定关系核心表
7. 完成 Spring Boot 启动
8. 完成 Vue Web 启动
9. 完成 H5 启动
10. 完成登录 API 框架
11. 完成 OpenAPI
12. 创建 docs 文档结构
13. 更新 `docs/progress.md`
14. 编译全部项目并修复错误

完成第一轮后，再开始第二阶段。

---

# 48. 验收核心标准

系统满足以下要求才算核心业务完成：

### 生产

- 四码能够稳定绑定；
- 重复零部件绝不能绑定第二台车；
- 组装生成整车档案；
- 自动生成整车总二维码。

### 入库

- 只有组装完成才能入库；
- 入库记录仓库、库位、人员、时间。

### 出库

- 只有在库整车才能出库；
- 出库能够关联客户和订单。

### 库存

- 实时计算当前库存；
- 代理锁库存不允许超卖。

### 返工

- 换件后原历史不能丢失。

### 追溯

能够通过：

```text
车架
电机
电池
仪表
```

任意 SN 查询完整关系。

### 权限

- 代理商不能越权；
- 工位不能越权；
- 业务员不能修改生产数据。

### 日志

所有关键操作都能追踪到：

```text
谁
什么时候
做了什么
原数据
新数据
```

---

# 49. 本期不实现范围

除非后续明确新增需求，本期不要擅自实现：

- SAP 对接
- 金蝶对接
- 用友对接
- ERP 对接
- WMS 对接
- MES 对接
- 原生 Android APP
- iOS APP
- 微信小程序
- 支付
- 在线合同
- 财务
- 采购系统
- APS 排产
- 设备 PLC 对接
- 打印机 SDK 深度集成
- 断网离线生产同步
- 多工厂集团架构

但代码结构要避免明显阻碍未来扩展。

---

# 50. 最终要求

本项目不是演示项目。

最终必须达到：

```text
可编译
可启动
可测试
可部署
可上线
可追溯
可维护
```

任何涉及：

- 状态流转
- 零件唯一绑定
- 库存
- 库存锁定
- 权限
- 返工
- 入库
- 出库

的关键业务逻辑，必须以后端和数据库为最终可信边界，不能仅依赖前端控制。
