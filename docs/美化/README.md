# 界面优化笔 · 设计稿

> ⚠️ **先读这段再动手。这些 HTML 不是可直接移植的代码，是设计稿。**

## 这是什么

`界面优化笔`（`docs/agent/progress.md` 多处以「不动界面样式（界面优化笔）」为前置的那一笔）的
**设计稿与效果对照**。产物形态 = **参考方向（高保真）**：

- ✅ 有真实 DOM 结构、真实交互（标签切页 / 预留位开关 / 配色切换）
- ✅ 有完整的设计 token（在 `主页改造.html` 的 `:root` 里，40+ 个变量）
- ❌ **不是 Vue 组件** —— 没有 props / slots，不能 `import`
- ❌ **类名与项目不一致** —— 设计稿用 `.p-band` / `.ords` / `.msgr` / `.frt`；
  项目用 `.card` / `.order-card` / `.friend-card`。直接粘进组件编译不过
- ❌ **不是独立页面** —— 设计稿自带顶栏、左菜单、页脚；项目是 `AppShell` 壳 + 子路由，
  照搬会带上重复的壳

## 文件清单

| 文件 | 内容 |
|---|---|
| `主页改造.html` | 商城主页。`:root` 里是全套设计 token（**落地时的源头**） |
| `子页面改造.html` | 用户中心四页（个人信息 / 历史订单 / 消息 / 好友）+ 好友空态。顶部标签切页；「显示预留位」按钮标出未来的功能位 |
| `色系.html` | 配色推导过程（OKLCH 取值、ΔE00 台阶、对比度校验） |
| `主页.png` / `个人页.png` / `消息页.png` | **改造前**的原版截图（对照用） |
| `shots/` | **改造后**的截图。`s-*` = 各页常态，`slot-*` = 打开预留位，`s-uc-1080` = 窄屏响应式 |

## 设计 token 在哪

`主页改造.html` 的 `<style>` 顶部 `:root { ... }`。落地第一步就是把它抽成
`Mall-management-web/mall-management-system/src/assets/tokens.css`。

⚠️ 项目**目前零 token 层**：`App.vue` 只有一条 `*{margin:0;padding:0;box-sizing:border-box}` reset，
没有 `src/assets/`，15 个 view 全用 `<style scoped>` + 硬编码颜色
（`#007bff`×17 / `#00bcd4`×8 / `#028fa8`×7 / `#ff4d4f`×5 + 一串灰）。

> ⚠️【界面优化笔 A 笔订正 2026-10-06】上面这段有 2 处过时，实测如下（原文保留不改）：
> （1）`#00bcd4` 实测 **7** 次，不是 8（其余三色 17 / 7 / 5 复核无误）；
> （2）「15 个 view 全用 `<style scoped>`」不成立：`views/` 下共 **16** 个 `.vue`
> （含子目录 `views/merchant/OrderPane.vue`），带 `<style>` 的 **14** 个、其中 `<style scoped>` 的 **13** 个
> —— `HomeTab.vue` 与 `merchant/OrderPane.vue` 无 `<style>`，`LoginPage.vue` 有 `<style>` 但不是 scoped。
> 另：`src/assets/` 已存在（见下节按语），`App.vue` 的那条全局 reset 已收敛进 `tokens.css`。

可行性关键：**`<style scoped>` 里照样能读全局 CSS 变量**（自定义属性会继承，scoped 只加属性
选择器、不隔离变量）。所以 token 放全局、组件样式照旧 scoped，两者不冲突。

## 落地顺序

1. **铺 token** —— 新建 `src/assets/tokens.css` + `main.js` 加一行 import。
   纯新增，没有任何组件在用它 ⇒ **零视觉回归风险**。验证 `npm run build`。
   > ⚠️【A 笔订正 2026-10-06】**本节已完成**，两处与本节预判不同：
   > （1）`main.js` 的 import 是**同行追加**到第 2 行（不是新增一行）—— 为保住 `main.js` 被全仓引用的
   > `:21`/`:28`/`:49` 行号（沿用该文件 `:27` 的既有做法）；
   > （2）另建了 `src/assets/fonts/`（`noto.css` + 202 个 woff2，黑体/宋体两族自托管，离线可用），
   > 并把 `docs/美化/*.html` 那两条指向 `./node_modules/` 的字体 `<link>` 改指过来（原链路本机实测 404）。
2. **逐页翻译**（一页一个 commit）——
   `AppShell.vue`（顶栏，全站共用）→ `HomePage.vue` → `UserCenter.vue`（左菜单）→
   `UserInfoPage.vue` → `HistoricalOrders.vue` → `FriendRoom.vue` → `ChatRoom.vue`。
   每页把设计稿的类名换成项目已有的，或直接替换整个 `<style scoped>`。
3. **视觉回归** —— `npm run serve` 后用 `shots/` 里的图对照。

## 落地前必须先裁的两件事

1. **顶栏搜索框** —— 设计稿把搜索框提到了壳顶栏（四个子页顶栏因此逐字一致）。
   代价：`HomePage.vue` 的 `.search-section` 要删掉，否则主页出现两个搜索框。
2. **账户中心的新模块没有后端** —— `UserInfoPage` 设计稿里的
   **资产总览**（积分 / 优惠券 / 收藏 / 浏览足迹）和 **账号与安全**（手机号 / 密码 / 实名认证）
   在 `src/api/endpoints.js` 里查不到对应端点。三条路：
   (a) 先只搬有依据的部分（身份条 + 表单），那两块等后端；
   (b) 搬视觉、数据写死（答辩时是假的）；
   (c) 先走后端（按 `agent.md` 追加式 DDL + 新端点）再同步。

   依据分级：`积分` / `手机号` / `登录密码` 有依据（ER 会员实体有「会员积分」、
   用户实体有「手机号」，`login` 表有密码）；`优惠券` / `收藏` / `浏览足迹` / `实名认证`
   是**提议**，尚未落任何文档。

   同理 `主页改造.html` 里的 **秒杀位**（`selection-status.md §9` 已列为「秒杀演示」【口述】，
   依赖 B1+B2，且前提「`goods` 无库存列」当前不存在）与 **券包位**（`§9` 里没有，纯提议）。

## 生成工具在哪

截图 / 配色推导的脚本**不在本目录**（它们是工具不是文档，且依赖 `node_modules`）。
在 `桌面/软件工程/美化/`：`shot.mjs`（用本机 Edge 截图）、`palette.mjs`、`system.mjs`。

```bash
cd 桌面/软件工程/美化
node shot.mjs --url "../期末项目/docs/美化/子页面改造.html" --out t.png --w 1440 --click "button[data-page=uc]"
```
