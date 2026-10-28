# 前置项修复验收报告（ALLOWED 白名单 + 商家工作台落点）

- 日期：2026-10-24
- 环境：离线 60 条 × 2 模型 × 3 次；Prompt 仍为 `_work/chat-baseline/prompt-merchant.txt`（未改）
- 约束：**未切线上模型**、未改超时 / 语义阈值 / Prompt / 前端、未 push
- 原始数据：`ab-results-60.csv`、`ab-results-60.json`、`ab-summary.json`、`test-set-60.csv`（同目录）

## 一、改了什么

### 1. `ALLOWED` 白名单缺口

| 文件 | 改动 |
|---|---|
| `LlmIntentClassifier.java` | `ALLOWED` 加入 `GREETING`、`HELP`；注释说明「Prompt 已列出、解析器必须同步」 |
| `LlmIntentClassifierTest.java` | 新增 `acceptsSmallTalkIntents`：GREETING / HELP 均能解析成功 |
| `probe-chat-air-ab.py` | 测量侧的 `ALLOWED_INTENTS` 同步加入 `GREETING`、`HELP` |
| `ChatAgentService.java` | 只改过期注释：Prompt 未列的只剩 `IDENTITY_CLAIM` |

> 测量侧也必须同步，否则修了生产、探针仍按旧白名单把这两类记成「解析失败」。两侧同改不是放水，单测锁的是生产 Java 行为。

### 2. 「商家工作台」落点

裁决：**方案 A = `/merchant/user`**（与 `RouteCatalog`、`UserInfoPage` 的「今日工作台」一致）。

| 文件 | 改动 |
|---|---|
| `SemanticRouteRegistry.java:47` | `MERCHANT_DASHBOARD` 目标 `/merchant` → `/merchant/user` |
| `ChatIntent.java:52-53` | 注释改为「落点是商家个人中心（工作台），而不是公域商城主页」 |
| `golden-set-50.csv` / `.md` | G05–G08 期望落点 → `/merchant/user` |
| `probe-chat-air-ab.py` | C02、B03 期望落点 → `/merchant/user`（这两条不在 golden CSV 内） |
| `SemanticRouterTest.java:33` | 断言 → `/merchant/user` |
| `ChatAgentServiceTest.java:1284/1289` | mock 与断言 → `/merchant/user` |
| `probe-chat-semantic-router.mjs` | 端到端期望 → `/merchant/user` |

未动：`RouteCatalog`、Prompt、前端。

## 二、验收数据

### 2.1 完整生产解析（attempt 口径，60 条 × 3 次 = 180）

| 模型 | 通过 | 率 | 失败项 |
|---|---:|---:|---|
| Air | **180/180** | **100.00%** | 无 |
| Flash | **177/180** | **98.33%** | 仅 G50「帮我写一首诗」3/3 无 JSON |

与验收值一致：Air 180/180、Flash 177/180。`json_valid` 同为 180/180、177/180。

### 2.2 G47 / G49 / C04 不再被白名单降级

| 条目 | 问题 | Flash 原始意图 ×3 | Air 原始意图 ×3 | 生产解析 ×3 |
|---|---|---|---|---|
| G47 | 你好 | GREETING | GREETING | 均 true |
| G49 | 今天天气不错 | FAQ | GREETING | 均 true |
| C04 | 今天天气不错 | FAQ | GREETING | 均 true |

结论：三条都**不再因 `ALLOWED` 被降级**。

⚠️ 语义残留：G49/C04 的 Air 原始意图仍是 `GREETING`（Flash 为 `FAQ`），只是不再被解析器拒绝。默认开启语义路由时，「今天天气不错」在 `SemanticRouter` 处以相似度 < 0.75 走 `CLARIFY`，不会到达这里；只有 `CHAT_EMBEDDING_ENABLED=false`（纯 LLM 路径）时才会看到 GREETING 话术。本次未改这条语义，留作后续观察项。

### 2.3 六条「商家工作台 / 后台」重跑

| ID | 问题 | Flash 落点 ×3 | Air 落点 ×3 | 期望 | 判定 |
|---|---|---|---|---|---|
| G05 | 怎么去商家工作台 | `/merchant/order` | `/merchant/user` | `/merchant/user` | Air ✅ / Flash ❌ |
| G06 | 我要进后台 | `/merchant/user` | `/merchant/user` | `/merchant/user` | 均 ✅ |
| G07 | 商家控制台在哪 | `/merchant/user` | `/merchant/user` | `/merchant/user` | 均 ✅ |
| G08 | 商家后台怎么进 | `/merchant/user` | `/merchant/user` | `/merchant/user` | 均 ✅ |
| C02 | 怎么前往商家工作台 | `/merchant/after-sales` | `/merchant/user` | `/merchant/user` | Air ✅ / Flash ❌ |
| B03 | 怎么前往商家工作台 | `/merchant/after-sales` | `/merchant/user` | `/merchant/user` | Air ✅ / Flash ❌ |

结论：**Air 6/6 全对；Flash 仍错 G05、C02、B03**，与验收预期逐条一致。

### 2.4 准确率与 A/B 报告的对照

改标签后，target 口径的基准应取 A/B 报告的「按 RouteCatalog 改判 `/merchant/user`」敏感性行，而不是原标签行：

| 指标 | A/B（改判口径） | 本次 | 差值 |
|---|---:|---:|---:|
| Flash intent | 41/60 68.33% | 41/60 68.33% | 0.00pp |
| Flash tool | 41/60 68.33% | 40/60 66.67% | −1.66pp |
| Flash target_all | 42/60 70.00% | 42/60 70.00% | 0.00pp |
| Flash target_navigate | 16/34 47.06% | 16/34 47.06% | 0.00pp |
| Air intent | 52/60 86.67% | 53/60 88.33% | +1.66pp |
| Air tool | 56/60 93.33% | 56/60 93.33% | 0.00pp |
| Air target_all | 54/60 90.00% | 54/60 90.00% | 0.00pp |
| Air target_navigate | 32/34 94.12% | 32/34 94.12% | 0.00pp |

逐条差异只有两处，都是模型单次波动，与本次改动无关：

- Air G45「我要转人工客服」：`UNKNOWN` → `FAQ`（期望 FAQ，+1 条 intent）。
- Flash G22「开店入驻流程」：intent 由 `GUIDE_NAVIGATION` → `OPEN_STORE`（变对），但 tool 由 `navigate` → `null`（变错），净 tool −1 条。

## 三、单测

| 测试 | 结果 |
|---|---|
| `LlmIntentClassifierTest` | 19/19 通过（含新增 GREETING/HELP 用例） |
| `SemanticRouterTest` | 6/6 通过（含 `/merchant/user` 断言） |
| `ChatAgentServiceTest` | 73/73 通过 |

## 四、结论

两个前置项均通过验收，可以进入第二步（切换 `glm-4-air`）。
