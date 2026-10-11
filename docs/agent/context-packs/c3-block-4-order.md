# context-pack: C3 块 4 —— 订单治理（三端，含购物车拆分） {#pack-c3-block-4-order-context-pack}

> 索引：`docs/agent/c3-blocks-index.md`。**只精读下文列出的区间，不要整份读任何文件**（除注明「整份」的）。
> ⚠️ **状态（2026-10-10 更新）：已落码 —— 构建/测试绿 + 两库已迁移；✅ 手验跑一轮（2026-10-10，回库核对；抓到并已修 `truncated` 谎报）；＋ 补丁〈模拟支付 / 物流全流程〉已落（2026-10-10，表数 18 → 19 —— 见文末「补丁」节）；未提交。** 块 3 已 merge（`fbf5c00`）；A 档 3 项已由用户 **2026-10-10 逐项批**（A1 建 `cart` / A2 `order` 加 `Quantity` / A3 只沿既有 CAS 边 + 3 端点归属校验）。落码结果与验收状态见 `slot-registry.md §二十`；契约以 `api-contract.md 附录 C` 为准。
> ⚠️ **本 pack 的「现状」坐标全部是「块 4 落码前」的实测值 ⇒ 一律按「原 N 行」读，不要当现值用。**（例：文中的 `orderAdd` 在 `OrderController.java:70` 是**原 70 行**，现值 **`:95`**；`OrderDao.java` 两条 `selectByMerchant` SELECT 的原 138/141 行，现值 **`:146/:149`**。）块内**回指旧行号一律写「原 N 行」不带冒号**，否则 `check-context-packs.sh` 会把 `` `:数字` `` 当同行简写误归属。
> ⚠️ **本 pack 所有行号字面仍保留落码前快照**（`check-context-packs.sh` 复核的就是那份快照）；**主要重指对照**（原 → 现，2026-10-10 实测）：`OrderController.java` 原 50/70/85/158/165/172 行 → 现 `:63` / `:95` / `:111` / `:184` / `:197` / `:215`；`OrderService.java` 原 177/205/456 行 → 现 `:199` / `:228` / `:562`；`OrderDao.java` 原 82/94/108/117 行 → 现 `:86` / `:102` / `:116` / `:125`；`AbstractSmokeTest.java` 原 156 行 → 现 `:160`。⚠️ **重写类锚点已不存在**：`ShoppingCart.vue` 原 266/302 行（该页已改成「真购物车」）· `OrderPane.vue` 原 2/3 行（已升格为两 tab 容器）—— 见 `slot-registry.md §二十`。**第 70 行与第 79 行的 `OrderDao` 引用已在原地重指**（第 70 行的 4 个 + 第 79 行的 1 个；其余行未动）。

## 一句话定位 {#pack-c3-block-4-order-s2}

管理端「订单治理」块（`docs/agent/c3-modules.md:67-73`）：**订单全量视图（按用户/商家/状态/时间筛）+ 异常监控（超时/纠纷/退款）+ 介入（改状态/强制退款/强制完成）+ 通知双方**；
并**同时**把用户端的 **`/customer/cart` 拆成「真购物车 + 订单中心」**（`docs/agent/c3-modules.md:263` 早已裁「购物车拆分属批 4 订单块，随该块一起做」）。

三端各自的落点：

- **用户端** —— `/customer/cart` 改**真购物车**（加购 / 改量 / 结算）；`/customer/orders` 新建**订单中心**（付款 / 物流 / 收货）；商品详情弹窗的「结算」真付款；
- **商家端** —— **销售 tab**（我卖的，发货，= 既有 `OrderCenter.vue`）/ **购买 tab**（我买的，继承用户端）；
- **管理端** —— 上述四项（今天**零业务**，`/admin/business/orders` 走占位组件）。

⚠️ 本块的**关键联动 = 通知管道的第三次实战**：商家发货 → 通知买家；用户收货 → 通知商家；管理员介入 → 通知双方。
两条 code（`order.intervened` / `order.notice`）**块 1 已为本块预留**（见「要读 → 块 1 已落的基建」）。

## 依赖块 {#pack-c3-block-4-order-s3}

**块 1（基础设施）、块 2（用户块）、块 3（商品块）**。本块对三者的用法都是**照抄 / 消费**，不扩展接口：

- **块 1**：写端点照块 2/块 3 的**挂钩形状** —— 审计 `AuditService.record(...)`（fail-open）+ 通知 `NotificationService.send(...)`（fail-soft）；
  订单的两条通知 code **块 1 已预留**（`.../notification/NotificationEvents.java:87-93`）。
- **块 2**：① 「提交 → 待审 → 通过」的 **CAS 工作流**（`WHERE 旧态` 条件 UPDATE → `rows>0/rows==0` 两分支），本块**介入动作**照它；
  ② `admin` 通道共享收件箱（`ToType='admin' / ToId='*'`）；
  ③ 商家降级冻结闸 —— **订单侧已在位**（`OrderService.java:205` 的 `freezeRejection`），本块**不动**。
- **块 3**：**提审制**（`goods` 直上架 → `goods_audit` 提审）已落；**订单侧无此概念**，但块 3 的**「新表 + 尾部追加 Migration 块 + `information_schema` 守卫」**手法是本块建表的模板。

⚠️ 本块**不改**三块的任何既有签名；可能动的公共登记簿只有 `NotificationEvents`（**无需加**，两条已预留）与 `AuditActions`（**要加** `ORDER_*` 常量，加常量不改表、不改 `send`）。

## 要读 {#pack-c3-block-4-order-s4}

### 设计意图（读区间） {#pack-c3-block-4-order-s5}

- `docs/agent/c3-modules.md:67-73` —— §2 `### 3. 订单治理` 的三行：全量订单 / 异常监控 / 操作日志，各配一个动作（介入改状态 / 强制退款·强制完成 / 通知双方）。
- `docs/agent/c3-modules.md:119-124` —— §9 状态流转「**订单生命线**」四视图（**归块 7**，但以订单为数据源 ⇒ 本块的字段决定块 7 能不能画）。
- `docs/agent/c3-modules.md:263` —— §八 边界：**购物车拆分属批 4 订单块**（原「C3 后第一笔」口径已废）。⇐ **本块落地，不是选择题。**
- `docs/agent/c3-blocks-index.md:16` —— 块 4 在 8 块路线里的位置与依赖（依赖块 3）。
- ⚠️ **与 §八 的边界冲突，动手前先读**：`docs/agent/c3-modules.md:265`「不做壳内搜索」/ `:266`「不做多商家入驻」/ `:267`「不做细粒度权限」—— 后者是本块 admin 端点**不加角色闸**的依据（同 TD-18）。

### 块 1 已落的基建 —— 本块要用的现成接口（**与块 2/块 3 相同，直接照抄**） {#pack-c3-block-4-order-s6}

- `.../notification/service/NotificationService.java:120-125` —— `send(batchId, toId, toType, eventType, title, content, targetType, targetId)：boolean`。**fail-soft 原语**：永不抛、不加事务、日志标记 `NOTIFY`；`batchId` 单发也要给。
- ⚠️ **`.../notification/NotificationEvents.java:87-93` —— 本块的两条 code 块 1 已预留，直接引用**：
  `:90` `ORDER_INTERVENED = "order.intervened"`（管理员介入订单）/ `:93` `ORDER_NOTICE = "order.notice"`（订单通知相关方）。**本块不新增 code。**
  ⚠️ `send` **不校验白名单**（`:19-24`）⇒ 引用已有 code 不动表、不动 `send`。
- `.../admin/service/AuditService.java:73-86` —— `record(actor, action, targetType, targetId, before, after)：void`，**fail-open**（catch 一切、只打 `ERROR ... AUDIT`）。`actor` 用 `AuthSession.identify(request)`。
  ⚠️ **`TargetId` 是 NOT NULL**（`mall_management_sql.sql:282`）—— 块 3 就是在这里把创建类动作的留痕 **100% 丢光**（TD-20）。本块的 `ORDER_*` 留痕**一律传 `OrderId`**（订单业务标识串）。
- `.../admin/AuditActions.java:53` —— **块 2 加的 7 条**，这是本块加常量的**行文范式**；`:140` `TEMPLATE_CREATE` 是**文件尾**（块 3 加）⇒ 本块新常量**继续追加在文件尾**（零漂移）。
- `.../shared/web/IdentityGuard.java:73-82` —— `requireSelfOrAdmin(request, paramId)`：自己 / 管理员 / **解析不出身份** → 放行，否则 403。⚠️ 必须在方法体**第一行**调。
- `.../shared/web/Result.java:64-133` —— `of(...)` / `ok(...)` / `fail(...)`；**禁 `putAll`**。
- `.../shared/web/GlobalExceptionHandler.java:83` —— ⚠️ `@RestControllerAdvice(assignableTypes = {...})`：**新 Controller 不在白名单里** ⇒ 新端点**一个参数约束都不要加**（`@NotBlank` 等会逃逸成 400 空体；类级 `@Validated` 会撞 CGLIB 断言族）。
- `.../smoke/AbstractSmokeTest.java:156` —— `@Import({OrderStateMachine, OrderService, OrderDao, ...})` 真 bean 名单：⚠️ **块 1/块 2/块 3 三次踩同一颗雷**（漏 import ⇒ 整个 `@WebMvcTest` 切片起不来）⇒ 本块新 Controller 的依赖 bean **必须同步加进这里**；`:304` `@MockitoBean OrderTransitionRecorder` 是订单域既有 mock 口。

### 现状：订单域后端 —— **三位状态机 + CAS + 8 端点；无购物车、无通知、无审计** {#pack-c3-block-4-order-cas}

- `.../order/controller/OrderController.java:50` —— `@RequestMapping("/order")`，**8 个端点全是 `@PostMapping`**（无 GET）：
  `:70` `orderAdd` · `:95` `orderSelect` · `:108` `orderDeliveryIfSelect` · `:123` `orderDeliverySelect` · `:139` `historicalOrdersSelect` · `:158` `orderPayIfUp` · `:165` `orderReceiveIfUp` · `:172` `orderDeliveryIfUp`
  （登记见 `docs/agent/api-contract.md#api-order`）。⚠️ 类注释反复告警「**形参名与顺序必须逐字保留**」（`-parameters` 靠形参名解析入参）。
- ⚠️ **身份门控两档**：5 个读/建端点在方法体第一行 `IdentityGuard.requireSelfOrAdmin`（以 `OrderController.java:85` 为例）；
  **3 个 CAS 端点 `:158`/`:165`/`:172` 只收 `orderId`、不判归属** —— **缺口**（登记 `progress.md §5-AF`，本块是否补属 A 档 3 的连带）。
- `.../order/model/OrderState.java:44` —— 8 种三位组合：合法 5（`CREATED(0,0,0)` / `PAID(1,0,0)` / `SHIPPED(1,1,0)` / `RECEIVED_NODELIVERY(1,0,1)` / `COMPLETED(1,1,1)`）+ 非法 3；`:100` `legal()`；**`:137` `isInitial()` = 出生闸白名单恰为 `{(0,0,0),(1,0,0)}`**（全仓唯一判据处）。
- ⚠️ **状态机唯一真值源 = `.../order/model/OrderStateMachine.java`**（守卫表静态块 `:98` / `updateSql()` `:136`）—— **用户明令本块不碰**（见「约束 4」）。⚠️ **2026-10-10 超时取消**在 `updateSql(PAY)` 尾部追加 `AND CancelledAt IS NULL` —— 属**付款业务规则**、**不违「约束 4」**（该约束只管「介入动作」），仍**不新增状态位**，见「约束 4」。
- `.../order/service/OrderService.java:177` —— `orderAdd`；**三道锁外闸**（全部在锁**之外**）：价格闸 `:190`（上界 `10_000_000f`）/ **出生闸 `:196`**（`OrderState.isInitial`）/ 商家冻结闸 `:205`（块 2 的降级冻结）⇒ 然后 `:209` `runLocked(ORDER_ADD, OrderId)` → `:368` `addLocked`（`OrderDao.countByOrderId` 查重 + `insertOrder` 落库）。
- 写路径 CAS：`OrderService.java:456` `casTransition(action, orderId)`（`orderStateMachine.updateSql(action)` → `orderDao.casUpdate` → `rows>0` 调 `applied` / `else` 调 `rejected`）；三入口 `:423` `payIfUp` / `receiveIfUp` / `deliveryIfUp`。
- `.../order/dao/OrderDao.java` —— `:67` `countByOrderId` · `:86` `insertOrder` · `:102` `record OrderRow`（**record 加分量 ⇒ 唯一构造点漏改会编译失败，是好信号**）· `:116` `casUpdate` · **`:125` 的 `ReceiveIf != 1`**（「购物车」谓词的物理出处）。⚠️ 这四个已按 **C3 块 4 落码后**就地重指（原 82 / 94 / 108 / 117 行；`:67` 未动，本文件其余「现状」坐标仍是落码前的，见文件头横幅）。
- ⚠️ **订单链禁 `@Transactional`**（`OrderService` / `OrderDao` 类注释的**全项目唯一禁令**）—— 结构断言 `.../shared/lock/DistributedLockServiceTest.java:419`（`orderChainIsNotTransactional`）会红。**改这块前必读该断言。**
- 锁：`.../shared/lock/DistributedLockService.java:237` `runLocked`；`:519` `LockName` 枚举（`ORDER_ADD` / `ORDER_PAY` / `ORDER_RECEIVE` / `ORDER_DELIVERY`）；**fail-open**。
- `mall_management_sql.sql:141` —— `CREATE TABLE \`order\``（列 = `Id` / `MerchantId` / `CustomerId` / `OrderId` / `Price` float / `ProductInformation` / `Images` / `PayIf` / `DeliveryIf` / `ReceiveIf` / `CreateTime` varchar(50) / `SettlementDate` varchar(50)）—— ⚠️ **无 `Quantity`、无单价、无商品 Id**（商品信息是 `ProductInformation` 文本）⇒ 真购物车的「改量」**无处可存**（见 A 档 2）。
- `mall_management_sql.sql:224` —— `CREATE TABLE IF NOT EXISTS \`order_state_transition\``（B3 新增，块 7 时间线视图的数据源；唯一写入点 `OrderTimelineWriter`，**fail-soft 永不抛**）。
- ⚠️ **订单域今天零 `notificationService.send` / 零 `auditService.record`**（对 `order` 包 grep = 0 命中）⇒ **本块是通知管道第三次实战的挂点**，全部要新挂。

### 现状：购物车 —— **后端零实体；`/customer/cart` 是「购物车 + 订单列表」混合体** {#pack-c3-block-4-order-customer}

- ⚠️ **后端没有任何购物车/结算/草稿实体、表或端点**：grep `cart|Cart`（Java）= **0**；grep「购物车」= 3 处**全是注释**，指的都是 `orderSelect`（`.../order/dao/OrderDao.java:125` 的 `ReceiveIf != 1`）。`settle` 只命中 `SettlementDate` 列名，**无结算业务逻辑**。
- 前端 `.../src/views/ShoppingCart.vue`（720 行）= **客户唯一的订单列表页**，实为混合形态：
  · **真功能** —— 订单操作列 `:125`（付款 `:302` 调 `order.orderPayIfUp` / 收货 **原 324 行** 调 `order.orderReceiveIfUp`）；取数 `:256` `loadOrders`（`:266` 调 `order.orderSelect`）。
  · **占位** —— 商品行区块 `:77`（整块 `data-slot`）；「编辑」`:45` `disabled`；吸底结算栏 **原 197 行** 的「去结算」**`:213` 是 `disabled` 死控件**（title「结算流程无端点」）；猜你喜欢 `:182`。
  · 商家复用**同一组件**（`.../src/views/merchant/OrderPane.vue:3`）。

### 现状：订单域既有基建（可直接复用的现成形状） {#pack-c3-block-4-order-s9}

- `.../order/model/OrderStateExecutor.java` —— B3 异步出口（有界 1024、丢最旧、daemon），时间线落库走它。
- `.../order/model/OrderTransitionRecorder.java` —— 观测编排（读回三位 → 前后态 → 指标/事件 → 投递时间线）；含 `created`/`applied`/`rejected`/`errored`/`rejectedAtBirth`。
- `.../order/metrics/MallOrderStateMetrics.java` —— 状态子系统**唯一埋点出口**（7 指标 + 512 槽事件环 `recentEvents()`）。⚠️ 「事件环」是**自有观测通道**，与 notification/audit **不是一回事**，别混。

### 现状：管理端「订单治理」面 —— **占位，零业务；且⚠️ 稿里没有这一页** {#pack-c3-block-4-order-s10}

- `.../src/router/index.js:453` —— `business/orders`（「订单治理」）今天 `component: AdminStub` —— **本块的替换点**（照同行替换做法 ⇒ 零漂移）。
- `.../src/config/admin-nav.js:45` —— 侧边栏项 `orders`（`to: '/admin/business/orders'`）。**入口已在** ⇒ ⚠️ 导航表**本块不动**。
- `.../src/views/admin/StubPage.vue:43` —— 占位组件**零请求**的显式理由（「管理员今天还没有可用的业务端点…一发请求就会 401」）。
- ⚠️ **`docs/美化/pages/` 里没有管理端订单稿**（实测只有 `OrderCenter-merchant.html` / `ShoppingCart-customer.html`）⇒ 管理端这一页**无稿可对**，按块 2 `AccountsPage.vue` 的**真页样板**自建。

### 现状：前端占位清单（订单块相关 —— `docs/美化/` 的「占位四件套」= `disabled` + `data-slot` + 角标 + `title`） {#pack-c3-block-4-order-docs}

- `.../src/views/OrderCenter.vue`（844 行，**商家订单面**）：**真功能** = 发货按钮 `:140`（`handleDeliver` **原 248 行**，`canSend` **原 246 行**：`PayIf=1 && DeliveryIf=0 && ReceiveIf=0`）/ 稳态取数 **原 202 行**（按 `orderStatus` 选 `orderDeliveryIfSelect`/`orderDeliverySelect`）/ 状态名表 **原 234 行**。
  **占位** = 数据概览 KPI `:63`（4 处·超档）/ 标签页 `:76`（6 枚全 `disabled`，`:81`「已完成」N=0 仍 `disabled`）/ 筛选栏 `:87` / 批量操作 `:100` / 分页 `:149`。
- `.../src/views/ShoppingCart.vue`：见上「现状：购物车」。
- `.../src/views/HistoricalOrders.vue`（275 行，客户/商家共用「历史订单」子页）：只读表 `:32`；**真功能** = 复制商家 Id `:122`；占位 = 状态枚举列 `:41` / 申请退货列 `:48`（`<th>` 无数 `disabled`）。
- `.../src/views/UserInfoPage.vue`（652 行）：商家版「待处理订单」四格 `:73`（整块占位）；客户版「我的订单」四格 `:154`（`:158` 注「客户侧只有一个整单列表页 `/customer/cart`，无按状态筛选端点（N=4）」）。
- 登记出处：`docs/agent/slot-registry.md:216`（订单中心占位表，归属列 `C3〈订单〉`）/ `:176`（付款/收货/进度条「是**订单**的职责」、目标 `/customer/orders` **本仓不存在** ⇒ 归「完整购物车笔」）。
- ⚠️ **真功能锚点，勿误判为占位**：`OrderCenter.vue:140` 发货 · `ShoppingCart.vue:302`/`:324` 付款/收货 · `ProductPage.vue:638` 建单 · `HistoricalOrders.vue:122` 复制商家 Id。

### 样板（照抄这些真页与既有形状，别自创风格） {#pack-c3-block-4-order-s12}

- **管理端真页** `.../src/views/admin/AccountsPage.vue`（块 2 建、块 3 照它）—— 页内 tab（`:37` 渲染 / `:332` 的裁决口径）/ 审核卡片流 `:76` / **危险动作要求填理由且理由展示给当事人**（`:104` 驳回理由必填、`:349` 的口径注释）⇒ 本块管理端照它。
- **三态回执** `.../src/views/admin/NotifyPage.vue:359` 的 `doSend()`（`try → if(!data.success) 记 msg → catch 记网络错误 → finally`）⇒ 本块的「介入／发货／收货」按钮照它。
- **列表取数** `.../src/views/admin/AuditPage.vue:441` 的 `load()`（错误走 `err.value = ...HTTP ${status}`）。〔行号 2026-10-11 可视化段收口重指：原 301 行〕
- **弹窗骨架** `.../src/views/GoodsUp.vue` 的 `.scrim` / `.dlg`（块 3 已抽成 `.../src/components/TemplatePicker.vue`，本块若需弹窗直接复用该组件形状）。
- 商家端两态开关 = `.../src/views/merchant/OrderPane.vue:2`（`OrderCenter` ⇄ `ShoppingCart`）；`:33` `toCart` 的路由跳转 ⇒ **B 档 2 的改造点**。

### 表 / 前端入口 / 登记 {#pack-c3-block-4-order-s13}

- `.../src/api/endpoints.js:53-62` —— `order` 组 8 条。⚠️ `endpoints.js` 有被 `progress.md` 按行号引的行（**当前最大被引 = 84**）⇒ **新增一律追加在文件尾**。
- ⚠️ **订单端点今天没有任何 `api/*.js` 封装层** —— 8 条全部是**view 内联** `http.post(endpoints.order.*)` 直调（`ShoppingCart.vue:266` / `OrderCenter.vue:202` / `HistoricalOrders.vue:148` / `ProductPage.vue:638`）；调用方矩阵见 `docs/agent/api-contract.md#api-5`（⚠️ 其表内 `ProductPage.vue:369` **已 stale**，实际 `:638`）。
- `.../src/router/index.js` —— 客户壳 `:61`（`cart` → `/customer/cart`，`ShoppingCart` + `props:{showSwitch:false}`）· `:78`（客户 `history`）· `:118`（商家 `history`）；商家壳 `:95`（`order` → `OrderPane`，`orderTab:'order'`）· `:96`（`order/cart`，`orderTab:'cart'`）；`:126` `/shoppingCart` 按角色重定向；`:453` 管理端订单占位。⚠️ **最大被引 = 453** ⇒ 新增路由**同行替换或追加**。
- `.../src/config/nav.js:31`（客户顶栏「购物车」→ `/customer/cart`）/ `:45`（商家顶栏「订单中心」→ `/merchant/order`）—— ⚠️ **导航表本块不动**（入口已在）。
- `docs/agent/db-schema.md#db-6-8` §6.8 `order`（逐列 + `uk_order_orderId`）/ `docs/agent/db-schema.md#db-6-12` §6.12 `order_state_transition`。
- `docs/agent/tech-debt.md:172` **TD-14**（`order.OrderId` 无索引，三个 CAS 端点全表扫）/ `:217` **TD-17**（`orderAdd` 不校验三位、客户端可伪造终态 —— C#6 2026-10-04 已裁拦截）。
- `docs/agent/slot-registry.md:3770` —— §十八起（块 2 登记结构）⇒ **本块收口照 10 小节结构开 §二十**。

## 要新建 {#pack-c3-block-4-order-s14}

> ### ⚠️ A 档待裁（3 项 —— 落在「建表 / 改契约」硬线上，**用户批了才落**）
>
> 判据 = 三条硬线（**建表 / 改契约 / 删文件**）。5 个候选里**只有下面 3 项真落硬线**；其余 2 项按 B/C 自裁（见下），
> 完整论证与备选代价见 `_work/c3-block4-landing-plan.md §二`。
>
> **A1〈购物车载体〉** —— 新建 **`cart` 表**（推荐）**vs** 扩 `order` 表加「未下单」状态位。
> · 甲（**推荐**）新表 `cart`：`Id` / `CustomerId` / `MerchantId` / `ProductCode` / `Quantity` / `PriceSnapshot` / `created_at` / `updated_at`；`UNIQUE(CustomerId, ProductCode)`（同商品合并数量）。
> · 乙 扩 `order`：把未下单行塞进 `order` —— ⚠️ **代价三连**：(a) `order` 的三位是**单调不可逆**的（`.../order/model/OrderStateMachine.java:98` 的守卫表 + `.../order/model/OrderState.java:137` 的出生闸白名单恰为 `(0,0,0)/(1,0,0)`）「未下单」会**污染出生闸**；(b) **A3 的 P4 种子门禁**（`order=6`）与 P3 表数会变红；(c) `order` **无 `Quantity`**（`mall_management_sql.sql:141`）改量仍要加列。
> ⇒ **推荐甲**（新表），与块 3「新表 `template`」同先例。
>
> **A2〈订单行的「数量」承载〉** —— `order` **加 `Quantity` 列**（推荐）**vs** 不加（数量塞进 `ProductInformation` 文本）。
> · 甲（**推荐**）`order` 加 `Quantity int NOT NULL DEFAULT 1`（可选 `UnitPrice`）。**硬线 = 改契约**：连带 `OrderDao.insertOrder` 列清单 + `OrderRow` record + `OrderController.orderAdd` 入参（`OrderController.java:70`）+ `ResultByteEquivalenceTest` / `Order*SmokeTest` / A3 P3·P4。
> · 乙 不加列：零契约变更，但订单中心「数量」列**恒为占位 `—`**（今天就是），且订单结构里数量**不可查询**。
> ⇒ **推荐甲**（真购物车「改量 → 结算」不落数量，等于没做完）。
>
> **A3〈管理端「介入」的动作边界〉** —— **只沿既有三条 CAS 边强制推进**（推荐）**vs** 允许任改三位 **vs** 新建退款/纠纷表。
> · 甲（**推荐**）：介入 = `强制付款`（`OrderAction.PAY` 边）/ `强制发货`（`DELIVER` 边）/ `强制收货·完成`（`RECEIVE` 边），复用 `OrderStateMachine.updateSql` + `OrderDao.casUpdate`，**不新增状态位、不动守卫表**（严守用户「不碰既有 `OrderStateMachine`」）；**「强制退款」只登记不实现**（`order` 无资金态、无退款列，`PayIf` 回退会破三位单调性）。
> · 乙 任改三位：绕过 CAS 守卫 ⇒ 破坏 `order_state_transition` 的因果链 ⇒ **不推荐**。
> · 丙 建退款/纠纷表：**建表**，**超本块**。
> ⇒ **推荐甲**。
>
> 附带同一裁决里的**小项（也归 A1 的连带，一并裁）**：3 个 CAS 端点（`OrderController.java:158`/`:165`/`:172`）**是否补归属校验**（今天只收 `orderId`、谁都能改别人的单 —— `progress.md §5-AF` 登记的缺口）；本块**建议补**（`requireSelfOrAdminByUsername` 或按 `OrderId` 回查归属），代价 = 改既有契约。

### B 档（自裁，已报备 —— 不落硬线，可落码） {#pack-c3-block-4-order-b}

1. **订单中心路由拆分**（**用户已在块 4 定位里裁过**）：新增客户侧 **`/customer/orders`**（订单中心：付款 / 物流 / 收货）；`/customer/cart` 改**真购物车**。`ShoppingCart.vue` 的**订单列表部分**（`loadOrders` `:256` + 付款/收货 **原 302/324 行**）**迁到新页**。
   ⚠️ 既有 `/customer/cart` 的语义**变**（今天 = `ReceiveIf != 1` 的订单列表）—— 属「改契约」的**前端面**，但用户已裁 ⇒ 按 B 落、报备。
2. **商家端**：改造 `OrderPane.vue` 既有**二态开关**（`:2`/`:3`/`:33`），文案改 **「我卖的 / 我买的」**；`OrderCenter.vue` = 我卖的（发货）、`ShoppingCart.vue` = 我买的（付款/收货）。**不新开门面**（复用既有 `orderTab` meta）。商家 = 用户超集 ⇒ 「我买的」直接继承客户侧。
3. **管理端新页** `.../src/views/admin/OrderPage.vue`（**新建**），**同行替换** `router/index.js:453`；新端点 `/admin/order/{list,detail,intervene,monitor}`（**只读 + 介入**）。⚠️ 无稿 ⇒ 照 `AccountsPage.vue` 自建。
4. **通知接线**（第三次实战）：`NotificationService.send` + **已预留**的两条 code（`NotificationEvents.java:90`/`:93`）；`AuditActions` 加 `ORDER_*` 常量（追加文件尾，照 `.../admin/AuditActions.java:53` 范式）；`.../src/config/notify-events.js:61-63` **两条中文标签已在**（`order.intervened` / `order.notice`）⇒ 无需补。
5. **前端 API 分层**：**新建** `.../src/api/order.js` 承接 8 个既有端点（照块 2/块 3 的 `api/*.js` 惯例）；`endpoints.js` **文件尾追加**新端点。

### C 档（自决 —— 落码时按此，不单独问） {#pack-c3-block-4-order-c}

- **「真付款」口径** = 走**既有** `OrderController.java:158` `orderPayIfUp`（`PayIf` 0→1 的 CAS，真改库）；**不接第三方支付**（仓里无支付网关、无资金面）。结算 = `orderAdd`（`PayIf=0`）→ 立即 `orderPayIfUp`。
- **购物车「改量」clamp** 沿用 `ProductPage.vue` 既有口径（min 0 / max 100）。
- **订单中心筛/分页**：本块只接**既有 8 端点能支撑的**（状态/时间前端过滤，或后端新增只读端点）。

### 表（**待 A 档裁后定稿** ⇒ 追加到 `mall_management_sql.sql` **末尾**，`IF NOT EXISTS`，`information_schema` 守卫，规则 7） {#pack-c3-block-4-order-mall_management_sql}

- **新表 `cart`**（A1 裁甲时）：见上列列清单。
- **`order` 加 `Quantity`**（A2 裁甲时）：`ADD COLUMN ... int NOT NULL DEFAULT 1 COMMENT ...`（加列在**表末**、带默认值、带 COMMENT）。
- ⚠️ **加列不幂等**（MySQL 无 `ADD COLUMN IF NOT EXISTS`）⇒ 用 `information_schema` 守卫动态 `PREPARE`，两库都能跑、重跑是空操作（**块 3 的手法**）。
- ⚠️ **表数变化** ⇒ 同笔更新 `scripts/concurrency/ConcurrencyRunner.java` 的 P3 `KNOWN_TABLES`（**当前 6，总数 17**）；**P4 种子门禁**（`order=6`）若被 `cart` 表影响则同笔核。

### 后端（**建议**分层 —— B 档，报备） {#pack-c3-block-4-order-s18}

- **新域 `cart/`**（**新建**，与 `order/` 平级）：`dao/CartDao` · `service/CartService` · `controller/CartController`（`@RequestMapping("/cart")`）—— 加购 / 改量 / 删行 / 列出 / **结算**。
- **订单域追加**：`OrderService` 新只读方法（管理端全量视图 / 异常监控）+ **介入写方法**（**沿既有 CAS 边**，见 A3）；⚠️ **不得加 `@Transactional`**（禁令 + `DistributedLockServiceTest.java:419` 结构断言），新的订单写路径同样走 `runLocked`。
- **`admin/` 域追加**：`AdminOrderController`（`@RequestMapping("/admin/order")`，**不加角色闸**，TD-18）+ 介入 / 监控 Service。
- **端点**：新增 = `/cart/{add,update,remove,list,checkout}` · `/admin/order/{list,detail,intervene,monitor}`；**改行为的既有端点** = `/order/orderAdd`（若 A2 裁甲，入参 +1）、3 个 CAS 端点（若 A3 连带裁「补归属校验」）。
- `AuditActions.java` 文件尾**加常量**（照 `:53` 范式）：`order.intervene` / `order.deliver` / `order.receive`（**`TargetId` 一律传 `OrderId`** —— 别重蹈 TD-20）。
- ⚠️ **测试面必改**：① 新 Controller 依赖的真 bean 同步进 `AbstractSmokeTest.java:156` 的 `@Import`；② 新 Controller **不得**带类级 `@Validated`；③ 若 `order` 加列，`Order*SmokeTest` 与 `ResultByteEquivalenceTest` 的字符串/形状断言要同笔核。

### 前端（三端） {#pack-c3-block-4-order-s19}

- **用户端**：新页 `.../src/views/OrderCenter-customer.vue`（**新建**，订单中心 —— 付款/物流/收货），**同行替换/追加** `router/index.js` 新增 `/customer/orders`；`ShoppingCart.vue` **改真购物车**（加购/改量/结算 —— 占位区块 `:77`/`:197` 转真，付款/收货迁走）。
- **商家端**：`OrderPane.vue` 二态开关改文案（B2）；两页占位按能力接线。
- **管理端**：**新建** `OrderPage.vue`（B3）。
- `.../src/api/order.js` **新建**；`.../src/api/endpoints.js` **文件尾追加**（`cart` 组 + `orderAdmin` 组）；`.../src/config/notify-events.js` 两条标签已在、无需动。

## 约束 {#pack-c3-block-4-order-s20}

1. **fail-open / fail-soft**：审计与通知**永不抛** —— 失败**不许**把业务成功翻成 500（同块 1/2/3）。
2. **逻辑废弃，不物理删**（规则 v2）：购物车删行 = 物理删**有争议** ⇒ 落码前按块 4 方案定（购物车行是**临时数据**，与「订单留痕」不同 —— 见 `_work/c3-block4-landing-plan.md §六`）。**订单/审计/时间线一律禁止 `DROP`/`DELETE`**。
3. **admin 端点不加角色闸**（TD-18）。
4. ⚠️ **不碰既有 `OrderStateMachine`**（**用户明令**）—— 介入只走既有三条 CAS 边；**不得**新增状态位或改守卫表。⚠️ **范围澄清（用户 2026-10-11）**：本约束管的是**「介入动作」**（状态位 / 守卫表 / CAS 边），**不管「付款业务规则」** ⇒ 2026-10-10「超时未付款自动取消」在 `updateSql(PAY)` 尾部追加 `AND CancelledAt IS NULL`（**改的是 WHERE，不是 SET；未新增任何状态位**）属付款业务规则，**不违本约束**（是「旧令本就不适用本次」，不是破例）。判据：取消**不动** `PayIf`，光靠 `PayIf = 0` 的守卫拦不住「已取消的单被付款」，而「先查再 CAS」会留 TOCTOU 窗口（`runLocked` 取锁失败是 fail-open）⇒ 放进同一条 UPDATE 才是单语句原子。其余（三条 CAS 边 / 守卫表 / 状态位）仍受本约束。后果登记 `tech-debt.md` **TD-34**。
5. ⚠️ **订单链禁 `@Transactional`**（全项目唯一禁令）—— 结构断言 `DistributedLockServiceTest.java:419` 兜底，新增订单写路径**同样禁止**。
6. **既有端点原则不动**：本块**动既有契约的处数**在 A 档裁后**逐条列全**（预期 ≤ 3：`order` 加列 / `orderAdd` 入参 / `/customer/cart` 前端语义）；**其余一律「新增」**。
7. **净零行数**：`router/index.js`（最大被引 **453**）/ `endpoints.js`（**84**）/ `nav.js`（**61**）/ `admin-nav.js`（**43**）⇒ 新增**追加文件尾**或**同行替换**；动到被引行前后必须重跑 `refs-scan.sh`。
8. **无缓存**：订单域今天零缓存（`GoodsCacheService` 只管商品）；购物车若加缓存必须登记失效面。
9. **时区**：新表时刻列一律 `CURRENT_TIMESTAMP(3)`（DB 存 UTC），**前端渲染再转**浏览器时区。
10. ⚠️ **手验提醒（每次）**：日常手验挂 **`dev`** profile（2026-10-11 起 `a3` 收窄为 CI 专用，见 `tech-debt.md` TD-35）；`dev` 的鉴权闸与 a3 孪生、**也是关的**（`application-a3.properties:98`）—— **闸关，手验绿 ≠ 默认 profile 绿**。本块端点**全是身份敏感**（订单归属）⇒ **必须在默认 profile 再验一遍**。

## 验收 {#pack-c3-block-4-order-s21}

**核心 5 项必跑**（提速规则 2026-10-09 起；其余抽查）：

1. **建表/加列**（按 A 裁决）：**新表 `cart`** +（若 A2 裁甲）**`order` 加 `Quantity`** 应用到 **VM 库**（**不整份重导** —— 会 DROP 现有表）；`information_schema` 见新表 / 新列；`SELECT COUNT(*) FROM cart` 起点为 0。
   ⚠️ **CI 连带**：表数变化 ⇒ `scripts/concurrency/ConcurrencyRunner.java` 的 P3 门禁**会变红**，必须同笔更新 `KNOWN_TABLES`；若动了 `order` 列，**P4 种子计数**（`order=6`）要复核。
2. **编译**：`cd Mall-management-server && ./mvnw -q -DskipTests compile` 退出 0。
3. **主链路**（真后端 + VM 库，**不 mock**）：
   · **购物车**：加购 → 改量 → 删行 → 结算 ⇒ 生成 `order` 行（`PayIf=0`、`Quantity` 落值）→ 付款 ⇒ `PayIf=1`；
   · **通知三连（第三次实战的验收点）**：商家发货 ⇒ **买家收到 `order.notice`** → 用户收货 ⇒ **商家收到 `order.notice`** → 管理员介入 ⇒ **双方收到 `order.intervened`** + `admin_audit` 有行（`TargetId = OrderId`，**非 NULL**）；
   · **管理端**：全量视图按用户/商家/状态/时间筛 → 介入（**A3 边界内**）→ 状态位与 `order_state_transition` 因果链**同时**成立。
4. **不回归**：`./mvnw -B test` **全绿**（新 Controller 必须同步 `AbstractSmokeTest.java:156` 的 `@Import`；`OrderPayIfUpSmokeTest` / `OrderAddPriceGuardSmokeTest` / `OrderAddStateGuardSmokeTest` **必复跑**；**`DistributedLockServiceTest.java:419` 结构断言必须仍绿**）+ 既有 A3 全绿。
5. **通知三态**：正例有行 / 负例无行 / **`notification` 表新增行可指认**（块 3 的教训：fail-open 会把「丢了」伪装成「成功」⇒ **必须回库读，而不是看接口返回**）。

**抽查**：`bash scripts/check-context-packs.sh`（本 pack 0 失效引用）；`refs-scan.sh`（动被引行时）；审计三态（正例有行 / 负例无行 / 有改前态的动作 `BeforeJson` 非空）；权限三档（非本人传**别人的** `CustomerId`/`MerchantId` → 403 / 本人 → 放行 / 管理员 → 放行）。

## 扩展阅读（不够时按指针读） {#pack-c3-block-4-order-s22}

- `docs/agent/context-packs/c3-block-3-goods.md` —— **本 pack 的模板**（结构与行文照它，含「块 1 基建」段的照抄手法）。
- `docs/agent/context-packs/c3-block-2-user.md#pack-c3-block-2-user-block1-reuse` —— 块 1 基建的**第一份照抄**；块 2 的审核 CAS 是块 4 介入动作的直接来源。
- `docs/agent/context-packs/c3-block-1-infra.md` —— 挂钩四步与两条管子的完整口径；动笔前读它的「约束」一节。
- `_work/c3-block4-landing-plan.md` —— **本块的落地方案**（A 档三项的备选与代价 / B·C 档自裁 / 表·后端·前端 / 验收 / 登记 / 零漂移手法）。
- `docs/agent/c3-modules.md:67-73` · `:119-124` · `:263` —— 范围 / 订单生命线 / 购物车归属。
- `docs/agent/db-schema.md`（`order` `db-schema.md#db-6-8` · `order_state_transition` `db-schema.md#db-6-12`）· `docs/agent/api-contract.md#api-order`（8 端点）· `docs/agent/tech-debt.md:172`（TD-14）· `:217`（TD-17）。
- `scripts/probes/` —— 端到端验收脚本样板（本块写 `probe-c3-block4-e2e.mjs`，真后端 + VM 库、逐条断言、机器可读 JSON、失败非零退出）。
- `docs/agent/slot-registry.md §二十`（**待开**，结构照 §十八/§十九）。

## 补丁〈模拟支付 / 物流全流程〉（2026-10-10 落码；本 pack 之上的一笔） {#pack-c3-block-4-order-pack}

> **为什么有这一节**：块 4 把「付款 / 发货」做成了**纯状态位**（只翻 `PayIf` / `DeliveryIf`）⇒ 用户要求升格为**模拟全流程**：
> 记交易号 / 支付方式 / 付款时刻 / 快递公司 / 运单号 / 发货时刻 / 收货时刻，并给买家一条**物流轨迹时间线**。
> 四点确认（用户 2026-10-10）：**列数 7**（3 支付 + 4 物流；`ReceivedAt` 归在物流组 —— 「8 列」是那份列表的计数笔误）·
> **物流签收 ≠ 订单收货**（调度器不碰状态位 ⇒ 必然结果，**接受**，前端分两词 + 登记 TD-28）· 新增 **A5 `/order/orderDetail`** ·
> 其余全采信（**不动 `OrderStateMachine`** / 三端点加参用**重载**（`intervene` 零改）/ 调度器**只 INSERT 轨迹** / A3 门禁 **7→8 · 18→19**）。

**坐标（补丁后实测；本节不引用旧行号，避免污染上面的快照表）**

| 项 | 值 |
|---|---|
| 新表 | `order_shipping_track`（`mall_management_sql.sql:805-815`；表数 **18 → 19**） |
| `order` 新列 | 7 列，守卫式 `ALTER` 在 `mall_management_sql.sql:769-796` |
| A3 门禁 | `scripts/concurrency/ConcurrencyRunner.java` P3 段 **仍 `:2644-2667`**（**净零行**手法）；`KNOWN_TABLES` **8**、期望 **19** |
| 新端点 | `POST /order/orderDetail`（A5）= `OrderController.java:245`（补丁后实测） |
| 三条 CAS 端点 | `:184`（`orderPayIfUp`）/ `:199`（`orderReceiveIfUp`）/ `:217`（`orderDeliveryIfUp`）—— 契约详解见 `api-contract.md 附录 D` |
| 新文件 | `.../order/job/ShippingTrackAdvanceJob.java` · `.../order/dao/ShippingTrackDao.java` |
| 调度器红线 | **只 INSERT `order_shipping_track`，绝不 UPDATE `order` 的状态位** ⇒ 轨迹「已签收」≠ 订单「已收货」 |
| ⚠️ 隐藏耦合 | `@EnableScheduling` **不在主类**，在 `MerchantDowngradeSchedulingConfig`（自身 `@ConditionalOnProperty(mall.downgrade.job.enabled, matchIfMissing=true)`）⇒ **a3 里调度器整个不存在**；发货任务自身的开关（`mall.order.` 前缀下的 `shipping.track.enabled`）是**第二道独立闸** ⇒ **不得**用「关降级任务」的方式关发货任务 |

**验收补充（在「验收」节之外，补丁专属）**

1. **模拟收银台**：`OrderMine` → 【付款】弹窗选 `支付宝 / 微信支付 / 银行卡` → 回库 `order.TradeNo` 非空（形如 `PAYyyyyMMdd######`）、`PaymentMethod` = 所选项、`PaidAt` 非空；响应体带 `tradeNo`。
2. **发货带单**：`OrderCenter` → 【发货】填快递公司 + 运单号 → 回库 `ExpressCompany` / `TrackingNo` / `ShippedAt` 三项落值；**三道守卫仍成立**（已发货 / 已收货 / 未付款 ⇒ 拒绝）。
3. **物流轨迹**：等 `transit-ms=15000` / `picked-up-ms=30000` / `delivery-ms=45000` / `signed-ms=60000`（⚠️ 2026-10-11 起**四节点全由调度器补**，时序「运输中 → 驿站已揽收 → 驿站派送中 → 已签收」；或手改 `ShippedAt` 回拨）⇒ `order_shipping_track` 依次出现 `in_transit` / `picked_up` / `out_for_delivery` / `delivered`；**重跑不重复插**（`NOT EXISTS` 幂等）；**`order.ReceiveIf` 仍为 0**（这就是 TD-28，**是预期不是 bug**）。
4. **买家侧详情**：`OrderMine` 的【物流】按钮（`DeliveryIf===1` 才出现）→ `POST /order/orderDetail` → 弹窗出现轨迹时间线；**归属校验**（非本人 `orderId` ⇒ 403）—— ⚠️ **a3 闸关下测不出**。
5. **管理端**：`admin/OrderPage` 详情区出现**第二组 `.a-kv`**（支付方式 / 交易号 / 付款时间 / 快递公司 / 运单号 / 发货时间 / 收货时间；空值 `—`）+ **物流轨迹 `<ol class="a-tl">`**；与「状态流转」时间线**故意分列不合**。
6. **不回归**：`./mvnw test` **258 / 0 fail / 26 skipped**（`OrderStateMachineTest` 21 → **23**）；**golden copy** `OrderStateMachineTest.updateSqlIsByteForByteTheLegacyLiteral` **必须仍绿且字节未变**；`npm run build` DONE。
7. **开关**：`application-a3.properties`（尾 11 行）/ `application-smoke.properties`（尾 8 行）均把 `mall.order.` 前缀下的 `shipping.track.enabled` 置 `false`。

**零漂移口径（按用户 2026-10-10 新规降级）**：本补丁**不再**全仓逐条重指 ⇒ 只核**自己动过的引用**（本 pack 已就地核：**OK 259 · INFO 1 · WARN 0 · FAIL 0**）；
本补丁造成的**外部**引用偏移全部记账在 **`_work/drift-ledger-block4patch.md`**，留到**块 4 收口**或**块 7 之后的锚点改造**一次性处理。

**登记**：`slot-registry.md §20-9` · `api-contract.md 附录 D` · `db-migration.md #10` · `db-schema.md §12` · `tech-debt.md TD-28…TD-31` · `progress.md §7-B4P` · `c3-blocks-index.md:16`。
**未做**：补丁的**手工验收未跑**（块 4 那一轮手验在补丁之前）。
