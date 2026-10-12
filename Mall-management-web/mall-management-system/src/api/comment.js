// src/api/comment.js —— 内容域「评论 / 问答 / 回复 / 赞踩」API 封装
// （C3 块 6 · commit 1 评论 + commit 2 问答；2026-10-12）。
//
// 六个端点、一个模块（后端 `CommentController`，`@RequestMapping("/comment")`，六条全 POST）：
//   list     —— 唯一调用方 `views/ProductPage.vue`（「累计评价」页签下的评论 / 问答两个子 tab）。
//   add      —— 发表：评论 / 回复 / 提问 / 回答**共用一个端点**（方案 §三的端点表逐字）。
//   delete   —— 作者 / 管理员删一条（顶层连带回复）。
//   accept   —— 采纳答案（问答，commit 2）：提问者选一条回答 ⇒ 置顶 + 标记已解决。
//   like     —— 赞 / 踩（顶层与回复都适用）。
//   myOrders —— 发表评论时「选对应商品」的下拉（本人**已确认收货**的历史订单里的商品）。
//
// 为什么单列一个模块（而不是在 view 里直接 `http.post`）：三条理由与 `api/shop.js` / `api/marketing.js`
// 逐字同款 ——
//   ① **路径不在这里硬编码** —— 一律取自 `endpoints.js`（全仓唯一允许出现后端路径字面量的文件）；
//   ② 参数一律走 `URLSearchParams` 字符串（`add` 的图片形态除外，见下）—— 本仓 `http.post` 的既有形态。
//      ⚠️ **别改成发 JSON**：后端收的是 `@RequestParam`，`application/json` 的 body 它**不绑定**，
//      而请求照样 200 ⇒ 表现为「静默没发出去 / 静默没删掉 / 静默没点赞」；
//   ③ **空值一律不发** —— 后端十个入参**全部** `required = false` + Service 手判
//      （为什么不做成裸 `@RequestParam` 的 400，理由写在 `CommentController` 类注释里）。
//
// ⚠️ 与 `api/shop.js` / `api/order.js` / `api/marketing.js` 的 `q()` / `post()` 是**同形复制、
//    不是共享** —— 本仓 `api/*.js` 各模块自带这两个内联函数，未抽公共模块。
//
// ⚠️ **六条里只有 `list` 是公开面**（块 6 唯一进白名单的一条）：`/comment/list` 已进
//    `WebAuthConfig.AUTH_WHITELIST`（**13 → 14 条**）⇒ **未登录也能打** —— 商品详情页
//    （`meta.public`）的评论 / 问答区在匿名状态下照常加载。**另五条**
//    （add / delete / **accept** / like / myOrders）**仍不在白名单**（匿名调必 401，
//    与 `cart` / `shop.follow` 同族）。
//    ⚠️ 别因为「都在 `/comment` 下」就把六条一起当公开面。
//
// ⚠️ **五条写端点的判据是「登录名」，不是业务号**：`comment.AuthorId` / `comment_like.UserId`
//    存的是 `login.UserName`（见 `CommentDao` 类注释），后端跑的是
//    `IdentityGuard.requireSelfOrAdminByUsername(request, Account)` ⇒ 传**别人的** `Account`
//    是 **403**（不是「查不到」）⇒ `account` **绝不要**从路由 / URL 取，只能是**当前登录者自己的**。
//
// ⚠️ **`list` 的「这条是不是我发的」不由入参决定**：观看者只有**会话**一个来源
//    （`AuthSession.currentUserId`）⇒ 本模块**刻意不接受** `Account` 参数给 `list`。
//    加了它就等于让人拿别人的登录名去问「这条匿名评论是不是你写的」，匿名就成了摆设。
import { http } from './http'
import { endpoints } from './endpoints'

/**
 * 组装表单 body：只收**有值**的键（`null` / `undefined` / `''` 一律不发）。见文件头第 ③ 条。
 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/**
 * 发一发 POST 并**拆掉 `Response` 外壳**，直接答 JSON body。
 * （`http.post` 返回的是裸 `Response`，见 `http.js`；拆壳收在这里，view 只管数据。）
 */
async function post(path, body) {
  return (await http.post(path, body)).json()
}

/**
 * 评论列表（顶层 + 每条顶层的回复 + 统计卡）。**公开端点** —— 未登录也能调。
 *
 * ⚠️ **一页拿三样**：`list`（顶层行，每条自带 `Replies` 数组与 `Images`）/ `stats`（评分分布或
 *    提问解决率）/ `total` —— 前端不必为回复或统计再发往返。
 *
 * ⚠️ `Kind` **不传 = 不筛**（`review` 与 `question` 混着回）；`ProductPage.vue` 的评价页签
 *    **必须显式传 `Kind: 'review'`**，否则提问会混进「累计评价」里 —— 那是**前端的选择**，
 *    不是后端的默认。
 *
 * @param {object} p
 * @param {string} p.targetType `goods`（商品）/ `user`（用户）。省略 ⇒ 后端当 `goods`。
 * @param {string|number} p.targetId 对象 ID —— 商品评论时是 **`goods.Id`**（`route.query.Id`），
 *        不是 `ProductCode`。⚠️ **必填**：不传后端拒（`缺少参数：TargetId`），不当成 0。
 * @param {string} [p.kind]   `review`（评价）/ `question`（提问）。⚠️ 未知值**拒**，不静默当「不筛」。
 * @param {string} [p.sort]   `hot`（按点赞数）/ `new`（按时间，**默认**）。⚠️ 未知值**拒** ——
 *        它参与 `ORDER BY` 拼串，是**注入面**，白名单是硬闸。
 * @param {string} [p.solved] 仅对 `kind=question` 有意义：`'1'` 已解决 / `'0'` 未解决。
 * @param {number} [p.limit]  页大小（默认 20，上限 100 —— 超了按 100 收）。
 * @param {number} [p.offset] 偏移（默认 0）。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number, offset:number,
 *                    stats:object, msg?:string}>}
 *          ⚠️ 匿名行**没有 `AuthorId`**（后端置 `null`）、`Author` 是 `首字*****` —— 别拿
 *          `AuthorId` 反查昵称，也别把 `Author` 当登录名用（那正是匿名想避开的）。
 *          ⚠️ **只有 `Status === 'approved'` 的图才带 `Url`**；`pending`（审核中）与 `rejected`
 *          （违规已删）**都是 `null`** —— 后端刻意不给未过审的图一个可用的链接（判据留在出口一处，
 *          不让每个消费方各自「记得看一眼 Status」）。⇒ **按 `Status` 渲染**：「审核中」/「图片违规
 *          已删除」，**别**拿 `Url` 的 `null`/非 `null` 当状态用。
 */
export function list(p) {
  return post(endpoints.comment.list, q({
    TargetType: p.targetType,
    TargetId: p.targetId,
    Kind: p.kind,
    Sort: p.sort,
    Solved: p.solved,
    Limit: p.limit,
    Offset: p.offset,
  }))
}

/**
 * 发表 —— 评论 / 回复 / 提问 / 回答**共用一个端点**（四种形态靠 `parentId` 与 `kind` 区分）。
 *
 * ⚠️ **带图时走 multipart，不带图时不走** —— 这一个函数两条发送形态，是本模块唯一的例外：
 *    - `files` 非空 ⇒ `FormData`（`http.js` 的 `buildInit` 对它是**原样塞进 `init.body`、不设
 *      `Content-Type`**，边界由浏览器加）。**别手工写 `Content-Type: multipart/form-data`** ——
 *      手写会丢掉 `boundary`，后端解不出任何 part；
 *    - `files` 空 ⇒ 与另四条**完全同形**的 `URLSearchParams` 字符串。
 *    ⇒ 加图片没有第 14 个端点（方案 A4 钉的是 13 条），图与本条文字**同一次提交**。
 *
 * ⚠️ **回复的 `Kind` 由后端按父行推，不由这里传**（回复提问 ⇒ `answer`，回复评价 ⇒ `review`）——
 *    传了也会被忽略。⚠️ `kind:'answer'` **只能**出现在回复里，顶层传它会被拒。
 *    ⚠️ **两层封顶**：`parentId` 必须指向一条**顶层**行；要「回复某条回复」用 `replyToId`（@ 谁），
 *    传同层的 `parentId` 会把这条挂到错的楼里（后端判 `ReplyToId` 与父行同楼，不同 ⇒ 拒）。
 *
 * @param {object} p
 * @param {string} p.account   发表者**登录名**（只能是自己）—— 见文件头 ⚠️。
 * @param {string|number} [p.targetId] 顶层行必填（`goods.Id` / `user` 业务号）；**回复时不传**
 *        （后端从父行推）。
 * @param {string} [p.targetType]     同上，顶层行用。
 * @param {string|number} [p.parentId] 回复哪条**顶层**评论；顶层发表不传。
 * @param {string|number} [p.replyToId] @ 谁（同楼内某条回复的 Id）；不 @ 人不传。
 * @param {string} p.content 正文。⚠️ 空 / 纯空白**拒**；超 **1000** 字**拒**（`varchar(1000)` 那列的
 *        长度就是 1000，不是能随手调的内部参数）。
 * @param {number} [p.rating] 星级 1–5。⚠️ **匿名时后端强制 5**（方案 §4.1）—— 前端别抢先算；
 *        商品评价首次发表才有意义，回复 / 提问忽略它。
 * @param {boolean|string} [p.anonymous] 匿名。⚠️ **不是所有形态都能匿名**：评价可选；**提问强制实名**；
 *        **回答强制匿名**（商家回答除外 —— 商家实名）。传了也被后端按形态覆盖，见 `resolveAnonymous`。
 * @param {string} [p.productCode] 买的那个货号（`goods.ProductCode` 或 `goods_sku.SkuCode`），
 *        评论时带上更精确（同商品多 SKU）。
 * @param {File[]} [p.files]  最多 **9** 张，存 `static/img/comment/`，**先审后显**（落库即 `pending`）。
 * @returns {Promise<{success:boolean, Id:number, Kind:string, images:number, imageFailed:number,
 *                    msg:string}>}
 *          ⚠️ **`success:true` 也可能是「评论进了、图没进全」**：`imageFailed > 0` 要如实报出来，
 *          别拿 `success` 一个字段当全绿。⚠️ 未买过 / 重复评价 ⇒ `success:false`
 *          （`购买并确认收货后才能评价` / `您已评价过该商品，不能重复评价`）—— 那是**业务规则**，
 *          不是故障，别弹「系统错误」。
 */
export function add(p) {
  const files = (p.files || []).filter(Boolean)
  if (files.length === 0) {
    return post(endpoints.comment.add, q({
      Account: p.account,
      TargetType: p.targetType,
      TargetId: p.targetId,
      ParentId: p.parentId,
      ReplyToId: p.replyToId,
      Kind: p.kind,
      Content: p.content,
      Rating: p.rating,
      Anonymous: p.anonymous,
      ProductCode: p.productCode,
    }))
  }
  const fd = new FormData()
  Object.entries({
    Account: p.account,
    TargetType: p.targetType,
    TargetId: p.targetId,
    ParentId: p.parentId,
    ReplyToId: p.replyToId,
    Kind: p.kind,
    Content: p.content,
    Rating: p.rating,
    Anonymous: p.anonymous,
    ProductCode: p.productCode,
  }).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') fd.append(k, String(v))
  })
  files.forEach((f) => fd.append('Files', f))
  return post(endpoints.comment.add, fd)
}

/**
 * 删一条评论（作者 / 管理员）。
 *
 * ⚠️ **删顶层会连带删掉它下面的回复**（后端 `softDeleteRepliesUnder`），删**回复**只删那一条 ——
 *    前端该据此调整确认文案（「连同 N 条回复」而不是「这条评论」）。
 *
 * @param {string} account 删除者**登录名**（只能是自己，除非是管理员）。
 * @param {string|number} id 要删的评论 Id。
 * @returns {Promise<{success:boolean, msg:string, replies?:number}>}
 *          `replies` 是**连带删掉的回复条数**（只对顶层行有意义）。
 */
export function remove(account, id) {
  return post(endpoints.comment.delete, q({ Account: account, Id: id }))
}

/**
 * 采纳一条答案（问答，块 6 commit 2）。
 *
 * ⚠️ **它是「提问者的动作」，但收件人是回答者**：后端会顺手给被采纳那条回答的作者发一条
 *    `comment.accepted` 通知（fail-soft）⇒ 调用方**不需要**再自己发通知，也别因为
 *    「没看到通知」以为采纳没成功。
 *
 * ⚠️ **采纳后不可改**（方案 §4.2）：后端那道闸在 SQL 的 `WHERE` 里。
 *    已采纳过 ⇒ `success:false` + `该提问已采纳过答案，不能更改`
 *    —— 那是**业务规则**（不是故障），前端据此把按钮置灰而不是弹「系统错误」。
 *
 * @param {string} account 提问者**登录名**（只能是自己，除非是管理员）—— 见文件头 ⚠️。
 * @param {string|number} id 那条**顶层提问**的 Id（不是回答的 Id）。
 * @param {string|number} replyId 要采纳的那条**回答**的 Id（必须挂在该提问下）。
 * @returns {Promise<{success:boolean, Id:number, AcceptedReplyId:number, msg:string}>}
 *          ⚠️ 成功后**回读列表**（`list`）而不是本地把这条回复挪到最前 —— 置顶次序是
 *          后端 `Replies` 数组的顺序（`Accepted` 为真的那条在最前），本地再排一遍会两处漂开。
 */
export function accept(account, id, replyId) {
  return post(endpoints.comment.accept, q({ Account: account, Id: id, ReplyId: replyId }))
}

/**
 * 赞 / 踩一条评论（顶层与回复都适用）。
 *
 * ⚠️ **`type` 必填、且「再点同一种 = 取消」也走显式传同一个值** —— 本端点**没有**
 *    「省略 = 切换」这种隐式语义：隐式语义在前端重试 / 重复点击时会翻成反效果
 *    （用户以为在点赞，实际把上一次的赞取消了）。
 *
 * @param {string} account 点赞者**登录名**（只能是自己）。
 * @param {string|number} id 评论 Id。
 * @param {'like'|'dislike'} type ⚠️ 必填。未知值 ⇒ 拒（`缺少参数：Type（只能是 like / dislike）`）。
 * @returns {Promise<{success:boolean, Id:number, MyLike:(string|null),
 *                    LikeCount:number, DislikeCount:number, msg?:string}>}
 *          ⚠️ **三个值都是回读的当前真值**（不是「原值 ±1」）⇒ 前端直接拿它们覆盖本地状态，
 *          别自己再加一次。`MyLike` 为 `null` 表示这次把票撤了。
 */
export function like(account, id, type) {
  return post(endpoints.comment.like, q({ Account: account, Id: id, Type: type }))
}

/**
 * 本人**可评论**的历史商品（发表评论时「选对应商品」的下拉）。
 *
 * ⚠️ 「可评论」的口径是**后端**定的、前端别自己筛：`ReceiveIf = 1`（**已确认收货**）才算
 *    —— 下单 / 发货中 / 未收货的订单**不在**这个列表里。
 *
 * @param {string} account 本人**登录名**（只能是自己）。⚠️ 只有**客户**有购买记录；
 *        商家 / 管理员调它 ⇒ `success:false` + `该账号没有历史订单（只有客户有购买记录）`。
 * @returns {Promise<{success:boolean, list:Array, limit:number, msg?:string}>}
 *          每项含 `GoodsId` / `MerchantId` / `GoodName` / `ProductCode` / `OrderedAt`。
 *          ⚠️ 拿去发评论时 `targetId` 要用 **`GoodsId`**（不是 `ProductCode`）。
 */
export function myOrders(account) {
  return post(endpoints.comment.myOrders, q({ Account: account }))
}
