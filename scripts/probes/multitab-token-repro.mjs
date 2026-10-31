// multitab-token-repro.mjs —— 复现「新窗口退出登录，原窗口掉成未知账户」。
//
// 机理（不依赖浏览器，直接打后端的凭据链路）：
//   标签页 A 登录 → token T（同时 localStorage 指针 = T）。
//   新标签页 B 继承/认领的也是 T（sessionStorage 复制 / adoptSharedToken）。
//   B 点退出 → `POST /user/logout` 带着 T ⇒ 后端 revoke(T) + 作废共享 cookie。
//   A 手里仍是 T ⇒ 之后每发都是「票不认识」⇒ 401 / 身份丢失。
//
// 用法：node scripts/probes/multitab-token-repro.mjs
import fs from 'node:fs'

const BASE = 'http://127.0.0.1:8088'
const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/); if (m) CREDS[m[1]] = m[2]
}
const USER = ['1111', 'yunqi_digital', 'chenguang_parts'].find((u) => CREDS[u])
if (!USER) { console.log('demo-pw.txt 里没有可用账号'); process.exit(2) }

const cookieOf = (r) => (r.headers.getSetCookie ? r.headers.getSetCookie() : []).map((c) => c.split(';')[0]).join('; ')

async function login() {
  const r = await fetch(BASE + '/user/loginSelect', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: new URLSearchParams({ username: USER, password: CREDS[USER] }).toString()
  })
  const role = (await r.text()).trim()
  const cookie = cookieOf(r)
  return { role, cookie }
}

async function mint(cookie, bearer) {
  const r = await fetch(BASE + '/user/token', {
    method: 'POST',
    headers: { ...(cookie ? { Cookie: cookie } : {}), ...(bearer ? { Authorization: 'Bearer ' + bearer } : {}) }
  })
  let j = {}; try { j = await r.json() } catch (e) {}
  return { http: r.status, token: j.token || null }
}

async function me(cookie, bearer) {
  const r = await fetch(BASE + '/user/me', {
    method: 'POST',
    headers: { ...(cookie ? { Cookie: cookie } : {}), ...(bearer ? { Authorization: 'Bearer ' + bearer } : {}) }
  })
  let j = {}; try { j = await r.json() } catch (e) {}
  return { http: r.status, success: j.success, username: j.username, user: j.user && j.user.UserName }
}

async function logout(cookie, bearer) {
  const r = await fetch(BASE + '/user/logout', {
    method: 'POST',
    headers: { ...(cookie ? { Cookie: cookie } : {}), ...(bearer ? { Authorization: 'Bearer ' + bearer } : {}) }
  })
  return r.status
}

const a = await login()
console.log(`账号 ${USER} 登录：role=${a.role}，cookie=${a.cookie ? '有' : '无'}`)
const tA = await mint(a.cookie)
console.log(`A 铸票：HTTP=${tA.http} token=${tA.token ? tA.token.slice(0, 8) + '…' : '无'}`)
console.log('A 身份（修复前）：', JSON.stringify(await me(a.cookie, tA.token)))

// 新标签页 B：同一浏览器 ⇒ 继承同一张票 T，并共用同一份 cookie
console.log('\n[模拟] 新标签页 B 继承同一张票 T，并点「退出登录」…')
console.log('B logout：HTTP=' + await logout(a.cookie, tA.token))

const after = await me(a.cookie, tA.token)
console.log('A 之后再用自己的票 T：', JSON.stringify(after))
console.log('判定：' + (after.success ? 'A 仍有效（隔离成立）' : 'A 已掉线（复现：B 的退出销掉了 A 的票）'))

// ── 期望行为：B 启动时先给自己的标签页铸一张新票 T2，退出只销 T2 ──
console.log('\n[期望行为] 重新登录，B 先铸自己的票 T2，再退出…')
const a2 = await login()
const tA2 = await mint(a2.cookie)
const tB2 = await mint(a2.cookie, tA2.token)
console.log(`A 票=${tA2.token.slice(0, 8)}… / B 票=${tB2.token ? tB2.token.slice(0, 8) + '…' : '无'}`)
console.log('B logout（销自己的 T2）：HTTP=' + await logout(a2.cookie, tB2.token))
const after2 = await me(a2.cookie, tA2.token)
console.log('A 之后再用 T：', JSON.stringify(after2))
console.log('判定：' + (after2.success ? 'A 仍有效（每页一票 = 隔离成立）' : 'A 掉线'))
