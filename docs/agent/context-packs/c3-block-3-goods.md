# context-pack: C3 块 3 —— 商品与类目治理（三端） {#pack-c3-block-3-goods-context-pack}

> 索引：`docs/agent/c3-blocks-index.md`。**只精读下文列出的区间，不要整份读任何文件**（除注明「整份」的）。
> ⚠️ **状态（2026-10-10）：落码完成 + 就地改造完成**（块 2 已 merge）；**待手验 / A3 复跑**。**A 档 4 项**：类目 = 扩 `goodstype` 加列 **+ 唯一键换代 `(ParentId, GoodType)`** / 审核 = 提审制（`goodsAdd` 不写 `goods` 行，通过才写）+ **`goods` 不加审核列**；⚠️ **原 A 档 #2（模板库 = 新表 `goods_template` + `goods` 加 3 个模板 Id 列）已被 2026-10-10 二次裁决改写**（模板改「**快照**」+ 两表合一 ⇒ 通用表 `template` + `goods` 存 3 段文本），见下文回执 8 与 `slot-registry.md §19-9`。**落地依据见 `_work/c3-block3-landing-plan.md`**。
> ⚠️ **本 pack 行号均为「本块落码时」（块 2 已 merge 后的工作区）实测**；其后已漂的按「**原 N 行**」读（如 §样板 3 处）—— **别当现值、别重指**。

## 一句话定位 {#pack-c3-block-3-goods-s2}

管理端「平台方治理」的第二块（`docs/agent/c3-modules.md:56-70`）：
**类目树（增删改）+ 基础模板库（详情/规格/售后，平台方独有）+ 上架审核（商家提审 → 通过/驳回）+ 违规处置（下架/删除）**。

三端各自的落点：

- **管理端** —— 上述四项（今天**零业务**，`business/catalog` 走占位组件）；
- **商家端** —— 商品**提审**流程（提交 → 等审核 → 通过上架）；
- **用户端** —— 浏览 / 搜索增强（把今天**画死的占位**接成真数据）+ 补块 2 挂账的 `ShelfState=0` 买家过滤。

写操作一律走块 1 的两条管子（审计 `admin_audit` + 通知 `notification`）；
**审核联动照抄块 2**（`merchant_application` 的「待办列表 + 共享收件箱」模式），本块**不新建基建**。

## 依赖块 {#pack-c3-block-3-goods-s3}

**块 1（基础设施）与块 2（用户块）**。本块对二者的用法都是**照抄 / 消费**，不扩展接口：

- **块 1**：写端点照 `GoodsController.goodsUp` 的**四步挂钩**（`.../goods/controller/GoodsController.java:248-265`；原 210-227）；
  审核结果发通知，code 用 `NotificationEvents` 里**块 1 已为块 3 预留的 3 条**（见下）。
- **块 2**：① 「提交 → 待审 → 通过」的**审核 CAS 工作流**（`merchant_application` 的 `WHERE Status='pending'` 条件 UPDATE，`_work/c3-block2-landing-plan.md:81-84`）；
  ② 「提审 → 通知管理员」走的 **`admin` 通道**（`NotificationEvents:112` 的 `ToType='admin' / ToId='*'` 共享收件箱，块 2 已开）；
  ③ `goods.ShelfState` 列（块 2 加，块 3 消费）。

⚠️ 本块**不改**块 1/块 2 的任何既有签名；唯一可能要动的公共登记簿是 `NotificationEvents` **加一条**常量（提审触达管理员，见「要新建」）—— 加常量**不改表、不改 `send`**（`NotificationEvents.java:19-24` 的「登记簿」口径）。

## 要读 {#pack-c3-block-3-goods-s4}

### 设计意图（读区间） {#pack-c3-block-3-goods-s5}

- `docs/agent/c3-modules.md:56-70` —— §2 的四视图 + 四个管理员动作。⚠️ `:65` 明写「**模板库是平台方独有权力**，普通商家不能编写」。
- `docs/agent/c3-modules.md:27` —— 块表 row 2 = 「类目树 + 基础模板库 + 上架审核 + 违规处置」四件。
- `docs/agent/c3-blocks-index.md:15` —— 块 3 在 8 块路线里的位置与依赖（依赖块 2）。
- ⚠️ **与 §八 的两处边界冲突，动手前先读**（同块 2 的「按用户要求、不按稿」处置先例）：
  · `docs/agent/c3-modules.md:265` 「**不做壳内搜索（C3 后）**」—— 而本节任务要「浏览/搜索增强（占位落地）」；
    判据是「落**占位**（`data-slot` 已在）≠ 造新面」，与 `:266` 的「不做多商家入驻」被块 2 突破是同一类偏离。
  · `:266` / `:267` —— 「不做多商家入驻」「不做细粒度权限（单一管理员角色）」：后者是本块 admin 端点**不加角色闸**的依据（同 TD-18）。

### 块 1 已落的基建 —— 本块要用的现成接口（**与块 2 完全相同，直接照抄**） {#pack-c3-block-3-goods-s6}

- `.../notification/service/NotificationService.java:114-125` —— `send(batchId, toId, toType, eventType, title, content, targetType, targetId)：boolean`。**fail-soft 原语**：永不抛、不加事务、日志标记 `NOTIFY`；`batchId` 单发也要给。
- `.../notification/NotificationEvents.java:78-85` —— **块 1 已为块 3 预留的 3 条 code，本块直接引用**：
  `GOODS_AUDIT_PASSED = "goods.audit.passed"`（`:80`）/ `GOODS_AUDIT_REJECTED = "goods.audit.rejected"`（`:82`）/
  `GOODS_VIOLATION_REMOVED = "goods.violation.removed"`（`:85`，注释「违规商品下架 / 删除」）。
  ⚠️ **缺口**：没有「提审 → 触达管理员」这一条（对照块 2 补的 `MERCHANT_APPLY_SUBMITTED`，`:112`）⇒ 本块**建议加一条** `goods.apply.submitted`（B 档，只加常量）。
  ⚠️ `send` **不校验白名单**（`:19-24`）⇒ 加 code 不动表、不动 `send`。
- `.../admin/service/AuditService.java:73-89` —— `record(actor, action, targetType, targetId, before, after)：void`，**fail-open**（catch 一切、只打 `ERROR ... AUDIT`）。`actor` 用 `AuthSession.identify(request)`。
- `.../admin/AuditActions.java:35` —— `GOODS_UPDATE = "goods.update"`（批次 A 的样板钩子，**已存在**）；`:53-77` —— 块 2 加的 7 条，**这是本块加常量的行文范式**；`:19-26` —— 「其余写端点随各自那一笔挂」的口径。
- `.../shared/web/IdentityGuard.java:104-115` —— `requireSelfOrAdmin(request, paramId)`：自己 / 管理员 / **解析不出身份** → 放行，否则 403。⚠️ 必须在方法体**第一行**调。
- `.../shared/web/Result.java:64-133` —— `of(...)` / `ok(...)` / `fail(...)`；**禁 `putAll`**。
- `.../shared/web/GlobalExceptionHandler.java:83` —— ⚠️ `@RestControllerAdvice(assignableTypes = {MessageController, GoodsController})` —— **新 Controller 不在白名单里** ⇒ 新端点**一个参数约束都不要加**（`@NotBlank` 等会逃逸成 400 空体；类级 `@Validated` 会撞 CGLIB 断言族）。
- `.../smoke/AbstractSmokeTest.java:137` —— `@Import({...})` 真 bean 名单：⚠️ **块 1/块 2 两次踩同一颗雷**（漏 import ⇒ 整个 `@WebMvcTest` 切片起不来，77 / 237 errors）⇒ 本块新 Controller 的依赖 bean **必须同步加进这里**。

### 现状：商品域后端 —— **今天是「直上架、真删除」，无审核、无类目、无模板** {#pack-c3-block-3-goods-s7}

- ⚠️ **行号均按落码后（2026-10-10）**：`.../goods/controller/GoodsController.java:95` —— `/goods/goodstype`（**GET/POST 双形态**，全类唯一未随三层下沉的方法；原 82 行）· `:122` `goodsAdd`（原 108）· `:171` `goodsSelect`（原 133）· `:185` `goodsSelectHome`（原 147）· `:193` `goodsDe`（原 155）· `:218` `productCodePrice`（原 180）· `:233` `goodsUp`（原 195）· `:273` `GoodsProductCodeSelect`（原 235）· `:286` `productSearch`（原 248）—— **全部 9 个端点**（登记见 `docs/agent/api-contract.md#api-goods`）；本块**另加** `categoryGoods`（291 行起）与 `myApplications`。
- `.../goods/controller/GoodsController.java:47`（原 43 行）—— 审计挂钩**只服务 `goodsUp` 一个端点**（「横切所有写操作」的样板，其余随各自那笔挂）。
- `.../goods/service/GoodsService.java:135-136`（`goodsAdd` + `@Transactional`；原 117-118）· `:310-311`（`goodsDe`；原 300-301）· `:359-360`（`goodsUp`；原 349-350）—— 三个 `@Transactional` 写方法。⚠️ **落码后口径变了**：`goodsDe` / `goodsUp` 唯一出口前仍 `evictByMerchant`（`:339` / `:415`），**而 `goodsAdd` 提审制后一行未动 `goods` 表 ⇒ 不再调它**（原 186 行那处已随改动消失）。
- ⚠️ `.../goods/service/GoodsService.java:310-341`（原 300-320）—— **`goodsDe` = 两条真 `DELETE`**（`deleteGoods` + `deleteProductsales`），**不是**下架。既有「下架」语义**与 `ShelfState` 无关**（`ShelfState` 是块 2 为「商家降级冻结」加的，只被 `MerchantDowngradeService` 写）。⇒ 本块「违规下架」与「既有 goodsDe」是**两件事**，别混。
- `.../goods/dao/GoodsDao.java:140-141`（`deleteGoods` 的 `WHERE MerchantId=? AND GoodName=? AND ProductCode=?`；原 132-133）· `:165-169`（`updateGoods` 的 `WHERE MerchantId=? AND ProductCode=?`，**谓词无 GoodName**；原 157-161）· `:243-245`（`selectGoodsForAudit`，`SELECT *`，返回 List；原 234-236）。
- `.../goods/dao/GoodsShelfDao.java:50-51` / `:56-57` —— `delistAllByMerchant` / `relistAllByMerchant`：**只改 `ShelfState`，绝不 DELETE**。⚠️ 两条 SQL **无 schema 前缀**（这在本仓是已知例外，见该类 javadoc）。

### 现状：买家侧查询 —— ⚠️ **`ShelfState` 过滤一条都没有**（块 3 要补的就是它） {#pack-c3-block-3-goods-shelfstate}

逐条实测（三条读路径 SQL 均**无任何 `ShelfState` 条件**）：

- `.../goods/service/GoodsCacheService.java:87`（`@Cacheable("goodsHome", key="'all'")`）/ **`:92`**（SQL：`SELECT * FROM mall_management_sql.goods`，**无条件**）—— 首页。
- `.../goods/service/GoodsCacheService.java:164`（`@Cacheable("goodsByMerchant", key="#MerchantId")`）/ **`:167`**（SQL：`... WHERE MerchantId = ?`）—— 商家自己的商品。
- `.../goods/dao/GoodsDao.java:204-217`（原 196-210）—— `searchByKeyword`：**逐字符**拼 `OR GoodName LIKE '%x%'`（**未参数化、未转义**）、`FROM goods`（**无 schema 前缀**、`SELECT *`、无 LIMIT）。⚠️ **落码后口径变了**：本块已把 `AND ShelfState = 1 AND DeletedAt IS NULL` 插进这条 SQL（`:214`），原文「无 `ShelfState`」不再成立。
  ⚠️ 这条 SQL 的两个已知问题（拼串 + 无前缀）**沿用「登记未修」口径**（`docs/agent/selection-status.md:170` 类 4），**本块不顺手修**，除非类目筛选非要动它（见 A 档 1）。
- `.../goods/service/GoodsCacheService.java:416-421` —— `evictByMerchant` **四条 evict**（`goodsHome` allEntries / `goodsByMerchant` key / `goodsCodes` allEntries / `goodsPrice` allEntries）。⚠️ **买家过滤一加，任何改 `ShelfState` 的写端点收尾必须 `evictByMerchant`**，否则首页缓存还挂着已下架品。
- 挂账出处：`docs/agent/slot-registry.md:3936`（「`ShelfState=0` 的买家侧过滤 … 随块 3 做」）· `docs/agent/progress.md:1921`（同）。**⇐ 已裁，本块落地，不是选择题。**

### 现状：类目（`goodstype`）—— 平表、公开端点、**被两处消费** {#pack-c3-block-3-goods-goodstype}

- `mall_management_sql.sql:74-79` —— `goodstype`（`Id` AI PK / `GoodTypeNew` int NOT NULL DEFAULT 0 / `GoodType` char(20) NOT NULL）；`uk_goodstype_GoodType` 唯一索引（collation `utf8mb4_0900_ai_ci`）—— ⚠️ **本块换代**为 `uk_goodstype_parent_name (ParentId, GoodType)`，见「A 档裁决回执 #4」。
  ⚠️ **种子 5 条、`GoodTypeNew` 全为 0**（`GoodTypeNew` 今天**零语义**，不是父级指针）。
- `mall_management_sql.sql:54-64` —— `goods` 8 列：`Id` / `MerchantId` / `GoodName` / `Price` / `GoodImg` / `GoodType` char(20)（**存类目「名字」**）/ `ProductCode` / `GoodTypeNew` int。
  ⚠️ `goods.GoodType` 与 `goodstype.GoodType` 是**按名字软引用**（无 FK、无 Id 列）⇒ 类目改名会与既有商品脱节。
- `.../goods/service/GoodsTypeCacheService.java:45-47`（`listTypes`：`@Cacheable("goodstype", key="'all'")` + SQL `SELECT Id, GoodType, GoodTypeNew FROM ...goodstype`）· `:74-86`（`addType`：`@CacheEvict(allEntries)` + `SELECT COUNT(*) ... WHERE GoodType=?` 查重 + `INSERT`；catch 分 `DuplicateKeyException` / `Exception` 两支）· `:62-72`（唯一索引 javadoc）。
- 消费点（**改类目就会动到它们，务必先看**）：`Mall-management-web/mall-management-system/src/views/HomePage.vue:281`（`getGoodType` 读 `/goods/goodstype` → 首屏分类 chips，`:46` 点击 `searchByType`）；`.../src/views/GoodsAdd.vue:359`（`loadGoodsType` GET `/goods/goodstype` → 类型 radio `:87-121`）与 `:390`（`saveNewTypeToDB` POST 加类型）。

### 现状：管理端「商品与类目治理」面 —— **占位，零业务；且⚠️ 稿里没有这一页** {#pack-c3-block-3-goods-s10}

- `.../src/router/index.js:452` —— `business/catalog`（「商品与类目治理」）今天 `component: AdminStub` —— **本块的替换点**（照 `:451` accounts / `:456` notify 的**同行替换**做法 ⇒ 零漂移）。
- `.../src/router/index.js:434` —— `AdminStub`；`:448-463` —— admin 壳 children（11 条占位 + 3 条真页）。`AdminStub` 懒加载占位组件 `StubPage.vue`（见下条）。
- `.../src/config/admin-nav.js:44` —— 侧边栏项 `catalog`（`to: '/admin/business/catalog'`）。**入口已在** ⇒ ⚠️ `admin-nav.js` **本块不动**。
- `.../src/views/admin/StubPage.vue:43` —— 占位组件**零请求**的显式理由（「管理员今天还没有可用的业务端点…一发请求就会 401」）。
- ⚠️ **`docs/美化/` 里没有管理端「商品与类目治理」的稿**（实测：`catalog` / 类目 / 模板 稿文件 = 0）。稿只覆盖**商家端**（`pages/GoodsUp.html` / `pages/GoodsAdd.html`）与**用户端**（`pages/GoodsSelect.html` / `pages/ProductPage.html`）。
  ⇒ 管理端这一页**无稿可对**，按块 2 `AccountsPage.vue` 的**真页样板**自建（见下「样板」）。

### 样板（照抄这些真页与既有形状，别自创风格） {#pack-c3-block-3-goods-s11}

- `.../src/views/admin/AccountsPage.vue`（1144 行，块 2 建的**管理端真页**，本块管理端照它）：
  · **原 373 行** —— **页内 tab** 结构（`[入驻审核][用户治理][降级控制]`，本块对应 `[商品审核][类目树][模板库][违规处置]`）；
  · `:76-113` —— **审核卡片流**（`v-for` 卡片 + `pending` 态高亮 + 通过/驳回按钮 + **驳回理由必填** `:104`）；
  · **原 301 行** —— 端点注释块（`/admin/accounts/applications` · `application/audit`）；
  · **原 324 行** —— ⚠️ 「危险动作**要求填理由**、理由**会展示给当事人**」的界面口径。
- `.../src/views/admin/NotifyPage.vue:335-377` —— `doSend()`：`try → if(!data.success) 记 msg → catch 记网络错误 → finally`；回执模板两态 ⇒ **本块的「提交 / 通过 / 驳回」按钮照它**。
- `.../src/views/admin/AuditPage.vue:441-469` —— `load()`：错误走 `err.value = ...HTTP ${status}` ⇒ **本块的列表取数照它**。〔行号 2026-10-11 可视化段收口重指：原 301-329 行〕
- 商家端提审入口：`.../src/views/GoodsAdd.vue:440`（`submitGoods` → `endpoints.goods.goodsAdd`）；`.../src/views/GoodsUp.vue:349`（列表 → `goodsSelect`）· `:390`（下架 → `goodsDe`）。
- 用户端筛选消费点：`.../src/views/GoodsSelect.vue:181-203`（`doSearch`：空关键词走 `goodsSelectHome`、非空走 `productSearch`）。

### 现状：前端已有占位（设计稿目录 `docs/美化/` 的 `README` 第 130 行「占位四件套」= `disabled` + `data-slot` + 角标 + `title`） {#pack-c3-block-3-goods-docs}

⚠️ `docs/美化/` 的 `README` 第 217 行 —— **占位判据** = 「无后端 **且** 加它要改的**既有文件** ≤ **3 处**」；本块**只接与四项功能相关的占位**，其余（多图 / SKU 矩阵 / 批量 / 分页…）**本块不动**（多数标「超档」）。

- `.../src/views/GoodsSelect.vue:23-68` —— **分类树占位**（**静态硬编码** 5 条，二级分类注释明示「示意」）；`title`（`:26`）明写「goodstype 端点现成、表里 5 条真分类…**缺的是筛选参数**」；`:77-111`（排序/筛选占位）· `:127-139`（分页占位）。
- `.../src/views/GoodsUp.vue:44-58`（筛选/搜索占位，`:54` 的「分类」`select` title 明写「有数据源 goodstype，缺的是接线」）· `:105-109`（库存/销量列占位）· `:164`（**状态列恒为「已上架」常量**，头注明写「后端无状态机」）· `:172-177`（**「下架」按钮是 `disabled` 死控件**，title 指既有 `goodsDe`）。
- `.../src/views/GoodsAdd.vue:235-249`（详情富文本占位，title「goods 表无详情字段」）· `:298-310`（服务承诺勾选占位）· `:316-322`（保存草稿占位）—— ⚠️ 这三条与「模板库」相关。⇒ **2026-10-10 就地改造后**：前两条（详情 / 服务承诺）已由 `disabled` 占位**转真功能**（「选模板」按钮 + 可编辑 `<textarea>`，落**快照文本**），见 §19-9-3。
- `.../src/views/ProductPage.vue:226-230`（详情富文本占位）· `:236-249`（规格参数占位，title「goods 表无参数列」）—— 与「模板库」相关；`:311-323`（售后保障）**不是占位**（显式判为「静态真功能」）。⇒ **2026-10-10 就地改造推翻后半句**：`:311-323` 已接**真渲染**（`goods.AfterSale` 快照），`:226-230` / `:236-249` 亦已成真渲染；该文件 1248 → **1253** 行，落码后行号见 §19-9-4。
- ⚠️ **商家端 / 用户端今天没有任何「审核状态 / 违规」字段或占位**（实测 grep = 0）⇒ 提审态、违规态是**本块新造**的界面元素（管理端无稿可对，商家端需在 `GoodsUp.vue` 状态列 `:164` 处接真数据）。

### 表 / 前端入口 / 登记 {#pack-c3-block-3-goods-s13}

- `.../src/api/endpoints.js:12-22` —— `goods` 组 9 条（`:13` `goodstype` … `:21` `goodsProductCodeSelect`）；`:70-83`（`admin` 组）· `:113-122`（`accounts` 组，块 2 加）· `:158-172`（`template` / `templateAdmin` 两组，2026-10-10 就地改造加，**在文件尾**）。⚠️ `:26` 被 `progress.md` 按行号引 ⇒ **新增一律追加在文件尾**。
- `.../src/config/notify-events.js:52` —— **块 3 的 3 条中文标签已在**（`goods.audit.passed` / `goods.audit.rejected` / `goods.violation.removed`）⇒ 若加新 code（如 `goods.apply.submitted`）**要同步补标签**。
- `.../src/api/admin.js` —— 管理端 API 封装（本块新端点照它加函数）；`:99-113` 是**通知**发送侧两条；`:117-122` 是「通知模板两条函数**已删**」的注释。⚠️ **原有那两条通知模板函数已下线**（2026-10-10 模板表通用化 ⇒ 存取改走 `src/api/template.js` 的 `templateAdmin*`；`admin.js` 现 218 行，**行号别照抄本节**，原 122-137 行）。
- `docs/agent/db-schema.md#db-1`（`goods` 表行；原 24 行）· `docs/agent/db-schema.md#db-6-3`（`ShelfState` 列说明「买方过滤随块 3」；原 178 行）—— **本块已回填**（另新增 §6.17 / §6.18 两张新表）。⇒ **再经 2026-10-10 就地改造**：§6.17 已由 `goods_template` 改写成 **`template`**、§6.15 `notification_template` 标**已删**；文件仍 **549 行（净 0）**，两个被引行未漂。
- `docs/agent/slot-registry.md:3763` —— §十八（块 2 登记）起：**本块收口时照它的 10 小节结构**开 §十九（表 / 端点 / A 档裁决 / 门禁 / 未做待办 …）。

## 要新建 {#pack-c3-block-3-goods-s14}

> ### ⚠️ 落码后回执（2026-10-10）—— **本节以下为方案原文，实际落地以 `slot-registry.md §十九` 为准**
>
> 本块的**表 / 后端 / 端点 / 三端前端**已按方案落码（**未提交**）。方案与落地的差异：
>
> 1. **前端 4 项落 3**：管理端 `CatalogPage.vue`（新建，替换 `router/index.js:452`）· 商家端 `GoodsAdd.vue` / `GoodsUp.vue` · 用户端 `HomePage.vue` chips / `GoodsSelect.vue` 树 · `endpoints.js` / `admin.js` / `notify-events.js` **均已接**。
> 2. ⏸️ **`ProductPage.vue:226-230` / `:236-249`（下文「用户端（续）」那条）未落** —— 撞上三个方案没写、仓里也查不到的口子（**无「商品→模板」绑定** / `spec` 的 `Content` 格式未定义 / `after_sale` 面板是否接）⇒ **停并报，待用户裁**，专节见 `_work/c3-block3-landing-plan.md §十一`。
> 3. ⚠️ **下文行号取自落码前**，落码后有三处下移：`GoodsAdd.vue` 提交行 **原 437 行 → `:440`**（该文件净 +3）；`GoodsUp.vue` 样式段 `:549` → `:637`、`:549-550` → `:637-638`（净 +94）；`GoodsSelect.vue` 的 `.goods-select-view` `:305` → `:375`（+70）。**其余被引行（两文件的模板 / `<script setup>` 段）经等行数处置，行号不变**（手法见 §十九-5）。
> 4. ⚠️ **新 `CatalogController`（`POST /catalog/templates`）不在下文端点清单里** —— 落码时补上（管理端模板 CRUD 之外，还给了商家端一个只读模板口）。
> 5. ✅ **表数 16 → 18 已同步**：`ConcurrencyRunner.java:2658` 的 `KNOWN_TABLES` 已 `5 → 7`（下文 §验收 第 1 项「当前 = 5」为落码前口径）。
> 6. ⚠️ **VM 库迁移未落**（VM `192.168.229.129` 本笔不可达）—— 待办，见 §十九-6。**本机库已执行**（备份 `_work/b3-dbbackup/`）。
> 7. ✅ **`db-schema.md` 已回填**：`goods` 行 `db-schema.md#db-1`、`ShelfState` 列 `db-schema.md#db-6-3`；新表登记见 §6.17 / §6.18。
>
> ### ⚠️ 就地改造后回执（2026-10-10 二次裁决）—— **A 档 #2 被改写，以本节为准**
>
> 用户当面口径：模板从「**引用**」（商品存模板 Id ⇒ 改模板联动改商品）改「**快照**」（选模板 → 填入输入框 → **可改** → 商品存**最终文本**），并令**两套模板表合一**。**就地改、不另开笔、不提交。** 权威登记见 `docs/agent/slot-registry.md` §19-9 · `docs/agent/db-migration.md` #8 · `db-schema.md`。
>
> 8. **A 档 #2（模板库）重写**：`goods_template` 表**物理删**，改为**通用表 `template`**（`Type` varchar(96) / `Name` / `Title` / `Body` / `Enabled` / `Sort`，`mall_management_sql.sql:556-569`）；`notification_template` 一并**物理删**（行按 `Type = 'notification.<原 TemplateKey>'` 迁入，`mall_management_sql.sql:657-673`）。**规则 v2「逻辑废弃不物理删」首次破例（用户批准）**。
> 9. **`goods` 的 3 个模板 Id 列作废**：`DetailTemplateId` / `SpecTemplateId` / `AfterSaleTemplateId` **物理删**，改加 3 段**快照文本** `Detail` / `Spec` / `AfterSale`（`mediumtext`；`Spec` = 行内 `键=值`）；`goods_audit` 加同 3 列（提审快照必须携带）。
> 10. **端点/分层改写**：`/catalog/templates` 与 `/admin/catalog/template/*`、`/admin/notify/template/*` **废**；改公共读 `/template/list` + `/template/get`、管理端 `/admin/template/{list,create,update,delete}`；承载包 = 新域 **`template/`**（5 文件，与 `catalog/` 平级）—— 本节上文「后端」把模板塞进 `catalog/` 的写法**作废**。**类目部分（`CategoryDao` / `CategoryService` / `AdminCatalogController` 的类目与审核端点）不受影响。**
> 11. **前端**：新组件 `.../src/components/TemplatePicker.vue`（**只做弹窗**，触发按钮留各页）+ **6 处「选模板」入口**（`GoodsAdd` 详情/规格/售后 ×3 + `CatalogPage` 商品驳回理由 + `AccountsPage` 商家驳回/用户封禁 ×2）；`ProductPage.vue` 三处**真渲染**（Tab① 详情 / Tab② 规格 / Tab④ 售后；净 +5 行，**1248 → 1253**）。
> 12. ✅ **上文 ⏸️ 结清**：`.../src/views/ProductPage.vue:226-230` / `.../src/views/ProductPage.vue:236-249` 未落的三点**全作废** —— ①「无商品→模板绑定」⇒ 改**快照**（3 列删、存文本，「无绑定」成设计）；②「`spec` 的 `Content` 格式未定义」⇒ 批**行内 `键=值`**；③「`after_sale` 面板」⇒ 接真渲染，并**推翻**本 pack 的「`.../src/views/ProductPage.vue:311-323` 不是占位（静态真功能）」裁决（**原 117 行**）。
> 13. **表数 18 → 17**：`scripts/concurrency/ConcurrencyRunner.java:2658` 的 `KNOWN_TABLES` **7 → 6**（上文第 5 条「`5 → 7`」为**中间态**，最终 **17**）。
> 14. ✅ **VM 库迁移已落**（改「VM 补块 3 + 补本块」、**非整份重导 ⇒ 业务数据零损失**）⇒ 上文第 6 条的「VM 不可达 / 未落」**作废**；`db-migration.md` #7 状态已同步更正。
> 15. ✅ **手验已过 + 当场修掉 1 个真缺陷**（判据见 §验收 第 1 项 / 第 3 项）：`GoodsAuditDao.insert`（`.../goods/dao/GoodsAuditDao.java`）的 `VALUES(…)` 值槽比传参**多一个** ⇒ 提审恒失败；已修（**单行、净 0 行**），详见 `docs/agent/slot-registry.md §19-9-7`。⚠️ **`mvn test` / `build` / `eslint` 三绿都没拦住它**（切片测试不打真库）⇒ 下文 §验收 第 5 项的「全绿」**不等于**写路径正确。⚠️ **第 2 个真缺陷是事后从后端日志里抓到的**（`admin_audit.TargetId` 是 NOT NULL，而 `template.create` / `category.create` 两处「新增类」动作把 `TargetId` 也传了 `null` ⇒ 创建类留痕 **100% 丢失**，fail-open 吞掉、三绿与 A3 都拦不住）；已按「就地补业务标识串」修（两处单行、净 0 行），详见 `docs/agent/slot-registry.md §19-9-8`。

### A 档裁决回执（2026-10-10 用户已批 —— **落地依据，不再是选择题**） {#pack-c3-block-3-goods-a}

> 判据 = 三条硬线（**建表 / 改契约 / 删文件**）。A 档 **4 项**全部落在硬线上，逐项已裁。

1. **类目载体 = 扩 `goodstype` 加 3 列**（**不建新表**，硬线「建表」的**反面**选择）：加 `ParentId`（自引用，`0`=一级）/ `Sort` / `State`（1 启用 / 0 停用）。
   ⚠️ 连带「改契约」：**既有公开端点 `/goods/goodstype` 的返回列 +3 键**（`.../goods/controller/GoodsController.java:95` 那条 GET/POST 双形态；原 82 行）⇒ 两处消费点要跟着核（`.../src/views/HomePage.vue:281` / `.../src/views/GoodsAdd.vue:359`）。数据模型支持任意深度，**UI 只做二级**。
2. **模板库 = 新表 `goods_template`（详情 / 规格 / 售后三类）+ 商家选用**：管理端 CRUD；`goods` 加 3 个模板 Id 列，商家在提审 / 详情处选用。
3. **审核 = 历史表 + 行存在性**（新表 `goods_audit` 存**提审历史**；**`goods` 不加审核列** —— 2026-10-10 自查结论见连带①），**`goodsAdd` 改提审制**（行为变，硬线「改契约」，用户已批）：
   · **商家提审**（`/goods/goodsAdd` 新行为）：写 `goods_audit(pending)` + 通知管理员，**不写 `goods` 行**；
   · **管理员通过**：写 `goods` 行（**普通行，无审核态列**）+ CAS `goods_audit.Status='approved'` + 回填 `GoodsId` + 通知商家；
   · **管理员驳回**：`goods_audit.Status='rejected'` + `RejectReason` + 通知商家；
   · **再次提审**：新 `goods_audit` 行（**保留历史**）。
4. **类目唯一键换代**（原 A1 连带，2026-10-10 用户裁「乙」）：`uk_goodstype_GoodType`（全局）→ **`uk_goodstype_parent_name (ParentId, GoodType)`**；连带三件 = `goods` 加 `TypeId`（软引用 `goodstype.Id`）+ 回填 + **二级筛选改走 Id**（契约口径见连带③）。

> **不进 A 档、按 B/C 自裁的**（已在方案报备）：
> · **违规处置的「删」= 软删**（规则 v2「逻辑废弃」；`ShelfState` 列已在）—— 既有 `goodsDe`（`.../goods/service/GoodsService.java:310-341`；原 300-320）的**硬删行为不动**，违规处置走**新端点**。
> · **`ShelfState=0` 买家过滤** —— 块 2 已裁「随块 3」（`slot-registry.md:3936`），**不是选择题**，直接落。
> · **通知 code 新增 `goods.apply.submitted`**（提审触达管理员）+ `AuditActions` 加若干常量 + `notify-events.js` 补标签。
> · **已知缺口（登记不修）**：已上架商品的**编辑**仍走既有 `goodsUp`、**不经审核**（口径同块 2 的注册页商家选项 `LoginPage.vue:175-176`）。
> · **违规下架 vs 降级冻结下架的数据区分**（`OffShelfReason`）—— 留 §十九 待办，本块不做。
>
> ⚠️ **连带后果 3 条**（必然发生，落地按此处理）：
> ① **`goods` 加 `AuditState` 的提议已作废 —— 该列去掉**：自查（2026-10-10）第二值**一种都不存在**（违规下架 = `ShelfState`、违规删除 = `DeletedAt`、驳回/待审 = `goods_audit.Status`）⇒ 恒 `'approved'` 的死列；`goods.RejectReason` 同被 `goods_audit` 覆盖 ⇒ 一并去掉。**副产品：原「既有种子会因默认值写错而全隐」的风险整条消失。**
> ② **`goods` 加 5 列**（`TypeId` / 3 个模板 Id / `DeletedAt`）**会经三条 `SELECT *` 读路径**（`.../goods/service/GoodsCacheService.java:94` / `:170` / `.../goods/dao/GoodsDao.java:213` —— 均按落码后行号，原 92 / 167 / 205）**泄进买家 API**（`/goods/goodsSelectHome` / `/goods/productSearch` / `/goods/goodsSelect`）。方案已裁**不剥**（同块 2 的 `user` 加列）。⚠️ 口径修正：去掉 `AuditState`/`RejectReason` 是 **6 → 4**，但 A 又加回 `TypeId` ⇒ **净 5**。
> ③ **类目唯一键换代**（裁「乙」）：`(ParentId, GoodType)` 允许不同父级下同名子类目，**代价 = `goods` 的类目引用必须从「名字」升为「Id」**——今天买家筛选是**按名字**走的（`.../goods/dao/GoodsDao.java:204-217` 的 `AND (GoodType = ? OR GoodName LIKE …)`；原 196-210）；`.../src/views/HomePage.vue:46` 传 `item.GoodType`），只换唯一键不够。⇒ `goods.TypeId` + 回填 + 二级筛选走**新端点**；**`/goods/productSearch` 与 HomePage 名字路径一行不改**（详见「前端」一节的契约口径表）。⚠️ A3 的 B-2 门禁**不受影响**（并发加的都是 `ParentId=0`）。

### 表（**已定稿** ⇒ 追加到 `mall_management_sql.sql` **末尾**，`IF NOT EXISTS`，块内不 DROP；加列一律**表末** + 默认值 + COMMENT，规则 7） {#pack-c3-block-3-goods-mall_management_sql}

- **`goodstype` 加 3 列 + 唯一键换代**：`ParentId` int NOT NULL DEFAULT 0（0=一级，自引用不设 FK）· `Sort` int NOT NULL DEFAULT 0 · `State` tinyint(1) NOT NULL DEFAULT 1（1 启用 / 0 停用）；索引 `(ParentId, Sort)`；**唯一键** `uk_goodstype_GoodType` → `uk_goodstype_parent_name (ParentId, GoodType)`（连带③）。
- **新表 `goods_template`**：`Id` / `TemplateType` varchar(16) NOT NULL（`detail` / `spec` / `after_sale`，字符串码不建 ENUM）/ `Name` varchar(64) NOT NULL / `Content` mediumtext / `Enabled` tinyint(1) NOT NULL DEFAULT 1 / `created_at` / `updated_at`；索引 `(TemplateType, Enabled)`。
- **新表 `goods_audit`**（提审历史，**列镜像 `merchant_application`**）：`Id` / `MerchantId` / `BatchId` / `GoodName` / `Price` / `GoodType` / `ProductCode` / `GoodImg` / `Status`(`pending`/`approved`/`rejected`) / `RejectReason` / **`GoodsId` bigint NULL**（通过后回填，软引用 `goods.Id`）/ `SubmittedAt` / `ReviewedBy` / `ReviewedAt` / `created_at` / `updated_at`；索引 `(Status, SubmittedAt)` + `(MerchantId, SubmittedAt)` + `(BatchId)`。
- **`goods` 加 5 列**：`TypeId` int NULL（软引用 `goodstype.Id`，换代连带）· `DetailTemplateId` int NULL · `SpecTemplateId` int NULL · `AfterSaleTemplateId` int NULL · `DeletedAt` datetime(3) NULL（违规删除用）。⚠️ **不加 `AuditState` / `RejectReason`**（连带①）。
- ⚠️ **`TypeId` 回填**（现有 5 条类目名全库唯一 ⇒ 无歧义；与换代同一条 Migration 内按名字 join）：`UPDATE goods g JOIN goodstype t ON g.GoodType = t.GoodType SET g.TypeId = t.Id WHERE g.TypeId IS NULL;`
- ⚠️ **加列不幂等**（MySQL 无 `ADD COLUMN IF NOT EXISTS`）⇒ VM 库手动跑一次；CI 整份重导安全。**不写进基线 CREATE TABLE**。
- ⚠️ **表数 16 → 18** ⇒ 同笔更新 `scripts/concurrency/ConcurrencyRunner.java:2658` 的 `KNOWN_TABLES`（**5 → 7**）。

### 后端（**建议**分层 —— B 档，报备） {#pack-c3-block-3-goods-s17}

- **新域 `catalog/`**（**新建**，与 `goods/` 平级）承载类目 + 模板：`dao/CategoryDao`（读写 **`goodstype`** 表，不建新表）· `service/CategoryService` · `dao/GoodsTemplateDao` · `service/GoodsTemplateService`；类目树读取**沿用既有** `/goods/goodstype`（不新开端点）。
- **新域 `goods/` 追加**（**改既有**不建新域）：`GoodsAuditDao` / `GoodsAuditService`（提审单 + 审核 CAS）；`GoodsCacheService` 三条读路径 SQL **加 `ShelfState = 1 AND DeletedAt IS NULL`**；**`GoodsDao.searchByKeyword`（`:196-210`）一行不动**（二级筛选走新端点）。
  ⚠️ **`GoodsService.goodsAdd` 改提审制（本块最重的行为变更）**：落盘后写 `goods_audit(pending)`、**不再写 `goods`/`productsales`**；**审核通过**路径复用原写口（`insertGoods` + `insertProductsales`，**普通行**）与 CAS **同事务**，收尾 `evictByMerchant`。
- **二级类目筛选（新端点）**：`/goods/categoryGoods`（入参 `TypeId` + 可选 `keyword`），不复用 `searchByKeyword`。
- **`GoodsTypeCacheService.addType`（连带）**：应用层查重（`:77` 的 `WHERE GoodType = ?`）**加 `AND ParentId = ?`**，与换代后的唯一键同口径。
- **`admin/` 域追加**：`AdminCatalogController`（`@RequestMapping("/admin/catalog")`，**不加角色闸**，TD-18）+ 审核 / 类目 / 模板 / 违规四个工作流 Service。
- **端点**：既有只改行为的 = `/goods/goodstype`（+3 键）· `/goods/goodsAdd`（改提审）；新增 = **`/goods/categoryGoods`** · `/goods/myApplications` · `/admin/catalog/category/create|update|delete` · `/admin/catalog/template/list|create|update|delete` · `/admin/catalog/applications` · `/admin/catalog/application/audit` · `/admin/catalog/violation/delist|delete`。
- `AuditActions.java:53-77` **加常量**（照该区间范式）：`category.create` / `category.update` / `category.delete` / `template.create` / `template.update` / `template.delete` / `goods.apply.create` / `goods.apply.audit` / `goods.violation.delist` / `goods.violation.delete`（**新增类动作 `BeforeJson` 传 `null`**、`TargetId` 用 `BatchId`）。
- `NotificationEvents.java:78-85` —— 3 条**已实测存在**，直接引用；**加 1 条** `goods.apply.submitted`。
- ⚠️ **测试面必改**：① 新 Controller 依赖的真 bean 同步进 `AbstractSmokeTest.java:137` 的 `@Import`；② **`GoodsWriteTxAtomicityTest` 用例 1/2/3 按新语义重写**（原「goodsAdd 写 goods + productsales 各 1 行」随提审制失效，判据平移到审核通过路径）；③ **`GoodsTypeUniqueIndexTest:134` 的索引名断言**（`uk_goodstype_GoodType` → `uk_goodstype_parent_name`，唯一键换代连带）；④ 若改 `goodsAdd` 返回文案，同笔更新 `ResultByteEquivalenceTest:81` 的字符串（键形状不变）。**完整清单（全仓实测 9 个用例文件 + 新增 1 个）见 `_work/c3-block3-landing-plan.md` §七**。新 Controller **不得**带类级 `@Validated`。

### 前端（三端） {#pack-c3-block-3-goods-s18}

- **管理端真页** `.../src/views/admin/CatalogPage.vue`（**新建**）：页内 tab —— 商品审核（`pending` 列表 + 通过/驳回）/ 类目树（增删改）/ 模板库（三类 CRUD）/ 违规处置（下架/删除）。**同行替换** `router/index.js:452` 的 `AdminStub`。
- **商家端提审**：`GoodsAdd.vue`（`:440` 提交**仍走 `/goods/goodsAdd`**，行为改为提审、提示语改「已提交审核」；落码前该行作 437 行，本笔净 +3 后下移）+ `GoodsUp.vue`（`:164` 状态列接真数据 —— ⚠️ **待审/驳回态不在 `goods` 表**，需并表 `/goods/goodsSelect` ∪ `/goods/myApplications`；`:44-58` 筛选接线）。
- **用户端 —— 契约口径（落码前必读，避免误改既有前端）**：① `.../src/views/HomePage.vue:46` 一级 chips **今天已能用**，只加一行 `ParentId===0` 过滤（否则二级混进 chips），**仍传类目名** —— 一级名在 `(0, GoodType)` 下唯一 ⇒ **不改契约**；② `.../src/views/GoodsSelect.vue:23-68` 二级树**今天没接线**（静态硬编码占位），接线后改调**新端点** `/goods/categoryGoods`、**传 `TypeId`** ⇒ **不算改契约**（无既有调用者被改）；③ `.../src/views/GoodsSelect.vue:181-203` 搜索框与 `/goods/productSearch` **一行不动**。⇒ **不是「前端不变」**，是「要改的只有那个**今天还没接线的树**，既有名字路径一行不动」；后端**不做**「按名字翻译成 Id」（手里只有字符串时必然歧义）。
- **用户端（续）**：`.../src/views/ProductPage.vue:226-230` / `:236-249` 详情 / 规格接模板内容。
- `.../src/api/endpoints.js` **文件尾追加**（新 `catalog` 组 + `goods` 组补提审/类目）；`.../src/api/admin.js` 追加对应函数；若加 code，`.../src/config/notify-events.js` 同步补中文标签。

## 约束 {#pack-c3-block-3-goods-s19}

1. **fail-open / fail-soft**：审计与通知**永不抛** —— 它们失败**不许**把业务成功翻成 500（同块 1/块 2）。
2. **逻辑废弃，不物理删**（规则 v2）：类目停用 = `State=0`；违规处置 = 软删。**禁止 `DROP` / `DELETE`**；留痕靠 `admin_audit`。
3. **admin 端点不加角色闸**（TD-18）—— 与 `AdminAuditController` / `AdminAccountsController` 的接缝一致。
4. **既有端点原则不动**（块 2 口径）：本块**动既有契约的三处**都已裁并显式列出 —— ① `/goods/goodstype` 返回列 +3 键；② `/goods/goodsAdd` 改提审；③ `goodstype` 唯一键换代（`(ParentId, GoodType)`）。**其余一律「新增」**；`/goods/productSearch` 与 `goodsDe`/`goodsUp` 的既有行为**一行不动**。
5. **买家过滤连带**：任何改 `goods.ShelfState` / 审核态的写端点，收尾必须 `GoodsCacheService.evictByMerchant`（`.../goods/service/GoodsCacheService.java:416-421`），否则首页 / 列表缓存挂着旧可见性。
6. **缓存四桶**：`goodsHome`（all）/ `goodsByMerchant`（key=MerchantId）/ `goodsCodes`（all）/ `goodsPrice`（all）—— 新增写路径要按这四桶判失效面。
7. **时区**：新表时刻列一律 `CURRENT_TIMESTAMP(3)`（DB 存 UTC），**前端渲染再转**浏览器时区。
8. **净零行数**：`router/index.js` / `endpoints.js` / `nav.js` / `admin-nav.js` 都有被按行号引的行（当前最大被引目标行：`router/index.js` = **451**、`endpoints.js` = **84**、`nav.js` = **61**、`admin-nav.js` = **43**）⇒ **新增一律追加在文件尾或同行替换**；动到被引行前后必须重跑 `refs-scan.sh`。
9. ⚠️ **手验提醒（每次）**：`application-a3.properties:98` 的 `app.auth.enabled=false` —— **a3 profile 闸关，手验绿 ≠ 默认 profile 绿**。本块端点若有鉴权/登录态语义，**必须在默认 profile 再验一遍**。

## 验收 {#pack-c3-block-3-goods-s20}

**核心 5 项必跑**（提速规则 2026-10-09 起；其余抽查）：

1. **建表**：**2 张新表**（`goods_template` / `goods_audit`）+ **2 张加列**（`goodstype` 3 列 / `goods` 5 列）+ **唯一键换代** + **`TypeId` 回填** 应用到 **VM 库**（**不整份重导** —— 会 DROP 现有表）；`information_schema` 见新表 / 新列，`SHOW INDEX FROM goodstype` 见 `uk_goodstype_parent_name`，`goods.TypeId` **`TypeId IS NULL` 行数 = 0**（种子实际 **5 行**，判据与行数无关）。
   ⚠️ **CI 连带**：表数 16 → 18 ⇒ `scripts/concurrency/ConcurrencyRunner.java` 的 P3 门禁**会变红**，必须同笔更新 `KNOWN_TABLES`（**已由 5 → 7 落定**，`ConcurrencyRunner.java:2658`；P3 判据 `tables == 11 + known` ⇒ 期望 `18`；口径见 `slot-registry.md §十七`）。
2. **编译**：`cd Mall-management-server && ./mvnw -q -DskipTests compile` 退出 0。
3. **主链路**（真后端 + VM 库，**不 mock**）：
   · 类目：管理端新增一级 + 二级 → 前端 GoodsSelect 分类树显示 → 点击筛选（**按 `TypeId` 走新端点**）→ 只出该类目商品；**两个不同父级下的同名二级都要能建**（唯一键换代的验收点）；
   · 审核：商家提审 → 落 `goods_audit(pending)`、**`goods` 表零新行** + **管理端收件箱收到 `goods.apply.submitted`** → 管理端列表见到 → **通过** ⇒ `goods` **新增 1 行** + `goods_audit.Status='approved'` + 回填 `GoodsId`、买家可见 + 商家收到 `goods.audit.passed`；**驳回**路径 ⇒ `goods_audit.Status='rejected'` + 理由 + `goods.audit.rejected`、**`goods` 仍零新行**；
   · 违规：管理端下架 / 删除 → 买家侧**立即查不到**（三条读路径 + 新端点均过滤）+ 商家收到 `goods.violation.removed`。
4. **块 2 挂账结清**：`ShelfState=0` **或 `DeletedAt IS NOT NULL`** 的商品**在 `/goods/goodsSelectHome` / `/goods/productSearch` / 商家自己的列表 / 新端点 `/goods/categoryGoods` 里都不再出现**（SQL 实测已加过滤）。缓存失效后不残留。（原「`AuditState<>'approved'` 过滤」随该列去掉而取消。）
5. **不回归**：`./mvnw -B test` **全绿**（新 Controller 必须同步 `AbstractSmokeTest.java:137` 的 `@Import` 名单）+ 既有 A3 全绿。⚠️ **提审制 + 唯一键换代连带的用例改写见 `_work/c3-block3-landing-plan.md` §七**（`GoodsWriteTxAtomicityTest`、`GoodsTypeUniqueIndexTest` 两处主受害；`GoodsAddUploadFailSmokeTest` 必复跑）。

**抽查**：`bash scripts/check-context-packs.sh`（本 pack 0 失效引用）；`refs-scan.sh`（动被引行时）；审计三态（正例有行 / 负例无行 / 有改前态的动作 `BeforeJson` 非空）；权限三档（非管理员传**别人的** `MerchantId` → 403 / 自己 → 放行 / 管理员 → 放行）。

## 扩展阅读（不够时按指针读） {#pack-c3-block-3-goods-s21}

- `docs/agent/context-packs/c3-block-2-user.md` —— **本块的模板**（结构与行文照它）；其「要新建」的审核 CAS / 收件箱是块 3 审核的直接来源。
- `docs/agent/context-packs/c3-block-1-infra.md` —— 挂钩四步与两条管子的完整口径；本块动笔前读它的「约束」一节。
- `_work/c3-block2-landing-plan.md` —— 块 2 的**落地方案**（本块方案照它的七节结构：裁决回执 / 表 / 后端 / 前端 / 验收 / 登记 / 零漂移手法）。
- `docs/agent/context-packs/three-layers.md` —— Controller / Service / DAO 三层约定。
- `scripts/probes/probe-i2-e2e.mjs` —— 端到端验收脚本样板（本块写 `probe-c3-block3-e2e.mjs`，落 `scripts/probes/`，照它：真后端 + VM 库、逐条断言、机器可读结果 JSON、失败非零退出）。
- `docs/agent/db-schema.md`（**已回填** `db-schema.md#db-1` / `#db-6-3` + §6.17 / §6.18）· `docs/agent/db-migration.md`（**已登记 #7**，含注意 10）· `docs/agent/slot-registry.md §十九`（本块的登记，结构照 §十八）。
- `docs/agent/api-contract.md#api-goods` —— `/goods` 既有 9 端点（本块新增端点**同样只在 slot-registry 登记**，是否回填 `api-contract.md` 留给收口裁决）。
