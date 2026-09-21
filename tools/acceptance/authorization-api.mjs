import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

if(process.env.BIKE_ACCEPTANCE_WRITE!=='isolated') throw new Error('仅对专用验收库运行');
const base=process.env.BIKE_API_URL||'http://127.0.0.1:18180';
const password=process.env.BIKE_TEST_PASSWORD;
if(!password || !process.env.BIKE_ACCEPTANCE_FIXTURE) throw new Error('需要测试密码及本批生产验收证据');
const fixture=JSON.parse(await readFile(process.env.BIKE_ACCEPTANCE_FIXTURE,'utf8'));
assert(!fixture.error && fixture.fixtures?.taskId && fixture.prefix?.startsWith('AC'));
const prefix=`AUTH${Date.now().toString(36).toUpperCase()}`;
const evidence={prefix,startedAt:new Date().toISOString(),checks:[]};
let admin;
const createdUsers=[];
async function request(token,path,body,method=body===undefined?'GET':'POST',expectedStatus){
  const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json();
  if(expectedStatus){assert.equal(response.status,expectedStatus,`${path}: ${result.code} ${result.message}`);return result;}
  assert(response.ok && result.code==='SUCCESS',`${path}: ${response.status} ${result.code} ${result.message}`);return result.data;
}
const asAdmin=(path,body,method)=>request(admin,path,body,method);
function pass(name){evidence.checks.push({name,passed:true});console.log(`PASS ${name}`);}
try{
  admin=(await request(null,'/api/auth/login',{username:process.env.BIKE_TEST_USERNAME||'admin',password})).accessToken;
  const permissions=await asAdmin('/api/roles/permissions');
  async function role(suffix,codes){
    const r=await asAdmin('/api/roles',{roleCode:`${prefix}${suffix}`,roleName:`验收${suffix}`});
    const ids=codes.map(code=>{const p=permissions.find(p=>p.permissionCode===code);assert(p,`权限不存在 ${code}`);return p.id;});
    await asAdmin(`/api/roles/${r.id}/permissions`,{permissionIds:ids},'PUT');return r;
  }
  async function account(suffix,r,type='WEB_OPERATOR',workstationId=null,agentId=null){
    const initial=randomBytes(24).toString('hex'),updated=randomBytes(24).toString('hex');
    const u=await asAdmin('/api/users',{username:`${prefix}${suffix}`,password:initial,displayName:`验收${suffix}`,userType:type,workstationId,agentId});
    createdUsers.push(u.id);
    await asAdmin(`/api/users/${u.id}/roles`,{roleIds:[r.id]},'PUT');
    const login=await request(null,'/api/auth/login',{username:u.username,password:initial});
    await request(login.accessToken,'/api/auth/password',{currentPassword:initial,newPassword:updated},'PUT');
    const token=(await request(null,'/api/auth/login',{username:u.username,password:updated})).accessToken;
    const context=await request(token,'/api/auth/context');assert(!context.roles.includes('SUPER_ADMIN'));return {u,token,context};
  }
  const clerkRole=await role('CLERK',['PART_READ','PART_WRITE','ORDER_READ','ORDER_WRITE','MASTER_READ','VEHICLE_READ','INVENTORY_READ','STOCK_READ']);
  const clerk=await account('CLERK',clerkRole);
  assert(!clerk.context.roles.includes('WEB_OPERATOR'));
  for(const path of ['/api/parts','/api/vehicles','/api/master/models','/api/orders','/api/inventory','/api/inventory/summary','/api/stock-in','/api/stock-out']) await request(clerk.token,path);
  const part=await request(clerk.token,'/api/parts',{partSn:`${prefix}MOTOR`,partType:'MOTOR',qcStatus:'PASSED'});assert(part.id);
  const order=await request(clerk.token,'/api/orders',{customerId:fixture.fixtures.customerId,modelId:fixture.fixtures.modelId,orderQty:1});
  await request(clerk.token,`/api/orders/${order.id}/confirm`,{});
  await request(clerk.token,'/api/users',undefined,'GET',403);pass('自定义角色读写部件、订单、基础资料和库存且不能管理用户');
  const readRole=await role('READER',['PART_READ']);const reader=await account('READER',readRole);
  await request(reader.token,'/api/parts');
  await request(reader.token,'/api/parts',{partSn:`${prefix}DENIED`,partType:'MOTOR'},'POST',403);
  await request(reader.token,'/api/vehicles',undefined,'GET',403);pass('只读岗位不能写入或越权读取车辆');
  await asAdmin(`/api/users/${reader.u.id}/roles`,{roleIds:[]},'PUT');
  await request(reader.token,'/api/parts',undefined,'GET',401);pass('清空角色立即撤销已有 token 的访问');
  await asAdmin(`/api/users/${reader.u.id}/roles`,{roleIds:[readRole.id]},'PUT');
  await request(reader.token,'/api/parts');
  await asAdmin(`/api/roles/${readRole.id}/status?enabled=false`,undefined,'PUT');
  await request(reader.token,'/api/parts',undefined,'GET',401);pass('停用角色立即撤销已有 token 的访问');
  await asAdmin(`/api/users/${clerk.u.id}/status?enabled=false`,undefined,'PUT');
  await request(clerk.token,'/api/parts',undefined,'GET',401);pass('停用账号立即撤销已有 token 的访问');
  const stations=await asAdmin('/api/workstations');
  const inStation=stations.find(s=>s.workstationType==='STOCK_IN'),outStation=stations.find(s=>s.workstationType==='STOCK_OUT');assert(inStation&&outStation);
  const stockRole=await role('STOCK',['STOCK_READ','INVENTORY_READ','STOCK_IN_OPERATE','STOCK_OUT_OPERATE']);
  const warehouse=await account('WAREHOUSE',stockRole,'WEB_OPERATOR',outStation.id);
  assert(warehouse.context.workstationTypes?.includes('STOCK_OUT') || warehouse.context.workstations?.includes('STOCK_OUT'));
  const trace=await asAdmin(`/api/trace/frame/${fixture.fixtures.frameSn}`);
  const out=trace.stockOutRecords.find(r=>r.status==='COMPLETED'),stockIn=trace.stockInRecords.find(r=>r.status==='COMPLETED');assert(out&&stockIn);
  const outId=(await request(warehouse.token,`/api/stock-out/by-no/${out.stockOutNo}`)).id;
  const inId=(await request(warehouse.token,`/api/stock-in/by-no/${stockIn.stockInNo}`)).id;assert(outId&&inId);
  await request(warehouse.token,`/api/stock-in/${inId}/cancel?reason=permission-check`,{},'POST',403);
  await request(warehouse.token,`/api/stock-out/${outId}/cancel?reason=acceptance`,{});
  await asAdmin(`/api/users/${warehouse.u.id}`,{displayName:'验收入库更正员',userType:'WEB_OPERATOR',workstationId:inStation.id,agentId:null},'PUT');
  await request(warehouse.token,`/api/stock-in/${inId}/cancel?reason=acceptance`,{});pass('普通 Web 仓管绑定工位可撤销出入库且禁止跨工位');
  const agentMaster=await asAdmin('/api/master/agents',{code:`${prefix}AGENT`,name:'验收隔离代理商'});
  const agent=await account('AGENT',clerkRole,'AGENT',null,agentMaster.id);
  await request(agent.token,'/api/parts',undefined,'GET',403);
  await request(agent.token,'/api/vehicles',undefined,'GET',403);
  await request(agent.token,'/api/stock-in',undefined,'GET',403);
  await request(agent.token,'/api/inventory/summary',undefined,'GET',403);
  await request(agent.token,'/api/parts',{partSn:`${prefix}AGENTDENIED`,partType:'MOTOR'},'POST',403);
  await request(agent.token,'/api/orders',{customerId:fixture.fixtures.customerId,modelId:fixture.fixtures.modelId,orderQty:1},'POST',403);
  await request(agent.token,`/api/orders/${order.id}`,undefined,'GET',403);pass('代理商即使误配内部权限也不能读写内部档案或跨代理商订单');
  const pda=await account('PDA',clerkRole,'PDA_OPERATOR',inStation.id);
  await request(pda.token,'/api/parts',undefined,'GET',403);
  await request(pda.token,'/api/vehicles',undefined,'GET',403);
  await request(pda.token,'/api/orders',undefined,'GET',403);pass('PDA 不能读取内部档案或跨工位订单');
}catch(error){evidence.error=error.stack;process.exitCode=1;console.error(error.stack);}
finally{
  for(const id of createdUsers) try{await asAdmin(`/api/users/${id}/status?enabled=false`,undefined,'PUT');}catch(error){evidence.cleanupError=String(error);process.exitCode=1;}
}
evidence.finishedAt=new Date().toISOString();
if(process.env.BIKE_ACCEPTANCE_REPORT)await writeFile(process.env.BIKE_ACCEPTANCE_REPORT,JSON.stringify(evidence,null,2)+'\n');
console.log(`完成 ${evidence.checks.length} 项；${evidence.error||evidence.cleanupError?'失败':'通过'}；测试账号已请求停用。`);
