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
| 2026-10-04 | `C#2` 商品域写事务边界（`fix/goods-write-tx`）：`GoodsService` 三处写方法加 `@Transactional` + catch 内带活跃事务判断的 `setRollbackOnly`（方案 A）+ 单测 ×6（含突变对照）+ 文档 ×6 + 主源码 javadoc ×2（`GoodsDao` / `UserService` 里被本笔改成假话的「未裁」与「真注解计数」） | #42 |
| 2026-10-04 | `B#1` 价格校验（`fix/price-validation`）：`OrderService.orderAdd` **锁之前**加 `priceRejection`（正面判据 `Float.isFinite && Price >= 0 && Price <= 10^7`，非法 ⇒ 200 + `{success:false,msg:"价格不合法"}`、**不取锁不触库**）+ 单测 ×8（`OrderAddPriceGuardSmokeTest`，**无 `A3_ENABLED` 门控 ⇒ 在阻塞 job 真跑**；含 3 条阳性对照与 4 组突变对照）+ 文档 ×4（`three-layers.md` 3 行 / `selection-status.md` `#3` / `defect-survey.md` / `api-contract.md` 契约变更）+ 本节 §5-F | 本笔 |
| 2026-10-04 | `C#6` 状态位拦截（`fix/order-state-guard`）：`OrderService.orderAdd` **锁之前**加**出生闸**（白名单 `{(0,0,0),(1,0,0)}`，**排在 `B#1` 价格闸之后**；非法 ⇒ 200 + `{success:false,msg:"订单状态不合法"}`、**不取锁不触库**）+ 指标 `mall.order.state.birth.rejected{shape}`（**5 值封闭枚举**：`010`/`001`/`011`/`non_binary`/`non_initial`）+ 单测 ×10（`OrderAddStateGuardSmokeTest`，**无 `A3_ENABLED` 门控 ⇒ 在阻塞 job 真跑**；含 3 组突变对照 7/3/7）+ **A3 适配**（S4a 前置态改走合法流转 + 新增门禁 `A-S4a-0`/`A-C6-1`/`A-C6-2`，`gates.count` 27 → 30）+ 文档 ×6 + 本节 §5-G | #45 |
| 2026-10-04 | **前端 API 层规范化**（`fix/frontend-api-layer`，8 笔 = 5 搬家 + 3 改行为）：14 个 view 的 **42** 处裸 `fetch`（41 POST + 1 GET）、**51** 行硬编码基址（39 行 fetch 字面量 + 6 行 URL 赋值 + 3 处头像 URL + 3 行 CSS `url()`）、**54** 处登录态 `sessionStorage`（4 键：`merchantid` 24 / `customerid` 12 / `loginUsername` 12 / `loginPassword` 6）收敛到 `src/api/` 三层（`http` / `endpoints` / `auth`）+ 2 行 `.env`。**落地点与残留**：§6-B 的 `P2.1`（`LoginPage` 3 行 CSS 挂账保留）/ `P2.2` / `P2.3`（改 22 处纯日志 `catch`，另 19 处业务分支不动）/ `P2.5`，加 §6-A 的 `P1.1`；⚠️ `P2.7` **只落超时**（默认 10s / 上传 30s），**重试按裁决不做**；**`P2.6` 未落**（余 17 处页面状态键 `SEARCH_*` / `FROM_PAGE` 本笔一字未动）—— 已拆出单列一笔。3 笔改行为 = 超时 / 非 2xx 抛 `HttpError` 并对用户可见 / 3 处头像死链（`default-avatar.jpg` → `default_user.png`）。证据：保真 42/42（路径 + 方法 + body + Content-Type 四维）、5 门禁含反向对照（51→3 / 42→0 / 0→42 / 54→0 / 14→1）、8 笔逐笔 `lint=0 build=0`、32 个 `(方法, 路径)` 组合对后端 0 处不存在。**不关 `TD-06`**（根因在后端 4 处字面量） | #48 |
| 2026-10-04 | **记录收尾 + 自查纠错**（`docs/progress-48-record`）：更新记录补 `#48` 行（行内带 **§6-B/§6-A 的落地点与残留**）→ 订正 `.env.production` 注释的 51 分解为 **39 fetch 字面量 + 6 URL 赋值行 + 3 处头像 URL + 3 CSS `url()` = 51**（纯注释、值零变更）→ 新增 **§5-I** 登记 #48 的 **2 处已知错误**（51 分解、`sessionStorage` 52 vs **54**）；**#48 的 PR body 同步订正**（GitHub 直改，2 处 + 回读校验）。⚠️ `8118741` 的提交信息**未修**（不改写已合并历史）⇒ 该 message 两个错数**永久留痕**，引用时按 §5-I 的 **39 / 54** 读 | #49 |
| 2026-10-04 | **记录补号 + `54` 订正 + 鉴权调研登记**（`docs/progress-49-record`，3 提交 `c97bcc5` / `2cf0e64` / `651a375`）：① 更新记录补 `#49` 行；② **订正 `54` 的逐键拆分**（`progress.md` §1 的 `#48` 行 + §5-I (2) + `src/api/auth.js:3-8` **三处**；原写 `merchantid 23 / loginUsername 13 / customerid 12 / loginPassword 6` **对不上任何一套口径** ⇒ 统一为**逐出现** `merchantid 24 / customerid 12 / loginUsername 12 / loginPassword 6`），并**实测坐实**另有 `52`（**行×键**去重）与 `51`（涉及凭据的**行数**）两套口径 —— `52` 的病是**被与 `17` 相加去对 `71`**（混用）；③ 新增 **§5-J**：登记鉴权笔（`BD-03`）开工前调研 **3 条发现**（`AbstractSmokeTest:45` 计数 5→**8** / `UserService:98` 明文账号密码进 stdout / `login.Password char(20)` 装不下 bcrypt(60)）+ **1 条裁定**（`P2.6` **独立一笔**，时序 鉴权笔 → `P2.6` 笔）。⚠️ `8118741` 的提交信息**未修**（不改写已合并历史） | #50 |
| 2026-10-04 | **鉴权笔 1/3 —— 拦截器 + 白名单 + 401 出口**（`feat/auth-gate`）：`shared/web/AuthInterceptor`（开关关 → 放行／白名单 → 放行／会话有 `userId` → 放行／否则 **401 + `{"success":false,"msg":"未登录"}`** 且**不进 Controller**）+ `shared/web/WebAuthConfig`（注册在 `/**`，**故意不用 `excludePathPatterns`** ⇒ 白名单唯一出处是 `AUTH_WHITELIST`）+ 开关 `app.auth.enabled` **默认 `false`**（⚠️ **承重**：写死 `true` 当场红 **29** 条）+ 白名单 **12 条**（业务 8 + 基础设施 4）+ 单测 10 条；文档同步 **4 文件 6 处**（组 A）；新挂 **§5-O** 3 条（组 B，本笔之前就过时）。⚠️ **只做「拦」，不做「认」** —— 认证侧留笔 2 | #53 |

> ⚠️ **表格欠账的现状**：`C#2` 那一行原记 `本笔`，已由 **PR #42 合并后的 `docs/progress-42-record` 一笔补成 `#42`** —— 与 **#11 的做法相同**（#11 的记录也是合并后另起一笔 `37cafc3` 补的 `#40`）。当初为何不直接填：那一笔的文档与代码在**同一笔**落盘（先出 diff → 用户核 → commit → 开 PR），commit 时 PR 号尚不存在。仍欠的**只剩三处**：**2026-10-02 / 2026-10-03 两行**的 `+ 本笔`，以及本表**新增的 `2026-10-04 B#1` 行**（同理：该笔的文档与代码同笔落盘，commit 时 PR 号尚不存在；补法与 #42 同 —— 合并后另起一笔）。

## 1. 项目级阶段

| 阶段 | 内容 | 状态 | 证据 |
|---|---|---|---|
| **A 阶段** | A1 计划 / A2 基建 / A3 并发脚本 | ✅ | M1（`tech-debt.md:235`，2026-09-18）、M2（`:254`，2026-09-20）；PR #2 #3 |
| **B 阶段** | B1 两级缓存 / B2 Redisson 锁 / B3 状态机 + 异步时间线 | ✅ ⚠️ **挂 4 条，见 §5-A** | M3（`tech-debt.md:272`，2026-09-29，PR #4/#6）、M4（`:291`，2026-09-30，`b226357`） |
| **C1** | Micrometer + Actuator 埋点 | ✅ | 出处 `selection-status.md:18`：21 个 `mall.*` 指标（11 cache + 4 lock + 6 order.state）；`aab3b9d` / PR #5 `3f8c4b7` |
| **C2** | SSE 实时日志 | ⏳ **未开始** | `亲读`：全仓 grep `SseEmitter` / `text/event-stream` / `WebFlux` = **0 命中**。计划出处 `archive/worklog-2026-09-20-a3.md:238` |
| **C3** | Vue3 管理端 | ⏳ **未开始** | `亲读`：前端 **14 个** view（`Mall-management-web/mall-management-system/src/views/`，2026-10-02 复测 `ls` 计数；原记 16，与 `project-map.md:68` 的 14 不一致，已按实测订正）无管理端入口。计划出处 `:239` |
| **三层重构** | 四域（订单 / 商品 / 用户 / 消息）Controller → Service → DAO + 公共层（CORS / `Result<T>` / 全局异常 + 写接口校验） | 🟡 **四域 4/4 ✅；公共层部分推进，实剩 `D#5` 配置收敛** | M5（`tech-debt.md:328`，2026-10-04，**PR #25–#46**） |

> ⚠️ 「B 阶段 ✅」**不是干净 ✅**：计划内的 B1/B2/B3 三笔本身已交付，但 **§5-A 挂了 4 条**（状态机选型待裁 + 三项选型 ❌ 未归属）。写 ✅ 不代表无挂账。
> ⚠️ 同上，「三层重构 🟡」**不写「完成」**：四域 4/4 已交付，但**公共层仍缺 `D#5` 配置收敛**（§3 同口径）。本条为 §1 表**最后一行**、按执行时序**晚于**上方全部 —— 插在 B 阶段与 C1 之间会读成「早于 C1」，故置末。

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
| 2 | **`D#4` DAO 选型结论**（JdbcTemplate vs JPA） | `three-layers.md:118`、`:274` | ✅ **已完成**（**PR #23**；落点 **`### D.4`** —— 结论 **`JdbcClient` 短期 + jOOQ 长期**） | 「**不定就没法落笔**」。另 `ddl-auto` + 零 `@Entity`（TD-01）—— ⚠️ 原文写「一旦有人加 `@Entity` 即反向 `ALTER TABLE`」；**该半句已于 2026-10-04 关闭**（TD-01 `B` 方案：`ddl-auto` 改 `validate`），另半句「JPA 依赖保留不删」**仍成立** |
| 3 | **C3 数据源清单** | — | ✅ **已完成**（**PR #23**；落点 **`selection-status.md §6`**） | C2/C3 均未开工；清单是 C3 的输入 |
| 4 | **A3 覆盖矩阵**（明示保护范围） | `concurrency-map.md`（13 行，**无「保护范围」列**，`亲读`） | ✅ **已完成**（**PR #23**；落点 **`three-layers.md §H`** —— 8 业务端点 + 27 门禁，未覆盖 24 个标「靠单测」） | 现表只列「有什么问题」，不列「A3 保护了什么」 |
| 5 | **`§G` 现状复校**（**不是新建**） | `three-layers.md:169-185` | ✅ **已存在**（**早于本批**：#11 落地、#12 修过一行；右列现状是 2026-09-30 复核结果） | ⚠️ 若要的只是复校，**不要重写** |

> ⚠️ 本块另有 **2 条「阻塞落笔」的裁决**，性质是**裁决不是笔** ⇒ 列在 **§5-B**。**2026-10-04 起实剩 1 条**：`C#2` 已裁并落地（`fix/goods-write-tx`），`C#6` 仍开着。

---

## 3. 三层重构（按域分批）

批次顺序：**订单域 → 商品域 → 消息域 → 用户域 → 公共层**

进度（2026-10-04 订正）：**订单域 ✅**（三层第一笔，PR #25 `28194b4`）→ **商品域 ✅**（三层第二笔，2026-10-02）→ **用户域 ✅**（三层第三笔，2026-10-02，PR #32 `0c07e96`）→ **消息域 ✅**（三层第四笔，2026-10-03，`8ed5fc1`）→ **公共层 🟡 部分推进**：**已合 3 笔** —— #34 CORS 收口（`D#3` 的 CORS 面）/ #35 `Result<T>` 统一响应体 / #36 全局异常出口 `GlobalExceptionHandler` + 写接口校验（`D#2`）；另 **`C#6` 状态位拦截已合**（**PR #45** `473b6c1`，见 §5-G）；**未做**：~~`C#2` 事务边界~~（**2026-10-04 落地**，`fix/goods-write-tx`，见 §5-B）/ ~~`C#6` 状态位拦截~~（**2026-10-04 落地并已合并**，**PR #45**）/ **`D#5` 配置收敛（实剩 1 项）**。⚠️ **本次订正修掉两处漂移**：原写「已合 3 笔 + 本笔（未合）」（#36 于 2026-10-03 已合）与「`C#6` 未做」（#45 于 2026-10-04 已合，`2e9d3f8`）。

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
| **公共层** | `shared/web`（`GlobalExceptionHandler` / `Result<T>`）、`shared`（CORS 收口） | — | 🟡 **部分推进，实剩 `D#5`（2026-10-04 订正）**：`D#2` 全局异常出口 ✅（#36 `573463c` + `1bd05d3`）、`D#3` 的 CORS 面 ✅（#34 `23f5361`，删 5 处冗余 `@CrossOrigin`）+ 统一响应体 `Result<T>` ✅（#35 `ea2bf02` + `321773e`，28 端点）+ **`C#6` 状态位拦截 ✅（PR #45 `473b6c1`，见 §5-G）**；**未做**：~~`C#2` 事务边界~~（**2026-10-04 落地**，`fix/goods-write-tx`；`GoodsService` 三处写方法各加 `@Transactional` + catch 内带活跃事务判断的 `setRollbackOnly`，单测 ×6）/ ~~`C#6` 状态位拦截~~（**2026-10-04 落地并已合并**，**PR #45**，`2e9d3f8`）/ **`D#5` 配置收敛** |

> **TODO（2026-10-04，drift-fix 笔登记）**：公共层三笔（#34–#36）目前**无 context-pack** —— `docs/agent/context-packs/common-layer.md` **待建**（**本笔不建**）。建议登记到 `selection-status.md §8` 类 4（新功能引出）或**另起一笔**。
> 另 3 处漂移待另开一笔：§3 的**域表行数快照**、`defect-survey.md:104/113/115/119/259/270` 表内旧坐标、追记内三处映射。
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

### 5-B. 阻塞三层落笔的两条裁决（`three-layers.md:276`）—— ✅ **两条均已落地（2026-10-04 同日）**

| 项 | 内容 | 出处 | 状态 |
|---|---|---|---|
| **`C#2` 事务边界** | §C Class B —— 原文标注「**阻塞落笔**」（**标题里的「两条」自此过期一半**，见下方 `C#6` 行） | `:99-108`、`:189` | ✅ **2026-10-04 已裁并落地**（`fix/goods-write-tx`）：三处**一起闭环**（`goodsAdd` / `goodsDe` / `goodsUp`），方案 A（`@Transactional` + catch 内 `setRollbackOnly`）。⚠️ **只加 `@Transactional` 不够**：三处 catch 都**吞异常、不 rethrow** ⇒ 不标回滚的话 AOP 看到的是正常返回、**照常提交**，留下「半成品商品」——那正是本项要消掉的东西，也是「加了注解就绿」的假绿路径 |
| **`C#6` TD-17 拦截 vs 观测** | ⚠️ **原记「拦截会使 A3 的 S4a 全部 8 条断言失去前置」已实测订正** —— S4a 的 5 个前置态**全是合法态** ⇒ 只 **3 条**（`A-S4a-3`/`-5`/`-6`）的前置**构造方式**变、**断言语义 0 条变**；**真正被打断的是 C 组**（`C_BITS` 八组合要求全部出生）。详见 **§5-G** | **`:112`**（§C 档定义；⚠️ 原记 `:108` 指着的是上一行 `C#2`）；复述于 **`:182`**（§G 表；⚠️ 原记 `:153` 已漂） | ✅ **2026-10-04 已裁并落地**（`fix/order-state-guard`）：裁决取**拦截**，出生闸白名单 `{(0,0,0),(1,0,0)}`（**白名单必含 `(1,0,0)`** —— 生产前端 `ProductPage.vue:354` 直传 `PayIf=1`），闸在 `runLocked` **前**、**价格闸后**。登记见 **§5-G** |

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

### 5-E. `C#2`（`fix/goods-write-tx`，2026-10-04）登记：**护栏失效 1 条（最高优先级）** + 遗留 1 条 + 未改的漂移 3 处

> 标题口径（2026-10-04 `B#1` 笔改）：原写「**本笔**」，与 §5-F 并排时两处都叫「本笔」⇒ 歧义。现按**笔号**命名，与 §5-F 的 `B#1` 对称；**本节下文自称「本笔」处，均指 `C#2`**。

**（1）挂账 —— 按优先级列；第一条不是漂移，是「护栏失效」（别让它沉到底）**：

- ⚠️ **最高优先级｜本笔与 `#11` 的单测在 CI 里**从不执行** —— 不是「没被选中」，是**被 `A3_ENABLED` 门控跳过**。性质：护栏失效，不是文档漂移**。**两条独立原因叠加，产物实证**（`surefire-reports` 产物，已拉本地核对）：
  1. `backend` job 跑**全量** `./mvnw -B test`（`ci.yml:51-55`；**该 job 是阻塞门禁**）但**不设** `A3_ENABLED` ⇒ 受 `@EnabledIfEnvironmentVariable` 门控的类**全部 `Skipped`**：产物里 `...GoodsWriteTxAtomicityTest.txt` 逐字为 `Tests run: 6, Failures: 0, Errors: 0, Skipped: 6`（`GoodsTypeUniqueIndexTest` 为 `Skipped: 3`，`MallManagementApplicationTests` 为 `Skipped: 6`）。
  2. 唯一设 `A3_ENABLED=true` 的步骤（`ci.yml:257-263`）又把类名**写死**为 `MallManagementApplicationTests,LockSceneRedisTest` ⇒ 本笔的 6 条与 `#11` 的 `GoodsTypeUniqueIndexTest`(3) **都不在白名单内**。

  两条合起来 ⇒ 二者**在 CI 里一次都没真正执行过**。CI 自己的注释 `ci.yml:232-234` 已写明这一机制（「*backend job 不设该变量，那里的全量 mvn test 会自动跳过本测试*」），`tech-debt.md:135` 同 —— **本条先前误把机制归给「`ci.yml:263` 的 `-Dtest=` 列表从未更新」（`-Dtest` 只解释第 2 条，解释不了第 1 条），2026-10-04 拉产物后订正**。它使「CI 绿」对本笔的原子性改动**零信息量**（验收边界见下方（5））。⚠️ 另注：**`continue-on-error: true`（`ci.yml:117`）属 `A3` `concurrency` job，不属 `backend`** —— `backend` 是阻塞的，它只是**跑不到**这些用例；别把非阻塞算到它头上。**本笔裁决（2026-10-04）**：**不动 CI 判据**（混进本笔会让 CI 报告把「门禁列表变更」与「行为变更」混为一谈）⇒ **另起一笔**；该笔至少要就「把这两类测试挪进 `A3_ENABLED` 步骤的白名单，还是索性去掉门控」给出裁决。
- 📌 **方法论教训（2026-10-04 用户裁：写进本挂记，防下次）——「只记住半边」**：本条先前只拿到「`-Dtest` 白名单写死」这一半，就据此定下「机制是白名单」的结论；而另一半（`backend` job **不设** `A3_ENABLED` ⇒ 门控类被自动跳过）就在**同一份 `ci.yml` 里**、且被它自己的注释逐字写着（`ci.yml:232-234`），`tech-debt.md:135` 也写着。**两个可核的证据源都没去看** —— 不是找不到，是**先有了结论、就只找支持它的那半边**。**防下次的三条判据**：(a) 凡归因「机制是 X」，**至少再核一处独立证据源**（此处 = CI 产物 `surefire-reports`；本条的订正正是**拉产物**才发生的，光看源码不会暴露它）；(b) 涉及「A 导致 B」时，先问 **A 单独能不能解释 B** —— `-Dtest` 白名单**解释不了**「非门控类也照跑全量 158 条」这一事实，这一问当场就能发现缺口；(c) 同一个机制在仓库里**可能有两处写字**（此处：机器判据 = `A3_ENABLED`，人写判据 = `-Dtest` 白名单），别把它们当成同一个。
- **本笔明写「不改」的遗留**：`DistributedLockServiceTest.java:396-401` 的 javadoc 拿 `goodsAdd` 当「将来 C#2」的举例；**C#2 落地后该举例成历史**（对象已加事务，不再有「将来」）。⚠️ 本笔**不改它**（用户 2026-10-04 裁决）—— 改它即第 7 个文件，为一句注释撑破预算是错的。

**（2）本笔使其失真、但留而未改的 3 处（登记，别让它们成孤儿）**：

| 处 | 原文要点 | 为何失真 | 最小改法 |
|---|---|---|---|
| `OrderService.java:61` | 「它的反面是 C#2 —— `goodsAdd` … **应当**加 `@Transactional`」 | 「**应当**」已兑现（2026-10-04 落地） | 句尾加「（**2026-10-04 已落地**）」。⚠️ 与 `three-layers.md §D.1` 的「不可外推」行是**同一句话的两个镜像**，那处已标 ✅ ⇒ 只改文档不改代码注释，正是本条禁令自己警告的「只记住半边」 |
| `UserController.java:23` | 「**三层前** `loginAdd` 上挂着全仓**唯一一处真** `@Transactional`」 | 句首「三层前」把它**限定为史料**，作为史料仍真 | 可不动；若要防误读，在「唯一一处真」后加「（**当时**；2026-10-04 起为 4 处）」 |
| `defect-survey.md:259` | **Class B** 第 2 条「`goodsAdd` / `goods` / `productsales` 的事务边界 —— … 修法是给 `goodsAdd` 加 `@Transactional`」 | 该条**已被 2026-10-04 裁决并落地**，不再属于「Class B —— 语义未定，**须先裁决**」 | 标 ✅ 已落地（或移出 Class B）。⚠️ **该行的坐标已在「`defect-survey` 收口笔」的清单里**（§3 的 TODO 列了 `:104/113/115/119/259/270`）⇒ 归那一笔；**但该笔的范围只写「表内旧坐标」，覆盖不到「Class B 归属」** ⇒ 那笔动手时须**一并**把它移出 Class B，别只改行号 |

**（3）本笔还改了两个文件的行数（等于喂给已有的「行数快照」欠账）**：`GoodsService` **332 → 459 行**、`GoodsDao` **208 → 211 行** ⇒ §3 域表里商品域行的行数快照**再旧一档**。该行属 §3 的「行数快照」欠账（见 §3 的 TODO 登记块），本笔**不另开一笔**订正它；那笔动手时**以本笔后的值为准**。

**（4）本笔已就地改的同类失真（记账，供复核）**：`project-map.md:95`（「仅 1 处事务边界」）、`api-contract.md:119`（「无事务保护」+ 已失效的体范围）、`GoodsDao.java:52-56`（「C 档裁决、**未裁**」）、`UserService.java:32-35`（「真注解 1 处的计数**不变**」）、`three-layers.md:138` / `selection-status.md:39`（`grep @Transactional` 的 **18 行 / 1 行真注解** ⇒ 复测 **45 行 / 4 行**）。这 4 个文件**超出**用户 2026-10-04 裁决的「6 个文件」，理由：它们各自写着**被本笔改成假话的具体断言**（不是泛指），与计划已列的 `three-layers.md` / `concurrency-map.md` / `selection-status.md` 属**同一类不可压**（**用户 2026-10-04 已裁：并**）。

**（5）⚠️ 本笔的验收边界（写进 PR 描述，逐字与此同）—— 比 #11 更弱，三点事实**：

1. **本笔新增的 `GoodsWriteTxAtomicityTest`（6 条）在 CI 里从不执行**：`backend` job 全量**跑到它但被门控跳过**（产物实证 `Tests run: 6, Skipped: 6`），唯一 `A3_ENABLED=true` 的步骤又在 `-Dtest=` 白名单外（详见（1）的两条原因）；
2. **A3 的 8 个受保护端点不含 goods 写端点**（`three-layers.md §H.3`：`/goods` 9 端点里 8 个未覆盖，正是 `goodsAdd` / `goodsDe` / `goodsUp`）⇒ A3 **不会**替本笔报警；
3. ⇒ 本笔的验收证据**只有两条**：**agent 本地 `GoodsWriteTxAtomicityTest` 6/0 通过** + **A3 `concurrency` job 零回归**（而 A3 根本不碰这三个端点，故它只证明「**没撞坏别处**」，**不构成对本笔改动的验证**）。

**本轮 CI 结果（两件产物均落本地后逐字核对，非看 job 颜色）**：`surefire-reports` = **158 tests / 0 failures / 0 errors / 19 skipped**（19 恰为 4 个 `A3_ENABLED` 门控类 3+6+4+6，**与本地未设该变量时逐字一致** ⇒ 第 1 条的两条原因在产物上被双向坐实）；`a3-concurrency-report` = **`exitCode` 0**、**`gates.failed` 0 / `gates.count` 27**（27 门全 PASS；另 1 条 `A-B2-1` 为 `RECORD_ONLY`，因 `--b-group-gate=false` 按设计不计入退出码）。三个 job 全绿。

> **「CI 绿 ≠ 本笔被 CI 验证了」** —— 本 PR 合并后 CI 全绿，**不能**被读作「原子性已被 CI 验证」：绿的是上面第 3 条的**零回归**，**不是**那 6 条用例。

### 5-F. `B#1`（`fix/price-validation`，2026-10-04）登记：**边界收窄 ≠ 资损修复** + 挂账 3 条 + 待裁 1 条

> 标题口径：与 §5-E 对称，均按**笔号**命名（理由见 §5-E 的标题口径行）。**本节下文自称「本笔」处，均指 `B#1`**。

> **性质（头一句，防误读）**：`B#1` 把 `orderAdd` 的 `Price` 从「**任意值**」收窄成「**有界值**」。它**不是资损修复** —— 合法但有界的「**1 元的电视**」本笔**挡不住**。`defect-survey.md` 病 B 该行自述「仓内无支付网关 ⇒『可伪造价格』成立、『资损』是**条件后果**」；本笔消掉的是前半句的**「任意」**，**没**消掉后半句。

**（1）挂账（3 条，落点 = 本挂账段）**：

1. **价格真值源（支付侧）** —— **本笔新立的独立条目**（用户 2026-10-04 裁定必立）。要真正挡住「1 元的电视」，服务端必须**不采信客户端传来的总价**、改按 `goods` 表**重算**（= `three-layers.md §B` Class A #1 与 §G 里的 **(b) 档「换真值源」**）。
   ⚠️ **(b) 档在本接口用现行契约做不到**：下单 payload 只送 **总价**（`ProductPage.vue:339` `Price: popupTotal.value`）+ `ProductInformation`（`goodName:productCode` 拼接，`:315`）—— **没有商品 ID、也没有数量** ⇒ 服务端**原则上**算不出总价。做 (b) 必须先改接口契约（**另一笔**）；且改完也只是「有了个真值源」，离「支付侧不采信客户端」仍有距离。
   **落点明写 = 本挂账段**，**不落 `selection-status.md §8 类 4`** —— 理由与 §3 的 TODO 登记块那条先例完全相同：§8 的编号空间 `#1..#33` 与 **PR 号同形**（PR `#34` CORS 已存在），再给 §8 增 `#34` 会造出「§8 `#34`」vs「PR `#34`」的混淆。
2. **A3 空证据｜第二类实例**：`ConcurrencyRunner.java:1653` 的 `[S4a] 预置态 …` **只被 `System.out.println`、从不被任何断言核** —— 即 `S4a` 的「前置态构造成功」（三个状态位真的被改到目标态）在 A3 里**没有判据**，它只是一行日志。⇒ 若「用 `orderAdd` 造前置态」这一步**静默失败**（例如将来有人把价格闸或校验层收紧到拒绝某些 `PayIf`/`DeliveryIf`/`ReceiveIf` 组合），`S4a` 后续 8 条会**以前置态不成立的方式**给出**看起来正常**的红/绿，而报告里不会有一行指出「前置态根本没造出来」。
   ⚠️ **与 §5-E 那条的关系**：§5-E 是「**测试根本没跑**」（门控跳过），本条是「**跑了，但关键一步没有判据**」。两者都归到「A3 的绿/红对本笔零信息量」，但**成因不同，别合并**。**本笔裁决**：不动 A3 判据（动它属另一笔）⇒ 只登记。
3. **金额精度债（只登记，未动）**：同一个「金额」在本仓有**两种精度** —— `order.Price` 是 **`float`**（`mall_management_sql.sql:146`）、`goods.Price` 是 **`double`**（`:58`）。本笔**不校验小数位**（理由见 `OrderService.priceRejection` 的 javadoc：前端 `popupTotal` 是 JS 浮点求和 `ProductPage.vue:297`，真实订单本就可能带 `9.999999999999998` 这类尾数，加「≤2 位小数」会**误杀真实订单**）。⇒ 金额该用什么类型（`DECIMAL` 是业界常规）是**独立债**。

**（2）待裁（1 条）**：

- **业务上界到底是多少｜`10^7` 只是技术锚，不是业务答案**。本笔取 `10^7` 的**唯一**依据是：`order.Price` 是 `float`，而 float 的**整数精确上限是 2^24 = 16_777_216**，超过它会**静默失真** ⇒ 上界必须严格小于 2^24；再叠加「**不误杀既有数据**」（种子 6 条的最大 `Price` = 22328）。⚠️ **真正的业务上界需要业务输入**（单笔订单金额上限），本笔**不自证**、也不声称 `10^7` 是对的。

**（3）本笔已就地改的失真（记账，供复核）**：`three-layers.md:94`（Class A #1 的**位置列**原写**商品域**坐标 `GoodsController:76` / `GoodsService.goodsAdd:83-148` ⇒ 订正为 `OrderController.orderAdd:53-67` / `OrderService.orderAdd`，与同表 §G 行对齐 —— 用户 2026-10-04 裁的 4b）；`three-layers.md:281`（**开工顺序 item 4** 仍把 `B#1` 列作「三层内顺带做」**待做**，与本笔新标的 §B / §G 两处 ✅ **自相矛盾** ⇒ 按 C#2 当年划掉「事务边界（C#2）」的同款写法订正）；`defect-survey.md:119`（**位置列**原写三层前的 `MallManagementGoodsController:390 → :435-449`，与上面同一类「搬错坐标」）。⚠️ **另记一处坐标漂移**：用户 2026-10-04 引的 `defect-survey.md:107` **现址已是 `:119`**。

**（4）⚠️ 本笔的验收边界 —— 与 §5-E 的 C#2 相反：本笔的判据在 CI 里真跑**：

| 项 | C#2（§5-E） | 本笔 |
|---|---|---|
| 新测试在 `backend`（**阻塞门禁**）job 里 | ❌ 被 `A3_ENABLED` 门控**跳过** | ✅ **真跑** —— `OrderAddPriceGuardSmokeTest` 继承 `AbstractSmokeTest`（`@WebMvcTest` 切片 + `@MockitoBean JdbcTemplate`），**无门控、不连 MySQL/Redis** |
| 该测试是否在 A3 覆盖面内 | ❌ A3 不打 goods 写端点 | ❌ `ConcurrencyRunner.java:1161` 里 `"Price"` **只出现一次且恒为 `"1.5"`**，`S4a` 8 条依赖**状态位**（TD-17）**不依赖 Price** ⇒ A3 零回归**不构成**对本笔改动的验证 |

⇒ 本笔的验收证据 = **CI `backend` job 里真跑的 8 条**（3 条阳性对照 + 4 组突变对照）+ A3 零回归（只证「没撞坏别处」）。**这是本笔强于 #11 与 C#2 的地方**：那两笔的单测在 CI 里形同虚设（§5-E），本笔的不会。

### 5-G. `C#6`（`fix/order-state-guard`，2026-10-04）登记：**拦截落地 + A3 适配** + 关掉 §5-F 挂账第 2 条 + 挂账 3 条 + **订正一处流传的说法（5 份文档）**

> 标题口径：与 §5-E / §5-F 对称，均按**笔号**命名。**本节下文自称「本笔」处，均指 `C#6`**。

> **性质（头一句，防误读）**：本笔把 `orderAdd` 的三个状态位从「**客户端自选**」收窄成「**初始态白名单**」—— 接受 ⟺ `(PayIf,DeliveryIf,ReceiveIf) ∈ {(0,0,0),(1,0,0)}`。它**不是「不变式前移」** —— 后者只挡非法形状的 3 个组合（`010`/`001`/`011`），而 TD-17 正文举的 `(1,1,1)`「已付款 + 已发货 + 已收货」**是合法态**，照它会**原样放行**，落笔时只能写「不变式前移」而不能写「已修」，等于**假修**。⇒ 判据必须比「合法态」更严；这也是 `(1,1,0)` / `(1,0,1)` / `(1,1,1)` 三条**合法但非初始**态**刻意被拒**的理由（写 3 条测试钉死）。

**（1）与 `B#1` 的关系：两个闸，不是同一个闸**：

| | `B#1` 价格闸（§5-F） | 本笔 状态闸 |
|---|---|---|
| 落点 | `OrderService.orderAdd`，`runLocked` **之前** | 同左，**排在价格闸之后** |
| 判据 | `Float.isFinite(Price) && Price >= 0 && Price <= 10^7` | `OrderState.isInitial(PayIf,DeliveryIf,ReceiveIf)` |
| 失败响应 | HTTP 200 + `{success:false,msg:"价格不合法"}` | HTTP 200 + `{success:false,msg:"订单状态不合法"}` |
| 观测 | 无（B#1 未埋点） | `mall.order.state.birth.rejected{shape}` + 一行 WARN + 事件流 |

⚠️ **顺序不许对调**：`api-contract.md` 的 `B#1` 那条已把「价格不通过 ⇒ `msg="价格不合法"`」**写成对外承诺**（§5-F 称之为「已发布的契约」）⇒ 把状态闸挪到前面 = **静默收窄一条已发布契约**。有一条用例专门钉死这个顺序（`priceGateRunsFirst_whenBothGatesWouldReject`：`Price="NaN"` + 状态 `(0,1,0)` ⇒ 必须答 `msg="价格不合法"`，且 `rejectedAtBirth` 零调用）。
⚠️ 两闸**不共用**失败通道的**代码**（各自一个 `private static Map` 方法），但**共用同一种形态**（200 + `{success:false,msg}`）—— 那是全仓业务失败的既有形状，不是本笔新造的。

**（2）关掉 `§5-F` 挂账第 2 条（A3 空证据｜第二类实例）**：

- 原挂账（`§5-F`（1）第 2 条）：「`ConcurrencyRunner.java:1653` 的 `[S4a] 预置态 …` **只被 `System.out.println`、从不被任何断言核** ⇒ `S4a` 的『前置态构造成功』在 A3 里**没有判据**」。
- **本笔已修**：新增门禁 **`A-S4a-0`**（`ass(..., true)`，`ConcurrencyRunner.java:1577`），判据取**库里三位的实际值**（`jdbcOrderState(o, id)` == 目标三位），**不是**「每一步都返回了 success」；那行 `println` **保留**（读数照打）。
- ⚠️ **这条门禁上线后第一次 CI 复跑就抓到了东西，同时推翻了本笔最初写在这一行上的预言**（2026-10-04，PR #45 run `37185693143`，`gates.failed = 3` / `exitCode = 3`）：
  - **原预言**（本笔初稿的写法）：「`PD`/`PDR`/`PR` 构造失败 ⇒ 订单压根不存在 ⇒ `A-S4a-3`/`-5` 真空转绿（`succ = 0`，与「重复发货被拒」同一形状），只有 `A-S4a-6` 会红」。**实测不成立** —— 那次失败把三单停在 **`(1,0,0)`**（不是「不存在」），`delivery`/`receive` 的 `succ` 反而是 **1** ⇒ `A-S4a-3`/`-5` **自己就红了**。真正假绿的是 **`A-S4a-6`**（期望 `succ == 1`，在 `(1,0,0)` 上也成立 —— `RECEIVE` 守卫只卡 `PayIf = 1 AND ReceiveIf = 0`，见 `OrderStateMachine.java:21`，**不要求 `DeliveryIf = 1`**）。
  - **订正后的价值主张**：这条门禁的收益是**定位**，不是「唯一的告警」。它的 `detail` 直接写「前置态没建成」，把责任从「拦截逻辑」指回「测试夹具」；没有它，人会对着两条红的 `A-S4a` 去怀疑状态机。挂账原文那条命题（「S4a 的前置态构造成功在 A3 里没有判据」）**仍然成立，且已被本笔关闭**。
  - ⚠️ **那次红的根因不是 C#6，是本笔新写的夹具 URL 少写了 `/order` 前缀**（`ConcurrencyRunner.java:1609` 当时写成 `o.baseUrl + "/" + advance[k][i]` ⇒ `/orderDeliveryIfUp` ⇒ **404**；`OrderController` 是 `@RequestMapping("/order")`，唯一路由是 `/order/orderDeliveryIfUp`）。已修，并给 setup 每一步补 **HTTP 200** 判据 —— 只卡 `successTrue()` 是不够的：它是裸子串匹配 `"\"success\":true"`，404 响应体里没有它 ⇒ **「路由错」与「业务拒绝」在旧判据下形状完全相同**，这正是它被误读一轮的原因。

**（3）A3 适配：订正一处流传的说法 + 改哪几处**：

⚠️ **订正（5 处、分布 4 份文档；另有 3 处代码 / 测试注释）**：`three-layers.md:112` / `:182`、`selection-status.md:128`、`defect-survey.md:263`、`progress.md` 的 §5-B `C#6` 行都写着「拦截会使 A3 的 S4a **全部 8 条断言失去前置**」。**2026-10-04 实测：不成立** —— S4a 的 5 个前置态 `{{0,0,0},{1,0,0},{1,1,0},{1,1,1},{1,0,1}}` 按 `A-S4a-8` 的不变式**全是合法态**，闸对其中 2 个完全透明，只有 3 个（`PD`/`PR`/`PDR`）的前置**构造方式**失效 ⇒ 受影响 **3 条**、且**断言语义 0 条变**（那 3 条的判据仍是「CAS 在目标态上的行为」）。**真正被打断的是 C 组**（`C_BITS` 八组合要求全部出生）。另 3 处代码 / 测试：`OrderStateMachine.java:42-53`（C#6 更新段，明写「不是『全部 8 条』」）、`OrderTransitionRecorder.created` 的 javadoc、`OrderTransitionRecorderTest.createdWithForgedBitsRecordsTheShape` 的陈旧 `as(...)` 文案。

| 处 | 改前 | 改后 |
|---|---|---|
| `ConcurrencyRunner.s4a` 前置构造 | 5 个态全用 `orderAdd` 直传三位（含 3 个伪造态） | 2 个走白名单出生；`PD`/`PDR`/`PR` 走**合法流转**（`add(1,0,0)` → `orderDeliveryIfUp` → `orderReceiveIfUp`）。⚠️ **不是绕过拦截** —— 生产造这些态走的也是这几步；判据改取**库里三位实际值** |
| `ConcurrencyRunner.C_BITS` | 8 个组合各出生一单（含 3 个伪造态） | 改名 **`C_REFUSED`**：6 个非白名单组合，职责从「造出前置态」**翻转**成「**断言造不出来**」（新增门禁 `A-C6-1`） |
| C 组的 `PR` 单 | 直传 `(1,0,1)` 伪造 | 走 `000 → 付款 → 收货`（后端本就放行未发货收货）⇒ 该单时间线由 **1 行变 3 行**（出生 + 付款 + 收货）；`expectedNew` 由 **12 重算为 8** |
| C 组的「伪造态 pay」burst | 在 `010` 单上打 20 并发 PAY | **删除** —— 该前置态已造不出来（覆盖登记见下（4）） |

**（4）D1 裁决：`forged="010"` 那段删掉，覆盖**改为登记到别处**（不是净删覆盖）**：

原 C 组用「伪造单 `010` 上的 PAY 必成功」证「时间线如实记下从非法态来的转移」。拦截后该前置态不存在 ⇒ 运行器里那条**不可保留**。**覆盖登记**（口径：删的是**运行器里的那一条**，覆盖本身另有承接）：

- `OrderStateMachineTest.onlyPayGuardAdmitsIllegalStates`（**已存在**）：证「只有 PAY 的守卫接纳 `010`」；
- `OrderAddStateGuardSmokeTest`（**本笔新增**）：证「`010` 等非法态在**入口**被拒」；
- `OrderTransitionRecorderTest.createdWithForgedBitsRecordsTheShape`（**保留**、javadoc 已标该支路不在 API 路径上）：证「若库里有**存量**非法行，观测仍按原样记」。

⚠️ **代价（明写）**：`ConcurrencyRunner.stateName()` 末尾那三个 `ILLEGAL_*` 分支在 C 组里成为**死代码**。**保留不删** —— 理由与 `mall.order.state.illegal` 保留不删相同（**存量脏数据仍会被重放读到** + 删掉等于抹掉 TD-17 的历史口径）。

**（5）挂账（4 条，落点 = 本挂账段）**：

1. **「今日拦截 N 次」在现行观测里给不出** —— `mall.order.state.birth.rejected` 是 **Micrometer counter = 进程生命周期累计**，**没有按日切的能力**；且本仓 `application.properties` 只暴露 `health,metrics`、**未接 Prometheus**（无时序库 = 无历史）。⇒ C3 要显示「今日」只有两条路：**(a)** 读本笔已发的**事件流**（每条带 `epochMilli` 墙钟）自行按日聚合；**(b)** 接 Prometheus / 时序库。⚠️ **本笔只保证「事件流里带够信息」**（`key` + `shape` + 时间戳），选 (a) 还是 (b) **留给 C3**。⚠️ **事件环只有 512 槽**（`MallOrderStateMetricsTest` 实测容量）⇒ 选 (a) 时**只能看「最近若干次」**、不能当真值用；这条限制由 C3 那笔处理，**不是本笔能关的**。
2. **`birth.rejected` 的 tag 词表与 `illegal` 是两份字面量拷贝** —— `010`/`001`/`011`/`non_binary` 这 4 个值在 `OrderStateMachine.illegalShape` 与 `rejectionShape` 里各出现一次（后者多一个 `non_initial`）。⚠️ **判据没有两份**（`rejectionShape` 直接调 `illegalShape`，不是复制逻辑），但**词表字符串没有单一真值源** —— 现由 `OrderStateMachineTest.rejectionShapeNamesEveryRejection` 逐条对拍顶着，暂不抽常量。⇒ 将来 shape 取值要增删时，**两处一起改**，那条对拍测试会红。⚠️ **tag 只收 `illegalShape()` 归一后的取值**（含 `non_initial` 共 5 个），**三位原值绝不进 tag**（否则条数随客户端传什么涨 —— 基数无界；`MallOrderStateMetrics` 类注释是本仓基数铁律的唯一出处）。
3. **两处「行数快照」被本笔进一步喂旧 —— 本笔明写「不改」，登记在此免得成孤儿**：`project-map.md:30`（`ConcurrencyRunner.java` **2795 行**）与 `ddd-package-plan.md:297`（「A3 **27 门禁**全绿」+ 基线 `130/0/0/8`）。⚠️ **三处数**都已陈旧：runner 实际 **2907 行**（**注意：2795 在本笔之前就已是旧值** —— 前序的笔已把它改到 2827，故这两处**不是本笔弄旧的**，只是本笔又添了约 80 行）、门禁实际 **30**、本地基线实际 **186/0/0/19**。⇒ **本笔不动它们**（超出裁决的 14 文件、且它们本就不准）；它们**归已有的「行数快照」欠账**（§3 的 TODO 登记块）⇒ 那一笔动手时须连同本笔的新值一起改。⚠️ **另**：`three-layers.md:207` 的同款「2795 行」**本笔已就地改**（它在 §H，正是本笔大改的那一节）。
4. **`A-S4a-6` 分不出 `(1,0,0)` 与 `(1,1,0)` —— 首次 CI 复跑那次假绿的成因（2026-10-04 收尾时登记）** —— `A-S4a-6` 的判据是「`orderReceiveIfUp` on `PD` ×20 → **`succ == 1`**」，**只数成败、不比对终态三位**。而 `RECEIVE` 的守卫只卡 `PayIf = 1 AND ReceiveIf = 0`（`OrderStateMachine.java:21`，**不要求 `DeliveryIf = 1`**）⇒ `(1,0,0)` 与 `(1,1,0)` **两种态都收得动**、都返回 1。⚠️ **两轮 CI 实测都 PASS**：首轮（run `37185693143`）`PD` 因夹具 URL 缺 `/order` 前缀而 404、塌在 `(1,0,0)`（**根本没建成**），这条**照绿**；夹具修好后的第二轮（run `37192169578`）`PD` 真的是 `(1,1,0)`，它**也绿**。⇒ 这条断言对「前置态到底对不对」**零信息量**，是那次 3 条红里唯一的假绿。**若要它变强**：判据改成同时比对**终态三位**（`(1,1,1)` vs `(1,0,1)`）。⚠️ **本笔不动**（属**断言强度增强**，不是本笔的拦截范围）—— 登记在此，免得被读成「`A-S4a-0` 已兜住一切」。

**（6）⚠️ 本笔的验收边界 —— 判据在 CI 里真跑（同 §5-F 的结论，异于 §5-E）**：

| 项 | 结论 |
|---|---|
| 新测试在 `backend`（**阻塞门禁**）job 里 | ✅ **真跑** —— `OrderAddStateGuardSmokeTest`（10 用例）继承 `AbstractSmokeTest`（`@WebMvcTest` 切片 + `@MockitoBean JdbcTemplate`），**无门控、不连 MySQL/Redis** |
| A3 侧新增门禁 | `A-S4a-0` / `A-C6-1` / `A-C6-2`，**静态计数 `gates.count` 27 → 30**（`ConcurrencyRunner.java:711-719` 的计数循环；31 条 `ass(` 中 30 条 `gate=true`，唯一 `false` 的是 `A-B2-1` 的 `o.bGroupGate`）。**artifact 实测 = 30 ✓**（2026-10-04 首次 CI 复跑，见下 (7)；本笔**不**在本地跑 A3 —— 它需 8.4 目标库，P1 版本门禁会拦下）。⚠️ **且 A3 的 `concurrency` job 是 `continue-on-error: true`（`ci.yml`）⇒ 不拦 merge** ⇒ 这三条只能算**旁证**，不能算验收证据 |
| 突变对照 | 3 组（见下），判据 = **整类用例的红数分布**，不是「指定那条一定红」 |

**突变对照（本笔实测，各在 `OrderAddStateGuardSmokeTest` 的 10 条上跑）**：

| 突变 | 红数 | 红的是哪几条 |
|---|---|---|
| ① 删掉出生闸（条件恒 false） | **7 / 10** | 全部 7 条「被拒」用例（2 条放行用例仍绿） |
| ② 放宽成「只挡非法形状」（= `!isCanonical \|\| !legal()`） | **恰 3 / 10** | 只有 `shipped`(110) / `receivedNoDelivery`(101) / `completed`(111) 三条 —— **正是「合法但非初始」那三条**，与判据设计逐字对应 |
| ③ 把闸挪进 `runLocked` **之内** | **7 / 10** | 全部 7 条「被拒」用例（`verify(lockService, never()).runLocked(...)` 同时失效） |

⚠️ ②的红集**恰好等于**「白名单\{合法态\}」，不是「红了 3 条」的巧合 —— 它证明判据**确实停在「初始态」而不是「合法态」**上（R3 那种取法会让这 3 条红不了 ⇒ 假修）。

**本轮本地结果**（`rm -rf target/surefire-reports` 后 `./mvnw -B test`）：`Tests run: 186, Failures: 0, Errors: 0, Skipped: 19`、`BUILD SUCCESS`。**166 → 186**（+20 = 新类 10 + `OrderStateMachineTest` +4 + `MallOrderStateMetricsTest` +3 + `OrderTransitionRecorderTest` +3）；**19 skipped 与基线逐字一致**（19 = 4 个 `A3_ENABLED` 门控类 3+6+4+6）⇒ 本笔**没碰门控**的旁证；新类**不在** skipped 里（它在 `backend` job 真跑）。

> ⚠️ **CI 绿 ≠ 本笔被 CI 验证了**（同 §5-E 的告诫）：本 PR 合并后 A3 若全绿，**没有**验证本笔 —— A3 的 `concurrency` job 不拦 merge，且本笔新增的三条门禁也只证「A3 侧适配没塌」。真正的验收证据是上表 `backend` job 里真跑的 10 条 + 3 组突变对照。

**（7）首次 CI 复跑实测（2026-10-04，PR #45，run `37185693143`）—— 一次「门禁抓到人」的完整记录**：

⚠️ **整轮 run 的结论是 `success`，但 A3 job 是 `failure`** —— 这正是 `continue-on-error: true` 的效果：**job 颜色被系统性抹平，只有拉 artifact 才看得见**（`§5-E` 同款告诫的又一次实证）。artifact 落本地后的实测：

| 判据 | 期望 | 实测 | |
|---|---|---|---|
| `gates.count` | 30 | **30** | ✅ 27 → 30 对上 |
| `gates.failed` | 0 | **3** | ❌ |
| `exitCode` | 0 | **3** | ❌ |
| `A-S4a-0` / `A-C6-1` / `A-C6-2` | 三条 PASS | **FAIL** / PASS / PASS | ❌/✅/✅ |
| `S4a` 8 条 + `C-1`/`C-2` | 全 PASS | `A-S4a-3`/`-5` **FAIL**，其余 6 条 + `C-1`/`C-2` PASS | ❌ |
| `surefire` `Tests run` | 186 | **186, Failures: 0, Errors: 0, Skipped: 19** | ✅ 与本地逐字一致 |

- **红的三条全因本笔新写的 A3 夹具 bug，与 C#6 无关**：`ConcurrencyRunner.java:1609` 少写 `/order` 前缀 ⇒ `PD`/`PDR`/`PR` 三单的推进调用全 404 ⇒ 停在 `(1,0,0)`。（C#6 本身由 `A-C6-1`/`A-C6-2` PASS 背书。）⇒ **已修**：补前缀 + setup 每一步卡 **HTTP 200** + 修 `preStates` 打错响应体（原来失败时打的是 `add` 的 `{"success":true}`，与 `FAILED` 并排，方向性地误导）。**A3 是本笔核心验收，故本笔在这三条红被修好之前不 merge。**
- **本笔对 `A-S4a-0` 的价值主张已在 (2) 订正**：原预言「真空转绿」被实测推翻（塌成的不是「不存在」而是另一个错态 `(1,0,0)`），真实收益是**定位**。

### 5-H. M5 里程碑落盘笔（`docs/m5-milestone`，2026-10-04）登记：**挂账 3 条**

本笔只改文档 —— 落点 = `tech-debt.md` 文末 **M5**（`:328`）+ 本文件 **§1 表新行「三层重构」** + **§3 两处 `C#6` 残留收口**。**不动代码 / 前端 / `ci.yml`**。三条挂账：

- **（1）2026-09-30 那一批（PR #8–#24）尚无里程碑收录 —— 是否补 M 待裁。** 该批 = A 批（#8 `feature/pre-three-layers-hardening`）/ G 表（#11 `79de3cb`、#12 `47c1ded`）/ 规则 v2（#13 `f19a14e`）/ S1a 7 笔（#14–#20）/ 三层前准备（#21 `abc0735`、#22 `69ac718`、#23 `b72bc80`、#24 `a2d593e`）。它落在 **M4 里程碑落盘（`c57f7e7`，2026-09-30 13:59）之后** ⇒ **M4 收录不到**；M5 按主题（三层重构）**也不收**。⇒ 现状只活在 **§1.1 已完成的收尾批** 与 **§2 三层前准备** 里，`tech-debt.md` 的「已完成里程碑」段**没有它**。**本笔不新开 M、不扩 M5 范围**；已在 M5 引注末句标「是否另立待裁」，此处再挂一条免得成孤儿。
- **（2）`context-packs/three-layers.md:241` 的旧口径未订正。** 该行仍写「artifact 里的 `gates.count` 实测值**待 CI 复跑**」—— 实际已核为 **30**（见本文件 **§5-G (6) / (7)**，run `37192169578`）。它是**同一处事实的另一份拷贝**（`gates.count` 27 → 30 的落点），**本笔不动**（超出裁决的文件范围），登记在此。⚠️ 同类的「拷贝未跟」还有 `project-map.md:30` 与 `ddd-package-plan.md:297`（行数 / 门禁数快照），已由 **§5-G (3)** 挂账，此处不重复。
- **（3）更新记录表仍欠三处 PR 号 —— 已挂账，无需另补。** 该欠账由**表下注自身**（`## 更新记录` 表下注）承担：仍欠 **2026-10-02 行 / 2026-10-03 行的 `+ 本笔`** 与 **`B#1` 行**（应填 `#44`），补法 = **合并后另起一笔**（同 #42 的 `d79ad96` / #11 的 `37cafc3`）。**本笔不加更新记录行** —— 那会让欠账从 3 处变 4 处，而 M5 的落点已在 §1 表新行与 `tech-debt.md:328` 各自可查。⚠️ **本笔已知副作用一处**：§1 表新行使本文件**其后所有行号 +2** ⇒ 文内既有的**行号自指**（`:82` 除外，它在 §1 之前）**整体偏移 2 行**，且它们**本就有偏差**（例：§3 表下的 TODO 原写「`progress.md:141-143` Controller 行数快照」，而 `:141-143` 指的是**另一段文字**）—— 建议**另起一笔**把这类自指改成**节锚点**（`§1.1` / `§3` / `§5-G`）而非行号。⇒ ⚠️ **2026-10-04 已由 `docs/progress-53-record` 落地，见 §5-P。**

### 5-I. `#48`（`fix/frontend-api-layer`，2026-10-04）登记：**已知错误 2 处（51 分解、52 vs 54）**

收尾写更新记录行时复核数字，发现 **#48 自身产物有 2 处数字错**（均为**我在 #48 里写错**的，非他人）：

- **（1）51 行的分解错。** `.env.production` 的注释、#48 的 PR body、commit `8118741` 都写「51 = **42** 处 fetch 字面量 + 6 处 URL 赋值 + **3** 处 `:src` 头像」。**逐行实测**分解 = **39** fetch 字面量 + **6** URL 赋值行 + **3** 处头像 URL（2 处 `:src`：`GoodsUp:9` / `UserInfoPage:7`；1 处 `const defaultAvatar`：`FriendRoom:71`）+ **3** CSS `url()`（`LoginPage:9/10/274`）= **51**。原式**双算了 3 处**（那 3 个走变量 URL 的 fetch，其字面量正落在「6 处 URL 赋值行」内）、**又漏了 3 行 CSS** —— `42+6+3=51` 只是数字巧合。⚠️ **§6-B 的 P2.1 行本身是对的**（「42 fetch 调用（39 字面 + 3 变量）」），错的是 #48 对它的转述。
- **（2）登录态 `sessionStorage`：`52` 与 `54` 是两套口径，`54` 才对得上 §6-B。** 逐出现实测 **54**（= `merchantid` **24** + `customerid` **12** + `loginUsername` **12** + `loginPassword` **6**），与 §6-B 的 P2.6「**71** 处 = **54** 凭据 + **17** 页面状态键」同口径；`8118741` 与 #48 的 PR body 写的 **52** 是另一套口径——「**行×键**」去重 = `merchantid` 23 / `loginUsername` 12 / `customerid` 11 / `loginPassword` 6（差在 `HomePage.vue:116` 同行两键、`ProductPage.vue:415`/`:416` 同行两次出现）；还有第三个数 **51** = 涉及凭据的行数。⚠️ **`52` 的病不在值，在被拿去与 `17` 相加对 `71`** —— 两口径混用（`52+17=69≠71`）。**本仓一律读逐出现的 `54`**；同一笔内 `489800d` 写的正是 **54**。
- **处置**：`.env.production` 注释**已订正**（纯注释、**行为零变更**）；**#48 的 PR body 已订正**（GitHub 上直接改，无需 commit）；**`8118741` 的提交信息未修** —— 改它须改写 #48 已合并的历史，**不做**。⚠️ 残留：`8118741` 的 message 里那两个错数**永久留痕**，引用该 commit 的说明时按本条的 **39 / 54** 读。**另**：`src/api/auth.js:3-8` 的头注释**同病**（写 `52`，且把 `52` 与 `17` 相加去对 `71`）—— **本笔已订正为逐出现的 `54`**，并注明 `52` 是「行×键」口径（**纯注释、行为零变更**）。
- ⚠️ **副作用一处**：本次给更新记录表加的行使本文件**其后行号 +1**（与 §5-H (3) 记的「§1 表使其后 +2」叠加）⇒ 文内**行号自指**再偏移 1 行；建议同 §5-H (3)，改用**节锚点**而非行号。⇒ ⚠️ **2026-10-04 已由 `docs/progress-53-record` 落地，见 §5-P。**

### 5-J. 鉴权笔（`BD-03`）开工前调研 —— 登记 3 条**发现** + 1 条**裁定**（`P2.6` 归属）

> **来源**：为 `BD-03` 出方案做的**只读**调研（后端鉴权面 / A3–CI 影响面）。**本节只登记，未改任何代码。**
> `BD-03` 的**裁决**、选 A 的**理由更新**、落地拆笔、A3 影响面、文件数预估**不在本节** —— 归后续「`BD-03` 落裁笔」。

- **（1）`AbstractSmokeTest.java:45` 计数过时。** javadoc 写「**5 个** smoke test 无需改动」，实测 **8 个**类 `extends AbstractSmokeTest`。纯文档漂移，无行为影响。
- **（2）`UserService.java:98` 明文账号密码进 stdout。** 登录成功后 `System.out.println(username + "," + password)` ⇒ **明文凭据落进程日志**。⚠️ 但 `:94` 的 javadoc 明写这是「三层前就有的副作用，**照搬** —— 它不是调试残留」⇒ **刻意的**，故不是笔误、是**既存安全面**。**待裁**：并进鉴权笔（登录改造本就动这一处）还是单列。
- **（3）`login.Password` 宽 `char(20)`，装不下 bcrypt。** 见 `db-schema.md:178`（`char(20)` 显式 / **明文**）。bcrypt 输出 **60** 字符 ⇒ hash 化**不能原地改列**（数据库规则禁 `MODIFY COLUMN`），只能走**影子列**（新增列 + 回填）。**待裁**：建议**单列一笔**（文件数 4–5），排在鉴权笔**之后**。
- **（4）【裁定 · `P2.6` 独立一笔】** ⚠️ **前 3 条是「发现」（调研看到的事实），本条是「裁定」（用户做的决定）—— 性质不同，勿混。** 内容 = 删前端 **4 个读写口**（`LoginPage.vue:163-169` 写 / `GoodsUp.vue:145-146` 读 / `UserInfoPage.vue:91-92` 读）+ 拆 `UserService.java:185` 的 `selectRoleForProfile(username, password)` 重验。**前提** = 鉴权笔落地（凭据进 **httpOnly Cookie**）；**时序** = 鉴权笔 → `P2.6` 笔。
  - ⚠️ **口径（已订正，勿沿用旧说法）**：`P2.6` 的解**不是「选 A」**，而是「**凭据进 httpOnly Cookie**」；A 只是**默认就走这条路**、且不引入密钥管理 / 刷新 / CSRF 复杂度的那个（**最省事 ≠ 唯一**）。B 把 token 放 httpOnly Cookie **同样解**；放 `localStorage` 则**只减轻不消除**（token 有时限、可撤销、不可复用，价值低于密码 —— 故「明文换明文」这个说法**不成立**）。C 与 `P2.6` **无关**（其默认形状即 A）。

### 5-K. `application-a3.properties` 注释漂移 **2 处**（鉴权笔调研附带发现；登记，未修）

> **来源**：为鉴权笔 1 做**撞车面体检**时只读扫到，**与鉴权无关**。
> **处置**：**本笔不动**（用户裁决：主题不符）。**归属** = 与「部署 / 上传」同族的那批笔（§5-C 的 `TD-05` / `TD-08` / `TD-09`）一并订正。

- **（1）行号过时。** `application-a3.properties` 写「`ImgPath.initUploadDir()` 的 `@PostConstruct` 调用 `Files.createDirectories(uploadPath)`（`ImgPath.java:23-27`）」。**实测**：`@PostConstruct` 在 **`ImgPath.java:45`**、`initUploadDir()` 在 **`:46`**、`Files.createDirectories(candidate)` 在 **`:49`**；`:23-27` 是字段 `uploadDir` 一带。
- **（2）行为描述与代码相反（较重）。** 同一段继续写「若创建失败会**抛 `IOException` 使 Bean 初始化失败**——即「启动期炸」，**不会静默**」。但 `ImgPath.java:31-34` 的 javadoc 明写「**本方法刻意不抛异常**……此前签名是 `throws IOException`」⇒ **该行为已被改掉**，注释描述的是**旧行为**。**现状**：启动期只 **`WARN`**、不阻断；失败推迟到真正落盘那一刻，由上传入口显式返回 `{success:false,msg}`。
### 5-L. TD-01 **`A` 方案**（删 `spring-boot-starter-data-jpa`）的实测结论 —— 本笔**不做**，仅登记（2026-10-04）

> ⚠️ **本节编号**：原拟 `§5-K`，与 **PR #51（`be1b1e4`）** 新增的 `§5-K`（`application-a3.properties` 注释漂移）**撞号**，2026-10-04 合并 `origin/main`（`a107a2c`）时改为 **`§5-L`**；其后的两节顺延为 `§5-M` / `§5-N`。

> **来源**：TD-01 裁决前，为回答「删掉依赖之后启动路径会怎样」做的一组实测（加 `HikariConfig=DEBUG`，对照跑 `update` / `validate` / 库不可达）。
> 用户 2026-10-04 裁定走 **`B` 方案**（依赖保留、只改 `ddl-auto`）⇒ **本节结论本笔不使用**，登记备查。
> ⚠️ **若未来改走 `A`，下列注释需同步改** —— 它们现在描述的都是「JPA 在、启动期建池」这一现状。

- **（1）删 JPA 后，Hikari 连接池不再在启动期建立。** 依据（实测）：移除 `spring-boot-starter-data-jpa` 后单跑 `contextLoads()`，日志里**只有** `HikariConfig` 的构造行、**没有** `HikariPool-1 - configuration:` 块；直到 `databaseIsReachable()` 发出第一次 `getConnection()`，该块才在 **15.4s** 之后出现 ⇒ **池在首次取连接时才建**。
- **（2）改走 `A` 时需同步改的三处注释**（改后它们会变成假话）：
  - `application-a3.properties:12-17`（「本项目启动期 Hibernate 初始化即触发连接，HikariConfig 块在 Tomcat started【之前】已打印，【不需要额外请求】」—— ⚠️ 该块 `:16-17` 已自己预留了这条退路，措辞可直接复用）；
  - `concurrency-test.md:70` / `:76`（同一时机论断）；
  - `MallManagementApplicationTests.contextLoads()` 的 javadoc（现写「成因有二」，`A` 下**只剩「数据源懒连接」一条**）。
- **（3）⚠️ 本节证据**不在仓库里**，且不可由 CI 复现。** 本轮有 **2 组探针作废并重跑**（均为**自捕**后丢弃）：① 环境变量 `LOGGING_LEVEL_..._HIKARICONFIG` 因 Spring 宽松绑定会**小写化**段名，而 logger 名大小写敏感 ⇒ 空探针（改用 `SPRING_APPLICATION_JSON`）；② `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` 被绑定器解读为 `spring.datasource.hikari.connection.timeout`，而在 `HikariDataSource` 上 `connection` 可经 `getConnection()` 绑定 ⇒ **绑定动作本身建立了一次连接**，污染整组运行（对照组的失败与 `tech-debt.md:131` 相矛盾，即由此发现）。⇒ 本节所有数字均为**人工执行、一次性**，引用时按「叙述记录」档读。

### 5-M. `HealthController.java:42`「其余 **33 处** HashMap」 vs `Result.java:10`「全项目 **33 个端点**…**30 个**返回 `Map<String,Object>`」—— 数字对不上（登记，本笔不修）

- **两处原文（逐字，2026-10-04 亲读）**：
  - `HealthController.java:41-43`：「本方法是全仓**唯一**用 LinkedHashMap 组装响应的端点（插入序），与其余 **33 处** HashMap（桶序）不同 ⇒ 包进 Result（HashMap 基类）会改它的字节 …」（该行开头另标「**例外 5/5**」）
  - `Result.java:10`：「全项目 **33 个端点**里 **30 个**返回 `Map<String,Object>`，前端按**顶层键**取值」
- **对不上在哪**：`HealthController` 写「**其余** 33 处」⇒ 加上它自己应是 **34**；而 `Result.java` 写全项目**一共** 33 个端点。**若两个 33 指同一总数**，则 `HealthController` 的「其余」把自身也算了进去（应为 32）；**若不是同一总数**，则两处**共用 33 却各指一物**，无从判断哪个是笔误。⚠️ 第三个数「30 个返回 `Map`」与「例外 5/5」也**互不印证**（33−5=28，33−30=3）。
- **端点数 33 已核实**（逐个数 `@*Mapping`：goods 9 / message 10 / order 8 / user 5 / health 1）；**「33 处 HashMap」「30 个」「5/5」三个数均未核实**。
- **裁定（用户 2026-10-04）：登记到挂账段，本笔不修。** ⚠️ 若要查，须先定**分母**（「端点」？「返回 `Map` 的方法」？「会经 `Result` 的方法」？）—— 三者不同，不能互证。

### 5-N. 本笔**不加**「更新记录」行（按 §5-H (3) 先例）—— 登记

- **内容**：TD-01 笔（`chore/jpa-ddl-validate`）改了 `application.properties` / `application-a3.properties` + 8 份文档，但**未在 `## 更新记录` 加行**。
- **理由**（与 §5-H (3) 同）：① 加行会让**表格欠账从 3 处变 4 处**（现欠：2026-10-02 行、2026-10-03 行、`B#1` 行的 PR 号）；② `## 更新记录` 在 §1 之前 ⇒ 加一行会使本文件**其后所有行号 +1**，与 §5-H (3) 的 +2、§5-I 的 +1 **叠加**，而本文件到处是**行号自指**；③ 本笔落点**已各自可查**：`tech-debt.md` TD-01（状态 → 已关闭，含分支与验收）、`db-schema.md` §3、§5-L。
- ⚠️ **若用户要求补**：合并后另起一笔加行并填 PR 号（与 `#42` / `#45` 的补法相同）。

### 5-O. 组 B 文档漂移 **3 处**（鉴权笔 1 `feat/auth-gate` 复核附带发现；登记，本笔不修）

- **来源与归属**：2026-10-04 鉴权笔 1 落地 `AuthInterceptor` 后逐份核「无拦截器」类断言时顺手发现。**不是本笔造成的**（本笔造成的 4 文件 6 处＝组 A，已随本笔**就地同步**，见末条），故按裁决**只登记、不修**。
- **（1）`three-layers.md:119` / `defect-survey.md:117`「`@ControllerAdvice` / `@ExceptionHandler` = **0**」—— `GlobalExceptionHandler` 落地时即已不成立。** 该仓用的是 **`@RestControllerAdvice`**（`GlobalExceptionHandler.java:83`，`assignableTypes = {MessageController, GoodsController}`），`@ExceptionHandler` **3 处**。⇒ 那两个 `= 0` 与本笔无关。⚠️ 本笔已在**同两行就地**补上「`HandlerInterceptor` 现为 1」并注明另两者属本条 —— 即那两行现在**同时**承载组 A 订正与指向本条的路标。
- **（2）`AbstractSmokeTest` **8 处**写「**5 个** smoke 子类」，实测既有 **8** 个（本笔后 **10** 个）。** 出处（2026-10-04 亲读逐处）：`:45`、`:97`、`:141`、`:149`、`:175`、`:179`、`:197`、`:239`。⚠️ 该文件属**本笔硬边界**（「不碰 `AbstractSmokeTest`」）⇒ 只登记。⇒ **凡把这 8 处的「5 个」抄进新文档的，都会把假数带下去。**
- **（3）「32 个接口」vs「33 个接口」口径差。** `32` = 4 个业务 Controller 的 handler 数（逐个数 `@*Mapping` 去类级 4 条：goods 9 / message 10 / order 8 / user 5，**2026-10-04 核实准确**）；`33` = 再加 `HealthController` 的 `/health/db`（`@Profile("a3")`）。⚠️ 写「**全项目** 32 个接口」时**含运维口即少算 1**。**该口径差在本笔之前就存在**（`HealthController` 非本笔新增）；本笔组 A 同步时**保留**原数字（它指业务面，且改动会与 §5-M 的端点数讨论交叉）。
- **裁定（用户 2026-10-04）：登记到挂账段，本笔不修。**
- **随本笔已同步的（组 A，供对照，不属本挂账）**：`three-layers.md:119` / `:185` / `:198`、`business-decisions.md:46`、`defect-survey.md:117`、`project-map.md:81` —— 共 **4 文件 6 处**，全部**同行替换（`-N +N` 净零）**，**零行号偏移**。

> ⚠️ **本节插入自身的副作用（按 §5-N 同口径记录）**：本节插在 **§5-N 之后、`## 6` 之前** ⇒ 本文件 `## 6` 及其后所有行号**整体后移**。已核：本文件**被外部引用的行号最大 `:199`**（`:82/83/85/86/130/132/141/150/151/153/158/160/163/189/199`），**自指行号最大 `:151`**，**全部在插入点之前** ⇒ **零引用偏移**。将来往 §5 尾段插内容仍须先做这一核。⚠️ **2026-10-04 订正（`docs/progress-53-record`）**：上列「被外部引用的行号」已在本笔**全部改成节锚点**（详见 §5-P）⇒ 本句清单为**历史测量**；本文件在仓内**已跟踪**文档中现已无「被外部引用的行号」。

### 5-P. 笔 1 收尾笔（`docs/progress-53-record`，2026-10-04）—— 补 `#53` 行 + 行号自指改节锚点 + `@CrossOrigin` 漂移收口

> **性质**：**纯文档**。不动代码 / 测试 / 前端 / `ci.yml` / A3 脚本 / `application*.properties`。

- **（1）补 `## 更新记录` 的 `#53` 行（鉴权笔 1 `feat/auth-gate`）。** 补法同 `#42`（`d79ad96`）/ `#11`（`37cafc3`）—— 合并后另起一笔补号。⚠️ **本笔自身不加行**（按 §5-H (3) / §5-N 先例）：加行会让表格欠账从 3 处变 4 处。
- **（2）`progress.md` 内的行号自指 → 节锚点（§5-H (3) / §5-I (2) / §5-N 三次建议，本次落地）。** 共 5 处：`:158` 的「`progress.md:141-143`」→「§3 的域表行数快照」；`:250` 的「本文 `:145`」→「§3 域表」、「（见 `:151`）」→「（见 §3 的 TODO 登记块）」；`:274` 的「本段 `:158`」→「§3 的 TODO 登记块」；`:346` 的「（`progress.md:151` 登记）」→「（§3 的 TODO 登记块）」；`:393` 的「（`:82`）」→「（`## 更新记录` 表下注）」。
- **（3）外部指向 `progress.md` 行号的 7 处引用 → 节锚点。** `selection-status.md` 6 处（`:127` / `:130` / `:131` / `:132` / `:133` / `:190`）+ `tech-debt.md:334` 1 处。⚠️ **这 7 处在本笔之前就已陈旧 7–9 行**，不是本笔弄旧的 —— 例：`selection-status.md:131` 引 `progress.md:85` 要说 C2 SSE，而 `:85` 是**表格欠账注**、C2 行实在 `:94`；`:133` 引 `:199` 要说 `TD-05/06/08/09`，而那组在 §5-C。⇒ 一律改指**节**（§1 / §3 / §4 / §5-C）。
- **（4）`@CrossOrigin` 漂移收口（6 文件 8 处）。** 那 5 处 `@CrossOrigin` 早在 **PR #34 `23f5361`** 已全部删除，但 6 份文档仍写「5 个 Controller 均标」。本次改为「已删 / 现 0 处」：`api-contract.md:9`、`three-layers.md:120`、`defect-survey.md:14` / `:104`、`project-map.md:81`、`tech-debt.md:61` / `:62` / `:64`。⚠️ **同文件内自相矛盾一并订正**：`tech-debt.md:344`（记「公共层笔 1/3 —— CORS：删 5 处」= `D#3` 的 CORS 面 ✅）与同文件 `:61`/`:62`/`:64`（仍写「**5 个** Controller 各自标注」）互斥 ⇒ 本次把 **TD-04 状态改为「🟡 部分关闭」** —— **重复配置面 ✅ 已关**（PR #34），**通配风险面仍开**（`allowedOriginPattern("*")` + `allowCredentials(true)` 未收敛，归生产加固）。`progress.md` §3 公共层行的「`D#3` 的 CORS 面 ✅」**与新状态一致，不改**。
- **（5）已知残留（本笔不修，登记）：** `defect-survey.md:213` 仍以「TD-04 写 3 处 `@CrossOrigin`，实为 **4**」作「文档锚点应从行号改符号名」的证人 —— 那是**当时的**漂移记录（非现行断言），故保留；引用 TD-04 时按 (4) 的新状态读。
- ⚠️ **（6）本笔的行号副作用。** 本笔在 `## 更新记录` 表尾（原 `:83` 后）**加 1 行**、并在 `## 6` 之前**加本节** ⇒ 本文件其后行号整体后移。已核：**仓内已跟踪文档中指向本文件的行号引用已在本笔全部改成节锚点**（见 (2)(3)），故**没有行号引用被这次偏移打破**；⚠️ 本节 (2)(3) 里写的 `:158` / `:250` / `:274` / `:346` / `:393` / `:127`… = **改动前**的编号，仅供对本笔 diff 定位，**不要当作现行位置引用**。

### 5-Q. 鉴权笔 2（`feat/auth-session`，2026-10-04）登记：**会话存储两条代价** + **「33」口径过时（2 处）** + **5 处漂移**（登记，本笔不修）

> **性质**：**本笔有代码**（新增 `/user/me`、`/user/logout` 两个端点 + `loginSelect` 写会话；改动 7 个文件，清单见 PR 描述）。以下 7 条**全部是登记/挂账**，本笔**一条都不修**。

- **（1）会话存储 = 纯内存（`HttpSession` 默认实现）—— 这是裁决，不是默认。两条代价已登记、本笔不选 Redis。** ① **多实例部署不共享会话**（第二台实例看不到第一台发的会话 ⇒ 需 sticky session 或共享存储方可水平扩展）；② **重启即丢**（所有在线用户被登出）。⚠️ 不选 Redis 的**最硬**一条不是「多引入一个组件」，而是它与本仓既有的**降级契约冲突**：`application.properties` 明写 Redis 不可达是**被容忍的降级态**（且 `management.health.redis.enabled=false` ⇒ 连健康检查都不报），而 Redis 存的会话在 Redis 挂掉时会**从「不确定」变成「已登出」** —— 那是一个**新契约**，且它的失败**不可观测**。落点（javadoc）：`AuthSession` 类注释。
- **（2）`Result.java` 的「33」两处口径在本笔之后**都已过时**（且它们本就互相不一致）。** `Result.java:10`「全项目 **33 个端点**里 **30 个**返回 `Map<String,Object>`」与 `:18`「今日 **33 处**响应 Map」—— 本笔新增 2 个端点 ⇒ 端点数 **33 → 35**（`api-contract.md §2` 已就地改成「共 35 个」）。⚠️ **本笔不改 `Result.java`**（理由同 (4)：避免夹带；改它是**行为无关**的注释笔，但会让本笔的 diff 伸进公共层笔 2 的地盘）。→ 与 **§5-M** 是**同一条欠账的两个面**：§5-M 记「33 处 / 30 个 / **分母未定**」，本条记「**基线本身已变**（无论按哪个口径都已过时）」。⚠️ §5-M 里那句「**端点数 33 已核实**（逐个数 `@*Mapping`：… / user 5）」现在也要改成 **user 7 / 合计 35**。
- **（3）`AbstractSmokeTest:43-45` 的「鉴权口子」措辞与**实际接法**不符（登记，本笔不修，本笔不改 `AbstractSmokeTest`）。** 原文逐字：「将来接入认证时，只需在此基类补全 `authHeaders()` 的实现，**5 个 smoke test 无需改动**」。实测两处不符：① 预留口是**请求头**（`:253` `authHeaders()` → `:262` 在 `request(MockHttpServletRequestBuilder)` 里 `authHeaders().forEach(builder::header)`），而鉴权笔 1/2 的**实际接法**是**开关 + `HttpSession`**（`MockHttpSession` + `.session(...)`），**一个请求头都没用** ⇒ 「只需改基类一处」对本次接法**不成立**（本笔的 `AuthSessionSmokeTest` 每处调用点自己挂 `.session(...)`）；② 「**5 个** smoke test」早已不是 5 —— 本笔后直接继承 `AbstractSmokeTest` 的类共 **12** 个（鉴权笔 1 时为 10，见 §5-O (2)），本条只补记**接法**那一半，数目那一半归 §5-O (2)。
- **（4）本笔不加「更新记录」行**（按 §5-N / §5-H (3) 先例）。理由同 §5-N：① 加行会让表格欠账从 3 处变 4 处；② `## 更新记录` 在 §1 之前 ⇒ 加一行会使本文件**其后所有行号 +1**。**合并后另起一笔补**（含本笔 PR 号）。
- **（5）成功登录的性能代价：`loginSelect` 成功路径多一次 `SELECT *`。** 本笔为拿**业务号**（`customerid` / `merchantid`）在认证成功后调 `UserService.businessId` → `UserDao.selectUserByUsername`（**`SELECT *` 全列**的第二次往返）。⚠️ 为什么不另写一条只取两列的 SQL：**TD-13**（全仓硬编码 schema 前缀 **39 处**）是**多文档基线**，本笔**不新增带前缀的语句**。代价面：每次**成功**登录 +1 次查询 / +1 次全列传输；**只在登录时**发生，不在请求热路径。本笔**不优化**。
- **（6）两处**既有**漂移（本笔发现、**不是本笔造成**，登记不修）：**
  - `progress.md:59` 与 `defect-survey.md:47` 均写现址 **`UserController.loginAdd:63`** —— 实测**在 HEAD（`661b841`）即已不符**：HEAD 的 `@PostMapping("/loginAdd")` 在 **:72**（`:63` 是 `loginSelect` 的**声明**行）；本笔后为 **:114**。⇒ **在 HEAD 已陈旧**，与本笔无关。
  - `progress.md:253` 引 `api-contract.md:119`（「无事务保护」+ 已失效的体范围）、`progress.md:481` 引 `api-contract.md:135`（四项对账）—— 实测**在 HEAD 即已失效**：HEAD `:119` 是 `B#1` 价格闸行（「无事务保护」一语**在 HEAD 已不存在**，被 2026-10-04 `C#2` 改写），HEAD `:135` 是 `## 5.` 标题（四项对账在 HEAD **:142**）。⇒ 两者均因 `B#1` / `C#2` 之后未同步，与本笔无关。
- **（7）本笔**造成的**行号漂移 3 行 / 4 个引用 —— **已就地重指**（记账供复核）。** 本笔在 `api-contract.md` 的 §2 前部净插入 **19 行**（`### /user` 段前注 +9、`/user` 表 +10）⇒ 指向其后的行号引用集体偏移：`three-layers.md:184`（`:88` → **`:107`**；⚠️ **并顺带改正该行把 §2 写成「§3」** —— `/goods` 的「业务体现址」段在 `## 2. 接口清单` 下的 `### /goods`，不在 §3）、`three-layers.md:188`（`:94` / `:118` → **`:113`** / **`:137`**）、`selection-status.md:172`（`:60` → **`:79`**）。⚠️ 三个文件**均在本笔 6 文件清单内**，不属超范围；另 5 个引用（`db-schema.md:5` / `:6`、`progress.md:471` / `:480`、`:488`）指向 `api-contract.md` 的 `:4` / `:6` / `:9` / `:14` —— 均**在插入点之前**，实测**未漂**，**未动**。
- ⚠️ **本节插入自身的副作用**（按 §5-P (6) 同口径）：本节插在 **§5-P 之后、`## 6` 之前**，共 **15 行** ⇒ 本文件 `## 6` 及其后所有行号**整体后移 15 行**（`## 6`：`477` → `492`）。已核：**仓内已跟踪文档中指向本文件的**数值**引用为空** —— §5-P (3) 已把 7 处外部引用改成节锚点，本文残余的 3 处（`:394` / `:469` / `:470`）是**引文/转换记录**（复述改动前的编号），**不是活引用** ⇒ **没有引用被这次偏移打破**。

### 5-R. 鉴权笔 3（`chore/auth-preflight`，2026-10-05）：**不翻开关** + 真容器受控取证 + 「认证侧未落地」子句订正

> **性质**：**本笔不翻开关、不改行为、不加/改测试**。产出 = ① 一次**真容器**受控取证（**只改启动参数，文件一字未动**）；② 订正 4 处「同一事实」引用点里那句**已过时**的子句；③ 登记 1 条**新的**口径不一致。
> ⚠️ **开关仍在 `false`** —— `application.properties` 的 `app.auth.enabled=false` **一行未动**。**本笔没有翻**，翻是**独立一笔**（见 (1)）。

- **（1）【裁决】本笔不翻；「翻」单列一笔，排在笔 4 之后。三条硬阻断（本笔复核，逐条给了现在的证据）：**
  ① **前端不带 cookie** —— `src/api/http.js:73` 的 `buildInit` **无** `credentials`（`:69` 自认「待鉴权笔填充」）；且前端 dev server 打 `:8088` 是**跨源** ⇒ 翻 `true` = 全站受保护端点 401。
  ② **测试套件红** —— 写死 `true` 实测红 **29** 条（记录在 `application.properties` 那条突变对照注，**笔 1 测的**）。⚠️ **本笔复核：这个数字现在已过时** —— `@WebMvcTest` **只声明在 `AbstractSmokeTest:47`**，其余 smoke 类**全部 `extends` 它** ⇒ 切片对**每一个** smoke 类生效（Spring 测试框架沿父类找注解，非 Java `@Inherited`）。而笔 1 之后又添了 `AuthGateSmokeTest` / `AuthSessionSmokeTest`（各自**两层**）⇒ **翻那一笔必须重测这个数，别直接沿用 29**。
  ③ **A3 全 401** —— CI A3 job 以 `SPRING_PROFILES_ACTIVE=a3` 起真 jar（`ci.yml:209`），`ConcurrencyRunner` 发**真实 HTTP** 打 `orderAdd` 等**非白名单**端点（`ConcurrencyRunner.java:108`）；而 `application-a3.properties` **不设** `app.auth.enabled` ⇒ **继承主配置** ⇒ 主配置一翻，A3 门禁全红，且 `continue-on-error: true` 会这个红**系统性抹平**（同 §5-E / §5-G 的告诫）。
  ⇒ **翻的时机在笔 4（前端接 `credentials` + 401 处理）之后**；且「翻」**单列一笔** —— 它要改 **a3 加显式 `false` 护栏** + **smoke 类适配**（挂会话或加白名单），混进笔 4 会让 diff 伸进 `src/test/`。

- **（2）【受控取证 · 真容器】2026-10-05，`--app.auth.enabled=true` 起 jar（仅改启动参数）。**
  环境：JDK 17 / 本机 MySQL 8.0.32 / **Redis 未起**（`6379` 闭；按设计降级 —— L2 自检 WARN、锁 fail-open，进程照常起，**6.5s**）。端口 **8099**（避开 8088）。13 条逐条实测：

  | # | 请求 | 预期 | 实测 |
  |---|---|---|---|
  | 1 | `POST /goods/goodstype`（白名单）**无** cookie | 200 | **200**，返回商品类型列表 |
  | 2 | `POST /user/updateUserInfo`（非白名单）**无** cookie | 401 | **401** `{"success":false,"msg":"未登录"}` |
  | 3 | `POST /user/me` **无** cookie | 401 | **401**，同体 |
  | 4 | `POST /user/loginSelect`（白名单）`123/123456` | 200 + Set-Cookie | **200** 体 `1`；`Set-Cookie: JSESSIONID=…; Path=/; HttpOnly` |
  | 5 | `POST /user/me` **带该 cookie** | 200 | **200** `{"success":true,"customerid":"pIzTWafaGp","usertype":"客户",…}` |
  | 6 | `POST /user/updateUserInfo` **带 cookie** | 非 401 | **200** `{"msg":"个人信息保存成功","success":true}` |
  | 7 | `POST /user/logout` **带 cookie** | 200 | **200** `{"msg":"已退出","success":true}` |
  | 8 | `POST /user/me` **logout 后**同一 cookie | 401 | **401**，同体（会话确已 `invalidate`） |
  | 9 | 401 响应是否给匿名请求 **mint 会话** | 无 Set-Cookie | **`Set-Cookie` 计数 = 0** |
  | 10 | 401 响应体是否**逐字节**等于常量 | 相同 | **`cmp` IDENTICAL**（byte-for-byte） |
  | 11 | `GET /img/nope.png`（白名单命中、资源不存在） | 404（**不是** 401） | **404** |
  | 12 | `POST /goods/goodstype` 带 `Origin: http://localhost:8080` | 回显 + 允许凭据 | `Access-Control-Allow-Origin: http://localhost:8080` + `Allow-Credentials: true` |
  | 13 | `OPTIONS /user/updateUserInfo` 预检（**非白名单**） | 200（**CorsFilter 先于拦截器**） | **200** + 三条 `Access-Control-*` + `Allow-Credentials: true` |

  ⇒ **[12]/[13] 是给笔 4 的现成结论**：后端 CORS 侧（`ImgConfig` 的 `addAllowedOriginPattern("*")` + `setAllowCredentials(true)`）**已支持跨源带凭据**，且**预检不会被闸拦**。⚠️ 但**浏览器是否真的带上 cookie**（`SameSite` / 跨源定义）**本机 curl 证不了**，笔 4 须**真浏览器实测**。

  ⚠️ **两条附带发现（本笔实测，非本笔造成）**：
  - **(i) 本开关没有启动期生效值打印。** 启动日志里 ASCII `auth` **0 命中**，且 `WebAuthConfig` / `AuthInterceptor` **源码内无任何 logger / `println`** ⇒ 与 `app.lock.*` / `app.cache.*` / `app.order.state.*` 的「启动期打印生效值」惯例**不一致**：**闸开没开只能从行为反推**。⚠️ 因此「启动日志能读到 `app.auth.enabled` 生效值」**当不了验收判据**（除非另补一行自检日志 —— 那属「翻」那一笔）。
  - **(ii) 取证副作用已还原。** 第 6 条**真写库**（改了 `user` 表 `customerid='pIzTWafaGp'` 行的 `email` / `address` / `selfintroduction`）。已按 `mall_management_sql.sql:197` 的种子值用 `UPDATE` **还原并读回核对**。⚠️ 教训：非白名单取证要挑**只读**端点，别顺手挑写端点。

- **（3）【订正】4 处「同一事实」的引用点 —— 过时的是**子句**，不是整句。**
  原句「…**认证侧尚未落地** —— 没有任何端点会把 `AuthInterceptor.SESSION_USER_ID` 写进会话（那是鉴权笔 2）…」的**后半句已过时**（笔 2 已落地写入方：`UserController.loginSelect` → `AuthSession.login`）。
  ⚠️ **「认证侧未生效」本身**（闸没开 ⇒ 认证链路未被启用）**仍是事实、保留** —— 整句删会掩盖「闸还没开」这个状态。
  ⇒ 统一改为「**写入方已在笔 2 落地，但闸仍默认关（认证未生效）**」。落点 **4 处**：`application.properties`（那条「为什么默认关」注释块）、`WebAuthConfig` 类 javadoc、`business-decisions.md`（BD-03 现状行）、`three-layers.md`（`:185` / `:198`）。
  ⚠️ **后两者按 §5-P (1) 的先例做同行替换（`-1 +1` / `-2 +2` 净零）** —— 那两个文件被别处**按行号**引用，增删行会集体偏移。

- **（4）【口径统一】端点数 = **35**（**handler 数口径**，2026-10-05 用户裁决）。⚠️ **订正（2026-10-05，单列笔见 §5-T）：本项原裁 34 作废** —— 成因是把 `goods` 漏数 1 条（原写 8，实为 **9**）。**
  口径：**一个 handler 方法算 1 条，与它声明几种 HTTP 方法无关** —— `@GetMapping` / `@PostMapping` / `@RequestMapping(GET,POST)` / 同名多注解**都算 1 条**。理由：handler 数是**方法数**，稳定；HTTP 方法数会随「一个方法支持几种 HTTP」变，不稳定。⇒ **`/goodstype`（`GoodsController.java:61` 同时声明 `GET,POST`）算 1 条**。
  逐域实测（4 个业务 Controller、去类级 `@RequestMapping`）：`message 10 / order 8 / goods 9 / user 7` = **34**（业务面）；**+ `HealthController./health/db`（`@Profile("a3")`）= 35**。
  ⚠️ **口径差的成因已对齐（2026-10-05 订正；原写「逐笔成因未完成对齐，不臆测」）。** `§5-M` 的「33」与 `§5-P (3)` 的「32」**同口径、当年均准确** —— 那两处逐个数出来的都是 `goods **9** / message 10 / order 8 / **user 5** / health 1`（**`goods 9` 与本项现在的实测一致，反证原写的 `goods 8` 是漏数**）；笔 2 新增 `/user/me` + `/user/logout` ⇒ `user **5 → 7**` ⇒ **33 → 35**（`:480` 已写此式）。⇒ **`§5-Q (2)` 的 35 从来是对的，本项原来的 34 才是错的**；原假设的「`/goodstype` 是否按 HTTP 方法拆成 2 条」**不是成因，作废**。⚠️ `§5-M` 与 `§5-P (3)` 的数字**按其字面保留**（已落盘、属历史；`:480` 已登记 §5-M 那句应改为 user 7 / 合计 35）⇒ **引用时一律以 handler 数 = 35 为准**。
  落点：本节 + `business-decisions.md`（BD-03 现状行）+ `three-layers.md`（`:185` / `:198`）—— 共 **4 处**已统一为 **35**（后 3 处为**同行替换、净零**）。

---

### 5-S. 鉴权笔 4（`feat/auth-frontend`，2026-10-05）：**前端接上**（`credentials` + 401 出口）—— 登记 **6 条漂移** + **1 条新发现** + **1 条翻笔前置**

> **性质**：**不翻开关**（`app.auth.enabled` 一行未动）。产出 = 前端两件事：① `credentials:'include'`；② **401 出口**（清态 + 跳转 + 风暴防护）。
> ⚠️ **会话恢复 + `currentUser` 缓存**已按用户裁决**切走**（不是漏做）⇒ `/user/me` **至今仍无调用方**，故 `src/api/endpoints.js` **本笔不动**。
> ⚠️ **`/user/logout` 本笔不接**（登记为**翻笔前置**，见 (4)）。

- **（1）【裁决·用户】原裁「34」作废，正确值 = **35**。** 本笔复核：`goods` 的 handler 是 **9**（`goodstype` / `goodsAdd` / `goodsSelect` / `goodsSelectHome` / `goodsDe` / `productCodePrice` / `goodsUp` / `GoodsProductCodeSelect` / `productSearch`），**不是 8** ⇒ `message 10 / order 8 / goods 9 / user 7` = **34** 业务 + `HealthController./health/db` = **35**。
  旁证：`api-contract.md:18`「共 **35** 个」+ `:81`「/goods — **9** 个」，与该表 9 行**逐行对得上**；§5 的四项对账「35 − 31 = 4」亦自洽。
  ⇒ **`§5-R (4)` 与 3 个落点（`business-decisions.md:46`、`three-layers.md:185` / `:198`）仍写 34，是错的**。**订正单列一笔**（纯文档），**本笔不夹带** ⇒ ⚠️ **已由 §5-T 落地（2026-10-05）**。
  ⚠️ **一个会同名误认的数**：`http.js` 头注释与 §6-A P2.4 里的 **`34`** 指 **`.success` 的判定处数**（§6-A P2.4 行口径 = `.success` **34 处**真实判定），与端点数**无关**。引用「34」前先确认是哪一个。

- **（2）【本笔落地】代码 3 处 + 文档 2 处，共 5 文件。**
  - `src/api/http.js`：`buildInit` 加 `credentials:'include'`（**一行覆盖 42/42** —— 全 `src/` **只有本文件一处 `fetch(`**，42 处调用全经 `http.post/get`，含 3 处「变量 URL」）；新增 `UnauthorizedError extends HttpError` + `setUnauthorizedHandler` / `resetUnauthorizedLatch` / `handleUnauthorized`；`handleApiError` 对 `UnauthorizedError` **静默**（先判子类再判 `HttpError`）。
  - `src/api/auth.js`：新增 **`clear()`**（清凭据 + `merchantid` + `customerid`）。
  - `src/main.js`：注册 `setUnauthorizedHandler(() => router.push('/login'))` + `router.afterEach(() => resetUnauthorizedLatch())`。
  - `api-contract.md:166`：**同行替换（净零）** ——「`main.js` 无拦截器、无全局配置」订正为「已注册 401 出口、仍无 HTTP 拦截器」。
  - `progress.md`：本节。
  ⚠️ **三条「为什么不那样做」的理由，一并记账：**
  - **401 判 `status` 不判 `body`** —— 闸 401 与 `/user/me` 401 的响应体**逐字节相同**（§5-R (2) 第 10 条 `cmp` IDENTICAL）⇒ 判 body 等于判「字符串相等」；且后端明写 `Result` 键序**不承诺**（`UserController.java:219`）。
  - **跳转走回调注册，不 `import router`** —— `router/index.js → views/* → api/http.js` 会成环；回调也让「跳哪」只写在 `main.js` **一处**（http.js 不硬编码 `#/login`，路由路径改了不会坏）。
  - **闩锁复位挂「路由变化」而非「任一 2xx」** —— 闸开时白名单端点（如 `goodsSelectHome`）照常 2xx，挂 2xx 会让闩锁被反复**误复位**、等于没有。

- **（3）【⚠️ 本笔最重要的诚实声明】401 出口在笔 4 期间**不可达 ⇒ **未验**。**
  闸默认关 ⇒ 唯一的 401 来源本是 `/user/me`，而它**本笔无调用方**（会话恢复已切走）⇒ **`UnauthorizedError` 这条分支在默认配置下一次都不会触发**。
  验收因此**只剩**：`npm run build` 通过 + 5 条 grep 判据 + 突变对照 A（撤 `credentials` ⇒ 手测 B3/B4/B6 变红）。**闩锁与 `clear()` 的实际行为，只能到「翻」那一笔才验得到**（原先的 B12 已由用户裁归翻笔）。
  ⇒ **不许**把「401 出口已验」写进任何结论。

- **（4）【翻笔前置登记】前端「退出」不调 `/user/logout`。**
  实测全 `src/` **0 调用** —— 「退出」只清 sessionStorage（`CustomerPage.vue:112` / `MerchantPage.vue:131-132`）。
  ⚠️ **闸一开这就成为真的没登出**：cookie 仍在，直开 `#/customer` 照样取到数据。⇒ **「退出调 `/user/logout`」列为翻笔前置**（翻笔本来就要动测试与 a3，那是它的时点）；并进笔 4 要改 `CustomerPage` + `MerchantPage` ⇒ **+2 文件、破 5**，故本笔不做。

- **（5）【漂移登记 · 本笔不修】** 除 (1) 外另 **5** 条：
  1. **`getUserInfo` 的消费点是 3，不是 5。** 实测 `grep -rn "getUserInfo" src/` = `LoginPage.vue:141` / `UserInfoPage.vue:99` / `GoodsUp.vue:153`（另 2 处是 `GoodsUp` 的本地函数名、1 处是 `endpoints.js:26`）。**三份独立旁证同为 3**：`UserController.java:133` javadoc「前端**三个**调用点」、§6-A P1.2「消费点 **3**」、`api-contract.md:171` 只列 3 个文件。⚠️ 「5」**最可能**来自 `endpoints.js` 的 `user:` 块恰好 5 条（**推断档，未证**）。
  2. **§6-A P1.2 的行号过时**：写 `LoginPage.vue:150` / `UserInfoPage.vue:101` / `GoodsUp.vue:155` → 实测 **`:141` / `:99` / `:153`**（成因 = `#48` 前端笔挪行）。
  3. **`api-contract.md:171` 同病 + §5 全表停摆**：写 `LoginPage.vue:142` / `GoodsUp.vue:150` / `UserInfoPage.vue:96` → 实测 **`:141` / `:153` / `:99`**；且 §5 矩阵全表行号停在 **2026-09-30**（`#48` 之前），此后**未重测**。
  4. **§5-J (4) 与 §6-B P2.6 的行号过时**：写 `LoginPage.vue:163-169`（写）/ `GoodsUp.vue:145-146`（读）/ `UserInfoPage.vue:91-92`（读）→ 实测写 **`LoginPage.vue:148-153` + `:158` / `:162`**、读 **`GoodsUp.vue:148-149`** / **`UserInfoPage.vue:94-95`**。
  5. **§6-A P1.1「`res.ok` / `res.status` / `.statusText` 全 `src` 0 命中」在 HEAD 即已失效** —— HEAD 的 `http.js` 已有 `if (!res.ok)`（HEAD `:97`）与 `res.status`（HEAD `:104`），**本笔后**分别位于 **`:151` / `:157`**（本笔又新增了 `res.status === 401`）。成因：`#48` 落地了 P1.1 却未回填本节。

- **（6）【新发现 · 未核影响】** `ChatRoom.vue:133` 用 `window.location.pathname` 判路由（`path.includes('/merchant')` / `('/customer')`）—— **hash 路由下 `pathname` 恒为 `/`** ⇒ 两个分支**恒不命中**，实际总走兜底链（`merchantid` → `customerid`）。⚠️ **影响未核**（只有当某用户**同时**持有两个 id 且站在客户页时才可能给出不同结果）。

---

### 5-T. 端点汇总数订正笔（`docs/endpoint-count-35`，2026-10-05）：**34 → 35**（纯文档）—— 结清 §5-S (1) 的挂账

> **性质**：**纯文档**。不动代码 / 测试 / 前端 / `ci.yml` / A3 脚本 / `application*.properties`。**3 个文件**。
> **结清对象**：§5-S (1) 把「`§5-R (4)` 与 3 个落点仍写 34」登记为「**订正单列一笔**」—— 本笔就是那一笔。

- **（1）改了什么。** 端点数 **34 → 35**（= **34 业务 + 1 health**）；**口径不变**（**handler 数**：一个 handler 方法算 1 条，与它声明几种 HTTP 方法无关 ⇒ `/goodstype`（`@RequestMapping(GET,POST)`）算 **1** 条）。**4 个落点**：
  - `progress.md` `§5-R (4)`（**就地改为 35，并在原文内留下 ⚠️ 订正痕迹**，理由见 (2)）
  - `business-decisions.md:46`、`three-layers.md:185` / `:198` —— **同行替换（净零）**：这 3 行被 `progress.md:460` 与 §5-S (1) **按行号引用**，增删行会集体偏移。
- **（2）为什么不照 `§5-R (4)` 自己的规矩「只标注、不改其字面」。** 那条规矩针对的是**历史记录**（`§5-M` / `§5-P (3)`，当年准确）；而 `§5-R (4)` 写的是**现行裁决** —— 改动前的字面是「**一律以 handler 数 = 34 为准**」。若对它也只标注，同一份文档里就同时存在「以 34 为准」和 3 个落点的 35。⇒ 取「**改成 35 + 原文内留痕**」两全。
- **（3）成因 —— `§5-R (4)` 原写「未完成对齐、不臆测」，本笔结清。** 四个数**同口径**，差别只在**测量时点**与**一处漏数**：

  | 记录 | 时点 | goods | user | health | 合计 |
  |---|---|---|---|---|---|
  | `§5-M` | 2026-10-04 | **9** | 5 | 1 | **33** |
  | `§5-P (3)` | 2026-10-04 | **9** | 5 | 1 | **33**（业务面 32） |
  | `§5-Q (2)` | 笔 2 之后 | 9 | **7** | 1 | **35** |
  | `§5-R (4)` | 2026-10-05 | ~~8~~ ⇒ **9** | 7 | 1 | ~~34~~ ⇒ **35** |

  ⇒ 唯一的变化量是 **笔 2 新增 `/user/me` + `/user/logout`**（`user 5 → 7`；`:480` 已写「端点数 **33 → 35**」）；**`§5-R (4)` 的 `goods 8` 是一处纯漏数**（`§5-M` 与 `§5-P (3)` **各自**独立数出 `goods 9`）。
  ⚠️ **原假设「`/goodstype` 是否按 HTTP 方法拆成 2 条」经此排除** —— 那两处用的都是「一 handler 一条」的口径，`/goodstype` 从来算 1。
- **（4）旁证 4 条（互相独立）**：① `§5-M`「逐个数 `@*Mapping`」记 goods **9**；② `§5-P (3)` 标「**2026-10-04 核实准确**」，同记 goods **9**；③ 本笔复核 `GoodsController` 的 **9 个 handler**（`goodstype` / `goodsAdd` / `goodsSelect` / `goodsSelectHome` / `goodsDe` / `productCodePrice` / `goodsUp` / `GoodsProductCodeSelect` / `productSearch`）；④ `api-contract.md:18`「共 **35** 个」+ `:81`「/goods — **9** 个」，与该表 9 行逐行对得上。
- **（5）本笔不修、登记（四处同源拷贝 + 一条老欠账）。** 这四处都还写着 2026-10-04（或更早）的 **user 5 / 合计 32-33**，**按字面保留**（都是**带日期的历史记录**，当时准确）：
  - `§5-M` 的「端点数 **33** 已核实（… / **user 5**）」与 `§5-P (3)` 的「32 / 33」；`§5-Q (2)` 已登记前者应改为「user 7 / 合计 35」。
  - `three-layers.md:7` 的「用户域 **5** 个端点的业务体…下沉」（**2026-10-02** 修订注）。
  - `api-contract.md:21`「`Result` 类注释的 33 两处口径已过时」—— **仍成立**（`Result.java` 字面未改），不属本笔。
  - ⚠️ 另有**一条老欠账与本笔无关**：`§5-M` 本身（`HealthController.java:42` 的「其余 33 处」vs `Result.java:10` 的「33 个端点」对不上）**仍挂着**。
  - ✅ 反面对照（**不属**这四处、已是对的）：`api-contract.md:18`「共 **35** 个」、`:81`「/goods — **9** 个」、`:7`「**9** 个端点的路由仍在 `GoodsController`」。
- **（6）行号副作用。** 本节插在 §5-S 之后、`## 6` 之前；`§5-R (4)` 就地增 1 行、`:535` 由 1 行改写为 1 行（净零）。已核：**仓内指向本文件的数值引用为空**（§5-P (3) 已把 7 处外部引用改成节锚点；残余 `:394` / `:469` / `:470` / `:485`-`:487` 均为**引文/转换记录**，不是活引用）⇒ **无引用被这次偏移打破**。`§5-R (4)` 与本节里新写的 `:480` / `:460` 都指向**上方**，未漂。
- **（7）不加「更新记录」行**（按 §5-H (3) / §5-N / §5-Q (4) 先例）：① 加行会让表格欠账从 **3 处变 4 处**；② `## 更新记录` 在 §1 之前 ⇒ 加一行使本文件其后行号 **+1**。

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
