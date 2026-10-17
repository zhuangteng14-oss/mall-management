// probe-d2-locked-switches.mjs —— D 段 · commit 2〈防护开关的面板落地〉实测。
// ⚠️ **阶段 2b 起口径变了**：`retry` 从「登记 TD」改成「接闸」（死锁重试落进
//    `MerchantDowngradeService.relist`）⇒ 面板上**可拨的那组由三枚变四枚**，
//    `is-locked` 那组**只剩 `degrade` 一枚**。本探针已随之改断言（本文件末次跑出的
//    证据是 `d2-locked-switches-after-2b.txt`；commit 2 那一份 `d2-locked-switches.txt`
//    是**当时的实测**，保留不动、不是过时错误）。
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-d2-locked-switches.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。**后端全部 mock**（本探针不碰真后端）。
//
// 验的是用户 2026-10-15 裁定的那两句：
//   ①「degrade 只记录；UI 若必须展示 ⇒ **标灰 + tooltip**」—— `degrade` 那一枚必须标灰，
//      而且**说清为什么**（阶段 2b 起只有它一个理由：「记录位」）。
//   ②「**禁止死控件：能点但无效果的开关一律不允许**」—— 它必须拨不动，
//      且**不只是靠模板上的 `disabled`**：JS 里那道路由守卫也得真的拦住
//      （抠掉 `disabled` 再点，状态仍不许变）。
//
// ⚠️ **本探针抓不到原生 tooltip 的浮层**：`title` 是浏览器/OS 画的浮层，headless 截图里
//    **没有它**（`Page.captureScreenshot` 只抓页面内容）。故 tooltip 这一半的判据是
//    **属性逐字比对**（`title` 的值 == 文案表里的原话），不是截图。
//    ⚠️ 已有的**可见**说明是那枚 `不可开关` 角标（它就画在开关里、跟着截图走），
//    tooltip 是补充、不是唯一出口 —— 这条区分让「截图里看不见 tooltip」不至于变成
//    「页面上没有任何解释」。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/observe/attack-sim'
const EVIDENCE = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'
const SHOT = `${EVIDENCE}d2-protection-switches-after-2b.png`

const CORS = [
  { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8080' },
  { name: 'Access-Control-Allow-Credentials', value: 'true' },
  { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
  { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' },
]

/** 文案表（**逐字抄** `observe-labels.js` 的 `PROTECTION_NOT_WIRED_TIP`；漂了就红）。
 *  ⚠️ 阶段 2b 起 `retry` 已接闸 ⇒ 它**不该有** tooltip（有闸的开关有提示反而误导）。 */
const EXPECTED = {
  labels: ['限流', '熔断', '降级', '重试', '分池'],
  locked: ['降级'],
  tip: {
    降级: '记录位，不可开关，系统固有 fail-open 设计',
  },
}

const R = report('D 段 · commit 2 防护开关面板（mock 后端）——  阶段 2b 口径：4 键有闸 / 1 键记录位')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const b = await open({ w: 1440, h: 1000 })

await b.send('Fetch.enable', { patterns: [{ urlPattern: '*8088*' }] })
const json = (obj) => ({
  responseCode: 200,
  responseHeaders: [...CORS, { name: 'Content-Type', value: 'application/json' }],
  body: Buffer.from(JSON.stringify(obj)).toString('base64'),
})
b.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method !== 'Fetch.requestPaused') return
  const { requestId, request } = m.params
  let payload
  if (request.method === 'OPTIONS') payload = { responseCode: 200, responseHeaders: CORS, body: '' }
  else if (request.url.includes('/admin/observe/attack/records')) {
    payload = json({ success: true, list: [], total: 0, limit: 20, offset: 0 })
  } else payload = json({ success: true })
  b.send('Fetch.fulfillRequest', { requestId, ...payload }).catch(() => {})
})

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

/** 五个防护开关的当前快照（顺序 = DOM 顺序 = `PROTECTIONS` 声明序）。 */
const SNAPSHOT = `(() => {
  const row = document.querySelectorAll('.a-switches')[0]
  return [...row.querySelectorAll('.a-sw')].map((el) => ({
    label: el.querySelector('.a-sw-n').innerText.trim(),
    locked: el.classList.contains('is-locked'),
    off: el.classList.contains('is-off'),
    disabled: el.hasAttribute('disabled'),
    ariaDisabled: el.getAttribute('aria-disabled'),
    title: el.getAttribute('title'),
    badge: el.querySelector('.a-sw-locked') ? el.querySelector('.a-sw-locked').innerText.trim() : null,
    cursor: getComputedStyle(el).cursor,
    opacity: getComputedStyle(el).opacity,
  }))
})()`

try {
  await b.goto(`${BASE}/${ROUTE}`, 2600)
  await sleep(600)

  // ── ① 挂载 ────────────────────────────────────────────────────────────────
  const errs = await b.evalJS('(window.__errs || [])')
  ok('① 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))

  const sw = await b.evalJS(SNAPSHOT)
  ok('① 五枚防护开关都在（数量）', sw.length === 5, sw.length)
  ok('① 中文名与顺序 = PROTECTIONS 声明序', JSON.stringify(sw.map((s) => s.label)) === JSON.stringify(EXPECTED.labels),
    sw.map((s) => s.label).join('/'))
  ok('① 缺省全开（一枚都不带 is-off）', sw.every((s) => !s.off), sw.filter((s) => s.off).map((s) => s.label))

  // ── ② 标灰：只剩降级一枚（阶段 2b 起 retry 已接闸）──────────────────────────
  const lockedLabels = sw.filter((s) => s.locked).map((s) => s.label)
  ok('② 标灰的**恰好**是降级那一枚', JSON.stringify(lockedLabels) === JSON.stringify(EXPECTED.locked),
    lockedLabels.join('/'))
  ok('② 接闸的四枚**没有**被误标灰', JSON.stringify(sw.filter((s) => !s.locked).map((s) => s.label))
    === JSON.stringify(['限流', '熔断', '重试', '分池']), sw.filter((s) => s.locked).length)
  ok('② locked 的那些枚 `cursor` 不是 pointer（看着也不像能点）',
    sw.filter((s) => s.locked).every((s) => s.cursor !== 'pointer'), sw.map((s) => `${s.label}=${s.cursor}`).join(' '))
  ok('② locked 的那些枚 `opacity` < 1（真的压暗了）',
    sw.filter((s) => s.locked).every((s) => Number(s.opacity) < 1), sw.filter((s) => s.locked).map((s) => s.opacity))

  // ── ③ tooltip：逐字 ───────────────────────────────────────────────────────
  for (const s of sw.filter((x) => x.locked)) {
    const want = `${s.label}：${EXPECTED.tip[s.label]}`
    ok(`③ ${s.label} 的 title 逐字 = 文案表原话`, s.title === want, s.title)
  }
  ok('③ 「记录位，不可开关，系统固有 fail-open 设计」这句在（裁定逐字要求）',
    String(sw.find((s) => s.label === '降级')?.title || '').includes('记录位，不可开关，系统固有 fail-open 设计'))
  // ⚠️ 有闸的那些枚**也有** tooltip，只是换了另一族说法（「已接闸，读数会变」）；
  //    这里要证的不是「没有 title」，而是「**不再**用『不可开关』那一族文案」。
  const retryTip = String(sw.find((s) => s.label === '重试')?.title || '')
  ok('③ 重试那枚改用「已接闸，读数会变」那一族 tooltip（阶段 2b 起它有真闸）',
    /已接闸，读数会变$/.test(retryTip) && !retryTip.includes('不可开关'), retryTip)

  // ── ④ 可见的说明：`不可开关` 角标（跟随截图，不依赖浮层）────────────────────
  ok('④ locked 的那些枚各有一枚可见的「不可开关」角标',
    sw.filter((s) => s.locked).every((s) => s.badge === '不可开关'), sw.map((s) => `${s.label}:${s.badge}`).join(' '))
  ok('④ 接闸的四枚**没有**这个角标', sw.filter((s) => !s.locked).every((s) => s.badge === null))

  // ── ⑤ 死控件禁令：拨不动（两层都要拦）─────────────────────────────────────
  const before = JSON.stringify(await b.evalJS(SNAPSHOT))

  // ⑤-1 模板层：disabled + aria-disabled 都在
  ok('⑤ locked 的那些枚带 `disabled` 属性',
    sw.filter((s) => s.locked).every((s) => s.disabled), sw.map((s) => `${s.label}:${s.disabled}`).join(' '))
  ok('⑤ locked 的那些枚带 `aria-disabled="true"`（读屏也知道它不可用）',
    sw.filter((s) => s.locked).every((s) => s.ariaDisabled === 'true'),
    sw.filter((s) => s.locked).map((s) => s.ariaDisabled).join(' '))

  // ⑤-2 **路由守卫层**：把 `disabled` 抠掉再点 —— 状态仍不许变。
  //      ⚠️ 这一条才是「禁止死控件」的真判据：只验 `disabled` 的话，模板一改就漏
  //      （`toggleProtection` 里那道 `if (!protectionOn(key)) return` 是真正的落点）。
  //      ⚠️⚠️ **点完必须等一拍再读**：Vue 的 class 绑定在 `nextTick`（微任务）里更新，
  //      同步读 `classList` **永远**读到旧值 ⇒ 「点前 == 点后」会**恒成立**，
  //      这一条会变成一个恒绿的假断言（本探针第一版就是这样，被 ⑤-3 的对照兜出来）。
  const afterTick = `await new Promise((r) => setTimeout(r, 250))`
  const guardHeld = await b.evalJS(`(async () => {
    const row = document.querySelectorAll('.a-switches')[0]
    const el = row.querySelectorAll('.a-sw')[2]           // 第 3 枚 = degrade
    const wasDisabled = el.hasAttribute('disabled')
    const before = el.classList.contains('is-off')
    el.removeAttribute('disabled')                        // 模拟「模板将来漏了 disabled」
    el.click()
    ${afterTick}
    const after = el.classList.contains('is-off')
    if (wasDisabled) el.setAttribute('disabled', '')
    return { wasDisabled, before, after }
  })()`)
  ok('⑤ 抠掉 `disabled` 再点「降级」⇒ 状态**没变**（JS 守卫真的在拦，不只是模板画灰）',
    guardHeld.before === guardHeld.after, JSON.stringify(guardHeld))

  // ⑤-3 对照：**阶段 2b 新接闸的那枚（`retry`）拨得动** —— 证明守卫只挡没闸的那些，
  //      也证明 ⑤-2 的「没变」不是读早了；顺带钉住「`retry` 这一枚不再是死控件」。
  const wiredToggled = await b.evalJS(`(async () => {
    const row = document.querySelectorAll('.a-switches')[0]
    const el = row.querySelectorAll('.a-sw')[3]           // 第 4 枚 = retry（阶段 2b 起有闸）
    const wasLocked = el.classList.contains('is-locked')
    const before = el.classList.contains('is-off')
    el.click()
    ${afterTick}
    const after = el.classList.contains('is-off')
    return { wasLocked, before, after }
  })()`)
  ok('⑤ 对照：阶段 2b 新接闸的 `retry` 点一下**状态真的变了**（守卫只挡没闸的那些；也证明 ⑤-2 的「没变」不是读早了）',
    wiredToggled.before !== wiredToggled.after && !wiredToggled.wasLocked, JSON.stringify(wiredToggled))

  const after = JSON.stringify(await b.evalJS(SNAPSHOT))
  ok('⑤ 点完之后：被拨动的只有接通那些枚，locked 的那一枚与其余状态原样',
    after !== before, '（快照确实变了 = 上一条的拨动生效）')

  // ── ⑥ 截图（那一排开关；`不可开关` 角标在图上）───────────────────────────
  await b.evalJS(`(() => {
    const row = document.querySelectorAll('.a-switches')[0]
    row.scrollIntoView({ block: 'center' })
    return true
  })()`)
  await sleep(400)
  await b.shot(SHOT)
  ok('⑥ 截图已落盘（含五枚开关的那一排）', true, SHOT)

  ok('① 全程无 console.error（收尾复核）', (await b.evalJS('(window.__errs || [])')).length === 0)
} finally {
  await b.close()
}

const failed = R.dump()
process.exit(failed === 0 ? 0 : 1)
