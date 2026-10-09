// probe-msg-center.mjs —— 「消息 / 通知」两个顶栏入口**合并**成一个「消息」入口的验收探针（2026-10-09）。
//
// 跑法：`node scripts/probes/probe-msg-center.mjs`（**只需 dev server 8080**，后端**全部就地 mock**）。
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）与 `npm run serve` 起的 8080（`.env.development`
//      的 API_BASE = `http://localhost:8088` ⇒ mock 拦的是 `*8088*` 那些**假**请求，真后端**不用起**）。
//
// ⚠️ **登录态怎么来的**：`Page.addScriptToEvaluateOnNewDocument` 在每个新文档**任何应用脚本之前**
//    往 `sessionStorage` 写 `loginUsername` / `customerid` / `role`（键名见 `src/api/auth.js` 的 `K`）
//    —— 这是唯一可靠的做法：先 `goto` 再写存储已经太晚（`AppShell` 的 `navFor` 在 `setup` 里只算**一次**，
//    见 `config/nav.js` 的 `navFor` 注释），表现会是「顶栏停在匿名态」。
//
// 断言 = 用户给的验收清单逐条（`docs/agent/slot-registry.md` §十六）：
//   ① 顶栏只剩一个「消息」入口（无「通知」项）② 页内两个 tab ③ 会话 tab 原有功能不破
//   ④ 通知 tab 原有功能不破 ⑤ 红点 = 未读会话 + 未读通知（**四种组合**逐一验，见 `MODES`）
//   ⑥ `/customer/notify` 深链落通知 tab ⑦ `/customer/user/chat` 不受影响 ⑧ 两壳对称（商家侧同款）
//
// ⚠️ **为什么不顺手验「进页清红点」**：那件事本就是**下一发轮询会盖回来**的（后端没把会话标已读）
//    ⇒ 断言它必然是**竞态**的。不在验收清单里，不写。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/msg-center/'
const OUT = EV + 'shots/'
fs.mkdirSync(OUT, { recursive: true })
const R = report('消息中心（消息+通知 入口合并）')
// ⚠️ `report()` 的 `dump()` **只回失败数**（总项数只印在它自己的 console 里）⇒ 自己数一份，
//    供 `result.json` 记「N 项 / M 失败」—— 数字要有出处，别手写。
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

// ── 后端桩：未读两笔各由 MODES 控制（红点那四条断言靠它逐一切换）──────────────────
// convUnread = `/message/conversations` 里 `unread === true` 的条数（`AppShell.fetchUnreadCount` 数的就是它）
// notifUnread = `/notification/unreadCount` 直接答的 `unread`
const MODES = {
  both: { conv: 1, notif: 2 },        // 两笔都有
  notifyOnly: { conv: 0, notif: 2 },  // **只有通知** —— 合并前这一档不会让「消息」项亮
  convOnly: { conv: 1, notif: 0 },    // 只有会话
  none: { conv: 0, notif: 0 },        // 两笔都空 ⇒ 不该有红点
}
let mode = 'both'

const mock = (url, method) => {
  if (method === 'OPTIONS') return { status: 200, body: '' }   // 预检：头由底座补（含 ACAO）
  const m = MODES[mode]
  const p = new URL(url).pathname
  const json = (o) => ({ status: 200, body: JSON.stringify(o) })
  if (p === '/user/me') return json({ success: false })        // 恢复态走不成 ⇒ 回落到 sessionStorage（正是本探针要的）
  if (p === '/message/conversations') {
    const list = []
    for (let i = 0; i < Math.max(m.conv, 1); i++) {
      list.push({ other_id: 'peer' + i, other_type: 'merchant', unread: i < m.conv,
        last_message: '预约看货', create_time: '2026-10-09 10:00:00' })
    }
    return json({ success: true, list })
  }
  if (p === '/message/list') return json({ success: true, list: [] })
  if (p === '/notification/unreadCount') return json({ success: true, unread: m.notif })
  if (p === '/notification/list') {
    const list = []
    for (let i = 0; i < m.notif; i++) {
      list.push({ Id: i + 1, Title: '公告 ' + (i + 1), Content: '正文 ' + (i + 1),
        EventType: 'system.notice', TargetType: null, TargetId: null,
        ReadAt: null, CreatedAt: '2026-10-09 10:00:00', unread: true })
    }
    return json({ success: true, list, total: m.notif, unread: m.notif })
  }
  if (p.startsWith('/notification/')) return json({ success: true, updated: 1 })
  return json({ success: true, list: [], total: 0, unread: 0 })
}

const SEED = (who) => Object.entries(who)
  .map(([k, v]) => `sessionStorage.setItem(${JSON.stringify(k)},${JSON.stringify(v)});`).join('')

/** 页面上一次读全（顶栏 + tab + 两个子页的在否）。 */
const READ = `(()=>{const q=s=>document.querySelector(s);
  const tabs=[...document.querySelectorAll('.mc-tab')].map(e=>({t:e.textContent.trim(),on:e.classList.contains('active')}));
  const navs=[...document.querySelectorAll('.nav-right .nav-btn')].map(e=>e.textContent.trim());
  return {hash:location.hash, navs, dot:!!document.querySelector('.nav-right .nav-red-dot'),
    tabs, chat:!!q('.chat-room .chat'), notify:!!q('.notify-room'), nlist:document.querySelectorAll('.nlist .nrow').length,
    convs:[...document.querySelectorAll('.chat-room .rail-list .conv .id')].map(e=>e.textContent.trim())}})()`

const clickTab = (label) => `(()=>{const b=[...document.querySelectorAll('.mc-tab')].find(e=>e.textContent.trim()===${JSON.stringify(label)});
  if(!b)return 'no-tab';b.click();return 'ok'})()`

const page = async (b) => { for (let i = 0; i < 30; i++) { const r = await b.evalJS(READ); if (r.tabs.length) return r; await sleep(300) } return b.evalJS(READ) }
/**
 * **整页重载**到某个 hash 路由。
 * ⚠️ 必须带一个一次性的 `?r=` —— 直接 `goto('…/#/x')` 在**只差 fragment** 时是**同文档导航**
 * （浏览器不重建文档）⇒ 应用**不会**重跑 `onMounted`：`AppShell` 的 `ref` 仍是上一轮的
 * 值、两个 5s 轮询也不重启 ⇒ 红点那几条断言会读到**陈旧状态**（本探针第一版就栽在这：
 * `notifyOnly` 那档读到的是上一轮被「进页清点」抹成 0 的值）。路径每次不同 ⇒ 必然整页重载。
 * ⚠️ `?r=` 在 hash **之前**，不属于路由的 query（`createWebHashHistory` 只认 `#` 之后）⇒ 不进 `to.query`。
 */
const fresh = (b, hash) => b.goto(`${BASE}/?r=${Math.random().toString(36).slice(2)}${hash}`, 2500)
/** 把 `Runtime.exceptionThrown` 收进一个数组（每开一个浏览器收一份）。 */
const catchErr = (b, sink) => b.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method === 'Runtime.exceptionThrown') sink.push(String(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text).slice(0, 200))
})

// ══ 客户侧 ═══════════════════════════════════════════════════════════════════
const b1 = await open({ w: 1440, h: 900, mock })
await b1.send('Page.addScriptToEvaluateOnNewDocument', {
  source: SEED({ loginUsername: 'i2probe', customerid: 'i2probe', role: '1' })
})
const errs1 = []
catchErr(b1, errs1)

await fresh(b1, '#/customer/msg')
let d = await page(b1)
ok('① 顶栏右侧只剩「消息 / 购物车 / 用户中心」（无「通知」项）',
  JSON.stringify(d.navs) === JSON.stringify(['消息', '购物车', '用户中心']), d.navs)
ok('② 页内两个 tab（会话 / 通知），默认停在「会话」',
  d.tabs.length === 2 && d.tabs[0].t === '会话' && d.tabs[0].on && d.tabs[1].t === '通知' && !d.tabs[1].on, d.tabs)
ok('③ 会话 tab = ChatRoom 原样渲染（`.chat-room` 的面板 + 会话条数）',
  d.chat === true && d.notify === false && d.convs.length >= 1, { chat: d.chat, convs: d.convs })
ok('⑤a 红点：未读会话 1 + 未读通知 2 ⇒ 亮', d.dot === true, d.dot)
await b1.shot(OUT + '01-customer-chat-tab.png')

// ── 切到「通知」tab ──────────────────────────────────────────────────────────
await b1.evalJS(clickTab('通知'))
await sleep(1200)
d = await b1.evalJS(READ)
ok('④ 切「通知」后：地址带 tab=notify、通知页渲染、会话页卸下',
  d.hash === '#/customer/msg?tab=notify' && d.tabs[1].on === true && d.notify === true && d.chat === false, d)
ok('④b 通知列表有行（原有功能不破：mock 的 2 条都在）', d.nlist === 2, d.nlist)
await b1.shot(OUT + '02-customer-notify-tab.png')

// ── 红点四组合（每次**整页重载**，在 2.5s 内读，避开 5s 轮询）────────────────
for (const k of ['notifyOnly', 'convOnly', 'none']) {
  mode = k
  await fresh(b1, '#/customer/msg')
  d = await page(b1)
  const want = MODES[k].conv + MODES[k].notif > 0
  ok(`⑤b 红点：会话 ${MODES[k].conv} + 通知 ${MODES[k].notif} ⇒ ${want ? '亮' : '灭'}`, d.dot === want, d.dot)
}
mode = 'both'

// ── 深链 /customer/notify ───────────────────────────────────────────────────
await fresh(b1, '#/customer/notify')
d = await page(b1)
ok('⑥ 深链 #/customer/notify ⇒ 改道到 …/msg?tab=notify 且落在通知 tab',
  d.hash === '#/customer/msg?tab=notify' && d.tabs[1].on === true && d.notify === true, { hash: d.hash, tabs: d.tabs })
ok('⑥b 深链落地的通知 tab 仍能看到通知行', d.nlist === 2, d.nlist)
await b1.shot(OUT + '03-customer-notify-deeplink.png')

// ── /customer/user/chat 不受影响 ────────────────────────────────────────────
await fresh(b1, '#/customer/user/chat')
d = await page(b1)
ok('⑦ 用户中心子路由 /customer/user/chat ⇒ 仍改道 …/msg（会话 tab），不受本轮影响',
  d.hash === '#/customer/msg' && d.tabs[0].on === true && d.chat === true, { hash: d.hash, tabs: d.tabs })

ok('⑧ 客户侧无 JS 异常', errs1.length === 0, errs1)

// ══ 商家侧（同款，换个壳）═══════════════════════════════════════════════════
const b2 = await open({ w: 1440, h: 900, mock })
await b2.send('Page.addScriptToEvaluateOnNewDocument', {
  source: SEED({ loginUsername: 'i2probe', merchantid: 'i2probe', role: '2' })
})
const errs2 = []
catchErr(b2, errs2)
await fresh(b2, '#/merchant/msg')
let e = await page(b2)
ok('⑨ 商家壳顶栏 = 「消息 / 订单中心 / 用户中心」（「通知」项同样已删）',
  JSON.stringify(e.navs) === JSON.stringify(['消息', '订单中心', '用户中心']), e.navs)
ok('⑩ 商家侧两个 tab 在，默认「会话」', e.tabs.length === 2 && e.tabs[0].on, e.tabs)
await fresh(b2, '#/merchant/notify')
e = await page(b2)
ok('⑪ 商家深链 #/merchant/notify ⇒ …/msg?tab=notify 且落通知 tab',
  e.hash === '#/merchant/msg?tab=notify' && e.tabs[1].on && e.notify === true, { hash: e.hash, tabs: e.tabs })
await b2.shot(OUT + '04-merchant-notify-tab.png')
ok('⑫ 商家侧无 JS 异常', errs2.length === 0, errs2)

const bad = R.dump()
fs.writeFileSync(EV + 'result.json', JSON.stringify({ ok: bad === 0, total: TOTAL, failed: bad, modes: MODES }, null, 1))
await b1.close(); await b2.close()
process.exit(bad === 0 ? 0 : 1)
