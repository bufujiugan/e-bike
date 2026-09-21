import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
if (!process.env.BIKE_TEST_PASSWORD) throw new Error('需要验收管理员密码');
const base = process.env.BIKE_API_URL || 'http://127.0.0.1:18181';
const prefix = `PR${Date.now().toString(36).toUpperCase()}`;
const output = process.env.BIKE_ACCEPTANCE_OUTPUT || process.env.BIKE_ACCEPTANCE_REPORT;
const lifetime = Number(process.env.BIKE_ACCEPTANCE_RESERVATION_SECONDS || 40);
const serverOffset = Number(process.env.BIKE_SERVER_UTC_OFFSET_MINUTES || 480);
assert(Number.isInteger(lifetime) && lifetime >= 15 && lifetime <= 120, '锁定期限须为15至120秒');
assert(Number.isFinite(serverOffset) && Math.abs(serverOffset) <= 840, '服务器时区偏移无效');
const evidence = { prefix, startedAt: new Date().toISOString(), checks: [], fixtures: {}, cleanupFailures: [] };
const users = [], roles = [];
let admin, clockOffset = 0;

async function req(path, body, { method = body === undefined ? 'GET' : 'POST', token = admin, code = 'SUCCESS' } = {}) {
  const response = await fetch(base + path, {
    method, signal: AbortSignal.timeout(30_000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!admin && response.headers.get('date')) clockOffset = Date.parse(response.headers.get('date')) - Date.now();
  const result = await response.json();
  assert.equal(result.code, code, `${path}: ${response.status} ${result.code} ${result.message}`);
  if (code === 'SUCCESS') assert(response.ok, `${path}: HTTP ${response.status}`);
  return code === 'SUCCESS' ? result.data : result;
}

function pass(name, details) {
  evidence.checks.push({ name, passed: true, ...(details ? { details } : {}) });
  console.log(`PASS ${name}`);
}

function futureLocalDate(seconds) {
  return new Date(Date.now() + clockOffset + seconds * 1000 + serverOffset * 60_000).toISOString().slice(0, 19);
}

async function account(suffix, permissionCodes, agentId = null) {
  const permissions = await req('/api/roles/permissions');
  const role = await req('/api/roles', { roleCode: prefix + suffix, roleName: `占用验收${suffix}` });
  roles.push(role.id);
  const permissionIds = permissionCodes.map(code => {
    const permission = permissions.find(p => p.permissionCode === code);
    assert(permission, `权限不存在 ${code}`);
    return permission.id;
  });
  await req(`/api/roles/${role.id}/permissions`, { permissionIds }, { method: 'PUT' });
  const username = prefix + suffix, initial = randomBytes(24).toString('hex'), password = randomBytes(24).toString('hex');
  const user = await req('/api/users', { username, password: initial, displayName: '占用验收', userType: agentId ? 'AGENT' : 'WEB_OPERATOR', agentId });
  users.push(user.id);
  await req(`/api/users/${user.id}/roles`, { roleIds: [role.id] }, { method: 'PUT' });
  const first = await req('/api/auth/login', { username, password: initial }, { token: null });
  await req('/api/auth/password', { currentPassword: initial, newPassword: password }, { method: 'PUT', token: first.accessToken });
  const token = (await req('/api/auth/login', { username, password }, { token: null })).accessToken;
  const context = await req('/api/auth/context', undefined, { token });
  assert(!context.roles.includes('SUPER_ADMIN'));
  return token;
}

async function inventory(modelId) {
  const page = await req(`/api/inventory?modelId=${modelId}&pageSize=100`);
  assert(page.total <= 100);
  return page.records.reduce((sum, row) => {
    for (const key of Object.keys(sum)) sum[key] += Number(row[key]);
    return sum;
  }, { physicalQty: 0, temporaryLockedQty: 0, orderReservedQty: 0, lockedQty: 0, availableQty: 0, shortageQty: 0 });
}

async function expectInventory(modelId, physical, reserved, available) {
  assert.deepEqual(await inventory(modelId), { physicalQty: physical, temporaryLockedQty: 0, orderReservedQty: reserved, lockedQty: reserved, availableQty: available, shortageQty: 0 });
}

async function agentOrderViews(token, order, lock, pi, state = 'ACTIVE') {
  const views = [await req(`/api/orders/${order.id}`, undefined, { token })];
  const page = await req(`/api/orders?keyword=${order.orderNo}`, undefined, { token });
  assert.equal(page.total, 1); views.push(page.records[0]);
  const linked = await req(`/api/inventory-locks/${lock.id}/orders`, undefined, { token });
  assert.equal(linked.total, 1); views.push(linked.records[0]);
  for (const view of views) {
    assert.equal(view.reservationStatus, state);
    for (const key of ['reservationHoldMode', 'reservationHeldAt', 'reservationExpiresAt']) assert.equal(view[key], null, `代理商 ${key} 泄露`);
  }
  const module = await req(`/api/modules/orders?keyword=${order.orderNo}`, undefined, { token });
  assert.equal(module.total, 1);
  const deadline = module.rows[0][module.columns.indexOf('占用截止')];
  assert(deadline == null || deadline === '' || deadline === '-', '通用订单列表泄露占用截止');
  if (state === 'ACTIVE') assert.equal(module.rows[0][module.columns.indexOf('占用状态')], '有效');
  const externalPi = await req(`/api/pis/${pi.id}`, undefined, { token });
  assert.equal(externalPi.reservationStatus, state);
  assert.deepEqual(externalPi.payments, []);
  for (const key of ['paidAmount', 'paymentStatus', 'depositRequiredAmount', 'shipmentRequiredAmount', 'releaseStatus']) assert.equal(externalPi[key], null);
}

async function waitForExpiry(orderId) {
  const stop = Date.now() + (lifetime + 20) * 1000;
  while (Date.now() < stop) {
    const order = await req(`/api/orders/${orderId}`);
    if (order.reservationStatus === 'EXPIRED_REQUIRES_REACQUIRE') return order;
    assert.equal(order.reservationStatus, 'TIMED_ACTIVE');
    console.log(`WAIT 真实锁定尚未到期，5秒后复查；截止 ${order.reservationExpiresAt}`);
    await new Promise(resolve => setTimeout(resolve, 5000));
  }
  throw new Error('未在预期窗口观察到真实占用到期，请检查服务器时区配置');
}

try {
  const login = await req('/api/auth/login', { username: process.env.BIKE_TEST_USERNAME || 'admin', password: process.env.BIKE_TEST_PASSWORD }, { token: null });
  assert.equal(login.mustChangePassword, false); admin = login.accessToken;
  const master = (type, suffix) => req(`/api/master/${type}`, { code: prefix + suffix, name: `占用验收${prefix}${suffix}` });
  const model = await master('models', 'M'), customer = await master('customers', 'C'), agent = await master('agents', 'AG');
  const warehouse = await master('warehouses', 'W');
  const location = await req(`/api/master/warehouses/${warehouse.id}/locations`, { code: prefix + 'L', name: '占用验收库位' });
  const template = (await req('/api/production/templates'))[0]; assert(template);
  await req('/api/production/skus', { modelId: model.id, skuCode: prefix + 'SKU', productName: '占用验收电动车', specification: '蓝色48V', templateId: template.id, enabled: true });
  const sku = (await req('/api/production/skus')).find(row => row.skuCode === prefix + 'SKU'); assert(sku);
  const ownAgent = await account('AGENT', ['PI_READ', 'ORDER_READ', 'AGENT_INVENTORY_READ'], agent.id);
  const ordinary = await account('READER', ['PI_READ', 'ORDER_READ', 'DATA_EXPORT', 'DASHBOARD_READ', 'TRACE_READ']);
  const vehicles = [];
  for (let index = 0; index < 4; index++) {
    const taskId = randomUUID(), tag = `${prefix}V${index}`;
    for (const type of ['MOTOR', 'BATTERY', 'DISPLAY']) await req('/api/parts', { partSn: tag + type, partType: type, qcStatus: 'PASSED' });
    await req('/api/production/tasks', { requestId: taskId, skuId: sku.id, batchNo: prefix });
    for (const type of ['FRAME', 'DISPLAY', 'MOTOR', 'BATTERY']) await req(`/api/production/tasks/${taskId}/scan`, { code: tag + type, expectedType: type });
    const vehicle = await req(`/api/production/tasks/${taskId}`); assert.equal(vehicle.status, 'COMPLETED');
    const stockIn = await req('/api/stock-in', { vehicleQrCode: vehicle.qrCode, warehouseId: warehouse.id, locationId: location.id });
    vehicles.push({ ...vehicle, stockInId: stockIn.id, frameSn: tag + 'FRAME' });
  }
  evidence.fixtures = { modelId: model.id, skuId: sku.id, customerId: customer.id, agentId: agent.id, warehouseId: warehouse.id, locationId: location.id, vehicles: vehicles.map(v => ({ taskId: v.id, frameSn: v.frameSn, stockInId: v.stockInId })) };
  await expectInventory(model.id, 4, 0, 4);
  pass('全新专用车型、真实SKU和四台四码生产入库车辆，读者账号无财务权限');

  const expires = futureLocalDate(lifetime);
  const createReservedPi = async suffix => {
    const lock = await req('/api/inventory-locks', { agentId: agent.id, productModelId: model.id, lockQty: 2, expireTime: expires, remark: prefix + suffix });
    const order = await req(`/api/inventory-locks/${lock.id}/orders`, { customerId: customer.id, quantity: 2, requestId: randomUUID() });
    await req(`/api/orders/${order.id}/confirm`, {});
    const pi = await req('/api/pis', { customerId: customer.id, agentId: agent.id, sourceOrderId: order.id, tradeType: 'DOMESTIC', currencyCode: 'CNY', depositRequiredAmount: '50.00', shipmentRequiredAmount: '200.00', document: { sellerName: '占用测试卖方', paymentTerms: '定金50元保留库存，发货前200元', deliveryTerms: '测试仓库交付', templateCode: 'GENERIC-ACCEPTANCE', templateVersion: '1' }, items: [{ skuId: sku.id, quantity: 2, unitPrice: '100.00' }] });
    await req(`/api/pis/${pi.id}/confirm`, {});
    return { lock, order, pi };
  };
  const paid = await createReservedPi('PAID'), late = await createReservedPi('LATE');
  evidence.fixtures.orders = [paid, late].map(row => ({ lockId: row.lock.id, orderId: row.order.id, orderNo: row.order.orderNo, piId: row.pi.id, piNo: row.pi.piNo }));
  evidence.fixtures.originalDeadline = expires;
  await expectInventory(model.id, 4, 4, 0);
  await agentOrderViews(ownAgent, paid.order, paid.lock, paid.pi);
  const first = await req(`/api/pis/${paid.pi.id}/payments`, { requestId: randomUUID(), amount: '49.99', currencyCode: 'CNY' });
  assert.equal(first.reservationStatus, 'TIMED_ACTIVE');
  const receiptNote = `${prefix}-private-receipt`;
  const deposit = { requestId: randomUUID(), amount: '0.01', currencyCode: 'CNY', remark: receiptNote };
  const held = await req(`/api/pis/${paid.pi.id}/payments`, deposit);
  assert.equal(held.paidAmount, '50.00'); assert.equal(held.reservationStatus, 'PAID_HELD');
  const retry = await req(`/api/pis/${paid.pi.id}/payments`, deposit);
  assert.equal(retry.paidAmount, '50.00'); assert.equal(retry.payments.length, 2);
  const heldOrder = await req(`/api/orders/${paid.order.id}`);
  assert.equal(heldOrder.reservationHoldMode, 'PAID'); assert(heldOrder.reservationExpiresAt);
  await agentOrderViews(ownAgent, paid.order, paid.lock, paid.pi);
  pass('差0.01元仍限时，截止前刚达定金转保留；重试不重复收款；代理商所有订单入口统一隐藏方式及截止');

  await waitForExpiry(late.order.id);
  assert.equal((await req(`/api/pis/${paid.pi.id}`)).reservationStatus, 'PAID_HELD');
  await expectInventory(model.id, 4, 2, 2);
  await agentOrderViews(ownAgent, paid.order, paid.lock, paid.pi);
  const unchanged = await req(`/api/orders/${paid.order.id}/reservation/renew`, { hours: 1, reason: '验收已付款占用不降级' });
  assert.equal(unchanged.reservationHoldMode, 'PAID');
  assert.equal(unchanged.reservationExpiresAt, heldOrder.reservationExpiresAt);
  assert.equal(unchanged.reservationHeldAt, heldOrder.reservationHeldAt);
  pass('跨原始截止PAID仍占两台，未付款订单即时失效；PAID续期不降级或改写截止');

  const lateNote = `${prefix}-late-private-receipt`;
  const latePayment = { requestId: randomUUID(), amount: '200.00', currencyCode: 'CNY', receivedAt: '2020-01-01T00:00:00', remark: lateNote };
  const savedLate = await req(`/api/pis/${late.pi.id}/payments`, latePayment);
  assert.equal(savedLate.paidAmount, '200.00'); assert.equal(savedLate.paymentStatus, 'PAID');
  assert.equal(savedLate.reservationStatus, 'EXPIRED_REQUIRES_REACQUIRE');
  const lateRetry = await req(`/api/pis/${late.pi.id}/payments`, latePayment);
  assert.equal(lateRetry.payments.length, 1); assert.equal(lateRetry.reservationStatus, 'EXPIRED_REQUIRES_REACQUIRE');
  await req(`/api/pis/${late.pi.id}/release`, { reason: '已达款但来源占用失效' }, { code: 'ORDER_RESERVATION_EXPIRED' });
  await expectInventory(model.id, 4, 2, 2);
  await req(`/api/stock-in/${vehicles[3].stockInId}/cancel?reason=${encodeURIComponent('验收制造真实合格库存不足')}`, {});
  await expectInventory(model.id, 3, 2, 1);
  await req(`/api/orders/${late.order.id}/reservation/renew`, { hours: 1, reason: '库存不足须拒绝重新占用' }, { code: 'INVENTORY_NOT_ENOUGH' });
  const rejected = await req(`/api/pis/${late.pi.id}`);
  assert.equal(rejected.paidAmount, '200.00'); assert.equal(rejected.payments.length, 1); assert.equal(rejected.reservationStatus, 'EXPIRED_REQUIRES_REACQUIRE');
  await req('/api/stock-in', { vehicleQrCode: vehicles[3].qrCode, warehouseId: warehouse.id, locationId: location.id });
  const reacquired = await req(`/api/orders/${late.order.id}/reservation/renew`, { hours: 1, reason: '重新入库补足，显式办理占用' });
  assert.equal(reacquired.reservationHoldMode, 'PAID'); assert.equal(reacquired.reservationStatus, 'PAID_HELD');
  await expectInventory(model.id, 4, 4, 0);
  pass('晚录倒填到账保留财务事实且不复活；库存不足重占失败不丢款，真实补库后显式重占才转PAID');

  await req(`/api/pis/${paid.pi.id}/payments`, { requestId: randomUUID(), amount: '150.00', currencyCode: 'CNY' });
  const releaseNote = `${prefix}-private-release`;
  await req(`/api/pis/${paid.pi.id}/release`, { reason: releaseNote });
  const ship = selected => req('/api/stock-out', { customerId: customer.id, orderId: paid.order.id, vehicleQrCodes: selected.map(v => v.qrCode) });
  const partial = await ship([vehicles[0]]);
  await expectInventory(model.id, 3, 3, 0);
  assert.equal((await req(`/api/orders/${paid.order.id}`)).shippedQty, 1);
  await req(`/api/stock-out/${partial.id}/cancel?reason=${encodeURIComponent('跨原截止撤销恢复占用')}`, {});
  await expectInventory(model.id, 4, 4, 0);
  assert.equal((await req(`/api/orders/${paid.order.id}`)).shippedQty, 0);
  const completeShipment = await ship([vehicles[0], vehicles[1]]);
  const complete = await req(`/api/orders/${paid.order.id}`);
  assert.equal(complete.status, 'SHIPPED'); assert.equal(complete.reservationStatus, 'FULFILLED'); assert.equal(complete.reservationHoldMode, 'PAID');
  await expectInventory(model.id, 2, 2, 0);
  await req(`/api/stock-out/${completeShipment.id}/cancel?reason=${encodeURIComponent('全部出库后受控撤销一台')}`, {});
  await expectInventory(model.id, 3, 3, 0);
  const restored = await req(`/api/orders/${paid.order.id}`);
  assert.equal(restored.shippedQty, 1); assert.equal(restored.reservationStatus, 'PAID_HELD');
  const remainingShipment = await req(`/api/stock-out/by-no/${restored.shippedVehicles[0].stockOutNo}`);
  const closed = await req(`/api/orders/${paid.order.id}/close-remainder`, { reason: '验收关闭未发剩余量' });
  assert.equal(closed.status, 'CLOSED'); assert.equal(closed.shippedQty, 1); assert.equal(closed.orderQty, 2); assert.equal(closed.reservationStatus, 'RELEASED');
  await expectInventory(model.id, 3, 2, 1);
  const closedShipment = await req(`/api/stock-out/${remainingShipment.id}/cancel?reason=${encodeURIComponent('已关闭不能重开')}`, {}, { code: 'INVALID_STATUS_TRANSITION' });
  assert(closedShipment.message.includes('订单已关闭'));
  pass('跨截止实际部分出库/撤销守恒；全部出库保留PAID方式，撤销恢复占用，关闭剩余只释放未发量且阻止重开');

  for (const note of [receiptNote, lateNote, releaseNote]) {
    for (const module of ['logs', 'operation-logs']) {
      assert.equal((await req(`/api/modules/${module}?keyword=${encodeURIComponent(note)}`, undefined, { token: ordinary })).total, 0);
      assert.equal((await req(`/api/modules/${module}?keyword=${encodeURIComponent(note)}`)).total, 1);
    }
  }
  for (const action of ['收款达标保留订单占用', '收款已达条件但占用已到期']) {
    assert.equal((await req(`/api/modules/logs?keyword=${encodeURIComponent(action)}`, undefined, { token: ordinary })).total, 0);
    assert((await req(`/api/modules/logs?keyword=${encodeURIComponent(action)}`)).total > 0);
  }
  const dashboard = await req('/api/dashboard/summary', undefined, { token: ordinary });
  assert(!dashboard.recentOperations.some(row => ['登记 PI 收款', '放行 PI 发货', '收款达标保留订单占用', '收款已达条件但占用已到期'].includes(row.action)));
  const trace = await req(`/api/trace/frame/${vehicles[0].frameSn}`, undefined, { token: ordinary });
  for (const note of [receiptNote, lateNote, releaseNote]) assert(!JSON.stringify(trace).includes(note));
  const ordinaryPi = await req(`/api/pis/${late.pi.id}`, undefined, { token: ordinary });
  assert.equal(ordinaryPi.paidAmount, null); assert.equal(ordinaryPi.paymentStatus, null); assert.deepEqual(ordinaryPi.payments, []);
  await req(`/api/pis/${late.pi.id}/payments`, { requestId: randomUUID(), amount: '0.01', currencyCode: 'CNY' }, { token: ordinary, code: 'PERMISSION_DENIED' });
  await req(`/api/orders/${late.order.id}/reservation/renew`, { hours: 1, reason: '代理商不能重新占用' }, { token: ownAgent, code: 'PERMISSION_DENIED' });
  pass('普通角色不能查看或搜索收款、放行和收款保留日志；看板与追溯无财务备注；代理商不可重占');
  await req(`/api/orders/${late.order.id}/cancel?reason=${encodeURIComponent('验收取消未发货订单，收款事实保留')}`, {});
  assert.equal((await req(`/api/pis/${late.pi.id}`)).paidAmount, '200.00');
  assert.equal((await req(`/api/orders/${late.order.id}`)).reservationStatus, 'RELEASED');
  await expectInventory(model.id, 3, 0, 3);
  pass('取消未发订单释放保留数量，已登记收款继续保留，独立车型最终数量守恒');
} catch (error) {
  evidence.error = error.stack || error.message;
  process.exitCode = 1;
  console.error(error);
} finally {
  for (const id of users) {
    try { await req(`/api/users/${id}/status?enabled=false`, {}, { method: 'PUT' }); }
    catch (error) { evidence.cleanupFailures.push({ userId: id, error: error.message }); process.exitCode = 1; }
  }
  for (const id of roles) {
    try { await req(`/api/roles/${id}/status?enabled=false`, {}, { method: 'PUT' }); }
    catch (error) { evidence.cleanupFailures.push({ roleId: id, error: error.message }); process.exitCode = 1; }
  }
  evidence.finishedAt = new Date().toISOString();
  if (output) await writeFile(output, JSON.stringify(evidence, null, 2) + '\n');
  console.log(`完成 ${evidence.checks.length} 项；${evidence.error || evidence.cleanupFailures.length ? '失败' : '通过'}；专用验收数据保留供复核，测试账号与角色已请求停用。`);
}
