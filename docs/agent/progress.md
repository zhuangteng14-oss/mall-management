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
- ⚠️ **文档悬空引用 2 条（2026-10-05 挂账；归属笔名 = `P2.6` 那笔，备选界面优化笔）**：① `progress.md:401` 引的 `HomePage.vue:116`（原文「同行两键」）**已悬空** —— 笔 3/8（`6639c1f`）把该文件的 `loginUsername` / `merchantid` **两键整体换掉**（现为 `:118` 注释 + `:119` 的 `isLogin = computed(...)`）⇒ 该文件现在**零处**凭据键；② `api/auth.js:118` 引的 `ShoppingCart.vue:88-93` / `HistoricalOrders.vue:36-41`（原文「收敛**逐字相同**的 2 处降级链」）**已悬空** —— 那两段现在只剩 `getBuyerId = () => resolveBuyerId()`。**两条都不是漂移，是悬空**（§5-W (5) 当时按「回放行内容」核，没核「这行还该不该有这个键」）。⚠️ ② 是 **R4 核实中新发现**。**为何不修**：两条都要重算 54/52 的逐出现口径（§5-I / §5-J 的记账结论）。**成因 / 归属 / 完整判据见 §5-Z (9) 第 5 条（① = 第 5 条本体、② = 其第 6 点）。**

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
  实测全 `src/` **0 调用** —— 「退出」只清 sessionStorage（`UserCenter.vue:37` / `MerchantPage.vue:131-132`）。⚠️ **按语（§5-X，2026-10-05）**：客户侧退出点 R1 后由 `CustomerPage.vue:112` **迁到 `UserCenter.vue:37`** —— 用户中心从 `CustomerPage` 的内联 markup 提成独立组件，`toLogin()` 随 markup 搬家（**换文件，不只是换行号**）；`MerchantPage.vue:131-132` **未动**。⚠️ **再按语（§5-Y，R2，2026-10-05）**：`MerchantPage.vue:131-132` **R2 后也不存在了**（商家壳路由化 ⇒ 商家退出点同样迁到 `UserCenter.vue`）⇒ 本句这两个行号**都已失效**；现行唯一退出点是 **`UserCenter.vue:52-55`**（同一函数两分支）。「退出只清 sessionStorage、不调 `/user/logout`」这个**事实**未变，只是落点从两处并为一处。
  ⚠️ **闸一开这就成为真的没登出**：cookie 仍在，直开 `#/customer` 照样取到数据。⇒ **「退出调 `/user/logout`」列为翻笔前置**（翻笔本来就要动测试与 a3，那是它的时点）；并进笔 4 要改 `CustomerPage` + `MerchantPage` ⇒ **+2 文件、破 5**，故本笔不做。⚠️ **按语（§5-Y，R2，2026-10-05）**：本条的**前提**已变 —— R1/R2 之后两个壳**都没有**自己的退出了（客户 R1、商家 R2 都迁到共用的 `UserCenter.vue`）⇒ 若「退出调 `/user/logout`」要落，落点是 **`UserCenter.vue:52-55` 那**一处**（不是两个壳）⇒ 文件数估计从「+2」降到 **+1**。本条描述的「退出只清 sessionStorage」在 **R2 后仍然成立**（行为未变，只是落点集中了）。

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

### 5-U. 会话恢复笔（`feat/auth-restore`，2026-10-05）：新标签页 / 存储被清后**仍认得你** —— 启动探针 + 401 **单次**旁路

> **性质**：前端 4 文件 / **0 新文件**。不动后端、不动 `ci.yml`、不动 A3 脚本、不动 `src/test/**`、不动 `src/views/**`、不动 8 键页面状态。
> **来源**：笔 4 切走的 **Q4 块**独立成笔（`§5-S (2)` 登记过）。
> ⚠️ **价值面只有后两档是本笔新增的**：① **同一标签页刷新** —— `sessionStorage` 按标签页存活，**改前就不丢**，本笔不加价值；② **新标签页** ③ **存储被清** —— 改前认不得，本笔靠 `POST /user/me`（读 **Cookie 会话**）恢复。

- **（1）改动 4 文件（不是方案里写的 3 —— 漏算了 `endpoints.js`）**：
  1. `src/api/endpoints.js`：`user:` 段新增 `me: '/user/me'`。⚠️ 该文件自称「全仓**唯一**允许出现后端路径字面量的文件」⇒ 探针**不能**硬编码路径，否则又添一处漂移。头注释那句「**31 个唯一端点**」按**同行替换**改写（它是 **view 抽取**口径，`me` **不是** view 抽取的）—— ⚠️ 这里**增删行会让 `endpoints.js:26` 漂移**，而 `progress.md:572` 正按行号引它。
  2. `src/api/auth.js`：新增模块级 `restored` + `restoreFromSession(info)`；4 个读口改走 `pick()`。⚠️ **只存内存、不落 `sessionStorage`**（BD-03 选 A 的方向：凭据走 Cookie、前端不持有身份）。⚠️ `restoreFromSession` **只张贴、不取数** —— 本文件**零 import** 是成文前提（`http.js → auth.js` 这条边已存在，反向加边即真环）。
  3. `src/api/http.js`：401 出口加**单次调用级**旁路 `{ allowUnauthorized: true }`（**不做**全局开关）。
  4. `src/main.js`：`bootstrap()` 中 `await restoreIdentity()`，**排在 `app.use(router)` 之前**。

- **（2）⚠️ 本笔最关键的坑（已解）**：`/user/me` **不在** `AUTH_WHITELIST`（`WebAuthConfig.java:97-113` 共 12 条，无它）⇒ 匿名访客**注定**收到 401 ⇒ 若照常走笔 4 的出口，**谁都没登录时一进站就被踢到 `/login`**（首页本是**公开页**）= 回归。故旁路为必需项，且旁路时**不置闩锁**（连 `handleUnauthorized` 都不进）。**判据 = 手测 T3**；突变对照 **B** = 撤 `allowUnauthorized` ⇒ T3 必变红。

- **（3）读口顺序的实现对裁决**收严**了（请追认）**：裁决（本笔方案 §3.1）是「6 个写口全部**同步**缓存」。本笔实现为 6 个写口一律 `dropRestored()`（**整体作废**），而非逐字段同步。理由：本模块写口**只有两类调用方** —— 登录（`LoginPage.vue`）与退出（`CustomerPage` / `MerchantPage` / `http.js` 的 401 出口），两者都是**身份变更**。丢弃给出可证不变量「**任何一次写之后，读口 ≡ 笔前行为**」；逐字段同步反而留过期字段：新标签页恢复成商家 Y 之后再登录成客户 B、而 B 的 `merchantid` 为空（`LoginPage.vue:148` 的 `if` 跳过写）⇒ `restored.merchantId` 留着 Y、存储里是空的、「优先」就把 Y 读出来。**「优先」只在恢复窗口内生效**（窗口 = 启动到第一次本地写）。

- **（4）恢复不了 / 已知残留（如实登记，均**非**回归）**：
  - **password 恢复不了**：`/user/me` 只答 5 列（`customerid / merchantid / username / usertype / gender`）⇒ `GoodsUp.vue:148-149` 与 `UserInfoPage.vue:94-95`（拿密码做**真凭据校验**）在新标签页**仍失效**。⚠️ 改前新标签页的 `sessionStorage` 整个是空的，本来就取不到密码 ⇒ 本笔只是**没有把它们变好**。P2.6 的落点。
  - **占位串会被当成真 id**：恢复字段判据与 `LoginPage.vue:148-153` **逐字对齐**（`if (x)` 只写真值，不加过滤）⇒ `loginAdd` 的默认值 `'暂无数据'` 会作为 `merchantid` 被恢复，恢复窗口内 `ProductPage` 的 `isMerchant` 判定会当成商家。⚠️ **不是本笔引入的新行为** —— 正常登录后 `LoginPage` 也把同一个值写进存储，改前同一条分支走到这里。**不修**（修 = 改 `LoginPage` 行为，不属本笔）。
  - **服务端会话已过期而本地存储还在**时不打探针（`isLoggedIn()` 为真直接返回）⇒ 要等某个业务请求 401 才发现。**既有**行为。
  - **匿名访客每次开页面多一次请求**：一次 401、无 DB 查询（`getSession(false)` 即返回）；后端不可用时至多白屏 **3000ms**（探针显式覆盖默认的 10s），仍 fail-open。

- **（5）漂移登记**：
  - ✅ **本笔自查逮到并已修**一处**自伤漂移**：`endpoints.js` 头注释先被写成 **3 行**（原 2 行）⇒ 该文件行号整体 **+1**，`endpoints.js:26`（被 `progress.md:572` 引用）从 `getUserInfo` 变成 `loginAdd`；**改回净零**后 `:26` 复原为 `getUserInfo`（实测 diff：该文件只在 `:7` 同行替换 + `:29` 起插 3 行）。
  - ✅ `src/api/auth.js:3-8`（被 `progress.md:83` / `:402` 引用）**逐字未动**（实测与 `HEAD` 比对 IDENTICAL）。
  - ⚠️ `progress.md:496` 引的 `src/api/http.js:73` / `:69` **在本笔之前**即已失效（笔 4 之后 `buildInit` 在 `:127`）；本笔后位于 **`:131`**。那处是**历史引文**（描述笔 4 前的状态），**不改**。
  - ⚠️ **本笔新增一处失效**：`progress.md:572` 的「「5」最可能来自 `endpoints.js` 的 `user:` 块**恰好 5 条**」—— 本笔加了 `me` 后该块是 **6** 条。属**历史引文**（原已标「推断档，未证」），**不改**，在此登记。
  - ⚠️ `src/main.js` 由「**无**尾换行」（笔 4 披露过）变为**有**尾换行；工作区 CRLF 警告一并披露（`core.autocrlf=true`，入库仍是 LF）。
  - ✅ 指向本文件的数值引用：本笔只在 **§5-T 之后**追加，`## 6` 及其后整体下移，但**仓内无指向 `progress.md` 的活数值引用**（承 §5-P (3) / §5-T (6) 的结论）⇒ 无引用被打断。

- **（6）验证**：`npm run build` 通过（**无** `export 'http' was not found` 那条 warning —— 即 `http.js` 的命名空间出口没被改坏）；`grep -rn "fetch(" src/` 仍**只有一处代码行**（`http.js` 的调用行，探针**没绕过**传输层）；`/user/me` 字面量**只**在 `endpoints.js`；`dist` 里 `allowUnauthorized:!0` 与 `"/user/me"` 均留存（**未被 tree-shake**），`credentials:"include"` 仍在。手测 **T1–T4 + 突变对照 B** 见 PR 描述 —— **留给用户逐条跑，不代跑**。

- **（7）不加「更新记录」行**（照 `§5-H (3)` / §5-N / §5-Q (4) / `§5-T (7)` 先例）。

---

### 5-V. 首页落点止血笔（`fix/home-landing`，2026-10-05）：`/` 加守卫按 `role` 分流 —— 并把 `role` 存下来

> **性质**：前端 **3 代码文件 + 1 文档** / **0 新文件**。不动后端、不动 `ci.yml`、不动 A3 脚本、不动 `src/test/**`、不动其他前端文件。
> **来源**：`/` 在 `router/index.js:35`（**R1 重指**；原记 `:12`）**裸挂** `HomePage` ⇒ **无外壳 ⇒ 无导航栏**；未登录时还有 `.lock-mask` 引导登录（`HomePage.vue:4`）+ 三条守卫（`:135` / `:144` / `:165`），而**已登录时遮罩不渲染、三条守卫也不 return** ⇒ **卡在裸首页**。⚠️ **按语（§5-W，2026-10-05）**：`.lock-mask` 已改为 `.login-hint-bar`（同为 `HomePage.vue:4`，**行号未漂**、仅内容变）；本句另 3 个行号（`:135` / `:144` / `:165`）**仍对**。⚠️ **按语（§5-X，2026-10-05）**：`router/index.js` R1 后 **55 → 123 行** ⇒ 本句的 `/` 记录由 `:12`（当时是 `const routes = [` 那行，记录本身在 `:13`）移到 **`:35`**；**「裸挂」这个事实 R1 没有改**（`/` 落点收口是 R3 的活）。⚠️ **再按语（§5-Y，R2，2026-10-05）**：`router/index.js` 又 **123 → 201 行** ⇒ 该 `/` 记录（`{ path: '/', component: HomePage }`）现实测在 **`:52`**（`const routes = [` 在 `:51`）。**「裸挂」这个事实 R2 仍未改** —— 两笔都只动 `/customer` 与 `/merchant` 的子树。⚠️ **三次按语（§5-Z，R3，2026-10-05）**：**「裸挂」这个事实已在 R3 终结** —— `{ path: '/', component: HomePage }` 换成 `{ path: '/', redirect: landingFor }`；**本句引用的 `:52` 因此未漂**（R3 刻意净零：`const routes = [` 仍在 `:51`，`export default` 由 `:201` 挪到 `:202`）。`/` 从此**不再是页面、只是落点**，且**未登录也落 `/customer`**（原为「停在裸页」）。逐条见 §5-Z (2)/(4)。
> **笔 5（§5-U）的角色**：它把「服务端已登录、前端不知道」（`JSESSIONID` 是 `HttpOnly`）这个**隐性状态**变**显性** ⇒ 遮罩消失、唯一出口关闭 ⇒ **放大器**，不是实现错误。**主因是首页设计债**（`/` 裸挂、`emit('go-search')` 无人接）。
> ⚠️ **本笔只是止血，不是「已解决」**：`/` **仍**裸挂 `HomePage`、仍无导航栏；标准布局重构另起（R1–R4，`§十一` 分笔建议已出）。

- **（1）改动 4 文件（3 代码 + 1 文档）**：
  1. `src/api/auth.js`：`K.role = 'role'` + `getRole()`（与 `getMerchantId` 同形：恢复值优先、存储兜底）+ `setRole(v)`（写口 + `dropRestored()`）。
  2. `src/views/LoginPage.vue`：`:158` / `:162` **同行**追加 `setRole(role)` —— **净零行数**（470 → 470），保 `api-contract.md:172` 引的 `:216` 不漂移。
  3. `src/router/index.js`：`import { isLoggedIn, getRole, getMerchantId }` + `const ROLE_HOME = { '1': '/customer', '2': '/merchant' }` + `router.beforeEach`（只守 `/`；未登录放行；已登录 `ROLE_HOME[getRole()] || (getMerchantId() ? '/merchant' : '/customer')`）。
  4. `docs/agent/progress.md`：本节。

- **（2）⚠️ 行为变更：`clearCredentials()` 多清一个键 `role`**：原只 `removeItem` `username` / `password`，现加 `role`。理由：退出登录语义上就该清角色。**影响面 = 两个退出点** —— `UserCenter.vue:37` 与 `MerchantPage.vue:131`。⚠️ **按语（§5-X，2026-10-05）**：前者 R1 后由此前记的 `CustomerPage.vue:112` 迁来（换文件）；后者未动。⚠️ **再按语（§5-Y，R2，2026-10-05）**：本句的**两个**行号均已失效 —— 现状是**一个**退出点（同一函数两分支）：`UserCenter.vue:52-55`（客户分支只清凭据、商家分支 `meta.logout === 'merchant'` 另清 `merchantid`）。⚠️ **行为**（`clearCredentials()` 多清 `role`）**没变**，变的是**退出点的数量与位置**。⚠️ `clear()`（401 出口）**不重复**清 `role`：它第一行就调 `clearCredentials()`，已覆盖。

- **（3）⚠️ 写口 6 → 7**：新增 `setRole` 后，`auth.js` 头注释与 §5-U (3) 记的「**6 个写口**一律 `dropRestored()`」变为 **7 个**。头注释已**行内订正**（不引发行移），此处登记。

- **（4）漂移登记**：
  - ⚠️ **本笔新增一处内容变更**：`src/api/auth.js:3-8`（被 `progress.md:83` / `:402` / `:638` 引用）**内容改了** —— 「登录态 **4 键** / 54 处」→「**5 键** / 54 处」，并注明第 5 键 `role` 为**本笔新增、基线 `9add994` 时不入存储 ⇒ 不在 54 内**。**行数未变**（`:3-8` 仍在 `:3-8`）。
  - ✅ **`progress.md:638` 那句「与 `HEAD` 比对 IDENTICAL」仍为真** —— 它是**笔 5 的历史陈述**（描述笔 5 当时未动 `:3-8`），本笔的改动不回溯它。**不改**。
  - ✅ `router/index.js` **全仓无任何 inbound「文件:行号」引用** ⇒ 零漂移；其尾换行与 `HEAD` **一致**（都无）⇒ 非新披露。
  - ✅ `LoginPage.vue` **净零**（470 → 470）⇒ `api-contract.md:170/171/172` 不破。
  - ✅ `auth.js` 在 `K` 之下整体 **+20 行**：`auth.js` 自引用注释（指 `LoginPage.vue:148-153` / `:163-169`）**只是移位、不失效**（它们指 `LoginPage`，不指本文件）。全仓引 `auth.js` 行号处**只有 `:3-8`**（实测），**未被打断**。
  - ✅ 指向本文件的数值引用：本笔只在 **§5-U 之后**追加，`## 6` 及其后整体下移，但**仓内无指向 `progress.md` 的活数值引用**（承 §5-P (3) / §5-T (6) / §5-U (5) 的结论）⇒ 无引用被打断。

- **（5）⚠️ C3（管理员）缺口 —— 两处，都不是本笔能关上的**：
  - **守卫本身改一行即可**：`ROLE_HOME` 是**唯一**的角色→落地页映射表。C3 加管理员时**只在本表加一行** `'3': '/admin'`（另需**后端支持** + 前端加 `#/admin` 路由）。⚠️ **不是「守卫不用改」** —— `/admin` 本笔还不存在，那一行必须加；但改动被压到 **1 处 1 行**（而非散落 5 处）。
  - **⚠️ C3 仍要动后端**：把 `role` 加进 `/user/me` 投影。否则**管理员开新标签页拿不到 `role`**（`/user/me` 现只答 `usertype` —— 中文串「客户/商家」—— **不含 `role`**）⇒ 走 `getMerchantId()` 兜底 ⇒ **落到 `/customer`（管理员无 `merchantid`）= 错页**。**本笔不动后端**（止血保持小）。
  - 本笔**刻意不从 `usertype` 反推 `role`**：那会让 `role` 有第二个来源（**口径分叉**），且要动笔 5 的 `restoreFromSession`，管理员（`usertype` 未知）仍映射不出来。

- **（6）⚠️ 时序耦合（本守卫正确性的唯一前提）**：守卫读的是 `auth.js` 的**恢复态**。`main.js` 的顺序是 `await restoreIdentity()` ⇒ `app.use(router)`（**初始导航由此触发**），而本文件在 `main.js` 顶部被 import ⇒ 守卫在**模块加载时**注册，必然早于 `app.use(router)`。⚠️ **若将来有人把恢复挪到 `app.use(router)` 之后** ⇒ 守卫读到「匿名」⇒ 不重定向 ⇒ **首页再度卡死（不报错、不崩，最难查）**。已写进 `router/index.js` 守卫上方注释。

- **（7）验证**：`npm run build` 通过（**无** `export 'xxx' was not found` 那条；仅 2 条**既有**体积警告）；`dist` 里 `beforeEach` **1 次** / `role` **6 次** ⇒ 接线**未被 tree-shake**；环检查 `router → api/auth` **不成环**（`grep -c "^import" src/api/auth.js` = **0**）。手测 **T1–T7** 见 PR 描述 —— **留给用户逐条跑，不代跑**。

- **（8）不加「更新记录」行**（照 `§5-H (3)` / `§5-N` / `§5-Q (4)` / `§5-T (7)` 先例）。

---

### 5-W. 首页锁改提示条（`fix/home-unlock`，2026-10-05）：整页遮罩 → 不挡点击的登录提示条

> **性质**：前端 **2 代码文件 + 1 文档** / **0 新文件**。不动后端、不动 `ci.yml`、不动 A3 脚本、不动 `src/test/**`、不动 `CustomerPage.vue` / `MerchantPage.vue`。⚠️ **按语（§5-Y，R2，2026-10-05）**：这两条壳文件后来**都被改过**（`CustomerPage.vue` 被 R1、`MerchantPage.vue` 被 R2）—— 「不动」是**§5-W 本笔**的边界，不是永久约束。⚠️ `router/index.js` 本笔**只改了一处注释措辞**（见 (5)②），**未做任何路由结构改动**（R1 / R3 的活）。
> **来源**：§5-V 只治了「**已登录**在 `#/` 卡死」。**未登录**那一半没治 —— `HomePage.vue:4` 的整页遮罩（`.lock-mask`，原 CSS `:225-233`：`position:absolute; z-index:99; width:100vw; height:100%`）把**整个首页**盖住 ⇒ 搜索框连聚焦都做不到、所有点击一律变成开弹窗 = **什么都点不了**。而**商品页本来就是「默认让看、只在购买/咨询弹提示」**（`ProductPage.vue:311-314` / `:408-412`）⇒ **首页的整页锁在全项目是孤例**。
> **判据面**：未登录能看到的首页数据全走**白名单**端点（`WebAuthConfig.java:103-107`：`goodstype` / `goodsSelectHome` / `GoodsProductCodeSelect` / `productCodePrice` / `productSearch`）⇒ **服务器侧本来就支持匿名浏览，本笔不需要动后端**。

- **（1）改动 3 文件（2 代码 + 1 文档）**：
  1. `src/views/HomePage.vue`：`:4` **一行换一行** —— `.lock-mask`（`@click="showLoginModal = true"`）→ `.login-hint-bar`（`@click="router.push('/login')"`，文案「未登录 · 点此登录后可搜索 / 购买」）；原 `.lock-mask` CSS 块（`:225-233`，**9 行**）**原地改写**为 `.login-hint-bar`（**同为 9 行**：`width:100%; padding:10px 16px; background:#fff8e1; color:#8a6d3b; font-size:13px; text-align:center; cursor:pointer`）。
  2. `src/router/index.js`：`:29-31` 守卫注释**同行换名**（`.lock-mask` → `.login-hint-bar`、「遮罩」→「提示条」）+ 追加一句 `§5-W` 指针 —— **3 行换 3 行，净零，语义不变**。⚠️ 这是**本笔改动的直接下游**（同一事实的引用点），不修就是**留假话**（同 `#11` / `#42` / TD-01 口径）⇒ 用户批准确认，破「2 文件」不破 `≤5`。
  3. `docs/agent/progress.md`：本节 + §5-V 源句按语。

- **（2）⚠️ 行为变更（本笔唯一的行为变化）**：**未登录从「整页点不动」变为「可正常浏览」**（内容可见、可滚动、顶部一条可点的登录提示条）。
  - ⚠️ **登录弹窗少了一条触发路径**：改前点遮罩 = `showLoginModal = true`（先弹窗，再点窗内「去登录」）；改后点提示条 = **直达 `/login`**。⚠️ 弹窗**本身仍在**（三条守卫照旧触发它），只是**不再有「点整页任意处即弹窗」这条路径** —— 因为那正是被去掉的整页遮罩。
  - **操作拦截一个字没改**：三条守卫（`goSearch :135` / `searchByType :144` / `toGoodsDetail :165`）**全部保留** ⇒ 未登录点搜索 / 分类 / 商品卡**仍然弹「请先登录以使用功能」**。
  - **已登录路径逐字不变**：遮罩 / 提示条都带 `v-if="!isLogin"` ⇒ 已登录时两者**都不渲染**。
  - ✅ **定位风险已排查**：新增的顶部条是 `.home-page`（`display:flex; flex-direction:column`）的**首个子项** ⇒ 只把后续区**整体下移**，不影响绝对定位元素 —— `.swiper-img` / `.dots` 的定位祖先仍是 `.swiper-container` / `.swiper-box`（均 `position:relative`，未变），`.login-modal` 是 `100vh` 全屏浮层（`z-index:9999`）。

- **（3）⚠️ 边界：搜索 / 分类的「放行」要等 R3，本笔不能做**。守卫若在本笔放开，`#/` 上 `emit('go-search')` **无人接**（`App.vue:3` 的裸 `<router-view />` 上没有 `@go-search` 监听）⇒ 未登录点搜索会**既不弹窗、也不跳转 = 静默无反应**，**比现在的弹窗更差**。⇒ **刻意停在「浏览放行、操作仍拦」**。

- **（4）归属：本笔只做「整页锁 → 提示条」**。未登录的**完整形态**——顶栏（登录/注册入口）、未登录占位（用户中心 / 购物车）、搜索放行、`emit('go-search')` 落点——**全部归 R3（`/` 落地页）**。R3 之后本笔的改动**无需回改**（公开落地页不该有整页遮罩，此结论与布局演进无关）。⚠️ **按语（§5-Z，R3，2026-10-05）—— 本条的 R3 待办已结清，但四项的结论与当年的预期不同**：① **顶栏（登录入口）：做了** —— 两壳未登录时右三项**整体换成「登录」**（`config/nav.js` 的 `navFor` / `ANON_RIGHT`）；② **未登录占位（用户中心 / 购物车）：没做占位页，改做「不渲染」** —— 匿名时那三项**根本不出现**，比占位页更彻底，且**不新增任何守卫**；③ **搜索放行：本笔刻意不做**（用户裁决 —— 后端白名单 ≠ 前端必须放开；放开要改 `HomePage.vue`，破坏 R1/R2 的「一字不动」）⇒ 三条守卫**原样保留**，本条 T2 因此**仍然成立**；④ **`emit('go-search')` 落点：R3 无需改任何代码** —— `#/` 落 `/customer` 后走的就是 R1 那条链（`CustomerPage` → `HomeTab` → `router.push('/customer/search')`）。另：本条预告的「**无需回改**」**已兑现** —— `.login-hint-bar`（`HomePage.vue:4`）一字未动，`HomePage.vue` 在 R3 **整文件零改动**（443 → 443 行）。

- **（5）漂移登记**：
  - ✅ **`HomePage.vue` 净零（443 → 443 行）**，`.lock-mask` 块 **9 insertions(+) / 9 deletions(-)** ⇒ 指向它的 **7 处 inbound 行号引用 / 10 个行号**（`api-contract.md:175`→`:154`、`api-contract.md:178`→`:121`、`progress.md:401`→`:116`、`progress.md:653`→`:4`/`:135`/`:144`/`:165`、`progress.md:701`→`:154`、`api/auth.js:108`→`:116`、`main.js:21`→`:119`）**全部零漂移**（本笔选「一行换一行 + CSS 原地改写」而非「删行」的**决定性理由**）。
  - ✅ **`router/index.js` 净零**（55 → 55 行）：注释 `:29-31` **3 行换 3 行** ⇒ 该文件全仓**无任何 inbound「文件:行号」引用**（承 §5-V 结论）⇒ 零漂移；其尾换行与 `HEAD` **一致**（都无）⇒ 非新披露。
  - ⚠️ **措辞过时 2 处（行号均未漂，只是描述不再为真）—— 本笔**两处都收口**：**
    1. `progress.md:653`（§5-V 源句）→ **已就地加按语**（见上）。
    2. `router/index.js:94`（守卫注释 + `:96` 的「遮罩不渲染」；⚠️ **§5-X 重指**：R1 后该文件 55 → 123 行，原 `:29` / `:30` 下移）→ **已同行换名**（`.lock-mask` → `.login-hint-bar`、「遮罩」→「提示条」）并追加 `§5-W` 指针。⚠️ 本条原计划「登记不修」，用户以**「不留假话」**（同 `#11` / `#42` / TD-01 口径）裁定**本笔修**——理由：该句是本笔改动的**直接下游**，同一事实的引用点不同步合入即留假话；且**只改注释措辞，不属 R1 / R3 的路由结构改动**。⚠️ **再按语（§5-Y，R2，2026-10-05）**：`router/index.js` R2 后又 **123 → 201 行** ⇒ 本条的两个行号**都已失效**（`:94` 现实测是 `OrderPane` 那段的 `order/cart` 注释；`.login-hint-bar` 那处守卫注释现实测在 **`:173-174`**）。**内容未再变** —— R2 没有回改 `§5-W` 那处措辞 ⇒ 本条描述的「已同行换名」这件事 R2 后**仍为真**，只是地址变了。⚠️ **三次按语（§5-Z，R3，2026-10-05）**：该注释**地址仍未变**（标题 `:173` + 提到 `.login-hint-bar` 的 `:174`），但**性质变了** —— R3 把这条守卫换成函数 `landingFor`（现 `:197`）⇒ `:173` 的标题由「首页落点**守卫**」改写为「首页落点（R3 起是一条 **redirect**）」，`:174` 那句 `.login-hint-bar` 引导**内容未改**（只保留「整页锁 → 提示条」的历史）。本条描述的「已同行换名」这个**事实** R3 后仍为真。
  - ✅ 指向本文件的数值引用：本笔在 **§5-V 之后**追加，`## 6` 及其后下移；全仓对 `progress.md` 的**活数值引用为零**，仅存于归档件 `docs/agent/archive/项目整体进度汇报-2026-10-04.md`，其 10 处引用**全部落在 `:82`–`:199`**（插入点 **686** 之前）⇒ **未被打断**。

- **（6）验证**：`npm run build` 通过（**Compiled with 2 warnings**，与改前**同数** —— 均为**既有**体积 / 分包建议，非本笔引入；无 `export 'xxx' was not found`）；`dist` 校验 —— `login-hint-bar` 在 `dist/css/app.18d30f85.css` **1 次**，`lock-mask` 在 `dist/css/*` 与 `dist/js/*` **各 0 次** ⇒ 改名已进产物、旧类名未残留；`src/` 全量 grep `lock-mask` = **0 命中** ⇒ 无残留引用（含注释）。手测 **T1–T4** —— ⚠️ **本笔不单独跑**：用户裁定攒到 **R1–R4 全做完一起跑**（反复手测成本高），其中 **T2**（未登录点搜索 / 分类 / 商品卡仍弹「请先登录」）是「**守卫没被误删**」的关键判据，**记着在 R4 后一起验**。

- **（7）不加「更新记录」行**（照 `§5-H (3)` / §5-N / `§5-Q (4)` / `§5-T (7)` 先例）。

---

### 5-X. R1 客户壳路由化笔（`feat/router-customer`，2026-10-05）：**设计 A**（真嵌套路由 + `<router-view>`）—— 破 ≤5（9 文件）

> **性质**：前端 **7 代码文件（5 改 + 2 新）+ 2 文档** / **2 新文件** —— **破 ≤5**（用户批准，对账见 (1) 末）。⚠️ **此数曾写错**：本笔报告一度写「8 文件（5 代码 + 3 文档）」（漏 `src/api/http.js`、且代码/文档分类颠倒）—— 用户核出并纠正为 **9 文件（7 代码 + 2 文档）**；**后续引用一律用 9 / 7 / 2**。不动后端、不动 `ci.yml`、不动 A3 脚本、不动 `src/test/**`。`HomePage.vue` / `ChatRoom.vue` / `ShoppingCart.vue` / `LoginPage.vue` / `MerchantPage.vue`（R2）/ `ProductPage.vue`（R5 不做）**一字未动**。
> **来源**：§5-W (4) 把「标准布局重构」判给 R1–R4。R1 前 `CustomerPage.vue` 用内部 `curPage` / `curSubPage` 两个 ref + `v-if`/`v-show` 切 8 个页面，用户中心是**内联 markup**。
> **用户裁定（本笔的设计前提）**：走**设计 A**，理由「项目要当**企业投名状**，要用企业标准做法」；同时**丢弃设计 B 草稿**（把 `curPage` 改成可写 `computed`）—— `git restore` 回干净起点、**不做部分保留**。我此前**建议保留 B**（论证：状态归属才是要点、A 的唯一增量是「壳/页是否拆开」），**用户否决**，A 为最终方案。
> **两轴口径（与 R6 的分界）**：轴 1 = tab 状态住在哪（内部 ref ⇄ 路由）；轴 2 = 壳与页是否拆开（`v-if` ⇄ `<router-view>`）。**A 与 B 在轴 1 完全相同**，A 的唯一增量是轴 2 ⇒ 本笔**不含 R6 的内容**（不建 `AppShell.vue`、不做配置化）。
> ⚠️ **整节按语（§5-Y，R2，2026-10-05）：本节是 R1 那一笔的落码快照 —— R2 之后节内每一处行号都已漂，且若干结构描述已变假。** 引用本节时**一律以 §5-Y (9) 的漂移表为准**。变假的**结构**描述已就地标出（(1)3 的 `MerchantPage.vue:20`、(3) 的 `GoodsSelect.vue:165` 先例、(12)4 的 `/goodsSelect`、(12)5、`(15)` 的宏根因），本节**其余文字一律保留 R1 原样** —— 它是对 R1 那一笔的**如实记录**，不逐行修订（同 §5-T (5) / §5-W (5) 的「历史句保留 + 按语」口径）。

- **（1）改动 9 文件（7 代码 + 2 文档）**：
  1. `src/views/CustomerPage.vue`（**改；311 → 217 行**）：删 `curPage` / `curSubPage` / `goodsSelectRef` / 两个 `route.query` watcher / `goSearch` / `goToUserCenter` / `toLogin` / 8 个组件 import；模板只剩「顶栏 + 内容壳 + `<router-view>`」；顶栏 4 个 `button` → `<router-link>`；用户中心 markup 搬出。
  2. `src/views/UserCenter.vue`（**新；77 行**）：用户中心左菜单 + 它自己的 `<router-view>`（4 个子页）；**多根 fragment**（为的是正好平铺进 `.user-page` 这个 flex 容器）；`.user-menu*` 样式随之搬入。
  3. `src/views/customer/HomeTab.vue`（**新；32 行**）：**适配器** —— 把 `HomePage` 的 `go-search` 语义事件翻成 `router.push('/customer/search', { query: { kw } })`。**为什么非有它不可**（用户批准的「修正 1 / 取 A1」）：A 下 `HomePage` 的父节点变成 `<router-view>`（**没有 `@go-search` 监听**）⇒ 不接就**静默丢事件**（搜索框 / 分类点下去毫无反应）；而改 `HomePage.vue` 内部直接 `router.push` 会把**商家壳**（`MerchantPage.vue:20` 的 `@go-search="goSearch"`）也送进客户搜索页 ⇒ 用适配器让 `HomePage.vue` **一字不动**（⇒ 指向它的 **7 处 inbound / 10 个行号全零漂移**，见 (10)）。⚠️ **按语（§5-Y，R2，2026-10-05）**：本条的**理由已被 R2 兑现并注销** —— 商家壳路由化后 `MerchantPage.vue:20` **不再存在**（无 `HomePage` / 无 `@go-search` / 无 `goSearch`），商家首页改用**同一个适配器**（移到 `views/HomeTab.vue`、`props: { base }` 由路由记录给）⇒ 上面「会把商家壳也送进客户搜索页」这个约束**随之消失**（正是本条预告的路径，见 (12)5 与 §5-Y (4)）。本条描述的**手法不变**（适配器 + `HomePage.vue` 一字不动）。
  4. `src/router/index.js`（**改；55 → 123 行**）：`/customer` 由平级记录改为**父记录 + 5 子路由**；`user` 子壳再带 5 个子路由；**+1 兼容 `beforeEach`**（见 (6)）；首页落点守卫（`:114-121`）不变，只把它注释里**一句 R1 后变假的话**同行订正（见 (10)）。
  5. `src/views/GoodsSelect.vue`（**改；247 → 270 行，纯增量**）：加 `defineOptions({ name: 'GoodsSelect' })`（`:56`）+ `watch(() => route.query.kw, …)`（`:122-124`，`immediate: true`）作为**第二条搜索通道**。⚠️ `defineExpose({ searchByKeyword })`（`:165`）与 `onMounted` 的 `back=search`（`:157-161`）**一个字没动** —— 那是**商家侧**的通道（见 (8)）。
  6. `src/api/auth.js`（**改；192 → 196 行**）：`clearCredentials` 注释加 §5-X 按语 + 指向客户侧退出点的行号重指（见 (10)）。
  7. `src/api/http.js`（**改；净零 221 → 221 行**）：`:124` 一处行号重指（GoodsSelect 三指针之一，见 (10)）。⚠️ **这一件是批准后才长出来的**：它**不在**用户批准时的 8 件里 —— R1 使 `GoodsSelect.vue` 行号上移，而该行**恰好**被 `http.js:124` 引着 ⇒ 不重指就留假话（同 #11 / #42 / TD-01 口径）⇒ 就地订正（一行换一行）。
  8. `docs/agent/api-contract.md`：4 + 1 处**调用点行号**重指（见 (10)）。
  9. `docs/agent/progress.md`：本节 + 5 处按语 / 重指（见 (10)）。
  - ⚠️ **破 ≤5 的对账（两段，别只记第二段）**：用户方案原估 **7 文件** ⇒ ① 批准时我核出 **8** —— 多的是 (1)3 的 `HomeTab.vue`（**修正 3**，用户批准「文件数 8 不是 7，破 ≤5 豁免理由认可」）；② 落码中因 (1)7 的 `http.js` 也必改 ⇒ **实为 9**（**第二件在批准之后才出现，用户未预先豁免** —— 已报备，用户对本笔报告的唯一一处数错即源于未把它计入）。其中**新文件 2 个**（(1)2 / (1)3）。破的理由 = 一笔做完一次路由化胜过拆两笔各破一次，且本笔是 **R2–R4 的模板**。
  - ⚠️ **按语（§5-Y，R2，2026-10-05）：上面 (1)1–(1)9 的「改后行数 / 行号」是 R1 落码**快照**，R2 后多数又变了**（`CustomerPage.vue` 217 → **236**、`UserCenter.vue` 77 → **96**、`router/index.js` 123 → **201**、`GoodsSelect.vue` 270 → **275**；`views/customer/HomeTab.vue` 32 行 ⇒ **移到 `views/HomeTab.vue`、44 行**）。**本节的每个数都保留原样**（它们是对 R1 那一笔的准确记录），**新值一律以 §5-Y (9) 的漂移表为准**。

- **（2）路由表结构（`router/index.js:37-58`）**：`/customer` = 父记录（`CustomerPage`），`children` 5 条 —— `''`→`HomeTab`、`search`→`GoodsSelect`、`msg`→`ChatRoom`、`cart`→`ShoppingCart`、`user`→`UserCenter`；`user` 自己再带 5 条（`''` / `info` → `UserInfoPage`、`history` → `HistoricalOrders`、`friend` → `FriendRoom`、`chat` → `ChatRoom`）。
  - ⚠️ **`meta` 三键分工**（壳与页**都读它**，别随手删）：`tab` = 顶栏高亮（`CustomerPage.vue:5` 起 4 条 `route.meta.tab === …`）；**搜索页故意不设 `tab`** ⇒ 停在搜索时四个按钮都不高亮（与 R1 前一致）；`layout` = 内容区外壳类名（子记录不写则**继承**父记录的，`meta` 是**合并**）；`sub` = 用户中心左菜单高亮。
  - ⚠️ **为什么用 `meta.tab` 而不用 `route.path.startsWith('/customer')`**：后者在**所有**子路由上都为真 ⇒ 会让「商城主页」永远亮着。
  - ⚠️ **`path: ''` 与 `path: 'info'` 两个记录指同一个组件**（`/customer/user` 是短地址、`/customer/user/info` 是显式地址），**不设 `redirect`** 是为了不依赖「有 `children` 的记录能不能同时挂 `redirect`」这一细节。
  - ⚠️ **`router-link` 的高亮不能用 `router-link-active`**：`to="/customer"` 会**前缀匹配** `/customer/msg`（顶栏「商城主页」会跟着亮）⇒ 一律走 `meta`。

- **（3）`<KeepAlive>` 的取法（用户决策 1）**：`CustomerPage.vue:19-23` 用官方写法 `<router-view v-slot="{ Component }"><KeepAlive :include="CACHE_TABS"><component :is="Component" /></KeepAlive></router-view>`，`CACHE_TABS = ['HomeTab', 'GoodsSelect']`（`:53`）。**只缓存这两个**的理由：R1 前 home / search 是 `v-show`（切走**不卸载**），其余是 `v-if`（卸载）⇒ 只缓存这两个才与改前**逐字等价**。
  - ⚠️ **别把 `ChatRoom` 加进 `CACHE_TABS`**：它有 **2s** 轮询（`ChatRoom.vue:369`），缓存 = 离开消息页后**后台继续轮询**（新行为，且与壳里那支 5s 未读轮询重复）。
  - ⚠️ **`<KeepAlive :include>` 的匹配依据是组件的 `name`（不是路径）**：这两个 name 写在 `defineOptions` 里（`HomeTab.vue:15` / `GoodsSelect.vue:56`）⇒ **改名（含改文件名）会让缓存静默失效** —— 不报错、不崩，只是每次切回来**重新挂载**（轮播重置、搜索态丢失）。**改名前先 grep `include`。**
  - ⚠️ **`defineOptions` 的两个前提**：① 需 **Vue ≥ 3.3**（实装 3.5.33；`package.json` 声明 `^3.2.13`）—— 若有人把 vue 降到 3.2.x，**不是**静默失效，而是**编译失败**；② 本仓 ESLint **未开** `vue/setup-compiler-macros` ⇒ 宏会被判 `no-undef`，**必须**紧跟 `// eslint-disable-next-line no-undef`（照 `GoodsSelect.vue:165` 对 `defineExpose` 的**既有**处理）。⚠️ **再按语（§5-Y，R2，2026-10-05）：这个「既有先例」R2 后**不存在**了** —— `GoodsSelect.vue` 的 `defineExpose` 整条已删（裁决 4）⇒ `:165` 现为**那条退役按语本身**。R2 起可引的先例是**两处**：`GoodsSelect.vue:57`（`defineOptions`）与 `views/HomeTab.vue:37`（`defineProps`）。⚠️ 且 `:165` 这个行号**只在 R1 快照里成立**（R1 时 `defineExpose` 确在 `:165`）。⚠️ **这一条是首次构建红出来的**（3 处 `'defineOptions' is not defined  no-undef`，17:56 那条），**不是预先想到的** —— 记在此处防下一个宏（`defineModel` 等）再撞。根因是 `.eslintrc` 缺宏环境，**改配置是另一笔的事**（会动构建配置，本笔不碰）。
    ⚠️ **登记为债（用户裁定，本笔不还）**：3 条 `eslint-disable-next-line no-undef` 是**逐个宏的补丁**，每来一个新宏（`defineModel` / `defineSlots` …）就再贴一条 ⇒ **应一次性开 `vue/setup-compiler-macros` 根治**。**时点 = 「R6 之后、C3 之前」评估**（用户划线）—— 现在开动的是**构建配置**、超出本笔范围，且 A 版是 R2–R4 模板，先让模板跑完再统一收口。
  - ⚠️ **`onDeactivated` 不触发 `onUnmounted`**：被缓存的组件切走后**不卸载** ⇒ `HomePage.vue:195-197` 的 `clearInterval` **不会跑**、5s 轮播定时器**仍在跑**。这与 R1 前的 `v-show` **行为相同**（不是回归），但它**是新的误解来源** —— 防后人以为「缓存 = 停掉后台」。同理：**不能**把 `GoodsSelect.vue:157` 的 `onMounted` 改成 `onActivated` —— `onActivated` 只在 `<KeepAlive>` 内触发，而**商家侧是直挂、无 KeepAlive** ⇒ 一改就断商家侧（R2 之前不许动）。⚠️ **按语（§5-Y，R2，2026-10-05）**：本条**两个前提都已变** —— ① `GoodsSelect.vue:157` 现为 **`:159`**（R2 在该文件注释区增删净 +2 行）；② 「**商家侧是直挂、无 KeepAlive**」**R2 后不成立**（商家壳同样 `<KeepAlive>` 了）。**结论不变、理由换**：不能改 `onActivated` 的真实原因是 —— **离开壳会连 KeepAlive 缓存一起销毁**（`/product` 是 `/merchant` / `/customer` 的**兄弟**路由 ⇒ 壳卸载）⇒ 回到搜索页必**重新挂载**、`onMounted` 必触发。客户侧从 R1 起就是这个机制。逐条见 §5-Y。

- **（4）壳「不会因切 tab 重挂」（用户补充要求 3）**：`/customer` 是**父记录**，`children` 之间切换**不重建父实例** ⇒ 壳 `onMounted`（`CustomerPage.vue:115-120`）里那支 **5s 未读轮询只起一次**。⚠️ 这个性质的来源**只有这一条**：后人若把 5 个子路由提成**平级记录**、或给壳加 `:key`（如 `<router-view :key="route.path">`），性质立刻消失 —— 表现是「每切一次 tab 就多一支 5s 轮询、**永不回收**」。

- **（5）搜索进 URL 的实证收益（用户补充要求 2 —— A 相对 B 的**唯一实证增量**）**：`/customer/search?kw=xxx` ① **可刷新还原**（刷新后 `watch` 的 `immediate` 立刻重搜）；② **可直接手输 URL**（首挂载即带 `kw` ⇒ 自动搜，不必先经过首页）；③ 可**深链 / 分享 / 后退**。B（`curPage` 可写 `computed`）下关键词只活在内存 ⇒ 这三条**一条都拿不到**。⚠️ 注意：`GoodsSelect` 的这条**第二条通道**与**商家侧的 ref 通道并存**、互不替换（用户决策 2 / 修正 2）。

- **（6）兼容重定向（`router/index.js:81` / `:83-92`）两个分支 —— 都不能删**：① `?page=cart|user|msg|chat` → 对应子路由，**带走其余 query**（`otherId` / `otherType` / `_t` 是 `ChatRoom` 读的会话参数，丢了就打不开对应聊天；**认不出的 `page` 归位 `/customer`**）〔⚠️ **按语（`fix/legacy-chat-tab`，2026-10-05）**：本条**原记只有 3 键**（`cart|user|msg`）——R1 落码时 `LEGACY_TAB` 误写成 `{cart,user,msg}`、**漏了 `chat`** ⇒ 客户侧「咨询商品 / 好友点聊天」两条流程落回主页并丢会话参数；已加回 `chat`，键集合现为 **4 个**，逐条见 (16)1〕；② `?back=search` → `/customer/search` **且保留 `back`**（`GoodsSelect.vue:158` 仍靠它触发 `loadState()`）。三条理由已写进代码注释（`router/index.js:74-77`）：**R5 不做**（`ProductPage.vue:363` / `:388` / `:400` 仍推旧 URL）、`MerchantPage` **R2 才改**、书签 / 手敲 URL 不受代码控制。
  - ⚠️ **只认精确路径 `/customer`** ⇒ `/merchant?page=order`（`ProductPage.vue:361`）**不受影响**（那是 R2 的活）。
  - ⚠️ `/customer/search` **不在** `LEGACY_TAB` 里 —— 它本来就不是旧 tab 名（用户 §三-3 认可）。

- **（7）四条搜索入口（A 下逐一走通）**：① `HomePage` 搜索框 → `HomeTab` 适配器 → `/customer/search?kw=`；② `HomePage` 分类点击 → 同 ①（两者都走 `emit('go-search')`，`HomePage.vue:139` / `:148`）；③ `ProductPage.vue:380` 写 `PENDING_SEARCH_KEYWORD` 后推 `/customer` → 壳 `onMounted` 的 `handlePendingSearch()`（`CustomerPage.vue:108-113`）读出并**换成** `/customer/search?kw=`（关键词从此由 URL 承载）；④ `ProductPage.vue:388` 的「返回」推 `/customer?back=search` → 兼容守卫换 `/customer/search?back=search` ⇒ `loadState()` 恢复结果（`GoodsSelect.vue:157-161`）。
  - ⚠️ ③④ 之所以绕一圈而不让 `ProductPage` 直接推新 URL：**R5 不做**（用户划定本笔不动 `ProductPage.vue`）⇒ 兼容层必须留着；R5 落地后 ③ 可**整段删掉**、④ 可简化。

- **（8）商家侧为什么不断（用户决策 3）—— 三条，缺一不可**：① 本笔对 `GoodsSelect.vue` 是**纯增量**（只新增行，**不改任何既有行**）⇒ `defineExpose`（`:165`）与 `onMounted`（`:157-161`）原样保留，`MerchantPage.vue:24` 的 `<GoodsSelect ref="goodsSelectRef" />` 照旧能调 `searchByKeyword`；② 商家 URL 永远 `/merchant` / `/merchant?back=search`（`ProductPage.vue:388` 的商家分支），**永不带 `kw`** ⇒ 新 `watch` 在商家侧**恒不触发**；③ **不用 `onActivated` 替换 `onMounted`**（商家侧无 `<KeepAlive>` ⇒ 会断，见 (3) 末）。
  ⇒ **R2 之前商家侧行为零变化**。⚠️ R2 把商家壳也路由化时，① 这条通道才会退役 —— **那时**才谈删 `defineExpose`。⚠️ **按语（§5-Y，R2，2026-10-05）**：**R2 已做，三条里两条已失效** —— ① 通道**已退役**（`defineExpose` 已删，见 `GoodsSelect.vue:165` 起）；② 「商家 URL **永不带 `kw`**」**已变假**：R2 起商家首页搜索也推 `/merchant/search?kw=`（`HomeTab` 适配器 + `props.base`）⇒ 该 `watch` 在商家侧**正常触发**、且是**唯一**通道；③ 理由已换成「离开壳销毁缓存」（见 (3) 末按语）。三条的**历史原样保留**，只在此处订正。

- **（9）布局等价的手法（本笔最容易被忽略的一处）**：R1 前 5 个兄弟 div 的类名（`.home-container` / `.page` / `.cart-container` / `.user-page` / `.empty-page`）→ 壳里**一个** `<div :class="layoutClass">`（`CustomerPage.vue:18` / `:61`，值来自 `route.meta.layout`、**默认 `'page'`**）⇒ 类名**一一对应**；⚠️ **按语（`fix/legacy-chat-tab`，2026-10-05）**：本条的「布局**逐字不变**」**在客户购物车一页不成立** —— R1 落码时丢了 `:showSwitch="false"` ⇒ 该页凭空多出 📋/🛒 开关（且点它 `emit('toggle')` 无人监听 = 死控件），已在本笔修回（见 (16)2）；**其余 4 页不受影响**；`ChatRoom.vue` / `ShoppingCart.vue` / `UserInfoPage.vue` 等**未动** ⇒ 指向它们的 inbound 引用**零漂移**，也不必依赖「scoped CSS 能不能透过 `<router-view>` 传播」这种细节。
  - 用户中心：左菜单由 `button` 改 `<router-link>`（渲染成 `<a>`）⇒ 选择器随之改（`.user-menu .menu-btn` / `.user-menu .logout`），并加 `text-decoration: none; color: #333; box-sizing: border-box`（让 `a` 与 `button` 的盒模型一致）。
  - ⚠️ **`.empty-page` 现在是死规则**（R1 前是 `curPage` 认不出时的兜底；A 下没有「认不出」这回事 —— 路径不匹配就什么都不渲染）。**本笔刻意保留**（删它属清理、非必要；R6 抽 `AppShell` 时一并处理）。

- **（10）漂移登记（本笔的**真漂移**与重指）**：
  - **R1 造成的真漂移 —— 已按**落码后实测值**重指（均留「原记」）**：
    - 客户侧退出点：`CustomerPage.vue:112` → **`UserCenter.vue:37`**（**换文件**，非仅换行号）—— 出现于 `auth.js:146/149/151/171`、`progress.md:568`、`progress.md:663`。
    - `CustomerPage.vue` 的 `conversations` 调用点：`:97` → **`:89`**。⚠️ **该原记在 R1 前就已漂 3**（`HEAD` 实测 `:100`）—— 出现于 `api-contract.md:192`、`progress.md:741`。
    - `GoodsSelect.vue` 四个指针：URL 赋值行 `:74` → **`:84`**、`:78` → **`:88`**、`fetch` 本身 `:81` → **`:91`**、if/else 块 `:71-80` → **`:82-90`**。⚠️ 同样**R1 前已各漂 2**（`HEAD` 实测 `:76` / `:80` / `:83` / `:74-82`）—— 出现于 `api-contract.md:178/183/210/214`、`progress.md:747`、`http.js:124`。
    - `router/index.js`：`/` 记录 `:12` → **`:35`**（`progress.md:653`）；守卫注释 `:29` / `:30` → **`:94` / `:96`**（`progress.md:714`）。⚠️ 与 §5-W (5) 记的「`router/index.js` **全仓无任何 inbound 引用**」**不符** —— 现存这 2 处、且**都是 §5-W 自己写入的**（`:653` 的按语、`:714` 的清单）；本笔据实处理。
    - ⚠️ **本笔自己的行号又被自己推翻过一次**：ESLint 修复给 3 个 `.vue` 各加了 **1 行**（`eslint-disable-next-line`）⇒ 上面这批 `GoodsSelect` / `UserCenter` 的行号是**第二次**重指后的值（第一版按「未加 disable」的落码态写，已作废）。
  - **零漂移**：`HomePage.vue`（**一字未动**、443 → 443 行 ⇒ 7 处 inbound / 10 个行号全对，含 §5-W 已核过的那批）；`ChatRoom.vue` / `ShoppingCart.vue` / `MerchantPage.vue` / `ProductPage.vue` / `LoginPage.vue` / `OrderCenter.vue` / `FriendRoom.vue`（**均未动**）。
  - **`api/auth.js` +4 行**（`:150-153` 的按语块）：全仓引 `auth.js` 行号处**只有 `:3-8`（4 处）与 `:108`（1 处）**、**都在插入点之上** ⇒ **零漂移**（实测 grep）。
  - ⚠️ **R1 之外的前置漂移（实测；本笔只登记，仅在「本笔已改的同一行」处订正）**：`ChatRoom(.vue):184` 实测 **`:183`**、`MerchantPage(.vue):113` 实测 **`:116`**、`OrderCenter(.vue):125/127/129` 实测 **`:130/132/134`**、`FriendRoom(.vue):226/228/231` 实测 **`:213/215/218`**。成因与 `api-contract.md` 全表同一条：**§5 矩阵停在 2026-09-30（`#48` 之前）、此后未重测** —— **已登记于 §5-V (5)3**。⇒ **不属本笔**，本笔**不重开那笔账**，只在 `api-contract.md:192` / `progress.md:741`（本笔已改的同一行）按实测订正。
  - ⚠️ **`auth.js` 的历史值不自洽（本笔登记、不改历史句）**：`auth.js:146` 写「原本散在 `CustomerPage.vue:113-114`」，而 `HEAD` 实测该块为 **`:111-114`**（`clearCredentials()` 在 `:112`）—— 两值不一致、**成因未考**；已在按语里注明，历史句**保留原样**。
  - **本笔新写的自引用 —— 逐处按落码后实测值核对**：`router/index.js:80` 引 `GoodsSelect.vue:158`（**初稿写 `:135`，落码后实测修正**）；`router/index.js:106` 引 `CustomerPage.vue:5` ✔；`auth.js:146/149` 引 `MerchantPage.vue:131`（实测 `:131-132` = `clearCredentials()` + `clearMerchantId()`）✔、`UserCenter.vue:37` ✔；`CustomerPage.vue:105` 引 `ProductPage.vue:380` ✔；`CustomerPage.vue:51` 引 `ChatRoom.vue:369` ✔；`GoodsSelect.vue:116` 引 `MerchantPage.vue:24` ✔。
  - ⚠️ **按语（§5-Y，R2，2026-10-05）：本节**全部**行号又漂了一轮** —— R2 改了 `CustomerPage.vue`（+19 行）、`UserCenter.vue`（重写）、`GoodsSelect.vue`（净 +5 行）、`router/index.js`（123 → 201）、并删了 `views/customer/` ⇒ 上面每条「重指后」的值**都已过期**。逐条的 **R2 新实测值**见 §5-Y (9)；`auth.js` 那四条按语本身也已被 R2 按新语义**重写**（不再引 `MerchantPage`）。本节保留原样 = 对 R1 那一笔的如实记录。

- **（11）已知行为变化 1 处（**请追认**）**：R1 前在用户中心**某个子页**（如「历史购买记录」）再点顶栏「用户中心」，`curSubPage` **保持**（停在原子页）；R1 后顶栏那条是 `to="/customer/user"` ⇒ 恒落**个人信息**（子路由 `''` = `UserInfoPage`）。这是「tab 状态 = URL」的直接后果，方向上**更标准**（顶栏 = 回主页签）。⚠️ 若要逐字保持原行为，得把「上次子路由」再存回内存 —— **不建议**（等于把刚搬走的状态搬回来）。
  - 另一处**不是**变化、但容易被误读：顶栏红点只在 **`/customer/msg`** 清（`CustomerPage.vue:101-103`），而用户中心里的「消息」子页（`/customer/user/chat`）**不清** —— 与 R1 前 `curPage === 'msg'` 的判据**完全一致**。

- **（12）遗留（本笔刻意不碰，逐条归口）**：
  1. **`ChatRoom.vue:348` / `:367` 的 `page: 'chat'` 残留**（用户 §三-2 认可留 R2 清）：它 `router.replace` 的是**当前路径**（`/customer/msg` 或 `/customer/user/chat`）、**路径不变** ⇒ 兼容守卫（只认精确 `/customer`）**不触发** ⇒ 现在只是 URL 上多一个没人读的 `page=chat`，**无行为影响**。R2（或独立笔）清。⚠️ **按语（§5-Y，R2，2026-10-05）：R2 未清**（用户裁定 7：清它要动 `ChatRoom.vue` 两行 ⇒ 行号漂移，而无害）⇒ **归 R4 收编笔**；且 R2 起 `ChatRoom` 在多出 3 条新路径（`/merchant/msg`、`/merchant/user/chat`）上也被挂载，判据不变（守卫只认精确 `/customer` / `/merchant`，`replace` 不动路径 ⇒ 仍不触发）。⚠️ **三次按语（§5-AA，R4，2026-10-05）**：**R4 已清** —— 用的是**同行改写**（`{ ...rest, page: 'chat' }` → `rest`）而非删行 ⇒ `ChatRoom.vue` **净零 578 行**、本条引的 `:348` / `:367` **地址未漂**（只由「残留」变成「已清」）。逐条见 §5-AA (9)。
  2. **`ChatRoom.vue:132-160` 的角色分支仍是死代码**：`createWebHashHistory()` 下 `window.location.pathname` **恒为 `/`**（承 §5-V 前的发现）。⚠️ **A 没有修它**（`ChatRoom.vue` 一字未动）—— 别把「A 已落地」当成「它已修」。
  3. **`FROM_PAGE` 死键**（写而无读）仍在，本笔未动。
  4. **`/goodsSelect` 平级路由仍挂着**（`router/index.js:63`；⚠️ **按语（§5-Y，R2，2026-10-05）：R2 后该记录实测在 `router/index.js:128`**），全仓**无 `router.push('/goodsSelect')`** ⇒ 与 R1 前一样是**僵尸路由**，收编归 R4。⚠️ **四次按语（§5-AA，R4，2026-10-05）**：**已在 R4 收编** —— 该记录**同行替换**为 `{ path: '/goodsSelect', redirect: searchLanding }`（`:128` **地址未漂**，本条按语引的 `:128` 仍对，只是「僵尸」这个事实终结）；生产者实测仍 **0**（含基线 `9b87ab9`），收编理由是「裸挂 ⇒ 无导航栏」+「全仓无 catch-all ⇒ 删记录 = 手敲白屏」。逐条见 §5-AA (3)(4)。
  5. ~~**`MerchantPage.vue:20` 的 `@go-search="goSearch"`** 仍是商家侧的唯一落点 —— R2 路由化时，`HomeTab` 适配器就是现成模板。~~ ⚠️ **R2（2026-10-05）就地订正：本条已变假** —— 商家壳已路由化，`MerchantPage.vue:20` **不再存在**（壳里已无 `HomePage`、无 `@go-search`、无 `goSearch`）；商家首页改由**共用的** `views/HomeTab.vue` 适配（`props: { base: '/merchant' }` 由路由记录给）⇒ 原句的「落地预期」已按预告兑现，逐条见 §5-Y (4)。末条括号里那句是**当时的预判**，保留原样以便对账。

- **（13）R6（抽公共 `AppShell`）要接的活**：
  1. **布局类名的所有权**现在留在**两个壳**（`CustomerPage.vue:61` / `MerchantPage.vue` 的 `layoutClass` + `route.meta.layout`；⚠️ **按语（§5-Y，R2，2026-10-05）：R2 把这一手法原样复制到商家壳后，两处实测都在各自文件的 `:80`**）。R6 抽 AppShell 时应把「内容区外壳 div」**连同类名下移到各页组件**，`meta.layout` 随之退役。⚠️ 本笔**不为 R6 做预案**（不建 `AppShell.vue`、不做配置化）——⚠️ **R2 破了一半**：顶栏已抽到 `src/config/nav.js`（用户裁决 6），`layoutClass` 仍留在两壳（见 §5-Y (7)）。
  2. **客户侧页面组件的路径风格统一**（用户裁定归 R6，见 (14)）：`views/customer/HomeTab.vue` **带子目录**、`UserCenter.vue` **平铺**在 `views/` ⇒ 企业标准应统一到一侧（建议全进 `views/customer/`）。⚠️ **现在不改**：`UserCenter.vue` 是既有 markup 的搬家（R1 只批了这一个路径），且更名会牵动 `router/index.js` 的 `import` 与**全仓指向它的行号引用**（`auth.js` / `api-contract.md` / `progress.md` 里的 `UserCenter.vue:37` 等多处）⇒ 为纯风格改名再付一轮重指标签**不值**，随 R6 的 AppShell 整顿一次到位。⚠️ **按语（§5-Y，R2，2026-10-05）：本条已半解、且旧口径作废** —— R2 把 `HomeTab.vue` 上移到 `views/HomeTab.vue`（与 `UserCenter.vue` 同为顶层共享件）⇒ `views/customer/` **整个空掉、目录已删**，「建议全进 `views/customer/`」这句话**不再成立**。R2 起的新口径是：**跨壳共享的页面组件平铺在 `views/`**（`HomeTab` / `UserCenter`）、**单壳专属的进该壳子目录**（`views/merchant/OrderPane.vue`）。残留的不一致 = `views/merchant/` 有子目录而**没有对称的 `views/customer/`**（客户壳无「专属子页面组件」）—— 归 R6 连同 AppShell 一起定案。

- **（14）命名/路径不一致（自觉登记，已并入 (13)2）**：新文件 `views/customer/HomeTab.vue` **带子目录**，`UserCenter.vue` 却**平铺**在 `views/` 下（它是既有 markup 的搬家，R1 只批了「新增 `UserCenter.vue`」这一个路径）。⇒ 客户侧页面组件的路径风格**暂不统一**；**收口口径见 (13)2 —— 归 R6 的 AppShell 整顿**（理由：改名要重指全仓引用）。⚠️ **按语（§5-Y，R2，2026-10-05）**：本条描述的「`views/customer/HomeTab.vue` 带子目录 vs `UserCenter.vue` 平铺」这组不一致**已不存在**（`HomeTab` 上移到 `views/`、`views/customer/` 已删）⇒ 新口径与残留见 (13)2 的按语。

- **（15）验证**：`npm run build` **通过** —— **Compiled with 2 warnings**（`entrypoint size limit` + `webpack performance recommendations`，与改前**同数**、均为**既有**；**无** `export 'xxx' was not found`）。⚠️ **首次构建是红的**：`[eslint] 'defineOptions' is not defined  no-undef` × 3（`GoodsSelect.vue:53` / `UserCenter.vue:24` / `HomeTab.vue:12`）⇒ 加 3 条 `eslint-disable-next-line` 后转绿（见 (3)）。⚠️ **按语（§5-Y，R2，2026-10-05）**：上述三个**行号已全部失效** —— `GoodsSelect.vue` 的 `defineOptions` 实测在 **`:57`**、`UserCenter.vue` 在 **`:38`**（该文件 R2 已重写）、`HomeTab.vue` 的移到 **`views/HomeTab.vue:17`**；且 R2 的 `views/HomeTab.vue:37` 新增了**第二类宏** `defineProps`，同样需要 disable（`HomeTab.vue:36`）。⚠️ **根因订正（R2 实测，比 R1 那句话更准）**：本仓 `eslint-plugin-vue` 是 **8.7.1**，它的 `vue3-essential` **不带**任何宏环境（实测 `configs['vue3-essential'].env === null`）。该版本**确实**导出一个 env，但名字是 **`setup-compiler-macros`**（**不带** `vue/` 前缀），且其中**只有 4 个宏**：`defineProps` / `defineEmits` / `defineExpose` / `withDefaults` —— **没有 `defineOptions`**。⇒ 两件事：① 即使把该 env 打开，`defineOptions` 仍会 `no-undef`（它要 eslint-plugin-vue **≥ 9.13** 才进得去）⇒ R1 记的那条「R6 之后、C3 之前评估开 `vue/setup-compiler-macros` —— 一次性解决宏的 eslint 报错」**目标只能实现一半**；② 逐条 disable 是 8.7.1 下的**长期**要求。逐条见 §5-Y (9)。`diff --stat` 与「净零核查 / 真漂移重指核查」见本笔报告。手测 **T1–T4** —— ⚠️ **本笔不单独跑**（用户裁定：攒到 R1–R4 全 merge 后一次跑）。

- **（16）事后修补（`fix/legacy-chat-tab`，2026-10-05）：R1 的两处缺陷** —— 两处都是**R2 侦察时实测发现**（非用户回报），用户逐条认可 ⇒ **独立小 PR 先行**（不混进 R2）。**本笔性质 = 1 代码（`router/index.js`）+ 1 文档**。
  1. **兼容层漏键：`LEGACY_TAB` 少了 `chat`** ⇒ 客户侧两条流程落回主页。**错在落码、不在方案** —— R1 方案原文写的是 `{ cart, user, chat }`，落码写成 `{ cart, user, msg }` ⇒ **`chat` 丢、`msg` 多**。
     - **判据 = 生产者实测反推**：全仓 `?page=` 生产者共 7 处（`ProductPage.vue:361/363/398/400/447`、`FriendRoom.vue:246`、`ChatRoom.vue:348/367`），**实际推的键只有 `cart` / `user` / `chat` / `order`** —— 客户侧 = `cart`（`ProductPage.vue:363`）、`user`（`:400`）、`chat`（`:447` 与 `FriendRoom.vue:246`）；**`msg` 全仓零生产者**。⚠️ **按语（§5-AA，R4，2026-10-05）**：本条写「共 **7** 处」，但**它自己列出的清单是 8 项**（`ProductPage.vue:361/363/398/400/447` 5 项 + `FriendRoom.vue:246` 1 项 + `ChatRoom.vue:348/367` 2 项）—— R4 实测复核后**以清单为准**记账：**R4 只清 `ChatRoom.vue:348/367` 这 2 处**（`page: 'chat'` 不再写回 URL，§5-AA (9)）⇒ **R4 后 = 6 处**（那 5 + 1 处**一字未动**）。⚠️ 计数与清单不符这一条**本笔只登记、不改原句**（它是 R1/`fix/legacy-chat-tab` 的如实记录）。⚠️ 地址 `ChatRoom.vue:348/367` **未漂**（R4 净零）—— 变的是**事实**（已清），不是地址。
     - **R1 前的行为（`9eb0729` 实测）**：旧 `CustomerPage.vue` 的两处**都认 `chat`**、**都不认 `msg`** —— `onMounted` 分支 `:175-180`（`chat` → `curPage='msg'`）与 `watch(route.query.page)` 分支 `:154-160` ⇒ R1 前有效键 = `cart|user|chat`。
     - **R1 后的后果**：`page=chat` 不命中 ⇒ 走 `{ path: '/customer' }` 兜底 ⇒ 「咨询商品」（`ProductPage.vue:447`）/「好友点聊天」（`FriendRoom.vue:246`）**落回主页、且丢会话参数**（`otherId` / `otherType` / `_t` 是 `ChatRoom` 读的）。
     - **修法**：`router/index.js:81` 加回 `chat: '/customer/msg'`；**`msg` 保留** —— 它是 R1 前的历史键（手敲 URL 也算），删它属**改行为**、不是修 bug。修后键集合 **4 个** ✅。
  2. **客户购物车丢了 `:showSwitch="false"`** ⇒ 多出一个**死控件**。R1 前客户壳是 `<ShoppingCart :showSwitch="false" />`（`9eb0729` 实测 `CustomerPage.vue:32`），迁进子路由时**整条 `props` 漏抄** ⇒ `ShoppingCart.vue:81` 的 `default: true` 生效（`:4` `v-if="showSwitch"`）⇒ 页头凭空多出 📋/🛒 开关。⚠️ 它**不是**「多余但能用」：`ShoppingCart` 的 `handleToggle` 只 `emit('toggle')`，而客户壳**从未**挂 `@toggle`（`9eb0729` 实测该文件 `toggle` / `showSwitch` 只出现这 1 行）⇒ **点了没反应**。
     - **修法 = 路由记录上加 `props`**（`router/index.js:44`）：`props: { showSwitch: false }`。⚠️ **按语（§5-Y，R2，2026-10-05）**：该记录 R2 后实测在 **`router/index.js:61`**（`{ path: 'cart', component: ShoppingCart, props: { showSwitch: false }, … }`，行末带 `fix/legacy-chat-tab` 的成因说明）—— R2 重写了 `router/index.js`（123 → 201 行）⇒ `:44` 失效。**前提已先验**：本项目 vue-router **4.6.4** 的 `<router-view v-slot>` 仍把路由记录的 `props` **预绑**到 slot 拿到的 `Component` 上（`node_modules/vue-router/dist/vue-router.mjs:1101-1106`：`const routePropsOption = matchedRoute.props[currentName]; … h(ViewComponent, assign({}, routeProps, attrs, …))`）⇒ 生效且 `CustomerPage.vue` **一字不动**。
     - ⚠️ **同一条机制也是 R2 `HomeTab` 参数化（`props: { base }`）的依据** —— 用户据此认可（R2 的登记落 `§5-Y`，**本笔时尚未登记**）。
  3. **净零核对（本笔核心手法）**：`router/index.js` **123 → 123 行**（3 insertions / 3 deletions，**只改 3 行、均为行内追加**）⇒ 该文件**全部既有行号引用零漂移**（`:35` / `:44` / `:81` / `:83-92` / `:94` / `:96`，及 `progress.md:653` / `:714`）。代价 = 3 行变长（最长注释行约 430 字符）；**合法** —— 本仓 ESLint = `plugin:vue/vue3-essential` + `eslint:recommended`、`rules: {}`（`package.json` 的 `eslintConfig`），**无 `max-len`**。
  4. **成因未考**：两处都不是有意取舍，是**落码期的静默丢失**（键写错、`props` 整条漏抄）。**不追责、只登记**；要防的是同类 —— **兼容层的键集合必须由「生产者实测反推」，不能凭方案记忆、也不能由消费者侧代码推**（旧 watcher 能读的键集 ≠ 生产者会推的键集，本例两集合只是近似相交）。
  5. **遗留**：`ProductPage.vue:361` 推的 `/merchant?page=order` **本笔仍不认**（守卫只认精确 `/customer`）⇒ 那是 **R2** 的活（R2 登记落 `§5-Y`，**本笔时尚未登记**）。

- **（17）不加「更新记录」行**（照 `§5-H (3)` / §5-N / `§5-Q (4)` / `§5-T (7)` 先例）。

---

### 5-Y. R2 商家壳路由化笔（`feat/router-merchant`，2026-10-05）：**镜像 R1 的设计 A** —— 破 ≤5（12 文件）

> **性质**：前端 **10 代码文件（7 改 + 1 移位 + 2 新）+ 2 文档** / **2 新文件** —— **破 ≤5**（用户预先批准 11 文件，见 (1) 末的**对账与超出**）。⚠️ 本笔还**顺手修了 R1 的两处缺陷** —— 但那落在 `fix/legacy-chat-tab`（PR #63，**独立小 PR 先行**），**不计入本笔的 12**（见 §5-X (16)）。
> **前置**：`fix/legacy-chat-tab` merge 后（`origin/main` = `999f87e`）`feat/router-merchant` 重指到新 `origin/main`（该分支此前无提交 ⇒ 零成本）。⚠️ 为什么必须串行：两笔都改 `router/index.js` 的**同一区**（兼容守卫）。
> **来源**：§5-W (4) 把「标准布局重构」判给 R1–R4；R1 做客户壳，**R2 做商家壳**（用户：「镜像 R1」）。R2 前 `MerchantPage.vue` 用内部 `curPage` / `curSubPage` 两个 ref + `v-if`/`v-show` 切 7 个页面，用户中心是**内联 markup**，与 R1 前的 `CustomerPage.vue` 同病。
> **用户裁定（七项，全部按推荐执行）**：① orderTab 全路由化；② UserCenter 共享 + `meta` 驱动；③ HomeTab 共享 + 移位 + `props.base`；④ GoodsSelect 退役 `defineExpose`；⑤ 顶栏配置 `src/config/nav.js` **R2 就抽**；⑥ 一笔做完（破 ≤5）；⑦ `ChatRoom.vue:348/367` 的 `page:'chat'` 残留**不清**、归 R4。⚠️ **按语（§5-AA，R4，2026-10-05）**：**R4 已清**（同行改写、`ChatRoom.vue` 净零 ⇒ `:348/367` **地址未漂**）。本条是 R2 当时的**裁定记录**，保留原样。逐条见 §5-AA (9)。
> **两轴口径**：同 §5-X —— R2 在轴 1（tab 状态住哪）与轴 2（壳/页是否拆开）上都照 R1 的做法 ⇒ **一壳一壳地搬**，不含 R6 的内容（不建 `AppShell.vue`，但 nav 已配置化 ⇒ R6 只需把两个壳包起来）。

- **（1）改动 12 文件（10 代码 + 2 文档）**：
  1. `src/views/MerchantPage.vue`（**改；329 → 236 行**）：删 `curPage` / `curSubPage` / `goodsSelectRef` / `orderTab` / `toggleOrderTab` / `goSearch` / `goToUserCenter` / `toLogin` / 两个 `route.query` watcher / 11 个组件 import；模板只剩「顶栏 + 内容壳 + `<router-view>`」；顶栏改 `v-for` 读 `MERCHANT_NAV`；`.user-menu*` 样式搬出（已由 `UserCenter.vue` 提供，与那份逐条等价）。⚠️ **壳现在与 `CustomerPage.vue` 逐字同构**（唯一差异 = 顶栏用哪份配置）。
  2. `src/router/index.js`（**改；123 → 201 行**）：`/merchant` 由平级记录改为**父记录 + 6 子路由**（`''` / `search` / `msg` / `order` / `order/cart` / `user`），`user` 子壳再带 7 条；兼容守卫**合成一张表**；`HomeTab` 的 import 路径改；新增 3 个 import（`OrderPane` / `GoodsAdd` / `GoodsUp`）。
  3. `src/views/customer/HomeTab.vue` → **`src/views/HomeTab.vue`**（**移位 + 参数化；32 → 44 行**）：加 `props: { base }`（`defineProps`，第 **37** 行，**需要自己的 eslint-disable** —— 见 (9)3），push 目标由写死的 `/customer/search` 改为 `` `${props.base}/search` ``。`views/customer/` 目录**随之空掉、已删**（见 (7)3）。
  4. `src/views/UserCenter.vue`（**改；77 → 96 行**）：菜单改 `meta.subMenu` 驱动（`v-for`），退出改 `meta.logout` 分支（商家侧加 `clearMerchantId()`）⇒ **两壳共用同一组件**（见 (5)）。
  5. `src/views/CustomerPage.vue`（**改；217 → 236 行**）：顶栏改 `v-for` 读 `CUSTOMER_NAV`（+import）；`CACHE_TABS` 注释与 `views/HomeTab.vue` 新路径同步。**其余一律未动**。
  6. `src/views/GoodsSelect.vue`（**改；270 → 275 行**）：删 `defineExpose`（原 `:163-165` 三行 = 注释 + eslint-disable + 宏）+ 改写两处注释（见 (8)）。
  7. `src/api/auth.js`（**改；196 → 202 行**）：三条注释按**新退出语义**重写（「两个退出点」→「同一函数两分支」），见 (5)3。
  8. `src/api/http.js`（**改；净零 221 → 221 行**）：`:124` 一行换一行（`GoodsSelect.vue:91` → `:92`）。⚠️ **这一件不在用户批准的 11 里** —— 见 (1) 末。
  9. `src/config/nav.js`（**新；34 行**）：`CUSTOMER_NAV` / `MERCHANT_NAV` = `{ left, right }`，项 = `{ label, tab, to, badge? }`（见 (6)）。
  10. `src/views/merchant/OrderPane.vue`（**新；35 行**）：orderTab 二态的包装组件（见 (3)）。
  11. `docs/agent/api-contract.md`：**5 处**调用点行号重指（`:192` 的两个壳 + `:178/183/210/214` 的 GoodsSelect 四指针——R2 使该文件行号 +1）。
  12. `docs/agent/progress.md`：本节 + §5-X 的**11 处**按语/订正 + §6 的 **3 处**按语。
  - ⚠️ **破 ≤5 的对账（用户先批 11，实落 12）**：批准口径 = **11 文件（9 代码 + 2 文档）/ 2 新文件**（见 §5-Y 方案的「问 7」表）。**落码实测 12** —— 多的是 (1)8 的 **`src/api/http.js`**。**为何计划时漏了**：我编「现行指针」清单时只按 `文件:行号` 的**引用方**去核（`auth.js` / `HomeTab.vue` / `GoodsSelect.vue` / `UserCenter.vue`），没把手写行号的**代码注释**再全量 grep 一遍 ⇒ 漏了 `http.js:124`。**为何还是改了**：该行字面写着 `GoodsSelect.vue:91`，R2 后为 `:92` ⇒ 不改就是**留假话**（同 #11 / #42 / TD-01 口径，用户长期要求）。⇒ **报备并记在此处**：这一件的性质与 R1 的 `http.js` 那件**完全相同**（都是「被别的文件的行号移动牵连」），R1 那件当时也是**批准后才长出来的**。

- **（2）路由表结构（`router/index.js:85-124`）**：`/merchant` = 父记录（`MerchantPage`），`children` 6 条 —— `''`→`HomeTab`（`props: { base: '/merchant' }`）、`search`→`GoodsSelect`、`msg`→`ChatRoom`、`order`→`OrderPane`、`order/cart`→`OrderPane`、`user`→`UserCenter`；`user` 自己再带 7 条（`''` / `info` → `UserInfoPage`、`add` → `GoodsAdd`、`list` → `GoodsUp`、`history` → `HistoricalOrders`、`friend` → `FriendRoom`、`chat` → `ChatRoom`）。⚠️ `order/cart` 是**单条多段路径**、**不**嵌第二层 `children`（`OrderPane` 自己渲染两面）。

- **（3）`orderTab` 二态 ⇒ 全路由化（用户裁决 1）**：R2 前是壳里一个 `orderTab` ref + `v-if`/`v-else` 切 `OrderCenter` / `ShoppingCart`（`MerchantPage.vue:31-42`）。R2 后两态**各成一条路由**（`/merchant/order` / `/merchant/order/cart`），靠 `meta.orderTab` 由**包装组件** `OrderPane.vue` 分辨。
  - ⚠️ **为什么用包装组件**（而不是两条路由直接指 `OrderCenter` / `ShoppingCart`）：`<router-view v-slot="{ Component }">` 上的 attrs 会**落到所有子页** ⇒ `orderTab="order"` 会变成 HomeTab / ChatRoom 根元素上的野属性；包装组件是「按 meta 条件分支」的**更直白的等价物**。
  - ⚠️ **`OrderCenter.vue` / `ShoppingCart.vue` 一字未动** —— 两壳共用的 `orderTab` / `showSwitch` / `@toggle` 契约照旧。`showSwitch` 不传 ⇒ 取默认 `true` ⇒ 商家侧开关**显示**（与 R2 前一致）。
  - **代价照实说**：切面 = **路由跳转** ⇒ 卸载重挂、各拉一次订单。⚠️ 但这**不是新行为** —— R2 前 `v-if` / `v-else` 本来就是卸载重挂，两者**逐字等价**；换来的是「刷新 / 深链 / 后退**保留所在面**」。
  - 与前一笔的衔接：客户侧 `/customer/cart` 的 `props: { showSwitch: false }` 是 `fix/legacy-chat-tab` 补的（§5-X (16)2），与商家侧**故意不同**（客户壳从无 `@toggle` 监听 ⇒ 传 false 隐藏；商家壳有开关语义 ⇒ 传默认 true 显示）。

- **（4）`HomeTab` 共享 + 移位（用户裁决 3）**：R1 造的适配器**只有 `HomePage` 是共用件**、适配器却挂在 `views/customer/` 下。R2 把它上移到 `views/HomeTab.vue`、参数化 `base`（由**路由记录**给，不是由壳传）⇒ 两壳共用一份。
  - ⚠️ **`props` 走路由记录的前提**已实测：vue-router **4.6.4** 的 `<router-view v-slot>` 仍把路由记录的 `props` **预绑**到交给 slot 的 `Component`（`vue-router.mjs:1101-1106`）⇒ 客户 / 商家两条记录各给 `props: { base: … }` 即可，两壳组件**零传参代码**。⚠️ 这条机制本就是 `fix/legacy-chat-tab` 修购物车时验过的同一条（§5-X (16)2）。
  - ⚠️ **name 不变**（仍 `HomeTab`）⇒ 两壳的 `CACHE_TABS` **无需分叉**。两个壳的 `<KeepAlive>` 实例**各自独立**，**不会串缓存**。
  - ⚠️ **`MerchantPage.vue:20` 的 `@go-search="goSearch"` 已不存在** —— §5-X (12)5 那句「仍是商家侧的唯一落点」已按预告**就地订正**（该条已划掉 + 按语）。

- **（5）`UserCenter` 共享 + `meta` 驱动（用户裁决 2）**：
  1. **菜单 = 数据**：`meta.subMenu`（客户 4 项 / 商家 6 项）由 `UserCenter.vue` `v-for` 渲染，高亮仍是 `meta.sub`。⚠️ `meta` 沿 `matched` 链**合并** ⇒ 子路由上读到的 `subMenu` 仍来自 `user` 那条父记录，`sub` 被**子**记录覆盖（正是高亮要的）。
  2. **退出 = 同一函数两分支**：`meta.logout === 'merchant'` ⇒ 另加 `clearMerchantId()`；缺省 ⇒ 只 `clearCredentials()`。`UserCenter.vue:52-55`。
  3. ⚠️ **这是语义变更，不只是行号重指**（用户已知情并认可）：`api/auth.js` 里四条按「**两个退出点**」写的注释已按新语义重写成「**同一组件两分支**」（`:146-157` 的 `clearCredentials` 文档块、`:165-168` 的 `clearMerchantId`、`:174-189` 的 `clear()` 文档块）。**「哪个页面清哪个键」的组织方式没变**（客户不清 customerid、商家多清 merchantid），变的是**位置**。
  4. **回归面照实说**：动的是 R1 刚 merge 的 `UserCenter.vue`（客户侧菜单的渲染方式从写死改为表驱动）⇒ 客户侧用户中心的**左菜单**是本笔的风险点；`subMenu` 表与 R1 的四个 `router-link` **逐项对照**：顺序、`to`、`sub`、文案全同。

- **（6）顶栏配置抽到 `src/config/nav.js`（用户裁决 5，R6 预备）**：两壳顶栏实测**同构**（左 1 + 右 3），**唯一差异 = 右起第 2 项**（客户「购物车」⇄ 商家「订单中心」）；红点都在「消息」项。⇒ 抽成 `CUSTOMER_NAV` / `MERCHANT_NAV`，壳里 `v-for` 渲染。⚠️ **按语（§5-Z，R3，2026-10-05）**：本条的「**唯一差异 = 右起第 2 项**」**不准确** —— `MERCHANT_NAV.left.to` 是 `/merchant`、`CUSTOMER_NAV.left.to` 是 `/customer` ⇒ **左项也差**（同一句话在 `src/` 内有 3 处：`config/nav.js:12`、`router/index.js:30`、`CustomerPage.vue:70`）。R3 已把 **3 处全部同行订正**为「差异只有两处：左项 `to` + 右起第 2 项」（**只改注释、零行号漂移**），本条作为 R2 的如实记录**保留原样**。⚠️ **按语（§5-AA，R4，2026-10-05）**：本条按语里写的 `router/index.js:31` **是 R3 自己写错的地址** —— 实测那句「两壳顶栏差异**只有两处**」在 **`:30`**（`:31` 是 R3 的标题行）。**R4 已把 3 处（`config/nav.js:17`、本条、`progress.md:968`）同行订正为 `:30`**（纯地址订正、净零）。见 §5-AA (11)D8。
  - 形态：`{ left: […], right: […] }`，项 `{ label, tab, to, badge? }`；`badge: 'unread'` = 该项显示未读红点（判据 `item.badge === 'unread' && unreadCount > 0`）。
  - ⚠️ **`active` 判据仍是 `route.meta.tab`**，**不是** `route.path.startsWith(item.to)` —— 父路径前缀会让「商城主页」在**所有**子路由上高亮（§5-X 已记过同一条）。
  - ⚠️ **R6 的剩余活**：把两个壳（`CustomerPage` / `MerchantPage`，现在**逐字同构**）包进一个渲染 nav 的 `AppShell`。**布局类名的所有权**仍在两个壳里（`layoutClass` + `meta.layout`，两处实测都在各自文件的 `:80` / `:79`）—— R6 应把「内容区外壳 div」连同类名下移到页组件（§5-X (13)1 的按语）。

- **（7）路径口径变更（自觉登记）**：
  1. `views/customer/` **整个空掉、目录已删**（`git mv` 后 `rmdir`）⇒ §5-X (13)2 / (14) 里「建议全进 `views/customer/`」的旧口径**作废**，两处已加按语。
  2. **R2 起的新口径**：**跨壳共享的页面组件平铺在 `views/`**（`HomeTab.vue` / `UserCenter.vue`）；**单壳专属的进该壳子目录**（`views/merchant/OrderPane.vue`）。
  3. ⚠️ **残留的不一致**：`views/merchant/` 有子目录，却**没有对称的 `views/customer/`**（客户壳没有专属子页面组件）⇒ 归 R6 连同 `AppShell` 一起定案（P 级：纯风格，无行为影响）。

- **（8）`GoodsSelect` 退役 `defineExpose`（用户裁决 4）**：删原 `:163-165`（注释 + `eslint-disable-next-line` + `defineExpose({ searchByKeyword })`），`searchByKeyword` 函数**保留**（`:124` 的 `watch` 在调用它）。
  - ⚠️ **理由已换、结论不变**：R1 时它是「纯增量的**第二条**通道」（商家侧走组件 `ref`），R2 路由化后 `<router-view>` 里拿不到子 ref ⇒ 那条通道**整条消失** ⇒ `?kw=` 通道成为**唯一**通道。
  - ⚠️ **`onMounted`（`back=search`）仍不改 `onActivated`** —— 但 R1 给的理由（「商家侧直挂、无 KeepAlive」）**R2 后失效**，已按「**离开壳会连 KeepAlive 缓存一起销毁**（`/product` 是 `/merchant` 的兄弟路由 ⇒ 壳卸载 ⇒ 回到搜索页必重新挂载）」加按语（§5-X (3) 末）。

- **（9）漂移与前提失效（同笔处理）**：
  1. **兼容守卫合表（用户裁决 6）**：`LEGACY_TAB = { '/customer': {…}, '/merchant': {…} }`（`router/index.js:156`），守卫 `:161`。⚠️ **商家表不带 `cart` / `msg`**（无生产者，收了就是凭空造旧约定）；客户表带 `msg` 是**因为它历史上真有**（R1 前的 watcher 认它）。**两表的差异是有意的**，代码注释里已写明「别对齐掉」。⚠️ R1 那句理由「`MerchantPage` R2 才改」**已随本笔消失**（商家侧现在的生产者是 `ProductPage`，与客户侧同因）。
  2. **生产者实测（R2 复核，7 处不变）**：`ProductPage.vue:361`（`/merchant?page=order`）、`:363`（`/customer?page=cart`）、`:388`（两壳 `?back=search`）、`:398`（`/merchant?page=user`）、`:400`（`/customer?page=user`）、`:447`（`page:'chat'` + `basePath`）、`FriendRoom.vue:246`（`page:'chat'`）⇒ 商家侧要认的键恰好是 `order` / `user` / `chat`。
  3. **ESLint 根因订正**：本仓 `eslint-plugin-vue` = **8.7.1**（实测），其 `vue3-essential` **不带**宏环境；该版本导出的 env 名是 **`setup-compiler-macros`**（无 `vue/` 前缀），且**只含 4 个宏**（`defineProps` / `defineEmits` / `defineExpose` / `withDefaults`）、**没有 `defineOptions`** ⇒ ① `defineOptions` 在 8.7.1 下**必须**逐条 disable（开 env 也没用，要 ≥ 9.13）；② R2 的 `views/HomeTab.vue:37` 新用 `defineProps` ⇒ 同样需要 disable（`:36`）。⚠️ §5-X (3) 那条「R6 之后、C3 之前评估开 `vue/setup-compiler-macros` —— 一次性解决宏的 eslint 报错」**目标只能实现一半**，已加按语（§5-X (15)）。
  4. **前提失效 2 处**（均已就地加按语）：① §5-X (3) 末「商家侧无 KeepAlive ⇒ 不能改 `onActivated`」—— **R2 后商家壳同样有 KeepAlive**，结论改由「离开壳销毁缓存」支撑；② §5-X (8)②「商家 URL 永不带 `kw`」—— **R2 后商家搜索也是 `/merchant/search?kw=`**。
  5. **`ChatRoom.vue:348/367` 的 `page:'chat'` 残留本笔不清**（用户裁决 7）⇒ 归 R4。⚠️ R2 起 `ChatRoom` 在**多出 3 条**路径上也被挂载（`/merchant/msg` 等），判据不变（守卫只认**精确** `/customer` / `/merchant`，而它是 `replace` **当前路径** ⇒ 不触发）。⚠️ **按语（§5-AA，R4，2026-10-05）**：**R4 已清**（同行改写 ⇒ `ChatRoom.vue` 净零 578 行，本条引的 `:348/367` **地址未漂**）—— 本条是 R2 当时的**裁定记录**，保留原样。逐条见 §5-AA (9)。
  6. **R1 缺陷的前置修复**：§5-X (16) 的两处（`chat` 兼容键、购物车 `showSwitch: false`）已由 `fix/legacy-chat-tab`（PR #63）**先行**修掉 —— 本笔的 `router/index.js` 是在**修后**的基线（`999f87e`）上改的，两条修复**随重指一起带过来**（`LEGACY_TAB` 的客户表含 `chat`、`/customer/cart` 记录带 `props`）。

- **（10）逐条重指针（按落码后实测值）**：

| 位置 | R1 快照值（R2 前） | **R2 后的实测值** | 处置 |
|---|---|---|---|
| `auth.js` 三条注释块 | `:146/149/151-153`、`:161-162`、`:171-176` | `:146-157`、`:165-168`、`:174-189` | 按新退出语义**重写**（不再引 `MerchantPage`） |
| `CustomerPage.vue`（§5-X 引到的 8 个锚点） | `:5` `:18` `:19-23` `:53` `:61` `:101-103` `:108-113` `:115-120` | `:5`（nav 起）`:31`（`<div :class>`）`:32`（`<router-view>`）`:67`（`CACHE_TABS`）`:80`（`layoutClass`）`:120`（红点 watch）`:127`（`handlePendingSearch`）`:134`（`onMounted`） | 见 §5-X 各条按语 |
| `MerchantPage.vue`（全部引用） | `:20` `:24` `:113` `:131` `:131-132` `:134` | 壳已重写：`:20`（`@go-search`）/`:24`/`:131`/`:134` **不存在**；`conversations` 调用点 → **`:107`**；新锚点 `:6`/`:18`（nav `v-for`）`:31`/`:32`（内容壳）`:48`（`MERCHANT_NAV` import）`:71`（`CACHE_TABS`）`:79`（`layoutClass`）`:119`（红点 watch）`:126`（`handlePendingSearch`）`:133`（`onMounted`） | `api-contract.md:192` 重指；其余按语 |
| `GoodsSelect.vue` | `:84` `:88` `:91` `:82-90` `:157-161` `:165` `:116` | `:85` `:89` `:92` `:83-91`；`onMounted` `:159-163`；`defineExpose`（原 `:165`）**已删**（现 `:165` 是退役按语）；注释重写（原 `:116` → `:118-121`） | `api-contract.md:178/183/210/214` 重指；`http.js:124` 重指 |
| `UserCenter.vue` | `:24` `:33` `:37` | `:38`（`defineOptions`）`:45`（`subMenu`）`:52-55`（`toLogin`）—— `:24` 的按语与 `:33` 起的注释块已**整体重写** | 重写 |
| `HomeTab.vue` | `views/customer/HomeTab.vue:12` `:15` `:23` | `views/HomeTab.vue:17`（`defineOptions`）`:36`（disable）`:37`（`defineProps`）`:41-42`（`onSearch` 的 `router.push`） | 移位 + 重指 |
| `router/index.js` | `:12` `:35` `:79-81` `:83-92` `:94` `:96` `:106` `:112` `:114-121` | `const routes = [` `:51`；`/` 记录 `:52`；`/customer` `:55`；`/customer/cart`（带 `showSwitch`）`:61`；`/merchant` `:86`；`/goodsSelect` `:128`；`HomeTab` import `:14`；`LEGACY_TAB` `:156`；守卫 `:161`；`ROLE_HOME` `:190`；首页守卫 `:192`；`export default` `:201` | §5-X (10) 的按语指向本节。⚠️ **再按语（§5-Z，R3，2026-10-05）**：本行**四个行号 R3 后未动** —— `:51`（`const routes`）、`:52`（`/` 记录，内容由 `component` 换成 `redirect`）、`:156`（`LEGACY_TAB`）、`:161`（兼容守卫）**逐一对得上**（R3 刻意做成净零：删 `HomePage` import 的 −1，用头部一行 R3 注释补回）。**两个失效** —— `ROLE_HOME` `:190` → **`:192`**；`:192` 的**首页守卫已不存在**（整条 `beforeEach` 换成 `:197` 的 `landingFor`）；`export default` `:201` → **`:202`**。逐条见 §5-Z (9)。 |

- **（11）验证**：`npm run build` **通过** —— **Compiled with 2 warnings**（`entrypoint size limit` + `webpack performance recommendations`，与改前**同数**、均为**既有**）；`npx eslint src --ext .vue,.js` **零输出零报错**（改前基线同样干净）。`dist` 抽查（**只 grep 产物、排除 `.map`**）：`dist/css/app.999ac31d.css` 里 `.user-menu button`（R1 客户壳那份旧选择器，`<button>` → `<router-link>` 后已无对应 DOM）**0 次**、`.user-menu .menu-btn`（共享件那份，`grep -o` 计 2 次 —— 基础规则 + `:hover` 类变体）**命中** ⇒ 样式搬家已进产物、R1 前那种旧规则未残留；`dist/js/app.903afe6c.js` 里 `orderTab` **11 次**、`showSwitch` **5 次** ⇒ 路由 `meta` 值**未被 tree-shake**。⚠️ `MERCHANT_NAV` / `CUSTOMER_NAV` 在产物里 **0 次** —— 这是 **webpack 压缩把模块级标识符改名**所致（配置对象被内联进 setup），**不是**配置没进产物（`orderTab` 可证同类对象在产物里）。手测 **T1–T4** —— ⚠️ **本笔不单独跑**（用户裁定：攒到 R1–R4 全 merge 后一次跑）。

- **（12）不加「更新记录」行**（照 `§5-H (3)` / §5-N / `§5-Q (4)` / `§5-T (7)` 先例）。

---

### 5-Z. R3 `/` 落点收口笔（`feat/router-landing`，2026-10-05）：**「裸挂首页」终结** —— 不破 ≤5（5 文件）

一句话：`#/` 不再裸挂 `HomePage` —— 改成「已登录 ⇒ 角色首页 / 未登录 ⇒ 客户壳」的**落点 redirect**，
同时给两壳顶栏补**未登录形态**（三个「点不动的入口」整体换成「登录」）。

- **（1）性质与台账**。前端 **4 代码文件（3 改 + 1 改）** + 1 文档 = **5 文件**，**0 新文件**，**不破 ≤5**（对账：`router/index.js` + `config/nav.js` + 两壳 + 本节，一件不可压 —— 路由落点、顶栏表、两个壳的取表，四者是同一条链上的四个环）。分支从 merge 后的 `origin/main`（`70f59ae` = #64）切。

| 文件 | 前 → 后 | 要点 |
|---|---|---|
| `src/router/index.js` | 201 → **202** | `:52` 由 `{ path: '/', component: HomePage }` 换成 `{ path: '/', redirect: landingFor }`；删 `HomePage` import；尾段守卫整条换成 `landingFor`；头部加 1 行 R3 注释（**补回删 import 的 −1，行号净零**） |
| `src/config/nav.js` | 34 → **69** | +`ANON_RIGHT`、+`navFor(table, isLogin)`；顺带订正 `:12` 的「唯一差异」 |
| `src/views/CustomerPage.vue` | 236 → **240** | 模板 `:6`/`:18` 同行换 `CUSTOMER_NAV.*` → `navTop.*`；`:47`/`:48` import 同行换；`:70` 注释同行订正；末尾 **+4 行**（空行 + 3 行 `navTop`） |
| `src/views/MerchantPage.vue` | 236 → **240** | 逐字同构的同一组（`MERCHANT_NAV`）；该文件**没有** `:70` 那句，故只 +4 行 |
| `docs/agent/progress.md` | 943 → 本节后 | 新增 §5-Z + 四处按语（§5-V 源句、§5-W (4)、§5-Y (6)、§5-Y (10) 表） |

`git diff --stat`（vs `origin/main`）：代码 **4 files changed, 76 insertions(+), 32 deletions(-)**（文档另计）。

- **（2）路由表结构：一条记录，改成函数 redirect**。**不用 alias、不新增第二条父记录复用 `CustomerPage`**。三条理由：
  - **两条父记录**（`/` + `/customer` 各挂 `CustomerPage`）= 两套 `<KeepAlive>` 实例、两支 5s 未读轮询、两份 `meta` 要同步，且「当前在哪个壳」的判据（`route.path`）分叉 ⇒ 与 R1/R2 的收敛方向相反。
  - **alias**（`alias: '/customer'`）会把客户壳的**规范路径**从 `/customer` 改成 `/`，牵动 `nav.js` 的 `to`、`LEGACY_TAB` 的键（`router/index.js:157`）、§5-Y (9) 整张漂移表 ⇒ 收益只是「URL 短一个词」。
  - **函数 redirect** 顺带把「已登录 → 角色首页」搬到 vue-router 的**惯用位**（记录上的 `redirect`），而不是一条 `beforeEach`。
  - ⇒ 落码后 `router.beforeEach` **全仓只剩 1 条**（`LEGACY_TAB`，`:161`）—— 后面 R4/R6 再理守卫是**更好动**的状态。

- **（3）`/` 进**客户**壳，不进商家壳**。`#/` 的语义是「店门」，对未登录访客就是商城；商家侧是**登录后才有意义**的运营面。⇒ 也回答了「若两者都不进，R3 做什么」：不进壳的话，`/` 这一笔只剩「删 `.login-hint-bar`」（§5-W 已裁定**无需回改**）与「抽 AppShell」（R6）⇒ **R3 会空掉**。「进壳」是 R3 唯一有意义的方向。

- **（4）未登录**能**进 `#/` —— 而且今天就能进壳**。依据（实测，非推断）：全仓 `beforeEach` 只有 **2 处**（`:161` 的 `LEGACY_TAB`、原 `:192` 的落点守卫），**`/customer` 子树一条登录守卫都没有** ⇒ 匿名手敲 `#/customer` 现在就能进壳。R3 只是把**默认落点**也指过去，**不新增任何门槛**。
  - ✅ **匿名落进壳不会触发 401**（实测代码路径）：壳 `onMounted` 的两件事**都是空转** —— `handlePendingSearch()`（无 `PENDING_SEARCH_KEYWORD` 键 ⇒ `:129` `return`）、`fetchUnreadCount()`（`getCurrentUser()` 无 `cid`/`mid` ⇒ `null` ⇒ `:103` `if (!user) return`）⇒ 5s 轮询同样空转。
  - ⇒ 本笔**不给壳加「未登录分支渲染」**（内容区 / `layoutClass` / 轮询对匿名**本来就是安全的**），**只换顶栏表**。
  - `.login-hint-bar`（`HomePage.vue:4`）**不删不改**：它随 `HomePage` 在壳内照旧渲染，位置从「页面最顶」变成「顶栏之下」，视觉上反而更对。§5-W 的裁定原文正是「**无需回改**」。

- **（5）顶栏未登录形态：换表，不换壳**。`config/nav.js` 新增 `navFor(table, isLogin)` —— 未登录返回 `{ left: table.left, right: ANON_RIGHT }`：
  - **左项原样保留**（每个壳的「商城主页」仍指自己的壳）—— 匿名手敲 `#/merchant` 的人，看到的不该是「商城主页 → 客户壳」这种错指。**两壳对称处理**（用户裁决 2）。
  - **右三项整体换掉**：那三项（消息 / 购物车|订单中心 / 用户中心）**每一项都以登录态为前提**，匿名点进去不是挂载即 401 被踢回 `/login`，就是拿到空列表。⇒ 「未登录占位」的做法**从「渲染一个占位页」改成「不渲染」** —— 更彻底，且不新增守卫。
  - **只放「登录」不放「注册」**（用户裁决 3）：全仓**没有** `#/register` 路由 —— 注册是 `LoginPage.vue` 内的滑动表单（`isSlide`），凭空造一条路由 = 凭空造约定（同 `LEGACY_TAB` 两表不收无生产者的键那条口径）。
  - **`tab: 'login'` 不会点亮任何东西**：高亮判据是 `route.meta.tab === item.tab`，而 `route.meta.tab` 永远不是 `'login'`（`/login` 不是任何壳的子路由）⇒ 中性入口。

- **（6）⚠️ 两个登录入口并存 —— 有意的，别当重复代码删一个**。R3 后未登录访客在客户壳首页会同时看到：① **顶栏的「登录」**（导航入口）；② **`.login-hint-bar`**（`HomePage.vue:4`，「未登录 · 点此登录后可搜索 / 购买」）。② 的语义**不止是入口** —— 它是在说明**功能边界**（「登录后**可搜索 / 购买**」，即当前搜索/分类/商品卡是被拦的），而 ① 只是导航。⇒ 二者**不合并**；`HomePage.vue` 本笔**整文件零改动**（443 → 443）。

- **（7）⚠️ 未登录搜索：本笔刻意不放开（用户裁决 1）**。三条守卫（`HomePage.vue:135` / `:144` / `:165`）**原样保留** ⇒ 未登录点搜索 / 分类 / 商品卡**仍然弹「请先登录以使用功能」**。理由：① 放开要改 `HomePage.vue`，破坏 R1/R2 两笔「让它一字不动」的性质（**7 处 inbound / 10 个行号**全漂）；② 后端白名单**确实**放行 `productSearch` / `goodsSelectHome`，但前端拦着是**产品选择**（「先登录再搜」避免「搜到商品 → 点进详情 → 想买 → 再弹窗」的两段式），**不是不一致** —— §5-W 已把「商品页默认让看、只在购买/咨询弹提示」定为基线；③ 混在一笔 = 路由收口 + 首页行为两件事一起审。⇒ **记独立小笔**（建议并入 R5，那里本来就要动匿名购买）。
  - ⚠️ **这也是 §5-W 那条 T2 判据的落点**：R3 之后 T2（未登录点搜索/分类/商品卡仍弹「请先登录」）**必须仍成立** —— 它是本笔「**没把守卫删过头**」的唯一判据。
  - ⚠️ **`emit('go-search')` 落点无需改任何代码**：`#/` 落 `/customer` 后走的就是 R1 那条链 —— `CustomerPage` → `<router-view>` → `HomeTab`（`props: { base: '/customer' }` 由路由记录给，`router/index.js:58`）→ `HomePage`；`HomeTab.vue:41-42` 把 `@go-search` 翻成 `router.push('/customer/search', { query: { kw } })`。

- **（8）守卫搬迁 + ⚠️ 时序耦合注释**。原 `:192-199` 的 `beforeEach` **整条删除**，逻辑搬进 `landingFor`（现 `:197`）；`ROLE_HOME`（现 `:192`）保留 —— C3 加管理员仍**只在本表加一行** `'3': '/admin'`。**唯一的行为变更 = 匿名分支**：`return true`（停在裸页）→ `return '/customer'`（进壳）。
  - ⚠️ **时序耦合注释必须跟着搬**（原 `:178-182`）：原文的理由是「本文件在 `main.js` 顶部被 import ⇒ **守卫在模块加载时注册**，必然早于 `app.use(router)`」；改成函数 redirect 后，**读的时机变成「初始导航解析记录时」**——仍在 `await restoreIdentity()` 之后，**前提不变但理由必须重写**（写成「守卫注册时机」就会指错东西，后人挪了恢复顺序仍会静默坏）。已按新语义重写。这段是全笔**唯一「挪了会静默坏」**的东西：坏了的表现是「已登录也被送进客户壳」，**不报错、不崩**。

- **（9）漂移表（按落码后实测值）**：

| 位置 | R3 前 | R3 后 | 处置 |
|---|---|---|---|
| `router/index.js` `const routes` | `:51` | **`:51`** | **未动**（头部加 1 行补回删 import 的 −1） |
| `router/index.js` `/` 记录 | `:52` | **`:52`** | 行号未动，**内容变**（`component` → `redirect`）；§5-V 源句已加**三次按语** |
| `router/index.js` `LEGACY_TAB` / 兼容守卫 | `:156` / `:161` | **`:156` / `:161`** | **未动**（R3 只改根记录与尾段） |
| `router/index.js` `ROLE_HOME` | `:190` | **`:192`** | +2；§5-Y (10) 表加按语 |
| `router/index.js` 首页守卫 | `:192-199` | **不存在** | 整条换成 `:197` 的 `landingFor` |
| `router/index.js` `export default` | `:201` | **`:202`** | +1（文件 201 → 202，在 ≤±2 内） |
| `CustomerPage.vue` 12 个被引锚点 | `:6 :18 :31 :32 :47 :48 :67 :80 :108 :120 :127 :134` | **逐一命中、零漂移** | 改动全部落在**被引行上或其之后**（末尾 143 行之后） |
| `MerchantPage.vue` 被引锚点 | `:6 :18 :31 :32 :47 :48 :71 :79 :107 :119 :126 :133` | **逐一命中、零漂移** | 同上（末尾 142 行之后） |
| `docs/agent/api-contract.md` | 220 | **220（零改动）** | `:192` 的 `CustomerPage.vue:108` / `MerchantPage.vue:107` **未漂** ⇒ 本笔**不动它** |
| `HomePage.vue` 7 处 inbound / 10 个行号 | — | **零漂移** | 该文件**整文件零改动**（443 → 443） |
| `progress.md` 自指（13 处 `progress.md:NN`） | 最大 `:747` | **零漂移** | 本节插在 `:904` 之后，**全部自指都在其前** |

  - ⚠️ **第 4 条漂移（落码中才发现，超出方案里那 3 条）**：「两壳顶栏**唯一**差异 = 右起第 2 项」这句**在 `src/` 内有 3 处**（不只是方案里写的 `nav.js:12`）—— `config/nav.js:12`、`router/index.js:30`、`CustomerPage.vue:70`。⚠️ **按语（§5-AA，R4，2026-10-05）**：本条的 `router/index.js:31` **是 R3 自己写错的地址**（实测那句在 **`:30`**；`:31` 是 R3 的标题行）—— **R4 已把 3 处同行订正为 `:30`**。见 §5-AA (11)D8。实测 `MERCHANT_NAV.left.to` 是 `/merchant`、`CUSTOMER_NAV.left.to` 是 `/customer` ⇒ **左项也差**。**3 处已全部同行订正**（只改注释、零行号漂移）；§5-Y (6) 那句作为 R2 的**如实记录保留原样 + 按语**。
  - ⚠️ **第 5 条：一处「预存在的假话」，R3 查证但**不修**（登记）** —— `progress.md:401` 的「（差在 **`HomePage.vue:116`** 同行两键、`ProductPage.vue:415`/`:416` 同行两次出现）」**已失效**：实测 `HomePage.vue` **全文 0 处** `loginUsername` / `merchantid`（`grep` 零命中）。**追历史确认了成因**：`loginUsername` 曾在 `9b87ab9` / `2ced01c` 的 `:116`（当时确有 `!!loginUsername || !!merchantid` 两键同行）⇒ 该地址**写的时候是对的**；**笔 3/8（`6639c1f`「用户与登录域 3 个 view 迁到 API 层」）**把这段换成 `:118` 的注释 + `:119` 的 `isLogin = computed(() => isLoggedIn())` ⇒ 两键**从该文件整体消失**，地址自此悬空。⚠️ 即：它**不是「漂移」而是「悬空」** —— §5-W (5) 把 `progress.md:401` 列进「零漂移」清单时，它**当时就已经不对**了（当时的核法只回放行内容、没回放「这行还该不该有这个键」）。⚠️ `auth.js:108` 的同一地址**不算假话** —— 它写的是「**原**唯一**定义在 `HomePage.vue:116`」，`9b87ab9` 可证 ✓。⇒ **本笔不修**。⚠️ **挂账落点与归属（用户 2026-10-05 裁定 —— 必须是有笔名的挂账，不是孤儿待办）**：
    1. **悬空事实**：`progress.md:401` 引的 `HomePage.vue:116`（原文「同行两键」）**已不存在** —— 该文件现在**零处** `loginUsername` / `merchantid`（`grep` 零命中）。
    2. **成因（已追历史）**：`9b87ab9` / `2ced01c` 时 `:116` **确有**那两键（`!!loginUsername || !!merchantid`，地址写得对）；**笔 3/8（`6639c1f`「用户与登录域 3 个 view 迁到 API 层」）**把它换成 `:118` 的注释 + `:119` 的 `isLogin = computed(() => isLoggedIn())` ⇒ 两键**整体消失**、地址自此悬空。⇒ 性质是**「悬空」不是「漂移」**。
    3. **为何本笔不修**：修它必须**重算 54 / 52 的逐出现口径**（那条口径本身是 §5-I / §5-J 的**记账结论**）—— 只改一个引用点、不动算术，等于把记账笔的结论改成**猜**。属记账笔，不属路由笔。
    4. **归属笔名 = `P2.6`（「sessionStorage 直接用」→ 凭据进 httpOnly Cookie）那笔** —— 它本来就要动 `auth.js` 的登录态口径与 `HomePage` 的 `isLogin` 来源，顺手重算一遍最省。**备选归属 = 界面优化笔**（若 `P2.6` 长期不动）。
    6. **【同族第 2 条 —— R4 新发现，并入本挂账】** `api/auth.js:118` 引的 `ShoppingCart.vue:88-93` / `HistoricalOrders.vue:36-41` 称「收敛**逐字相同**的 2 处降级链（customerid → merchantid → loginUsername）」—— **实测那两段已无降级链**（现为 `const loading = ref(false)` + 注释 + `const getBuyerId = () => resolveBuyerId()`）⇒ 与第 5 条**同类（悬空）**、**同一成因**（都是「凭据键被 API 层笔搬走、引用未跟」）。⚠️ **为何并入同一挂账**：那段注释**逐个列出了三个凭据键名** ⇒ 与第 5 条一样牵动 **§5-I / §5-J 的 54/52 逐出现口径**，修它 = 重算同一笔账。⇒ **归属笔名 = `P2.6`**（备选界面优化笔），本笔（R4）**不修**。
    5. 另：`auth.js:108` 的同一地址**不算假话** —— 它写的是「**原**唯一**定义在 `HomePage.vue:116`」，`9b87ab9` 可证 ✓。
  - ⚠️ **第 6 条（R3 自身产生，已就地处置）**：`router/index.js` 的「全仓**没有任何代码 `router.push('/')`**」注释行 `:184` → **`:185`**（尾段改写 +1）。全仓**只有 §5-Z (12) 引它**，已同步改；无第二个引用点。

- **（10）行号纪律：壳的零漂移是刻意设计，不是碰巧**。两壳 12 个被引行号之所以**一个没漂**，靠两条手法：① **同行替换**（模板 2 行 + import 2 行 + 注释 1 行，都是「一行换一行」）；② **新增行落在被引用行的最大行号之后**（客户壳最大被引是 `:134`、商家壳 `:133`，故 `navTop` 写在 `onUnmounted` 之后、`</script>` 之前）。⚠️ 这也正好绕开了「`isLoggedIn()` 不是响应式」这个坑（见 (11)）。

- **（11）⚠️ `isLoggedIn()` 不是响应式 —— 别「顺手」改成 `computed`**。它读的是 `auth.js` 的模块态 + `sessionStorage`（`sessionStorage` 不是响应式依赖）⇒ 写成 `computed(() => isLoggedIn())` **看起来更「正确」，实际永远不更新**。两壳**不需要**它更新：会改变登录态的路径只有四条 —— 登录后 `push` 进壳、退出 `push('/login')`、401 出口 `push('/login')`、`/login` ⇄ 壳 —— **每一条都会重建壳组件**（壳不在任何 `<KeepAlive>` 的 `include` 里，只有 `HomeTab` / `GoodsSelect` 被缓存）⇒ 在 `setup` 里**算一次**（`const navTop = navFor(CUSTOMER_NAV, isLoggedIn())`）就够。

- **（12）兼容层实测：无旧 URL 依赖 `/`，本笔不动兼容层**。R2 后重跑：`push('/')` / `push("/")` / `to="/"` / `replace('/')` 在 `src/` **0 命中**，唯一匹配是 `router/index.js:185` 的**注释**（它自己就在说「全仓没有」；⚠️ 该行 R3 前在 **`:184`**，本笔尾段改写后 +1 —— 这也是**唯一**一处引用它的地方，无其它引用点跟着漂）⇒ §5-X 那句话仍成立。`LEGACY_TAB`（`:156-171`）**一字不动**（键是 `/customer` / `/merchant`，不含 `/`）。
  - ⚠️ **新登记一条口径变化**：`/` 由「页面」变「落点」后，`#/?a=b` 这类**带 query 的根 URL 会被丢 query**（redirect 函数不带 query）。**实测无生产者**（全仓无代码推 `/`），只有手敲会产生 ⇒ 登记，不做兼容。

- **（13）验证**（全部实测）：
  - `npm run build`：**绿** —— `Compiled with 2 warnings`（`entrypoint size limit` + `webpack performance recommendations`，与 R2 **同数、均既有**；**无** `export 'xxx' was not found`）。
  - `npx eslint src --ext .vue,.js`：**exit 0，零输出**（删 `HomePage` import 后无 unused 报错 ⇒ 侧证该文件在 `router/index.js` 内确无其它使用点）。
  - **`dist` 结构性取证**（只 grep `js/app.*.js`、排除 `.map`）：① `path:"/",redirect:Tl` **1 次** ⇒ 根记录已是 redirect，且指向一个**被压缩的函数引用**（不是字符串）⇒ **函数形式生效**；② `path:"/",component:` **0 次** ⇒ 旧的裸挂形态未残留；③ `未登录 · 点此登录后可搜索` **1 次** ⇒ **`HomePage` 仍在包内**（删 import 未把它变成孤儿——它另由 `HomeTab` 引入）；④ R2 基线未回退：`orderTab` **11 次** / `showSwitch` **5 次**（与 §5-Y (11) 记录**逐值一致**）、`商城主页` 2 / `订单中心` 2。
  - ⚠️ **自查纠错**：首轮 dist 抽查忘了排除 `.map`，`orderTab` 量出 **31**（含 sourcemap 的重复）—— 已在报告里更正为排除后的 **11**。这条与 §5-Y (11) 记过的同一个坑同源。
  - 手测 **T1–T4** —— ⚠️ **本笔不单独跑**（用户裁定：攒到 R1–R4 全 merge 后一次跑）。

- **（14）登记新增手测项（留 T 批）**：**T-new-1** 未登录手敲 `#/` ⇒ 应落 `/customer`，且顶栏**右侧只剩「登录」**；**T-new-2** 已登录客户手敲 `#/` ⇒ `/customer`、已登录商家 ⇒ `/merchant`。⚠️ **同时必须复验 §5-W 的 T2**（未登录点搜索 / 分类 / 商品卡**仍弹「请先登录」**）—— 见 (7)。

- **（15）本笔不做**（按裁定）：不动 `ProductPage.vue`（R5）、后端、`ci.yml`、A3 脚本、`src/test/**`；**不抽 `AppShell`**（R6）；`ChatRoom.vue:348/367` 的 `page:'chat'` 残留归 **R4**；僵尸兄弟路由 `/product`（R5，实测 `router/index.js:125` 记录 + 3 处 `{ path: '/product' }` 对象字面量：`ChatRoom.vue:118` / `GoodsSelect.vue:132` / `HomePage.vue:173`，**无字符串 push**）、`/shoppingCart` / `/goodsSelect`（R4，全仓 `push`/`replace` **0 命中**）**不动**；`HomePage.vue` **一字不动**。⚠️ **按语（§5-AA，R4，2026-10-05）**：本条列的「归 R4」三件，R4 的处置是：① `ProductPage.vue` —— **仍是 R5**（`/product` 的 `:125` 裸挂记录**一字未动**）；② ③ `/shoppingCart` / `/goodsSelect` —— **已收编**（同行替换为 `redirect: cartLanding` / `searchLanding`，`:126` / `:128` **地址未漂**）。逐条见 §5-AA (2)(4)。本条作为 §5-Z 落码时的**裁定记录**保留原样。

- **（16）不加「更新记录」行**（照 `§5-H (3)` / §5-N / `§5-Q (4)` / §5-T (7) 先例）。

---

### 5-AA. R4 裸页收编笔（`feat/router-naked-pages`，2026-10-05）：**僵尸裸页终结**

> 笔的位置：R4 是**重构本体最后一笔**（R6 抽 `AppShell` 是纯消重）。用户裁决 D1–D7 全按推荐执行；R5（`ProductPage` 进壳）**不做**（独立 UI 演示）。

- **（1）台账 / 破 ≤5 对账**：**4 文件**（`src/router/index.js` + `src/views/ChatRoom.vue` + `src/config/nav.js` + `docs/agent/progress.md`）、**0 新增文件** ⇒ 不破 ≤5。⚠️ `config/nav.js` 是**超出用户批的 3 文件清单**的第 4 个 —— 理由是 R3 自己写进去的地址 `router/index.js:31` **实测应为 `:30`**（见 (11)D8），属「不留假话」口径下的纯地址订正（**同行替换、净零**），已单独报备。

- **（2）裸页清单（落码前实测 `grep -n "path: '/"`）**：裸挂 **4 条** + 已收口 1 条。

  | 行号（R3 后） | path | component（改前） | 谁在用 |
  |---|---|---|---|
  | `:52` | `/` | —（`redirect: landingFor`） | R3 已收口，**非裸页** |
  | `:53` | `/login` | `LoginPage` | `HomePage.vue:4`/`:90`、`UserCenter.vue:55`、`ProductPage.vue:402`、`main.js:49`（401 出口） |
  | `:125` | `/product` | `ProductPage` | 3 处 `{ path: '/product' }` 对象字面量（`ChatRoom.vue:118` / `GoodsSelect.vue:132` / `HomePage.vue:173`）⇒ **R5 不做，本笔不动** |
  | `:126` | `/shoppingCart` | `ShoppingCart` | **全仓 0 生产者** |
  | `:128` | `/goodsSelect` | `GoodsSelect` | **全仓 0 生产者** |

  ⛔ **全仓没有 catch-all**（`grep -n "pathMatch\|\*\*"` 零命中）⇒ **删记录 = 手敲白屏**（不是 404 页，是空白 + 控制台 warning）。这一条是本笔取舍的**决定性事实**。`router.beforeEach` 全仓仍只剩 **1 处**（`:161` 兼容层）。

- **（3）两条僵尸路由的生产者实测（收编判据，不是印象）**：`grep -rn "'/goodsSelect'\|'/shoppingCart'"` 在 **当前树**与**基线 `9b87ab9`** 各 **0 命中**（唯一同名是后端 `MallManagementGoodsController.java:154` 的 `@PostMapping("/goodsSelect")`，**无关**）。⇒ 两条 URL **从第一天起就没人生产**，只有手敲 / 书签能到。R2 时 §5-Y (3)4 已把它记为「僵尸路由…… 收编归 R4」（本条加四次按语）。

- **（4）为什么是 redirect 而不是删记录**：见 (2) 的 ⛔。R3 对 `/` 的裁定是「手敲也被送出 = 有意的」（§5-Z (7)），本笔沿用同一口径；代价只有 2 条记录。**「不凭空造约定」这条反对理由在此不适用** —— 本笔**没有给兼容表加键**，只是把一条**活着的**（虽然没人链接）裸页换成等价落点，正是 R3 对 `/` 做的事。

- **（5）为什么按角色分（而不是「一律指客户壳」）**：`/shoppingCart` 的商家落点**换组件**（`/merchant/order/cart` 渲染的是 `OrderPane` 的 cart 面）、`/goodsSelect` 的商家落点**换壳**。恒指客户壳会让商家手敲落错壳 —— 正是 R3 的 `landingFor` 刻意避开的错。判据与 `landingFor` **同口径**：`role` 优先、`getMerchantId()` 兜底（新标签页恢复时 `getRole()` 拿不到 ⇒ **走兜底是常态**）。匿名 ⇒ 两个都拿不到 ⇒ `'1'` ⇒ 落客户壳，与 §5-Z (4)「未登录也进客户壳」一致。

- **（6）新增 `ROLE_CART` / `ROLE_SEARCH` / `roleKey()`（`router/index.js:217-227`）**：`ROLE_CART = { '1': '/customer/cart', '2': '/merchant/order/cart' }`、`ROLE_SEARCH = { '1': '/customer/search', '2': '/merchant/search' }`。⚠️ **`cartLanding` / `searchLanding` 必须是函数声明、不能写成 `const` 箭头** —— `const routes` 在**模块体**里就求值（记录上的 `redirect: cartLanding` 是**读这个标识符**），箭头函数此刻还在 TDZ ⇒ `ReferenceError: Cannot access 'cartLanding' before initialization`。`landingFor` 之所以能「先用后定义」就是因为它**是函数声明**。⚠️ **不合并进 `landingFor`**（用户裁决 D7）：会动 R3 刚落码登记的 `:192` / `:197`；**登记为 R6 可选**。

- **（7）`to.query` 透传（本笔与 R3 的唯一手法差别）**：`redirect: (to) => ({ path, query: to.query })`。R3 丢弃 `/` 的 query 是因为**无生产者**；这里 `kw` 有真实语义（`GoodsSelect.vue:124` 的 watch 读 `route.query.kw`）⇒ 手敲 `#/goodsSelect?kw=手机` 能落到搜索页并生效。

- **（8）死控件第三例（附带发现，从基线就在）**：裸 `/shoppingCart` 渲染的 `ShoppingCart` 取 `showSwitch` 的**默认 `true`**（`ShoppingCart.vue:81`），而该记录**不传 `@toggle`** ⇒ 那个 📋/🛒 开关**点了没反应** —— 与 §5-X (16)2（R1 漏传 `:showSwitch="false"` 凭空造死开关）是**同一类病症的第三例**。收编后该裸挂形态消失 ⇒ 一并终结。

- **（9）`ChatRoom.vue:348/367` 的 `page: 'chat'` 残留 —— 清法：同行改写（用户裁决 D4）**：`router.replace({ query: { ...rest, page: 'chat' } })` → `router.replace({ query: rest })`。**不删行** ⇒ `ChatRoom.vue` **578 → 578 行（净零）**，`api-contract.md:192-195` 的 5 个 ChatRoom 地址、`progress.md:752` / `:787`、`CustomerPage.vue:65` / `MerchantPage.vue:69` 引的 `ChatRoom.vue:369` **全部零漂移**（这就是不删行的**决定性理由**）。⚠️ **语义零变更**：`rest` 仍照旧剥掉 `otherId` / `otherType` / `_t`；两个 `eslint-disable-next-line no-unused-vars` 仍需要（三个键还是解构未用）。⚠️ **不成环**（推理核过，非手测）：`replace` 后 `route.query.otherId` / `otherType` 都变 `undefined` ⇒ `watch` 的 `if (newOtherId && newOtherType)` 守卫不成立 ⇒ **不再 replace**。**删后 URL** = 当前路径 + **无 query**：`/customer/msg`、`/customer/user/chat`、`/merchant/msg`、`/merchant/user/chat` 四种（四条 ChatRoom 挂载点）。⚠️ **判据**：全仓 `grep "route.query.page"` **零命中** ⇒ 纯残留；`LEGACY_TAB` 只认**精确** `/customer` `/merchant` 而 `replace` 不动路径 ⇒ 两边互不触发（与 §5-X (12)1 / §5-Y 的登记一致）。

- **（10）落码后实测数值（供复核）**：`router/index.js` **202 → 241 行**（两条记录**同行替换** + 尾部插入 39 行）；`ChatRoom.vue` **578 → 578 行**；`config/nav.js` **同行替换（净零）**。R3 登记的锚点 **`:51` / `:52` / `:125` / `:126` / `:128` / `:156` / `:157` / `:161` / `:185` / `:192` / `:197` 逐个回放，全部未漂**；只有 `export default router` 由 `:202` → **`:241`**（全仓**无**以 `:202` 为地址的引用，仅 §5-Z (10) 与 `progress.md:654` 以叙述句提过）。

- **（11）新发现（本笔核实中翻出，都不是本笔造的）**：
  - **D5 —— `§6-A` 的 **P1.3** 行引 `ChatRoom.vue:170`，实测 `:169` ⇒ 漂移 1（已就地订正）**：写这句的 `6afaac2`（2026-10-03）**当时是对的**（基线 `9b87ab9` 的 `return data.user` 在 `:170`），**笔 4/8（`5990d07`，2026-10-04「消息与好友域 4 个 view 迁到 API 层」）把它搬到 `:169`**、文档未跟。⚠️ 与 §5-Z (9) 第 5 条那处**同类不同因**：那处是**悬空**（写的时候就已经不在），这处是**漂移**（写的时候在、后被搬走）。
  - **D8 —— `router/index.js:31` 应为 `:30`（R3 自己写进去的，已就地订正）**：R3 记「同一句话在 `src/` 内有 3 处：`config/nav.js:12`、`router/index.js:**31**`、`CustomerPage.vue:70`」—— 实测那句「两壳的顶栏差异**只有两处**」在 **`:30`**（`:31` 是 R3 的标题行「`/` 由『裸挂 `HomePage` 的页面』改为『一条落点 redirect』」）。**3 处**（`config/nav.js:17`、`progress.md:867`、`progress.md:968`）**全部同行订正为 `:30`**。
  - **D9 —— `api-contract.md:192-195` 的 ChatRoom 四个地址陈旧（登记，不修）**：实测调用点 `:167`（userSelect）/ `:183`（conversations）/ `:208` + `:270`（history ×2）/ `:314`（send），而该表记 `:164` / `:184` / `:213` + `:279` / `:327`。⚠️ 其中 `:184` → `:183` **已登记**于 §5-V (5)3 / R1 的 §5-X (10)3（「R1 之外的前置漂移…… 不重开那笔账」）；**其余 3 个（`:164` 指注释、`:213`/`:279`、`:327`）本笔新发现、同样不修** —— 承 R1 已立的口径（改 `api-contract.md` 全表属**契约表重测笔**，不属路由笔）。⇒ **登记**，归属笔名待定（与 (11)D5 的 `6afaac2` 同族：都是「笔 4/8 之后契约表未重测」）。
  - **非漂移但记录在案（「注释锚点」一类）**：`GoodsSelect.vue:158`（`router/index.js:152` 引）与 `ChatRoom.vue:164`（`api-contract.md:195` 引）指的是**描述该行为的注释行**（代码分别在 `:160` / `:167`）—— 不是假话，**建议保留**（同 §5-W 那处注释锚点的先例）。

- **（12）验证（落码后、commit 前）**：
  - `npm run build` **绿**（警告 **2 条**，与 R3 基线**同数**，均为既有项）；
  - `npx eslint src --ext .vue,.js` **exit 0**、零 lint 输出；
  - **行号回放**（全仓 `router/index.js:N` / `ChatRoom.vue:N` 引用逐个回放实测行内容）：**全部指对**（本笔按净零设计 ⇒ 预期零漂移，实测一致）；
  - **dist 结构性取证**（排除 `*.map`）：`path:"/shoppingCart",redirect:` × **1**、`path:"/goodsSelect",redirect:` × **1**、**两者 `,component:` 各 0**（裸挂形态消失）；`redirect:` 全产物 **3 处**（= `/` + 两条收编，**均为函数引用** ⇒ 生效）；`path:"/product",component:` / `path:"/login",component:` 各 **1**（R5 与登录页**未被波及**）；`page:"chat"` **4 → 2**（剩下 2 处正是 `FriendRoom.vue:246` 与 `ProductPage.vue:447` 两个**合法生产者**）；`orderTab` **11** / `showSwitch` **5**（R2 基线，无回归）；
  - `git diff --stat` + 净零核查（见 (10)）。⚠️ 用户方案里的 `refcheck.sh` **全仓不存在**（`find -name "*refcheck*"` 零命中）⇒ 「行号回放」是**按同一口径手工做的**（grep 抽全部引用 + awk 回放实测行），登记此差异以免误以为脚本跑过。

- **（13）登记新增手测项（留 T 批，攒到 R1–R4 全 merge 后一次跑）**：
  - **T-new-3**：手敲 `#/shoppingCart` ⇒ 未登录**应落 `/customer/cart`**、商家登录**应落 `/merchant/order/cart`**；且**不应**出现那个点了没反应的 📋/🛒 开关（(8) 的死控件应已随裸挂形态消失）。
  - **T-new-4**：手敲 `#/goodsSelect?kw=<词>` ⇒ 应落对应壳的搜索页**且关键词生效**（query 透传，(7)）；手敲不带 `?` 时落点相同、不报错。

- **（14）本笔不做**（按八条裁定）：不动 `ProductPage.vue`（R5）、`LoginPage.vue`（保持独立）、后端、`ci.yml`、A3 脚本、`src/test/**`；不抽 `AppShell`（R6）；**不合并 `landingFor` 与 `roleKey`**（D7）；`/product` 的 3 个生产者**一字不动**。

- **（15）与 R6 的关系**：R4 后 `router/index.js` 剩三件**有意保留**的事 —— ① `/product` 裸挂（`:125`，R5 不做 ⇒ 永久）；② `LEGACY_TAB` + `beforeEach`（`:156-171`；理由 ① 依赖 R5、理由 ②「书签 / 历史 / 手敲不受代码控制」永久）；③ 两壳父记录的**结构性重复**（只差 component / `subMenu` / `logout`）。R6 抽 `AppShell` 时 router 的改动量 = **两条父记录换 `component` + 补 `props`（nav 表 / `base` / `CACHE_TABS`）+ 一处 import**，估 **10~20 行**、不新增记录、不动 `children` ⇒ R4 **不给 R6 添堵，也不替 R6 抢活**。
- **（16）不加「更新记录」行**（照 `§5-H (3)` / §5-N / §5-Q (4) / §5-T (7) 先例）。

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
| **P1.3 message 10 端点形态** | **10/10 全 `Map`**（无 String/null）；在用 9 个（`getOrCreateConversation` 无调用方） | **A** | 顶层 `success`/`msg`/`list`/`user` **一个都不能挪进 `data.*`**（`ChatRoom.vue:169` 直接 `return data.user`；⚠️ **R4 顺带订正**：原记 `:170` —— 写这句的 `6afaac2`（2026-10-03）当时**是对的**（基线 `9b87ab9` 的 `return data.user` 在 `:170`），**笔 4/8（`5990d07`，2026-10-04）把它搬到 `:169`**、文档未跟 ⇒ 属**漂移**不是错记，见 §5-AA (11)） | 笔 2 |
| **P1.4 `goodstype` 双形态** | GET 无参→读 `list`（`GoodsAdd.vue:135`）；POST 带参→**不读响应**（`GoodsAdd.vue:166` fire-and-forget）；`HomePage.vue:154` 用 **POST 无参**→读 `list` | **A** | 两条路径都读**顶层** `list`；POST 无参分支须继续返 `{success,list}`。**已在笔 2 例外清单**（签名不动） | 笔 2 |
| **P2.4 `success`/`msg` 读点** | `.success` **34 处**真实判定（另 3 处是 CSS 类 `.success-cover`/`.success-text`，非判定）；`.msg` **17 处** | **A** | 笔 2 的「JSON 字节等价」**由这 51 处定义** —— 顶层 `success`(bool)/`msg`(string) 键名与语义逐字不变 | 笔 2 |
| **P1.1 无 `res.ok` 检查** | `res.ok`/`res.status`/`.statusText` 全 src **0 命中** | **B** | 前端**从不看状态码**，只读 body 的 `success` ⇒ 笔 3 若引入 4xx/5xx，前端**静默无感**（不报错、也不提示） | 独立笔 |
| **P1.5 OPTIONS 预检** | `ImgConfig.CorsFilter`：`addAllowedMethod("*")`（Spring `checkHttpMethod` 对 `"*"` 直放行 ⇒ **含 OPTIONS**）、`addAllowedHeader("*")`（⇒放行 `Access-Control-Request-Headers`）、`addAllowedOriginPattern("*")` + `allowCredentials(true)`、`maxAge=3600` | **C** | **已覆盖，无需改**；且前端多为 `application/x-www-form-urlencoded`（simple request）⇒ 预检基本不触发 | — |

> **P1.3 逐条**（10 端点 → 前端读的字段）：`conversations`→`success`+`list`（`ChatRoom:184`/`CustomerPage:89`（R1 重指；原记 `:97`）/`MerchantPage:113`；⚠️ **按语（§5-Y，R2，2026-10-05）：这两个壳的调用点 R2 后为 `CustomerPage:108` / `MerchantPage:107`**）；`history`→`success`(+`list`)（`ChatRoom:213`/`:279`）；`send`→`success`+`msg`（`ChatRoom:327`/`ProductPage:459`）；`userSelect`→`success`+`user`（`ChatRoom:164`/`FriendRoom:132`）；`friendAdd`→`success`+`msg`（`FriendRoom:159`）；`selectFriend`→`success`(+`list`)（`FriendRoom:194`）；`friendUp`/`friendDe`→`success`+`msg`（`FriendRoom:231` 变量 URL）；`friendSelect`→`success`(+`list`)（`FriendRoom:107`）；`getOrCreateConversation`→**无调用方**。

### 6-B. 前端债（归一笔独立的「前端 API 层规范化」）

| 项 | 分布 | 归属 | 验收判据 | 依赖 |
|---|---|---|---|---|
| **P2.1 硬编码 URL** | `http://localhost:8088` **51 处**（14 文件）= **42** fetch 调用（39 字面 + 3 变量：`GoodsSelect.vue:81`/`OrderCenter.vue:129`/`FriendRoom.vue:231`）+ **6** 条 URL 赋值行（`GoodsSelect:74/78`、`OrderCenter:125/127`、`FriendRoom:226/228`）+ **3** 图片 `src`（`FriendRoom:71`/`GoodsUp:9`/`UserInfoPage:7`）+ **3** CSS `url()`（`LoginPage:9/10/274`）；分布：ProductPage/FriendRoom 各 **7** 最多，CustomerPage/HistoricalOrders/MerchantPage 各 **1**；⚠️ **按语（§5-X，2026-10-05）**：本行是 **2026-10-03 审计的基线快照**（`#48` 已把 **51 → 3**，见 `progress.md:81`；`api-contract.md` 全表同病、已登记于 §5-V (5)3）；其中 `GoodsSelect` 的三个指针 R1 后为 **`:91` / `:84` / `:88`**（原记 `:81` / `:74/78`），**其余指针本笔不动**（非 R1 下游）；⚠️ **再按语（§5-Y，R2，2026-10-05）**：`GoodsSelect` 那三个指针 R2 后为 **`:92` / `:85` / `:89`**（R1 记 `:91` / `:84` / `:88`），`MerchantPage` 的「各 1」计数与本行其余指针**本笔不动**（非 R2 下游；`api-contract.md` 的 4 处已随 R2 重指，见该文件 `:178/183/210/214`） | **B** | 收敛到 base URL 常量 + `.env`；与 `TD-06`（`tech-debt.md:90`，图片 URL 前缀硬编码）**同源** | 独立笔 |
| **P2.2 无 API 层** | `src/` 仅 `App.vue`/`components`/`main.js`/`router`/`views`；无 `api/`/`services/`/`utils`；`axios`/`request(` **0 命中**；全部原生 `fetch(` | **B** | **P2.1/P2.3/P2.5/P2.7 的根因** —— 建层后其余四项收敛到一处 | 独立笔（**最先**） |
| **P2.3 catch 错误点** | `try` **40** / `catch` **41**，覆盖**全部 42** 处 fetch（`ProductPage.vue` 2 个 try 各含 2 fetch）；全为 `console.error`/`alert`，无统一出口 | **B** | 收敛到 API 层统一 catch；`alert` 文案不属接口契约，可改 | 依赖 P2.2 |
| **P2.5 环境变量** | `.env` / `VUE_APP_*` / `process.env` 全 src **0 命中**；`vue.config.js` **无 `proxy`** | **B** | 与 P2.1 同笔：base URL 走 `VUE_APP_*` | 依赖 P2.2 |
| **P2.6 sessionStorage 直接用** | **71 处** API 调用 / **14 文件**（MerchantPage **9** 最多；⚠️ **按语（§5-Y，R2，2026-10-05）：R2 把商家壳重写后该计数**必然下降**（壳里只剩 `conversations` 一处 `sessionStorage` 之外的调用路径，`curPage`/`goSearch` 等已删）—— 本行是 **2026-10-03 的基线快照**，**未重测**，别当现值用**）；key：`merchantid`/`customerid`/`loginUsername`/**`loginPassword`（明文）**/`SEARCH_KEYWORD`/`SEARCH_RESULTS`/`PENDING_SEARCH_KEYWORD`/`FROM_PAGE` | **B** | ⚠️ **含独立安全债**：`loginPassword` **明文入 sessionStorage**（写：`LoginPage.vue:164/169`；读：`GoodsUp.vue:146`、`UserInfoPage.vue:92`），且被当凭据回传 `getUserInfo`/`updateUserInfo` —— 规范化笔须**单列**这条 | 独立笔（可并行） |
| **P2.7 无超时/重试** | `AbortController`/`signal`/`timeout`/`retry` 全 src **0 命中** | **B** | API 层加超时 + 有限重试 | 依赖 P2.2 |

### 6-C. 归类汇总

- **A 类（公共层笔强制处理）——4 项**：P1.2、P1.3、P1.4、P2.4。
- **B 类（独立笔「前端 API 层规范化」）——7 项**：P2.1、P2.2、P2.3、P2.5、P2.6、P2.7、P1.1。
- **C 类（不规范但无 bug）——不列**：P1.5（OPTIONS 预检已覆盖）等。

> **本节用法**：A 类 4 项是**公共层笔 2/3 的硬约束** —— 笔 2 落地前先跑「旧 `Map` vs 新 `Result<T>` 响应体**逐字（取字节）比对**」，判据即 6-A 的 P1.2/P1.3/P1.4/P2.4；B 类 7 项归一笔**独立**的「前端 API 层规范化」，**不在公共层三笔内**（边界：公共层三笔不动前端）。
