import { readFile } from 'node:fs/promises';

export class PrintApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

/** 只重试 API 通信；本模块从不调用打印机，结果回报重试不会再次出纸。 */
export function createTransport({ base, token, tokenFile, username, password, fetcher=fetch }) {
  let currentToken = token;
  async function renew() {
    if (tokenFile) currentToken = (await readFile(tokenFile, 'utf8')).trim();
    if (!username || !password) return;
    const response = await fetcher(`${base}/api/auth/login`, {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({username,password}), signal:AbortSignal.timeout(15000),
    });
    const result=await response.json();
    if (!response.ok || result.code !== 'SUCCESS') throw new PrintApiError(response.status, '打印账号登录失败，请检查账号状态及凭据');
    if (result.data.mustChangePassword) throw new PrintApiError(403,'打印账号必须先在页面修改初始密码');
    currentToken=result.data.accessToken;
  }
  return async function request(path, body) {
    if (!currentToken) await renew();
    async function send() {
      return fetcher(base+'/api/production'+path, {
        method:body===undefined?'GET':'POST', headers:{Authorization:`Bearer ${currentToken}`,'Content-Type':'application/json'},
        body:body===undefined?undefined:JSON.stringify(body), signal:AbortSignal.timeout(15000),
      });
    }
    let response=await send();
    if (response.status===401 && (tokenFile || (username && password))) { await renew(); response=await send(); }
    if (!response.ok) {
      const result=await response.json().catch(()=>null);
      throw new PrintApiError(response.status,result?.message || `请求失败 HTTP ${response.status}`);
    }
    if(path.endsWith('/label')) return response.text();
    const result=await response.json();
    if(result.code!=='SUCCESS') throw new PrintApiError(400,result.message || '打印接口业务失败');
    return result.data;
  };
}
