// src/config/labels/api-labels.js —— **HTTP 端点**的中文标签层（C3 块 7 · 阶段 3a.3 · C 块）。
//
// 【这张表是干什么的】可观测域的页面上会出现**原始请求路径**（目前唯一一处 = ⑤ 链路抽屉的
//   「请求开始（POST /goods/goodsUp）」那一行）。路径是给机器看的，人要的是「这一次请求干的
//   是哪件事」⇒ 本表把 `方法 + 全路径` 翻成一个**短中文名**。
//
// ⚠️ 【它不是契约】端点的入参 / 返回 / 状态码 / 调用点**一个都不在这里** —— 那一切以
//    `docs/agent/api-contract.md` 为准。本表只做「路径 → 人话短名」，是本仓
//    `config/labels/` 那一族（observe / audit / order / report / user）的第六张，同一套口径。
//
// ── 口径（三条，逐条都有出处）────────────────────────────────────────────────────
//
// ① **键 = `METHOD /path`**（方法全大写 + 一个空格 + 以 `/` 开的全路径）。
//    带上方法是**故意**的：本仓真有同一路径两条方法（`/admin/observe/attack/config` 的
//    `POST` 写配置与 `GET` 读配置），只按路径做键会把这两件事并成一条。
//    ⚠️ 路径**不带** query string；取用口 `apiLabel()` 会自己剥掉 `?...` 与结尾多余的 `/`。
//
// ② **条数 = 146**（全仓 `*Controller.java` 里 `^@(Post|Get|Put|Delete)Mapping` 的**方法级**
//    注解数，不含类级 `@RequestMapping`；2026-10-17 用脚本重新提取过一遍，逐条落进本表）。
//    ⚠️ **口径差的 ±1 写在这里，别让后人以为数错了**：朴素 `grep -E "@(Post|Get|Put|Delete)Mapping"`
//    全仓得 **147**，多出来的那一条是 `MetricsStreamController` 里 javadoc 正文的一句
//    `{@code @PostMapping}` 字面提及 —— 它是注释、不是端点。故**本表 146 条**，
//    文档里若见到「147 条」那是那个朴素 grep 的读数（`api-contract.md` 附录 H.0 记的 144
//    是 2026-10-15 的旧读数，两者都不是本表的出处）。
//    ⚠️ 另有两处**只有 `@PostMapping`、不带路径**的端点（`AdminStatsController` 的看板与
//    `MerchantStatsController` 的看板），它们的全路径就是类级 base 本身：
//    `POST /admin/stats` 与 `POST /merchant/stats` —— 提取脚本会把紧随其后那个
//    `@RequestParam` 的**参数名**当成路径片段（实测吐出 `POST /admin/stats/from` 这种不存在的路径），
//    本表按注解实际形态改正，不照抄脚本产物。
//
// ③ **兜底 = 认不出回落原值**（同 `observe-labels.js`：`hasOwnProperty` 判，不用 `TBL[k] || k`
//    —— 后者对 `'constructor'` 这类原型链上的键会返回函数并渲染进模板）。
//    这里「原值」= 调用方给的那个 `METHOD /path` 本身，**不是**空串、也不是「未知接口」：
//    没登记的端点照样把路径念出来，只是没人话。空 / `null` ⇒ 返回空串（调用方据此**跳过该槽位**，
//    不写占位符 —— 与 `sentence()` 的「缺槽位跳过」同一条规则）。

/**
 * `METHOD /path` → 中文短名。**146 条**，按 Controller 分组（分组注释只是给人找，不影响查表）。
 *
 * ⚠️ 值的写法有两条约定：① 一律**动词开头或名词短语**，不带标点、不带 `接口` 二字；
 *    ② 同一件事的读写两端**要能一眼分开**（`改演练配置` / `读演练配置`、`订单列表` / `订单详情`）。
 */
export const API_ENDPOINTS = {
  // ── 账号 / 入驻 / 降级（AdminAccountsController）────────────────────────────
  'POST /admin/accounts/users': '账号列表',
  'POST /admin/accounts/user/ban': '封禁用户',
  'POST /admin/accounts/user/unban': '解封用户',
  'POST /admin/accounts/applications': '入驻申请列表',
  'POST /admin/accounts/application/audit': '审核入驻申请',
  'POST /admin/accounts/downgrade/schedule': '预约降级',
  'POST /admin/accounts/downgrade/cancel': '取消降级',
  'POST /admin/accounts/downgrade/relist': '确认重新上架',

  // ── 审计台账（AdminAuditController，base = `/admin`）────────────────────────
  'POST /admin/audit/list': '审计日志列表',
  'POST /admin/audit/detail': '审计详情',
  'POST /admin/audit/facets': '审计分面统计',

  // ── 商品目录 / 违规处置（AdminCatalogController）────────────────────────────
  'POST /admin/catalog/applications': '上架申请列表',
  'POST /admin/catalog/application/audit': '审核上架申请',
  'POST /admin/catalog/category/create': '新建分类',
  'POST /admin/catalog/category/update': '修改分类',
  'POST /admin/catalog/category/delete': '删除分类',
  'POST /admin/catalog/violation/delist': '违规下架',
  'POST /admin/catalog/violation/delete': '违规删除',

  // ── 后台通知（AdminNotificationController）─────────────────────────────────
  'POST /admin/notify/send': '后台发通知',
  'POST /admin/notify/history': '通知历史',

  // ── 后台订单（AdminOrderController）────────────────────────────────────────
  'POST /admin/order/list': '订单列表',
  'POST /admin/order/detail': '订单详情',
  'POST /admin/order/monitor': '订单异常监控',
  'POST /admin/order/intervene': '订单人工干预',

  // ── 购物车（CartController）────────────────────────────────────────────────
  'POST /cart/add': '加入购物车',
  'POST /cart/update': '改购物车数量',
  'POST /cart/remove': '移出购物车',
  'POST /cart/list': '购物车列表',
  'POST /cart/checkout': '购物车结算',

  // ── 评论（CommentController）──────────────────────────────────────────────
  'POST /comment/list': '评论列表',
  'POST /comment/adminList': '后台评论列表',
  'POST /comment/add': '发表评论',
  'POST /comment/delete': '删除评论',
  'POST /comment/accept': '采纳回答',
  'POST /comment/like': '评论点赞',
  'POST /comment/imageReview': '图片评论审核',
  'POST /comment/myOrders': '可评论的历史商品',

  // ── 举报 / 敏感词 ─────────────────────────────────────────────────────────
  'POST /report/add': '提交举报',
  'POST /report/list': '举报列表',
  'POST /report/handle': '处理举报',
  'POST /sensitive/list': '敏感词列表',
  'POST /sensitive/add': '新增敏感词',
  'POST /sensitive/delete': '删除敏感词',

  // ── 商品（GoodsController）────────────────────────────────────────────────
  'POST /goods/goodsAdd': '上架商品',
  'POST /goods/goodsSelect': '商品列表',
  'POST /goods/goodsSelectHome': '首页商品',
  'POST /goods/goodsDe': '下架商品',
  'POST /goods/productCodePrice': '按编码查价格',
  'POST /goods/skuList': 'SKU 列表',
  'POST /goods/goodsUp': '修改商品',
  'POST /goods/GoodsProductCodeSelect': '按编码查商品',
  'POST /goods/productSearch': '商品搜索',
  'POST /goods/categoryGoods': '分类下商品',
  'POST /goods/myApplications': '我的提审记录',

  // ── 优惠券（管理端 + 用户端）──────────────────────────────────────────────
  'POST /admin/marketing/coupon/list': '优惠券列表',
  'POST /admin/marketing/coupon/create': '新建优惠券',
  'POST /admin/marketing/coupon/stop': '停发优惠券',
  'POST /admin/marketing/coupon/activate': '启用优惠券',
  'POST /admin/marketing/coupon/grants': '领取明细',
  'POST /admin/marketing/coupon/grant': '定向发券',
  'POST /admin/marketing/coupon/stats': '优惠券统计',
  'POST /marketing/coupons': '可领优惠券',
  'POST /marketing/myCoupons': '我的优惠券',
  'POST /marketing/coupon/claim': '领取优惠券',
  'POST /marketing/coupon/claimBatch': '批量领券',

  // ── 秒杀（管理端 + 用户端）─────────────────────────────────────────────────
  'POST /admin/marketing/seckill/invitations': '秒杀邀请列表',
  'POST /admin/marketing/seckill/invite': '邀请参加秒杀',
  'POST /admin/marketing/seckill/applications': '秒杀申请列表',
  'POST /admin/marketing/seckill/review': '审核秒杀申请',
  'POST /admin/marketing/seckill/activities': '秒杀活动列表',
  'POST /admin/marketing/seckill/stop': '停止秒杀活动',
  'POST /marketing/seckill/invitations': '我的秒杀邀请',
  'POST /marketing/seckill/respond': '回应秒杀邀请',
  'POST /marketing/seckill/applications': '我的秒杀申请',
  'POST /marketing/seckill/apply': '申请秒杀',
  'POST /marketing/seckill/activities': '秒杀活动',
  'POST /marketing/seckill/live': '秒杀进行中',

  // ── 补贴（管理端 + 用户端）─────────────────────────────────────────────────
  'POST /admin/marketing/subsidy/list': '补贴列表',
  'POST /admin/marketing/subsidy/create': '新建补贴',
  'POST /admin/marketing/subsidy/stop': '停止补贴',
  'POST /admin/marketing/subsidy/activate': '启用补贴',
  'POST /admin/marketing/subsidy/nominate': '提名补贴商品',
  'POST /admin/marketing/subsidy/nominations': '提名列表',
  'POST /marketing/subsidy/invitations': '我的补贴邀请',
  'POST /marketing/subsidy/confirm': '确认补贴',
  'POST /marketing/subsidy/goods': '补贴商品',

  // ── 商家入驻（MerchantApplicationController）───────────────────────────────
  'POST /merchant/apply': '提交入驻申请',
  'POST /merchant/myApplications': '我的入驻申请',

  // ── 消息 / 好友（MessageController）────────────────────────────────────────
  'POST /message/conversations': '会话列表',
  'POST /message/history': '聊天记录',
  'POST /message/send': '发消息',
  'POST /message/getOrCreateConversation': '打开会话',
  'POST /message/userSelect': '搜索用户',
  'POST /message/friendAdd': '加好友',
  'POST /message/selectFriend': '好友列表',
  'POST /message/friendUp': '成为好友',
  'POST /message/friendDe': '删好友',
  'POST /message/friendSelect': '好友查询',

  // ── 站内通知（NotificationController）──────────────────────────────────────
  'POST /notification/list': '通知列表',
  'POST /notification/unreadCount': '未读数',
  'POST /notification/read': '标记已读',
  'POST /notification/readAll': '全部已读',

  // ── 可观测域（observe：演练 8 + 三条 SSE + 回放）───────────────────────────
  'POST /admin/observe/attack/scenarios': '演练场景清单',
  'POST /admin/observe/attack/run': '发起攻击演练',
  'POST /admin/observe/attack/config': '改演练配置',
  'GET /admin/observe/attack/config': '读演练配置',
  'POST /admin/observe/attack/stop': '停止演练',
  'POST /admin/observe/attack/status': '演练状态',
  'POST /admin/observe/attack/records': '演练记录',
  'POST /admin/observe/attack/cleanup': '清理演练数据',
  'POST /admin/observe/events/stream': '事件流（SSE）',
  'POST /admin/observe/metrics/stream': '指标流（SSE）',
  'POST /admin/observe/orders/stream': '订单流（SSE）',
  'POST /admin/observe/replay': '演练回放',

  // ── 订单（OrderController）────────────────────────────────────────────────
  'POST /order/orderAdd': '下单',
  'POST /order/orderSelect': '我的订单',
  'POST /order/orderDeliveryIfSelect': '待发货订单',
  'POST /order/orderDeliverySelect': '已发货订单',
  'POST /order/historicalOrdersSelect': '历史订单',
  'POST /order/orderPayIfUp': '订单付款',
  'POST /order/orderReceiveIfUp': '确认收货',
  'POST /order/orderDeliveryIfUp': '商家发货',
  'POST /order/orderDetail': '订单详情',

  // ── 健康检查（HealthController）────────────────────────────────────────────
  'GET /health/db': '数据库健康检查',

  // ── 店铺（关注 + 资料，两个 Controller 共用 base `/shop`）────────────────────
  'POST /shop/follow': '关注店铺',
  'POST /shop/unfollow': '取关店铺',
  'POST /shop/followState': '关注状态',
  'POST /shop/profile': '店铺资料',

  // ── 统计（两个 Controller，两条都是类级 `@PostMapping` 不带路径）────────────
  'POST /admin/stats': '全站看板',
  'POST /admin/stats/rebuild': '重建统计聚合',
  'POST /merchant/stats': '店铺看板',

  // ── 模板（管理端 + 用户端）─────────────────────────────────────────────────
  'POST /admin/template/list': '模板列表',
  'POST /admin/template/create': '新建模板',
  'POST /admin/template/update': '修改模板',
  'POST /admin/template/delete': '删除模板',
  'POST /template/list': '可用模板列表',
  'POST /template/get': '模板详情',

  // ── 用户 / 会话（UserController）───────────────────────────────────────────
  'POST /user/loginSelect': '登录',
  'POST /user/loginAdd': '注册',
  'POST /user/getUserInfo': '用户信息',
  'POST /user/updateAvatar': '更换头像',
  'POST /user/updateUserInfo': '更新个人信息',
  'POST /user/me': '当前会话用户',
  'POST /user/token': '换取令牌',
  'POST /user/logout': '退出登录',
  'POST /user/updateProfile': '更新性别年龄',
}

/**
 * 逻辑锁名 → 中文（`MallLockEvent.lock`，取值 = `DistributedLockService.LockName` 的 `tag()`）。
 *
 * ⚠️ **五把锁，取值有界**（枚举常量），列表就是全量 —— 不是「已知的几种」。
 *    `null` 那条（锁可用性翻转 transition）没有锁名，取用口返回空串（跳过槽位）。
 */
export const LOCK_LABEL = {
  'order-add': '下单',
  'order-pay': '支付',
  'order-receive': '收货',
  'order-delivery': '发货',
  'friend-add': '加好友',
}

/**
 * 缓存区名 → 中文（`MallCacheEvent.cache`，取值 = `@Cacheable(cacheNames = …)` 的**区名**，
 * 也就是 `TwoLevelCache.name`）。
 *
 * ⚠️ **八个区，取值有界**：全仓 `cacheNames` 字面量去重后正好这 8 个（2026-10-17 实测）。
 *    「商品类型」那条是 `goodstype` —— 与 `MallCacheMetrics` 类注释里 `kind=l1/l2/...`
 *    的**另一层** tag 不是一回事：那是「哪一层命中」，这是「哪个区」。
 */
export const CACHE_AREA_LABEL = {
  goodstype: '商品类型',
  goodsHome: '首页商品',
  goodsByMerchant: '店铺商品',
  goodsPrice: '商品价格',
  goodsCodes: '商品编码',
  friendList: '好友列表',
  friendRequests: '好友申请',
  userPublic: '用户公开资料',
}

/** 端点数（本表自身的读数，供页面 / 探针核对「表有没有被改小」）。 */
export const API_ENDPOINT_COUNT = Object.keys(API_ENDPOINTS).length

/** `hasOwnProperty` 判在不在表里（不用 `in` —— 那会连原型链上的键一起算进来）。 */
const has = (tbl, k) => Object.prototype.hasOwnProperty.call(tbl, k)

/**
 * 取端点的中文短名。**认不出 ⇒ 回落 `METHOD /path` 原值**（不是空串、不是「未知接口」）。
 *
 * @param method 帧里那个方法名（大小写不敏感：内部 `toUpperCase()`）
 * @param path   请求路径；**允许带 query string 与结尾 `/`**（真请求常有），取用口自己归一：
 *               `'/goods/goodsUp?t=1'` 与 `'/goods/goodsUp/'` 都应认出「修改商品」。
 *               空 / `null` ⇒ 返回空串（调用方跳过这一槽位，不写占位符）。
 */
export function apiLabel(method, path) {
  const m = String(method === null || method === undefined ? '' : method).trim().toUpperCase()
  const p0 = String(path === null || path === undefined ? '' : path).trim()
  if (!m && !p0) return ''
  // ⚠️ 先剥 `?`/`#`（真路径里带 query 很常见），再剥结尾多余的 `/`；根路径 `/` 单独保底，
  //    否则 `'/'` 会被剥成空串、和「没给路径」混成同一个结果。
  const p = p0.split('?')[0].split('#')[0].replace(/\/+$/, '') || '/'
  const key = `${m} ${p}`
  return has(API_ENDPOINTS, key) ? API_ENDPOINTS[key] : key
}

/** 取锁名中文。认不出回落锁名原值；空 / `null` ⇒ 空串。 */
export function lockLabel(name) {
  if (name === null || name === undefined || name === '') return ''
  const k = String(name)
  return has(LOCK_LABEL, k) ? LOCK_LABEL[k] : k
}

/** 取缓存区中文。认不出回落区名原值；空 / `null` ⇒ 空串。 */
export function cacheAreaLabel(name) {
  if (name === null || name === undefined || name === '') return ''
  const k = String(name)
  return has(CACHE_AREA_LABEL, k) ? CACHE_AREA_LABEL[k] : k
}
