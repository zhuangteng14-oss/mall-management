# 界面优化笔 · 设计稿

> ⚠️ **先读这段再动手。这些 HTML 不是可直接移植的代码，是设计稿。**

## 这是什么

`界面优化笔`（`docs/agent/progress.md` 多处以「不动界面样式（界面优化笔）」为前置的那一笔）的
**设计稿与效果对照**。产物形态 = **参考方向（高保真）**：

- ✅ 有真实 DOM 结构、真实交互（标签切页 / 预留位开关 / 配色切换）
- ✅ 有完整的设计 token（在 `主页改造.html` 的 `:root` 里，40+ 个变量）
- ❌ **不是 Vue 组件** —— 没有 props / slots，不能 `import`
- ❌ **类名与项目不一致** —— 设计稿用 `.p-band` / `.ords` / `.msgr` / `.frt`；
  项目用 `.card` / `.order-card` / `.friend-card`。直接粘进组件编译不过
- ❌ **不是独立页面** —— 设计稿自带顶栏、左菜单、页脚；项目是 `AppShell` 壳 + 子路由，
  照搬会带上重复的壳

## 文件清单

| 文件 | 内容 |
|---|---|
| `主页改造.html` | 商城主页。`:root` 里是全套设计 token（**落地时的源头**） |
| `子页面改造.html` | 用户中心四页（个人信息 / 历史订单 / 消息 / 好友）+ 好友空态。顶部标签切页；「显示预留位」按钮标出未来的功能位 |
| `色系.html` | 配色推导过程（OKLCH 取值、ΔE00 台阶、对比度校验） |
| `主页.png` / `个人页.png` / `消息页.png` | **改造前**的原版截图（对照用） |
| `shots/` | **改造后**的截图。`s-*` = 各页常态，`slot-*` = 打开预留位，`s-uc-1080` = 窄屏响应式 |

## 设计 token 在哪

`主页改造.html` 的 `<style>` 顶部 `:root { ... }`。落地第一步就是把它抽成
`Mall-management-web/mall-management-system/src/assets/tokens.css`。

⚠️ 项目**目前零 token 层**：`App.vue` 只有一条 `*{margin:0;padding:0;box-sizing:border-box}` reset，
没有 `src/assets/`，15 个 view 全用 `<style scoped>` + 硬编码颜色
（`#007bff`×17 / `#00bcd4`×8 / `#028fa8`×7 / `#ff4d4f`×5 + 一串灰）。

> ⚠️【界面优化笔 A 笔订正 2026-10-06】上面这段有 2 处过时，实测如下（原文保留不改）：
> （1）`#00bcd4` 实测 **7** 次，不是 8（其余三色 17 / 7 / 5 复核无误）；
> （2）「15 个 view 全用 `<style scoped>`」不成立：`views/` 下共 **16** 个 `.vue`
> （含子目录 `views/merchant/OrderPane.vue`），带 `<style>` 的 **14** 个、其中 `<style scoped>` 的 **13** 个
> —— `HomeTab.vue` 与 `merchant/OrderPane.vue` 无 `<style>`，`LoginPage.vue` 有 `<style>` 但不是 scoped。
> 另：`src/assets/` 已存在（见下节按语），`App.vue` 的那条全局 reset 已收敛进 `tokens.css`。

可行性关键：**`<style scoped>` 里照样能读全局 CSS 变量**（自定义属性会继承，scoped 只加属性
选择器、不隔离变量）。所以 token 放全局、组件样式照旧 scoped，两者不冲突。

## 落地顺序

1. **铺 token** —— 新建 `src/assets/tokens.css` + `main.js` 加一行 import。
   纯新增，没有任何组件在用它 ⇒ **零视觉回归风险**。验证 `npm run build`。
   > ⚠️【A 笔订正 2026-10-06】**本节已完成**，两处与本节预判不同：
   > （1）`main.js` 的 import 是**同行追加**到第 2 行（不是新增一行）—— 为保住 `main.js` 被全仓引用的
   > `:21`/`:28`/`:49` 行号（沿用该文件 `:27` 的既有做法）；
   > （2）另建了 `src/assets/fonts/`（`noto.css` + 202 个 woff2，黑体/宋体两族自托管，离线可用），
   > 并把 `docs/美化/*.html` 那两条指向 `./node_modules/` 的字体 `<link>` 改指过来（原链路本机实测 404）。
2. **逐页翻译**（一页一个 commit）——
   `AppShell.vue`（顶栏，全站共用）→ `HomePage.vue` → `UserCenter.vue`（左菜单）→
   `UserInfoPage.vue` → `HistoricalOrders.vue` → `FriendRoom.vue` → `ChatRoom.vue`。
   每页把设计稿的类名换成项目已有的，或直接替换整个 `<style scoped>`。
3. **视觉回归** —— `npm run serve` 后用 `shots/` 里的图对照。

## 落地前必须先裁的两件事

1. **顶栏搜索框** —— 设计稿把搜索框提到了壳顶栏（四个子页顶栏因此逐字一致）。
   代价：`HomePage.vue` 的 `.search-section` 要删掉，否则主页出现两个搜索框。
   ⚠️ **已裁（C 笔，2026-10-07）：壳顶栏**不加**搜索框** ⇒ 本条要裁的**作废**（`.search-section` 不用删）。
   依据不是「设计稿没有」，而是**稿子自己两套口径**：基准的壳内稿（`ChatRoom` / `GoodsAdd` / `GoodsUp` /
   `GoodsSelect` / `OrderCenter-merchant` / `ShoppingCart-customer`）顶栏**逐字相同且无搜索框**，只有
   `UserCenter-customer.html` / `UserCenter-merchant.html` 两份画了 —— 取基准 6 份，见下方第 4 条。
2. **账户中心的新模块没有后端** —— `UserInfoPage` 设计稿里的
   **资产总览**（积分 / 优惠券 / 收藏 / 浏览足迹）和 **账号与安全**（手机号 / 密码 / 实名认证）
   在 `src/api/endpoints.js` 里查不到对应端点。三条路：
   (a) 先只搬有依据的部分（身份条 + 表单），那两块等后端；
   (b) 搬视觉、数据写死（答辩时是假的）；
   (c) 先走后端（按 `agent.md` 追加式 DDL + 新端点）再同步。

   依据分级：`积分` / `手机号` / `登录密码` 有依据（ER 会员实体有「会员积分」、
   用户实体有「手机号」，`login` 表有密码）；`优惠券` / `收藏` / `浏览足迹` / `实名认证`
   是**提议**，尚未落任何文档。

   同理 `主页改造.html` 里的 **秒杀位**（`selection-status.md §9` 已列为「秒杀演示」【口述】，
   依赖 B1+B2，且前提「`goods` 无库存列」当前不存在）与 **券包位**（`§9` 里没有，纯提议）。

## 生成工具在哪

截图 / 配色推导的脚本**不在本目录**（它们是工具不是文档，且依赖 `node_modules`）。
在 `桌面/软件工程/美化/`：`shot.mjs`（用本机 Edge 截图）、`palette.mjs`、`system.mjs`、
以及 B 笔的三支验收探针 `check-drafts.mjs` / `check-badges.mjs` / `check-contrast.mjs`（见下节）。

```bash
cd 桌面/软件工程/美化
node shot.mjs --url "../期末项目/docs/美化/子页面改造.html" --out t.png --w 1440 --click "button[data-page=uc]"
```

---

# `pages/` —— 无设计稿的 10 个页面（界面优化笔 B 笔，2026-10-06）

A 笔之前，`docs/美化/` 三份稿只覆盖了「主页 + 用户中心四页」。**剩下 10 个页面没有设计稿**，
`pages/` 就是补的这一层：**一个 Vue 组件一份 HTML**，放在比 `docs/美化/*.html` 深一层的 `pages/` 下。

## 文件清单（文件名 = 源组件名）

| 稿 | 宿主路由 | 现状行数 | 本稿是什么 |
|---|---|---|---|
| `ShoppingCart-customer.html` | `#/customer/cart` | `ShoppingCart.vue` 395 | 目标版购物车（**现有实现其实是订单列表**，见下「语义变化」） |
| `OrderCenter-merchant.html` | `#/merchant/order` + `#/merchant/order/cart` | `OrderCenter.vue` + `OrderPane.vue` | **一稿两 pane**：订单处理 / 商家购物车 |
| `UserCenter-customer.html` | `#/customer/user` | `UserCenter.vue` | 客户个人中心 |
| `UserCenter-merchant.html` | `#/merchant/user` | `UserCenter.vue` | 商家个人中心 |
| `GoodsSelect.html` | `#/customer/search` **与** `#/merchant/search` | `GoodsSelect.vue` | 商品搜索（客户 / 商家两个顶栏共用一份，`router/index.js:59` / `:90`） |
| `GoodsAdd.html` | `#/merchant/user/add` | `GoodsAdd.vue` | 商品上架表单 |
| `GoodsUp.html` | `#/merchant/user/list` | `GoodsUp.vue` | 查看商品 / 上下架 |
| `ProductPage.html` | `#/product` | `ProductPage.vue` 804 | 商品详情（**不进壳**，自带顶栏；路由**不带路径参数**，`router/index.js:125`） |
| `LoginPage.html` | `#/login` | `LoginPage.vue` 530 | 登录 / 注册 / 注册成功 |
| `ChatRoom.html` | `#/customer/msg`、`#/merchant/msg`、`#/customer/user/chat`、`#/merchant/user/chat` | `ChatRoom.vue` | **四个挂载点**各一视图（顶栏停靠 ×2 / 个人中心停靠 ×2） |

> ⚠️ **「现状行数」口径**：表里与各稿头注用的是 `readFile().split('\n').length`
> （**末行的换行符也算一行**）⇒ 比 `wc -l` **恒多 1**。实测对照（三份文件末字节**均为换行**）：
> `ShoppingCart.vue` **395 / 394**、`ProductPage.vue` **804 / 803**、`LoginPage.vue` **530 / 529**
> （前者 = 本目录口径，后者 = `wc -l`）。两套都「对」，**改数字前先看这句**。

## B 笔与既有三份稿的三处不同（不是笔误，是刻意的）

1. **不内联 `:root`，改 `<link>` 仓库里真在用的样式表。** 既有三份稿各自内联一份 `:root`；
   `pages/` 这 10 份直接 `<link>` `src/assets/tokens.css` + `fonts/noto.css` + `fonts/mono.css`
   ⇒ **唯一一份 token**、改一处十份跟着变，也顺带证明 A 笔的 token 够用。
   ⚠️ 路径是**三级** `../../../`（`pages/` 比 `docs/美化/` 深一层）。
2. **颜色只有 token，一个 `#hex` / `rgb()` 都不许有**（可 `grep` 复核）⇒ 预留位颜色也从字面量
   改成 `var(--warning)` / `var(--info)`，「没有硬编码颜色」这句是可验的，不是口号。
3. **占位四件套**：每个留白处 = `disabled` + `data-slot` + 角标 + `title`（`title` 里写全归属笔名）；
   `data-future` 另标「目标版应有、但当前后端没有」。开着 `显示预留位 / 目标版` 时，
   黄色 = 预留位、青色 = 缺后端。

## 角标挂哪：**只挂区块级**（这条是踩出来的）

角标是 `position:absolute; top:-11px` 的伪元素，伸在宿主块**外面**。因此两条纪律：

- **不逐行、不逐格挂。** `OrderCenter-merchant.html` 首版是逐行挂的（42 个角标），
  开槽位视图后同一行三四个角标叠成一团、表头两格盖住左边的「订单号 / 商品」。
  现在全稿只剩 6 个（筛选栏整块 / 订单表格整块 / 每行数量块 ×3 / 结算栏整块）。
  逐行的重复控件一律 `disabled` + `title` 注明归属，不挂 `data-slot`
  —— 这条沿用 `子页面改造.html:779` 的既有做法（「12 格全标会糊成一片，标一格就够说明模式了」）。
- **宿主块与其祖先都不许 `overflow:hidden`。** 一裁，角标就剩半行字。`.qty` / `.orders`
  为了圆角原本都写 `overflow:hidden`，已改成「不裁 + 首/末个子元素自带圆角」。
- **窄元素（按钮）加 `.slot-stack`。** 两个角标一个 `left:8px`、一个 `right:8px`，
  宿主一旦比「两段字宽之和」窄就会叠成一段糊字 —— `ProductPage.html` 商家卡里的
  「进店逛逛」只有 90px、两个角标要 250px，1440 截图上直接糊成一行。
  `.slot-stack` 把 `data-future` 改挂到下边（`top:auto;bottom:-11px`），上下错开、与宽度无关。
  只在**确实挂不下**的窄元素上用，不推广到全稿。

## 自检：三支探针（脚本在 `桌面/软件工程/美化/`，只读不写）

| 脚本 | 查什么 |
|---|---|
| `check-drafts.mjs` | `file://` 直开每份稿：三张样式表挂上没有、`document.fonts.size`、`--brand` 计算值、中文字形、占位/视图计数、**加载失败的请求** |
| `check-badges.mjs` | 每个角标的绝对矩形 vs 祖先链上所有 `overflow!=visible` 的盒子 ⇒ 抓「角标被裁」 |
| `check-contrast.mjs` | 每个有文字的元素，沿祖先求实际底色、算 WCAG 对比度 ⇒ 抓「字看不见」 |

后两支都**先验过后置条件才敢用**：把 `overflow:hidden` / 深灰字在运行时注入回去，
确认它们真能报出来，再拿「全绿」当结论。

## 落地前必须先裁的（B 笔新增，接在本文件上面那两条后面）

1. **`ShoppingCart.vue` 现在根本不是购物车** —— 它渲染的是订单列表（含付款 / 收货 / 4 步进度）。
   目标版购物车（数量、小计、勾选、结算）**后端零端点**，属 C3 新增；现有那几件（付款 / 收货 /
   进度 / 日期）应迁往「客户订单页」。`ShoppingCart-customer.html` 头注逐条登记了每一件的去向。
2. **商家订单中心的 Pane 2 要换语义** —— 现状 `/merchant/order/cart` 渲染 `ShoppingCart.vue`、
   数据口是 `merchantId`，实际显示的是「**商家收到的订单**」；目标是「**商家自己作为买家的购物车**」
   ⇒ 数据口 / 身份要变（新表 / 新字段，或复用客户购物车逻辑 + 商家身份），归 C3。
3. **`ProductPage.vue` 不进壳**（R5 仍在），所以 `ProductPage.html` 自带顶栏，
   **没有**壳顶栏那个搜索框。
4. **壳顶栏：B 笔按「现状」画，不是按设计稿画。** 上面第 1 条要裁的那个搜索框只存在于
   `子页面改造.html:504-513` 的设计稿里；**仓库里真在用的是 `src/config/nav.js`** ——
   `CUSTOMER_NAV` / `MERCHANT_NAV` 各自只有「左：商城主页 + 右：三四个链接」，
   **没有任何搜索框**（`AppShell.vue` 的模板里也没有）。两壳的差别只有左项的 `to` 和右起第二项
   （客户「购物车」⇄ 商家「订单中心」）。
   ⇒ `pages/` 里**8 份**壳内稿（除 `ProductPage.html`，及下面点名的两份 `UserCenter-*.html`）
   的顶栏逐字照 `nav.js` 画、**无搜索框**。
   ⚠️ **订正（C 笔，2026-10-07）**：原文写「**所有**壳内稿 …… **十份同源、无搜索框**」，
   **与实测不符**。逐份 `grep -c nav-search`：**8 份命中 0**（`ChatRoom` / `GoodsAdd` /
   `GoodsSelect` / `GoodsUp` / `LoginPage` / `OrderCenter-merchant` / `ProductPage` /
   `ShoppingCart-customer`），只有 `UserCenter-customer.html`（`:127-130` 三条 `.nav-search*`
   规则 + `:275` 的标记）与 `UserCenter-merchant.html`（`:123-126` + `:261`）**各有一处**
   （`flex:1;max-width:520px;height:36px`，即第 1 条那个搜索框）。
   「顶栏照 `nav.js` 画」这个**基线**仍无误，出格的是**这两份 `UserCenter` 稿**（其余 8 份一致）。
   ⇒ 连带订正下面那份「头注里写了这条对照的」清单：它把 `UserCenter-merchant.html`
   也列进「照 `nav.js` 画」的例证，**与该稿自己顶栏里的搜索框相抵**，读时按本条。
   **第 1 条一旦裁定「壳顶栏加搜索框」，改的是 `nav.js` + `AppShell.vue`，
   不是这些稿的顶栏** —— 这正是当初把顶栏抽成 config 的用意。
   （头注里写了这条对照的：`GoodsSelect.html` / `OrderCenter-merchant.html` /
   `UserCenter-merchant.html` / `GoodsUp.html` / `GoodsAdd.html` / `ChatRoom.html`；
   `ProductPage.html` 见上面第 3 条。）

---

# B 笔 v4：判据换向（2026-10-06 第二次修正）

## 换的是什么

v2/v3 的判据是「**现有 Vue 里有什么** → 保留并重画；缺的 → 有归属才占位」。
那条判据的必然产物是「换样式 + 补最扎眼的几个缺」——它照不出**这一页本该长什么样**。

v4 换成「**这一页在正规电商平台里该有什么** → 先列清单，再逐条给难度档」。

**铁证**（用户给的，本笔认账）：v3 的 `ProductPage.html` 只有「图 + 名 + 规格 + 价 + 咨询 + 购入」，
而正规商品详情页还有：面包屑 / 服务承诺条 / 商家卡 / 四 Tab（商品详情·规格参数·累计评价·售后保障）/
评价区 / 推荐区 —— v3 一个都没有。外部依据（PDP 通行解剖：buy box + 信任信号 + 评价 + 推荐 + 政策）：
[Shopify PDP 指南](https://www.shopify.com/kr/blog/what-is-pdp-in-ecommerce) ·
[ChannelEngine：Amazon PDP 解剖](https://www.channelengine.com/nl/blog/amazon-product-detail-page) ·
[Plytix 术语表](https://knowledge.plytix.com/glossary/product-detail-page-pdp/) 。

## 三档难度判据（决定性）

| 档 | 判据 | 画法 |
|---|---|---|
| **真功能** | `src/api/endpoints.js` 的 **34 条端点**里已有 | 直接画，不 `disabled` |
| **占位** | 无后端 **且** 「加它要改的**既有文件**」≤ **3 处** | `disabled` + `data-slot` + 角标 + `title`，**角标文案里写出 N** |
| **只登记** | 无后端 **且** 改既有文件 **> 3 处** | **不画**，只在本表登记 |

> **「动现有代码 N 处」口径**：让这个功能真跑起来需要**修改**的既有文件条数
> （`.vue` / `.java` / `endpoints.js` / `router/index.js`）。
> **新增**的文件（新表、新端点自身、新组件）**不计入** —— 判据原文是「加它动**现有**代码 N 处」。
>
> ⚠️ **下限是 3，不是 2。** 这条是重画到 `GoodsUp` 时才发现的**系统性少算**：
> 一个「需要新端点」的功能，最少要动
> ① `src/api/endpoints.js`（加那一行）② 对应域的 `*Controller.java`（加方法）
> ③ 用它的那个 `.vue`。**`.java` 必须计入** —— 反证：用户把「改状态机加退款态」举为
> **超档**的例子，而它恰好是 `OrderState.java`(1) + `OrderCenter.vue` + `OrderPane.vue` +
> `ShoppingCart.vue` = **3 个 `.vue` + 1 个 `.java`**；若 `.java` 不计入，它正好等于 3 ⇒ 是占位，
> 就自相矛盾了。
> 再往上加：后端还要改 SQL / 参数 ⇒ `+*Service.java` `+*Dao.java`；要加列 ⇒
> **`+1`**（`mall_management_sql.sql` 是 SQLyog dump，或在库里跑 ALTER）；要在第二个页面显示 ⇒ `+1 .vue`。

⚠️ **两条判据打架时以覆盖率优先**：用户逐页清单里**点名要求**的元素，即使 N > 3 **也画成占位**，
   但把 N 原样写进角标（`data-slot="预留 · 优惠券（动现有代码 5 处）"`），让落码时一眼看到代价。

⚠️⚠️ **本笔必须挂账的冲突（重画到第 4 张稿才看清全貌）**：按上面的口径重算，
   「> 3 处」的元素**远不止**当初以为的两处 —— 凡是**要加数据库列**的几乎必然超档：

   | 元素 | 出现 | 动现有代码 | 明细 |
   |---|---|---|---|
   | 划线原价 / 折扣 | 商品详情 | **5** | `mall_management_sql.sql` + `GoodsDao` + `GoodsService` + `GoodsController` + `ProductPage.vue` |
   | 商品详情长文 | 商品详情 | **6** | 同上 + `GoodsAdd.vue`（录入） |
   | 规格参数（品牌/材质/产地） | 商品详情 | **6** | 同上（每项一列） |
   | 累计评价 | 商品详情 | **≥ 4** | 评价表 + 端点 + 详情页 + 我的评价位 |
   | 优惠券领取 | 商品详情 | **5** | 见下「只登记」表的跨页那套 |
   | 库存 | 商品管理 / 上架商品 | **5～6** | `mall_management_sql.sql` + `GoodsDao` + `GoodsService` + `GoodsController` + `GoodsUp.vue`（+ `GoodsAdd.vue`） |
   | 店铺主页（进店逛逛） | 商品详情 | **≥ 4** | 新路由 + 新页 + 商家侧入口 + 商家卡跳转 |
   | 图片消息 | 消息 | **4** | `endpoints.js` + `MessageController.java` + `mall_management_sql.sql`（存 URL 要加列）+ `ChatRoom.vue` |
   | 手机号登录 | 登录 / 注册 | **4** | `endpoints.js` + `AuthController.java` + 建表 SQL（`login` 表现只有 `UserName/Password/Role/Id`，**无手机号列**）+ `LoginPage.vue` |
   | 多图（图集） | 上架商品 / 商品详情 | **≥ 4** | `goods` 只有 `GoodImg` **一列**（单图）⇒ 图集子表（+SQL）+ `GoodsDao` + `GoodsService` + `GoodsController` + `GoodsAdd.vue` |
   | 多规格矩阵（颜色 × 尺码） | 上架商品 / 商品详情 | **≥ 4** | 无 SKU 表、无组合价 / 库存列 ⇒ 新建 SKU 表 + `GoodsDao` + `GoodsService` + `GoodsController` + `GoodsAdd.vue` + `ProductPage.vue` |
   | 服务承诺（正品 / 7 天 / 极速退款 / 运费险） | 上架商品 / 商品详情 | **≥ 4** | 无承诺列 ⇒ `mall_management_sql.sql` + `GoodsDao` + `GoodsController` + `GoodsAdd.vue` |
   | 保存草稿 | 上架商品 | **≥ 4** | 无草稿态（下架 = `DELETE FROM goods`）⇒ 加状态列（+SQL）+ `GoodsDao` + `GoodsController` + `GoodsUp.vue`（列表要过滤掉草稿） |
   | 左分类树（一级可筛 + 二级层级） | 商品搜索 | **6** | `productSearch` 只收 `keyword` ⇒ 一级可筛：`GoodsDao` + `GoodsService` + `GoodsController` + `GoodsSelect.vue` = 4；加二级层级再 + `GoodsTypeCacheService.java`（改 SQL 取父子）+ 造 `GoodTypeNew` 数据 = 6。⚠️ **数据源是现成的**（`goodstype` 端点 + 表里 5 条真分类），缺的只是筛选参数，不是数据 |
   | 排序 / 筛选带（销量 / 价格 ↑↓ / 品牌 / 价格区间 / 有货 / 更多筛选） | 商品搜索 | **4～6** | 共同点：都要改 `searchByKeyword` 的 SQL 并加参数，最短链路 4 处（`GoodsDao` + `GoodsService` + `GoodsController` + `GoodsSelect.vue`）。品牌 / 有货要**加列** ⇒ 再 + `mall_management_sql.sql` = 5；「更多筛选」是各项聚合 ⇒ ≥ 6 |
   | 分页 | 商品搜索 | **4** | `GoodsDao`（SQL 加 `LIMIT` + 一条 `count`）+ `GoodsService` + `GoodsController` + `GoodsSelect.vue` |
   | **购物车整表**（店铺分组 / 店铺小计 / 商品行 / 数量 / 删除 / 勾选 / 吸底结算栏 / 编辑） | 购物车（客户） | **4** | **零购物车端点**、零购物车表 ⇒ `mall_management_sql.sql`（建 cart 表）+ `CartController.java` + `endpoints.js` + `ShoppingCart.vue`。⚠️ **这 4 处是本页的共同基线**：11 条占位**全部**含它 —— 所以本页 100% 覆盖率的代价是「**整页都是超档块**」。分组键用 `goods.MerchantId`（**已有**，不用加列） |
   | 店铺名 /「进店逛逛」 | 购物车 / 商品详情 | **≥ 5** | 上面 4 处基线 + **无店铺表**（`login` 只到 `Id` / `UserName` / `Password` / `Role`，**没有店名**）⇒ 再 +建表与查询。与「店铺主页」是同一个缺口 |
   | 订单数据概览（各态计数） | 订单中心（商家） | **4** | 无聚合端点 ⇒ `OrderDao`（加一条 `GROUP BY` 的 count）+ `OrderService` + `OrderController` + `OrderCenter.vue` |
   | 订单筛选栏（订单号 / 买家 / 商品名 / 时间区间） | 订单中心（商家） | **4** | 无任何搜索 ⇒ `OrderDao`（SQL 加 `WHERE`）+ `OrderService` + `OrderController` + `OrderCenter.vue` |
   | 订单批量操作（批量发货 / 导出 CSV） | 订单中心（商家） | **≥ 4** | 无批量端点；批量发货要事务 + 逐单走状态机 ⇒ `OrderDao` + `OrderService` + `OrderController` + `OrderCenter.vue`（导出还要一个额外的响应类型） |
   | 订单分页 | 订单中心（商家） | **4** | 两条商户侧 SQL（`selectByMerchant` / `selectActiveByMerchant`）**都没有 `LIMIT`**，全量回前端 ⇒ `OrderDao`（加 `LIMIT` + 一条 `count`）+ `OrderService` + `OrderController` + `OrderCenter.vue` |

   ⚠️ **本表的一处已知缺口（2026-10-06 收尾时发现；明写「未核」，不补一个像样的数字）**：
   两个 `UserCenter` 页各有 3～4 条 **N=4 量级**的占位（客户版：我的资产 / 我的订单四入口 /
   收藏·足迹 / 服务；商家版：经营数据 / 待处理订单 / 服务），两页头注都写着
   「账记在 README 挂账表（与客户版同一笔）」—— 但**这张表里从来没有它们**
   （反查：表内检索「个人中心 / 我的资产 / 经营数据 / 身份卡」命中 0 条）。
   那两句头注因此是**指向空处的交叉引用**。
   为什么不在这里补上：上表每一行都落到**文件名 + 条数**，而这两页头注给的口径只有
   「DAO + Service + endpoints + 第二个 .vue」= 4 —— **没算 `*Controller.java`，也没算建表 / 加列的
   `mall_management_sql.sql`**；而本表其它行凡涉及这两样一律 +1。按同一口径推，这两页的 N
   很可能与「购物车整表」「订单数据概览」同量级（**5～6**），而不是 4。
   ⇒ **核清楚之前不写数字** —— 免得把一个低估值混进一张看上去全是实测的表里。**待核。**
   ⚠️ 顺带一个同源观察：这两页的覆盖率估值也错了（见上面「逐页清单」表下的订正），
   错的是**同一批条目**。两处指向同一件事：**这两页在估算阶段就没被逐条拆开过。**

   ⇒ 结论：**这两条判据不可能同时满足**。要么严格按 ≤3 只画少数占位（覆盖率会掉到 60% 上下，
   直接违约「覆盖率 < 90% 不算完成」），要么按覆盖率优先全画、把真实 N 写在角标上让 D 笔自己看着办。
   **本笔记选择后者**，并在交付报告里把这张表原样交出去 —— 让用户看到代价后再定。

   ⚠️⚠️ **一个反例，也是本笔最有价值的一条发现（2026-10-06 重画订单中心时量出来的）**：
   **同一类元素、同一个页面，N 可以差 6 倍** ——
   「全部 / 待发货 / 已发货」三个标签页 = **N=1（在档）**。为什么可能：`OrderDao.selectByMerchant`
   把 `PayIf` / `DeliveryIf` / `ReceiveIf` **三个状态位全 SELECT 出来了**，而两条商户侧 SQL
   **都没有 `LIMIT`** ⇒ 商家的**全量订单本来就在前端**，`待发货 = PayIf=1 && DeliveryIf=0`、
   `已发货 = DeliveryIf=1 && ReceiveIf=0` 纯前端筛即可，只改 `OrderCenter.vue` 一处。
   而「退款中 / 售后」= **N≥6**（见下「只登记」表）。
   ⇒ **这条 N=1 有保质期，且与上表最后一行「订单分页」互锁**：它成立的唯一前提是「没有分页」。
     一旦照上表加了分页（SQL 加 `LIMIT`），全量就不再在前端，那几个标签页**必须同时搬到后端**，N 从 1 变 4。
     **这是同一个决定的两面：先上分页再上标签页，N 是 4+4；反过来是 1+4。**

> **在档（N ≤ 3）的占位长什么样**（口径修正后剩下的）：
> 商品搜索的搜索 / 筛选 / 排序（3）· 分页（3）· 批量操作（3）· 加入购物车（3）· 图形验证码（3）·
> 记住我 / 忘记密码（3）· 销量列（3）· **订单中心「全部 / 待发货 / 已发货」三个标签页（1）**·
> **订单中心「买家昵称」列（2 = `OrderDao` 加一条 JOIN `user.customerid → username` + `OrderCenter.vue` 渲染）**·
> 猜你喜欢这类 0 处的独立模块（且它已有 `goodsSelectHome` ⇒ 其实是真功能）。
> ⚠️ 注意「在档」是**逐元素**判的，不是逐页判的 —— 同一页里可以既有 N=1 的标签页、又有 N≥6 的标签页（上面那个反例）。

## 只登记（本笔**不画**，留给 C3/后续笔）

| 功能 | 动现有代码 | 为什么超档 |
|---|---|---|
| **退款 / 售后状态机** | **≥ 6** | `OrderState.java` 的状态 = `PayIf` / `DeliveryIf` / `ReceiveIf` **三个状态位**的 8 种组合（**5 合法 + 3 非法，已逐一具名**），里面**没有**退款 / 售后 ⇒ 加这两个态**不是加两个枚举常量，是加第 4 个状态位**：`mall_management_sql.sql`（加列）+ `OrderState` + `OrderStateMachine` + `OrderDao` + `OrderService` + `OrderController` + `OrderCenter.vue`。⚠️ 这两个标签页在 `OrderCenter-merchant.html` 里**照画了**（用户点名 ⇒ 按覆盖率优先画成占位，见该稿头注），但点进去是零：**状态机里不存在这两个态**。这是**状态机缺口，不是列表页缺口** |
| **第三方登录（OAuth）** | ≥ 4 | 回调路由 + 鉴权链 + `LoginPage.vue` + `AppShell.vue` 登录态四处 |
| **店铺主页**（进店逛逛的落点） | ≥ 4 | 新路由 + 新页 + 商家侧装修入口 + 商家卡跳转 |
| **优惠券体系**（跨页那套） | 5 | 领券(PDP) / 可用券(购物车) / 我的券(个人中心) / 已用券(结算) / 领券中心(首页) |
| **订单详情页**（列表点进去的落点） | ≥ 4 | 新路由 + 新页（**新文件不计入**）+ `OrderCenter.vue` 的跳转 = 2 处在档，但它要承接的东西超档：**状态时间线读取端点**（3 处：`OrderTimelineDao` / `OrderTimelineService` / `OrderTimelineController`，**表已存在、写入侧现成**）+ 物流 + 售后，每项再各叠一层 ⇒ 整页 ≥ 4。⇒ 本笔**不画**，只在订单中心稿的清单里作为「不计入本页分母」的条目登记 |
| **订单物流轨迹**（承运商快递单号查询） | ≥ 4 | 三方对接 + 单号存储列（+SQL）+ 新端点 + 商家发货表单。⚠️ **必须与 `order_state_transition` 分开记**：那张表（`mall_management_sql.sql:224-235`，B3 新增）记的是**内部状态流转**（create / pay / deliver / receive），写入点唯一（`OrderTimelineWriter.TABLE`），**全仓无读取端点、无 `.vue` 引用** —— 是「**写得到、读不到**」，与 `productsales.OrderVolume` 硬编码传 0 同型。补一条读取端点只要 3 处（**在档**），但那是**详情页的时间线**，**不是**物流轨迹；两者不是同一件东西，别把前者当成后者的现成基础 |
| **消息已读回执 / 表情包** | ≥ 4 | 后端已读表 + 会话列表 + 消息流 + 输入区 |

## 跨页共性组件（统一样式 + 跨页登记）

| 组件 | 出现在 | 统一规则 |
|---|---|---|
| **商品卡** | 搜索页栅格 / 商品详情推荐区 / 购物车猜你喜欢 / 个人中心收藏位 | 同一个卡：`--img` 占位图 + 发丝边 + 两行名（`min-height:3em` 对齐价）+ 衬线价三档（`¥` / 整数 `--fs-lg` / 小数） |
| **服务承诺条** | 商品详情 / 购物车 / 结算 | 发丝线横排四格，等宽、等重、无底色；文案固定「正品保障 / 7 天无理由 / 极速退款 / 运费险」 |
| **优惠券行** | 商品详情 / 购物车 / 个人中心 / 结算 / 首页 | 描边券框：面额用**衬线 + `--accent`**，条件用 `--fs-xs`，右侧「领取」描边小按钮；不用底色条 |
| **商家卡** | 商品详情（右栏）。**订单中心没有 —— 这是对原表的一处更正** | 字标（店铺名首字）+ 店名 + 两个描边动作（客服 / 进店）。⚠️ 原表写「商品详情 / 订单中心」，**错**：订单中心是**商家自己的后台**，页面里不存在「第二个商家」可链接 ⇒ 该页不画商家卡。购物车页只有**店名一行 + 进店逛逛**（`.shop-head`），那是这张卡的**极简版**，不是同一张卡 |
| **订单状态 chip** | 订单中心（商家）—— 唯一定义处 | `--ground` 底 + 同色深字；五态**同一套内距 / 字号 / 圆角，只有色相不同**（Q2「同模块不同状态视觉一致」就靠这一条）。配色：未付款=中性 `--surface-3`、待发货=`--warning`、已发货=`--info`、已收货·未发货=`--danger`、已完成=`--success`；**一律不描边**（12px 下描边会糊） |
| **店铺分组**（店名 + N 件 + 进店逛逛 + 本店小计） | 购物车（客户） / 订单中心·商家购物车 pane | 两处是**同一份 `ShoppingCart.vue`** ⇒ 形态**逐字对齐**（分组头 + 发丝线商品行 + 本店小计 + 吸底结算栏）。分组键用 `goods.MerchantId`（**已有列**，不用加列）。**改一处必须两处一起改** |
| **勾选框**（`.ck`） | 购物车 / 订单中心（批量条 + 表格行） | 统一 **18px**、`--r1`、描边 `--line-2`、无底色。原生控件而非自绘，一律 `disabled` + `title` |
| **面包屑** | 全部内页 | `首页 / … / 当前`，`--fs-xs`，**分隔符与文字同色**（`--ink-3`，见上文对比度事故） |
| **空态** | 全部列表页 | 一句衬线大字（`--fs-lg`）+ 一句 `--fs-xs` 说明，居中，无插图 |

## 逐页清单

**每份稿的头注里都有一张「平台功能清单」表**（条目 / 后端 / 难度档 / 动现有代码 N 处 / 本笔怎么画），
README 这里不重复；覆盖率与逐条对账见每份稿头注与交付报告。

| 稿 | 清单条目数 | 真功能 | 占位 | 只登记 | 覆盖率 | 核算 |
|---|---|---|---|---|---|---|
| `ProductPage.html` | 19 | 11 | 7 | 1 | 18/19 = **94.7%** | 已复核 |
| `GoodsSelect.html` | 15 | 7 | 8 | 0 | 15/15 = **100%** | 已复核 |
| `ShoppingCart-customer.html` | 15 | 4 | 11 | 0 | 15/15 = **100%** | 已复核 |
| `UserCenter-customer.html` | 13 | 6 | 7 | 0 | 13/13 = **100%** | 已复核 |
| `UserCenter-merchant.html` | 15 | 9 | 6 | 0 | 15/15 = **100%** | 已复核 |
| `OrderCenter-merchant.html` | 17 | 7 | 10 | 0 | 17/17 = **100%** | 已复核 |
| `LoginPage.html` | 11 | 7 | 4 | 1 | 10/11 = **90.9%** | 已复核 |
| `ChatRoom.html` | 8 | 7 | 1 | 0 | 8/8 = **100%** | 已复核 |
| `GoodsUp.html` | 13 | 8 | 5 | 0 | 13/13 = **100%** | 已复核 |
| `GoodsAdd.html` | 19 | 12 | 7 | 0 | 19/19 = **100%** | 已复核 |

> 覆盖率 =（真功能 + 占位）/ 清单条目；只登记的不计入分子，但**计入分母**（它是清单的一部分，只是本笔不画）。
> ⚠️ 这张表**重画前是推算的**（按清单估条目数，没逐条落表）；重画一页就照着该页头注的清单
> 表**逐条重数一次**，`核算`列标「已复核」。2026-10-06 收尾时**10 行已全部复核**，
> 并逐行与该页头注的「覆盖率」行对过（10/10 一致）。
> ⚠️ 复核**抓到两行的估值是错的**，别以为"估算"只是差个位数 —— 两个个人中心页
> 从 `12 / 真 4 / 占位 7 / 只登记 1 / 92%` 订正为 `13 / 6 / 7 / 0 / 100%` 与 `15 / 9 / 6 / 0 / 100%`：
> **条目数、真功能数、只登记数三项全错**（占位那列 7 恰好对上，纯属巧合）。
> ⚠️ **错因无法回溯，也不编一个**：旧估值**从未逐条落表**（就是上面那句"没逐条落表"），
> 拿不到可比的旧明细，只能说结论错了。值得记的是**「只登记」这一列从 1 变成了 0** ——
> 它是唯一**不产出画稿**的一列，估的时候最容易顺口漏掉；这页恰好还有一条独立佐证：
> 两个 UserCenter 头注都写着「账记在 README 挂账表」，而那张表**从来没有这两页的条目**（见下）。
> **一条交叉引用指向一张没有它的表**，就是这么来的。

---

### ⚠️ `check-badges.mjs` 的三个盲区（2026-10-06 重画购物车时发现两个、重画订单中心时补出第三个）

该探针读源码确认：它沿**祖先链**找 `overflow != visible`，且 `boxes()` **逐个算盒**。
于是有三类失效它**不但查不出来，还会报 OK**：

1. **视口外溢出** —— 它只跟祖先盒比，**从不与视口比**。裁掉角标的若是浏览器窗口本身
   （祖先链上没有任何 `overflow:hidden`），它报 OK。
   实例：`ShoppingCart-customer.html` 的「编辑」角标右缘到 x=1471，视口 1440，**被裁 31px**，探针报 OK。
2. **两枚角标互撞** —— 它逐枚独立算盒，**从不做两盒相交**。
   实例：同一处 `data-slot` 角标（1256–1471）与 `data-future` 角标（1193–1278）
   **咬掉 6×15px**，而 `::after` 画在 `::before` 之上 ⇒ 蓝角标被切一块，探针报 OK。
3. **角标撞「自己所在块的内容」**（2026-10-06 重画 `OrderCenter-merchant.html` 时补出）——
   它只检查「角标有没有被**外部**裁掉」，不检查「角标有没有盖住**自己块里**的东西」。
   默认角标画在 `top:-11px`、高 17px ⇒ **下沿伸进块内 6px**；
   若这一块的内容正好**贴着自己的顶边**开始（没有 `padding-top`），就会被盖住。
   实例：该稿的 `.tabs` / `.filter` / `.pager` 三块，实测角标下沿探进内容区 **+4px**
   （统计量 = `badgeBottom − firstTextTop`；同稿 `.stats` / `.bulk` 有内距，是 −21 / −9，干净），
   探针报 OK。修法是把这四块（含购物车 pane 的 `.shops`）的角标**局部**抬到 `top:-19px` ——
   **不动目标版布局**：角标只存在于预览态，为它去改目标版的 `padding`，加出来的内距会留在最终页面上。
   ⚠️ 附带一条更细的坑：**`::after` 与 `::before` 必须成对写**。
   两枚角标挂在**同一个宿主**上、只是伪元素不同；先只改 `::after` 时，复核脚本立刻报出
   `tabs(future) +4`、`shops(future) +4` —— 宿主相同、伪元素不同，只改一个等于只修了一半。

**查法**（不落任何文件）：内联 `node -e` 起 puppeteer，对每个挂点按
`getComputedStyle(el,'::after')` 的 `top/left/right/width/height` 换算成绝对矩形，
再①与 `document.documentElement.clientWidth` 比、②两两求交、③与**自己块内最上面那行文字**比
（③ 要用 `document.createRange()` 取**字形盒**，不是元素盒 —— 元素盒含行高，会把 4px 的盖字判成"没碰到"）。
⚠️ 只统计**当前可见 pane** 里的角标：隐藏 pane 里 `getBoundingClientRect()` 全是 0，
不过滤的话会把「溢出」和「互撞」的统计带偏。
⇒ **通用结论：探针报 OK 只等于「它查过的那几件事 OK」。**
一个探针没覆盖到的失效模式，不会以「没查」的形式报出来，而是以「OK」的形式报出来 —— 这是最危险的失败方式。
**用之前先读一遍它在查什么。**（探针脚本按约定只读，不许改，所以盲区记在这里。）
