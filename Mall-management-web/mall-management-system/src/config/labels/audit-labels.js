// src/config/labels/audit-labels.js —— **审计域**的中文标签层（C3 块 7 · commit 9 收口，2026-10-14）。
//
// ⚠️ 【它对应后端的哪两个取值域】两张表各自对着一个**有上界的**后端清单：
//    ① `AUDIT_ACTION_LABEL` ← 后端 `admin/AuditActions.java` 的 **45 条常量**（`admin_audit.Action` 列）。
//       那个类的类注释写明了它的存在理由：「写入方（各业务端挂钩点）与读出方（筛选、前端下拉）
//       各写一遍，抄错时**双方都不报错**，只会是一条也筛不出来的静默空集」——
//       本文件就是那个「读出方」，故**两条判据**：必须与常量表**逐条对齐**、**必须有兜底**。
//    ② `TARGET_TYPE_LABEL` ← `admin_audit.TargetType` 的**点号前段**（域），也就是 `Action` 的
//       前缀（`goods` / `comment_image` / `sensitive_word` …）。它不是独立的常量表，
//       而是从上面 45 条 tag 里**归纳**出来的 —— 故两表必须能互相解释：任一 `Action` 的
//       域段都应当能在 `TARGET_TYPE_LABEL` 里查到（`auditActionLabel` 的兜底会暴露不一致）。
//
// ⚠️ 【本文件最容易做错、也最不该做错的一条】**必须有兜底**。
//    后端**不校验** `Action` / `TargetType` 的取值（写入方各自传字面量，加了新动作只是加一条常量）
//    ⇒ 库里**一定**会出现这里没有的 tag。所以两个 `*Label()` 认不出时**原样回 code** ——
//    判据同 `notify-events.js`：**任何一行都不许渲染成空白**。空白会被读成「这条审计没有动作」，
//    而真相是「前端不认识它」。
//
// ⚠️ 【为什么用 `hasOwnProperty` 而不是 `TBL[code] || code`】
//    后者对 `'constructor'` / `'toString'` 这类**原型链上的键**会拿到一个**函数**（truthy）
//    ⇒ 直接把函数渲染进模板。理由与 `notify-events.js` 块首那条逐字相同，不重抄。

/**
 * `Action` → 中文（**45 条**，与后端 `AuditActions` 的常量**逐条对齐**）。
 *
 * ⚠️ 加一条 = 后端 `AuditActions` 加常量 + 本表加一行 + （若新域）`TARGET_TYPE_LABEL` 加一行。
 *    漏掉本文件只会让**新动作**显示成 tag 原文（兜底在，不崩），漏掉后端常量则写入方没得引
 *    —— 两个方向都不会静默。
 *
 * ⚠️ 三条 `goods_template.*`（`@Deprecated`）**保留映射**：存量 `admin_audit` 行里还有这些字面量，
 *    后端常量也**刻意保留不删**（删常量不删行，只会让「这条值还能是哪些」在代码里失去依据）
 *    ⇒ 前端同理：删了映射，那些老行会退回显示英文 tag。
 */
export const AUDIT_ACTION_LABEL = {
  // ── 批 A 样板 + 块 1 ──────────────────────────────────────────────
  'goods.update': '商品更新',
  'notification.send': '通知发送',
  // ── 块 2（用户块） ────────────────────────────────────────────────
  'user.ban': '封禁用户',
  'user.unban': '解封用户',
  'user.update': '用户资料更新',
  'merchant.apply.create': '提交入驻申请',
  'merchant.apply.audit': '审核入驻申请',
  'merchant.downgrade.schedule': '排定商家降级',
  'merchant.downgrade.apply': '执行商家降级',
  // ── 块 3（商品块） ────────────────────────────────────────────────
  'goods.apply.create': '商品提审',
  'goods.apply.audit': '审核商品提审',
  'goodstype.create': '新增类目',
  'goodstype.update': '修改类目',
  'goodstype.delete': '删除类目',
  'goods_template.create': '新增商品模板（旧表）',
  'goods_template.update': '修改商品模板（旧表）',
  'goods_template.delete': '删除商品模板（旧表）',
  'goods.violation.delist': '违规下架商品',
  'goods.violation.delete': '违规删除商品',
  'template.create': '新增模板',
  'template.update': '修改模板',
  'template.delete': '删除模板',
  // ── 块 4（订单块） ────────────────────────────────────────────────
  'order.intervene': '管理员介入订单',
  'order.deliver': '商家发货',
  'order.receive': '买家收货',
  // ── 块 5（营销块） ────────────────────────────────────────────────
  'coupon.create': '新建优惠券',
  'coupon.stop': '停用优惠券',
  'coupon.activate': '重新启用优惠券',
  'coupon.grant': '定向发放优惠券',
  'subsidy.rule.create': '新建国补规则',
  'subsidy.rule.stop': '停用国补规则',
  'subsidy.rule.activate': '重新启用国补规则',
  'subsidy.nominate': '提名商品参加国补',
  'seckill.invite': '邀请商家上秒杀',
  'seckill.review': '审核秒杀申请',
  'seckill.stop': '停用秒杀活动',
  'coupon.redeem': '下单核销优惠券',
  // ── 块 6（内容块） ────────────────────────────────────────────────
  'comment.image.approve': '通过评论图',
  'comment.image.reject': '驳回评论图（删文件）',
  'sensitive_word.add': '新增敏感词',
  'sensitive_word.delete': '删除敏感词',
  'report.handle': '处理举报',
  // ── 块 7（跨域补挂 2 条 + 清理 1 条） ──────────────────────────────
  'goods.delete': '商家删除商品',
  'comment.delete': '删除评论',
  'observe.attack.cleanup': '清理演练数据',
}

/**
 * `TargetType`（= `Action` 的点号前段 = 被改对象的**域**）→ 中文。
 *
 * ⚠️ 它与 `AUDIT_ACTION_LABEL` 是**同一批字符串的两个切法**，不是两套数据：
 *    域段一律是**表名**（`comment_image` / `sensitive_word` 是表名，不是「评论图 / 敏感词」的拼音）
 *    —— 那正是后端类注释里「tag 前段必须与调用点传的 TargetType 字面量一致」那条。
 *    本表把表名翻成人话；`targetTypeLabel()` 的兜底保证「表名没登记」时仍显示原文。
 */
export const TARGET_TYPE_LABEL = {
  goods: '商品',
  goodstype: '商品类目',
  goods_template: '商品模板（旧表）',
  template: '模板',
  user: '用户',
  merchant: '商家',
  order: '订单',
  comment: '评论',
  comment_image: '评论图',
  sensitive_word: '敏感词',
  report: '举报单',
  coupon: '优惠券',
  subsidy: '国补',
  seckill: '秒杀',
  notification: '通知',
  attack: '攻击演练',
}

// ⚠️ 【角色码的那张表**刻意不在这个文件里**】`admin_audit.ActorRole` 与 `login.Role` 是同一件事
//    （1 客户 / 2 商家 / 3 管理员），表只能有一份 ⇒ 放在 `labels/user-labels.js` 的 `ROLE_LABEL`。
//    审计页要用就 `import { roleLabel } from './user-labels'` —— 别在这里复制第二份。

/** 取动作中文（认不出回落 tag 原文）。⚠️ `hasOwnProperty` 的理由见文件头。 */
export function auditActionLabel(tag) {
  return Object.prototype.hasOwnProperty.call(AUDIT_ACTION_LABEL, tag)
    ? AUDIT_ACTION_LABEL[tag]
    : String(tag == null ? '' : tag)
}

/** 取对象类型中文（认不出回落原文）。 */
export function targetTypeLabel(type) {
  return Object.prototype.hasOwnProperty.call(TARGET_TYPE_LABEL, type)
    ? TARGET_TYPE_LABEL[type]
    : String(type == null ? '' : type)
}

