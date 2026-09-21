import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createTransport} from './transport.mjs';
const response=(status,data)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}});
test('expired token is renewed without changing the print result request',async()=>{
  const calls=[];
  const request=createTransport({base:'http://localhost',token:'expired',username:'printer',password:'test-only',fetcher:async(url,options)=>{
    calls.push({url,options});
    if(calls.length===1)return response(401,{code:'UNAUTHORIZED'});
    if(url.endsWith('/auth/login'))return response(200,{code:'SUCCESS',data:{accessToken:'renewed',mustChangePassword:false}});
    return response(200,{code:'SUCCESS',data:null});
  }});
  await request('/prints/job-1/result',{status:'SUBMITTED',message:'test'});
  assert.equal(calls.length,3);
  assert.equal(calls[0].url,calls[2].url);
  assert.equal(calls[0].options.body,calls[2].options.body);
  assert.equal(calls[2].options.headers.Authorization,'Bearer renewed');
});
test('business denial is surfaced rather than retried as printing',async()=>{
  let calls=0;
  const request=createTransport({base:'http://localhost',token:'valid',fetcher:async()=>{calls++;return response(409,{code:'PRINT_STATUS_CONFLICT',message:'任务状态已改变'});}});
  await assert.rejects(()=>request('/prints/job-1/result',{status:'SUBMITTED'}),e=>e.status===409);
  assert.equal(calls,1);
});
