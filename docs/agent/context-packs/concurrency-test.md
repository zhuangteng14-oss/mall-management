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
- **运行形态（已定，2026-09-20 修正）**：产物放 `scripts/`（project-map 标注「预留，后续放验证脚本」），以 **Java 17 两步编译后运行**运行：

  ```bash
  javac -encoding UTF-8 -d <out> scripts/concurrency/ConcurrencyRunner.java
  java -cp <out>:<mysql-connector.jar> ConcurrencyRunner [选项]
  ```

  - ⚠️ **原定案是「单文件源码启动」`java scripts/concurrency/ConcurrencyRunner.java`，已作废**：
    该模式由启动器在运行期就地编译，**不采用任何编译期 `-encoding`**，而按宿主平台默认
    字符集解读源码。Windows 上 `file.encoding=GBK` → 源里中文字面量全部失真 → 所有依赖
    中文响应体的断言静默落空、恒不命中。实测同脚本同机器，S3 由「异常单=0 / PASS」变成
    「异常单=10 / FAIL」，且不报任何错。详见待落盘清单第 18 条。
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

## 元教训：基于局部信息下结论（两个实例）

本节由「待落盘清单」第 21 条的末条子项与第 23 条**合并而成**（2026-09-20；合并后不再单列第 23 条 —— 单独立条只会将来续出 24、25 条，不如一次归类）。两例同源，且与 **TD-02**（根目录无 `.gitignore`）、**TD-07**（catch 归因错）同类：**都没读实际内容就下了结论**。

**实例 1 —— 看调用点存在，就以为「有效」（`recLoad()` 死代码）**

`recLoad()` 一度**定义了却无人调用**，于是 S1/S2b/S4b 的均值与分位从未进 REC，报告里 `scenarios` 只有 `B2` 与 `cleanup` 两段。是**实测报告内容**才发现的，不是读代码发现的。与 `--rounds`、`--report-dir`（修复前）、**TD-16**（`--reset-db`）同类。

→ 规则：报告这类「汇总出口」必须打开**实际产物**验一遍，光看调用点存在不算数。

**实例 2 —— 看 grep 命中行号，就断言那一行的内容（`jdbcPassword` 误报）**

我在 CI 设计稿里写「`run.sh` 的横幅会原样打印 `jdbcPassword`，CI 日志会传成 artifact」，并据此建议本轮顺手修 —— **这是错的**：`mask()`（`ConcurrencyRunner.java` 的 `static String mask(String s)`）早已存在，且两处打印点（启动横幅、JSON 报告的 `target` 段）**都已调用**，报告里写的是 `******`。

- 成因：我在 grep 命中表里看到 `jdbcPassword` 出现在那两行的**行号**上，就断言它是明文 —— 只看了行号，没读那一行。这与 `agent.md` 规则 9（不得从名字/位置推断，要读内容）正相反；同一轮里 `--reset-db` 我读了那几行，就判对了。
- 代价：这条是**我提出、你据以批准的**。若照单执行，会写出一个「把已经正确的东西改一遍」的 commit，是纯粹的噪声与风险 —— **上游的错报会变成下游的工作量**。

→ 规则：**grep 命中行号 ≠ 命中内容**。

**两者的关系**：同一类错误的两面 —— 一个是「看调用点存在就以为**有效**」，一个是「看调用点存在就以为**有害**」。两者都源于不读实际内容。

附带记下 `mask()` 的行为：返回 `"*".repeat(len)`，故脱敏后仍泄漏**口令长度**（横幅里是 7 个星号）。判断为可接受（长度不是秘密），未改。

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
| 已回填 | 15 | ✅ 2026-09-20 自转录**自行定位并解出**（非人工提供） | `[x]` 已写入本文件下节 |

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
| 3 | F4 **（已修正；2026-09-20 再收窄）** —— 种子 6 行订单全为 `(1,1,1)` 终态，故 **S3/S4 必须自建订单**；**S2 不在此列** | `mall_management_sql.sql` 的 order INSERT：Id 18–23 六行全部 `PayIf=1, DeliveryIf=1, ReceiveIf=1`；`orderPayIfUp` 的 WHERE 为 `PayIf=0` → 在种子订单上「恰好 1 次 `success:true`」**必然不成立**。修正对象是**支付 / 发货 / 收货**三场景，即 S3 与 S4 —— **上轮把 S2 一并写入是笔误，实为 S3**：S2 只调 `orderAdd` 自建订单（订单由被测端点自身创建），不存在「复用种子单」的问题。故原依据里「原文只写 S3/S4 会让 A 组第一条断言必败」一句的前提本身有误，一并更正 |
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
| 15 | H3 / H4 / H5 / H6 正文 —— **已回填**（见下节）：含 **4 处与已落盘冲突（C1–C4，一律以已落盘为准）**、**2 处 H3 自身缺口（G1–G2）**、**7 项待裁决（D1–D7）** | 正文**存在于本地转录**（`5574d7b3` 第 134 行，27208 字符，经 `json.loads` 解出）。原判「从未落盘」**为误** —— 根因见下节 |
| 16 | R12 —— **改本 pack 时须同步 grep `concurrency-map.md` §5**：第 5 行的 `loginAdd` 表述（以及第 9 行的 `order.OrderId`）是否仍与 pack 的 B 组断言口径一致 | H3 `risks.R12`；`agent.md` 规则 9.2 / 9.3（**引用外部文档的结论必须回原文核对**）。已当场核对过一次：第 5 行确为 `loginAdd` TOCTOU、第 9 行确为 `order.OrderId` 无唯一索引，与本 pack `A-S2a-1` 的 `basis` 一致（详见待落盘清单第 13 条）。列为 checklist 项的理由：这是**每次改 pack 都要重做**的动作，不是一次性核对 —— 上游 §5 若增删行，pack 的 `basis` 会静默失准 |

### H3–H6 正文回填（2026-09-20）

**出处（已自行定位，无需人工提供）：**
- 文件：`C:\Users\86159\.claude\projects\C--Users-86159-Desktop----------\5574d7b3-2969-4a05-9192-a50392eb19f5.jsonl` 第 **134** 行（27208 字符，`role=assistant`）
- 解出方式：逐行 `json.loads` 后取 `message.content[].text` → `h-line134.txt`（脚本 `extract_h.py`）。**不可用 `sed` / `awk` 反转义**，见根因 3
- 同源材料：第一阶段 A3 方案 JSON 在 `4d8d887a-df56-4ef4-a671-d2ad4444d423.jsonl` 第 **206** 行（13490 字符 → `plan-line206.txt`）；该份的 H4/H5 为 H 组前身

**检索失败根因（「从未落盘」的结论为误）：**
1. 按 `H1`~`H6` / `F3` 这类**标签**检索，而正文的键是英文（`backend_to_vm_db` / `files_to_change` / `script_design` / `script_form_decision` / `ci_integration` / `verification_backend_on_vm`）—— 按键名一搜即得
2. **更关键**：`F3` 的检索**确实命中过** `5574d7b3`，但该文件的全部命中被整体归因为「2026-09-20 本轮的命令与说明自身」而丢弃 —— 第 134 行（正文本身，其 `findings[2].id == "F3"`）就在其中。**命中是真的，归因是错的**，与 TD-02 更正 2、TD-07 的 catch 归因同属「结果对、归因错」
3. Bash 工具会把命令串中的 `\\` 折叠为 `\`，故 `sed 's/\\n/\n/g'` 类反转义**静默失效**（`awk` 会报 `unterminated regexp`）。JSONL 内容的唯一正确解出路径是 `json.loads`
4. 「探索结论从未落进任何文件」（全仓库 `preflight` / `哨兵` / `--b-group-gate` / `expect-db-host` grep 0 命中）**这条本身仍成立** —— 正文在转录里，不在仓库里

**H 组与已落盘冲突 —— 一律以已落盘为准（已落盘经过多轮修正）：**

| # | H 组原文 | 已落盘（胜） | 性质 |
|---|---|---|---|
| C1 | H2 `files_to_change.new[]`：「`.gitignore` 根目录新增（TD-02 指出根目录无 `.gitignore`）」；H 组 `rollback.gitignore` 亦写「若该文件仅为本任务而建，可直接删文件」 | 根 `.gitignore` 早已存在（27 行，`9b87ab9`），A3 **只追加 2 条**，绝不新建 / 覆盖 / 删除 | 复现 TD-02 原错 |
| C2 | H3 `driver_prerequisite.resolution_order`：glob `~/.m2/repository/com/mysql/**/{mysql-connector-j,mysql-connector-java}-*.jar` 取**首个匹配** | Maven 权威解析（`dependency:build-classpath`）+ 校验 jar major=8 | glob 必然取错版本（item 8 实测） |
| C3 | H3 `report.schema_sketch.assertions[].verdict`：`PASS`/`FAIL`/`EXPECTED-DEFECT-PRESENT`/`DEFECT-FIXED`/`SKIPPED` | `PASS` / `EXPECTED_DEFECT_PRESENT` / `DEFECT_FIXED` / `RECORD_ONLY`（**下划线**） | 拼写 + 语义双差（见 D6） |
| C4 | H3 `report.schema_sketch` 无 `rounds` / `reproRounds` 字段，亦无「复现率低」报告级提示 | 澄清 1 + 建议 4 选项 B：脚本自动统计、报告必带、弱证据在摘要行显式提示 | 缺字段 |

> H 组**内部**亦自相矛盾一处：H3 `invocation` 用 `-cp <mysql-connector-jar>`（仅驱动），H5 第 8 步用 `-cp "$(cat /tmp/cp.txt)"`（全项目 classpath）；而 pack 约束节写「不依赖项目 classpath」。三方需统一，见 **D3**。

**H3 自身的两个缺口（非与已落盘冲突，属新发现）：**

| # | 缺口 | 后果 |
|---|---|---|
| G1 | `P6` 哨兵行前缀为 `A3PROBE-`，而 `cleanup.statements_sketch` 只清 `A3TEST-<runId>%` | 哨兵行不在清理语句覆盖范围内，仅靠「P6 后立即 DELETE」兜住；进程若在该窗口被 SIGKILL，哨兵行**永久残留**，且 `--cleanup-only --run-id` 也清不掉（前缀不同） |
| G2 | `P4` 种子计数只写 `order=6 / goods=5 / login=5 / user=5`，漏 `friend` | 已由 item 11 补上（`friend=4`，B-2 跑完按 `+2 × 轮数` 计提） |

**H4 / H5 / H6 与已落盘一致（无冲突），要点确认：**
- **H4**：HTTP + JDBC；四条理由中「清理无 HTTP 通路」为决定性 —— `order`/`login`/`user`/`goodstype`/`friend` 五表全项目无删除接口
- **H5**：第三个 job `concurrency`、无 `needs: backend`；service container **`mysql:8.4`**（与 `environment.md` 对齐；第一阶段那份 JSON 写的是 `mysql:8.0`，**H5 更晚，以 8.4 为准**）；**容器内库名就是 `mall_management_sql`，故 `CREATE DATABASE` / `USE` 正好落位、不需 sed 过滤**（与本地 `mall_management_test` 场景相反）；`--app.upload-dir=/tmp/a3-uploads/`；`if: always()` 上传报告 + `app.log`（供事后核对 Hikari 实际 jdbcUrl）
- **H6**：五层验证 —— L1 启动日志（**只对 `HikariConfig` 开 DEBUG**，避免 `com.zaxxer.hikari.pool` 的每请求日志在 120 QPS 下刷屏并拖慢被测对象）/ L2 `--expect-db-host` 门禁 / L3 哨兵往返 / L4 阴性对照 / L5 旁证（不入脚本）。**关键洞察**：哨兵往返只能证明「HTTP 侧与 JDBC 侧同库」，**不能**证明「该库是虚拟机」（本机开发库同名，TD-13），必须叠加主机比对。H6 尚无 `SELECT VERSION()`（S0-5 是更晚的发现），亦未提「Hikari 懒初始化需先打请求才有配置块」—— 两项均已由已落盘内容补上
- **场景计数口径澄清**：pack 验收标准写「**4 个**并发场景」（只读/下单/支付/混合 = S1/S2/S3/S4），H3 在各场景下设子相位，**实际负载段落为 6 个**（S1、S2a、S2b、S3、S4a、S4b）。此前「6 场景」的说法即源于此，**与「4 场景」不矛盾**

**已随本轮回填当场核实的 H3 事实（`agent.md` 规则 9）：**
- `SQL:99` login 种子含 `('123','123456',1,'pIzTWafaGp')` → S1 的 `loginSelect('123','123456')` 断言 `A-S1-3` 成立
- `SQL:145/148/147`：`OrderId varchar(40)`、`Images varchar(100)`、`ProductInformation varchar(500)` → H3 的 `length_check`（命名空间串长 < 24）成立
- `SQL:152-153`：`CreateTime` / `SettlementDate` 均为 `varchar(50)` → F6 成立（脚本直查该列须按字符串处理，不得按 TIMESTAMP 比较）
- `SQL:48` `friend` 种子 4 行、`SQL:83` `goodstype` 种子 5 行 → 与 item 11 一致
- `SQL:159` 种子 `OrderId` 形如 `ZHJC53759811`（4 大写字母 + 8 数字）→ 与 `A3TEST-` 前缀零碰撞

**待裁决（本轮不预填，`agent.md` 规则 9.1）：**

| # | 事项 | 出处 |
|---|---|---|
| D1 | **OD-3**：独立 job 架构下 backend job 仍零 MySQL，TD-10 的 `@Disabled` 删不掉。维持现状（TD-10 继续挂起），还是让 `concurrency` job 顺手跑一次 `-Dtest=MallManagementApplicationTests` 以真正关闭 TD-10？ | H3 `open_decisions` |
| D2 | **OD-8**：S4b 混合负载时长 60s（CI 总时长增约 3–4 分钟）是否接受 | H3 `open_decisions` |
| D3 | classpath 形态：H3「仅驱动 jar」vs H5「全项目 cp.txt」vs pack 约束「不依赖项目 classpath」—— 建议统一为「用 `dependency:build-classpath` 解析后**只提取 mysql-connector 单条** + 校验 major=8」，同时满足 H3 的窄 classpath 意图与 item 8 的「不靠 glob」 | H3 `invocation` / H5 step 8 / pack 约束节 |
| D4 | **item 3 措辞收窄**：H3 的 S2 只调 `orderAdd`（订单由被测端点自身创建），不存在「复用种子单」问题；F4 的修正对象是**支付/发货/收货**三场景。故 item 3「S2/S3/S4 三者」中的 **S2 应删**，收窄为「S3/S4」 | item 3 vs H3 `scenarios[1]` / `findings F4` |
| D5 | **pack §B 候选新断言**：H3 的 S4a 用 `orderAdd` 直接构造 `(1,1,1)` 等前置态 —— 反证 `orderAdd` 的三个状态位**无任何校验**，客户端可直接伪造「已付款/已发货/已收货」的订单。此为 pack §B 未收录的缺陷类（状态伪造），是否登记为 B 组新条目？ | H3 `scenarios[3].sub_phases[0].setup` |
| D6 | H3 verdict 枚举中的 `SKIPPED` 是否保留为**前置项状态**（P7 不可达时用），与断言 verdict 分离 | H3 `phase_0_preflight.P7` |
| D7 | H3 `R12` 要求：改 pack 时同步 grep `concurrency-map.md §5` 第 5 行的 `loginAdd` 表述（`agent.md` 规则 9.2/9.3）—— 该动作**未进 checklist**，是否补为第 16 项 | H3 `risks.R12` |

### 待落盘清单（脚本跑通后一次性并入正文；本清单即索引）

> 口径：下列条目均已**实测或已定案**，按约定不在脚本跑通前散落进正文。
> 标 ⚠️ 的是**未验证项或强度不足的判据**，落盘时**必须保留标记**。

**S0 实测记录（已定稿）**

1. **S0-1 导入命令**：`sudo` 需密码，且 `root@localhost` 为 `auth_socket` → root 无法 TCP 登录，`-p` 形式不可能。定稿 `echo 123456 | sudo -S -p '' mysql < /tmp/mall_management_sql.sql`（文件需先上传；库名已是 `mall_management_sql`，无需 `sed` 过滤）。含 11 条 `DROP TABLE IF EXISTS` → 仅 `--reset-db`、仅 VM/CI，**不得指向有真实数据的库**。
2. **S0-2 账号与权限**：`test@'%'` 存在（`caching_sha2_password`），实为 `GRANT ALL ON *.*`（含 DELETE）；`test@'localhost'` 不存在；DELETE 实测通过 → cleanup 可行。**越权登记为新 TD**：「`test` 账号本应最小权限，本地/CI 因 cleanup 放 ALL，转生产前必须收紧」——登记，不改。
3. **S0-5 启动验证四步**：VM `SELECT VERSION()` = `8.4.11-0ubuntu0.26.04.1`；宿主 JDBC 用 `pom.xml` 声明的 8.0.33 连通 → **无需回退，`pom.xml` 未改**；后端自证四步（`profile is active: "a3"` / jdbcUrl → 192.168.229.129 / `Database version: 8.4.11` / upload-dir / `Tomcat started on port 8088`）。
4. **vmrun 通道配方**（将来复用）：`export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'` —— 否则 Git Bash 把 `/bin/sh` 改写成 Windows 路径，`runProgramInGuest` 全报「未找到文件」；**vmrun 不回传 guest stdout** → 落 guest 文件再 `CopyFileFromGuestToHost`；`getGuestIPAddress` 输出含 CRLF，勿用 `read -r` 捕获（会把 `\r` 并进路径）。实例 `D:\VMware\VMs\Ubuntu-MySQL-A3.vmx` @ `192.168.229.129`。
5. **D3 驱动单条路径**定稿：`~/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar`（`dependency:build-classpath` 解析后**只取单条** + 校验 major=8）。glob 陷阱：`mysql/mysql-connector-java/*/` 只有 8.0.28，且各版本均存在 `-sources.jar`。
6. **P7 判据修正**（锁 1）：host 为主、version 为辅，任一成立即通过；已实装。
7. **REPEATABLE-READ 表述修正**（锁 3）：写「A-S4a-7 依赖 **MySQL 默认的** REPEATABLE-READ（8.4 实测）」，**不得**写「本项目配置为 RR」。
8. **`environment.md` 的历史重写警告需扩容**：明文凭据不止 `5da4127`，`application-a3.properties` 亦随 jar 出厂。

**A3 脚本（阶段 A–C 已定案）**

9. **哨兵前缀定案 `A3P-<runId8>`**（12 字符）。理由为**长度**，非实现简化：`goodstype.GoodType` 是 `char(20) NOT NULL`，严格模式下 >20 即 `ERROR 1406`。实测：`A3P-<runId8>`=12、`A3TEST-<runId8>`=15、`A3TEST-<runId8>-PROBE`=**21（已超 1）**、`A3TEST-<runId8>-PROBE-<host>`=**37**、H3 原式 `A3PROBE-<runId8>-<host>`=**32** —— **任何带 `PROBE` 字样的形式都塞不进 20**，故命名表那一行不可执行。选定式同时满足三件事：塞得进、**仍携带 runId**（可按 run 精确定位）、前缀可辨（可全局清扫）。
   ⚠️ **出处待补**：`docs/` 全域 grep 不到「D 组命名空间表」，磁盘上唯一记录的哨兵形态是本文第 198 行 G1 所引 H3 的 `A3PROBE-`。若确有一份 D 组命名表，落盘时须提供出处并同步改写。
10. **cleanup 双规则**：`A3TEST-<runId>%`（run 作用域）+ **`A3P-%`（全局，无 runId 亦可清）**。全局清扫安全 —— 前缀是代码字面量，正常数据不可能命中，且探针行按构造即一次性，故**任何后续 run 都能自愈先前被 SIGKILL 留下的哨兵**（G1 所要的兜底）。*当前状态：阶段 D 未实现，P6 仅靠自身 DELETE。*
11. **自增值会前进，行删了值不复用**：P6 每轮消耗 1 个（实测 12→15）；B-2 的 5 轮也消耗（`loginAdd` / `friendAdd` / `goodstype` 三处 INSERT 各自计数）。故「P6 不留痕迹」不成立，报告须如实写「留自增计数前进」。
12. **N4 版本门禁 = TD-13 的补偿机制**（落盘时单独强调）：本机开发库与 VM 库同名，只验 `DATABASE()` 会让 8.0.32 静默通过，全部断言在错误数据上跑还全绿。`VERSION()` 判据实测接住：`DATABASE()=mall_management_sql ✓` 而 `VERSION()=8.0.32 ✗` → 拒。
13. **R12 已当场核对**：`concurrency-map.md §5` 第 5 行确为 `loginAdd` TOCTOU，与 H3 `R12` 一致；第 9 行为 `order.OrderId` 无唯一索引，与 A-S2a-1 的 `basis` 一致。

**待验证清单（⚠️ 落盘时不得省略）**

14. ⚠️ **P6 的 FAIL 分支未实测**：本轮仅代码审阅。本地构造不出（需后端连另一个库，须重启用户的后端）。故「HTTP 读回=true」有实测，其反向分支无实测。
15. ⚠️ **P5 就绪等待上限默认 60s 是本脚本取值**，H3 未规定；`--ready-timeout-sec` 可覆盖。落盘须写明，以免日后被当成 H3 的规定。
16. ⚠️ **P7 主判据（host）在本环境分辨力有限**：探针行于 P7 后即删，且本机是独立实例，故「探针行不在本机」实测为真。它对「本机 = 目标的隧道/端口转发」有效，对「有人把脚本指向本机」**无效**（后者由 P1 的版本门禁负责）。已把读取窗口留在删除之前以尽量保留其分辨力，但**该判据强度弱于锁 1 的表述**，落盘时如实标注。

**阶段 D 实测发现**

17. **S4b 性能退化 = `order.OrderId` 无索引 → CAS 全表扫（已登记 TD-14）**：`SHOW INDEX` 实测 `order` 只有 `PRIMARY(Id)`；三个 CAS 端点 WHERE 均以 `OrderId = ?` 起头，`EXPLAIN` 实测 `type=ALL`、`possible_keys` 空。同款 WHERE 的只读对照（16 线程 × 25，只变行数）：**6 行 p50=1ms → 6006 行 p50=51ms → 12006 行 p50=94ms**（12000 填充行测后已删，`order` 回到 6 行）。满负载档 `order` 约 1 万行 → CAS p50 由短档 5.5ms 涨至 **398.9ms**、达成 **68.3 QPS**（目标 120）。与 §5 行 9 是同一索引缺失的两个面（正确性 / 性能）。**A3 不加索引**。
    ⚠️ 上述 `398.9ms / 68.3 QPS` 出自**背压之前**的负载发生器：开环提交 + 无界队列 → 7200 条按计划 60s 提交完毕、后端只能吃 68 QPS，积压队列在提交结束后又排空约 45s，于是 `achieved` 被算成排空期均值、墙钟 105s（读起来像「按 68 QPS 跑」，真相是「按 120 QPS 提交、被测端只吃 68」）。已修：`paced` 加 `Semaphore` 背压，提交速率被压到可持续速率，`achieved` 才等于真实达速率；`line()` 同时打印 `offered` / `achieved` / `wall`，未达速时标 `←未达速（只记录，不门禁）`。
    **修后重测（2026-09-20 全量档，两步编译，runId=4f11e708）**：`offered=120 achieved=63.0QPS wall=114.3s p50=496.1 p95=779.7 p99=842.4 max=1120.6(ms)`，不变式违反=0、5xx=0。即：`achieved` 从 68.3 降到 63.0，**不是变差，是变准** —— 旧值把排空期的高速段平均了进去；新值是背压下的真实持续吞吐。p50 从 398.9 升到 496.1 是因为旧值同样被排空期稀释。**结论不变且更硬：同一份 `order` 表、同款 WHERE，120 QPS 不可达，瓶颈是索引缺失（TD-14），不是负载发生器。**
    **S4b 长档（60s）单独验收（2026-09-20，runId=cf8c2a6f，两步编译）**：`offered=120 achieved=96.2QPS wall=74.8s p50=292.4 p95=538.7 p99=625.6 max=860.1(ms)`，四条门禁断言全 PASS，不变式违反=0、5xx=0。
    - **两个数字的差是证据本身**：同一份脚本、同一档时长、同一端点配比，唯一实质差异是**窗口开始时 `order` 已有多少行** —— 全量档里 S4b 前面已跑过 S2a/S2b/S3/S4a（约 3700 行在手），单独档里从 6 行起、窗口内涨到约 6486 行。结果：**96.2 QPS / p50 292.4ms（从 6 行起）对 63.0 QPS / p50 496.1ms（从约 3700 行起）**。行数越多越慢，与只读对照的单调曲线同向，**再次指向 TD-14 的索引缺失**。
    - ⚠️ 该对比有一个未排除的混杂因素：全量档里 S4b 不是首段，后端与连接池已处于热态、且 JIT 已充分预热；热态本应**更快**而不是更慢，故混杂方向与观测差**相反**，不足以解释 96.2 → 63.0。但要下严格结论仍须把行数当唯一变量做对照（即前半的只读实验），本对比只能算**同向旁证**。
    - ⚠️ 仍待办：两次的 p50 都是**整个窗口的合并分位**，未按表大小分段；且 S4b 的 setup 一次预建 3600 条、负载期又 add 2880 条，窗口内行数始终在变。要「行数 → 延迟」的严格曲线，须另做只读对照。

18. **A3 级缺陷：单文件源码启动使中文断言静默失效（已根治，须单独落盘）**。严重性**高于任何单条断言**：它让判定本身不可信，且**不报错**。
    - 根因：`java Foo.java`（单文件源码启动）由启动器**在运行期就地编译**源文件，此时**不采用任何编译期 `-encoding`**，而按宿主平台默认字符集解读源码。Windows 上 `file.encoding=GBK` → 源中中文字面量全部失真。
    - 实测（同一份脚本、同一台机器，唯一变量是启动方式）：源码模式 + GBK 下 **S3 由「异常单=0 / A-S3-2 PASS」变成「异常单=10 / FAIL」**。受影响断言 = 一切比对中文响应体的项：`msg == 「订单不存在或已付款」`（A-S3-2）、`msg` 以「数据库错误」开头（A-S3-3）、`添加成功！`（B-2 `loginAdd`）。它们失真后**恒不命中**，于是 S3 判成「异常单=10」，而 A-S3-3 这类「不得出现」型断言反而**恒真**（假绿）。
    - 定位方式：B-2 首轮出现自相矛盾的数字（`goodstype` 重复行 10 行但「新增成功=0」、`loginAdd` 20/20 未判定）。拒绝猜测，加 `dumpSamples` + 非 ASCII 转义（`esc()`），做 A/B 对照（唯一变量 `-Dfile.encoding=UTF-8`）后确认：**响应体正确，是被编译进 class 的字面量失真**。
    - 修法（根治，非掩盖）：改启动方式为两步编译，`javac -encoding UTF-8 -d <out>` 后 `java -cp <out>:<driver>`。**不用 `-Dfile.encoding=UTF-8`** —— 它改的是 JVM 全局默认，会连 JDBC 与文件读取一起动，属于掩盖症状。run.sh 封装这两步，本地与 CI 同一套逻辑。
    - 运行期输出另接 UTF-8（`forceUtf8Output`）：编译期钉死的是**判定**，但 `System.out` 仍按平台字符集编码字节，中文日志会落成 GBK 字节（终端与 CI 都按 UTF-8 读 → 乱码；JSON 报告更是读不出来）。只约束「本程序写出去的字节」，不动 JVM 全局默认。
    - 守卫：`charsetSelfCheck()` 在**产生任何判定之前**用码点字面量与源码字面量比对，失真则 `exit 2` 拒绝运行，并把两边转义形式打进日志。位置在 `--cleanup-only` 之后、preflight 之前 —— 清理该让它跑，判定不许在失真前提下产生。
    - **影响范围已核：历史日志无假绿、无假红。** 两份含 S3 的日志（`a3_d2` / `a3_d_full`）均为「异常单=0 + A-S3-2 PASS」，说明那两次的中文比对确实命中了（源码模式下会显示 10）。⚠️ 遗留缺口：此前日志**不记录启动方式**，故「某次运行是哪种模式」无法事后判定，只能靠上述结果反推。已补：自检行现在把 `file.encoding` / `native.encoding` / 启动方式 / 自检结果写进每次运行的日志。
    - 附带教训（已写进代码注释）：`javac` 的 Unicode 转义预处理在词法分析**之前**，故 `\uXXXX` 即使写在**注释里**也会触发「非法的 Unicode 转义」编译错误。

19. **B-2 实测来源（`rounds=5 / reproRounds=5/5`）**：三处 check-then-insert 各 5 轮全中，共两次独立实测 —— ①背压/字符集修正前一次；②两步编译 + run.sh 下 `runId=f84fc015` 一次。两次逐轮数字一致：`loginAdd(5xx=9 成功=1 已存在=10 未判定=0)`、`goodstype` 重复行=新增成功数、`friendAdd` 重复行=申请成功数。
    - 三处约束不同、缺陷形态不同，故**信号极性不同**：`login/user/customerimage` 三表都有主键 → 撞键 `ERROR 1062` → 未捕获 → **HTTP 500**（信号=5xx>0，**与其它场景极性相反**：这里 5xx 是「缺陷复现」而非异常）；`goodstype`/`friend` 只有自增主键 → **插得出重复行**（信号=重复行≥2）。无全局 `@ControllerAdvice`，未捕获异常走 Spring 默认 500。
    - 每轮必须换新 `username`/`GoodType`/`friendPair`，否则第 2 轮起查重命中第 1 轮已写入的行，`loginAdd` 直接走「已存在」分支，不进并发窗口。三个 burst 之间**串行**，不混栅栏。
    - 默认 `--b-group-gate=false` → `A-B2-1` 落 `RECORD_ONLY`，不计退出码。刻意如此：**「本轮没复现」也可能只是竞态未重叠，不等于缺陷已修**，不该让一次没撞上的运行变红。显式开门禁时 `reproRounds>=1` → `EXPECTED_DEFECT_PRESENT`（PASS），`==0` → `DEFECT_FIXED`（FAIL，附「已修复 / 未重叠」两种可能）。
    - 附带发现已**登记为 TD-15**（不是只记录）：`loginAdd` 在 `usertype` 不等于「客户 / 商家」时返回 `添加成功！` 而**实际不插任何行**。curl 复现（该请求未带 `charset`），`login` 行数不变。影响不止数据丢失：它使「以响应体判成功」的测试口径在本端点失真，故 B-2 **同时用 JDBC 核对行数**，不能只数 `添加成功！`。

20. **流程记录：2026-09-20 已提前修改正文「运行形态」一节**（原因：旧表述 `java Foo.java` 是本次 A3 级缺陷的**成因**，留着不改会让后来者照错的方式启动、原样复现该 bug）。属于「修正明显错误」，不是「落盘待定结论」；除此之外本清单各条仍按「脚本跑通后一次性并入正文」的约定留在清单里，未提前散落。

21. **JSON 报告已落盘**（阶段 E 完成）。`scripts/concurrency/reports/a3-report-<runId>-<ts>.json`，UTF-8，手写 JSON（classpath 上没有 Jackson，不得引新依赖）。数据来源是 `REC` / `ASS` / `B2_ROUNDS` / preflight，**不解析 stdout 文本** —— 解析自己的打印串等于把「给人看的格式」变成契约。
    - **报告带什么**：`launch`（启动方式 + 平台字符集 + 自检结果，**这条同时补上了「历史日志不记录启动方式」的缺口**）、`target`、`options`、`preflight` 全项、`assertions`（含 C4 的 `rounds`/`reproRounds`，**未做多轮计数的落 `null`，不拿别的数凑**）、`gates`、`scenarios`（7 段 + `cleanup`）、`b2PerRound`（结构化逐轮，不反解文本）、`logFile`（指回本次运行的完整日志）、`exitCode`。
    - **报告不带什么**：S2a/S3/S4a 三段是栅栏型（burst）、没有 `Load`，故其取数是**手写 `rec()` 的头条计数**（如 `inconsistentOrders`），细粒度响应分类（哪几单、哪个 msg）只存在于断言 `detail` 与日志里。落盘时须如实写明这条边界，别让读者以为 JSON 里能查到全部证据。
    - 报告写失败**不改变退出码**：报告是证据，不是判定；判定已由门禁断言与退出码给出。若因磁盘满/无权限就把语义上通过的运行改成失败，是把两件事混为一谈。但绝不静默 —— stderr 明确报错，且 stdout 摘要行落 `<<未落盘>>（见 stderr）`。
22. **CI 集成已落地（阶段 E 收尾）**：`.github/workflows/ci.yml` 新增第三个 job `concurrency`（`needs: backend`；job 级 `concurrency` 覆盖 workflow 级的 `cancel-in-progress: true`，因**被砍断的测量没有报告、日志也是截断的**，是最难排查的一种红；`continue-on-error: true`）。关键约定与理由：
    - **首跑是观测跑，不是门禁**。A-S1-1 / A-S2b-1 / A-S4b-1 断言的是**传输层**（「5xx == 0 且连接错误 == 0」「success 比率 100%」），而 CI 把压测器、后端、MySQL 挤在同一台机器上（本地是「压测器 + 后端在本机、MySQL 在另一台 VM」），存在**资源性假红**的可能。反向的错法更坏：为迁就 CI 下调 `--qps` 会让 TOCTOU / 锁争用类缺陷**不复现 → 假绿**。故先观测、存档，门禁地位待首跑数据再定。`continue-on-error: true` 使该 job 显示**黄色警告而非绿**，含义正是「绿不等于通过」；判定看报告里的 `gates.failed` 与 `exitCode`。
    - **镜像必须 `mysql:8.4`**：P1 是硬门禁，要求 `VERSION()` 以 `8.4.` 开头（`8.0` / `latest` 必红）。驱动 8.0.33 × 服务端 8.4 的搭配本身是被测环境的一部分，不许「顺手升级」。
    - **导入用容器自带的 `mysql` 客户端**（`docker exec -i ${{ job.services.mysql.id }} mysql ...`），不用 runner 预装的：预装版本不受控（当前 8.0，将来可能变），而导入应与 server 同版本。VM 形态 `echo 123456 | sudo -S -p '' mysql` 里的 sudo 是因为那里 `root@localhost` 是 `auth_socket`、root 无法 TCP 登录；容器里 root 可用口令连接，**别把 sudo 形式抄过来**。
    - **`test` 账号刻意保留 `GRANT ALL ON *.*`**：那是 VM 上已登记的越权现状，CI 复刻之；在 CI「顺手修好」会让 CI 测的环境与 A3 记录的环境不是同一个。
    - **`--skip-negative-control` 是必需的**，理由见下方发现 ③。**`--ready-timeout-sec 180`**（默认 60 的三倍）是 CI 与本地命令的一处**刻意不同**：P5 超时是 FAIL 不是 skip，值给小了会把「GHA 冷启动慢」变成假红 —— 将来比对 CI 与本地 QPS 时要知道这一条。另有预热步骤（等就绪最多 120s 后打 5 个请求），只削通用路径（DispatcherServlet / Hikari / JIT）的冷启动峰，替代不了 S1 自己的爬坡。
    - 本阶段**零 Java 改动**。三条读码发现：① `--reset-db` 是死参数 → 登记 **TD-16**，不在本阶段实现；② `run.sh` 里 `--log-file` 的注释与代码相反（注释称「调用方优先」，实际是 run.sh 覆盖调用方）→ 已改注释，**未改行为**；③ **CI 上 P7 会硬失败**：`negativeControl()` 第一段就是「阴性对照库与目标库是同一主机 → FAIL 对照无意义」并按 exit 2 短路，而 CI 里目标库本就是 `127.0.0.1` 上的 service container，必然命中 —— 那条「本机不可达自动 skip」的路在 CI 不成立。对照要证明的是「目标不是开发机上那个同名库」，CI 里不存在第二个库，无对象可对照；OD-4 在 CI 仍由 P2（主机串比对）+ P6（哨兵往返，证明后端与脚本同库）兜住。
    - 顺带更正一处**过期注释**：`backend` job 里「MySQL service 计划在 A3 阶段引入」已改为「落在 concurrency job；本 job 至今不需要 MySQL」。
