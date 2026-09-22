import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
const base = process.env.BIKE_API_URL || 'http://127.0.0.1:18181';
const prefix = `AS${Date.now().toString(36).toUpperCase()}${randomBytes(2).toString('hex').toUpperCase()}`;
const evidence = { prefix, startedAt: new Date().toISOString(), checks: [], fixtures: {} };
const accounts = [], customRoles = [];
let admin;

async function req(path, body, { method = body === undefined ? 'GET' : 'POST', token = admin, code = 'SUCCESS' } = {}) {
  const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json();
  assert.equal(result.code, code, `${path}: ${response.status} ${result.code} ${result.message}`);
  if (code === 'SUCCESS') assert(response.ok);
  else assert(!response.ok, `${path}: refusal must use an error HTTP status`);
  return code === 'SUCCESS' ? result.data : result;
}
function pass(name) { evidence.checks.push({ name, passed: true }); console.log(`PASS ${name}`); }
async function role(suffix, permissionCodes) {
  const all = await req('/api/roles/permissions');
  const permissionIds = permissionCodes.map(code => { const permission = all.find(p => p.permissionCode === code); assert(permission, `missing permission ${code}`); return permission.id; });
  const created = await req('/api/roles', { roleCode: prefix + suffix, roleName: `售后验收${suffix}` }); customRoles.push(created.id);
  await req(`/api/roles/${created.id}/permissions`, { permissionIds }, { method: 'PUT' });
  return created.id;
}
async function account(suffix, roleIds, { agentId, workstationId } = {}) {
  const username = prefix + suffix, initial = randomBytes(20).toString('hex'), password = randomBytes(20).toString('hex');
  const created = await req('/api/users', { username, password: initial, displayName: `售后验收${suffix}`, userType: agentId ? 'AGENT' : workstationId ? 'PDA_OPERATOR' : 'WEB_OPERATOR', agentId, workstationId }); accounts.push(created.id);
  await req(`/api/users/${created.id}/roles`, { roleIds }, { method: 'PUT' });
  const first = await req('/api/auth/login', { username, password: initial }, { token: null }); assert.equal(first.mustChangePassword, true);
  await req('/api/auth/password', { currentPassword: initial, newPassword: password }, { method: 'PUT', token: first.accessToken });
  return { id: created.id, token: (await req('/api/auth/login', { username, password }, { token: null })).accessToken };
}
const draft = (suffix, extra = {}) => ({ frameSn: prefix + suffix, faultType: '电机', description: '验收：骑行时电机不启动', attemptedRepair: '已检查电源和连接线', warrantyStatus: 'PENDING', ...extra });
const casePath = id => `/api/after-sales/cases/${id}`;

try {
  const login = await req('/api/auth/login', { username: process.env.BIKE_TEST_USERNAME || 'admin', password: process.env.BIKE_TEST_PASSWORD }, { token: null });
  assert.equal(login.mustChangePassword, false); admin = login.accessToken;
  const master = (type, suffix) => req(`/api/master/${type}`, { code: prefix + suffix, name: `售后验收${suffix}` });
  const customer = await master('customers', 'C'), agentA = await master('agents', 'A'), agentB = await master('agents', 'B');
  const unrelatedModel = await master('models', 'M');
  const readRole = await role('READ', ['AFTER_SALES_READ', 'AFTER_SALES_SCOPE_ALL']);
  const writeRole = await role('WRITE', ['AFTER_SALES_READ', 'AFTER_SALES_WRITE', 'AFTER_SALES_SCOPE_ALL']);
  const reviewRole = await role('REVIEW', ['AFTER_SALES_READ', 'AFTER_SALES_REVIEW', 'AFTER_SALES_SCOPE_ALL']);
  const techRole = await role('TECH', ['AFTER_SALES_READ', 'AFTER_SALES_TECH', 'AFTER_SALES_SCOPE_ALL']);
  const shipRole = await role('SHIP', ['AFTER_SALES_READ', 'AFTER_SALES_LOGISTICS', 'AFTER_SALES_SCOPE_ALL']);
  const reader = await account('Reader', [readRole]);
  const writer = await account('Writer', [writeRole]);
  const reviewer = await account('Reviewer', [reviewRole]);
  const technician = await account('Tech', [techRole]);
  const warehouse = await account('Warehouse', [shipRole]);
  // Deliberately assign a technical permission to an external account to test the scope boundary.
  const externalA = await account('AgentA', [writeRole, techRole], { agentId: agentA.id });
  const externalB = await account('AgentB', [writeRole], { agentId: agentB.id });
  const workstation = await req('/api/workstations', { workstationCode: prefix + 'PDA', workstationName: '售后边界验收工位', workstationType: 'ASSEMBLY', enabled: true });
  const pda = await account('Pda', [writeRole, reviewRole, techRole], { workstationId: workstation.id });
  evidence.fixtures = { customerId: customer.id, agentAId: agentA.id, agentBId: agentB.id, userIds: accounts, roleIds: customRoles };

  const own = await req('/api/after-sales/cases', draft('FRAME'), { token: externalA.token });
  const id = own.id; evidence.fixtures.caseId = id; evidence.fixtures.caseNo = own.caseNo;
  assert.equal(own.agentId, agentA.id); assert.equal(own.status, 'DRAFT'); assert.equal(own.customerId, null);
  await req('/api/after-sales/cases', draft('FORGED', { customerId: customer.id }), { token: externalA.token, code: 'FORBIDDEN' });
  await req('/api/after-sales/cases', draft('OTHER', { agentId: agentB.id }), { token: externalA.token, code: 'FORBIDDEN' });
  await req('/api/after-sales/cases', draft('MODEL', { productModelId: unrelatedModel.id }), { token: externalA.token, code: 'MODEL_NOT_AUTHORIZED' });
  const options = await req('/api/after-sales/cases/options', undefined, { token: externalA.token });
  assert.deepEqual(options.customers, []); assert.deepEqual(options.agents, []);
  await req(casePath(id), undefined, { token: externalB.token, code: 'FORBIDDEN' });
  const otherList = await req(`/api/after-sales/cases?keyword=${encodeURIComponent(own.caseNo)}`, undefined, { token: externalB.token }); assert.equal(otherList.total, 0);
  for (const path of [casePath(id), `${casePath(id)}/messages`, `${casePath(id)}/shipments`, `${casePath(id)}/history`, '/api/after-sales/cases/options']) {
    await req(path, undefined, { token: pda.token, code: 'PERMISSION_DENIED' });
  }
  pass('代理商案例自动绑定本人代理商；伪造客户或代理商归属被拒绝；跨代理商列表和详情隔离；PDA不可访问');

  await req(`${casePath(id)}/messages`, { body: '只读不得写入', visibility: 'CUSTOMER' }, { token: reader.token, code: 'PERMISSION_DENIED' });
  await req(`${casePath(id)}/review`, { action: 'APPROVE' }, { token: reviewer.token, code: 'INVALID_STATUS_TRANSITION' });
  await req(`${casePath(id)}/submit`, {}, { token: externalA.token });
  await req(`${casePath(id)}/solution`, { solution: '提前方案' }, { token: technician.token, code: 'INVALID_STATUS_TRANSITION' });
  await req(`${casePath(id)}/review`, { action: 'START' }, { token: reviewer.token });
  await req(`${casePath(id)}/review`, { action: 'APPROVE' }, { token: reviewer.token, code: 'AFTER_SALES_SOLUTION_REQUIRED' });
  await req(`${casePath(id)}/review`, { action: 'APPROVE' }, { token: technician.token, code: 'PERMISSION_DENIED' });
  const solutionBody = { technicalJudgment: '已复核故障描述', solution: '更换电机后测试', internalNote: '仅供内部：责任分析记录', faultCause: '轴承故障', customerActions: '安装后测试', repairNotes: '记录诊断结果', warrantyStatus: 'IN_WARRANTY', currency: 'CNY', parts: [{ partCode: 'MOTOR-TEST', partName: '替换电机', quantity: 1, unitCharge: '0.00', returnRequired: true }] };
  const firstPlan = (await req(`${casePath(id)}/solution`, solutionBody, { token: technician.token })).solutionVersion;
  await req(`${casePath(id)}/solution`, { solution: '外部技术越权' }, { token: externalA.token, code: 'FORBIDDEN' });
  assert.equal((await req(casePath(id), undefined, { token: technician.token })).status, 'UNDER_REVIEW');
  pass('只读账号不能留言；草稿不能直接批准；审核前不能保存方案；审批需要维修方案，技术账号不能审批');

  const privateMessage = await req(`${casePath(id)}/messages`, { body: '仅供内部：诊断沟通', visibility: 'INTERNAL' }, { token: technician.token });
  const publicMessage = await req(`${casePath(id)}/messages`, { body: '客户补充：仍然没有动力', visibility: 'CUSTOMER' }, { token: externalA.token });
  assert.equal(publicMessage.body, '客户补充：仍然没有动力'); assert.notEqual(privateMessage.id, publicMessage.id);
  await req(`${casePath(id)}/messages`, { body: '不合法范围', visibility: 'SECRET' }, { token: technician.token, code: 'INVALID_PARAMETER' });
  await req(`${casePath(id)}/messages`, { body: '越权内部留言', visibility: 'INTERNAL' }, { token: externalA.token, code: 'PERMISSION_DENIED' });
  for (const token of [externalA.token, reader.token]) {
    const current = await req(casePath(id), undefined, { token }); assert.equal(current.internalNote, null);
    assert(current.messages.every(message => message.visibility === 'CUSTOMER'));
    assert(current.history.every(event => event.action !== 'INTERNAL_MESSAGE_ADDED'));
    assert(!JSON.stringify(current).includes('仅供内部'));
  }
  await req(casePath(id), undefined, { token: warehouse.token, code: 'FORBIDDEN' });
  const notifications = await req(casePath(id) + '/notifications', undefined, { token: reviewer.token });
  assert.equal(notifications.sendingEnabled, false);
  assert(notifications.notifications.records.some(n => n.eventType === 'CASE_SUBMITTED' && n.audience === 'STAFF' && n.status === 'PENDING'));
  assert(notifications.notifications.records.every(n => !('body' in n) && !('messageKey' in n) && !('claimToken' in n)));
  assert(!notifications.notifications.records.some(n => n.eventType === 'INTERNAL_MESSAGE_ADDED'));
  await req(casePath(id) + '/notifications', undefined, { token: externalA.token, code: 'FORBIDDEN' });
  await req(casePath(id) + '/notifications', undefined, { token: warehouse.token, code: 'PERMISSION_DENIED' });
  const pending = notifications.notifications.records[0];
  await req(casePath(id) + '/notifications/' + pending.id + '/retry', { revision: pending.revision, reason: '不得重复待发队列' }, { token: reviewer.token, code: 'AFTER_SALES_NOTIFICATION_CONFLICT' });
  pass('业务事件自动产生Email队列；默认关闭外发，内部留言不入客户通知，队列按岗位隔离且不暴露发送凭据');
  const internal = await req(casePath(id), undefined, { token: technician.token });
  assert.equal(internal.internalNote, '仅供内部：责任分析记录'); assert(internal.messages.some(message => message.id === privateMessage.id));
  const ownList = await req(`/api/after-sales/cases?keyword=${encodeURIComponent(own.caseNo)}`, undefined, { token: externalA.token }); assert.equal(ownList.records[0].internalNote, null);
  pass('客户/只读/仓库详情和列表不泄露内部备注；内部留言及对应历史不可见；自定义技术角色可读取内部信息');

  await req(`${casePath(id)}/review`, { action: 'APPROVE', solutionVersionId: firstPlan.id }, { token: reviewer.token, code: 'INVALID_STATUS_TRANSITION' });
  await req(`${casePath(id)}/solution/confirm`, { versionId: firstPlan.id }, { token: reviewer.token, code: 'PERMISSION_DENIED' });
  await req(`${casePath(id)}/solution/confirm`, { versionId: firstPlan.id }, { token: externalA.token, code: 'FORBIDDEN' });
  await req(`${casePath(id)}/solution/confirm`, { versionId: firstPlan.id }, { token: technician.token });
  await req(`${casePath(id)}/review`, { action: 'APPROVE', solutionVersionId: firstPlan.id }, { token: reviewer.token });
  await req(`${casePath(id)}/solution`, { solution: '审批后擅改' }, { token: technician.token, code: 'INVALID_STATUS_TRANSITION' });
  await req(`${casePath(id)}/review`, { action: 'REVISE', reason: '补充维修说明后重新确认', solutionVersionId: firstPlan.id }, { token: reviewer.token });
  const revisedPlan = (await req(`${casePath(id)}/solution`, { ...solutionBody, previousVersionId: firstPlan.id, repairNotes: '补充诊断步骤后重新审批' }, { token: technician.token })).solutionVersion;
  await req(`${casePath(id)}/solution`, { ...solutionBody, previousVersionId: firstPlan.id }, { token: technician.token, code: 'AFTER_SALES_SOLUTION_CONFLICT' });
  await req(`${casePath(id)}/solution/confirm`, { versionId: revisedPlan.id }, { token: technician.token });
  await req(`${casePath(id)}/review`, { action: 'APPROVE', solutionVersionId: firstPlan.id }, { token: reviewer.token, code: 'AFTER_SALES_SOLUTION_CONFLICT' });
  await req(`${casePath(id)}/review`, { action: 'APPROVE', solutionVersionId: revisedPlan.id }, { token: reviewer.token });
  const versions = await req(`${casePath(id)}/solution/versions`, undefined, { token: externalA.token });
  assert.deepEqual(versions.map(v => v.revision), [2, 1]); assert(versions.every(v => v.document.internalNote === null && v.approvedAt && v.confirmedAt));
  await req(`${casePath(id)}/solution/versions`, undefined, { token: externalB.token, code: 'FORBIDDEN' });
  pass('技术确认和负责人审批绑定版本；旧版不覆盖，修订重新审核，过期保存/审批拒绝，历史方案按公司隔离且隐藏内部备注');
  const warehouseView = await req(casePath(id), undefined, { token: warehouse.token });
  for (const field of ['frameSn','description','contactEmail','technicalJudgment','solution','internalNote','warrantyStatus']) assert.equal(warehouseView[field], null);
  assert.equal(warehouseView.solutionVersion.parts[0].unitCharge, null);
  assert.equal(warehouseView.solutionVersion.document.chargeReason, null); assert.deepEqual(warehouseView.messages, []);
  for (const resource of ['messages','attachments','solution/versions']) await req(casePath(id) + '/' + resource, undefined, { token: warehouse.token, code: 'PERMISSION_DENIED' });
  const warehouseSearch = await req('/api/after-sales/cases?keyword=' + encodeURIComponent(own.frameSn), undefined, { token: warehouse.token });
  assert(!warehouseSearch.records.some(row => row.id === id));
  pass('仓库详情隐藏故障、费用和沟通，历史版本/附件拒绝访问，车架号关键词不能旁路检索');
  await req(`${casePath(id)}/process`, {}, { token: technician.token });
  const shipmentBody = { partName: '替换电机', quantity: 1, oldPartReturnRequired: true, solutionItemId: revisedPlan.parts[0].id };
  await req(`${casePath(id)}/shipments`, { ...shipmentBody, solutionItemId: firstPlan.parts[0].id }, { token: warehouse.token, code: 'AFTER_SALES_APPROVED_PART_REQUIRED' });
  await req(`${casePath(id)}/shipments`, { ...shipmentBody, oldPartReturnRequired: false }, { token: warehouse.token, code: 'INVALID_PARAMETER' });
  const concurrent = await Promise.all([1,2].map(async () => {
    const response = await fetch(base + `${casePath(id)}/shipments`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${warehouse.token}` }, body: JSON.stringify(shipmentBody) });
    const result = await response.json(); assert.equal(response.ok, result.code === 'SUCCESS'); return result;
  }));
  assert.deepEqual(concurrent.map(r => r.code).sort(), ['AFTER_SALES_APPROVED_QUANTITY_EXCEEDED', 'SUCCESS']);
  let planned = concurrent.find(r => r.code === 'SUCCESS').data;
  assert.equal(planned.solutionItemId, revisedPlan.parts[0].id);
  pass('仓库只能选择本版批准配件，不能变更退回要求；并发补发只成功一次，累计数量不超批准数量');
  assert.equal(planned.status, 'PLANNED'); assert.equal(planned.shippedAt, null);
  await req(`${casePath(id)}/resolve`, {}, { token: technician.token, code: 'INVALID_STATUS_TRANSITION' });
  const cancelledId = planned.id;
  const cancelled = await req(casePath(id) + '/shipments/' + cancelledId + '/logistics', { revision: planned.revision, action: 'CANCEL', note: '重新安排仓库发运' }, { token: warehouse.token });
  assert.equal(cancelled.status, 'CANCELLED');
  assert.equal((await req(casePath(id) + '/returns', undefined, { token: externalA.token })).find(r => r.shipmentId === cancelledId).status, 'CANCELLED');
  planned = await req(casePath(id) + '/shipments', shipmentBody, { token: warehouse.token });
  const shipping = { warehouseName: '隔离验收仓库', recipient: '验收收货人', phone: '000-0000', countryCode: 'GB', region: 'Test', city: 'Test City', addressLine: '仅供隔离验收的收货地址', postalCode: '00000', expectedArrivalDate: '2030-12-31' };
  const logisticsPath = casePath(id) + '/shipments/' + planned.id + '/logistics';
  await req(casePath(id) + '/shipments/' + planned.id + '/dispatch', { carrier: 'DHL', trackingNo: prefix + '-TRACK', revision: planned.revision }, { token: warehouse.token, code: 'INVALID_PARAMETER' });
  const prepared = await req(logisticsPath, { revision: planned.revision, action: 'SAVE', shipping }, { token: warehouse.token });
  await req(logisticsPath, { revision: planned.revision, action: 'SAVE', shipping }, { token: warehouse.token, code: 'AFTER_SALES_SHIPMENT_CONFLICT' });
  await req(casePath(id) + '/shipments/' + planned.id + '/dispatch', { carrier: 'DHL', trackingNo: prefix + '-TRACK', shipping, revision: planned.revision }, { token: warehouse.token, code: 'AFTER_SALES_SHIPMENT_CONFLICT' });
  planned = prepared;
  pass('取消未发计划保留历史并释放批准数量和未启动旧件；重新计划、保存收货快照、缺资料/过期发货拒绝');
  await req(`${casePath(id)}/shipments/${planned.id}/dispatch`, { carrier: 'DHL', trackingNo: prefix + '-TRACK' }, { token: reader.token, code: 'PERMISSION_DENIED' });
  const sent = await req(`${casePath(id)}/shipments/${planned.id}/dispatch`, { carrier: 'DHL', trackingNo: prefix + '-TRACK', revision: planned.revision }, { token: warehouse.token });
  assert.equal(sent.id, planned.id); assert.equal(sent.status, 'SHIPPED'); assert(sent.shippedAt);
  assert.equal((await req(casePath(id), undefined, { token: externalA.token })).status, 'SHIPPED');
  assert.equal(sent.recipient, shipping.recipient); assert.equal(sent.warehouseName, shipping.warehouseName); assert.equal(sent.logisticsStatus, 'IN_TRANSIT');
  await req(logisticsPath, { revision: sent.revision, action: 'TRACK', logisticsStatus: 'DELIVERED', note: '越权' }, { token: externalA.token, code: 'FORBIDDEN' });
  const corrected = await req(logisticsPath, { revision: sent.revision, action: 'CORRECT', carrier: 'DHL', trackingNo: prefix + '-CORRECTED', expectedArrivalDate: '2030-12-31', note: '承运商提供了正确运单号' }, { token: warehouse.token });
  assert.equal(corrected.shippedAt, sent.shippedAt); assert.equal(corrected.addressLine, shipping.addressLine);
  const delivered = await req(logisticsPath, { revision: corrected.revision, action: 'TRACK', logisticsStatus: 'DELIVERED', note: '客户已签收' }, { token: warehouse.token });
  assert(delivered.deliveredAt);
  await req(logisticsPath, { revision: delivered.revision, action: 'CANCEL', note: '尝试取消已发' }, { token: warehouse.token, code: 'INVALID_STATUS_TRANSITION' });
  pass('仓库按保存的地址发货、保留发货时间更正运单、登记签收；客户不能写物流，已发货不能取消');
  await req(`${casePath(id)}/resolve`, {}, { token: technician.token });
  await req(`${casePath(id)}/close`, {}, { token: externalA.token, code: 'AFTER_SALES_RETURN_PENDING' });
  const returnPath = `${casePath(id)}/returns/${planned.id}`;
  assert.equal((await req(`${casePath(id)}/returns`, undefined, { token: externalA.token })).find(r => r.shipmentId === planned.id).status, 'REQUIRED');
  await req(`${casePath(id)}/returns`, undefined, { token: externalB.token, code: 'FORBIDDEN' });
  await req(returnPath, { revision: 1, action: 'REQUEST', warehouseName: '验收退回仓库', recipient: '验收收件员', phone: '000-0000', addressLine: '仅供隔离测试的退回地址', postalCode: '000000' }, { token: warehouse.token });
  await req(returnPath, { revision: 2, action: 'SEND', carrier: 'DHL', trackingNo: prefix + '-RETURN' }, { token: externalA.token });
  await req(returnPath, { revision: 2, action: 'SEND', carrier: 'DHL', trackingNo: prefix + '-RETURN' }, { token: externalA.token, code: 'AFTER_SALES_RETURN_CONFLICT' });
  await req(returnPath, { revision: 3, action: 'RECEIVE' }, { token: externalA.token, code: 'FORBIDDEN' });
  const received = await req(returnPath, { revision: 3, action: 'RECEIVE' }, { token: warehouse.token }); assert(received.receivedAt);
  await req(returnPath, { revision: 4, action: 'INSPECT', inspectionResult: '轴承磨损，检测结果已确认' }, { token: technician.token });
  await req(returnPath, { revision: 5, action: 'SCRAP', reason: '检测后确认不可修复' }, { token: reviewer.token });
  const returnFinished = await req(returnPath, { revision: 6, action: 'COMPLETE', reason: '旧件已按审批完成处理' }, { token: reviewer.token });
  assert.equal(returnFinished.status, 'COMPLETED'); assert(returnFinished.completedAt);
  pass('旧件退回地址→客户寄回→仓库收件→技术检测→报废→完成；跨公司、越权、重复版本和未完成归档门禁通过');
  const closed = await req(`${casePath(id)}/close`, {}, { token: externalA.token });
  assert.equal(closed.status, 'CLOSED'); assert(closed.closedAt);
  evidence.fixtures.shipmentId = planned.id;
  pass('维修方案审批后受保护；正式处理→计划配件→登记承运商/运单/发货时间→解决→客户归档闭环完成');

  for (const [action, body, token] of [
    ['submit', {}, externalA.token], ['review', { action: 'START' }, reviewer.token],
    ['solution', { solution: '归档后改变' }, technician.token], ['messages', { body: '归档后留言', visibility: 'CUSTOMER' }, externalA.token],
    ['shipments', { partName: '额外配件', quantity: 1 }, warehouse.token], ['resolve', {}, technician.token], ['close', {}, externalA.token],
  ]) await req(`${casePath(id)}/${action}`, body, { token, code: 'INVALID_STATUS_TRANSITION' });
  await req(casePath(id), draft('MODIFY', { agentId: agentA.id }), { method: 'PUT', token: externalA.token, code: 'INVALID_STATUS_TRANSITION' });
  const history = await req(`${casePath(id)}/history`, undefined, { token: reviewer.token });
  for (const action of ['CASE_CREATED', 'CASE_SUBMITTED', 'REVIEW_START', 'SOLUTION_SAVED', 'REVIEW_APPROVE', 'PROCESSING_STARTED', 'SHIPMENT_PLANNED', 'SHIPMENT_DISPATCHED', 'CASE_RESOLVED', 'CASE_CLOSED']) assert(history.some(event => event.action === action));
  assert(history.every(event => event.operatorName && event.operationTime && !('beforeData' in event) && !('afterData' in event)));
  evidence.fixtures.historyEventCount = history.length;
  pass('归档终态禁止继续修改、审批、发货和留言；全部关键操作有操作者/时间历史且不返回内部审计快照');

  const supplement = await req('/api/after-sales/cases', draft('SUPPLEMENT', { customerId: customer.id }), { token: writer.token });
  await req(`${casePath(supplement.id)}/submit`, {}, { token: writer.token });
  await req(`${casePath(supplement.id)}/review`, { action: 'REQUEST_INFO', reason: '' }, { token: technician.token, code: 'INVALID_PARAMETER' });
  await req(`${casePath(supplement.id)}/review`, { action: 'REQUEST_INFO', reason: '请提供故障代码' }, { token: technician.token });
  await req(casePath(supplement.id), draft('SUPPLEMENT', { customerId: customer.id, errorCode: 'E01' }), { method: 'PUT', token: writer.token });
  await req(`${casePath(supplement.id)}/submit`, {}, { token: writer.token });
  await req(`${casePath(supplement.id)}/review`, { action: 'REJECT', reason: '测试用拒绝原因' }, { token: reviewer.token });
  await req(`${casePath(supplement.id)}/review`, { action: 'START' }, { token: reviewer.token, code: 'INVALID_STATUS_TRANSITION' });
  pass('技术要求补充资料必须有原因；客户补充后可重新提交；拒绝后不可重开');
} catch (error) {
  evidence.error = error.message; process.exitCode = 1; console.error(error.message);
} finally {
  evidence.cleanupFailures = [];
  for (const id of accounts) try { await req(`/api/users/${id}/status?enabled=false`, {}, { method: 'PUT' }); }
  catch (error) { evidence.cleanupFailures.push({ userId: id, error: error.message }); process.exitCode = 1; }
  for (const id of customRoles) try { await req(`/api/roles/${id}/status?enabled=false`, {}, { method: 'PUT' }); }
  catch (error) { evidence.cleanupFailures.push({ roleId: id, error: error.message }); process.exitCode = 1; }
  evidence.finishedAt = new Date().toISOString();
  if (process.env.BIKE_ACCEPTANCE_OUTPUT) await writeFile(process.env.BIKE_ACCEPTANCE_OUTPUT, JSON.stringify(evidence, null, 2));
}
