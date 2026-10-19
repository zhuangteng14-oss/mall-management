// d3-attack-config-toggle.mjs —— 攻击模拟器开关接线取证（2026-10-18）
//
// 背景：D3 手验发现「开 mqDown → 指标页横幅出现；关 mqDown → 横幅不消失」。根因不是指标页，
//      而是本页开关**只改本地 Set、从未 POST 配置** ⇒ 后端 `AttackSwitchConfig` 一直开着。
// 判据：在真页面上点一次「MQ 断开」，服务端 `GET /admin/observe/attack/config` 的
//      `Injections` 必须出现 `mqDown`；再点一次必须消失（**拨动真的落到服务端**）。
// 收尾：脚本无论成功失败都再点一次，把注入恢复为空（不把 broker 发布侧留在暂停态）。
//
// 凭据：**只**从环境变量 PROBE_ADMIN_PWD 读（本地来源 `_work/admin-pass`）。
// 用法：
//   PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" \
//     node scripts/probes/d3-attack-config-toggle.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = process.env.PROBE_BASE || 'http://localhost:8080'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) {
  console.error('缺少 PROBE_ADMIN_PWD')
  process.exit(2)
}

const CONFIG_URL = 'http://localhost:8088/admin/observe/attack/config'
const b = await open({ w: 1600, h: 1000, headed: false })
const out = { base: BASE, steps: [] }

async function readInjections() {
  const data = await b.evalJS(`(async () => {
    const r = await fetch(${JSON.stringify(CONFIG_URL)}, { cache: 'no-store' })
    return r.json()
  })()`)
  return { injections: (data && data.Injections) || [], version: data && data.Version }
}

try {
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

  await b.evalJS(`location.hash = '#/admin/observe/attack-sim'`)
  let sw = null
  for (let i = 0; i < 60; i++) {
    await sleep(400)
    sw = await b.evalJS(`(() => {
      const btn = [...document.querySelectorAll('.a-switches .a-sw')]
        .find((x) => x.textContent.includes('MQ 断开'))
      if (!btn) return null
      return {
        text: btn.textContent.trim(),
        pressed: btn.getAttribute('aria-pressed'),
        cls: btn.className,
        disabled: btn.disabled,
      }
    })()`)
    if (sw) break
  }
  out.before = { sw, ...(await readInjections()) }
  if (!sw) throw new Error('没有找到「MQ 断开」开关')
  if (sw.disabled) throw new Error('「MQ 断开」按钮是 disabled —— 开关仍是死控件')

  // 点一次：期望服务端出现 mqDown。
  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.a-switches .a-sw')]
      .find((x) => x.textContent.includes('MQ 断开'))
    btn.click()
    return true
  })()`)
  let afterOn = null
  for (let i = 0; i < 20; i++) {
    await sleep(300)
    afterOn = await readInjections()
    if (afterOn.injections.includes('mqDown')) break
  }
  out.afterOn = afterOn
  if (!afterOn.injections.includes('mqDown')) {
    throw new Error('点开「MQ 断开」后服务端 Injections 没有 mqDown：' + JSON.stringify(afterOn))
  }

  // 再点一次：期望服务端恢复为空（这正是「关掉开关横幅该消失」的那条链路）。
  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.a-switches .a-sw')]
      .find((x) => x.textContent.includes('MQ 断开'))
    btn.click()
    return true
  })()`)
  let afterOff = null
  for (let i = 0; i < 20; i++) {
    await sleep(300)
    afterOff = await readInjections()
    if (!afterOff.injections.includes('mqDown')) break
  }
  out.afterOff = afterOff
  if (afterOff.injections.includes('mqDown')) {
    throw new Error('再点一次后服务端 Injections 仍有 mqDown：' + JSON.stringify(afterOff))
  }

  console.log(JSON.stringify(out, null, 2))
} catch (e) {
  console.log('FAILED ' + (e && e.message))
  console.log(JSON.stringify(out, null, 2))
  process.exitCode = 1
  // 兜底复位：别把 broker 发布侧留在暂停态。
  try {
    await b.evalJS(`(async () => {
      const c = await fetch(${JSON.stringify(CONFIG_URL)}, { cache: 'no-store' }).then(r => r.json())
      if (c && (c.Injections || []).includes('mqDown')) {
        const body = new URLSearchParams({ injections: '[]' })
        await fetch(${JSON.stringify(CONFIG_URL)}, { method: 'POST', body })
      }
    })()`)
  } catch (e2) { /* 页面可能已关 */ }
} finally {
  await b.close().catch(() => {})
}
