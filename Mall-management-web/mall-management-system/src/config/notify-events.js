// src/config/notify-events.js —— 系统通知**事件类型 code → 中文标签**（C3 块 1 · I2，2026-10-09）。
//
// 它对应后端的 `notification/NotificationEvents.java`（31 条常量 —— 2026-10-11 块 5 加了 `coupon.expiring.unclaimed`；
// 2026-10-12 commit 3 加国补 3 条、commit 4 加秒杀 2 条（`seckill.responded` / `seckill.application.submitted`）；
// ⚠️ 本注释此前写的「25 条」与后端**当时**的 25 条常量其实是对齐的，只落后端的类注释（那句写 21）是陈旧的，已一并校正）。**两张表必须逐字对齐** ——
// 后端那个类的类注释写明了「写入方与读出方各写一遍，第二处抄错双方都不报错」，本文件就是那个「读出方」。
//
// ⚠️ 【本文件最容易做错、也最不该做错的一条】**必须有兜底**。
//    后端 `NotificationService.send` **不校验** `eventType`（那是「加类型不改表、不改后端」的兑现点）
//    ⇒ 库里**一定**会出现这里没有的 code（实测过：发 `weird.unknown.code` 照样落库）。
//    所以 `eventLabel()` 认不出时**原样回 code**，调用方再把「它是不是已知 code」单独画出来
//    （`NotifyInbox.vue` 用 `isKnownEvent()` 决定要不要加一枚小号等宽 chip）。
//    ⇒ 判据：**任何一行都不许渲染成空白**。空白会被读成「这条通知没内容」，而真相是「前端不认识它」。
//
// ⚠️ 【为什么用 `hasOwnProperty` 而不是 `EVENT_LABELS[code] || code`】
//    后者对 `'constructor'` / `'toString'` / `'valueOf'` 这类**原型链上的键**会拿到一个**函数**
//    （truthy）⇒ 直接把函数渲染进模板。这不是学术问题：code 由后端透传、后端不校验，
//    所以「一个恰好叫 `constructor` 的未知 code」是**可达**的。故判据必须是「**自有**属性」。

/**
 * code → 中文标签。**33 条**，顺序与后端 `NotificationEvents.java` 的「归属块」分组一致
 * （块 5 收口时 31 条，块 6 commit 2 问答补 `comment.accepted` / `question.timeout` 两条到 33）。
 *
 * ⚠️ 加一条 = 后端 `NotificationEvents` 加常量 + 本表加一行 + `EVENT_OPTIONS` 加一项（**三处**）。
 *    漏掉本文件只会让**新类型**显示成 code 原文（兜底在，不崩），漏掉后端常量则写入方没得引 —— 不会静默。
 */
export const EVENT_LABELS = {
  // ── 块 1（本块）：平台自身 ──────────────────────────────────────────────
  'platform.announcement': '平台公告',
  'system.notice': '系统通知',

  // ── 块 6：评论区三类事件（§9.2 硬约束）+ 举报结果 ────────────────────────
  'comment.on.post': '商品被评论',
  'comment.on.user': '留言板被评论',
  'comment.reply': '评论被回复',
  'comment.mention': '被 @ 提及',
  'report.handled': '举报已处理',

  // ── 块 2：用户与商家治理 ────────────────────────────────────────────────
  // ⚠️ 上面 6 条**建块 1 时就登记了**（那张表是「清单要覆盖到后续所有块」，不是「本块会发」）；
  //    下面 3 条是块 2 真正**开始发**的（2026-10-09）—— 判据记在此处，免得日后误以为前 6 条没实现。
  'user.audit.passed': '用户审核通过',
  'user.audit.rejected': '用户审核驳回',
  'user.banned': '账号被封禁',
  'user.unbanned': '账号已解封',
  'merchant.audit.passed': '商家入驻通过',
  'merchant.audit.rejected': '商家入驻驳回',
  // ⚠️ 收件人不是申请人本人：`merchant.apply.submitted` 走的是**管理端的共享收件箱**
  //    （`ToType='admin'`、`ToId='*'`，见 `MerchantApplicationController.ADMIN_INBOX_ID`）
  //    —— 它是三条里唯一**发往管理端**的；另两条发给商家本人。
  'merchant.apply.submitted': '商家入驻申请已提交',
  'merchant.downgrade.scheduled': '商家降级已排定',
  'merchant.downgrade.applied': '商家降级已生效',

  // ── 块 3：商品与类目治理 ────────────────────────────────────────────────
  'goods.audit.passed': '商品审核通过',
  'goods.audit.rejected': '商品审核驳回',
  'goods.violation.removed': '违规商品下架',
  // ⚠️ 2026-10-10（C3 块 3 落地）：商家**提审**时发的一条。与块 2 的 `merchant.apply.submitted`
  //    同款 —— 收件人**不是商家本人**，而是**管理端的共享收件箱**（`ToType='admin'` / `ToId='*'`，
  //    见 `GoodsController.ADMIN_INBOX_ID`）⇒ 「待办来了」这一侧也归它。
  'goods.apply.submitted': '商品提审已提交',

  // ── 块 4：订单治理 ──────────────────────────────────────────────────────
  'order.intervened': '订单被介入',
  'order.notice': '订单通知',

  // ── 块 5：秒杀双向制（§9.1）────────────────────────────────────────────
  'seckill.invited': '秒杀邀请',
  'seckill.application.approved': '秒杀申请通过',
  'seckill.application.rejected': '秒杀申请驳回',
  // ⚠️ 2026-10-11（C3 块 5 · commit 1 券）：**只发「未领」那一侧** —— 已领的券快过期
  //    **不发系统通知**（走券包红点 + 排序），见 `CouponExpiryJob` 的类注释。
  'coupon.expiring.unclaimed': '优惠券即将过期（未领取）',
  // ⚠️ 2026-10-12（C3 块 5 · commit 3 国补）：双向制的**两个方向各一条**。
  //    方向 1 = 平台 → 商家（`ToType='merchant'`），商家在消息里同意 / 拒绝；
  //    方向 2 = 商家 → **管理端共享收件箱**（`ToType='admin'` / `ToId='*'`）的回执，
  //    同意与拒绝**分开两个 code**（一封要人跟进、一封只是回执，长得一样就没法处理）。
  'subsidy.nominated': '国补邀请',
  'subsidy.confirmed': '商家确认国补',
  'subsidy.rejected': '商家拒绝国补',
  // ⚠️ 2026-10-12（C3 块 5 · commit 4 秒杀）：**商家 → 管理端共享收件箱**的两条
  //    （上面 `seckill.invited` / `seckill.application.approved|rejected` 是反方向：管理端 → 商家）。
  //    ⚠️ 应答**只有一条 code**（`seckill.responded`，同意 / 拒绝写在标题里）——
  //    与国补那对（`subsidy.confirmed` / `subsidy.rejected`）不同：邀请这条路径上管理端
  //    本来就知道自己邀请了谁，回执只需「答没答、答的什么」。判据写在 `NotificationEvents` 该类注释里。
  'seckill.responded': '商家应答秒杀邀请',
  'seckill.application.submitted': '秒杀申请已提交（发往管理端）',

  // ── 块 6：问答（commit 2，2026-10-12）────────────────────────────────────
  // ⚠️ 上面那一节「评论区三类事件 + 举报结果」5 条是**建块 1 时就登记的预留**；
  //    下面这 2 条是**问答**这一笔才第一次有人发的（`CommentService.accept` / `QuestionTimeoutJob`）。
  //    ⚠️ 收件人各异，别照抄：`comment.accepted` 发给**回答者**本人（ToType 随其角色 customer/merchant）；
  //    `question.timeout` 发给**管理端共享收件箱**（ToType='admin' / ToId='*'）。
  'comment.accepted': '回答被采纳',
  'question.timeout': '提问超时未解决（发往管理端）',
}

/**
 * 下拉用的**有序**选项表（管理端「系统通知」页的事件类型那一格）。
 * 单独列一份而不是 `Object.entries(EVENT_LABELS)`：对象键序是「插入序」这件事实**不该被依赖**
 * （一张下拉的次序是**呈现**决定，不是数据结构的副产品）。
 */
export const EVENT_OPTIONS = [
  { code: 'platform.announcement', label: '平台公告（全站）' },
  { code: 'system.notice', label: '系统通知（群发 / 定向）' },
  { code: 'user.audit.passed', label: '用户审核通过' },
  { code: 'user.audit.rejected', label: '用户审核驳回' },
  { code: 'user.banned', label: '账号被封禁' },
  { code: 'user.unbanned', label: '账号已解封' },
  { code: 'merchant.audit.passed', label: '商家入驻通过' },
  { code: 'merchant.audit.rejected', label: '商家入驻驳回' },
  { code: 'merchant.apply.submitted', label: '商家入驻申请已提交（发往管理端）' },
  { code: 'merchant.downgrade.scheduled', label: '商家降级已排定' },
  { code: 'merchant.downgrade.applied', label: '商家降级已生效' },
  { code: 'goods.audit.passed', label: '商品审核通过' },
  { code: 'goods.audit.rejected', label: '商品审核驳回' },
  { code: 'goods.violation.removed', label: '违规商品下架' },
  { code: 'goods.apply.submitted', label: '商品提审已提交（发往管理端）' },
  { code: 'order.intervened', label: '订单被介入' },
  { code: 'order.notice', label: '订单通知' },
  { code: 'seckill.invited', label: '秒杀邀请' },
  { code: 'seckill.application.approved', label: '秒杀申请通过' },
  { code: 'seckill.application.rejected', label: '秒杀申请驳回' },
  { code: 'coupon.expiring.unclaimed', label: '优惠券即将过期（未领取）' },
  { code: 'subsidy.nominated', label: '国补邀请（发往商家）' },
  { code: 'subsidy.confirmed', label: '商家确认国补（发往管理端）' },
  { code: 'subsidy.rejected', label: '商家拒绝国补（发往管理端）' },
  { code: 'seckill.responded', label: '商家应答秒杀邀请（发往管理端）' },
  { code: 'seckill.application.submitted', label: '秒杀申请已提交（发往管理端）' },
  { code: 'comment.on.post', label: '商品被评论' },
  { code: 'comment.on.user', label: '留言板被评论' },
  { code: 'comment.reply', label: '评论被回复' },
  { code: 'comment.mention', label: '被 @ 提及' },
  { code: 'report.handled', label: '举报已处理' },
  { code: 'comment.accepted', label: '回答被采纳' },
  { code: 'question.timeout', label: '提问超时未解决（发往管理端）' },
]

/**
 * code → 展示用文字。**认不出就原样回 code**（见文件头那条：不许空白）。
 *
 * @param {string} code 后端 `EventType` 列的值
 * @returns {string} 中文标签；未知 code 回**它自己**
 */
export function eventLabel(code) {
  const key = String(code == null ? '' : code)
  return hasOwnEvent(key) ? EVENT_LABELS[key] : key
}

/**
 * 是不是本表认得的 code —— 调用方据此决定「要不要补一枚 `code` 原文的小 chip」。
 * ⚠️ 空串算**不认得**（`hasOwnEvent('')` 为假）—— 那正好：`EventType` 是 `NOT NULL`，
 * 真拿到空串说明上游有问题，显示成裸 chip 比显示成空白更容易被发现。
 */
export function isKnownEvent(code) {
  return hasOwnEvent(String(code == null ? '' : code))
}

function hasOwnEvent(key) {
  return Object.prototype.hasOwnProperty.call(EVENT_LABELS, key)
}
