# 数据库变更记录 — mall_management_sql

> 依据 `agent.md`「数据库管理规则」第 5 条维护：**每次数据库变更必须在此登记**。
> 权威 Schema：项目根目录 `mall_management_sql.sql`；表结构快照：`db-schema.md`。
> 追加规则（`agent.md` 全局硬性约束 3 = 规则 v2）：**只允许追加** —— 新增表 / 新增字段 / 新增索引（含 `UNIQUE`）**无条件允许**；新增 **FK** / 改列类型**有条件允许**（四项前置：正向 SQL · 回滚 SQL · 迁移脚本 · VM 先验证 + A3 全量绿）。禁止删表、删列、删字段、删已有索引与约束。

## 变更登记

| # | 日期 | 功能名 | 变更类型 | 涉及表 | 正向 SQL | 回滚 SQL | 执行人 | 状态 |
|---|---|---|---|---|---|---|---|---|
| 1 | 2026-09-29 | B3 订单状态流转时间线 | 新增表 | `order_state_transition` | `mall_management_sql.sql:221-235` | `DROP TABLE IF EXISTS order_state_transition;`（见注意 5） | B3 `e93a499` | ✅ 已落盘（CI P3 表数 = 12 确证） |
| 2 | 2026-09-30 | S1a 订单号唯一索引 | 新增索引 | `order` | `mall_management_sql.sql` 末尾 Migration 块（`CREATE UNIQUE INDEX uk_order_orderId`） | `DROP INDEX uk_order_orderId ON \`order\`;`（规则 v2：回滚 = 逻辑废弃，执行前须人工确认） | S1a-1 | ⏳ 待 VM 验证 |
| 3 | 2026-10-04 | goodstype 类型名唯一索引 | 新增索引（UNIQUE） | `goodstype` | `mall_management_sql.sql:246-262`（Migration 块，`CREATE UNIQUE INDEX uk_goodstype_GoodType`） | `DROP INDEX uk_goodstype_GoodType ON \`goodstype\`;`（规则 v2：回滚 = 逻辑废弃，执行前须人工确认） | #11 `fix/goodstype-unique` | ⏳ 待 A3 复跑验证（判据见注意 7；⚠️ 前置与 #2 相反） |
| 4 | 2026-10-08 | C3 批 A 管理端审计日志 | 新增表 | `admin_audit` | `mall_management_sql.sql:264-292`（Migration 块，`CREATE TABLE IF NOT EXISTS`，11 列 + 主键 + 3 个 KEY） | `DROP TABLE IF EXISTS admin_audit;`（见注意 6） | C3 批 A（`slot-registry.md §十四`） | ✅ 已落 **VM 库**（**未提交**）；⚠️ **补登**，见注意 8 |
| 5 | 2026-10-09 | C3 块1-I2 系统通知（含模板） | 新增表 | `notification` · `notification_template` | `mall_management_sql.sql:294-327`（通知，13 列 + 主键 + 3 个 KEY）· `:329-345`（模板，7 列 + 主键 + 1 个 UNIQUE） | `DROP TABLE IF EXISTS notification;` · `DROP TABLE IF EXISTS notification_template;`（见注意 6） | C3 块1-I2 | ✅ 已落 **VM 库**（**未提交**；两表均 0 行，2026-10-09 实测） |
| 6 | 2026-10-09 | C3 块2 用户块（用户治理 + 商家入驻审核） | 新增表 + 新增字段 | `merchant_application` · `user` · `goods` | `mall_management_sql.sql:348-409` —— ① 新表 `merchant_application`（`:369-390`，16 列 + 主键 + 3 个 KEY）② `user` 加 8 列（`:396-404`，封禁 3 + 降级 5）③ `goods` 加 2 列（`:407-409`，`ShelfState` / `DelistedAt`） | `DROP TABLE IF EXISTS merchant_application;` · `ALTER TABLE \`user\` DROP COLUMN \`banned\`,\`banReason\`,\`bannedAt\`,\`DowngradeState\`,\`ScheduledDowngradeAt\`,\`DowngradeReason\`,\`FrozenAt\`,\`FreezeUntil\`;` · `ALTER TABLE \`goods\` DROP COLUMN \`ShelfState\`,\`DelistedAt\`;`（见注意 6 + 注意 9；**规则 v2：回滚 = 逻辑废弃，执行前须人工确认**） | C3 块2（`slot-registry.md §十六`） | ✅ 已落 **VM 库**（**未提交**；`merchant_application` 0 行，2026-10-09 实测） |
| 7 | 2026-10-10 | C3 块3 商品块（商品与类目治理） | 新增表 + 新增字段 + 索引换代（UNIQUE 换键） | `goods_template` · `goods_audit` · `goodstype` · `goods` | `mall_management_sql.sql:411-515` —— ① `goodstype` 加 3 列（`:449-451`，`ParentId` / `Sort` / `State`）+ 普通索引（`:452`）+ **唯一键换代**（`:454-455`，`DROP uk_goodstype_GoodType` → `CREATE UNIQUE uk_goodstype_parent_name (ParentId, GoodType)`）② `goods` 加 5 列（`:461-467`，`TypeId` / `DetailTemplateId` / `SpecTemplateId` / `AfterSaleTemplateId` / `DeletedAt`）+ 按名字回填 `TypeId`（`:471`）③ 新表 `goods_template`（`:474-484`，7 列 + 主键 + 1 个 KEY）④ 新表 `goods_audit`（`:492-515`，18 列 + 主键 + 3 个 KEY） | 见 `mall_management_sql.sql:421-428` 块内「回滚」注释（`DROP TABLE goods_audit` · `DROP TABLE goods_template` · `ALTER TABLE goods DROP COLUMN TypeId,DetailTemplateId,SpecTemplateId,AfterSaleTemplateId,DeletedAt` · `DROP INDEX uk_goodstype_parent_name` → 重建 `uk_goodstype_GoodType` · `DROP INDEX idx_goodstype_parent_sort` · `ALTER TABLE goodstype DROP COLUMN ParentId,Sort,State`）（见注意 6 + 注意 10；**规则 v2：回滚 = 逻辑废弃，执行前须人工确认**） | C3 块3（`slot-registry.md §十九`） | ✅ 已落 **本机库** + **VM 库**（均**未提交**，本机备份 `_work/b3-dbbackup/`）；⚠️ VM 走「补块 3 + 补本块 #8」而**非**整份重导 ⇒ **业务数据零损失**（VM `goods` 仍 5 行、`order` 6 行，2026-10-10 实测）—— 原判「VM `192.168.229.129` 本笔不可达」**已作废**，见注意 11 |
| 8 | 2026-10-10 | C3 块3 **就地改造**〈模板表通用化 + 商品侧改「快照」〉 | 新增表 + **删表**（用户破例批准，见注意 11）+ 新增字段 + **删字段**（同前） | `template` · `goods` · `goods_audit` · ~~`goods_template`~~ · ~~`notification_template`~~ | `mall_management_sql.sql:517-680` —— ① 新表 `template`（`:556-569`，9 列 + 主键 + `UNIQUE uk_template_type_name (Type,Name)` + `KEY idx_template_type_enabled_sort`）+ `Type` 加宽到 96 的幂等守卫（`:579-584`）② `goods` **加** 3 列（`:588-607`，`Detail` / `Spec` / `AfterSale`，`mediumtext`）+ **删** 3 列（`:611-630`，`DetailTemplateId` / `SpecTemplateId` / `AfterSaleTemplateId`）③ `goods_audit` 加 3 列（`:636-655`，同名列）④ 搬 `notification_template` 行 → `template`（`:657-673`，`Type = CONCAT('notification.', TemplateKey)`）⑤ **物理删**两张老表（`:679-680`） | 见 `mall_management_sql.sql:527-531` 块内「回滚」注释（`DROP TABLE template` · `ALTER TABLE goods DROP COLUMN Detail,Spec,AfterSale` · `ALTER TABLE goods_audit DROP COLUMN Detail,Spec,AfterSale` · **3 个 `*TemplateId` 不回填 —— 逻辑废弃，不恢复**）。⚠️ 被删的两张老表**不登记重建**：其行已在 ④ 步搬进 `template`，重建 = 把已合并的两套表再拆回去、与本次口径相反（原始 `DROP` 语句已随块落在 `.sql:679-680`，**执行前须人工确认**）—— **规则 v2：回滚 = 逻辑废弃，执行前须人工确认** | C3 块3 就地改造（`slot-registry.md §十九` 19-9） | ✅ 已落 **本机库**（13 表）+ **VM 库**（17 表）—— 均**未提交**；两库 `template.Type` 均 `varchar(96)`、`template` 0 行，2026-10-10 实测（见注意 11） |
| 9 | 2026-10-10 | C3 块4 订单块（购物车 `cart` + 管理端订单治理） | 新增表 + 新增字段 | `cart` · `order` | `mall_management_sql.sql:681-738` —— ① 新表 `cart`（`:693-720`，8 列 + 主键 + `uk_cart_customer_product` UNIQUE + `idx_cart_customer` 普通 KEY）· 另 `:722-728` 同笔 `MODIFY COLUMN PriceSnapshot` **只改注释不改列**（见注意 12 第 3 条）② `order` 加 `Quantity`（`:730-738`，`int NOT NULL DEFAULT 1`，存量 6 行回填 1） | `DROP TABLE IF EXISTS cart;` · `ALTER TABLE \`order\` DROP COLUMN \`Quantity\`;`（见注意 1 / 注意 6；**规则 v2：回滚 = 逻辑废弃，执行前须人工确认**） | C3 块4（明细见 `slot-registry.md §二十`） | ✅ 已落 **本机库（14 表）+ VM 库（18 表）**（**未提交**）；证据见注意 12 |

## 登记格式

正向 SQL 按 `agent.md` 规定的注释块格式追加到 `mall_management_sql.sql` 末尾：

```sql
-- ============================================================
-- Migration: <功能短名>  Date: YYYY-MM-DD
-- ============================================================
CREATE TABLE IF NOT EXISTS xxx (...);
ALTER TABLE xxx ADD COLUMN new_col VARCHAR(64) DEFAULT NULL;
CREATE INDEX idx_xxx ON xxx(col);
-- 其余模板（CREATE UNIQUE INDEX / ADD CONSTRAINT FK / 改列类型的影子列）见 `agent.md` 数据库管理规则 3（规则 v2）；本文件不重复 DDL（见注意 5）。
```

## 注意事项

1. **回滚 SQL 的固有矛盾 —— 2026-09-30 已由规则 v2 裁决**：`agent.md` 曾同时要求「登记回滚 SQL」和「禁止 `DROP COLUMN` / `MODIFY COLUMN`」，对「新增字段」类变更两者不能同时满足。规则 v2（数据库管理规则 4）已明写：**回滚 SQL = 逻辑废弃，不是物理删除** —— 「保留列 / 表 + 置 NULL / 停用」，**不得**写 `DROP COLUMN` / `DROP TABLE`。原议题见 `business-decisions.md` BD-02、`tech-debt.md` TD-03（两者随之关闭）。
2. 新增字段必须带默认值或允许 NULL、必须带 `COMMENT`、必须加在表末尾（`agent.md` 数据库管理规则 7）。
3. 新增表必须 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`、有主键、有 `created_at` / `updated_at`（`agent.md` 数据库管理规则 8）。
4. 本文件由文档化工作创建，用于补齐 `agent.md` 第 85 行要求的强制产物——该产物此前**不存在**，会导致第一次数据库变更无处登记。
5. **本行的正向 SQL 不在此重复**：权威源即 `mall_management_sql.sql:221-235` 的 Migration 块（`CREATE TABLE IF NOT EXISTS`，8 列 + 主键 + `idx_order_state_transition_order`），逐列快照见 `db-schema.md §6.12`。三处各留一份 DDL 必有漂移，故本文件只登记「位置 + 回滚 + 执行证据」。
6. **「新增表」的回滚 —— 2026-09-30 已由规则 v2 裁决**（与注意 1 同源，是同一矛盾的第二个面）：`agent.md` 数据库管理规则 4 禁止 `DROP TABLE`，而新增表唯一的**物理**回滚手段就是 `DROP TABLE`。规则 v2 的裁决是：新增表**不物理回滚**，改为「**保留表 + 停止写入**」；因该做法的代价是 schema 漂移**不可见**，故本文件**如实登记**原 `DROP TABLE` 语句并标注「**执行前必须人工确认**」。现状证据（未变）：`defect-survey.md §三 病 G` 记 —— 全库重建脚本的 11 条 `DROP TABLE` **不含** `order_state_transition`，故本机重复导入会残留旧表结构（CI 每次新容器，故只在开发机上）。
7. **⚠️ 本条（#3 goodstype）的「重复行检测」前置与 #2（order）相反 —— 2026-10-04 记**：`agent.md` 数据库管理规则 3 要求 `CREATE UNIQUE INDEX` 前「**每个库重复行检测为 0** 才能建」。**#2 满足**（该块自述「本机 7/7、VM 6/6，2026-09-30 实测」均为 0）；**#3 不满足、且不可能满足** —— `goodstype` 的重复行恰恰是 A3 的 B-2 场景**自己在造**的：CI `ci49`（2026-10-03）实测 `goodstypeDupRounds = 4/5`（逐轮 `goodstypeDupRows` 4 / 1 / 4 / 3 / 3）。故 #3 的落盘路径**只能走「全量重导」**：`mall_management_sql.sql` 头部含 `DROP TABLE IF EXISTS \`goodstype\``，建表时表为空 ⇒ 索引必建成；**对已有重复行的既有库必须先人工去重再建，不可直接执行该迁移块**。这一差异已逐字写进 `mall_management_sql.sql:246-262` 的 Migration 块注释，两处口径必须一致。
8. **⚠️ #4（`admin_audit`）是「补登」不是本次变更 —— 2026-10-09 记**：批 A（C3「权限与审计」，落码 2026-10-08）在 **VM 库**建了 `admin_audit`（`mall_management_sql.sql:264-292`），但**当时没有在本文件登记**（违反 `agent.md` 数据库管理规则 5 —— 「每次数据库变更必须在此登记」）。本条在 I2 登记时**顺手补上** ⇒ 行号顺延，I2（`notification` / `notification_template`）因之记作 **#5**（I2 方案里原写「#4」，那是在假定批 A 已登记的前提下编的号）。
   **同一缺口还有第二处**：`db-schema.md` 也漏了 `admin_audit`（其表数长期停在 **12**）。本次一并补齐到 **15**。
   **证据（2026-10-09 实测）**：`SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='mall_management_sql'` ⇒ 共 **15 张表**，其中 `admin_audit` / `notification` / `notification_template` 三张正是上述 12 之外的部分。⚠️ `TABLE_ROWS` 对 InnoDB 是**估算值**（同一次查询里 `login` 报 7 而 `progress.md` 的种子基线是 5），故本文件**只用它判「表是否存在」**，不用它当行数。
   ⚠️ **两处口径必须一致**：本条的编号（#4 = 批 A、#5 = I2）与 `db-schema.md` 的表数（15）、`slot-registry.md §十四/§十五` 的登记同源；若日后回填批 A 的独立登记，须三处同改。
9. **⚠️ 块 2（#6）—— 表数 15 → 16，且**首次**出现「改**基线表**」的变更 —— 2026-10-09 记**：C3 块 2〈用户块〉在 VM 库 ① 新建 `merchant_application`（`mall_management_sql.sql:369-390`）② 给**导入基线表** `user` 加 8 列（`:396-404`）③ 给 `goods` 加 2 列（`:407-409`）⇒ 表数 **15 → 16**（`db-schema.md` 随之更新：索引 12 → 15 个、分布 7 → 8 张表；**非主键 UNIQUE 仍 3 个**，新表无 UNIQUE）。
   ⚠️ **注意 8 那句「共 15 张表」测于块 2 之前**，本笔之后同口径应为 **16**（`SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='mall_management_sql'`）。
   ⚠️ **「加列」与「新增表」性质不同**：`user` / `goods` **属于导入基线、会被全量重导 `DROP`+`CREATE`**，但加列语句写在 Migration 块（各表的 `CREATE TABLE` 之后）⇒ 重导后仍由该块补上，**且不会出现「基线已含新列 + 末尾又 ADD」的重复列**（该处置逐字见 `.sql:365-367`）。代价：加列**不幂等** —— MySQL **不支持** `ADD COLUMN IF NOT EXISTS`（那是 MariaDB 扩展），VM 重复执行会报 **Duplicate column（预期，非故障）**（`.sql:359-364`）。
   **已裁决不剥**：`user` 的 8 个新列会随全仓唯一一条 `SELECT *`（`UserDao.selectUserByUsername`）出现在 `/user/me` 与 `getUserInfo` 返回里 —— 用户该知道自己被封/被降级，`DowngradeReason` 本就是给商家看的（`.sql:392-395`）。
10. **⚠️ 块 3（#7）—— 表数 16 → 18；**首次**出现「**索引换代**（UNIQUE 换键，即**替换**一个既有唯一索引）—— 2026-10-10 记**：C3 块 3〈商品块〉① `goodstype` 加 3 列 + **换唯一键**（`uk_goodstype_GoodType` → `uk_goodstype_parent_name (ParentId, GoodType)`，`.sql:454-455`）② `goods` 加 5 列（`.sql:461-467`）+ 按名字回填 `TypeId`（`.sql:471`）③ 新表 `goods_template`（`.sql:474-484`，7 列 + 主键 + 1 KEY）④ 新表 `goods_audit`（`.sql:492-515`，18 列 + 主键 + 3 KEY）⇒ 表数 **16 → 18**。
   ⚠️ **换代 ≠ 净增索引**：本笔 `DROP INDEX uk_goodstype_GoodType` + `CREATE UNIQUE INDEX uk_goodstype_parent_name` 是**一换一**（非主键 UNIQUE 仍 3 个），另有普通索引 `idx_goodstype_parent_sort` **净增 1** ⇒ 全库索引数 **15 → 16**。⚠️ **注意 7（#3 `uk_goodstype_GoodType`）自本笔起已失效** —— 那个索引**已被删掉**，注意 7 描述的前置条件与构建路径**随之作废**（`goodstype` 唯一性判据改看 `(ParentId, GoodType)`）。
   ⚠️ **换代的前置与 #3 相反（本次满足）**：新键 `(ParentId, GoodType)` 依赖「既有类目 ParentId 全为 0 且名字互异」—— 基线种子 5 条（Id 1-5）全为一级、名互异 ⇒ **全量重导路径上当场成立**，无需先去重（逐字见 `.sql:437-440`）。**对已有重复行的既有库**仍须先人工去重再执行该块。
   ⚠️ **A3 B-2 场景是否仍复现 —— 按代码读、待复跑核实**：`ConcurrencyRunner.java:2310-2312` 的 B-2 只传 `GoodType` + `GoodTypeNew`（**不传 `ParentId`** ⇒ 默认 0），故新键 `(0, gt)` 在该场景下与旧键**等价** ⇒ **预期 B-2 仍逐轮复现重复行**（判据 `goodstypeDupRounds`）。**这是读代码的推断，非实测** —— 须由下一次 A3 复跑确证（判据口径见注意 7 引用处的 `goodstypeDupRows` 分布）。
   ⚠️ **`goods` 的 3 个模板列今天「零写入」**：`DetailTemplateId` / `SpecTemplateId` / `AfterSaleTemplateId` 全仓 `grep -rn`（server + web + scripts）= **0 命中**，`GoodsDao.insertGoods` 的 INSERT 只有 8 列不含它们 ⇒ **三列恒 NULL**。已登记为落码待裁项，见 `_work/c3-block3-landing-plan.md §十一`（**不是**本文件要修的事，此处只记 schema 事实）。⇒ **该待裁项已由 #8 结清**：三列**已删**，见注意 11。
11. **⚠️ #8（块 3 就地改造）—— 本文件**首次**登记「**删表**」「**删列**」；用户**明确破例批准** —— 2026-10-10 记**：规则 v2（`agent.md` 全局硬性约束 3 / 数据库管理规则 4）**禁止删表、删列**，本文件其余 7 条登记**无一例外**。本笔两处都破了例，且**是用户 2026-10-10 当面批准的口径**（不是 agent 自行放宽）：
   - **删表 2 张**：`goods_template`（#7 刚建、0 行）、`notification_template`（#5 建、0 行、**从未接线**）。
     理由：两套模板表语义重复，合并成一张通用 `template`（`Type` 区分用途）。**保留旧表 = 留一份必然漂移的孤儿 schema**。
   - **删列 3 个**：`goods.DetailTemplateId` / `SpecTemplateId` / `AfterSaleTemplateId`（**注意 10 末句已记：全仓零写入、恒 NULL**）。
     理由：商品侧语义由「引用模板 Id」（改模板 ⇒ 商品**联动**变化）改为「**快照**」（选模板 → 填入 → **可改** → 商品存**最终文本** ⇒ 改模板**不影响**已上架商品）。三列在新语义下**永不写入**。
   ⇒ 若日后要「回滚到引用语义」，须**重新决策**并新开一笔（不是本条的幂等重跑）。

   ⚠️ **零漂移的落地手法（与「物理删」等价但不动基线）**：两张老表的 `CREATE TABLE` 行（`notification_template` 在 `.sql:329-345`、`goods_template` 在 `.sql:474-484`）**留在原地一个字符都没改**；净行为由本块尾部的 `DROP TABLE IF EXISTS`（`.sql:679-680`）兑现 ⇒ **净判据 = 导入后这两张表不存在**。若改成字面删掉那两个建表块，全文 ≥330 行集体漂移 ⇒ 与全仓 `文件:行号` 互引口径冲突（理由逐字在 `.sql:532-535`）。
   **行搬迁**（`.sql:657-673`）：`notification_template` → `template`，`Type = CONCAT('notification.', TemplateKey)`、`Sort = 0`，撞 `uk_template_type_name` 时 `ON DUPLICATE KEY UPDATE Id = template.Id` ⇒ **不覆盖已编辑过的模板**（不用 `INSERT IGNORE`：会吞无关错误）。搬迁**必须早于 DROP**（同块 ④ 在 ⑤ 前）。⚠️ **`notification.*` 类仍不接线**（块 1 立下的口径：无占位符渲染、无「send 时按 key 取模板」）—— 本笔只让它**有表、有 CRUD**。
   **计数**：表数 **18 → 17**（= 11 基线 + 6 迁移建表：`order_state_transition` / `admin_audit` / `notification` / `merchant_application` / `template` / `goods_audit`；**`notification_template` 退出**）；索引 **16 → 16**（−`goods_template` 的 1 个普通 KEY、−`notification_template` 的 1 个 UNIQUE，+`template` 的 1 UNIQUE + 1 普通 KEY ⇒ **净 0**）；**非主键 UNIQUE 仍 3 个**。
   ⚠️ **实测两库（2026-10-10）**：**本机库 13 表 / VM 库 17 表**。⚠️ 两库**不是同一张表集**：本机库（`mall_management_sql`）从未落过 #1 / #4 / #5 / #6（无 `order_state_transition` / `admin_audit` / `notification` / `merchant_application`）⇒ **13 = 11 基线 + `goods_audit`(#7) + `template`(#8)**；VM 库 17 = 全量迁移后状态。判「#8 成功」的判据**不靠总数**，而是：`SHOW TABLES LIKE 'template'` 有、`LIKE '%_template'` **只剩它一张**、`goods` / `goods_audit` 各有 `Detail`/`Spec`/`AfterSale`、`goods` 的 `LIKE '%TemplateId%'` **0 行**、`template.Type` 的 `CHARACTER_MAXIMUM_LENGTH = 96`、两库 `template` 均 **0 行**。
   ⚠️ **VM 落地方式与 #7 的假设相反**：本笔**未**走「整份重导」（那会 `DROP` 掉 VM 上的业务数据），改为**在该库补块 3 再补本块 #8** ⇒ VM 业务数据**零损失**（`goods` 仍 5 行、`order` 6 行）。⇒ 第 17 行（#7 状态列）原写的「⏳ VM 库待落 —— VM `192.168.229.129` 本笔不可达」**已作废并已就地改正**。⚠️ **库本地前置（脚本未改）**：VM 缺 2026-10-04 那条 `uk_goodstype_GoodType`（#3），而块 3 的 `DROP INDEX uk_goodstype_GoodType` 在缺索引时会 **1091** ⇒ 已先在 VM 建该索引再跑块 3。
   ⚠️ **本块对 `goods_audit` 的前置**：③ 要求该表**已存在**（即块 3 已跑）。整份重导路径上块 3 在本块之前 ⇒ 成立；在**没有块 3 的既有库**上单跑本块，**先跑块 3**（逐字在 `.sql:539-540`）。
   ⚠️ **两条只有 scratch 库才测得出的缺陷（`_work/c3-block3-landing-plan.md §1.6`）**——本机库 `notification_template` 不存在 ⇒ ④ 步恒走 `DO 0`，把两条都遮住了：
   ① `ON DUPLICATE KEY UPDATE Id = Id` 的 RHS **未限定** ⇒ 源表也有 `Id` ⇒ **1052 ambiguous**，整句失败 ⇒ 改成 `template.\`Id\``；
   ② `template.Type varchar(32)` 装不下搬迁值 —— 真实最长 `notification.` + `merchant.downgrade.scheduled`(28) = **41 字符** ⇒ **1406 Data too long** ⇒ 加宽到 **`varchar(96)`**，并补一条幂等加宽守卫（`.sql:571-584`）让已按 32 建过表的库**就地补宽**。
   演练判据：3 行搬迁到位；撞键行 `notification.user.ban` 保持 `OLD-TITLE/OLD-BODY`（ODKU 不覆盖）；`type_maxlen = 41` 落得下；连跑 2 次 `EXIT = 0`。
   ⚠️ **同源的引用面**：`A3` 的 P3 表数门禁 `ConcurrencyRunner.java` 的 IN 清单由 `'goods_template'` 改 `'template'`、去掉 `'notification_template'`，`KNOWN_TABLES` **7 → 6**、总表数 **18 → 17**（逐字见 `_work/c3-block3-landing-plan.md §2.10`）。

12. **⚠️ 块 4（#9）—— 表数 17 → 18；本文件**首次**登记「**不在文件头 `DROP` 清单里的新表**」—— 2026-10-10 记**：C3 块4〈订单块〉在 `mall_management_sql.sql:681-738` 追加 Migration 块 6：

- **新增表 1 张 `cart`**（`:693-720`，8 列 + 主键 + `uk_cart_customer_product (CustomerId, ProductCode)` UNIQUE + `idx_cart_customer` 普通 KEY）—— 真购物车载体（A1 甲，用户 2026-10-10 裁决）。列宽一律向**源列**对齐（`CustomerId` / `MerchantId` ← `order` 的 `varchar(40)`、`ProductCode` ← `goods` 的 `char(40)`、`PriceSnapshot` ← `goods.Price` 的 `double`），**刻意不复制** `order.Price` 的 `float`（`defect-survey.md` 记过的不一致）。
- **新增字段 1 个 `order.Quantity`**（`:730-738`，`int NOT NULL DEFAULT 1`）—— 规则 7：加在**表末** + 默认值 + `COMMENT`。`DEFAULT 1` 让存量 6 行（`.sql:159`）落到「1 件」，语义正确且**不改行数** ⇒ A3 的 P4 种子门禁（`order = 6`）不受影响（**2026-10-10 实测 VM 库 `order` 仍 6 行**）。
- **⚠️ 同笔做了一次「只改注释」的 `MODIFY COLUMN`**（`:722-728`）：初稿把 `PriceSnapshot` 注释成「仅陈列／对账，非成交价」，与 A1 的选型理由（存快照**就是为了避免改价争议** ⇒ 结算以快照为准）**相反**。⚠️ **必须单独一条语句**：`CREATE TABLE IF NOT EXISTS` 对**已存在**的表是**空操作** ⇒ 已按旧注建过表的库（本机库 / VM 库，都是本笔几分钟前建的）不会自己更正。幂等性：重写成**同一份**列定义 ⇒ 重跑是空操作（列类型 / 可空性 / 默认值一个字符没变，**只动 `COMMENT`**）。
- **⚠️ `cart` 刻意不进文件头（`:21` 起）的 `DROP TABLE IF EXISTS` 基线清单** —— 那份清单管的是**种子数据**，而 `cart` 是**用户数据**（同 `order` / `user`）⇒ **整份重导不会清空购物车**；列数变化由 `CREATE TABLE IF NOT EXISTS` 兜住。⚠️ 代价：`cart` 的表结构变更**不能**靠「重导即生效」这条捷径，必须写独立 Migration（本块即是）。
- **计数**：表数 **17 → 18**（`db-schema.md` 随之更新）；索引 **16 → 18**（+`cart` 的 1 UNIQUE + 1 普通 KEY）；**非主键 UNIQUE 3 → 4**。（后两项由 `.sql:708-720` 的 DDL 直接可数，非实测查询。）
- **⚠️ P3 门禁同笔 +1**：`ConcurrencyRunner.java` 的 IN 清单加 `'cart'`、`KNOWN_TABLES` **6 → 7**、期望表数 **17 → 18** —— 少改一处 A3 的 **P3 必红**，而 `concurrency` job 是 `continue-on-error` ⇒ **只能读 artifact 的 `gates.count`，不能读 job 颜色**（同块 3 的教训）。
- **⚠️ 实测证据（2026-10-10，两库各自查 `information_schema`）**：**本机库 `mall_management_sql` = 14 表**（13 → 14）、**VM 库 = 18 表**（17 → 18）；两库都能 `SHOW TABLES LIKE 'cart'` 取到该表（各返回 1 行元数据）、`order` 有 `Quantity int`；**本机库 `order` 12 行 / `cart` 0 行**、**VM 库 `order` 仍 **6 行**（P4 种子未动）/ `cart` 0 行**（两库 `cart` **均为 0 行**，2026-10-10 由本机 `mysql` 客户端各查一次 `SELECT COUNT(*)` 实测）。⚠️ **本机库 14 ≠ VM 库 18 是预期的**（本机库从未落过 #1 / #4 / #5 / #6，理由同注意 11 末段）—— 判「#9 成功」不靠总数，而靠上面那几条逐项判据。
- **⚠️ 回滚**：`cart` 是**新表** ⇒ 按注意 6，规则 v2 的回滚是「保留表 + 停止写入」，**不物理 `DROP`**；`order.Quantity` 按注意 1，回滚 = 逻辑废弃（保留列 + 停止写入），**不得**写 `DROP COLUMN`。
