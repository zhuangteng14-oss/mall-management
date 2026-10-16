// probe-b7a2b-heat-diff.mjs —— 把「热力图画布差在哪」从原始像素里**定位**出来（3a.2b，2026-10-16）
//
// 为什么要有它：`probe-b7a2b-preview-shots.mjs` 只给「ink 不同 / hash 不同」——是**是/否**，
// 不是**在哪**。要判断「甲版预览 ≡ 真页面」这条到底成不成立，必须知道差异是**一堆抗锯齿
// 边缘**（可接受：同一份 option 两次渲染的亚像素差）还是**结构性位移**（不可接受：快照丢了东西）。
//
// 输入 = `probe-b7a2b-preview-shots.mjs` 在 `DUMP=<i>` 模式下落的原始 RGBA（格式：
//   `1086x260:<base64>`，**单行**）。
// 输出 = 逐行（y）直方图 + x 范围 + 每像素通道差 —— 小而可复核（原始 dump 才 1.5MB，是
//   由「不动的多数像素」撑起来的；**动的那几个像素**才是证据）。
// 跑法：node scripts/probes/probe-b7a2b-heat-diff.mjs <A.dump.txt> <B.dump.txt> [<C.dump.txt> …]
//   A 是基准（真页面），其余各自与 A 比。
import { readFileSync, writeFileSync } from 'node:fs'

const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/out-b7a2b-heat-diff.txt'
const files = process.argv.slice(2)
if (files.length < 2) { console.error('用法：node probe-b7a2b-heat-diff.mjs <基准.dump.txt> <比对.dump.txt> …'); process.exit(2) }

/* 一行的 dump：`WxH:<base64>`。⚠️ 只认**第一个**冒号 —— base64 里没有冒号，但文件名可能带盘符。 */
function load(p) {
  const s = readFileSync(p, 'utf8')
  const i = s.indexOf(':')
  const [w, h] = s.slice(0, i).split('x').map(Number)
  const buf = Buffer.from(s.slice(i + 1).trim(), 'base64')
  if (buf.length !== w * h * 4) throw new Error(`${p}: 期望 ${w * h * 4} 字节，实际 ${buf.length}`)
  return { w, h, buf, name: p.split(/[\\/]/).pop().replace(/^out-b7a2b-dump-|\.txt$/g, '') }
}

const base = load(files[0])
const lines = []
const say = (t) => { lines.push(t); console.log(t) }

say(`基准 = ${base.name}  ${base.w}x${base.h}（RGBA，${base.buf.length} 字节）`)
say('')

for (const p of files.slice(1)) {
  const b = load(p)
  if (b.w !== base.w || b.h !== base.h) { say(`${b.name}: 尺寸不同（${b.w}x${b.h}）—— 不是像素级可比，跳过`); continue }
  const N = base.w * base.h
  let n = 0, minX = 1e9, maxX = -1, minY = 1e9, maxY = -1, maxD = 0
  const rows = new Map()                      // y → {n, x0, x1}
  for (let y = 0; y < base.h; y++) {
    for (let x = 0; x < base.w; x++) {
      const o = (y * base.w + x) * 4
      let d = 0
      for (let c = 0; c < 4; c++) d = Math.max(d, Math.abs(base.buf[o + c] - b.buf[o + c]))
      if (!d) continue
      n++; maxD = Math.max(maxD, d)
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
      const r = rows.get(y) || { n: 0, x0: 1e9, x1: -1 }
      r.n++; r.x0 = Math.min(r.x0, x); r.x1 = Math.max(r.x1, x)
      rows.set(y, r)
    }
  }
  say(`── ${base.name} vs ${b.name} ──`)
  if (!n) { say('  逐像素完全相同（0 个像素有差）'); say(''); continue }
  say(`  不同像素 ${n} / ${N}（${(n / N * 100).toFixed(3)}%）· 包围盒 x ${minX}–${maxX} / y ${minY}–${maxY} · 最大通道差 ${maxD}/255`)
  say('  y 直方图（只列有差的行）：')
  for (const y of [...rows.keys()].sort((a, c) => a - c)) {
    const r = rows.get(y)
    say(`    y=${String(y).padStart(3)}  ${String(r.n).padStart(3)} px   x ${r.x0}–${r.x1}`)
  }
  say('')
}

writeFileSync(OUT, lines.join('\n') + '\n', 'utf8')
console.log(`产物：${OUT}`)
