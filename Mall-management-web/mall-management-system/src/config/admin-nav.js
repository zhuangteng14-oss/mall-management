// src/config/admin-nav.js —— 管理端**侧边栏**表（C3 骨架第二笔建、第三笔补子项路由，2026-10-08）
//
// 与 `config/nav.js` 的关系：那份管**商城顶栏**（客户 / 商家两壳共用的一串平铺按钮），
//   本份管**管理端侧边栏**（「域 → 子项」两级）。两份**有意不合并** —— 形状不同，
//   硬并只会得到一张两头都要分支的表（`nav.js` 块首那条「提取的代价」说的就是这个）。
//
// ── 用户裁决（2026-10-08，第三笔开工时）─────────────────────────────────────
// ① **域名**：「业务域 / **可观测域**」。⚠️ 第二笔我写的「风控域」**已废** ——
//    用户原话：「风控业界指**反欺诈**，与内容不符」。**别再改回去。**
// ② **URL**：`/admin/business`、`/admin/observe`（第二笔我取的 `/biz` / `/risk` **已废**）。
// ③ **子项清单**：6 + 5 = **11 项**，由用户给出（定义在 `docs/agent/c3-modules.md`）。
//    ⇒ 第二笔那 6 行「子项占位 N」占位文案**已全部替换为真名**，`SUB_STUB_NOTE` 随之删除。
// ④ **概览保留**（用户第六节）。
//
// ⚠️ **11 条子项的 `key`（URL 末段）是本次我取的** —— 用户只给了**中文名**，没给 URL 段。
//   取名口径：全小写 kebab，语义直译（`用户与商家治理` → `accounts`、`攻击模拟器` → `attack-sim`）。
//   用户若另有口径，**改三处**：本文件的 `to` / `subs[].key`，以及 `router/index.js` 里对应的 `path`。
// ⑤ **URL 裁决（2026-10-08，第三笔过审后）**：`content` → **`moderation`**（覆盖举报/评价/敏感词）、
//    `flows` → **`timeline`**（状态流转本质是时间线）；其余 **9 条保留**。用户只点了 `to` + `path` 两处，
//    但 `subs[].key`（本文件）与 `router` 的 `meta.sub` 我**一并改了** —— 否则与本文件「`key` = URL 末段」自相矛盾。
//
// ⚠️ **点亮判据是 `key`，不是路径前缀** —— 沿用 `nav.js` 那条成文口径
//   （`router/index.js:36`：「用 `meta.tab` 而**不是** `route.path.startsWith(...)` 之类」）。
//   这里尤其致命：`/admin` 是 `/admin/business` 的前缀、`/admin/business` 又是
//   `/admin/business/accounts` 的前缀 ⇒ 用 `startsWith` 会让「概览」和「业务域」**永远同时亮**。
//   ⇒ 侧边栏比两个键：域行比 `meta.key`，子项行比 `meta.sub`（见各子路由记录）。
//
// 表项形状：
//   · 域   `{ key, label, to, subs[] }`   —— `subs` 空数组 = 该域暂无子项（「概览」即是）
//   · 子项 `{ key, label, to, glyph? }`   —— `key` 与子路由 `meta.sub` **逐字相同**；`glyph?` = `components/ui/glyphs.js` 的**字形名**（不是字符本身 —— 页面里散落的裸字符 grep 不到，《看板规范》§4 的检查就是这么做的）。⚠️ 挂不挂的判据 = **这一项是不是能落到真页面的叶子**：11 条子项、「概览」（落 `AdminHome.vue`）、「通知收件箱」（落 `NotifyInbox.vue`）**都挂**；而「业务域」「可观测域」两条**不挂** —— 它们的 `component` 是 `AdminStub`（纯分组壳，`router/index.js:450`/`:458`），给一个「点进去只是占位页」的项挂图标，等于承诺了一个没有的页面
// ⚠️ 子项的 `key` 在**本域内**唯一即可（点亮只比本域的子项），全表不要求唯一。
export const ADMIN_SIDEBAR = [
  { key: 'ov', label: '概览', to: '/admin', glyph: 'overview', subs: [{ key: 'stats', label: '经营数据', to: '/admin/stats', glyph: 'metrics' }] },   // ⚠️ 可视化段 commit 2（2026-10-11）：`subs` 由 `[]` → 1 项。**同行替换、净零行数** —— 本文件的最大被引行是 `:43`（`c3-block-3-goods.md:221` / `c3-block-4-order.md:197` 钉的），在第 33 行**加行**会让 `:43`/`:44`/`:45` 那三条引用集体下移。落点是文件尾的 `router.addRoute('admin', { path: 'stats', … })`，**别**去 `routes` 的 `children` 里找它。为什么挂**概览域**而不是业务/可观测：它不是「治理」也不是「观测」，是**一眼看经营**
  // ⚠️ 补丁 B1（2026-10-09 手验后）：**通知收件箱**从 `AccountsPage.vue` 第 ⑤ 块提出来，
  //   成**顶层独立项**（`subs: []` ⇒ 侧边栏仍是**两级**、没增深）。⚠️ 「与「概览」同形」这句在 2026-10-11 之后**不再成立**（概览域加了「经营数据」子项）⇒ 本项成了本表**唯一** `subs: []` 的域。
  //   理由：那页页内有 4 块（入驻审核 / 用户治理 / 降级控制 / 收件箱），靠滚动区分；
  //   收件箱与前三块**不同域**（它是「发给管理员的待办」，不属用户与商家治理）⇒ 拆出去。
  //   URL `/admin/inbox`（`router/index.js` 的 `children` 末条，`meta.key` = `inbox` **逐字**）。
  { key: 'inbox', label: '通知收件箱', to: '/admin/inbox', glyph: 'msg', subs: [] },   // ⚠️ 有 `glyph` 而 `subs: []`：它是**叶子**（落 `NotifyInbox.vue`），不是纯分组 —— 判据见 `:30`
  {
    key: 'business', label: '业务域', to: '/admin/business',
    subs: [
      { key: 'accounts',   label: '用户与商家治理', to: '/admin/business/accounts',   glyph: 'users' },
      { key: 'catalog',    label: '商品与类目治理', to: '/admin/business/catalog',    glyph: 'goods' },
      { key: 'orders',     label: '订单治理',       to: '/admin/business/orders',     glyph: 'orders' },
      { key: 'marketing',  label: '营销运营',       to: '/admin/business/marketing',  glyph: 'marketing' },
      { key: 'moderation', label: '消息与内容治理', to: '/admin/business/moderation', glyph: 'moderation' },
      { key: 'notify',     label: '系统通知',       to: '/admin/business/notify',     glyph: 'notify' },
    ],
  },
  {
    key: 'observe', label: '可观测域', to: '/admin/observe',
    subs: [
      { key: 'attack-sim', label: '攻击模拟器',   to: '/admin/observe/attack-sim', glyph: 'attack' },
      { key: 'metrics',    label: '指标面板',     to: '/admin/observe/metrics',    glyph: 'metrics' },
      { key: 'timeline',   label: '状态流转',     to: '/admin/observe/timeline',   glyph: 'flow' },
      { key: 'logs',       label: '实时日志',     to: '/admin/observe/logs',       glyph: 'logs' },
      { key: 'audit',      label: '权限与审计',   to: '/admin/observe/audit',      glyph: 'audit' },
    ],
  },
]
