# 并发控制地图 — Mall-management

> **证据来源**：3 个 Controller 全文（缩写 `U`/`M`/`G`，数字为源码行号）、`mall_management_sql.sql`（缩写 `SQL`）、`application.properties`（缩写 `APP`）。
> 全部表为 InnoDB，行锁可用；但代码层几乎未使用任何并发控制。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-02 实测**；重构后须重测。用户域旧 `U:<n>` 为三层前 264 行版 `UserController` 的历史坐标（2026-10-02 第三笔 `0c07e96` 后已重指）。
>
> ⚠️ **2026-10-02 复测订正（本页多处）**：本文写于 **2026-09-14 截面**，此后 **B1/B2/B3 三笔**改动了架构与坐标。本次就地重测的是**坐标已冻结的部分**（商品/订单/shared 三层已完成的域），逐行标注「原记 → 2026-10-02 实测」。两点范围声明：
> 1. **证据基础已变**：写时是 3 个 Controller，现为 **4 个业务 Controller**（`goods`/`order`/`message`/`user`）+ `shared/health/HealthController`；且**订单、商品两域的方法体已离开 Controller**（三层重构），旧 `G:` 坐标多数**已越界**（`GoodsController` 现仅 176 行）。
> 2. ~~**`U:` / `M:` 坐标本次不改**：用户域与消息域的三层重构**尚未做**，现在重指会在下一笔再漂一轮。文中凡命中这两域的锚点一律标「**待 user/message 笔重指**」，**不写新行号**。~~ ✅ **2026-10-03 结清**：两域的三层笔**都已落地**（user 域 `0c07e96`、message 域 `8ed5fc1`），本节 §4 里原先挂着「待 message 笔重指」的两行**已就地重指**（`UserController` 那一行在 2026-10-02 user 笔时即已重指）。**本条作废**。⚠️ 全文其余命中这两域的锚点（§1 #12 的 `MessageController.friendAdd` 等）**一并改完**；仍要「直接去源码重测」的只剩**未做三层的公共层**（`shared`）相关坐标。

## 1. 摘要

| 结论 | 严重度 |
|---|---|
| 全项目**仅 1 处** `@Transactional`（`loginAdd`，现址 `UserService.loginAdd:124`，方法声明 `:125`；2026-10-02 全仓复测仍**仅此 1 处**） | — |
| 订单三状态位用条件 UPDATE 实现 **CAS**，是全项目唯一正确的并发控制 | 正面案例 |
| `productsales.OrderVolume` **从未被更新**，订单量统计功能失效（**非竞态问题**） | 高 |
| 4 处多表写**无事务保护**（`goodsAdd`/`goodsUp`/`goodsDe`/`friendUp`） | 高 |
| 3 处「先查后插」存在 **TOCTOU 竞态**，且无数据库唯一约束兜底 | 高 |
| ~~无任何锁：无 `synchronized`/`Lock`/`FOR UPDATE`/`@Version`/Redis/Redisson~~ → ⚠️ **2026-10-02 已不成立**：**B2**（2026-09-30）引入 **Redisson 3.46.0** 分布式锁，`DistributedLockService.runLocked` 现有 **5 个业务调用点**（`OrderService` 4 + `MessageService` 1）。仍**确实**没有的是 `synchronized` / `FOR UPDATE` / `@Version`（全仓 0 命中）—— 「无锁」已从**事实**降级为**局部事实** | 中 |
| ~~无任何异步与线程池：无 `@Async`/`@Scheduled`/`CompletableFuture`/`ThreadPoolTaskExecutor`~~ → ⚠️ **2026-10-02 部分不成立**：**B3** 引入显式 `ThreadPoolExecutor`（`OrderStateExecutor:79`，`ArrayBlockingQueue(1024)`，`app.order.state.async.queue-capacity`）。四个字面量仍 0 命中，但「无线程池」不再为真 | 低（主流程仍同步；异步仅限订单状态事件） |

## 2. 唯一的 @Transactional

| 位置 | 方法 | 覆盖的写入 | 评价 |
|---|---|---|---|
| `UserService.loginAdd:125` | `loginAdd` | `user` 插入 + `customerimage`/`merchantimage` 插入 + `login` 插入（客户分支 `UserDao:170,186,205`；商家分支 `UserDao:178,193,211`） | ✅ **用法正确**。三表写入具备原子性，任一失败整体回滚。 |

> ✅ **`U:` 坐标已于 2026-10-02 三层第三笔（user 域，`0c07e96`）重指完毕** —— 原「待 user 笔重指」作废。`@Transactional` 现址 `UserService.loginAdd:124`（方法声明 `:125`），6 条分支 INSERT 现址见上表。原 `U:47` / `U:70,75,81` / `U:87,91,96` 均为三层前 264 行版 `UserController` 的历史坐标。

四个业务 Controller 全文再无第二处 `@Transactional`（全仓复测：主源真实注解**仅 `UserService.loginAdd:124` 一处**，其余均为 javadoc 里的字面提及）。

## 3. 正面案例：order 三状态位 CAS

三个订单状态接口全部使用**条件 UPDATE**，以 `rows == 0` 作为冲突信号。InnoDB 下单条 UPDATE 持行锁且原子，重复请求会被 WHERE 条件拦截——这是全项目唯一正确的并发控制。

| 接口 | WHERE 条件 | 防护效果 | 证据 |
|---|---|---|---|
| `orderPayIfUp` | `OrderId = ? AND PayIf = 0` | 重复支付 → 拒绝 | 路由 `OrderController:100` → `OrderService.payIfUp:179`（原记 `G:524`，`rows` 判断 `G:526-533`） |
| `orderReceiveIfUp` | `OrderId = ? AND PayIf = 1 AND ReceiveIf = 0` | 未付款收货 / 重复收货 → 拒绝 | 路由 `OrderController:107` → `OrderService.receiveIfUp:185`（原记 `G:546`/`G:548-555`） |
| `orderDeliveryIfUp` | `OrderId = ? AND PayIf = 1 AND DeliveryIf = 0 AND ReceiveIf = 0` | 未付款发货 / 重复发货 / 已收货后发货 → 拒绝 | 路由 `OrderController:114` → `OrderService.deliveryIfUp:191`（原记 `G:568`/`G:570-577`） |

> ⚠️ **三处 `WHERE` 现由 `OrderStateMachine` 统一生成**（B3 起）：守卫谓词在 `OrderStateMachine:90-100` 的 `TRANSITIONS`，完整 UPDATE 在 `updateSql():123-127`。**这两处才是现行唯一真值源**，由 `OrderStateMachineTest` 的 golden copy 钉死。本表三行的 `WHERE` 文本与之逐字符一致。

冲突时返回明确失败消息（如「订单不存在或已付款」），前端可据此提示。**该模式应作为其余写接口的改造范本。**

⚠️ 遗留缺口：三处均把 `SettlementDate` 覆写为 `NOW()`（原记 `G:524`/`G:546`/`G:568`；**现址 `OrderStateMachine.updateSql:126`** —— B3 起三个端点收敛到同一条拼接），先付款后发货时结算时间会被发货时间覆盖，字段语义存疑（见 `db-schema.md` §4）。

## 4. 纠正记录：OrderVolume 不是竞态，是功能失效

**原错误推断**：「`OrderVolume` 是计数器，下单时读-改-写，无版本列 → 并发丢失更新」。

**正确结论**：该字段**全项目从未被更新**，永远停留在插入时的 `0`。既不存在读-改-写，也就不存在丢失更新；真正的问题是**订单量统计功能完全失效**。

| 行号 | 事实 |
|---|---|
| `GoodsService:93` | 局部变量 `OrderVolume` 声明后**从未被引用**（原记 `G:79`，搬迁后**巧合同号**） |
| `GoodsService:131` → `GoodsDao.insertProductsales:113-118` | 上架时 INSERT `productsales`；硬编码传参 `0` 在 `GoodsDao:117`（原记 `G:129-138`/`G:137`） |
| `GoodsService:291` → `GoodsDao.updateProductsalesGoodName:168-172` | 更新商品时仅 `UPDATE productsales SET GoodName=?`，**不含 `OrderVolume`**（原记 `G:312-314`） |
| `GoodsService.goodsDe:211-238`（`deleteGoods:220` / `deleteProductsales:223`） | 下架时仅 `DELETE`，无计数调整（原记 `G:230-232`） |
| `OrderService.addLocked:147-166` → `OrderDao.insertOrder:82-91` | `orderAdd` 只 INSERT `order`，**完全不触碰 `productsales`**（原记 `G:397-411`） |

交叉证据：`SQL:176` 中 5 条 `productsales` 记录 `OrderVolume` 全为 `0`，而 `SQL:159` 的 `order` 表已有 6 条历史订单。

## 5. 缺失的并发防护清单

| # | 缺失项 | 具体问题 | 证据 |
|---|---|---|---|
| 1 | 多表写无事务 | `goodsAdd` 插 `goods` + `productsales`，第二次失败会留下无销量记录的孤儿商品 | `GoodsService:124`、`:131`（原记 `G:113`/`G:133`） |
| 2 | 多表写无事务 | `goodsUp` 更新 `goods` + `productsales` 两表非原子 | `GoodsService:288`、`:291`（原记 `G:304`/`G:314`） |
| 3 | 多表写无事务 | `goodsDe` 删 `goods` + `productsales` 两表非原子 | `GoodsService:220`、`:223`（原记 `G:227`/`G:231`） |
| 4 | 多表写无事务 | `friendUp` 改单向 `if_friend=1` 后插入反向行，第二步失败会产生**单向好友**（半好友关系） | ~~`M:266`、`M:268`~~ ✅ **已重指（2026-10-03 message 笔 `8ed5fc1`）**：`MessageService.friendUp:412`（UPDATE，`MessageDao.acceptFriendRequest:254-255`）→ `:414`（INSERT，`MessageDao.insertMutualFriend:275-276`）。**竞态未修**（两条语句之间仍无事务，catch 吞异常 ⇒ 单向好友仍可产生） |
| 5 | TOCTOU 竞态 | `loginAdd` 先 SELECT 查重再 INSERT，并发下两个请求可同时通过查重 | ~~`U:61` → `U:81`~~ ✅ **已重指（2026-10-02 user 笔 `0c07e96`）**：查重 `UserDao.existsUserName:119` → 第一条写语句 `UserDao.insertUserCustomer:170`（**竞态未修**，仍由 A3 `A-B2-1` 以 `EXPECTED_DEFECT_PRESENT` 钉住） |
| 6 | TOCTOU 竞态 | `friendAdd` 先查再插，并发重复申请会写入重复行 | ~~`M:205` → `M:219`~~ ✅ **已重指（2026-10-03 message 笔 `8ed5fc1`）**：查重 `MessageService.friendAddLocked:349`（`MessageDao.selectFriendPair:233-234`）→ 插入 `:362`（`MessageDao.insertFriendRequest:243-244`）；⚠️ **B2 已给该临界区加 `runLocked` 分布式锁**（`MessageService.friendAdd:323`，见 §5 #12 注） |
| 7 | TOCTOU 竞态 | `goodstype` POST 先查重再插，并发下可产生重复类型 | `GoodsTypeCacheService:62` → `:71`（原记 `G:42`/`G:51`） |
| 8 | 无唯一约束兜底 | `friend` 仅 PK `id`，`(customerid, friendid)` 无唯一键 —— 上述竞态**无数据库层兜底** | `SQL:43` |
| 9 | ~~无唯一约束兜底~~ ✅ **已消除（2026-09-30 S1a，`1e2fcfa`）** | ~~`order.OrderId` 无唯一索引（主键是自增 `Id`），业务单号可重复~~ → 已加 UNIQUE **`uk_order_orderId`**，DB 层兜底到位。应用层查重仍由 B2 的临界区「先查后插」负责，两条互补（`A-S2a-1b` 断言前者、`1c` 断言后者） | `SQL:154`；追加块 `SQL:245` |
| 10 | 无唯一约束兜底 | `goodstype.GoodType` 无唯一索引，查重仅靠应用层 | `SQL:78` |
| 11 | 无锁机制（**已部分消除**） | `synchronized` / `SELECT ... FOR UPDATE` / `@Version` 乐观锁版本列**仍全仓 0 命中**；但 ⚠️ **B2（2026-09-30）已引入 Redisson 应用层锁** ⇒ 「无锁」只剩后半句 | 主源全仓 grep（2026-10-02 复测） |
| 12 | ~~无分布式锁~~ ✅ **已消除（B2，2026-09-30）** | ~~未引入 Redis / Redisson~~ → `DistributedLockService.runLocked`（`:237`）现有 **5 个业务调用点**（`OrderService:110/180/186/192` + ~~`MessageController.friendAdd:227`~~ `MessageService.friendAdd:323`，2026-10-03 message 笔 `8ed5fc1` 重指）。⚠️ 覆盖**不是**全面的：§4 #4/#5 两处多表写**仍未打锁** | `pom.xml:82-85`（`spring-boot-starter-data-redis`）、`pom.xml:117-121`（`redisson 3.46.0`） |
| 13 | 无异步 / 线程池（**已部分消除**） | `@Async`/`@Scheduled`/`CompletableFuture`/`ThreadPoolTaskExecutor` 四个字面量**仍 0 命中**，但 ⚠️ **B3 引入显式 `ThreadPoolExecutor`**（`OrderStateExecutor:79`，有界 `ArrayBlockingQueue(1024)`） | `OrderStateExecutor.java:79/100`、`application.properties`（`app.order.state.async.queue-capacity`） |

## 6. 连接池配置

`APP` 中**未配置任何** `spring.datasource.hikari.*` 参数。项目引入 `spring-boot-starter-jdbc`（`pom.xml:40`），走 Spring Boot 默认的 **HikariCP**，各项参数均为框架默认值（`maximum-pool-size` 默认 10）。

> 该数值为框架默认，**非本项目显式配置**。并发压力下的实际表现取决于默认值，如需调整须显式配置——**当前未做任何调优**，属待确认项。

## 7. 改造优先级建议

1. **立即**：为 §5 中 #1–#4 的四处多表写补 `@Transactional`（成本最低、收益最直接）。
2. **立即**：给 `friend(customerid, friendid)`、`goodstype.GoodType` 加唯一索引，让数据库兜住 #5–#7 的 TOCTOU 竞态。✅ **已完成 1/3**：`order.OrderId` 于 **2026-09-30 S1a（`1e2fcfa`）** 加了 UNIQUE `uk_order_orderId`（§5 第 9 行已标「已消除」）。⚠️ **2026-10-04 订正（drift-fix 笔）**：原写「剩余两项**未做**」不准确 —— **(a) `friend`**：其 TOCTOU（#5/#6）**已被 B2 的 `runLocked` 覆盖**（CI `ci49` 2026-10-03：`friendDupRows` 恒 = 1、`friendDupRounds = 0/5`）⇒ **只剩唯一索引未建**（仍建议建，作为最后防线）；**(b) `goodstype`**：**仍会造重复行**（同一份 `ci49`：`goodstypeDupRows` 4 / 1 / 4 / 3 / 3，**4/5 轮复现**；根因 `GoodsTypeCacheService:71-72` 裸 `INSERT`、不在 B2 的 5 落点内）⇒ 本条对 `goodstype` 的「**立即**」**完全成立**（唯一索引 + 写入路径两者都未做）。
3. **中期**：把 §3 的 CAS 模式推广到其余状态变更类写接口。
4. **中期**：修复 `OrderVolume` 的统计逻辑（`orderAdd` 成功后递增，或改为查询时实时聚合）。

> 上述为改造建议，**非现状描述**。
