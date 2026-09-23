import { createPiDocument } from './pi-document-fixture.mjs';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
if (!process.env.BIKE_TEST_PASSWORD) throw new Error('缺少验收管理员密码');
const base = process.env.BIKE_API_URL || 'http://127.0.0.1:18181';
assert(['127.0.0.1', 'localhost', '[::1]'].includes(new URL(base).hostname), '仅允许本机隔离实例');
const prefix = `PIC${Date.now().toString(36).toUpperCase()}`;
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
async function uploadFile(path, bytes, { token = admin, requestId = randomUUID(), visibility = 'CUSTOMER', code = 'SUCCESS' } = {}) {
  const form = new FormData(); form.append('file', new Blob([bytes], { type: 'image/png' }), 'confirmation.png'); form.append('requestId', requestId); form.append('visibility', visibility);
  const response = await fetch(base + path, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form, signal: AbortSignal.timeout(30000) });
  const result = await response.json(); assert.equal(result.code, code, `${path}: ${response.status} ${result.code} ${result.message}`); assert.equal(response.ok, code === 'SUCCESS'); return result.data;
}
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
  const secondModel = await master('models', 'M2');
  const model = await master('models', 'M'), customer = await master('customers', 'C'), otherCustomer = await master('customers', 'C2'), agent = await master('agents', 'A');
  const warehouse = await master('warehouses', 'W');
  const location = await req(`/api/master/warehouses/${warehouse.id}/locations`, { code: prefix + 'L', name: 'SKU验收库位' });
  const template = (await req('/api/production/templates'))[0]; assert(template);
  const skus = [];
  for (const color of ['RED', 'BLUE']) {
    await req('/api/production/skus', { modelId: color === 'RED' ? model.id : secondModel.id, skuCode: prefix + color, productName: 'SKU验收车辆', specification: color, templateId: template.id, enabled: true });
    skus.push((await req('/api/production/skus')).find(s => s.skuCode === prefix + color));
  }
  const [red, blue] = skus; assert(red && blue);
  const imageBytes = await readFile(new URL('./fixtures/after-sales/fault.png', import.meta.url));
  const productImage = await uploadFile(`/api/production/skus/${red.id}/images`, imageBytes);
  const sales = await account('SALES', ['PI_READ', 'PI_WRITE', 'PI_EXPORT', 'AGENT_LOCK_WRITE', 'ORDER_READ', 'ORDER_WRITE', 'INVENTORY_READ']);
  for (const type of ['STOCK_IN', 'STOCK_OUT']) stations.push(await req('/api/workstations', { workstationCode: prefix + type, workstationName: 'SKU验收' + type, workstationType: type }));
  const warehouseOperator = await account('WAREHOUSE', ['STOCK_OUT_OPERATE', 'ORDER_READ', 'INVENTORY_READ', 'TRACE_READ'], null, stations[1].id);
  const inboundOperator = await account('INBOUND', ['STOCK_IN_OPERATE', 'INVENTORY_READ'], null, stations[0].id);
  const reader = await account('READER', ['PI_READ', 'INVENTORY_READ']);
  const finance = await account('FINANCE', ['PI_READ', 'PI_PAYMENT_CONFIRM', 'PI_RELEASE']);
  const logisticsOperator = await account('LOGISTICS', ['PI_READ', 'PI_LOGISTICS_WRITE']);
  const external = await account('AGENT', ['PI_READ', 'PI_WRITE', 'PI_EXPORT', 'PI_TEMPLATE_MANAGE', 'PI_PAYMENT_REVERSE', 'AGENT_INVENTORY_READ', 'AGENT_LOCK_WRITE', 'ORDER_WRITE', 'ORDER_READ'], agent.id);
  const paymentCorrector = await account('CORRECTOR', ['PI_READ', 'PI_PAYMENT_REVERSE']);
  const customerManager = await account('CUSTOMER', ['CUSTOMER_PROFILE_WRITE']);
  const profilePath = `/api/master/customers/${customer.id}/profile`;
  assert.equal((await req(profilePath, undefined, { token: sales })).revision, 0);
  const customerProfile = { revision: 0, address: '原客户商务地址', country: 'DE', email: 'buyer@example.invalid', preferredCurrency: 'eur', businessOwner: '出口业务验收组', reason: '隔离验收客户资料' };
  await req(profilePath, customerProfile, { method: 'PUT', token: sales, code: 'PERMISSION_DENIED' });
  await req(profilePath, undefined, { token: warehouseOperator, code: 'PERMISSION_DENIED' });
  await req(profilePath, undefined, { token: external, code: 'FORBIDDEN' });
  await req(profilePath, customerProfile, { method: 'PUT', token: external, code: 'FORBIDDEN' });
  const savedProfile = await req(profilePath, customerProfile, { method: 'PUT', token: customerManager });
  await req('/api/modules/customers', undefined, { token: customerManager });
  assert.equal(savedProfile.preferredCurrency, 'EUR'); assert.equal(savedProfile.revision, 1);
  await req(profilePath, { ...customerProfile, address: '过期覆盖' }, { method: 'PUT', token: customerManager, code: 'CUSTOMER_PROFILE_CONFLICT' });
  await req(profilePath, { ...customerProfile, revision: 1, preferredCurrency: 'ZZZ' }, { method: 'PUT', token: customerManager, code: 'CUSTOMER_PROFILE_INVALID' });
  pass('客户商务资料独立权限、仓库和代理拒绝读取，币种规范化和过期版本拒绝覆盖');
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
  const sourceLocks = await req('/api/sku-locks', { requestId: randomUUID(), customerId: customer.id, agentId: agent.id, items: skus.map(s => ({ skuId: s.id, quantity: 2 })) }, { token: sales });
  sourceLocks.forEach(l => locksToRelease.add(l.id));
  const choices = await req(`/api/pis/lock-options?customerId=${customer.id}&agentId=${agent.id}&keyword=${prefix}`, undefined, { token: sales });
  assert.equal(choices.total, 2);
  assert.equal((await req(`/api/pis/lock-options?customerId=${otherCustomer.id}&agentId=${agent.id}`, undefined, { token: sales })).total, 0);
  const body = { requestId: randomUUID(), sources: sourceLocks.map(l => ({ lockId: l.id, quantity: 1 })), pi: {
    customerId: customer.id, agentId: agent.id, tradeType: 'DOMESTIC', currencyCode: 'CNY', depositRequiredAmount: '50.00', shipmentRequiredAmount: '200.00',
    document: await createPiDocument(req, prefix, { sellerName: '隔离测试卖方', paymentTerms: '50元保留/200元发货', deliveryTerms: '测试仓库交付', templateCode: 'ACCEPTANCE', templateVersion: '1' }),
    items: skus.map(s => ({ skuId: s.id, quantity: 1, unitPrice: '100.00', unit: 'PCS' })), otherFeeAmount: '12.34',
  } };
  Object.assign(body.pi.document, { buyerAddress: '', buyerCountry: '', shippingAddress: '' });
  const publishRequest = { ...body.pi.document, tradeType: 'DOMESTIC', layoutCode: 'DOMESTIC_V1', reason: '模板权限验证' };
  await req('/api/pi-templates', publishRequest, { token: sales, code: 'PERMISSION_DENIED' });
  await req('/api/pi-templates', publishRequest, { token: external, code: 'FORBIDDEN' });
  await req('/api/pi-templates', undefined, { token: external, code: 'FORBIDDEN' });
  await req('/api/pi-templates', publishRequest, { code: 'PI_TEMPLATE_EXISTS' });
  await req('/api/pis/from-locks', { ...body, pi: { ...body.pi, document: { ...body.pi.document, bankAccount: 'TAMPERED' } } }, { token: sales, code: 'PI_TEMPLATE_SNAPSHOT_MISMATCH' });
  await req('/api/pis/from-locks', { ...body, pi: { ...body.pi, document: { ...body.pi.document, templateVersion: 'UNPUBLISHED' } } }, { token: sales, code: 'PI_TEMPLATE_NOT_PUBLISHED' });
  pass('模板发布独立权限及外部误授权拒绝，已发布版本不可覆盖，银行改写和未发布模板不能创建PI');
  await req('/api/pis/from-locks', body, { token: reader, code: 'PERMISSION_DENIED' });
  await req('/api/pis/from-locks', body, { token: external, code: 'FORBIDDEN' });
  await req(`/api/pis/lock-options?customerId=${customer.id}`, undefined, { token: external, code: 'FORBIDDEN' });
  await req('/api/pis/from-locks', { ...body, pi: { ...body.pi, customerId: otherCustomer.id } }, { token: sales, code: 'LOCK_CUSTOMER_MISMATCH' });
  await req('/api/pis/from-locks', { ...body, pi: { ...body.pi, depositRequiredAmount: '999.00' } }, { token: sales, code: 'AMOUNT_INVALID' });
  for (const l of sourceLocks) { const saved = await req(`/api/inventory-locks/by-no/${l.lockNo}`, undefined, { token: sales }); assert.equal(saved.convertedQty, 0); }
  pass('普通岗位按客户代理查可用锁定，权限与跨客户拒绝，后置PI金额失败全部撤回转换');

  const results = await Promise.all([req('/api/pis/from-locks', body, { token: sales }), req('/api/pis/from-locks', { ...body, sources: [...body.sources].reverse() }, { token: sales })]);
  const pi = results[0]; assert.equal(pi.id, results[1].id); assert.equal(pi.fulfillment.length, 2); assert(pi.seriesId);
  assert.equal(pi.document.buyerAddress, customerProfile.address); assert.equal(pi.document.shippingAddress, customerProfile.address);
  assert.equal(pi.document.buyerCountry, customerProfile.country); assert.equal(pi.document.buyerEmail, customerProfile.email); assert.equal(pi.document.businessOwner, customerProfile.businessOwner);
  await req(profilePath, { ...customerProfile, revision: 1, address: '修改后的客户商务地址', email: 'new-buyer@example.invalid' }, { method: 'PUT', token: customerManager });
  const originalSnapshot = (await req(`/api/pis/${pi.id}`, undefined, { token: sales })).document;
  assert.equal(originalSnapshot.buyerAddress, customerProfile.address); assert.equal(originalSnapshot.buyerEmail, customerProfile.email);
  pass('新PI自动带入客户商务资料并形成快照，主资料修改不覆盖已保存PI');
  assert.equal(pi.totalAmount, '212.34'); assert.equal(pi.otherFeeAmount, '12.34'); assert(pi.items.every(i => i.unit === 'PCS'));
  const savedImage = pi.items.find(i => i.skuId === red.id); assert.equal(savedImage.imageAttachmentId, productImage.id);
  assert.equal(savedImage.imageSha256, createHash('sha256').update(imageBytes).digest('hex'));
  pi.fulfillment.forEach(line => ordersToCancel.add(line.orderId));
  evidence.fixtures.piId = pi.id; evidence.fixtures.orders = pi.fulfillment;
  await req('/api/pis/from-locks', { ...body, pi: { ...body.pi, remark: '同请求改变内容' } }, { token: sales, code: 'PI_CONVERSION_CONFLICT' });
  const draftAgent = await req(`/api/pis?keyword=${pi.piNo}`, undefined, { token: external }); assert.equal(draftAgent.total, 0);
  await req(`/api/pis/${pi.id}`, undefined, { token: external, code: 'FORBIDDEN' });
  body.pi.document = originalSnapshot;
  await req(`/api/pis/${pi.id}`, { ...body.pi, document: { ...body.pi.document, issueDate: null, validUntil: null } }, { method: 'PUT', token: sales });
  await req(`/api/pis/${pi.id}/confirm`, {}, { token: sales, code: 'PI_DOCUMENT_DATES_INVALID' });
  await req(`/api/pis/${pi.id}`, { ...body.pi, document: { ...body.pi.document, validUntil: '2020-01-01' } }, { method: 'PUT', token: sales, code: 'PI_DOCUMENT_DATES_INVALID' });
  await req(`/api/pis/${pi.id}`, body.pi, { method: 'PUT', token: sales });
  const confirmed = await req(`/api/pis/${pi.id}/confirm`, {}, { token: sales });
  assert(confirmed.fulfillment.every(line => line.status === 'CONFIRMED'));
  const imageItem = confirmed.items.find(i => i.skuId === red.id);
  const imageResponse = await fetch(`${base}/api/pis/${pi.id}/items/${imageItem.id}/image`, { headers: { Authorization: `Bearer ${external}` } });
  assert.equal(imageResponse.status, 200); assert(Buffer.from(await imageResponse.arrayBuffer()).equals(imageBytes));
  pass('日期缺失及倒置拒绝确认，其他费用逐分计入总额，单位和SKU图片快照保留且客户按单据范围可读');
  const attachmentPath = `/api/pis/${pi.id}/confirmation-attachments`, attachmentRequest = randomUUID();
  const attachments = await Promise.all([uploadFile(attachmentPath, imageBytes, { token: sales, requestId: attachmentRequest }), uploadFile(attachmentPath, imageBytes, { token: sales, requestId: attachmentRequest })]);
  assert.equal(attachments[0].id, attachments[1].id);
  const internalAttachment = await uploadFile(attachmentPath, imageBytes, { token: sales, visibility: 'INTERNAL' });
  await uploadFile(attachmentPath, imageBytes, { token: external, code: 'FORBIDDEN' });
  await uploadFile(attachmentPath, imageBytes, { token: reader, code: 'PERMISSION_DENIED' });
  const customerAttachments = await req(attachmentPath, undefined, { token: external }); assert.equal(customerAttachments.length, 1); assert(!('objectKey' in customerAttachments[0]));
  await req(`${attachmentPath}/${internalAttachment.id}/download`, undefined, { token: external, code: 'ATTACHMENT_NOT_FOUND' });
  const attachmentResponse = await fetch(`${base}${attachmentPath}/${attachments[0].id}/download`, { headers: { Authorization: `Bearer ${external}` } });
  assert.equal(attachmentResponse.status, 200); assert.equal(attachmentResponse.headers.get('x-content-type-options'), 'nosniff'); assert(Buffer.from(await attachmentResponse.arrayBuffer()).equals(imageBytes));
  pass('确认附件并发上传幂等，客户/只读不可上传，内部附件对客户隐藏，公开附件按范围逐字节下载');
  await req(`/api/pis/${pi.id}/export-task`, undefined, { token: reader, code: 'PERMISSION_DENIED' });
  await req(`/api/pis/${pi.id}/export-task/retry`, { revision: 0, reason: '外部越权' }, { token: external, code: 'FORBIDDEN' });
  let exportTask;
  for (let n = 0; n < 30; n++) {
    exportTask = await req(`/api/pis/${pi.id}/export-task`, undefined, { token: sales });
    assert(exportTask); assert.notEqual(exportTask.status, 'FAILED');
    if (exportTask.status === 'READY') break;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.equal(exportTask.status, 'READY'); assert.equal(exportTask.sourceType, 'CONFIRMATION');
  for (const key of ['snapshotJson', 'claimToken', 'pdfKey', 'xlsxKey', 'leaseUntil']) assert(!(key in exportTask));
  const exported = {};
  for (const format of ['pdf', 'xlsx']) {
    const download = async token => {
      const response = await fetch(`${base}/api/pis/${pi.id}/export.${format}`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30000) });
      assert.equal(response.status, 200); return Buffer.from(await response.arrayBuffer());
    };
    const first = await download(sales), second = await download(external);
    assert(first.equals(second)); assert.equal(createHash('sha256').update(first).digest('hex'), exportTask[`${format}Sha256`]);
    exported[format] = exportTask[`${format}Sha256`];
  }
  evidence.fixtures.exportHashes = exported;
  pass('确认自动生成两种格式，任务不暴露内部存储，普通销售与客户下载逐字节一致且匹配归档SHA256');
  for (const source of sourceLocks) {
    const linked = await req(`/api/inventory-locks/${source.id}/orders`, undefined, { token: sales });
    assert.equal(linked.total, 1); assert.equal(linked.records[0].reservationExpiresAt, source.expireTime);
  }
  const reds = pi.fulfillment.find(line => line.skuId === red.id), blues = pi.fulfillment.find(line => line.skuId === blue.id); assert(reds && blues);
  const redShipment = { customerId: customer.id, orderId: reds.orderId, vehicleQrCodes: [vehicles[0].qrCode] };
  await req('/api/stock-out', redShipment, { token: warehouseOperator, code: 'PI_SHIPMENT_NOT_RELEASED' });
  pass('并发及换序重试返回同一PI两子单，确认一次确认全部，原截止不变，草稿和旧出库不能绕过限制');

  await req(`/api/pis/${pi.id}/payments`, { requestId: randomUUID(), amount: '49.99', currencyCode: 'CNY' }, { token: finance });
  const retained = await req(`/api/pis/${pi.id}/payments`, { requestId: randomUUID(), amount: '0.01', currencyCode: 'CNY' }, { token: finance });
  assert(retained.fulfillment.every(line => line.reservationStatus === 'PAID_HELD'));
  const agentPi = await req(`/api/pis/${pi.id}`, undefined, { token: external });
  assert(agentPi.fulfillment.every(line => line.reservationStatus === 'ACTIVE')); assert.equal(agentPi.paidAmount, null); assert.deepEqual(agentPi.payments, []);
  await req(`/api/pis/${pi.id}/release`, { reason: '未收足拒放行' }, { token: finance, code: 'PAYMENT_GATE_NOT_MET' });
  await req(`/api/pis/${pi.id}/payments`, { requestId: randomUUID(), amount: '150.00', currencyCode: 'CNY' }, { token: finance });
  await req('/api/stock-out', redShipment, { token: warehouseOperator, code: 'PI_SHIPMENT_NOT_RELEASED' });
  await req(`/api/pis/${pi.id}/release`, { reason: '财务核实200元，准许发货' }, { token: finance });
  for (const format of ['pdf', 'xlsx']) {
    const response = await fetch(`${base}/api/pis/${pi.id}/export.${format}`, { headers: { Authorization: `Bearer ${external}` }, signal: AbortSignal.timeout(30000) });
    assert.equal(response.status, 200); assert.equal(createHash('sha256').update(Buffer.from(await response.arrayBuffer())).digest('hex'), exported[format]);
  }
  pass('定金逐分门槛同时保留两车型，代理所有子单隐藏付款方式，足额收款仍须授权放行');

  let redOut;
  if (process.env.BIKE_ACCEPTANCE_DISPATCH_MODE === 'batch') {
    const warehouseView = await req(`/api/pi-dispatch/${pi.id}`, undefined, { token: warehouseOperator });
    assert.equal(warehouseView.orders.length, 2);
    for (const forbidden of ['unitPrice', 'paidAmount', 'paymentStatus', 'depositRequiredAmount', 'PAID_HELD', 'reservationStatus', 'expiresAt', 'bankAccount']) assert(!JSON.stringify(warehouseView).includes(forbidden));
    await req(`/api/pis/${pi.id}`, undefined, { token: warehouseOperator, code: 'PERMISSION_DENIED' });
    const dispatchOptions = await req(`/api/pi-dispatch/options?keyword=${pi.piNo}`, undefined, { token: warehouseOperator }); assert.equal(dispatchOptions.total, 1);
    const command = { requestId: randomUUID(), vehicles: [{ orderId: reds.orderId, vehicleQrCode: vehicles[0].qrCode }, { orderId: blues.orderId, vehicleQrCode: vehicles[2].qrCode }] };
    await req(`/api/pi-dispatch/${pi.id}`, command, { token: reader, code: 'PERMISSION_DENIED' });
    await req(`/api/pi-dispatch/${pi.id}`, { ...command, vehicles: [command.vehicles[0], { ...command.vehicles[1], vehicleQrCode: vehicles[1].qrCode }] }, { token: warehouseOperator, code: 'ORDER_MODEL_MISMATCH' });
    const unchanged = await req(`/api/pis/${pi.id}`, undefined, { token: sales });
    assert(unchanged.fulfillment.every(line => line.shippedQty === 0)); assert.deepEqual(unchanged.dispatchBatches, []);
    await expectSku(model.id, red.id, 2, 1, 1, 0); await expectSku(secondModel.id, blue.id, 2, 1, 1, 0);
    const [one, two] = await Promise.all([req(`/api/pi-dispatch/${pi.id}`, command, { token: warehouseOperator }), req(`/api/pi-dispatch/${pi.id}`, { ...command, vehicles: [...command.vehicles].reverse() }, { token: warehouseOperator })]);
    assert.equal(one.id, two.id); assert.equal(one.activeQty, 2); assert.equal(one.sourceType, 'PI_COMMAND');
    evidence.fixtures.dispatchBatchId = one.id;
    await req(`/api/pi-dispatch/${pi.id}`, { ...command, vehicles: [command.vehicles[0]] }, { token: warehouseOperator, code: 'PI_DISPATCH_CONFLICT' });
    const batch = await req(`/api/pi-dispatch/batches/${one.id}`, undefined, { token: warehouseOperator }); assert.equal(batch.vehicles.length, 2);
    const agentBatch = await req(`/api/pis/${pi.id}/dispatch-batches/${one.id}`, undefined, { token: external }); assert.equal(agentBatch.vehicles.length, 2);
    redOut = { id: batch.vehicles.find(v => v.orderId === reds.orderId).stockOutId };
    const trace = await req(`/api/trace/qr/${encodeURIComponent(vehicles[0].qrCode)}`, undefined, { token: warehouseOperator });
    assert(trace.stockOutRecords.some(row => row.dispatchBatchNo === one.batchNo && row.piNo === pi.piNo && row.executionRevision === 1));
    await req(`/api/stock-out/${redOut.id}/cancel?reason=验收批次撤销一台`, {}, { token: warehouseOperator });
    const prior = await req(`/api/pi-dispatch/${pi.id}`, command, { token: warehouseOperator });
    assert.equal(prior.id, one.id); assert.equal(prior.activeQty, 1); assert.equal(prior.status, 'PARTIALLY_CANCELLED');
    const remaining = await req(`/api/pis/${pi.id}`, undefined, { token: sales }); assert.equal(remaining.dispatchBatches.length, 1);
    redOut = await req('/api/stock-out', redShipment, { token: warehouseOperator });
    const afterLegacy = await req(`/api/pis/${pi.id}`, undefined, { token: sales }); assert.equal(afterLegacy.dispatchBatches.length, 2); assert.equal(afterLegacy.dispatchBatches[0].sourceType, 'ORDER_COMMAND');
    pass('仓管投影无价格收款；混合批次最后车失败全部回滚；并发幂等；真实批次车辆/追溯/代理读取；撤销后重试不重发且旧入口也留批次');
  } else {
    redOut = await req('/api/stock-out', redShipment, { token: warehouseOperator });
    await req('/api/stock-out', { customerId: customer.id, orderId: blues.orderId, vehicleQrCodes: [vehicles[2].qrCode] }, { token: warehouseOperator });
  }
  ordersToCancel.delete(blues.orderId);
  await req(`/api/stock-out/${redOut.id}/cancel?reason=PI多车型履约受控撤销`, {}, { token: warehouseOperator });
  const restored = await req(`/api/pis/${pi.id}`, undefined, { token: sales });
  assert.equal(restored.fulfillment.find(line => line.orderId === reds.orderId).remainingQty, 1);
  await req('/api/stock-out', redShipment, { token: warehouseOperator }); ordersToCancel.delete(reds.orderId);
  const complete = await req(`/api/pis/${pi.id}`, undefined, { token: finance });
  assert(complete.fulfillment.every(line => line.shippedQty === 1 && line.remainingQty === 0)); assert.equal(complete.paidAmount, '200.00'); assert.equal(complete.payments.length, 3);
  const revisionRequest = { requestId: randomUUID(), expectedRevision: 1, reason: '已收款PI条款修订验收' };
  const revision = await req(`/api/pis/${pi.id}/revisions`, revisionRequest, { token: sales });
  assert.equal(revision.revision, 2); assert.equal(revision.status, 'DRAFT'); assert.equal(revision.previousRevisionId, pi.id);
  const revisionFinance = await req(`/api/pis/${revision.id}`, undefined, { token: finance });
  assert.equal(revisionFinance.paidAmount, '200.00');
  const revisionRetry = await req(`/api/pis/${pi.id}/revisions`, revisionRequest, { token: sales }); assert.equal(revisionRetry.id, revision.id);
  await req(`/api/pis/${pi.id}/revisions`, { ...revisionRequest, reason: '不同内容' }, { token: sales, code: 'PI_REVISION_REQUEST_CONFLICT' });
  const revisions = await req(`/api/pis/${pi.id}/revisions`, undefined, { token: finance });
  assert.deepEqual(revisions.map(row => row.revision), [1, 2]);
  pass('PI商业修订草稿不可覆盖原版本；请求幂等/冲突受控；系列累计收款在新修订仍连续可见');
  if(process.env.BIKE_ACCEPTANCE_LOGISTICS === 'actual') {
    const batch = complete.dispatchBatches.find(b => b.activeQty > 0); assert(batch);
    const logisticsBase = { requestId: randomUUID(), batchNo: prefix + 'MANUAL', carrier: '隔离承运商', trackingNo: prefix + 'TRACK', status: 'SHIPPED' };
    await req(`/api/pis/${pi.id}/logistics`, logisticsBase, { token: logisticsOperator, code: 'LOGISTICS_ACTUAL_BATCH_REQUIRED' });
    await req(`/api/pis/${pi.id}/logistics`, { ...logisticsBase, stockOutBatchId: batch.id }, { token: warehouseOperator, code: 'PERMISSION_DENIED' });
    await req(`/api/pis/${pi.id}/logistics`, { ...logisticsBase, stockOutBatchId: batch.id }, { token: external, code: 'FORBIDDEN' });
    const planBody = { ...logisticsBase, status: 'PLANNED' };
    const plan = (await req(`/api/pis/${pi.id}/logistics`, planBody, { token: logisticsOperator })).logistics[0];
    const logisticsAttachmentsPath = `/api/pis/${pi.id}/logistics/${plan.id}/attachments`, logisticsUploadRequest = randomUUID();
    const logisticsFiles = await Promise.all([uploadFile(logisticsAttachmentsPath, imageBytes, { token: logisticsOperator, requestId: logisticsUploadRequest }), uploadFile(logisticsAttachmentsPath, imageBytes, { token: logisticsOperator, requestId: logisticsUploadRequest })]);
    assert.equal(logisticsFiles[0].id, logisticsFiles[1].id);
    const privateLogisticsFile = await uploadFile(logisticsAttachmentsPath, imageBytes, { token: logisticsOperator, visibility: 'INTERNAL' });
    await uploadFile(logisticsAttachmentsPath, imageBytes, { token: sales, code: 'PERMISSION_DENIED' });
    await uploadFile(logisticsAttachmentsPath, imageBytes, { token: external, code: 'FORBIDDEN' });
    const publicLogisticsFiles = await req(logisticsAttachmentsPath, undefined, { token: external }); assert.equal(publicLogisticsFiles.length, 1); assert(!('objectKey' in publicLogisticsFiles[0]));
    await req(`${logisticsAttachmentsPath}/${privateLogisticsFile.id}/download`, undefined, { token: external, code: 'ATTACHMENT_NOT_FOUND' });
    await req(`/api/pis/${pi.id}/logistics/9223372036854775807/attachments`, undefined, { token: sales, code: 'ATTACHMENT_NOT_FOUND' });
    assert.equal(plan.linkType,'PLAN_UNLINKED'); assert.equal(plan.revision,1);
    assert.equal((await req(`/api/pis/${pi.id}/logistics`, planBody, { token: logisticsOperator })).logistics.length,1);
    await req(`/api/pis/${pi.id}/logistics`, { ...planBody, remark: '改变同一请求' }, { token: logisticsOperator, code: 'LOGISTICS_REQUEST_CONFLICT' });
    const shippingBody = { ...logisticsBase, requestId: randomUUID(), stockOutBatchId: batch.id, expectedRevision: 1 };
    const shippedView = await req(`/api/pis/${pi.id}/logistics/${plan.id}`, shippingBody, { method: 'PUT', token: logisticsOperator });
    const shipped = shippedView.logistics[0];assert.equal(shipped.revision,2);assert.equal(shipped.batchNo,batch.batchNo);assert.equal(shipped.shippedQtySnapshot,batch.activeQty);assert.equal(shipped.linkType,'ACTUAL_BATCH');
    const shippingRetry = await req(`/api/pis/${pi.id}/logistics/${plan.id}`, shippingBody, { method: 'PUT', token: logisticsOperator }); assert.equal(shippingRetry.logistics[0].revision,2);
    const batchData = await req(`/api/pi-dispatch/batches/${batch.id}`,undefined,{token:warehouseOperator});
    const active = batchData.vehicles.find(v=>v.status==='COMPLETED');assert(active);
    await req(`/api/stock-out/${active.stockOutId}/cancel?reason=物流已在途不能撤销`,{},{token:warehouseOperator,code:'LOGISTICS_SHIPPED'});
    const tracked = await req(`/api/trace/frame/${encodeURIComponent(active.frameSn)}`,undefined,{token:warehouseOperator});
    assert(tracked.stockOutRecords.some(r=>r.stockOutNo===active.stockOutNo && r.logisticsTrackingNo===logisticsBase.trackingNo && r.logisticsStatus==='SHIPPED'));
    const withdrawal={requestId:randomUUID(),expectedRevision:2,reason:'承运商取消，已核实货物未离库'};
    await req(`/api/pis/${pi.id}/logistics/${plan.id}/withdraw`,withdrawal,{token:logisticsOperator});
    const withdrawn=await req(`/api/pis/${pi.id}/logistics/${plan.id}/withdraw`,withdrawal,{token:logisticsOperator});assert.equal(withdrawn.logistics[0].revision,3);
    await uploadFile(logisticsAttachmentsPath, imageBytes, { token: logisticsOperator, code: 'INVALID_STATUS_TRANSITION' });
    const logisticsFileResponse = await fetch(`${base}${logisticsAttachmentsPath}/${logisticsFiles[0].id}/download`, { headers: { Authorization: `Bearer ${external}` } }); assert.equal(logisticsFileResponse.status, 200); assert.equal(logisticsFileResponse.headers.get('x-content-type-options'), 'nosniff'); assert(Buffer.from(await logisticsFileResponse.arrayBuffer()).equals(imageBytes));
    assert.equal((await req(`/api/pis/${revision.id}/logistics/${plan.id}/attachments`, undefined, { token: sales })).length, 2);
    pass('物流附件绑定实际记录，普通物流并发上传幂等、客户只读公开文件；跨记录拒绝，撤回后保留下载并拒绝新增');
    const history=await req(`/api/pis/${pi.id}/logistics/${plan.id}/history`,undefined,{token:logisticsOperator});assert.equal(history.length,3);assert(JSON.stringify(history).includes(logisticsBase.trackingNo));
    await req(`/api/pis/${pi.id}/logistics/${plan.id}/history`,undefined,{token:external,code:'FORBIDDEN'});
    await req(`/api/stock-out/${active.stockOutId}/cancel?reason=授权撤回后实际撤销`,{},{token:warehouseOperator});
    await req(`/api/pis/${pi.id}/logistics/${plan.id}`,{...shippingBody,requestId:randomUUID(),expectedRevision:3},{method:'PUT',token:logisticsOperator,code:'LOGISTICS_BATCH_EMPTY'});
    const vehicle=vehicles.find(v=>v.qrCode=== (active.orderId===reds.orderId ? vehicles[0].qrCode : vehicles[2].qrCode));assert(vehicle);
    await req('/api/stock-out',{customerId:customer.id,orderId:active.orderId,vehicleQrCodes:[vehicle.qrCode]},{token:warehouseOperator});
    pass('真实物流计划转发运、幂等与版本历史；在途禁止撤销，授权撤回后可撤；空批次拒发运；追溯返回真实运单');
  }
  for (const l of sourceLocks) { await req(`/api/inventory-locks/${l.id}/release?reason=释放本次未转换一台`, {}, { token: sales }); locksToRelease.delete(l.id); }
  await expectSku(model.id, red.id, 1, 0, 0, 1); await expectSku(secondModel.id, blue.id, 1, 0, 0, 1);
  pass('同PI两车型经普通仓管真实出库，撤销后可重新履约；剩余锁释放，实物与承诺守恒且收款只记一次');
  const extraRequest = { requestId: randomUUID(), amount: '12.34', currencyCode: 'CNY', paymentMethod: 'BANK_TRANSFER', remark: '隔离核对方式' };
  const extra = await req(`/api/pis/${pi.id}/payments`, extraRequest, { token: finance });
  const receipt = extra.payments.find(p => p.requestId === extraRequest.requestId); assert(receipt); assert.equal(receipt.paymentMethod, 'BANK_TRANSFER'); assert(receipt.recordedAt); assert.equal(receipt.revision, 1);
  await req(`/api/pis/${pi.id}/payments`, { ...extraRequest, paymentMethod: 'CASH' }, { token: finance, code: 'PAYMENT_REQUEST_CONFLICT' });
  await req(`/api/pis/${pi.id}/payments`, { ...extraRequest, requestId: randomUUID(), paymentMethod: 'OTHER' }, { token: finance, code: 'PAYMENT_METHOD_INVALID' });
  const receiptPath = `/api/pis/${pi.id}/payments/${receipt.id}/attachments`;
  const receiptFile = await uploadFile(receiptPath, imageBytes, { token: finance });
  const reversalPath = `/api/pis/${pi.id}/payments/${receipt.id}/reverse`, reversalBody = { requestId: randomUUID(), revision: 1, reason: '重复登记验收，保留实际出库事实' };
  await req(reversalPath, reversalBody, { token: finance, code: 'PERMISSION_DENIED' });
  await req(reversalPath, reversalBody, { token: external, code: 'FORBIDDEN' });
  await req(reversalPath, { ...reversalBody, revision: 2 }, { token: paymentCorrector, code: 'PI_PAYMENT_REVERSE_CONFLICT' });
  const reversed = await Promise.all([req(reversalPath, reversalBody, { token: paymentCorrector }), req(reversalPath, reversalBody, { token: paymentCorrector })]);
  for (const result of reversed) {
    assert.equal(result.paidAmount, '200.00'); assert.equal(result.status, 'CONFIRMED'); assert.equal(result.releaseStatus, 'BLOCKED');
    assert(result.fulfillment.every(line => line.shippedQty === 1)); const row = result.payments.find(p => p.id === receipt.id); assert.equal(row.status, 'REVERSED'); assert.equal(row.revision, 2); assert.equal(row.reversalReason, reversalBody.reason); assert(!('reversalRequestId' in row));
  }
  await req(reversalPath, { ...reversalBody, reason: '同请求改原因' }, { token: paymentCorrector, code: 'PI_PAYMENT_REVERSE_CONFLICT' });
  const downloadReceipt = await fetch(`${base}${receiptPath}/${receiptFile.id}/download`, { headers: { Authorization: `Bearer ${paymentCorrector}` } }); assert.equal(downloadReceipt.status, 200); assert(Buffer.from(await downloadReceipt.arrayBuffer()).equals(imageBytes));
  await uploadFile(receiptPath, imageBytes, { token: finance, code: 'INVALID_STATUS_TRANSITION' });
  const revisionAfter = await req(`/api/pis/${revision.id}`, undefined, { token: finance }); assert.equal(revisionAfter.paidAmount, '200.00'); assert.equal(revisionAfter.payments.find(p => p.id === receipt.id).piId, pi.id);
  const sameExport = await req(`/api/pis/${pi.id}/export-task`, undefined, { token: sales }); assert.equal(sameExport.pdfSha256, exported.pdf); assert.equal(sameExport.xlsxSha256, exported.xlsx);
  await expectSku(model.id, red.id, 1, 0, 0, 1); await expectSku(secondModel.id, blue.id, 1, 0, 0, 1);
  pass('收款方式参与幂等，独立权限并发撤回只生效一次；已发数量/库存及归档不变、凭证保留、系列历史连续并重新阻止放行');
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
