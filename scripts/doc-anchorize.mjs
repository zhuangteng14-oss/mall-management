#!/usr/bin/env node
// ═══════════════════════════════════════════════════════════════════════════
// doc-anchorize.mjs —— 文档锚点式改造（把底座文档的 :行号 引用换成 #锚点）
//
// 范围（方案 A，2026-10-11 用户批）：
//   · 加锚点：docs/agent/db-schema.md、api-contract.md、context-packs/*.md（活文档）
//   · 替换  ：target ∈ 上述三类 且 source ∉ 账本(progress/slot-registry/tech-debt)/档案 的引用
//   · 不动  ：账本内部历史引用、档案、指向代码/稿的行号
//
// 用法：
//   node scripts/doc-anchorize.mjs                 # dry-run：只出表，不落盘
//   node scripts/doc-anchorize.mjs --apply         # 落盘：先写锚点，再替换引用
//
// 产物（dry-run / apply 都写）：
//   _work/anchor-map.txt     —— 标题 → 锚点 全表（供人核命名）
//   _work/anchor-replace.txt —— 引用替换 before/after 全表（供人核语义）
// ═══════════════════════════════════════════════════════════════════════════
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..')
const APPLY = process.argv.includes('--apply')
const WORK = path.join(ROOT, '_work')
fs.mkdirSync(WORK, { recursive: true })

const SCAN = path.join(WORK, 'anchor-baseline.txt')   // refs-scan.sh 的产物

// ── 活文档目标（相对 ROOT）────────────────────────────────────────────────
const TARGETS = [
  { file: 'docs/agent/db-schema.md',     prefix: 'db' },
  { file: 'docs/agent/api-contract.md',  prefix: 'api' },
]
const PACK_DIR = 'docs/agent/context-packs'
for (const f of fs.readdirSync(path.join(ROOT, PACK_DIR))) {
  if (f.endsWith('.md')) TARGETS.push({ file: `${PACK_DIR}/${f}`, prefix: 'pack-' + f.replace(/\.md$/, '') })
}

// ── 账本 / 档案（source 侧跳过）────────────────────────────────────────────
const LEDGERS = ['progress.md', 'slot-registry.md', 'tech-debt.md']
const isLedger = p => LEDGERS.some(l => p.endsWith(l))
const isArchive = p => p.includes('/archive/') || p.includes('/evidence/')

// ── 读目标文档，fence-aware 解析标题（\r 归一）─────────────────────────────
function parseHeadings(abs) {
  const t = fs.readFileSync(abs, 'utf8').split('\n')   // 保留 \r（CRLF 文件写出时不改行尾）
  let fence = false
  const out = []
  for (let i = 0; i < t.length; i++) {
    const L = t[i].replace(/\r$/, '')
    if (/^\s*(```|~~~)/.test(L)) { fence = !fence; continue }
    if (fence) continue
    const m = L.match(/^(#{1,6})\s+(.*\S)/)
    if (m) out.push({ line: i + 1, lvl: m[1].length, text: m[2] })
  }
  return { lines: t, headings: out }
}

// ── 标题 → slug（取稳定编号部分，散文改了也不动）──────────────────────────
// 手工命名表：doc:line → 锚点（仅用于自动规则给不出好名字的标题）
const OVERRIDES = {
  'docs/agent/db-schema.md:1': 'top',
  'docs/agent/api-contract.md:1': 'top',
  'docs/agent/context-packs/three-layers.md:39': 'must-read',
  'docs/agent/context-packs/three-layers.md:46': 'constraints',
  'docs/agent/context-packs/three-layers.md:274': 'to-verify',
  'docs/agent/context-packs/c3-block-1-infra.md:70': 'log-precedent',
  'docs/agent/context-packs/c3-block-1-infra.md:88': 'frontend-nav',
  'docs/agent/context-packs/c3-block-2-user.md:35': 'block1-reuse',
  'docs/agent/context-packs/concurrency-test.md:64': 'start-verify',
  'docs/agent/context-packs/concurrency-test.md:178': 'acceptance',
  'docs/agent/context-packs/concurrency-test.md:251': 'ci-integration',
  'docs/agent/context-packs/cache-layer.md:319': '9-1',
  'docs/agent/context-packs/cache-layer.md:331': '9-1-blind',
}

function slugOf(text, idx) {
  let m = text.match(/^§\s*(\d+(?:\.\d+)*)/)          // §9.1 附 → 9-1-app（避免与正文 §N 同号）
  if (m) return m[1].replace(/\./g, '-') + '-app'
  m = text.match(/^(\d+(?:\.\d+)*)/)                  // 6.8 → 6-8
  if (m) return m[1].replace(/\./g, '-')
  m = text.match(/^([A-Za-z])\.(\d+(?:\.\d+)*)/)      // C.3 → c-3
  if (m) return m[1].toLowerCase() + '-' + m[2].replace(/\./g, '-')
  m = text.match(/^([A-Za-z])\.\s/)                   // B. → b
  if (m) return m[1].toLowerCase()
  m = text.match(/^附录\s*([A-Za-z])/)                 // 附录 D → app-d
  if (m) return 'app-' + m[1].toLowerCase()
  m = text.match(/^([A-Za-z])(?=\s|$)/)               // 单字母
  if (m) return m[1].toLowerCase()
  // 无编号：取首个 ASCII 词（如 /user → user），否则顺序号
  m = text.match(/[A-Za-z][A-Za-z0-9_-]+/)
  if (m) return m[0].toLowerCase()
  return 's' + (idx + 1)
}

const map = []           // { doc, line, anchor, heading }  —— 全量标题表
const collisions = []    // 真重号（自动消歧）—— 供人工改 override
const docs = new Map()   // file → { abs, lines, headings:[{line,lvl,text,anchor}] }
for (const T of TARGETS) {
  const abs = path.join(ROOT, T.file)
  const { lines, headings } = parseHeadings(abs)
  const used = new Map()
  for (let i = 0; i < headings.length; i++) {
    const h = headings[i]
    const ov = OVERRIDES[`${T.file}:${h.line}`]
    let a = `${T.prefix}-${ov || slugOf(h.text, i)}`
    // 文档内查重：重复追加 -2/-3…
    if (used.has(a)) {
      let n = (used.get(a) || 1) + 1; used.set(a, n)
      collisions.push(`${T.file}:${h.line}  {#${a}} → {#${a}-${n}}  标题: ${h.text}`)
      a = `${a}-${n}`
    } else used.set(a, 1)
    h.anchor = a
    map.push({ doc: T.file, line: h.line, anchor: a, heading: h.text })
  }
  docs.set(T.file, { abs, lines, headings })
}

// 标题里已存在的手写锚点 {#x} —— 尊重，不覆盖（本次新笔，预期为 0）
const existing = [...docs.values()].flatMap(d => d.headings.filter(h => /\{#/.test(h.text))).length

// ── 行号 → 锚点：找 line 之前最近的标题 ────────────────────────────────────
function anchorAt(docFile, line) {
  const d = docs.get(docFile)
  if (!d) return null
  let best = null
  for (const h of d.headings) { if (h.line <= line) best = h; else break }
  return best ? best.anchor : null
}

// ── 解析引用所在文件是否命中活文档目标 ─────────────────────────────────────
// token 里可能带路径前缀（如 docs/agent/api-contract.md:267）或只有后缀（api-contract.md:267）
function resolveTarget(token) {
  const m = token.match(/^(.*?):(\d+)(?:-\d+)?$/)
  if (!m) return null
  const nameOrPath = m[1], line = +m[2]
  // 先按完整后缀匹配
  for (const T of TARGETS) {
    if (nameOrPath === T.file || nameOrPath.endsWith('/' + T.file) || T.file.endsWith('/' + nameOrPath)) {
      return { doc: T.file, line }
    }
  }
  return null
}

// ── 裸续引转换表（人工核定）────────────────────────────────────────────────
// refs-scan 对「同行裸续引」的归属是**启发式**：同一行里常混着
//   (a) 活引用  —— 该转锚点（如 `api-contract.md#api-goods` / `:137`）
//   (b) 历史注记 —— 「原 `:314`」这类，按新规则冻结、不动
//   (c) 代码文件引用 —— 归属被误判到文档上（如 LoginPage 的 `:216`），保留行号
// 故只对**人工逐条核过**的下列活引用做转换（num 出现的第 nth 次，默认首次）。
const BARE = [
  { src: 'Mall-management-server/src/main/resources/application.properties', line: 15,  num: '148', repl: 'three-layers.md#pack-three-layers-d-4' },
  { src: 'docs/agent/context-packs/c3-block-3-goods.md',                      line: 125, num: '183', repl: 'docs/agent/db-schema.md#db-6-3' },
  { src: 'docs/agent/context-packs/c3-block-4-order.md',                      line: 122, num: '289', repl: 'docs/agent/db-schema.md#db-6-12' },
  { src: 'docs/agent/context-packs/three-layers.md',                          line: 188, num: '137', repl: 'api-contract.md#api-health', nth: 1 },
  { src: 'docs/agent/defect-survey.md',                                       line: 212, num: '448', repl: 'cache-layer.md#pack-cache-layer-11' },
  { src: 'docs/agent/selection-status.md',                                    line: 126, num: '135', repl: 'three-layers.md#pack-three-layers-d-1' },
  { src: 'docs/agent/selection-status.md',                                    line: 100, num: '282', repl: 'db-schema.md#db-6-11' },
  { src: 'docs/agent/selection-status.md',                                    line: 140, num: '524', repl: 'db-schema.md#db-10-2' },
  { src: 'docs/agent/selection-status.md',                                    line: 145, num: '328', repl: 'cache-layer.md#pack-cache-layer-9-1' },
  { src: 'docs/agent/selection-status.md',                                    line: 146, num: '434', repl: 'cache-layer.md#pack-cache-layer-11' },
]

// ── 扫 refs-scan 产物，算替换集 ────────────────────────────────────────────
const scanLines = fs.readFileSync(SCAN, 'utf8').split('\n').filter(Boolean)
const repl = []    // { src, srcline, oldTok, newTok }
const skippedBare = []
for (const L of scanLines) {
  const m = L.match(/^(.*?):(\d+):(.*)$/)
  if (!m) continue
  const src = m[1], srcline = +m[2], tok = m[3]
  if (isArchive(src) || isLedger(src)) continue
  const t = resolveTarget(tok)
  if (!t) continue
  const a = anchorAt(t.doc, t.line)
  if (!a) { skippedBare.push(`${src}:${srcline}:${tok} (无覆盖标题)`); continue }
  // 保留原 token 的路径写法，只把 :行号(-范围) 换成 #anchor
  const newTok = tok.replace(/:\d+(?:-\d+)?$/, '#' + a)
  repl.push({ src, srcline, oldTok: tok, newTok, doc: t.doc, line: t.line, anchor: a })
}

// ── 出表 ───────────────────────────────────────────────────────────────────
fs.writeFileSync(path.join(WORK, 'anchor-map.txt'),
  map.map(r => `${r.doc}:${r.line}\t{#${r.anchor}}\t${r.heading}`).join('\n') + '\n', 'utf8')
fs.writeFileSync(path.join(WORK, 'anchor-replace.txt'),
  repl.map(r => `${r.src}:${r.srcline}\t${r.oldTok}\t→\t${r.newTok}`).join('\n') + '\n', 'utf8')

console.log(`目标活文档 ${TARGETS.length} 份，标题 ${map.length} 个，已带手写锚点 ${existing} 个`)
console.log(`可替换引用 ${repl.length} 条，来源文件 ${new Set(repl.map(r => r.src)).size} 个`)
if (skippedBare.length) console.log(`跳过（无覆盖标题）${skippedBare.length} 条:\n  ` + skippedBare.join('\n  '))
if (collisions.length) console.log(`真重号（自动消歧，建议改 override）${collisions.length} 条:\n  ` + collisions.join('\n  '))
else console.log('真重号 0 条')
console.log(`表 → _work/anchor-map.txt, _work/anchor-replace.txt`)

if (!APPLY) { console.log('（dry-run，未落盘；加 --apply 落盘）'); process.exit(0) }

// ── 落盘：① 标题加锚点 ② 替换引用 ──────────────────────────────────────────
for (const [docFile, d] of docs) {
  const out = d.lines.slice()
  for (const h of d.headings) {
    if (/\{#/.test(h.text)) continue
    const eol = out[h.line - 1].endsWith('\r') ? '\r' : ''   // 保持原行尾（CRLF 文件别被改成 LF）
    out[h.line - 1] = out[h.line - 1].replace(/\s*$/, '') + ` {#${h.anchor}}` + eol
  }
  fs.writeFileSync(d.abs, out.join('\n'), 'utf8')
}
const byFile = new Map()
for (const r of repl) { if (!byFile.has(r.src)) byFile.set(r.src, []); byFile.get(r.src).push(r) }
const bareByFile = new Map()
for (const b of BARE) { if (!bareByFile.has(b.src)) bareByFile.set(b.src, []); bareByFile.get(b.src).push(b) }
let doneNamed = 0, skippedDone = 0, doneBare = 0, missedBare = 0, skippedBareDone = 0
for (const src of new Set([...byFile.keys(), ...bareByFile.keys()])) {
  const abs = path.join(ROOT, src)
  const lines = fs.readFileSync(abs, 'utf8').split('\n')
  for (const r of (byFile.get(src) || [])) {
    const i = r.srcline - 1
    if (!lines[i].includes(r.oldTok)) {
      if (lines[i].includes(r.newTok)) { skippedDone++; continue }   // 幂等：已换过
      console.error(`!! 未命中: ${src}:${r.srcline} 找 ${r.oldTok}`); continue
    }
    lines[i] = lines[i].split(r.oldTok).join(r.newTok); doneNamed++
  }
  for (const b of (bareByFile.get(src) || [])) {
    const i = b.line - 1
    if (lines[i].includes(b.repl)) { skippedBareDone++; continue }   // 幂等：已换过
    const re = new RegExp(`(?<![0-9]):${b.num}`, 'g')
    let n = 0, hit = false
    lines[i] = lines[i].replace(re, m => { n++; return n === (b.nth || 1) ? (hit = true, b.repl) : m })
    if (!hit) { missedBare++; console.error(`!! 裸续引未命中: ${src}:${b.line} 找 :${b.num}(第${b.nth || 1}次)`) } else doneBare++
  }
  fs.writeFileSync(abs, lines.join('\n'), 'utf8')
}
console.log(`已落盘：${docs.size} 份活文档加锚点；具名引用 新换 ${doneNamed} / 已换跳过 ${skippedDone}；裸续引 新换 ${doneBare} / 已换跳过 ${skippedBareDone}${missedBare ? ' / 未命中 ' + missedBare : ''}`)
