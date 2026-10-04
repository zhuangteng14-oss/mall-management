# context-pack: three-layers

> **定位**：三层重构**开工时读**。上游是 `defect-survey.md`（缺陷普查）+ `selection-status.md`（选型现状）+ `db-schema.md`（schema 视图）。
> 本文**只做提取与归属**，不含新事实 —— 每条都能追到那三份文档之一。
> 定稿：2026-09-30（`origin/main` = `e930b30`）。
> **修订：2026-09-30（`origin/main` = `3917cd3`）** —— C 组已升门禁（`5544362`）、P0 #1～#4 已修、新增 **G. 缺陷修复归属**。
> **修订（2026-09-30，`origin/main` = `2cc2338`）** —— **D#1 禁令已登记**（新增 `### D.1`）、**D#4 DAO 选型已定**（新增 `### D.4`）、新增 **H. A3 覆盖矩阵**。⚠️ **修订（2026-10-02，三层第三笔 = 用户域，PR #32 `0c07e96`）** —— 用户域 5 个端点的业务体与全部内联 SQL 下沉 `UserService` / `UserDao`；本文凡引用 `UserController:<n>` 处已就地重指为 `UserService.<方法>:<行>` / `UserDao.<方法>:<行>`（行号 = 方法声明行）。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-02 实测**；重构后须重测。
>
> **修订（2026-10-04，`fix/goods-write-tx` = C#2 落地）** —— **C#2 已裁并落地**：商品域三个写方法（`GoodsService.goodsAdd` / `goodsUp` / `goodsDe`）加 `@Transactional` **+ catch 内标记回滚**。本文凡称 C#2「未裁 / 阻塞落笔 / 应当加」处已就地改为「✅ 已落地」，并订正 **D.1「现状」行的真注解计数（1 → 4 处）** —— 那个数被本笔改了。**D#1 禁令本身一字未动**。

## 任务

把 **Controller / Service / DAO** 三层拆出来，并把普查发现的缺陷按「属于哪一层」归位。

## 三层范围定义（2026-09-30 定）

| 层 | 内容 |
|---|---|
| **Controller** | 只做入参绑定 / 分支 / 文案 |
| **Service** | 业务规则、事务边界、**校验** |
| **DAO** | 数据访问出口；消灭硬编码 schema 名（**开题记 39；2026-10-02 复测仍 39，但已由 2 个文件散成 7 个**，见 §F#1）。⚠️ 商品域只做到**部分**收敛：内联 8 条已进 `GoodsDao`，四个缓存读路径与 `goodstype` 的语句仍留在两个 Cache Service（那是 `@Cacheable` 的落点）—— 该域因此有**两条**数据访问路径，不是「唯一出口」 |

**另两层的归宿**（`defect-survey.md §三 病 0` 说「四层都缺」）：

- 病 0 的「**校验层**」→ **三层内做**（Service 责任）
- 病 0 的「**身份层**」（鉴权）→ **三层后单独做**
- 病 0 的「**环境层**」（profile / 配置）→ **与三层并行或之后**

> ⚠️ 「三层」这个名字**不等于**病 0 的四层。别把四层当成四笔工作。

## 必读文档

1. `agent.md`（规则）
2. `docs/agent/defect-survey.md`（七病 + 七前提 + 元发现 + Class A/B 分岔）
3. `docs/agent/db-schema.md §6–§10`（逐列 / 索引 / 外键 / 类型基线 —— 三层可直接引用，不必再读 `.sql`）
4. `docs/agent/selection-status.md`（选型现状；未落地的四项都**有触发条件**）
5. `docs/agent/tech-debt.md`（TD-01 ~ TD-17；TD-17 含 2026-09-30 锚点更正）

## 必读源码

- `GoodsController.java` / `OrderController.java`（商品与订单**路由 + 入参绑定**。⚠️ 两域的业务逻辑均已下沉，两个 Controller 现在都**不持有 `JdbcTemplate`**：商品 → `GoodsService`（业务）/ `GoodsDao`（8 条内联 SQL），订单 → `OrderService` / `OrderDao`。订单的 `@Transactional` 禁令现随业务逻辑下沉，见 `OrderService` 类 javadoc 与 D#1）
- `GoodsService.java` / `GoodsDao.java`（**商品域三层，2026-10-02 落定**。DAO 边界的裁决是「**只收 Controller 内联的 8 条**」，四个缓存读路径**不进** DAO —— 代价与理由见 `GoodsDao` 类 javadoc）
- `UserController.java`（user 域路由 + 入参绑定；业务体与全部内联 SQL 已下沉 `UserService`（319）/ `UserDao`（242），**2026-10-02 三层第三笔**）、`MessageController.java` / `MessageService.java` / `MessageDao.java`（**message 域三层，2026-10-03 第四笔落定**。`MessageController` 417 → **127** 行；DAO 的裁决是「只收 Controller 内联的 14 处调用点、合并其中一对逐字相同的 SQL ⇒ 13 方法」。⚠️ **message 域`不是`「数据出口收成一处」**：`FriendCacheService`（5 条）+ `UserCacheService.publicInfo`（1 条）是**缓存读路径**，按 `three-layers.md:40` 的边界裁决**不进** DAO —— 代价与理由见 `MessageDao` 类 javadoc）
- `mall_management_sql.sql`（权威 schema，**CRLF 陷阱**）

## 约束

1. 只允许**追加**：新增表 / 新增字段 / 新增索引（含 `UNIQUE`）无条件允许；新增 **FK** / 改列类型**有条件允许**
   （四项前置见 `agent.md` 数据库管理规则 4）。禁止删表、删列、删字段、删已有索引与约束（全局硬约束 3，规则 v2）。
2. 所有 DB 变更登记在 `db-migration.md`（含回滚 SQL）。**回滚 = 逻辑废弃、非物理删除** —— 原「回滚 vs 规则 4」的
   固有矛盾已由规则 v2 裁决（见 `business-decisions.md` BD-02、`tech-debt.md` TD-03）。
3. **向后兼容**：不得删旧接口、旧字段。
4. ⚠️ **`orderAddLocked` 不得加 `@Transactional`** —— 见 D#1。**这条不可外推到其他方法**；反面是 C#2（`goodsAdd` **应当**加）—— ✅ **C#2 已于 2026-10-04 落地**（`fix/goods-write-tx`：商品域三个写方法 `goodsAdd` / `goodsUp` / `goodsDe` 加 `@Transactional` + catch 内标记回滚）。本禁令**不受影响**：它管的是「被打锁的临界区」，商品域无锁。

## 验收标准

共用底线：

- 后端 `cd Mall-management-server && mvnw -B test` 全绿
- 前端 `cd Mall-management-web/mall-management-system && npm run build` 通过
- ✅ A3 的 **C 组（时间线核对）自 2026-09-30 起是门禁**（C-1/C-2 由 `gate=false` 升为 `true`，`5544362`） —— 改坏时间线，**CI 会红**。⚠️ 本文**定稿时**它还是 record-only（`e930b30` 早于 `5544362`），见 E 档与「最容易漏的两条」
- ⚠️ 种子行数 `6/5/5/5/4` 是 **P4 判据基线**，改种子数据会先让 P4 变红（`db-schema.md §6.0`）

各阶段验收见文末「开工顺序建议」。

## 相关 TD / BD

TD-01（ddl-auto）、TD-03（回滚规则矛盾）、TD-04/06/07/08（锚点与上传）、TD-13（schema 硬编码）、TD-14（OrderId 无索引）、TD-15/16/17（静默谎言 / 死参数 / 伪造态）、BD-01（SettlementDate）、BD-03（无鉴权）。

---

## A. P0 级 —— 不补会出错

| # | 项 | 位置 | 为什么是 P0 | 归属 | 工作量 |
|---|---|---|---|---|---|
| 1 | **L2 条目永不过期** | `CacheConfig.java:110-112` + 默认 profile 无 TTL | `time-to-live` = null ⇒ `defaultCacheConfig()` ⇒ **TTL = -1**。`goodstype::all` 写入后永久陈旧，L1 60s 到期还从 L2 读回永不失效的旧值 | ✅ **已修**（`743ab4c`，A 批）—— 原归属「并行（环境层） ⚠️ 别拖到三层后」 | 小 |
| 2 | **失败被当值缓存（B+E 合成链）** | `GoodsCacheService.priceByTriple:243-264`（`@Cacheable` `:241-242`；0 行→null `:250-253`；多行抛 `:255-261`）+ 前提 B/E | collation 大小写不敏感 + 三元组无唯一约束 ⇒ 多行 ⇒ `queryForMap` 抛 ⇒ 被当值缓存 ⇒ `priceByTriple` 退化成「把失败缓存满整个 TTL」 | ✅ **已修**（`88a14e4`，PR #10）—— 原归属「三层内（DAO / Service）」。⚠️ **只关掉「失败被固化到 TTL」这一环**：第 1/2 环（collation + 三元组无唯一约束）仍在，**失败本身仍存在** | 小 |
| 3 | **同名文件静默跳过，DB 记别人的图** | `GoodsService:109-120`（`UploadStorage.store` → `GoodImg` 拼接；三层前为 `GoodsController:100-111`）、`UserService.updateAvatar:254-264,266` | 商家 B 传同名 `1.jpg` → 文件不落盘、DB 指向**商家 A 的图**、返回 `success:true` | ✅ **已修**（`efe7959`，A 批，同名策略 C）—— 原归属「三层内（Controller / Service）」，与 B#5 同一件事 | 小 |
| 4 | **上传目录不可写 ⇒ 整个应用起不来** | `ImgPath.java:45-64`（`@PostConstruct`） | 非核心功能拖死服务，无降级 | ✅ **已修**（`70eace2`，A 批，启动降级）—— 原归属「三层内（Service，启动期）」 | 小 |
| 5 | **`getUserInfo` null ⇒ HTTP 200 + 0 字节** | 全仓 `ResponseBodyAdvice` / `@ControllerAdvice` = 0 | 写路径整段在 `if (body != null)` 内 ⇒ 前端拿「成功」但无内容 | **三层内（全局异常出口）** | 小 |

## A′. 与三层并行 —— 安全 / 环境

| # | 项 | 位置 | 说明 | 归属 | 工作量 |
|---|---|---|---|---|---|
| 6 | 口令明文进 stdout + 随 CI artifact 扩散 | `UserController.java:39`、`ci.yml:315` | 泄露成立，但不改变运行结果 | 并行（环境层）；**git 历史清理另计** | 小 / 中～大 |
| 7 | 生产 = 默认 profile | 只有 `default` 与 `a3` | 它把 #1、#8 串起来；修它本身就是环境层 | 并行（环境层） | 中 |
| 8 | root:123456 与 Redis 口令明文 | `application.properties:6-7`、`application-a3.properties:31` | 同 #7 | 并行（环境层） | 小～中 |

## B. Class A —— 语义无歧义，可直接做

| # | 项 | 位置 | 归属 | 工作量 |
|---|---|---|---|---|
| 1 | **服务端零价格校验**（只做 `Price >= 0` + 上界） | 入参绑定 `GoodsController:76`；业务侧 `GoodsService.goodsAdd`（`:83-148`，**无任何校验**）—— 三层前为 `GoodsController:390 → :435-449`（⚠️ 该旧坐标在 2026-10-02 已是**越界行号**，本笔按现行源码重测） | **三层内（Service 校验）**。⚠️ 按 `goods` 表**重算**属 **C 档** —— 重算会改行为（客户端值被覆盖），是「换真值源」不是「加校验」 | 小 |
| 2 | `goodsUp` 的 WHERE 补 `GoodName` | `GoodsService:286-291`（两条 update 调用）→ `GoodsDao:154-165`（WHERE 的构造点；三层前为 `GoodsController:317-330`） | 三层内（DAO / Service） | 小 |
| 3 | `goodstype.GoodTypeNew` 不许显式传 NULL | `mall_management_sql.sql:76` | 三层内（Service 校验）。_行为本身待验，见「待验」_ | 小 |
| 4 | `login.Id` 唯一性（前提 C） | 无约束，客户端直传 `userid` | 三层内（Service）；DB 侧加 UNIQUE 属 schema 变更 | 小 / 中 |
| 5 | 三处上传提取 basename + 类型白名单 | `GoodsService:105-117`（goodsAdd）/ `:265-284`（goodsUp）、`UserService.updateAvatar:254-264`（三层前为 `GoodsController:106-113,282-287`） | 三层内（Service）。⚠️ **路径穿越仍待验**，不得写成「可穿越」 | 小 |
| 6 | `l2Put` 冷却路径补指标 | `TwoLevelCache.java:221-234` | 并行（观测） | 小 |
| 7 | `L2HealthProbe.down` 改 CAS | `L2HealthProbe.java:154-177` | 并行（观测） | 小 |
| 8 | 文档锚点 → 符号名 | `defect-survey.md §五` | 独立（文档）。**TD-17 已于 2026-09-30 改完**；TD-04/06/07 待改 | 小～中 |

## C. Class B —— 语义未定，须先裁决

| # | 项 | 卡住什么 | 归属 | 工作量 |
|---|---|---|---|---|
| 1 | `goodsUp` 改名粒度（一条 vs 批量） | — | 裁决先行 → 三层内实施 | 小 |
| 2 | **`goodsAdd` / `goods` / `productsales` 事务边界** | **DAO / 事务层**：「半成品商品」的修法是给 `goodsAdd` 加 `@Transactional`。⚠️ 这是 **D#1 的反面** —— `orderAdd` 那条**不得加**只适用于它自己，**不要外推** | ✅ **已落地（2026-10-04，`fix/goods-write-tx`）** —— 范围**扩到三处**（`goodsAdd` + `goodsUp` + `goodsDe`，同文件 / 同表对 / 同修法）；**落点是 Service 写方法**，不是 DAO。⚠️ 关键：三个方法都**自己吞异常**，故只加注解不够，catch 内须 `setRollbackOnly`（`GoodsService.markRollbackOnlyIfActive()`） | 中 |
| 3 | `productsales` 硬删 vs 软删 | — | 裁决先行 → 三层内 | 小 |
| 4 | 时间线动作集是否需 cancel/refund | B3 时间线扩展 / C3 视图 | 三层后（C3） | 小 / 中 |
| 5 | `BD-01` `SettlementDate` 归谁 | `db-schema.md §4` 方案 A/B/C | 裁决先行 → 三层后（C3） | 小 / 中 |
| 6 | **TD-17 伪造态：拦截 vs 观测** | **校验层** —— 拦截会使 A3 的 S4a **全部 8 条断言失去前置** | **三层内（校验层）** | 小 / 中 |

## D. 结构性缺陷 —— 三层重构时会撞上的

| # | 项 | 位置 | 为何会撞 | 归属 | 工作量 |
|---|---|---|---|---|---|
| 1 | **`orderAddLocked` 不得加 `@Transactional`** | 禁令现随订单逻辑下沉：`OrderService` 类 javadoc `:26`（全项目唯一禁令处）、`addLocked` 声明 `:124`、`OrderDao` 类 javadoc `:39`（原在 `GoodsController` javadoc，2026-10-02 随订单端点拆出） | 锁在 `runLocked` 的 `finally` 先释放、事务提交在方法返回**之后** ⇒ 后进者的 `COUNT(*)` 在 RR 下读自己事务开始时的快照、**看不到前一个未提交的行** ⇒ 仍插 2 行。全项目**唯一「禁止用标准注解」处** —— ✅ **已登记**（`### D.1`，2026-09-30 本笔），不再「无处登记」 | **三层前（新层须知）** ✅ **本笔落定** | 小（登记）✅ / 中（设计规避） |
| 2 | 无全局异常处理 / 无拦截器 | `@ControllerAdvice` / `@ExceptionHandler` / `HandlerInterceptor` = **0** | 新层抛出的异常无统一出口。**2026-09-30 实测（限定版）**：**框架层**错误出口已实测 —— 缺必填参数→**400**、未知路由→**404**，两者均落 **Boot 默认错误体**（`{timestamp,status,error,path}`，非本仓自造）；**业务层**异常出口**未验** | **三层内（三层产出）** | 中 |
| 3 | 两套 CORS 机制并存 | `ImgConfig` 全局 `CorsFilter` + 5 处裸 `@CrossOrigin`（`GoodsController:38` / `OrderController:24` / `UserController:42` / ~~`MessageController:18`~~ **`MessageController:44`**（2026-10-03 第四笔重写后） / `HealthController:37`） | CorsFilter 先于 DispatcherServlet ⇒ handler 级**静默失效** | **三层内（拦截器层）** | 小 |
| 4 | `ddl-auto=update` + 零 `@Entity` | `application.properties:12`、`pom.xml:51`（TD-01） | 一旦有人加 `@Entity` 即反向 `ALTER TABLE`；`show-sql=true` 同刻全量打 SQL。**DAO 选型已定（`### D.4`）⇒ 不引 JPA ⇒ 本风险不会自行发生**；⚠️ 但 `spring-boot-starter-data-jpa` **不删**（`agent.md:36-37`「禁止删除旧配置」） | **三层前（定 DAO）** ✅ **本笔落定：`JdbcClient` 短期 + jOOQ 长期**（`### D.4`）；⚠️ **结论本笔落、实现随三层第一笔（订单域开工）** | 小 |
| 5 | `app.upload-dir` 默认值三处重复 | `ImgPath:17` / `GoodsService:69` / `UserService:78` | 改一处必漏两处（同 TD-06 形态）。⚠️ 商品域那一处在 2026-10-02 从 `GoodsController` 的 `@Value` **字段**（`:46`）搬成了 `GoodsService` 构造器的 `@Value` **形参** —— 仍是三份，位置换了 | 三层内（配置收敛） | 小 |
| 6 | 落库 URL 前缀硬编码且未 URL 编码 | `GoodsService:120,283`、`UserService.updateAvatar:266`、另 `UserController.loginAdd:63`（三层前商品侧为 `GoodsController:111,312`） | 换机 / 反代全体图片失效；中文 / 空格文件名拼非法 URL（仓内既有 `色款.jpg`） | 三层内（Service / 配置） | 小 |
| 7 | 无 logger（`printStackTrace` × 20 + `println` × 5）、无日志配置、`open-in-view` 默认 true | 病 D | 观测层欠账 | 并行（观测） | 小～中 |
| 8 | 无迁移工具 / 无 Dockerfile | 全仓 0 命中 | 属环境层 | 并行（环境层） | 中 |

### D.1 📌 登记：`orderAddLocked` 的 `@Transactional` 禁令（2026-09-30 本笔落定）

> **D#1 的原话是「无处登记」** —— 本节就是那处。**一句话**：新层里任何被打上
> `DistributedLockService.runLocked` 的临界区，**都不得加 `@Transactional`**。

| 项 | 内容 |
|---|---|
| **禁令** | **`orderAddLocked` 不得加 `@Transactional`** —— 与本文 **`:49` 约束 4** 是同一句。源头（2026-10-02 随订单端点拆出后现址）：`OrderService` 类 javadoc `:26`（禁令标题）、`addLocked` 声明 `:124`、`OrderDao` 类 javadoc `:39` + `DistributedLockService` 类 javadoc「调用点的两条硬要求」（第一颗 bullet） |
| **理由 ① 锁外事务** | 事务的提交发生在**方法返回之后**，而锁在 `runLocked` 的 `finally` 里、即方法**返回之前**已释放 ⇒ 后进者拿到锁后的 `SELECT COUNT(*)` 在 **RR** 下读的是自己事务开始时的快照、**看不到前一个未提交的行** ⇒ 仍插 2 行。**不加事务时 autocommit 让 INSERT 在释放锁之前就提交** —— 这正是临界区查重成立的前提（`A-S2a-1b`：20 并发同 `OrderId` 落库 **20 → 1**，靠的就是这一条） |
| **理由 ② 跨 bean 调用** | 临界区是**传给另一个 bean**（`DistributedLockService`）的 **lambda**，锁的持有者是那个 bean、不是本方法 ⇒ **没有「锁内的 bean 方法边界」可以挂事务**。且三个 Controller 被测试**钉死为无 AOP 代理**（`MallManagementApplicationTests:117` `goodsControllerIsNotAopProxied` / `:137` `messageControllerIsNotAopProxied` / `:157` `orderControllerIsNotAopProxied`）⇒ 注解式 AOP 在这里**结构上不可用**，锁只能是方法体内的显式调用（同类内自调用同样不走代理，同仓 `GoodsService:43-52` 的同一论证；⚠️ 2026-10-02 前该论证在 `GoodsController:236`，随 `productCodePrice` 的 catch 一起下沉） |
| **⚠️ 不可外推** | 本禁令**只管被打锁的方法体**。它的反面是 **C#2**：`goodsAdd`（**没有**打锁）的事务边界**应当**加 `@Transactional` —— 只记住半边就会踩另一边。✅ **C#2 已于 2026-10-04 落地**（`fix/goods-write-tx`，范围含 `goodsUp` / `goodsDe`）；本禁令本身**不变**，两个方向都仍是原状 |
| **现状** | 全项目**唯一一处「禁止用标准注解」**。`orderAddLocked` 目前**未加**（`亲读`：该方法体上无 `@Transactional`）。⚠️ **真注解计数：2026-10-04 起是 4 处** —— `UserService.loginAdd:124` + C#2 新增的 `GoodsService.goodsAdd` / `goodsUp` / `goodsDe`（均**不在此方法上**、也不在任何被打锁的方法上，禁令未破）。⚠️ 原记「全仓 10 处」已在 **2026-10-02 实测订正为 1 处** —— 10 是**把 javadoc / 注释里提到 `@Transactional` 的地方也数了进去**（`grep -rn "@Transactional"` 在 `src/main/java` 于 **2026-10-04 复测得 45 行，其中 4 行是真注解**；2026-10-02 的旧值是 18 行 / 1 行 —— 本笔自己就添了 3 处真注解与若干说明文字）；同源订正见 `selection-status.md §2` 与 `concurrency-map.md §1` 的「仅 1 处」 |

### D.4 📌 登记：DAO 选型结论（2026-09-30 本笔落定）

> **结论**：**短期 `JdbcClient`，长期 `jOOQ`** —— **分两层，不是二选一**。
> **结论本笔落、实现随三层第一笔（订单域开工）** —— 本笔**不动 `pom.xml`、不动任何 Java**。

| 项 | 内容 |
|---|---|
| **短期（三层第一笔起）** | `JdbcClient` —— Spring 的 JDBC 门面（命名参数 + `RowMapper`），**零新依赖**：`spring-boot-starter-jdbc` 已在 `pom.xml:40`。`亲读`：本机 m2 的 `spring-jdbc-6.2.6.jar` 内含 `org/springframework/jdbc/core/simple/JdbcClient.class`（连同 `StatementSpec` / `MappedQuerySpec` 等 11 个 class）—— **不是推断，是解包实测** |
| **为什么短期不用 JPA** | ① **TD-01**：引 JPA 就会有人加 `@Entity`，而 `ddl-auto=update`（`application.properties:12`）会**反向 `ALTER TABLE`**；② 三层第一笔的需求只是「唯一数据访问出口 + 消灭 39 处硬编码 schema 名」（**F#1**），JPA 的实体映射在这里是**净负担**。⚠️ `spring-boot-starter-data-jpa`（`pom.xml:51`）**保留不删**（`agent.md:36-37`） |
| **长期（何时切 jOOQ）** | 等 **DAO 边界稳定**、且出现「类型安全的 SQL 构建 / 代码生成」的实际需求时。**现状（`亲读`）**：jOOQ **全仓 0 命中** —— `pom.xml` 无、`src` 无、`docs` 无；本机 m2 只有 `org/jooq/jooq-bom/3.19.32`（**BOM 元数据，无 jar**）⇒ 现在引入等于**新增一个依赖 + 一套生成流程**，而收益要等 DAO 边界定形才兑现 |
| **与 D#1 / C#2 的关系** | **D#1** 是那条规则的**唯一例外** —— 两者必须一起读。⚠️ 但 2026-10-04 落地时 **C#2 的落点是 Service 写方法（`GoodsService`），不是 DAO**：注解要挂在「一个 bean 的公开方法」上才有代理，而跨两表的两条语句由 Service 编排 ⇒ 挂 DAO 反而要合并方法（见 `GoodsService` 类注释「C#2」） |

## E. 三层前置 —— 开工前要知道的事实

| 项 | 状态 | 说明 |
|---|---|---|
| `db-schema.md §6–§10` 逐列 / 索引 / 外键 / 类型基线 | ✅ **已完成**（12 表全量） | 三层可直接引用 |
| 七前提 A–G | ✅ **已列**（`defect-survey.md §二`） | 是**三层的输入**不是产物 —— 设计新层时逐条问「谁保证它」 |
| 全库除主键外**只有 2 个 UNIQUE**（`order.uk_order_orderId`、`goodstype.uk_goodstype_GoodType`；其余仍零） | ✅ 已确证（§10） | 三层必须承认：唯一性**基本**仍是应用层约定 —— 例外只有两处，均由 DB 自己执行：`order.OrderId`（**2026-09-30 S1a**，`1e2fcfa`）与 `goodstype.GoodType`（**2026-10-04 #11**，`fix/goodstype-unique`）。⚠️ 本行原写「**零 UNIQUE**」，那是 **2026-09-30 S1a 之前**的口径；2026-10-04 前写「只有 1 个」，那是 **#11 之前**的口径。两处认领的**理由不同**：S1a 兜的是「锁 fail-open 时的重复单号」，#11 兜的是「**根本没有锁**」（`GoodsTypeCacheService.addType` 不在 B2 的 5 个锁落点内） |
| `order_state_transition.OrderId → order.OrderId` 的 FK | ⚠️ **建得起来但未建**（原「建不起来」） | 阻塞点只是「父列无索引」**这一条**，与「不保证唯一」是两件事（§8 有实测）。🔄 **2026-09-30 S1a 后阻塞点已消失**（`order.OrderId` 现有索引；唯一索引同样满足「被引用列有索引」），但本笔**未建 FK**（规则 v2 把新增 FK 列为「有条件允许」，四项前置）⇒ 准确说法已从「建不起来」改为「建得起来但未建」 |
| 种子行数 = P4 判据基线 `6/5/5/5/4` | ⚠️ 门禁输入 | 改种子数据会**先让 P4 变红**（§6.0） |
| A3 C 组（时间线核对）**曾是 record-only**，2026-09-30 起升为**门禁**（`5544362`） | ✅ **门禁**（C-1/C-2） | 改坏时间线 **CI 会红**。⚠️ 本行原写「不门禁 / 静默通过」—— 那是**定稿时**（`e930b30`）的结论，**已过期** |
| `mall_management_sql.sql` **CRLF + 1 个裸 CR** | ⚠️ 陷阱 | 用会归一化行尾的编辑器改它，diff 变成整份重写 |

## F. 三层中做 —— 三层本身的产出，不是前置

| # | 项 | 位置 | 为什么不能提前做 |
|---|---|---|---|
| 1 | **SQL 硬编码 schema 名 39 处**（2026-10-02 实测；**与开题记的 39 相等**） | 口径 = **SQL 字面量**（不含注释 / javadoc 里的提及）。按域：**goods 14** = `GoodsDao` 6 + `GoodsCacheService` 5 + `GoodsTypeCacheService` 3；**order 9** = `OrderDao` 7 + `OrderStateMachine` 1 + `OrderTimelineWriter` 1；**`UserDao` 16**；**`MessageDao` 0**（2026-10-03 第四笔后口径修正：原记 `MessageController` 0 —— 本域 SQL 已全数下沉 `MessageDao`，该文件**仍 0 处**，且本笔**合并了那对逐字相同的 SQL 也没动 39**）⇒ 同进程脑裂（TD-13）。⚠️ **总数没变、分布全变**：开题时这 39 处**集中在 2 个 Controller**（`git show 9b87ab9` 实测 = `MallManagementGoodsController` **23** + `MallManagementUserController` **16**）—— 其中 goods 那份 23 里含**订单端点的 SQL 9 条**（`orderAdd` / 四个订单查询 / 三个 CAS UPDATE），2026-10-02 订单笔拆出后正好 **23 = goods 14 + order 9** ⇒ 文件数 **2 → 7**。⚠️ **连注释一起数**（`Mall-management-server/src/main/java/**/*.java` 下 `grep -o "mall_management_sql\."`）得 **50** 处（**2026-10-03 实测**；多出的 **11** 处是 javadoc / 注释）—— ⚠️ 本数**逐笔在涨**：2026-10-02 时为 **48**（本行原记的 **46** 更早，当时已陈旧），第四笔再增 2（`MessageDao` javadoc 两处引用 schema 脚本名）；而同期**口径乙（SQL 字面量）恒为 39**。**不要**再把这个数当「SQL 处数」（原记的 45 就是这么来的：口径混用 —— 商品侧按字面量取 6、订单侧却按含注释取 10+3+2） | 抽 DAO 层时**自然**改完。⚠️ **本行与两笔的实际做法是一处已知矛盾**：订单笔（`OrderDao`）与商品笔（`GoodsDao`）的裁决都是「**逐字保留** schema 前缀，不顺手清」—— 理由是只清一域会造出「一半有前缀、一半没有」的中间态（`GoodsDao` 类 javadoc 有完整理由）。故本行说的「自然改完」**截至 2026-10-02 没有发生**；前缀的清除必须是**单独一笔跨四域**。列为三层前会让人以为要独立一笔重改 —— **比三层本身还大**。它是「为什么需要 DAO 层」的证据，不是它的前置 |

---

## G. 缺陷修复归属（2026-09-30 定）

> **本节只做一件事**：给「还不知道谁来修」的缺陷指定**认领阶段**。`defect-survey.md` 病 0 说「四层都缺」，
> 那四层**不等于**本文的 A～F 档 —— 本节是它们的映射表。
> ⚠️ **「认领」≠「已做」**：右两列分开写，**现状一栏全部是 2026-09-30 复核的结果**，不是计划。
> ⚠️ 已修的 P0 #1～#4 **不在此表**（见上方 **A. P0 级**，归属栏已改标 ✅ 与落地 commit）。

| 缺陷 | 出处 | 认领阶段 | 现状（2026-09-30 复核） |
|---|---|---|---|
| **服务端零价格校验**：`orderAdd` 的 `Price` 由客户端直传直落库 | 病 B；本文 **B#1** | **三层期间（Service 校验）** —— 只做 `Price >= 0` + 上界；⚠️ 按 `goods` 表**重算**属 **C 档**（那是换真值源，不是加校验） | ⏳ **未做** —— `OrderController.orderAdd` 仍是 `@RequestParam("Price") float`（入参绑定），业务逻辑在 `OrderService.orderAdd`，其中无重算、无比对 |
| **状态位拦截**（TD-17：三个状态位可直传出终态订单） | 病 B；**TD-17**；本文 **C#6** | **三层期间（校验层）** —— 前置是**裁决**「拦截 vs 观测」 | ⏳ **未做**（C#6 两方案未裁）。⚠️ 拦截会使 A3 的 S4a **全部 8 条断言失去前置**（H3 正是用 `orderAdd` 造的前置态） |
| **长度边界（字符数量）**：`productsales.GoodName char(20)` vs `goods.GoodName char(40)` | 病 G（`SQL:169` vs `:57`） | **三层期间（Service 校验，长度上界）** —— ⚠️ schema 侧**不改类型**（本文「约束 1」只允许新增表 / 字段 / 索引），只能在 Service 拦 | ⏳ **未做**。21–40 字符名写 `productsales` 报 **Error 1406**（**实测**，用户可见失败）；⚠️ `.sql` **导入会话**非严格 ⇒ 导入期**静默截断** |
| **搜索 SQL**：`productSearch` 逐字 `OR GoodName LIKE` | 病 C（`api-contract.md:88`，**2026-10-03 本笔 +17 重指**；原记 `:72` —— 该行当时已是空行，现址 `:88` 是 `productSearch` 的**业务体现址**行，`:74` 是路由行） | **三层期间（DAO）** —— 参数化与 schema 前缀在抽 DAO 时自然改完（⚠️ 同 §F#1 的已知矛盾：两笔 DAO 的裁决都是「逐字搬运、不顺手改」，**DAO 已建而这两条一条都没改**） | ⏳ **未做** —— 现址 `GoodsDao.searchByKeyword`（`:193-207`，2026-10-02 由 `GoodsController:385-387` 的 SQL 构造搬入）：`keyword.trim().split("")` 每字符一个 OR；单引号即 SQL 语法错误；`SELECT *` 无 schema 前缀、无 `LIMIT` |
| **身份伪造**：登录是**业务接口级判断**，不是框架鉴权 | 病 A；**BD-03** | **鉴权阶段**（**三层后单独做**，见「三层范围定义」） | ⏳ **未做** —— 32 个接口零鉴权、无拦截器、无 Spring Security |
| **上传文件名**：**无 basename 提取**（路径穿越）+ 零类型白名单（可传 `.html` / `.svg`） | 病 B；本文 **B#5**；§七 Class A **#4** | **三层内（Service 校验层）；C3 复用** | ⏳ **未做** —— `UploadStorage.store()` 直接 `uploadDir.resolve(file.getOriginalFilename())`：**basename 与白名单两者都还在**（2026-09-30 复核）。已修的只有**同名策略**那一半（§七 Class A **#5** ✅ `efe7959`）。⚠️ **可穿越性仍待验** —— 取决于 Tomcat 是否在 `getSubmittedFileName()` 剥离路径段；探针出来前只能写「无校验」 |
| **分页** | `api-contract.md:15`「无分页参数，列表接口全量返回」；病 C（`productSearch` / `goodsDe` 均无 `LIMIT`） | **C3 顺带** | ⏳ **未做** |
| **订单幂等**：`orderAdd` 非幂等（`OrderId` 由调用方提供） | `api-contract.md:94`（幂等列）/ `:118`（说明）；**TD-14**（**2026-10-03 本笔 +17 重指**，原记 `:77` / `:101`） | **C3 后** | ✅ **DB 面已做（2026-09-30 S1a，`1e2fcfa`）**：`uk_order_orderId` 已使「同单号重复插入」不可能。⏳ **接口面仍未做** —— 不同单号照旧每调一次插一行，幂等键仍由调用方负责（本文「约束 1」允许**新增索引**，S1a 正是依此落地） |

### G.1 ⚠️ 前端鉴权显示 ≠ 安全边界

> **一句话**：前端按 `sessionStorage` 切页面，那只是**显示**；安全边界只能在**后端拦截器**上。
> （本小节的**前端**两条事实是 2026-09-30 亲读源码所得，其余均可追到病 A 与 `BD-03`。）

| 事实 | 位置 | 说明 |
|---|---|---|
| 前端只有**页面级分支**：无路由守卫、无请求拦截器 | `ProductPage.vue`（`sessionStorage.getItem('merchantid')` 真 / 假 ⇒ `/merchant` 或 `/customer`）、`FriendRoom.vue`、`LoginPage.vue`（按 `/loginSelect` 返回的 `role` 跳三处）；`beforeEach` / `axios.interceptors` 全仓 **0 命中** | 登录后把 `merchantid` / `role` 放进 `sessionStorage`，各页面读它决定去哪个页面 ⇒ **换浏览器、手写 `sessionStorage`、或直接 `curl`**，这一层分支全部绕开 |
| 后端**零鉴权、零拦截器** | `BD-03`：全项目 32 个接口零鉴权，无拦截器、无 Spring Security；`WebMvcConfigurer` 全仓只有 `ImgPath` 一个实现，且**不含 `addInterceptors`** | 「身份」不是会话，而是**请求参数**：`MerchantId` / `CustomerId` 由调用方自报（`goodsAdd` / `goodsDe` / `goodsUp` / `orderAdd` / `productCodePrice` 全部如此）。服务端**没有任何一步**核对「这个 MerchantId 是不是你」 |
| ⇒ 前端那一层只能防误操作 | 同上 | 它挡得住「点错了按钮」，挡不住任何一个会用 `curl` 的人 |

**归属**：**鉴权阶段**（三层后单独做）。BD-03 的三个方案里建议的 A / B **都是拦截器 + 令牌**（A：拦截器 + Redis Session；B：JWT + 拦截器）——
「真安全在后端拦截器」是**既有裁决**，不是本节的新主张。落地位置与依赖见 **D#3（拦截器层）**、**D#2（全局异常出口）**：没有全局异常出口时，拦截器抛出的 401 / 403 会走 Boot 默认错误体。

## H. A3 覆盖矩阵 —— A3 保护了什么（2026-09-30 实测）

> **本节只回答一个问题**：改三层时，**哪些东西坏了 A3 会报警**。
> 事实来源：`scripts/concurrency/ConcurrencyRunner.java`（2795 行，`亲读`）。
> ⚠️ 已有的 `concurrency-map.md`（13 行）只有「**有什么问题**」、**没有「保护范围」列** —— 本节是它的补集。

### H.1 A3 实际打到的端点 —— 9 个（8 个业务端点 + 1 个探针）

| 端点 | 脚本里的角色 | 出现行（均为 `ConcurrencyRunner.java` 行号） |
|---|---|---|
| `/order/orderAdd` | **前置态构造**（S4a 的 H3 —— 三个状态位是入参、可任意置位）+ 并发写 | `:1441 :1494 :1525 :1594 :1623 :1700 :1842` |
| `/order/orderPayIfUp` | 并发 CAS | `:1541 :1626 :1704 :1855` |
| `/order/orderDeliveryIfUp` | 并发 CAS | `:1602 :1605 :1608 :1626 :1708 :1856` |
| `/order/orderReceiveIfUp` | 并发 CAS | `:1611 :1614 :1617 :1712 :1857` |
| `/goods/goodstype` | 读侧基线（S1） | `:1399 :1715 :2178` |
| `/user/loginSelect` | 读侧基线（S1） | `:1400` |
| `/user/loginAdd` | TOCTOU 观测（B-2 段） | `:2156` |
| `/message/friendAdd` | TOCTOU 观测（B-2 段） | `:2197` |
| `/health/db` | **探针，不是被压端点** —— P5 就绪 / P6 库身份 / P7 阴性对照 | `:2592` |

⇒ **受 A3 保护的业务端点 = 8 个**（上表除 `/health/db`）。

### H.2 门禁项 —— `gate = true` 者失败即脚本退出码 1

| 段 | 门禁项 | 编号 | 行 |
|---|---|---|---|
| S1 | 3 | `A-S1-1` / `A-S1-2` / `A-S1-3` | `:1393-1395` |
| S2a | 4 | `A-S2a-1a` / `A-S2a-1b` / `A-S2a-1c` / `A-S2a-2` | `:1431-1434` |
| S2b | 2 | `A-S2b-1` / `A-S2b-2` | `:1491-1492` |
| S3 | 4 | `A-S3-1` … `A-S3-4` | `:1515-1518` |
| **S4a** | **8** | `A-S4a-1` … `A-S4a-8` | `:1577-1584` |
| S4b | 4 | `A-S4b-1` … `A-S4b-4` | `:1668-1671` |
| **C** | **2** | `C-1` / `C-2` | `:1814-1815` |
| **合计** | **27** | | |

⚠️ **若按「C 组升门禁之前」的口径数是 25 项**（27 − `C-1` / `C-2`）—— `C-1`/`C-2` 自 2026-09-30（`5544362`）起才由 record-only **升为门禁**。**现口径 27。**

⚠️ **另有三类不计入上表**（都不是 `Ass.gate`，别合并计数）：

- **条件门禁 1 项**：`A-B2-1`（`:2220`，`gate = --b-group-gate`，默认 `false`）—— 也是全脚本**唯一**被 `EXPECT` 覆盖的断言（`:459` 静态块仅一条：`A-B2-1` → `EXPECTED_DEFECT_PRESENT`）；
- **动态异常门禁最多 7 项**：`guard(...)`（`:1359`）在负载段抛异常时补一条 `gate = true` 的 `*-异常`，调用点 7 个（`:1313` / `:1316` / `:1319` / `:1322` / `:1325` / `:1328` / `:1331`）；
- **前置校验 P0–P7 共 8 项**（`:2415` 起，写进 `Map<String,String> pre`）—— 失败走**退出码 2**，与门禁（退出码 1）是**两套机制**。其中 **P4** 的判据就是「种子行数 = `6/5/5/5/4`」（同 §验收标准）。

⚠️ **本节「门禁」指脚本退出码，不等于「CI 拦 merge」**：`concurrency` job 目前是 `continue-on-error: true`（`.github/workflows/ci.yml:117`，**观测跑**，门禁地位同 M2 / M3 / M4 未裁）。§验收标准 里「改坏时间线 CI 会红」是同一口径。

### H.3 不在覆盖范围内的端点 —— **只有单测**

`api-contract.md §2` 共 **33** 个路由，A3 打到 **9** 个（§H.1）⇒ 其余 **24** 个 **A3 一个都没打**：

| 族 | 未覆盖 | 是哪些 |
|---|---|---|
| `/user` | 3 / 5 | `getUserInfo`、`updateAvatar`、`updateUserInfo` |
| `/message` | 9 / 10 | `conversations`、`history`、`send`、`getOrCreateConversation`、`userSelect`、`selectFriend`、`friendUp`、`friendDe`、`friendSelect` |
| `/goods` | 8 / 9 | `goodsAdd`、`goodsSelect`、`goodsSelectHome`、`goodsDe`、`productCodePrice`、`goodsUp`、`GoodsProductCodeSelect`、`productSearch` |
| `/order` | 4 / 8 | `orderSelect`、`orderDeliveryIfSelect`、`orderDeliverySelect`、`historicalOrdersSelect` |
| `/health` | 0 / 1 | — |

⇒ **这 24 个的护栏只有单测**（全项目 `@Test` **136** 处 / **19** 个测试类，2026-10-02 实测，见 `tech-debt.md` M4），而**单测不跑并发路径** ⇒ 三层改动它们时，A3 **不会替你报警**。

⚠️ **「断言口径盖不到」≠「端点没被调到」** —— 两个已知实例是 **TD-15**（`loginAdd` 无 else）与 **TD-17**（三个状态位可直传出终态）：`tech-debt.md:224` 逐字把它们归因于「**都是 A3 的断言口径盖不到的地方**」。⚠️ 别把两者混为一谈 —— `loginAdd` 本身**就在** A3 的 B-2 段里被调（§H.1），漏的是**断言**，不是调用。

## 最容易漏的两条

1. **D#1** —— `@Transactional` 禁令 **2026-09-30 已登记**（`### D.1`，本笔）。⚠️ 但**登记 ≠ 记住**：它仍是新层最容易踩的一条。且它与 **C#2** 是同一约束的两面（一边**必须加**、一边**禁止加**），只记住半边就会踩另一边。⚠️ **C#2 那半边已于 2026-10-04 落地**（`fix/goods-write-tx`）—— 落地的形状印证了这句话：真正的难点不在「加不加」，而在**加了之后 catch 会不会把回滚吞掉**。
2. **E 档「C 组」** —— ⚠️ **此条已过期**：2026-09-30 `5544362` 起 C-1/C-2 是门禁，改了时间线 CI **会红**。仍留在「最容易漏」里，是因为它描述的陷阱换了形状、并没消失 —— **从旧文档读到「不门禁」的人，会以为时间线没有护栏**。

## 待验 —— 开工前不该当成已知

1. `goodstype.GoodTypeNew` 显式 NULL 打 `NOT NULL DEFAULT '0'` 的实际行为
2. 上传路径穿越的**可利用性** —— 代码侧确证**无 basename 提取**，但取决于 Tomcat 是否在 `getSubmittedFileName()` 剥离路径段。**探针结果出来前只能写「无校验」，不能写「可穿越」**
3. 本机无 8.4 目标库 ⇒ C 组端到端跑不了（P1 版本门禁会拦下）

## 开工顺序建议

1. ✅ **两条「新层须知」已于 2026-09-30 定（本笔）**：**D#4** = **`JdbcClient` 短期 + jOOQ 长期**（`### D.4`）+ **D#1** = `@Transactional` 禁令**已登记**（`### D.1`） —— 原文「不定就没法落笔」的**前置已满足**，可落笔
2. **裁决先行**：~~**C#2**（事务边界）~~ ✅ **2026-10-04 已裁并落地**（`fix/goods-write-tx`）、**C#6**（TD-17 拦截 vs 观测）—— ⚠️ **本条原写「两条都阻塞落笔」，现只剩 C#6 一条**
3. **拆层顺序**：Controller → **DAO** → Service —— 先把数据出口收成一处，Service 才有意义
4. **三层内顺带做**：校验（B#1 / #3 / #4 / #5、C#6）、全局异常出口（A#5 / D#2）、拦截器与 CORS（D#3）、~~事务边界（C#2）~~ ✅ **已于 2026-10-04 做**（但**未走三层**：四域三层早已完成，本笔记在公共层笔）、A#2 / A#3 / D#5 / D#6
5. **并行**：A′#6–8、A#1（L2 TTL）、D#7 / D#8
6. **三层后**：身份层（鉴权）；C#4 / C#5（C3 时间线）

---

## 全项目进度表

> 进度**不在本文维护** —— 本文 `:4` 自述「只做提取与归属，不含新事实」，全项目进度属跨文档状态，放这里会与各 pack 打架。
> **权威进度表**：`docs/agent/progress.md`（2026-09-30 新建；命名约定见其 **§0**）。
> 与本文相关的两笔（三层前准备）**均已收尾（2026-09-30）**：**笔 1 前端走查**（结果分流入 `api-contract.md` + `defect-survey.md`）/ **笔 2 文档补节** ——
> 笔 2 的 5 项**全部落定**：`D#1`（**`### D.1`**）、`D#4`（**`### D.4`**）、C3 数据源清单（`selection-status.md §6`）、A3 覆盖矩阵（**§H**）、`§G` 现状复校（**G 表已存在**，#11 / #12）；
> 另有两条**阻塞落笔**的裁决 `C#2` / `C#6` —— ⚠️ **本条已过期一半**：`C#2` 于 **2026-10-04 已裁并落地**（`fix/goods-write-tx`），**只剩 `C#6` 仍开着**，见 `progress.md §5-B`。

