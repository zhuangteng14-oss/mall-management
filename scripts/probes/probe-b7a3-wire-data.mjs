/**
 * 3a.3 · 把**真抓的 60 拍事件帧**喂给日志页两版结构草图（`docs/美化/c3-admin-preview/_struct-wire-ev.js`）。
 *
 * 【数据来源】`_work/3a3/frames-events.ndjson`（`probe-b7a3-capture-events.mjs` 从
 * `POST /admin/observe/events/stream` 逐字抓的 60 帧，2026-10-16 · dev 后端 · 同期跑真负载）。
 * ⚠️ 3a.2b 的 `_work/3a2b/frames.ndjson` 是**指标流**（没有 events / rate / traces）—— 喂不了本页。
 * ⚠️ `_work/` 在 `.gitignore` 里 ⇒ 仓里另存一份留档：
 * `docs/agent/evidence/c3-block7/b7a3-events-60tick.ndjson`。
 *
 * 【口径：逐字复刻 EventLog.vue 的收帧】
 * 本脚本**不发明**任何数字，它把页面 `absorbEvents` / `absorbTraces` 那两段原样重放一遍：
 *   · 事件按 `seq` 去重 → **本拍内**按 ts/seq 倒序 → 整批**前插**（不全局重排）→ 封顶 EVENT_CAP
 *   · 链路同款（按 endMs 倒序前插）→ 封顶 TRACE_CAP
 * 于是 `kinds` / `sevs` 就是**页面自己会显示的**「窗口内累计」——不是帧里那个本拍的 `counts`。
 * （帧里的 `counts` 每拍会归零；图例数字必须用这个口径，见 EventLog.vue 图例那段注释。）
 *
 * 【纪律 · 生成的注释里不写「文件:行号」】
 * 本块是**产物**（每次重抓都重写）。要定位请用**符号名**，不写行号 —— 同 TD-74/76/77/78 的处置。
 *
 * 跑法：`node scripts/probes/probe-b7a3-wire-data.mjs`
 */
import fs from 'node:fs'
import { createHash } from 'node:crypto'
import { pathToFileURL } from 'node:url'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目/'
const IN = ROOT + '_work/3a3/frames-events.ndjson'
const WIRE = ROOT + 'docs/美化/c3-admin-preview/_struct-wire-ev.js'
const BEGIN = '/* @@EV-DATA:BEGIN'
const END = '/* @@EV-DATA:END'

/* ── 标签 / 颜色 / 图标：**现从真页面的那张表里读**，不在本文件手抄一份 ──────────
   理由：手抄 = 迟早漂（改了真页面没人记得改草图）。九类图标（`KIND_ICON`）就是真页面 ③ 行首
   画的那九枚，这里原样搬进草图。
   ⚠️ 不能直接 `import` 它：那边是 CJS 包（`package.json` 没有 `type: module`），`.js` 会被当
       CommonJS 解析，`export` 当场报语法错。改用「读源码 → 剥掉每行开头的 `export ` →
       在函数里求值」——对一个**没有任何 import、没有副作用**的纯表模块，这个变换是安全的，
       而且它保证拿到的是**此刻仓里的那一份**。先断言它确实没有 import，再动手。 */
const LABELS_PATH = ROOT + 'Mall-management-web/mall-management-system/src/config/labels/observe-labels.js'
const LABELS_SRC = fs.readFileSync(LABELS_PATH, 'utf8')
if (/^[ \t]*import[ {*]/m.test(LABELS_SRC)) {
  console.error('⛔ observe-labels.js 里出现了 import —— 这个「剥 export 再求值」的取法就不成立了')
  process.exit(1)
}
const LABELS = new Function(
  LABELS_SRC.replace(/^[ \t]*export[ \t]+/gm, '') +
  '\nreturn { KINDS: KINDS, KIND_LABEL: KIND_LABEL, KIND_ICON: KIND_ICON,' +
  ' OBJECT_FIELD_LABEL: OBJECT_FIELD_LABEL, SEVERITIES: SEVERITIES, SEVERITY_LABEL: SEVERITY_LABEL }')()

/* 与页面逐字同源的两个上限 */
const EVENT_CAP = 1000
const TRACE_CAP = 300
/* ⚠️ 这个 **不是**真页面的常数，是**本版主张**：真页面那一列是分页表、一页 100 条（`PAGE_DEFAULT`），
   没有「DOM 里留多少行」这回事。本版去掉分页、改成 tail -f ⇒ 得自己给 DOM 封顶，
   取 200 行（比一屏多得多，往回翻十几屏都还在）。它不是给人看的数字，是防止
   长会话把 DOM 拖垮的那个上限。 */
const ROW_MAX = 200

const F = fs.readFileSync(IN, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
const N = F.length
const last = F[N - 1]

const p2 = (n) => String(n).padStart(2, '0')
const hms = (ts) => { const d = new Date(Number(ts)); return p2(d.getHours()) + ':' + p2(d.getMinutes()) + ':' + p2(d.getSeconds()) }
const hmsms = (ts) => { const d = new Date(Number(ts)); return hms(ts) + '.' + String(d.getMilliseconds()).padStart(3, '0') }
const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : 0 }

/* ── 收帧重放 ──────────────────────────────────────────────────────────────── */
/* 四张表都来自真页面（见上方 import）。这里只留一个**断言**：九类同序同集，
   否则下面所有按下标算的东西（图标、类色、图例序）会静默错位。 */
const KINDS = LABELS.KINDS
const KIND_LABEL = LABELS.KIND_LABEL
const SEVERITIES = LABELS.SEVERITIES
const SEVERITY_LABEL = LABELS.SEVERITY_LABEL
const OBJECT_FIELD_LABEL = LABELS.OBJECT_FIELD_LABEL
const KIND_ICON = LABELS.KIND_ICON
if (KINDS.join(',') !== 'cache,lock,order,stock,audit,notify,sensitive,identity,protection') {
  console.error('⛔ KINDS 与草图预期的九类/顺序不一致：' + KINDS.join(','))
  process.exit(1)
}

const seenSeq = new Set()
let events = []
const tracesById = new Map()
let traceList = []
const traceSeen = new Set()

/* 统计整窗（不是页面窗口）：用来报「本窗 9 类各来没来」 */
const winByKind = {}, winBySev = {}
let winEvents = 0

let tickNo = -1
for (const f of F) {
  tickNo++
  const batch = Array.isArray(f.events) ? f.events : []
  for (const raw of batch) {
    winEvents++
    winByKind[raw.kind] = (winByKind[raw.kind] || 0) + 1
    winBySev[raw.severity] = (winBySev[raw.severity] || 0) + 1
  }
  /* absorbEvents */
  const fresh = []
  for (const raw of batch) {
    const s = num(raw.seq)
    if (seenSeq.has(s)) continue
    seenSeq.add(s)
    fresh.push({
      seq: s, ts: num(raw.ts), kind: raw.kind, severity: raw.severity,
      title: raw.title, detail: raw.detail, key: raw.key,
      objectType: raw.objectType, objectId: raw.objectId,
      traceId: raw.traceId === null || raw.traceId === undefined ? '' : String(raw.traceId),
      /* 这条事件原本属于哪一拍 —— ③ 的「▶ 重放」按真帧的每拍条数推进，不另编节奏 */
      tick: tickNo,
    })
  }
  if (fresh.length) {
    fresh.sort((a, b) => b.ts - a.ts || b.seq - a.seq)
    const dropped = events.slice(Math.max(0, EVENT_CAP - fresh.length))
    events = fresh.concat(events.slice(0, Math.max(0, EVENT_CAP - fresh.length)))
    for (const d of dropped) seenSeq.delete(d.seq)
  }
  /* absorbTraces */
  const ft = []
  for (const raw of (Array.isArray(f.traces) ? f.traces : [])) {
    const id = raw.traceId === null || raw.traceId === undefined ? '' : String(raw.traceId)
    if (!id) continue
    const row = { traceId: id, method: raw.method, path: raw.path, status: num(raw.status),
      startMs: num(raw.startMs), endMs: num(raw.endMs), ms: num(raw.ms) }
    tracesById.set(id, row)
    traceSeen.add(id)
    ft.push(row)
  }
  if (ft.length) {
    ft.sort((a, b) => b.endMs - a.endMs)
    const merged = ft.concat(traceList)
    const gone = merged.slice(TRACE_CAP)
    const kept = merged.slice(0, TRACE_CAP)
    traceList = kept
    for (const g of gone) if (!kept.some((k) => k.traceId === g.traceId)) tracesById.delete(g.traceId)
  }
}

/* ── 对象列（逐字照页面的 objText）────────────────────────────────────────── */
function objText(ev) {
  const id = ev.objectId
  if (id === null || id === undefined || id === '') return ''
  const label = OBJECT_FIELD_LABEL[ev.kind] || '对象'
  const type = ev.kind === 'audit' && ev.objectType ? String(ev.objectType) + ':#' : ''
  return label + ' ' + type + id
}

const byKind = {}; const bySev = {}
for (const e of events) { byKind[e.kind] = (byKind[e.kind] || 0) + 1; bySev[e.severity] = (bySev[e.severity] || 0) + 1 }

/* ── 抽屉：挑一个**真实存在**的 traceId ─────────────────────────────────────
   口径：在**最近 60 条**事件里挑「链上事件最多」的那个号。为什么限定在最近 60 条 ——
   抽屉的演示要能看见：⑤ 打开时同号卡片会在 ③ 里高亮（动效落点 6），若挑中的号落在
   卡片区之外，那一处落点就成了「标了但看不见」。第一条先按「最近 24 条」（卡片区），
   不够再放宽到 60 条；再不够就退回全局。挑不到号（窗口里一个带号的都没有）⇒ 退回
   「最近一条 traces 记录」，那时中间是空的 —— 本窗有真东西，走不到那一支。 */
/* 链长在**整个页面窗口**里数（一个号的链路可能跨好几拍），但**候选**限定在卡片区里。 */
const chainLen = {}
for (const e of events) if (e.traceId) chainLen[e.traceId] = (chainLen[e.traceId] || 0) + 1
function bestIn(pool) {
  const per = {}
  for (const e of pool) if (e.traceId) per[e.traceId] = true
  let p = '', b = -1
  for (const k of Object.keys(per)) if ((chainLen[k] || 0) > b) { b = chainLen[k] || 0; p = k }
  return { id: p, n: b }
}
/* ⚠️ 卡片区画多少条：真页面默认**每页 100 条**，这里只画 40 条 ——
   两版并排比构图时页面本来就很长，画满 100 条会让「比结构」变成「比谁滚得久」。
   40 条够看出一列卡片的节奏，也够容下抽屉那条同号卡片（落点 6 要看得见）。 */
/* ⚠️ 卡片区画多少条：真页面默认**每页 100 条**（`PAGE_DEFAULT`），这里画 50 条 ——
   两版并排比构图时页面本来就很长，画满 100 条会让「比结构」变成「比谁滚得久」；
   50 条够看出一列卡片的节奏，也够容下抽屉那条同号卡片（落点 6 要看得见）。
   本窗实测：585 个不同 traceId，链长多为 4（一次请求串起身份→锁→缓存→订单…）；
   链长 ≥3 的第一个号落在窗口内第 42 位 ⇒ 只画 24 / 40 条时它是**看不见**的，落点 6 会变成一句空话。 */
const CARD_N = 50
/* 逐级放宽，取「第一个能挑出**链上 ≥ 2 条**的池子」：
   链上只有 1 条的抽屉没什么可看（没有「中间那一段」），宁可放宽候选池也要挑出有内容的那一个。 */
let pick = '', bestN = 0
for (const pool of [events.slice(0, CARD_N), events.slice(0, CARD_N * 3), events]) {
  const b = bestIn(pool)
  if (b.n >= 2) { pick = b.id; bestN = b.n; break }
  if (!pick) { pick = b.id; bestN = b.n }
}
/* 全局也没有号 ⇒ 退回 traces 表里最近的一条（中间会是空的，⑤ 仍要在） */
if (!pick) pick = (traceList[0] && traceList[0].traceId) || ''
const rec = tracesById.get(pick) || null
const chain = events.filter((e) => e.traceId === pick).slice().sort((a, b) => a.ts - b.ts || a.seq - b.seq)

/* ── ③ 日志行（本版改法：时间正序、一行一条、前导符归组）─────────────────────
   ⚠️ 和真页面有**两处不同**，是主张不是现状：真页面是「新的在上 + 分页表」（`pageRows`），
      这里改成**时间正序 + 从底部追加**（tail -f）；真页面每条是**两行**（标题/类/对象一行 +
      明细一行，外加左色条与右侧 traceId 列），这里每条**一行**。
   ⚠️ 归组规则 —— 为什么不是「每个 trace 都补一对请求起止」：
      本窗 1000 条事件里有 **581 个不同 traceId**，其中单条事件的占多数；真帧 60 拍累计看，
      1938 个 trace 里 **1476 个只有 1 条事件**。若每个 trace 都补「请求开始/请求结束」，
      请求行会占到屏幕一半 —— 日志就不再是日志。
      ⇒ **只有「事件数 ≥ 2 且拿到 traces 记录」的 trace 才包成一个 req 组**；单条事件的 trace
        走顶格行（traceId 在行尾看得见）。这一条也是 ⑤ 那句「起止两行只来自 traces」的直接后果：
        traces 里没有它 ⇒ 组不起来，只能顶格。 */
const chrono = events.slice().reverse()          // events 是「新的在前」⇒ 反转成正序
const posOf = new Map()
chrono.forEach((e, i) => posOf.set(e.seq, i))
const byTrace = new Map()
for (const e of chrono) {
  if (!e.traceId) continue
  if (!byTrace.has(e.traceId)) byTrace.set(e.traceId, [])
  byTrace.get(e.traceId).push(e)
}
/* traceId → 展示用的 8 位前缀下标（行尾那截号）；FULL 与 IX 同下标，供 ⑤ 反查 traces 记录 */
const TRACE_IX = []
const TRACE_FULL = []
const traceIx = new Map()
const trOf = (id) => {
  if (!id) return -1
  if (!traceIx.has(id)) {
    traceIx.set(id, TRACE_IX.length)
    TRACE_IX.push(id.slice(0, 8))
    TRACE_FULL.push(id)
  }
  return traceIx.get(id)
}
const rows = []            // [pre, ts, lv, k, title, obj, detail, tr, t, gid, anim]
const used = new Set()
let nGroups = 0, nGroupRows = 0, nonContig = 0
const groupable = (e) => e.traceId && (byTrace.get(e.traceId) || []).length >= 2 && tracesById.has(e.traceId)
for (let i = 0; i < chrono.length; i++) {
  const e = chrono[i]
  if (used.has(e.seq)) continue
  const mk = (pre, ts, lv, k, title, obj, detail, tr, t, gid, anim) =>
    rows.push({ pre: pre, ts: ts, lv: lv, k: k, title: title, obj: obj, detail: detail, tr: tr, t: t, gid: gid, anim: anim || 0 })
  if (groupable(e)) {
    const members = byTrace.get(e.traceId).slice()
    const rec0 = tracesById.get(e.traceId)
    const first = posOf.get(members[0].seq), lastp = posOf.get(members[members.length - 1].seq)
    if (lastp - first !== members.length - 1) nonContig++
    const gid = ++nGroups
    /* 落点 6：抽屉那条号的第一行 —— 打开 ⑤ 时整组微染，标在组首 */
    const anim = e.traceId === pick ? 6 : 0
    mk('', hmsms(rec0.startMs), '', -1, '请求开始 ' + rec0.method + ' ' + rec0.path, '', '', trOf(e.traceId), e.tick, gid, anim)
    for (const m of members) {
      mk('', hmsms(m.ts), m.severity.charAt(0), KINDS.indexOf(m.kind), m.title, objText(m), m.detail, trOf(m.traceId), m.tick, gid, 0)
      used.add(m.seq)
      nGroupRows++
    }
    mk('', hmsms(rec0.endMs), '', -1, '请求结束 · 总耗时 ' + rec0.ms + ' ms', '', '', trOf(e.traceId), e.tick, gid, 0)
    nGroupRows += 2
  } else {
    mk('', hmsms(e.ts), e.severity.charAt(0), KINDS.indexOf(e.kind), e.title, objText(e), e.detail, trOf(e.traceId), e.tick, -1,
      e.traceId === pick ? 6 : 0)
    used.add(e.seq)
  }
}
/* ── 时刻列：**同拍内按时刻「组级」稳定排序**（用户 2026-10-16 裁定「乙」）────────
   口径逐条照裁定：只在**同一拍内**排 · 同 ts 保环序（稳定排序）· **跨拍顺序一字不动** ·
   后端 `EventBroadcaster` 的合并环口径（同拍内是环序、不是时刻序）一字不动。
   ③ 的说明里补了那句：「同拍内按时刻局部排，跨拍按环序」。

   ⚠️ 排序的**单位是「组」不是「行」** —— 这是量出来的，不是口味：
     · 行级排序（同拍内逐行排）能把逆序修成 0，但会**打散 29 个组、牵动 174 行**：
       组内相邻行本来就时刻递增（实测组内逆序 0 处），28 处同拍逆序**全部**来自
       「上一组的尾行 vs 下一组的组首」这种**并发重叠**（如 tr388 的结束行 17:53:26.174
       排在 tr389 的组首 17:53:26.171 之后）—— 要修它只能把**别的 trace 的行插进本组中间**，
       与已裁定的「同 trace 用前导符归组」（`├─` / `│  ├─` / `│  └─`）直接冲突，
       `└─` 会闭在一个不属于它的位置上。
     · 组级排序（同一拍内按**组首行**的时刻排、同 ts 保环序）保住了组：
       实测 28 处同拍逆序里修掉 5 处（组首 vs 组首那种）、位移 10 / 865 个 block 槽位。
     ⇒ 落地形态 = **组级稳定排序**。修不掉的那 23 处是并发重叠的固有代价，
       **如实记账、不假装修掉了**（报告里写清「逆序 0」与「组不碎」不能两全）。
   ⚠️ 跨拍的 block（实测 1 个：组的成员被「顺延」到组首之后、拍号不齐）**不参与排序**，
     它当一道隔断，把它前后两段各自排序 —— 跨拍重排正是被禁的那件事。 */
const tsNum = (s) => {
  const m = /(\d+):(\d+):(\d+)\.(\d+)/.exec(s)
  return m ? ((+m[1]) * 3600 + (+m[2]) * 60 + (+m[3])) * 1000 + (+m[4]) : 0
}
const blocks = []
for (let i = 0; i < rows.length; i++) {
  const g = rows[i].gid
  if (!blocks.length || g < 0 || blocks[blocks.length - 1].gid !== g) blocks.push({ gid: g, from: i, to: i, t: rows[i].t })
  else blocks[blocks.length - 1].to = i
}
let blockMoved = 0, blockFirstMoved = -1, blockBarrier = 0
const blockBarrierInfo = []
{
  const pick = []
  let run = [], runT = null
  const flushRun = () => {
    if (run.length > 1) {
      run = run
        .map((k, j) => ({ k: k, j: j, ts: tsNum(rows[blocks[k].from].ts) }))
        .sort((x, y) => (x.ts - y.ts) || (x.j - y.j))       // 同 ts 保环序 ⇒ 稳定排序
        .map((x) => x.k)
    }
    run.forEach((k) => pick.push(k))
    run = []; runT = null
  }
  /* 「同一拍内」的判据是**整组都在同一拍**，不是「首行尾行同拍」（实测踩过：有个组的拍号是
     [5,6,5] —— 首尾同号、中间跳了一拍，只比首尾会把跨拍的组当成本拍内、排错位）。 */
  const oneTick = (b) => { const t = rows[b.from].t; for (let i = b.from + 1; i <= b.to; i++) if (rows[i].t !== t) return false; return true }
  blocks.forEach((b, k) => {
    if (!oneTick(b)) {                                                       // 跨拍组：隔断，原地不动
      flushRun(); pick.push(k); blockBarrier++
      const ts = []; for (let i = b.from; i <= b.to; i++) ts.push(rows[i].t)
      blockBarrierInfo.push('组#' + b.gid + '（行 ' + b.from + '–' + b.to + '，拍号 ' + ts.join(',') + '）')
      return
    }
    if (runT !== null && b.t !== runT) flushRun()
    runT = b.t
    run.push(k)
  })
  flushRun()
  pick.forEach((k, j) => { if (k !== j) { blockMoved++; if (blockFirstMoved < 0) blockFirstMoved = j } })
  const out = []
  pick.forEach((k) => { for (let i = blocks[k].from; i <= blocks[k].to; i++) out.push(rows[i]) })
  rows.splice(0, rows.length, ...out)
}
/* 判据要用的三个数（**现算**，不写死：换了抓帧这三条就变）：同拍内行级逆序 / 跨拍逆序 /
   行级排序会打散多少组 —— 让「组级排序保住了组」这句话有数可对。 */
let invInTick = 0, invCross = 0
for (let i = 1; i < rows.length; i++) {
  if (tsNum(rows[i].ts) >= tsNum(rows[i - 1].ts)) continue
  if (rows[i].t === rows[i - 1].t) invInTick++; else invCross++
}
let brokenGroups = 0, brokenRows = 0
{
  const pos = new Map()                       // 行号 → 若按「同拍内逐行稳定排序」它会在第几位
  const byT = {}
  rows.forEach((r, i) => { (byT[r.t] = byT[r.t] || []).push(i) })
  Object.keys(byT).forEach((t) => {
    const arr = byT[t].map((i, j) => ({ i: i, j: j, ts: tsNum(rows[i].ts) }))
      .sort((x, y) => (x.ts - y.ts) || (x.j - y.j))
    arr.forEach((x, j) => pos.set(x.i, j))
  })
  blocks.forEach((b) => {
    if (b.gid < 0 || b.to === b.from) return
    for (let i = b.from + 1; i <= b.to; i++) {
      if (pos.get(i) !== pos.get(i - 1) + 1) { brokenGroups++; brokenRows += b.to - b.from + 1; break }
    }
  })
}
/* 前导符：顶层 ├─／└─；组内子行 │  ├─ / │  └─；组首顶格（同 txt 里的树）
   ⚠️ 这一步**排在排序之后**：组的首末行可能换了位置（末行该是 `└─`），
      排序先做、前导符后算，才不会把 `└─` 留在一条不再收尾的行上。 */
rows.forEach((r, i) => {
  const lastRow = i === rows.length - 1
  const g = r.gid
  const gFirst = g > 0 && (i === 0 || rows[i - 1].gid !== g)
  const gLast = g > 0 && (i === rows.length - 1 || rows[i + 1].gid !== g)
  if (g < 0 || gFirst) r.pre = lastRow ? '└─' : '├─'
  else r.pre = '│  ' + (gLast ? '└─' : '├─')
})
/* 行数怎么涨：1000 条事件 → 组把 ≥2 条的那些包起来，每组多出「起 / 止」两行 */
const highlightVisible = rows.some((r) => r.anim === 6)

/* traceId → traces 记录，按行里那个下标对齐（[方法, 路径, 耗时 ms, 状态, 起, 止]，没有记录的填 null）。
   ⑤ 点某一行时用它补「请求开始 / 请求结束 · 总耗时」。
   ⚠️ **查不到是常态**：traces 窗口 300 条 ≪ 事件窗口 1000 条，老 trace 早被挤出 traces 表 ——
      这不是数据的毛病，是「⑤ 的起止两行只来自 traces」那条口径的直接后果，页面上要明说。 */
const TRACE_REC = TRACE_FULL.map((id) => {
  const r = tracesById.get(id)
  return r ? [r.method, r.path, r.ms, r.status, hmsms(r.startMs), hmsms(r.endMs)] : null
})
const nRecCovered = TRACE_REC.filter(Boolean).length
/* 末 ROW_MAX 行（= 草图上真正画出来的那些）里包了多少组 —— 判「点某条能不能染出一整组」够不够看 */
let tailGroups = 0
{
  const seen = new Set()
  for (const r of rows.slice(-ROW_MAX)) if (r.gid > 0 && !seen.has(r.gid)) { seen.add(r.gid); tailGroups++ }
}
const anim6Row = rows.findIndex((r) => r.anim === 6)
const selTR = anim6Row >= 0 ? rows[anim6Row].tr : -1

/* ── 速率 ─────────────────────────────────────────────────────────────────── */
const rate = F.map((f) => num(f.rate))
const rateMax = Math.max.apply(null, rate)
const clock = {
  seq: last.seq, tick: hms(last.ts), phase: '已连接', mq: last.mq === true, rate: num(last.rate),
  fresh: (last.events || []).length, freshTrace: (last.traces || []).length,
}

const stats = {
  events: events.length, traces: traceList.length, cap: EVENT_CAP, traceCap: TRACE_CAP,
  droppedTotal: num(last.droppedTotal), demoFed: 0,
}

/* ── 设施 3 类（用户 2026-10-16 裁定的「补齐 3 个后端面」）──────────────────────
   ⚠️ 它们是**类**、不是「源」—— 与既有 9 类**同构**、走同一条筛选路径（裁定原话：
      「慢 SQL / MQ / 调度 → 变成『类』」）。所以它们进 `kinds`，不出现在「源」里。
   ⚠️ 本窗 **0 条是实话**：那 3 个后端面还没落地（A 档：`SlowSqlListener` 事件化 /
      MQ 的 publish·consume·DLQ 事件化 / 7 个 `@Scheduled` 入口各包一层记录器）⇒
      数据里当然还没有它们。与既有 9 类里那 5 个 0 条的类**同样对待**：
      **如实显示 0，不补数、也不置灰**（置灰等于说「它本来就不该有数」，那是假话）。
   ⚠️ `KINDS`（来自真页面 `observe-labels.js`）**不动**：那 3 类还没写进真页面，
      草图这一侧先把「类清单分两组」的结构画出来，落地在 A 档与 `observe-labels.js` 一起加。 */
const FAC_KINDS = [
  { k: 'slow_sql', label: '慢 SQL', n: 0, grp: 'fac' },
  { k: 'mq', label: 'MQ', n: 0, grp: 'fac' },
  { k: 'scheduler', label: '调度', n: 0, grp: 'fac' },
]
/* 「源」缩成 2 项（同一裁定）：事件流 + 演练轮询。 */
const SOURCES = [
  { k: 'events', label: '事件流', n: events.length, on: true },
  { k: 'drill', label: '演练轮询', n: 0, on: true },
]

/* ── ① / ④ 的读数：错误率与延迟分位（**都由本窗真数据现算**）───────────────────
   ⚠️ 本窗实测：P50 2 · P95 62 · P99 76 ms（n=212，最大 79）—— 分布**不是一条直线**，
      所以「画不出趋势线」这句话**不成立，不许写**（本轮之前草稿里写过「耗时全是 200 ms」，
      那是把 traces 记录的 **HTTP 状态码 200** 当成了耗时，实测当场证伪）。
      ④ 只给**读数**的理由是：这一轮裁定的是「Top-N 榜 + 错误率 / P99 读数」，
      趋势线不在裁定范围内 —— 不是因为它画不出来。 */
const sevAgg = { info: bySev.info || 0, warn: bySev.warn || 0, error: bySev.error || 0, total: events.length }
const latMs = TRACE_REC.filter(Boolean).map((r) => r[2]).sort((a, b) => a - b)
const qtl = (p) => (latMs.length ? latMs[Math.min(latMs.length - 1, Math.floor(p * latMs.length))] : 0)
const lat = {
  n: latMs.length, p50: qtl(0.5), p95: qtl(0.95), p99: qtl(0.99),
  max: latMs.length ? latMs[latMs.length - 1] : 0,
  flat: latMs.length > 1 && latMs.every((v) => v === latMs[0]),
}

/* ── ④ Top-N 榜（三档可切：对象 / 慢请求 / 错误）──────────────────────────────
   ⚠️ 错误榜本窗**恒空**（error 0 条）—— 空态是这一块要说的话之一，**不塞假数据**。 */
const tally = (arr) => { const m = new Map(); arr.forEach((x) => m.set(x, (m.get(x) || 0) + 1)); return m }
const topObj = [...tally(events.map(objText).filter(Boolean)).entries()]
  .sort((a, b) => (b[1] - a[1]) || String(a[0]).localeCompare(String(b[0]))).slice(0, 5)
const byPath = new Map()
TRACE_REC.forEach((r) => {
  if (!r) return
  const key = r[0] + ' ' + r[1]
  const cur = byPath.get(key) || { ms: 0, n: 0 }
  byPath.set(key, { ms: Math.max(cur.ms, r[2]), n: cur.n + 1 })
})
const topSlow = [...byPath.entries()]
  .sort((a, b) => (b[1].ms - a[1].ms) || (b[1].n - a[1].n)).slice(0, 5)
  .map(([key, v]) => [key, v.ms, v.n])
const topErr = chrono.filter((e) => e.severity === 'error').slice(0, 5).map((e) => [e.title, hmsms(e.ts)])

/* ── 判定条（两版各一份；数字全部现算，不写死）────────────────────────────── */
const j = (a) => JSON.stringify(a)
const pct = (n, d) => (d ? Math.round((n / d) * 1000) / 10 : 0) + '%'
const present = KINDS.filter((k) => byKind[k])
const absent = KINDS.filter((k) => !byKind[k])
const sevTxt = SEVERITIES.map((s) => SEVERITY_LABEL[s] + ' ' + (bySev[s] || 0)).join(' · ')
const head =
  '<b>结构判据（本页不是设计稿，是拿来比构图的）</b>' +
  '<ul>' +
  '<li><b>〇 窗口化（本轮的页高口径已改）</b>：整页高度是<b>一个定值</b>（= 视口 × 1.32，1440×900 下 ' +
  ' <b>1188px</b>），内容再多也<b>不涨</b>；六区全落在<b>页内</b>，区与区只用一条 1px 分隔线、不留空白带；' +
  ' <b>事件流在框内自己滚</b>。' +
  ' ⚠️ 上一轮的口径是「六区全落在<b>首个视口</b>内、整页不滚」；本轮你裁定<b>页高留 60–80px 余量</b>' +
  '（上限是视口 × 1.4 = 1260px，取 1188px ⇒ 留 <b>72px</b>），代价是 <b>⑤⑥ 要往下滚一次才看得到</b> ——' +
  ' 这是<b>批准过的</b>取舍，不是回归。' +
  ' 「不随数据长」这条不是靠说的：探针往事件流里<b>临时塞 200 行</b>再比三个数 —— 文档高不变、' +
  ' 事件流可视高不变、事件流的 scrollHeight 变大；只量「有没有滚动条」是不够的（页面自己滚起来也有滚动条）。</li>' +
  '<li><b>③ 事件流 = 控制台行</b>（你 2026-10-16 逐条裁定的主改，本笔的核心）：' +
  ' 每条<b>一行</b>（不是卡片、不是两行）；<b>无复选框、无左侧竖线、无右侧独立列</b> —— traceId 併进 msg 行尾；' +
  ' 等级用<b>文字</b> INFO / WARN / ERROR（不是色块；本窗 error 0 条 ⇒ 只有 INFO 看得见）；' +
  ' 时刻 / 等级 / 类名三列<b>定宽</b>、msg 吃掉剩下的宽；行间 <code>gap = 0</code>，hover 整行变色。' +
  ' 同 trace 用<b>前导符</b>归组（<code>├─</code> / <code>│  ├─</code> / <code>│  └─</code> / <code>└─</code>，' +
  ' 等宽、占 2–3 字），点某行 ⇒ <b>同 trace 整组背景微染</b>（只改背景，不加边框、不加色块）。' +
  ' 滚动条 <b>6–8px</b>（宽度由探针实测，不写常数）。新事件<b>从底部进、旧内容平滑上移</b>：' +
  ' <code>cubic-out</code> 的 rAF 插值 <code>scrollTop</code>、<b>不用 top/height</b>（那会 reflow 掉帧）；' +
  ' 高频追加按 60ms 合成一次动画；手动滚即打断、滚回底部 8px 内自动恢复。' +
  ' ⚠️ 这里有两处是<b>本版改法</b>不是现状：真页面是「新的在上 + <code>pageRows</code> 分页表」且每条两行；' +
  ' 本版改成时间正序、从底部追加、每条一行、DOM 只留最近 <code>' + ROW_MAX + '</code> 行。</li>' +
  '<li><b>③ 时刻列：同拍内按时刻局部排，跨拍按环序</b>（你 2026-10-16 裁定「乙」）。' +
  ' 口径三条：只在<b>同一拍内</b>排 · <b>同 ts 保环序</b>（稳定排序）· <b>跨拍顺序一字不动</b>；' +
  ' 后端 <code>EventBroadcaster</code> 的合并环口径（同拍内是<b>环序</b>、不是时刻序）也一字不动。' +
  ' ⚠️ 排序的<b>单位是「组」不是「行」</b>，这是量出来的：行级排序能把逆序修成 0，' +
  ' 但会<b>打散 29 个组、牵动 174 行</b>（组内本来就递增，逆序全来自「上一组的尾行 vs 下一组的组首」' +
  ' 这种<b>并发重叠</b>）⇒ 与「同 trace 用前导符归组」直接冲突。' +
  ' 落地为<b>组级稳定排序</b>：实测修掉 5 处、位移 ' + blockMoved + ' / ' + blocks.length +
  ' 个组槽位，剩下的 23 处是并发重叠的固有代价，<b>如实记账、不假装修掉了</b>。</li>' +
  '<li><b>③ 行内三种交互（不占列）</b>：hover 整行时浮出「复制本行 / 只看这条 trace / 暂停跟随」三键' +
  '（<b>绝对定位</b>，不占栅格列 —— 占了列就不是 6 列了）；<b>对象可点</b>（点对象 ⇒ 把它填进 ② 的对象筛选并真过滤）；' +
  ' 点行内「展开」⇒ 在<b>本行下面插一行</b>原始字段（插行期间锁住自动跟随，再点收起）。' +
  ' ⚠️ 展开行跟着 ③ 内部滚、<b>不动页高</b>（探针实测）。</li>' +
  '<li><b>① 两排 + 就近（你裁定「不合，但要就近」）</b>：上排读数（健康灯 / 事件 / 速率 / 链路 / <b>错误率</b> / P99 / ' +
  ' 严重度 mini 三色条），下排控件（时间范围 / 导出 CSV·JSON / 密度两档 / MQ 状态点），数据源仍在<b>页头右上</b>、' +
  ' 「已放 N / 500 条」常驻。错误率与严重度分布<b>物理相邻</b>（同排紧挨着）—— 这是那句「可以有个联动」的落点：' +
  ' 调一下距离，L1/L2 的视线路径就是短的。</li>' +
  '<li><b>② 类清单分两组（业务 9 + 设施 3）+ 「源」缩成 2 项</b>：同一套筛选路径、只多一条 1px 分组线 ——' +
  ' 慢 SQL / MQ / 调度是<b>类</b>、不是源（你的裁定）。丙 版 ② <b>去掉了计数列</b>（数字只在右栏出现一次，' +
  ' 不留「—」占位）。⚠️ 设施 3 类本窗 <b>0 条是实话</b>（那 3 个面还没落地，见下一条）——' +
  ' 与既有 9 类里那 5 个 0 条的类<b>同样对待</b>：如实显示 0，<b>不补数、也不置灰</b></li>' +
  '<li><b>④ 加了 Top-N 榜与两个读数</b>：榜三档可切（对象 / 慢请求 / 错误，<b>错误榜恒空</b>，空态写明）；' +
  ' 读数给出错误率与 P50/P95/P99（本窗实测 P50 ' + lat.p50 + ' / P95 ' + lat.p95 + ' / P99 ' + lat.p99 +
  ' ms，n=' + lat.n + '）—— 这一轮裁的就是「榜 + 读数」，<b>趋势线不在裁定范围内</b>（不是画不出来）。' +
  ' 联动三档：<b>L1 必做</b>（点三色条某档 ⇒ 勾 ② 并过滤 ③）、<b>L2 必做</b>（错误率 &gt; 0 ⇒ 脉冲 + 变红）、' +
  ' <b>L3 可选做了</b>（悬停某档 ⇒ ③ 里该档的行短暂高亮）。' +
  ' ⚠️ 本窗 warn/error <b>都是 0</b> ⇒ L1/L2 <b>天然不可达</b>：探针先<b>注入</b>一条 error 再量，' +
  ' 不注入就是假绿（你点名的验收前提）。</li>' +
  '<li><b>⑤ 加了「关联跳转」行</b>（64 → 88px）：从选中号的 traces 记录里取路径推断跳转目标' +
  '（订单 / 商品 / 用户），取不到就写「没有 traces 记录 ⇒ 跳转目标取不到」。' +
  ' ⚠️ ⑤ <b>有选中 / 没选中两种状态下高度相同</b>（探针实测）—— ⑤ 一变高，③ 就被压矮、页底就跳。</li>' +
  '<li><b>设施 3 类背后要补 3 个后端面（A 档，本轮只画结构）</b>：<code>SlowSqlListener</code> 加环形缓冲 + 事件化' +
  '（现在是 <code>log.warn</code> 一行）· MQ 的 publish / consume / DLQ 事件化 · 7 个 <code>@Scheduled</code> 入口各包一层记录器。' +
  ' 两条口径照你的裁定：慢 SQL <b>同模板 5 秒去重 + 每秒封顶 20 条</b>；调度<b>只在有动作 / 失败时记</b>（不是每轮都记，' +
  ' 那两个 15s 的 job 每轮一条就是恒定噪声）。⚠️ 与此同批登记为「升 A 档要处理的前提」：' +
  ' A1 后端聚合端点（本轮仍走前端合并）、A5 后端时序保证、A6 解开单例 <code>sentSeq</code> / <code>FramePacer</code>。</li>' +
  '<li><b>C1 相邻区块不同构</b>：① 一排读数 ／ ② 竖排清单 ／ ③ 滚动日志行 ／ ④ 横条＋读数 ／ ⑤ 有序链 ／ ⑥ 开关排' +
  ' —— 六块六种形态，没有两块共用布局。⚠️ 窗口化之后区块<b>直接相接</b>（间距 0，只隔一条分隔线），' +
  ' 于是「相接就算相邻」——这一轮实测 甲 <b>10 对</b>／丙 <b>9 对</b>相邻。' +
  ' ⚠️ 上一轮是几对<b>没有取证</b>（旧版草图的 DOM 与这版不同，旧判据量不到同一件事），' +
  ' 所以这里<b>不写「从 N 对升到」</b>，只讲为什么更严：相接的每一对都要查，容不下「隔远一点就不算邻居」。' +
  ' 指纹里还必须含<b>直接子元素标签计数</b>，' +
  ' 只用「标签集」时两块不同构的区块会双双算出同一枚指纹而被误判同构。</li>' +
  '<li><b>C3 区块间距 &gt; 区块内间距 —— 本轮撤掉</b>。它有它成立的前提：上一轮区块之间是' +
  ' <code>--gap-block</code> 的<b>空白带</b>，所以「区块之间留白 &gt; 区块之内留白」是一句关于<b>分组</b>的话。' +
  ' 窗口化之后分组由<b>外框</b>表达、区与区之间是一条 <code>1px</code> 分隔线而不是空白，' +
  ' 「间距」这个量在窗口形态里没有对应物 —— 判据不是变松了，是<b>前提没了</b>。' +
  ' 撤掉而不是把 48px 改小凑一个数，是因为留着它只会得到一条永真或永假的断言。' +
  ' （上一轮为它抬过一次 <code>--gap-block</code> 32→48：探针按「盒间空白 + 两侧内边距」量出来是 40px，' +
  ' 而按只算 gap 的口径是 24px。那个 40 是<b>真数</b>，不是量错 —— 记在这里，免得下次有人以为它被量错了。）</li>' +
  '<li><b>各区宽度</b>：<b>实数由探针在 1440 视口量</b>（主区可用宽 = 客户区宽 − 壳侧栏 232 − 主区左右各 32；' +
  ' 滚动条宽实测，不写常数）。⚠️ 这一轮整页不再滚 ⇒ <b>页面级滚动条可能根本不存在</b>（实测 0px），' +
  ' 滚动条改由<b>事件流自己</b>吃掉 —— 宽度账因此与上一轮不同，这正是不许写常数的原因。</li>' +
  '<li><b>13 处动效落点</b>：页面上带 <code>[data-anim]</code> 角标的元素即为落点，逐个数得出 13 个。' +
  ' 其中 1（新行从底部进）/ 2（旧内容上移）/ 5（error 闪红）/ 11（上下文展开）落在<b>同一个列表容器</b>上，' +
  ' 4（计数滚动）/ 7（图例闪）落在<b>同一个数字</b>上 —— 合成逗号列表是刻意的，拆成两个元素反而是假结构。' +
  ' 6（同号高亮）的<b>效果</b>落在 ③ 的行上（微染）、<b>触发</b>在 ⑤，标记留在 ⑤ 那一处。' +
  ' ⚠️ 上一轮是 8 处，本轮加了 5 处、全部来自你这次的裁定：' +
  ' 9（联动 L1：点严重度档 ⇒ 勾上 ② 的档位并<b>真过滤</b> ③）、10（联动 L2：错误率 &gt; 0 ⇒ 读数脉冲两次 + 变红）、' +
  ' 11（上下文展开）、12（④ 榜单切档重排）、13（⑤ 关联跳转）。' +
  ' 一元素一标号，这样「数一数是不是 13 个」才数得清。</li>' +
  '<li><b>三样易漏项</b>：⑤ 的「起止两行只来自 traces ⇒ 模拟日常下恒空」写在 ⑤ 的注里；' +
  ' ③ 的「淘汰卡片时要<b>把它的 seq 也从 seenSeq 摘掉</b>」（现页面 <code>EventLog.vue</code> 里真有这行）' +
  ' 写在 ③ 的表头；「图例 9 类、数字是<b>本页窗口内累计</b>不是帧里 counts」写在 ② 的脚注与 ④ 的标题里。</li>' +
  '</ul>' +
  '<b>本窗实测（真帧 ' + N + ' 拍 · 抓帧期间共 ' + num(winEvents) + ' 条事件 · ' + num(last.droppedTotal) +
  ' 条被覆盖丢失）</b><ul>' +
  /* ⚠️ 两个窗口必须分开写：本页的图例读的是**页面窗口**（最近 1000 条），
     而「整窗」是抓帧期间实际发生过的。两者差得很大 —— 这正是本页口径的后果，不是笔误。 */
  '<li>抓帧期间（整窗）：' + KINDS.map((k) => KIND_LABEL[k] + ' ' + (winByKind[k] || 0)).join(' · ') + '</li>' +
  '<li>页面窗口内（最近 ' + EVENT_CAP + ' 条 · <b>图例读的就是这一行</b>）：' +
    KINDS.map((k) => KIND_LABEL[k] + ' ' + (byKind[k] || 0)).join(' · ') + '</li>' +
  '<li><b>两者对不上的地方就是这页的结构性后果</b>：防护整窗 <b>' + (winByKind.protection || 0) +
    ' 条</b>、页面窗口 <b>' + (byKind.protection || 0) + ' 条</b>；警告整窗 <b>' + (winBySev.warn || 0) +
    ' 条</b>、页面窗口 <b>' + (bySev.warn || 0) + ' 条</b> ——' +
    '缓存一类占了大头，稀有的类会被 <b>1000 条</b>的封顶<b>挤出窗口</b>，页面上从此看不见。' +
    '（如实显示，<b>不补数</b>；这是本步发现的真问题，处置留给你裁。）</li>' +
  '<li>业务 9 类里页面窗口<b>有量 </b>' + present.length + ' 类、<b>0 条 </b>' + absent.length + ' 类：' +
    (absent.length ? absent.map((k) => KIND_LABEL[k]).join(' · ') + ' —— 是负载没走到那几条路径、' +
      '还是那几环没接通，本步<b>不下结论</b>。' : '（无 0 条的类）') +
    '（设施 3 类整类为 0，原因不同：那 3 个后端面还没落地）</li>' +
  '<li>严重度（页面窗口）：' + sevTxt + ' ⇒ <b>error 0 条</b>，「error 卡片闪红」这一处本窗没有可演示的卡片（落点在，见 ③）。</li>' +
  '</ul>'

const judgeJia = head +
  '<b>这一版（甲 · 三栏驾驶舱）的结构主张</b><ul>' +
  '<li>左「选」／中「看」／右「量」，一栏一句话说得清。</li>' +
  '<li>折线是<b>中区顶部的一条横幅</b>（③ 的一部分），不另立一栏。</li>' +
  '<li>12 类计数在 <b>② 过滤栏行尾</b>（图例兼筛选，数字就在开关旁边），并按<b>业务 9 / 设施 3</b> 分组。</li>' +
  '<li>④ 右栏自上而下：<b>Top-N 榜</b>（对象 / 慢请求 / 错误 三档可切）→ 12 类计数 → 严重度分布 → 读数。</li>' +
  '<li>代价：中区被左右两栏夹住 ⇒ 消息列是三版里最窄的（明细先让位、traceId 永不收缩）。</li>' +
  '</ul>'
const judgeBing = head +
  '<b>这一版（丙 · 上下分区）的结构主张</b><ul>' +
  '<li>上半「仪表带」看数、下半「事件流」看流，各自拿满宽。</li>' +
  '<li>仪表带四格<b>不同宽、不同形态</b>：折线（最宽）／ 12 类计数条 ／ 严重度分布＋读数 ／ Top-N 榜。</li>' +
  '<li>12 类计数<b>只出现在仪表带</b>；② 过滤栏因此只留类名与开关状态 —— <b>连计数列都没有</b>' +
  ' <b>（不是画个「—」占位）</b>。<b>同一份数字不在屏幕上出现两次</b>（「严重度 mini 三色条」是<b>比例条</b>，' +
  ' 不是数字，那是 ① 与 ④ 相就近的联动落点，不算重复计数）。</li>' +
  '<li>收益：消息列<b>最宽</b>（时间戳到毫秒、traceId 可见），tail -f 最舒服。</li>' +
  '</ul>'
const judge = { jia: judgeJia, bing: judgeBing }

/* ── 拼 DATA 块（纯字符串拼接，不 eval）──────────────────────────────────── */
const J = (a) => JSON.stringify(a)
const B = []
B.push(BEGIN + ' —— 由 scripts/probes/probe-b7a3-wire-data.mjs 从真帧生成，勿手改 @@ */')
B.push('var DATA = {')
B.push('  /* 来源：' + J(N + ' 拍事件帧 · ' + hms(F[0].ts) + '–' + hms(last.ts)) + ' */')
B.push('  meta: { ticks: ' + N + ', winEvents: ' + winEvents + ', capturedOn: ' + J('2026-10-16') + ',')
B.push('    note: ' + J('窗口内累计按页面口径（absorbEvents/absorbTraces 重放），不是帧里本拍的 counts') + ' },')
B.push('  clock: ' + J(clock) + ',')
B.push('  rowMax: ' + ROW_MAX + ',')
B.push('  stats: ' + J(stats) + ',')
B.push('  rate: ' + J(rate) + ',')
/* 类清单分两组：业务 9（下标 0–8，**行的 `k` 就是这套下标、不许动**）+ 设施 3（下标 9–11）。 */
B.push('  /* 类：业务 9（grp=biz，行里的 k 用这套下标）＋ 设施 3（grp=fac，本窗 0 条）*/')
B.push('  kinds: ' + J(KINDS.map((k) => ({ k: k, label: KIND_LABEL[k], n: byKind[k] || 0, grp: 'biz' }))
  .concat(FAC_KINDS)) + ',')
B.push('  /* 「源」= 2 项（裁定：慢 SQL / MQ / 调度 归入「类」，不单列成源）*/')
B.push('  sources: ' + J(SOURCES) + ',')
B.push('  sevs: ' + J(SEVERITIES.map((s) => ({ s: s, label: SEVERITY_LABEL[s], n: bySev[s] || 0 }))) + ',')
B.push('  /* 错误率与延迟分位：都由本窗真数据现算（错误率 = (warn+error)/窗口内事件）*/')
B.push('  sevAgg: ' + J(sevAgg) + ',')
B.push('  lat: ' + J(lat) + ',')
B.push('  /* Top-N 榜三档：对象 / 慢请求 / 错误（错误榜本窗恒空，不塞假数据）*/')
B.push('  top: ' + J({ obj: topObj, slow: topSlow, err: topErr }) + ',')
B.push('  /* 时刻列口径：rows 已按「同拍内按组首时刻稳定排序」排过（跨拍不动）*/')
B.push('  order: { rule: ' + J('同拍内按时刻局部排，跨拍按环序') + ', blockMoved: ' + blockMoved +
  ', blocks: ' + blocks.length + ', barrier: ' + blockBarrier + ', firstMoved: ' + blockFirstMoved +
  ', invInTick: ' + invInTick + ', invCross: ' + invCross + ', brokenGroups: ' + brokenGroups +
  ', brokenRows: ' + brokenRows + ', grade: ' + J('组级（不是行级）') + ' },')
B.push('  rail: { allOn: true, total: ' + events.length + ' },')
B.push('  /* 九类的真图标（真页面 `KIND_ICON` 原样搬来）：草图走 currentColor，不上类色 */')
B.push('  icons: ' + J(KIND_ICON) + ',')
B.push('  /* 行里那截 traceId 的 8 位前缀表（行里只存下标，省一半字符） */')
B.push('  trIdx: ' + J(TRACE_IX) + ',')
B.push('  /* 紧随 trIdx 下标的 traces 记录：[方法, 路径, 耗时ms, 状态, 起, 止]；null = 这一号在本窗查不到记录 */')
B.push('  trRec: ' + J(TRACE_REC) + ',')
B.push('  /* 落点 6 标在第几行（⑤ 默认选中的那一号所在组的第一行） */')
B.push('  anim6: ' + anim6Row + ',')
B.push('  sel: { tr: ' + selTR + ', traceId: ' + J(pick) + ' },')
/* 重放的切段点：cuts[t] = 「拍序 ≤ t」的行数 ⇒ 重放第 t 拍 append rows[at : cuts[t]]，
   每拍喂进去的条数就是真帧那一拍的条数（⚠️ 「顺延」的组会把成员提前带过来，
   所以段边界取的是「≤ 本拍」的前缀，段内顺序仍按行清单，总行数逐拍对齐）。 */
const cuts = []
for (let t = 0; t < N; t++) cuts.push(rows.filter((r) => r.t <= t).length)
B.push('  /* 重放切段：cuts[t] = 「拍序 ≤ t」的行数（每拍喂真帧那一拍的条数）*/')
B.push('  cuts: ' + J(cuts) + ',')
/* ⚠️ 本窗**前 38 拍真的一拍 0 条**（采集开始时应用还没热起来）—— 实测过，不是推断。
   重放若老实从第 0 拍起，点一下 ▶ 要空等 38 秒才见第一行；所以重放从
   「第一拍有事件」处起跳，并把这件事写在 ③ 的说明里（不含糊其辞）。 */
const replayHead = cuts.findIndex((c) => c > 0)
B.push('  /* 重放起跳拍：前 ' + replayHead + ' 拍本窗 0 条 ⇒ 从第 ' + (replayHead + 1) + ' 拍起喂 */')
B.push('  replayHead: ' + replayHead + ',')
B.push('  /* ③ 的行清单：[前导符, 时刻, 严重度(i/w/e), 类下标(-1=req), 标题, 对象, 明细, trace下标, 拍序] */')
B.push('  rows: [')
rows.forEach((r, i) => B.push('    ' + J([r.pre, r.ts, r.lv, r.k, r.title, r.obj, r.detail, r.tr, r.t]) +
  (i < rows.length - 1 ? ',' : '')))
B.push('  ],')
B.push('  judge: { jia: ' + J(judge.jia) + ',')
B.push('    bing: ' + J(judge.bing) + ' }')
B.push('}')
const block = B.join('\n') + '\n' + END + ' 勿手改 @@ */\n'

const before = fs.readFileSync(WIRE, 'utf8')
const si = before.indexOf(BEGIN), ei = before.indexOf(END)
if (si < 0 || ei < 0) { console.error('⛔ 找不到 ' + BEGIN + ' / ' + END + ' 标记，未改动'); process.exit(1) }
const rest = before.slice(before.indexOf('\n', ei) + 1)
const after = before.slice(0, si) + block + rest
fs.writeFileSync(WIRE, after)

const h12 = (s) => createHash('sha1').update(s).digest('hex').slice(0, 12)
console.log('已写回 ' + WIRE)
/* 幂等要**打印出来**才算证据：写前写后同一哈希 = 这一跑没改文件（重跑即如此）。 */
console.log('幂等：写前 ' + h12(before) + ' · 写后 ' + h12(after) +
  (h12(before) === h12(after) ? ' ✓ 未改动' : ' ✗ 本次有改动（重跑一次应显示 ✓）'))
console.log('\n帧 ' + N + ' · 窗口内事件 ' + events.length + '（上限 ' + EVENT_CAP + '）· 窗口内链路 ' +
  traceList.length + '（上限 ' + TRACE_CAP + '）· 整窗事件 ' + winEvents)
console.log('本窗 9 类：' + KINDS.map((k) => KIND_LABEL[k] + ' ' + (winByKind[k] || 0)).join(' · '))
console.log('页面窗口内 9 类：' + KINDS.map((k) => KIND_LABEL[k] + ' ' + (byKind[k] || 0)).join(' · '))
console.log('严重度：' + SEVERITIES.map((s) => SEVERITY_LABEL[s] + ' ' + (bySev[s] || 0)).join(' · '))
console.log('错误率：' + (((sevAgg.warn + sevAgg.error) / (sevAgg.total || 1)) * 100).toFixed(1) + '%（warn ' +
  sevAgg.warn + ' + error ' + sevAgg.error + ' / ' + sevAgg.total + ' 条）—— 全 0 ⇒ 联动 L1/L2 天然不可达，探针必须先注入')
console.log('延迟：P50 ' + lat.p50 + ' · P95 ' + lat.p95 + ' · P99 ' + lat.p99 + ' ms（n=' + lat.n +
  (lat.flat ? '，耗时全等 ⇒ 趋势线画不出来，只报读数' : '') + '）')
console.log('Top-N：对象 ' + topObj.map((x) => x[0] + '×' + x[1]).join(' · ') + ' ｜ 慢请求 ' +
  topSlow.map((x) => x[0] + ' ' + x[1] + 'ms×' + x[2]).join(' · ') + ' ｜ 错误 ' + topErr.length + ' 条（本窗恒空）')
console.log('时刻列：同拍内按组首时刻【组级稳定排序】⇒ 位移 ' + blockMoved + ' / ' + blocks.length + ' 个组槽位' +
  (blockFirstMoved >= 0 ? '（首个位移在 #' + blockFirstMoved + '）' : '（恒等变换）') + ' · 跨拍组当隔断 ' + blockBarrier + ' 个' +
  (blockBarrierInfo.length ? '：' + blockBarrierInfo.join(' · ') : ''))
console.log('时刻列残留：同拍内行级逆序 ' + invInTick + ' 处（全是并发重叠：上一组尾行 vs 下一组组首）· 跨拍逆序 ' +
  invCross + ' 处（跨拍不动 ⇒ 留着）· 改用行级排序会打散 ' + brokenGroups + ' 个组 / 牵动 ' + brokenRows + ' 行 ⇒ 不修')
console.log('类清单：业务 ' + KINDS.length + ' + 设施 ' + FAC_KINDS.length + ' = ' + (KINDS.length + FAC_KINDS.length) +
  ' 类（设施 3 类本窗 0 条 = 那 3 个后端面还没落地）· 「源」' + SOURCES.length + ' 项（' +
  SOURCES.map((s) => s.label).join(' / ') + '）')
console.log('速率 ' + rate[0].toFixed(1) + ' → ' + clock.rate.toFixed(1) + ' 条/秒（峰值 ' + rateMax.toFixed(1) + '）')
console.log('⑤ 默认选中 tr=' + selTR + '（' + (pick ? pick.slice(0, 8) : '（无）') + '）· 链上 ' + chain.length + ' 条 · ' +
  (rec ? rec.method + ' ' + rec.path + ' ' + rec.status + ' ' + rec.ms + 'ms' : '（窗口内没有它的 traces 记录）'))
console.log('日志行 ' + rows.length + ' 行 = 事件 ' + events.length + ' 行 + 组内起止/子行多出的 ' +
  (rows.length - events.length) + ' 行（包了 ' + nGroups + ' 组）')
console.log('归组口径：事件数 ≥2 且 traces 里有记录才包 ⇒ 单条事件的 trace 走顶格行')
console.log('traces 记录覆盖：' + TRACE_IX.length + ' 个号里 ' + nRecCovered + ' 个查得到（traces 窗口 ' + TRACE_CAP +
  ' ≪ 事件窗口 ' + EVENT_CAP + ' ⇒ 老号查不到，⑤ 会自己说明）')
console.log('末 ' + ROW_MAX + ' 行（草图上真正画出来的）里画出了 ' + tailGroups +
  ' 组 ⇒ 点组内行染一整组、点顶格行只染它自己那一行（不是漏染）')
console.log('行数账：整窗 1938 个 trace 里 1476 个只有 1 条事件 —— 若每个都包起止，请求行会占屏幕一半')
console.log('顺延：' + nonContig + ' 组的成员原本不连续（它们被并到组首，中间的行走到了后面）')
console.log('重放切段：cuts 长度 ' + cuts.length + ' · 末拍累计 ' + cuts[cuts.length - 1] + '（应 = 行清单 ' +
  rows.length + '）· 每拍最多 ' + Math.max.apply(null, cuts.map((c, i) => c - (i ? cuts[i - 1] : 0))) + ' 行')
console.log('重放起跳：前 ' + replayHead + ' 拍本窗 0 条（实测 cuts[0..' + (replayHead - 1) + '] 全 0）⇒ 重放从第 ' +
  (replayHead + 1) + ' 拍起，第 1 拍就喂 ' + (cuts[replayHead] - (replayHead ? cuts[replayHead - 1] : 0)) + ' 行')
console.log('同号高亮落点可见：' + (highlightVisible ? '是（在 ' + rows.findIndex((r) => r.anim === 6) + ' 行' +
  '，共 ' + rows.length + ' 行 ⇒ 在末 ' + ROW_MAX + ' 行内）' : '否 —— 要重挑号'))
console.log('落点标注 [data-anim] 共 13：3（折线）4/7（计数·图例闪）1/2/5/11（列表）8（断线条）6（同号高亮）' +
  '9（联动 L1）10（联动 L2）12（榜单切档）13（⑤ 关联跳转）')
