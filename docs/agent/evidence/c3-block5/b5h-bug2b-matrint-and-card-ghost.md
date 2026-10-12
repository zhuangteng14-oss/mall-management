# b5h ·〈Bug 2b 详情页「标题在、价 / 型号 / 图空缺」〉+〈用户 ① 标签行等高占位〉—— 根因、落码与实证

- **日期**：2026-10-12 · 分支 `feat/c3-admin-block5` · profile **a3**（⚠️ `app.auth.enabled=false` ⇒ 闸关）
- **本笔两件**（用户逐条裁定，会话原文见 §三 / §四）：
  1. **② 详情页真的还是空的**（用户截图 2）—— 现象与上一轮修的「整页空白」**不是同一件事**：
     上一轮是页面整支不渲染；本轮是**标题拿到、价格只剩 `¥`、型号空、图空**。
     **根因 = `Math.rint` 是 Java 的函数、JS 没有**（详见 §二）。
  2. **① 普通商品卡也占住标签行**（用户 ①，现象 = 有标签的卡高 / 无标签的卡矮 ⇒ 网格参差）。
- **结论先行**：**数据没有被污染，不删数据**（用户附言的前提被证据否掉，逐条见 §三）。

- **产物**（都在仓里 / `_work/`，可直接复核）：
  | 文件 | 是什么 |
  |---|---|
  | `Mall-management-web/.../src/views/ProductPage.vue:520` | **② 的主犯**：`discountRate` 里那句 `Math.rint`（同行替换为 `Number.isInteger`） |
  | `Mall-management-web/.../src/views/GoodsAdd.vue:526` | 同一处笔误的第二落点（`fmtRate`，被两个 computed 调用） |
  | `Mall-management-web/.../src/views/merchant/SeckillApplyPage.vue:155` | 第三落点（`qtyBad`）+ `:102` 的注释订正 |
  | `Mall-management-web/.../src/components/ProductCard.vue` | ① 的落码处：标签行**恒渲染** + 隐形同形签 `.pcard-kind.ghost` |
  | `scripts/probes/probe-b5h-bug2b-detail.mjs` | **HTTP 层**探针（真后端 8088，逐字照抄前端发法 + 编码对照） |
  | `docs/美化/probe-b5h-bug2b-detail-page.mjs` | **页面层**探针（真 dev server 8080 + 真 8088，**不 mock**） |
  | `docs/美化/probe-b5h-matrint-merchant-pages.mjs` | 另两个受害页的页面级验证（**带对照**，见 §四） |
  | `_work/b5h-probe-round3.json` · `_work/b5h-bug2b-detail.json` | 两份 HTTP 探针的原始响应 |
  | 本目录 `b5h-round3-*.txt` | 本笔四支探针 + eslint + build + DB 只读查询的**原始输出**（不是转述） |

---

## 一 · 先纠一条前提：上一轮的「修好了」与本轮的「还是空」不矛盾

- 上一轮修的是**整页空白**（判据 = 三元组 `(MerchantId, GoodName, ProductCode)`，见
  `docs/agent/evidence/c3-block5/b5h-bug1-detail-blank.md`）—— 那一件的落点在后端
  **`priceByTriple` 遇到同码多行时抛错**，与「标题在不在」无关。
- 本轮用户报的是**另一件事**：标题**在**（它来自 `route.query.GoodName`，
  `ProductPage.vue:82` / `:44`；**不走接口**），但价格 / 型号 / 图三处**同源于 `allItems`** 的地方全空。
  这三处同源 ⇒ 只要 `allItems` 没被填上，它们必**同时**空 —— 这正好对上用户看到的形态。

---

## 二 · 根因：`Math.rint` 是 **Java 的**，JS 的 `Math` 上没有这个函数

```js
// ProductPage.vue:515 附近的 discountRate（本笔未改这一行）
const discountRate = computed(() => Math.round((o / p) * 10 * 100) / 100)
// ProductPage.vue:520 —— ⚠️ **原写 `Math.rint(r)`**，本笔同行替换：
return Number.isInteger(r) ? String(Math.trunc(r)) : String(r)
```

**因果链（每一环都实测过）**：

1. `Math.rint` 在 JS 里**不存在** ⇒ 一求值就 `TypeError: Math.rint is not a function`。
   探针里的**对照项**直接在本页运行时里调了一次：
   `try { Math.rint(1.5) } catch (e) { return e.message }` ⇒ 返回 **`"Math.rint is not a function"`**
   （`b5h-round3-merchant-pages-probe.txt` 第 1 条）。这一条是给「没抛异常」那几条断言**撑腰**用的 ——
   否则「没异常」可能只是「这段压根没跑」。
2. 它在 **`computed` 里**抛 ⇒ Vue **整支 render 函数**抛出 ⇒ 组件**停在上一次成功的渲染帧**上
   （那一帧 `allItems` 还空着）⇒ 页面上**标题在**（模板里它读 `route.query.GoodName`，与 `allItems` 无关），
   而 `¥` 只剩符号、型号钮 0 个、大图是空占位。**「不是整页空白」** 就是这么来的。
3. **为什么藏了一整轮**：`hasDiscount`（`ProductPage.vue:510`）= `o > 0 && p > 0 && o > p`，
   模板**只在它为真时**才读 `discountRate`。⇒ 只有「有划线原价」的商品中招。
   实测（本文件 §三 的 DB 查询）：全库在架 **43 行**里 `OriginalPrice > 0` 的**只有 2 行**，
   而这两行**正好是同一件耳机的重复行** ⇒ **中招的商品只有 1 件（同一件货的 2 行）**，
   另外 41 件照常 —— 这就是它在上一轮整体回归里没露头的力学原因。

**同一处笔误的另两个落点**（同一批〈折扣〉/〈秒杀〉代码，用户 ② 的页面只是最先被点到的那个）：

| 落点 | 表达式 | 触发条件（实测） |
|---|---|---|
| `GoodsAdd.vue:526` | `const fmtRate = (v) => (v === Math.rint(v) …)` → `Number.isInteger(v)` | 被 `discountError` / `discountHint` **两个 computed** 调用（`:549` / `:551` / `:564`）⇒ 商家一填对「划线原价」这两行提示就整体 TypeError |
| `SeckillApplyPage.vue:155` | `quantity.value !== '' && (!isFinite(q) \|\| q < 0 \|\| q !== Math.rint(q))` → `!Number.isInteger(q)` | `isFinite(q) \|\| q < 0` 两个短路支**都是假**时才轮到它 ⇒ 商家一填限量就 TypeError |
| `SeckillApplyPage.vue:102` | 注释里的 `qtyBad` 叙述 | **同笔订正**：原文写 `Math.rint`，那是 Java 的 |

- **判据的来源**：`v === Math.rint(v)` 的原意就是「`v` 是整数」⇒ JS 等价物 `Number.isInteger(v)`；
  `!Number.isInteger(q)` 对应原来的 `q !== Math.rint(q)`。**不是换了个写法糊过去**，是原意。
- **三处一律「同行替换」**（不新增 / 不删除行）⇒ 行号零漂移，见 §六。

### 2.1 为什么「eslint 干净 + build 过」**不构成证据**

- `no-undef` 只认**变量**；`Math.rint` 是「已定义对象上的**未知成员**」，规则管不着 ⇒ **抓不到**。
- build 只打包、**不执行**这一行 ⇒ 也抓不到。
- ⇒ 判据只能是**真触发一次**：本笔三条（详情页 / `GoodsAdd` / 秒杀报名页）**都上页面真跑了一遍**（§四）。

---

## 三 · 用户 5 问的逐条答案（【要查】1–5）

> 全部基于**真跑着的 a3 后端**（`8088`）与**真库只读查询**，原始输出在
> `b5h-round3-http-probe.txt` / `b5h-round3-goods-db.txt` / `b5h-round3-detail-page-probe.txt`。

### 问 1 · `productCodePrice` 实际返回什么

`POST /goods/productCodePrice`（`ProductCode=GD-101-9552`）⇒ HTTP 200：

```json
{"success": true, "price": 299, "originalPrice": 373.75,
 "goodImg": "https://picsum.photos/seed/GD-101-9552/400"}
```

**三个字段都有值**。⚠️ 上一轮用 `curl` 看到的 `{"success":false}` 是**假象**：Git Bash 的
`argv → CP936` 会把中文参数绞成乱码。本笔的 HTTP 探针**逐字照抄前端发法**（不编码的中文 / 空格 form body），
并同时发一份 `URLSearchParams` 编码版做对照 ⇒ **两版返回完全相同**（`_work/b5h-bug2b-detail.json` 的
`step1.raw` vs `step1.encoded`）。

### 问 2 · 同码两行（`Id` 76 / 120）逐字段对比

`SELECT * FROM goods WHERE ProductCode='GD-101-9552'` ⇒ **2 行**，`goods` 表共 21 列，**逐列对比**：

| 列 | `Id=76` | `Id=120` |
|---|---|---|
| `MerchantId` | `dRD3gqtbFr` | `dRD3gqtbFr` |
| `GoodName` | 无线蓝牙耳机 声谷标准版 | 同 |
| `Price` | 299.0 | 299.0 |
| `GoodImg` | `https://picsum.photos/seed/GD-101-9552/400` | 同 |
| `GoodType` | 电子用品 | 同 |
| `ProductCode` | `GD-101-9552` | 同 |
| `GoodTypeNew` | 0 | 0 |
| `ShelfState` | 1 | 1 |
| `DelistedAt` / `DeletedAt` | null / null | null / null |
| `TypeId` | 1 | 1 |
| `Detail` / `Spec` / `AfterSale` | null / null / null | null / null / null |
| 四条承诺列（`PromiseGenuine` / `Return7` / `FastRefund` / `FreightIns`） | 1 / 1 / 1 / 1 | 1 / 1 / 1 / 1 |
| `OriginalPrice` | **373.75** | **373.75** |
| **`Source`** | **`seed`** | **（空串）** |

⇒ **业务列逐个相同，唯一差别是 `Source` 一列**（`seed` 是「种子数据」标记，与展示无关）。
**76 的 `Price` / `GoodImg` / 型号字段一个都不空** —— 用户猜的「76 字段是空的」**不成立**。

### 问 3 · 取 `MIN(Id)=76` 后，76 是否完整

**完整**。前端确实取的是 `Id` 最小的那行（`ProductCard` 拼的链接就是 `Id=76`，
`b5h-round3-detail-page-probe.txt` 里读出来的 `href` 逐字可核），而这行的每个业务字段都有值。
⇒ 详情页**不是**「取错了行」，是**取到了完整的行却画不出来**（§二 的 render 中断）。

### 问 4 · 若 76 不全，去重该按什么规则

**前提不成立 ⇒ 无需改去重规则。** 两条理由，都可复核：

1. 两行**逐列等价**（问 2 的表）⇒ 按「字段完整度」或「最新时间」选，都会选到**同一份内容**。
2. 「最新时间」**根本没有可用的列**：`goods` 的 21 列里**没有上架 / 创建时间**；
   `DelistedAt` / `DeletedAt` 是「下架 / 删除**时刻**」，两行都是 `null`。

现有的去重判据（`config/goods-flags.js` 的 `dedupeGoods`，键 `MerchantId|ProductCode`、同键留 `Id` 最小）
与详情页后端 `priceByTriple` 的 `ORDER BY Id ASC LIMIT 1` **同判据**，保持不变。

### 问 5 · 前端 `ProductPage.vue` 取值路径，哪个字段空

**没有哪个字段空 —— 是 render 抛了异常，把组件冻在空帧上。** 取值链：

| 页面上那一块 | 取值路径 | 断点 |
|---|---|---|
| 标题 | `route.query.GoodName`（`ProductPage.vue:82` / `:44`） | ✅ 与 `allItems` **无关** ⇒ 所以它在 |
| 价格区 | `currentItem.price` ← `allItems` | ❌ |
| 型号区 | `allItems.slice(0, 4)` | ❌ |
| 图片区 | `v-for item in allItems` | ❌ |

`allItems` 的唯一填充路径是 `loadAllItems`（`ProductPage.vue:546-573`）：先 `GoodsProductCodeSelect`
取型号码表，再逐码 `productCodePrice`（`d2.success` 才入列）。
⚠️ `mergeSkus`（`skuList`）**只**被 `loadPopupItems`（`:608` 起）用 —— **详情页主区不走它**，
别照那条支线找。

### 3.1 关于用户附言：「如果是数据被污染导致的，我建议数据库内删掉这个商品的信息」

**数据没有被污染，本笔不删任何数据。** 依据是上面 5 问：接口三字段齐、两行逐列等价、
取到的那行字段完整 —— **污染假设的每一条前提都被证据否掉了**；真因是前端 render 中断。
（另一条硬线：整表 / 批量删除在本环境本来就会被 `[Cloud Storage Mass Delete]` 拒，只能按具体 `Id` 逐条删；
本笔连单条也不需要删。）若你仍想清掉那行 `Id=120` 的重复行，那是**另一笔**（要动库、需你单独点头），
与本次「详情页空」无关。

---

## 四 · 实证：四支探针 + 页面截图

命令（全部在 `a3` 后端 + `8080` dev server 上跑；**页面侧零 mock**）：

```
node scripts/probes/probe-b5h-bug123.mjs http://localhost:8088 _work/b5h-probe-round3.json
node docs/美化/probe-b5h-bug2b-detail-page.mjs
node docs/美化/probe-b5h-bug123-page.mjs
node docs/美化/probe-b5h-matrint-merchant-pages.mjs
```

| 探针 | 结果 | 关键读数（原始输出见同名 `b5h-round3-*.txt`） |
|---|---|---|
| HTTP 层 `probe-b5h-bug123.mjs` | — | 主页 **43 行**，三列齐；秒杀 2 / 售完 1 / 国补 **2**；去重 42 ⇒ `sharedList` **41** |
| 页面层 `probe-b5h-bug2b-detail-page.mjs`（**② 的正证**） | **8 项 / 0 失败** | 耳机页现在：`priceLine = "¥299¥373.758 折"` · `priceInt = "299"` · `was = "¥373.75"` · `off = "8 折"` · `models = ["GD-101-9552"]` · 大图 `naturalWidth = 400`；网络依次 `401 /user/me` · `200 goodsSelectHome` · `200 GoodsProductCodeSelect` · `200 productCodePrice`；**报错：（无）**。对照件（充电器 65W 双口）同样有价有图 |
| 页面层 `probe-b5h-bug123-page.mjs`（**① 的正证**） | **22 项 / 0 失败** | 主页 **41 张卡全 329px** · 店铺页「全部」网格 **4 张全 363px** · **真签与隐形签同像素高 21px** · 隐形签 39 = 无标签卡 39 · `visibility = hidden` |
| 页面层 `probe-b5h-matrint-merchant-pages.mjs`（另两处受害页） | **8 项 / 0 失败** | 对照项证实本运行时 `Math.rint` 调用即抛；`GoodsAdd` 填 100/200 后提示行 = **「折扣 5 折 —— 详情页会显示 ¥200 划线价与「5折」角标。」**；秒杀报名页填价 99 / 限量 5 ⇒ 提示行带「原价 ¥299.00 … 非负整数」且**不是** bad 态；**两页填值过程零未捕获异常** |

**① 的构造（为什么用「隐形同形签」而不是给行写死 `min-height`）**

- `ProductCard.vue` 的标签行改为**恒渲染**；没有真标签时由 `rowKinds`
  （`ProductCard.vue:192`）给一枚 **`ghost: true`** 的签，文案与真签逐字相同（`秒杀商品`），
  靠 `.pcard-kind.ghost { visibility: hidden; }`（`ProductCard.vue:339`）藏起来。
- **判据是构造保证**：同一元素、同一 class、同一字体、同一 padding ⇒ 像素高必然相等
  （实测 `[21]` 单值）。若改成给行写 `min-height`，就得去追**字体度量**，那是另一类问题。
- ⚠️ **不能用 `display: none`** —— 那会把盒子整个撤掉，高度又没了（`.ghost` 那条注释里写明）。
- ⚠️ 隐形签**必须用 CJK 文本**，不能拿 `&nbsp;` 顶 —— 否则字体回退不同、line box 高度不同。
- 隐形签带 `aria-hidden="true"`（`ProductCard.vue:106`）：它是**占位物**，对读屏器无意义。

**截图**（本目录，本轮全部重取）：

| 文件 | 内容 |
|---|---|
| `out-b5h-detail-earphone.png` | **②** 的耳机详情页：标题 / `¥299` + `¥373.75` 划线 + 「8 折」角标 / 型号 `GD-101-9552` / 真图 |
| `out-b5h-detail-control.png` | 对照件（充电器 65W 双口） |
| `out-b5h-home-recommend.png` | 主页 41 张卡（等高） |
| `out-b5h-shophome.png` · `out-b5h-search-earphone.png` · `out-b5h-seckill.png` | 店铺页 / 搜索页 / 秒杀页 |

**同一轮的静态检查**：`npx eslint`（四个改动文件）**EXIT=0**、输出只有一条 browserslist 过期提示
（`b5h-round3-eslint.txt`）；`npm run build` ⇒ `DONE Build complete.`
**Hash `24d7e096309be325` / 2026-10-12T14:03:13.210Z**（`b5h-round3-build.txt`）。
⚠️ 按 §2.1，这两条**不算** ② 的证据，只是「没引入新问题」的旁证。

---

## 五 · 偏差与须知情（验收归用户）

1. **⚠️ 手验期间库里的数据变了，本笔必须如实报**：`goodsSelectHome` 的 `IsSubsidy` 由 **1 → 2**
   —— 多了一件 `Id=77`「无线蓝牙耳机 声谷 Pro 降噪」（`MerchantId=nlzzGB4vgV`，`ProductCode=GD-102-AF27`）。
   ⇒ 主页「为你推荐」上**现在确实能看到国补绿标**（实测带真标签的卡：
   `无线蓝牙耳机 声谷 Pro 降噪[国补]` / `机械键盘 87 键 茶轴[秒杀商品]`）。
   这条**推翻了** `docs/agent/evidence/c3-block5/b5h-bug2-bug3-cards.md` §四 第 3 条
   「共享面今天看不到国补绿标」的结论（那句的前提是「全库唯一国补商品 = 那件已售完的手环」，前提已不成立）；
   已在原文件末尾加**补记**（§七）指向本节，过滤器语义本身**未变**。秒杀 2 / 售完 1 / 总行 43 未变。
2. **② 的「中招面」比现象窄**：只有「有划线原价」的商品会踩到那行（§二 第 3 点），全库**只有 1 件**
   （同码 2 行）。⇒ 反过来说，**本笔修之前，随机点 43 件里的任意一件，41 件是好的** —— 现象难以复现的力学原因在此。
3. **① 是构造层面的统一**：隐形签的文案**借用**真签那句「秒杀商品」。任何**按 `.pcard-kind` 数标签**的
   断言 / 脚本**必须**先 `filter(k => !k.classList.contains('ghost'))`，否则会把普通商品全报成秒杀红标
   （本笔第一次跑探针就是这个结果：主页 41 张全报红标，已修）。这条已在 `probe-b5h-bug123-page.mjs`
   的 `DUMP` 注释里写明。
4. **⚠️ a3 ⇒ 闸关**（`app.auth.enabled=false`）：本文件所有「绿」都是**闸关下**的绿。
   本笔没动任何鉴权 / 白名单 ⇒ 但**匿名可读**一类判据在默认 profile 下**只有默认 profile 能验**。
5. **本笔没动文档类收口**：`progress` / `slot-registry` / `context-packs` / `db-schema` / `api-contract`
   **一行未动**（口径：留到块 5 收口统一做）。被改的只有**源码注释**与**证据文件自身**。

---

## 六 · drift ledger（本笔的 refs 前后对照）

- **before** = `scripts/refs-b5h-after.txt`（上一笔的末次快照：**4453 条 / token 集 2567**）
- **after** = `scripts/refs-b5h2-after.txt`（**写完本文件之后**再跑一次 —— 本文件自己也在扫描根里：
  **4524 条 / token 集 2589**）
- 判据（比 **token 集合**，不比原始产物；比对用 `LC_ALL=C sort` 两侧同口径）：
  **LOST = 0**，无 `<` 行；**NEW = 22**（2567 + 22 = 2589，数目自洽）。

### 6.1 只有 **3 条**来自源码 / 探针（本笔真正的落码产物）

`grep` 逐条查过「谁写的」，这三条是**唯一**有非证据文件作者的：

| 新 token | 引用写在哪（`grep` 实测） | 目标行实测 |
|---|---|---|
| `ProductPage.vue:520` | `GoodsAdd.vue:526` 的修正按语 · `SeckillApplyPage.vue:155` 的修正按语 · `docs/美化/probe-b5h-matrint-merchant-pages.mjs:6` | `return Number.isInteger(r) ? String(Math.trunc(r)) : String(r)`（**已修正**） |
| `GoodsAdd.vue:526` | `docs/美化/probe-b5h-matrint-merchant-pages.mjs:8` | `const fmtRate = (v) => (Number.isInteger(v) ? … )`（**已修正**） |
| `SeckillApplyPage.vue:155` | `docs/美化/probe-b5h-matrint-merchant-pages.mjs:9` | `const qtyBad = quantity.value !== '' && (… !Number.isInteger(q))`（**已修正**） |

⇒ 三条都是**修正按语自己写出来的互引**（「同批误用，见 `ProductPage.vue:520`」那类），
且**逐条回读确认指向的都是修正后的行** —— 不是扫出来就算。

### 6.2 另 **19 条**是**本文件（这份证据）自己写出来的**，不是源码漂移

口径说明（免得下一轮误读）：`refs-scan` 对**证据文件本身**也照扫，而 §二 / §三 / §四 / §六 的
表格里为了可复核，写的都是带行号的互引 ⇒ 它们会以「新 token」的形式落进快照。
逐条查过作者：**以下 19 条的引用方只有本文件**（`ProductPage.vue:520` 那条同时也是 §6.1 的目标，两边都算）：

| 本文件写出的 token | 写在本文哪儿 / 是什么 | 目标行实测 |
|---|---|---|
| `ProductPage.vue:82` · `ProductPage.vue:510` · `ProductPage.vue:515` | §一 / §二 的取值链与 `hasDiscount` / `discountRate` | 模板标题取自 `route.query.GoodName` · `o > 0 && p > 0 && o > p` · `Math.round((o / p) * 10 * 100) / 100` |
| `ProductCard.vue:192` · `ProductCard.vue:339` · `ProductCard.vue:106` | §四 ① 的构造三连 | `const rowKinds = computed(…)` · `.pcard-kind.ghost { visibility: hidden; }` · `:aria-hidden="k.ghost ? 'true' : null"` |
| `ProductCard.vue:354` | §六 末条（描述 `block5-g` 那句过时） | `tokens.css:39` 那条注释（数字列对齐）现今所在行 |
| `SeckillApplyPage.vue:102` | §二 表格第三行（注释订正） | 限量非负整数那段按语 |
| `docs/agent/evidence/c3-block5/block5-g-shophome-layout.md:338` · `block5-g-shophome-layout.md:338` | §六 末条 / §七（同一目标的长短两种写法） | 「`ProductCard.vue:201 → 203`」那句 |
| `Mall-management-web/.../src/views/ProductPage.vue:520` · `…GoodsAdd.vue:526` · `…merchant/SeckillApplyPage.vue:155` | 开头产物表的**省略写成法**（`…` 缩写） | 同 §6.1 三处（其中第三种写法还会以 `merchant/SeckillApplyPage.vue:155` 这个形态**单独**落成一个 token —— 同一目标的不同写法，不是新目标） |
| `docs/美化/probe-b5h-matrint-merchant-pages.mjs:6` · `:8` · `:9` | §6.1 表格「引用写在哪」一列（把**探针文件的第几行**也写清楚了） | 那三行正是三条断言里的修正按语 |
| `:551` · `:564` | §二 表格第二行里的**裸续引**（`GoodsAdd.vue` 的调用点） | `discountError` / `discountHint` 两处调用 |

⇒ 结论：**源码侧的真实漂移只有 §6.1 那 3 条**，且方向是「新增引用、目标行正确」；
其余 19 条是**本文件成文时自己产生的**，会随本文改动而增减，**不构成代码漂移**。

- **`ProductCard.vue` 内部互引的「引用所在行」漂移**（token 本身不变 ⇒ 不进 LOST/NEW，
  由 `refs-scan` 的**已知盲区**（目标文件内部）之外的那一半照出来）。文件 260 → **411** 行
  （上一笔的类别标签 / 灰态 + 本笔的标签行占位）。逐条实测（`before 行 → after 行`）：

  | token | before | after |
  |---|---|---|
  | `HistoricalOrders.vue:66` | 77 | **88** |
  | `tokens.css:35-37` | 172 | **199** |
  | `SeckillPage.vue:289` | 192 | **219** |
  | `tokens.css:123` | 221 | **248** |
  | `SeckillPage.vue:303` | 243 | **270** |
  | `tokens.css:123` | 246 | **273** |
  | `docs/美化/主页改造.html:515` | 280 | **307** |
  | `HomePage.vue:242` | 281 | **308** |
  | `GoodsUp.vue:1037-1038` | 282 | **309** |
  | `tokens.css:39` | 319 | **354** |

  这 10 条都是 **`ProductCard.vue` 自己作为引用方**的行号位移（**目标**在别的文件里、一个字未动）
  ⇒ 不会让别人指错。已 `sed -n Np` 抽验「指的是同一件事」。
- **登记一条**收口时要处理的（本笔**不改**，与既有同类项一起进块 5 收口清单）：
  `docs/agent/evidence/c3-block5/block5-g-shophome-layout.md:338` 那句
  「`ProductCard.vue:201 → 203`」是**描述性**提及（它指的其实是文件内 `tokens.css:39` 那条注释的位置）。
  该注释现在在 `ProductCard.vue:354`（上表最后一行）⇒ 那句话的数字**过时**，
  收口时改成 `ProductCard.vue:354` 或改成不带行号的叙述。
- **三处修正一律同行替换** ⇒ `ProductPage.vue` / `GoodsAdd.vue` / `SeckillApplyPage.vue` 的
  **总行数未变**，这三支文件内其余互引**零漂移**（快照里也无 LOST 佐证）。

---

## 七 · 待收口（**块 5 收口时统一做**，本笔按口径不动文档）

- `doc-anchor-check.sh` + 收口那一轮的 refs before/after 复跑
- 重指：`ChatRoom.vue` 指向 `GoodsSelect.vue` 价签函数那条（目标已搬进 `ProductCard.vue`）；
  `router/index.js` / `AppShell.vue` 两条**改前就已陈旧**的
- **本笔新增**：`block5-g-shophome-layout.md:338` 的 `ProductCard.vue:201`（见 §六 末条）
- 订正 `cache-layer.md` / `slot-registry.md` 里与 `PriceTripleFailSmokeTest` 相关的过期叙述
- 登记新改动文件的**新行号**：`GoodsCacheService.java` / `GoodsDao.java` / `SeckillDao.java` /
  `SubsidyDao.java` / `MarketingGoodsFlags.java`
