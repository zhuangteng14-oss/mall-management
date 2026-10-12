# C3 块 5 · B 组 · commit C —— 店铺主页 `ShopHome.vue`（2026-10-12）

> 口径：先查后改 · 块内不动文档 · 验收归用户 · 加图不动结构。
> 本文是**证据**，不是方案；引用到的每个数字都指得回本目录里的产物。

## 0 · 这一笔落了什么

| 文件 | 动作 | `git numstat` |
|---|---|---|
| `src/views/ShopHome.vue` | **新建**（裸页，337 行） | 新文件 |
| `src/api/shop.js` | **新建**（3 个具名导出） | 新文件 |
| `src/api/endpoints.js` | 文件尾追加 `shop` 组 3 条 | `14/0`（242 → 256 行） |
| `src/router/index.js` | 文件**最尾**追加 `/shop` 记录 + 公开面计数判据句改写 | `27/3`（566 → 590 行） |
| `src/views/ProductPage.vue` | 「进店逛逛」改真按钮 + 撤 `data-slot` 占位 | `13/13`（**1423 行不变，净零**） |

判据来源：用户 2026-10-12 的 commit C 范围（【新页 ShopHome.vue（裸页）】/【路由】/【「进店逛逛」】/【api】四段）。

## 1 · 页面探针（`docs/美化/probe-b5c-shophome.mjs`，**不入库**）

产物：`b5-c-shophome-probe.txt`（连跑两遍，**两遍都是 73 项 / 0 失败**）。

后端**不参与** —— `docs/美化/_cdp.mjs` 的 mock 在页面内拦截 `*8088*`，只需要前端 dev server（8080）。
唯一不需要 mock 的一节是「载入态」：那一帧只存在于真往返里（mock 是就地兑现的，请求与响应之间没有时间窗），
故那一节**真打 8088**，并另外用 CDP `Network.emulateNetworkConditions` 人为加 900ms 延迟才抓得住。

覆盖用户点名的四类手验断点，逐条：

- **过滤后只本店商品**：喂 5 件（本店 3 + 别家 2，`MerchantId` 一个是 `5N1k5K2guh`、一个是 `ZZother1234`）
  ⇒ 页面只出 3 件、**别家一件不漏进来**。另有一节**不 mock**真打 8088，真库同样只出本店那 1 件
  （`{"note":null,"cards":1}`）—— 过滤逻辑在真数据上也成立。
- **关注 / 取关 / 回读**：初值 `following:false, followers:7` ⇒ 点一次变「已关注」+ 实心 `--brand` +
  **粉丝数变 8**；再点回「关注」+ **回落 7**。⚠️ 这两个数都是**后端回读值**（mock 里写侧改了状态、
  由 `followState` 的返回值下发），不是前端本地 ±1 —— 探针断言的正是这一点。
- **未登录的关注与客服都有出口**：两处都 `alert('请先登录')` **且一个请求都不发**，且都被送到
  `#/login?redirect=/shop?MerchantId=…`（有出口，不是干瞪眼）。
- **「进店逛逛」到达本页**：`ProductPage` 上那颗按钮 `disabled === false`、title 已不是占位话；
  点下去落 `#/shop?MerchantId=5N1k5K2guh`，落到的**就是**店铺主页（`.shop-page` 在），店名对得上。
- 另按 brief 补测了三态：**载入态**（抓到「正在载入店铺…」那一帧）、**空态**、**报错态**。

### 探针里两处「第一版断言写错了」的记录（留痕，免得下个人再踩）

1. **`?page=chat` 不是终态**。`consultShop` 推的是 `/customer?page=chat&otherId=…`，`beforeEach` 里
   `LEGACY_TAB`（`router/index.js:157`）把它换成路径 `/customer/msg` 并带走其余 query；随后
   `ChatRoom.vue:444` 认掉 `otherId/otherType/_t` 再 `router.replace` 清走。
   ⇒ 终态就是 `#/customer/msg`（**没有 query**），「参数没了」恰恰是「会话真开了」的证据。
   第一版断言写的是 `includes('page=chat')` ⇒ 误报 FAIL。与 `ProductPage.consultProduct` 逐字同路。
2. **载入态要带 sleep 轮询**。延迟是加在**所有**请求上的，连 `ShopHome` 那个懒加载 chunk 都要等
   900ms 才到 ⇒ 组件在那之前根本没 mount、`.note` 是 `null`。第一版连打 30 次（几十毫秒跑完）
   全落在 mount 之前 ⇒ 误报「载入态不存在」。

## 2 · 去 AI 化自评（`out-b5c-aidecheck-shophome.png`）

图：`ProductPage`（既有那页）｜`ShopHome` 买家｜`ShopHome` 空态，**同一宽度并排**。
判据用 skill 那句原话 ——「AI 味最直观的判据是『和旁边两页像一套吗』」。

**像一套**：同一套 top nav（‹ 返回 / 商城 / 用户中心）、同一套 crumb（`首页 / X`）、同一枚
`--brand-soft` 字母圆牌、同一个 `--accent` 价格色、同一款商品卡（1px `--line` 框 + `--r2` 圆角）。

**skill 点名的五类 AI 默认样，逐条对着本页自评（`probe` 里多数有断言）**：

| AI 默认样 | 本页 |
|---|---|
| 标题上方挂全大写 eyebrow | **不做**（用真标题 `本店商品`） |
| `·` 拼接的元信息串 | **不做**（两个计数**各带各的量词**：`3 件商品，7 位粉丝`） |
| `WORD — fragment` 破折号标签 | **不做** |
| 每张卡同圆角 + 同一个 `rgba(0,0,0,.1)` 阴影的 SaaS 卡墙 | **不做**：全页 `box-shadow` 计数 **0**（`bodyShadow === 0`），边界一律 1px `--line` |
| `→` 缀在按钮文案后 / fade-and-slide 入场 | **都不做** |

**头卡是「整幅横带」而不是一张卡** —— 这是本页与 `ProductPage` 那块的**有意差异**：
底用 `--zone`（`tokens.css` 自己的**区块底**）、无圆角、无阴影、通栏（探针实测宽 == 视口宽 1440）。
理由两条：`tokens.css:35` 明写「禁 · 嵌套卡片」，且「同圆角同阴影的卡墙」正是上面第 4 类 AI 默认样。

**「已关注」退回描边**：全页只有关注按钮一处会实心（`--brand` + 白字），已关注时它**退回描边**——
状态不是邀请。

## 3 · 对 brief 的增补与偏离（逐条报备，验收归用户）

1. **头卡多了一个「粉丝数」**（brief 只点名「商品数」）。理由：后端三个端点**都**回 `followers`，
   头卡为此不必多打一次往返；`followState` 又刻意**不判**「是不是自己的店」（判了会把自家店铺
   整页的粉丝数弄没）。⇒ 商品数人人可见、粉丝数**只在已登录时**追加。
   ⚠️ **匿名访客拿不到粉丝数**（`followState` 缺 `CustomerId` 回 `success:false` 且**不带**
   `followers`）⇒ 前端 `followers` 初值 `null`、**未登录不发这三条请求、也不渲染那一段**，
   **不拿 `0` 冒充**（那是「一个粉丝都没有」，一句假话）。
2. **头卡是「横带」不是「卡」**（见上节）。
3. **nav-bar 有意不放搜索框**。`ProductPage` 那个框不是装饰 —— 它写的 `PENDING_SEARCH_KEYWORD`
   由 `AppShell.vue` 消费，是「壳内搜索流」在那一页的**唯一入口**；本页没有那条流
   ⇒ 放一个点了会跳走的框 = 画一个假控件。**这是与 `ProductPage` 顶栏唯一的有意不同**。
4. **客服消息体是素话** `'你好，我想咨询一下'`，**不伪造 `[PROD]{json}`** —— `ChatRoom` 会
   `JSON.parse` 后渲染成商品卡，本页没有选中商品，编一个会让店家看到不存在的商品。
5. **「不能咨询自己的店铺」这句文案是自撰的**（brief 只裁过「商家不能关注自己」那条）。
   守卫形态照 `consultProduct`（未登录先 `alert` 再送登录页），判据与后端大小写不敏感口径对齐。
6. **空态/报错态的字**：空态 `这家店还没有上架商品。` + 一个真能走的去处「先去逛逛别的」；
   报错态把后端 `msg` **原样**带出来（`店铺信息没取到：<msg>`）+ 一颗「重试」。都按
   skill 的「空态是行动邀请，不是情绪」写。
7. **一件商品都没有的店，本页拿不到它的名字**（店名唯一来源是商品行的 `UserName`，`/shop` 只带
   `MerchantId`，仓里没有第二个按商家号问店名的公开端点）⇒ 那时店名退化成「店铺」、圆牌退化成
   「商」，**不编一个**。

**我自己看到的实话（留给手验判）**：空态下 `本店商品` 这个栏题**照旧挂着**，紧跟着一句
「这家店还没有上架商品。」—— 语义不冲突，但读起来略有重复。载入/报错态同理。改掉是一行
（`v-if` 掉 `.sec-h`），本笔**没动**，等你手验时一并裁。

## 4 · 自检（我的最小自检；最终以你手验为准）

- `npx eslint` 五个改动文件 ⇒ 干净（只有 browserslist 陈旧提示）。
- `npm run build` ⇒ `DONE Build complete.`（Hash `4312f8787ad09d58`，23330ms）。
- `git diff --numstat`：`ProductPage.vue` **13/13**（1423 行不变）、`endpoints.js` 14/0、
  `router/index.js` 27/3。⚠️ `ProductPage.vue` 的 `<script>` 加行会顶漂 `<style>` 里的行号引用
  ⇒ `enterShop` 是**借 `consultProduct` 与 `onMounted` 之间那个空行放下**的（空行已撤，
  同 `:592` 那条 `}; const setPopQtyByCode = …` 的手法）。
- ⚠️ **手验挂 a3 profile ⇒ 闸关**（`app.auth.enabled=false`）⇒ **手验绿 ≠ 默认 profile 绿**。
  关注是**身份敏感端点**，它的 403 面在 a3 下**验不到**。

## 5 · 产物清单

| 文件 | 是什么 |
|---|---|
| `b5-c-shophome-probe.txt` | 探针全量输出（73/73） |
| `out-b5c-shophome-buyer.png` | 买家视角（过滤 + 头卡） |
| `out-b5c-shophome-buyer-followed.png` | 点关注后（实心 + 粉丝数回读 8） |
| `out-b5c-shophome-buyer-unfollowed.png` | 取关后（回描边 + 回落 7） |
| `out-b5c-shophome-anon.png` | 未登录（粉丝数那一段不渲染） |
| `out-b5c-shophome-self.png` | 商家看**自己的店**（无关注按钮，粉丝数照显） |
| `out-b5c-shophome-empty.png` | 空态 |
| `out-b5c-shophome-error.png` | 报错态 |
| `out-b5c-shophome-loading.png` | 载入态（900ms 人为延迟才抓到） |
| `out-b5c-productpage-enter.png` | `ProductPage` 的 `.shop` 块（改前那颗 `disabled` 已撤） |
| `out-b5c-productpage-entered.png` | 点「进店逛逛」后落到 `/shop` |
| `out-b5c-aidecheck-shophome.png` | **去 AI 化自评**：三页同宽并排 |
