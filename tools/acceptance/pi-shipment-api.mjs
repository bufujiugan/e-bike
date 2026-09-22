import { createPiDocument } from './pi-document-fixture.mjs';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
const base = process.env.BIKE_API_URL || 'http://127.0.0.1:18181';
const prefix = `PI${Date.now().toString(36).toUpperCase()}`;
const evidence = { prefix, startedAt: new Date().toISOString(), checks: [], fixtures: {} };
const accounts = [];
let admin;
async function req(path, body, { method = body === undefined ? 'GET' : 'POST', token = admin, code = 'SUCCESS' } = {}) {
  const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json();
  assert.equal(result.code, code, `${path}: ${response.status} ${result.code} ${result.message}`);
  if (code === 'SUCCESS') assert(response.ok);
  return code === 'SUCCESS' ? result.data : result;
}
function pass(name) { evidence.checks.push({ name, passed: true }); console.log(`PASS ${name}`); }
async function account(roleCode, agentId) {
  const role = (await req('/api/roles')).find(r => r.roleCode === roleCode); assert(role);
  const initial = randomBytes(20).toString('hex'), password = randomBytes(20).toString('hex');
  const username = `${prefix}${roleCode}`;
  const user = await req('/api/users', { username, password: initial, displayName: 'PI验收', userType: agentId ? 'AGENT' : 'WEB_OPERATOR', agentId }); accounts.push(user.id);
  await req(`/api/users/${user.id}/roles`, { roleIds: [role.id] }, { method: 'PUT' });
  let token = (await req('/api/auth/login', { username, password: initial }, { token: null })).accessToken;
  await req('/api/auth/password', { currentPassword: initial, newPassword: password }, { method: 'PUT', token });
  return (await req('/api/auth/login', { username, password }, { token: null })).accessToken;
}

try {
  const login = await req('/api/auth/login', { username: 'admin', password: process.env.BIKE_TEST_PASSWORD }, { token: null });
  assert.equal(login.mustChangePassword, false); admin = login.accessToken;
  const master = (type, suffix) => req(`/api/master/${type}`, { code: prefix + suffix, name: `验收${prefix}${suffix}` });
  const model = await master('models', 'M'), customer = await master('customers', 'C'), agent = await master('agents', 'AG');
  const warehouse = await master('warehouses', 'W');
  const location = await req(`/api/master/warehouses/${warehouse.id}/locations`, { code: prefix + 'L', name: 'PI验收库位' });
  const template = (await req('/api/production/templates'))[0]; assert(template);
  const skus = [];
  for (const suffix of ['A', 'B', 'C']) {
    await req('/api/production/skus', { modelId: model.id, skuCode: prefix + suffix, productName: `电动自行车${suffix}`, specification: '蓝色 / 48V', templateId: template.id, enabled: true });
    skus.push((await req('/api/production/skus')).find(s => s.skuCode === prefix + suffix));
  }
  const vehicles = [];
  for (const [index, sku] of [skus[0], skus[0], skus[1], skus[2]].entries()) {
    const taskId = randomUUID(), tag = `${prefix}V${index}`;
    for (const type of ['MOTOR', 'BATTERY', 'DISPLAY']) await req('/api/parts', { partSn: tag + type, partType: type, qcStatus: 'PASSED' });
    await req('/api/production/tasks', { requestId: taskId, skuId: sku.id, batchNo: prefix });
    for (const type of ['FRAME', 'DISPLAY', 'MOTOR', 'BATTERY']) await req(`/api/production/tasks/${taskId}/scan`, { code: tag + type, expectedType: type });
    const vehicle = await req(`/api/production/tasks/${taskId}`); assert.equal(vehicle.status, 'COMPLETED');
    await req('/api/stock-in', { vehicleQrCode: vehicle.qrCode, warehouseId: warehouse.id, locationId: location.id }); vehicles.push(vehicle);
  }
  const order = await req('/api/orders', { customerId: customer.id, agentId: agent.id, modelId: model.id, orderQty: 2 });
  const document = await createPiDocument(req, prefix, { sellerName: '测试卖方', sellerAddress: '测试地址', buyerAddress: '测试买方地址', paymentTerms: '定金50元，发货前200元', deliveryTerms: '测试仓库交付', templateCode: 'GENERIC-ACCEPTANCE', templateVersion: '1' });
  const piRequest = { customerId: customer.id, agentId: agent.id, sourceOrderId: order.id, tradeType: 'DOMESTIC', currencyCode: 'CNY', depositRequiredAmount: '50.00', shipmentRequiredAmount: '200.00', document, items: skus.slice(0, 2).map(s => ({ skuId: s.id, quantity: 1, unitPrice: '100.00' })) };
  const pi = await req('/api/pis', { ...piRequest, document: null });
  assert.equal(pi.items.length, 2); assert.equal(pi.totalAmount, '200.00');
  assert.equal(typeof pi.items[0].unitPrice, 'string');
  await req(`/api/pis/${pi.id}/confirm`, {}, { code: 'PI_DOCUMENT_INCOMPLETE' });
  const repaired = await req(`/api/pis/${pi.id}`, piRequest, { method: 'PUT' });
  assert.equal(repaired.piNo, pi.piNo); assert.equal(repaired.revision, pi.revision);
  assert.equal(repaired.document.sellerName, document.sellerName);
  evidence.fixtures = { piId: pi.id, orderId: order.id, customerId: customer.id, agentId: agent.id };
  await req(`/api/orders/${order.id}`, { customerId: customer.id, agentId: agent.id, modelId: model.id, orderQty: 1 }, { method: 'PUT', code: 'ORDER_LINKED_TO_PI' });
  await req(`/api/orders/${order.id}/confirm`, {});
  const ship = (selected, code = 'SUCCESS') => req('/api/stock-out', { customerId: customer.id, orderId: order.id, vehicleQrCodes: selected.map(v => v.qrCode) }, { code });
  await ship([vehicles[0]], 'PI_SHIPMENT_NOT_RELEASED');
  pass('真实两行SKU PI创建及草稿门槛阻断旧出库接口，关联订单不可改量');
  const ownAgent = await account('WEB_AGENT', agent.id), operator = await account('WEB_OPERATOR');
  await req(`/api/pis/${pi.id}`, undefined, { token: ownAgent, code: 'FORBIDDEN' });
  const draftList = await req(`/api/pis?keyword=${pi.piNo}`, undefined, { token: ownAgent }); assert.equal(draftList.total, 0);
  await req(`/api/pis/${pi.id}/confirm`, {});
  await req(`/api/pis/${pi.id}`, piRequest, { method: 'PUT', code: 'INVALID_STATUS_TRANSITION' });
  await req(`/api/pis/${pi.id}/payments`, { requestId: randomUUID(), amount: '50.00', currencyCode: 'CNY' }, { token: operator, code: 'PERMISSION_DENIED' });
  const receiptNote = `${prefix}-private-receipt-note`;
  const payment = { requestId: randomUUID(), amount: '50.00', currencyCode: 'CNY', voucherObjectKey: 'acceptance-reference', remark: receiptNote };
  await req(`/api/pis/${pi.id}/payments`, payment);
  const repeated = await req(`/api/pis/${pi.id}/payments`, payment); assert.equal(repeated.paidAmount, '50.00'); assert.equal(repeated.payments.length, 1);
  await req(`/api/pis/${pi.id}/payments`, { ...payment, amount: '51.00' }, { code: 'PAYMENT_REQUEST_CONFLICT' });
  await req(`/api/pis/${pi.id}/cancel`, { reason: '付款后不得直接取消' }, { code: 'PI_HAS_PAYMENTS' });
  await req(`/api/pis/${pi.id}/release`, { reason: '定金不足发货款' }, { code: 'PAYMENT_GATE_NOT_MET' });
  await ship([vehicles[0]], 'PI_SHIPMENT_NOT_RELEASED');
  const agentView = await req(`/api/pis/${pi.id}`, undefined, { token: ownAgent }); assert.equal(agentView.payments.length, 0);
  for (const field of ['paidAmount', 'paymentStatus', 'depositRequiredAmount', 'shipmentRequiredAmount', 'releaseStatus']) assert.equal(agentView[field], null);
  const operatorView = await req(`/api/pis/${pi.id}`, undefined, { token: operator });
  assert.equal(operatorView.payments.length, 0); assert.equal(operatorView.paidAmount, null); assert.equal(operatorView.paymentStatus, null);
  assert.equal((await req(`/api/modules/logs?keyword=${encodeURIComponent(receiptNote)}`, undefined, { token: operator })).total, 0);
  assert.equal((await req(`/api/modules/operation-logs?keyword=${encodeURIComponent(receiptNote)}`, undefined, { token: operator })).total, 0);
  assert.equal((await req(`/api/modules/logs?keyword=${encodeURIComponent(receiptNote)}`)).total, 1);
  const operatorDashboard = await req('/api/dashboard/summary', undefined, { token: operator });
  assert(!operatorDashboard.recentOperations.some(row => ['登记 PI 收款', '放行 PI 发货', '收款达标保留订单占用', '收款已达条件但占用已到期'].includes(row.action)));
  pass('草稿可修复且确认后不可改；代理商无草稿或财务明细；普通操作员无财务授权；收款重试幂等且冲突拒绝，定金不等于发货许可');
  const cancelledOrder = await req('/api/orders', { customerId: customer.id, agentId: agent.id, modelId: model.id, orderQty: 1 });
  const cancelRequest = { ...piRequest, sourceOrderId: cancelledOrder.id, depositRequiredAmount: '0.00', shipmentRequiredAmount: '100.00', items: [piRequest.items[0]] };
  const cancelledPi = await req('/api/pis', cancelRequest);
  await req(`/api/pis/${cancelledPi.id}/confirm`, {});
  const cancellation = await req(`/api/pis/${cancelledPi.id}/cancel`, { reason: '专用验收：未付款取消' });
  assert.equal(cancellation.status, 'CANCELLED'); assert.equal(cancellation.releaseStatus, 'BLOCKED'); assert.equal(cancellation.sourceOrderId, cancelledOrder.id);
  await req('/api/pis', cancelRequest, { code: 'ORDER_PI_EXISTS' });
  await req(`/api/orders/${cancelledOrder.id}/confirm`, {});
  await req('/api/stock-out', { customerId: customer.id, orderId: cancelledOrder.id, vehicleQrCodes: [vehicles[0].qrCode] }, { code: 'PI_SHIPMENT_NOT_RELEASED' });
  pass('未付款PI可取消且保留来源关联，重建PI或旧订单出库均不能绕过取消门槛');
  await req(`/api/pis/${pi.id}/payments`, { requestId: randomUUID(), amount: '150.00', currencyCode: 'CNY' });
  await ship([vehicles[0]], 'PI_SHIPMENT_NOT_RELEASED');
  await req(`/api/pis/${pi.id}/release`, { reason: '专用测试：达到约定发货门槛' });
  await ship([vehicles[3]], 'PI_SKU_MISMATCH');
  await ship([vehicles[0], vehicles[1]], 'PI_SKU_QTY_EXCEEDED');
  const rolledBack = await req(`/api/orders/${order.id}`); assert.equal(rolledBack.shippedQty, 0); assert.equal(rolledBack.shippedVehicles.length, 0);
  const stock = await req(`/api/production/inventory?skuId=${skus[0].id}`); assert.equal(stock.reduce((sum, x) => sum + Number(x.physicalQty), 0), 2);
  pass('足额收款仍需放行；同车型错误SKU被拒绝；批次第二车超SKU数量时整批库存和订单回滚');
  const firstShipment = await ship([vehicles[0], vehicles[2]]);
  assert.equal((await req(`/api/orders/${order.id}`)).status, 'SHIPPED');
  await req(`/api/stock-out/${firstShipment.id}/cancel?reason=${encodeURIComponent('PI测试撤销')}`, {});
  assert.equal((await req(`/api/orders/${order.id}`)).shippedQty, 1);
  await ship([vehicles[0]]);
  assert.equal((await req(`/api/orders/${order.id}`)).shippedQty, 2);
  const actualBatch = (await req(`/api/pis/${pi.id}`)).dispatchBatches.find(batch => batch.activeQty > 0); assert(actualBatch);
  const logistics = await req(`/api/pis/${pi.id}/logistics`, { requestId: randomUUID(), stockOutBatchId: actualBatch.id, batchNo: actualBatch.batchNo, carrier: '验收承运商', trackingNo: prefix + 'TRACK', status: 'SHIPPED' });
  assert.equal(logistics.logistics.length, 1); assert.equal(logistics.logistics[0].status, 'SHIPPED');
  pass('正确SKU整批出库，撤销后释放对应SKU已发数量并可重新出库');
  for (const extension of ['pdf', 'xlsx']) {
    const response = await fetch(`${base}/api/pis/${pi.id}/export.${extension}`, { headers: { Authorization: `Bearer ${admin}` } });
    assert.equal(response.status, 200); const bytes = Buffer.from(await response.arrayBuffer());
    assert(bytes.length > 1000); if (extension === 'pdf') assert.equal(bytes.subarray(0, 4).toString(), '%PDF');
    if (process.env.BIKE_ACCEPTANCE_OUTPUT) await writeFile(`${process.env.BIKE_ACCEPTANCE_OUTPUT}.${extension}`, bytes);
  }
  pass('真实API导出PDF/Excel成功返回文件，PDF文件头正确');
} catch (error) { evidence.error = error.message; process.exitCode = 1; console.error(error); }
finally {
  evidence.cleanupFailures = [];
  for (const id of accounts) try { await req(`/api/users/${id}/status?enabled=false`, {}, { method: 'PUT' }); }
  catch (error) { evidence.cleanupFailures.push({ userId: id, error: error.message }); process.exitCode = 1; }
  evidence.finishedAt = new Date().toISOString();
  if (process.env.BIKE_ACCEPTANCE_OUTPUT) await writeFile(process.env.BIKE_ACCEPTANCE_OUTPUT, JSON.stringify(evidence, null, 2));
}
