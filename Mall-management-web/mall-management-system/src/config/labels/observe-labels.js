// src/config/labels/observe-labels.js —— 可观测域的**中文标签层**（commit 8 起头，commit 9 收口）。
//
// ⚠️ 【commit 8 只**起个头**，commit 9 才收敛完】commit 8 的批文只要求「至少把 `EventLog.vue`
//    用到的键先登记」；commit 9 批文把 `MetricsPanel` / `OrderFlow` / `AttackConsole` 里那几份
//    硬编码中文（指标名、五态名、场景名）一并搬进来 ⇒ **本文件现已是可观测域唯一的中文来源**。
//    判据：那四页里不该再出现「裸露的中文标签字面量表」—— 表只能有一份，就在本文件。
//    ⚠️ 收敛搬的是**表**，不是数据：页面渲染的仍是后端帧里那个 tag，本文件只把 tag 翻成人话。
//
// ⚠️ 【为什么落在 `src/config/labels/`】与 `notify-events.js` / `goods-flags.js` 同域：本仓把
//    「**code → 中文**」这类表放 `src/config/` 下（而非 `views/` 里），因为它是**多个页共用的**只读表
//    —— 跨 `views/*` 子目录引 `views/admin/observe/x.js` 会读成「某个页的私有件」。commit 9 立起
//    `labels/` 子目录：审计 / 订单 / 举报 / 用户各有自己的表，本文件管**可观测域**。与
//    `notify-events.js` 一样，**每张表都必须有兜底**：新增一类事件 / 一个新场景时，后端常量表
//    与这里**各自独立改**，漏跟的一方只会让页面显示一个看得见的英文 tag —— 而不是一条空白。

/**
 * 9 类事件的**声明序**（tag）。⚠️ 与后端 `EventBroadcaster.Kind` 的枚举声明序**逐字对齐**
 * （`cache → lock → order → stock → audit → notify → sensitive → identity → protection`）。
 *
 * ⚠️ 顺序不是审美：后端 `EventStreamService.frame()` 的 `counts` 是 `LinkedHashMap`，按这个序
 * 装进帧；页面的图例、卡片配色、抽屉里的链路排序全都吃这个序 ⇒ 两处一旦不一致，
 * 「图例第 3 个」与「卡片第 3 色」就会指向不同的类，而且**不会报错**。
 *
 * ⚠️ 第 9 类 `protection`（防护）是 D 段 · commit 2 新增，**追加在末尾**：既有的八条序一字未动
 * （后端 `Kind` 的声明序同样把 `PROTECTION` 放在最后，两处都动才叫「逐字对齐」）。
 */
export const KINDS = ['cache', 'lock', 'order', 'stock', 'audit', 'notify', 'sensitive', 'identity', 'protection']

/**
 * tag → 中文名。**取值逐字抄自后端** `EventBroadcaster.Kind` 的 `label` 字段
 * （后端那两列是「页面图例上的中文名」的**唯一出处**，前端这份是它的镜像）。
 *
 * ⚠️ 认不出时回落成 **tag 原文**（同 `notify-events.js` 的兜底口径）：后端加了第 10 类而这里没跟，
 *    页面应当显示一个看得见的英文 `weird`，**不是空白** —— 空白会被读成「这条事件没分类」，
 *    而真相是「前端不认识它」。
 */
export const KIND_LABEL = {
  cache: '缓存',
  lock: '锁',
  order: '订单',
  stock: '库存',
  audit: '审计',
  notify: '通知',
  sensitive: '敏感词',
  identity: '身份',
  protection: '防护',
}

/**
 * tag → 色（**CSS 变量引用**，不是色值）。
 *
 * ⚠️ 九类落 `--c1`–`--c9`，`KINDS` 序一一对应。写 `var(--cN)` 而不是 `#hex`：本仓两套主题
 *   （浅色 `:root` / 管理端 `html[data-theme="admin"]`）下这些名字**同名不同值**
 *   （`--c3` 浅色 `#00a64a` / 管理端 `#22c55e`）—— 硬编码色值会在另一套主题上错，
 *   而且错得不明显（都是绿）。见 `assets/tokens.css:200` / `:300` 两行。
 *   ⚠️ `--c9` 与 `--c10` **不是本文件新造的**：色板早就到 `--c10`（可视化段 commit 1 的图表十色）。
 *   第 9 类只是**第一次有事件环用到 `--c9`** ⇒ 防护环的类色与图表第 9 色**同一个值**（`#ec4899`）。
 *   这是取用既有色板、不新造色的取舍 —— 同页同时出现折线与卡片时，两处的粉指向不同东西。
 *
 * ⚠️ 与**严重度**色（`--info` / `--warning` / `--danger`）是**两套轴**：类色说「这是哪个环」，
 *   严重度色说「它好不好」。卡片上类色走左侧色条 + 图标，严重度色走角标 —— 不混用。
 */
export const KIND_COLOR = {
  cache: 'var(--c1)',
  lock: 'var(--c2)',
  order: 'var(--c3)',
  stock: 'var(--c4)',
  audit: 'var(--c5)',
  notify: 'var(--c6)',
  sensitive: 'var(--c7)',
  identity: 'var(--c8)',
  protection: 'var(--c9)',
}

/**
 * tag → 图标（**内联 SVG 的 `<path d>` 数组**，`viewBox="0 0 16 16"`，描边、不填充）。
 *
 * ⚠️ 【为什么不是 `components/ui/glyphs.js` 里的字体字形】这条路**实测走不通**，不是偏好：
 *    本仓 `glyphs.js` 那 19 枚是**导航图标**层（与丁稿侧栏一一对应，且有一条「两两不同」的
 *    不变量），而 8 类事件图标是**另一套语义**。判据有**三条**：字体真覆盖 ∧ 未被那 19 枚占用
 *    ∧ **语义真表示该物**。实测（`scripts/probes/b7c8-kind-icons.mjs`，2026-10-14）：
 *      · 前两条看着很松 —— 8 类**都能**找到「覆盖 ∧ 未占用」的字形；
 *      · **加上第三条就只剩 1 类**：`sensitive` 有 `⛔`（U+26D4），其余 7 类的窄池**一个都没有**
 *        （`lock` 那一池里通过前两条的是 `⌘ ⚔ ⚜ ⛔ ∅` —— 五枚里没有一枚表示「锁」；
 *        真正的 `⚿ ⛓ ⛨ ⚷ 🔑 🔒` 全部**字体不覆盖**）。
 *    ⇒ 「前两条通过」是**假阳性**，这正是初版探针报「8 类有 8 类可用」的原因（那份结论已作废）。
 *    ⇒ 硬凑只能拿「形状像」的几何符当「意思对」（`██▀▄` 表示订单、`░▒▓` 表示库存），
 *      或复用别处的字形（同一页里 `▩` 既指「订单管理」又指「订单环」）—— 都是「图标不再指向唯一一件事」。
 *    ⇒ 内联 SVG：零字体依赖（不怕 `noto.css` 覆盖不到的降级）、吃 `currentColor`、
 *      语义自己定。**同一手法见 `OrderFlow.vue` 的状态机图**（手写 SVG 而不为一张 5 节点图引
 *      ECharts 的 `graph` 系列）。
 *    ⚠️ 唯一的例外是 `sensitive`（`⛔` 三条都过）—— **仍然走 SVG**：8 枚要**同一套笔法**
 *      （同一个 `viewBox`、同一条描边宽、同一种端点），混一枚字体字形会在页面上露馅
 *      （字形的粗细与基线由字体决定，改不动）。这条是**取舍**，不是「没查到」。
 *
 * ⚠️ 认不出 ⇒ 落 `FALLBACK_ICON`（一枚空心方框），**不是空白**：理由同 `KIND_LABEL` 的兜底。
 */
export const KIND_ICON = {
  // 两级缓存：两条叠层
  cache: ['M3.2 4.4h9.6v2.2H3.2z', 'M3.2 9.4h9.6v2.2H3.2z'],
  // 锁：锁体 + 锁梁
  lock: ['M4.2 7.4h7.6v5.2H4.2z', 'M6 7.4V5.6a2 2 0 0 1 4 0v1.8'],
  // 订单：折角单据 + 两行明细
  order: ['M4.2 2.6h5.2L12 5.2v8.2H4.2z', 'M9.4 2.6v2.6h2.6', 'M6.2 8.6h3.6', 'M6.2 10.8h3.6'],
  // 库存：箱体（顶盖 + 竖棱）
  stock: ['M2.6 5.4 8 2.8l5.4 2.6v5.2L8 13.2l-5.4-2.6z', 'M2.6 5.4 8 8l5.4-2.6', 'M8 8v5.2'],
  // 审计：天平（横梁 + 两端吊盘 + 底座）
  audit: [
    'M8 3v9.2',
    'M2.8 5h10.4',
    'M2.8 5v2.2',
    'M13.2 5v2.2',
    'M1.4 7.2h2.8L2.8 9.6z',
    'M11.8 7.2h2.8L13.2 9.6z',
    'M5.2 12.2h5.6',
  ],
  // 通知：信封（封套 + 封盖）
  notify: ['M3 4.6h10v6.8H3z', 'M3 4.6l5 3.6 5-3.6'],
  // 敏感词：眼睛 + 斜杠（「看见了，但被拦下」）
  sensitive: [
    'M2.4 8c1.6-2.1 3.4-3.2 5.6-3.2s4 1.1 5.6 3.2c-1.6 2.1-3.4 3.2-5.6 3.2S4 10.1 2.4 8z',
    'M6.9 8a1.1 1.1 0 1 0 2.2 0 1.1 1.1 0 1 0-2.2 0z',
    'M3.4 13.2 12.6 2.8',
  ],
  // 身份：人形（头 + 肩）
  identity: [
    'M8 4.2a2.1 2.1 0 1 0 0 4.2 2.1 2.1 0 0 0 0-4.2z',
    'M3.8 13.4c0-2.3 1.9-3.6 4.2-3.6s4.2 1.3 4.2 3.6',
  ],
  // 防护：盾（外轮廓）+ 中间一竖（闸闩）—— D 段第 9 类
  protection: [
    'M8 2.4 12.9 4.3v3.7c0 2.7-1.9 4.8-4.9 5.6-3-.8-4.9-2.9-4.9-5.6V4.3z',
    'M8 5.6v6.6',
  ],
}

/** 认不出的类用的图标：一枚空心方框（与 `KIND_LABEL` / `KIND_COLOR` 的兜底同一理由）。 */
export const FALLBACK_ICON = ['M3.4 3.4h9.2v9.2H3.4z']
/** 认不出的类用的颜色：中性灰（`--ink-3`），**不是**八色里的某一个 —— 免得冒充。 */
export const FALLBACK_COLOR = 'var(--ink-3)'

/**
 * 严重度三档（tag → 中文 + 色）。
 *
 * ⚠️ 规则表**唯一的出处是后端**（`EventBroadcaster.severityOf`，类注释第 2 件）：
 *    `error` ← `result=error`；`warn` ← 被挡/被拒/超时/降级/不足；其余 `info`。
 *    前端**不重推一遍**这条判据（两处判据迟早漂），只负责把帧里那个 tag 画成人话。
 */
export const SEVERITY_LABEL = { info: '信息', warn: '警告', error: '错误' }
export const SEVERITY_COLOR = { info: 'var(--info)', warn: 'var(--warning)', error: 'var(--danger)' }
export const SEVERITIES = ['info', 'warn', 'error']

/**
 * tag → **对象列**该叫什么（实时日志页那张卡片上「这事件动的是谁」那一格的说明词）。
 *
 * ⚠️ 九类的 `objectId` **来源各不相同**（逐环口径见后端 `EventBroadcaster` 里九处 `add(...)`）：
 *    `cache`/`lock`/`notify` 取各自的事件键，`order` 取订单号，`stock` 取 SKU，
 *    `audit` 取 `TargetType` + `TargetId`（**块 7 · commit 8 新补的两列**），
 *    `sensitive` 取**命中的词**（同上新补，「以前只记命中几个」），
 *    `identity` 取**被校验的请求路径**（同上新补），
 *    `protection` 取**防护开关 key**（D 段 · commit 2 新补：限流 / 熔断 / 分池）。
 *    ⇒ 同一格在这九类里指的**不是同一种东西**，所以说明词必须逐类给，不能统一写「对象」。
 *
 * ⚠️ 这一格的值**照原文上屏**（中文词、请求路径、业务号…），**不做翻译** —— 唯一的例外是
 *    `objectType`：它是 `AuditActions` 的 **45 条** tag 的域前缀（`goods` / `comment_image` …），
 *    commit 9 起由 `labels/audit-labels.js` 的 `targetTypeLabel` 翻成中文（见其 `TARGET_TYPE_LABEL`）。
 */
export const OBJECT_FIELD_LABEL = {
  cache: '键',
  lock: '锁键',
  order: '订单',
  stock: 'SKU',
  audit: '对象',
  notify: '收件人',
  sensitive: '命中的词',
  identity: '请求路径',
  // ⚠️ 写「防护开关」而不是「对象」：这一列在防护环里装的是**哪道闸动了**（rateLimit / circuitBreak /
  //    poolIsolation），不是「谁被拦了」—— 后者在防护环里根本不存在（事件不带业务键）。
  protection: '防护开关',
}

/** 认不出 ⇒ 用最中性的那个词（同各表的兜底口径）。 */
export const FALLBACK_FIELD_LABEL = '对象'

/**
 * 事件帧 **`fields` 里的键名** → 中文（3a.3 收尾批 2，用户 2026-10-17 裁定「第一层要全 fields」）。
 *
 * ⚠️ 【键集**逐字来自真帧**，不是猜的】唯一出处是后端 `EventBroadcaster` 九个 `from…()` 方法里
 *    那串 `fields.put(...)`（cache / lock / order / stock / audit / notify / sensitive /
 *    identity / protection）。3a.3 之前前端只把 `detail` 当人读串，没登记过键名 ——
 *    本轮逐环读了一遍源码才落这张表。用户明令：**真帧里没有的键不预留**
 *    （`toType` / `eventType` / `words` / `targetType` / `targetId` / `detail` / `src` /
 *    `actorRole` / `op` / `path` 都不在这张表里）。
 *
 * ⚠️ `actor` 在两处含义不同：`fields.actor` 是**审计里的业务号**（字符串），帧顶层 `actor` 是
 *    `{id, role}` —— 顶层那个的标签在 `EVENT_KEY_LABEL`，别拿这张表去解释它。
 * ⚠️ `kind` 在 `fields` 里是**库存操作**（deduct / release），帧顶层 `kind` 是**事件类** ——
 *    同名不同物，故这张表只在 `fields` 上生效。
 */
export const FIELD_LABEL = {
  // 缓存 / 锁
  cache: '缓存区',
  lock: '锁',
  key: '键',
  result: '结果',
  // 订单（transition / illegal / async 三支共用这些键里的若干）
  action: '动作',
  from: '前态',
  to: '后态',
  order: '订单号',
  shape: '状态形状',
  atBirth: '出生时',
  reason: '原因',
  // 库存
  kind: '操作',
  sku: 'SKU',
  qty: '数量',
  // 审计 / 通知 / 敏感词 / 身份 / 防护
  actor: '发起人',
  event: '事件类型',
  scene: '场景',
  hit: '命中数',
  total: '总数',
  subject: '主体',
  protection: '防护',
  scope: '范围',
  extra: '附加信息',
}

/**
 * **逐环覆盖**：同一个英文键在不同环里指的不是一件事，只有覆盖才说得准。
 *
 * ⚠️ 目前只有两处真的撞了：
 *   · `order.to` = **后态**（状态机），`notify.to` = **收件人类型**（`customer` / `merchant`）；
 *   · `order.action` / `audit.action` 都是「动作」，但值的翻译表不同（见 `fieldValueText`）。
 * 其余键在九环里语义一致，走 `FIELD_LABEL` 的通用词。
 */
export const FIELD_LABEL_BY_KIND = {
  notify: { to: '收件人类型' },
}

/**
 * 帧**顶层**结构键 → 中文（⑤ 的「中文（英文原键）= 值」那一层用）。
 * ⚠️ 与 `FIELD_LABEL` 分开：顶层的 `kind` / `key` / `actor` 和 `fields` 里的同名键**不是一回事**。
 */
export const EVENT_KEY_LABEL = {
  kind: '类型',
  severity: '级别',
  key: '键',
  objectType: '对象类型',
  objectId: '对象 id',
  traceId: '链路号',
  batchId: '批次号',
  durationUs: '耗时',
  remaining: '剩余',
  actor: '发起人',
  src: '来源环',
}

/**
 * 通知收件人类型（`fields.to` 的**值**）→ 中文。
 * ⚠️ 取值出处是后端 `NotificationService.TO_TYPE_CUSTOMER` / `TO_TYPE_MERCHANT`
 *    （两个常量逐字就是 `customer` / `merchant`）；认不出回落原值。
 */
export const NOTIFY_TO_LABEL = {
  customer: '客户',
  merchant: '商家',
}

/** 库存操作（`fields.kind` 的**值**）→ 中文。出处：后端 `StockMetrics` 的 `deduct` / `release`。 */
export const STOCK_KIND_LABEL = {
  deduct: '扣减',
  release: '释放',
}

/** 取 `fields` 键名的中文（逐环覆盖优先；认不出**回落英文原键**，绝不空白）。 */
export function fieldLabel(kind, key) {
  const k = String(key)
  const byKind = Object.prototype.hasOwnProperty.call(FIELD_LABEL_BY_KIND, kind) ? FIELD_LABEL_BY_KIND[kind] : null
  if (byKind && Object.prototype.hasOwnProperty.call(byKind, k)) return byKind[k]
  return Object.prototype.hasOwnProperty.call(FIELD_LABEL, k) ? FIELD_LABEL[k] : k
}

/** 取帧顶层结构键的中文（认不出回落英文原键）。 */
export function eventKeyLabel(key) {
  const k = String(key)
  return Object.prototype.hasOwnProperty.call(EVENT_KEY_LABEL, k) ? EVENT_KEY_LABEL[k] : k
}

/** 取中文名（认不出回落 tag 原文）。⚠️ 用 `hasOwnProperty` 而不是 `X[k] || k` —— 理由见
 *  `notify-events.js` 块首那条：`'constructor'` 之类**原型链上的键**会拿到函数（truthy）并渲染进模板。 */
export function kindLabel(tag) {
  return Object.prototype.hasOwnProperty.call(KIND_LABEL, tag) ? KIND_LABEL[tag] : String(tag)
}

/** 取类色（认不出回落中性灰）。 */
export function kindColor(tag) {
  return Object.prototype.hasOwnProperty.call(KIND_COLOR, tag) ? KIND_COLOR[tag] : FALLBACK_COLOR
}

/** 取类图标（认不出回落空心方框）。 */
export function kindIcon(tag) {
  return Object.prototype.hasOwnProperty.call(KIND_ICON, tag) ? KIND_ICON[tag] : FALLBACK_ICON
}

/** 取对象列的说明词（认不出回落「对象」）。 */
export function objectFieldLabel(tag) {
  return Object.prototype.hasOwnProperty.call(OBJECT_FIELD_LABEL, tag) ? OBJECT_FIELD_LABEL[tag] : FALLBACK_FIELD_LABEL
}

/** 取严重度中文（认不出回落 tag 原文）。 */
export function severityLabel(tag) {
  return Object.prototype.hasOwnProperty.call(SEVERITY_LABEL, tag) ? SEVERITY_LABEL[tag] : String(tag)
}

/** 取严重度色（认不出回落中性灰）。 */
export function severityColor(tag) {
  return Object.prototype.hasOwnProperty.call(SEVERITY_COLOR, tag) ? SEVERITY_COLOR[tag] : FALLBACK_COLOR
}

/**
 * 攻击演练的 **8 个场景** key → 中文名（commit 9 从 `AttackConsole.vue` 的场景读数搬入）。
 *
 * ⚠️ 【为什么这里还需要一张 key→中文的表，后端不是已经返回 `Name` 了吗】
 *    因为**记录里存的是 key、不是 `Name`**：`drill_record.Result` 里存下来的勾选清单与分场景读数
 *    （页面上的 `x.k` / `p.Key`）带的都是 key ⇒ 回看一条老记录时，`Name` 已经不在手上。
 *
 * ⚠️ 取值**逐字抄自后端** `observe/service/AttackEngine.java` 的 `SCENARIOS`（那个 `List<ScenarioSpec>`
 *    是唯一出处，连 `/attack/scenarios` 端点返回的 `Name` 也从它来）。本表是它的镜像。
 *
 * ⚠️ 认不出回落 **key 原文**（同 `KIND_LABEL` 的兜底口径）：后端加第 9 个场景而这里没跟，
 *    页面应当显示一个看得见的英文 key，不是空白。
 */
export const SCENARIO_LABEL = {
  seckill: '秒杀抢购',
  orderBurst: '订单并发',
  repeatSubmit: '重复提交',
  unpaidTimeout: '超时不付款',
  cacheCold: '缓存冷启',
  redisDown: 'Redis 断开',
  bannedUser: '封禁用户攻击',
  sensitiveWord: '敏感词攻击',
}

/**
 * 订单状态流转页（`OrderFlow.vue`）的**五态** key → 中文（commit 9 从该页搬入）。
 *
 * ⚠️ 键名与后端 `OrderStreamService` 帧里的 `counts` / `state` **逐字相同**。它们**不是**
 *    `OrderState` 的枚举名，是后端为**这一页**另起的一套 key（`CREATED` → `waitPay`）
 *    ⇒ 别拿 `labels/order-labels.js` 的 `ORDER_STATE_LABEL` 来替代本表（两者是两套坐标，
 *    见那个文件块首的 📌）。
 *
 * ⚠️ **展示顺序**（计数卡 / 环图）不在本文件：那是那一页的版面问题，留在页面的 `STATES` 数组里。
 *    本文件只管「key 该翻成什么」这一件事。
 */
export const ORDER_FLOW_STATE_LABEL = {
  waitPay: '待付款',
  waitShip: '待发货',
  waitReceive: '待收货',
  completed: '已完成',
  cancelled: '已取消',
}

/**
 * 攻击演练的**五个防护开关** key → 中文名 + 声明序（commit 10c 起头）。
 *
 * ⚠️ 【key 与顺序**逐字对齐后端**】`observe/service/AttackEngine.java` 的
 *    `PROTECTION_KEYS = List.of("rateLimit","circuitBreak","degrade","retry","poolIsolation")`
 *    —— 那是唯一出处（后端用它做白名单：表外的 key 一律 400）。本表是它的镜像。
 *
 * ⚠️ 【中文名抄自后端**同一份类注释**】`AttackEngine.java` 讲硬边界 A 的那句逐字是
 *    「补救（**限流 / 熔断 / 降级 / 重试 / 分池**）」，五行与上表五 key 一一对应、顺序相同。
 *    ⇒ 中文不在本文件新起；这里是那五个词的**登记处**（`labels/` 是中文的唯一来源）。
 *
 * ⚠️ 【顺序不是审美】面板上五枚开关的先后、以及将来 D 段把它们接上闸时的顺序，都吃这个序
 *    —— 两处一旦不一致，看数字的人会把「第 2 个开关」指到另一个防护上，而且**不会报错**
 *    （同文件头 `KINDS` 那条 ⚠️ 的道理）。
 */
export const PROTECTIONS = ['rateLimit', 'circuitBreak', 'degrade', 'retry', 'poolIsolation']

/** `PROTECTIONS` 的 key → 中文（与后端类注释逐字对应，见上）。 */
export const PROTECTION_LABEL = {
  rateLimit: '限流',
  circuitBreak: '熔断',
  degrade: '降级',
  retry: '重试',
  poolIsolation: '分池',
}

/**
 * **本段没有真闸**的那一枚 —— 面板必须把它画成**不可开关**（标灰 + tooltip）。
 *
 * ⚠️ 这是后端 `protect/ProtectionGate` 的**镜像**，逐字对应、缺一不可：
 *    `WIRED_KEYS = List.of("rateLimit","circuitBreak","retry","poolIsolation")`（真有闸的四个）
 *    与 `RECORD_ONLY_KEY = "degrade"`（那一枚）。
 *    后端那份是**判据的唯一出处**（`ProtectionGate.wired(key)` 可断言）；前端这份只用于
 *    「开跑之前就把话说明白」——那一刻没有结果可读。
 *
 * ⚠️ 【口径：本段是 **4 键接闸 + 1 键记录位**（2026-10-15 · D 段 · 阶段 2b 起）】
 *    阶段 2b 之前是「3 键接闸 + 1 键登记 TD + 1 键记录位」，`retry` 只在 TD-74 挂了个名。
 *    **用户 2026-10-15 逐字纠正过「4 键已接闸」这种写法** —— 那时它确实是错的（真的只有 3 键）；
 *    当天在拿到死锁实测（48/48 轮复现、149 次 `ER_LOCK_DEADLOCK`）之后裁定**接闸 3 → 4**，
 *    `retry` 落进 `MerchantDowngradeService.relist` 的重试策略，这句话才第一次成为事实。
 *    ⇒ 现在**只剩一枚**关掉，理由只有一句：
 *      · `degrade` 不是「没实现」，是**系统本来就没有这一层**（缓存 L2 回落 / 锁 fail-open /
 *        MQ 缺席 / 审计 fail-open 都是固有行为，不由这个开关管）⇒ 它是**记录位**。
 *    ⚠️ `retry` 那一枚**已经从这份清单里移出**：它现在**能拨且有事发生**，
 *    留在清单里会被面板画成标灰 = 一个「能拨却什么都不发生」的死控件 —— 用户明令禁止。
 *
 * ⚠️ 【为什么是「标灰 + tooltip」而不是「藏起来」】藏起来会让「面板上少了一枚」与
 *    「这一笔忘了做」读起来一样；画成一个**能拨但什么也不发生**的开关则是**死控件**——
 *    用户明令禁止。标灰 + 说清理由，是唯一既不骗人也不留白的那一种。
 */
export const PROTECTIONS_NOT_WIRED = ['degrade']

/** `PROTECTIONS_NOT_WIRED` 里每个 key 的 tooltip（哪一枚标灰、为什么，都从这里读）。 */
export const PROTECTION_NOT_WIRED_TIP = {
  degrade: '记录位，不可开关，系统固有 fail-open 设计',
}

/**
 * 攻击演练的**三个故障注入开关** key → 中文名 + 声明序（commit 10c 起头）。
 *
 * ⚠️ 【key 与顺序对齐后端】`AttackEngine.java` 的
 *    `INJECTION_KEYS = List.of("redisDown","mqDown","dbSlow")` 是唯一出处。
 *
 * ⚠️ 【中文名是**本仓新起**的，不是抄来的】后端对这三个 key **没有**中文文案
 *    （`redisDown` 那一路复用的是 `AttackEngine.REDIS_DOWN_CAUSE` 那句「SIM：演练注入的 L2 故障…」，
 *    那是日志文案不是开关名）。故这里如实登记：`Redis 断开` / `MQ 断开` / `慢 SQL` 三个词
 *    **出自本文件**（前两个与 `SCENARIO_LABEL.redisDown`、`AttackEngine` 里「慢 SQL」的用词对齐）。
 *
 * ⚠️ 【`redisDown` 这个词在两处出现，含义不同】这里说的是**注入开关**（让 L2 真的不可用）；
 *    `SCENARIO_LABEL.redisDown` 说的是**场景**（打 Redis 相关读的那一类请求）。
 *    两者能同时开、也能只开一个 —— 别把两张表合成一张。
 */
export const INJECTIONS = ['redisDown', 'mqDown', 'dbSlow']

/** `INJECTIONS` 的 key → 中文（本仓新起，见上）。 */
export const INJECTION_LABEL = {
  redisDown: 'Redis 断开',
  mqDown: 'MQ 断开',
  dbSlow: '慢 SQL',
}

/**
 * **本笔没有真接通**的注入 —— 面板必须把它们画成「未接通」（可点也要标出来，或不给点）。
 *
 * ⚠️ 这是后端 `AttackEngine.INJECTIONS_NOT_WIRED = List.of("mqDown","dbSlow")` 的**镜像**。
 *    后端把「这一轮哪些注入其实没生效」如实列进结果里的 `Engine.InjectionsNotWired`
 *    ⇒ 前端这份只用于**开跑之前**就把话说明白（那一刻还没有结果可读）。
 *    ⚠️ 未接通 = 拨了它**读数不会变**。画成一个能拨的开关而不标出来，等于骗人。
 */
export const INJECTIONS_NOT_WIRED = ['mqDown', 'dbSlow']

/** 取场景中文名（认不出回落 key 原文）。 */
export function scenarioLabel(key) {
  return Object.prototype.hasOwnProperty.call(SCENARIO_LABEL, key) ? SCENARIO_LABEL[key] : String(key == null ? '' : key)
}

/** 取五态中文名（认不出回落 key 原文）。 */
export function orderFlowStateLabel(key) {
  return Object.prototype.hasOwnProperty.call(ORDER_FLOW_STATE_LABEL, key)
    ? ORDER_FLOW_STATE_LABEL[key]
    : String(key == null ? '' : key)
}

/** 取防护开关中文名（认不出回落 key 原文）。 */
export function protectionLabel(key) {
  return Object.prototype.hasOwnProperty.call(PROTECTION_LABEL, key)
    ? PROTECTION_LABEL[key]
    : String(key == null ? '' : key)
}

/** 这枚防护开关**本段有没有真闸**（`false` ⇒ 面板必须标灰，见 `PROTECTIONS_NOT_WIRED`）。 */
export function protectionWired(key) {
  return !PROTECTIONS_NOT_WIRED.includes(key)
}

/** 取「没真闸」那些枚的 tooltip（认不出回落空串 —— 有闸的开关不该有提示，空串即「不显示」）。 */
export function protectionNotWiredTip(key) {
  return Object.prototype.hasOwnProperty.call(PROTECTION_NOT_WIRED_TIP, key)
    ? PROTECTION_NOT_WIRED_TIP[key]
    : ''
}

/** 取注入开关中文名（认不出回落 key 原文）。 */
export function injectionLabel(key) {
  return Object.prototype.hasOwnProperty.call(INJECTION_LABEL, key)
    ? INJECTION_LABEL[key]
    : String(key == null ? '' : key)
}
