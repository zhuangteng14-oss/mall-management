# 块 5 · B 组 commit B —— `UserInfoPage.vue` 重排 + 商家端【无名框】7 项（证据）

- 日期：2026-10-12
- 范围：`Mall-management-web/mall-management-system/src/views/UserInfoPage.vue` **一个文件**
  （753 → 1036 行）
- 裁定依据：`_work/block-5-updates.md`（用户 2026-10-12 定稿「甲 + 乙」+ 四处裁定 + 两处待定）
  + 方案 §4 + 用户当轮补充的**边界 2 / 边界 3**
- 口径：块内不动文档 · 验收归用户 · 加图不动结构 · a3 闸关 ⇒ 手验绿 ≠ 默认 profile 绿

---

## 1 · 两壳目标顺序（用户定稿「我的订单 ← 上 / 无名框 ← 下」）

| 位 | 商家端 | 用户端 | 本笔 |
|---|---|---|---|
| ① | 身份卡 | 身份卡 | **一行未动** |
| ② | **我的订单**（4 格） | **我的订单**（4 格） | 内容原在 ③ ⇒ **上移** |
| ③ | **【无名框】7 项** | **【无名框】4 项** | 原 ② 「经营数据 4 项」/「我的资产 4 项」⇒ **下移 + 商家端换成 7 项** |
| ④⑤⑥⑦ | 最近上架 / 服务 / 账号设置 / 资料完善 | 收藏足迹 / 服务 / 同左 | **一行未动** |

实measure（探针 1440×900）：两壳 `tiles`（订单四格）顶边 **431.0px** 都在 `section.assets` 顶边
**591.8px** 之上 ⇒ 「订单在上」在两壳都成立。

### 1.1 商家端四格文案（用户裁定 2：**与用户端逐字一致**）

| | 现在 | 原来 |
|---|---|---|
| 块标题 | 我的订单 | 待处理订单 |
| 四格 | 待付款 / 待发货 / 待收货 / 已完成 | 待发货 / 待收货 / 退款申请 / 待评价 |
| 副文案 | 下单后未完成支付 / 商家尚未发出 / 商品在途 / 确认收货的订单 | 买家已付款待处理 / 已发出在途 / 买家发起的售后 / 已完成待买家评价 |

⚠️ 这是**用户逐字指定的文案**（含副文案），不是此处自裁。连带效果两条，已在交接口头报备：
① 「商家尚未发出」在**商家自己的**个人中心里读着别扭（它是写给买家看的第二人称）；
② 原来的「退款申请 / 待评价」两格**从商家壳消失**。块右侧那枚 `disabled`「去订单中心」**保持现状**。

---

## 2 · 商家端【无名框】7 项

| # | 格 | `.v`（大字） | 交互 | 归谁 |
|---|---|---|---|---|
| 1 | 积分 | `—` | 无（无端点） | 待 C3〈商家中心〉 |
| 2 | **优惠券** | 真券数（取不到 ⇒ `—`） | 点开 `CouponWalletDialog` | **复用用户中心那套**（两处待定 ①） |
| 3 | 余额 | `—` | 无 | 待 C3〈商家中心〉 |
| 4 | 我的收藏 | `—` | 无 | 待 C3〈商家中心〉 |
| 5 | **店铺信息** | `进店` | `router.push('/shop?MerchantId=自己的')` | commit C 建路由 |
| 6 | **商家认证** | `已认证` / `审核中` / `已驳回` / `未提交`（取不到 ⇒ `—`） | 弹窗 | 读 `POST /merchant/myApplications` |
| 7 | **经营等级** | `—` | 弹窗（「敬请期待」+「功能开发中」） | 裁定 ④ 占位 |

- 三枚真入口都是 `div[role=button]` + `tabindex="0"` + `@keydown.enter/space`（与客户版那枚**逐条同款**）
  —— 实测第 2 行 3 格 `entry=true, tabindex="0"` 全中。
- 认证四态映射实测 **4/4**：`approved→已认证` / `pending→审核中` / `rejected→已驳回` / 无记录→`未提交`
  （前三个值就是 `merchant_application.Status` 的取值，只在渲染处映射成中文，**不另造一套词**）。
- 两枚弹窗共用**一枚**遮罩（`cardPanel` 决定画哪份），视觉逐条抄 `CouponWalletDialog.vue` 的
  `.cscrim` / `.cdlg`（z-index **300** 档、`rgba(0,0,0,.42)`、`min(560px,100%)`、`--r2`、
  `0 12px 48px rgba(0,0,0,.22)`）。实测：点格开、点 `×` 关、**点遮罩空白处（`@click.self`）也关**。

### 2.1 边界 2：`user.customerid` 为空时券格显示什么 ⇒ **`—` + 不可点**

- 落法：`v-if="customerId"` 走真入口（`div[role=button]`）；`v-else` 走**裸 `div`**（与客户版另外三格同形）
  + 一条 `title` 说明原因。**不弹一个空账号的券包**（那是「可点但点了没用」，比不可点更糟）。
- 实测：无客户号 ⇒ `{k:'优惠券', v:'—', entry:false, tag:'DIV', tabindex:null}`；有客户号 ⇒ `entry:true, tabindex:"0"`。
- ⚠️ **现实性（这条不是理论情形）**：VM 库（`192.168.229.129`）实测
  **14 个商家、14 个 `customerid` 为空、0 个非空**
  （`SELECT IF(customerid IS NULL OR customerid='','空','非空'), COUNT(*) FROM user WHERE usertype='商家' GROUP BY 1`
  → 空 **14**；`user` 表 `usertype` 分布 = 商家 14 / 客户 28 / 管理员 5）——
  种子商家是直接建号、没走过客户阶段；只有
  `UserGovernanceDao.promoteToMerchant`（**刻意保留客户号**）那条路来的商家才有值。
  ⇒ **手验时商家端这一格必然看到 `—`**；要看那条真入口，得用「先注册成客户、再入驻通过」的账号。

---

## 3 · §4.3 那三处「非模板代码」的落地结果（**第 1 处与方案不同**）

| 方案 §4.3 | 实况 | 处置 |
|---|---|---|
| ① `CouponWalletDialog` 的挂载点**搬出**客户分支 | ⚠️ **它本来就在两支之外** —— `<template v-else>` 早在 ⑥ 之前就闭合了（HEAD 版 `:242`），组件一直挂在 `<main>` 里、两支之后（HEAD 版 `:301-306`）⇒ 商家壳**一直** mount 得到它 | **无需搬移**。真正错的是**注释**（写着「挂在客户分支内／商家壳根本不 mount 它」）⇒ 已订正，并把连带那句「只给客户壳发券包请求」一并改掉 |
| ② `:402` 守卫改判 | 已落：`if (!isMerchantShell.value && customerId.value)` → **`if (customerId.value)`** | 另加 `if (isMerchantShell.value) loadCert()`（认证格要真状态） |
| ③ 认证弹窗的数据 | 已落：`loadCert()` 调 `merchantMyApplications({username, limit:1})` | `endpoints.merchant.myApplications` **已有** ⇒ `endpoints.js` **一行未动**；`api/merchant.js` 也**一行未动**（复用既有导出） |

**为什么第 ① 处会写反**：那三行注释是「意图」写成了「事实」。它当年想表达的是「券包只对客户壳有意义」，
而**机制**（组件位置）从来就是两支都挂 —— 于是「商家壳不发券包请求」这件事**从来不是靠组件位置实现的**，
而是靠 `:402` 那个 `!isMerchantShell.value` 守卫。本笔把守卫改掉之后，那三行注释就成了**指向错误机制的说明**，
必须一起改，否则下一个人会按它去「搬组件」。

---

## 4 · 新样式（容器滚动 + 下边缘渐隐）

```css
.assets-scroll {
  --assets-row: calc(var(--d2) + var(--s3) + var(--fs-sm) * 1.4);      /* = 36 + 12 + 21 = 69px */
  max-height: calc(var(--assets-row) + var(--s6) + var(--s5));          /* = 69 + 32 + 24 = 125px */
  overflow-y: auto; scrollbar-width: none;
  mask-image: linear-gradient(to bottom, #000 78%, transparent);
  -webkit-mask-image: linear-gradient(to bottom, #000 78%, transparent);
}
.assets-scroll::-webkit-scrollbar { display: none; }
```

| 判据 | 实测（探针） |
|---|---|
| 类**加在 `.assets` 同一元素**上（不套 wrapper） | `class="assets assets-scroll"` —— 套 wrapper 会把 `margin-top:76.8px` 卷进滚动内容、首屏顶掉第 1 行 |
| 一行高（用 token 算，不拍裸 px） | **69.0px**（客户壳 `.assets` 无 max-height 时 `clientHeight` 恰为 69 ⇒ 算式与真实行高一致） |
| 容器高**不随项数长高** | 商家 7 项 `clientHeight` **125**；窄屏 980px 塌成 2 列、**4 行**，`clientHeight` **仍是 125**（`scrollHeight` 69→372） |
| 可滚 | `scrollHeight` 170 > `clientHeight` 125 |
| 第 1 行整行可见 | 第 1 行底边 **69.0** ≤ 125 |
| 第 2 行**只露一段** | `clientHeight − row2Top` = **24.0px**（≈ 三分之一行高，与领券中心「一张 + 半张」同档） |
| 隐藏滚动条 | `scrollbar-width` 计算值 = `none` |
| 下边缘渐隐 | `mask-image` 与 `-webkit-mask-image` **都**是 `linear-gradient(rgb(0,0,0) 78%, rgba(0,0,0,0))` |
| 客户壳**三不沾** | `class="assets"`、`scrollHeight == clientHeight == 69`、`mask-image: none` |

⚠️ **「≈ 一行」的落地口径**：容器**不是**一行整，而是 **一行 + 一个行间距 + 24px**。
理由：滚动条已经隐藏，若第 2 行一个像素都不露，页面上就**没有任何**「还能往下滚」的线索
（用户定稿同时要了「隐藏滚动条」与「下边缘渐隐」，两者只有在露出一点点时才自洽）。
24px 这一档取「≈ 三分之一行高」，与「下边缘渐隐（与领券中心统一）」那句引的先例同量级。

---

## 5 · 验证

**构建**：`npm run build` → `DONE Build complete`（两次：落码后 / 定稿文案后）。

**页面探针 47 项 / 0 失败**（`docs/美化/probe-b5-b-usercenter.mjs`，按约定**不入仓**）：
覆盖 7 次页面加载 —— 商家(无客户号) / 商家(有客户号)×认证四态 / 客户壳 / 窄屏 980px，
断言分四组：几何（容器高、行分组、左对齐、可滚、渐隐、滚动条）· 边界 2 两态 ·
认证四态映射 · 弹窗三式（开 / `×` 关 / 点空白关）。

⚠️ **一条实测教训（写进探针注释）**：探针的兜底 mock 一开始只回 `{"success":true}`，
壳里某个列表组件读 `list.length` 当场抛 `TypeError`（11 条）—— 看着像本笔的缺陷。
跑了两组对照（`_work/_diag-b5b.mjs`：粗兜底 **1 条**异常 / 带 `list:[]` 兜底 **0 条**）
才定住**那是 mock 的形状问题，不是页面缺陷**。⇒ 「异常条数」这类断言必须先把**环境噪声**排干净，
否则红的是探针自己。

**截图**：`out-b5b-usercenter-{M-none,M-cert-approved,M-level,C-customer,M-narrow980}.png`（本目录）。

**互引快照**（`scripts/refs-scan.sh` before=HEAD / after=工作区）：

- **被引用的 token（目标侧）**：**零丢失**。仅有的 2 条新增，都是**本笔自己写的**先例引用
  （`ProductPage.vue:1032` / `:1330-1331`）。
- **引本页的位置**（别人引本页）：**50 个 `文件:行` 位置 / 51 条**，before/after 完全一致
  —— 本笔没碰任何引用方，那些引用所在行自然零变化。
- **本页引别人的**（citer 侧）：9 → 12 个 token —— 旧行号 **8 个**消失 / 新行号 **11 个**出现，
  是**同一批注释随行号搬家**（内容零丢失），不是引用增删。
- **本页自身行号**：全仓共 **43 个 token** 指向它 ⇒ **同 7 / 漂 36** ——
  `:1–:55` 那 7 个仍准，`:56` 之后集体漂。明细 `_work/b5b-refs-delta.md`。
  按 `agent.md` 规则 4「档案里的旧行号不追」**只登记不追**，**收口统一重指**。

---

## 6 · 手验前提（边界 3）

- ⚠️ **手验前必须登录**（别重蹈 A 组「没登录看不到」）：`getUserData()` 第一行就是
  `if (!isLoggedIn()) return` —— 不登录 ⇒ 身份卡是空的、两壳都判不出（`role` 为 `undefined` ⇒
  落**客户版**）、券数 `—`、认证 `—`。
  - **商家壳**：`user.username='2222'`（`role=2`，业务号 `merchantid=5N1k5K2guh`，即 oppo12 那家）
  - **客户壳**：`user.username='1111'`（`role=1`，业务号 `customerid=uDopHnCeLS`）
- ⚠️ **「店铺信息」格在本 commit 里点了不跳**（vue-router 控制台告警、不导航）——
  `/shop` 那条路由与 `ShopHome.vue` 按批准的方案属 **commit C**，**不是遗漏**。
- ⚠️ **手验挂 a3 profile ⇒ `app.auth.enabled=false` ⇒ 闸关** ⇒ 本页所有身份判据都是
  `IdentityGuard` 的 fail-open 一档。**手验绿 ≠ 默认 profile 绿。**

---

## 7 · 复跑方式

```bash
# 只要前端 8080 活着；后端**不参与**（探针用 _cdp.mjs 的 mock 就地伪造 8088）
cd Mall-management-web/mall-management-system && npm run serve
node docs/美化/probe-b5-b-usercenter.mjs       # 47 项断言；原始值 → _work/b5b-usercenter.json
```
