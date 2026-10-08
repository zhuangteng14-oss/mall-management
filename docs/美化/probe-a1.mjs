// probe-a1.mjs —— 2026-10-08 裁决 ⑤ 复测：A1「后退还原被截到 450px」修没修好。
// 口径：把首页 mock 成**长页**（12 个商品卡），滚到 y=W ⇒ 点卡片进详情 ⇒ `history.back()` ⇒ 读 scrollY。
//   期望：**还原成 W**（修之前：600/900/1041 一律 450；300 恰好 ≤ 450 所以本来就对）。
// 附加一测（第 ③ 条出口）：还原等待期内**再导航走**（连点后退 / 手动改 hash）⇒ 不许把新页滚走。
import { open, sleep, report } from './_cdp.mjs'

const GOODS = Array.from({ length: 16 }, (_, i) => ({
  Id: 'G' + (100 + i), GoodName: '演示商品 ' + (i + 1) + ' · 长名字占位',
  GoodImg: '', Price: 99 + i, GoodType: '数码'
}))
const MOCK = (url) => {
  if (/goodsSelectHome/.test(url)) return { status: 200, body: JSON.stringify({ success: true, list: GOODS }) }
  if (/goodstype/.test(url)) return { status: 200, body: JSON.stringify({ success: true, list: [{ Id: 'T1', GoodType: '数码' }] }) }
  return { status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) }
}

const b = await open({ w: 1440, h: 900, mock: MOCK })
b.ws.on('message', (m) => { const x = JSON.parse(m); if (x.method === 'Page.javascriptDialogOpening') console.log('  ⚠️ 页面弹窗:', x.params.message) })
const R = report('probe-a1')
const S = `JSON.stringify({ y: Math.round(scrollY), docH: document.documentElement.scrollHeight, hash: location.hash })`
try {
  await b.goto('http://localhost:8080/#/customer', 2500)
  console.log('首页就绪:', await b.evalJS(S))   // ⚠️ S 回的是 **JSON 字符串** ⇒ 下面一处不漏地 JSON.parse（2026-10-08 踩过：忘了 parse ⇒ 全印 undefined）
  const maxY = await b.evalJS('document.documentElement.scrollHeight - innerHeight')
  console.log('首页最大滚动量 =', maxY, '（要有 600/900/1041 这几档，得 ≥ 1041）\n')

  for (const want of [300, 600, 900, 1041]) {
    await b.evalJS(`window.scrollTo(0, ${want}); 1`); await sleep(120)
    await b.evalJS(`document.querySelector('.card').click(); 1`)          // 真点击 → toGoodsDetail → push
    await sleep(1200)
    const at = JSON.parse(await b.evalJS(S))
    await b.evalJS('history.back(); 1')
    await sleep(1600)                                                       // > 1500ms 兜底，确保等待已结束
    const back = JSON.parse(await b.evalJS(S))
    console.log(`设定 ${String(want).padStart(4)} → 详情页 y=${at.y} (${at.hash}) → 后退还原 y=${back.y}  docH=${back.docH}`)
    R.ok(`后退还原 ${want}`, Math.abs(back.y - want) <= 2, back.y)
  }

  // ③ 出口：还原等待期内再导航走 —— 不许把新页滚到旧位置
  await b.evalJS(`window.scrollTo(0, 1041); 1`); await sleep(120)
  await b.evalJS(`document.querySelector('.card').click(); 1`); await sleep(1200)
  await b.evalJS('history.back(); 1'); await sleep(60)                     // 还没等它滚，立刻再往前走
  await b.evalJS(`location.hash = '#/product'; 1`)
  await sleep(1800)
  const stale = JSON.parse(await b.evalJS(S))
  console.log('\n③ 出口：等待期内又导航走 ⇒', stale, '（期望 hash=#/product 且 y 不被拉到 1041）')
  R.ok('作废的那一发没滚新页', stale.hash.indexOf('/product') > -1 && stale.y !== 1041, stale)
  R.dump()
} finally { await b.close() }
