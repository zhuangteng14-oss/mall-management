// c3b4p-p2-tz-display.mjs —— 验「我的订单」列表里的时间**已经按浏览器时区显示**（问题 2 的前端一半）。
//
// 【为什么要这条探针】后端时间列全部是**库时钟**、库时钟是 **UTC**（VM 实测 `@@system_time_zone=UTC`）
//   ⇒ 交回前端的是 `'YYYY-MM-DD HH:mm:ss'` 这种**无时区文本**。
//   改前 `new Date(str)` 按**浏览器本地**解释它 ⇒ 屏幕上与库里**一模一样**（东八区下"少 8 小时"的手感）。
//   改后补末尾 `Z` 当 UTC 解析 ⇒ 屏幕上 = 库里 **+ 浏览器偏移**（东八区即 +8h）。
//
// 【判据】把**同一个订单**的「库里值」（调用方用 mysql 查出来传进来）与「屏幕上值」对齐比：
//   DB 值 + tzMin 分钟 === 屏幕值。相等 = 转换生效；相等不了而屏幕恰等于 DB 原样 = **没转换**（改前）。
//   时间列用「结单日期」`SettlementDate`（`.time`，见 `views/OrderMine.vue:96`）—— 它与轨迹
//   `TrackedAt` 走的是**同一个** `formatDateTime`，前者可查、后者要新后端的 `orderDetail`。
//
// 用法：node scripts/probes/c3b4p-p2-tz-display.mjs <OrderId> <库里SettlementDate>
//   例：node scripts/probes/c3b4p-p2-tz-display.mjs KTLQ27366931 '2026-10-10 14:29:16'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const CUSTOMER = 'pIzTWafaGp'
const URL = 'http://localhost:8080/#/customer/cart?tab=orders'
const [wantId, wantDb] = process.argv.slice(2)

const b = await open({ w: 1440, h: 900 })
try {
  await b.goto('http://localhost:8080/#/login', 1200)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeP2');sessionStorage.setItem('role','1');sessionStorage.setItem('customerid','${CUSTOMER}');1`)
  await b.goto(URL, 3500)
  await sleep(1500)

  // 每行「已完成」是 6 个兄弟 div（`.oid` 是订单号、`.time` 是结单日期）⇒ 取共同父节点把两者配起来。
  const s = JSON.parse(await b.evalJS(`JSON.stringify((() => ({
    tzMin: -new Date().getTimezoneOffset(),
    行: [...document.querySelectorAll('.trow.done')].map(r => ({
      订单号: (r.querySelector('.oid') || {}).textContent?.trim() || null,
      结单日期: (r.querySelector('.time') || {}).textContent?.trim() || null
    }))
  }))())`))

  console.log(`设备时区偏移 tzMin = ${s.tzMin} 分钟（东八区 = 480）`)
  console.log('页面上「已完成」各行：')
  for (const r of s.行) console.log(`  ${r.订单号}  →  ${r.结单日期}`)

  const row = s.行.find((r) => r.订单号 === wantId)
  if (!wantId || !wantDb) {
    console.log('\n（没给 OrderId / 库值，只做输出，不做断言）')
    await b.shot('scripts/probes/out-c3b4p-p2-tz.png')
  } else if (!row) {
    console.log(`\n❌ 页面上没找到订单 ${wantId} —— 该单不在本客户的「已完成」里？`)
  } else {
    const [y, mo, d, hh, mm, ss] = wantDb.split(/[- :]/).map(Number)
    const shift = new Date(Date.UTC(y, mo - 1, d, hh, mm, ss) + s.tzMin * 60 * 1000)
    const p = (n) => String(n).padStart(2, '0')
    const exp = `${shift.getUTCFullYear()}-${p(shift.getUTCMonth() + 1)}-${p(shift.getUTCDate())} `
      + `${p(shift.getUTCHours())}:${p(shift.getUTCMinutes())}:${p(shift.getUTCSeconds())}`
    const got = row.结单日期
    const okConverted = got === exp
    const looksRaw = got === wantDb
    console.log(`\n订单 ${wantId}`)
    console.log(`  库里 SettlementDate = ${wantDb}（库时钟 UTC）`)
    console.log(`  期望屏幕值          = ${exp}（+= tzMin）`)
    console.log(`  实际屏幕值          = ${got}`)
    console.log(`${okConverted ? 'PASS' : 'FAIL'}  ① 屏幕值 = 库值 + 浏览器偏移（转换生效）`)
    console.log(`${looksRaw ? 'FAIL' : 'PASS'}  ② 屏幕值 ≠ 库值原样（不是"没转换"）`)
    console.log(okConverted && !looksRaw ? '\n✅ 问题 2 的前端这一半成立' : '\n❌ 未过')
    await b.shot('scripts/probes/out-c3b4p-p2-tz.png')
  }
} finally {
  await b.close()
}
