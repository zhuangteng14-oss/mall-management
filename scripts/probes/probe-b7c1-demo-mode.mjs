// probe-b7c1-demo-mode.mjs —— 块 7 收尾 ②〈模拟日常〉的**页面级**实测（三页各一遍）。
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-b7c1-demo-mode.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
//
// ⚠️ **本探针不 mock 出假帧**（与 `probe-b7c7-order-flow.mjs` 那条路正相反）：假数据是
//    **前端自己造的**，探针要验的正是「它到底走没走后端」⇒ 后端请求**一个都不放行**，
//    全被 `Fetch` 拦截并**记下来**。于是：
//      · 「演示模式下零请求」不是推断，是**数出来的**（拦截表长度不变）；
//      · 反向对照（⑥）把开关拨回「正常」后**必须**重新出现请求 —— 否则「零请求」这个结论
//        也可能只是「探针压根没拦到东西」。
//    ⚠️ 拦截器只挂 `*8088*`（`_cdp.mjs` 的既有口径）：前端自身的静态资源走 8080，不受影响。
//
// 三页各查六件事（同构，故合并成一张表）：
//   ① 页面挂载、无 console.error / 无未捕获异常
//   ② 页头右上角有那枚开关，且是「正常 / 模拟日常」两枚按钮
//   ③ 点「模拟日常」后**这一页再没有对 8088 的请求**（纯前端、不入库的判据）
//   ④ 帧真的在走：隔 2 秒两次读「已放 N / 500 条」，N 在涨
//   ⑤ 该页看得见的假数据（逐页不同的那一格）
//   ⑥ 拨回「正常」⇒ 请求重新出现（反向对照）
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

// ⚠️ 管理端是**另一个入口**（`public/admin.html`，见 `vue.config.js` 的 pages 配置）——
//    8080 的根是商城，本探针要的是 `/admin.html#/admin/observe/...`。
const BASE = 'http://localhost:8080/admin.html'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'

const R = report('块 7 收尾 ② 模拟日常（三页 · 真 dev server + 拦截计数）')

/** 页头那枚开关：找「模拟日常」那枚按钮并点它（`.a-dm` 是组件的根类）。 */
const CLICK = (txt) => `(() => {
  const bs = [...document.querySelectorAll('.a-dm .a-seg-b')]
  const t = bs.find((x) => x.textContent.trim() === ${JSON.stringify(txt)})
  if (!t) return 'no-btn'
  t.click(); return 'ok'
})()`

/** 读开关下面那行字（「已放 N / 500 条」/「后端真流」）。 */
const NOTE = `(() => { const e = document.querySelector('.a-dm-n'); return e ? e.textContent.replace(/\\s+/g, ' ').trim() : '' })()`

/** 拦截表：每一条被拦下的 8088 请求都记在这里（「零请求」那个结论就是数它）。 */
const calls = []
const consoleErrs = []
const b = await open({ mock: (url, method) => {
  calls.push(`${method} ${url}`)
  return { status: 200, body: '{}' }
} })
b.ws.on('message', (m) => {
  if (m.method === 'Runtime.exceptionThrown') {
    consoleErrs.push(String(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text))
  }
  if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
    consoleErrs.push((m.params.args || []).map((a) => a.value ?? a.description ?? '').join(' '))
  }
})

// ── 会话注入（必须在应用脚本之前；`role` 必须是字符串 "3"）────────────────────
// 照 `probe-b7c7-order-flow.mjs` 那一份：管理端不走登录页，`sessionStorage` 里这几个键就是门。
await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => {
    try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {}
  })()`,
})

const PAGES = [
  { key: 'metrics', route: '#/admin/observe/metrics', shot: 'out-b7c1-demo-metrics.png' },
  { key: 'timeline', route: '#/admin/observe/timeline', shot: 'out-b7c1-demo-timeline.png' },
  { key: 'logs', route: '#/admin/observe/logs', shot: 'out-b7c1-demo-logs.png' },
]

for (const p of PAGES) {
  console.log(`\n──── ${p.key} ────`)
  calls.length = 0
  consoleErrs.length = 0
  await b.goto(`${BASE}/${p.route}`, 2500)
  const before = calls.length

  R.ok(`${p.key} ① 页面挂载后确实打过后端（说明这页本来是要连的）`, before > 0, before)

  const btns = await b.evalJS(`[...document.querySelectorAll('.a-dm .a-seg-b')].map((x) => x.textContent.trim())`)
  R.ok(`${p.key} ② 开关在（正常 / 模拟日常）`, JSON.stringify(btns) === '["正常","模拟日常"]', btns)

  const click = await b.evalJS(CLICK('模拟日常'))
  R.ok(`${p.key} ② 点得到「模拟日常」`, click === 'ok', click)
  await sleep(600) // 让重连发生（`watch(demo.on)` → `connect()`）

  // ── ③ 纯前端：拨到演示后**不再有任何 8088 请求** ─────────────────────
  const mark = calls.length
  const n1 = await b.evalJS(NOTE)
  await sleep(2200)
  const n2 = await b.evalJS(NOTE)
  R.ok(`${p.key} ③ 演示模式下**零**后端请求`, calls.length === mark,
    calls.slice(mark))
  R.ok(`${p.key} ④ 帧在走（已放数在涨）`, n1 !== n2 && /已放/.test(n2), [n1, n2])

  // ── ⑤ 逐页那一格看得见的假数据 ───────────────────────────────────────
  if (p.key === 'metrics') {
    const kpi = await b.evalJS(`[...document.querySelectorAll('.a-kpis .kpi-n')].map((x) => x.textContent.trim())`)
    const why = await b.evalJS(`[...document.querySelectorAll('.a-reasons li')].map((x) => x.textContent.trim()).join('|')`)
    R.ok(`${p.key} ⑤ KPI 有数且不为「—」`, kpi.length > 0 && kpi.every((v) => v !== '—' && v !== ''), kpi)
    R.ok(`${p.key} ⑤ 健康度判据自报「模拟日常」`, /模拟日常/.test(why), why)
  }
  if (p.key === 'timeline') {
    const nums = await b.evalJS(`[...document.querySelectorAll('.a-of-kpis .kpi-n')].map((x) => x.textContent.trim())`)
    const batchRow = await b.evalJS(`document.body.innerText.includes('本批次单数')`)
    R.ok(`${p.key} ⑤ 五态计数卡有数`, nums.length >= 5 && nums.every((v) => v !== ''), nums)
    R.ok(`${p.key} ⑤ 演示无批次 ⇒「本批次单数」不出现`, batchRow === false, batchRow)
  }
  if (p.key === 'logs') {
    // ⚠️ 行类名是 `.a-el-row`（不是 `.a-ev` —— 那是攻击页回放面板的类，两处别混）。
    const rows = await b.evalJS(`document.querySelectorAll('.a-el-row').length`)
    const mq = await b.evalJS(`(() => { const e = document.querySelector('.a-el-mq'); return e ? e.textContent.trim() : '' })()`)
    R.ok(`${p.key} ⑤ 事件行渲染出来了`, rows > 0, rows)
    R.ok(`${p.key} ⑤ MQ 条照实显示「未连接」（演示事件确实不落库）`, /未连接/.test(mq), mq)
  }

  await b.shot(EV + p.shot)

  // ── ⑥ 反向对照：拨回「正常」⇒ 请求必须重新出现 ───────────────────────
  const back = await b.evalJS(CLICK('正常'))
  await sleep(1500)
  R.ok(`${p.key} ⑥ 拨回「正常」后请求重新出现`, back === 'ok' && calls.length > mark,
    calls.length - mark)

  R.ok(`${p.key} ① 全程无 console.error`, consoleErrs.length === 0, consoleErrs.slice(0, 3))
}

await b.close()
process.exit(R.dump() === 0 ? 0 : 1)
