// 临时探针（不入库）：修复后的现场截图 —— 攻击页三条流 / 演练记录（含批次台账） / 回放日志页。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const OUT = process.env.OUT_DIR || '_work/postfix-shots'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) { console.error('缺少 PROBE_ADMIN_PWD'); process.exit(2) }
fs.mkdirSync(OUT, { recursive: true })

const b = await open({ w: 1680, h: 1050, headed: false })
try {
  await b.goto(BASE + '/admin.html', 2500)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    set(acc, 'admin'); set(pwd, ${JSON.stringify(PWD)})
    ;[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x => x.textContent.trim() === '登录').click()
  })()`)
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    if (/^#\/admin/.test(await b.evalJS('location.hash'))) break
  }

  await b.evalJS(`location.hash = '#/admin/observe/attack-sim'`)
  // 等场景清单与记录都载入（场景加载失败会写进 .a-err）。
  let ready = null
  for (let i = 0; i < 60; i++) {
    await sleep(400)
    ready = await b.evalJS(`(() => ({
      err: (document.querySelector('.a-err') || {}).textContent || '',
      live: [...document.querySelectorAll('.lp-conn')].map(x => x.textContent.trim()),
      records: document.querySelectorAll('.a-ev').length,
      hasTarget: document.body.innerText.includes('post-fix-100'),
    }))()`)
    if (ready.hasTarget && ready.live.filter(x => x === '实时').length === 3) break
  }

  // ① 页头 + 三条流（先滚回顶部）
  await b.evalJS(`window.scrollTo(0, 0)`)
  await sleep(600)
  await b.shot(`${OUT}/01-attack-top.png`)

  // ①b 实时预览三列（三条流都在「实时」）
  await b.evalJS(`document.querySelector('.lp').scrollIntoView({ block: 'center' })`)
  await sleep(800)
  await b.shot(`${OUT}/01b-attack-live.png`)
  await b.evalJS(`window.scrollTo(0, 0)`)

  // ② 展开最新一条记录（post-fix-100），滚到「本批事件台账」那一行
  await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.a-ev')]
    const row = rows.find(r => r.innerText.includes('post-fix-100')) || rows[0]
    row.querySelector('.a-ev-head').click()
    return true
  })()`)
  await sleep(800)
  const recordInfo = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.a-ev')]
    const row = rows.find(r => r.innerText.includes('post-fix-100')) || rows[0]
    row.scrollIntoView({ block: 'center' })
    const btn = row.querySelector('.a-rp-in button')
    return {
      text: row.innerText.replace(/\\n+/g, ' | ').slice(0, 500),
      replayDisabled: btn ? btn.disabled : null,
      replayText: btn ? btn.textContent.trim() : null,
    }
  })()`)
  await sleep(400)
  await b.shot(`${OUT}/02-attack-record.png`)

  // ③ 点回放：跳到日志页，等横幅与行出现
  await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.a-ev')]
    const row = rows.find(r => r.innerText.includes('post-fix-100')) || rows[0]
    row.querySelector('.a-rp-in button').click()
    return true
  })()`)
  let replay = null
  for (let i = 0; i < 80; i++) {
    await sleep(400)
    replay = await b.evalJS(`(() => ({
      hash: location.hash,
      banner: (document.querySelector('.a-el') || {}).innerText ? document.querySelector('.a-el').innerText.slice(0, 300) : '',
      rows: document.querySelectorAll('.a-el li').length,
    }))()`)
    if (/logs/.test(replay.hash) && replay.banner.includes('回放')) break
  }
  await sleep(2500)   // 让播放时钟吐出几秒历史行
  await b.shot(`${OUT}/03-replay-logs.png`)

  console.log(JSON.stringify({ ready, recordInfo, replay }, null, 2))
} finally {
  await b.close().catch(() => {})
}
