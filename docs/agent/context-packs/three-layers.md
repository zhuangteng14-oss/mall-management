# context-pack: three-layers

> **定位**：三层重构**开工时读**。上游是 `defect-survey.md`（缺陷普查）+ `selection-status.md`（选型现状）+ `db-schema.md`（schema 视图）。
> 本文**只做提取与归属**，不含新事实 —— 每条都能追到那三份文档之一。
> 定稿：2026-09-30（`origin/main` = `e930b30`）。
> **修订：2026-09-30（`origin/main` = `3917cd3`）** —— C 组已升门禁（`5544362`）、P0 #1～#4 已修、新增 **G. 缺陷修复归属**。

## 任务

把 **Controller / Service / DAO** 三层拆出来，并把普查发现的缺陷按「属于哪一层」归位。

## 三层范围定义（2026-09-30 定）

| 层 | 内容 |
|---|---|
| **Controller** | 只做入参绑定 / 分支 / 文案 |
| **Service** | 业务规则、事务边界、**校验** |
| **DAO** | 唯一数据访问出口；消灭 39 处硬编码 schema 名 |

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

- `MallManagementGoodsController.java`（最大；注意 `orderAddLocked` 的 `@Transactional` 禁令注释，见 D#1）
- `MallManagementUserController.java`、`MallManagementMessage.java`
- `mall_management_sql.sql`（权威 schema，**CRLF 陷阱**）

## 约束

1. 只允许新增表 / 新增字段 / 新增索引；禁止删列、改类型、删表（`agent.md` 全局硬约束 3）。
2. 所有 DB 变更登记在 `db-migration.md`（含回滚；注意回滚与规则 4 的固有矛盾，见该文件注意 1 / 6）。
3. **向后兼容**：不得删旧接口、旧字段。
4. ⚠️ **`orderAddLocked` 不得加 `@Transactional`** —— 见 D#1。**这条不可外推到其他方法**；反面是 C#2（`goodsAdd` **应当**加）。

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
| 2 | **失败被当值缓存（B+E 合成链）** | `GoodsCacheService:186-198` + `:211-219` + 前提 B/E | collation 大小写不敏感 + 三元组无唯一约束 ⇒ 多行 ⇒ `queryForMap` 抛 ⇒ 被当值缓存 ⇒ `priceByTriple` 退化成「把失败缓存满整个 TTL」 | ✅ **已修**（`88a14e4`，PR #10）—— 原归属「三层内（DAO / Service）」。⚠️ **只关掉「失败被固化到 TTL」这一环**：第 1/2 环（collation + 三元组无唯一约束）仍在，**失败本身仍存在** | 小 |
| 3 | **同名文件静默跳过，DB 记别人的图** | `GoodsController:111-113,119`、`UserController:197-199,205` | 商家 B 传同名 `1.jpg` → 文件不落盘、DB 指向**商家 A 的图**、返回 `success:true` | ✅ **已修**（`efe7959`，A 批，同名策略 C）—— 原归属「三层内（Controller / Service）」，与 B#5 同一件事 | 小 |
| 4 | **上传目录不可写 ⇒ 整个应用起不来** | `ImgPath.java:23-27`（`@PostConstruct`） | 非核心功能拖死服务，无降级 | ✅ **已修**（`70eace2`，A 批，启动降级）—— 原归属「三层内（Service，启动期）」 | 小 |
| 5 | **`getUserInfo` null ⇒ HTTP 200 + 0 字节** | 全仓 `ResponseBodyAdvice` / `@ControllerAdvice` = 0 | 写路径整段在 `if (body != null)` 内 ⇒ 前端拿「成功」但无内容 | **三层内（全局异常出口）** | 小 |

## A′. 与三层并行 —— 安全 / 环境

| # | 项 | 位置 | 说明 | 归属 | 工作量 |
|---|---|---|---|---|---|
| 6 | 口令明文进 stdout + 随 CI artifact 扩散 | `MallManagementUserController.java:39`、`ci.yml:315` | 泄露成立，但不改变运行结果 | 并行（环境层）；**git 历史清理另计** | 小 / 中～大 |
| 7 | 生产 = 默认 profile | 只有 `default` 与 `a3` | 它把 #1、#8 串起来；修它本身就是环境层 | 并行（环境层） | 中 |
| 8 | root:123456 与 Redis 口令明文 | `application.properties:6-7`、`application-a3.properties:31` | 同 #7 | 并行（环境层） | 小～中 |

## B. Class A —— 语义无歧义，可直接做

| # | 项 | 位置 | 归属 | 工作量 |
|---|---|---|---|---|
| 1 | **服务端零价格校验**（只做 `Price >= 0` + 上界） | `GoodsController:390` → `:435-449` | **三层内（Service 校验）**。⚠️ 按 `goods` 表**重算**属 **C 档** —— 重算会改行为（客户端值被覆盖），是「换真值源」不是「加校验」 | 小 |
| 2 | `goodsUp` 的 WHERE 补 `GoodName` | `GoodsController:281-290` 一带 | 三层内（DAO / Service） | 小 |
| 3 | `goodstype.GoodTypeNew` 不许显式传 NULL | `mall_management_sql.sql:76` | 三层内（Service 校验）。_行为本身待验，见「待验」_ | 小 |
| 4 | `login.Id` 唯一性（前提 C） | 无约束，客户端直传 `userid` | 三层内（Service）；DB 侧加 UNIQUE 属 schema 变更 | 小 / 中 |
| 5 | 三处上传提取 basename + 类型白名单 | `GoodsController:106-113,282-287`、`UserController:193-199` | 三层内（Service）。⚠️ **路径穿越仍待验**，不得写成「可穿越」 | 小 |
| 6 | `l2Put` 冷却路径补指标 | `TwoLevelCache.java:220-233` | 并行（观测） | 小 |
| 7 | `L2HealthProbe.down` 改 CAS | `L2HealthProbe.java:154-177` | 并行（观测） | 小 |
| 8 | 文档锚点 → 符号名 | `defect-survey.md §五` | 独立（文档）。**TD-17 已于 2026-09-30 改完**；TD-04/06/07 待改 | 小～中 |

## C. Class B —— 语义未定，须先裁决

| # | 项 | 卡住什么 | 归属 | 工作量 |
|---|---|---|---|---|
| 1 | `goodsUp` 改名粒度（一条 vs 批量） | — | 裁决先行 → 三层内实施 | 小 |
| 2 | **`goodsAdd` / `goods` / `productsales` 事务边界** | **DAO / 事务层**：「半成品商品」的修法是给 `goodsAdd` 加 `@Transactional`。⚠️ 这是 **D#1 的反面** —— `orderAdd` 那条**不得加**只适用于它自己，**不要外推** | **三层内（DAO / 事务层）** | 中 |
| 3 | `productsales` 硬删 vs 软删 | — | 裁决先行 → 三层内 | 小 |
| 4 | 时间线动作集是否需 cancel/refund | B3 时间线扩展 / C3 视图 | 三层后（C3） | 小 / 中 |
| 5 | `BD-01` `SettlementDate` 归谁 | `db-schema.md §4` 方案 A/B/C | 裁决先行 → 三层后（C3） | 小 / 中 |
| 6 | **TD-17 伪造态：拦截 vs 观测** | **校验层** —— 拦截会使 A3 的 S4a **全部 8 条断言失去前置** | **三层内（校验层）** | 小 / 中 |

## D. 结构性缺陷 —— 三层重构时会撞上的

| # | 项 | 位置 | 为何会撞 | 归属 | 工作量 |
|---|---|---|---|---|---|
| 1 | **`orderAddLocked` 不得加 `@Transactional`** | javadoc `GoodsController:405` | 锁在 `runLocked` 的 `finally` 先释放、事务提交在方法返回**之后** ⇒ 后进者的 `COUNT(*)` 在 RR 下读自己事务开始时的快照、**看不到前一个未提交的行** ⇒ 仍插 2 行。全项目**唯一「禁止用标准注解」处，且无处登记** | **三层前（新层须知）** | 小（登记）/ 中（设计规避） |
| 2 | 无全局异常处理 / 无拦截器 | `@ControllerAdvice` / `@ExceptionHandler` / `HandlerInterceptor` = **0** | 新层抛出的异常无统一出口 | **三层内（三层产出）** | 中 |
| 3 | 两套 CORS 机制并存 | `ImgConfig` 全局 `CorsFilter` + 4 处裸 `@CrossOrigin`（`MallManagementGoodsController:27` / `MallManagementUserController:20` / `MallManagementMessage:18` / `HealthController:37`） | CorsFilter 先于 DispatcherServlet ⇒ handler 级**静默失效** | **三层内（拦截器层）** | 小 |
| 4 | `ddl-auto=update` + 零 `@Entity` | `application.properties:12`、`pom.xml:51`（TD-01） | 一旦有人加 `@Entity` 即反向 `ALTER TABLE`；`show-sql=true` 同刻全量打 SQL | **三层前（定 DAO 走 JdbcTemplate 还是 JPA）** | 小 |
| 5 | `app.upload-dir` 默认值三处重复 | `ImgPath:18` / `GoodsController:55` / `UserController:30` | 改一处必漏两处（同 TD-06 形态） | 三层内（配置收敛） | 小 |
| 6 | 落库 URL 前缀硬编码且未 URL 编码 | `GoodsController:119,289`、`UserController:205`、另 `UserController:59` | 换机 / 反代全体图片失效；中文 / 空格文件名拼非法 URL（仓内既有 `色款.jpg`） | 三层内（Service / 配置） | 小 |
| 7 | 无 logger（`printStackTrace` × 20 + `println` × 5）、无日志配置、`open-in-view` 默认 true | 病 D | 观测层欠账 | 并行（观测） | 小～中 |
| 8 | 无迁移工具 / 无 Dockerfile | 全仓 0 命中 | 属环境层 | 并行（环境层） | 中 |

## E. 三层前置 —— 开工前要知道的事实

| 项 | 状态 | 说明 |
|---|---|---|
| `db-schema.md §6–§10` 逐列 / 索引 / 外键 / 类型基线 | ✅ **已完成**（12 表全量） | 三层可直接引用 |
| 七前提 A–G | ✅ **已列**（`defect-survey.md §二`） | 是**三层的输入**不是产物 —— 设计新层时逐条问「谁保证它」 |
| 全库除主键外**零 UNIQUE** | ✅ 已确证（§10） | 三层必须承认：唯一性是应用层约定，不是约束 |
| `order_state_transition.OrderId → order.OrderId` 的 FK | ⚠️ **建不起来** | 阻塞点只是「父列无索引」**这一条**，与「不保证唯一」是两件事（§8 有实测） |
| 种子行数 = P4 判据基线 `6/5/5/5/4` | ⚠️ 门禁输入 | 改种子数据会**先让 P4 变红**（§6.0） |
| A3 C 组（时间线核对）**曾是 record-only**，2026-09-30 起升为**门禁**（`5544362`） | ✅ **门禁**（C-1/C-2） | 改坏时间线 **CI 会红**。⚠️ 本行原写「不门禁 / 静默通过」—— 那是**定稿时**（`e930b30`）的结论，**已过期** |
| `mall_management_sql.sql` **CRLF + 1 个裸 CR** | ⚠️ 陷阱 | 用会归一化行尾的编辑器改它，diff 变成整份重写 |

## F. 三层中做 —— 三层本身的产出，不是前置

| # | 项 | 位置 | 为什么不能提前做 |
|---|---|---|---|
| 1 | **SQL 硬编码 schema 名 39 处** | `GoodsController` 23 + `UserController` 16；`MallManagementMessage` **0** ⇒ 同进程脑裂（TD-13） | 抽 DAO 层时**自然**改完。列为三层前会让人以为要独立一笔重改 39 处 —— **比三层本身还大**。它是「为什么需要 DAO 层」的证据，不是它的前置 |

---

## G. 缺陷修复归属（2026-09-30 定）

> **本节只做一件事**：给「还不知道谁来修」的缺陷指定**认领阶段**。`defect-survey.md` 病 0 说「四层都缺」，
> 那四层**不等于**本文的 A～F 档 —— 本节是它们的映射表。
> ⚠️ **「认领」≠「已做」**：右两列分开写，**现状一栏全部是 2026-09-30 复核的结果**，不是计划。
> ⚠️ 已修的 P0 #1～#4 **不在此表**（见上方 **A. P0 级**，归属栏已改标 ✅ 与落地 commit）。

| 缺陷 | 出处 | 认领阶段 | 现状（2026-09-30 复核） |
|---|---|---|---|
| **服务端零价格校验**：`orderAdd` 的 `Price` 由客户端直传直落库 | 病 B；本文 **B#1** | **三层期间（Service 校验）** —— 只做 `Price >= 0` + 上界；⚠️ 按 `goods` 表**重算**属 **C 档**（那是换真值源，不是加校验） | ⏳ **未做** —— `MallManagementGoodsController.orderAdd` 仍是 `@RequestParam("Price") float`，方法体内无重算、无比对 |
| **状态位拦截**（TD-17：三个状态位可直传出终态订单） | 病 B；**TD-17**；本文 **C#6** | **三层期间（校验层）** —— 前置是**裁决**「拦截 vs 观测」 | ⏳ **未做**（C#6 两方案未裁）。⚠️ 拦截会使 A3 的 S4a **全部 8 条断言失去前置**（H3 正是用 `orderAdd` 造的前置态） |
| **长度边界（字符数量）**：`productsales.GoodName char(20)` vs `goods.GoodName char(40)` | 病 G（`SQL:169` vs `:57`） | **三层期间（Service 校验，长度上界）** —— ⚠️ schema 侧**不改类型**（本文「约束 1」只允许新增表 / 字段 / 索引），只能在 Service 拦 | ⏳ **未做**。21–40 字符名写 `productsales` 报 **Error 1406**（**实测**，用户可见失败）；⚠️ `.sql` **导入会话**非严格 ⇒ 导入期**静默截断** |
| **搜索 SQL**：`productSearch` 逐字 `OR GoodName LIKE` | 病 C（`:72`）；`api-contract.md:72` | **三层期间（DAO）** —— 参数化与 schema 前缀在抽 DAO 时自然改完 | ⏳ **未做** —— `keyword.trim().split("")` 每字符一个 OR；单引号即 SQL 语法错误；`SELECT *` 无 schema 前缀、无 `LIMIT` |
| **身份伪造**：登录是**业务接口级判断**，不是框架鉴权 | 病 A；**BD-03** | **鉴权阶段**（**三层后单独做**，见「三层范围定义」） | ⏳ **未做** —— 32 个接口零鉴权、无拦截器、无 Spring Security |
| **文件白名单**：三处上传零类型校验（可传 `.html` / `.svg`） | 病 B；本文 **B#5** | **三层内（Service 校验层）；C3 复用** | ⏳ **未做** —— 本文 **B#5** 的另一半（basename 提取 / 同名策略）已由 `efe7959` 修 |
| **分页** | `api-contract.md:11`「无分页参数，列表接口全量返回」；病 C（`productSearch` / `goodsDe` 均无 `LIMIT`） | **C3 顺带** | ⏳ **未做** |
| **订单幂等**：`orderAdd` 非幂等（`OrderId` 由调用方提供、且无唯一约束） | `api-contract.md:69`；**TD-14** | **C3 后** | ⏳ **未做** —— DB 侧加索引属 schema 变更（本文「约束 1」允许**新增索引**） |

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

## 最容易漏的两条

1. **D#1** —— `@Transactional` 禁令**无处登记**，新层必然踩；且它与 **C#2** 是同一约束的两面（一边**必须加**、一边**禁止加**），只记住半边就会踩另一边。
2. **E 档「C 组」** —— ⚠️ **此条已过期**：2026-09-30 `5544362` 起 C-1/C-2 是门禁，改了时间线 CI **会红**。仍留在「最容易漏」里，是因为它描述的陷阱换了形状、并没消失 —— **从旧文档读到「不门禁」的人，会以为时间线没有护栏**。

## 待验 —— 开工前不该当成已知

1. `goodstype.GoodTypeNew` 显式 NULL 打 `NOT NULL DEFAULT '0'` 的实际行为
2. 上传路径穿越的**可利用性** —— 代码侧确证**无 basename 提取**，但取决于 Tomcat 是否在 `getSubmittedFileName()` 剥离路径段。**探针结果出来前只能写「无校验」，不能写「可穿越」**
3. 本机无 8.4 目标库 ⇒ C 组端到端跑不了（P1 版本门禁会拦下）

## 开工顺序建议

1. **两条「新层须知」先定**：**D#4**（DAO 走 JdbcTemplate 还是 JPA）+ **D#1**（登记 `@Transactional` 禁令）—— 不定就没法落笔
2. **裁决先行**：**C#2**（事务边界）、**C#6**（TD-17 拦截 vs 观测）—— 两条都阻塞落笔
3. **拆层顺序**：Controller → **DAO** → Service —— 先把数据出口收成一处，Service 才有意义
4. **三层内顺带做**：校验（B#1 / #3 / #4 / #5、C#6）、全局异常出口（A#5 / D#2）、拦截器与 CORS（D#3）、事务边界（C#2）、A#2 / A#3 / D#5 / D#6
5. **并行**：A′#6–8、A#1（L2 TTL）、D#7 / D#8
6. **三层后**：身份层（鉴权）；C#4 / C#5（C3 时间线）
