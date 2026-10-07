#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# refs-scan.sh —— 全仓「文件:行号」互引扫描器
#
# 用途：把仓库里所有形如 `<文件>:<行号>` 的**互引**抓出来，产出 before/after 两份
#       可逐行 diff 的清单 —— 用来验证一次改动有没有让互引漂移。
#
# 为什么要它：本仓的成文纪律是「任何事实都要带 `文件:行号`」，于是文档之间、注释之间
#       互相引用行号。任何一处增删行，都会让其后的引用集体偏移。BRIEF-落码 §C3-5 已
#       实测：仓库里**原本没有**这个脚本，历史上的 before/after 是**一次性手工 grep**，
#       无法复跑 ⇒ 落码后无法验证漂移。
#
# 三种口径（全收）：
#   ① `文件:行号`        —— 有名目标，如 `HomePage.vue:119`、`docs/agent/progress.md:401`
#   ② 同行裸写多行号      —— 同行的续引，如 （`HomePage.vue:4` / `:135` / `:144` / `:165`）
#                           ⇒ `:135` / `:144` / `:165` 归属**本行最近的那个有名目标**
#   ③ 区间               —— `HomePage.vue:195-197`、`api/auth.js:99-106`、`G:51-65`
#
# 扫描范围（可改 ROOTS，见下）：
#   Mall-management-web/mall-management-system/src  +  Mall-management-server/src  +  docs
#
# 输出格式（每行一条，已排序，**无表头** —— 便于 `diff before after`）：
#       <引用所在文件>:<引用所在行>:<原始 token>
#   例：docs/agent/progress.md:401:HomePage.vue:116
#       读法 = 「progress.md 的第 401 行里，出现了 token `HomePage.vue:116`」
#
# 用法：
#   bash scripts/refs-scan.sh                       # → scripts/refs-after.txt
#   bash scripts/refs-scan.sh scripts/refs-before.txt
#
# 已知边界（**这是漂移探测器，不是语义校验器**）：
#   · 它只看「token 文本有没有变、引用所在行有没有变」；
#     目标文件**自己**内部的行号是否还指向对的东西，它**看不见** —— 那一条靠人按
#     before/after 的 diff 逐条核（DoD 的「refs before/after 逐条核，语义未偏」）。
#   · 它有少量假阳性（如 `HTTP:8088` 这类全大写词），已在 STOPLIST 里压了一部分；
#     假阳性在 before / after 两侧都出现 ⇒ 不产生 diff 噪声，可忽略。
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="${1:-$HERE/refs-after.txt}"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac

ROOT="$ROOT" OUT="$OUT" node --input-type=module - <<'NODEEOF'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = process.env.ROOT
const OUT  = process.env.OUT

// ── 扫描根（相对 ROOT；目录不存在就跳过）────────────────────────────────────
const ROOTS = [
  'Mall-management-web/mall-management-system/src',
  'Mall-management-server/src',
  'docs',
]
// ── 剪掉的目录名（任意层）───────────────────────────────────────────────────
const SKIP_DIRS = new Set([
  'node_modules', '.git', 'target', 'dist', '.idea', '.vscode',
  '.a3-out', 'reports', 'coverage', '.cache',
])
// ── 只看这些扩展名（其余一律当二进制/无关）──────────────────────────────────
const EXT_OK = new Set([
  '.vue', '.js', '.mjs', '.json', '.md', '.html', '.java', '.properties',
  '.sql', '.yml', '.yaml', '.css', '.sh', '.txt', '.ts', '.xml', '.gitignore',
])
// ── 全大写假目标（`HTTP:8088` 那类）─────────────────────────────────────────
const STOPLIST = new Set([
  'HTTP', 'HTTPS', 'URL', 'URI', 'API', 'JSON', 'SQL', 'CSS', 'HTML', 'XML',
  'GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'ID', 'DB', 'JDK', 'JRE',
  'UTF', 'GBK', 'ASCII', 'TTL', 'QPS', 'ER', 'UI', 'UX', 'CI', 'CD', 'PR',
  'OK', 'FAIL', 'TODO', 'NOTE', 'WARN', 'ERROR', 'INFO', 'DEBUG', 'TRACE',
])

// ── 目标 token 的三条文法 ───────────────────────────────────────────────────
const EXT_ALT = [...EXT_OK].map(e => e.replace('.', '')).join('|')
// ① 带扩展名的路径（可含 / . - 与中文）
const RE_PATH = `[\\w\\u4e00-\\u9fa5./\\-]*\\.(?:${EXT_ALT})`
// ② PascalCase 组件/类名（≥2 字符，首字母大写）
const RE_PASCAL = '[A-Z][A-Za-z0-9]{1,}'
// ③ 单个大写字母（历史上 G/U/M/C/S/B/D/P/T 这套旧锚点）
const RE_LETTER = '[A-Z]'

const NAMED = new RegExp(`(${RE_PATH}|${RE_PASCAL}|${RE_LETTER}):(\\d+)(?:-(\\d+))?`, 'g')
const BARE  = /:(\d+)(?:-(\d+))?/g

function isStopHit(name) {
  return STOPLIST.has(name) && !name.includes('.') && !name.includes('/')
}

// 一个"行内引用"在遇到 `A:B` 前，还得确认它前面不是字母/数字/下划线（避免吃掉 `UTF-8:12` 的尾巴）
function boundaryOk(line, i) {
  if (i === 0) return true
  return !/[A-Za-z0-9_.-]/.test(line[i - 1])
}

function scanLine(line) {
  const out = []
  const namedSpans = []
  NAMED.lastIndex = 0
  let m
  while ((m = NAMED.exec(line)) !== null) {
    const name = m[1]
    if (!boundaryOk(line, m.index)) continue
    if (isStopHit(name)) continue
    out.push({ at: m.index, tok: m[0], target: name })
    namedSpans.push([m.index, m.index + m[0].length])
  }
  // ② 裸写续引：`:NUM` 归本行**最近的那个有名目标**
  BARE.lastIndex = 0
  while ((m = BARE.exec(line)) !== null) {
    const inside = namedSpans.some(([a, b]) => m.index >= a && m.index < b)
    if (inside) continue
    // 前面紧跟名字（没走 named 分支）的情况，跳过，避免重复
    if (m.index > 0 && /[A-Za-z0-9_.\-]/.test(line[m.index - 1])) continue
    let owner = null
    for (const r of out) if (r.at < m.index) owner = r.target
    out.push({ at: m.index, tok: m[0], target: owner })
  }
  out.sort((a, b) => a.at - b.at)
  return out
}

function walk(dir, acc) {
  let ents
  try { ents = fs.readdirSync(dir, { withFileTypes: true }) } catch { return acc }
  for (const e of ents) {
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name)) continue
      walk(path.join(dir, e.name), acc)
    } else if (e.isFile()) {
      const ext = path.extname(e.name).toLowerCase()
      if (!EXT_OK.has(ext) && e.name !== '.gitignore') continue
      if (/^refs-(before|after).*\.txt$/.test(e.name)) continue   // 不扫自己的产物
      acc.push(path.join(dir, e.name))
    }
  }
  return acc
}

const files = []
for (const r of ROOTS) {
  const abs = path.join(ROOT, r)
  if (fs.existsSync(abs)) walk(abs, files)
}

const lines = []
let orphan = 0
for (const f of files) {
  const rel = path.relative(ROOT, f).split(path.sep).join('/')
  let txt
  try { txt = fs.readFileSync(f, 'utf8') } catch { continue }
  const arr = txt.split('\n')
  for (let i = 0; i < arr.length; i++) {
    for (const r of scanLine(arr[i])) {
      if (!r.target) { orphan++; continue }   // 无上游目标的裸 `:NUM` —— 多半是表格里的自指，丢弃
      lines.push(`${rel}:${i + 1}:${r.tok}`)
    }
  }
}

const uniq = [...new Set(lines)].sort()
fs.writeFileSync(OUT, uniq.join('\n') + '\n', 'utf8')

// 统计走 stderr —— 免得污染产物
process.stderr.write(
  `refs-scan: 扫了 ${files.length} 个文件\n` +
  `           命中引用 ${uniq.length} 条（原始 ${lines.length}，去重掉 ${lines.length - uniq.length}）\n` +
  `           丢弃无主裸 ` + '`:' + `行号 ${orphan} 条\n` +
  `           产物 → ${OUT}\n`
)
NODEEOF
