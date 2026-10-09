// probe-c3-dev8080-login.mjs —— 真 dev server（8080）+ **真后端**（8088，此刻 a3 profile ⇒ VM 库）的
// **端到端登录核验**（2026-10-08，用户行动项 3）。
//
// 与另外两份的分工：
//   · probe-c3-admin-accept.mjs  —— 验**已构建产物** + **态化 mock**（判代码，不碰任何真进程）；
//   · probe-c3-dev8080.mjs       —— 验**真 dev server** 的接线，但**止于登录页**（不登录）；
//   · 本份                        —— 验**真 dev server + 真后端**的**整条登录链**：填表 → loginSelect →
//                                  /user/me → mintToken(401，fail-open) → 落 `#/admin` → 暗色壳渲染。
//
// ⚠️ 三处**有意**不做，别当成漏写：
//   ① 不 mock 任何请求 —— 本份的**全部价值**就是「真的走了一遍真后端」；
//   ② 不写库 —— 登录只**读** `login` 表；`/user/me` 走识别口径、只读会话。骨架阶段不写库。
//   ③ 不 `--disable-web-security` —— 页面 origin 就是 8080，正是后端 CORS 白名单那一条。
//
// 判据（每一项都指向一个**只能由真链**产生的事实）：
//   ① `<html data-theme="admin">`（dev server 的模板处理生效）
//   ② 首航 hash 归一化 ⇒ `#/login?redirect=%2Fadmin`
//   ③ 用 `admin / 123@admin` 点登录后，hash 落进 `#/admin`
//   ④ `AdminShell` 真渲染（`.a-top` 在、品牌字在、三个 `.a-tab` 在）
//   ⑤ body 底色是丁版暗色（`rgb(16, 24, 40)` = `#101828`）
//   ⑥ 无 JS 异常
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
const udd = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-live-'))
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

// ── 首航 ────────────────────────────────────────────────────────────────────
await send('Page.navigate', { url: BASE + '/admin.html' })
let diag = null
for (let i = 0; i < 120; i++) {
  await sleep(500)
  diag = await evalJS(`({
    url: location.href, hash: location.hash,
    theme: document.documentElement.getAttribute('data-theme'),
    title: document.title,
    appLen: ((document.querySelector('#app') || {}).innerHTML || '').length,
    inputs: [...document.querySelectorAll('input')].map(e => e.id || e.type),
  })`)
  if (diag.appLen > 0) break
}
console.log('── 首航诊断 ──\n' + JSON.stringify(diag, null, 1))
await shot(OUT + 'live-01-login.png').then((f) => console.log('截图 → ' + f)).catch(() => { })
ok('① <html data-theme="admin">（模板被 dev server 处理过）', diag.theme === 'admin', diag.theme)
ok('② 首航 hash 归一化 ⇒ 落在登录页且带 redirect=/admin', /^#\/login\?redirect=(\/|%2F)admin/.test(diag.hash), diag.hash)
ok('③ 登录页输入框在（#acc / #pwd）', diag.inputs.includes('acc') && diag.inputs.includes('pwd'), diag.inputs)

// ── 填表 → 点登录（v-model 要收到 input 事件，故走原生 setter）────────────────
const fill = await evalJS(`(() => {
  const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
  const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
  if (!acc || !pwd) return { ok: false }
  set(acc, 'admin'); set(pwd, '123@admin')
  const btns = [...document.querySelectorAll('button.btn.btn-primary.btn-block')]
  const btn = btns.find(b => b.textContent.trim() === '登录')
  if (!btn) return { ok: false, btns: btns.map(b => b.textContent.trim()) }
  btn.click()
  return { ok: true, acc: acc.value, pwdLen: pwd.value.length }
})()`)
console.log('── 填表 ──\n' + JSON.stringify(fill))
ok('④ 登录表单被填充且登录按钮可点', fill.ok === true, fill)

// ── 等落 /admin ─────────────────────────────────────────────────────────────
let land = null
for (let i = 0; i < 60; i++) {
  await sleep(500)
  land = await evalJS(`({
    hash: location.hash,
    tabs: [...document.querySelectorAll('.a-tab')].map(e => e.textContent.trim()),
    brand: (document.querySelector('.a-brand') || {}).innerText || '',
    hasTop: !!document.querySelector('.a-top'),
    bg: getComputedStyle(document.body).backgroundColor,
    text: document.body.innerText.slice(0, 200),
  })`)
  if (/^#\/admin/.test(land.hash)) break
}
console.log('── 落点诊断 ──\n' + JSON.stringify(land, null, 1))
await shot(OUT + 'live-02-admin.png').then((f) => console.log('截图 → ' + f)).catch(() => { })

ok('⑤ 登录后 hash 落进 #/admin', /^#\/admin/.test(land.hash), land.hash)
ok('⑥ AdminShell 渲染（.a-top + 品牌字 + 3 个 .a-tab）', land.hasTop && land.brand.includes('管理端') && land.tabs.length === 3, { brand: land.brand, tabs: land.tabs })
ok('⑦ body 底色 = 丁版暗色 rgb(16, 24, 40)', land.bg === 'rgb(16, 24, 40)', land.bg)
ok('⑧ 无 JS 异常', errs.length === 0, errs.length)
if (errs.length) console.log('── JS 异常 ──\n' + errs.join('\n'))

for (const [s, l, g] of rows) console.log(`${s} ${l}${g ? '  → ' + g : ''}`)
const bad = rows.filter((r) => r[0] === 'FAIL').length
console.log(`\nc3-dev8080-login: ${rows.length} 项 / ${bad} 失败`)
try { ws.close() } catch (e) { }
try { proc.kill() } catch (e) { }
await sleep(200)
try { fs.rmSync(udd, { recursive: true, force: true }) } catch (e) { }
process.exit(bad ? 1 : 0)
