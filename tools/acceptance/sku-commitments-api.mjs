import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
if (!process.env.BIKE_TEST_PASSWORD) throw new Error('缺少验收管理员密码');
const base = process.env.BIKE_API_URL || 'http://127.0.0.1:18181';
assert(['127.0.0.1', 'localhost', '[::1]'].includes(new URL(base).hostname), '仅允许本机隔离实例');
const prefix = `SKU${Date.now().toString(36).toUpperCase()}`;
const evidence = { prefix, startedAt: new Date().toISOString(), layer: 'HTTP', browserVerified: false, checks: [], fixtures: {}, cleanupFailures: [] };
const accounts = [], roles = [], stations = [], locksToRelease = new Set(), ordersToCancel = new Set();
let admin;
async function req(path, body, { method = body === undefined ? 'GET' : 'POST', token = admin, code = 'SUCCESS' } = {}) {
  const response = await fetch(base + path, { method, signal: AbortSignal.timeout(30000), headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json();
  assert.equal(result.code, code, `${path}: HTTP ${response.status} ${result.code} ${result.message}`);
  assert.equal(response.ok, code === 'SUCCESS');
  return code === 'SUCCESS' ? result.data : result;
}
function pass(name) { evidence.checks.push({ name, passed: true }); console.log(`PASS ${name}`); }
async function account(suffix, codes, agentId = null, workstationId = null) {
  const permissions = await req('/api/roles/permissions');
  const role = await req('/api/roles', { roleCode: prefix + suffix, roleName: `SKU验收${suffix}` });
  roles.push(role.id);
  await req(`/api/roles/${role.id}/permissions`, { permissionIds: codes.map(code => { const p = permissions.find(p => p.permissionCode === code); assert(p, code); return p.id; }) }, { method: 'PUT' });
  const initial = randomBytes(24).toString('hex'), password = randomBytes(24).toString('hex'), username = prefix + suffix;
  const user = await req('/api/users', { username, password: initial, displayName: 'SKU隔离验收', userType: agentId ? 'AGENT' : 'WEB_OPERATOR', agentId, workstationId });
  accounts.push({ id: user.id, username, password });
  await req(`/api/users/${user.id}/roles`, { roleIds: [role.id] }, { method: 'PUT' });
  const first = await req('/api/auth/login', { username, password: initial }, { token: null });
  await req('/api/auth/password', { currentPassword: initial, newPassword: password }, { method: 'PUT', token: first.accessToken });
  const token = (await req('/api/auth/login', { username, password }, { token: null })).accessToken;
  assert(!(await req('/api/auth/context', undefined, { token })).roles.includes('SUPER_ADMIN'));
  return token;
}
async function snapshot(modelId, token = admin) { return (await req(`/api/inventory/skus?modelId=${modelId}&pageSize=100`, undefined, { token })).records; }
async function expectSku(modelId, skuId, physical, temporary, reserved, available) {
  const row = (await snapshot(modelId)).find(r => r.skuId === skuId); assert(row);
  for (const [key, value] of Object.entries({ physicalQty: physical, temporaryLockedQty: temporary, orderReservedQty: reserved, availableQty: available, shortageQty: 0 })) assert.equal(Number(row[key]), value, `${key} SKU ${skuId}`);
  return row;
}
try {
  const login = await req('/api/auth/login', { username: process.env.BIKE_TEST_USERNAME || 'admin', password: process.env.BIKE_TEST_PASSWORD }, { token: null });
  assert.equal(login.mustChangePassword, false); admin = login.accessToken;
  const master = (type, suffix) => req(`/api/master/${type}`, { code: prefix + suffix, name: prefix + suffix });
  const model = await master('models', 'M'), customer = await master('customers', 'C'), otherCustomer = await master('customers', 'C2'), agent = await master('agents', 'A');
  const warehouse = await master('warehouses', 'W');
  const location = await req(`/api/master/warehouses/${warehouse.id}/locations`, { code: prefix + 'L', name: 'SKU验收库位' });
  const template = (await req('/api/production/templates'))[0]; assert(template);
  const skus = [];
  for (const color of ['RED', 'BLUE']) {
    await req('/api/production/skus', { modelId: model.id, skuCode: prefix + color, productName: 'SKU验收车辆', specification: color, templateId: template.id, enabled: true });
    skus.push((await req('/api/production/skus')).find(s => s.skuCode === prefix + color));
  }
  const [red, blue] = skus; assert(red && blue);
  const sales = await account('SALES', ['AGENT_LOCK_WRITE', 'ORDER_READ', 'ORDER_WRITE', 'INVENTORY_READ']);
  for (const type of ['STOCK_IN', 'STOCK_OUT']) stations.push(await req('/api/workstations', { workstationCode: prefix + type, workstationName: 'SKU验收' + type, workstationType: type }));
  const warehouseOperator = await account('WAREHOUSE', ['STOCK_OUT_OPERATE', 'ORDER_READ', 'INVENTORY_READ'], null, stations[1].id);
  const inboundOperator = await account('INBOUND', ['STOCK_IN_OPERATE', 'INVENTORY_READ'], null, stations[0].id);
  const reader = await account('READER', ['INVENTORY_READ']);
  const external = await account('AGENT', ['AGENT_INVENTORY_READ', 'AGENT_LOCK_WRITE', 'ORDER_WRITE', 'ORDER_READ'], agent.id);
  const vehicles = [];
  for (const sku of skus) for (let i = 0; i < 2; i++) {
    const taskId = randomUUID(), tag = `${prefix}${sku.id}V${i}`;
    for (const type of ['MOTOR', 'BATTERY', 'DISPLAY']) await req('/api/parts', { partSn: tag + type, partType: type, qcStatus: 'PASSED' });
    await req('/api/production/tasks', { requestId: taskId, skuId: sku.id, batchNo: prefix });
    for (const type of ['FRAME', 'DISPLAY', 'MOTOR', 'BATTERY']) await req(`/api/production/tasks/${taskId}/scan`, { code: tag + type, expectedType: type });
    const vehicle = await req(`/api/production/tasks/${taskId}`); assert.equal(vehicle.status, 'COMPLETED');
    const stockIn = await req('/api/stock-in', { vehicleQrCode: vehicle.qrCode, warehouseId: warehouse.id, locationId: location.id });
    vehicles.push({ skuId: sku.id, taskId, qrCode: vehicle.qrCode, stockInId: stockIn.id });
  }
  evidence.fixtures = { modelId: model.id, customerId: customer.id, skus: skus.map(s => ({ id: s.id, code: s.skuCode })), vehicles };
  await expectSku(model.id, red.id, 2, 0, 0, 2); await expectSku(model.id, blue.id, 2, 0, 0, 2);
  const pages = await Promise.all([1, 2].map(page => req(`/api/inventory/skus?modelId=${model.id}&page=${page}&pageSize=1`, undefined, { token: sales })));
  assert(pages.every(p => p.total === 2)); assert.deepEqual(pages.flatMap(p => p.records.map(r => r.skuId)).sort((a,b) => a-b), skus.map(s => s.id).sort((a,b) => a-b));
  pass('两种SKU各两台经真实四码生产入库；普通销售分页不丢不重');

  const lockBody = items => ({ requestId: randomUUID(), customerId: customer.id, agentId: null, items, remark: prefix });
  const failedBatch = lockBody([{ skuId: red.id, quantity: 1 }, { skuId: blue.id, quantity: 3 }]);
  await req('/api/sku-locks', failedBatch, { token: sales, code: 'SKU_INVENTORY_NOT_ENOUGH' });
  await expectSku(model.id, red.id, 2, 0, 0, 2); await expectSku(model.id, blue.id, 2, 0, 0, 2);
  const both = { ...failedBatch, items: [{ skuId: blue.id, quantity: 1 }, { skuId: red.id, quantity: 1 }] };
  const batch = await req('/api/sku-locks', both, { token: sales }); batch.forEach(l => locksToRelease.add(l.id));
  assert.equal(batch.length, 2);
  const retry = await req('/api/sku-locks', { ...both, items: [...both.items].reverse() }, { token: sales });
  assert.deepEqual(retry.map(l => l.id), batch.map(l => l.id));
  await req('/api/sku-locks', { ...both, items: [{ skuId: red.id, quantity: 2 }] }, { token: sales, code: 'SKU_LOCK_REQUEST_CONFLICT' });
  for (const lock of batch) { await req(`/api/inventory-locks/${lock.id}/release?reason=SKU批量验收释放`, {}, { token: sales }); locksToRelease.delete(lock.id); }
  pass('第二SKU不足整批回滚，失败请求可重试；排序无关幂等，改内容冲突，释放恢复');

  const redBody = lockBody([{ skuId: red.id, quantity: 2 }]);
  const [lock] = await req('/api/sku-locks', redBody, { token: sales }); locksToRelease.add(lock.id);
  await expectSku(model.id, red.id, 2, 2, 0, 0); await expectSku(model.id, blue.id, 2, 0, 0, 2);
  await req('/api/sku-locks', lockBody([{ skuId: red.id, quantity: 1 }]), { token: sales, code: 'SKU_INVENTORY_NOT_ENOUGH' });
  const module = await req(`/api/modules/inventory-locks?keyword=${lock.lockNo}`, undefined, { token: sales });
  assert.equal(module.total, 1); assert(JSON.stringify(module.rows).includes('直接客户'));
  const conversion = { customerId: customer.id, quantity: 2, requestId: randomUUID() };
  await req(`/api/inventory-locks/${lock.id}/orders`, { ...conversion, customerId: otherCustomer.id }, { token: sales, code: 'LOCK_CUSTOMER_MISMATCH' });
  const order = await req(`/api/inventory-locks/${lock.id}/orders`, conversion, { token: sales }); ordersToCancel.add(order.id); locksToRelease.delete(lock.id);
  evidence.fixtures.orderId = order.id;
  assert.equal((await req(`/api/inventory-locks/${lock.id}/orders`, conversion, { token: sales })).id, order.id);
  assert.equal(order.reservationExpiresAt, lock.expireTime);
  await req(`/api/orders/${order.id}/confirm`, {}, { token: sales });
  await expectSku(model.id, red.id, 2, 0, 2, 0);
  pass('蓝色库存不能替代红色；直接客户列表可见；转单客户必须匹配且重试不重复，原截止不延长');

  const ship = selected => req('/api/stock-out', { customerId: customer.id, orderId: order.id, vehicleQrCodes: selected.map(v => v.qrCode) }, { token: warehouseOperator });
  await req('/api/stock-out', { customerId: customer.id, orderId: order.id, vehicleQrCodes: [vehicles[0].qrCode, vehicles[2].qrCode] }, { token: warehouseOperator, code: 'ORDER_SKU_MISMATCH' });
  assert.equal((await req(`/api/orders/${order.id}`, undefined, { token: sales })).shippedQty, 0);
  await expectSku(model.id, red.id, 2, 0, 2, 0); await expectSku(model.id, blue.id, 2, 0, 0, 2);
  await req(`/api/stock-in/${vehicles[0].stockInId}/cancel?reason=不能撤掉已承诺红色库存`, {}, { token: inboundOperator, code: 'SKU_INVENTORY_LOCK_CONFLICT' });
  const shipped = await ship([vehicles[0]]);
  await expectSku(model.id, red.id, 1, 0, 1, 0);
  await req(`/api/stock-out/${shipped.id}/cancel?reason=SKU验收受控撤销`, {}, { token: warehouseOperator });
  await expectSku(model.id, red.id, 2, 0, 2, 0);
  const reshipped = await ship([vehicles[0]]);
  const closed = await req(`/api/orders/${order.id}/close-remainder`, { reason: 'SKU验收关闭未发一台' }, { token: sales }); ordersToCancel.delete(order.id);
  assert.equal(closed.status, 'CLOSED'); assert.equal(closed.shippedQty, 1); assert.equal(closed.orderQty, 2);
  const rejected = await req(`/api/stock-out/${reshipped.id}/cancel?reason=关闭后不得重开`, {}, { token: warehouseOperator, code: 'INVALID_STATUS_TRANSITION' });
  assert(rejected.message.includes('订单已关闭'));
  await expectSku(model.id, red.id, 1, 0, 0, 1); await expectSku(model.id, blue.id, 2, 0, 0, 2);
  pass('最后一车SKU错误整批回滚；撤销入库不能破坏承诺；部分发货、撤销、再发及关闭数量守恒');

  await req('/api/sku-locks', lockBody([{ skuId: blue.id, quantity: 1 }]), { token: reader, code: 'PERMISSION_DENIED' });
  await req('/api/sku-locks', lockBody([{ skuId: blue.id, quantity: 1 }]), { token: external, code: 'FORBIDDEN' });
  assert.equal((await snapshot(model.id, external)).length, 0);
  await req(`/api/master/agents/${agent.id}/model-access`, { modelIds: [model.id] }, { method: 'PUT' });
  assert.equal((await snapshot(model.id, external)).length, 2);
  await req(`/api/master/agents/${agent.id}/model-access`, { modelIds: [] }, { method: 'PUT' });
  assert.equal((await snapshot(model.id, external)).length, 0);
  pass('只读无写权限；误授内部写权限的代理仍被拒，车型授权及撤销对现有会话立即生效');
} catch (error) { evidence.error = error.stack || error.message; console.error(error); process.exitCode = 1; }
finally {
  for (const id of ordersToCancel) try { await req(`/api/orders/${id}/cancel?reason=SKU验收结束清理未完成订单`, {}); } catch(error) { evidence.cleanupFailures.push({ orderId: id, error: error.message }); }
  for (const id of locksToRelease) try { await req(`/api/inventory-locks/${id}/release?reason=SKU验收结束清理锁定`, {}); } catch(error) { evidence.cleanupFailures.push({ lockId: id, error: error.message }); }
  for (const user of accounts) try {
    await req(`/api/users/${user.id}/status?enabled=false`, {}, { method: 'PUT' });
    const response = await fetch(base + '/api/auth/login', { method: 'POST', signal: AbortSignal.timeout(30000), headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: user.username, password: user.password }) });
    assert.notEqual((await response.json()).code, 'SUCCESS', '停用账号仍能登录');
  } catch(error) { evidence.cleanupFailures.push({ userId: user.id, error: error.message }); }
  for (const id of roles) try { await req(`/api/roles/${id}/status?enabled=false`, {}, { method: 'PUT' }); } catch(error) { evidence.cleanupFailures.push({ roleId: id, error: error.message }); }
  for (const station of stations) try { await req(`/api/workstations/${station.id}/status?enabled=false`, {}, { method: 'PUT' }); } catch(error) { evidence.cleanupFailures.push({ workstationId: station.id, error: error.message }); }
  if(evidence.cleanupFailures.length) process.exitCode = 1;
  evidence.finishedAt = new Date().toISOString();
  const output = process.env.BIKE_ACCEPTANCE_OUTPUT || process.env.BIKE_ACCEPTANCE_REPORT;
  if(output) await writeFile(output, JSON.stringify(evidence, null, 2) + '\n');
  console.log(`完成 ${evidence.checks.length} 组；${evidence.error || evidence.cleanupFailures.length ? '失败' : '通过'}。`);
}
