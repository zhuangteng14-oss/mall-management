// probe-c3-shell2.mjs —— C3 骨架**第二笔**（admin 壳：侧边栏 / 顶栏身份 / 两个域入口）的
// 页面级验收（2026-10-08）。跑在**真 dev server（8080）+ 真后端（8088，a3 profile ⇒ VM 库）**上。
//
// 为什么必须真后端：本笔有「**退出**」这一项 —— 它的判据是**服务端会话真的被销掉**
//   （退出后再直开 `/admin` 必须被弹回）。mock 环境里那一发 `/user/logout` 是假的，验不出来。
//
// 判据：
//   ① 登录 `admin / 123@admin` → 落 `#/admin`
//   ② 侧边栏在：`.a-side` + 品牌 + 3 个域入口 + 2 组子项（共 6 行「子项占位」）
//   ③ 顶栏在：面包屑 = 「管理端 / 概览」；右组 = 身份（admin · 管理员）+ 退出按钮
//   ④ 点「业务域」→ `#/admin/biz`，标题变「业务域」，高亮**唯一**且落在「业务域」上
//   ⑤ 点「风控域」→ `#/admin/risk`，标题变「风控域」，高亮唯一且落在「风控域」上
//   ⑥ 点「概览」→ 回 `#/admin`
//   ⑦ 点「退出」→ 落 `#/login…`
//   ⑧ 退出后**不带任何本地态**再打一发 `/user/me` ⇒ **401**（证明销的是**服务端会话**，不只是本地清）
//   ⑨ 退出后直开 `#/admin` ⇒ 被守卫弹回登录页
//   ⑩ 无 JS 异常
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
const API = process.env.API || 'http://localhost:8088'
const WebSocket = createRequire(path.join(MALL, 'package.json'))('ws')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const rows = []
const ok = (label, cond, got) => rows.push([cond ? 'OK  ' : 'FAIL', label, got === undefined ? '' : JSON.stringify(got)])

const port = 9700 + Math.floor(Math.random() * 200)
const udd = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-shell2-'))
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

// 页面侧小工具：按文本点一个元素 / 读一次壳的状态
const HELPERS = `
  window.__click = (sel, text) => { const el = [...document.querySelectorAll(sel)].find(e => e.textContent.trim() === text); if (!el) return false; el.click(); return true }
  window.__state = () => ({
    hash: location.hash,
    h1: (document.querySelector('h1') || {}).textContent || '',
    side: !!document.querySelector('.a-side'),
    brand: ((document.querySelector('.a-brand') || {}).innerText || '').replace(/\\s+/g, ' ').trim(),
    doms: [...document.querySelectorAll('.a-dom')].map(e => e.textContent.trim()),
    on: [...document.querySelectorAll('.a-dom.on')].map(e => e.textContent.trim()),
    subs: document.querySelectorAll('.a-sub').length,
    groups: document.querySelectorAll('.a-subs').length,
    crumb: ((document.querySelector('.a-crumb') || {}).innerText || '').replace(/\\s+/g, ' ').trim(),
    who: ((document.querySelector('.a-who') || {}).innerText || '').replace(/\\s+/g, ' ').trim(),
    out: !!document.querySelector('.a-out'),
    sideBg: getComputedStyle(document.querySelector('.a-side') || document.body).backgroundColor,
  })
`

// ── ① 首航 + 登录 ───────────────────────────────────────────────────────────
await send('Page.navigate', { url: BASE + '/admin.html' })
let diag = null
for (let i = 0; i < 120; i++) {
  await sleep(500)
  diag = await evalJS(`({ hash: location.hash, appLen: ((document.querySelector('#app') || {}).innerHTML || '').length, inputs: [...document.querySelectorAll('input')].map(e => e.id) })`)
  if (diag.appLen > 0 && diag.inputs.includes('acc')) break
}
await evalJS(HELPERS)
const fill = await evalJS(`(() => {
  const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
  const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
  if (!acc || !pwd) return false
  set(acc, 'admin'); set(pwd, '123@admin')
  return window.__click('button.btn.btn-primary.btn-block', '登录')
})()`)
ok('① 登录表单已填充且登录按钮可点', fill === true, fill)

let s = null
for (let i = 0; i < 60; i++) { await sleep(500); s = await evalJS(`window.__state()`); if (/^#\/admin/.test(s.hash)) break }
console.log('── 落 /admin 后的壳状态 ──\n' + JSON.stringify(s, null, 1))
ok('② 登录后落 #/admin', s.hash === '#/admin', s.hash)
await shot(OUT + 'shell2-01-overview.png').then((f) => console.log('截图 → ' + f)).catch(() => { })

ok('③ 侧边栏在（品牌 + 3 个域入口 + 2 组子项 / 6 行占位）',
  s.side && s.brand.includes('管理端') && s.doms.join('/') === '概览/业务域/风控域' && s.groups === 2 && s.subs === 6,
  { side: s.side, brand: s.brand, doms: s.doms, groups: s.groups, subs: s.subs })
ok('④ 顶栏在（面包屑「管理端 / 概览」+ 身份 admin·管理员 + 退出钮）',
  s.crumb === '管理端 / 概览' && s.who === 'admin 管理员' && s.out,
  { crumb: s.crumb, who: s.who, out: s.out })
ok('⑤ 当前域高亮**唯一**且落在「概览」', s.on.length === 1 && s.on[0] === '概览', s.on)

// ── ④⑤ 切域 ────────────────────────────────────────────────────────────────
const clickDom = async (label) => {
  await evalJS(`window.__click('.a-dom', ${JSON.stringify(label)})`)
  let st = null
  for (let i = 0; i < 40; i++) { await sleep(250); st = await evalJS(`window.__state()`); if (st.h1.includes(label) && st.hash !== '#/admin') break }
  return st
}

const biz = await clickDom('业务域')
console.log('── 业务域 ──\n' + JSON.stringify(biz, null, 1))
await shot(OUT + 'shell2-02-biz.png').then((f) => console.log('截图 → ' + f)).catch(() => { })
ok('⑥ 点「业务域」⇒ #/admin/biz，标题「业务域」，高亮唯一',
  biz.hash === '#/admin/biz' && biz.h1.trim() === '业务域' && biz.on.length === 1 && biz.on[0] === '业务域',
  { hash: biz.hash, h1: biz.h1, on: biz.on })
ok('⑦ 面包屑跟着走到「管理端 / 业务域」', biz.crumb === '管理端 / 业务域', biz.crumb)

const risk = await clickDom('风控域')
ok('⑧ 点「风控域」⇒ #/admin/risk，标题「风控域」，高亮唯一',
  risk.hash === '#/admin/risk' && risk.h1.trim() === '风控域' && risk.on.length === 1 && risk.on[0] === '风控域',
  { hash: risk.hash, h1: risk.h1, on: risk.on })
await shot(OUT + 'shell2-03-risk.png').then((f) => console.log('截图 → ' + f)).catch(() => { })

const home = await clickDom('概览')
ok('⑨ 点「概览」⇒ 回 #/admin', home.hash === '#/admin' && home.on.length === 1 && home.on[0] === '概览', { hash: home.hash, on: home.on })

// ── ⑦⑧⑨ 退出 ───────────────────────────────────────────────────────────────
await evalJS(`window.__click('.a-out', '退出')`)
let logoutSt = null
for (let i = 0; i < 40; i++) { await sleep(250); logoutSt = await evalJS(`({ hash: location.hash, side: !!document.querySelector('.a-side') })`); if (/^#\/login/.test(logoutSt.hash)) break }
ok('⑩ 点「退出」⇒ 落 #/login，且管理端壳已卸载', /^#\/login/.test(logoutSt.hash) && !logoutSt.side, logoutSt)

await sleep(1200)   // 那一发 `/user/logout` 不 await（fail-open），给它落地的工夫
const meStatus = await evalJS(`(async () => { try { const r = await fetch(${JSON.stringify(API + '/user/me')}, { method: 'POST', credentials: 'include' }); return r.status } catch (e) { return 'ERR:' + e.message } })()`)
ok('⑪ 退出后**服务端会话真的没了**（再打 /user/me ⇒ 401）', meStatus === 401, meStatus)

await send('Page.navigate', { url: BASE + '/admin.html#/admin' })
let bounce = null
for (let i = 0; i < 60; i++) { await sleep(500); bounce = await evalJS(`({ hash: location.hash, side: !!document.querySelector('.a-side') })`); if (/^#\/login/.test(bounce.hash)) break }
ok('⑫ 退出后直开 #/admin ⇒ 被守卫弹回登录页', /^#\/login/.test(bounce.hash) && !bounce.side, bounce)
await shot(OUT + 'shell2-04-after-logout.png').then((f) => console.log('截图 → ' + f)).catch(() => { })

ok('⑬ 无 JS 异常', errs.length === 0, errs.length)
if (errs.length) console.log('── JS 异常 ──\n' + errs.join('\n'))

for (const [st, l, g] of rows) console.log(`${st} ${l}${g ? '  → ' + g : ''}`)
const bad = rows.filter((r) => r[0] === 'FAIL').length
console.log(`\nc3-shell2: ${rows.length} 项 / ${bad} 失败`)
try { ws.close() } catch (e) { }
try { proc.kill() } catch (e) { }
await sleep(200)
try { fs.rmSync(udd, { recursive: true, force: true }) } catch (e) { }
process.exit(bad ? 1 : 0)
