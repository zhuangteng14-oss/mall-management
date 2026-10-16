// probe-b7a2b-shot-compare.mjs —— 把甲/乙并排页截成一张交付图（3a.2b，2026-10-16）
//
// 输入：`b7a2b-compare.html`（+ 它引用的 `out-b7a2b-*-crop-*.png`，这些由
//       `probe-b7a2b-preview-shots.mjs` 产出 ⇒ **先跑那个，再跑这个**）
// 输出：`docs/美化/c3-admin-preview/指标页-甲乙对照.png`
//
// ⚠️ 视口宽度是**判据**，不是排版偏好：裁切图是 2×（2240 宽），视口若窄于
//    2240 + 标签列 44 + 间隙 8 + 两侧留白 32 = 2324，`<img>` 就会被缩到 <1×，
//    2× 的 1px 高光边 / 拐点 6→4 这类差**正好被重采样抹平**（本笔踩过：第一版对照图
//    里乙版 QPS 的轴标签几乎看不见，差点误判成「标签没了」）。
//    ⇒ 所以下面两个断言：① 每张图**按原尺寸**渲染 ② 全部加载成功。任一不成立只打印告警，
//      不静默出图 —— 一张被缩过的对照图比没有对照图更危险。
import { open } from '../../docs/美化/_cdp.mjs'

const PAGE = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/b7a2b-compare.html'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/美化/c3-admin-preview/指标页-甲乙对照.png'
const VIEW_W = 2340

const b = await open({ w: VIEW_W, h: 1000 })
try {
  await b.goto('file:///' + encodeURI(PAGE), 2500)
  const imgs = await b.evalJS(`(() => [...document.images].map((i) => i.naturalWidth + 'x' + i.naturalHeight))()`)
  console.log('图上尺寸：' + imgs.join(' | '))
  const bad = await b.evalJS(`(() => [...document.images].filter((i) => !i.naturalWidth).length)()`)
  console.log(bad ? `❌ 有 ${bad} 张没加载出来` : '✅ 全部加载')

  /* ⚠️ 用 `img.width`（= 内容盒布局宽）比，**不是** `getBoundingClientRect().width` —— 后者
     含两侧 1px 边框 ⇒ 2240 的图恒为 2242、断言每跑必红（本笔第一版就写成那样，
     一个每次都响的哨兵等于没有哨兵）。 */
  const scaled = await b.evalJS(`(() => [...document.images]
    .filter((i) => Math.round(i.width) !== i.naturalWidth)
    .map((i) => i.src.split('/').pop() + '→' + Math.round(i.width)))()`)
  if (scaled.length) console.log(`❌ 被缩放了（会把 1px 的差糊掉）：${scaled.join(' ')}`)
  else console.log(`✅ 全部按原尺寸渲染（视口 ${VIEW_W}）`)

  await b.shotFull(OUT)
  console.log('产物：' + OUT)
} finally { await b.close() }
