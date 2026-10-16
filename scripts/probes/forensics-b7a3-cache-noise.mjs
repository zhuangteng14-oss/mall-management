// 取证：**缓存噪声到底占多少 / 为什么天生不能嵌套**（用户 2026-10-16 方案 C 丁：
// 「不治理 + 挂一句备忘」，本脚本是那句话背后**可复核的数**的来源，不是它的替代品）。
//
// 用法（在仓根跑）：
//   node scripts/probes/forensics-b7a3-cache-noise.mjs [输入.ndjson] [输出.txt]
// 默认：输入 = `docs/agent/evidence/c3-block7/b7a3-events-60tick.ndjson`（**已入库**的 60 拍真帧）
//       输出 = `docs/agent/evidence/c3-block7/b7a3-cache-noise.txt`（本节结论的证据文件）
// ⚠️ 输入是**入库**的那份，不是 `_work/` 里的临时副本（两份逐字节相同，`cmp` 验过）——
//    这样这份证据换个时间点还能原地复现，不依赖临时区还在不在。
//
// ⚠️ 口径**刻意对齐页面**：不是「把 60 拍的 events 拼起来看」，而是**复刻 ③ 的取数链** ——
//    `absorbEvents()`（按 seq 去重 → 本帧内按 ts/seq 倒序 → 整批前插 → 封顶 1000）
//    → `ranged`（本处不设时间范围 = 全量）
//    → `filtered.slice(0, ROW_MAX).reverse()`（= `streamRows`）。
//    只有这样，"连续同 trace 的 run 分布" 才是**用户屏幕上那 200 行的**分布。
//
// ⚠️ 本脚本**不写任何「文件:行号」**（那是会随编辑漂移的另一类账）。
import fs from 'node:fs'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目/'
const SRC = process.argv[2] || `${ROOT}docs/agent/evidence/c3-block7/b7a3-events-60tick.ndjson`
const OUT = process.argv[3] || `${ROOT}docs/agent/evidence/c3-block7/b7a3-cache-noise.txt`

const EVENT_CAP = 1000
const TRACE_CAP = 300
const ROW_MAX = 200

const frames = fs.readFileSync(SRC, 'utf8').trim().split(/\r?\n/).map((l) => JSON.parse(l))

// ── 复刻 absorbEvents()：去重 → 帧内倒序 → 前插 → 封顶 ─────────────────────────
const seen = new Set()
let events = []          // 新在前
let dropped = 0
for (const f of frames) {
  const fresh = []
  for (const raw of f.events || []) {
    const s = Number(raw.seq)
    if (seen.has(s)) continue
    seen.add(s)
    fresh.push({ ...raw, seq: s, traceId: raw.traceId == null ? '' : String(raw.traceId) })
  }
  if (!fresh.length) continue
  fresh.sort((a, b) => b.ts - a.ts || b.seq - a.seq)
  const kept = events.slice(0, Math.max(0, EVENT_CAP - fresh.length))
  dropped += events.slice(Math.max(0, EVENT_CAP - fresh.length)).length
  events = fresh.concat(kept)
  for (const e of events) seen.add(e.seq)
}
// ── 复刻 tracesById（按 traceId 索引，后来的覆盖先前的）────────────────────────
const tracesById = new Map()
for (const f of frames) for (const t of f.traces || []) tracesById.set(String(t.traceId), t)

// ── 复刻 streamRows：取窗口最前 200 条，翻成时间正序 ──────────────────────────
const rows = events.slice(0, ROW_MAX).reverse()

const byKind = {}
for (const e of rows) byKind[e.kind] = (byKind[e.kind] || 0) + 1
const allByKind = {}
for (const e of events) allByKind[e.kind] = (allByKind[e.kind] || 0) + 1

// trace 出现次数分布（在 ③ 这 200 行里）
const multi = new Map()
for (const e of rows) if (e.traceId) multi.set(e.traceId, (multi.get(e.traceId) || 0) + 1)
const spread = {}
for (const n of multi.values()) {
  const k = n >= 4 ? '>=4' : String(n)
  spread[k] = (spread[k] || 0) + 1
}

// 连续 run：与 buildStream 的切法**逐字同源**（同一个 traceId 且**相邻**才算一段）
const runs = []
let i = 0
while (i < rows.length) {
  const tid = rows[i].traceId
  let j = i + 1
  while (tid && j < rows.length && rows[j].traceId === tid) j++
  runs.push({ tid, len: j - i })
  i = j
}
const runLen = {}
for (const r of runs) {
  const k = r.len >= 3 ? '>=3' : String(r.len)
  runLen[k] = (runLen[k] || 0) + 1
}
const grouped = runs.filter((r) => r.tid && r.len >= 2)                       // 新版判据：连续 ≥2 ⇒ 成组
const groupedWithRec = grouped.filter((r) => tracesById.has(r.tid))           // 旧版判据：还要有收尾记录
const rowsFlat = rows.filter((e) => !e.traceId).length
const uniqTid = new Set(rows.filter((e) => e.traceId).map((e) => e.traceId)).size
const uniqTidAll = new Set(events.filter((e) => e.traceId).map((e) => e.traceId)).size

const L = []
const p = (s = '') => L.push(s)
p('块 7 · 3a.3 · ③ 事件流「缓存噪声」取证（方案 C 丁：不治理 + 备忘）')
p('='.repeat(78))
p()
p(`输入：60 拍真后端帧（dev profile，8088）逐行 JSON`)
p(`来源文件：${SRC.replace(ROOT, '')}（已入库）`)
p(`复现：node scripts/probes/forensics-b7a3-cache-noise.mjs`)
p(`取数链：复刻 absorbEvents() → ranged(无窗口) → slice(0,${ROW_MAX}).reverse()  = streamRows`)
p()
p('── ① ③ 这 200 行的类分布（= 用户截图里看到的那批）─────────────────')
for (const [k, v] of Object.entries(byKind).sort((a, b) => b[1] - a[1])) {
  p(`   ${k.padEnd(12)} ${String(v).padStart(4)}  取整页占比 ${(v / rows.length * 100).toFixed(1)}%`)
}
p(`   （全量累积 ${events.length} 条，封顶 ${EVENT_CAP}，本窗丢弃 ${dropped} 条）`)
p()
p('── ② traceId 重复度（在 ③ 这 200 行里）────────────────────────────')
p(`   行数 ${rows.length} · 不同的 traceId ${uniqTid} 个 · 其中没有 traceId 的行 ${rowsFlat} 条`)
p(`   ${JSON.stringify(spread)}`)
p('   读法：值为 1 的那一档 = 「整个 200 行窗口里只出现这一次」的 traceId 个数。')
p()
p('── ③ 连续 run 长度分布（buildStream 的切法逐字同源）────────────────')
p(`   ${JSON.stringify(runLen)}`)
p(`   连续 ≥2 ⇒ 成组：**${grouped.length}** 段`)
p(`   其中还查得到收尾记录（旧判据的第二道闸）：**${groupedWithRec.length}** 段`)
p(`   ⇒ 旧判据把成组率砍掉了 ${grouped.length ? (100 - groupedWithRec.length / grouped.length * 100).toFixed(0) : '—'}%`)
p()
// ── ③b 逐拍演进：**这一节才是旧判据的实测**────────────────────────────────
// ⚠️ 只有「收尾时刻」那一张快照是不够的：60 拍跑完之后，所有请求都已返回、记录都已写到，
//    在那张快照上量「有记录的比例」必然是 100% —— 那是**稳态**，而 ③ 是 tail -f，
//    用户看到的顶上是**刚发生、请求还没返回**的行。所以必须按时间轴**一帧一帧地**量。
p('── ③b 逐拍演进（把帧按时间顺序一帧帧喂进去，每 5 帧量一次）────────────')
p('   拍  ③行数  连续≥2段  其中有收尾记录  旧判据下能成组  差值')
const seen2 = new Set()
let ev2 = []
let tr2 = new Map()
for (let k = 0; k < frames.length; k++) {
  const f = frames[k]
  const fresh = []
  for (const raw of f.events || []) {
    const s = Number(raw.seq)
    if (seen2.has(s)) continue
    seen2.add(s)
    fresh.push({ ...raw, seq: s, traceId: raw.traceId == null ? '' : String(raw.traceId) })
  }
  if (fresh.length) {
    fresh.sort((a, b) => b.ts - a.ts || b.seq - a.seq)
    ev2 = fresh.concat(ev2.slice(0, Math.max(0, EVENT_CAP - fresh.length)))
  }
  for (const t of f.traces || []) tr2.set(String(t.traceId), t)
  if ((k + 1) % 5) continue
  const rw = ev2.slice(0, ROW_MAX).reverse()
  const rr = []
  let x = 0
  while (x < rw.length) {
    const tid = rw[x].traceId
    let y = x + 1
    while (tid && y < rw.length && rw[y].traceId === tid) y++
    rr.push({ tid, len: y - x })
    x = y
  }
  const g = rr.filter((r) => r.tid && r.len >= 2)
  const gw = g.filter((r) => tr2.has(r.tid))
  p(`   ${String(k + 1).padStart(3)}  ${String(rw.length).padStart(6)}  ${String(g.length).padStart(8)}  ${String(gw.length).padStart(14)}  ${String(gw.length).padStart(14)}  ${String(g.length - gw.length).padStart(4)}`)
}
p('   读法：最后一列的「差值」= 旧判据在**那一拍**上丢掉的分组数。')
p('   ⚠️ 差值恒 0 ⇒ 旧判据在本窗口内**没有**抑制成组（不是「修了一个大 bug」）；')
p('      演进里出现非 0 ⇒ 它在**流式**过程中确实压过（新行先到、收尾记录后到）。')
p()
p('── ④ 结论 ──────────────────────────────────────────────────────────')
p(`   · 缓存类在 ③ 占绝对多数（${byKind.cache || 0}/${rows.length}），而它的 traceId 几乎条条不同`)
p(`     ⇒ 连续同 trace 的 run 长度绝大多数是 1，**天生无法嵌套** —— 不是树形画错了。`)
p(`   · 全量 ${events.length} 条里不同的 traceId 只有 ${uniqTidAll} 个`)
p(`     ⇒ 一批攻击模拟器打出来的写入各自成了独立请求，不是一次请求的多步。`)
p(`   · 若**日常**也出现「缓存类刷屏」，再回来治理（折叠 / 降级 / 隐藏）；`)
p(`     治理前必须先看这一份分布 —— 判据是「run 长度分布」而不是「缓存条数」。`)
p()
p(`生成时刻（本机）：${new Date().toISOString()}`)
p('⚠️ 本文件不含任何「文件:行号」引用（引用会随编辑漂移，是另一类账）。')

const text = L.join('\n') + '\n'
if (OUT) fs.writeFileSync(OUT, text)
process.stdout.write(text)
