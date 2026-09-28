# context-pack: cache-layer（B1 方案）

> **证据来源**：3 个 Controller 全文、`pom.xml`、`application.properties`、`scripts/concurrency/ConcurrencyRunner.java`（断言 ID 与场景负载逐条实测）、`docs/agent/cache-map.md`、`project-map.md §6`、`concurrency-map.md`、`api-contract.md`、`environment.md`。
> **性质**：**方案文档，非实现。** 本文不含代码，不改 `pom.xml` / Java / properties。
>
> **行号约定**（与 `project-map.md §6` 一致）：`G:` = `MallManagementGoodsController.java`，`M:` = `MallManagementMessage.java`，`U:` = `MallManagementUserController.java`，`L:` = `ConcurrencyRunner.java`。
> 查询记 **「SQL 定义行 → 执行行」区间**（如 `M:48-50`）；方法调用记 **调用行**（如 `M:244`）。

---

## 0. 【硬约束】缓存不得落在「先查后插」的查重查询上

**本条优先级高于穿透 / 击穿 / 雪崩的全部防护设计。违反它的后果不是性能问题，是证据问题。**

项目里有三处 TOCTOU 缺陷，是 A3 阶段最值钱的产出（B-2 三处逐轮复现，断言 `A-B2-1`）：

| 缺陷 | 「查」 | 「插」 |
|---|---|---|
| 商品类型重复 | `G:47-48` `SELECT COUNT(*) ... WHERE GoodType = ?` | `G:56-57` `INSERT INTO ... goodstype` |
| 注册用户名重复 | `U:66-67` 查重 | `U:76` / `U:81` / `U:87` 等 6 处分支 INSERT |
| 好友重复添加 | `M:204-205` `SELECT ... FROM friend WHERE ...` | `M:217-219` `INSERT INTO ... friend` |

**A3 的 `A-B2-1` 断言期望这三处「缺陷复现」**（`ConcurrencyRunner.java:450` 登记为 `EXPECTED_DEFECT_PRESENT`）。

若把任何一处**查重查询**放进缓存：20 并发线程会看到同一个起点 → 要么插出更多重复，要么**被缓存挡住、缺陷不再复现 = 假绿**。后者更危险——A3 的证据被悄无声息抹掉，而 CI 依然全绿。

**落地规则**：
1. 缓存注解**只挂读路径**。`goodstype` 的 GET 分支可缓存；**POST 分支的 `G:47-48` 一行都不许碰**。
2. 任何「先 SELECT 判断存在性、再 INSERT/UPDATE」的查询，**天然排除在缓存范围外**，不因「读多」而入选。
3. 实现完成后，`A-B2-1` 必须仍是 `EXPECTED_DEFECT_PRESENT`。若变成 `DEFECT_FIXED`，先怀疑缓存，不要先庆祝。

### 0.1 这条约束在 `goodstype` 上怎么落地

**实测结论：`G:37` 的 GET 与 `G:47` 的 POST 在同一个方法里，不是两个方法。**

```java
// G:30-34
@RequestMapping(value = "/goodstype", method = {RequestMethod.GET, RequestMethod.POST})
public Object goodstype(@RequestParam(required = false) String GoodType,
                        @RequestParam(required = false) Integer GoodTypeNew) {
    if (GoodType == null) {                                    // G:36  ← 读分支
        String sql = "SELECT Id, GoodType, GoodTypeNew ...";    // G:37
        ...                                                     // G:38-42
    }
    String checkSql = "SELECT COUNT(*) ...";                    // G:47  ← 写分支
    ...                                                         // G:48-69
}
```

**推论 1：`@Cacheable` 不能挂在这个 Controller 方法上。** 挂了会**连写分支一起缓存**，正是 §0 要禁止的事。

**推论 2：不能靠「拆成两个方法放同一个类里」绕过。** Spring 缓存基于 AOP 代理，**同类内自调用不经过代理，`@Cacheable` 会静默失效**。且项目当前**零分层**——全项目 `grep` 无任何 `@Service` / `@Component` / `@Repository`，三个 `@RestController` 直接注入 `JdbcTemplate`（`project-map.md §3`「后端架构：无分层」）。

**做法：抽一个极薄的 Service 承载注解。**

| 层 | 内容 | 注解 |
|---|---|---|
| Service（新） | `listTypes()` —— 只含 `G:37-38` 的读查询 | `@Cacheable` |
| Service（新） | `addType(GoodType, GoodTypeNew)` —— 只含 `G:47-57` 的查重 + 插入 | `@CacheEvict` |
| Controller（改） | 保留 `if (GoodType == null)` 分支，**仅做委托** | 无缓存注解 |

**为什么必须是独立 bean**：读方法放进独立 Service bean 是被 AOP 代理拦截的前提（否则自调用绕过代理），同时满足 §10 原则「缓存注解挂 Service 方法」。**注解覆盖的方法体内只有 `G:37-38`，写分支的 `G:47-48` 不在任何被缓存的方法里——约束由结构保证，不靠自觉。**

**路由不变**：Controller 仍用同一个 `@RequestMapping(value="/goodstype", method={GET,POST})` + `GoodType == null` 判定，URL / HTTP 方法 / 返回结构全部不变 → **`A-S1-2` 的调用形态（GET 无参）与 `A-B2-1` 的 POST 路径都不受影响**。

> **B1 不做的事**：不改成一个 `@GetMapping` + 一个 `@PostMapping` 两个注解。那会把「GET 带 `GoodType` 参数」这种边角调用的行为从「进写分支」变成「进读分支」——是本阶段不需要的行为变化。现有 `if` 分支保持不变。

---

## 1. 探索背景

### B1 目标

引入缓存层：Caffeine（一级本地）+ Redis（二级分布式），并实现缓存击穿 / 雪崩 / 穿透防护。

### 约束变化（相对 A3）

| 约束 | A3 阶段 | B1 阶段 |
|---|---|---|
| 引入新依赖 | ❌ 禁止 | ✅ **解除** |
| 改 `pom.xml` | ❌ 禁止 | ✅ **解除** |
| 改业务代码 | ❌ 禁止 | ✅ **解除** |

B1 就是来改这三条的。

### 延续的约束

- **feature 分支 + PR 流程**（`agent.md` 规则 4 / 4.1）
- `scripts/concurrency/` 作为**回归验证工具**
- `a3` profile（连 VM 库）
- TD 登记习惯

### 环境现状

Redis 已就绪：`192.168.229.129:6379`，redis-server **8.0.5**，`requirepass 123456`，`bind 0.0.0.0 -::1`，配置 `/etc/redis/redis.conf`，原版备份 `/etc/redis/redis.conf.a3bak`。详见 `docs/agent/environment.md`。

---

## 2. 可缓存接口清单（8 条）

> **注解语义**：这里的「可缓存」是**方法级**判断——Spring 的 `@Cacheable` 挂在 **Service 方法**上，与 HTTP 方法是 POST 无关。POST 只是传输语义，缓存的是**方法返回值**。故本表列了多个 POST 接口，不代表「按 HTTP 方法缓」。

| 接口 | HTTP | 读/写特征 | 键设计（见 §10 版本前缀） | TTL 建议 | 失效策略 |
|---|---|---|---|---|---|
| `/mysqlGoods/goodstype` | **GET** | 类型字典，几乎不变。A3 负载中 S1 占 **50%**（`L:1279-1280`）、S4b 占 **2/20 槽**（`L:1532`+`L:1549`） | `v1:goodstype:all` | 长（30–60min） | POST 新增时 evict 该键 |
| `/mysqlGoods/goodsSelectHome` | POST | 首页商品全量 + 商家名。读多，无参 | `v1:goods:home` | 短（1–5min） | goodsAdd / goodsUp / goodsDe 全清 |
| `/mysqlGoods/goodsSelect` | POST | 商家商品列表（`G:166-167`，`WHERE MerchantId = ?`），读多写少 | `v1:goods:m:{MerchantId}` | 中（5–10min） | 按 MerchantId 清 |
| `/mysqlGoods/productCodePrice` | POST | 单点价格，结果稳定 | `v1:goods:price:{MerchantId}:{GoodName}:{ProductCode}` | 中 | goodsUp / goodsDe |
| `/mysqlGoods/GoodsProductCodeSelect` | POST | 型号列表（`G:343`），读 | `v1:goods:codes:{MerchantId}:{GoodName}` | 中 | goodsAdd / goodsUp / goodsDe |
| `/message/userSelect` | POST | 用户公开信息（`M:163`） | `v1:user:pub:{userId}` | 中 | 无对应写接口（见 §11 风险表） |
| `/message/friendSelect` | POST | 好友列表，per-user（`M:309-311`） | `v1:friend:list:{customerId}` | 短 | friendAdd / friendUp / friendDe |
| `/message/selectFriend` | POST | 待处理好友申请，per-user（`M:238-240`） | `v1:friend:req:{friendId}` | 短 | friendAdd / friendUp / friendDe |

**per-user 键的由来**：这些接口的结果**随传入 ID 变化**（`MerchantId` / `customerId` / `friendId` / `userId`），属于「用户态参数」。键必须含该 ID，**不能用一个全局键**——否则 A 用户看到 B 用户的数据。键用**业务标识**，不用行号、不用自增主键。

---

## 3. 明确不可缓存清单

| 接口 / 范围 | 类别 | 不缓存的理由 |
|---|---|---|
| **`/mysqlLogin/getUserInfo`** | **认证接口** | 虽然键不含密码，但它是认证接口——**缓存命中后返回的可能是旧用户信息**（密码改了、头像改了都滞后）。**认证接口的缓存风险 > 收益。安全判断不是性能判断。** |
| `/mysqlLogin/loginSelect` | 认证接口 | 键**天然包含明文密码**（`U:38-41`）。缓存它 = 把口令当缓存键长期驻留，Redis 里 `KEYS *` 可直接 dump 出来。**建议永不缓存** |
| `/mysqlGoods/productSearch` | 注入面 + 键空间无界 | `G:365` 把关键词**逐字符**拼进 SQL（`for (String s : keys)`），键空间无界 → 缓存等于给穿透攻击开一个不限长的靶子 |
| `orderPayIfUp` / `orderReceiveIfUp` / `orderDeliveryIfUp` | 写（CAS） | 条件 UPDATE 实现的 CAS，是**全项目唯一正确的并发控制**。缓存会摧毁其语义 |
| `orderAdd` / `goodsAdd` / `goodsUp` / `goodsDe` / `loginAdd` / `friendAdd` / `friendUp` / `friendDe` / `send` | 写 | 写接口不进缓存；它们只做 **evict** |
| `/message/history` | **写**（伪装成读） | `M:88` 每次请求 upsert `user_conversation_read.last_read_time = NOW()` —— **名为查询，实为写**。缓存会让已读时间永远不更新 |
| 4 个 order 列表查询 | 高频变化 | 订单状态流转频繁；且缓存会破坏 A3 性能基线的可比性（见 §6） |

---

## 4. N+1 位置清单（4 处）

**实测 4 处**，每处均回到源码确认循环结构：

| 位置 | 触发条件 | 复杂度 | 缓存是否可解 |
|---|---|---|---|
| `goodsSelectHome` 全量查 `G:184-185` → 循环 `G:188-208` → 内层查询 `G:194-200` | 每次调该接口 | 1 + **N**（N = 商品数） | ❌ |
| `conversations` 外层查 `M:33` → 循环 `M:35-66` → 内层查询 `M:48-50` | 每次调该接口 | 1 + **N**（N = 会话数） | ❌ |
| `selectFriend` 外层查 `M:238-240` → 循环 `M:242-248` → 调用 `getUserInfoById` `M:244` | 每次调该接口 | 1 + **2N** | ❌ |
| `friendSelect` 外层查 `M:309-311` → 循环 `M:313-319` → 调用 `getUserInfoById` `M:315` | 每次调该接口 | 1 + **2N** | ❌ |

**复杂度修正**：`getUserInfoById`（定义 `M:330-369`）**自身含 2 条 SQL** —— `M:331-333` 查 `user`，再按用户类型走 `M:349-350`（`customerimage`）或 `M:353-354`（`merchantimage`）查头像。故 `selectFriend` / `friendSelect` 每轮循环产生 **2** 条查询，真实复杂度是 **1 + 2N**，不是 1 + N。

（对照：`getOrCreateConversation` 也调 `getUserInfoById`，但**只调 1 次**，是常数 3 条查询，**不构成 N+1**。）

**结论：缓存对 N+1 基本无效。** N+1 的本质是「每行一次查询」，只有当多行**共享同一 ID** 时缓存才有命中；ID 全不同时缓存零命中、反而多一层开销。**解 N+1 只能改 SQL（JOIN / IN 批量）。**

### 登记去向：两处 N+1 清单的关系

`project-map.md §6` 与 `cache-map.md §6` **职责不同，但有 3 行重叠**：

| | `project-map.md §6` | `cache-map.md §6` |
|---|---|---|
| 标题 | N+1 待优化清单（优先级：高） | 引入后的可收益点（建议，非现状） |
| 服务对象 | `fix-n-plus-one`（SQL 改写） | B1 缓存选型 |
| 视角 | **只收 N+1** | 收「缓存有收益的接口」 |
| 独有行 | `selectFriend` | `goodstype`（字典，非 N+1）、`goodsSelect`（读多写少，非 N+1） |
| 重叠行 | `goodsSelectHome`、`conversations`、`friendSelect` | 同左 |

**结论：不是同一份数据的双份拷贝，是两个不同职责的清单**；但重叠的 3 行是**同一事实的两份副本**——且**已经漂移过一次**（`selectFriend` 长期只在 `project-map.md §6` 里，`cache-map.md §6` 从未登记）。

故本轮**两处都修**（见 §5），并在 `cache-map.md §6` 尾部加了指向 `project-map.md §6` 的交叉引用。

> **留给你裁决的进一步收敛**：若要让「N+1 的坐标」只剩一个事实源，可把 `cache-map.md §6` 的重叠 3 行**改为引用**而非复制。本轮**未做**——它改变该节性质，超出「补漏 + 修坐标」的范围。

---

## 5. cache-map.md 现有方案评估

| 部分 | 处置 | 依据 |
|---|---|---|
| §1–§5（现状实测） | **保留** | 独立复核成立：`pom.xml` `<dependencies>` 实测 7 项，**无任何缓存 / Redis 依赖**；3 个 Controller 全文无 `@EnableCaching` / `@Cacheable` / `CacheManager` / `RedisTemplate`；`application.properties` 无 `spring.cache.*` / `spring.data.redis.*` |
| §6（引入后的可收益点） | **部分重做** | ① 补漏 `selectFriend`；② 补 `getUserInfoById` 的 1+2N 性质；③ 修 3 处**真错误**坐标（见下）。**注意：另有一批「坐标」实为约定差异，不是错误，不改** |
| §7（若要引入缓存） | **重做** | 原文「先做 Caffeine 本地缓存，**后续多实例才上 Redis**」——**与 B1 的两级缓存目标直接冲突**。且其假设「要支持多实例才需要 Redis」，但 VM 上 Redis 已装好、B1 就是要用它。原文「最小改动路径」对本阶段不适用 |

### §6 修正明细

**真错误（改了）**：

| 项 | 原文 | 实测 |
|---|---|---|
| `goodstype` 坐标 | `G:31` | **`G:37-38`** —— `G:31` 是方法签名 `public Object goodstype(`，查询在方法体内 |
| `goodsSelectHome` 内层坐标 | 内层查询 `G:188` | **`G:194-200`** —— `G:188` 是 `for` 循环头，不是查询 |
| `goodsSelectHome` 范围 | `G:182-202` | **`G:182-208`** —— 循环体在 `G:208` 闭合 |
| `goodsSelect` 坐标 | `G:160` | **`G:166-167`** —— `G:160` 是 `@PostMapping` 注解行 |
| `selectFriend` | **未登记** | **新增**：外层查 `M:238-240`，循环 `M:242-248`，调用 `M:244`，复杂度 1 + 2N |
| `getUserInfoById` | 未说明其双查询性质 | **新增**：`M:331-333` 查 user + `M:349-350`/`M:353-354` 查 image，故调用方是 1 + 2N |

**约定差异（未改，说明清楚）**：

`cache-map.md` 原文的 `M:48-50`，以及 `project-map.md §6` 的 `M:331` / `M:349` / `M:353`，指的是 **SQL 定义行**；它们**没有错**。本文档头声明的约定（「定义行 → 执行行」区间）以 `project-map.md §6` 为准，两处现已一致。

> **本轮同时修了 `project-map.md §6`**：与 `cache-map.md §6` 职责不同（见 §4），故两处都维护。`project-map.md §6` 只有 `goodsSelectHome` 一行坐标是真错误（`G:182-202` → `G:182-208`，内层 `G:188` → `G:194-200`）；其余行与 `M:331`/`M:349`/`M:353` 均正确，未动。

### §7 重做方向

不再走「单实例 Caffeine → 多实例才 Redis」的渐进路径，改为**一次性两级缓存**（见 §8）。

---

## 6. A3 断言影响清单

`ConcurrencyRunner.java` 实测共 **24 条断言**，分属 7 个场景（s1 / s2a / s2b / s3 / s4a / s4b / b2）。**先明确脚本实际打了哪些端点**（`grep` 路径字面量实测）：

```
orderAdd 6 | orderDeliveryIfUp 5 | orderReceiveIfUp 4 | goodstype 4
orderPayIfUp 3 | loginSelect 1 | loginAdd 1 | friendAdd 1
```

**其中只有 `goodstype` 与 `loginSelect` 是「读」**，其余 6 个全是写。这决定了缓存的影响面仅落在 S1 / S4b（含 goodstype）与 B-2（写 goodstype）。

| 断言 | 描述（脚本原文摘要） | 受影响 | 说明 |
|---|---|---|---|
| **`A-S1-1`** | HTTP 5xx == 0 且连接错误 == 0 | ⚠️ **高风险** | 加 Redis 引入新故障点。Redis 不可达时若抛异常 → 5xx > 0 → **断言直接挂** |
| **`A-S1-2`** | goodstype 响应全部 `success:true` | ⚠️ 是 | 若加 `@Cacheable`，返回的是缓存对象。**必须确认序列化后形状仍是 `{success:true, list:[...]}`** |
| **`A-S1-3`** | `loginSelect('123','123456')` 每次返回 1 | ⚠️ 是（若缓存它） | 逻辑上 miss→DB、hit→缓存，结果不变；但键含明文密码，**建议不缓存**（§3） |
| `A-S2a-1` / `A-S2a-2` | 同 OrderId 20 并发下单 → `count >= 2` / 行数 == 成功响应数 | ❌ 否 | `orderAdd` 是写，不缓存 |
| `A-S2b-1` / `A-S2b-2` | 唯一 OrderId 下单 → 成功率 100% / `count == 成功数` | ❌ 否 | 同上 |
| `A-S3-1` … `A-S3-4` | CAS 付款：每单恰好 1 个 success / 其余 msg 正确 / 无「数据库错误」/ JDBC 核对 PayIf=1 | ❌ 否 | CAS 端点不缓存；`A-S3-4` 走 JDBC 直连，绕开缓存 |
| `A-S4a-1` … `A-S4a-8` | 发货 / 收货 CAS 状态机 + 命名空间不变式 | ❌ 否 | 同上 |
| **`A-S4b-1`** | 5xx == 0，连接错误 == 0 | ⚠️ **高风险** | 同 `A-S1-1` |
| **`A-S4b-2`** | 各端点 success 数 == 分区计算出的期望值 | ⚠️ 是 | goodstype 占 **2/20 槽**（`slot >= 18`，`L:1532` + `L:1549`）。缓存若导致其返回非 `success:true` → `gGs != gsWant` → 挂 |
| `A-S4b-3` / `A-S4b-4` | 命名空间不变式 / JDBC 核对下单数 | ❌ 否 | JDBC 直连 |
| **`A-B2-1`** | B-2 三处 TOCTOU 逐轮复现 | 🔴 **最高风险** | 见 §0。**这是 A3 最值钱的产出，缓存把 5/5 变成 0/5 只需在错的地方加一个注解** |

**另有一条不易察觉的**：S4b 记录的 QPS 基线（本地报告实测 **63.0 / 96.2 QPS**）在加了读缓存后**不再可比**——goodstype 占其负载 10%，命中缓存后该部分耗时坍缩。**B1 之后若要与 A3 数字对比，必须先声明「缓存已启用」。**

---

## 7. 实现顺序

按**风险从低到高**排序：

1. **基础设施** —— `pom.xml` 加 `spring-boot-starter-cache` + `caffeine` + `spring-boot-starter-data-redis`；启动类加 `@EnableCaching`；`application-a3.properties` 配 `spring.data.redis.host` / `port` / `password`。
2. **`goodstype` GET** —— 先做它：**无参数、无用户态、写侧只有 1 个 POST**，失效面最小。**落地方式见 §0.1**（抽极薄 Service，`@Cacheable` 只覆盖 `G:37-38`）。**POST 的 `G:47-48` 查重一行都不许碰。**
3. **商品类**（`goodsSelectHome` / `goodsSelect`）—— 键开始带业务标识。
4. **per-user 类**（`friendSelect` / `selectFriend` / `userSelect`）—— 需处理好友关系变更的失效。
5. **order 列表类** —— 放最后。收益最低（变化最频繁）、风险最高（打乱 A3 性能基线）。

> N+1 不在本阶段范围内（B1 只管缓存，N+1 留给 `fix-n-plus-one`）。第 3 步**只加缓存，不改 SQL**。

---

## 8. 两级缓存的实现决策

**Spring 的 `@Cacheable` 不原生支持两级缓存**（一个 `CacheManager` 就是一层）。三个方案取舍：

| 方案 | 优点 | 缺点 | 后续改造影响 |
|---|---|---|---|
| A. 只做 Redis 单级 | 注解干净 | 无本地缓存，每次跨网络 | 小 |
| **B. 自写两级 CacheManager** | **性能**（本地命中不打网络） | 100–200 行基础设施 | **中** |
| C. 业务层手写两级 | 灵活 | 注解优势丢失，代码散落 | 大 |

### 采方案 B

**自写两级 `CacheManager` 包装（Caffeine → Redis），业务层仍用 `@Cacheable` 注解。**

理由：**一次性封装，后续所有改造不用碰业务代码。** C 不可取——后续改造（接口规范化、状态机改造等）要逐个手改业务代码，成本随改造次数线性增长；B 则把复杂度收敛在基础设施一处。

> 方案 B 与 §0.1 的 Service 抽取是同一件事的两半：Service 提供**注解落点**，两级 `CacheManager` 提供**注解背后的实现**。

### 工程取舍（2026-09-28 补）

> **【诚实定位】两级缓存在本项目规模下的实际收益是 5%（S4b：单级 Redis `66.0` → 两级 `69.6` QPS），代价是 423 行基础设施（`wc -l`：`TwoLevelCache` 233 + `TwoLevelCacheManager` 73 + `CacheConfig` 117；另 `application.properties` 配置段 13 行） + L1/L2 一致性窗口（60s）。这是「展示性设计」，不是「工程必需」。**工程最优解是单级 Redis（Spring Boot 自动装配，0 行）—— 在当前单实例、1 个缓存键的规模下完全够用。选两级是为了展示「L1+L2 + 降级 + evict 传播」的完整设计。若将来嫌复杂，撤 L1 只留 L2 的改动很小。

**依据**：S4b 有一组实测序列（同一脚本、同一后端、同一断言集、同为「全量档」中的 S4b 段，仅缓存状态不同；全部可在 `scripts/concurrency/reports/` 复核）：

| 缓存状态 | 报告 | S4b `achieved` | `wall` | 日期 |
|---|---|---|---|---|
| 无缓存 | `a3-report-7c58cc9f-20260920-214724` | **61.5** QPS | 117.0 s | 09-20 |
| 无缓存 | `a3-report-b1aec22d-20260920-215211` | **60.5** QPS | 118.9 s | 09-20（同晚、同配置） |
| 单级 Redis | `a3-report-506a0997-20260927-210332`（⚠️ 归属为推断，见下） | **66.0** QPS | 109.1 s | 09-27 |
| 两级 | `a3-report-128b0b45-20260928-141106` | **69.6** QPS | 103.4 s | 09-28 |

四轮均 `ok=7200 / 5xx=0 / gates 0 failed(23)`。**同配置的两次（60.5 与 61.5）差 1.6%，即本压测在「同状态」下的运行间噪声底 ≈ 2%。** 故单级→两级的 5.5%（66.0 → 69.6）**只比噪声底大一倍多**，方向确凿（单调，且与「L1 命中不打网络」的预期一致），但**不宜当作精确百分点引用**。

三条必须一起说的话：

1. **S4b 不是缓存的主场**：goodstype 只占其负载 **2/20 槽（10%）**，且该档绝对值被 **TD-14（`order` 无索引）** 主导 —— 缓存能触及的性能面就那么大。真正该看缓存收益的档是 S1（goodstype 占 50%），但 S1 在各轮都已打满 119.9/120，**天花板效应**使它同样给不出收益数字。
2. **同份数据在 CI 上实测 119.9 QPS**（CI #8）—— 这台机器上的 S4b 是**环境受限**的（压测器与 MySQL 跨机经 VM 网络），本就不适合做百分比级对比（`concurrency-test.md §实测数据` 已有此结论）。
3. **报告的元数据不记录缓存状态**：已实测比对两次不同代码状态的报告（09-27 与 09-28），除 `jdbcUrl` 与日期外**逐字段相同**。故 66.0 归属「单级 Redis」**不是报告中可取证的事实，而是时间线推断**：`TwoLevelCache.java` / `TwoLevelCacheManager.java` / `CacheConfig.java` 的落盘时间是 **09-28 13:51–14:24**，晚于该轮压测的 09-27 21:03；而 09-27 19:52 的 `dd5d3e1` 已引入 Redis + Caffeine 依赖。**→ 附带待办：A3 报告应增加一个「后端状态」字段（缓存开关 / commit），否则日后无法回溯任一轮的测量条件。**

> ⚠️ 早期叙述里的 **`63.0`（满负载档）与 `96.2`（长档）** 两个数出自 `archive/worklog-2026-09-20-a3.md` 与 `tech-debt.md TD-14`，**其原始报告不在 `reports/` 目录中，无法本地复核**，故本表不引用它们。引用这两个数时须注明「叙述记录，报告未留档」。

**结论**：收益方向确凿、量级为**个位数百分比**（单级→两级 ≈ 5%，无缓存→两级 ≈ 13%）；代价是约 400 行基础设施与一个一致性窗口。**这不是「当前规模下的最优解」，是一次刻意的完整设计演示。**

---

## 9. 穿透 / 击穿 / 雪崩 / 降级

| 问题 | 在哪层解 | 具体做法 | 本项目的目标对象 |
|---|---|---|---|
| **穿透** | 两级都要 | 空值缓存（null 也缓存短 TTL）+ 可选布隆过滤器 | `userSelect` / `friendSelect` 的任意 ID 查询——**零鉴权**下 ID 可被任意枚举 |
| **击穿** | 一级（Caffeine） | 热点 key 加互斥：Caffeine `LoadingCache` 天然单飞；Redis 侧用 `SETNX` 建锁 | `goodstype` 是全站最热的单 key（S1 负载 50%） |
| **雪崩** | TTL 策略 + 降级 | TTL 加随机抖动；**单机 VM Redis 无 HA**，只能靠抖动 + 降级兜 | 所有二级缓存的 key |
| **降级** | 全局 | **Redis 不可达必须回落 DB，绝不 5xx** | `A-S1-1` / `A-S4b-1` 的硬要求 |

> **降级不是优化项，是 B1 的硬约束**（见 §10 核心原则）。若不实现降级，`A-S1-1` / `A-S4b-1` 会把 Redis 的任何抖动传导为 HTTP 5xx。

### §9.1 本项目的落地状态（2026-09-28 核对）

上面那张表是**设计**，不是现状。逐项核对如下 —— **设计写了不等于生效**，本仓刚因「机制已写但从未被调用」吃过一次亏（见 §11「机制已写 ≠ 机制生效」）：

| 项 | 现状 | 依据 / 落地条件 |
|---|---|---|
| **击穿** | ✅ **已生效**（2026-09-28，`f693bdb`） | `@Cacheable(sync = true)` 是单飞的**开关**：不加它时 Spring 只走 `Cache#get(key)`，`TwoLevelCache.get(key, Callable)` 从不被调用。实测冷 L1 + 冷 L2 打 20 并发：修复前 **20 次 DB 查询**、修复后 **1 次**（`Redis GET` 增量 +20 → +1，L2 探测同样被合并）。**规则：本仓所有 `@Cacheable` 默认加 `sync = true`**，除非有理由不加 —— 约束是只允许单 `cacheNames`、不能与 `unless` 同用 |
| **穿透** | ⚠️ **机制未验证**（不是「已实现」） | L2 侧 `cache-null-values` 默认开、L1 侧用 `SimpleValueWrapper` 包 null —— 链条存在，但**当前无任何接口会返回 null**（`goodstype::all` 恒返回列表），故该路径从未被执行过。**落地时机**：`userSelect` / `friendSelect` 上缓存时（它们是零鉴权可枚举 ID 的最大受害面）。落地时必须：只对按 ID 查的读路径开负缓存、负 TTL 明显短于正 TTL（30–60s）、写路径**按 key** `@CacheEvict`（只 `allEntries` 会让「新建后读不到」的窗口拖到 TTL 到期） |
| **布隆过滤器** | ❌ **不采纳**（不是待办） | 本项目无键基数数据、缓存只有个位数 key，布隆的复杂度换不来收益。明确记为**不采纳**，避免它日后冒充欠债 |
| **雪崩（同时过期）** | ❌ **不实现 TTL 抖动**（触发条件写死） | L1 固定 60s、L2 固定 5m 全局同刻，但当前只有 **1 个键** → 纯理论问题。**触发条件：某缓存族的 key 数 > ~10**。届时方案：L1 用 Caffeine 自定义 `Expiry` 加 0–20% 随机；L2 的 `entryTtl` 是静态的，需换逐键 TTL 供应商 |
| **雪崩（Redis 整体不可达）** | ✅ **代价已测 + 冷却窗口已实现**（2026-09-28，`84cd200`） | 本项目真正会发生的雪崩是**这个**：Redis 一挂，全站读回落 DB。降级已保证 `5xx = 0`（`84cd200` 后 A3 复核：`A-S1-1` / `A-S4b-1` 均 `5xx=0 connErr=0`，报告 `a3-report-077eebf9`），但每次读仍要付一次「连不上」的代价。**代价已测，且两种故障差别很大**（均为本轮可复核的**首个冷读**，DB 本身约 2.5ms）：① **连接被拒**（端口关闭 = Redis 进程真挂了）→ **325ms**，之后亚毫秒级；② **SYN 被丢弃**（`192.0.2.1:6379`，TEST-NET）→ **3.40s**；③ **连得上但永不响应**（accept 后不回字节）→ **3.38s**。即**「秒级」只在网络路径黑洞时成立，真实故障（进程挂 = 端口关闭）是亚秒级**。**应对已实现**：冷却窗口 —— 窗口内所有 L2 操作直接跳过（零网络代价，只读一个 volatile），到期后由 CAS 选出的**唯一**一个请求探测一次（窗口 30s / 探测超时 200ms / `PROBE_INFLIGHT` 1s 互斥，启动期校验 1s > 200ms，违反即 WARN）。**黑洞端点实测**（7 样本，每样本 = 一次 `@CacheEvict` + 一次冷读）：首个冷读 3.376s → 之后 4~7ms、端点 accept 增量 **0**；窗口到期那一个样本 233ms（探测）、accept **+1**；探测失败即重设窗口，之后又回到 4ms。全程 `5xx=0`、`ERROR=0`、DB 查询数 = 请求数（确证回落 DB）。**探测代价**见下段 |

#### §9.1 附：探测 ≠ 主通道健康（设计盲区，不假装能识别）

探测通道与主工厂是**两条连接**（`L2ProbeConfig` 里探测通道是普通对象、**不是 bean** —— 若注册成 bean，会因 Boot 的 `@ConditionalOnMissingBean(RedisConnectionFactory.class)` 顶掉主工厂，整个 L2 静默跑在「200ms 超时、无连接池」的探测通道上，而 **A3 看不出来**：缓存照样能读能写）。故 PING 成功只证明「**端点**可达」，不证明「**主通道**可用」。反向（端点通、主通道坏 —— 例如口令只对探测通道正确，或主通道连接池耗尽）是本机制的**盲区，不假装能识别**；误判的后果有界且自纠正：清标记后真实操作若仍失败，会立刻 `markFailed` 重设窗口，最坏多付一次代价。

**探测代价（实测，`84cd200`）**：稳态 **233ms**（= 200ms 命令超时 + 开销）—— `L2HealthProbe` 注释与 `application.properties` 里写的「≤200ms」是**超时上限**，不是实测值；**进程内首次探测更贵**：启动自检实测 **489ms**（连接被拒）/ **612ms**（黑洞）/ **772ms**（SYN 被丢弃），差异来自 Lettuce 首建客户端与重试。故「每 cooldown 秒恰有一个请求多付 ≤200ms」应读作「**稳态约 0.23s，进程内首次约 0.5~0.8s**」。自检失败**不阻断启动**、且**不写进健康状态**（探测通道不通 ≠ 主通道不通，启动时没有任何证据说明主通道坏了）。

---

## 10. 演进影响分析

本节列出「未来可能的改造」与「对缓存的影响」，以便 B1 第一版就选对抽象层。

| 未来改造 | 对缓存影响 | B1 需提前做的 |
|---|---|---|
| 接口返回结构改 DTO | 序列化内容旧 | 键加版本前缀 |
| 写接口影响范围扩 | evict 范围要扩 | 写接口注释声明范围 |
| 限流 / 熔断 / 降级 / 重试 | 与缓存协同 | 降级第一天就做 |
| 状态机改造 | 写语义变多步 | 每个状态出口声明 evict |

### 核心原则

- 缓存注解**挂 Service 方法**，不散落业务代码
- 键用**业务标识**（`MerchantId` / `UserId`），不用行号、不用自增主键
- 键加**版本前缀**（`v1:...`），结构变了升版本
- 缓存 **DTO**，不缓存裸 `Map<String,Object>`
- 写接口的 **evict 范围写进注释**
- **降级不是优化项**，是 B1 的硬约束

### 关键澄清：读写与缓存的关系

- **读**走缓存（`@Cacheable`）
- **写**不走缓存（直接 DB）+ 写**必须清缓存**（`@CacheEvict`）
- **不是「只改读」，是「读走缓存、写清缓存」** —— 只加 `@Cacheable` 不加 `@CacheEvict`，得到的是永远不更新的脏缓存

> **「不缓存裸 `Map<String,Object>`」在本项目的现实约束**：现有 32 个接口**全部返回 `Map<String,Object>`**（见 `api-contract.md`：无统一响应体）。故本原则在 B1 **第一版无法立即满足**——需等接口规范化引入 DTO 后才成立。第一版的替代约束：**键加版本前缀 + 序列化后形状必须与原返回逐字段一致**（这与 `A-S1-2` 的断言直接相关）。

---

## 11. 已知风险

| 风险 | 说明 | 应对 |
|---|---|---|
| ~~RESP3 协议兼容~~ **（已关闭 2026-09-27）** | **已验证**：Lettuce **6.4.2** 与 Redis **8.0.5** 协商到 **RESP3**（`CLIENT INFO` 返回 `resp=3`），`SET`/`GET` 往返正常。§11 原登记的风险**当前不成立**——不是「有风险但未触发」，是**根本没走那条路**（原假设「Lettuce 默认可能走 RESP2」与实测相反） | 若将来升级 Lettuce 或 Redis，**需重验此条** |
| commons-pool2 缺失 → 池配置静默忽略 | Spring Boot 只在 classpath 有 commons-pool2 时才启用 Lettuce 池；否则 spring.data.redis.lettuce.pool.* 不报错、不生效。**已解决**（2026-09-27）：pom.xml 已加 commons-pool2:2.12.1，探针实测 maxTotal=8/maxIdle=4/minIdle=1 与配置逐项一致 | 若将来移除依赖，此坑复现 |
| **Redis 单点无 HA** | VM 上是单实例 Redis，无主从 / 哨兵 / 集群。它挂了二级缓存全失效 | 降级必须第一天就做（§9）；雪崩靠 TTL 抖动 |
| **降级缺失即 5xx** | 若 Redis 故障上抛异常，`A-S1-1` / `A-S4b-1` 立刻挂 | 降级是硬约束，不是优化项 |
| **缓存污染 A3 证据** | 缓存落在「先查后插」的查重查询上 → `A-B2-1` 假绿 | §0 硬约束 + §0.1 的结构保证 |
| ~~P6 与缓存冲突~~ **（已解决 2026-09-27）** | **原现象**：A3 的 P6 是「哨兵往返」—— 脚本用裸 JDBC 直写一行 `A3P-<runId8>`，再 `GET /mysqlGoods/goodstype` 读回。B1 给该端点加缓存后，读回的是**缓存填充时刻的旧列表**，哨兵行必然不在其中 → **结构性 FAIL，重跑多少次都一样**（不是偶发）。**根因**：P6 的隐含前提是「HTTP 读回来的内容 == 库里的内容」，缓存正是打破这个前提的东西 —— 所以**换探针接口是打地鼠**（改成 `goodsSelect` 只是把爆炸推迟到给它加缓存那天）。 | **新方案**：新增 `GET /health/db`（`@Profile("a3")`，**自身永不加 `@Cacheable`**），由后端报自己连的 `@@server_uuid`，脚本 JDBC 侧查同一个变量逐字段对比。**不依赖任何被缓存的数据，故与缓存永久正交**。P5 就绪探测一并搬过去（它同样被缓存静默削弱：命中时不碰 DB，就不再能证明 Hikari/MySQL 可用），P7 阴性对照改用 `server_uuid` 比对。A3 全量已复测通过（23/23 门禁、`reproRounds=5/5`）。详见 `concurrency-test.md §脚本内置校验 P0–P7` |
| ~~两级缓存尚未生效（方案 B 只落了一半）~~ **（已解决 2026-09-28）** | **原现象**：pom 里 Caffeine + Redis 并存，但 **Spring Boot 实测只装配 RedisCacheManager** —— `CacheType` 的枚举顺序里 `REDIS` 排在 `CAFFEINE` **之前**，Redis 胜出。故 §8 定的「Caffeine(L1) + Redis(L2)」实际是**单级 Redis**。**处置**：新增 `CacheConfig` 提供 `@Primary CacheManager`（`TwoLevelCacheManager` 组合 Caffeine L1 + Redis L2，见 §8）。**六项实测**：① `--debug` 自动装配报告显示 `CacheAutoConfiguration` 因 `@ConditionalOnMissingBean(types: CacheManager)` 失配而退回；② GET#2 起 Redis `commandstats` 计数**冻结**（L1 命中不打网络，单级 Redis 必增）；③ 重启后仅 L2 命中（`get` +1、`set` 不变、DB SQL 0，即**不回刷 L2 TTL**）；④ `evict` 两级同清；⑤ Redis 不可达时回落 DB 且 **5xx=0**，恢复时 `markL2Up` 记录一次状态翻转；⑥ A3 全量回归 `exitCode=0`、门禁 0 failed(23)、`A-B2-1` 仍 `EXPECTED_DEFECT_PRESENT reproRounds=5/5`。**遗留待观测**：`app.cache.caffeine.max-size=1000` 是**防御性上界**（无键基数依据，当前仅 1 个键），待 Micrometer 观测后调整 | 若将来新增 `spring.cache.redis.*` 属性，**必须**在 `CacheConfig.l2Configuration` 里同步映射，否则该属性被静默忽略（本项目已因 commons-pool2 缺失踩过一次同类坑） |
| **机制已写 ≠ 机制生效**（2026-09-28 新增的风险类型） | **实例**：`TwoLevelCache.get(key, Callable)` 完整实现了单飞（Caffeine 原子 load + 只在真正执行 loader 时才写 L2），但 `@Cacheable` 不加 `sync = true` 时 Spring 只走 `Cache#get(key)` —— 该方法**从不被调用**，击穿防护整整一轮是**死代码**；`3678719` 的 message 第 5 条还把它当已生效的设计写了（纠正见 `f693bdb`）。**同类未验证项**：穿透的空值缓存链（同样无接口触发，见 §9.1） | 判据不是「代码写了」，而是「**有入口在走 + 有能证伪它的实测数字**」。新增机制时一并给出触发它的入口与实测；凡「代码在、调用方不在」的机制一律标**未验证**，不标「已实现」。本仓同款前例：`recLoad()` 定义了却无人调用，S1/S2b/S4b 的均值与分位从未进报告（`concurrency-test.md` 记过） |
| **单飞是 per-JVM 的** | Caffeine 在进程内，`sync = true` 只在**单个 JVM 内**合并同 key 的并发未命中。多实例部署时 N 个实例最多产生 **N** 次 DB 加载，不是 1 次 | 分布式锁是唯一真解；本项目单实例，不划算，按已知限制登记。若将来多实例：先测「N 实例冷启时的 DB 峰值」再决定是否上分布式锁 |
| **性能基线不可比** | 加缓存后 S4b 的 QPS 与 A3 记录不可直接对比 | 对比时显式声明「缓存已启用」。**（2026-09-28 补）本行即 §8 工程取舍段的实测序列**：S4b 61.5（无缓存）→ 66.0（单级）→ 69.6（两级）；跨状态对比必须声明状态，且**报告元数据目前不记录缓存状态**（已实测比对，见 §8），引用旧报告时只能靠时间线推断 |
| **`userSelect` 的失效面** | `userSelect` 无对应写接口，但 `updateUserInfo`（`U:228`）可改 username / 头像 | 第一版给短 TTL；或让 `updateUserInfo` 一并 evict（需先确认其影响面） |
| **新增 Service 层** | 项目原为「无分层」架构，B1 首次引入 Service bean | 只抽**被缓存的读路径**所需的极薄 Service，不全面重构分层；`project-map.md §3` 需在实现后同步更新 |

---

## 相关 TD / BD

- 无新增。N+1 相关登记见 `project-map.md §6` 与 `cache-map.md §6`。
