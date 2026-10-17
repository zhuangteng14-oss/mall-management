// probe-b7c8-event-stream.mjs —— 块 7 · commit 8〈实时日志页〉**对着真后端**的端到端实测。
//
// 与 `probe-b7c8-event-log.mjs`（mock）的分工：那个验「帧怎么渲染」，这个验「**真流能不能跑**」。
// 两件事必须分开验，因为 mock 里 SSE 是一次性给完整 body（`Fetch.fulfillRequest` 不支持分块推送）
// ⇒ 「逐拍推进」这件事**只有真后端验得了**。
//
// 跑法：① 后端 **dev** profile 在 8088（`app.auth.enabled=false`）② `npm run serve`（8080）
//       ③ `node scripts/probes/probe-b7c8-event-stream.mjs`
//
// ⚠️ **不拦截 Fetch**：本探针的全部价值就在于打真后端。任何 mock 都会让它变成上面那个探针。
//
// ⚠️ **八环怎么亮起来的**：八个环都在**进程内存**里，后端一重启就是空的 ⇒ 不能只「看」，
//    必须**真打业务端点**把它喂出来。本探针自己驱动（`drive()`，五条真 HTTP）：
//      · `POST /goods/goodstype`        → 缓存环（L1 未命中 / 缓存命中）
//      · `POST /cart/list`              → 身份环（放行；闸关着 ⇒ fail-open，见 IdentityGuard 注释）
//      · `POST /admin/notify/send`      → 审计环 + 通知环（**一条打两环**：该端点自己既发通知又记审计）
//      · `POST /comment/add`（拒收档）  → 敏感词环 + 身份环
//      · `POST /order/orderAdd`         → 锁环 ×2 + 订单环 + 库存环（**四条同一个 traceId** ⇒ 抽屉那一项有料）
//    ⚠️ 为什么不用**攻击引擎**（`POST /admin/observe/attack/run`，八场景一键）来喂：它今天在
//      dev + 当前 VM 库上**跑不起来**（`DrillTargetDao` 挑演练账号的 SQL `ORDER BY u.id`
//      与库里 `user` 表不符 ⇒ `Unknown column 'u.id' in 'order clause'` ⇒ 500）。
//      那是**既有缺陷、与本笔无关**（本笔未碰攻击引擎任何一行），已记进收笔报告。
//      ⇒ 本探针改用直连业务端点的办法喂环，喂法逐条列在上面。
//    ⚠️ 副作用**如实记**：驱动会在库里**真写**①一行订单（`OrderId` 前缀 `SIM-` + `Source=attack`）
//      —— 与攻击引擎写的是同一种标记，可按同一套 SQL 清理；②评论**不写**（拒收档在写库前就拦下了，
//      见下面 `content` 那条注释）。报告里给了清理 SQL。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const API = 'http://localhost:8088'
const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/observe/logs'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'
const SHOT = `${EV}out-b7c8-event-stream.png`

const R = report('块 7 · commit 8 实时日志页（真后端 8088）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const KIND_LABELS = ['缓存', '锁', '订单', '库存', '审计', '通知', '敏感词', '身份']

/* ── 真 HTTP 打业务端点（**没有 mock**）──────────────────────────────────────── */
/** 库里挑出来的真货：一条在架商品的码与商家号（`/goods/goodsSelectHome` 读回的第一个）。 */
const SKU = { code: 'GD-101-9552', name: '无线蓝牙耳机 声谷标准版', merchant: 'dRD3gqtbFr' }
const CUST = 'qa7PmpUojO'

async function post(path, form) {
  const r = await fetch(API + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
    body: new URLSearchParams(form).toString(),
  })
  return { status: r.status, body: (await r.text()).slice(0, 200) }
}

const stamp = Date.now()
async function drive() {
  const out = []
  const step = async (label, path, form) => {
    const r = await post(path, form)
    out.push(`${label} ${path} → ${r.status} ${r.body}`)
    // 每喂一环之间留 1.2 秒：后端一秒一拍，全挤在同一拍里会看不出「逐拍推进」。
    await sleep(1200)
  }
  await step('缓存', '/goods/goodstype', {})
  await step('身份', '/cart/list', { CustomerId: CUST })
  await step('审计+通知', '/admin/notify/send', {
    title: 'b7c8 探针', body: '探针正文', eventType: 'probe.b7c8', toId: CUST, toType: 'customer',
  })
  // ⚠️ 拒收档（命中占比 ≥ 40%）：**回 success:false 且不写库** —— 打码档会真插一行评论。
  //    正文里那三个词是真库里的敏感词（`POST /sensitive/list` 读回的前三个）。
  await step('敏感词+身份', '/comment/add', {
    Account: 'ops_duan', TargetType: 'user', TargetId: CUST, Kind: 'review',
    Content: 'SIM-广告博彩刷单', Rating: '5', Anonymous: '0',
    ProductCode: SKU.code, Source: 'attack',
  })
  // ⚠️ 这一条会**真建单**（本仓唯一的建单入口，锁/库存/订单三环都长在它身上）。
  //    `SIM-` 前缀 + `Source=attack` 与攻击引擎写的行**同一种标记**，可一起清理。
  await step('锁+订单+库存', '/order/orderAdd', {
    CustomerId: CUST, MerchantId: SKU.merchant, OrderId: `SIM-B7C8-${stamp}`,
    Price: '299', ProductInformation: SKU.name, Images: '', PayIf: '0',
    DeliveryIf: '0', ReceiveIf: '0', Quantity: '1', Source: 'attack',
    Items: JSON.stringify([{ code: SKU.code, name: SKU.name, price: 299, qty: 1 }]),
  })
  return out
}

/* ── 浏览器侧 ─────────────────────────────────────────────────────────────── */
const b = await open({ w: 1440, h: 1100 })

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
const tickOf = `(() => { const m = document.body.innerText.match(/第\\s*(\\d+)\\s*拍/); return m ? Number(m[1]) : 0 })()`

try {
  await b.send('Page.navigate', { url: `${BASE}/${ROUTE}` })
  let mounted = false
  for (let i = 0; i < 120; i++) {
    mounted = await b.evalJS(`!!document.querySelector('.a-el')`).catch(() => false)
    if (mounted) break
    await sleep(250)
  }
  ok('① 页面挂载（.a-el 出现）', mounted === true)

  // ── ① 真流是不是真的在推（喂环之前先验一遍，免得后面的断言被「帧本来就是静态的」解释掉）──
  await sleep(3500)
  const tick1 = await b.evalJS(tickOf)
  await sleep(3000)
  const tick2 = await b.evalJS(tickOf)
  ok('① 流是**活的**（不是「已断开」）—— 真流会一直推，与 mock 那条一次性 body 不同',
    String(await b.evalJS(`(document.querySelector('.a-chip-live') || {}).innerText || ''`)).trim() === '已连接',
    await b.evalJS(`(document.querySelector('.a-chip-live') || {}).innerText || ''`))
  ok('① 拍号**自己在走**（两次采样间至少 +2 拍，每秒一拍）',
    Number(tick2) >= Number(tick1) + 2, `${tick1} → ${tick2}`)
  ok('① 无 console.error / unhandledrejection', (await b.evalJS('(window.__errs || [])')).length === 0)

  // ── ② 喂环：五条真 HTTP，把八个环逐个打亮 ────────────────────────────────────
  const drove = await drive()
  console.log('驱动结果：\n' + drove.map((s) => '  ' + s).join('\n'))
  await sleep(3000)          // 等三拍，让上面的写入点都进帧

  // ── ③ 八类的图例数字**全部 ≥ 1**（窗口内累计，由页面自己数）────────────────────
  const legend = await b.evalJS(`(() => {
    const out = {}
    for (const btn of document.querySelectorAll('.a-el-kinds .a-el-kind')) {
      if (!btn.querySelector('.a-el-ic')) continue
      out[btn.querySelector('.a-el-kindn').innerText.trim()] = Number((btn.querySelector('.a-el-kc').innerText || '0').replace(/,/g, ''))
    }
    return out
  })()`)
  const missing = KIND_LABELS.filter((k) => !(Number(legend[k]) >= 1))
  ok('③ 八类图例**全部 ≥ 1 条**（真帧驱动出来的，不是 mock）',
    Object.keys(legend).length === 8 && missing.length === 0, JSON.stringify(legend))

  // ── ④ 卡片流里八类都真的出现了，且类色两两不同 ──────────────────────────────
  const cardKinds = await b.evalJS(TEXT('.a-el-row .a-el-kindn'))
  const uniq = [...new Set(cardKinds)]
  ok('④ 卡片流覆盖全部八类', KIND_LABELS.every((k) => uniq.includes(k)), uniq)
  const rowColors = await b.evalJS(`(() => {
    const out = {}
    for (const r of document.querySelectorAll('.a-el-row')) {
      const k = (r.querySelector('.a-el-kindn') || {}).innerText
      if (k && !out[k]) out[k] = getComputedStyle(r.querySelector('.a-el-ic')).color
    }
    return out
  })()`)
  ok('④ 八类的类色两两不同（真页面上的计算色值）',
    new Set(Object.values(rowColors)).size === Object.keys(rowColors).length && Object.keys(rowColors).length === 8,
    JSON.stringify(rowColors))

  // ── ⑤ D 项那三列（objectType/objectId）+ 命中的词 + 请求路径，**真的流到了 UI** ──
  const objByKind = await b.evalJS(`(() => {
    const out = {}
    for (const r of document.querySelectorAll('.a-el-row')) {
      const k = (r.querySelector('.a-el-kindn') || {}).innerText
      if (!k || out[k]) continue
      out[k] = ((r.querySelector('.a-el-obj') || {}).innerText || '').trim()
    }
    return out
  })()`)
  ok('⑤ 审计卡 = 「对象 notification:<批次号>」（`TargetType:TargetId` 两列都非空）',
    /^对象\s+notification:\S+/.test(objByKind['审计'] || ''), objByKind['审计'])
  ok('⑤ 敏感词卡 = 「命中的词 <真词>」（命中的词真的进了 `objectId`）',
    /^命中的词\s+\S+/.test(objByKind['敏感词'] || ''), objByKind['敏感词'])
  ok('⑤ 身份卡 = 「请求路径 /<路径>」（被校验的路径真的进了 `objectId`）',
    /^请求路径\s+\/[\w/]+$/.test(objByKind['身份'] || ''), objByKind['身份'])
  ok('⑤ 订单卡的对象列是**探针自己下的那一单**（`SIM-B7C8-*`）',
    /^订单\s+SIM-B7C8-/.test(objByKind['订单'] || ''), objByKind['订单'])
  ok('⑤ 库存卡取的是 SKU 码', /^SKU\s+\S+/.test(objByKind['库存'] || ''), objByKind['库存'])

  // ── ⑥ traceId 是真的（`TraceIdFilter` 在发号，不是空串）──────────────────────
  const traces = await b.evalJS(TEXT('.a-el-row .a-el-trace'))
  const uuids = traces.filter((s) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(s))
  ok('⑥ 卡片上的 traceId 是**真 UUID**（`TraceIdFilter` 生成的号进了事件）',
    uuids.length >= 8, `${uuids.length}/${traces.length} 条是 UUID 形状：${uuids.slice(0, 2).join(' ')}…`)

  await b.shot(SHOT)

  // ── ⑦ 抽屉：点「订单」那张卡 → 链路两端来自真 `traces`（`TraceLog` 台账）────────
  const clicked = await b.evalJS(`(() => {
    const row = [...document.querySelectorAll('.a-el-row')]
      .find(r => (r.querySelector('.a-el-kindn') || {}).innerText === '订单')
    if (!row) return false
    row.click(); return true
  })()`)
  ok('⑦ 找得到「订单」卡片并点开了', clicked === true)
  await sleep(400)
  const chain = await b.evalJS(TEXT('.a-el-chain-i .a-el-chain-d'))
  const ends = await b.evalJS(`document.querySelectorAll('.a-el-chain-i.is-ends').length`)
  ok('⑦ 链路两端在（起 = 请求开始 / 终 = 请求结束）—— **只有真 `traces` 在推才可能出现**',
    Number(ends) === 2 && String(chain[0]).includes('请求开始') && String(chain[chain.length - 1]).includes('请求结束'),
    [chain[0], chain[chain.length - 1]])
  ok('⑦ 链路两端是**这一次请求**（起 = `POST /order/orderAdd`）',
    /请求开始（POST \/order\/orderAdd）/.test(String(chain[0])), chain[0])
  ok('⑦ 中间那条把这次下单碰到的环串起来了（≥3 条，含锁/订单/库存里的至少两类）',
    chain.length >= 5 && ['锁', '订单', '库存'].filter((k) => chain.some((c) => c.includes(k))).length >= 2,
    chain)
  const sub = String(await b.evalJS(`(document.querySelector('.a-el-modal-sub') || {}).innerText || ''`))
  ok('⑦ 抽屉上的总耗时是**真数**（`TraceLog` 记的 `ms`）',
    /总耗时\s+\d+ ms/.test(sub.replace(/\s+/g, ' ')), sub.replace(/\s+/g, ' '))
  await b.evalJS(`(() => { document.querySelector('.a-el-close').click(); return true })()`)
  await sleep(250)
  ok('⑦ 抽屉可关闭', (await b.evalJS(`!!document.querySelector('.a-el-modal')`)) === false)
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
