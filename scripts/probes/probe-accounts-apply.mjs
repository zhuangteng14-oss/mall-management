// scripts/probes/probe-accounts-apply.mjs —— 块 2 两个**新鉴权页**的渲染取证
//   · 管理端 `views/admin/AccountsPage.vue`      （`#/admin/business/accounts`，role=3）
//   · 用户端 `views/MerchantApplyPage.vue`       （`#/customer/user/apply`，role=1）
//   （C3 块 2〈用户块〉，2026-10-09）
//
// 【为什么必须 mock 8088、而不是打真后端】
//   用户 IDEA 里跑着的 8088 进程**早于**本笔（没有 `AdminAccountsController` / `MerchantApplication*`
//   ⇒ 真打过去是 404），而那条进程**不得杀掉**（用户口径）。故本探针只验**前端这一侧**：
//   路由是否挂对、身份判据是否放行、两条链路是否**真的按预期端点**发请求、渲染有没有崩。
//   ⚠️ 它**不证明**后端行为 —— 后端那半边由 `MvnTest`（239/0/0）与后续真机验收覆盖。
//
// 【身份怎么进页面】`main.js` 的 `restoreIdentity()` 第一行是 `if (isLoggedIn()) return null`
//   ⇒ 只要在**文档加载之前**把 `sessionStorage` 写好，全站**一次网络都不打**就认下这个身份，
//   也不会触发 `settleRecoveredIdentity` 的「管理员退回登录页」那一档（它要求 `recovered.success`）。
//   落点 = CDP `Page.addScriptToEvaluateOnNewDocument`（在页面任何脚本之前跑）。
//   ⚠️ 存 `loginUsername` 而不是只存 `role`：`isLoggedIn() = !!(getUsername() || getMerchantId())`。
//
// 【判据】① 管理端四段（入驻审核 / 用户治理 / 降级控制 / 管理员收件箱）都渲染出内容
//         ② 管理端**真的**打了 `/admin/accounts/*` 与 `/notification/*`
//         ③ 用户端入驻页表单 + 我的申请都渲染
//         ④ 用户端**真的**打了 `/merchant/myApplications`
//         ⑤ 两页**没有多出**任何 console error / Vue warn / 未捕获异常
//            ⚠️ ⑤ 是**基线差分**，不是「零 warn」。理由（2026-10-09 实测）：`#/login` 上就已经有
//               **三条** vue-router warn（`customer` / `customerUser` / `merchant` 三个**命名的父记录**
//               各带一个**无名空路径子记录**）—— 它们在 `createRouter` 建匹配表时就发，**与当前在哪个
//               页面无关**。判「零 warn」等于把这三条既有噪音算成本笔的账，且这条断言从此永远红。
//               故先取基线页面的 warn 集合，再断言两页的集合**与之相等**（不多不少）。
//               ⚠️ 基线那三条**是既有缺陷**（`customer`/`merchant` 来自块 1、`customerUser` 来自本笔的
//               同行 `name:` 追加），零功能影响（全仓无 `push({name:'customer'})`）—— 已单独上报，本探针**不修**。
//
// 用法：node scripts/probes/probe-accounts-apply.mjs   （只需 dev server 8080 + npm i ws 已装）
import { mkdirSync } from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const OUT = 'docs/agent/evidence/c3-block2/accounts-apply'
mkdirSync(OUT, { recursive: true })   // `_cdp.shot` 只写文件、不建目录

const R = report('probe-accounts-apply')

// ── 在文档加载前：播种身份 + 装错误探针 ──────────────────────────────────────
const seedScript = (seed) => `(() => {
  try { for (const [k, v] of Object.entries(${JSON.stringify(seed)})) sessionStorage.setItem(k, v) } catch (e) {}
  window.__errs = []; window.__logs = []
  window.addEventListener('error', (e) => window.__errs.push('error: ' + (e.message || e)))
  window.addEventListener('unhandledrejection', (e) =>
    window.__errs.push('reject: ' + String((e.reason && (e.reason.message || e.reason)) || e)))
  const wrap = (k) => {
    const o = console[k].bind(console)
    console[k] = (...a) => {
      try { window.__logs.push(k + ': ' + a.map((x) => (x && x.message) || String(x)).join(' ')) } catch (e) {}
      o(...a)
    }
  }
  wrap('error'); wrap('warn')
})()`

const json = (o) => ({ status: 200, body: JSON.stringify(o) })

// ── 夹具（行结构照 `AccountsPage.vue` / `MerchantApplyPage.vue` 真读的键）─────
const APPS = [
  { Id: 7, Username: 'zhangsan', ShopName: '米可数码专营店', LicenseNo: '91310000MA1K3XYZ8Q',
    ContactName: '张三', ContactPhone: '13800001111', ShopAddress: '上海市浦东新区示例路 1 号',
    LicenseImage: null, Status: 'pending', RejectReason: null, BatchId: 'b2-1111-2222-3333',
    ReviewedBy: null, SubmittedAt: '2026-10-09 03:12:44', ReviewedAt: null },
  { Id: 6, Username: 'lisi', ShopName: '四四便利店', LicenseNo: '91310000MA1K3AAA1Q',
    ContactName: '李四', ContactPhone: null, ShopAddress: null, LicenseImage: null,
    Status: 'rejected', RejectReason: '执照号与工商登记不符', BatchId: 'b2-3333-4444-5555',
    ReviewedBy: 'admin', SubmittedAt: '2026-10-08 01:00:00', ReviewedAt: '2026-10-08 02:00:00' },
]
const USERS = [
  { username: 'zhangsan', usertype: '客户', customerid: 'C1001', merchantid: null,
    banned: 1, banReason: '恶意刷单', bannedAt: '2026-10-09 05:00:00', DowngradeState: 'none', DowngradeReason: null },
  { username: 'wangwu', usertype: '商家', customerid: 'C1002', merchantid: 'M2001',
    banned: false, banReason: null, bannedAt: null, DowngradeState: 'scheduled',
    DowngradeReason: '连续三周零成交' },
]
const NOTICES = [
  { Id: 31, BatchId: 'b2-7777-8888', ToId: '*', ToType: 'admin', EventType: 'merchant.apply.submitted',
    Title: '新的商家入驻申请', Content: 'zhangsan 提交了「米可数码专营店」的入驻申请（批次 b2-1111-2222-3333）',
    TargetType: 'merchant_application', TargetId: 'b2-1111-2222-3333', ReadAt: null,
    CreatedAt: '2026-10-09 03:12:44' },
]
const MINE = [
  { Id: 7, Username: 'zhangsan', ShopName: '米可数码专营店', LicenseNo: '91310000MA1K3XYZ8Q',
    Status: 'pending', RejectReason: null, BatchId: 'b2-1111-2222-3333',
    SubmittedAt: '2026-10-09 03:12:44', ReviewedAt: null },
  { Id: 3, Username: 'zhangsan', ShopName: '旧铺子', LicenseNo: '91310000MA1K3OLD9Q',
    Status: 'rejected', RejectReason: '店铺名称与执照不符', BatchId: 'b2-0000-0000',
    SubmittedAt: '2026-10-01 08:00:00', ReviewedAt: '2026-10-02 09:00:00' },
]

/** 造一条 mock：按 **pathname** 分派，并**记下每一次调用**（③ 的判据靠它）。 */
function makeMock(table, calls) {
  return (url, method) => {
    let pathname = url
    try { pathname = new URL(url).pathname } catch (e) { /* 保持原样 */ }
    calls.push(`${method} ${pathname}`)
    if (method === 'OPTIONS') return { status: 200, body: '' }   // 预检必须放行，否则连不上
    for (const [needle, body] of table) if (pathname.includes(needle)) return json(body)
    return json({ success: false, msg: `mock 未覆盖：${method} ${pathname}` })
  }
}

/** 开一个会话：先装种子脚本、再导航。 */
async function session(seed, table, url, waitMs) {
  const calls = []
  const b = await open({ w: 1440, h: 900, mock: makeMock(table, calls) })
  await b.send('Page.addScriptToEvaluateOnNewDocument', { source: seedScript(seed) })
  await b.goto(url, waitMs)
  return { b, calls }
}

/** 读页面的健康度：未捕获错误 + 被记为 error/warn 的 console（Vue 的告警走 warn）。 */
const health = (b) => b.evalJS(`(() => ({
  errs: window.__errs || [],
  logs: (window.__logs || []).filter((l) => /^error/.test(l)),
  warns: (window.__logs || []).filter((l) => /^warn/.test(l)),
  hash: location.hash,
}))()`)

const uniq = (a) => [...new Set(a)]

/** 取一个**与两个新页无关**的公开页的 console 噪音，当 ⑤ 的基线（见文件头 ⑤）。 */
async function baseline() {
  const { b } = await session({}, [['/user/me', { success: false }]], 'http://localhost:8080/#/login', 2200)
  const h = await health(b)
  await b.close()
  console.log('\n── ⓪ 基线（#/login）──')
  console.log(JSON.stringify({ warns: h.warns, errs: h.errs, logs: h.logs }, null, 2))
  return h
}

/** 集合相等（本次 = 基线，不多不少）。 */
const sameSet = (a, b) => a.length === b.length && [...a].sort().join('\u0000') === [...b].sort().join('\u0000')

async function adminSide(base) {
  const { b, calls } = await session(
    { loginUsername: 'admin', role: '3', authToken: 'tk-admin-probe' },
    [
      ['/admin/accounts/applications', { success: true, list: APPS, total: 2, limit: 100 }],
      ['/admin/accounts/users', { success: true, list: USERS, total: 2, limit: 100 }],
      ['/notification/list', { success: true, list: NOTICES, total: 1, unread: 1 }],
      ['/notification/unreadCount', { success: true, unread: 1 }],
      ['/message/unreadCount', { success: true, unread: 0 }],
      ['/user/me', { success: false }],
    ],
    'http://localhost:8080/#/admin/business/accounts', 2600,
  )

  const dom = await b.evalJS(`(() => {
    const txt = (s) => { const e = document.querySelector(s); return e ? e.textContent.trim() : null }
    const n = (s) => document.querySelectorAll(s).length
    return {
      root: !!document.querySelector('.a-acc'),
      h2: Array.from(document.querySelectorAll('.a-h2')).map((e) => e.firstChild.textContent.trim()),
      stats: Array.from(document.querySelectorAll('.a-stat')).map((e) => e.textContent.replace(/\\s+/g, ' ').trim()),
      appCards: document.querySelectorAll('.a-stream')[0] ? document.querySelectorAll('.a-stream')[0].children.length : 0,
      userCards: document.querySelectorAll('.a-stream')[1] ? document.querySelectorAll('.a-stream')[1].children.length : 0,
      appBadges: Array.from(document.querySelectorAll('.a-card .a-badge')).map((e) => e.textContent.trim()),
      firstApp: (document.querySelector('.a-stream li') || {}).textContent ? document.querySelector('.a-stream li').textContent.replace(/\\s+/g, ' ').trim().slice(0, 90) : null,
      inbox: !!document.querySelector('.a-inbox .notify-room'),
      inboxText: (document.querySelector('.a-inbox') || {}).innerText ? document.querySelector('.a-inbox').innerText.replace(/\\s+/g, ' ').trim().slice(0, 120) : null,
      tz: txt('.a-tz'),
    }
  })()`)
  const h = await health(b)
  await b.shot(`${OUT}/1-admin-accounts.png`)
  await b.shotFull(`${OUT}/1-admin-accounts-full.png`)
  await b.close()

  console.log('\n── ① 管理端 #/admin/business/accounts ──')
  console.log(JSON.stringify({ dom, calls: uniq(calls), ...h }, null, 2))

  R.ok('① 页面根 .a-acc 渲染', dom.root === true)
  R.ok('① 四段 <h2> 齐全', dom.h2.length === 4 && dom.h2[0] === '商家入驻审核' && dom.h2[1] === '用户治理' && dom.h2[2] === '商家降级控制' && dom.h2[3] === '管理员收件箱', dom.h2)
  R.ok('① 状态条三个大数来自 mock（待审 1 / 用户 2 / 封禁 1）',
    dom.stats.length === 3 && /^1\b/.test(dom.stats[0]) && /^2\b/.test(dom.stats[1]) && /^1\b/.test(dom.stats[2]), dom.stats)
  R.ok('① 入驻申请渲染 2 条（含 pending / rejected 徽章）', dom.appCards === 2 && dom.appBadges.includes('待审') && dom.appBadges.includes('已驳回'), dom.appBadges)
  R.ok('① 用户卡片渲染 2 条（含「已封禁」与「降级已排定」徽章）', dom.userCards === 2 && dom.appBadges.includes('已封禁') && dom.appBadges.includes('降级已排定'), dom.appBadges)
  R.ok('① 收件箱挂上了（.a-inbox > .notify-room）', dom.inbox === true && (dom.inboxText || '').includes('新的商家入驻申请'), dom.inboxText)
  R.ok('② 打了 /admin/accounts/applications', calls.some((c) => c.includes('/admin/accounts/applications')))
  R.ok('② 打了 /admin/accounts/users', calls.some((c) => c.includes('/admin/accounts/users')))
  R.ok('② 收件箱用 ToId=* 打了 /notification/list', calls.some((c) => c.includes('/notification/list')))
  R.ok('⑤ 管理端未多出 console 噪音（warn 集合 = 基线，无 error、无未捕获异常）',
    h.errs.length === 0 && h.logs.length === 0 && sameSet(h.warns, base.warns),
    { errs: h.errs, logs: h.logs, warns: h.warns, baseWarns: base.warns.length })
}

async function customerSide(base) {
  const { b, calls } = await session(
    { loginUsername: 'zhangsan', customerid: 'C1001', role: '1', authToken: 'tk-cust-probe' },
    [
      ['/merchant/myApplications', { success: true, list: MINE, total: 2, limit: 50 }],
      ['/notification/unreadCount', { success: true, unread: 0 }],
      ['/message/unreadCount', { success: true, unread: 0 }],
      ['/user/me', { success: false }],
    ],
    'http://localhost:8080/#/customer/user/apply', 2600,
  )

  const dom = await b.evalJS(`(() => {
    const n = (s) => document.querySelectorAll(s).length
    const q = (s) => { const e = document.querySelector(s); return e ? e.textContent.replace(/\\s+/g, ' ').trim() : null }
    return {
      root: !!document.querySelector('.uc-main'),
      title: q('.page-h-t'),
      headNote: q('.page-h-n'),
      form: !!document.querySelector('form.f'),
      fields: Array.from(document.querySelectorAll('.fld > span')).map((e) => e.textContent.replace(/\\s+/g, ' ').trim()),
      submit: (document.querySelector('button[type=submit]') || {}).textContent ? document.querySelector('.f-acts button').textContent.trim() : null,
      apps: n('.apps .app'),
      appStatus: Array.from(document.querySelectorAll('.apps .app .badge')).map((e) => e.textContent.trim()),
      rejectWhy: q('.apps .why'),
      whoMissing: !!document.querySelector('.ph-note'),
    }
  })()`)
  const h = await health(b)
  await b.shot(`${OUT}/2-customer-apply.png`)
  await b.shotFull(`${OUT}/2-customer-apply-full.png`)
  await b.close()

  console.log('\n── ③ 用户端 #/customer/user/apply ──')
  console.log(JSON.stringify({ dom, calls: uniq(calls), ...h }, null, 2))

  R.ok('③ 页面根 .uc-main 渲染 + 标题「商家入驻」', dom.root === true && dom.title === '商家入驻', dom.title)
  R.ok('③ 提交表单在位（6 个字段 + 提交按钮）', dom.form === true && dom.fields.length === 6 && dom.fields[0].startsWith('店铺名称') && dom.fields[1].startsWith('营业执照号'), dom.fields)
  R.ok('③ 页头计数来自 mock（共 2 条 · 待审 1）', (dom.headNote || '').includes('共 2 条') && (dom.headNote || '').includes('待审 1'), dom.headNote)
  R.ok('③ 我的申请渲染 2 条（待审 / 已驳回）', dom.apps === 2 && dom.appStatus.includes('待审') && dom.appStatus.includes('已驳回'), dom.appStatus)
  R.ok('③ 驳回理由显示给申请人', (dom.rejectWhy || '').includes('店铺名称与执照不符'), dom.rejectWhy)
  R.ok('④ 打了 /merchant/myApplications', calls.some((c) => c.includes('/merchant/myApplications')))
  R.ok('⑤ 用户端未多出 console 噪音（warn 集合 = 基线，无 error、无未捕获异常）',
    h.errs.length === 0 && h.logs.length === 0 && sameSet(h.warns, base.warns),
    { errs: h.errs, logs: h.logs, warns: h.warns, baseWarns: base.warns.length })
}

try {
  const base = await baseline()
  await adminSide(base)
  await customerSide(base)
} finally {
  console.log('\n══ 判定 ══')
  const bad = R.dump()
  console.log('截图：' + OUT)
  process.exitCode = bad === 0 ? 0 : 1
}
