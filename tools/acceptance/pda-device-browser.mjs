// 仅在本机模拟 API 和设备桥接，不连接业务服务、不写生产数据。
import { createRequire } from 'node:module'
import { resolve, dirname, extname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { readFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import assert from 'node:assert/strict'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const require = createRequire(resolve(process.env.PDA_TEST_MODULES || resolve(root, 'output/pda-tests'), 'package.json'))
const { chromium } = require('playwright')
const server = createServer(async (req, res) => {
  const file = decodeURIComponent(req.url.split('?')[0]).replace(/^\/__bike_pda__\//, '')
  if (file.includes('..') || file.startsWith('/')) { res.writeHead(404).end(); return }
  try {
    let bytes = await readFile(resolve(root, 'bike-h5/dist', file || 'index.html'))
    if (process.env.PDA_TEST_LEGACY === '1' && (file === 'index.html' || !file)) {
      bytes = Buffer.from(bytes.toString().replace(/<script\b[^>]*type="module"[^>]*>[\s\S]*?<\/script>/g, '').replace(/ nomodule/g, ''))
    }
    res.setHeader('Content-Type', ({ '.js':'application/javascript', '.css':'text/css', '.html':'text/html' })[extname(file)] || 'application/octet-stream')
    res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; font-src 'self' data:; frame-src 'self' blob:; base-uri 'self'; form-action 'none'")
    res.end(bytes)
  } catch { res.writeHead(404).end() }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
const page = await browser.newPage({ viewport: { width: 393, height: 800 } })
const errors = [], requests = []
page.on('pageerror', error => errors.push(error.message))
const workOrder = { id: 1, workOrderNo:'WO-608', status:'RELEASED', skuCode:'SKU-608', productName:'测试车型', plannedQuantity:10, completedQuantity:0, inProgressQuantity:0 }
let scanCount = 0, claimCount = 0, authMode = 'healthy'
let releaseContext
const job = { id:'print-608', status:'PENDING', claimedBy:null }
await page.addInitScript(() => {
  window.nativePrints = []
  window.nativeScans = 0
  window.BikePda = { scan() { window.nativeScans++ }, settings() {}, info() { return '{"printWidth":384}' }, printBitmap(id, png) {
    window.nativePrints.push({id, png})
    window.dispatchEvent(new CustomEvent('bike-device-print', { detail: { id, status:'SUBMITTED', message:'模拟设备已接收，请人工核对并回报' } }))
  } }
})
await page.route('**/api/**', async route => {
  const req = route.request(), path = new URL(req.url()).pathname
  requests.push({ path, method:req.method(), body:req.postDataJSON() })
  let data = []
  if (path === '/api/auth/context') {
    if (authMode === 'pending') await new Promise(resolve => { releaseContext = resolve })
    if (authMode === 'network') { await route.abort('failed'); return }
    if (authMode === 'expired') { await route.fulfill({status:401,contentType:'application/json',body:JSON.stringify({message:'会话过期'})}); return }
    data = authMode === 'malformed' ? null : { username:'pda-test', roles:['SUPER_ADMIN'], permissions:[], workstations:['*'], mustChangePassword:false }
  }
  else if (path === '/api/auth/login') data = {accessToken:'local-mock-login-token'}
  else if (path === '/api/production/work-orders') data = { records:[workOrder] }
  else if (path.endsWith('/work-orders/1/scan')) {
    scanCount++; await new Promise(resolve => setTimeout(resolve, 150))
    data = { id:'task-608', workOrderId:1, status:scanCount >= 4 ? 'COMPLETED' : 'IN_PROGRESS', skuCode:'SKU-608', productName:'测试车型', qrCode:'QR-608', scans:Array.from({length:Math.min(scanCount,4)}, (_,i)=>({codeType:['FRAME','MOTOR','BATTERY','DISPLAY'][i],code:'SN-'+i})), workOrder }
  }
  else if (path.endsWith('/tasks/task-608/prints')) data = [job]
  else if (path.endsWith('/prints/print-608/claim')) { claimCount++; job.status = 'PROCESSING'; job.claimedBy = 'pda-test'; data = job }
  else if (path.endsWith('/prints/print-608/label')) {
    await route.fulfill({ contentType:'text/html', body:"<!doctype html><html><head><style>.label{width:50mm;min-height:30mm;background:white;color:black;padding:2mm;box-sizing:border-box}</style></head><body><article class='label'><h2>BIKE 608</h2><p>FRAME-608</p><p>标签模拟测试</p></article></body></html>" }); return
  }
  else if (path === '/api/master/warehouses') data = [{id:1,name:'仓库',code:'W',status:'ENABLED'}]
  else if (path.endsWith('/locations')) data = [{id:1,name:'库位',code:'L',status:'ENABLED'}]
  else if (path === '/api/stock-in/validate') { await new Promise(resolve => setTimeout(resolve, 150)); data = {valid:true,message:'校验通过，请确认入库'} }
  else if (path === '/api/master/customers') data = [{id:1,name:'客户608',code:'C',status:'ENABLED'}]
  else if (path === '/api/orders/stock-out-options') data = { records:[{id:1,orderNo:'ORDER-608',modelName:'车型',orderQty:10,shippedQty:0,status:'CONFIRMED'}],total:1,page:1 }
  else if (path === '/api/pi-dispatch/options') data = {records:[{id:1,piNo:'PI-608',customerName:'客户608',customerId:1}],total:1}
  else if (path === '/api/pi-dispatch/1') data = {id:1,piNo:'PI-608',customerName:'客户608',customerId:1,orders:[{orderId:2,orderNo:'PI-ORDER',skuCode:'SKU',status:'CONFIRMED',remainingQty:10}]}
  else if (path === '/api/stock-out/validate') { await new Promise(resolve => setTimeout(resolve, 150)); data = {valid:true,frameSn:req.postDataJSON().vehicleQrCode,message:'通过'} }
  await route.fulfill({ contentType:'application/json', body:JSON.stringify({code:'SUCCESS',data}) })
})
const emit = code => page.evaluate(code => window.dispatchEvent(new CustomEvent('bike-device-scan',{detail:{code}})), code)
const waitUntil = async predicate => { for(let i=0;i<80;i++){ if(await predicate())return; await page.waitForTimeout(100) } throw new Error('等待条件超时') }
try {
  await page.goto(`http://127.0.0.1:${server.address().port}/__bike_pda__/index.html`)
  await page.getByText('608 手持终端', {exact:true}).waitFor()
  if (process.env.PDA_TEST_LEGACY === '1') assert.equal(await page.evaluate(()=>typeof window.System),'object')
  await emit('LOGIN-SHOULD-IGNORE')
  assert.equal(requests.length, 0)
  console.log('PASS 登录页不接收业务扫码')
  await page.evaluate(() => localStorage.setItem('bike_access_token','local-mock-token'))
  authMode = 'pending'
  await page.reload()
  await page.getByText('正在加载工位信息…',{exact:true}).waitFor()
  assert.equal(await page.getByRole('tab',{name:'组装',exact:true}).count(),0)
  await emit('PENDING-MUST-IGNORE')
  assert.equal(scanCount,0)
  await waitUntil(()=>Boolean(releaseContext)); authMode = 'network'; releaseContext()
  await page.getByRole('button',{name:'重新加载工位',exact:true}).waitFor()
  assert.equal(await page.evaluate(()=>localStorage.getItem('bike_access_token')),'local-mock-token')
  assert.equal(requests.filter(r=>r.path==='/api/auth/logout').length,0)
  authMode = 'malformed'
  await page.getByRole('button',{name:'重新加载工位',exact:true}).click()
  await page.getByRole('button',{name:'重新加载工位',exact:true}).waitFor()
  authMode = 'healthy'
  await page.getByRole('button',{name:'重新加载工位',exact:true}).click()
  await waitUntil(()=>page.getByPlaceholder('首码自动建单车记录，四码可任意顺序').isEnabled())
  console.log('PASS 启动加载有提示，断网和异常响应可重试，不退出会话或提交扫码')
  authMode = 'expired'
  await page.reload()
  await page.getByPlaceholder('请输入密码',{exact:true}).waitFor()
  assert.equal(await page.evaluate(()=>localStorage.getItem('bike_access_token')),null)
  authMode = 'network'
  await page.getByPlaceholder('请输入密码',{exact:true}).fill('mock-password')
  await page.getByRole('button',{name:'登录工位'}).click()
  await page.getByRole('button',{name:'重新加载工位',exact:true}).waitFor()
  authMode = 'healthy'
  await page.getByRole('button',{name:'重新加载工位',exact:true}).click()
  await waitUntil(()=>page.getByPlaceholder('首码自动建单车记录，四码可任意顺序').isEnabled())
  console.log('PASS 401回到登录页，登录后账号加载失败也能重试恢复')
  await page.reload()
  await waitUntil(()=>page.getByPlaceholder('首码自动建单车记录，四码可任意顺序').isEnabled())
  await page.getByRole('button',{name:'启动手持机扫描',exact:true}).click()
  assert.equal(await page.evaluate(()=>window.nativeScans),1)
  assert.equal(scanCount,0)
  console.log('PASS 输入框旁扫码按钮触发原生扫描，收到条码前不提交业务')
  await emit('FRAME-608'); await emit('FAST-DUPLICATE')
  await waitUntil(()=>scanCount===1); await page.waitForTimeout(250)
  assert.equal(scanCount,1)
  for (const code of ['MOTOR-608','BATTERY-608','DISPLAY-608']) { await emit(code); await page.waitForTimeout(300) }
  assert.equal(scanCount,4)
  console.log('PASS 硬件扫码进入四码生产，忙碌时不重复提交')
  await page.getByText('打印、结果回报与补打', {exact:true}).click()
  await page.getByRole('button',{name:'人工打印所选任务'}).click()
  await waitUntil(()=>page.evaluate(()=>window.nativePrints.length===1))
  assert.equal(claimCount,1)
  const png = await page.evaluate(()=>window.nativePrints[0].png)
  const bytes = Buffer.from(png,'base64')
  assert.equal(bytes.readUInt32BE(16),384)
  assert.ok(bytes.readUInt32BE(20)>0)
  assert.equal(requests.filter(r=>r.path.endsWith('/result')).length,0)
  console.log('PASS 标签转384点PNG，先领取再送印，不自动回报实物成功')
  await page.getByRole('tab',{name:'入库',exact:true}).click()
  await emit('STOCKIN-608'); await emit('STOCKIN-FAST')
  await page.waitForTimeout(300)
  assert.equal(requests.filter(r=>r.path==='/api/stock-in/validate').length,1)
  assert.equal(requests.filter(r=>r.path==='/api/stock-in').length,0)
  assert.equal(scanCount,4)
  console.log('PASS 入库只校验，未确认不入库，离开组装后旧监听已注销')
  await page.getByRole('tab',{name:'返工',exact:true}).click()
  await emit('VEHICLE-608'); await page.waitForTimeout(50); await emit('NEW-PART-608')
  assert.equal(await page.getByPlaceholder('扫描车辆聚合码',{exact:true}).inputValue(),'VEHICLE-608')
  assert.equal(await page.getByPlaceholder('扫描新零部件 SN',{exact:true}).inputValue(),'NEW-PART-608')
  await page.getByPlaceholder('扫描车辆聚合码',{exact:true}).focus(); await emit('VEHICLE-REPLACE')
  assert.equal(await page.getByPlaceholder('扫描车辆聚合码',{exact:true}).inputValue(),'VEHICLE-REPLACE')
  assert.equal(requests.filter(r=>r.path==='/api/rework').length,0)
  console.log('PASS 返工按车辆/新件顺序填入，可重扫车辆，须手工确认换件')
  await page.getByRole('tab',{name:'出库',exact:true}).click()
  await page.getByRole('tab',{name:'PI 多车型整批出库',exact:true}).click()
  await page.getByText('PI-608',{exact:true}).first().click()
  await page.getByLabel('选择本次扫描SKU订单',{exact:true}).selectOption('2')
  await emit('PI-QR-608'); await page.waitForTimeout(300)
  const before = requests.filter(r=>r.path==='/api/stock-out/validate').length
  assert.equal(before,1)
  await page.getByRole('tab',{name:'按订单出库',exact:true}).click()
  await emit('HIDDEN-PI-MUST-IGNORE'); await page.waitForTimeout(200)
  assert.equal(requests.filter(r=>r.path==='/api/stock-out/validate').length,before)
  console.log('PASS PI扫码正常，切回订单后隐藏PI组件不再消费扫码')
  await page.screenshot({path:resolve(root,'output/pda-tests/pda-608.png'),fullPage:true})
  assert.deepEqual(errors,[])
  console.log('PASS 所有浏览器检查，无未捕获页面异常；原生硬件仍需真机验证')
} catch (error) {
  await page.screenshot({path:resolve(root,'output/pda-tests/failure.png'),fullPage:true})
  throw error
} finally { await browser.close(); await new Promise(resolve=>server.close(resolve)) }
