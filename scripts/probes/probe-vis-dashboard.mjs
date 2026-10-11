// probe-vis-dashboard.mjs —— 可视化段 commit 2（经营数据看板）的**渲染实测**。
//
// 与 `probe-vis-charts.mjs`（commit 1，打的是**组件展示页** `#/__probe-charts`）的分工：
//   那份查「图表组件本身画不画得出」，这份查「**真页面 + 真后端**接不接得上」——
//   组件对了但页面把 JSON 的键名读错（`Gmv` 写成 `gmv`）照样是一片空白，而那种错
//   在单元测试与编译里都看不见。
//
// ⚠️ **前提两条**（缺一条本脚本必红，且是环境问题不是代码问题）：
//   ① 后端 `./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.auth.enabled=false`
//      —— 默认 profile 的闸是 `true`，`/admin/stats` 与 `/merchant/stats` **不在白名单**
//      ⇒ 不开这个开关，页面两发 POST 都是 401（`WebAuthConfig.AUTH_WHITELIST` 只有 12 条）。
//   ② 前端 `npm run serve`（**8080**）—— 8080 同时必须在后端的 CORS 白名单里（`http://localhost:8080`）。
//
// 用法：node scripts/probes/probe-vis-dashboard.mjs
// 证据落 `docs/agent/evidence/vis-dashboard/page-*.png`。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const ORIGIN = 'http://localhost:8080'

/** 身份由 `sessionStorage` 直接种。
 *  ⚠️ 键名**逐字**取自 `api/auth.js` 的 `K`，**不是**直觉名：`loginUsername` / `merchantid` /
 *  `customerid`（后两个是**全小写 d**）/ `role`。写成 `username` / `merchantId` 会**静默无效**
 *  —— `isLoggedIn()` 读到空 ⇒ 守卫把人送去 `/login?redirect=…`，而页面本身毫无异常。 */
const seed = (role, merchantId, customerId) => `(() => {
  sessionStorage.setItem('loginUsername', 'PROBE');
  sessionStorage.setItem('role', '${role}');
  sessionStorage.setItem('merchantid', '${merchantId}');
  sessionStorage.setItem('customerid', '${customerId}');
  return ['loginUsername=' + sessionStorage.getItem('loginUsername'),
          'role=' + sessionStorage.getItem('role'),
          'merchantid=' + sessionStorage.getItem('merchantid')].join(' ');
})()`

/**
 * 页面快照。⚠️ 判据分三层，缺一层就会出现「有 canvas 标签所以算过」的假绿：
 *   ① canvas **数量**（结构在不在）；
 *   ② canvas 上的**不透明像素数**（真画了没有 —— 空画布也有标签）；
 *   ③ KPI 文本 / 表格行（数字有没有真的解出来）。
 */
const SNAP = `(() => {
  const out = {}
  out.hash = location.hash
  out.h1 = (document.querySelector('.a-st, .ms-page') ? document.body.innerText.split('\\n').slice(0, 3).join(' | ') : '(未挂载)')

  const canvases = [...document.querySelectorAll('.bc-canvas canvas')]
  out.canvasCount = canvases.length
  out.canvasSizes = canvases.map(c => { const r = c.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height)] })
  out.inkPixels = canvases.map(c => {
    try {
      const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data
      let n = 0
      for (let i = 3; i < d.length; i += 4) if (d[i] > 24) n++
      return n
    } catch (e) { return -1 }
  })

  out.kpi = [...document.querySelectorAll('.kpi')].map(k => ({
    label: (k.querySelector('.kpi-k span') || {}).innerText || '',
    value: (k.querySelector('.kpi-n') || {}).innerText || '',
    unit: (k.querySelector('.kpi-u') || {}).innerText || '',
    hint: (k.querySelector('.kpi-d') || {}).innerText || ''
  }))
  out.kpiSkeleton = document.querySelectorAll('.kpi .kpi-skel').length

  out.tbodyRows = document.querySelectorAll('.a-tbl tbody tr, .ms-tbl tbody tr').length
  out.tfootCells = document.querySelectorAll('.a-tbl tfoot td, .ms-tbl tfoot td').length
  out.emptyOverlays = document.querySelectorAll('.bc-state--empty').length
  out.errorOverlays = document.querySelectorAll('.bc-state--error').length
  out.loadingOverlays = document.querySelectorAll('.bc-state--loading').length
  out.errorText = [...document.querySelectorAll('.bc-state--error .bc-msg')].map(e => e.innerText)
  out.flash = (document.querySelector('.a-flash, .ms-flash') || {}).innerText || ''
  out.gate = document.querySelectorAll('.a-gate, .ms-gate').length
  return JSON.stringify(out)
})()`

const r = report('vis-dashboard')

async function run({ label, role, merchantId, customerId, route, file }) {
  const b = await open({ w: 1440, h: 1200, headed: false })
  const errs = []
  const dialogs = []
  b.ws.on('message', (raw) => {
    let m
    try { m = JSON.parse(raw) } catch (e) { return }
    if (m.method === 'Runtime.exceptionThrown') {
      const d = m.params.exceptionDetails
      errs.push((d.text || '') + ' ' + ((d.exception && d.exception.description) || ''))
    } else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      errs.push(m.params.args.map((a) => a.value || a.description || '').join(' '))
    } else if (m.method === 'Page.javascriptDialogOpening') {
      // ⚠️ **必须在这里就地放行**：`api/http.js:228` 的兜底是 `alert(...)`，而 `alert` 会**阻塞渲染进程的
      //    JS 线程** —— 一旦弹出来，后面**每一条** CDP 命令（含 `Runtime.evaluate` / `Page.captureScreenshot`）
      //    都会静默不返回，本脚本看起来就像「页面卡死」（实测 2026-10-11：`/notification/unreadCount`
      //    因本地库缺 `notification` 表回 500 ⇒ 壳的轮询 catch 里弹 alert ⇒ 探针挂在第一个 evalJS 上，
      //    且 `Edge` 各进程 CPU ≈ 0、`Debugger.pause` 也抓不到栈 —— 因为没有循环，是**被模态框挡住**）。
      //    ⇒ 自动确认掉，并把对话框文案**记成证据**：页面上一旦出现 alert，本身就是一条 FAIL。
      dialogs.push({ type: m.params.type, message: m.params.message })
      b.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
    }
  })

  // ① 先在**同源**上把身份种进 sessionStorage（`Page.navigate` 只改 hash = 同文档导航，
  //    sessionStorage 按标签页存活 ⇒ 不用整页重载，守卫读到的就是刚种下的值）。
  await b.goto(ORIGIN + '/', 2500)
  await b.evalJS(seed(role, merchantId, customerId))
  // ② 再切到目标路由。
  await b.goto(ORIGIN + '/#/admin', 300)   // 先落壳，避免 hash 首次设置时 SPA 还没接管
  await b.goto(ORIGIN + '/#/' + route, 3500)
  const s = JSON.parse(await b.evalJS(SNAP))
  await b.shotFull(file)
  await b.close()

  console.log(`\n── ${label} ──`)
  console.log(JSON.stringify(s, null, 2))
  if (errs.length) console.log('   JS 错误:\n     ' + errs.join('\n     '))
  if (dialogs.length) console.log('   阻塞式对话框:\n     ' + JSON.stringify(dialogs))
  return { s, errs, dialogs }
}

// ⚠️ fixture：`_work/vis-commit2-expected.md` 的本地库（12 单 / GMV 10,073,164.00）。
//    下面每个期望值都是**从库里现跑出来**的，不是凭印象写的。
const admin = await run({
  label: '管理端 #/admin/stats', role: '3', merchantId: '', customerId: 'PROBE',
  route: 'admin/stats', file: 'docs/agent/evidence/vis-dashboard/page-admin-stats.png'
})
const merchant = await run({
  label: '商家端 #/merchant/stats', role: '2', merchantId: '5N1k5K2guh', customerId: 'PROBE',
  route: 'merchant/stats', file: 'docs/agent/evidence/vis-dashboard/page-merchant-stats.png'
})

// ── 断言 ──────────────────────────────────────────────────────────────────────────
const A = admin.s
r.ok('管理端：路由停在 /admin/stats（没被守卫踢走）', A.hash === '#/admin/stats', A.hash)
r.ok('管理端：5 张 KPI 卡', A.kpi.length === 5, A.kpi.length)
r.ok('管理端：KPI 没有骨架残留（加载已结束）', A.kpiSkeleton === 0, A.kpiSkeleton)
r.ok('管理端：KPI 数字都不是占位 `—`（说明数据真回来了）',
  A.kpi.every((k) => k.value && k.value !== '—'), A.kpi.map((k) => k.value))
r.ok('管理端：GMV 合计 = 10,073,164.00（本地库真值）',
  A.kpi[0] && A.kpi[0].value === '10,073,164.00', A.kpi[0] && A.kpi[0].value)
r.ok('管理端：成单数合计 = 12', A.kpi[1] && A.kpi[1].value === '12', A.kpi[1] && A.kpi[1].value)
r.ok('管理端：3 块图表画布', A.canvasCount === 3, A.canvasCount)
r.ok('管理端：3 块画布都有尺寸', A.canvasSizes.every(([w, h]) => w > 0 && h > 0), A.canvasSizes)
r.ok('管理端：3 块画布都真画了（不透明像素 > 300）', A.inkPixels.every((n) => n > 300), A.inkPixels)
r.ok('管理端：逐日明细 6 行（site 口径的 6 个已聚合日）', A.tbodyRows === 6, A.tbodyRows)
r.ok('管理端：合计行 6 格', A.tfootCells === 6, A.tfootCells)
r.ok('管理端：没有「还没预聚合」横幅（库里有 13 行）', A.gate === 0, A.gate)
r.ok('管理端：没有错误浮层', A.errorOverlays === 0, A.errorText)
r.ok('管理端：无 JS 错误', admin.errs.length === 0, admin.errs)
r.ok('管理端：无阻塞式 alert/confirm', admin.dialogs.length === 0, admin.dialogs)

const M = merchant.s
r.ok('商家端：路由停在 /merchant/stats（没被守卫踢走）', M.hash === '#/merchant/stats', M.hash)
r.ok('商家端：5 张 KPI 卡', M.kpi.length === 5, M.kpi.length)
r.ok('商家端：KPI 数字都不是占位 `—`', M.kpi.every((k) => k.value && k.value !== '—'),
  M.kpi.map((k) => k.value))
r.ok('商家端：本店 GMV 合计 = 10,073,151.00（本地库真值）',
  M.kpi[0] && M.kpi[0].value === '10,073,151.00', M.kpi[0] && M.kpi[0].value)
r.ok('商家端：3 块画布（趋势 / 成单 / 商品榜）', M.canvasCount === 3, M.canvasCount)
// ⚠️ 商品榜**上线即空**是预期（`order_item` 不回填存量单）⇒ 它那一块是**空态浮层**，
//    像素判据对它不成立（只有前两块要求真画了）。这条断言就是在钉「空态是**说出来的**，
//    不是一块白画布」。
r.ok('商家端：前 2 块画布真画了', M.inkPixels.slice(0, 2).every((n) => n > 300), M.inkPixels)
r.ok('商家端：商品榜是**空态浮层**（order_item 0 行，预期）',
  M.emptyOverlays === 1 && M.inkPixels[2] < 3000, { empty: M.emptyOverlays, ink: M.inkPixels[2] })
r.ok('商家端：逐日明细 6 行', M.tbodyRows === 6, M.tbodyRows)
r.ok('商家端：没有错误浮层', M.errorOverlays === 0, M.errorText)
r.ok('商家端：无 JS 错误', merchant.errs.length === 0, merchant.errs)
r.ok('商家端：无阻塞式 alert/confirm', merchant.dialogs.length === 0, merchant.dialogs)

process.exit(r.dump())
