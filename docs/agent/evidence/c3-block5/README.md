# C3 块 5〈营销块〉证据

## commit 1（券）—— 手验前置：把 commit 0 的迁移落到 VM 库

**为什么需要这一步**：commit 0（`a1b845d`）的 7 表 + 4 加列是**人工落库**的迁移
（`mall_management_sql.sql` 末尾的 Migration 块），与代码是两笔 —— 代码绿不代表库里有表。
券端点一启动就打这些表，本仓切片全是 `@WebMvcTest`（不碰真库）⇒ 这条路径**没有任何自动化覆盖**，
不落库就是**运行时**才炸。

**授权**：用户 2026-10-11 就 A1–A5 全批并指定「迁移落库 = 执行已批事项」。

**执行对象**：dev / a3 用的那台库（`192.168.229.129:3306/mall_management_sql`）。
**执行范围**：只取 `-- Migration: 块 5` 标记起到文件末尾（第 1222 行起），
该标记之前的历次迁移**一条都不碰**。

| 文件 | 是什么 |
|---|---|
| `b5-c1-dev-db-probe.txt` | **落库前**只读探针：7 表 / 4 列**全缺**，全库 22 表（= 11 基线 + 11 既有追加），退出码 2 |
| `b5-migration-apply-run1.txt` | 执行第一遍：23 条语句，**23 成功 / 0 失败** |
| `b5-c1-dev-db-probe-after1.txt` | **落库后**探针：7 表 / 4 列全部「有」，全库 **29** 表（= 11 + 18，P3 期望），退出码 0 |
| `b5-migration-apply-run2.txt` | 执行第二遍（幂等）：**23 成功 / 0 失败** —— 建表走 `IF NOT EXISTS` 空操作；4 个加列的 `information_schema` 守护取到 `'DO 0'` 分支 |
| `b5-c1-dev-db-probe-after2.txt` | 第二遍之后的探针：与 `after1` **逐字节相同**（`diff` 无输出） |

**幂等的判据**（不只是「没报错」）：若守护没生效，第二遍的 `ALTER TABLE ... ADD COLUMN` 会以
`Duplicate column name` 失败 ⇒ run2 的 0 失败即证明它走的是 `'DO 0'`。

**未受影响**：三次读数里种子计数恒为 `order=184 goods=45 login=47` ⇒ 迁移**只加表 / 只加列**，
未改任何既有数据（与迁移注释里「规则 v2：向后兼容」一致）。

**工具**：
- 只读探针 `scripts/probes/ProbeB5Migration.java`（**只查 information_schema**，留在仓里可反复跑；连接串与口令走环境变量，本文件不含口令字面量）
- 执行器**不入仓**（它会改库），落 `_work/b5-apply-migration/ApplyB5Migration.java`（`.gitignore` 覆盖）。
  它支持 `--dry-run` 只打印切句结果、不连库 —— 本轮先 dry-run 核对了「23 条 = 7 建表 + 4 守护 ×4」才执行。
