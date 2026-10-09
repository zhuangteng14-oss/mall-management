# context-pack: C3 块 1 —— 基础设施（权限与审计 + 系统通知）

> 索引：`docs/agent/c3-blocks-index.md`。**只精读下文列出的区间，不要整份读任何文件**（除注明「整份」的）。

## 一句话定位

C3 全部写操作的**横切底座**，两块各管一半：

- 「**权限与审计**」—— 把「**谁 / 何时 / 对什么 / 改前 / 改后**」落 `admin_audit`；
- 「**系统通知**」—— 平台 → 个人的**单向投递**（公告 / 群发 / 定向 / 模板），
  且必须能被「**被评论 / 被回复 / 被 @**」三类事件触发（硬约束来自 `c3-modules.md` §9.2）。

它自己**不产生业务动作** —— 它是别的块挂上来的那一层。

> **状态（2026-10-09 更新）**：**两块都已落码** —— 审计 = 批 A（`slot-registry.md §十四`）、
> 系统通知 = **块1·I2**（`slot-registry.md §十五`）。⇒ 本节以下凡标「待建」的，**I2 已全部兑现**
> （见「要新建」逐条的 ✅）。⚠️ 但**块 6 的触发点仍刻意未实现**（只留 code 与 `send` 原语）。

## 依赖块

**无。** 块 1 是根，被块 2–7 **全部**依赖（每块都往它上面挂）。
⇒ 本块的接口（`AuditService.record` 的签名、`AuditActions` 的命名口径）**改了就是全仓改**。

## 要读

### 设计意图（读区间）
- `docs/agent/c3-modules.md:133-139` —— §11「权限与审计」的原始定义（**横切所有写操作**：记录 + 查看 / 筛选 / 导出）。
- `docs/agent/c3-modules.md:96-104` —— §6「系统通知」的功能面（公告 / 群发 / 定向 / 模板）。
- `docs/agent/c3-modules.md:247-296` —— §9.1 秒杀双向制 + §9.2 评论区多对象。
  ⚠️ **§9.2 末段是本块 I2 的硬约束**（通知要支持「被评论 / 被回复 / 被 @」）。
- `docs/agent/c3-modules.md:300-311` —— §10 路线修订（本块 = 8 块路线里的第 1 块；§七 旧批次表已作废）。

### 已落的代码 —— 审计半边（读它就是读**现成的形状**）
- `Mall-management-server/src/main/java/com/minke/mallmanagement/admin/service/AuditService.java:42-154`
  —— `record(...)`（`:73`）的签名 + **fail-open 契约**（`:81-84` catch 一切、只打 `ERROR ... AUDIT`，
  **不让审计失败翻业务**）。上限常量 `DEFAULT_LIMIT=100`（`:47`）/ `MAX_LIMIT=500`（`:50`）。
- `.../admin/dao/AdminAuditDao.java:34-170` —— 表列 + `insert`（`:61`）/ `selectAudits`（`:80`）/
  `countAudits`（`:119`）/ `selectAuditById`（`:150`）/ 两个 facets 去重（`:156`、`:162`）。
  ⚠️ `BeforeJson` / `AfterJson` 读出走 `CAST(... AS CHAR)` —— MySQL JSON 列的原生读法会带回外层引号。
- `.../admin/controller/AdminAuditController.java:42-82` —— `/admin` 三个端点（`list` `:59` / `detail` `:72` / `facets` `:78`）。
  ⚠️ **`:23-33` 是「本批不动鉴权面」的理由**（TD-18）—— 动手前必须先读，否则会把「放宽」误当「加固」。
- `.../admin/AuditActions.java:19-26` —— tag 口径 `<TargetType>.<动作>`（**今天 2 条**：`goods.update` `:35`、
  `notification.send` `:51`）。
  ⚠️ `:19-25` 说明「**一开始只有 1 条常量不是遗漏**」—— 其余写端点**随各自那笔挂**。

### 样板挂钩（块 1 交付的那个「1 个钩子」）
- `.../goods/controller/GoodsController.java:210-227` —— **四步形状**：改前快照 → 业务 → 判 `success` → 改后快照 + 记账。
  **块 2–6 每挂一个写端点，照这个形状抄一遍**（**刻意不抽工具方法**，理由见「约束」）。
- `.../goods/service/GoodsService.java:475-478` —— 快照读的 Service 转发，**刻意非事务**
  （`record` 不加入 `goodsUp` 的 `@Transactional`：它必须是**独立的自动提交 INSERT**）。
- `.../goods/dao/GoodsDao.java:234-239` —— `selectGoodsForAudit`。
  ⚠️ 两条：谓词必须与 `updateGoods` 的 `WHERE` **逐字相同**；**返回 List 不取单行**（同键可多行）。
- `.../admin/controller/AdminNotificationController.java:74-114` —— **全仓第 2 个挂钩点**（I2 落，2026-10-09）。
  与 `goodsUp` 同形状，但按「**新增类动作**」调了三处，是**非 `goods` 域**的第一个样本 ⇒
  块 2–6 的写端点更该照它（那些多半是新增 / 状态变更，而非改既有对象）：
  **① `before` 传 `null`**（无改前态）；**② `TargetId` 用 `batchId`（UUID）而非自增 Id**
  （本表**跨全量重导保留** ⇒ 自增 Id 会漂）；**③ `AfterJson` 只留索引字段、不抄正文**
  （正文留在域表 `notification` 上，靠 `batchId` 一 join 就到 —— 审计做**索引**，正文留域表）。

### 权限面（块 1 的另一半地基）
- `.../shared/web/IdentityGuard.java:65-74` —— `requireSelfOrAdmin` 的三档判据。
  ⚠️ `:36-49` 是「**解析不出身份 = 放行**」的理由 —— 最容易被误读成漏洞的一条。
- `.../shared/web/AuthSession.java:77-134` —— `ROLE_ADMIN=3`（`:77`）/ `Identity`（`:84`）/
  `identify`（`:105`）/ `authorize`（`:128`）。**识别口径 vs 授权口径**是本块的关键区分（cookie 来的 `role=3` 只被识别、不被授权）。
- `.../shared/web/AuthInterceptor.java:12-30` —— 登录闸四档 + 401 契约（**闸关着 ⇒ 全放行**）；
  `:48-56` 说明 401 响应体为什么是字面量常量。
- `.../shared/web/WebAuthConfig.java:97-116` —— `AUTH_WHITELIST`（白名单，**唯一出处**）+
  「身份识别例外」表（**与白名单不是一回事**）。

### 先例：往日志表写，全仓只有一处 —— 照它
- `.../order/model/OrderTimelineWriter.java:23-137` —— **永不抛 / fail-soft / 「允许丢，不许静默」**、
  WARN **只在状态翻转时**打一行、`TABLE`+`INSERT_SQL`+`PARAM_COUNT`（`:59`、`:68`、`:72`）由测试钉住。
  ⚠️ 这是全仓「往日志表写」的**唯一先例**，`AuditService` 就是照它写的；**系统通知的写入路径也要照它**。

### 表
- `mall_management_sql.sql:264-292` —— `admin_audit` 迁移块（`CREATE TABLE IF NOT EXISTS` 在 `:276`）。
  ⚠️ 看块头 `:271-273`：用 `IF NOT EXISTS` 且**不**在头部加 `DROP` ⇒ **全量重导不清审计历史**（刻意）。
- `mall_management_sql.sql:294-327` —— **I2 的 `notification` 迁移块**（`CREATE TABLE IF NOT EXISTS` 在 `:309`）。
- `mall_management_sql.sql:329-345` —— **I2 的 `notification_template` 迁移块**（`:335`）。
  ⚠️ 两块的 `IF NOT EXISTS` / **块内不 DROP** 口径与 `admin_audit` **同款** ⇒ 全量重导**不清通知历史**。
  ⚠️ `notification` 块注释 **`:305-307`** 逐字写着「`TargetId` 软引用、**无 FK**、前端容错」—— 与 pack 同口径，改一处必须改另一处。
- `mall_management_sql.sql:120-133` —— 既有 `message` 表（点对点聊天）。**系统通知不放进它**，理由见「要新建」。

### 账
- `docs/agent/tech-debt.md:231-...` —— **TD-18**（管理员铸不出 token）。
  ⚠️ 本块的读 API 今天**没有角色闸**，根因就是这一条。

### 前端（本块两个面各占一条**既有**导航项）
- `Mall-management-web/mall-management-system/src/router/index.js:396-462` —— `/admin` 路由：
  记录在 `:436-439`（`AdminShell` 懒加载在 `:439`；`meta.roles:['3']` 在 `:438`）、
  局部常量 `AdminStub`（`:434`，懒加载 `StubPage`）、**11 条**占位记录（2 域入口 + 9 子项，`:447-462`）、
  **`business/notify`（`:456`）已换成真页**（I2 —— **同行替换** `component`，故该区间行号**原地不动**）。
  ⚠️ 全仓引本文件的**最大被引行是 `:378`**：**任何新增行必须落在它之后** —— 该理由的原文在 `:397-404`。
  两个**端壳**的 `notify` 子路由也照此用 `router.addRoute('customer'|'merchant', …)`
  **追加在文件尾**（不往 `:57-83` / `:88-122` 里插行）。
- `.../src/config/admin-nav.js:32-53` —— `ADMIN_SIDEBAR`。本块两条：
  `observe/audit`（`:52`「权限与审计」）与 `business/notify`（`:42`「系统通知」）。
  ⚠️ 改 `key` / `to` 要**三处同改**（前因见 `:17`）。
  ⚠️ **三端**的通知入口在 `config/nav.js`（**不在** `admin-nav.js`）：`CUSTOMER_NAV.right` / `MERCHANT_NAV.right`
  各加 `{ label:'通知', tab:'notify', badge:'notify', … }`，**插在「消息」之后**（I2 改；改它会漂移其行号引用 ⇒ 跑 `refs-scan.sh`）。
  ⚠️ **按语（「合并入口」笔，2026-10-09 晚）：上面这一条已作废 —— 那两个「通知」项已从 `nav.js` 删除**。
  现行：顶栏**只剩一个「消息」项**，点它进**消息中心页** `views/MessageCenter.vue`（页内 tab：会话 / 通知），
  顶栏红点 = 未读会话 + 未读通知；旧地址 `/…/notify` 改 `redirect` 到 `/…/msg?tab=notify`。
  ⚠️ **只动前端入口** —— 本 pack 下面所有关于表 / 端点 / `NotificationService` 的内容**仍然有效**。
  细节见 `slot-registry.md §十六`。
- `.../src/views/AdminShell.vue:69-90` —— 壳：面包屑读 `route.meta.title`，点子项读 `route.meta.sub`；
  `SIDEBAR` 的单一事实源在 `admin-nav.js`。
- `.../src/api/endpoints.js` —— **全仓唯一允许写后端路径字面量的文件**。
  ⚠️ **上半部一行不能动**（`:26` 被 `progress.md` 按行号引）⇒ 新增一律**追加在文件尾**。
  现状：`admin` 组 = 批 A 的 3 条 `/admin/audit/*` **+ I2 的 4 条 `/admin/notify/*`**；
  另在文件尾新开 **`notification` 组**（I2 的 4 条接收端点，前缀 `/notification` —— **公共层，无 `/admin`**）。
- `.../src/api/http.js:190-220` —— `get` / `post` 包装（401 出口在 `request` 内）。前端调审计 / 通知 API 都走这两个。
- **I2 的接口 / 配置层**（读它就是读**现成的形状**）：
  `.../src/api/notify.js`（收件箱四件套 `notifyList` / `notifyUnreadCount` / `notifyRead` / `notifyReadAll`）、
  `.../src/api/admin.js`（追加 `notifySend` / `notifyHistory` / `notifyTemplateList` / `notifyTemplateSave`）、
  `.../src/config/notify-events.js`（21 条 code → 中文标签；`eventLabel()` **兜底** `EVENT_LABELS[code] || code`）。

## 要新建

### 表
- **`notification`** —— ✅ **已落**（I2，VM 库；`mall_management_sql.sql:294-327`，字段清单见 `db-schema.md §6.14`）。
  定稿列（**不再来回改**）：`BatchId` / `ToId` + `ToType` / `EventType` / `Title` / `Content` /
  `TargetType` + `TargetId`（可空）/ `ReadAt`（`NULL` = 未读）/ `CreatedAt`（`datetime(3)`，**DB 存 UTC**）。
  ⚠️ **`BatchId`（varchar(36)，UUID）是开案之外的新增列**：不做它，管理端「通知历史」只能把一次群发的 N 行
  逐条平铺（刷屏），或按 `(Title,EventType,分钟)` 近似归组（脆弱）。**单发也生成一个**（别写 NULL）。
  ⚠️ **`ReadAt` 落在行上 ⇒ 公告 / 群发必须扇出**（一行多人共享读态做不到）⇒ 表语义 = **每收件人一行**。
  ⚠️⚠️ **`TargetId` 是软引用，`TargetType`/`TargetId` 都「不设 FK」**（用户裁决 2026-10-09）：
  目标对象可能已被删除 / 全量重导后失效；有 FK 会让插入报错或触发级联删。**前端容错规则**：
  目标对象不存在 ⇒ 显示「**（相关对象已删除）**」，**不崩、不空白**。
  ⇒ 与建表块注释 `mall_management_sql.sql:305-307` **逐字同口径，改一处必须改另一处**。
  ⚠️ **与既有 `message`（`mall_management_sql.sql:120`）分开建**：`message` 是「两人之间的会话」，
  系统通知是「**平台 → 个人**的单向投递」—— 混一张表会让会话列表被公告淹没，
  且 `message` 的 `from_id` 在公告场景下没有意义。
- **`notification_template`** —— ✅ **已落**（I2；`:329-345`，`TemplateKey` 唯一）：模板键 / 标题模板 / 正文模板 / 启用。
  ⚠️ 块 1 **只做模板的存取 CRUD** —— `send` 收**字面** title/body，**不在发送时套用模板**（占位符渲染留后）。
- （`admin_audit` **已建**，见「要读 → 表」。）

### 后端
- **审计**：`AuditService` / `AdminAuditDao` / `AdminAuditController` / `AuditActions` —— **已落**（批 A）。
  15 个写端点的挂钩**待做但不在本块**（随各自那笔挂，见 `AuditActions.java:19-24`）。
- **系统通知** —— ✅ **已落**（I2）。**切法 = 原语 vs 工作流**：行级 `send`（fail-soft 原语，块 6 要用）住**公共层**；
  admin 的「受众解析 + 扇出 + 模板」住 `admin/`。六个类：
  · 公共层 `.../notification/NotificationEvents.java` —— **21 条** code 常量，类注释写明「**仅为登记簿，后端不校验**」；
  · `.../notification/dao/NotificationDao.java` —— 两张表的**唯一 SQL 出口**（`@Repository` + `JdbcClient`）；
  · `.../notification/service/NotificationService.java` —— `send`（fail-soft 原语）+ `list` / `unreadCount` / `markRead` / `markAllRead`；
  · `.../notification/controller/NotificationController.java` —— `@RequestMapping("/notification")`，**三端接收**端点；
  · `.../admin/service/NotificationBroadcastService.java` —— **受众解析 + 扇出**（admin 专属工作流）；
  · `.../admin/controller/AdminNotificationController.java` —— `@RequestMapping("/admin/notify")`，发送 / 历史 / 模板。
  ⚠️ **`send` 走 fail-soft，照 `OrderTimelineWriter` / `AuditService.record`**：永不抛、不加 `@Transactional`、
  日志标记用 **ASCII `NOTIFY`**（中文 pattern 在 GBK 日志里 grep 不到，同 `AUDIT` 惯例）。
  ⚠️⚠️ **`send` 不拿 `eventType` 与常量类比对** —— 未知 code **照样落库**，这正是「**加类型不改表**」的兑现点；
  反过来要求**前端映射必须有兜底**（`eventLabel(code) || code`，未知 code 显原文 chip，**不静默**）。
  ⚠️ **接收侧有条继承来的边界**：`requireSelfOrAdmin` 只认 `toId`、**不认 `toType`** ⇒ 传
  `toId=<商家号> + toType=customer` **不报错**（只是查不到行）。既有形态，I2 **未收口**（见 `slot-registry.md §15-6`）。
  ⚠️ **admin 端点坚决不加角色闸**（TD-18）：接缝与 `AdminAuditController` 绑定，等裁决。
  ⚠️ **受众解析的魔法串**：`user.usertype` 是**中文**「客户」/「商家」⇒ 在 Service 里收成私有常量并注释
  （将来加第三类 `usertype` 会静默漏人）。
- **触发点**：评论区（块 6）产生 `comment` 事件 → 调 `NotificationService.send(...)`。
  ⚠️ 块 1 **只定接口与表，不实现块 6 的触发点**；但 21 条 code 已**把这三类事件留出来** ——
  `comment.on.post` / `comment.on.user`（被评论）、`comment.reply`（被回复）、`comment.mention`（被 @）。

### 前端
- 管理端「权限与审计」页 —— ✅ **已落**（批 A）：`/admin/observe/audit` 占位 → 真页 `views/admin/AuditPage.vue`：
  列表 + 筛选（动作 / 对象 / 操作者 / 时间范围）+ 详情（**改前 / 改后并排 diff**）。
  ⚠️ **`OccurredAt` 渲染时按浏览器时区转换** —— 后端存的是 DB 时钟 **UTC**（全仓 `NOW()` 一致），**不要改写入**。
- 管理端「系统通知」页 —— ✅ **已落**（I2）：`/admin/business/notify` 占位 → 真页 `views/admin/NotifyPage.vue`
  （发送：受众 `all`/`customer`/`merchant`/定向 + 事件类型下拉〔另留自定义 code〕；历史按批显 `recipientCount`；模板管理）。
- 三端接收面 —— ✅ **已落**（I2）：用户端 / 商家端**共用** `views/NotifyInbox.vue`（拉 `/notification/list`；
  点单条 → `read`；「全部已读」→ `readAll`）。顶栏入口 + 红点在 `views/AppShell.vue`
  （`notifyUnreadCount` + `fetchNotifyUnread()` + `dotCount(badge)`）。
  ⚠️ **`CreatedAt` 是 UTC，渲染时按浏览器时区转**（同 AuditPage）；**入口与「消息」分开**（两个导航项、两个页面、两套 API）。
  ⚠️ **软引用容错**：`TargetType`/`TargetId` 非空时尝试解析目标；解析失败或对象不存在 ⇒ 显示「（相关对象已删除）」。
  块 1 **不产生 target**（`TARGET_RESOLVERS = {}` 故意为空），此路径留给后续块。
  ⚠️ `fetchNotifyUnread()` **必须先判 `isLoggedIn()`** —— 否则匿名残留 id 会 401、把公开页踢去 `/login`（同 `fetchUnreadCount`）。
- `src/api/endpoints.js` —— ✅ **已落**：`admin` 组（批 A 的 3 条 `/admin/audit/*` + I2 的 4 条 `/admin/notify/*`）
  + 文件尾 `notification` 组（I2 的 4 条接收端点）。

## 约束

- **不抽工具方法**：样板挂钩在 Controller 里**就地四步展开**，不封装成切面 / 工具方法。
  (a) 本仓无 AOP 先例，且切面会让审计**隐式发生**，与「改前快照必须在 UPDATE **之前**」这条**显式**要求相冲突；
  (b) 审计的**位置**本身就是语义（必须夹在判权与业务之间）。
- **fail-open，不 fail-closed**：审计写失败**不许**把业务成功翻成 500（同 `OrderTimelineWriter`）。
- **append-only**：`admin_audit` 只 INSERT，永不 UPDATE / DELETE。
  ⚠️ **`notification` 不是 append-only** —— 标记已读会 UPDATE（`ReadAt` + `updated_at`）。**两表相反，别照抄。**
- **类型是字符串 code，不是 DB ENUM**：加类型**不改表**；`NotificationEvents` 只是**写入方登记簿**（防两侧抄错），
  后端 `send` **不校验**；⇒ **前端映射必须有兜底**（`eventLabel(code) || code`，未知 code 显原文 chip、**不静默**）。
- **`TargetId` 软引用、无 FK**：目标对象不存在 ⇒ 前端显示「（相关对象已删除）」，**不崩、不空白**。
- **⚠️ 不动鉴权面**（`AdminAuditController.java:23-33`）：本块**既不**把 `/admin/**` 加进 `AUTH_WHITELIST`，
  **也不**加「须 `role=3`」判据 —— 这条接缝与 TD-18 绑定，**等裁决**。
  （I2 的 `/admin/notify/*` 同理：**不加角色闸**。）
- **不动 DB 时钟**：`OccurredAt` / `notification.CreatedAt` 用 `CURRENT_TIMESTAMP(3)`（**UTC**）；时区只在**前端**转。

## 验收

1. **审计链通**：`POST /goods/goodsUp` 改一次价 → `admin_audit` 多一行；`BeforeJson` / `AfterJson`
   **只差被改的那一列**；`ActorId` = 商家业务号。
2. **fail-open**：让审计写失败（改名表 / 断库）→ 业务仍返回 `200 + success=true`，日志出现 `ERROR ... AUDIT`。
3. **权限三档**：非管理员传**别人的** `MerchantId` → **403**；传自己的 → 放行；管理员传别人的 → 放行。
4. **读 API**：`/admin/audit/list` 的 `action` / `targetType` / `actorId` / 时间范围筛选都能收窄；
   `limit` 超上限被夹到 500。
5. **时区**：AuditPage 显示的 `OccurredAt` = **浏览器本地时间**（与库里 UTC 值差一个时区偏移，且随浏览器时区变）。
6. **一致性**：`bash scripts/check-context-packs.sh` 对已落地的 pack 报 **0 失效引用**。
7. **不回归**：`./mvnw -q -DskipTests compile` 退出 0；既有 smoke / A3 全绿
   （`IdentityGuard` 与审计挂钩对既有行为**零影响** —— 这是硬门槛）。

### I2 系统通知（2026-10-09 实测 · 真后端 + VM 库，不 mock）

8. **建表**：两个 Migration 块应用到 **VM 库**（**不**整份重导 —— 那会 DROP 现有表）；
   `information_schema` 里见到 `notification` / `notification_template`。
9. **发送 → 落库 → 接收**：`POST /admin/notify/send {audience:merchant}` → `{success:true,sent:N,batchId}`；
   N 行的 `ReadAt` 全 NULL、`BatchId` 一致、`ToId` = `user.merchantid`；`/notification/list` 与 `/unreadCount` 互相一致。
10. **已读**：`/notification/read {id}` → `updated` = **1**（**重复调 → 0**）；`/readAll` → `updated` = **N**；
    DB 该行 `ReadAt` 非 NULL、`updated_at` 变。
11. **code 可扩（核心判据）**：`send {eventType:"weird.unknown.code"}` → `success:true`（**不校验白名单**）；
    `list` 里见到该 code；前端行**兜底显示 + 原文 chip**。
12. **权限三档**（**闸开**实例）：传**别人的** `toId` → **403**（`list`/`unreadCount`/`read`/`readAll` **四端点全中**）；
    传自己 → 放行；匿名 → **401**。
    ⚠️ 附带事实：**闸开**时带 `role=3` cookie **一律 401**（TD-18：管理员铸不出 token）—— 只咬**默认 profile**。
13. **三端 E2E（13 / 13）**：管理端发送 → 客户端顶栏「通知」**5s 内**出红点 → 进收件箱红点清零、
    点单行后 DB `ReadAt` 被写；`CreatedAt` 显示为**浏览器本地时间**。
    ⚠️ **按语（「合并入口」笔，2026-10-09 晚）**：本条记的是**当时（两个入口）**的形态，**作为实测记录仍然成立**。
    合并后顶栏**没有**独立的「通知」项了 —— 红点出在**「消息」**项上（= 两笔之和），落点变成
    `…/msg?tab=notify`。合并后该链路**尚未在真后端上重跑**（mock 探针 17/17 已过，见
    `slot-registry.md §十六 16-3`；真后端复验需先重启用户的 8088）。
14. **一致性 / 不回归**：`refs-scan.sh` 无陈旧 token；`check-context-packs.sh` **0 失效引用**；
    `npm run build` 绿；`./mvnw -B test` **237 / 0 failures / 0 errors / 19 skipped / BUILD SUCCESS**
    （通知新域对既有行为**零影响** —— 硬门槛）。
    ⚠️ **这一条是查出来才绿的**，不是本来就绿：批 A 起项目测试套件就是红的（**77 errors**，
    全部是同一个根因 —— `smoke.AbstractSmokeTest` 这个 `@WebMvcTest` 切片加载全部 `@Controller`、
    却由基类 `@Import` 逐个补 `@Service`，新控制器没进名单就**上下文启动失败**）。
    本笔按该基类既有范式补了 `@Import`；**明细与「为什么不是靠 skip 变绿」见
    `slot-registry.md §十五 15-7`**。⚠️ 后续任何**新增 Controller** 的笔都要同步那张 `@Import` 名单。
15. **通知发送挂审计**（用户 2026-10-09 裁决「发送公告是典型平台动作，必须留痕」）：
    `POST /admin/notify/send` 成功后写一行 `admin_audit`（`Action='notification.send'`）。
    判据（新编译实例上实测）：**正例有行 / 负例无行**（只有 `success=true` 才记）、`BeforeJson` 恒 NULL、
    `TargetType='notification'`、`TargetId=batchId`。⚠️ 回体因此**多带一个 `eventType`**（生效值），
    供挂钩写「实际用了哪个 code」。⚠️ **改完要重启后端**才生效（旧 class 回体里没有该字段）。
    明细 `slot-registry.md §十五 15-8`；原文 `docs/agent/evidence/i2/audit-hook/`。
16. **证据与探针的固定位置**（用户口径）：可复跑探针 → `scripts/probes/`；验收证据 →
    `docs/agent/evidence/<笔名>/`；临时产物 → `_work/`（已 gitignore）。见 `slot-registry.md §十五 15-9`。

## 扩展阅读（不够时按指针读）

- `docs/agent/context-packs/three-layers.md` —— Controller / Service / DAO 三层约定（admin 域照它分层）。
- `docs/agent/context-packs/cache-layer.md` §11 —— evict 面（给写端点挂钩时别忘缓存失效那一步）。
- `docs/agent/progress.md` §5-AF —— 13 个身份校验端点的清单。
- `docs/agent/db-schema.md` —— 表结构现状（新建表要登记）。
- `docs/agent/db-migration.md` —— 数据库变更记录：**#4 = `admin_audit`（批 A，补登）**、**#5 = `notification` + `notification_template`（I2）**；
  ⚠️ **注意 8** 记了「批 A 当时漏登」的来龙去脉（连带 `db-schema.md` 表数 12 → 15）。
- `agent.md`「数据库管理规则」8 —— `created_at` / `updated_at` 的约定。
