// scripts/probes/probe-register-deeplink.mjs —— 「注册直达 `#/register`」两态取证（C3 块 2〈用户块〉，2026-10-09）
//
// 【为什么这个探针不需要后端】
//   `/login` 与 `/register` 都是**公开面**（`meta.public`），进页不发任何业务请求
//   ⇒ 只靠 dev server（8080）就能验「路由 + 初始 tab」这一整条链。
//   本探针**不 mock 8088**、也不要真后端在跑。
//
// 【判据（三条，缺一不可）】
//   ① `#/login`   → `.ctabs` 的第 1 枚 `.on`，`.brand-fig` **没有** `reg` 类
//   ② `#/register`→ `.ctabs` 的第 2 枚 `.on`，`.brand-fig` **有**  `reg` 类
//   ③ 在 `#/login` 上点「注册」→ URL 变成 `#/register`（`toggle()` 里那条 `replace`）；
//      再点「去登录」→ 回到 `#/login`。⚠️ 第 ③ 条是「tab ↔ URL 绑回同一处真源」的**唯一**证据：
//      少了它，刷新会把用户从注册面弹回登录面，而 ①② 都还是绿的。
//
// 用法：node scripts/probes/probe-register-deeplink.mjs   （只需 dev server 8080）
import { mkdirSync } from 'node:fs'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const OUT = 'docs/agent/evidence/c3-block2/register-deeplink'
mkdirSync(OUT, { recursive: true })   // `_cdp.shot` 只写文件、不建目录
const b = await open({ w: 1440, h: 900, headed: false })

/** 读当前态：URL + 两枚 tab 的 `.on` + 品牌图有没有 `reg`。 */
const read = () => b.evalJS(`(() => {
  const btns = Array.from(document.querySelectorAll('.ctabs button'))
  const fig = document.querySelector('.brand-fig')
  return {
    hash: location.hash,
    tabOn: btns.map(x => x.classList.contains('on')),   // [登录, 注册]
    tabText: btns.map(x => x.textContent.trim()),
    figReg: fig ? fig.classList.contains('reg') : null,
    figW: fig ? Math.round(fig.getBoundingClientRect().width) : null,
    figH: fig ? Math.round(fig.getBoundingClientRect().height) : null,
    registerPageOn: !!document.querySelector('.page[data-page="register"].on'),
    loginPageOn: !!document.querySelector('.page[data-page="login"].on'),
  }
})()`)

const show = (label, r) => {
  console.log(`\n── ${label} ──`)
  console.log(JSON.stringify(r, null, 2))
}

const verdicts = []
try {
  // ① 直达登录面
  await b.goto('http://localhost:8080/#/login', 1800)
  const login = await read()
  show('① #/login', login)
  verdicts.push(['① #/login 是登录面', login.hash === '#/login' && login.tabOn[0] === true && login.tabOn[1] === false && login.figReg === false && login.loginPageOn === true])
  await b.shot(`${OUT}/1-login.png`)

  // ② 直达注册面（**本笔的新路由**）
  await b.goto('http://localhost:8080/#/register', 1800)
  const reg = await read()
  show('② #/register', reg)
  verdicts.push(['② #/register 直达注册面', reg.hash === '#/register' && reg.tabOn[1] === true && reg.tabOn[0] === false && reg.figReg === true && reg.registerPageOn === true])
  await b.shot(`${OUT}/2-register.png`)

  // ③ 两态互切时 URL 跟着走（`toggle()` 里那条 `replace`）
  await b.goto('http://localhost:8080/#/login', 1500)
  await b.evalJS(`(() => { document.querySelectorAll('.ctabs button')[1].click(); return 1 })()`)
  await sleep(700)
  const toReg = await read()
  show('③a #/login 点「注册」后', toReg)
  verdicts.push(['③a 点注册 → URL 变 #/register', toReg.hash === '#/register' && toReg.tabOn[1] === true && toReg.figReg === true])

  await b.evalJS(`(() => { document.querySelectorAll('.ctabs button')[0].click(); return 1 })()`)
  await sleep(700)
  const toLogin = await read()
  show('③b 再点「登录」后', toLogin)
  verdicts.push(['③b 点登录 → URL 回 #/login', toLogin.hash === '#/login' && toLogin.tabOn[0] === true && toLogin.figReg === false])
  await b.shot(`${OUT}/3-after-toggle.png`)

  console.log('\n══ 判定 ══')
  let bad = 0
  for (const [name, pass] of verdicts) {
    console.log(`${pass ? '✅' : '❌'} ${name}`)
    if (!pass) bad += 1
  }
  console.log(bad === 0 ? '\n全部通过。' : `\n${bad} 条不通过。`)
  console.log('截图：' + OUT)
  process.exitCode = bad === 0 ? 0 : 1
} finally {
  await b.close()
}
