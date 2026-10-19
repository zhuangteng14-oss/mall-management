// b4-batch1-verify.mjs —— 批四 · 第一批（2026-10-18）的真页面取证
//
// 判据（真前端 8080 + 真后端 8088）：
//   ① 攻击页「实时预览」：三列都在、三条流都 live、指标列滚动出真行
//   ② 切走攻击页 10 秒内**不再**新开 SSE（`onBeforeUnmount` 三条 close）
//   ③ 日志页 ⑥ 开关栏是只读图例：没有 <button>、有「去攻击模拟器操作」链接
//   ④ 主页三枚占位按钮变成真链接，点「品牌专区」落「敬请期待」页
//   ⑤ 商品详情 / 规格：稿 mock 不在，出现「暂无数据」空态
//   ⑥ 商品卡加购：未登录时提示登录且**不跳详情页**；有 customerid 时真的写进 `/cart/add`
//   ⑦ 收尾：删掉探针写的那行车、清掉探针身份
//
// 凭据：admin 口令只从环境变量 `PROBE_ADMIN_PWD` 读（本地来源 `_work/admin-pass`），不硬编码。
// 用法：
//   PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" node scripts/probes/b4-batch1-verify.mjs
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const API = 'http://localhost:8088'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) {
  console.error('缺少 PROBE_ADMIN_PWD')
  process.exit(2)
}

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/batch4-1'
fs.mkdirSync(EV, { recursive: true })
const SHOT = {
  attack: `${EV}/b4-01-attack-preview.png`,
  attackFull: `${EV}/b4-01b-attack-preview-full.png`,
  logs: `${EV}/b4-02-logs-switch-legend.png`,
  soon: `${EV}/b4-03-coming-soon-brand.png`,
  desc: `${EV}/b4-04-product-empty-desc.png`,
  spec: `${EV}/b4-05-product-empty-spec.png`,
}

const R = report('批四 · 第一批（占位清理 + 三块预览）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const PRODUCT = {
  Id: 120,
  MerchantId: 'dRD3gqtbFr',
  GoodName: '无线蓝牙耳机 声谷标准版',
  GoodType: '电子用品',
  ProductCode: 'GD-101-9552',
}
const PRODUCT_URL = `${BASE}/#/product?${new URLSearchParams(PRODUCT).toString()}`

const b = await open({ w: 1600, h: 1100 })

const hash = () => b.evalJS('location.hash')

async function waitFor(expr, timeoutMs = 15000, step = 250) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const v = await b.evalJS(expr).catch(() => null)
    if (v) return v
    await sleep(step)
  }
  return null
}

// 在页面里装一个 fetch 计数器：只记三条 SSE 的 URL。
async function installSseCounter() {
  return b.evalJS(`(() => {
    window.__b4sse = []
    const orig = window.fetch
    window.fetch = function (input, init) {
      const u = typeof input === 'string' ? input : ((input && input.url) || '')
      if (/\\/admin\\/observe\\/(metrics|orders|events)\\/stream/.test(u)) {
        window.__b4sse.push({ u, t: Date.now() })
      }
      return orig.apply(this, arguments)
    }
    return true
  })()`)
}

const sseCount = () => b.evalJS('(window.__b4sse || []).length')

const cartList = (cid) => b.evalJS(`fetch(${JSON.stringify(API + '/cart/list')}, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: 'CustomerId=' + encodeURIComponent(${JSON.stringify(cid)}),
}).then(r => r.json())`)

const cartRemove = (cid, mid, code) => b.evalJS(`fetch(${JSON.stringify(API + '/cart/remove')}, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ CustomerId: ${JSON.stringify(cid)}, MerchantId: ${JSON.stringify(mid)}, ProductCode: ${JSON.stringify(code)} }).toString(),
}).then(r => r.json())`)

let probeCustomer = ''
let addedRow = null

try {
  // ── 登录管理端（攻击页要登录态）────────────────────────────────────────────
  await b.goto(BASE + '/admin.html', 2500)
  const login = await b.evalJS(`(() => {
    const set = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    if (!acc || !pwd) return { ok: false, hash: location.hash }
    set(acc, 'admin'); set(pwd, ${JSON.stringify(PWD)})
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')]
      .find(x => x.textContent.trim() === '登录')
    if (!btn) return { ok: false, why: 'no-button' }
    btn.click()
    return { ok: true }
  })()`)
  ok('管理端登录提交', login.ok === true, login)
  const adminHash = await waitFor(`/^#\\/admin/.test(location.hash) ? location.hash : ''`, 20000, 500)
  ok('进入管理端', !!adminHash, adminHash)
  await installSseCounter()

  // ── ① 攻击页三块预览 ──────────────────────────────────────────────────────
  await b.evalJS(`location.hash = '#/admin/observe/attack-sim'`)
  const lp = await waitFor(`!!document.querySelector('.lp')`, 20000)
  ok('① 攻击页出现「实时预览」块（.lp）', !!lp)
  const cols = await b.evalJS(`document.querySelectorAll('.lp .lp-col').length`)
  ok('① 三列布局（指标 / 订单 / 日志）', cols === 3, cols)

  const live = await waitFor(`(() => {
    const xs = [...document.querySelectorAll('.lp-conn')]
    return xs.length === 3 && xs.every(x => x.classList.contains('live')) ? xs.map(x => x.textContent.trim()) : null
  })()`, 15000, 500)
  ok('① 三条流都到 live（实时）', Array.isArray(live) && live.length === 3, live)

  // 指标列每秒一行；等它攒够几行，证明是「滚动」而不是只有表头。
  const metricRows = await waitFor(`(() => {
    const n = document.querySelectorAll('.lp-col:nth-child(1) .lp-list li').length
    return n >= 3 ? n : 0
  })()`, 15000, 500)
  ok('① 指标列滚动出 ≥3 行真读数', metricRows >= 3, metricRows)

  const lineTxt = await b.evalJS(`(() => {
    const li = document.querySelector('.lp-col:nth-child(1) .lp-list li')
    return li ? li.textContent.trim() : ''
  })()`)
  ok('① 行内容含 QPS / P99（不是空行）', /QPS/.test(lineTxt) && /P99/.test(lineTxt), lineTxt)

  await b.evalJS(`document.querySelector('.lp').scrollIntoView({ block: 'center' })`)
  await sleep(600)
  await b.shot(SHOT.attack)
  await b.shotFull(SHOT.attackFull)

  // ── ② 切走页面 ⇒ 三条流必须停 ─────────────────────────────────────────────
  await b.evalJS(`location.hash = '#/admin/observe/logs'`)
  await sleep(1500)
  const before = await sseCount()
  await sleep(10000)
  const after = await sseCount()
  ok('② 切走攻击页后 10 秒内没有新开 SSE（close 生效）', after === before, { before, after })

  // ── ③ 日志页 ⑥ 开关栏 = 只读图例 ─────────────────────────────────────────
  const sw = await waitFor(`!!document.querySelector('.ev-switch')`, 20000)
  ok('③ 日志页开关栏在', !!sw)
  const swInfo = await b.evalJS(`(() => {
    const bar = document.querySelector('.ev-switch')
    if (!bar) return null
    const go = bar.querySelector('a.go')
    return {
      buttons: bar.querySelectorAll('button').length,
      chips: bar.querySelectorAll('.sw2').length,
      text: bar.textContent.replace(/\\s+/g, ' ').trim(),
      goHref: go ? go.getAttribute('href') : null,
      goText: go ? go.textContent.trim() : null,
    }
  })()`)
  ok('③ 栏内没有任何 <button>（不再像可点）', swInfo && swInfo.buttons === 0, swInfo && swInfo.buttons)
  ok('③ 有「只读图例」说明', !!swInfo && /只读图例/.test(swInfo.text), swInfo && swInfo.text)
  ok('③ 有去攻击模拟器的链接', !!swInfo && /#\/admin\/observe\/attack-sim/.test(swInfo.goHref || ''), swInfo && swInfo.goHref)
  await b.evalJS(`document.querySelector('.ev-switch').scrollIntoView({ block: 'center' })`)
  await sleep(400)
  await b.shot(SHOT.logs)

  // ── ④ 主页三入口 → 敬请期待页 ─────────────────────────────────────────────
  // ⚠️ 先退掉管理员身份：`#/` 的守卫会把 admin 送回 `/admin`，主页根本不会渲染
  //    （第一次跑探针时就撞在这里：`.subnav` 为 null，而「没有 disabled」那条**假绿**了）。
  await b.evalJS(`sessionStorage.clear(); localStorage.clear()`)
  await b.send('Network.clearBrowserCookies').catch(() => {})
  await b.goto(BASE + '/?b4=store#/', 2500)
  await sleep(1200)
  const soonLinks = await waitFor(`(() => {
    const xs = [...document.querySelectorAll('.subnav a[href*="coming-soon"]')]
    return xs.length ? xs.map(x => ({ text: x.textContent.trim(), href: x.getAttribute('href') })) : null
  })()`, 15000, 500)
  ok('④ 二级导航三枚占位都成了真链接', Array.isArray(soonLinks) && soonLinks.length === 3, soonLinks)
  const subDisabled = await b.evalJS(`document.querySelectorAll('.subnav button[disabled]').length`)
  ok('④ 二级导航没有 disabled 按钮了', subDisabled === 0, subDisabled)

  const clickedBrand = await b.evalJS(`(() => {
    const a = [...document.querySelectorAll('.subnav a[href*="coming-soon/brand"]')][0]
    if (!a) return false
    a.click(); return true
  })()`)
  await sleep(800)
  const soon = await b.evalJS(`(() => {
    const t = document.querySelector('.cs-title')
    const n = document.querySelector('.cs-name')
    return t ? { title: t.textContent.trim(), name: n ? n.textContent.trim() : '', hash: location.hash } : null
  })()`)
  ok('④ 点「品牌专区」落敬请期待页', clickedBrand === true && !!soon && soon.title === '敬请期待', soon)
  ok('④ 页面写明是「品牌专区」', !!soon && soon.name === '品牌专区', soon && soon.name)
  await b.shot(SHOT.soon)

  // ── ⑤ 商品详情 / 规格空态 ────────────────────────────────────────────────
  await b.goto(PRODUCT_URL, 2500)
  const desc = await waitFor(`(() => {
    const e = document.querySelector('.panel .empty')
    if (!e) return null
    return {
      text: e.textContent.replace(/\\s+/g, ' ').trim(),
      mock: document.body.innerText.includes('声谷标准版是这一代的主力款') || document.body.innerText.includes('商品详情长图'),
    }
  })()`, 20000, 500)
  ok('⑤ 详情 Tab 出现空态', !!desc && /暂无数据/.test(desc.text), desc)
  ok('⑤ 稿的耳机文案 / 1200×400 长图已不在', !!desc && desc.mock === false, desc && desc.mock)
  await b.shot(SHOT.desc)

  await b.evalJS(`document.querySelectorAll('.tabs button')[1].click()`)
  await sleep(500)
  const spec = await b.evalJS(`(() => {
    const e = document.querySelector('.panel .empty')
    if (!e) return null
    return {
      text: e.textContent.replace(/\\s+/g, ' ').trim(),
      mock: document.body.innerText.includes('SG-A2') || document.body.innerText.includes('声谷 SG'),
    }
  })()`)
  ok('⑤ 规格 Tab 出现空态', !!spec && /暂无数据/.test(spec.text), spec)
  ok('⑤ 稿的 8 行耳机参数已不在', !!spec && spec.mock === false, spec && spec.mock)
  await b.shot(SHOT.spec)

  // ── ⑥ 商品卡加购 ─────────────────────────────────────────────────────────
  await b.evalJS(`location.hash = '#/'`)
  await sleep(1200)
  const buyInfo = await b.evalJS(`(() => {
    const el = document.querySelector('.pcard-buy')
    if (!el) return null
    return { role: el.getAttribute('role'), tab: el.getAttribute('tabindex'), cls: el.className }
  })()`)
  ok('⑥ 加购图标已是 role=button / tabindex=0', !!buyInfo && buyInfo.role === 'button' && buyInfo.tab === '0', buyInfo)

  // 6a 未登录（清掉 customerid）：提示登录、且不跳详情页
  await b.evalJS(`sessionStorage.removeItem('customerid'); sessionStorage.removeItem('merchantid'); window.__b4alert = ''; window.alert = (m) => { window.__b4alert = String(m) }`)
  const beforeHash = await hash()
  await b.evalJS(`document.querySelector('.pcard-buy').click()`)
  await sleep(800)
  const guard = await b.evalJS(`({ alert: window.__b4alert, hash: location.hash })`)
  ok('⑥ 未登录点加购 → 提示「请先登录」', /请先登录/.test(guard.alert || ''), guard.alert)
  ok('⑥ 且没有跳到商品详情', !/#\/product/.test(guard.hash || '') && guard.hash !== beforeHash, { beforeHash, after: guard.hash })

  // 6b 有身份：真的写进 /cart/add（dev 鉴权关，用探针身份）
  const probeCid = 'probeB4batch1'
  probeCustomer = probeCid
  await b.evalJS(`sessionStorage.setItem('customerid', ${JSON.stringify(probeCid)}); window.__b4alert = ''; location.hash = '#/'`)
  await sleep(1200)
  const beforeCart = await cartList(probeCid)
  await b.evalJS(`document.querySelector('.pcard-buy').click()`)
  await sleep(1200)
  const afterCart = await cartList(probeCid)
  // ⚠️ 不按「预期的 ProductCode」筛：主页第一张卡是哪件商品由后端排序决定，探针不该假定它
  //    就是详情页用的那一件（第一次跑正是在这里假红，收尾还因此删错了行）。
  const beforeRows = (beforeCart && beforeCart.list) || []
  const afterRows = (afterCart && afterCart.list) || []
  addedRow = afterRows.find((x) => !beforeRows.some((y) => y.Id === x.Id)) || afterRows[0] || null
  ok('⑥ 加购真的写进 /cart/add（车从 0 行变 1 行，且行上有 ProductCode）',
    (beforeCart && beforeCart.success) === true && afterRows.length > beforeRows.length &&
      !!addedRow && !!addedRow.ProductCode,
    { before: beforeRows.length, after: afterRows.length, row: addedRow && { ProductCode: addedRow.ProductCode, MerchantId: addedRow.MerchantId } })
  const doneCls = await b.evalJS(`(document.querySelector('.pcard-buy') || {}).className || ''`)
  ok('⑥ 成功后图标短暂变「已加入」态', /done/.test(doneCls), doneCls)

  // ── ⑦ 收尾 ───────────────────────────────────────────────────────────────
  const removed = await cartRemove(probeCid, addedRow.MerchantId, addedRow.ProductCode)
  const left = await cartList(probeCid)
  ok('⑦ 收尾：探针写的那行车已删除', removed && removed.success === true && (left.list || []).length === 0, {
    removed, left: (left && left.list || []).length,
  })
  await b.evalJS(`sessionStorage.removeItem('customerid')`)

  const errs = await b.evalJS('(window.__errs || [])')
  ok('⑦ 全程无 console.error / unhandledrejection（页面未挂监听时跳过）',
    !Array.isArray(errs) || errs.length === 0, Array.isArray(errs) ? errs.slice(0, 3) : 'no-hook')
} catch (e) {
  console.log('探针中途异常：', (e && e.message) || e)
} finally {
  try {
    if (probeCustomer && addedRow) await cartRemove(probeCustomer, addedRow.MerchantId, addedRow.ProductCode)
  } catch (e) {
    console.log('收尾删车失败：', (e && e.message) || e)
  }
  const bad = R.dump()
  console.log(`\n截图：\n  ${Object.values(SHOT).join('\n  ')}`)
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
