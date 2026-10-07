# 预留位台账（slot registry）

**建立**：2026-10-07（D 笔 commit 1，`feat/ui-d-pages`）。
**用途**：把设计稿里的 `data-slot` 逐条对到 **Vue 落点** + **归属笔名**。供「**无死控件**」验收
（`progress.md:1652` 判据①）用 `grep data-slot` 直接对账 —— 台账里有的，Vue 里必须有；反之亦然。

---

## 口径（用户裁决 2026-10-07，**覆盖**同日早先的「超档不落」）

1. **UI 一律落**：稿里画了的预留位，Vue 模板**照落** —— `disabled` + `data-slot` + `title` **三件套**。
   **角标不进 Vue**：它是稿的 `body.slots` 预览开关产物（`::after{content:attr(data-slot)}`），
   只服务于「显示预留位」那个按钮。
2. **功能按「动现有代码 N 处」分档**（`docs/美化/README.md:212-218`）：
   - `N ≤ 3` = 「占位」档；
   - `N > 3` = 「只登记」档 ⇒ 登记 `C3〈…〉`；
   - ⚠️ `README.md:234` **覆盖率优先**：用户逐页清单里**点名要求**的元素，即使 `N > 3` **也落 UI**
     （把 N 原样写进 `data-slot`）。
3. ⚠️ **「只登记」管的是功能、不管 UI**：`N > 3` ⇒ **功能**不本笔做（留给 C3 笔），
   **UI 照落**。D 笔边界只含 `<template>` + `<style scoped>`（`prB-body.md`「本笔只做」），
   **不碰任何 `.java` / `endpoints.js` / `router/index.js`** ⇒ 无论哪一档，本笔的功能实现量都是 0。
   ⇒ 下表「档」列只标**归属去哪**，不表示本笔做了什么功能。
4. **`title` 逐字取稿**（含稿里的 `**粗体**` 标记与「待 C3〈…〉」），便于与稿 `grep` 对齐。
5. **锚点漂移只报告**：重写 `<template>` 会让 `<script>` 整体后移 ⇒ 指向该文件的「文件:行号」互引
   全部失效。`bash scripts/refs-scan.sh` 是**知情工具**，出报告、**不逐条修**。
6. ⚠️ **稿基线口径 v2（用户裁决 2026-10-07，**钉死**；v2 同日修订）**：
   - **除主页与两页例外外**，全 14 页一律以 `docs/美化/pages/` 里的 10 份**新稿**为唯一基线；
   - **`主页改造.html` 仅作 `HomePage` 的基线**（`pages/` 里没有主页新稿）；
   - ⚠️ **v2 新增例外**：`HistoricalOrders` / `FriendRoom` 在 `pages/` 里**没有对应新稿** ⇒
     这两页**回到老稿 `子页面改造.html`** 作基线，**不再要求去 `pages/` 里定位段落**。
     （v1 曾记「最可能 `UserCenter-customer.html` 内，待定位」—— 该指向**已作废**。）
   - ⚠️ **`子页面改造.html` 按段生效**（不是整份弃用，也不是整份有效）：
     `:538` / `:560` / `:593`（**UserInfoPage 面**）⇒ **弃用**，已由 `pages/UserCenter-customer.html`
     承担（见 §二 commit 7）；`:630` / `:633`（**HistoricalOrders 面**）、`:787`（**FriendRoom 面**）
     ⇒ **有效**，按老稿落（见 §二 commit 8 / 9）。

---

## 一、本笔已落（按 commit 追加）

### commit 1 —— `GoodsSelect.vue` ↔ `docs/美化/pages/GoodsSelect.html`

| # | `data-slot` | 稿行 | N | 归属 | Vue 落点 |
|---|---|---|---|---|---|
| 1 | 预留 · 左分类树（动现有代码 6 处 · 超档） | `:458` | 6 | `C3〈搜索〉` | `GoodsSelect.vue:25`（`<aside class="cat">`） |
| 2 | 预留 · 排序 / 筛选（逐项 4～6 处 · 均超档） | `:504` | 4～6 | `C3〈搜索〉` | `GoodsSelect.vue:79`（`<div class="toolbar">`） |
| 3 | 预留 · 分页（动现有代码 4 处 · 超档） | `:539` | 4 | `C3〈搜索〉` | `GoodsSelect.vue:129`（`<nav class="pager">`） |

**本页合计 3 条，全落；0 条只登记。** 三条在稿里都自标「超档」，但**都属 `README.md:234` 的点名例外**
（稿 `title` 原文：「因清单点名而画出（见 README 挂账表）」）⇒ 落 UI 是对的。

⚠️ 三条的共同归属 `C3〈搜索〉` 有一个**已知内部差异**（照稿登录，不自裁）：
- 排序条里 `综合` 那枚的 `title` 自写 **`N=0`「真功能：就是现在的默认返回序，前端不加参数即可」**；
- 但稿把它和其余 4 枚一起画成 `disabled` ⇒ **Vue 照稿 `disabled`**，不自作主张放开。
  若后续要放开 `综合`，它不属「UI 落不落」问题，而是**功能实现**问题 ⇒ 归 `C3〈搜索〉`。

---

### commit 2 —— `ChatRoom.vue` ↔ `docs/美化/pages/ChatRoom.html`

| # | `data-slot` | 稿行 | N | 归属 | Vue 落点 |
|---|---|---|---|---|---|
| 4 | 预留 · 图片消息（动现有代码 4 处） | `:418` | 4 | `C3〈消息〉` | `ChatRoom.vue:82`（输入区「图片」按钮） |

**本页合计 1 条，全落；0 条只登记。**

⚠️ 稿在本页有**三处自相矛盾**，本笔的取值与理由（照登录、不自裁）：

| 稿里打架的两处 | 取谁 | 为什么 |
|---|---|---|
| `:88` 占位清单写「动现有代码 **2 处**」 ↔ `:76` 功能清单表 + `:418` 实码都写 **4 处** | **4** | 二对一；且 `:419` 的 `title` 逐字列了那 4 处（`endpoints.js` / `MessageController.java` / `mall_management_sql.sql` / `ChatRoom.vue`） |
| `:281` 预览按钮 `title` 写「**本稿零占位块**（ChatRoom 的端点都有）」 ↔ 实码 `:418` 确有 1 条 `data-slot` | **1 条** | 实码为准；`README.md:249` 也把它列进挂账表 |
| `:87` 写「**1 块 / 视图，共 4 处**」 ↔ 实码只出现在 a/b/c/d 四个视图各 1 枚 | 一致，无冲突 | —— ㊟ 但 **`data-future` 不落 Vue**（口径 1 只落三件套）；相应地 `.slot-stack`（`:247-248`，专为 `data-future` 角标错位而设）**在 Vue 里没有规则可匹配**，也未落 |

**在挂账表里的档位**：`README.md:249` 明列 图片消息 = **4 ⇒ 超档**，稿的 `data-slot` 又自标
「因清单点名而画出」⇒ 属 `README.md:234` 的**点名例外**，落 UI 是对的。

**空态面（稿 `data-page="e"`）不带角标**：稿 `:89-90` 自注「空态 e 的输入区整条禁用，按钮不带角标」。
Vue 与之逐字一致 —— `v-else` 那条 `.inp-area` 的 4 个控件只 `disabled` + `title`、**无 `data-slot`**
（实跑复核：空态下 `[data-slot]` 命中数 = 0）。

---

### commit 3 —— `HomePage.vue` ↔ **老稿** `docs/美化/主页改造.html`（`HomeTab.vue` 零改动）

⚠️ **本页是全 14 页里唯一以老稿为基线的** —— `docs/美化/pages/` 里**没有主页新稿**（口径 6）。
⚠️ `HomeTab.vue` 是**薄壳**（只渲染 `<HomePage @go-search>` + `defineOptions({name:'HomeTab'})`
供 KeepAlive 命中），**无 template / style 可改** ⇒ 本 commit 整文件零改动。

**稿基线表（用户 2026-10-07 钉死）里 4 条老稿预留位，按「一律落」全部落 Vue：**

| # | `data-slot` | 稿行 | N | 归属 | Vue 落点 |
|---|---|---|---|---|---|
| 5 | 预留 · 功能入口 | `:787` | 稿未标 | `C3〈待定〉` | `HomePage.vue:97`（`<section class="quick">`） |
| 6 | 预留 · 秒杀位 | `:807` | 稿未标 | `C3〈待定〉` | `HomePage.vue:121`（`<section class="flash">`） |
| 7 | 预留 · 券包位 | `:817` | 稿未标 | `C3〈待定〉` | `HomePage.vue:135`（`<section class="tickets">`） |
| 8 | 预留 · 信任位 | `:829` | 稿未标 | `C3〈待定〉` | `HomePage.vue:181`（`<section class="trust">`） |

**本页合计 4 条，全落；0 条只登记。**

⚠️ **本页 4 条与 `progress.md:1636-1649` 登记表的冲突，以本文为准**：那张表头写的是
2026-10-06 的「**有归属的才放**」⇒ #1 功能入口 / #2 券包位 / #3 信任位记「无归属 ⇒ 不做」、
#10 秒杀位记「归属未定 ⇒ 定不出则不做」。**用户 2026-10-07 复核后改为「一律落」**（与 commit 1
的三条同一口径）⇒ 4 条全部落 Vue，归属一律记 `C3〈待定〉`（稿里这四条**没标 N、也没标归属**，
不自裁填）。

⚠️ **三条口径性改动（照落 UI 的前提下，必须在此登记，别当成漏做）**：
1. **秒杀倒计时不写死** —— 稿 `:810` 写死 `02:14:37`，但稿自己的 JS `:966` 就注明
   「真接进项目时应该用接口给的活动结束时间，**不要在这里写死**」⇒ Vue 落 `--:--:--`，
   说明写进该 `<section>` 的 `title`。
2. **`.flash-g` / `.tickets` 的 mock 内容不落** —— 稿这两块由 JS 塞 mock（`FLASH` 4 件 / `TICKETS`
   3 张），本仓**无对应接口** ⇒ `.fl-*` / `.ticket*` 两整组规则**不搬**（搬进来就是悬空类名）。
   但空 `display:grid` 高度为 0 ⇒ 会「落了却看不见」⇒ 两块各出一行 `.empty-tip` 说明。
3. **功能入口的三个 mock 数字不落** —— 稿 `:790/:794/:798` 的副标题带「待收货 2 / 3 张可用 /
   最近 18 件」是 **mock 值**，照落会在真产品里显示假数字（与 commit 2 不写死「今天」同一条理由）
   ⇒ 只落**描述**（`待付款 · 待收货` / `我的券包` / `最近看过的商品`）；「在线客服 9:00 – 21:00」
   是服务时段陈述、不是 mock ⇒ 逐字落。

⚠️ **稿本页的 4 条都没有 `title`**（只有裸 `data-slot`）⇒ Vue 的 `title` 是本笔补的**归属说明**
（`预留 · X（老稿 主页改造.html:NNN）· 归属待定 C3〈待定〉`），不是「逐字取稿」（口径 4 在此不适用）。

---

### commit 4 —— `ShoppingCart.vue` ↔ `pages/ShoppingCart-customer.html`（4 条）

⚠️ **本页服务两个宿主**（同一文件、同一套模板）：
- **客户宿主** `/customer/cart`（`router/index.js:61` 传 `props:{showSwitch:false}`）—— 本 commit 的基线；
- **商家宿主** `/merchant/order/cart`（`merchant/OrderPane.vue:3` 的 `v-else` 那支）。

用户 2026-10-07 裁决「**选 1（共用骨架 + `showSwitch` 分叉）**」，理由：商家壳**继承**用户层购物车，
不是「商家也能买」，是**角色超集的结构性结果**。商家稿 `OrderCenter-merchant.html:749-808`
自注「形态与 `ShoppingCart-customer.html` 逐字对齐」「同源（同一 `ShoppingCart.vue`）」
「换个顶栏就是同一页」⇒ 两稿本就是同一页。

| # | `data-slot` | 稿行 | N | 归属 | Vue 落点 |
|---|---|---|---|---|---|
| 9 | 预留 · 编辑（动现有代码 4 处 · 超档） | `:514` | 4 | `C3〈购物车〉` | `ShoppingCart.vue:46`（客户宿主挂、商家宿主 `null`） |
| 10 | 预留 · 店铺分组 / 商品行 / 店铺小计（动现有代码 4 处 · 超档） | `:519` | 4 | ⚠️ 稿未标 `data-future`，随邻项 ⇒ `C3〈购物车〉` | `ShoppingCart.vue:77`（**两宿主都挂**） |
| 11 | 预留 · 服务承诺条（动现有代码 ≥4 处 · 超档） | `:531` | ≥4 | `C3〈商品〉` | `ShoppingCart.vue:173`（客户宿主挂、商家宿主 `null`） |
| 12 | 预留 · 吸底结算栏（动现有代码 4 处 · 超档） | `:547` | 4 | `C3〈购物车〉` | `ShoppingCart.vue:198`（**两宿主都挂**） |

**本页合计 4 条，全落；0 条只登记。**

⚠️ **两条挂 `data-slot`、两条走三元 —— 不是笔误，是照稿**：客户稿给 `.edit`（`:514`）与 `.pledge`（`:531`）
都挂了 `data-slot`，而商家稿的 `data-page="cart"` 面 **同值不挂**（`OrderCenter-merchant.html:763`/`:788`），
页内 `:760` 与 `:783-785` 写明理由是「**同一个缺口不在两处各挂一次角标**」（缺口已在客户稿登记过）。
⇒ Vue 用 `:data-slot="showSwitch ? null : '…'"` 实现这条**逐字照稿**的分叉。
`.shops` / `.paybar` 两稿都挂 ⇒ 无条件落。

⚠️ **两宿主实跑实测的条数（对账用）**：
- 客户宿主 `/customer/cart` **有商品** = **4** 条，字符串与客户稿逐字同；**空车** = **0** 条 ——
  与客户稿 `:126` 自注「空视图 slots 计数为 **0**」一致（`.edit` 故加 `v-if="orderList.length > 0"`：
  稿 `:572-574` 的空视图 `.h1row` 里只有 `.page-h`、没有这枚按钮）；
- 商家宿主 `/merchant/order/cart` = **2** 条（`.shops` + `.paybar`），
  与 `OrderCenter-merchant.html:767`/`:793` 一致。

⚠️ **「商家那 2 条算谁的」**：商家购物车面的 2 条**已由本 commit 落地**（同一文件）。
`§二 commit 6` 的 7 条里包含 `:767`/`:793` 这两条 —— 落 commit 5/6 时**只核销售面**，
这两条**不重复计数**（订单面见 `:678`–`:738` 五条）。

⚠️ **本页的功能面登记（照落 UI 的前提下，别当漏做）**：
1. **付款 / 收货 / 4 步进度带 / 创建·更改日期 四样全部留页内** —— 稿 `:19-20` 把这**四样并列**
   写成「**迁走**」（原文：「【付款】`order.orderPayIfUp`(endpoints.js:56) /【收货】
   `order.orderReceiveIfUp`(:57) / 4 步进度条 / 创建·更改日期 —— 这些是**订单**的职责」），
   目标是 `/customer/orders`。但**该路由本仓不存在**（`router/index.js` 无 `customer/orders`），
   本页是全前端**唯一**提供付款/收货的入口 ⇒ 迁走 = 功能回退。用户裁定 D 笔
   **保持现有「购物车 + 订单列表」混合形态** ⇒ **四样一并原地保留**，两个按钮的
   `:disabled` / `:class` / `@click` **逐字保留原实现**；缺失的 `/customer/orders` 路由
   归「**完整购物车笔**」。
   ⚠️ 这四样**都不是本笔新写的** —— `git show HEAD:…/ShoppingCart.vue` 的 `:147-157` 与
   `getProgressWidth()`（旧 `:295`）**本来就在**。稿 `:28-33` 自己也把这事写明了：
   「现有实现其实是订单列表，不是购物车」「本稿画的『购物车』与现有实现在语义上不是同一个东西」
   「**这不是本笔要解决的事**（购物车功能本身被用户划在 B 笔之外）」
   ⇒ 本笔对它们**零改动**，只是没删。
2. **订单号照留** —— 稿 ★ v4 新删了行内的「订单号」，那条理由以「纯购物车」为前提；
   本页混合形态下订单号是必要标识 ⇒ 保留，登记为**有意偏离稿**。
3. **数量 / 小计 / 店铺小计 / 猜你喜欢 → 占位不删**（用户 2026-10-07 中途裁决
   「目前有些内容比较缺失，是因为没做（数据库缺列，功能流程不完善），**你先占位**，
   我 C3 后续要回过来完善流程补上」）：
   - 数量列 `—` + `disabled` 的 −/+（`order` 表**无 `Quantity` 列**，`mall_management_sql.sql:141-155`）；
   - 行小计 `—`；店铺分组头「商家 {{MerchantId}}」+「本店小计 `—`」（`shopGroups` 按 `MerchantId` 分组）；
   - 猜你喜欢、店铺名、承诺条、吸底栏的「运费 免运费」**不落** —— 承诺条/吸底栏由 `data-slot` 承担，
     猜你喜欢出 `.empty-tip` 说明（其数据源 `goodsSelectHome` **现成**，但 D 笔功能量恒为 0 ⇒ 未接线）。
4. **吸底「合计」= Σ `order.Price`**，而**行小计是 `—`** —— 两者口径不同，吸底栏 `title` 里写明。
5. ⚠️ **勘误（2026-10-07，落 commit 8/9 前自查 ⑥ 时发现）**：本条原写「4 步进度带是本页
   混合形态的**新增**，稿里没有对应物」—— **与事实不符**，已并入上面第 1 条订正：
   它是 **HEAD 原实现**（旧 `:147-157`），稿 `:20` 明确把它列进「**迁走**」清单，
   本笔**零改动保留**。commit 4 的 commit message（`6f92fc1`）里也用了「新增」二字，属**措辞错误**
   （改动本身无错：保留是符合裁决的）。⚠️ **代码与台账均无需回改** —— 用户 2026-10-07 已择定
   **「保留原文、只在本条订正」**（`--force-with-lease` 改写已推 commit = 有风险，不做）：
   > commit `6f92fc1`（commit 4）body 里的「**新增**」措辞**不准确**，实际为「**重排 + 占位**」；
   > 改动本身无错（保留是符合裁决的），**以本台账为准**。

---

### commit 5 —— `OrderCenter.vue` ↔ `pages/OrderCenter-merchant.html`（5 条，**只核销售面**）

⚠️ 本稿是**一稿两 pane**：`data-page="order"`（销售面 = 本 commit）与 `data-page="cart"`
（商家购物车面 = **已随 commit 4 落进 `ShoppingCart.vue`**）。稿内 7 条 `data-slot` ⇒ 5 + 2。

| # | `data-slot` | 稿行 | N | 归属 | Vue 落点 |
|---|---|---|---|---|---|
| 13 | 预留 · 数据概览（动现有代码 4 处 · 超档） | `:678` | 4 | `C3〈订单〉` | `OrderCenter.vue`（`<section class="stats">`） |
| 14 | 预留 · 标签页（全部/待发货/已发货 = 1 处 · 在档；退款中/售后 = ≥6 · 超档） | `:690` | 1 / ≥6 | `C3〈订单〉`（**同一块里两档并存**，`data-slot` 原文已写明档次） | `OrderCenter.vue`（`<nav class="tabs">`） |
| 15 | 预留 · 筛选栏（动现有代码 4 处 · 超档） | `:705` | 4 | `C3〈订单〉` | `OrderCenter.vue`（`<div class="filter">`） |
| 16 | 预留 · 批量操作条（动现有代码 ≥4 处 · 超档） | `:718` | ≥4 | `C3〈订单〉` | `OrderCenter.vue`（`<div class="bulk">`） |
| 17 | 预留 · 分页（动现有代码 4 处 · 超档） | `:738` | 4 | `C3〈订单〉` | `OrderCenter.vue`（`<nav class="pager">`） |

**销售面合计 5 条，全落；0 条只登记。**（另 2 条 `:767` / `:793` = 购物车面，已记在 §一 commit 4。）

⚠️ **表格（`.tbl`）不挂 `data-slot`** —— 稿 `:728` 原话：「9 列全是真功能（状态由三个状态位推出、
金额/订单号/时间/商品/买家 id 都在响应里）⇒ **不挂角标**」。逐行重复的链接/按钮一律不加角标，
理由同老稿 `子页面改造.html:779`「12 格全标会糊成一片」+ 稿 `:17` 那条（首版逐行挂到 42 个、
开槽位视图后叠成一团 ⇒ 改到 6 个）。

⚠️ **实跑实测（Edge 1440 视口 / hash 路由整串）**：
- 销售面**有数据** = **5** 条，5 个 `data-slot` 字符串与稿**逐字相同**；
- 销售面**空态** = **0** 条 —— 稿的空态 pane（`:811-825`）里概览/标签页/筛选/批量/分页**五块都不存在**；
- 两档宽度下 `document.documentElement` 的 `overflowX` 均为 **0**。

⚠️ **用户裁决 A —— `<select>` 保留（本页唯一一处「两个控件并存」）**：
稿 `:53` 的 ★ 是「**收紧**：现有的二选一下拉 ⇒ 6 个标签页」，但 6 枚 tab 在本笔**全部 `disabled`**
（功能量恒为 0）⇒ 真把 `<select>` 拿掉，「已完成」视图在页面上**不可达**，而 `orderDeliverySelect`
端点仍在 ⇒ **属功能回退**。⇒ A = **6 枚 tab 照稿落（全 `disabled`，`.tabs` 整块挂 `data-slot`）
+ 现有 `<select>` 原样保留**。代价 = 两个控件短暂并存（过渡态本来就该长这样）；
副作用 = 标题行改用稿的 `.h1row` 才装得下右侧控件 ⇒ **比稿低 `--s4`(16px)**
（稿 `:352` 自注「本规则在订单中心区域**零消费**」，即稿里这一面的 h1 是直接挂在 `.crumb` 之后的）。

⚠️ **本页有意偏离 / 必须交代的 5 条**（照落 UI 的前提下，别当漏做）：

1. **4 步进度带不落，`getProgressWidth()` 随之删除** —— 稿的 9 列表格没有它的位置，而信息由
   「状态」列的 **5 态 chip 完全承担**（含第 5 个合法态「已收货·未发货」，比进度带**还多一态**）
   ⇒ 是**替换**、不是丢失。⚠️ 这是本 commit **唯一的脚本改动**（删一个因失去消费者而变死的
   纯展示函数；不删则 `eslint no-unused-vars` 直接报错）。`loadOrders` / `formatDateTime` /
   `handleDeliver` / `currentMerchantId` / `onMounted` **逐字未动**。
2. **`.stats` 四个 KPI 全部是 `—`** —— 它们是**预留位**（无聚合端点），而现有两次请求各只取
   一个口径（active = `ReceiveIf != 1`）⇒ 四个数**都算不出来**。稿的那四个数是从它自带的
   6 行静态数据集推出来的，本页没有那个数据集 ⇒ 照稿自己立的规矩（`:881-885`
   「**凡是另一个数算得出来的数，都不许手写**」）的**反面**：算不出来就不写 ⇒ 留 `—`。
   同理 `.tabs` 前四个 tab 的计数也是 `—`；后两个照稿写 `0`（稿 `:697-699` 明写
   「恒为 0，**不是『暂无数据』而是『这个态不存在』**」）。
3. **`.page-h .n` 用两个真算出来的数**：「共 {当前视图行数} 单 · 待处理 {待发货行数} 单」——
   前者是**当前视图**的行数、不是全库，`title` 已写明这一点。
4. **时间列保留第二行「更改日期」**（`.time.upd` ← `SettlementDate`）——
   ⚠️ 稿的 A 列对照（`:44-54`，自称「现有 `OrderCenter.vue` **411 行的每一件**去哪了」）
   **没有登记这一条**：既不在「保留并重画」清单里，也不在「删」里。
   本笔按用户一贯的「不删既有已显示字段」保留，**并入同一格第二行**（不新增列）。
   ⚠️ **待用户裁**：是照稿撤掉，还是补进稿的 A 列对照。
   因这一行，**三列列宽相对稿改动**（时间列 128→170、状态列 92→100、订单号列 132→112；
   固定列合计 732→762 ⇒ 商品列 272→242）—— 三条都有**本机实测**出处，见 `OrderCenter.vue`
   的 `.tbl` 注释。⚠️ 状态列那 8px 与「更改日期」无关，是**独立的**一处修正：
   「已收货·未发货」chip 实测 100px，稿的 92px 会把它压折。
5. **操作列按稿的 `canSend` 落** —— 只有「待发货」行渲染可点的【发货】
   （`:disabled` / `:class` / `@click` **逐字保留原实现**），其余行画 `—`。
   ⚠️ 副作用：未付款行今天「点了弹『用户未付款，暂不能发货』」这条路径在 UI 上消失；
   `handleDeliver()` 的**三道守卫逐字保留**（纵深防御），且状态列已具名「未付款」。
   ⚠️ **稿标注的既有缺陷照原样呈现**：`OrderDao.selectByMerchant` 的两条分支都不 SELECT
   `CreateTime` ⇒「已完成」视图的下单时间格画 `.dash`「—」+ `title` 说明是**库层缺字段**
   而不是「这一单没有时间」（既有缺陷，`db-schema.md:91` 有记；Dao 注释亦写明「本笔不趁手改」）。

---

### commit 7 —— `UserInfoPage.vue` ↔ `pages/UserCenter-customer.html` + `UserCenter-merchant.html`（7 条）

⚠️ **本页是「一页两稿」**：基线是**两份**稿（`UserCenter-customer.html` / `UserCenter-merchant.html`），
Vue 侧是**同一文件按角色分支** —— `v-if="!userInfo.customerid && userInfo.merchantid"` ⇒ 商家面、
`v-else` ⇒ 客户面。判据与原 `getUserData()` 的分支**逐字同构**。

| # | `data-slot` | 稿行 | N | 归属 | Vue 落点 |
|---|---|---|---|---|---|
| 18 | 预留 · 我的资产（待 C3〈个人中心〉） | 客户 `:323` | 4 | `C3〈个人中心〉` | `UserInfoPage.vue:143`（`<section class="assets">`） |
| 19 | 预留 · 我的订单四入口（待 C3〈订单〉） | 客户 `:332` | 4 | `C3〈订单〉` | `UserInfoPage.vue:155`（`<section class="sec">`） |
| 20 | 预留 · 收藏 / 浏览足迹（待 C3〈个人中心〉） | 客户 `:348` | 3 | `C3〈个人中心〉` | `UserInfoPage.vue:176`（`<section class="sec">`） |
| 21 | 预留 · 服务（待 C3〈个人中心〉） | 客户 `:371` | 4 | `C3〈个人中心〉` | `UserInfoPage.vue:208`（`<section class="sec">`） |
| 22 | 预留 · 经营数据（待 C3〈商家中心〉） | 商家 `:311` | 4 | `C3〈商家中心〉` | `UserInfoPage.vue:61`（`<section class="assets">`） |
| 23 | 预留 · 待处理订单四入口（待 C3〈订单〉） | 商家 `:320` | 4 | `C3〈订单〉` | `UserInfoPage.vue:73`（`<section class="sec">`） |
| 24 | 预留 · 服务（待 C3〈商家中心〉） | 商家 `:352` | 4 | `C3〈商家中心〉` | `UserInfoPage.vue:122`（`<section class="sec">`） |

**合计 7 条，全落；0 条只登记。** ⚠️ **运行时按角色分别是 4 / 3**（`v-if` 两分支互斥，同款说明见 §五）。

⚠️ **编号顺延说明**：commit 7 的落地**早于** commit 8/9，按本表既定的「全表升序 = 落地序」取
**#18–#24**；§二 commit 8/9 原先预占的 #18–#20 **顺延为 #25–#27**（先例：commit 5 落地时把 v1 的
#13–#15 顺延成 #18–#20，见 §二 commit 8/9 的「编号说明」）。⚠️ 这**只动台账编号、不涉代码**；
若用户认为该先到先得，本条可回退。

⚠️ **N 值逐字取稿的 `title`**（不是本笔估的）——客户 `:324` / `:333` / `:372` 均写 N=4、
`:349` 写 N=3；商家 `:312` / `:321` / `:353` 均写 N=4。⚠️ **客户 `:349` 的「N=3」是七条里唯一的异值**
（title 原文：「收藏与浏览足迹均无端点（N=3）」，第三项稿没点名）⇒ **照抄，不自裁补齐**。

**⚠️ 判据分开口径的统一登记（用户 2026-10-07 钉死，本页是新加入的一处）**

> **入口指向「已存在路由」⇒ 真 `<router-link>`**（不调 API、不改状态、不动 script
> ⇒ **不是功能增量**；「本笔功能量恒为 0」管的是 API / 状态机 / 逻辑，**不管 UI 导航**）；
> **编的数字 / 名 / 价 ⇒ 落 `—`**。

⚠️ **同族条目另有两条**（commit 5 `OrderCenter.vue` 的 `.go2`、`ShoppingCart.vue:59` 的 `.go2`）
—— 那两处**已推、不回头改**，与本条**一并在案**，**commit 14 终验时一次性扫、统一改**。

⚠️ **本页唯一一条真 `<router-link>`**：商家侧 ④「查看全部」→ `/merchant/user/list`
（`router/index.js:117`，**路由已存在**，指向 `GoodsUp`），落点 `UserInfoPage.vue:103-107`。
- ⚠️ **稿在这里自身表里不一**：稿 `:194` 的 CSS 注释写「「查看全部」是**真链接**（指向
  /merchant/user/list）」，可实码 `:339` 却是 `<button class="more" title="…">查看全部</button>`
  —— **无 `href`、无 `@click`、连 `disabled` 都没有**（全页唯一的裸控件）。
  ⇒ 用户裁决 **F2 = 甲改**：**取「真链接」那一半**，Vue 落真 `<router-link>`。
- ⚠️ **`title` 逐字取稿，含稿写错的组件名**：原文「真路由 —— /merchant/user/list
  （查看商品，**GoodsSelect**）」，而该路由实际到 **`GoodsUp`** ⇒ **照稿逐字落 + 在此登记**，
  不替稿改错（同稿内其余错字一律照落）。

⚠️ **`—` 的落点（F1 口径：编的数一律不落）**：② 我的资产 / 经营数据的**四格数字全 `—`**
（稿是 `1,280` / `18件` 这类示意值）；商家 ④「最近上架」**6 张卡的卡名与价全 `—`**
（稿编的「无线蓝牙耳机 声谷标准版 / ¥129.00」）。⚠️ 同一条理见 §一 commit 5 的 `.stats`：
**算不出来就不写**（稿 `:881-885` 自立的规矩）。

⚠️ **身份卡用真值，不用稿的 `plzTWafaGp`**：`.ident-name` ← `userInfo.username`、
`.ident-id` ← `客户 ID {customerid}` / `商家 ID {merchantid}`，都是 `/user/me` 的真返回
（`UserController.java:213-238` = `meInfo` 之上再叠 `profileOf` ⇒ **`user` 表整行 + image**）
⇒ ① 用户类型 / 性别 / **年龄** 三行均为真字段（`age` 确在 `user` 表，**不是**本笔编的）。

⚠️ **零功能增量的取证**：`<script setup>` **85 行、逐行未动** —— `git show HEAD:…` 与工作区
两侧都是 85 行，三处调用点距 script 起点的行内偏移（21 / 51 / 67）**完全一致**，
`/user/me`、`/user/updateAvatar`、`/user/updateUserInfo` 三个 `http.post` 仍在原处
（`UserInfoPage.vue:272` / `:302` / `:318`）。⑥ 账号设置的三个 `v-model` 与
`saveUserInfo()` 是**既有真功能**，逐字保留。

⚠️ **本 commit 唯一一处壳改动**（C 笔明文交接、用户采信）：`AppShell.vue` 的 `.user-page`
取走稿里的 `align-items:flex-start` —— 右栏子页根节点是 `<main class="uc-main">`，
撑满高度会把「身份卡以下的区块」拉到视口底。⚠️ 该规则**三页共用**（commit 7/8/9）。

### commit 7b —— 补 `.wrap` 居中容器 + 修「顶栏被压扁」（两件，用户 2026-10-07 裁决，**同笔一起推**）

**来由 ①**（`.wrap` 居中）：C 笔明文挂给 D 笔（`UserCenter.vue:81-82`「`.content` 的 1180 居中
不在本笔（D 笔）」），→ 用户裁决**本轮补掉**，理由：**影响 commit 7/8/9 三页共用**，拖到
commit 14 会让 8/9 落完后又重测三页。
**来由 ②**（顶栏压扁）：本笔**复核几何时撞出来的**，不在计划内 ⇒ 先只报不改，用户裁决后并入。
（两件**同在 `AppShell.vue`、同为主题 = 让 7/8/9 三页的壳先稳**，故并成一笔。）

**改动 ①**（`AppShell.vue` 的 `.user-page`，只加三件居中属性 + 注释）：
```css
max-width: 1140px;
margin: 0 auto;
padding: var(--s7) var(--s5) var(--s8);
```
⚠️ `display:flex` / `align-items:flex-start` / `gap` / `height:100%` **一概未动**。

⚠️ **宽度取 `1140`，不是交接话里的 `1180`** —— 实测 `docs/美化/pages/` 的 9 份稿：
**只有这两份 UserCenter 稿用 1140**（`UserCenter-customer.html:136` / `UserCenter-merchant.html:132`，
逐字相同），**其余 7 份 + 已落的 `GoodsSelect.vue:305` / `ShoppingCart.vue:446` 都是 1180**。
按稿基线口径 v2「以稿为唯一基线」⇒ 照本页这两份稿落 **1140**。

⚠️ **源码依据**：稿 `.wrap` 包住的 `.uc`（`UserCenter-customer.html:141`）= 本壳的 `.user-page`
（`UserCenter.vue` 的两个根 `.uc-menu` + `main.uc-main` 直接落在这里，多根片段，勿加包裹 div）。

**取证**（`geo7.mjs`，1440 视口，与稿逐值对照 —— 四组的 x/w **完全相同**）：

| 选择器 | 稿 | Vue（7b 后） |
|---|---|---|
| `.wrap` / `.user-page` | x=150 w=1140 | x=150 w=1140 |
| `.uc-menu` / `.user-menu` | x=174 w=200 | x=174 w=200 |
| `main.uc-main` | x=438 w=828 | x=438 w=828 |

- `shot7.mjs` 加 ⑤ `.wrap` 居中块（4 条断言）⇒ 全页断言 **35 → 39 条全绿**。
- `npx eslint src/views/UserInfoPage.vue src/views/AppShell.vue` → exit 0；`npm run build` → 绿。

⚠️ **本笔引起的互引漂移（**只报告、不逐条修** —— 归 commit 14 文档漂移清理笔）**：
本笔在 `AppShell.vue` 里插了两处（`.top-nav` 处 **+16 行**、`.user-page` 处 **+13 行**）⇒
**所有指向 `AppShell.vue` 且行号 ≥ 208 的引用集体 +16**。
实测受影响 **7 条**，**全部 +16**：

| 旧行 → 现行 | 被引内容 | 引用方 |
|---|---|---|
| `214 → 230` | `.top-nav` 的「本仓没有全局 `body{font-family}`」注释 | `UserInfoPage.vue:386` |
| `225 → 241` | 顶栏无 hover 态 | `HomePage.vue:23`、**本文件**（§一 commit 3 那段） |
| `254 → 270` | `.nav-right a.on` 的 `color: var(--nav-now)` | `progress.md:1111` |
| `295 → 311` | `.cart-container` 的说明注释 | `OrderCenter.vue:293`、`ShoppingCart.vue:372` |
| `299 → 315` | `.cart-container {` | `ShoppingCart.vue:369` |
| `306 → 322` | `.order-container {` | `OrderCenter.vue:292` |
| `318 → 334` | 「D 笔 commit 7 已取走 `align-items:flex-start`」 | `ChatRoom.vue:485`、`UserInfoPage.vue:8` / `:356` |

⚠️ 其余指向 `AppShell.vue` 的引用（行号 ≤ 184 的 16 处）在插入点**之上**，**未受影响**。

### ⚠️ 附带修复（**并入本笔一起推**）—— 壳的 `.top-nav` 被压扁（60px → 36px）

**用户 2026-10-07 裁决：丁 = 甲 + sticky** —— `.top-nav` 加 4 条声明
（`flex:none` / `position:sticky` / `top:0` / `z-index:10`），**并入 commit 7b 一起推**
（理由：同文件、同主题、8/9 落前必须修好）。**判据（原话）**：「`flex:none` 是修 bug 必需；
`sticky` 是壳应有的行为；不需要乙的嵌套滚动。」**否决理由**：甲 = 顶栏随页滚走 = 把壳降级回
「没有壳」的形态（本壳的设计哲学是「壳稳定、内容切换」）；乙 = 嵌套滚动容器 + 百分比高度 +
`absolute` 参照物，回归面大，且滚动行为与稿不一致。

**发现经过**：这条**不在计划内**，是 commit 7b 复核几何时撞出来的 —— 发现时**先只报不改**，
用户裁决后才落码。

**实测**（`navmulti7.mjs`，1440×900，`getBoundingClientRect`）：

| 路由（组件 / 落入的 commit） | 前置态 `00a4be3` nav | 前置态 content | 现值 nav | 现值 content |
|---|---|---|---|---|
| `/customer`（HomeTab / commit 3） | 60 | 840 | 60 | 840 |
| `/customer/cart`（ShoppingCart / commit 4） | 60 | 840 | 60 | 840 |
| `/customer/msg`（ChatRoom / commit 2） | 60 | 840 | 60 | 840 |
| `/merchant/order`（OrderPane / commit 6） | 60 | 840 | 60 | 840 |
| `/merchant/order/cart`（OrderPane / commit 6） | 60 | 840 | 60 | 840 |
| `/customer/search`（**GoodsSelect / commit 1**） | 60 | 840 | **36** | **963** |
| `/customer/user/info`（**UserInfoPage / commit 7**） | 60 | 840 | **36** | **2126** |
| `/merchant/user/info`（**UserInfoPage / commit 7**） | 60 | 840 | **36** | **1817** |

⚠️ **订正**：commit 7b 复核时我先判成「**commit 7 引入的回归**」——**不准确**。补测
`/customer/search` 后可见，**commit 1（GoodsSelect，已推）的 content=963 就已经触发**。
真相分两层：

- **壳的缺陷是既有的**：`git diff 00a4be3 HEAD -- AppShell.vue` 里，`.top-nav` / `.content`
  两条规则**一字未改**（D 笔对壳的唯一改动是 commit 7 的 `+align-items:flex-start`）。
- **触发者是 D 笔的页面重写**：前置态**每一条**路由的内容高**恰好 840**（= 900 − 60，正好铺满、
  不溢出）⇒ 缺陷**潜伏不显**。页面被重写变高后，第一次露出是 commit 1 的 963，commit 7 最重。

**机制**（`navkids7.mjs`）：`.app-shell` = `height:100vh` 的 `flex column`；`.top-nav` 只写了
`height:60px`，`flex` 仍是默认 **`0 1 auto` ⇒ `flex-shrink:1`**；`.content` 是 `flex:1` 且
`min-height:auto` ⇒ 子页 2126px 把它顶高，总高 > 900 ⇒ flex **按可缩权重压缩 `.top-nav`**，
压到它的 **min-content 高**为止（子元素 `.nav-left` 高 35px ⇒ 栏底 36px）：

| | `.nav-left` y | `.nav-right` y | `.top-nav` boxH |
|---|---|---|---|
| `/`（正常） | 12（上下各留 12px） | 18 | 60 |
| `/customer/user/info` | **0**（贴顶，零内边距） | 6 | **36** |

⚠️ **没有内容被裁掉**（`scrollH == clientH`，36 == 35）—— 是**被压扁**：品牌块贴上边与底框线。
截图对照：`美化/d7/crop7-vue-nav-ok.png`（60px）vs `crop7-vue-nav.png`（36px）。

⚠️ **照稿修不了**：稿是普通文档流（`UserCenter-customer.html:124` `.nav-bar{height:60px}`，
整页 `body` 滚动、顶栏随页滚走）；本壳是 `100vh` 定高 + 无滚动容器，两者结构不同。

**落码后复测**（`sticky7.mjs`，1440×900，逐路由滚 0 / ⅓ / ⅔ / 底，读 `.top-nav` 的 viewport-y）：

| 路由 | navH | scrollH | maxScroll | 吸顶表现 |
|---|---|---|---|---|
| `/customer/user/info` | **60** | 2186 | 1286 | 吸到 **840px**，之后滚走（857→y=−17；1286→y=−446） |
| `/merchant/user/info` | **60** | 1877 | 977 | 吸到 **840px**，之后滚走（977→y=−137） |
| `/customer/search` | **60** | 1023 | 123 | **全程吸顶**（maxScroll < 840） |
| `/`（HomePage） | **60** | 1350 | 450 | **全程吸顶** |
| `/customer/cart` · `/customer/msg` · `/merchant/order` | **60** | 900 | 0 | 不滚 |

⇒ **`flex:none` 完全修好了压扁**（全部路由 navH = 60，不再有 36）。

⚠️ **残留（已实测、**未自行修**）**：`sticky` 只吸到 **840px** 就释放。原因是 sticky 的约束
矩形 = **包含块**（`.app-shell` 是 `height:100vh` = 900px 的**固定盒**），元素底边不得越过包含块
底边 ⇒ 吸附上限 = 900 − 60 = **840px**。实测逐条吻合（857 − 840 = 17；977 − 840 = 137）。
**长页滚到底时顶栏仍会滚走** —— 在 `user/info`（2126px）上最明显。

⚠️ **一行可修，但超出「只碰 `.top-nav`」的授权范围 ⇒ 未落，等裁决**：
`.app-shell` 的 `height:100vh` → **`min-height:100vh`**。**已实测验证**（`sticky7.mjs` 改后逐路由
滚到底 `.top-nav` 恒 `y=0`；六条路由的 `scrollH` **逐值不变** 2186 / 1877 / 1023 / 900 / 1350 / 900
⇒ **零布局回归**），验证完**已还原**成授权形态（`height:100vh`）。

⚠️ **影响 commit 8/9**：`HistoricalOrders` / `FriendRoom` 同为壳内右栏页 —— **`flex:none` 已生效
⇒ 不会再被压扁**；但若两页高度 > 900，**顶栏会在滚到 840px 后释放**（同上残留）。

---

## 二、待落清单（**新稿 44 条 − 已落 22 条 = 22 条**，**另加老稿 3 条**，落各页 commit 时逐条核）

> 全部**照落 UI**。「档」列写的是**归属**，不是「本笔做不做功能」。
> **commit 3 的 4 条不在这个数里** —— 它们来自**老稿 `主页改造.html`**（口径 6 的主页例外），见 §一。
> ⚠️ **commit 8 / 9 的 3 条也不在这个数里** —— 它们来自**老稿 `子页面改造.html`**（口径 6 v2 的两页例外）。
> ⇒ 本 §二 共 **22（新稿）+ 3（老稿）= 25 条**，另 §一 已落 24（含老稿 4）。
>
> ⚠️ **数法以「新稿 44」为唯一基准**（`grep -rc data-slot= docs/美化/pages/*.html` 可复核，实测 44）。
> 本表之前的表头写「10 页 / 36 条」，但**逐段相加是 40** ⇒ 那个 36 本就对不上，别再用它做减法。
> 现按实测重算：44 − 已落 22（`GoodsSelect` 3 + `ChatRoom` 1 + `ShoppingCart-customer` 4 +
> `OrderCenter-merchant` 的购物车面 2 + 同页销售面 5 + **commit 7 的 7**）= **22**。
>
> ⚠️ **两个「已落」数字口径不同，别互相减**：本表的 **22 是「新稿里的条数」**；
> §五 首条 grep 的 **24 是「源码里 `data-slot=` 出现的次数」**（`GoodsSelect` 3 + `ChatRoom` 1 +
> `ShoppingCart` 4 + 老稿主页 4 + `OrderCenter` 5 + **`UserInfoPage` 7** = 24，实测）。
> 差在 `OrderCenter-merchant` 购物车面的 2 条**与 `ShoppingCart.vue` 共用同 2 处模板** ⇒ 稿 22 条
> 只对应源码 20 处（新稿）+ 4 处（老稿）。

### commit 6 —— `OrderPane.vue` ↔ `pages/OrderCenter-merchant.html`（**0 条**）

⚠️ `OrderPane.vue` 是**薄壳包装**（按 `meta.orderTab` 在 `OrderCenter.vue` 与 `ShoppingCart.vue`
之间二选一），本身**不承载任何 UI** ⇒ 销售面的 5 条已随 commit 5 落进 `OrderCenter.vue`、
购物车面的 2 条已随 commit 4 落进 `ShoppingCart.vue`。commit 6 名下**没有新的 `data-slot` 落点**。

⚠️ **本笔的定位 = 验证笔，非代码笔。** `OrderPane.vue` **零代码 delta**（纯包装组件，
连 `<style scoped>` 都没有 ⇒ D 笔边界「`<template>` + `<style scoped>` 重写」在本文件**无可改之处**）。
产出的是一条**契约验证**：commit 4（`ShoppingCart.vue` +505/−180）与 commit 5
（`OrderCenter.vue` 411 → 844）把**两个子页整个重写**之后，包装层那条
`orderTab` / `showSwitch` / `@toggle` 契约**无回归** —— **19 项断言全绿**
（① 4 / ② 4 / ③ 2 / ④ 2 / ⑤ 3 / ⑥ 4）。逐条结果见 commit 6 的 message。

### commit 7 —— `UserInfoPage.vue` ↔ `pages/UserCenter-customer.html` + `UserCenter-merchant.html`（7 条 · **已落**）

⚠️ **7 条已全部落地，逐条台账移入 §一 commit 7**（#18–#24：稿行、N、归属、Vue 落点）。
此处**不再复制那张表**，避免第二份真相（同 §四 的处理）。留在本节的只有两件事：
1. 该 7 条**已不在本「待落清单」里**，上表头的 44 − 22 = 22 已扣掉；
2. ⚠️ **编号顺延**：commit 7 落地早于 commit 8/9 ⇒ 取 #18–#24，原预占 #18–#20 的
   commit 8/9 **顺延为 #25–#27**（见下节）。

### commit 8 / 9 —— `HistoricalOrders.vue` / `FriendRoom.vue` ↔ **老稿** `子页面改造.html`（3 条）

⚠️ **基线已于 2026-10-07 二次改判（口径 v2）**：`pages/` 里**没有**这两页的新稿 ⇒ **回到老稿**。
（v1 曾记「已作废、改以 `pages/` 内的对应段落为准，最可能 `UserCenter-customer.html` 内，待定位」
—— 那条指向**已作废**，不必再找。`子页面改造.html` 按段生效：`:538`/`:560`/`:593` 仍弃用，
`:630`/`:633`/`:787` 恢复有效。）

| # | `data-slot` | 稿行 | 稿里的形态 | N | 归属 | 落点 |
|---|---|---|---|---|---|---|
| 25 | 预留 · 状态枚举 | `:630` | `<th class="c-st" data-slot="…">状态</th>`（**有文字**） | 稿未标 | `C3〈订单〉` | `HistoricalOrders.vue` 表头 |
| 26 | 预留 · 申请退货 | `:633` | `<th class="c-act" data-slot="…"></th>`（**完全空**） | 稿未标 | `C3〈订单〉` | `HistoricalOrders.vue` 表头 |
| 27 | 预留 · 备注 / 删除好友（friendUp / friendDe 已有端点） | `:787` | 好友卡上的 `<button class="btn-text">私信</button>` | 稿未标 | `C3〈消息〉` | `FriendRoom.vue` 好友卡 |

> ⚠️ **编号说明（已顺延两次）**：这三条原在 v1 里编作 **#13–#15**（当时 §一 只到 #12）；
> commit 5 落地后 §一 占用了 #13–#17 ⇒ 按全表**升序重编**为 **#18–#20**；
> **commit 7 落地后**（早于本节）§一 又占用了 #18–#24 ⇒ 再顺延为 **#25–#27**。
> 三次调整**内容均一字未改**，只动序号。

**合计 3 条，全落；0 条只登记。**

⚠️ **老稿这 3 条都没有 `title`**（只有裸 `data-slot`，与 commit 3 的 `主页改造.html` 同款）
⇒ Vue 的 `title` 是本笔补的**归属说明**，不是「逐字取稿」（口径 4 在此不适用）。

⚠️ **老稿自身的两处「表里不一」，照稿落、不改稿**（落 commit 8/9 时逐条登记）：
1. `:633` 表头列 `class="c-act"` 挂的是「**申请退货**」，而表体 `:754` 同一列渲染的是
   「**复制商家 ID**」按钮 —— **表头说退货、表体说复制**。Vue 照稿：表头落退货预留位，
   表体现有实现（复制商家 ID）原样保留。
2. `:787` 的按钮文字是「**私信**」，而挂的 `data-slot` 说的是「**备注 / 删除好友**」——
   Vue 照稿：按钮是私信，预留位是备注/删除好友。
   ⚠️ 且该 `data-slot` 在稿里**只挂在第一张好友卡上**（`:787` 的 `${i === 0 ? … : ''}`，
   稿注 `:777-779` 自写「虚线只标第一格 —— 12 格全标会糊成一片」）⇒ 稿里的**角标只标一处**是
   预览产物；Vue 落法见 commit 8/9 的实落（与「角标不落」同一条口径：角标不进 Vue）。


### commit 10 —— `GoodsAdd.vue` ↔ `pages/GoodsAdd.html`（7 条 · 归属均 `C3〈商品〉`）

| `data-slot` | 稿行 | N |
|---|---|---|
| 预留 · 划线原价 / 折扣（动现有代码 5 处 · 超档） | `:386` | 5 |
| 预留 · 库存（动现有代码 5 处 · 超档） | `:396` | 5 |
| 预留 · 多图（图集）（动现有代码 ≥4 处 · 超档） | `:413` | ≥4 |
| 预留 · 详情富文本（动现有代码 6 处 · 超档） | `:429` | 6 |
| 预留 · 多规格矩阵（动现有代码 ≥4 处 · 超档） | `:452` | ≥4 |
| 预留 · 服务承诺勾选（动现有代码 ≥4 处 · 超档） | `:469` | ≥4 |
| 预留 · 保存草稿（动现有代码 ≥4 处 · 超档） | `:484` | ≥4 |

### commit 11 —— `GoodsUp.vue` ↔ `pages/GoodsUp.html`（5 条 · 归属均 `C3〈商品〉`）

| `data-slot` | 稿行 | N | ⚠️ 含**在档**项 |
|---|---|---|---|
| 预留 · 筛选 / 搜索（动现有代码 3 处） | `:367` | **3** | **在档**，稿未画 `disabled` 前标「超档」 |
| 预留 · 批量操作（动现有代码 3 处） | `:379` | **3** | **在档** |
| 预留 · 库存 / 销量列（动现有代码 5 / 3 处 · 库存超档） | `:391` | 5 / **3** | 库存超档；**销量在档** —— 一块两档 |
| 预留 · 分页（动现有代码 3 处） | `:414` | **3** | **在档** |
| 预留 · 库存（动现有代码 5 处 · 超档） | `:499` | 5 | 超档 |

### commit 12 —— `LoginPage.vue` ↔ `pages/LoginPage.html`（3 条）

| `data-slot` | 稿行 | N | 归属 |
|---|---|---|---|
| 预留 · 图形验证码（动现有代码 3 处） | `:461` | **3 在档** | `C3〈鉴权〉` |
| 预留 · 记住我 / 忘记密码（各动现有代码 3 处） | `:474` | **3 在档** | ⚠️ 稿未标 `data-future`，随邻项 ⇒ `C3〈鉴权〉` |
| 预留 · 手机号登录（动现有代码 4 处 · 超档） | `:504` | 4 | `C3〈鉴权〉` |

### commit 13 —— `ProductPage.vue` ↔ `pages/ProductPage.html`（7 条）

| `data-slot` | 稿行 | N | 归属 |
|---|---|---|---|
| 预留 · 详情富文本（动现有代码 6 处 · 超档） | `:810` | 6 | `C3〈商品〉` |
| 预留 · 规格参数（动现有代码 6 处 · 超档） | `:815` | 6 | `C3〈商品〉` |
| 预留 · 累计评价（动现有代码 ≥4 处 · 超档） | `:829` | ≥4 | `C3〈商品〉` |
| 预留 · 原价 / 折扣（动现有代码 5 处 · 超档） | `:936` | 5 | `C3〈商品〉` |
| 预留 · 促销 / 优惠券（动现有代码 5 处 · 超档） | `:943` | 5 | `C3〈促销〉` |
| 预留 · 加入购物车（动现有代码 3 处） | `:979` | **3 在档** | `C3〈购物车〉` |
| 预留 · 店铺主页（动现有代码 4 处 · 超档） | `:994` | 4 | `C3〈店铺〉` |

---

## 三、稿外死控件（稿画了、但**没挂 `data-slot`** 的可点元素）

⚠️ 这一节**不在** `grep data-slot` 的对账口径里 —— 判据① 的要求是「每个可点元素要么**绑事件**、
要么 `disabled` + tooltip + **登记归属**」⇒ 这些元素**登记在本文**、但**不加 `data-slot`**
（加了会让「Vue 里 data-slot 条数」与「稿里条数」对不上，污染 §五 的对账）。

### commit 3 —— `HomePage.vue`（老稿 `主页改造.html`）

| 稿行 | 元素 | 稿里的状态 | Vue 处置 | 归属 |
|---|---|---|---|---|
| `:744-753` | `.subnav` 六项（全部商品分类 / 今日秒杀 / 领券中心 / 品牌专区 / 会员中心 / 客户服务） | `<a>`，无 href、无事件 | 六枚 `<button disabled>` + `title` → `HomePage.vue:30-35` | 无路由 ⇒ `C3〈待定〉` |
| `:769` | `.cats-more`「查看全部 →」 | `<div cursor:pointer>`，无事件 | `HomePage.vue:51` | 无落点 ⇒ `C3〈搜索〉` |
| `:811` | `.flash-h .more`「全部秒杀 →」 | `<a>`，无事件 | `HomePage.vue:125` | 无落点 ⇒ `C3〈促销〉` |
| `:823` | `.rec-h a`「查看全部」 | `<a>`，无事件 | `HomePage.vue:149` | 无落点 ⇒ `C3〈搜索〉` |
| `:917` | `.card-buy`（加购圆钮，`CART_SVG`） | `<button>`，稿里也无事件（稿自己的 mock） | 10 枚 `<button disabled>` + `title` → `HomePage.vue:169` | `C3〈购物车〉` |

⚠️ **`.subnav` 为什么落在 HomePage 而不是壳**：`AppShell.vue:225-226` 自注「稿的 `.subnav a` 属
**二级导航**、不属顶栏」⇒ C 笔（壳笔）**有意没落它**，壳里至今只有 `.top-nav`。它是**页级**带子，
所以本笔落在首页顶部。⚠️ 若后续裁定它归壳，本块连同 `.subnav*` 四条规则整体迁走。

⚠️ **`HomePage.vue` 里另有 3 处「可点元素照稿保留真事件」**（不是死控件，登记备查）：
`.cats li`（`@click="searchByType"` → `HomePage.vue:46`，稿 `:761-768` 是静态 `<li>`）、
`.dots i`（`@click="goToSlide"` → `HomePage.vue:85`，稿 `:782` 是静态装饰）、
`.card`（`@click="toGoodsDetail"` → `HomePage.vue:153`，稿 `:913-920` 的 mock 卡）。

### commit 4 —— `ShoppingCart.vue`（`pages/ShoppingCart-customer.html`）

| 稿行 | 元素 | 稿里的状态 | Vue 处置 | 归属 |
|---|---|---|---|---|
| `:619` / `:641` / `:551` | 勾选框 `.ck.on`（行勾 / 全选本店 / 吸底全选） | `<button disabled title>` —— **稿自己就没挂 `data-slot`** | 3 枚 `<button class="ck on" disabled>` + `title` → `ShoppingCart.vue:89` / `:100` / `:202` | `C3〈购物车〉` |
| `:627` / `:629` | `.b` − / +（数量加减） | 同上，`disabled` + `title`、无 `data-slot` | 2 枚 → `ShoppingCart.vue:113` / `:115`（每行各一组，随行数） | `C3〈购物车〉` |
| `:643` | `.visit`「进店逛逛 ›」 | `<a>` + `title`，无 `disabled`、无 `data-slot` | `<button class="visit" disabled>` → `ShoppingCart.vue:92`（每店铺组各一枚） | `C3〈店铺〉`（与 `ProductPage.html` 商家卡同一缺口） |
| `:559` | `.go`「去结算（N 件）」 | `<button disabled id="goBtn">`，文案由稿 JS `:665` 填 | `<button class="go" disabled>` → `ShoppingCart.vue:213`（**在外层 `.paybar` 的 `data-slot` 之内**，故不再单独挂 `data-slot`） | `C3〈购物车〉` |
| `:582` | `.go2`「去挑商品」 | `<a>`，**稿里既无 `disabled` 也无 `title`** —— 唯一一枚裸控件 | `<button class="go2" disabled>` + 补 `title` → `ShoppingCart.vue:59` | `C3〈购物车〉`（搜索页已有，缺本页跳转路由） |

⚠️ **本页与前几页不同的一点**：勾选框 / 数量 / 进店逛逛 / 去结算 **稿自己就没挂 `data-slot`**
（不是本笔漏挂）—— 它们在稿里以 `disabled` + `title` 呈现，正是「稿外死控件」的原始形态。
`.go2` 是**唯一一枚稿里连 `disabled` 都没有的裸控件**（稿只当它静态文案画），Vue 补 `disabled` + `title`。

⚠️ **本页稿外「活」控件（照旧保留真事件，登记备查）**：
1. `showSwitch` 两态开关 —— `ShoppingCart.vue:8-19`，`@click="handleToggle"`。
   **稿里没有它**（客户稿 `:14-15` 明写「客户侧不画」）。它是**商家宿主的回程**：
   `/merchant/order/cart` 走 `OrderPane.vue:3` 的 `v-else`，删掉它商家就无法切回订单面 ⇒
   与 §一 commit 4 的「选 1（共用骨架 + `showSwitch` 分叉）」裁决配套，**不是新增功能**。
2. `.op button` 付款 / 收货 —— `ShoppingCart.vue:127-139`，`@click="handlePay(order)"` /
   `@click="handleReceive(order)"`，`:disabled` / `:class` **逐字保留原实现**（见 §一 commit 4 注 1）。

---

### commit 5 —— `OrderCenter.vue`（`pages/OrderCenter-merchant.html` 销售面）

⚠️ 本页与 commit 4 **相反**：稿的销售面**自己就把 `disabled` + `title` 写全了**（`:707-713`
`:720-725` `:740-742` `:867`）—— 这些是**照稿直落**，不是本笔补的。真正属于「稿画了却没挂
`data-slot`、也没给 `disabled`」的只有下面三类。

| 稿行 | 元素 | 稿里的状态 | Vue 处置 | 归属 |
|---|---|---|---|---|
| `:693-701` | `.tab` ×6（全部/待发货/已发货/已完成/退款中/售后） | `<button class="tab">` —— **无 `disabled`、无逐枚 `title`**（只有整块 `data-slot` 上那句） | 6 枚 `<button class="tab" disabled>` + **补**逐枚 `title`（按稿 `:692` 的分档写）→ `OrderCenter.vue` 的 `<nav class="tabs">` | `C3〈订单〉` |
| `:822` | `.empty .go2`「去商品管理」 | `<a class="go2">` —— **裸控件**：无 `disabled`、无 `title`、无 `data-slot` | `<button class="go2" disabled>` + 补 `title` | `C3〈订单〉`（商品管理路由已有，本笔功能量恒为 0 故不接线） |
| `:674` | `nav.crumb` 的「首页」 | `<a>首页</a>` —— **无 `href`** | 落成 `<span>首页</span>`（**不画死控件**，与 `ShoppingCart.vue:25-33` 同款处置） | — |

⚠️ **两条「描边 vs 灰字」的样式补充**（稿里没有，本笔补，否则光标撒谎）：`.tab[disabled]:not(.on)`
与 `.fld input[disabled]` / `.btn[disabled]` / `.ck[disabled]` / `.pager .pg[disabled]` 的
`cursor:not-allowed`。⚠️ `.tab` 那条**必须带 `:not(.on)`** —— `.tab.on` 与 `.tab[disabled]`
特异性同为 (0,2,0)，后者在后 ⇒ 不加选择器「全部」的选中色会被禁用的灰压掉
（`GoodsSelect.html` 的 `.chip.on` 踩过同一个坑，见 `progress.md §5-AI-B`）。

⚠️ **`.pager .pg.on`「1」是唯一一枚「照稿不禁用」的按钮**（稿 `:741`：`<button class="pg on" title="当前页">1</button>`
—— 有 `title`、**没有** `disabled`）。Vue **照稿保留 enabled**：它没有 `@click` ⇒ 点击无副作用；
若给它补 `disabled`，`.pager .pg[disabled]`（同为 (0,3,0)、在后）会压掉 `.pg.on` 的描边色，
把「当前页」画成「不可用」—— **禁用反而改坏了稿的语义**。

⚠️ **稿外「活」控件（照旧保留真事件）**：
1. `.status-select`（首页 / 历史订单 二选一下拉）—— 稿 `:53` 的 ★ 是把它**收紧成 6 个标签页**，
   但 6 枚 tab 全 `disabled` ⇒ 保留（**用户裁决 A**，理由与代价见 §一 commit 5）。
2. `.trow .send`【发货】—— 稿 `:876` 自己标了「现有端点 `order.orderDeliveryIfUp`
   （endpoints.js:58）——本页唯一不禁用的按钮」⇒ **本页唯一真事件**，
   `:disabled` / `:class` / `@click` 逐字保留原实现。
3. `showSwitch` 两态开关 —— `OrderCenter.vue` 顶部，`@click="handleToggle"`，**稿的两个 pane 都没画它**
   （同 `ShoppingCart.vue:8-19`，是 `/merchant/order/cart` 的回程）；与 commit 4 是同一条契约。

⚠️ **稿自身的一处「表里不一」，照稿落、不改稿**：`@media (max-width:1120px)` 里
`:607-609` 给 `.stat:nth-child(3)` / `(4)` 补了 `border-top:1px solid var(--line)`，
而稿 `:371-373` 在 `.stats` 上刚写完「四条线（`border-top`/`border-bottom`/格间 `border-left`×3）
**一并删掉**」（理由：面之外再叠线是同一种手段跨层级复用；同级用间距、线留给分组）。
⇒ Vue **照稿落**（窄屏那两格确实会多一条线），作为稿子自身的矛盾登记在此，不擅自删。
（同类先例：§二 commit 8/9 记的 `子页面改造.html` 两处「表头说退货、表体说复制」。）

---

### commit 7 —— `UserInfoPage.vue`（`pages/UserCenter-customer.html` + `UserCenter-merchant.html`）

⚠️ 本页的 `.tiles` / `.svc` **整块落在带 `data-slot` 的 `<section>` 之内** ⇒ 逐格**不再挂
`data-slot`**（挂了会让 §五 的对账双计）。下表把两面的 8 类死控件并成一表。

| 稿行 | 元素 | 稿里的状态 | Vue 处置 | 归属 |
|---|---|---|---|---|
| 客户 `:336` | `.more`「全部订单」 | `<button class="more" disabled title="待 C3〈订单〉——无「全部订单」列表端点">` —— **disabled + title 稿里都有** | 照落 → `UserInfoPage.vue:162` | `C3〈订单〉` |
| 客户 `:350` / `:360` | `.more`「查看全部」/「清空足迹」 | `<button class="more" disabled>` —— **有 `disabled`、无 `title`** | 照落 + **本笔补 `title`**（补的是归属说明，非逐字取稿）→ `:183` / `:196` | `C3〈个人中心〉` |
| 客户 `:339-343` | `.tiles` 四格 | `<button disabled title="待 C3〈订单〉——无按状态筛选端点">` —— 稿里都有 | 照落（4 枚）→ `:164` | `C3〈订单〉` |
| 客户 `:374-378` | `.svc` 四项 | `<a title="无端点（N=4）">` —— **无 `href`、无 `disabled`**（裸控件） | 落 4 枚 `<button disabled>` + `title` 逐字 → `:214` | `C3〈个人中心〉` |
| 商家 `:324` | `.more`「去订单中心」 | `<button class="more" disabled title="订单中心在顶栏（/merchant/order），此处不做重复入口">` —— 稿里都有 | 照落 → `:80` | `C3〈订单〉` |
| 商家 `:327-331` | `.tiles` 四格 | `<button disabled title="待 C3〈订单〉——…">` —— 稿里都有 | 照落（4 枚）→ `:83` | `C3〈订单〉` |
| 商家 `:355-359` | `.svc` 四项 | `<a title="无端点（N=4）">` —— **无 `href`、无 `disabled`**（裸控件） | 落 4 枚 `<button disabled>` + `title` 逐字 → `:128` | `C3〈商家中心〉` |
| 商家 `:339` | `.more`「查看全部」 | `<button class="more" title="…">` —— **无 `disabled`、无 `href`** | **不走 `disabled` 路线**：判据判为「指向已存在路由」⇒ 落**真 `<router-link>`** → `:103-107` | 见 §一 commit 7 |

⚠️ **8 项 `.svc` 为什么全是 `disabled` 按钮、不走「真链接」那一半**：这 8 个入口
（收货地址 / 账号与安全 / 退款·售后 / 帮助中心 / 资质认证 / 结算账户 / 经营规则 / 商家帮助）
**全仓没有任何一条对应路由** ⇒ 不满足「指向已存在路由」的判据。**对照组**正是同页商家 ④ 的
「查看全部」：它指向 `router/index.js:117` **确已存在**的 `/merchant/user/list` ⇒ 落真链接。
⚠️ 两条判据（编的数落 `—` / 已存在路由落 router-link）的**统一登记在 §一 commit 7**。

⚠️ **本页两处 `<a>` → `<button>` 的结构改写**（稿画 `<a>`、Vue 落 `<button>`）：
`.svc` 那 8 项，以及商家 ④ 那枚（改落 `<router-link>` ⇒ 仍渲染成 `<a>`，但**带 href**）。
⇒ 连带 `<style scoped>` 里 `.svc a` → `.svc button`（稿 `:226` / `:212`）。

⚠️ **两处「`hover` 会点亮禁用项」的补丁**（稿里没有，本笔补，理由同 §三 commit 5 的 `.tab`）：
稿 `.tiles button:hover{background:var(--brand-soft)}`（客户 `:194` / 商家 `:182`）与
`.svc a:hover b{color:var(--brand-ink)}`（客户 `:228` / 商家 `:214`）**都不带 `:not([disabled])`**
—— 而本页这些元素**全是 `disabled`** ⇒ 照抄会让「不可点」的东西在悬停时变色（**光标撒谎的样式版**）。
Vue 补为 `.tiles button:hover:not([disabled])`（`UserInfoPage.vue:437`）与
`.svc button:hover:not([disabled]) b`（`:475`）。
⚠️ **`.sec-h .more` 不用补** —— 稿**自己就有** `:209` / `:197` 那对 `:not([disabled])` 规则，
Vue 逐字照落（`:456-457`），只多一条 `text-decoration:none`（因为 `.more` 在本页可能是
`<router-link>` ⇒ 渲染成 `<a>`、UA 会给下划线）。

⚠️ **本页稿外「活」控件（照旧保留真事件，登记备查）**：
1. ① 身份卡「更换头像照片」`<button @click="chooseImage">` + 隐藏 `<input type="file" @change="doUpload">`
   —— **既有真功能**（`UserInfoPage.vue:30` / `:302`），稿 `:290-299` 同样画了这两件。
2. ⑥ 账号设置的三个 `v-model` + `<button @click="saveUserInfo">` —— 既有真功能，逐字保留。

---

## 四、老稿 10 条（`主页改造.html` + `子页面改造.html`）

逐条登记见 **`progress.md:1636-1649`**（表 #1–#10），此处**不复制**，避免第二份真相。

⚠️ **2026-10-07 稿基线口径 v2（口径 6）后的状态**：
- **`主页改造.html` 的 4 条**（`progress.md` 表的 #1 功能入口 / #2 券包位 / #3 信任位 / #10 秒杀位）
  ⇒ 用户改判「**一律落**」⇒ **commit 3 全落**，见 §一。**`progress.md` 那张表头「有归属的才放」
  已被本次改判覆盖**。
- **`子页面改造.html` 的 6 条** ⇒ ⚠️ **v2 改为「按段生效」**（v1 曾整份弃用，已作废）：
  - **#4 会员等级/积分 `:538` / #5 账户资产 `:560` / #6 账号与安全 `:593`（UserInfoPage 面）
    ⇒ 仍弃用**：功能面已由**新稿** `pages/UserCenter-customer.html:323`（我的资产）、
    `:371`/`:376`（服务 → 账号与安全）承担 ⇒ 见 §二 commit 7。
  - **#7 状态枚举 `:630` / #8 申请退货 `:633`（HistoricalOrders 面）/ #9 备注·删除好友 `:787`
    （FriendRoom 面）⇒ v2 恢复有效**，按老稿落 ⇒ **落点见 §二 commit 8 / 9**
    （v1 曾记「待在新稿里定位」，该指向已作废）。
  - ⚠️ `progress.md:1636-1649` 作为**历史裁决记录**原样保留（不属本台账的修改范围）。

---

## 五、对账方法（验收时跑）

```bash
# Vue 里实落的条数（应 = 台账「已落」条数 = 3 + 1 + 4 + 4 + 5 + 7 = 24，2026-10-07 实测 24）
grep -rn 'data-slot=' Mall-management-web/mall-management-system/src/views/ | wc -l
#   逐文件：GoodsSelect 3 / ChatRoom 1 / ShoppingCart 4 / HomePage 4（老稿）/ OrderCenter 5 / UserInfoPage 7
# 新稿里的总条数（应 = 44；**老稿另计**：主页改造.html 4 条 + 子页面改造.html 3 条，都不在这个数里）
grep -rc 'data-slot=' docs/美化/pages/*.html | awk -F: '{s+=$2} END{print s+0}'
# 稿外死控件（§三）：不带 data-slot 的 disabled 元素 —— 逐条与 §三 表对齐
grep -rn 'disabled' Mall-management-web/mall-management-system/src/views/ | wc -l
```

⚠️ **第二条 grep 不含「老稿 10 条」** —— 那 10 条不在 `docs/美化/pages/` 下：`主页改造.html`
（4 条，见 §一 commit 3）与 `子页面改造.html`（3 条**有效**，见 §二 commit 8/9；另 3 条已弃用）
都在 `docs/美化/`。要数它们得单独跑：
```bash
grep -c 'data-slot=' docs/美化/主页改造.html docs/美化/子页面改造.html
```
⚠️ `子页面改造.html` 的**裸命中是 6**（`:538` `:560` `:593` `:630` `:633` `:787`），
但只有 **3 条有效** —— 前三条属 UserInfoPage 面、已弃用，别拿裸数当落点数。

⚠️ **第一条 grep 是「源码条数」不是「渲染条数」**：`ShoppingCart.vue` 有 2 条走
`:data-slot="showSwitch ? null : '…'"`（见 §一 commit 4），源码里数 4，运行时按宿主分别渲染
**4（客户有商品）/ 0（客户空车）/ 2（商家）**。
`OrderCenter.vue` 的 5 条是**无条件**的，但**空态下一条都不渲染**（见 §一 commit 5）
⇒ 运行时按视图：**5（有数据，两档宽度同）/ 0（空态）**。
`UserInfoPage.vue` 的 7 条**源码里也是无条件挂**，但两面在**互斥的 `v-if` / `v-else` 分支**里
（见 §一 commit 7）⇒ 运行时按角色：**4（客户）/ 3（商家）**，**永不 → 7**。
（同理 `HomePage.vue` 的 4 条是**老稿主页**的，不在「新稿 44」里。）
