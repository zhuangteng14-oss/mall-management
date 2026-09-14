# 并发控制地图 — Mall-management

> **证据来源**：3 个 Controller 全文（缩写 `U`/`M`/`G`，数字为源码行号）、`mall_management_sql.sql`（缩写 `SQL`）、`application.properties`（缩写 `APP`）。
> 全部表为 InnoDB，行锁可用；但代码层几乎未使用任何并发控制。

## 1. 摘要

| 结论 | 严重度 |
|---|---|
| 全项目**仅 1 处** `@Transactional`（`loginAdd`） | — |
| 订单三状态位用条件 UPDATE 实现 **CAS**，是全项目唯一正确的并发控制 | 正面案例 |
| `productsales.OrderVolume` **从未被更新**，订单量统计功能失效（**非竞态问题**） | 高 |
| 4 处多表写**无事务保护**（`goodsAdd`/`goodsUp`/`goodsDe`/`friendUp`） | 高 |
| 3 处「先查后插」存在 **TOCTOU 竞态**，且无数据库唯一约束兜底 | 高 |
| 无任何锁：无 `synchronized`/`Lock`/`FOR UPDATE`/`@Version`/Redis/Redisson | 中 |
| 无任何异步与线程池：无 `@Async`/`@Scheduled`/`CompletableFuture`/`ThreadPoolTaskExecutor` | 低（当前为纯同步模型） |

## 2. 唯一的 @Transactional

| 位置 | 方法 | 覆盖的写入 | 评价 |
|---|---|---|---|
| `U:47` | `loginAdd` | `user` 插入 + `customerimage`/`merchantimage` 插入 + `login` 插入（客户分支 `U:70,75,81`；商家分支 `U:87,91,96`） | ✅ **用法正确**。三表写入具备原子性，任一失败整体回滚。 |

三个 Controller 全文再无第二处 `@Transactional`。

## 3. 正面案例：order 三状态位 CAS

三个订单状态接口全部使用**条件 UPDATE**，以 `rows == 0` 作为冲突信号。InnoDB 下单条 UPDATE 持行锁且原子，重复请求会被 WHERE 条件拦截——这是全项目唯一正确的并发控制。

| 接口 | WHERE 条件 | 防护效果 | 证据 |
|---|---|---|---|
| `orderPayIfUp` | `OrderId = ? AND PayIf = 0` | 重复支付 → 拒绝 | `G:524`，`rows` 判断 `G:526-533` |
| `orderReceiveIfUp` | `OrderId = ? AND PayIf = 1 AND ReceiveIf = 0` | 未付款收货 / 重复收货 → 拒绝 | `G:546`，`G:548-555` |
| `orderDeliveryIfUp` | `OrderId = ? AND PayIf = 1 AND DeliveryIf = 0 AND ReceiveIf = 0` | 未付款发货 / 重复发货 / 已收货后发货 → 拒绝 | `G:568`，`G:570-577` |

冲突时返回明确失败消息（如「订单不存在或已付款」），前端可据此提示。**该模式应作为其余写接口的改造范本。**

⚠️ 遗留缺口：三处均把 `SettlementDate` 覆写为 `NOW()`（`G:524`/`G:546`/`G:568`），先付款后发货时结算时间会被发货时间覆盖，字段语义存疑（见 `db-schema.md` §4）。

## 4. 纠正记录：OrderVolume 不是竞态，是功能失效

**原错误推断**：「`OrderVolume` 是计数器，下单时读-改-写，无版本列 → 并发丢失更新」。

**正确结论**：该字段**全项目从未被更新**，永远停留在插入时的 `0`。既不存在读-改-写，也就不存在丢失更新；真正的问题是**订单量统计功能完全失效**。

| 行号 | 事实 |
|---|---|
| `G:79` | 局部变量 `OrderVolume` 声明后**从未被引用** |
| `G:129-138` | 上架时 INSERT `productsales`，`G:137` 硬编码传参 `0` |
| `G:312-314` | 更新商品时仅 `UPDATE productsales SET GoodName=?`，**不含 `OrderVolume`** |
| `G:230-232` | 下架时仅 `DELETE`，无计数调整 |
| `G:397-411` | `orderAdd` 只 INSERT `order`，**完全不触碰 `productsales`** |

交叉证据：`SQL:176` 中 5 条 `productsales` 记录 `OrderVolume` 全为 `0`，而 `SQL:159` 的 `order` 表已有 6 条历史订单。

## 5. 缺失的并发防护清单

| # | 缺失项 | 具体问题 | 证据 |
|---|---|---|---|
| 1 | 多表写无事务 | `goodsAdd` 插 `goods` + `productsales`，第二次失败会留下无销量记录的孤儿商品 | `G:113`、`G:133` |
| 2 | 多表写无事务 | `goodsUp` 更新 `goods` + `productsales` 两表非原子 | `G:304`、`G:314` |
| 3 | 多表写无事务 | `goodsDe` 删 `goods` + `productsales` 两表非原子 | `G:227`、`G:231` |
| 4 | 多表写无事务 | `friendUp` 改单向 `if_friend=1` 后插入反向行，第二步失败会产生**单向好友**（半好友关系） | `M:266`、`M:268` |
| 5 | TOCTOU 竞态 | `loginAdd` 先 SELECT 查重再 INSERT，并发下两个请求可同时通过查重 | `U:61` → `U:81` |
| 6 | TOCTOU 竞态 | `friendAdd` 先查再插，并发重复申请会写入重复行 | `M:205` → `M:219` |
| 7 | TOCTOU 竞态 | `goodstype` POST 先查重再插，并发下可产生重复类型 | `G:42` → `G:51` |
| 8 | 无唯一约束兜底 | `friend` 仅 PK `id`，`(customerid, friendid)` 无唯一键 —— 上述竞态**无数据库层兜底** | `SQL:43` |
| 9 | 无唯一约束兜底 | `order.OrderId` 无唯一索引（主键是自增 `Id`），业务单号可重复 | `SQL:154` |
| 10 | 无唯一约束兜底 | `goodstype.GoodType` 无唯一索引，查重仅靠应用层 | `SQL:78` |
| 11 | 无锁机制 | 全项目无 `synchronized`、无 `Lock`、无 `SELECT ... FOR UPDATE`、无 `@Version` 乐观锁版本列 | 3 个 Controller 全文 |
| 12 | 无分布式锁 | 未引入 Redis / Redisson（见 `cache-map.md` 依赖实测），多实例部署时连应用层锁的基础设施都不具备 | `pom.xml:32-68` |
| 13 | 无异步 / 线程池 | 无 `@Async`、`@Scheduled`、`CompletableFuture`、`ThreadPoolTaskExecutor` —— 当前为纯同步阻塞模型，不存在异步竞态 | 3 个 Controller 全文 |

## 6. 连接池配置

`APP` 中**未配置任何** `spring.datasource.hikari.*` 参数。项目引入 `spring-boot-starter-jdbc`（`pom.xml:40`），走 Spring Boot 默认的 **HikariCP**，各项参数均为框架默认值（`maximum-pool-size` 默认 10）。

> 该数值为框架默认，**非本项目显式配置**。并发压力下的实际表现取决于默认值，如需调整须显式配置——**当前未做任何调优**，属待确认项。

## 7. 改造优先级建议

1. **立即**：为 §5 中 #1–#4 的四处多表写补 `@Transactional`（成本最低、收益最直接）。
2. **立即**：给 `friend(customerid, friendid)`、`order.OrderId`、`goodstype.GoodType` 加唯一索引，让数据库兜住 #5–#7 的 TOCTOU 竞态。
3. **中期**：把 §3 的 CAS 模式推广到其余状态变更类写接口。
4. **中期**：修复 `OrderVolume` 的统计逻辑（`orderAdd` 成功后递增，或改为查询时实时聚合）。

> 上述为改造建议，**非现状描述**。
