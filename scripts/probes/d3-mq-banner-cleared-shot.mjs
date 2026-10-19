// d3-mq-banner-cleared-shot.mjs —— MQ 黄条撤销取证（2026-10-18 · D3 手验反馈）
//
// 背景：VM 上 RabbitMQ 4.0.5 正常，但本机后端启动时没带 `MALL_MQ_PASSWORD`
//      （application-dev.properties 是 `${MALL_MQ_PASSWORD:}`）⇒ 空口令被 broker
//      `ACCESS_REFUSED`，探针如实报「MQ 不可达」。带上口令重启后应满足：
//        · 后端日志出现「[EVENT] MQ 已连接」；
//        · 指标页健康度 = 健康，顶部**没有** MQ 告警条；
//        · gauge `mqUp = 1.0`。
// 本探针只断言页面侧（后端日志与 SSE 帧见同名 .txt 证据），并截图。
//
// 凭据：**只**从环境变量 PROBE_ADMIN_PWD 读（本地来源 `_work/admin-pass`）。
// 用法：
//   PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" \
//     node scripts/probes/d3-mq-banner-cleared-shot.mjs [out-prefix]
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const stamp = process.argv[2] || 'd3-mq-banner-cleared'
const OUT = 'docs/agent/evidence/c3-d3'
const BASE = process.env.PROBE_BASE || 'http://localhost:8080'
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
  const login = await b.evalJS(`(() => {
    const set = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    if (!acc || !pwd) return { ok: false }
    set(acc, 'admin'); set(pwd, ${JSON.stringify(PWD)})
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')]
      .find(x => x.textContent.trim() === '登录')
    if (!btn) return { ok: false }
    btn.click()
    return { ok: true }
  })()`)
  out.steps.push({ login })
  if (!login.ok) throw new Error('登录失败')

  let hash = ''
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    hash = await b.evalJS('location.hash')
    if (/^#\/admin/.test(hash)) break
  }
  if (!/^#\/admin/.test(hash)) throw new Error('登录后没有落进 #/admin，当前 ' + hash)

  await b.evalJS(`location.hash = '#/admin/observe/metrics'`)
  // 等到首帧：健康度 KPI 从「连接中」变成后端给的字样。
  let page = null
  for (let i = 0; i < 60; i++) {
    await sleep(400)
    page = await b.evalJS(`(() => {
      const hv = document.querySelector('.a-hv')
      const reasons = [...document.querySelectorAll('.a-reasons li')].map((x) => x.innerText.trim())
      return {
        health: hv ? hv.innerText.trim() : null,
        healthClass: hv ? hv.className : null,
        reasons,
        dlqCard: !!document.querySelector('.a-stat--dlq'),
      }
    })()`)
    if (page && page.health && page.health !== '连接中' && page.dlqCard) break
  }
  out.page = page
  if (!page || !page.health) throw new Error('指标页没有健康度读数（首帧没到）')
  if (page.health !== '健康') throw new Error('健康度不是「健康」：' + JSON.stringify(page.health))
  const mq = page.reasons.filter((r) => /MQ|broker/i.test(r))
  if (mq.length) throw new Error('MQ 告警条仍在：' + JSON.stringify(mq))

  const shot = `${OUT}/${stamp}.png`
  await b.shot(shot)
  out.shot = shot
  console.log(JSON.stringify(out, null, 2))
  console.log('SHOT ' + shot)
} catch (e) {
  console.log('FAILED ' + (e && e.message))
  console.log(JSON.stringify(out, null, 2))
  process.exitCode = 1
} finally {
  await b.close().catch(() => {})
}
