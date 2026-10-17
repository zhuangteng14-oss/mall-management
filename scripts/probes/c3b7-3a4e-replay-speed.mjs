// c3b7-3a4e-replay-speed.mjs —— 块 7 · 2026-10-17「回放倍速 1x/2x/5x/10x，默认 1x」验收探针。
//
// 判据：
//   ① 回放横幅里出现四个倍速按钮 1x / 2x / 5x / 10x，**默认高亮 1x**；
//   ② 点 5x 后高亮切换，时钟倍率文字变 5×，**进度不回零**（前后 cursor 单调不减）；
//   ③ 再点回 1x，进度继续走、不回零；
//   ④ 截图横幅（含四个按钮与当前档位高亮）。
//
// 数据：真后端 + 真台账。取演练记录列表里最新一条（id 82，批次 af539eee-…）的回放。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'

const out = { at: new Date().toISOString(), checks: {}, samples: {} }
const fail = []
function check(name, ok, detail) {
  out.checks[name] = { ok: !!ok, detail }
  if (!ok) fail.push(name + ': ' + JSON.stringify(detail))
}

const b = await open({ w: 1440, h: 900 })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => {
      try {
        sessionStorage.setItem('loginUsername', 'ops_duan')
        sessionStorage.setItem('customerid', 'qa7PmpUojO')
        sessionStorage.setItem('role', '3')
      } catch (e) {}
    })()`,
  })
  await b.goto(`${WEB}/admin.html?speed=${Date.now()}#/admin/observe/attack-sim`, 6000)
  await sleep(2500)

  // 展开最新一条演练记录并点「回放」。
  const clicked = await b.evalJS(`(async () => {
    const li = [...document.querySelectorAll('.a-ev')][0]
    if (!li) return { ok: false, why: 'no .a-ev' }
    if (!li.querySelector('.a-ev-body')) {
      li.querySelector('.a-ev-head').click()
      await new Promise((r) => setTimeout(r, 500))
    }
    const btn = [...li.querySelectorAll('.a-rp-in button')].find((x) => x.textContent.includes('回放') && !x.disabled)
    if (!btn) return { ok: false, why: 'no replay button', text: li.innerText.slice(0, 200) }
    btn.click()
    return { ok: true, record: li.innerText.replace(/\\s+/g, ' ').slice(0, 160) }
  })()`)
  out.samples.clicked = clicked
  check('replay-started', clicked.ok, clicked)

  // 等横幅出现并开始走。
  let banner = null
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    banner = await b.evalJS(`(() => {
      const rb = document.querySelector('.a-rb')
      if (!rb) return null
      const q = (s) => { const el = rb.querySelector(s); return el ? el.textContent.trim() : '' }
      return {
        buttons: [...rb.querySelectorAll('.a-rb-sp')].map((x) => x.textContent.trim()),
        active: (rb.querySelector('.a-rb-sp.on') || {}).textContent || '',
        speedText: q('.a-rb-speed'),
        prog: (rb.querySelector('.a-rb-prog b') || {}).textContent || '',
        count: (rb.querySelector('.a-rb-prog') || {}).textContent || '',
        clock: q('.a-rb-clock b'),
        state: q('.a-rb-state'),
      }
    })()`)
    if (banner && /\d/.test(banner.prog)) break
  }
  out.samples.initial = banner
  check('banner-present', !!banner, banner)
  check('four-speed-buttons', banner && banner.buttons.join('|') === '1x|2x|5x|10x', banner && banner.buttons)
  check('default-1x', banner && banner.active === '1x' && banner.speedText.includes('1×'), banner)
  check('initial-progress-moves', banner && parseInt(banner.prog.replace(/[^0-9]/g, ''), 10) > 0, banner)

  const cursor = () => b.evalJS(`(() => {
    const rb = document.querySelector('.a-rb')
    const rp = rb && rb.querySelector('.a-rb-prog b')
    return rp ? parseInt(rp.textContent.replace(/[^0-9]/g, ''), 10) || 0 : 0
  })()`)
  const before5 = await cursor()
  const clickSpeed = (label) => b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.a-rb .a-rb-sp')].find((x) => x.textContent.trim() === ${JSON.stringify(label)})
    if (!btn) return false
    btn.click()
    return true
  })()`)

  check('click-5x', await clickSpeed('5x'), { label: '5x' })
  await sleep(1200)
  const after5State = await b.evalJS(`(() => {
    const rb = document.querySelector('.a-rb')
    if (!rb) return null
    return {
      active: (rb.querySelector('.a-rb-sp.on') || {}).textContent || '',
      speedText: ((rb.querySelector('.a-rb-speed') || {}).textContent || '').trim(),
      prog: ((rb.querySelector('.a-rb-prog b') || {}).textContent || '').trim(),
      clock: ((rb.querySelector('.a-rb-clock b') || {}).textContent || '').trim(),
      speed: (document.querySelector('.ev-frame') && document.querySelector('.ev-frame').__vueParentComponent
        ? null : null),
    }
  })()`)
  const after5 = await cursor()
  out.samples.after5x = { ...after5State, cursor: after5, before: before5 }
  check('5x-active', after5State && after5State.active === '5x' && after5State.speedText.includes('5×'), after5State)
  check('5x-progress-not-reset', after5 >= before5, { before: before5, after: after5 })
  check('5x-progress-advances', after5 > before5, { before: before5, after: after5 })

  // ⚠️ 截图前先暂停：回放仍在 1,000+ 条/秒地改 DOM 时，CDP 截到的是合成层
  //    （左侧滚动栏 / 顶部状态条分属不同层）的**不同帧**，会出现「顶部 0.8% 而左栏防护 0」
  //    这种只存在于截图里的假不一致。暂停 + 等一拍，让整页落在同一帧上再截。
  const clickBanner = (label) => b.evalJS(`(() => {
    const rb = document.querySelector('.a-rb')
    if (!rb) return false
    const btn = [...rb.querySelectorAll('.a-rb-btn')].find((x) => x.textContent.trim() === ${JSON.stringify(label)})
    if (!btn) return false
    btn.click()
    return true
  })()`)
  await clickBanner('暂停')
  await sleep(600)
  const settled5 = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
    const sum = Object.values(s.rawWindow.bySev).reduce((a, b) => a + b, 0)
    const txt = (x) => ((x && x.innerText) || '').replace(/\\s+/g, ' ').trim()
    return {
      n: s.rawWindow.n, sum, sev: { ...s.rawWindow.bySev },
      sessionSev: { ...s.sessionBySev },
      protectRate: s.protectRate,
      failRate: s.failRate,
      left: [...document.querySelectorAll('.ev-rail .sev .sevchip')].map(txt),
      right: [...document.querySelectorAll('.ev-bars.sev .ev-bar')].map(txt),
      prot: txt(document.querySelector('.cell.err .prot')),
      fail: txt(document.querySelector('.cell.err b')),
    }
  })()`)
  out.samples.settled5x = settled5
  check('stats-consistent-when-paused', settled5.n === settled5.sum
    && settled5.left.join('|').replace(/[ ,]/g, '') === settled5.right.join('|').replace(/[ ,]/g, ''),
    settled5)

  // ⚠️ 截图前的最后一关：暂停后仍出现过「顶部 0.8% 而左栏防护 0」的**陈旧合成层**。
  //    这里先强制作一次全页重绘（临时改 zoom 触发 layout+paint，再恢复），
  //    再从 DOM 读一遍让「顶部数 = 左栏合计」，最后才截；截完再读一次留证。
  const settleShot = async (file) => {
    await sleep(1000)
    await b.evalJS(`(() => {
      const b = document.body
      b.style.zoom = '1.0001'
      void b.offsetHeight
      b.style.zoom = ''
      void b.offsetHeight
      return true
    })()`)
    await sleep(400)
    const dom = await b.evalJS(`(() => {
      const txt = (x) => ((x && x.innerText) || '').replace(/\\s+/g, ' ').trim()
      const num = (s) => parseInt(String(s).replace(/[^0-9]/g, ''), 10) || 0
      const chips = [...document.querySelectorAll('.ev-rail .sev .sevchip')].map(txt)
      return {
        topN: num(txt(document.querySelector('.cell b'))),
        chips,
        sum: chips.reduce((a, s) => a + num(s), 0),
        fail: txt(document.querySelector('.cell.err b')),
        prot: txt(document.querySelector('.cell.err .prot')),
        prog: txt(document.querySelector('.a-rb-prog b')),
      }
    })()`)
    await b.shot(file)
    const after = await b.evalJS(`(() => {
      const txt = (x) => ((x && x.innerText) || '').replace(/\\s+/g, ' ').trim()
      return {
        topN: txt(document.querySelector('.cell b')),
        chips: [...document.querySelectorAll('.ev-rail .sev .sevchip')].map(txt),
        prot: txt(document.querySelector('.cell.err .prot')),
      }
    })()`)
    return { before: dom, after }
  }
  const shot5 = await settleShot(EV + '3a4e-replay-speed-5x.png')
  out.samples.shot5 = shot5
  check('shot5-dom-consistent', shot5.before.topN === shot5.before.sum, shot5)
  await clickBanner('继续')
  await sleep(250)

  // 回 1x：进度仍不回零。
  check('click-1x', await clickSpeed('1x'), { label: '1x' })
  await sleep(800)
  const after1State = await b.evalJS(`(() => {
    const rb = document.querySelector('.a-rb')
    return {
      active: (rb.querySelector('.a-rb-sp.on') || {}).textContent || '',
      speedText: ((rb.querySelector('.a-rb-speed') || {}).textContent || '').trim(),
      prog: ((rb.querySelector('.a-rb-prog b') || {}).textContent || '').trim(),
      state: ((rb.querySelector('.a-rb-state') || {}).textContent || '').trim(),
    }
  })()`)
  const after1 = await cursor()
  out.samples.after1x = { ...after1State, cursor: after1, before: after5 }
  check('back-to-1x-active', after1State.active === '1x' && after1State.speedText.includes('1×'), after1State)
  check('1x-progress-not-reset', after1 >= after5, { after5, after1 })
  // 同一原因：暂停后再截 1x。
  await clickBanner('暂停')
  await sleep(600)
  const shot1 = await settleShot(EV + '3a4e-replay-speed-1x.png')
  out.samples.shot1 = shot1
  check('shot1-dom-consistent', shot1.before.topN === shot1.before.sum, shot1)

  // 收尾：退出（不把回放会话留给下一位用户）。
  await b.evalJS(`(() => {
    const rb = document.querySelector('.a-rb')
    if (!rb) return
    const x = [...rb.querySelectorAll('.a-rb-btn')].find((y) => y.textContent.trim() === '退出回放')
    if (x) x.click()
  })()`)
  await sleep(300)
  await b.evalJS(`(() => {
    const rb = document.querySelector('.a-rb')
    if (!rb) return
    const x = [...rb.querySelectorAll('.a-rb-btn')].find((y) => y.textContent.trim() === '退出回放')
    if (x) x.click()
  })()`)
  await sleep(500)

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  await b.close()
}
