// src/api/accounts.js —— 管理端「用户与商家治理」API 封装（C3 块 2〈用户块〉，2026-10-09）。
//
// 与 `src/api/admin.js` 的分工 —— 这两份**有意不合并**：
//   · `admin.js`    = 批 A 的审计 3 条 + 块 1·I2 的发送侧 4 条（`/admin/audit/*`、`/admin/notify/*`）；
//   · 本文件        = 块 2 的治理 8 条 + 2026-10-18 的明文详情 1 条（`/admin/accounts/*`）—— 同前缀、同「无角色闸」姿态，
//                     但**归属一块**：它们随「用户块」一起落地、一起被引，跟另两块不该混在一处。
//   ⚠️ 后端也是**两个 Controller**（`AdminAuditController` / `AdminNotificationController` ⇄
//      `AdminAccountsController`）⇒ 前端跟着分文件，免得日后有人以为「管理端接口都在一个模块里」
//      而把权限口径顺带合并。
//
// ⚠️ **`q()` / `post()` 与 `admin.js` / `notify.js` 里那两个是**逐字相同的本地副本**（刻意的重复）：
//   抽成共享模块要新开一个文件、并回头看那两份刚落的 —— 那笔改动越出本块范围，而这里只有
//   8 个函数、两处 helper。**第三处已经出现了**（`notify.js:9-11` 当年写的是「等第三处出现再抽」），
//   故把那条口径**如实记在此处**：本块**仍不抽**（抽 = 动两个既有文件的行号锚点，那两处正被
//   `docs/agent/slot-registry.md` 按行号引），留给专门的收敛笔；本文件是**第四份**副本。
//   ⚠️ 三条附带的口径**同样适用**（不是抄漏）：
//     ① 参数一律 `URLSearchParams` 字符串、**别改成 JSON** —— 后端收 `@RequestParam`，
//        `application/json` 的 body 它不绑定，而请求照样 200 ⇒ 表现为「静默无效」；
//     ② 空值一律不发 —— 后端的缺参判据是 `isBlank`，发空串 = 「给了个空值」
//        （`/admin/accounts/users` 的 `keyword` 尤其：发空串 = 「有筛，筛空串」⇒ 一条都查不到）；
//     ③ 一律**拆掉 `Response` 外壳**（`http.post` 返回裸 `Response`），直接答 JSON body。
import { http } from './http'
import { endpoints } from './endpoints'

/**
 * 组装表单 body：只收**有值**的键（`null` / `undefined` / `''` 一律不发）。见文件头 ②。
 *
 * ⚠️ 它**不丢 `false`** —— 与 `notify.js` 那份同一个语义。本模块里唯一的布尔入参是
 *    `auditApplication` 的 `approve`，而它用**两项显式传参**（见下），故这个边界不影响本文件。
 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/** 发一发 POST 并**拆掉 `Response` 外壳**，直接答 JSON body。见文件头 ③。 */
async function post(path, body) {
  const res = await http.post(path, body)
  return res.json()
}

// ══════════════════════════════════════════════════════════════════════════════
// ① 用户治理（`UserGovernanceService`）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 用户列表（封禁的排前面，同序再按 `username` 升序 —— 后端 `UserGovernanceDao.selectUsers`）。
 *
 * @param {{keyword?:string, limit?:number, offset?:number}} params
 *        `keyword` 命中 `username` / `customerid` / `merchantid` 三列的**包含**匹配（模糊）；
 *        `limit` 缺省 100、上限 100（后端夹取，回显在 `limit` 里）；`offset` 负数归 0。
 *        ⚠️ 块 7 收尾 · P1 起管理页默认 `limit=20` + `offset` 翻页（后端真 `LIMIT ? OFFSET ?`）。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number, offset:number}>}
 *          ⚠️ `total` 是**同一筛选条件下**的总数（与 `list` 同源），不是全表数。
 *          ⚠️ 行里**包含管理员自己**（`selectUsers` 不筛 `usertype`）—— 但封禁对管理员**无效**
 *          （`AuthInterceptor.isBanned` 对 `role=3` 豁免），见 `AuthInterceptor` 第 ⑤ 档。
 */
export function accountsUsers(params) {
  return post(endpoints.accounts.users, q(params))
}

/**
 * 封禁一个账号（**只标 `user` 行**，不动 `login`）。
 *
 * @param {{username:string, reason:string}} params
 *        ⚠️ `reason` **必填** —— 后端在缺失时答 `{success:false,msg:'封禁必须填写理由（该理由会展示给用户）'}`
 *        （理由是**给被封的人看**的，不是内部备注）。
 * @returns {Promise<{success:boolean, username?:string, msg?:string}>}
 *          ⚠️ 库里的落点是 `banned=1` + `banReason` + `bannedAt`；**不是**删账号、也不改 `login`。
 *          ⚠️ 生效面是**下一次请求**（登录闸第 ⑤ 档 + `loginSelect` 答 `4`），**不是**这一发返回里。
 */
export function accountsBan(params) {
  return post(endpoints.accounts.ban, q(params))
}

/**
 * 解封（幂等 —— 解一个本来就没封的账号照样 `success:true`）。
 *
 * @param {{username:string}} params
 * @returns {Promise<{success:boolean, username?:string, msg?:string}>}
 */
export function accountsUnban(params) {
  return post(endpoints.accounts.unban, q(params))
}

// ══════════════════════════════════════════════════════════════════════════════
// ② 商家入驻审核（`MerchantApplicationService`）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 入驻申请列表（`SubmittedAt` 倒序）。
 *
 * @param {{status?:'pending'|'approved'|'rejected', limit?:number}} params
 *        `status` 不传 = 全部（后端 `DEFAULT_LIMIT=100` / `MAX_LIMIT=500`）。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number}>}
 *          一行 = 一条申请：`Id` / `Username` / `ShopName` / `LicenseNo` / `ContactName` /
 *          `ContactPhone` / `ShopAddress` / `LicenseImage` / `Status` / `RejectReason` /
 *          `BatchId` / `ReviewedBy` / `SubmittedAt` / `ReviewedAt`。
 */
export function accountsApplications(params) {
  return post(endpoints.accounts.applications, q(params))
}

/**
 * 审核一条申请（**通过 = 客户升级成商家**）。
 *
 * @param {{id:number|string, approve:boolean, rejectReason?:string}} params
 *        ⚠️ `approve` **必须显式传 `true` / `false`** —— 传 `false` 是「驳回」这个**动作**，
 *        不是「没给」。这也正是本模块把 `approve` 拼成 `'true'` / `'false'` 字面量的理由
 *        （`q()` 不丢 `false`，但**丢空串** ⇒ 用 `approve ? 'true' : 'false'`，永远有值）。
 *        ⚠️ `rejectReason` 在驳回时**必填**（后端答 `'驳回必须填写理由'`）；通过时给了也**不落库**。
 * @returns {Promise<{success:boolean, outcome?:string, username?:string, rejectReason?:string, msg?:string}>}
 *          `outcome` 四个值，**判据看它、不要自己猜**：
 *            · `approved`           —— 通过（该行原本没有商家号，或旧号已过期失效）
 *            · `approved-restored`  —— 通过，且**恢复了** 30 天冻结期内的旧商家号（见下）
 *            · `rejected`           —— 驳回
 *            · `already-handled`    —— **这条已经被审过了**（CAS 未命中）。⚠️ 这**不是失败**
 *                                      —— 但它**不发通知、不写审计**（后端刻意跳过），页面别报「成功」，
 *                                      报「这条已处理过」并刷新列表。
 *          ⚠️ 「通过」的副作用是**一串**（后端同事务）：写 `user.usertype='商家'` + 造/复用 `merchantid`
 *             + `login.Role` 改 2 + 商家头像行；商户**再登录**才进商家壳（本页面不代它登录）。
 */
export function accountsAudit(params) {
  return post(
    endpoints.accounts.audit,
    q({ id: params.id, approve: params.approve ? 'true' : 'false', rejectReason: params.rejectReason }),
  )
}

// ══════════════════════════════════════════════════════════════════════════════
// ③ 商家降级控制（`MerchantDowngradeService`）
//
// ⚠️ 降级是**五阶段**（用户 2026-10-09 裁决 A 档 5）：排定 → 通知期 → 冻结（降级生效）→
//    （可解冻恢复）/ 重新申请。本模块这 3 个函数覆盖**管理端能手动触发的三处**：
//    排定（`schedule`）、撤销排定（`cancel`）、人工恢复（`relist`）。
//    「通知期到点后自动冻结」那一步**不在前端触发** —— 它由后端 `@Scheduled` 扫 `FreezeUntil`/
//    `ScheduledDowngradeAt` 跑（`MerchantDowngradeService.runDue`），前端不轮询它。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 排定降级（**不是立刻降级** —— 通知期 1 天后才冻结）。
 *
 * @param {{username:string, reason:string}} params
 *        ⚠️ `reason` **必填**（后端答 `'降级必须填写原因（该原因会展示给商家）'`）
 *        —— 它写进 `user.DowngradeReason`，**商家看得到**（`/user/me` 会答这 5 列）。
 * @returns {Promise<{success:boolean, username?:string, scheduledAt?:string, state?:string,
 *                    outcome?:string, msg?:string}>}
 *          `scheduledAt` = 生效时刻（后端 `LocalDateTime.toString()`，无时区，库内 UTC）。
 *          ⚠️ 排定**不改 `Role`**：通知期内商家**照常经营**，只在 `/user/me` 上多一个 `scheduled` 态。
 *
 *          【⚠️ 补丁 A3（2026-10-09 手验后）：同一个降级**只能排定一次**】
 *          后端加了 CAS（`UPDATE … WHERE Username=? AND DowngradeState='none'`）。命中 0 行时
 *          **不是「失败」、也不是「排定成功」**，而是**被拦下**，且**一律 `success:false`**：
 *            · `outcome === 'already-scheduled'` —— 已经排过了（同一账号重复点击）。`msg` =
 *              「已发送过降级请求，请于用户治理模块查看」。
 *            · `outcome === 'already-frozen'`    —— 已经**冻结**了 ⇒ 该走「人工恢复」那条路。
 *            · 两者都**没有** `outcome`（只有 `msg:'用户不存在'`） —— 用户名打错了。
 *          ⇒ **前端按 `outcome` 挑文案，不要拿 `msg` 做字符串匹配**（后端成文口径，见
 *             `MerchantDowngradeService.scheduleMiss`）。
 *          ⚠️ `success:false` 这一点是**有意**的、也是**补丁 A4 的根因修法**：Controller 只在
 *             `success:true` 时才发通知 + 写审计 ⇒ 重复点击**结构性地**不可能再刷通知
 *             （手验时一次降级刷了 14 条通知，就是这个洞）。
 */
export function downgradeSchedule(params) {
  return post(endpoints.accounts.downgradeSchedule, q(params))
}

/**
 * 撤销排定（通知期内反悔）。**不改 `Role`、不碰商品**，只把 `DowngradeState` 清回 `none`。
 *
 * @param {{username:string}} params
 * @returns {Promise<{success:boolean, username?:string, outcome?:string, msg?:string}>}
 *          ⚠️ **补丁 A3 附带**：对**已冻结**（`DowngradeState='frozen'`）的账号**拒绝**，
 *             `{success:false, outcome:'already-frozen'}`。理由：在冻结态下把状态清回 `none`
 *             会留下「商品仍下架、`Role` 仍是客户，但没有冻结标记」的**半成品态**，界面再没有入口
 *             能把它捞回来 —— 那条路叫「人工恢复」（`downgradeRelist`），不叫「撤销」。
 */
export function downgradeCancel(params) {
  return post(endpoints.accounts.downgradeCancel, q(params))
}

/**
 * 人工恢复（**冻结之后**那条路；`FreezeUntil` 是否过期由后端判，前端不猜）。
 *
 * @param {{username:string}} params
 * @returns {Promise<{success:boolean, username?:string, relisted?:boolean, msg?:string}>}
 *          · `relisted:true`  —— 真的恢复了（`Role` 改回 2、商品重新上架、清冻结态）
 *          · `relisted:false` —— 幂等命中：它**本来就正常**（没被冻结过 / 已经恢复过）
 *          ⚠️ `{success:false, msg:'该账号没有商家号（从未入驻）'}` 是**另一回事**：
 *             该账号从没通过入驻 ⇒ `merchantid` 为 NULL，没有可恢复的东西。
 *          ⚠️ 恢复**只恢复它自己的商家号**；30 天窗口**过期后**重新申请要人工确认，那也正是本函数
 *             （后端 `restoreIfFrozenWithinWindow` 判窗口，本函数是窗口外那条人工路）。
 */
export function downgradeRelist(params) {
  return post(endpoints.accounts.downgradeRelist, q(params))
}

// ══════════════════════════════════════════════════════════════════════════════
// ④ 用户明文详情（2026-10-18 · D3 手验裁决）
//
// ⚠️ 用户治理**列表**仍返回掩码 email（`a***@x.com`）；本条是管理员显式点「查看明文」时
//    才走的一次单行读取。后端每次成功调用都会写一条 `user.viewplaintext` 审计
//    （`AdminAccountsController#userDetail`）—— 前端不需要、也不应该自己再记一份。
// ⚠️ 传**空 username** 会得到 `{success:false,msg:'用户不存在'}`（不回退成「列出全部」）；
//    调用点必须从当前行取 username，别让空串走到这里。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 单个用户的**明文**整行（含 `email`）。
 *
 * @param {{username:string}} params
 * @returns {Promise<{success:boolean, user?:object, msg?:string}>}
 *          `user` 的列与列表行同源（`UserGovernanceDao.USER_COLUMNS`），只是 email 未脱敏。
 */
export function accountsUserDetail(params) {
  return post(endpoints.accountsDetail.userDetail, q(params))
}

/**
 * 设置会员等级（批四第二批 commit 3）。
 *
 * @param {{username:string, level:number|string}} params
 *        `level` 只接受 `0`（普通）/ `1`（VIP）—— 其余值后端**拒绝**而不是静默夹断
 *        （写错档位的人该看到拒绝）。等级变了**不失效**任何缓存：会员读取走
 *        `MemberLevel` 直查（`UserStateLookup`），不在 `userPublic` 的 5 列里。
 * @returns {Promise<{success:boolean, msg?:string, username?:string, memberLevel?:number}>}
 *          `affected==0` ⇒ 「用户不存在」。
 */
export function accountsSetMember(params) {
  return post(endpoints.memberB4.adminSet, q(params))
}
