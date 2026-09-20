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

### S0-5 启动验证步骤（待执行 —— 落盘以免重演「只在对话里」）

> 触发条件：`application-a3.properties` 落地后，首次以 a3 profile 启动后端时执行。
> **不要预设结论**：驱动 8.0.33 能否连 MySQL 8.4，必须实测。

| 步 | 动作 | 期望 / 判据 |
|---|---|---|
| 1 | `SPRING_PROFILES_ACTIVE=a3` 起服务，**先打一个请求**（Hikari 懒初始化，启动日志里没有配置块），再 grep 日志中 `HikariConfig` 的 `jdbcUrl` | 实际值指向 `192.168.229.129:3306/mall_management_sql` —— 这同时是「后端确实连的是虚拟机」的**第 1 层**证据 |
| 2 | 看日志中 `ImgPath` 打印的 `目前图片所在本地位置为：` | `app.upload-dir` 解析为 `<java.io.tmpdir>/a3-uploads/`，且该目录已被 `Files.createDirectories` 自动创建（验证 `${java.io.tmpdir}` 嵌套占位符确实被解析） |
| 3 | 连上后跑 `SELECT VERSION()` | **必须确认服务端为 8.4.x** —— 只验「能连上」不够：8.0.33 驱动连 8.0.x 也通，会让人误以为连的是 8.4 |
| 4 | 确认驱动 8.0.33 与 8.4 服务端的握手 | 若失败，走兜底「脚本侧 `$A3_JDBC_DRIVER` + 后端 a3 profile 临时 classpath 覆盖」——**不改 `pom.xml`**（硬约束） |

> 第 3 步的 `SELECT VERSION()` 是**判据的一部分**，不是可选项。
> 第 1 步的 `jdbcUrl` 与 2–4 步合并，构成「后端确实连的是虚拟机」四层验证的第 1 层；第 2–4 层（`--expect-db-host` 门禁、哨兵往返、localhost 反向对照）在脚本侧实现。

### 核对状态汇总（2026-09-20）

| 类别 | 条目 | 依据核对 | 落地状态 |
|---|---|---|---|
| 事实断言 | 1, 2, 3, 4, 6, 7, 8, 11 | ✅ **8/8 实测通过** | `[ ]` 待 pack 正文落盘 |
| 已锁定裁决 | 5, 6（裁决部分） | ✅ 2026-09-20 锁定 | `[ ]` 待 pack 正文落盘 |
| 设计推理 | 10（P0 清理与 P4 计数互斥） | ✅ 本会话推导 | `[ ]` 待 pack 正文落盘 |
| 待实测 | 9（S0-5） | ⏳ 等实测，**不预设** | `[ ]` 待 S0-5 结果 |
| pack 现存错误 | 12, 13, 14 | ✅ **3/3 确认存在** | `[ ]` **待修**（pack 现仍为错值） |
| 未留存 | 15 | ⏳ 未在本地任何 artefact 中留存 | `[ ]` 待人工确认 |

> **「前 14 项是『待核对』还是『已核对通过』」—— 分两个维度，不能合成一个结论：**
>
> - **依据**：逐条核对**已完成**。事实断言 8 项全部实测通过（不是照抄探索结论），裁决 2 项已锁定，12–14 确认为 pack 现存错误，9 项待实测。
> - **落地**：**0 项**。因为 pack 正文尚未修订 —— 第 1–11 项要写进去的内容还不存在，第 12–14 项的错误还挂在 pack 里。
>
> 即当前状态是「**依据已核对通过、落地待执行**」。本表在 pack 正文修订完成后，才应把「落地状态」列改为 `[x]`。

| # | 核对项 | 依据（已取证） |
|---|---|---|
| 1 | H2 —— 根 `.gitignore` 一处必须写「**追加**」而非「新增」 | `9b87ab9` 已含 27 行 `.gitignore`；见 `tech-debt.md` TD-02 更正记录 |
| 2 | F1b / OD-1 —— `loginAdd` 的 B 组断言必须是「**无 HTTP 5xx**」，**不得**写「无重复行」 | `login` PK=`UserName`、`user` PK=`username`、`customerimage` PK=`UserName` → 重复行**不可能**产生；真实缺陷是未捕获 `DuplicateKeyException` → HTTP 500（`U:65-105` 无 try/catch） |
| 3 | F4 **（已修正）** —— 种子 6 行订单全为 `(1,1,1)` 终态，故 **S2/S3/S4 三者都必须自建订单** | `mall_management_sql.sql` 的 order INSERT：Id 18–23 六行全部 `PayIf=1, DeliveryIf=1, ReceiveIf=1`；`orderPayIfUp` 的 WHERE 为 `PayIf=0` → 在种子订单上「恰好 1 次 `success:true`」**必然不成立**。原文只写 S3/S4 会让 A 组第一条断言必败 |
| 4 | B-1 —— `order.OrderId` **无唯一索引**（主键仅 `Id`） | `mall_management_sql.sql` order 建表语句。pack 不得写成「有唯一约束兜底」 |
| 5 | 澄清 1 —— `--b-group-gate` 默认 `false`、**只影响 B-2**（B-1 恒门禁、B-3 恒 record-only）；verdict 枚举 `PASS` / `EXPECTED_DEFECT_PRESENT` / `DEFECT_FIXED` / `RECORD_ONLY`；`rounds`、`reproRounds` 由脚本自动统计，**「≥3/5」不做运行时门禁**（仅作人工翻默认值的准入条件） | 本会话 2026-09-20 裁决 |
| 6 | 澄清 4 —— B 组子前缀是 `<round>`（1..5，**同轮内 20 线程共享**），**不是** `<seq>`；行序号才用 `-S<seq>` / `-N<seq>` | 若跨轮共用同参，第 2 轮起 `loginAdd` 直接走「已存在」分支，**不进并发窗口**，后 4 轮等于白跑 |
| 7 | Q1=B / TD-13 —— 隔离策略必须写「**独立实例 + 同名库** `mall_management_sql`」，**不得**写成「切库名隔离」 | 硬编码前缀 39 处（Goods 23 + User 16 + Message 0），全部指向 `mall_management_sql` |
| 8 | S0-3 —— 驱动路径由 **Maven 权威解析**（`dependency:build-classpath`），**不做 glob 猜测**；解析后**校验 jar major = 8** | 实测 `~/.m2/repository` 两个 groupId 下的真实状态：①**`pom.xml:57-61` 声明的坐标 `mysql:mysql-connector-java:8.0.33` 本地只有 `.pom`、没有 jar**（jar 因 relocation 落在 `com.mysql:mysql-connector-j:8.0.33`）；②按 `mysql/mysql-connector-java/*/*.jar` 去 glob，**唯一命中的是 `8.0.28`**（8.0.31/8.0.33 在该坐标下无 jar）→ 静默用上 8.0.28；③按 `com/mysql/**/*.jar` 去 glob 会同时命中 `8.0.31` 与 `8.0.33`，不排序则取到 8.0.31；④另有 3 个 `mysql/mysql-connector-j/8.0.33/*.lastUpdated` 失败标记（groupId 与 artifactId 混用导致的不存在坐标）。**结论：glob 猜测必然取错版本，必须走 Maven** |
| 9 | S0-5 —— 必须 `SELECT VERSION()` 确认服务端确为 8.4.x；兼容失败时的兜底写「脚本侧 `$A3_JDBC_DRIVER` + 后端 a3 profile 临时 classpath 覆盖」，**不得**写成改 `pom.xml` | 硬约束：不改 `pom.xml`；`environment.md` 已定 MySQL 8.4 |
| 10 | 禁止并发 —— 必须写明「两个 A3 运行**绝不**可同时作用于同一实例」 | P0 的 `A3TEST-%` 清理与 P4 的种子计数互斥 |
| 11 | P4 种子计数 —— `order=6` / `goods=5` / `login=5` / `user=5`（已复核 ✓）；另 **`friend=4`**，B-2 跑完按 `+2 × 轮数` 计提 | `mall_management_sql.sql` 各表 INSERT 逐行计数 |
| 12 | 约束节「**只走 HTTP，不依赖项目 classpath**」与已定的 **HTTP + JDBC** 冲突 → 须重写；前置准备第 3 步的「届时再议」已由「脚本形态 = HTTP+JDBC」**解决**，应改为结论 | 本文件 `约束` 节 vs `archive/progress-2026-09-19.md`「脚本形态 = HTTP + JDBC」 |
| 13 | 前置准备第 1–3 步（建 `mall_management_test` 库）**已失效** → 须按 Q1=B 重写为「独立 MySQL 实例 + 同名库」 | Q1=B 裁决 + TD-13（本地测试库当前「建好了但用不上」） |
| 14 | 必读文档行「tech-debt.md（**TD-01 ~ TD-11**）」**已过时** → 须补 TD-12、TD-13 | `tech-debt.md` 现有 TD-01 ~ TD-13 |
| 15 | **待回填** —— H3 / H4 / H5 / H6 正文 | 未在本地**任何** artefact 中留存（9 个转录文件穷举 + 全仓库 grep，均 0 命中）→ 属「**从未落盘过**」，**不是遗漏**。见下节 |

### H 组正文未能定位的说明（2026-09-20）

**已取证的事实：**
- 全仓库 grep（`preflight` / `哨兵` / `--b-group-gate` / `expect-db-host`）→ **0 命中**，即第一阶段探索结论**从未落进任何文件**
- 项目全部 **9 个**转录文件穷举 `F3` → 命中 4 个，但逐条抓定义句后：`5574d7b3` 的 11 处**全部落在 2026-09-20 本轮的命令与说明自身**；`2cbb3e66`（9-18）与 `d7f1f78d`（9-14）属其它任务（N+1、A2）

**未能确定的部分：** 以 `F3` / `H3` 为**标签**的正文未找到，**不等于**结论正文不存在 —— 它可能以**其它标题**存在于转录中（F/H 编号可能是用户侧的命名，未必是我输出时的标题）。故不排除正文仍在转录中的可能，只是本轮无法定位。

**处理：** 不做推断补全（`agent.md` 规则 9.1），第 15 项待人工提供原文。若确实无法提供，则以本 checklist 的**已取证条目**为基线继续 —— 第 1–14 项覆盖了所有会直接影响实现的结论，且每项都有独立取证，**不依赖 H 组正文**。
