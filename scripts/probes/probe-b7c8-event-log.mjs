// probe-b7c8-event-log.mjs —— 块 7 · commit 8〈实时日志页〉的**渲染 + 交互**实测。
//
// 跑法：① `npm run serve`（8080，两入口同一台）② `node scripts/probes/probe-b7c8-event-log.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
//
// ⚠️ **自接管 `Fetch` 拦截，不用 `_cdp.mjs` 的 `mock`**（同 `probe-b7c5` / `probe-b7c7` 的理由）。
//
// ⚠️ **本页的数据源是 SSE 流**，而 `Fetch.fulfillRequest` 只能给**一整块** body（不是分块推送）
//   ⇒ 这里把 2 拍拼成一个 body 一起回。前端 `openStream` 会把它切成 2 帧、逐帧 `onFrame`，
//   行为与真流一致；差别只有「一次到齐」这一点。真后端上的逐拍推进另算（本笔的端到端在真 8088 上验）。
//
// ⚠️ 帧的**形状**必须与后端 `EventStreamService.frame()` 逐字对齐（9 个键）：
//   `seq / ts / cursor / count / rate / counts / severity / events / traces`。
//   卡片那 10 列 = `ObservedEvent` 的 10 个字段（`seq/ts/kind/severity/title/detail/key/objectType/objectId/traceId`）。
//
// 查十件事：
//   ① 页面挂载 / 无 console.error / 打的是 POST `.../events/stream`
//   ② **8 类图例**：标签逐字（缓存/锁/订单/库存/审计/通知/敏感词/身份）+ **8 个不同的色**
//   ③ 严重度三枚 + **MQ 黄点**（`.a-el-mq.is-off`）+ 那条「MQ 未连接」提示
//   ④ 卡片流：最新的 8 条（一条一类）排在最前，且**每类的类色不同**
//   ⑤ **对象列**八类各自的说法：审计带 `TargetType:TargetId`、敏感词带命中词、身份带请求路径
//   ⑥ **筛选**：点掉「缓存」那一类 ⇒ 缓存卡片全部消失（其它类不动）
//   ⑦ **分页**：共 158 条 → 第 1 页 100 条；点「下一页」→ 第一张卡片换人
//   ⑧ **抽屉**：点一张带 traceId 的卡 ⇒ 弹层 = 开始 + 8 条 + 结束，含总耗时 42 ms 与 traceId
//   ⑨ 没有 traceId 的卡片**不可点**（没有 `role=button`）
//   ⑩ 关掉抽屉 ⇒ 弹层消失
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/observe/logs'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'
const SHOT = `${EV}out-b7c8-event-log.png`
const FULL = `${EV}out-b7c8-event-log-full.png`

const R = report('块 7 · commit 8 实时日志页（mock 后端 SSE）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const T = 1750000000000
const TRACE = 'trace0001'

/** 八条「有意思的」事件（一条一类，各自带本类**特有**的对象字段）—— 它们是最新的，故在卡片流最前。 */
const PERKIND = [
  ['cache', '缓存命中（L1）', 'cache=goodstype result=hit key=all', 'goodsType:all', 'cache', 'goodsType:all'],
  ['lock', '取锁成功（order:create）', 'key=order:create result=acquired', 'order:create/O-1', 'lock', 'order:create/O-1'],
  ['order', '状态流转 waitPay → waitShip', 'orderId=O-1 from=waitPay to=waitShip', 'O-1', 'order', 'O-1'],
  ['stock', '库存扣减', 'sku=SKU-0001 qty=1 result=ok', 'SKU-0001', 'sku', 'SKU-0001'],
  ['audit', '审计写入', 'action=goods.delete actor=C-1', 'goods.delete', 'goods', 'M-1:P-1'],
  ['notify', '通知发送', 'type=email event=order.paid', 'C-1', 'notify', 'C-1'],
  ['sensitive', '敏感词拒收', 'scene=评论 hit=9 total=20 result=rejected', '评论', 'sensitive', '傻逼,滚'],
  ['identity', '身份拒绝', 'subject=C-1 reason=令牌过期 result=rejected', 'C-1', 'identity', '/goods/goodsUp'],
].map(([kind, title, detail, key, objectType, objectId], i) => ({
  seq: 1000 + i,
  // ⚠️ ts **按 i 递减**（不是递增）：全部晚于下面那批（保证排最前），而页面收一拍时会把**本拍内**
  //    按 `ts` 倒序排（`absorbEvents`）⇒ 递减的 ts 正好让卡片顺序 = `KINDS` 声明序，
  //    断言才好写。递增的话卡片会是反的 —— 那是页面的正确行为，不是缺陷。
  //    ⚠️ 步长 4ms、区间落在 [startMs, endMs]（T+5000…T+5028 ⊂ T+4990…T+5032）——
  //    mock 也要自洽：事件时刻跑到「请求结束」之后，抽屉里的链路时间戳就成假的了。
  ts: T + 5000 + (7 - i) * 4,
  kind, severity: kind === 'sensitive' || kind === 'identity' ? 'warn' : 'info',
  title, detail, key, objectType, objectId,
  traceId: TRACE,                  // 八条同属一次请求 ⇒ 抽屉里能看到 8 条中间节点
}))

/** 批量事件（第 2 拍），只为了把条数撑过一页（每页 100）。**没有 traceId** —— 专门用来验「不可点」。 */
const BULK = Array.from({ length: 150 }, (_, i) => ({
  seq: i,
  ts: T + i * 7,
  kind: ['cache', 'lock', 'order', 'stock', 'audit', 'notify', 'sensitive', 'identity'][i % 8],
  severity: i % 11 === 0 ? 'error' : (i % 5 === 0 ? 'warn' : 'info'),
  title: `批量事件 ${i}`,
  detail: `idx=${i} src=bulk#${i}`,
  key: `K-${i}`,
  objectType: 'bulk',
  objectId: `B-${i}`,
  traceId: null,
}))

const FRAME1 = {
  seq: 1, ts: T + 1000, cursor: 149, count: 150, rate: 12.5,
  counts: { cache: 19, lock: 19, order: 19, stock: 19, audit: 19, notify: 19, sensitive: 18, identity: 18 },
  severity: { info: 118, warn: 29, error: 3 },
  events: BULK, traces: [],
}
const FRAME2 = {
  seq: 2, ts: T + 2000, cursor: 157, count: 8, rate: 8.0,
  counts: { cache: 1, lock: 1, order: 1, stock: 1, audit: 1, notify: 1, sensitive: 1, identity: 1 },
  severity: { info: 6, warn: 2, error: 0 },
  events: PERKIND,
  // 请求台账（`TraceLog` 的形状）：这一次请求的起止与总耗时。
  traces: [{
    seq: 0, traceId: TRACE, method: 'POST', path: '/goods/goodsUp',
    status: 200, startMs: T + 4990, endMs: T + 5032, ms: 42,
  }],
}

const SSE_BODY = [FRAME1, FRAME2].map((f) => `data: ${JSON.stringify(f)}\n\n`).join('')

const CORS = [
  { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8080' },
  { name: 'Access-Control-Allow-Credentials', value: 'true' },
  { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
  { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' },
]

const seen = []
const b = await open({ w: 1440, h: 1100 })

await b.send('Fetch.enable', { patterns: [{ urlPattern: '*8088*' }] })
b.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method !== 'Fetch.requestPaused') return
  const { requestId, request } = m.params
  seen.push({ url: request.url, method: request.method })
  const answer = () => {
    if (request.method === 'OPTIONS') {
      b.send('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: CORS, body: '' }).catch(() => {})
      return
    }
    const isStream = request.url.includes('/admin/observe/events/stream')
    const body = isStream ? SSE_BODY : JSON.stringify({ success: true })
    b.send('Fetch.fulfillRequest', {
      requestId, responseCode: 200,
      responseHeaders: [...CORS, { name: 'Content-Type', value: isStream ? 'text/event-stream' : 'application/json' }],
      body: Buffer.from(body).toString('base64'),
    }).catch(() => {})
  }
  // 压 1.2 秒再放：确保「挂载 → 帧到达 → 渲染」这三步在断言前都完成（同 `probe-b7c7` 的手法）。
  setTimeout(answer, 1200)
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

const TEXT = (sel) => `[...document.querySelectorAll('${sel}')].map(e => (e.innerText ?? e.textContent ?? '').trim())`
const COLOR = (sel) => `[...document.querySelectorAll('${sel}')].map(e => getComputedStyle(e).color)`

try {
  await b.send('Page.navigate', { url: `${BASE}/${ROUTE}` })
  let mounted = false
  for (let i = 0; i < 120; i++) {
    mounted = await b.evalJS(`!!document.querySelector('.a-el')`).catch(() => false)
    if (mounted) break
    await sleep(250)
  }
  ok('① 页面挂载（.a-el 出现）', mounted === true)
  await sleep(2200)      // 1.2 秒 mock 延迟 + 渲染
  // 再等一拍：`openStream` 是把整块 body 切帧逐帧回调，两帧之间没有间隔，给一拍冗余。
  await sleep(500)

  // ── ① 挂载面 ───────────────────────────────────────────────────────────────
  const body = await b.evalJS('document.body.innerText')
  ok('① 正文含「事件分类」与「事件流」', String(body).includes('事件分类') && String(body).includes('事件流'))
  const errs = await b.evalJS('(window.__errs || [])')
  ok('① 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))
  // ⚠️ 本仓的 `<template>` 里写 `**粗**` **不会**被渲染成粗体，会**原样上屏**（本次实测踩过两次：
  //    MQ 提示条、分类那节的 h2n）。这条断言把它钉住 —— 渲染文本里不该出现连续两个星号。
  const elText = await b.evalJS(`(document.querySelector('.a-el') || {}).innerText || ''`)
  ok('① 渲染文本里没有 markdown 星号（`**粗**` 在模板里会原样上屏）', !String(elText).includes('**'))
  const streamReq = seen.find((r) => r.url.includes('/admin/observe/events/stream'))
  ok('① 打的是 POST /admin/observe/events/stream',
    !!streamReq && streamReq.method === 'POST', streamReq ? streamReq.method : '(没打到流)')

  // ── ② 8 类图例：标签逐字 + 8 个不同的色 ─────────────────────────────────────
  // ⚠️ 只取 8 类那 8 枚：严重度那三枚也是 `.a-el-kind`（同一个样式类）⇒ 用 `.a-el-kind .a-el-ic`
  //    的存在与否区分（严重度画的是圆点 `.a-el-dot`，没有图标）。
  const kindLabels = await b.evalJS(`[...document.querySelectorAll('.a-el-kinds .a-el-kind')].filter(e => e.querySelector('.a-el-ic')).map(e => e.querySelector('.a-el-kindn').innerText.trim())`)
  ok('② 8 类图例，标签逐字 = 缓存/锁/订单/库存/审计/通知/敏感词/身份',
    JSON.stringify(kindLabels) === JSON.stringify(['缓存', '锁', '订单', '库存', '审计', '通知', '敏感词', '身份']), kindLabels)
  const kindColors = await b.evalJS(`[...document.querySelectorAll('.a-el-kinds .a-el-kind')].filter(e => e.querySelector('.a-el-ic')).map(e => getComputedStyle(e.querySelector('.a-el-ic')).color)`)
  ok('② 8 类**每类一色**（8 个互不相同的计算色值）',
    kindColors.length === 8 && new Set(kindColors).size === 8, kindColors)
  const kindIconCount = await b.evalJS(`[...document.querySelectorAll('.a-el-kinds .a-el-kind')].filter(e => e.querySelector('.a-el-ic')).map(e => e.querySelectorAll('.a-el-ic path').length)`)
  ok('② 8 类**每类一图标**（每枚至少 1 条 path）', kindIconCount.length === 8 && kindIconCount.every((n) => n >= 1), kindIconCount)

  // ── ③ 严重度三枚 + MQ 黄点 + 提示条 ────────────────────────────────────────
  const sevLabels = await b.evalJS(`[...document.querySelectorAll('.a-el-kinds .a-el-sevchip')].map(e => e.querySelector('.a-el-kindn').innerText.trim())`)
  ok('③ 严重度三枚 = 信息/警告/错误', JSON.stringify(sevLabels) === JSON.stringify(['信息', '警告', '错误']), sevLabels)
  ok('③ MQ 黄点 = 未连接（.a-el-mq.is-off）', (await b.evalJS(`!!document.querySelector('.a-el-mq.is-off')`)) === true)
  const mqText = await b.evalJS(`(document.querySelector('.a-el-mq') || {}).innerText || ''`)
  ok('③ MQ chip 写着「未连接」', String(mqText).includes('MQ') && String(mqText).includes('未连接'), mqText)
  ok('③ 那条「MQ 未连接…事件不落库」的提示在场',
    String(body).includes('MQ 未连接') && String(body).includes('不落库'))

  // ── ④ 卡片流：最新的一批（一条一类）排在最前，类色各异 ─────────────────────
  const firstKinds = await b.evalJS(TEXT('.a-el-row .a-el-kindn'))
  ok('④ 前 8 张卡片 = 第 2 拍那八类（顺序 = 帧里的顺序）',
    JSON.stringify(firstKinds.slice(0, 8)) === JSON.stringify(['缓存', '锁', '订单', '库存', '审计', '通知', '敏感词', '身份']), firstKinds.slice(0, 8))
  const rowColors = await b.evalJS(`[...document.querySelectorAll('.a-el-row')].slice(0, 8).map(e => getComputedStyle(e.querySelector('.a-el-ic')).color)`)
  ok('④ 前 8 张卡片的类色两两不同', rowColors.length === 8 && new Set(rowColors).size === 8, rowColors)
  const rowCount = await b.evalJS(`document.querySelectorAll('.a-el-row').length`)
  ok('④ 第 1 页 100 条卡片（共 158 条 ⇒ 每页 100）', Number(rowCount) === 100, rowCount)

  // ── ⑤ 对象列：审计带 TargetType:TargetId、敏感词带命中词、身份带请求路径 ────
  const objByKind = await b.evalJS(`(() => {
    const out = {}
    for (const r of document.querySelectorAll('.a-el-row')) {
      const k = (r.querySelector('.a-el-kindn') || {}).innerText
      if (!k || out[k]) continue
      out[k] = ((r.querySelector('.a-el-obj') || {}).innerText || '').trim()
    }
    return out
  })()`)
  ok('⑤ 审计卡的对象列 = 「对象 goods:M-1:P-1」（TargetType + TargetId 两列）',
    objByKind['审计'] === '对象 goods:M-1:P-1', objByKind['审计'])
  ok('⑤ 敏感词卡的对象列 = 「命中的词 傻逼,滚」', objByKind['敏感词'] === '命中的词 傻逼,滚', objByKind['敏感词'])
  ok('⑤ 身份卡的对象列 = 「请求路径 /goods/goodsUp」', objByKind['身份'] === '请求路径 /goods/goodsUp', objByKind['身份'])
  ok('⑤ 库存卡取的是 SKU（不是别的列）', objByKind['库存'] === 'SKU SKU-0001', objByKind['库存'])

  // ── ⑥ 筛选：点掉「缓存」⇒ 缓存卡片全消失 ──────────────────────────────────
  await b.evalJS(`(() => {
    const chip = [...document.querySelectorAll('.a-el-kinds .a-el-kind')]
      .find(e => e.querySelector('.a-el-kindn') && e.querySelector('.a-el-kindn').innerText.trim() === '缓存')
    chip.click(); return true
  })()`)
  await sleep(250)
  const afterOff = await b.evalJS(`(() => {
    const labels = [...document.querySelectorAll('.a-el-row .a-el-kindn')].map(e => e.innerText.trim())
    return { total: labels.length, cache: labels.filter(x => x === '缓存').length }
  })()`)
  ok('⑥ 点掉「缓存」后：第 1 页不再有缓存卡片、但仍有别类',
    Number(afterOff.cache) === 0 && Number(afterOff.total) > 0, afterOff)
  // ⚠️ `.a-h2n` 有**两枚**（「事件分类」那节、「事件流」那节各一枚）⇒ 不能 `querySelector` 取第一枚
  //    （那是分类那节的）。⚠️ 也不能只判「含『窗口内』」—— 分类那枚写的是「数字 = 本页窗口内累计」，
  //    两个字眼它都有（探针第一版就栽在这儿）。要挑**形状**对的那一枚：`窗口内 N / M 条` 这个比。
  const summary = await b.evalJS(`(() => {
    const n = [...document.querySelectorAll('.a-h2n')].find(e => /窗口内\\s*[\\d,]+\\s*\\/\\s*[\\d,]+\\s*条/.test(e.innerText))
    return n ? n.innerText : ''
  })()`)
  ok('⑥ 筛选后「窗口内 158 / 158」变成「窗口内 N / 158」（分子变小、分母不动）',
    /窗口内\s*\d+\s*\/\s*158/.test(String(summary).replace(/\s+/g, ' ')), String(summary).slice(0, 60))
  // 打开回来
  await b.evalJS(`(() => {
    const chip = [...document.querySelectorAll('.a-el-kinds .a-el-kind')]
      .find(e => e.querySelector('.a-el-kindn') && e.querySelector('.a-el-kindn').innerText.trim() === '缓存')
    chip.click(); return true
  })()`)
  await sleep(200)

  // ── ⑦ 分页 ────────────────────────────────────────────────────────────────
  const pagerSum = await b.evalJS(`(document.querySelector('.pager-sum') || {}).innerText || ''`)
  ok('⑦ 分页条写着「事件流 · 共 158 条」',
    String(pagerSum).replace(/\s+/g, '').includes('事件流') && String(pagerSum).replace(/\s+/g, '').includes('共158条'),
    pagerSum.replace(/\s+/g, ' '))
  const firstBefore = await b.evalJS(`(document.querySelector('.a-el-row .a-el-title') || {}).innerText || ''`)
  const timesBefore = await b.evalJS(TEXT('.a-el-row .a-el-t'))
  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.pager-btns .pg')].find(e => e.innerText.trim() === '下一页')
    btn.click(); return true
  })()`)
  await sleep(300)
  const firstAfter = await b.evalJS(`(document.querySelector('.a-el-row .a-el-title') || {}).innerText || ''`)
  const timesAfter = await b.evalJS(TEXT('.a-el-row .a-el-t'))
  ok('⑦ 点「下一页」后第一张卡片换人（第 1 页与第 2 页不是同一批）',
    !!firstBefore && !!firstAfter && firstBefore !== firstAfter && timesBefore[0] !== timesAfter[0],
    [firstBefore, firstAfter])
  const page2Count = await b.evalJS(`document.querySelectorAll('.a-el-row').length`)
  ok('⑦ 第 2 页 = 余下的 58 条', Number(page2Count) === 58, page2Count)
  // 回第 1 页（抽屉那一项要在第 1 页上做，因为带 traceId 的 8 条在最前）
  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.pager-btns .pg')].find(e => e.innerText.trim() === '首页')
    btn.click(); return true
  })()`)
  await sleep(300)

  await b.shot(SHOT)
  await b.shotFull(FULL)

  // ── ⑧ 抽屉：点第一张（缓存，带 trace0001）──────────────────────────────────
  await b.evalJS(`(() => { document.querySelector('.a-el-row').click(); return true })()`)
  await sleep(300)
  ok('⑧ 点一张带 traceId 的卡弹出抽屉', (await b.evalJS(`!!document.querySelector('.a-el-modal')`)) === true)
  const modalTrace = await b.evalJS(`(document.querySelector('.a-el-mono') || {}).innerText || ''`)
  ok('⑧ 抽屉头上的 traceId = trace0001', String(modalTrace).trim() === TRACE, modalTrace)
  const chain = await b.evalJS(TEXT('.a-el-chain-i .a-el-chain-d'))
  ok('⑧ 链路 = 开始 + 8 条中间 + 结束（共 10 项）', chain.length === 10, chain.length)
  ok('⑧ 首项是「请求开始」、末项是「请求结束」',
    String(chain[0]).includes('请求开始') && String(chain[chain.length - 1]).includes('请求结束'), [chain[0], chain[chain.length - 1]])
  const msText = await b.evalJS(`(document.querySelector('.a-el-modal-sub') || {}).innerText || ''`)
  ok('⑧ 抽屉写着总耗时 42 ms', String(msText).includes('42 ms'), String(msText).replace(/\s+/g, ' '))
  const status = await b.evalJS(`(document.querySelector('.a-el-st') || {}).innerText || ''`)
  ok('⑧ 状态码 200（2xx 走中性色）', String(status).trim() === '200', status)

  // ── ⑩ 关掉 ────────────────────────────────────────────────────────────────
  await b.evalJS(`(() => { document.querySelector('.a-el-close').click(); return true })()`)
  await sleep(200)
  ok('⑩ 关闭后抽屉消失', (await b.evalJS(`!!document.querySelector('.a-el-modal')`)) === false)

  // ── ⑨ 没有 traceId 的卡片不可点 ────────────────────────────────────────────
  // ⚠️ 第 1 页前 8 张都带 traceId、第 9 张起是批量事件（traceId 为 null）⇒ 取第 9 张。
  const notClickable = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.a-el-row')]
    const r = rows[8]
    if (!r) return 'no-row'
    return {
      role: r.getAttribute('role'),
      tabindex: r.getAttribute('tabindex'),
      trace: (r.querySelector('.a-el-trace') || {}).innerText || '',
      title: (r.querySelector('.a-el-title') || {}).innerText || '',
    }
  })()`)
  ok('⑨ 无 traceId 的卡片：没有 role / tabindex，traceId 那格写「无」',
    notClickable !== 'no-row' && notClickable.role === null && notClickable.tabindex === null &&
      String(notClickable.trace).trim() === '无',
    notClickable)
  // 点它也不该开抽屉
  await b.evalJS(`(() => { document.querySelectorAll('.a-el-row')[8].click(); return true })()`)
  await sleep(250)
  ok('⑨ 点它不会开抽屉', (await b.evalJS(`!!document.querySelector('.a-el-modal')`)) === false)
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
