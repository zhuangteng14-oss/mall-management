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
