# 块 5 · 券相关整改（账号口径 + 不能领自己的券 + 商家端接弹窗）—— 证据

- 日期：2026-10-12
- 范围：`Mall-management-server` 券域 4 个 Java 文件 + `Mall-management-web` 5 个前端文件
- 口径：**先查后改**，不猜 · 两端券功能一字不差 · **块内不动文档** · 验收归用户 ·
  ⚠️ 手验挂 a3 ⇒ **闸关**（`app.auth.enabled=false`）⇒ **手验绿 ≠ 默认 profile 绿**

---

## 0 · 一条**必须先说的更正**：`CustomerId` 这个列**不存在**

用户指令里写的是「字段语义 `CustomerId` → 建议改名 `AccountId`（改字段名 = A 档 → 先报用户批）」。
**先核之后：改名动作根本不存在** —— 券域从头到尾没有叫 `CustomerId` 的列/参数：

| 层 | 真实名字 | 出处（`grep -n` 实测） |
|---|---|---|
| 表列 | **`coupon_grant.ToAccount`** | `mall_management_sql.sql:1273`（注释就是「领取人账号」） |
| 接口参数 | **`Account`** | `CouponController` 的 `@RequestParam("Account")`（4 个端点全是它） |
| 前端契约 | **`account`** | `api/marketing.js` 的 `@param {string} account` |

⇒ 所以本次**没有任何 DDL / 改名**（因此也**不需要** A 档批准）。
**真正变的是「值」**：`ToAccount` 里存的东西从 `user.customerid` 换成 **`login.UserName`（登录名）**。
这一点与用户的前提不符，**按实报**。

---

## 1 · 为什么要改口径（可复核的成因，不是推断）

a3 VM 库（`192.168.229.129/mall_management_sql`）2026-10-12 实测：

| 事实 | 数字 |
|---|---|
| `user` 表总行数 | 47（13 商家 / 33 客户 / 5 管理员） |
| **13 个商家的 `customerid`** | **全部为 NULL**（种子商家直接建号、没走过客户阶段） |
| `login` ↔ `user` 的 `UserName` ↔ `username` | **47 ↔ 47，零差集** |
| 客户 `123` 的 `customerid` | `pIzTWafaGp`（旧口径写进 `ToAccount` 的就是它） |

⇒ 旧口径下**商家在券包上没有账号可挂**（`customerid` 为空 ⇒ `HomePage` 恒显「登录后可领取优惠券」、
用户中心商家壳那一格恒为不可点的空态）。登录名是客户与商家**都有且同一个**的键 ⇒ 改口径。

---

## 2 · 后端改动（4 个文件，+187 / −18）

| 文件 | 改动 |
|---|---|
| `CouponDao.java` | 新增常量 **`NOT_SELF_ISSUED`**（列表侧那道筛）；`selectClaimable` 无条件追加它；新增 **`selectMerchantIdByUsername`**；**重写 `selectClaimantAccounts`**（全类唯一「进登录名、出业务号」的口，见 §5）；类注释新增口径一节 |
| `CouponService.java` | `doClaim` 在 **`tryClaim` 之前**加「不能领自己发的券」闸（排在前面 ⇒ 不占名额、不需要补偿路径）；`claimBatch` javadoc 同步 |
| `CouponController.java` | 4 个端点的身份判据由 `requireSelfOrAdmin`（比业务号）→ **`requireSelfOrAdminByUsername`**（比登录名）；`UserStateLookup` 走 `ObjectProvider` |
| `AdminCouponController.java` | `grant` 端点 javadoc 说明 `ToAccount` 现在是登录名 |

### 2.1 ⚠️ 身份判据**必须**同笔换（不然是恒 403）

`IdentityGuard.requireSelfOrAdmin` 量的是 `identity.userId()`（**业务号**），而登录名**永远不等于**它
⇒ 只改 DAO/值、不换判据，就是**对每个非管理员恒 403**（形状同补丁 A1 记录的那次双 403 事故）。

`UserStateLookup` 刻意不被 `AbstractSmokeTest` `@Import`（且 `@WebMvcTest` 切片不扫 `@Service`）
⇒ 一律 `ObjectProvider.getIfAvailable()`，取不到 ⇒ fail-open（`requireSelfOrAdminByUsername` 的第三处）。

### 2.2 两道闸的**同款判据**

- **列表侧**（管体验）：`CouponDao.NOT_SELF_ISSUED`
  ```sql
  (c.MerchantAccount IS NULL OR c.MerchantAccount = ''
   OR c.MerchantAccount <> COALESCE((SELECT u.merchantid FROM user u WHERE u.username = ?), ''))
  ```
  ⚠️ 三分支是**必须的**：第一版写成 `COALESCE(c.MerchantAccount,'') <> COALESCE(...,'')` ⇒
  **平台券（`MerchantAccount=''`）对客户全部消失**（客户的 merchantid 也是 `''` ⇒ `'' <> ''` = 0）。
  实测复跑确认修好：客户 11/11、商家 10/11。
- **动作侧**（管边界）：`CouponService.doClaim` 里那段，判据与上面**同款**（同 `selectMerchantIdByUsername`）。
  ⚠️ 两处**必须成对改**，只改一处 = 「列表里还看得见、点下去被拒」或「列表里没了、直连 API 还能领」。

---

## 3 · 前端改动（5 个文件，全部**净零行数**）

| 文件 | 改动 | 行数 |
|---|---|---|
| `api/marketing.js` | 文件头 ⚠️ 段（`account` = 登录名）+ 3 处 `@param` 文案 | +12 / −3 |
| `HomePage.vue` | `getCustomerId` → `getUsername`；14 处 `customerId` → `couponAccount`；`:413` 注释一句扩写 | **+15 / −15** |
| `ProductPage.vue` | `couponAccount = getUsername() || ''` + import 同行追加 `getUsername` + jsdoc | **+6 / −6** |
| `UserInfoPage.vue` | 商家端券格**两支合一**成无条件可点；`:account` / `loadWallet` / ref 全改；注释同步 | **+33 / −33** |
| `CouponWalletDialog.vue` | 头注释说明 `account` = 登录名、两支壳都 mount（**零逻辑改动**） | **+5 / −5** |

### 3.1 商家端券格：把「边界 2」废掉

原实现按 `user.customerid` 是否为空分两支：有 ⇒ 可点；**空 ⇒ 不可点的裸 `div` + `—` + `title` 解释**
（2026-10-12 B 组加的「边界 2」）。改口径后 `Account` = 登录名，客户与商家**都有**
⇒ 两支合一，与客户版那一格**逐字同形**（用户：「两端券功能一字不差」）。
「没有券」这种空态**不在这格上画**，画在**弹窗**里（用户：「customerid 为空 → 弹窗显示空态（不造假）」）。

### 3.2 `selectClaimantAccounts` —— 全类唯一的**口径例外**（必须翻回来）

`CouponExpiryJob` 拿它去和通知受众（`notificationService.resolveAudience(AUDIENCE_CUSTOMER)` 给的
**customerid**）比 `contains`。口径一改若不翻，就**永不命中** ⇒ 已领过的人照样收到「快来领」，
**而且不报错**（静默坏）。⇒ 该方法里 `JOIN user` 把登录名翻回 `customerid`，通知域**一字未动**。

---

## 4 · 数据迁移（**2 行**）—— ⚠️ 未执行，等用户

`coupon_grant` 全库只有 2 行，都是客户 `123` 的（旧口径值）：

| Id | CouponId | ToAccount（旧） | 应改成 |
|---|---|---|---|
| 9 | `CPf65c03d8-d29c-4e25-a0b2-1756b852e788` | `pIzTWafaGp` | **`123`** |
| 10 | `CPIXKU58285727` | `pIzTWafaGp` | **`123`** |

**不迁的后果是静默坏**：券包按登录名查 ⇒ 这 2 行谁也匹配不上（客户会看到「券包空了」）。

已就绪的执行器（**dry-run 已跑过，输出见 §6**）：`_work/b5-migrate-grant/MigrateGrant.java`
（旧值/新值都写死在常量里、执行前校验新值是唯一登录名、`--apply` 才写、写完读回双重确认）。

⚠️ **`--apply` 那一步被 auto-mode 的 `[Modify Shared Resources]` 挡住了** ——
理由写的是「用户的原文对 >0 行的情形要求**先报迁移方案**，只有 0 行才授权直接改」。
**这个判读是对的**（用户原话：「若 >0 → 报迁移方案」），⇒ 我停在这里报人，**没有绕**。

跑法（授权后一条命令）：
```bash
DEV_DB_URL='jdbc:mysql://192.168.229.129:3306/mall_management_sql?useUnicode=true&characterEncoding=utf8' \
DEV_DB_USER=test DEV_DB_PASSWORD=test123 \
java -Dfile.encoding=UTF-8 \
  -cp /c/Users/86159/.m2/repository/com/mysql/mysql-connector-j/8.0.31/mysql-connector-j-8.0.31.jar \
  _work/b5-migrate-grant/MigrateGrant.java --apply
```

⚠️ **不迁 = 手验时「客户 `123` 的券包是空的」** —— 那是**数据没迁**，不是代码没接。

---

## 5 · 验证结果

### 5.1 后端（真库真端点，a3 8088）

**列表侧那道筛**（`POST /marketing/coupons`，实测）：

| `Account` | 条数 | 说明 |
|---|---|---|
| `123`（客户） | **11 / 11** | 全都能看见 |
| `yunqi_digital`（商家，`merchantid=dRD3gqtbFr`） | **10 / 11** | **自己那张 `CPf65c03d8-…` 消失**；6 张平台券（`MerchantAccount=''`）与 `5N1k5K2guh` 家的 3 张**照旧在** |

**动作侧那道闸**（`POST /marketing/coupon/claim`，实测）：

| 调用 | 结果 |
|---|---|
| `Account=yunqi_digital` + 自己那张券 | `{"success":false,"msg":"不能领自己发的券"}` ✅ |
| `Account=123` + 同一张券 | `{"success":true,"msg":"领取成功"}`（⇒ 闸**只拦自己发的**，不误伤客户） |

### 5.2 构建 / 测试

| 项 | 结果 |
|---|---|
| `./mvnw -B test` | **EXIT=0**（全绿；日志 `_work/b5e-mvn-test.log`，无 `BUILD FAILURE`） |
| `npm run build` | `DONE Build complete.`（日志 `_work/b5e-build2.log`） |
| `npx eslint`（5 个改动文件） | **EXIT=0** |

### 5.3 前端探针 `scripts/probes/c3b5e-coupon-account.mjs` —— **14 项 / 0 失败**

⚠️ **用 mock**（同 `probe-b5-b-usercenter.mjs` 的取舍，文件头写了理由）：a3 闸关 ⇒ `/user/me`
拿不到真身份，而本笔要控的恰恰是「**商家**账号 + `customerid` 为空」这一个人格。
**只伪造、不落数据**；拦截到的请求 URL 全部记下来当证据。

关键三条断言 + 实测：

```
OK  ③ 那个请求的 `Account` 是**登录名** 2222  → ["2222"]
OK  ③ `Account` **不是**商家号/客户号（没退回旧口径）  → {"accts":["2222"],"merchantId":"5N1k5K2guh"}
OK  ② 弹窗真的开了（`.cdlg` + 遮罩）  → {"open":true,"title":"我的券包",
       "tabs":["可领","未使用","已使用","已过期"],"activeTab":"可领",
       "tip":"暂时没有可领的券。新券发布后会出现在这里，也会出现在主页的领券中心。"}
```

拦截到的请求体（**决定性证据**）：`POST /marketing/myCoupons   body=Account=2222`
—— 旧口径下这个请求**一个都不会发**（`customerid` 空 ⇒ `loadWallet` 早退）。

截图：`b5e-merchant-coupon-cell.png`（可点的那一格）、`b5e-merchant-wallet-dialog.png`（弹窗 4 tab）。

> ⚠️ 探针自己的一个缺陷（已修）：第一版只记 URL，而 `Account` 在 **POST 体**里
> （`URLSearchParams`）⇒ 取到 `null`、报 1 条 FAIL。**那是探针的缺陷，不是页面的**。

### 5.4 行号互引（`scripts/refs-scan.sh`）

| 项 | 结果 |
|---|---|
| 全仓 token 条数 | before 4336 / after 4336 |
| `diff before after` | **0 行**（零漂移、零 token 增删） |
| 4 个 Java 文件被按行号引 | **无**（`grep` 实测，故其后端注释增行不产生漂移） |

⚠️ 中途确实**自己制造过一次漂移**：`HomePage.vue` 的 `:413` 注释先写成 6 行（净 +5）、
`CouponWalletDialog.vue` 头注释净 +3 ⇒ 这两文件内部的 `文件:行号` 引用跟着位移
（`HomePage.vue:1242` → `:1247` 等）。**已把两处注释压回原行数**，复跑 `diff` 归零。
⇒ 这一条印证了「改注释也要净零行数」。

---

## 6 · 我自己造的探针残留（已清，如实报）

§5.1 里那次「客户领券成功」的调用**真的插了一行** `coupon_grant`（`Id=11`, `ToAccount='123'`）。
它数据上**合法**（客户领商家的券本来就允许），但是**我探针的副产物**，不该留在库里当「用户已领过」。

已用 `_work/b5-migrate-grant/DeleteProbeGrant.java` 清掉（目标写死 `Id=11` + 逐列比对 + `--apply` + 读回）：

```
【DELETE 影响行数】1   （应为 1）
  总行数：3 -> 2   （应为 3 -> 2）
✓ 残留已清，读回一致。
```

⇒ 库现回到「§4 那 2 行旧口径值」，与代码改前**除口径外**一字不差。

---

## 7 · 重启与三查（交给用户手验的那份）

| 查 | 结果 |
|---|---|
| 端口 | 8088 **LISTENING**（PID 146256）· 8080 仍在（PID 110948，dev server 自行重编） |
| profile | 日志 `The following 1 profile is active: "a3"` |
| 库 | Hikari `jdbcUrl = jdbc:mysql://192.168.229.129:3306/mall_management_sql` |

⚠️ **a3 闸关** ⇒ 手验绿 **≠** 默认 profile 绿：那两个 `Account` 身份判据（`requireSelfOrAdminByUsername`）
在默认 profile 下才真起作用，**本次没验过默认 profile**。

---

## 8 · 交给用户的待办

1. **数据迁移 2 行**（§4）—— 需要你点头（auto-mode 按你原文判「>0 行要先报」）。
2. **手验**：客户 `123` 与商家 `yunqi_digital` 各登一次，看券包 / 领券中心 / 商品页领券块 /
   用户中心商家壳那一格（现在应可点、点开是同一个弹窗）。
3. **测试残留**：`docs/美化/probe-b5-*.mjs` 5 个孤立探针文件仍未提交（本笔依旧没动它们）。
