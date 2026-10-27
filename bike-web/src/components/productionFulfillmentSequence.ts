export type SequenceRole = 'plan' | 'material' | 'assembly' | 'print' | 'warehouse' | 'sales' | 'finance' | 'system'
export const sequenceRoles: Record<SequenceRole, { name: string; location: string }> = {
  plan: { name: '生产计划员', location: 'Web · 商品 / 工单' },
  material: { name: '来料登记员', location: 'Web · 零部件' },
  assembly: { name: '组装操作员', location: 'PDA · 组装' },
  print: { name: '打印操作员', location: 'PDA / 打印队列' },
  warehouse: { name: '仓管员', location: 'PDA · 入库 / 出库' },
  sales: { name: '销售 / 订单员', location: 'Web · 锁定 / 订单' },
  finance: { name: '财务 / 放行人员', location: 'Web · PI 与履约' },
  system: { name: '业务系统', location: '自动校验与记录' },
}
export type SequenceMessage = {
  from: SequenceRole; to: SequenceRole; kind: 'action' | 'result' | 'condition' | 'handoff'
  title: string; summary: string; location: string; input: string; check: string; result: string; recovery: string; guideStep: number
}
export type SequenceGroup = { title: string; tag: string; messages: SequenceMessage[] }
export type SequencePhase = { title: string; subtitle: string; roles: SequenceRole[]; before: string; deliver: string; decision: string; groups: SequenceGroup[] }

export const fulfillmentSequence: SequencePhase[] = [
  {
    title: '资料准备', subtitle: '从商品规格到生产工单', roles: ['plan', 'material', 'system', 'assembly'],
    before: '首次生产或新增配置时准备资料；已有且可用的资料直接复用。',
    deliver: '已启用的 SKU、可用部件 SN、已下达工单号与批次。',
    decision: '有工单不等于有库存；车架号取自实物，不在零部件菜单里编造。',
    groups: [
      { title: '先准备车型、模板与 SKU', tag: '商品资料', messages: [
        { from: 'plan', to: 'system', kind: 'action', title: '确认可用车型', summary: '系统管理 → 车型管理', location: 'Web → 系统管理 → 车型管理', input: '查找实际要生产的车型，确认编码、名称与启用状态。', check: '后续保存 SKU 时，所选车型必须存在且可用。', result: '得到 SKU 所属车型；同一车型下可建立多种规格。', recovery: '车型缺失或已停用时，由有权限的人员维护后再建 SKU。', guideStep: 0 },
        { from: 'plan', to: 'system', kind: 'action', title: '确认标签模板', summary: '核对尺寸、内容与打印版式', location: 'Web → 生产中心 → 标签模板', input: '选择已配置的标签模板，核对尺寸、标题和版式。', check: '生产标签依赖 SKU 关联的模板，不能只填商品名称就开始打印。', result: '得到创建 SKU 时要使用的模板。', recovery: '模板缺失或内容不合适时，先维护模板，再继续创建 SKU。', guideStep: 1 },
        { from: 'plan', to: 'system', kind: 'action', title: '创建具体配置的 SKU', summary: '选车型 + 编码 + 规格 + 模板', location: 'Web → 业务办理工作台 → 商品准备 → 新增 SKU', input: '填写 SKU 编码、名称、规格及所需标签资料，选择车型与模板。', check: '校验编码、车型及填写的条码资料；已有 SKU 的车型和编码不能随意修改。', result: '生成代表一种确定配置的 SKU；同一 SKU 可生产多台车。', recovery: '提示编码重复时先查已有 SKU；配置不同则建立新的 SKU。', guideStep: 2 },
        { from: 'system', to: 'plan', kind: 'result', title: 'SKU 保存完成', summary: '后续工单、库存和销售共用此规格', location: 'Web → 商品准备', input: '核对列表中保存的 SKU 编码、名称和规格。', check: '生产前确认该 SKU 及所属车型仍可用。', result: '将 SKU 编码与配置交给生产人员；此时没有增加整车库存。', recovery: '未看到保存结果时先刷新核对，不要重复创建同一编码。', guideStep: 2 },
      ] },
      { title: '登记部件，确认实物编码', tag: '来料准备', messages: [
        { from: 'material', to: 'system', kind: 'action', title: '登记三个部件 SN', summary: '电机 / 电池 / 仪表，按实物填写', location: 'Web → 系统管理 → 零部件管理', input: '登记电机、电池、仪表的真实 SN，填写类型、批次、供应商及质检状态。', check: '生产扫码时部件必须已登记、类型匹配、未绑定、可用且质检合格。', result: '三个部件可供 PDA 查询和绑定。', recovery: '未知编码先登记；未质检放行的部件先处理质检，不要换类型绕过。', guideStep: 3 },
        { from: 'material', to: 'assembly', kind: 'handoff', title: '交接实物与车架号', summary: '车架取实物号码，新车架选“车架”', location: '实物交接；PDA → 组装 → 编码类型', input: '把车架、电机、电池、仪表交给组装员，核对实物上的编码。', check: '当前后台没有独立的车架号维护页面；新车架要明确选择“车架”类型。', result: '组装人员拿到真实车架号和三个已登记部件。', recovery: '实物没有号码时先确认企业编号和贴码流程，不能随意填写一个号码。', guideStep: 5 },
      ] },
      { title: '下达工单并交接到工位', tag: '生产安排', messages: [
        { from: 'plan', to: 'system', kind: 'action', title: '创建批量生产工单', summary: '选 SKU、填写批次和计划数量', location: 'Web → 业务办理工作台 → 生产办理 → 创建工单', input: '选择 SKU，填写生产批次和正整数计划数量，例如计划 100 台。', check: 'SKU 必须可用，数量和批次需符合表单要求。', result: '一张工单对应一个 SKU、一个批次和计划数量。', recovery: '创建结果不确定时先查询工单，确认是否已创建再继续。', guideStep: 4 },
        { from: 'system', to: 'plan', kind: 'result', title: '返回已下达工单', summary: '尚未逐台建车，也未形成库存', location: 'Web → 生产工单列表', input: '记录工单号、SKU、批次、计划数量。', check: 'PDA 只允许继续处理可生产状态的工单，且不能超过计划名额。', result: '工单可供 PDA 搜索；首码才建立当前单车记录。', recovery: '暂停工单需由有权限人员确认后恢复，不能直接继续扫码。', guideStep: 4 },
        { from: 'plan', to: 'assembly', kind: 'handoff', title: '交接工单号与生产要求', summary: '组装员按工单号 / SKU / 批次查找', location: 'PDA → 组装 → 查找工单', input: '把工单号、SKU、批次及计划数量交给工位人员。', check: '核对屏幕上的商品规格与实物一致。', result: '进入下一阶段：按这张工单逐台扫描四码。', recovery: '搜不到工单时核对关键字、工单状态及账号权限。', guideStep: 5 },
      ] },
    ],
  },
  {
    title: '生产与入库', subtitle: '逐台扫码、贴标、确认入库', roles: ['assembly', 'system', 'print', 'warehouse'],
    before: '已下达工单有可用名额，三个部件已登记并质检合格。',
    deliver: '已绑定四码、贴好标签并入库的车辆，以及可查询的入库记录。',
    decision: '四码可任意顺序；扫描错误与打印不确定要先处理，入库必须另行确认。',
    groups: [
      { title: '每台车重复：选择工单并扫描四码', tag: '循环 · 每台车', messages: [
        { from: 'assembly', to: 'system', kind: 'action', title: '查找并选择生产工单', summary: '输入工单号 / SKU / 批次后搜索', location: 'PDA → 组装 → 查找工单 → 搜索 / 刷新', input: '选择目标工单，核对 SKU、批次、已完成与作业中数量。', check: '工单必须已下达；已占用和已完成数量不能耗尽计划名额。', result: '本次扫码归入所选工单。', recovery: '暂停或名额已满时联系计划员核对，不要误选其他工单。', guideStep: 5 },
        { from: 'assembly', to: 'system', kind: 'action', title: '扫描本车第一个编码', summary: '新车架先选“车架”，其余可自动识别', location: 'PDA → 组装 → 编码类型 / 扫描编码', input: '扫描或手工输入实物编码；四类编码任意顺序均可。', check: '检查编码类型、占用情况及部件状态；新车架不能依赖自动识别。', result: '首码成功后自动创建本车记录并预占该编码。', recovery: '未登记部件先回后台登记；车架未识别时选择正确类型后重试。', guideStep: 5 },
        { from: 'system', to: 'assembly', kind: 'result', title: '显示当前单车与已扫描项', summary: '进度从 1/4 开始，保留已扫编码', location: 'PDA → 当前单车', input: '核对单车任务及各类编码展示是否对应同一辆车。', check: '同一类别不能重复绑定不同编码，同一码不能充当两类编码。', result: '继续为这辆车扫描剩余编码。', recovery: '需要继续先前任务时用“恢复 / 刷新单车进度”核对，不要凭记忆重新建车。', guideStep: 5 },
        { from: 'assembly', to: 'system', kind: 'action', title: '扫描其余三个编码', summary: '确认本车车架、电机、电池、仪表', location: 'PDA → 组装 → 确认本次扫描', input: '逐个扫描本车剩余部件，确认每次成功反馈。', check: '校验部件已登记、未绑定、可用且质检合格；车架车型与工单相符。', result: '未齐四码时保存进度；第四码成功后进入自动完成。', recovery: '部件不可用或质检未通过时先处理原部件，不可编造新码继续。', guideStep: 5 },
        { from: 'assembly', to: 'system', kind: 'condition', title: '若扫错：取消当前单车后重扫', summary: '只在需要换码时执行，不是必经步骤', location: 'PDA → 组装 → 取消原因 → 取消当前单车并释放占用', input: '填写取消原因，核对后取消尚未完成的单车任务。', check: '只有未完成任务可取消；四码完成后不能按此方式换码。', result: '释放该任务的编码占用与工单名额，再从首码开始。', recovery: '已经完成的车辆发现问题，应交由异常或返工流程处理。', guideStep: 5 },
        { from: 'system', to: 'assembly', kind: 'result', title: '第四码成功，完成本车绑定', summary: '建立整车关联，生成总码与打印任务', location: 'PDA → 组装 → 上一台已完成', input: '核对完成提示与本车总码。', check: '系统再次检查四类编码完整、可用且与 SKU 车型相符。', result: '形成整车及工单关联；当前单车清空，可开始下一台。', recovery: '完成结果或标签加载异常时先恢复任务、核对打印记录，避免重复组装。', guideStep: 5 },
      ] },
      { title: '打印与实物交接', tag: '人工核对', messages: [
        { from: 'print', to: 'system', kind: 'action', title: '查看、领取并打印标签', summary: '人工预览打印，或由终端领取', location: 'PDA → 上一台已完成；或 Web → 打印队列', input: '选择对应打印任务，预览标签并执行打印。', check: '任务应可领取，总码版本有效；不能重复发送已领取或已处理任务。', result: '打印动作被提交；还需要核对实物出纸。', recovery: '任务被其他终端领取时先核对终端状态，不要重复创建打印。', guideStep: 6 },
        { from: 'print', to: 'system', kind: 'condition', title: '打印失败 / 不确定：先核对再补打', summary: '任务已提交不等于已实际出纸', location: 'PDA 打印回报；Web → 打印队列', input: '核对纸张、设备和实物标签，按实际情况回报结果；补打填写原因。', check: '只能回报当前账号领取的任务；存在待处理打印任务时不能重复补打。', result: '明确打印结果，防止同车误贴或重复发标签。', recovery: '结果不确定时保留待核对状态，确认原任务后再决定是否补打。', guideStep: 6 },
        { from: 'print', to: 'warehouse', kind: 'handoff', title: '贴标后交接车辆', summary: '车辆、实物车架号与标签总码一致', location: '打印工位 → 入库工位', input: '核对标签属于当前车辆，贴好后交给入库仓管。', check: '人工核对实际标签；系统不会替代实物贴标确认。', result: '仓管可使用总码办理入库。', recovery: '标签不清或贴错时先停下核对，不用其他车辆的标签代替。', guideStep: 6 },
      ] },
      { title: '车辆入库后才形成库存', tag: '库存变化', messages: [
        { from: 'warehouse', to: 'system', kind: 'action', title: '选仓库、库位，扫总码入库', summary: '核对后点击“确认入库”', location: 'PDA → 入库', input: '选择实际仓库及其库位，扫描本车整车总码。', check: '仓库库位有效且匹配；车辆已组装、未入库、未出库且无阻断异常。', result: '成功后记录车辆所在仓库和库位，生成入库记录。', recovery: '已入库先核对原记录；隔离或状态不符先处理异常，不重复确认。', guideStep: 7 },
        { from: 'system', to: 'warehouse', kind: 'result', title: '返回入库成功与记录', summary: '在库增加，可用量仍须扣除占用', location: 'PDA 入库结果；Web → 库存管理 → 入库记录', input: '核对入库结果、车架号和库位；必要时回后台查询入库单。', check: 'SKU 可新增锁定量还要扣除隔离及已有承诺，并受车型余量限制。', result: '销售可查询该 SKU 的实际库存，进入锁定环节。', recovery: '网络中断、结果不明时先查车辆状态和入库记录，再决定下一步。', guideStep: 7 },
      ] },
    ],
  },
  {
    title: '锁定与转单', subtitle: '从客户需求到待出库订单', roles: ['sales', 'system', 'warehouse'],
    before: '客户已确定需求，SKU 已有可支撑本次锁定的合格库存。',
    deliver: '锁定单号、销售订单号、客户、固定 SKU 与待发数量。',
    decision: '只占数量；转单要保持原客户，且不能超过锁定剩余量或有效期。',
    groups: [
      { title: '核对库存并为客户留货', tag: '数量锁定', messages: [
        { from: 'sales', to: 'system', kind: 'action', title: '刷新 SKU 库存与可新增锁定量', summary: '查看在库、隔离、锁定及订单占用', location: 'Web → 业务办理工作台 → 库存与锁定', input: '按车型或 SKU 找到所需规格，读取“可新增锁定”。', check: '数量同时受 SKU 合格余量和车型余量约束；各 SKU 上限不能简单相加。', result: '得到本次能够承诺给客户的数量上限。', recovery: '计划数量、作业中数量不能当作库存；库存不符先核对入库与占用记录。', guideStep: 8 },
        { from: 'sales', to: 'system', kind: 'action', title: '提交客户、SKU、数量与有效期', summary: '代理商可选，数量在待锁定商品填写', location: 'Web → 库存与锁定 → 加入锁定 → 选择客户并锁定', input: '填写每个 SKU 数量，选择客户、可选代理商和截止时间。', check: '客户及代理商应有效、数量可用；多个 SKU 任一不足时整批不保存。', result: '按指定数量创建临时锁定，不指定具体车架。', recovery: '库存变化导致失败时刷新后调整数量；结果不明先查看锁定记录。', guideStep: 8 },
        { from: 'system', to: 'sales', kind: 'result', title: '返回锁定单与有效期', summary: 'A 锁 30 台，剩余仍可给其他客户', location: 'Web → 本次锁定已保存 / 库存锁定列表', input: '记录锁定单号、客户、SKU、数量与到期时间。', check: '例：100 台合格可用且无其他限制，A 锁 30、B 再锁 30，剩 40。', result: '临时锁定增加；未被占用的数量仍可销售。', recovery: '不要把“加入锁定”的本次选择去重理解成整个 SKU 被禁售。', guideStep: 8 },
      ] },
      { title: '有效锁定转订单', tag: '保留库存占用', messages: [
        { from: 'sales', to: 'system', kind: 'action', title: '打开锁定记录，填写转换数量', summary: '原客户、原 SKU，核对计划出库日', location: 'Web → 锁定转订单 → 转订单', input: '选择来源锁定，核对客户、SKU、可转数量及计划出库日。', check: '客户必须与 SKU 锁定一致；转换数量不能超过剩余量。', result: '提交后生成关联该锁定的销售订单。', recovery: '要给不同客户留货时应另行按剩余库存办理，不能直接改转单客户。', guideStep: 9 },
        { from: 'system', to: 'sales', kind: 'condition', title: '若过期 / 超量 / 库存不足：停止转单', summary: '处理原因后再提交，不会强行生成订单', location: 'Web → 锁定转订单 → 转单提示', input: '读取错误提示，核对截止时间、已转数量和库存。', check: '锁定须在有效期内；原承诺必须有合格库存支撑。', result: '本次转单被拒绝，不能拿未成功的订单安排出库。', recovery: '过期后重新核对库存并办理有效锁定；超量则调整数量；隔离问题先处理。', guideStep: 9 },
        { from: 'system', to: 'sales', kind: 'result', title: '返回销售订单并保留占用', summary: '转换部分成为订单占用，不重复扣库存', location: 'Web → 新订单办理', input: '核对订单号、来源锁定、固定 SKU、客户和数量。', check: '系统记录锁定已转数量，并建立对应的订单库存占用。', result: '进入订单核对与确认；不代表实物已经出库。', recovery: '未看到返回结果时按来源锁定查关联订单，避免重复转单。', guideStep: 9 },
      ] },
      { title: '确认订单并准备交接', tag: '待出库', messages: [
        { from: 'sales', to: 'system', kind: 'action', title: '核对后确认销售订单', summary: '点击“确认订单，继续出库”', location: 'Web → 订单与出库 → 打开订单办理', input: '核对客户、数量、占用、计划出库日，再确认销售订单。', check: '必须是可确认订单且账号有权限；业务占用异常需先解决。', result: '订单进入待出库；关联 PI 的还需处理 PI 发货条件。', recovery: '不能确认时根据订单状态和提示处理，不让仓管绕过订单发货。', guideStep: 10 },
        { from: 'sales', to: 'warehouse', kind: 'handoff', title: '交接订单号、客户与待发数量', summary: '提醒仓管：关联 PI 还须先完成放行', location: 'Web 订单办理 → 出库岗位', input: '交接客户、订单号、固定 SKU、数量和是否关联 PI。', check: '只有待出库或部分出库且仍有余量的订单可以继续办理。', result: '进入放行与出库阶段，按实际总码选车。', recovery: '客户或 SKU 不明确时先回订单核对，不按同车型随意替代配置。', guideStep: 10 },
      ] },
    ],
  },
  {
    title: '放行与出库', subtitle: '收款条件、授权放行与逐车出库', roles: ['sales', 'finance', 'system', 'warehouse'],
    before: '订单已确认或部分出库，仍有待发数量且库存占用有效。',
    deliver: '实际出库车辆、出库单、订单已发数量与剩余数量。',
    decision: '无 PI 跳过 PI 环节；有关联 PI 时须满足收款条件并由有权限人员放行。',
    groups: [
      { title: '是否关联 PI：有则先满足放行条件，无则跳至仓管选单', tag: '条件分支 · PI', messages: [
        { from: 'sales', to: 'system', kind: 'condition', title: '核对订单是否关联 PI', summary: '无 PI：直接进入下面的选单与扫码', location: 'Web → 订单办理 → 本单 PI 与发货放行', input: '查关联 PI，核对客户、商品明细与本订单。', check: '有关联 PI 时不能绕过其收款与放行要求；关联冲突需先处理。', result: '无 PI 则跳到仓管选单；有 PI 则继续确认、到账与放行。', recovery: '多个 PI 关联冲突或客户不一致时，交订单人员核对后再发货。', guideStep: 10 },
        { from: 'sales', to: 'finance', kind: 'handoff', title: '确认 PI 并交接收款要求', summary: '按 PI 约定的发货收款条件办理', location: 'Web → PI 与履约 / 本单 PI 与发货放行', input: '核对 PI 客户、SKU 明细、数量及发货收款条件，按页面完成确认。', check: '必须使用当前订单关联的 PI，不拿其他客户的收款或放行代替。', result: '财务可按该 PI 核对实际到账。', recovery: '明细或收款要求有误时先修正业务资料，不直接进入出库。', guideStep: 10 },
        { from: 'finance', to: 'system', kind: 'action', title: '按实际到账登记收款', summary: '到账达到发货要求后才能继续', location: 'Web → PI 与履约 → 到账登记', input: '核对真实收款，再由有权限的人员填写页面要求的到账信息。', check: '出库时到账金额须达到该 PI 的发货要求金额，不统一假设必须全款。', result: '记录收款；仅登记到账还不代表已获发货放行。', recovery: '到账不足时等待或核对业务收款条件，不能用虚假到账放行。', guideStep: 10 },
        { from: 'finance', to: 'system', kind: 'action', title: '授权人员执行发货放行', summary: '按岗位权限填写放行说明并确认', location: 'Web → PI 与履约 → 发货放行', input: '由具备放行权限的人员核对条件并填写放行说明。', check: '出库校验 PI 与放行状态均已放行，并满足发货收款要求。', result: '满足条件后可交接仓管出库。财务与放行人员可为不同人员。', recovery: '无权限交由授权人员处理；尚未放行时不能让 PDA 强行发货。', guideStep: 10 },
        { from: 'system', to: 'sales', kind: 'result', title: 'PI 已满足发货放行条件', summary: '仍需仓管扫描实际车辆并确认出库', location: 'Web → PI / 订单办理', input: '核对放行结果与本次待发数量。', check: '放行之后实际出库仍会重新校验客户、库存、SKU 和数量。', result: '可以把订单及发货要求交给仓管。', recovery: '后续状态变化导致出库拦截时，以当前校验结果为准重新核对。', guideStep: 10 },
      ] },
      { title: '按客户与订单选车，逐台扫描总码', tag: '循环 · 本批车辆', messages: [
        { from: 'sales', to: 'warehouse', kind: 'handoff', title: '交接本次可发订单与数量', summary: '无 PI 或已完成 PI 放行', location: '订单岗位 → PDA 出库岗位', input: '告知客户、订单号、固定 SKU 和本次计划发货数量。', check: '仓管需要实际核对车辆，订单确认和 PI 放行都不会自动出库。', result: '仓管按客户与订单建立本次扫描清单。', recovery: '发货数量临时调整时先核对订单余量，不能超过剩余量。', guideStep: 11 },
        { from: 'warehouse', to: 'system', kind: 'action', title: 'PDA 选择客户，再搜索订单', summary: '只选待出库 / 部分出库且仍有余量的单', location: 'PDA → 出库 → 按订单出库', input: '先选客户，再按订单号或车型查询并选择目标订单。', check: '客户与订单要匹配，订单状态允许出库且有待发数量。', result: '展示订单总量、已发和剩余数量。', recovery: '找不到订单时核对客户、确认状态、关键词和权限。', guideStep: 11 },
        { from: 'warehouse', to: 'system', kind: 'action', title: '逐台扫描整车总码', summary: '每台只扫一次，核对扫描清单', location: 'PDA → 出库 → 扫描整车总码', input: '扫描本次实际要发出的每台车，核对总码与实物一致。', check: '车辆需在库、无阻断异常、车型及固定 SKU 符合订单；PI SKU 也须匹配。', result: '形成待确认扫描清单；扫描通过还不等于已经出库。', recovery: '扫错先从清单移除；同车型不同 SKU 不能随意替代。', guideStep: 11 },
      ] },
      { title: '整批确认与结果核对', tag: '出库结果', messages: [
        { from: 'warehouse', to: 'system', kind: 'action', title: '核对数量，确认批量出库', summary: '提交前再对照本批实物和订单余量', location: 'PDA → 出库 → 确认批量出库', input: '核对客户、订单、扫描清单与本次数量后确认。', check: '系统重新校验整批重复码、客户、订单余量、库存占用、车辆状态与 PI 条件。', result: '全部校验通过后，记录出库车辆并更新库存及订单数量。', recovery: '业务校验失败时本次整批不生效；修正错误项后再确认。', guideStep: 11 },
        { from: 'system', to: 'warehouse', kind: 'condition', title: '若有拦截：整批失败，先处理原因', summary: '未入库 / 隔离 / SKU 不符 / 余量不足等', location: 'PDA → 出库反馈', input: '按提示定位问题车辆或订单，不要把失败的扫描清单当作已出库。', check: '任一车辆或订单校验失败会回滚本次整批出库。', result: '修正后重新核对清单；不应出现只发出一部分却提示整批失败的正常业务结果。', recovery: '网络超时与业务拒绝不同：结果不确定时先查订单和出库记录，防止重复提交。', guideStep: 11 },
        { from: 'system', to: 'warehouse', kind: 'result', title: '出库成功，返回业务结果', summary: '车辆变为已出库，已发数量同步增加', location: 'PDA 出库结果；Web → 订单办理', input: '核对成功反馈、车辆清单及订单已发数量。', check: '本次车辆、库存占用与订单数量一并更新。', result: '未发完为“部分出库”；全部发完为“已完成”。', recovery: '页面未返回但记录已成功时不要重复出库，按已有出库记录核对。', guideStep: 11 },
        { from: 'warehouse', to: 'sales', kind: 'handoff', title: '回交出库单与剩余待发数量', summary: '部分出库可下次继续；完成后核对归档', location: 'Web → 订单与出库 → 打开订单办理', input: '查看已出库车辆、总码、车架号、出库单及时间。', check: '按实际记录核对已发与剩余，不只看本次手写数量。', result: '订单、库存与实际车辆交接形成闭环。', recovery: '发现出库错误先核对实物和状态，再按授权撤销 / 纠正流程处理。', guideStep: 11 },
      ] },
    ],
  },
]
