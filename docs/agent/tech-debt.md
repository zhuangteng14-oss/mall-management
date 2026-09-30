# 技术债清单 — Mall-management

> 本文件只收录**技术层面**的建议与欠账，不收表结构事实、不收业务决策。
> 相关文档：表结构事实 → `db-schema.md`；业务待裁决 → `business-decisions.md`；并发问题与改造优先级 → `concurrency-map.md` §7；缓存 → `cache-map.md`。

## TD-01 移除未使用的 `spring-boot-starter-data-jpa` 依赖

| 项 | 内容 |
|---|---|
| 现状 | `pom.xml:51` 引入 `spring-boot-starter-data-jpa` |
| 实际数据访问 | 全部走 `JdbcTemplate`（`spring-boot-starter-jdbc`，`pom.xml:40`） |
| 证据 | 全项目**不存在任何 `@Entity`**；`mallmanagement_class/info.java` 是唯一实体类，仅含 lombok 注解，无 JPA 注解，且未被任何类引用 |
| 问题 | 该依赖引入了 Hibernate 并激活了 `ddl-auto` 配置（`application.properties:12`），但项目没有任何 JPA 实体可供映射，依赖处于空转状态 |
| 影响 | ① 增大构建产物、延长启动时间；② 保留了 `ddl-auto=update` 的生效条件，一旦有人误加 `@Entity` 即会反向 `ALTER TABLE`（详见 `db-schema.md` §3） |
| 建议 | 移除该依赖；保留 JPA 但仅置 `ddl-auto=validate`/`none` 也可，两者是独立的处置手段，可只做其一 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-02 `.idea/` 与 Git 忽略规则 — ✅ 已关闭（原文有误，2026-09-20 更正）

| 项 | 内容 |
|---|---|
| 原文现状 | `.idea/` 存在于 `Mall-management-server/.idea/`；**本目录不是 Git 仓库**，不存在索引 |
| 原文问题 | 根目录无 `.gitignore`；子项目 `.gitignore:17` 已包含 `.idea/`，忽略规则本身正确 |
| 原文建议 | 若启用 Git，在根目录新建 `.gitignore` |
| 更正 1 | **「根目录无 `.gitignore`」是错的**。根 `.gitignore`（27 行）由 `9b87ab9` 引入，且**与本 TD 在同一个 commit 落盘**（`git show --stat 9b87ab9 -- .gitignore` = `27 +`）。不存在「记录时正确、之后过时」的时间窗，**本 TD 自诞生起即错** |
| 更正 2 | **「本目录不是 Git 仓库，不存在索引」是错的**。monorepo 初始化后 `Mall-management-server/` 属根仓库；`git ls-files Mall-management-server/.idea` 返回 0 条，是因被根 `.gitignore:14` 的 `.idea/` 规则忽略，**不是**「无仓库」。原句属「结果对、归因错」 |
| 更正 3 | 原文建议已于 `9b87ab9` 落地，无需再动作 |
| 结论 | 关闭。A3（本 feature）**只在根 `.gitignore` 末尾追加**两条 A3 临时产物规则（`scripts/concurrency/reports/`、`scripts/concurrency/classpath.txt`），**不新建、不覆盖** |
| 教训 | 「文件存在与否」类断言必须当场 `ls` / `git ls-files` 取证，不得沿用文档推断（`agent.md` 规则 9.1）。2026-09-20 抽检 TD-01/06/07/08/10/12/13 共 **7 条**，**仅本项有此类错误**（TD-12 行号、TD-13 计数均精确命中），tech-debt.md 不构成系统性失真 |
| 优先级 | — |
| 状态 | 已关闭（2026-09-20） |

> **附：验证忽略规则的正确姿势（避免测试假象）**
>
> `git check-ignore` 对**不存在**的路径会「假设类型」：**无尾斜杠假定为文件，有尾斜杠假定为目录**。故 `**/node_modules/`（尾斜杠 = 只匹配目录）对 `git check-ignore node_modules`（无斜杠）会报「未被忽略」——这是**命令假象，不是规则失效**。实测：
>
> | 命令 | 结果 |
> |---|---|
> | `git check-ignore --no-index -v node_modules` | 未命中（假象） |
> | `git check-ignore --no-index -v node_modules/` | ✅ `.gitignore:2:**/node_modules/` |
> | `git check-ignore --no-index -v some/nonexistent/node_modules/` | ✅ 命中（不存在的路径同样可靠） |
>
> 结论：**查目录规则一律带尾斜杠 + `--no-index`**。

## TD-03 `agent.md` 回滚规则自相矛盾

| 项 | 内容 |
|---|---|
| 现状 | `agent.md` 数据库管理规则同时要求：① 每次变更登记回滚 SQL；② 禁止 `DROP COLUMN` / `MODIFY COLUMN` |
| 问题 | 对「新增字段」类变更，回滚必须 `DROP COLUMN`，与规则 ② 直接冲突。当前 `db-migration.md` 第 28 行采用折中方案（保留列 + 置 NULL），但这是**临时的，非正式规则** |
| 建议 | 修改 `agent.md` 规则：允许为「新增字段」类变更编写 `DROP COLUMN` 回滚 SQL，但限制其只在人工确认后执行；或明确「回滚 = 逻辑废弃 + 置 NULL」，不物理删除 |
| 优先级 | 中（当前无数据库变更，暂不阻塞） |
| 状态 | 待确认 |

## TD-04 CORS 重复配置与通配风险

| 项 | 内容 |
|---|---|
| 现状 | `ImgConfig.java` 注册全局 `CorsFilter`（`addAllowedOriginPattern("*")`），同时 3 个 Controller 各自标注 `@CrossOrigin` |
| 证据 | `ImgConfig:11-22`（全局 `CorsFilter`）；3 个 Controller 类级 `@CrossOrigin` |
| 问题 | ① 全局 CORS 与局部 CORS 重复配置；② `allowedOriginPattern("*")` + `allowCredentials(true)` 组合在生产环境有安全风险 |
| 建议 | 保留全局 `CorsFilter`，移除 3 个 Controller 的 `@CrossOrigin`；生产环境将 `allowedOriginPattern` 收敛为具体域名 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-05 静态资源路径依赖 `user.dir`，jar 部署时会失效

| 项 | 内容 |
|---|---|
| 现状 | **修复前行号**：`ImgPath.java:13`、`U:166`、`G:82`、`G:283`。修复后上述坐标已失效，现对应 `U:172`、`G:88`、`G:289`；`ImgPath` 的路径拼接已被 `@Value` 注入 + `Paths.get(uploadDir)` 取代（见 `ImgPath.java:18`、`:25`）。原写法：`System.getProperty("user.dir") + "\src\main\resources\static\img\"` |
| 问题 | 开发期 `user.dir` = 项目根，路径有效；打成 jar 后 `user.dir` = 运行目录，`src/main/resources/static/img/` 不存在 |
| 影响 | ① 图片上传失败 ② `/img/**` 映射失效 ③ 部署到生产环境或换机运行立即暴露 |
| 建议 | 改为可配置路径（`application.properties` 配 `app.upload-dir`），或改用 `classpath:/static/img/` + 外部上传目录分离 |
| 验证步骤 | 1. 开发期不配 app.upload-dir，调 /mysqlLogin/updateAvatar 上传，确认落盘位置与改动前一致<br>2. 访问 http://localhost:8088/img/<文件名>，期望 200<br>3. mvn package 打 jar，在非项目根目录 java -jar 启动，指定 --app.upload-dir=<jar 外可写目录><br>4. 再次上传，确认文件落在配置目录且返回 URL 可访问<br>5. 重启进程后再次访问同一 URL<br>6. 回归 /mysqlLogin/getUserInfo，确认 image 字段格式不变<br>7. 回归 /mysqlGoods/goodsAdd、/mysqlGoods/goodsUp，确认 GoodImg 格式不变 |
| 回归保护 | 读侧的 `/img/**` 映射已由 `ImgMappingSmokeTest`（A2 新增）纳入 CI 覆盖，含「已存在文件 200 且内容非空」与「不存在文件 404」正反两条断言，可防 TD-05 读侧回归。jar 场景仍无覆盖（见 TD-09） |
| 优先级 | 高（部署级阻塞） |
| 状态 | 读侧已修复、写侧开发期已验证（1421a0c、c5d74b4），jar 场景待验证 |

## TD-06 图片 URL 前缀硬编码为 localhost:8088

| 项 | 内容 |
|---|---|
| 现状 | `U:192`、`G:111`、`G:300` 三处硬编码 `"http://localhost:8088/img/" + 文件名` |
| 口径补充（2026-09-20） | **本 TD 以「生成落库 URL」口径 = 3 处为准**（`U:192`、`G:111`、`G:300`，即上式）。另按「出现 `localhost:8088` **字面量**」口径为 **4 处**：多出 `U:55` 的 `@RequestParam(value = "image", defaultValue = "http://localhost:8088/img/default_user.png")`（入参默认值，非生成 URL，但同属换机即失效的硬编码）。**修复时需一并处理 `U:55`** —— 只改这 3 处则修复不完整，下方「改一处必漏两处」实际是**漏三处** |
| 问题 | 换机、容器化、走反向代理或改端口后，落库的 URL 全部不可用；且前缀散落三处，改一处必漏两处 |
| 建议 | 外置为 `app.upload-url-prefix`，三处统一注入 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-07 文件上传失败被静默吞掉

| 项 | 内容 |
|---|---|
| 现状 | `U:187-190` 与 `G:102-108` 用 `catch (Exception)` 捕获后仅 `System.out.println`，接口仍返回 `success:true` |
| 问题 | 目录不可写、磁盘满、路径不存在时，用户看到「上传成功」但文件未落盘，故障极难定位。**注意 `G:296-298`（`goodsUp`）未加 catch，异常会向上抛**——三处上传点行为不一致 |
| 补充（2026-09-20） | **异常消息把失败原因归错了**：两处 catch 都打印「已存在」。`G:106-108` → `System.out.println("商品图片已存在，继续上架")`；`U:187-190` → 注释「就算保存失败，也不崩溃！」+ `System.out.println("文件已存在，继续更新头像")`。但真正的「已存在」已被紧邻上方的 `if (!saveFile.exists())` 拦下，**根本不会进 catch** —— 故 catch 分支里的「已存在」归因**几乎总是错的**（磁盘满 / 目录不可写 / 路径非法一律被说成「已存在」），排查方向被彻底带偏。属「结果对、归因错」在生产代码中的实例，与 TD-02 的错误类型同源 |
| 建议 | 捕获后返回 `success:false` + 明确 `msg`，或至少改为 ERROR 级日志 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-08 开发期上传目录受 Git 跟踪

| 项 | 内容 |
|---|---|
| 现状 | 开发期上传目录 `src/main/resources/static/img/` 受 Git 跟踪，新上传图片会成为未跟踪文件。**已实测发生**：写侧验证上传头像后，`git status` 出现未跟踪文件（后经人工清理） |
| 问题 | 每轮开发验证都会污染 `git status`，易与真实代码改动混淆；且上传的用户图片被当作源码入库 |
| 建议 | 改用 `.gitignore` 白名单例外（先忽略 `*.jpg/*.png` 再 `!` 放行初始 8 张），或开发期上传目录与生产一致改为 jar 外。**补充（2026-09-18）**：该白名单只覆盖 `*.jpg`/`*.png` 两种扩展名，而 `src/main/resources/static/img/` 下 `b000602fe1f47af8e0e4747ae1b3338c_720.jpeg` 的扩展名是 `.jpeg`，不在放行范围内，方案落地时需一并覆盖 |
| 优先级 | 低 |
| 状态 | 实测发生，待处理方案 |

## TD-09 静态资源修复的 jar 部署场景待验证

| 项 | 内容 |
|---|---|
| 现状 | TD-05 读侧与开发期写侧均已验证；仅 jar 部署 + 外部 app.upload-dir 场景未验证 |
| 问题 | 打 jar 后以 `--app.upload-dir=<外部目录>` 启动、上传、重启访问的端到端路径未测 |
| 验证步骤 | 见 `docs/agent/tech-debt.md` TD-05 描述中的 7 步验证第 3、4、5 步 |
| 建议 | 首次打 jar 部署前完成验证；验证通过后与 TD-05 一起关闭 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-10 `contextLoads()` 在无 MySQL 环境跑不通 — ✅ 已关闭（2026-09-20，D1 落地）

| 项 | 内容 |
|---|---|
| 原文现状 | `MallManagementApplicationTests.contextLoads()` 依赖 JPA + `ddl-auto`，**推测**需要真实 MySQL 连接 |
| 原文问题 | CI（无 MySQL）上该测试会红，整个 `mvn test` 失败 |
| 原文处置 | 加 `@Disabled` 临时规避（`c3cd9f6`），Web 层覆盖由 `smoke/` 包承担；未改 `@WebMvcTest`，因会与 smoke 层重复且丢掉全上下文覆盖 |
| **实测推翻原文前半段** | 「无 MySQL 会失败」**是错的**。三个阴性探针实测（数据见 `99206a5` 的 commit message、`worklog-2026-09-20-a3.md` §缺陷记录 4）：**库名不存在（服务器可达）**与**端口无监听（完全无库）**两种情况**都是整个类 `Failures 0` / BUILD SUCCESS**。根因 TD-01 —— `ddl-auto=update` 的 schema 管理是「尽力而为」（连不上只记 ERROR 日志、不终止启动），且本项目**无任何 `@Entity`**、本就无 schema 可导出；两条叠加，启动路径上**没有任何一处「连不上就必须失败」**。故 `@Disabled` 当初的理由本身不成立 |
| 为什么必须补第二条 | 若 D1 只做「`@Disabled` 改环境开关」，CI 会多出一条**「库好不好都绿」**的步骤 —— 与 TD-15（以响应体判成功）、`recLoad()` 死代码同属「断言落空」那一族 |
| 处置（D1，方案 A） | ① `@Disabled` → `@EnabledIfEnvironmentVariable(named="A3_ENABLED", matches="true")`（开关置于类上，将来新增方法不会漏）；② 新增 `databaseIsReachable()`：`JdbcTemplate` 跑一条 `SELECT 1`，**强制真连**。加后探针 2/3 分别变成 `Errors: 1` 与 BUILD FAILURE，且报错**只**指向 `databaseIsReachable` |
| 刻意的克制 | 两个方法职责分开、不合并（`contextLoads` 证明「全上下文能启动」—— smoke 是 `@WebMvcTest` 切片，从不启动完整上下文，这一条本身仍有价值）；**只断言可达性、不断言 `VERSION()` 前缀**（本地开发库是 8.0.32，断言 8.4 会让它在本地变红；版本门禁由 A3 脚本 P1 承担）；未动 `pom.xml`、未动任何生产 profile、未引依赖 |
| CI 接法 | `concurrency` job 加一步：`A3_ENABLED=true ./mvnw -B -Dtest=MallManagementApplicationTests test`。**backend job 不设该变量** —— 那里全量 `mvn test` 自动跳过本类（跳过不是失败），backend 仍零 MySQL |
| 优先级 | — |
| 状态 | 已关闭（2026-09-20） |

## TD-11 前端依赖存在已知漏洞

| 项 | 内容 |
|---|---|
| 现状 | `npm ci` 报告 36 个依赖漏洞：2 critical / 14 high / 18 moderate / 2 low |
| 问题 | 含 2 个 critical、14 个 high，可能影响安全性；当前不影响功能运行 |
| 建议 | 先跑 `npm audit` 看详情，按 severity 分级；critical/high 优先，考虑 `npm audit fix` 或升级依赖 |
| 优先级 | 中（有 critical 和 high） |
| 状态 | 待确认（建议时机：A3 完成后、管理端开发前） |

## TD-12 mall_management_sql.sql 自带建库语句

| 项 | 内容 |
|---|---|
| 现状 | SQL 文件第 15 行 CREATE DATABASE、第 17 行 USE 硬编码库名 mall_management_sql |
| 问题 | 导入到其他库时必须先过滤，实测已触发（A3 前置准备首次执行失败） |
| 建议 | 长期拆分：一份"仅 Schema"供测试，一份"Schema + 建库"供生产 |
| 优先级 | 低 |
| 状态 | 待确认 |

## TD-13 SQL 硬编码 schema 名，导致测试库隔离失效

| 项 | 内容 |
|---|---|
| 现状 | `MallManagementGoodsController` **23 处**、`MallManagementUserController` **16 处** SQL 硬编码 schema 限定名 `mall_management_sql.`（合计 39 处）；`MallManagementMessage` **0 处**（SQL 不带前缀）。三个 Controller 共用同一个自动装配的 `JdbcTemplate` Bean，差异只在 SQL 字符串 |
| 问题 | 切换 `spring.datasource.url` **无法**把 `/mysqlGoods/*`、`/mysqlLogin/*` 的读写切到其他库——服务连上测试库，SQL 仍定向到 `mall_management_sql`。又因 `/message/*` 不带前缀，同一进程内会**脑裂**：`/message` 的写入跟随 datasource，`/mysqlGoods`、`/mysqlLogin` 的写入留在 `mall_management_sql` |
| 实测触发 | A3 前置准备按 context-pack 建 `mall_management_test` 并导入成功后，隔离对 `/mysqlGoods`、`/mysqlLogin` 实际无效。`orderAdd` 压测数据会落入开发库，而 `order` 表无删除接口，HTTP 层无法清理 |
| 影响 | ① 一切依赖「切库做隔离测试」的方案失效；② 压测/集成测试污染开发库；③ 多实例部署时无法让实例指向不同库 |
| 建议 | **方案 A**：移除 SQL 字符串中的 `mall_management_sql.` 前缀改用非限定表名（由 datasource 决定库），彻底但需改 39 处并全量回归；**方案 B**（A3 已采用）：保留硬编码，测试侧一律改用**独立 MySQL 实例**（库名仍为 `mall_management_sql`），绕开该问题 |
| 与 TD-12 的关系 | TD-12 是 `mall_management_sql.sql` 自带建库语句；本项是 Java 源码硬编码库名。同属「库名硬编码」的两个面，触发点与修法不同，分列 |
| 优先级 | 高（静默污染开发库，且在本任务中已实际触发一次流程失败） |
| 状态 | 待确认（A3 采用方案 B 绕过，不阻塞当前里程碑；是否做方案 A 待定） |

## TD-14 `order.OrderId` 无索引，三个 CAS 端点全表扫

| 项 | 内容 |
|---|---|
| 现状 | `SHOW INDEX FROM mall_management_sql.order` 实测**只有 `PRIMARY(Id)` 一个索引**，`OrderId` 上无任何索引。`orderPayIfUp` / `orderDeliveryIfUp` / `orderReceiveIfUp` 三个 CAS 端点的 `WHERE` 均以 `OrderId = ?` 起头（后接 `PayIf` / `DeliveryIf` / `ReceiveIf` 状态位） |
| 问题 | 无索引 → 每次 CAS 都是**全表扫**，扫描成本随 `order` 行数近线性增长；并发下 32 线程各自全表扫，退化被放大。表越大，CAS 端点的可用容量越低 |
| 实测取证 | ① `EXPLAIN` 与 CAS 完全同款 WHERE：`type=ALL`、`possible_keys` 空、`Extra=Using where`；② 只读对照（16 线程 × 25 次，只变行数这一个变量）：**6 行 p50=1ms → 6006 行 p50=51ms → 12006 行 p50=94ms**（12000 填充行测后已删，`order` 回到 6 行） |
| 实测触发（本地 VM） | A3 满负载档（120 QPS × 60s 混合负载）期间 `order` 表约 1 万行（含 S4b 预建池 3600 + 运行中插入 2880 + 同轮未清数据）：**achieved=63.0 QPS、p50=496.1ms**；S4b 长档（60s、从 6 行起）**achieved=96.2 QPS、p50=292.4ms**。⚠️ 早期报出的 68.3 QPS / p50 398.9ms 出自**背压之前**的负载发生器（开环提交 + 无界队列，`achieved` 被算成排空期均值）—— 63.0 不是变差，是变准 |
| 对照实测（CI 首跑，2026-09-21） | CI #8 报告的同一档 S4b：**achieved=119.9 QPS、p50=6.3ms**。同一份 SQL、同一后端、同一断言集，本地 VM 的 p50 是 CI 的 **46–79 倍**（496.1 / 6.3 = 79×，292.4 / 6.3 = 46×） |
| 影响 | ① **慢**：无索引 → 每次 CAS 全表扫，扫描成本随 `order` 行数近线性增长，并发下 32 线程各自全表扫，退化被放大（只读对照：6 行 p50=1ms → 12006 行 p50=94ms）；② 与 §5 行 9 同一索引缺失的**性能面** |
| **定性修正（2026-09-21，CI 首跑带回）** | **「120 QPS 不可达」是本地环境的属性，不是索引缺失本身造成的** —— CI 上同份数据可达 119.9 QPS。TD-14 的性能影响**确凿**（本地 p50 292–496ms vs CI p50 6.3ms），但写成「A3 目标不可达」是归因错误：本地达不到 120 QPS 还叠加了 VM 的网络 / 磁盘瓶颈。索引缺失的代价要用**同一环境内**的行数对照来证（见上「实测取证」②），不能拿跨环境的达成率当证据 |
| 与并发缺陷的关系 | `concurrency-map.md §5` 第 9 行记的是 `OrderId` 无**唯一**索引 → 同 id 可重复插入（**正确性面**，由 A-S2a-1 证明）；本项是同一索引缺失的**性能面**（全表扫）。修法部分重合（先加普通索引即解性能，唯一索引另需先清重复行），验收口径不同，分列 |
| 建议 | 给 `order.OrderId` 建普通 B-tree 索引即可消除扫描成本；是否升级为唯一索引取决于 §5 行 9 的正确性修复。**A3 不加**：`mall_management_sql.sql` 不得修改，且本缺陷正是 A3 要证明仍然存在的东西 |
| 优先级 | 高 |
| 状态 | 待确认（A3 登记，不加索引；**定性已于 2026-09-21 修正**，见上两行） |

## TD-15 `loginAdd` 在 `usertype` 非法值时返回「添加成功！」但不插任何行

| 项 | 内容 |
|---|---|
| 现状 | `MallManagementUserController.loginAdd` 的 `usertype` 分支只识别「客户」/「商家」两个值；取到其它值时既不插 `login` 也不插 `user`/`customerimage`，方法仍返回字符串 `添加成功！` |
| 问题 | **返回值与实际行为不符**：调用方无法区分「真的插入了一行」和「参数非法、被静默丢弃」。前端若拼错 `usertype`（或传空）会看到成功提示，而后台无任何数据落库，故障对使用者和日志都不可见 |
| 实测发现 | A3 的 B-2 场景期间连带发现。用 curl 单独复现（该请求未带 `charset`）：响应为 `添加成功！`，而 JDBC 侧 `login` 行数**不变** |
| 影响 | ① 数据静默丢失；② 掩盖前端调用错误；③ 使「以响应体判成功」的测试口径在本端点上失真 —— A3 的 B-2 因此不能只用 `添加成功！` 计数，必须同时用 JDBC 核对行数（已如此实现） |
| 与 TD-07 的关系 | TD-07 是「文件上传失败被静默吞掉」（异常被吞）；本项是**没有异常、正常返回里的语义谎言**。同一类「失败不可见」，成因不同，分列 |
| 建议 | 二选一：① 校验 `usertype` 枚举，非法值返回明确错误（HTTP 4xx 或非成功响应体）；② 保留成功语义但补插默认类型。倾向 ① —— 静默降级比报错更难排查 |
| 优先级 | 中 |
| 状态 | 待确认（A3 登记，不在 A3 修） |

## TD-16 `--reset-db` 是死参数：声明了破坏性语义，实际什么都不做

| 项 | 内容 |
|---|---|
| 现状 | `ConcurrencyRunner` 的 `--reset-db` 全文件只出现 5 处，全是「声明（`Opt.resetDb`）/ 解析（`case "--reset-db"`）/ 启动横幅 / 写进 JSON 报告（`options.resetDb`）/ HELP 文字」，**没有任何执行路径** —— 不打开 `mall_management_sql.sql`，不执行任何 `DROP`，不碰数据库。 |
| 问题 | 不是「少个功能」，是**谎报做了**：库脏了传 `--reset-db`，横幅打 `resetDb : true`、报告也写 `true`、退出码 0，操作者会以为库已重置，实际一行没动；下一次运行 P4 报「库脏」，成因完全不可见。与 `--report-dir`（修复前）、`recLoad()` 同类，但更坏：前两者是「该做的没做」，本项是「声称做了」。 |
| 实测发现 | 2026-09-20 设计 CI 集成时核对，方式是**全文件大小写不敏感搜 `reset`**（得 5 处，逐处读过，无一是读取）。⚠️ 方法论要点：不能只数 `o.<字段>` 的出现次数 —— `Opt` 内的访问器读字段时**不带 `o.` 前缀**（如 `--scenarios` 由 `o.has(...)` 消费，字段名本身只有 2 处命中，会被误判为死参数）。判死参数必须读那几行本身，且要覆盖类内访问器这一形态。 |
| 影响 | ① 唯一的「重置库」手段实际不存在；② 危险语义的空转比缺失更坏，它掩盖了「该清库了」这一事实；③ 差点被写进 CI：设计稿最初假设 CI 用 `--reset-db` 供数，若照此落地，CI 会跑到空库上、在 P4 红，且原因难查（已改为用容器自带 `mysql` 客户端导入）。 |
| 与 TD-12 的关系 | TD-12 记录 SQL 文件自带 `CREATE DATABASE` / `USE`（导入其他库需过滤）；本项是「执行这个文件的那条路径根本不存在」。故库重置至今一直是 VM 上手动执行 S0-1 定稿命令，不是脚本能力。 |
| 建议 | 三选一：① 实现它（读 SQL、按语句切分、执行 11 条 `DROP TABLE IF EXISTS` 后重建）—— 要动 runner 的执行路径，且把 11 条 DROP 交给自动化脚本执行，风险高于其便利；② **删掉这个参数**（连 HELP 说明一并删），让「清库只能手动」成为明确事实；③ 至少在 HELP 与 `--help` 里标注「未实现」。倾向 ② —— 一个不工作的破坏性开关，不如没有。 |
| 优先级 | 中 |
| 状态 | 待确认（A3 登记；不在 A3 修，也不在 CI 集成阶段实现） |

## TD-17 `orderAdd` 不校验三个状态位：客户端可直接伪造终态订单

| 项 | 内容 |
|---|---|
| 现状 | `MallManagementGoodsController.orderAdd` 以 `int` 直接接收 `PayIf` / `DeliveryIf` / `ReceiveIf`，原样绑定进 `INSERT ... INTO mall_management_sql.order`，方法体内**无任何取值校验** —— 既无 `{0,1}` 白名单，也无「新建订单只能是初始态」的约束。`rows > 0` 即返回 `success: true`。 |
| 问题 | 三个状态位是**写入方的自选值**，不是状态机的输出：调用方可直接插入一条 `(1,1,1)` 的「已付款 + 已发货 + 已收货」订单，全程不经过付款 / 发货 / 收货。三个 CAS 端点（`orderPayIfUp` 的 `AND PayIf = 0`；`orderReceiveIfUp` 的 `AND PayIf = 1 AND ReceiveIf = 0`；`orderDeliveryIfUp` 的 `AND PayIf = 1 AND DeliveryIf = 0 AND ReceiveIf = 0`）防的是**并发重复流转**，其前提「必须先处于前一状态」在 `orderAdd` 这里根本不存在 —— 于是状态机可以从入口被绕过。 |
| 实测发现 | H3 的 S4a 场景即以此构造前置态（用 `orderAdd` 直接造 `(1,1,1)` 终态订单）—— 一个**测试**手段，反过来说明**生产接口**具备同一能力。旁证（同一方法内，一并可读）：`Price` 是 `float` 且不校验正负（**该条已独立分级**，见 `defect-survey.md` 病 B「服务端零价格校验」——此处保留，因它是本条「伪造状态位」的**同方法实例**，搬走会削弱论证）；`OrderId` 不查重（即 TD-14 / B-1 的 `order.OrderId` 无唯一索引）。另：`SettlementDate` **不在** `orderAdd` 的列清单里，故伪造出来的终态单该列为空 —— 与 BD-01「由付款 / 发货 / 收货三处覆写」的假设不一致。 |
| 影响 | ① 业务上可凭空造出「已收货」订单，影响对账与报表口径（连带上一条的 `SettlementDate` 为空）；② 绕过 BD-01 所依赖的流转时序；③ **A3 的 A 组「无非法状态流转」断言只在并发路径上成立**，与本次入口的伪造能力无关 —— 故 A3 不会因它变红。这一点与 TD-15 同因：**都是 A3 的断言口径盖不到的地方**，也是它们至今未被发现的原因。 |
| 与 TD-15 的关系 | TD-15 是「返回成功但什么都没做」（静默说谎）；本项是「老老实实做了不该做的事」（缺校验放行非法输入）。两者同属「端点对输入不做校验」，一个不作为、一个乱作为，故分列。 |
| 建议 | ① 新建订单只允许初始态：三个状态位**不接收客户端传值**，一律写 0（或直接在 SQL 里写常量）；若确需导入历史订单，另开一个带鉴权的管理端点。② 顺带补 `PayIf/DeliveryIf/ReceiveIf ∈ {0,1}` 与 `Price >= 0` 的校验（当前 `int` 连 2 都收）—— 其中 `Price` 一条已**独立分级**，见 `defect-survey.md` 病 B「服务端零价格校验」，此处不重复展开。 |
| 优先级 | 中 —— 无鉴权（BD-03）放大了可达性；但它造成的是「不合业务时序的数据」而非数据不一致，且本项目定位为内部管理系统。 |
| 状态 | 待确认（A3 登记，不在 A3 修 ——「不改业务代码」是 A3 的硬约束）。D5 裁决：**登记不门禁** —— A3 不为它新增断言，以免把「已知缺陷的复现」混进 A 组的回归证明。 |
| 锚点更正（2026-09-30） | 本条原用 `G:` + 行号锚定（`G:390` / `G:398-400` / `G:403-417` / `G:527` / `G:549` / `G:571`），**六处全部漂移**（B2 / B3 往该 controller 加行所致）。已改为**符号名**锚定（`MallManagementGoodsController.orderAdd` / `.orderPayIfUp` / `.orderReceiveIfUp` / `.orderDeliveryIfUp`），行号锚点一律撤除；2026-09-30 核一次导航用行号：方法 `:386`、三状态位入参 `:393-395`、`INSERT` 绑定 `:435-449`、三 CAS `@PostMapping` `:567` / `:579` / `:591`。⚠️ 其中 `G:390` 是最阴的一种漂：它仍落在 `orderAdd` 方法体内（现为 `Price` 参数行），**看着是对的**。这是 `defect-survey.md §五`「锚点漂移」的**新证人**，也加固了 Class A #8（锚点改符号名）的理由 |

## 已完成里程碑

> 本节记录已交付的阶段性成果，与上方「活跃技术债」区分。新里程碑追加在最下方。

### M1 — A2 基建（2026-09-18）

| 项 | 交付物 | 证据 |
|---|---|---|
| smoke test | 4 用例：`LoginSelectSmokeTest`、`OrderPayIfUpSmokeTest`（成功分支 + 失败分支）、`ImgMappingSmokeTest` | `78f3ef1`、`8709e14` |
| CI 配置 | GitHub Actions：后端 `./mvnw -B test` 全量 + 前端 `npm ci && npm run build`；JDK 17 / Node 20.17.0 / `~/.m2` 缓存 / surefire 报告 artifact | `417254e`、`bcada27` |
| CI 实跑 | 5 次运行全绿（Run ID 35328548406、35329177960、35329430908、35330326189、35330643388） | `gh run list` |
| paths-ignore | 纯文档 push 不触发 CI。实证：全仓库 5 次运行无一 `headSha` 为 `23dfaab` | `df99a53`、`c4807d9`、`23dfaab` |
| workflow_dispatch | 手动触发按钮在 main 上可见（GitHub 仅对默认分支显示） | `8ecbf1a` |
| 文档漂移修复 | project-map 目录树与结论、api-contract §4、agent.md 目录说明、Vue 版本事实错误 | `23dfaab` |
| TD-10 处置 | `contextLoads()` 加 `@Disabled`，解除 CI 阻塞 | `c3cd9f6`、`d62e89d`、`4b84ee2` |
| TD-11 登记 | 前端 36 个依赖漏洞（2 critical / 14 high / 18 moderate / 2 low） | `0eefced` |

**硬性前置**：`Mall-management-server/mvnw` 可执行位 `100644 → 100755`，Linux runner 必需 —— `f7d6172`。

**收尾状态**：主源 7 个 `.java`、测试源 5 个；全项目 5 用例 = smoke 层 4 个（CI 实跑）+ `contextLoads` 1 个（`@Disabled`）。

> 上句是 **A2 当时的快照**。A3 已改：`@Disabled` 换成 `A3_ENABLED` 环境开关，并新增 `databaseIsReachable()` → 全项目 6 用例（默认 SKIP 2 个）。见上方 TD-10（已关闭）。

### M2 — A3 并发测试（2026-09-20）

| 项 | 交付物 | 证据 |
|---|---|---|
| 并发脚本 | `scripts/concurrency/ConcurrencyRunner.java` **2270 行**（单文件、不引新依赖，只用 JDK 内置 `HttpClient` + `ExecutorService` + JDBC）+ `scripts/concurrency/run.sh` **151 行**（两步编译 + 驱动解析，校验 jar major=8） | `333ae1a` … `6465655`（阶段 A–E） |
| 负载与校验 | **6 个负载段落**（S1 / S2a / S2b / S3 / S4a / S4b —— 与「4 场景」不矛盾，见 pack §场景计数口径澄清）；负载走 HTTP、断言与清理走 JDBC（五张表都没有删除接口）；preflight P0–P7 全 PASS | pack §验收标准、§脚本内置校验 P0–P7 |
| a3 profile | `application-a3.properties` **57 行** —— 指向独立 MySQL 实例（库名仍为 `mall_management_sql`，TD-13 所迫） | `c37cb8e` |
| 字符集根治 | 放弃「单文件源码启动」（由启动器就地编译、不采用编译期 `-encoding` → 宿主 `file.encoding=GBK` 使源码中文字面量失真 → 依赖中文响应体的断言**静默落空**）→ 改**两步编译** `javac -encoding UTF-8` + `java -cp <out>`；`charsetSelfCheck()` 每次运行自检，并把 `file.encoding` / 启动方式写进日志与 JSON 报告 | `6ae41d5` |
| CI job | `.github/workflows/ci.yml` 增至 **256 行**，新增第三个 job `concurrency`（`needs: backend`、service container `mysql:8.4`、job 级 `concurrency` 覆盖 `cancel-in-progress`、`continue-on-error: true`） | `af75b97` |
| CI 首跑 | **通过——结论见 artifact `a3-concurrency-report`**。**`concurrency` job 仍是观测跑，不是门禁** —— 门禁地位待裁决，本条在裁决前不改 | `ci.yml:252`；pack §CI 集成 |
| TD 变更 | **登记 TD-14 / TD-15 / TD-16 / TD-17**；**关闭 TD-10**（D1 落地：`@Disabled` → `A3_ENABLED` 环境开关 + 新增 `databaseIsReachable()` 以 `SELECT 1` 强制真连库）；**修正 TD-14 定性**（「120 QPS 不可达」改判为本地环境属性，非索引缺失一因所致） | `6934886` / `1150e89` / `dce2307` / `27d3205` / `99206a5`；`a4225ad` |
| 文档 | pack **356 行** + worklog **246 行** + `environment.md` **70 行**（均为**落盘时**行数；pack 与 worklog 其后因 TD-14 定性修正增至 368 / 247） | `3137776` / `54fdd35` / `e3a9ada` |
| 实测缺陷 | B-2 三处 TOCTOU `rounds=5 / reproRounds=5/5`（两次独立实测逐轮数字一致）；A-S2a-1：**20 并发同参 `orderAdd` 插出 20 行**（`5xx=0`）；S4b 本地 `63.0` / `96.2` QPS vs **CI 同档 `119.9` QPS / p50 6.3ms**；TD-14 行数对照 6 行 p50 1ms → 6006 行 51ms → 12006 行 94ms | pack §实测数据；本地报告 `scripts/concurrency/reports/a3-report-*.json` |

**硬性前置**：`scripts/concurrency/run.sh` 可执行位 `100644 → 100755`，Linux runner 必需 —— `d47371a`。

**收尾状态**：A3 全部合入 `main`（`fc4e0fe`，PR #3）；CI 三 job 全绿；`concurrency` job 仍是观测跑（门禁地位待定）。**零业务 Java 改动**（除 D1 那一个测试类）；全项目 6 用例 = smoke 层 4 + `contextLoads` / `databaseIsReachable` 2（默认 SKIP 2 个，同 M1 脚注口径）。

### M3 — B 阶段优化地基（2026-09-29）

| 项 | 交付物 | 证据 |
|---|---|---|
| 两级缓存 | `TwoLevelCacheManager` + `TwoLevelCache`（Caffeine L1 60s / 上限 1000 + Redis L2 5m）；**8 个 cacheName**：`goodstype`、`userPublic`、`goodsHome`、`goodsByMerchant`、`goodsPrice`、`goodsCodes`、`friendList`、`friendRequests`；写路径 evict 收敛成 Service 单入口（3 个实例） | `3678719`；`79d2a0d` … `52a944f`（8 个 cacheName）；`a3e50cf`（单入口） |
| 缓存三防护（逐项定态） | **击穿** ✅ `sync = true` 单飞（冷 L1 + 冷 L2 打 20 并发：修前 20 次 DB → 修后 1 次）；**穿透** ✅ 负缓存（0 行 → 真 `null` → 被缓存，首个实例 `userPublic`）；**布隆过滤器** ❌ **明确不采纳**（记不采纳，不冒充欠债）；**雪崩（Redis 不可达）** ✅ 冷却窗口 30s + 探测超时 200ms（黑洞端点实测：首个冷读 3.376s → 之后 4~7ms，全程 `5xx = 0`）；**雪崩（TTL 抖动）** ❌ 不实现（触发条件：某缓存族 key 数 > ~10） | `f693bdb`（击穿）；`549f74f`（负缓存）；`84cd200`（冷却窗口）；`cache-layer.md §9.1` |
| L2 探活 | `L2HealthProbe` 单例 + `L2ProbeConfig`（探测通道**不注册为 bean** —— 否则会顶掉主连接工厂，L2 静默跑在无连接池的探测通道上而 A3 看不出）；探测代价实测稳态 **233ms**、进程内首次 **489 / 612 / 772ms**；`mall.cache.l2.up` gauge（⚠️ 0 ≠ Redis 已死） | `437140e`、`84cd200`、`9c245d2` |
| 观测地基 | `MallCacheMetrics` —— **11 个 `mall.cache.*` 指标** + **512 槽事件环**，「计数 + 事件流」合并于同一调用点（防两通道口径漂移）；Actuator 暴露 `health,metrics`；**关闭 Boot 的 Redis health indicator**（它是绕过冷却窗口的第二条探测通道） | `aab3b9d` |
| 分布式锁 | `DistributedLockService.runLocked` 模板方法 + **5 个落点**（`order-add` / `order-pay` / `order-receive` / `order-delivery` / `friend-add`）；`app.lock.wait=5s` / `lease=10s` / `cooldown=30s`；**fail-open**：取锁超时、Redis 不可达、冷却窗口**三路都回落原路径**，不新发任何拒绝文案 | `8810efe` |
| 锁观测 | `MallLockMetrics` —— **4 个 `mall.lock.*` 指标**（`acquires` / `wait.duration` / `holds` / `up`）+ **独立** 512 槽事件环（不共用缓存那份，代价换 C1 一个字不动） | `8810efe`；`application.properties` B2 节 |
| 锁专项场景 | `LockSceneService` 四形状（`WAIT` / `CONFLICT` / `TIMEOUT` / `DEADLOCK`）；完备性不变量「四路计数之和 == 预期取锁次数」，对不上即抛 —— 不返回偏小的分布 | `8810efe`、`LockSceneServiceTest` |
| **缺陷翻转（终态）** | **A-S2a-1 `EXPECTED_DEFECT_PRESENT` → `PASS`**：20 并发同 `OrderId` 的 `orderAdd` 落库行数 **20 → 1**；**B-2 的 friend 那一路 `friendDupRounds` 5/5 → 0/5**（`friendAdd` 原有查重的 TOCTOU 窗口被锁关闭）。**未翻转两路**：`login5xxRounds` 5/5、`goodstypeDupRounds` 5/5 —— 与「5 落点不含 `loginAdd` / `goodstype`」自洽 | 对照 **18 份** B2 前报告（`2026-09-20` ~ `09-29 14:40`，`friendDupRounds` **全部 5**；其中 **12 份**含 S2a 段，`rowsForSameOrderId` **全部 20**）+ `a3-report-b2-0929a-20260929-195531.json`、`a3-report-b2-0929b-20260929-202046.json` |
| 文档 | B1 收尾 worklog `archive/worklog-2026-09-29-b1.md` **327 行**（含「三档数字」可复核性口径）；方案/现状 `context-packs/cache-layer.md` **476 行** | `1e53788`、`7012176` |
| TD 变更 | **无** —— B1 / C1 / B2 三笔均未改动 `tech-debt.md`（该文件最后一次改动即 M2 本身 `c5a230b`）；B1 发现的工程约束记在 `cache-layer.md §11`，未升为 TD | `git log --follow docs/agent/tech-debt.md` |

**硬性前置**：① `pom.xml` 新增 **6 个依赖**（`spring-boot-starter-cache`、`caffeine`、`spring-boot-starter-data-redis`、`commons-pool2`、`spring-boot-starter-actuator`、`redisson:3.46.0`）；② **运行时需 Redis 实例** —— CI 的 `concurrency` job 内以步骤 `docker run -d --name a3-redis -p 6379:6379 redis:7 redis-server --requirepass 123456` 提供（**刻意不用 services**：官方 redis 镜像 entrypoint 不接受环境变量传参，见 `ci.yml:153-164`）。

**收尾状态**：B1 / C1 / B2 三笔全合入 `main`（`dcf2d39` / `3f8c4b7` / `aacd2bb`，PR #4 / #5 / #6）；CI 三 job 全绿；`concurrency` job 仍是观测跑（`continue-on-error: true`，门禁地位同 M2 未裁）。全项目 **51 用例**（10 个可执行测试类，Failures 0 / Errors 0，默认 SKIP 8 = `LockSceneRedisTest` 4 + `MallManagementApplicationTests` 4；2026-09-29 21:04 本地实测）；主源 21 个 `.java`、测试源 11 个。观测出口 2 个，共 **15 个 `mall.*` 指标**。

### M4 — B3 订单状态机 + 异步时间线（2026-09-30）

> 标题日期 = 合并笔 `148e77b` 的**提交日（+0800 本地）** `2026-09-30 00:02`，**不取** GitHub / CI 显示的 `2026-09-29T16:02:35Z`。
> 此即 M2 的覆辙处：`fc4e0fe` 本地为 `2026-09-21 00:10`，M2 标题却写 `2026-09-20`（用了 UTC 日期）。B3 的代码笔全在本地 `09-29 23:10–23:49`，唯合并笔跨了本地零点。

| 项 | 交付物 | 证据 |
|---|---|---|
| 状态机（核心） | `OrderAction`(64) / `OrderState`(124) / `OrderStateMachine`(281) —— **无状态、描述性转移表**：守卫层（SQL 谓词 CAS）与语义层（状态名）显式分离、差集可测；**golden copy 回归闸**钉住线上 SQL 逐字符不变 | `b226357` |
| 四个写方法接入 | `MallManagementGoodsController` +55/-40：`orderAdd`（`orderAddLocked:455` 记 **CREATE** —— 只在 `rows > 0` 之后记，「订单已存在」早退分支不记，因未产生新状态）+ `orderPayIfUp` / `orderReceiveIfUp` / `orderDeliveryIfUp` 三 CAS 端点（统一经 `casTransition` 记转移）。**CREATE 是必需项而非可选项** —— 时间线的第一条就是出生 | `428293f` |
| 异步出口 | `OrderStateExecutor`(289) —— 单池 core2/max4 + `ArrayBlockingQueue(1024)` + discard-oldest-with-callback（**丢观测、不丢业务**）；`OccurredAt` 在**锁临界区内**取 | `428293f` |
| 时间线（两类分工） | `OrderTransitionRecorder`(187) —— 在 UPDATE 返回后**立刻**于临界区内判定/读回三位/埋点/入队（`submitTimeline` → `executor.submit`）；`OrderTimelineWriter`(137) —— 时间线的**唯一写入点**，跑异步线程、**永不抛**（fail-soft，`result=error` 即该条永久丢，不重投）。读回在 UPDATE **之后**、由新态**精确反推** `FromState`（非近似） | `428293f` |
| 观测 | `MallOrderStateMetrics`(267) —— **6 个 `mall.order.state.*` 指标**（`transitions` / `illegal` / `async.duration` / `async.queue.depth` / `async.rejected` / `timeline.writes`） | `428293f` |
| 建表 | `order_state_transition` 追加至 `mall_management_sql.sql:221-235`：bigint PK + `idx_order_state_transition_order(OrderId, OccurredAt)`；**全库唯一带列 COMMENT 的表**、唯一 `datetime(3)` 列 | `e93a499` |
| A3 扩展 | `ConcurrencyRunner.java` +407/-6：新增 **C 组时间线核对**（**record-only**，不门禁）；P3 表数判据改为「随 DDL 是否落盘走」 | `4bc78d2` |
| 配置 | `application.properties` +26（B3 节） | `428293f` |
| 合并与 CI | PR #7 合入 `main`（`148e77b`）；CI `success`（headSha = `148e77b`） | `gh run list` |

**决策（B3 期间）—— 四项选型明确不补 + 登记触发条件**：Resilience4j 限流熔断（触发：出现第 2 个外部依赖，或需按接口限流）、Spring Retry 死锁重试（触发：出现多表写事务）、Logback JSON 编码器（触发：接入日志聚合）、线程池分池（触发：出现第 2 个异步出口且其任务不可丢）。⇒ **四项均三层前不必补**。

**发现（B3 后普查，纯文档，`10da544`）**：

| 文档 | 内容 | 规模 |
|---|---|---|
| `db-schema.md` | schema 完整视图：12 表（11 原始 + B3 追加 1）+ §6–§10 逐列 / 索引 / 外键 / 跨表类型基线 | +287/-4 |
| `defect-survey.md` | 缺陷普查：**七病 + 七前提 + 元发现 + Class A/B 分岔**；三条总判断 ——「局部推理极强、组合推理缺席」/ 七前提 / 测试与缺陷不重叠 | 226 |
| `selection-status.md` | 7 项选型逐项：**3 已落地 + 1 自研替代 + 3 未落地**；「切面 + 注解」整体未落地（全仓 `@interface` = 0） | 90 |

**Class A 8 项 + Class B 6 项待裁决**（`defect-survey.md §7`），为三层前输入。

**待裁决两处**（`selection-status.md §5`，本次**未动 `agent.md`**）：① `agent.md:142`「能引库就不自研」与 B3 自研状态机背离；② 「乐观锁」是否已被谓词守卫等效覆盖 —— 判定为**非等价替换**，是有意的语义差异（TD-17）。

**硬性前置**：**无新增依赖** —— B3 全范围 `pom.xml` 零改动（`git diff --name-only 4ef77f1..148e77b -- Mall-management-server/pom.xml` 为空）。运行时需 Redis，同 M3（CI 内 `docker run ... redis:7 --requirepass`，刻意不用 services）。

**收尾状态**：B3 合入 `main`（`148e77b`，PR #7）；CI 三 job 全绿；`concurrency` job 仍是观测跑（`continue-on-error: true`，门禁地位同 M2 / M3 未裁）。普查三份文档 `10da544` 为纯文档 ⇒ **`paths-ignore` 生效、未触发 CI**（实证：近 25 次运行无一 `headSha` = `10da544`）。全项目 **`@Test` 111 处**（15 个含测试的类；默认 SKIP 8 = `LockSceneRedisTest` 4 + `MallManagementApplicationTests` 4，均 `@EnabledIfEnvironmentVariable(A3_ENABLED)`）；主源 **28** 个 `.java`、测试源 **16** 个。观测出口累计 **21 个 `mall.*` 指标**（11 cache + 4 lock + 6 order.state）。

**TD 变更**：**无** —— `tech-debt.md` 在 B3 全期间未被改动（最后一次改动 `4ef77f1` = M3 本身）。B3 的待落项共三笔，均属「声明过、从未落盘」：`db-migration.md` 的 B3 行、**M4 本条**、`agent.md` 技术选型最小改。
