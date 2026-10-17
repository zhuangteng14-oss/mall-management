#!/usr/bin/env node
/**
 * 探针 · D 段 commit 2 —— **入站限流闸真的在拦吗，而且没误伤白名单**。
 *
 * 【它验哪两条裁定】
 *   ① 超限 → 429（阈值 200 QPS）
 *   ② 白名单（健康检查 / 静态资源 / 探针 / error 页）**不被误限**
 * ⚠️ 这两条是一条的两面：只验 ① 的话，「把白名单也一起限掉」照样全绿
 *    （那时运维看不见机器还活着）。故本探针**必须两段都跑**，任一段不符即退出码 1。
 *
 * 【为什么是「并发爆发」而不是「循环发」】
 * 阈值是 200 条 / 秒 —— 顺序发 300 条要好几秒，令牌自己就补回来了，**永远拦不到**，
 * 而那种探针会给出一个假绿的「没有 429」，看着像「闸没生效」。
 * ⇒ 一次性并发发出，让它们落在同一个刷新周期里。
 *
 * 【跑法】
 *   node scripts/probes/probe-d2-inbound-gate.mjs            # 默认 http://localhost:8088
 *   MALL_BASE=http://localhost:8088 node …                   # 换地址
 * 退出码 0 = 两条都成立；1 = 不成立。结果同时打印到 stdout（可重定向留证）。
 *
 * ⚠️ 前置：后端**必须**在跑，且是**带本笔代码**的那个构建。
 *    跑之前先看启动日志里那行 `[PROTECT] 入站限流闸已装配（PROTECT）：enabled=true 生效=true 阈值=200/1s 豁免 6 条`
 *    —— 没有那一行就别跑本探针（探针会红，而红的原因不是代码，是「你跑的是旧包」）。
 */

const BASE = process.env.MALL_BASE || 'http://localhost:8088'

/** 业务面（**不**在白名单里）：超过 200 条就该被拦。 */
const BUSINESS = { method: 'POST', path: '/goods/goodsSelectHome' }
/** 白名单面：发多少条都不该出现 429。 */
const EXEMPT = { method: 'GET', path: '/actuator/health' }

/** 爆发量。⚠️ 必须显著大于 200（阈值），否则「一条都没拦住」与「本来就不到阈值」分不开。 */
const BURST = 320

async function fire({ method, path }) {
  try {
    const res = await fetch(BASE + path, { method, redirect: 'manual' })
    return { status: res.status, body: res.status === 429 ? await res.text() : null }
  } catch (e) {
    return { status: 'ERR', error: String(e && e.message ? e.message : e) }
  }
}

function summarize(results) {
  const by = {}
  for (const r of results) by[r.status] = (by[r.status] || 0) + 1
  return by
}

function report(title, results) {
  const by = summarize(results)
  console.log(`· ${title}`)
  console.log(`    发出 ${results.length} 条 → ${Object.entries(by).map(([k, v]) => `${k}×${v}`).join('  ')}`)
  return by
}

let ok = true

console.log(`入站限流闸探针（D 段 · commit 2）  BASE=${BASE}  爆发=${BURST}`)
console.log('='.repeat(72))

// 读数取在**第一段之前**：计数器是进程级的，只有「跑本探针前后的差」才是本次的证据
const counterBefore = await readBlockedCounter()

// ---------- 第一段：业务面必须出现 429 ----------
const business = await Promise.all(Array.from({ length: BURST }, () => fire(BUSINESS)))
const businessBy = report(`业务面 ${BUSINESS.method} ${BUSINESS.path}（不在白名单 ⇒ 超 200 应被拦）`, business)
const blocked = businessBy[429] || 0
if (blocked > 0) {
  console.log(`    ✓ 出现 ${blocked} 条 429 —— 闸在拦`)
} else {
  ok = false
  console.log('    ✗ **一条 429 都没有**：要么闸没生效（跑的是旧包？），要么白名单把它也放过了')
}

// 429 的体：前端 http.js 的失败分支读 success / msg 两个键
const sample = business.find((r) => r.status === 429 && r.body)
if (sample) {
  const shaped = sample.body.includes('"success"') && sample.body.includes('"msg"')
  console.log(`    429 体样例：${sample.body.slice(0, 160)}`)
  console.log(shaped ? '    ✓ 体里有 success / msg 两个键（形状与 Result.fail 同款）'
    : '    ✗ 体里缺 success / msg —— 前端 http.js 读不到这两个键')
  if (!shaped) ok = false
} else if (blocked > 0) {
  console.log('    ⚠️ 抓到了 429，但体为空 —— 再跑一次留证')
}

// ---------- 第二段：白名单**一条都不许** 429 ----------
console.log('')
const exempt = await Promise.all(Array.from({ length: BURST }, () => fire(EXEMPT)))
const exemptBy = report(`白名单面 ${EXEMPT.method} ${EXEMPT.path}（豁免 ⇒ 一条都不该拦）`, exempt)
const exemptBlocked = exemptBy[429] || 0
if (exemptBlocked === 0) {
  console.log('    ✓ 零 429 —— 探针不会被自己的限流闸误伤（运维仍看得见这台机器活着）')
} else {
  ok = false
  console.log(`    ✗ 白名单被拦了 ${exemptBlocked} 条 —— 这正是裁定逐字要防的「误伤探针」`)
}

// ---------- 第三段：拦下的那些**记进第 9 类事件环**了吗 ----------
// ⚠️ 判据是**增量**（跑本探针前后各读一次），不是绝对值 —— 计数器是进程级的，
//    上一次跑本探针留下的数会让「绝对值 = 本次 429 数」永远不成立。
// ⚠️ 而且**不能另起一小串请求来测增量**：40 条打在 200 的阈值下**一条都不会被拦**
//    ⇒ 增量必然是 0，探针会红在一个与它无关的地方（这是本探针第一版的真错，留在这里）。
console.log('')
const counterAfter = await readBlockedCounter()
console.log('· 第 9 类事件环 mall.protection.events{protection=rateLimit,result=blocked}')
console.log(`    跑本探针前 ${counterBefore === null ? '取不到' : counterBefore} → 跑完 ${counterAfter === null ? '取不到' : counterAfter}`)
if (counterBefore === null || counterAfter === null) {
  ok = false
  console.log('    ✗ 取不到该指标（/actuator/metrics 没暴露？名字改了？）—— 闸拦下了但**记不下来**，'
    + '页面上就是一片安静')
} else {
  const delta = counterAfter - counterBefore
  console.log(`    本次增量 ${delta}；第一段实测被拦 ${blocked} 条`)
  if (delta === blocked) {
    console.log('    ✓ 恰好相等 ⇒「闸拦下 → 记进第 9 类环」这条链在运行时是通的，一条不多一条不少')
  } else if (delta >= blocked) {
    console.log(`    ✓ 增量 ${delta} ≥ 被拦 ${blocked} —— 差额是本探针跑的**别的**流量留下的（同机其它请求）`)
  } else {
    ok = false
    console.log(`    ✗ 增量 ${delta} 小于被拦数 ${blocked} —— 有一部分拦下没被记（页面会少算）`)
  }
}

// 连接错误的如实报（不算闸的错，但不许静默吞掉）
const errs = [...business, ...exempt].filter((r) => r.status === 'ERR')
if (errs.length) {
  console.log('')
  console.log(`⚠️ 有 ${errs.length} 条请求**根本没到后端**（连接层失败），首个原因：${errs[0].error}`)
  console.log('    —— 这是压测侧（本机并发 / 临时端口）的限度，不是闸的行为；上面每一段的判据都只对'
    + '「拿到响应码的那些」下结论，没有把 ERR 算成放行或拦下。')
}

console.log('='.repeat(72))
console.log(ok ? '结论：拦得住 + 没误伤 + 拦下的都记了 ✓' : '结论：不成立 ✗（退出码 1）')
process.exit(ok ? 0 : 1)

/**
 * 读 `mall.protection.events{protection=rateLimit,result=blocked}` 的当前值。
 * 取不到（端点未暴露 / 计数器尚不存在）返回 null —— 由调用方判成红，不在这里抛。
 */
async function readBlockedCounter() {
  try {
    const res = await fetch(`${BASE}/actuator/metrics/mall.protection.events`
      + '?tag=protection:rateLimit&tag=result:blocked')
    if (!res.ok) return null
    const json = await res.json()
    const m = (json.measurements || []).find((x) => x.statistic === 'COUNT')
    return m ? m.value : 0
  } catch {
    return null
  }
}
