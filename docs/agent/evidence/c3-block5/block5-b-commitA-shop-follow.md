# 块 5 · B 组 commit A —— `shop_follow` 建表 + `shop` 域三端点（证据）

- 日期：2026-10-12
- 范围：迁移块 ⑫（`shop_follow`）· `ConcurrencyRunner.java` P3 四处同改 · 新域
  `com.minke.mallmanagement.shop.follow.{dao,service,controller}` · `AbstractSmokeTest` 的 `@Import`
- 裁定依据：`_work/block-5-updates.md`（用户 2026-10-12）+ 方案 §2 / §3

---

## 1 · 落库前后（VM 库 `192.168.229.129:3306/mall_management_sql`，MySQL 8.4.11）

| 判据 | 落库前 | 落库后 |
|---|---|---|
| `information_schema.tables` 总数 | **29** | **30** |
| `shop_follow` 存在？ | 0 | 1 |
| `SELECT COUNT(*) FROM shop_follow` | —（表不存在） | **0** |

**幂等**：把迁移块 ⑫ **连跑两遍**，两遍均无输出（无报错）；表数停在 30 —— 第二遍走
`CREATE TABLE IF NOT EXISTS` 的空操作。

### 表形状（`information_schema` 实读）

| 列 | 类型 | NULL |
|---|---|---|
| `Id` | `int` | NO |
| `CustomerId` | `varchar(40)` | NO |
| `MerchantId` | `varchar(40)` | NO |
| `created_at` | `datetime` | NO |
| `updated_at` | `datetime` | NO |

索引四条，逐一核对：`PRIMARY(Id)` · `uk_shop_follow_cust_merchant(CustomerId,MerchantId)` ·
`idx_shop_follow_merchant_created(MerchantId,created_at)` · `idx_shop_follow_cust_created(CustomerId,created_at)`。

---

## 2 · 三端点实测（真后端 8088 · a3 profile → VM 库）

被测对：`CustomerId=uDopHnCeLS`（`user.username='1111'`）· `MerchantId=5N1k5K2guh`（oppo12 的商家）。

| 步骤 | HTTP | `success` | `following` | `followers` |
|---|---|---|---|---|
| `followState`（落库前） | 200 | true | **false** | 0 |
| `follow` 第 1 次 | 200 | true | true | **1** |
| `follow` 第 2 次（**幂等**） | 200 | true | true | **1**（未变 2） |
| `followState`（关注后） | 200 | true | true | 1 |
| `unfollow` 第 1 次 | 200 | true | false | 0 |
| `unfollow` 第 2 次（**幂等**） | 200 | true | false | 0 |
| `followState`（取关后） | 200 | true | false | 0 |
| `follow` 少 `MerchantId` | 200 | **false** | — | — |
| `follow` 少 `CustomerId` | 200 | **false** | — | — |
| `POST /shop/nope`（对照） | **404** | — | — | — |

**直接读库双重确认**（不走 API）：测试跑完 `SELECT COUNT(*) FROM shop_follow` = **0**、
表总数 **30** —— 与 API 回的 `followers` 同源同值，表里确实没留下测试行。

### 2.1 边界 1：自己不能关注自己（用户 2026-10-12 补裁）

`CustomerId` 与 `MerchantId` **字面相等**（大小写不敏感）⇒ `follow` 回 `success:false`。

| 步骤 | HTTP | `success` | `msg` | `following` | `followers` |
|---|---|---|---|---|---|
| `follow` 自己（**同串**） | 200 | **false** | `不能关注自己` | — | — |
| `follow` 自己（**仅大小写不同**，`5N1K5K2GUH` vs `5N1k5K2guh`） | 200 | **false** | `不能关注自己` | — | — |
| `followState` 自己（**必须仍可用** —— 自己的店也要看得见粉丝数） | 200 | true | ok | false | 0 |
| `unfollow` 自己（**无害空操作**，不另加闸） | 200 | true | 已取消关注 | false | 0 |
| 对照 · 正常关注（别人） | 200 | true | 已关注 | true | 1 |

- **大小写不敏感**：表与库的排序规则是 `utf8mb4_0900_ai_ci` ⇒ 库里 `WHERE CustomerId = MerchantId`
  本来就是 CI 的，本闸用 `equalsIgnoreCase` 与库同判据，免得两条口径分叉。
- **闸只在 `follow`**：`unfollow` 传自己只是删 0 行；`followState` 判它会把「自己的店铺主页」
  的粉丝数整块弄没 —— 前端那边是**隐藏关注按钮**，不是不请求。
- ⚠️ **前端半边未落**：`ShopHome.vue` 的「自己的店不显示关注按钮」属 **commit C**
  （本页尚未新建），不是遗漏。

---

## 3 · ⚠️ 本笔**没能**在 a3 下验到的一格：403 归属

`/shop/{follow,unfollow,followState}` 三个端点的方法体第一行都是
`IdentityGuard.requireSelfOrAdmin(request, CustomerId)`，**但在 a3 profile 下这一格测不出来**：

`IdentityGuard.java:73-82` 的判据是「解析不出身份 ⇒ **放行**」，而 a3 钉
`app.auth.enabled=false`（启动日志实测 `登录闸装配（AUTH）… app.auth.enabled=false`
⇒ `AuthSession.identify()` 恒为 `null`）⇒ **传别人的 `CustomerId` 在 a3 下同样放行**。
这不是本笔的缺陷，是 `IdentityGuard` 的既有设计（同一条纪律见该类注释「为什么解析不出身份 = 放行」，
smoke 切片 + A3 都在这一档）。

⇒ 「传别人的 `CustomerId` ⇒ 403」这一条**归默认 profile 手验**（闸开着时才有会话可比），
a3 下不可达。**手验绿 ≠ 默认 profile 绿** —— A 组同款提醒。

---

## 4 · 复跑方式

```bash
# 后端（a3 → VM 库）
cd Mall-management-server && SPRING_PROFILES_ACTIVE=a3 ./mvnw -B spring-boot:run
# 三端点矩阵（脚本在 _work/，按约定不进仓）
PYTHONIOENCODING=utf-8 python _work/_tmp-shop-verify.py
```
