// 将浏览器中已展示的项目配置转存至本机 DPAPI 文件，不记录配置内容。
const http = require('node:http')
const { randomBytes } = require('node:crypto')
const { spawn } = require('node:child_process')
const path = require('node:path')
const nonce = randomBytes(24).toString('hex')
let accepted = false
const server = http.createServer((req, res) => {
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader('Content-Security-Policy', "default-src 'none'; form-action 'self'; frame-ancestors 'none'")
  res.setHeader('Content-Type', 'text/html; charset=utf-8')
  const origin = `http://127.0.0.1:${server.address().port}`
  if (req.headers.host !== new URL(origin).host) { res.writeHead(400); res.end('Invalid host'); return }
  if (req.method === 'GET' && req.url === '/') {
    res.end(`<h1>BIKE PDA 本机配置保存</h1><p>仅写入本机受保护目录，不输出配置值。</p><form method="post" action="/save"><input type="hidden" name="nonce" value="${nonce}"><label>离线 AppKey <input name="appkey" type="password" autocomplete="off"></label><button type="submit">加密保存到本机</button></form>`)
    return
  }
  if (req.method !== 'POST' || req.url !== '/save' || req.headers.origin !== origin || accepted) { res.writeHead(403); res.end('Rejected'); return }
  let body = ''
  req.on('data', data => { body += data; if (body.length > 4096) req.destroy() })
  req.on('end', () => {
    const fields = new URLSearchParams(body)
    const value = fields.get('appkey') || ''
    if (fields.get('nonce') !== nonce || !/^[A-Za-z0-9+/_=-]{24,}$/.test(value)) { res.writeHead(400); res.end('Invalid input'); return }
    accepted = true
    const child = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(__dirname, 'receive-appkey.ps1')], { windowsHide: true, env: { ...process.env, PSModulePath: path.join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/Modules') }, stdio: ['pipe', 'ignore', 'pipe'] })
    let diagnostic = ''
    child.stderr.on('data', chunk => { diagnostic += chunk.toString() })
    child.stdin.end(value)
    child.on('error', () => { res.writeHead(500); res.end('保存失败，已有配置保留'); server.close() })
    child.on('exit', code => { res.writeHead(code === 0 ? 200 : 500); res.end(code === 0 ? 'AppKey 已加密保存成功。' : '保存失败，已有配置保留。'); console.log(code === 0 ? 'AppKey 已加密保存，无明文日志。' : '保存失败：' + diagnostic.replaceAll(value, '[redacted]').replace(/[A-Za-z0-9+/_=-]{24,}/g, '[redacted]')); server.close() })
  })
})
server.listen(0, '127.0.0.1', () => console.log(`http://127.0.0.1:${server.address().port}/`))
setTimeout(() => { server.close(); process.exit(1) }, 10 * 60 * 1000).unref()
