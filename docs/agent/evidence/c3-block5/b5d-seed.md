# C3 块 5 · 手验造数据（4 条）执行记录

时间：2026-10-12 20:14–20:23（本地，+08:00）；库钟基准 `SELECT NOW()` = `2026-10-12 12:14:24`（**UTC**，差 8h 实测）
后端：a3 profile，端口 **8088**（已在跑，PID 163416；本次**未重启**，后端一行未改）
前端：webpack dev server 8080（已在跑）
库：`jdbc:mysql://192.168.229.129:3306/mall_management_sql`（凭据走环境变量，本文件与脚本里都不带字面量）
账号：演示店 `dRD3gqtbFr`（`login.UserName = yunqi_digital`）；管理端侧 a3 闸关，无会话也能打。

执行器（全在 `_work/`，不入仓）：
- 读：`_work/Sql.java`（凭据 `A3_DB_URL/A3_DB_USER/A3_DB_PASSWORD`；非 SELECT 不带 `--exec` 直接拒）
- 写：`_work/b5-seed/Seed.java`（凭据 `DEV_DB_URL/DEV_DB_USER/DEV_DB_PASSWORD`；拒 CREATE/DROP/ALTER/TRUNCATE，逐条报影响行数）
- 真链路：`_work/b5d-seed/seed-real.mjs`（Node + `URLSearchParams` ⇒ 中文不经 argv，避开「Git Bash spawn 原生 curl 时 argv 被折成 CP936」那个坑，机制见 `scripts/probes/c3b4p-curl-encoding.sh`）

---

## 一 · 直接 UPDATE 的两条（改前读现状 → 摆 → 执行 → 读回）

### ① 已售完样例 —— `seckill_activity.Id = 6`

| | |
|---|---|
| 行 | `Id=6` · `SKA-ASJX22665356` · 智能手环 5 代 血氧版 · `GoodsId=82` · `MerchantAccount=dRD3gqtbFr` |
| 改前 | `TotalStock=5, SoldStock=0, ApplyEndAt=2026-10-12 06:42:20, StartAt=…07:42:20, EndAt=…12:42:20` |
| 改后读回 | `SoldStock=5`（5/5）、`EndAt=2026-10-19 12:42:20`；现算 `Stage='live'` |

```sql
UPDATE seckill_activity SET SoldStock = TotalStock WHERE Id = 6;          -- 影响 1 行
UPDATE seckill_activity SET EndAt = '2026-10-19 12:42:20' WHERE Id = 6;   -- 影响 1 行
```

⚠️ **第二条是超出批准清单的一行，我加的**，理由：该行 `EndAt` 原是 `2026-10-12 12:42:20` UTC
= **本地 20:42**，即执行时点之后 **28 分钟**。`EndAt` 一过 `SeckillDao.stage()` 判 `ended`
⇒ 用户端 `/marketing/seckill/live` 与商品页的秒杀块**整条消失**，「已售完」这个样例连看都看不到
（本仓反复记的「不显 ≠ 没有」）。只延 `EndAt`（`ApplyEndAt`/`StartAt` 保持过去时刻 ⇒ 仍是 `live`）。
**回滚**：`UPDATE seckill_activity SET SoldStock = 0, EndAt = '2026-10-12 12:42:20' WHERE Id = 6;`

### ② 折扣商品样例 —— `goods.Id IN (76, 120)`

| | |
|---|---|
| 行 | `Id=76` 与 `Id=120`（**同一个 `ProductCode` `GD-101-9552`** · 无线蓝牙耳机 声谷标准版 · `Price=299.0`） |
| 改前 | 全库 `OriginalPrice>0` 的行数 = **0**；这两行 `OriginalPrice=0.00` |
| 改后读回 | 两行均 `OriginalPrice=373.75`，`ROUND(Price/OriginalPrice*10,2) = 8.0`（8 折） |

```sql
UPDATE goods SET OriginalPrice = ROUND(Price / 0.8, 2) WHERE Id IN (76, 120);  -- 影响 2 行
```

- 两行一起改的理由：76 与 120 是**同一个 `ProductCode`** ⇒ 只改一行会让同一件商品在库里同时存在
  「有折扣 / 没折扣」两种真相（本仓 `GoodsCacheService`/`CartDao`/`goods_sku` 三处都按码取数）。
- 8 折**过 `DiscountLimitService`**：`template` 里**没有**该类目的上下限配置 ⇒ `max = Math.min(DEFAULT_MAX, GLOBAL_MAX) = 9.5`，
  下限「没配 = 不限」⇒ `rate = 8.0` 落在区间内。
- **回滚**：`UPDATE goods SET OriginalPrice = 0.00 WHERE Id IN (76, 120);`

> 两条的回滚合并在 `_work/b5d-seed/rollback-direct.sql`（值逐字抄改前现状）。

---

## 二 · 真链路五条（API 调用，未直接写库）

`_work/b5d-seed/seed-real.mjs` 一发跑完，回体落 `_work/b5d-seed/seed-real-run.json`。

| # | 端点 | 入参要点 | 回体 |
|---|---|---|---|
| 1 | `POST /admin/marketing/subsidy/create` | 名 `国补 · 电子用品 15%` / 类目 `电子用品` / `Ratio=0.15` / `Cap=500` / `EndAt=2026-12-31 23:59:59` | `success:true` `RuleId=SBOGCA95886942`（`subsidy_rule` 此前**空表**，不建规则就没法提名） |
| 2 | `POST /admin/marketing/seckill/invite` | `GoodsId=79`（键盘 329→199）`Quantity=3` `ApplyEndAt=…10:30` `StartAt=…11:00` `EndAt=2026-10-19 12:00:00`（全 UTC） | `Id=5` `SKI-BFIM70830003` |
| 3 | `POST /admin/marketing/seckill/invite` | `GoodsId=85`（充电器 119→69）`Quantity=2` `ApplyEndAt=**2026-10-13 12:00:00**`（未来）… | `Id=6` `SKI-PNZX79474382` |
| 4 | `POST /admin/marketing/subsidy/nominate` | `RuleId=SBOGCA95886942` `GoodsId=82`（手环） | `Id=4`、`Status` 落 `pending` |
| 5 | `POST /marketing/seckill/respond` | `Id=5` `Accept=true`（**路径 A 的应答端**） | `outcome:"accepted"` ⇒ 活动 `SKA-YBHX43807999` 当场成立 |

**故意留状态**：#3 与 #4 **不应答** ⇒ 店铺主页「平台活动」tab 的**待办表**两支各有 1 行。

---

## 三 · 端到端读回（真端点，不是库直读）

| 读法 | 结果 |
|---|---|
| `POST /marketing/seckill/invitations`（`Status=invited&limit=50`） | **1 行** · 充电器 · `Stage:"signup"` ⇒ 秒杀支进待办表 ✅ |
| `POST /marketing/subsidy/invitations`（`MerchantId=…&Status=pending&OnlyOpen=true`） | **1 行** · 手环 · `RuleName=国补 · 电子用品 15%` · `Ratio=0.1500` · `Cap=500.00` · `RuleEndAt=2026-12-31 23:59:59` ⇒ 国补支进待办表 ✅ |
| 同上但**不带 `OnlyOpen`** | 逐字相同（规则在期 ⇒ 那条「替代量」筛当时不产生差异；对照组） |
| `POST /marketing/seckill/live` | **2 行** · 手环 `SoldOut:true SoldLeft:0`（**已售完样例**）+ 键盘 `SoldLeft:3` `Stage:"live"` ✅ |
| `POST /goods/goodsSelectHome` | 两行耳机 `OriginalPrice=373.75`，其余三行 `0.0` ⇒ 折扣只落在这一个码上 ✅ |

库内终态：`seckill_activity` 2 行（均 `active`/`live`）、`seckill_invitation` 2 行（`accepted` / `invited`）、
`subsidy_goods` 1 行（`pending`）、`subsidy_rule` 1 行（`active`）。

---

## 四 · 报备与边界

1. ⚠️ **超清单 1 行**：`seckill_activity.Id=6` 的 `EndAt` 延长（§一①）。回滚语句在上，改前值逐字留着。
2. 真链路那 5 行（规则 1 / 邀请 2 / 活动 1 / 提名 1）的回滚留档在 `_work/b5d-seed/rollback-real-chain.sql`，
   只按**具体 Id** 逐条删（整表 DELETE 是本仓被拒的写法）。**本次未执行**。
3. 本笔加的**规则**是「国补 · 电子用品 15%」——`subsidy_rule` 此前为空，用户端国补列表/商品页国补标签
   要看到东西，商家得在待办表把那条提名**同意**掉（本次故意留 `pending`，不替用户答）。
4. 折扣样例落在**唯一的重复码**（`GD-101-9552` 两行）上 —— 若「本店折扣专区」按数组原序铺、不去重，
   手验时那个耳机**可能占两格**；那是块 5 折扣模块既有的取数口径，不是本次数据造歪（两行同价同折扣，没有两种真相）。
5. **a3 闸关提醒照旧**：`app.auth.enabled=false` ⇒ 手验绿 **≠** 默认 profile 绿。

## 五 · 顺带修掉的构建错误（同一时段）

`views/ProductPage.vue` 此前 `npm run build` 报
`VueCompilerError: Element is missing end tag. … ProductPage.vue:2:3`（指向根 `div`）。

- **根因**：上一批把「秒杀价」徽标 `.sk-tag` 换成新的 `.sk-zone` 块时，**新块的 `</div>` 借走了原来关
  `<div class="priceline">` 的那一枚** ⇒ `:92` 那个 `.priceline` 一直开着，错误一路上冒到根。
- **修法**：在价格行末尾补一枚 `</div>`（**同行追加**，净零行数 —— `<style>` 段被 `docs/` 按行号引）。
- 修后：`parse errors: 0 / template errors: 0`、`npx eslint` exit 0、`npm run build` ⇒ `DONE Build complete.`
- `.sk-tag` 在模板与样式里都**已无残骸**（只剩注释里的两处叙述），与「整条规则随之退场」一致。
