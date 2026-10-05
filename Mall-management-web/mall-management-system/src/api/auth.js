// src/api/auth.js —— 认证状态模块（全仓**唯一**允许触碰登录态 sessionStorage 的地方）
//
// 收敛范围（**历史口径**，实测 2026-10-04，基线 `9add994`）：14 个 view 共 8 键 / **71 处（逐出现）**，其中
//   登录态 5 键 / **54 处（逐出现）** ← 本模块收（⚠️ 第 5 键 `role` 为**止血笔新增**，基线 `9add994` 时**不入存储** ⇒ 不在 54 内）
//     merchantid 24 / customerid 12 / loginUsername 12 / 登录密码键 6 **+ 新增 `role`**
//   ⚠️ 同组另有「行×键」口径 = **52**（merchantid 23 / loginUsername 12 / customerid 11 /
//     登录密码键 6），差在 HomePage:116 同行两**键**、ProductPage:415/416 同行两**次出现**。
//     **本文件一律用逐出现的 54**（与 §6-B 的「71 = 54 + 17」同口径）；别把 52 与 17 相加。⚠️ **P2.6（2026-10-05）起登录密码键归零**（该键的字面量在整个 `src/` 已 0 命中 —— 复现命令见 `progress.md §5-AC`；本文件不复述它，免得复检 grep 命中注释）⇒ 54 / 6 是**基线历史值**，今日现状 = 登录态 **4 键**（明文密码整键退场）。
//   ⚠️ 另 4 键 / **17 处是页面状态、不属认证、本笔一字未动**，仍留在各 view：
//     SEARCH_KEYWORD 6 / SEARCH_RESULTS 5 / PENDING_SEARCH_KEYWORD 5 / FROM_PAGE 1
//   —— 这条线要守住，否则本模块会变成杂物箱（见方案 §4）。
//
// ⚠️ 本模块当前**仍按原样读写 sessionStorage，行为与改前完全一致**。
//    P2.6（安全债）**已落地（2026-10-05）** —— 密码半边退场、`/user/me` 成为前端唯一资料读口。

const K = {
  username: 'loginUsername',
  // `K.password` 的那个存储键名已随 P2.6（2026-10-05）删除：明文密码不再进任何存储（墓碑见下方 getPassword 原位置）
  merchantId: 'merchantid',
  customerId: 'customerid',
  role: 'role',   // ⚠️ 止血笔新增：`loginSelect` 的 role（"1" 客户 / "2" 商家）。基线时该值只活在 `LoginPage` 的分支里、不入存储
}

function read(k) { return sessionStorage.getItem(k) }

/** 把字面量 'null' / 'undefined' 也当「无值」—— 原各 view 的降级链就是这么判的
 *  （`sessionStorage.setItem(k, null)` 会存成字符串 "null"）。 */
function usable(v) { return v && v !== 'null' && v !== 'undefined' ? v : null }

// ── 会话恢复态（会话恢复笔，2026-10-05）──────────────────────────────────────────
// 来源：`POST /user/me`（读 **Cookie 会话**）。取数在 `main.js`（启动时一次），
// 本模块只提供**张贴**口 `restoreFromSession` —— 本文件**零 import** 是成文前提
// （见本文件头部与 `http.js` 顶部注释：`http.js → auth.js` 这条边已存在，反向加边就是真环）。
//
// ⚠️ **只存内存、不落 sessionStorage**：BD-03 选 A（凭据走 Cookie、前端不持有身份），
//    把恢复来的身份写回存储会把「身份来自前端存储」这条老路重新焊死（与 P2.6 同一条线）。
//
// 读口顺序：**恢复值优先、sessionStorage 兜底**（`restored === null` 时每个读口都与
//    本笔之前**逐字等价** —— 这是本笔「零行为改动」的判据）。
//
// ⚠️ **维护纪律（7 个写口一律丢弃本对象，别改成「逐字段同步」）**：
//    `setUsername` / `setMerchantId` / `setCustomerId` / `setRole` / `clearCredentials` /
//    `clearMerchantId` / `clear`。理由：本模块的写口**只有两类调用方** —— 登录
//    （`LoginPage.vue`）与退出（`UserCenter.vue` / `http.js` 的 401 出口；⚠️ P2.6 订正：原写 `CustomerPage` / `MerchantPage`，R1/R2 后两者的退出点都迁进了 `UserCenter.vue` —— 见本文件 `clearCredentials` 的按语），
//    两者都是**身份变更**。丢弃 ⇒ 「任何一次写之后，读口 ≡ 笔前行为」这条不变量成立；
//    「逐字段同步」反而会留下过期字段：新标签页恢复成商家 Y 之后再登录成客户 B，
//    若 B 的 `merchantid` 为空（`LoginPage.vue:148` 的 `if` 跳过了写），`restored.merchantId`
//    会**留着 Y**，而 `sessionStorage` 里是空的 ⇒ 「优先」就把 Y 读出来了。
let restored = null   // { username, merchantId, customerId }（各字段可为 null）| null

function readRestored(field) { return restored ? restored[field] : null }

/** 读口统一走这里：恢复值优先、存储兜底。 */
function pick(field, k) { return readRestored(field) || read(k) }

/** 写口统一走这里：本地一写，恢复态整体作废（理由见上方纪律）。 */
function dropRestored() { restored = null }

/**
 * 把 `/user/me` 的响应贴成恢复态。**不取数、不发请求**（取数在 `main.js`）。
 *
 * ⚠️ 字段判据与 `LoginPage.vue:148-153` **逐字对齐**（`if (x)` 只写真值，不加额外过滤）——
 *    加过滤就是改行为。
 * ⚠️ **P2.6（2026-10-05）后本段只剩历史意义**：`/user/me` 现在答的是 **`user` 表整行 + 头像
 *    `image`**（不再只是那 5 列），且 `GoodsUp.vue` / `UserInfoPage.vue` 已改走 `/user/me`、
 *    **不再需要密码** ⇒ 原先「密码恢复不了 ⇒ 这两页在新标签页不可用」的缺口**已消失**。
 *    本函数仍只取 username / merchantId / customerId 三个身份字段（**行为零变更**）。
 * ⚠️ 三个字段全空 ⇒ **不置** `restored`（保持 `null`），读口继续走存储兜底。
 * @param {object|null} info `/user/me` 的 JSON body（`{success, customerid, merchantid, username, ...}`）
 * @returns {boolean} 是否贴上了**至少一个**身份字段
 */
export function restoreFromSession(info) {
  if (!info) return false
  const next = { username: null, merchantId: null, customerId: null }
  if (info.username) next.username = info.username
  if (info.merchantid) next.merchantId = info.merchantid
  if (info.customerid) next.customerId = info.customerid
  if (!next.username && !next.merchantId && !next.customerId) return false
  restored = next
  return true
}

// ── 读 ────────────────────────────────────────────────────────────────────────

export function getUsername() { return pick('username', K.username) }

/** ⚠️ **P2.6（2026-10-05）已删除**：`getPassword()` 与 `K.password` 都不在了 —— 明文密码不再进任何存储。
 *  为什么能删：两个读点（`GoodsUp.vue` / `UserInfoPage.vue`）P2.6 起改走**会话驱动**的
 *  `/user/me`，那里不做凭据校验 ⇒ 密码半边**整体退场**（读口、写口、存储键三处一起）。
 *  ⚠️ 删的只是**前端**：`/user/getUserInfo` 与服务层的 `selectRoleForProfile(username, password)`
 *     仍在（端点保留的理由见 `UserService.getUserInfo` 的 javadoc）—— 它今日**0 个前端调用方**。
 *  ⚠️ 别把它加回来：会话恢复来的身份本来就不含密码（见 `restoreFromSession`），
 *     任何「前端再持密码」的方案都会把凭据驱动那条老路焊回去。 */

export function getMerchantId() { return pick('merchantId', K.merchantId) }

export function getCustomerId() { return pick('customerId', K.customerId) }

/** 角色 —— `loginSelect` 的 `role`（"1" 客户 / "2" 商家）。**止血笔新增**。
 *  ⚠️ **不接恢复态**（P2.6 起 `/user/me` 答 `user` 表整行 —— 含 `usertype` 中文串「客户/商家」，但 `role` 在 `login` 表 ⇒ **不含** `role`）
 *     ⇒ 新标签页恢复时本口返回 **`null`**，调用方（`router/index.js` 的首页守卫）**必须自己兜底**
 *     （现兜底 = `getMerchantId()`，与 `ProductPage.vue:381` 同口径）。**本笔刻意不从 `usertype` 反推**
 *     —— 那会让 `role` 有第二个来源（口径分叉）。C3 加管理员时若要从恢复路径拿到 role，
 *     须把 `role` 加进 `/user/me` 投影（登记于 §5-V）。
 *  ⚠️ `pick` 仍照用（恢复值优先、存储兜底）⇒ 将来若 `/user/me` 真返回了 `role`，本口**一行不用改**。 */
export function getRole() { return pick('role', K.role) }

/** 是否已登录 —— 原**唯一**定义在 `HomePage.vue:116`（`!!loginUsername || !!merchantid`）。
 *  收敛到此处后，鉴权笔有了落点（改这里的判据即可）。
 *  ⚠️ 判据**一行未改**（仍是 `!!(username || merchantid)`），只是改从两个读口取
 *     ⇒ 恢复了身份时自然为真，未恢复时与改前逐字等价（`pick` 的兜底就是原来的 `read`）。 */
export function isLoggedIn() {
  return !!(getUsername() || getMerchantId())
}

/**
 * 买家 id —— 收敛**逐字相同**的 2 处降级链（⚠️ **P2.6 结清悬空**：`#48` 之后那两处只剩 `getBuyerId = () => resolveBuyerId()`，链的**本体**在此）：
 *   `ShoppingCart.vue:88-93` / `HistoricalOrders.vue:36-41`（customerid → merchantid → loginUsername）—— 均为**历史地址**，悬空成因见 `progress.md §5-AC`
 *
 * ⚠️ **不要**拿它替换另外 2 处 —— 它们形似而实不同，换掉就是改行为：
 *   `ProductPage.vue:145-147` 只有 2 级（无 loginUsername 兜底）；
 *   `OrderCenter.vue:95-97` 是**另一条链**（merchantid → loginUsername，不含 customerid）。
 *   这两处**本笔未动**，登记为「角色/身份判定的口径统一」另议。
 */
export function resolveBuyerId() {
  return usable(getCustomerId()) || usable(getMerchantId()) || usable(getUsername()) || null
}

// ── 写 ────────────────────────────────────────────────────────────────────────

export function setMerchantId(v) { sessionStorage.setItem(K.merchantId, v); dropRestored() }

export function setCustomerId(v) { sessionStorage.setItem(K.customerId, v); dropRestored() }

/** 写入角色（`LoginPage.vue` 登录成功后，与 `setUsername` **同行**各写一次）。**止血笔新增**。
 *  ⚠️ 与其余写口一样 `dropRestored()` —— 登录 = 身份变更，恢复态整体作废。 */
export function setRole(v) { sessionStorage.setItem(K.role, v); dropRestored() }

/** 登录成功后写入用户名（原 `LoginPage.vue:163-169`）—— **P2.6 起由 `setCredentials` 改名、只写用户名**，密码写口随明文退场一起删（墓碑见上方 `getPassword` 原位置）。 */
export function setUsername(username) {
  sessionStorage.setItem(K.username, username)
  // sessionStorage.setItem(K.password, password) —— P2.6 删除（明文密码不再落存储）
  dropRestored()
}

/** 退出登录：清凭据。原本散在 `CustomerPage.vue:113-114`（R1 后迁到 `UserCenter.vue`）/ `MerchantPage.vue:132-133`（**R2 后也迁到 `UserCenter.vue`**）。
 *  ⚠️ **止血笔行为变更**：本函数**多清一个键** `role`（原只清 username / password）。**P2.6（2026-10-05）**：`password` 已退场 ⇒ 今日只清 username / role。
 *     理由：退出登录语义上就该清角色，留着会让下一次「已登录」判断带上上一轮的角色。
 *     影响面 = **一个退出点（同一函数、两个分支）**：`UserCenter.vue:52-55` 的 `toLogin()`（登记于 §5-V / §5-Y）。
 *     ⚠️ **按语（§5-Y，2026-10-05）**：退出点的**形状**变过两次，别拿旧口径读 ——
 *        ① R1 前：两个壳**各自内联**（客户 `CustomerPage.vue:111-114`、商家 `MerchantPage.vue:130-134`）；
 *        ② R1 后：客户侧随 markup 迁到 `UserCenter.vue:37`（**换文件**），商家侧未动；
 *        ③ **R2 后**：商家壳也路由化 ⇒ 两壳共用**同一个** `UserCenter.vue`，退出 = **同一函数的两分支**
 *           （`UserCenter.vue:52-55`，`meta.logout === 'merchant'` ⇒ 多清 merchantid）——
 *           这是**语义**变更（从「分文件」到「同文件两分支」），不只是行号重指（用户裁决 3 已认可）。
 *        本文件上方那句「原本散在 `CustomerPage.vue:113-114`」是**历史行号** —— `HEAD` 实测该块是 `:111-114`、
 *        `clearCredentials()` 在 `:112`（两值不一致，**成因未考**）。 */
export function clearCredentials() {
  sessionStorage.removeItem(K.username)
  // sessionStorage.removeItem(K.password) —— P2.6 删除（该存储键已不存在；留注释是为了让「净零行数」可核）
  sessionStorage.removeItem(K.role)   // ⚠️ 止血笔新增（见上）
  dropRestored()
}

/** 退出登录：额外清 merchantid（**仅商家侧那一支**：`UserCenter.vue:54`，`meta.logout === 'merchant'`；
 *  R2 前在 `MerchantPage.vue:134`，R2 后商家退出也走共享的 `UserCenter.vue`）。
 *  ⚠️ 单独一个函数而非并进 `clearCredentials()` —— 客户侧（`meta.logout` 缺省那支）的退出**不清** merchantid，
 *     合并就是改行为。 */
export function clearMerchantId() {
  sessionStorage.removeItem(K.merchantId)
  dropRestored()
}

/** 清**全部**本地登录态（凭据 + 两个业务号）—— 供 **401 出口**用（`api/http.js`）。
 *
 *  ⚠️ **与「点退出」的现行清法故意不同**：`UserCenter.vue:52-55`（**R2 起两壳共用同一个 `toLogin`**；
 *     客户侧只 `clearCredentials()`、商家侧另加 `clearMerchantId()`）**两支都不清 `customerid`**
 *     （本模块此前**根本没有**清它的出口）。本函数的语义是「**会话没了**」⇒ 本地身份**应当全清**，
 *     故比「点退出」多清 `merchantid` 与 `customerid`。这是**有意**的，不是漏改。
 *  ⚠️ 调用方**只有** `api/http.js` 的 401 出口**一处** —— 别顺手把 `UserCenter` 的
 *     `toLogin()` 换成本函数：那是**改退出行为**，不属于本笔。（R2 前这条还点了 `MerchantPage`，
 *     它现在没有自己的退出点了 —— 商家退出也走 `UserCenter.vue`。）
 *  ⚠️ 这里对 `customerid` 直接 `removeItem` 而**不先补一个 `clearCustomerId()`**：本模块刻意按
 *     「哪个页面清哪个键」组织（见 `clearMerchantId` 的注释），补对称出口是另一笔的事。
 *  ⚠️ **会话恢复笔**：本函数是「会话没了」⇒ 恢复态也必须一起丢（`clearCredentials` /
 *     `clearMerchantId` 各自已丢一次，这里再丢一次是幂等兜底）—— 不丢就会在 401 出口
 *     清空存储后，读口仍从 `restored` 读出身份 ⇒「已登录」判断残留、跳登录后又弹回来。
 *  ⚠️ **止血笔**：`role` 由第一行的 `clearCredentials()` 一并清掉 ⇒ 这里**不重复** removeItem(K.role)
 *     （重复也是幂等的，但没必要）。 */
export function clear() {
  clearCredentials()
  clearMerchantId()
  sessionStorage.removeItem(K.customerId)
  dropRestored()
}

// ── ⚠️ 以下两处待鉴权笔填充（方案 §4 ③）：本笔为空实现 ──────────────────────────
// BD-03 选 A（拦截器 + Redis Session）⇒ 这两个保持空（凭据走 Cookie，无需前端持有）。
// BD-03 选 B/C（JWT）⇒ 在此实现 token 读写 ——「token 存哪」即 P2.6 的同一道题
//   （localStorage 会被 XSS 读；httpOnly Cookie 则又回到 A 的形状）。
export function getToken() { return null }
export function setToken(/* token */) {}
