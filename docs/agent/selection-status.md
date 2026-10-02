# 技术选型现状 — 对照 `agent.md` 的「目标态」

> **本文只查现状、只归类，不做任何实现。**
> 证据来源：`agent.md:142-173`、`Mall-management-server/pom.xml`、`src/main/java` 实读、`git log`。
> 核查日期：2026-09-30（B3 合并后，`origin/main` = `148e77b`；**本笔修订时** `origin/main` = `2cc2338` —— **§6 为本次新增**，§1–§5 仍是 `148e77b` 的截面）。
> 档位：`亲读` = 逐行读过；`亲读(pom)` = grep 计数；`推断` = 由亲读证据链推导。

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
| **分布式锁**：Redisson `tryLock(wait,lease)` + finally `isHeldByCurrentThread` | ✅ | B2；`tryLock` **真实调用 1 处**（`DistributedLockService:249` 的 `lock.tryLock(wait, lease, MILLISECONDS)`）。⚠️ **2026-10-02 实测订正**：原记「4 处实命中」是把 `LockSceneService` 里**注释提到 `tryLock`** 的 3 行（`:57` / `:268` / `:269`）一并数了进去。⚠️ 但取锁超时 / Redis 不可达 / 冷却窗口**三路 fail-open 回落原路径**（`tech-debt.md:280`）。⚠️ 此处原引 `:275` —— 该行号**在 S1a-2 之前就已偏 1**（实测该版本里目标表行在 **276**），S1a-2 的 +4 行把它推到 **280**，本笔一并更正 |
| **MySQL 死锁**：固定加锁顺序 + 小事务 + `@Version` 乐观锁 + `@Retryable` | 部分 | `@Transactional` **注解 1 处**（`UserController:57` 的 `loginAdd`）。⚠️ **2026-10-02 实测订正**：原记「10 处」是**把 javadoc / 注释里写到 `@Transactional` 的地方也数了进去** —— `grep -n "@Transactional"` 在 `src/main/java` 得 18 行，其中 **17 行是说明文字**（含 `OrderService:26` 那条**禁令**、`OrderDao:39`、`GoodsDao:52-55` 等），真注解只有 1 行。**`@Version` ❌（0 命中）**；**`@Retryable` ❌（0 命中，且无 spring-retry）** |
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
