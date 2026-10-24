# LLM 超时 3s→5s 回归（2026-10-24，Step 1 受阻）

## 结论

超时改动本身按预期生效：**顺序真实链路 10 次超时 0/10**，37 次成功调用里有 20 次
超过 3 秒（3s 时代会全部误杀），最慢成功 4863ms。

但 Step 1 的两项门槛仍不能判绿，原因**不是本次改动**，而是 **embedding-3 配额耗尽**：
模型返回 `HTTP 429 / code 1113`「余额不足或无可用资源包,请充值。」，
语义路由预加载失败，所有查询退化到纯 LLM 分类。

- 10 个 UI 探针：**8 绿 / 2 红**（nav-graph 28/36、semantic-router 4 失败）。
- 真实链路 10 次：超时 0/10 达标，但 `targetPath` **0/10** 是 `/merchant/user`。
- default profile 4 条链路：**3/4 过**，商家工作台链路落 `/merchant/after-sales`。

按「验证通过后再提交」的口径，**Step 2 两笔 commit 未执行；未 push**。

## 一、改了什么

只改两处常量与对应注释，模型 / Prompt / 决策逻辑 / 语义阈值 0.75 均未动：

| 文件 | 改动 |
|---|---|
| `LlmIntentClassifier.java` | `TIMEOUT_SECONDS` 3 → 5；javadoc 补尾延迟依据 |
| `LlmReplyComposer.java` | `TIMEOUT_SECONDS` 3 → 5；javadoc 改为「两次出网最坏约 10 秒」 |

`LlmReplyComposer` 是订单 / 售后 / 评论播报的第二次出网；只改意图识别会让
「查订单」这类回答仍有约四成回退模板，因此同步放宽。

## 二、单测

`LlmIntentClassifierTest` 19/19、`SemanticRouterTest` 6/6、`ChatAgentServiceTest` 73/73，
合计 98/98，BUILD SUCCESS（`_work/step1-unit.out`）。

## 三、10 个 UI 探针（`ui/`）

| # | 探针 | 结果 |
|---|---|---:|
| 1 | tour | 26/26 PASS |
| 2 | goods-query | 6/6 PASS |
| 3 | goods-tour | 12/12 PASS |
| 4 | guide-vs-jump | 14/14 PASS |
| 5 | llm (online) | 4/4 PASS |
| 6 | nav-graph | **28/36** |
| 7 | product-aftersales | 12/12 PASS |
| 8 | semantic-router | **4 失败后中止** |
| 9 | tour-advance | 12/12 PASS |
| 10 | tour-flow | 23/23 PASS |

失败项与原因见 `ui-probes-summary.txt` 与 `embedding-429.txt`：
`MERCHANT_DASHBOARD / GOODS_MANAGE / ASSET_QUERY` 是语义路由专有意图，
不在 `LlmIntentClassifier.ALLOWED` 白名单里；向量化 429 后纯 Flash 路径无法产出。

## 四、真实链路 10 次「怎么前往商家工作台」（`real-chain-10.json`）

登录商家 `chenguang_parts`（role=2），开发 profile，顺序 10 次：

| 指标 | 实测 | 门槛 | 结论 |
|---|---:|---:|---|
| HTTP 200 | 10/10 | 10/10 | 过 |
| LLM 超时（`llmTimeoutType`） | **0/10** | ≤1/10 | 过 |
| intent = MERCHANT_DASHBOARD | 0/10 | — | 受 embedding 阻断 |
| targetPath = `/merchant/user` | **0/10** | 10/10 | 不过 |

10 次全部 `GUIDE_NAVIGATION` + `/merchant/after-sales`，墙钟 1442–4998ms。
匿名状态下另跑过 10 次（未计入本表）：超时 1/10、落点同样不对。

## 五、default profile 4 条链路（`default-auth/`）

| # | 链路 | 实测 | 结论 |
|---|---|---|---|
| 1 | 匿名 `POST /chat/ask` | 401 + `{"success":false,"msg":"未登录"}` | 过 |
| 2 | 匿名 UI 发消息 | 7/7 PASS，不跳登录页 | 过 |
| 3 | 客户「我的订单到哪了」 | 200 / ORDER_QUERY / 真实订单；`llmLatencyMs=2984` | 过 |
| 4 | 商家「怎么前往商家工作台」 | 200 / GUIDE_NAVIGATION / `/merchant/after-sales` | **不过** |

第 3 条顺带证明 5s 的必要性：该次 LLM 耗时 2984ms，3s 时已在边缘。
第 4 条在 17:28 的上一轮里走语义路由 17ms 命中；现在语义层 429，纯 LLM 无法产出该意图。

## 六、embedding 配额证据

`embedding-429.txt`：同一 Key、同一时刻，`embedding-3` 429 / code 1113，
`glm-4-flash` 聊天 200。dev 与 default 两个 profile 的启动日志都出现
`语义路由预加载失败（EmbeddingException: Embedding HTTP 429）`。

## 七、当前状态

- dev 后端已重启（profile=dev，health=200），模型仍 `glm-4-flash`，超时 5s。
- 未 commit、未 push；工作区包含本轮超时改动 + 之前的白名单 / 落点修复 + 诊断代码。
- 两笔 commit 的素材已就绪，但按门槛未落。

## 八、需要裁决

embedding-3 没有可用资源包，语义路由整层不可用。可选：

1. 给 embedding-3 充值 / 确认资源包恢复，然后我重跑 10 探针 + 真实链路 + default 4 条，
   全过再提交（推荐，最符合原验收口径）。
2. 明确接受「语义路由暂不可用」，把 2 条红探针记为环境例外，仅提交超时改动；
   但这样 `targetPath=/merchant/user` 这条门槛无法判绿。
3. 授权更换 embedding 模型 / 关闭语义路由并调整白名单与用例（属于改决策逻辑，需你点头）。
