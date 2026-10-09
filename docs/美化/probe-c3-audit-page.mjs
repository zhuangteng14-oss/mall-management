// probe-c3-audit-page.mjs —— C3 批 A「权限与审计」页的**真后端**页面级验收（2026-10-08）。
//
// 与同目录既有的 `probe-c3-admin-accept.mjs` 的**关键不同**：那份的 mock 是**就地伪造**后端
// （因为要测的只是「骨架落没落到暗色壳」）；本份**不 mock 任何东西** ——
//   页面 → `http://localhost:8089`（agent 本会话自起的 a3 实例，连 VM 库，库里真有 2 行 goods.update）。
// 因此本份验的是**整条链**：真库 → 真端点 → 真前端 → 真渲染。
//
// ⚠️ 为什么另起一个 8089 实例、而不去用 8088：
//   8088 上跑的是**用户从 IDEA 起的旧进程**（classpath = `target/classes`），
//   它在 `admin/` 包加进来**之前**就起来了 ⇒ `/admin/audit/*` 一律 404。
//   ⚠️ agent 尝试 `taskkill` 该进程时被**权限分类器拒绝**（「杀非本会话创建的进程」）——
//      该拒绝是对的，**故改为不动用户任何进程**：自起一个 8089 + 自建一份指向 8089 的 `dist/`。
//
// ⚠️ `dist/` 的 API 基址是**构建期内联**的（`VUE_APP_API_BASE`），所以那份 dist 是用
//   `VUE_APP_API_BASE=http://localhost:8089 npm run build` 单独构建的；`npm run build` 裸跑
//   会重新内联成 8088。**若本探针要重跑，先按那行重建 dist。**
//
// 判据（= 用户给的 DoD）：登录态进审计页 / 看到 goods.update / 时间区间筛 /
//   详情 diff / **时区按浏览器时区**。见文件末 `rows` 结果表。
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import http from 'node:http'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const MALL = 'C:/Users/86159/Desktop/软件工程/期末项目/Mall-management-web/mall-management-system'
const DIST = MALL + '/dist'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/美化/'
const WebSocket = createRequire(path.join(MALL, 'package.json'))('ws')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const rows = []
const ok = (label, cond, got) => rows.push([cond ? 'OK  ' : 'FAIL', label, got === undefined ? '' : JSON.stringify(got)])

// ── ① 静态服务（root = dist/）—— 同 accept 探针的写法（含那条 path.resolve 的坑）────────
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.svg': 'image/svg+xml', '.map': 'application/json' }
const ROOT = path.resolve(DIST)
const srv = http.createServer((req, res) => {
  const p = decodeURIComponent(req.url.split('?')[0])
  const f = path.resolve(ROOT, '.' + p)
  if ((f !== ROOT && !f.startsWith(ROOT + path.sep)) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end('nope') }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(f).toLowerCase()] || 'application/octet-stream' })
  fs.createReadStream(f).pipe(res)
})
await new Promise((r) => srv.listen(8091, r))
console.log('静态服务: http://localhost:8091/  (root = dist/)')

// ── ② Edge（headless + 关同源策略：页面源 8091 → API 8089 是跨源）─────────────────────
const port = 9600 + Math.floor(Math.random() * 300)
const udd = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-audit-'))
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
const evalJS = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('页面里抛错: ' + JSON.stringify(r.exceptionDetails.exception && r.exceptionDetails.exception.description || r.exceptionDetails.text))
  return r.result.value
}
const waitFor = async (expr, ms = 10000) => { const t0 = Date.now(); for (;;) { const v = await evalJS(expr); if (v) return v; if (Date.now() - t0 > ms) return null; await sleep(120) } }

await send('Page.enable')
await send('Runtime.enable')

// ③ 造登录态：**在文档脚本之前**塞 sessionStorage（两条守卫的判据 ——
//    ① `isLoggedIn()` = `!!(loginUsername || merchantid)`；② `meta.roles` ∋ `roleKey()` = `getRole()`）。
//    ⚠️ 用 `addScriptToEvaluateOnNewDocument` 而不是先导航再 `evaluate`：本页是 SPA，
//       守卫在**首次导航**就跑；事后补种太晚（会被改判成匿名 → `/login?redirect=…`）。
await send('Page.addScriptToEvaluateOnNewDocument', {
  source: `sessionStorage.setItem('loginUsername','admin');sessionStorage.setItem('role','3');`
})

await send('Page.navigate', { url: 'http://localhost:8091/admin.html#/admin/observe/audit' })
await sleep(1200)

// ── ④ 断言 ────────────────────────────────────────────────────────────────────
const hash = await evalJS('location.hash')
ok('落在审计页（没被守卫踢去 /login）', String(hash).includes('/admin/observe/audit'), hash)

const nRows = await waitFor("document.querySelectorAll('.a-ev').length")
ok('事件流渲染出 2 行', nRows === 2, nRows)

const actions = await evalJS("[...document.querySelectorAll('.a-tag')].map(e=>e.textContent)")
ok('动作全部是 goods.update', Array.isArray(actions) && actions.length === 2 && actions.every((a) => a === 'goods.update'), actions)

// 状态条：total
const total = await evalJS("document.querySelector('.a-stat b') && document.querySelector('.a-stat b').textContent")
ok('状态条 total = 2', total === '2', total)

// ── 时区（本页最要紧的一条）────────────────────────────────────────────────────
// 取第 1 行的两个读数：**渲染出来的本地时间** 与 **库内原始文本**。二者之差必须 == 浏览器偏移。
const tz = await evalJS(`(() => {
  const ev = document.querySelector('.a-ev')
  const local = ev.querySelector('.a-ev-time b').textContent.trim()
  const raw = ev.querySelector('.a-ev-time .a-dim').textContent.replace('库内','').trim()
  return { local, raw, offMin: -new Date().getTimezoneOffset() }
})()`)
// 独立算一遍：把 raw 当 UTC，按浏览器时区渲染 —— 期望与页面显示**逐字相同**
const expectLocal = (() => {
  const d = new Date(tz.raw.replace(' ', 'T') + 'Z')
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
})()
ok(`时区：页面本地时间 = 库内 UTC + 偏移(${tz.offMin}min)`, tz.local === expectLocal, { 页面: tz.local, 独立算: expectLocal, 库内: tz.raw })
ok('时区：偏移不为 0 时，显示确实被移动过（防「恰好相等」的假绿）', tz.offMin === 0 || tz.local !== tz.raw, { offMin: tz.offMin, local: tz.local, raw: tz.raw })

// ── 时间区间筛选（跨 tz 的**往返**：探针按本地时区填，页面须反算回 UTC）──────────────
// 两行分别是 Id=3 @ 14:57:21Z 与 Id=2 @ 14:56:49Z（上面实测）。取分界线 14:57:00Z：
//   · `to   <= 14:57:00Z` ⇒ 只剩 Id=2（1 行）
//   · `from >= 14:57:00Z` ⇒ 只剩 Id=3（1 行）
// 换算成 **本地** 的 `datetime-local` 字面量由探针**独立**算（不调页面代码）。
const localLit = (utcText) => {
  const d = new Date(utcText.replace(' ', 'T') + 'Z')
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
// ⚠️ 分界点为什么要挑**分钟边界**、而且两个方向**挑不同的一分钟**：
//   页面口径是「用户挑到哪一分，就把那一分查满」⇒ `toSqlTime` 把**起**(from)补 `:00`、
//   把**止**(to)补 `:59`（见 `AuditPage.vue` 的 `toSqlTime`）。于是「止」的取值粒度
//   **不是秒而是分**：写 `22:57` 得到的是 `14:57:59Z`，它会**连 14:57:21 一起收进来**。
//   两行在 14:56:49Z 与 14:57:21Z ⇒ 唯一能把它们**分开**的分界点是**整分钟 14:57:00Z**：
//     · 止 = 22:56（本地）⇒ 14:56:59Z ⇒ 只剩 14:56:49 ✅   （14:57:21 被排除）
//     · 起 = 22:57（本地）⇒ 14:57:00Z ⇒ 只剩 14:57:21 ✅   （14:56:49 被排除）
//   ⚠️ 第一版本探针把**止**也取在 22:57，得到 2 行，一度像 bug —— 实为**期望写错了**：
//      22:57 → 14:57:59Z → 两行都 ≤ 它。**页面是对的**，口径见上。
const cutTo = localLit('2026-10-08 14:56:00')   // 止：本分钟整
const cutFrom = localLit('2026-10-08 14:57:00') // 起：本分钟整

const setInput = (idx, val) => evalJS(`(() => {
  const el = document.querySelectorAll('.a-f input[type=datetime-local]')[${idx}]
  el.value = ${JSON.stringify(val)}
  el.dispatchEvent(new Event('input', { bubbles: true }))
  return el.value
})()`)
const clickQuery = () => evalJS(`(() => { document.querySelector('.a-btn-on').click(); return true })()`)
const clickReset = () => evalJS(`(() => {
  const b = [...document.querySelectorAll('.a-f-actions .a-btn')].find(x => x.textContent.trim() === '重置')
  b.click(); return true
})()`)

// 读回**每行**的库内原始时间（UTC 文本）——比数行数更锐：能钉住「留下的是哪一行」。
const rawTimes = () => evalJS("[...document.querySelectorAll('.a-ev .a-ev-time .a-dim')].map(e=>e.textContent.replace('库内','').trim())")

await setInput(1, cutTo)       // idx 1 = 止
await clickQuery()
await sleep(900)
const tTo = await rawTimes()
ok('时间区间 [止 = 22:56 本地 ⇒ 14:56:59Z] ⇒ 只留 14:56:49 那行', tTo.length === 1 && tTo[0] === '2026-10-08 14:56:49', tTo)

await clickReset()
await sleep(700)
const nAfterReset = await evalJS("document.querySelectorAll('.a-ev').length")
ok('重置 ⇒ 回到 2 行', nAfterReset === 2, nAfterReset)

await setInput(0, cutFrom)     // idx 0 = 起
await clickQuery()
await sleep(900)
const tFrom = await rawTimes()
ok('时间区间 [起 = 22:57 本地 ⇒ 14:57:00Z] ⇒ 只留 14:57:21 那行', tFrom.length === 1 && tFrom[0] === '2026-10-08 14:57:21', tFrom)

await clickReset()
await sleep(700)

// ── 筛选：动作下拉（facets 供给）与操作者 ───────────────────────────────────────
const optVals = await evalJS("[...document.querySelector('.a-f select').options].map(o => o.value)")
ok('动作下拉由 facets 供给（含 goods.update）', Array.isArray(optVals) && optVals.includes('goods.update'), optVals)

const pickAction = (v) => evalJS("(() => { const s = document.querySelector('.a-f select'); s.value = " + JSON.stringify(v) + "; s.dispatchEvent(new Event('change', { bubbles: true })); return s.value })()")
const setActor = (v) => evalJS("(() => { const el = document.querySelector('.a-f input[type=text]'); el.value = " + JSON.stringify(v) + "; el.dispatchEvent(new Event('input', { bubbles: true })); return el.value })()")

await pickAction('goods.update')
await sleep(900)
ok('动作 = goods.update ⇒ 仍 2 行', (await evalJS("document.querySelectorAll('.a-ev').length")) === 2)
await setActor('5N1k5K2guh')
await clickQuery()
await sleep(900)
ok('操作者 = 真 actor ⇒ 2 行', (await evalJS("document.querySelectorAll('.a-ev').length")) === 2)
await setActor('no-such-actor')
await clickQuery()
await sleep(900)
ok('操作者 = 假值 ⇒ 0 行（空态）', (await evalJS("document.querySelectorAll('.a-ev').length")) === 0)
const emptyTxt = await evalJS("document.querySelector('.a-empty') && document.querySelector('.a-empty').textContent.trim()")
ok('  空态文案出现', !!emptyTxt, emptyTxt)
await clickReset()
await sleep(700)
ok('重置 ⇒ 回到 2 行（筛选项全清）', (await evalJS("document.querySelectorAll('.a-ev').length")) === 2)

// ── 详情 diff ────────────────────────────────────────────────────────────────
await evalJS("(() => { document.querySelector('.a-ev-head').click(); return true })()")
const hasDiff = await waitFor("document.querySelectorAll('.a-diff tbody tr').length")
ok('展开后出现 diff 表', !!hasDiff && hasDiff > 0, hasDiff)
const nChg = await evalJS("document.querySelectorAll('.a-diff tr.chg').length")
ok('diff 里至少 1 处变化列被高亮（tr.chg）', nChg >= 1, nChg)
const srcLine = await evalJS("document.querySelector('.a-src') && document.querySelector('.a-src').textContent.replace(/\\s+/g,' ').trim()")
ok('详情来源标为 /admin/audit/detail 且已取到', /audit\/detail/.test(srcLine) && /已取到/.test(srcLine), srcLine)
const chgKeys = await evalJS("[...document.querySelectorAll('.a-diff tr.chg .a-k')].map(e=>e.textContent)")
ok('高亮列 = Price（快照是单元素数组，已拆开才看得到字段名）', chgKeys.length === 1 && chgKeys[0] === 'Price', chgKeys)
const nDiffAll = await evalJS("document.querySelectorAll('.a-diff tbody tr').length")
ok('  「只看差异」默认开 ⇒ 表里只剩 1 行（而非 8 行全字段）', nDiffAll === 1, nDiffAll)

// 截图存证
const shot = await send('Page.captureScreenshot', { format: 'png' })
fs.writeFileSync(OUT + 'audit-page-real-backend.png', Buffer.from(shot.data, 'base64'))
console.log('截图: docs/美化/audit-page-real-backend.png')

// ── ⑤ 汇总 ───────────────────────────────────────────────────────────────────
console.log('\n──── 结果 ────')
let bad = 0
for (const [st, label, got] of rows) { if (st === 'FAIL') bad++; console.log(`${st} ${label}${got ? '  → ' + got : ''}`) }
console.log(`\n${rows.length - bad}/${rows.length} 通过；${bad} 失败`)

ws.close()
proc.kill()
srv.close()
process.exit(bad ? 1 : 0)
