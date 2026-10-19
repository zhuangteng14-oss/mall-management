// src/api/order.js —— 订单 / 购物车 API 封装（C3 块 4〈订单块〉，2026-10-10）。
//
// 三块内容，**边界写死在这里**（免得日后有人以为「订单域的接口都在一个模块里」而把口径合并）：
//   ① 订单域既有 8 条（`/order/*`）—— 本文件**承接**它们，把原先散在 4 个 view 里的
//      `http.post(endpoints.order.x, params.toString())` + `res.json()` 两行收成一处。
//      ⚠️ 这是**搬家**，不是改行为：每条函数的入参就是原调用点 `URLSearchParams` 里的那几个键，
//         **含空值也照发**（见下面 `build()` 与 `q()` 的分工说明）。
//   ② 购物车域 5 条（`/cart/*`，块 4 · A1 甲新建的 `cart` 表）。
//   ③ 管理端订单治理 4 条（`/admin/order/*`，块 4 新页 `views/admin/OrderPage.vue`）。
//
// ⚠️ **`q()` / `post()` 与 `admin.js` / `accounts.js` / `template.js` 里那两个是逐字相同的
//    本地副本**（刻意的重复）：抽成共享模块要动那三份刚落的文件、越出本块范围，而这里只有
//    两个小 helper。**第五份副本**——`accounts.js:11-15` 早把这条口径记成「仍不抽，留给专门的
//    收敛笔」，本文件照它办。三条附带口径同样适用（不是抄漏）：
//      ① 参数一律 `URLSearchParams` 字符串、**别改成 JSON**（后端收 `@RequestParam`，
//         `application/json` 的 body 它**不绑定**，而请求照样 200 ⇒ 表现为「静默无效」）；
//      ② 空值一律不发（后端的缺参判据是 `isBlank`，发空串 = 「给了个空值」）；
//      ③ 一律**拆掉 `Response` 外壳**（`http.post` 返回裸 `Response`），直接答 JSON body。
import { http } from './http'
import { endpoints } from './endpoints'

/** 组装表单 body，**丢掉**空值。只给「空 = 没筛」语义的入参用（`/admin/order/list` 的四个筛选键）。 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/**
 * 组装表单 body，**原样发每一条**（含空串 / `null` 转成的空串）。
 *
 * ⚠️ 与 `q()` 的关系是**刻意的两套**，别互相替换：`q()` 丢空值，本函数不丢。
 *    订单域的既有调用点里，`OrderId` / `Price` / `Images` 这些键**有空串的合法含义**
 *    （`Images` 为空 ⇒ 库里存的是 `''` 还是 `NULL`，**取决于发不发这个键**）⇒ 换成 `q()`
 *    会把「发了个空串」静默改成「没发」，那是**改行为**，不是搬家。
 */
function build(pairs) {
  const usp = new URLSearchParams()
  Object.entries(pairs || {}).forEach(([k, v]) => {
    usp.append(k, v === undefined || v === null ? '' : String(v))
  })
  return usp.toString()
}

/** 发一发 POST 并**拆掉 `Response` 外壳**，直接答 JSON body。见文件头 ③。 */
async function post(path, body) {
  const res = await http.post(path, body)
  return res.json()
}

// ══════════════════════════════════════════════════════════════════════════════
// ① 订单域既有 8 条（`/order/*`）—— 搬家，逐条与改前的调用点一一对应
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 下单（**同时**是「立即购买」与「加入购物车」的旧实现 —— 两者都走它，只差 `PayIf`）。
 *
 * @param {{CustomerId:string, MerchantId:string, OrderId:string, Price:number|string,
 *          ProductInformation:string, Images:string, PayIf:number, DeliveryIf:number,
 *          ReceiveIf:number, Quantity?:number, Items?:string, CouponId?:string, ActivityId?:string}} payload
 *        ⚠️ `Quantity` 是 **C3 块 4（A2 甲）新加的入参**，不传 = 1（后端 `defaultValue="1"`）。
 *        ⚠️ `Items` 是 **可视化段 commit 0 新加的入参**（**显式**商品明细 ⇒ 服务端写 `order_item` 表）：
 *           一个 **JSON 数组串**，一件一项：`[{"code":"IJ-555","name":"222","price":5555,"qty":2}]`
 *           （`code` 必填、`name` 可缺、`qty` 缺省 1、`price` 是**单价**）。⚠️ **不是**自造的分隔符串 ——
 *           商品名未禁 `:` / `；`，分隔符一旦错位就是**脏数据**（理由见 `OrderService.resolveItems`）。
 *           **不传 / 传空 ⇒ 后端回落到解析 `ProductInformation` 的既有格式**（`名:码` 以「；」分隔；
 *           仅**恰有一段**时推得出单价 ⇒ 一单多品不写明细），再解析不出就不写 ⇒ **老调用方行为不变**。
 *           ⚠️ `build()` 会把 `undefined` 转成**空串**（本文件 `:42`）⇒ 这里的「不传」到后端是 `Items=`。
 *        ⚠️ `CouponId` 是 **C3 块 5 · commit 5 续笔新加的入参**（「立即购买」结算用券，2026-10-12）：
 *           不传 / 传空串 ⇒ 与加它之前**逐字同行为**（后端 `CouponId` 是 `required=false`、缺省 `""`）。
 *           ⚠️ 传了它 **`Price` 仍要传原价合计** —— 后端在临界区内按原价算折后额、落库 `order.Price`
 *           是**折后价**（回体里多 `CouponId` / `Discount` 两个键，判据是「有没有 `Discount`」）。
 *        ⚠️ `ActivityId` 是 **C3 块 7 · commit 2c 新加的入参**（秒杀页下单，2026-10-14）：
 *           秒杀活动串 `seckill_activity.ActivityId`。**不传 / 传空串 ⇒ 一次库读都不发**、
 *           秒杀段整段跳过 —— 与加它之前**逐字同行为**（后端 `required=false`、缺省 `""`）。
 *           传了它 ⇒ 后端在**订单锁临界区内**按活动 CAS 扣 `seckill_activity.SoldStock`；
 *           **扣不到就不建单**（回 `success:false` + `msg:"秒杀活动已售完"`）—— 这是本仓唯一一条
 *           **fail-closed** 的下单路径（与库存扣减「0 行不挡单」刻意相反）。
 *           ⚠️ 服务端**不猜**这单是不是秒杀单（订单装的是 `ProductCode`、活动挂的是 `ActivityId`，
 *           两者无推导关系）⇒ 只有秒杀页的调用点会传它。
 * @returns {Promise<{success:boolean, msg?:string, CouponId?:string, Discount?:number}>}
 *          ⚠️ 失败是 **HTTP 200 + `success:false`**（本仓口径），不是 4xx。
 */
export function orderAdd(payload) {
  return post(endpoints.order.orderAdd, build({
    CustomerId: payload.CustomerId, MerchantId: payload.MerchantId, OrderId: payload.OrderId,
    Price: payload.Price, ProductInformation: payload.ProductInformation, Images: payload.Images,
    PayIf: payload.PayIf, DeliveryIf: payload.DeliveryIf, ReceiveIf: payload.ReceiveIf,
    Quantity: payload.Quantity, Items: payload.Items, CouponId: payload.CouponId,
    ActivityId: payload.ActivityId
  }))
}

/**
 * 我买的单（**未成交**：`ReceiveIf != 1`）。「我的订单」tab 的第一路。
 * ⚠️ 已成交那半由 {@link orderHistory} 取 —— 后端是**两条 SQL**，没有「一次取全量」的端点。
 */
export function orderSelect(customerId) {
  return post(endpoints.order.orderSelect, build({ customerId }))
}

/** 我买的单（**已成交**：`ReceiveIf == 1`，`SettlementDate` 非空）。同上，两路拼成全量。 */
export function orderHistory(customerId) {
  return post(endpoints.order.historicalOrdersSelect, build({ customerId }))
}

/**
 * 付款（买家侧 CAS 边之一）。`orderId` 不存在 / 已付款 ⇒ `success:false`，不是异常。
 *
 * @param {string} orderId
 * @param {string} [paymentMethod] C3 块4 补丁新增：支付方式标签（收银台选的那一个）。
 *        ⚠️ **不传时这个键根本不发**（不是发空串）—— 后端 `paymentMethod` 是 `required=false`，
 *        「没发」与「发了空串」在库里是 `NULL` 与 `''` 两回事；不传等价于改前的逐字行为。
 * @returns {Promise<{success:boolean, msg?:string, tradeNo?:string}>}
 *        ⚠️ 成功时后端**生成并回传 `TradeNo`**（`PAY` + `yyyyMMdd` + 6 位随机）—— 收银台要把
 *           它显示给用户；这是「模拟支付」的全部凭据（没有真实网关，见 `tech-debt`）。
 */
export function orderPay(orderId, paymentMethod) {
  const pairs = { orderId }
  if (paymentMethod) pairs.paymentMethod = paymentMethod
  return post(endpoints.order.orderPayIfUp, build(pairs))
}

/** 收货（买家侧 CAS 边）。后端守卫：未付款 / 未发货 / 已收货一律拦。 */
export function orderReceive(orderId) {
  return post(endpoints.order.orderReceiveIfUp, build({ orderId }))
}

/**
 * 发货（商家侧 CAS 边）。
 *
 * @param {string} orderId
 * @param {string} [expressCompany] 快递公司（C3 块4 补丁）。⚠️ 与 `trackingNo` **同进同出**：
 *        两个都不传 = 改前那条单参调用（管理员代发走的就是它）；都传 = 商家在发货弹窗里填的。
 * @param {string} [trackingNo] 运单号。
 * @returns {Promise<{success:boolean, msg?:string}>}
 *        ⚠️ 成功时后端**只改状态位 + 写 3 个物流列，不写轨迹**（2026-10-10 起）：四节点（`in_transit` 运输中 →
 *           `picked_up` 驿站已揽收 → `out_for_delivery` → `delivered`）全由 `ShippingTrackAdvanceJob` 按 `ShippedAt` + 阈值补（⚠️ 2026-10-11 起前两步对调）。
 */
export function orderDeliver(orderId, expressCompany, trackingNo) {
  const pairs = { orderId }
  if (expressCompany) pairs.expressCompany = expressCompany
  if (trackingNo) pairs.trackingNo = trackingNo
  return post(endpoints.order.orderDeliveryIfUp, build(pairs))
}

/**
 * 商家视角的订单列表（**我卖的**）。
 *
 * @param {string} merchantId
 * @param {'active'|'completed'} status `'completed'` 走另一条端点 —— **两条 SQL 的分支在后端**
 *        （`OrderDao.selectByMerchant` 两个分支），前端这一层只挑端点，与改前逐字一致。
 */
export function orderMerchantList(merchantId, status) {
  const path = status === 'completed'
    ? endpoints.order.orderDeliverySelect
    : endpoints.order.orderDeliveryIfSelect
  return post(path, build({ merchantId, status }))
}

// ══════════════════════════════════════════════════════════════════════════════
// ② 购物车域 5 条（`/cart/*`）—— 块 4 新增（A1 甲）
//
// ⚠️ 后端 5 条**每条都判身份**（`IdentityGuard.requireSelfOrAdmin(request, CustomerId)`）
//    ⇒ 传别人的 `CustomerId` 是 **403**，不是「查不到」。
// ⚠️ 单价**不由前端传**：`PriceSnapshot` 由后端 `selectGoodsSnapshot` 现取
//    （`cart/PRODUCT_SNAPSHOT` 那条，防「1 元的电视」）⇒ 本层只有 `ProductCode` + `Quantity`。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 加入购物车。已在车里 ⇒ 后端**合并数量**（同键不新开行）。
 *
 * @param {{CustomerId:string, MerchantId:string, ProductCode:string, Quantity?:number}} params
 * @returns {Promise<{success:boolean, msg?:string}>}
 *          ⚠️ 数量闸在后端：`Quantity < 1` ⇒ 「数量必须大于 0」；`> 999` ⇒ 「数量最多 999 件」。
 */
export function cartAdd(params) {
  return post(endpoints.cart.add, q(params))
}

/** 改量（**绝对赋值**）。`Quantity < 1` 会被拒 —— 移除请走 {@link cartRemove}，两条语义不混。 */
export function cartUpdate(params) {
  return post(endpoints.cart.update, q(params))
}

/** 删一行。⚠️ 后端是**物理 DELETE** —— 全仓对「逻辑废弃」规则的唯一破例（理由见 `CartDao` 类注释）。 */
export function cartRemove(params) {
  return post(endpoints.cart.remove, q(params))
}

/**
 * 整车（展示用）。行里有 `GoodName` / `GoodImg` / `PriceNow`（后端 LEFT JOIN `goods`）。
 *
 * @returns {Promise<{success:boolean, list:Array, msg?:string}>}
 *          ⚠️ 商品被删 / 下架 ⇒ 该行 `GoodName` 为 `null`（**行还在**，不静默少一行）——
 *             前端要画「已下架」，别当空值跳过。
 *          ⚠️ `PriceSnapshot` 是**加购时的成交价**，`PriceNow` 是现价 —— **两个都画**，
 *             差值就是「购物车里 ¥10、点进去 ¥12」这件事的可见化。
 */
export function cartList(customerId) {
  return post(endpoints.cart.list, q({ CustomerId: customerId }))
}

/**
 * 结算 ⇒ **按商家分组，一个商家一笔订单**（后端走 `OrderService.orderAdd` 的锁与五道闸），
 * 一组落库成功才清这一组。
 *
 * ⚠️ 可视化段 commit 0b 改：**改前是「逐行下单」**（N 行 ⇒ N 张互相独立的订单）。
 * 现在 N 行 ⇒ **M 张**（M = 本车的商家数）。购物车可跨商家（`cart` 唯一键是
 * `(CustomerId, ProductCode)`），而 `order.MerchantId` 是单值 ⇒ 分组键只能是商家。
 * **单商家车（常态）才是「整车一单多行」。**
 *
 * @param {{CustomerId:string, ProductCode?:string, CouponId?:string, PointsUsed?:number}} params
 *        `ProductCode` 不传 ⇒ 结算**整车**。
 *        ⚠️ **C3 块 5 commit 5 新增 `CouponId`**（结算用券）：不传 ⇒ 与加它之前**逐字节同行为**。
 *        券的核销在 `OrderService.orderAdd` 的临界区内（CAS 抢不到就整单不建），
 *        且**只随「整车且单商家」那一笔走** —— 多商家整车带券会被 `CartService.checkout`
 *        直接拒（一张券只抵一笔订单）。空串会被上面的 `q()` 丢掉。
 *        ⚠️ **第三批㉘㉙（2026-10-19）新增 `PointsUsed`**：整数积分，空值由 `q()` 丢掉 ⇒
 *        不传时与加它之前逐字节同行为。后端 `CartService.checkout` 把它透传给
 *        `OrderService.orderAdd`，上限与扣减都在服务端（折后价 20%、100 积分 = 1 元）；
 *        与券同一条限制：**多商家整车带积分会被直接拒**。
 * @returns {Promise<{success:boolean, list:Array, msg?:string}>}
 *          ⚠️ 券真的核销掉时，回体里**多** `CouponId` / `Discount` 两个键（否则不出现）——
 *             判据就是「有没有 `Discount`」（`OrderController` 据此写 `coupon.redeem` 审计）。
 *          `list` 每项：`OrderId` / `MerchantId` / `ProductCode` / `Quantity` / `Price` / `Items`。
 *          ⚠️ **消费者只读 `OrderId`**（`ShoppingCart.vue`）⇒ 多出来的键是**超集、不算破坏**；
 *             单行组时那四个旧键与改前**逐字同值**，多行组时才是聚合值。
 *          ⚠️ **结算不是一笔原子操作**（订单链禁 `@Transactional`）⇒ 中途失败时
 *             `success:false` **且** `list` 里有**已经生成的那几笔**。前端**必须**把它们显示出来
 *             （把部分成功报成整体失败 = 让用户重复下单）。
 */
export function cartCheckout(params) {
  return post(endpoints.cart.checkout, q({
    CustomerId: params.CustomerId,
    ProductCode: params.ProductCode,
    CouponId: params.CouponId,
    PointsUsed: params.PointsUsed,
  }))
}

// ══════════════════════════════════════════════════════════════════════════════
// ③ 管理端订单治理 4 条（`/admin/order/*`）—— 块 4 新增
//
// ⚠️ 后端**无角色闸**（TD-18）⇒ 本层不许「顺手」加任何前端角色判据。
// ⚠️ 只有 `intervene` 写审计；另三条是读，不留痕。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 全量视图。
 *
 * @param {{CustomerId?:string, MerchantId?:string, status?:string, from?:string, to?:string, limit?:number}} params
 *        `status` 走**白名单**：`unpaid` / `paid` / `shipped` / `completed` / `abnormal` / `all`。
 * @returns {Promise<{success:boolean, list:Array, total:number, truncated:boolean, msg?:string}>}
 *          ⚠️ **未知 `status` ⇒ `success:false` + 文案**，不是空列表 —— 「筛错了一个词」与
 *             「这个状态确实没数据」在画面上都是空表，后端刻意把它们分开（见 `AdminOrderService`）。
 *          ⚠️ `total` 是**全表**总数（不是本次筛选数）；`total > list.length` ⇒ 已按 500 截断。
 */
export function orderAdminList(params) {
  return post(endpoints.orderAdmin.list, q(params))
}

/**
 * 单条详情：订单行 + 状态流转时间线 + 物流轨迹。
 *
 * @returns {Promise<{success:boolean, row:Object, timeline:Array, shippingTrack:Array, msg?:string}>}
 *          `timeline` 一行 = `{Action, FromState, ToState, OccurredAt}`，**已按时间升序**
 *          （排序键是 `OccurredAt` 再 `Id`，**不是 `Id`**）。
 *          `shippingTrack`（C3 块4 补丁新增）一行 = `{Status, Description, TrackedAt}`，同样按
 *          `TrackedAt` 升序。⚠️ **两条时间线别合并**：`timeline` 是**状态流转**（CAS 的副产品，
 *          `order_state_transition`），`shippingTrack` 是**模拟物流**（`order_shipping_track`）。
 *          末条 `shippingTrack.Status='delivered'`（物流「已签收」）**不等于** `row.ReceiveIf=1`
 *          （订单「收货」仍由买家点）—— 前端分两词表述，见 `tech-debt`。
 */
export function orderAdminDetail(orderId) {
  return post(endpoints.orderAdmin.detail, q({ OrderId: orderId }))
}

/**
 * 异常监控（**只读识别**）。
 *
 * @returns {Promise<{success:boolean, row:{unpaidTimeout:number, undeliveredTimeout:number,
 *                    abnormalState:number, unfinished:number, total:number},
 *                    unpaidHours:number, undeliveredHours:number}>}
 *          ⚠️ 两个阈值**随响应回传** ⇒ 页面别自己写死 24 / 48（它们目前是拍出来的数，待业务裁）。
 */
export function orderAdminMonitor() {
  return post(endpoints.orderAdmin.monitor, '')
}

/**
 * 介入：沿既有三条 CAS 边强制推进。
 *
 * @param {{OrderId:string, Action:'pay'|'deliver'|'receive'}} params
 * @returns {Promise<{success:boolean, msg?:string}>}
 *          ⚠️ 三种失败**文案不同**，别合并显示：
 *             · `Action='refund'` ⇒ 「强制退款本块未实现：order 表没有资金态…」——**知道但不做**；
 *             · 拼错的 `Action`   ⇒ 「未知的介入动作…」——**不认识**；
 *             · 守卫没命中（如推一个已完成的单） ⇒ 后端 CAS 的原话。
 *          ⚠️ 成功后后端会**通知买卖双方**（一个批次两次投递）；发货 / 收货另外还会各触发一次
 *             用户侧的「对端通知」⇒ 买家可能收到两条，**这是有意的**（登记在 `tech-debt`）。
 */
export function orderAdminIntervene(params) {
  return post(endpoints.orderAdmin.intervene, q(params))
}

// ══════════════════════════════════════════════════════════════════════════════
// ④ 买家订单详情 1 条（`/order/orderDetail`）—— **C3 块4 补丁**新增（不在块 4 原 9 条里）
//
// ⚠️ 为什么单列一节而不是塞进 ① 组：① 组是**搬家**（块 4 把这 8 条从 4 个 view 里收拢，
//    逐条与改前的调用点一一对应）；本条是**新端点**，语义不同，混进去会让「搬来的」与「新加的」
//    再也分不开。末尾追加也顺带让 ① 组及其后的行号一个都不漂。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 买家订单详情（A5）：订单行 + 物流轨迹时间线。
 *
 * @param {string} orderId
 * @returns {Promise<{success:boolean, row:Object, shippingTrack:Array, msg?:string}>}
 *          ⚠️ **买家侧**的详情**没有 `timeline`**（那是管理端 `/admin/order/detail` 才有的
 *             状态流转线）—— 买家看的是**物流**（`shippingTrack`），不是内部状态机轨迹。
 *          ⚠️ 归属：后端第一行 `isOwner(request, orderId, false)`（买家）⇒ 看别人的单是 **403**；
 *             单**不存在**放行、由后端出 `success:false` +「订单不存在」（不是 403）。
 *          ⚠️ `row` 含块4 补丁的 7 个明细列（`TradeNo` / `PaymentMethod` / `PaidAt` /
 *             `ExpressCompany` / `TrackingNo` / `ShippedAt` / `ReceivedAt`），老单这些列全 `null`
 *             ⇒ 前端画 `—`，别把 `null` 当成空字符串。
 */
export function orderDetail(orderId) {
  return post(endpoints.order.orderDetail, build({ orderId }))
}

// ══════════════════════════════════════════════════════════════════════════════
// ⑤ 售后状态机（批四第二批 commit 4）—— 用户 / 商家 / 平台三端
//
// ⚠️ 金额**不由前端传**：后端 `apply` 用 `order.Price` 快照（折后成交价），
//    前端只负责填原因。⚠️ `Approve` 必须显式 `'true'` / `'false'`：缺省 ⇒ 后端报缺参，
//    绝不默认任何一侧（尤其不能把「没点」当成「同意退款」）。
// ⚠️ 这里用 `q()`（丢空值）而不是 `build()`：售后端点没有「空串是合法值」的列，
//    且 `Reason` 为空本来就该由后端回「请填写售后原因」，不该发 `Reason=`。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 用户申请 / 重申售后。`RefundStatus` 0/2 且 `ReceiveIf=1` 才受理；次数上限 2。
 *
 * @param {{CustomerId:string, OrderId:string, Reason:string}} params
 * @returns {Promise<{success:boolean, msg:string, outcome?:string, refundStatus?:number, retryCount?:number}>}
 *          ⚠️ 失败是 **HTTP 200 + `success:false`**；文案区分「非本人 / 未收货 / 次数上限 / 已退款」。
 */
export function orderRefundApply(params) {
  return post(endpoints.refundB4.apply, q(params))
}

/** 我的售后单（`RefundStatus <> 0`，最近申请在前）。 */
export function orderRefundMy(customerId) {
  return post(endpoints.refundB4.my, q({ CustomerId: customerId }))
}

/** 商家售后队列。`status` 省略 ⇒ 全部有售后记录的单（1 = 待商家审核）。 */
export function orderRefundMerchantList(merchantId, status) {
  return post(endpoints.refundB4.merchantList, q({ MerchantId: merchantId, status }))
}

/**
 * 商家同意 / 拒绝。
 *
 * @param {{MerchantId:string, OrderId:string, Approve:boolean|'true'|'false', Note?:string}} params
 * @returns {Promise<{success:boolean, msg:string, outcome?:'refunded'|'rejected'|'escalated', refundAmount?:number, retryCount?:number}>}
 *          `outcome='escalated'` = 第二次拒绝，后端已自动转平台（`RefundStatus=3`）。
 */
export function orderRefundMerchantHandle(params) {
  return post(endpoints.refundB4.merchantHandle, q(params))
}

/** 平台售后队列（VIP 优先：后端按 `user.MemberLevel DESC` 排序）。 */
export function orderRefundAdminList(status) {
  return post(endpoints.refundB4.adminList, q({ status }))
}

/**
 * 平台裁决已升级的售后（`RefundStatus=3`）。
 *
 * @param {{OrderId:string, Approve:boolean|'true'|'false', Note?:string}} params
 * @returns {Promise<{success:boolean, msg:string, outcome?:'refunded'|'refused', refundAmount?:number}>}
 */
export function orderRefundAdminHandle(params) {
  return post(endpoints.refundB4.adminHandle, q(params))
}
