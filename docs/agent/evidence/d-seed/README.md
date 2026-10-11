# D 笔证据 —— 演示数据种子（seed-demo.sql）

日期：2026-10-11 · 库：VM `192.168.229.129:3306` / `mall_management_sql`

## 本目录文件

| 文件 | 是什么 |
|---|---|
| `before-counts.txt` | 灌种子**之前**的行数基线（`login 9 / order 24 / order_item 0 / goods 6 / stats_daily 8`） |
| `verify-run1.txt` | 第一次灌库后的自查输出（`scripts/probes/seed-verify.sql`）—— **有 1 条不过**，见下 |
| `verify-run2.txt` | 修完 `order_item.MerchantId` 那处缺陷、重灌后的自查输出 —— **12 条带判据的自查 + 7 段分布/口径核对**，全绿 |

## 怎么跑的

```bash
# ① 加 Source 列（mall_management_sql.sql 末尾的 Migration 块，单独抽出执行）
sed -n "$(grep -n 'Migration: 可视化段补丁 —— 演示种子标记列' mall_management_sql.sql | cut -d: -f1),\$p" mall_management_sql.sql > _work/_source-migration.sql
mysql -h192.168.229.129 -utest -p --default-character-set=utf8mb4 mall_management_sql < _work/_source-migration.sql   # 跑两遍，第二遍 EXIT=0（幂等）
# ② 生成 + 灌种子
node scripts/seed-demo-gen.mjs
mysql -h192.168.229.129 -utest -p --default-character-set=utf8mb4 mall_management_sql < seed-demo.sql
# ③ 自查（只读）
mysql -h192.168.229.129 -utest -p --default-character-set=utf8mb4 -B mall_management_sql < scripts/probes/seed-verify.sql
# ④ 统计表自洽
curl -s -X POST "http://localhost:8088/admin/stats/rebuild"
```

> ⚠️ **演示口令不入库**：`login.Password` 一律写占位符 `<demo-pwd>`（本仓不落明文口令）。
> 本地演示若需用这些账号登录，两条路：`DEMO_PWD=... node scripts/seed-demo-gen.mjs` 重新生成一份
> （生成物放本地、不入库），或灌完后执行本地未入库的回填脚本 `_work/_seed-local-pwd.sql`。
> 生成器与 `seed-demo.sql` 逐字自洽：默认 `DEMO_PWD` 下重跑，产物与入库的这份**完全相同**。

## 灌进去的规模（种子自检输出）

```
login 40 / user 40 / goods 44 / goods_audit 24 / order 178 / order_item 199
cart 8 / notification 52 / admin_audit 60 / merchant_application 14
orders_done 102 / orders_unpaid 13 / orders_cancelled 7 / order_span_days 49
```

## 自查与核对（`verify-run2.txt` 全绿：**12 条带判据的 `check_item` + 7 段分布/口径输出**）

关键几条：

- `baseline_rows_intact` 9/9/6/24 —— **存量行一条没被删**（与 `before-counts.txt` 一致）。
- `no_future_timestamps`：最晚一刻 `2026-10-10 22:14:58` < `today_midnight = 2026-10-11`；跨度 `49` 天。
- `create_time_zero_padded` = 0 —— `order.CreateTime` 全是零填充 `'YYYY-MM-DD HH:MM:SS'`（否则 `DATE()` 解成 NULL、看板静默漏单）。
- `order_total_matches_items` = 0 单不符 —— 逐单 `Σ order_item.Subtotal == order.Price` 且 `Σ Quantity` 一致。
- `no_orphan_or_empty_items` 0 / 0。
- 六档状态全有：`0_unpaid 13 / 1_paid_not_shipped 19 / 2_shipped_not_received 24 / 3_done 102 / 4_cancelled 7 / 9_abnormal 13`。
- 周末低谷：工作日 `4.43 单/天`（35 天）vs 周末 `1.64 单/天`（14 天）≈ **2.7 倍**，按**执行时的真实星期**算。
- `no_day_gap` 49 / 49 —— 窗口内每一天都有单。
- `no_truncation`：`Images` 最长 85（列宽 100）、`ProductInformation` 55（列宽 500）。
- 用户多角色 24 : 12 : 4；类目 10/10/8/8/8；占位符/debug/假时间 **0**。
- `item_codes_exist_in_goods` = 0、`cart_rows_join_goods` 8/0（`ProductCode` 全能 JOIN 上 `goods`）。

## 第一次灌库时发现的缺陷（已修）

`verify-run1.txt` 里 `item_codes_exist_in_goods = 18`：一单两件时我从**全表**随机挑第二件，
于是第二件可能来自别家 ⇒ `order_item.MerchantId`（当时取的是全单商家）与 `order_item.ProductCode`
对不上 `goods`，`selectTopGoods` 的 `(MerchantId, ProductCode)` 归类会落空。

修法：多品也**只从同一个商家的货里挑**（`order.MerchantId` 是唯一字段 —— 购物车结算即按商家分组建单）。
重灌后该条归零。

## 端到端抽查（走 HTTP，不是只看库）

- `POST /admin/stats?days=30`（2026-09-12 ~ 10-10，29 天）逐日 GMV `129 → 4218`，形状干净；
  工作日 5-6 单、周末 1-2 单的低谷肉眼可见。
- `POST /merchant/stats?MerchantId=r2vfwK6z3E`：`topGoods` 返回真实种子商品
  （`竹纤维四件套 1.8m 床 / 星河不粘炒锅 32cm / 加厚收纳箱 60L 两件套`），量价自洽 ⇒ `order_item` 链路通。
- `POST /notification/list?toId=uDopHnCeLS&toType=customer`：种子通知落进存量演示账号 `1111` 的收件箱，
  `TargetId` 指向种子订单号，`unread=1`。

## 存量脏数据清理（2026-10-11，用户逐条裁定后执行）

脚本：`scripts/probes/cleanup-dirty-legacy.sql`（每条 DELETE 后跟 `ROW_COUNT()` 自报行数）
输出：`cleanup-legacy.txt`（本目录）· 回滚备份：`_work/_backup-test-orders-1010.sql`

| 类别 | 连带走 | 行数 |
|---|---|---|
| `order` Price=10000000（商品名「黑客」） | `order_state_transition` | 1 + 1 |
| `goods` Id 20/22/23/24/25/31（`222`/`叠法双方`/`黑客`/`1`/`啊实打实`） | `goods_audit` | 6 + 1 |
| `login` / `user` `i2probe` + `性暗示` | `notification` | 2 + 2 + 2 |
| `order` 2026-10-10 的 17 笔存量测试单（内容全是已删垃圾商品） | `order_shipping_track` 33 / `order_state_transition` 55 | 17 |

`order_item` / `cart` / `merchant_application` / `goods_sku` 连带各 **0 行**（本来就没有）。
`admin_audit` 的 actor 记录按用户要求**未动**（历史审计保留）。
脚本整段重跑第二遍时 ①–③ 全报 0 ⇒ 幂等成立。

清理前后（`POST /admin/stats?days=30` 的 `days` 序列）：

```
删前：2026-10-10 orders=19 gmv=72764    gmv min 129 / max 72764  ratio 564.1
删后：2026-10-10 orders= 2 gmv=  356    gmv min 129 / max 4218.8 ratio  32.7
```

留存的存量单：`order` 6 笔（2026-05-18 三笔 + 05-21 三笔，min 2 / max 22328）——
用户明示「时间跨度大不是异常，清了反而像数据只有 10 月」。种子 `Source='seed'` 一行未动
（`login 40 / user 40 / goods 44 / order 178 / order_item 199 / notification 52`）。
残留扫描 `bad_login / bad_goods / bad_price / bad_order_price` 全 0；10 月已无存量单。

### 本目录里仍未裁的两件小事

- `goods` Id 120「无线蓝牙耳机 声谷标准版」299 `GD-101-9552` —— 与种子 goods Id 76 同商家/同名/同价/同码，
  是 2026-10-11 19:22:52 一次「审核通过」动作新建的**重复行**。名字价格都正常 ⇒ 按「看起来普通的旧数据不清」**未删**。
- `customerimage` 1 行（i2probe）+ `merchantimage` 1 行（N7w3UOB5VI）—— 被删账号的配套头像，
  **不在用户给的连带清单里**，未删。
