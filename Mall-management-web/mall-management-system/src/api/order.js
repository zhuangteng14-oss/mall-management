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
 *          ReceiveIf:number, Quantity?:number}} payload
 *        ⚠️ `Quantity` 是 **C3 块 4（A2 甲）新加的入参**，不传 = 1（后端 `defaultValue="1"`）。
 * @returns {Promise<{success:boolean, msg?:string}>}
 *          ⚠️ 失败是 **HTTP 200 + `success:false`**（本仓口径），不是 4xx。
 */
export function orderAdd(payload) {
  return post(endpoints.order.orderAdd, build({
    CustomerId: payload.CustomerId, MerchantId: payload.MerchantId, OrderId: payload.OrderId,
    Price: payload.Price, ProductInformation: payload.ProductInformation, Images: payload.Images,
    PayIf: payload.PayIf, DeliveryIf: payload.DeliveryIf, ReceiveIf: payload.ReceiveIf,
    Quantity: payload.Quantity
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

/** 付款（买家侧 CAS 边之一）。`orderId` 不存在 / 已付款 ⇒ `success:false`，不是异常。 */
export function orderPay(orderId) {
  return post(endpoints.order.orderPayIfUp, build({ orderId }))
}

/** 收货（买家侧 CAS 边）。后端守卫：未付款 / 未发货 / 已收货一律拦。 */
export function orderReceive(orderId) {
  return post(endpoints.order.orderReceiveIfUp, build({ orderId }))
}

/** 发货（商家侧 CAS 边）。 */
export function orderDeliver(orderId) {
  return post(endpoints.order.orderDeliveryIfUp, build({ orderId }))
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
 * 结算 ⇒ 逐行建订单（后端走 `OrderService.orderAdd` 的锁与五道闸），成功一行清一行。
 *
 * @param {{CustomerId:string, ProductCode?:string}} params
 *        `ProductCode` 不传 ⇒ 结算**整车**。
 * @returns {Promise<{success:boolean, list:Array, msg?:string}>}
 *          ⚠️ **结算不是一笔原子操作**（订单链禁 `@Transactional`）⇒ 中途失败时
 *             `success:false` **且** `list` 里有**已经生成的那几笔**。前端**必须**把它们显示出来
 *             （把部分成功报成整体失败 = 让用户重复下单）。
 */
export function cartCheckout(params) {
  return post(endpoints.cart.checkout, q(params))
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
 * 单条详情：订单行 + 状态流转时间线。
 *
 * @returns {Promise<{success:boolean, row:Object, timeline:Array, msg?:string}>}
 *          `timeline` 一行 = `{Action, FromState, ToState, OccurredAt}`，**已按时间升序**
 *          （排序键是 `OccurredAt` 再 `Id`，**不是 `Id`**）。
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
