// probe-chat-tour-flow.mjs —— 「多步路径引导」真链验收（2026-10-22 纠偏版）
//
// 验的是用户口径：
//   ① 公域首页点【去发布商品】**画面必须停在首页**，高亮右上角「用户中心」；
//   ② 个人中心点胶囊 ⇒ 从「上架商品」开始（跳过第 1 步）；
//   ③ 进发布页后按真实表单走完 7 步：主图 → 名称/分类 → 价格/库存 → 详情 → 提交；
//   ④ 全程只有用户点「下一步 / 上一步」才换页。
//
// 真 dev server + 真后端 + 真模型，不 mock 任何请求。
// 用法：node scripts/probes/probe-chat-tour-flow.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-tour-flow'
fs.rmSync(OUT, { recursive: true, force: true })
fs.mkdirSync(OUT, { recursive: true })

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

// 演示账号口令不硬编码、不回显（与 probe-chat-llm.mjs 同口径）。
const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const b = await open({ w: 1440, h: 900, headed: false })
const pageErrors = []
b.ws.on('message', (raw) => {
  let m
  try { m = JSON.parse(raw) } catch { return }
  if (m.method === 'Page.javascriptDialogOpening') {
    b.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
  }
  if (m.method === 'Runtime.exceptionThrown') {
    pageErrors.push(m.params?.exceptionDetails?.text || 'exception')
  }
})
await b.send('Runtime.enable', {}).catch(() => {})

async function waitFor(expr, timeout = 15000, step = 150) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
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
      const hash = await b.evalJS(`location.hash`)
      if (/^#\/merchant/.test(hash)) return true
    }
  }
  return false
}

/** hash 导航（不整页刷新，保留聊天历史）。 */
async function goHash(hash) {
  await b.evalJS(`location.hash = ${JSON.stringify(hash)}`)
  await sleep(500)
}

async function openPanel() {
  // ⚠️ 必须用 `.chat-root .panel`：页面上本来就有很多业务 `.panel`，
  //    裸 `.panel` 会把「客服面板没开」误判成已开。
  if (await b.evalJS(`!!document.querySelector('.chat-root .panel')`)) return true
  await b.evalJS(`document.querySelector('.chat-root .ball')?.click()`)
  const up = await waitFor(`document.querySelector('.chat-root .panel')`, 5000)
  await sleep(300)
  return up
}

async function sendChat(text) {
  await openPanel()
  const before = await b.evalJS(`document.querySelectorAll('.bubble.bot').length`)
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(el, ${JSON.stringify(text)})
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(150)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  // 完成信号 = 新的 bot 气泡已落地且「正在输入」的 dots 消失。
  const done = await waitFor(
    `document.querySelectorAll('.bubble.bot').length > ${before} && !document.querySelector('.bubble.bot .dots')`,
    20000,
  )
  await sleep(400)
  return done
}

async function clickChip(text) {
  await openPanel()
  await waitFor(`[...document.querySelectorAll('.chip')].some(e => e.textContent.includes(${JSON.stringify(text)}))`, 8000)
  return b.evalJS(`(() => {
    const c = [...document.querySelectorAll('.chip')].find(e => e.textContent.includes(${JSON.stringify(text)}))
    c?.click()
    return !!c
  })()`)
}

// 2026-10-22：driver 自带 popover 已隐藏，控件全部在 `.tour-bar`（贴着飞过去的悬浮球）。
const barShown = `!!document.querySelector('.tour-bar')`
const barGone = `!document.querySelector('.tour-bar')`
const active = (sel) => `document.querySelector(${JSON.stringify(sel)})?.classList.contains('driver-active-element')`
const progress = `document.querySelector('.tour-bar-step')?.textContent?.trim() || ''`
const flowState = `sessionStorage.getItem('mall.chat.tourFlow.v1')`

// ⚠️ 2026-10-22 实测教训：`.tour-bar` 挂在 body 下，driver 的 `.driver-active *`
//    会把它的 pointer-events 置成 none —— 视觉在、真鼠标点不动。JS 的 el.click()
//    不经过命中测试，全绿也发现不了 ⇒ 这里一律用 CDP 真鼠标点击。
async function realClick(sel) {
  const point = await b.evalJS(`(() => {
    const el = document.querySelector(${JSON.stringify(sel)})
    if (!el || el.style.display === 'none') return null
    const r = el.getBoundingClientRect()
    if (r.width < 1 || r.height < 1) return null
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) }
  })()`)
  if (!point) return false
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y, button: 'none' })
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 })
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 })
  return true
}

/** 控制条三个按钮的命中情况：中心点最上层元素必须是按钮自己。 */
async function barHit() {
  return b.evalJS(`(() => {
    const out = {}
    for (const sel of ['.tour-bar-skip', '.tour-bar-prev', '.tour-bar-next']) {
      const el = document.querySelector(sel)
      if (!el || el.style.display === 'none') { out[sel] = { shown: false }; continue }
      const r = el.getBoundingClientRect()
      const top = document.elementFromPoint(Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2))
      out[sel] = {
        shown: true,
        pe: getComputedStyle(el).pointerEvents,
        ok: top === el || el.contains(top),
        top: top ? top.tagName + '.' + (typeof top.className === 'string' ? top.className : '') : null,
      }
    }
    return out
  })()`)
}

async function clickNext() {
  const clicked = await realClick('.tour-bar-next')
  await sleep(250)
  return clicked
}

async function waitStep(sel, progressText, timeout = 12000) {
  const hit = await waitFor(`${active(sel)} && (${progress}) === ${JSON.stringify(progressText)}`, timeout)
  return { hit, progress: await b.evalJS(progress), active: await b.evalJS(active(sel)) }
}

const FORM_ANCHORS = [
  '#tour-publish-image',
  '#tour-publish-basic',
  '#tour-publish-title',
  '#tour-publish-category',
  '#tour-publish-pricing',
  '#tour-publish-price',
  '#tour-publish-stock',
  '#tour-publish-detail',
  '#g-desc',
  '#tour-publish-submit',
]

try {
  ok('商家登录落 /merchant', await login('chenguang_parts', CREDS['chenguang_parts']))

  // ── ① 商城首页：点胶囊必须留在首页 ──────────────────────────────────────
  // ⚠️ 商家角色被角色守卫送回 `/merchant`（客户壳 `/customer` 只给 role 1/3），
  //    所以商家的「商城首页」就是 `/merchant`，不是 `/customer`。别把探针前提写错。
  const homeReady = await waitFor(
    `location.hash === '#/merchant' && document.querySelector('#tour-entry-merchant') && document.querySelector('.chat-root .ball')`,
    12000,
  )
  await b.shot(path.join(OUT, 'flow-00-merchant-home.png'))
  ok('商家商城首页已就绪且顶栏有用户中心入口', homeReady, { hash: await b.evalJS(`location.hash`) })

  const done = await sendChat('我的新商品要上架秒杀页，还没上架呢')
  // 真模型有 3s 超时降级路径；再等一次胶囊本身，避免回复落地与 DOM 更新不同帧。
  const chipReady = await waitFor(
    `[...document.querySelectorAll('.chip')].some(e => e.textContent.includes('去发布商品'))`, 20000,
  )
  const chips = await b.evalJS(`[...document.querySelectorAll('.chip')].map(e => e.textContent.trim())`)
  const reply = await b.evalJS(`[...document.querySelectorAll('.bubble.bot .txt')].pop()?.textContent || ''`)
  ok('复杂语义拿到两枚胶囊', done && chipReady
    && chips.some((x) => x.includes('去发布商品')) && chips.some((x) => x.includes('去秒杀报名')),
  { chips, reply: reply.slice(0, 100) })

  const clicked = await clickChip('去发布商品')
  const s1 = await waitStep('#tour-entry-merchant', '第 1 步 / 共 7 步')
  ok('Step1：首页点胶囊后画面仍在首页，高亮右上角「用户中心」',
    clicked && s1.hit && (await b.evalJS(`location.hash`)) === '#/merchant',
    { ...s1, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-01-entry-home.png'))

  await clickNext()
  const s2 = await waitStep('#tour-menu-add', '第 2 步 / 共 7 步')
  ok('Step2：点「下一步」才进个人中心，高亮「上架商品」',
    s2.hit && (await b.evalJS(`location.hash`)) === '#/merchant/user',
    { ...s2, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-02-menu.png'))

  await clickNext()
  const s3 = await waitStep('#tour-publish-image', '第 3 步 / 共 7 步')
  ok('Step3：进发布页先高亮商品主图',
    s3.hit && (await b.evalJS(`location.hash`)) === '#/merchant/user/add',
    { ...s3, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-03-image.png'))

  const formReady = await b.evalJS(`(${JSON.stringify(FORM_ANCHORS)}).map(s => {
    const el = document.querySelector(s)
    return { s, ok: !!el && el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0 }
  })`)
  ok('发布页真实字段锚点全部渲染可见', formReady.every((x) => x.ok), formReady)

  await clickNext()
  const s4 = await waitStep('#tour-publish-basic', '第 4 步 / 共 7 步')
  const basicHas = await b.evalJS(`(() => {
    const box = document.querySelector('#tour-publish-basic')
    return !!box && !!box.querySelector('#tour-publish-title') && !!box.querySelector('#tour-publish-category')
  })()`)
  ok('Step4：高亮名称与分类（同一区块含标题 / 类型）', s4.hit && basicHas, { ...s4, basicHas })
  await b.shot(path.join(OUT, 'flow-04-basic.png'))

  await clickNext()
  const s5 = await waitStep('#tour-publish-pricing', '第 5 步 / 共 7 步')
  const pricingHas = await b.evalJS(`(() => {
    const box = document.querySelector('#tour-publish-pricing')
    return !!box && !!box.querySelector('#tour-publish-price') && !!box.querySelector('#tour-publish-stock')
  })()`)
  ok('Step5：高亮价格与库存（同一区块含售价 / 库存）', s5.hit && pricingHas, { ...s5, pricingHas })
  await b.shot(path.join(OUT, 'flow-05-pricing.png'))

  await clickNext()
  const s6 = await waitStep('#tour-publish-detail', '第 6 步 / 共 7 步')
  const detailHas = await b.evalJS(`!!document.querySelector('#tour-publish-detail #g-desc')`)
  ok('Step6：高亮商品详情文本域', s6.hit && detailHas, { ...s6, detailHas })
  await b.shot(path.join(OUT, 'flow-06-detail.png'))

  await clickNext()
  const s7 = await waitStep('#tour-publish-submit', '第 7 步 / 共 7 步')
  ok('Step7：高亮提交审核按钮', s7.hit, s7)
  await b.shot(path.join(OUT, 'flow-07-submit.png'))

  // 回归（2026-10-22 用户实测 bug）：最后一步「跳过 / 上一步 / 完成」必须能被**真鼠标**
  // 命中。根因是 driver 给 body 挂 .driver-active 后 `.driver-active *` 把 body 级
  // `.tour-bar` 的 pointer-events 置成了 none；JS 的 el.click() 测不出来。
  const lastHit = await barHit()
  ok('Step7：三个按钮真鼠标可命中（pointer-events = auto）',
    ['skip', 'prev', 'next'].every((k) => lastHit[`.tour-bar-${k}`]?.ok && lastHit[`.tour-bar-${k}`].pe === 'auto'),
    lastHit)

  const finished = await clickNext()
  const closed = await waitFor(barGone, 5000)
  const finishState = await b.evalJS(flowState)
  await sleep(950) // 球飞回右下角 0.8s
  const home = await b.evalJS(`(() => {
    const sp = document.querySelector('.chat-root .ball')
    const de = document.documentElement
    const r = sp.getBoundingClientRect()
    return {
      x: Math.round(r.left), y: Math.round(r.top),
      wantX: de.clientWidth - 24 - 56, wantY: de.clientHeight - 24 - 56,
      tourMode: document.querySelector('.chat-root').classList.contains('tour-mode'),
      overlay: !!document.querySelector('.driver-overlay'),
    }
  })()`)
  ok('最后一步点「完成」⇒ 浮层消失、存档清空、同一颗球飞回右下角',
    finished && closed && finishState === null && home.tourMode === false && home.overlay === false
      && Math.hypot(home.x - home.wantX, home.y - home.wantY) <= 2,
    { finished, closed, flow: finishState, home })

  // ── ② 直接导航进发布页（无流程）⇒ 不出现高亮 ────────────────────────────
  await goHash('#/merchant/user/add')
  await waitFor(`document.querySelector('#tour-publish-image')`, 8000)
  await sleep(800)
  ok('直接进发布页不触发高亮（无流程存档）',
    !(await b.evalJS(barShown)) && !(await b.evalJS(active('#tour-publish-image'))))

  // ── ③ 已在发布页点同一个胶囊 ⇒ 从第 3 步（主图）开始 ────────────────────
  const clickedAgain = await clickChip('去发布商品')
  const s8 = await waitStep('#tour-publish-image', '第 3 步 / 共 7 步')
  ok('已在发布页：自动从第 3 步（主图）开始，不重复强调入口',
    clickedAgain && s8.hit && (await b.evalJS(`location.hash`)) === '#/merchant/user/add',
    { ...s8, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-08-resume-image.png'))

  await realClick('.tour-bar-prev')
  const back = await waitStep('#tour-menu-add', '第 2 步 / 共 7 步', 12000)
  ok('「上一步」跨页回到个人中心并高亮上架入口',
    back.hit && (await b.evalJS(`location.hash`)) === '#/merchant/user',
    { ...back, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-09-prev.png'))
  // 「跳过引导，直接前往」现在会真的走到流程终点（发布页）——本步只是收尾，
  // 下一幕要在个人中心点胶囊，所以跳过之后把人送回个人中心。
  await realClick('.tour-bar-skip')
  await sleep(950)
  await goHash('#/merchant/user')

  // ── ④ 个人中心点胶囊 ⇒ 第 1 步就是「上架商品」，不再高亮顶栏 ─────────────
  const personalClick = await clickChip('去发布商品')
  const s9 = await waitStep('#tour-menu-add', '第 2 步 / 共 7 步')
  ok('已在个人中心：Step1 留在当前页高亮「上架商品」（跳过顶栏）',
    personalClick && s9.hit && (await b.evalJS(`location.hash`)) === '#/merchant/user',
    { ...s9, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-10-personal-start.png'))
  await realClick('.tour-bar-skip')
  await sleep(950)
  await goHash('#/merchant/user')

  // ── ⑤ 秒杀两步流（用户点胶囊后允许直接到报名页）────────────────────────
  const clickedSeckill = await clickChip('去秒杀报名')
  const k1 = await waitStep('#tour-seckill-select', '第 1 步 / 共 2 步')
  ok('秒杀：点胶囊落到报名页并高亮商品下拉',
    clickedSeckill && k1.hit && (await b.evalJS(`location.hash`)) === '#/merchant/seckill',
    { ...k1, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-11-seckill.png'))
  await clickNext()
  const k2 = await waitStep('#tour-seckill-submit', '第 2 步 / 共 2 步')
  ok('秒杀：同页切到提交报名', k2.hit, k2)
  await clickNext()
  ok('秒杀：完成后浮层消失', await waitFor(barGone, 5000))

  // ── ⑥ 「怎么开店」默认引导：从当前页起步，不瞬移、不串到发布流程 ──────────
  await sendChat('怎么开店')
  const hasAction = await waitFor(`document.querySelector('.chat-root .tour-btn')`, 15000)
  const from6 = await b.evalJS(`location.hash`)
  const acted = await b.evalJS(`(() => {
    // 聊天记录里还有上一幕「去秒杀报名」的动作按钮 ⇒ 必须点**最后一条**回复上的那颗。
    const btns = [...document.querySelectorAll('.chat-root .tour-btn')]
    const el = btns[btns.length - 1]
    el?.click()
    return !!el
  })()`)
  const g1 = await waitStep('#tour-entry-merchant', '第 1 步 / 共 5 步', 15000)
  const g1Hash = await b.evalJS(`location.hash`)
  ok('「怎么开店」从当前页开始引导（不瞬移、不串到发布页）',
    hasAction && acted && g1.hit && g1Hash === from6 && !from6.endsWith('/user/add'),
    { hasAction, acted, from: from6, hash: g1Hash, ...g1 })
  await b.shot(path.join(OUT, 'flow-12-open-store-guide.png'))
  await realClick('.tour-bar-skip')
  const skipped = await waitFor(`location.hash === '#/merchant/user/apply'`, 8000)
  ok('「怎么开店」点【跳过引导，直接前往】落到入驻申请页',
    skipped, { hash: await b.evalJS(`location.hash`) })

  ok('页面无未捕获异常', pageErrors.length === 0, { pageErrors })
} catch (e) {
  ok('探针异常', false, { error: String(e && e.message ? e.message : e) })
} finally {
  const passed = rows.filter((r) => r.pass).length
  console.log(`\nflow: ${passed}/${rows.length} PASS`)
  fs.writeFileSync(path.join(OUT, 'probe-flow.txt'),
    rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}  ${JSON.stringify(r.detail)}`).join('\n') + '\n'
    + `\nflow: ${passed}/${rows.length} PASS\n`)
  await sleep(200)
  process.exit(passed === rows.length ? 0 : 1)
}
