// src/config/labels/order-labels.js —— **订单域**的中文标签层（C3 块 7 · commit 9，2026-10-14）。
//
// ⚠️ 【三张表各对后端**一个**枚举，一一对齐，别互相替代】
//    ① `ORDER_ACTION_LABEL`    ← `order/model/OrderAction.java` 的 4 个 tag
//       （`create`/`pay`/`deliver`/`receive`）—— 时间线里「这一步**做了什么**」。
//    ② `ORDER_STATE_LABEL`     ← `order/model/OrderState.java` 的 **8 个枚举名**（5 合法 + 3 非法）
//       ＋ `OrderStateMachine.UNCLASSIFIED` —— 时间线里 `FromState`/`ToState` 的取值，
//       也就是「**从哪到哪**」。①与②**正交**（后端 `OrderAction` 的类注释原话），故两张表缺一不可。
//    ③ `SHIPPING_STATUS_LABEL` ← `order/dao/ShippingTrackDao.java` 的 4 个常量 —— **模拟物流**的节点
//       （不是订单状态：本仓没有真实网关，这四行是后台任务按 `ShippedAt` 偏移补出来的）。
//
// ⚠️ 【②与「状态流转页那五态」**不是一套**】那五态（`waitPay`/`waitShip`/…）是后端
//    `OrderStreamService` 为那一页**另起的** key，表在 `labels/observe-labels.js` 的
//    `ORDER_FLOW_STATE_LABEL`。📌 别拿这一张去喂那一页：`CREATED` 与 `waitPay` 指的是同一件事，
//    但**是两套坐标**，混用会让「计数卡写着待付款、时间线写着 CREATED」同时出现在一屏上。
//
// ⚠️ 【三个非法态**必须画得出来**】`ILLEGAL_*` 是 `orderAdd` 不校验状态位时直插造的「出生即非法」行。
//    那个入口 **C#6（2026-10-04）已关闭**，但**存量脏数据可能已是这些态**，后端常量一个都没删
//    ⇒ 前端也不能把它们藏起来：认不出就回落枚举原文，正好是一个看得见的
//    `ILLEGAL_DELIVERED_UNPAID` —— 那正是观测路径想看见的东西。
//
// ⚠️ 兜底口径同各表：认不出回 code 原文，**永不画空白**。

/** 动作 tag → 中文（`OrderAction` 的 4 个，与后端 `tag()` 逐字对齐）。 */
export const ORDER_ACTION_LABEL = {
  create: '创建订单',
  pay: '付款',
  deliver: '发货',
  receive: '收货',
}

/**
 * 状态名 → 中文。键是 `OrderState` 的**枚举名**（后端 `OrderStateMachine.stateName(...)` 的返回值，
 * 前端拿到的是这个字符串），不是三位状态位。
 *
 * ⚠️ 括号里的三位组是 `(PayIf, DeliveryIf, ReceiveIf)` —— 写进标签里是**故意的**：
 *    `RECEIVED_NODELIVERY` 看着像脏数据，其实它是**合法态**（后端类注释整节在解释这一条），
 *    「未发货」三个字能让人少去后端翻一次。
 */
export const ORDER_STATE_LABEL = {
  CREATED: '待付款',
  PAID: '已付款（待发货）',
  SHIPPED: '已发货（待收货）',
  RECEIVED_NODELIVERY: '已收货（未发货）',
  COMPLETED: '已完成',
  ILLEGAL_DELIVERED_UNPAID: '非法态·未付款已发货',
  ILLEGAL_RECEIVED_UNPAID: '非法态·未付款已收货',
  ILLEGAL_DELIVERED_RECEIVED_UNPAID: '非法态·未付款已发货已收货',
  /** 后端 `OrderStateMachine.UNCLASSIFIED`：状态位越界（伪造值），永不抛而是回这个哨兵。 */
  UNCLASSIFIED: '无法归类',
}

/** 物流节点 → 中文（`ShippingTrackDao` 的 4 个常量，顺序即时间顺序）。 */
export const SHIPPING_STATUS_LABEL = {
  picked_up: '驿站已揽收',
  in_transit: '运输中',
  out_for_delivery: '驿站派送中',
  delivered: '已签收',
}

/** 取动作中文（认不出回落 tag 原文）。 */
export function orderActionLabel(tag) {
  return Object.prototype.hasOwnProperty.call(ORDER_ACTION_LABEL, tag)
    ? ORDER_ACTION_LABEL[tag]
    : String(tag == null ? '' : tag)
}

/** 取状态中文（认不出回落枚举原文 —— 非法态正是靠这条兜底才画得出来）。 */
export function orderStateLabel(state) {
  return Object.prototype.hasOwnProperty.call(ORDER_STATE_LABEL, state)
    ? ORDER_STATE_LABEL[state]
    : String(state == null ? '' : state)
}

/** 取物流节点中文（认不出回落原文）。 */
export function shippingStatusLabel(status) {
  return Object.prototype.hasOwnProperty.call(SHIPPING_STATUS_LABEL, status)
    ? SHIPPING_STATUS_LABEL[status]
    : String(status == null ? '' : status)
}
