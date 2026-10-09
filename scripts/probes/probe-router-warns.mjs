// scripts/probes/probe-router-warns.mjs —— 路由表「零 vue-router warn」回归探针（C3 块 2，2026-10-09）
//
// 【它守的是什么】`checkChildMissingNameWithEmptyPath`（`vue-router.mjs:848`）——
//   **命名的父记录**下挂一个**空路径且无名**的子记录时，`createRouter` 建匹配表就发一条 warn。
//   ⚠️ 它在**任何页面**都发（建表在模块加载期，与当前路由无关），所以判据取「随便打开一页，集合为空」。
//   现状：三壳的默认子记录（`/customer`、`/merchant` 的 HomeTab 与 `/customer/user` 的 UserInfoPage）
//   都补了同行 `name` ⇒ 应为 **0 条**。
//
// 【为什么值得单独立一条】它是**静默**的（只在 console 里），且**与业务无关** ——
//   哪天有人在某个命名父记录下新加一条空路径子记录，这里会立刻红，而不是等谁的 devtools 里多一行。
//   ⚠️ 判据是「集合为空」而非「没有某三条」：本探针不认名字，只认**这一类**（Vue Router warn）一条都不许有。
//
// 用法：node scripts/probes/probe-router-warns.mjs   （只需 dev server 8080；本探针不 mock 8088）
import { open } from '../../docs/美化/_cdp.mjs'

const PAGES = ['#/login', '#/customer', '#/merchant']   // 三个不同壳各看一遍（建表期发，但顺带确认没别处再发）
const b = await open({ w: 1280, h: 800 })

await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => {
    window.__warns = []
    const o = console.warn.bind(console)
    console.warn = (...a) => { try { window.__warns.push(a.map(String).join(' ')) } catch (e) {}; o(...a) }
  })()`,
})

let bad = 0
try {
  for (const h of PAGES) {
    await b.goto(`http://localhost:8080/${h}`, 2200)
    const w = (await b.evalJS('window.__warns || []')).filter((x) => /Vue Router warn/.test(x))
    console.log(`\n── ${h} ──`)
    console.log(w.length === 0 ? '（无 Vue Router warn）' : w.map((x) => '  - ' + x.slice(0, 160)).join('\n'))
    if (w.length !== 0) bad += 1
  }
  console.log('\n══ 判定 ══')
  console.log(bad === 0 ? '✅ 三个壳都零 vue-router warn' : `❌ ${bad} 个页面仍有 warn`)
  process.exitCode = bad === 0 ? 0 : 1
} finally {
  await b.close()
}
