#!/usr/bin/env node
/**
 * 探针 · 块 7 / D 段 —— **死锁侦查**（TD-74「先有死锁实测」那条前置的取证）
 *
 * 【它只做一件事】把**产品自己的两个真实端点**并发打在一起，看 MySQL 会不会自己判出死锁。
 *   ⚠️ 它**不**用裸 SQL 造环（那是伪造），也**不**为了「让重试能做」先落重试。
 *
 * 【场景：同一商家的两条管理动作撞在同一个账号上】
 *   A（慢起手）POST /admin/accounts/application/audit  id=<id>&approve=true
 *       → MerchantApplicationService.audit（@Transactional，:141/:142）
 *         写序：merchant_application → **user** → login → merchantimage → user → **goods**
 *         （最后那条 goods 只在「DowngradeState='frozen' 且 FreezeUntil 未过」时才走到 ——
 *           见 MerchantApplicationService.restoreIfFrozenWithinWindow；实测未冻结时用不发生）
 *   B（快起手）POST /admin/accounts/downgrade/relist   username=__dlprobe__
 *       → MerchantDowngradeService.relist（@Transactional，:267/:268）
 *         写序：**goods** → **user**
 *   ⇒ {goods, user} 两表**反向**。两个事务同时持着对方要的那把，即构成环。
 *   ⚠️ 两条写序都是从源码静态读出来的，不是猜的；`goods` / `user` 两张表在库上
 *      **只有 PRIMARY KEY**（`goods` 无 MerchantId 索引、`user` 无 merchantid 索引）
 *      ⇒ 两条 UPDATE 都是**全表扫描 + 全表加锁**，争用窗口因此不依赖运气。
 *
 * 【为什么每轮打 K 个 B】A 要过约 5 条语句才碰 user 行，B 第 2 条语句就锁 goods。
 *   K 个 B 排队在 goods 上 ⇒ goods 被连续持有一段时间；A 拿到 user 行后在 goods 上排队，
 *   此刻队首那个 B 释放 goods、转去要 user 行 —— 正好撞在 A 手里，环闭合。
 *   K 只放大窗口、**不是开关**：K=1（各一发，即最小场景）实测也有 14/72 轮撞上
 *   —— 两遍 36 轮分别跑出 10/36 与 4/36，**单点数字不可复现，要按分布看**。
 *
 * 【为什么还要扫「错开量」】两者的相对时序是本实验唯一的自由变量：
 *   要让 A 先拿到 user 行（A 起手更慢 ⇒ 需要 A **先发**一点），又要让 B 还握着 goods。
 *   两个条件的交集只有几毫秒宽，且依赖当时的下游延迟 —— 所以**扫**一遍而不是拍一个数。
 *
 * 【判据（三条，任一条非零即「真死了」）】
 *   ① MySQL error 1213（ER_LOCK_DEADLOCK）在 performance_schema 里的计数增量
 *   ② SHOW ENGINE INNODB STATUS 的 LATEST DETECTED DEADLOCK 段换成**本场景**（含 user/goods/merchant_application）
 *   ③ HTTP 5xx（实测：Spring 把 MySQL 1213 翻成 **CannotAcquireLockException**，
 *      root cause `MySQLTransactionRollbackException: Deadlock found when trying to
 *      get lock`；**不是** DeadlockLoserDataAccessException —— 后者在本仓 167 条现场里 0 次）
 *
 * 【跑法】
 *   node scripts/probes/d2-deadlock-scout.mjs                 # 默认：扫错开量，48 轮
 *   ROUNDS=48 KB=4 node scripts/probes/d2-deadlock-scout.mjs  # 环境变量可覆盖
 *   node scripts/probes/d2-deadlock-scout.mjs --cleanup       # 只清夹具
 * 退出码：0 = 至少死了一次（判据①③任一非零）；1 = 一轮都没死。
 *
 * ⚠️ 前置：后端在跑、且是带本笔代码的构建；profile **dev 或默认**（a3 下调度器总闸关着，与真实并发不同）。
 * ⚠️ 会往开发库写**探针自己的**夹具行，跑完自动清（`--cleanup` 可手动补）。
 * ⚠️ 凭据只从 application-dev.properties 就地取，**不打印、不进任何输出**。
 */

import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const BASE = process.env.BASE || 'http://localhost:8088'
const ROUNDS = Number(process.env.ROUNDS || 48)
const KB = Number(process.env.KB || 4)
const U = '__dlprobe__'            // 探针账号（user.username 是 char(20)，够）
const M = 'dlprobeM01'             // 探针商家号

// ── 凭据：就地剥壳，不打印 ────────────────────────────────────────────
const PROP = join(ROOT, 'Mall-management-server/src/main/resources/application-dev.properties')
const prop = (k) => {
  const line = readFileSync(PROP, 'utf8').split(/\r?\n/).find((l) => l.startsWith(k + '='))
  if (!line) throw new Error('properties 里找不到 ' + k)
  let v = line.slice(k.length + 1)
  const m = v.match(/^\$\{[^:]*:(.*)\}$/)
  if (m) v = m[1]
  return v
}
const DB_URL = prop('spring.datasource.url')
const DB_USER = prop('spring.datasource.username')
const DB_PASS = prop('spring.datasource.password')
const m = DB_URL.match(/jdbc:mysql:\/\/([^:/]+):(\d+)\/([^?]+)/)
if (!m) throw new Error('datasource url 形状不认识')
const [, HOST, PORT, DBNAME] = m

const MYSQL = process.env.MYSQL_BIN || 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'

function sql(q, { table = false } = {}) {
  const r = spawnSync(MYSQL, ['-h', HOST, '-P', PORT, '-u', DB_USER, '-D', DBNAME,
    '--default-character-set=utf8mb4', ...(table ? ['--table'] : ['-N', '-B']), '-e', q],
    { env: { ...process.env, MYSQL_PWD: DB_PASS }, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  if (r.error) throw r.error
  if (r.status !== 0) throw new Error('mysql 退出码 ' + r.status + ': ' + (r.stderr || '').slice(0, 400))
  return (r.stdout || '').trim()
}
const one = (q) => sql(q).split(/\r?\n/).filter(Boolean).pop()

const FIXTURE_SQL = (opts = {}) => `
INSERT INTO \`user\` (username,usertype,customerid,merchantid,gender,age,DowngradeState,FrozenAt,FreezeUntil)
  VALUES ('${U}','商家','dlprobeC01','${M}','男',20,'frozen',NOW(3),DATE_ADD(NOW(3),INTERVAL 10 DAY))
  ON DUPLICATE KEY UPDATE usertype='商家',merchantid='${M}',DowngradeState='frozen',
    FrozenAt=NOW(3),FreezeUntil=DATE_ADD(NOW(3),INTERVAL 10 DAY);
INSERT INTO \`login\` (UserName,Password,Role,Id) VALUES ('${U}','probe',2,'${M}')
  ON DUPLICATE KEY UPDATE Role=2, Id='${M}';
INSERT INTO goods (MerchantId,GoodName,Price,GoodImg,GoodType,ProductCode,GoodTypeNew,ShelfState)
  SELECT '${M}','探针商品',1,'http://localhost:8088/img/x.jpg','服装','dlprobeP01',0,0 FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM goods WHERE MerchantId='${M}' AND ProductCode='dlprobeP01');
INSERT INTO merchant_application (Username,ShopName,LicenseNo,Status,BatchId)
  SELECT '${U}','探针店铺','L-PROBE-1','pending','dlprobe' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM merchant_application WHERE Username='${U}');`

/** 每轮复位：申请打回 pending、账号退回「冻结且窗口内」——环成立的前提态。 */
const RESET_SQL = `
UPDATE merchant_application SET Status='pending', ReviewedBy=NULL, ReviewedAt=NULL WHERE Username='${U}';
UPDATE \`user\` SET DowngradeState='frozen', FrozenAt=NOW(3), FreezeUntil=DATE_ADD(NOW(3),INTERVAL 10 DAY),
  usertype='商家', merchantid='${M}' WHERE username='${U}';`

const CLEANUP_SQL = `
DELETE FROM merchant_application WHERE Username='${U}';
DELETE FROM goods WHERE MerchantId='${M}';
DELETE FROM \`login\` WHERE UserName='${U}';
DELETE FROM \`user\` WHERE username='${U}';`

const deadlockCount = () =>
  Number(one(`SELECT COALESCE(SUM(SUM_ERROR_RAISED),0) FROM performance_schema.events_errors_summary_global_by_error WHERE ERROR_NUMBER=1213;`))
const waitTimeoutCount = () =>
  Number(one(`SELECT COALESCE(SUM(SUM_ERROR_RAISED),0) FROM performance_schema.events_errors_summary_global_by_error WHERE ERROR_NUMBER=1205;`))
const innoStatus = () => sql('SHOW ENGINE INNODB STATUS\\G')

const post = (path, body) =>
  fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  }).then(async (r) => ({ status: r.status, text: await r.text() }))
    .catch((e) => ({ status: 'ERR', text: String(e.message || e) }))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ── 开跑 ──────────────────────────────────────────────────────────────
if (process.argv.includes('--cleanup')) {
  sql(CLEANUP_SQL); console.log('夹具已清理'); process.exit(0)
}

console.log('===== 0 · 前置 =====')
const hb = await fetch(BASE + '/actuator/health').then((r) => r.status).catch(() => 'ERR')
console.log(`后端 ${BASE}/actuator/health → ${hb}`)
if (hb !== 200) { console.error('后端不在跑或不健康（dev profile 起一台再来）'); process.exit(2) }

console.log('===== 1 · 夹具 =====')
sql(FIXTURE_SQL())
const APPID = Number(one(`SELECT Id FROM merchant_application WHERE Username='${U}' ORDER BY Id DESC LIMIT 1;`))
const goodRows = one(`SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='${DBNAME}' AND TABLE_NAME='goods';`)
console.log(`账号=${U} 商家号=${M} 申请Id=${APPID}  goods 行数≈${goodRows}（全表扫描的代价就在这个数上）`)

console.log('===== 2 · 基线 =====')
const D0 = deadlockCount(), W0 = waitTimeoutCount()
const sec0 = (innoStatus().split('LATEST DETECTED DEADLOCK')[1] || '').split('\n').slice(0, 4).join(' | ')
console.log(`ER_LOCK_DEADLOCK=${D0}  ER_LOCK_WAIT_TIMEOUT=${W0}`)
console.log(`INNODB STATUS 现有死锁段（历史/对照）: ${sec0 ? sec0.slice(0, 160) : '<无>'}`)

console.log(`===== 3 · 开压：${ROUNDS} 轮 × (1×A + ${KB}×B)，错开量扫 0..7.7ms（12 档 × 0.7ms） =====`)
let firstDeadRound = null
const perRound = []
for (let i = 1; i <= ROUNDS; i++) {
  sql(RESET_SQL)
  const stagger = Math.round(((i - 1) % 12) * 0.7)   // 12 档 × 0.7ms：A 先发这么多久
  const jobs = []
  for (let k = 0; k < KB; k++) {
    jobs.push((async () => { await sleep(stagger); return { tag: 'B', ...(await post('/admin/accounts/downgrade/relist', `username=${U}`)) } })())
  }
  jobs.push((async () => { await sleep(stagger + 2.2); return { tag: 'A', ...(await post('/admin/accounts/application/audit', `id=${APPID}&approve=true`)) } })())
  const res = await Promise.all(jobs)
  const A = res.find((r) => r.tag === 'A')
  const Bs = res.filter((r) => r.tag === 'B')
  const dNow = deadlockCount() - D0
  let aOut = '(非JSON)'
  try { aOut = JSON.parse(A.text).outcome || JSON.parse(A.text).msg || JSON.stringify(JSON.parse(A.text)) } catch { }
  perRound.push({ i, stagger, aStatus: A.status, aOut, bBad: Bs.filter((b) => b.status !== 200).map((b) => b.status) })
  if (dNow > 0 && firstDeadRound === null) { firstDeadRound = i; console.log(`  ★ 轮 ${i}（错开 ${stagger}ms）出现死锁，累计 1213 = ${dNow}`) }
  console.log(`  轮 ${String(i).padStart(2)}/${ROUNDS} 错开=${String(stagger).padStart(2)}ms  A=${A.status} ${aOut}  B=[${Bs.map((b) => b.status).join(',')}]  1213增量=${dNow}`)
  if (dNow > 0 && perRound.filter((r) => r.bBad.length || r.aStatus !== 200).length > 0) { /* 继续跑，收全分布 */ }
}

console.log('===== 4 · 结果 =====')
const D1 = deadlockCount(), W1 = waitTimeoutCount()
const http5 = perRound.reduce((n, r) => n + (r.aStatus >= 500 ? 1 : 0) + r.bBad.filter((s) => s >= 500).length, 0)
const restored = perRound.filter((r) => r.aOut === 'approved-restored').length
const plain = perRound.filter((r) => r.aOut === 'approved').length
console.log(`ER_LOCK_DEADLOCK: ${D0} → ${D1}   （增量 ${D1 - D0}）`)
console.log(`ER_LOCK_WAIT_TIMEOUT: ${W0} → ${W1}   （增量 ${W1 - W0}）`)
console.log(`HTTP 5xx 次数=${http5}`)
console.log(`A 走完整链（outcome=approved-restored，即真碰到 goods）的次数=${restored}；只到 approved（未碰 goods）=${plain}`)
console.log(`首次死锁出现在第 ${firstDeadRound === null ? '—（一轮都没死）' : firstDeadRound} 轮`)

console.log('===== 5 · INNODB STATUS 的死锁段（含锁序）=====')
const st = innoStatus()
const idx = st.indexOf('LATEST DETECTED DEADLOCK')
console.log(idx < 0 ? '（没有 LATEST DETECTED DEADLOCK 段）' : st.slice(idx, idx + 4000))

console.log('===== 6 · 收尾 =====')
sql(CLEANUP_SQL)
console.log('夹具已删（user / login / merchant_application / goods 各删探针行）；INNODB STATUS 是服务器内存里的历史，不会被本探针清掉。')
process.exit(D1 - D0 > 0 || http5 > 0 ? 0 : 1)
