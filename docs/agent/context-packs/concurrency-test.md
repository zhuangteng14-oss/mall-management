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
| 15 | H3 / H4 / H5 / H6 正文 —— **已回填**（见下节）：含 **4 处与已落盘冲突（C1–C4，一律以已落盘为准）**、**2 处 H3 自身缺口（G1–G2）**、**7 项待裁决（D1–D7）** | 正文**存在于本地转录**（`5574d7b3` 第 134 行，27208 字符，经 `json.loads` 解出）。原判「从未落盘」**为误** —— 根因见下节 |

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
11. **`goodstype.AUTO_INCREMENT` 会前进**：P6 每轮消耗 1 个自增值（实测 12→15），行删除后值不复用。故「P6 不留痕迹」不成立，报告须如实写「留自增计数前进」。
12. **N4 版本门禁 = TD-13 的补偿机制**（落盘时单独强调）：本机开发库与 VM 库同名，只验 `DATABASE()` 会让 8.0.32 静默通过，全部断言在错误数据上跑还全绿。`VERSION()` 判据实测接住：`DATABASE()=mall_management_sql ✓` 而 `VERSION()=8.0.32 ✗` → 拒。
13. **R12 已当场核对**：`concurrency-map.md §5` 第 5 行确为 `loginAdd` TOCTOU，与 H3 `R12` 一致；第 9 行为 `order.OrderId` 无唯一索引，与 A-S2a-1 的 `basis` 一致。

**待验证清单（⚠️ 落盘时不得省略）**

14. ⚠️ **P6 的 FAIL 分支未实测**：本轮仅代码审阅。本地构造不出（需后端连另一个库，须重启用户的后端）。故「HTTP 读回=true」有实测，其反向分支无实测。
15. ⚠️ **P5 就绪等待上限默认 60s 是本脚本取值**，H3 未规定；`--ready-timeout-sec` 可覆盖。落盘须写明，以免日后被当成 H3 的规定。
16. ⚠️ **P7 主判据（host）在本环境分辨力有限**：探针行于 P7 后即删，且本机是独立实例，故「探针行不在本机」实测为真。它对「本机 = 目标的隧道/端口转发」有效，对「有人把脚本指向本机」**无效**（后者由 P1 的版本门禁负责）。已把读取窗口留在删除之前以尽量保留其分辨力，但**该判据强度弱于锁 1 的表述**，落盘时如实标注。

**阶段 D 实测发现**

17. **S4b 性能退化 = `order.OrderId` 无索引 → CAS 全表扫（已登记 TD-14）**：`SHOW INDEX` 实测 `order` 只有 `PRIMARY(Id)`；三个 CAS 端点 WHERE 均以 `OrderId = ?` 起头，`EXPLAIN` 实测 `type=ALL`、`possible_keys` 空。同款 WHERE 的只读对照（16 线程 × 25，只变行数）：**6 行 p50=1ms → 6006 行 p50=51ms → 12006 行 p50=94ms**（12000 填充行测后已删，`order` 回到 6 行）。满负载档 `order` 约 1 万行 → CAS p50 由短档 5.5ms 涨至 **398.9ms**、达成 **68.3 QPS**（目标 120）。与 §5 行 9 是同一索引缺失的两个面（正确性 / 性能）。**A3 不加索引**。
    ⚠️ 上述 `398.9ms / 68.3 QPS` 出自**背压之前**的负载发生器：开环提交 + 无界队列 → 7200 条按计划 60s 提交完毕、后端只能吃 68 QPS，积压队列在提交结束后又排空约 45s，于是 `achieved` 被算成排空期均值、墙钟 105s（读起来像「按 68 QPS 跑」，真相是「按 120 QPS 提交、被测端只吃 68」）。已修：`paced` 加 `Semaphore` 背压，提交速率被压到可持续速率，`achieved` 才等于真实达速率；`line()` 同时打印 `offered` / `achieved` / `wall`，未达速时标 `←未达速（只记录，不门禁）`。**修后的达速率待下一次运行重测**。
