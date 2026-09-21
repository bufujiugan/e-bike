import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {readFile,writeFile} from 'node:fs/promises';
if(process.env.BIKE_ACCEPTANCE_WRITE!=='isolated')throw new Error('仅允许专用验收库');
const fixture=JSON.parse(await readFile(process.env.BIKE_ACCEPTANCE_FIXTURE,'utf8'));
assert(!fixture.error&&fixture.prefix.startsWith('ORDER'));
const f=fixture.fixtures,base=process.env.BIKE_API_URL||'http://127.0.0.1:18180';
const prefix='OP'+Date.now().toString(36).toUpperCase(),evidence={prefix,checks:[]},accounts=[];
let admin;
async function req(token,path,body,method=body===undefined?'GET':'POST',expected=200){
 const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});
 const data=await response.json();assert.equal(response.status,expected,`${path}: ${data.code}`);
 if(expected===200)assert.equal(data.code,'SUCCESS');return expected===200?data.data:data;
}
const a=(path,body,method)=>req(admin,path,body,method);
function pass(name){evidence.checks.push({name,passed:true});console.log('PASS '+name);}
async function account(suffix,agentId,roleId){
 const initial=randomBytes(24).toString('hex'),password=randomBytes(24).toString('hex'),username=prefix+suffix;
 const user=await a('/api/users',{username,password:initial,displayName:'转单权限验收',userType:agentId?'AGENT':'WEB_OPERATOR',agentId});accounts.push(user.id);
 await a(`/api/users/${user.id}/roles`,{roleIds:[roleId]},'PUT');
 let token=(await req(null,'/api/auth/login',{username,password:initial})).accessToken;
 await req(token,'/api/auth/password',{currentPassword:initial,newPassword:password},'PUT');
 return (await req(null,'/api/auth/login',{username,password})).accessToken;
}
try{
 admin=(await req(null,'/api/auth/login',{username:'admin',password:process.env.BIKE_TEST_PASSWORD})).accessToken;
 const order=await a(`/api/orders/${f.orderId}`),role=(await a('/api/roles')).find(r=>r.roleCode==='WEB_AGENT');assert(role);
 const own=await account('OWN',f.agentId,role.id),otherAgent=await a('/api/master/agents',{code:prefix,name:'其他代理商权限验收'});
 const other=await account('OTHER',otherAgent.id,role.id);
 assert.equal((await req(own,`/api/inventory-locks/${f.lockId}/orders`)).records[0].id,f.orderId);
 await req(other,`/api/inventory-locks/${f.lockId}/orders`,undefined,'GET',403);
 await req(other,`/api/orders/${f.orderId}`,undefined,'GET',403);
 const ownOverview=await req(own,`/api/modules/agent-dashboard?keyword=${order.sourceLockNo}`);
 assert.equal(ownOverview.total,1);assert.equal(ownOverview.rows[0][4],'1');
 assert.equal((await req(other,`/api/modules/agent-dashboard?keyword=${order.sourceLockNo}`)).total,0);
 pass('来源锁关联订单仅本代理可读，其他代理商订单与关联接口均拒绝');
 const payload={customerId:order.customerId,quantity:1,requestId:randomUUID()};
 for(const token of [own,other]) {
  await req(token,`/api/inventory-locks/${f.lockId}/orders`,payload,'POST',403);
  await req(token,`/api/orders/${f.orderId}/reservation/renew`,{hours:48,reason:'越权验收'},'POST',403);
  await req(token,`/api/orders/${f.orderId}/close-remainder`,{reason:'越权验收'},'POST',403);
 }
 pass('代理商不能代替内部业务员转单、续占用或关闭订单');
 const custom=await a('/api/roles',{roleCode:prefix+'READ',roleName:'占用只读验收'});
 const permissions=await a('/api/roles/permissions');
 const permissionIds=permissions.filter(p=>['ORDER_READ','AGENT_INVENTORY_READ'].includes(p.permissionCode)).map(p=>p.id);assert.equal(permissionIds.length,2);
 await a(`/api/roles/${custom.id}/permissions`,{permissionIds},'PUT');
 const readOnly=await account('READ',null,custom.id);
 assert.equal((await req(readOnly,`/api/inventory-locks/${f.lockId}/orders`)).total,1);
 await req(readOnly,`/api/inventory-locks/${f.lockId}/orders`,payload,'POST',403);
 await req(readOnly,`/api/orders/${f.orderId}/reservation/renew`,{hours:48,reason:'只读越权'},'POST',403);
 await req(readOnly,`/api/orders/${f.orderId}/close-remainder`,{reason:'只读越权'},'POST',403);
 await req(null,`/api/inventory-locks/${f.lockId}/orders`,undefined,'GET',401);
 pass('自定义只读岗位可查询但所有新增写入口拒绝，未登录拒绝');
}catch(error){evidence.error=error.message;process.exitCode=1;console.error(error.message);}
finally{
 for(const id of accounts)await a(`/api/users/${id}/status?enabled=false`,{},'PUT');
 if(process.env.BIKE_ACCEPTANCE_REPORT)await writeFile(process.env.BIKE_ACCEPTANCE_REPORT,JSON.stringify(evidence,null,2));
}
