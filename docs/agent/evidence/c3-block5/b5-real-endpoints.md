# C3 块 5 §四.3 —— 真端点验证（a3 profile + VM 真库）

时间：2026-10-11 23:21–23:25（本地，+08:00）
后端：`SPRING_PROFILES_ACTIVE=a3 ./mvnw -o spring-boot:run` · PID 112368 · 端口 8088
　　　`Started MallManagementApplication in 13.244 seconds`；日志实证 `app.auth.enabled=false`（a3 闸关）
库：`jdbc:mysql://192.168.229.129:3306/mall_management_sql`
账号：`uDopHnCeLS`（`user.username='1111'`）

## 为什么要造数据

`coupon` 表在 §三 删旧券之后是**空的** —— 而本次要验的恰恰是
「整条 `selectClaimable` 的装配」（`LEFT JOIN` / `Claimed` / `SoldOut` / `IsNew` / `thisWeek` 筛法 / `ORDER BY`）。
§四.2 的纯表达式哨兵只钉了**常量语义**，钉不到装配。
`selectClaimable` 一行空表上跑只会回一个空数组 —— 那证明不了任何筛法。

⇒ 用 `_work/b5-seed/Seed.java`（**写**执行器，不入仓；拒 DROP/TRUNCATE/ALTER/CREATE）
造 12 张券 + 8 条 `coupon_grant`，覆盖全部 5 个筛法与 4 个桶。**验完已全部清理**（见文末）。

- `_work/b5-seed/seed.sql` —— 7 张：A 本周新 / B 本周新(不限量) / C **上周旧券** / D **本周但超 3 天** /
  E **本周已领完** / F 本周但 EndAt 已过 / G 本周但 Status=stopped
- `_work/b5-seed/seed2.sql` —— 5 张 + 8 条 grant：H 快过期 / I、L 已过期（远、近）/ J、K 已使用（近、远）

## 验证步骤与结果

### 预演：先在库里用**真谓词**读回一遍（作为期望值基线）

7 行全表（节选判读列）：

| CouponId | Name | created_at (UTC) | 本地 created | InThisWeek | IsNew | SoldOut | NotExpired |
|---|---|---|---|---|---|---|---|
| CPSEED00000001 | 本周新券A | 2026-10-11 14:20:44 | 10-11 22:20 | 1 | 1 | 0 | 1 |
| CPSEED00000002 | 本周新券B | 2026-10-09 15:20:44 | 10-09 23:20 | 1 | 1 | 0 | 1 |
| CPSEED00000003 | 上周旧券C | 2026-10-01 15:20:44 | 10-01 23:20 | **0** | 0 | 0 | 1 |
| CPSEED00000004 | 本周非新券D | 2026-10-07 15:20:44 | 10-07 23:20 | 1 | **0** | 0 | 1 |
| CPSEED00000005 | 本周已领完E | 2026-10-11 12:20:44 | 10-11 20:20 | 1 | 1 | **1** | 1 |
| CPSEED00000006 | 本周已过期F | 2026-10-11 10:20:44 | 10-11 18:20 | 1 | 1 | 0 | **0** |
| CPSEED00000007 | 本周停用券G | 2026-10-10 15:20:44 | 10-10 23:20 | 1 | 1 | 0 | 1 |

⇒ 全量（`Status='active'` + 未过期）应为 **A B C D E**（5 张，F/G 被筛）；
　 本周（再加 `THIS_WEEK`）应为 **A B D E**（4 张，C 被筛）。

### [1] `POST /marketing/coupons`（省略 `thisWeek`）→ `b5-real-coupons-all.json`

```json
{"success":true,"limit":10,"daysLeft":1,"list":[
 {"CouponId":"CPSEED00000001","Name":"本周新券A",...,"Claimed":0,"SoldOut":0,"IsNew":1},
 {"CouponId":"CPSEED00000002","Name":"本周新券B",...,"Claimed":0,"SoldOut":0,"IsNew":1},
 {"CouponId":"CPSEED00000003","Name":"上周旧券C",...,"Claimed":0,"SoldOut":0,"IsNew":0},
 {"CouponId":"CPSEED00000004","Name":"本周非新券D",...,"Claimed":0,"SoldOut":0,"IsNew":0},
 {"CouponId":"CPSEED00000005","Name":"本周已领完E",...,"Claimed":0,"SoldOut":1,"IsNew":1}]}
```

✅ 5 张 = A B C D E，与库内预演**逐行吻合**；`daysLeft:1`（周日）；序 = `updated_at ASC`
（09:20 → 10:20 → 11:20 → 12:20 → 13:20 = **最早更新最左**，用户「六、保持」那条）。
✅ `IsNew` 形态是**整数 `1`/`0`**（不是 `true`）—— 印证 `api/marketing.js` 里那条 ⚠️「形态可能是 `true` 也可能是 `1`，判据要走 `truthy()`」。

### [2] `POST /marketing/coupons?thisWeek=1` → `b5-real-coupons-week.json`

返回 **A B D E**（4 张）。✅ **C（上周）被筛掉、F/G 照旧被筛掉** —— `THIS_WEEK` 那个 `AND` 是**唯一**的差异，
其余字段逐字相同 ⇒ 「同一个查询两个筛法」（裁定「甲」）成立：主页那 4 张是「可领」5 张的真子集。

### [3] `POST /marketing/coupon/claimBatch` 第 1 次 → `b5-real-claimbatch-1.json`

```json
{"msg":"已领取 3 张","total":3,"success":true,"claimed":3,"failed":0,"daysLeft":1,
 "items":[{"CouponId":"CPSEED00000001","Name":"本周新券A","success":true,"msg":"领取成功"},
          {"CouponId":"CPSEED00000002","Name":"本周新券B","success":true,"msg":"领取成功"},
          {"CouponId":"CPSEED00000004","Name":"本周非新券D","success":true,"msg":"领取成功"}]}
```

✅ 集合 = **A B D** —— 正是「本周 ∩ 可领」：C 不在本周、**E 已领完被 `SoldOut` 跳过**。
　 ★ 这一条同时证明「**服务端定集合**」真的生效：请求体里**只有 `Account`**，没有任何券 ID 列表。
✅ 与真库读回**一致**：`coupon_grant` 恰 **3 行**（A/B/D × `uDopHnCeLS`，`Source='claim'`、`Status='unused'`）；
　 `coupon.ClaimedQty` 恰好 A/B/D 各 +1（`1/1/1`），C `0`、E `5`（未动）、F/G `0`。

### [4] `POST /marketing/coupon/claimBatch` 第 2 次 → `b5-real-claimbatch-2.json`

```json
{"msg":"本周没有可领的新券","total":0,"success":true,"claimed":0,"failed":0,"daysLeft":1,"items":[]}
```

✅ **空转**：`coupon_grant` 仍是 **3 行**、`ClaimedQty` **一个都没再涨** ⇒
　 「已领过的不重复占名额」成立（`Claimed` 为真的行**根本没进集合**）。
✅ 顶层 `success:true` + `total:0` ⇒ 前端 `doClaimBatch` 的
　 `data.total === 0 ? '本周没有可领的新券'` 分支**确实可达**（不是死代码）。
✅ ⚠️ 这里也顺带印证那条刻意取舍：**顶层 `success:true` 不含「领到了」的意思** ——
　 第 2 次一张没领到，它照样是 `true`（说的是「批量动作执行完毕」）。

### [5] `POST /marketing/myCoupons` → `b5-real-mycoupons.json`

（先补种 H/I/J/K/L 五张 + 5 条 grant，见 `seed2.sql`）

```json
{"success":true,"redDot":true,"expiringCount":1,"list":[ ... 8 行 ... ]}
```

实际返回次序（客户端按数组原序切 tab，**不重排**）：

| 序 | Bucket | CouponId | Name | GrantStatus | UsedAt | EndAt | EndDaysLeft |
|---|---|---|---|---|---|---|---|
| 1 | `expiring` | CPSEED00000008 | 快过期券H | unused | — | 2026-10-12 03:22:55 | **0** |
| 2 | `usable` | CPSEED00000002 | 本周新券B | unused | — | 2026-10-31 15:20:44 | 19 |
| 3 | `usable` | CPSEED00000004 | 本周非新券D | unused | — | 2026-11-05 15:20:44 | 24 |
| 4 | `usable` | CPSEED00000001 | 本周新券A | unused | — | 2026-11-10 15:20:44 | 29 |
| 5 | `used` | CPSEED00000010 | 已使用券J | used | 2026-10-11 13:22:55 | 2026-10-31 15:22:55 | 20 |
| 6 | `used` | CPSEED00000011 | 已使用券K | used | 2026-10-06 15:22:55 | 2026-10-31 15:22:55 | 20 |
| 7 | `expired` | CPSEED00000012 | 已过期券L | unused | — | 2026-10-10 15:22:55 | −1 |
| 8 | `expired` | CPSEED00000009 | 已过期券I | unused | — | 2026-10-06 15:22:55 | −5 |

✅ `redDot:true` / `expiringCount:1` —— 与 H 的 `Bucket='expiring'` 同一处（unused 那张 grant 命中）。
　 注意 **K 的 `Status='used'` 但 `EndAt` 还没过**：它进 `used` 桶而**不是** `expired` ⇒
　 「已核销」与「已过期」确实是两件事（`CouponDao` javadoc 那条 ⚠️ 成立）。
✅ **§1.4 改的那条 `ORDER BY` 生效**：
　 · `used` 桶 = **J(10-11) → K(10-06)** = 最近用掉的在前（旧写法 `EndAt ASC` 在这里两者 `EndAt` 相同 ⇒
　　 只靠 `g.Id DESC`，`UsedAt` 根本不会被用上）；
　 · `expired` 桶 = **L(10-10) → I(10-06)** = 最近过期的在前（旧写法 `EndAt ASC` 会给 **I → L**，**正好相反**）。
✅ `usable` 桶内按 `EndAt ASC`：B(10-31) → D(11-05) → A(11-10)（「无期限的沉底」那条键在本批无样本）。
✅ `EndDaysLeft` 的截断方向：H 剩 12h ⇒ **0**（前端 `endLabel` 渲染「今天过期」）、
　 L 已过期 1 天 ⇒ **−1**。⚠️ L/I 是 `expired` 桶、前端**不**渲染 `endLabel`（只在 `expiring` 档渲染）⇒
　 负值不会露到界面上。

## 清理（破坏性操作 —— 读回双重确认）

`_work/b5-seed/cleanup.sql`：

```sql
DELETE FROM coupon_grant WHERE CouponId LIKE 'CPSEED%';
DELETE FROM coupon       WHERE CouponId LIKE 'CPSEED%';
```

跑完读回（`_work/b5-seed/cleanup-run.txt`）：两表 `COUNT(*)` 均 = **0**，
且 `SELECT COUNT(*) FROM coupon` 全表 = **0**（未误伤非 `CPSEED` 行）。
⇒ VM 库回到 §三种删旧券之后的**空券**状态，交给用户手验时「领券中心」正显示新的
「本周暂无新券 —— 去用户中心的「优惠券 · 可领」看全部可领券」空态。

## 本份证据**没有**覆盖的

1. **前端**：以上全是 curl 打的真端点。前端怎么渲染（NEW 角标位置、`daysLeft` 那行小字、
   弹窗 4 tab 分组、几何）**不在这里** —— 归 §四.4 探针（mock 后端）与用户手验。
2. **`use`/核销路径**：`used` 桶那两行是**直接插的** `coupon_grant`（`Status='used'`），
   没走结算 —— 核销 `markUsed` 归块 5 commit 5。
3. **并发**：`claimBatch` 与单张 `claim` 抢同一张券的竞态没有构造。
4. **`percent` 券的 `SoldOut`**：B 是 `TotalQty=0`（不限量）⇒ 只验了「0 量不被判领完」，
   `percent` 类型本身与 `SoldOut` 无交互，未单独构造。

## 产物清单

| 文件 | 内容 |
|---|---|
| `b5-real-coupons-all.json` | [1] 全量可领（省略 `thisWeek`） |
| `b5-real-coupons-week.json` | [2] 本周新券（`thisWeek=1`） |
| `b5-real-claimbatch-1.json` | [3] 一键领取第 1 次 |
| `b5-real-claimbatch-2.json` | [4] 一键领取第 2 次（空转） |
| `b5-real-mycoupons.json` | [5] 券包（4 桶齐） |
| `b5-week-window-sql.txt` | §四.2 的纯表达式哨兵（本轮之前） |
| `_work/b5-seed/{Seed.java,seed.sql,seed2.sql,cleanup.sql,cleanup-run.txt}` | 造数据/清理执行器与日志（**不入仓**） |
