// probe-chat-goods-tour.mjs —— 商品查询「脱敏 + 带我去详情」跨页漫游的真链验收（2026-10-22）
//
// 真 dev server（8080）+ 真后端（8088，dev profile）+ 真 GLM-4-Flash：
//   ① 买家登录（1111，口令读 _work/demo-pw.txt，不打印）；
//   ② 「怎么看 oppo12 商品的详细数据」⇒ 回复不含店铺号 / 内部编号，且带「带我去商品详情」胶囊；
//   ③ 关闭再打开面板 ⇒ 第一帧就贴底（scrollTop 已在底部），历史不重放打字机（无 caret）；
//   ④ 点胶囊（真鼠标）⇒ 漫游四步：搜索框 → 搜索按钮 → 商品卡片 → 详情页，逐步断言 + 截图。
//
// 用法：node scripts/probes/probe-chat-goods-tour.mjs
import fs from 'node:fs'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-goods-query'
fs.mkdirSync(OUT, { recursive: true })

const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const b = await open({ w: 1440, h: 900, headed: false })
const rows = []
const ok = (name, pass, got) => {
  rows.push([name, !!pass, got])
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}  ${JSON.stringify(got)}`)
}

async function waitFor(expr, timeout = 20000, step = 200) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

/** 真鼠标点击：先取元素中心，再走 CDP 命中测试（JS 的 el.click() 会绕过 .driver-active 的遮罩）。 */
async function clickSelector(selector) {
  const box = await b.evalJS(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)})
    if (!el) return null
    const r = el.getBoundingClientRect()
    if (!r.width || !r.height) return null
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
  })()`)
  if (!box) return false
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y, buttons: 0 })
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  return true
}

/** 按可见文字点胶囊（最后一条客服气泡里的那一枚）。 */
async function clickChip(label) {
  const box = await b.evalJS(`(() => {
    const chips = [...document.querySelectorAll('.bubble.bot .chip')]
    const el = [...chips].reverse().find((c) => c.textContent.includes(${JSON.stringify(label)}))
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
  })()`)
  if (!box) return false
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y, buttons: 0 })
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  return true
}

async function login(acc, pwd) {
  await b.goto(BASE + '/#/login', 1500)
  await waitFor(`document.querySelector('#acc') && document.querySelector('#pwd')`, 15000)
  for (let attempt = 0; attempt < 3; attempt++) {
    await b.evalJS(`(() => {
      const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
      set(document.querySelector('#acc'), ${JSON.stringify(acc)})
      set(document.querySelector('#pwd'), ${JSON.stringify(pwd)})
      const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(e => e.textContent.trim() === '登录' && !e.disabled)
      btn?.click()
      return !!btn
    })()`)
    for (let i = 0; i < 24; i++) {
      await sleep(500)
      if (/^#\/customer/.test(await b.evalJS(`location.hash`))) return true
    }
  }
  return false
}

async function openChat() {
  if (await b.evalJS(`!!document.querySelector('.panel')`)) return true
  await clickSelector('.chat-root .ball')
  return await waitFor(`document.querySelector('.panel')`, 5000)
}

async function sendChat(text) {
  await openChat()
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(el, ${JSON.stringify(text)})
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(200)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  const done = await waitFor(
    `document.querySelector('.composer textarea') && !document.querySelector('.composer textarea').disabled`,
    20000,
  )
  await sleep(500)
  return done
}

const tourTitle = () => b.evalJS(`document.querySelector('.tour-bar .tour-bar-title')?.textContent || ''`)
const tourStep = () => b.evalJS(`document.querySelector('.tour-bar .tour-bar-step')?.textContent || ''`)

try {
  if (!await login('1111', CREDS['1111'])) throw new Error('买家登录失败')
  await sleep(1200)

  await sendChat('怎么看 oppo12 商品的详细数据')
  await sleep(400)
  const reply = await b.evalJS(`[...document.querySelectorAll('.bubble.bot .txt')].pop()?.textContent || ''`)
  ok('回复不含店铺号 5N1k5K2guh', !/5N1k5K2guh/.test(reply), reply)
  ok('回复不含内部编号 OP2', !/OP2/.test(reply), reply)
  ok('回复含库存话术', /库存/.test(reply), reply)
  const capsule = await b.evalJS(`[...document.querySelectorAll('.bubble.bot .chip')].some((c) => c.textContent.includes('带我去商品详情'))`)
  ok('回复下方有「带我去商品详情」胶囊', capsule)
  await b.shot(OUT + '/shot-goods-capsule.png')

  // ── 打开面板的滚动：关闭后再打开，**第一帧**就应贴底，且历史不重放打字机 ──
  await clickSelector('.panel .icon-btn')
  await sleep(400)
  const frames = await b.evalJS(`(async () => {
    document.querySelector('.chat-root .ball').click()
    const out = []
    for (let i = 0; i < 3; i += 1) {
      await new Promise((r) => requestAnimationFrame(r))
      const el = document.querySelector('.list')
      out.push(el ? { top: el.scrollTop, h: el.clientHeight, sh: el.scrollHeight } : null)
    }
    return out
  })()`)
  const first = frames[0]
  ok('打开面板第一帧已贴底', !!first && first.top + first.h >= first.sh - 2, frames)
  const carets = await b.evalJS(`document.querySelectorAll('.bubble.bot .caret').length`)
  ok('历史不重放打字机（无 caret）', carets === 0, carets)

  // ── 点胶囊：搜索框 → 搜索按钮 → 商品卡片 → 详情页 ──
  ok('胶囊可点（真鼠标）', await clickChip('带我去商品详情'))
  await waitFor(`document.querySelector('.tour-bar')`, 8000)
  await sleep(1200)
  const t1 = await tourTitle()
  ok('第 1 步高亮顶栏搜索框', /搜索框/.test(t1) && /oppo12/.test(t1), { t1, step: await tourStep() })
  await b.shot(OUT + '/shot-tour-search-input.png')

  await clickSelector('.tour-bar-next')
  await sleep(1200)
  const t2 = await tourTitle()
  ok('第 2 步高亮搜索按钮', /点「搜索」/.test(t2), t2)
  await b.shot(OUT + '/shot-tour-search-button.png')

  await clickSelector('.tour-bar-next')
  await sleep(2500)
  await waitFor(`location.hash.includes('/search')`, 8000)
  await waitFor(`document.querySelector('.tour-bar')`, 8000)
  await sleep(1200)
  const hashSearch = await b.evalJS(`location.hash`)
  const t3 = await tourTitle()
  ok('第 3 步落在搜索结果页并高亮商品卡片', /\/search\?kw=oppo12/.test(hashSearch) && /商品卡片/.test(t3),
    { hashSearch, t3 })
  await b.shot(OUT + '/shot-tour-product-card.png')

  await clickSelector('.tour-bar-next')
  await sleep(2500)
  await waitFor(`location.hash.startsWith('#/product')`, 8000)
  await waitFor(`document.querySelector('.tour-bar')`, 8000)
  await sleep(1200)
  const hashDetail = await b.evalJS(`location.hash`)
  const t4 = await tourTitle()
  ok('第 4 步到达商品详情页（且是 oppo12 本尊）',
    /^#\/product\?/.test(hashDetail) && /GoodName=oppo12/.test(hashDetail) && /已到达商品详情页/.test(t4),
    { hashDetail, t4 })
  ok('详情页真实渲染（有详情容器）', await b.evalJS(`!!document.querySelector('.goods-detail-page')`))
  await b.shot(OUT + '/shot-tour-detail.png')

  fs.writeFileSync(OUT + '/ui-tour-probe.txt',
    rows.map(([n, p, g]) => `${p ? 'PASS' : 'FAIL'}  ${n}\n  ${JSON.stringify(g)}`).join('\n') + '\n', 'utf8')
  if (rows.some(([, p]) => !p)) process.exitCode = 1
} finally {
  await b.close()
}
