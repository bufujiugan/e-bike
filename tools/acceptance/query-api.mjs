import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';

if(process.env.BIKE_ACCEPTANCE_WRITE!=='isolated') throw new Error('仅对专用验收库运行');
const base=process.env.BIKE_API_URL || 'http://127.0.0.1:18180';
const password=process.env.BIKE_TEST_PASSWORD;
if(!password) throw new Error('缺少 BIKE_TEST_PASSWORD');
let token;
const prefix=`Q${Date.now().toString(36).toUpperCase()}`;
const evidence={prefix,startedAt:new Date().toISOString(),checks:[]};
async function request(path,body,expectedCode){
  const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json();
  if(expectedCode){ assert.equal(result.code,expectedCode,`${path}: ${result.message}`); return result; }
  assert(response.ok && result.code==='SUCCESS',`${path}: ${response.status} ${result.code} ${result.message}`);return result.data;
}
function pass(name){ evidence.checks.push({name,passed:true});console.log(`PASS ${name}`); }
try{
  token=(await request('/api/auth/login',{username:process.env.BIKE_TEST_USERNAME||'admin',password})).accessToken;
  const modules=['assembly','vehicles','inventory','stock','orders','agents','trace','parts','customers','suppliers','models','warehouses','users','roles','workstations','logs','customs','locations','inventory-locks','stock-in','stock-out','rework','operation-logs','config','agent-dashboard','agent-inventory','agent-locks','purchase-intents'];
  for(const module of modules){
    for(const keyword of ['', 'AC']){
      const page=await request(`/api/modules/${module}?page=1&pageSize=2&keyword=${keyword}`);
      assert(Array.isArray(page.rows));assert(page.rows.length<=2);assert(page.total>=page.rows.length);
      const next=await request(`/api/modules/${module}?page=2&pageSize=2&keyword=${keyword}`);
      assert.equal(next.total,page.total);
      if(page.total<=2) assert.equal(next.rows.length,0);
    }
    pass(`${module} 两种关键词及前两页查询`);
  }
  for(const path of ['/api/parts/import-template','/api/export/vehicles?keyword=DOESNOTEXIST','/api/export/parts?keyword=AC','/api/production/skus/import-template']){
    const response=await fetch(base+path,{headers:{Authorization:`Bearer ${token}`}});
    assert(response.ok,`${path}: ${response.status}`);
    assert(response.headers.get('content-type').includes('spreadsheetml'));
    const bytes=new Uint8Array(await response.arrayBuffer());assert.equal(bytes[0],0x50);assert.equal(bytes[1],0x4b);
    pass(`${path} 返回 Excel 文件`);
  }
  const empty=await request(`/api/production/quarantined-parts?keyword=${prefix}&page=2`);
  assert.equal(empty.total,0);assert.deepEqual(empty.records,[]);pass('旧件分页和无匹配记录');
  const model=await request('/api/master/models',{code:prefix,name:prefix});
  const template=(await request('/api/production/templates'))[0];
  const codes={frameSn:`${prefix}FRAME`,motorSn:`${prefix}MOTOR`,batterySn:`${prefix}BATTERY`,displaySn:`${prefix}DISPLAY`};
  await request('/api/vehicles',{frameSn:codes.frameSn,modelId:model.id});
  for(const type of ['MOTOR','BATTERY','DISPLAY']) await request('/api/parts',{partSn:`${prefix}${type}`,partType:type,qcStatus:'PASSED'});
  await request('/api/assembly/submit',codes,'SKU_SELECTION_REQUIRED');pass('旧组装无 SKU 拒绝');
  await request('/api/production/skus',{modelId:model.id,skuCode:prefix,productName:prefix,templateId:template.id,enabled:true});
  const assembled=await request('/api/assembly/submit',codes);
  assert.equal(assembled.status,1);assert(assembled.vehicleQrCode);
  const tasks=await request(`/api/production/tasks?keyword=${codes.frameSn}`);
  assert.equal(tasks.total,1);assert.equal(tasks.records[0].status,'COMPLETED');
  const taskId=tasks.records[0].id;
  assert.equal((await request(`/api/production/tasks/${taskId}/prints`)).length,1);
  const again=await request('/api/assembly/submit',codes);assert.equal(again.id,assembled.id);
  assert.equal((await request(`/api/production/tasks/${taskId}/prints`)).length,1);
  await request('/api/assembly/submit',{...codes,motorSn:`${prefix}OTHER`},'IDEMPOTENCY_CONFLICT');
  pass('旧组装统一任务和首打、重复幂等与变码拒绝');
  await request('/api/production/skus',{modelId:model.id,skuCode:`${prefix}SECOND`,productName:prefix,templateId:template.id,enabled:true});
  await request('/api/vehicles',{frameSn:`${prefix}FRAME2`,modelId:model.id});
  await request('/api/assembly/submit',{...codes,frameSn:`${prefix}FRAME2`},'SKU_SELECTION_REQUIRED');pass('旧组装多 SKU 拒绝歧义');
}catch(error){evidence.error=error.stack;process.exitCode=1;console.error(error.stack);}
evidence.finishedAt=new Date().toISOString();
if(process.env.BIKE_ACCEPTANCE_REPORT) await writeFile(process.env.BIKE_ACCEPTANCE_REPORT,JSON.stringify(evidence,null,2)+'\n');
console.log(`完成 ${evidence.checks.length} 项；${evidence.error?'失败':'通过'}`);
