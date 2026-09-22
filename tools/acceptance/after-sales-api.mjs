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
  await req(`${casePath(id)}/solution`, { technicalJudgment: '已复核故障描述', solution: '更换电机后测试', internalNote: '仅供内部：责任分析记录' }, { token: technician.token });
  await req(`${casePath(id)}/solution`, { solution: '外部技术越权' }, { token: externalA.token, code: 'FORBIDDEN' });
  assert.equal((await req(casePath(id), undefined, { token: technician.token })).status, 'UNDER_REVIEW');
  pass('只读账号不能留言；草稿不能直接批准；审核前不能保存方案；审批需要维修方案，技术账号不能审批');

  const privateMessage = await req(`${casePath(id)}/messages`, { body: '仅供内部：诊断沟通', visibility: 'INTERNAL' }, { token: technician.token });
  const publicMessage = await req(`${casePath(id)}/messages`, { body: '客户补充：仍然没有动力', visibility: 'CUSTOMER' }, { token: externalA.token });
  assert.equal(publicMessage.body, '客户补充：仍然没有动力'); assert.notEqual(privateMessage.id, publicMessage.id);
  await req(`${casePath(id)}/messages`, { body: '不合法范围', visibility: 'SECRET' }, { token: technician.token, code: 'INVALID_PARAMETER' });
  await req(`${casePath(id)}/messages`, { body: '越权内部留言', visibility: 'INTERNAL' }, { token: externalA.token, code: 'PERMISSION_DENIED' });
  for (const token of [externalA.token, reader.token, warehouse.token]) {
    const current = await req(casePath(id), undefined, { token }); assert.equal(current.internalNote, null);
    assert(current.messages.every(message => message.visibility === 'CUSTOMER'));
    assert(current.history.every(event => event.action !== 'INTERNAL_MESSAGE_ADDED'));
    assert(!JSON.stringify(current).includes('仅供内部'));
  }
  const internal = await req(casePath(id), undefined, { token: technician.token });
  assert.equal(internal.internalNote, '仅供内部：责任分析记录'); assert(internal.messages.some(message => message.id === privateMessage.id));
  const ownList = await req(`/api/after-sales/cases?keyword=${encodeURIComponent(own.caseNo)}`, undefined, { token: externalA.token }); assert.equal(ownList.records[0].internalNote, null);
  pass('客户/只读/仓库详情和列表不泄露内部备注；内部留言及对应历史不可见；自定义技术角色可读取内部信息');

  await req(`${casePath(id)}/review`, { action: 'APPROVE' }, { token: reviewer.token });
  await req(`${casePath(id)}/solution`, { solution: '审批后擅改' }, { token: technician.token, code: 'INVALID_STATUS_TRANSITION' });
  await req(`${casePath(id)}/process`, {}, { token: technician.token });
  const planned = await req(`${casePath(id)}/shipments`, { partName: '替换电机', quantity: 1, oldPartReturnRequired: true }, { token: warehouse.token });
  assert.equal(planned.status, 'PLANNED'); assert.equal(planned.shippedAt, null);
  await req(`${casePath(id)}/resolve`, {}, { token: technician.token, code: 'INVALID_STATUS_TRANSITION' });
  await req(`${casePath(id)}/shipments/${planned.id}/dispatch`, { carrier: 'DHL', trackingNo: prefix + '-TRACK' }, { token: reader.token, code: 'PERMISSION_DENIED' });
  const sent = await req(`${casePath(id)}/shipments/${planned.id}/dispatch`, { carrier: 'DHL', trackingNo: prefix + '-TRACK' }, { token: warehouse.token });
  assert.equal(sent.id, planned.id); assert.equal(sent.status, 'SHIPPED'); assert(sent.shippedAt);
  assert.equal((await req(casePath(id), undefined, { token: externalA.token })).status, 'SHIPPED');
  await req(`${casePath(id)}/resolve`, {}, { token: technician.token });
  await req(`${casePath(id)}/close`, {}, { token: externalA.token, code: 'AFTER_SALES_RETURN_PENDING' });
  const returnPath = `${casePath(id)}/returns/${planned.id}`;
  assert.equal((await req(`${casePath(id)}/returns`, undefined, { token: externalA.token }))[0].status, 'REQUIRED');
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
