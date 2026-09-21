import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {readFile,writeFile} from 'node:fs/promises';
if(process.env.BIKE_ACCEPTANCE_WRITE!=='isolated')throw new Error('仅允许专用验收库');
const base=process.env.BIKE_API_URL||'http://127.0.0.1:18180';
const fixture=JSON.parse(await readFile(process.env.BIKE_ACCEPTANCE_FIXTURE,'utf8'));
assert(!fixture.error&&fixture.prefix.startsWith('AC')&&fixture.fixtures?.qrCode);
const f=fixture.fixtures,prefix=`ACCESS${Date.now().toString(36).toUpperCase()}`,evidence={prefix,checks:[]};
let admin,account,agent,ownLock,otherLock;
async function req(token,path,body,method=body===undefined?'GET':'POST',expected=200){
  const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json();assert.equal(response.status,expected,`${path}: ${result.code}`);
  if(expected===200)assert.equal(result.code,'SUCCESS');return expected===200?result.data:result;
}
const a=(path,body,method)=>req(admin,path,body,method);
function pass(name){evidence.checks.push({name,passed:true});console.log(`PASS ${name}`);}
try{
  admin=(await req(null,'/api/auth/login',{username:'admin',password:process.env.BIKE_TEST_PASSWORD})).accessToken;
  const trace=await a(`/api/trace/frame/${f.frameSn}`);
  const out=trace.stockOutRecords.find(r=>r.status==='COMPLETED');assert(out,'本次生产验收车辆应在已出库状态');
  const outRecord=await a(`/api/stock-out/by-no/${out.stockOutNo}`);
  await a(`/api/stock-out/${outRecord.id}/cancel?reason=授权验收恢复专用样车`,{},'POST');
  agent=await a('/api/master/agents',{code:prefix,name:'授权验收代理商'});
  const other=await a('/api/master/agents',{code:prefix+'B',name:'另一个验收代理商'});
  const secret=randomBytes(24).toString('hex'),password=randomBytes(24).toString('hex');
  account=await a('/api/users',{username:prefix,password:secret,displayName:'授权验收',userType:'AGENT',agentId:agent.id});
  const role=(await a('/api/roles')).find(r=>r.roleCode==='WEB_AGENT');assert(role);
  await a(`/api/users/${account.id}/roles`,{roleIds:[role.id]},'PUT');
  let token=(await req(null,'/api/auth/login',{username:prefix,password:secret})).accessToken;
  await req(token,'/api/auth/password',{currentPassword:secret,newPassword:password},'PUT');
  token=(await req(null,'/api/auth/login',{username:prefix,password})).accessToken;
  const lockRequest={agentId:other.id,productModelId:f.modelId,lockQty:1};
  assert.deepEqual(await req(token,'/api/master/models'),[]);
  assert.equal((await req(token,'/api/agent/inventory-locks',lockRequest,'POST',400)).code,'MODEL_NOT_AUTHORIZED');
  await req(token,`/api/master/agents/${agent.id}/model-access`,{modelIds:[f.modelId]},'PUT',403);
  pass('未授权代理商不能自行锁库或授予车型');
  await a(`/api/master/agents/${agent.id}/model-access`,{modelIds:[f.modelId]},'PUT');
  assert((await req(token,'/api/master/models')).some(m=>m.id===f.modelId));
  assert((await req(token,'/api/agent/inventory')).records.some(r=>r.modelId===f.modelId));
  await req(token,'/api/agent/purchase-intents',{productModelId:f.modelId,intentQty:1});
  pass('首次授权无需已有锁定，车型/库存/采购意向可用');
  otherLock=await a('/api/inventory-locks',{agentId:other.id,productModelId:f.modelId,lockQty:1});
  const stock=await req(token,`/api/inventory?modelId=${f.modelId}`);assert.equal(stock.records[0].availableQty,0);
  const module=await req(token,`/api/modules/agent-inventory?keyword=${fixture.prefix}`);
  assert.equal(module.total,1);assert.equal(String(module.rows[0][module.columns.indexOf('可用库存')]),'0');
  assert.equal((await req(token,'/api/agent/inventory-locks',lockRequest,'POST',400)).code,'INVENTORY_NOT_ENOUGH');
  await a(`/api/inventory-locks/${otherLock.id}/release`,{});otherLock=null;
  ownLock=await req(token,'/api/agent/inventory-locks',lockRequest);assert.equal(ownLock.agentId,agent.id);assert(f.modelId>1);
  pass('他人锁定计入可用量，车型ID大于1可锁定且归属取登录账号');
  await a(`/api/master/agents/${agent.id}/model-access`,{modelIds:[]},'PUT');
  assert.deepEqual(await req(token,'/api/master/models'),[]);
  assert.equal((await req(token,'/api/agent/inventory')).total,0);
  assert.equal((await req(token,'/api/modules/agent-inventory')).total,0);
  await req(token,`/api/inventory?modelId=${f.modelId}`,undefined,'GET',403);
  await req(token,'/api/agent/inventory-locks',lockRequest,'POST',400);
  await req(token,'/api/agent/purchase-intents',{productModelId:f.modelId,intentQty:1},'POST',400);
  await req(token,`/api/agent/inventory-locks/${ownLock.id}/release`,{});ownLock=null;
  pass('撤销授权立即阻止查询和新增，历史锁定仍可释放');
  evidence.fixtures={agentId:agent.id,modelId:f.modelId,accountId:account.id};
}catch(error){evidence.error=error.message;process.exitCode=1;console.error(error.message);}
finally{
  for(const lock of [ownLock,otherLock])if(lock)await a(`/api/inventory-locks/${lock.id}/release`,{});
  if(account)await a(`/api/users/${account.id}/status?enabled=false`,{},'PUT');
  if(process.env.BIKE_ACCEPTANCE_REPORT)await writeFile(process.env.BIKE_ACCEPTANCE_REPORT,JSON.stringify(evidence,null,2));
}
