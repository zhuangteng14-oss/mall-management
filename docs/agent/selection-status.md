# 技术选型现状 — 对照 `agent.md` 的「目标态」

> **本文只查现状、只归类，不做任何实现。**
> 证据来源：`agent.md:142-173`、`Mall-management-server/pom.xml`、`src/main/java` 实读、`git log`。
> 核查日期：2026-09-30（B3 合并后，`origin/main` = `148e77b`）。
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
| **分布式锁**：Redisson `tryLock(wait,lease)` + finally `isHeldByCurrentThread` | ✅ | B2；`tryLock` 4 处实命中。⚠️ 但取锁超时 / Redis 不可达 / 冷却窗口**三路 fail-open 回落原路径**（`tech-debt.md:275`） |
| **MySQL 死锁**：固定加锁顺序 + 小事务 + `@Version` 乐观锁 + `@Retryable` | 部分 | `@Transactional` 10 处；**`@Version` ❌（0 命中）**；**`@Retryable` ❌（0 命中，且无 spring-retry）** |
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
| `@DistributedLock` | ❌ 无此注解 | B2 是**模板方法** `DistributedLockService.runLocked(...)`（`DistributedLock` 字符串的 8 处命中全是类名/文档，**无 `@interface`**） |
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
| **Spring Retry 死锁重试** | 三处 CAS 的失败是**业务拒绝**（守卫不命中），不是**技术失败** —— 重试对业务拒绝无意义且有害。现无多表写事务 | **出现多表写事务**（`goodsAdd` + `productsales` 那条边界），或给 `order.OrderId` 加 UNIQUE 后需要重试唯一冲突（`DuplicateKeyException`） |
| **Logback JSON 编码器** | 需要日志被**机器消费**（采集/检索/告警）才有价值；现在是 `nohup ... > app.log` + 人看 | **接入日志聚合**（ELK/Loki）或**要从日志做告警**（与「频次告警」同源） |
| **线程池分池** | 现在只有订单时间线**一个**池，且拒绝策略是 discard-oldest —— **丢的是观测，不是业务**，丢得起 | **出现第 2 个异步出口且其任务不可丢**（通知 / 对账）—— 那时「共享池 + 同一个拒绝策略」第一次变成错：时间线噪音会把不可丢的任务挤掉 |

---

## 5. 需裁决的两处（本文件只记录，不代为裁决）

1. **`agent.md:142`「能引库就不自研」与 B3 背离。** B3 没引 Spring StateMachine，而是自研了 `OrderStateMachine`。理由（B3 类注释自述）：需要的是**无状态、描述性的转移表**（SQL 谓词守卫 + golden copy 钉住线上语句逐字符不变），而 Spring StateMachine 是**有状态实例机**，形态不匹配。
   → 二选一：给 `agent.md` 补一条例外说明，或在 `tech-debt.md` 记一笔「自研偏离」。
   ⚠️ 本次**未动 `agent.md`**（边界）。
2. **「乐观锁」是否已被谓词守卫等效覆盖。** 强制规范要求 `@Version`；B3 用 `WHERE PayIf = 0` 这种**谓词守卫**做 CAS。二者都能防丢失更新，但**语义不同**：`@Version` 是「整行版本」，谓词守卫是「本动作那一位」—— 后者**有意更宽**（TD-17：伪造态 `(0,1,0)` 也能被 PAY 推走）。故这不是等价替换，而是**有意的语义差异**，需在规范侧明确。

---

## 6. 一句话结论

7 项选型：**3 项已落地**（Redisson / Caffeine / Micrometer+Actuator）、**1 项以自研替代**（Spring StateMachine）、**3 项未落地**（Resilience4j / Logback JSON Encoder / Spring Retry）；另「切面 + 注解」形态**整体未落地**。未落地的四项均**有明确触发条件**，**三层前不必补**。
