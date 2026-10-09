# 证据索引 —— C3 块 1 的 CI 首跑红（P3 表数门禁）· 2026-10-09 晚

**登记**：`docs/agent/slot-registry.md` **§十七**（同笔）；进度见 `docs/agent/progress.md` 同日晚那条。

## 本目录内容

| 文件 | 是什么 |
|---|---|
| `a3-run-20261009-120224.txt` | **A3 并发测试自己那份报告**的完整副本（`scripts/concurrency/reports/` 的产物）。原始来源 = CI 那次运行的 artifact（`gh run download`），此处**只做搬运、不改一字** |

⚠️ **入库时由 `.log` 改名 `.txt`** —— `.gitignore:11` 有 `**/*.log`，`.log` 会被**静默**挡在门外
（第一次提交就踩了：README 进来了、证据没进来）。**内容一字未改**；证据目录用 `.txt` / `.out`
也是本仓既有惯例（对照 `evidence/i2/db/apply.out` / `state.txt`）。

## 这份证据证明了什么（判据就在文件里）

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

## ⚠️ 本目录**不**包含

- **修好之后那次（绿）的报告** —— 复跑绿了会另补一份同款副本进本目录（届时本 README 一并补行）。
- **不写 run number / job id**：CI 的运行号会失效、本地不可复核；要复核的是**这份文件的内容**。
