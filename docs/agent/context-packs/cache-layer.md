# context-pack: cache-layer（B1 方案）

> **证据来源**：3 个 Controller 全文、`pom.xml`、`application.properties`、`scripts/concurrency/ConcurrencyRunner.java`（断言 ID 与场景负载逐条实测）、`docs/agent/cache-map.md`、`project-map.md §6`、`concurrency-map.md`、`api-contract.md`、`environment.md`。
> **性质**：**方案文档，非实现。** 本文不含代码，不改 `pom.xml` / Java / properties。
>
> **行号约定**（与 `project-map.md §6` 一致）：**本仓坐标格式 = `<类>.<方法>:<行>`**，**行号 = 方法声明行**（不是调用行 —— 最稳定）。`G:` = `GoodsController.java`（2026-10-02 前名 `MallManagementGoodsController.java`；文中 `G:` 值多为**搬迁前**坐标），`M:` = `MessageController.java`（前名 `MallManagementMessage.java`），`L:` = `ConcurrencyRunner.java`。⚠️ **`U:<n>` = 三层前 264 行版 `UserController` 的历史坐标** —— 2026-10-02 三层第三笔（user 域，`0c07e96`）后**全部失效**，**勿在新笔引用**；用户域现指一律写作 `UserService.<方法>:<行>` / `UserDao.<方法>:<行>`。旧 `U:<n>` 仅保留在明确标注为历史记录的行（本文件 §11 的「修正记录（保留）」段）。⚠️ **`M:<n>` 同理，已于 2026-10-03 三层第四笔（message 域，`8ed5fc1`）后全部失效** —— `MessageController` 由 417 行重写为 **127 行**（只剩路由，无 SQL、无 `@Transactional`），消息域现指一律写作 `MessageService.<方法>:<行>` / `MessageDao.<方法>:<行>` / `FriendCacheService.<方法>:<行>`；旧 `M:<n>` 仅保留在明确标注为**搬迁前 / 历史记录**的行（§2 的两行搬迁前值、§4 的两行、§11 ㊷ 的对照表）。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-03 实测**；重构后须重测。
> ⚠️ **2026-10-02（三层重构第二笔，商品域）后新增两个可用前缀**：`S:` = `GoodsService.java`、`D:` = `GoodsDao.java` —— 商品域的**8 个写/读端点方法体已不在 Controller**（`G:` 只到 `goodstype` 为止）。本文凡断言商品**业务或落库**行为的 `G:` 值**一律是搬迁前的**；**本次只重指了 evict 相关的那几处**（§2 表末列、§10、§11 ①②③④），其余历史锚点未逐条改写 —— 属**已知欠账**（同 §11 ㊷ 的处理方式）。
> 查询记 **「SQL 定义行 → 执行行」区间**（如 `MessageDao.selectLastReadTime:131-133`；搬迁前对应 `M:48-50`）；方法调用记 **调用行**（如 `MessageService.getConversations:162`；搬迁前对应 `M:244`）。

---

## 0. 【硬约束】缓存不得落在「先查后插」的查重查询上

**本条优先级高于穿透 / 击穿 / 雪崩的全部防护设计。违反它的后果不是性能问题，是证据问题。**

项目里有三处 TOCTOU 缺陷，是 A3 阶段最值钱的产出（B-2 三处逐轮复现，断言 `A-B2-1`）：

| 缺陷 | 「查」 | 「插」 |
|---|---|---|
| 商品类型重复 | `G:47-48` `SELECT COUNT(*) ... WHERE GoodType = ?` | `G:56-57` `INSERT INTO ... goodstype` |
| 注册用户名重复 | `UserDao.existsUserName:119` 查重 | `UserDao.insertUserCustomer:170` / `:186` / `:205`（客户）+ `:178` / `:193` / `:211`（商家），共 6 处分支 INSERT |
| 好友重复添加 | `MessageDao.selectFriendPair:233-234` `SELECT ... FROM friend WHERE ...`（搬迁前 `M:204-205`） | `MessageDao.insertFriendRequest:243-244` `INSERT INTO ... friend`（搬迁前 `M:217-219`） |

**A3 的 `A-B2-1` 断言期望这三处「缺陷复现」**（`ConcurrencyRunner.java:459` 登记为 `EXPECTED_DEFECT_PRESENT`；2026-10-02 实测，原记 `:450`）。

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
| `/goods/goodstype` | **GET** | 类型字典，几乎不变。A3 负载中 S1 占 **50%**（`L:1398` 的 `(i & 1)` 分流；`L:1409` 打印「只读 50%」）、S4b 占 **2/20 槽**（`L:1698` `slot = i % 20` + `L:1711` `if (slot < 18)`）—— ⚠️ 四处均为 **2026-10-02 实测**，原记 `L:1279-1280` / `L:1532` / `L:1549` 已失效 | `v1:goodstype:all` | 长（30–60min） | POST 新增时 evict 该键 |
| `/goods/goodsSelectHome` | POST | 首页商品全量 + 商家名。读多，无参 | `v1:goods:home` | 短（1–5min） | goodsAdd / goodsUp / goodsDe 全清 |
| `/goods/goodsSelect` | POST | 商家商品列表（`WHERE MerchantId = ?`），读多写少。✅ **已落地**（2026-09-28）：读路径 `GoodsCacheService.listByMerchant()`，单条 SQL **无 N+1**。⚠️ `MerchantId` 无索引（`db-schema.md §索引缺失`；该表原记 `G:160`，**2026-10-02 已改指 `GoodsCacheService:146`**）⇒ 库侧是**全表扫**，缓存是掩盖而非修复 | **`goodsByMerchant::<MerchantId>`**（`key="#MerchantId"`；§10 的 `v1:` 前缀仍未加） | 全局 L2 5m / L1 60s（`CacheConfig` 用 `cacheDefaults`，**无 per-cacheName TTL 配置** ⇒ 新增 cacheName 零配置） | **按 key 精确清**：三个写接口各**显式调用** `GoodsCacheService.evictByMerchant(<MerchantId>)`（2026-09-28 一笔 D：四条 evict 由三个写接口上的 `@Caching` **收敛**到该单入口），**本 cacheName 那一条**是 `@CacheEvict(cacheNames="goodsByMerchant", key="#MerchantId")` —— 精度按 cacheName 分别声明，同一份 `@Caching` 里共 **4 条** evict（见下两行） |
| `/goods/productCodePrice` | POST | 单点价格 + 图片（`SELECT Price, GoodImg`，**显式两列**，非 `SELECT *`）。✅ **已落地**（2026-09-28）：读路径 `GoodsCacheService.priceByTriple()`，单条 SQL、无 N+1。⚠️ 本仓**首个把失败当值缓存**的接口（`queryForMap` 0 行抛异常 → catch → `{success:false}` 是**正常返回的非 null Map** ⇒ 缓存到 TTL，见 §11）；⚠️ `MerchantId` 无索引（只有 `PRIMARY(Id)`）⇒ 库侧**全表扫**，缓存是掩盖而非修复 | **`goodsPrice::<MerchantId>::<GoodName>::<ProductCode>`**（**三维复合键**，`key="#MerchantId + '::' + #GoodName + '::' + #ProductCode"`；§10 的 `v1:` 前缀仍未加） | 全局 L2 5m / L1 60s（`CacheConfig` 用 `cacheDefaults`，**无 per-cacheName TTL 配置** ⇒ 新增 cacheName 零配置） | **整片清**：三个写接口各**显式调用** `GoodsCacheService.evictByMerchant()`（一笔 D 收敛），该方法的 `@Caching` 共四条、本 cacheName 用 `allEntries=true` —— 键含 `GoodName`，而 `goodsUp` **会改 `GoodName`** 且它的 `WHERE` 是 `(MerchantId, ProductCode)` ⇒ 旧 `GoodName` 既不在入参也无处可查 ⇒ 旧键构造不出；且精确清会呈现「**条件性正确**」（未改名时对、改名时**静默**漏）。⚠️ **本行原写的失效面 `goodsUp / goodsDe` 漏了 `goodsAdd`，已订正** —— 它也改变**已存在**三元组的答案（0→1 行是失败变价格；1→2 行是价格变失败：`queryForMap` 遇 >1 行抛异常） |
| `/goods/GoodsProductCodeSelect` | POST | 型号列表（`S:173-175` 纯委托 → `GoodsCacheService.listCodes()`；三层前为 `G:347-366`），读多写少。✅ **已落地**（2026-09-28）：读路径 `GoodsCacheService.listCodes()`，单条 SQL。⚠️ `MerchantId` 无索引 ⇒ 库侧是**全表扫 + 临时表**（`EXPLAIN`：`type=ALL`、`Using temporary`），缓存是掩盖而非修复；⚠️ SQL 是 `SELECT DISTINCT ProductCode` **无 `ORDER BY`** ⇒ **值序未定义**（实测连跑 6 次序稳定，但那是实测不是保证） | **`goodsCodes::<MerchantId>::<GoodName>`**（**二维复合键**，`key="#MerchantId + '::' + #GoodName"`；§10 的 `v1:` 前缀仍未加） | 全局 L2 5m / L1 60s（`CacheConfig` 用 `cacheDefaults`，**无 per-cacheName TTL 配置** ⇒ 新增 cacheName 零配置） | **整片清**：三个写接口各**显式调用** `GoodsCacheService.evictByMerchant()`（一笔 D 收敛），其 `@Caching` 里属于本 cacheName 的那条是 `allEntries=true` —— 与 `goodsPrice` 同因（键含 `GoodName`，`goodsUp` 会改 `GoodName` 而其 `WHERE` 是 `(MerchantId, ProductCode)` ⇒ 旧键构造不出）。⚠️ 这三条 evict **先于本行的读接口落地**（2026-09-28 随 `goodsPrice` 那笔一并挂上），落地前每次写都在清一个空片，见 §11「先加 evict 再加读」 |
| `/message/userSelect` | POST | 用户公开信息（原 `M:161-165`，**2026-09-28 起读路径在 `UserCacheService.publicInfo()`**）。✅ **已落地**（2026-09-28）：**本仓首个负缓存**（0 行 → `null` → 被缓存 ⇒ 防 ID 枚举穿透，§9.1 那行由「机制未验证」转「已验证」）+ `sync = true`（单飞在**负路径**上同样生效）。⚠️ **技术异常不 catch、原样上抛**（不进缓存）—— 与仓内既有「catch 吞成 marker」形态**不一致**，属**新模式**，见 §11「族内不一致」 | **`userPublic::<userId>`**（`key="#userId"`；§10 的 `v1:` 前缀仍未加） | 全局 L2 5m / L1 60s（**刻意不做负缓存单独短 TTL**，理由与触发条件见 §9.1） | **按 key 精确清**：唯一脏源是 `loginAdd`（全仓对 `user` 表的写只有 3 处，另两处与缓存的 5 列无交集），在它的**唯一出口**前显式调用 `UserCacheService.evictPublic(id)`（模板见 §10「写路径 evict 的单入口」，本笔是它的第二个实例）。⚠️ 清的是**正负条目两者**：新注册的 id 若曾被查过，「不存在」的负条目不清则本用户到 TTL 前读不到自己 |
| `/message/friendSelect` | POST | 好友列表，per-user。✅ **已落地**（2026-09-29）：读路径 `FriendCacheService.listFriends()`（SQL 在 `FriendCacheService:92`），Controller 只留路由 + 响应组装（**2026-10-03 现址** `MessageService.friendSelect:472-484`；**搬迁前** `M:342-355`）。**本行原写的坐标 `M:309-311` 是漂移值**，搬迁前实测为 `M:320-322`（全套 +11，见 §4）。⚠️ **不上负缓存**：0 行返回 `{success:true, list:[]}`（**成功空值**，原 Controller 即如此）—— 非 null，**照样被 `@Cacheable` 缓存** ⇒ 防「重复打同一键」成立，但 §9.1「穿透」行的 **null 路径不由本接口触发**，该行已据此订正。⚠️ 值是**跨表投影**（`userInfoById` 读 `user` + `customerimage`/`merchantimage`）⇒ 失效面**只对 `friend` 表闭合**，见 §11 ㊱ | **`friendList::<customerId>`**（`key="#customerId"`；§10 的 `v1:` 前缀仍未加） | 全局 L2 5m / L1 60s（`cacheDefaults`，**零配置**）+ `sync = true` | **按 key 精确清**：`FriendCacheService.evictPair(customerId, friendId)` 的 `@Caching` 里 **3 条** evict，三个写接口在**唯一出口前无条件**各显式调用一次。⚠️ **本行原写的失效面（「friendAdd / friendUp / friendDe」）已订正**：真正影响本 cacheName 的**只有 `friendUp`** —— `friendAdd` 写 `if_friend=0` 的行、`friendDe` 删 `if_friend=0` 的行，而本接口 SQL 带 `AND if_friend = 1` ⇒ 那两者对本 cacheName 是 **no-op**（仍统一清：代价 = 下次冷读回填一次 1+2N，换「三个调用点同名同形、可 grep 计数」） |
| `/message/selectFriend` | POST | 待处理好友申请，per-user。✅ **已落地**（2026-09-29）：读路径 `FriendCacheService.listRequests()`（SQL 在 `FriendCacheService:120`），Controller 只留路由 + 响应组装（**2026-10-03 现址** `MessageService.selectFriend:386-397`；**搬迁前** `M:260-272`）。**原坐标 `M:238-240` 是漂移值**，搬迁前实测 `M:249-251`（+11） | **`friendRequests::<friendId>`**（`key="#friendId"`；§10 的 `v1:` 前缀仍未加） | 同左 | 同左（同一条 `@Caching` 的第三条）。本 cacheName 的脏源是**三个写接口全体**：`friendAdd`（INSERT `(A,B,0)`）、`friendUp`（该行 `0→1` 后不再是待处理）、`friendDe`（DELETE 该行）—— 与上一行不同，这里没有 no-op |
| `/message/conversations` | POST | 会话列表（**2026-10-03 现址** `MessageService.getConversations:144-183`，SQL `MessageDao.selectConversations:112-120`；**原记** `M:25-32` 本就是陈旧值），per-user（`currentId`），读多写少 | `v1:conv:list:{currentId}` | 短（1–5min） | `send`（新消息）+ `history`（**写** `last_read_time`）。按 key 清：`history` 只改一对会话的已读时间，全清会让其他用户的会话列表无谓失效 |

> **落地后的键形态**：上表「键设计」列是**设计建议**（含 §10 的版本前缀）；实际落地为 `<cacheName>::<业务键段>` —— cacheName 段由 `@Cacheable(cacheNames=…)` 给出，业务键段是 SpEL 求值后 **key 对象的 `toString()`**（`RedisCache`，键序列化器为 StringRedisSerializer；实证 = Redis 里是干净文本键，而 value 是 JDK 字节流）。⚠️ **业务键段自身可含多个 `::`**（三维键即是）⇒ 原措辞「**两段**拼出」已不准确，改为「cacheName 段 + 业务键段」。已落地的**八**个：`goodstype::all`（`f693bdb` 之前）、`goodsHome::all`、`goodsByMerchant::<MerchantId>`、`goodsPrice::<MerchantId>::<GoodName>::<ProductCode>`、`goodsCodes::<MerchantId>::<GoodName>`（后四个 2026-09-28）、`userPublic::<userId>`（2026-09-28，**单分量键**）、`friendList::<customerId>`、`friendRequests::<friendId>`（后两个 2026-09-29，**均单分量键**）——**当前均未加 `v1:` 前缀**，前缀仍是建议值。加它的代价见 §11「形状耦合」：形状变更时必须记得 bump，否则新旧条目会在同一片键里混住。
>
> **命名规则（2026-09-28 定）**：`cacheName` = **表名 + 投影名（camelCase）**，无投影时用表名 —— `goodstype` / `goodsHome` / `goodsByMerchant` / `goodsPrice` / `goodsCodes` / `userPublic` / `friendList` / `friendRequests`（**八个全部已落地**，后五个为 2026-09-28、末两个 2026-09-29）。收益：同族的键仍能被 `KEYS goods*` 一眼看全，而失效精度可以**逐投影**不同。**复合键的两条落地实例（`goodsPrice` 三维、`goodsCodes` 二维）已抽出可复用的「复合键模板」——key 规则 / evict 规则 / 边界三条，见 §10。****同一张 `goods` 表分裂成多个 cacheName 是有意的**：`goodsHome` 是全局单键（只能整片清，allEntries 在那里零精度损失），`goodsByMerchant` 是按键数=商家数的键空间（可精确清，allEntries 在那里**不是**零精度损失）；合并后一片键空间无法只对其中一个投影精确清。**「投影名」段的第二个实例（2026-09-28）**：`goodsPrice`（单点 `{price, goodImg}`）与 `goodsCodes`（列表 `List<String>`）也**不能**合并 —— 同一片键空间里混住两种**值形状**，`@Cacheable` 会按谁先命中返回谁，触发类型混淆（`Cache#get(key, type)` 路径直接 `IllegalStateException`）。合并（B）与 evict 单入口（D）两条待办及其触发条件见 §11 —— **其中 D 已于 2026-09-28 落地**（四条 evict 收敛为 `GoodsCacheService.evictByMerchant()` 单入口，Controller 恢复无代理；代价与验证见 §10、§11）。
>
> **本节与 §7 的关系**：本表是「哪些接口可缓存」的完整候选；§7 是**落地顺序**（风险从低到高）。本节新增行不等于「纳入本轮」。 

**per-user 键的由来**：这些接口的结果**随传入 ID 变化**（`MerchantId` / `customerId` / `friendId` / `userId`），属于「用户态参数」。键必须含该 ID，**不能用一个全局键**——否则 A 用户看到 B 用户的数据。键用**业务标识**，不用行号、不用自增主键。

---

## 3. 明确不可缓存清单

| 接口 / 范围 | 类别 | 不缓存的理由 |
|---|---|---|
| **`/user/getUserInfo`** | **认证接口** | 虽然键不含密码，但它是认证接口——**缓存命中后返回的可能是旧用户信息**（密码改了、头像改了都滞后）。**认证接口的缓存风险 > 收益。安全判断不是性能判断。** |
| `/user/loginSelect` | 认证接口 | 键**天然包含明文密码**（`UserService.loginSelect:95`）。缓存它 = 把口令当缓存键长期驻留，Redis 里 `KEYS *` 可直接 dump 出来。**建议永不缓存** |
| `/goods/productSearch` | 注入面 + 键空间无界 | `GoodsDao.searchByKeyword:196-198` 把关键词**逐字符**拼进 SQL（`for (String s : keys)`；原记 `G:365`，**已越界** —— `GoodsController` 现仅 176 行），键空间无界 → 缓存等于给穿透攻击开一个不限长的靶子 |
| **`/message/getOrCreateConversation`** | **键空间 + 体积双无界**（读接口，但不宜缓存） | ⚠️ 先澄清一个易误判点：名字叫 getOrCreate，**实测是纯读** —— `MessageService.getOrCreateConversation:240-266` 内**没有任何 INSERT**（**2026-10-03 现址**；搬迁前 `M:121-155`。只有 1 次 `getUserInfoById`（2 条 SQL）+ 1 条 message 查询），故它**不是**「伪读实写」，与 `history` 不同类。它不上缓存的理由在键与体积：①**键含 4 个参数**（`currentId`/`currentType`/`otherId`/`otherType`，即「用户对」）⇒ 键空间 ~O(N²)；②正文是**全量历史消息**（无分页）⇒ 缓存体随 `send` 单调增长、无上界；③失效面 = 每个活跃用户对，`send` 要精确清对应键。第一版的收益不抵这三项复杂度 |
| `orderPayIfUp` / `orderReceiveIfUp` / `orderDeliveryIfUp` | 写（CAS） | 条件 UPDATE 实现的 CAS，是**全项目唯一正确的并发控制**。缓存会摧毁其语义 |
| `orderAdd` / `goodsAdd` / `goodsUp` / `goodsDe` / `loginAdd` / `friendAdd` / `friendUp` / `friendDe` / `send` | 写 | 写接口不进缓存；它们只做 **evict** |
| **`/user/updateAvatar`** / **`/user/updateUserInfo`** | 写（**同时是 `userSelect` 失效面的判断依据**） | 写接口不进缓存。它们被单独列出是因为 §11 有一条风险行曾断言「`updateUserInfo` 可改 username / 头像」，**实测不成立**：`updateAvatar` 只写 `customerimage`（`UserDao.updateCustomerImage:219`）/ `merchantimage`（`UserDao.updateMerchantImage:225`）两张**别的表**；`updateUserInfo` 的 UPDATE 只写 `email`/`address`/`selfintroduction`（`UserDao.updateUserProfile:238`）。与 `userSelect` 被缓存的 5 列（`customerid`/`merchantid`/`username`/`usertype`/`gender`，`MessageDao.selectUserPublicInfo:174`；**原记** `M:161`）**无交集** ⇒ `userSelect` 上缓存时**不需要**为这两个写接口挂 evict（详见 §11 该行） |
| `/message/history` | **写**（伪装成读） | `MessageDao.upsertLastReadTime:206`（调用点 `MessageService.getMessageHistory:200`；**原记** `M:88`）每次请求 upsert `user_conversation_read.last_read_time = NOW()` —— **名为查询，实为写**。缓存会让已读时间永远不更新 |
| 4 个 order 列表查询：`orderSelect`（`OrderController:61`）/ `orderDeliveryIfSelect`（`:69`）/ `orderDeliverySelect`（`:77`）/ `historicalOrdersSelect`（`:88`）—— ⚠️ **2026-10-02 实测**；原记 `G:412`/`G:432`/`G:450`/`G:478` 已随订单域三层笔失效，且 `G:` 前缀整体越界（`GoodsController` 现仅 176 行） | 高频变化 | 订单状态流转频繁（三个 `*IfUp` 是条件 UPDATE 的 CAS，见上一行）；且缓存会破坏 A3 性能基线的可比性（见 §6） |

---

## 4. N+1 位置清单（4 处）

**实测 4 处**，每处均回到源码确认循环结构：

| 位置 | 触发条件 | 复杂度 | 缓存是否可解 |
|---|---|---|---|
| `goodsSelectHome` 全量查 `G:184-185` → 循环 `G:188-208` → 内层查询 `G:194-200` | 每次调该接口 | 1 + **N**（N = 商品数） | ❌ |
| `conversations` 外层查 `MessageDao.selectConversations:112-120` → 循环 `MessageService.getConversations:148-178` → 内层查询 `MessageDao.selectLastReadTime:131-133`（**2026-10-03 现址**；搬迁前 `M:33` → `M:35-66` → `M:48-50`） | 每次调该接口 | 1 + **N**（N = 会话数） | ❌ |
| `selectFriend` 外层查 → 循环 → 调用 `getUserInfoById`。**2026-09-29 搬迁**：整段从 Controller 移到 `FriendCacheService.listRequests()`（SQL 在 `FriendCacheService:120`、循环 `:123-129`、调用内层 `:125`）；搬迁**前**的实测坐标是 `M:249-251` / 循环 `M:253-259` / 调用 `M:255`（本行原写的 `M:238-240` 是**漂移值**，全套 +11） | 每次调该接口 | 1 + **2N** | ❌ |
| `friendSelect` 外层查 → 循环 → 调用 `getUserInfoById`。**2026-09-29 搬迁**：整段移到 `FriendCacheService.listFriends()`（SQL 在 `FriendCacheService:92`、循环 `:95-103`、调用内层 `:97`）；搬迁**前**的实测坐标是 `M:320-322` / 循环 `M:324-330` / 调用 `M:326`（原写的 `M:309-311` 同样是 +11 漂移值） | 每次调该接口 | 1 + **2N** | ❌ |

**复杂度修正**：`getUserInfoById` **自身含 2 条 SQL** —— 查 `user`，再按用户类型走 `customerimage` 或 `merchantimage` 查头像。故 `selectFriend` / `friendSelect` 每轮循环产生 **2** 条查询，真实复杂度是 **1 + 2N**，不是 1 + N。

**（2026-09-29 坐标更新）** `getUserInfoById` 现有**两份**：① `FriendCacheService.userInfoById()`（`FriendCacheService:150-191`，SQL 在 `:151`、两条 image 查询在 `:172` / `:178`）—— 供两个**被缓存的**读方法自调用；② 那份同名同逻辑但**保留 catch** 的 —— **2026-10-03 起在** `MessageService.getUserInfoById:497-533`（已随三层第四笔离开 Controller；SQL 下沉 `MessageDao.selectUserPublicInfo:174` / `:184` / `:190`；搬迁前 `M:358-397`）—— 仍被**未上缓存的** `getOrCreateConversation` 使用（调用点 **2026-10-03 现址** `MessageService.getOrCreateConversation:245`；搬迁前 `M:141`）。**两份并存是有意的，不是漏删**。搬迁前的实测坐标（本行原写的 `M:330-369` 等）已是漂移值。

> ⚠️ **2026-10-02 订正**：本节两行（以及 §2 中同源的两行）里的「+11 / 全套 +11 / 同样是 +11 漂移值」**只表示「原记值与本行所载搬迁前值在当时相差 11」，不能当换算规则用** —— 以 `git show 549f74f` 逐锚点比对，实测漂移**不是常数**（同文件内 +18 / +25 / +33 / +36 / +45 并存，成因见 §11 ㊷）。凡引用这两族 `M:` 坐标一律**读到即重测**。

（对照：`getOrCreateConversation` 也调 `getUserInfoById`，但**只调 1 次**，是常数 3 条查询，**不构成 N+1**。）

**结论：缓存对 N+1 基本无效。** N+1 的本质是「每行一次查询」，只有当多行**共享同一 ID** 时缓存才有命中；ID 全不同时缓存零命中、反而多一层开销。**解 N+1 只能改 SQL（JOIN / IN 批量）。**

### 登记去向：两处 N+1 清单的关系

`project-map.md §6` 与 `cache-map.md §6` **职责不同，但有 4 行重叠**：

| | `project-map.md §6` | `cache-map.md §6` |
|---|---|---|
| 标题 | N+1 待优化清单（优先级：高） | 引入后的可收益点（建议，非现状） |
| 服务对象 | `fix-n-plus-one`（SQL 改写） | B1 缓存选型 |
| 视角 | **只收 N+1** | 收「缓存有收益的接口」 |
| 独有行 | —（原记 `selectFriend` 独有，系补漏前的状态，见下） | `goodstype`（字典，非 N+1）、`goodsSelect`（读多写少，非 N+1） |
| 重叠行 | `goodsSelectHome`、`conversations`、`friendSelect`、`selectFriend` | 同左 |

**结论：不是同一份数据的双份拷贝，是两个不同职责的清单**；但重叠的 4 行是**同一事实的两份副本**——且**已经漂移过一次**（`selectFriend` 长期只在 `project-map.md §6` 里，`cache-map.md §6` 从未登记；2026-09-27 补进后者，重叠数**当轮即由 3 变 4**）。

故本轮**两处都修**（见 §5），并在 `cache-map.md §6` 尾部加了指向 `project-map.md §6` 的交叉引用。

> **2026-09-29 追注**：本小节原写「**3 行**重叠」+ `project-map.md §6`「独有 `selectFriend`」—— 那是**补漏前**的状态：本小节在本轮补漏**之前**落笔，而补漏当轮就把 `selectFriend` 补进了 `cache-map.md §6`，**本小节的计数当时未回改**（同一处漏改也出现在 `cache-map.md §6`：「独有 `selectFriend`」+「3 行重叠」，2026-09-29 一并订正）。本节**是活文档**（§4 内另有 2026-09-29 的坐标更新），故**直接改成 4 行**，不留旧数 —— 与 §5「§6 修正明细」那类**历史记录不改写、只加追注**的处理方式不同（见 §5 的 2026-09-29 追注）。

> **留给你裁决的进一步收敛**：若要让「N+1 的坐标」只剩一个事实源，可把 `cache-map.md §6` 的重叠 4 行**改为引用**而非复制。本轮**未做**——它改变该节性质，超出「补漏 + 修坐标」的范围。

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
>
> **2026-09-29 追注**：本节是 **2026-09-27 那轮**的记录，其中 friend 块的坐标（`M:238-240` / `M:242-248` / `M:244` / `M:330-369` / `M:331-333` / `M:349-350` / `M:353-354`，以及承自 `cache-map.md §6` 的同值）是**当时的实测值**；此后该段代码整体下移 **+11**，**现值与搬迁前后对照见 §2、§4 与 §11 ㊷**。本节作为历史记录**不改写**，但 ⚠️ **原文「按 +11 读即可」的换算已于 2026-10-02 作废** —— 见 §11 ㊷：实测漂移不是任何单一常数（同一文件内 +18 / +25 / +33 / +36 / +45 并存），引用本节的 `M:` 坐标一律**读到即重测**。⚠️ `cache-map.md §6` 的同两行（`M:238-240` / `M:309-311`）**坐标本身仍未同步** —— 本笔只在 `cache-map.md` 与 `project-map.md §6` 各补了一条同文的「读到即重测」作废说明，未改其坐标（消息域三层笔负责），属已知欠账。

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
| `A-S2a-1a` / `A-S2a-1b` / `A-S2a-1c` / `A-S2a-2` | 同 OrderId 20 并发下单 → 锁指标增量 == 20 / 行数 == 1 / msg 无「数据库错误」/ 行数 == 成功响应数 | ❌ 否 | `orderAdd` 是写，不缓存。⚠️ 原写 `count >= 2` —— 那是 **B2 之前** `A-S2a-1`「缺陷存在型」的口径，**B2 把该断言翻正后即失效**；2026-09-30 S1a 又把它拆为 1a/1b/1c |
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
4. **per-user 类**（`friendSelect` / `selectFriend` / `userSelect`）—— 需处理好友关系变更的失效。✅ **三个全部已落地**：`userSelect`（2026-09-28，`userPublic::<userId>` + **负缓存** + `loginAdd` 唯一出口精确清）；`friendSelect` / `selectFriend`（2026-09-29，`friendList::<customerId>` / `friendRequests::<friendId>`，两个 cacheName **不合并** —— 值形状相同 ⇒ 合并后**静默串味**而非报错，见 §11 ㊲；失效面 = `FriendCacheService.evictPair(customerId, friendId)` 一条 `@Caching` **三条** evict，三个写接口在唯一出口前各调一次）。**per-user 类至此收口**，下一步是第 5 步（order 列表类，本轮未做）。
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
| **穿透** | 两级都要 | 空值缓存（null 也缓存短 TTL）+ 可选布隆过滤器 | **只有 `userSelect`** 走本行机制（0 行 → `null` → 被缓存）。⚠️ **2026-09-29 订正**：本行原写「`userSelect` / `friendSelect`」，但 `friendSelect` / `selectFriend` 的 0 行返回的是 `{success:true, list:[]}`（**成功空值**，非 null）⇒ 它们**照样被 `@Cacheable` 缓存** ⇒ 防穿透在「重复打同一键」这层**已成立**，只是**不靠负缓存**那一套（详见 §9.1） |
| **击穿** | 一级（Caffeine） | 热点 key 加互斥：Caffeine `LoadingCache` 天然单飞；Redis 侧用 `SETNX` 建锁 | `goodstype` 是全站最热的单 key（S1 负载 50%） |
| **雪崩** | TTL 策略 + 降级 | TTL 加随机抖动；**单机 VM Redis 无 HA**，只能靠抖动 + 降级兜 | 所有二级缓存的 key |
| **降级** | 全局 | **Redis 不可达必须回落 DB，绝不 5xx** | `A-S1-1` / `A-S4b-1` 的硬要求 |

> **降级不是优化项，是 B1 的硬约束**（见 §10 核心原则）。若不实现降级，`A-S1-1` / `A-S4b-1` 会把 Redis 的任何抖动传导为 HTTP 5xx。

### §9.1 本项目的落地状态（2026-09-28 核对）

上面那张表是**设计**，不是现状。逐项核对如下 —— **设计写了不等于生效**，本仓刚因「机制已写但从未被调用」吃过一次亏（见 §11「机制已写 ≠ 机制生效」）：

| 项 | 现状 | 依据 / 落地条件 |
|---|---|---|
| **击穿** | ✅ **已生效**（2026-09-28，`f693bdb`） | `@Cacheable(sync = true)` 是单飞的**开关**：不加它时 Spring 只走 `Cache#get(key)`，`TwoLevelCache.get(key, Callable)` 从不被调用。实测冷 L1 + 冷 L2 打 20 并发：修复前 **20 次 DB 查询**、修复后 **1 次**（`Redis GET` 增量 +20 → +1，L2 探测同样被合并）。**规则：本仓所有 `@Cacheable` 默认加 `sync = true`**，除非有理由不加 —— 约束是只允许单 `cacheNames`、不能与 `unless` 同用 |
| **穿透** | ✅ **已验证**（2026-09-28，`userSelect` / `UserCacheService.publicInfo()`） | 本仓**首个负缓存**：0 行 → **`null`** → 被 `@Cacheable` 缓存 ⇒ 同 key 的第二次读 `SQL = 0`（实测）。**为什么 null 能被缓存**（两级机制均经字节码核实）：L2 侧 `RedisCache.serializeCacheValue` 对 null 写哨兵 `BINARY_NULL_VALUE`、读回时 `deserializeCacheValue` 还原 `NullValue.INSTANCE`、再由 `AbstractValueAdaptingCache.toValueWrapper`（判 `storeValue != null`）包成 `SimpleValueWrapper(null)` ⇒ **是命中不是 miss**（`fromStoreValue` 才把 `NullValue.INSTANCE` 映射回 null）；前提是 `spring.cache.redis.cache-null-values` 未关（本仓未设 ⇒ Boot 默认 true）。L1 侧 Caffeine 禁 null **value**，但本仓 L1 存的是 `SimpleValueWrapper`（见 `TwoLevelCache.put`）⇒ 可存。**Redis 里可直接分辨**：负条目的值是哨兵字节，而不是 marker 的 JDK 流。**（历史：此前五补，2026-09-29 加第五组）** `goodsSelect` / `goodsCodes`（0 行 `list: []`）、**`friendSelect` / `selectFriend`（0 行同样 `list: []`）** 与 `productCodePrice`（0 行被 catch 吞成 `{success:false}`）**都不是 null** ⇒ 它们从不触发本行路径；其中 `productCodePrice` 那个**会把失败当真值缓存到 TTL**（见 §11「失败结果被缓存」）—— 与本行的**负缓存**必须分开记：**本行缓存的是 null（值层面的「不存在」），那条缓存的是非 null 的失败标记**（意图、机制、Redis 里的值形状都不同）。⚠️ 但**「不触发本行路径」≠「不防穿透」**：前四组的空结果都是非 null ⇒ 一样被 `@Cacheable` 缓存 ⇒ 同 key 的第二次读 `SQL = 0`。差别只在**值层面的形状**与 **Redis 里的字节**（负条目是 `NullValue.INSTANCE` 哨兵，空列表是 JDK 字节流），不在「是否防住重复打库」。**实测（2026-09-29）**：`friendList::<id>` 的空条目在 Redis 里是 **58 字节**的 `java.util.ArrayList`（size=0），与负条目的 64 字节 `NullValue` 哨兵**可字节分辨**。**⚠️ TTL 做法已订正**（2026-09-28，本笔落地时）：原写「负 TTL 明显短于正 TTL（30–60s）」**不执行** —— ① per-entry TTL 在本仓现有装配下做不到（L1 是 `TwoLevelCacheManager` 里单一的 `expireAfterWrite`、L2 是 `CacheConfig` 里 `cacheDefaults` 的单一 `entryTtl`，要改只能动**共享基础设施**，影响既有 6 个 cacheName）；② 更关键的是**它的用途已被 evict 取代**：负条目的陈旧上界由写路径的 `@CacheEvict` 封顶（`userSelect` 的唯一脏源 `loginAdd` 可**精确清**该键），不由 TTL 决定；而 TTL 更长反而**更有利于**防穿透。故**同 TTL**（L2 5m / L1 60s）。**触发条件（届时才做 differential TTL）**：出现「写接口覆盖不到该键」的负缓存场景（那时短 TTL 才是真兜底）；方案已就位 —— L1 用 Caffeine `Expiry`（3.1.8 实证存在）、L2 用 `entryTtl(RedisCacheWriter.TtlFunction)`（spring-data-redis 3.4.5 实证存在） |
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
| 写接口影响范围扩 | evict 范围要扩 | evict **收敛成 Service 单入口**（**已做** 2026-09-28，见下「写路径 evict 的单入口」）⇒ 扩范围只改一处 |
| 限流 / 熔断 / 降级 / 重试 | 与缓存协同 | 降级第一天就做 |
| 状态机改造 | 写语义变多步 | 每个状态出口声明 evict |

### 核心原则

- 缓存注解**挂 Service 方法**，不散落业务代码
- 键用**业务标识**（`MerchantId` / `UserId`），不用行号、不用自增主键
- 键加**版本前缀**（`v1:...`），结构变了升版本
- 缓存 **DTO**，不缓存裸 `Map<String,Object>`
- 写接口的 **evict 范围写进注释**
- **降级不是优化项**，是 B1 的硬约束

### 写路径 evict 的单入口（2026-09-28 定，一笔 D）

> 与「复合键模板」同级：有实例、可机械执行；今后新增**写接口**照此执行，与本节冲突的写法即为错。

**形态**：一张表的写路径 evict = **一个 Service 方法**，`@Caching(evict={…})` 四条集中声明在它上面，方法体**刻意留空**（`GoodsCacheService.evictByMerchant(String MerchantId)`）。三个写接口在各自的**唯一出口**前**显式调用**它。收益：加 cacheName 只改一处；四个 cacheName 的注解不再散落在 12 行上；**Controller 恢复无代理**（缓存注解全在 Service ⇒ `@EnableCaching` 不再需要 CGLIB 包 Controller）。

> ⚠️ **2026-10-02（三层重构第二笔）后，「三个写接口」的调用点在 `GoodsService`，不在 `GoodsController`** —— 三个写方法体整体搬了过去，evict 那一行随方法体搬（`S:146` / `S:236` / `S:304`）。形态与约束一字未变，只是宿主 bean 换了：`GoodsService` 仍是**外部 bean**（相对 `GoodsCacheService` 而言），故 §11 ③ 的「不得自调用」依然满足。

> **三个实例（本模板成立的依据）**：① `goods` 族 —— `GoodsCacheService.evictByMerchant(String MerchantId)`，一份 `@Caching` 四条（混精度）、3 个写接口各调一次（`goodsAdd` 那条**不覆盖**提前 return，见 §11 ④）；② `userPublic` —— `UserCacheService.evictPublic(String userId)`，**单条** `@CacheEvict`、**1 个**写接口（`loginAdd`）调用；③ **`friend` 族（2026-09-29）** —— `FriendCacheService.evictPair(String customerId, String friendId)`，一份 `@Caching` **三条**、**3 个**写接口各调一次。三实例的**差异正是模板要说明的**：`@Caching` 只在**同方法需要多条（尤其混精度）**时才必要，单条 cacheName 用单个 `@CacheEvict` 即可 —— 模板约束的是**形态与约束**（唯一出口前调用、方法体留空、三条约束），不是注解的数量。随第二个实例一并得到验证的还有：**「唯一出口」论证不依赖 catch 吞异常** —— `goodsAdd` 靠「全部 JDBC 在 try 内、catch 吞」；`loginAdd` 无 try/catch，靠**异常退出 + `@Transactional` 回滚**（异常路径同样不改库）⇒ 两种写法都成立，判据统一为「出口之外的路径是否可能改库」，而不是「有没有 try」。

> **⚠️ 实例 ③ 独有的两点（本模板的边界，也是 §10 唯一没有第二个实例的部分）**
>
> 1. **同一 cacheName 在同一份 `@Caching` 里出现两次**（`friendList::#customerId` 与 `friendList::#friendId`），这是**本仓首个实例**（goods 族那四条是**四个不同** cacheName）。「两条 evict 是否**都**执行」在写文档时是**待实测项**（依据只有 `CacheInterceptor` 逐条处理 `CacheOperation` 的机制）。**2026-09-29 已实测**：scratch 层（真实 `CacheInterceptor` + `ConcurrentMapCacheManager`，两条 key 均被清、无关 key 未误伤）**与运行时层**（真实两级缓存：一次 `friendUp(A,B)` 后 `friendList::A` / `friendList::B` / `friendRequests::B` **三键 `EXISTS` 同时为 0**，各自重读均产生 SQL）**均通过**。⚠️ 「scratch 通过 ≠ 运行时通过」这条纪律在此得到一次实例：两级缓存（L1 Caffeine + L2 Redis）与 scratch 的单层 `ConcurrentMapCacheManager` 是**两套清法**，不能拿前者替后者。
> 2. **论证方向与实例 ② 相反**（与实例 ① 也不同）：`loginAdd` 靠 `@Transactional` 回滚证明「异常路径不改库 ⇒ 唯一出口覆盖 100%」；而 **`friendUp` 没有 `@Transactional`**（实测），却有**两条**写语句 ⇒ UPDATE 提交后 INSERT 抛错时**库已经被改了**，却走 catch 返回 `{success:false}` ⇒ **清缓存必须无条件放在唯一出口之前，绝不能塞进 `if (updated > 0)` 分支里**。⇒ 模板的调用点判据要读成「**出口之外的路径是否可能改库**」的**两个方向**：① 异常路径**不改库**（靠事务回滚）⇒ 出口覆盖即可；② 异常路径**可能已改库**（无事务）⇒ 出口仍覆盖，但**不得**再给它附加任何 `if` 条件。**新增写接口时两种都要问一遍**。详见 §11 ㊳。

**① 调用点判据：写库之后、唯一出口之前**

- **不能改成「进方法就清」**（等价于注解的 `beforeInvocation=true`）：清完到写库之间有一个窗口，并发读会把**旧值**回填进已清的空片 ⇒ 窗口内所有读都拿到陈旧值，且**自愈要等 TTL**。放写库之后，最坏情况退化为「清早了」一样的短窗，但不再有「清晚了」。实测的漏写形态见下 ③。
- **唯一出口覆盖 100% 的前提是「全部 JDBC 都在 try 内、catch 吞异常」** —— 三个写接口当前都满足 ⇒ 唯一出口覆盖 100% 的**可能改库路径**。⚠️ 这是**可被将来破坏的前提**：新增一个 `return`（尤其在新 JDBC 之后）就会打破它。故三个调用点上方各有一段警告注释（「若新增 return 请确认：① 该分支不改库，或 ② 在该分支前也调 evictByMerchant()」）。本仓先例：`GoodsTypeCacheService.addType()` 的提前 return（`GoodsTypeCacheService:68`）**仍然 evict**（注解在方法上，与 placement 无关）—— 与 D 之后 `goodsAdd` 的同类分支行为**相反**，见 §11 ④。

**② 精度与 cacheName 的分工**：一份 `@Caching` 里可以**混精度**（`goodsHome` 全清 / `goodsByMerchant` 精确 / `goodsCodes`+`goodsPrice` 全清），理由与判据见「复合键模板」② 第 4 条。

**③ 代价（必须正视）：语义从「注解自动清」变「显式调用清」** —— 漏写那一行**不报错**，只表现为陈旧到 TTL 自愈。这是 §11「机制已写 ≠ 机制生效」同族，新增 §11 专行并附实测数字。**注意这不是 D 引入的新风险，而是 D 把风险从「placement 无关的注解」换成「placement 有关的一行调用」** —— 注解时代 `goodsAdd` 的图片为空分支也照样清（今天不清了，见 §11 ④）。

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
| ~~P6 与缓存冲突~~ **（已解决 2026-09-27）** | **原现象**：A3 的 P6 是「哨兵往返」—— 脚本用裸 JDBC 直写一行 `A3P-<runId8>`，再 `GET /goods/goodstype` 读回。B1 给该端点加缓存后，读回的是**缓存填充时刻的旧列表**，哨兵行必然不在其中 → **结构性 FAIL，重跑多少次都一样**（不是偶发）。**根因**：P6 的隐含前提是「HTTP 读回来的内容 == 库里的内容」，缓存正是打破这个前提的东西 —— 所以**换探针接口是打地鼠**（改成 `goodsSelect` 只是把爆炸推迟到给它加缓存那天）。 | **新方案**：新增 `GET /health/db`（`@Profile("a3")`，**自身永不加 `@Cacheable`**），由后端报自己连的 `@@server_uuid`，脚本 JDBC 侧查同一个变量逐字段对比。**不依赖任何被缓存的数据，故与缓存永久正交**。P5 就绪探测一并搬过去（它同样被缓存静默削弱：命中时不碰 DB，就不再能证明 Hikari/MySQL 可用），P7 阴性对照改用 `server_uuid` 比对。A3 全量已复测通过（23/23 门禁、`reproRounds=5/5`）。详见 `concurrency-test.md §脚本内置校验 P0–P7` |
| ~~两级缓存尚未生效（方案 B 只落了一半）~~ **（已解决 2026-09-28）** | **原现象**：pom 里 Caffeine + Redis 并存，但 **Spring Boot 实测只装配 RedisCacheManager** —— `CacheType` 的枚举顺序里 `REDIS` 排在 `CAFFEINE` **之前**，Redis 胜出。故 §8 定的「Caffeine(L1) + Redis(L2)」实际是**单级 Redis**。**处置**：新增 `CacheConfig` 提供 `@Primary CacheManager`（`TwoLevelCacheManager` 组合 Caffeine L1 + Redis L2，见 §8）。**六项实测**：① `--debug` 自动装配报告显示 `CacheAutoConfiguration` 因 `@ConditionalOnMissingBean(types: CacheManager)` 失配而退回；② GET#2 起 Redis `commandstats` 计数**冻结**（L1 命中不打网络，单级 Redis 必增）；③ 重启后仅 L2 命中（`get` +1、`set` 不变、DB SQL 0，即**不回刷 L2 TTL**）；④ `evict` 两级同清；⑤ Redis 不可达时回落 DB 且 **5xx=0**，恢复时 `markL2Up` 记录一次状态翻转；⑥ A3 全量回归 `exitCode=0`、门禁 0 failed(23)、`A-B2-1` 仍 `EXPECTED_DEFECT_PRESENT reproRounds=5/5`。**遗留待观测**：`app.cache.caffeine.max-size=1000` 是**防御性上界**（无键基数依据，当前仅 1 个键），待 Micrometer 观测后调整 | 若将来新增 `spring.cache.redis.*` 属性，**必须**在 `CacheConfig.l2Configuration` 里同步映射，否则该属性被静默忽略（本项目已因 commons-pool2 缺失踩过一次同类坑） |
| **机制已写 ≠ 机制生效**（2026-09-28 新增的风险类型） | **实例**：`TwoLevelCache.get(key, Callable)` 完整实现了单飞（Caffeine 原子 load + 只在真正执行 loader 时才写 L2），但 `@Cacheable` 不加 `sync = true` 时 Spring 只走 `Cache#get(key)` —— 该方法**从不被调用**，击穿防护整整一轮是**死代码**；`3678719` 的 message 第 5 条还把它当已生效的设计写了（纠正见 `f693bdb`）。**同类未验证项**：穿透的空值缓存链（同样无接口触发，见 §9.1） | 判据不是「代码写了」，而是「**有入口在走 + 有能证伪它的实测数字**」。新增机制时一并给出触发它的入口与实测；凡「代码在、调用方不在」的机制一律标**未验证**，不标「已实现」。本仓同款前例：`recLoad()` 定义了却无人调用，S1/S2b/S4b 的均值与分位从未进报告（`concurrency-test.md` 记过） |
| **单飞是 per-JVM 的** | Caffeine 在进程内，`sync = true` 只在**单个 JVM 内**合并同 key 的并发未命中。多实例部署时 N 个实例最多产生 **N** 次 DB 加载，不是 1 次 | 分布式锁是唯一真解；本项目单实例，不划算，按已知限制登记。若将来多实例：先测「N 实例冷启时的 DB 峰值」再决定是否上分布式锁 |
| **性能基线不可比** | 加缓存后 S4b 的 QPS 与 A3 记录不可直接对比 | 对比时显式声明「缓存已启用」。**（2026-09-28 补）本行即 §8 工程取舍段的实测序列**：S4b 61.5（无缓存）→ 66.0（单级）→ 69.6（两级）；跨状态对比必须声明状态，且**报告元数据目前不记录缓存状态**（已实测比对，见 §8），引用旧报告时只能靠时间线推断 |
| **`userSelect` 的失效面**（**2026-09-28 本笔已落地**） | **落地形态**：读路径 `UserCacheService.publicInfo(userId)`（0 行 → null，负缓存）+ 唯一脏源 `loginAdd` 在**唯一出口**前显式调 `UserCacheService.evictPublic(id)`（`@CacheEvict(key="#userId")` **按 key 精确清**，正负条目一起清）。`updateUserInfo` / `updateAvatar` **仍无需 evict**（理由见下）。**以下是本笔之前的实测修正记录（保留）**：原文写「`userSelect` 无对应写接口，但 `updateUserInfo`（`U:228`）可改 username / 头像」——**两条都不成立**：①`U:237` 的 UPDATE 只写 `email`/`address`/`selfintroduction`，**不改 username**；②头像根本不在 `user` 表（`updateAvatar` 写的是 `customerimage`（`U:210`）/`merchantimage`（`U:212`））。⇒ 与 `userSelect` 被缓存的 5 列（`customerid`/`merchantid`/`username`/`usertype`/`gender`，`M:161`）**无交集**，`userSelect` 上缓存时**不需要** evict | **唯一脏源是 `loginAdd`**（`user` 表只有 INSERT 新行 + 一条不含缓存列的 UPDATE）：新行能被**已缓存的键**命中，仅当客户端传入的 `id`/`customerid`/`merchantid` 与已存在值相同（`loginAdd` 的 `id` 是入参，客户端可控）。陈旧上界 = L2 TTL（5m）/ L1 TTL（60s），自愈。若要彻底关掉：给 `loginAdd` 挂 evict（代价 = 每次注册清一片，实施时再定 cacheName） |
| **`goodsHome` / `goodsSelect` 的形状耦合（`SELECT *`）** | 两者是**同一张表、同一条 `SELECT *`**（`GoodsCacheService.listHome()` 与 `listByMerchant()`）⇒ 缓存体 = 「`goods` 表**列全集**的快照」，列集合完全相同：给表加列后，**旧条目缺该列 → 响应字段缺失直到 TTL**（L2 5m / L1 60s）。**列类型已实证**（`CREATE TABLE goods`）：`Price` 是 **`double`，不是 DECIMAL** ⇒ 「12.50 / 12.5 / 12.500」这类**尾零精度**问题在类型层面不存在，只需验**类型不漂移**（往返后仍是 `Double`，不是 `BigDecimal`/`String`）；且该表**没有任何时间列** ⇒「时间列类型」在本表上**无对象可测**（不是跳过，是不存在 —— 本仓三张已缓存表 `goodstype`/`goods` 均无时间列）。**（2026-09-28 补）第三个投影 `goodsPrice` 不属本行范围**：它是**显式两列**（`SELECT Price, GoodImg`）⇒ **不耦合于列集合**（加列不影响响应），只耦合于**具体列名** —— 列**改名/删除**会让 `map.get("Price")` / `map.get("GoodImg")` 静默取不到 → 响应里 `price: null`，**编译期无感**。即本行需区分**三种**耦合：「耦合于**列集合**」（两个 `SELECT *` 投影）/「耦合于**具体列名**」（`goodsPrice`：显式列名 + `map.get("列名")`，列**改名/删除**后**静默**返回 `null`，编译期无感）/「耦合于**被选中的那一列**」（`goodsCodes`：单列 + `queryForList(sql, String.class)`，`SingleColumnRowMapper` 按**列序号 1** 取值 ⇒ 列**改名/删除**会让 **SQL 自身报错**（响亮，非静默），但把它**改选成另一列**（如 `SELECT DISTINCT GoodImg`）**不报错**、仍返回 `List<String>`，只是语义悄悄变了）。三种形态的共同点：**改表结构时编译器都不说话** | 沿用 §10 约定：**响应形状变更 ⇒ bump 键版本**。本轮**未加** `v1:` 前缀（与既有 `goodstype::all` 一致，见 §2 注），所以这一行是加前缀之前唯一的提醒 |
| **`goodsHome` 的体积与 N+1** | ①无分页 `SELECT *` ⇒ 缓存体随表增长（本轮实测 `STRLEN goodsHome::all`，见 commit）；②该接口是 1 + N 查询（§4 第 1 行）——缓存把它摊薄为「每 TTL 一次」，是**掩盖而非修复**；③冷启动/失效后的首个请求仍付 1 + N。**代码坐标已变**：读路径从 `GoodsController.goodsSelectHome()`（原 `G:157-196`）搬到 `GoodsCacheService.listHome()`，§4 表里的 `G:` 坐标随之过期。⚠️ 2026-10-02 起又多一跳：`GoodsService.goodsSelectHome()`（`S:164-166`，纯委托）—— 转发链是 Controller → **Service** → CacheService | 体积/键基数上量后（表行数上千或缓存键 > ~10）再决定分页或放弃缓存该接口；N+1 仍归 `fix-n-plus-one`（B1 不改 SQL） |
| **精确 evict 的三条边界**（2026-09-28 新增，同日补第三条） | ① **MySQL 比较 ≠ 缓存键比较**：`MerchantId` 是 `char(20)` 且默认 collation（`utf8mb4_0900_ai_ci`）**忽略尾空格与大小写**，而缓存键是**字符串精确比较** ⇒ 写侧传 `"1 "` 能改到 `"1"` 的行，却只清 `goodsByMerchant::1 `、清不到读侧用的 `goodsByMerchant::1` ⇒ 陈旧到 TTL 自愈。这是**精确清相对 allEntries 的唯一新增风险**（allEntries 对这类输入免疫，代价是别家列表被顺带清）。② **`@CacheEvict` 不是 `@Repeatable`**（spring-context 6.2.6 实证：常量池无 `Repeatable`，且该包下**没有** `CacheEvicts` / `Cacheables` 容器类 —— `unzip -l spring-context-*.jar \| grep cache/annotation/` 可复核）⇒ 一个方法要挂**两条不同精度**的 evict **只有** `@Caching(evict={…})` 一条路；单个 `@CacheEvict(cacheNames={A,B})` 只能表达**同精度**的多命名空间。③ **「条件性正确」——最坏形态**（2026-09-28 新增，由 `productCodePrice` 的键含 `GoodName` 而暴露）：若某个 key 分量**只在部分情况下**能从写接口入参构造出来，精确清就会**在常见情况下正确、在例外情况下静默漏清**。实例：`goodsPrice` 的键 `M::G::P`，`goodsUp` 的入参含 `M`/`P`（**未改名**时 `G` 也在）⇒ 精确清**看起来完全正确**，只在**改名**时漏清旧键 `M::旧G::P`（该行的真答案已变成 `{success:false}`）—— 而测试通常不改名 ⇒ **测试全绿、线上错**。**判据（可机械执行）**：取键的**每一个**分量，问「有哪个写接口能改到它、且该接口的 `WHERE`/入参含它的**旧值**」；**只要有一个分量缺 ⇒ 该 cacheName 只能 `allEntries`**（残余的精确清不是折中，是陷阱） | 三条均**登记为已知边界，本轮不改语义**：① 接受 TTL 内陈旧；若要免疫则退回 allEntries。② 已写进 `GoodsCacheService.evictByMerchant()` 的 javadoc（含上面的复核命令；**一笔 D 之后**三个写接口只留一行「调它」的注释，注解本体已搬到该 Service 方法上），免得后人以为能写两个 `@CacheEvict`。③ 已据此把 `goodsPrice` 与 `goodsCodes` **两个投影都**定为 `allEntries`（两个的键都含 `GoodName`，同一个原因）—— 判据本身已抽成 §10「复合键模板」的 evict 规则第 1 条，见 §2 表与代码注释 |
| **`goodsSelect` / `goodsCodes` / `productCodePrice` 的键空间随入参无界** | 三个接口都零鉴权（BD-03）、分量全是任意入参 ⇒ 任何人可造键。**一维**：`goodsByMerchant::<任意值>`；**二维**：`goodsCodes::<任意 M>::<任意 G>`（每个 `(M,G)` 只有 1 个键，但 `(M,G)` 本身任取 ⇒ 键数无上界）；**三维**：`goodsPrice::<任意 M>::<任意 G>::<任意 P>` —— **造键速率三维最高**（2 次请求即可产出 2 个互不相干的键，**不需要数据存在**）。实测键空间：5 个三元组 = 5 行、**0 个重复三元组**；4 组 `(MerchantId, GoodName)`（`k` 分别为 2/1/1/1）。**低危**：L1 有 `app.cache.caffeine.max-size=1000` 硬上界、L2 条目随 TTL（5m）过期，每条目是一个小列表/小 map —— 与 `productSearch` 的无界（拼 SQL + 键空间双无界，§3）**不同量级**。⬆️ **最容易被误判的一点**：`goodsSelect` 的 0 行是 `success:true, list: []`（空结果被正常缓存，**不是**「空值缓存（负缓存）机制」）；`goodsCodes` 同（无型号时也是 `list: []`）；`productCodePrice` 的 0 行是 `{success:false}`（**失败被正常缓存**，同上，也不是空值缓存）⇒ §9.1「穿透：机制未验证」的结论**不因这三个接口改变** | 登记；若键基数上量（> ~10，与 §9.1 雪崩的触发条件同源）再考虑「只缓存已存在的商家/三元组」或加负 TTL。判据：随机造 N 组入参后数 `KEYS goodsByMerchant*` / `KEYS goodsPrice*` |
| **新增 Service 层** | 项目原为「无分层」架构，B1 首次引入 Service bean | 只抽**被缓存的读路径**所需的极薄 Service，不全面重构分层（2026-09-28：已落地 `GoodsTypeCacheService`、`GoodsCacheService` 两个 —— 后者现含**三个**读方法 `listHome()` / `listByMerchant()` / `priceByTriple()`，外加**写路径的失效面** `evictByMerchant()`；**一笔 D 之后 evict 不再留在 Controller**，`@Caching` 四条全部集中在该 Service 方法上，`GoodsController` 恢复**无代理**。⚠️ **本条原写「三个写接口的方法体仍留在 Controller」—— 该句已于 2026-10-02 失效**：三层重构第二笔（商品域）把 8 个方法体整段搬进 `GoodsService`，内联 SQL 再下沉 `GoodsDao`。**「不全面重构分层」的边界因此被有意放宽了一次** —— ~~那次放宽只覆盖商品域，订单域三层（第一笔）同理；`UserController` / `MessageController` 的写逻辑**仍未搬**。~~ ⚠️ **2026-10-03 订正**：该句已**全数失效** —— 四个业务域（order / goods / user / message）的三层笔**均已落定**（user 域 `0c07e96`、message 域 `8ed5fc1`）⇒ 四个业务 Controller 的写逻辑**全部已搬**，无一例外；本仓的「Service 层」不再只是「被缓存读路径的极薄 Service」，而是**每域一个完整业务 Service**。`project-map.md §3` 的分层说明**已随之同步**，见 `project-map.md`） |
| **失败结果被缓存**（2026-09-28 新增，本仓首例） | `productCodePrice` 的 `queryForMap` 在 0 行时抛 `EmptyResultDataAccessException`（>1 行抛 `IncorrectResultSizeDataAccessException`），被方法内 `catch (Exception e)` 吞成 `{success:false}` —— 一个**正常返回的非 null Map** ⇒ **会被缓存到 TTL**（a3 为 5m）。即：**失败的生命周期从「瞬时」变成「TTL」**。⚠️ 与「空值缓存（负缓存）机制」**不是一回事**（缓存的是失败结果，不是 null） | 登记 + 接受。三条理由：① 常规解法 `unless` 被 `sync = true` **禁止**（spring-context 6.2.6 实证报文 `…sync=true operation does not support the unless attribute on '<method>'`），而放弃 `sync` 是**已实测的回归**（20 并发冷读 20 次 DB → 1 次，§9.1 击穿行）；② 唯一真实来源是前端用**未保存**的表单名查询（`GoodsUp.vue` 的 `switchPrice` 传 `editForm.goodName`），保存时即被 `goodsUp` 的 `allEntries` 清掉 ⇒ **自愈**；③ 0 行在本项目是**业务失败**，不是技术异常。**附**：重复三元组（`goods` 表无唯一约束、`goodsAdd` 不查重）会让本接口从「价格」变「失败」且被缓存到 TTL —— 实测当前 0 个重复三元组 ⇒ 该分支不可达，不修（建唯一索引属另一主题） |
| **「方案 X」被考虑过、不采纳**（2026-09-28 立项登记） | **方案 X** = 异常上移到 Controller 以绕过 `@Cacheable`：Service 不 catch → `EmptyResultDataAccessException` 上抛 → 缓存不写（`@Cacheable` 遇异常不缓存）→ Controller 层 catch 后返回 `{success:false}`。**它同时解决「不缓存失败」与「保留单飞」**，所以看起来对 | **不采纳，理由：语义错位** —— 把**业务失败**（0 行）包装成**技术异常**去骗过缓存。三层重整后业务失败仍应返回业务结果（技术异常才上抛），届时此方案**必须改回来**；且它会制造**族内不一致**（另三个 Service 都是「成功失败都返回 Map」，唯独它「失败抛异常」）。若未来重提此方向（包括未来的我），**先读本条** |
| **`DEL <key>` 只清 L2**（2026-09-28 新增的运维陷阱） | **L1 是进程内 Caffeine**，Redis 里没有它 ⇒ `DEL <key>` / `redis-cli` 的任何删除**只清 L2**，进程内那份仍在（60s）。实测现象：清缓存后**第一次读仍返回旧值且 `SQL=0`**，要等 L1 TTL 到期（或重启实例）才回落 DB。**与「机制已写 ≠ 机制生效」同族 —— 清了一半以为清干净了**，且它不会报错，只会给出「改了代码但行为没变」的假象 | 登记。**做缓存状态的验证时必须**：要么等 L1 TTL（本仓验证脚本统一用 65s），要么重启实例 —— **不要**用 `DEL` 之后的第一次读取当作「已冷」的证据。实测复现见 `productCodePrice` 那一笔的验证 d 步 |
| **evict 清单可能领先于读接口落地**（2026-09-28 新增，本仓的顺序） | 本仓是**先加 evict、再加读**：`goodsCodes` 的三条 `@CacheEvict(allEntries=true)` 在 2026-09-28 随 `goodsPrice` 一笔挂上，而它的读接口（`GoodsCacheService.listCodes()`）**下一笔才存在** ⇒ 中间那段时间里每次写都对一个**尚无实际键**的 cacheName 做清理：L2 侧**建出空命名空间 + 一次 no-op 清理**（`TwoLevelCacheManager.getCache()` 是 `computeIfAbsent` 惰性建；SCAN 命中 0 键）。**不是 bug** —— `allEntries` 对空片是幂等 no-op、代价近乎零 —— 但**顺序不常见**，会让人误以为「这个 cacheName 一定已经有读接口在用了」。**为什么本仓会这样**：`goodsPrice` 那一笔的裁决是「三个写接口的 `@Caching` 一次性扩到 4 条」，避免同一份注解改两遍；代价就是 evict 领先一拍 | 登记。判据/消解：读接口一落地即自然消解（本笔已消解）。**若将来再出现「先挂 evict」，必须能答出「它什么时候会有实际键」**；答不出就是漏了读接口 |

| **① 显式 evict 的漏写是静默的**（2026-09-28 新增，D 笔的代价） | evict 从「注解挂在写方法上」变成「写方法里手写一行调用」后，**忘写那一行不报错、不告警**，只表现为**陈旧到 TTL 自愈**。**实测（故意注释掉 `goodsUp` 的调用，纯注释、不提交）**：造键 `goodsPrice::DVT1::DVG1::DVP1`（写入价 20.0 后冷读，`{"price":20.0}`，`TTL=300`）→ **重启实例**（清 L1、留 L2：`EXISTS=1`、`TTL=233`）→ 写前读 **20.0 / `SQL=0`**（L2 命中）→ `goodsUp` 改价 20.0→**99.0** 返回 `{"msg":"商品更新成功","success":true}`，键**仍在**（`EXISTS=1`、`TTL=232`）→ 写后再读 **仍是 20.0 / `SQL=0`**，而**直接查库 `goods.Price = 99.0`** ⇒ 缓存撒谎，且**全程无任何错误信号**。陈旧上界 = 写入时刻起的 `min(L1 60s, L2 剩余 TTL)`。⚠️ **这是 D 把风险从「placement 无关」换成「placement 有关」，不是 D 引入的新风险**（注解时代同一次漏写不存在，因为这个「写」是别的写接口漏清 —— 见 ④ 的对照） | 三道防线，缺一不可：① **结构**——唯一出口 + 出口前三行警告注释（见 §10「写路径 evict 的单入口」①）；② **代码**——三个写接口的调用行是**同名同形**的一行，便于 `grep evictByMerchant` 一次数清（应为 **3**）；③ **文档**——本行 + §10 小节。判据（可机械执行，**别用裸名字**：注释里也写着它 —— 裸 `grep -c evictByMerchant` 在 Controller 上数出来曾是 9，2026-10-02 搬到 `GoodsService` 后是 **8**）：`grep -c 'goodsCacheService\.evictByMerchant(' GoodsService.java` 必须等于写接口数（**现为 3**，实测）。⚠️ **2026-10-02 起 grep 的目标文件换成了 `GoodsService.java`**（在 Controller 上跑它是 **0**）；新增写接口时这条 grep 就是 checklist |
| **② `@CacheEvict` 挂非 `public` 方法上静默失效**（2026-09-28 新增） | Spring 的缓存注解只对**代理可见的方法**生效：方法被声明为 `private` / `protected` / 包私有 ⇒ 注解**不报错、不生效**（同类：`static` / `final` 亦不被拦截）。单入口形态下达成了**两个新增可能**：方法写成非 public、或形参名改动（SpEL `#MerchantId` 解析不到），**两者都不报错** | 已固化在 `GoodsCacheService.evictByMerchant()` 的 javadoc「三条约束」里：① 必须 **`public`**；② 形参名必须叫 **`MerchantId`**（`key="#MerchantId"` 按名求值，改名的后果是 evict 报 `SpelEvaluationException` 或清错键 —— 与 `@RequestParam` 同类坑）；③ 见 ③。**本仓现状已满足**，登记是为了防后人重构时把 `public` 改掉 |
| **③ 在 `GoodsCacheService` 内部自调用 `evictByMerchant()` 无效**（2026-09-28 新增） | Spring AOP 是**代理式**：`this.evictByMerchant(...)` 不经过代理 ⇒ 注解**完全不执行**，且不报错。单入口形态让它成为一个**看起来很自然**的写法（「反正都在一个类里」） | 已写进该方法的 javadoc 第 ③ 条：**不得在本类内部调用**。调用方只能是**外部 bean**（三层前是三个写接口所在的 `GoodsController`；**2026-10-02 起是 `GoodsService` 的三个写方法** —— 仍是外部 bean，结论不变）。⚠️ 与 ② 的不同：② 是「人改了签名」，③ 是「人写了一句看起来很对的新代码」—— 后者更容易发生 |
| **④ `goodsAdd`「图片为空」提前 return 在 D 之后不再清缓存**（2026-09-28 新增，**设计选择，非缺陷**） | 注解时代：`@CacheEvict` 挂在方法上 ⇒ **提前 return 也会清**；D 之后：清是「唯一出口前的一行调用」⇒ 那个提前 return（`S:99-103`；三层前为 `G:83-87` 一带）**不清**了。**这是有意的**：该分支一行未改库（实测该次调用**写接口 SQL 段 = 0**、四键 `EXISTS` 全为 1），清它是**纯浪费**（`goodsCodes` / `goodsPrice` 的整片清要走 SCAN 删）。**行为差异仅此一处**，其余分支/出口的清法完全一致 | ① 本笔的**分支覆盖用例**（临时验证脚本、**未入库**）实测该分支「四键仍为 1 + 写接口 SQL 段 = 0」—— ⚠️ 它**没有**固化成仓库里的测试 ⇒ **将来谁改回去不会红**，这是本行的**欠账**（要做成回归断言须另起一笔，且需真起实例、不能只靠 `@SpringBootTest`）；② 本行标「**设计选择，非缺陷**」—— 不标清读者会怀疑「是不是漏了」；③ **开放问题（不追）**：`beforeInvocation=false`（默认）在方法**抛异常**时到底执不执行 evict，本轮**未实测**。**这不改变正确性**：那条异常路径**不改库**，清与不清**都对**。故登记为开放问题而非欠账 |

| **① 负缓存的失效面**（2026-09-28 新增，本笔） | `userPublic` 的负条目（「查无此人」缓存成 null）**必须**能被清，否则新注册用户的 id 若曾被查过，则该用户到 TTL 前**读不到自己**。本笔的失效面 = `loginAdd` 一处（全仓对 `user` 表的写只有 3 处：`UserDao.insertUserCustomer:170`/`UserDao.insertUserMerchant:178` 两条 INSERT 都在 `UserService.loginAdd:125`，`UserDao.updateUserProfile:238` 一条 UPDATE 只写 email/address/selfintroduction，与缓存的 5 列无交集）。**论证**：`loginAdd` 的写路径唯一出口 = `return "添加成功！"`，另一条 return（「用户已存在」）只跑 SELECT；该方法**无 try/catch**，INSERT 抛异常经异常退出、`@Transactional` 回滚 ⇒ 异常路径也不改库 ⇒ 出口覆盖 100% 的可能改库路径 | 已按 §10「写路径 evict 的单入口」落地（`UserCacheService.evictPublic`），调用点上方有警告注释（同 D 笔）。**⚠️ 一个曾被认为是反例、实测推翻的点**：`loginAdd` 的入参默认值是 `暂无数据`，若该值落进**列**里，则每次注册都会改写「键 = `暂无数据`」那一行的答案 ⇒ 精确清不完备。**实测**：对侧列不在 INSERT 列清单里 ⇒ 落 NULL；真实数据里**没有任何一行的列值是 `暂无数据`**（`COUNT = 0`），且 `NULL != '暂无数据'` ⇒ 无此漏洞。判据留档：**「精确清是否完备」要查的是「写接口能否改到某个键的答案而该键不在其入参」**，不是「该键的分量能否从入参构造」（后者是 §10 复合键模板 ② 第 1 条的旧判据，本笔补上前者） |
| **② `userSelect` 的结果可能不唯一，且缓存的是「无序第一行」**（2026-09-28 新增） | SQL 是 `WHERE customerid = ? OR merchantid = ?` + `queryForList(...).get(0)`，**无 `ORDER BY`**；而 `loginAdd` 只按 `username` 去重（`UserDao.existsUserName:119`）、**不按 userid 去重** ⇒ 客户端可控地注册出重复 id，使同一 key 命中 **2 行**（一行客户 + 一行商家）⇒ `get(0)` 返回哪一行**不确定**，而缓存把这个「当时的第一行」固化到 TTL。属 §10 边界第 3 条「值序未定义」族，此处用在**标量投影**上 | 登记，不改（改 SQL 属另一主题）。**对验证的影响**：验证用例必须用**随机新 id**、避开重复 id，否则「缓存体与冷查逐字节相同」这条断言会**偶发假红**。**触发条件**：若将来出现真实的 id 撞车（客户端这么注册，或业务引入预分配 id），则先补 `ORDER BY` 与去重约束，再谈缓存 |
| **③ `loginAdd` 的 evict 发生在事务提交之前**（2026-09-28 新增） | `loginAdd` 是 `@Transactional`（`UserService.loginAdd:125`，注解在 `:124`），而 evict 是**方法体内的显式调用** ⇒ 清完之后、提交之前有一个窗口；窗口内的并发读会把**负条目回填**，且**没有第二次清** ⇒ 该 id 到 TTL 前读不到。⚠️ 注意这与「注解挂在方法上」不同：注解形态的 evict 与事务提交的先后**取决于两个 advisor 的排序**（未实测），而显式调用形态是**确定性**地在提交前 | **登记并接受**（本笔判定无害）：该 `id` 是**刚刚创建**的，除注册者本人外无人知晓，而注册者拿到响应时事务已提交 ⇒ 窗口内不可能有并发读。**触发条件（必须重审）**：若将来缓存「写完之前该键就已被他人知晓」的东西（预分配 id、或键来自公共查询结果），此窗口会真实存在 —— 届时选项是改用挂在**事务外层**的 `@CacheEvict` 并**实测 advisor 排序** |
| **④ 技术异常与「查无此人」在响应文案上不可区分**（2026-09-28 新增，**既有瑕疵，本笔不动**） | `userSelect` 的异常路径与 0 行路径返回**同一个** `{success:false, msg:"用户不存在"}`。本笔的 C′ 已把**缓存行为**分开（业务 0 行 → null 被缓存；技术异常 → 上抛**不进**缓存），但**响应文案**照旧 ⇒ 调用方仍无法从响应判断是「真没有」还是「后端出错」。⚠️ 注意：本笔**没有加重**这条 —— 它在本笔之前就存在（原代码 catch 吞异常后落到同一 marker） | 登记，本笔不动（改文案是可观测的契约变更，须单列一笔）。**第二实例（2026-09-30 补，走查 A-3）**：`productCodePrice` 同样是「两个语义不同的失败原因 ⇒ 折叠成同一个不可区分的响应」—— 业务 **0 行**（`S:189-195`；三层前 `G:264-270`）与技术**异常**（`S:199-204`；三层前 `G:274-279`）**都**返回 `{success:false}`，且 `S:191-192` 的注释逐字写着「与下面的 catch 分支落**同一个** marker」（三层前 `G:266` —— ⚠️ 该 catch 与响应组装在 2026-10-02 又随本笔从 Controller 搬进 `GoodsService`，判据与结论不变）。补三点：(a) **它的 marker 比 ④ 更瘦** —— 连 `msg` 都没有，只有 `success:false`，调用方**连「用户不存在」这种程度的信息都拿不到**；(b) 具体调用方 `GoodsUp.vue:272`（`const switchPrice = async (code) =>`；**2026-10-02 实测**，原记 `:285`），前端只能显示无差别失败；(c) **归 ④ 而不归 §11「失败结果被缓存」**：判据是 ④ 自己末句的划界 —— 本条讲的是「失败与业务的**响应不可区分**」，与「缓存了失败」是两件事（本条**不涉及**缓存行为）。⚠️ 与 ④ 的差异仅一处：`userSelect` 的 0 行是**业务值**（null 被缓存）、异常**上抛**；`productCodePrice` 的 0 行**本身就是失败**（与异常同落 marker）与 §11「失败结果被缓存」的区别：那条讲**缓存了失败**，本行讲**失败与业务的响应不可区分** —— C′ 之后两者的**缓存行为**已经分开了 |
| **⑤ 族内不一致：`userSelect` 是新模式**（2026-09-28 新增） | `UserCacheService.publicInfo()` 采用「**业务值 + 技术异常分离**」（0 行 → 值、异常 → 上抛），而仓内既有形态是「**catch 吞成 marker**」（`GoodsTypeCacheService.addType()`、`productCodePrice` 的读路径等）。两种形态**并存**，同一项目里读同一类接口会有两套心智模型 | 登记，**本笔不动**既有形态（边界：只做 `userSelect`）。**三层重整时统一**，目标：**技术异常上抛 + `@ControllerAdvice` 统一处理**（届时 §11「失败结果被缓存」与「方案 X 不采纳」两条都要重读 —— 方案 X 被否决的理由是「把业务失败包装成技术异常」，与本条的「把技术异常如实上抛」**方向相反**，不冲突） |

| **㊱ 跨表投影不闭合**（2026-09-29 新增，**本笔的核心新增风险**） | `friendSelect` / `selectFriend` 的值是**三张表的投影**：`friend`（决定列表**成员**）+ `user`（`username`/`usertype`/`gender`，并决定 `id`）+ `customerimage` / `merchantimage`（`image`）。而 `FriendCacheService.evictPair()` 只覆盖 **`friend` 表**的写 ⇒ **对另两张表的写不闭合**。**不闭合的具体形态**：`updateAvatar` 的语句是 `UPDATE customerimage SET image = ? WHERE username = ?`（`UserDao.updateCustomerImage:219`/`UserDao.updateMerchantImage:225`）—— **`WHERE` 用的是 `username`，连 id 都不是**；受影响的键是「**所有把该用户当好友的 viewer**」，与**写者入参毫无关系** ⇒ 键**构造不出**。`loginAdd` 同理（新 id 可能**已在别人的好友列表里**）。**⚠️ 可达的真实序列（「先加好友后注册」）**：`friendAdd(A,B)` **不校验 B 是否存在**（只查 `friend` 表、不查 `user` 表 ⇒ 这是**既有缺陷，非缓存引入**）⇒ 注册前 `userInfoById(B)` 返回 null ⇒ B 被跳过、不出现在 A 的列表里；注册后应出现。若 B 注册前 A 的列表已被缓存 ⇒ **B 最长 5m 不出现** | **登记 + 接受 TTL 自愈**（本笔裁决：**不**给 `friendList`/`friendRequests` 挂 `allEntries` 兜底）。理由：挂上等于「任何人换一次头像 / 注册一个号，清掉**所有**用户的好友列表缓存」—— 代价 ∝ 活跃用户数，精度损失远大于收益；与 §11 既有裁决一致（`goodsHome` 接受 5m 陈旧**价格**，量级更重）。**触发条件（届时再挂）**：若「改头像后好友看到旧头像」或「新注册用户不出现在好友列表」被当成缺陷。⚠️ **缓存对「先加好友后注册」的作用只是把可见窗口从「下次读」放大到「TTL」** —— 该序列本身是**既有缺陷**，缓存不是成因 |
| **㊲ 两个 cacheName 不能合并，且后果是「静默串味」**（2026-09-29 新增） | 本笔的 `friendList` 与 `friendRequests` **不能**合并成一个 cacheName。**合并的后果比 goods 族更危险**：goods 族两个投影的值形状**不同**（单点 `{price, goodImg}` vs `List<String>`）⇒ `Cache#get(key, type)` 路径会抛 `IllegalStateException`（**响亮**）；而本处两个投影的值形状**完全相同**（都是 `List<Map>`）⇒ 同一个键串（例如 `"222"`）先缓存了「222 的**好友**」再读「222 的**申请**」，会**直接把好友列表当作申请列表返回** —— **不抛异常、静默串味**。第二个理由：**键列不同**（`friendList` 用 `customerid`、`friendRequests` 用 `friendid`）⇒ 同一个字符串在两族里**语义不同** | 已落地为两个 cacheName（`FriendCacheService` 类注释有完整论证）。**判据（可机械执行）**：合并两个 cacheName 前，先问「两者的值形状是否**可辨**」—— 可辨（形状不同）⇒ 会抛异常，**响亮**；**不可辨 ⇒ 静默串味，更危险**。⚠️ 这条与 §2「命名规则」段里记的 `goodsPrice`/`goodsCodes` 合并风险是**同一判据的两个实例**，但**结论相反**：那处的形状不同（会抛），这处的形状相同（不会抛） |
| **㊳ `friendUp` 的 catch 路径可能**已经改库**（2026-09-29 新增，本笔最锋利一条） | `friendUp` **没有 `@Transactional`**（实测 `MessageService.friendUp:409-435`；搬迁前 `M:275-305`），却有**两条**写语句（`UPDATE … SET if_friend=1 WHERE …if_friend=0` 与 `INSERT … VALUES (?, ?, 1)`）。若 UPDATE 提交后 INSERT 抛错 ⇒ 走 `catch` ⇒ 返回 `{success:false}`，**但库已经被改了**（`(A,B)` 已成 `if_friend = 1`）。⇒ **清缓存必须无条件放在唯一出口 `return result` 之前，绝不能塞进 `if (updated > 0)` 分支里** | 已写入 `FriendCacheService.evictPair()` 的 javadoc ＋ 三个调用点上方的警告注释。⚠️ **它的论证方向与「`userSelect` 的失效面」那条相反**：那里靠「`loginAdd` 是 `@Transactional`、异常路径**回滚 ⇒ 不改库**」证明唯一出口覆盖 100%；这里是「**无事务 ⇒ 异常路径可能已经改库**」⇒ 出口必须**无条件**覆盖。**§10 的调用点判据已据此扩成两个方向**（新增写接口时两种都要问一遍） |
| **㊴ 逐行异常上抛 = 可观测的行为变更**（2026-09-29 新增） | 原 `getUserInfoById` 是「`catch (Exception e) { e.printStackTrace(); } return null`」= **逐行吞异常**。照搬进被缓存的方法会被**放大**：某一行 enrich 失败 ⇒ 该好友被静默跳过 ⇒ 这份「**少一个人**」的列表被**固化到 TTL**（未加缓存时只错这一次请求）。故本笔的读方法**不 catch**（只保留「0 行 → null → 跳过该行」这一**业务**事实），由 Controller 既有的 `catch` 构造 marker。**代价是一条可观测的行为变更**：原先「某行失败 ⇒ 少一个好友但 `success:true`」变成「**整个请求 `success:false`**」 | **登记 + 接受**。**实测（2026-09-29，临时探针，跑完已还原并 `grep` 标记 = 0）**：让某一行的 enrich 抛 `IllegalStateException` ⇒ 响应 `{success:false, msg:…}`（**不是**「少一个好友的 success:true」），且 **Redis 无该键**（异常不进缓存）；还原后再读得到**完整**列表（`SQL=5`，证明异常没被固化）。同笔实测的对照项：探针在**全部** SQL 跑完之后抛 ⇒ 三次读 `SQL` 全 = 5（每次都真打 DB）、Redis 无该键 —— 即「技术异常不进缓存」在**正路径**上同样成立。与 §11「④ 技术异常与『查无此人』在响应文案上不可区分」**同族** |
| **㊵ 值序未定义 + 可插重复行**（2026-09-29 新增） | 两条 SQL（`FriendCacheService:92` / `:120`）**均无 `ORDER BY`**；且 `friend` 表**只有主键、无唯一约束**（`mall_management_sql.sql:38-44`）⇒ `friendAdd` 的 **B-2 缺陷本就会造重复行**（A3 每轮实测 `friendAdd` 重复行 12–19 条）⇒ 同一 viewer 的列表**可能含重复项、顺序不保证** | 登记，不改（加 `ORDER BY` / 唯一索引属另一主题）。**对验证的影响**：逐字节比对要求「两次读之间不改表」—— 本笔的 V7 已验证满足（4 个 body 与基线逐字节相同）。属 §10「复合键模板」③ 第 3 条「值序未定义」在**一维键**上的实例 |
| **㊶ 负缓存的前置条件：`spring.cache.redis.cache-null-values` 必须为 `true`**（2026-09-29 新增） | 负缓存（`userPublic` 那套「0 行 → null → 被缓存」）**依赖该属性**。本仓**三处均未设**（`application.properties` / `application-a3.properties` / `CacheConfig` 无 `disableCachingNullValues()`）⇒ 取 Boot 默认 **true**（元数据 `defaultValue: True`；`CacheProperties$Redis` 字节码 `iconst_1 → putfield cacheNullValues`；`CacheConfig.l2Configuration()` 有 `if (!redis.isCacheNullValues()) config = config.disableCachingNullValues()` 的条件映射，证明该属性**被读到**）。**⚠️ 若把它改成 `false`**：`RedisCache.processAndCheckValue` 会抛 `IllegalArgumentException`（`Cache '%s' does not allow 'null' values…`），而**该异常不是 `DataAccessException`** ⇒ `TwoLevelCache.l2Put` 的降级 catch **不接**（它只 catch `DataAccessException`）⇒ 穿透防护**失效**（且不是「静默降级为普通缓存」这么温和） | **先读本条**：改这个属性前，先确认负缓存的失效面（`loginAdd` 的 evict）仍在，并重跑「0 行 → 第二次读 `SQL=0`」那条实测。⚠️ **订正一处原拟措辞**：原先打算写「此设置若改 false，穿透防护**静默**失效」—— **不准确**：它会**抛异常**（只是被包在**非** `DataAccessException` 的类型里、逃过降级 catch）。**推断部分（未实测，标注区分）**：null 条目在抛之前**已写进 L1**（Caffeine loader 先于 `l2Put` 完成）⇒ 行为可能是「首读抛 → 之后 60s 内命中 L1 正常 → 又抛」；且 L2 永远拿不到负条目。对 `userSelect` 而言，该异常被 Controller 既有 catch 兜住 ⇒ 返回 marker、**不是 5xx** ⇒ `A-S1-1` 不会因此变红 |
| **㊷ `friendSelect` / `selectFriend` / `getUserInfoById` 的文档坐标漂移 +11**（2026-09-29 新增） | §2 与 §4 引用这三处的 `M:` 坐标**已过期**，实测均匀 **+11**（7 个锚点：`238→249`、`244→255`、`309→320`、`315→326`、`330→341` 与 `331→342`、`349→360`、`353→364`）。成因：**这两个读接口的方法体之后的区域**被整体下移，而引用它的两节没同步。⚠️ **不是「整个 friend 块」**：`friendAdd` 自己的查重 SELECT（§0 硬约束引用的 `M:215-216`）~~**未漂移**~~（`git show 549f74f` 实测 `215-216`）—— ⚠️ **2026-10-02 推翻**：B2 的 `friendAdd` 外包 `runLocked` + `friendAddLocked` 之后，该查重实测已到 **`M:240-241`**（`friendAddLocked` 内），§0 那处引用**同样需要重指** | 已在本笔一并订正（§2 两行、§4 两行 + 复杂度段），并按**搬迁后**的真实位置改为指向 `FriendCacheService`（读路径已搬走），同时保留**搬迁前**的实测值以便对照。⚠️ **2026-10-02 订正：「按 +11 读」的承诺作废。** 本行的 +11 修正写于 B2 **之前**，之后再未回改；本次以 `git show 549f74f`（即本行「实测值」那一列所测的状态）逐锚点比对，漂移**既不是 +11、也不是任何单一常数** —— 同一文件内 `friendAdd` 方法头 **+18**（207→225）、其查重 SELECT **+25**（215→240）、`selectFriend` 方法头 **+33**（247→280）、`friendSelect` 方法头 **+45**（317→362）、`getUserInfoById` 方法头 **+36**（341→377）。成因是两个方向相反的改动叠加：B1 把两个读接口的方法体**搬走**（该减），B2 给 `friendAdd` 外包 `runLocked` + 补注释（该增）。**§11 里凡是引用这两族 `M:` 坐标的行，一律「读到即重测」，不要做任何加减法换算** —— 按任何常数读都会读错。旧行未逐条改写，属已知欠账。**2026-10-02 补充**：本笔另在 `cache-map.md §6` 与 `project-map.md §6`（两处 assert 同一 +11 结论的文件）各补了一条同文的作废说明，并在本文件 §2 / §4 各加一条指向本行的提示；三处**坐标一律未改**（归消息域三层笔）。✅ **2026-10-03 结清（三层第四笔 message 域，`8ed5fc1`）**：本行点名的欠账**已全部兑现** —— §2 两行（`friendSelect` / `selectFriend` 的「Controller 只留路由 + 响应组装」）与 §4 的 `conversations` 行已重指为 `MessageService` / `MessageDao`；§0 硬约束引的 `friendAdd` 查重 SELECT 现址 `MessageDao.selectFriendPair:233-234`；本行与 §11 其它行内**三列式的「实测值」对照表（`M:238-240` 等）一律保留不改写** —— 它们是「当时实测」的存档，不是当前坐标。三分口径（当前重指 / 陈旧未标 → 已标 / 真历史留）见 `cache-map.md §6` 的 2026-10-03 结清注 |

### §11 附：四项带触发条件的待办（2026-09-28 立项三项，2026-09-29 增一项；本轮均**不做**）

| 待办 | 现状 | 触发条件 | 本轮不做的理由 |
|---|---|---|---|
| **B. 合并 goods 族 cacheName**（合成一个 `goods`） | `goodsHome`（全局单键，1 键）+ `goodsByMerchant`（按商家，键数 = 商家数）+ `goodsPrice`（三维，键数 = 三元组数）+ `goodsCodes`（二维，键数 = (商家,商品名) 组数） | goods 族读接口 **≥3 且全部是 allEntries 失效面**时（现为 **4 个**接口，但 `goodsByMerchant` 是**精确清** ⇒ **未满足**） | 本轮 `goodsSelect` 要的是**精确清**（§2 登记即「按 MerchantId 清」），合并后一片键空间**无法只对其中一个投影精确清** ⇒ 与 A′ 直接冲突。合并还需给键加投影前缀（否则 `'all'` 与 `MerchantId="all"` **撞键**，零鉴权下入参可控），代价 = 重改 `da616a6` 的键名，并使其键名类实测记录（`EXISTS goodsHome::all` / `STRLEN goodsHome::all`）在代码里再也搜不到、需注记 |
| ~~**D. evict 收敛成 Service 单入口**~~ **（已完成 2026-09-28）** | 四条 evict 曾分别挂在 3 个写接口上（共 **12 行**注解） | 写接口的 evict 清单 **≥4 条**，或**第二张表的读接口上缓存**时 ⇒ **⚠️ 本轮已满足**（`goodsPrice` 上线后从 3 条变 4 条） | ① 语义从「注解自动清」变「**显式调用清**」：漏写那一行调用是**静默**的（不报错、陈旧到 TTL 自愈），属本表「机制已写 ≠ 机制生效」同类；而注解挂在方法上时**提前 return 也会清**（`goodsAdd` 的图片为空分支即是），placement 无关。② 它改的是**已验证单元**（`da616a6` 的 b 组证据针对 Controller 上的注解），须单独一笔、独立验证。**注**：D 比 A′ 更符合 §10「缓存注解挂 Service 方法」，且是**唯一**能真正解决「写接口清单膨胀」的一条路 —— 三个写接口还写 `productsales`，将来缓存该表的读接口时仍要动这三处，**合并 cacheName 消不掉「表数」**。**触发条件已满足 ⇒ 2026-09-28 已完成**（形态见 §10「写路径 evict 的单入口」，风险见 §11 ①②③④）。**落地时的验证**：① 四键全清 × 5 用例（`goodsAdd` 正常路径 / `goodsAdd` 提前 return / `goodsUp` 带 file 改名 / `goodsUp` 不带 file / `goodsDe`）—— 除提前 return 那条**应当不清**外，其余写后四条读接口**均产生 SQL**（证明 L1 与 L2 都被清，而非只清 L2）；② 提前 return 用例实测「四键仍为 1 + 写接口 SQL 段 = 0」；③ **CGLIB 对照**：修前 `AopUtils.isAopProxy(controller)=true`、advisor 链为 `[BeanFactoryCacheOperationSourceAdvisor: advice CacheInterceptor]` → 修后 `false`，防回归测试留在 `MallManagementApplicationTests.goodsControllerIsNotAopProxied()` |
| **E. TTL 抖动（防同时过期）** | L1 固定 60s、L2 固定 5m 全局同刻；但三个新投影（`goodsByMerchant` / `goodsPrice` / `goodsCodes`）的键数**无上界**（入参可控），且其中两个失效是**整片清** ⇒ 「清空 → 紧接着的读集中回填 → 同一固定 TTL 同刻到期」这条路径**结构上已就位** | 某缓存族的 key 数 **> ~10**（§9.1 雪崩行）—— **实测未满足**：5 个三元组 + 4 个型号组 | 当前键数个位数 ⇒ 抖动的收益为 0；方案已记在 §9.1（L1 用 Caffeine 自定义 `Expiry` 加 0–20% 随机；L2 的 `entryTtl` 是静态的，需换逐键 TTL 供应商） |

| **㊸. 让 friend 的 enrich 改走 `userPublic` 缓存**（2026-09-29 立项，**本轮不做**） | `FriendCacheService.userInfoById()` 的内层 SQL 与 `UserCacheService.publicInfo()` 的 SQL **逐字相同**（`FriendCacheService:151` vs `UserCacheService:81`），而**好友 id 跨请求大量重复**（同一个「红人」出现在很多人的好友列表里）—— 这正是 §4 说的「多行共享同一 ID 时缓存才有命中」的**少见实例**。现状：每次冷读付 **1 + 2N** 条 SQL | **好友 id 的重复度上量后**（判据：某次冷读的 `1 + 2N` 里，`user` 查询的**去重后**目标数明显小于 `2N`；或 `friendList` 冷读的 DB 耗时占比升高）。届时方案：让 enrich 走 `userPublic` 缓存把 **1 + 2N** 摊薄 | **本笔不做**，两条理由：① 它会**改变失效耦合** —— `friendList` 的值将依赖 `userPublic` 的清，而 `userPublic` 的唯一脏源是 `loginAdd`，清它**不会**清 `friendList` ⇒ 多出一层「跨 cacheName 的失效传导」，当前无实例可依、无模板可套；② **image 部分仍解决不了**（`userPublic` 的 5 列里**没有** `image`，头像在另两张表）⇒ 摊薄后仍是 **1 + N + N**，收益只覆盖一半。**登记为发现，不是欠账** |

（另有欠账一条：`project-map.md §3` 的分层说明需与本笔同步 —— **2026-10-02（三层重构第二笔，商品域）已一并更新**，见 `project-map.md` §2/§3。）

---

## 相关 TD / BD

- 无新增。N+1 相关登记见 `project-map.md §6` 与 `cache-map.md §6`。
