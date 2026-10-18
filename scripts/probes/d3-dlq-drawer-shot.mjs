// d3-dlq-drawer-shot.mjs —— 死信详情抽屉取证（2026-10-18 · D3 手验补全）
//
// 用途：真 dev server（8080）+ 真后端（8088）登录管理员 → 指标页 → 点「死信数量」卡 →
//      断言右侧抽屉出现、行数 = 库里条数、点行能展开 RawBody → 截图。
// 前提：库里已插入若干非法 JSON 死信（本次 3 条，见 _work/d3/dlq-list.json）。
// 凭据：**只**从环境变量 PROBE_ADMIN_PWD 读（本地来源 `_work/admin-pass`，先 tr -d '\r\n'）。
//
// 用法：
//   PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" \
//     node scripts/probes/d3-dlq-drawer-shot.mjs <out-prefix>
// 产物：docs/agent/evidence/c3-d3/<out-prefix>.png（+ 同前缀 .txt 由调用方重定向）
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const stamp = process.argv[2] || 'd3-dlq-drawer'
const OUT = 'docs/agent/evidence/c3-d3'
const BASE = process.env.PROBE_BASE || 'http://localhost:8080'
const EXPECT_EMPTY = process.env.EXPECT_EMPTY === '1'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) {
  console.error('缺少 PROBE_ADMIN_PWD（不要写进命令行字面量，从 _work/admin-pass 读）')
  process.exit(2)
}
if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true })

const b = await open({ w: 1600, h: 1000, headed: false })
const out = { stamp, base: BASE, steps: [] }
try {
  await b.goto(BASE + '/admin.html', 2500)

  // ── 登录（与 b7-metrics-stock-shot.mjs 同款；管理入口是 admin.html）──────────
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
      || [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '登录')
    if (!btn) return { ok: false, buttons: [...document.querySelectorAll('button')].map(x => x.textContent.trim()) }
    btn.click()
    return { ok: true, pwdLen: pwd.value.length }
  })()`)
  out.steps.push({ login })
  if (!login.ok) throw new Error('登录页输入框/按钮没找到：' + JSON.stringify(login))

  let hash = ''
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    hash = await b.evalJS('location.hash')
    if (/^#\/admin/.test(hash)) break
  }
  out.steps.push({ afterLoginHash: hash })
  if (!/^#\/admin/.test(hash)) throw new Error('登录后没有落进 #/admin，当前 ' + hash)

  // ── 指标页：等死信卡出现（SSE 首帧到）──────────────────────────────────────
  await b.evalJS(`location.hash = '#/admin/observe/metrics'`)
  let card = null
  for (let i = 0; i < 60; i++) {
    await sleep(500)
    card = await b.evalJS(`(() => {
      const el = document.querySelector('.a-stat--dlq')
      if (!el) return null
      const cs = getComputedStyle(el)
      return {
        text: el.innerText.trim(),
        count: (el.querySelector('b') || {}).innerText || null,
        cls: el.className,
        borderColor: cs.borderTopColor,
        opacity: cs.opacity,
        clickable: el.tagName.toLowerCase(),
      }
    })()`)
    if (card) break
  }
  out.card = card
  if (!card) throw new Error('没有找到 .a-stat--dlq（指标页没渲染或页面没进）')

  // ── 点卡 → 抽屉 ────────────────────────────────────────────────────────────
  const clicked = await b.evalJS(`(() => {
    const el = document.querySelector('.a-stat--dlq')
    el.click()
    return true
  })()`)
  out.steps.push({ clicked })

  let drawer = null
  for (let i = 0; i < 40; i++) {
    await sleep(300)
    drawer = await b.evalJS(`(() => {
      const d = document.querySelector('.a-of-drawer')
      if (!d) return null
      const rows = [...document.querySelectorAll('.a-dlq-i')]
      return {
        title: (d.querySelector('.a-of-drawer-hd h3') || {}).innerText || null,
        subtitle: (d.querySelector('.a-of-drawer-hd p') || {}).innerText || null,
        rows: rows.length,
        empty: (document.querySelector('.a-dlq .a-empty') || {}).innerText || null,
        err: (document.querySelector('.a-dlq-tip.is-bad') || {}).innerText || null,
        items: rows.map((li) => ({
          time: ((li.querySelector('.a-dlq-time') || {}).innerText || '').trim(),
          trace: ((li.querySelector('.a-dlq-trace') || {}).innerText || '').trim(),
          errClass: ((li.querySelector('.a-dlq-err') || {}).innerText || '').trim(),
          errTitle: (li.querySelector('.a-dlq-err') || {}).title || '',
          errorText: ((li.querySelector('.a-dlq-text') || {}).innerText || '').trim(),
          errorTitle: (li.querySelector('.a-dlq-text') || {}).title || '',
        })),
      }
    })()`)
    if (drawer && (drawer.rows > 0 || drawer.empty || drawer.err)) break
  }
  out.drawer = drawer
  if (!drawer) throw new Error('点了死信卡但抽屉没有出现（.a-of-drawer 不存在）')

  if (EXPECT_EMPTY) {
    // 空态判据：逐字「当前没有处理失败的死信消息」，且不能把「读不到」伪装成 0 条。
    if (drawer.rows !== 0) throw new Error(`预期空表，实际 ${drawer.rows} 行`)
    if (drawer.empty !== '当前没有处理失败的死信消息') {
      throw new Error('空态文案不符：' + JSON.stringify(drawer.empty))
    }
  } else {
    // ── 错误中文化判据（D3 手验反馈）：三类各自逐字断言 ──────────────────────
    const of = (k) => drawer.items.filter((x) => x.errClass === k)
    const fmt = of('格式错误')
    const db = of('数据库错误')
    const sys = of('系统异常')
    if (!fmt.length) throw new Error('没有「格式错误」行：' + JSON.stringify(drawer.items.map((x) => x.errClass)))
    if (!db.length) throw new Error('没有「数据库错误」行：' + JSON.stringify(drawer.items.map((x) => x.errClass)))
    if (!sys.length) throw new Error('没有「系统异常」行：' + JSON.stringify(drawer.items.map((x) => x.errClass)))
    if (!fmt.every((x) => x.errorText === '【格式错误】消息体不是合法的 JSON 格式')) {
      throw new Error('格式错误文案不符：' + JSON.stringify(fmt.map((x) => x.errorText)))
    }
    if (!db.every((x) => x.errorText === '【数据库错误】落库时发生异常')) {
      throw new Error('数据库错误文案不符：' + JSON.stringify(db.map((x) => x.errorText)))
    }
    if (!sys.every((x) => x.errorText.startsWith('【系统异常】具体堆栈：') && x.errorText.length > '【系统异常】具体堆栈：'.length)) {
      throw new Error('系统异常文案不符：' + JSON.stringify(sys.map((x) => x.errorText)))
    }
    // 英文原文不能消失：它进了 title（排障用），页面正文只给中文。
    if (!fmt.some((x) => /JsonParseException/.test(x.errTitle))) {
      throw new Error('格式错误的 title 里丢了原始类名：' + JSON.stringify(fmt.map((x) => x.errTitle)))
    }

    // ── 点「格式错误」那行 → 展开 RawBody（挑有原始消息体的那行，别挑到无 RawBody 的）──
    const expand = await b.evalJS(`(() => {
      const item = [...document.querySelectorAll('.a-dlq-i')]
        .find((li) => ((li.querySelector('.a-dlq-err') || {}).innerText || '').trim() === '格式错误')
      if (!item) return { ok: false }
      item.querySelector('.a-dlq-row').click()
      return { ok: true }
    })()`)
    await sleep(500)
    const raw = await b.evalJS(`(() => {
      const pre = document.querySelector('.a-dlq-raw')
      return pre ? { text: pre.innerText, height: pre.getBoundingClientRect().height } : null
    })()`)
    out.expand = { clicked: expand, raw }
    if (!raw || !raw.text) throw new Error('点行后没有展开 .a-dlq-raw')
  }

  // 截图（视口内整个抽屉；再补一张全页作对照）
  const shot = `${OUT}/${stamp}.png`
  await b.shot(shot)
  const full = `${OUT}/${stamp}-full.png`
  await b.shotFull(full)
  out.shots = [shot, full]
  console.log(JSON.stringify(out, null, 2))
  console.log('SHOT ' + shot)
  console.log('SHOT ' + full)
} catch (e) {
  console.log('FAILED ' + (e && e.message))
  console.log(JSON.stringify(out, null, 2))
  process.exitCode = 1
} finally {
  await b.close().catch(() => {})
}
