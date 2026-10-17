// c3b7-drawer-rail-overlap.mjs —— 块 7 · 2026-10-17「链路抽屉与左侧栏文字重叠」复现/验收探针。
//
// 现象（用户报）：点 ③ 任一行打开 ⑤ 链路展开后，⑤ 的标题 / 链路文字与左边 ② 过滤栏
// 的文字叠在一起。
// 根因：⑤ 抽屉最高占 `.ev-frame` 的 42% ⇒ `.ev-body`（唯一伸缩行）只剩一半高；② 栏里
// 「口径 / 严重度 / 全部打开」都是 flex: 0 0 auto（压不下去），而 `.ev-rail` 默认
// overflow: visible ⇒ 子块从栏底溢出去，直接画在 ⑤ 的区域上。1440×900 勉强放得下，
// 1440×800 / 1366×768 必现。
//
// 本探针固定用 1440×800（真相是「窗口矮一点就必现」）：
//   ① 打开日志页 → 点一条带 traceId 的行，拉起 ⑤；
//   ② 把 `.ev-rail` 的内联 overflow 改回 visible ⇒ 复现修复前形态，截图 before；
//   ③ 清掉内联样式（回到修复后的 CSS）⇒ 截图 after；
//   ④ 判据：修复后 overflowY !== visible；栏内容超出时栏自身可滚；
//      滚到底后「严重度」与「全部打开 / 清筛选」完整落在栏的可视区内。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'
const W = 1440
const H = 800

const out = { at: new Date().toISOString(), viewport: { W, H }, checks: {}, samples: {} }
const fail = []
function check(name, ok, detail) {
  out.checks[name] = { ok: !!ok, detail }
  if (!ok) fail.push(name + ': ' + JSON.stringify(detail))
}

const b = await open({ w: W, h: H })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => { try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {} })()`,
  })
  await b.goto(`${WEB}/admin.html?drail=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(3000)

  // ① 点一条带 traceId 的行（tabindex 的那类），拉起 ⑤。
  const clicked = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('#ev-list .log-line')]
    const r = rows.find((x) => x.getAttribute('tabindex') !== null) || rows[0]
    if (!r) return null
    r.click()
    const frame = document.querySelector('.ev-frame')
    const s = frame && frame.__vueParentComponent && frame.__vueParentComponent.setupState
    return {
      text: (r.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 70),
      drawerOpen: s ? s.drawerOpen : null,
      hasTrace: s ? !!s.openedTrace : null,
      hasEvent: s ? !!s.openedEvent : null,
    }
  })()`)
  out.samples.clicked = clicked

  // ② 复现修复前：内联 overflow: visible（其余代码不变）⇒ 左侧栏底部溢出到 ⑤ 上。
  const before = await b.evalJS(`(() => {
    const rail = document.querySelector('.ev-rail')
    rail.style.overflow = 'visible'
    rail.style.overflowX = 'visible'
    rail.style.overflowY = 'visible'
    const cs = getComputedStyle(rail)
    const rr = rail.getBoundingClientRect()
    const hd = document.querySelector('.ev-drawer .hd').getBoundingClientRect()
    const sev = document.querySelector('.ev-rail .sev').getBoundingClientRect()
    const foot = document.querySelector('.ev-rail .foot').getBoundingClientRect()
    return {
      overflowY: cs.overflowY,
      rail: { y: +rr.y.toFixed(1), b: +rr.bottom.toFixed(1) },
      drawerHd: { y: +hd.y.toFixed(1), b: +hd.bottom.toFixed(1) },
      sev: { y: +sev.y.toFixed(1), b: +sev.bottom.toFixed(1) },
      foot: { y: +foot.y.toFixed(1), b: +foot.bottom.toFixed(1) },
      sevSpill: +(sev.bottom - rr.bottom).toFixed(1),
      footSpill: +(foot.bottom - rr.bottom).toFixed(1),
    }
  })()`)
  out.samples.before = before
  await b.evalJS(`(() => { document.body.style.zoom = '1.0001'; void document.body.offsetHeight; document.body.style.zoom = ''; return true })()`)
  await sleep(350)
  await b.shot(EV + '3a4h-drawer-rail-before.png')

  // ③ 回到修复后的样式。
  const after = await b.evalJS(`(() => {
    const rail = document.querySelector('.ev-rail')
    rail.style.overflow = ''
    rail.style.overflowX = ''
    rail.style.overflowY = ''
    const cs = getComputedStyle(rail)
    const rr = rail.getBoundingClientRect()
    const sh = rail.scrollHeight, ch = rail.clientHeight
    const atTop = { scrollTop: rail.scrollTop }
    rail.scrollTop = 99999
    const st = rail.scrollTop
    const sev = document.querySelector('.ev-rail .sev').getBoundingClientRect()
    const foot = document.querySelector('.ev-rail .foot').getBoundingClientRect()
    const rr2 = rail.getBoundingClientRect()
    const inside = (r) => r.top >= rr2.top - 0.6 && r.bottom <= rr2.bottom + 0.6
    return {
      overflowY: cs.overflowY,
      rail: { y: +rr.y.toFixed(1), b: +rr.bottom.toFixed(1) },
      scrollHeight: sh,
      clientHeight: ch,
      scrollable: sh > ch,
      atTop,
      scrolledTo: st,
      sevInsideAfterScroll: inside(sev),
      footInsideAfterScroll: inside(foot),
      sevBox: { y: +sev.y.toFixed(1), b: +sev.bottom.toFixed(1) },
      footBox: { y: +foot.y.toFixed(1), b: +foot.bottom.toFixed(1) },
      drawerHd: (() => { const r = document.querySelector('.ev-drawer .hd').getBoundingClientRect(); return { y: +r.y.toFixed(1), b: +r.bottom.toFixed(1) } })(),
    }
  })()`)
  out.samples.after = after
  await sleep(350)
  await b.shot(EV + '3a4h-drawer-rail-after.png')

  check('drawer-opened-by-click', !!(clicked && clicked.drawerOpen && (clicked.hasTrace || clicked.hasEvent)), clicked)
  check('before-overflow-visible', before.overflowY === 'visible', before)
  check('before-spill-reproduced', before.sevSpill > 0 || before.footSpill > 0,
    { sevSpill: before.sevSpill, footSpill: before.footSpill })
  check('after-overflow-not-visible', after.overflowY !== 'visible', { overflowY: after.overflowY })
  check('after-rail-scrollable', !after.scrollable || (after.scrolledTo > 0), after.scrollable
    ? { scrollHeight: after.scrollHeight, clientHeight: after.clientHeight, scrolledTo: after.scrolledTo }
    : { note: '内容放得下，无需滚动' })
  check('after-foot-reachable', after.footInsideAfterScroll, after.footBox)
  check('after-sev-reachable', after.sevInsideAfterScroll, after.sevBox)

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  await b.close()
}
