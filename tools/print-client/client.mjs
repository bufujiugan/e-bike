import { chromium } from 'playwright';
import { mkdir, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const base = (process.env.BIKE_PRINT_URL || 'http://localhost:18080').replace(/\/$/, '');
const token = process.env.BIKE_PRINT_TOKEN;
const printer = process.env.BIKE_PRINT_PRINTER;
const terminal = process.env.BIKE_PRINT_TERMINAL || 'windows-production';
const previewId = process.argv[2] === '--preview' ? process.argv[3] : null;
const once=process.argv.includes('--once');
if (!token || (!previewId && !printer)) throw new Error('请设置 BIKE_PRINT_TOKEN 和 BIKE_PRINT_PRINTER；预览仅需 token');
if (!/^https:\/\//.test(base) && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(base)) throw new Error('远程服务必须使用 HTTPS');

async function request(path, body) {
  const response = await fetch(base + '/api/production' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000)
  });
  if (!response.ok) throw new Error(`请求失败 HTTP ${response.status}`);
  if (path.endsWith('/label')) return response.text();
  return (await response.json()).data;
}
const folder = await mkdtemp(join(tmpdir(), 'bike-print-'));
const browser = await chromium.launch({ headless: true, executablePath:process.env.BIKE_PRINT_BROWSER || undefined });
async function render(job) {
  const snapshot = typeof job.snapshot === 'string' ? JSON.parse(job.snapshot) : job.snapshot;
  const page = await browser.newPage({deviceScaleFactor:203/96, viewport:{width:1000,height:1400}});
  try {
    await page.setContent(await request(`/prints/${job.id}/label`));
    await page.evaluate(() => Promise.all([...document.images].map(img => img.decode())));
    const label = page.locator('.label');
    const bounds = await label.boundingBox();
    if (!bounds || bounds.height > snapshot.heightMm * 96/25.4 + 2) throw new Error('标签内容超出纸张高度，请调整版式');
    const image = join(folder, `${job.id}.png`);
    await label.screenshot({path:image});
    return {image,snapshot};
  } finally { await page.close(); }
}
try {
  if (previewId) {
    const job = await request(`/prints/${encodeURIComponent(previewId)}`);
    const result = await render(job);
    console.log(`预览已生成（未领取、未打印）：${result.image}`);
  } else {
    if (process.platform !== 'win32') throw new Error('系统打印客户端仅支持 Windows');
    console.log(`打印终端已启动：${terminal}，打印机：${printer}`);
    while (true) {
      const job = await request('/prints/claim',{terminal});
      if (!job) { await new Promise(resolve => setTimeout(resolve,2000)); continue; }
      let status='FAILED', message='渲染失败，未发送到打印机';
      try {
        const {image,snapshot} = await render(job);
        status='UNKNOWN'; message='系统打印调用未确认，请核对实物';
        const args=['-NoProfile','-File',join(dirname(fileURLToPath(import.meta.url)),'print-image.ps1'),'-ImagePath',image,'-PrinterName',printer,'-WidthMm',String(snapshot.widthMm),'-HeightMm',String(snapshot.heightMm)];
        if(process.env.BIKE_PRINT_PDF_DIRECTORY){
          if(printer!=='Microsoft Print to PDF')throw new Error('PDF验收目录只能与虚拟PDF打印机同时使用');
          await mkdir(process.env.BIKE_PRINT_PDF_DIRECTORY,{recursive:true});
          args.push('-OutputFile',join(resolve(process.env.BIKE_PRINT_PDF_DIRECTORY),`${job.id}.pdf`));
        }
        const result=spawnSync('powershell.exe',args,{windowsHide:true,encoding:'utf8',timeout:60000});
        if(result.status===0 && result.stdout.includes('SUBMITTED')) {status='SUBMITTED';message='已提交 Windows 打印队列，实物出纸待现场确认';}
      } catch(error) { console.error(`任务 ${job.id}：${error.message}`); }
      // 回报失败时终止客户端，不重复提交实物打印；服务端超时转为待核对。
      await request(`/prints/${job.id}/result`,{status,message});
      console.log(`${job.id} ${status}`);
      if(once)break;
    }
  }
} finally { await browser.close(); }
