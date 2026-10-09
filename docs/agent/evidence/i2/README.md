# C3 块 1 · I2「系统通知」—— 验收证据

本目录是 I2（系统通知）那一笔的**可取证证据集**：每份文件对应 `slot-registry.md §十五`
里列的一条验收，且都在 **2026-10-09 真跑过**（真 dev server → 真后端 → VM 库 `mall_management_sql`）。

> 2026-10-09 由散落目录 `_a3work/`（两份，一份在桌面根、一份在项目根）迁入本目录。
> 用户口径：**可复跑的探针进 `scripts/probes/`、证据进本目录、纯中间产物删掉、
> 临时产物一律写 `_work/`（已 gitignore）**。

## 清单

| 文件 | 对应验收 | 说明 |
|---|---|---|
| `shots/01..07-*.png` | §五·11（三端） | 管理端发送页 → 填表 → 发送回执 → 历史归组 → 客户端红点 → 收件箱 → 点开已读，7 张 |
| `shots/result.json` | §五·11 | E2E 汇总：`{"ok": true}` + 本次 `batchId` + 回执文案 |
| `scripts/probes/probe-i2-e2e.mjs` | §五·11 | 产生上面 8 份的脚本，13 条断言 `①–⑬`。⚠️ 迁入时改过两处路径（见文件头注释）：与 `_cdp.mjs` 的相对引用、`OUT` 截图目录 |
| `scripts/probes/i2-guard.mjs` | §五·10 | 权限三档（闸开实例 8098 上打 `/notification/*`：自己 200 / 别人 403 / 匿名 401）。⚠️ **它的运行输出没有落盘** —— 只有这个脚本和当时的会话记录，要复核得重跑一遍 |
| `smoke.out` | §五·3–9、10b | 后端 smoke 的逐条请求/响应原文：群发、中文载荷、未知 code 不校验白名单、四条缺参/非法值负例、单发、列表、未读计数、单条已读（含重复读幂等、读别人的行）、全部已读、按批历史、模板 upsert |
| `db/notif_migration.sql` | §五·1 | **实际执行的那份**建表 SQL（md5 记在 `apply.out`） |
| `db/apply.out` | §五·1 | 第一次执行：sudo 认证失败 ⇒ `mysql_rc=1`，库内仍是 13 张表 |
| `db/apply2.out` | §五·1 | 第二次执行：`mysql_rc=0`，表数 **13 → 15**（+`notification`、+`notification_template`） |
| `db/state.txt` | §五·1 | 执行**前**的库快照：MySQL `8.4.11`，13 张表 |
| `db/verify.out` | §五·1 | 执行**后**的结构核验：两张表的 ENGINE/COLLATION、全部列（含注释）、索引 |
| `audit-hook/requests.txt` | §15-8 | 「通知发送挂审计」的实测原文：3 次 curl 的请求/响应 + 判据（正例 2 行、负例 0 行、`BeforeJson` 全 NULL、`TargetId=batchId`） |
| `audit-hook/admin_audit-notification.send.txt` | §15-8 | 上面那次跑完后 `admin_audit` 里 `notification.send` 的行（UTF-8，别在 GBK 控制台直接打） |
| `audit-hook/AuditQ.java` | §15-8 | 产生上面那份的只读 JDBC 探针（`java -cp <mysql-connector-j> AuditQ.java <输出文件>`） |

## 没在这里的（以及为什么）

- **§五·2 编译 / §五·13 不回归**：`./mvnw -q -DskipTests compile` 退出 0、
  `./mvnw -B test` **237 / 0 failures / 0 errors / 19 skipped / BUILD SUCCESS** ——
  这两个是**可复跑**的，证据就是再跑一次，不另存产物。
- **§五·4 落库**：由 `smoke.out` 的 `>>> 5. list` 段间接证明（`Id=22`、`ReadAt:null`、`BatchId` 一致），
  DB 直查那一次的输出没有单独存文件。
- 暂存脚本、webpack dev 包、日志、refs 快照、探针 SQL 等 —— **纯中间产物，已删**。
