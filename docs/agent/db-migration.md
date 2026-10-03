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
