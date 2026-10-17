// 回放拖拽（Seek）录屏帧：用**真鼠标事件**（CDP Input.dispatchMouseEvent）拖进度条，
// 不是脚本改 value。产出 drag-frames/fNN.png，再由 make-b7r3-seek-gif.py 拼成 gif。
//
// 跑法：前端 8080 + 后端 8088（dev）+ `PROBE_ADMIN_PWD="$(cat _work/admin-pass)" node scripts/probes/probe-b7r3-seek-gif.mjs`
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080/admin.html'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/b7r3-seek/drag-frames'
const BATCH = process.env.PROBE_BATCH || 'b81ab9b5-3497-4e16-965e-6c67f567c403'
const ADMIN_ACC = process.env.PROBE_ADMIN_ACC || 'admin'
const ADMIN_PWD = process.env.PROBE_ADMIN_PWD || ''

if (!ADMIN_PWD) {
  console.error('缺少 PROBE_ADMIN_PWD（用法：PROBE_ADMIN_PWD="$(cat _work/admin-pass)" node scripts/probes/probe-b7r3-seek-gif.mjs）')
  process.exit(2)
}

const b = await open({ w: 1440, h: 1000, headed: false })
let n = 0
const shot = async () => {
  const f = `${OUT}/f${String(n).padStart(2, '0')}.png`
  n += 1
  await b.shot(f)
  return f
}

try {
  await b.goto(`${BASE}#/login`, 2500)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('#acc'), ${JSON.stringify(ADMIN_ACC)})
    set(document.querySelector('#pwd'), ${JSON.stringify(ADMIN_PWD)})
    const btn = document.querySelector('button.btn.btn-primary.btn-block')
    if (btn) btn.click()
  })()`)
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    if (/^#\/admin/.test(await b.evalJS('location.hash'))) break
  }

  await b.goto(`${BASE}#/admin/observe/metrics`, 3500)
  // 等横幅
  for (let i = 0; i < 80; i++) {
    if (await b.evalJS(`!!document.querySelector('#a-rb-batch')`)) break
    await sleep(200)
  }
  // 选正样本并回放
  await b.evalJS(`(() => {
    const s = document.querySelector('#a-rb-batch')
    s.value = ${JSON.stringify(BATCH)}
    s.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  })()`)
  await sleep(300)
  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.a-rb-pick .a-rb-btn')].find((x) => x.textContent.trim() === '回放')
    if (btn) btn.click()
    return !!btn
  })()`)
  // 等回放开始播
  for (let i = 0; i < 100; i++) {
    const on = await b.evalJS(`(() => {
      const b = document.querySelector('.a-rb-prog b')
      return !!b && Number((b.textContent || '0').replace(/[^0-9]/g, '')) > 0
    })()`)
    if (on) break
    await sleep(200)
  }
  // 暂停：录屏里从「播放中」开始拖
  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.a-rb .a-rb-btn')].find((x) => x.textContent.trim() === '暂停')
    if (btn) btn.click()
    return !!btn
  })()`)
  await sleep(300)
  await shot() // f00：拖前

  const rectOf = () => b.evalJS(`(() => {
    const r = document.querySelector('.a-rb-range').getBoundingClientRect()
    return { x: r.x, y: r.y, w: r.width, h: r.height, value: Number(document.querySelector('.a-rb-range').value) }
  })()`)
  const rect0 = await rectOf()
  const y = Math.round(rect0.y + rect0.h / 2)
  const xOf = (r, p) => Math.round(r.x + 6 + (r.w - 12) * (p / 100))

  // 按下（从当前位置）
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: xOf(rect0, rect0.value), y, buttons: 0 })
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: xOf(rect0, rect0.value), y, button: 'left', buttons: 1, clickCount: 1 })
  await sleep(120)

  // 按住拖到 20% / 30% / 40%。
  // ⚠️ 每一步都**重新量一次** range 的方框：开拖后右边的目标时间文字变长，
  //    弹性布局会把轨道压窄 —— 用拖前的宽度算 x，会越拖越偏（本次 40 拖成 47.6）。
  let lastX = xOf(rect0, rect0.value)
  for (const p of [20, 30, 40]) {
    const rr = await rectOf()
    // ⚠️ `button:'left'` 不能省：只给 `buttons:1` 时原生 range 不跟手（2026-10-18 实测）
    lastX = xOf(rr, p)
    await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: lastX, y, button: 'left', buttons: 1 })
    await sleep(160)
    // 校一次：文字宽度变化会让落点差几格
    const got = Number(await b.evalJS(`document.querySelector('.a-rb-range').value`))
    if (Math.abs(got - p) > 1.5) {
      const rr2 = await rectOf()
      lastX = Math.round(lastX + ((p - got) / 100) * rr2.w)
      await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: lastX, y, button: 'left', buttons: 1 })
      await sleep(120)
    }
    console.log('MOVE', p, await b.evalJS(`document.querySelector('.a-rb-range').value`))
    await shot()
  }

  // 松开 → 状态重建（遮罩出现）
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: lastX, y, button: 'left', buttons: 0, clickCount: 1 })
  await shot() // 松开瞬间（遮罩）
  await sleep(120)
  await shot()
  await sleep(280)
  await shot()
  await sleep(700)
  await shot() // 重建完成：40% 那一刻的指标

  console.log('FRAMES', n)
} finally {
  await b.close()
}
