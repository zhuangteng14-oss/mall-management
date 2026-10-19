// b4-preview-visual.mjs —— 批四「预览视觉升维」取证（2026-10-18）
//
// 判据：
//   ① 指标列出现 3 张带 Sparkline 的 KPI 卡（QPS / 平均延迟 / 缓存命中率），柱数会随时间增长
//   ② 订单列是四节点流轨（不是纯文字），最多 10 行，压测时真的滚出新行
//   ③ 日志列保留纯文字，PROTECT 紫 / WARN 黄 / ERROR 红至少各出现一次（若本拍有）
//   ④ 500 并发演练期间主线程 Long Task 统计（>50ms 的条数与最长一条）
//   ⑤ 截图：升维后静态 + 500 并发演练中
//
// 用法：
//   PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" node scripts/probes/b4-preview-visual.mjs
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/batch4-1'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) {
  console.error('缺少 PROBE_ADMIN_PWD')
  process.exit(2)
}
fs.mkdirSync(EV, { recursive: true })

const SHOT = {
  static: `${EV}/b4-06-preview-upgraded.png`,
  drill: `${EV}/b4-07-preview-500-concurrency.png`,
  after: `${EV}/b4-08-preview-after-drill.png`,
}

const R = report('批四 · 预览视觉升维（KPI 卡 / 流轨 / 分级日志）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const b = await open({ w: 1600, h: 1100 })
async function waitFor(expr, timeoutMs = 20000, step = 300) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const v = await b.evalJS(expr).catch(() => null)
    if (v) return v
    await sleep(step)
  }
  return null
}

try {
  await b.goto(BASE + '/admin.html', 2500)
  const login = await b.evalJS(`(() => {
    const set = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    if (!acc || !pwd) return false
    set(acc, 'admin'); set(pwd, ${JSON.stringify(PWD)})
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x => x.textContent.trim() === '登录')
    if (btn) btn.click()
    return true
  })()`)
  ok('管理端登录提交', login === true)
  const adminHash = await waitFor(`/^#\\/admin/.test(location.hash) ? location.hash : ''`, 25000, 500)
  ok('进入管理端', !!adminHash, adminHash)

  await b.evalJS(`location.hash = '#/admin/observe/attack-sim'`)
  await waitFor(`!!document.querySelector('.lp')`, 25000)
  const live = await waitFor(`(() => {
    const xs = [...document.querySelectorAll('.lp-conn')]
    return xs.length === 3 && xs.every(x => x.classList.contains('live')) ? true : null
  })()`, 20000, 500)
  ok('三条流到 live', live === true)

  // ① KPI 卡 + Sparkline：等两拍，柱数应 > 0 且至少出现非零高度
  await sleep(3500)
  const kpi = await b.evalJS(`(() => {
    const cards = [...document.querySelectorAll('.lp-kpis .kpi')]
    return {
      cards: cards.length,
      labels: cards.map(c => (c.querySelector('.kpi-k') || {}).textContent || ''),
      bars: cards.map(c => c.querySelectorAll('.kpi-spark i').length),
      maxH: cards.map(c => Math.max(0, ...[...c.querySelectorAll('.kpi-spark i')].map(i => parseFloat(i.style.height) || 0))),
    }
  })()`)
  ok('① 指标列 3 张 KPI 卡', kpi && kpi.cards === 3, kpi && kpi.labels)
  ok('① 三张卡都有 Sparkline 柱（≥ 2 拍）', kpi && kpi.bars.every((n) => n >= 2), kpi && kpi.bars)
  ok('① 至少有柱子长出来（不是全 0 高）', kpi && kpi.maxH.some((h) => h > 0), kpi && kpi.maxH)

  // ③ 日志列：纯文字 + 严重度标签
  const log = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.lp-log li')]
    return {
      rows: rows.length,
      hasSev: rows.some(r => !!r.querySelector('.lp-sev')),
      sevs: [...new Set(rows.map(r => (r.className.match(/sev-(\\w+)/) || [])[1]).filter(Boolean))],
    }
  })()`)
  ok('③ 日志列仍是纯文字行并带严重度标签', log && log.rows > 0 && log.hasSev, log)

  await b.evalJS(`window.__lt = []; try {
    window.__ltObs = new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__lt.push(Math.round(e.duration)) })
    window.__ltObs.observe({ type: 'longtask', buffered: true })
  } catch (e) { window.__ltErr = String(e) }`)
  await b.evalJS(`document.querySelector('.lp').scrollIntoView({ block: 'center' })`)
  await sleep(500)
  await b.shot(SHOT.static)

  // ④ 500 并发演练
  const started = await b.evalJS(`(() => {
    const ins = [...document.querySelectorAll('.a-f-narrow input')]
    if (ins.length < 2) return { ok: false, why: 'inputs' }
    const set = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      s.call(el, String(v)); el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    set(ins[0], 500); set(ins[1], 20)
    const btn = [...document.querySelectorAll('button.a-btn-on')].find(x => /开始/.test(x.textContent))
    if (!btn || btn.disabled) return { ok: false, why: 'button', disabled: btn && btn.disabled }
    btn.click()
    return { ok: true, conc: ins[0].value, dur: ins[1].value }
  })()`)
  ok('④ 以 500 并发 / 20 秒启动演练', started && started.ok === true, started)

  const running = await waitFor(`!!document.querySelector('.a-prog')`, 20000, 300)
  ok('④ 页面进入演练中（进度条出现）', !!running)
  await sleep(7000)

  // 演练进行中：预览三列必须有真数据
  const during = await b.evalJS(`(() => ({
    moves: document.querySelectorAll('.lp-rail .lp-move').length,
    logs: document.querySelectorAll('.lp-log li').length,
    qps: (document.querySelector('.lp-kpis .kpi .kpi-v b') || {}).textContent || '',
    seqs: [...new Set([...document.querySelectorAll('.lp-log li')].map(r => (r.className.match(/sev-(\\w+)/) || [])[1]).filter(Boolean))],
    progress: (document.querySelector('.a-prog-t') || {}).textContent || '',
  }))()`)
  ok('④ 演练中订单流轨有新行', during.moves > 0, during.moves)
  ok('④ 演练中指标 QPS 有读数', during.qps && during.qps !== '—', during.qps)
  ok('④ 演练中日志列有新行', during.logs > 0, during.logs)
  console.log('   演练中严重度集合：', JSON.stringify(during.seqs))

  // ② 流轨结构 + 中文状态（只在真有 moves 时才检查；空态没有轨道是**对的**）
  const rail = await b.evalJS(`(() => {
    const t = document.querySelector('.lp-rail .lp-track')
    const rows = [...document.querySelectorAll('.lp-rail .lp-move')]
    const lit = rows.map(r => r.querySelectorAll('.lp-node.on').length)
    return {
      hasTrack: !!t,
      nodes: t ? t.querySelectorAll('.lp-node').length : 0,
      segs: t ? t.querySelectorAll('.lp-seg').length : 0,
      rows: rows.length,
      labels: rows.slice(0, 4).map(r => (r.querySelector('.lp-d') || {}).textContent.trim()),
      lit,
      capped: rows.length <= 10,
    }
  })()`)
  ok('② 订单列已换成流轨（4 节点 / 3 段）', !!rail && rail.hasTrack && rail.nodes === 4 && rail.segs === 3, rail)
  ok('② 状态文字是中文（不是 CREATED / PAID 原样）', !!rail && rail.labels.every((s) => !/[A-Z]{3,}/.test(s)), rail && rail.labels)
  ok('② 轨道节点真的亮了（至少一行有 ≥1 个实心节点）', !!rail && rail.lit.some((n) => n > 0), rail && rail.lit)
  ok('② 最多渲染 10 行', !!rail && rail.capped, rail && rail.rows)

  // ③ 严重度颜色：PROTECT 的标签色必须等于 `--protect` 的解析值（不是随手一个紫）
  const sevColor = await b.evalJS(`(() => {
    const row = document.querySelector('.lp-log li.sev-protect')
    if (!row) return { skip: 'no-protect-row' }
    const tag = row.querySelector('.lp-sev')
    const probe = document.createElement('i')
    probe.style.color = 'var(--protect)'
    document.body.appendChild(probe)
    const want = getComputedStyle(probe).color
    probe.remove()
    return { got: getComputedStyle(tag).color, want, text: tag.textContent }
  })()`)
  ok('③ PROTECT 标签用的是 --protect（紫）', sevColor && (sevColor.skip || (sevColor.got === sevColor.want && sevColor.text === 'PROTECT')), sevColor)

  await b.evalJS(`document.querySelector('.lp').scrollIntoView({ block: 'center' })`)
  await sleep(400)
  await b.shot(SHOT.drill)

  // 等演练结束（最多 70 秒）
  await waitFor(`!document.querySelector('.a-prog')`, 70000, 1000)
  const lt = await b.evalJS(`(() => ({
    err: window.__ltErr || '',
    count: (window.__lt || []).length,
    max: Math.max(0, ...(window.__lt || [])),
    over200: (window.__lt || []).filter(d => d > 200).length,
  }))()`)
  ok('④ 演练期间无 >1s 的长任务（主线程未被拖死）', !lt.err && lt.max < 1000, lt)
  ok('④ Long Task 数量在可接受范围（≤ 20 条）', !lt.err && lt.count <= 20, lt)

  const after = await b.evalJS(`(() => ({
    moves: document.querySelectorAll('.lp-rail .lp-move').length,
    logs: document.querySelectorAll('.lp-log li').length,
    result: (document.querySelector('.a-result, .a-drill-summary, .a-prog-t') || {}).textContent || '',
  }))()`)
  ok('④ 演练结束后预览仍有数据（没被清空）', after.moves > 0 && after.logs > 0, after)
  await b.shot(SHOT.after)
} catch (e) {
  console.log('探针中途异常：', (e && e.message) || e)
} finally {
  const bad = R.dump()
  console.log(`\n截图：\n  ${Object.values(SHOT).join('\n  ')}`)
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
