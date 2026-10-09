# context-pack: C3 块 2 —— 用户块（三端）

> 索引：`docs/agent/c3-blocks-index.md`。**只精读下文列出的区间，不要整份读任何文件**（除注明「整份」的）。
> ⚠️ **状态（2026-10-09 晚）：A 档 5 项已裁决，块已落码 + 验收完成（未提交）。** 下文标「⚠️ 待裁」的条目**均已按裁决落地**；
> ⚠️ **本 pack 的下文行号已随块 2 的改动重指过一次 ⇒ 均为「当前」行号**（重指清单见 `slot-registry.md §十八 18-6`）。现状 / 样板 / 坑**全部实测过**。

## 一句话定位

用户 / 商家的**全生命周期**（`docs/agent/c3-modules.md:47-54` 的「用户与商家治理」）：
**注册 → 资料 → 入驻申请 → 审核 → 封禁 / 解封**。
每一步的写操作都**走块 1 的两条管子**：审计留痕（`admin_audit`）+ 通知当事人（`notification`）。

它自己**不新建基建** —— 本块是块 1 那两个原语的**第一个真实消费者**
（块 1 只留了原语 + 一个样板钩子，见其 pack「要读 → 样板挂钩」）。

## 依赖块

**块 1（基础设施）**。本块对它的用法是**照抄**、不是扩展：

- 每个写端点照 `GoodsController.goodsUp` 的**四步挂钩**（`Mall-management-server/src/main/java/com/minke/mallmanagement/goods/controller/GoodsController.java:207-228`）；
- 每次「审核结果 / 封禁 / 解封」发一条通知，code 用 `NotificationEvents` 里**已有**的 6 条（见下）。

⚠️ 本块**不改**块 1 的任何接口签名；唯一可能要动的是 `NotificationEvents` **加一条**常量（待裁 4）。

## 要读

### 设计意图（读区间）

- `docs/agent/c3-modules.md:47-54` —— §1 的四视图 + 四个管理员动作。⚠️ 本块只做**前两行**（用户列表/详情 → 封禁/解封；商家列表/资质 → 入驻审核）；「举报工单」归块 6，「标签」**本块不做**。
- `docs/agent/c3-modules.md:241` —— §八「**不做多商家入驻（新功能，先占位）**」。⚠️ **与本节任务冲突，已由用户裁决**（2026-10-09「按用户要求做，不按稿做」）：本块**做**「一个账号从客户升为商家」的入驻审核；**不做**的是「同一账号开多家店」。
- `docs/agent/c3-modules.md:238` / `:242` —— 另两条边界：不做购物车拆分（块 4）、**不做细粒度权限（单一管理员角色）** —— 后者是本块「admin 端点不加角色闸」的依据（同 TD-18）。
- `docs/agent/c3-blocks-index.md:14` —— 块 2 在 8 块路线里的位置与依赖。
- `docs/agent/progress.md:940` / `:1474` —— 「**为什么当年不做 `#/register`**」与「**`ANON_RIGHT` 为什么不拆两条**」的原始裁决。⚠️ 本块要做前者 ⇒ **必须先读那两条理由**（一条是「凭空造路由 = 凭空造约定」，一条是「两个链接指同一处 = 假承诺」）。

### 块 1 已落的基建 —— 本块要用的现成接口

- `.../notification/service/NotificationService.java:114-125` —— `send(batchId, toId, toType, eventType, title, content, targetType, targetId)：boolean`。**fail-soft 原语**：永不抛、不加事务、日志标记 `NOTIFY`；`batchId` 单发也要给。
- `.../notification/NotificationEvents.java:33-120` —— **24 条** code 常量（块 1 的 21 条 + 块 2 新增 3 条）。**块 2 要的 6 条全在**：
  `user.audit.passed`（`:62`）/ `user.audit.rejected`（`:64`）/ `user.banned`（`:67`）/ `user.unbanned`（`:70`）/
  `merchant.audit.passed`（`:73`）/ `merchant.audit.rejected`（`:76`）。
  ⚠️ 已核实：`send` **不校验白名单**（类注释 `:19-24`）⇒ 要新 code 时**只加常量**，不动表、不动 `send`。
- `.../admin/service/AuditService.java:73-86` —— `record(AuthSession.Identity actor, String action, String targetType, String targetId, Object before, Object after)：void`，**fail-open**（catch 一切、只打 `ERROR ... AUDIT`）。`actor` 用 `AuthSession.identify(request)` 取 —— **认不出身份 = `ActorId` / `ActorRole` 双 NULL**。
- `.../admin/AuditActions.java:27-79` —— 动作 tag 常量，**块 1 时只有 2 条**（`:35` / `:51`），**块 2 加到 9 条**（新增 `:56-77`）。⚠️ 类注释 `:19-26` 明写「其余写端点**随各自那一笔挂**」⇒ **本块每个写端点都在这里加一条**（见「要新建」）。
- `.../shared/web/IdentityGuard.java:67-76` —— `requireSelfOrAdmin(request, paramId)`：自己 / 管理员 / **解析不出身份** → 放行，否则 403。⚠️ 必须在方法体**第一行**调（`:60`）。
- `.../shared/web/Result.java:64-133` —— `of(...)` / `ok(...)` / `fail(...)`；**禁 `putAll`**（`ResultByteEquivalenceTest` 钉着）。
- `.../shared/web/GlobalExceptionHandler.java:83` —— ⚠️ `@RestControllerAdvice(assignableTypes = {MessageController, GoodsController})` —— **新 Controller 不在白名单里** ⇒ 新端点**一个参数约束都不要加**（`@NotBlank` 等会逃逸成 400 空体；类级 `@Validated` 会撞 CGLIB 断言族）。

### 现状：用户 / 登录域（读它就是读**现成的形状**）

- `mall_management_sql.sql:89-95` —— `login`（`UserName` PK / `Password` / `Role` int / `Id` 业务号）。**无任何状态列**；种子 `:99`。
- `mall_management_sql.sql:182-193` —— `user` 9 列（`username` PK / `usertype` **中文** 客户·商家·管理员 / `customerid` / `merchantid` / `gender` / `age` / `email` / `selfintroduction` / `address`）。**无审核态、无封禁态**；种子 `:197`。
- `mall_management_sql.sql:105-110` —— `merchantimage`（`UserName` PK / `MerchantId` / `image`）；客户侧孪生表 `customerimage`。
- `.../user/controller/UserController.java:102-143` —— `loginSelect`（`:102`，返回**裸 `int`** 1/2/3/**0 = 失败**；成功且取到业务号时建会话）、`loginAdd`（`:127`，返回**裸 `String`** `"0"` / `"添加成功！"`）。⚠️ **`loginSelect` 是封禁唯一的必经闸**（待裁 3）。
- `.../user/controller/UserController.java:149-175` —— `getUserInfo`（`:149`，失败 = 200 空体）、`updateAvatar`（`:158`）、`updateUserInfo`（`:167`，**只认 email / address / selfintroduction 三参数**）；`:219` = `/user/me`（会话身份的读路径）。
- `.../user/service/UserService.java:136-152` —— `loginSelect(username, password)`：`selectRoleByCredentials` → int，**catch 后一律 0**。
- `.../user/service/UserService.java:188-203` —— `businessId(username, role)`：**role 1/3 → `customerid`；2 → `merchantid`**（⇒ **管理员也有 `customerid`**，这条决定了「通知管理员」的收件号怎么取）。
- `.../user/service/UserService.java:240-276` —— `@Transactional loginAdd(...)`：查重 → 按 `usertype` 写三表 → `evictPublic`。⚠️ **TD-15**：`usertype` 非法值时**仍返回「添加成功！」但不插任何行**（`docs/agent/tech-debt.md:190-201`）—— 本块若碰它，先读那条。
- `.../user/service/UserService.java:330-360` —— `profileOf(username)`：`selectUserByUsername`（**全仓唯一的 `SELECT *`**，`UserDao.java:140-143`）+ 按 **usertype（中文）** 选头像表；⚠️ 头像 0 行 / 异常 ⇒ **只跳过 `image` 键**（`UserService.java:352-355`），**不抛、不崩**。
- `.../user/dao/UserDao.java:95-128` —— 凭据 / 角色查（`:95` 凭据、`:119` 注册查重、`:125` by 用户名取 Role）。
- `.../user/dao/UserDao.java:170-197` —— 注册写口（`:170` / `:178` 建 user 行、`:186` / `:193` 建头像行）。
- `.../user/dao/UserDao.java:211-214` / `:238-241` —— `insertLoginMerchant` / `updateUserProfile`：**本块写口的范式**（`JdbcClient` + `mall_management_sql.` 前缀）。
- `.../user/service/UserCacheService.java:80-88` / `:130-133` —— `publicInfo`（`@Cacheable("userPublic")`，SQL 是 `WHERE customerid=? OR merchantid=?`）/ `evictPublic`（**唯一失效入口**）。⚠️ **改 `user` 行的写端点收尾必须 `evictPublic`**，否则 `/user/me` 读的是旧行（含负缓存）。
- `agent.md:100-108` —— 数据库管理规则 7 / 8（新增字段三条件、新增表四条件）。
- `docs/agent/tech-debt.md:231-240`（**TD-18**）—— 管理员**铸不出 token** ⇒ 管理端不加角色闸、接缝与 `AdminAuditController` 绑定；`docs/agent/tech-debt.md:242-251`（**TD-19**）—— 通知接收侧不校验 `toId × toType`（**无害空集**，登记不修）。

### 现状：管理端「治理 / 审核」面 —— **全是占位，零业务**

- `.../src/router/index.js:451` —— `/admin/business/accounts`（「用户与商家治理」）今天 `component: AdminStub` —— **本块的替换点**（照 `:456` notify / `:463` audit 的**同行替换**做法 ⇒ 零漂移）。
- `.../src/router/index.js:434` —— `AdminStub` 定义；`:448-463` —— admin 壳的 children（11 条占位 + 2 条真页）。
- `.../src/config/admin-nav.js:37` —— 侧边栏项 `accounts`（`to: '/admin/business/accounts'`）。**入口已在** ⇒ ⚠️ `admin-nav.js` **本块不动**（改 key/to 要三处同改）。
- `.../src/views/admin/StubPage.vue:1-46` —— 占位组件（`:5` 自述「不写库，也不建业务端点」；`:44` 说明为何**故意零请求**）。

### 样板（照抄这两个真页，别自创风格）

- `.../src/views/admin/NotifyPage.vue:335-377` —— `doSend()`：`try → if(!data.success) 记 msg → catch 记网络错误 → finally`；回执模板 `:99-105`（`.n-sent`，`ok` / `bad` 两态）。⇒ **本块的「提交 / 通过 / 驳回」按钮照它**。
- `.../src/views/admin/AuditPage.vue:301-329` —— `load()`：错误走 `err.value = ...HTTP ${status}`；错误条模板 `:72-75`。⇒ **本块的列表取数照它**。
- `.../src/views/admin/AuditPage.vue:82-141` —— 事件流 + 展开详情；`NotifyPage.vue:146-168` —— 按批归组的卡片列表。⇒ **「申请列表 / 用户列表」照它**。
- `.../src/views/LoginPage.vue:359-390` —— `register()`：五字段手判 → `http.post(loginAdd, URLSearchParams)` → 按**返回字符串**分支（`添加成功！` / `0`）。

### 表 / 前端入口

- `.../src/config/nav.js:21-34`（`CUSTOMER_NAV`）/ `:36-48`（`MERCHANT_NAV`）—— **本块不动**；`:63` —— `ANON_RIGHT`（**只有一条** `登录 / 注册` → `/login`）。
- `.../src/router/index.js:53` —— `/login`（`meta.public`）；`:55` / `:86` —— 两壳壳记录（customer = `roles:['1','3']`、merchant = `['2','3']`）；`:68-74` —— 用户中心 `meta.subMenu`；`:329-334` —— 第二条守卫（**角色边界 = `meta.roles`**）；`:504-505` —— 文件尾两条 `addRoute`。
  ⚠️ **新增路由一律追加在文件尾**：全仓引本文件的**最大被引行是 `:378`**。
- `.../src/views/LoginPage.vue:161-223`（注册表单，`:216` 提交按钮）/ `:283-287`（`toggle()` 翻 `isSlide` = 登录↔注册态）/ `:289-330`（`login()`：`loginSelect` → `/user/me` → 按 role 分支）。
- `.../src/views/UserInfoPage.vue:232` / `:236` / `:241` —— **仅有的三个可改字段**；`:348`（读 `/user/me`）/ `:401`（`updateUserInfo`）；`:55` / `:138` —— 商家 / 客户两版模板，**分支判据是 `customerid` / `merchantid`**（⚠️ 见「约束」5）。
- `.../src/api/endpoints.js:23-41`（`user` 组）/ `:70-82`（`admin` 组）/ `:88-93`（`notification` 组）—— ⚠️ **上半部一行不能动**（`:26` 被 `progress.md` 按行号引）⇒ 新增一律**追加在文件尾**。
- `.../src/config/notify-events.js:39-44`（块 1 的 6 条用户/商家标签**已在**）/ `:75-80`（发送页下拉项）。

## 要新建

### ⚠️ 待裁（A 档 —— 用户批了才落）

1. **资质放哪**：新表 `merchant_application`（**建议**）vs 往 `user` 加列。
   **理由**：① `user` 被 `SELECT *` 整行读出（`UserDao.java:141`）⇒ 加列会**自动出现在 `/user/me` 与 `getUserInfo` 的返回里**（契约面被动扩大）；② 审核要「状态 / 理由 / 审核人 / 时间」4 列，且**同一账号可多次申请**（驳回后重申）⇒ 加列会把「申请历史」压成「当前态」；③ 资质是**申请材料**、不是用户属性。
2. **入驻流程**：现有客户**升级**（**建议**：用户端提交 → 管理员审核 → 通过时把账号提为商家）vs「注册时选商家 + 账号待审」vs 两者都做。
   ⚠️ **必须点明的一处偏离**：表单**只能挂用户端** —— 商家壳被 `meta.roles:['2','3']` 锁住（`router/index.js:86`，判定在 `:329-334`），**未审核的客户进不去商家壳**。语义仍是「商家入驻」，落点是用户端。
3. **封禁落在哪**：只标 `user`（**建议**）vs 只标 `login` vs 两者同标。
   **理由**：治理视图（用户列表）在 `user` 上；`login` 是**凭据表**（只有 `UserName`/`Password`/`Role`/`Id`），往里塞业务态会让「账号」与「状态」两义混在一处。登录闸在 `UserService.loginSelect`（`UserService.java:136-152`）加一步查 `user` 的封禁列即可。
4. **「提交入驻 → 通知管理员」怎么落地**：扩 `ToType` 取值域加 `admin` + 管理端壳加通知面（**建议**）vs 不动通道（管理端拿「待审数」当待办）。
   ⚠️ 这是**契约级缺口**，不是实现细节：`ToType` 现行取值域是**封闭的** `customer` / `merchant`（`docs/agent/db-schema.md:314`），而管理端**今天没有收件箱**（块 1 的接收面只挂了两壳）。且**必须新增**一条事件 code（如 `merchant.apply.submitted`）—— 块 1 的 21 条清单里**没有**「提交通知管理员」这一条。
   ⚠️ 附带事实：管理员在 `user` 表有一行（`usertype='管理员'`）且 `businessId` 把 role 3 映射到 `customerid` ⇒ 收件号取得到；「用 `customer` 通道冒充」是**语义撒谎**，不建议。

### 表（待批后定稿 ⇒ 追加到 `mall_management_sql.sql` **末尾**）

- 新建 `merchant_application`（**建议列**）：

| 列 | 类型 | 说明 |
|---|---|---|
| `Id` | bigint PK AI | |
| `Username` | varchar(20) NOT NULL | 申请人账号。⚠️ **软引用**（`user.username`），**不设 FK** —— 同 `notification.TargetId` 口径 |
| `ShopName` / `LicenseNo` | varchar(64) NOT NULL | 店铺名 / 营业执照号 |
| `ContactName` / `ContactPhone` | varchar(20) | 联系人 / 联系电话 |
| `ShopAddress` | varchar(128) | 店铺地址 |
| `LicenseImage` | varchar(500) NULL | 证件图 URL（走既有上传） |
| `Status` | varchar(16) NOT NULL DEFAULT `'PENDING'` | `PENDING` / `APPROVED` / `REJECTED`。**字符串码，不建 DB ENUM**（同 `NotificationEvents` 口径：加状态不改表） |
| `RejectReason` | varchar(255) NULL | 驳回理由 |
| `BatchId` | varchar(36) NOT NULL | 提交时 UUID —— 给通知与审计一个**跨重导稳定**的 `TargetId`（同 I2 的 `BatchId` 理由） |
| `SubmittedAt` / `AuditedAt` | datetime(3) | 提交 / 审核时刻（**DB 存 UTC**）；`AuditedAt` 可空 |
| `AuditorName` | varchar(20) NULL | 审核的管理员**账号名**（管理员无独立业务号） |
| `created_at` / `updated_at` | datetime | 规则 8 |

  ⚠️ 索引：`(Status, SubmittedAt)`（管理端待审列表 = 最热查询）+ `(Username, SubmittedAt)`（我的申请）。
  ⚠️ **不加 `UNIQUE(Username)`** —— 同一账号可多次申请；「同一时刻只有一条 `PENDING`」**由应用层保证**（全库除主键只有 3 个 UNIQUE，唯一性基本靠应用层，`docs/agent/db-schema.md:454-476`）。
- 封禁列（**若**裁「标 user」，用 `ALTER TABLE ... ADD COLUMN` 追加，规则 7 三条件）：`BanState` tinyint(1) NOT NULL DEFAULT 0 / `BanReason` varchar(255) NULL / `BannedAt` datetime(3) NULL —— **都带 `COMMENT`、都加在表末尾**。
  ⚠️ **连带后果（必然发生，不是 bug）**：`user` 被 `SELECT *` 读 ⇒ 这三列会**自动出现在** `/user/me` 与 `/user/getUserInfo` 的返回 Map 里（前端多三个键）。**建议不剥**（少一处特判），但要知道它发生了。

### 后端（**建议**分层 —— B 档，报备）

- **新域 `merchant/`**（**新建**，与 `user/` 平级；全仓今天**没有** merchant 包）：
  · `.../merchant/dao/MerchantApplicationDao.java` —— **新建**：新表的唯一 SQL 出口（`@Repository` + `JdbcClient`，照 `NotificationDao.java:48-290`）。
  · `.../merchant/service/MerchantApplicationService.java` —— **新建**：提交（写申请 + 发通知 + 记审计）/ 我的申请 / **审核工作流**（通过 = 提升账号；驳回 = 写理由；两态都发通知 + 记审计）。
  · `.../merchant/controller/MerchantApplicationController.java` —— **新建**，`@RequestMapping("/merchant")`，公共层端点（提交 / 我的申请）。
- **`user/` 域追加**（**改既有文件**）：`loginSelect`（`UserService.java:136-152`）加**封禁判定**（建议返回 `4` = 已封禁，**不与既有 1/2/3/0 冲突**，前端可分开提示）；`UserService` / `UserDao` 追加 `updateProfile` 与封禁列的读写。
- **`admin/` 域追加**：
  · `.../admin/controller/AdminAccountsController.java`（**新建**）—— `@RequestMapping("/admin/accounts")`。⚠️ **不加角色闸**（TD-18）。
  · `.../admin/service/UserGovernanceService.java`（**新建**）—— 封禁 / 解封工作流（改 `user` 行 + `evictPublic` + 发通知 + 记审计）。
  · `AuditActions.java:27-79` **加常量**（照 `:19-26` 的口径）：`user.ban` / `user.unban` / `user.update` / `merchant.apply.create` / `merchant.apply.audit` / `merchant.downgrade.schedule` / `merchant.downgrade.apply`。
  · `NotificationEvents.java:33-120` **已加 3 条**（A 档裁决 4 定案）：`merchant.apply.submitted` / `merchant.downgrade.scheduled` / `merchant.downgrade.applied`。
- **端点清单**（**新增**；全 `POST` + `@RequestParam(required=false)`，路径照 `/admin/notify/*` 的分组风格）：
  `/merchant/apply`（提交，Guard 首行）· `/merchant/myApplications`（我的申请）·
  `/user/updateProfile`（资料完善：gender / age / email / address / selfintroduction，**只改给了值的列**）·
  `/admin/accounts/users` · `/admin/accounts/user/ban` · `/admin/accounts/user/unban` ·
  `/admin/accounts/applications` · `/admin/accounts/application/audit`
  ⚠️ **既有端点一律不动**（`loginAdd` / `updateUserInfo` / `/user/me` 的参数与返回不变；唯一例外是 `loginSelect` 多一个返回值 `4`）。
- ⚠️ **测试面必改**（块 1 的教训）：`@WebMvcTest` 会加载**全部** `@Controller`，新 Controller 依赖的真 bean 必须**同步加进** `Mall-management-server/src/test/java/com/minke/mallmanagement/smoke/AbstractSmokeTest.java:137-146` 的 `@Import` 名单（块 1 漏了它 ⇒ 77 errors）。另：新 Controller **不得**带类级 `@Validated`（否则撞 `Mall-management-server/src/test/java/com/minke/MallManagementApplicationTests.java:121` 起的断言族）。

### 前端（本块 3 个面 + 1 个入口）

- **注册直达 `#/register`**：`router/index.js` **追加**一条 `/register` 记录（懒加载**同一个** `LoginPage.vue`，`meta.public`），`LoginPage` 按 `route.path` / `?mode=register` **初态即注册态**（今天只有 `toggle()`（`:283-287`）能翻）。
  ⚠️ **不新造注册页组件** —— 注册逻辑（`register()` `:359-390`）与登录页共用 `#acc` / `#pwd` / 校验 / 成功面，拆页等于复刻一遍（`progress.md:940` 记过这条口径）。
  ⚠️ `nav.js:63` 的 `ANON_RIGHT` **建议不动**（拆两条要不同 `tab` 键，且两个链接指同一处 = 假承诺，理由见 `progress.md:1474`）。
- **管理端真页** `.../src/views/admin/AccountsPage.vue`（**新建**）：两段 —— 「用户治理」（列表 + 筛选 + 封禁/解封，**理由必填**）与「商家审核」（`PENDING` 列表 + 通过 / 驳回）。替换 `router/index.js:451` 的 `component`（**同行替换**）。
- **入驻申请面** `.../src/views/MerchantApplyPage.vue`（**新建**，挂**用户端**）：表单 + 我的申请状态 + 驳回理由展示。路由追加在 `router/index.js` 文件尾；入口加在用户中心子菜单（`:68-74` 的 `meta.subMenu` —— ⚠️ **改它要先跑 `refs-scan.sh`**，该区间被文档引过）。
- **资料完善**：`.../src/views/UserInfoPage.vue` 增强（`gender` / `age` 由只读转可编辑 + 补只读区展示），调**新**端点 `/user/updateProfile`（**旧 `updateUserInfo` 不动**）。
- `.../src/api/endpoints.js` **文件尾追加**（`admin` 组 6 条 + 新 `merchant` 组 2 条 + `user` 组 1 条）；`.../src/api/admin.js` 追加对应函数；若加 code，`.../src/config/notify-events.js` 要**同步补中文标签**。

## 约束

1. **fail-open / fail-soft**：审计与通知**永不抛** —— 它们失败**不许**把业务成功翻成 500（同 `OrderTimelineWriter`）。
2. **`evictPublic` 收尾**：任何改 `user` 行的端点，写完必须 `UserCacheService.evictPublic(userId)`（`UserCacheService.java:130-133`）。
3. **逻辑废弃，不物理删**（规则 v2）：封禁 = 置位；解封 = 清零。**禁止 `DROP` / `DELETE`**；留痕靠 `admin_audit`（append-only）。
4. **admin 端点不加角色闸**（TD-18）—— 与 `AdminAuditController.java:23-33` 的接缝一致，等裁决。
5. **⚠️ 升级后模板分支会「对不上」**：`UserInfoPage.vue:55` 的商家版判据是 `!customerid && merchantid`，而「客户升级」**保留 `customerid`** ⇒ 升级后仍显示**客户版**布局。**落地时必须处理**（建议把判据改成 `usertype === '商家'` —— `usertype` 才是权威角色标记；改 1 行 `v-if`，**要报备**）。
6. **注册页的「商家」选项仍绕开审核**（`LoginPage.vue:175-176` 让注册直接建 `Role=2` 账号）：本块**登记不修**（改它 = 动既有注册契约，且撞「禁止删除旧接口」）。⚠️ 这是**已知缺口**，不是遗漏。
7. **时区**：新表的时刻列一律 `CURRENT_TIMESTAMP(3)`（DB 存 UTC），**前端渲染时再转**浏览器时区（同 `AuditPage` / `NotifyInbox`）。
8. **净零行数**：`router/index.js` / `endpoints.js` / `nav.js` / `admin-nav.js` 都有被按行号引的行 ⇒ **新增一律追加在文件尾或同行替换**；动到被引行前后必须重跑 `refs-scan.sh`。

## 验收

**核心 5 项必跑**（提速规则 2026-10-09 起；其余抽查）：

1. **建表**：`merchant_application`（+ 若裁加列的 `user` 三列）应用到 **VM 库**（**不整份重导** —— 那会 DROP 现有 11 张表）；`information_schema` 里见到新表。
   ⚠️ **CI 连带**：表数 15 → 16 ⇒ `scripts/concurrency/ConcurrencyRunner.java` 的 P3 门禁**会变红**，必须同笔更新 `KNOWN_TABLES`（口径与上次的修法见 `docs/agent/slot-registry.md §十七`）。
2. **编译**：`cd Mall-management-server && ./mvnw -q -DskipTests compile` 退出 0。
3. **主链路**（真后端 + VM 库，**不 mock**）：客户登录 → 提交入驻 → `merchant_application` 多一行 `PENDING`（`BatchId` 有值）→ 管理端列表见到 → **通过** ⇒ `login.Role` 1→2、`user.usertype` = 商家、`merchantid` 非空 ⇒ 该账号**能进商家壳**、且收到一条 `merchant.audit.passed` 通知；**驳回**路径 ⇒ `REJECTED` + 理由 + `merchant.audit.rejected` 通知，账号不变。
4. **权限三档**：非管理员传**别人的** `username` / `toId` → 403；自己 → 放行；管理员 → 放行。**封禁路径**：封禁后该账号 `loginSelect` 返回 **4**、进不去；解封后恢复正常。
5. **不回归**：`./mvnw -B test` **全绿**（新 Controller 必须同步 `AbstractSmokeTest.java:137-146` 的 `@Import` 名单，否则整批 smoke 上下文启动失败）+ 既有 A3 全绿（新域对既有行为**零影响**是硬门槛）。

**抽查**：`bash scripts/check-context-packs.sh`（本 pack 0 失效引用）；`refs-scan.sh`（只在动被引行时跑）；审计三态（正例有行 / 负例无行 / 有改前态的动作 `BeforeJson` 非空）。

## 扩展阅读（不够时按指针读）

- `docs/agent/context-packs/c3-block-1-infra.md` —— 块 1 的 pack：**挂钩四步**与两条管子的完整口径。本块是它的第一个消费者 ⇒ **动笔前读它的「约束」一节**。
- `docs/agent/context-packs/three-layers.md` —— Controller / Service / DAO 三层约定。
- `scripts/probes/probe-i2-e2e.mjs` —— 块 1 的**端到端验收脚本样板**（真后端 + VM 库、`R.ok(...)` 逐条断言、写一份机器可读的结果 JSON、失败非零退出）。⚠️ **本块的验收脚本照它写**（改名如 `probe-c3-block2-e2e.mjs`，落 `scripts/probes/`），别另起一套。
- `docs/agent/db-schema.md` —— 表结构现状（新表要登记；`:454-476` 是「唯一性靠应用层」的基线）。
- `docs/agent/db-migration.md` —— 变更登记（本块将是 **#6**；`#4` = 批 A、`#5` = I2，**注意 8** 记了「漏登」的来龙去脉）。
- `docs/agent/slot-registry.md` §十五 / §十七 —— 块 1 的登记结构与 **CI 门禁 `KNOWN_TABLES` 的同步义务**。
- `docs/agent/api-contract.md` —— ⚠️ **已 stale**：C3 的 11 个端点（`/admin/*` + `/notification/*`）**未登记**在内（`:18` 仍写「共 36 个」）。本块新增端点**建议同样只在 slot-registry 登记**（要不要回填 `api-contract.md` 留给块收口裁决）。
