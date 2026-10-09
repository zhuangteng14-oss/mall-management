# 证据索引 —— 「消息 / 通知 UI 入口合并」笔（2026-10-09 晚）

**登记**：`docs/agent/slot-registry.md` **§十六**（同笔；§十五 15-3 已加按语）。
**探针**：`scripts/probes/probe-msg-center.mjs`（可复跑）。
**跑法**：`node scripts/probes/probe-msg-center.mjs` —— **只需 dev server 8080**（vue-cli），
后端**全部就地 mock**（`Fetch.enable` 拦 `*8088*`，见 `docs/美化/_cdp.mjs`）⇒ **真后端不用起**。

## 本目录内容

| 文件 | 是什么 |
|---|---|
| `probe.out` | 探针**完整 stdout**（17 行断言 + 汇总行；末行 = `17 项 / 0 失败`） |
| `result.json` | 机器可读汇总：`{ok, total:17, failed:0, modes}` —— `modes` 是红点那四组 mock 的**未读数**，逐组对应一条断言 |
| `shots/01-customer-chat-tab.png` | 客户壳 · `/customer/msg` · **会话** tab（默认落点） |
| `shots/02-customer-notify-tab.png` | 客户壳 · 点「通知」tab 后（地址变 `…/msg?tab=notify`） |
| `shots/03-customer-notify-deeplink.png` | 客户壳 · **深链** `#/customer/notify` 改道后的落点 |
| `shots/04-merchant-notify-tab.png` | 商家壳 · 深链 `#/merchant/notify` 改道后的落点 |

⚠️ **02 与 03 逐字节相同（md5 均为 `d413d3ed…`）—— 这是对的，不是拍重复了**：两者都是
「停在通知 tab」，mock 数据也相同 ⇒ 渲染结果本就该一样。两图分别证明的是**两条不同的入口**
（页内点 tab ⇄ 旧深链改道）**落到同一个视图** —— 图相同正是「改道成功」的证据。
（同款判据在 `progress.md` 记过：匿名两壳顶栏逐字节相同也是「正确行为、不是坏图」。）

## 图 ↔ 验收项 对照（用户给的 7 条）

| 验收项 | 证据 |
|---|---|
| ① 顶栏只剩一个「消息」入口 | `probe.out` 断言 ①（客户）/ ⑨（商家）；图 `01`（顶栏右侧三项、无「通知」） |
| ② 点进去能看到两个 tab | 断言 ② / ⑩；图 `01`（**会话** 亮）与 `02`（**通知** 亮） |
| ③ 会话 tab 能聊天（原有功能不破） | 断言 ③（`.chat-room` 渲染 + 会话条）；图 `01` |
| ④ 通知 tab 能看通知（原有功能不破） | 断言 ④ / ④b（mock 的 2 条都在）；图 `02`/`03`/`04` |
| ⑤ 红点 = 未读会话 + 未读通知 | 断言 ⑤a + **⑤b ×3**（穷举 `notifyOnly` / `convOnly` / `none`，见 `result.json.modes`） |
| ⑥ `/customer/notify` 深链 → 落通知 tab | 断言 ⑥ / ⑥b；图 `03` |
| ⑦ `/customer/user/chat` 不受影响 | 断言 ⑦ |

另：断言 ⑧ / ⑫ = 两壳**零 JS 异常**。

## ⚠️ 这个探针**不验**什么（别把绿当全绿）

- **不验真后端**：所有 `/message/*`、`/notification/*` 都是就地伪造的 ⇒ 后端两张表、八个端点、
  审计钩子**本笔没碰、也没在这里验**（见 §十六 16-4 第 2 条）。
- **不验「进页清红点」**：那件事本就**会被下一发 5s 轮询盖回来**（后端没把会话标已读）⇒ 断言它必然是
  **竞态**的；不在用户验收清单里，故不写。
- **不验顶栏「消息」项的高亮**：停在通知 tab 时顶栏仍亮「消息」，这是**刻意的**（两个 tab 同属一个入口）。
