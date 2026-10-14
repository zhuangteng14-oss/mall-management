// probe-b7c7-order-flow-live.mjs —— 块 7 · commit 7〈状态流转页〉**对着真后端**的端到端实测。
//
// 与 `probe-b7c7-order-flow.mjs`（mock）的分工：那个验「帧怎么渲染」，这个验「真流能不能跑」。
// 两件事必须分开验，因为 mock 里 SSE 是一次性给完整 body（`Fetch.fulfillRequest` 不支持分块推送）
// ⇒ 「逐拍推进」这件事**只有真后端验得了**。
//
// 跑法：① 后端 dev profile 在 8088（`app.auth.enabled=false`）② `npm run serve`（8080）
//       ③ `node scripts/probes/probe-b7c7-order-flow-live.mjs`
//
// ⚠️ **不拦截 Fetch**：本探针的全部价值就在于打真后端。任何 mock 都会让它变成上面那个探针。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/observe/timeline'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'
const SHOT = `${EV}out-b7c7-order-flow-live.png`

const R = report('块 7 · commit 7 状态流转页（真后端 8088）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const b = await open({ w: 1440, h: 1000 })

await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => {
    try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {}
    window.__errs = []
    const oe = console.error.bind(console)
    console.error = (...a) => { try { window.__errs.push(a.map(String).join(' ')) } catch (e) {} ; oe(...a) }
    window.addEventListener('unhandledrejection', (e) => { try { window.__errs.push('unhandledrejection: ' + e.reason) } catch (x) {} })
  })()`,
})

const TEXT = (sel) => `[...document.querySelectorAll('${sel}')].map(e => (e.innerText ?? e.textContent ?? '').trim())`

try {
  await b.send('Page.navigate', { url: `${BASE}/${ROUTE}` })
  let mounted = false
  for (let i = 0; i < 120; i++) {
    mounted = await b.evalJS(`!!document.querySelector('.a-of')`).catch(() => false)
    if (mounted) break
    await sleep(250)
  }
  ok('① 页面挂载', mounted === true)

  // ⚠️ 等**真流**推两拍以上：一拍 1 秒（`mall.observe.sse.interval-ms`），留 6 秒余量
  //    （首拍要等后端跑完两条只读查询；库刚起来时第一条可能慢）。
  await sleep(6000)

  const phase = await b.evalJS(`(document.querySelector('.a-chip-live') || {}).innerText || ''`)
  ok('① 流是**活的**（不是「已断开」）—— 真流会一直推，与 mock 那条一次性 body 不同',
    String(phase).trim() === '已连接', phase)

  const seq = await b.evalJS(`(() => { const m = document.body.innerText.match(/第\\s*(\\d+)\\s*拍/); return m ? Number(m[1]) : 0 })()`)
  ok('① 已推进 ≥ 2 拍（证明是逐拍推、不是一帧到底）', Number(seq) >= 2, seq)

  const errs = await b.evalJS('(window.__errs || [])')
  ok('① 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))

  // ── 真库的读数：五态之和必须等于「订单总数」这一格 ──────────────────────────
  const nums = await b.evalJS(`[...document.querySelectorAll('.a-of-kpis .kpi-n')].map(e => e.innerText.trim())`)
  const totalTxt = await b.evalJS(`(document.querySelector('.a-stat b') || {}).innerText || ''`)
  const sum = nums.reduce((a, v) => a + Number(String(v).replace(/,/g, '')), 0)
  ok('② 五态计数卡都在，且**五数之和 = 订单总数**（后端一条 SQL 出的同一口径）',
    nums.length === 5 && sum === Number(String(totalTxt).replace(/,/g, '')), `${JSON.stringify(nums)} sum=${sum} total=${totalTxt}`)

  const center = await b.evalJS(`(document.querySelector('.a-of-donut .dc-v') || {}).innerText || ''`)
  ok('② 环图中心 = 同一个总数', String(center).trim() === String(totalTxt).trim(), `${center} vs ${totalTxt}`)

  // ── 真库的订单列表 ────────────────────────────────────────────────────────
  const rowCount = await b.evalJS(`document.querySelectorAll('.a-of-row').length`)
  const ids = await b.evalJS(TEXT('.a-of-row .a-of-id'))
  const steps = await b.evalJS(`[...document.querySelectorAll('.a-of-row')].map(r => r.querySelectorAll('.a-of-node.on').length)`)
  ok('③ 列表画出了真单（条数 = 后端 `RECENT_LIMIT` 与库内单数的较小者）',
    rowCount > 0 && ids.every((s) => s.length > 0), `${rowCount} 行：${JSON.stringify(ids.slice(0, 3))}…`)
  ok('③ 每行的点亮节点数落在 0–4（`step` 是后端算的，不是前端推的）',
    steps.every((n) => n >= 0 && n <= 4), steps.slice(0, 8))
  const states = await b.evalJS(TEXT('.a-of-row .a-of-st'))
  const KNOWN = ['待付款', '待发货', '待收货', '已完成', '已取消']
  ok('③ 每行状态都是五态之一（没有英文 code 上屏）', states.every((s) => KNOWN.includes(s)), [...new Set(states)])

  // ── 状态机 + 分页条 ───────────────────────────────────────────────────────
  const boxes = await b.evalJS(TEXT('.a-of-box-t'))
  ok('④ 状态机 5 个节点盒', boxes.length === 5, boxes)
  const pager = await b.evalJS(`(document.querySelector('.pager-sum') || {}).innerText || ''`)
  ok('④ 分页条在，且计数 = 列表行数',
    String(pager).includes('实时订单') && String(pager).replace(/\s+/g, '').includes(`共${rowCount}单`),
    pager.replace(/\s+/g, ' '))

  await b.shot(SHOT)

  // ── 点第一行 → 时间线（真流下弹层里可能有 0 条流转，那是**正确**的：本页只攒本会话）──
  if (rowCount > 0) {
    await b.evalJS(`(() => { document.querySelectorAll('.a-of-row')[0].click(); return true })()`)
    await sleep(300)
    const modal = await b.evalJS(`!!document.querySelector('.a-of-modal')`)
    const tl = await b.evalJS(TEXT('.a-of-tl-i'))
    ok('⑤ 点第一行弹层打开，且**至少有「创建」那一项**', modal === true && tl.length >= 1, tl)
    await b.evalJS(`(() => { document.querySelector('.a-of-close').click(); return true })()`)
    await sleep(200)
    ok('⑤ 弹层可关闭', (await b.evalJS(`!!document.querySelector('.a-of-modal')`)) === false)
  } else {
    ok('⑤ 库内无单 ⇒ 跳过弹层（页面显示空态而不是报错）', true, '（无单）')
  }
} catch (e) {
  console.log('探针中途异常：', (e && e.message) || e)
} finally {
  const errs2 = await b.evalJS('(window.__errs || [])').catch(() => [])
  ok('全流程收尾：仍无 console.error / unhandledrejection', (errs2 || []).length === 0, (errs2 || []).slice(0, 5))
  const bad = R.dump()
  console.log(`\n截图：${SHOT}`)
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
