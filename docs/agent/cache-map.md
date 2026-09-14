# 缓存地图 — Mall-management

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

以下为按现有代码实测出的 N+1 与高频只读场景，**当前均为零缓存、每次请求直查数据库**：

| 接口 | 现状问题 | 证据 |
|---|---|---|
| `GET /mysqlGoods/goodstype` | 商品类型字典，几乎不变但每次全表查 | `G:31` |
| `POST /mysqlGoods/goodsSelectHome` | 先全量查 `goods`，再**为每个商品单独查一次** `login` 取商家名（N+1） | `G:182-202`，内层查询 `G:188` |
| `POST /message/conversations` | 查完会话列表后，**为每个会话单独查一次** `user_conversation_read`（N+1） | `M:35-66`，内层查询 `M:48-50` |
| `POST /mysqlGoods/goodsSelect` | 商家商品列表，读多写少 | `G:160` |
| `POST /message/friendSelect` | 好友列表，每个好友触发一次用户信息查询（N+1） | `M:313-319` |

其中 N+1 问题**优先应通过 SQL 改写（JOIN / IN 批量查询）解决**，而非靠缓存掩盖——缓存在此只会延长脏数据窗口。真正适合缓存的仅类型字典一类低频变更数据。

## 7. 若要引入缓存

按当前技术栈（Spring Boot 3.4.5 / Java 17），**最小改动路径**为：

1. 加 `spring-boot-starter-cache` + `com.github.ben-manes.caffeine:caffeine`，单实例部署下走进程内缓存；
2. 在启动类加 `@EnableCaching`；
3. 对 `goodstype` 等字典类查询加 `@Cacheable`，并在对应写接口（`goodstype` POST）加 `@CacheEvict`。

若后续要支持多实例部署，才需要 `spring-boot-starter-data-redis` + `@Cacheable` 的 Redis 实现；届时也才有条件实现分布式锁（见 `concurrency-map.md` §5 第 12 项）。

> ⚠️ 本节为实施方案建议，**与项目当前状态无关**——现状是**零缓存**。
