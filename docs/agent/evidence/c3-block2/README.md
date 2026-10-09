# c3-block2 证据目录

C3 块 2〈用户块〉的验收证据。**每条结论旁边标了它属于哪一档**（下同）：

- **【实测】** —— 本次在本机跑出来的原始产物（状态码 / 字节 / DOM 读数 / 截图），产物就在本目录里；
- **【推断】** —— 由实测**推**出来的、没有直接产物的解释，**必须**当成待确认项读。

所有探针都可复跑（脚本在 `scripts/probes/`，每个文件头写了判据与用法）。复跑命令一律在**仓库根**执行。

---

## `db/` —— 建表与库内状态

| 文件 | 是什么 |
|---|---|
| `migration.sql` | 本笔对 VM 库执行的**正向 SQL**（照抄，非生成物） |
| `apply.out` | 执行时的标准输出 |
| `state-before.txt` / `state-after.txt` | 执行前后对目标对象的 `SHOW`/`SELECT` 快照 |

**判据**：`state-after` 里新表存在、列与索引与 `migration.sql` 一致；`state-before` 里它们不存在。
**档**：实测。

---

## `login-fig/` —— 登录页「品牌大图」空白（UI 回归，与块 2 无关）

**复跑**：`node scripts/probes/probe-login-fig.mjs`（只需 dev server 8080；本探针**不 mock** 8088）

| 文件 | 是什么 |
|---|---|
| `run.out` | 原始读数：`fetch` 状态码、`performance` 资源条目、两态 `.brand-fig` 的 rect 与计算样式 |
| `login-1440x900.png` / `register-1440x900.png` | 两态全屏截图 |
| `login-1280x640.png` / `register-1280x640.png` | 短视口两态截图 |

**结论（实测）**

1. `.brand-fig` 的盒子**没有塌**：1440×900 下两态都是 **440×248**，短视口 1280×640 下是 **440×137**，
   `flex:0 1 auto`、`min-height:0px`、`aspect-ratio:16/9`。⇒ **不是布局/渲染条件回归**，
   也**不是** B1 那条 `min-height:0` 把它压没了。
2. `/img/**` 现在**通**：页面内 `fetch` 两张图都是 `200 image/jpeg`；8088 直连亦然
   （`Mall-Section-1.jpg` 45525B、`Mall-Section-2.jpg` 60353B、`default_user.png` 175591B）。
3. `login-1440x900.png` 里品牌大图**画出来了**（不再是灰白块）。

**根因（实测 + 一处推断）**

- 【实测】`application-a3.properties:50` 把上传目录外置到 `${java.io.tmpdir}/a3-uploads/`，
  而 `%TEMP%\a3-uploads\` 是**空的**；修前 `ImgPath.addResourceHandlers` 在 `uploadPath != null` 时
  **只**注册这一个位置 ⇒ `/img/**` 整片 404，应用**自带**的图（`Mall-Section-*.jpg`、
  `default_user.png`）一起被赔进去。登录页那张 `.brand-fig` 于是只剩
  `background-color: var(--img)`（`#e5e9ec`）兜底 —— 看着就是「灰白空块」。
- 【实测】修法 = `ImgPath` 注册**两个有序位置** `{ 上传目录, classpath:/static/img/ }`
  （`ResourceHttpRequestHandler` 按顺序取首个命中 ⇒ 同名时上传目录优先）。
  佐证：`target/classes/.../ImgPath.class`（21:52:49）晚于源码（21:51:22），
  且 8088 实际吐出的 `Mall-Section-1.jpg` 与 `src/main/resources/static/img/Mall-Section-1.jpg`
  **md5 同为 `ee9f2c351e51451ca7f3c774bc834d51`**，而上传目录为空
  ⇒ 跑着的那个进程（IDEA 启动，profile=a3）**已经带上了这个修**。
- 【推断】用户报告里「**注册** tab 图片区正常」：同一处修的同一批资源，注册态用的是
  `Mall-Section-1.jpg`。**推断**为浏览器缓存里那份旧响应，**未**取到直接证据。

**⚠️ 待用户确认的一处**：本笔复现不出「修前全 404」那一刻的进程状态 ——
现在量到的 8088 已经是**修后**的。若当时看到的空白另有其因，这条结论要重开。

---

## `register-deeplink/` —— 注册直达 `#/register`

**复跑**：`node scripts/probes/probe-register-deeplink.mjs`（只需 8080，不 mock 8088）

| 文件 | 是什么 |
|---|---|
| `run.out` | 四条判定的逐条结果 |
| `1-login.png` / `2-register.png` / `3-after-toggle.png` | 三个态的截图 |

**结论（实测，4/4 通过）**：`#/login` 是登录面、`#/register` **直达**注册面、
两态互切时 URL 跟着走（`toggle()` 里那条 `replace`）。
③ 是「tab ↔ URL 绑回同一处真源」的唯一证据 —— 少了它，刷新会把用户从注册面弹回登录面，
而 ①② 都还是绿的。

---

## `accounts-apply/` —— 块 2 两个新鉴权页的渲染

**复跑**：`node scripts/probes/probe-accounts-apply.mjs`（只需 8080；**mock** 8088）

| 文件 | 是什么 |
|---|---|
| `run.out` | 17 项判定逐条结果 |
| `1-admin-accounts*.png` | 管理端 `/admin/business/accounts` 视口 / 全页 |
| `2-customer-apply*.png` | 用户端 `/customer/user/apply` 视口 / 全页 |

**结论（实测，17/17 通过）**：管理端四段（入驻审核 / 用户治理 / 降级控制 / 管理员共享收件箱）
都渲染出内容，且**真的**按预期端点发了请求（`/admin/accounts/applications`、`/admin/accounts/users`、
`/notification/list`）；用户端表单 + 「我的申请」都渲染，**真的**打了 `/merchant/myApplications`。

**⚠️ 这个探针证明的边界**：它只验**前端这一侧**（路由挂对没有、身份判据放不放行、
发的是不是预期端点、渲染有没有崩）。**不**证明后端行为 —— 那半边由 `mvn test` 与真机验收覆盖。
之所以要 mock：用户 IDEA 里跑着的 8088 **早于**本笔（没有
`AdminAccountsController` / `MerchantApplicationController` ⇒ 真打过去是 404），
而那条进程**不得杀掉**。

**⑤ 是基线差分，不是「零 warn」**：`#/login` 上本来就有**三条** vue-router warn
（`customer` / `customerUser` / `merchant` 三个**命名父记录**各带一个**无名空路径子记录**），
它们在 `createRouter` 建匹配表时就发、**与在哪个页面无关**。判「零 warn」等于把既有噪音算成本笔的账。
故探针先取基线集合，再断言两页的集合与之**相等**。
