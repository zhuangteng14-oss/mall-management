// probe-c3-admin-accept.mjs —— **C3 骨架第一笔的页面级验收**（2026-10-08）。
//
// 判据：用 `admin / 123@admin` 走**真登录流程**，最后**落在 `#/admin` 且是丁版暗色**。
//
// ⚠️ 为什么不去打用户那个 8080 dev server：
//   它 20:09 从 IDEA 起的，**早于** `vue.config.js` 多入口那一改 ⇒ `/admin.html` 没被
//   html-webpack-plugin 接管，被当静态文件原样吐（`<%= %>` 未替换）⇒ 页面是白的。
//   ⇒ 本探针改测**已构建产物** `dist/`（含同一套源码），自己起静态服务、自己带 CDP 底座，
//     **一个用户的进程都不动**。
//
// ⚠️ 为什么自带底座而不 `import './_cdp.mjs'`：`_cdp.mjs` 的 mock 把
//   `Access-Control-Allow-Origin` 写死成 `http://localhost:8080`；本探针的页面源是 8091，
//   带凭据的跨源请求会被浏览器按 CORS 拒掉。**不改共享底座** ⇒ 只好自己起浏览器并加
//   `--disable-web-security`（否则就要去占用户的 8080 端口）。
//   ⚠️ 这条只影响**本探针**的取材方式，不影响被验的代码。
//
// ⚠️ mock 是**状态化**的，复现真后端的回答（`:8090` 实测）：
//     未登录时 `/user/me` ⇒ 401（前端 fail-open → 当匿名）
//     `/user/loginSelect` 命中 ⇒ 裸体 `3`（真后端逐字如此，前端 `res.text()` 后比 "3"）
//     此后 `/user/me` ⇒ `{"role":3,"success":true}`（真后端对 cookie 管理员的**降级作答**）
//   ⇒ 这样走的才是「登录 → 落 /admin」那条**真路径**（含 `LoginPage` 的 role==="3" 分支 +
//     `landingAfterLogin`），而不是被 `restoreIdentity` 直接恢复出来的捷径。
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import http from 'node:http'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const MALL = 'C:/Users/86159/Desktop/软件工程/期末项目/Mall-management-web/mall-management-system'
const DIST = MALL + '/dist'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/美化/c3-admin-preview/'
const WebSocket = createRequire(path.join(MALL, 'package.json'))('ws')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ── 结果表 ─────────────────────────────────────────────────────────────────
const rows = []
const ok = (label, cond, got) => rows.push([cond ? 'OK  ' : 'FAIL', label, got === undefined ? '' : JSON.stringify(got)])

// ── ① 静态服务（roots = dist/）──────────────────────────────────────────────
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.svg': 'image/svg+xml', '.map': 'application/json' }
const ROOT = path.resolve(DIST)   // ⚠️ 必须 resolve 成绝对路径：DIST 是正斜杠，path.join 在 Windows 上
                                  //    返回反斜杠 ⇒ `startsWith(DIST)` 恒假 ⇒ **每个请求都 404**（第 2 轮实测）。
const srv = http.createServer((req, res) => {
  const p = decodeURIComponent(req.url.split('?')[0])
  const f = path.resolve(ROOT, '.' + p)
  if ((f !== ROOT && !f.startsWith(ROOT + path.sep)) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end('nope') }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(f).toLowerCase()] || 'application/octet-stream' })
  fs.createReadStream(f).pipe(res)
})
// ⚠️ 必须绑**全接口**：只绑 127.0.0.1 的话 `localhost` 先解析到 `::1`（IPv6）⇒ 连接被拒 ⇒
//    页面停在空文档（第 1 轮实测：url 是 admin.html，但 `#app` 不存在、`scripts` 为空）。
await new Promise((r) => srv.listen(8091, r))
console.log('静态服务: http://localhost:8091/  (root = dist/)')

// ── ② 起 Edge（headless + 关同源策略，见文件头）──────────────────────────────
const port = 9600 + Math.floor(Math.random() * 300)
const udd = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-c3-'))
const proc = spawn(EDGE, [
  `--remote-debugging-port=${port}`, `--user-data-dir=${udd}`,
  '--headless=new', '--disable-gpu', '--disable-web-security',
  '--no-first-run', '--no-default-browser-check', '--disable-extensions',
  '--window-size=1440,900', 'about:blank'
], { stdio: 'ignore' })

const get = (u) => new Promise((res, rej) => http.get(u, (r) => { let d = ''; r.on('data', (c) => (d += c)); r.on('end', () => res(d)) }).on('error', rej))
let list = null
for (let i = 0; i < 120; i++) { try { list = JSON.parse(await get(`http://127.0.0.1:${port}/json/list`)) } catch (e) { list = null } if (list && list.some((t) => t.type === 'page')) break; await sleep(100) }
const ws = new WebSocket(list.find((t) => t.type === 'page').webSocketDebuggerUrl, { perMessageDeflate: false, maxPayload: 256 * 1024 * 1024 })
await new Promise((r, j) => { ws.on('open', r); ws.on('error', j) })

let id = 0
const pend = new Map(); const listeners = []
ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.id && pend.has(m.id)) { const { res, rej } = pend.get(m.id); pend.delete(m.id); m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result) }
  else if (m.method) for (const fn of listeners) fn(m)
})
const send = (method, params = {}) => new Promise((res, rej) => { const i = ++id; pend.set(i, { res, rej }); ws.send(JSON.stringify({ id: i, method, params })) })

await send('Page.enable'); await send('Runtime.enable')
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

// ── ③ 状态化 mock：只拦 8088 ────────────────────────────────────────────────
let loggedIn = false
const seen = []
await send('Fetch.enable', { patterns: [{ urlPattern: '*8088*' }] })
listeners.push(async (m) => {
  if (m.method !== 'Fetch.requestPaused') return
  const { requestId, request } = m.params
  const u = request.url, p = u.split('8088')[1] || u
  seen.push((request.method || 'GET') + ' ' + p)
  let status = 200, body = '{}'
  if (p.startsWith('/user/loginSelect')) { loggedIn = true; body = '3' }                       // 真后端返裸体 3
  else if (p.startsWith('/user/me')) { if (loggedIn) { status = 200; body = '{"role":3,"success":true}' } else { status = 401; body = '{}' } }
  else if (p.startsWith('/user/token')) { status = 401; body = '{}' }                          // 仓里**刻意**不给 cookie 管理员铸票（见 slot-registry §十三 13-5）
  try {
    await send('Fetch.fulfillRequest', {
      requestId, responseCode: status, body: Buffer.from(body).toString('base64'),
      responseHeaders: [
        { name: 'Content-Type', value: 'application/json' },
        { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8091' },
        { name: 'Access-Control-Allow-Credentials', value: 'true' },
        { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
        { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' }
      ]
    })
  } catch (e) { }
})

const evalJS = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('eval 失败：' + JSON.stringify(r.exceptionDetails.exception))
  return r.result.value
}
const shot = async (f) => { const r = await send('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(f, Buffer.from(r.data, 'base64')); return f }

// ══ 验收 ═══════════════════════════════════════════════════════════════════
await send('Page.navigate', { url: 'http://localhost:8091/admin.html' })
await sleep(3000)

// 诊断（第 1 轮就崩在 #acc 为 null ⇒ 先看清页面到底停在哪）
const diag = await evalJS(`({
  url: location.href,
  hash: location.hash,
  theme: document.documentElement.getAttribute('data-theme'),
  appHTML: (document.querySelector('#app') || {}).innerHTML ? document.querySelector('#app').innerHTML.length : -1,
  inputs: [...document.querySelectorAll('input')].map(e => e.id || e.type),
  btns: [...document.querySelectorAll('button')].map(e => e.textContent.trim()).slice(0, 14),
  scripts: [...document.scripts].map(s => s.src.split('/').pop()).filter(Boolean),
  err: window.__c3err || null
})`)
console.log('── 诊断 ──\n' + JSON.stringify(diag, null, 1))
console.log('── 8088 请求序列（至今）──\n' + (seen.join('\n') || '（无）'))

ok('① <html data-theme="admin">（admin.html 真生效）', await evalJS(`document.documentElement.getAttribute('data-theme')`) === 'admin', await evalJS(`document.documentElement.getAttribute('data-theme')`))
const hash0 = await evalJS('location.hash')
ok('② 未登录直开 /admin.html ⇒ 被守卫送回登录页（带 redirect=/admin）', /^#\/login\?redirect=%2Fadmin|^#\/login\?redirect=\/admin/.test(hash0), hash0)

// 填表（v-model 听 input 事件，必须派发）
await evalJS(`(() => {
  window.__c3err = null
  window.addEventListener('error', e => { window.__c3err = String(e.message) })
  const set = (sel, v) => { const e = document.querySelector(sel); if (!e) throw new Error('缺 ' + sel); e.value = v; e.dispatchEvent(new Event('input', { bubbles: true })); };
  set('#acc', 'admin'); set('#pwd', '123@admin');
  return document.querySelector('#acc').value + '/' + document.querySelector('#pwd').value;
})()`)
await sleep(300)

// 账号面那个「登录」按钮（`.btn-block` + 文案「登录」+ 未 disabled；手机号面那个是 disabled 的）
const clicked = await evalJS(`(() => {
  const b = [...document.querySelectorAll('button.btn-block')].find(b => b.textContent.trim() === '登录' && !b.disabled);
  if (!b) return null; b.click(); return b.textContent.trim();
})()`)
ok('③ 登录按钮可点（账号面）', clicked === '登录', clicked)
await sleep(2000)

const hash1 = await evalJS('location.hash')
ok('④ **落在 /admin**（真登录 → role=3 → landingAfterLogin）', hash1 === '#/admin', hash1)

const probe = await evalJS(`(() => {
  const cs = getComputedStyle(document.body)
  const root = getComputedStyle(document.documentElement)
  return {
    bodyBg: cs.backgroundColor, bodyFg: cs.color,
    brand: root.getPropertyValue('--brand').trim(),
    brandInk: root.getPropertyValue('--brand-ink').trim(),
    bg: root.getPropertyValue('--bg').trim(),
    c6: root.getPropertyValue('--c6').trim(),
    tipBg: root.getPropertyValue('--tip-bg').trim(),
    txt: (document.querySelector('.a-main') || document.body).innerText.slice(0, 400),
    shell: !!document.querySelector('.admin-shell'),
    tabs: [...document.querySelectorAll('.a-tab')].map(e => e.textContent.trim())
  }
})()`)

ok('⑤ 丁版暗色生效：body 底色 = #101828', probe.bodyBg === 'rgb(16, 24, 40)', probe.bodyBg)
ok('⑥ token 换值：--bg = #101828', probe.bg.toLowerCase() === '#101828', probe.bg)
ok('⑦ 填充 / 文字两分：--brand=#2f6fed、--brand-ink=#5b90ff', probe.brand.toLowerCase() === '#2f6fed' && probe.brandInk.toLowerCase() === '#5b90ff', probe.brand + ' / ' + probe.brandInk)
ok('⑧ 图表 10 色 + 辅助 6 已落（--c6 / --tip-bg）', probe.c6.toLowerCase() === '#06b6d4' && probe.tipBg.toLowerCase() === '#1f2a3f', probe.c6 + ' / ' + probe.tipBg)
ok('⑨ AdminShell 外壳渲染', probe.shell === true, probe.shell)
ok('⑩ 占位页内容渲染', probe.txt.includes('管理端骨架已就绪'), probe.txt.slice(0, 90).replace(/\s+/g, ' '))

console.log('\n── 页面文本 ──\n' + probe.txt.replace(/\n{2,}/g, '\n').split('\n').slice(0, 12).join('\n'))
console.log('\n── 顶栏 tab ──\n' + JSON.stringify(probe.tabs))
console.log('\n── 8088 请求序列 ──\n' + seen.join('\n'))
console.log('\n── 截图 ──')
for (const [f, u] of [['c3-accept-login.png', 'http://localhost:8091/admin.html'], ['c3-accept-admin.png', hash1]]) {
  if (f.includes('login')) { await send('Page.navigate', { url: 'http://localhost:8091/admin.html' }); await sleep(2000) }
  console.log('  ' + await shot(OUT + f))
}

for (const [s, l, g] of rows) console.log(`${s} ${l}${g ? '  → ' + g : ''}`)
const bad = rows.filter((r) => r[0] === 'FAIL').length
console.log(`\nc3-admin-accept: ${rows.length} 项 / ${bad} 失败`)

try { ws.close() } catch (e) { }
try { proc.kill() } catch (e) { }
srv.close()
await sleep(200)
try { fs.rmSync(udd, { recursive: true, force: true }) } catch (e) { }
process.exit(bad ? 1 : 0)
