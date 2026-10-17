// c3b7-rail-motion.mjs —— 块 7 · 2026-10-17「流轨方向 + 取消支线」动效验收探针。
//
// 用户交付验收 ②：
//   · 轨道点阵 / 光点必须**从左向右**（正向）流动；
//   · 订单取消必须有**逆向 / 向下**连线动画（待付款 / 待发货 → 已取消），
//     大批量取消时不能视觉假死。
//
// 场景：状态流转页 + 「模拟日常」假数据源（纯前端、不入库；接口见 `api/demo-stream.js`）。
//   ⚠️ 必须用演示流：真流在大盘空闲时一秒不到 1 条 move，探针会等成「没动画」的假红。
//
// 判据：
//   ① 页面哨兵 `data-theme === 'admin'`；
//   ② 主链粒子的 `left` 在 200ms 内**增大**（正向）；
//   ③ 取消粒子同时满足 `left` 与 `top` 增大（向右下坠落，不是主链直线）；
//   ④ 4.5 秒采样窗口内主链粒子出现帧数 ≥ 10、取消粒子出现帧数 ≥ 1；
//   ⑤ 逐帧截图落 `_work/rail-frames/`，由外层 Pillow 合成 GIF。
//
// 产物：stdout JSON + 帧序列（GIF 由 `python` 的 Pillow 合成，见调用命令）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const WEB = 'http://localhost:8080'
const FRAMES = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/rail-frames/'
const FRAME_MS = 100
const FRAME_COUNT = 46

const out = { at: new Date().toISOString(), checks: {}, samples: {} }
const fail = []
function check(name, ok, detail) {
  out.checks[name] = { ok: !!ok, detail }
  if (!ok) fail.push(name + ': ' + JSON.stringify(detail))
}

async function sampleRise(sel, keys, tries = 30) {
  for (let i = 0; i < tries; i += 1) {
    const s = await b.evalJS(`(async () => {
      const el = document.querySelector(${JSON.stringify(sel)})
      if (!el) return null
      const read = () => { const r = el.getBoundingClientRect(); return { left: r.left, top: r.top } }
      const a = read()
      await new Promise((r) => setTimeout(r, 220))
      const b = read()
      return { a, b }
    })()`)
    if (s) return s
    await sleep(120)
  }
  return null
}

const b = await open({ w: 1440, h: 900 })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => { try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {} })()`,
  })
  await b.goto(`${WEB}/admin.html?rail=${Date.now()}#/admin/observe/timeline`, 5000)
  await sleep(1500)

  const theme = await b.evalJS(`document.documentElement.getAttribute('data-theme')`)
  out.samples.theme = theme
  check('admin-shell-sentinel', theme === 'admin', theme)

  // 切「模拟日常」（数据源入口必须保留，也是本探针的数据源）。
  const clicked = await b.evalJS(`(() => {
    const btns = [...document.querySelectorAll('.a-dm .a-seg-b')]
    const b = btns.find((x) => x.textContent.trim() === '模拟日常')
    if (!b) return false
    b.click()
    return true
  })()`)
  await sleep(2200)
  const demoOn = await b.evalJS(`!!document.querySelector('.a-dm.is-on')`)
  out.samples.demoOn = demoOn
  check('demo-source-on', clicked && demoOn, { clicked, demoOn })

  // 轨道在视口内（不在就滚进来；截图 clip 用视口坐标）。
  await b.evalJS(`(() => {
    const l = document.querySelector('.a-of-lane')
    if (l) l.scrollIntoView({ block: 'center' })
    return true
  })()`)
  await sleep(500)

  // ⚠️ `Page.captureScreenshot` 的 clip 用**文档坐标**（不是视口坐标）——
  //    scrollIntoView 之后必须把 scrollX / scrollY 加回去，否则截到页头去。
  const rect = await b.evalJS(`(() => {
    const r = document.querySelector('.a-of-lane')
    if (!r) return null
    const b = r.getBoundingClientRect()
    return {
      x: b.x + window.scrollX, y: b.y + window.scrollY,
      width: b.width, height: b.height,
      viewportY: b.y, scrollY: window.scrollY,
    }
  })()`)
  out.samples.laneRect = rect
  check('lane-visible', rect && rect.width > 300 && rect.height >= 120, rect)

  const main = await sampleRise('.a-of-particle', ['left'])
  out.samples.mainParticle = main
  check('main-particle-moves-right',
    !!main && main.b.left > main.a.left + 0.5, main)

  const cancel = await sampleRise('.a-of-cancel-particle', ['left', 'top'])
  out.samples.cancelParticle = cancel
  check('cancel-particle-moves-right-down',
    !!cancel && cancel.b.left > cancel.a.left + 0.5 && cancel.b.top > cancel.a.top + 0.5, cancel)

  // 逐帧截图：每帧同时记「这一帧有没有主链 / 取消粒子」。
  fs.mkdirSync(FRAMES, { recursive: true })
  for (const f of fs.readdirSync(FRAMES)) fs.unlinkSync(FRAMES + f)
  let mainFrames = 0
  let cancelFrames = 0
  for (let i = 0; i < FRAME_COUNT; i += 1) {
    const hit = await b.evalJS(`(() => ({
      main: document.querySelectorAll('.a-of-particle').length,
      cancel: document.querySelectorAll('.a-of-cancel-particle').length,
    }))()`)
    if (hit.main > 0) mainFrames += 1
    if (hit.cancel > 0) cancelFrames += 1
    const shot = await b.send('Page.captureScreenshot', {
      format: 'png',
      clip: { x: rect.x, y: rect.y, width: rect.width, height: rect.height, scale: 1 },
    })
    fs.writeFileSync(FRAMES + `f${String(i).padStart(3, '0')}.png`, Buffer.from(shot.data, 'base64'))
    await sleep(FRAME_MS)
  }
  out.samples.frameStats = { frames: FRAME_COUNT, mainFrames, cancelFrames, frameMs: FRAME_MS }
  check('main-particles-present', mainFrames >= 10, out.samples.frameStats)
  check('cancel-particles-present', cancelFrames >= 1, out.samples.frameStats)

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  await b.close()
}
