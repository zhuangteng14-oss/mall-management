# context-pack: concurrency-test

## 任务

开发 120 QPS 并发测试脚本，验证多线程场景下的业务正确性。

## 必读文档

- docs/agent/project-map.md §6（N+1 清单，并发脚本要避开这些干扰）
- docs/agent/concurrency-map.md（现有并发现状；**重点 §5「缺失的并发防护清单」——A3 的断言目标即此清单**）
- docs/agent/db-schema.md（表结构与索引，**§2 索引现状**）
- docs/agent/tech-debt.md（**TD-01 ~ TD-17**，以及「已完成里程碑」M1）
- docs/agent/environment.md（A3 的 VM / MySQL 环境表与安全提醒）
- docs/agent/business-decisions.md（BD-01 ~ BD-03）

## 必读源码

- Mall-management-server/src/main/java/com/minke/mallmanagement/order/controller/OrderController.java（订单路由）+ `order/service/OrderService.java`（订单核心逻辑：`orderAdd` / `orderPayIfUp` / `orderReceiveIfUp` / `orderDeliveryIfUp`）
- Mall-management-server/src/main/java/com/minke/mallmanagement/user/controller/UserController.java（**仅路由**，三层第三笔后）+ `user/service/UserService.java`（业务体，`loginAdd` 的 `@Transactional` 在此）+ `user/dao/UserDao.java`（全部内联 SQL，含 `loginAdd` 的三表写入）
- Mall-management-server/src/test/java/com/minke/mallmanagement/smoke/AbstractSmokeTest.java（参考基类设计）

> 路径注意：主源与测试源**现已统一**在 `com/minke/`（2026-10-02 笔 1 挪包与笔 2 改名之前，主源是 `com/minke/mallmanagement_controller/`「**无 `server` 段**」、测试源是 `com/minke/mallmanagementserver/`，两者不一致）。下文凡引用旧路径者均属**历史记录**。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-02 实测**；重构后须重测。用户域旧 `U:<n>` 为三层前 264 行版 `UserController` 的历史坐标（2026-10-02 第三笔 `0c07e96` 后已重指）。

## 前置准备

### 目标环境：独立 MySQL 实例 + 同名库（Q1=B 定案）

A3 **不复用本机开发库，也不是「切库名」**，而是**另起一个 MySQL 实例，库名仍叫 `mall_management_sql`**。理由（TD-13，实测）：

- `GoodsController`（23 处）与 `UserController`（16 处）【⚠️ **2026-10-02 三层第三笔后：user 那 16 处已全在 `UserDao`**；goods 那 23 处已拆为 goods 14 + order 9，见下】把 schema 名 `mall_management_sql.` **硬编码**在 SQL 语句里（合计 39 处；`MessageDao` 为 0 处）。切库名切不走这 39 处写入，而 `/message/*` 会跟随 datasource —— 同一进程内**脑裂**。（⚠️ 2026-10-02 复测：合计仍 **39 处**（口径 = SQL 字面量），但已散到 **7 个文件** —— `UserDao` 16 / `GoodsDao` 6 + `GoodsCacheService` 5 + `GoodsTypeCacheService` 3（goods 14）/ `OrderDao` 7 + `OrderStateMachine` 1 + `OrderTimelineWriter` 1（order 9）。`GoodsController` 那 23 处含**订单端点的 SQL 9 条**，订单笔拆出后 **23 = goods 14 + order 9** ⇒ 文件数 2 → 7、**总数不变**。逐处口径与证据见 `three-layers.md §F#1`；原记「45 处」是**口径混用**所致（商品侧取 SQL 字面量、订单侧连 javadoc / 注释一起数）。）
- 故隔离只能靠**独立实例 + 保留同名库**。这同时意味着 `SELECT DATABASE()` **无法**区分「本机开发库」与「VM 库」（两者库名相同），必须叠加 `VERSION()` 门禁 —— 见 §脚本内置校验的 P1 与 N4。

> ⚠️ **本节原先写的「本地建 `mall_management_test` 库 + `sed` 过滤导入 + 切 `DB_URL`」三步已作废**（2026-09-20 重写）：按 Q1=B 与 TD-13，那三步均不成立 —— 切库名切不走硬编码，且「脚本的 JDBC 断言」与「后端的读写」必须在同一个实例上才谈得上互相印证。
> 代价是本地需要第二个 MySQL 实例（VM）。这是**刻意的**：被测环境必须与开发环境分离，否则 A3 与日常开发互相污染。

### VM 实例（`192.168.229.129`）、导入与账号

| 项 | 值 |
|---|---|
| 实例 | VMware `D:\VMware\VMs\Ubuntu-MySQL-A3.vmx` @ `192.168.229.129` |
| SSH | `ubuntu` / `123456` |
| MySQL | `test` / `test123`（`test@'%'`，服务端 **8.4.11**） |
| 库 | `mall_management_sql`，**12 张表**（原记 11；B3 于 2026-09-29 追加 `order_state_transition`） |

**S0-1 导入命令（定稿）**：

```bash
# 先把 mall_management_sql.sql 上传到 guest，再：
echo 123456 | sudo -S -p '' mysql < /tmp/mall_management_sql.sql
```

- **必须 `sudo`**：guest 上 `root@localhost` 是 `auth_socket`，root **无法用口令走 TCP 登录**，故 `mysql -uroot -p` 形式不可能。
- **不需要 `sed` 过滤**：文件自带 `CREATE DATABASE mall_management_sql` + `USE`，与目标库名一致（TD-12）—— 这一点与上面那张已作废的「本地建 `mall_management_test`」场景**相反**。
- ⚠️ 该文件含 **11 条 `DROP TABLE IF EXISTS`** → 只能用于 **VM / CI 的专用实例**，**绝不可指向有真实数据的库**。清库至今只有这一条路：脚本的 `--reset-db` 是**死参数**（声明了破坏性语义却不执行任何动作，见 TD-16）。

**S0-2 账号与权限（登记，不改）**：`test@'%'` 存在（`caching_sha2_password`），实际权限是 `GRANT ALL ON *.*`（含 DELETE）；`test@'localhost'` 不存在；DELETE 实测通过 → 脚本的 cleanup 可行。**该越权已登记为技术债**（`test` 本应最小权限，本地/CI 因 cleanup 放 ALL，转生产前必须收紧）；A3 **不改它**，且 CI **刻意复刻同一越权** —— 否则 CI 测的环境与 A3 记录的环境不是同一个。

**vmrun 通道配方**（将来重做 VM 操作时复用）：

- `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'` —— 否则 Git Bash 会把 `/bin/sh` 改写成 Windows 路径，`runProgramInGuest` 一律报「未找到文件」。
- **vmrun 不回传 guest 的 stdout** → 把输出落到 guest 文件，再 `CopyFileFromGuestToHost`。
- `getGuestIPAddress` 的输出**含 CRLF**，不要用 `read -r` 捕获（会把 `\r` 并进路径）。

### 启动验证（S0-5，2026-09-20 已实测）

**不预设结论**：驱动 8.0.33 能否连 MySQL 8.4，是实测出来的。四步与实测结果：

| 步 | 动作 | 实测结果 |
|---|---|---|
| 1 | `SPRING_PROFILES_ACTIVE=a3` 起服务，grep 日志里 `HikariConfig` 的 `jdbcUrl`。**本项目启动期 Hibernate 初始化即触发连接**，配置块在 `Tomcat started` **之前**已打印，**不需要额外请求**；若日后按 TD-01 移除 `spring-boot-starter-data-jpa` 致 Hikari 不再被触发，再改回「先打一个请求」 | 日志出现 `The following 1 profile is active: "a3"`；`jdbcUrl` 指向 `192.168.229.129:3306/mall_management_sql` |
| 2 | 看 `ImgPath` 打印的 `目前图片所在本地位置为：` | 解析为 `<java.io.tmpdir>/a3-uploads/`，且该目录由 `Files.createDirectories` 自动创建 |
| 3 | `SELECT VERSION()` | **`8.4.11-0ubuntu0.26.04.1`** —— 只验「能连上」不够：8.0.33 驱动连 8.0.x 也通，会让人误以为连的是 8.4 |
| 4 | 宿主 JDBC 用 `pom.xml` 声明的 8.0.33 直连 | **连通 → 无需兜底，`pom.xml` 未改** |

> ⚠️ **第 1 步的时机已更正（2026-09-20）**：原写「Hikari **懒初始化**，配置块在**第一次 `getConnection()`** 时才打印，服务启动日志里没有 —— 要用日志确认连的是哪台库，**必须先打一个请求**」，**该判断是错的**（用户指出，与 `worklog-2026-09-20-a3.md` 同一处更正）。实测本项目**启动期 Hibernate 初始化就会触发连接**，故 `HikariConfig` 块在 `Tomcat started` **之前**已打印，**不需要额外请求**。
> 「先打一个请求」仍作为**备用路径**保留：TD-01 建议移除未使用的 `spring-boot-starter-data-jpa`（全部数据访问走 `JdbcTemplate`），届时 Hikari 不再被 Hibernate 触发，那一步才真的需要 —— 两种未来状态下这一行都说得通，故不删。

### CI 环境

CI 侧对应的是 `concurrency` job 的 **service container `mysql:8.4`**，库名同为 `mall_management_sql`，导入走**容器自带的 `mysql` 客户端**。详见 §CI 集成。

## 约束

- 目标 120 QPS
- 必须有业务正确性断言
- 用 Java 自写，不引 JMeter/Gatling，**也不引任何新依赖**：JDK 内置 `java.net.http.HttpClient` + `ExecutorService` 已足够；不修改 `pom.xml`
- 测试运行需 MySQL：**backend job 至今零 MySQL**（全量 `mvn test` 保持快速），MySQL 落在 **concurrency job** 的 service container
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
    「异常单=10 / FAIL」，且不报任何错（本轮完整记录见 `docs/agent/archive/worklog-2026-09-20-a3.md`）。
    **本节是当时提前落盘的**（2026-09-20），不适用「清单跑通后一次性并入正文」的约定 ——
    旧表述 `java Foo.java` 正是上述缺陷的**成因**，留着不改会让后来者照错的方式启动、原样
    复现该 bug，属「修正明显错误」而非「落盘待定结论」；清单其余各条当时仍留在清单里，未提前散落。
  - **负载走 HTTP、断言与清理走 JDBC（H4 定案）**：负载体只用 JDK 内置的 `java.net.http.HttpClient`；而断言与 cleanup **必须**直连库 —— 全项目没有删除接口（`order` / `login` / `user` / `goodstype` / `friend` 五表都没有），「清理无 HTTP 通路」是 JDBC 不可替代的决定性理由。
  - **不依赖项目 classpath**：脚本不引项目里的任何类，只需要**一条** mysql-connector jar（见下）。故仍与 Maven 工程解耦；但**并非完全不碰 Maven** —— 驱动不在下面那条定稿路径上时，`run.sh` 会用 `dependency:build-classpath` 回退解析（仅此一处用到 Maven）。
  - **驱动（D3 定稿）：单条路径、不 glob** —— `~/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar`。glob 必然取错版本：`mysql/mysql-connector-java/*` 下**只有 8.0.28**（`pom.xml` 声明的坐标因 relocation 没有 jar），而 `com/mysql/**` 下 8.0.31 与 8.0.33 并存、且各版本都带 `-sources.jar`。回退解析后**校验「major=8 且恰好 1 条」**，选不到就报错退出，不猜。
  - 不进 `surefire`，**`mvn test` 不会跑它** —— 避免 A2 定的「全量 `mvn test`」把每次 CI 都拖成必须起 MySQL
  - CI 侧**已新增独立 job**（带 `mysql` service）跑此脚本；**原 backend job 保持零 MySQL、全量快速**（已落地，见 §CI 集成）

## 脚本内置校验 P0–P7（先证明「跑的是对的东西」，再谈结论）

A3 的结论只有在「确实压到了目标环境」时才成立。故脚本自己先做七道校验；**硬门禁失败即不继续**，且退出码 `2` 专用于「环境不对」，与「断言失败」区分开。SKIP 是**级联状态**（前一环没过，后一环无从谈起），不是「通过」。

| # | 校验 | 判据（实测于代码） | 性质 |
|---|---|---|---|
| P0 | 静态长度检查（**13 列**） | 逐列与**该列自己的上限**比对，只查长度，**不约束 runId 格式** | 门禁 |
| P1 | 连通 + 库名 + 服务端版本 | `DATABASE()` 必须是 `mall_management_sql` **且** `VERSION()` 必须以 `8.4.` 开头 | **硬门禁** |
| P2 | 主机串比对 | `--expect-db-host` 与 `jdbcUrl` 里的 host 字面量相等 | 门禁（不符即 `exit 2`） |
| P3 | 表数量 | **12 张** = `11` + `timelineTable`（B3 的 `order_state_transition`）。原记只写「11 张」；**脚本本身是对的**（`ConcurrencyRunner:2518` 断言 `11 + timelineTable`），是**本表漏了 `+1`**（兼作 TD-01 校验：`ddl-auto=update` 启动后未反向改表） | 门禁（库脏即拒） |
| P4 | 种子计数 | `order=6` / `goods=5` / `login=5` / `user=5` / `friend=4` | 门禁（库脏即拒） |
| P5 | 就绪探测（`GET /health/db`） | 响应含 `"success":true`。该端点**永不被缓存**，故仍能同时证明 Web + DB（见下「2026-09-27 改版」） | 门禁：**超时是 FAIL，不是 skip** |
| P6 | **库身份对比** | `GET /health/db` 报的 `@@server_uuid` == 脚本 JDBC 查到的；`hostname` 不一致只告警，`database`/`port`/`version` 只报告 | 门禁 |
| P7 | 阴性对照 | 本机库的 `@@server_uuid` ≠ 后端报的 `server_uuid`；本机不可达或无同名库记 `SKIP` | 常态只读，不改本机库 |

**顺序上先 P2 再 P1**（代码里就地写了理由）：本机开发库与 VM 库**同名**（TD-13），只看到 `DATABASE()=mall_management_sql` 无法区分是哪台机器，故必须先过主机门禁。级联：P1 一挂，P3 / P4 记 `SKIP`；P5 一挂，P6 / P7 记 `SKIP`。

**P0 的 13 个列**（长度-only：严格模式下超长即 `ERROR 1406`）：
`goodstype.GoodType`(20)、`login.UserName`(20)、`user.username`(20)、`customerimage.UserName`(20)、`customerimage.CustomerId`(20)、`goods.MerchantId`(20)、`order.OrderId`(40)、`order.CustomerId`(40)、`order.MerchantId`(40)、`order.ProductInformation`(500)、`order.Images`(100)、`friend.customerid`(50)、`friend.friendid`(50)。
> **2026-09-27：14 → 13，只删了一项** —— 原第 7 项「P6 探针 `goodstype.GoodType`(20)」。P6 不再是哨兵往返，脚本不再写任何 `A3P-` 行，该列自然不再是「会实际写入的取值」，留着就是**恒真的死检查**。其余 13 项一字未动。
> 计数口径踩过坑：**不能**把各列的最长串直接与 `char(20)` 比 —— `order.OrderId` 是 `varchar(40)`，它的 22 字符完全合规，混在一起讲会误导读者以为 P0 失效。后两列（`ProductInformation` / `Images`）是阶段 D 才补入的，此前不在表内，而 `orderAdd` 的入参恰是它们（即「12 列」时代的漏检）。

**N4 —— `VERSION()` 门禁是 TD-13 的补偿机制**（落盘时单独强调）：本机开发库与 VM 库同名，只验 `DATABASE()` 会让 8.0.32 **静默通过** —— 全部断言在错误的数据上跑，还**全绿**。该判据实测接得住：`DATABASE()=mall_management_sql ✓` 而 `VERSION()=8.0.32 ✗` → 拒。**A-S4a-7 依赖的正是 8.4 实测的隔离级别行为**（口径见 §判定口径），故这条门禁不是洁癖。

**P7 判据修正（2026-09-27 改）**：改为 **`@@server_uuid` 对比** —— 本机库的 `server_uuid` 必须 ≠ `/health/db` 报的 `server_uuid`。相同即说明后端连到了开发机上的同名库（TD-13）。本机不可达、或本机无同名库（驱动抛 `Unknown database`，走同一个 `SQLException` 分支）→ 记 `SKIP`（不阻断），`--skip-negative-control` 亦记 `SKIP`。**本机库全程只读** —— A3 未获授权改动本机库，阴性对照不需要写。
⚠️ **同一主机会硬失败**：若阴性对照库与目标库同主机，直接判 `FAIL`（「对照无意义」）并按 `exit 2` 短路 —— 这就是 CI 必须传 `--skip-negative-control` 的原因（见 §CI 集成）。**2026-09-27 改版后这条不变**：新 P7 的第一段仍是同一判断。

> **原 P7 的两条判据为何被换掉**
> - 主判据「哨兵行不在本机」：随哨兵机制一起删除 —— P6 不再写行后 `rowAbsent` 会退化成**恒真**，正是脚本原注释自己警告过的失效模式。
> - 辅判据「本机 `VERSION()` ≠ 目标」：本机升级到 8.4 后自动失效（2026-09-27 本机实测 `8.0.32`，尚有效但已在倒计时）。
> `server_uuid` 不会烂掉：它在 MySQL 初始化数据目录时生成、全局唯一，**克隆虚拟机也不会重复**。

**P6 改版：从「哨兵往返」到「库身份对比」（2026-09-27）**

原方案：JDBC 写一行 `A3P-<runId8>` → `GET /goods/goodstype`（当时前缀 `/mysqlGoods`）读回 → 删除，证明「脚本写的」与「后端读的」是同一个库。命名曾按 `goodstype.GoodType CHAR(20)` 的上限反复调整（`A3P-<runId8>`=12 字符塞得进；`A3TEST-<runId8>-PROBE`=21、H3 原式 `A3PROBE-<runId8>-<host>`=32 都塞不进）。

**该方案的前提是「HTTP 读回来的内容 == 库里的内容」，而 B1 给这个端点加了缓存，前提破裂**：脚本用裸 JDBC 直写哨兵行（绕过应用），再从 HTTP 读回时拿到的是**缓存填充时刻的旧列表**，哨兵行必然不在其中 → 结构性 FAIL，重跑多少次都一样。这不是偶发，是设计冲突。

新方案：`GET /health/db`（`@Profile("a3")`，**永不被缓存**）报出后端自己的 `@@server_uuid` / `@@hostname` / `DATABASE()` / `@@port` / `VERSION()`，与脚本 JDBC 侧查到的逐字段比。**不依赖任何被缓存的数据，故与缓存永久正交。**

判定分层，依据是**实测的判别力**（两台机器的实测值）：

| 字段 | 性质 | 为什么 |
|---|---|---|
| `@@server_uuid` | **主判据** | 纯 GLOBAL 变量（MySQL 拒绝以 `@@session` 读取），同实例上任何连接、任何用户取到的值必然相同 —— 脚本侧与后端侧一致是**变量语义决定的必然**，不靠巧合 |
| `@@hostname` | 辅判据（不一致只告警） | 是 `gethostname()`，**克隆 VM 可能保留原主机名** |
| `DATABASE()` | 参考项，不判定 | VM 与本机**必然相同**（都叫 `mall_management_sql`，TD-13），判别力为零 |
| `@@port` | 参考项，不判定 | 两台都是 `3306`，判别力为零 |
| `VERSION()` | 参考项，不判定 | 随本机升级到 8.4 即失效 |

**P6 顺带不再写库**：原方案每轮消耗 1 个 `goodstype` 自增值（「P6 不留痕迹」不成立，见 §命名空间与清理）；改版后 P6 全程只读，该差额已消失。

⚠️ **P6 的 FAIL 分支未实测**（改版前后一致）：本地构造不出（需后端连另一个库，须重启后端）。故「一致 → PASS」有实测（`runId=506a0997`，2026-09-27 全绿），其**反向分支仍无实测**。
⚠️ **P5 的就绪等待上限默认 60s 是本脚本的取值**，H3 未规定；`--ready-timeout-sec` 可覆盖（CI 用 180 —— 那是**刻意不同**）。落盘写明，以免日后被当成 H3 的规定。

## 命名空间与清理

- **行数据命名空间**：`A3TEST-<runId8>`。种子 `OrderId` 形如 `ZHJC53759811`（4 大写字母 + 8 数字），与 `A3TEST-` 前缀**零碰撞**。
- ~~**哨兵行**：`A3P-<runId8>`~~ —— **2026-09-27 起脚本不再产生任何 `A3P-` 行**（P6 改库身份对比，见上），该前缀连同其 20 字符长度约束一并成为历史。
- **cleanup 双规则（已实装）**：① `A3TEST-<runId>%` —— 按表、按列 `LIKE` 删除，逐表把「首列命中行数」与「删除数」记进报告；② **`A3P-%` 全局清扫，不带 runId** —— 前缀是代码字面量，正常数据不可能命中。**语义已于 2026-09-27 变更：从「清本轮哨兵」→「清旧版脚本残留」**；本轮不再写入该前缀，规则保留纯为兜底（曾用旧版脚本跑过、又被 SIGKILL 留下的行仍能被后续 run 自愈）。这正是 H3 缺口 G1 的兜底：H3 只清 `A3TEST-<runId>%`，哨兵前缀不在其覆盖范围内。
  - 删除语句命中数是**合计**（同一行可能被多列命中），报告如实标注「非去重行数」。
  - **清理失败必须进报告**（`cleanup.status=FAILED`）：报告说「跑完了」而库里有残留，是最容易骗过下一位读者的组合。
  - `--keep-data` 跳过清理（命名空间内的行保留供人工查验）；`--cleanup-only` 只清理、不产生流量、退出码 0。
- **清理与 P4 计数互斥**：`--cleanup-only` 必须在 preflight **之前**执行 —— 曾经的顺序是「先 preflight 再 cleanup」，结果 P4 的基线行数检查会把清理**挡在门外**（清理是为了让 P4 能过，却被 P4 拦住）。故 **两个 A3 运行绝不可同时作用于同一实例**。
- ⚠️ **自增值会前进，行删了值不复用**：B-2 的 5 轮消耗（`loginAdd` / `friendAdd` / `goodstype` 三处 INSERT **各自计数**）。报告须如实写「留自增计数前进」—— 这个差额是 P4「库脏」判据必须容忍的部分，不是有人手工改了数据。
- **P6 不再写库，故不留自增痕迹**：原 P6 每轮消耗 1 个 `goodstype` 自增值（实测 12→15），旧文档据此写下「P6 不留痕迹」不成立 —— 该结论**已随哨兵机制作废**。2026-09-27 改版后 P6 全程只读，`goodstype` 的自增只由 B-2 的五轮驱动。

## 验收标准

4 个并发场景：只读（S1）、下单（S2）、支付（S3）、混合（S4）。每场景跑通并输出报告。

> **场景计数口径澄清**：H3 在各场景下设子相位，**实际负载段落为 6 个** —— S1、S2a、S2b、S3、S4a、S4b。此前「6 场景」的说法即源于此，与「4 场景」**不矛盾**。

**A. 预期通过（回归证明 —— 证明现有 CAS 有效）**

| 断言 | 依据 |
|---|---|
| 无重复支付：并发对同一 `OrderId` 调 `orderPayIfUp`，恰好 1 次 `success:true`，其余返回「订单不存在或已付款」 | `concurrency-map.md` §3（路由 `OrderController:100` → `OrderService.payIfUp:179`；条件 UPDATE 的 `WHERE … AND PayIf = 0` 由 `OrderStateMachine` 生成 —— 守卫 `:90-91`、整句 `updateSql():123-127`。**2026-10-02 实测**；原记 `G:527`/`G:530` 已失效） |
| 无非法状态流转：未付款发货/收货、重复发货、已收货后发货 —— 全部被拒 | `concurrency-map.md` §3（`OrderService.receiveIfUp:185` / `deliveryIfUp:191`；两条守卫在 `OrderStateMachine:93-100`。**2026-10-02 实测**；原记 `G:549`/`G:571` 已失效） |

> ⚠️ **A 组的前置条件：S3 / S4 必须自建订单**（F4）。`mall_management_sql.sql` 的 order 种子 6 行（Id 18–23）**全部为 `(1,1,1)` 终态**，而 `orderPayIfUp` 的 WHERE 是 `PayIf = 0` → 在种子订单上「恰好 1 次 `success:true`」**必然不成立**，会让 A 组第 1 条断言必败。修正对象是**支付 / 发货 / 收货**三处（即 S3 与 S4），**S2 不在此列** —— S2 只调 `orderAdd`，订单由被测端点自身创建，不存在「复用种子单」的问题（**上轮曾把 S2 一并写入，是笔误**）。
>
> **行号出处**：上表三个 CAS 端点的行号已于 2026-09-20 逐条回原文核实（`@PostMapping` 在 `G:527` / `G:549` / `G:571`，条件 UPDATE 的 SQL 在 `G:530` / `G:552` / `G:574`）。原文曾引 `G:524` / `G:546` / `G:568`，**整体偏移 3 行**，疑似引自更早的修订 —— 已按实测更正。
> ⚠️ `concurrency-map.md` §3 仍引旧行号：**已登记待修，不擅改**（那是别的文档）。

**B. 预期暴露缺陷（A3 的真正价值：把已有的纸面缺陷变成实测复现）**

| 断言 | 预期结果 | 依据 |
|---|---|---|
| 并发同参 `orderAdd` 不产生重复 `OrderId` | ~~**预期失败**~~ → ✅ **PASS**（2026-09-30 S1a 翻转） | ~~`concurrency-map.md` §5 第 9 行（`order.OrderId` 无唯一索引，主键是自增 `Id`）~~ → §5 第 9 行**已标记「已消除」**（`1e2fcfa`）；本断言同日拆为 **`A-S2a-1a`（锁指标增量 == 20）/ `1b`（rows == 1）/ `1c`（msg 无「数据库错误」）**，见 `cache-layer.md:233` |
| 并发同参 `loginAdd` **不产生 HTTP 5xx** —— **不得**写成「不产生重复行」 | **预期失败**（信号 = **5xx > 0**） | `concurrency-map.md` §5 第 5 行（`UserDao.existsUserName:119` → `UserDao.insertUserCustomer:170` TOCTOU）。三表 `login` / `user` / `customerimage` **都有主键**（`UserName` / `username` / `UserName`）→ 重复行**不可能**产生；真实缺陷是未捕获的 `DuplicateKeyException` → HTTP 500（`UserService.loginAdd:125-160` 无 try/catch）。**此行的信号极性与其它各处相反**：这里的 5xx 是「缺陷复现」而非异常 |
| 并发同参 `friendAdd` / `goodstype`(POST) 不产生重复行 | **预期失败**（信号 = **重复行 ≥ 2**） | `concurrency-map.md` §5 第 6–8、10 行（TOCTOU；`friend` 仅 PK `id`、`goodstype` 仅 PK 自增 → 两表可**真的插出重复行**） |
| 并发下无死锁 / 无锁等待超时（MySQL 1213 / 1205） | 待实测 | 当前 `order` 仅 6 行种子数据，全表扫描代价极低，**小表上绿色不构成有意义的证据**，需在大数据量下复测 |

**C. 不可断言**

- ~~无超卖~~ —— 已核对 `mall_management_sql.sql:54-64`：`goods` 表**无任何库存/数量列**，全项目也无扣减逻辑，**该断言没有对象**。以 B 组第 1 行的「无重复 `OrderId`」替代。

**D. 脚本自身设计约束**

- 场景优先选 `orderAdd` 系列：`Images` 是字符串入参，**不落盘**，天然规避 TD-08 的污染；避免走 `goodsAdd` 的 `MultipartFile` 分支。
- 预置数据用命名空间隔离（`A3TEST-<runId8>`），**不改 `mall_management_sql.sql`**，跑完按**双规则**清理 —— 详见 §命名空间与清理。
- 脚本直查 `CreateTime` / `SettlementDate` 须按**字符串**处理（两者均为 `varchar(50)`），**不得**按 TIMESTAMP 比较（F6，已核实 `SQL:152-153`）。
- QPS 达标与业务断言**解耦**：CI runner 性能抖动大，建议 QPS 只记录、不设门禁，**门禁只卡业务断言**，否则 CI 会因性能波动随机变红。

### 判定口径（verdict）与门禁

| verdict | 含义 | 谁用 |
|---|---|---|
| `PASS` | 断言成立 | A 组全部；B 组「预期失败」的反面 |
| `EXPECTED_DEFECT_PRESENT` | 断言**失败**，且失败形态与登记缺陷一致 —— B 组的「预期失败」就是它 | B 组 |
| `DEFECT_FIXED` | 预期要失败却没失败 | B 组 |
| `RECORD_ONLY` | 只记录，不计门禁 | B-2 默认、B-3 |
| `SKIP` | 前置不可达导致的未执行（如 P7 阴性对照无对象可对照） | 前置项/断言 |

- 枚举**用下划线不用连字符**（`EXPECTED_DEFECT_PRESENT`，非 `EXPECTED-DEFECT-PRESENT`）；H3 原文的 `SKIPPED` 是把**断言 verdict 与前置状态混用**，见附录 A 冲突表 C3。
- **门禁分层**：A 组**恒门禁**（A 是回归证明）；B-1 **恒门禁**；B-3 恒 `RECORD_ONLY`；`--b-group-gate` 默认 `false`，**只影响 B-2**。
- `rounds` / `reproRounds` 由脚本自动统计并写进报告，**不做运行时门禁** —— 「≥3/5」只是人工翻默认值的准入条件，不是退出码依据。
- **`SKIP` 不计门禁**，但报告目前**没有**「N 项 SKIPPED」的摘要行（该汇总尚未实装，不得按其存在来读报告）。
- B 组子前缀是 `<round>`（1..5，**同轮内 20 线程共享**），**不是** `<seq>`；行序号才用 `-S<seq>` / `-N<seq>`。跨轮共用同参会让第 2 轮起直接走「已存在」分支、不进并发窗口。
- **A-S4a-7 依赖 MySQL 默认的 REPEATABLE-READ**（8.4 实测），**不是**本项目配置的 —— 别在报告里写成项目设定（口径见 §判定口径）。
- **S4b 混合负载时长 60s**（D2 裁决），CI 总时长因此增约 3–4 分钟。
- B-2 的成功判定**同时用 JDBC 核对行数**，不能只数 `添加成功！` —— TD-15（`loginAdd` 假成功）会让「只数响应体」的口径失真。
- `CreateTime` / `SettlementDate` 按**字符串**比（F6：两者均 `varchar(50)`，已核实 `SQL:152-153`）。
- `A-S1-3` 的 basis 是种子行 `SQL:99` `('123','123456',1,'pIzTWafaGp')`。

### 实测数据（阶段 D / E 已跑出）

> 本节只放**结论性数字**；完整的发现过程、踩过的坑与原始日志见 `docs/agent/archive/worklog-2026-09-20-a3.md`。

- **B-2 三处 check-then-insert 全部复现（`rounds=5 / reproRounds=5/5`）**，两次独立实测（背压 / 字符集修正前一次；两步编译 + `run.sh` 下 `runId=f84fc015` 一次），逐轮数字一致：`loginAdd` → `5xx=9 成功=1 已存在=10 未判定=0`；`goodstype` 重复行数 = 新增成功数；`friendAdd` 重复行数 = 申请成功数。**信号极性不同**：`login` / `user` / `customerimage` 三表有主键 → 撞键 `ERROR 1062` → 未捕获 → **HTTP 500**（信号 = 5xx>0，**与其它各处相反**：此处 5xx 是「缺陷复现」而非异常）；`goodstype` / `friend` 只有自增主键 → **真的插出重复行**（信号 = 重复行 ≥ 2）。无全局 `@ControllerAdvice`，未捕获异常走 Spring 默认 500。
- **S4b 性能退化 = TD-14（`order.OrderId` 无索引）**：`SHOW INDEX` 实测 `order` 只有 `PRIMARY(Id)`；三个 CAS 端点 WHERE 均以 `OrderId = ?` 起头，`EXPLAIN` 实测 `type=ALL`、`possible_keys` 空。同款 WHERE 的只读对照（16 线程 × 25，只变行数）：**6 行 p50=1ms → 6006 行 p50=51ms → 12006 行 p50=94ms**（12000 填充行测后已删，`order` 回到 6 行）。满负载档（`order` 约 1 万行）：`offered=120 achieved=63.0 QPS wall=114.3s p50=496.1 p95=779.7 p99=842.4 max=1120.6(ms)`；S4b 长档（60s，从 6 行起）：`offered=120 achieved=96.2 QPS wall=74.8s p50=292.4 p95=538.7 p99=625.6 max=860.1(ms)`，四条门禁断言全 PASS、不变式违反 = 0、5xx = 0。（🔄 **2026-09-30 S1a**：以上是**加索引之前**的记录 —— `order.OrderId` 现已有 UNIQUE `uk_order_orderId`（`1e2fcfa`），本条描述的全表扫退化**已消除**；数字保留作历史对照。）
  - **结论（2026-09-21 修正定性）：无索引的代价要在同一环境内用行数对照来证 —— 只读对照（6 行 → 12006 行，p50 1ms → 94ms）就是那个证据。** 原写「120 QPS 不可达，瓶颈是索引缺失」，**归因错误**：CI 首跑（CI #8）同一档 S4b 实测 `achieved=119.9 QPS / p50=6.3ms` —— 同一份 SQL、同一后端、同一断言集，**同份数据在 CI 上可达 120 QPS**。故「本地达不到」= 本地环境（VM 的网络 / 磁盘）属性 + 索引缺失的叠加，不能记成索引缺失一因所致。两个本地数字的差（96.2 vs 63.0）仍成立，其唯一实质差异是窗口开始时 `order` 已有多少行。
  - ⚠️ `achieved` 的旧值（68.3 QPS / p50 398.9ms）出自**背压之前**的负载发生器（开环提交 + 无界队列 → 积压队列在提交结束后又排空，`achieved` 被算成排空期均值）。加了 `Semaphore` 背压后 68.3 → 63.0 **不是变差，是变准**；`line()` 同时打印 `offered` / `achieved` / `wall`，未达速时标 `←未达速（只记录，不门禁）`。
  - ⚠️ 96.2 vs 63.0 的对比有**未排除的混杂因素**（全量档里 S4b 不是首段，后端 / 连接池 / JIT 已热；热态本应更快却更慢，混杂方向与观测差**相反**），只能算**同向旁证**。严格曲线须把行数当唯一变量，即上一条只读对照。
- **CI 首跑对照（CI #8，2026-09-21）**：同一档 S4b `achieved=119.9 QPS / p50=6.3ms`（本地同档 `63.0 / 496.1`、`96.2 / 292.4`）—— 同份数据在 CI 上可达 120 QPS，故本地达不到 120 是**本地环境属性**（压测器与 MySQL 跨机、经 VM 的网络 / 磁盘），不是索引缺失一因所致。**跨环境的达成率不能当 TD-14 的证据**，行数对照才能。
- **JSON 报告**：`scripts/concurrency/reports/a3-report-<runId>-<ts>.json`（UTF-8，手写 JSON —— classpath 上没有 Jackson，**不引新依赖**）。数据来自 `REC` / `ASS` / `B2_ROUNDS` / preflight，**不解析 stdout 文本**（解析自己的打印串等于把「给人看的格式」变成契约）。带 `launch`（启动方式 + 平台字符集 + 自检结果，**这条补上了「历史日志不记录启动方式」的缺口**）、`target`、`options`、`preflight` 全项、`assertions`（含 `rounds` / `reproRounds`，**未做多轮计数的落 `null`，不拿别的数凑**）、`gates`、`scenarios`（7 段 + `cleanup`）、`b2PerRound`（结构化逐轮，不反解文本）、`logFile`、`exitCode`。
  - **报告不带什么**：S2a / S3 / S4a 是栅栏型（burst）、没有 `Load`，其取数是手写 `rec()` 的**头条计数**（如 `inconsistentOrders`），细粒度响应分类只存在于断言 `detail` 与日志里 —— 别以为 JSON 里能查到全部证据。
  - 报告**写失败不改变退出码**：报告是证据，不是判定；判定由门禁断言与退出码给出。但绝不静默 —— stderr 明确报错，stdout 摘要行落 `<<未落盘>>（见 stderr）`。

## CI 集成（concurrency job）

`.github/workflows/ci.yml` 的第三个 job `concurrency` —— **有** `needs: backend`（要用 backend 构建出的 jar；**但 backend job 本身零 MySQL**，库在本 job 的 service container 里）；job 级 `concurrency` 覆盖 workflow 级的 `cancel-in-progress: true`（**被砍断的测量没有报告、日志也是截断的**，是最难排查的一种红）；`continue-on-error: true`。

- **首跑是观测跑，不是门禁**。A-S1-1 / A-S2b-1 / A-S4b-1 断言的是**传输层**，而 CI 把压测器、后端、MySQL 挤在同一台机器上（本地是「压测器 + 后端在本机、MySQL 在另一台 VM」），存在**资源性假红**的可能。反向的错法更坏：为迁就 CI 下调 `--qps` 会让 TOCTOU / 锁争用类缺陷**不复现 → 假绿**。故先观测、存档，门禁地位待首跑数据再定。`continue-on-error: true` 让该 job 显示**黄色警告而非绿**，含义正是「绿不等于通过」；判定看报告里的 `gates.failed` 与 `exitCode`。**首跑（CI #8，2026-09-21）已完成** —— 该档 S4b `achieved=119.9 QPS / p50=6.3ms`（见 §实测数据）；**门禁地位是否从「观测」改为「门禁」仍待裁决**，本条在那之前不改。
> ⚠️ 一处显示语义与预期不符（**未查因**）：首跑显示为**绿勾**而非本条预期的黄色警告。看报告本身是真通过而非被 `continue-on-error` 掩盖（`exitCode=0`、`gates.failed=0`），故不影响判定；但「设了 `continue-on-error` 为什么仍显示绿」未查明，登记在此，别把它当成「`continue-on-error` 没生效」的证据。
- **镜像必须 `mysql:8.4`**：P1 是硬门禁，要求 `VERSION()` 以 `8.4.` 开头（`8.0` / `latest` 必红）。驱动 8.0.33 × 服务端 8.4 的搭配**本身是被测环境的一部分**，不许「顺手升级」。
- **容器内库名正好落位**：service container 给的库名就是 `mall_management_sql`，故导入脚本里的建库/`USE` 语句**不需 sed 过滤**（与本机 `mall_management_test` 场景相反，见 TD-12 / TD-13）。
- **导入用容器自带的 `mysql` 客户端**（`docker exec -i ${{ job.services.mysql.id }} mysql ...`），不用 runner 预装的：预装版本不受控（当前 8.0，将来可能变），而导入应与 server 同版本。**别把 VM 形态的 sudo 抄过来** —— 那里 `root@localhost` 是 `auth_socket`、root 无法 TCP 登录才需要 sudo，容器里 root 可用口令连接。
- **`test` 账号刻意保留 `GRANT ALL ON *.*`**：那是 VM 上已登记的越权现状（见 §前置准备 S0-2），CI **复刻**之。在 CI「顺手修好」会让 CI 测的环境与 A3 记录的环境不是同一个。
- **`--skip-negative-control` 是必需的**：`negativeControl()` 第一段就是「阴性对照库与目标库同一主机 → FAIL 并 `exit 2`」，而 CI 里目标库本就是 `127.0.0.1` 上的 service container，必然命中；那条「本机不可达则自动 skip」的路在 CI **不成立**。对照要证明的是「目标不是开发机上那个同名库」，而 CI 里不存在第二个库，**无对象可对照**。OD-4 在 CI 仍由 P2（主机串比对）+ P6（库身份对比，证明后端与脚本同库）兜住。
- **`--ready-timeout-sec 180`**（默认 60 的三倍）是 CI 与本地命令的一处**刻意不同**：P5 超时是 FAIL 不是 skip，值给小了会把「GHA 冷启动慢」变成假红 —— 将来比对 CI 与本地 QPS 时要知道这一条。另有预热步骤（等就绪最多 120s 后打 5 个请求），只削通用路径（DispatcherServlet / Hikari / JIT）的冷启动峰，**替代不了 S1 自己的爬坡**。
- **`--app.upload-dir=/tmp/a3-uploads/`**；报告与 `app.log` 以 `if: always()` 上传（`app.log` 供事后核对 Hikari 实际 `jdbcUrl`）。
- **全上下文冒烟 + 数据库可达性步骤**（D1）：`A3_ENABLED=true` 下跑 `-Dtest=MallManagementApplicationTests`。**该步骤的意义是「真连库」，不是「上下文能起」** —— 2026-09-20 实测证明原 `contextLoads()` 恒过：库不存在（`Unknown database`）与端口无监听（`Connection refused`）**两种情况下都 BUILD SUCCESS**（这与「上下文能起」是两回事，根因是 TD-01：无 `@Entity` + `ddl-auto=update` 尽力而为）。故 D1 语义定为「测试真的连库」（方案 A）：新增 `databaseIsReachable()` 以 `SELECT 1` 强制真连后，同样两例分别变成 `Errors: 1` 与 BUILD FAILURE，且报错**只**指向 `databaseIsReachable`。TD-10 因此关闭。
- 本阶段**零业务 Java 改动**（除 D1 那一个测试类）。三条读码发现：① `--reset-db` 是死参数 → 登记 **TD-16**，不在本阶段实现；② `run.sh` 里 `--log-file` 的注释与代码相反（注释称「调用方优先」，实际是 run.sh 覆盖调用方）→ 已改注释，**未改行为**；③ 见上一条 `--skip-negative-control`。
- 顺带更正一处**过期注释**：`backend` job 里「MySQL service 计划在 A3 阶段引入」已改为「落在 concurrency job；本 job 至今不需要 MySQL」。

## 相关 TD / BD

- TD-01（`spring-boot-starter-data-jpa` 空转 + `ddl-auto=update`，CI 起库时需确认不反向改表）—— **同时是 D1 的根因**：无 `@Entity`，上下文加载根本不需要库，故 `contextLoads()` 连不上库也能过
- TD-08（测试数据落盘污染 —— 选 `orderAdd` 系列可规避）
- TD-10（CI 无 MySQL 的临时规避）—— **已关闭**（D1 落地：concurrency job 以 `A3_ENABLED=true` 跑真连库断言）
- TD-12（`mall_management_sql.sql` 自带建库语句 → 导入到哪个实例都会建同名库；也是 CI 容器内「正好落位」的原因）
- TD-13（39 处硬编码 `mall_management_sql.` 前缀 → 必须「独立实例 + 同名库」；`DATABASE()` 单独无法区分主机，故 P1 的 `VERSION()` 门禁是它的补偿）
- TD-14（`order.OrderId` 无索引 —— S4b 性能退化的根因；正确性与性能是同一缺失的两面）—— ✅ **2026-09-30 S1a（`1e2fcfa`）已加 UNIQUE `uk_order_orderId`**。⚠️ 原文尾注「**A3 不加索引**」**已作废**：该前提是「`mall_management_sql.sql` 不得修改」，已被规则 v2 取代（新增索引**无条件允许**）⇒ 现在 A3 跑在**已加索引**的库上，S4b 的对照数字随之换了口径
- TD-15（`loginAdd` 返回「添加成功！」而不插行 → B-2 必须同时用 JDBC 核对行数）
- TD-16（`--reset-db` 是死参数 —— 声明了破坏性语义，实际什么都不做）
- TD-17（`orderAdd` 不校验三个状态位，可伪造终态订单 —— **登记不门禁**，A3 不为它新增断言）
- BD-01（`SettlementDate` 被付款/发货/收货三处覆写，涉及支付场景的读取口径）
- BD-03（零鉴权 —— 并发脚本无需处理登录态，可直接压接口）

## 元教训：基于局部信息下结论（三个实例）

本节由 2026-09-20 的两条同源子项**合并而成**（原为待落盘清单第 21 条的末条子项与第 23 条；清单已并入正文，故此处不再引编号 —— 单独立条只会将来续出 24、25 条，不如一次归类），实例 3 于同日晚由「H 组正文检索失败」一事补入。三例同源，且与 **TD-02**（根目录无 `.gitignore`）、**TD-07**（catch 归因错）同类：**都没读实际内容就下了结论**。

**实例 1 —— 看调用点存在，就以为「有效」（`recLoad()` 死代码）**

`recLoad()` 一度**定义了却无人调用**，于是 S1/S2b/S4b 的均值与分位从未进 REC，报告里 `scenarios` 只有 `B2` 与 `cleanup` 两段。是**实测报告内容**才发现的，不是读代码发现的。与 `--rounds`、`--report-dir`（修复前）、**TD-16**（`--reset-db`）同类。

→ 规则：报告这类「汇总出口」必须打开**实际产物**验一遍，光看调用点存在不算数。

**实例 2 —— 看 grep 命中行号，就断言那一行的内容（`jdbcPassword` 误报）**

我在 CI 设计稿里写「`run.sh` 的横幅会原样打印 `jdbcPassword`，CI 日志会传成 artifact」，并据此建议本轮顺手修 —— **这是错的**：`mask()`（`ConcurrencyRunner.java` 的 `static String mask(String s)`）早已存在，且两处打印点（启动横幅、JSON 报告的 `target` 段）**都已调用**，报告里写的是 `******`。

- 成因：我在 grep 命中表里看到 `jdbcPassword` 出现在那两行的**行号**上，就断言它是明文 —— 只看了行号，没读那一行。这与 `agent.md` 规则 9（不得从名字/位置推断，要读内容）正相反；同一轮里 `--reset-db` 我读了那几行，就判对了。
- 代价：这条是**我提出、你据以批准的**。若照单执行，会写出一个「把已经正确的东西改一遍」的 commit，是纯粹的噪声与风险 —— **上游的错报会变成下游的工作量**。

→ 规则：**grep 命中行号 ≠ 命中内容**。

**实例 3 —— 命中过，但按文件归因丢弃（H1–H6 检索失败）**

先按 `H3` / `F3` 这类标签搜，而正文的键是英文（`backend_to_vm_db` / `script_design` / `ci_integration` 等），检索键就错了；更关键的是：`F3` 其实**命中过** `5574d7b3`，但把该文件的命中整体归因为「本轮命令与说明自身」而丢弃 —— 第 134 行就在其中。**命中是真的，归因是错的。**

→ 规则：**命中要逐行核实来源；按文件整体归因丢弃 = 主动删掉正确线索。**

**三者的关系**：同一类错误的三面 —— 实例 1 是「看调用点存在就以为**有效**」，实例 2 是「看调用点存在就以为**有害**」，实例 3 是「看命中存在就以为**无关**」。三者都源于**不读实际内容就下结论**：前两例跳过了「读那一行」，实例 3 跳过了「读那个文件里的那几行」。

附带记下 `mask()` 的行为：返回 `"*".repeat(len)`，故脱敏后仍泄漏**口令长度**（横幅里是 7 个星号）。判断为可接受（长度不是秘密），未改。

## 落盘前 checklist（v1 — 仅含已取证条目）

> 用途：本 pack 正文与探索结论之间**已出现漂移**。落盘时对着本表**逐项打勾**，不靠记忆。
> 每条均附取证依据；无依据的条目一律不列入（`agent.md` 规则 9.1）。
> **本表已于 2026-09-20 完成落盘核对，16 项全部 `[x]`**；「落盘位置」列指向 pack 正文对应章节，使本表兼作导航索引。

### 核对状态汇总（2026-09-20）

| 类别 | 条目 | 依据核对 | 落地状态 |
|---|---|---|---|
| 事实断言 | 1, 2, 3, 4, 6, 7, 8, 11 | ✅ **8/8 实测通过** | `[x]` 已落盘 |
| 已锁定裁决 | 5, 6（裁决部分） | ✅ 2026-09-20 锁定 | `[x]` 已落盘（§判定口径） |
| 设计推理 | 10（P0 清理与 P4 计数互斥） | ✅ 本会话推导 | `[x]` 已落盘（§命名空间与清理） |
| 待实测 | 9（S0-5） | ✅ **2026-09-20 实测通过**（服务端 `8.4.11`，驱动无需回退） | `[x]` 已落盘（§前置准备） |
| pack 现存错误 | 12, 13, 14 | ✅ **3/3 确认存在** | `[x]` **已修** |
| 已回填 | 15 | ✅ 2026-09-20 自转录**自行定位并解出**（非人工提供） | `[x]` 已写入 §附录 A |
| 本轮补入 | 16 | ✅ 已当场核对（`concurrency-map.md` §5 第 5 / 9 行） | `[x]` 见本表（**动作项，无正文落点**） |

> **两个维度现在都已完成**（此前是「依据已核对通过、落地待执行」，现已收口）：
>
> - **依据**：逐条核对**已完成**。事实断言 8 项全部实测通过（不是照抄探索结论），裁决 2 项已锁定，12–14 确认为 pack 现存错误，9 项已由 S0-5 实测补齐。
> - **落地**：**16/16**。第 1–11 项的结论已写进正文（见「落盘位置」列），第 12–14 项的错误已改，15 项已回填。

| # | 落地 | 核对项 | 依据（已取证） | 落盘位置（pack 正文） |
|---|---|---|---|---|
| 1 | `[x]` | H2 —— 根 `.gitignore` 一处必须写「**追加**」而非「新增」 | `9b87ab9` 已含 27 行 `.gitignore`；见 `tech-debt.md` TD-02 更正记录 | §约束 |
| 2 | `[x]` | F1b / OD-1 —— `loginAdd` 的 B 组断言必须是「**无 HTTP 5xx**」，**不得**写「无重复行」 | `login` PK=`UserName`、`user` PK=`username`、`customerimage` PK=`UserName` → 重复行**不可能**产生；真实缺陷是未捕获 `DuplicateKeyException` → HTTP 500（`UserService.loginAdd:125-160` 无 try/catch） | §验收标准 B 组第 2 行（原「loginAdd…不产生重复行」一行**已按本项拆开改写**） |
| 3 | `[x]` | F4 **（已修正；2026-09-20 再收窄）** —— 种子 6 行订单全为 `(1,1,1)` 终态，故 **S3/S4 必须自建订单**；**S2 不在此列** | `mall_management_sql.sql` 的 order INSERT：Id 18–23 六行全部 `PayIf=1, DeliveryIf=1, ReceiveIf=1`；`orderPayIfUp` 的 WHERE 为 `PayIf=0` → 在种子订单上「恰好 1 次 `success:true`」**必然不成立**。修正对象是**支付 / 发货 / 收货**三场景，即 S3 与 S4 —— **上轮把 S2 一并写入是笔误，实为 S3**：S2 只调 `orderAdd` 自建订单（订单由被测端点自身创建），不存在「复用种子单」的问题。故原依据里「原文只写 S3/S4 会让 A 组第一条断言必败」一句的前提本身有误，一并更正 | §验收标准 A 组（含「种子单必败」的说明） |
| 4 | `[x]` | ~~B-1 —— `order.OrderId` **无唯一索引**（主键仅 `Id`）~~ → ✅ **2026-09-30 S1a 已加 UNIQUE `uk_order_orderId`**（`1e2fcfa`） | 原依据 `mall_management_sql.sql` order 建表语句。⚠️ 原文「pack **不得**写成『有唯一约束兜底』」**自 S1a 起反转** —— 现在 DB 层**确实**有唯一约束兜底（`A-S2a-1b` 断言的正是它） | §验收标准 B 组第 1 行（同日已同步翻转） |
| 5 | `[x]` | 澄清 1 —— `--b-group-gate` 默认 `false`、**只影响 B-2**（B-1 恒门禁、B-3 恒 record-only）；verdict 枚举 `PASS` / `EXPECTED_DEFECT_PRESENT` / `DEFECT_FIXED` / `RECORD_ONLY`；`rounds`、`reproRounds` 由脚本自动统计，**「≥3/5」不做运行时门禁**（仅作人工翻默认值的准入条件） | 本会话 2026-09-20 裁决 | §判定口径（verdict）与门禁 |
| 6 | `[x]` | 澄清 4 —— B 组子前缀是 `<round>`（1..5，**同轮内 20 线程共享**），**不是** `<seq>`；行序号才用 `-S<seq>` / `-N<seq>` | 若跨轮共用同参，第 2 轮起 `loginAdd` 直接走「已存在」分支，**不进并发窗口**，后 4 轮等于白跑 | §判定口径（verdict）与门禁 |
| 7 | `[x]` | Q1=B / TD-13 —— 隔离策略必须写「**独立实例 + 同名库** `mall_management_sql`」，**不得**写成「切库名隔离」 | 硬编码前缀 39 处（**2026-10-03 实测**：`UserDao` 16 / goods 14 / order 9 / `MessageDao` 0，散到 **7 个文件**；原记「Goods 23 + User 16」是重构前分布 —— goods 那 23 含订单端点 SQL 9 条），全部指向 `mall_management_sql`；口径见 `three-layers.md §F#1` | §前置准备「目标环境」 |
| 8 | `[x]` | S0-3 —— 驱动路径由 **Maven 权威解析**（`dependency:build-classpath`），**不做 glob 猜测**；解析后**校验 jar major = 8** | 实测 `~/.m2/repository` 两个 groupId 下的真实状态：①**`pom.xml:57-61` 声明的坐标 `mysql:mysql-connector-java:8.0.33` 本地只有 `.pom`、没有 jar**（jar 因 relocation 落在 `com.mysql:mysql-connector-j:8.0.33`）；②按 `mysql/mysql-connector-java/*/*.jar` 去 glob，**唯一命中的是 `8.0.28`**（8.0.31/8.0.33 在该坐标下无 jar）→ 静默用上 8.0.28；③按 `com/mysql/**/*.jar` 去 glob 会同时命中 `8.0.31` 与 `8.0.33`，不排序则取到 8.0.31；④另有 3 个 `mysql/mysql-connector-j/8.0.33/*.lastUpdated` 失败标记（groupId 与 artifactId 混用导致的不存在坐标）。**结论：glob 猜测必然取错版本，必须走 Maven** | §约束（D3 单条路径） |
| 9 | `[x]` | S0-5 —— 必须 `SELECT VERSION()` 确认服务端确为 8.4.x；兼容失败时的兜底写「脚本侧 `$A3_JDBC_DRIVER` + 后端 a3 profile 临时 classpath 覆盖」，**不得**写成改 `pom.xml` | 硬约束：不改 `pom.xml`；`environment.md` 已定 MySQL 8.4。**实测结果：服务端 `8.4.11-0ubuntu0.26.04.1`，驱动 8.0.33 连通，无需兜底** | §前置准备「启动验证（S0-5）」 |
| 10 | `[x]` | 禁止并发 —— 必须写明「两个 A3 运行**绝不**可同时作用于同一实例」 | P0 的 `A3TEST-%` 清理与 P4 的种子计数互斥 | §命名空间与清理（末条） |
| 11 | `[x]` | P4 种子计数 —— `order=6` / `goods=5` / `login=5` / `user=5`（已复核 ✓）；另 **`friend=4`**，B-2 跑完按 `+2 × 轮数` 计提 | `mall_management_sql.sql` 各表 INSERT 逐行计数 | §脚本内置校验 P0–P7（P4）＋ §命名空间与清理（自增计数的容忍差额） |
| 12 | `[x]` | 约束节「**只走 HTTP，不依赖项目 classpath**」与已定的 **HTTP + JDBC** 冲突 → 须重写；前置准备第 3 步的「届时再议」已由「脚本形态 = HTTP+JDBC」**解决**，应改为结论 | 本文件 `约束` 节 vs `archive/progress-2026-09-19.md`「脚本形态 = HTTP + JDBC」 | §约束 |
| 13 | `[x]` | 前置准备第 1–3 步（建 `mall_management_test` 库）**已失效** → 须按 Q1=B 重写为「独立 MySQL 实例 + 同名库」 | Q1=B 裁决 + TD-13（本地测试库当前「建好了但用不上」） | §前置准备「目标环境」 |
| 14 | `[x]` | 必读文档行「tech-debt.md（**TD-01 ~ TD-11**）」**已过时** → 须补 TD-12、TD-13 | `tech-debt.md` 现有 TD-01 ~ TD-13；本轮实际补到 **TD-01 ~ TD-17**（TD-14/15/16/17 均在本任务期间登记） | §必读文档 |
| 15 | `[x]` | H3 / H4 / H5 / H6 正文 —— **已回填**（见 §附录 A）：4 处与已落盘冲突（C1–C4，**一律以已落盘为准**）保留在附录 A；2 处 H3 自身缺口（G1–G2）已在正文解决；7 项待裁决（D1–D7）**均已裁决并落地**（D1→§CI 集成、D2→§判定口径、D3→§约束、D4→§验收标准、D5→TD-17、D6→§判定口径、D7→本表第 16 项） | 正文**存在于本地转录**（`5574d7b3` 第 134 行，27208 字符，经 `json.loads` 解出）。原判「从未落盘」**为误** —— 根因见 §元教训「实例 3」 | §附录 A |
| 16 | `[x]` | R12 —— **改本 pack 时须同步 grep `concurrency-map.md` §5**：第 5 行的 `loginAdd` 表述（以及第 9 行的 `order.OrderId`）是否仍与 pack 的 B 组断言口径一致 | H3 `risks.R12`；`agent.md` 规则 9.2 / 9.3（**引用外部文档的结论必须回原文核对**）。已当场核对过一次：第 5 行确为 `loginAdd` TOCTOU、第 9 行**当时**确为 `order.OrderId` 无唯一索引。⚠️ **2026-09-30 S1a 后第 9 行已改为「已消除」**（`1e2fcfa`），本 pack B 组第 1 行与 `A-S2a-1` 亦同日翻转 —— R12 下次重做时须按**新口径**核对（第 9 行现在应读作「有 UNIQUE 兜底」）。列为 checklist 项的理由：这是**每次改 pack 都要重做**的动作，不是一次性核对 —— 上游 §5 若增删行，pack 的 `basis` 会静默失准 | 本表（**动作项**，每次改 pack 重做） |

### 附录 A —— H3–H6 与已落盘的冲突表（2026-09-20）

> 本节保留**冲突表**（以已落盘为准）与**温和偏离表**（实现与 H3 写法不同但等价或更强）两类。H3 自身的两个缺口（G1 哨兵前缀、G2 P4 漏 friend）已在本 pack §命名空间与清理 / §脚本内置校验 P0–P7 解决，不在本附录重列；检索 H3–H6 的**失败根因**（按标签检索、命中被按文件整体归因丢弃）属「元教训」章节的第三个实例，见该节。
>
> **出处**：H3–H6 正文不在仓库里，在本地转录 `5574d7b3-2969-4a05-9192-a50392eb19f5.jsonl` **第 134 行**（27208 字符，`role=assistant`）；解出方式为逐行 `json.loads` 后取 `message.content[].text` —— **不可用 `sed` / `awk` 反转义**（Bash 会把命令串中的 `\\` 折叠为 `\`，反转义静默失效，`awk` 报 `unterminated regexp`）。同源材料：第一阶段 A3 方案 JSON 在 `4d8d887a-df56-4ef4-a671-d2ad4444d423.jsonl` 第 206 行，其 H4/H5 为 H 组前身。

**H 组与已落盘冲突 —— 一律以已落盘为准（已落盘经过多轮修正）：**

| # | H 组原文 | 已落盘（胜） | 性质 |
|---|---|---|---|
| C1 | H2 `files_to_change.new[]`：「`.gitignore` 根目录新增（TD-02 指出根目录无 `.gitignore`）」；H 组 `rollback.gitignore` 亦写「若该文件仅为本任务而建，可直接删文件」 | 根 `.gitignore` 早已存在（27 行，`9b87ab9`），A3 **只追加 2 条**，绝不新建 / 覆盖 / 删除 | 复现 TD-02 原错 |
| C2 | H3 `driver_prerequisite.resolution_order`：glob `~/.m2/repository/com/mysql/**/{mysql-connector-j,mysql-connector-java}-*.jar` 取**首个匹配** | Maven 权威解析（`dependency:build-classpath`）+ 校验 jar major=8 | glob 必然取错版本（见 checklist 第 8 项实测） |
| C3 | H3 `report.schema_sketch.assertions[].verdict`：`PASS`/`FAIL`/`EXPECTED-DEFECT-PRESENT`/`DEFECT-FIXED`/`SKIPPED` | `PASS` / `EXPECTED_DEFECT_PRESENT` / `DEFECT_FIXED` / `RECORD_ONLY`（**下划线**） | 拼写 + 语义双差（见 §判定口径） |
| C4 | H3 `report.schema_sketch` 无 `rounds` / `reproRounds` 字段，亦无「复现率低」报告级提示 | 澄清 1 + 建议 4 选项 B：脚本自动统计、报告必带、弱证据在摘要行显式提示 | 缺字段 |

> H 组**内部**亦自相矛盾一处：H3 `invocation` 用 `-cp <mysql-connector-jar>`（仅驱动），H5 第 8 步用 `-cp "$(cat /tmp/cp.txt)"`（全项目 classpath）；而 pack 约束节写「不依赖项目 classpath」。三方需统一 —— **已按 §约束 的 D3 定稿收口**（`dependency:build-classpath` 解析后只取单条 + 校验 major=8，回退路径仅此一处用到 Maven）。

**与 H3 的温和偏离 —— 不是冲突**（2026-09-21 补）：实现与 H3 原文的写法不同，但判据**等价或更强**，故不列入上表；两组断言各自的偏离理由**已写在代码注释里**（`ConcurrencyRunner.java` 就地注明），此处只作索引。

| # | H3 原文 | 实现 | 性质 |
|---|---|---|---|
| 1 | `A-S4a-8` 的不变式检查把「**PR 型预置单排除在外**」 | **未排除** —— 谓词直接作用于全命名空间：`WHERE OrderId LIKE '<ns>%' AND PayIf = 0 AND (DeliveryIf = 1 OR ReceiveIf = 1)`（`s4a()` 末段） | **等价（且检查面更宽）**：PR 的置位是 `(PayIf=1, DeliveryIf=0, ReceiveIf=1)`，`PayIf = 0` 已为假 → 排除与否都不入违反集。跳过排除只是少一层特例，判据本身不受影响 |
| 2 | `A-S4b-4` 的「JDBC `count` 按 **LIKE 命名空间**」 | **收窄到 `-S4bN%` 子集**：`WHERE OrderId LIKE '<ns>-S4bN%'`（`s4b()`） | **加强**：不收窄会把 3 个预建单池（`-S4bPay` / `-S4bDlv` / `-S4bRcv`，合计 3600 行）一并计入而**必然不等** —— 即该断言在原文写法下恒假。收窄不是放宽，是让它**真能判** |

> 两者的共同点：H3 是**设计稿**，写的是当时设想的写法；实现按实际数据布局修正了谓词范围。**冲突表**（C1–C4）里以已落盘为准，是因为 H3 那些写法**错**；本表则以 H3 的**意图**为准，是因为它的写法只是不够具体 —— 这两类必须分开记，否则下次会误把「等价偏离」当成「H3 又错了一处」。

> **原「待落盘清单」已并入本 pack 正文（2026-09-20）**：S0 记录进 §前置准备，P0–P7 判据进 §脚本内置校验，命名 / 清理进 §命名空间与清理，裁决与门禁进 §判定口径，实测数据（B-2 / S4b / 报告）进 §实测数据，CI 约定进 §CI 集成，H 组冲突进 §附录 A。清单本体删除 —— 索引留在正文里，不另存一份。

## 待验证（⚠）

本 pack 中**未验证项与强度不足的判据**集中如下（正文各处已就地标注，此处仅作**索引**，不重复正文）：

| # | 未验证项 | 就地标注处 |
|---|---|---|
| 1 | **P6 的 FAIL 分支未实测** —— 本地构造不出（需后端连另一个库，须重启后端）。故「一致 → PASS」有实测（`runId=506a0997`，2026-09-27 全绿），其**反向分支无实测** | §脚本内置校验 P0–P7 |
| 2 | **P5 的就绪等待上限默认 60s 是本脚本的取值**，H3 未规定（`--ready-timeout-sec` 可覆盖；CI 用 180 是**刻意不同**）—— 别日后当成 H3 的规定 | §脚本内置校验 P0–P7 |
| 3 | **P6 可用的可靠判据只有 `@@server_uuid` 一条** —— `hostname` 是 `gethostname()`，**克隆 VM 可能保留原主机名**，故不一致只告警；`DATABASE()` / `@@port` 在两台机器上**必然相同**（TD-13），判别力为零；`VERSION()` 随本机升级到 8.4 即失效。后三项只能报告，不得升格为判定 | §脚本内置校验 P0–P7 |
| 4 | **S4b 96.2 vs 63.0 的对比有未排除的混杂因素**（热态方向与观测差**相反**），只能算同向旁证；严格曲线须另做「把行数当唯一变量」的只读对照 | §实测数据 |
| 5 | **`SKIP` 的摘要行未实装** —— 报告里没有「N 项 SKIPPED」的汇总行，不得按其存在来读报告 | §判定口径 |
| 6 | **历史日志不记录启动方式** —— 此前无法事后判定某次运行是「单文件源码启动」还是「两步编译」，只能靠 S3 结果反推（**已核：历史日志无假绿、无假红**）。自检行现已把 `file.encoding` / `native.encoding` / 启动方式 / 自检结果写进每次运行的日志 | §约束（运行形态） |
