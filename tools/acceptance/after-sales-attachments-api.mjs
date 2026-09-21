import assert from 'node:assert/strict';
import { randomBytes, createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
const base = process.env.BIKE_API_URL || 'http://127.0.0.1:18181';
const prefix = `ASF${Date.now().toString(36).toUpperCase()}${randomBytes(2).toString('hex')}`;
const evidence = { prefix, startedAt: new Date().toISOString(), checks: [], fixtures: {} };
const accounts = [], customRoles = [];
let admin;
async function request(path, body, { method = body === undefined ? 'GET' : 'POST', token = admin, code = 'SUCCESS' } = {}) {
  const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json();
  assert.equal(result.code, code, `${path}: ${response.status} ${result.code} ${result.message}`);
  assert.equal(response.ok, code === 'SUCCESS');
  return code === 'SUCCESS' ? result.data : result;
}
async function upload(caseId, files, { token = admin, visibility = 'CUSTOMER', category = 'OTHER', messageId, code = 'SUCCESS' } = {}) {
  const form = new FormData(); for (const file of files) form.append('files', new Blob([file.bytes], { type: file.type }), file.name);
  const query = new URLSearchParams({ visibility, category }); if (messageId) query.set('messageId', String(messageId));
  const response = await fetch(`${base}/api/after-sales/cases/${caseId}/attachments?${query}`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  const result = await response.json(); assert.equal(result.code, code, `${response.status}: ${result.message}`); assert.equal(response.ok, code === 'SUCCESS');
  return code === 'SUCCESS' ? result.data : result;
}
async function account(suffix, roleIds, agentId) {
  const initial = randomBytes(20).toString('hex'), password = randomBytes(20).toString('hex'), username = prefix + suffix;
  const user = await request('/api/users', { username, password: initial, displayName: `售后附件验收${suffix}`, userType: agentId ? 'AGENT' : 'WEB_OPERATOR', agentId }); accounts.push(user.id);
  await request(`/api/users/${user.id}/roles`, { roleIds }, { method: 'PUT' });
  const first = await request('/api/auth/login', { username, password: initial }, { token: null });
  await request('/api/auth/password', { currentPassword: initial, newPassword: password }, { method: 'PUT', token: first.accessToken });
  return (await request('/api/auth/login', { username, password }, { token: null })).accessToken;
}
function pass(name) { evidence.checks.push({ name, passed: true }); console.log(`PASS ${name}`); }
function pdf() {
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Count 1 /Kids [3 0 R] >>', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << >> /Contents 4 0 R >>', '<< /Length 0 >>\nstream\n\nendstream'];
  let result = '%PDF-1.4\n', offsets = [0];
  for (const [index, body] of objects.entries()) { offsets.push(Buffer.byteLength(result)); result += `${index + 1} 0 obj\n${body}\nendobj\n`; }
  const xref = Buffer.byteLength(result); result += `xref\n0 5\n0000000000 65535 f \n`;
  result += offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  return Buffer.from(result + `trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
}

try {
  const login = await request('/api/auth/login', { username: process.env.BIKE_TEST_USERNAME || 'admin', password: process.env.BIKE_TEST_PASSWORD }, { token: null }); admin = login.accessToken;
  const roles = await request('/api/roles'), agentRole = roles.find(r => r.roleCode === 'WEB_AGENT'); assert(agentRole);
  const permissions = (await request('/api/roles/permissions')).filter(p => ['AFTER_SALES_READ', 'AFTER_SALES_SCOPE_ALL'].includes(p.permissionCode)); assert.equal(permissions.length, 2);
  const readRole = await request('/api/roles', { roleCode: prefix + 'READ', roleName: '售后附件验收只读' }); customRoles.push(readRole.id);
  await request(`/api/roles/${readRole.id}/permissions`, { permissionIds: permissions.map(p => p.id) }, { method: 'PUT' });
  const agentA = await request('/api/master/agents', { code: prefix + 'A', name: '附件验收代理A' });
  const agentB = await request('/api/master/agents', { code: prefix + 'B', name: '附件验收代理B' });
  const own = await account('Own', [agentRole.id], agentA.id), other = await account('Other', [agentRole.id], agentB.id), reader = await account('Read', [readRole.id]);
  const current = await request('/api/after-sales/cases', { frameSn: prefix + 'FRAME', description: '附件实际文件往返验收', faultType: '电机', warrantyStatus: 'PENDING' }, { token: own });
  const caseId = current.id, endpoint = `/api/after-sales/cases/${caseId}`;
  evidence.fixtures = { caseId, caseNo: current.caseNo, userIds: accounts, roleIds: customRoles };
  const png = await readFile(new URL('./fixtures/after-sales/fault.png', import.meta.url));
  const mp4 = await readFile(new URL('./fixtures/after-sales/fault.mp4', import.meta.url));
  const hostileVideo = Buffer.from(mp4), media = hostileVideo.indexOf('mdat');
  const mediaBody = media + (hostileVideo.readInt32BE(media - 4) === 1 ? 12 : 4), nalSize = hostileVideo.readInt32BE(mediaBody);
  // Valid MP4 container with an in-band PPS claiming a million FMO slice groups.
  const hostilePps = Buffer.from('68c00001e84830', 'hex');
  assert.ok(nalSize >= hostilePps.length); hostileVideo.fill(0, mediaBody + 4, mediaBody + 4 + nalSize); hostilePps.copy(hostileVideo, mediaBody + 4);
  const inputs = [{ name: '故障照片.png', type: 'image/png', bytes: png }, { name: '故障视频.mp4', type: 'video/mp4', bytes: mp4 }, { name: '购买凭证.pdf', type: 'application/pdf', bytes: pdf() }, { name: '诊断报告.json', type: 'application/json', bytes: Buffer.from('{"motor":"E01","voltage":48}') }];
  const uploaded = await upload(caseId, inputs, { token: own }); assert.equal(uploaded.length, 4);
  for (const [index, file] of uploaded.entries()) {
    assert.equal(file.fileName, inputs[index].name); assert.equal(file.fileSize, inputs[index].bytes.length);
    assert.equal(file.sha256, createHash('sha256').update(inputs[index].bytes).digest('hex')); assert(!('objectKey' in file));
    const response = await fetch(`${base}${endpoint}/attachments/${file.id}/download`, { headers: { Authorization: `Bearer ${own}` } });
    assert.equal(response.status, 200); assert.match(response.headers.get('content-disposition'), /^attachment;/i);
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff'); assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), inputs[index].bytes);
  }
  pass('真实PNG、H.264 MP4、PDF和JSON四文件批量上传及下载逐字节一致，SHA256匹配，下载安全响应头正确且无对象键');

  const internal = (await upload(caseId, [{ name: '内部诊断.log', type: 'text/plain', bytes: Buffer.from('private liability assessment') }], { visibility: 'INTERNAL', category: 'TECHNICAL' }))[0];
  assert.equal((await request(`${endpoint}/attachments`, undefined, { token: own })).length, 4);
  assert.equal((await request(`${endpoint}/attachments`, undefined, { token: reader })).length, 4);
  assert.equal((await request(`${endpoint}/attachments`)).length, 5);
  await request(`${endpoint}/attachments/${internal.id}/download`, undefined, { token: own, code: 'FORBIDDEN' });
  await request(`${endpoint}/attachments/${internal.id}/download`, undefined, { token: reader, code: 'FORBIDDEN' });
  await request(`${endpoint}/attachments`, undefined, { token: other, code: 'FORBIDDEN' });
  await request(`${endpoint}/attachments/${uploaded[0].id}/download`, undefined, { token: other, code: 'FORBIDDEN' });
  await upload(caseId, [inputs[0]], { token: reader, code: 'PERMISSION_DENIED' });
  await upload(caseId, [inputs[0]], { token: own, visibility: 'INTERNAL', code: 'PERMISSION_DENIED' });
  const anonymous = await fetch(`${base}${endpoint}/attachments/${uploaded[0].id}/download`); assert.equal(anonymous.status, 401);
  pass('跨代理商、匿名及内部附件下载拒绝；只读不能上传；客户看不到内部附件或存储对象键');

  const before = (await request(`${endpoint}/attachments`)).length;
  for (const input of [
    { name: 'fake.png', type: 'image/png', bytes: Buffer.from('fake image') },
    { name: 'cut.png', type: 'image/png', bytes: png.subarray(0, 33) },
    { name: 'fake.pdf', type: 'application/pdf', bytes: Buffer.from('%PDF-1.4 fake %%EOF') },
    { name: 'cut.mp4', type: 'video/mp4', bytes: mp4.subarray(0, 100) },
    { name: 'hostile-pps.mp4', type: 'video/mp4', bytes: hostileVideo },
    { name: 'binary.txt', type: 'text/plain', bytes: Buffer.from([65, 0, 66]) },
    { name: 'invalid.json', type: 'application/json', bytes: Buffer.from('{broken}') },
  ]) await upload(caseId, [inputs[0], input], { token: own, code: 'ATTACHMENT_CONTENT_INVALID' });
  await upload(caseId, [{ name: '../path.png', type: 'image/png', bytes: png }], { token: own, code: 'ATTACHMENT_NAME_INVALID' });
  await upload(caseId, [{ name: 'active.svg', type: 'image/svg+xml', bytes: Buffer.from('<svg/>') }], { token: own, code: 'ATTACHMENT_TYPE_UNSUPPORTED' });
  await upload(caseId, [{ name: 'huge.log', type: 'text/plain', bytes: Buffer.alloc(10 * 1024 * 1024 + 1, 65) }], { token: own, code: 'ATTACHMENT_SIZE_INVALID' });
  assert.equal((await request(`${endpoint}/attachments`)).length, before);
  pass('真假混合批次、损坏图片/PDF/视频、二进制文本、非法JSON、路径名、SVG和超限文件全部拒绝，附件列表无部分提交');

  await request(`${endpoint}/submit`, {}, { token: own });
  const message = await request(`${endpoint}/messages`, { body: '请查看附带诊断文件', visibility: 'CUSTOMER' }, { token: own });
  const linked = (await upload(caseId, [{ name: 'reply.csv', type: 'text/csv', bytes: Buffer.from('part,error\nmotor,E01') }], { token: own, category: 'DIAGNOSTIC', messageId: message.id }))[0];
  assert.equal(linked.messageId, message.id);
  await upload(caseId, [inputs[0]], { token: own, messageId: message.id, visibility: 'INTERNAL', code: 'PERMISSION_DENIED' });
  const unrelated = await request('/api/after-sales/cases', { frameSn: prefix + 'OTHER', agentId: agentA.id, description: '另一案例', faultType: '电机' });
  await upload(unrelated.id, [inputs[0]], { token: own, messageId: message.id, code: 'INVALID_PARAMETER' });
  await request(`${endpoint}/review`, { action: 'REJECT', reason: '附件验收结束' });
  await upload(caseId, [inputs[0]], { token: own, code: 'INVALID_STATUS_TRANSITION' });
  const history = await request(`${endpoint}/history`, undefined, { token: own });
  assert(history.some(item => item.action === 'ATTACHMENTS_ADDED')); assert(history.every(item => item.action !== 'INTERNAL_ATTACHMENTS_ADDED'));
  pass('留言附件真实关联、跨案例留言绑定拒绝、终态上传拒绝且内部附件审计对客户隐藏');
} catch (error) { evidence.error = error.message; process.exitCode = 1; console.error(error.message); }
finally {
  evidence.cleanupFailures = [];
  for (const id of accounts) try { await request(`/api/users/${id}/status?enabled=false`, {}, { method: 'PUT' }); }
  catch (error) { evidence.cleanupFailures.push({ userId: id, error: error.message }); process.exitCode = 1; }
  for (const id of customRoles) try { await request(`/api/roles/${id}/status?enabled=false`, {}, { method: 'PUT' }); }
  catch (error) { evidence.cleanupFailures.push({ roleId: id, error: error.message }); process.exitCode = 1; }
  evidence.finishedAt = new Date().toISOString(); if (process.env.BIKE_ACCEPTANCE_OUTPUT) await writeFile(process.env.BIKE_ACCEPTANCE_OUTPUT, JSON.stringify(evidence, null, 2));
}
