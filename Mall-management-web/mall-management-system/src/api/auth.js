// src/api/auth.js —— 认证状态模块（全仓**唯一**允许触碰登录态 sessionStorage 的地方）
//
// 收敛范围（实测 2026-10-04，基线 `9add994`）：14 个 view 共 8 键 / **71 处（逐出现）**，其中
//   登录态 4 键 / **54 处（逐出现）** ← 本模块收
//     merchantid 24 / customerid 12 / loginUsername 12 / loginPassword 6
//   ⚠️ 同组另有「行×键」口径 = **52**（merchantid 23 / loginUsername 12 / customerid 11 /
//     loginPassword 6），差在 HomePage:116 同行两**键**、ProductPage:415/416 同行两**次出现**。
//     **本文件一律用逐出现的 54**（与 §6-B 的「71 = 54 + 17」同口径）；别把 52 与 17 相加。
//   ⚠️ 另 4 键 / **17 处是页面状态、不属认证、本笔一字未动**，仍留在各 view：
//     SEARCH_KEYWORD 6 / SEARCH_RESULTS 5 / PENDING_SEARCH_KEYWORD 5 / FROM_PAGE 1
//   —— 这条线要守住，否则本模块会变成杂物箱（见方案 §4）。
//
// ⚠️ 本模块当前**仍按原样读写 sessionStorage，行为与改前完全一致**。
//    P2.6（安全债）已**拆出单列一笔**，不在本笔。

const K = {
  username: 'loginUsername',
  password: 'loginPassword',
  merchantId: 'merchantid',
  customerId: 'customerid',
}

function read(k) { return sessionStorage.getItem(k) }

/** 把字面量 'null' / 'undefined' 也当「无值」—— 原各 view 的降级链就是这么判的
 *  （`sessionStorage.setItem(k, null)` 会存成字符串 "null"）。 */
function usable(v) { return v && v !== 'null' && v !== 'undefined' ? v : null }

// ── 读 ────────────────────────────────────────────────────────────────────────

export function getUsername() { return read(K.username) }

/** ⚠️ P2.6 待处理点：这里是「密码明文在浏览器存储」的**唯一读口**。
 *  拆出的那一笔要把 K.password 换成模块级内存变量 —— 两个读点（GoodsUp / UserInfoPage）
 *  会因此变成「刷新后需重输」，这是**已知且已接受**的代价（路线 3）。
 *  ⚠️ 不能只删写口：`UserService.java:185` 用 `selectRoleForProfile(username, password)`
 *     做**真凭据校验**（role == null 即 return null）⇒ 前端无法在不知道密码的前提下保持行为。 */
export function getPassword() { return read(K.password) }

export function getMerchantId() { return read(K.merchantId) }

export function getCustomerId() { return read(K.customerId) }

/** 是否已登录 —— 原**唯一**定义在 `HomePage.vue:116`（`!!loginUsername || !!merchantid`）。
 *  收敛到此处后，鉴权笔有了落点（改这里的判据即可）。 */
export function isLoggedIn() {
  return !!(read(K.username) || read(K.merchantId))
}

/**
 * 买家 id —— 收敛**逐字相同**的 2 处降级链：
 *   `ShoppingCart.vue:88-93` / `HistoricalOrders.vue:36-41`（customerid → merchantid → loginUsername）
 *
 * ⚠️ **不要**拿它替换另外 2 处 —— 它们形似而实不同，换掉就是改行为：
 *   `ProductPage.vue:145-147` 只有 2 级（无 loginUsername 兜底）；
 *   `OrderCenter.vue:95-97` 是**另一条链**（merchantid → loginUsername，不含 customerid）。
 *   这两处**本笔未动**，登记为「角色/身份判定的口径统一」另议。
 */
export function resolveBuyerId() {
  return usable(read(K.customerId)) || usable(read(K.merchantId)) || usable(read(K.username)) || null
}

// ── 写 ────────────────────────────────────────────────────────────────────────

export function setMerchantId(v) { sessionStorage.setItem(K.merchantId, v) }

export function setCustomerId(v) { sessionStorage.setItem(K.customerId, v) }

/** 登录成功后写入凭据（原 `LoginPage.vue:163-169`，role 为 "1"/"2" 时各写一次）。 */
export function setCredentials(username, password) {
  sessionStorage.setItem(K.username, username)
  sessionStorage.setItem(K.password, password)
}

/** 退出登录：清凭据。原本散在 `CustomerPage.vue:113-114` / `MerchantPage.vue:132-133`。 */
export function clearCredentials() {
  sessionStorage.removeItem(K.username)
  sessionStorage.removeItem(K.password)
}

/** 退出登录：额外清 merchantid（**仅** `MerchantPage.vue:134` 有这一条）。
 *  ⚠️ 单独一个函数而非并进 `clearCredentials()` —— CustomerPage 的退出**不清** merchantid，
 *     合并就是改行为。 */
export function clearMerchantId() {
  sessionStorage.removeItem(K.merchantId)
}

/** 清**全部**本地登录态（凭据 + 两个业务号）—— 供 **401 出口**用（`api/http.js`）。
 *
 *  ⚠️ **与「点退出」的现行清法故意不同**：`CustomerPage.vue:112` 只 `clearCredentials()`、
 *     `MerchantPage.vue:131-132` 另加 `clearMerchantId()`，**两者都不清 `customerid`**
 *     （本模块此前**根本没有**清它的出口）。本函数的语义是「**会话没了**」⇒ 本地身份**应当全清**，
 *     故比「点退出」多清 `merchantid` 与 `customerid`。这是**有意**的，不是漏改。
 *  ⚠️ 调用方**只有** `api/http.js` 的 401 出口**一处** —— 别顺手把 `CustomerPage` / `MerchantPage`
 *     的 `toLogin()` 换成本函数：那是**改退出行为**，不属于本笔。
 *  ⚠️ 这里对 `customerid` 直接 `removeItem` 而**不先补一个 `clearCustomerId()`**：本模块刻意按
 *     「哪个页面清哪个键」组织（见 `clearMerchantId` 的注释），补对称出口是另一笔的事。 */
export function clear() {
  clearCredentials()
  clearMerchantId()
  sessionStorage.removeItem(K.customerId)
}

// ── ⚠️ 以下两处待鉴权笔填充（方案 §4 ③）：本笔为空实现 ──────────────────────────
// BD-03 选 A（拦截器 + Redis Session）⇒ 这两个保持空（凭据走 Cookie，无需前端持有）。
// BD-03 选 B/C（JWT）⇒ 在此实现 token 读写 ——「token 存哪」即 P2.6 的同一道题
//   （localStorage 会被 XSS 读；httpOnly Cookie 则又回到 A 的形状）。
export function getToken() { return null }
export function setToken(/* token */) {}
