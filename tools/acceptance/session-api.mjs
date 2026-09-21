import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {writeFile} from 'node:fs/promises';

if(process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
const base=process.env.BIKE_API_URL || 'http://127.0.0.1:18180';
const prefix=`SESSION${Date.now().toString(36).toUpperCase()}`;
const evidence={prefix,checks:[]};
let admin,user;
async function request(token,path,body,method=body===undefined?'GET':'POST',status=200){
  const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json();
  assert.equal(response.status,status,`${path}: ${result.code}`);
  if(status===200)assert.equal(result.code,'SUCCESS');
  return result.data;
}
const login=(username,password)=>request(null,'/api/auth/login',{username,password});
function pass(name){evidence.checks.push({name,passed:true});console.log(`PASS ${name}`);}
try {
  admin=(await login('admin',process.env.BIKE_TEST_PASSWORD)).accessToken;
  const roles=await request(admin,'/api/roles?page=1&pageSize=100');
  const role=roles.find(r=>r.roleCode==='WEB_OPERATOR');assert(role);
  const initial=randomBytes(24).toString('hex'),updated=randomBytes(24).toString('hex'),next=randomBytes(24).toString('hex');
  user=await request(admin,'/api/users',{username:prefix,password:initial,displayName:'会话验收',userType:'WEB_OPERATOR'});
  await request(admin,`/api/users/${user.id}/roles`,{roleIds:[role.id]},'PUT');
  const first=(await login(prefix,initial)).accessToken;
  await request(first,'/api/auth/password',{currentPassword:initial,newPassword:updated},'PUT');
  await request(first,'/api/auth/context',undefined,'GET',401);
  pass('首次改密后旧凭证拒绝');
  const a=(await login(prefix,updated)).accessToken,b=(await login(prefix,updated)).accessToken;assert.notEqual(a,b);
  assert.equal((await request(a,'/api/auth/me')).accessToken,a);
  await request(a,'/api/auth/logout',{});
  for(const path of ['/api/auth/me','/api/auth/context','/api/orders'])await request(a,path,undefined,'GET',401);
  await request(b,'/api/auth/context');pass('退出撤销当前会话且另一独立登录仍有效，me不续发');
  const c=(await login(prefix,updated)).accessToken;
  await request(b,'/api/auth/password',{currentPassword:updated,newPassword:next},'PUT');
  for(const token of [b,c])await request(token,'/api/auth/me',undefined,'GET',401);
  const d=(await login(prefix,next)).accessToken;await request(d,'/api/auth/context');
  pass('改密撤销所有旧会话，新密码登录有效');
  await request(admin,`/api/users/${user.id}/status?enabled=false`,{},'PUT');
  await request(admin,`/api/users/${user.id}/status?enabled=true`,{},'PUT');
  await request(d,'/api/auth/context',undefined,'GET',401);pass('停用再启用不恢复旧会话');
}catch(error){evidence.error=error.message;throw error;}
finally {
  if(user&&admin)await request(admin,`/api/users/${user.id}/status?enabled=false`,{},'PUT');
  evidence.completedAt=new Date().toISOString();
  if(process.env.BIKE_ACCEPTANCE_OUTPUT)await writeFile(process.env.BIKE_ACCEPTANCE_OUTPUT,JSON.stringify(evidence,null,2));
}
