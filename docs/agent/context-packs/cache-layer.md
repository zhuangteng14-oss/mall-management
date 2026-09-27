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

---

## 9. 穿透 / 击穿 / 雪崩 / 降级

| 问题 | 在哪层解 | 具体做法 | 本项目的目标对象 |
|---|---|---|---|
| **穿透** | 两级都要 | 空值缓存（null 也缓存短 TTL）+ 可选布隆过滤器 | `userSelect` / `friendSelect` 的任意 ID 查询——**零鉴权**下 ID 可被任意枚举 |
| **击穿** | 一级（Caffeine） | 热点 key 加互斥：Caffeine `LoadingCache` 天然单飞；Redis 侧用 `SETNX` 建锁 | `goodstype` 是全站最热的单 key（S1 负载 50%） |
| **雪崩** | TTL 策略 + 降级 | TTL 加随机抖动；**单机 VM Redis 无 HA**，只能靠抖动 + 降级兜 | 所有二级缓存的 key |
| **降级** | 全局 | **Redis 不可达必须回落 DB，绝不 5xx** | `A-S1-1` / `A-S4b-1` 的硬要求 |

> **降级不是优化项，是 B1 的硬约束**（见 §10 核心原则）。若不实现降级，`A-S1-1` / `A-S4b-1` 会把 Redis 的任何抖动传导为 HTTP 5xx。

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
| **Redis 单点无 HA** | VM 上是单实例 Redis，无主从 / 哨兵 / 集群。它挂了二级缓存全失效 | 降级必须第一天就做（§9）；雪崩靠 TTL 抖动 |
| **降级缺失即 5xx** | 若 Redis 故障上抛异常，`A-S1-1` / `A-S4b-1` 立刻挂 | 降级是硬约束，不是优化项 |
| **缓存污染 A3 证据** | 缓存落在「先查后插」的查重查询上 → `A-B2-1` 假绿 | §0 硬约束 + §0.1 的结构保证 |
| **性能基线不可比** | 加缓存后 S4b 的 QPS 与 A3 记录不可直接对比 | 对比时显式声明「缓存已启用」 |
| **`userSelect` 的失效面** | `userSelect` 无对应写接口，但 `updateUserInfo`（`U:228`）可改 username / 头像 | 第一版给短 TTL；或让 `updateUserInfo` 一并 evict（需先确认其影响面） |
| **新增 Service 层** | 项目原为「无分层」架构，B1 首次引入 Service bean | 只抽**被缓存的读路径**所需的极薄 Service，不全面重构分层；`project-map.md §3` 需在实现后同步更新 |

---

## 相关 TD / BD

- 无新增。N+1 相关登记见 `project-map.md §6` 与 `cache-map.md §6`。
