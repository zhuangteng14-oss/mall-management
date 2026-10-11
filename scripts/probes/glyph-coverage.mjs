// 探针：自托管字体（Noto Sans SC Variable）到底覆盖哪些候选字形码位（2026-10-11）
//
// 为什么必须实测再换：`glyphs.js` 的 11 枚字形里有 5 枚（◧U+25E7 / ▤U+25A4 / ▦U+25A6 /
// ≣U+2263 / ⚿U+26BF）不在 `noto.css` 的 unicode-range 里 ⇒ 那 5 枚走**系统字体降级**，
// 跨平台不一致。换字形不能靠「看着像」，只能靠这份表。
//
// 做法：把 noto.css 里所有 `unicode-range:` 段解析成区间集合，逐个码位查覆盖。
//
// 用法：node scripts/probes/glyph-coverage.mjs
import { readFileSync } from 'node:fs'

const CSS = 'C:/Users/86159/Desktop/软件工程/期末项目/Mall-management-web/mall-management-system/src/assets/fonts/noto.css'
const css = readFileSync(CSS, 'utf8')

// 逐个 @font-face 块取 unicode-range（一个块可能有多段逗号分隔）
const ranges = []
for (const m of css.matchAll(/unicode-range\s*:\s*([^;}]+)/g)) {
  for (const tok of m[1].split(',')) {
    const t = tok.trim()
    const mm = /^U\+([0-9A-Fa-f]+)(?:-([0-9A-Fa-f]+))?$/.exec(t)
    if (!mm) continue
    const lo = parseInt(mm[1], 16)
    const hi = mm[2] ? parseInt(mm[2], 16) : lo
    ranges.push([lo, hi])
  }
}
ranges.sort((a, b) => a[0] - b[0])
const covered = (cp) => ranges.some(([lo, hi]) => cp >= lo && cp <= hi)
console.log(`unicode-range 段数：${ranges.length}`)

// 候选：每行 = [语义, 当前字形, 候选字形…]。先列现状，再看候选有没有能用的。
const GROUPS = [
  ['overview 总览', '◧', '▣◰◱◻▢◈◆●○◎◍◐◒◓◕▣'],
  ['goods 商品/类目', '▤', '▥▧▨▩▣▪◘░▒▓◼◾◻▢◫'],
  ['orders 订单/状态', '▦', '▩▧▨█▌▐▀▄◪◫▥▤'],
  ['logs 日志/明细', '≣', '≡☰⋮⋯⋰⋱⫶∷∴'],
  ['audit 权限/审计', '⚿', '⚷⚖⚒⛨⌘⛓⚔⚑⚜☖⚙⛭⌥⌦⌫⍟'],
  // ── 2026-10-11 补图标（C1 之后那一笔）新增的四组：侧边栏的「通知收件箱 / 营销运营 /
  //    消息与内容治理 / 系统通知」与商城顶栏的「消息 / 购物车」原表里**没有**对应字形。
  //    候选池按「同语义、同族」挑，选不选得中只看这份表（与上面 5 组同一口径）。
  ['inbox 收件箱/通知', '✉', '✉✎✉☏☑✦✧◈◉▣▤▥◫'],
  ['cart 购物车', '🛒', '⛁⛃⛒▣▤▥◫◇◈◆◉◍⬒⬓◙◘⧉⎔▨▧'],
  ['home 商城主页', '🏠', '⌂⏣⛫⛬⬒⬓⬔⬕⬖⬗◰◱◲◳◴◵◶◷◨◩◬◭◮'],
  ['marketing 营销运营', '✦', '✦✧❖◈◇◆◉◎◍◐◑◒◓◔◕▣▨▧▩'],
  ['moderation 内容治理', '⚠', '⚠⚑⚐⛔◬◭△▽⌦⌫◁▷▱'],
  // 状态标签（待审 / 通过 / 驳回）与「主要按钮」（保存 / 提交 / 删除 / 通过 / 驳回）用。
  ['ok 通过/保存', '✓', '✓✔☑✅⍻√∨⊕✚＋⌸⏎'],
  ['no 驳回/删除', '✗', '✗✘✕×⨯✖☒⛔⌫⌦⊘∅'],
  ['wait 待审', '⏳', '⏳⌛◷◶◵◴↻⟳⋯⋯⋯'],
]


let ok = 0, bad = 0
for (const [label, cur, cands] of GROUPS) {
  const curOk = covered(cur.codePointAt(0))
  console.log(`\n【${label}】现字形 ${cur} U+${cur.codePointAt(0).toString(16).toUpperCase()} ⇒ ${curOk ? '覆盖' : '**缺失**'}`)
  const usable = []
  for (const ch of [...cands]) {
    const cp = ch.codePointAt(0)
    if (covered(cp)) usable.push(`${ch} U+${cp.toString(16).toUpperCase()}`)
    else bad++
  }
  ok += usable.length
  console.log(`  可用候选(${usable.length})：${usable.join('  ') || '（一个都没有）'}`)
}
console.log(`\n合计：可用候选 ${ok} 枚，被否掉 ${bad} 枚`)
