# 技术选型现状 — 对照 `agent.md` 的「目标态」

> **本文只查现状、只归类，不做任何实现。**
> 证据来源：`agent.md:142-173`、`Mall-management-server/pom.xml`、`src/main/java` 实读、`git log`。
> 核查日期：2026-09-30（B3 合并后，`origin/main` = `148e77b`；**本笔修订时** `origin/main` = `2cc2338` —— **§6 为本次新增**，§1–§5 仍是 `148e77b` 的截面）。
> 档位：`亲读` = 逐行读过；`亲读(pom)` = grep 计数；`推断` = 由亲读证据链推导。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-02 实测**；重构后须重测。用户域旧 `U:<n>` / `UserController:<n>` 为三层前 264 行版 `UserController` 的历史坐标（2026-10-02 第三笔 `0c07e96` 后已重指）。
> ⚠️ **2026-10-04（触发条件汇总核对笔）新增 §8 / §9**：**§8 项目待办与优化总表（33 项）** + **§9 新功能引出表（6 项）** 自本笔起收口于本文件。**§1–§7 一字未改**（原 §7「一句话结论」的编号与内容不变，§8/§9 为文末追加）。

---

## 1. `agent.md:142` 「技术选型（目标态）」7 项逐项核对

> 原文标着【目标态，当前项目尚未引入】—— 该标注**已过期三处**（下表前三行）。

| 项 | 目标库 | 现状 | 落地笔 | 归属 |
|---|---|---|---|---|
| 分布式锁 | Redisson | ✅ **已实现** | B2 `8810efe`（PR #6 `aacd2bb`）：Redisson 3.46.0，5 落点 + fail-open + `mall.lock.*` 4 指标 | — |
| 本地缓存 | Caffeine | ✅ **已实现**（L1） | B1（PR #4 `dcf2d39`）：`TwoLevelCache` = L1 Caffeine + L2 Redis | — |
| 指标 | Micrometer + Actuator | ✅ **已实现** | C1 `aab3b9d`（PR #5 `3f8c4b7`）：**21 个 `mall.*` 指标名**（11 cache + 4 lock + 6 order.state）+ actuator 暴露 `health,metrics` | — |
| 状态机 | Spring StateMachine | ⚠️ **未引库，自研了等价物** | B3 `b226357` 自研 `OrderStateMachine`（转移表 + 守卫层/语义层分离 + golden copy 回归闸） | **需裁决**（见 §5） |
| 限流熔断 | Resilience4j | ❌ **未实现** | — | 未归属 |
| 日志 | Logback JSON Encoder | ❌ **未实现** | — | 未归属 |
| 死锁重试 | Spring Retry | ❌ **未实现** | — | 未归属 |

依赖侧证据（`pom.xml` grep 计数，`亲读(pom)`）：`redisson` 7 命中、`caffeine` 3、`micrometer` 2、`spring-boot-starter-actuator` 1；**`resilience4j` / `spring-retry` / `statemachine` / `logback-json` 均为 0**。

---

## 2. `agent.md:154-162` 「缓存与锁的强制规范」落实度

> 这一节比「7 项选型」更细，也更常被引用；逐条对现状。

| 规范条目（原文） | 落实 | 现状与差距 |
|---|---|---|
| **击穿**：逻辑过期 + Redisson 互斥重建 + Caffeine 本地兜底 | 部分 | Caffeine 本地兜底 ✅；互斥重建 ✅ **但用 Spring `sync=true` 的进程内 single-flight（`mall.cache.singleflight`），不是 Redisson 分布式互斥** ⇒ 多实例下每个实例各回源一次；**逻辑过期 ❌** |
| **雪崩**：随机 TTL + 多级缓存 + Resilience4j 限流 | 部分 | 多级缓存 ✅；**随机 TTL ❌**（B1 明确不做，触发条件 = 某缓存族 key 数 > ~10）；**Resilience4j ❌** |
| **穿透**：空值缓存 + 布隆过滤器 + 参数校验 | 部分 | 空值缓存 ✅（`549f74f` 首个负缓存，`userSelect`）；**布隆过滤器 ❌**；**参数校验 ❌**（无 validation 依赖，全仓 `@Valid` = 0） |
| **分布式锁**：Redisson `tryLock(wait,lease)` + finally `isHeldByCurrentThread` | ✅ | B2；`tryLock` **真实调用 1 处**（`DistributedLockService:249` 的 `lock.tryLock(wait, lease, MILLISECONDS)`）。⚠️ **2026-10-02 实测订正**：原记「4 处实命中」是把 `LockSceneService` 里**注释提到 `tryLock`** 的 3 行（`:57` / `:268` / `:269`）一并数了进去。⚠️ 但取锁超时 / Redis 不可达 / 冷却窗口**三路 fail-open 回落原路径**（`tech-debt.md:291`）。⚠️ 此处原引 `:275` —— 该行号**在 S1a-2 之前就已偏 1**（实测该版本里目标表行在 **276**），S1a-2 的 +4 行把它推到 **280**，本笔一并更正；⚠️ **2026-10-08（立 TD-18）** 该文件的 TD 段再 **+11 行** ⇒ 此指针**再推至 291**（本处已同步）。 |
| **MySQL 死锁**：固定加锁顺序 + 小事务 + `@Version` 乐观锁 + `@Retryable` | 部分 | `@Transactional` **注解 4 处**（2026-10-04 起）：`UserService.loginAdd:124`（2026-10-02 三层第三笔前在 `UserController:57`）+ **C#2 的商品域三处**（`GoodsService.goodsAdd` / `goodsUp` / `goodsDe`）。⚠️ **2026-10-02 实测订正**：原记「10 处」是**把 javadoc / 注释里写到 `@Transactional` 的地方也数了进去** —— `grep -rn "@Transactional"` 在 `src/main/java` 于 2026-10-02 得 18 行、**2026-10-04 复测得 45 行**（本笔自己添了 3 处真注解与若干说明文字），其中 **4 行是真注解、41 行是说明文字**（含 `OrderService:26` 那条**禁令**、`OrderDao:39`、`GoodsDao:52-56` 等）—— **该「1 处」已被 C#2 改为 4 处**。**`@Version` ❌（0 命中）**；**`@Retryable` ❌（0 命中，且无 spring-retry）** |
| **状态机**：显式 transition + 乐观锁 + 流转日志 + 频次告警 | 部分 | 显式 transition ✅（B3 转移表）；流转日志 ✅（B3 `order_state_transition` 时间线）；**乐观锁 ❌**（改用谓词守卫 CAS —— 需判断是否算等效，见 §5）；**频次告警 ❌** |
| **线程池**：分池 + 有界队列 + `orTimeout` + 明确拒绝策略 | 部分 | 有界队列 ✅（`ArrayBlockingQueue(1024)`）；明确拒绝策略 ✅（discard-oldest-with-callback）；**分池 ❌（B3 只有单池）**；**`orTimeout` ❌（0 命中）** |

---

## 3. `agent.md:164-173` 「通用做法：切面 + 注解」落实度

目标态是「把锁、缓存、状态机、重试、监控打点做成切面 + 注解，业务代码只加注解」：

```java
@DistributedLock(key = "#orderId", waitTime = 3, leaseTime = 10)
@CacheProtect(key = "'order:' + #orderId", ttl = 60, jitter = 20)
@StateTransition(from = "CREATED", to = "PAID")
@Retryable(retryFor = OptimisticLockException.class, maxAttempts = 3)
```

| 目标注解 | 现状 | 实际形态 |
|---|---|---|
| `@DistributedLock` | ❌ 无此注解 | B2 是**模板方法** `DistributedLockService.runLocked(...)`。⚠️ **2026-10-02 实测重数**：`DistributedLock` 字符串在 `src/main/java` **37 处**、全仓 tracked 文件 **92 处**，**每一处都是类名 `DistributedLockService`、import、或注释 / 文档**；**全仓 `@interface` = 0 命中**（只在两份文档的行文里出现过该字面量）⇒ 原记「8 处」口径不明，但**「无 `@interface`」这条结论不变** |
| `@CacheProtect` | ❌ 无此注解 | B1 用 Spring 框架注解 `@Cacheable` / `@CacheEvict` / `@Caching`，无自研切面（**故无独立的 `ttl`/`jitter` 参数位** —— 这正是「随机 TTL 做不了」的结构原因：`entryTtl` 是静态的，见 `cache-layer.md` §9.1） |
| `@StateTransition` | ❌ 无此注解 | B3 是**显式转移表调用**（`OrderStateMachine.updateSql(...)`），刻意的描述性姿态 |
| `@Retryable` | ❌ 无此注解 | 无 spring-retry |

**全仓 `@interface` 命中 0 —— 零自研注解。** ⇒ **「切面 + 注解」这一层整体未落地**：已落地的是「引库 + 模板方法 / 框架注解」。这是目标态与现状之间**最大的形状差**，且它同时解释了上面若干条「部分」的成因（没有切面，就没有一处能挂 `ttl`/`jitter`/`retry` 参数的声明点）。

---

## 4. 未实现的四项：**何时才会真正需要**（触发条件）

> 结论先行：**四项都属「等第 N 个同类组件出现」的一次性重组，不是逐笔增量 ⇒ 三层前都不必补。**

| 项 | 为什么现在不需要 | 触发条件（何时必须补） |
|---|---|---|
| **Resilience4j 限流熔断** | 熔断要有 ≥2 个**外部依赖**才有意义；现在外部依赖只有 Redis 一个（DB 是**真值源**，熔断它没有降级语义）。而 Redis 不可用已由 `L2HealthProbe` **自研冷却窗口**（30s）覆盖 ⇒ 引库的增量 = 把手写冷却换成库 | **出现第 2 个外部服务调用**（对象存储 / 第三方支付 / 短信），或需要**按接口限流**（现在零限流） |
| **Spring Retry 死锁重试** | 三处 CAS 的失败是**业务拒绝**（守卫不命中），不是**技术失败** —— 重试对业务拒绝无意义且有害。现无多表写事务 | **出现多表写事务**（`goodsAdd` + `productsales` 那条边界），或给 `order.OrderId` 加 UNIQUE 后需要重试唯一冲突（`DuplicateKeyException`）。**🔄 2026-09-30 裁决：后一个条件已被 S1a 变为现实（`uk_order_orderId`，`1e2fcfa`），裁决为「不重试」** —— 撞 UNIQUE 时 `orderAdd` 返回 `{"success":false,"msg":"数据库错误：Duplicate entry…"}` 是**正确告警**：UNIQUE 是**最后防线**、**不是正常路径**（正常路径下前端失败会重新生成 `OrderId`，撞上即说明**应用层有 bug**，改成「订单已存在」反而把它掩盖）⇒ **Spring Retry 仍不需引入** |
| **Logback JSON 编码器** | 需要日志被**机器消费**（采集/检索/告警）才有价值；现在是 `nohup ... > app.log` + 人看 | **接入日志聚合**（ELK/Loki）或**要从日志做告警**（与「频次告警」同源） |
| **线程池分池** | 现在只有订单时间线**一个**池，且拒绝策略是 discard-oldest —— **丢的是观测，不是业务**，丢得起 | **出现第 2 个异步出口且其任务不可丢**（通知 / 对账）—— 那时「共享池 + 同一个拒绝策略」第一次变成错：时间线噪音会把不可丢的任务挤掉 |

---

## 5. 需裁决的两处（本文件只记录，不代为裁决）

1. **`agent.md:142`「能引库就不自研」与 B3 背离。** B3 没引 Spring StateMachine，而是自研了 `OrderStateMachine`。理由（B3 类注释自述）：需要的是**无状态、描述性的转移表**（SQL 谓词守卫 + golden copy 钉住线上语句逐字符不变），而 Spring StateMachine 是**有状态实例机**，形态不匹配。
   → 二选一：给 `agent.md` 补一条例外说明，或在 `tech-debt.md` 记一笔「自研偏离」。
   ⚠️ **2026-09-30 更正**：`agent.md` 已动 —— 但**只加了现状标注**（7 项逐项 ✅ / ⬜ / ⚠️ + 指向本文件），**规则正文一字未改**；本条的裁决（补例外说明 / 记一笔「自研偏离」）**仍开着**。
2. **「乐观锁」是否已被谓词守卫等效覆盖。** 强制规范要求 `@Version`；B3 用 `WHERE PayIf = 0` 这种**谓词守卫**做 CAS。二者都能防丢失更新，但**语义不同**：`@Version` 是「整行版本」，谓词守卫是「本动作那一位」—— 后者**有意更宽**（TD-17：伪造态 `(0,1,0)` 也能被 PAY 推走）。故这不是等价替换，而是**有意的语义差异**，需在规范侧明确。

---

## 6. C3（Vue3 管理端）数据源清单（2026-09-30 定）

> C3 **未开工**（§1 表）。本节**只定数据源**，不做实现 —— **结论本笔落、实现随 C3**。
> 逐项只回答「C3 的面板各自读什么、够不够」。⚠️ 这四项**都不是「未落地的库」**，故**不进 §4**
> （§4 记的是 Resilience4j / Spring Retry / Logback JSON / 线程池四种库级选型）。

| # | 用途 | 数据源 | 结论 | 依据 |
|---|---|---|---|---|
| 1 | **时序数据**（QPS / 缓存命中率 / 锁等待曲线） | 进程内存，**保留 30 分钟** | ✅ **不引 Prometheus** —— 单实例 + 内部管理系统，30 分钟窗口用**内存环**足够。⚠️ 这是**决定**、不是实测。触发条件（何时才需要 Prometheus）：**多实例部署**，或需要 **> 30 分钟**的历史留存 | 现事件环**按条数**限界、**不按时间**：`EVENT_CAPACITY = 512`（`MallCacheMetrics:115` / `MallLockMetrics:77` / `MallOrderStateMetrics:88`），其注释自述窗口「满负荷下数百毫秒」（cache）／「约 4 秒」（order.state）⇒ **远不够 C3 看曲线**，故 C3 另起一个**按时间**保留的内存序列，**与既有三份环并存**（不动它们）。⚠️ **2026-10-02 实测订正**：全仓 `Prometheus` 字面量 **2 处** —— `MallCacheMetrics:30` 的 javadoc（「…全小写点分（Prometheus 友好）」）与 `pom.xml:94` 的注释（「…不引入 Prometheus registry」）。**两处都不是依赖、不是代码**，原记「0 命中」已不成立，但**结论（不引 Prometheus）不变** |
| 2 | **事件流**（最近事件原始列表） | 三份 512 槽环 | ⚠️ **扩到 8192 槽** —— **独立一笔**，**不在 C3 内做** | 三份环各自 `512`（见上）；`MallLockMetrics:23` 记「两份 512 槽数组（各约 4KB 引用）」⇒ 8192 = 512 × **16**，量级仍可忽略。**为什么必须独立成笔**：扩容会**改变现有断言的观察窗口**（`A-S2a-1a` 对 `mall.lock.acquires` 增量敏感等），要单独核 A3 —— 混进 C3 会让「改视图」与「改护栏口径」互相掩护 |
| 3 | **指标 tag** | 现 **21 个** `mall.*` 指标的 tag 集 | ✅ **现状够** —— C3 **不新增 tag** | tag 全取自**封闭枚举**：`cache`（= cacheNames）/ `result` / `kind` / `to` / `lock`（= 5 个 `LockName`）；且有**基数铁律「key 绝不进 tag」**（`MallCacheMetrics:31-32`）—— C3 需要的分组维度（按缓存族 / 按锁名 / 按端点）**都已在既有 tag 里** |
| 4 | **B3 时间线聚合索引** | `order_state_transition` | ⏸ **暂缓** —— **先给查询**，按实际查询形状再定索引 | 该表**现只有** `idx_order_state_transition_order(OrderId, OccurredAt)`（`db-schema.md:29`、`:282`）；`db-schema.md:29` 已明写它是「**C3 时间线视图的数据源**」。C3 的视图查询形状（按单 / 按时间区间 / 按动作）**尚未出现** ⇒ 现在加索引是**猜**。⚠️ 暂缓的理由是**没有依据**，**不是不允许** —— 加索引属**无条件允许**（`three-layers.md` 约束 1） |

---

## 7. 一句话结论

7 项选型：**3 项已落地**（Redisson / Caffeine / Micrometer+Actuator）、**1 项以自研替代**（Spring StateMachine）、**3 项未落地**（Resilience4j / Logback JSON Encoder / Spring Retry）；另「切面 + 注解」形态**整体未落地**。未落地的四项均**有明确触发条件**，**三层前不必补**。另：**C3 的数据源已定**（§6）—— 四项**都不引新库**，其中「事件流扩 8192 槽」**独立一笔**。

---

## 8. 项目待办与优化总表（33 项）

> **口径（2026-10-04 新增）**：全项目待办与优化收口于此，**一项一行**，按类排序（类 1 → 类 2 → 类 3 → 类 4）。
> **算式**：**33 = 类 1 10 项 + 类 2 有效 10 项（另 `#12` 为「已升类 1」占位行，不重复计数）+ 类 3 6 项 + 类 4 7 项**。
> **「触发/时机」列口径**：类 1 = **前置依赖 + 顺序**；类 2 = **触发条件**；类 3 = **时机（可选）**；类 4 = **具体触发条件**。
> ⚠️ **本表不收「明确不采纳」项** —— 布隆过滤器（现址 §2 穿透行 `❌`）、JPA 迁移（现址 `three-layers.md` D#4 裁决）、全量 TS 化 等，**既不属类 1–4，也不在这 33 项内**。
> **本表只管「待办」，不复制长理由**：选型现状见 §1–§3，四项未实现库的**长理由**见 §4（本表只指回 `§4:<行>`）。
> **标注**：**【新拟】** = 本笔首次给出的可证伪门限；**【口述】** = 尚未落文档（仅在对话里）。**【文档】** = 已有文档锚点。
> ⚠️ 本表为**快照**：`#10 goodstype` 与 `#12 friend` 的触发判据取自 **CI `ci49`（2026-10-03）**；其后若重跑 A3 须重判。
> ✅ **2026-10-04 #11（`fix/goodstype-unique`）了结 `#10`** —— 唯一索引已落地，但**该笔不重跑 A3**（复跑由合并后的 CI 承担）。⇒ 本表 `#10` 那行引的 `ci49` 数值仍是**加索引前**的截面；**`#12 friend` 的触发判据仍待重判**，且须以新报告复核 `#10` 是否真归零（判据见 `db-migration.md` 注意 7）。

### 8.1 类 1 —— 必做（10 项）

| # | 项 | 分类 | 触发 / 时机 | 现状 | 归属 |
|---|---|---|---|---|---|
| 1 | 缓存正确性收口 | 类 1 | 前置：② 笔已 merge（✅）⇒ **现在即可** | ⚠️ **文档无同名条目**（口径是你的编号）；最近锚点 = `cache-layer.md §11`（㊳–㊷ 登记表，多为「登记 + 接受」未复裁）+ `§0` 硬约束 + `§11 附` 待办 B / E / ㊸ | 待指派（缓存笔） |
| 2 | `C#2` `goodsAdd` 事务边界 | 类 1 | 前置：**#1 依赖已裁放行**（2026-10-04 用户裁决；**#1 本身仍挂着、不消**）—— 理由：改动面不重叠（#1 落 `GoodsCacheService` / `cache-layer.md §11`，C#2 落 `GoodsService` 三个**写**方法） | ✅ **已完成（2026-10-04，`fix/goods-write-tx`）** —— 范围**扩到三处**（`goodsAdd` + `goodsUp` + `goodsDe`，同文件 / 同表对 / 同修法）；落点是 `GoodsService` 的三个写方法。⚠️ **真正的难点不是「加不加注解」**：三个方法都自己 catch 吞异常 ⇒ 必须配 `setRollbackOnly`（`GoodsService.markRollbackOnlyIfActive()`），否则只加 `@Transactional` 是**假绿**。判据：`GoodsWriteTxAtomicityTest`（6 用例，含突变对照）；⚠️ **A3 不打这三个端点**（`three-layers.md §H.3`），护栏只有单测。关键：`orderAddLocked` **禁** `@Transactional` 的**反面** = `goodsAdd`（**无锁**）**应当加**（`three-layers.md:51` / `:135`） | 公共层笔 |
| 3 | `B#1` 价格校验（资损） | 类 1 | 前置：校验层（`three-layers.md:279`「三层内顺带做」）—— 四域三层**已完** ⇒ 现在即可 | ✅ **已完成（2026-10-04，`fix/price-validation`）** —— `OrderService.orderAdd` **锁之前**加 `priceRejection`（正面判据 `Float.isFinite && >= 0 && <= 10^7`）；非法价 ⇒ **HTTP 200 + `{success:false,msg:"价格不合法"}`**，且**不取锁、不触库**。⚠️ **性质 = 边界收窄，不是资损修复**：合法但有界的「1 元的电视」仍挡不住 ⇒ **价格真值源（支付侧）** 已独立挂账（落点 = `progress.md` **挂账段**；⚠️ **不落本 §8 类 4** —— 理由见 `progress.md` §3 的 TODO 登记块：§8 编号空间 `#1..#33` 与 **PR 号**同形，再增行会造混淆）。判据 = `OrderAddPriceGuardSmokeTest`（8 用例，`@WebMvcTest` 切片、**无 `A3_ENABLED` 门控** ⇒ 在 `backend` **阻塞** job 里**真跑**）+ 4 组突变对照。⚠️ **A3 不替本笔报警**：`ConcurrencyRunner` 里 `"Price"` 只出现一次且恒为 `"1.5"`、S4a 8 条依赖状态位（TD-17）⇒ A3 零回归**不构成**对本笔改动的验证 | 校验层笔（`three-layers.md:279`） |
| 4 | `C#6` 状态位拦截 | 类 1 | ✅ **已完成（2026-10-04，`fix/order-state-guard`）** —— 裁决取**拦截**（不是观测、也**不是「不变式前移」**）；⚠️ **原记「拦截会使 A3 的 S4a 全部 8 条断言失去前置」已实测订正**：S4a 的 5 个前置态**全是合法态** ⇒ 只 **3 条**前置**构造方式**变、**断言语义 0 条变**；**真正被打断的是 C 组** | ✅ **已完成** —— 出生态白名单 `{(0,0,0),(1,0,0)}`（`OrderState.isInitial`），闸在 `OrderService.orderAdd` 的 `runLocked` **之前**、**排在 B#1 价格闸之后**；非法出生态 ⇒ **HTTP 200 + `{success:false,msg:"订单状态不合法"}`** 且**不取锁、不触库**，同时记 `mall.order.state.birth.rejected{shape}` + 一行 WARN。⚠️ **白名单必须含 `(1,0,0)`**：生产前端 `ProductPage.vue:354` 就直传 `PayIf=1` 下单（同页 `:342-343` 固定 `DeliveryIf:0`/`ReceiveIf:0`）⇒ 拒它 = **弄坏生产下单**。⚠️ `(1,1,0)`/`(1,0,1)`/`(1,1,1)` 三条**合法但非初始**态**刻意被拒**。判据 = `OrderAddStateGuardSmokeTest`（10 用例，`@WebMvcTest` 切片、**无 `A3_ENABLED` 门控** ⇒ 在 `backend` **阻塞** job 里**真跑**）+ **3 组突变对照**（删闸 ⇒ 7 红／放宽成「只挡非法形状」⇒ **恰 3 红**／闸挪进 `runLocked` ⇒ 7 红）+ A3 `A-S4a-0`/`A-C6-1`/`A-C6-2`（`gates.count` **27 → 30**，⚠️ `concurrency` job 是 `continue-on-error: true` ⇒ **只算旁证、不拦 merge**）。原登记（现址）：`three-layers.md:182`、`defect-survey.md` 病 B（TD-17）、`progress.md §5-G` | `fix/order-state-guard` |
| 5 | 前端规范化（7 项 B 类） | 类 1 | 前置：无（**可并行**） | `progress.md §6-C`：B 类 7 项 = P2.1 / P2.2 / P2.3 / P2.5 / P2.6 / P2.7 / P1.1。⚠️ **P2.6 含独立安全债**：`loginPassword` **明文入 sessionStorage** | 独立笔「前端 API 层规范化」 |
| 6 | 鉴权（拦截器 + JWT + RBAC） | 类 1 | 前置：三层后、**#8 之前**（BD-03 建议时机 T2） | `business-decisions.md:42-52` BD-03，方案 A / B / C **待裁**；`progress.md` §4 | 鉴权笔（待裁后） |
| 7 | C2 SSE 事件流 | 类 1 | 前置：三层后 | `progress.md` §1（C2 行）：全仓 `SseEmitter` / `text/event-stream` / `WebFlux` = **0 命中** | 独立笔 |
| 8 | C3 管理端 | 类 1 | 前置：**依赖 #6 鉴权** | `progress.md` §1 / §4；数据源清单**已定**（§6，PR #23） | 独立笔 |
| 9 | 部署（打包 + systemd + nginx + profile） | 类 1 | 前置：三层后 | `progress.md` §4：无 Dockerfile；`TD-05 / 06 / 08 / 09` 四条与部署**直接相关**（`progress.md` §5-C） | 独立笔 |
| 10 | **goodstype 唯一约束** | 类 1 ⚠️ **本笔升类** | 时机：**待指派**（本笔不代排）—— 触发**已满足** | ✅ **已完成（2026-10-04 #11，`fix/goodstype-unique`）** —— UNIQUE `uk_goodstype_GoodType` 已落库（`mall_management_sql.sql:246-262`）；catch 已区分 `DuplicateKeyException`（`GoodsTypeCacheService:93`，返回「类型已存在」而非「新增失败」）。⚠️ **写入路径不加分布式锁**（DB UNIQUE 已兜底；加锁会动 B2 的 5 落点、触发 A3 断言重校准）。⚠️ 验收判据（`b2PerRound[].goodstypeDupRows` 5 轮恒 = 1）**待 CI 复跑** | #11 `fix/goodstype-unique` |

### 8.2 类 2 —— 触发条件（11 行 = 10 有效 + 1 占位）

| # | 项 | 分类 | 触发 / 时机 | 现状 | 归属 |
|---|---|---|---|---|---|
| 11 | `login.Id` 唯一索引 | 类 2 | 【新拟】实测同一 `Id` 多条 `login` 行（`GROUP BY Id HAVING COUNT(*)>1` 非空）；**或鉴权笔落地** | 已登记 —— `db-schema.md:207` / `:524`（前提 C；原 `:194` / `:468`，2026-10-10 块 3 增量后重指）、`defect-survey.md:81`（病 C）、`three-layers.md:95` | 鉴权笔（服务侧）/ 独立（DB 侧） |
| 12 | goodstype 唯一约束 ⚠️ **已升类 1 ⇒ 本行占位，不重复计数** | 类 2 → **已升类 1 `#10`** | 触发**已满足** | ✅ **已完成（2026-10-04 #11，`fix/goodstype-unique`）** —— 见 `#10` 行。升类依据（本节原值）：CI `ci49`（2026-10-03）**4/5 轮** `goodstypeDupRows ≥ 2`（逐轮 4 / 1 / 4 / 3 / 3）；代码级根因：`GoodsTypeCacheService:71-72` **裸 `INSERT`**、且**不在 B2 的 5 落点内**。原登记 —— `concurrency-map.md:77` + `:91`、`db-schema.md:196`（原 `:186`，2026-10-10 块 3 增量后重指） | #11 `fix/goodstype-unique` |
| 13 | `friend` 唯一约束 | 类 2 | 触发**未满足**：`ci49` `friendDupRows` 恒 = **1**、`friendDupRounds = 0/5`（B2 的 `runLocked` 已覆盖 `MessageService.friendAdd`）。⚠️ `cache-layer.md:462` 的「12–19 条」是 **B1 时代**值，**已被 B2 证伪** | 已登记 —— `concurrency-map.md:75` / `:91`、`db-schema.md:526`（原 `:470`，2026-10-10 块 3 增量后重指）、`defect-survey.md:85`（病 G） | 独立（schema 变更） |
| 14 | query-timeout（`spring.jdbc.template`） | 类 2 | 实测单条 SQL P99 > 1s；或 `hikaricp.connections.pending` > 0 | 已登记 —— `cache-layer.md:434`（② 笔） | ② 笔（登记）；**未修** |
| 15 | N+1 改 JOIN（`goodsHome` / `friendList` / `friendRequests`） | 类 2 | 【新拟】任一 N+1 接口实测 P99 > 1s —— 门限取「**摊薄失效点**」：缓存只把 1+2N 摊薄为「每 TTL 一次」、是**掩盖非修复**（`cache-layer.md:161`） | 已登记 —— `project-map.md:111-126`、`cache-map.md:60-97`、`context-packs/fix-n-plus-one.md`、`cache-layer.md:142-181` | 独立笔 `fix-n-plus-one` |
| 16 | TTL 抖动（防同时过期） | 类 2 | 某缓存族 key 数 > ~10（**实测未满足**：5 三元组 + 4 型号组） | 已登记 —— `cache-layer.md:472`（§11 附 行 E）+ `:328`（§9.1 雪崩行）、`§2:35`、`tech-debt.md:288` | 独立（B1 明确不做） |
| 17 | 多实例 / 分布式场景 | 类 2 | **部署第 2 个实例**（原为「单飞等待无界」的触发条件 ③，本笔提为独立条目） | 已登记（散落）—— `cache-layer.md:433` / `:434`、§6:96 | 独立（部署笔 → `#9`） |
| 18 | Resilience4j 限流熔断 | 类 2 | 出现第 2 个外部服务调用；或需按接口限流 | 已登记 —— **`§4:72`** | 无（未归属） |
| 19 | Logback JSON 编码器 | 类 2 | 接入日志聚合（ELK / Loki）；或需从日志告警 | 已登记 —— **`§4:74`** | 无（未归属） |
| 20 | Spring Retry 死锁重试 | 类 2 | 出现多表写事务（`goodsAdd` + `productsales` 那条边界）。⚠️ 原第二条件（撞 UNIQUE 后重试）2026-09-30 **裁决为否** | 已登记 —— **`§4:73`**（含裁决） | 无（未归属） |
| 20b | 线程池分池（**编号占位**，避与 `#21` 冲突） | 类 2 | 出现第 2 个异步出口且其任务**不可丢** | 已登记 —— `§2:40` + **`§4:75`** | 无（未归属） |

> **类 2 表 11 行 = 10 项有效 + 1 行 `#12`「已升类 1」占位**；**线程池分池为类 2 第 10 有效项，编号暂用 `#20b` 占位**（避免与类 3 的 `#21` 冲突）。

### 8.3 类 3 —— 可能补（6 项，看答辩需求）

| # | 项 | 分类 | 触发 / 时机 | 现状 | 归属 |
|---|---|---|---|---|---|
| 21 | TS 化前端（新代码用 TS / 旧 view 不动） | 类 3 | C3 阶段 / 可选 | 全 `docs/` grep `TypeScript` = **0 命中**（未登记） | C3 笔 【口述】 |
| 22 | Vite（构建快） | 类 3 | C3 阶段 / 可选 | 全 `docs/` grep = **0 命中**（未登记） | C3 笔 【口述】 |
| 23 | `<script setup>`（逐文件） | 类 3 | C3 阶段 / 可选 / 逐文件 | 全 `docs/` grep = **0 命中**（未登记） | C3 笔 【口述】 |
| 24 | 攻击模拟器「限流 / 熔断」演示 | 类 3 | C3 阶段 / 可选（Semaphore 10 行） | 攻击模拟器在档 —— `ddd-package-plan.md:45`；**限流熔断** = `§4:72` **未落地** | C3 笔 【文档 + 口述】 |
| 25 | 秒杀场景演示 | 类 3 | C3 阶段 / 可选 / **复用 B1–B5、不新建业务** | ⚠️ **前提不存在**：`goods` 表**无任何库存 / 数量列**、全项目无扣减逻辑（`concurrency-test.md:207`） | C3 笔 【口述】 |
| 26 | 微服务拆分（**答辩口径准备：不拆、保留 DDD 分域**） | 类 3 | C3 阶段 / 可选；若答辩问架构演进则说明 | `ddd-package-plan.md`（DDD 包规划**已执行**） | C3 笔 / 答辩材料 |

### 8.4 类 4 —— 登记未修（7 项）

| # | 项 | 分类 | 触发 / 时机 | 现状 | 归属 |
|---|---|---|---|---|---|
| 27 | `loginAdd` 无 else（TD-15） | 类 4 | 优先级「中」；**无触发条件** | `tech-debt.md:190-200`：`usertype` 非法值时返回「添加成功！」但**不插任何行**；状态「待确认（A3 登记，不在 A3 修）」 | 待指派 |
| 28 | `productSearch` 拼 SQL（**病 C**） | 类 4 | 数据量上量后 ⇒ 单请求**全表扫描**（无分页） | `defect-survey.md:124` 病 C：逐字符 `OR GoodName LIKE`、`_` / `%` **未转义** ⇒ 实测「关键词含 `_` / `%` 时返回全表」；现址 `GoodsDao.productSearch`（`GoodName LIKE` 拼装在 `:198`） | 待指派 |
| 29 | `goodsUp` 的 WHERE 缺 `GoodName` | 类 4 | 批量改名的**串改风险** | `defect-survey.md:127` 病 C | 待指派 |
| 30 | `getUserInfoById` 副本（记为「第三份」） | 类 4 | — | ⚠️ **文档立场相反**：`api-contract.md:79`（2026-10-04 鉴权笔 2 +19 重指） / `cache-map.md:77` 明写「两份并存是**有意的**，不是漏删」 | ⚠️ **需先与文档立场对齐**再定 |
| 31 | 单飞等待无界 | 类 4 | 沿用 `cache-layer.md:434` 的 **4 条**触发条件 | `cache-layer.md:434`（② 笔登记，含失败路径退化） | ② 笔（登记） |
| 32 | 雪崩形态 3（写后集中回填） | 类 4 | 同上 4 条（与 `#31` 是**同一实例的两个面**） | `cache-layer.md:434`：「与 §9.1『雪崩（同时过期）』行的**写后集中回填**是同一实例」 | 登记 |
| 33 | `productsales` 二元组无 UNIQUE | 类 4 | 【新拟】实测出现重复 `(ProductCode, MerchantId)` 行。⚠️ 原第二触发「**或 `#2` 落地时一并加**」**已过期**：`#2` 于 2026-10-04 落地（`fix/goods-write-tx`）**未**一并加该索引（本笔是行为变更、不含 schema 变更） | `db-schema.md:58`（原 `:55`，2026-10-10 块 3 增量后重指）：「`productsales` 无索引，亦无 `(ProductCode, MerchantId)` UNIQUE → **可写重复行**」 | 独立（schema 变更） |

---

## 9. 新功能引出表（6 项）

> **另一维度**：§8 按「项」列待办，本节按「**做什么新功能 → 引出什么优化 / 新增**」列依赖。两表**互不重复**（§8 记「已存在的待办」，本节记「新功能的引出」）。
> **标注**：**【口述】** = 尚未落文档；**【文档】** = 已有文档锚点。

| 新功能 | 引出的优化 / 新增 | 依赖已完成项 |
|---|---|---|
| **秒杀演示** 【口述】 | 库存预热（读侧预载）+ 分布式扣减（复用 B1 `TwoLevelCache` + B2 `runLocked`）。⚠️ 前提当前**不存在**：`goods` 无库存列（`concurrency-test.md:207`） | B1 + B2 |
| **权限管理页** | 拦截器 / 过滤器链 **【文档】**（`business-decisions.md:42-52` BD-03，方案待裁）+ RBAC 三表 **【口述】**。⚠️ 鉴权落地**同时满足** §8 `#11`（login.Id 唯一索引）的一个触发条件 | **鉴权笔**（= §8 `#6`） |
| **攻击模拟器** 【文档】 | scenario 集合 + REST 触发 + SSE 实时输出；可复用 `LockSceneService`（`ddd-package-plan.md:45`） | B1–B5 + C2（= §8 `#7`） |
| **多商家入驻** 【口述】 | `merchant` 主表 + 到 `login.Id` 的 FK（FK 属**「有条件允许」**，四项前置缺一不可 —— `agent.md:85-89`） | S3（三层后） |
| **实时日志（C2 SSE）** 【文档】 | SSE 出口（**全仓 0 命中** —— `progress.md` §1）+ 三份指标环**扩到 8192 槽**（**独立一笔**，不混进 C2） | C1 指标 + §6 |
| **C3 管理端** 【文档】 | ① 事件流扩 8192 槽（独立笔）② `order_state_transition` 聚合索引（**按实际查询形状再定**，现在加是猜）③ 数据源清单**已定**（§6） | §6（清单已决，PR #23） |

> **跨表联动**（勿重复登记）：**鉴权笔**（§8 `#6`）⇒ 满足 §8 `#11` 触发条件之一；**部署笔**（§8 `#9`）⇒ 触发 §8 `#17`（多实例）。
