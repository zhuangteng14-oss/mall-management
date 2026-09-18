# context-pack: concurrency-test

## 任务

开发 120 QPS 并发测试脚本，验证多线程场景下的业务正确性。

## 必读文档

- docs/agent/project-map.md §6（N+1 清单，并发脚本要避开这些干扰）
- docs/agent/concurrency-map.md（现有并发现状；**重点 §5「缺失的并发防护清单」——A3 的断言目标即此清单**）
- docs/agent/db-schema.md（表结构与索引，**§2 索引现状**）
- docs/agent/tech-debt.md（TD-01 ~ TD-11，以及「已完成里程碑」M1）
- docs/agent/business-decisions.md（BD-01 ~ BD-03）

## 必读源码

- Mall-management-server/src/main/java/com/minke/mallmanagement_controller/MallManagementGoodsController.java（订单核心：`orderAdd` / `orderPayIfUp` / `orderReceiveIfUp` / `orderDeliveryIfUp`）
- Mall-management-server/src/main/java/com/minke/mallmanagement_controller/MallManagementUserController.java
- Mall-management-server/src/test/java/com/minke/mallmanagementserver/smoke/AbstractSmokeTest.java（参考基类设计）

> 路径注意：主源包为 `com/minke/mallmanagement_controller/`（**无 `server` 段**），测试源为 `com/minke/mallmanagementserver/`，两者不一致，勿按惯例推断（agent.md 规则 9.1）。

## 约束

- 目标 120 QPS
- 必须有业务正确性断言
- 用 Java 自写，不引 JMeter/Gatling，**也不引任何新依赖**：JDK 内置 `java.net.http.HttpClient` + `ExecutorService` 已足够；不修改 `pom.xml`
- 测试运行需 MySQL（CI 需加 service container）
- 不影响现有 smoke test
- 不改业务代码，只加测试
- **运行形态（已定）**：产物放 `scripts/`（project-map 标注「预留，后续放验证脚本」），以 **Java 17 单文件源码启动**运行：

  ```bash
  java scripts/concurrency/ConcurrencyRunner.java
  ```

  - 只走 HTTP，**不依赖项目 classpath**，故可完全绕开 Maven
  - 不进 `surefire`，**`mvn test` 不会跑它** —— 避免 A2 定的「全量 `mvn test`」把每次 CI 都拖成必须起 MySQL
  - CI 侧**新增独立 job**（带 `mysql` service）跑此脚本；**原 backend job 保持零 MySQL、全量快速**

- ⚠️ **待裁决**：本地跑 120 QPS 大量写入会污染开发库 `mall_management_sql`（CI 的 service container 是一次性的，天然隔离）；本地是否另起独立库。

## 验收标准

4 个并发场景：只读、下单、支付、混合。每场景跑通并输出报告。

**A. 预期通过（回归证明 —— 证明现有 CAS 有效）**

| 断言 | 依据 |
|---|---|
| 无重复支付：并发对同一 `OrderId` 调 `orderPayIfUp`，恰好 1 次 `success:true`，其余返回「订单不存在或已付款」 | `concurrency-map.md` §3（`G:524` 条件 UPDATE `… AND PayIf = 0`） |
| 无非法状态流转：未付款发货/收货、重复发货、已收货后发货 —— 全部被拒 | `concurrency-map.md` §3（`G:546`、`G:568` 的 WHERE 条件） |

**B. 预期暴露缺陷（A3 的真正价值：把已有的纸面缺陷变成实测复现）**

| 断言 | 预期结果 | 依据 |
|---|---|---|
| 并发同参 `orderAdd` 不产生重复 `OrderId` | **预期失败** | `concurrency-map.md` §5 第 9 行（`order.OrderId` 无唯一索引，主键是自增 `Id`） |
| 并发同参 `loginAdd` / `friendAdd` / `goodstype`(POST) 不产生重复行 | **预期失败** | `concurrency-map.md` §5 第 5–7、8、10 行（TOCTOU 且无数据库唯一约束兜底） |
| 并发下无死锁 / 无锁等待超时（MySQL 1213 / 1205） | 待实测 | 当前 `order` 仅 6 行种子数据，全表扫描代价极低，**小表上绿色不构成有意义的证据**，需在大数据量下复测 |

**C. 不可断言**

- ~~无超卖~~ —— 已核对 `mall_management_sql.sql:54-64`：`goods` 表**无任何库存/数量列**，全项目也无扣减逻辑，**该断言没有对象**。以 B 组第 1 行的「无重复 `OrderId`」替代。

**D. 脚本自身设计约束**

- 场景优先选 `orderAdd` 系列：`Images` 是字符串入参，**不落盘**，天然规避 TD-08 的污染；避免走 `goodsAdd` 的 `MultipartFile` 分支。
- 预置数据用 `OrderId` 命名空间隔离（如 `TEST-<runId>-*`），**不改 `mall_management_sql.sql`**，跑完按命名空间清理。
- QPS 达标与业务断言**解耦**：CI runner 性能抖动大，建议 QPS 只记录、不设门禁，**门禁只卡业务断言**，否则 CI 会因性能波动随机变红。

## 相关 TD / BD

- TD-01（`spring-boot-starter-data-jpa` 空转 + `ddl-auto=update`，CI 起库时需确认不反向改表）
- TD-08（测试数据落盘污染 —— 选 `orderAdd` 系列可规避）
- TD-10（CI 无 MySQL 的临时规避；本任务激活后应为其补 MySQL service）
- BD-01（`SettlementDate` 被付款/发货/收货三处覆写，涉及支付场景的读取口径）
- BD-03（零鉴权 —— 并发脚本无需处理登录态，可直接压接口）
