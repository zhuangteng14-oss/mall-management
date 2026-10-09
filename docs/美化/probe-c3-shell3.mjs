// probe-c3-shell3.mjs —— C3 骨架**第三笔**（11 个子项可点 / 可跳 / 可高亮）的页面级验收（2026-10-08）。
// 跑在**真 dev server（8080）+ 真后端（8088，a3 profile ⇒ VM 库）**上。
//
// 判据里**硬编码**了 11 个子项的名字与落点（而不是从侧边栏 DOM 里读回来比 —— 那等于自己验自己）。
// 期望值来源：用户裁决的子项清单（`docs/agent/c3-modules.md` §一）+ 本笔取的 URL 段。
//
// 关键判据：
//   ① 侧边栏共 **11** 条子项、分 **2** 组；域名是「业务域 / **可观测域**」（**不是**「风控域」）
//   ② 逐个点开**每一个子项** ⇒ 落点 / 标题 / 高亮三项都对
//   ③ **进子项后父域不熄灯** —— `.a-dom.on` 仍是所属域、`.a-sub.on` 是子项（两者同时亮、各一）
//   ④ 两个域入口页都把本域子项列成**可点的链接**
//   ⑤ 退出链仍通（回归第二笔，别为了第三笔把它碰坏）
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

// ── 期望表（用户裁决的子项清单）──────────────────────────────────────────────
const DOMAINS = [
  { label: '概览', to: '#/admin', subs: [] },
  {
    label: '业务域', to: '#/admin/business', subs: [
      ['用户与商家治理', '#/admin/business/accounts'],
      ['商品与类目治理', '#/admin/business/catalog'],
      ['订单治理', '#/admin/business/orders'],
      ['营销运营', '#/admin/business/marketing'],
      ['消息与内容治理', '#/admin/business/moderation'],
      ['系统通知', '#/admin/business/notify'],
    ],
  },
  {
    label: '可观测域', to: '#/admin/observe', subs: [
      ['攻击模拟器', '#/admin/observe/attack-sim'],
      ['指标面板', '#/admin/observe/metrics'],
      ['状态流转', '#/admin/observe/timeline'],
      ['实时日志', '#/admin/observe/logs'],
      ['权限与审计', '#/admin/observe/audit'],
    ],
  },
]
const ALL_SUBS = DOMAINS.flatMap((d) => d.subs.map(([l, t]) => ({ label: l, to: t, domain: d.label, domainTo: d.to })))

const rows = []
const ok = (label, cond, got) => rows.push([cond ? 'OK  ' : 'FAIL', label, got === undefined ? '' : JSON.stringify(got)])

const port = 9700 + Math.floor(Math.random() * 200)
const udd = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-shell3-'))
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

const HELPERS = `
  window.__click = (sel, text) => { const el = [...document.querySelectorAll(sel)].find(e => e.textContent.trim() === text); if (!el) return false; el.click(); return true }
  window.__state = () => ({
    hash: location.hash,
    h1: ((document.querySelector('h1') || {}).textContent || '').trim(),
    doms: [...document.querySelectorAll('.a-dom')].map(e => e.textContent.trim()),
    subsAll: [...document.querySelectorAll('.a-sub')].map(e => e.textContent.trim()),
    domOn: [...document.querySelectorAll('.a-dom.on')].map(e => e.textContent.trim()),
    subOn: [...document.querySelectorAll('.a-sub.on')].map(e => e.textContent.trim()),
    groups: document.querySelectorAll('.a-subs').length,
    crumb: ((document.querySelector('.a-crumb') || {}).innerText || '').replace(/\\s+/g, ' ').trim(),
    listed: [...document.querySelectorAll('.a-sub-list .a-link')].map(e => e.textContent.trim()),
  })
`

// ── 登录 ────────────────────────────────────────────────────────────────────
await send('Page.navigate', { url: BASE + '/admin.html' })
for (let i = 0; i < 120; i++) {
  await sleep(500)
  const d = await evalJS(`({ n: ((document.querySelector('#app') || {}).innerHTML || '').length, ids: [...document.querySelectorAll('input')].map(e => e.id) })`)
  if (d.n > 0 && d.ids.includes('acc')) break
}
await evalJS(HELPERS)
const filled = await evalJS(`(() => {
  const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
  const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
  if (!acc || !pwd) return false
  set(acc, 'admin'); set(pwd, '123@admin')
  return window.__click('button.btn.btn-primary.btn-block', '登录')
})()`)

let s = null
for (let i = 0; i < 60; i++) { await sleep(500); s = await evalJS(`window.__state()`); if (s.hash === '#/admin') break }
ok('① 登录 `admin/123@admin` ⇒ 落 #/admin', filled === true && s.hash === '#/admin', { filled, hash: s.hash })

// ② 侧边栏结构（硬编码期望）
ok('② 三个域入口 = 概览 / 业务域 / **可观测域**（无「风控域」）',
  s.doms.join('/') === DOMAINS.map((d) => d.label).join('/'), s.doms)
ok('③ 共 **11** 条子项、分 **2** 组，文案与清单逐字一致',
  s.subsAll.length === 11 && s.groups === 2 && s.subsAll.join('/') === ALL_SUBS.map((x) => x.label).join('/'),
  { n: s.subsAll.length, groups: s.groups, subsAll: s.subsAll })
await shot(OUT + 'shell3-01-sidebar.png').then((f) => console.log('截图 → ' + f)).catch(() => { })

// ④⑤ 逐个子项点开
const bad = []
for (const x of ALL_SUBS) {
  await evalJS(`window.__click('.a-sub', ${JSON.stringify(x.label)})`)
  let st = null
  for (let i = 0; i < 40; i++) { await sleep(150); st = await evalJS(`window.__state()`); if (st.hash === x.to) break }
  const good = st.hash === x.to && st.h1 === x.label
    && st.subOn.length === 1 && st.subOn[0] === x.label           // 子项行亮，且**唯一**
    && st.domOn.length === 1 && st.domOn[0] === x.domain          // ④ **父域不熄灯**
    && st.crumb === `管理端 / ${x.label}`
  if (!good) bad.push({ 子项: x.label, 期望: x.to, got: { hash: st.hash, h1: st.h1, subOn: st.subOn, domOn: st.domOn, crumb: st.crumb } })
}
ok('④ **11 个子项逐个点开**：落点 / 标题 / 面包屑 / 子项高亮唯一 全对', bad.length === 0, bad)
ok('⑤ 进子项后**父域不熄灯**（`.a-dom.on` = 所属域，`.a-sub.on` = 子项，各恰好 1）', bad.length === 0, bad)

// ④′ 两个域入口页把本域子项列成可点链接
const listBad = []
for (const d of DOMAINS.slice(1)) {
  await evalJS(`window.__click('.a-dom', ${JSON.stringify(d.label)})`)
  let st = null
  for (let i = 0; i < 40; i++) { await sleep(150); st = await evalJS(`window.__state()`); if (st.hash === d.to) break }
  const want = d.subs.map(([l]) => l).join('/')
  if (st.listed.join('/') !== want || st.subOn.length !== 0) listBad.push({ 域: d.label, got: { listed: st.listed, subOn: st.subOn } })
}
ok('⑥ 两个**域入口页**都把本域子项列成可点链接，且此时**无子项高亮**', listBad.length === 0, listBad)
await shot(OUT + 'shell3-02-biz-entry.png').then((f) => console.log('截图 → ' + f)).catch(() => { })

// ⑦ 回归：退出链
await evalJS(`window.__click('.a-out', '退出')`)
let lo = null
for (let i = 0; i < 40; i++) { await sleep(250); lo = await evalJS(`({ hash: location.hash, side: !!document.querySelector('.a-side') })`); if (/^#\/login/.test(lo.hash)) break }
await sleep(1200)
const me = await evalJS(`(async () => { try { const r = await fetch(${JSON.stringify(API + '/user/me')}, { method: 'POST', credentials: 'include' }); return r.status } catch (e) { return 'ERR' } })()`)
ok('⑦ 回归：退出 ⇒ 落 #/login + 壳卸载 + 服务端会话真没了（/user/me ⇒ 401）',
  /^#\/login/.test(lo.hash) && !lo.side && me === 401, { hash: lo.hash, side: lo.side, me })

ok('⑧ 无 JS 异常', errs.length === 0, errs.length)
if (errs.length) console.log('── JS 异常 ──\n' + errs.join('\n'))
if (bad.length) console.log('── 子项失败明细 ──\n' + JSON.stringify(bad, null, 1))

for (const [st, l, g] of rows) console.log(`${st} ${l}${g ? '  → ' + g : ''}`)
const n = rows.filter((r) => r[0] === 'FAIL').length
console.log(`\nc3-shell3: ${rows.length} 项 / ${n} 失败（子项断言内部覆盖 ${ALL_SUBS.length} 条记录）`)
try { ws.close() } catch (e) { }
try { proc.kill() } catch (e) { }
await sleep(200)
try { fs.rmSync(udd, { recursive: true, force: true }) } catch (e) { }
process.exit(n ? 1 : 0)
