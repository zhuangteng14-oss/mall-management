# 块 5 · B 组 · 个人中心两壳【无名框】「甲案：上亮下素」（证据）

- 日期：2026-10-12
- 范围：`Mall-management-web/mall-management-system/src/views/UserInfoPage.vue` **一个文件**
  （**1118 行**，本笔之前是 **1076**）+ 4 张改前图 / 5 张改后图
- 依据：用户当轮口头裁定「**甲案**」+ 两个单选答复（**图标保留 `@lucide/vue`** / 下半改**横向行卡**）
- 口径：**只改下半视觉结构** · 不动已验收结构 · 去 AI 化（`frontend-design` SKILL.md）· 块内不动文档 · 验收归用户
- 上一份（③ 卡片式统一）：同目录 `block5-b-commitB-cards.md`

---

## 0 · 一句话

上半「我的订单」四格**一行未动**（探针逐项 computed 实测未变）；
下半【无名框】（商家 7 / 客户 4 格）由**浅绿底竖卡**改成 **白底 + 1px 发丝线 + `--r1` 的横向行卡**，
名称字阶降一档（`--fs-md`→`--fs-sm`，对齐同页「服务」块），卡高 **119.4 → 79.8px**。
⇒ 上下**五处**不同（底色 / 有无边框 / 圆角 / 字阶 / 版式），「上亮下素」靠**结构**分家，不靠颜色。

---

## 1 · 两遍流程（`frontend-design` 的 SKILL.md）

⚠️ `Skill(frontend-design:frontend-design)` 本会话**仍报 `Unknown skill`**（插件在建会话时一次性载入）
⇒ 按批准方案 §0 的兜底：**读盘上那份 SKILL.md 并逐条照做**，收口登记「插件已启用，下个会话起走真 Skill」。

### 第一遍 · 草案（色 / 字 / 版面）

| 维 | 取 | 为什么是它 |
|---|---|---|
| 卡面 | `--surface`（白） | **不用 `--surface-2`** —— 那是 `.pcard:hover` 的悬停底（`:926`），当静态卡面会撞语义 |
| 边 | `--line`（`#dde2e5`） | 最轻那一档；`tokens.css:91` 自注「发丝线（替代阴影的主力）」。**不用 `--line-2`** —— 那是按钮/输入框那档（`.btn-plain:1003` / `.f input:988`），下半要比它们更轻 |
| 圆角 | `--r1`（4px） | 与页面成**阶梯**：身份卡 `--r3`(10) > 上半 `--r2`(8) > 下半 `--r1`(4) |
| 图标 | `--brand`（`#3b7966`） | 下半**唯一的绿**；依据 `tokens.css:100-101`「`--brand` 用于按钮底、图标」 |
| 名称 | `--fs-sm` / `--fw-med` / `--ink` | 与同页 `.svc`（服务块 `:979`）**逐值相同** |
| 值 | `--fs-xs` / `--ink-3` | 与 `.svc span`（`:981`）逐值相同（只差 `line-height`：那里 1.6、这里 1.4） |
| 版面 | 两列网格 `auto \| minmax(0,1fr)`，图标 `grid-row: 1 / span 2` | **零 DOM 改动**拿到「图标左 / 名称与值两行右」 |

### 第二遍 · 对着「默认样」自评（SKILL 点名的 AI tells）

| AI tell | 命中？ | 处置 |
|---|---|---|
| 每张卡同圆角 + 同一个 `rgba(0,0,0,.1)` 阴影 | **没有** | 一条 `box-shadow` 都没有 —— `tokens.css:35-36` **成文禁**：「禁 · 阴影铺满（改用 1px 发丝线 `--line`）」+「禁 · 统一大圆角（只用 `--r1/--r2/--r3`）」。圆角成 `r3>r2>r1` 阶梯 |
| 标题上挂全大写 eyebrow | **不适用** | 下半**无块标题**（用户定稿「无名框」），没挂任何 eyebrow |
| `·` 拼接的元信息串 | **没有** | 全页 0 处，本笔也没加 |
| `→` 缀在按钮/链接文案后 | **没有** | 不加。可点的格靠 `cursor:pointer` + hover 换边色 + `title` |
| 每块 fade-and-slide-up 入场 | **没有** | 不加任何入场动画（只有既有的 `transition: border-color .15s`） |

**据此否掉的自己的三处草案**：

1. hover 原本想用 `--brand-soft` 底 —— 那是**把亮又拿回来**，与「更素」相反 ⇒ 改成**只染边色**。
2. 想给可点的格加一枚 `›` —— 正撞 tell 家族；且**上半四格是 `disabled`**，加箭头会让人以为上面也能点 ⇒ 不加。
3. 圆角本想一把 `--r2` —— 那就是 tell 第 1 条的原话 ⇒ 用 `--r1` 成阶梯。

### 第四步 · 截图自评（「和旁边几块像一套吗」）

新增 `out-b5b-halfcard-lower.png`（滚到 ⑥ 账号设置那一段）。判据三点：

- 下半的名称/值字阶 == ⑤「服务」块的标题/副行（`--fs-sm`/`--ink` + `--fs-xs`/`--ink-3`）—— **同一档**。
- 下半的「白底 + 1px 描边」== ⑥ 账号设置输入框 `.f input`（`:988`：`background: var(--surface)` +
  `border: 1px solid var(--line-2)` + `transition: border-color`）—— **同一族**，本笔只是取了更轻的
  `--line`、更小的 `--r1`（输入框是要打字的主控件，下半是次级入口）。
- 与身份卡 / 最近上架 / 服务 / 账号设置 **无一处冲突**：那些块一块未动。

---

## 2 · CSS（改动全貌）

```css
.assets { margin-top: calc(var(--s8) * 1.2); display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s3); }
.assets > .asset-card {
  display: grid; grid-template-columns: auto minmax(0, 1fr); column-gap: var(--s3);
  padding: var(--s4); background: var(--surface);
  border: 1px solid var(--line); border-radius: var(--r1);
}
.tiles b { … font-size: var(--fs-md); … color: var(--brand-ink); }     /* 上半：保持 ③ 那版 */
.tiles span { … color: var(--ink-2); }
.assets b { display: block; font-size: var(--fs-sm); font-weight: var(--fw-med);
            line-height: 1.4; color: var(--ink); }                      /* 下半：降一档 */
.assets span { display: block; margin-top: var(--s2); font-size: var(--fs-xs);
               line-height: 1.4; color: var(--ink-3); }
.assets .card-ic { grid-row: 1 / span 2; align-self: center; margin-bottom: 0; }   /* 图标跨两行居中 */
.asset-entry { cursor: pointer; transition: border-color .15s; }
.asset-entry:hover, .asset-entry:focus-visible { border-color: var(--brand); }    /* 换边色，不换底色 */
```

容器高跟着卡片走（`.assets-scroll`）：

```css
--assets-row: calc(var(--s4) * 2 + var(--fs-sm) * 1.4 + var(--s2) + var(--fs-xs) * 1.4 + 2px);  /* 79.8 */
max-height: calc(var(--assets-row) + var(--s3) + var(--s5));                                    /* 115.8 */
```

⚠️ 算式里**没有 `--fs-lg`（图标）那一项**：图标 24px 比文字块 45.8px 矮，卡高由文字块定。
注释里已写明「若日后把 `--fs-lg` 调到 45.8 以上，这一项要加回来」。

### 2.1 判据表（探针实测，1440×900）

| 判据 | 上半 `.tiles`（未动） | 下半 `.assets`（本笔） |
|---|---|---|
| `background-color` | `rgb(238, 252, 246)`（`--brand-tint`） | **`rgb(255, 255, 255)`（`--surface`）** |
| 边框 | **`0px none`**（无） | **`1px solid rgb(221, 226, 229)`（`--line`）** |
| `border-radius` | `8px`（`--r2`） | **`4px`（`--r1`）** |
| `padding` | `16px`（`--s4`） | `16px`（**唯一相同的一项**） |
| `box-shadow` | `none` | `none` |
| 卡高 | `119.4px` | **`79.8px`**（差 **39.6px**） |
| 名称 | `19px` / `rgb(39, 94, 78)`（`--brand-ink`） | **`15px` / `rgb(27, 30, 32)`（`--ink`）** |
| 值 | `12px` / `rgb(76, 81, 85)`（`--ink-2`） | **`12px` / `rgb(99, 105, 109)`（`--ink-3`）** |
| 图标位置 | 在名称**上方**（4/4） | 在名称**左侧** + 两行里**垂直居中**（7/7、C 壳 4/4） |
| 图标 | `24×24` / `rgb(59, 121, 102)`（`--brand`） | 同左（两壳一致） |
| 容器高 | （不适用） | `clientH 116`（算式 115.8）；第 2 行顶边 `92`，**露出 24px** |
| 客户壳 | `119.4px` | `79.8px`，`scrollH == clientH == 80` ⇒ 不滚不渐隐 |
| 窄屏 980px | — | 2 列 4 行，容器**仍是 116**，横向版式与 7 枚图标**一个不少** |

---

## 3 · 三条**有意为之**与一条**偏离报备**

1. **删掉了一对重复声明**：`.tiles b` / `.tiles span` 在 ③ 那笔之后**存在两份**
   （「卡片式统一」段里一份 + `.tiles` 自己段里一份），只差 `line-height: 1.4`。本笔删掉 `.tiles`
   自己段里那一对 —— **计算值零变化**，有探针实测为证（卡高 `119.4` / `bg rgb(238,252,246)` /
   `radius 8px` / 名称 `19px` / 值 `rgb(76,81,85)` / 图标仍在名称上方，全中）。
2. **hover 从「换底色」改成「换边色」—— 这是对 `tokens.css:37` 那句「（hover 用底色变化）」的
   *字面*偏离**，已在代码注释里写明理由：那句的主语是「禁 · **侧边强调条**」，禁的是拿左侧色条
   当悬停指示；而**本页自己的两处描边元素**（`.btn-plain:hover:1004`、`.f input:focus:992`）
   hover 换的都是边色 ⇒ 照页面自己的话讲。**若判定要回到底色**，改回
   `.asset-entry:hover { background: var(--brand-soft) }` 一行即可（但下半会与上半的 hover 变同款）。
3. **顺手修了一条引错的行号引用**（**不是本笔引入的**）：`tokens.css:102` 被引作
   「**填充**：按钮底、图标」的出处，但 `:102` 是裸的 `--brand: #3b7966;` 赋值行，而那句话在
   **`:271`**、属 `html[data-theme="admin"]` 的**暗色块**（值也不同，`#2f6fed` 蓝）
   ⇒ 已重指到亮色块那条 **`tokens.css:100-101`**，并在注释里留了说明。
   ⚠️ 探针/扫描器仍会在本页看到一枚裸 `:102`（那是注释里**引用那句错话**用的），不是活引用。

---

## 4 · 验证

1. **构建**：`npm run build` → `DONE Build complete`（本笔 2 次：落码后 / 重指行号后）。
2. **页面探针 70 项 / 0 失败**（`docs/美化/probe-b5-b-usercenter.mjs`，按约定**不入仓**；
   原始值 `_work/b5b-usercenter-cards.json`）。比 ③ 那笔的 58 项多 12 项，且**把 4 条翻转了**：
   - 「两处共用同一张卡壳（bg/radius/padding 逐值相等）」⇒ **「上下半卡壳分家」**（bg 与 radius 必须**不同**）
   - 「四格与无名框的卡片**同高**」⇒ **「上半比下半高一档」**（差 > 30px，实测 39.6）
   - 「图标画在名称**上方**」⇒ 上半查「仍在上方」、下半查**「不再在上方」+「在名称左侧」+「垂直居中」**
   - 新增：边框有无 · `box-shadow` 两边都是 `none` · 上下半字阶逐值 · 容器高算式 ·
     **hover 换边色不换底色**（用 CDP `CSS.forcePseudoState` 强制 `:hover` 实测）· 窄屏容器高与横向版式
   **连跑两遍都是 70/0**（第二遍在注释重指之后）。
3. **互引**（`scripts/refs-scan.sh`；before = `git checkout HEAD -- <本页>` 复现的 1076 行工作区态，
   after = 1118 行工作区；产物 `_work/b5b-halfcard-refs-{before,after}.txt`）：
   - 全量 token 去重后：**都有 2505 / 只 before **0** / 只 after 3** —— 零丢失；3 条新增**全是本笔写的**
     `tokens.css:35-36` / `tokens.css:91` / `tokens.css:100-101`，逐条已核到真身。
   - 引本页的位置（`(引用所在文件, 行)` 去重）：**51 → 51，两遍完全一致**（本笔没碰任何引用方文件）。
   - 目标侧 `UserInfoPage.vue:NN` 显式 token：46 → 50 —— **不是引用增删**，是本页内部的引用
     随着行号上移而换了位置（每条都在本页自己身上）。逐 token 的「同 / 漂」重算按原裁定
     **留到块 5 收口**一次做（本笔只登记：**1076 → 1118 行**）。
4. **截图**：改前 / 改后**成对**在证据目录 —— `out-b5b-halfcard-before-*.png` / `-after-*.png`
   （×4：`M-none` 商家 7 项 / `M-none-scrolled` 滚到底 / `C-customer` 客户 4 项 / `M-narrow980` 窄屏）；
   另加一张自评用的 `out-b5b-halfcard-lower.png`（滚到 ⑥ 账号设置，看「像不像一套」）。
   `out-b5b-usercenter-*.png`（探针自己产的那套）== `-after-*` 那 4 张（md5 已对）。
   ⚠️ 确认过：改前那份 `-before-M-none.png` 与 HEAD（`208c0de`）时刻的产物**逐字节相同**
   （`_work/shots/before-M-none.png` 与 `out-b5b-usercenter-M-none.png` md5 都是 `bf0589c1…`）。

---

## 5 · 手验前提与已知项

- ⚠️ **必须登录**（不登录两壳都判不出、落客户版）：商家壳 `user.username='2222'`
  （`merchantid=5N1k5K2guh`，oppo12）· 客户壳 `user.username='1111'`（`customerid=uDopHnCeLS`）。
- ⚠️ 本段挂 **a3 profile ⇒ `app.auth.enabled=false` ⇒ 闸关** ⇒ 本页身份判据全是 `IdentityGuard`
  的 fail-open 一档。**手验绿 ≠ 默认 profile 绿。**
- ⚠️ 商家壳【无名框】的「优惠券」格，VM 库里 **14/14 商家的 `customerid` 都为空**
  ⇒ **必然看到 `—` 且不可点**；真入口要在「先注册成客户、再入驻通过」的账号上才看得到。
- ⚠️ 「店铺信息」格点了**仍不跳**（`/shop` 那条路由属 **commit C**，不是遗漏）。
- ⚠️ **滚到底时最后一行仍被渐隐**、以及**滚到底时第 1 行顶部被截**：都是 ③ 就报过的
  `mask-image` 静态形态（用户给的 CSS 的固有形态），本轮**只换了卡片**、`.assets-scroll` 那一块
  除算式之外**一字未改**（`mask` 仍是逐字的 `linear-gradient(rgb(0,0,0) 78%, rgba(0,0,0,0))`）。
  改前那张 `-before-M-none-scrolled.png` 同样能看到第 1 行的窄条 —— **不是本笔引入的**。
- ⚠️ 本页**两套图标语言并存**：11 张卡是 Lucide 线图标，同页两枚「保存」按钮仍是 `IconGlyph` 字形
  —— ③ 那笔就报过的已知代价，本轮**未动**。

---

## 6 · 复跑方式

```bash
# 只要前端 8080 活着；后端**不参与**（探针用 _cdp.mjs 的 mock 就地伪造 8088）
cd Mall-management-web/mall-management-system && npm run serve
node docs/美化/probe-b5-b-usercenter.mjs       # 70 项断言；原始值 → _work/b5b-usercenter-cards.json
node _work/_b5b-halfcard-lower.mjs             # 自评图：滚到 ⑥ 账号设置
```
