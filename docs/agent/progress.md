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
| 2026-10-04 | **鉴权笔 1/3 —— 拦截器 + 白名单 + 401 出口**（`feat/auth-gate`）：`shared/web/AuthInterceptor`（开关关 → 放行／白名单 → 放行／会话有 `userId` → 放行／否则 **401 + `{"success":false,"msg":"未登录"}`** 且**不进 Controller**）+ `shared/web/WebAuthConfig`（注册在 `/**`，**故意不用 `excludePathPatterns`** ⇒ 白名单唯一出处是 `AUTH_WHITELIST`）+ 开关 `app.auth.enabled` **默认 `false`**（⚠️ **承重**：写死 `true` 当场红 **29** 条；⚠️ **按语（§5-AD，翻开关笔，2026-10-05）**：该「默认 `false`」**已于 2026-10-05 翻成 `true`**，「承重」改由两处 profile 显式 pin `false` 承担 —— 本行是当日如实记录，**保留原样**）+ 白名单 **12 条**（业务 8 + 基础设施 4）+ 单测 10 条；文档同步 **4 文件 6 处**（组 A）；新挂 **§5-O** 3 条（组 B，本笔之前就过时）。⚠️ **只做「拦」，不做「认」** —— 认证侧留笔 2 | #53 |

> ⚠️ **表格欠账的现状**：`C#2` 那一行原记 `本笔`，已由 **PR #42 合并后的 `docs/progress-42-record` 一笔补成 `#42`** —— 与 **#11 的做法相同**（#11 的记录也是合并后另起一笔 `37cafc3` 补的 `#40`）。当初为何不直接填：那一笔的文档与代码在**同一笔**落盘（先出 diff → 用户核 → commit → 开 PR），commit 时 PR 号尚不存在。仍欠的**只剩三处**：**2026-10-02 / 2026-10-03 两行**的 `+ 本笔`，以及本表**新增的 `2026-10-04 B#1` 行**（同理：该笔的文档与代码同笔落盘，commit 时 PR 号尚不存在；补法与 #42 同 —— 合并后另起一笔）。

## 1. 项目级阶段

| 阶段 | 内容 | 状态 | 证据 |
|---|---|---|---|
| **A 阶段** | A1 计划 / A2 基建 / A3 并发脚本 | ✅ | M1（`tech-debt.md:246`，2026-09-18）、M2（`:265`，2026-09-20）；PR #2 #3 |
| **B 阶段** | B1 两级缓存 / B2 Redisson 锁 / B3 状态机 + 异步时间线 | ✅ ⚠️ **挂 4 条，见 §5-A** | M3（`tech-debt.md:283`，2026-09-29，PR #4/#6）、M4（`:302`，2026-09-30，`b226357`） |
| **C1** | Micrometer + Actuator 埋点 | ✅ | 出处 `selection-status.md:18`：21 个 `mall.*` 指标（11 cache + 4 lock + 6 order.state）；`aab3b9d` / PR #5 `3f8c4b7` |
| **C2** | SSE 实时日志 | ⏳ **未开始** | `亲读`：全仓 grep `SseEmitter` / `text/event-stream` / `WebFlux` = **0 命中**。计划出处 `archive/worklog-2026-09-20-a3.md:238` |
| **C3** | Vue3 管理端 | ⏳ **未开始** | `亲读`：前端 **14 个** view（`Mall-management-web/mall-management-system/src/views/`，2026-10-02 复测 `ls` 计数；原记 16，与 `project-map.md:68` 的 14 不一致，已按实测订正）无管理端入口。计划出处 `:239` |
| **三层重构** | 四域（订单 / 商品 / 用户 / 消息）Controller → Service → DAO + 公共层（CORS / `Result<T>` / 全局异常 + 写接口校验） | 🟡 **四域 4/4 ✅；公共层部分推进，实剩 `D#5` 配置收敛** | M5（`tech-debt.md:339`，2026-10-04，**PR #25–#46**） |

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
| **公共层** | `shared/web`（`GlobalExceptionHandler` / `Result<T>`）、`shared`（CORS 收口） | — | 🟡 **部分推进，实剩 `D#5`（2026-10-04 订正）**：`D#2` 全局异常出口 ✅（#36 `573463c` + `1bd05d3`）、`D#3` 的 CORS 面 ✅（#34 `23f5361`，删 5 处冗余 `@CrossOrigin`）+ 统一响应体 `Result<T>` ✅（#35 `ea2bf02` + `321773e`，28 端点）+ **`C#6` 状态位拦截 ✅（PR #45 `473b6c1`，见 §5-G）**；**未做**：~~`C#2` 事务边界~~（**2026-10-04 落地**，`fix/goods-write-tx`；`GoodsService` 三处写方法各加 `@Transactional` + catch 内带活跃事务判断的 `setRollbackOnly`，单测 ×6）/ ~~`C#6` 状态位拦截~~（**2026-10-04 落地并已合并**，**PR #45**，`2e9d3f8`）/ **`D#5` 配置收敛**（⚠️ **2026-10-06 追加（CORS 收窄笔，§5-AH）**：`D#3` 的**通配风险面**亦已关闭 —— `ImgConfig` 的 origin 侧由 `addAllowedOriginPattern("*")` 改为 **字面量白名单** `addAllowedOrigin(String)`（键 `app.cors.allowed-origins`；配置含 `*` ⇒ **启动失败**）⇒ `tech-debt.md` 的 **TD-04 由「🟡 部分关闭」转「✅ 已关闭」**，本节这行原写的「`D#3` 的 CORS 面 ✅」当时只覆盖**重复配置面**） |

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
- ⚠️ **文档悬空引用 3 条（2026-10-05 挂账；归属笔名 = `P2.6` 那笔，备选界面优化笔）**：① `progress.md:401` 引的 `HomePage.vue:116`（原文「同行两键」）**已悬空** —— 笔 3/8（`6639c1f`）把该文件的 `loginUsername` / `merchantid` **两键整体换掉**（现为 `:118` 注释 + `:119` 的 `isLogin = computed(...)`）⇒ 该文件现在**零处**凭据键；② `api/auth.js:118` 引的 `ShoppingCart.vue:88-93` / `HistoricalOrders.vue:36-41`（原文「收敛**逐字相同**的 2 处降级链」）**已悬空** —— 那两段现在只剩 `getBuyerId = () => resolveBuyerId()`。**两条都不是漂移，是悬空**（§5-W (5) 当时按「回放行内容」核，没核「这行还该不该有这个键」）。⚠️ ② 是 **R4 核实中新发现**。**为何不修**：两条都要重算 54/52 的逐出现口径（§5-I / §5-J 的记账结论）。**成因 / 归属 / 完整判据见 §5-Z (9) 第 5 条（① = 第 5 条本体、② = 其第 6 点）。** ⚠️ **③（R6 核实中新发现 = §5-AB (12) 的 D-new-1）**：`api/auth.js:44` 的**现在时**陈述「退出（**`CustomerPage` / `MerchantPage`** / `http.js` 的 401 出口）」**已悬空** —— R1 把客户退出、R2 把商家退出**都迁到了 `UserCenter.vue`**；**同一文件 `:146` 自己就写着这件事** ⇒ 本条是**自相矛盾**（`:44` 说「现在在两个壳」、`:146` 说「R1/R2 后都迁到 `UserCenter.vue`」）。⚠️ 各笔此前只核了自己改的文件 ⇒ 漏到 R6 才发现。**本笔不修**（用户裁决 D6：归 `P2.6`，与 ①② 同族 —— 都要动 `auth.js` 口径）。 ⚠️ **另挂一条待定（R6 裁决 D10）**：R4 遗留的「合并 `landingFor` 与 `roleKey`」（R4 的 D7 曾记为「R6 可选」）**移出 R6、改挂本段待定笔** —— 合并会动 R3 刚落码登记的 `router/index.js:192` / `:197`，且与 AppShell 无关（属 router 笔）。 ⚠️ **三条已全部结清（P2.6，2026-10-05，见 §5-AC）**：① 在 `progress.md:402` **就地**加按语（`HomePage.vue:116` 标为**历史地址**）；② 在 `src/api/auth.js:118` **就地**加按语（那两处现只剩 `getBuyerId = () => resolveBuyerId()`）；③ 在 `src/api/auth.js:44` **就地**改成 `UserCenter.vue`（原写两个壳）。

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
| `UserController.java:25` | 「**三层前** `loginAdd` 上挂着全仓**唯一一处真** `@Transactional`」 | 句首「三层前」把它**限定为史料**，作为史料仍真 | 可不动；若要防误读，在「唯一一处真」后加「（**当时**；2026-10-04 起为 4 处）」 |
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
- **（3）更新记录表仍欠三处 PR 号 —— 已挂账，无需另补。** 该欠账由**表下注自身**（`## 更新记录` 表下注）承担：仍欠 **2026-10-02 行 / 2026-10-03 行的 `+ 本笔`** 与 **`B#1` 行**（应填 `#44`），补法 = **合并后另起一笔**（同 #42 的 `d79ad96` / #11 的 `37cafc3`）。**本笔不加更新记录行** —— 那会让欠账从 3 处变 4 处，而 M5 的落点已在 §1 表新行与 `tech-debt.md:339` 各自可查。⚠️ **本笔已知副作用一处**：§1 表新行使本文件**其后所有行号 +2** ⇒ 文内既有的**行号自指**（`:82` 除外，它在 §1 之前）**整体偏移 2 行**，且它们**本就有偏差**（例：§3 表下的 TODO 原写「`progress.md:141-143` Controller 行数快照」，而 `:141-143` 指的是**另一段文字**）—— 建议**另起一笔**把这类自指改成**节锚点**（`§1.1` / `§3` / `§5-G`）而非行号。⇒ ⚠️ **2026-10-04 已由 `docs/progress-53-record` 落地，见 §5-P。**

### 5-I. `#48`（`fix/frontend-api-layer`，2026-10-04）登记：**已知错误 2 处（51 分解、52 vs 54）**

收尾写更新记录行时复核数字，发现 **#48 自身产物有 2 处数字错**（均为**我在 #48 里写错**的，非他人）：

- **（1）51 行的分解错。** `.env.production` 的注释、#48 的 PR body、commit `8118741` 都写「51 = **42** 处 fetch 字面量 + 6 处 URL 赋值 + **3** 处 `:src` 头像」。**逐行实测**分解 = **39** fetch 字面量 + **6** URL 赋值行 + **3** 处头像 URL（2 处 `:src`：`GoodsUp:9` / `UserInfoPage:7`；1 处 `const defaultAvatar`：`FriendRoom:71`）+ **3** CSS `url()`（`LoginPage:9/10/274`）= **51**。原式**双算了 3 处**（那 3 个走变量 URL 的 fetch，其字面量正落在「6 处 URL 赋值行」内）、**又漏了 3 行 CSS** —— `42+6+3=51` 只是数字巧合。⚠️ **§6-B 的 P2.1 行本身是对的**（「42 fetch 调用（39 字面 + 3 变量）」），错的是 #48 对它的转述。
- **（2）登录态 `sessionStorage`：`52` 与 `54` 是两套口径，`54` 才对得上 §6-B。** 逐出现实测 **54**（= `merchantid` **24** + `customerid` **12** + `loginUsername` **12** + `loginPassword` **6**），与 §6-B 的 P2.6「**71** 处 = **54** 凭据 + **17** 页面状态键」同口径；`8118741` 与 #48 的 PR body 写的 **52** 是另一套口径——「**行×键**」去重 = `merchantid` 23 / `loginUsername` 12 / `customerid` 11 / `loginPassword` 6（差在 `HomePage.vue:116` 同行两键、`ProductPage.vue:415`/`:416` 同行两次出现）；还有第三个数 **51** = 涉及凭据的行数。⚠️ **`52` 的病不在值，在被拿去与 `17` 相加对 `71`** —— 两口径混用（`52+17=69≠71`）。**本仓一律读逐出现的 `54`**；同一笔内 `489800d` 写的正是 **54**。 ⚠️ **按语（P2.6，2026-10-05，§5-AC）**：本句引的 **`HomePage.vue:116`（原文「同行两键」）已悬空** —— 笔 3/8（`6639c1f`）把该文件两键整体换掉 ⇒ 该地址是**历史地址**（写成时对、后失效，§5-Z (9) 第 5 条）；**今日**登录态 = **4 键**（登录密码键**归零**），故 54 / 6 是**基线历史值**，不是现状。
- **处置**：`.env.production` 注释**已订正**（纯注释、**行为零变更**）；**#48 的 PR body 已订正**（GitHub 上直接改，无需 commit）；**`8118741` 的提交信息未修** —— 改它须改写 #48 已合并的历史，**不做**。⚠️ 残留：`8118741` 的 message 里那两个错数**永久留痕**，引用该 commit 的说明时按本条的 **39 / 54** 读。**另**：`src/api/auth.js:3-8` 的头注释**同病**（写 `52`，且把 `52` 与 `17` 相加去对 `71`）—— **本笔已订正为逐出现的 `54`**，并注明 `52` 是「行×键」口径（**纯注释、行为零变更**）。
- ⚠️ **副作用一处**：本次给更新记录表加的行使本文件**其后行号 +1**（与 §5-H (3) 记的「§1 表使其后 +2」叠加）⇒ 文内**行号自指**再偏移 1 行；建议同 §5-H (3)，改用**节锚点**而非行号。⇒ ⚠️ **2026-10-04 已由 `docs/progress-53-record` 落地，见 §5-P。**

### 5-J. 鉴权笔（`BD-03`）开工前调研 —— 登记 3 条**发现** + 1 条**裁定**（`P2.6` 归属）

> **来源**：为 `BD-03` 出方案做的**只读**调研（后端鉴权面 / A3–CI 影响面）。**本节只登记，未改任何代码。**
> `BD-03` 的**裁决**、选 A 的**理由更新**、落地拆笔、A3 影响面、文件数预估**不在本节** —— 归后续「`BD-03` 落裁笔」。

- **（1）`AbstractSmokeTest.java:45` 计数过时。** javadoc 写「**5 个** smoke test 无需改动」，实测 **8 个**类 `extends AbstractSmokeTest`。纯文档漂移，无行为影响。
- **（2）`UserService.java:98` 明文账号密码进 stdout。** 登录成功后 `System.out.println(username + "," + password)` ⇒ **明文凭据落进程日志**。⚠️ 但 `:94` 的 javadoc 明写这是「三层前就有的副作用，**照搬** —— 它不是调试残留」⇒ **刻意的**，故不是笔误、是**既存安全面**。**待裁**：并进鉴权笔（登录改造本就动这一处）还是单列。
- **（3）`login.Password` 宽 `char(20)`，装不下 bcrypt。** 见 `db-schema.md:205`（`char(20)` 显式 / **明文**；原 `:192`，2026-10-10 块 3 增量后重指）。bcrypt 输出 **60** 字符 ⇒ hash 化**不能原地改列**（数据库规则禁 `MODIFY COLUMN`），只能走**影子列**（新增列 + 回填）。**待裁**：建议**单列一笔**（文件数 4–5），排在鉴权笔**之后**。
- **（4）【裁定 · `P2.6` 独立一笔】** ⚠️ **前 3 条是「发现」（调研看到的事实），本条是「裁定」（用户做的决定）—— 性质不同，勿混。** 内容 = 删前端 **4 个读写口**（`LoginPage.vue:163-169` 写 / `GoodsUp.vue:145-146` 读 / `UserInfoPage.vue:91-92` 读）+ 拆 `UserService.java:185` 的 `selectRoleForProfile(username, password)` 重验。**前提** = 鉴权笔落地（凭据进 **httpOnly Cookie**）；**时序** = 鉴权笔 → `P2.6` 笔。 ⚠️ **已落地（P2.6，2026-10-05，§5-AC）**：本条 4 个行号**全部重指** —— 写口 `LoginPage.vue:163-169` → **`:158` / `:162`**（`setCredentials` 改名 `setUsername`、不再传密码）；读口 `GoodsUp.vue:145-146` → **`:148-149`**、`UserInfoPage.vue:91-92` → **`:94-95`**（均改走 `/user/me`，不再读密码）；`UserService.java:185` 的 `selectRoleForProfile(username, password)` 重验 —— ⚠️ **该地址在 HEAD 即已偏**（HEAD `:185` 是 `loginAdd` 形参行；`selectRoleForProfile` 的真实调用点 HEAD 在 **`:242`**）⇒ 本笔后为 **`:239`**（本笔把该方法的 javadoc 压掉 3 行，调用点上移）。
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
- **（3）外部指向 `progress.md` 行号的 7 处引用 → 节锚点。** `selection-status.md` 6 处（`:127` / `:130` / `:131` / `:132` / `:133` / `:190`）+ `tech-debt.md:345` 1 处。⚠️ **这 7 处在本笔之前就已陈旧 7–9 行**，不是本笔弄旧的 —— 例：`selection-status.md:131` 引 `progress.md:85` 要说 C2 SSE，而 `:85` 是**表格欠账注**、C2 行实在 `:94`；`:133` 引 `:199` 要说 `TD-05/06/08/09`，而那组在 §5-C。⇒ 一律改指**节**（§1 / §3 / §4 / §5-C）。
- **（4）`@CrossOrigin` 漂移收口（6 文件 8 处）。** 那 5 处 `@CrossOrigin` 早在 **PR #34 `23f5361`** 已全部删除，但 6 份文档仍写「5 个 Controller 均标」。本次改为「已删 / 现 0 处」：`api-contract.md:9`、`three-layers.md:120`、`defect-survey.md:14` / `:104`、`project-map.md:81`、`tech-debt.md:61` / `:62` / `:64`。⚠️ **同文件内自相矛盾一并订正**：`tech-debt.md:355`（记「公共层笔 1/3 —— CORS：删 5 处」= `D#3` 的 CORS 面 ✅）与同文件 `:61`/`:62`/`:64`（仍写「**5 个** Controller 各自标注」）互斥 ⇒ 本次把 **TD-04 状态改为「🟡 部分关闭」** —— **重复配置面 ✅ 已关**（PR #34），**通配风险面仍开**（`allowedOriginPattern("*")` + `allowCredentials(true)` 未收敛，归生产加固）。`progress.md` §3 公共层行的「`D#3` 的 CORS 面 ✅」**与新状态一致，不改**。⚠️ **按语（CORS 收窄笔，2026-10-06，§5-AH）**：本行说的「**通配风险面仍开**」**已关** —— origin 侧改为字面量白名单（`addAllowedOrigin(String)`，配置含 `*` 直接启动失败）⇒ `tech-debt.md` 的 **TD-04 由「🟡 部分关闭」转「✅ 已关闭」**（该文件 `:61`/`:63`/`:64`/`:66` 已就地订正）；§3 公共层行的那句因此**再一次需要追加**（已加）。
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
  ⇒ **翻的时机在笔 4（前端接 `credentials` + 401 处理）之后**；且「翻」**单列一笔** —— 它要改 **a3 加显式 `false` 护栏** + **smoke 类适配**（挂会话或加白名单），混进笔 4 会让 diff 伸进 `src/test/`。⚠️ **按语（§5-AD 翻开关笔，2026-10-05）：本条的预测已如期应验并已被护栏拆掉** —— 翻笔按本条「单列一笔 + a3 加显式 `false` + smoke 适配」三条照办：`application-a3.properties` 加 `app.auth.enabled=false`（故上面「不设 ⇒ 继承主配置 ⇒ 全 401」不再发生）、`src/test/resources/application-smoke.properties` 加同款 pin（10 个 smoke 类**零改动**全绿）；② 那个 29 **确已重测**（见 §5-AD）。

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

  ⇒ **[12]/[13] 是给笔 4 的现成结论**：后端 CORS 侧（`ImgConfig` 的 `addAllowedOriginPattern("*")` + `setAllowCredentials(true)`）**已支持跨源带凭据**，且**预检不会被闸拦**。⚠️ 但**浏览器是否真的带上 cookie**（`SameSite` / 跨源定义）**本机 curl 证不了**，笔 4 须**真浏览器实测**。 ⚠️ **按语（CORS 收窄笔，2026-10-06，§5-AH）**：括注里的「`addAllowedOriginPattern("*")`」**已收窄**为**字面量白名单**（键 `app.cors.allowed-origins`，dev 值 = `http://localhost:8080,http://127.0.0.1:8080`）⇒ **[12]/[13] 的结论与两条响应头数值（含 `Max-Age: 3600`）逐字不变**，因为 `http://localhost:8080` **正在**白名单内；收窄后按同一手法重跑的真容器实测见 §5-AH (7) 的 A/B 档（A 档 = 同值回放、B 档 = 非白名单 **403**）。

  ⚠️ **两条附带发现（本笔实测，非本笔造成）**：
  - **(i) 本开关没有启动期生效值打印。** 启动日志里 ASCII `auth` **0 命中**，且 `WebAuthConfig` / `AuthInterceptor` **源码内无任何 logger / `println`** ⇒ 与 `app.lock.*` / `app.cache.*` / `app.order.state.*` 的「启动期打印生效值」惯例**不一致**：**闸开没开只能从行为反推**。⚠️ 因此「启动日志能读到 `app.auth.enabled` 生效值」**当不了验收判据**（除非另补一行自检日志 —— 那属「翻」那一笔）。⚠️ **按语（§5-AD 翻开关笔，2026-10-05）：本条已结清** —— 翻笔在 `WebAuthConfig` 构造器补了一行启动自检日志（ASCII 片段 `AUTH`），**此后「启动日志读生效值」可当验收判据**。
  - **(ii) 取证副作用已还原。** 第 6 条**真写库**（改了 `user` 表 `customerid='pIzTWafaGp'` 行的 `email` / `address` / `selfintroduction`）。已按 `mall_management_sql.sql:197` 的种子值用 `UPDATE` **还原并读回核对**。⚠️ 教训：非白名单取证要挑**只读**端点，别顺手挑写端点。

- **（3）【订正】4 处「同一事实」的引用点 —— 过时的是**子句**，不是整句。**
  原句「…**认证侧尚未落地** —— 没有任何端点会把 `AuthInterceptor.SESSION_USER_ID` 写进会话（那是鉴权笔 2）…」的**后半句已过时**（笔 2 已落地写入方：`UserController.loginSelect` → `AuthSession.login`）。
  ⚠️ **「认证侧未生效」本身**（闸没开 ⇒ 认证链路未被启用）**仍是事实、保留** —— 整句删会掩盖「闸还没开」这个状态。
  ⇒ 统一改为「**写入方已在笔 2 落地，但闸仍默认关（认证未生效）**」。落点 **4 处**：`application.properties`（那条「为什么默认关」注释块）、`WebAuthConfig` 类 javadoc、`business-decisions.md`（BD-03 现状行）、`three-layers.md`（`:185` / `:198`）。
  ⚠️ **后两者按 §5-P (1) 的先例做同行替换（`-1 +1` / `-2 +2` 净零）** —— 那两个文件被别处**按行号**引用，增删行会集体偏移。

- **（4）【口径统一】端点数 = **35**（**handler 数口径**，2026-10-05 用户裁决）。⚠️ **订正（2026-10-05，单列笔见 §5-T）：本项原裁 34 作废** —— 成因是把 `goods` 漏数 1 条（原写 8，实为 **9**）。**
  口径：**一个 handler 方法算 1 条，与它声明几种 HTTP 方法无关** —— `@GetMapping` / `@PostMapping` / `@RequestMapping(GET,POST)` / 同名多注解**都算 1 条**。理由：handler 数是**方法数**，稳定；HTTP 方法数会随「一个方法支持几种 HTTP」变，不稳定。⇒ **`/goodstype`（`GoodsController.java:82` 同时声明 `GET,POST`）算 1 条**。
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
  - **401 判 `status` 不判 `body`** —— 闸 401 与 `/user/me` 401 的响应体**逐字节相同**（§5-R (2) 第 10 条 `cmp` IDENTICAL）⇒ 判 body 等于判「字符串相等」；且后端明写 `Result` 键序**不承诺**（`UserController.java:300`）。
  - **跳转走回调注册，不 `import router`** —— `router/index.js → views/* → api/http.js` 会成环；回调也让「跳哪」只写在 `main.js` **一处**（http.js 不硬编码 `#/login`，路由路径改了不会坏）。
  - **闩锁复位挂「路由变化」而非「任一 2xx」** —— 闸开时白名单端点（如 `goodsSelectHome`）照常 2xx，挂 2xx 会让闩锁被反复**误复位**、等于没有。

- **（3）【⚠️ 本笔最重要的诚实声明】401 出口在笔 4 期间**不可达 ⇒ **未验**。**
  闸默认关 ⇒ 唯一的 401 来源本是 `/user/me`，而它**本笔无调用方**（会话恢复已切走）⇒ **`UnauthorizedError` 这条分支在默认配置下一次都不会触发**。
  验收因此**只剩**：`npm run build` 通过 + 5 条 grep 判据 + 突变对照 A（撤 `credentials` ⇒ 手测 B3/B4/B6 变红）。**闩锁与 `clear()` 的实际行为，只能到「翻」那一笔才验得到**（原先的 B12 已由用户裁归翻笔）。
  ⇒ **不许**把「401 出口已验」写进任何结论。

- **（4）【翻笔前置登记】前端「退出」不调 `/user/logout`。**
  实测全 `src/` **0 调用** —— 「退出」只清 sessionStorage（`UserCenter.vue:37` / `MerchantPage.vue:131-132`）。⚠️ **按语（§5-X，2026-10-05）**：客户侧退出点 R1 后由 `CustomerPage.vue:112` **迁到 `UserCenter.vue:37`** —— 用户中心从 `CustomerPage` 的内联 markup 提成独立组件，`toLogin()` 随 markup 搬家（**换文件，不只是换行号**）；`MerchantPage.vue:131-132` **未动**。⚠️ **再按语（§5-Y，R2，2026-10-05）**：`MerchantPage.vue:131-132` **R2 后也不存在了**（商家壳路由化 ⇒ 商家退出点同样迁到 `UserCenter.vue`）⇒ 本句这两个行号**都已失效**；现行唯一退出点是 **`UserCenter.vue:52-55`**（同一函数两分支）。「退出只清 sessionStorage、不调 `/user/logout`」这个**事实**未变，只是落点从两处并为一处。⚠️ **再按语（§5-AB，R6，2026-10-05）**：两个壳已随 R6 **删除**（合一为 `AppShell.vue`，用户裁决 D2）⇒ 本条里 `MerchantPage.vue:131-132` 不只是「R2 后不存在」，**文件本身已不在仓内**（悬空，不是漂移）；现行唯一退出点仍是 `UserCenter.vue:52-55`。
  ⚠️ **闸一开这就成为真的没登出**：cookie 仍在，直开 `#/customer` 照样取到数据。⇒ **「退出调 `/user/logout`」列为翻笔前置**（翻笔本来就要动测试与 a3，那是它的时点）；并进笔 4 要改 `CustomerPage` + `MerchantPage` ⇒ **+2 文件、破 5**，故本笔不做。⚠️ **按语（§5-Y，R2，2026-10-05）**：本条的**前提**已变 —— R1/R2 之后两个壳**都没有**自己的退出了（客户 R1、商家 R2 都迁到共用的 `UserCenter.vue`）⇒ 若「退出调 `/user/logout`」要落，落点是 **`UserCenter.vue:52-55` 那**一处**（不是两个壳）⇒ 文件数估计从「+2」降到 **+1**。本条描述的「退出只清 sessionStorage」在 **R2 后仍然成立**（行为未变，只是落点集中了）。⚠️ **再按语（§5-AB，R6，2026-10-05）**：R6 直接**删除**两个壳（非改写）⇒「并进笔 4 要改 `CustomerPage` + `MerchantPage` ⇒ **+2 文件、破 5**」的**前提整体消失**（笔 4 落的是前端传输层、未动壳，且两壳此后已删）；保留原句作历史记录。

- **（5）【漂移登记 · 本笔不修】** 除 (1) 外另 **5** 条：
  1. **`getUserInfo` 的消费点是 3，不是 5。** 实测 `grep -rn "getUserInfo" src/` = `LoginPage.vue:141` / `UserInfoPage.vue:99` / `GoodsUp.vue:153`（另 2 处是 `GoodsUp` 的本地函数名、1 处是 `endpoints.js:26`）。**三份独立旁证同为 3**：`UserController.java:149` javadoc「前端**三个**调用点」、§6-A P1.2「消费点 **3**」、`api-contract.md:171` 只列 3 个文件。⚠️ 「5」**最可能**来自 `endpoints.js` 的 `user:` 块恰好 5 条（**推断档，未证**）。⚠️ **按语（P2.6，2026-10-05，§5-AC）**：本条是**当时口径**；P2.6 起 `getUserInfo` 的消费点 = **0**（三处**全改走 `/user/me`**）⇒ `grep -rn "getUserInfo" src/` 今日只余 `GoodsUp.vue` 本地函数名等非调用命中，不再是 3。
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

- **（3）读口顺序的实现对裁决**收严**了（请追认）**：裁决（本笔方案 §3.1）是「6 个写口全部**同步**缓存」。本笔实现为 6 个写口一律 `dropRestored()`（**整体作废**），而非逐字段同步。理由：本模块写口**只有两类调用方** —— 登录（`LoginPage.vue`）与退出（`CustomerPage` / `MerchantPage` / `http.js` 的 401 出口），两者都是**身份变更**。丢弃给出可证不变量「**任何一次写之后，读口 ≡ 笔前行为**」；逐字段同步反而留过期字段：新标签页恢复成商家 Y 之后再登录成客户 B、而 B 的 `merchantid` 为空（`LoginPage.vue:148` 的 `if` 跳过写）⇒ `restored.merchantId` 留着 Y、存储里是空的、「优先」就把 Y 读出来。**「优先」只在恢复窗口内生效**（窗口 = 启动到第一次本地写）。⚠️ **再按语（§5-AB，R6，2026-10-05）**：本条举证里的两个壳**已随 R6 删除** ⇒「本模块写口只有两类调用方（登录 / 退出）」这个**结论**未变，但退出侧的落点现在**只有** `UserCenter.vue:52-55`（+ `http.js` 的 401 出口），壳内已无写口。

- **（4）恢复不了 / 已知残留（如实登记，均**非**回归）**：
  - **password 恢复不了**：`/user/me` 只答 5 列（`customerid / merchantid / username / usertype / gender`）⇒ `GoodsUp.vue:148-149` 与 `UserInfoPage.vue:94-95`（拿密码做**真凭据校验**）在新标签页**仍失效**。⚠️ 改前新标签页的 `sessionStorage` 整个是空的，本来就取不到密码 ⇒ 本笔只是**没有把它们变好**。P2.6 的落点。 ⚠️ **已结清（P2.6，2026-10-05，§5-AC）**：两页现均改走**会话驱动**的 `/user/me`（`GoodsUp.vue:148-149` / `UserInfoPage.vue:94-95`），**不再读密码** ⇒ 新标签页**可用**，本缺口消失。
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
> **来源**：`/` 在 `router/index.js:35`（**R1 重指**；原记 `:12`）**裸挂** `HomePage` ⇒ **无外壳 ⇒ 无导航栏**；未登录时还有 `.lock-mask` 引导登录（`HomePage.vue:4`）+ 三条守卫（`:135` / `:144` / `:165`），而**已登录时遮罩不渲染、三条守卫也不 return** ⇒ **卡在裸首页**。⚠️ **按语（§5-W，2026-10-05）**：`.lock-mask` 已改为 `.login-hint-bar`（同为 `HomePage.vue:4`，**行号未漂**、仅内容变）；本句另 3 个行号（`:135` / `:144` / `:165`）**仍对**。⚠️ **按语（§5-X，2026-10-05）**：`router/index.js` R1 后 **55 → 123 行** ⇒ 本句的 `/` 记录由 `:12`（当时是 `const routes = [` 那行，记录本身在 `:13`）移到 **`:35`**；**「裸挂」这个事实 R1 没有改**（`/` 落点收口是 R3 的活）。⚠️ **再按语（§5-Y，R2，2026-10-05）**：`router/index.js` 又 **123 → 201 行** ⇒ 该 `/` 记录（`{ path: '/', component: HomePage }`）现实测在 **`:52`**（`const routes = [` 在 `:51`）。**「裸挂」这个事实 R2 仍未改** —— 两笔都只动 `/customer` 与 `/merchant` 的子树。⚠️ **三次按语（§5-Z，R3，2026-10-05）**：**「裸挂」这个事实已在 R3 终结** —— `{ path: '/', component: HomePage }` 换成 `{ path: '/', redirect: landingFor }`；**本句引用的 `:52` 因此未漂**（R3 刻意净零：`const routes = [` 仍在 `:51`，`export default` 由 `:201` 挪到 `:202`）。`/` 从此**不再是页面、只是落点**，且**未登录也落 `/customer`**（原为「停在裸页」）。逐条见 §5-Z (2)/(4)。 ⚠️ **四条按语（§5-AG (13)，2026-10-06）**：`/` 的落点事实**未变**；但本句提到的 `.lock-mask` → `.login-hint-bar` 那条引导**已删除**，另三条守卫（`:135` / `:144` / `:165`）**也已拆除**（改判 2：未登录可逛公开面）—— 三个行号现为**同址按语**，「行号仍对」的结论不变。
> **笔 5（§5-U）的角色**：它把「服务端已登录、前端不知道」（`JSESSIONID` 是 `HttpOnly`）这个**隐性状态**变**显性** ⇒ 遮罩消失、唯一出口关闭 ⇒ **放大器**，不是实现错误。**主因是首页设计债**（`/` 裸挂、`emit('go-search')` 无人接）。
> ⚠️ **本笔只是止血，不是「已解决」**：`/` **仍**裸挂 `HomePage`、仍无导航栏；标准布局重构另起（R1–R4，`§十一` 分笔建议已出）。

- **（1）改动 4 文件（3 代码 + 1 文档）**：
  1. `src/api/auth.js`：`K.role = 'role'` + `getRole()`（与 `getMerchantId` 同形：恢复值优先、存储兜底）+ `setRole(v)`（写口 + `dropRestored()`）。
  2. `src/views/LoginPage.vue`：`:158` / `:162` **同行**追加 `setRole(role)` —— **净零行数**（470 → 470），保 `api-contract.md:172` 引的 `:216` 不漂移。
  3. `src/router/index.js`：`import { isLoggedIn, getRole, getMerchantId }` + `const ROLE_HOME = { '1': '/customer', '2': '/merchant' }` + `router.beforeEach`（只守 `/`；未登录放行；已登录 `ROLE_HOME[getRole()] || (getMerchantId() ? '/merchant' : '/customer')`）。
  4. `docs/agent/progress.md`：本节。

- **（2）⚠️ 行为变更：`clearCredentials()` 多清一个键 `role`**：原只 `removeItem` `username` / `password`，现加 `role`。理由：退出登录语义上就该清角色。**影响面 = 两个退出点** —— `UserCenter.vue:37` 与 `MerchantPage.vue:131`。⚠️ **按语（§5-X，2026-10-05）**：前者 R1 后由此前记的 `CustomerPage.vue:112` 迁来（换文件）；后者未动。⚠️ **再按语（§5-Y，R2，2026-10-05）**：本句的**两个**行号均已失效 —— 现状是**一个**退出点（同一函数两分支）：`UserCenter.vue:52-55`（客户分支只清凭据、商家分支 `meta.logout === 'merchant'` 另清 `merchantid`）。⚠️ **行为**（`clearCredentials()` 多清 `role`）**没变**，变的是**退出点的数量与位置**。⚠️ `clear()`（401 出口）**不重复**清 `role`：它第一行就调 `clearCredentials()`，已覆盖。⚠️ **再按语（§5-AB，R6，2026-10-05）**：本条点名的 `MerchantPage.vue:131` 及其客户侧同源点**已随两壳删除悬空**（合一为 `AppShell.vue`）⇒ `clearCredentials()` 多清 `role` 这个**行为**未变，退出点仍是 `UserCenter.vue:52-55` 一处。

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
  - **⚠️ C3 仍要动后端**：把 `role` 加进 `/user/me` 投影。否则**管理员开新标签页拿不到 `role`**（~~`/user/me` 现只答 `usertype` —— 中文串「客户/商家」—— **不含 `role`**~~）⇒ 走 `getMerchantId()` 兜底 ⇒ **落到 `/customer`（管理员无 `merchantid`）= 错页**。**本笔不动后端**（止血保持小）。 ⚠️ **按语（2026-10-06 核，§5-AF (5) / `:1318`）**：括注里「**不含 `role`**」**已过时** —— 笔 5 之后 `/user/me` **已答 `role`**（`progress.md:1318` 原文「/user/me 现答 `role`」）。⇒ 本段结论**只剩前半**仍然成立：**前端**今天仍拿不到（`role === "3"` 无登录分支、DB 无 role=3 种子），C3 要做的是**前端分支 + 路由记录**，后端那半步**可能已不需要**（以 (16) 的 C3 待办为准）。
  - 本笔**刻意不从 `usertype` 反推 `role`**：那会让 `role` 有第二个来源（**口径分叉**），且要动笔 5 的 `restoreFromSession`，管理员（`usertype` 未知）仍映射不出来。

- **（6）⚠️ 时序耦合（本守卫正确性的唯一前提）**：守卫读的是 `auth.js` 的**恢复态**。`main.js` 的顺序是 `await restoreIdentity()` ⇒ `app.use(router)`（**初始导航由此触发**），而本文件在 `main.js` 顶部被 import ⇒ 守卫在**模块加载时**注册，必然早于 `app.use(router)`。⚠️ **若将来有人把恢复挪到 `app.use(router)` 之后** ⇒ 守卫读到「匿名」⇒ 不重定向 ⇒ **首页再度卡死（不报错、不崩，最难查）**。已写进 `router/index.js` 守卫上方注释。

- **（7）验证**：`npm run build` 通过（**无** `export 'xxx' was not found` 那条；仅 2 条**既有**体积警告）；`dist` 里 `beforeEach` **1 次** / `role` **6 次** ⇒ 接线**未被 tree-shake**；环检查 `router → api/auth` **不成环**（`grep -c "^import" src/api/auth.js` = **0**）。手测 **T1–T7** 见 PR 描述 —— **留给用户逐条跑，不代跑**。

- **（8）不加「更新记录」行**（照 `§5-H (3)` / `§5-N` / `§5-Q (4)` / `§5-T (7)` 先例）。

---

### 5-W. 首页锁改提示条（`fix/home-unlock`，2026-10-05）：整页遮罩 → 不挡点击的登录提示条

> **性质**：前端 **2 代码文件 + 1 文档** / **0 新文件**。不动后端、不动 `ci.yml`、不动 A3 脚本、不动 `src/test/**`、不动 `CustomerPage.vue` / `MerchantPage.vue`。⚠️ **按语（§5-Y，R2，2026-10-05）**：这两条壳文件后来**都被改过**（`CustomerPage.vue` 被 R1、`MerchantPage.vue` 被 R2）—— 「不动」是**§5-W 本笔**的边界，不是永久约束。⚠️ `router/index.js` 本笔**只改了一处注释措辞**（见 (5)②），**未做任何路由结构改动**（R1 / R3 的活）。⚠️ **再按语（§5-AB，R6，2026-10-05）—— 本节（§5-W）整节按语**：本条点名的两个壳**已随 R6 合一删除**（用户裁决 D2）⇒ 节内（含 (4) 提到的 `emit('go-search')` 那条链）所有 `CustomerPage` / `MerchantPage` 引用**一律悬空**（历史记录保留原样），逐条见 §5-AB (11)。
> **来源**：§5-V 只治了「**已登录**在 `#/` 卡死」。**未登录**那一半没治 —— `HomePage.vue:4` 的整页遮罩（`.lock-mask`，原 CSS `:225-233`：`position:absolute; z-index:99; width:100vw; height:100%`）把**整个首页**盖住 ⇒ 搜索框连聚焦都做不到、所有点击一律变成开弹窗 = **什么都点不了**。而**商品页本来就是「默认让看、只在购买/咨询弹提示」**（`ProductPage.vue:311-314` / `:408-412`）⇒ **首页的整页锁在全项目是孤例**。 ⚠️ **按语（§5-AG (13)，2026-10-06）**：本句引的 `HomePage.vue:4` 整页遮罩 → 提示条 → **现已整条删除**（黄条冗余：顶栏匿名形态已承担该入口）；`ProductPage.vue` 那两处**仍在**（本笔明确不动）。 ⚠️ **再按语（收口笔 §5-AG (15)，2026-10-06）**：`ProductPage.vue` 的 `:311-314` / `:408-412` 两处**判据（`!customerId`）与文案逐字未变**，但 (15) 在 `alert` **之后**接了一句「未登录则跳 `/login?redirect=<本页 fullPath>`」⇒ 本条「首页锁是孤例」的结论不变；变的是**商品页也终于给了去登录的出口**。
> **判据面**：未登录能看到的首页数据全走**白名单**端点（`WebAuthConfig.java:103-107`：`goodstype` / `goodsSelectHome` / `GoodsProductCodeSelect` / `productCodePrice` / `productSearch`）⇒ **服务器侧本来就支持匿名浏览，本笔不需要动后端**。

- **（1）改动 3 文件（2 代码 + 1 文档）**：
  1. `src/views/HomePage.vue`：`:4` **一行换一行** —— `.lock-mask`（`@click="showLoginModal = true"`）→ `.login-hint-bar`（`@click="router.push('/login')"`，文案「未登录 · 点此登录后可搜索 / 购买」）；原 `.lock-mask` CSS 块（`:225-233`，**9 行**）**原地改写**为 `.login-hint-bar`（**同为 9 行**：`width:100%; padding:10px 16px; background:#fff8e1; color:#8a6d3b; font-size:13px; text-align:center; cursor:pointer`）。 ⚠️ **按语（§5-AG (13)，2026-10-06）**：该 9 行 CSS 块**已删除**（`:225-233` 现为**一行按语**）；`HomePage.vue` 由 443 → **389 行**，但本行段提到的 `:4`、以及 `:116` / `:119` / `:121` / `:154` / `:135` / `:144` / `:165` 等被引行号**全部未漂**（改判 2 沿用「按语 + 净零」手法）。
  2. `src/router/index.js`：`:29-31` 守卫注释**同行换名**（`.lock-mask` → `.login-hint-bar`、「遮罩」→「提示条」）+ 追加一句 `§5-W` 指针 —— **3 行换 3 行，净零，语义不变**。⚠️ 这是**本笔改动的直接下游**（同一事实的引用点），不修就是**留假话**（同 `#11` / `#42` / TD-01 口径）⇒ 用户批准确认，破「2 文件」不破 `≤5`。
  3. `docs/agent/progress.md`：本节 + §5-V 源句按语。

- **（2）⚠️ 行为变更（本笔唯一的行为变化）**：**未登录从「整页点不动」变为「可正常浏览」**（内容可见、可滚动、顶部一条可点的登录提示条）。
  - ⚠️ **登录弹窗少了一条触发路径**：改前点遮罩 = `showLoginModal = true`（先弹窗，再点窗内「去登录」）；改后点提示条 = **直达 `/login`**。⚠️ 弹窗**本身仍在**（三条守卫照旧触发它），只是**不再有「点整页任意处即弹窗」这条路径** —— 因为那正是被去掉的整页遮罩。
  - **操作拦截一个字没改**：三条守卫（`goSearch :135` / `searchByType :144` / `toGoodsDetail :165`）**全部保留** ⇒ 未登录点搜索 / 分类 / 商品卡**仍然弹「请先登录以使用功能」**。 ⚠️ **按语（§5-AG (13)，2026-10-06）**：**该行为已改判** —— 三条守卫**全部拆除**、弹窗与文案随之删除（未登录可逛公开面）。本行作为 R3 的历史记录保留；**不要再把它当判据**。
  - **已登录路径逐字不变**：遮罩 / 提示条都带 `v-if="!isLogin"` ⇒ 已登录时两者**都不渲染**。
  - ✅ **定位风险已排查**：新增的顶部条是 `.home-page`（`display:flex; flex-direction:column`）的**首个子项** ⇒ 只把后续区**整体下移**，不影响绝对定位元素 —— `.swiper-img` / `.dots` 的定位祖先仍是 `.swiper-container` / `.swiper-box`（均 `position:relative`，未变），`.login-modal` 是 `100vh` 全屏浮层（`z-index:9999`）。

- **（3）⚠️ 边界：搜索 / 分类的「放行」要等 R3，本笔不能做**。守卫若在本笔放开，`#/` 上 `emit('go-search')` **无人接**（`App.vue:3` 的裸 `<router-view />` 上没有 `@go-search` 监听）⇒ 未登录点搜索会**既不弹窗、也不跳转 = 静默无反应**，**比现在的弹窗更差**。⇒ **刻意停在「浏览放行、操作仍拦」**。

- **（4）归属：本笔只做「整页锁 → 提示条」**。未登录的**完整形态**——顶栏（登录/注册入口）、未登录占位（用户中心 / 购物车）、搜索放行、`emit('go-search')` 落点——**全部归 R3（`/` 落地页）**。R3 之后本笔的改动**无需回改**（公开落地页不该有整页遮罩，此结论与布局演进无关）。⚠️ **按语（§5-Z，R3，2026-10-05）—— 本条的 R3 待办已结清，但四项的结论与当年的预期不同**：① **顶栏（登录入口）：做了** —— 两壳未登录时右三项**整体换成「登录」**（`config/nav.js` 的 `navFor` / `ANON_RIGHT`）；② **未登录占位（用户中心 / 购物车）：没做占位页，改做「不渲染」** —— 匿名时那三项**根本不出现**，比占位页更彻底，且**不新增任何守卫**；③ **搜索放行：本笔刻意不做**（用户裁决 —— 后端白名单 ≠ 前端必须放开；放开要改 `HomePage.vue`，破坏 R1/R2 的「一字不动」）⇒ 三条守卫**原样保留**，本条 T2 因此**仍然成立**；④ **`emit('go-search')` 落点：R3 无需改任何代码** —— `#/` 落 `/customer` 后走的就是 R1 那条链（`CustomerPage` → `HomeTab` → `router.push('/customer/search')`）。另：本条预告的「**无需回改**」**已兑现** —— `.login-hint-bar`（`HomePage.vue:4`）一字未动，`HomePage.vue` 在 R3 **整文件零改动**（443 → 443 行）。 ⚠️ **按语（§5-AG (13)，2026-10-06）**：本句是**对 R3 的如实记录**（R3 当时确实一字未动）；2026-10-06 的改判 2 才开始动它 —— `.login-hint-bar` 与三条守卫删除，`HomePage.vue` **443 → 389 行**，`:4` / `:119` / `:135` / `:144` / `:165` 五处为**同址按语**。

- **（5）漂移登记**：
  - ✅ **`HomePage.vue` 净零（443 → 443 行）**，`.lock-mask` 块 **9 insertions(+) / 9 deletions(-)** ⇒ 指向它的 **7 处 inbound 行号引用 / 10 个行号**（`api-contract.md:175`→`:154`、`api-contract.md:178`→`:121`、`progress.md:401`→`:116`、`progress.md:653`→`:4`/`:135`/`:144`/`:165`、`progress.md:701`→`:154`、`api/auth.js:108`→`:116`、`main.js:21`→`:119`）**全部零漂移**（本笔选「一行换一行 + CSS 原地改写」而非「删行」的**决定性理由**）。 ⚠️ **按语（§5-AG (13)，2026-10-06）**：这 7 处**至今零漂移**（改判 2 沿用同一手法：删除处**补等量说明行**）—— 其中 `progress.md:653` / `:701` 两处已加按语，`main.js:21`、`api-contract.md:178`→`:121`、`:187`→`:154` 三条引用**逐字仍对**。
  - ✅ **`router/index.js` 净零**（55 → 55 行）：注释 `:29-31` **3 行换 3 行** ⇒ 该文件全仓**无任何 inbound「文件:行号」引用**（承 §5-V 结论）⇒ 零漂移；其尾换行与 `HEAD` **一致**（都无）⇒ 非新披露。
  - ⚠️ **措辞过时 2 处（行号均未漂，只是描述不再为真）—— 本笔**两处都收口**：**
    1. `progress.md:653`（§5-V 源句）→ **已就地加按语**（见上）。
    2. `router/index.js:94`（守卫注释 + `:96` 的「遮罩不渲染」；⚠️ **§5-X 重指**：R1 后该文件 55 → 123 行，原 `:29` / `:30` 下移）→ **已同行换名**（`.lock-mask` → `.login-hint-bar`、「遮罩」→「提示条」）并追加 `§5-W` 指针。⚠️ 本条原计划「登记不修」，用户以**「不留假话」**（同 `#11` / `#42` / TD-01 口径）裁定**本笔修**——理由：该句是本笔改动的**直接下游**，同一事实的引用点不同步合入即留假话；且**只改注释措辞，不属 R1 / R3 的路由结构改动**。⚠️ **再按语（§5-Y，R2，2026-10-05）**：`router/index.js` R2 后又 **123 → 201 行** ⇒ 本条的两个行号**都已失效**（`:94` 现实测是 `OrderPane` 那段的 `order/cart` 注释；`.login-hint-bar` 那处守卫注释现实测在 **`:173-174`**）。**内容未再变** —— R2 没有回改 `§5-W` 那处措辞 ⇒ 本条描述的「已同行换名」这件事 R2 后**仍为真**，只是地址变了。⚠️ **三次按语（§5-Z，R3，2026-10-05）**：该注释**地址仍未变**（标题 `:173` + 提到 `.login-hint-bar` 的 `:174`），但**性质变了** —— R3 把这条守卫换成函数 `landingFor`（现 `:197`）⇒ `:173` 的标题由「首页落点**守卫**」改写为「首页落点（R3 起是一条 **redirect**）」，`:174` 那句 `.login-hint-bar` 引导**内容未改**（只保留「整页锁 → 提示条」的历史）。本条描述的「已同行换名」这个**事实** R3 后仍为真。
  - ✅ 指向本文件的数值引用：本笔在 **§5-V 之后**追加，`## 6` 及其后下移；全仓对 `progress.md` 的**活数值引用为零**，仅存于归档件 `docs/agent/archive/项目整体进度汇报-2026-10-04.md`，其 10 处引用**全部落在 `:82`–`:199`**（插入点 **686** 之前）⇒ **未被打断**。

- **（6）验证**：`npm run build` 通过（**Compiled with 2 warnings**，与改前**同数** —— 均为**既有**体积 / 分包建议，非本笔引入；无 `export 'xxx' was not found`）；`dist` 校验 —— `login-hint-bar` 在 `dist/css/app.18d30f85.css` **1 次**，`lock-mask` 在 `dist/css/*` 与 `dist/js/*` **各 0 次** ⇒ 改名已进产物、旧类名未残留；`src/` 全量 grep `lock-mask` = **0 命中** ⇒ 无残留引用（含注释）。手测 **T1–T4** —— ⚠️ **本笔不单独跑**：用户裁定攒到 **R1–R4 全做完一起跑**（反复手测成本高），其中 **T2**（未登录点搜索 / 分类 / 商品卡仍弹「请先登录」）是「**守卫没被误删**」的关键判据，**记着在 R4 后一起验**。 ⚠️ **按语（§5-AG (13)，2026-10-06）**：T2 **已失效** —— 它验的行为在改判 2 里被**有意**改掉。它当年跑过一遍（R4 的 T-new-2，通过）；2026-10-06 起判据换成「**未登录点搜索 / 分类 / 商品卡能进公开面**」，见 §5-AG (7) 的 T-guard-2 扩写与 T-guard-9。

- **（7）不加「更新记录」行**（照 `§5-H (3)` / §5-N / `§5-Q (4)` / `§5-T (7)` 先例）。

---

### 5-X. R1 客户壳路由化笔（`feat/router-customer`，2026-10-05）：**设计 A**（真嵌套路由 + `<router-view>`）—— 破 ≤5（9 文件）

> **性质**：前端 **7 代码文件（5 改 + 2 新）+ 2 文档** / **2 新文件** —— **破 ≤5**（用户批准，对账见 (1) 末）。⚠️ **此数曾写错**：本笔报告一度写「8 文件（5 代码 + 3 文档）」（漏 `src/api/http.js`、且代码/文档分类颠倒）—— 用户核出并纠正为 **9 文件（7 代码 + 2 文档）**；**后续引用一律用 9 / 7 / 2**。不动后端、不动 `ci.yml`、不动 A3 脚本、不动 `src/test/**`。`HomePage.vue` / `ChatRoom.vue` / `ShoppingCart.vue` / `LoginPage.vue` / `MerchantPage.vue`（R2）/ `ProductPage.vue`（R5 不做）**一字未动**。
> **来源**：§5-W (4) 把「标准布局重构」判给 R1–R4。R1 前 `CustomerPage.vue` 用内部 `curPage` / `curSubPage` 两个 ref + `v-if`/`v-show` 切 8 个页面，用户中心是**内联 markup**。
> **用户裁定（本笔的设计前提）**：走**设计 A**，理由「项目要当**企业投名状**，要用企业标准做法」；同时**丢弃设计 B 草稿**（把 `curPage` 改成可写 `computed`）—— `git restore` 回干净起点、**不做部分保留**。我此前**建议保留 B**（论证：状态归属才是要点、A 的唯一增量是「壳/页是否拆开」），**用户否决**，A 为最终方案。
> **两轴口径（与 R6 的分界）**：轴 1 = tab 状态住在哪（内部 ref ⇄ 路由）；轴 2 = 壳与页是否拆开（`v-if` ⇄ `<router-view>`）。**A 与 B 在轴 1 完全相同**，A 的唯一增量是轴 2 ⇒ 本笔**不含 R6 的内容**（不建 `AppShell.vue`、不做配置化）。
> ⚠️ **整节按语（§5-Y，R2，2026-10-05）：本节是 R1 那一笔的落码快照 —— R2 之后节内每一处行号都已漂，且若干结构描述已变假。** 引用本节时**一律以 §5-Y (9) 的漂移表为准**。变假的**结构**描述已就地标出（(1)3 的 `MerchantPage.vue:20`、(3) 的 `GoodsSelect.vue:165` 先例、(12)4 的 `/goodsSelect`、(12)5、`(15)` 的宏根因），本节**其余文字一律保留 R1 原样** —— 它是对 R1 那一笔的**如实记录**，不逐行修订（同 §5-T (5) / §5-W (5) 的「历史句保留 + 按语」口径）。⚠️ **再按语（§5-AB，R6，2026-10-05）**：本节的**被记录对象（两个壳）已不存在** —— `CustomerPage.vue` / `MerchantPage.vue` 已随 R6 合一为 `AppShell.vue` 并**删除**（用户裁决 D2）⇒ 节内**每一处** `CustomerPage.vue:N` / `MerchantPage.vue:N` 现在都是**悬空**（不是漂移）。**逐条不再加按语**，以 §5-AB (11) 的悬空清单为准。

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
  1. **布局类名的所有权**现在留在**两个壳**（`CustomerPage.vue:61` / `MerchantPage.vue` 的 `layoutClass` + `route.meta.layout`；⚠️ **按语（§5-Y，R2，2026-10-05）：R2 把这一手法原样复制到商家壳后，两处实测都在各自文件的 `:80`**）。R6 抽 AppShell 时应把「内容区外壳 div」**连同类名下移到各页组件**，`meta.layout` 随之退役。⚠️ 本笔**不为 R6 做预案**（不建 `AppShell.vue`、不做配置化）——⚠️ **R2 破了一半**：顶栏已抽到 `src/config/nav.js`（用户裁决 6），`layoutClass` 仍留在两壳（见 §5-Y (7)）。⚠️ **再按语（§5-AB，R6，2026-10-05）**：本条**被 R6 推翻** —— 用户裁决 **D4：不做**「把『内容区外壳 div』连同类名**下移**到各页组件、`meta.layout` 随之退役」。理由：① 下移要动 **12 个页组件**，远超任何预算；② `meta.layout` 是「路由决定布局」的**自洽**机制（一条 computed + 路由表里的字符串），那条承诺写下时（R1）还没看到它跑起来的效果；③ R6 的章程是**纯消重**。⇒ 布局类名的所有权**仍在壳**，R6 后壳 = `AppShell.vue`。两壳已删 ⇒ 本条里的 `CustomerPage.vue:61` 与其按语 `:80` **全部悬空**。
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
> **两轴口径**：同 §5-X —— R2 在轴 1（tab 状态住哪）与轴 2（壳/页是否拆开）上都照 R1 的做法 ⇒ **一壳一壳地搬**，不含 R6 的内容（不建 `AppShell.vue`，但 nav 已配置化 ⇒ R6 只需把两个壳包起来）。⚠️ **再按语（§5-AB，R6，2026-10-05）—— 本节（§5-Y）整节按语**：两个壳**已随 R6 合一删除**（合一为 `AppShell.vue`）⇒ 节内**每一处** `CustomerPage.vue:N` / `MerchantPage.vue:N` 现在都是**悬空**（不是漂移，文件已不在仓内）；**逐条不再加按语**，以 §5-AB (11) 的悬空清单为准（与 §5-X 节首那条整节按语同口径）。

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

- **（6）顶栏配置抽到 `src/config/nav.js`（用户裁决 5，R6 预备）**：两壳顶栏实测**同构**（左 1 + 右 3），**唯一差异 = 右起第 2 项**（客户「购物车」⇄ 商家「订单中心」）；红点都在「消息」项。⇒ 抽成 `CUSTOMER_NAV` / `MERCHANT_NAV`，壳里 `v-for` 渲染。⚠️ **按语（§5-Z，R3，2026-10-05）**：本条的「**唯一差异 = 右起第 2 项**」**不准确** —— `MERCHANT_NAV.left.to` 是 `/merchant`、`CUSTOMER_NAV.left.to` 是 `/customer` ⇒ **左项也差**（同一句话在 `src/` 内有 3 处：`config/nav.js:15`、`router/index.js:30`、`CustomerPage.vue:70`）。R3 已把 **3 处全部同行订正**为「差异只有两处：左项 `to` + 右起第 2 项」（**只改注释、零行号漂移**），本条作为 R2 的如实记录**保留原样**。⚠️ **按语（§5-AA，R4，2026-10-05）**：本条按语里写的 `router/index.js:31` **是 R3 自己写错的地址** —— 实测那句「两壳顶栏差异**只有两处**」在 **`:30`**（`:31` 是 R3 的标题行）。**R4 已把 3 处（`config/nav.js:20`、本条、`progress.md:968`）同行订正为 `:30`**（纯地址订正、净零）。见 §5-AA (11)D8。⚠️ **再按语（§5-AB，R6，2026-10-05）**：本条列的 3 处地址里 **`CustomerPage.vue:70` 已悬空**（该文件已随两壳合一**删除**）；那句「两壳顶栏差异**只有两处**」**随 R6 搬进 `AppShell.vue:76-78`**（`CustomerPage.vue:70` 的原地址自此失效），并在 R6 里升格为「两条父记录的**全部配置差异**」的验收口径。另两处（`config/nav.js:15`、`router/index.js:30`）**地址未漂**（`nav.js:15` 的措辞 R6 同笔澄清为「两套顶栏表」）。
  - 形态：`{ left: […], right: […] }`，项 `{ label, tab, to, badge? }`；`badge: 'unread'` = 该项显示未读红点（判据 `item.badge === 'unread' && unreadCount > 0`）。
  - ⚠️ **`active` 判据仍是 `route.meta.tab`**，**不是** `route.path.startsWith(item.to)` —— 父路径前缀会让「商城主页」在**所有**子路由上高亮（§5-X 已记过同一条）。
  - ⚠️ **R6 的剩余活**：把两个壳（`CustomerPage` / `MerchantPage`，现在**逐字同构**）包进一个渲染 nav 的 `AppShell`。**布局类名的所有权**仍在两个壳里（`layoutClass` + `meta.layout`，两处实测都在各自文件的 `:80` / `:79`）—— R6 应把「内容区外壳 div」连同类名下移到页组件（§5-X (13)1 的按语）。⚠️ **再按语（§5-AB，R6，2026-10-05）**：本条的活**已落**（两个壳已包进 `AppShell.vue`、顶栏表走 `props.nav`、两壳删除）；但「把内容区外壳 div 连同类名下移」这半句**被 R6 裁决 D4 推翻、不做**（见 §5-X (13)1 的再按语）⇒ `layoutClass` + `meta.layout` **保留**在壳里。本条引的 `:80` / `:79` 两处**已悬空**。

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

- **（1）性质与台账**。前端 **4 代码文件（3 改 + 1 改）** + 1 文档 = **5 文件**，**0 新文件**，**不破 ≤5**（对账：`router/index.js` + `config/nav.js` + 两壳 + 本节，一件不可压 —— 路由落点、顶栏表、两个壳的取表，四者是同一条链上的四个环）。分支从 merge 后的 `origin/main`（`70f59ae` = #64）切。⚠️ **再按语（§5-AB，R6，2026-10-05）—— 本节（§5-Z）整节按语**：本条台账里点名的「两壳」**已随 R6 合一删除** ⇒ 节内（含 (1) 的台账行、R2→R3 漂移表里的两行、以及 (2) 的 `CustomerPage → <router-view> → HomeTab` 那条链）所有壳引用**一律悬空**（历史记录保留原样）；**逐条不再加按语**，以 §5-AB (11) 的悬空清单为准。

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

- **（7）⚠️ 未登录搜索：本笔刻意不放开（用户裁决 1）**。三条守卫（`HomePage.vue:135` / `:144` / `:165`）**原样保留** ⇒ 未登录点搜索 / 分类 / 商品卡**仍然弹「请先登录以使用功能」**。理由：① 放开要改 `HomePage.vue`，破坏 R1/R2 两笔「让它一字不动」的性质（**7 处 inbound / 10 个行号**全漂）；② 后端白名单**确实**放行 `productSearch` / `goodsSelectHome`，但前端拦着是**产品选择**（「先登录再搜」避免「搜到商品 → 点进详情 → 想买 → 再弹窗」的两段式），**不是不一致** —— §5-W 已把「商品页默认让看、只在购买/咨询弹提示」定为基线；③ 混在一笔 = 路由收口 + 首页行为两件事一起审。⇒ **记独立小笔**（建议并入 R5，那里本来就要动匿名购买）。 ⚠️ **按语（§5-AG (13)，2026-10-06）**：该「独立小笔」**已在守卫笔内落地**（用户改判 2）—— 三条守卫放开、黄条删除；⚠️ **理由①（「会漂 7 处 inbound / 10 个行号」）已被净零手法解除**（实测零漂移，见 §5-W (3) 按语），②③ 两条也由用户 2026-10-06 裁决推翻。
  - ⚠️ **这也是 §5-W 那条 T2 判据的落点**：R3 之后 T2（未登录点搜索/分类/商品卡仍弹「请先登录」）**必须仍成立** —— 它是本笔「**没把守卫删过头**」的唯一判据。 ⚠️ **按语（§5-AG (13)，2026-10-06）**：判据**反转** —— 现在要验的是「**守卫确实删了、但没删过头**」：公开面（搜索 / 分类 / 商品卡）**要能进**，**受保护面与 `ProductPage` 的购买 / 咨询守卫一个不能少**（见 T-guard-2 扩写 + T-guard-9）。
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
| `docs/agent/api-contract.md` | 220 | **220（零改动）** | `:192` 的 `CustomerPage.vue:108` / `MerchantPage.vue:107` **未漂** ⇒ 本笔**不动它**。⚠️ **按语（§5-AB，R6，2026-10-05）**：本行「未漂」的结论**只到 R3** —— R6 已把那两处**合一重指**为 `api-contract.md:192` 的 `AppShell.vue:161`（两壳的 `fetchUnreadCount()` 合并到一处、两文件已删）。本行作为 **R3 的核验快照**保留原样；本表**上方两行**（两壳各 12 个「被引锚点」）的现状见 §5-AB (11) |
| `HomePage.vue` 7 处 inbound / 10 个行号 | — | **零漂移** | 该文件**整文件零改动**（443 → 443） |
| `progress.md` 自指（13 处 `progress.md:NN`） | 最大 `:747` | **零漂移** | 本节插在 `:904` 之后，**全部自指都在其前** |

  - ⚠️ **第 4 条漂移（落码中才发现，超出方案里那 3 条）**：「两壳顶栏**唯一**差异 = 右起第 2 项」这句**在 `src/` 内有 3 处**（不只是方案里写的 `nav.js:15`）—— `config/nav.js:15`、`router/index.js:30`、`CustomerPage.vue:70`。⚠️ **按语（§5-AA，R4，2026-10-05）**：本条的 `router/index.js:31` **是 R3 自己写错的地址**（实测那句在 **`:30`**；`:31` 是 R3 的标题行）—— **R4 已把 3 处同行订正为 `:30`**。见 §5-AA (11)D8。实测 `MERCHANT_NAV.left.to` 是 `/merchant`、`CUSTOMER_NAV.left.to` 是 `/customer` ⇒ **左项也差**。**3 处已全部同行订正**（只改注释、零行号漂移）；§5-Y (6) 那句作为 R2 的**如实记录保留原样 + 按语**。⚠️ **再按语（§5-AB，R6，2026-10-05）**：本条说的「3 处」中含 **`CustomerPage.vue:70`（已随两壳合一悬空）** —— 那句已搬进 `AppShell.vue:76-78`；另 2 处（`config/nav.js:15`、`router/index.js:30`）**地址未漂**。
  - ⚠️ **第 5 条：一处「预存在的假话」，R3 查证但**不修**（登记）** —— `progress.md:401` 的「（差在 **`HomePage.vue:116`** 同行两键、`ProductPage.vue:415`/`:416` 同行两次出现）」**已失效**：实测 `HomePage.vue` **全文 0 处** `loginUsername` / `merchantid`（`grep` 零命中）。**追历史确认了成因**：`loginUsername` 曾在 `9b87ab9` / `2ced01c` 的 `:116`（当时确有 `!!loginUsername || !!merchantid` 两键同行）⇒ 该地址**写的时候是对的**；**笔 3/8（`6639c1f`「用户与登录域 3 个 view 迁到 API 层」）**把这段换成 `:118` 的注释 + `:119` 的 `isLogin = computed(() => isLoggedIn())` ⇒ 两键**从该文件整体消失**，地址自此悬空。⚠️ 即：它**不是「漂移」而是「悬空」** —— §5-W (5) 把 `progress.md:401` 列进「零漂移」清单时，它**当时就已经不对**了（当时的核法只回放行内容、没回放「这行还该不该有这个键」）。⚠️ `auth.js:108` 的同一地址**不算假话** —— 它写的是「**原**唯一**定义在 `HomePage.vue:116`」，`9b87ab9` 可证 ✓。⇒ **本笔不修**。⚠️ **挂账落点与归属（用户 2026-10-05 裁定 —— 必须是有笔名的挂账，不是孤儿待办）**： ⚠️ **本条第 5/6 点已结清（P2.6，2026-10-05，§5-AC）** —— 第 5 点的 `HomePage.vue:116`（`progress.md:402` 引用）与第 6 点的 `auth.js:118`（`ShoppingCart` / `HistoricalOrders`）均已在**原处**加按语、标为历史地址。
    1. **悬空事实**：`progress.md:401` 引的 `HomePage.vue:116`（原文「同行两键」）**已不存在** —— 该文件现在**零处** `loginUsername` / `merchantid`（`grep` 零命中）。
    2. **成因（已追历史）**：`9b87ab9` / `2ced01c` 时 `:116` **确有**那两键（`!!loginUsername || !!merchantid`，地址写得对）；**笔 3/8（`6639c1f`「用户与登录域 3 个 view 迁到 API 层」）**把它换成 `:118` 的注释 + `:119` 的 `isLogin = computed(() => isLoggedIn())` ⇒ 两键**整体消失**、地址自此悬空。⇒ 性质是**「悬空」不是「漂移」**。
    3. **为何本笔不修**：修它必须**重算 54 / 52 的逐出现口径**（那条口径本身是 §5-I / §5-J 的**记账结论**）—— 只改一个引用点、不动算术，等于把记账笔的结论改成**猜**。属记账笔，不属路由笔。
    4. **归属笔名 = `P2.6`（「sessionStorage 直接用」→ 凭据进 httpOnly Cookie）那笔** —— 它本来就要动 `auth.js` 的登录态口径与 `HomePage` 的 `isLogin` 来源，顺手重算一遍最省。**备选归属 = 界面优化笔**（若 `P2.6` 长期不动）。
    6. **【同族第 2 条 —— R4 新发现，并入本挂账】** `api/auth.js:118` 引的 `ShoppingCart.vue:88-93` / `HistoricalOrders.vue:36-41` 称「收敛**逐字相同**的 2 处降级链（customerid → merchantid → loginUsername）」—— **实测那两段已无降级链**（现为 `const loading = ref(false)` + 注释 + `const getBuyerId = () => resolveBuyerId()`）⇒ 与第 5 条**同类（悬空）**、**同一成因**（都是「凭据键被 API 层笔搬走、引用未跟」）。⚠️ **为何并入同一挂账**：那段注释**逐个列出了三个凭据键名** ⇒ 与第 5 条一样牵动 **§5-I / §5-J 的 54/52 逐出现口径**，修它 = 重算同一笔账。⇒ **归属笔名 = `P2.6`**（备选界面优化笔），本笔（R4）**不修**。 ⚠️ **已结清（P2.6，2026-10-05）**：`api/auth.js:118` 就地加按语（§5-AC）。
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

- **（14）登记新增手测项（留 T 批）**：**T-new-1** 未登录手敲 `#/` ⇒ 应落 `/customer`，且顶栏**右侧只剩「登录」**；**T-new-2** 已登录客户手敲 `#/` ⇒ `/customer`、已登录商家 ⇒ `/merchant`。⚠️ **同时必须复验 §5-W 的 T2**（未登录点搜索 / 分类 / 商品卡**仍弹「请先登录」**）—— 见 (7)。 ⚠️ **按语（§5-AG (13)，2026-10-06）**：**T-new-1 的顶栏判据改了** —— 右侧现为「**登录 / 注册**」（`nav.js:63` 的 `ANON_RIGHT`），不再是「只剩「登录」」；**T-new-2 不受影响**；末句的 T2 复验**已作废**（改判 2，见 §5-W (6) 按语）。

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

- **（9）`ChatRoom.vue:348/367` 的 `page: 'chat'` 残留 —— 清法：同行改写（用户裁决 D4）**：`router.replace({ query: { ...rest, page: 'chat' } })` → `router.replace({ query: rest })`。**不删行** ⇒ `ChatRoom.vue` **578 → 578 行（净零）**，`api-contract.md:192-195` 的 5 个 ChatRoom 地址、`progress.md:752` / `:787`、`CustomerPage.vue:65` / `MerchantPage.vue:69` 引的 `ChatRoom.vue:369` **全部零漂移**（这就是不删行的**决定性理由**）。⚠️ **语义零变更**：`rest` 仍照旧剥掉 `otherId` / `otherType` / `_t`；两个 `eslint-disable-next-line no-unused-vars` 仍需要（三个键还是解构未用）。⚠️ **不成环**（推理核过，非手测）：`replace` 后 `route.query.otherId` / `otherType` 都变 `undefined` ⇒ `watch` 的 `if (newOtherId && newOtherType)` 守卫不成立 ⇒ **不再 replace**。**删后 URL** = 当前路径 + **无 query**：`/customer/msg`、`/customer/user/chat`、`/merchant/msg`、`/merchant/user/chat` 四种（四条 ChatRoom 挂载点）。⚠️ **判据**：全仓 `grep "route.query.page"` **零命中** ⇒ 纯残留；`LEGACY_TAB` 只认**精确** `/customer` `/merchant` 而 `replace` 不动路径 ⇒ 两边互不触发（与 §5-X (12)1 / §5-Y 的登记一致）。⚠️ **按语（§5-AB，R6，2026-10-05）**：本条引的 `CustomerPage.vue:65` / `MerchantPage.vue:69` —— 那两条「**别把 ChatRoom 加进 `CACHE_TABS`**」的注释**已随两壳合一搬进 `AppShell.vue:99`**（两文件已删 ⇒ 原地址**悬空**）；本条另引的 `ChatRoom.vue:369` **指向别文件、地址未漂**。

- **（10）落码后实测数值（供复核）**：`router/index.js` **202 → 241 行**（两条记录**同行替换** + 尾部插入 39 行）；`ChatRoom.vue` **578 → 578 行**；`config/nav.js` **同行替换（净零）**。R3 登记的锚点 **`:51` / `:52` / `:125` / `:126` / `:128` / `:156` / `:157` / `:161` / `:185` / `:192` / `:197` 逐个回放，全部未漂**；只有 `export default router` 由 `:202` → **`:241`**（全仓**无**以 `:202` 为地址的引用，仅 §5-Z (10) 与 `progress.md:654` 以叙述句提过）。

- **（11）新发现（本笔核实中翻出，都不是本笔造的）**：
  - **D5 —— `§6-A` 的 **P1.3** 行引 `ChatRoom.vue:170`，实测 `:169` ⇒ 漂移 1（已就地订正）**：写这句的 `6afaac2`（2026-10-03）**当时是对的**（基线 `9b87ab9` 的 `return data.user` 在 `:170`），**笔 4/8（`5990d07`，2026-10-04「消息与好友域 4 个 view 迁到 API 层」）把它搬到 `:169`**、文档未跟。⚠️ 与 §5-Z (9) 第 5 条那处**同类不同因**：那处是**悬空**（写的时候就已经不在），这处是**漂移**（写的时候在、后被搬走）。
  - **D8 —— `router/index.js:31` 应为 `:30`（R3 自己写进去的，已就地订正）**：R3 记「同一句话在 `src/` 内有 3 处：`config/nav.js:15`、`router/index.js:**31**`、`CustomerPage.vue:70`」—— 实测那句「两壳的顶栏差异**只有两处**」在 **`:30`**（`:31` 是 R3 的标题行「`/` 由『裸挂 `HomePage` 的页面』改为『一条落点 redirect』」）。**3 处**（`config/nav.js:20`、`progress.md:867`、`progress.md:968`）**全部同行订正为 `:30`**。⚠️ **按语（§5-AB，R6，2026-10-05）**：本条 3 处中 **`CustomerPage.vue:70` 已随两壳合一悬空**（那句现居 `AppShell.vue:76-78`）；另 2 处（`config/nav.js:20`、`progress.md:867`）**地址未漂**。
  - **D9 —— `api-contract.md:192-195` 的 ChatRoom 四个地址陈旧（登记，不修）**：实测调用点 `:167`（userSelect）/ `:183`（conversations）/ `:208` + `:270`（history ×2）/ `:314`（send），而该表记 `:164` / `:184` / `:213` + `:279` / `:327`。⚠️ 其中 `:184` → `:183` **已登记**于 §5-V (5)3 / R1 的 §5-X (10)3（「R1 之外的前置漂移…… 不重开那笔账」）；**其余 3 个（`:164` 指注释、`:213`/`:279`、`:327`）本笔新发现、同样不修** —— 承 R1 已立的口径（改 `api-contract.md` 全表属**契约表重测笔**，不属路由笔）。⇒ **登记**，归属笔名待定（与 (11)D5 的 `6afaac2` 同族：都是「笔 4/8 之后契约表未重测」）。
  - **非漂移但记录在案（「注释锚点」一类）**：`GoodsSelect.vue:158`（`router/index.js:152` 引）与 `ChatRoom.vue:164`（`api-contract.md:195` 引）指的是**描述该行为的注释行**（代码分别在 `:160` / `:167`）—— 不是假话，**建议保留**（同 §5-W 那处注释锚点的先例）。

- **（12）验证（落码后、commit 前）**：
  - `npm run build` **绿**（警告 **2 条**，与 R3 基线**同数**，均为既有项）；
  - `npx eslint src --ext .vue,.js` **exit 0**、零 lint 输出；
  - **行号回放**（全仓 `router/index.js:N` / `ChatRoom.vue:N` 引用逐个回放实测行内容）：**全部指对**（本笔按净零设计 ⇒ 预期零漂移，实测一致）；
  - **dist 结构性取证**（排除 `*.map`）：`path:"/shoppingCart",redirect:` × **1**、`path:"/goodsSelect",redirect:` × **1**、**两者 `,component:` 各 0**（裸挂形态消失）；`redirect:` 全产物 **3 处**（= `/` + 两条收编，**均为函数引用** ⇒ 生效）；`path:"/product",component:` / `path:"/login",component:` 各 **1**（R5 与登录页**未被波及**）；`page:"chat"` **4 → 2**（剩下 2 处正是 `FriendRoom.vue:246` 与 `ProductPage.vue:447` 两个**合法生产者**）；`orderTab` **11** / `showSwitch` **5**（R2 基线，无回归）；
  - `git diff --stat` + 净零核查（见 (10)）。⚠️ 用户方案里的 `refcheck.sh` **全仓不存在**（`find -name "*refcheck*"` 零命中）⇒ 「行号回放」是**按同一口径手工做的**（grep 抽全部引用 + awk 回放实测行），登记此差异以免误以为脚本跑过。

- **（13）登记新增手测项（留 T 批，攒到 R1–R4 全 merge 后一次跑）**：
  - **T-new-3**：手敲 `#/shoppingCart` ⇒ 未登录**应落 `/customer/cart`**、商家登录**应落 `/merchant/order/cart`**；且**不应**出现那个点了没反应的 📋/🛒 开关（(8) 的死控件应已随裸挂形态消失）。 ⚠️ **按语（§5-AG，前端路由守卫笔，2026-10-06）**：**未登录那半条的判据已变** —— `/customer/cart` 与 `/merchant/order/cart` 都落在**受保护面**内 ⇒ 未登录手敲 `#/shoppingCart` 会**先被 `cartLanding` 收编、再被守卫拦下**，终态 = **`/login?redirect=/customer/cart`**（不再是 `/customer/cart`）。**「商家登录」那半条不变**（已登录 ⇒ 放行 ⇒ 落 `/merchant/order/cart`）；死控件那半条也不变（R4 已收编）。改读后的判据见 §5-AG (7) 的 **T-guard-8**。
  - **T-new-4**：手敲 `#/goodsSelect?kw=<词>` ⇒ 应落对应壳的搜索页**且关键词生效**（query 透传，(7)）；手敲不带 `?` 时落点相同、不报错。

- **（14）本笔不做**（按八条裁定）：不动 `ProductPage.vue`（R5）、`LoginPage.vue`（保持独立）、后端、`ci.yml`、A3 脚本、`src/test/**`；不抽 `AppShell`（R6）；**不合并 `landingFor` 与 `roleKey`**（D7）；`/product` 的 3 个生产者**一字不动**。

- **（15）与 R6 的关系**：R4 后 `router/index.js` 剩三件**有意保留**的事 —— ① `/product` 裸挂（`:125`，R5 不做 ⇒ 永久）；② `LEGACY_TAB` + `beforeEach`（`:156-171`；理由 ① 依赖 R5、理由 ②「书签 / 历史 / 手敲不受代码控制」永久）；③ 两壳父记录的**结构性重复**（只差 component / `subMenu` / `logout`）。R6 抽 `AppShell` 时 router 的改动量 = **两条父记录换 `component` + 补 `props`（nav 表 / `base` / `CACHE_TABS`）+ 一处 import**，估 **10~20 行**、不新增记录、不动 `children` ⇒ R4 **不给 R6 添堵，也不替 R6 抢活**。
- **（16）不加「更新记录」行**（照 `§5-H (3)` / §5-N / §5-Q (4) / §5-T (7) 先例）。

---

### 5-AB. R6 两壳合一笔（`feat/router-appshell`，2026-10-05）：**抽公共 `AppShell`**（重构本体最后一笔 = 纯消重）—— 破 ≤5（9 文件）

一句话：`CustomerPage.vue`（R1 客户壳）与 `MerchantPage.vue`（R2 商家壳）逐行 diff **只有 6 处语义差异**（见 (2)）⇒ 合成 `src/views/AppShell.vue`（**3 props + 零 slot**），两条父路由记录换成**同一个组件**、只差 `props`；**两个壳文件删除**（`git rm`）。

- **（1）台账与「破 ≤5」（用户裁决 D3：认，逐项给不可压的理由）**：
  - `src/views/AppShell.vue`（**新；283 行**）+ `src/views/CustomerPage.vue`（**删；240 行**）+ `src/views/MerchantPage.vue`（**删；240 行**）：三件是**同一件事**（合一），**不可拆** —— 只删不增 = 断链；只增不删 = 两份壳留在仓里，`CACHE_TABS` 与承载性注释永久三份。
  - `src/router/index.js`（**241 → 241**，**7 行**同行替换：import 2 行 + 注释 3 行 + `component:` 2 行）：新组件要有人挂。**不可压**。
  - `src/config/nav.js`（**69 → 69**）/ `src/views/HomeTab.vue`（**44 → 44**）/ `src/api/http.js`（**221 → 221**）：三处「现在时假话」的地址订正（见 (8)）。**不可压**：留着就把「该文件已删」这句假话留在仓里（「不留假话」，同 #11 / #42 / TD-01 口径）。
  - `docs/agent/progress.md`（本节 + **20 处按语**）/ `docs/agent/api-contract.md`（**220 → 220**，`:192` 合并重指）：两文档。
  ⇒ **9 文件（1 新 / 2 删 / 4 改 / 2 文档）**，同 R1（9 文件）/ R2（12 文件）的先例。

- **（2）两壳的差异实测 —— 本笔的全部依据**：`diff -u` = **8 个 hunk / 6 处语义差异**（其余逐字相同）：
  ① 根类名（`.customer-page` ⇄ `.merchant-page`；两份 CSS 的**体**逐条相同）→ 合一为 `.app-shell`；
  ② 顶栏表（`CUSTOMER_NAV` ⇄ `MERCHANT_NAV`）→ 走 prop `nav`；
  ③ **`getCurrentUser()` 的降级链顺序相反**（客户 `customerid` → `merchantid`；商家 `merchantid` → `customerid`）→ 走 prop `role`；
  ④ 消息页路径（`/customer/msg` ⇄ `/merchant/msg`）与 ⑤ 待搜索关键词的落点（`/customer/search` ⇄ `/merchant/search`）→ 由 prop `base` 拼（`AppShell.vue:72` 一行点名 ④⑤）；
  ⑥ 布局容器类名（`.cart-container` ⇄ `.order-container`）→ **两个类名都保留**（裁决 D7，见 (10)）。
  ⚠️ 第 ③ 条是 R1 / R2 / R4 三份 R6 草稿**都漏掉**的一条 —— 它**不是**「碰巧写成不同顺序」（两端 id 可以同时在：新标签页恢复成商家 Y 之后再登录成客户 B、而 `B.merchantId` 为空时 `restored.merchantId` 会**留着 Y**，见 `src/api/auth.js` 的 `restore` 段）⇒ **不能统一**，必须第三 prop（理由留在 `AppShell.vue:137-144`）。
  ⚠️ 因此「两个壳只差一个 nav 表」这句**不成立** —— 本笔后那句「顶栏只差两处」的**验收口径**改述并留在 `AppShell.vue:76-78`（它说的是**顶栏**，不是两个壳；R6 前在 `CustomerPage.vue:70`）。

- **（3）`AppShell` 的形态（用户裁决 D1：3 props + 零 slot）**：
  - props = `nav`（顶栏表）/ `base`（URL 前缀）/ `role`（降级链顺序），**全部 `required: true`**（`AppShell.vue:107-114`）；**零 slot** —— 内容区仍是 `<router-view>`，页面选择权在路由（两轴口径不动，见 (13)）。
  - `CACHE_TABS = ['HomeTab','GoodsSelect']`（`AppShell.vue:101`）**写死**、**不进 props**：两壳原本的值**逐字相同** ⇒ 传两遍是同一个常量、同一个理由。
  - `role` **不从 `base` 反推**：`base` 是 URL 前缀、`role` 是身份优先级，二者**恰好**同向但互不定义（改 URL 前缀不该顺带改身份判定）。
  - `defineProps` 上**必须有** `// eslint-disable-next-line no-undef`（`AppShell.vue:106`）：本仓 `eslint-plugin-vue` = **8.7.1**，`vue3-essential` 不带宏环境、它导出的 `setup-compiler-macros` 也**不含** `defineOptions` ⇒ 缺 disable 直接构建红（先例 `src/views/HomeTab.vue`）。
  - ⚠️ **更正 §5-AA (15) 的两处估计**：① 那里写「router 改动量 = 换 `component` + 补 `props`（nav 表 / `base` / **`CACHE_TABS`**）」—— `CACHE_TABS` **不传**（同值常量，见上）；② 「一处 import」实为**两行互换**（`:4` 客户壳 import → `AppShell`；`:5` 商家壳 import → `nav.js` 的 `CUSTOMER_NAV` / `MERCHANT_NAV`）⇒ router 的改动是 **7 行同行替换**（4 个 hunk）、不是一行。

- **（4）两条父路由记录保留、不合并**：`/customer` 与 `/merchant` 仍是**两条**记录，**不做**「一条记录 + 参数」——
  ① 两条记录的 `children` **本来就不同**（右起第 2 项与 `subMenu` 不同、`logout` 分支键不同）；
  ② 「一条记录 + 参数」在**静态路由表**里无处传参（参数只能来自 URL ⇒ 又要造一个假路径段）；
  ③ 合并会动 R3 登记的 `landingFor` / `roleKey`（`router/index.js:197` / `:217`）⇒ 越出「纯消重」。

- **（5）机制依据（为什么「同一个组件挂两条记录」是安全的）**：
  - vue-router **4.6.4** 在 `RouterView` 里把**路由记录的 `props`** 预绑到 slot 拿到的 `Component` 上（`node_modules/vue-router/dist/vue-router.mjs:1101-1106`）⇒ 两条记录各有自己的 `props`，**同一个组件被挂两次**、各自的 `props.nav` / `props.base` / `props.role` 互不串。
  - `RouterView` 的**深度**按「有 `components` 的 matched 记录」数（`inject(viewDepthKey, 0)`）⇒ `AppShell` 在深度 0、其内部 `<router-view>` 在深度 1，**与两壳时逐位相同**，无行为变化。
  - 每条记录**各挂一个** `<KeepAlive>` 实例 ⇒ 两个壳的缓存**互不影响**（`AppShell.vue:98`）。
  - ⚠️ **`isLoggedIn()` 不是响应式**（读 `src/api/auth.js` 的模块态 + `sessionStorage`）⇒ `navTop = navFor(props.nav, isLoggedIn())`（`AppShell.vue:257`）在 `setup` 里**只算一次**；**不能**改 `computed`（看起来更对、实际永不更新）。两壳原本如此，本笔照搬。
  - ⚠️ **壳不会因切 tab 重挂**（`AppShell.vue:87-90`）：`/customer` / `/merchant` 是**父记录**，`children` 之间切换**不重建父实例** ⇒ 5s 未读轮询「只跑一次」这个性质**仍成立**（后人若把子路由提成平级记录、或给壳加 `:key`，它会立刻消失）。

- **（6）净零手法（为什么全仓 `router/index.js:N` 地址一个没漂）**：`router/index.js` 的 **7 行**改动**全部同行替换**（import 2 行、注释 3 行、`component:` 2 行，共 4 个 hunk）⇒ **241 → 241**。同理 `config/nav.js` 69 → 69、`views/HomeTab.vue` 44 → 44、`api/http.js` 221 → 221、`api-contract.md` 220 → 220。`progress.md` 的 20 处按语**一律行内追加**（不加行）⇒ 追加本节之前仍是 **1095** 行、全仓 `progress.md:N` 自引用（最大只到 `:968`）**零漂移**；本节追加在文件末尾（`## 6.` 之前），其后**再无**被行号引用的内容。
  ⚠️ 落码中**自己撞到过一次**：`router/index.js:29` 的注释起初被写成 5 行（+4 行），当场改回 2 行同行替换 —— 净零是**约定**，破了要当笔报告（同 §5-X (10) 的手法）。

- **（7）为什么「删两个壳」而不是「让它们各薄包一层 `AppShell`」（裁决 D2）**：薄包一层能保住「文件仍在」⇒ 全仓对两壳的行号引用**一字不动**；代价是**两个 240 行的死壳永久留在仓里**（名字在、逻辑不在，`CACHE_TABS` 与注释三份，且后人会继续往里加东西）。用户选**删**（同 R1/R2「换文件不是换行号」的口径）⇒ 本笔把悬空**一次性清算**（见 (11)）。**收益**：`views/` 少两个文件、包内少一份重复代码、`AppShell` 成为**唯一**壳。

- **（8）4 处「现在时假话」订正 + 1 处澄清（同行改字，全部净零）**：
  ① `src/config/nav.js:15-20`：原文「两壳顶栏的差异**只有两处**」→ 改述为「**两套顶栏表**（`CUSTOMER_NAV` / `MERCHANT_NAV`）的差异只有两处」，并补 R6 记录 + 把句子出处从 `CustomerPage.vue:70` 改指 **`AppShell.vue:76-78`**。
  ② `src/config/nav.js:61`：「`CustomerPage.vue` 的 `getCurrentUser()`」→ **`AppShell.vue` 的 `getCurrentUser()`**（R6 前该函数在两壳里）。
  ③ `src/views/HomeTab.vue:9`：「这个 name 是**两壳里** `<KeepAlive :include>` 的匹配依据」→ **`AppShell.vue` 里**。
  ④ `src/api/http.js:72`：「轮询 …… `CustomerPage.fetchUnreadCount` / `MerchantPage.fetchUnreadCount`」→ **`AppShell.fetchUnreadCount`**（R6 前在两个壳里）。
  **澄清（不是订正）**：`docs/agent/api-contract.md:192` 不是假话 —— 它记的两个**指针**（`CustomerPage.vue:108` / `MerchantPage.vue:107`）在 R4 时**准确**；R6 后文件没了 ⇒ 属**悬空**。按裁决 D9 把该行**合并重指**为 `ChatRoom.vue:184`、`AppShell.vue:161` 并留沿革。

- **（9）`.empty-page` 终结（用户裁决 D8）**：两壳 `<style scoped>` 各有一条 `.empty-page`（`display:flex` 居中提示），R1 前是 `curPage` 认不出时的兜底；路由化后**没有「认不出」这回事** ⇒ 全仓 **0 使用**的死规则。两壳删除时**不搬进** `AppShell.vue`（其 `:281-282` 留说明，防后人以为漏搬）。⚠️ **按语（§5-AI-C，C 笔，2026-10-07）**：该说明随 C 笔样式块注释增行，现为 **`:326-327`**。

- **（10）两个容器类名都保留（用户裁决 D7）**：`.cart-container`（客户购物车）与 `.order-container`（商家订单中心）**不合并** —— 后者多一行 `position: relative`，而 `OrderCenter.vue:200/:235` 与 `ShoppingCart.vue:205/:240` 都有 `absolute` 子元素 ⇒ 合并 = **改行为**，归界面优化笔（理由留在 `AppShell.vue:376-379` —— ⚠️ **C 笔样式块增行后为 `:374-377`**）。两类名仍由路由记录的 `meta.layout` 选中。

- **（11）悬空清单 —— 本笔后，全仓对两个**已删壳文件**的引用处置**（口径：**过去时 / 历史快照保留原样**；**现在时一律按语**，或并入本节清单）：
  - `docs/agent/progress.md`：命中 **59 行**。加 R6 按语的 **19 处**：`:224`（挂账「文档悬空引用」条）、`:569`、`:570`、`:629`、`:664`、`:691`（**§5-W 整节**）、`:730`（**§5-X 整节**）、`:801`、`:831`（**§5-Y 整节**）、`:867`、`:870`、`:912`（**§5-Z 整节**）、`:964`、`:968`、`:1030`、`:1036`、`:1076`、`:1082`、`:1086`。**其余一律是过去时**（「R1 前…」「`9eb0729` 实测…」「本笔前…」）⇒ 按 §5-X 节首那条整节按语立的口径**保留原样**（历史句保留 + 按语，同 §5-T (5) / §5-W (5)）。
  - `docs/agent/api-contract.md`：`:192`（**已合并重指**，见 (8)）—— 全文件仅此 1 处。
  - `src/`：`api/auth.js` 6 处（`:146/:151/:156/:166/:181` 全是历史句；**`:44` 是现在时、已登记 → P2.6**，见 (12)）；`router/index.js` 5 处（`:25` / `:29` 已就地改述；`:137/:138/:144` 是历史句）；`views/UserCenter.vue` 3 处（`:21/:60/:66` 历史句）；`views/GoodsSelect.vue` 2 处（`:118/:166` 历史句）；`views/merchant/OrderPane.vue` 1 处（`:24` 历史句）；`views/AppShell.vue` 2 处（`:66/:78` **自述 R6 来历**）；`config/nav.js` 1 处（`:17` 已就地订正）。
  ⇒ **一句话口径**：本笔后全仓**没有**任何「现在时」把两个壳当作**存在**的说法（唯一例外 `api/auth.js:44`，它已挂 P2.6）。
  ⚠️ **索引的用法（§5-P 口径）**：上列行号只是**按语落点**在写入时的坐标 —— 本文件在**这些行之前**一旦增删即会漂。**权威 = 各节自己的按语本身**：以 `§5-AB` 为关键字 grep 本文件 = **20 行**（**19 处按语** + 本索引行本身），本条只做索引、**不承担坐标承诺**。

- **（12）本笔新发现的漂移 3 条（登记，**不修**）**：
  - **D-new-1 `src/api/auth.js:44`**：现在时表述「前端写口只有两类调用方 —— 登录（`LoginPage.vue`）与退出（**`CustomerPage` / `MerchantPage`** / `http.js` 的 401 出口）」—— 自 R1/R2 起**已不成立**（退出点早已只在 `UserCenter.vue:52-55`），与同文件 `:146/:151` 的历史句**自相矛盾**。按**裁决 D6** → **P2.6 那笔**（凭据驱动 → 会话驱动）；本笔**不修、也不动 `auth.js`**（它就是 P2.6 的落点）。⚠️ 该行「两个壳」这半句随 R6 **一并悬空** —— P2.6 动手时**一起**处理。 ⚠️ **已结清（P2.6，2026-10-05，§5-AC）**：`src/api/auth.js:44` **就地**改成「退出（`UserCenter.vue` / `http.js` 的 401 出口）」，并注明原写的两个壳是 R1/R2 前的旧形状。
  - **D-new-2 `docs/agent/api-contract.md:195`**：引 `ChatRoom.vue:164`，实测 `:164` 是**注释行**（「获取用户信息」），代码在 `:167`。与 R4 的 D9 **同族**（都是「笔 4/8 之后契约表未重测」），**合并挂账**、**归属笔名待定**。
  - **D-new-3 `src/components/HelloWorld.vue`**：**孤儿文件**（Vue CLI 脚手架残留），全仓 **0 引用**（`grep -rn "HelloWorld" src/` 只命中自身）⇒ 归**界面优化笔**。

- **（13）对 §5-X (13)「R6 该接的活」的逐条答复**：
  1. **布局类名下移**（原承诺：把「内容区外壳 div」连同类名下移到各页组件、`meta.layout` 随之退役）—— **推翻**（**裁决 D4**）：① 下移要动**两壳子树里的全部页组件**（本笔实测：路由表里挂的**不同组件 = 11 个** —— `HomeTab` / `GoodsSelect` / `ChatRoom` / `ShoppingCart` / `UserCenter` / `UserInfoPage` / `HistoricalOrders` / `FriendRoom` / `OrderPane` / `GoodsAdd` / `GoodsUp`；`OrderCenter` 由 `OrderPane` 内部挂、不在路由表里。§5-X 写承诺时的口径估「**12 个**」，量级一致），远超任何预算；② `meta.layout` 是「路由决定布局」的**自洽**机制（一条 computed + 路由表里的字符串），那条承诺写下时（R1）还没看到它跑起来的效果；③ R6 的章程是**纯消重**。⇒ 所有权仍在壳（`AppShell.vue:127` 的 `layoutClass`），就地说明留在 `AppShell.vue:123-126`。
  2. **路径风格统一**（`views/customer/` 的存废）—— **定案、结案**（**裁决 D5**）：现行口径即**最终状态** —— **跨壳共享的页面组件平铺 `views/`**（`HomeTab` / `UserCenter` / `GoodsSelect` / `ChatRoom` / `ProductPage` …）、**单壳专属的进该壳子目录**（`views/merchant/OrderPane.vue`）；**不建 `views/customer/`**（客户壳没有专属子页面组件，为对称而建 = 凭空造约定）。
  3. **`.empty-page`（§5-X (7) 按语点名）**—— **终结**（**裁决 D8**，见 (9)）。
  4. **`vue/setup-compiler-macros` 的评估窗口**（§5-X (15) / §5-Y (9) 登记「R6 之后、C3 之前」）—— 窗口**已到**，但本笔**不开**：开它 = 动 `.eslintrc` / 构建配置，超出「纯消重」；且 §5-Y (9) 已实测「开了也只解决一半」（8.7.1 的环境**不含** `defineOptions`）⇒ 逐条 disable **仍是长期要求**（`AppShell.vue:103-106` 照办）。**重申**：该笔仍在「R6 之后、C3 之前」。

- **（14）验证（落码后、commit 前，全部实测）**：
  - **`npm run build` 绿** —— `DONE Compiled successfully`，产物 `dist/js/app.84f15326.js` 75.42 KiB / `dist/js/chunk-vendors.55f81f68.js` 130.64 KiB / `dist/css/app.74b6d88f.css` 37.91 KiB（`rm -rf dist` 后**干净重建**）。
    ⚠️ **警告从 2 条变 0 条** —— R2/R3/R4 记的都是 **`Compiled with 2 warnings`**（`entrypoint size limit` + `webpack performance recommendations`），本笔实测 **0 条**。**解释（推断档，依据可复核）**：webpack **5.106.2** 的默认阈值 `maxEntrypointSize = 250000`（`node_modules/webpack/lib/config/defaults.js:1860-1861`），而 `SizeLimitsPlugin.getEntrypointSize()` 把 **entrypoint 的全部 file**（含 MiniCssExtract 出来的 CSS）**按字节相加**、`> 250000` 才发（`node_modules/webpack/lib/performance/SizeLimitsPlugin.js:91-100`）⇒ 本笔现值 = 77,225 + 133,778 + 38,824 = **249,827 B**，**距线只有 173 B**；R2–R4 时同一份包里含**两份**壳代码（另一份 240 行组件 + 重复 CSS）⇒ entrypoint 只会更大 ⇒ 越线（未重测当年的字节数，故标**推断**：警告文案逐字相同、且判据只有 `size > 250000`）。**这不是「本笔消除了警告」** —— 是**同一个既有项越线与否**；且**余量只剩 173 B**：日后任何 +200 B 级改动都会让这 2 条回来（**不是回归**，是正常的体积建议）。⚠️ **必须说清（用户 2026-10-05 点名，落码时补）**：**R6 后警告 2 → 0，是删两壳让 entrypoint 刚好掉到 `250000 B` 线下（余量 173 B），不是本笔消除了警告。界面优化笔动 CSS 后大概率回来 —— 这是既有项，不是新债。** ⇒ 后人读 §5-Y / §5-Z / §5-AA 的「2 warnings」与本节「0 warnings」**不是矛盾**，是同一条体积建议在阈值两侧。
  - **`npx eslint src --ext .vue,.js` → exit 0、零输出**。⚠️ **首次是红的**：`AppShell.vue 57:58 error Irregular whitespace not allowed`（`no-irregular-whitespace`）—— 起因是我在 `）` 与 `⇒` 之间写了一个 **U+3000 全角空格**；换成普通空格后转绿。**记一笔**：中文注释里的全角空格会**直接红**。
  - **行号回放（手工；`refcheck.sh` 全仓不存在，同 R4 登记）**：全仓 `router/index.js:N` = **22 个去重地址 / 31 处引用**，逐个回放**全部命中**（净零 ⇒ 预期零漂移，实测一致）；新增的 `AppShell.vue:N` 引用（`:76-78` / `:99` / `:101` / `:157`）逐个实测命中。
  - **`dist` 结构性取证**（只 grep 产物 `js/app.*.js`，**排除 `*.map`**）：
    - `path:"/customer"` **1** / `path:"/merchant"` **1**，两者 `component:` **同为压缩标识符 `Ve`**，而 `Ve = withScopeId(qe, [["__scopeId","data-v-e56957b4"]])`、`qe.__name === "AppShell"` ⇒ **两条记录挂的是同一个组件**（本笔的核心判据）。
    - `customer-page"` / `merchant-page"` → **0**（旧类名未残留）；`page:"chat"` → **2**（R4 基线，无回归）；`orderTab` **11** / `showSwitch` **5**（R2 基线，无回归）。
    - props **三键**：`nav:` **3** = `AppShell` 的 `defineProps` 1 + 两条父记录 2；`base:` **6** = 组件 `defineProps` 2（`AppShell` / `HomeTab`）+ 父记录 2 + `path:""` 子记录 2；`role:` **4** = `api/auth.js` 的 `role:"role"` 映射 1 + `defineProps` 1 + 父记录 2。⚠️ 方案里「三键各 2 处」的口径 = **两条父记录各 1 处**，实测命中；原始计数更大是因为**组件声明与子记录里的同名字段**也算命中 —— 报数按口径注明。
  - **`git diff --stat` + 净零核查**：`router/index.js` **241 → 241**、`config/nav.js` **69 → 69**、`views/HomeTab.vue` **44 → 44**、`api/http.js` **221 → 221**、`api-contract.md` **220 → 220**、`progress.md` **1095 → 1095**（本节之前；本节追加在其后）；新增 `views/AppShell.vue` **283**；删除两壳各 **240**。
  - **手测：不跑**（用户裁定 T 批攒到 R1–R6 全 merge 后一次跑）。

- **（15）手测项（留 T 批，本笔不单跑）**：**T-new-5** 已登录客户手敲 `#/customer` ⇒ 顶栏 4 项、切 tab **不重建壳**（轮询不叠加）；**T-new-6** 已登录商家同法在 `#/merchant`；**T-new-7** 商家登录且客户 id 同时存在时，`fetchUnreadCount` 取的是**商家** id（降级链顺序，`AppShell.vue:137-144`）；**T-new-8** 红点清零：进 `#/…/msg` 立刻清、进 `#/…/user/chat` **不清**（`AppShell.vue:200-210`）。

- **（16）本笔不做**（按十条裁决，不变）：不动 `ProductPage.vue`（**R5 永久不做**）、后端、`ci.yml`、A3 脚本、`src/test/**`；不动界面样式（界面优化笔）；**不合并** `landingFor` 与 `roleKey`（**裁决 D10** ⇒ 已移出 R6、改挂 §5-C 待定笔）；**不合并** `.cart-container` / `.order-container`（D7）；**不建** `views/customer/`（D5）；**不动** `src/api/auth.js`（D6 ⇒ P2.6）。

- **（17）不加「更新记录」行**（照 `§5-H (3)` / §5-N / §5-Q (4) / §5-T (7) 先例）。

---

### 5-AC. `P2.6` 凭据驱动 → 会话驱动（`feat/auth-session-driven`，2026-10-05）：**前端明文密码退场 + `/user/me` 成为唯一资料读口** —— 10 文件 = 8 代码 + 2 文档（⚠️「≤5」是 R1–R6 重构序列的预算，本笔由用户直接给定落码清单，**不受其限**）

> **性质**：前端 **5 代码文件**（`api/auth.js` / `LoginPage.vue` / `GoodsUp.vue` / `UserInfoPage.vue` / `api/http.js`）+ 后端 **2 代码文件**（`user/service/UserService.java` / `user/controller/UserController.java`）+ **1 测试文件**（`smoke/AuthSessionSmokeTest.java`）+ **2 文档**（本文件 / `api-contract.md`）。
> **来源**：§5-J (4) 的裁定（**2026-10-04**，用户）——「删前端 4 个读写口 + 拆 `UserService.java:185` 的凭据重验」；前提 = 鉴权笔的 **httpOnly Cookie 会话**（已由鉴权笔 1–4 + 会话恢复笔落地）。
> **一句话**：把前端「自持明文密码、拿它去 `/user/getUserInfo` 换资料」这条**凭据驱动**老路，换成「凭 Cookie 会话、`/user/me` 直接答资料」的**会话驱动**新路。

- **（1）两跳复用（裁决 D1）——`/user/me` 与 `/user/getUserInfo` 返回同一个 `Map`。**
  `meInfo(userId)` = `publicInfo(userId)`（缓存读 `userPublic`，给 `username`）→ `profileOf(username)`（`user` 表整行 + 条件头像 `image`）。⇒ 两者**逐键相同、只差 `success` 键**（`Result.ok(Map)` 顶层平铺）。`profileOf` = **步骤 ②③ 的逐字搬移**（含无条件 `put("image", …)` 与吞异常 `catch`），故「同一张 Map」不是巧合而是**同一段代码**。
  ⚠️ **最关键的坑（测试专属，已记入 `userRow` 的 javadoc）**：`profileOf` 的 `put("image", image)` 是**无条件**的 ⇒ 行 `Map` **不可变**时该 `put` 抛 `UnsupportedOperationException`、**被同块 `catch` 静默吞掉**、头像凭空消失。生产行**可变**（`queryForMap` 返回可变 `HashMap`）⇒ 只有测试会踩到。**本笔实测踩到两次**（初用 `Map.of` 造行 ⇒ `$.image` 断言红）。

- **（2）后端改动（净 +57 行，全在 `UserService.java` ~`:220` 之后）。**
  `UserService.java` **378 → 435**：新增 `private Map<String,Object> profileOf(String username)`（`:273`，步骤 ②③ 搬移）与 `public Map<String,Object> meInfo(String userId)`（两跳）。`getUserInfo(username, password)` **保留**（步骤①凭据校验 + `return profileOf(username)`）。⇒ `:32` / `:98` / `:185` **回放 IDENTICAL**（新增全在 `:220` 之后）；`selectRoleForProfile` 的**真实调用点** HEAD 在 **`:242`**、本笔后 **`:239`**（§5-J (4) 原记的 `:185` **在 HEAD 即已偏**，见 (6)）。
  `UserController.java` **230 → 230（净零）**：`me` 改调 `userService.meInfo(userId)`（同行替换）；`me` 的 javadoc 与 `@RequestParam` 段**同行重写**；`:133-135` 的「前端三个调用点」**就地订正**为「P2.6 起 **0 个调用方**」。⇒ `:23` / `:150` / `:219` IDENTICAL；`:133` / `:171` **同行号变内容**（引用其旧文者须更新，已在下文 (6) 处理）。
  ⚠️ **不变量（裁决 D1/D4）**：`UserCacheService` / `UserDao` / 任何 SQL **一字未动**；`image` 的跨表处理**留在 `profileOf`**、不下推 DAO；`updateAvatar` 的 `login.Role` 与 `profileOf` 的 `user.usertype` **不合并**；缓存载荷**不扩宽**。

- **（3）前端改动（5 文件，全部**净零**）。**
  `api/auth.js` **202 → 202**：`K.password` 删除（墓碑注释）、`getPassword()` 删除（墓碑）、`setCredentials` → **`setUsername`**（只写用户名）、`clearCredentials` 少清一键。头部 `:3-8` 就地改成**历史口径 vs 现状**（54 / 6 = 基线历史值；今日登录态 **4 键**、登录密码键归零），**避免写出该键字面量**（否则复检 `grep` 命中注释）。
  `LoginPage.vue` **470 → 470**：登录成功后第二发改走 `POST /user/me`（`{allowUnauthorized:true}`）+ `setUsername`/`setRole`。`GoodsUp.vue` **590 → 590** / `UserInfoPage.vue` **261 → 261**：读口由 `/user/getUserInfo`（带密码）改为 `/user/me`（无参）；守卫由「有 `username`」改为 **`if (!isLoggedIn()) return`**（匿名访客仍**零请求**）。
  `api/http.js` **221 → 221**：仅 `allowUnauthorized` 的注释（`:9-10`）改口径 —— 今日 **4 个调用点**（`main.js:28` 启动探针 + 三个资料读点 `LoginPage.vue:144` / `GoodsUp.vue:153` / `UserInfoPage.vue:99`）。**不是全局开关**，且**仍然照抛**（不吞异常）。
  ⚠️ `role` **仍取自 `loginSelect` 的文本**（裁决 D5：**不**把 `role` 加进 `/user/me` 投影 —— 它属 `login` 表，加了就是口径分叉，见 `auth.js:98-103`）。C3 若要走恢复路径拿 `role`，是**另一笔**。⚠️ **多账户隔离笔（2026-10-06）订正 —— 取代 P2.6 的 D5 口径**：`/user/me` 自本笔起**答 `role`**（身份的两条口径见 `AuthSession` 类注释；`auth.js:98-103` 已同步写订正）⇒ 恢复态**拿得到**角色，本口不再必然为 `null`。⚠️ 由此 C3 的口子**变了形**：不再是「另开一笔补 `role`」，而是「`role` 已在手，缺的是细粒度权限模型」（见 §5-AF (5)）。

- **（4）`AbstractSmokeTest`「待定」的**解决**（用户要求 ①）——**结论：**不改**，理由成文。**
  新 stub（`queryForMap` 行 + `query(contains("login"))` + `query(contains("customerimage"))`）**只落在测试类本地**，不进共享基类；共享 `query(String, RowMapper, Object...)` 重载的歧义，用**不相交的 SQL 子串 matcher** 化解。⇒ 基类**不动**（**代码**文件数 8 而非 9；连 2 文档共 10）。跨两个顶层类共用的 `userRow(...)` 做成 **`static` 包级** helper、以 `AuthSessionSmokeTest.userRow(...)` 调用（`AuthSessionSmokeTest` 与 `AuthSessionSwitchOnTest` 是**同一文件的两个顶层类**，private 不共享）。**测试数 13 不变。**

- **（5）验证（提交前全绿；本笔不 commit）。**
  - 后端：`./mvnw -B test` = **209 run / 0 failures / 0 errors / 19 skipped / BUILD SUCCESS**。⚠️ 口径：surefire 目录**残留上次 .txt**，报数须**按时间戳过滤**（否则累加出 217/8 的假数，见 `surefire-stale-reports`）。
  - 前端：`npm run build` **exit 0**；`npx eslint src --ext .vue,.js` **exit 0**。
  - `grep -rn "loginPassword" src/` = **0**（复检要求）。
  - 关键断言：`AuthSessionSmokeTest` 实测 `/user/me` 去 `success` **逐键等于** `/user/getUserInfo` 的响应（两跳端到端走通）。
  - `dist` 结构探针：`loginPassword` **0** / `/user/me` **1** / `allowUnauthorized` **5** / `getUserInfo` **2**。
  - 净零复核：`LoginPage.vue` 470、`GoodsUp.vue` 590、`UserInfoPage.vue` 261、`auth.js` 202、`http.js` 221、`UserController.java` 230、`api-contract.md` 220；`UserService.java` **+57**（有意）。

- **（6）漂移结清 + 归属（用户要求 ②：**不留孤儿**）。**
  - ✅ **三条悬空引用结清**（§5-C `:224`）：① `progress.md:402` 的 `HomePage.vue:116`（⚠️ §5-C 原记 `:401`，**内容实在 `:402`** —— 见 §5-Z (9) 第 5 条）② `api/auth.js:118`（`ShoppingCart` / `HistoricalOrders`）③ `api/auth.js:44`（两个壳 → `UserCenter.vue`）—— **均在原处加按语**（本笔落码时全部就地关闭）。
  - ✅ **`api-contract.md`**（净零 220）：`:38`（`/user/me` 返回形态 → `user` 整行 + `image`）、`:46`、`:161-164`（差集成员互换、**仍 4**）、`:171`（行改键为 `/user/me`、`getUserInfo` 归零）、`:203`（行改键为 `/user/getUserInfo`）。
  - ✅ **`progress.md` 就地按语**：`:224` / `:402` / `:414` / `:573` / `:632` / `:969` / `:974` / `:1121` / `:1162`。
  - ⚠️ **本笔新发现的漂移（第 9 条）**：`progress.md:414` 引的 `UserService.java:185` **在 HEAD 即已偏**（HEAD `:185` 是 `loginAdd` 形参行；`selectRoleForProfile` 的真实调用点在 HEAD **`:242`**）⇒ 本笔后为 **`:239`**（javadoc 压缩 3 行 ⇒ 上移）。已并入 `:414` 的按语。
  - ⚠️ **另发现（非本笔引入，**已就地补数**）**：`api-contract.md:161` 的 `42` 是 **2026-10-03 基期值**；**现状 = 43** —— 会话恢复笔的 `main.js:28` 探针未计入基期（本笔实测 `grep -rn "http\.\(post\|get\)(" src/` = **43**），**不同路径仍 31**（`/user/me` 进 / `/user/getUserInfo` 出）⇒ 差集仍 4。**处置 = 就地写「基期 42 / 现状 43」并注明来源**（`:161`；⚠️ **按语（§5-AD 翻开关笔，2026-10-05）：这个「现状 43」同日又被翻笔推成 `44`**（`UserCenter.vue:64` 退出销会话 +1）⇒ 今日 `api-contract.md:161` 已写「现状 44 / 不同路径 32 / 差集 4 → 3」）—— **不**指向已完成的笔（`#48`「前端 API 层规范化」已交付，不能再当归属）；§6 头部 / P2.1 / P2.3 及 §5-I / §5-S 里的 `42` 均为**基期口径**，按原样保留。
  - ⚠️ **`WebAuthConfig.java:90-95` 缺守卫**（用户要求 ② 点名；⚠️ **裁决 D5（翻开关笔，2026-10-05）：地址由原记 `:83-90` 订正为 `:90-95`，归属由「界面优化笔（C3 笔）」改指「前端路由守卫笔」** —— 理由：界面优化笔是**样式**笔，路由守卫不是样式；同 §5-AD D3）：**归属笔名 = 前端路由守卫笔** —— 见「本笔不做」，本笔**不动**鉴权配置。
  - ⚠️ **`/user/getUserInfo` 的最终去留**（= §5-J (4) 那一半的挂账）：**归属笔名 = 「跨四域清除 schema 前缀」笔**（或独立记账笔）—— 删它会使 `UserDao.selectRoleForProfile` 的 SQL 字面量消失，把全仓「硬编码 `mall_management_sql.` 前缀 = **39 处**」基线拉到 **38**、端点数 **35 → 34** ⇒ **必须与记账笔同批**，本笔**只保留、不删**。

- **（7）手测项（留 T 批，本笔不单跑；用户裁定 六、不手测）**：**T-new-9** 登录成功后 `sessionStorage` **不再**有密码键（只有 `loginUsername` / `merchantid` / `customerid` / `role`）；**T-new-10** 开新标签页直入 `#/merchant/goodsUp` 与 `#/customer/user/info` ⇒ 头像 / 店名 / 10 列渲染正常（会话恢复 + `/user/me` 通路）；**T-new-11** 退出后再进这两页 ⇒ 回落默认头像、**无错误弹窗**（`isLoggedIn()` 为假 ⇒ 直接 return）。 ⚠️ **按语（§5-AG，前端路由守卫笔，2026-10-06）**：**T-new-11 的前置已不可达** —— 这两页现在都在**受保护面**内 ⇒ 退出后手敲会被守卫**直接送 `/login`**，页内那条 `isLoggedIn()` 为假 ⇒ return 的分支**根本不会被执行**。要复现该条只能用「本地登录态未清、而**服务端会话已失效**」构造（或在页内把 storage 手删）。**T-new-10 不受影响**（登录态下进得去）。

- **（8）本笔不做**（用户裁定 八）：不翻开关（`app.auth.enabled` **仍 `false`**）、不动 `ProductPage.vue`（R5 永久不做）、不动界面样式（界面优化笔）、不动 C3、不动 `ci.yml` / A3 脚本、不动 `UserCacheService` / `UserDao` / SQL、**不 merge**（PR 由用户合）。

- **（9）不加「更新记录」行**（照 `§5-H (3)` / §5-N / §5-Q (4) / §5-T (7) 先例）。

---

### 5-AD. 翻开关笔（`feat/auth-enable-final`，2026-10-05）：`app.auth.enabled` **`false` → `true`** —— 鉴权自本笔起**真正生效**（≈15 文件 = 7 代码/配置 + 8 文档/注释）

> **上游**：§5-R（鉴权笔 3「**不翻开关**」+ 真容器受控取证）／§5-S（鉴权笔 4：前端接上 `credentials` + 401 出口）／§5-U（会话恢复笔：启动探针）／§5-V·§5-W（`/` 落点守卫 + 登录提示条）／`P2.6`（§5-AC：凭据驱动 → 会话驱动）。**翻的前置条件至此全齐** —— 正是 §5-R `:499-500` 自己写下的三条（「翻的时机在笔 4 之后」「**单列一笔**」「要改 a3 显式 `false` 护栏 + smoke 类适配」）。

**（1）为什么必须单列一笔（不是把一行 `false` 改成 `true`）** —— 翻会让**三处**同时变脸，任何一处没护栏就当场红：
  ① **10 个 smoke 类**（`AbstractSmokeTest:47` 的 `@WebMvcTest` 切片扫到 `WebAuthConfig`）—— 打非白名单端点者**全被闸 401**；
  ② **A3 真容器 job**（`SPRING_PROFILES_ACTIVE=a3`；`ConcurrencyRunner` 发**真实 HTTP** 打 `orderAdd` 等 **5 个非白名单**端点）—— 全 401，且 `continue-on-error: true` 会把这个红**系统性抹平**（同 §5-E / §5-G 的告诫）；
  ③ **前端**（退出登录只清 `sessionStorage`、**不销服务端会话**）。

**（2）三档现值表（翻后；`grep app.auth.enabled` 的权威口径 —— 可复核）**

| 文件 | 值 | 理由 |
|---|---|---|
| `Mall-management-server/src/main/resources/application.properties` | **`true`** | **本笔落点** —— 主配置，现网生效 |
| `.../src/main/resources/application-a3.properties` | **`false`**（本笔新加显式 pin） | A3 的 5 个非白名单端点 + `ConcurrencyRunner.lockAcquires()` 读 `/actuator/metrics/**`（**不在白名单**；`r.code != 200` ⇒ `-1`「无法判定」）—— 第二重保护面 |
| `.../src/test/resources/application-smoke.properties` | **`false`**（本笔新加显式 pin） | `@ActiveProfiles("smoke")`（`AbstractSmokeTest:47-48`）激活 ⇒ 10 个 smoke 类**零改动**保持全绿 |

> ⚠️ **`@Value("${app.auth.enabled:false}")` 的兜底**仍是 `false`（用户裁决 **D2**）—— 兜底是「属性**缺失**」的口子，与「主配置**现值**」是两件事。**不改它**：若改成 `true`，「**谁删了那一行**」就从「静默关」变成「静默开」（闸在无人察觉时生效），风险方向反了。⇒ 登记为**独立欠账**（`WebAuthConfig:137` 的兜底）。

**（3）用户四项裁决（本笔照办）**
  - **D1 文档订正范围（8 处纳入本笔）**：先前 6 份文档 + 2 处 javadoc 写着「开关**默认 `false`** ⇒ 对现网零影响」—— 翻后**全是假话**，本笔**就地全部订正**（清单见 (6)）。理由：不许假话。
  - **D2 兜底仍 `false`**（见上表下注）。
  - **D3** 6 条 `REACHABLE_BUT_NOT_PUBLIC` 端点的前端守卫缺口**不塞进界面优化笔（C3 是样式笔）** ⇒ 归属新的具名笔「**前端路由守卫笔**」。
  - **D4 前端退出时序**：fire-and-forget + **fail-open** + `allowUnauthorized: true`（见 (5)）。
  - **D5**（落码中接受的订正）：`progress.md:1189` 原把「`WebAuthConfig.java:83-90` 缺守卫」判给「界面优化笔（C3 笔）」，与 D3 冲突 ⇒ **改指「前端路由守卫笔」**，并把失效地址 `:83-90` 订正为实测 **`:90-95`**。⚠️ **`:90-95` 的来历（实测，非推算）**：区块首行 `h3` 在 HEAD 与本笔**都在 `:90`**（未动）；变的是**块尾 `:94` → `:95`** —— 本笔把末句另起一行（文件整体仍 135 行，那 +1 被上方注释压缩吸收）。规划期写的「`:89-94`」是**按「h3 会下移 1 行」推的、未实测**，故不采用。

**（4）四处核实（落码前必答；全部实测）**
  1. **smoke profile 的激活机制**：Spring Boot **不**自动加载 `application-smoke.properties` ⇒ 必须核 `@ActiveProfiles`。实测 `AbstractSmokeTest:47-48` 有 `@ActiveProfiles("smoke")`，且 `@ActiveProfiles` 是 `@Inherited` ⇒ **10 个 smoke 子类全覆盖、基类零改动**。⚠️ 备选 (b) `src/test/resources/application.properties` **不可用**：它**屏蔽**主 `application.properties`（`target/test-classes` 先于 `target/classes`）⇒ `@SpringBootTest` 的 `MallManagementApplicationTests` 丢 `spring.datasource.*` 而启动失败。
  2. **`/user/logout` 的方法**：`@PostMapping`（`UserController:294`）⇒ 前端写 `http.post`。
  3. **启动探针**：`main.js:28` 调 `/user/me` **已带** `allowUnauthorized: true` ⇒ 翻后未登录访客**静默继续**，首页零变化。
  4. **启动自检日志落点**：`WebAuthConfig` **构造器**（唯一读该 `@Value` 处），ASCII 片段 **`AUTH`**，与 `app.lock.*` / `app.cache.*` / `app.order.state.*` 的「启动期打印生效值」惯例对齐（结清 §5-R (i)）。

**（5）前端退出（D4 落地，`UserCenter.vue:64`）**：`http.post(endpoints.user.logout, null, { allowUnauthorized: true }).catch(() => {})` —— **放在 `router.push('/login')` 之后**（保 `:52-55` 行号锚）。三条语义：闸开之后**只清本地 `sessionStorage` = 没登出**（cookie 还在，直开 `#/customer` 照样取数据）⇒ 必须销**服务端会话**；`allowUnauthorized` 让「未登录时点退出」**不进 401 出口**（不置闩锁、不重复跳转）；**不 `await` + `.catch` 吞掉** = fail-open（后端不可达 / 超时也不把用户困在「看起来还登着」的界面）。**残留**（服务端会话活到过期）是有意接受的代价。⚠️ `endpoints.js` 的 `logout` 行已注明「**不在白名单** ⇒ 未登录调它**必 401**」。

**（6）本笔文件清单（≈15，破 ≤5 —— 用户已批）**
  代码 / 配置 **7**：`application.properties`（翻值 + 同段注释改写，**净零**）／`application-a3.properties`（追加 pin 段）／`src/test/resources/application-smoke.properties`（追加 pin 段）／`AuthGateSmokeTest.java`（**净零**：文案就地改 + 层清单）／`AuthSessionSmokeTest.java`（**净零**：2 处文案）／`api/endpoints.js`（`user.logout` 1 行 + 头注释计数）／`views/UserCenter.vue`（+2 import，两处注释压缩吸收 ⇒ 锚点 `:52-55` 不漂）。
  文档 / 注释 **8**：`progress.md`（本 § + `:84` / `:499` / `:524` / `:1188` / `:1189` 按语）／`api-contract.md`（`:9` / `:39` / `:46` / `:47` / `:161` / `:164` / `:204`）／`business-decisions.md:46`／`context-packs/three-layers.md:119`·`:185`·`:198`／`defect-survey.md:117`／`project-map.md:81`／`WebAuthConfig.java`（**净零 135 行**：+启动日志、javadoc 就地改）／`UserController.java`（**净零 230 行**：4 处 javadoc 就地改）。

**（7）净零与行号回放（本笔核心手法）**：`WebAuthConfig.java` **135 行（净零）** ⇒ 三处外部引用（`progress.md:627` → `:97-113`、`:693` → `:103-107`、本节 → 6 端点块 **`:90-95`**）逐条回放**未漂**；`UserController.java` **230 行（净零）**；`UserCenter.vue` 的 `:24` / `:38` / `:44` / `:52-55` 未漂。

**（8）落码后实测（全部本地可复核）**
  - **前端**：`npm run build` ⇒ **exit 0**（`Compiled successfully in ~11.8s`）；`npx eslint src --ext .vue,.js` ⇒ **exit 0**（零输出）。
  - **后端全套**：`./mvnw -B test` ⇒ **`209 / 0 / 0 / 19`**（**与翻前逐条同集合**）。⚠️ 目录先 `rm -rf target/surefire-reports` 再跑（防 §5-V 记过的 surefire 残留累加）⇒ 30 个报告文件、**0** 个含 `Failures/Errors`。⚠️ `MallManagementApplicationTests` 那 **6 条仍 `skipped`** —— 原因不是本笔，是它的 `@EnabledIfEnvironmentVariable(named = "A3_ENABLED", matches = "true")`（见下条）。
  - **「主配置现值 = `true`」的显式探针**（用户验收第 4 条）：`A3_ENABLED=true ./mvnw -B test -Dtest=MallManagementApplicationTests` ⇒ **6 / 0 / 0 / 0 全绿**，且启动日志打出 `WebAuthConfig : …（AUTH）… app.auth.enabled=true …`。**为何这条能当探针**：该类是 `@SpringBootTest` 且**无 `@ActiveProfiles`、无 `@TestPropertySource`** ⇒ 生效值只可能来自**主 `application.properties:163`**。⚠️ 这是本笔**唯一**一次真启动主 profile 上下文（需本地 MySQL 可达）。
  - **启动日志打印「生效值」而非常量**：全量跑里 `grep AUTH` **3 命中** —— `AuthGateSmokeTest` / `GoodsAddUploadFailSmokeTest` 打 `false`（smoke profile 的显式 pin），`AuthGateSwitchOnTest` 打 `true`（其 `@TestPropertySource` 覆盖）⇒ **同一行格式打出两个不同的值**。⚠️ 日志正文在 GBK 控制台是乱码，**ASCII 片段 `AUTH` 是唯一可 grep 的锚**（这正是选它的理由，同 `app.lock.*` 惯例）。
  - **三档值 grep（可复核）**：`application.properties:163` = **`true`** ／ `application-a3.properties:98` = **`false`** ／ `src/test/resources/application-smoke.properties:31` = **`false`**。
  - **净零**：`WebAuthConfig.java` **135**、`UserController.java` **230**、`api-contract.md` **220** —— 三处均与翻前一致。
  - **`git diff --stat`**：**15 文件，175 insertions(+), 85 deletions(-)**。
  - **新发现的漂移**：**无**。⚠️ 唯一一处连锁：`api-contract.md:161` 的「现状 43」同日被本笔推成 **44** ⇒ 已在 `progress.md:1188` 就地加按语（属同笔连锁，非新发现）。

**（9）手测与回滚点（用户裁决）**
  - **本笔不手测**（攒 T 批，同 R1–R6 / `P2.6`）。⚠️ **手测批在本笔 merge 之后启动** —— 范围 = **T1–T4 + T-new-1~11**（把 R1–R6 路由化、`P2.6` 会话驱动、以及本笔翻开关**一起**测）。理由：这三拨改动叠在同一条链路上（路由 → 会话 → 闸），分开测反而要重搭环境、且翻开关的真正效果（未登录直开 `#/customer` 该被拦）必须和前端一起看。
  - **回滚点（一行）**：`application.properties:163` 的 `app.auth.enabled` 改回 **`false`** 即回退本笔对现网的**全部**影响；`application-a3.properties` / `application-smoke.properties` 两处 pin **可留**（回退后它们是冗余，不是有害）。⚠️ 这是本笔把「开关现值」与「两处 pin」**分开**放的理由 —— 回滚只动一行，护栏不动。

---

### 5-AE. 手测批（R1–R6 + `P2.6` + 翻开关**合并一次跑**，2026-10-05）：**14 / 14 通过** —— ⑫ 的「通过」是**预期内的缺口**（前端路由守卫尚未做）

**（1）范围**：T1–T4（R1 起攒：客户/商家分流、深链接刷新）+ T-new-1·2（R3 `/` 落点）+ T-new-3·4（R4 裸页收编）+ T-new-9·10·11（`P2.6` 会话驱动）+ 翻开关笔新增 2 条（匿名逛首页不被踢 / 退出真的登出）—— 重叠项合并后 **14 条**。四条线**在同一次手测里一起验**（理由见 §5-AD (9)）。

**（2）结果**：**14 / 14 通过**。
  - ⚠️ **档位 = 叙述记录（用户口述）**：本仓**无**逐条截图 / 响应体 / 日志落盘 ⇒ 本记录**不可本地复核**，只承载「用户报 14 条全绿」这一事实，不承载逐条证据。
  - ⚠️ **产物条件（前置按语，必须与结果同读）**：本批的效力**以「dev server 已重启重编」为前提** —— 开跑前 8080 上那份 `/js/app.js` 里 `GoodsUp.vue` / `UserInfoPage.vue` 两个模块**仍是 `P2.6` 之前**的版本（调已删除的 `getPassword()` ⇒ `TypeError` ⇒ 页面对应字段全落兜底文案，如「未知用户」），而同包 `auth.js` 已是 `P2.6` 之后 ⇒ **混合陈旧产物**。
    - **档位 = 实测**：① 拉 `http://localhost:8080/js/app.js` 后逐字核对（旧码为 `const password = getPassword()` + `endpoints.user.getUserInfo`）；② 同包 `auth.js` 模块的导出清单里**无** `getPassword`（只剩注释墓碑）；③ 后端 `POST /user/loginSelect` → `POST /user/me` 实登实测，返回**顶层平铺 11 键**，键名与模板要求逐字一致。
    - 修法 = 重启 dev server + 硬刷新，**未改任何源码**（源码本就是对的）。
    - ⇒ **⑧（新标签页进 `#/customer/user/info` 显示完整资料）通过，反证产物已刷新**（陈旧产物下该条必红）。**档位 = 推断**。

**（3）⑫ 的「通过」怎么算**：⑫ = **未登录 / 无会话时直开受保护页**。现象 = 页面能打开但**空白**；Network 里 `POST /user/me` 返回 **401**。
  - **判据不是「行为正常」而是「现象与已知缺口逐条吻合」**：前端**至今没有路由守卫** —— `router/index.js` 唯一的 `beforeEach` 是 `LEGACY_TAB` 归一器，**无鉴权分支** ⇒ 未登录照样能被路由送进受保护页；页内请求被闸 401、又无人拦截 ⇒ 空白页。与缺口描述一致 ⇒ 判**通过**（**不是回归**）。
  - ⚠️ **该缺口有具名笔主 = 「前端路由守卫笔」**（§5-AC 的 D5 已把该笔登记为接管方，见 `progress.md:1189`）。本批**不修**，只登记「现象已在真环境验过」。
  - ⚠️ 附带确认：`/user/me` 的 401 **没有**触发跳登录 —— 因为 `P2.6` 三个读点都带 `allowUnauthorized: true`（`UserInfoPage.vue:99` / `GoodsUp.vue:153` / `LoginPage.vue:144`），401 只落各页 `catch`。**这是设计如此**，不是 401 闩锁失效。
  - ⚠️ **按语（§5-AG，前端路由守卫笔，2026-10-06）**：本条第 1 点那句「**前端至今没有路由守卫** —— `router/index.js` 唯一的 `beforeEach` 是 `LEGACY_TAB` 归一器，**无鉴权分支**」**已终结** —— 该文件现有**两条** `beforeEach`（`:161` 旧归一器 + `:294` 新守卫：默认拒绝 + 6 条 `meta.public`）。⚠️ 本条**作为 2026-10-05 那次手测的如实记录保留原样**（当时确实如此），但它的**判据从此不可复现**：未登录手敲受保护页现在**直接跳 `/login?redirect=…`**，空白页现象消失（T-guard-1）。本条第 2 点（现象与已知缺口逐条吻合 ⇒ 判通过）不受影响。

**（4）本批不覆盖的两块**：
  - **管理员（`role=3`）全链路** —— **C3 未落地**，`login` 表种子只有 role `1`/`2`（`mall_management_sql.sql:99`）⇒ **无对象可测**。
  - **多账户串号**（同浏览器两标签页：标签 1 登客户、标签 2 登商家 ⇒ 回标签 1 的 `#/customer/user` 显示**商家**数据）—— 本批 14 条的判据都建立在**单账户**路径上，不含此项。成因 = **cookie 浏览器级共享**（HTTP 协议层约束：标签 2 的登录覆盖了 cookie）⇒ 前端身份不能再由 cookie 唯一决定。归属 = **「多账户隔离」笔**（`feat/auth-token-isolation`，2026-10-05 开；落码后按序分配小节号）。

---

### 5-AF. 多账户隔离笔（`feat/auth-token-isolation`，2026-10-06）：不透明 token 机制 + **13 端点入参身份校验** —— ⚠️ **落码完成、验证全绿**（验证见 (7)；commit 拆分见 (11)）

**（1）治的病与两次改判**
- **病**（§5-AE (4) 已登记本笔为归属）：同一浏览器两标签页**共用一份 cookie** ⇒ 标签 2 的登录把身份「抢」过去，回标签 1 刷页看到的是**商家**数据。根因 = 身份**只能**由 cookie（浏览器级共享）决定。
- **改判 ① —— 入参身份校验（「填别人的 ID 改别人的数据」）并入本笔**。理由：与 token 同属「身份从哪来」这一个主题；且它是 **C3 的硬前置**（不先钉死「谁只能改自己的」，细粒度权限模型没有落点）；答辩必问「能改别人的商品吗」。
- **改判 ② —— 管理员豁免本笔就做**。上一轮曾判「管理员也走自己那条校验」——**错**：管理员改他人数据**是职责**（驳回商家异常更新、处理异常订单、处理不合规描述）。本笔把门**开到「放行」为止**；管理员能改**哪些字段**、要不要审计日志 / 二次确认、前端入口 —— 全归 **C3**（见 (5)）。

**（2）机制：不透明 token（`shared/web/AuthTokenStore.java`，新增）**
- **形态**：32 字节 `SecureRandom` → Base64URL 无填充 = **43 字符**不透明串；表在**内存**（`ConcurrentHashMap`）。头名与方案前缀的**唯一出处**是 `AuthTokenStore.HEADER` / `BEARER`（前端 `http.js` 按它拼头）。
- **不落 Redis**：理由与 `AuthSession` 同源（Redis 在本仓被定义为**可容忍的降级态** —— `application.properties` 明写、`management.health.redis.enabled=false`）；token 表若在 Redis，Redis 一挂就**从「不确定」变成「全部已登出」**。⚠️ 代价同 `AuthSession`、已同处登记：**多实例不共享、重启即丢**。
- **绑 `userId`、不绑会话** —— 这是隔离的**唯一**诀窍：两标签页各登各的 ⇒ 表里**两条互不相干的记录**；标签 2 覆盖 cookie，覆盖不了标签 1 手里那张票。⚠️ 故 `mint` **每次调用都新铸**、**永不回发**既有 token（`AuthTokenStoreTest.mintTwice_producesTwoDistinctLiveTokens` 专门钉这一条：改成「同一 `userId` 复用一张票」= 隔离当场失效，而**别的用例照样全绿**）。
- **有效期**：**30 分钟滑动**（每次命中把 `lastAccessAt` 推到当下）；过期项**没有后台清理线程**，只在 `mint` 时顺带扫一遍（登录频率低，一次 O(n)）。`≥ TTL` 即过期 —— 这个判据在 `resolveAt` 与 `purgeExpiredAt` 两处**逐字取齐**。
- **不回落 cookie（裁决 D4）**：带了 `Authorization` 但票不认识 ⇒ **401**，**即便此时 cookie 会话是好的**。回落等于把隔离**静默降级**回「谁有 cookie 谁是身份」——正是本笔要治的病。
- **两条口径（`AuthSession`）**：`authorize`（token → cookie 且 **role≠3** → `null`；**闸与全部业务端点**用）／`identify`（token → cookie **任意角色** → `null`；**只**给 `/user/me` 与 `IdentityGuard` 用）。可观测后果：**`role=3` 的 cookie 在业务端点是 401、在 `/user/me` 是 200 降级形状** `{success:true, role:3}`（认出「你是管理员」，但**不查资料**）。这条分叉是最容易被「顺手统一掉」的地方，端到端各钉一遍。
- **闸的 4 档（`AuthInterceptor`）**：开关 → 白名单（12 条）→ `authorize` → **身份识别例外**（`WebAuthConfig.AUTH_IDENTITY_PATHS = ["/user/me"]`）。
- **端点 +1**：新增 `POST /user/token`（凭**会话**铸票）—— ⚠️ **不在白名单**（无凭据 ⇒ 401），且**管理员 cookie 换不到票**（管理员身份只能由 token 承载）。
- **localStorage 指针（裁决 D6）**：键名 `authToken` **同用在两个 store** —— `sessionStorage` = **本页的票**、`localStorage` = 「**最近登录**」指针（`role ≠ 3` 才发布，管理员从不发布）。清除规则 = **仅当指针 == 本页的票**才清（否则标签 1 退出会把标签 2 刚发布的指针一起抹掉）。
- **⚠️ 已知边界（登记不修）**：浏览器「复制标签页」/ `window.open` **会复制 `sessionStorage`** ⇒ 复制出来的页**继承原页身份**，而不是「最近登录」。浏览器行为，前端管不了。

**（3）入参身份校验：一处判据、13 个端点共用**
`IdentityGuard.requireSelfOrAdmin(request, paramId)` → `boolean`（**不通过**时端点返 `IdentityGuard.forbidden()` = **403** + `Result.fail("无权操作该账户")`）：

| 档 | 情形 | 结果 |
|---|---|---|
| 1 | 入参填**自己** | 放行（**所有**角色） |
| 2 | 入参填**别人** + `role == 3` | 放行（**管理员豁免**） |
| 3 | 入参填**别人** + 非管理员 | **403**（不是 401 —— **有身份、无权限**） |

- **API 契约不变**：前端照旧传 `MerchantId` / `CustomerId`。
- **13 个端点**：`GoodsController` 的 `goodsAdd` / `goodsSelect` / `goodsDe` / `goodsUp`；`OrderController` 的 `orderAdd` / `orderSelect` / `orderDeliveryIfSelect` / `orderDeliverySelect` / `historicalOrdersSelect`；`MessageController` 的 `friendAdd` / `friendUp` / `friendDe` / `friendSelect`。
- **刻意排除的 2 个**：`productCodePrice` / `GoodsProductCodeSelect` —— 它们的 `MerchantId` 是**被浏览的商家**（对端），**不是调用者**；判它 = 把「逛店」判成越权。
- **`identify() == null ⇒ 放行`**：闸关（`app.auth.enabled=false`，含 smoke / A3 两个 profile）时没有身份可言 ⇒ 10 个既有 smoke 类与 A3 **全绿不受影响**。

**（4）测试面**：新增 **28** 条，总数 **237**
- `shared/web/AuthTokenStoreTest`（**9**，纯单测、不起 Spring）：43 字符与 URL 安全字符集、**永不回发**、不认识 ⇒ `null`（含 `null` 入参）、过期边界（`TTL-1` 有效 / `≥TTL` 失效）、**滑动续期**、**懒清除**（判 `liveCount()`）、`mint` 顺带清扫、`revoke` 幂等、**只销指定那张**。
- `smoke/AuthTokenSmokeTest`（**11**，闸开 + MockMvc）：无凭据铸票 ⇒ 401（**与闸同字节**）、**管理员 cookie 换不到票**、票可**替代** cookie、**票优先于 cookie**、不认识的票在 `/user/me` **与业务端点各钉一遍**（走的是闸的两条不同判定分支）⇒ 401、管理员 cookie 在业务端点 401 / 在 `/user/me` **降级形状**、管理员**票**拿得到完整资料、登出销**本请求那张**票、**不误伤别的标签页**。
- `smoke/IdentityGuardSmokeTest`（**8**）：13 端点 × **三档**（档 1 / 档 3 / 档 2 **各扫一遍全部 13 个端点**）、**对端方参数不判**、**浏览侧 2 端点刻意不设防**（反向断言）、403 体形状、`null` 身份放行、`null` 入参放行。⚠️ 该文件带 `app.upload-dir=pom.xml/nope-img/` pin —— 走上传路径的用例**不得**写进 `src/main/resources/static/img/`（那是**真仓库目录**）。
- **新增测试钩子**：`AuthTokenStore.mintAt(userId, role, nowMillis)`（**包级可见、仅供测试**，与既有 `resolveAt` 同源理由：30 分钟的过期路径**不能**靠 `Thread.sleep` 测）。它是「`mint` 顺带清扫过期项」那条用例存在的**唯一**理由。

**（5）细粒度权限归 C3（登记，本笔不做）**：管理员能改**哪些字段**？审计日志？二次确认？管理员前端入口？—— 四问全归 **C3**。本笔只把「管理员**可以**改别人的数据」这扇门打开（(3) 档 2）。⚠️ **C3 的口子已因本笔变形**：`/user/me` 现**答 `role`** ⇒ C3 不必再「另开一笔补 `role`」（`progress.md:1170` 的 P2.6-D5 口径已就地订正）。

**（6）前端影响（本笔）**：**无功能改动**（管理员暂无前端入口，前端照旧传自己的 `MerchantId`/`CustomerId`）。唯一落码 = `UserCenter.vue:64` 追加 `.finally(() => clearToken())`（退出时**销本页的票**；票必须在 `POST /user/logout` **之前**送出去，销的动作**之后**做）。⚠️ §5-AD (5) 引的该行是**行首前缀**，不再是整行原文。

**（7）验证**（落码后、commit 前，全部**实测**）
- `npm run build` → **`DONE` / exit 0**；`npx eslint src --ext .vue,.js` → **exit 0**。
- `rm -rf target/surefire-reports && ./mvnw -B test` → **`Tests run: 237, Failures: 0, Errors: 0, Skipped: 19` — BUILD SUCCESS**（其中本笔新增 **28** 条 = 9 + 11 + 8；19 skipped 里 `MallManagementApplicationTests` 占 6 条 —— 该 6 条的 `@Disabled` 是本仓既有状态）。
- **净零核查**（`git diff --numstat`）：`router/index.js` **5/5**、`UserCenter.vue` **10/10** —— 这两处**行数中性**，指向它们的既有行号**不漂**。其余文件是**功能新增**（不是注释改写），行数本就会涨。
- ⚠️ **上述三条跑在代码冻结点之后**：其后只再动过**文档**（`api-contract.md` 端点表同步 ③ + 本文档 §5-AF 的裁定登记与状态措辞），**未再动任何 `.java` / `.js` / `.vue`** ⇒ build / eslint / 测试结论**继续有效**，不重跑。
- **行号回放 —— 本笔改动的 7 个生产文件使引用它们的坐标位移。已就地修 6 处**（全在本文档，均为**一行换一行、净零**）：

| 处 | 原引 | 现引 | 成因 |
|---|---|---|---|
| `progress.md:534` | `GoodsController.java:61` | **`:74`** | `GoodsController` 头部 javadoc 与入参注释增行 |
| `progress.md:559` | `UserController.java:228` | **`:300`** | `UserController` 增 `/token`、重写两支 javadoc |
| `progress.md:573` | `UserController.java:142` | **`:149`** | 同上 |
| `progress.md:1170` | `auth.js:99-106` | **`:98-103`** | 见下「位移第二类」 |
| `progress.md:1217` | `WebAuthConfig:118` | **`:137`** | 构造函数前新增两段 javadoc |
| `progress.md:1228` | `UserController:222` | **`:294`** | 同 `:559` |

- ⚠️ **位移第二类：`auth.js` 内部 −1/−2 —— 本笔新发现、本笔不改**。`auth.js` 相对 `HEAD` 的位移**不是** 0：`:64` 与 `:74` 各 −1、`:78` +1（头部三处净 −1），`:99` 那一块再 −1 ⇒ 各锚点：**`:99-106`→`:98-103`、`:108`→`:106`、`:118`→`:116`、`:146`→`:144`**（`:44` 未动）。⚠️ 这与 `auth.js:21` 注释里「合并两键以保住净零行数」的**意图相冲** —— 合并在 `:21` 处确实是 0 增减，但**文件别处的改写**把锚点带走了。本文档 §5-W / §5-X / §5-Z / §5-AC 里引 `auth.js:108`/`:118`/`:146` 的 **11 处**属「悬空/审计台账」（台账的**主语**就是这些地址），**本笔不改**（改了就篡改台账原话）。✅ **用户裁定（2026-10-06）：不改** —— 口径同上（唯一例外是 `auth.js:21` 那句**代码注释**，已改）。
- ⚠️ **位移第三类：快照 / 史料文档 —— 不在本 PR 的改动面内、本笔不改**。筛子 = 「该行内容相对 `HEAD` 有变」，扫 `docs/` 命中 **12 份、79 处**：`defect-survey.md` 44、`context-packs/*` 13（`three-layers.md` 10 + 另 3 份各 1）、`tech-debt.md` 6、`cache-map.md` 6、`api-contract.md` 4、`concurrency-map.md` 3、`db-schema.md` / `ddd-package-plan.md` / `selection-status.md` 各 1。
  - ⚠️ **79 是「候选」不是「已判位移」**：筛子只比**该行内容**，不含「行号是否真的动了」。反例：`api-contract.md` 那 4 处引 `UserCenter.vue:64` —— 行号**没动**，只是该行内追加了 `.finally(...)` ⇒ **引用仍有效**。79 处里另有**大半**是「原 → 现」对照表的**原址**列（`defect-survey.md` / `tech-debt.md` / `three-layers.md`，多带「原记 / 三层前 / ~~」标记）⇒ 本身即**史料地址**，不算漂。
  - **逐条判过的那一小撮，全部在 `HEAD` 时已与所述不符**（⇒ 属**既有陈旧**，本笔只是让它们继续偏移）：`cache-map.md:66`/`:94` 引 `GoodsController:60` 说「只剩一行调用」，而 `HEAD` 该行是**注释**；`:67`/`:95` 引 `:103` 同理；`concurrency-map.md:41` 引 `OrderController:100`，`HEAD` 该行是 `historicalOrdersSelect` 的**签名**，而该表说的是 `orderPayIfUp`；`db-schema.md:96` 引 `OrderController:88` = `orderDeliverySelect` 的路由，而该处说的是 `historicalOrdersSelect`。✅ **用户裁定（2026-10-06）：只登记不修，派笔** —— 见下条。
- 📌 **挂账（不留孤儿）｜「文档漂移清理笔」**（用户 2026-10-06 派定）：
  - **范围**：上条那 12 份快照 / 史料文档、**79 处**候选（`defect-survey.md` 44 / `context-packs/*` 13 / `tech-debt.md` 6 / `cache-map.md` 6 / **`api-contract.md` 4** / `concurrency-map.md` 3 / `db-schema.md`、`ddd-package-plan.md`、`selection-status.md` 各 1）。⚠️ `api-contract.md` 那 4 处**已在本笔补做 ③ 时顺手处理**（见下条）⇒ 剩余 **11 份、75 处**候选。
  - **处理原则**：只改「**现在时引用**」；「原 → 现」对照表的**原址列**（带「原记 / 三层前 / ~~」标记者）一律**保留**（本身即史料地址）。
  - **判据**：逐条先判「在 `HEAD` 时是否已经不符」—— 已不符者记**既有陈旧**；在 `HEAD` 时准、被某笔带偏者，回指**造成位移的那一笔**。
  - **不手测**；产出 = diff + 一份「判过 / 未判」账。
- ✅ **另一条已在本笔补做（2026-10-06，用户裁定 ③）**：`api-contract.md` 端点表已同步本笔新增的 `POST /user/token` —— 计数 **35 → 36**、`### /user` **7 → 8 行**、新增 `token` 行；`/user/me` 契约行补上 **`role` 键**与**管理员降级作答 `{success:true,role:3}`**（并订正「与 `/user/getUserInfo` 同一个 Map」那句 —— 本笔起 `/user/me` 先复制再加 `role`）。⚠️ **顺手发现并修复**：该表 `### /user` 的 7 个行号**在 `HEAD` 时是准的、被本笔带偏**（`92 / 114 / 136 / 145 / 154 / 191 / 222` → **`96 / 121 / 143 / 152 / 161 / 213 / 294`**），已按实测重填。
**（8）相邻缺口** —— 本笔刻意不覆盖，**归属笔名待用户裁定**
- `orderPayIfUp` / `orderReceiveIfUp` / `orderDeliveryIfUp`：入参只有 `orderId` ⇒ 要判的是**资源归属**（「这单是不是我的」），形状与「入参填自己」**不同**，套不上 `requireSelfOrAdmin`。
- `/message/send`：带 `fromId` ⇒ 可**冒名**发消息（与 13 端点同族，但判据是「`fromId` 必须是自己」）—— 本笔**未**纳入。

**（9）手测项** —— T-iso 系列，**本笔未跑**，随下一批

| 项 | 判据 |
|---|---|
| T-iso-1 | 标签 1 登客户 `123`、标签 2 登商家 `2222` ⇒ 回标签 1 刷 `#/customer/user` = **客户**资料、标签 2 刷 `#/merchant/user/info` = **商家**资料；Network 里两页各带**各自的** `Authorization` |
| T-iso-2 | 再新开标签 3 ⇒ 落点 = **商家**（「最近登录」指针指向它） |
| T-iso-3 | 标签 1 点退出 ⇒ 标签 2 **仍可用**；`localStorage` 指针**未被误清**（它指向标签 2） |
| T-iso-4 | 存储面：`sessionStorage` 有 `authToken`（本页的票）；`localStorage` 有 `authToken`（指针；`role ≠ 3` 时） |
| T-iso-5 | `#/customer/user/info` 直开 + 无会话 ⇒ 401（**既有缺口**，归「前端路由守卫笔」，不变） ⚠️ **已结清（§5-AG，前端路由守卫笔，2026-10-06）**：该页在受保护面内 ⇒ 直开**直接跳 `/login?redirect=/customer/user/info`**，那发 `/user/me` 的 401 **不再发生**（用户看不到空白页）。本条**并入 T-guard-1** 验收 |
| T-iso-6 | curl 带 `Authorization: Bearer …` 拿身份；**无效 token ⇒ 401**。⚠️ **管理员那半条挂 C3 前**（`login` 表无 `role=3` 种子 ⇒ 无账号可造） |
| T-iso-7 | **本笔新增**：登录客户 → curl 发 `goodsUp`，`MerchantId` 填**别人** ⇒ **403** |

**（10）本笔不做（不变）**：不顺手带别的主题；不动 `ProductPage.vue` / `ci.yml` / A3 脚本；不做「前端路由守卫笔」的活（⑫ 缺口仍归它）；**不修 CORS 通配**（归 CORS 收窄笔）；不做细粒度管理员权限模型（C3）；不做管理员前端入口（C3）。

**（11）commit 拆分** —— **一个 PR、两个 commit**（用户 2026-10-06 批准）：① **token 机制**（`AuthTokenStore` + `AuthSession` 两条口径 + `AuthInterceptor` 4 档 + `/user/token` + 前端 token 存取与 D6 规则 + `AuthTokenStoreTest` 9 + `AuthTokenSmokeTest` 11）；② **13 端点入参校验**（`IdentityGuard` + 13 端点 + `IdentityGuardSmokeTest` 8 + 两条相邻边界用例）。两笔**一起 review、一起 merge**（拆分只为可读，不为分别上线）。**执行形态**：顺序提交 ① ② → push `feat/auth-token-isolation` → 开 PR（base `main`）。⚠️ **merge 由用户本人执行**；手测 T-iso-1~5 / T-iso-7 由用户跑完再 merge（T-iso-6 的管理员那半条挂 C3 前）。
- ⚠️ **归属划界（免得两个 commit 互相依赖）**：`docs/agent/progress.md` 与 `docs/agent/api-contract.md` 两份文档**全进 ①**；`IdentityGuard.java` 与 `IdentityGuardSmokeTest.java`、三个 Controller **全进 ②**。① 单独 checkout 可编译可测（② 之前的 13 端点是**旧行为**：任何角色只要填对端方业务号就能改）。**已实测**：临时 worktree 装入 ① 的 18 个文件（不含 `IdentityGuard`）⇒ `./mvnw -B test` = **229 / 0 / 0 / 19** BUILD SUCCESS —— 229 = 237 − 8，差额正是 `IdentityGuardSmokeTest` 的 8 条；⚠️ ① 里 `AuthSession.java` / `UserController.java` / `AuthTokenStoreTest.java` 对 `IdentityGuard` 的 4 处提及**全是 javadoc `{@link}`**（无代码依赖，故 `javac` 过得去）。

---

### 5-AG. 前端路由守卫笔（`feat/router-guard`，2026-10-06）：**未登录不得进「私人面」+ 登录后回原目标** —— ⚠️ ~~落码完成、验证全绿；未 commit~~ ⇒ **已 commit `e891e0d`（8 文件，400+/123−）+ push + PR #72；用户已 merge，`origin/main` 合并点 = `c70ab85`**（CORS 收窄笔即从该点切出）

**（1）治的病与归属**
- **病**（= §5-AE (3) 登记的现象、T-iso-5 同一条）：未登录 / 无会话时**手敲受保护页**（`#/customer/user/info` 等）照样被路由送进去 ⇒ 页内请求被闸 **401**、又**无人接管** ⇒ **空白页**。成因 = `router/index.js` **全文只有一条 `beforeEach`**（`LEGACY_TAB` 归一器，`:161`）、**无鉴权分支**；且全仓**没有 catch-all** ⇒ 也没有兜底面。
- **归属**：§5-AC 的裁决 **D5**（`progress.md:1189`）已把该缺口判给本笔；§5-AD **D3** 同。⚠️ `WebAuthConfig.java:90-95` 那 6 条 `REACHABLE_BUT_NOT_PUBLIC` 的归属也是本笔 —— 处置见 (9)。

**（2）边界怎么划的**（⚠️ 本笔的核心；用户明确指示「边界由 agent 自己分析，我的想法并非一定」）
三档候选与采纳：
| 档 | 保护集 | 判 |
|---|---|---|
| 保守 | 除 `/login` / `/product` 外**一律**要登录（连两壳首页与搜索页一起锁） | **否** —— 撞 R3 的 `landingFor` 裁定（「未登录也落 `/customer`」，`router/index.js:198`）、撞 T-new-1/2/4、撞翻开关笔那条验收项「匿名逛首页**不被踢**」 |
| **平衡（采纳）** | **「该页全部端点都在 `AUTH_WHITELIST` 外」**的记录 —— 即匿名进去**注定拿不到数据**的那些 | ✅ 见下 |
| 宽松 | 只保护 `/…/user*`（用户中心） | **否** —— 消息页 / 购物车在匿名下同样是空列表，且 `navFor` 的匿名形态**已经把这三项一起换掉** ⇒ 三档不一致 |

**判据不是感觉，是三个独立来源**：
1. **后端白名单**（亲读，`WebAuthConfig.java:97-113`）—— 白名单 = **公开面**（12 条）。6 条 `meta.public` 记录所调端点**全在白名单内** ⇒ 匿名**拿得到**数据。
2. **既有前端行为**（亲读，`src/config/nav.js`）—— `navFor(table, false)` 把右三项**整体**换成「登录」单项（`ANON_RIGHT`，`:53`），注释原文：「未登录访客**可以进壳** —— 但顶栏右三项（消息 / 购物车\|订单中心 / 用户中心）**每一项都以登录态为前提**」（`:41-43`）⇒ **产品决定早就存在**，本笔只是让路由与它一致（不是本笔新立的口径）。
3. **页内行为**（文档档，§5-AE (3) + `ShoppingCart.vue` 亲读）—— 这些页的 `onMounted` **没有**登录判据（`loadOrders()` 直接发 `POST /order/orderSelect`）⇒ 匿名进入 = 空壳 + 401。

⇒ **公开面 6 条**：`/login`、两壳 `''`（首页）、两壳 `search`、`/product`。
⇒ **受保护**：27 条挂 `component` 的记录里 **21 条**受保护（客户壳 8：`msg` / `cart` / `user` + `user` 的 5 个子页；商家壳 11：`msg` / `order` / `order/cart` / `user` + `user` 的 7 个子页；另 2 条是**两壳父记录**，它们**永远与公开的 `''` 子记录同时匹配** ⇒ **可独立到达的受保护目的地 = 19 条**）。另有 **3 条纯 redirect 记录**（`/` / `/shoppingCart` / `/goodsSelect`）—— 它们在**任何守卫之前**就被 `handleRedirectRecord` 解析掉（`vue-router.mjs:1264-1287` 亲读 + 运行时探针实测）⇒ 守卫看到的一律是**解析后的终态**。
⚠️ **原「不确定项」（本笔唯一边界争议点）—— ⚠️ 已结清（改判 2，2026-10-06，见 (13)）**：`/…/search` 判**公开**，而首页点搜索**一度仍弹「请先登录」**（R3 用户裁决 1，三条守卫在 `HomePage.vue`）—— 那条不一致在本笔**保持不动**、并记给了「匿名搜索 / 购买放开」笔；**用户 2026-10-06 改判 2 已放开三条守卫** ⇒ 两处判据现在**一致**。其余 18 条无歧义。

**（3）守卫形态：默认拒绝 + 显式 `meta.public` 白名单**（`router/index.js:241-299`：一段注释 + 5 行守卫）
- **为什么是「默认拒绝」而不是逐条标 `requiresAuth`**：漏标一条在默认拒绝下 = **静默受保护**（错得更安全），在 `requiresAuth` 下 = **静默公开**。**红利**：C3 的 `#/admin` 将来**自动**落在受保护一侧，不必回来改。**风险（本笔唯一「静默分歧」型）**：公开面与 `navFor` 的匿名隐藏集是**两处各自维护、无代码引用关系** ⇒ 改一处要回头核另一处，不会报错。
- **与后端同构**：白名单 = 公开面（照抄 `WebAuthConfig.java:90-95` 的那句「白名单是**公开面**，不是『未登录可达面』」）。
- **与既有 `LEGACY_TAB` 守卫共存**：**两条** `beforeEach` 并存（`:161` 旧、`:294` 新）。注册序**不影响判定** —— 归一器返回的是**新导航**（`pushWithRedirect` 递归）⇒ 全部守卫在新目标上**重跑**（`:285-287` 的注释，本笔按此设计、运行时探针实测过）。
- **#1 不变量：`/login` 必须公开**（`:53` 的 `meta.public` 别删）—— 它同时是**本守卫的落点**与 **401 出口的落点**（`main.js:49`）⇒ 标成「要登录」= **无限重定向**。全站只有这一条会成环。
- **元数据继承**：`meta` 沿 `matched` 链合并、子覆盖父 —— 这既是「只标 6 条就够」的原因，也是 `LoginPage.landingAfterLogin` 能**复用同一条规则**的原因（见 (4)）。
- **未匹配路径放行**（`to.matched.length === 0`）：无组件可挂 ⇒ **零请求**，不在「受保护页」语义内；改它是 scope creep（保持现状的「白屏 + 控制台 warning」）。
- ⚠️ **前端守卫 = UX，不是安全边界**：真边界是后端**两道**（`AuthInterceptor` 四档 + `AUTH_WHITELIST`；`IdentityGuard` 13 端点）。改 JS / 直连 API 都能绕过本守卫。本笔**不动**任何后端判据与白名单。

**（4）回原目标（用户明确要求「本笔一起做」的扩展）**
- **契约**：`redirect` query 的值 = `to.fullPath`（**带 query** —— `/product` 的 `merchantId` / `goodName` 是**必填输入**，丢了就回不到原状态）。
- **三处同一条契约**（`grep redirect` 一次全命中）：① 守卫 `router/index.js:298`；② `main.js:49` 的 **401 出口**（带**出事那一页**的 `fullPath`）；③ 消费方 `LoginPage.vue:271-280` 的 `landingAfterLogin(role)`。
- **401 出口为什么也要带**（用户 2026-10-06 确认）：不带就会出现「**守卫进的回原目标、401 弹的落角色首页**」两条体验 ⇒ 不一致；带它是「闭环」的一部分。
- **角色校验不另抄一张表**：`this.$router.resolve(target).meta` 返回的是**沿 `matched` 合并后**的 meta ⇒ 「这页这位用户能不能去」= `meta.public === true || 属于本角色壳`，与守卫**同一条规则**。（**不能** `import router/index.js`：它 `:3` 已 import `LoginPage` ⇒ 反向加边成环。）⚠️ 代价：`landingAfterLogin` 里的 `/customer` / `/merchant` 字面量与 `ROLE_HOME`（`:192`）是**同构的第二份**。
- **四条校验**：① 必须是**字符串**（同名 query 出现两次时 vue-router 给的是数组）；② **单斜杠**开头且不是 `//`（防开放重定向）；③ 不是 `/login`（防自环）；④ `resolve()` 必须**匹配得到**记录（认不出的不猜，同 `LEGACY_TAB` 口径）。**全不满足 ⇒ 返回值 = 本笔之前的字面量** ⇒ 「回原目标」是**纯增量**，既有登录路径的结果**逐字不变**。
- **公开面可作 redirect 目标**（`r.meta.public === true` 那一行，用户 2026-10-06 确认保留）：匿名开 `/product?…` 点购买 → 登录 → **回该商品页、参数还在**。

**（5）落码清单**（守卫本体 **4 个代码文件 + 1 文档**；⚠️ **改判 2（见 (13)）并入本笔后再 +2 文件、收口笔（见 (15)）再 +1 ⇒ 合计 8 个**，`git diff --name-only` 实测正是这 8 个）
| 文件 | 变化 |
|---|---|
| `src/router/index.js` | 241 → **301 行**（⚠️ **角色边界 (16) 后 = 336 行**）：6 处**同行内联** `meta.public`（`:53` / `:58` / `:59` / `:89` / `:90` / `:125`）+ 文件末尾**守卫块**（`:241-299`）⚠️ 另 2 处**同行按语**（`:174` / `:268`，改判 2）+ 2 处**同行接** `meta.roles`（`:55` / `:86`）+ **第二条守卫**（角色边界，`:300` 之后） |
| `src/views/LoginPage.vue` | 494 → **522 行**（⚠️ **角色边界 (16) 后 = 529 行**）：`:159` / `:163` 两行**同行替换**（`push("/customer")` / `push("/merchant")` → `push(this.landingAfterLogin(role))`）+ 新增 `landingAfterLogin`（`:253-280`）+ `:278` 同行替换（加 `roleAllowed`）+ `:280` 之后新增 `roleAllowed` |
| `src/main.js` | 99 → **99 行**（净零）：`:49` **同行替换**（401 出口加 `redirect` query）⚠️ 另 `:21` 一处**同行按语**（改判 2） |
| `.../shared/web/WebAuthConfig.java` | 154 → **154 行**（净零）：`:93-95` 三条 javadoc 改写为「**已结清**」（见 (9)） |
| **`src/views/HomePage.vue`** | **443 → 389 行**（改判 2）：删三条守卫的拦截（各 4 行 → **各 4 行同址按语**）、删黄条（模板 `:4` + CSS `:225-233`）、删 `showLoginModal`（模板弹窗 9 行 + ref + 5 个 CSS 块）、删死掉的 `isLogin`/`isLoggedIn`/`computed`，并**补 9 行说明块 + 等量按语** ⇒ 被引行号零漂移（见 (13)） |
| **`src/config/nav.js`** | **69 → 69 行**（净零，改判 2）：`:53` 的 `ANON_RIGHT` 标签 `'登录'` → `'登录 / 注册'`（`to` 仍只有 `/login`）；`:43` / `:45-47` 注释**原地**改写（理由见 (13)） |
| **`src/views/ProductPage.vue`** | **795 → 803 行**（收口笔，见 (15)）：`:15` 顶栏标签改成条件表达式（`goUserCenter` **一字未动**）、`:135` import 追加 `isLoggedIn`、`:312` / `:409` 两处拦截**同行接**跳转、`:496` 的 `.nav-right` 加 `white-space: nowrap`，+ **末尾 8 行**（`isLogin` + 说明）—— 全在**最大被引行 `:462-465` 之后** ⇒ 24 个被引目标**零漂移** |
| `docs/agent/progress.md` | 本节 + **四处按语**（§5-AE (3)、T-new-3、T-new-11、T-iso-5）+ 购物车挂账（见 (8)）+ **改判 2 的 10 处按语 + (13) / (14) 两节** |
- **行号纪律**：全仓对 `router/index.js` 有 **27 个**行号引用、**最大 `:227`** ⇒ 新块插在 `:239` 之后**零漂移**（与 R3 §5-Z (10) 同一手法）。`LoginPage.vue` 被引用**最大行 = `:216`** ⇒ 新方法插在 `:252` 之后零漂移。`main.js` / `WebAuthConfig.java` 均**同行替换**。 ⚠️ **订正（角色边界 (16)，2026-10-06 重核）**：本行两个「最大」是**当时的**值、**已过时** —— `router/index.js` 现被引最大 = **`:241-299`（守卫块自身）+ `:298`**（本笔 (3)/(6) 自己写的）；`LoginPage.vue` 现被引最大 = **`:271-280`，正是 `landingAfterLogin` 本体**（本笔 (4) 写的）。⇒ (16) 的落点纪律因此**更严**：守卫只能加在 `:299` 之后、`landingAfterLogin` 内**一行都不能增减**。
- ⚠️ **零新 import**：`isLoggedIn` 早在 `router/index.js:2` 已引入（本笔只把它**用**起来）。

**（6）验证（落码后、commit 前）** —— 全部**实测**：
- `npm run build` **绿**（exit 0；警告 **2 条**，与 R3/R4 基线**同数**，均为既有项）；
- `npx eslint src --ext .vue,.js` **exit 0**、零输出；
- **grep 账**：`beforeEach` = **2 条调用**（`:161` / `:294`）+ 1 处注释；`isLoggedIn` = 3 处代码（`:2` import、`:198` R3 既有、`:297` 本笔）+ 2 处注释；`public: true` = **6 条记录** + 1 处注释（`:260` 自述）；`pathMatch` = **1 处命中 = `:208` 的注释自身**（⚠️ 出处见 (10) 第 1 条，**非本笔引入**、已同行改写）；
- **dist 结构性取证**（`dist/` 排除 `*.map`）：`path:"/",redirect:` **1**、`orderTab` **11**、`showSwitch` **5**、`商城主页` **2**、`订单中心` **2** —— **与 R4/R6 基线逐项相同 ⇒ 无回归**；新增 `landingAfterLogin` **2**（= 两处 push，符合预期）；`redirect:` 全产物 **7 处**（含三条收编记录 + 守卫 + 401 出口的字符串）；
- **行号回放**（按 §5-AA (12) 同一口径**手工**做 —— 全仓无 `refcheck.sh`）：`router/index.js` 的 27 处引用**逐个回放行内容**（`:30` / `:51` / `:52` / `:53` / `:58` / `:59` / `:61` / `:74-77` / `:81` / `:83-92` / `:94` / `:96` / `:106` / `:124` / `:125` / `:126` / `:128` / `:152` / `:156` / `:157` / `:161` / `:173` / `:174` / `:185` / `:192` / `:197` / `:217-227`）**全部指对**；`LoginPage.vue` 的 11 处（`131` / `141` / `144` / `148-153` / `150` / `153` / `158` / `162` / `216`）**全部指对**；
- **净零核查**：`main.js` 99 → 99、`WebAuthConfig.java` 154 → 154；`src/router/index.js` 的 6 处 `meta.public` 与 `LoginPage.vue` 的两处 push 全部**同行替换**。

**（7）手测项（T-guard 系列）** —— 用户**不手测本笔**；原 15 条按用户要求**精简为 8 条**（用户是新手，条目太多会累）：
| 项 | 判据（括号内 = 合并自） |
|---|---|
| T-guard-1 | 未登录手敲**受保护页三个代表**（`#/customer/user/info`、`#/customer/msg`、`#/merchant/user/list`）⇒ 一律**跳 `/login`**、地址栏带 `?redirect=…`、**不再白屏**（合并原 1/2/3 + **T-iso-5**：那发 `/user/me` 的 401 用户不再看见） |
| T-guard-2 | **反向判据**（该开的还开着）：未登录手敲 `#/customer`、`#/customer/search`、`#/merchant`、`#/product?merchantId=…` ⇒ 照旧能开、**不跳登录**（原 4）。⚠️ **改判 2 扩写（见 (13)）**：未登录在 `#/customer` 首页**用搜索框搜「手机」** ⇒ 落 `#/customer/search?kw=手机` **并出结果**；**点分类** ⇒ 同上；**点商品卡** ⇒ 进 `/product?…` **详情渲染**（三条原先弹「请先登录」，现已放行） |
| T-guard-3 | 旧地址收编页：`#/goodsSelect?kw=手机`、`#/shoppingCart` ⇒ 未登录时**先被收编、再按新边界裁决**（前者公开 ⇒ 落客户搜索页并生效；后者受保护 ⇒ 跳登录），**不报错**（合并原 8/9） |
| T-guard-4 | **回原目标**：未登录手敲 `#/customer/user/history` → 跳登录 → **登录成功后回到 `/customer/user/history`**（不是落 `/customer`）；且**不成环**（不在登录页 ↔ 登录页之间来回跳） |
| T-guard-5 | **角色校验**：上一条改用**商家账号**登录 ⇒ 落 `/merchant`（不把商家送进客户壳）；反向：客户账号 + `redirect=/merchant/user/list` ⇒ 落 `/customer` |
| T-guard-6 | **商品页闭环**（⚠️ 本判据 2026-10-06 由我**自己纠错**，又被收口笔 (15) **再订正一次**）：~~匿名开 `#/product?…` → 点购买 → 登录 → 回商品页~~ —— 在 (15) 之前**匿名点购买只 `alert` + `return`，不跳转**（`ProductPage.vue:311-313`）⇒ 那条路走不通；⚠️ **(15) 起匿名点购买会 `alert` 后跳 `/login?redirect=<本页 fullPath>`** ⇒ 该路**已通**（登录后由 `landingAfterLogin` 送回本页）。**两条路径现在都要跑**：① 匿名开 `#/product?merchantId=…&goodName=…&price=…&…`（**7 个 query 参数都要在**）⇒ **点购买** ⇒ 弹 `请先登录再购买商品` ⇒ **落 `/login?redirect=/product?…`** ⇒ 登录 ⇒ **回本页且 7 个参数都在**；② 另在**登录态**下进同一页、让**会话过期**（或重启服务器 —— token 表在内存）⇒ 再点购买 ⇒ `orderAdd` **401** ⇒ 401 出口带 `redirect=<该商品页 fullPath>` **回原页且 7 个参数都在** |
| T-guard-7 | **401 出口也回原目标**（与 T-guard-4 同一条契约）：登录态下进 `#/customer/user/info`，再让**服务端会话失效**（或服务器重启 —— token 表在内存）⇒ 被弹回 `/login?redirect=/customer/user/info` |
| T-guard-8 | **R3/R4 复验（不是本笔新增行为）**：未登录开 `#/` ⇒ 仍落 `/customer`、顶栏右侧为「**登录 / 注册**」（⚠️ 改判 2 起**不再是**「只有「登录」」，见 (13)）；`#/customer?page=msg&otherId=1&otherType=1` ⇒ **仍被 `LEGACY_TAB` 归一化**（落 `/customer/msg` 且带走会话参数）；已登录手敲 `#/shoppingCart` ⇒ 仍落 `/merchant/order/cart`（商家）/ `/customer/cart`（客户）（原 5 + T-new-3/4 的存活部分） |
| **T-guard-9** | **未登录首页形态（改判 2 新增）**：未登录开 `#/customer` ⇒ **无黄条**、顶栏右侧显示「**登录 / 注册**」、点它落 `#/login`（登录页出来即可，不必进注册态）；⚠️ **同时反向验「没删过头」**：未登录在 **`#/product?…`** 点购买 / 咨询 ⇒ **仍先 `alert`**（两处守卫的判据 `!customerId` 与文案**均未动**）—— ⚠️ 收口笔 (15) 起，`alert` **之后会跳 `/login?redirect=…`**（改判 2 当时是「`alert` 后就停」）⇒ 本条的「反向」半截见 **T-guard-10** |
| **T-guard-10** | **ProductPage 未登录 UX 闭环（收口笔新增，(15)；5 条）**：① 未登录进 `#/product?…` ⇒ 顶栏右侧显示「**登录 / 注册**」（不再是「用户中心」）⇒ 点它落 `#/login`；② 未登录点**购买** ⇒ `alert` ⇒ 落 `/login?redirect=/product?…`（**地址栏能看到 redirect 带 7 个参数**）；③ **登录后自动回到那个商品页、参数还在**（= T-guard-6 ①）；④ **反向（已登录客户）**：进商品页 ⇒ 顶栏**仍「用户中心」**、点购买 ⇒ **正常下单**（不弹「请先登录」）；⑤ **反向（已登录）**：点咨询 ⇒ **正常进消息页**（不弹「请先登录」）。⚠️ **已知的非回归边界**：**已登录商家**进商品页 ⇒ 点购买 ⇒ **仍弹「请先登录再购买商品」且停在原地**（商家没有 `customerId`；(15) 选 (b′) 的刻意结果，**不要当 bug 报**） |
| **T-guard-11** | **角色边界（收口笔新增，(16)；8 条）**：① 已登录**客户**手敲 `#/merchant/user/info` ⇒ **被踢回 `/customer`**（不再显示「商家壳 + 客户数据」）；② 已登录**客户**手敲 `#/merchant`（商家壳**首页**）⇒ 同样踢回 `/customer`（⚠️ 首页虽 `public`，但**不是**他的壳）；③ 反向：已登录**商家**手敲 `#/customer/user/info` / `#/customer` ⇒ 踢回 `/merchant`；④ **本壳内**自由走：客户进 `#/customer/user/info`、商家进 `#/merchant/order/list` ⇒ **不被踢**（判据没写宽）；⑤ **R3 不回归**：**未登录**开 `#/customer` 与 `#/merchant` ⇒ **两个首页都仍能进**（匿名判据在前，`public` 只豁免匿名）；⑥ **公开面不回归**：**已登录**客户开 `#/product?…`（`meta.public`、**无** `roles` 标记）⇒ **照常进**，不踢；`#/login` 仍可达；⑦ **搜索页**：已登录客户开 `#/merchant/search?kw=…` ⇒ 踢回 `/customer`（属别家的壳）；开 `#/customer/search?kw=…` ⇒ 正常；⑧ **踢回不带 `redirect`**：被踢后地址栏是 `/customer`，**不带** `?redirect=/merchant/...`（目标本就不合法，回跳无意义）—— 与 `landingAfterLogin` 对非法目标的口径一致 |
⚠️ **与已登记手测项的接口（三处，已在原处加按语）**：**T-new-3** 的未登录半条判据已变（见 §5-AA (13) 的按语）；**T-new-11** 的前置已不可达（见 §5-AC (7) 的按语）；**T-new-10** 不受影响。

**（8）📌 挂账（不留孤儿）｜完整购物车 —— 归属「C3 后续」笔**（用户 2026-10-06 裁决：**先做 C3，回头再补业务流程**；本笔只**登记**） ⚠️ **按语（2026-10-08 裁决 1）**：本条「归属『C3 后续』笔」的**归属口径已订正** —— 购物车拆分**属批 4 订单块、随该块一起做**（不是「C3 之后另起一笔」）。权威表述见 `c3-modules.md` §八 / §十 与 `c3-blocks-index.md` §四。⚠️ 本行下方的**现状 / 为什么不做 / 将来范围 / 接口四条仍有效** —— 它们是不变的实施细节，变的是「归哪一笔」。
- **现状（本笔核实，全仓只读 grep）**：购物车在**全仓只是「名字」**，语义层**完全缺失** —— 命中的全是顶栏项 / 路由记录 / 页面标题 / 空态文案。具体：**无**「加入购物车」按钮（`ProductPage.vue:311` 的 `confirmPurchase` 走的是**直接下单** `POST /order/orderAdd`，`:352`）；**无**数量 / 增删 / 合并；**无** `localStorage` 购物车；**DB 无 `cart` 表**（`mall_management_sql.sql` 12 张表里没有 —— 只有 `order`，`:141-155`）。
- **`/customer/cart` 现在其实是什么**：`ShoppingCart.vue` 的 `loadOrders()` 调 `POST /order/orderSelect`（带 `customerId`）⇒ 它是**「订单列表 + 付款 / 收货」**（含 4 段进度条），只是顶栏叫它「购物车」；商家侧同一组件的落点是 `OrderPane` 的 `meta.orderTab === 'cart'` 面。
- **为什么现在不做**：① 本笔焦点是 **C3**（管理员全链路），购物车是**业务流程**、**不是 C3 的前置**；② 它是**跨栈一笔**（DB 表 + 端点 + 前端 + 登录合并 + 测试 + 文档），与本笔「**零后端改动**」的形状冲突；③ 用户裁决「先做 C3，回头再补」。
- **将来范围（供那笔估工）**：`cart` 表（`customerId` + `goodsId` + `quantity`）+ **3~5 个端点**（加入 / 列表 / 改数量 / 删 / 结算转单）+ 前端改造（`ProductPage` 的「加入购物车」按钮 —— ⚠️ **R5 的禁令只覆盖「商品详情页进壳」，不含这个按钮**，届时要与 R5 裁定对齐）+ **未登录购物车**（`localStorage`）与**登录后合并**（登录那一刻把本地车并进服务端车）+ 手测项 + 文档。
- **与已落地事实的接口（两条，别漏）**：① `#/shoppingCart` 这条**旧地址**已由 R4 收编成 `cartLanding`（`router/index.js:126`）⇒ 那笔改的是 **`/…/cart` 的语义**，`/shoppingCart` 的落点逻辑**不用动**；② `nav.js:31` 的 tab 键 `cart`、`router/index.js:61` 的记录、`:217` 的 `ROLE_CART` 是**同一个概念的三个拷贝**。

**（9）`WebAuthConfig.java:90-95` 的 6 条 —— 本笔的处置（已结清）**
那 6 条 `REACHABLE_BUT_NOT_PUBLIC`（`/goods/goodsAdd`、`/goods/goodsDe`、`/message/friendAdd`、`/message/userSelect`、`/user/updateAvatar`、`/user/updateUserInfo`）**逐条**核过归属：它们的**调用页**（`GoodsAdd.vue` / `GoodsUp.vue` / `FriendRoom.vue` / `UserInfoPage.vue`）**全部落在守卫的受保护一侧** ⇒ 匿名再也到不了 ⇒ 当年「可达」的成因（**前端缺守卫**）**已消除**。**但仍不进 `AUTH_WHITELIST`** —— 白名单的判据是「公开面」，这 6 条一条都不是。⇒ 就地改写 `:93-95` 三条 javadoc 为「**已结清**」（净零）。

**（10）新发现的既有陈旧（**不属本笔**，登记在此）**
1. **`router/index.js:208` 的注释自证为假（R4 写的）**：原文「全仓**没有 catch-all**（`grep pathMatch` **零命中**）」—— 该行**本身**就含 `pathMatch` ⇒ 实测 `grep pathMatch` = **1 命中，就是它自己**。**不是本笔引入**（R4 落码时即如此）。**处置 = 已同行改写为「`grep` 该词**只命中本行注释自身**」**（一行换一行、净零，属「不留假话」口径）。
2. ⚠️ **`WebAuthConfig.java` 的行数口径陈旧**：`progress.md:1224` 写「文件整体仍 **135 行**」，而 **`HEAD` 实测 154 行**（多账户隔离笔加了 `AUTH_IDENTITY_PATHS`）⇒ **既有陈旧**，本笔净零（154 → 154）。**本笔不改那处按语**（它是对翻开关笔的如实记录；「本笔」在那儿指翻开关笔）。
3. ⚠️ **`api-contract.md:182` 引 `LoginPage.vue:131`** 实测该行是 `return;`（`loginSelect` 那段的收尾）—— 属「注释 / 结构锚点」一类，**行号本笔未动**、**不是漂移**，登记备查。

**（11）本笔不做**（按用户裁定，不变）：不动 `ProductPage.vue`（**R5 永久不做**）、不动界面样式（界面优化笔）、不动 C3 相关、不动 `ci.yml` / A3 脚本、**不做购物车**（见 (8)）。 ⚠️ **边界澄清（改判 2，2026-10-06）**：「不动界面样式」指的是**样式优化**（配色 / 布局 / 组件化那类笔）；改判 2 删黄条 + 改顶栏标签**不属该条** —— 它们是**未登录 UX 闭环**的一部分（用户明确纳入，见 (13)）。 ⚠️ **再按语（收口笔 (15)，2026-10-06）**：本条「**不动 `ProductPage.vue`（R5 永久不做）**」**已被本笔自己改动三处**（页内未登录 UX）—— 但那是**页内 UX**、**不是 R5**：**R5 = 「商品详情页进壳」（套 `AppShell`）仍不做**，它至今仍是独立页。⛔ 别把两者混为一谈：R5 的禁令只覆盖「套壳 / 换布局」，**不含**页内文案与两处拦截的出口。

**（12）不加「更新记录」行**（照 `§5-H (3)` / §5-N / `§5-Q (4)` / `§5-T (7)` / §5-AA (16) 先例 —— commit 时 PR 号尚不存在）。

**（13）改判 2（2026-10-06 用户裁定）：**未登录可逛公开面 + 删黄条 + 顶栏「登录 / 注册」** —— 并入本笔**

**改了三件事**：① **未登录也能用搜索 / 点分类 / 点商品卡**（淘宝 / 京东式「随便逛、**关键操作才锁**」）；② **删掉首页黄条** `.login-hint-bar`；③ 顶栏匿名形态的标签 `登录` → **`登录 / 注册`**。

- **与 R3 的关系 = 正面改判，不是修 bug**：R3 的用户裁决 1 是「先登录再逛」，§5-W (2) 据此**保留三条守卫**、§5-Z (7) 明确「**刻意不放开**」。本笔**推翻该裁决** ⇒ **10 处已加按语**（逐条：§5-U/V 来源 `:654`；§5-W 来源 `:692`、落码 1 `:696`、拦截 `:702`、归属 `:708`、净零表 `:711`、验证 T2 `:718`；§5-Z (7) `:945` / `:946`；§5-AA (14) T-new-1 `:992`；全部**行内追加** ⇒ 本文档行号零漂移）。⚠️ **§5-W 的 T2 因此失效** —— 它验的正是被改掉的行为。
- **黄条为什么能删**：它是 §5-W 的**中间形态** —— 当时顶栏**还没有匿名形态**（那正是 R3 做的 `ANON_RIGHT`）。现在顶栏已承担该入口 ⇒ 黄条与顶栏**提示同一个动作** = 冗余（淘宝 / 京东都没有这条黄条）。
- **顶栏为什么是「复合标签」而不是两个独立链接**（用户问，我的判断 + 依据）：`LoginPage` **不读任何 query 切注册态**（亲读：`isSlide` 只由 `toggle()`（`:121-122`）翻，全文只读 `query.redirect`）⇒ 两个链接**都会指 `/login`** = 指向同一处的**假承诺**（淘宝那两个链接之所以独立，是因为它们**分别直达**不同去处）。且 `AppShell.vue:19` 的 `v-for` 用 **`:key="item.tab"`** ⇒ 两项必须给**不同 `tab`**（否则重复 Vue key），为一条假承诺改数据结构不划算。⇒ **一项 `label: '登录 / 注册'`、`to: '/login'`**，`tab: 'login'` **不变**（中性高亮不变量 `route.meta.tab === item.tab` 不受影响）。**`#/register` 直达**（= 让 LoginPage 读 `?mode=register`）已登记为**另一笔**（用户 2026-10-06 选 A：本笔不做、不新建路由）。
- **`HomePage.vue` 净零账（443 → 389，−54）**：

| 位置 | 原文 | 处理后 | Δ |
|---|---|---|---|
| `:4` | 黄条 `<div v-if="!isLogin" class="login-hint-bar" …>` | 1 行模板按语 | 0 |
| `:86-94` | 弹窗 9 行 | 1 行模板按语 | **−8** |
| `:99` | `import { … computed } from 'vue'` | 原地摘 `computed` | 0 |
| `:103` | `import { isLoggedIn } from '../api/auth'` | 删（死 import） | **−1** |
| `:109` | `const showLoginModal = ref(false)` | 删（死 ref） | **−1** |
| 插在 `:104` 后 | — | **9 行「未登录可逛公开面」说明块** | **+9** |
| `:116` / `:121` / `:154` | `let timer` / `getHomeImgAndGoods` / `goodstype 消费点` | **逐字未变** | 0 |
| `:118-119` | `isLogin` 注释 + computed | **2 行同址按语** | 0 |
| `:134-140` / `:144-147` / `:165-168` | 三条守卫各 4 行拦截 | **各 4 行同址按语** | 0 |
| `:225-233` | 黄条 CSS 9 行 | **1 行按语** | **−8** |
| `:398-443` | `.login-modal` 系 **6 个块 / 46 行**（只用在那一个弹窗；`FriendRoom.vue` 的 `.modal-content` 是**别的**弹窗） | 删净 | **−46** |

- **⚠️ 判据修正（我提出、用户认可）**：**`:119` 不可能「逐字未变」** —— 它就是 `const isLogin = computed(() => isLoggedIn())`，而黄条 + 三条守卫是它**仅有**的四个消费者（grep 亲读，无第五处）⇒ 删后成**死代码**，`isLoggedIn` 的 import 与 `computed` 也随之失去消费者。⇒ 口径改为「**`:116` / `:121` / `:154` 逐字未变；`:4` / `:119` / `:135` / `:144` / `:165` 同址按语**」。
- **⚠️ 一条实测发现（与直觉相反）**：`HomePage.vue:1` 有 `/* eslint-disable */`，但**它豁免不了 `<script setup>` 里的 `no-unused-vars`** —— **探针实测**（临时插一个未使用常量 + `npx eslint` 单文件）：**exit 1**、报 `no-unused-vars`。⇒ 「`isLogin` / `isLoggedIn` / `computed` 漏删则 eslint 红」这条判据**成立且是实测**，不是推断。
- **grep 口径（用户 2026-10-06 拍板：🔑 标识符在按语里保留 / 用户可见文案必须干净）**：
  - **活引用 = 0**：`showLoginModal`、`login-modal` / `modal-text` / `modal-buttons` / `login-btn` / `close-btn`（HomePage 内）、`.login-hint-bar`（HomePage 内）；
  - **文案**：`请先登录以使用功能` = **0**；`请先登录` 只余 **`ProductPage.vue:312` / `:409`**（⚠️ 收口笔 (15) 起这两行**同行接了跳转**，但**文案逐字保留、地址仍是 `:312` / `:409`**，见 (15)）；`点此登录后可搜索` = 0；
  - **按语里保留的标识符命中**（可追溯用）：`login-hint-bar` **4**（`HomePage.vue:4` / `:98` / `:225` + `router/index.js:174`）、`login-modal` **2**（`HomePage.vue:86` / `:225`）、`isLogin` **1**（`HomePage.vue:119`）；
  - ⚠️ 别误判：`modal-content` 的 3 条命中全在 `FriendRoom.vue`（**另一个组件的弹窗**），与本笔无关。
- **验证（落码后、commit 前，全部实测 2026-10-06）**：`npm run build` **exit 0**（`Compiled successfully`，9252ms）；`npx eslint src --ext .js,.vue` **exit 0**（零输出）；`HomePage.vue` **443 → 389 行**、`config/nav.js` **69 → 69**、`router/index.js` **301 → 301**、`main.js` **99 → 99**（后三个净零）；**行号回放**：`HomePage.vue` 的 `:116` / `:121` / `:154` **逐字命中原文**，`:4` / `:119` / `:135` / `:144` / `:165` **均为按语**，三条 `emit` / `sessionStorage` 调用回到 `:139` / `:148` / `:169`（原位置）；**dist 探针**（`--exclude=*.map`）：`请先登录以使用功能` **0**、`login-hint-bar` / `login-modal` **0**、`登录 / 注册` **1**、基线 `orderTab` **11** / `showSwitch` **5** **不回退**；`git diff --name-only` = **7 文件**。
- **⚠️ 方法论纠错（我自己犯的，记账）**：dist 基线我第一遍量出 `orderTab` **31** / `showSwitch` **15**，与记录的 **11 / 5** 不符 —— 原因是**没排除 `dist/js/*.map`**（sourcemap 内嵌原文 ⇒ 命中翻倍）。排除后 **11 / 5**，与 R4/R6 基线**逐项相同 ⇒ 无回归**。⇒ 记一条：**dist 探针必须显式 `--exclude=*.map`**。
- **文件数**：本笔（守卫 + 改判 2；⛔ **不含**收口笔）合计 **7** 个，破 ≤5 的理由 = **同一主题**（未登录 UX 完整闭环：**锁受保护面 + 放开公开面 + 去冗余提示 + 顶栏入口**）。⚠️ **收口笔 (15) 再 +1（`ProductPage.vue`）⇒ 全笔 8 个**（`git diff --name-only` 实测，见 (5) 的表与 (15)）。
- **范围锁定（用户 2026-10-06）**：**这是本笔最后一次扩张** —— 不再往本笔加东西；后续（`#/register` 直达、评论区）**各自另起笔**。

**（14）📌 挂账（不留孤儿）｜评论区（未登录形态）—— 归属将来的「评论 / 评价」笔**（用户 2026-10-06 要求登记）
- **现状（本笔只读核实，全仓 grep）**：`src/` 里 **`评论` 零命中** ⇒ **评论区目前不存在**，本笔不做（唯一相近的是 `ProductPage.vue:65` 的「咨询商品？」按钮 + `consultProduct`，它已被 `:408-410` 的守卫锁在登录态之后）。
- **登记的**设计意图**（将来那笔的口径，非本笔落码）**：按「淘宝式」——**未登录可读**（列表 / 评分照常显示）、**不可写**；写入口**就在输入框位置**给「登录后评论」引导，点击 ⇒ `/login?redirect=<当前商品页 fullPath>`（**复用守卫与 401 出口那条契约，不新造第二条**）。⚠️ **本笔已把这条形状做出来**（收口笔 (15)，可照抄）：`ProductPage.vue` 未登录点购买 / 咨询 = `alert(...)` 后 **`router.push({ path: '/login', query: { redirect: route.fullPath } })`** ⇒ 评论区那笔**沿同一形状**即可（对象形式 + `route.fullPath`，别新造第二条契约）。
- **为什么只登记**：它必然要动 `ProductPage.vue`（**R5 永久不做**的同一片区域）⇒ 得等那笔的禁令重新裁定。⚠️ 与 (8) 购物车挂账**同一形态**（只登记、**不留孤儿**）。⚠️ **按语（收口笔 (15) 起）**：「R5 永久不做」= **进壳**不做，**不覆盖页内 UX** —— (15) 已动过 `ProductPage.vue` 三处（页内文案 + 两处出口），本条不再构成对该文件的**绝对**封锁。

**（15）收口笔（2026-10-06 用户裁定）：ProductPage 未登录 UX 完整闭环（三处）—— 并入本笔（本笔最后一处补）**

**病（用户两次合并发现）**：改判 2 把「未登录可逛」放开了，但**商品详情页**还留着一处不一致 —— ① 匿名进 `#/product?…`，顶栏右侧**仍写「用户中心」**（与 `ANON_RIGHT` 的「登录 / 注册」不一致）；② 匿名点「购买」只 `alert('请先登录再购买商品')` + `return`、③ 点「咨询商品？」只 `alert('请先登录')` + `return` ⇒ **用户停在原地、没有去登录的出口**。⚠️ **这不是 R5**（R5 = 商品详情页**进壳**，仍不做 —— 它至今是独立页）：改的只是**页内未登录 UX**。

- **三处改法（4 处同行替换 + 1 处 CSS，全净零）**：

| 位置 | 改动 | Δ |
|---|---|---|
| `:15` | `<div class="nav-right" @click="goUserCenter">用户中心</div>` → `{{ isLogin ? '用户中心' : '登录 / 注册' }}`（行尾接一段 HTML 按语） | 1 → 1 |
| `:135` | `import { getMerchantId, getCustomerId }` → 追加 `isLoggedIn` | 1 → 1 |
| `:312` | 购买：`alert('请先登录再购买商品')` **同行接** `if (!isLogin.value) router.push({ path: '/login', query: { redirect: route.fullPath } })` | 3 → 3 |
| `:409` | 咨询：`alert('请先登录');` **同行接**同一句 | 3 → 3 |
| `:496` | `.nav-right { cursor: pointer; }` → 加 `white-space: nowrap;`（防「登录 / 注册」在窄窗口折行；顶栏只有 50px 高） | 1 → 1 |
| 文件末尾（`:465` 后） | 新增 **8 行**：`const isLogin = computed(() => isLoggedIn())` + 7 行说明 | **+8** |

- **顶栏那处「没料到的好处」（兑现）**：`goUserCenter`（`:394-404`）**早就**分了三档（商家 ⇒ `/merchant?page=user`、客户 ⇒ `/customer?page=user`、**其余 ⇒ `/login`**）⇒ 匿名点击**本来就落 `/login`** ⇒ **只需改文案**，点击逻辑**一字未动**（该函数全文与 `HEAD` 逐字相同）。⚠️ 已登记的既有缝隙（**本笔不动**）：文案判据用 `isLoggedIn()`、点击判据用 `getMerchantId()` / `getCustomerId()`，理论上「有 `getUsername()`、两个 id 都无」时会「显示用户中心但点击落登录页」。
- **⚠️ 取舍 —— 用户 2026-10-06 拍板 (b′)**：两处拦截的判据是 `!customerId`，而**已登录商家**在商品页 `customerId` **恒为空** ⇒ 若写成「无条件 `router.push`」（方案 a），**商家会被踢去 `/login`**（明明是登录态）= **新回归**。⇒ 落码用 **(b′)**：`alert(...)`；`if (!isLogin.value) router.push(...)` —— 对**客户**（有 `customerId`、进不了这道 if）与**匿名**（跳登录）与 (a) **完全同形**，只把**已登录商家**留在原地（= 今天的行为）。⛔ **第三条路已否**：把判据改成 `!isLogin` —— 商家会带着**空 `CustomerId`** 走到 `orderAdd`，那是**拆真检查**。
- **redirect 目标 = `route.fullPath`（零新 import）**：`useRoute()` 早在 `:137` 已引入 ⇒ 拿到的就是本页 `/product?…`（**7 个参数原样**）。写法取**对象形式**（与 `main.js:49` 逐字同款），理由是**一致性** —— `redirect` query 现在**四个生产者、一个消费者**：守卫 `router/index.js:298`、401 出口 `main.js:49`、(15) 的 `:312` 与 `:409`；消费者 `LoginPage.landingAfterLogin` **不需改**（`/product` 是 `meta.public` ⇒ 它把用户**原样送回**）。⛔ **不手拼字符串**：`'/login?redirect=' + encodeURIComponent(...)` 功能上也对，但「一处手拼、三处对象」= 将来少一个 `encodeURIComponent` 就是静默 bug。
- **净零账（795 → 803，+8）**：本笔在 `ProductPage.vue` 里**没有任何删除** ⇒ 不需要补偿；4 处同行替换 + 1 处 CSS 同行 + 末尾 8 行 ⇒ **24 个被引目标零漂移**。⚠️ 关键判据：该文件被引行号清单 = `65 / 145-147 / 148-152 / 170 / 184 / 193 / 236 / 245 / 297 / 311-314 / 312 / 315-317 / 339 / 354 / 361 / 363 / 369 / 380 / 388 / 402 / 415 / 447 / 459 / 462-465`，**最大 = `:462-465`**（`WebAuthConfig.java:70`）⇒ 新增行插在 `:465` **之后** = 零漂移（与守卫块「插在最大被引行之后」同一手法）。
- **⚠️ 连带订正（本笔自查发现，不是漂移）**：草案里的 **T-guard-6** 与 **T-guard-9 的反向半截**都写着「匿名点购买**只 `alert` + return、不跳转**」——(15) 落码后**前半句仍真、后半句已假**（现在 `alert` 后会跳登录）⇒ 两处**均已就地改写**（T-guard-6 的路子**由「走不通」变「已通」**）。
- **验证（落码后、commit 前，全部实测 2026-10-06）**：`npm run build` **exit 0**（`Compiled successfully`，22622ms，`dist/js/app.5fdc77c3.js`）；`npx eslint src --ext .js,.vue` **exit 0**（零输出 —— 这次 eslint 是**实质判据**：`isLoggedIn` / `computed` / `isLogin` 任一漏用即红）；`ProductPage.vue` **795 → 803 行**；**行号回放**：上列 24 个目标**逐个回放、内容全部对得上**（`:145` = `const productId = Id`、`:152` = `const customerId = buyerId || ''`、`:311-314` 仍是那道 `if`、`:315-317` 仍是「至少选一件」、`:462-465` 仍是 `onMounted` 块）；`git diff --name-only` = **8 文件**；**grep**：`redirect: route.fullPath` = **2**（`:312` / `:409`）、模板里 `用户中心` = **1 处条件表达式**（`:15`）、`请先登录` = **2**（`:312` / `:409`，文案未改）；**dist 探针**（`--exclude=*.map`）：`登录 / 注册` **2**（原 1 = `nav.js`，新增 1 = 本页）、`用户中心` **3**（原 2 + 本页 1）、基线 `orderTab` **11** / `showSwitch` **5** **不回退**。
- **⚠️ 落码自查出的两处（我自己犯的，记账）**：① 末尾说明块第一版写成 **9 行**（多一个空行）⇒ `wc -l` 实测 **804 ≠ 803**，删掉空行后 **803**；② 计划里写的 `isLogin` computed 在脚本里的取值是 **`isLogin.value`**（`<script setup>` 里 ref 不自动解包），模板里才是 `isLogin` —— 两处已按此写。
- **手测项 = T-guard-10（5 条）**，见 (7)；其中 ①②③ 是用户给的三条，④⑤ 是反向。
- **范围锁定（用户 2026-10-06 第二次声明）**：**本笔最后一次补** —— 不再往本笔加东西，**落完 merge**。 ⚠️ **按语（同日，§5-AG (16)）**：该「最后一次」**已被用户当场再扩一格**（角色边界，**同一主题、不另立笔** —— 用户实测发现的 bug 类，见 (16)；豁免理由与文件数见 (16) 的「范围锁定」段）。

**（16）角色边界（2026-10-06 用户实测发现、当场裁定并入本笔）：已登录客户不得进商家壳（反之亦然）**

**病（用户实测，与 (15) 同一批手测发现）**：已登录**客户**手敲 `#/merchant/user/info` ⇒ **进了商家壳、拉的是客户数据**（视觉与数据不一致）；反向同理。此前**全仓没有任何角色判据** —— (3) 那条守卫的原文就写着「角色边界…是 RBAC = **C3** 的事，**本笔不做**」；用户当场改判：**C3 之前必须先做**。

- **判据 = 路由记录上的 `meta.roles`（单一事实源）**：不新建文件、不新增 import、不另抄一张表。两条**壳父记录**声明「谁能进这个壳」，`meta` 沿 `matched` 链合并 ⇒ **子路由自动继承**（与 `meta.public` 同一机制，无一条子路由需要单独标）。⚠️ **别挂到 `props.role` 上** —— `:55` 下一行的 `props.role` 是「**壳的种类**」，与本键**同形异义**（已写在该行注释里）。

| 访问者 ＼ 目标 | 客户壳 `/customer/*` | 商家壳 `/merchant/*` | 无 `roles` 标记（`/login`、`/product`） | 既无 `roles` 也非 `public` 的将来页 |
|---|---|---|---|---|
| **匿名** | ✅ 放行（R3 **不变**） | ✅ 放行（R3 **不变**） | ✅ 由第一条守卫判（受保护则 → `/login?redirect=…`） | ✗ → `/login?redirect=…`（(3) 已有行为） |
| **客户** `roleKey()='1'` | ✅ | ✗ → `/customer` | ✅ | ✗（(3) 已有行为，缺省 = 仅登录；**不在本笔范围**） |
| **商家** `roleKey()='2'` | ✗ → `/merchant` | ✅ | ✅ | 同上 |
| **管理员** `'3'` | ✅（`['1','3']`） | ✅（`['2','3']`） | ✅ | 同上 |

- **⚠️ 顺序判据（本笔唯一容易做错处）**：**匿名判据必须在 `meta.roles` 之前**。两壳的**首页 / 搜索页是 `public`**（`:58` / `:59` / `:89` / `:90`）但它们**属某个壳**；若先看 `public` 就放行，已登录的跨壳用户在这 4 条上**漏检** —— 那**正是**用户实测的那几条。⇒ 口径写死：**`public` 只豁免匿名；已登录一律再过 `roles`**（第一条守卫 `:296` 的 `if (to.meta.public === true) return true` 因此**先于**角色判定执行，且**只对匿名有效**）。⚠️ 注册序 = 执行序：第一条（登录）**先**跑 ⇒ 受保护页的匿名访问在这一条就结束，角色判定只在「已登录」时才可能生效。
- **公开面会不会被误伤**：不会。`/login`（`:53`）与 `/product`（`:125`）**没有 `roles` 标记** ⇒ `!Array.isArray(roles)` 直接放行（与守卫**同一口径**：**缺省 = 不限制**）。⚠️ 反过来说，(15) 的商品页回跳（`/product` 是 `public`、无 `roles`）**不受影响**。
- **与 `landingAfterLogin` 的共用（不重复落一张表）**：`LoginPage.vue` 的 `:265-268` **早就写死**这条口径 ——「角色校验不另抄一张表，而是**复用守卫的 `meta`**」。本笔把它**兑现**：`:278`（唯一的**公开面**分支）由「`public` ⇒ 原样回」改成「`public` ⇒ `roleAllowed(...)` 判**壳归属**」；`:281-283` 加 3 行注释、`:284-287` 新增 `roleAllowed(meta, role)`（`!Array.isArray(meta.roles) ⇒ true`，与守卫同口径）。⚠️ **只被 `:278` 调用** —— `:279`（受保护面）用的是 `base` 前缀判据，其结论**已被 `meta.roles` 蕴含**，不必再过一遍（写了也是死枝）。⚠️ `resolve(target).meta` 会不会丢 `roles`：不会，`meta` 合并的是 **`matched` 链**，父记录的键在子路由上可见（`meta.public` 就靠这个）。
- **管理员（今天不可测，登记不落码）**：用户裁定**允许进两边**（细粒度归 C3）⇒ `:55` 写 `['1','3']`、`:86` 写 `['2','3']`。⚠️ **今天管理员登不进来**：`LoginPage.login()` **没有 `role === "3"` 分支**（`:272` 已就地加按语）、`login` 表种子只有 role 1/2。⇒ 表中那两格**靠代码推演、没有手测路径**（T-guard-11 **不含**管理员），管理员那格**挂 C3**。
- **踢回哪 = 本角色首页（`ROLE_HOME`）、且不带 `redirect`**：目标页对这位用户**本就不合法**，登录完再回去也没用（同 `landingAfterLogin` 对非法目标的口径）。⚠️ `ROLE_HOME`（`:192`）今天只有 `'1'`/`'2'`；`'3'` 由 C3 补 —— 管理员两壳都进 ⇒ **走不到这里**，`|| '/customer'` 只是兜底。
- **⚠️ 为什么另起一条 `beforeEach`、不并进上面那条**：上面那条的注释块 + 5 行体被 (3)/(6) 按 **`:241-299`** / **`:298`** **整段引用** ⇒ 在它内部插行会让那两处**漂移**。⇒ 沿用「新增行落在被引最大行号之后」的既有手法（R3 §5-Z (10) 先例）：新块落在 **`:299` 之后**、文件末尾（`:329` 起）。⚠️ 本文件 `router.beforeEach` 现共 **3 处**：`:161`（旧地址收编，本笔之前就有）、`:294`（登录守卫）、`:329`（角色守卫）—— 别把 `:161` 读成本笔的。
- **撞车面（已核，全部无恙）**：全仓**只有**两处**硬编码**跨壳 push —— `OrderPane.vue:33-34`，且都是**同壳内部**（商家订单页的两个 tab）；其余落点（聊天 `basePath`、购物车 `cartLanding`、`navFor`）**都从 `isMerchant` / `getMerchantId()` 现算** ⇒ 角色一致，**不会被本守卫误踢**。⚠️ 兜底复用**本文件**的 `roleKey()`（`:225`，函数声明提升）⇒ 与既有 `landingFor` / `cartLanding` **同一套兜底**，**不新造第三份**（R6 裁决 D10 要合并的那两处**本笔不碰**，避免与待定笔撞车）。
- **净零账（本笔第二、三处改动）**：`router/index.js` **301 → 336 行**（`:55` / `:86` 两处**同行接** `meta.roles`，各 1 → 1；末尾新增守卫块 **35 行** —— 空行 1（`:300`）+ 注释 28（`:301-328`）+ 体 6（`:329-334`））；`LoginPage.vue` **522 → 529 行**（`:278` **同行替换** 1 → 1；`:281-287` 新增 7 行）。⚠️ **没有一处删除** ⇒ 不需要补偿；**新 import 数 = 0**（`isLoggedIn` / `roleKey` / `ROLE_HOME` 全在作用域内）。**文件数 = 8（不变，无新文件）** —— 破 ≤5 的豁免理由 = **与 (15) 同一主题**（用户裁定「再扩一格」）。
- **⚠️ 行号回放（本笔的硬判据）**：(6) 那条「`router` 被引最大 `:227`」、(5) 的「`LoginPage` 被引最大 `:216`」在**重核时已双双过时**（见 (5) 行号纪律行的就地订正——**最大行恰恰是本笔自己写的 `:241-299` / `:271-280`**）⇒ 落点纪律因此更严：守卫只能加在 `:299` 之后、`landingAfterLogin` 内**一行都不能增减**（`:278` 同行替换 + `:280` 之后新增）。回放结果：`:241-299`、`:298`、`:271-280`、`:55`（在 `37-58` 内）、`:86`（在 `85-124` 内）**逐个对上**。
- **验证（落码后、commit 前，全部实测 2026-10-06）**：`npm run build` **exit 0**（`dist/js/app.6507f43f.js`，76.49 KiB）；`npx eslint src --ext .js,.vue` **exit 0**；`wc -l` = `router 336` / `LoginPage 529` / `ProductPage 803`；`git diff --name-only` = **8 文件**；**grep**：`meta: { roles` = **2**（`:55` / `:86`）、`roles.includes` 在守卫内、`roleAllowed` 在 `LoginPage` 内、`public === true` = **6** 条记录**未变**；**dist 探针**（`--exclude=*.map`）：`roles:["1","3"]` = **1**、`roles:["2","3"]` = **1**（⇒ 接线**未被 tree-shake**），基线 `登录 / 注册` **2** / `用户中心` **3** / `orderTab` **11** / `showSwitch` **5** / `请先登录` **2** **全部不回退**。⚠️ **新增 import = 0（实测对 HEAD 逐文件核）**：`router/index.js` **17 = 17**、`LoginPage.vue` **3 = 3**、`ProductPage.vue` **5 = 5**（(15) 那处是把 `isLoggedIn` 追加进**已有**的 import 行 ⇒ 行数也不变）。⚠️ **行尾核查（非漂移，一并记账）**：`git ls-files --eol src` 显示**全部 24 个文件 index 一律 `lf`**，worktree 多为 `crlf`，`LoginPage.vue` 与**本笔从未碰过**的 `App.vue` / `HelloWorld.vue` 同为 `w/lf` ⇒ 是**工作区既有状态**（`core.autocrlf=true` 只影响 checkout，提交进 index 的仍是 `lf`）⇒ **diff 零噪音、无需处置**；只在 `git diff` 打了一行 `LF will be replaced by CRLF` 提示，**不是本笔引入的语义变化**。
- **⚠️ C3 待办（4 条，全部挂到将来那笔；本笔只登记）**：① 加 `#/admin` 路由记录，带 `meta: { roles: ['3'] }` 且 **不标 `public`**；② `ROLE_HOME` 加 `'3': '/admin'`（**唯一**的角色→落地页表，加一行即可）；③ `LoginPage.login()` 补 `else if (role === "3")` 分支（**今天缺**，`:272` 已就地加按语）；④ `landingAfterLogin` 的 `base` 认 `'3'`（否则管理员的兜底落点仍是 `/customer`）。⚠️ 后端那半步见 (5) 的**就地订正**（`/user/me` **已答 `role`**，原文「不含 `role`」过时）。
- **⚠️ 本笔登记的新发现（4 条，2 已就地加按语、2 已登记本段）**：① `progress.md:678` 的「`/user/me` 不含 `role`」**过时** ⇒ 已在原处加按语；② 本笔 (5) 行号纪律行的两个「被引最大行」**过时** ⇒ 已在原处加按语；③ `LoginPage` 缺 `role === "3"` 分支 ⇒ C3 待办 ③；④ `landingAfterLogin` 的 `base` 不认 `'3'` ⇒ C3 待办 ④。
- **手测项 = T-guard-11（8 条）**，见 (7)。
- **范围锁定（用户 2026-10-06 第三次声明，本笔第四次扩张）**：由「未登录 UX 完整闭环」扩为「**未登录 + 角色边界完整闭环**」—— 用户裁定为**同一主题**（都由未登录/身份边界这组手测暴露）、**不另立笔**、**破 ≤5 豁免理由 = 同一主题**。⚠️ **本笔不做（不变）**：评论区（只登记，(14)）、R5（ProductPage 仍**不进壳**）、购物车（挂 C3 后续，(8)）、CORS（归 CORS 收窄笔）、管理员**细粒度**权限（挂 C3）、**后端一字不动**。

---

### 5-AH. CORS 收窄笔（`fix/cors-tighten`，2026-10-06）—— origin 侧「通配」→ **字面量白名单**

> **性质**：**有代码**（后端 2 文件 + 前端 1 处注释 + 文档）。**一笔闭环**：方案 → 落码 → 四档取证 → 文档。
> ⚠️ **本笔唯一的行为改动 = 代码里的一行**：`ImgConfig.corsFilter` 内 `config.addAllowedOriginPattern("*")` → `applyAllowedOrigins(config, allowedOrigins)`（逐条 `config.addAllowedOrigin(...)`）。
> ⚠️ **落码后未 commit**（用户裁定：**先出 diff 给用户核 → 再 commit**）。分支 `fix/cors-tighten`，基线 = `origin/main` 的 `c70ab85`（守卫笔合并点）。

- **（1）病灶**：`ImgConfig.java` 注册在 `/**` 上的全局 `CorsFilter` = `addAllowedOriginPattern("*")` + `setAllowCredentials(true)` ⇒ **任意站点**都能让浏览器带着用户的 `JSESSIONID` 发请求**并读到响应体**。⚠️ 当年是**为了让 8080 的 dev server 真跨源打 8088** 才这么写的（§5-R (2) [12]/[13] 就是那件事的取证）—— **合法且最宽，不是配错**；但生产不能是这个形状。登记处：`defect-survey.md:102`、`tech-debt.md` TD-04。
- **（2）用户拍板三处**：① **机制选字面量**（`addAllowedOrigin(String)`，不用 `addAllowedOriginPattern`）—— 不需要 pattern、字面量更硬；将来要支持多子域名**再另开一笔**；② **文件数 6 破 ≤5 批准**（前 5 个必然：`ImgConfig` / `application.properties` / `progress` / `tech-debt` / `defect-survey`；第 6 个 `http.js` 是**直接被引方**；另两个文档同步是同一条「不留假状态」纪律，**见 (8) 的例外**）；③ **空值语义 = 「拒绝全部跨源 + WARN、进程照常启动」**，不是「空即启动失败」—— 一次环境变量没设对，不该让整个服务起不来。
- **（3）⚠️ 两种「空」含义不同（用户点名要区分的澄清，已写进代码注释）**：**完全空**（键没配 / `@Value` 默认空串）⇒ **降级**：拒绝全部跨源 + WARN + **启动期照打空清单**，**不失败**（fail-closed 的安全侧）；**非空但某项为空 / 某项含 `*`** ⇒ **启动期抛 `IllegalStateException`**（配置**格式错**，越早炸越好 —— 同 `ImgPath.initUploadDir` 的既有先例）。⚠️ 判据用 `raw.split(",", -1)`：**`-1` 必须有**，否则尾随逗号会被 `split` 吃掉、变成「静默少一条」。
- **（4）为什么只收 origin（三条备选死因 + 一条不动 `maxAge`）**：收窄 `allowedHeader` / `allowedMethod` **挡不住跨源攻击者**（能挡住他的是 **origin 被限**），却带来真回归面（`Authorization` 头、上传 multipart、actuator 的 GET）；`maxAge` 改它无收益，**却会连带动摇 §5-R (2) [13] 那条 `Max-Age: 3600` 的真容器取证**。⇒ 四项里**只动 origin**，另三项**逐字未动**（§6-A 的 P1.5 行已就地加按语保留）。
- **（5）与既有机制的关系（四问四答，均已核）**：① **与 `WebAuthConfig` 不冲突、不相干** —— 后者只管鉴权（白名单 + 拦截器），**零 CORS 内容**；唯一交界面是**执行顺序**：`CorsFilter` 是 servlet Filter（**先于** DispatcherServlet），`AuthInterceptor` 后跑 ⇒ **预检（OPTIONS）在过滤器层就被应答、进不了闸**（[13] 的实证在收窄后**仍成立**，见 (7) B3 档）；② **与 `@CrossOrigin` 无关** —— 那 5 处早在 PR #34 `23f5361` 已全删，现为唯一一套机制；③ **与 profile 无关** —— 两值都在 `application.properties`（`application-smoke` / `application-a3` 无 CORS 键 ⇒ 落到 `@Value` 的**空默认** = 拒绝全部跨源；⚠️ 但两边都**不发 `Origin` 头** ⇒ 非 CORS 请求，`CorsFilter` 直接放行，**测试与 A3 均不受影响**，实测见 (9)）；④ **环境变量 = 整体替换**（`APP_CORS_ALLOWED_ORIGINS=https://你的域名`）⇒ 生产**不会继承** `localhost:8080`，正是要的效果（实测见 (7) CASE 2）。
- **（6）落码清单与净零账**：`ImgConfig.java` **24 → 111 行**（新增 3 个 import + 类 javadoc + 常量 + `applyAllowedOrigins`；**行为改动仅 1 行**）；`application.properties` **164 → 176 行**（追加在**被引最大行 `:124` 之后** ⇒ 该处零漂移）；`src/api/http.js` **行数不变**（`:126` / `:127` **两处同行替换，1 → 1**）。⚠️ `ImgConfig` 的 `@Value` 默认值刻意写成**空串**（不是 localhost）：读不到配置的上下文（切片 / 误删配置的部署）**一律不放行**，真实值只写在 `application.properties`。⚠️ **启动期打印生效值**（ASCII 片段 `CORS`）—— 与 `AUTH` / `B2` 同惯例；**空清单也打**，否则「一个都没配」与「配了没生效」在日志里分不开。
- **（7）四档取证（真容器 + 真 jar，全部实测；端口 8099 —— 机器上 8088 已有改动前的旧实例在跑，不去打扰它）**：

  | 档 | 请求（`curl`） | 实测结果 |
  |---|---|---|
  | **A1** | `GET /img/nope.png` + `Origin: http://localhost:8080` | **404** + `Access-Control-Allow-Origin: http://localhost:8080` + `Access-Control-Allow-Credentials: true` + 3 条 `Vary` ⇒ **dev 跨源仍通**（404 = 已进到资源处理器，说明请求**没被过滤器拦**） |
  | **A2** | 同上 + `Origin: http://127.0.0.1:8080` | **404** + ACAO **回显该 origin** ⇒ 两个 dev origin **都**在白名单内（`localhost` 与 `127.0.0.1` 是两个不同 Origin，故两写） |
  | **B1** | 同上 + `Origin: http://evil.example.com` | **403**，**零个 `Access-Control-*`** ⇒ **服务端当场拒**，请求**根本进不到业务层**（同一路径同一请求 A1 得 404、B1 得 403，差的就是 origin） |
  | **B2** | 预检 `OPTIONS /user/loginSelect` + evil origin | **403** |
  | **B3** | 预检 + `Origin: http://localhost:8080` | **200** + ACAO 回显 + `Access-Control-Allow-Methods: POST` + `Access-Control-Allow-Headers: content-type` + `Allow-Credentials: true` + **`Max-Age: 3600`** + `Content-Length: 0` ⇒ **[13] 的结论在收窄后逐字成立** |
  | **C1**（突变对照） | 把白名单**换成** `http://localhost:9999`（走 `APP_CORS_ALLOWED_ORIGINS`），再打**原** origin `:8080` | **403** ⇒ 原白名单**真的失效**（不是「恰好在别处也放行」） |
  | **C2** | 同一进程打**新** origin `:9999` | **404** + ACAO **回显 `:9999`** ⇒ 值**确实是从配置读进来的**，且**环境变量是替换** |
  | **C3**（改回） | 去掉环境变量、回到默认配置，再打 `:8080` | **404** + ACAO 回显 `:8080` ⇒ **行为恢复**（突变对照三段闭合：**变 → 原 origin 失 → 改回 → 恢复**） |
  | **D**（浏览器侧等价） | `Origin: http://localhost:9090`（= 非白名单页面的 origin）打后端 | **403 + 零个 `Access-Control-*`** ⇒ 「非白名单页面的 JS 读不到响应体」**由 ACAO 的有无判定**（⚠️ 用户 2026-10-06 二次裁定：浏览器侧的本质判据就是 ACAO 头，curl 覆盖得掉 ⇒ **不装 headless 浏览器、不引依赖**；浏览器行为是**可推**，不是「未测」） |

  另外三档（边界）：**完全空** `APP_CORS_ALLOWED_ORIGINS=` ⇒ 启动日志 **WARN `CORS 白名单已装配（CORS）：0 条 []`** + 进程**照常起** + 原 dev origin **403**；**含空项**（`...,`）⇒ 进程 **EXITED**，`IllegalStateException`，栈顶 **`ImgConfig.applyAllowedOrigins(ImgConfig.java:97)`**；**含 `*`**（`...,*`）⇒ 进程 **EXITED**，栈顶 **`ImgConfig.applyAllowedOrigins(ImgConfig.java:102)`**。
  ✅ **后两条栈顶行号是对 (8) 锚点的独立交叉验证** —— 行号不是我 grep 出来的，是 **JVM 运行时给的**，与文档里写的 `:97` / `:102` 逐字相符。
  ⇒ **合计 8 条断言（A1/A2/B1/B2/B3/C1/C2/C3/D 中的 8 条判定）PASS 8 / FAIL 0**，逐条判据见上表；三道 JVM 启动（默认 / 突变 / 改回）各自的启动日志生效值分别为 `2 条 [...]` / `1 条 [http://localhost:9999]` / `2 条 [...]`。
- **⚠️ （7bis）一处与方案预判不符的实测（必须先说）**：方案里 B 档的判据原写「**非白名单：服务端仍 200（只是不带 ACAO），浏览器侧才拒**」—— **实测是 403**。成因（源码链）：Spring `DefaultCorsProcessor.handleInternal` 在 `checkOrigin(config, origin) == null` 时走 `rejectRequest(...)` ⇒ **403 "Invalid CORS request"**，且**简单请求与预检走同一条路径**（`CorsFilter` 随后 `return`、不进 `filterChain`）。⇒ **口径修正**：非白名单 origin **连「服务端处理」都不会发生**（不是「服务端照常处理、只靠浏览器拦」）—— 这比方案预期的**更强**，(7) 的 B1 档（403）与 A1 档（404）正是同路径同请求的对照。⚠️ **用户批方案时曾把「服务端 200 但无 ACAO」列为认可的关键澄清**（那是方案的错，不是用户的错）—— **2026-10-06 二次裁定后已一致**：用户要求断言的是「**403 + 无 ACAO**」，与实测相符。
- **⚠️ （8）行号副作用：3 个新 import 让 `ImgConfig.java:8` 之后整体下移 ⇒ 全仓 4 处「`ImgConfig.java:行号`」引用失效** —— 2 处在批准清单内（`defect-survey.md:102` 的 `:14,17,21`、`tech-debt.md:62` 的 `:11-22`），**2 处不在**（`api-contract.md:164`、`project-map.md:137`，两者同引 `:9-12`）。**处置**：**不为了保行号扭曲代码**（已否决「删一个空行凑行数」与「注解 / logger 写全限定名」两条备选 —— 前者是拿可读性换假行号、后者与全仓风格不符），**4 处锚点全部就地重指**到 `:55-56` + `:67-68`（`@Configuration` + `@Bean corsFilter`）/ `:67-79`（bean 全体）/ `:88-110`（`applyAllowedOrigins`），并逐处注明「原记」。⚠️ **其中 2 处超批准清单**（`api-contract.md` / `project-map.md`）—— 这是本笔的**第 8 个文件**，已**如实上报**用户（性质 = 同一条「不留假状态」纪律，与用户为第 6 个文件给的理由同源）。
- **（9）净绿与回归面实测（落码后、commit 前）**：后端 `./mvnw -B -q -DskipTests package` **exit 0**；`./mvnw -B test` = **`Tests run: 237, Failures: 0, Errors: 0, Skipped: 19` / BUILD SUCCESS**（⚠️ **按 §5-Z 的记账教训先做了陈旧报告过滤**：`target/surefire-reports/*.txt` **33 个文件 mtime 全为本次 15:00**、陈旧 0 个 ⇒ 237 是**本次**的合计，不是累加）；前端 `npm run build` **exit 0**（`dist/js/app.2a7c563a.js`，76.49 KiB —— 与守卫笔同尺寸、仅 hash 变；⚠️ 本笔对 `http.js` 的注释改过一次 ⇒ **build 跑了两次**，本处记的是**最后一次**的值）、`npx eslint src --ext .js,.vue` **exit 0**（两次均绿）。
- **⚠️ （10）取证过程本身的三条已知坑（记下来，下次省一遍）**：① **本机启动日志是 GBK** ⇒ 用**含中文**的 grep 模式去匹配**一条都命中不了**（文件里明明有）；必须 grep **ASCII 片段 `CORS`** —— 这正是「启动期打印带 ASCII 片段」这条惯例的价值所在；② 同源：**本机 GBK 控制台会把日志里的 `——`（U+2014）落成 `?`**（不可映射字符）⇒ 检索/贴日志时别把 `?` 当成代码里写错了；③ **别拿 8088 取证**：该端口上有一个**改动前**的旧实例长期在跑（PID 95784），它会给出**收窄前**的结论（curl 侧不吃 cookie 还好；浏览器侧若测错实例则**完全测不出收窄**）。
- **（11）手测项 H-CORS-1~6 的归宿（用户 2026-10-06 二次裁定）**：**改由 agent 用 curl 跑**（= (7) 那张表，**PASS 8 / FAIL 0**），**不再要求用户开浏览器** —— 理由：CORS 的浏览器侧判据就是 **ACAO 头的有无**，curl 覆盖得掉；**不装 headless 浏览器、不引依赖**。⇒ **用户只剩一件事：重启 8088 的后端**（现跑的是**改动前**的旧进程，见 (10)③），并确认启动日志里有 `CORS 白名单已装配（CORS）：2 条 [http://localhost:8080, http://127.0.0.1:8080]`。
- **（12）本笔不做（用户在本笔方案批准那条里逐条声明，照抄锁定）**：不做评论区、购物车、C3、界面样式、`ProductPage.vue`、鉴权链；**不搬 `ImgConfig` 的包**（归 `D#5` 配置收敛笔）；**不动** `addAllowedMethod("*")` / `addAllowedHeader("*")` / `maxAge`；**不动** devServer 的 `allowedHosts`；**不重跑 A3**。
- **（13）本笔登记的新发现（2 条，均未改代码）**：① **`http.js:126-127` 的注释已随本笔变假**（「`addAllowedOriginPattern("*")`」）⇒ 已**就地加按语**（1 → 1，行数不变）；② **`defect-survey.md:103`（actuator 行）双重过时** —— `application.properties:97` 是**旧址**（现 `:124`），且「匿名跨源读 `/actuator/metrics`」**自 §5-AD 翻开关笔起就已不成立**（`/actuator/**` 不在 `AUTH_WHITELIST` ⇒ 匿名得 **401**）⇒ 已就地订正为史料 + 现值指路。
- ⚠️ **（14）本节插入自身的副作用**（按 §5-P (6) / §5-Q 同口径）：本节插在 **§5-AG 之后、`## 6.` 之前** ⇒ 本文件 `## 6.` 及其后所有行号**整体后移**。已核：**仓内已跟踪文档中指向本文件的数值引用** —— 活引用**已为空**（§5-P (3) 把外部引用全改成节锚点；本文内部残余的数值引用是**引文/转换记录**），唯一按行号指本文件的活文件是 `docs/agent/archive/` 里那份归档快照，其最大引用 `:199` **在插入点之前** ⇒ **零引用偏移**。

---

### 5-AI. 界面优化笔（`feat/ui-polish`，2026-10-06）—— 全站美化，拆五笔 A/B/C/D/E；**本段只记 A 笔落码**

> **性质**：**有代码**（前端 4 文件 + 字体资产 210 文件 + 设计稿 3 处 + 文档 2 处）。**一笔大改造拆五笔**：**A** 地基（token + 字体 + 入口）／**B** 补齐 7 页 HTML 设计稿／**C** 壳 `AppShell.vue`／**D** 14 页全落 Vue／**E** 预留位（并入 D）。**本段记 A 笔的落码与取证**，B–E 只记方案与归属。
> ⚠️ **本笔不动（用户逐条声明，照抄锁定）**：**后端一字不动 / `ci.yml` / A3 脚本**；不做评论区、购物车、C3、鉴权链；`ProductPage.vue` 本笔不落 Vue（R5 仍不做）；**不引 UI 组件库、不引 diff 工具、不引字体 CDN**；**不引 pixelmatch**。
> **分支** `feat/ui-polish`，基线 = `origin/main` 的 `a4e274d`（= CORS 收窄笔 `ff22bfd` 的合并点）。

- **（1）现状盘点（拍板前的事实面，2026-10-06 实测）**：① 前端**零设计 token 层** —— `src/assets/` 原先**不存在**；14 个页面各写各的硬编码色（`#007bff`×17 / `#00bcd4`×7 / `#028fa8`×7 / `#ff4d4f`×5 …）；② 全局 reset **只有一条**，写在 `App.vue` 的 `<style>` 里；③ `docs/美化/` 三份独立可预览的设计稿（`主页改造.html` 975 行 / `子页面改造.html` 810 行 / `色系.html` 561 行），其 `:root` 即「落地源头」；④ ⚠️ **设计稿的字体一直是坏的**：两条 `@fontsource-variable` 的 `<link>` 指向 `docs/美化/node_modules/`（该目录**不存在**）⇒ 实测 **404**、`document.fonts.size = 0` ⇒ 设计稿与已入库的 `shots/` **一直是系统雅黑/宋体**在渲染（⚠️ `document.fonts.check()` 会**假报 true**，`fonts.size` 才是可信信号）。处置见 (5)。
- **（2）用户三处拍板（2026-10-06）**：① **范围 = 整个前端（全 14 页 + 壳 `AppShell` + 顶栏/左菜单）**，且**结构可重排** —— 用户**明确否掉** agent 上一轮「无设计稿的 7 页只换取值、不重排」的判断；② **功能占位可以放**，判据是**必须「有归属笔名」**（「后续会更新」= 不是假的）；**纯提议无归属一律不做**；③ **无设计稿的 7 页先生成 HTML 模板**放 `docs/美化/` → 用户挑 → 迭代 1–2 轮 → 定稿 → **才**落 Vue ⇒ 因为 **HTML 稿不动 Vue**，天然规避与 C3/购物车/评论/R5 撞车。⚠️ 用户同日**二次裁定**（并入第 ③ 条的执行细节）：**占位块现在就落 Vue**（先占位置、布局完整），功能等 C3 后续补 —— **不是**「设计稿画、Vue 不放」。
- **（3）铁律：不留死控件** —— 占位 = **可见 + `disabled` + tooltip（「即将上线」／「待 XX 笔」）+ 登记归属笔名**，**不绑任何事件**。病史两例（`showSwitch`、`/shoppingCart` 裸页）即「可点无反应」。**三层处理**：HTML 稿（B 笔）画占位并标「占位 · 待 XX 笔」；Vue 落（D 笔）按设计稿落占位；**挂账段逐条登记**（内容 + 归属笔名 + 时机）。
- **（4）分笔与撞车面**：**A** 地基（本段）；**B** 7 页 HTML 设计稿（`docs/美化/pages/<页名>.html` = GoodsSelect / ShoppingCart / OrderCenter / GoodsAdd / GoodsUp / ProductPage / LoginPage；含**三条占位形态候选 + 推荐**，见 (11)）；**C** 壳（`AppShell.vue` token 化 + 按稿重排；⚠️ **`body` 排版四件套的第一处消费点**）；**D** 14 页全落 Vue（有稿按稿、无稿按 B 定稿、结构可重排、占位按 B 登记）；**E** 预留位（并入 D）。⚠️ **撞车面**：C3（角色细粒度/管理员）、购物车笔、评论笔、R5（`ProductPage` 不进壳）—— **A/B 两笔都不碰 Vue**，撞车面被压到 D 笔。
- **（5）A 笔落码清单与净零账（实测）**：
  - `src/assets/tokens.css` **新建 147 行**：三份稿 `:root` 的**并集**，**69** 个变量 + 逐字的全局 reset。三处取舍**公开在案**（① 字号档冲突取主页 `15/19/24/32`，子页独有 `--fs-2xl:36` 并入；② 子页 `--brand*` 五件套并入；③ 主页 `--accent`/`--cin-*`/`--nav-*`/`--zone` 并入）。⚠️ **刻意不设 `body` 的字体/字号/颜色/背景** —— 一设就全站变样，与「零视觉回归」冲突，**第一处消费点是 C 笔**。
  - `src/assets/fonts/` **新建 210 文件**：`noto.css`（**202** 条 `@font-face` = 黑体 101 + 宋体 101，205.8 KB）+ **202** 个 `woff2`（**10.1 MiB**）+ `mono.css`（**6** 条，4.3 KB）+ **6** 个 `woff2`（**84.2 KiB**）。**三族全自托管**（用户 2026-10-06 选定），**无 CDN**、离线可用；变动式字体（一条声明覆盖权重轴）+ `unicode-range` 分片（⚠️ **不许删「看着没用」的分片**）。源文件只改一处：`url(./files/…)` → `url(./…)`。
  - **`--font-mono` + 等宽族（用户在 A 笔 commit 前追加的一步，边界由用户划定）**：只加**等宽**这一类，**不加消费点**。
    - **选 JetBrains Mono Variable、不选 Noto Sans Mono CJK SC**：本族定位是**数字对齐 / 指标展示 / 代码块**，三者都在 ASCII 范围；JetBrains 变体只 **84.2 KiB**（6 分片），CJK 等宽是 **5–10 MiB** 级 ⇒ **体积差两个数量级**，而用户点名的顾虑正是「仓库膨胀」。中文由**既有** `Noto Sans SC Variable` 在降级链上承担。
    - **只取 normal、不取 italic**（包内另有 6 个 italic 分片**未取**）：定位用不到真斜体，需要时浏览器会合成，或 C3 那笔再补。
    - `--font-mono` 的降级链**必须**含中文字体（`'JetBrains Mono Variable', 'Noto Sans SC Variable', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`）—— 该族**不含中文字形**，不接就是豆腐块。
    - ⚠️ `tokens.css` 原注「别再加第三族」指的是**装饰/展示族**；等宽是**功能族** ⇒ 已在原处**就地澄清**，避免与自身注释自相矛盾。
    - ⚠️ **其他字体一律不加**（用户裁定）：装饰族 / 手写体 / 艺术体**等有消费点再加**；**图标字体不做**（项目现用 emoji，C3 再说）。
  - `src/main.js` **行数不变（1 → 1）**：两条 CSS import **同行追加**到第 2 行 —— 为保住被全仓引用的 **`:21`/`:28`/`:49`（实测 **14** 处：`21`×2 / `28`×7 / `49`×5）**，沿用该文件 `:27` 的既有做法。
  - `src/App.vue` **16 → 8 行**：删尾部 `<style>` 块（reset 收敛进 `tokens.css`，同一份规则只留一处）；被引的 **`:3`（`<router-view />`）零漂移**（实测引用 1 处）。
  - `vue.config.js` **+8 行**：`chainWebpack` 关掉**字体 base64 内联**。⚠️ **这是 A 笔原清单外的第 5 个文件**（原定 4 个），理由见 (6)③；属**一行可回退**的配置，已在 diff 里单列。
  - `docs/美化/*.html` **3 文件各 2 行就地替换、行数逐字不变**（975/810/561）：字体 `<link>` 改指仓库内 `noto.css`，第 2 行留按语 —— **为保住这三份稿被引的行号**（`主页:29`/`:128`、`子页:13` 等）。
  - `docs/美化/README.md` **2 处就地按语**（原文保留）。
- **（6）A 笔取证（落码后、commit 前，全部实测）**：① `npm run build` **exit 0**；`npx vue-cli-service lint` **exit 0**；② **产物**：`@font-face` **208** 条、`url(/fonts/…)` **208** 条、**落盘 woff2 208 个**、**内联 0 个**、CSS **227.63 KiB**（gzip 70.21）；③ ⚠️ **首轮实测踩到的坑（已处置）**：webpack 默认把小字体转 base64 塞进 CSS —— 实测**内联 10 个分片**（字体内容 49.2 KiB → base64 **65.6 KiB**），**CSS 291 KiB**；对分片字体这是净亏（这 65.6 KiB 每个用户首屏都要下载解析）⇒ 用 `vue.config.js` 一行关掉，**改后 0 内联、CSS 降 65.4 KiB**；④ **零视觉回归（逐像素 + 结构性双判据）**：
    - **布局签名（主判据 —— 与图像光栅化时序无关）**：把 `body` 内**每个元素的 `getBoundingClientRect` 矩形**串起来取 md5（**不含**图像像素 / 自然尺寸 / 文字内容），再并上 `scrollHeight` / 元素数 / 标签直方图。四个路由在 **before / after 两态下逐字相同**（元素数 **112 / 19 / 48 / 48**；`scrollHeight` **1387 / 900 / 900 / 900**；标签直方图逐字相同）⇒ **布局零变化**。⚠️ 探针自身的确定性**已先验过**：同一态连起两次独立进程，签名逐字相同。⚠️ 该判据**只看 `body`**：全文档口径会多出 **2** 个元素（after 态把 reset 从 `App.vue` 挪进 `tokens.css`、又新增两个字体 `.css` ⇒ **`<head>` 里的 `<style>` 标签净 +2**），那 2 个**无布局盒**，不影响任何几何。
    - **逐像素（辅助判据 —— ⚠️ 本仓已证**不可靠**，记录在案）**：4 个路由的截图差异**只出现在 `<img>` 内容区**；**同代码态**（同 URL、两次独立进程）**也会复现同量级差异**（`#/login` 两次都差 **2696** 像素 = 0.052%，集中在插画区；`#/customer` 两次给出的图不同）⇒ 成因是**浏览器图像解码/光栅化的时序**，与 A 笔无关，**不能拿它当判据**。⚠️ 我最初报过「`#/customer`、`#/customer/search` 与改动前**逐字节相同**」—— 那是**两次刚好落在同一光栅化态**的运气，**已按上面的口径作废**。
    - ⚠️ **两个方法坑（下次省一遍）**：① 本项目是 **hash 路由**（`#/customer`）—— 拿 `/login` 之类**路径**去截，会**全都落在客户主页**上（一度误判成「路由交换」）；② 该 SPA 挂载前要 await 一次 `POST /user/me`（**超时 3 s**）⇒ 截图 `--wait` 必须 > 3 s，否则拍到的是**挂载前**的态。
    - **结构性**（与绘制时序无关）：`src/` 里除 `tokens.css` 自身外 **`var(--…)` 消费点 = 0**、**把 `font-family` 设成三族字体的地方 = 0**（⇒ token 无消费面、字体零下载）；产物里 reset 逐字为 `*{margin:0;padding:0;box-sizing:border-box}`、**68** 个 token 全在包里。
    - **行号回放**：`main.js:21/:28/:49`、`App.vue:3`、`主页:29/:128`、`子页:13` **逐字仍在**。
- **（7）完备性与口径（⚠️ 含一条自查纠错记录）**：脚本口径 = **去 `/* */` 注释 + 只取 `:root{}` 块 + 全局正则**提 `--名:`。结论：`tokens.css` 定义 **69** 个；两份页面稿 `var()` 消费 **50** 个 ⇒ **缺失 0**；`色系.html` 另消费 4 个（`--tq-700` / `--n-100/700/900`）—— 那是 `system.mjs` 的**生成器命名**，**按设计不进本文件**。⚠️ **两条自查纠错**：（a）首版脚本用**行首锚点** `^\s*(--…)`，漏掉**同行多变量**写法（`--s2: 8px;  --s3: 12px;`）⇒ 假报 7 个「缺失」（**度量假象，非文件缺陷**）；（b）头注初稿写「三份稿消费的 **58** 个全部有定义」 —— **错**（把上面那 4 个生成器名算进去了），已订正为「**50 个，缺失 0**」。
- **（8）行号纪律（全笔适用）**：本仓**全仓「文件:行号」互引**。实测面：`.vue:行号` **324 处 / 22 个文件名**；`main.js` **14 处**；`App.vue` **1 处**；指向 `progress.md` 的 **68 处**（最大 `:1318`）。规矩 ⇒ ① 新增行**必须落在被引最大行之后**；② 能 1→1 同行替换就**净零**（`main.js` 与三份设计稿都这么做）；③ **不为保行号扭曲代码**（同 §5-AH (8) 先例）。
- **（9）⚠️ 挂账：「壳内搜索笔」** —— 设计稿把**搜索框提到壳顶栏**（四个子页顶栏因此逐字一致），代价是 `HomePage.vue` 的 `.search-section` 要删（否则主页出现两个搜索框）。**本笔不做**，登记为将来那一笔（暂名**壳内搜索笔**）；`docs/美化/README.md` 的「落地前必须先裁的两件事」已就地登记。
- **（10）10 条预留位逐条登记**（= 设计稿里 10 个 `data-slot`；⚠️ 用户裁定：**有归属的才放**）：

  | # | 预留位 | 出处 | 归属笔名 | 本笔处置 |
  |---|---|---|---|---|
  | 1 | 功能入口 | `主页改造.html:787` | — **无归属** | **不做**（纯提议） |
  | 2 | 券包位 | `主页改造.html:817` | — **无归属** | **不做**（纯提议） |
  | 3 | 信任位 | `主页改造.html:829` | — **无归属** | **不做**（纯提议） |
  | 4 | 会员等级 / 积分 | `子页面改造.html:538` | **会员积分笔** | 放占位（disabled + tooltip + 归属），D 笔落、C3 后续补功能 |
  | 5 | 账户资产 | `子页面改造.html:560` | **会员积分笔**（积分部分）；券/收藏/足迹 **待裁** | 同上 |
  | 6 | 账号与安全 | `子页面改造.html:593` | **账号安全笔** | 同上 |
  | 7 | 状态枚举 | `子页面改造.html:630` | **订单状态笔** | 同上 |
  | 8 | 申请退货 | `子页面改造.html:633` | **订单售后笔** | 同上 |
  | 9 | 备注 / 删除好友 | `子页面改造.html:787` | **好友功能笔**（⚠️ `friendUp`/`friendDe` **已有端点**） | 同上 |
  | 10 | 秒杀位 | `主页改造.html:807` | ⚠️ **未定** | 用户 2026-10-06 把它归入「**已有设计稿默认沿用**」那 6 项（**不是**预留位）；但**归属笔名没给** ⇒ B 笔方案里**单列一条请用户定**，定不出则不做 |

- **（11）待 B 笔：三条占位形态候选（用户点名要 agent 给推荐）** —— ① `disabled` + tooltip（hover 显「即将上线」／「待 C3」）；② 正常视觉 + hover 提示 + **点击不响应**；③ 灰显/半透明 + 小字标注。⚠️ ② 与「不留死控件」铁律**正面冲突**（看着能点、点了没反应）⇒ 倾向 ①（必要时叠 ③ 的「小字标注」）；**最终形态在 B 笔方案里定，本段不预先拍板**。
- **（12）验收判据（全笔，D 笔完成时逐条核）**：① **无死控件** —— 每个可点元素要么绑事件、要么 `disabled`+tooltip+登记归属（可 grep `disabled` 与 `data-slot` 对账）；② 结构按设计稿（无稿页按 B 定稿）；③ 每页落 Vue 后**截图对照**（本机 `shot.mjs`，不引依赖）；④ `npm run build` + lint 绿；⑤ 行号纪律不破。
- **（13）本节插入自身的副作用**：本节插在 **§5-AH 之后、`## 6.` 之前** ⇒ 本文件 `## 6.` 及其后行号整体后移（同 §5-AH (14) 口径）。已核：指向本文件的数值引用 **68 处**、**最大 `:1318`（在插入点之前）** ⇒ **零引用偏移**。

---

### 5-AI-B. **B 笔**（10 页 HTML 设计稿落盘，2026-10-06）—— ⚠️ **纯 HTML 稿，~~`src/` 只动一行注释~~**（**已作废**：v5 收尾把 `tokens.css` 改成 **46/7**，见 (a)；C 笔再改 **4/4**，见 §5-AI-C）；编号接 §5-AI 的 (1)–(13) 续排

> **分支** `feat/ui-polish-b`，从 `origin/main` 的 **`6452489`**（= A 笔 PR #74 合并点）切出。**产物 10 份 HTML + 2 处文档改动**，**不进 Vue**（落 Vue 是 D 笔）。
> **`src/` 唯一改动（用户已批准的一行漂移修复）**：`src/assets/tokens.css:26` 把 A 笔原文「本文件定义 **68** 个变量」订正为「**69**（⚠️ A 笔漏计 `--font-mono`）」—— 同 1 行就地改、**净零**（文件仍 **147** 行；`git diff --numstat` = `1 1`）。除这行外，`src/` 与**后端一字未动**。⚠️ **按语（C 笔 2026-10-07）：本句已作废** —— v5 收尾把 `tokens.css` 改成 **46/7**（147 → 186 行）、C 笔再改 **4/4**（仍 186 行，只动注释）；以 (a) 与 §5-AI-C 为准。
> **基线变更（用户当日更正，取代方案期的口径）**：方案期按「7 页无稿」列的清单，用户裁定**两份并一份** ⇒ 最终 **10 份**、且**新增两份**（`ChatRoom` / `UserCenter` ×2 属「已有稿但需重画」）。逐份见 (15) 表。

- **（14）用户六条拍板（2026-10-06，逐条落进稿子）**：① 稿 4/5（`UserCenter` 两页）**只画到「新面板 + 左菜单」这一层**，其余面用「→ 见 xxx.html」指路；② 稿 6（`GoodsAdd`）**筛选 / 排序 / 分页照画**，标 `data-future="待 C3〈搜索〉"` —— 理由：**HTML 稿 = 很像最终的**，不因后续才做就不占位，且筛选排序分页是正规电商标配；③ **商家端订单中心 = 一页两 pane**（订单处理 / 商家购物车），**不单出 `ShoppingCart-merchant.html`**；④ `ChatRoom` **顶栏停靠独立成稿**（它有 **4 个挂载点**：`/customer/msg`、`/merchant/msg`、`/customer/user/chat`、`/merchant/user/chat` ⇒ 独立才画得清）；⑤ 稿 9（`LoginPage`）**去掉搜索框**、保留「返回 + logo + 用户中心」，保留但 token 化（它**不进壳**，不重画成壳顶栏）；⑥ 稿 10（`ProductPage`）后端底图用**纯色 / 渐变占位 + 注释**（注释标「原图来自 `/img/Mall-Homepage.jpg`」），**零新文件**。
- **（15）10 份稿（`docs/美化/pages/<Vue 组件名>-<侧>.html`，两侧共用则不带后缀；合计 3837 行）**：

  | 稿 | 行数 | 宿主 | 备注 |
  |---|---|---|---|
  | `ShoppingCart-customer.html` | 260 | `/customer/cart` | **本批的规范范本**（其余 9 份照它写） |
  | `OrderCenter-merchant.html` | 600 | `/merchant/order` + `/merchant/order/cart` | **一稿两 pane**（⑥＋③） |
  | `UserCenter-customer.html` | 256 | `/customer/user` | |
  | `UserCenter-merchant.html` | 253 | `/merchant/user` | |
  | `GoodsSelect.html` | 495 | `/customer/search` **与** `/merchant/search` | 客户 / 商家顶栏**共用一份**：实测 `GoodsSelect.vue` **无 auth 导入 / 无 props / 无角色分支**，且路由注释（`router/index.js:38`）明说**搜索页所有顶栏按钮都不高亮** ⇒ 两个 pane 的差别只有壳本身 |
  | `GoodsAdd.html` | 338 | `/merchant/goods/add` | |
  | `GoodsUp.html` | 431 | `/merchant/goods/up` | |
  | `ProductPage.html` | 406 | `/product/:code` | **不进壳**（R5），自带顶栏、无搜索框（⑤） |
  | `LoginPage.html` | 277 | `/login` | 登录 / 注册 / 注册成功三态；**零预留位** |
  | `ChatRoom.html` | 521 | 四个挂载点 | 四视图 + 一个空态视图（见 (21) 待裁） |

  ⚠️ **上表是「落盘那一刻」的快照，此后从未回填**（B 笔 v5 收尾时核出）。10 行现在**全部**偏离，**包括 B 笔一个字都没动过的三份**：`ChatRoom` 表内 521 / 实 **463**、`GoodsAdd` 表内 338 / 实 **659**、`GoodsUp` 表内 431 / 实 **573**（`GoodsAdd` 近乎翻倍）⇒ 这不是被哪一次编辑弄旧的，是**快照本身没维护**。当前逐份（`wc -l` 口径，含 v5 全部改动）：`ShoppingCart-customer` **710** / `OrderCenter-merchant` **891** / `UserCenter-customer` **453** / `UserCenter-merchant` **432** / `GoodsSelect` **630** / `GoodsAdd` **659** / `GoodsUp` **573** / `ProductPage` **981** / `LoginPage` **578** / `ChatRoom` **463**，**合计 6370**。⚠️ 表内合计 3837 与表内逐行之和**恰好相等**（已逐项加过），所以那张表**内部是自洽的**、只是整体过期 —— 不要去"订正"它的合计。**原表保留不改**：它是落盘记录，改了就不是记录了。

- **（16）与既有三份稿的三处刻意不同**：① **不内联 `:root`，改 `<link>` 仓库里真在用的三张样式表**（`tokens.css` + `fonts/noto.css` + `fonts/mono.css`）—— ⚠️ 路径是**三级** `../../../`（`pages/` 比 `docs/美化/` 深一层；实测 10 份 × 3 行全部命中）；② **颜色只有 token**：全 10 份 `grep -c '#hex\|rgb()\|hsl()'` **逐份为 0**，预留位色也从字面量改成 `var(--warning)` / `var(--info)` ⇒「无硬编码色」是**可 grep 复核**的；③ **占位四件套** = `disabled` + `data-slot` + 角标 + `title`（`title` 写全归属笔名），另加 `data-future` = **目标版应有但当前无后端**（黄 = 预留位、青 = 缺后端）。共享 `.bar` 块与 `body.slots` 规则块在 **10 份里 md5 逐字相同**（`a65fc0f0eebb` / 5 条规则）。
- **（17）⚠️ 角标**只挂区块级**（本笔踩出来的规矩，写进稿子头注 + `docs/美化/README.md`）**：角标是 `top:-11px` 的绝对定位伪元素、伸在宿主块**外** ⇒ ① **不逐行 / 不逐格挂**：`OrderCenter-merchant.html` 首版**逐行挂到 42 个**，开槽位视图后同一行三四个角标叠成一团、表头两格盖住左边的「订单号 / 商品」⇒ 改到 **6 个**（筛选栏整块 / 订单表格整块 / 每行数量块 ×3 / 结算栏整块）；逐行的重复控件只留 `disabled` + `title`。② **宿主块及其祖先都不许 `overflow:hidden`** —— 一裁角标就剩半行字（`.qty` / `.orders` 为圆角原本都写了它，已改成「不裁 + 首末个子元素自带圆角」）。规矩沿用 `子页面改造.html:779`（「12 格全标会糊成一片，标一格就够说明模式了」）。
- **（18）自检三支探针（脚本在 `桌面/软件工程/美化/`，仓库外、只读、零新文件）**：① `check-drafts.mjs` —— `file://` 直开 10 份：三张样式表挂上 / `document.fonts.size` / `--brand` 计算值 / 中文字形 / 占位计数 / **失败请求**；② `check-badges.mjs` —— 角标绝对矩形 vs 祖先链上所有 `overflow!=visible` 盒子 ⇒ 抓「角标被裁」；③ `check-contrast.mjs` —— 沿祖先求实际底色算 WCAG 对比度 ⇒ 抓「字看不见」。⚠️ **②③ 都先验过后置条件才敢用**（把 `overflow:hidden` / 深灰字在运行时**注入回去**，确认真能报出来，再拿「全绿」当结论）。
  - **实测结果**：`fontsSize 208`（= A 笔 `noto.css` **202** 条 + `mono.css` **6** 条 `@font-face`，与 A 笔产物口径一致）、`--brand = #3b7966`、`cjk true`、**失败请求 0** —— **10/10 全绿**；②③ 各 **0 处**。
- **（19）⚠️ 自查抓出并修掉的 5 处缺陷（都是「grep 查不出、只有截图/算得出」那类）**：① 角标逐行挂（见 (17)①，42 → 6）；② 角标被 `overflow:hidden` 裁（`OrderCenter` 的 `.orders`、`ShoppingCart-customer` + `OrderCenter` 的 `.qty`）—— ②的判据由探针 ② 落地；③ **`.qty` 被 grid 拉伸**到整列 132px，`+` 右边多出约 30px 空白（实测 132 − 102）⇒ `justify-self:start`；④ 购物车末列 **72px 把「删除」挤成两行**（按钮 padding 24×2 + 两字 30 = 78px）⇒ 放宽到 88px + `.btn{white-space:nowrap}`；⑤ **`GoodsSelect` 的「综合」chip 字看不见** —— `.chip.on` 与 `.chip[disabled]` **特异性同为 (0,3,0)**、后者在后 ⇒ 深灰 `--ink-3` 压在 `--brand` 绿底上 **1.09:1** ⇒ 加 `:not(.on)`。**⑤ 是探针 ③ 独立抓出来的**（首轮报「GoodsSelect 1 处对比度 < 3:1」）。另：`LoginPage.html` 的「注册成功」文案首版写成「【客户用户】」—— ⚠️ 源是 `{{ userType }}`（`LoginPage.vue:86`），其**取值**是 `客户` / `商家`（`:50-51` 的 radio `value`），「客户用户」是 **label 文案** ⇒ 已订正为「【客户】」。
- **（20）C3 登记（B 笔新发现，本笔不落）**：① **`ShoppingCart.vue` 现在根本不是购物车** —— 它渲染的是**订单列表**（含付款 / 收货 / 4 步进度），目标版购物车（数量 / 小计 / 勾选 / 结算）**后端零端点**；② **商家订单中心 Pane 2 的语义要变** —— 现状 `/merchant/order/cart` 渲染 `ShoppingCart.vue`、数据口是 `merchantId` ⇒ 实际显示的是「**商家收到的订单**」；目标是「**商家自己作为买家的购物车**」⇒ 数据口 / 身份要变（新表 / 新字段，或复用客户购物车逻辑 + 商家身份）；③ **客户订单页**（承接 ① 迁出的付款 / 收货 / 进度 / 日期）；④ **`OrderPane.vue` 的两 pane 开关在 `showSwitch` 缺省 `true` 下对商家两侧都渲染**，而 `orderTab` **只改旋钮位置与图标色、从不改内容** —— 稿里画成两个真正不同的 pane（现状是同一块内容换皮）。
- **（21）⚠️ 待用户裁的 1 条**：`ChatRoom.html` 画了**第 5 个视图「空态 · 无会话」** —— 题面说「四个视图」，但同一段又要求逐字渲染 `请选择一个会话` / `暂无会话`，那两条在「四视图内容逐字相同」的前提下无处安放 ⇒ 按范本自身的「列表 / 空态」同构惯例另加一视图承载。若裁定必须严格 4 个，删该 `<section>` 与对应按钮即可（其余不受影响）。另两条**已自裁**：`LoginPage.html` 补 5 个可见 label（`type="date"` 无 placeholder、无 label 不好看；用词全部取自既有条目名）；会话下拉的**展开态只画在视图 1**（其余三个显示 `-- 选择会话 --`）。
- **（22）两处行数订正（用户已认可）+ ⚠️ 口径**：`ProductPage.vue` **804**、`LoginPage.vue` **530** —— 口径 = `readFile().split('\n').length`（**末行的换行符也算一行**）。同两份文件 `wc -l` 给 **803** / **529**：实测三份 .vue（含 `ShoppingCart.vue` **395 / 394**）**末字节均为换行** ⇒ 两套口径**恒差 1**。方案期写的 803/529 是 `wc -l` 口径，不是算错。**改数字前先认口径。**
- **（23）本笔不做（逐字保留，与 A 笔同口径）**：不做评论区、购物车（功能）、C3、鉴权链；**不动后端 / `ci.yml` / A3 脚本**；**不引 UI 组件库 / diff 工具 / 字体 CDN**；**B 笔只出 HTML 稿，不落 Vue**。
- **（24）本段插入自身的副作用**：插在 §5-AI 正文之后、`## 6.` 之前 ⇒ `## 6.` 及其后行号再后移。已核：指向本文件的数值引用**最大 `:1318`**（远在插入点之前）⇒ **零引用偏移**。
- **（25）v5 收尾（同日第二步）—— 对上面两条的订正 + 一次互引审计**：
  - **(a) 表头那句「`src/` 只动一行注释」已不成立。** v5 收尾（用户「改 4 第三步」明确授权 = 换字体）在 `tokens.css` 里**新增 1 个变量** `--glaze-mid`、**改 1 个变量的值** `--font-display`（宋体 → 楷体 `'LXGW WenKai GB'`，链上保留 Noto Serif SC 作降级），另订正 3 处注释（房规「大字 300→500」、`--glaze-deep` 的用途、变量数 68→70）。`git diff --numstat` = **46 / 7**，文件 **147 → 186** 行。读表头时按本条读。**再订正（C 笔，2026-10-07）**：C 笔又在 `tokens.css` 改 **4 / 4**（净零，**只动注释**，文件仍 **186** 行）⇒ 表头那句与上面 `:1660` 的引文均已作废，一并见 §5-AI-C。
  - **(b) (15) 末段那张「当前逐份」表是 v5 收尾「启动时」的快照**（写在改 1/2/3 落地**之前**），已被本笔后续改动推旧。**终值（`wc -l`）**：`ChatRoom` **465** / `GoodsAdd` **661** / `GoodsSelect` **632** / `GoodsUp` **575** / `LoginPage` **580** / `OrderCenter-merchant` **989** / `ProductPage` **1058** / `ShoppingCart-customer` **716** / `UserCenter-customer` **455** / `UserCenter-merchant` **434**，**合计 6565**（快照 6370 ⇒ **+195**）。增量已逐项对平：**10 份各 +2**（WenKai 注释 + `<link>`）＝ **60**，另 `OrderCenter-merchant` **+96**、`ProductPage` **+75**、`ShoppingCart-customer` **+4**。
  - **(c) 行号互引审计：订正前全仓 17 条，逐条核过，只有 8 条失效、已就地订正。** 跨文件 **4** 条在 `ProductPage.html` 上（标题注 1 处含 2 条、`.shop` 注 2 处各 1 条），指向两稿的 `.shop` 规则，因两稿本轮各增行而漂移：**原记 `:484` / `:324` ⇒ 实 `:522` / `:326`**。文内 **4** 条全在 `OrderCenter-merchant.html`：`:155`（`:480`→`:497`）、`:350`（`:342`→`:344`）、`:514`（`:441`→`:458`）、`:565`（客户稿 `:409-422`→`:415-428`）。8 处均留「原记 …」、**逐处净零行数**。**其余 13 条**全指 `.vue` 源码（`LoginPage` / `ChatRoom` / `ProductPage` 等组件内部的行号）⇒ 本笔**未动任何 `.vue`**，无需订正（P1.2 / P2.1 两行本就自带「实测早已是 …」按语）。⚠️ 本条**刻意不写完整的「文件:行号」形式**（只留 `ProductPage.html` 不带行号）—— 否则重跑那条扫描会把本条自己也算成引用，17 就变成 18。
  - **(d) (24) 的结论仍成立**：指向本文件的数值引用最大仍是 `:1318`，本条插入点在其后 ⇒ 对**入向**引用零影响；§6 里那三处**出向**引用（原 `:1705` / `:1712` / `:1718`）随本条整体后移，无人按行号引用它们。

### 5-AI-C. **C 笔**（壳落 Vue：`AppShell.vue` / `UserCenter.vue`，2026-10-07）—— 第一个**消费 token** 的笔；编号接 §5-AI-B 的 (14)–(25) 续排

> **分支** `feat/ui-polish-c`，从 `origin/main` 的 **`3d672b1`**（= B 笔 PR #75 合并点）切出。
> **本笔只动 3 个文件、全是 CSS，新增 0 文件**：`AppShell.vue`（`--numstat` **75/30**，文件 **283 → 328** 行）、`UserCenter.vue`（**64/18**，**105 → 151**）、`tokens.css`（**4/4**，**净零**，仍 186 行）。**三处都只碰 `<style scoped>` / 注释** —— 模板与脚本**一字节未改**，故不存在「跑到一半挂掉」的中间态。
> **A 笔的预告兑现**：`tokens.css:21-24` 自己写着「本文件刻意不设 `body` 的排版四件套 …… 第一处消费点是 C 笔」，本笔落地时**仍不开全局**（用户裁决：字体只声明在壳三件上），把该注释就地订正为「C 笔仍未开」。

- **（26）顶栏落成 6 项基准形态（不改模板一个字节）**：`.top-nav` `height` **55 → 60**（`AppShell.vue` 的 `<style scoped>` 内，规则本身在 `:206`）、`padding` **40 → `var(--s7)`(=48)**、`border-bottom` 由 `#eee` 改 **`var(--line)`**、`background` 由 `#fff` 改 **`var(--surface)`**（同值，换语义名）；旧 `.top-nav a`（圆角描边按钮那条）**整条拆掉**，改 `.nav-left a`（brand 自带衬线深绿）+ `.nav-right a{color:inherit}`；`.active` 由 `#007bff/500` 改 **`var(--nav-now)` + `var(--fw-semi)`**。**实测（computed style，1440 匿名）**：`height 55px → 60px`、`paddingLeft 40px → 48px`、`borderBottomColor rgb(238,238,238) → rgb(221,226,229)`、brand `fontSize 15px → 24px`、`color rgb(0,123,255) → rgb(39,94,78)`、`padding 6px 12px → 0px`、`borderRadius 4px → 0px`。⚠️ **一处语义后果（照稿的必然结果，登记）**：稿的 `.brand` **没有 `.on` 变体** ⇒ 落成 brand 后「商城主页」在首页**不再高亮**（改前 `.top-nav a.active` 会把它染蓝：实测首页 brand `rgb(0,123,255)/fw 500`）；模板里那个 `:class="{ active }"` **保留不动**，只是 `.nav-left a.active` 不写规则。
- **（27）未读红点**：`8px → 6px`、`background-color:red → var(--accent)`、`margin-left 6 → 3`、`vertical-align: middle → super`。模板零改动（`.nav-red-dot` 与两处 `v-if` 原样）。用户裁决：**照稿用 `--accent` 朱砂**（旧房规只准朱砂管价格），`tokens.css:39` 与 `:123` 两处房规已订正为「**价格、促销与未读提示**」。**实测（合成元素）**：`width/height 6px`、`margin-left 3px`、`border-radius 50%`、`background-color rgb(178,66,47)`、`vertical-align super` ✓。
  - ⚠️ **取证方式的诚实说明**：红点 `v-if` 依赖 `unreadCount > 0`（真实未读消息），而**造一条未读 = 写 VM 库**、不属本笔 ⇒ 本笔**没有**拍真未读。改法是**只读合成**：克隆一个 `.nav-btn`（它带 `<style scoped>` 的 `data-v-*` 属性）后把 className 改成 `nav-red-dot`、塞回原 `<a>` —— 位置与真实红点逐字一致、且带 scoped 属性，量到的就是 `.nav-red-dot[data-v-*]` 那条**真规则**。**它只证明「CSS 生效」，不证明「有未读时真会显示」**（后者是 `v-if` 的事，本笔未动）。
- **（28）左菜单：圆角描边按钮列 → 发丝线列表**：`.user-menu` 宽 **220 → 200** 并**新增 `flex: none`**（⚠️ 改前只有 `width:220px`，而它是 `.user-page`（flex 行）的子项 ⇒ **实测被压到 195.9px（1440）/ 182.7px（1080）**，加 `flex:none` 后才是真 200px）；`.menu-btn` / `.logout` 共用规则由「`border:1px solid #eee` + `border-radius:6px` + `background:#fff` + `transition`」改成「`border:0` + `border-bottom:1px solid var(--line)` + `background:none` + 负 margin 让线比文字列左右各宽 `var(--s3)`」；`.active` 由「蓝框蓝底」改 **`var(--brand-tint)` 底 + `var(--brand-ink)` 字 + `--fw-semi`，无侧边强调条**（`tokens.css:37` 房规）；`.logout` 由「红字红框」改**常态 `--ink-3` 灰字、hover 才 `--danger`**，`margin-top: var(--s6)`。**实测（1440 客户）**：`.user-menu` `width 195.9px → 200px`、`display flex → block`、`gap 12px → normal`；`.active` `backgroundColor rgb(230,247,255) → rgb(238,252,246)`、`color rgb(0,123,255) → rgb(39,94,78)`、**`borderLeftWidth 1px → 0px`**、`fontWeight 400 → 600`。稿子把 `width` 写了**两遍**（`100%` 与 `calc(100% + var(--s3)*2)`），落码只写**生效值**一条并在注释里留原文。
- **（29）⚠️⚠️ 本笔抓出并修掉的 1 处缺陷（只有「量 computed style」能抓到，四支只读探针与 eslint/build 全部不报）**：`.logout` 是 **`<button>`**（模板 `:10`），不是 `<a>`；**改前**那条 `border:1px solid #eee`（**简写、四边**）+ `background:#fff` 顺手把浏览器 UA 外观压住了，C 笔把简写收成「只 `border-bottom`」并删掉底色 ⇒ **UA 的 `2px outset` 边框 + `ButtonFace` 灰底 + 自带字体族全部漏回来**。实测：`borderTopWidth 2px`、`backgroundColor rgb(240,240,240)` ⇒ 截图里「退出登录」**看着像颗真按钮**，与其余四项的发丝线行不一致（与方案 Q10#8 判据「灰字」不符）。**修** = 共用规则补 **`border: 0` + `background: none` + `font-family: var(--font)`** 三条（`border-bottom` 那条在 `border:0` **之后**重新给菜单项接上发丝线）。三条各自对治：`border:0` 清三边、`background:none` 清灰底、`font-family` 治 **`<button>` 不继承父级字体**（UA 给按钮自带 `font:…Arial`，只写 `font-size` 压不住**族名** ⇒ 不补这条，退出项与菜单项**不同字体**）。修后实测：`borderTop/Left/RightWidth 0px`、`backgroundColor rgba(0,0,0,0)`、`fontFamily` 与 `.menu-btn` **逐字相同** ✓。⚠️ 性质同 §5-AI-B 的 (19)：**grep 查不出、探针不覆盖**。
- **（30）布局容器（用户裁决：保守口径）**：**改** `.home-container` `top 55px → 60px` + `height calc(100vh - 55px) → calc(100vh - 60px)`（⚠️ **必须锁步** —— 只改顶栏不改这里，首页会被顶栏压掉 5px）、`.user-page` `gap 30px → var(--s8)`(=64)。**实测锁步**：`.home-container` `top 55 → 60`、`height 845 → 840`（1440 与 1080 同）；`.content` 的 box 也随之 `y 55 → 60 / h 845 → 840`，但 **`padding:30px` 与 `flex` 未变**。**不改（留 D 笔，逐条登记）**：`.content{padding:30px}`（及 1180 居中）、`.page`、`.cart-container` / `.order-container` 的 `border-radius:12px` + `box-shadow`、`.home-container{background:#f5f5f5}`、`.app-shell{background:#f9fafb}`（既非 token、也不属壳三件）。
- **（31）字体声明范围（用户裁决：只给顶栏 + 左菜单）**：仅 **3 处** —— `AppShell.vue` 的 `.top-nav{font-family:var(--font)}` / `.nav-left a{font-family:var(--font-display)}`、`UserCenter.vue` 的 `.user-menu{font-family:var(--font)}`。**壳内 12 个页面组件的字体一字不动**。实测 `top-nav` `fontFamily "Noto Sans SC" → "Noto Sans SC Variable", …`、`.user-menu` 同。
  - ⚠️ **订正（本笔向用户报告的第 1 条）：方案里那句「全仓零 `font-family` ⇒ C 笔会造出字体缝」的代价说明是错的。** 本机 Chrome（zh-CN、无字体设置）默认用的**就是已装的 `Noto Sans SC`**，与 `var(--font)` **同族** ⇒ **今天没有这条缝**，C 笔也没有把它造出来（改前实测 `fontFamily` 已是 `"Noto Sans SC"`，不是某个系统黑体）。方案把「未声明」误当成「不是 Noto Sans SC」。⚠️ 该结论**只对本机成立**（换台没装 Noto Sans SC 的机器，缝会出现）—— 但那是「未声明」本身的问题、与本笔无关。
  - ⚠️ brand 的 `var(--font-display)` 链首 `'LXGW WenKai GB'` **尚未自托管**，实际降级到链上的 `'Noto Serif SC Variable'` ⇒ **不是豆腐块、也不掉成默认黑体，但不是稿子的文楷**（A/B 笔已登记的已知例外，本笔不解决：自托管 = 10.8 MiB / 491 片 woff2）。
- **（32）唯一必须偏离稿子写法的地方（撞车面）**：稿子靠两件本仓没有的全局东西 —— `a{color:inherit;text-decoration:none}` 与 `body{font-family;font-size;color;background}`。**后者**按用户裁决不落全局（见 (31)）；**前者不能照抄**：`.top-nav a`（特异性 0,1,1）会**压过** `.brand`（0,1,0）⇒ 必须拆成 `.nav-left a`（brand 自己写 color/font）与 `.nav-right a{color:inherit;text-decoration:none}`。
- **（33）与稿子的两处刻意不引（用户裁决）**：① **壳顶栏不加搜索框** —— 依据是稿子自己两套口径：基准 6 份（`ChatRoom` / `GoodsAdd` / `GoodsUp` / `GoodsSelect` / `OrderCenter-merchant` / `ShoppingCart-customer`）顶栏**逐字相同且无搜索框**，只有两份 `UserCenter-*.html` 画了（实测逐份 `grep -c nav-search`：**8 份 0** / `UserCenter-customer.html` 与 `UserCenter-merchant.html` 各 **4**）⇒ 取基准，`docs/美化/README.md` 那两处已就地订正（详见该文件）。② **不引稿子的占位项**（`.uc-menu a.future`：`我的收藏` / `浏览足迹` / `收货地址`）—— 加一项要改 `router/index.js`，且属「凭空造约定」。
- **（34）四支只读探针 + 两道构建门禁**：`npm run build` **绿**（只有既有的 2 条 webpack 体积/性能警告）；`npx eslint src/views/AppShell.vue src/views/UserCenter.vue` **退出码 0**；四支探针（`check-drafts` / `check-badges` / `check-contrast` / `check-render`）**4/4 全过**。⚠️ 探针查的是 `docs/美化/pages/` 的 HTML 稿，**本笔不碰那些文件** ⇒ 这条是「**确认没误伤**」，不是本笔的判据。⚠️ 反过来也成立：**四支探针 + eslint + build 全部不报** (29) 那处 `.logout` 缺陷。
- **（35）截图取证：before / after 各 14 张（`deviceScaleFactor:2`，落 `docs/美化/shots3/`）**。原方案写「10 张」，实测按「顶栏 ×2 断点、两壳差异、匿名 + 登录态、不进壳两页、左菜单 ×2 断点」摊开是 **14 对**（`home-1440/1080`、`top-customer-1440/1080`、`top-customer-logged-1440`、`top-merchant-1440/1080`、`top-merchant-logged-1440`、`login-1440`、`product-1440`、`usermenu-customer-1440/1080`、`usermenu-merchant-1440/1080`）。
  - **最强判据 —— 不进壳两页零回归**：`#/login` 与 `#/product` 的 before/after **md5 逐字节相同**（`d986189f…` / `0ab4b1eb…`）⇒ 不是「看起来没变」，是**一个像素都没变**。
  - **匿名两壳顶栏彼此也逐字节相同** —— ⚠️ 这是**正确行为、不是坏图**：匿名态两壳的 `.nav-right` 都走 `nav.js:63` 的 `ANON_RIGHT`、都渲染「登录 / 注册」⇒ 匿名拍摄本就该一样。两壳的真差异只在**登录后**（右起第 2 项：客户「购物车」⇄ 商家「订单中心」），那两张实测**不同** ✓。
  - ⚠️ **假阴性排除**：不能拿「两张一样」当坏图的判据（见上一条）。
- **（36）⚠️⚠️ 取证踩的两个坑（都记下来防复现）**：① **脚本坑** —— 一版截图脚本每个用例建新 `page` 但**共用同一个 `browser`**（同一 cookie 罐）⇒ 前一个「已登录客户」用例落下的**客户态会话**让紧随其后、本应匿名的 `#/merchant` **被路由挡回登录页**：实测 hash 已是 `#/merchant` 但 `topNav:false` / `hasLoginForm:true` ⇒ 拍出来的「商家匿名顶栏」**其实是登录页**。**只核 `location.hash` 的判据抓不到这个**。修 = 脚本改成**每个用例一个独立浏览器上下文**（`createBrowserContext`），并加**渲染态断言**（`.top-nav` 在不在、登录表单在不在）⇒ 以后「hash 对、画面错」当场红。② **before 图有 4 张是残留 `_probe.png` 的副本**（`b-top-customer-1440` = `b-top-merchant-1440` = `_probe.png`，`…-1080` 同）—— 判据是**它们与 `_probe.png` 同源**，不是「两张彼此相同」。**before 已全部重拍**：`git stash push` 3 个文件 → 拍 → `git stash pop`，还原后 md5 逐字核对（`AppShell.vue` / `UserCenter.vue` 逐字相同；⚠️ `tokens.css` 因 `core.autocrlf` 行尾由 LF 变 CRLF ⇒ md5 变，但 `git diff --numstat` 仍 **4/4 净零**、三处 hunk 一字未改，语义无损）。临时件 `_probe.png` / `_probe-login.png` 已删。
- **（37）后端环境与两处偏差（逐字按用户约束）**：起 a3 profile 后端（`application-a3.properties:19-21` 指向 `192.168.229.129:3306`）—— **起前后各核一次** `netstat -ano | grep ':3306'`：**全部 ESTABLISHED 指向 `192.168.229.129:3306`、无 `127.0.0.1:3306`** ✓；**未改任何 `.properties`、未用命令行覆盖**；拍完**已停**（不留常驻进程）。⚠️ **两处必须明写的偏差**：① **a3 的 `app.auth.enabled=false` ⇒ 鉴权闸是关的** —— 左菜单那几张「登录态」图的**取证环境与生产不同**，不能含糊成「已按生产配置验证」；② a3 的 `app.upload-dir=${java.io.tmpdir}/a3-uploads/` ⇒ **`/img/**` 全 404 是设计行为**（`ImgPath.java:45-80` 在 upload-dir 不可用时**刻意不注册** handler、不兜假目录）⇒ 截图里商品图是裂图占位，与上一笔 `b-*` 的拍摄条件一致、前后可比。
- **（38）订正方案的第 2 条（Q10 手测清单第 6 项不成立）**：方案写「未登录点顶栏『消息』⇒ 401 踢回 `/login`」——**匿名顶栏根本没有「消息」项**（实测匿名 `.nav-right` = `[登录 / 注册]`，`ANON_RIGHT` 只有两项）⇒ 该项**无从执行**，作废。
- **（39）本笔不做（不变）**：不动页面主体（**D 笔**）；不做评论区、购物车（功能）、C3、鉴权链；**不动后端 / `ci.yml` / A3 脚本**；不引稿子的 `.nav-search` / `.uc-menu a.future` 占位项；**不引 `'LXGW WenKai GB'` 自托管**（10.8 MiB / 491 片 woff2）。
- **（40）本段插入自身的副作用**：插在 §5-AI-B 之后、`## 6.` **之前** ⇒ `## 6.` 及其后行号整体后移。已核：指向本文件的数值引用**最大 `:1318`**（远在插入点之前）⇒ **零入向偏移**。
- **（41）行号互引核对**：落码前后逐条核过。**唯一会漂移的是指向 `AppShell.vue` 样式块的引用**（本笔在该块内净增 45 行），需订正的只有两条，两处**已就地补按语**（见上文 (9)/(10)）：`.empty-page` 说明 ~~`:281-282`~~ ⇒ **`:326-327`**、两容器注释 ~~`:254-257`~~ ⇒ **`:295-298`**。**其余全部零漂移**：`AppShell.vue` 的引用除上面两条外都 ≤ `:184`（模板 / 脚本段，本笔**未动 `:187` 之前**任何行）；`UserCenter.vue` 的引用全在脚本块、最大 `:64`，本笔只改 `:68` 之后；`tokens.css` 三处注释**逐处净零行数**（`git diff --numstat` = 4/4）⇒ 行号不动。
  - ⚠️ **本条刻意不记「各文件被引 N 处」的具体数** —— 数一次就会把 §5-AI-C 自己新增的引用也算进去，数字当场失真（同 §5-AI-B (25)(c) 立的口径）。上面那两个新行号也**刻意不带 `AppShell.vue:` 前缀**，同理。

---

## 6. 前端规范化（2026-10-03 双审计）

> **口径**：`亲读` —— `Mall-management-web/mall-management-system/src` 全量 grep + 逐行读（只读，前端代码**一字未动**）。
> **上游**：为公共层**笔 2**（统一响应体 `Result<T>`）/ **笔 3**（异常 + 校验）做的**依赖面体检**；起因 = `api-contract.md:9`「无统一返回体、无全局异常处理」。
> **四项对账（沿用 `api-contract.md:135` 同口径，须自洽）**：`fetch(` **42** 处 ／ 不同路径 **31** 条 ／ 路由 **33** 条 ／ 差集 33−31 = 2（`getOrCreateConversation`、`/health/db` 无调用方）。
> ⚠️ **51 ≠ 42**：**51** 是 `http://localhost:8088` 的**出现行数**。其中 **39** 行含字面量 `fetch(`，另 **12** 行不含（6 条 URL 赋值行 + 3 处图片 `src` + 3 处 CSS `url()`）；再另有 **3** 个 fetch 用变量 URL（行内无字面量）⇒ `fetch(` 调用数 **42** = 39 + 3。（逐条见 §6-B 的 P2.1）

### 6-A. 依赖面（决定公共层笔能不能动）

| 项 | 分布 | 归属 | 验收判据 | 依赖 |
|---|---|---|---|---|
| **P1.2 `getUserInfo` 空体** | 消费点 3：`LoginPage.vue:150`、`UserInfoPage.vue:101`、`GoodsUp.vue:155` —— 均 `await res.json()` **无判空**，靠外层 `catch` 兜 `SyntaxError`（后端失败 = HTTP 200 **空体**，`api-contract.md:14`） | **A** | ⚠️ **全审计最脆一条**：笔 2/3 **不得**把该响应包成**可解析 JSON** —— 一旦 `json()` 成功且无 `merchantid`/`customerid`，`LoginPage:154/157` 静默不写 sessionStorage、**不抛不报**。判据写死：失败路径必须继续**抛异常**落 `catch`；笔 3 异常白名单**必须排除** `UserController.getUserInfo` | 笔 2 + 笔 3（⚠️ **P2.6 按语（2026-10-05，§5-AC）**：本行「消费点 3」及三个行号是**当时口径** —— `LoginPage:150`/`UserInfoPage:101`/`GoodsUp:155` 实测早已是 `:141`/`:99`/`:153`，**P2.6 后三处全改走 `/user/me`** ⇒ 该端点消费点 **0**；本条「失败必须继续抛异常」的判据对 `/user/me` 亦然（`allowUnauthorized` 只旁路 401 出口，**不吞异常**）） |
| **P1.3 message 10 端点形态** | **10/10 全 `Map`**（无 String/null）；在用 9 个（`getOrCreateConversation` 无调用方） | **A** | 顶层 `success`/`msg`/`list`/`user` **一个都不能挪进 `data.*`**（`ChatRoom.vue:169` 直接 `return data.user`；⚠️ **R4 顺带订正**：原记 `:170` —— 写这句的 `6afaac2`（2026-10-03）当时**是对的**（基线 `9b87ab9` 的 `return data.user` 在 `:170`），**笔 4/8（`5990d07`，2026-10-04）把它搬到 `:169`**、文档未跟 ⇒ 属**漂移**不是错记，见 §5-AA (11)） | 笔 2 |
| **P1.4 `goodstype` 双形态** | GET 无参→读 `list`（`GoodsAdd.vue:135`）；POST 带参→**不读响应**（`GoodsAdd.vue:166` fire-and-forget）；`HomePage.vue:154` 用 **POST 无参**→读 `list` | **A** | 两条路径都读**顶层** `list`；POST 无参分支须继续返 `{success,list}`。**已在笔 2 例外清单**（签名不动） | 笔 2 |
| **P2.4 `success`/`msg` 读点** | `.success` **34 处**真实判定（另 3 处是 CSS 类 `.success-cover`/`.success-text`，非判定）；`.msg` **17 处** | **A** | 笔 2 的「JSON 字节等价」**由这 51 处定义** —— 顶层 `success`(bool)/`msg`(string) 键名与语义逐字不变 | 笔 2 |
| **P1.1 无 `res.ok` 检查** | `res.ok`/`res.status`/`.statusText` 全 src **0 命中** | **B** | 前端**从不看状态码**，只读 body 的 `success` ⇒ 笔 3 若引入 4xx/5xx，前端**静默无感**（不报错、也不提示） | 独立笔 |
| **P1.5 OPTIONS 预检** | `ImgConfig.CorsFilter`：`addAllowedMethod("*")`（Spring `checkHttpMethod` 对 `"*"` 直放行 ⇒ **含 OPTIONS**）、`addAllowedHeader("*")`（⇒放行 `Access-Control-Request-Headers`）、~~`addAllowedOriginPattern("*")`~~ ⚠️ **2026-10-06 按语（CORS 收窄笔，§5-AH）：该通配已收窄为字面量白名单** `config.addAllowedOrigin(app.cors.allowed-origins 逐项)`（**其余四项逐字未动**；本行的 `addAllowedMethod("*")` / `addAllowedHeader("*")` / `maxAge=3600` 因此仍为现值）+ `allowCredentials(true)`、`maxAge=3600` | **C** | **已覆盖，无需改**（收窄后**依然成立**：预检判据只看 method/header/maxAge 三项 + origin 是否白名单，dev 的 `localhost:8080` 在白名单内 ⇒ 行为不变）；且前端多为 `application/x-www-form-urlencoded`（simple request）⇒ 预检基本不触发。⚠️ 收窄后新增一条口径：**非白名单 origin 的预检不再"被覆盖"，而是 403**（§5-AH (7) B 档实测） | — |

> **P1.3 逐条**（10 端点 → 前端读的字段）：`conversations`→`success`+`list`（`ChatRoom:184`/`CustomerPage:89`（R1 重指；原记 `:97`）/`MerchantPage:113`；⚠️ **按语（§5-Y，R2，2026-10-05）：这两个壳的调用点 R2 后为 `CustomerPage:108` / `MerchantPage:107`**）；`history`→`success`(+`list`)（`ChatRoom:213`/`:279`）；`send`→`success`+`msg`（`ChatRoom:327`/`ProductPage:459`）；`userSelect`→`success`+`user`（`ChatRoom:164`/`FriendRoom:132`）；`friendAdd`→`success`+`msg`（`FriendRoom:159`）；`selectFriend`→`success`(+`list`)（`FriendRoom:194`）；`friendUp`/`friendDe`→`success`+`msg`（`FriendRoom:231` 变量 URL）；`friendSelect`→`success`(+`list`)（`FriendRoom:107`）；`getOrCreateConversation`→**无调用方**。⚠️ **再按语（§5-AB，R6，2026-10-05）**：本行里 `CustomerPage:108` / `MerchantPage:107` 两个指针**已随两壳删除悬空**（合一后为 `AppShell.vue:161`；`api-contract.md:192` 已同步重指）。

### 6-B. 前端债（归一笔独立的「前端 API 层规范化」）

| 项 | 分布 | 归属 | 验收判据 | 依赖 |
|---|---|---|---|---|
| **P2.1 硬编码 URL** | `http://localhost:8088` **51 处**（14 文件）= **42** fetch 调用（39 字面 + 3 变量：`GoodsSelect.vue:81`/`OrderCenter.vue:129`/`FriendRoom.vue:231`）+ **6** 条 URL 赋值行（`GoodsSelect:74/78`、`OrderCenter:125/127`、`FriendRoom:226/228`）+ **3** 图片 `src`（`FriendRoom:71`/`GoodsUp:9`/`UserInfoPage:7`）+ **3** CSS `url()`（`LoginPage:9/10/274`）；分布：ProductPage/FriendRoom 各 **7** 最多，CustomerPage/HistoricalOrders/MerchantPage 各 **1**；⚠️ **按语（§5-X，2026-10-05）**：本行是 **2026-10-03 审计的基线快照**（`#48` 已把 **51 → 3**，见 `progress.md:81`；`api-contract.md` 全表同病、已登记于 §5-V (5)3）；其中 `GoodsSelect` 的三个指针 R1 后为 **`:91` / `:84` / `:88`**（原记 `:81` / `:74/78`），**其余指针本笔不动**（非 R1 下游）；⚠️ **再按语（§5-Y，R2，2026-10-05）**：`GoodsSelect` 那三个指针 R2 后为 **`:92` / `:85` / `:89`**（R1 记 `:91` / `:84` / `:88`），`MerchantPage` 的「各 1」计数与本行其余指针**本笔不动**（非 R2 下游；`api-contract.md` 的 4 处已随 R2 重指，见该文件 `:178/183/210/214`） | **B** | 收敛到 base URL 常量 + `.env`；与 `TD-06`（`tech-debt.md:90`，图片 URL 前缀硬编码）**同源**（⚠️ **再按语（§5-AB，R6，2026-10-05）**：本行末段「`CustomerPage`/`HistoricalOrders`/`MerchantPage` 各 **1**」里的两个壳**已随 R6 删除** ⇒ 该计数（2026-10-03 基线快照、本就未重测）**再次失效**，别当现值用） | 独立笔 |
| **P2.2 无 API 层** | `src/` 仅 `App.vue`/`components`/`main.js`/`router`/`views`；无 `api/`/`services/`/`utils`；`axios`/`request(` **0 命中**；全部原生 `fetch(` | **B** | **P2.1/P2.3/P2.5/P2.7 的根因** —— 建层后其余四项收敛到一处 | 独立笔（**最先**） |
| **P2.3 catch 错误点** | `try` **40** / `catch` **41**，覆盖**全部 42** 处 fetch（`ProductPage.vue` 2 个 try 各含 2 fetch）；全为 `console.error`/`alert`，无统一出口 | **B** | 收敛到 API 层统一 catch；`alert` 文案不属接口契约，可改 | 依赖 P2.2 |
| **P2.5 环境变量** | `.env` / `VUE_APP_*` / `process.env` 全 src **0 命中**；`vue.config.js` **无 `proxy`** | **B** | 与 P2.1 同笔：base URL 走 `VUE_APP_*` | 依赖 P2.2 |
| **P2.6 sessionStorage 直接用** | **71 处** API 调用 / **14 文件**（MerchantPage **9** 最多；⚠️ **按语（§5-Y，R2，2026-10-05）：R2 把商家壳重写后该计数**必然下降**（壳里只剩 `conversations` 一处 `sessionStorage` 之外的调用路径，`curPage`/`goSearch` 等已删）—— 本行是 **2026-10-03 的基线快照**，**未重测**，别当现值用**）；key：`merchantid`/`customerid`/`loginUsername`/**`loginPassword`（明文）**/`SEARCH_KEYWORD`/`SEARCH_RESULTS`/`PENDING_SEARCH_KEYWORD`/`FROM_PAGE` | **B** | ⚠️ **含独立安全债**：`loginPassword` **明文入 sessionStorage**（写：`LoginPage.vue:164/169`；读：`GoodsUp.vue:146`、`UserInfoPage.vue:92`），且被当凭据回传 `getUserInfo`/`updateUserInfo` —— 规范化笔须**单列**这条 ⚠️ **再按语（§5-AB，R6，2026-10-05）**：本行「`MerchantPage` **9** 最多」的计数所依附的文件**已随 R6 删除** ⇒ 该基线快照（2026-10-03、本就未重测）**彻底作废** | 独立笔（可并行） |
| **P2.7 无超时/重试** | `AbortController`/`signal`/`timeout`/`retry` 全 src **0 命中** | **B** | API 层加超时 + 有限重试 | 依赖 P2.2 |

### 6-C. 归类汇总

- **A 类（公共层笔强制处理）——4 项**：P1.2、P1.3、P1.4、P2.4。
- **B 类（独立笔「前端 API 层规范化」）——7 项**：P2.1、P2.2、P2.3、P2.5、P2.6、P2.7、P1.1。
- **C 类（不规范但无 bug）——不列**：P1.5（OPTIONS 预检已覆盖）等。

> **本节用法**：A 类 4 项是**公共层笔 2/3 的硬约束** —— 笔 2 落地前先跑「旧 `Map` vs 新 `Result<T>` 响应体**逐字（取字节）比对**」，判据即 6-A 的 P1.2/P1.3/P1.4/P2.4；B 类 7 项归一笔**独立**的「前端 API 层规范化」，**不在公共层三笔内**（边界：公共层三笔不动前端）。

---

## 7. C3（Vue3 管理端）—— 骨架三笔 + 批 A「权限与审计」+ 块1·I2「系统通知」+ 块2〈用户块〉（2026-10-09；**未提交**）

⚠️ **细节一律以 `slot-registry.md` §十三 / §十四 为准**；本节只记**进度与判据**，不复制细节（避免两处各写各的）。

- **C3 骨架三笔**（壳 → 侧边栏 + 顶栏身份 + 两个域入口 → 子项可点可跳）：登记见 `slot-registry.md` §十三。
- **批 A「权限与审计」**（= 可观测域第 11 项）：表 **`admin_audit`**（**VM 库**）+ 后端 **`admin/` 域**
  （3 个**只读**端点 `list` / `detail` / `facets`）+ 前端 **`views/admin/AuditPage.vue`**。
  验收 **20 / 20**（**真后端 + VM 库，不 mock**）；块级 pack 行号引用 **73 OK / 0 WARN / 0 FAIL**；
  `npm run build` + `eslint` **双绿**。登记见 `slot-registry.md` §十四。
- **块 1 · I2「系统通知」**（2026-10-09 —— 块 1 的**另一半**，与批 A 合为「**基础设施**」）：
  表 **`notification`** + **`notification_template`**（**VM 库**，两个 Migration 块 `mall_management_sql.sql:294-327` / `:329-345`）
  + 后端**公共层 `notification/`**（`send` fail-soft 原语 + 4 个接收端点 `list`/`unreadCount`/`read`/`readAll`）
  + **`admin/`**（`/admin/notify/*` 发送 / 历史 / 模板）+ 三端前端（管理端 `views/admin/NotifyPage.vue`；
  用户端 / 商家端共用 `views/NotifyInbox.vue` + 顶栏「通知」项与红点；入口与「消息」**分开**）。
  ⚠️ **按语（「合并入口」笔，2026-10-09 晚）：上面这句「入口与『消息』分开」已被同日用户裁决推翻** ——
  两入口**合并**为一个「消息」→ 消息中心页（页内 tab：会话 / 通知），顶栏红点 = 两笔之和。原文保留（史料）；
  ⚠️ **被推翻的只有前端入口这一层**：后端两张表 / 两套端点 / 两个 Controller **仍分开、本笔一行未动**。见下条与 §7 末。登记见 `slot-registry.md §十六`。
  ⚠️ **核心决策**：类型用**字符串 code（带命名空间，21 条）**、**不建 DB ENUM** ⇒ **加类型不改表**（后端不校验白名单）。
  验收：后端 §五·3–9 全过（含未知 `eventType` 照落）+ 权限三档 + **三端 E2E 13 / 13**；
  `bash scripts/check-context-packs.sh` **116 OK / 0 FAIL**（pack 增补 + 收官补 ref 后由 73 升上来）；`npm run build` 绿。
  ⚠️ **本笔收官时补跑项目测试套件，查出并修掉一处源自批 A 的回归**（77 errors → 0，见下方 I2 收尾条）。
  登记见 `slot-registry.md §十五`。
- ⚠️ **两处环境副作用**（详见 §十四 14-5）：① **8088 上是旧进程**，`/admin/audit/*` 一律 **404**，
  要看得**重启**它；② 验收那份 `dist/` 被构建成**指向 8089**，恢复常规产物须**裸跑** `npm run build`。
  ⚠️ **2026-10-09 更新**：**② 已关闭**（I2 已裸跑 `npm run build`，`dist` 回 **8088**）；
  **① 的真相被改正** —— 8088 不是「闸开」而是 **`-Dspring.profiles.active=a3` + 闸关**（用户从 IDEA 起，
  加载的是**旧 class**）⇒ 它对 `/notification/*` 404 **只是没重编译**，**在 8088 上重启该进程即可验**
  （TD-18 那个「管理员 401」只咬**默认 profile**）。该进程是**用户从 IDEA 起的**，agent **未动**。
- **未提交、未 push** —— 连同此前各笔全在工作区（用户口径「**攒着，等用户过一遍**」）。

- ⚠️ **顺带补齐一处旧漏登**：`db-schema.md` 的表数长期停在 **12** —— 漏了批 A 的 **`admin_audit`**。
  I2 登记时一并补齐到 **15**（11 原始 + `order_state_transition` + `admin_audit` + `notification` + `notification_template`），
  连带修了它的索引 / UNIQUE / 外键三处基线。见 `db-migration.md` 注意 8。

- ⚠️ **I2 顺手做的两处「账目更正」**（都是**既有陈旧**，非本笔新错，但被本笔照出来）：
  1. **指向 `db-schema.md` 的外部行号引用已就地重指** —— 本笔使该文件 **388 → 453** 行（净增 **65**），
     **6 行 / 3 文件 / 7 处**全部同行改数字（**净零行**）：`selection-status.md:140`（`:180`→**`:188`**、
     `:363`→**`:428`**）、`:141`（`:172`→**`:180`**）、`:142`（`:365`→**`:430`**）、`:175`（`:49`→**`:53`**）、
     `defect-survey.md:187`（`:172`→**`:180`**）、`progress.md:413`（`:178`→**`:186`**）。
  2. **另登记 6 处「既有陈旧」引用（只登记不修）** —— 引 `db-schema.md:91` 的 4 处（`defect-survey.md:182`、
     `slot-registry.md:272`、`docs/美化/pages/OrderCenter-merchant.html:70`、`OrderDao.java:133`）、
     `selection-status.md:100`（`:29`/`:282`）、`progress.md:1341`（`:96`，这一处**是本笔**造成的偏移）。
     明细与判据见 `slot-registry.md §十五 15-5` 第 4–5 条 —— 派给「**文档漂移清理笔**」。
- ⚠️ **占位页计数再减 1**（I2 把 `/admin/business/notify` 换成真页 `NotifyPage.vue` 的直接后果）：
  `views/admin/StubPage.vue` 与 `router/index.js` 里的「**12 条**占位记录 / **子项 10 条**」同步改为
  「**11 条** / **9 条**」；两处的「抄 N 遍」也各减一（`StubPage.vue` **11**→**10**、`router/index.js` **13**→**11**）。
  两文件**同行替换 ⇒ 零行漂移**（`router/index.js` 仍 **498** 行）。
- ⚠️ **§五·13「不回归」查出一处红的测试套件并修掉**（**方案外增改 1 个文件**，详见 `slot-registry.md §十五 15-7`）：
  收官前补跑 `./mvnw -B test` → **237 测试 / 77 errors / BUILD FAILURE**。根因**源自批 A**：
  `AbstractSmokeTest` 是 `@WebMvcTest` **切片**（加载全部 `@Controller`、但**不扫 `@Service`**），
  它用 `@Import` 逐个拉真 service；批 A 的 `AdminAuditController` 与 I2 的两个通知控制器**没进名单**
  ⇒ 切片上下文启动失败（首报 `AdminAuditController required a bean of type 'AuditService'`），
  77 个 error **全是同一个根因的扇出**（10 个 smoke 类）。
  **修法**：该基类 `@Import` 补 `AuditService`/`AdminAuditDao`/`NotificationService`/`NotificationDao`/
  `NotificationBroadcastService`（+ 6 行 import + 一段注释）。复跑 → **237 / 0 failures / 0 errors /
  19 skipped / BUILD SUCCESS**（`Skipped` 前后都是 **19** ⇒ 不是靠 skip 变绿）。

- ✅ **收官（用户裁决 ② 落地）：「通知发送挂审计」** —— 新增全仓**第 2 个**审计挂钩点
  （第 1 个是批 A 的 `goods.update` 样板）。改 **3 个文件**：`AuditActions` 加常量 `notification.send`；
  `NotificationBroadcastService.send` 回体**多带生效的 `eventType`**（免得 Controller 重算默认值
  = 同一条规则的第二个副本）；`AdminNotificationController.send` 在 `success=true` 分支记账
  （`before` 传 **null** = 新增类动作；`TargetId` 用 **batchId** 而非 `notification.Id`，因本表**跨重导保留**；
  `AfterJson` **只带索引字段、不抄正文** —— 正文留 `notification` 行，用 batchId 一 join 就到）。
  实测（临时新编译实例 8099/a3/闸关，**验完即停**，未动用户的 8088）：正例 **2** 行、**负例（缺受众）0** 行、
  `BeforeJson` 全 NULL、`TargetType=notification` / `TargetId=batchId`、回体带 `eventType=system.notice`；
  原文见 `docs/agent/evidence/i2/audit-hook/`。⚠️ 那两次请求**没带 cookie** ⇒ `ActorId`/`ActorRole` 记
  **NULL**（如实记「没识别出 actor」，不编造）。详见 `slot-registry.md §十五 15-8`。
- ⚠️ **本钩子生效要重启后端**：8088（PID 136460，用户从 IDEA 起）跑的是加钩子**之前**的 class
  —— 19:14 实测它的 `/admin/notify/send` 回体里**没有 `eventType` 字段**。在 IDEA 里**重启一次**即可。
- ⏸ **另一条自查按裁决「登记不修」**：接收侧 `toId` × `toType` 不校验（错配只是空集，无害）⇒
  立 **TD-19**（`tech-debt.md`），**未动** `requireSelfOrAdmin` 契约。
- 🧹 **临时产物归位**（用户要求）：桌面根 / 项目根两份 `_a3work/` 已清（**证据先全量拷出**）——
  可复跑探针 → `scripts/probes/`、证据 → `docs/agent/evidence/i2/`、临时物 → `_work/`（**已 gitignore**）；
  规矩写进 `agent.md` 开发要求 **9**。见 `slot-registry.md §十五 15-9`。
- ✅ **收官复跑**：后端 `./mvnw -q -DskipTests compile` 退出 **0**；`./mvnw -B test` **237 / 0 / 0 /
  19 skipped / BUILD SUCCESS**（**含新加钩子后的重跑**）。

- ✅ **「合并入口」笔（2026-10-09 晚，用户裁决）—— I2 的前端入口层被合并**：顶栏「通知」项**删掉**、只留「消息」；
  点「消息」进**消息中心页** `views/MessageCenter.vue`（页内 tab：**会话** = `ChatRoom` / **通知** = `NotifyInbox`，
  两者**内部逻辑一字不动**）；顶栏红点 = **未读会话 + 未读通知**（`dotCount` 里相加，**两套 5s 轮询仍各走各的**）。
  旧地址没死：`/customer/notify`、`/merchant/notify` 改 **`redirect` → `…/msg?tab=notify`**（tab 状态住 **query**）。
  **只改「怎么进」，不改「进去干什么」**：后端两张表 / 两套端点 / 两个 Controller / `NotificationService` 原语 /
  `notification.send` 审计钩子 —— **本笔一行未动**（故 15-7 / 15-8 那两条本笔不适用）。
  ⚠️ **「I2 的『入口分开』口径」就此作废**（`slot-registry.md §十五 15-3` 已加按语；15-2 后端**仍然有效**）。
- 📐 **本笔的硬约束 = 净零行数**：动过的三份文件都被文档按行号引（`nav.js` **17 处** / `router/index.js`
  **最大被引行 `:462`** / `AppShell.vue` **63 处**）⇒ 手法同 15-3：**同行替换** + **墓碑等行数** + **新增行落被引行之后**。
  结果：`nav.js` **79 行（不变）**、`AppShell.vue` **464 行（不变）**、`router/index.js` **498 → 505**
  （**+7 全在文件尾**：`addRoute` 由「挂页面」改 redirect 时补的注释；`:462` 及其之前一行未动）。
- 🔎 **行号引用复核（本笔收尾必做）**：三份文件的行号引用**逐条核过内容**（不止核「指得到」）——
  **全部指对、无一条需重指**。⚠️ 唯一「差一点」：`AppShell.vue` 进页清红点块重写后，块内某句的行内位置变了
  （`slot-registry.md:2361` 钉的那行）⇒ 已把该句**回填原行号**。临时工具 `_work/refcheck.py`（不入库）。
- 🧪 **验收**：`node scripts/probes/probe-msg-center.mjs` → **17 项 / 0 失败**（红点那条**穷举四组合**：
  会话 1+通知 2 / 0+2 / 1+0 / 0+0 ⇒ 亮亮亮灭）；`npm run build` **绿**（`2026-10-09T11:38:51Z`，hash `3b17ecf01e888659`）；
  `eslint` **无错误**。证据 `docs/agent/evidence/msg-center/`（`probe.out` / `result.json` / `shots/01..04`）。
- ⚠️ **用户说的「块 6 那条『消息与通知 UI 入口合并』待办」在 `docs/` 里查无此文** —— 按多组关键词搜过
  （`docs/**` 与 `agent.md`），**没有任何一条**以它为名。最接近的是 15-3 那句**「入口分开」**（口径陈述、
  方向还相反，已加按语）。⇒ **没有「从待办里删掉」这个动作可做**；细节见 `slot-registry.md §十六 16-4`。
- ⚠️ **真后端 E2E 未重跑（挂账）**：本笔用 mock 探针验收；真后端上的「发送 → 顶栏红点 → 进页清点」链路
  此前由 I2 的 `probe-i2-e2e.mjs`（**两个入口**时代）13/13 覆盖过，**合并后尚未在真后端上重跑**
  （8088 是用户 IDEA 起的旧 class ⇒ 要验得先重启 8088）。谁复验谁补这一条。
- 🔧 **CI 首跑红了一次 → 已定位并改门禁（2026-10-09 晚）**：PR 上三个 job 里**只有**
  `A3 concurrency (120 QPS)` 红，且红在**负载段之前** —— 前置校验 `P3 表数` 报 `表数=15（期望 11 + 1）`，
  随后「前置校验失败，未产生任何业务流量。退出码 2」。`Backend tests` / `Frontend build` 全绿；
  后端 `app.log` 启动干净（唯一命中 `ERROR|Exception` 的行是 Hikari 的
  `exceptionOverrideClassName......none`，一条 DEBUG）。
  **真因不是库坏了**：`mall_management_sql.sql` 本笔新增 **3 张表**（`admin_audit` / `notification` /
  `notification_template`）⇒ 12 → **15**，而 P3 的期望还停在「11 + `order_state_transition`」= 12。
  ⇒ 改 `scripts/concurrency/ConcurrencyRunner.java` 的 P3：期望从「11 + 单张点名」改成「11 + **IN 清单**计数」，
  并加一条「清单里那 4 张必须**真在**」（`known == KNOWN_TABLES`）—— 保住 TD-01 的原意
  「**多出来的每一张都必须点过名**」，而不是把 12 硬改成 15。
  `javac -encoding UTF-8` 本地编译**退出码 0**；算式对红那次的实测 `表数=15` = `15 == 11 + 4 && 4 == 4` ⇒ **PASS**
  （「4 张全在」由红的那次**自己证过**：15 = 11 基线 + 1 时间线 + 本笔 3 张）。
  红的证据落 `docs/agent/evidence/c3-ci-p3/a3-run-20261009-120224.txt`；登记见 `slot-registry.md §十七`。

- ✅ **C3 块 2〈用户块〉落码 + 验收完成（2026-10-09 晚，未提交）**：块 1 已 merge、正式收口；本块起**启用提速规则**。
  三端一起落 —— 用户端**注册直达 `#/register`** + 资料完善、商家端**入驻申请**、管理端**用户治理 + 商家审核 + 降级控制**。
  **A 档 5 项已裁决**：A1 新表 `merchant_application`；A2 入驻 = **客户升级**（CAS 幂等 + 批准与改 role 同事务）；
  A3 **封禁只标 `user`** + `loginSelect` 双查返 **4**；A4 收件箱扩 **`ToType=admin` / `ToId='*'`**；
  A5 降级商家（`@Scheduled`、**冻结新单允许收尾旧单**、30 天不删只冻结、只手动触发、五阶段）。逐条登记见 `slot-registry.md §十八`。
- 🏗 **建表（VM 库，不可逆）**：`mall_management_sql.sql:348-409` —— 新表 `merchant_application`（16 列，`:369-390`）
  ＋ `user` 加 **8 列**（`:396-404`）＋ `goods` 加 **2 列**（`:407-409`，`ShelfState` **1=在架 / 0=已下架**）。
  ⚠️ **本仓首次「改基线表」**（此前只加表、不动基线列）；加列**不幂等**（MySQL **不支持** `ADD COLUMN IF NOT EXISTS`，
  重复执行报 Duplicate column 属预期）。实测 `apply.out` `rc=0`、库内表数 **16**、列/索引与正向 SQL 一致，
  证据 `docs/agent/evidence/c3-block2/db/`（`migration.sql` / `state-before|after.txt`）。
- 🧩 **后端**：新 `merchant/` 域（申请读写 + 降级 job）+ `admin/AdminAccountsController`（8 端点）/`UserGovernanceService`
  + `user/UserGovernanceDao` / `UserStateLookupService` + `goods/GoodsShelfDao` + `shared/web/UserStateLookup`。
  改既有：`AuditActions` **+7**（2→9 条）、`NotificationEvents` **+3**（21→24）、`UserController` 加 `/user/updateProfile`、
  `UserService.loginSelect` 封禁双查、`IdentityGuard` **13→16 端点**、**`AuthInterceptor` 第 ⑤ 档封禁闸**
  （复用 401、**管理员豁免** —— 否则一次误封锁死整个管理面）、**`WebAuthConfig` 用 `ObjectProvider<UserStateLookup>`**
  （否则**每个 `@WebMvcTest` 切片启动即集体失败** —— block 1 同型事故 77 errors）、`OrderService` 冻结新单允许收尾旧单。
- 🎨 **前端**：新 `api/accounts.js` / `api/merchant.js` / `MerchantApplyPage.vue`（⚠️ **挂用户端** —— 商家壳被
  `meta.roles:['2','3']` 锁住，未审核客户进不去）/ `admin/AccountsPage.vue`（四段）；**注册直达 `#/register`**
  （文件尾 `addRoute` + `isSlide` 读 `meta.register` + `$route` watch 补外部导航 + `toggle()` 同行写 URL）；
  修掉全站 **3 条** vue-router `checkChildMissingNameWithEmptyPath` 噪音（三处子记录 `:58`/`:76`/`:89` **同行**加 `name`）。
  ⚠️ 全部**净零行数**改法（`router/index.js` / `LoginPage.vue` / `UserInfoPage.vue` 都被文档按行号引）。
- 🐞 **顺带修一个既有 bug —— `/img/**` 整片 404**（**与块 2 无关**，用户报「登录页大图空白」牵出）：
  真因 = a3 profile 把上传目录外置到**空目录**（`application-a3.properties:50`，TD-08），而 `ImgPath` 修前**只**注册它
  ⇒ **应用自带**的图（`Mall-Section-*.jpg` / `default_user.png`）一起 404。修法 = 注册**两个有序位置**
  `{ 上传目录, classpath:/static/img/ }`（首个命中优先）。⚠️ 它修的是**一类** bug：a3 profile 下**任何**自带图都 404；
  且**不是**布局/渲染回归（实测 `.brand-fig` 盒子没塌，1440×900 下 440×248）。证据 `docs/agent/evidence/c3-block2/login-fig/`。
- 🧪 **验收**：`./mvnw -B test` **239 / 0 / 0 / 19 skipped / BUILD SUCCESS**（块 1 时 237）。UI 探针（证据
  `docs/agent/evidence/c3-block2/`，README 每条标【实测】/【推断】两档）：`probe-accounts-apply` **17 / 0**、
  `probe-register-deeplink` **4 / 4**、`probe-router-warns` **3 壳零 warn**（⚠️ **基线差分**不是「零绝对量」）、
  `probe-login-fig`（定性）。⚠️ 探针**只证前端一侧**，后端由 `mvn test` 覆盖（脚本走 mock 是因 8088 真进程不得杀）。
- 🔎 **登记**：`db-migration.md` **#6**（+ 注意 9）、`db-schema.md` **15→16 表**（453→**493**，+40）、
  `slot-registry.md §十八`、`c3-blocks-index.md:14` 状态。因 `db-schema.md` 增行**重指 7 处**外部引用
  （`selection-status.md` ×5 / `defect-survey.md` ×1 / `progress.md` ×1，按 `@@` hunk **确定性映射**，非手估）；
  pack `c3-block-2-user.md` **行号同步 20 处**（后端 8 + 前端 9 + 裸简写 3，逐条 grep 对实际内容核过），
  `bash scripts/check-context-packs.sh` 报 **170 引用 / 0 WARN / 0 FAIL**。
- 🔬 **refs-scan 重扫**（`_work/refs-b2doc-after.txt` vs `before`）：**丢失 20 个 token，逐一核对 = 全部是本次有意的重指**
  （无一处非预期丢失）；after token 集 **2055**（before 2032）。
- ⚠️ **未做 / 待办**：① `ShelfState=0` 的**买家侧过滤**随**块 3**；② 注册页「商家」选项**绕开审核**
  （`LoginPage.vue:175-176`）**登记不修**（改它＝动既有注册契约）；③ 既有陈旧 **6 处**引用（I2 笔遗留，
  见 §十八 18-6 尾）派「文档漂移清理笔」；④ **真后端 E2E 未重跑**（8088 是用户 IDEA 起的**旧 class**，
  须**重启**才认新 Controller —— 那条进程**不得杀**）。

- 🔧 **手验后补丁（2026-10-09，用户手验 6 条）**：A 组必修 4 条 + B 组 2 条**全落** —— 详见 `slot-registry.md` §18-9。
  要点：**A1 真因 = 把登录名当业务号用**（`MerchantApplicationController` 把登录名交给按业务号比较的
  `IdentityGuard.requireSelfOrAdmin` ⇒ 非管理员必 403；`/user/updateProfile` 同病，一并修，**wire 契约不动**）；
  **A2「解封入口缺失」未复现**（入口早在块 2 落码时就有 ⇒ 判旧构建 / 缓存，只加固 `isBanned` 认 `'true'`）；
  **A3 用 SQL 级 CAS**（`WHERE … AND DowngradeState='none'`）+ 返回 `success:false` ⇒ **A4（14 条重复通知）结构上消失**；
  **B1 甲案**（`AccountsPage` 页内三 tab + 收件箱提成 `/admin/inbox` 顶层项；规范落 `c3-modules.md` §5.1）；
  **B2 按甲落黄条**（`AppShell.vue` 的 `.nav-notice`，与红点**同一支** 5s 轮询）。
- 🧹 **行号互引整笔重定基**（`_work/renumber-b2.mjs`，机械 + 逐条打印）：**具名 105 + 裸 21 = 126 处 / 20 文件**；
  `check-context-packs.sh` → **290 引用 / 0 WARN / 0 FAIL**；`refs-scan` before/after **3597 → 3595**，
  **零非预期丢失**（少的 2 条同出被有意删掉的 `AccountsPage.vue:207` 那个 ⑤ 块注释）。

- 🔧 **手验后补丁（第二轮，2026-10-09/10 用户手验，两条 A 组必修）**：详见 `slot-registry.md` §18-10。
  **Bug 1（`/admin/inbox` 403）真因 = 管理端带着「别人的票」** —— 403 只出 `IdentityGuard.forbidden()`
  ⇒ 反推 `identity!=null ∧ role≠3 ∧ userId≠'*'`；用户两个猜测都不成立（解不出身份走 **fail-open 放行**、永不会 403；
  cookie 管理员折 null 走的是**拦截器**、产物是 **401** 且没走到）。链条 = `main.js` 认领的共享指针
  → `http.js:149-150` 挂票 → `identify` **票优先** → 管理员又**铸不出票**（TD-18）。
  **实测**：8088 **不带凭据**打 `/notification/list` = **200**（证明用户那发**确实带了**非管理员凭据）；
  **DB 旁证**（a3 库）`admin_audit` Id=22 的 `user.ban` 记 `ActorId=uDopHnCeLS / ActorRole=1`，而那是**客户 1111** 的业务号。
  ⚠️ 用户 8088 跑 **a3 profile**，`application-a3.properties:98` **闸是关的** ⇒ **手验绿 ≠ 默认 profile 绿**。
  **修法 = 丁（用户裁决，甲/乙/丙被否）**：`api/auth.js` 尾追加 `dropPageToken()`（**只动 sessionStorage**，
  与 `clearToken` 的 D6「连指针一起删」相反）+ `main-admin.js` `bootstrap()` **第一行**调用它
  （**早于 `restoreIdentity`**，否则那发 `/user/me` 也带票）⇒ 闸关档两病同治（收件箱 200 + 审计记 role=3）。
  🔴 **闸开档治不了** ⇒ 新挂账 **TD「管理端身份根治」**（管理端结构性无凭据 ⇒ `/admin/**` 全 401；
  连带闸关档零凭据也能写审计 `ActorId=null`）。
  **Bug 2（封禁用户登录提示错）**：后端**返对了 4**（`UserService.LOGIN_BANNED`），前端**无 `"4"` 支**、
  且 `:306` 的 `/user/me` 对封禁用户**必 401 先抛** ⇒ 落 `:328` 通用文案；修法 = `:300` 早退（**同行追加、净零**），
  并纠正 `UserService` javadoc 一处错述 + 标注 `:317` 那支**本就不可达**（既有）。**四个改动文件全部零行号漂移。**
  🧪 `npm run build` **DONE**（Hash `557c5bf852801885`）；`git diff --numstat` = `auth.js 20/0 · main-admin.js 14/1 ·
  LoginPage.vue 2/2 · UserService.java 2/2`。**待用户重启 8088 手验。**
- 📦 **块 2 收口（2026-10-10）**：上述 §18-9 + §18-10 全部改动 **commit `a224840`** 并 **push 到 PR #83**
  （`feat/c3-admin` → `main`；PR 上共 **2 个提交** = `f8f67d6` + `a224840`；35 文件 / +930 −159）。
  7 个 `docs/美化/_dbg*.mjs` 按仓规**不入库**。**混合 PR ⇒ 由用户 merge**；CI 两个 job 已起，待用户看 CI + A3 产物后点 merge。
  ⇒ merge 后切 `feat/c3-admin-block3`，**块 3 明天开**。

- 🧩 **块 3〈商品块〉落码（2026-10-10）**：见 `slot-registry.md §十九`。**表** ⇒ `mall_management_sql.sql:411-515`（表数 **16 → 18**）。
  **后端** ⇒ 新域 `catalog/`（6 文件）+ `admin/AdminCatalogController` + `goods/GoodsAuditDao|Service`；`GoodsService.goodsAdd` **改提审制**；
  `GoodsCacheService` 三条读路径加 `ShelfState=1 AND DeletedAt IS NULL`。**前端 4 项落 3** ⇒ 管理端 `CatalogPage.vue`（新建 + `router/index.js:452` 替换）、
  商家端 `GoodsAdd/GoodsUp`、用户端 `HomePage` chips + `GoodsSelect` 树、`endpoints/admin/notify-events` 均接。
  ⏸️ **`ProductPage.vue:226-230`/`:236-249` 未落** —— 卡「无商品→模板绑定 / `spec` 的 `Content` 格式未定义 / `after_sale` 面板」三点，**待用户裁**（`_work/c3-block3-landing-plan.md §十一`）。⇒ **该 ⏸️ 已由下方〈块 3 就地改造〉结清（2026-10-10 二次裁决）**。
  前端 `vue-cli-service build` **Build complete**（Hash `f80464f503bf002a`）。**文档** ⇒ `db-migration.md` #7（+注意 10）· `db-schema.md` 16→18（+§6.17/§6.18）· `c3-blocks-index.md:15` · 本 pack。
  ⏳ **待办**：VM 库迁移（VM `192.168.229.129` 不可达，**不静默跳过**）· 用户手验（⚠️ a3 `app.auth.enabled=false`，**手验绿 ≠ 默认 profile 绿**）· A3 复跑核实 B-2 是否仍复现。**未提交**。

- 🧩 **块 3〈商品块〉就地改造：模板表通用化 + 商品侧改「快照」（2026-10-10；~~未提交~~ ⇒ 已收口，见本段末 ✅）**：见 `slot-registry.md §19-9`、`db-migration.md` **#8**。
  **起因** = 用户 2026-10-10 当面口径：模板从「**引用**」（商品存模板 Id ⇒ 改模板联动改商品）改「**快照**」（选模板 → 填入 → **可改** → 商品存**最终文本**）；
  并令**两张模板表合一**为通用表。**就地改、不另开笔、不提交。**
  **表（`.sql:517-680`；表数 18 → 17）** ⇒ 新建通用表 **`template`**（`.sql:556-569`，`Type` varchar(96) / `Name` / `Title` / `Body` / `Enabled` / `Sort`）；
  **物理删** `goods_template` + `notification_template`（`.sql:679-680`，**规则 v2 首次破例，用户批准**，`CREATE TABLE` 行留原地由尾部 `DROP TABLE IF EXISTS` 中和以保零漂移）；
  `goods` **删** 3 个模板 Id 列（`.sql:611-630`）**加** 3 段快照文本 `Detail`/`Spec`/`AfterSale`（`mediumtext`，`.sql:588-607`）；`goods_audit` 加同 3 列（`.sql:636-655`）；
  旧 `notification_template` 行按 `Type = notification.<原 TemplateKey>` 搬迁（`.sql:657-673`）。**本机库 13 表 / VM 库 17 表**（VM 走「补块 3 + 补本块」，**非整份重导 ⇒ 业务数据零损失**）。
  **后端** ⇒ 新包 **`template/`**（5 文件：`TemplateTypes` / `TemplateDao` / `TemplateService` / `TemplateController` `/template/*` / `AdminTemplateController` `/admin/template/*`）；
  **删** 3 个 Java 文件（`CatalogController` / `GoodsTemplateDao` / `GoodsTemplateService`）+ 旧端点（`/catalog/templates`、`/admin/catalog/template/*`、`/admin/notify/template/*`）；
  `insertGoods` **8 → 11 列**、`GoodsAuditDao`/`Service`、`GoodsService`/`GoodsController.goodsAdd` 各 +3 参透传。
  **前端** ⇒ 新组件 **`TemplatePicker.vue`**（只做弹窗，触发按钮留各页）+ **6 处入口**（`GoodsAdd` ×3 + `CatalogPage` / `AccountsPage` ×2 理由）；
  **`ProductPage.vue` 三处真渲染**（Tab① 详情 / Tab② 规格 / Tab④ 售后；**净 +5 行，1248 → 1253**）；`CatalogPage` / `NotifyPage` 模板区改走新 API；`api/template.js` 新增。
  ✅ **结清上一笔的 ⏸️**：`ProductPage.vue:226-230`/`:236-249` 三点待裁**全部作废**（无绑定⇒改快照、`spec`⇒行内 `键=值`、`after_sale`⇒接真渲染并**推翻** pack `c3-block-3-goods.md:117` 的「静态真功能 N=0」裁决）；留档见 `_work/c3-block3-landing-plan.md §十一`，覆盖口径见 §十二。
  🧪 `./mvnw -B test` **246 / 0 / 0**；`vue-cli-service build` **Build complete**；`eslint` exit 0。
  **文档** ⇒ `db-migration.md` #8（+ 注意 11；#7 状态由「VM 不可达」更正为「已落两库」）· `db-schema.md` **18 → 17**（**净 0 行**=549；§6.15 标已删 / §6.17 `goods_template` 改写成 `template` / §6.18 +3 列）· `slot-registry.md §19-9` · `_work/c3-block3-landing-plan.md §十一 结清 + §十二` · `c3-blocks-index.md:15` · pack 回执。~~未提交~~ ⇒ **已提交 `5695755` / PR #84 / merge `fbf5c00`**（收口见下方 ✅）。
  ⚠️ **三件必须点明（已登记 §19-9-5，未修）**：① `CatalogPage.vue:114`/`:117` 用 `:disabled="busyId !== null"` 但文件定义的是 **`busyKey`** ⇒ 按钮**永不置灰**（**既有 bug**，只报告；行号 2026-10-10〈模板表通用化〉重指：原 112/115 行）；② `merchant.reject` / `user.ban` 两个 type **无录入面**；③ `template` 初始为空 ⇒ 6 处弹窗首开显示「暂无模板」**是预期**。
  ✅ **手验已跑并通过**（真后端 + **VM 库** + a3 profile；证据 `docs/agent/evidence/c3-block3-snapshot/hand-verify.txt`，探针 `scripts/probes/c3b3-snapshot-verify.sh`）：建模板 `goods.detail` Body=`AAA` → 商家 `/goods/goodsAdd` 提审（`Detail` **手改**成 `AAA-SHOP-EDITED`）⇒ **`goods` 零新行** + `goods_audit(pending)` 1 行 → 管理员通过 ⇒ **`goods` +1 行** + `Status=approved` + `GoodsId` 回填 → **把模板改成 `BBB`** → 重查该商品 `Detail` **仍是 `AAA-SHOP-EDITED`** ⇒ **快照语义成立**；结构断言同过（`%TemplateId%` 0 行 / 只剩 `template`）；跑完**已删回**探针造的行（VM `goods` 回到基线 5 行）。
  🐞 **手验当场抓出并修掉 1 个真缺陷**（见 `slot-registry.md §19-9-7`）：`GoodsAuditDao.insert`（`.../goods/dao/GoodsAuditDao.java:67`）的 `VALUES(…)` 值槽**多一个** —— 列 **14** + 字面量 `'pending'`，却写了 **14 个 `?`** 而只传 **13 个值** ⇒ 提审恒 `{"success":false,"msg":"商品提交失败"}`（后端 `SQLException: No value specified for parameter 14`）。**本笔引入**（加 `Detail`/`Spec`/`AfterSale` 三列时多打一个 `?`）；删掉 `'pending'` 前多余那个 `?` ⇒ 13 对 13。**单行、净 0 行。** ⚠️ **`mvn test` 246 绿 + `build` 通过 + `eslint` 0 三绿全过仍漏了它** —— Mockito 切片 `GoodsWriteTxAtomicityTest` 与 `@WebMvcTest` 的 `AbstractSmokeTest` **都不打真库**，覆盖不到「`?` 数 ≠ 传参数」。⇒ 已登记待办：DAO 层要一条**打真库**的冒烟（或「`?` 计数 == `.params()` 计数」的静态检查）。🐞 **第 2 个真缺陷：事后从后端进程日志抓到**（`/tmp/boot-a3b.log` 4 条 `action=template.create … targetId=null`；`admin_audit.TargetId` 是 **NOT NULL**，`mall_management_sql.sql:282`）—— `template.create` / `category.create` 两处「新增类」动作把 `TargetId` 也传了 `null` ⇒ **创建类留痕 100% 丢失**（fail-open 吞异常、只记 ERROR；三绿与 A3 都拦不住）；全仓 18 个 `auditService.record` 调用点逐个核过，只有这 2 处传 `null`。**已修**（2026-10-10 用户批「就地补业务标识串」：`Type:Name` / `ParentId:GoodType`，两处单行、净 0 行）；**未重新手验**（需重启后端），见 `slot-registry.md §19-9-8`。
  ✅ **块 3 收口（2026-10-10）**：commit **`5695755`** → push → **PR #84** → 用户 merge，**合并提交 `fbf5c00`**（远端 `feat/c3-admin-block3` 已删）。CI 三 job 全绿；**A3 一律按产物读**（`concurrency` job 是 `continue-on-error`，颜色无信息量）：`exitCode=0` / `gates.count=30` / `gates.failed=0` / `assertions 31`（0 条 `ok:false`）；飞前校验 **P3 = PASS 表数=17**（`KNOWN_TABLES` 7→6 生效）、**P4 = PASS** 种子 `order=6 goods=5 login=5 user=5 friend=4`、P0–P6 PASS / P7 SKIP。⏳ **遗留（不静默）**：`goodsUp` 改不了已上架商品的详情/规格/售后（**本块范围外已知缺口**）· **TD-20**（静默失败路径需专门测试）挂到**块 8 部署前**一并处理。⚠️ 提醒：`application-a3.properties:98` 的 `app.auth.enabled=false` —— **手验绿 ≠ 默认 profile 绿**（本笔手验即在闸关下跑的）。

**路线**：C3 现行 = **8 块**，唯一权威 **`c3-blocks-index.md`**；§七 那份「10 批 + 按端切」**已作废**
（见 `c3-modules.md` §十）。⚠️ 购物车的归属**已统一**为「**属批 4 订单块，随该块一起做**」
（原「C3 后第一笔」口径已废；见 §5-AG (8) 的按语）。
### 7-B4. C3 块 4〈订单块〉（2026-10-10；⚠️ **落码 + 构建/测试绿 + 两库已落；手验未做**；**未提交**）

> 本节**追加在文件尾**（不在 §7 中段插行）—— 本文件有 4 处外部 `文件:行号` 引用，中段插行会顶漂。
> 上一条（§7 末段那个 `**路线**` 块）仍是全文的收口说明，本节接在它之后，只是**追加登记**。

**用户口径（逐字要点）**：块 3 已 merge（**PR #84 / merge `fbf5c00`**）⇒ 正式收口；进**块 4「订单块」**，**三端一起设计一起落**；**继续攒、不提交**。
**方案**：商家端两块 = **订单中心（我卖的）** + **购物车块（我买的）**；用户端一块 = 购物车块。
**购物车块 = 容器**，内含 `[购物车]` `[我的订单]` 两个 tab；**一个路由 + `?tab=`**（`/cart?tab=cart` / `/cart?tab=orders`），顶栏一个入口。
**职责**：订单中心 = 别人在我这买的单（发货 / 物流）；购物车块 = 我自己买的单（未成交 + 已成交）。
**撤回**：用户自己上一条「我买的放用户中心」**作废** —— 购物车块内 tab 更简单直接。
**A 档回归 3 个**：A1 购物车载体（新表 `cart`）· A2 订单数量（`order` 加 `Quantity`）· A3 介入动作边界（只沿既有三条 CAS 边）+ 归属校验（连带）。前序裁决：**付款 / 收货全归「我的订单」tab** ⇒ cart tab = 真购物车。

**表（`.sql:681-738`；表数 17 → 18）** ⇒ 新表 **`cart`**（`:708-720`，8 列；`uk_cart_customer_product (CustomerId, ProductCode)` ⇒ 同商品重复加购**合并数量**；`PriceSnapshot` **double** 对齐 `goods.Price`、**刻意不照** `order.Price` 的 float；**不在**文件头 `DROP TABLE IF EXISTS` 基线里 ⇒ 重导**不清购物车**）；同笔 `ALTER … MODIFY COLUMN PriceSnapshot`（`:722-728`，**只改注释不改列**）；`order` 加 `Quantity`（`:730-738`，`int NOT NULL DEFAULT 1`，守卫幂等 ⇒ 存量 6 行回填 1、**不改行数** ⇒ P4 不受影响）。**本机库 14 表 / VM 库 18 表**（VM `order` 仍 6 行、`cart` 0 行）。⇒ 同笔改 `ConcurrencyRunner` P3 门禁：IN 清单加 `'cart'`、`KNOWN_TABLES` **6 → 7**、期望 `11+7=18`（现落 **`:2662`**）。
**后端** ⇒ 新包 **`cart/`**（`CartController` 121 行 / 5 端点 · `CartService` 299 · `CartDao` 153）+ 新包 **`admin/` 订单治理三件**（`AdminOrderController` 95 / `AdminOrderService` 230 / `AdminOrderDao` 192）；`OrderController` **176 → 267**（`orderAdd` 加 `Quantity` + 归属校验；三条 CAS 端点加归属校验）、`OrderService` +106（**未加 `@Transactional`**、**未碰 `OrderStateMachine`**）、`OrderDao` +37、`AuditActions` +`ORDER_INTERVENE`；测试新增 `CartSmokeTest` / `AdminOrderSmokeTest` + `AbstractSmokeTest` 的 `@Import` 补新 `@Service`。⚠️ 2026-10-10 手验补丁：`/admin/order/list` 的 `truncated` 改判 `rows.size() >= 生效上限`（原 `total > rows.size()` 会在**筛 status 后谎报「已截断」**——`total` 是全表量、不随筛）。
**前端** ⇒ 新 `views/OrderMine.vue`（361 行，「我的订单」tab）· 新 `views/admin/OrderPage.vue`（873 行，订单全量 + 异常监控两 tab + 行级介入；`flash` 横幅 + 两击确认代 `alert`/`confirm`）· 新 `api/order.js`（233 行）；`ShoppingCart.vue` 改成真购物车（付款收货迁走）· `merchant/OrderPane.vue` 升格为**两壳共用的容器** · `router/index.js` 的 `cart` 子路由 `component` 换 `OrderPane` 并**删掉 `props:{showSwitch:false}`** + `meta.tab` 由 `'order'` 改 `'cart'` · `config/nav.js` **同行追加**商家「购物车」入口（**块 4 前购物车块没有顶栏入口**）· `ProductPage.vue` 两入口**彻底分家**（`'cart'` 支根本不碰 `order` 表、`'buy'` 支 `PayIf` **恒 1**）。
**契约** ⇒ `/cart/*` 5 个 + `/admin/order/*` 4 个（**TD-18：不得加角色闸**；只有 `/intervene` 挂审计）；`Result` 是 `HashMap` 子类、`Result.of` **逐键平铺** ⇒ `/admin/order/*` **没有 `data` 包裹层**；`/cart/checkout` 逐行走既有 `orderAdd`（锁 + 三道闸不绕）、出生态恒 `(0,0,0)`、**先落订单后清行**、**部分失败不回滚**。逐条见 `api-contract.md 附录 C`。

🧪 `vue-cli-service build` **Build complete** · `./mvnw -q test` **exit 0** · `eslint --ext .js,.vue src/` **零报错**（修掉 `ProductPage.vue` 两处 `no-extra-boolean-cast`）。
**文档** ⇒ `db-migration.md` **#9 + 注意 12** · `db-schema.md` **17 → 18**（⚠️ **不中段插行**：该文件 44 处外部引用会被顶漂 ⇒ `cart`/`Quantity`/第 4 个 UNIQUE 一律登记在**文件末新建 §11**，§1/§6/§7/§10 只做**同行改 + 指向 §11**，净零行）· `slot-registry.md` **§二十** · `api-contract.md` **附录 C**（正文 `O:` 一栏按 15-5「只登记、不修」）· `c3-blocks-index.md:16` 状态 · pack `c3-block-4-order.md` 加「现状坐标为块 4 前」横幅。
⚠️ **零漂移**：**本笔打破的引用** —— ① `OrderCenter.vue:131` 引的 `OrderDao.java:138 / :141`（HEAD 时准确）因两条 SELECT 下移 8 行 ⇒ **同行改**为 `:146 / :149`；② 手验补丁改了 `AdminOrderService` / `AdminOrderDao` / `OrderPage.vue` 的行数 ⇒ 把 `slot-registry.md` / `progress.md` 里的 `223 / 184 / 869 行` 同行改为 `230 / 192 / 873`，并把 `api-contract.md` 的 `AdminOrderService.java:186-190` 重指为 `:183-187`。其余（`ConcurrencyRunner:2658`、`OrderDao:133`、`api-contract.md:196` 的 `ProductPage.vue:369`）**在 HEAD 即已陈旧** ⇒ 按 §十五 15-5 **只登记、不修**。`refs-scan` token 集合回归：丢失对 21 对，**referrer 全部是本笔重写/手改的文件**，无第三方文件丢引用。
✅ **手工验收已跑一轮（2026-10-10，回库核对）**：购物车加/改量/移除/结算 · 两 tab 路由 · 三处详情页入口 · 管理端订单治理四端点 · 介入后 `notification` 双投递**回库核对**（不看接口 `success`，`notifyBoth` 是 fail-soft）—— 全绿；**唯一抓到并已修**的是 `/admin/order/list` 的 `truncated` **谎报**（原按全表 `total` 判 ⇒ 筛 status 后误报「已截断」）。**仍未做**：深链 / 前退后退未验；`OrderCenter` 无「数量」列、`showSwitch`/`orderTab`/`@toggle` 三 prop 全线退役、**强制退款无资金态**、`cart` 物理 DELETE 规则-v2 破例、CAS 归属校验 **fail-open**（a3 闸关下测不出）、强制发货/完成**各让被推进的对端收两条通知** —— 均已登记 `tech-debt.md`。
⚠️ **手验前必提醒**：`application-a3.properties:98` 的 **`app.auth.enabled=false`** ⇒ a3 闸关，**手验绿 ≠ 默认 profile 绿**。
⚠️ **A3 读法**：`concurrency` job 是 `continue-on-error` ⇒ 按产物读 `gates.count`（2026-10-07 实测 30/0），别记死。

### 7-B4P. C3 块 4 补丁〈模拟支付 / 物流全流程〉（2026-10-10；**落码 + 测试/构建绿 + 两库已落；未提交**）

> 本节**追加在文件尾**。上一条（§7-B4）仍是块 4 的收口说明；本节只是**追加登记**补丁这一笔。

**用户口径（逐字要点）**：块 4 把「付款 / 发货」做成**纯状态位**（只翻 `PayIf` / `DeliveryIf`）⇒ 用户要求升格为**模拟全流程**：
付款要记**交易号 / 支付方式 / 付款时刻**，发货要记**快递公司 / 运单号 / 发货时刻 / 收货时刻**，并给买家一条**物流轨迹时间线**。
批前四点确认（用户 2026-10-10）：① 列数 **7**（3 支付 + 4 物流；`ReceivedAt` 归在物流组里 —— 「8 列」是那份列表的**计数笔误**）· ② **物流签收 ≠ 订单收货**（调度器不碰状态位 ⇒ 必然结果，**接受**，前端分两词 + 登记 TD-28）· ③ 新增 **A5 `/order/orderDetail`**（买家看物流详情必须要有）· ④ 其余全采信（**不动 `OrderStateMachine`** / 三端点加参用**重载**（`intervene` 零改）/ 调度器**只 INSERT 轨迹**（`NOT EXISTS` 幂等）/ A3 门禁 **7→8 · 18→19**）。

**表（`.sql:740-815`；表数 18 → 19）** ⇒ `order` **+7 列**（守卫式 `ADD COLUMN`，全 `DEFAULT NULL`）：`TradeNo varchar(64)` · `PaymentMethod varchar(32)` · `PaidAt datetime` · `ExpressCompany varchar(64)` · `TrackingNo varchar(64)` · `ShippedAt datetime` · `ReceivedAt datetime`；新表 **`order_shipping_track`**（`:805-815`，7 列：`Id` / `OrderId` / `Status` / `Description` / `TrackedAt datetime(3)` / `created_at` / `updated_at`；`KEY idx_order_shipping_track_order (OrderId,TrackedAt)`；**不建 FK**）。**本机库 15 表 / VM 库 19 表**（VM `order` **10 行** —— 与 `db-migration.md` #9 记的 6 行不同，是**先前开发期累积**，与本次 `ADD COLUMN` **无关**）。索引 **18 → 19**、`CREATE TABLE` **20 → 21**、UNIQUE 仍 4、`DROP` 基线**不加**新表（同 `cart` 口径）。幂等：本机 + VM 各连跑 2 次 **EXIT=0**。⇒ 同笔改 `ConcurrencyRunner` P3 门禁：IN 清单加 `'order_shipping_track'`、`KNOWN_TABLES` **7 → 8**、期望 **`11+8=19`**（**净零行**手法，P3 段仍 `:2644-2667`）。

**后端** ⇒ 新 **`ShippingTrackDao.java`**（`exists(orderId,status)` / `insert(...)`）；新包 **`order/job/`** ⇒ **`ShippingTrackAdvanceJob.java`**（`@Component` + `@ConditionalOnProperty(mall.order.shipping.track.enabled, matchIfMissing=true)`；`@Scheduled(fixedDelay)` 按 `ShippedAt` + `transit-ms=60000` / `delivery-ms=120000` / `signed-ms=240000` 补 `in_transit` / `out_for_delivery` / `delivered` 三条轨迹 —— **只 INSERT、绝不 UPDATE `order` 状态位**（用户批准的红线））；`OrderDao.selectShippableForTrack()`（尾追加，**现有行号零漂**）；`OrderService` 加 **`detailUpdateSql(action)` + `detailColumns(action)`**（**第二条 UPDATE**，与 CAS 的 golden-copy `updateSql` **同在订单锁内**）+ 三个 CAS 方法 **1 参重载**（`intervene` 零改）；`OrderController` 三端点加可选参 + 新增 **`orderDetail`**（A5）+ `isOwner` 归属校验。
⚠️ **golden copy 仍钉死**：`OrderStateMachineTest.updateSqlIsByteForByteTheLegacyLiteral` **未动**，`OrderStateMachine.updateSql(action)` **字节不变**。
⚠️ **隐藏的调度耦合（写进 Job javadoc）**：`@EnableScheduling` **不在**主类，在 `MerchantDowngradeSchedulingConfig`（自身 `@ConditionalOnProperty(mall.downgrade.job.enabled, matchIfMissing=true)`）⇒ **a3 里调度器根本不存在**（不是「被开关关掉」）；发货任务的 `mall.order.shipping.track.enabled` 是**第二道独立闸** ⇒ **不得**用「关降级任务」的方式关发货任务。`application-a3.properties` 尾追加 11 行、`application-smoke.properties` 尾追加 8 行，均把该开关置 `false`。

**前端** ⇒ `api/endpoints.js` **同行追加** `orderDetail`（**零漂**，沿用 `updateProfile` 先例）；`api/order.js`：`orderPay(orderId, paymentMethod)` / `orderDeliver(orderId, expressCompany, trackingNo)`（可选参**有值才带**，后向兼容）+ 新 `orderDetail(orderId)`；`OrderMine.vue`：【付款】改开**收银台弹窗**（`支付宝 / 微信支付 / 银行卡` 三选一 ⇒ `confirmPay`；成功提示带 `tradeNo`）+ 新增【物流】按钮（`DeliveryIf===1`）⇒ **物流轨迹弹窗**（有序列表）；`OrderCenter.vue`：【发货】改开**发货弹窗**（快递公司 + 运单号，**三道守卫逐字保留**：`DeliveryIf===1` / `ReceiveIf===1` / `PayIf!==1`；样式用 `dlg-` 前缀避与既有 `.fld`/`.act`/`.send` 撞名）；`admin/OrderPage.vue`：详情区**加第二组 `.a-kv`**（支付方式 / 交易号 / 付款时间 / 快递公司 / 运单号 / 发货时间 / 收货时间，空值走 `dash()`/`toLocal()` 出 `—`）+ **物流轨迹 `<h3>` + `<ol class="a-tl">`**（与既有「状态流转」时间线**故意分列不合**）。

🧪 `./mvnw test` **258 / 0 fail / 0 err / 26 skipped / BUILD SUCCESS**（`OrderStateMachineTest` 21 → **23**）· `npm run build` **DONE** · 块 4 pack 校验 `check-context-packs.sh docs/agent/context-packs/c3-block-4-order.md` ⇒ **引用 OK 259 · INFO 1 · WARN 0 · FAIL 0**。
**文档** ⇒ `db-migration.md` **#10 + 注意 13** · `db-schema.md` **18 → 19**（§6 表头 + §6.8 `order` 注 + 尾追加 **§12**）· `slot-registry.md` **§20-9** · `api-contract.md` **附录 D** · `tech-debt.md` **TD-28…TD-31** · `c3-blocks-index.md:16`。
⚠️ **本笔的零漂移口径（按用户 2026-10-10 新规降级）**：**不再**全仓逐条重指 ⇒ 只核**自己动过的引用**（块 4 pack 已就地核，结果见上）；本笔造成的**外部**引用偏移全部记账在 **`_work/drift-ledger-block4patch.md`**，留到**块 4 收口**或**块 7 之后的锚点改造**一次性处理（若那时改成 `#锚点`，该表整张作废）。
⚠️ **手验前必提醒**：`application-a3.properties:98` 的 **`app.auth.enabled=false`** ⇒ a3 闸关，**手验绿 ≠ 默认 profile 绿**；**归属校验（含 A5）在 a3 下测不出**。
⚠️ **未做**：补丁的**手工验收未跑**（块 4 那一轮手验在补丁之前）。
