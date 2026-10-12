# 块 5〈Bug 1〉耳机详情页空白 —— 定位 + 修（2026-10-12）

现象（用户给的）：搜索结果里第 1 个与第 4 个都是「无线蓝牙耳机 声谷标准版 · `GD-101-9552` ¥299」，
点进详情页**整页空白**。

结论先说：**不是缺数据，是读侧对「同码重复」显式抛异常**。判据 = `(MerchantId, GoodName, ProductCode)`
**三元组**（**不是 `Id`**）；同码时**不取 MIN(Id)**，而是 `throw IncorrectResultSizeDataAccessException`
⇒ `GoodsService` 接成 `{success:false}` ⇒ 前端 `if (d2.success)` 全跳过 ⇒ `allItems` 空 ⇒ 空白页。

---

## 一 · 判据链（逐段实测/读码，行号为改动前）

| 环节 | 位置 | 事实 |
|---|---|---|
| 详情页 URL 参数 | `ProductPage.vue:490` | 只解构 `MerchantId / GoodName / GoodType / Id`；`Id` 存进 `productId` 后**加载路径根本不用它** |
| 取商品 | `ProductPage.vue:546-573`（`loadAllItems`） | `GoodsProductCodeSelect(MerchantId, GoodName)` → 型号码数组 → 逐个 `productCodePrice(MerchantId, GoodName, ProductCode)`；**只有 `d2.success` 才 push** |
| 取码 | `GoodsCacheService.listCodes`（`:363`） | `SELECT DISTINCT ProductCode …` ⇒ 同码重复**仍只回 1 个码** |
| 取价 | `GoodsCacheService.priceByTriple`（`:279`） | `SELECT Price, GoodImg, OriginalPrice … WHERE MerchantId=? AND GoodName=? AND ProductCode=?`，走 `queryForList`；**`rows.size() > 1` 时显式 `throw new IncorrectResultSizeDataAccessException(1, rows.size())`** |
| 成型 | `GoodsService.productCodePrice`（`:318-343`） | `catch (Exception e)` ⇒ `{success:false}`（200，不是 500） |

⇒ 三元组重复 = 该商品**恒失败**（不是偶尔），且**同码两行点进去是同一条 URL 路径**（`Id` 不参与取数），
所以第 1 个和第 4 个点进去一样空白。

### 实测回体（改前，探针 `scripts/probes/c3b5h-bug1-detail-blank.mjs`）

```
── /goods/GoodsProductCodeSelect  {"MerchantId":"dRD3gqtbFr","GoodName":"无线蓝牙耳机 声谷标准版"}
   → {"success":true,"list":["GD-101-9552"]}
── /goods/productCodePrice  {…,"ProductCode":"GD-101-9552"}
   → {"success":false}                      ← 耳机：恒失败
── /goods/productCodePrice  {…"机械键盘 87 键 茶轴"…"GD-104-7045"}
   → {"goodImg":"…","originalPrice":0.00,"success":true,"price":329.0}   ← 对照：无重复码 ⇒ 成功
```

## 二 · 「是缺数据吗」—— 不是（库实测）

```sql
SELECT Id, MerchantId, GoodName, ProductCode, Price, OriginalPrice, GoodImg, ShelfState, DeletedAt
  FROM mall_management_sql.goods WHERE ProductCode='GD-101-9552';
```

| Id | ProductCode | Price | OriginalPrice | GoodImg | ShelfState | DeletedAt |
|---|---|---|---|---|---|---|
| 76 | GD-101-9552 | 299.0 | 373.75 | `https://picsum.photos/seed/GD-101-9552/400` | 1 | null |
| 120 | GD-101-9552 | 299.0 | 373.75 | 同上 | 1 | null |

**两行数据都完整、同价同图** ⇒ 不是「某行缺数据」，是读侧遇到两行就抛。

全库重复三元组扫描（`GROUP BY MerchantId, GoodName, ProductCode HAVING COUNT(*)>1`）：

```
MerchantId   ProductCode   c   ids
dRD3gqtbFr   GD-101-9552   2   76,120      ← 全库**只有这一组**
```

（库钟 UTC、JVM/浏览器 +8h；两处查询都直连 VM `192.168.229.129:3306`，本文件不带任何口令字面量。）

## 三 · 修法（读侧取 MIN(Id)，净零行数）

`GoodsCacheService.priceByTriple` 两处改动，**行数不变**（`git diff --stat` = 16 加 / 16 删）：

1. **SQL 尾加** `ORDER BY Id ASC LIMIT 1` —— 判据是 `Id`：`goods.Id` 是 `AUTO_INCREMENT`
   ⇒ 同店内 `Id` 大小 = 上架先后 ⇒ `MIN(Id)` = 该三元组最早的那一行，**可复核、不臆造**。
2. **删掉 `rows.size() > 1` 的 throw 分支**（SQL 有 `LIMIT 1` 后该分支成为死代码），
   原处保留同长度注释：写清改判理由、实测证据、以及**代价**。

同处同步的注释（都在原行数内改写）：
`GoodsCacheService` 类 javadoc 〈为何改〉那条 `>1 行 → 异常上抛`、〈本次没动的两件事〉①
（原文写「实测当前 **0 个**重复三元组 ⇒ 该分支不可达」，现已是 **1 组**）；
`GoodsService:337` 那句「含缓存 Service 侧『>1 行』抛出的 …」。

**没做**（属 schema 变更，硬线必问）：给 `goods` 加三元组唯一索引 —— 那才是根因修法。
读侧只是不再被它拖成整页空白；**多行这件事本身仍在**。

### 代价（如实登记）

改前「多行 ⇒ 抛 ⇒ `@Cacheable` 不写入 ⇒ 数据一修好下一次读取立刻正确」这条**自愈**没有了：
现在多行是**正常返回值** ⇒ 进 TTL（a3 5m）⇒ 库里的重复行被删/改后，本键在 TTL 内仍回旧价。
自愈改由**写接口的 `evictByMerchant` allEntries** 承担（`goodsAdd` / `goodsUp` / `goodsDe` 三条都挂）。
换来的东西是本笔的全部目的：**详情页不再整页空白**。

## 四 · 测试同步（不改测试就会红）

`GoodsPriceCacheTest`（`goods/service/`）：
- `sqlAndArgsArePassedThroughUnchanged` —— SQL 字面量断言补 `ORDER BY Id ASC LIMIT 1`。
- 第四节的 3 条（改前断言「抛 + 不进缓存 + 自愈」）**整节重写**为新契约：
  `manyRowsTakesTheFirstRowInsteadOfThrowing`（取第一行）、
  `manyRowsNoLongerStarvesTheCaller`（★反向断言：调用方拿得到价）、
  `manyRowsResultIsCachedNow_unlikeTheOldThrowBehaviour`（⚠️ 代价如实钉住：进缓存）。
- 类 javadoc〈本类不覆盖的〉补一句：替身给不了 `ORDER BY` ⇒ 「取 MIN(Id)」这半边判据在
  SQL 字面量 + A3 实测，**不在本类**（免得后人以为本类证明了它证明不了的事）。

`PriceTripleFailSmokeTest` **未改**（它 mock 掉 Service 喂异常进去，测的是 Controller 侧 catch
⇒ 与生产是否抛无关），但它的注释「它是本次唯一**新增**的抛出口」已过期 —— 留到块 5 收口统一订正。

### 验证

| 项 | 结果 |
|---|---|
| 聚焦两类 | `Tests run: 14, Failures: 0` |
| **变异对照**（只 `git stash` 生产文件回改前态，测试不动） | **4 条红**：`manyRowsTakesTheFirstRow…`、`manyRowsNoLongerStarvesTheCaller`、`manyRowsResultIsCachedNow…`、`sqlAndArgsArePassedThroughUnchanged`；`PriceTripleFailSmokeTest` 5 条**仍绿**（预期：与生产行为无关）。对照成立后 `git stash pop` 复原 |
| 全量 | `Tests run: 284, Failures: 0, Errors: 0, Skipped: 26` ⇒ `BUILD SUCCESS` |
| 端到端（重启 a3 后端 8088 后重跑探针） | 耳机 `/goods/productCodePrice` → `{"goodImg":"https://picsum.photos/seed/GD-101-9552/400","originalPrice":373.75,"success":true,"price":299.0}` ✅（取到 Id=76；两行同价 ⇒ 取哪行值相同） |

## 五 · 没做的、以及要你裁的

1. **列表侧同码显两次 —— 未做**。搜索端点实测仍回 7 行、其中 `Id 76` 与 `Id 120` 同码同店。
   去重要放在**消费侧**（列表 computed），且**键必须含 `MerchantId`**（不同商家可复用码）；
   这一处与 ②③⑤ 的 `ProductCard.vue` 抽组件改的是**同一批文件**，建议**并进那一批一起做**，
   不重复动同一批文件。⇒ 请你点头（默认按「并入 ⑤」执行）。
2. ⚠️ **a3 闸关提醒照旧**：手验跑在 `SPRING_PROFILES_ACTIVE=a3` + `app.auth.enabled=false`
   ⇒ **手验绿 ≠ 默认 profile 绿**。
3. 文档（`cache-layer.md:440` 的「0 个重复三元组」、`:442` 的「该分支不可达，不修」、
   `slot-registry.md` 里 `PriceTripleFailSmokeTest.java:24` 那串）**一行未动** —— 按口径留到块 5 收口。
