# 数据库设计说明

数据库名称为 `bike`，业务表统一使用 `bike_` 前缀。应用通过 Flyway 执行迁移，生产部署使用 `classpath:db/migration-prod`，开发样例使用 `classpath:db/migration`。截至批次15，独立验收库已验证 V24；后续开发中的迁移以实施台账记录的实际验证结果为准。

## 第一版范围

- `bike_sys_user`、`bike_sys_role`、`bike_sys_permission`：账号、角色和权限。
- `bike_sys_user_role`、`bike_sys_role_permission`：多对多授权关系。
- `bike_workstation`：PDA 工位定义。
- `bike_product_model`：车型定义，代理商锁库存的业务维度。
- `bike_part`：零部件来料及当前占用状态。
- `bike_vehicle`：整车档案，`frame_sn` 为业务主 ID，`status` 使用规范定义的状态值。
- `bike_vehicle_part_binding`：当前绑定和历史绑定，历史记录不物理删除。
- `bike_inventory_lock`：按车型和数量锁定整车库存，不绑定具体车辆 SN。
- `bike_operation_log`：关键业务操作审计记录。
- V2 补充客户、供应商、代理商、仓库、库位、销售订单、订单明细、入库和出库记录。
- V3 补充订单车辆、返工记录、返工部件明细、登录日志、系统配置和撤销字段。
- V4 为开发环境样例数据迁移；生产环境建议改用独立 seed 流程，避免自动写入演示数据。
- V5 增加用户代理商/工位关联字段并初始化权限种子和管理员授权。
- V6 增加默认锁库存时长配置 `inventory.lock.hours=48`。
- V7 初始化 Web 业务员和 PDA 操作员的默认功能权限。
- V8 增加代理商采购意向、代理商角色和范围权限。
- V9 增加整车二维码生成时间 `qr_generated_at`。
- V10 回填历史整车二维码的生成时间。
- V11 增加车架和零部件 SN 的长度、正则和前缀校验配置；业务入口统一通过 `SnRuleService` 执行，配置缺失时使用受限默认规则。
- V12 仅修复仍处于首次登录状态的开发样例管理员密码哈希，已修改密码的账号不会被覆盖。
- V13 将订单车辆关联的唯一约束改为普通索引，允许撤销出库后再次出库，同时保留每次出库历史关联。
- V14 为全部业务表设置表注释，并为全部业务字段设置中文数据字典注释。
- V15 为 Flyway 内部 `bike_schema_history` 表及其字段设置注释，确保所有 `bike_` 表均有完整注释。

## 并发和唯一性

`bike_part.part_sn`、`bike_vehicle.frame_sn`、`bike_vehicle.vehicle_qr_token` 使用唯一约束。绑定表通过生成列和唯一索引保证同一零部件只能有一条当前绑定、同一车辆同一部件类型只能有一条当前绑定；解绑历史使用 `is_current = 0` 保留。

库存锁定的扣减和释放必须在 Service 事务中配合原子更新或锁机制实现。V1-V3 只创建结构，V4 仅用于当前开发环境样例数据验证。

## V20–V22 增量

V20增加bike_order_reservation：订单与来源锁定、幂等请求标识、车型、转换数量、到期时间。订单主键唯一，来源锁+请求标识唯一。
V21增加 `bike_sales_order.remark` 及 `bike_order_reservation.expiry_audited_at`。旧订单备注为空；续占用重置到期审计标记。

V22 注册可配置权限 `PRODUCTION_MANAGE`，用于 SKU、标签模板、生产图片、生产异常解除及旧件复检等生产管理写操作。迁移只注册权限，不自动授予既有普通岗位；管理员须按现场岗位矩阵分配。
两套迁移分别在根db目录与后端resources镜像。V16仍是Java迁移；勿修改已运行历史迁移。

## V23–V24 已验证增量

V23 新增售后案例、留言、附件元数据和补发记录表，并注册独立的读取、申报、审核、技术、留言和发货权限。案例状态变更及内部操作保留在 `bike_operation_log`。附件元数据表的存在不代表 V23 已提供上传和下载闭环。

V24 新增 `bike_pi`、`bike_pi_item`、`bike_pi_payment`、`bike_pi_logistics`。PI 保存客户及单据快照，商品保存真实 SKU 与商品快照，金额使用 DECIMAL(18,2)；来源订单唯一关联、收款 requestId 在单据内唯一。财务确认与发货放行权限只注册，不自动授予普通岗位。

空生产库迁移到 V24，以及 V16 升级到 V24 并保留旧数据，均已在独立 MySQL 库验证。后续只添加新迁移，不改已应用脚本；开发与生产两套 SQL 及根目录镜像保持一致。

## V25 收款保留订单占用

新增 `bike_order_reservation.hold_mode`，默认 `TIMED`，检查约束只允许 `TIMED`、`PAID`、`RELEASED`；新增 `payment_hold_pi_id` 外键、`payment_held_at`、`payment_held_by`、`payment_held_amount` 记录转保留依据，`released_at` 记录主动释放时间。保留原 `expires_at`，新增方式/截止/审计时间联合索引。旧记录不按历史付款回填 `PAID`。

有效占用的统一 SQL 条件为有效订单状态且 `hold_mode='PAID'`，或 `hold_mode='TIMED' AND expires_at>NOW(3)`；数量仍为 `GREATEST(reserved_qty-shipped_qty,0)`。到期任务仅扫描 `TIMED`。收款转保留使用获得互斥锁后的独立条件更新，再次以数据库当前时刻检查期限；到期后登记到账不会恢复库存。锁顺序为库存互斥锁、订单/占用、PI、收款记录，相关业务写入和审计同事务。


## V34 售后旧件退回

新增 `bike_after_sales_return`，以补发条目 shipment_id 为主键，记录各配件独立的退回仓库/收件人/地址快照、客户运单、收件/检测/完成时间、检测结论、处置理由和递增 revision。新建要求退回的补发条目时，同事务创建 REQUIRED 记录；全部动作与案例行锁、版本检查和审计绑定。归档案例时所有要求退回条目必须已完成或有审核豁免。

迁移仅对旧 `old_part_return_required=1` 的条目创建记录：已有真实收件时间的保留为 RECEIVED，其余为 REQUIRED。不推测旧退回地址、运单或操作者；无需退回的条目不生成记录。历史已归档案例仍保持归档，不自动重开。V34 四份迁移 SHA256 为 `8E44A43E68A3413F835AA7DDDADA28F73CA402D8FA8B1D249F08BDBB92B5657E`，已在独立全新库、V16升级库及隔离应用库执行，后续不得修改。

## V35 售后方案版本和批准配件

新增方案版本与配件条目，补发关联实际批准条目；数量按案例同一配件编号跨版本累计。旧文本保存为LEGACY，旧补发标记LEGACY_UNLINKED，须人工核对，不猜审批身份、收费和配件编号。四镜像SHA256为 `D9871948A0CDFF447DD7621847B6A1706B4401C9047AB7BEDCD84390DB289CB0`，已执行独立空库/V16升级和隔离API迁移，不得修改。

## V36 售后发运快照及物流进度

补发增加revision、仓库、独立收货地址、预计到达、物流状态、签收登记及取消原因。旧地址、仓库和物流进度未知则保持NULL，不把历史SHIPPED猜成仍在途。取消保留原行，旧件增CANCELLED状态。四镜像SHA256为 `4BB49A69AB7DAD6106209ED5249E7059077D133D3157011CA20F5D3314B45D3A`，已应用独立空库/V16升级及隔离API，禁止修改。

## V37 售后Email事务队列

新增来源事件/受众唯一的通知队列、原始及当前收件地址、公开模板快照、稳定Message-ID、状态/次数/revision、领取租约及固定失败代码。迁移不回填历史事件，不伪造已发送记录。四镜像SHA256为 `070F790E31E6FB29CE720A9F59E15333C62FF9A24CF7440BACAED2FF4AF814B3`，已通过新库与升级检查，禁止修改。

## V38 PI受控模板发布

`bike_pi_template`保存不可修改的模板编号/版本、内外销固定版式、卖方及银行资料、发布人/时间和原因。`PI_TEMPLATE_MANAGE`独立于业务编辑权限，仅默认分配超级管理员，不导入假银行资料或猜测历史模板。四镜像SHA256为 `641CDB598EC7A01BB8D21CF1E4A44896417E411BC49D16C4B7673622AFE1C13E`，已在隔离API执行，禁止修改。

## V39 PI导出归档任务

`bike_pi_export`按PI修订ID唯一，保存对外商务快照、PENDING/PROCESSING/READY/FAILED、revision、领取租约、尝试次数和两文件存储键/字节数/SHA256。文件二进制使用现有本地/COS存储。确认事务创建队列，文件生成在独立工作线程；只有两文件都保存后标记READY。失败重试保留成功文件；历史已发布单据需要显式建立LEGACY_SNAPSHOT任务，不回填原发布时间或伪造历史文件。四镜像SHA256为 `4180A74764974AF41419223613E5D0956669F14798985C7B2F575FAC397E52AB`，已在隔离API执行，禁止修改。

## V40/V41 PI资料与确认附件

V40为明细增加unit/image_attachment_id/image_sha256，为PI增加other_fee_amount。历史单位/图片保持空，不猜测原文件；日期和运输保存在原document_json。SHA256：`154FDA511E346C92B8F8B2315961F008F1006EE430C376605FFB6784DC1E8D17`。

V41新增bike_pi_confirmation_attachment，包含PI修订、请求ID、可见范围、文件元数据、对象键和SHA256。请求在PI内唯一；没有复制历史附件。SHA256：`1E482663D9500EF529B652EB576B5BAF17F2D9022C7A791ADA14FE6CF552C0C4`。两迁移四镜像均已在空库/升级库与隔离API执行，禁止修改。

## V42 客户商务资料

新增bike_customer_profile，以原客户ID关联地址、国家/地区、Email、币种偏好、业务归属、revision及修改人员/时间。历史未知资料不回填。业务归属为部门/负责人文字，不改变账号权限；新增CUSTOMER_PROFILE_WRITE仅默认分配超级管理员，可独立授权内部岗位。四镜像SHA256：`17FD441ADC43AF27CCA9E35834918BE6FEE571A4D4C08172AEC08C6AC95EF093`，已应用隔离API，禁止修改。

## V43 收款方式及撤回审计

bike_pi_payment增加payment_method/payment_method_note/recorded_at/revision及reversal_request_id/reversal_reason/reversed_by/reversed_at。旧方式和登记时间留空。PI_PAYMENT_REVERSE默认仅管理员，可独立授权。四镜像SHA256：`CB099A8054865C0050776BBE58029887F51AC1EC5858BECAC395D474FE934D57`，已用于真实MySQL迁移夹具，不再修改。

## V44 PI物流记录附件

新增bike_pi_logistics_attachment，实际logistics_id外键、每记录请求唯一、CUSTOMER/INTERNAL可见性及文件字节数/类型/对象键/SHA256。历史无文件不回填。四镜像SHA256：`426A33F0C93CF2E212CBF6B8D717F3270438260833C64837B993C0AD755D174C`，已执行新库/升级库及隔离API，不再修改。

## V45 内部客户授权

增加内部用户客户关系、范围版本和独立管理/全范围权限。普通岗位不自动获得全部客户。四镜像SHA256：`BBF677F8BED0558F0B3F2D1216D4E07F7854449C19881117784BFF81ED674B87`，已执行隔离API及全新/升级库，禁止修改。

## V46 受控卖方资料

bike_pi_template增加seller_country/seller_contact_name/seller_phone/seller_email。历史值允许NULL，不推断或更新旧模板内容、银行资料和发布时间；新发布完整性由V3模板服务强制。四镜像SHA256：`2E420F805163A9E484733AA4BF390439D7DF620C0931E6011CADDE9924FFA1F5`，已执行隔离API及全新/升级库，禁止修改。

## V49 上传意图及绑定失败核对

`bike_file_upload`以服务端对象键为主键，保存业务类型/标识快照、文件名/类型/大小/SHA256、上传人、状态、实际对象状态及并发版本。写对象前通过独立连接池提交意图，不对业务或用户父表建外键；绑定事务持记录行锁，并与业务引用同时标记BOUND。`bike_file_upload_review`保存人工核对和清理申请的原因、操作者及操作前状态/版本，并与通用审计同事务。业务附件、归档和旧付款凭证字段增加对象键索引。新增`FILE_REVIEW_MANAGE`不自动分配给普通岗位。

迁移不回填历史上传记录、不删除历史对象或发货后的业务附件。四镜像SHA256：`1063A4A6D7B204D3E75CD6E26C04A5304904AE3A07732A6446CB3CFD814AB1A2`，已执行隔离API、全新库及V16升级库，禁止改写。状态与保留边界见[上传绑定失败核对](file-upload-reconciliation.md)。
