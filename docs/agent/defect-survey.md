# 缺陷与未实现功能普查（B3 合并后）

> 范围：全部后端源码、`mall_management_sql.sql`、`docs/agent/*`、`.github/workflows/ci.yml`、`scripts/concurrency/*`。
> 核查日期：2026-09-30。基线 `origin/main` = `148e77b`（B3 已合并）。
> **本文只记录与分级，不做任何修复。** 分级与锚点一律给可复核位置。
>
> **档位**：`亲读` = 逐行读过该文件；`亲读(框架)` = 从本机 `.m2` 抽框架源码/元数据核过；`实测` = 本机实跑探针；`推断` = 由亲读证据链推导，未实跑。

---

## 一、三条总判断

**判断 1 —— 不是「没有层」，是「局部推理极强、组合推理缺席」。**
单个类的论证密度极高（`OrderTransitionRecorder` 的类注释本身就是一份设计说明；`OrderStateMachine` 把守卫层/语义层显式分离并让差集可测）。但**跨组件的相互作用无人认领**。标本：B2 的锁是刻意 fail-open 的、B3 的时间线 `from` 反推必须在锁内 —— 两个设计各自都写了理由，**合起来没人看过**（§三 病 F）。

**判断 2 —— 七条前提**（§二）。这是本次普查最该留下的产物。

**判断 3 —— 测试与缺陷不重叠。** 111 例绿，但没有一例盖在下列任何一条新缺陷上。最重的三条（凭据、上传串图、L2 永不过期）**零测试覆盖**。

---

## 二、前提清单（多路径依赖、但没有任何东西保证）

| # | 前提 | 谁在依赖 | 保证它的东西 |
|---|---|---|---|
| A | `order.OrderId` 唯一 | 时间线主排序键、重放、C-2 | 前端 `Math.random()`，**无约束** |
| B | `goods(MerchantId, GoodName, ProductCode)` 唯一 | `priceByTriple`、`queryForMap` | **无** |
| B′ | `goods(MerchantId, ProductCode)` 唯一 | 与 B 并存的另一套键 | **无**，且**与 B 冲突** |
| C | `login.Id` 唯一 | 用户身份 | 客户端直传 `userid`，**无** |
| D | 同单转移被串行化 | 时间线 `from`、排序 | 分布式锁 —— **fail-open** |
| E | `utf8mb4_0900_ai_ci` 不区分大小写/重音 | 全部按名查重 | 服务器默认值，**非本仓声明** |
| F | L2 TTL 是陈旧度上界 | 缓存正确性 | **仅 a3 profile**；默认 profile TTL = **-1** |
| G | `friend(customerid, friendid)` 唯一 | `friendList` / `friendRequests` 缓存 | **无**（本次新列；原清单未含） |

**B + E 的合成链（本次最值钱的一条）**：`SQL:15` 的 `utf8mb4_0900_ai_ci`（大小写/重音不敏感）+ 三元组无唯一约束 + `GoodsCacheService:211-219` 的 `queryForMap` **多行即抛** + `GoodsCacheService:186-198` 把**失败也当值缓存** ⇒ `priceByTriple` 退化成「把一个失败结果缓存满整个 TTL」。四环各自都「对」，组合起来是错的。

---

## 三、缺陷清单（按「病」分组 —— 五病一因：缺层）

### 病 0｜总纲
无身份层、无校验层、无 DAO 层、无环境层。**四层都缺，所以每条缺陷都能同时用「没分层」解释，也就没人觉得该由自己修。**

### 病 A｜无身份层

| 项 | 位置 | 后果 | 档位 |
|---|---|---|---|
| CORS 等价「任意 Origin + 允许凭据」 | `ImgConfig.java:14,17,21` | `addAllowedOriginPattern("*")` + `setAllowCredentials(true)` + `/**` ⇒ 任意站点可带凭据读全部响应。**合法且最宽**，不是配错是配太开 | 亲读(框架) |
| 上条覆盖 actuator | 同上 + `application.properties:97` | 匿名跨源读 `/actuator/metrics`（连接池等基础设施画像） | 推断 |
| 两套 CORS 机制并存 | 4 处裸 `@CrossOrigin`：`MallManagementGoodsController:27` / `MallManagementUserController:20` / `MallManagementMessage:18` / `HealthController:37` | CorsFilter 是 servlet filter，先于 DispatcherServlet ⇒ handler 级配置**静默失效**；将来有人只改 `@CrossOrigin` 收紧 → 改了不生效且无提示 | 亲读(框架) |
| 全端点匿名可达 | `pom.xml` 无 spring-security；fat jar grep = 0 | 登录是业务接口级判断，不是框架鉴权 | 亲读 |
| 登录口令明文进 stdout，随 CI artifact 扩散 | `MallManagementUserController.java:39` | `System.out.println(username + "," + password)`；`ci.yml:315` 上传 `app.log` | 亲读 |
| `getUserInfo` 返回 null ⇒ **HTTP 200 + 0 字节响应体** | 全仓 `ResponseBodyAdvice`/`@ControllerAdvice` = 0 | 写路径整段在 `if (body != null)` 内（Spring 6.2.6 `AbstractMessageConverterMethodProcessor:359`）⇒ 前端拿到「成功」但无内容 | 亲读(框架) |

### 病 B｜无校验层

| 项 | 位置 | 后果 | 档位 |
|---|---|---|---|
| 三处上传零类型校验，目录整体作静态资源回源 | `MallManagementGoodsController:106-113` / `:282-287`；`MallManagementUserController:193-199`；`ImgPath.java:34` | 可传 `.html`/`.svg`，同源存储型 XSS 面 | 亲读 + 推断(可利用性) |
| **同名文件静默跳过、DB 照样记 URL** | `GoodsController:111-113,119`；`UserController:197-199,205` | 商家 B 传同名 `1.jpg` → 文件不落盘、DB 指向**商家 A 的图**、接口返回 `success:true` | 亲读 |
| catch 归因一律「已存在」 | `GoodsController:114-116`；`UserController:200-203` | 磁盘满/权限错被说成「已存在」，排查方向被带偏 | 亲读 |
| `spring.servlet.multipart.*` 完全未配 | 全仓 0 命中 | 走 Boot 默认 1MB，超限抛 `MaxUploadSizeExceededException` → 无全局异常处理 → 用户拿框架错误体 | 亲读(框架) |
| 无全局异常处理 / 无拦截器（除 CorsFilter） | 全仓 `@ControllerAdvice`/`@ExceptionHandler`/`HandlerInterceptor` = **0** | 未捕获异常走 Boot 默认错误体（含 `timestamp/path`），错误文案与 HTTP 码全项目不统一 | 亲读 |
| `orderAdd` 三位状态位由客户端直传（TD-17） | `OrderStateMachine.java:36-38` 自述 | 伪造态唯一入口；**只观测不拦截**（D2），拦截需业务裁决 | 亲读 |
| `goodstype.GoodTypeNew` 可被显式传 NULL 打 `NOT NULL DEFAULT '0'` | `mall_management_sql.sql:76` | 行为待验（见 §七） | 亲读 |

### 病 C｜无 DAO 层

| 项 | 位置 | 后果 | 档位 |
|---|---|---|---|
| `productSearch` 逐字 `OR GoodName LIKE` | `GoodsController:346-367` | `keyword.trim().split("")` 每字符一个 OR；单引号即 SQL 语法错误；`SELECT *` **无 schema 前缀**、无 LIMIT | 亲读 |
| `goodsDe` 两个 DELETE 返回值都不捕获 | `GoodsController:205-225` | `:224` 无条件 `success=true`；无 `@Transactional`、无归属校验、无 LIMIT | 亲读 |
| `app.upload-dir` 默认值串**三处重复** | `ImgPath:18` / `GoodsController:55` / `UserController:30` | 改一处必漏两处（与 TD-06 同形态） | 亲读 |
| `goodsUp` 的 WHERE 缺 `GoodName` | `GoodsController:281-290` 一带 | 批量改名的串改风险 | 亲读 |
| 落库 URL 前缀硬编码且未做 URL 编码 | `GoodsController:119,289`；`UserController:205`；另 `UserController:59` | 换机/反代即全体图片失效；中文/空格文件名（仓内既有 `色款.jpg`）拼出非法 URL | 亲读 |
| **`orderAddLocked` 不得加 `@Transactional`** | javadoc `GoodsController:401-409` | 锁在 `runLocked` 的 `finally` 先释放、事务后提交 ⇒ 加了则去重立刻失效。**这是设计约束不是缺陷**，但它是全项目唯一「禁止用标准注解」处，无处登记 | 亲读 |
| `loginAdd` 应用层 TOCTOU 预检 + 两个死参数 | `UserController:70-71`、`:63-64`（`defaultValue="暂无数据"`）、`:75`（死 `int role;`） | 预检在事务外；死参数误导调用方 | 亲读 |
| `loginSelect` 广域 catch 吞掉一切 | `UserController:47-49` | 任何异常（含库连不上）都返回 `0` = 「登录失败」，与「密码错」不可区分 | 亲读 |

### 病 D｜无环境层（**最重的一族**）

| 项 | 位置 | 后果 | 档位 |
|---|---|---|---|
| **L2 条目永不过期** | `CacheConfig.java:110-112` + 默认 profile 无 TTL | `spring.cache.redis.time-to-live` 为 null ⇒ `defaultCacheConfig()` ⇒ **TTL = -1**。`goodstype::all` 写入后**永久陈旧**，且 L1 60s 到期后还会从 L2 读回永不失效的旧值 | 亲读(框架) |
| 默认 profile 无 `spring.data.redis.*` | `application.properties` 全文（a3 有 6 项） | 静默连 `localhost:6379` 无口令，或 WARN 后永久走库 —— **性能静默退化除日志外无出口** | 亲读 |
| **生产 = 默认 profile** | 只有 default 与 a3，无 dev/prod | 跑的是「开发期路径 + root/123456 + 无 Redis 配置 + 无 L2 TTL」这套 | 亲读 |
| `upload-dir` 默认解析到 CWD 下**源码目录** | `application.properties:111` | 打 jar 后写 jar 旁边或直接失败 | 亲读 |
| **上传目录不可写 ⇒ 整个应用起不来** | `ImgPath.java:23-27` `@PostConstruct` | 非核心功能把服务整体拖死，无降级 | 亲读 |
| MySQL 不可达 ⇒ 启动失败 | `HikariConfig.initializationFailTimeout=1` | 无「先起再自愈」路径；纯内存的缓存/锁路径也被拖死 | 推断 |
| `show-sql=true` 在默认 profile 且 a3 未覆盖 | `application.properties:11` | 当前空转（零 `@Entity`），**加实体即刻全量打 SQL** —— 埋在降级路径上的地雷 | 亲读 |
| `printStackTrace()` × 20 + `System.out.println` × 5 | `GoodsController`(11) / `MallManagementMessage:188,413` / `UserController:216,257` / `GoodsCacheService:96,147,221,296` / `GoodsTypeCacheService:79` | 异常只进 stdout，无级别、无 logger 名、无上下文；线上故障无 ERROR 级可检索记录 | 亲读 |
| `open-in-view` 未设 ⇒ 默认 true | 全仓 0 命中 | 纯 JdbcTemplate 项目**零收益**，白挂一个拦截器 + 一条启动 WARN | 亲读(框架) |
| 无日志配置文件 | 全仓 `logback*`/`*.yml` = 0 命中 | 只有 Boot 默认 console appender；生产靠 `nohup > app.log`（`ci.yml:213`）⇒ 单个无界文件，无 rotation | 亲读 |
| 无 Dockerfile / 迁移工具 / README | 全仓 0 命中 | 无容器化路径（而病 D 的上传 bug 在容器里最致命）；schema 变更靠人肉登记 | 亲读 |
| `mysql:mysql-connector-java:8.0.33` 旧坐标 | `pom.xml:57-61` | **不受 parent 托管**（Boot 3.4.5 托管 9.1.0）⇒ 安全修补不随 parent 升级自动跟进 | 亲读 |
| 无依赖漏洞扫描 | 无 `dependabot.yml`、无 OWASP check、无 `npm audit` | TD-11 记的前端 36 漏洞（2 critical/14 high）至今靠人记得 | 亲读 |

### 病 E｜凭据

| 项 | 位置 | 后果 | 档位 |
|---|---|---|---|
| root/123456 明文在**默认=生产** profile | `application.properties:6-7` | 默认 profile 全文无 `${}` 覆盖口子 | 亲读 |
| Redis 口令明文作默认值 | `application-a3.properties:31` | CI 起 Redis 时**故意**不设该变量以对齐 ⇒ 默认值就是线上口令 | 亲读 |
| SSH 口令 + 全套凭据写进运维文档 | `environment.md:10,14,27` | 有人写了处置建议（`:143-157`），**但文档里的人肉提醒不是机制** | 亲读 |
| 口令自**初始提交**起就在 git 历史 | `9b87ab9`、`dd5d3e1` | 改工作区/.gitignore 都清不掉 blob，**泄露面 = 历史** | 亲读 |
| CI 内三处明文 + `GRANT ALL ON *.*` | `ci.yml:126,164,189` | 口令与 a3 默认值强耦合 | 亲读 |

### 病 F｜观测层自身的缺陷（B1/B2/B3 新增代码里）

| 项 | 位置 | 后果 | 档位 |
|---|---|---|---|
| **时间线链正确性依赖锁可用，而锁刻意 fail-open** | `OrderTransitionRecorder.java:24-31` + B2 fail-open | `(1,0,0)` 上 **deliver × receive** 两个不同动作可**同时提交**（各要另一位为 0，互不冲突；pay × dly 则因 `PayIf` 互斥不可能双成功）。锁不可用时读回发生在两次提交之后 ⇒ `from` 错、**链断**。A3 的 C-2 断言过 `链断=0`，但**只在 Redis 可用时测得** | 亲读 + 推断 |
| `L2HealthProbe.down` 是 check-then-act | `L2HealthProbe.java:154-177` | 普通 `volatile boolean`，并发失败会**都记一次**，与注释「计数天然只在翻转时 +1」矛盾。`L2HealthProbeConcurrencyTest` **只断言 `pingCount`，不覆盖此项** | 亲读 |
| `l2Put` 冷却路径**不记任何指标** | `TwoLevelCache.java:220-233` | `l2Get`(DEGRADED)/`l2Evict`/`l2Clear` 都记，唯 `l2Put` 静默返回 ⇒ 指标口径有缺口 | 亲读 |
| `get()` 命中 L2 后**无条件**回填 L1；`evict()` 是 L1 先 L2 后 | `TwoLevelCache.java:89-94,173-176` | 拓宽了已知的 L2→L1 回填竞态窗口（原登记见 `cache-layer.md:371`、`:448③`） | 亲读 |
| `OrderTimelineWriter.java:47-48` 点名的索引与 DDL 不一致 | 同左 | 注释指向的索引名与建表语句不符 | 亲读 |
| 启动日志打 `queue.size()` 而非 `queueCapacity` | `OrderStateExecutor` | 启动期读数无意义 | 亲读 |
| `max-size=4` **结构性不可达** | `OrderStateExecutor`（core2/max4 + `ArrayBlockingQueue(1024)`） | 有界队列满之前不会建超过 core 的线程 ⇒ max 永远到不了 | 亲读(框架) |

### 病 G｜Schema 层

完整字段/索引/外键基线见 `db-schema.md §6-§10`。此处只列缺陷：

| 项 | 位置 | 后果 | 档位 |
|---|---|---|---|
| `order.Price` float vs `goods.Price` double | `SQL:146` vs `:58` | 同一语义两种精度 | 亲读 |
| `CreateTime`/`SettlementDate` 存成 `varchar(50)` | `SQL:152-153` | 时间当字符串 | 亲读 |
| `productsales.GoodName char(20)` vs `goods.GoodName char(40)` | `SQL:169` vs `:57` | 21–40 字符名写此表报 **Error 1406**（**实测**：本机 8.0.32 `@@sql_mode` 含 `STRICT_TRANS_TABLES` ⇒ 用户可见失败，**非静默截断**）。⚠️ 但 `.sql` **导入会话**是例外：`:13` 覆写 sql_mode 为 `NO_AUTO_VALUE_ON_ZERO`（非严格），`:216` 才恢复 ⇒ 导入期种子数据超长会**静默截断**。**TD 候选，未登记** | 实测 |
| `order.MerchantId`/`CustomerId` varchar(40) vs 其余表 char(20)/varchar(50) | `SQL:286-288` | 同一业务号三种宽度、两种定长性，无 FK 兜底（`db-schema.md §9`） | 亲读 |
| `login.UserName char(20)` PK + 五个明文种子账号 | `SQL:94`、`:99` | 明文口令。**唯一性本身没问题**（曾疑为缺陷，已自证否） | 亲读 |
| `BD-01`：付款/发货/收货三处覆写同一 `SettlementDate` | `OrderStateMachine.java:42-47` 自述 | 该列无法稳定表示任何业务时间点；B3 的时间线表在事实上取代其数据源，**但那是 C3 的事** | 亲读 |

**附带**：唯一库脚本是全库重建（11 条 `DROP TABLE`），而 B3 新表是 `CREATE TABLE IF NOT EXISTS` 且**无对应 DROP** ⇒ 本机重复导入会让旧 schema 留存、漂移不可见（CI 每次新容器，故只在开发机上）。

---

## 四、计划外功能缺口（未实现 → 会引出的新增/重组）

| 缺口 | 现状 | 补齐什么之后才真正需要 |
|---|---|---|
| 身份/鉴权层 | 无 spring-security | 任何「按角色过滤数据」的需求 |
| 校验层 | 无 validation，`@Valid` = 0 | 任何非 `@RequestParam` 型入参 |
| DAO 层 | 30 主源码、**零 `@Entity`**、裸 `JdbcTemplate` 散落 | 第三个数据源出现时 |
| 前端 API 层 | `fetch("http://localhost:8088/...")` 直接写死 | 换域名/加反代时 |
| 前端产物托管 | `static/` 下只有 8 张图、无 `index.html` | 要单端口部署时（**这也是 CORS 现在必需的原因**：前端 8080 dev server 真跨源打 8088） |
| 全局异常处理 | 0 处 | 现在就缺 |
| 多环境 profile | 只有 default 与 a3 | 生产 = default（病 D） |
| 迁移工具 | 无 Flyway/Liquibase | 第一次面对「有数据的库」 |
| 依赖漏洞扫描 | 无 | TD-11 至今无自动化 |
| Resilience4j / Spring Retry / Logback JSON / 线程池分池 / 切面+注解 | 见 `selection-status.md` | **四项均有明确触发条件，三层前不必补** |

---

## 五、已登记的补强（不是新缺陷，但有新证据加宽）

- **L2→L1 回填竞态**：已登记于 `cache-layer.md:371` 与 `:448③`。新增的是**范围**：`get()` 无条件回填（`TwoLevelCache.java:89-94`）+ `evict()` L1 先 L2 后（`:173-176`），窗口比原「清空片」的框法更宽。
- **文档锚点漂移**：新增**三个独立证人** —— TD-04（写 3 处 `@CrossOrigin`，实为 **4**）、TD-06（写 `U:192/G:111/G:300`，实为 `U:205/G:119/G:289`，且**遗漏 `U:59`**）、TD-07（写 `G:102-108/U:187-190`，实为 `G:110-116/U:196-203`）。**建议：锚点从行号改为符号名。**（其余 TD 未逐条复核，不排除同类。）
- `db-schema.md` 的 `.sql` 行号引用**经全量验算，无漂移**（真实行号口径 —— 该文件 CRLF 会让 `tr` 之类的读取把行号翻倍，易被误判为漂移）。
- 「`recentEvents()` 无调用方」的说法**过宽**：`MallLockMetrics.recentEvents()` 在生产路径有调用（`LockSceneService:358,401`）。
- 仓内有一条**文件名里带字面双引号**的已跟踪路径：`static/img/"色款.jpg"`。

**已排除（不计为缺陷，登记以免重复提出）**：
- `order_state_transition` 不在 `db-migration.md` —— 那是声明过的待落项（「单独一笔」）。
- 3 处手写 `getConnection()` —— **实为 `RedisConnection` 且均为 try-with-resources**（`L2HealthProbe:202`、`L2ProbeConfig:200`）⇒ 与 Hikari `leakDetectionThreshold=0` 无关，此条不成立。
- `rdiscount`/redisson 版本取舍、`R2` 等已有充分可复核理由者，非缺陷。

---

## 六、元发现：文档化的控制 ≠ 真控制

| 声明的东西 | 实际 | 位置 |
|---|---|---|
| `--reset-db` | **空实现** —— 声明、打印、报告，从未实现 | `ConcurrencyRunner.java:340` |
| A3 硬门禁进退出码 | `continue-on-error: true` 是 **job 级** ⇒ **全部非门禁** | `ci.yml:117` |
| TD-01 的描述 | 措辞**掩盖了两处语义变化** | `tech-debt.md` |
| 启动日志的队列容量 | 打的是 `queue.size()` 而非 `queueCapacity` | `OrderStateExecutor` |
| `max-size=4` 生效 | **结构性不可达** | 同上 |
| 时间线索引 | 注释点名的索引与 DDL 不一致 | `OrderTimelineWriter.java:47-48` |
| `L2HealthProbe` 翻转计数 | check-then-act，并发下**会多记** | `L2HealthProbe.java:154-177` |

**共性**：控制**写在文档/注释/日志里**，而**不写在被执行的路径上**。审计时读文档会得出「已覆盖」的错结论。

---

## 七、Class A / Class B 分岔（下一步怎么修）

**Class A —— 语义无歧义，先加固、后补最小断言**（可直接做）：
1. `goodsUp` 的 WHERE 补 `GoodName`（串改风险）
2. `goodstype.GoodTypeNew` 不许显式传 NULL 打 `NOT NULL DEFAULT '0'`
3. `login.Id` 唯一性（前提 C）
4. 三处上传提取 basename + 类型白名单
5. 同名文件不静默跳过
6. `l2Put` 冷却路径补指标（对齐另三条）
7. `L2HealthProbe.down` 改 CAS（对齐注释的承诺）
8. 文档锚点 → 符号名；TD-04/06/07 更正；`db-schema.md` 的 11 表/索引口径更正（**已完成**）

**Class B —— 语义未定，须先裁决**（不能动手）：
1. `goodsUp` 改名粒度（改一条还是批量）
2. `goodsAdd` / `goods` / `productsales` 的事务边界 —— 「**半成品商品**」的修法是给 `goodsAdd` 加 `@Transactional`；注意 `orderAdd` 那条「不得加 `@Transactional`」（`GoodsController:405-408`）**不适用于此处**
3. `productsales` 硬删 vs 软删
4. 时间线的动作集是否需要 cancel/refund
5. `BD-01` 的 `SettlementDate` 归谁
6. **TD-17 伪造态：观测已做，拦截需业务裁决**（拦截会使 A3 的 S4a 全部 8 条断言失去前置）

---

## 八、存档待验

1. `goodstype.GoodTypeNew` 显式 NULL 打 `NOT NULL DEFAULT '0'` 的实际行为。
2. 上传路径穿越 —— 代码侧确证**无 basename 提取**（`GoodsController:106-107,282-283`；`UserController:193-194`），但可利用性取决于 Tomcat 是否在 `getSubmittedFileName()` 剥离路径段。**在探针结果出来前只能写「无校验」，不能写「可穿越」。**
3. 本机无 8.4 目标库，C 组端到端跑不了（P1 版本门禁会拦下）。

---

## 九、本次两处自我更正（留档，防再犯）

1. **`FromState` 取自 UPDATE **之后**，是精确反推，不是近似。** 曾推断「先读三位、再 UPDATE、并发下 from 记错」——**证伪**。`OrderTransitionRecorder.java:98` 读回在 UPDATE 之后；`:111` 由新态反推旧态（`from ≡ to 清本位`）；前提两半都被钉住：守卫约束本位（`OrderStateMachine.java:228-229`，测试 `everyGuardRequiresItsOwnBitToBeZero`）+ `setClause()` 结构性只吐一列（`:130-133`）且整串由 golden copy 钉死。**但风险没消失，只是挪到了「锁必须可用」上**（见病 F）。
2. **「没有任何测试打过同单并发不同动作」——证伪。** `ConcurrencyRunner.java:1523` 的 c7 就是 40 线程 pay×dly 抢同一单（`:1481` `A-S4a-7`）。**但**：pay×dly 因 `PayIf` 互斥而**不可能双成功**（是守卫 + InnoDB 行锁保证的，**不是**分布式锁），真正的漏洞组合是 `(1,0,0)` 上的 deliver×receive，**A3 无此组合**。

---

## 十、边界

- 全程**只读**（唯一写入是本次落盘的三份 `docs/agent/*`）；未碰 `db-migration.md`（**2026-09-30 更正**：B3 收尾时已登记 `order_state_transition`，见 `db-migration.md` 变更登记第 1 行）；未加 PR 评论；未做任何修复。
- `order_state_transition` 落盘**已确证、本地可复核**：`/tmp/b3ci/a3-full.log:1421`（P3 表数=12）、`:1480`（C-1）、`:1481`（C-2）；机制是 `ci.yml:186` 整份导入 `.sql` 而 B3 建表在文件末尾。
- 相关文件：`db-schema.md`（schema 完整视图）、`selection-status.md`（选型现状）、`tech-debt.md`（已登记项）。
