# 项目进度表 — Mall-management

> **定位**：全项目**唯一**的进度真相源。只记「做到哪了」，不记「怎么做」（怎么做见各 context-pack）。
> **不重复**：缺陷明细见 `three-layers.md §G`、未决议题见 `business-decisions.md`、技术债见 `tech-debt.md`。本文只给**状态 + 证据**。
> **维护规则**：状态变化时改本文，**不**在各 pack 里另立进度表 —— 2026-09-30 复核时已出现「同一件事两处各写一份、互相标过期」的情况。
> ⚠️ **2026-10-02 追记（两笔）**：本文的类名与行号原是 **2026-09-30 截面**。此后：
> **笔 1**（`01df702`）按域挪包 —— 原扁平 `mallmanagement_*` 4 包 + `info` 孤儿类删除，改为 `goods`/`order`/`message`/`user`/`shared`。
> **笔 2**（PR #28）改名 + URL 规范化 —— `MallManagementGoodsController` / `MallManagementMessage` / `MallManagementUserController` → `GoodsController` / `MessageController` / `UserController`，订单 8 端点拆入 `OrderController`；`/mysqlGoods`、`/mysqlLogin` → `/goods`、`/order`、`/user`。⚠️ 笔 2 **不只是改名**：它同时把订单域拆成 `OrderController`/`OrderService`/`OrderDao`（**三层第一笔**在 PR #25 `28194b4` 已先落地，见 §3）。
> **三层第二笔**（2026-10-02，商品域）—— 8 条内联 SQL 下沉 `GoodsDao`/`GoodsService`；§3 与 §5-C 的 `G:` 坐标已就地改为现行 `GoodsService` 行号。
> ⚠️ 正文中**未就地改**的旧名 / 旧行号，**按当时状态读**；已知残余漂移见 §5-D 与 `defect-survey.md` 顶部追记。⚠️ **三层第三笔**（2026-10-02，用户域，**PR #32** `0c07e96`）—— 5 个端点的业务体与全部内联 SQL 下沉 `UserService`/`UserDao`；§3 用户域行与 §5-C 的 `U:` 坐标已就地改为现行 `UserService.*` / `UserDao.*` 行号。
> **三层第四笔**（2026-10-03，**message 域**，`8ed5fc1`）—— 10 个端点的业务体下沉 `MessageService`、14 处内联 SQL 收进 `MessageDao`（**合并了一对逐字相同的 SQL** ⇒ 13 方法 / 14 调用点）；`MessageController` 417 → **127** 行。⚠️ 本笔**未并入** doc 重指：文档坐标由**同日的笔 B**（文档笔）一并重写 —— 三分口径见该笔 commit 正文。⚠️ 本域**不是**「数据出口收成一处」：`FriendCacheService` / `UserCacheService` 的 6 条缓存读路径语句按 DAO 边界裁决**不进** `MessageDao`。
> **复核基准**：2026-09-30，`origin/main` = `39224e8`；PR #1–#20 **全部 MERGED**（**这是 2026-09-30 的截面**）。⚠️ **此后已前进**：PR #21（progress 落盘）、PR #22（走查分流）**均已合**，`origin/main` = `69ac718`（2026-09-30）。最新落盘状态以「更新记录」一节为准。
> **档位**：`亲读` = 本次逐行读 / grep / 命令实测；引他人文档处标**出处**（file:line），未亲验。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-03 实测**；重构后须重测。用户域旧 `U:<n>` 为三层前 264 行版 `UserController` 的历史坐标；消息域旧 `M:<n>` 为三层前 417 行版 `MessageController` 的历史坐标（2026-10-03 第四笔后仅保留在**明确标注为历史**的行）。

---

## 0. 命名约定（2026-09-30 定，防重名）

⚠️ 本项目历史上**同一个代号被两处用过**。从本表起统一口径：

| 代号 | 唯一含义 | 明确**不再**用于 |
|---|---|---|
| `A1 / A2 / A3` | 项目级 A 路径：计划 / 基建 / 并发脚本 | — |
| `B1 / B2 / B3` | **项目级 B 阶段**：Redis 两级缓存 / Redisson 分布式锁 / 订单状态机 + 异步时间线 | **三层前准备的笔**（曾用 `B1` 指前端走查） |
| `C1 / C2 / C3` | Micrometer 埋点 / SSE 实时日志 / Vue3 管理端 | — |
| `D#n` | `three-layers.md §D` 结构性缺陷编号 | — |
| `C#n` | `three-layers.md §C` 待裁决编号 | —（与 `C1/C2/C3` **不是同一套**） |
| **`笔 1` / `笔 2`** | 三层前准备的两笔（见 §2）—— **不带字母前缀** | — |

**已废弃的两个旧代号**（保留供回溯，别再使用）：

| 旧代号 | 曾经的意思 | 为什么废 |
|---|---|---|
| `A4` | upload-hardening（TD-06~09）—— 出处 `archive/worklog-2026-09-18.md:125`，**全仓唯一一处定义** | ⚠️ **部分完成 —— 不是已完成**：A 批（PR #8 `feature/pre-three-layers-hardening`：`efe7959` / `70eace2` / `743ab4c`）**只关掉 `TD-07`**；**`TD-06` / `TD-08` / `TD-09` 三条仍未关闭**（逐条状态见 §5-C）⇒ **既不可当作整体待做项，也不可当作已完成**。且 DAO 选型在仓库里的代号是 **`D#4`**，不是 `A4` |
| 三层前用 `B1/B2/B3` | 前端走查 / 数据源清单+缺陷归属 / A3 覆盖矩阵 | 与项目级 B 阶段**同名不同物**；且 `前端走查` / `覆盖矩阵` / `保护范围` 三词全仓 **0 命中**（`亲读` grep），从未入文档 |

⚠️ **`A4` 为什么是孤儿代号**（本段为 **`推断`** 档，低于上方四条证据）—— 旧 A 系列被**整体改挂**到 B/C 系列，只有 A2/A3 保留了 A 名：

| 旧代号（`archive/worklog-2026-09-18.md:123-127`） | 旧内容 | 后来的归宿 |
|---|---|---|
| A3 | 120 QPS 并发脚本 | **保留原名** → A3 / M2 |
| **A4** | upload-hardening（TD-06~09） | **无后继编号**，被拆进「A 批」，**只完成 1/4** ⇒ **唯一的孤儿代号** |
| A5 | add-cache / add-distributed-lock / state-machine | → **B1 / B2 / B3**（`dcf2d39` / `8810efe` / `b226357`） |
| A6 | 管理端 | → **C3**（Vue3 管理端） |

⇒ `A4` 既**没有后继编号**，又背着一个「只完成 1/4」的旧承诺 —— 是最容易被误当成待做项的一个。

⚠️ **一处问题两个编号**（引用时**挑一个**，别两处各写一半）：

| 问题 | 编号 A | 编号 B |
|---|---|---|
| 落库图片 URL 前缀硬编码 | **`TD-06`**（`tech-debt.md:81-90`）：以「生成落库 URL」口径 = `U:192` / `G:111` / `G:300` 三处，另 `U:55` 入参默认值 1 处 | **`three-layers.md §D#6`**（`:121`）：`GoodsService:120,283` / `UserService.updateAvatar:266` / `UserController.loginAdd:63`，且**多列**「未 URL 编码」（中文 / 空格文件名拼出非法 URL） |

两者症状同类、位置**有交集但不重合** ⇒ 引用时必须注明用的是哪个编号（本表 §5-C 用 `TD-06`）。

> ⚠️ **2026-10-02 三层第二笔后**：两栏的 `G:` 坐标口径**同时失准**，成因不同，**别当成同一件事**：
> ① 左栏 `G:111` / `G:300` 是 `MallManagementGoodsController`（三层前名）的**旧类旧行号**——两处 URL 拼接现已随 `goodsAdd`/`goodsUp` 下沉到 `GoodsService:120` / `GoodsService:283`（本表已就地改为右栏口径）。
> ② 右栏原写 `GoodsController:119,289`，那是**三层前的 Controller 内坐标**；本笔改动后 `GoodsController` 已不含这两行 ⇒ 已改为 `GoodsService:120,283`。
> 用户域两侧原写 `UserController:205` / `:59`（**三层第二笔时未漂**）；⚠️ **2026-10-02 三层第三笔（`0c07e96`）后已下沉** —— 现址 `UserService.updateAvatar:266` / `UserController.loginAdd:63`，本表右栏已就地改。⇒ 本表两栏现已**同一口径**（现行源码行号），差异只剩**范围**（左栏 4 处 / 右栏 4 处，左栏含 `U:55`、右栏含「未 URL 编码」一项）。

⚠️ **另有一处历史错位**（非本表造成，是计划与落地不同名）：
`archive/worklog-2026-09-20-a3.md:232-236` 的计划是 B2=乐观锁/CAS、B3=分布式锁、B4=状态机、B5=线程池；**实际落地**是 B2=Redisson 锁（`8810efe`）、B3=状态机+异步（`b226357`），B4/B5 被 B3 吸收。**引用 `B2/B3` 时必须注明用的是哪一版。**

⚠️ **进度表的源头不在仓库**：`archive/worklog-2026-09-20-a3.md:228` 引用的《A3 完成后，整个项目走到哪了》**全仓无此文件**。本文即为此后的**仓库内权威版**。

---

## 更新记录

| 日期 | 内容 | PR |
|---|---|---|
| 2026-09-29 | B1 缓存 / C1 埋点 / B2 锁 / B3 状态机 | #4 #5 #6 #7 |
| 2026-09-30 | A 批 P0 + P0 #2 + 缺陷归属 + G 表 + 规则 v2 + S1a-1 + S1a 文档 + progress 落盘 + 走查分流 | #8 #10–#22 |
| 2026-10-01+ | 三层前准备其余 → 三层开工 → … | — |
| 2026-10-02 | 订单域三层（#25 `28194b4`）→ 笔 1 DDD 挪包（#27 `01df702`）→ 笔 2 URL 规范化 + 订单拆 Controller + 类名对齐域（#28）→ **商品域三层（三层第二笔）** | #25 #27 #28 + 本笔 |
| 2026-10-03 | 三层第四笔 **message 域**（`8ed5fc1`）→ 文档坐标重指（笔 B `481e72b`）→ `goodsSelectHome` 坐标重指（`bc0126a`）→ **公共层笔 1 CORS 收口**（#34 `3f6ba4f`，A3 27/27）→ **前端双审计落盘（§6）** | #33 #34 + 本笔 |
| 2026-10-04 | #11 goodstype 唯一约束（fix/goodstype-unique）：DB UNIQUE + catch 语义修正 + 文档 ×5 + 单测 ×3 | #40 |

## 1. 项目级阶段

| 阶段 | 内容 | 状态 | 证据 |
|---|---|---|---|
| **A 阶段** | A1 计划 / A2 基建 / A3 并发脚本 | ✅ | M1（`tech-debt.md:235`，2026-09-18）、M2（`:254`，2026-09-20）；PR #2 #3 |
| **B 阶段** | B1 两级缓存 / B2 Redisson 锁 / B3 状态机 + 异步时间线 | ✅ ⚠️ **挂 4 条，见 §5-A** | M3（`tech-debt.md:272`，2026-09-29，PR #4/#6）、M4（`:291`，2026-09-30，`b226357`） |
| **C1** | Micrometer + Actuator 埋点 | ✅ | 出处 `selection-status.md:18`：21 个 `mall.*` 指标（11 cache + 4 lock + 6 order.state）；`aab3b9d` / PR #5 `3f8c4b7` |
| **C2** | SSE 实时日志 | ⏳ **未开始** | `亲读`：全仓 grep `SseEmitter` / `text/event-stream` / `WebFlux` = **0 命中**。计划出处 `archive/worklog-2026-09-20-a3.md:238` |
| **C3** | Vue3 管理端 | ⏳ **未开始** | `亲读`：前端 **14 个** view（`Mall-management-web/mall-management-system/src/views/`，2026-10-02 复测 `ls` 计数；原记 16，与 `project-map.md:68` 的 14 不一致，已按实测订正）无管理端入口。计划出处 `:239` |

> ⚠️ 「B 阶段 ✅」**不是干净 ✅**：计划内的 B1/B2/B3 三笔本身已交付，但 **§5-A 挂了 4 条**（状态机选型待裁 + 三项选型 ❌ 未归属）。写 ✅ 不代表无挂账。

### 1.1 已完成的收尾批（供回溯）

| 批 | 内容 | 证据 |
|---|---|---|
| **规则 v2** | `agent.md` 硬性约束 3 + 数据库管理规则 2/4 升级（回滚 SQL = 逻辑废弃，非物理删除） | PR #13 `f19a14e` |
| **G 表** | `three-layers.md §G. 缺陷修复归属`（8 行 × 4 列） | PR #11 `79de3cb`；#12 `47c1ded` 修过上传文件名行 |
| **A 批** | 三层前 P0 加固（TTL + 上传三项 + C 组升门禁） | PR #8 `feature/pre-three-layers-hardening` |
| **S1a 收尾 7 笔** | #14–#20（口径见下） | 全部 MERGED |

**S1a 收尾 7 笔的口径**（⚠️ 两点易数错）：
- **7 = #14–#20**。其中 **#14（`1e2fcfa`）是唯一的代码落地**（`CREATE UNIQUE INDEX uk_order_orderId` + `A-S2a-1` 拆 1a/1b/1c）；#15–#19 是文档同步与收口；#20 = TD-16 收口。⇒ 这笔系列叫「S1a」已名不副实（#19/#20 与索引无关）。
- **第 7 笔只存在于分支名**：`feature/s1a-6`，而 commit message 是 `chore(a3): TD-16 …` —— **按 message 回溯只会数出 6 笔**。

---

## 2. 三层前准备（**2026-09-30 已收尾**）

| 笔 | 内容 | 状态 |
|---|---|---|
| **笔 1** | **前端走查** —— 静态审计（fetch/axios 调用 vs 后端路由表）+ 接口层 curl 走查 | ✅ **已完成** —— 走查报告**未落盘**（按裁决分流入 `api-contract.md` + `defect-survey.md`），随 **PR #22** `69ac718` 合入 |
| **笔 2** | **文档补节** —— 见下表 | ✅ **已完成**（**PR #22** `69ac718` + **PR #23** `b72bc80`） |

**笔 2 应覆盖的 5 项**（前两项依 `three-layers.md:272-279` §开工顺序建议 —— 仓库明写它们**阻塞落笔**）—— **5 项已全部落定（2026-09-30）**：

| # | 项 | 出处 | 状态 | 为什么必须先做 |
|---|---|---|---|---|
| 1 | **`D#1` 登记 `@Transactional` 禁令** | `three-layers.md:115` | ✅ **已完成**（**PR #23**；落点 **`### D.1`**） | `orderAddLocked` 是全项目**唯一「禁止用标准注解」处**（登记后不再「无处登记」）；`:263` 仍把它列为最易漏 |
| 2 | **`D#4` DAO 选型结论**（JdbcTemplate vs JPA） | `three-layers.md:118`、`:274` | ✅ **已完成**（**PR #23**；落点 **`### D.4`** —— 结论 **`JdbcClient` 短期 + jOOQ 长期**） | 「**不定就没法落笔**」。另 `ddl-auto=update` + 零 `@Entity`（TD-01）—— 一旦有人加 `@Entity` 即反向 `ALTER TABLE` |
| 3 | **C3 数据源清单** | — | ✅ **已完成**（**PR #23**；落点 **`selection-status.md §6`**） | C2/C3 均未开工；清单是 C3 的输入 |
| 4 | **A3 覆盖矩阵**（明示保护范围） | `concurrency-map.md`（13 行，**无「保护范围」列**，`亲读`） | ✅ **已完成**（**PR #23**；落点 **`three-layers.md §H`** —— 8 业务端点 + 27 门禁，未覆盖 24 个标「靠单测」） | 现表只列「有什么问题」，不列「A3 保护了什么」 |
| 5 | **`§G` 现状复校**（**不是新建**） | `three-layers.md:169-185` | ✅ **已存在**（**早于本批**：#11 落地、#12 修过一行；右列现状是 2026-09-30 复核结果） | ⚠️ 若要的只是复校，**不要重写** |

> ⚠️ 本块另有 **2 条「阻塞落笔」的裁决**，性质是**裁决不是笔** ⇒ 列在 **§5-B**。

---

## 3. 三层重构（按域分批）

批次顺序：**订单域 → 商品域 → 消息域 → 用户域 → 公共层**

进度（2026-10-03）：**订单域 ✅**（三层第一笔，PR #25 `28194b4`）→ **商品域 ✅**（三层第二笔，2026-10-02）→ **用户域 ✅**（三层第三笔，2026-10-02，PR #32 `0c07e96`）→ **消息域 ✅**（三层第四笔，2026-10-03，`8ed5fc1`）→ **公共层 🟡 部分推进**（**2026-10-04 订正**，原写「⏳ 未动」）：**已合 3 笔** —— #34 CORS 收口（`D#3` 的 CORS 面）/ #35 `Result<T>` 统一响应体 / #36 全局异常出口 `GlobalExceptionHandler` + 写接口校验（`D#2`）；**未做**：`C#2` 事务边界 / `C#6` 状态位拦截 / `D#5` 配置收敛。

⚠️ **实际顺序与上行的计划顺序不同**：计划把**消息域排在用户域之前**，实际是**用户域先做**（2026-10-02）、消息域后做（2026-10-03）—— 两条路都不违反任何裁决，但引「批次顺序」时**以本行为准**，不要拿上行的计划顺序当执行史。

⚠️ **分批要按「方法」切，不能按「文件」切** —— ~~订单接口不在独立文件里：`orderAddLocked` 在 `GoodsController:442`（javadoc `:433-441`；`three-layers.md:115`）、`orderAdd` 同在 `MallManagementGoodsController`（`:418`，路由注解 `:417`）。~~
**2026-10-02 笔 2 已消解该形式**：订单 8 端点拆出 `order/controller/OrderController.java`（118 行，`@RequestMapping("/order")`），方法体进 `order/service/OrderService.java`（313 行）。现行坐标——`orderAdd` 路由 `OrderController:42-43`（方法签名 `:43`）→ 委托 `OrderService.orderAdd:99` → 打锁 `OrderService:110` 内 lambda 调 `addLocked`（私有，`OrderService:124`）；`@Transactional` 禁令的 javadoc 在 `OrderService:25-45`。
⚠️ 但**「按方法切」的教训本身仍成立**，不是历史遗留：`addLocked`（`:124`）与 `casTransition`（`:212`）**同处一个临界区、必须同批**，两者之间还夹着 `OrderStateMachine` / `OrderTransitionRecorder` 的调用 —— 只按文件切仍会切错。`DistributedLockServiceTest.orderChainIsNotTransactional` 反射扫的**不再是两个 bean**：2026-10-03 第四笔把它扩到**两条链 5 个 bean** —— 订单链 `OrderService` + `OrderDao` + `OrderTransitionRecorder`，消息链 `MessageService` + `MessageDao`（方法名 `orderChainIsNotTransactional` 因文档锚点而保留，见该测试的 DisplayName）。⚠️ 本笔的代价：`MessageService` 是**整类扫描**，故非临界区的 `friendUp` 也一并被扫 —— 这是**有意的**覆盖面，不是误伤。

| 域 | 主要所在 | 行数（`亲读` `wc -l`） | 备注 |
|---|---|---|---|
| **订单域** | `order/controller/OrderController`（118 行，`/order` 8 端点）+ `order/service/OrderService`（313）+ `order/dao/OrderDao`（155）+ `order/model`、`order/metrics` | 586（三文件合计） | ✅ **已完成** —— **PR #25** `28194b4`「订单域三层重构」（早于笔 1/笔 2，故为**三层第一笔**）；状态机部分更早由 B3 拆出 |
| **商品域** | `goods/controller/GoodsController`（176 行）+ `goods/service/GoodsService`（332）+ `goods/dao/GoodsDao`（208） | 716（三文件合计；原 `MallManagementGoodsController` 676） | ✅ **已完成**（2026-10-02 三层第二笔）—— SQL 硬编码 schema 名：`GoodsDao` **6** 处 +（读路径，非本笔搬动）`GoodsCacheService` 5 / `GoodsTypeCacheService` 3；见 `three-layers.md:166` F#1 与其中的**已知矛盾**段 |
| **消息域** | `message/controller/MessageController`（**127** 行，仅路由；原名 `MallManagementMessage`）+ `message/service/MessageService`（534）+ `message/dao/MessageDao`（284）；另有 `message/service/FriendCacheService`（268，缓存读路径，不属本笔） | 945（三文件合计；原 `MessageController` **417**） | ✅ **已完成**（2026-10-03 三层第四笔，`8ed5fc1`）—— SQL 硬编码 schema 名 **0** 处（原 Controller 0 处，现 `MessageDao` 仍 0 处）：**本域合了一对逐字相同 SQL 也没动 39 口径**，理由见 `MessageDao` 类 javadoc。`@Transactional` 禁令（不可加在 `friendAddLocked`）现由 `DistributedLockServiceTest` 的结构断言覆盖 `MessageService` + `MessageDao` 两个 bean；见 `three-layers.md:166` F#1 |
| **用户域** | `user/controller/UserController`（**107** 行，仅路由；原名 `MallManagementUserController`）+ `user/service/UserService`（319）+ `user/dao/UserDao`（242） | 668（三文件合计；原 `UserController` **264**） | ✅ **已完成**（2026-10-02 三层第三笔，**PR #32** `0c07e96`）—— SQL 硬编码 schema 名 **16** 处全部下沉 `UserDao`；`loginAdd` 的 `@Transactional` 现挂 `UserService.loginAdd:124`；见 `three-layers.md:166` F#1 |
| **公共层** | `shared/web`（`GlobalExceptionHandler` / `Result<T>`）、`shared`（CORS 收口） | — | 🟡 **部分推进（2026-10-04 订正）**：`D#2` 全局异常出口 ✅（#36 `573463c` + `1bd05d3`）、`D#3` 的 CORS 面 ✅（#34 `23f5361`，删 5 处冗余 `@CrossOrigin`）+ 统一响应体 `Result<T>` ✅（#35 `ea2bf02` + `321773e`，28 端点）；**未做**：`C#2` 事务边界 / `C#6` 状态位拦截 / `D#5` 配置收敛 |

> **TODO（2026-10-04，drift-fix 笔登记）**：公共层三笔（#34–#36）目前**无 context-pack** —— `docs/agent/context-packs/common-layer.md` **待建**（**本笔不建**）。建议登记到 `selection-status.md §8` 类 4（新功能引出）或**另起一笔**。
> 另 3 处漂移待另开一笔：`progress.md:141-143` Controller 行数快照、`defect-survey.md:104/113/115/119/259/270` 表内旧坐标、追记内三处映射。
>
> **TODO（2026-10-04，#11 `fix/goodstype-unique` 笔登记）**：本笔发现但**不修**的 4 点（方案 Q10，只登记）——
> ① `A-B2-1` 的 signal 是「**或**」（`login5xx > 0 ‖ gtRows ≥ 2 ‖ frRows ≥ 2`）⇒ **单点修好它一动不动**（`login5xx` 每轮 5–9 撑着），拿它验收单点是**结构性做不到**。更好的形态是**三站点各一条独立断言**（`A-B2-lg` / `A-B2-gt` / `A-B2-fr`），聚合条退成摘要。⚠️ 动 A3 判据属另一笔。
> ② `A-B2-1` 的 desc 写死「B-2 三处 TOCTOU（loginAdd / goodstype / friendAdd）」—— goodstype 修好后这句**名不副实**，须重述或降级。
> ③ `AbstractSmokeTest:153` 注入了 `GoodsTypeCacheService` 却**零写用例** ⇒ 加索引后 smoke 层没有任何一处能发现 catch 文案写错（本笔的 `GoodsTypeUniqueIndexTest` 走的是「有真库」那条路，补不了 smoke 层）。
> ④ B-2 场景的 goodstype 断言**只数「重复行数」，不数「响应文案分布」** ⇒ 修完后「19 条『类型已存在』/ 1 条『新增成功』」这个最直接的证据在报告里一个字都看不到（`b.gtOk` 只记成功数）。
> **落点**：这 4 条**明写落在本挂账段**（M4 允许的三个落点里选它）—— **不落 `selection-status.md §8 类 4`**，理由是 §8 的编号空间（`#1..#33`）与 PR 号（`#34` CORS / `#35 Result<T>` / `#36 GlobalExceptionHandler`）**同形**，再给 §8 增 4 行会造出「§8 `#34`」与「PR `#34`」的混淆。
> **下一笔已定名**：**「`defect-survey` 收口笔」**（起手时间未定）—— 内容 = `defect-survey.md` 的**前提清单** + **病 G 行** + **§七 Class A 第 9 条标完成**。⚠️ 本笔按方案 Q8 把 `defect-survey.md` **压掉**（不并入 #11），故必须有此落点，**不许让它变孤儿**。该笔与上一段 `common-layer.md` 待建**是两笔**，不合并。

**拆层顺序**（`three-layers.md:276`）：Controller → **DAO** → Service ——「先把数据出口收成一处，Service 才有意义」。

**基线**（`亲读`，**2026-09-30 截面**）：后端 **28 个 `.java`**（controller 4 / service 13 / server 10 / class 1）；DB 访问散在 **12 个文件**；`@Repository` = **0 个文件**、`@Mapper` = **0 个文件**。

> ⚠️ 上一行是**截面值**，此后未逐笔同步。**2026-10-03 复测（含三层第四笔后）**（同「主源 `.java`」口径，`亲读` find + grep）：**37 个**（`goods` 5 / `order` 10 / `message` **4** / `user` **4** / `shared` 13 / 顶层启动类 1）；`@Repository` **4 个文件**（`OrderDao` / `GoodsDao` / `UserDao` / **`MessageDao`**）、`@Service` **14 个文件**（+**`MessageService`**）、`@RestController` **5 个文件**（四业务域 + `HealthController`）；`@Mapper` 仍 **0**（全项目零 MyBatis）。
> ⚠️ **2026-10-02 的旧值**（本行上一版）：**35 个**（`message` **2** = `MessageController` + `FriendCacheService`）；`@Repository` **3**；`@Service` **13**。第四笔的 `+2` 文件 = `MessageDao` + `MessageService`。
> 「DB 访问散在 12 个文件」一项**未重测** —— 它与「含 `mall_management_sql.` 前缀的文件数」（现 **7**，见 `three-layers.md:166` F#1）**不是同一口径**（前者含无前缀的读路径），引用时以 F#1 为准。

---

## 4. 后续

| 项 | 状态 | 入口 |
|---|---|---|
| **鉴权** | ⏳ 未开始（三层后单独做） | `business-decisions.md:42-52` **BD-03**，状态「**待确认**」；方案 A 拦截器+Redis Session / B JWT+拦截器 / C Spring Security+JWT；**建议 A 或 B** |
| **C2 SSE** | ⏳ 未开始 | 见 §1 |
| **C3 管理端** | ⏳ 未开始 | 见 §1；**依赖鉴权**（BD-03「建议时机 T2：业务功能完成后、管理端之前」） |
| **部署** | ⏳ 未落地 | 无 Dockerfile（`defect-survey.md:95`）；`TD-09` jar 场景待验证 |

---

## 5. 挂账（未决 + 未归属）—— 排期时先看这里

### 5-A. B 阶段遗留（`亲读` `selection-status.md:19-22`）

| 项 | 状态 | 出处 |
|---|---|---|
| 状态机 | ⚠️ **未引库，自研了等价物** —— **需裁决** | `:19`（自研 `b226357`；裁决见其 `§5`） |
| 限流熔断（Resilience4j） | ❌ 未实现，**未归属** | `:20` |
| 日志（Logback JSON） | ❌ 未实现，**未归属** | `:21` |
| 死锁重试（Spring Retry） | ❌ 未实现，**未归属**；2026-09-30 已裁「**仍不需引入**」 | `:22` + `selection-status.md:73` 裁决注 |

### 5-B. 阻塞三层落笔的两条裁决（`three-layers.md:275`）

| 项 | 内容 | 出处 |
|---|---|---|
| **`C#2` 事务边界** | §C Class B —— 原文标注「**阻塞落笔**」 | `:99-108`、`:189` |
| **`C#6` TD-17 拦截 vs 观测** | ⚠️ **拦截会使 A3 的 S4a 全部 8 条断言失去前置**（H3 正是用 `orderAdd` 造的前置态） | **`:108`**（§C 档定义）；复述于 `:153`（§G 表） |

### 5-C. 其他未决（不在三层路径上）

- `BD-03` 鉴权方案 A/B/C **待裁** —— 见 §4
- `TD-16` 主体：`--reset-db` **行为未实现**（2026-09-30 已在 `--help` 与 `tech-debt.md` 标「未实现」，**行为没动**）—— `tech-debt.md §TD-16`
- **上传 / 部署一组**（`tech-debt.md` 状态行**逐条亲读**，行号为实测）：

  | 编号 | 内容 | 状态 | 行 |
  |---|---|---|---|
  | `TD-05` | 静态资源路径依赖 `user.dir`，jar 部署时会失效 | 读侧已修复、写侧开发期已验证（`1421a0c`、`c5d74b4`）；**jar 场景待验证** | `:79` |
  | `TD-06` | 图片 URL 前缀硬编码为 `localhost:8088` | ⚠️ **待确认**（**未关闭**） | `:90` |
  | `TD-07` | 文件上传失败被静默吞掉 | ✅ **已修复**（A 批 `efe7959`）；残留：异常日志仍为 `printStackTrace`（归病 D） | `:101` |
  | `TD-08` | 开发期上传目录受 Git 跟踪 | ⚠️ **实测发生，待处理方案**（**未关闭**） | `:111` |
  | `TD-09` | 静态资源修复的 jar 部署场景待验证 | ⚠️ **待确认**（**未关闭**）—— 依赖 `TD-05` | `:122` |

  ⚠️ **`TD-06` / `TD-08` / `TD-09` 三条均与「部署」直接相关**（换机 / 反代即失效、jar 场景未验、上传目录入库）⇒ 排「部署」时必须一起看，见 §4。
- **开工前不该当成已知的三项待验**（`three-layers.md:266-270`）：① `goodstype.GoodTypeNew` 显式 NULL 的实际行为；② 上传路径穿越的**可利用性**（代码侧确证无 basename 提取，但取决于 Tomcat 是否剥离路径段 —— **探针出来前只能写「无校验」，不能写「可穿越」**）；③ 本机无 8.4 目标库 ⇒ C 组端到端跑不了

### 5-D. 复核时发现的文档漂移（不是进度，但会误导）

- ~~`docs/agent/README.md` 的「文档职责」表**未登记** `defect-survey.md` 与 `selection-status.md`~~ —— ✅ **本笔已补**（`README.md` 两行）。依据：`three-layers.md:3` 明写「上游是 `defect-survey.md`（缺陷普查）+ `selection-status.md`（选型现状）+ `db-schema.md`」，其中前两份此前不在文档地图里。

---

## 6. 前端规范化（2026-10-03 双审计）

> **口径**：`亲读` —— `Mall-management-web/mall-management-system/src` 全量 grep + 逐行读（只读，前端代码**一字未动**）。
> **上游**：为公共层**笔 2**（统一响应体 `Result<T>`）/ **笔 3**（异常 + 校验）做的**依赖面体检**；起因 = `api-contract.md:9`「无统一返回体、无全局异常处理」。
> **四项对账（沿用 `api-contract.md:135` 同口径，须自洽）**：`fetch(` **42** 处 ／ 不同路径 **31** 条 ／ 路由 **33** 条 ／ 差集 33−31 = 2（`getOrCreateConversation`、`/health/db` 无调用方）。
> ⚠️ **51 ≠ 42**：**51** 是 `http://localhost:8088` 的**出现行数**。其中 **39** 行含字面量 `fetch(`，另 **12** 行不含（6 条 URL 赋值行 + 3 处图片 `src` + 3 处 CSS `url()`）；再另有 **3** 个 fetch 用变量 URL（行内无字面量）⇒ `fetch(` 调用数 **42** = 39 + 3。（逐条见 §6-B 的 P2.1）

### 6-A. 依赖面（决定公共层笔能不能动）

| 项 | 分布 | 归属 | 验收判据 | 依赖 |
|---|---|---|---|---|
| **P1.2 `getUserInfo` 空体** | 消费点 3：`LoginPage.vue:150`、`UserInfoPage.vue:101`、`GoodsUp.vue:155` —— 均 `await res.json()` **无判空**，靠外层 `catch` 兜 `SyntaxError`（后端失败 = HTTP 200 **空体**，`api-contract.md:14`） | **A** | ⚠️ **全审计最脆一条**：笔 2/3 **不得**把该响应包成**可解析 JSON** —— 一旦 `json()` 成功且无 `merchantid`/`customerid`，`LoginPage:154/157` 静默不写 sessionStorage、**不抛不报**。判据写死：失败路径必须继续**抛异常**落 `catch`；笔 3 异常白名单**必须排除** `UserController.getUserInfo` | 笔 2 + 笔 3 |
| **P1.3 message 10 端点形态** | **10/10 全 `Map`**（无 String/null）；在用 9 个（`getOrCreateConversation` 无调用方） | **A** | 顶层 `success`/`msg`/`list`/`user` **一个都不能挪进 `data.*`**（`ChatRoom.vue:170` 直接 `return data.user`） | 笔 2 |
| **P1.4 `goodstype` 双形态** | GET 无参→读 `list`（`GoodsAdd.vue:135`）；POST 带参→**不读响应**（`GoodsAdd.vue:166` fire-and-forget）；`HomePage.vue:154` 用 **POST 无参**→读 `list` | **A** | 两条路径都读**顶层** `list`；POST 无参分支须继续返 `{success,list}`。**已在笔 2 例外清单**（签名不动） | 笔 2 |
| **P2.4 `success`/`msg` 读点** | `.success` **34 处**真实判定（另 3 处是 CSS 类 `.success-cover`/`.success-text`，非判定）；`.msg` **17 处** | **A** | 笔 2 的「JSON 字节等价」**由这 51 处定义** —— 顶层 `success`(bool)/`msg`(string) 键名与语义逐字不变 | 笔 2 |
| **P1.1 无 `res.ok` 检查** | `res.ok`/`res.status`/`.statusText` 全 src **0 命中** | **B** | 前端**从不看状态码**，只读 body 的 `success` ⇒ 笔 3 若引入 4xx/5xx，前端**静默无感**（不报错、也不提示） | 独立笔 |
| **P1.5 OPTIONS 预检** | `ImgConfig.CorsFilter`：`addAllowedMethod("*")`（Spring `checkHttpMethod` 对 `"*"` 直放行 ⇒ **含 OPTIONS**）、`addAllowedHeader("*")`（⇒放行 `Access-Control-Request-Headers`）、`addAllowedOriginPattern("*")` + `allowCredentials(true)`、`maxAge=3600` | **C** | **已覆盖，无需改**；且前端多为 `application/x-www-form-urlencoded`（simple request）⇒ 预检基本不触发 | — |

> **P1.3 逐条**（10 端点 → 前端读的字段）：`conversations`→`success`+`list`（`ChatRoom:184`/`CustomerPage:97`/`MerchantPage:113`）；`history`→`success`(+`list`)（`ChatRoom:213`/`:279`）；`send`→`success`+`msg`（`ChatRoom:327`/`ProductPage:459`）；`userSelect`→`success`+`user`（`ChatRoom:164`/`FriendRoom:132`）；`friendAdd`→`success`+`msg`（`FriendRoom:159`）；`selectFriend`→`success`(+`list`)（`FriendRoom:194`）；`friendUp`/`friendDe`→`success`+`msg`（`FriendRoom:231` 变量 URL）；`friendSelect`→`success`(+`list`)（`FriendRoom:107`）；`getOrCreateConversation`→**无调用方**。

### 6-B. 前端债（归一笔独立的「前端 API 层规范化」）

| 项 | 分布 | 归属 | 验收判据 | 依赖 |
|---|---|---|---|---|
| **P2.1 硬编码 URL** | `http://localhost:8088` **51 处**（14 文件）= **42** fetch 调用（39 字面 + 3 变量：`GoodsSelect.vue:81`/`OrderCenter.vue:129`/`FriendRoom.vue:231`）+ **6** 条 URL 赋值行（`GoodsSelect:74/78`、`OrderCenter:125/127`、`FriendRoom:226/228`）+ **3** 图片 `src`（`FriendRoom:71`/`GoodsUp:9`/`UserInfoPage:7`）+ **3** CSS `url()`（`LoginPage:9/10/274`）；分布：ProductPage/FriendRoom 各 **7** 最多，CustomerPage/HistoricalOrders/MerchantPage 各 **1** | **B** | 收敛到 base URL 常量 + `.env`；与 `TD-06`（`tech-debt.md:90`，图片 URL 前缀硬编码）**同源** | 独立笔 |
| **P2.2 无 API 层** | `src/` 仅 `App.vue`/`components`/`main.js`/`router`/`views`；无 `api/`/`services/`/`utils`；`axios`/`request(` **0 命中**；全部原生 `fetch(` | **B** | **P2.1/P2.3/P2.5/P2.7 的根因** —— 建层后其余四项收敛到一处 | 独立笔（**最先**） |
| **P2.3 catch 错误点** | `try` **40** / `catch` **41**，覆盖**全部 42** 处 fetch（`ProductPage.vue` 2 个 try 各含 2 fetch）；全为 `console.error`/`alert`，无统一出口 | **B** | 收敛到 API 层统一 catch；`alert` 文案不属接口契约，可改 | 依赖 P2.2 |
| **P2.5 环境变量** | `.env` / `VUE_APP_*` / `process.env` 全 src **0 命中**；`vue.config.js` **无 `proxy`** | **B** | 与 P2.1 同笔：base URL 走 `VUE_APP_*` | 依赖 P2.2 |
| **P2.6 sessionStorage 直接用** | **71 处** API 调用 / **14 文件**（MerchantPage **9** 最多）；key：`merchantid`/`customerid`/`loginUsername`/**`loginPassword`（明文）**/`SEARCH_KEYWORD`/`SEARCH_RESULTS`/`PENDING_SEARCH_KEYWORD`/`FROM_PAGE` | **B** | ⚠️ **含独立安全债**：`loginPassword` **明文入 sessionStorage**（写：`LoginPage.vue:164/169`；读：`GoodsUp.vue:146`、`UserInfoPage.vue:92`），且被当凭据回传 `getUserInfo`/`updateUserInfo` —— 规范化笔须**单列**这条 | 独立笔（可并行） |
| **P2.7 无超时/重试** | `AbortController`/`signal`/`timeout`/`retry` 全 src **0 命中** | **B** | API 层加超时 + 有限重试 | 依赖 P2.2 |

### 6-C. 归类汇总

- **A 类（公共层笔强制处理）——4 项**：P1.2、P1.3、P1.4、P2.4。
- **B 类（独立笔「前端 API 层规范化」）——7 项**：P2.1、P2.2、P2.3、P2.5、P2.6、P2.7、P1.1。
- **C 类（不规范但无 bug）——不列**：P1.5（OPTIONS 预检已覆盖）等。

> **本节用法**：A 类 4 项是**公共层笔 2/3 的硬约束** —— 笔 2 落地前先跑「旧 `Map` vs 新 `Result<T>` 响应体**逐字（取字节）比对**」，判据即 6-A 的 P1.2/P1.3/P1.4/P2.4；B 类 7 项归一笔**独立**的「前端 API 层规范化」，**不在公共层三笔内**（边界：公共层三笔不动前端）。
