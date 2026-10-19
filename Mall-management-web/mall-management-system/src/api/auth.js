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
// ⚠️ 本模块**原有 4 个身份键**的读写仍与改前逐字等价（`pick` 的兜底就是原来的 `read`）。
//    P2.6（2026-10-05）密码半边退场；多账户隔离笔（2026-10-06）**另加**一个存储键 `authToken`（见文件末尾）。

const K = {
  username: 'loginUsername',
  // `K.password` 的那个存储键名已随 P2.6（2026-10-05）删除：明文密码不再进任何存储（墓碑见下方 getPassword 原位置）
  merchantId: 'merchantid',
  customerId: 'customerid',
  role: 'role', token: 'authToken', memberLevel: 'memberLevel', balance: 'balance', isBrand: 'isBrand',   // ⚠️ 两个键**合并写在同一行**是为了保住本文件的净零行数（`:44`/`:98-103`/`:108`/`:118`/`:146` 全被文档按行号引）。`role`：止血笔新增（`loginSelect` 的 "1" 客户 / "2" 商家；基线时该值只活在 `LoginPage` 的分支里、不入存储）。`authToken`：多账户隔离笔（2026-10-06）新增 —— **同一个键名用在两个 store**（sessionStorage = 本页的票；localStorage = 「最近登录」指针），读写全在文件末尾那一节
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
 * ⚠️ **P2.6（2026-10-05）后本段只剩历史意义**：`/user/me` 现在答 **`user` 表整行 + 头像 `image`**
 *    （不再只是那 5 列），且 `GoodsUp.vue` / `UserInfoPage.vue` 已改走 `/user/me`（**不再需要密码**）⇒ 原缺口**已消失**。
 * ⚠️ 多账户隔离笔（2026-10-06）**多贴一个 `role`**（`/user/me` 自本笔起答它，见那个读口的注释）：其余三字段**零变更**。
 * ⚠️ **四个字段全空** ⇒ **不置** `restored`（保持 `null`），读口继续走存储兜底。
 * @param {object|null} info `/user/me` 的 JSON body（`{success, customerid, merchantid, username, role, ...}`）
 * @returns {boolean} 是否贴上了**至少一个**身份字段
 */
export function restoreFromSession(info) {
  if (!info) return false
  const next = { username: null, merchantId: null, customerId: null, role: null, memberLevel: null, balance: null, isBrand: null }
  if (info.username) next.username = info.username; if (info.merchantid) next.merchantId = info.merchantid; if (info.MemberLevel !== undefined && info.MemberLevel !== null) next.memberLevel = Number(info.MemberLevel) || 0; if (info.Balance !== undefined && info.Balance !== null) next.balance = Number(info.Balance) || 0; if (info.IsBrand !== undefined && info.IsBrand !== null) next.isBrand = info.IsBrand === 1 || info.IsBrand === true || info.IsBrand === '1'   // 批四第二批 commit 3：会员字段从 `/user/me` 的 `user` 整行带出（同行压缩是为保住本函数内既有行号）
  if (info.customerid) next.customerId = info.customerid
  if (info.role) next.role = String(info.role)   // ⚠️ 与上面三个同行压缩是**故意的**：保住 `:108`/`:118`/`:146` 的行号。⚠️ **`String(...)` 是本行唯一的行为改动（2026-10-11，问题①的根因）**：`/user/me` 答的 `role` 是 JSON **数字**（实测 `{"role":3,"success":true}`），而全站**一律按字符串比** —— `router/index.js` 的 `meta.roles`（`['1','3']`）、`ROLE_HOME` 的键、`roleKey()` 的返回、`AdminShell.vue:99` 的角色名表。原样存 ⇒ 恢复路径上**每个角色的比较都恒 false**（`['3'].includes(3)` 是 false）⇒ 守卫 2（`router/index.js:329-334`）把人送到 `ROLE_HOME[数字]` = `undefined` ⇒ `|| '/customer'` ⇒ 在 `/customer` 上再判一次仍 false ⇒ **重定向成环** ⇒ `router.isReady()` reject ⇒ `app.mount('#app')` **永不执行** ⇒ `#app` 空 + `data-theme="admin"` 的深色底 = **整页深色、无内容**（实测：`_work/diag-me.mjs` 第 ④ 段 `textLen=0`；且 Console 只有一条 redirect warning、Network 一片干净 —— 最像「进不去」的那种）。⚠️ 与 `LoginPage` 写入的形态**本来就该一致**：那里是 `res.text()`，从一开始就是字符串
  if (!next.username && !next.merchantId && !next.customerId && !next.role) return false
  restored = next; try { if (next.memberLevel != null) sessionStorage.setItem(K.memberLevel, String(next.memberLevel)); else sessionStorage.removeItem(K.memberLevel); if (next.balance != null) sessionStorage.setItem(K.balance, String(next.balance)); else sessionStorage.removeItem(K.balance); if (next.isBrand != null) sessionStorage.setItem(K.isBrand, next.isBrand ? '1' : '0'); else sessionStorage.removeItem(K.isBrand) } catch (e) { /* 存储不可用 ⇒ 只留内存 */ }   // 批四第二批 commit 3（同行追加）：会员三字段**落 sessionStorage** —— 刷新时 `main.js` 对「本页已有身份」直接返回、不再打 `/user/me`，不落盘就会在刷新后静默退回普通用户价。新增/管理员改档后仍以**下一次恢复**为准（登录 / 新标签页）。
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

/** 角色 —— `loginSelect` 的 `role`（"1" 客户 / "2" 商家 / "3" 管理员）。**止血笔新增**。
 *  ⚠️ 多账户隔离笔（2026-10-06）**订正**：`/user/me` 自本笔起**答 `role`**（它不是 `user` 表的 5 列之一，
 *     而是本次请求的身份 —— 见 `UserController.me`）⇒ 恢复态**拿得到**角色，本口在恢复路径上不再必然为 `null`。
 *     调用方（`router/index.js` 的首页守卫）的 `getMerchantId()` 兜底**仍留着**：匿名、或没走成恢复
 *     （超时 / 后端不可达）时它仍是唯一出路。**本笔仍不从 `usertype` 反推**（那会让 `role` 有第二个来源）。
 *  ⚠️ `pick` 照用（恢复值优先、存储兜底）—— 正因如此，`/user/me` 一答 `role`，本口**一行都不用改**。 */
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
 *   `ProductPage.vue:169-171` 只有 2 级（无 loginUsername 兜底）；
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
  dropRestored(); try { sessionStorage.removeItem(K.memberLevel); sessionStorage.removeItem(K.balance); sessionStorage.removeItem(K.isBrand) } catch (e) { /* 存储不可用 ⇒ 无键可清 */ }   // 批四第二批 commit 3：登出 / 换号时会员展示态一并清，防下一位账号读到上一位的档位
}

/** 退出登录：额外清 merchantid（**仅商家侧那一支**：`UserCenter.vue:54`，`meta.logout === 'merchant'`；
 *  R2 前在 `MerchantPage.vue:134`，R2 后商家退出也走共享的 `UserCenter.vue`）。
 *  ⚠️ 单独一个函数而非并进 `clearCredentials()` —— 客户侧（`meta.logout` 缺省那支）的退出**不清** merchantid，
 *     合并就是改行为。 */
export function clearMerchantId() {
  sessionStorage.removeItem(K.merchantId)
  dropRestored()
}

/** 清 `customerid` —— 与 `clearMerchantId` **对称**的出口（**多账户切换笔**，2026-10-08，用户第 3 条）。
 *  ⚠️ 为什么到今天才补：下面 `clear()` 的注释写着「补对称出口是另一笔的事」 —— **这笔就是它**。
 *     病的形状：商家登录写 `merchantid`、客户登录写 `customerid`，`LoginPage.vue` 旧代码**只写不清**
 *     （`/user/me` 答不出对面的号就整段 `if` 跳过）⇒ 换账户后**上一家的业务号留在存储里**。
 *     而 `resolveBuyerId()` **优先取 `customerid`** ⇒ 商家账号查历史订单会带上上一位**客户**的 id，
 *     票却是商家的 ⇒ `IdentityGuard.requireSelfOrAdmin` 判 403（实测：`docs/美化/dtop4/acct.log`）。
 *  ⚠️ 与 `clearMerchantId` **逐字同形**（removeItem + dropRestored）：两个出口不许有行为差。 */
export function clearCustomerId() {
  sessionStorage.removeItem(K.customerId)
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
 *     「哪个页面清哪个键」组织（见 `clearMerchantId` 的注释），补对称出口是另一笔的事。（⚠️ 2026-10-08：那个出口已补出来 = 下方 `clearCustomerId`（用户第 3 条修）；**本处仍不改**，行为一字未动。）
 *  ⚠️ **会话恢复笔**：本函数是「会话没了」⇒ 恢复态也必须一起丢（`clearCredentials` /
 *     `clearMerchantId` 各自已丢一次，这里再丢一次是幂等兜底）—— 不丢就会在 401 出口
 *     清空存储后，读口仍从 `restored` 读出身份 ⇒「已登录」判断残留、跳登录后又弹回来。
 *  ⚠️ **止血笔**：`role` 由第一行的 `clearCredentials()` 一并清掉 ⇒ 这里**不重复** removeItem(K.role)
 *     （重复也是幂等的，但没必要）。 */
export function clear() {
  clearCredentials()
  clearMerchantId()
  sessionStorage.removeItem(K.customerId)
  clearToken()   // ⚠️ 多账户隔离笔（2026-10-06）：票也是凭据 ⇒ 「会话没了」时一并清（指针由 D6 判）
  dropRestored()
}

// ── token 读写（多账户隔离笔，2026-10-06）────────────────────────────────────────
// 形状 = 「BD-03 的 **A 的显式传输变体**」：票由服务端签发（`POST /user/token`）、前端显式携带
// （`Authorization: Bearer`，加头那一行在 `http.js` 的 `buildInit`）—— 不是 JWT、也不是 httpOnly
// Cookie。⚠️ 别写成「改判 B」：B/C 那种「前端自己造凭据」的路一条都没走（见 §5-AF 的裁决口径）。
//
// 两个存储位 —— **同一个键名 `K.token`、两个 store，别混**：
//   · `sessionStorage[K.token]` —— **本标签页**的票。隔离全靠它：sessionStorage 按标签页存活
//     ⇒ 两个标签页各自登录、各持一张票，互不覆盖（而 cookie 会被后登录那个覆盖 —— 那正是要治的病）；
//   · `localStorage[K.token]`   —— 「**最近登录**」指针（`role ≠ 3` 时才写）。新标签页的
//     sessionStorage 是空的 ⇒ 靠它「认领」（`main.js` 启动时调 `adoptSharedToken`）。
//     ⚠️ 它是**指针、不是凭据来源**：认领之后仍要用 `/user/me` 验一发（票可能已被撤销或过期）。
//
// ⚠️ **为什么不用 httpOnly Cookie 装票**：那样票由浏览器自动携带 ⇒ 又回到「谁有 cookie 谁是身份」，
//    「一标签页一身份」当场落空。代价照实记：**XSS 可读走本存储**（P2.6 的同一条线，登记于 §5-AF）。
// ⚠️ **管理员（role=3）永不发布指针**（用户裁决 D6）：管理员身份只能由 token 承载，留一个共享指针
//    等于把「新标签页自动获得管理员身份」这条路焊回来。

/** 本标签页的票（没有 ⇒ `null`）。
 *  ⚠️ **只读 `sessionStorage`** —— 指针的「认领」是 `main.js` **显式**做的一步，不藏在这里：
 *  读口保持「读到的一定是本页自己的票」这条语义，否则隔离会从读口漏掉（跨标签页串味）。 */
export function getToken() { return usable(read(K.token)) }

/** 写本标签页的票。
 *  ⚠️ **刻意不 `dropRestored()`** —— 它与上面那 7 个身份写口不同：票**不是身份**
 *  （本模块的身份读口只读 `restored` / 身份键，票里虽有 userId/role 但一个读口都不碰它）。
 *  真正可能连坐的调用点（登录 / cookie 恢复）在写票的前后本来就会走身份写口。 */
export function setToken(token) { sessionStorage.setItem(K.token, token) }

/** 「最近登录」指针（`localStorage`；没有 ⇒ `null`）。 */
export function getSharedToken() { return usable(localStorage.getItem(K.token)) }

/** 发布指针 —— **只在 `role ≠ 3` 时调用**（两个调用点：`LoginPage.vue` 登录成功后 /
 *  `main.js` 的 cookie 恢复补票）。 */
export function publishSharedToken(token) { localStorage.setItem(K.token, token) }

/** 清本标签页的票。⚠️ **D6 规则**：只有当「指针 == 本页这张票」时才**一并清指针** ——
 *  `localStorage` 是共享的，A 页登出不该把 B 页刚发布的指针抹掉（那会让 B 页刷新后失去恢复能力）。
 *  ⚠️ 本函数**不**清身份键（那是 `clearCredentials` / `clearMerchantId` 的活）；401 出口的
 *  `clear()` 两者都调 —— 那是「会话没了」的语义，比「点退出」清得多，见 `clear()` 的注释。 */
export function clearToken() {
  const mine = getToken()
  sessionStorage.removeItem(K.token)
  if (mine && mine === getSharedToken()) localStorage.removeItem(K.token)
}

/** 认领指针（`main.js` 启动时、且**本标签页没有票**时调一次）：把共享指针搬进本页。
 *  @returns {boolean} 是否认领到（供排查/测试）。
 *  ⚠️ **不在此处验票**：认领之后 `main.js` 照常打 `/user/me`，带的就是这张票；票若已废，
 *  那一发会 401 ⇒ 由 `restoreIdentity` 的 catch 丢掉它（`clearToken`）。 */
export function adoptSharedToken() {
  if (getToken()) return false
  const shared = getSharedToken()
  if (!shared) return false
  setToken(shared)
  return true
}

/** 只弃**本页**的票，**不碰**「最近登录」指针（`localStorage`）—— 与 {@link clearToken} 的 D6 规则**相反**。
 *
 *  <h3>【C3 块 2 手验后补丁「丁」（2026-10-10，用户裁决）：管理端入口不能持票】</h3>
 *  **管理端的票按构造只可能是别人的**：管理员**铸不出票**（`/user/token` 对 cookie 管理员答 401，
 *  TD-18），也**不发布**指针（D6 裁决）；指针的**认领**管理端入口早已不做（`main-admin.js` 不调
 *  {@link adoptSharedToken}）—— 唯一漏的是**同一个标签页**里从商城入口登录留下的这张
 *  `sessionStorage` 票（两个 store 同一键名，见本文件尾部那节）。而 `AuthSession.identify`
 *  **票优先**（且票不认识也**不回落** cookie）⇒ 管理端的每个请求都被后端认成**那个人**：
 *  · `/notification/list` 拿 `'*'` 比他的业务号 ⇒ **403**（收件箱打不开）；
 *  · `admin_audit` 的 `ActorId/ActorRole` 记成他 —— **实测**（a3 库，2026-10-09）Id=22 的
 *    `user.ban` 记 `ActorId=uDopHnCeLS / ActorRole=1`，而那是**客户 1111** 的业务号。
 *
 *  ⚠️ **别拿 {@link clearToken} 顶替**：它按 D6 在「本页票 == 指针」时把 `localStorage` 指针
 *  **一起删掉** —— 管理端不该替别的标签页清指针（那会让新标签页认领不到票）。本函数刻意**只动
 *  sessionStorage**。⚠️ 弃票之后管理端靠 **cookie 会话**认身份（闸关档下 `identify` 看得见
 *  `role=3` ⇒ `IdentityGuard` 的管理员档放行）；🔴 **闸开的档（默认 profile）本入口仍无凭据，
 *  是已知缺口**（§十三 13-5 / 新增 TD）。 */
export function dropPageToken() { sessionStorage.removeItem(K.token) }

// ── 会员字段读口（批四第二批 commit 3，2026-10-19）──────────────────────────────
// 数据源 = 会话恢复那一跳（`/user/me` 的 `user` 整行），并**镜像到 `sessionStorage`** ——
// 刷新时 `main.js` 对「本页已有身份」直接返回、不再打 `/user/me`，不落盘会静默退回普通用户价。
// 改档后的新鲜度：本人开通走 `setMemberLevel` 即时同步；管理员改他人档位则在对方下一次恢复时生效。
// ⚠️ 不在这里额外打 `/user/me`：重复请求只会让公开页多一条 401。
//
// ⚠️ 读口一律在 `restored === null` 时回「普通用户」缺省值 —— 与改动前逐字等价：
// 旧页面从不读这三个口，读到什么都不会改变既有渲染。
/** 会员等级：0 普通 / 1 VIP。无恢复态 ⇒ 0（按普通用户）。 */
export function getMemberLevel() { return restored && restored.memberLevel != null ? restored.memberLevel : (Number(read(K.memberLevel)) || 0) }
/** 账户余额（展示用；真实入账以服务端为准）。无恢复态 ⇒ 0。 */
export function getBalance() { return restored && restored.balance != null ? restored.balance : (Number(read(K.balance)) || 0) }
/** 品牌加盟店（商家侧）：true ⇒ 商品带「品牌」标。无恢复态 ⇒ false。 */
export function getIsBrand() { return !!(restored && restored.isBrand) || read(K.isBrand) === '1' }
/** 开通会员成功后同步展示态（`MemberCenter` 用）：内存 + `sessionStorage` 一起写 ⇒ 刷新后仍按 VIP 展示，不必重新登录。
 *  ⚠️ 只在**本人主动开通**这一处调用；管理员改他人档位仍走 `api/accounts.js`，被改的那一端下次恢复（刷新若已登录则不会重取）才见新值。 */
export function setMemberLevel(level) { const n = Number(level) || 0; if (restored) restored.memberLevel = n; try { sessionStorage.setItem(K.memberLevel, String(n)) } catch (e) { /* 存储不可用 ⇒ 只留内存 */ } }

