# 块 5 · B 组「commit B 手验三点」之 ③ 卡片式统一（证据）

- 日期：2026-10-12
- 范围：`Mall-management-web/mall-management-system/src/views/UserInfoPage.vue`（**1036 → 1076 行**）
  + `package.json`（+1 行）/ `package-lock.json`（+10 行）—— **新引一个运行时依赖**
- 依据：用户当轮「B 组 commit B 手验三点」的 ③「样式统一为卡片式」（①② 见 §0）
- 口径：**布局改动就是这块（不越界）** · 块内不动文档 · 验收归用户 · a3 闸关提醒照旧
- 前一份（commit B 本身）的证据：同目录 `block5-b-commitB-userinfo.md`

---

## 0 · 三点里，本轮只有第 ③ 点在动

| 点 | 用户要求 | 本轮 |
|---|---|---|
| ① 滚轮 | 「滚轮能用 ⇒ 不用改滚动逻辑」 | **一行未动** |
| ② 下边缘渐隐 | 给出逐字 CSS（`to bottom, #000 78%, transparent`） | **commit B 已落地**（本轮**CSS 值一字未改**，只改了它上面那段注释，见 §5.4） |
| ③ 卡片式统一 | 全部卡片式（含「我的订单」4 格）+ 上图标下文字 + 整块可点 | **本笔的正题**（§1–§4） |

探针复测 ② 仍在、且仍是逐字的那个值：
`mask-image` / `-webkit-mask-image` = `linear-gradient(rgb(0, 0, 0) 78%, rgba(0, 0, 0, 0))`。

---

## 1 · 图标来源：**先找库 → 报用户 → 用户裁定「照 ECharts 那条先例用 npm 引」**

用户的硬约束是「**不自己造图标**」＋「若找不到图标库就报我」。三步都做了：

1. **全仓没有下载好的图标库**：0 个 `.svg` / 0 套图标字体 / 0 个 zip；稿 HTML 里只有自托管的
   `noto.css`；`iconfont` / `fontawesome` / `unpkg` / `cdn.` / `jsdelivr` 这些关键词 **0 命中**。
2. 本仓**自有**的图标机制是 `src/components/ui/IconGlyph.vue` + `glyphs.js`：19 枚**字形**
   （Unicode 几何 / 符号字符，单色，吃 `color`），出处是稿 `docs/美化/c3-admin-preview/丁-运营蓝.html:321-333`
   的侧栏，覆盖面由 `scripts/probes/glyph-coverage.mjs`（解析 `noto.css` 的 18136 段
   `unicode-range`）把关。
3. 用**同一个算法**跑了一遍候选池（`_work/glyph-coverage-b5b.mjs`）：用户点名的那 5 枚 emoji
   **全部不在**自托管字体的覆盖内 —— 🎫 U+1F3AB · 👛 U+1F45B · 🏠 U+1F3E0 · 🏅 U+1F3C5 · 🏆 U+1F3C6
   ⇒ **缺失**（★ U+2605 / ♥ U+2665 倒是覆盖）。
   ⚠️ 这正是 `glyphs.js` 里 `cart` 那条注释记过的同一件事：**本仓的 emoji 是不可用的**。

⇒ 按指令**不自己造图标**，把「找不到库」报给用户，并列出三条路（(a) 沿用字形 / (b) 引图标库 /
(c) 手写内联 SVG）。用户先答「ECharts 之前用的这个」，再答「**A**」⇒ **照 `echarts` 那条先例，
用 npm 引一个图标库**（这是**用户裁定**，不是此处自裁）。

---

## 2 · 选了哪一套

`@lucide/vue@1.55.0` —— `package.json` 的 `dependencies` 加一行（插在字母序第一位）。

| 项 | 值（读 `node_modules/@lucide/vue/package.json`） |
|---|---|
| license | `ISC` |
| peerDependencies | `vue: ">=3.0.1"`（本仓 `vue: ^3.2.13` 满足） |
| sideEffects | `false` |
| module / main | `dist/esm/lucide-vue.mjs` / `dist/cjs/lucide-vue.js` |

- **选它是因为名字 1:1 对得上**用户点名的每一个语义（见 §3 表），不用「找一枚像购物车的」。
- 先试的 `lucide-vue-next` 被 npm 明确弃用（回文 `"Package deprecated. Please use @lucide/vue instead."`）
  ⇒ 卸载换名。
- **打包实测（tree-shaking 有效）**：11 枚图标落在 `chunk-vendors`；**未导入**的两枚
  （`accessibility` / `banana`）在产物里 **0 次命中**，导入的 11 枚各命中 1 次
  ⇒ 没有把整包上千枚塞进来。
- 这是本仓**第二个第三方运行时依赖**（第一个是可视化段为图表引的 `echarts`）。
  理由链与原裁定写在新增的那行 import 注释里；依赖登记见 `_work/block5-b-pending-registry.md` 第 15 条。

---

## 3 · 卡片解剖与 15 格

用户定稿的卡片解剖：**上半：图标；下半：文字（名称 + 可选副文案）；整块可点**。
落成**一张卡三段**：`<svg class="card-ic">` → `<b>`（名称）→ `<span>`（值 / 副文案）。

| 容器 | 格数 | 卡外套 | 谁 |
|---|---|---|---|
| `.tiles`（「我的订单」） | 两壳各 4 = **8** | `<button disabled>` | 待 C3〈订单〉的预留 |
| `.assets` 商家版【无名框】 | **7** | `.asset-card`（三枚真入口另加 `.asset-entry`） | 积分 / 优惠券 / 余额 / 我的收藏 / 店铺信息 / 商家认证 / 经营等级 |
| `.assets` 客户版【无名框】 | **4** | 同上 | 积分 / 优惠券 / 余额 / 我的收藏 |

**图标映射**（用户给的参考语义 → 库里实际命名）：

| 语义 | 库里的名字 | 语义 | 库里的名字 |
|---|---|---|---|
| 积分 | `Star` | 待付款 | `CreditCard` |
| 优惠券 | `Ticket` | 待发货 | `Package` |
| 余额 | `Wallet` | 待收货 | `Truck` |
| 我的收藏 | `Heart` | 已完成 | `CircleCheck` |
| 店铺信息 | `Store` | | |
| 商家认证 | `BadgeCheck` | | |
| 经营等级 | `Trophy` | | |

**值的语义**：原来「大数字（`.v`）+ 小标签（`.k`）」两行 ⇒ 现在名称升为主字（`b`）、
值降为副文案（`span`）。`—` 那几格**照旧是 `—`**（边界 2 的明确态没动，只是换了字号位）。

---

## 4 · CSS

```css
.assets { margin-top: calc(var(--s8) * 1.2); display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s3); }
.assets > .asset-card { padding: var(--s4); background: var(--brand-tint); border-radius: var(--r2); }
.assets b, .tiles b { display: block; font-size: var(--fs-md); font-weight: var(--fw-med);
                      line-height: 1.4; color: var(--brand-ink); }
.assets span, .tiles span { display: block; margin-top: var(--s2); font-size: var(--fs-xs);
                            line-height: 1.4; color: var(--ink-2); }
.card-ic { display: block; width: var(--fs-lg); height: var(--fs-lg); margin-bottom: var(--s3); color: var(--brand); }
.asset-entry { cursor: pointer; transition: background .15s; }
.asset-entry:hover, .asset-entry:focus-visible { background: var(--brand-soft); }
.asset-entry:focus-visible { outline: 2px solid var(--brand); outline-offset: var(--s2); }
.wallet-dot { font-style: normal; font-size: 1em; color: var(--danger); margin-left: var(--s1); }
```

三处**有意为之**：

- **`.assets .v` / `.assets .k` 两条被删**（选择器已无命中元素）；`.asset-entry:hover .k` 那两条
  （原为「可点」的颜色线索）随之换成整卡 `background: var(--brand-soft)` —— 卡片有底色之后，
  hover 该整块变色，不是只变一行字。
- **`gap` 由 `--s6`（32px）收成 `--s3`（12px）**，与 `.tiles` 同缝宽：**有底色之后** 32px 的缝
  会把一行拆成四张孤立的卡（无底色的旧版不存在这个问题）。
- **两条 `line-height: 1.4` 必须显式写**：文件尾 `.assets-scroll` 的容器高是拿这批 token 算出来的。
- 图标色相取 `--brand`（`tokens.css:102` 那行自注「**填充**：按钮底、**图标**」），文字才用 `--brand-ink`。

### 4.1 容器高跟着卡片走（`.assets-scroll`）

```css
.assets-scroll {
  --assets-row: calc(var(--s4) * 2 + var(--fs-lg) + var(--s3) + var(--fs-md) * 1.4 + var(--s2) + var(--fs-xs) * 1.4);
  max-height: calc(var(--assets-row) + var(--s3) + var(--s5));
  overflow-y: auto; scrollbar-width: none;
  mask-image: linear-gradient(to bottom, #000 78%, transparent);
  -webkit-mask-image: linear-gradient(to bottom, #000 78%, transparent);
}
```

### 4.2 判据表（探针实测，1440×900）

| 判据 | 实测 |
|---|---|
| 一张卡的高（算式）= `32 + 24 + 12 + 26.6 + 8 + 16.8` | **119.4px** |
| 容器高 = 一卡 + 一格间距 + 24 | **155px**（`clientHeight 155`） |
| 第 2 行顶边 | **131**（= 119.4 + 12），露出 **24px** |
| 「我的订单」四格的高 | **119.4px**（与【无名框】的卡**逐值同高**） |
| 两处格子共用同一张卡壳 | bg `rgb(238,252,246)`（= `--brand-tint` #eefcf6）· radius `8px`（`--r2`）· padding `16px`（`--s4`）—— **三项逐值相等** |
| 图标 | **24×24**（= `--fs-lg`）、色 `rgb(59,121,102)`（= `--brand` #3b7966） |
| 图标位置 | 每格**第一个子元素**，且**底边 ≤ 名称顶边**（上半图标 / 下半文字） |
| 客户壳（4 项） | `clientH == scrollH == 119` ⇒ **不滚**，`mask: none` ⇒ **不渐隐** |
| 窄屏 980px（2 列 ⇒ 4 行） | 容器**仍是 155**（不随行数长高），`scrollH 514`，渐隐仍在 |

---

## 5 · 验证

1. **构建**：`npm run build` → `DONE Build complete`（本轮 3 次：改 CSS 后 / 改注释后 / 改选择器后）。
2. **页面探针 58 项 / 0 失败**（`docs/美化/probe-b5-b-usercenter.mjs`，按约定**不入仓**；
   原始值 `_work/b5b-usercenter-cards.json`）。比 commit B 那份多了 11 项，全是 ③ 的断言：
   每格有图标 / 图标在下单上方 / 图标尺寸与色相 / 卡片高等于三段算式 / **两处格子三项 computed 逐值相等** /
   四格各带图标 / 四格是 `<button>`（整块命中区）/ 四格与【无名框】同高 / 客户壳也统一 / 窄屏 7 格 7 图标。
   **连跑两遍都是 58/0**。
3. **互引**（`scripts/refs-scan.sh`，before = `git stash` 回 HEAD / after = 工作区；明细 `_work/b5b-cards-refs-delta.md`）：
   - 全量 token：两遍都有 2500，**只 before 0**，只 after **2** —— 两条新增都是**本笔自己写的先例引用**
     （`_work/vis-dashboard-landing-plan.md:51`、`tokens.css:102`），写在新增的 import 注释里。
   - 目标侧 `UserInfoPage.vue:NN` 显式 token：**31 → 31，集合零增删**。
   - 引本页的位置：**50 → 50，两遍完全一致**（本笔没碰任何引用方文件）。
   - ⚠️ 上次那份 delta 记的「43 个 token / 同 7 / 漂 36」含 12 条**手工归属的裸续引**，本次未逐条重算
     ⇒ 不作沿用声明；逐 token 重算仍按原裁定留到**块 5 收口**一次做（本笔只登记：行数 1036 → 1076）。
4. **截图**（本目录，全部按卡片版重拍，另加一张滚到底的）：
   `out-b5b-usercenter-M-none.png` · `-M-none-scrolled.png`（**新增**：第 2 行三枚真入口的图标与副文案）
   · `-M-cert-approved.png` · `-M-level.png` · `-C-customer.png` · `-M-narrow980.png`。

---

## 6 · 已报备的代价与两处刻意保留

1. **这页从此有两套图标语言**：11 张卡上是 Lucide 线图标，同页两枚「保存」按钮仍是
   `IconGlyph` 的**字形** ✓（`UserInfoPage.vue:361` / `:389` —— 实测行号）。这是这次裁定的**已知代价**，
   已在新增的 import 注释里写明；要统一得另立一笔。
2. **「我的订单」四格仍是 `disabled`**：用户 ③ 只点名「加图标」与「整块可点」两项。
   「整块可点」在这四格是**结构**属性（`<button>` 整块就是命中区，不是只有文字），
   但它们**没有端点**（待 C3〈订单〉）⇒ 保持 `disabled` + `title`。
   ⚠️ **没有把它们打开** —— 打开会变成「能点但点了没用」，比不可点更糟（同 commit B 边界 2 的口径）。
3. **滚到底时最后一行仍被渐隐**：`mask-image` 是**静态**的，不区分「还有没有内容可滚」。
   这是用户给的 CSS 的固有形态（先例 `ProductPage.vue:1032` 同源），
   **本轮不擅自加 JS 判断**，留作手验时一起看。
4. **② 那条渐隐的 CSS 值一字未改**：只改了它上面那段注释（把算式从「大字 `--d2` + 小标签」
   改写成「跟卡片解剖同源」）—— 探针复测值仍是 `78%` 那一条。

---

## 7 · 手验前提

- ⚠️ **必须登录**（不登录两壳都判不出、落客户版）：商家壳 `user.username='2222'`
  （`merchantid=5N1k5K2guh`，oppo12）· 客户壳 `user.username='1111'`（`customerid=uDopHnCeLS`）。
- ⚠️ **手验挂 a3 profile ⇒ `app.auth.enabled=false` ⇒ 闸关** ⇒ 本页身份判据全是 `IdentityGuard`
  的 fail-open 一档。**手验绿 ≠ 默认 profile 绿。**
- ⚠️ 「店铺信息」格在本 commit 里点了**仍不跳**（`/shop` 那条路由属 **commit C**，不是遗漏）。
- ⚠️ 商家壳【无名框】的「优惠券」格，VM 库里 **14/14 商家的 `customerid` 都为空** ⇒ **必然看到 `—` 且不可点**

## 8 · 复跑方式

```bash
# 只要前端 8080 活着；后端不参与（探针用 _cdp.mjs 的 mock 就地伪造 8088）
cd Mall-management-web/mall-management-system && npm run serve
node docs/美化/probe-b5-b-usercenter.mjs       # 58 项断言；原始值 → _work/b5b-usercenter-cards.json
```
