// probe-c3-dev8080.mjs —— 打**真 dev server（8080）**的接线核验（2026-10-08）。
//
// 与 probe-c3-admin-accept.mjs 的分工：
//   · 那份验**已构建产物**（dist/ 起在 8091 + 态化 mock 8088）⇒ 验的是**代码**；
//   · 这份验**用户那个 8080 dev server**⇒ 验的是**接线**（vue.config.js 的 pages 有没有真生效、
//     `public/admin.html` 的 `<head>` 内联脚本有没有被 dev server 原样送出、vue-router 首航 hash
//     归一化有没有真跑）。选 dev server 而不是 dist，是因为**只有 dev server 走 html-webpack-plugin
//     的模板处理**，而首航 hash 那段脚本正住在模板里。
//
// 为什么**不用** `--disable-web-security`：页面 origin 就是 `http://localhost:8080`，
//   正是后端 CORS 白名单里的那一个 ⇒ 跨源请求（8088）本来就放行。上一份用它是因页面源是 8091。
//
// 判据（不依赖后端数据 —— 后端此刻连的是**本机库**，没有 admin 账号）：
//   ① `<html data-theme="admin">`（模板被处理过，不是原样静态文件）
//   ② 首航 hash 被归一化 ⇒ 最终落在 `#/login?redirect=%2Fadmin`（**这一条同时证明**：
//      内联脚本跑了 + vue-router 起来了 + 守卫按 roles:['3'] 拦了）
//   ③ 登录页的两个输入框在（`#acc` / `#pwd`）
//   ④ 无 JS 异常
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import http from 'node:http'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const MALL = 'C:/Users/86159/Desktop/软件工程/期末项目/Mall-management-web/mall-management-system'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/美化/c3-admin-preview/'
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = process.env.BASE || 'http://localhost:8080'
const WebSocket = createRequire(path.join(MALL, 'package.json'))('ws')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const rows = []
const ok = (label, cond, got) => rows.push([cond ? 'OK  ' : 'FAIL', label, got === undefined ? '' : JSON.stringify(got)])

const port = 9700 + Math.floor(Math.random() * 200)
const udd = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-dev-'))
const proc = spawn(EDGE, [
  `--remote-debugging-port=${port}`, `--user-data-dir=${udd}`,
  '--headless=new', '--disable-gpu',
  '--no-first-run', '--no-default-browser-check', '--disable-extensions',
  '--window-size=1440,900', 'about:blank',
], { stdio: 'ignore' })

const get = (u) => new Promise((res, rej) => http.get(u, (r) => { let d = ''; r.on('data', (c) => (d += c)); r.on('end', () => res(d)) }).on('error', rej))
let list = null
for (let i = 0; i < 150; i++) { try { list = JSON.parse(await get(`http://127.0.0.1:${port}/json/list`)) } catch (e) { list = null } if (list && list.some((t) => t.type === 'page')) break; await sleep(100) }
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

await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable')
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

const errs = []
listeners.push((m) => { if (m.method === 'Runtime.exceptionThrown') errs.push(JSON.stringify(m.params.exceptionDetails.exception && m.params.exceptionDetails.exception.description || m.params.exceptionDetails.text).slice(0, 300)) })
const evalJS = async (e) => { const r = await send('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error('eval: ' + JSON.stringify(r.exceptionDetails.exception)); return r.result.value }
const shot = async (f) => { const r = await send('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(f, Buffer.from(r.data, 'base64')); return f }

// 首航（dev server 首编译可能慢 ⇒ 轮询到 #app 有内容为止，最多 60s）
await send('Page.navigate', { url: BASE + '/admin.html' })
let diag = null
for (let i = 0; i < 120; i++) {
  await sleep(500)
  diag = await evalJS(`({
    ready: document.readyState,
    url: location.href, hash: location.hash,
    theme: document.documentElement.getAttribute('data-theme'),
    title: document.title,
    appLen: ((document.querySelector('#app') || {}).innerHTML || '').length,
    inputs: [...document.querySelectorAll('input')].map(e => e.id || e.type),
    scripts: [...document.scripts].map(s => (s.src || '').split('/').pop()).filter(Boolean),
    body: document.body.innerText.slice(0, 200)
  })`)
  if (diag.appLen > 0) break
}
console.log('── 诊断 ──\n' + JSON.stringify(diag, null, 1))
if (errs.length) console.log('── JS 异常 ──\n' + errs.join('\n'))

ok('① <html data-theme="admin">（模板被 dev server 处理过）', diag.theme === 'admin', diag.theme)
ok('② 首航 hash 归一化 ⇒ 落在登录页且带 redirect=/admin', /^#\/login\?redirect=(\/|%2F)admin/.test(diag.hash), diag.hash)
ok('③ 登录页输入框在（#acc / #pwd）', diag.inputs.includes('acc') && diag.inputs.includes('pwd'), diag.inputs)
ok('④ 无 JS 异常', errs.length === 0, errs.length)
ok('⑤ <title> 来自 vue.config.js 的 admin 条目', diag.title === '管理端 · Mall Management', diag.title)

console.log('\n── 页面文本（前 200）──\n' + (diag.body || '').replace(/\s+/g, ' '))
try { console.log('\n截图 → ' + await shot(OUT + 'dev8080-admin.png')) } catch (e) { }

for (const [s, l, g] of rows) console.log(`${s} ${l}${g ? '  → ' + g : ''}`)
const bad = rows.filter((r) => r[0] === 'FAIL').length
console.log(`\nc3-dev8080: ${rows.length} 项 / ${bad} 失败`)
try { ws.close() } catch (e) { }
try { proc.kill() } catch (e) { }
await sleep(200)
try { fs.rmSync(udd, { recursive: true, force: true }) } catch (e) { }
process.exit(bad ? 1 : 0)
