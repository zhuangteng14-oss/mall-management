// src/config/labels/user-labels.js —— **用户 / 账号域**的中文标签层（C3 块 7 · commit 9，2026-10-14）。
//
// ⚠️ 【这一域只有一个 code，而且它是**数字**】`login.Role` / `user.usertype` 的写法 / `admin_audit.ActorRole`
//    三处指的是同一件事：**1 客户 / 2 商家 / 3 管理员**。唯一出处是后端 `login` 表的 `Role` 列 ——
//    入场审核（`AdminAccountsController`）、封禁、通知收件人（`NotificationService`）都从它取。
//
// ⚠️ 【别把它与 `usertype` 混为一谈】`user.usertype` 存的是**中文字面量** `"客户"` / `"商家"`
//    （后端按中文字面量比对），不是 code ⇒ `AccountsPage` 那一格显示的**本来就是中文**，
//    本页不需要、也不该给它配一张翻译表（配了就是把「已经是中文」翻第二遍）。
//    ⚠️ 两列**互不联动**：改 `Role` 不会带 `usertype`，反之亦然。
//
// ⚠️ 【兜底口径】认不出的角色码**原样回码**；空 / null 回 `—`（不是空串）——
//    理由与 `audit-labels.js` 同一句：闸关着的 dev / a3 上取不到身份是**有信息**的，
//    画成空白会被读成「渲染坏了」。

/**
 * 角色码 → 中文（1 客户 / 2 商家 / 3 管理员）。**取值唯一出处**是后端 `login.Role`。
 *
 * ⚠️ 镜子关系：本表与后端 `AuthInterceptor` 里那个 `role == 3 ⇒ 管理员豁免` 的判据**必须同时改**
 *    （改角色编码 = 改这个闸的语义）。前端这份只负责画，不参与任何权限判断。
 */
export const ROLE_LABEL = {
  1: '客户',
  2: '商家',
  3: '管理员',
}

/**
 * 取角色中文。⚠️ 入参可能是**数字**（后端列是 `Integer`）或数字串 ⇒ 先 `String()` 归一，
 *   否则 `1` 与 `'1'` 会在 `hasOwnProperty` 下走两条路（对象键一律是字符串）。
 */
export function roleLabel(role) {
  if (role === null || role === undefined || role === '') return '—'
  const k = String(role)
  return Object.prototype.hasOwnProperty.call(ROLE_LABEL, k) ? ROLE_LABEL[k] : k
}
