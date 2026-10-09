# 证据索引 —— C3 块 1 的 CI 首跑红（P3 表数门禁）· 2026-10-09 晚

**登记**：`docs/agent/slot-registry.md` **§十七**（同笔）；进度见 `docs/agent/progress.md` 同日晚那条。

## 本目录内容

| 文件 | 是什么 |
|---|---|
| `a3-run-20261009-120224.txt` | **红**那次：A3 并发测试自己那份报告（`scripts/concurrency/reports/` 的产物）。原始来源 = CI 运行的 artifact（`gh run download`），此处**只做搬运、不改一字** |
| `a3-run-20261009-121313.txt` | **绿**那次（修好 P3 之后）的同款报告 |
| `a3-report-ci132-20261009-121521.json` | 绿那次的**机器可读产物**（同上目录的 `.json`）—— 门禁数按它的 `gates` 读：`{"count": 30, "failed": 0}` |

⚠️ **入库时由 `.log` 改名 `.txt`** —— `.gitignore:11` 有 `**/*.log`，`.log` 会被**静默**挡在门外
（第一次提交就踩了：README 进来了、证据没进来）。**内容一字未改**；证据目录用 `.txt` / `.out`
也是本仓既有惯例（对照 `evidence/i2/db/apply.out` / `state.txt`）。

## 红的那次证明了什么（判据就在文件里）

```
--- 前置校验 ---
  P0  PASS 13 列逐列比对通过；余量最小 goodstype.GoodType 16/20（余 4）
  P2  PASS 实际主机=127.0.0.1 期望主机=127.0.0.1
  P1  PASS DATABASE()=mall_management_sql（TD-13 要求 mall_management_sql ✓）；VERSION()=8.4.11（S0-5 要求 8.4.x ✓）
  P3  FAIL 表数=15（期望 11 + 1；order_state_transition 在）
  P4  PASS order=6 goods=5 login=5 user=5 friend=4
  P5  SKIP JDBC 侧前置未过，未做 HTTP 侧校验
  P6  SKIP 同上
  P7  SKIP 同上

前置校验失败，未产生任何业务流量。退出码 2。
```

⇒ 三条结论，全部**以这份文件为据**，不靠转述：

1. **红点唯一且明确**：P3。P0/P1/P2/P4 都 PASS ⇒ **不是库坏了、不是连不上、不是种子被污染**。
   「表数=15」是**实测值**（同一份文件里 P1 已证明连的正是 `mall_management_sql` + 8.4.11 那只库）。
2. **没烧并发**：P5–P7 全 SKIP、末行「未产生任何业务流量。退出码 2」⇒ 拦截发生在**负载段之前**。
3. **`15 = 11 + 1 + 3` 自洽**：11 张基线 + `order_state_transition` + 本笔 C3 块 1 的 3 张
   （`admin_audit` / `notification` / `notification_template`）—— 这正是修门禁后 `known == KNOWN_TABLES`
   那一半「4 张必须真在」的**直接证据**（表数能凑到 15，就是这 4 张都在）。

## 绿的那次证明了什么（同一套判据，修后复跑）

```
  P3  PASS 表数=15；已知追加表 order_state_transition / admin_audit / notification / notification_template 在 4/4 张（期望表数 = 11 + 4 = 15）
  P5  PASS GET http://127.0.0.1:8088/health/db → HTTP 200 …（Web+DB 联合就绪）
  P6  PASS 库身份对比（GET /health/db vs 脚本 JDBC） server_uuid/hostname/database/port/version 全部一致
  P7  SKIP 已用 --skip-negative-control 显式关闭

[阶段 D] preflight P0–P7 通过，开始跑负载段：S1,S2a,S2b,S3,S4a,S4b,B2,C
…
  门禁断言 30 项，失败 0 项

preflight  : 8 项，全部通过
gates      : 全部通过
exitCode   : 0
```

⇒ 与红那次**唯一不同的一行就是 P3**（`FAIL 期望 11 + 1` → `PASS … 在 4/4 张`），
且**负载段真跑了**（红那次 P5–P7 全 SKIP、2m0s；绿这次跑满 S1–C、4m5s，`5xx=0 connErr=0`）
—— 这正是「C3 块 1 对既有 A3 行为零影响」的直接证据。

## ⚠️ 本目录**不**包含 / 不写什么

- **不写 run number / job id**：CI 的运行号会失效、本地不可复核；要复核的是**这里文件的内容**
  （`runId` 在文件里自己写着，但那只是报告的一个字段，不是这里的索引键）。
- 不含 `app.log`（红那次 500KB+ 的启动日志）：判据不依赖它 —— 启动干净这件事在 §十七 17-1 有记，
  且两份报告里的 P1/P5/P6 已证明「后端起来了、Web+DB 都就绪」。
