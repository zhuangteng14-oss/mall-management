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

## 前置准备

### 本地测试库（隔离开发库）

1. 本地 MySQL 执行：

   ```sql
   CREATE DATABASE mall_management_test;
   CREATE USER 'test'@'localhost' IDENTIFIED BY 'test123';
   GRANT ALL ON mall_management_test.* TO 'test'@'localhost';
   ```

2. 导入表结构与种子数据（**SQL 文件自带 CREATE DATABASE 和 USE，
   必须过滤后导入，否则会切回原库名**）：

   ```bash
   sed -E '/^CREATE DATABASE/d; /^USE /d' mall_management_sql.sql > /tmp/mall_import.sql
   mysql -u test -ptest123 mall_management_test < /tmp/mall_import.sql
   rm /tmp/mall_import.sql
   ```

3. 切库配置：

   ```bash
   DB_URL=jdbc:mysql://localhost:3306/mall_management_test
   ```

   > 生效点在**服务端**，不在脚本：脚本只走 HTTP，连库的是被测的 Spring Boot 服务。要压测试库，须**启动被测服务时**把 `spring.datasource.url` 指向 `mall_management_test`。若脚本自身还需直连库做断言，则须额外提供 JDBC 驱动路径 —— 与「不依赖项目 classpath」冲突，届时再议。

4. 跑完可保留库供复用，不 DROP。

### CI 环境

用 service container 起临时 MySQL，自动导入 `mall_management_sql.sql`。

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

## 落盘前 checklist（v1 — 仅含已取证条目）

> 用途：本 pack 正文与探索结论之间**已出现漂移**。落盘时对着本表**逐项打勾**，不靠记忆。
> 每条均附取证依据；无依据的条目一律不列入（`agent.md` 规则 9.1）。
> 本段在 pack 正文落盘完成后应删除或压缩为「已核对」一行。

| # | 核对项 | 依据（已取证） |
|---|---|---|
| 1 | H2 —— 根 `.gitignore` 一处必须写「**追加**」而非「新增」 | `9b87ab9` 已含 27 行 `.gitignore`；见 `tech-debt.md` TD-02 更正记录 |
| 2 | F1b / OD-1 —— `loginAdd` 的 B 组断言必须是「**无 HTTP 5xx**」，**不得**写「无重复行」 | `login` PK=`UserName`、`user` PK=`username`、`customerimage` PK=`UserName` → 重复行**不可能**产生；真实缺陷是未捕获 `DuplicateKeyException` → HTTP 500（`U:65-105` 无 try/catch） |
| 3 | F4 **（已修正）** —— 种子 6 行订单全为 `(1,1,1)` 终态，故 **S2/S3/S4 三者都必须自建订单** | `mall_management_sql.sql` 的 order INSERT：Id 18–23 六行全部 `PayIf=1, DeliveryIf=1, ReceiveIf=1`；`orderPayIfUp` 的 WHERE 为 `PayIf=0` → 在种子订单上「恰好 1 次 `success:true`」**必然不成立**。原文只写 S3/S4 会让 A 组第一条断言必败 |
| 4 | B-1 —— `order.OrderId` **无唯一索引**（主键仅 `Id`） | `mall_management_sql.sql` order 建表语句。pack 不得写成「有唯一约束兜底」 |
| 5 | 澄清 1 —— `--b-group-gate` 默认 `false`、**只影响 B-2**（B-1 恒门禁、B-3 恒 record-only）；verdict 枚举 `PASS` / `EXPECTED_DEFECT_PRESENT` / `DEFECT_FIXED` / `RECORD_ONLY`；`rounds`、`reproRounds` 由脚本自动统计，**「≥3/5」不做运行时门禁**（仅作人工翻默认值的准入条件） | 本会话 2026-09-20 裁决 |
| 6 | 澄清 4 —— B 组子前缀是 `<round>`（1..5，**同轮内 20 线程共享**），**不是** `<seq>`；行序号才用 `-S<seq>` / `-N<seq>` | 若跨轮共用同参，第 2 轮起 `loginAdd` 直接走「已存在」分支，**不进并发窗口**，后 4 轮等于白跑 |
| 7 | Q1=B / TD-13 —— 隔离策略必须写「**独立实例 + 同名库** `mall_management_sql`」，**不得**写成「切库名隔离」 | 硬编码前缀 39 处（Goods 23 + User 16 + Message 0），全部指向 `mall_management_sql` |
| 8 | S0-3 —— 驱动路径由 **Maven 权威解析**（`dependency:build-classpath`），**不做 glob 猜测**；解析后**校验 jar major = 8** | `~/.m2` 同时存在 `com.mysql:mysql-connector-j:8.0.31` / `8.0.33` 与 `mysql:mysql-connector-java:8.0.28`，并有 `*.lastUpdated` 失败标记 → glob 会静默取到错误版本 |
| 9 | S0-5 —— 必须 `SELECT VERSION()` 确认服务端确为 8.4.x；兼容失败时的兜底写「脚本侧 `$A3_JDBC_DRIVER` + 后端 a3 profile 临时 classpath 覆盖」，**不得**写成改 `pom.xml` | 硬约束：不改 `pom.xml`；`environment.md` 已定 MySQL 8.4 |
| 10 | 禁止并发 —— 必须写明「两个 A3 运行**绝不**可同时作用于同一实例」 | P0 的 `A3TEST-%` 清理与 P4 的种子计数互斥 |
| 11 | P4 种子计数 —— `order=6` / `goods=5` / `login=5` / `user=5`（已复核 ✓）；另 **`friend=4`**，B-2 跑完按 `+2 × 轮数` 计提 | `mall_management_sql.sql` 各表 INSERT 逐行计数 |
| 12 | 约束节「**只走 HTTP，不依赖项目 classpath**」与已定的 **HTTP + JDBC** 冲突 → 须重写；前置准备第 3 步的「届时再议」已由「脚本形态 = HTTP+JDBC」**解决**，应改为结论 | 本文件 `约束` 节 vs `archive/progress-2026-09-19.md`「脚本形态 = HTTP + JDBC」 |
| 13 | 前置准备第 1–3 步（建 `mall_management_test` 库）**已失效** → 须按 Q1=B 重写为「独立 MySQL 实例 + 同名库」 | Q1=B 裁决 + TD-13（本地测试库当前「建好了但用不上」） |
| 14 | 必读文档行「tech-debt.md（**TD-01 ~ TD-11**）」**已过时** → 须补 TD-12、TD-13 | `tech-debt.md` 现有 TD-01 ~ TD-13 |
| 15 | **待回填** —— H3 / H4 / H5 / H6 正文 | 未留存（见下节），须人工提供或确认可省略 |

### H 组不可恢复的说明

H1–H6 / F1–F6 的第一阶段探索正文**在任何本地转录中都不存在**：对项目全部 9 个转录文件穷举搜索 `F3` 的命中，全部落在 2026-09-20 本轮的搜索命令自身，以及 9-14 / 9-18 的其它任务（N+1、A2）上。上下文压缩只留下了章节名，正文未留存。

故本 checklist 只收录**已取证条目**，不对 H3–H6 做推断补全（`agent.md` 规则 9.1）。第 15 项须由人工提供原文后补全，或明确确认可省略。
