// I2 系统通知 —— 三端真实 E2E（真 dev server 8080 → 真后端 8099(a3) → VM 库）
// 用户端用真实账号 i2probe/i2probe（VM 库里已存在）；管理端用 admin/123@admin（同 probe-c3-* 惯例）。
// 依赖：8080 dev server（.env.local 指向 8099）+ 8099 a3 实例（闸关 —— 管理员在闸开时 401，TD-18）。
// ⚠️ 2026-10-09 由散落的 `_a3work/` 移入本目录（用户口径：可复跑的探针进 `scripts/probes/`、
//    证据进 `docs/agent/evidence/i2/`、临时产物写 `_work/`）。移动时改了两处路径：
//    下面的 `_cdp.mjs` 相对引用（本文件在 `scripts/probes/` ⇒ 退两级回项目根再进 `docs/`）
//    与 `OUT` 截图目录（指向证据目录）。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/i2/shots/'
fs.mkdirSync(OUT, { recursive: true })
const R = report('I2 三端 E2E')
const TITLE = 'E2E·三端冒烟'

const b1 = await open({ w: 1440, h: 900 })
const js = (e) => b1.evalJS(e)
const fill = (sel, v, ev = 'input') => b1.evalJS(
  `(()=>{const el=document.querySelector(${JSON.stringify(sel)});if(!el)return 'no-el';` +
  `const P=el.tagName==='TEXTAREA'?HTMLTextAreaElement:HTMLInputElement;` +
  `Object.getOwnPropertyDescriptor(P.prototype,'value').set.call(el,${JSON.stringify(v)});` +
  `el.dispatchEvent(new Event(${JSON.stringify(ev)},{bubbles:true}));return 'ok'})()`)
const clickText = (sel, txt) => b1.evalJS(
  `(()=>{const e=[...document.querySelectorAll(${JSON.stringify(sel)})].find(x=>x.textContent.includes(${JSON.stringify(txt)}));` +
  `if(!e)return 'no-btn';e.click();return 'ok'})()`)

// ── ① 管理端登录 ──────────────────────────────────────────────────────────────
await b1.goto(BASE + '/admin.html', 3000)
let d = null
for (let i = 0; i < 40; i++) {
  d = await js(`({hash:location.hash,inputs:[...document.querySelectorAll('input')].map(e=>e.id)})`)
  if (d.inputs.includes('acc')) break
  await sleep(500)
}
R.ok('① 管理端登录页出现 #acc/#pwd', d.inputs.includes('acc') && d.inputs.includes('pwd'), d.inputs)
await fill('#acc', 'admin'); await fill('#pwd', '123@admin')
await clickText('button.btn.btn-primary.btn-block', '登录')
let h = ''
for (let i = 0; i < 40; i++) { await sleep(500); h = await js(`location.hash`); if (/^#\/admin/.test(h)) break }
R.ok('② 管理员登录后落 #/admin', /^#\/admin/.test(h), h)

// ── ② 进「系统通知」页并发送 ──────────────────────────────────────────────────
await js(`location.hash='#/admin/business/notify'`)
await sleep(2600)
const pg = await js(`({root:!!document.querySelector('.n-page'),` +
  `h2:[...document.querySelectorAll('.n-h2')].map(e=>e.textContent.trim()),` +
  `stats:[...document.querySelectorAll('.n-stat,.n-tz')].map(e=>e.textContent.replace(/\\s+/g,' ').trim())})`)
R.ok('③ 系统通知页渲染（.n-page）', pg.root, pg.stats)
await b1.shot(OUT + '01-admin-notify.png')

await js(`(()=>{const r=[...document.querySelectorAll('input[type=radio]')].find(x=>x.value==='one');if(!r)return 0;r.click();return r.checked})()`)
await sleep(400)
await fill('input[placeholder^="业务号"]', 'i2probe')
await js(`(()=>{const s=[...document.querySelectorAll('select')].find(x=>[...x.options].some(o=>o.value==='customer'));` +
  `if(!s)return 0;s.value='customer';s.dispatchEvent(new Event('change',{bubbles:true}));return s.value})()`)
await fill('input[placeholder^="通知标题"]', TITLE)
await fill('textarea[placeholder^="通知正文"]', '由管理端在浏览器里发出，验证客户端 5s 内出红点。')
await sleep(300)
await b1.shot(OUT + '02-admin-filled.png')
await clickText('.n-card button.n-btn', '发送')
let sent = null
for (let i = 0; i < 30; i++) {
  sent = await js(`(()=>{const e=document.querySelector('.n-sent');return e?e.textContent.replace(/\\s+/g,' ').trim():null})()`)
  if (sent) break
  await sleep(500)
}
R.ok('④ 发送回执「已投递 1 人」', !!sent && /已投递\s*1\s*人/.test(sent), sent)
const batch = (sent || '').match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)
R.ok('⑤ 回执带 batchId', !!batch, batch && batch[0])
await b1.shot(OUT + '03-admin-sent.png')

// 历史里应能看到这批（按批归组、RecipientCount=1）
await sleep(1200)
const hist = await js(`[...document.querySelectorAll('.n-stream .n-ev')].slice(0,3).map(e=>({` +
  `t:(e.querySelector('.n-ev-title')||{}).textContent,` +
  `b:((e.querySelector('.n-ev-l3 code')||{}).textContent||''),` +
  `n:((e.querySelector('.n-ev-nums')||{}).textContent||'').replace(/\\s+/g,' ').trim()}))`)
R.ok('⑥ 投递历史出现该批次（含 batchId/人数）',
  hist.length > 0 && hist[0].t === TITLE && hist[0].b === (batch && batch[0]) && /1 人/.test(hist[0].n), hist)
await b1.shot(OUT + '04-admin-history.png')

// ── ③ 客户端：红点 → 收件箱 → 点开 ────────────────────────────────────────────
const b2 = await open({ w: 1440, h: 900 })
const js2 = (e) => b2.evalJS(e)
const fill2 = (sel, v) => b2.evalJS(
  `(()=>{const el=document.querySelector(${JSON.stringify(sel)});if(!el)return 'no-el';` +
  `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,${JSON.stringify(v)});` +
  `el.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()`)

await b2.goto(BASE + '/#/login', 3000)
let d2 = null
for (let i = 0; i < 40; i++) {
  d2 = await js2(`({hash:location.hash,inputs:[...document.querySelectorAll('input')].map(e=>e.id)})`)
  if (d2.inputs.includes('acc')) break
  await sleep(500)
}
R.ok('⑦ 客户端登录页出现 #acc/#pwd', d2.inputs.includes('acc'), d2.inputs)
await fill2('#acc', 'i2probe'); await fill2('#pwd', 'i2probe')
await b2.evalJS(`(()=>{const e=document.querySelector('button.btn.btn-primary.btn-block');if(!e)return 0;e.click();return 1})()`)
let h2 = ''
for (let i = 0; i < 40; i++) { await sleep(500); h2 = await js2(`location.hash`); if (/^#\/(customer|merchant)/.test(h2)) break }
R.ok('⑧ 客户登录后落客户壳', /^#\/(customer|merchant)/.test(h2), h2)

const dot = () => js2(`(()=>{const a=[...document.querySelectorAll('.nav-right .nav-btn')].map(e=>e.textContent.trim());` +
  `const n=[...document.querySelectorAll('.nav-right .nav-btn')].find(e=>e.textContent.includes('通知'));` +
  `return {tabs:a, notifyHasDot: !!(n&&n.querySelector('.nav-red-dot'))}})()`)
let dt = null
for (let i = 0; i < 16; i++) { dt = await dot(); if (dt.notifyHasDot) break; await sleep(1000) }
R.ok('⑨ 顶栏出现「通知」项', (dt.tabs || []).some((t) => t.includes('通知')), dt.tabs)
R.ok('⑩ 5s 内「通知」出红点（未读数轮询）', dt.notifyHasDot, dt)
await b2.shot(OUT + '05-customer-dot.png', true)

// 点「通知」进收件箱
await b2.evalJS(`(()=>{const n=[...document.querySelectorAll('.nav-right .nav-btn')].find(e=>e.textContent.includes('通知'));if(!n)return 0;n.click();return 1})()`)
await sleep(2500)
const inbox = () => js2(`({hash:location.hash, root:!!document.querySelector('.notify-room'),` +
  `head:(document.querySelector('.nhead .sub')||{}).textContent,` +
  `first:(document.querySelector('.nlist .nrow .title')||{}).textContent,` +
  `unreadRows:document.querySelectorAll('.nlist .nrow.unread').length,` +
  `dot:!!document.querySelector('.nav-right .nav-red-dot')})`)
let ib = null
for (let i = 0; i < 20; i++) { ib = await inbox(); if (ib.root && ib.first) break; await sleep(500) }
R.ok('⑪ 落到 #/customer/notify 且收到该条', /#\/customer\/notify$/.test(ib.hash) && ib.first === TITLE, ib)
R.ok('⑫ 进通知页后顶栏红点清零', ib.dot === false, ib.dot)
await b2.shot(OUT + '06-customer-inbox.png')

const before = await inbox()
await js2(`(()=>{const r=document.querySelector('.nlist .nrow');if(!r)return 0;r.click();return 1})()`)
await sleep(2000)
const after = await inbox()
R.ok('⑬ 点单条 ⇒ 未读行数 -1（写 ReadAt）', after.unreadRows === before.unreadRows - 1, { before: before.unreadRows, after: after.unreadRows })
await b2.shot(OUT + '07-customer-read.png')

const bad = R.dump()
fs.writeFileSync(OUT + 'result.json', JSON.stringify({ title: TITLE, batchId: batch && batch[0], sent, ok: bad === 0 }, null, 1))
await b1.close(); await b2.close()
process.exit(bad === 0 ? 0 : 1)
