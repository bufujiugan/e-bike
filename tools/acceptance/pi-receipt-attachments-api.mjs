import { createPiDocument } from './pi-document-fixture.mjs';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { inflateRawSync } from 'node:zlib';

// Run only after the root task deploys V27 to its isolated acceptance instance.
if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
if (!process.env.BIKE_TEST_PASSWORD) throw new Error('缺少验收管理员密码');
const base = process.env.BIKE_API_URL || 'http://127.0.0.1:18181';
const url = new URL(base);
assert(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname), '凭证脚本仅允许本机隔离验收实例');
const prefix = `PRC${Date.now().toString(36).toUpperCase()}${randomBytes(2).toString('hex')}`;
const evidence = { prefix, startedAt: new Date().toISOString(), layer: 'HTTP', browserVerified: false, checks: [], accounts: [], fixtures: {}, files: [] };
const accounts = [], customRoles = [], workstations = [];
let admin;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const pass = name => { evidence.checks.push({ name, passed: true }); console.log(`PASS ${name}`); };

async function request(path, body, { method = body === undefined ? 'GET' : 'POST', token = admin, code = 'SUCCESS', status } = {}) {
  const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) });
  const result = await response.json();
  assert.equal(result.code, code, `${path}: ${response.status} ${result.code} ${result.message}`);
  assert.equal(response.ok, code === 'SUCCESS');
  if (status) assert.equal(response.status, status);
  return code === 'SUCCESS' ? result.data : result;
}

async function upload(endpoint, files, { token, requestId = randomUUID(), code = 'SUCCESS', status } = {}) {
  const form = new FormData(); form.append('requestId', requestId);
  for (const file of files) form.append(file.part || 'file', new Blob([file.bytes], { type: file.type }), file.name);
  const response = await fetch(base + endpoint, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form, signal: AbortSignal.timeout(45000) });
  const result = await response.json();
  assert.equal(result.code, code, `${endpoint}: ${response.status} ${result.code} ${result.message}`);
  assert.equal(response.ok, code === 'SUCCESS');
  if (status) assert.equal(response.status, status);
  return code === 'SUCCESS' ? result.data : result;
}

async function role(suffix, codes, available) {
  const created = await request('/api/roles', { roleCode: prefix + suffix, roleName: `隔离凭证验收 ${suffix}` }); customRoles.push(created.id);
  const permissionIds = codes.map(code => { const permission = available.find(p => p.permissionCode === code); assert(permission, `Missing permission ${code}`); return permission.id; });
  await request(`/api/roles/${created.id}/permissions`, { permissionIds }, { method: 'PUT' });
  return created;
}

async function account(suffix, assigned, userType = 'WEB_OPERATOR', bindings = {}) {
  const username = prefix + suffix, initial = randomBytes(20).toString('hex'), password = randomBytes(20).toString('hex');
  const user = await request('/api/users', { username, password: initial, displayName: `隔离凭证验收 ${suffix}`, userType, ...bindings });
  const entry = { id: user.id, username, password: initial }; accounts.push(entry);
  await request(`/api/users/${user.id}/roles`, { roleIds: [assigned.id] }, { method: 'PUT' });
  const first = await request('/api/auth/login', { username, password: initial }, { token: null });
  await request('/api/auth/password', { currentPassword: initial, newPassword: password }, { method: 'PUT', token: first.accessToken }); entry.password = password;
  const login = await request('/api/auth/login', { username, password }, { token: null }); assert.equal(login.mustChangePassword, false);
  const context = await request('/api/auth/context', undefined, { token: login.accessToken });
  assert(!context.roles.includes('SUPER_ADMIN'));
  evidence.accounts.push({ suffix, id: user.id, username, userType, roles: context.roles, permissions: context.permissions });
  return { token: login.accessToken, username, id: user.id };
}

function pdf() {
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Count 1 /Kids [3 0 R] >>', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << >> /Contents 4 0 R >>', '<< /Length 0 >>\nstream\n\nendstream'];
  let result = '%PDF-1.4\n'; const offsets = [];
  for (const [index, body] of objects.entries()) { offsets.push(Buffer.byteLength(result)); result += `${index + 1} 0 obj\n${body}\nendobj\n`; }
  const xref = Buffer.byteLength(result);
  result += 'xref\n0 5\n0000000000 65535 f \n' + offsets.map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  return Buffer.from(result + `trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
}

function spreadsheetXml(bytes) {
  // Read central-directory sizes, because Apache POI uses ZIP data descriptors.
  let end = bytes.length - 22;
  while (end >= 0 && bytes.readUInt32LE(end) !== 0x06054b50) end--;
  assert(end >= 0, '导出文件须为完整XLSX');
  let offset = bytes.readUInt32LE(end + 16), output = '';
  for (let index = 0; index < bytes.readUInt16LE(end + 10); index++) {
    assert.equal(bytes.readUInt32LE(offset), 0x02014b50);
    const method = bytes.readUInt16LE(offset + 10), length = bytes.readUInt32LE(offset + 20);
    const nameLength = bytes.readUInt16LE(offset + 28), extraLength = bytes.readUInt16LE(offset + 30), commentLength = bytes.readUInt16LE(offset + 32);
    const name = bytes.subarray(offset + 46, offset + 46 + nameLength).toString(), local = bytes.readUInt32LE(offset + 42);
    const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    if (name.endsWith('.xml')) {
      const content = bytes.subarray(start, start + length);
      output += (method === 8 ? inflateRawSync(content) : content).toString('utf8');
    }
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return output;
}

async function download(endpoint, attachment, expected, token) {
  const response = await fetch(`${base}${endpoint}/${attachment.id}/download`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30000) });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-disposition'), /^attachment;/i);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert.match(response.headers.get('content-security-policy'), /sandbox/);
  const bytes = Buffer.from(await response.arrayBuffer()); assert.deepEqual(bytes, expected.bytes);
  assert.equal(attachment.sha256, hash(bytes)); assert.equal(attachment.fileSize, bytes.length); assert.equal(attachment.fileName, expected.name);
  assert(!('objectKey' in attachment));
  evidence.files.push({ attachmentId: attachment.id, fileName: attachment.fileName, size: bytes.length, sha256: hash(bytes), downloadedBy: token ? 'authorized-finance' : 'anonymous' });
}

try {
  const login = await request('/api/auth/login', { username: process.env.BIKE_TEST_USERNAME || 'admin', password: process.env.BIKE_TEST_PASSWORD }, { token: null });
  assert.equal(login.mustChangePassword, false); admin = login.accessToken;
  const available = await request('/api/roles/permissions');
  const common = ['PI_READ', 'DATA_EXPORT', 'TRACE_READ', 'DASHBOARD_READ'];
  const financeRole = await role('FIN', [...common, 'PI_PAYMENT_CONFIRM'], available);
  const releaseRole = await role('REL', [...common, 'PI_RELEASE'], available);
  const readerRole = await role('READ', common, available);
  const master = (type, suffix) => request(`/api/master/${type}`, { code: prefix + suffix, name: `隔离凭证验收 ${prefix}${suffix}` });
  const customer = await master('customers', 'C'), agent = await master('agents', 'A'), model = await master('models', 'M');
  const station = await request('/api/workstations', { workstationCode: prefix + 'WS', workstationName: '凭证权限验收工位', workstationType: 'STOCK_OUT' }); workstations.push(station.id);
  const finance = await account('Finance', financeRole), release = await account('Release', releaseRole), reader = await account('Reader', readerRole);
  const external = await account('External', financeRole, 'AGENT', { agentId: agent.id });
  const pda = await account('Pda', financeRole, 'PDA_OPERATOR', { workstationId: station.id });
  const template = (await request('/api/production/templates'))[0]; assert(template);
  await request('/api/production/skus', { modelId: model.id, skuCode: prefix + 'SKU', productName: '隔离收款凭证测试商品', specification: '不产生真实交易', templateId: template.id, enabled: true });
  const sku = (await request('/api/production/skus')).find(row => row.skuCode === prefix + 'SKU'); assert(sku);
  const payload = { customerId: customer.id, agentId: agent.id, tradeType: 'DOMESTIC', currencyCode: 'CNY', depositRequiredAmount: '30.00', shipmentRequiredAmount: '100.00', remark: '专用凭证验收，非真实交易，不生成出库',
    document: await createPiDocument(request, prefix, { sellerName: '隔离验收卖方', paymentTerms: '仅测试：30元定金、100元放行；不得实际付款', deliveryTerms: '仅测试，不交付', templateCode: 'RECEIPT-ACCEPTANCE', templateVersion: '1' }), items: [{ skuId: sku.id, quantity: 1, unitPrice: '100.00' }] };
  const pis = [];
  for (let index = 0; index < 2; index++) {
    const pi = await request('/api/pis', payload); await request(`/api/pis/${pi.id}/confirm`, {});
    const paid = await request(`/api/pis/${pi.id}/payments`, { requestId: randomUUID(), amount: '30.00', currencyCode: 'CNY', voucherObjectKey: 'local:legacy-unverified-reference.pdf', remark: prefix + ' payment' }, { token: finance.token });
    assert.equal(paid.payments.length, 1); pis.push({ id: pi.id, paymentId: paid.payments[0].id, before: paid });
  }
  const current = pis[0], other = pis[1], endpoint = `/api/pis/${current.id}/payments/${current.paymentId}/attachments`;
  evidence.fixtures = { piIds: pis.map(pi => pi.id), paymentIds: pis.map(pi => pi.paymentId), customerId: customer.id, agentId: agent.id, skuId: sku.id, roleIds: customRoles, workstationIds: workstations };
  assert.deepEqual(await request(endpoint, undefined, { token: finance.token }), []);
  pass('专用PI、真实到账及五个非super岗位创建完成，旧凭证引用未被当作文件');

  const png = { name: `${prefix}银行回单.png`, type: 'image/png', bytes: await readFile(new URL('./fixtures/after-sales/fault.png', import.meta.url)) };
  const document = { name: `${prefix}银行回单.pdf`, type: 'application/pdf', bytes: pdf() };
  const pngRequest = randomUUID();
  const uploadedPng = await upload(endpoint, [png], { token: finance.token, requestId: pngRequest });
  const repeated = await upload(endpoint, [png], { token: finance.token, requestId: pngRequest }); assert.equal(repeated.id, uploadedPng.id);
  const uploadedPdf = await upload(endpoint, [document], { token: finance.token });
  await download(endpoint, uploadedPng, png, finance.token); await download(endpoint, uploadedPdf, document, release.token);
  assert.equal((await request(endpoint, undefined, { token: release.token })).length, 2);
  pass('普通财务上传PNG/PDF与只读放行下载逐字节一致、SHA256匹配、安全响应头有效且顺序重试只保存一份');

  const concurrentRequest = randomUUID();
  const concurrent = await Promise.all([upload(endpoint, [png], { token: finance.token, requestId: concurrentRequest }), upload(endpoint, [png], { token: finance.token, requestId: concurrentRequest })]);
  assert.equal(concurrent[0].id, concurrent[1].id);
  await upload(endpoint, [document], { token: finance.token, requestId: concurrentRequest, code: 'IDEMPOTENCY_CONFLICT' });
  assert.equal((await request(endpoint, undefined, { token: finance.token })).length, 3);
  pass('相同request并发返回同一附件，不同内容复用请求被拒绝且不重复保存');

  for (const [actor, code] of [[reader, 'PERMISSION_DENIED'], [external, 'FORBIDDEN'], [pda, 'PERMISSION_DENIED']]) {
    await request(endpoint, undefined, { token: actor.token, code, status: 403 });
    await request(`${endpoint}/${uploadedPng.id}/download`, undefined, { token: actor.token, code, status: 403 });
    await upload(endpoint, [png], { token: actor.token, code, status: 403 });
  }
  await upload(endpoint, [png], { token: release.token, code: 'PERMISSION_DENIED', status: 403 });
  const anonymous = await fetch(`${base}${endpoint}/${uploadedPng.id}/download`); assert.equal(anonymous.status, 401);
  for (const path of [`/api/pis/${other.id}/payments/${current.paymentId}/attachments`, `/api/pis/${other.id}/payments/${other.paymentId}/attachments/${uploadedPng.id}/download`])
    await request(path, undefined, { token: finance.token, code: 'ATTACHMENT_NOT_FOUND' });
  await upload(`/api/pis/${other.id}/payments/${current.paymentId}/attachments`, [png], { token: finance.token, code: 'ATTACHMENT_NOT_FOUND' });
  pass('普通业务、误授权财务的代理/PDA及匿名均不能访问，放行角色不能上传，跨PI/付款/附件组合拒绝');

  for (const invalid of [
    { name: 'fake.pdf', type: 'application/pdf', bytes: Buffer.from('%PDF-1.4 fake %%EOF') },
    { name: 'fake.png', type: 'image/png', bytes: Buffer.from('not an image') },
    { name: 'partial.png', type: 'image/png', bytes: png.bytes.subarray(0, 33) },
    { ...png, type: 'application/pdf' },
  ]) await upload(endpoint, [invalid], { token: finance.token, code: 'ATTACHMENT_CONTENT_INVALID' });
  await upload(endpoint, [{ name: 'video.mp4', type: 'video/mp4', bytes: Buffer.from('fake video') }], { token: finance.token, code: 'ATTACHMENT_TYPE_UNSUPPORTED' });
  await upload(endpoint, [{ ...png, name: '../proof.png' }], { token: finance.token, code: 'ATTACHMENT_NAME_INVALID' });
  await upload(endpoint, [{ ...png, name: 'large.png', bytes: Buffer.alloc(10 * 1024 * 1024 + 1, 65) }], { token: finance.token, code: 'ATTACHMENT_SIZE_INVALID' });
  for (const part of ['file', 'extra']) await upload(endpoint, [png, { part, name: 'bad.pdf', type: 'application/pdf', bytes: Buffer.from('fake') }], { token: finance.token, code: 'INVALID_PARAMETER' });
  assert.equal((await request(endpoint, undefined, { token: finance.token })).length, 3);
  assert.deepEqual(await request(`/api/pis/${current.id}`, undefined, { token: finance.token }), current.before);
  pass('伪造/截断/错误MIME/视频/路径/超限文件和真假混合多文件请求全部拒绝，无部分附件且到账、PI和占用视图不变');

  for (const module of ['logs', 'operation-logs']) {
    const path = `/api/modules/${module}?keyword=${encodeURIComponent(finance.username)}`;
    assert.equal((await request(path, undefined, { token: reader.token })).total, 0);
    const financial = await request(path, undefined, { token: finance.token });
    assert.equal(financial.rows.filter(row => row.includes('上传 PI 收款凭证')).length, 3);
  }
  const trace = `/api/modules/trace?keyword=${encodeURIComponent('上传 PI 收款凭证')}`;
  assert.equal((await request(trace, undefined, { token: reader.token })).total, 0);
  assert((await request(trace, undefined, { token: finance.token })).total >= 3);
  const dashboard = await request('/api/dashboard/summary', undefined, { token: reader.token });
  assert(!dashboard.recentOperations.some(row => row.action === '上传 PI 收款凭证'));
  for (const actor of [reader, finance]) {
    const response = await fetch(`${base}/api/export/logs?keyword=${encodeURIComponent(finance.username)}`, { headers: { Authorization: `Bearer ${actor.token}` } });
    assert.equal(response.status, 200);
    const xml = spreadsheetXml(Buffer.from(await response.arrayBuffer()));
    assert.equal(xml.includes('上传 PI 收款凭证'), actor === finance);
    if (actor === reader) assert(!xml.includes(finance.username));
  }
  pass('凭证审计对普通日志、别名、搜索、追溯、仪表盘和真实Excel导出隐藏，财务可见且幂等仅一条审计');
} catch (error) {
  evidence.error = error.message; process.exitCode = 1; console.error(error.message);
} finally {
  evidence.cleanup = []; evidence.cleanupFailures = [];
  for (const account of accounts) try {
    await request(`/api/users/${account.id}/status?enabled=false`, {}, { method: 'PUT' });
    await request('/api/auth/login', { username: account.username, password: account.password }, { token: null, code: 'AUTH_INVALID_CREDENTIALS' });
    evidence.cleanup.push({ userId: account.id, disabled: true, loginRejected: true });
  } catch (error) { evidence.cleanupFailures.push({ userId: account.id, error: error.message }); process.exitCode = 1; }
  for (const [type, ids] of [['roles', customRoles], ['workstations', workstations]]) for (const id of ids) try {
    await request(`/api/${type}/${id}/status?enabled=false`, {}, { method: 'PUT' }); evidence.cleanup.push({ type, id, disabled: true });
  } catch (error) { evidence.cleanupFailures.push({ type, id, error: error.message }); process.exitCode = 1; }
  evidence.finishedAt = new Date().toISOString();
  if (process.env.BIKE_ACCEPTANCE_OUTPUT) await writeFile(process.env.BIKE_ACCEPTANCE_OUTPUT, JSON.stringify(evidence, null, 2));
}
