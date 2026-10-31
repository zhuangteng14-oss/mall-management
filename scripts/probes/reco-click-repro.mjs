// reco-click-repro.mjs —— 复现「商品详情页底部『猜你喜欢』点了没反应」。
//
// 口径：**真前端(8080) + 真后端(8088) + 真库**，不 mock。
//   判据分两步，别把「没导航」和「导航了但页面没换」混为一谈：
//     ① 真实鼠标点击（Input.dispatchMouseEvent，不是 el.click()）—— 先量命中元素，排除遮挡层；
//     ② 点后看 location.hash **和** `.pname` 标题：hash 变了而标题没变 = 同路由复用、页面没重载。
//
// 用法：node scripts/probes/reco-click-repro.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const A = { Id: 130, MerchantId: 'yERU17koBc', GoodName: 'Draft-B7-Demo', Price: 99.9,
  GoodImg: 'http://localhost:8088/img/draft-b7.png', GoodType: 'electronics', ProductCode: 'DRAFT-B7-001' }
const q = new URLSearchParams(A).toString()
const START = `http://localhost:8080/#/product?${q}`

const b = await open({ w: 1440, h: 1000 })
try {
  await b.goto(START, 2500)

  // 等到推荐卡真渲染出来（最多 10s）
  let n = 0
  for (let i = 0; i < 40; i++) {
    n = await b.evalJS(`document.querySelectorAll('.reco .pcard').length`)
    if (n > 0) break
    await sleep(250)
  }

  const before = JSON.parse(await b.evalJS(`(() => {
    const card = document.querySelector('.reco .pcard')
    if (!card) return JSON.stringify({ cards: 0 })
    card.scrollIntoView({ block: 'center' })
    const r = card.getBoundingClientRect()
    const x = r.left + r.width / 2, y = r.top + r.height / 2
    const hit = document.elementFromPoint(x, y)
    const pe = getComputedStyle(card).pointerEvents
    return JSON.stringify({
      cards: document.querySelectorAll('.reco .pcard').length,
      hash: location.hash,
      title: (document.querySelector('.pname') || {}).textContent,
      firstCardName: (card.querySelector('.pcard-name') || {}).textContent,
      href: card.getAttribute('href'),
      rect: { x, y, w: r.width, h: r.height },
      hitInsideCard: !!(hit && hit.closest('.reco .pcard')),
      hitTag: hit ? hit.tagName + '.' + (hit.className || '') : null,
      pointerEvents: pe
    })
  })()`))
  console.log('点击前：', JSON.stringify(before, null, 2))

  if (!before.cards) { console.log('没渲染出推荐卡，终止'); process.exit(2) }

  // 真鼠标：按下 + 抬起（el.click() 绕过命中测试，不作数）
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: before.rect.x, y: before.rect.y, buttons: 0 })
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: before.rect.x, y: before.rect.y, button: 'left', clickCount: 1 })
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: before.rect.x, y: before.rect.y, button: 'left', clickCount: 1 })
  await sleep(2000)

  const after = JSON.parse(await b.evalJS(`JSON.stringify({
    hash: location.hash,
    title: (document.querySelector('.pname') || {}).textContent,
    firstCardName: ((document.querySelector('.reco .pcard .pcard-name') || {}).textContent),
    pnameExists: !!document.querySelector('.pname')
  })`))
  console.log('点击后：', JSON.stringify(after, null, 2))

  const hashChanged = after.hash !== before.hash
  const titleChanged = (after.title || '') !== (before.title || '')
  console.log('\n结论：')
  console.log(`  命中卡片：${before.hitInsideCard}（pointer-events=${before.pointerEvents}）`)
  console.log(`  hash 变化：${hashChanged}`)
  console.log(`  标题变化：${titleChanged}  「${before.title}」→「${after.title}」`)
  console.log(`  判定：${!hashChanged ? '点击未触发导航（CSS/事件问题）' : titleChanged ? '跳转正常' : '已导航到新 URL，但详情页未重新加载（同路由复用 bug）'}`)

  // ── 回归 2：浏览器后退应恢复上一件商品（而不是只改 URL）──
  await b.evalJS('history.back(); 1')
  await sleep(2500)
  const back = JSON.parse(await b.evalJS(`JSON.stringify({
    hash: location.hash,
    title: (document.querySelector('.pname') || {}).textContent
  })`))
  console.log('\n后退后：', JSON.stringify(back))
  console.log(`  后退恢复上一件：${(back.title || '') === (before.title || '')}`)

  // ── 回归 3：连续再点一张不同的推荐卡 ──
  const second = JSON.parse(await b.evalJS(`(() => {
    const card = document.querySelector('.reco .pcard')
    card.scrollIntoView({ block: 'center' })
    const r = card.getBoundingClientRect()
    return JSON.stringify({ x: r.left + r.width / 2, y: r.top + r.height / 2,
      name: (card.querySelector('.pcard-name') || {}).textContent })
  })()`))
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: second.x, y: second.y, buttons: 0 })
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: second.x, y: second.y, button: 'left', clickCount: 1 })
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: second.x, y: second.y, button: 'left', clickCount: 1 })
  await sleep(2500)
  const again = JSON.parse(await b.evalJS(`JSON.stringify({
    title: (document.querySelector('.pname') || {}).textContent,
    scrollY: Math.round(window.scrollY)
  })`))
  console.log('\n第二次点击：', JSON.stringify({ 目标: second.name, ...again }))
  console.log(`  第二次跳转正确：${(again.title || '').startsWith(second.name)}`)

  await b.shot('_work/reco-click-after.png')
} finally {
  await b.close()
}
