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

## 2. 可缓存接口清单（9 条）

> **注解语义**：这里的「可缓存」是**方法级**判断——Spring 的 `@Cacheable` 挂在 **Service 方法**上，与 HTTP 方法是 POST 无关。POST 只是传输语义，缓存的是**方法返回值**。故本表列了多个 POST 接口，不代表「按 HTTP 方法缓」。

| 接口 | HTTP | 读/写特征 | 键设计（见 §10 版本前缀） | TTL 建议 | 失效策略 |
|---|---|---|---|---|---|
| `/mysqlGoods/goodstype` | **GET** | 类型字典，几乎不变。A3 负载中 S1 占 **50%**（`L:1279-1280`）、S4b 占 **2/20 槽**（`L:1532`+`L:1549`） | `v1:goodstype:all` | 长（30–60min） | POST 新增时 evict 该键 |
| `/mysqlGoods/goodsSelectHome` | POST | 首页商品全量 + 商家名。读多，无参 | `v1:goods:home` | 短（1–5min） | goodsAdd / goodsUp / goodsDe 全清 |
| `/mysqlGoods/goodsSelect` | POST | 商家商品列表（`WHERE MerchantId = ?`），读多写少。✅ **已落地**（2026-09-28）：读路径 `GoodsCacheService.listByMerchant()`，单条 SQL **无 N+1**。⚠️ `MerchantId` 无索引（`db-schema.md §索引缺失` `G:160`）⇒ 库侧是**全表扫**，缓存是掩盖而非修复 | **`goodsByMerchant::<MerchantId>`**（`key="#MerchantId"`；§10 的 `v1:` 前缀仍未加） | 全局 L2 5m / L1 60s（`CacheConfig` 用 `cacheDefaults`，**无 per-cacheName TTL 配置** ⇒ 新增 cacheName 零配置） | **按 key 精确清**：`goodsAdd` / `goodsUp` / `goodsDe` 各挂 `@Caching`，**本 cacheName 那一条**是 `@CacheEvict(cacheNames="goodsByMerchant", key="#MerchantId")` —— 精度按 cacheName 分别声明，`goodsPrice` 落地后同一份 `@Caching` 里共 **4 条** evict（见下两行） |
| `/mysqlGoods/productCodePrice` | POST | 单点价格 + 图片（`SELECT Price, GoodImg`，**显式两列**，非 `SELECT *`）。✅ **已落地**（2026-09-28）：读路径 `GoodsCacheService.priceByTriple()`，单条 SQL、无 N+1。⚠️ 本仓**首个把失败当值缓存**的接口（`queryForMap` 0 行抛异常 → catch → `{success:false}` 是**正常返回的非 null Map** ⇒ 缓存到 TTL，见 §11）；⚠️ `MerchantId` 无索引（只有 `PRIMARY(Id)`）⇒ 库侧**全表扫**，缓存是掩盖而非修复 | **`goodsPrice::<MerchantId>::<GoodName>::<ProductCode>`**（**三维复合键**，`key="#MerchantId + '::' + #GoodName + '::' + #ProductCode"`；§10 的 `v1:` 前缀仍未加） | 全局 L2 5m / L1 60s（`CacheConfig` 用 `cacheDefaults`，**无 per-cacheName TTL 配置** ⇒ 新增 cacheName 零配置） | **整片清**：`goodsAdd` / `goodsUp` / `goodsDe` 各挂 `@Caching` 四条，本 cacheName 用 `allEntries=true` —— 键含 `GoodName`，而 `goodsUp` **会改 `GoodName`** 且它的 `WHERE` 是 `(MerchantId, ProductCode)` ⇒ 旧 `GoodName` 既不在入参也无处可查 ⇒ 旧键构造不出；且精确清会呈现「**条件性正确**」（未改名时对、改名时**静默**漏）。⚠️ **本行原写的失效面 `goodsUp / goodsDe` 漏了 `goodsAdd`，已订正** —— 它也改变**已存在**三元组的答案（0→1 行是失败变价格；1→2 行是价格变失败：`queryForMap` 遇 >1 行抛异常） |
| `/mysqlGoods/GoodsProductCodeSelect` | POST | 型号列表（`G:347-366`），读多写少。✅ **已落地**（2026-09-28）：读路径 `GoodsCacheService.listCodes()`，单条 SQL。⚠️ `MerchantId` 无索引 ⇒ 库侧是**全表扫 + 临时表**（`EXPLAIN`：`type=ALL`、`Using temporary`），缓存是掩盖而非修复；⚠️ SQL 是 `SELECT DISTINCT ProductCode` **无 `ORDER BY`** ⇒ **值序未定义**（实测连跑 6 次序稳定，但那是实测不是保证） | **`goodsCodes::<MerchantId>::<GoodName>`**（**二维复合键**，`key="#MerchantId + '::' + #GoodName"`；§10 的 `v1:` 前缀仍未加） | 全局 L2 5m / L1 60s（`CacheConfig` 用 `cacheDefaults`，**无 per-cacheName TTL 配置** ⇒ 新增 cacheName 零配置） | **整片清**：`goodsAdd` / `goodsUp` / `goodsDe` 各挂 `@Caching` 里属于本 cacheName 的那条是 `allEntries=true` —— 与 `goodsPrice` 同因（键含 `GoodName`，`goodsUp` 会改 `GoodName` 而其 `WHERE` 是 `(MerchantId, ProductCode)` ⇒ 旧键构造不出）。⚠️ 这三条 evict **先于本行的读接口落地**（2026-09-28 随 `goodsPrice` 那笔一并挂上），落地前每次写都在清一个空片，见 §11「先加 evict 再加读」 |
| `/message/userSelect` | POST | 用户公开信息（`M:163`） | `v1:user:pub:{userId}` | 中 | 无对应写接口（见 §11 风险表） |
| `/message/friendSelect` | POST | 好友列表，per-user（`M:309-311`） | `v1:friend:list:{customerId}` | 短 | friendAdd / friendUp / friendDe |
| `/message/selectFriend` | POST | 待处理好友申请，per-user（`M:238-240`） | `v1:friend:req:{friendId}` | 短 | friendAdd / friendUp / friendDe |
| `/message/conversations` | POST | 会话列表（`M:25-32`），per-user（`currentId`），读多写少 | `v1:conv:list:{currentId}` | 短（1–5min） | `send`（新消息）+ `history`（**写** `last_read_time`）。按 key 清：`history` 只改一对会话的已读时间，全清会让其他用户的会话列表无谓失效 |

> **落地后的键形态**：上表「键设计」列是**设计建议**（含 §10 的版本前缀）；实际落地为 `<cacheName>::<业务键段>` —— cacheName 段由 `@Cacheable(cacheNames=…)` 给出，业务键段是 SpEL 求值后 **key 对象的 `toString()`**（`RedisCache`，键序列化器为 StringRedisSerializer；实证 = Redis 里是干净文本键，而 value 是 JDK 字节流）。⚠️ **业务键段自身可含多个 `::`**（三维键即是）⇒ 原措辞「**两段**拼出」已不准确，改为「cacheName 段 + 业务键段」。已落地的五个：`goodstype::all`（`f693bdb` 之前）、`goodsHome::all`、`goodsByMerchant::<MerchantId>`、`goodsPrice::<MerchantId>::<GoodName>::<ProductCode>`、`goodsCodes::<MerchantId>::<GoodName>`（后四个均为 2026-09-28）——**当前均未加 `v1:` 前缀**，前缀仍是建议值。加它的代价见 §11「形状耦合」：形状变更时必须记得 bump，否则新旧条目会在同一片键里混住。
>
> **命名规则（2026-09-28 定）**：`cacheName` = **表名 + 投影名（camelCase）**，无投影时用表名 —— `goodstype` / `goodsHome` / `goodsByMerchant` / `goodsPrice` / `goodsCodes`（**五个全部已落地**，后四个为 2026-09-28）。收益：同族的键仍能被 `KEYS goods*` 一眼看全，而失效精度可以**逐投影**不同。**复合键的两条落地实例（`goodsPrice` 三维、`goodsCodes` 二维）已抽出可复用的「复合键模板」——key 规则 / evict 规则 / 边界三条，见 §10。****同一张 `goods` 表分裂成多个 cacheName 是有意的**：`goodsHome` 是全局单键（只能整片清，allEntries 在那里零精度损失），`goodsByMerchant` 是按键数=商家数的键空间（可精确清，allEntries 在那里**不是**零精度损失）；合并后一片键空间无法只对其中一个投影精确清。**「投影名」段的第二个实例（2026-09-28）**：`goodsPrice`（单点 `{price, goodImg}`）与 `goodsCodes`（列表 `List<String>`）也**不能**合并 —— 同一片键空间里混住两种**值形状**，`@Cacheable` 会按谁先命中返回谁，触发类型混淆（`Cache#get(key, type)` 路径直接 `IllegalStateException`）。合并（B）与 evict 单入口（D）两条待办及其触发条件见 §11。
>
> **本节与 §7 的关系**：本表是「哪些接口可缓存」的完整候选；§7 是**落地顺序**（风险从低到高）。本节新增行不等于「纳入本轮」。 

**per-user 键的由来**：这些接口的结果**随传入 ID 变化**（`MerchantId` / `customerId` / `friendId` / `userId`），属于「用户态参数」。键必须含该 ID，**不能用一个全局键**——否则 A 用户看到 B 用户的数据。键用**业务标识**，不用行号、不用自增主键。

---

## 3. 明确不可缓存清单

| 接口 / 范围 | 类别 | 不缓存的理由 |
|---|---|---|
| **`/mysqlLogin/getUserInfo`** | **认证接口** | 虽然键不含密码，但它是认证接口——**缓存命中后返回的可能是旧用户信息**（密码改了、头像改了都滞后）。**认证接口的缓存风险 > 收益。安全判断不是性能判断。** |
| `/mysqlLogin/loginSelect` | 认证接口 | 键**天然包含明文密码**（`U:38-41`）。缓存它 = 把口令当缓存键长期驻留，Redis 里 `KEYS *` 可直接 dump 出来。**建议永不缓存** |
| `/mysqlGoods/productSearch` | 注入面 + 键空间无界 | `G:365` 把关键词**逐字符**拼进 SQL（`for (String s : keys)`），键空间无界 → 缓存等于给穿透攻击开一个不限长的靶子 |
| **`/message/getOrCreateConversation`** | **键空间 + 体积双无界**（读接口，但不宜缓存） | ⚠️ 先澄清一个易误判点：名字叫 getOrCreate，**实测是纯读** —— `M:121-155` 内**没有任何 INSERT**（只有 1 次 `getUserInfoById`（2 条 SQL）+ 1 条 message 查询），故它**不是**「伪读实写」，与 `history` 不同类。它不上缓存的理由在键与体积：①**键含 4 个参数**（`currentId`/`currentType`/`otherId`/`otherType`，即「用户对」）⇒ 键空间 ~O(N²)；②正文是**全量历史消息**（无分页）⇒ 缓存体随 `send` 单调增长、无上界；③失效面 = 每个活跃用户对，`send` 要精确清对应键。第一版的收益不抵这三项复杂度 |
| `orderPayIfUp` / `orderReceiveIfUp` / `orderDeliveryIfUp` | 写（CAS） | 条件 UPDATE 实现的 CAS，是**全项目唯一正确的并发控制**。缓存会摧毁其语义 |
| `orderAdd` / `goodsAdd` / `goodsUp` / `goodsDe` / `loginAdd` / `friendAdd` / `friendUp` / `friendDe` / `send` | 写 | 写接口不进缓存；它们只做 **evict** |
| **`/mysqlLogin/updateAvatar`** / **`/mysqlLogin/updateUserInfo`** | 写（**同时是 `userSelect` 失效面的判断依据**） | 写接口不进缓存。它们被单独列出是因为 §11 有一条风险行曾断言「`updateUserInfo` 可改 username / 头像」，**实测不成立**：`updateAvatar` 只写 `customerimage`（`U:210`）/ `merchantimage`（`U:212`）两张**别的表**；`updateUserInfo` 的 UPDATE 只写 `email`/`address`/`selfintroduction`（`U:237`）。与 `userSelect` 被缓存的 5 列（`customerid`/`merchantid`/`username`/`usertype`/`gender`，`M:161`）**无交集** ⇒ `userSelect` 上缓存时**不需要**为这两个写接口挂 evict（详见 §11 该行） |
| `/message/history` | **写**（伪装成读） | `M:88` 每次请求 upsert `user_conversation_read.last_read_time = NOW()` —— **名为查询，实为写**。缓存会让已读时间永远不更新 |
| 4 个 order 列表查询：`orderSelect`（`G:412`）/ `orderDeliveryIfSelect`（`G:432`）/ `orderDeliverySelect`（`G:450`）/ `historicalOrdersSelect`（`G:478`） | 高频变化 | 订单状态流转频繁（三个 `*IfUp` 是条件 UPDATE 的 CAS，见上一行）；且缓存会破坏 A3 性能基线的可比性（见 §6） |

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
3. **商品类** —— 键开始带业务标识。`goodsSelectHome` ✅ **已落地**（2026-09-28：`goodsHome::all`（无参单键）+ `sync = true`；失效面 = `goodsAdd`/`goodsUp`/`goodsDe` 三个写接口各挂 evict；读路径搬到 `GoodsCacheService.listHome()`）；`goodsSelect` ✅ **已落地**（2026-09-28：`goodsByMerchant::<MerchantId>`（`key="#MerchantId"`）+ `sync = true`；同样那三个写接口**按 key 精确清**（`@Caching` 里属于本 cacheName 的那条是 `goodsHome` 全清 + `goodsByMerchant` 精确；`goodsPrice` 落地后整份 `@Caching` 共 4 条）；读路径搬到 `GoodsCacheService.listByMerchant()`。⚠️ 该表 `MerchantId` 无索引，库侧是全表扫）；`productCodePrice` ✅ **已落地**（2026-09-28：**首个三维复合键** `goodsPrice::<MerchantId>::<GoodName>::<ProductCode>`（`key="#MerchantId + '::' + #GoodName + '::' + #ProductCode"`）+ `sync = true`；读路径搬到 `GoodsCacheService.priceByTriple()`；失效面 = 同样三个写接口，本 cacheName 用 **`allEntries`**。⚠️ 它还是**首个把失败当值缓存**的接口，见 §11）；`GoodsProductCodeSelect` ✅ **已落地**（2026-09-28：**二维复合键** `goodsCodes::<MerchantId>::<GoodName>`（`key="#MerchantId + '::' + #GoodName"`）+ `sync = true`；读路径搬到 `GoodsCacheService.listCodes()`；失效面 = 同样三个写接口，本 cacheName 用 **`allEntries`**（那三条 evict 此前已随 `goodsPrice` 一笔挂上，本笔只是让它们有了实际键）。⚠️ 值序未定义、单列取值耦合，见 §2 行与 §11）—— ⚠️ 「键含 `GoodName` 的投影只能用 allEntries」这条**已从预言核实为事实**，依据是 **`goodsUp` 的 `WHERE` 不含 `GoodName`**（`UPDATE … SET GoodName=?, Price=?[, GoodImg=?] WHERE MerchantId=? AND ProductCode=?`，方法体内**无任何 SELECT**）⇒ 旧 `GoodName` 既不在入参、也不可查 ⇒ 旧键 `M::旧G` 构造不出；且精确清会呈现「**条件性正确**」（未改名时对、改名时静默漏）。见 §11「精确 evict 的边界」。
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
| **穿透** | ⚠️ **机制未验证**（不是「已实现」） | L2 侧 `cache-null-values` 默认开、L1 侧用 `SimpleValueWrapper` 包 null —— 链条存在，但**当前无任何接口会返回 null**（`goodstype::all` 恒返回列表），故该路径从未被执行过。**（2026-09-28 补）首个「按 ID 入参 + 零鉴权 + 已上缓存」的接口已经出现** —— `goodsSelect`（`goodsByMerchant::<MerchantId>`），但它**从不返回 null**、0 行是 `success:true, list: []` ⇒ **仍不触发本行的路径**；它的键空间问题另见 §11「`goodsSelect` / `productCodePrice` 的键空间随入参无界」。**（2026-09-28 再补）`productCodePrice` 是第二个，它制造了一个容易与本节混淆的新状态**：0 行时 `queryForMap` 抛异常、被 catch 吞成 `{success:false}` —— 也**不是 null** ⇒ **本行的空值缓存路径仍未被走到**；但那个失败结果**会被当真值缓存到 TTL**（本仓首例，见 §11「失败结果被缓存」）。两件事必须分开记：**穿透的防护手段是「缓存 null」**，与「缓存了一个表示失败的非 null 值」**不是同一机制**，`KEYS`/`TTL` 上看不出区别，只有读值形状能分辨。**（2026-09-28 三补）`goodsCodes`（`goodsCodes::<M>::<G>`）与 `goodsSelect` 同形**：0 行是 `list: []`、不返回 null ⇒ 同样不触发本行路径。**落地时机**：`userSelect` / `friendSelect` 上缓存时（它们是零鉴权可枚举 ID 的最大受害面）。落地时必须：只对按 ID 查的读路径开负缓存、负 TTL 明显短于正 TTL（30–60s）、写路径**按 key** `@CacheEvict`（只 `allEntries` 会让「新建后读不到」的窗口拖到 TTL 到期） |
| **布隆过滤器** | ❌ **不采纳**（不是待办） | 本项目无键基数数据、缓存只有个位数 key，布隆的复杂度换不来收益。明确记为**不采纳**，避免它日后冒充欠债 |
| **雪崩（同时过期）** | ❌ **不实现 TTL 抖动** —— 但**触发条件本轮已从「纯理论」变成「结构上已就位」**（2026-09-28 更新） | 机制本身没变：L1 固定 60s、L2 固定 5m，全局同刻。变的是**键空间的形状**：`goodsByMerchant`（一维）与 `goodsPrice`（三维）的键**随入参无界**（零鉴权、入参可控，§11），且这两族的失效是**整片 `allEntries` 清**（§2）⇒「写一次 → 整片清 → 紧接着的读集中回填 → 同一固定 TTL 同刻到期」这条路径**结构上已经走得通**，不再需要「恰好很多键自然存在」这个前提。**但仍是未触发状态**：实测键数 **5 个三元组 + 4 个型号组**（个位数，同一批次回填的全部在同刻到期也只是 ≈10 次 DB 查询，DB 单查约 2.5ms）⇒ 收益为 0，**本轮不做**。**触发条件（与 §11 附 E 行同一判据）：某缓存族的 key 数 > ~10**。届时方案：L1 用 Caffeine 自定义 `Expiry` 加 0–20% 随机；L2 的 `entryTtl` 是静态的，需换逐键 TTL 供应商 |
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

### 复合键模板（2026-09-28 定，由 `goodsPrice` 三维 / `goodsCodes` 二维两个实例抽出）

> 有 **≥2 个实例**才有引用价值 —— 所以这条不写在某笔的提交信息里，写进本节。今后新增复合键读接口（`userSelect` / `friendSelect` 的 per-user 键、`order` 族）**照此三条执行**；与本节冲突的写法即为错。

**① key 规则**

1. **显式拼接**：`key = "#A + '::' + #B"`，**不要**用 SpEL 内联 list（`{#A,#B}`）。理由：L2 的键是 key 对象 **`toString()`** 后与 cacheName 拼成（`RedisCache` + `StringRedisSerializer`）⇒ list 形态落成 `goodsPrice::[a, b]`，既不可读，也**不更安全**。
2. **段序 = SQL `WHERE` 的顺序**（由粗到细）。目的：让键的可读序与 SQL 的书写序一致，读代码时不必二次映射。
3. **每个分量不得含 `::`** —— 这是拼接可解析的**唯一性条件**。`A::B::C` 存在多种拆法，但拆法数量受**内嵌 `::` 总数**约束：两个不同业务键要给出同一字符串，双方的内嵌分隔符数量必须相等 ⇒ 分量均不含 `::` 时解析唯一。实测本仓 `MerchantId` / `GoodName` / `ProductCode` 的**全部取值均不含 `::`**（`ProductCode` 长度 1–7、首尾无空白）。**已实测构造出歧义**：`(M, "222::IJ-555", "X")` 与 `(M, "222", "IJ-555::X")` 拼出同一键 ⇒ 第 2 次请求直接命中第 1 次的缓存（SQL=0）。**判定**：对真实数据不可达，且构造得出也只是零鉴权（BD-03）下本就能直接读的东西 ⇒ **不是新增能力，不修**。新增复合键时若分量可能含 `::`，须改用转义或长度前缀 —— 本仓现状不需要。
4. 分量只放**业务标识**（`MerchantId` / `GoodName` / `ProductCode`），不放行号、不放自增主键（同「核心原则」）。
5. **cacheName 段承担「哪个投影」，键段不重复**：`cacheName = 表名 + 投影名`，键保持裸业务标识 —— 与 `goodstype::all` / `goodsHome::all` 同一读法。

**② evict 规则**

1. **机械判据（决定精确还是整片）**：取键的**每一个**分量，问「有哪个写接口能改到它、且该接口的 `WHERE`/入参含它的**旧值**」；**只要有一个分量缺 ⇒ 该 cacheName 只能 `allEntries`**。实例：`goodsPrice` / `goodsCodes` 的键含 `GoodName`，而 `goodsUp` 会改 `GoodName`，其 `WHERE` 是 `(MerchantId, ProductCode)` 且方法体内**无 SELECT** ⇒ 旧 `GoodName` 无处可查 ⇒ 旧键构造不出 ⇒ 整片清。
2. **一致性优先于局部最优**：同一 cacheName 上**不能既精确又整片清**（`goodsDe` 单看是能精确清的，但 `goodsUp` 那条做不到）⇒ **该片的全部写接口取一致的 `allEntries`**，避免「哪条是哪种精度」的记忆负担。
3. **精确清只在闭合时可用**：写接口的写入集合 ⊆ 该键集合，且多清无害（实例：`goodsByMerchant` 的键 = `MerchantId`，三个写接口的写入集合都由 `MerchantId` 限定）。
4. **精度逐 cacheName 声明**，一份 `@Caching` 里可以混：`goodsHome` 全清 / `goodsByMerchant` 精确 / `goodsCodes`+`goodsPrice` 全清。**`@CacheEvict` 不是 `@Repeatable`**（且无 `CacheEvicts` 容器类）⇒ 混精度**只能**靠 `@Caching(evict={…})`。

**③ 必须一并登记的边界**（四条，均已在 §11 成行）

1. **MySQL 比较 ≠ 缓存键比较**：`char` + `utf8mb4_0900_ai_ci` 忽略尾空格与大小写，缓存键是**精确串比较** ⇒ 精确清会漏（`allEntries` 对此免疫）；且同一业务键可产生多个条目（`M::222` 与 `M::222 `）。
2. **键空间随入参无界**：零鉴权下分量可控 ⇒ 键数无上界。缓解 = L1 `max-size=1000` 硬上界 + L2 随 TTL 过期；触发条件（某族键数 > ~10）到了再考虑「只缓存已存在的键」。
3. **值序未定义**：`SELECT DISTINCT` 无 `ORDER BY`（`EXPLAIN` 见 `Using temporary`）⇒ 「缓存体与冷查逐字节相同」只是**实测稳定**，不是**保证相同**。验证脚本若依赖逐字节比较，须先确认该 SQL 有序；无业务含义就别加 `ORDER BY`（改 SQL 属另一主题）。
4. **形状耦合按投影形态分三种**（加列/改名时编译器**都不说话**）：`SELECT *` → 耦合**列集合**；显式列名 + `map.get("列名")` → 耦合**具体列名**（改名后**静默** `null`）；单列 + `queryForList(sql, Class)` → 耦合**被选中的那一列**（`SingleColumnRowMapper` 按列序号取值，改选列**不报错**、语义悄悄变）。

> 附：**失败/空结果是值**。0 行返回的**非 null** 结果会被正常缓存到 TTL（`{success:false}` / `list: []`），这与「空值缓存（负缓存）机制」**不是一回事**。新增复合键接口时先问：它的 0 行返回什么、是否会被缓存、谁能清掉它。本仓首例是 `productCodePrice`（见 §11）。

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
| **`userSelect` 的失效面** | **（2026-09-28 实测修正）** 原文写「`userSelect` 无对应写接口，但 `updateUserInfo`（`U:228`）可改 username / 头像」——**两条都不成立**：①`U:237` 的 UPDATE 只写 `email`/`address`/`selfintroduction`，**不改 username**；②头像根本不在 `user` 表（`updateAvatar` 写的是 `customerimage`（`U:210`）/`merchantimage`（`U:212`））。⇒ 与 `userSelect` 被缓存的 5 列（`customerid`/`merchantid`/`username`/`usertype`/`gender`，`M:161`）**无交集**，`userSelect` 上缓存时**不需要** evict | **唯一脏源是 `loginAdd`**（`user` 表只有 INSERT 新行 + 一条不含缓存列的 UPDATE）：新行能被**已缓存的键**命中，仅当客户端传入的 `id`/`customerid`/`merchantid` 与已存在值相同（`loginAdd` 的 `id` 是入参，客户端可控）。陈旧上界 = L2 TTL（5m）/ L1 TTL（60s），自愈。若要彻底关掉：给 `loginAdd` 挂 evict（代价 = 每次注册清一片，实施时再定 cacheName） |
| **`goodsHome` / `goodsSelect` 的形状耦合（`SELECT *`）** | 两者是**同一张表、同一条 `SELECT *`**（`GoodsCacheService.listHome()` 与 `listByMerchant()`）⇒ 缓存体 = 「`goods` 表**列全集**的快照」，列集合完全相同：给表加列后，**旧条目缺该列 → 响应字段缺失直到 TTL**（L2 5m / L1 60s）。**列类型已实证**（`CREATE TABLE goods`）：`Price` 是 **`double`，不是 DECIMAL** ⇒ 「12.50 / 12.5 / 12.500」这类**尾零精度**问题在类型层面不存在，只需验**类型不漂移**（往返后仍是 `Double`，不是 `BigDecimal`/`String`）；且该表**没有任何时间列** ⇒「时间列类型」在本表上**无对象可测**（不是跳过，是不存在 —— 本仓三张已缓存表 `goodstype`/`goods` 均无时间列）。**（2026-09-28 补）第三个投影 `goodsPrice` 不属本行范围**：它是**显式两列**（`SELECT Price, GoodImg`）⇒ **不耦合于列集合**（加列不影响响应），只耦合于**具体列名** —— 列**改名/删除**会让 `map.get("Price")` / `map.get("GoodImg")` 静默取不到 → 响应里 `price: null`，**编译期无感**。即本行需区分**三种**耦合：「耦合于**列集合**」（两个 `SELECT *` 投影）/「耦合于**具体列名**」（`goodsPrice`：显式列名 + `map.get("列名")`，列**改名/删除**后**静默**返回 `null`，编译期无感）/「耦合于**被选中的那一列**」（`goodsCodes`：单列 + `queryForList(sql, String.class)`，`SingleColumnRowMapper` 按**列序号 1** 取值 ⇒ 列**改名/删除**会让 **SQL 自身报错**（响亮，非静默），但把它**改选成另一列**（如 `SELECT DISTINCT GoodImg`）**不报错**、仍返回 `List<String>`，只是语义悄悄变了）。三种形态的共同点：**改表结构时编译器都不说话** | 沿用 §10 约定：**响应形状变更 ⇒ bump 键版本**。本轮**未加** `v1:` 前缀（与既有 `goodstype::all` 一致，见 §2 注），所以这一行是加前缀之前唯一的提醒 |
| **`goodsHome` 的体积与 N+1** | ①无分页 `SELECT *` ⇒ 缓存体随表增长（本轮实测 `STRLEN goodsHome::all`，见 commit）；②该接口是 1 + N 查询（§4 第 1 行）——缓存把它摊薄为「每 TTL 一次」，是**掩盖而非修复**；③冷启动/失效后的首个请求仍付 1 + N。**代码坐标已变**：读路径从 `MallManagementGoodsController.goodsSelectHome()`（原 `G:157-196`）搬到 `GoodsCacheService.listHome()`，§4 表里的 `G:` 坐标随之过期 | 体积/键基数上量后（表行数上千或缓存键 > ~10）再决定分页或放弃缓存该接口；N+1 仍归 `fix-n-plus-one`（B1 不改 SQL） |
| **精确 evict 的三条边界**（2026-09-28 新增，同日补第三条） | ① **MySQL 比较 ≠ 缓存键比较**：`MerchantId` 是 `char(20)` 且默认 collation（`utf8mb4_0900_ai_ci`）**忽略尾空格与大小写**，而缓存键是**字符串精确比较** ⇒ 写侧传 `"1 "` 能改到 `"1"` 的行，却只清 `goodsByMerchant::1 `、清不到读侧用的 `goodsByMerchant::1` ⇒ 陈旧到 TTL 自愈。这是**精确清相对 allEntries 的唯一新增风险**（allEntries 对这类输入免疫，代价是别家列表被顺带清）。② **`@CacheEvict` 不是 `@Repeatable`**（spring-context 6.2.6 实证：常量池无 `Repeatable`，且该包下**没有** `CacheEvicts` / `Cacheables` 容器类 —— `unzip -l spring-context-*.jar \| grep cache/annotation/` 可复核）⇒ 一个方法要挂**两条不同精度**的 evict **只有** `@Caching(evict={…})` 一条路；单个 `@CacheEvict(cacheNames={A,B})` 只能表达**同精度**的多命名空间。③ **「条件性正确」——最坏形态**（2026-09-28 新增，由 `productCodePrice` 的键含 `GoodName` 而暴露）：若某个 key 分量**只在部分情况下**能从写接口入参构造出来，精确清就会**在常见情况下正确、在例外情况下静默漏清**。实例：`goodsPrice` 的键 `M::G::P`，`goodsUp` 的入参含 `M`/`P`（**未改名**时 `G` 也在）⇒ 精确清**看起来完全正确**，只在**改名**时漏清旧键 `M::旧G::P`（该行的真答案已变成 `{success:false}`）—— 而测试通常不改名 ⇒ **测试全绿、线上错**。**判据（可机械执行）**：取键的**每一个**分量，问「有哪个写接口能改到它、且该接口的 `WHERE`/入参含它的**旧值**」；**只要有一个分量缺 ⇒ 该 cacheName 只能 `allEntries`**（残余的精确清不是折中，是陷阱） | 三条均**登记为已知边界，本轮不改语义**：① 接受 TTL 内陈旧；若要免疫则退回 allEntries。② 已写进三个写接口的注释（含上面的复核命令），免得后人以为能写两个 `@CacheEvict`。③ 已据此把 `goodsPrice` 与 `goodsCodes` **两个投影都**定为 `allEntries`（两个的键都含 `GoodName`，同一个原因）—— 判据本身已抽成 §10「复合键模板」的 evict 规则第 1 条，见 §2 表与代码注释 |
| **`goodsSelect` / `goodsCodes` / `productCodePrice` 的键空间随入参无界** | 三个接口都零鉴权（BD-03）、分量全是任意入参 ⇒ 任何人可造键。**一维**：`goodsByMerchant::<任意值>`；**二维**：`goodsCodes::<任意 M>::<任意 G>`（每个 `(M,G)` 只有 1 个键，但 `(M,G)` 本身任取 ⇒ 键数无上界）；**三维**：`goodsPrice::<任意 M>::<任意 G>::<任意 P>` —— **造键速率三维最高**（2 次请求即可产出 2 个互不相干的键，**不需要数据存在**）。实测键空间：5 个三元组 = 5 行、**0 个重复三元组**；4 组 `(MerchantId, GoodName)`（`k` 分别为 2/1/1/1）。**低危**：L1 有 `app.cache.caffeine.max-size=1000` 硬上界、L2 条目随 TTL（5m）过期，每条目是一个小列表/小 map —— 与 `productSearch` 的无界（拼 SQL + 键空间双无界，§3）**不同量级**。⬆️ **最容易被误判的一点**：`goodsSelect` 的 0 行是 `success:true, list: []`（空结果被正常缓存，**不是**「空值缓存（负缓存）机制」）；`goodsCodes` 同（无型号时也是 `list: []`）；`productCodePrice` 的 0 行是 `{success:false}`（**失败被正常缓存**，同上，也不是空值缓存）⇒ §9.1「穿透：机制未验证」的结论**不因这三个接口改变** | 登记；若键基数上量（> ~10，与 §9.1 雪崩的触发条件同源）再考虑「只缓存已存在的商家/三元组」或加负 TTL。判据：随机造 N 组入参后数 `KEYS goodsByMerchant*` / `KEYS goodsPrice*` |
| **新增 Service 层** | 项目原为「无分层」架构，B1 首次引入 Service bean | 只抽**被缓存的读路径**所需的极薄 Service，不全面重构分层（2026-09-28：已落地 `GoodsTypeCacheService`、`GoodsCacheService` 两个 —— 后者现含**三个**读方法 `listHome()` / `listByMerchant()` / `priceByTriple()`，写路径与 evict **仍留在 Controller**（现为 `@Caching` **四条**）；`project-map.md §3` 需在实现后同步更新，**单独一笔**） |
| **失败结果被缓存**（2026-09-28 新增，本仓首例） | `productCodePrice` 的 `queryForMap` 在 0 行时抛 `EmptyResultDataAccessException`（>1 行抛 `IncorrectResultSizeDataAccessException`），被方法内 `catch (Exception e)` 吞成 `{success:false}` —— 一个**正常返回的非 null Map** ⇒ **会被缓存到 TTL**（a3 为 5m）。即：**失败的生命周期从「瞬时」变成「TTL」**。⚠️ 与「空值缓存（负缓存）机制」**不是一回事**（缓存的是失败结果，不是 null） | 登记 + 接受。三条理由：① 常规解法 `unless` 被 `sync = true` **禁止**（spring-context 6.2.6 实证报文 `…sync=true operation does not support the unless attribute on '<method>'`），而放弃 `sync` 是**已实测的回归**（20 并发冷读 20 次 DB → 1 次，§9.1 击穿行）；② 唯一真实来源是前端用**未保存**的表单名查询（`GoodsUp.vue` 的 `switchPrice` 传 `editForm.goodName`），保存时即被 `goodsUp` 的 `allEntries` 清掉 ⇒ **自愈**；③ 0 行在本项目是**业务失败**，不是技术异常。**附**：重复三元组（`goods` 表无唯一约束、`goodsAdd` 不查重）会让本接口从「价格」变「失败」且被缓存到 TTL —— 实测当前 0 个重复三元组 ⇒ 该分支不可达，不修（建唯一索引属另一主题） |
| **「方案 X」被考虑过、不采纳**（2026-09-28 立项登记） | **方案 X** = 异常上移到 Controller 以绕过 `@Cacheable`：Service 不 catch → `EmptyResultDataAccessException` 上抛 → 缓存不写（`@Cacheable` 遇异常不缓存）→ Controller 层 catch 后返回 `{success:false}`。**它同时解决「不缓存失败」与「保留单飞」**，所以看起来对 | **不采纳，理由：语义错位** —— 把**业务失败**（0 行）包装成**技术异常**去骗过缓存。三层重整后业务失败仍应返回业务结果（技术异常才上抛），届时此方案**必须改回来**；且它会制造**族内不一致**（另三个 Service 都是「成功失败都返回 Map」，唯独它「失败抛异常」）。若未来重提此方向（包括未来的我），**先读本条** |
| **`DEL <key>` 只清 L2**（2026-09-28 新增的运维陷阱） | **L1 是进程内 Caffeine**，Redis 里没有它 ⇒ `DEL <key>` / `redis-cli` 的任何删除**只清 L2**，进程内那份仍在（60s）。实测现象：清缓存后**第一次读仍返回旧值且 `SQL=0`**，要等 L1 TTL 到期（或重启实例）才回落 DB。**与「机制已写 ≠ 机制生效」同族 —— 清了一半以为清干净了**，且它不会报错，只会给出「改了代码但行为没变」的假象 | 登记。**做缓存状态的验证时必须**：要么等 L1 TTL（本仓验证脚本统一用 65s），要么重启实例 —— **不要**用 `DEL` 之后的第一次读取当作「已冷」的证据。实测复现见 `productCodePrice` 那一笔的验证 d 步 |
| **evict 清单可能领先于读接口落地**（2026-09-28 新增，本仓的顺序） | 本仓是**先加 evict、再加读**：`goodsCodes` 的三条 `@CacheEvict(allEntries=true)` 在 2026-09-28 随 `goodsPrice` 一笔挂上，而它的读接口（`GoodsCacheService.listCodes()`）**下一笔才存在** ⇒ 中间那段时间里每次写都对一个**尚无实际键**的 cacheName 做清理：L2 侧**建出空命名空间 + 一次 no-op 清理**（`TwoLevelCacheManager.getCache()` 是 `computeIfAbsent` 惰性建；SCAN 命中 0 键）。**不是 bug** —— `allEntries` 对空片是幂等 no-op、代价近乎零 —— 但**顺序不常见**，会让人误以为「这个 cacheName 一定已经有读接口在用了」。**为什么本仓会这样**：`goodsPrice` 那一笔的裁决是「三个写接口的 `@Caching` 一次性扩到 4 条」，避免同一份注解改两遍；代价就是 evict 领先一拍 | 登记。判据/消解：读接口一落地即自然消解（本笔已消解）。**若将来再出现「先挂 evict」，必须能答出「它什么时候会有实际键」**；答不出就是漏了读接口 |

### §11 附：三项带触发条件的待办（2026-09-28 立项，本轮均**不做**）

| 待办 | 现状 | 触发条件 | 本轮不做的理由 |
|---|---|---|---|
| **B. 合并 goods 族 cacheName**（合成一个 `goods`） | `goodsHome`（全局单键，1 键）+ `goodsByMerchant`（按商家，键数 = 商家数）+ `goodsPrice`（三维，键数 = 三元组数）+ `goodsCodes`（二维，键数 = (商家,商品名) 组数） | goods 族读接口 **≥3 且全部是 allEntries 失效面**时（现为 **4 个**接口，但 `goodsByMerchant` 是**精确清** ⇒ **未满足**） | 本轮 `goodsSelect` 要的是**精确清**（§2 登记即「按 MerchantId 清」），合并后一片键空间**无法只对其中一个投影精确清** ⇒ 与 A′ 直接冲突。合并还需给键加投影前缀（否则 `'all'` 与 `MerchantId="all"` **撞键**，零鉴权下入参可控），代价 = 重改 `da616a6` 的键名，并使其键名类实测记录（`EXISTS goodsHome::all` / `STRLEN goodsHome::all`）在代码里再也搜不到、需注记 |
| **D. evict 收敛成 Service 单入口** | 四条 evict 分别挂在 3 个写接口上（共 **12 行**注解） | 写接口的 evict 清单 **≥4 条**，或**第二张表的读接口上缓存**时 ⇒ **⚠️ 本轮已满足**（`goodsPrice` 上线后从 3 条变 4 条） | ① 语义从「注解自动清」变「**显式调用清**」：漏写那一行调用是**静默**的（不报错、陈旧到 TTL 自愈），属本表「机制已写 ≠ 机制生效」同类；而注解挂在方法上时**提前 return 也会清**（`goodsAdd` 的图片为空分支即是），placement 无关。② 它改的是**已验证单元**（`da616a6` 的 b 组证据针对 Controller 上的注解），须单独一笔、独立验证。**注**：D 比 A′ 更符合 §10「缓存注解挂 Service 方法」，且是**唯一**能真正解决「写接口清单膨胀」的一条路 —— 三个写接口还写 `productsales`，将来缓存该表的读接口时仍要动这三处，**合并 cacheName 消不掉「表数」**。**本轮触发条件已满足 ⇒ 下一笔的候选**（本笔仍不做：一笔一主题 + 它改已验证单元） |
| **E. TTL 抖动（防同时过期）** | L1 固定 60s、L2 固定 5m 全局同刻；但三个新投影（`goodsByMerchant` / `goodsPrice` / `goodsCodes`）的键数**无上界**（入参可控），且其中两个失效是**整片清** ⇒ 「清空 → 紧接着的读集中回填 → 同一固定 TTL 同刻到期」这条路径**结构上已就位** | 某缓存族的 key 数 **> ~10**（§9.1 雪崩行）—— **实测未满足**：5 个三元组 + 4 个型号组 | 当前键数个位数 ⇒ 抖动的收益为 0；方案已记在 §9.1（L1 用 Caffeine 自定义 `Expiry` 加 0–20% 随机；L2 的 `entryTtl` 是静态的，需换逐键 TTL 供应商） |

（另有欠账一条：`project-map.md §3` 的分层说明需与本笔同步 —— 单独一笔，不混主题。）

---

## 相关 TD / BD

- 无新增。N+1 相关登记见 `project-map.md §6` 与 `cache-map.md §6`。
