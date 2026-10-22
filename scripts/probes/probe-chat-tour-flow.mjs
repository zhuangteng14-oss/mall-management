// probe-chat-tour-flow.mjs —— 「多步路径引导」真链验收（2026-10-22）
//
// 验的是用户口径：点【去发布商品】**不许直接瞬移**，必须先在当前页高亮入口，
// 点 driver 的「下一步」才跳；跳过去再高亮下一步，五步连贯。
//
// 流程（真 dev server + 真后端 + 真模型，不 mock 任何请求）：
//   ① 商家登录（chenguang_parts）⇒ /merchant；
//   ② 客服发「我的新商品要上架秒杀页，还没上架呢」⇒ 点【去发布商品】；
//   ③ 断言：当前页（/merchant）高亮 #tour-entry-merchant，进度「第 1 步 / 共 5 步」；
//   ④ 点「下一步」⇒ /merchant/user，高亮 #tour-menu-add（第 2 步）；
//   ⑤ 点「下一步」⇒ /merchant/user/add，依次高亮 title / image / submit（第 3–5 步）；
//   ⑥ 点「完成」⇒ 浮层消失、sessionStorage 里的流程存档清空；
//   ⑦ 直接深链 /merchant/user/add（无流程）⇒ 不出现任何高亮；
//   ⑧ 已在发布页点胶囊 ⇒ 自动跳到第 3 步；点「上一步」⇒ 回到个人中心第 2 步；
//   ⑨ 【去秒杀报名】仍能落到 /merchant/seckill 并高亮两步。
//
// 用法：node scripts/probes/probe-chat-tour-flow.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-tour-flow'
fs.mkdirSync(OUT, { recursive: true })

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

// 演示账号口令不硬编码、不回显（与 probe-chat-llm.mjs 同口径）。
const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const b = await open({ w: 1440, h: 900, headed: false })
const pageErrors = []
b.ws.on('message', (raw) => {
  let m
  try { m = JSON.parse(raw) } catch { return }
  if (m.method === 'Page.javascriptDialogOpening') {
    b.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
  }
  if (m.method === 'Runtime.exceptionThrown') {
    pageErrors.push(m.params?.exceptionDetails?.text || 'exception')
  }
})
await b.send('Runtime.enable', {}).catch(() => {})

async function waitFor(expr, timeout = 15000, step = 150) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

async function login(acc, pwd) {
  await b.goto(BASE + '/#/login', 1500)
  await waitFor(`document.querySelector('#acc') && document.querySelector('#pwd')`, 15000)
  for (let attempt = 0; attempt < 3; attempt++) {
    await b.evalJS(`(() => {
      const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
      set(document.querySelector('#acc'), ${JSON.stringify(acc)})
      set(document.querySelector('#pwd'), ${JSON.stringify(pwd)})
      const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(e => e.textContent.trim() === '登录' && !e.disabled)
      btn?.click()
      return !!btn
    })()`)
    for (let i = 0; i < 24; i++) {
      await sleep(500)
      const hash = await b.evalJS(`location.hash`)
      if (/^#\/merchant/.test(hash)) return true
    }
  }
  return false
}

async function openPanel() {
  // ⚠️ 必须用 `.chat-root .panel`：页面上本来就有很多业务 `.panel`，
  //    裸 `.panel` 会把「客服面板没开」误判成已开。
  if (await b.evalJS(`!!document.querySelector('.chat-root .panel')`)) return true
  await b.evalJS(`document.querySelector('.chat-root .ball')?.click()`)
  const up = await waitFor(`document.querySelector('.chat-root .panel')`, 5000)
  await sleep(300)
  return up
}

async function sendChat(text) {
  await openPanel()
  const before = await b.evalJS(`document.querySelectorAll('.bubble.bot').length`)
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(el, ${JSON.stringify(text)})
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(150)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  // 完成信号 = 新的 bot 气泡已落地且「正在输入」的 dots 消失。
  // ⚠️ 不能只看 textarea 的 disabled：真模型有 3s 超时降级，DOM 更新与 CDP 轮询之间有竞态。
  const done = await waitFor(
    `document.querySelectorAll('.bubble.bot').length > ${before} && !document.querySelector('.bubble.bot .dots')`,
    20000,
  )
  await sleep(400)
  return done
}

const popoverShown = () => `document.querySelector('.driver-popover') && getComputedStyle(document.querySelector('.driver-popover')).display !== 'none'`
// ⚠️ 不能写 `!${popoverShown()}`：插值后 `!` 只作用于 querySelector，后半截会拿 null 调 getComputedStyle 抛错。
const popoverGone = `!document.querySelector('.driver-popover') || getComputedStyle(document.querySelector('.driver-popover')).display === 'none'`
const active = (sel) => `document.querySelector(${JSON.stringify(sel)})?.classList.contains('driver-active-element')`
const progress = `document.querySelector('.driver-popover-progress-text')?.textContent?.trim() || ''`
const flowState = `sessionStorage.getItem('mall.chat.tourFlow.v1')`

async function clickNext() {
  await b.evalJS(`document.querySelector('.driver-popover-next-btn')?.click()`)
  await sleep(250)
}

async function waitStep(sel, progressText, timeout = 12000) {
  const hit = await waitFor(`${active(sel)} && (${progress}) === ${JSON.stringify(progressText)}`, timeout)
  return { hit, progress: await b.evalJS(progress), active: await b.evalJS(active(sel)) }
}

try {
  ok('商家登录落 /merchant', await login('chenguang_parts', CREDS['chenguang_parts']))
  await b.shot(path.join(OUT, 'flow-00-merchant.png'))

  const done = await sendChat('我的新商品要上架秒杀页，还没上架呢')
  // 再等一次胶囊本身：真模型有 3s 超时降级的路径，回复落地与 DOM 更新不是同一帧。
  const chipReady = await waitFor(
    `[...document.querySelectorAll('.chip')].some(e => e.textContent.includes('去发布商品'))`, 20000,
  )
  const chips = await b.evalJS(`[...document.querySelectorAll('.chip')].map(e => e.textContent.trim())`)
  const reply = await b.evalJS(`[...document.querySelectorAll('.bubble.bot .txt')].pop()?.textContent || ''`)
  ok('复杂语义拿到两枚胶囊', done && chipReady
    && chips.some((x) => x.includes('去发布商品')) && chips.some((x) => x.includes('去秒杀报名')),
  { chips, reply: reply.slice(0, 100) })

  // 点胶囊：**不许**直接落 /merchant/user/add，必须先在本页高亮入口。
  await b.evalJS(`[...document.querySelectorAll('.chip')].find(e => e.textContent.includes('去发布商品'))?.click()`)
  const s1 = await waitStep('#tour-entry-merchant', '第 1 步 / 共 5 步')
  ok('Step1：当前页高亮「用户中心」入口（不瞬移）',
    s1.hit && (await b.evalJS(`location.hash`)) === '#/merchant', { ...s1, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-01-entry.png'))

  await clickNext()
  const s2 = await waitStep('#tour-menu-add', '第 2 步 / 共 5 步')
  ok('Step2：下一步跳到个人中心并高亮「上架商品」',
    s2.hit && (await b.evalJS(`location.hash`)) === '#/merchant/user', { ...s2, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-02-menu.png'))

  await clickNext()
  const s3 = await waitStep('#tour-publish-title', '第 3 步 / 共 5 步')
  ok('Step3：下一步跳到发布页并高亮商品名称',
    s3.hit && (await b.evalJS(`location.hash`)) === '#/merchant/user/add', { ...s3, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-03-title.png'))

  await clickNext()
  const s4 = await waitStep('#tour-publish-image', '第 4 步 / 共 5 步')
  ok('Step4：同页切到商品主图', s4.hit, s4)
  await b.shot(path.join(OUT, 'flow-04-image.png'))

  await clickNext()
  const s5 = await waitStep('#tour-publish-submit', '第 5 步 / 共 5 步')
  ok('Step5：同页切到提交审核', s5.hit, s5)
  await b.shot(path.join(OUT, 'flow-05-submit.png'))

  await clickNext()
  const closed = await waitFor(popoverGone, 5000)
  const dom = await b.evalJS(`({
    count: document.querySelectorAll('.driver-popover').length,
    display: [...document.querySelectorAll('.driver-popover')].map(e => getComputedStyle(e).display),
    overlay: document.querySelectorAll('.driver-overlay').length,
    body: document.body.className,
    active: [...document.querySelectorAll('.driver-active-element')].map(e => e.id || e.className),
  })`)
  ok('完成后浮层消失且流程存档清空',
    closed && !(await b.evalJS(flowState)), { closed, flow: await b.evalJS(flowState), dom })

  // ⑦ 直接导航进发布页（无流程）⇒ 不出现高亮。
  // ⚠️ 用 hash 导航而不是 goto：整页刷新会把聊天历史（那两枚胶囊）一起清掉。
  await b.evalJS(`location.hash = '#/merchant/user/add'`)
  await waitFor(`document.querySelector('#tour-publish-title')`, 8000)
  await sleep(1000)
  ok('直接进发布页不触发高亮（无流程存档）',
    !(await b.evalJS(popoverShown())) && !(await b.evalJS(active('#tour-publish-title'))))

  // ⑧ 已在发布页点同一个胶囊 ⇒ 从第 3 步开始；点「上一步」回到个人中心第 2 步。
  await openPanel()
  await waitFor(`[...document.querySelectorAll('.chip')].some(e => e.textContent.includes('去发布商品'))`, 5000)
  const clickedAgain = await b.evalJS(`(() => {
    const c = [...document.querySelectorAll('.chip')].find(e => e.textContent.includes('去发布商品'))
    c?.click()
    return !!c
  })()`)
  const s6 = await waitStep('#tour-publish-title', '第 3 步 / 共 5 步')
  ok('已在发布页：自动跳到第 3 步（不重复强调入口）',
    s6.hit && (await b.evalJS(`location.hash`)) === '#/merchant/user/add',
    { ...s6, clickedAgain, flow: await b.evalJS(flowState), pop: await b.evalJS(`document.querySelectorAll('.driver-popover').length`) })
  await b.shot(path.join(OUT, 'flow-06-resume-title.png'))

  await b.evalJS(`document.querySelector('.driver-popover-prev-btn')?.click()`)
  const back = await waitStep('#tour-menu-add', '第 2 步 / 共 5 步', 12000)
  ok('「上一步」跨页回到个人中心并高亮上架入口',
    back.hit && (await b.evalJS(`location.hash`)) === '#/merchant/user',
    { ...back, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-07-prev.png'))
  await b.evalJS(`document.querySelector('.driver-popover-close-btn')?.click()`)
  await sleep(300)

  // ⑨ 秒杀：胶囊 → 报名页两步。
  await openPanel()
  await waitFor(`[...document.querySelectorAll('.chip')].some(e => e.textContent.includes('去秒杀报名'))`, 5000)
  const clickedSeckill = await b.evalJS(`(() => {
    const c = [...document.querySelectorAll('.chip')].find(e => e.textContent.includes('去秒杀报名'))
    c?.click()
    return !!c
  })()`)
  const k1 = await waitStep('#tour-seckill-select', '第 1 步 / 共 2 步')
  ok('秒杀：落到报名页并高亮商品下拉',
    k1.hit && (await b.evalJS(`location.hash`)) === '#/merchant/seckill',
    { ...k1, hash: await b.evalJS(`location.hash`) })
  await b.shot(path.join(OUT, 'flow-08-seckill.png'))
  await clickNext()
  const k2 = await waitStep('#tour-seckill-submit', '第 2 步 / 共 2 步')
  ok('秒杀：同页切到提交报名', k2.hit, k2)
  await clickNext()
  ok('秒杀：完成后浮层消失', await waitFor(popoverGone, 5000))

  ok('页面无未捕获异常', pageErrors.length === 0, { pageErrors })
} catch (e) {
  ok('探针异常', false, { error: String(e && e.message ? e.message : e) })
} finally {
  const passed = rows.filter((r) => r.pass).length
  console.log(`\nflow: ${passed}/${rows.length} PASS`)
  fs.writeFileSync(path.join(OUT, 'probe-flow.txt'),
    rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}  ${JSON.stringify(r.detail)}`).join('\n') + '\n'
    + `\nflow: ${passed}/${rows.length} PASS\n`)
  await sleep(200)
  process.exit(passed === rows.length ? 0 : 1)
}
