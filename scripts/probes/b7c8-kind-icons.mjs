// 探针：8 类事件图标能不能用**字体字形**（C3 块 7 · commit 8，2026-10-14）
//
// 为什么要有这一份：`src/config/observe-labels.js` 的 `KIND_ICON` 最后**没有**走
// `components/ui/glyphs.js` 那条路（改用内联 SVG）。那是**实测**出来的结论，不是偏好 ——
// 这份探针就是那条结论的证据（同 `glyph-coverage.mjs` 的姿势：把 `noto.css` 的全部
// `unicode-range` 段解析成区间集合，逐个码位查覆盖）。
//
// ⚠️ 判据有**三条**，缺一不可（这条是本探针**改过一次**的地方，记下来免得下次又只跑两条）：
//   ① 字体真覆盖 —— 否则静默降级到系统字体（见 `glyphs.js` 块首：Windows 靠 Segoe UI 兜着，
//      别的机器上不一定）；
//   ② **未被现有 19 枚占用** —— 一棵表里两枚同形，图标就不再指向唯一一件事；
//   ③ **语义真说得通** —— ⚠️ 这一条只能靠**把池子划窄**来体现，不能靠程序判。
//      初版探针只查 ①②，结果「8 类里有 8 类通过」——那是个**假阳性**：`lock` 那一池
//      「覆盖 ∧ 未占用」的是 `⌘ ⚔ ⚜ ⛔ ∅`，五枚里没有一枚表示「锁」。⇒ 现在每类给**两个池**：
//        · **窄池** = 真正表示该物的符号（含 Emoji，用来暴露「Emoji 一律不覆盖」这件事）；
//        · **宽池** = 同族几何符（退而求其次时的候选面）。
//      结论的判据是**窄池**：窄池无命中 ⇒ 只能另找路子（本笔 = 内联 SVG）。
//
// 占用表从 `glyphs.js` 里**读出来**（不手抄：手抄的那份迟早与它漂）。
//
// 用法：node scripts/probes/b7c8-kind-icons.mjs
import { readFileSync } from 'node:fs'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目/Mall-management-web/mall-management-system'
const css = readFileSync(`${ROOT}/src/assets/fonts/noto.css`, 'utf8')

const ranges = []
for (const m of css.matchAll(/unicode-range\s*:\s*([^;}]+)/g)) {
  for (const tok of m[1].split(',')) {
    const mm = /^U\+([0-9A-Fa-f]+)(?:-([0-9A-Fa-f]+))?$/.exec(tok.trim())
    if (!mm) continue
    const lo = parseInt(mm[1], 16)
    ranges.push([lo, mm[2] ? parseInt(mm[2], 16) : lo])
  }
}
const covered = (cp) => ranges.some(([lo, hi]) => cp >= lo && cp <= hi)

const glyphSrc = readFileSync(`${ROOT}/src/components/ui/glyphs.js`, 'utf8')
// ⚠️ 表只取 `GLYPHS = {` 到 `export const GLYPH_NAMES` 之间 —— 后面那段说明注释里也引了字形
//    （写成散文），整文件扫会把它们算成「已占用」，结论就假了。
const table = glyphSrc.slice(glyphSrc.indexOf('export const GLYPHS = {'),
  glyphSrc.indexOf('export const GLYPH_NAMES'))
const used = new Map()
for (const m of table.matchAll(/^\s*(\w+):\s*'(.)'/gm)) {
  used.set(m[2], m[1])
}
console.log(`unicode-range 段数：${ranges.length}`)
console.log(`glyphs.js 现有字形：${[...used.keys()].join(' ')}（${used.size} 枚）\n`)

/** 逐码位查 ①②，返回可用清单。⚠️ Emoji 用 `[...ch]` 拆（`👮` 这类代理对 `codePointAt(0)` 才对）。 */
function usable(pool) {
  const both = []
  const cov = []
  const seen = new Set()
  for (const ch of [...pool]) {
    if (seen.has(ch)) continue
    seen.add(ch)
    const cp = ch.codePointAt(0)
    if (!covered(cp)) continue
    cov.push(form(ch, cp))
    if (!used.has(ch)) both.push(form(ch, cp))
  }
  return { cov, both }
}
const form = (ch, cp) => `${ch} U+${cp.toString(16).toUpperCase()}`

// 每行 = [类 tag, 窄池（真表示该物）, 宽池（同族几何）]
const GROUPS = [
  ['cache', '缓存', '⧉▤▥⌗🗄🧰', '◫▣◈◉◆◇◘◙▢◻⬓⬒'],
  ['lock', '锁', '⚿🔒⛓⛨⚷⌫⌦🔑', '⌘⚔⚜⛔∅⌥'],
  ['order', '订单', '▤▦⎘📄🧾🗒', '▩▪▫◘◙▱▰█▌▐▀▄☰⌸'],
  ['stock', '库存', '▣⬓⬔⧉📦🗃', '◰◱◲◳⬕░▒▓▥▨▧▩'],
  ['audit', '审计', '⚖⚷⎔📋🖋', '⚔⌘⚜☰≡⋮⌸'],
  ['notify', '通知', '✉🔔📣📧', '✎☏☑✦✧✈◈◉'],
  ['sensitive', '敏感词', '⚠⛔🚫🛡👁', '◬◭△▽◁▷☒✖⊘'],
  ['identity', '身份', '◎👤🪪🙂', '◉◍◐◑☺☻⚉▣◫'],
]

let narrowOk = 0
for (const [tag, what, narrow, wide] of GROUPS) {
  const n = usable(narrow)
  const w = usable(wide)
  if (n.both.length) narrowOk++
  console.log(`【${tag} ${what}】`)
  console.log(`  窄池（真表示「${what}」）覆盖∧未占用：${n.both.length ? n.both.join('  ') : '**（无）**'}`)
  console.log(`     └ 其中字体都不覆盖的：${[...narrow].filter((c) => !covered(c.codePointAt(0))).join(' ') || '（无）'}`)
  console.log(`  宽池（同族几何符）覆盖∧未占用：${w.both.length ? w.both.join('  ') : '（无）'}`)
  console.log('')
}

console.log(`结论：8 类里窄池有命中的只有 ${narrowOk} 类。窄池无命中 ⇒「字体字形」这条路对这些语义`)
console.log(`      不存在可用解（宽池那些是「形状像」，不是「意思对」）⇒ KIND_ICON 用内联 SVG。`)
