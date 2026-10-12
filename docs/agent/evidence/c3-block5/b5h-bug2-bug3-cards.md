# b5h ·〈Bug 2 已售完过滤〉+〈Bug 3 类别标签〉+〈Bug 1 的列表侧收尾〉—— 落码与实证

- **日期**：2026-10-12 · 分支 `feat/c3-admin-block5` · profile **a3**（⚠️ `app.auth.enabled=false` ⇒ 闸关）
- **本笔的**三件**（用户逐条裁定，见会话）：
  1. **① 耳机详情页空白**（真 bug）—— 根因与修法**不**在本文件，见
     `docs/agent/evidence/c3-block5/b5h-bug1-detail-blank.md`（判据 = 三元组
     `(MerchantId, GoodName, ProductCode)`；`priceByTriple` 改 `ORDER BY Id ASC LIMIT 1`）。
     本文件只补它在**列表侧**的收尾：同码去重（否则同一件货画两张卡）。
  2. **② 已售完只在自己店铺页显示** —— 共享面（主页「为你推荐」/ 搜索页 / 两处「猜你喜欢」）过滤掉
     `IsSoldOut`；店铺页三个 tab **照显**（灰态 + 红斜标）；`/seckill` 秒杀页**照显**。
  3. **③ 秒杀 / 国补双标** —— 普通商品不显；秒杀 ⇒ 红「秒杀商品」，国补 ⇒ 绿「国补」，**两者同屏**。

- **产物**（都在仓里，可直接复核）：
  | 文件 | 是什么 |
  |---|---|
  | `Mall-management-server/.../marketing/MarketingGoodsFlags.java` | **新建**：三个 `EXISTS` 投影列的唯一真相（SQL 那一半） |
  | `Mall-management-web/.../src/config/goods-flags.js` | **新建**：列表侧共用判据（三个标记 + `dedupeGoods` + `sharedList`） |
  | `Mall-management-web/.../src/components/ProductCard.vue` | 公共商品卡：类别标签行 + 已售完灰态 + 红斜贴图 + `from` prop |
  | `scripts/probes/probe-b5h-bug123.mjs` | **HTTP 层**探针（真库、真端点） |
  | `docs/美化/probe-b5h-bug123-page.mjs` | **页面层**探针（真 dev server 8080 + 真后端 8088，**不 mock**） |
  | `_work/b5h-probe.json` | HTTP 探针的原始响应（43 行全文） |

---

## 一 · 后端数据面（HTTP 探针实测，2026-10-12）

命令：`node scripts/probes/probe-b5h-bug123.mjs`（a3 后端 8088 在跑）

| 端点 | 行数 | 三列齐 | `IsSeckill` | `IsSoldOut` | `IsSubsidy` |
|---|---|---|---|---|---|
| `POST /goods/goodsSelectHome` | **43** | ✅ | 2 | **1** | 1 |
| `POST /goods/productSearch`（`keyword=耳机`） | 4 | ✅ | 1 | 0 | 0 |

- **同码重复组：全库 1 组** —— `dRD3gqtbFr` + `GD-101-9552`（`Id` 76 与 120，同名同价 ¥299）。
- **已售完那 1 行**：`Id=82` `dRD3gqtbFr` 「智能手环 5 代 血氧版」`GD-107-0959`，
  `IsSeckill=1 / IsSoldOut=1 / IsSubsidy=1` ⇒ **它同时是秒杀、已售完、国补**（本文件第五节那条数据巧合的由来）。
- **前端判据跑出来的数**（探针直接 import `config/goods-flags.js` 的**真源码**，不是重写一遍判据）：
  - 主页：43 行 → `dedupeGoods` **42** → `sharedList` **41**
  - 搜索「耳机」：4 行 → `sharedList` **3**
- `POST /goods/goodsSelect`（商家自己的商品管理端点）**没有**这三列 —— **不是**本笔的漏：店铺页的数据源是
  `goodsSelectHome`（一次拉全量、前端按 `MerchantId` 过滤，`ShopHome.vue` 的 `loadShop`），
  从来不走 `goodsSelect`。

---

## 二 · 前端判据的**落点**（一份定义，四处消费）

- `config/goods-flags.js` 是**列表侧判据的唯一定义处**：`isSeckill` / `isSoldOut` / `isSubsidy`
  （一律 `Number(v) === 1`，防「驱动把 0/1 退成字符串、`'0'` 在 JS 里是真值」这个本仓踩过的坑）、
  `dedupeGoods`（键 = `MerchantId|ProductCode`，同键留 `Id` 最小 —— **与详情页 `ORDER BY Id ASC LIMIT 1`
  同判据**）、`sharedList`（= 去重 + 滤已售完）。
- **`ProductCard.vue` 的四处消费点**（⑤ 的「4 处复用」）：
  1. `HomePage.vue`「为你推荐」 —— `sharedList`
  2. `GoodsSelect.vue` 搜索结果 —— `sharedList`（本笔**唯一**需要换卡的页；另三处早已在用组件）
  3. `ProductPage.vue`「猜你喜欢」 —— `sharedList`（**另开** `recommendGoods` 支线，不动 `goodsWithUser`
     本身 —— 它还有第二个消费方：`loadAllItems` 按 `MerchantId + GoodName` 取当前那件的 `UserName`）
  4. `ShopHome.vue` 本店网格 / 折扣 / 底部猜你喜欢 —— **本店两格只去重不过滤**（`dedupeGoods`），
     底部猜你喜欢走 `sharedList`
- **落码时去掉了 ⑤ 原方案里的 `showKinds` prop**：四个消费点都要显标签 ⇒ 恒为真的开关是没人拧的旋钮。
  头注写明；要用时再加回（判据仍只有一处）。

---

## 三 · 页面层实证（`docs/美化/probe-b5h-bug123-page.mjs`，**17 项 / 0 失败**）
> ⚠️ **本节后来长了**：同一支探针加上「标签行等高占位」那 5 条断言后变成 **22 项 / 0 失败**
> （2026-10-12 复跑，`docs/agent/evidence/c3-block5/b5h-bug2b-matrint-and-card-ghost.md` §四）。

真 dev server（8080）+ 真后端（8088），**页面内零 mock**；已登录态靠贴 `sessionStorage`
（`loginUsername=1111` / `customerid=uDopHnCeLS` / `role=1`；⚠️ 键名是 `loginUsername` 不是 `username`，
第一版贴错键 ⇒ 秒杀页只渲染「登录后即可查看」，那一项假红）。

| 面 | 判据 | 实测 |
|---|---|---|
| 主页「为你推荐」 | 卡数 = 43 −1 同码 −1 已售完 | **41** ✅ |
| 主页 | 耳机（`GD-101-9552`）只出现一次 | 1 ✅ |
| 主页 | 无 `.pcard.sold`、无「已售完」贴图（共享面已滤） | 0 / 0 ✅ |
| 主页 | 秒杀红标在（键盘那件） | 1 张 ✅ |
| 搜索页「耳机」 | 「共 3 件」（4 行 −1 同码） | `共 3 件` ✅ |
| 搜索页 | 耳机只出现一次 | 1 ✅ |
| 店铺页「全部」tab | 本店网格 4 张（5 行 −1 同码） | 4 ✅ |
| 店铺页 | 手环卡在网格里 | ✅ |
| 店铺页 | 手环卡带 `.sold` 灰态 | ✅ |
| 店铺页 | 手环卡有「已售完」红斜贴图 | ✅ |
| 店铺页 | 手环卡**两枚类别标同屏**（`秒杀商品` + `国补`） | ✅ |
| 店铺页 | 底部猜你喜欢（别家）不含已售完 | ✅ |
| `/seckill` | 手环**仍列出**（用户 ②：秒杀页不过滤） | ✅ |

截图（同目录）：`out-b5h-home-recommend.png` · `out-b5h-search-earphone.png` · `out-b5h-shophome.png` ·
`out-b5h-shophome-hot.png` · `out-b5h-seckill.png`

---

## 四 · 偏差与须知情（验收归用户）

1. **店铺页「本店网格」也做了同码去重**（5 行 → 4 张卡）—— 用户 ② 的字面只要求「含已售完」，
   去重不在其内。取此判据的理由：不去重就是**同一件货两张卡、且两张都点进同一个详情页**
   （详情页只认 `Id` 最小那一行），与本笔 ① 的判据同源。**自裁 + 报备**，不妥即回退（一行改动）。
2. **热销 tab 是柱状图，不是卡片** ⇒ 灰态 / 类别标签在那一格**只存在于卡片，图上没有**。
   实测该榜（默认停在这一格）= 充电器 / 耳机 / **手环**，手环以「名字 + 销量柱」在榜上（**没被藏**）。
3. ~~**共享面今天看不到「国补」绿标**：全库唯一的国补商品**就是**那件已售完的手环（第五节），
   它在共享面被 ② 过滤掉了 ⇒ 绿标只在**店铺页**可见。~~ ⇒ **已被推翻**：见下方补记。
4. **① 的修法代价**：`priceByTriple` 遇到同码多行时不再抛错，而是取 `Id` 最小那一行 ⇒
   **多行进缓存的机会变多了**（测试专门钉住这一条，见 `b5h-bug1-detail-blank.md`）。
5. **a3 ⇒ 闸关**（`app.auth.enabled=false`）：本文件的所有「绿」都是**闸关下**的绿。
   匿名可读的判据在默认 profile 下**只有默认 profile 能验**。

---

## 五 · 待收口（**块 5 收口时统一做**，本笔按口径不动文档）

- `scripts/refs-scan.sh` before/after 对比 + 逐条核 LOST / 移动（before = `scripts/refs-before-b5h.txt`，4431 条）
- 重指：`ChatRoom.vue` 里指向 `GoodsSelect.vue` 价签函数的那条（目标**已随卡搬进 `ProductCard.vue`**）；
  `router/index.js` / `AppShell.vue` 两条**改前就已陈旧**的（描述的内容不在所指行上）
- 订正 `cache-layer.md` / `slot-registry.md` 里与 `PriceTripleFailSmokeTest` 相关的过期叙述
- 登记本笔新改动文件的**新行号**（`GoodsCacheService.java` / `GoodsDao.java` / `SeckillDao.java` /
  `SubsidyDao.java` / `MarketingGoodsFlags.java`）

---

## 六 · drift ledger（本笔的 refs 前后对照）

- 命令：`bash scripts/refs-scan.sh scripts/refs-b5h-after.txt`（**最后一次编辑之后**才跑 —— 本文件自己
  也会被扫，故「先写完证据、再取快照」）
- 产物：before = `scripts/refs-before-b5h.txt`（4431 条 / token 集 **2558**）·
  after = `scripts/refs-b5h-after.txt`（**最后一次**快照：4453 条 / token 集 **2567**）
  —— 本文件**自己**也被扫，故 after 是在写完本节**之后**重取的（那一版比前一版多出本表这几条引用，
  **LOST 仍是 0**）。
- 判据（比 **token 集合**、不比原始产物）：**LOST = 0** —— 一条都没丢，无 `<` 行。
- **NEW = 9 条**，逐条已回读目标行确认（不是「扫出来就算」）：

  | 新 token | 写在哪 | 目标行实测 |
  |---|---|---|
  | `GoodsUp.vue:1037-1038` | `ProductCard.vue` 的标签配色按语 | `.kind.subsidy`（`--brand-tint`/`--brand-ink`）/ `.kind.seckill`（`--cin-100`/`--cin-700`） |
  | `HomePage.vue:242` · `HomePage.vue:299` | `ShopHome.vue` / `GoodsSelect.vue` 的相关按语 | 稿的 `.card-tags` 按语 / 合并 import 那一行 |
  | `SeckillPage.vue:289` · `:303` | `ProductCard.vue` 的灰态与贴图形制 | 「不用 `grayscale()`」按语 / `.stamp` 规则 |
  | `ShopHome.vue:180-183` | 本页面探针的注释 | 「分类 chip 本店有 ≥2 个类目才画」那段 |
  | `api/auth.js:17` · `api/auth.js:20` | 本页面探针的注释（第一版把行号写成 16，**回读后订正为 17**） | `username: 'loginUsername'` / `customerId: 'customerid'` |
  | `docs/美化/主页改造.html:515` | `HomePage.vue` 的按语 | `.card-tags { margin-top … }` |

- **自引没漂**：`ShopHome.vue` 里那条「本文件有 8 条 refs token，其中 `:872/:875` 就在下面这段
  `<style>` 里」—— 本笔往里加过注释，**同笔压回原行数**（净零），实测 `tokens.css:36` / `:37`
  仍在 `:872` / `:875`（该文件 `:1050` 那条按语继续成立）。
- **本笔新增的落点在「最大被引行之后」**：`ProductCard.vue` / `config/goods-flags.js` 是**新文件**；
  四个消费点的改动一律**同行**（不改行数），唯一例外是把 `ShopHome.vue` 的 `dedupeByCode` 换成墓碑
  —— 墓碑**补满原 19 行**。

---

## 七 · 补记（2026-10-12 晚，两件后续）

1. **本文件 §三 的「17 项」已是旧数**：同一支 `docs/美化/probe-b5h-bug123-page.mjs` 加了
   5 条「标签行等高占位」断言（用户 ①）⇒ 复跑 **22 项 / 0 失败**。那一笔的完整证据在
   `docs/agent/evidence/c3-block5/b5h-bug2b-matrint-and-card-ghost.md`。
2. **本文件 §四 第 3 条（「共享面看不到国补绿标」）已被数据推翻**：手验期间库里多了一件国补商品
   —— `Id=77`「无线蓝牙耳机 声谷 Pro 降噪」（`MerchantId=nlzzGB4vgV`，`ProductCode=GD-102-AF27`）
   ⇒ `goodsSelectHome` 的 `IsSubsidy` 由 **1 变 2**，主页「为你推荐」上**确实**出现了绿标
   （实测带真标签的卡：`无线蓝牙耳机 声谷 Pro 降噪[国补]` / `机械键盘 87 键 茶轴[秒杀商品]`）。
   当时那句结论的前提是「全库唯一国补商品 = 那件已售完的手环」，**前提在数据变化后不成立**，
   结论作废；过滤器本身（② 的语义）**未变**。原始响应见 `_work/b5h-probe-round3.json`。
