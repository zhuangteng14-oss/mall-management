# 数据库变更记录 — mall_management_sql

> 依据 `agent.md`「数据库管理规则」第 5 条维护：**每次数据库变更必须在此登记**。
> 权威 Schema：项目根目录 `mall_management_sql.sql`；表结构快照：`db-schema.md`。
> 追加规则（`agent.md` 全局硬性约束 3）：**只允许新增表 / 新增字段 / 新增索引**；禁止删列、改类型、删表。

## 变更登记

| # | 日期 | 功能名 | 变更类型 | 涉及表 | 正向 SQL | 回滚 SQL | 执行人 | 状态 |
|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | **暂无变更** |

## 登记格式

正向 SQL 按 `agent.md` 规定的注释块格式追加到 `mall_management_sql.sql` 末尾：

```sql
-- ============================================================
-- Migration: <功能短名>  Date: YYYY-MM-DD
-- ============================================================
CREATE TABLE IF NOT EXISTS xxx (...);
ALTER TABLE xxx ADD COLUMN new_col VARCHAR(64) DEFAULT NULL;
CREATE INDEX idx_xxx ON xxx(col);
```

## 注意事项

1. **回滚 SQL 的固有矛盾**：`agent.md` 同时要求「登记回滚 SQL」和「禁止 `DROP COLUMN` / `MODIFY COLUMN`」。对「新增字段」类变更，回滚必须 `DROP COLUMN`，两者不能同时满足。**此冲突待规则制定方澄清**，当前建议的回滚写法为「保留列、置 NULL 或停用」，而非物理删除。
2. 新增字段必须带默认值或允许 NULL、必须带 `COMMENT`、必须加在表末尾（`agent.md` 数据库管理规则 7）。
3. 新增表必须 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`、有主键、有 `created_at` / `updated_at`（`agent.md` 数据库管理规则 8）。
4. 本文件由文档化工作创建，用于补齐 `agent.md` 第 85 行要求的强制产物——该产物此前**不存在**，会导致第一次数据库变更无处登记。
