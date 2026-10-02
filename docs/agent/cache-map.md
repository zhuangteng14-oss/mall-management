# 缓存地图 — Mall-management

> ⚠️ **时效标注（2026-09-29 加）**：**§1–§5 是 2026-09-14 的「引入缓存前」现状快照** —— 那一天全项目确实零缓存依赖、零 Redis 连接，故 §1 的「当前项目未引入任何缓存依赖」只对**那一天**成立。**B1 自 2026-09-27 起逐笔落地**（首个缓存接口 `goodstype`；两级 CacheManager 于 2026-09-28 上线），**§1–§5 已被实现取代**。**现行状态以 §6 为准**（该节已按每笔落地逐行加注）；§7「若要引入缓存」是**当时写的建议路径**，与最终落地不同（真实方案是两级 Caffeine L1 + Redis L2，见 `context-packs/cache-layer.md §8`）。§1–§5、§7 **保留作历史对照，不重写**；节内所有行号与实测值均为 **2026-09-14** 的值。

> **证据来源**：`pom.xml` 的 `<dependencies>` 段全文、`application.properties` 全文、3 个 Controller 全文。
> 结论基于**实际依赖清单**，非命名推测。前端源码未读。

## 1. 结论

**当前项目未引入任何缓存依赖，需新增。**

全项目不存在任何形式的缓存层：无缓存依赖、无缓存配置、无缓存注解、无缓存管理器、无 Redis 连接。所有 32 个接口均直连 MySQL（`JdbcTemplate`）。

## 2. 依赖实测（`pom.xml:32-68`）

`<dependencies>` 段共 7 项，全量列出：

| 依赖 | 用途 | 缓存相关 |
|---|---|---|
| `spring-boot-starter` | 基础 | 否 |
| `spring-boot-starter-jdbc` | JdbcTemplate | 否 |
| `spring-boot-starter-test` (test) | 测试 | 否 |
| `spring-boot-starter-data-jpa` | JPA（实际未使用，无 `@Entity`） | 否 |
| `spring-boot-starter-web` | Web MVC | 否 |
| `mysql-connector-java:8.0.33` | MySQL 驱动 | 否 |
| `lombok:1.18.46` (provided) | 样板代码 | 否 |

**以下依赖全部未引入**（已逐项核对）：

- `spring-boot-starter-data-redis` — 无
- `spring-boot-starter-cache` — 无
- `com.github.ben-manes.caffeine:caffeine` — 无
- `org.redisson:redisson` — 无
- 任何其他缓存实现（EhCache、Hazelcast、Infinispan 等）— 无

## 3. 配置实测（`application.properties`，共 13 行）

全文仅含应用名、端口、数据源、JPA 四类配置。**不存在**任何缓存相关配置项：

- 无 `spring.cache.*`
- 无 `spring.redis.*` / `spring.data.redis.*`
- 无 `spring.datasource.hikari.*`（连接池参数，见 `concurrency-map.md` §6）

## 4. 代码实测（3 个 Controller 全文）

- **无** `@EnableCaching`、`@Cacheable`、`@CachePut`、`@CacheEvict`
- **无** `CacheManager` / `ConcurrentMapCacheManager` 等任何缓存管理器
- **无** `RedisTemplate` / `StringRedisTemplate` / `RedisConnectionFactory`
- **无** 手写 `Map` 充当进程内缓存（`HashMap` 出现处均为方法内局部结果集，作用域不跨请求）
- 3 个 Controller 的 `import` 段均无缓存/Redis 相关包

因此，即便 Spring 提供了默认的 `ConcurrentMapCacheManager`，本项目也**未启用**——该默认实现需要 `spring-boot-starter-cache` 依赖 + `@EnableCaching` 才生效，二者均缺失。

## 5. 前端

**待确认。** 前端源码未在授权读取范围内。浏览器侧是否存在 `localStorage`/`sessionStorage`/HTTP 缓存策略等客户端缓存，**无法判定**。

## 6. 引入后的可收益点（**建议，非现状**）

以下为按现有代码实测出的 N+1 与高频只读场景，**2026-09-14 时均为零缓存、每次请求直查数据库**（缓存落地后本表**逐行加注**，见各行与下方注记）：

行号约定：`G:` = `GoodsController.java`（2026-10-02 前名 `MallManagementGoodsController.java`；本节 `G:` 值一律为**搬迁前**坐标），`M:` = `MessageController.java`（前名 `MallManagementMessage.java`）。查询记**「SQL 定义行 → 执行行」区间**，方法调用记**调用行**。

| 接口 | 现状问题 | 证据 |
|---|---|---|
| `GET /goods/goodstype` | 商品类型字典，几乎不变但每次全表查 | `G:37-38`（**搬迁前坐标**；现 SQL 在 `GoodsTypeCacheService.listTypes()` `:46-47`，Controller 只剩 `G:47` 调用 —— 见下注） |
| `POST /goods/goodsSelectHome` | 先全量查 `goods`，再**为每个商品单独查一次** `login` 取商家名（N+1，1+N） | 全量查 `G:184-185`，循环 `G:188-208`，内层查询 `G:194-200`（**均为搬迁前坐标**；现整段在 `GoodsCacheService.listHome()`，Controller 只剩 `G:176` 调用 —— 见下注） |
| `POST /message/conversations` | 查完会话列表后，**为每个会话单独查一次** `user_conversation_read`（N+1，1+N） | 外层查 `M:33`，循环 `M:35-66`，内层查询 `M:48-50` |
| `POST /message/selectFriend` | 好友申请列表，每个申请人触发一次用户信息查询（N+1，**1+2N**） | 外层查 `M:249-251`，循环 `M:253-259`，内层调用 `M:255`（**搬迁前坐标**；2026-09-29 起整段在 `FriendCacheService.listRequests()` —— 见下注） |
| `POST /message/friendSelect` | 好友列表，每个好友触发一次用户信息查询（N+1，**1+2N**） | 外层查 `M:320-322`，循环 `M:324-330`，内层调用 `M:326`（**搬迁前坐标**；2026-09-29 起整段在 `FriendCacheService.listFriends()` —— 见下注） |
| `POST /goods/goodsSelect` | 商家商品列表，读多写少 | `G:166-167`（**搬迁前坐标**；现 SQL 在 `GoodsCacheService.listByMerchant()` `:142-143`，Controller 只剩 `G:164` 调用 —— 见下注） |

**`1+2N` 的由来**：`selectFriend` / `friendSelect` 的内层调用的是私有方法 `getUserInfoById`（定义 `M:341-380`），**该方法自身含 2 条 SQL** —— `M:342-344` 查 `user`，再按用户类型走 `M:360-361`（`customerimage`）或 `M:364-365`（`merchantimage`）查头像。故每轮循环实际产生 **2** 条查询。（对照：`getOrCreateConversation` 也调它，但只调 1 次，是常数 3 条，**不构成 N+1**。）

其中 N+1 问题**优先应通过 SQL 改写（JOIN / IN 批量查询）解决**，而非靠缓存掩盖——缓存在此只会延长脏数据窗口。真正适合缓存的仅类型字典一类低频变更数据。

> **与 `project-map.md §6`（「N+1 待优化清单」）的关系**：两节**职责不同**——本节服务**缓存选型**（故含 `goodstype`、`goodsSelect` 两个非 N+1 行），`project-map.md §6` 服务 **SQL 改写**（`fix-n-plus-one.md` 读的是那一节，只收 N+1）。两节有 **4 行**重叠（`goodsSelectHome` / `conversations` / `friendSelect` / `selectFriend`），**需两处维护**。⚠️ `selectFriend` 原只在 `project-map.md §6`，2026-09-27 补进本节后重叠即由 3 行变 4 行 —— 本句与「独有 `selectFriend`」的旧写法是同一处漏改，2026-09-29 一并订正（`project-map.md §6` 末注复述的「重叠 3 行」同源，同改）。
>
> 2026-09-27 修正：补 `selectFriend`（此前只在 `project-map.md §6` 登记）；修 3 处坐标错误（`goodstype` 原 `G:31` 是方法签名行、`goodsSelectHome` 内层原 `G:188` 是循环头、`goodsSelect` 原 `G:160` 是注解行）。缓存侧完整方案见 `context-packs/cache-layer.md`。
>
> **2026-09-29 修正（`M:` 坐标 +11 漂移，与 `project-map.md §6` 同步）**：上表两个 friend 行与本段 `getUserInfoById` 的 `M:` 坐标**四组各漂移 +11** —— 原记 `M:238-240` / `M:242-248` / `M:244`（selectFriend）、`M:309-311` / `M:313-319` / `M:315`（friendSelect）、`M:330-369` / `M:331-333` / `M:349-350` / `M:353-354`（getUserInfoById），实测值为 `M:249-251` / `M:253-259` / `M:255`、`M:320-322` / `M:324-330` / `M:326`、`M:341-380` / `M:342-344` / `M:360-361` / `M:364-365`。**证据**：以 `git show 549f74f:MallManagementMessage.java` 逐行核对（该 commit 即上述代码搬迁前的状态），非推算。⚠️ **不是「整个 friend 块」**：`friendAdd` 自己的查重 SELECT（`M:215-216`）**未漂移** —— 下移发生在那两个读接口的方法体之后。⚠️ 搬迁后 Controller 里**仍有一份**同名同逻辑但保留 `catch` 的 `getUserInfoById`（现 `M:358-397`，供未上缓存的 `getOrCreateConversation` 使用）—— 两份并存是**有意的**，不是漏删，见 `cache-layer.md §4`。
>
> ⚠️ **同日起这两个接口的读路径已搬到 `FriendCacheService`**（`listFriends()` / `listRequests()`，B1 缓存笔）：上表 `M:` 值是**搬迁前**位置，沿它到 Controller 只会找到路由与响应组装，实现与 SQL 在 Service（`FriendCacheService:92` / `:120` / `:150-191`）。缓存把这两处的 `1 + 2N` 摊薄为「每 TTL 一次」，是**掩盖而非修复**。
>
> **2026-09-29 修正（三个 `G:` 行的失效 —— 与上面两条不同源，不是 +11 漂移）**：上表三个 goods 行的 `G:` 值失效的原因是**读路径已整段搬进 Service**，且**搬迁日期各不相同**（`goodstype` 为 2026-09-27 `79d2a0d`；`goodsSelectHome` / `goodsSelect` 为 2026-09-28 `da616a6` / `accfe6d`）—— 故这些值自搬迁起就不再指 SQL，`G:` 一律按**搬迁前**位置读：
> - `goodstype`：`G:37-38` → SQL 在 `GoodsTypeCacheService.listTypes()` `:46-47`，Controller 只剩 `G:47` 的调用。
> - `goodsSelectHome`：`G:184-185` / `G:188-208` / `G:194-200` → 整段在 `GoodsCacheService.listHome()`，Controller 只剩 `G:174` / `G:176`。⚠️ **旧 `G:188-208` 现在是 `goodsDe` 的方法体（写路径）** —— 照旧值去找读路径会读到 `DELETE FROM … goods / productsales`，这是本节三处 `G:` 失效里最该先修的一处（不是「读错行」，是「读到写」）。
> - `goodsSelect`：`G:166-167` → SQL 在 `GoodsCacheService.listByMerchant()` `:142-143`，Controller 只剩 `G:160` / `G:164`。
> `cache-layer.md §11` 此前只就 `goodsHome` 一行标过这类「读路径已搬家」的失效，未覆盖上述三处；本节据此一次性补齐。

## 7. 若要引入缓存

按当前技术栈（Spring Boot 3.4.5 / Java 17），**最小改动路径**为：

1. 加 `spring-boot-starter-cache` + `com.github.ben-manes.caffeine:caffeine`，单实例部署下走进程内缓存；
2. 在启动类加 `@EnableCaching`；
3. 对 `goodstype` 等字典类查询加 `@Cacheable`，并在对应写接口（`goodstype` POST）加 `@CacheEvict`。

若后续要支持多实例部署，才需要 `spring-boot-starter-data-redis` + `@Cacheable` 的 Redis 实现；届时也才有条件实现分布式锁（见 `concurrency-map.md` §5 第 12 项）。

> ⚠️ 本节为实施方案建议，**与项目当前状态无关**——现状是**零缓存**。
