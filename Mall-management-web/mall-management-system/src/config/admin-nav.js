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
//   · 子项 `{ key, label, to }`           —— `key` 与子路由 `meta.sub` **逐字相同**
// ⚠️ 子项的 `key` 在**本域内**唯一即可（点亮只比本域的子项），全表不要求唯一。
export const ADMIN_SIDEBAR = [
  { key: 'ov', label: '概览', to: '/admin', subs: [] },
  {
    key: 'business', label: '业务域', to: '/admin/business',
    subs: [
      { key: 'accounts',   label: '用户与商家治理', to: '/admin/business/accounts' },
      { key: 'catalog',    label: '商品与类目治理', to: '/admin/business/catalog' },
      { key: 'orders',     label: '订单治理',       to: '/admin/business/orders' },
      { key: 'marketing',  label: '营销运营',       to: '/admin/business/marketing' },
      { key: 'moderation', label: '消息与内容治理', to: '/admin/business/moderation' },
      { key: 'notify',     label: '系统通知',       to: '/admin/business/notify' },
    ],
  },
  {
    key: 'observe', label: '可观测域', to: '/admin/observe',
    subs: [
      { key: 'attack-sim', label: '攻击模拟器',   to: '/admin/observe/attack-sim' },
      { key: 'metrics',    label: '指标面板',     to: '/admin/observe/metrics' },
      { key: 'timeline',   label: '状态流转',     to: '/admin/observe/timeline' },
      { key: 'logs',       label: '实时日志',     to: '/admin/observe/logs' },
      { key: 'audit',      label: '权限与审计',   to: '/admin/observe/audit' },
    ],
  },
]
