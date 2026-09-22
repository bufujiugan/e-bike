import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
const base = process.env.BIKE_API_URL || 'http://127.0.0.1:18181';
const prefix = `ASC${Date.now().toString(36).toUpperCase()}${randomBytes(2).toString('hex')}`;
const evidence = { prefix, startedAt: new Date().toISOString(), checks: [], fixtures: {} };
const accounts = [], customRoles = [];
let admin;

async function req(path, body, { method = body === undefined ? 'GET' : 'POST', token = admin, code = 'SUCCESS' } = {}) {
  const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json();
  assert.equal(result.code, code, `${path}: ${response.status} ${result.code} ${result.message}`);
  assert.equal(response.ok, code === 'SUCCESS');
  return code === 'SUCCESS' ? result.data : result;
}
async function role(suffix, permissions) {
  const all = await req('/api/roles/permissions');
  const permissionIds = permissions.map(code => { const permission = all.find(p => p.permissionCode === code); assert(permission, `missing permission ${code}`); return permission.id; });
  const created = await req('/api/roles', { roleCode: prefix + suffix, roleName: `售后公司验收${suffix}` }); customRoles.push(created.id);
  await req(`/api/roles/${created.id}/permissions`, { permissionIds }, { method: 'PUT' });
  return created.id;
}
async function account(suffix, roleIds, agentId) {
  const username = prefix + suffix, initial = randomBytes(20).toString('hex'), password = randomBytes(20).toString('hex');
  const created = await req('/api/users', { username, password: initial, displayName: `售后公司验收${suffix}`, userType: agentId ? 'AGENT' : 'WEB_OPERATOR', agentId }); accounts.push(created.id);
  await req(`/api/users/${created.id}/roles`, { roleIds }, { method: 'PUT' });
  const first = await req('/api/auth/login', { username, password: initial }, { token: null }); assert.equal(first.mustChangePassword, true);
  await req('/api/auth/password', { currentPassword: initial, newPassword: password }, { method: 'PUT', token: first.accessToken });
  return { id: created.id, token: (await req('/api/auth/login', { username, password }, { token: null })).accessToken };
}
function pass(name) { evidence.checks.push({ name, passed: true }); console.log(`PASS ${name}`); }
const company = id => `/api/after-sales/companies/${id}`;
const casePath = id => `/api/after-sales/cases/${id}`;
const contact = name => ({ contactName: name, contactPhone: '+49 1234567', contactEmail: 'acceptance@example.com', countryCode: 'de', region: 'Bavaria', city: 'Munich', addressLine: 'Test Street 12', postalCode: '80000' });
const draft = (suffix, extra = {}) => ({ frameSn: prefix + suffix, faultType: '电机', description: '公司资料与销售范围验收', warrantyStatus: 'PENDING', ...extra });
async function uploadText(id, token, visibility = 'CUSTOMER') {
  const form = new FormData(); form.append('files', new Blob(['diagnostic for company scope'], { type: 'text/plain' }), `${visibility}.log`);
  const response = await fetch(`${base}${casePath(id)}/attachments?visibility=${visibility}&category=DIAGNOSTIC`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  const result = await response.json(); assert.equal(result.code, 'SUCCESS', result.message); assert(response.ok); return result.data[0];
}
async function download(id, attachmentId, token, code = 'SUCCESS') {
  const response = await fetch(`${base}${casePath(id)}/attachments/${attachmentId}/download`, { headers: { Authorization: `Bearer ${token}` } });
  if (code === 'SUCCESS') { assert(response.ok); assert.equal(await response.text(), 'diagnostic for company scope'); }
  else { assert(!response.ok); assert.equal((await response.json()).code, code); }
}

try {
  const login = await req('/api/auth/login', { username: process.env.BIKE_TEST_USERNAME || 'admin', password: process.env.BIKE_TEST_PASSWORD }, { token: null }); admin = login.accessToken;
  assert.equal(login.mustChangePassword, false);
  const agentA = await req('/api/master/agents', { code: prefix + 'A', name: '公司验收A' });
  const agentB = await req('/api/master/agents', { code: prefix + 'B', name: '公司验收B' });
  const readRole = await role('READ', ['AFTER_SALES_READ']);
  const writeRole = await role('WRITE', ['AFTER_SALES_READ', 'AFTER_SALES_WRITE']);
  const managerRole = await role('MANAGE', ['AFTER_SALES_READ', 'AFTER_SALES_COMPANY_MANAGE', 'AFTER_SALES_SCOPE_ALL']);
  const assignmentRole = await role('ASSIGN', ['AFTER_SALES_READ', 'AFTER_SALES_ASSIGN', 'AFTER_SALES_SCOPE_ALL']);
  const techRole = await role('TECH', ['AFTER_SALES_READ', 'AFTER_SALES_TECH', 'AFTER_SALES_SCOPE_ALL']);
  const localTechRole = await role('LOCALTECH', ['AFTER_SALES_READ', 'AFTER_SALES_TECH']);
  const own = await account('Own', [writeRole, managerRole, assignmentRole, techRole], agentA.id);
  const colleague = await account('Colleague', [writeRole], agentA.id);
  const other = await account('Other', [writeRole], agentB.id);
  const sales = await account('Sales', [readRole]);
  const manager = await account('Manager', [managerRole]);
  const dispatcher = await account('Dispatcher', [assignmentRole]);
  const tech = await account('Technician', [techRole]);
  const localTech = await account('LocalTechnician', [localTechRole]);
  const disabledTech = await account('Disabled', [techRole]);
  await req(`/api/users/${disabledTech.id}/status?enabled=false`, {}, { method: 'PUT' });
  evidence.fixtures = { agentAId: agentA.id, agentBId: agentB.id, userIds: accounts, roleIds: customRoles };

  assert.equal((await req(company(agentA.id), undefined, { token: own.token })).source, 'MASTER');
  await req(company(agentA.id), contact('Original contact'), { method: 'PUT', token: manager.token });
  const original = await req('/api/after-sales/cases', draft('ORIGINAL', { endCustomerPurchaseDate: '2025-05-16' }), { token: own.token });
  const id = original.id; evidence.fixtures.caseId = id; evidence.fixtures.caseNo = original.caseNo;
  assert.equal(original.contactName, 'Original contact'); assert.equal(original.snapshotSource, 'PROFILE'); assert.equal(original.countryCode, 'DE'); assert.equal(original.endCustomerPurchaseDate, '2025-05-16'); assert.equal(original.productModelId, null);
  await req(company(agentA.id), contact('Updated company contact'), { method: 'PUT', token: manager.token });
  assert.equal((await req(casePath(id), undefined, { token: colleague.token })).contactName, 'Original contact');
  const newer = await req('/api/after-sales/cases', draft('NEWER'), { token: own.token }); assert.equal(newer.contactName, 'Updated company contact');
  const corrected = await req(casePath(newer.id), draft('NEWER', { contact: contact('Case-specific contact'), endCustomerPurchaseDate: '2025-05-17' }), { method: 'PUT', token: own.token });
  assert.equal(corrected.contactName, 'Case-specific contact'); assert.equal(corrected.snapshotSource, 'MANUAL');
  assert.equal((await req(company(agentA.id), undefined, { token: own.token })).contactName, 'Updated company contact');
  pass('公司资料自动带出，历史案例快照不被公司更新覆盖；同公司第二员工可查看；无车型可报故障，独立最终客户购买日期可保存');

  assert.equal((await req('/api/after-sales/companies', undefined, { token: own.token })).length, 1);
  await req(company(agentB.id), undefined, { token: own.token, code: 'FORBIDDEN' });
  await req(casePath(id), undefined, { token: other.token, code: 'FORBIDDEN' });
  await req(company(agentA.id), contact('Blocked'), { method: 'PUT', token: own.token, code: 'FORBIDDEN' });
  await req(`${company(agentA.id)}/salespeople`, { userIds: [sales.id] }, { method: 'PUT', token: own.token, code: 'FORBIDDEN' });
  await req(`${casePath(id)}/assignee`, { assigneeId: tech.id }, { method: 'PUT', token: own.token, code: 'FORBIDDEN' });
  await req(`${company(agentA.id)}/history`, undefined, { token: own.token, code: 'FORBIDDEN' });
  const members = await req(`${company(agentA.id)}/members`, undefined, { token: manager.token }); assert.deepEqual(new Set(members.map(m => m.id)), new Set([own.id, colleague.id]));
  assert(members.every(m => !('passwordHash' in m) && !('permissions' in m)));
  pass('外部账号误授全部范围/公司管理/派单/技术权限仍不能跨公司或执行内部管理；公司成员仅返回必要字段');

  const publicFile = await uploadText(id, own.token), privateFile = await uploadText(id, admin, 'INTERNAL');
  const otherCase = await req('/api/after-sales/cases', draft('OTHER'), { token: other.token });
  assert.equal((await req('/api/after-sales/cases', undefined, { token: sales.token })).total, 0);
  assert.equal((await req('/api/after-sales/cases/dashboard', undefined, { token: sales.token })).total, 0);
  await req(casePath(id), undefined, { token: sales.token, code: 'FORBIDDEN' });
  await download(id, publicFile.id, sales.token, 'FORBIDDEN');
  await req(`${company(agentA.id)}/salespeople`, { userIds: [sales.id] }, { method: 'PUT', token: manager.token });
  const scoped = await req('/api/after-sales/cases', undefined, { token: sales.token }); assert.equal(scoped.total, 2); assert(scoped.records.every(c => c.agentId === agentA.id));
  assert.equal((await req('/api/after-sales/cases/dashboard', undefined, { token: sales.token })).total, 2);
  assert.equal((await req('/api/after-sales/cases/dashboard', undefined, { token: own.token })).total, 2);
  assert.equal((await req('/api/after-sales/cases/dashboard?keyword=' + otherCase.caseNo, undefined, { token: own.token })).total, 0);
  const advanced = new URLSearchParams({ keyword: prefix, companyName: '公司验收A', contactName: 'Original contact', countryCode: 'de', faultType: '电机', frameSn: prefix + 'ORIGINAL', status: 'DRAFT' });
  const selected = await req('/api/after-sales/cases?' + advanced, undefined, { token: sales.token });
  assert.equal(selected.total, 1); assert.deepEqual(selected.records.map(c => c.id), [id]);
  assert.equal((await req('/api/after-sales/cases/dashboard?' + advanced, undefined, { token: sales.token })).total, 1);
  await req('/api/after-sales/cases?createdFrom=2026-09-02&createdTo=2026-09-01', undefined, { token: sales.token, code: 'INVALID_PARAMETER' });
  await req('/api/after-sales/cases/dashboard?queue=UNKNOWN', undefined, { token: own.token, code: 'INVALID_PARAMETER' });
  pass('看板/count/列表共用公司与销售范围；组合联系人快照/国家/故障/车架筛选和非法查询验证通过');
  assert.deepEqual((await req('/api/after-sales/companies', undefined, { token: sales.token })).map(c => c.agentId), [agentA.id]);
  await req(casePath(otherCase.id), undefined, { token: sales.token, code: 'FORBIDDEN' });
  assert.deepEqual((await req(`${casePath(id)}/attachments`, undefined, { token: sales.token })).map(f => f.id), [publicFile.id]);
  await download(id, publicFile.id, sales.token); await download(id, privateFile.id, sales.token, 'FORBIDDEN');
  await req(`${casePath(id)}/messages`, { body: 'Read-only write attempt' }, { token: sales.token, code: 'PERMISSION_DENIED' });
  await req(`${company(agentA.id)}/history`, undefined, { token: sales.token, code: 'PERMISSION_DENIED' });
  await req(`${company(agentA.id)}/salespeople`, { userIds: [] }, { method: 'PUT', token: manager.token });
  await req(casePath(id), undefined, { token: sales.token, code: 'FORBIDDEN' });
  await req(`${casePath(id)}/attachments`, undefined, { token: sales.token, code: 'FORBIDDEN' });
  await download(id, publicFile.id, sales.token, 'FORBIDDEN');
  assert.equal((await req('/api/after-sales/cases', undefined, { token: sales.token })).total, 0);
  assert.equal((await req('/api/after-sales/cases/dashboard', undefined, { token: sales.token })).total, 0);
  pass('只读销售列表/count/公司资料/案例/附件共同按负责公司限制；撤销后同一token下一次访问立即拒绝，内部附件不可见');

  for (const assigneeId of [sales.id, own.id, disabledTech.id, localTech.id]) await req(`${casePath(id)}/assignee`, { assigneeId }, { method: 'PUT', token: dispatcher.token, code: 'INVALID_PARAMETER' });
  for (const userIds of [[sales.id, sales.id], [own.id], [disabledTech.id]]) await req(`${company(agentA.id)}/salespeople`, { userIds }, { method: 'PUT', token: manager.token, code: 'INVALID_PARAMETER' });
  await req(`${company(agentA.id)}/salespeople`, { userIds: [localTech.id] }, { method: 'PUT', token: manager.token });
  assert.equal((await req(`${casePath(id)}/assignee`, { assigneeId: localTech.id }, { method: 'PUT', token: dispatcher.token })).assigneeId, localTech.id);
  await req(`${casePath(id)}/submit`, {}, { token: own.token });
  await req(`${casePath(id)}/review`, { action: 'START' });
  await req(`${casePath(id)}/solution`, { solution: '检查后更换电机', internalNote: 'Internal company assignment test', currency: 'CNY', parts: [] });
  const assigned = await req(`${casePath(id)}/assignee`, { assigneeId: tech.id }, { method: 'PUT', token: dispatcher.token });
  assert.equal(assigned.assigneeId, tech.id); assert(assigned.assigneeName.includes('Technician')); assert.equal(assigned.internalNote, null);
  assert.equal((await req(`${casePath(id)}/assignee`, { assigneeId: tech.id }, { method: 'PUT', token: dispatcher.token })).internalNote, null);
  const cards = await req('/api/after-sales/cases/dashboard?keyword=' + prefix, undefined, { token: colleague.token });
  assert.equal(cards.total, 2); assert.equal(cards.review, 1); assert.equal(cards.pending, 0);
  const drilling = await req('/api/after-sales/cases?keyword=' + prefix + '&queue=REVIEW', undefined, { token: colleague.token });
  assert.equal(drilling.total, cards.review); assert.deepEqual(drilling.records.map(c => c.id), [id]);
  const assignedFilter = new URLSearchParams({ keyword: prefix, assigneeName: 'Technician' });
  assert.equal((await req('/api/after-sales/cases?' + assignedFilter, undefined, { token: colleague.token })).total, 1);
  await req(`${casePath(id)}/review`, { action: 'REQUEST_INFO', reason: '请上传诊断资料' });
  const waiting = await req('/api/after-sales/cases/dashboard?keyword=' + prefix, undefined, { token: colleague.token });
  assert.equal(waiting.waitingCustomer, 1); assert.equal(waiting.review, 0);
  assert.equal((await req('/api/after-sales/cases?keyword=' + prefix + '&queue=WAITING_CUSTOMER', undefined, { token: colleague.token })).total, 1);
  await req(`${casePath(id)}/submit`, {}, { token: colleague.token });
  assert.equal((await req('/api/after-sales/cases/dashboard?keyword=' + prefix, undefined, { token: colleague.token })).waitingCustomer, 0);
  pass('普通公司员工看板钻取到同一审核案例；负责人筛选生效，补资料和重新提交实时迁移计数');
  assert((await req(`${casePath(id)}/history`, undefined, { token: colleague.token })).some(h => h.action === 'CASE_ASSIGNED'));
  await req(`${casePath(id)}/review`, { action: 'REJECT', reason: '验收结束：终态派单边界检查' });
  await req(`${casePath(id)}/assignee`, { assigneeId: null }, { method: 'PUT', token: dispatcher.token, code: 'INVALID_STATUS_TRANSITION' });
  pass('派单只允许启用且具公司范围的内部处理人员；无权限、外部、停用和无范围目标拒绝；同值派单也脱敏，终态不可重新派单');

  await req(company(agentA.id), { ...contact('Invalid email'), contactEmail: 'invalid' }, { method: 'PUT', token: manager.token, code: 'INVALID_PARAMETER' });
  await req(company(agentA.id), { ...contact('Invalid country'), countryCode: 'ZZ' }, { method: 'PUT', token: manager.token, code: 'INVALID_PARAMETER' });
  await req('/api/after-sales/cases', draft('FUTURE', { endCustomerPurchaseDate: '2099-01-01' }), { token: own.token, code: 'INVALID_PARAMETER' });
  const history = await req(`${company(agentA.id)}/history`, undefined, { token: manager.token });
  assert(history.some(h => h.action === 'COMPANY_CONTACT_UPDATED')); assert(history.some(h => h.action === 'COMPANY_SALES_ASSIGNED'));
  assert(history.every(h => !('beforeData' in h) && !('afterData' in h)));
  pass('非法Email/国家与未来购买日期被拒绝；公司变更记录通过管理专用授权接口可查');
} catch (error) { evidence.error = error.message; process.exitCode = 1; console.error(error.stack); }
finally {
  evidence.cleanupFailures = [];
  for (const id of accounts) try { await req(`/api/users/${id}/status?enabled=false`, {}, { method: 'PUT' }); }
  catch (error) { evidence.cleanupFailures.push({ userId: id, error: error.message }); process.exitCode = 1; }
  for (const id of customRoles) try { await req(`/api/roles/${id}/status?enabled=false`, {}, { method: 'PUT' }); }
  catch (error) { evidence.cleanupFailures.push({ roleId: id, error: error.message }); process.exitCode = 1; }
  evidence.finishedAt = new Date().toISOString();
  if (process.env.BIKE_ACCEPTANCE_OUTPUT) await writeFile(process.env.BIKE_ACCEPTANCE_OUTPUT, JSON.stringify(evidence, null, 2));
}
