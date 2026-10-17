// probe-b7r-replay-cancel.mjs —— 验证「回放与实时演练一致」的取消支线：
// 回放一个**窗口内真的发生过超时取消**的批次，状态页必须出现「批量取消 N 单」并重建已取消存量。
//
// 批次 713b2210-9ed0-4dd6-b085-ff646899fbd0（2026-10-18 00:18:08 ~ 00:19:48 本地）里，
// 超时任务在 00:19:02 / 00:19:32 发过两条无批次号的汇总取消（count=20 / 253）。
// 后端 `ObservedEventDao.BATCH_PREDICATE` 的第二支把它们补进该批次；本探针从 UI 上再验一遍。
//
// 跑法：dev 后端 8088 + `npm run serve` 8080，`node scripts/probes/probe-b7r-replay-cancel.mjs`
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/observe/timeline'
const BATCH = '713b2210-9ed0-4dd6-b085-ff646899fbd0'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'

const R = report('块 7 · 回放取消支线：批次窗口内的无批次号汇总取消（真后端）')
const ok = (label, cond, got) => R.ok(label, cond, got)

const b = await open({ w: 1440, h: 1000 })
await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `try {
    sessionStorage.setItem('loginUsername','ops_duan');
    sessionStorage.setItem('customerid','qa7PmpUojO');
    sessionStorage.setItem('role','3');
  } catch (e) {}`,
})

try {
  await b.send('Page.navigate', { url: `${BASE}/${ROUTE}` })
  let mounted = false
  for (let i = 0; i < 120; i++) {
    mounted = await b.evalJS(`!!document.querySelector('#a-rb-batch')`).catch(() => false)
    if (mounted) break
    await sleep(250)
  }
  ok('① 状态页顶部有批次选择框', mounted === true)

  let picked = false
  for (let i = 0; i < 60; i++) {
    picked = await b.evalJS(`(() => {
      const s = document.querySelector('#a-rb-batch')
      if (!s) return false
      const opt = [...s.options].find((o) => o.value === '${BATCH}')
      if (!opt) return false
      s.value = opt.value
      s.dispatchEvent(new Event('change', { bubbles: true }))
      return true
    })()`).catch(() => false)
    if (picked) break
    await sleep(300)
  }
  ok('① 下拉框里有目标批次（演练记录已列出）', picked === true)

  await b.evalJS(`[...document.querySelectorAll('.a-rb-pick .a-rb-btn')].find((x) => x.innerText.trim() === '回放').click()`)
  let loaded = false
  for (let i = 0; i < 120; i++) {
    loaded = await b.evalJS(`/正在回放/.test(document.body.innerText)`).catch(() => false)
    if (loaded) break
    await sleep(250)
  }
  ok('① 回放已启动', loaded === true)
  await sleep(800)

  const head = await b.evalJS(`(() => {
    const t = document.body.innerText
    const m = t.match(/回放进度\\s*([\\d,]+)\\s*\\/\\s*([\\d,]+)/) || t.match(/([\\d,]+)\\s*\\/\\s*([\\d,]+)/)
    return { m: m ? [m[1], m[2]] : null, replay: /正在回放/.test(t), mq: /MQ\\s*未连接/.test(t) }
  })()`)
  ok('② 整批载入（分母 = 35,226，不是 5,000 截断）',
    !!head.m && String(head.m[1]).replace(/,/g, '') === '35226', head.m)
  ok('② 回放期间没有「MQ 未连接」误报', head.mq === false)

  // 汇总行只留在「最近 30 条流转」里（本批 ~75 单/秒 ⇒ 10x 下只可见约 40ms）。
  // 做法：10x 冲到 00:19:00 前立刻暂停，切 0.25x 再播 —— 第一波取消（00:19:02）的
  // 汇总行在慢速下能停留 ~1.6s，轮询才抓得到。抓到后立刻暂停再截图。
  await b.evalJS(`[...document.querySelectorAll('.a-rb-sp')].find((x) => x.innerText.trim() === '10x').click()`)
  let gate = ''
  let atGate = false
  for (let i = 0; i < 400; i++) {
    gate = await b.evalJS(`(() => (document.body.innerText.match(/回放时钟\\s*([0-9:.]+)/) || [])[1] || '')()`)
    if (gate && gate >= '00:19:00') { atGate = true; break }
    await sleep(50)
  }
  ok('③ 10x 追到 00:19:00（第一条取消 00:19:02 之前）', atGate, gate)

  await b.evalJS(`(() => { const x = [...document.querySelectorAll('.a-rb-btn')].find((e) => e.innerText.trim() === '暂停'); if (x) x.click() })()`)
  await b.evalJS(`[...document.querySelectorAll('.a-rb-sp')].find((x) => x.innerText.trim() === '0.25x').click()`)
  await b.evalJS(`(() => { const x = [...document.querySelectorAll('.a-rb-btn')].find((e) => e.innerText.trim() === '继续'); if (x) x.click() })()`)

  let row = null
  let cursor = 0
  for (let i = 0; i < 240; i++) {
    const snap = await b.evalJS(`(() => {
      const rows = [...document.querySelectorAll('.a-of-rpm-i')].map((e) => e.innerText.replace(/\\s+/g, ' ').trim())
      return {
        hit: rows.find((t) => t.includes('批量取消')) || '',
        clock: (document.body.innerText.match(/回放时钟\\s*([0-9:.]+)/) || [])[1] || '',
      }
    })()`)
    if (snap.hit) {
      // 先暂停，保证截图时这一条还在「最近 30 条」里。
      await b.evalJS(`(() => { const x = [...document.querySelectorAll('.a-rb-btn')].find((e) => e.innerText.trim() === '暂停'); if (x) x.click() })()`)
      row = snap
      break
    }
    cursor++
    await sleep(100)
  }
  ok('③ 回放明细出现「批量取消 N 单」汇总行（窗口内取消没有被丢掉）',
    !!row && /批量取消\s*[\d,]+\s*单/.test(row.hit), row && row.hit)
  ok('③ 汇总行时间落在批次窗口内（00:19:0x / 00:19:3x）',
    !!row && /00:19:[0-9]{2}/.test(row.hit), row && row.hit)

  // 把汇总行滚到视口中间再拍：截图上要能直接读到这条。
  await b.evalJS(`(() => {
    const row = [...document.querySelectorAll('.a-of-rpm-i')].find((e) => e.innerText.includes('批量取消'))
    if (row) row.scrollIntoView({ block: 'center' })
    return !!row
  })()`)
  await sleep(400)
  await b.shot(`${EV}b7r-replay-cancel-row.png`)

  // 继续播到第二条汇总（00:19:32）：已取消存量应按 count 加权成 20 + 253。
  await b.evalJS(`[...document.querySelectorAll('.a-rb-sp')].find((x) => x.innerText.trim() === '10x').click()`)
  await b.evalJS(`(() => { const x = [...document.querySelectorAll('.a-rb-btn')].find((e) => e.innerText.trim() === '继续'); if (x) x.click() })()`)
  let after = null
  for (let i = 0; i < 80; i++) {
    after = await b.evalJS(`(() => {
      const t = document.body.innerText.replace(/\\s+/g, ' ')
      const m = t.match(/已取消[^0-9]{0,12}([\\d,]+)/)
      return {
        cancelled: m ? m[1] : '',
        clock: (t.match(/回放时钟\\s*([0-9:.]+)/) || [])[1] || '',
        qps: (t.match(/取消流入\\s*([\\d.]+)/) || [])[1] || '',
      }
    })()`)
    if (Number(String(after.cancelled).replace(/,/g, '')) >= 273) break
    await sleep(150)
  }
  ok('③ 回放重建的「已取消」存量 ≥ 273（两条汇总按 count 加权）',
    Number(String(after.cancelled).replace(/,/g, '')) >= 273, after)
  await b.shot(`${EV}b7r-replay-cancel-state.png`)

  const bad = R.dump()
  console.log('pollRounds=' + cursor)
  process.exitCode = bad ? 1 : 0
} finally {
  await b.close()
}
