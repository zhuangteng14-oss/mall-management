// src/config/observe-labels.js —— 可观测域的**中文标签层**（C3 块 7 · commit 8，2026-10-14）。
//
// ⚠️ 【本笔只**起个头**，不是收敛完成】用户 2026-10-14 批文：commit 8 的实时日志页
//    「本页先硬编码（同 commit 7），commit 9 统一收敛到 `observe-labels.js` —— 但本笔要给
//    `observe-labels.js` 起个头（至少本页用到的键先登记）」。⇒ 本文件今天**只登记 `EventLog.vue`
//    用到的那些键**；`MetricsPanel.vue` / `OrderFlow.vue` / `AttackConsole.vue` 里那几份硬编码
//    中文（指标名、五态名、场景名）**还没搬进来**，那是 commit 9 的活。
//    ⇒ 判据：**今天出现两个中文来源是预期的，不是漏搬**；commit 9 收敛后本注释要一并改掉。
//
// ⚠️ 【为什么落在 `src/config/`】与 `notify-events.js` / `goods-flags.js` 同层：本仓把
//    「**code → 中文**」这类表放 `src/config/`（而非 `views/` 下），因为它是**多个页共用的**只读表
//    —— commit 9 收敛时 `MetricsPanel` / `OrderFlow` 要从这里取，跨 `views/*` 子目录引
//    `views/admin/observe/x.js` 会读成「某个页的私有件」。与 `notify-events.js` 一样，
//    **每张表都必须有兜底**（理由见下面每条）。

/**
 * 8 类事件的**声明序**（tag）。⚠️ 与后端 `EventBroadcaster.Kind` 的枚举声明序**逐字对齐**
 * （`cache → lock → order → stock → audit → notify → sensitive → identity`）。
 *
 * ⚠️ 顺序不是审美：后端 `EventStreamService.frame()` 的 `counts` 是 `LinkedHashMap`，按这个序
 * 装进帧；页面的图例、卡片配色、抽屉里的链路排序全都吃这个序 ⇒ 两处一旦不一致，
 * 「图例第 3 个」与「卡片第 3 色」就会指向不同的类，而且**不会报错**。
 */
export const KINDS = ['cache', 'lock', 'order', 'stock', 'audit', 'notify', 'sensitive', 'identity']

/**
 * tag → 中文名。**取值逐字抄自后端** `EventBroadcaster.Kind` 的 `label` 字段
 * （后端那两列是「页面图例上的中文名」的**唯一出处**，前端这份是它的镜像）。
 *
 * ⚠️ 认不出时回落成 **tag 原文**（同 `notify-events.js` 的兜底口径）：后端加了第 9 类而这里没跟，
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
}

/**
 * tag → 色（**CSS 变量引用**，不是色值）。
 *
 * ⚠️ 八类落 `--c1`–`--c8`，`KINDS` 序一一对应。写 `var(--cN)` 而不是 `#hex`：本仓两套主题
 *   （浅色 `:root` / 管理端 `html[data-theme="admin"]`）下这 8 个名字**同名不同值**
 *   （`--c3` 浅色 `#00a64a` / 管理端 `#22c55e`）—— 硬编码色值会在另一套主题上错，
 *   而且错得不明显（都是绿）。见 `assets/tokens.css:200` / `:300` 两行。
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
 * ⚠️ 八类的 `objectId` **来源各不相同**（逐环口径见后端 `EventBroadcaster` 里八处 `add(...)`）：
 *    `cache`/`lock`/`notify` 取各自的事件键，`order` 取订单号，`stock` 取 SKU，
 *    `audit` 取 `TargetType` + `TargetId`（**块 7 · commit 8 新补的两列**），
 *    `sensitive` 取**命中的词**（同上新补，「以前只记命中几个」），
 *    `identity` 取**被校验的请求路径**（同上新补）。
 *    ⇒ 同一格在这八类里指的**不是同一种东西**，所以说明词必须逐类给，不能统一写「对象」。
 *
 * ⚠️ 这一格的值**照原文上屏**（中文词、请求路径、业务号…），**不做翻译**：
 *    `objectType` 那一列（`goods` / `comment` / `order` …，取值面 = `AuditActions` 的 44 条 tag
 *    的域前缀）的**中文表不在本笔**（属 commit 9 的收敛范围），故页面上它只以等宽小字出现。
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
}

/** 认不出 ⇒ 用最中性的那个词（同各表的兜底口径）。 */
export const FALLBACK_FIELD_LABEL = '对象'

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
