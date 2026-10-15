# 数据库结构 — mall_management_sql {#db-top}

> **证据来源**：`mall_management_sql.sql` 全部 CREATE TABLE 段（行号为该文件行号）、`application.properties`、`pom.xml`。**`.sql` 是唯一权威源，本文件只做记录，不反向约束。**
> **行号约定**：本文件全部 `:N` 均为 `.sql` 的**文件真实行号（LF 计数）**，不是任何工具归一化行尾后的行号。（该文件行尾为 CRLF，`tr '\r' '\n'` 之类的读取会把行号翻倍 —— 已实证对撞：真实 `146` 与归一化 `292` 同为 `` `Price` float NOT NULL,``。）
> **Java 坐标约定（2026-09-30 补）**：`G:` = `GoodsController.java`（前 `MallManagementGoodsController.java`）、`M:` = `MessageController.java`（前 `MallManagementMessage.java`）、`U:` = `UserController.java`（前 `MallManagementUserController.java`）的**语句行**（**不是** route 注解行，与 `api-contract.md` 的口径**相反**，两文件不要互抄）。B1 / B2 / B3 之后若干读写路径已搬到 `mallmanagement_service` 下的 Service ⇒ 本文件出现 `GoodsCacheService:` / `UserCacheService:` / `OrderStateMachine:` 等前缀，那是**跨文件搬迁**、不是行号漂移。**全文件 Java 坐标已于 2026-09-30 对 HEAD 逐条复核并替换** —— 原一版系统性偏旧（成因同 `api-contract.md#api-top`：各笔新代码插入后未同步）。⚠️ **三层四笔（order PR #25 `28194b4` / goods 2026-10-02 / user PR #32 `0c07e96` / message `8ed5fc1`）后**：`G:` / `M:` / `U:` 三个 Controller 已只剩路由 —— SQL 与业务体分别下沉 `<域>/dao/<域>Dao.java` 与 `<域>/service/`。**本文件下表凡涉这三个域的 `G:` / `M:` / `U:` 坐标，若未随该域的笔改写，一律已陈旧**；下表 `:41-48` 的用户域行已于第三笔、消息域行（`M:378`）已于第四笔改写，其余（`U:` 一族、`G:` 的未重测行）引用前请以源码为准。
> ⚠️ **2026-10-02 笔 2 后**：订单端点的 SQL 已随 `OrderController` 拆出，迁至 `order/dao/OrderDao.java`；`GoodsController` 的商品行整体 **−9**（⚠️ 该「−9」只是**首两行**的口径：以 2026-09-30 基线 `148e77b` 逐条实测，`goodstype`/`goodsAdd` 为 −9、`goodsSelect` 一族 −8、`productCodePrice` −4、`goodsUp` 之后 +16/+23/+23，**不要拿 −9 换算全表**；详见 `api-contract.md#api-top`）。下表**订单相关行的 `G:` 已改指 `OrderDao`**，其余 `G:` 值**尚未重测** —— 引用前请以源码为准。
> ⚠️ **2026-10-02 三层重构第二笔（商品域）后**：商品 **8 条内联 SQL** 从 `GoodsController` 下沉到 `goods/dao/GoodsDao.java`，写/读方法体进 `goods/service/GoodsService.java` —— 本表原 `G:` 的**商品行**据此改指 `GoodsDao.searchByKeyword`。这四个走缓存的读路径（`GoodsCacheService:81` / `:146` / `priceByTriple`）**未受影响**（它们的 SQL 本就在 Service，2026-09-30 已复核）。新增前缀：`S:` = `GoodsService.java`、`D:` = `GoodsDao.java`。
> 表数量：**18** —— 11 张原始表 + 7 张由 `.sql` 末尾 Migration 块建的：`order_state_transition`（B3，2026-09-29，`:221-235`）· `admin_audit`（C3 批 A，2026-10-08，`:264-292`）· `notification`（C3 块1-I2，2026-10-09，`:294-327`）· `merchant_application`（C3 块 2〈用户块〉，2026-10-09，`:369-390`）· `template`（C3 块3 **就地改造**，2026-10-10 #8，`:556-569`）· `goods_audit`（C3 块 3〈商品块〉，2026-10-10，`:492-515`）· `cart`（C3 块 4〈订单块〉，2026-10-10，`:708-720`）。⚠️ **C3 块 4〈订单块〉（2026-10-10，分支 `feat/c3-admin-block4`）—— 本文件第二次「加表」但 `cart` 刻意不走本文件的中段**：`cart` 是 A1 甲（用户 2026-10-10 逐项裁决）的**真购物车载体**，逐列清单 / 索引 / 与 `order` 的关系**登记在文件末 §11**，**不就地插入 §1 表清单与 §6.x** —— 理由是零漂移：本文件当前有 **44 处外部 `文件:行号` 引用**（10 个文件，指向本文件 **26 个不同行号**）（`slot-registry.md` 26 / `selection-status.md` 5 / `progress.md` 4 / `defect-survey.md` 2 / context-pack 4 / `OrderCenter-merchant.html` 1 / `api-contract.md` 1 / `OrderDao.java` 1），中段插一行就让其后**全部**集体 +1；重指回本文件的工作归「文档漂移清理笔」（同 §十五 15-5 的登记不修口径）。另 `order` **加** `Quantity`（`.sql:730-738`，`int NOT NULL DEFAULT 1`，存量 6 行回填 1，**不增表、不改行数** ⇒ A3 的 P4 种子计数 `order=6` 不受影响）。⚠️ **`cart` 不在 `.sql:21` 起的 `DROP TABLE IF EXISTS` 基线清单里** —— 它是用户数据不是种子，整份重导**不清空购物车**（`.sql:689-690` 块注释）。⇒ 表数 **17 → 18**、索引 **16 → 18**（+`uk_cart_customer_product`、+`idx_cart_customer`）、**非主键 UNIQUE 3 → 4**（新增第 4 个 `uk_cart_customer_product`；⚠️ 三处计数均为**按 DDL 直接推导**，非实测查询结果，出处 `.sql:708-720`）。⚠️ **`notification_template`（`:329-345`）与 `goods_template`（`:474-484`）已由 #8 物理删** —— 两者在 `.sql` 里的 `CREATE TABLE` 语句**仍在原地**（零漂移手法），由块尾的 `DROP TABLE IF EXISTS` 中和 ⇒ **本笔之前**，该文件里共 **21** 个 `CREATE TABLE`、**净 19 张表**（判据：导入后那两张表不存在；⚠️ 原写「20 个 / 净 18 张」**漏算了 §12 的 `order_shipping_track`**，2026-10-11 订正）。⚠️ **可视化段（2026-10-11，commit 0–4）之后**：再加 **3 张表**（`order_item` commit 0 / `stats_daily` commit 2 / `goods_sku` commit 3 补丁）+ **4 张表加列**（`login.CreateTime` / `goods` ×4 / `goods_audit` ×5 / `template.Kind`）+ **11 张表各加一列 `Source`** ⇒ 表数 **19 → 22**、`CREATE TABLE` **21 → 24**、索引 **23 → 30**、非主键 UNIQUE **4 → 7**（VM 库 `information_schema` 实测，2026-10-11）。§1 表清单与 §6.x **刻意不插入**（零漂移，理由同本段开头的 `cart`）—— 逐列定义 / 净变化表见**文件末 §13**。⚠️ **C3 块 5〈营销块〉（2026-10-11/12）之后**：再加 **8 张表**（`coupon` / `coupon_grant` / `subsidy_rule` / `subsidy_goods` / `seckill_invitation` / `seckill_application` / `seckill_activity` / `shop_follow`）+ **6 处加列**（`goods.OriginalPrice` / `order.CouponId` / `order.SubsidyAmount` / `order.PayAmount` / `goods_audit.OriginalPrice`）+ **1 个新索引**（`goods_sku` 的 `idx_goods_sku_sku_code`）⇒ 表数 **22 → 30**、行首 `CREATE TABLE` **24 → 32**（VM 库 `information_schema.TABLES` 实测 = 30、本文件所在仓的 `.sql` 实测 `CREATE TABLE` = 32，2026-10-12）。**本行的「表数量：18」与 §2 行首的「17 张表 / 共 20 个索引」一律按各自写作时的实测口径读，不要拿它们做减法**；块 5 口径以 **§14** 为准（逐列定义 / 加列 / 索引 / 净变化表）。⚠️ **C3 块 6〈内容+消息块〉（2026-10-12/13）之后**：再加 **6 张表**（`comment` / `comment_mention` / `comment_image` / `comment_like` / `report` / `sensitive_word`）+ **1 处加列**（`user.WarnCount`，`int NOT NULL DEFAULT 0`）⇒ 表数 **30 → 36**、行首 `CREATE TABLE` **32 → 38**（VM 库 `information_schema.TABLES` 实测 = 36、本仓 `.sql` 实测 `CREATE TABLE` = 38，2026-10-13）。§1 表清单与 §6.x **同块 5 一样刻意不插入**（零漂移），块 6 口径以文件末 **§15** 为准（逐列定义 / 加列 / 索引 / 净变化表）。⚠️ **C3 块 7〈可观测域 + 库存治理〉（2026-10-13/14）之后**：Migration 段 ⑰ 加 **1 张表**（`drill_record`）+ **8 列**（2 个演示基线列 + 块 6 六表的 `Source`）、⑱ 加 **1 列**（`order.StockDeducted`）⇒ 表数 **36 → 37**；commit 8b 的 ⑲ 再加 **1 张表**（`observed_event`）+ **4 列**（`BatchId`）⇒ 表数 **37 → 38**、行首 `CREATE TABLE` **38 → 40**（VM 库 `information_schema` 实测 = 38 表 / 非主键索引 73 / 非主键 UNIQUE 19，2026-10-14）。⚠️ **块 7 的 ⑰ / ⑱ 两段本文件尚未登记**（`drill_record` 12 列 · `goods_sku.BaselineStock` · `seckill_activity.BaselineSoldStock` · 块 6 六表 `Source` · `order.StockDeducted`）—— 按 `db-migration.md` 注意 21/22 的口径**归块 7 commit 9（收口）**；本文件末 **§16** 只登 commit 8b 那一段（`observed_event` + 4 处 `BatchId`），**§17**（commit 9 补登）登 ⑰ / ⑱ 两段。
> ⚠️ **块 2 还改了「两张既有表」，但加列不增表**：`user` +8 列（`:396-404`，封禁 3 + 降级 5）、`goods` +2 列（`:407-409`，`ShelfState` / `DelistedAt`）—— 故表数**只 +1**；那 10 列落在 §6.3 与 §6.10 的既有小节里（**不另开节**）。
> ⚠️ **块 3 再改「两张既有表」+ 净增 2 张**：`goodstype` +3 列（`:449-451`，`ParentId` / `Sort` / `State`）**并换唯一键**（`:454-455`，`uk_goodstype_GoodType` → `uk_goodstype_parent_name (ParentId, GoodType)`）、`goods` +5 列（`:461-467`，`TypeId` / `DetailTemplateId` / `SpecTemplateId` / `AfterSaleTemplateId` / `DeletedAt`）；两张新表 `goods_template` / `goods_audit`。⇒ 表数 **16 → 18**，索引 **15 → 16**（换代一换一 + 净增 `idx_goodstype_parent_sort`），**非主键 UNIQUE 仍 3 个**。既有两表的加列落在 §6.4（`goodstype`）/ §6.3（`goods`）。 ⚠️ **块 3 就地改造（#8，2026-10-10）—— 本文件首次出现「删表 + 删列」**：新建**通用表** `template`（`:556-569`，`Type` 区分用途）；**物理删** `goods_template` + `notification_template`（用户明确批准，见 `db-migration.md` 注意 11）；`goods` **加** 3 段快照文本（`:588-607`，`Detail` / `Spec` / `AfterSale`）**删** 3 个模板引用 Id（`:611-630`，`DetailTemplateId` / `SpecTemplateId` / `AfterSaleTemplateId` —— 那三列**恒 NULL、全仓零写入**，见注意 10 末句）；`goods_audit` 加同 3 列（`:636-655`）。⇒ 表数 **18 → 17**、索引 **16 → 16**（净 0）、**非主键 UNIQUE 仍 3 个**（第三个从 `uk_notification_template_key` 换成 `uk_template_type_name`）。语义变化：商品侧由「**引用**模板 Id」（改模板 ⇒ 商品联动变）改为「**快照**」（选模板 → 填入 → **可改** → 存**最终文本** ⇒ 改模板**不影响**已上架商品）。
> ⚠️ **2026-10-09 更正 —— 原写 12 是漏登**：先后漏了批 A 的 `admin_audit`；I2 登记时一并补齐（同一缺口见 `db-migration.md` 注意 8）。本文件此前所有「12 张表」的字面与据此推导的计数（索引张数、UNIQUE 个数）也随之更新。
> `.sql` 末尾另有一段 S1a 追加的 Migration 块（`:236-245`，2026-09-30），**只含一条 `CREATE UNIQUE INDEX`，不建表**。
> 全部为 `ENGINE=InnoDB`、`utf8mb4_0900_ai_ci`。
>
> ⚠️ 该 `.sql` 行尾是 CRLF（**738 个 `\n` / 738 个 `\r`，0 个裸 CR** —— 2026-10-10 **C3 块 4〈订单块〉**追加一块后**重新实测**〔LF 738 / CR 738 / 裸 CR 0；同次实测行首 `CREATE TABLE` 共 20 条〕；上一版为 680 / 680〔块 3 就地改造 #8 追加一块后，`CREATE TABLE` 19 条〕，更前为 409 / 409〔块 2 追加一块后〕，再上为 345 / 345〔I2 追加两块后〕、245 / 246，S1a 前原为 235 / 236）。⚠️ **更新（2026-10-11，可视化段 commit 0–4 追加四个块后重新实测）**：**1219 行 / LF 1219 / CR 1219 / 裸 CR 0**、行首 `CREATE TABLE` **24 条**。用会把行尾归一化的编辑器改它，diff 会变成整份文件重写 —— 改前先确认 diff 只有预期行。⚠️ **更新（2026-10-12，C3 块 5〈营销块〉追加四个块后重新实测）**：**1505 行 / LF 1505 / CR 1505 / 裸 CR 0**、行首 `CREATE TABLE` **32 条**（+8，见 §14.1）。⚠️ **更新（2026-10-13，C3 块 6〈内容+消息块〉追加一个块后重新实测）**：**1634 行 / LF 1634 / CR 1634 / 裸 CR 0**、行首 `CREATE TABLE` **38 条**（+6，见 §15.1）。⚠️ **更新（2026-10-14，C3 块 7 追加三个块后重新实测）**：**1918 行 / LF 1918 / CR 1918 / 裸 CR 0**、行首 `CREATE TABLE` **40 条**（⑰ 加 `drill_record`、⑲ 加 `observed_event`；⑱ 只加列、不加表 ⇒ 共 +2，见 §16.3）。
>
> **完整字段清单 / 索引基线 / 外键关系见 §6–§9**（2026-09-30 补齐，逐列逐索引以 `.sql` 为准）。⚠️ **C3 块 4 的 `cart` 与 `order.Quantity` 见文件末 §11** —— 本节群**刻意不含 `cart`**，理由（零漂移）见上。

## 1. 表清单 {#db-1}

| 表名 | 用途 | 主键 | 关键字段 | 并发相关字段 | 索引 | 证据 |
|---|---|---|---|---|---|---|
| `customerimage` | 客户头像 | `UserName` | CustomerId, image | — | 仅主键 | `:23-28` |
| `friend` | 好友关系（含待处理申请） | `id` (自增) | customerid, friendid, if_friend | **`if_friend` 状态位**：0=待处理 1=已通过 | 仅主键 | `:38-44` |
| `goods` | 商品（按型号一行） | `Id` (自增) | MerchantId, GoodName, Price, GoodImg, GoodType, ProductCode, GoodTypeNew, **ShelfState, DelistedAt**（C3 块 2 加列，`:407-409`）, **TypeId, DeletedAt**（C3 块 3 加列，`:461-467`）, **Detail, Spec, AfterSale**（C3 块 3 **就地改造**加列，`:588-607`） | **`ShelfState`**：1=在架 / 0=已下架（块 2 新增；降级冻结只置 0、不删行；买方过滤随块 3）；**`DeletedAt`**：非 NULL = 违规删除（块 3 逻辑删除）；**`Detail` / `Spec` / `AfterSale`**：3 段**快照文本**（`mediumtext`），块 3 就地改造取代原 `DetailTemplateId` / `SpecTemplateId` / `AfterSaleTemplateId` —— **那 3 列已删**（`.sql:611-630`）；商品存**最终文本**而**非**模板 Id ⇒ **改模板不影响**已上架商品 | 仅主键 | `:54-64` |
| `goodstype` | 商品类型字典 | `Id` (自增) | GoodTypeNew, GoodType, **ParentId, Sort, State**（C3 块 3 加列，`:449-451`） | **`ParentId`**：0 = 一级类目（自引用 `Id`）；**`State`**：1 启用 / 0 停用（逻辑废弃） | **主键 + `idx_goodstype_parent_sort(ParentId, Sort)`（普通）+ `uk_goodstype_parent_name(ParentId, GoodType)`（UNIQUE，块 3 换代）** —— ⚠️ 原 `uk_goodstype_GoodType(GoodType)` **已被删**（2026-10-04 #11 → 2026-10-10 #7 换代） | `:74-79` |
| `login` | 登录凭证与角色 | `UserName` | Password, **Role**(1=客户 2=商家), Id | — | 仅主键 | `:89-95` |
| `merchantimage` | 商家头像 | `UserName` | MerchantId, image | — | 仅主键 | `:105-110` |
| `message` | 聊天消息 | `id` (自增) | from_id, from_type, to_id, to_type, content, create_time | — | 主键 + **`idx_from_to(from_id,to_id)`** + **`idx_create_time(create_time)`** | `:120-131` |
| `order` | 订单 | `Id` (自增) | **`OrderId`**(业务单号), MerchantId, CustomerId, Price, ProductInformation, Images, CreateTime, SettlementDate | **`PayIf`/`DeliveryIf`/`ReceiveIf` 三状态位**（CAS 更新）；`SettlementDate` 被三处操作覆写 | 主键 + **`uk_order_orderId(OrderId)`**（UNIQUE，S1a `:245`） | `:141-155` |
| `productsales` | 商品销量统计 | `Id` (自增) | ProductCode, MerchantId, GoodName | **`OrderVolume`：恒为 0，全项目从未更新** | 仅主键 | `:165-172` |
| `user` | 用户档案 | `username` | usertype, customerid, merchantid, gender, age, email, selfintroduction, address, **banned, banReason, bannedAt**（封禁 3）、**DowngradeState, ScheduledDowngradeAt, DowngradeReason, FrozenAt, FreezeUntil**（降级 5）〔C3 块 2 加列，`:396-404`〕 | **`banned`** 状态位（1 封 / 0 正常；登录闸与已登录会话校验都查）；**`DowngradeState`** 状态机 none/scheduled/frozen；`FreezeUntil` = 冻结保留截止 | 仅主键 | `:182-193` |
| `user_conversation_read` | 会话最后已读时间 | **复合** `(user_id, user_type, other_id, other_type)` | last_read_time | 复合主键天然支撑 `ON DUPLICATE KEY UPDATE` upsert | 主键即索引 | `:203-210` |
| `order_state_transition` | 订单状态流转时间线（B3 新增；C3 时间线视图的数据源） | `Id`（自增，**bigint**） | OrderId, Action, FromState, ToState, OccurredAt, created_at, updated_at | **append-only**；同单内排序用 `OccurredAt`，**不是 `Id`**（落库是 4 线程并发 INSERT，`Id` 在 INSERT 时才分配）；`updated_at` 恒等于 `created_at` | 主键 + **`idx_order_state_transition_order(OrderId, OccurredAt)`** | `:224-235` |
| `admin_audit` | 管理端审计日志（C3 批 A 新增；「权限与审计」页数据源，横切所有写操作） | `Id`（自增，**bigint**） | ActorId, ActorRole, Action, TargetType, TargetId, BeforeJson, AfterJson, OccurredAt | **append-only**（只 INSERT，永不 UPDATE / DELETE）；`ActorId` 是**业务号**不是登录名；快照列是 MySQL `json` | 主键 + **`idx_admin_audit_occurred(OccurredAt)`** + **`idx_admin_audit_actor(ActorId, OccurredAt)`** + **`idx_admin_audit_target(TargetType, TargetId, OccurredAt)`** | `:264-292` |
| `notification` | 系统通知（C3 块1-I2 新增；平台 → 个人单向投递，**每收件人一行**） | `Id`（自增，**bigint**） | BatchId, ToId, ToType, EventType, Title, Content, TargetType, TargetId, ReadAt, CreatedAt | `ReadAt NULL` = 未读（唯一判据）⇒ 标记已读会 UPDATE，**非** append-only；`TargetId` 是**软引用**（**无 FK**，见 §8） | 主键 + **`idx_notification_inbox(ToId, ToType, CreatedAt)`** + **`idx_notification_unread(ToId, ToType, ReadAt)`** + **`idx_notification_batch(BatchId)`** | `:294-327` |
| ~~`notification_template`~~ | 系统通知模板（C3 块1-I2 新增；`c3-modules.md §6` 的「消息模板管理」）—— ⚠️ **已于 2026-10-10 #8 物理删**，行搬进 `template`（详见 §6.15） | ~~`Id`（自增，**bigint**）~~ | ~~TemplateKey, TitleTemplate, BodyTemplate, Enabled~~ | 现无此表；`Enabled` 停用语义由 `template.Enabled` 承接 | ~~主键 + `uk_notification_template_key(TemplateKey)`（UNIQUE）~~ | `:329-345`（建表语句仍在 .sql 原地；由 `.sql:680` 的 DROP 中和） |
| `merchant_application` | 商家入驻申请（C3 块 2〈用户块〉新增；入驻审核数据源；一次申请一行，同一账号可多次） | `Id`（自增，**bigint**） | Username, ShopName, LicenseNo, ContactName, ContactPhone, ShopAddress, LicenseImage, Status, RejectReason, BatchId, SubmittedAt, ReviewedBy, ReviewedAt | **`Status`**：pending / approved / rejected（小写字符串码，与 CAS 的 `WHERE Status=?` 字面一致）；`BatchId` 给通知/审计一个跨重导稳定的 TargetId；审核会 UPDATE ⇒ 非 append-only；`Username` 软引用无 FK | 主键 + **`idx_merchant_application_status(Status, SubmittedAt)`** + **`idx_merchant_application_user(Username, SubmittedAt)`** + **`idx_merchant_application_batch(BatchId)`** | `:369-390` |
| `template` | **通用模板库**（C3 块 3 **就地改造** #8 新增；`Type` 区分用途 —— 覆盖 `goods.detail` / `goods.spec` / `goods.afterSale` / `goods.reject` / `merchant.reject` / `merchant.approve` / `user.ban` / `notification.<原 TemplateKey>` / `comment.reply`〔块 6 预留〕。**取代** `goods_template` + `notification_template` 两张老表，两者已物理删） | `Id`（自增） | Type, Name, Title, Body, Enabled, Sort | **`Type`**：点分字符串码（**不建 DB ENUM**），`varchar(96)`；`Enabled` 1 启用 / 0 停用（逻辑废弃，不物理删）；`Body` 是 `mediumtext`（商品类 = **快照源**：选模板填入后可改，商品存最终文本；通知类 = 正文模板）；`(Type, Name)` 撞键即「该类型下已有同名模板」 | 主键 + **`uk_template_type_name(Type, Name)`**（UNIQUE）+ **`idx_template_type_enabled_sort(Type, Enabled, Sort)`** | `:556-569` |
| `goods_audit` | 商品提审历史（C3 块 3〈商品块〉新增；**提审只写本表**，管理员通过才写 `goods` 行；一次提审一行，可多次） | `Id`（自增，**bigint**） | MerchantId, BatchId, GoodName, Price, GoodType, GoodTypeNew, TypeId, ProductCode, GoodImg, **Detail, Spec, AfterSale**（块 3 就地改造加列，`:636-655`）, Status, RejectReason, GoodsId, ReviewedBy, SubmittedAt, ReviewedAt | **`Status`**：pending / approved / rejected（与 CAS 的 `WHERE Status=?` 字面一致）；审核会 UPDATE ⇒ 非 append-only；`GoodsId` 通过后回填（软引用无 FK）；`MerchantId` 软引用无 FK；**3 段快照必须随提审走** —— 通过时 `writeGoodsFromAudit` 用**本表行重建** `goods` 行，本表不带就会丢（`.sql:524` 块注释） | 主键 + **`idx_goods_audit_status(Status, SubmittedAt)`** + **`idx_goods_audit_merchant(MerchantId, SubmittedAt)`** + **`idx_goods_audit_batch(BatchId)`** | `:492-515`（建表块）+ `:636-655`（#8 加 3 列） |

种子数据交叉证据：`:176` 中 5 条 `productsales` 的 `OrderVolume` 全为 `0`，而 `:159` 的 `order` 表已有 6 条历史订单。

## 2. 索引现状 {#db-2}

**17 张表中 9 张有二级索引（共 20 个）**：`message` 2 个（`idx_from_to`、`idx_create_time`）、`order_state_transition` 1 个（`idx_order_state_transition_order`）、**`order` 1 个（`uk_order_orderId`，UNIQUE）**、**`goodstype` 2 个（`idx_goodstype_parent_sort` 普通 + `uk_goodstype_parent_name` UNIQUE，2026-10-10 #7 换代：原 `uk_goodstype_GoodType` 已删）**、**`admin_audit` 3 个**（批 A，非唯一）、**`notification` 3 个**（I2，非唯一）、**`merchant_application` 3 个**（块 2，非唯一）、**`goods_audit` 3 个**（块 3，非唯一）、**`template` 2 个（`uk_template_type_name` UNIQUE + `idx_template_type_enabled_sort` 普通，#8 新增）**。其余 **8 张表除主键外无任何索引**；全库**除主键外 3 个 UNIQUE**（`uk_order_orderId`，2026-09-30 S1a `:236-245`；**`uk_goodstype_parent_name`，2026-10-10 #7 `:455`〔换代自 `uk_goodstype_GoodType`〕**；**`uk_template_type_name`，2026-10-10 #8 `:567`**〔#8 前是 `uk_notification_template_key`，2026-10-09 I2 `:344`，**该表已删**〕；完整基线见 §7）。⚠️ **本行原写「共 16 个」是既有少算**（枚举里列了 `goods_template` / `goods_audit` 却漏进总数：2+1+1+2+3+3+1+3 = 16 少了块 3 两张表的 4 个）—— 本次按 **2026-10-10 实测**（VM 库 `information_schema.STATISTICS`，`INDEX_NAME <> 'PRIMARY'`）改为 **20**；⚠️ 与 #8 **无关**（#8 索引净增 0：−`goods_template` 1 − `notification_template` 1 + `template` 2）。⚠️ **可视化段（2026-10-11）净增 7 个索引 / 3 个非主键 UNIQUE**（`order_item` +3〔1 UNIQUE〕、`stats_daily` +2〔1 UNIQUE〕、`goods_sku` +2〔1 UNIQUE〕）⇒ 按 VM 库 `information_schema` 实测：**非主键索引 23 → 30、非主键 UNIQUE 4 → 7**（逐列见文件末 **§13.7**）。⚠️ 本段行首的「**17 张表**中 9 张有二级索引（共 **20** 个）」是 **17 张表口径**的旧实测（未含 `cart` 的 2 个与 `order_shipping_track` 的 1 个），**与 30 不矛盾也不相等** —— 引用总数时以 §13.7 的实测值 30 为准，本段只用于看「哪几列缺索引」。已知高频查询列缺索引：

| 查询位置 | SQL 条件 | 涉及表 | 现况 |
|---|---|---|---|
| `GoodsCacheService:81` | `login WHERE id = ?` | `login` | `Id` 无索引；`goodsSelectHome` 中对每个商品执行一次（读路径 B1 已搬到该 Service；原记 `G:188`） |
| `GoodsCacheService:146` | `goods WHERE MerchantId = ?` | `goods` | `MerchantId` 无索引（读路径 B1 已搬到该 Service；原记 `G:160`） |
| `OrderDao:117` | `order WHERE CustomerId = ?` | `order` | `CustomerId` 无索引（原记 `G:507`，再前 `G:434`；2026-10-02 随订单端点迁至 `OrderDao`） |
| `OrderDao:124` | `order WHERE MerchantId = ?` | `order` | `MerchantId` 无索引（原记 `G:525`，再前 `G:452`；同上） |
| `UserCacheService:81` | `user WHERE customerid = ? OR merchantid = ?` | `user` | 两列均无索引；且 `OR` 无法走单列索引（`userSelect` 读路径已搬到该 Service；原记 `M:331`）。⚠️ HEAD 中**同形 SQL 共 3 处**：另有 `FriendCacheService:151`（`friendList` 的逐行 enrich）与 `MessageDao.selectUserPublicInfo:174`（SQL 定义行；调用者 `MessageService.getUserInfoById:497`，私有辅助 —— 原记 `M:378`，三层第四笔 `8ed5fc1` 后已下沉，`MessageController` 内**已无 SQL**） |
| `orderPayIfUp` / `orderDeliveryIfUp` / `orderReceiveIfUp` + `OrderStateMachine.selectBitsSql()` | `order WHERE OrderId = ?` | `order` | ✅ **已消除（2026-09-30 S1a，`1e2fcfa`）** —— `uk_order_orderId` 一次解决索引与唯一性两面。**原状**：`OrderId` 无索引，且无 UNIQUE → 三个 CAS 端点与转移后读回每次都全表扫描。已登记 **TD-14**（`tech-debt.md:172`，含 2026-09-21 定性修正：本地 120 QPS 不可达属环境属性，索引代价须**同环境**对照举证）。**曾是** B2 fail-open + B3 并发热点的根：唯一性无约束、索引无兜底，一旦锁回落直行（`tech-debt.md:291` 三路 fail-open），就只剩应用层「先查后写」在守。⚠️ 唯一性现已由 DB 兜底，但**失败响应仍被 `orderAddLocked` 的 catch 吞成 200 + msg**（故 `A-S2a-1c` 数的是 msg 而非 5xx） |
| `GoodsCacheService.priceByTriple` 回源 | `goods WHERE MerchantId = ? AND GoodName = ? AND ProductCode = ?` | `goods` | 三元组无索引（B1 缓存回源路径，命中缺失即全表扫） |
| `GoodsDao.searchByKeyword`（`:193-207`；2026-10-02 由 `GoodsController.productSearch` 搬入） | `goods WHERE GoodType = ? AND (… OR GoodName LIKE '%x%')` | `goods` | `GoodType` 无索引；且 `LIKE '%x%'` 本身也无法走索引。⚠️ 该 SQL 还**无 schema 前缀**（`GoodsDao` 8 条里唯一一条），但这只影响可移植性，不改变「走不了索引」的结论 |
| 销量维护 | `productsales WHERE ProductCode = ? AND MerchantId = ?` | `productsales` | 无索引；亦无 `(ProductCode, MerchantId)` UNIQUE → 可写重复行 |
| 头像读取 | `customerimage WHERE CustomerId = ?` / `merchantimage WHERE MerchantId = ?` | `customerimage` / `merchantimage` | 两列均无索引 |
| 好友列表 | `friend WHERE customerid = ? OR friendid = ?` | `friend` | 两列均无索引；且 `OR` 无法走单列索引 |

另：`login.Password` 为明文存储（`application.properties` 中无加密配置，Controller 中无哈希逻辑）。

## 3. ⚠️ ddl-auto 与手写 DDL 的冲突 {#db-3}

| 项 | 内容 | 证据 |
|---|---|---|
| 配置 | `spring.jpa.hibernate.ddl-auto=validate`（2026-10-04 起；原 `update`） | `application.properties:29` |
| 依赖 | `spring-boot-starter-data-jpa` 已引入 | `pom.xml:51` |
| 手写 DDL | `mall_management_sql.sql`，**19** 个 `CREATE TABLE`（11 原始 + 迁移块 8：B3 / S1a 后 5 + 块 3 追加 2 + #8 新增 `template` 1），其中 `notification_template` / `goods_template` 两块由 #8 尾部 `DROP TABLE IF EXISTS` 中和 ⇒ **净 17 张表**（2026-10-10 实测）。⚠️ **2026-10-11 订正（可视化段后）**：**24** 个 `CREATE TABLE`（＋`order_item` / `stats_daily` / `goods_sku`）— 同样中和两块 ⇒ **净 22 张表**（VM 库实测，见 §13.7） | 全文件 |

**当前实际生效的是手写 `mall_management_sql.sql`。**
原因：全项目**不存在任何 `@Entity`**——既有的唯一一个 POJO 曾是 `mallmanagement_class/info.java`（仅 `@Data`/`@AllArgsConstructor`/`@NoArgsConstructor` 三个 lombok 注解、无 JPA 注解、0 引用），**该类已于 2026-10-02 笔 1 挪包时删除**（`01df702`）。Hibernate 无实体可映射，`ddl-auto` 当前是**空转**，不会修改任何表结构。⚠️ **结论不变但依据换了**：从「有一个无注解的实体类」变成「**一个实体类都没有**」——条文原写「info.java 是唯一实体类」，笔 1 之后该句本身已不成立（详见 `project-map.md` 与 `tech-debt.md` TD-01）。

**这是隐患而非当前故障**，风险在于：

1. **表结构漂移** —— ✅ **已于 2026-10-04 关闭**（TD-01 **`B` 方案**）：`ddl-auto` 由 `update` 改为 **`validate`** ⇒ 即便有人给某个类加上 `@Entity`，Hibernate 也**不再按实体定义反向 `ALTER TABLE`**。原文要防的是 `update` 模式**只增不减**（擅自加列但不删列、**静默**产生与 `mall_management_sql.sql` 不一致的表结构且无任何报错）—— 那正是 `validate` 关掉的那条。
2. **命名策略冲突** —— ⚠️ **口径已变：后果不是「列名被偷偷改掉」，而是「启动失败」**。本表结构用 `UserName`/`MerchantId` 等大驼峰列名，与 Hibernate 默认的 snake_case 命名策略不一致 ⇒ 一旦有人加 `@Entity`，`validate` 会因**映射与表不符**直接拒绝启动。这是**期望行为**：显式炸好过静默改表（同 §3 的处置口径）。
3. **脚本失去权威性** —— ⚠️ **原口径已不成立**：`update` 下两份定义并存、无法判断线上库以哪份为准；**改成 `validate` 后 Hibernate 只校验、不写入** ⇒ `mall_management_sql.sql` 是**唯一权威**。本条随 ① 一并关闭。

**已落地（TD-01 `B` 方案，2026-10-04）**：`spring.jpa.hibernate.ddl-auto` 已由 `update` 改为 **`validate`**（`application.properties:29`），由 `mall_management_sql.sql` 统一管理表结构。⚠️ **选 `validate` 而不是 `none`**：两者都不自动改表，但 `none` 在误加 `@Entity` 时**静默通过**、要等运行期某条 SQL 报错才暴露 —— 属「静默 / 断言落空」那一族（TD-15）。**依赖本身不删**（这是 `B` 方案的另一半，理由见 `tech-debt.md` TD-01）。

> 关于 `spring-boot-starter-data-jpa` 依赖本身的去留，属技术债而非表结构事实，已移入 `tech-debt.md`（TD-01）。

## 4. `order.SettlementDate` 语义 —— **待业务确认** {#db-4}

> ⚠️ **该列变更会影响 `historicalOrdersSelect` 的排序结果，修改前必须回归验证历史订单页面。**

> 本节只记录事实与可选方案，**不代为裁决**。语义归属是业务决定。业务侧决策单见 `business-decisions.md`（BD-01）。

### 4.1 三处写入位置 {#db-4-1}

| 操作 | 写入语句 | 证据 |
|---|---|---|
| 付款 | `SET PayIf = 1, SettlementDate = NOW()` | `OrderStateMachine:113` |
| 收货 | `SET ReceiveIf = 1, SettlementDate = NOW()` | `OrderStateMachine:115` |
| 发货 | `SET DeliveryIf = 1, SettlementDate = NOW()` | `OrderStateMachine:117` |

> **2026-09-30 坐标更正**：这三行原先硬编码在 `MallManagementGoodsController`（旧坐标 `G:524` / `G:546` / `G:568`）。B2 起三处 UPDATE 改由 `OrderStateMachine.updateSql()` **生成**（`SET` 子句来自 `setClause()`，`OrderStateMachine:125-127`；`setClause` 本体在 `:130-133`），上表行号即 `:113-118` javadoc 里逐条写明的三个字面量。⚠️ 该 javadoc 自己还记着**更早一版**的坐标（`原 G:556` / `G:584` / `G:612`）—— 两套旧坐标都不要再用。

### 4.2 覆盖行为 {#db-4-2}

三个操作**写的是同一列**，后发生者覆盖先发生者。正常流转（付款→发货→收货）下该列最终值 = **收货时间**；只付款未发货时 = **付款时间**。因此该列**无法稳定表示任何一个业务时间点**，取值取决于订单停在哪个状态。

另：该列在 `orderSelect`、`orderDeliveryIfSelect`、`orderDeliverySelect` 中**均未被查询**；仅 `historicalOrdersSelect`（路由 ~~`G:567`~~ → **`OrderController:88`**，2026-10-02 笔 2）取出用于历史订单展示（SQL ~~`G:575`~~ → **`OrderDao.selectHistoryByCustomer:148-152`**），并按 `SettlementDate DESC` 排序（~~`G:578`~~ → **`OrderDao:152`**）。

### 4.3 三种可选方案（**待业务确认**） {#db-4-3}

| 方案 | 做法 | 代价 |
|---|---|---|
| **A 不改** | 维持现状，将语义解释为「最后状态变更时间」 | 零改动；但列名与语义不符，历史订单排序含义模糊 |
| **B 只付款写** | 三处中仅付款保留赋值，发货/收货不再写该列 | 改动小；若业务需要发货/收货时间，仍需另加字段 |
| **C 拆三字段** | 拆为 `PayTime` / `DeliveryTime` / `ReceiveTime` 三列 | 语义最清晰；需改 DDL + 三处 UPDATE + 相关查询，改动最大 |

**决策依据应由业务方给出**：该列当前实际承担的是「历史订单展示与排序」（**`OrderDao:149`（SELECT 列）、`:152`（ORDER BY）**；原记 `G:575`、`G:578`），需先确认业务上到底要展示哪个时间点。

## 5. 待确认（其他） {#db-5}

- 全库无 CHECK 约束、无外键约束（`mall_management_sql.sql` 导入时关闭 `FOREIGN_KEY_CHECKS`，且 DDL 未定义外键）—— 引用完整性完全由应用层维护。

---

## 6. 完整字段清单（19 张表 —— ⚠️ **`cart` 不在本节**（§11）、**`order_shipping_track` 也不在**（§12）；逐列以 `.sql` 为准） {#db-6}

> 行号 = `mall_management_sql.sql` 真实行号。`约束` 列照抄 DDL；`备注` 列出 COMMENT（本库仅 `order_state_transition` 有列注释）与已核实的语义。
> 列级 `CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci` 在部分列上显式声明（下表标「显式」）、部分列省略 —— 因表默认值相同，**功能等价**，属声明噪音。

### 6.0 表级属性与种子数据基线 {#db-6-0}

| 表 | ENGINE | CHARSET | COLLATE | `AUTO_INCREMENT` | 种子行数 | P4 判据 |
|---|---|---|---|---|---|---|
| `customerimage` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 2 | — |
| `friend` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 6 | 4 | ✅ 期望 4 |
| `goods` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 26 | 5 | ✅ 期望 5 |
| `goodstype` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 12 | 5 | — |
| `login` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 5 | ✅ 期望 5 |
| `merchantimage` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 3 | — |
| `message` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 36 | 29 | — |
| `order` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 24 | 6 | ✅ 期望 6 |
| `productsales` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 28 | 5 | — |
| `user` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 5 | ✅ 期望 5 |
| `user_conversation_read` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 4 | — |
| `order_state_transition` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 0 | — |
| `admin_audit` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 2 ⚠️ | — |
| `notification` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 0 | — |
| ~~`notification_template`~~ **已删**（#8） | — | — | — | — | — | — |
| `merchant_application` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 0 | — |
| `template`（#8 新增，取代 `goods_template`） | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 0 ⚠️ | — |
| `goods_audit` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 0 ⚠️ | — |

> **18 张表的 ENGINE / CHARSET / COLLATE 完全一致**（含分节在 §11 的 `cart`：同为 `InnoDB` / `utf8mb4` / `utf8mb4_0900_ai_ci`，`.sql:720`），且逐字等于库级默认值（`:15` `CREATE DATABASE ... DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`）⇒ **没有任何表声明自己的字符集**，全套吃库级默认。
> `AUTO_INCREMENT` = DDL 中的现值（导入基线）。
> ⚠️ **后六张表（`admin_audit` / `notification` / `merchant_application` / `template` / `goods_audit` / `cart`）不属于「导入基线」** —— 它们是 `.sql` 末尾的 Migration 块，用 `IF NOT EXISTS` 追加、**块内不 DROP** ⇒ 全量重导**不清**它们（`admin_audit` 刻意保审计历史、`notification` 刻意保通知历史、`merchant_application` 刻意保入驻申请历史、`template` 刻意保模板、`goods_audit` 刻意保提审历史、`cart` 是**用户购物车**）。其行数**不是种子数据**：`admin_audit` 那 2 行是批 A 验收探针留下的 `goods.update`；其余均 **0**（⚠️ `admin_audit` = 2 与 `merchant_application` = 0 测于 **VM 库**；`template` = 0 / `goods_audit` = 0 为 **2026-10-10 实测**，见 `db-migration.md` 注意 11；`cart` = 0 为 **2026-10-10 实测**，见 `db-migration.md` 注意 12）。⚠️ 六张新表本身都用 `CREATE TABLE IF NOT EXISTS`（**幂等**）⇒ 全量重导路径上它们的行数**不清零**（`admin_audit` 的 2 行会留到重导之后）。**P4 判据不覆盖这六张**（只查 `order/goods/login/user/friend`）。
> ⚠️ **另有「两张基线表被改列」**：C3 块 2 —— `user` +8 列、`goods` +2 列；C3 块 3 —— `goodstype` +3 列、`goods` 再 +5 列；C3 块 3 就地改造（#8）—— `goods` **+3 段快照文本并 −3 个 `*TemplateId`**（净 0）、`goods_audit` +3 列。合计 `goods` 净 **+7 列**（+2 +5 +3 −3）。这两张**属于导入基线**且**会被重导清空重建**，但加列语句在 Migration 块里（`CREATE TABLE` 之后）⇒ 重导后仍由该块补上（见 §6.3 goods / §6.4 goodstype / §6.10 user）。⚠️ **#8 是本文件首次出现「删**基线表**的列」**（`goods` 的 3 个 `*TemplateId`，用户明确批准，见 `db-migration.md` 注意 11）。⚠️ **两条幂等口径不同**：块 2 / 块 3 的加列**不幂等**（MySQL 无 `ADD COLUMN IF NOT EXISTS`），重复执行报 Duplicate column（预期，见 `.sql:359-364`、`.sql:437-446`）；**#8 的加列 / 删列全部走 `information_schema` 守卫 + `PREPARE`**，**重复执行是空操作、不报错**（`.sql:536-538`）。
> **种子行数即 A3 的 P4 判据基线**：`ConcurrencyRunner.java:2521-2547`（**2026-10-02 实测**；原写 `:2404-2427`，2026-09-30 因 C 组升门禁的注释重写下移 17 行改为 `:2421-2444`，其后 B2 与 C 两段先后增补使本段**再下移 100 行**）只查 `order/goods/login/user/friend` 五张，期望 `6/5/5/5/4`，与本表**逐字一致**（其注释即写「`mall_management_sql.sql` 逐行计数」）。P4 的用途是「确认是刚导入过的干净实例，而非被上一轮污染过的库」⇒ 种子行数是**门禁输入而非装饰**，改种子数据会先让 P4 变红。

### 6.1 `customerimage`（`:23-28`） {#db-6-1}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `UserName` | char(20) 显式 | **PK**, NOT NULL | 应用层用户名 |
| `CustomerId` | char(20) 显式 | NOT NULL | 客户业务号 |
| `image` | varchar(500) 显式 | NOT NULL | 头像 URL（含硬编码 `http://localhost:8088/img/` 前缀） |

### 6.2 `friend`（`:38-44`） {#db-6-2}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=6` |
| `customerid` | varchar(50) | NOT NULL | 发起方业务号（**宽度与 `user.customerid` 的 char(20) 不同**） |
| `friendid` | varchar(50) | NOT NULL | 对方业务号 |
| `if_friend` | int | NOT NULL | 0=待处理 1=已通过。**语义只写在本文件，DDL 无 CHECK/默认值** |

### 6.3 `goods`（`:54-64`） {#db-6-3}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=26` |
| `MerchantId` | char(20) | NOT NULL | |
| `GoodName` | char(40) | NOT NULL | 比 `productsales.GoodName` 宽一倍 |
| `Price` | **double** | NOT NULL | 与 `order.Price` 的 float 不同型 |
| `GoodImg` | char(100) 显式 | NOT NULL | |
| `GoodType` | char(20) | NULL, DEFAULT NULL | 类型名（文本，非外键） |
| `ProductCode` | char(40) | NULL, DEFAULT NULL | |
| `GoodTypeNew` | int | NULL, DEFAULT NULL | 类型 ID；**与 `goodstype.GoodTypeNew` 的 `NOT NULL DEFAULT '0'` 不一致** |
| `ShelfState` | tinyint(1) | NOT NULL DEFAULT 1 | **C3 块 2 加列**（`:408`）。1=在架 / 0=已下架。⚠️ 既有「下架」端点 `/goods/goodsDe` 是**真 DELETE**；本列让下架**可逆**（降级冻结只置 0、不删行）。买方过滤随块 3 |
| `DelistedAt` | datetime(3) | NULL, DEFAULT NULL | **C3 块 2 加列**（`:409`）。下架时刻；在架为 NULL |
| `TypeId` | int | NULL, DEFAULT NULL | **C3 块 3 加列**（`:462`）。类目 Id（软引用 `goodstype.Id`）；唯一键换代连带 —— 二级筛选改按 Id 走。迁移块内按名字回填既有行（`:471`） |
| `Detail` | **mediumtext** | NULL, DEFAULT NULL | **C3 块 3 就地改造（#8）加列**（`:588-593`）。详情**快照文本**（提审通过时由 `goods_audit.Detail` 原样写回）；**取代**原 `DetailTemplateId` —— 该列**已删**（`:611-616`） |
| `Spec` | **mediumtext** | NULL, DEFAULT NULL | **C3 块 3 就地改造（#8）加列**（`:595-600`）。规格**快照文本**，约定**行内 `键=值`**（下游若要解析须按此约定）；原 `SpecTemplateId` **已删**（`:618-623`） |
| `AfterSale` | **mediumtext** | NULL, DEFAULT NULL | **C3 块 3 就地改造（#8）加列**（`:602-607`）。售后**快照文本**；原 `AfterSaleTemplateId` **已删**（`:625-630`） |
| `DeletedAt` | datetime(3) | NULL, DEFAULT NULL | **C3 块 3 加列**（`:466`）。违规删除时刻；正常为 NULL（**逻辑删除，不物理删**，规则 v2） |

### 6.4 `goodstype`（`:74-79`） {#db-6-4}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=12` |
| `GoodTypeNew` | int | **NOT NULL DEFAULT '0'** | 与 `goods.GoodTypeNew` 可空不同 |
| `GoodType` | char(20) | NOT NULL，**UNIQUE `uk_goodstype_parent_name(ParentId, GoodType)`**（2026-10-10 #7 **换代**，`mall_management_sql.sql:455`） | 原全局唯一 `uk_goodstype_GoodType(GoodType)`（2026-10-04 #11）**已删**（`:454`）⇒ 键收窄为「**每父级下不重名**」（允许「电子用品>配件」与「服装>配件」并存）。⚠️ 与 `goods.GoodType` **无 FK**（本库除 3 个 `uk_*` 外没有任何约束，见 §8） |
| `ParentId` | int | **NOT NULL DEFAULT 0** | **C3 块 3 加列**（`:449`）。父类目 Id：0 = 一级类目（自引用 `goodstype.Id`；模型不限深度，UI 只做二级） |
| `Sort` | int | **NOT NULL DEFAULT 0** | **C3 块 3 加列**（`:450`）。同级排序（升序）；0 = 默认 |
| `State` | tinyint(1) | **NOT NULL DEFAULT 1** | **C3 块 3 加列**（`:451`）。1 启用 / 0 停用（逻辑废弃，不物理删） |

### 6.5 `login`（`:89-95`） {#db-6-5}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `UserName` | char(20) 显式 | **PK**, NOT NULL | 应用层账号 |
| `Password` | char(20) 显式 | NOT NULL | **明文**（种子 5 条见 `:99`） |
| `Role` | int | NOT NULL | 1=客户 2=商家 |
| `Id` | char(20) 显式 | NOT NULL | 客户/商家业务号。**无唯一约束** → 同一业务号可对多条账号 |

> 本表**无自增列**。`login.Id` 与 `user.customerid` / `user.merchantid` 的对应关系**由应用层按 `Role` 择一**，DB 无约束（见 §8）。

### 6.6 `merchantimage`（`:105-110`） {#db-6-6}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `UserName` | char(20) 显式 | **PK**, NOT NULL | |
| `MerchantId` | char(20) 显式 | NOT NULL | |
| `image` | varchar(500) 显式 | NOT NULL | |

### 6.7 `message`（`:120-131`） {#db-6-7}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=36` |
| `from_id` | varchar(50) | NOT NULL | |
| `from_type` | varchar(10) | NOT NULL | merchant / customer |
| `to_id` | varchar(50) | NOT NULL | |
| `to_type` | varchar(10) | NOT NULL | |
| `content` | text | NOT NULL | **无长度约束** |
| `create_time` | datetime | NOT NULL | 与 `user_conversation_read.last_read_time` 同型，可直接比较 |

### 6.8 `order`（`:141-155`；⚠️ C3 块 4 已加 `Quantity` —— `.sql:730-738`，`int NOT NULL DEFAULT 1`，存量 6 行回填 1，**不增表、不改行数**；⚠️ C3 块 4 **补丁**又加 **7 列**（支付 3 / 物流 3 / 收货 1，全 `DEFAULT NULL`）—— `.sql:769-796`，理由见 §12；**下表刻意未加行**，见文件末 §11 / §12） {#db-6-8}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=24`（内部主键，业务不使用） |
| `MerchantId` | **varchar(40)** | NOT NULL | 比 `goods.MerchantId` 的 char(20) 宽 |
| `CustomerId` | **varchar(40)** | NOT NULL | 比 `user.customerid` 的 char(20) 宽 |
| `OrderId` | varchar(40) | NOT NULL | **业务单号**。原为「既无唯一约束也无索引」（前提 A 的出处）—— **2026-09-30 S1a 已加 UNIQUE `uk_order_orderId`**（`:245`），前提 A 的 DB 面由此消解。与 `order_state_transition.OrderId` 逐字同型 |
| `Price` | **float** | NOT NULL | 与 `goods.Price` 的 double 不同型 |
| `ProductInformation` | varchar(500) | NOT NULL | 冗余快照文本（如 `222:IJ-555；222:IF-55`） |
| `Images` | varchar(100) | NOT NULL | |
| `PayIf` | int | NOT NULL | CAS 状态位 0/1，**无 CHECK**（可被 `orderAdd` 传入任意 int） |
| `DeliveryIf` | int | NOT NULL | 同上 |
| `ReceiveIf` | int | NOT NULL | 同上 |
| `CreateTime` | **varchar(50)** | NOT NULL | 时间以字符串存储 |
| `SettlementDate` | **varchar(50)** | NULL, DEFAULT NULL | 被付款/发货/收货三处覆写（BD-01，见 §4） |

### 6.9 `productsales`（`:165-172`） {#db-6-9}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=28` |
| `ProductCode` | char(40) | NOT NULL | 与 `goods.ProductCode` 同型 ✓ |
| `MerchantId` | char(20) | NOT NULL | 与 `goods.MerchantId` 同型 ✓ |
| `GoodName` | **char(20)** | NOT NULL | **比 `goods.GoodName` 的 char(40) 窄 20** → 21–40 字符名写此表报 **Error 1406 (Data too long)**，**不是静默截断**（本机 8.0.32 实测 `@@sql_mode` 含 `STRICT_TRANS_TABLES`；CI 用 `mysql:8.4` 未覆写 sql_mode，默认同样是严格）。⚠️ 但 `.sql` 导入会话是**例外**：`:13` 把整个 sql_mode 覆写为 `NO_AUTO_VALUE_ON_ZERO`（**非严格**），至 `:216` 才恢复 ⇒ 若种子数据本身超长会**静默截断**。**TD 候选，尚未登记** |
| `OrderVolume` | int | NULL, DEFAULT NULL | 种子数据恒为 0（`:176`），全项目从未 UPDATE |

### 6.10 `user`（`:182-193`） {#db-6-10}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `username` | char(20) 显式 | **PK**, NOT NULL | |
| `usertype` | char(20) 显式 | NULL, DEFAULT NULL | 中文取值「客户」/「商家」（与 `login.Role` 的 1/2 是两套编码） |
| `customerid` | char(20) 显式 | NULL, DEFAULT NULL | |
| `merchantid` | char(20) 显式 | NULL, DEFAULT NULL | |
| `gender` | char(20) 显式 | NOT NULL | **无默认值** → INSERT 必须给 |
| `age` | int | NOT NULL | **无默认值** |
| `email` | char(40) | NULL, DEFAULT NULL | |
| `selfintroduction` | varchar(100) | NULL, DEFAULT NULL | |
| `address` | varchar(20) | NULL, DEFAULT NULL | **宽度 20 偏窄**（种子数据已达 20 字符） |
| `banned` | tinyint(1) | NOT NULL DEFAULT 0 | **C3 块 2 加列**（`:397`）。1=封 / 0=正常；**登录闸与已登录会话校验都查这列** |
| `banReason` | varchar(255) | NULL, DEFAULT NULL | **块 2 加列**（`:398`）。封禁理由（`banned=1` 时有值） |
| `bannedAt` | datetime(3) | NULL, DEFAULT NULL | **块 2 加列**（`:399`）。封禁时刻；未封为 NULL |
| `DowngradeState` | varchar(16) | NOT NULL DEFAULT 'none' | **块 2 加列**（`:400`）。商家降级状态机：`none` / `scheduled`（通知期）/ `frozen`（已降级冻结） |
| `ScheduledDowngradeAt` | datetime(3) | NULL, DEFAULT NULL | **块 2 加列**（`:401`）。计划降级生效时刻（= 发起时刻 + 1 天通知期） |
| `DowngradeReason` | varchar(255) | NULL, DEFAULT NULL | **块 2 加列**（`:402`）。降级原因。⚠️ 这是**给商家看的**（会出现在降级通知与 `/user/me`），填写时当「会公开」写 |
| `FrozenAt` | datetime(3) | NULL, DEFAULT NULL | **块 2 加列**（`:403`）。实际冻结（降级生效）时刻 |
| `FreezeUntil` | datetime(3) | NULL, DEFAULT NULL | **块 2 加列**（`:404`）。冻结保留截止（= `FrozenAt` + 30 天）；此后重新申请通过需人工确认才恢复 |

> 本表**无自增列**。`customerid` / `merchantid` **均无唯一约束**。
> ⚠️ **块 2 的 8 列会随「唯一一条 `SELECT *`」外泄**：`UserDao.selectUserByUsername` 是全仓唯一 `SELECT *`，它出现在 `/user/me` 与 `getUserInfo` 的返回里 ⇒ 上述 8 列都会返回给前端。**已裁决：不剥**（用户该知道自己被封/被降级；`DowngradeReason` 本就是给商家看的原因）。见 `.sql:392-395`。

### 6.11 `user_conversation_read`（`:203-210`） {#db-6-11}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `user_id` | varchar(50) | **复合 PK(1/4)**, NOT NULL | |
| `user_type` | varchar(10) | **复合 PK(2/4)**, NOT NULL | |
| `other_id` | varchar(50) | **复合 PK(3/4)**, NOT NULL | |
| `other_type` | varchar(10) | **复合 PK(4/4)**, NOT NULL | |
| `last_read_time` | datetime | NOT NULL | |

> 全库**唯一**的复合主键。PK 即索引，无其他索引。**无自增列**。

### 6.12 `order_state_transition`（`:224-235`，B3 新增） {#db-6-12}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | **bigint** | **PK**, NOT NULL AUTO_INCREMENT | COMMENT：不保证同单内与发生顺序一致（4 线程并发 INSERT，`Id` 在 INSERT 时才分配）——同单排序请用 `OccurredAt` |
| `OrderId` | varchar(40) | NOT NULL | COMMENT：对应 `order`.OrderId（**无 FK**，见 §8） |
| `Action` | varchar(16) | NOT NULL | COMMENT：动作 tag `create/pay/deliver/receive` |
| `FromState` | varchar(40) | NOT NULL | COMMENT：出生为 `NONE`；越界取值写 `UNCLASSIFIED` |
| `ToState` | varchar(40) | NOT NULL | COMMENT：转移后状态名（最长 `ILLEGAL_DELIVERED_RECEIVED_UNPAID`，33 字符） |
| `OccurredAt` | **datetime(3)** | NOT NULL | COMMENT：在订单锁临界区内取；与 `created_at` 之差即队列延迟 |
| `created_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP | COMMENT：行落库时刻 |
| `updated_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP **ON UPDATE CURRENT_TIMESTAMP** | 本表 append-only，恒等于 `created_at`；为规则 8 保留 |

> ⚠️ **2026-10-09 更正**：本句原文「全库**唯一**带列 COMMENT 的表；也是唯一用 `datetime(3)` 的列」**已不成立** —— 批 A 的 `admin_audit` 与 I2 的 `notification` / `notification_template` **三张表每一列都带 COMMENT**，且 `admin_audit.OccurredAt`、`notification.ReadAt`、`notification.CreatedAt` 同用 `datetime(3)`。`order_state_transition` 是**最早**这样做的表，**不是唯一**。

### 6.13 `admin_audit`（`:264-292`，C3 批 A 新增） {#db-6-13}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | **bigint** | **PK**, NOT NULL AUTO_INCREMENT | 自增主键 |
| `ActorId` | varchar(50) | DEFAULT NULL | 操作者业务号（会话里的 `customerid`/`merchantid`，**不是**登录名）；认不出身份为 NULL |
| `ActorRole` | int | DEFAULT NULL | 取自 `login.Role`：1 客户 / 2 商家 / 3 管理员；认不出为 NULL |
| `Action` | varchar(64) | NOT NULL | 动作 tag，形如 `goods.update`（与 admin 域 `AuditActions` 常量一一对应） |
| `TargetType` | varchar(32) | NOT NULL | 被改对象类型，形如 `goods` |
| `TargetId` | varchar(128) | NOT NULL | 被改对象标识，形如 `MerchantId:ProductCode`（⚠️ 与 `notification.TargetId` 不同：这里是**拼接串**、且 NOT NULL） |
| `BeforeJson` | **json** | DEFAULT NULL | **改前**快照；新增类动作（无改前态）为 NULL |
| `AfterJson` | **json** | DEFAULT NULL | **改后**快照 |
| `OccurredAt` | **datetime(3)** | NOT NULL DEFAULT CURRENT_TIMESTAMP(3) | 动作发生时刻（毫秒精度，同秒内可排序） |
| `created_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP | 行落库时刻 |
| `updated_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP **ON UPDATE CURRENT_TIMESTAMP** | append-only，恒等于 `created_at`；为规则 8 保留 |

> 索引：主键 + `idx_admin_audit_occurred(OccurredAt)` + `idx_admin_audit_actor(ActorId, OccurredAt)` + `idx_admin_audit_target(TargetType, TargetId, OccurredAt)`。
> ⚠️ 两条读法坑（`slot-registry.md §十四 14-4` 实测）：① `json` 列**原生读法会带回外层引号**，读出须 `CAST(... AS CHAR)`；② 存的是**数组**（单行 = 单元素数组 `[{"Id":20,"Price":5556.0,…}]`），前端 diff 要**先拆单元素数组**（多元素不拆 —— 那是批量写、无「同一实体的改前改后」语义）。

### 6.14 `notification`（`:294-327`，C3 块1-I2 新增） {#db-6-14}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | **bigint** | **PK**, NOT NULL AUTO_INCREMENT | 自增主键 |
| `BatchId` | varchar(36) | NOT NULL | 本次投递批次（UUID）；一次群发/公告的扇出各行**共享同一值**，供管理端「通知历史」按次归组；**单发也生成一个** |
| `ToId` | varchar(50) | NOT NULL | 收件人业务号（`user.customerid` / `user.merchantid`），与会话存号口径一致 |
| `ToType` | varchar(16) | NOT NULL | `customer` / `merchant`（英文，对齐 `message.from_type`） |
| `EventType` | varchar(64) | NOT NULL | 事件类型 code，形如 `comment.reply` / `goods.audit.rejected`；⚠️ **后端不校验白名单，未知 code 也照落**（21 条登记簿见 `notification/NotificationEvents.java`） |
| `Title` | varchar(128) | NOT NULL | 通知标题 |
| `Content` | varchar(1024) | NOT NULL | 通知正文（列名对齐既有 `message.content`） |
| `TargetType` | varchar(32) | DEFAULT NULL | 关联对象类型（`goods` / `user` / `comment`…）；无关联为 NULL。⚠️ **软引用，无 FK** |
| `TargetId` | varchar(128) | DEFAULT NULL | 关联对象标识；无关联为 NULL。⚠️ **软引用，无 FK**；前端容错：对象不存在 → 显示「（相关对象已删除）」，**不崩、不空白** |
| `ReadAt` | **datetime(3)** | DEFAULT NULL | 已读时刻；**NULL = 未读（唯一判据）** |
| `CreatedAt` | **datetime(3)** | NOT NULL DEFAULT CURRENT_TIMESTAMP(3) | 通知产生时刻（毫秒精度，收件箱排序用；**DB 存 UTC**） |
| `created_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP | 行落库时刻 |
| `updated_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP **ON UPDATE CURRENT_TIMESTAMP** | ⚠️ 本表**非** append-only —— 标记已读会 UPDATE，故它**随 `ReadAt` 变化**（与 `admin_audit` 相反） |

> 索引：主键 + `idx_notification_inbox(ToId, ToType, CreatedAt)`（收件箱分页，前两列等值 ⇒ 第三列有序，反向扫满足 `ORDER BY CreatedAt DESC` 不 filesort）+ `idx_notification_unread(ToId, ToType, ReadAt)`（未读计数，三端 5s 轮询的**最热查询**，可 covering count 不回表）+ `idx_notification_batch(BatchId)`（管理端历史按批归组）。

### 6.15 ~~`notification_template`~~ —— **已物理删（2026-10-10 #8）**（原 `:329-345`，C3 块1-I2 新增） {#db-6-15}
> ⚠️ **本表已于 2026-10-10 被物理删除**（用户明确批准 —— 本库规则 v2 禁止删表；批件逐字见 `db-migration.md` 注意 11）。原列：`Id` bigint PK · `TemplateKey` varchar(64) UNIQUE(`uk_notification_template_key`) · `TitleTemplate` varchar(255) · `BodyTemplate` varchar(1024) · `Enabled` tinyint(1) · `created_at` / `updated_at`。
> 行已**搬迁**进 §6.17 的 `template`：`Type = 'notification.' + TemplateKey` · `Name = TemplateKey` · `Title = TitleTemplate` · `Body = BodyTemplate` · `Enabled` 原样 · `Sort = 0`（`.sql:657-673`）；撞 `(Type,Name)` 唯一键时 **`ON DUPLICATE KEY UPDATE Id = template.Id` ⇒ 不覆盖已编辑过的模板**。
> ⚠️ **建表语句仍留在 `.sql:329-345` 原地不动**（零漂移手段），净行为由 `.sql:680` 的 `DROP TABLE IF EXISTS` 兑现 ⇒ 判据是「**导入后本表不存在**」，与「物理删」等价。
> ⚠️ 块 1 立下的口径**不变**：通知类模板**仍不接线** —— 无占位符渲染、`send` 仍收**字面** title/body（要定渲染语法与转义口径，返工风险大；I2 方案 §七·未决 3）。#8 只让它**有表、有 CRUD**（现为 `template` 表的 `notification.*` 行）。
> ⚠️ **为什么删而不是留**：两套模板表语义重复（通知侧 `notification_template` / 商品侧 `goods_template`），留一张孤儿表 = 留一份必然漂移的 schema。合并后 `notification.*` 只是 `template` 里的一族 `Type`。
> ⚠️ **`template.Type` 是 `varchar(96)` 而不是 32** —— 搬迁值是 `'notification.' + TemplateKey`，真实最长 key `merchant.downgrade.scheduled`(28) ⇒ **41 字符**，`varchar(32)` 会 **1406 Data too long**；`.sql:571-584` 的加宽守卫即为此，见 `db-migration.md` 注意 11。
> ⚠️ 本表是 #8 **之前**「非主键 UNIQUE 3 个」里的第 3 个（`uk_notification_template_key`）；#8 后该位置由 `template.uk_template_type_name` 接替 —— 二者同属「**新建表顺手给的唯一性**」而非「认领一条旧前提」，见 §10.1 与 §7 附注。
> ⚠️ **CRUD 端点一并搬走**（表没了，旧端点集就是「指向已删表的 500 / 空集」）：`POST /admin/notify/template/{list,save}` **已删**，改走通用 `POST /admin/template/*` —— 通知页只带 `TypePrefix='notification.'`。⚠️ 口径变化：通知模板 CRUD 旧路径**从未挂审计**，合并后统一挂 `AuditActions.TEMPLATE_*`（对 catalog 侧等价、对 notify 侧是**新增留痕**）。

### 6.16 `merchant_application`（`:369-390`，C3 块 2〈用户块〉新增） {#db-6-16}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | **bigint** | **PK**, NOT NULL AUTO_INCREMENT | 自增主键 |
| `Username` | varchar(20) | NOT NULL | 申请人账号（`user.username`）。⚠️ **软引用，不设 FK**（同 `notification.TargetId` 口径） |
| `ShopName` | varchar(64) | NOT NULL | 店铺名 |
| `LicenseNo` | varchar(64) | NOT NULL | 营业执照号 |
| `ContactName` | varchar(20) | NULL, DEFAULT NULL | 联系人姓名 |
| `ContactPhone` | varchar(20) | NULL, DEFAULT NULL | 联系电话 |
| `ShopAddress` | varchar(128) | NULL, DEFAULT NULL | 店铺地址 |
| `LicenseImage` | varchar(500) | NULL, DEFAULT NULL | 证件图 URL；无图 NULL |
| `Status` | varchar(16) | NOT NULL DEFAULT 'pending' | 审核状态：`pending` / `approved` / `rejected`（小写，与 CAS 的 `WHERE Status=?` 字面一致；字符串码，**不建 DB ENUM**） |
| `RejectReason` | varchar(255) | NULL, DEFAULT NULL | 驳回理由（`Status=rejected` 时有值） |
| `BatchId` | varchar(36) | NOT NULL | 提交时生成的 UUID；给通知与审计一个**跨重导稳定**的 `TargetId`（同 I2 的 `BatchId` 理由） |
| `SubmittedAt` | **datetime(3)** | NOT NULL DEFAULT CURRENT_TIMESTAMP(3) | 提交时刻（**DB 存 UTC**） |
| `ReviewedBy` | varchar(20) | NULL, DEFAULT NULL | 审核的管理员账号名（管理员无独立业务号） |
| `ReviewedAt` | **datetime(3)** | NULL, DEFAULT NULL | 审核时刻；未审为 NULL |
| `created_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP | 行落库时刻 |
| `updated_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP **ON UPDATE CURRENT_TIMESTAMP** | 行更新时刻（审核会 UPDATE，故本表**非** append-only） |

> 索引：主键 + `idx_merchant_application_status(Status, SubmittedAt)`（审核队列：按状态取、按提交时刻排）+ `idx_merchant_application_user(Username, SubmittedAt)`（「我的申请」按账号取近期）+ `idx_merchant_application_batch(BatchId)`（通知/审计按批归组）。
> ⚠️ 本表**无 UNIQUE**：同一账号**可多次申请**（`Username` 天然重复），一次申请一行。

### 6.17 `template`（`:556-569`，C3 块 3 **就地改造** #8 新增；**通用**模板库 —— **取代** `goods_template` + `notification_template`，两者已物理删） {#db-6-17}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | 自增主键 |
| `Type` | varchar(96) | NOT NULL | 模板类型（**点分字符串码，不建 DB ENUM**）：`goods.detail` / `goods.spec` / `goods.afterSale` / `goods.reject` / `merchant.reject` / `merchant.approve` / `user.ban` / `notification.<原 TemplateKey>` / `comment.reply`（块 6 预留）。⚠️ 后端**不校验未知 Type**：查不到回**空列表 + `success:true`**（不是错误） |
| `Name` | varchar(64) | NOT NULL | 模板名（「选模板」弹窗列表的显示名）。与 `Type` 合成唯一键 —— 撞键 ⇒ 回「该类型下已有同名模板」 |
| `Title` | varchar(255) | NULL, DEFAULT NULL | 标题模板（**通知类用**；商品类 / 理由类为 NULL —— 正文即全部） |
| `Body` | **mediumtext** | NULL, DEFAULT NULL | 正文：**商品类 = 纯文本内容（快照源）** —— 选模板 → 填入输入框 → **可改** → 商品存**最终文本**而**非**模板 Id ⇒ 改模板**不影响**已上架商品（与 `goods_template` 的「引用 Id」语义**相反**）；通知类 = 正文模板 |
| `Enabled` | tinyint(1) | NOT NULL DEFAULT 1 | 1 启用 / 0 停用（**逻辑废弃，不物理删**，规则 v2）。⚠️ **写**入走后端 `Integer` ⇒ 发 `1`/`0`；**读**回经 Connector/J（`tinyInt1isBit=true`）可能变 **Boolean** ⇒ 判断要宽容 |
| `Sort` | int | NOT NULL DEFAULT 0 | 同 `Type` 内排序（升序）；0 = 默认。排序统一 `ORDER BY Sort ASC, Id ASC` |
| `created_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP | 行落库时刻（`agent.md` 数据库管理规则 8） |
| `updated_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP **ON UPDATE CURRENT_TIMESTAMP** | 行更新时刻 |

> 索引：主键 + **`uk_template_type_name(Type, Name)`**（UNIQUE —— 兼「防同类型重名」与搬迁 upsert 锚点）+ **`idx_template_type_enabled_sort(Type, Enabled, Sort)`**（公共读 / 管理端按 `Type` 取启用的）。⚠️ **本表初始 0 行、无种子数据** ⇒「选模板」弹窗显示「暂无模板」是**预期**（建库即空）。⚠️ **无 FK**。端点为公共读 `POST /template/{list,get}` + 管理端 `POST /admin/template/{list,create,update,delete}`（TD-18：**不加角色闸**）；`Type` **落库即定、不允许改**（改了等于换一个模板）。

### 6.18 `goods_audit`（`:492-515`，C3 块 3〈商品块〉新增） {#db-6-18}
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | bigint | **PK**, NOT NULL AUTO_INCREMENT | 自增主键 |
| `MerchantId` | varchar(20) | NOT NULL | 提审商家号（`goods.MerchantId`）。⚠️ **软引用，不设 FK**（同 `notification.TargetId` 口径） |
| `BatchId` | varchar(36) | NOT NULL | 提审时生成的 UUID；给通知与审计一个**跨重导稳定**的 `TargetId` |
| `GoodName` | varchar(40) | NOT NULL | 商品名（提审快照） |
| `Price` | **double** | NOT NULL | 价格（提审快照；与 `goods.Price` 同型） |
| `GoodType` | varchar(20) | NULL, DEFAULT NULL | 类目名（提审快照；仅作展示/兼容，正式引用走 `TypeId`） |
| `GoodTypeNew` | int | NULL, DEFAULT NULL | 类目新旧位（提审快照；通过时原样写进 `goods.GoodTypeNew`） |
| `TypeId` | int | NULL, DEFAULT NULL | 类目 Id（提审快照；通过时原样写进 `goods.TypeId`）。⚠️ **必须随单走** —— 否则改名后按名字回查会歧义 |
| `ProductCode` | varchar(40) | NULL, DEFAULT NULL | 商品货号（提审快照） |
| `GoodImg` | varchar(255) | NULL, DEFAULT NULL | 商品图 URL（提审快照；放宽到 255 容长 URL） |
| `Detail` | **mediumtext** | NULL, DEFAULT NULL | **C3 块 3 就地改造（#8）加列**（`:636-641`）。详情**快照**（提审携带；通过时**原样**写进 `goods.Detail`） |
| `Spec` | **mediumtext** | NULL, DEFAULT NULL | **C3 块 3 就地改造（#8）加列**（`:643-648`）。规格**快照**（同上；行内 `键=值`） |
| `AfterSale` | **mediumtext** | NULL, DEFAULT NULL | **C3 块 3 就地改造（#8）加列**（`:650-655`）。售后**快照**（同上） |
| `Status` | varchar(16) | NOT NULL DEFAULT 'pending' | 审核状态：`pending` / `approved` / `rejected`（小写，与 CAS 的 `WHERE Status=?` 字面一致；字符串码，**不建 DB ENUM**） |
| `RejectReason` | varchar(255) | NULL, DEFAULT NULL | 驳回理由（`Status=rejected` 时有值）。命名沿用 `merchant_application.RejectReason`（同名同义） |
| `GoodsId` | bigint | NULL, DEFAULT NULL | 通过后回填的 `goods.Id`（软引用）；未通过为 NULL |
| `SubmittedAt` | **datetime(3)** | NOT NULL DEFAULT CURRENT_TIMESTAMP(3) | 提交时刻（**DB 存 UTC**） |
| `ReviewedBy` | varchar(20) | NULL, DEFAULT NULL | 审核的管理员账号名（管理员无独立业务号） |
| `ReviewedAt` | **datetime(3)** | NULL, DEFAULT NULL | 审核时刻；未审为 NULL |
| `created_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP | 行落库时刻 |
| `updated_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP **ON UPDATE CURRENT_TIMESTAMP** | 行更新时刻（审核会 UPDATE，故本表**非** append-only） |

> 索引：主键 + `idx_goods_audit_status(Status, SubmittedAt)`（审核队列：按状态取、按提交时刻排）+ `idx_goods_audit_merchant(MerchantId, SubmittedAt)`（「我的提审」按商家取近期）+ `idx_goods_audit_batch(BatchId)`（通知/审计按批归组）。
> ⚠️ 本表**无 UNIQUE**：**再次提审 = 新插一行**（保留完整历史）⇒ `(MerchantId, GoodName, ProductCode)` 不是键。
> ⚠️ **提审只写本表（不写 `goods` 行）**；管理员【通过】才在**同一事务**里写 `goods` + `productsales` + CAS 置本表 `Status='approved'` + 回填 `GoodsId`；【驳回】只改本表 `Status='rejected'` + `RejectReason`。

---

## 7. 索引完整基线（⚠️ **不含 C3 块 4 的 `cart`**（3 个：`PRIMARY` + `uk_cart_customer_product` + `idx_cart_customer`，见 §11）**与块 4 补丁的 `order_shipping_track`**（2 个：`PRIMARY` + `idx_order_shipping_track_order`，见 §12）） {#db-7}

| 表 | 主键 | 二级索引 | 非主键 UNIQUE |
|---|---|---|---|
| `customerimage` | `UserName` | 无 | **无** |
| `friend` | `id` | 无 | **无** |
| `goods` | `Id` | 无 | **无** |
| `goodstype` | `Id` | `idx_goodstype_parent_sort(ParentId, Sort)`（普通，2026-10-10 #7）**+ `uk_goodstype_parent_name(ParentId, GoodType)`**（UNIQUE，2026-10-10 #7 **换代**：原 `uk_goodstype_GoodType(GoodType)` 已删） | **有 1 个**（`(ParentId, GoodType)`，UNIQUE，#7 换代） |
| `login` | `UserName` | 无 | **无**（`Id` 非唯一） |
| `merchantimage` | `UserName` | 无 | **无** |
| `message` | `id` | `idx_from_to(from_id,to_id)`、`idx_create_time(create_time)` | **无** |
| `order` | `Id` | **`uk_order_orderId(OrderId)`** | **有 1 个**（`OrderId`，UNIQUE，S1a） |
| `productsales` | `Id` | 无 | **无** |
| `user` | `username` | 无 | **无**（`customerid`/`merchantid` 非唯一） |
| `user_conversation_read` | 复合 4 列 | 无 | **无** |
| `order_state_transition` | `Id` | `idx_order_state_transition_order(OrderId, OccurredAt)` | **无** |
| `admin_audit`（`:264-292`，批 A） | `Id` | `idx_admin_audit_occurred(OccurredAt)`、`idx_admin_audit_actor(ActorId, OccurredAt)`、`idx_admin_audit_target(TargetType, TargetId, OccurredAt)` | **无** |
| `notification`（`:294-327`，I2） | `Id` | `idx_notification_inbox(ToId, ToType, CreatedAt)`、`idx_notification_unread(ToId, ToType, ReadAt)`、`idx_notification_batch(BatchId)` | **无** |
| ~~`notification_template`~~ **已删**（#8） | — | —（原 **`uk_notification_template_key(TemplateKey)`**，UNIQUE，I2 `:344`，**随表删**） | —（原「有 1 个」，现由下面 `template` 行接替） |
| `merchant_application`（`:369-390`，块 2） | `Id` | `idx_merchant_application_status(Status, SubmittedAt)`、`idx_merchant_application_user(Username, SubmittedAt)`、`idx_merchant_application_batch(BatchId)` | **无**（同账号可多次申请 ⇒ `Username` 非键） |
| `template`（`:556-569`，**#8** 取代 `goods_template`） | `Id` | **`uk_template_type_name(Type, Name)`**（UNIQUE）+ `idx_template_type_enabled_sort(Type, Enabled, Sort)` | **有 1 个**（`(Type, Name)`，UNIQUE，#8 `:567`） |
| `goods_audit`（`:492-515`，块 3） | `Id` | `idx_goods_audit_status(Status, SubmittedAt)`、`idx_goods_audit_merchant(MerchantId, SubmittedAt)`、`idx_goods_audit_batch(BatchId)` | **无**（再次提审 = 新插一行 ⇒ `(MerchantId, GoodName, ProductCode)` 非键） |

**汇总**：**17** 张表；主键 17 个（1 个复合）；二级索引 **20 个**（分布 9 张表；**2026-10-10 实测** —— ⚠️ 原写「16 个」是**既有少算**，枚举里列了块 3 两张表却没并进总数，见 §2）；**全库除主键外 3 个 UNIQUE 约束**（`order.uk_order_orderId`，2026-09-30 S1a；**`goodstype.uk_goodstype_parent_name`，2026-10-10 #7 换代〔原 `uk_goodstype_GoodType` 已删〕**；**`template.uk_template_type_name`，2026-10-10 #8**〔原为 `notification_template.uk_notification_template_key`，2026-10-09 I2，**随表删**〕）。
唯一约束只此 3 个，也不是遗漏 —— 其中 2 个（`order` / `goodstype`）是 `defect-survey.md §二 前提清单`里 A/B/C 三条的出处，到 2026-10-04 为止只有**前提 A 的 DB 面**（S1a）与 **`goodstype` 这条新前提**（#11）被认领，B / B′ / C 仍全靠应用层保证。⚠️ **`goodstype` 这条已在 2026-10-10 #7 换代**：全局唯一的 `GoodType` 换成**每父级下唯一**的 `(ParentId, GoodType)` —— 约束面**收窄**（同一父级下仍禁重名），前提清单里那条对应的仍是「同父级下不重名」。
⚠️ **第三个 UNIQUE（现为 `template.(Type, Name)`；#8 前是 `notification_template.TemplateKey`，I2）不在前提清单里**，性质不同：它不是一个「本该约束、长期缺失」的业务键，而是**新建表时顺手就给了**的唯一性（#8 只是把同一性质从「模板键全局唯一」换成「**同 Type 内模板名唯一**」）—— 故它既不算「认领了一条旧前提」，也不代表 B / B′ / C 有进展。（`notification` 表**没有** UNIQUE：`BatchId` 天然重复，`(ToId,ToType)` 也不是键。）（原文此处引「§4「前提清单」」，但本文件 §4 是 `SettlementDate` 语义，前提清单实际在 `defect-survey.md` —— 一并更正。）

---

## 8. 外键与引用完整性 {#db-8}

**结论：全库 0 个 `FOREIGN KEY`、0 个 `CHECK`。** 导入脚本一开始就关掉检查（`:12` `FOREIGN_KEY_CHECKS=0`）并在末尾恢复（`:217`）；`SQL_MODE` 亦置为 `NO_AUTO_VALUE_ON_ZERO`（`:13`，末尾 `:216` 恢复）。引用完整性**完全由应用层维护**。

逻辑引用关系（DDL 中不存在，仅记录事实）：

| 子表.列 | 指向 | 说明 |
|---|---|---|
| `login.Id` | `user.customerid` **或** `user.merchantid` | **按 `Role` 择一**，一个 `char(20)` 列指向两个不同列 —— 无法用单个 FK 表达 |
| `goods.MerchantId` | `user.merchantid` / `login.Id` | |
| `order.MerchantId` | 同上 | 类型不同（varchar(40) vs char(20)），见 §9 |
| `order.CustomerId` | `user.customerid` / `login.Id` | 类型不同（varchar(40) vs char(20)） |
| `order_state_transition.OrderId` | `order.OrderId` | ⚠️ **未建 FK**（原文「当前无法加 FK」）—— 阻塞点已于 **2026-09-30 S1a（`1e2fcfa`）消失**：`order.OrderId` 现有 UNIQUE `uk_order_orderId`，同样满足「被引用列有索引」。**建得起来，但迄今未建**（规则 v2 把新增 FK 列为「有条件允许」）。详见下方 🔄 |
| `productsales.(ProductCode, MerchantId, GoodName)` | `goods` 三元组 | 冗余派生表 |
| `message.(from_id,to_id)` | 用户业务号 | 类型 varchar(50)，与 char(20) 不同 |
| `friend.(customerid,friendid)` | 用户业务号 | 同上 |
| `customerimage.CustomerId` | `user.customerid` | |
| `merchantimage.MerchantId` | `user.merchantid` | |
| `notification.ToId` | `user.customerid` **或** `user.merchantid` | **按 `ToType` 择一**（与 `login.Id` 同一种形态：一个 varchar(50) 列指向两个不同列，无法用单个 FK 表达） |
| `notification.TargetId` | 任意业务对象（`goods` / `user` / `comment`…） | ⚠️ **软引用，刻意不建 FK** —— 用户裁决：目标可能已被删除 / 全量重导后失效，有 FK 会让插入报错或触发级联删；无 FK 由前端渲染时容错（对象不存在 → 显示「（相关对象已删除）」，不崩、不空白）。逐字见 `mall_management_sql.sql:305-307` 块注释与 `context-packs/c3-block-1-infra.md` |
| `admin_audit.TargetId` | 任意业务对象 | 同上（软引用）；且它是**拼接串**（`MerchantId:ProductCode`），更不可能有 FK |

> ⚠️ **（S1a 之前）`order_state_transition.OrderId → order.OrderId` 这个 FK 建不起来 —— 不是「没建」，是「建不了」**（**现况见本块末尾 🔄**）：MySQL/InnoDB 要求**被引用列上有索引**（该列须为该索引的最左前缀列），而 `order.OrderId` **当时无任何索引**。故要建 FK 必须先给 `order.OrderId` 建索引。
>
> ✅ **本机实测（8.0.32，用完即 DROP 并复核删净）**：
> - 父列**无索引** → `ERROR 1822 (HY000): Failed to add the foreign key constraint. Missing index for constraint 'fk1' in the referenced table 'p'`
> - 父列**仅有一个非唯一索引** → **ACCEPTED**（且真生效：插孤儿行被拒 `ERROR 1452`）
>
> ⇒ 精确表述是「**要求有索引，不要求唯一索引或主键**」（InnoDB 甚至不强制被引用键唯一，这是它对 SQL 标准的一处偏离）。**注意**：因此这条 FK 的阻塞点**只有「无索引」这一个**，与 `OrderId` 不保证唯一是**两件事**（后者属前提 A，影响的是时间线能否指向不存在的订单，而非 FK 能否创建）。
>
> 🔄 **2026-09-30 更新（S1a）**：`uk_order_orderId` 落地后，阻塞点**已消失** —— 该 FK 现在**建得起来**（唯一索引同样满足「被引用列有索引」）。但**本笔未建 FK**：规则 v2 把新增 FK 列为「**有条件允许**」（四项前置），是否建按 `selection-status.md` 的触发条件。另：`order.OrderId` 现在唯一了，但**时间线 → 订单的引用完整性仍只能靠应用层** —— 唯一索引只管 `order` 自己那一侧，`order_state_transition` 上仍无 FK，其中仍可存在指向不存在订单的 `OrderId`。

---

## 9. 跨表类型 / 宽度不一致基线 {#db-9}

同一业务语义在多张表用不同类型/宽度 —— 无 FK 兜底，join 与比较语义随之漂移：

| 语义 | 各处类型 | 是否一致 |
|---|---|---|
| 商家业务号 | `goods.MerchantId` char(20)、`productsales.MerchantId` char(20)、`merchantimage.MerchantId` char(20)、`user.merchantid` char(20)、`login.Id` char(20) | ✅ 一致 |
| ↳ 同上 | **`order.MerchantId` varchar(40)** | ❌ 宽一倍且定长/变长不同 |
| 客户业务号 | `customerimage.CustomerId` char(20)、`user.customerid` char(20) | ✅ 一致 |
| ↳ 同上 | **`order.CustomerId` varchar(40)** | ❌ |
| ↳ 用户业务号（消息/好友） | `friend.customerid`/`friendid` varchar(50)、`message.from_id`/`to_id` varchar(50)、`user_conversation_read.user_id`/`other_id` varchar(50) | ✅ 内部一致 |
| ↳ 与上面两组 | char(20) vs varchar(40) vs varchar(50) | ❌ 三种宽度 |
| 用户账号 | `login.UserName` char(20)、`user.username` char(20) | ✅ 一致 |
| ↳ 同上 | **`merchant_application.Username` varchar(20)**（C3 块 2） | ❌ 变长 vs 定长（宽同 20）—— 软引用无 FK，join `user.username` 时须注意尾空格语义 |
| 类型名 | `goodstype.GoodType` char(20)、`goods.GoodType` char(20) | ✅ |
| 类型 ID | `goodstype.GoodTypeNew` **NOT NULL DEFAULT '0'**、`goods.GoodTypeNew` **NULL DEFAULT NULL** | ❌ 可空性不同 |
| 商品名 | `goods.GoodName` char(40) | — |
| ↳ 同上 | **`productsales.GoodName` char(20)** | ❌ **窄 20 → 21–40 字符名报 Error 1406**（严格模式，已实测；非静默截断） |
| 商品型号 | `goods.ProductCode` char(40)、`productsales.ProductCode` char(40) | ✅ |
| 价格 | `goods.Price` **double** | — |
| ↳ 同上 | **`order.Price` float** | ❌ 精度不同 |
| 会话类型 | `message.from_type`/`to_type` varchar(10)、`user_conversation_read.user_type`/`other_type` varchar(10) | ✅ |
| 交易标识 | `order.OrderId` varchar(40)、`order_state_transition.OrderId` varchar(40) | ✅（B3 刻意对齐） |

> `char`（定长，比较时按补空格规整）与 `varchar`（变长）在 `utf8mb4_0900_ai_ci` 下比较行为不同。跨表按业务号 join 时，类型不一致会带来尾空格与大小写语义差异，**且无 FK 能在写入时拦下不匹配**。

---

## 10. ⚠️ 全库除主键外只有 **4 个 UNIQUE** —— 唯一性仍基本靠应用层（⚠️ 第 4 个 = C3 块 4 的 `uk_cart_customer_product (CustomerId, ProductCode)`，**本节下面未加行**，见 §11） {#db-10}

> 本节单独成节，因为它是整份 schema 最重的一条：**业务主键几乎全是约定，不是约束。**（2026-09-30 S1a 之前是「全是」；`order.OrderId` 是第一个例外，`goodstype.GoodType` 于 **2026-10-04 #11** 成为第二个、`template.(Type, Name)` 于 **2026-10-10 块 3 就地改造（#8）** 成为第三个、**`cart.(CustomerId, ProductCode)` 于 2026-10-10 C3 块 4 成为第四个**。）

### 10.1 事实 {#db-10-1}
17 张表 → 17 个主键（其中 1 个复合）→ 20 个二级索引 → **非主键 UNIQUE：3 个**（`order.uk_order_orderId`，2026-09-30 S1a 新增；**`goodstype.uk_goodstype_parent_name`，2026-10-10 #7 换代**；**`template.uk_template_type_name`，2026-10-10 #8**〔#8 **之前**是 `notification_template.uk_notification_template_key`，2026-10-09 I2 新增，**该表已删**〕—— **最后这个不属前提清单，只是新表自带**，见 §7 附注）。

### 10.2 本应是业务键、实际只是「约定」的列 {#db-10-2}

| 业务键 | 位置 | 现状 | 后果 | 登记 / 前提 |
|---|---|---|---|---|
| `order.OrderId` | `order` | ✅ **有 UNIQUE**（`uk_order_orderId`，2026-09-30 S1a） | 重复单号写不进去（`A-S2a-1b`/`1c` 实测）；但 `order_state_transition.OrderId` **仍可**指向不存在的订单（无 FK，见 §8） | **TD-14**（已修）；前提 A（DB 面已消解） |
| `goods (MerchantId, GoodName, ProductCode)` | `goods` | 无 | 三元组重复 → `queryForMap` 多行即抛 → **失败被当值缓存满 TTL** | 前提 B（B1 `priceByTriple`） |
| `goods (MerchantId, ProductCode)` | `goods` | 无 | 与三元组并存且**互相冲突** | 前提 B′ |
| `login.Id` | `login` | 无 | 同一业务号可对多条账号 | 前提 C |
| `user.customerid` / `user.merchantid` | `user` | 无 | 同上；且 `login.Id` 按 `Role` **择一**指向它们 | 前提 A 同族 |
| `friend (customerid, friendid)` | `friend` | 无 | 重复好友对可写入 → `friendList::<cid>` 与 `friendRequests::<fid>` 两个缓存都会重复列出 | **新前提候选**（原普查清单未列，见下注） |
| `productsales (ProductCode, MerchantId)` | `productsales` | 无 | 重复销量行 | — |
| `goodstype.GoodType` | `goodstype` | ✅ **有 UNIQUE**（`uk_goodstype_GoodType`，2026-10-04 #11） | ~~类型名可重复~~ ⇒ 重复类型名写不进去（DB 层兜底）；应用层 `SELECT COUNT(*)` 查重**保留**（省一次正常路径的回查，且让并发输方拿到「类型已存在」而非「新增失败」） | — |
| `login.UserName` | `login` | **PK ✓** | 两个主键级唯一性之一（另一个是 `user.username`）；`order.OrderId` 于 2026-09-30 成为**第三个**真正被约束的唯一性（唯一索引，非主键） | — |

> ⚠️ **编号提醒**：本次收到「`friend` 无唯一约束 → 前提 B′」，但普查报告里 **B′ 是 `goods (MerchantId, ProductCode)`**；`friend` 这条在原清单中**没有**，属**新前提**。上表按「新前提候选」记，**不并入 B′**，以免串号 —— 若你的 B′ 另有所指请更正。

### 10.3 这解释了 B1/B2/B3 为何都围绕「查重 + 锁」打转 {#db-10-3}

唯一性既然不是**约束**，就必须在应用层用「先查后写 + 分布式锁」模拟出来 —— 这正是三笔工作的共同重心，且三笔各补一角：

| 笔 | 对策 | 覆盖到的 | 覆盖不到的 |
|---|---|---|---|
| B1 | **查重 + 缓存**（`priceByTriple` 先查后缓存） | 命中唯一时 | 唯一性未约束 ⇒ 查重会命中多行并抛，而**失败又被当值缓存**（前提 B 的传导链） |
| B2 | **分布式锁**（`order-add`/`order-pay`/`order-receive`/`order-delivery`/`friend-add` 五落点） | 锁可用时 | 锁是**三路 fail-open**（`tech-debt.md:291`）⇒ 锁不可用即回落到无锁原路径 |
| B3 | **谓词守卫（CAS）** | 同一动作只成功一次（守卫互斥 + InnoDB 行锁） | 保证不了 `OrderId` 唯一（**该点 2026-09-30 由 S1a 的 UNIQUE 认领**）；也保证不了时间线的 `from`（要读回，且读回必须在锁内） |

⇒ **三笔都在替数据库补唯一性，而三笔的补法没有一笔覆盖「锁不可用」这一档。** 这是「局部推理极强、组合推理缺席」的结构性后果：
每一笔在自己的边界内都正确，而**边界本身（唯一性不是约束）从未被任何一笔认领**。

> 🔄 **2026-09-30 S1a 是第一次有笔认领这条边界**：`order.OrderId` 的 UNIQUE 由 DB 自己执行 —— 锁**全部 fail-open 也拦得住重复单号**（这正是 `A-S2a-1b` 在兜的那一层）。但**只认领了一条**（前提 A 的 DB 面）：B / B′ / C 与 `order_state_transition → order` 的引用完整性至今仍无约束。
>
> 🔄 **2026-10-04 #11 认领了第二条**：`goodstype.GoodType` 的 UNIQUE。它是**同一逻辑的第二个实例，但认领理由与 S1a 不同** —— S1a 兜的是「锁 fail-open 时的重复单号」，#11 兜的是「**根本没有锁**」：`GoodsTypeCacheService.addType` 的 check-then-insert **不在 B2 的 5 个锁落点内**，所以它连 fail-open 的锁都没有，唯一的防线从来就只有那条应用层 `SELECT COUNT(*)`。A3 的 B-2 场景实测（CI `ci49`，2026-10-03）**4/5 轮复现**，正是这条缺口的直接证据。
> ⚠️ **本条与 S1a 的一条关键差异**：S1a 的迁移块能写「前置：每个库重复行检测为 0」（`order` 实测本机 7/7、VM 6/6 均为 0）；#11 的迁移块**不能**写 —— `goodstype` 的重复行是 A3 自己造的、**已实测存在**。故它只能走「全量重导」路径落盘，对既有库须先人工去重。逐字见 `mall_management_sql.sql:246-262` 与 `db-migration.md` 注意 7。
---

## 11. 附录：C3 块 4〈订单块〉的 `cart` + `order.Quantity`（2026-10-10） {#db-11}

> **本节为什么在文件末**：`cart` 是第 18 张表，但**没有**插进 §1 表清单 / §6.x。本文件有 **44 处外部 `文件:行号` 引用**（10 个文件，指向本文件 **26 个不同行号**）（`slot-registry.md` 26 / `selection-status.md` 5 / `progress.md` 4 / context-pack 4 / `defect-survey.md` 2 / `OrderCenter-merchant.html` 1 / `api-contract.md` 1 / `OrderDao.java` 1），中段插一行会让其后**全部集体 +1**；而把「已陈旧」与「本笔打破」分开判别、再逐条重指的工作，本仓已有归口（§十五 15-5 的「只登记、不修」＋「文档漂移清理笔」）。故本笔取**零漂移**手法：**外部引用一个都不动**，`cart` 的逐列定义放文件末。
> **出处** = `mall_management_sql.sql:693-738`（Migration 块 6：C3 块 4〈订单块〉，2026-10-10，分支 `feat/c3-admin-block4`）。**判据档位 = 可复核**（DDL 字面 + 本机库 / VM 库 `information_schema` 实测，见 `db-migration.md` 注意 12）。

### 11.1 `cart` —— 真购物车载体（A1 甲，用户 2026-10-10 逐项裁决） {#db-11-1}

| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | 内部主键，业务不使用 |
| `CustomerId` | varchar(40) | NOT NULL | 买家业务号，**对齐 `order.CustomerId`**（`:143`） |
| `MerchantId` | varchar(40) | NOT NULL | 卖家业务号，加购时从 `goods` 带入，**对齐 `order.MerchantId`**（`:142`） |
| `ProductCode` | **char(40)** | NOT NULL | **对齐 `goods.ProductCode`**（`:61`）—— 不是 `order` 那侧的 varchar |
| `Quantity` | int | NOT NULL DEFAULT 1 | 数量（≥1）；改量走 UPDATE，**不加行** |
| `PriceSnapshot` | **double** | NULL, DEFAULT NULL | 加购时的单价快照 = **结算成交价**；NULL 时结算回 `goods.Price`。⚠️ 刻意对齐 `goods.Price` 的 **double**（`:58`）而**不**照 `order.Price` 的 **float**（`:146`，`defect-survey.md` 记过的不一致）—— 新列向源列对齐，不复制旧缺陷 |
| `created_at` | datetime(3) | NOT NULL DEFAULT CURRENT_TIMESTAMP(3) | 行落库时刻 |
| `updated_at` | datetime(3) | NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) | 行更新时刻 |

- 主键 `Id`；**UNIQUE `uk_cart_customer_product (CustomerId, ProductCode)`** ⇒ 同一商品重复加购**合并数量、不新开行**（这是全库第 4 个非主键 UNIQUE，见 §10）。
- `KEY idx_cart_customer (CustomerId)`。
- `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`（`:720`）—— 与其余 17 张表逐字相同（见 §6 首段）。
- ⚠️ **归属以 `MerchantId` 为准，不按单独的 `ProductCode`**：`ProductCode` 跨商家不保证唯一（本仓无店铺表），回读商品名 / 图一律按 `(MerchantId, ProductCode)` 联 `goods`（`.sql:700-703` 块注释）。
- ⚠️ 同笔另有一条 `ALTER TABLE cart MODIFY COLUMN PriceSnapshot …`（`:728`）—— **只改注释、不改列**（初稿把 `PriceSnapshot` 注释成「仅陈列 / 对账，非成交价」，与 A1「存快照就是为了避免改价争议」的选型理由相反）。它**必须单独一条**：`CREATE TABLE IF NOT EXISTS` 对**已存在**的表是空操作（`.sql:722-727`）。
- ⚠️ **不在** `.sql:21` 起的 `DROP TABLE IF EXISTS` 基线清单里 ⇒ 整份重导**不清空购物车**（它是用户数据不是种子，`.sql:689-690`）。

实测：本机库 / VM 库 `cart` 均 **0 行**（2026-10-10，见 `db-migration.md` 注意 12）。

### 11.2 `order` + `Quantity`（A2 甲） {#db-11-2}

`.sql:730-738`：`ADD COLUMN \`Quantity\` int NOT NULL DEFAULT 1`（**表末 + 默认值 + COMMENT**，数据库管理规则 7），走 `information_schema` 守卫 + `PREPARE` ⇒ **重复执行是空操作、不报错**。

- `DEFAULT 1` ⇒ 存量 6 行全部落到「1 件」（语义正确，那些单本就只买了 1 件），且**不改行数** ⇒ A3 的 **P4 种子计数 `order=6` 不受影响**。
- §6.8 的表**未加行**（理由同本节首段），`Quantity` 的定义**以本小节为准**。

### 11.3 本笔对四个基线的净变化 {#db-11-3}

| 基线 | 变化 | 判据档位 |
|---|---|---|
| 表数 | **17 → 18**（+`cart`） | 可复核（VM 库 18 表实测） |
| 行首 `CREATE TABLE` 条数 | **19 → 20** | 可复核（`grep -c '^CREATE TABLE'` + 文件头 `LF/CR` 实测） |
| 索引 | **16 → 18**（+`uk_cart_customer_product`、+`idx_cart_customer`） | **推断**（按 DDL 直接推导，非实测查询） |
| 非主键 UNIQUE | **3 → 4**（+`uk_cart_customer_product`） | **推断**（同上） |
| 种子行数（P4 判据 `order/goods/login/user/friend`） | **不变** `6/5/5/5/4` | 可复核 |

> ⚠️ 索引 / UNIQUE 两处计数为**按 DDL 推导**，不是 `information_schema.STATISTICS` 的实测结果 —— 引用时按此档位标注。

## 12. 附录：C3 块 4 **补丁**〈付款 / 发货 模拟完整流程〉的 `order_shipping_track` + `order` 的 7 个明细列（2026-10-10） {#db-12}

> **本节为什么也在文件末**：理由与 §11 首段**逐字相同**（本文件有大量外部 `文件:行号` 引用，中段插行会让其后集体漂移）⇒ 同样取**零漂移**手法：外部引用一个不动，新表逐列定义放文件末。⚠️ 与 §11 的区别：§11 是**新增第 18 张表**（`cart`），本节是**新增第 19 张表**（`order_shipping_track`）**并**给基线表 `order` 加 7 列。
> **出处** = `mall_management_sql.sql:740-815`（Migration 块 7：C3 块 4 补丁，2026-10-10，分支 `feat/c3-admin-block4`）。**判据档位 = 可复核**（DDL 字面 + 本机库 / VM 库 `information_schema` 实测，见 `db-migration.md` 注意 13）。

### 12.1 `order_shipping_track` —— 模拟物流轨迹（A2） {#db-12-1}

| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | 内部主键，业务不使用 |
| `OrderId` | varchar(40) | NOT NULL | 业务单号，**对齐 `order.OrderId`**（`:143`）。⚠️ **不建外键**（理由见下） |
| `Status` | varchar(24) | NOT NULL | 节点码（**按时序**）：`in_transit` / `picked_up` / `out_for_delivery` / `delivered` |
| `Description` | varchar(255) | NULL, DEFAULT NULL | 节点中文描述（「运输中」「驿站已揽收」等；2026-10-11 前两步对调），供前端直接显示 |
| `TrackedAt` | datetime(3) | NOT NULL | 该节点的**发生时刻**（毫秒精度）。⚠️ 排序键是它，**不是 `Id`**——同单几条可能同批 INSERT，`Id` 不保证与发生顺序一致（同 `order_state_transition.OccurredAt` 的口径） |
| `created_at` | datetime(3) | NOT NULL DEFAULT CURRENT_TIMESTAMP(3) | 行落库时刻 |
| `updated_at` | datetime(3) | NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) | 行更新时刻 |

- 主键 `Id`；`KEY idx_order_shipping_track_order (OrderId, TrackedAt)`。
- **无 UNIQUE**（故 §10 的「4 个非主键 UNIQUE」**不变**）：同一单同一 `Status` 的**唯一性由应用层保证** —— 调度器落库前先 `ShippingTrackDao.exists(orderId, status)`（`NOT EXISTS` 式幂等）。⚠️ **这是有意把唯一性交给应用层**（同 §10 的整体局面），不是一个疏漏：轨迹表可能将来要支持「同一节点多次出现」（改派 / 退回），库级 UNIQUE 会把它锁死。
- **⚠️ 不建外键**：与全库一致（§8「外键与引用完整性」—— 本仓一律靠应用层）。删除 / 清理订单**不**级联删轨迹。
- `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`（`:815`）—— 与其余 18 张表逐字相同（见 §6 首段）。
- **⚠️ 不在** `.sql:21` 起的 `DROP TABLE IF EXISTS` 基线清单里（同 `cart`）⇒ 整份重导**不清空轨迹**（它是业务数据不是种子）。
- **⚠️ 只追加、不更新**：调度器 `ShippingTrackAdvanceJob` 与发货路径**都只 INSERT**，全仓**没有任何** `UPDATE order_shipping_track`（也没有任何「轨迹反过来改 `order` 状态位」的语句 —— 那条红线见 `tech-debt.md` 的「物流签收 ≠ 订单收货」）。

### 12.2 `order` 的 7 个明细列（补丁） {#db-12-2}

`.sql:769-796`：7 条 `ADD COLUMN`（各带 `information_schema` 守卫 + `PREPARE` ⇒ 重复执行是空操作），**全部 `DEFAULT NULL`**、加在**表末**（数据库管理规则 7）。

| 列 | 类型 | 组 | 何时写 |
|---|---|---|---|
| `TradeNo` | varchar(64) | 支付 | `payIfUp` 成功（`PAY+yyyyMMdd+6位随机`，模拟网关） |
| `PaymentMethod` | varchar(32) | 支付 | `payIfUp` 成功（收银台所选，可空） |
| `PaidAt` | datetime | 支付 | `payIfUp` 成功（`NOW()`） |
| `ExpressCompany` | varchar(64) | 物流 | `deliveryIfUp` 成功（可空） |
| `TrackingNo` | varchar(64) | 物流 | `deliveryIfUp` 成功（可空） |
| `ShippedAt` | datetime | 物流 | `deliveryIfUp` 成功（`NOW()`） |
| `ReceivedAt` | datetime | 收货 | `receiveIfUp` 成功（`NOW()`） |

- ⚠️ **这 7 列不进 CAS 的 SET 子句**：`OrderStateMachine.updateSql(action)` 被 golden copy 逐字钉死（`OrderStateMachineTest.updateSqlIsByteForByteTheLegacyLiteral`）⇒ 改由**新增的** `detailUpdateSql(action)` 在**同一把订单锁内**发第二条 UPDATE（`OrderStateMachine.java` 类尾补丁段）。
- ⚠️ **存量行全 `NULL`**（不是空串）⇒ 前端画 `—`。§6.8 的表**未加行**（理由同本节首段）。

### 12.3 本笔对基线的净变化 {#db-12-3}

| 基线 | 变化 | 判据档位 |
|---|---|---|
| 表数 | **18 → 19**（+`order_shipping_track`） | 可复核（VM 库 19 表实测） |
| `order` 列数 | **+7** | 可复核（`information_schema.COLUMNS` 逐列实测） |
| 行首 `CREATE TABLE` 条数 | **20 → 21** | 可复核（`grep -c '^CREATE TABLE'` 实测） |
| 索引 | **18 → 19**（+`idx_order_shipping_track_order`） | **推断**（按 DDL 直接推导，非实测查询） |
| 非主键 UNIQUE | **不变 4**（本块不新增 UNIQUE） | **推断**（同上） |
| 种子行数（P4 判据 `order/goods/login/user/friend`） | **不变** `6/5/5/5/4`（`ALTER ADD COLUMN` 不改行数） | 可复核 |

> ⚠️ 索引 / UNIQUE 两处计数为**按 DDL 推导**，不是 `information_schema.STATISTICS` 的实测结果 —— 引用时按此档位标注。
> ⚠️ **`order` 的存量行数**：本机库 **12 行** / VM 库 **10 行**（2026-10-10 实测）—— **都不是** P4 判据的 6。P4 读的是 CI 里**刚从 `.sql` 导入**的那份（种子 6 行），与两库的既有累积无关（见 `db-migration.md` 注意 13 末段）。

### 12.4 `order` 的第 8 个明细列 `CancelledAt`（补丁 · 第 2 笔，2026-10-10） {#db-12-4}

`.sql:817-830`（**尾部追加块**）：1 条 `ADD COLUMN`（带 `information_schema` 守卫 + `PREPARE` ⇒ 重复执行是空操作），`datetime(3) DEFAULT NULL`，加在**表末**。

| 列 | 类型 | 何时写 |
|---|---|---|
| `CancelledAt` | datetime(3) | `OrderTimeoutCancelJob` 扫到「`PayIf=0 AND CancelledAt IS NULL` 且 `CreateTime` 已超阈值」时，`OrderDao.cancelIfUnpaid` 的 **CAS** 写 `NOW(3)`；`NULL` = 未取消 |

- ⚠️ **这是「第 4 态」的唯一载体**：取消的单三位仍是 `(0,0,0)`，与「未付款」**完全相同** ⇒ 三位状态位表达不出来，前端必须**先判 `CancelledAt != null`**（`OrderMine.vue` / `OrderCenter.vue` 的 `stateOf`）。
- ⚠️ **本笔让 `CancelledAt` 进了 CAS 的 WHERE（**不进 SET**）**：`CancelledAt` **不进 SET 子句**（取消不改任何状态位），却**进了 PAY 的 WHERE** —— `OrderStateMachine.updateSql(PAY)` 尾部追加 `AND CancelledAt IS NULL`（§12.2 那条「被 golden copy 逐字钉死」的例外）。**不违「不碰 `OrderStateMachine`」约束**：该约束管**「介入动作」**，不管**「付款业务规则」**（用户 2026-10-11 澄清）。理由：取消不动 `PayIf`，光靠 `PayIf = 0` 的守卫**拦不住**「已取消的单被付款」；放进同一条 UPDATE 才是**单语句原子**，「先查再 CAS」会留 TOCTOU 窗口（锁是 fail-open）。
- ⚠️ **取消不写 `order_state_transition`**：`OrderAction` 仍是 4 个（A3 的 `A-S3-2` 按 tag 基数断言），取消是**系统按时间**的动作、不是一条 CAS 边。后果登记 `tech-debt.md` **TD-34**。
- ⚠️ **阈值不入库**：`mall.order.cancel.timeout-ms`（默认 60000）/ `.fixed-delay-ms`（默认 15000）走 properties；a3 profile 显式 `mall.order.cancel.enabled=false`（同族三道闸）。
- ⚠️ **不动表数门禁**：只加列、不加表 ⇒ `ConcurrencyRunner` 的 P3 表数（11+6=17）与 P4 种子行数**全不变**（VM 库 `CancelledAt` 列**已实测存在**，2026-10-10）。

## 13. 附录：可视化段（2026-10-11）的 3 张新表 · 4 张表加列 · 11 张表的 `Source` 标记列 {#db-13}

> **本节为什么也在文件末**：理由与 §11 / §12 首段**逐字相同**（本文件有大量外部 `文件:行号` 引用，中段插行会让其后**集体**漂移）⇒ 同样取**零漂移**手法：外部引用一个不动，新表逐列定义放文件末。本笔一共动了 **4 个 Migration 块**：新增 **3 张表**（`order_item` / `stats_daily` / `goods_sku`）、给 **4 张既有表加列**（`login` / `goods` / `goods_audit` / `template`）、再给 **11 张表各加一列 `Source`**。
> **出处**：`mall_management_sql.sql:832-862`（可视化段 commit 0）· `:864-901`（commit 2）· `:902-1099`（commit 3 + commit 3 补丁）· `:1101-1219`（commit 4）。**判据档位 = 可复核**（DDL 字面 + VM 库 `information_schema` 实测，见 `db-migration.md` 注意 14–17）。
> ⚠️ **本笔不动任何基线表的结构**（`goods` / `goods_audit` / `template` / `login` 只**加列**，`Source` 也只加列）⇒ **不触发规则 v2 的「改列类型」前置**（四项前置见 `db-migration.md` 首段）。

### 13.1 `order_item` —— 订单明细（可视化段 commit 0） {#db-13-1}

`.sql:846-862`（Migration 块，`CREATE TABLE IF NOT EXISTS`）。**为什么要有它**：`order` 一行只装得下**一件**商品的成交信息（`Price` / `Quantity` 都是单值），「一单多品」的清单此前被序列化进文本列 `ProductInformation`（格式 `名:码`、多品以「；」分隔）⇒ **商品维度的统计 / 明细 / 退货都做不了**（`.sql:835-837`）。用户 2026-10-11 裁「建数据源，不砍功能」。

| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | 内部主键，业务不使用 |
| `OrderId` | varchar(40) | NOT NULL | 业务单号，**对齐 `order.OrderId`**（`:143`）。⚠️ **不建外键**（`order` 是全量重导会 `DROP` 的基线表） |
| `MerchantId` | varchar(40) | NOT NULL DEFAULT '' | 卖家业务号，**冗余自 `order`** ⇒ 商家维度聚合免联查；对齐 `order.MerchantId` |
| `ProductCode` | **char(40)** | NOT NULL DEFAULT '' | **对齐 `goods.ProductCode`**（`:61`）—— 与 `cart.ProductCode` 同一取宽口径；解析不出时为空串 |
| `GoodName` | varchar(255) | NULL | 商品名**快照**（免联查 `goods`） |
| `Price` | **double** | NOT NULL DEFAULT 0 | 成交单价快照（对齐 `goods.Price` 的 double，**不复制** `order.Price` 的 float） |
| `Quantity` | int | NOT NULL DEFAULT 1 | 数量（≥1） |
| `Subtotal` | decimal(14,2) | NOT NULL DEFAULT 0.00 | 小计 = `Price × Quantity` |
| `OrderedAt` | datetime(3) | NOT NULL DEFAULT CURRENT_TIMESTAMP(3) | **下单业务时刻**（库时钟 `NOW(3)`）—— ⚠️ **与 `created_at` 刻意分开**：后者是**行落库**时刻，全量导入时会全挤在同一天，**不能**当统计维度（同 `order_shipping_track.TrackedAt` 的口径） |
| `created_at` / `updated_at` | datetime(3) | NOT NULL DEFAULT CURRENT_TIMESTAMP(3)〔`updated_at` 另带 `ON UPDATE`〕 | 行落库 / 更新时刻（数据库管理规则 8） |

- 主键 `Id`；**UNIQUE `uk_order_item_order_product (OrderId, ProductCode)`** ⇒ 同单同商品只一行（结算重放幂等）。⚠️ **不能写成 `UNIQUE(OrderId)`** —— 那会把「一单多品」直接堵死（`.sql:842-843`）。这是全库第 5 个非主键 UNIQUE（§10）。
- 两个普通 KEY：`idx_order_item_goods_time (ProductCode, OrderedAt)` · `idx_order_item_merchant_time (MerchantId, OrderedAt)`。
- **写入方**：`OrderService.addLocked` 的 `if (rows > 0)` 分支内（同一把 order-add 锁内；本仓订单链**禁 `@Transactional`** ⇒ 订单 INSERT 在 autocommit 下先提交、明细随后写 —— `.sql:844-845`）。
- ⚠️ **存量单不回填**（用户裁定）：5 月中与 10 月中的存量单在 `order_item` 里**没有行** ⇒ 商品维度的历史统计天然为空。这是「商品榜上线即空」的**唯一**原因（不是缺陷）。

### 13.2 `stats_daily` —— 经营数据日聚合（可视化段 commit 2） {#db-13-2}

`.sql:887-901`（Migration 块，`CREATE TABLE IF NOT EXISTS`）。**为什么要有它**：GMV / 成单数 / 件数 / 买家数四项**从来没被算过**（不是「算错了」），端点里现算会随 `order` 增长线性变慢，且「日切口径」会被抄散在多个查询里；⇒ 收成一张**日聚合**表 + 一个预聚合任务，端点只做「读 + 求和」（`.sql:867-869`）。

| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | 内部主键 |
| `StatDate` | date | NOT NULL | 统计日期 —— 日切列是 **`order.CreateTime`** |
| `Scope` | varchar(16) | NOT NULL DEFAULT 'site' | 口径：`site` = 全站 / `merchant` = 按商家 |
| `MerchantId` | varchar(40) | NOT NULL DEFAULT '' | 商家业务号；`Scope=site` 时**恒为空串**（占唯一键位） |
| `Orders` | int | NOT NULL DEFAULT 0 | 成单数 |
| `Gmv` | decimal(14,2) | NOT NULL DEFAULT 0.00 | `CAST(SUM(order.Price) AS DECIMAL(14,2))` |
| `Items` | int | NOT NULL DEFAULT 0 | `SUM(order.Quantity)` |
| `Buyers` | int | NOT NULL DEFAULT 0 | `COUNT(DISTINCT order.CustomerId)` |
| `created_at` / `updated_at` | datetime(3) | NOT NULL DEFAULT CURRENT_TIMESTAMP(3)〔`updated_at` 另带 `ON UPDATE`〕 | 行落库 / 更新时刻 |

- 主键 `Id`；**UNIQUE `uk_stats_daily_scope_date_merchant (Scope, StatDate, MerchantId)`**（第 6 个非主键 UNIQUE）+ 普通 KEY `idx_stats_daily_scope_date (Scope, StatDate)` —— upsert 就打在唯一键上。
- ⚠️ **为什么 `site` 与 `merchant` 两行都存，而不是只存 `merchant` 读时 `ROLLUP`**：`ROLLUP` 会丢掉 `COUNT(DISTINCT CustomerId)` 的**可加性** —— 同一人今天在两店各下一单，全站买家数算 **1**、两店相加算 **2** ⇒ 全站口径**必须单独算**。这是本表分 `Scope` 两行的**唯一**理由（`.sql:870-873`）。
- ⚠️ **`Aov`（客单价）刻意不落列**：它是 `Gmv / Orders` 的派生值，存下来会在「订单被重导 / 口径修正后」与分子分母漂开、而**漂开没人会发现** ⇒ **读时现除**（`.sql:874-875`）。
- ⚠️ **日切依赖 `order.CreateTime` 的文本格式**：该列是 `varchar(50)`、ISO 零填充 `'YYYY-MM-DD HH:MM:SS'`，`DATE(CreateTime)` 依赖那个**格式** —— 谁把写入格式改成非零填充，本表会**静默算错**（`.sql:876-879`）。同理不能当日切列的还有：`PaidAt`（12 行全 NULL）、`SettlementDate`（varchar 且部分 NULL）。
- ⚠️ **`Gmv` 用 decimal 承接 + 显式 `CAST`**：`order.Price` 是 **float(4 字节)** —— 裸选显示 `10000000`、`CAST(… AS DOUBLE)` 却是 `9999999`；不显式转型，float 的显示舍入会渗进库里（`.sql:880-882`）。
- **写入方唯一**：`StatsAggregateJob`（滚动窗口 upsert）与 `StatsService.rebuild()`（全量重算）**走同一条 upsert 语句** ⇒ 「job 算的」与「rebuild 算的」不可能算出两个口径（`.sql:883-884`）。
- ⚠️ **`Source` 列在本表恒空**（commit 4 加的，见 §13.6）：本表的行由 `StatsService.computeAndUpsert` **唯一**写出、`POST /admin/stats/rebuild` 先 `deleteAll` 再从 `order` 重算 ⇒ **不走 `seed-demo.sql`**。⇒ 种子灌完后统计表里确实全是种子订单重算出来的行，但列上仍是 `''`。**列留着只为 11 张表形状一致**，**不是**「这张表干净不干净」的判据（`.sql:1114-1118`）。

### 13.3 `goods_sku` —— 多规格矩阵（可视化段 commit 3 补丁） {#db-13-3}

`.sql:1063-1078`（`CREATE TABLE IF NOT EXISTS`；照 `order_item` 先例 —— 软引用、不设 FK、`created_at` / `updated_at` 齐备）。一行 = **一个规格组合**。

| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | 内部主键 |
| `GoodsId` | int | NOT NULL | 父商品 `goods.Id`（**软引用，不设 FK**） |
| `MerchantId` | **char(20)** | NOT NULL DEFAULT '' | 冗余自 `goods`；⚠️ 取宽**对齐 `goods.MerchantId`（char(20)）**，与 `order_item.MerchantId`（varchar(40)，对齐 `order`）**不同** —— 各自向**源列**对齐，是既定口径（同 `cart` 的说明），**不是**新缺陷 |
| `GoodName` | **char(40)** | NOT NULL DEFAULT '' | 商品名快照（免联查）；取宽对齐 `goods.GoodName` |
| `AxisName` | varchar(255) | NOT NULL DEFAULT '' | 规格组合标签（如「深空灰 / M」）= 模板各轴取值做**笛卡尔积** |
| `Price` | double | NOT NULL DEFAULT 0 | 该组合的价格（商家逐格填） |
| `Stock` | int | NOT NULL DEFAULT 0 | 该组合的库存 —— ⚠️ **本轮只存不扣**（用户已同意单独一轮；下单**不会**减它） |
| `SkuCode` | varchar(40) | NOT NULL DEFAULT '' | SKU 编码（商家填）；**详情页加入购物车时作为 `ProductCode` 上报** ⇒ 下单 / 购物车链路**不改** |
| `Sort` | int | NOT NULL DEFAULT 0 | 展示排序（升序） |
| `created_at` / `updated_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP〔`updated_at` 另带 `ON UPDATE`〕 | 行落库 / 更新时刻（⚠️ 本表是 **datetime**（无小数位），与上面三张 `datetime(3)` 不同 —— 未启用毫秒精度，不是笔误） |

- 主键 `Id`；**UNIQUE `uk_goods_sku_goods_axis (GoodsId, AxisName)`**（第 7 个非主键 UNIQUE；⚠️ `AxisName` 为空时另算 —— **NULL 不进唯一键**，而本列 `NOT NULL DEFAULT ''` ⇒ 空串会进）+ 普通 KEY `idx_goods_sku_merchant_goods (MerchantId, GoodName)`。
- 结构由**模板**给出（`template.Kind = 'structured'`，正文一行一条轴 `轴名=值1,值2,…`），价格 / 库存 / 编码留空由商家**逐格填**。

### 13.4 `login` + `CreateTime` —— 注册时刻（可视化段 commit 3） {#db-13-4}

`.sql:927-931`：**两条** `ALTER TABLE`，**刻意分两步**（这是本条的要点）。

| 列 | 类型 | 默认 | 备注 |
|---|---|---|---|
| `CreateTime` | datetime(3) | **NULL**（存量行）→ `CURRENT_TIMESTAMP(3)`（此后新行） | 注册时刻（库时钟 UTC）；管理端「用户增长」曲线的**唯一**数据源 |

- ⚠️ **为什么必须两步**：① `ADD COLUMN … DEFAULT NULL` ⇒ **已有行拿 NULL**；② `MODIFY COLUMN … DEFAULT CURRENT_TIMESTAMP(3)` ⇒ **此后**新注册的行自动取库时钟（写侧 `UserDao.insertLoginCustomer/Merchant` 的 INSERT **一行不用改**）。若**一步**写成 `ADD COLUMN … DEFAULT CURRENT_TIMESTAMP(3)`，MySQL 会用「执行 ALTER 的那一刻」**填满存量行** ⇒ 所有老账号被伪装成同一秒注册、增长曲线**凭空竖起一根假柱**（`.sql:912-918`）。
- ⚠️ 第二步**不能**用 `ALTER COLUMN … SET DEFAULT`：MySQL 8.4 对该子句报 `ERROR 1064`（它只吃字面量默认值）；且 `MODIFY` 要**重写整列定义 + COMMENT**，否则注释被清空（`.sql:920-922`，实测见 `db-migration.md` 注意 16）。
- ⚠️ **存量行一律 NULL、不回填**：注册时刻没有第二个来源、回填性为 **0** ⇒ 老账号只能记「不知道」（同 `order_item` 不回填存量单）。VM 实测：两步之后 `SELECT COUNT(*), COUNT(CreateTime) FROM login` = **8, 0**。
- ⚠️ **加列不幂等**（MySQL 无 `ADD COLUMN IF NOT EXISTS`）：CI 整份重导 ⇒ 每轮只执行一次；VM / 本机库手动执行一次，**重复执行报 Duplicate column（预期，非故障）**（同块 2 / 块 3 的口径）。

### 13.5 商品侧三处加列（可视化段 commit 3 补丁） {#db-13-5}

出处 `.sql:1028-1056`，三段各自走 `information_schema` 守卫 + `PREPARE`（**每个 ALTER 组只按组内首列守卫**：同组各列必然同批加，不出现「加了一半」的中间态）。**只加列 / 加表 / 灌种子 / 修一处正文**，不改既有列、不删行、不动其它既有数据。

| 表 | 加了什么 | 默认 | 为什么要加 |
|---|---|---|---|
| `goods` | 4 个服务承诺布尔列：`PromiseGenuine` / `PromiseReturn7` / `PromiseFastRefund` / `PromiseFreightIns` | **`tinyint(1) NOT NULL DEFAULT 1`** | 默认 **1 = 保持现状**：详情页 `.promise` 改前对**每件**商品无条件渲染四条 ⇒ 旧行默认全 1 后仍显示四条（**向后兼容**，硬线「不能阻碍旧功能」） |
| `goods_audit` | 同样 4 列（**提审快照**）+ `SkuSpec mediumtext` | 承诺列 `DEFAULT 1`；`SkuSpec` NULL | ⚠️ 提审制下 `goods` 行由「审核通过」时用**提审行重建**（`GoodsAuditService.writeGoodsFromAudit`）⇒ 承诺位与 SKU 快照**必须随提审单走**，否则通过后新行里这两处为空。`SkuSpec` 存 **JSON 数组串**（每元素 `{spec,price,stock,code}`）—— 与 `order.Items` 同款「结构化用 JSON 串」口径（自造分隔符会因规格名里出现该字符而错位） |
| `template` | `Kind varchar(16) NOT NULL DEFAULT 'text'` | `'text'` | **写侧分流**的依据：`text` = 文本模板（填一段话；详情 / 售后 / 驳回理由）/ `structured` = 结构化模板（规格矩阵的行列定义）。默认 `text` ⇒ 既有 13 行种子（含通知 / 理由类）**行为不变**。⚠️ **字符串码不建 DB ENUM**（同 `template.Type` 口径） |

- 同块的**数据**部分（非结构）：`.sql:962-1005` 给 `template` 灌 **6 个 type / 13 行**默认模板（商品三件套 + 驳回 / 封禁理由三类）、`.sql:1086-1090` 再灌 **1 行 `Kind='structured'`** 的「服装规格模板」；`.sql:1096-1099` 是一个**修数据**的 `UPDATE`（把 `goods.spec` 两条种子的正文里**全角 `：`** 改成半角 `=` —— 页面按行内第一个 `=` 切左右两列，全角切不开 ⇒ 整行塞进右列），精确到 `Name`、**不做全表 REPLACE**。
- ⚠️ 幂等一律 `ON DUPLICATE KEY UPDATE Id = template.Id`（撞 `uk_template_type_name` 时**啥也不改**）⇒ 整份重导正常插入、VM / 本机库重跑**不覆盖管理员改过的模板**。**种子只是起点，不是权威副本**。不用 `INSERT IGNORE`（吞无关错误）、不用 `VALUES()`（8.0.20 起废弃）。

### 13.6 `Source` 标记列 —— 11 张表各一列（可视化段 commit 4） {#db-13-6}

`.sql:1133-1219`：**11 段**同形语句（`information_schema` 守卫 + `PREPARE`，重复执行走 `DO 0`），每段 `ALTER TABLE … ADD COLUMN \`Source\` varchar(16) NOT NULL DEFAULT ''`。表清单：`login` · `user` · `goods` · `goods_audit` · `order` · `order_item` · `cart` · `notification` · `admin_audit` · `merchant_application` · `stats_daily`。

- ⚠️ **语义（不能简写成「非种子」）**：`''` = **存量或真实业务**写入；`'seed'` = **`seed-demo.sql` 写的那一行**。⇒ 撤种子必须 `DELETE … WHERE Source = 'seed'`，**不能**按「表里有 Source 列」删（真实买家下单写进来的新行同样是 `''`）。这个列判别的**只有一件事**：这一行是不是 `seed-demo.sql` 写的（`.sql:1111-1113`）。
- ⚠️ **`stats_daily` 的这一列恒空**（理由见 §13.2 末条）。
- **回滚**（逻辑废弃，执行前须人工确认）：11 条 `DROP COLUMN Source` 已逐表写在 `.sql:1119-1130` 的块注释里。
- ⚠️ 本块**只加列**：不改既有列、不删行、不改既有数据（规则 v2）。

### 13.7 本笔对基线的净变化 {#db-13-7}

| 基线 | 变化 | 判据档位 |
|---|---|---|
| 表数 | **19 → 22**（+`order_item` → +`stats_daily` → +`goods_sku`） | 可复核（VM 库 22 表实测） |
| 行首 `CREATE TABLE` 条数 | **21 → 24** | 可复核（`grep -c '^CREATE TABLE'` = 24，2026-10-11 实测）。⚠️ 头部原文的「共 20 个 `CREATE TABLE` / 净 18 张表」**本就少算 1**（漏了 §12 的 `order_shipping_track`，`:805`）⇒ 正确的前值应为 **21 / 19**；两处已在本笔同日订正 |
| 索引 | **23 → 30**（+`uk_order_item_order_product` / +`idx_order_item_goods_time` / +`idx_order_item_merchant_time` / +`uk_stats_daily_scope_date_merchant` / +`idx_stats_daily_scope_date` / +`uk_goods_sku_goods_axis` / +`idx_goods_sku_merchant_goods` = 净 +7） | 可复核（VM 库 `information_schema.STATISTICS` 实测 = **30**，2026-10-11；⚠️ §2 行内的「共 20 个」是 **17 张表口径**的旧实测，未含 `cart`（2）/ `order_shipping_track`（1）⇒ 30 − 2 − 1 = 27 ≠ 20 是**本文件既有的陈旧计数**，不是本笔引入的差） |
| 非主键 UNIQUE | **4 → 7**（+ 上面三个 `uk_*`） | 可复核（VM 库 `NON_UNIQUE=0 AND INDEX_NAME<>'PRIMARY'` 实测 = **7**：存活的是 `uk_order_orderId` / `uk_goodstype_parent_name` / `uk_template_type_name` / `uk_cart_customer_product` / `uk_order_item_order_product` / `uk_stats_daily_scope_date_merchant` / `uk_goods_sku_goods_axis`） |
| 加列 | `login` +1 · `goods` +4 · `goods_audit` +5 · `template` +1 · 11 张表各 +1 `Source` | 可复核（`information_schema.COLUMNS` 逐列实测） |
| 种子行数（P4 判据 `order/goods/login/user/friend`） | **不变** `6/5/5/5/4`（**全是加列**，`ALTER ADD COLUMN` 不改行数） | 可复核 |
| A3 P3 门禁 | `KNOWN_TABLES` **10 → 11**、期望表数 **21 → 22**（IN 清单加 `'goods_sku'`）—— ⚠️ **四处同改**，见 `ConcurrencyRunner.java` 的 P3 段注释 | 可复核（`db-migration.md` 注意 17） |

> ⚠️ 索引 / UNIQUE 两处计数**已按 VM 库 `information_schema.STATISTICS` 实测校正**为 **30 / 7**（2026-10-11；本节初稿那句「按 DDL 推导、不是实测」**作废**，档位以上表两行写的「可复核」为准）。
> ⚠️ **`.sql` 的行尾仍是 CRLF**：`1219 行 / LF 1219 / CR 1219 / 裸 CR 0`（2026-10-11 实测，本笔追加三个块之后重新数过）。

### 13.8 演示种子 `seed-demo.sql` 的口径（D 笔） {#db-13-8}

文件在**仓库根**（不在 `mall_management_sql.sql` 里）：`seed-demo.sql`，919 行 / 158.7 KiB；生成器 `scripts/seed-demo-gen.mjs`（`mulberry32(20261011)` ⇒ 同一天重跑**逐字相同**）；灌库后自查 `scripts/probes/seed-verify.sql`（18 条只读）；证据 `docs/agent/evidence/d-seed/`。

1. **两条跑法**（顺序不能反）：① 先跑 `mall_management_sql.sql` 末尾的 Migration 块（建 `Source` 列，**只做一次**）→ ② 再灌 `seed-demo.sql` → ③ 最后打一次 `POST /admin/stats/rebuild`（统计表由 `order` 重算，**不是种子文件写的**）。
2. **周末低谷是按「执行日」算的，不是按生成时的相位钉死**：`order` / `order_item` 两条 INSERT 带
   `WHERE (v.gate = 0 OR DAYOFWEEK(DATE_SUB(CURDATE(), INTERVAL v.k DAY)) BETWEEN 2 AND 6)`
   —— `gate=0` 的行**每天保底**（趋势图连续不跳日），`gate=1` 的行**只落工作日**。好处：换一天执行，低谷仍落在**真实的**周末。
3. **`order.Price` 的口径**（沿用 `OrderService`）：`Price` = 这一单的**小计**、`Quantity` = **总件数**；`order_item.Subtotal` 逐行小计，**`Σ Subtotal = order.Price`**。
4. **多品单只挂一个商家**：`order.MerchantId` 是全单**唯一**字段 ⇒ 多品也要从**同一商家**的货里挑；否则 `order_item.MerchantId` / `ProductCode` 对不上 `goods`，`selectTopGoods` 的 `(MerchantId, ProductCode)` 归类会落空（`evidence/d-seed/README.md` 记了第一次灌库就是栽在这里）。
5. **演示口令 = 用户名**（对齐存量 `1/1`、`2222/2222` 的既有惯例）。⚠️ 这是**明文口令** —— **若 push 被 `[Credential Leakage]` 拦，改占位符再 amend，不要硬推**。
6. **撤种子** = `DELETE … WHERE Source = 'seed'`（见 §13.6），**存量数据一行不动**；`stats_daily` 由 rebuild 自己覆盖，不在此列。

## 14. 附录：C3 块 5〈营销块〉（2026-10-11/12）的 8 张新表 · 6 处加列 · 1 个新索引 {#db-14}

> 与 §11 / §12 / §13 同一手法：**§1 表清单与 §6.x 刻意不插入**（零漂移），块 5 的库变更全部落在本附录里。
> 权威源 = `mall_management_sql.sql` 末尾的**四个** Migration 块（A `:1222` / B `:1427` / C `:1455` / D `:1480`，段内编号 ①~⑭ 跨段连续）；登记表见 `db-migration.md` #16–#29 + 注意 18。

### 14.1 八张新表 {#db-14-1}

| 表名 | 用途（块内编号） | 主键 | 关键列 | 索引（除主键外） | 证据（`.sql` 行号） |
|---|---|---|---|---|---|
| `coupon` | 优惠券定义（①，A1 券） | `Id` 自增 | `CouponId`(业务串) · `Name` · `Type`(threshold/percent/none) · `FaceValue` · `Threshold` · `TotalQty` · `ClaimedQty` · `Scope`(all/merchant) · `MerchantAccount`(**空串 = 平台券**) · `CategoryMatch` · `StartAt`/`EndAt` · `Status`(active/stopped) | `uk_coupon_couponId(CouponId)` UNIQUE · `idx_coupon_merchant_status(MerchantAccount,Status)` | `:1247-1267` |
| `coupon_grant` | 券领取/发放记录（②，A1 券） | `Id` 自增 | `CouponId` · `ToAccount`(领取人) · `Source`(claim 自助/grant 定向) · `Status`(unused/used) · `UsedAt` · `OrderId`(核销单) | `uk_coupon_grant_coupon_acct(CouponId,ToAccount)` UNIQUE · `idx_coupon_grant_acct_status(ToAccount,Status)` · `idx_coupon_grant_coupon(CouponId)` | `:1270-1284` |
| `subsidy_rule` | 国补规则（③，A3 国补） | `Id` 自增 | `RuleId`(业务串) · `Name` · `CategoryMatch` · `Ratio`(decimal(6,4)，0.1500=15%) · `Cap`(单笔上限，0=不封顶) · `StartAt`/`EndAt` · `Status` | `uk_subsidy_rule_ruleId(RuleId)` UNIQUE · `idx_subsidy_rule_status(Status)` | `:1287-1302` |
| `subsidy_goods` | 国补商品提名/确认（④，A3·双向） | `Id` 自增 | `RuleId` · `GoodsId`(软引用 `goods.Id`) · `GoodName`(快照) · `MerchantId` · `Status`(pending/confirmed/rejected) · `Reason` | `uk_subsidy_goods_rule_goods(RuleId,GoodsId)` UNIQUE · `idx_subsidy_goods_merchant_status(MerchantId,Status)` | `:1305-1318` |
| `seckill_invitation` | 秒杀邀请（⑤，A4 · 管理端→商家） | `Id` 自增 | `InvitationId` · `MerchantAccount` · `GoodsId` · `OriginalPrice`/`SeckillPrice`(快照) · `Quantity` · `ApplyEndAt`/`StartAt`/`EndAt` · `Status`(invited/accepted/declined) · `Reason` | `uk_seckill_invitation_id(InvitationId)` UNIQUE · `idx_seckill_invitation_merchant_status` · `idx_seckill_invitation_goods` | `:1321-1341` |
| `seckill_application` | 秒杀申请（⑥，A4 · 商家→管理端） | `Id` 自增 | `ApplicationId` · 其余同 `seckill_invitation`；`Status`(submitted/approved/rejected) | `uk_seckill_application_id(ApplicationId)` UNIQUE · `idx_seckill_application_merchant_status` · `idx_seckill_application_goods` | `:1344-1364` |
| `seckill_activity` | **成立的**秒杀（⑦，A4 · **用户端读侧唯一来源**） | `Id` 自增 | `ActivityId` · `Name` · `MerchantAccount` · `GoodsId` · 价/量快照 · `TotalStock` · **`SoldStock`**（块 5 手动置满演示，**真扣减挂块 7**）· `SourceKind`/`SourceId`(来源报名单) · `Status`(active/stopped) | `uk_seckill_activity_id(ActivityId)` UNIQUE · `idx_seckill_activity_merchant_status` · `idx_seckill_activity_goods` · `idx_seckill_activity_window(StartAt,EndAt)` | `:1368-1392` |
| `shop_follow` | 店铺关注关系（⑫，B 组；买家 → 商家） | `Id` 自增 | `CustomerId`(对齐 `user.customerid`) · `MerchantId`(对齐 `user.merchantid`) | `uk_shop_follow_cust_merchant(CustomerId,MerchantId)` UNIQUE · `idx_shop_follow_merchant_created` · `idx_shop_follow_cust_created` | `:1442-1452` |

⚠️ **`shop_follow` 的取关 = 物理 `DELETE` 行**（关系表取关即删，与「业务实体不物理删」不冲突 —— 用户 2026-10-12 裁定 8）。
⚠️ **三张 `seckill_*` 是「同一件事的三个阶段」**：邀请 / 申请是**流程表**（谁向谁发起），`seckill_activity` 是**成立的具体活动**（用户端 `/marketing/seckill/live` 只读它）。

### 14.2 六处加列 {#db-14-2}

| # | 表.列 | 类型 | 默认 | 语义 | 证据 |
|---|---|---|---|---|---|
| ⑧ | `goods.OriginalPrice` | `decimal(10,2)` | `0.00` | **划线原价**（0 = 无；折扣率读侧现算，不落列） | `.sql:1394-1400`（ADD `:1396`） |
| ⑨ | `order.CouponId` | `varchar(40)` | `''` | 核销券业务串（**空串 = 没用券**，不是 NULL） | `.sql:1402-1408`（ADD `:1404`） |
| ⑩ | `order.SubsidyAmount` | `decimal(10,2)` | `0.00` | 国补额快照 | `.sql:1410-1416`（ADD `:1412`） |
| ⑪ | `order.PayAmount` | `decimal(10,2)` | `0.00` | 实付额快照（**原 `Price` 保留 = 原价合计**） | `.sql:1418-1424`（ADD `:1420`） |
| ⑬ | `goods_audit.OriginalPrice` | `decimal(10,2)` | `0.00` | 划线原价的**提审快照** —— 上架是提审制，`goods` 行由审核通过时用提审行**重建** ⇒ 只加 `goods` 侧会在提审那步丢掉商家填的值 | `.sql:1471-1477`（ADD `:1473`） |
| — | （说明）`goods_audit.OriginalPrice` 与 `goods.OriginalPrice` **两处都要**，不是重复 | — | — | 理由同上；块内逐字注释 `.sql:1458-1466` | — |

### 14.3 一处新索引 {#db-14-3}

| # | 索引 | 类型 | 动机 | 证据 |
|---|---|---|---|---|
| ⑭ | `goods_sku` 的 `idx_goods_sku_sku_code(SkuCode)` | **普通索引（非 UNIQUE）** | `SkuCode` 就是加购上报的 `ProductCode` ⇒ `/cart/add` 与 `CartDao` 的 `SKU_BY_CODE` 都按它反查，而它此前**既无唯一约束也无索引** ⇒ 那两条是**全表扫** | `.sql:1499-1505`（ADD `:1501`） |

⚠️ **为什么不是 UNIQUE**：全仓**没有任何一处**声明「`SkuCode` 唯一」是既定不变量（建表注释只说「商家填」）⇒ 改成 UNIQUE 是**新增一条契约**，超出「加索引」（块内理由 `.sql:1483-1492`）。

### 14.4 本笔对基线的净变化 {#db-14-4}

| 基线 | 变化 | 判据档位 |
|---|---|---|
| 表数 | **22 → 30**（+8 张，见 §14.1） | 可复核（VM 库 `information_schema.TABLES` 实测 = **30**，2026-10-12） |
| 行首 `CREATE TABLE` 条数 | **24 → 32** | 可复核（`grep -c '^CREATE TABLE' mall_management_sql.sql` = **32**，2026-10-12 实测） |
| 加列 | `goods` +1 · `order` +3 · `goods_audit` +1 | 可复核（`information_schema.COLUMNS` 逐列实测类型/默认值，见 §14.2） |
| 索引 | **30 → 30 + 8 张新表自带**（+`goods_sku` 1 个普通索引）；⚠️ 新表各自带的 UNIQUE / KEY 记在 §14.1，**本笔未重算全库总数** | 可复核（新表索引由 DDL 直接可数；`idx_goods_sku_sku_code` 由 VM 库 `information_schema.STATISTICS` 实测在册） |
| 种子行数（P4 判据 `order/goods/login/user/friend`） | **不变**（**全是加列 + 建表**，`ALTER ADD COLUMN` 不改行数，新表 0 行） | 可复核 |
| A3 P3 门禁 | `KNOWN_TABLES` **11 → 18 → 19**、期望表数 **22 → 29 → 30**（段 A / 段 B 各一次；段 C / 段 D 不动门禁）—— ⚠️ **每次四处同改**，见 `ConcurrencyRunner.java` 的 P3 段注释 | 可复核（`db-migration.md` 注意 18） |

⚠️ **`.sql` 的行尾仍是 CRLF**：`1505 行 / LF 1505 / CR 1505 / 裸 CR 0`（2026-10-12 实测，块 5 四个块追加之后重新数过）。
⚠️ **本附录的「档位」一律标了出处**：类型 / 默认值 / 索引名 = **可复核**（实读库）；索引总数未重算 ⇒ **不写数**（避免又添一处像 §2 那样的陈旧计数）。

## 15. 附录：C3 块 6〈内容+消息块〉（2026-10-12/13）的 6 张新表 · 2 处加列 · 15 个新索引 {#db-15}

> 与 §11 / §12 / §13 / §14 同一手法：**§1 表清单与 §6.x 刻意不插入**（零漂移），块 6 的库变更全部落在本附录里。
> 权威源 = `mall_management_sql.sql` 末尾的**两个** Migration 块（⑮，段头 `:1507-1526`，段内编号 ⑮-1~⑮-8 连续；段体 `:1528-1634`）＋ **⑯**（整改补笔，2026-10-13：段头 `:1636-1639`，⑯-1 生成列 `:1641-1678` / ⑯-2 唯一键 `:1680-1686`）；登记表见 `db-migration.md` **#30–#37** + 注意 **19–20**。
> ⚠️ **块 6 的库变更只有「加表 6 + 加列 2 + 加索引 15」** —— 不改既有列、不删行、不改既有数据（规则 v2：向后兼容）。

### 15.1 六张新表 {#db-15-1}

| 表名 | 用途（段内编号） | 主键 | 关键列 | 索引（除主键外） | 证据（`.sql` 行号） |
|---|---|---|---|---|---|
| `comment` | **统一评论表**（⑮-1）—— 评价 / 提问 / 回答 / 楼中楼**同一张表**，`Kind` 分流 | `Id` 自增 | `TargetType`(goods/user) · `TargetId` · `ParentId`(NULL=顶层；≠NULL=回复，**指向顶层行**) · `ReplyToId`(回复的回复，两层封顶) · `Kind`(review/question/answer) · `AuthorId` · `AuthorRole`(1 用户/2 商家) · `IsAnonymous`(匿名⇒强制 5 星) · `Rating`(1-5；问答=0) · `Content`(敏感词命中<40% 已就地屏蔽为 `***`) · `Status`(normal/deleted，**逻辑删**) · `IsSolved`/`AcceptedReplyId`(问答采纳) · `IsMerchantFirst`(商家首答置顶) · `ProductCode`(购买型号，评论时从历史订单选) · `LikeCount`/`DislikeCount`(**冗余计数**) · `created_at`/`updated_at` · **`ReviewLockKey`**（⑯-1 **STORED 生成列**：仅**顶层商品评价**有值，其余 NULL） | `idx_comment_target(TargetType,TargetId,created_at)` · `idx_comment_parent(ParentId,created_at)` · `idx_comment_author(AuthorId,created_at)` · `idx_comment_kind(Kind,TargetType,TargetId)` · **`uk_comment_review_once(ReviewLockKey)` UNIQUE**（⑯-2） | `:1528-1555` + `:1641-1686` |
| `comment_mention` | 评论 **@ 关系**（⑮-2）—— 后端自动生成，**无前端输入框** | `Id` 自增 | `CommentId`(comment.Id) · `MentionedId`(被 @ 的账号，对齐 `login.UserName`) · `created_at` | `idx_mention_comment(CommentId)` · `idx_mention_user(MentionedId,created_at)` | `:1557-1566` |
| `comment_image` | 评论图片（⑮-3）—— **先审后发** | `Id` 自增 | `CommentId` · `ImageUrl`(`/img/comment/xxx`) · **`Status`(pending 待审 / approved 通过 / rejected 不合规〔已删仓库文件〕)** · `SortOrder` · `created_at` | `idx_image_comment(CommentId,SortOrder)` · `idx_image_status(Status,created_at)` | `:1568-1579` |
| `comment_like` | 评论**点赞 / 踩**（⑮-4）—— 同一用户对同一评论**只能赞或踩一次** | `Id` 自增 | `CommentId`(顶层 or 回复的 Id，都是 `comment` 行) · `UserId` · `Type`(like/dislike) · `created_at` | **`uk_like_comment_user(CommentId,UserId)` UNIQUE** · `idx_like_user(UserId,created_at)` | `:1581-1591` |
| `report` | **举报单**（⑮-5）—— 同内容同人不能二次举报；**驳回后再举报 = `UPDATE` 同一行复活** | `Id` 自增 | `TargetType`(comment/goods/user) · `TargetId` · `ReporterId` · `ReporterRole`(1 用户/2 商家) · `Reason`(辱骂/广告/违法/其他) · `Description`(理由=其他 时填) · `Status`(pending/handled/rejected) · `HandlerId` · `HandleNote` · `created_at`/`updated_at` | **`uk_report_target_reporter(TargetType,TargetId,ReporterId)` UNIQUE** · `idx_report_status(Status,created_at)` · `idx_report_reporter(ReporterId,created_at)` | `:1593-1611` |
| `sensitive_word` | **敏感词库**（⑮-6）—— 预置 10 词（⑮-8 种子） | `Id` 自增 | `Word` · `created_at`/`updated_at` | **`uk_sensitive_word(Word)` UNIQUE** | `:1613-1621` |

⚠️ **`comment` 一表装四件事**：`Kind` 分流 `review` / `question` / `answer`，`ParentId` 永远指向**顶层行**、「回复某条回复」用 `ReplyToId` 表达（**两层封顶**）⇒ **别按表名当「评论表」读** —— 评价、留言、楼中楼、问答**都在这张表里**（段头 `.sql:1528` 注释写明）。
⚠️ **`LikeCount` / `DislikeCount` 是冗余计数**，真值在 `comment_like`（`COUNT(*)` by `Type`）—— 两处必须**同步回填**，种子若手写一个对不上的数，页面上的互动数就成了假话（`evidence/c3-block6/b6-c7-fake-data.txt` §5 第 5 条记了这条纪律）。
⚠️ **`Status` 一律是受控字符串码，不建 DB `ENUM`**（`comment_image` 三态 / `report` 三态 / `comment` 二态）—— 同 `template.Kind` 的口径：取值受控在应用层，改值不用动 DDL。
⚠️ **六张表都带 `created_at`**（`comment` / `report` / `sensitive_word` 另带 `updated_at`）—— 满足 `agent.md` 数据库管理规则 8（表必须有时间列）。

### 15.2 两处加列 {#db-15-2}

| # | 表.列 | 类型 | 默认 | 语义 | 证据 |
|---|---|---|---|---|---|
| ⑮-7 | `user.WarnCount` | `int NOT NULL DEFAULT 0` | `0` | **警告累计**（块 6 举报链）：达 **3** 自动封号（写 `user.banned=1`）；**解封后由管理端手动清零** | `.sql:1623-1629`（加列走 `information_schema.COLUMNS` 守卫 + `PREPARE`/`EXECUTE`，重复执行走 `DO 0`） |
| ⑯-1 | `comment.ReviewLockKey` | `varchar(120)` **GENERATED ALWAYS AS (…) STORED** | —（生成列无默认值） | **顶层商品评价的唯一锁载体**（⑯，2026-10-13 整改补笔）= `CASE WHEN TargetType='goods' AND Kind='review' AND ParentId IS NULL AND Status='normal' THEN CONCAT(TargetType,':',TargetId,':',AuthorId) ELSE NULL END`；配 ⑯-2 的 UNIQUE 一起构成「一商品一用户只能评一条评价」的 **DB 层**闸 | `.sql:1641-1678`（守卫式 `ALTER`，判据 = `information_schema.COLUMNS` 里 `ReviewLockKey` 不存在） |

⚠️ **存量行一律不变**：47 个存量用户取默认值 `0`（VM 库实测 `WarnCount` 合计 = 0，`b6-c7-fake-data.txt` §4.8）—— **不改行数、不改既有列**。
⚠️ **别新建「封号表」**：块 6 复用既有 `user.banned`（块 2 加的），`WarnCount` 只记**计数**（口径见 `.sql:1625` 的列注释）。
⚠️ **⑯-1 是「生成列」，不是普通加列** —— 它**由数据算出**、不可写入；列宽 `varchar(120)` 的算法写在 DDL 注释里（`16 + 1 + 50 + 1 + 40 = 108`，留余量；utf8mb4 下 480 B < 3072 B 索引上限）。
⚠️ **为什么是「生成列 + NULL」而不是裸的三列唯一键**：裸三列会把「同一用户对同一商品下的**第二条回复**」也锁死（那是**降级**）；NULL 在唯一键下互不相等 ⇒ 一条 DDL 同时给出四类豁免（回复 / 提问 / 回答 / 留言板）。理由逐条写在 `.sql:1646-1654`。
⚠️ **`Status='normal'` 走白名单而非 `<>'deleted'`**：与 Service 侧「作者删了自己那条后能重评」**同口径**（`.sql:1652-1654`）。

### 15.3 十五处新索引 {#db-15-3}

| # | 表 | 索引 | 类型 |
|---|---|---|---|
| 1 | `comment` | `idx_comment_target(TargetType,TargetId,created_at)` | 普通 |
| 2 | `comment` | `idx_comment_parent(ParentId,created_at)` | 普通 |
| 3 | `comment` | `idx_comment_author(AuthorId,created_at)` | 普通 |
| 4 | `comment` | `idx_comment_kind(Kind,TargetType,TargetId)` | 普通 |
| 5 | `comment_mention` | `idx_mention_comment(CommentId)` | 普通 |
| 6 | `comment_mention` | `idx_mention_user(MentionedId,created_at)` | 普通 |
| 7 | `comment_image` | `idx_image_comment(CommentId,SortOrder)` | 普通 |
| 8 | `comment_image` | `idx_image_status(Status,created_at)` | 普通 |
| 9 | `comment_like` | `uk_like_comment_user(CommentId,UserId)` | **UNIQUE** |
| 10 | `comment_like` | `idx_like_user(UserId,created_at)` | 普通 |
| 11 | `report` | `uk_report_target_reporter(TargetType,TargetId,ReporterId)` | **UNIQUE** |
| 12 | `report` | `idx_report_status(Status,created_at)` | 普通 |
| 13 | `report` | `idx_report_reporter(ReporterId,created_at)` | 普通 |
| 14 | `sensitive_word` | `uk_sensitive_word(Word)` | **UNIQUE** |
| 15 | `comment` | `uk_comment_review_once(ReviewLockKey)`（⑯-2，2026-10-13 整改补笔） | **UNIQUE** |

⚠️ **四个 UNIQUE 各是一道业务闸**，不是纯性能索引：`uk_like_comment_user` = 「同人同评论只能赞或踩一次」；`uk_report_target_reporter` = 「同内容同人不能二次举报」（**驳回后再举报靠 `UPDATE` 同一行复活**，正因有这条唯一键才不会写出两行）；`uk_sensitive_word` = 敏感词**去重**（⑮-8 的 `INSERT IGNORE` 幂等就靠它）；**`uk_comment_review_once` = 「一商品一用户只能评一条评价」的 DB 层兜底**（Service 侧那道预检是「先查后插」，两个并发请求可以同时通过预检 ⇒ 必须有 DB 侧闸；**它不是**把预检删掉、而是同一判据的 DB 版本）。
⚠️ **15 个索引均**由 §15.1 的 DDL **逐条可数**；全库总数另**实测**过一次（2026-10-13 整改后重测，`information_schema`，口径 = `COUNT(DISTINCT 表.索引名)` 且 `INDEX_NAME <> 'PRIMARY'`）：**非主键索引 68 / 非主键 UNIQUE 19**（⑯ 之前那次是 **67 / 18** ⇒ ⑯ 净 **+1 索引 / +1 UNIQUE**，对得上）。⚠️ **别和 §14.4 那个「30」做减法** —— 那是块 5 当年**未重算**的写法，口径不同；本笔起以实测口径为准。

### 15.4 本笔对基线的净变化 {#db-15-4}

| 基线 | 变化 | 判据档位 |
|---|---|---|
| 表数 | **30 → 36**（+6 张，见 §15.1） | 可复核（VM 库 `information_schema.TABLES` 实测 = **36**，2026-10-13；`b6-c7-fake-data.txt` §4.10 六表行数逐张命中） |
| 行首 `CREATE TABLE` 条数 | **32 → 38** | 可复核（实读 `.sql`：`CREATE TABLE` 行首 = **38**，2026-10-13 实测） |
| 加列 | `user` **+1**（`WarnCount`）+ `comment` **+1**（`ReviewLockKey` 生成列，⑯-1）⇒ 共 **+2** | 可复核（`.sql:1623-1629` / `.sql:1641-1678` 守卫式 `ALTER`；VM 库 `information_schema.COLUMNS` 实读可用，§15.2） |
| 索引 | **+15**（含 UNIQUE **4**，见 §15.3；⑯-2 是第 15 个 / 第 4 个 UNIQUE）；全库实测 **非主键 68 / 非主键 UNIQUE 19**（2026-10-13 整改后重测，口径 = 排除主键与 `PRIMARY`） | 可复核（新表索引由 DDL 逐条可数；全库数见 §15.3 那一段的实测命令） |
| 种子行数（P4 判据 `order/goods/login/user/friend`） | **不变**（纯「加表 + 加列」，`ALTER ADD COLUMN` 不改行数，新表 0 行） | 可复核 |
| A3 P3 门禁 | `KNOWN_TABLES` **19 → 25**、期望表数 **30 → 36**（同笔一处；⚠️ **四处同改**，见 `ConcurrencyRunner.java:2664`/`:2670`） | 可复核（`db-migration.md` 注意 19） |

⚠️ **`.sql` 的行尾仍是 CRLF**：`1686 行 / LF 1686 / CR 1686 / 裸 CR 0`（2026-10-13 实测，**⑯ 块追加之后**重新数过；⑯ 之前是 `1634 行`）。
⚠️ **本附录的「档位」一律标了出处**：类型 / 默认值 / 索引名 = **可复核**（实读 DDL）；表数 36 = **可复核**（VM 库 `information_schema` 实测，`b6-c7-fake-data.txt` §4.11）；索引 **68 / UNIQUE 19** = **可复核**（2026-10-13 整改后重测，命令见 §15.3）；`user.WarnCount` 另有 VM 库实读（同证据 §4.8）。
⚠️ **跨文件口径必须一致**：本条的表数（**36**，⑯ 不改表数）、`db-migration.md` 注意 19–20、`slot-registry.md §二十三`、`progress.md §7-B6` **同源**；`api-contract.md` 附录 G 是**端点**侧（与本条无关，内容域 **14** 条端点）。

## 16. 附录：C3 块 7〈可观测域 + 库存治理〉· commit 8b《事件台账 + 清理》的库变更（2026-10-14） {#db-16}

> 与 §11–§15 同一手法：**§1 表清单与 §6.x 刻意不插入**（零漂移），库变更全部落在本附录里。
> 权威源 = `mall_management_sql.sql` 末尾的**第四个** Migration 段（**⑲**，段头 `:1832-1863`，⑲-1 建表 `:1865-1887`，⑲-2..⑲-5 加列 `:1888-1918`）；登记表见 `db-migration.md` **#40** + 注意 **23**。
> ⚠️ **本附录只登 ⑲ 这一段。** 块 7 的前两段（**⑰** = 建 `drill_record` + 8 列；**⑱** = `order` 加 `StockDeducted`）**已由 commit 9（收口）登在 §17**（原文写「本文件尚未登记 —— 归块 7 commit 9」）—— 那一段的登记（`drill_record` 12 列、`goods_sku.BaselineStock`、`seckill_activity.BaselineSoldStock`、块 6 六表各自加 `Source`、`order.StockDeducted`）**见下 §17**；`db-migration.md` 注意 22 那句「本笔只登 `db-migration.md`，不改 `db-schema.md`」说的是**当时**（commit 0 那笔）的口径。
> ⚠️ **本段的库变更只有「加表 1 + 加列 4」** —— 不改既有列、不删行、不改既有数据（规则 v2：向后兼容）；且**只建表、不接写入**（写入方 = 8c 的 MQ consumer）⇒ 本笔之后这张表**是空的、0 行**。
> ⚠️ **两条线上的表数别互相减**：本条的表数是 **38**，§15.4 是块 6 的 **36**（中间隔着 ⑰ 的 +1）；本文件§15.4 索引一栏的最后数字是 **68**（块 6 口径），本条的基线 **69** 来自 ⑰ 之后的实测（`db-migration.md` 注意 21）—— **69 这个数不在本文件里**。

### 16.1 `observed_event` —— 八环事件落库台账（⑲-1，`.sql:1866-1883`） {#db-16-1}

| 列 | 类型 / 约束 | 可空 | 默认 | 语义（列注释逐字） |
|---|---|---|---|---|
| `Id` | `bigint AUTO_INCREMENT` | NO | — | 自增主键 |
| `Kind` | `varchar(16)` | NO | —（**必填**） | 事件所属环：`cache` / `lock` / `order` / `stock` / `audit` / `notify` / `sensitive` / `identity` / `protection`（对齐前端 `KIND_LABEL`；第 9 类 `protection` 由 D 段 commit 2 加，2026-10-15，`.sql` 同步） |
| `Severity` | `varchar(16)` | NO | `info` | `info` / `warn` / `error`（对齐前端 `SEVERITY_LABEL`） |
| `Title` | `varchar(255)` | NO | `''` | 事件标题（一句话；含业务键时由后端截断） |
| `ObjectType` | `varchar(32)` | **YES** | `NULL` | 对象类型（审计 = `TargetType` / 敏感词 = 命中的词 / 身份 = 请求路径 …）；**无则 NULL** |
| `ObjectId` | `varchar(128)` | **YES** | `NULL` | 对象标识（与 `ObjectType` **成对**；对齐 `ObservedEvent.objectId`） |
| `TraceId` | `varchar(64)` | **YES** | `NULL` | 本次请求的 `traceId`（`TraceIdFilter` 发号；抽屉链路按它归组） |
| `BatchId` | `varchar(36)` | **YES** | `NULL` | 演练批次 UUID（8c 由 `X-Drill-Batch` 头注入）；**NULL = 不属于任何演练批次** |
| `OccurredAt` | `datetime(3)` | NO | —（**必填**） | 事件发生时刻（**毫秒精度**，对齐 `ObservedEvent.ts`） |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` | 行落库时刻（`agent.md` 数据库管理规则 8） |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | 行更新时刻 |

索引（**除主键外 4 个，全部非唯一**）：

| 索引 | 列 | 归属（`.sql:1884-1886` 逐条写明，**别减**） |
|---|---|---|
| `idx_observed_event_kind_time` | `(Kind,OccurredAt)` | 日志页按类过滤 + 按时刻排（前列等值 ⇒ 后列有序，反向扫即满足 `ORDER BY OccurredAt DESC`，**不 filesort**） |
| `idx_observed_event_time` | `(OccurredAt)` | **保留期扫描**（清理 job 的主查询） |
| `idx_observed_event_trace` | `(TraceId)` | 抽屉「这一次请求的完整链路」按 `traceId` 取整组 |
| `idx_observed_event_batch` | `(BatchId)` | 按演练批次**一次性清理** |

⚠️ **`Kind` 无默认值而 `Severity` 有 `info`**（DDL 字面 = 可复核）：本条的读法是「严重度缺省即 info，环别必须显式给」。两列都**不建 DB `ENUM`**（同 §15.1 的口径：受控字符串码，改值不用动 DDL）。
⚠️ **`OccurredAt` 是 `datetime(3)`，全表唯一带毫秒精度的列** —— 它与 `created_at` 不是一回事：`OccurredAt` = 事件**发生**时刻、`created_at` = 行**落库**时刻，两者之间的延迟正由这两列分别记着（同为「事件」，一个来自内存环、一个来自 DB 时钟）。
⚠️ **`ObjectType` / `ObjectId` / `TraceId` / `BatchId` 四列可空是刻意的** —— 「没有这个对象 / 没有链路号 / 不属于批次」的表达是 **NULL**，不是空串（理由同 §16.2 的 `BatchId`）。

### 16.2 四处加列 `BatchId varchar(36) DEFAULT NULL`（⑲-2..⑲-5） {#db-16-2}

| # | 表.列 | 类型 / 默认 | 语义 | 证据 |
|---|---|---|---|---|
| ⑲-2 | `drill_record.BatchId` | `varchar(36) DEFAULT NULL` | 本行是哪一次演练产生的（一次「跑攻击」一个批次） | `.sql:1888-1894` |
| ⑲-3 | `order.BatchId` | 同上 | 攻击演练下的单；按批清理的载体 | `.sql:1896-1902` |
| ⑲-4 | `comment.BatchId` | 同上 | 攻击场景 8 的评论；按批清理的载体 | `.sql:1904-1910` |
| ⑲-5 | `admin_audit.BatchId` | 同上 | 演练产生的审计行；按批清理的载体 | `.sql:1912-1918` |

四个加列**逐列一个** `information_schema.COLUMNS` 守卫 + `PREPARE`/`EXECUTE`（照 ⑬ / ⑰ / ⑱ 先例，**不写循环** —— 守卫必须能单独重跑）。

⚠️ **批文的「5 张表」与实况不符（实测更正，登记在此）**：批文列的是 `drill_record` / `order` / `comment` / `admin_audit` / **`notification`**，但库里**早就有 `BatchId`** 的是**三张**（`notification`〔块 1 · I2 建的〕+ `goods_audit` + `merchant_application`，**三张皆 `varchar(36) NOT NULL`**）⇒ 本段**只加缺的 4 张**（VM 库实测：这 4 张 `null=YES`、另 3 张 `null=NO`，`_work/b7c8b-verify.txt`）。
⚠️ **为什么新的 4 列是 `NULL`、旧的 3 列是 `NOT NULL`**：旧三张的 `BatchId` 是「**一次群发 / 一次申请的扇出批次**」，行**必然**属于某个批次；本段的 4 列反过来 —— **绝大多数行不属于任何演练批次**（真实下单 / 真实评论 / 真实审计），「不属于」必须是**没有值**。⇒ **同一个列名、不同语义**：8c 接线时若让攻击演练发通知，是把这个批次号**借写**进 `notification.BatchId`（同为 `varchar(36)`，写得进），此时**必须写清是借用**（`db-migration.md` 注意 23 逐字记了这条）。
⚠️ **`drill_record.BatchId` 还多一层语义**：本段之前记下的演练行（存量行）一律 NULL —— 它标的是「**这一行演练记录**属于哪一批」，而 `drill_record` 本来就是一跑一行；且这一列**不在**「按批清理」的目标里（清理**只看 `observed_event`**，`drill_record` 是「演练这件事本身」的记录，见 §16.3 的保留期那条）。

### 16.3 本笔对基线的净变化 {#db-16-3}

| 基线 | 变化 | 判据档位 |
|---|---|---|
| 表数 | **37 → 38**（+1：`observed_event`） | 可复核（VM 库 `information_schema.TABLES` 实测 = **38**，2026-10-14，`_work/b7c8b-verify.txt`）。⚠️ **别拿 §15.4 的 36 做减法** —— 中间隔着块 7 的 ⑰（+1 `drill_record`，登记在 `db-migration.md` 注意 21） |
| 行首 `CREATE TABLE` | **38 → 40**（块 7 三段合计 +2：⑰ `drill_record`、⑲ `observed_event`；**⑲ 自身 +1**）；`.sql` 行数同时 **1634 → 1918** | 可复核（实读本仓 `.sql`：`^CREATE TABLE` = **40**，2026-10-14 实测） |
| 加列 | 既有表 **+4**（`BatchId`，见 §16.2）；新表 11 列是**整表新建**不是加列 | 可复核（`.sql:1866-1883` 建表字面逐列可数 / `:1888-1918` 四个守卫式 `ALTER`） |
| 索引 | 非主键 **69 → 73**（+4，全是 `observed_event` 的；**UNIQUE 仍 19**）；全库实测 **73 / UNIQUE 19** | 可复核（4 条 `KEY` 由 DDL 逐条可数；全库数 = VM 库 `information_schema`，口径 = `COUNT(DISTINCT 表.索引名)` 且 `INDEX_NAME <> 'PRIMARY'`）。⚠️ 基线 **69** 不在本文件里（见本附录开头那条 —— §15.4 最后是块 6 的 **68**） |
| 种子行数（P4 判据 `order/goods/login/user/friend`） | **不变**（`ALTER ADD COLUMN` 不改行数；新表 **0 行** —— 本笔**不接写入**） | 可复核（`b7c8b-verify.txt` 实测 `observed_event` 行数 = 0） |
| A3 P3 门禁 | `KNOWN_TABLES` **26 → 27**、期望表数 **37 → 38**（同笔一处；⚠️ **四处同改**，见 `ConcurrencyRunner.java`） | 可复核（`db-migration.md` 注意 23） |

⚠️ **落库证据（第一手复核，2026-10-14）**：`_work/ApplyMigration.java` + `_work/b6-c7-db-env.sh`（凭据剥壳取、**未打印未落盘**），marker = `库存治理补完`（截到文件尾 ⇒ **⑱ + ⑲ 一次跑完**，共 **21 条语句**）⇒ 落库 **21/21 OK**、复跑第二次 **21/21 OK**（幂等确证，无 1050 / 1060）；只读复核原文落在 `_work/b7c8b-verify.txt`（**全库 38 表 / 73 索引 / 19 UNIQUE；`observed_event` 11 列 / 4 索引 / 0 行；4 张新 `BatchId` 均 `null=YES`**）。同一次复核顺带确认 ⑱ 的 `order.StockDeducted` = `tinyint(1) NOT NULL DEFAULT 0`。
⚠️ **`.sql` 的行尾仍是 CRLF**：**1918 行 / LF 1918 / CR 1918 / 裸 CR 0**（2026-10-14 实测，⑲ 块追加之后；上一版 **1634** 是块 6 之后）。
⚠️ **两档保留期（本段只是「基础设施先行」，不是已有数据在跑）**：**攻击批次 3 天 / 日常 30 天**，判据 = `BatchId IS NOT NULL` 分档、按 **`OccurredAt`** 算（**不是** `created_at` —— 判的是「事件**发生**了多久」，落库延迟不该延长保留）。⚠️ **本笔跑起来表是空的、清理 job 每轮删 0 行** —— 这不是缺陷，是「保留期」这件基础设施提前就位（写入方 8c 才接）。
⚠️ **回滚**：新表按注意 6 / 规则 v2 = 「**保留表 + 停止写入**」（`DROP TABLE` 只在本地库重建时用）；4 个加列按注意 1 = **逻辑废弃**（保留列 + 停用写入，**不得** `DROP COLUMN`）。段头 `:1856-1862` 的 `ROLLBACK` 注释逐条写了这些语句并标注「**执行前须人工确认**」。
⚠️ **`observed_event` 刻意不进文件头（`:21` 起）的 `DROP TABLE IF EXISTS` 基线清单**（同 `cart` / `drill_record` / `order_item` / `stats_daily`：它是**业务数据**不是种子）⇒ **整份重导不清空事件台账**；表结构变更必须写独立 Migration（本段即是）。
⚠️ **跨文件口径必须一致**：本条的表数（**38**）、行首 `CREATE TABLE`（**40**）、非主键索引（**73**）、`db-migration.md` 注意 21–23、`slot-registry.md` 的块 7 登记、`progress.md §7-B7`、`ConcurrencyRunner` 的 `KNOWN_TABLES`（**27**）**同源**；`api-contract.md` 的**可观测域端点**（**7** 条）是**端点**侧 —— 该文件的**附录 H** 已由 **commit 9（收口）** 落（原写「尚未落」）。
⚠️ **块 7 的 ⑰ / ⑱ 两段**（本附录写作时未登的那两段）已由 **commit 9（收口）** 登记在 **§17**（见下）—— 本附录开头那句「那两段的登记归 commit 9」**已兑现**。

## 17. 附录：C3 块 7〈可观测域 + 库存治理〉· 前两段（⑰ 演练记录 / ⑱ 库存扣成标记）的库变更（2026-10-13/14，**commit 9 补登**） {#db-17}

> 与 §11–§16 同一手法：**§1 表清单与 §6.x 刻意不插入**（零漂移），库变更全部落在本附录里。
> 权威源 = `mall_management_sql.sql` 末尾的**第一、二个**块 7 Migration 段：**⑰**（段头 `:1688-1715`，⑰-1..⑰-9 本体 `:1717-1797`）与 **⑱**（段头 `:1798-1822`，⑱-1 本体 `:1824-1830`）；登记见 `db-migration.md` **#38**（注意 21）+ **#39**（注意 22）。
> ⚠️ **本附录就是 §16 开头那条延后指针的兑现** —— 两段此前只登在 `db-migration.md`（注意 22 逐字写了「本笔只登本文件，不改 `db-schema.md`」），表结构快照（本文件）缺这一段。
> ⚠️ **两条线上的表数别互相减**：本附录的终点是 **37**（⑰ 之后），§16 的终点是 **38**（⑲ 之后）；本附录的索引终点是 **69**，§16 是 **73**。

### 17.1 `drill_record` —— 攻击演练记录（⑰-1，`.sql:1717-1733`） {#db-17-1}

| 列 | 类型 / 约束 | 可空 | 默认 | 语义（列注释逐字） |
|---|---|---|---|---|
| `Id` | `bigint AUTO_INCREMENT` | NO | — | 自增主键 |
| `OperatorId` | `varchar(40)` | NO | `''` | 发起演练者业务号（对齐 `user.customerid` / `user.merchantid`，**不是**登录名） |
| `OperatorRole` | `tinyint` | NO | `0` | 1=用户 / 2=商家 / 3=管理员（认不出 = 0，**不编造**） |
| `Name` | `varchar(64)` | NO | `''` | 场景名（预置 8 场景之一）或「自定义」 |
| `Scenario` | `varchar(500)` | NO | `''` | 场景配置 JSON（8 场景勾选 + 各自权重 + 总并发 + 持续秒数） |
| `TotalRequests` | `int` | NO | `0` | 本轮总请求数 |
| `BlockedRequests` | `int` | NO | `0` | 被防护拦下数 |
| `FailedRequests` | `int` | NO | `0` | 真失败数（5xx 等） |
| `DurationMs` | `bigint` | NO | `0` | 本轮耗时（毫秒） |
| `Result` | `mediumtext` | **YES** | `NULL` | 长文本结果（**后端解析后分字段**给前端显示 —— 不是让前端展示原始长文） |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` | 行落库时刻 |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | 行更新时刻 |

索引（**除主键外 1 个，非唯一**）：`idx_drill_created (created_at)` —— 列表按时间倒序取（`.sql:1732`）。
⚠️ **12 列**（含两个时间列）＝ §16.1 那 11 列之外多一个 `updated_at`；本表**没有** `BatchId`（⑲-2 才补）。
⚠️ **`OperatorId` 是业务号不是登录名**（列注释逐字）；**认不出 ⇒ `''` / `0`**，不编造（闸关着的 dev / a3 就是这一档）。
⚠️ **`Result mediumtext` 刻意是长文本**：它由后端 `split()` 拆成字段后再给前端（解析失败落 `ResultRaw` + `ResultParseError`，**不静默** —— 同 `SensitiveWordController` 那条「`masked=0` 也必须报出来」的口径）。

### 17.2 两个「演示基线列」（⑰-2 / ⑰-3） {#db-17-2}

| # | 表.列 | 类型 | 语义 | `.sql` |
|---|---|---|---|---|
| ⑰-2 | `goods_sku.BaselineStock` | `int DEFAULT NULL` | 演示基线库存：演示前 = `Stock`，演示后一行 `UPDATE` 还原；**NULL = 未处于演示中** | `:1735-1741` |
| ⑰-3 | `seckill_activity.BaselineSoldStock` | `int DEFAULT NULL` | 同 ⑰-2，秒杀侧（演示基线**已售**） | `:1743-1749` |

⚠️ **这两列不是 `Source` 列，语义相反**（`db-migration.md` 注意 21 逐字）：`Source` 的判据是「这一**行**是谁写进来的」，而库存行的**行本身不是攻击创建的**、只是**数值被改** ⇒ 给这两张表加 `Source` 属**语义错位**。「攻击动过它」由**基线列能否还原**表达。
⚠️ **⚠️ 库里 `NULL` = 未处于演示中**（这是设计语义，不是「没填」）：演示三/四步 —— 演示前 `UPDATE goods_sku SET BaselineStock = Stock WHERE BaselineStock IS NULL;` → 演示后 `UPDATE goods_sku SET Stock = BaselineStock;` → 收尾 `UPDATE goods_sku SET BaselineStock = NULL;`。⇒ **种子脚本刻意不预置非 NULL 值**（预置了引擎会把「上一轮的基线」当成这一轮的，`Result.Stock.Baseline` 当场变**假读数**）。
⚠️ **`goods_sku` 因此没有 `Source` 列** —— 块 7 的 `seed-demo.sql` 演示种子那一段用**码前缀**（`SkuCode LIKE 'SEED-%'`）当撤销判据，而不是 `Source='seed'`。

### 17.3 块 6 六表补 `Source`（⑰-4 .. ⑰-9） {#db-17-3}

| # | 表 | `.sql` | 说明 |
|---|---|---|---|
| ⑰-4 | `comment` | `:1751-1757` | 攻击场景 8（敏感词攻击）会写本表 |
| ⑰-5 | `comment_image` | `:1759-1765` | 块 6 六表补齐 |
| ⑰-6 | `comment_like` | `:1767-1773` | 同上 |
| ⑰-7 | `comment_mention` | `:1775-1781` | 同上 |
| ⑰-8 | `report` | `:1783-1789` | 同上 |
| ⑰-9 | `sensitive_word` | `:1791-1797` | 攻击场景 8 会写本表 |

六列**同形**：`ALTER TABLE … ADD COLUMN Source varchar(16) NOT NULL DEFAULT ''`，逐表一个 `information_schema.COLUMNS` 守卫 + `PREPARE`/`EXECUTE`（照 ⑬ 先例，**不写循环**）。
⚠️ **块 5 可视化段已给 11 张表加过 `Source`**（本文件 §13.6），块 6 六表当时**漏加** ⇒ 本段是**补齐**；补齐后全库 **17 张表**有 `Source`（11 + 6；`goods_sku` / `seckill_activity` **不在**其中，见 §17.2）
⚠️ **六列 `COMMENT` 比块 5 那 11 张多一段 `attack=`**（`.sql:1709` 逐字记了这条）：那批加列时**还没有攻击模拟器**，只写了 `seed=` 与空值两支；本块多出 `attack=攻击模拟器写入` 这一支。**这是刻意的不一致**（新语义必须能表达），不是抄漏；`varchar(16)` 宽度、默认值、守卫形态**都不变**。

### 17.4 `order.StockDeducted`（⑱-1，`.sql:1824-1830`） {#db-17-4}

| 列 | 类型 / 约束 | 可空 | 默认 | 语义（列注释逐字） |
|---|---|---|---|---|
| `StockDeducted` | `tinyint(1)` | NO | `0` | 本单库存**真扣成过**（块 7 commit 2d）：1 = 下单时 CAS 命中并动了 `goods_sku.Stock` / 0 = 没动过（库存不足、非矩阵商品无 sku 行、或 2d 之前的存量单）；超时释放**只有 = 1 才回补** |

⚠️ **为什么需要它**（`.sql:1806-1810` 逐字）：块 7 commit 1 起「下单即扣」，但 **0 行不挡单**（库存不足 / 非矩阵商品没有 `goods_sku` 行）⇒ **单落库 ≠ 库存被动过**。超时取消若无条件回补，就会给「当初一行也没扣成」的单**凭空补出一份库存**。
⚠️ **刻意不回填**（用户 2026-10-14 明批）：2d 之前落的单大多数确实**没走过**「下单即扣」，无从回判 ⇒ 一律不回补（偏账方向选「少还一件」，不是「凭空补一件」）。
⚠️ **精度到「单」不到「行」**：混合单（一行真扣成、一行当时库存不足）只要有一行扣成过就**整单**回补。根治要把标记下沉到 `order_item` 粒度（新列 + 新契约）—— **本块不做**。
⚠️ **写入 / 读取点**：写 = `OrderService.markStockDeducted`（落库后一句幂等 UPDATE，`WHERE OrderId=? AND StockDeducted=0`）；读 = `OrderDao.selectStockDeducted`（查无此行回落 0）；**唯一读判据点** = `OrderTimeoutCancelJob.releaseStock`（`== 1` 才回补，读失败 **fail-closed** + WARN）。

### 17.5 段 ⑰ / ⑱ 对基线的净变化 {#db-17-5}

| 基线 | ⑰ 之后 | ⑱ 之后（= 本附录终点） | 判据档位 |
|---|---|---|---|
| 表数 | **36 → 37**（+1：`drill_record`） | **仍 37**（只加列） | 可复核（VM 库 `information_schema.TABLES` 实测 = **37**，2026-10-13） |
| 加列 | **+8**（2 个演示基线列 + 6 个 `Source`） | **+1**（`order.StockDeducted`）⇒ 两段合计 **+9** | 可复核（`.sql:1735-1797` 八个守卫 + `:1824-1830` 一个守卫，逐条可数） |
| 索引 | 非主键 **68 → 69**（+1：`drill_record.idx_drill_created`，**非 UNIQUE**） | **仍 69**（加列不加索引） | 可复核（VM 库 `information_schema`，口径 = `COUNT(DISTINCT 表.索引名)` 且 `INDEX_NAME <> 'PRIMARY'`） |
| 非主键 UNIQUE | **19（不变）** | **19（不变）** | 可复核（同上口径） |
| A3 P3 门禁 | `KNOWN_TABLES` **25 → 26**、期望表数 **36 → 37**（**四处同改**，见 `db-migration.md` 注意 21） | **一处不改**（与 ⑰ 方向相反，见注意 22） | 可复核（`ConcurrencyRunner.java`） |
| 行首 `CREATE TABLE` | **36 → 37** | **仍 37** | ⚠️ **这是 ⑰/⑱ 的中间态** —— §16 已把「块 7 三段合计」记成 **40**（⑲ 又 +1 表），两处**别互相减** |

⚠️ **落库证据（第一手复核）**：⑰ 于 **2026-10-13** 落 VM 库（**33/33 语句 OK**、复跑 **33/33 OK** 无 1060）、⑱ 与 ⑲ 于 **2026-10-14** 由同一次 `ApplyMigration`（marker `库存治理补完`，**21 条语句**）落库（**21/21 OK**、复跑 21/21 OK）—— 逐条见 `db-migration.md` 注意 21 / 22 / 23，原文落在 `_work/b7c8b-verify.txt`。⑱ 落地时的只读复核顺带确认 `order.StockDeducted` = `tinyint(1)` / NOT NULL / DEFAULT 0。
⚠️ **回滚**：`drill_record` 是新表 ⇒ 按注意 6 = 「**保留表 + 停止写入**」（`DROP TABLE` 只在本地库重建时用）；8 个加列按注意 1 = **逻辑废弃**（保留列 + 停用写入，**不得** `DROP COLUMN`）；两个基线列的合规「废用」动作恰是 `UPDATE … SET BaselineStock = NULL`（它本来就是 NULL 语义）。段头 `:1711-1714` / `:1818-1821` 的 `ROLLBACK` 注释逐条写了这些语句并标注「**执行前须人工确认**」。
⚠️ **`drill_record` 刻意不进文件头（`:21` 起）的 `DROP TABLE IF EXISTS` 基线清单**（同 `cart` / `order_item` / `stats_daily` / `observed_event`：它是**业务数据**不是种子）⇒ **整份重导不清空演练记录**；表结构变更必须写独立 Migration。

