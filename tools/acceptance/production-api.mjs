import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅对专用验收库运行，设置 BIKE_ACCEPTANCE_WRITE=isolated');
const base = process.env.BIKE_API_URL || 'http://127.0.0.1:18180';
const username = process.env.BIKE_TEST_USERNAME || 'admin';
const password = process.env.BIKE_TEST_PASSWORD;
if (!password) throw new Error('缺少 BIKE_TEST_PASSWORD');
let token;
const prefix = `AC${Date.now().toString(36).toUpperCase()}`;
const evidence = { prefix, startedAt:new Date().toISOString(), checks:[], fixtures:{} };
async function request(path, body, method = body === undefined ? 'GET' : 'POST', expectedCode) {
  const response = await fetch(base + path, { method, headers: { 'Content-Type':'application/json', ...(token ? {Authorization:`Bearer ${token}`} : {}) }, body:body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json();
  if (expectedCode) { assert.equal(result.code, expectedCode, `${path}: ${result.message}`); return result; }
  assert(response.ok && result.code === 'SUCCESS', `${path}: ${response.status} ${result.code} ${result.message}`);
  return result.data;
}
function check(name) { evidence.checks.push({name,passed:true}); console.log(`PASS ${name}`); }
try {
  const login = await request('/api/auth/login',{username,password}); token=login.accessToken;
  assert.equal(login.mustChangePassword,false,'验收账号须先完成首次改密');
  const master = async (type,name) => request(`/api/master/${type}`,{code:`${prefix}${name}`,name:`验收${prefix}${name}`});
  const model = await master('models','MODEL'), customer = await master('customers','CUSTOMER');
  const warehouse = await master('warehouses','WAREHOUSE');
  const location = await request(`/api/master/warehouses/${warehouse.id}/locations`,{code:`${prefix}LOC`,name:`验收${prefix}库位`});
  const templates=await request('/api/production/templates');
  await request('/api/production/skus',{modelId:model.id,skuCode:`${prefix}SKU`,productName:'验收商品',specification:'蓝色 / 48V',ean:'5901234123457',upc:'036000291452',templateId:templates[0].id,enabled:true});
  const sku=(await request('/api/production/skus')).find(s=>s.skuCode===`${prefix}SKU`); assert(sku);
  const codes={FRAME:`${prefix}FRAME`,MOTOR:`${prefix}MOTOR`,BATTERY:`${prefix}BATTERY`,DISPLAY:`${prefix}DISPLAY`};
  for(const type of ['MOTOR','BATTERY','DISPLAY']) await request('/api/parts',{partSn:codes[type],partType:type,qcStatus:'PASSED'});
  const taskId=randomUUID(); await request('/api/production/tasks',{requestId:taskId,skuId:sku.id,batchNo:prefix});
  const badCode=`${prefix}UNREGISTERED`;
  await request(`/api/production/tasks/${taskId}/scan`,{code:badCode,expectedType:'MOTOR'},'POST','PART_NOT_FOUND');
  const failures=await request(`/api/production/scan-failures?keyword=${badCode}`);
  assert.equal(failures.total,1); assert.equal(failures.records[0].taskId,taskId); assert.equal(failures.records[0].errorCode,'PART_NOT_FOUND');
  assert.equal((await request(`/api/production/tasks/${taskId}`)).scans.length,0); check('失败扫码独立事务留痕且业务无残留');
  const invalidCode=`${prefix}INVALIDTYPE`;
  await request(`/api/production/tasks/${taskId}/scan`,{code:invalidCode,expectedType:'INVALID'},'POST','INVALID_PARAMETER');
  const invalidAudit=await request(`/api/production/scan-failures?keyword=${invalidCode}`);
  assert.equal(invalidAudit.total,1);assert.equal(invalidAudit.records[0].errorCode,'INVALID_PARAMETER');check('HTTP 扫码参数校验失败留痕');
  const first=await request(`/api/production/tasks/${taskId}/scan`,{code:codes.DISPLAY,expectedType:'DISPLAY'});
  assert.equal(first.scans.length,1); assert.equal((await request(`/api/production/tasks/${taskId}`)).scans.length,1); check('扫码进度持久化与恢复');
  await request(`/api/production/tasks/${taskId}/scan`,{code:codes.DISPLAY,expectedType:'DISPLAY'});
  assert.equal((await request(`/api/production/tasks/${taskId}`)).scans.length,1); check('重复扫码幂等');
  for(const type of ['BATTERY','MOTOR','FRAME']) await request(`/api/production/tasks/${taskId}/scan`,{code:codes[type],expectedType:type});
  const complete=await request(`/api/production/tasks/${taskId}`); assert.equal(complete.status,'COMPLETED'); assert(complete.qrCode); check('无序四码完成与聚合码');
  let prints=await request(`/api/production/tasks/${taskId}/prints`); assert.equal(prints.length,1);
  await request(`/api/production/tasks/${taskId}/complete`,{});
  assert.equal((await request(`/api/production/tasks/${taskId}/prints`)).length,1); check('重复完成不重复首打');
  await request(`/api/production/prints/${prints[0].id}/claim`,{terminal:'acceptance-no-hardware'});
  await request(`/api/production/prints/${prints[0].id}/result`,{status:'FAILED',message:'验收模拟失败，未向实物设备送印'});
  const reprint=await request(`/api/production/prints/${prints[0].id}/reprint`,{reason:'验收补打'});
  assert.notEqual(reprint.id,prints[0].id);
  prints=await request(`/api/production/tasks/${taskId}/prints`); assert.equal(prints.length,2); assert(prints.some(p=>p.id===reprint.id));
  await request(`/api/production/prints/${reprint.id}/claim`,{terminal:'acceptance-no-hardware'});
  await request(`/api/production/prints/${reprint.id}/result`,{status:'FAILED',message:'接口验收结束，未进行硬件打印'}); check('补打任务可查询领取与回报');
  const issue=await request('/api/production/issues',{code:codes.FRAME,reason:'验收隔离'});
  await request('/api/stock-in',{vehicleQrCode:complete.qrCode,warehouseId:warehouse.id,locationId:location.id},'POST','PRODUCTION_QUARANTINED');
  await request(`/api/production/issues/${issue.id}/resolve`,{reason:'验收复检合格'}); check('隔离阻断入库及解除');
  const stock=await request('/api/stock-in',{vehicleQrCode:complete.qrCode,warehouseId:warehouse.id,locationId:location.id});
  assert.equal((await request(`/api/stock-in/by-no/${stock.stockNo}`)).id,stock.id); check('按业务单号精确查询入库');
  const inventoryIssue=await request('/api/production/issues',{code:codes.FRAME,reason:'验收在库隔离口径'});
  const productionStock=await request(`/api/production/inventory?skuId=${sku.id}&warehouseId=${warehouse.id}&locationId=${location.id}`);
  assert.equal(productionStock.length,1); assert.equal(productionStock[0].physicalQty,1); assert.equal(productionStock[0].quarantinedQty,1);
  const inventoryModule=await request(`/api/modules/inventory?keyword=${prefix}`);
  assert.equal(inventoryModule.total,1);
  const availableIndex=inventoryModule.columns.indexOf('可用库存'), quarantineIndex=inventoryModule.columns.indexOf('隔离库存');
  assert(availableIndex>=0 && quarantineIndex>=0); assert.equal(String(inventoryModule.rows[0][availableIndex]),'0'); assert.equal(String(inventoryModule.rows[0][quarantineIndex]),'1');
  await request('/api/dashboard/summary');
  await request(`/api/production/issues/${inventoryIssue.id}/resolve`,{reason:'库存口径验收结束'}); check('在库隔离统一生产库存及通用库存可用量');
  const order=await request('/api/orders',{customerId:customer.id,modelId:model.id,orderQty:1});
  await request(`/api/orders/${order.id}/confirm`,{});
  await request('/api/stock-out',{customerId:customer.id,orderId:order.id,vehicleQrCodes:[complete.qrCode]}); check('客户订单校验出库');
  for (const [type,code] of [['frame',codes.FRAME],['part',codes.BATTERY],['qr',complete.qrCode]]) {
    const trace=await request(`/api/trace/${type}/${encodeURIComponent(code)}`); assert.equal(trace.vehicle.frameSn,codes.FRAME); assert.equal(trace.bindingHistory.length,3); assert(trace.events.length>=8); assert.equal(trace.stockInRecords.length,1); assert.equal(trace.stockOutRecords.length,1);
  }
  check('三种编码追溯返回生产与仓储完整数据');
  evidence.fixtures={taskId,frameSn:codes.FRAME,partSn:codes.BATTERY,qrCode:complete.qrCode,skuId:sku.id,modelId:model.id,customerId:customer.id,warehouseId:warehouse.id,locationId:location.id};
} catch(error) { evidence.error=error.message; process.exitCode=1; console.error(error.message); }
evidence.finishedAt=new Date().toISOString();
if(process.env.BIKE_ACCEPTANCE_REPORT) await writeFile(process.env.BIKE_ACCEPTANCE_REPORT,JSON.stringify(evidence,null,2)+'\n');
console.log(`完成 ${evidence.checks.length} 项；结果 ${evidence.error?'失败':'通过'}。此脚本不验证真实硬件。`);
