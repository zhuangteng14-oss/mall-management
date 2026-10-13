// probe-b6c7-admin-ui.mjs —— C3 块 6 · commit 7〈收口〉**管理端页面**手验（2026-10-13）。
//
// 跑法：`node scripts/probes/probe-b6c7-admin-ui.mjs`
// 依赖：① `docs/美化/_cdp.mjs`（headless Edge + CDP）
//      ② `npm run serve` 的 8080（`.env.development` 的 API_BASE = `http://localhost:8088`）
//      ③ **真后端**（a3 profile）—— 本探针**不 mock 任何请求**。
//
// ⚠️ 为什么这一发**不做 mock**：`scripts/probes/*.mjs` 里那一批（`probe-msg-center.mjs` 等）
//    是**交互逻辑**的验收，mock 掉后端是对的（要构造四种红点组合那种存量里没有的态）。
//    本发要的是「**真数据落到真页面上**」—— 第 4 段那批手验产物（`_work/b6-hv-full.txt`）
//    到底有没有从 HTTP 冒到界面上，mock 一拦就什么都验不到了。
//
// ⚠️ 登录态：`Page.addScriptToEvaluateOnNewDocument` 在**任何应用脚本之前**往 sessionStorage
//    写 `loginUsername` / `customerid` / `role`（键名见 `src/api/auth.js` 的 `K`；`role` 必须是
//    **字符串** `"3"`，数字 3 会让每个角色比较恒 false ⇒ 重定向成环，见 `auth.js:76` 那段注释）。
//    ⚠️ `authToken` **不写**：a3 的 `app.auth.enabled=false`，本页的写操作走 cookie/免鉴权路径。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/business/moderation'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block6/hv-shots/'
fs.mkdirSync(EV, { recursive: true })

const R = report('管理端「消息与内容治理」页（真后端 a3 @ 8088）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }
const num = (s) => { const m = String(s).replace(/\s/g, '').match(/-?\d+/) ; return m ? Number(m[0]) : NaN }

const b = await open({ w: 1560, h: 1000 })

// ── 会话注入（必须在应用脚本之前）────────────────────────────────────────────
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

/** 当前可见（`v-show` 没藏起来的）面板里「行状元素」的条数。
 *  六个面板各用各的壳（不是一张统一表格）：评论/问答/举报 = `li.g-row`，
 *  敏感词 = `tbody tr`，待审图片 = `li.g-shot`，消息 = `li.g-ev`。 */
const ROWS_IN_VISIBLE_PANEL = `(() => {
  const ps = [...document.querySelectorAll('.g-panel')].filter(p => p.offsetParent !== null)
  const p = ps[0]
  if (!p) return -1
  return p.querySelectorAll('.g-row, tbody tr, .g-shot, .g-ev').length
})()`

const VISIBLE_PANEL_TEXT = `(() => {
  const ps = [...document.querySelectorAll('.g-panel')].filter(p => p.offsetParent !== null)
  return ps[0] ? ps[0].innerText : ''
})()`

const clickTab = (i) => b.evalJS(
  `(() => { const t = [...document.querySelectorAll('.g-tabs .g-tab')][${i}]; if (!t) return false; t.click(); return true })()`)

try {
  await b.goto(`${BASE}/${ROUTE}`, 3000)
  await sleep(2500)   // 首页要连打 5 个「只取 total」的请求

  // ── ① 页面真的挂上了 ────────────────────────────────────────────────────
  const text = await b.evalJS(`document.body.innerText`)
  ok('① 页面挂载（#app 文字长度 > 200）', String(text).length > 200, String(text).length)
  ok('① 面包屑/侧栏含「消息与内容治理」', String(text).includes('消息与内容治理'))
  const errs = await b.evalJS(`(window.__errs || [])`)
  ok('① 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))
  // ⚠️ 这一条是**回填**出来的：第一版探针没查这里，`ui-01-overview.png` 上却实拍到了
  //    冷进页面就挂一条红 flash（`onMounted` 拿着空 `TargetId` 调 `loadComments()`，
  //    把「还没下钻」当成了失败）。修法见 `ModerationPage.vue` 的 `loadComments({silent})`。
  const coldBad = await b.evalJS(
    `[...document.querySelectorAll('.g-page > .g-flash, .g-flash')].filter(p => p.classList.contains('bad')).length`)
  ok('① 冷进页面无红 flash（`loadComments` 的空 TargetId 不再当失败）', coldBad === 0, coldBad)
  const coldHint = await b.evalJS(
    `(() => { const p = [...document.querySelectorAll('.g-panel')].filter(x => x.offsetParent !== null)[0]
       return p ? p.innerText.includes('本栏按对象下钻') : false })()`)
  ok('① 冷进页面 · 评论栏停在灰底引导文案上', coldHint === true, coldHint)

  // ── ② 五个状态条的**全量口径**数字 ───────────────────────────────────────
  const stats = await b.evalJS(
    `[...document.querySelectorAll('.g-status .g-stat')].map(d => d.innerText.replace(/\\s+/g, ' ').trim())`)
  ok('② 状态条 5 格', stats.length === 5, stats)
  const statOf = (label) => { const s = stats.find((x) => x.includes(label)); return s ? num(s) : NaN }
  const nReport = statOf('待处理举报')
  const nImage = statOf('待审图片')
  const nWords = statOf('敏感词')
  const nUnread = statOf('未读治理消息')
  ok('② 待处理举报 ≥ 1（种子 2 条里 1 条已驳回、1 条 pending ⇒ ≥1）', nReport >= 1, nReport)
  ok('② 待审图片 ≥ 1（种子 Id=4 pending）', nImage >= 1, nImage)
  ok('② 敏感词 = 11（种子 10 + 手验 ㉙ 加的那一发「线材」；㉗ 的 `hv_b6_probe_word` 已被 ㉙″ 删掉）',
     nWords === 11, nWords)
  ok('② 未读治理消息 ≥ 1（管理端共享收件箱里有 question.timeout / report.handled / comment.*）',
     nUnread >= 1, nUnread)

  // ── ③ 六个 tab 的标签 ───────────────────────────────────────────────────
  // ⚠️ `innerText` 里含**图标字形**（`IconGlyph`）与角标数字（如「待审图片\n1」）⇒ 用 `includes`，
  //    不用 `startsWith`；顺序仍按 `TABS` 数组逐位比。
  const tabs = await b.evalJS(`[...document.querySelectorAll('.g-tabs .g-tab')].map(t => t.innerText.replace(/\\s+/g, ' ').trim())`)
  const WANT = ['评论管理', '问答管理', '待审图片', '举报中心', '敏感词库', '消息管理']
  ok('③ 六个 tab 且顺序一致', tabs.length === 6 && WANT.every((w, i) => tabs[i].includes(w)), tabs)
  await b.shot(EV + 'ui-01-overview.png')

  // ── ④ 逐个 tab 看「有没有行」（`v-show`，所以按可见面板取数）─────────────
  const TAB_CASES = [
    { i: 0, name: '评论管理', expect: '前面两个字段由下一段填' },
    { i: 1, name: '问答管理', expect: '前面两个字段由下一段填' },
    { i: 2, name: '待审图片', min: 1 },
    { i: 3, name: '举报中心', min: 1 },
    { i: 4, name: '敏感词库', min: 11 },
    { i: 5, name: '消息管理', min: 1 },
  ]
  for (const c of TAB_CASES) {
    await clickTab(c.i)
    await sleep(1600)
    const n = await b.evalJS(ROWS_IN_VISIBLE_PANEL)
    if (c.min !== undefined) ok(`④ ${c.name}：行数 ≥ ${c.min}`, n >= c.min, n)
    else ok(`④ ${c.name}：面板已渲染（行数 ≥ 0，未填对象时允许 0）`, n >= 0, n)
    await b.shot(EV + `ui-0${c.i + 2}-${c.i}.png`)
  }

  // ── ⑤ 评论管理：按对象下钻 goods 80（第 2 段那三条手验评价就在它下面）────
  await clickTab(0)
  await sleep(500)
  const filled = await b.evalJS(`(() => {
    const p = [...document.querySelectorAll('.g-panel')].filter(x => x.offsetParent !== null)[0]
    if (!p) return 'no-panel'
    const inp = p.querySelector('input[type=text]')
    if (!inp) return 'no-input'
    inp.value = '80'
    inp.dispatchEvent(new Event('input', { bubbles: true }))
    const btn = [...p.querySelectorAll('button[type=submit]')][0]
    if (!btn) return 'no-submit'
    btn.click()
    return 'clicked'
  })()`)
  ok('⑤ 评论管理下钻表单已提交（goods 80）', filled === 'clicked', filled)
  await sleep(2000)
  const cRows = await b.evalJS(ROWS_IN_VISIBLE_PANEL)
  ok('⑤ 评论管理 · goods 80 命中 ≥ 3 条（手验 ⑥/⑦/⑱′ 三条评价）', cRows >= 3, cRows)
  const cText = await b.evalJS(VISIBLE_PANEL_TEXT)
  ok('⑤ 评论管理 · 面板里能看到手验那条的正文', String(cText).includes('手验'), String(cText).slice(0, 120))
  const cCanvas = await b.evalJS(
    `(() => { const p = [...document.querySelectorAll('.g-panel')].filter(x => x.offsetParent !== null)[0]; return p ? p.querySelectorAll('canvas').length : -1 })()`)
  ok('⑤ 评论管理 · 有 ECharts 画布（评分分布 + 赞/踩）', cCanvas >= 2, cCanvas)
  await b.shot(EV + 'ui-08-comments-drilldown.png')

  // ── ⑥ 问答管理：下钻 goods 76 ──────────────────────────────────────────
  await clickTab(1)
  await sleep(500)
  const filledQ = await b.evalJS(`(() => {
    const p = [...document.querySelectorAll('.g-panel')].filter(x => x.offsetParent !== null)[0]
    if (!p) return 'no-panel'
    const inp = p.querySelector('input[type=text]')
    if (!inp) return 'no-input'
    inp.value = '76'
    inp.dispatchEvent(new Event('input', { bubbles: true }))
    const btn = [...p.querySelectorAll('button[type=submit]')][0]
    if (!btn) return 'no-submit'
    btn.click()
    return 'clicked'
  })()`)
  ok('⑥ 问答管理下钻表单已提交（goods 76）', filledQ === 'clicked', filledQ)
  await sleep(2000)
  const qRows = await b.evalJS(ROWS_IN_VISIBLE_PANEL)
  ok('⑥ 问答管理 · goods 76 命中 ≥ 3 条提问（种子 110/111 + 手验 ⑭）', qRows >= 3, qRows)
  const qCanvas = await b.evalJS(
    `(() => { const p = [...document.querySelectorAll('.g-panel')].filter(x => x.offsetParent !== null)[0]; return p ? p.querySelectorAll('canvas').length : -1 })()`)
  ok('⑥ 问答管理 · 有 ECharts 画布（已解决/未解决环图等）', qCanvas >= 2, qCanvas)
  await b.shot(EV + 'ui-09-questions-drilldown.png')

  // ── ⑦ 全页画布总数（6 个面板的 DOM 一直在，故这里是**全页**口径）────────
  const allCanvas = await b.evalJS(`document.querySelectorAll('.g-page canvas').length`)
  ok('⑦ 全页 ECharts 画布 ≥ 8（六个面板的图一起算）', allCanvas >= 8, allCanvas)

  const errs2 = await b.evalJS(`(window.__errs || [])`)
  ok('⑦ 走完全程仍无 console.error / unhandledrejection', errs2.length === 0, errs2.slice(0, 3))
} catch (e) {
  ok('探针异常', false, String(e && e.message ? e.message : e))
} finally {
  const bad = R.dump()
  fs.writeFileSync(EV + 'result.json', JSON.stringify({ total: TOTAL, failed: bad }, null, 2))
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
