// probe-lock-wait-dashboard.mjs —— 2026-10-31「锁等待图表全空」修复的**端到端实测**。
//
// 查三件（用户原话）：
//   ① 依赖状态是否变绿（L2 缓存 / 锁客户端 / 商品库）；
//   ② 打真并发时「锁等待次数」是否 > 0（修复前它只数 timeouts，恒 0）；
//   ③ 锁竞争热力图是否出现高亮格。
//
// 跑法（**默认 profile 为准** —— 答辩演示用的是它）：① 后端 8088 不带 profile
//   ② `pnpm dev`（8080）③ `PROBE_ADMIN_PWD=$(cat _work/admin-pass) node scripts/probes/probe-lock-wait-dashboard.mjs`
// 登录走**管理员专属页** `#/admin/login`（AdminLoginPage → `POST /user/adminToken`）：
//   · 不走普通登录页：那一页自 2026-10-31 起要图形验证码，脚本读不出 PNG（不做 OCR 才证明它是真闸）；
//   · 也不走「手机号登录」：后端 `loginByPhone` 只放行客户(1)/商家(2)，管理员明确被挡在门外
//     （`UserService.java:203`）—— 实测报「该手机号未注册，或短信验证码不正确」，别当成账号问题。
//   · 口令只从环境变量读，不落盘、不打印。
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080/admin.html'
const API = 'http://localhost:8088'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/'
const out = (l, v) => console.log(l, JSON.stringify(v))

async function findButton(tab, scope, text) {
  const box = JSON.parse(await tab.evalJS(`(() => {
    const b = [...document.querySelectorAll(${JSON.stringify(scope)})].find((x) => x.textContent.trim().includes(${JSON.stringify(text)}) && !x.disabled)
    if (!b) return JSON.stringify({ found: false })
    const r = b.getBoundingClientRect()
    return JSON.stringify({ found: true, x: r.left + r.width / 2, y: r.top + r.height / 2 })
  })()`))
  if (!box.found) throw new Error('没找到可点的按钮：' + text)
  return box
}

async function realClick(tab, x, y) {
  await tab.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, buttons: 0 })
  await tab.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 })
  await tab.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 })
}

/** 管理员登录：`#/admin/login` 一页两框一按钮，成功后前端自己 push 到 `#/admin`。 */
async function uiLogin(tab, account, password) {
  await tab.evalJS(`location.hash = '#/admin/login'; 1`)
  for (let t = 0; t < 8000; t += 250) {
    if (await tab.evalJS(`!!document.querySelector('input[name="adminAccount"]')`)) break
    await sleep(250)
  }
  await tab.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('input[name="adminAccount"]'), ${JSON.stringify(account)})
    set(document.querySelector('input[name="adminPassword"]'), ${JSON.stringify(password)})
    return 1
  })()`)
  const submit = await findButton(tab, 'button', '进入管理后台')
  await realClick(tab, submit.x, submit.y)
}

/** 读右栏「锁等待次数」KPI 与三枚依赖状态。 */
const readDash = `(() => {
  const kpi = [...document.querySelectorAll('.mp-kpi')].find(e => /锁等待次数/.test(e.textContent))
  const lockWait = kpi ? kpi.querySelector('.mp-kpi-v').innerText.replace(/\\s+/g, '') : null
  const fac = [...document.querySelectorAll('.mp-fac')].map(e => ({
    k: e.querySelector('.mp-fac-k').innerText.trim(),
    s: e.querySelector('.mp-fac-s').innerText.trim(),
    tone: (e.querySelector('.mp-fac-dot') || {}).className,
  }))
  return { lockWait, fac }
})()`

// 用户口径：只勾一个场景、并发 200、持续 30 秒。`PROBE_SCEN=all` 时**不发 scenarios**
//（后端缺省 = 8 个场景全开，用来对比「混合流量下才有的排队」）。
const SCEN = process.env.PROBE_SCEN || 'seckill'
const CONC = process.env.PROBE_CONC || '200'
const DUR = process.env.PROBE_DUR || '30'

const ADMIN_PWD = process.env.PROBE_ADMIN_PWD
if (!ADMIN_PWD) {
  console.error('缺 PROBE_ADMIN_PWD：PROBE_ADMIN_PWD=$(cat _work/admin-pass) node scripts/probes/probe-lock-wait-dashboard.mjs')
  process.exit(2)
}

/** 热力图是 ECharts 画在 canvas 上 —— DOM 里读不到格子颜色，只能把画布回读成像素。
 *
 *  口径：色阶两端 = 底色混 22% series[0]（蓝 ≈91）⇄ 纯 series[0]（#2f6fed，蓝 237）
 *  ⇒ 「深」就等价于**蓝通道高**（≥150）。⚠️ 第一版用饱和度判「有色」是错的：
 *  22% 混色在暗底上饱和度已有 0.69，深浅根本区分不出来（实测前后都 ≈9.2 万，白跑三趟）。
 *  ⚠️ 这个指标只作**旁证**：整块画布 60 列里攻击值只占一小截，且 tooltip 会被每秒
 *  `setOption` 收起 ⇒ 主判据仍是「悬停读 tooltip」+ 后端 `mall.lock.waited` 累计增量。 */
const heatPixels = `(() => {
  const sec = [...document.querySelectorAll('.mp-chart')].find(e => e.textContent.includes('锁竞争热力图'))
  if (!sec) return null
  const src = sec.querySelector('canvas')
  if (!src || !src.width) return { deep: null, reason: 'no-canvas' }
  const c = document.createElement('canvas'); c.width = src.width; c.height = src.height
  const g = c.getContext('2d'); g.drawImage(src, 0, 0)
  let d
  try { d = g.getImageData(0, 0, c.width, c.height).data } catch (e) { return { deep: null, reason: 'tainted' } }
  let bright = 0, n = 0, sumB = 0
  for (let i = 0; i < d.length; i += 4) {
    if (d[i + 3] < 200) continue
    const r = d[i], gg = d[i + 1], bb = d[i + 2]
    n++; sumB += bb
    // 深端 = 纯 series[0]（蓝 237）；浅端（0.22 混底色）蓝只有 ~91 ⇒ 蓝通道就是色阶的度量
    if (bb >= 150 && bb >= r + 50) bright++
  }
  return { bright, colored: n, meanB: n ? Math.round(sumB / n) : 0 }
})()`

/** 悬停热力图中央，读 ECharts 的 tooltip 文本 —— 格子到底带的是什么数，只有这一条能直读。 */
async function hoverHeat(tab) {
  // 命中点按 grid 几何算（HeatmapChart 的 grid 是 left 64 / right 56 / top 12 / bottom 56），
  // 取**最新一列**（右端）第 2 行（下单锁）。直接取画布中心会落在折线/空白上。
  const pts = await tab.evalJS(`(() => {
    const sec = [...document.querySelectorAll('.mp-chart')].find(e => e.textContent.includes('锁竞争热力图'))
    const c = sec && sec.querySelector('canvas')
    if (!c) return null
    const r = c.getBoundingClientRect()
    const gw = r.width - 64 - 56, gh = r.height - 12 - 56, rows = 4
    const out = []
    // 行 = 锁、列 = 拍：从最新一列往回试 3 列，每行都扫 —— 到底哪一行是 order-add 不能靠猜
    for (const col of [0.985, 0.95, 0.9]) {
      for (let k = 0; k < rows; k++) {
        out.push({ x: Math.round(r.left + 64 + gw * col), y: Math.round(r.top + 12 + gh / rows * (k + 0.5)) })
      }
    }
    return out
  })()`)
  if (!pts) return null
  const readTip = `(() => {
    const divs = [...document.querySelectorAll('div')].filter(e => e.style.position === 'absolute' && /等待/.test(e.innerText || ''))
    return divs.length ? divs[divs.length - 1].innerText.replace(/\\s+/g, ' ') : ''
  })()`
  // 面板每秒 setOption 一次、重建时 tooltip 会被收起 ⇒ 悬停后要**轮询**抓那不到 1 秒的窗口。
  let best = ''
  let bestN = -1
  for (const p of pts) {
    await tab.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: p.x - 20, y: p.y, buttons: 0 })
    for (let i = 0; i < 6; i++) {
      await tab.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: p.x - (i % 2 ? 3 : 0), y: p.y, buttons: 0 })
      await sleep(110)
      const t = await tab.evalJS(readTip)
      if (!t) continue
      const m = /等待\\s*(\\d+)\\s*次/.exec(t)
      const n = m ? Number(m[1]) : 0
      if (n > bestN) { bestN = n; best = t }
      break
    }
    if (bestN > 0) break
  }
  return best
}

const b = await open({ w: 1600, h: 1000, headed: false })
try {
  await b.goto(`${BASE}#/admin/login`, 2500)
  await uiLogin(b, 'admin', ADMIN_PWD)
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    if (/^#\/admin/.test(await b.evalJS('location.hash'))) break
  }
  const hash = await b.evalJS('location.hash')
  if (!/^#\/admin/.test(hash)) {
    const err = await b.evalJS(`(document.querySelector('.al-err') || {}).innerText || ''`)
    out('LOGIN', { ok: false, hash, err })
    process.exit(1)
  }

  await b.goto(`${BASE}#/admin/observe/metrics`, 6000)
  for (let t = 0; t < 12000; t += 500) {
    if (await b.evalJS(`!!document.querySelector('.mp-stack')`)) break
    await sleep(500)
  }
  if (!(await b.evalJS(`!!document.querySelector('.mp-stack')`))) {
    out('PAGE', { hash: await b.evalJS('location.hash'), body: (await b.evalJS('document.body.innerText')).slice(0, 300) })
    process.exit(1)
  }
  await sleep(3000)
  const before = await b.evalJS(readDash)
  const heatBefore = await b.evalJS(heatPixels)
  out('BEFORE', { ...before, heatBefore })
  await b.shot(`${OUT}lock-dash-before.png`)

  // 连跑多轮时，上一轮的 POST 会一直挂到「排空在途」之后才返回（实测 65–95 s）；
  // 新一轮若在排空前发起，面板会先空二十多秒——先喊停、把在途排干净再打，证据才可重复。
  await b.evalJS(`fetch('${API}/admin/observe/attack/stop', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + (sessionStorage.getItem('adminAuthToken') || '') }, body: '{}'
  }).then(r => r.status).catch(() => 0)`).catch(() => 0)
  await sleep(3000)

  // 后端累计值（actuator，不经前端）—— 与面板并行取证，防「面板漏拍」被当成「没发生」。
  const meter = async () => {
    const r = await fetch(`${API}/actuator/metrics/mall.lock.waited`).then((x) => x.json()).catch(() => null)
    const m = r && r.measurements && r.measurements[0]
    return m ? m.value : null
  }
  const meterBefore = await meter()

  // 打真并发：200 并发、25 秒、只勾秒杀抢购。**不 await**，让本进程能继续读页面。
  // ⚠️ 后端 `/attack/run` 收的是 **@RequestParam（表单串）**，且是**同步**跑完才回：
  //   第一版这里发了 JSON body ⇒ 六个参数全被当缺省（默认 60 秒、默认并发、8 个场景全开），
  //   拿到的「200 并发/25 秒/只跑秒杀」是假的。口径见 `api/observe.js:411-413`。
  await b.evalJS(`(() => {
    window.__atkSent = Date.now()
    const usp = new URLSearchParams({ name: 'ui-lock-check', concurrency: '${CONC}', durationSeconds: '${DUR}' })
    if ('${SCEN}' !== 'all') usp.set('scenarios', JSON.stringify([{ key: '${SCEN}', weight: 100 }]))
    window.__atk = fetch('${API}/admin/observe/attack/run', {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...(sessionStorage.getItem('adminAuthToken') ? { Authorization: 'Bearer ' + sessionStorage.getItem('adminAuthToken') } : {}) },
      body: usp.toString(),
    }).then(r => r.status + '@' + Date.now()).catch(e => 'ERR:' + e.message)
    return 'started'
  })()`)

  const samples = []
  const ticks = []
  let peak = 0
  let peakAt = -1
  let heatDuring = null
  let heatTip = ''
  let captured = false
  const snapshotDuring = async () => {
    // ① 先不滚动、立刻拍一张：右栏「锁等待次数」是**本拍**值，滚屏+悬停要十几秒，
    //    等热力图那段拍完，KPI 早翻到下一拍（大概率 0）了 —— 第一版就是这么丢的证据。
    await b.shot(`${OUT}lock-dash-during-kpi.png`)
    // ② 再滚到左栏最底下拍热力图（窗口 1000 高，图区 top≈714 已在折线下缘）
    await b.evalJS(`(() => { const s=document.querySelector('.mp-stack'); s.scrollTop=s.scrollHeight })()`)
    await sleep(800)
    await b.shot(`${OUT}lock-dash-during.png`)
    heatDuring = await b.evalJS(heatPixels)
    heatTip = await hoverHeat(b)
  }
  for (let i = 0; i < Number(DUR) + 6; i++) {
    await sleep(1000)
    const d = await b.evalJS(readDash)
    const n = Number(String(d.lockWait).replace(/[^\d]/g, '')) || 0
    samples.push(n)
    ticks.push(Date.now())
    if (n > peak) { peak = n; peakAt = i + 1 }
    // ⚠️ 等值的「第一次非零拍」再截图：本机 KPI 是**一阵一阵**的（撞上 Redis 中断/抢购爆点才冲高），
    //   固定在第 8 秒截，很可能正好截到 0。
    if (!captured && n > 0) { captured = true; await snapshotDuring() }
  }
  if (!captured) await snapshotDuring()
  const attackStatus = await b.evalJS('window.__atk')
  const atkSent = await b.evalJS('window.__atkSent')
  const meterAfter = await meter()
  await sleep(2000)
  const after = await b.evalJS(readDash)

  // 热力图：滚到图区并单独截一张（图在左栏底部）。
  await b.evalJS(`(() => { const s=document.querySelector('.mp-stack'); s.scrollTop=s.scrollHeight })()`)
  await sleep(1200)
  const heatBox = await b.evalJS(`(() => {
    const sec=[...document.querySelectorAll('.mp-chart')].find(e=>e.textContent.includes('锁竞争热力图'))
    if (!sec) return null
    const r=sec.getBoundingClientRect()
    return { t:Math.round(r.top), b:Math.round(r.bottom), l:Math.round(r.left), r:Math.round(r.right),
             sub:(sec.querySelector('.mp-chart-n')||{}).innerText||'' }
  })()`)
  await b.shot(`${OUT}lock-dash-heat.png`)

  out('AFTER', { attackStatus, atkSent, tickOffsets: ticks.map((t) => Math.round((t - atkSent) / 1000)),
    meterBefore, meterAfter, meterDelta: (meterAfter === null || meterBefore === null) ? null : meterAfter - meterBefore,
    peakWaitPerTick: peak, peakAtSecond: peakAt, samples, heatDuring, heatTip, ...after })
  out('HEAT_BOX', heatBox)
  out('VERDICT', {
    facilitiesGreen: before.fac.every(f => /正常/.test(f.s)) && after.fac.every(f => /正常/.test(f.s)),
    lockWaitRose: peak > 0,
    heatSection: !!heatBox,
    heatDeepened: !!(heatDuring && heatDuring.bright > (heatBefore.bright || 0) * 2 + 500),
    heatTooltipHasWait: /等待\s*[1-9]\d*\s*次/.test(heatTip || ''),
  })
} finally {
  await b.close()
}
