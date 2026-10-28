# DeepSeek-V4.1-Flash + SiliconFlow BAAI/bge-m3 迁移回归（2026-10-24）

## 结论

- 两家连通性均 200；语义路由预加载 **94 条标准问法**，最终启动日志 **0 个 HTTP 429 / 0 个 ERROR**。
- 全量单测 **889 / 0 / 0 / 26 skipped，BUILD SUCCESS**（`LlmIntentClassifierTest` 22/22）。
- 10 个 UI 探针全部 exit 0、**0 失败**（共 159 项）。
- 真实链路「怎么前往商家工作台」顺序 10 次：**HTTP 200 10/10、`/merchant/user` 10/10、LLM 超时 0/10**。
- 纯 LLM（Embedding 关闭）降级基线：**`GUIDE_NAVIGATION` + 正确路径 3/3**；专用意图名 **0/3**（按裁决 B 接受）。
- `parseTargetPath` 在专用意图下丢路径的退化点已修复，并补 3 条单测。

## 一、纯 LLM 降级口径（裁决 B）

**语义路由不可用时，DeepSeek 以 `GUIDE_NAVIGATION` 承接导航意图，实测路径 3/3 正确。**

| 问法 | 实际意图 | `targetPath` | 墙钟 |
|---|---|---|---:|
| 怎么前往商家工作台 | `GUIDE_NAVIGATION` | `/merchant/user` | 1688 ms |
| 怎么看我上架的商品 | `GUIDE_NAVIGATION` | `/merchant/user/list` | 1401 ms |
| 我要管理我的钱 | `GUIDE_NAVIGATION` | `/merchant/user/member` | 1628 ms |

不修改 `SYSTEM_PROMPT`：DeepSeek 按 Prompt 把页面导航归到
`GUIDE_NAVIGATION / DIRECT_NAVIGATION`；三个专用意图名
（`GOODS_MANAGE / MERCHANT_DASHBOARD / ASSET_QUERY`）只在语义路由路径使用。
原始响应：`llm-only-3.json`（`dedicatedIntentOk=0`、`pathOk=3`）；调用日志：`llm-only-trace.txt`。

## 二、parseTargetPath 退化点修复

原先 `parseTargetPath` 只对 `GUIDE_NAVIGATION / DIRECT_NAVIGATION` 解析
`target_path`，其余意图一律返回 `null`。而 `ALLOWED` 已包含三个专用意图 ⇒
若模型真的产出它们，路径会被丢掉，下游 `answerRouted` 退化成
「这个页面在当前身份下打不开」。

修复：三个专用意图带 `target_path` 时同样放行并按角色路由字典解析；
不带路径时仍返回 `null`（保持旧行为）。单测覆盖三种意图带路径的响应
（`keepsTargetPathForGoodsManage / ForMerchantDashboard / ForAssetQuery`）。

## 三、连通性与启动

- `GET https://api.deepseek.com/models` → HTTP 200，
  `models = ['deepseek-flash', 'deepseek-v4-pro']`，`deepseek-flash` 在列。
- `POST https://api.siliconflow.cn/v1/embeddings`
  `{"model":"BAAI/bge-m3","input":["测试"]}` → HTTP 200，`dim=1024`。
- 最终启动（dev + Embedding 开启）：
  `语义路由预加载完成：94 条标准问法，阈值 0.75`；
  日志 149 行，HTTP 429 = 0、ERROR = 0；`/actuator/health` 200。

证据：`connectivity.txt`、`startup-semantic-on-final.txt`、
`startup-semantic-on-final-counts.txt`。

## 四、语义路由探针

| 探针 | 结果 |
|---|---|
| `probe-chat-nav-graph.mjs` | **36/36 PASS** |
| `probe-chat-semantic-router.mjs` | **14 项 / 0 失败**（stdout 28 行 PASS：脚本先即时打印、末尾再汇总一次） |

关键断言：`怎么前往商家工作台 → MERCHANT_DASHBOARD + /merchant/user`；
`怎么看我上架的商品 → GOODS_MANAGE + /merchant/user/list`；
`我花出去的钱去哪了 → ASSET_QUERY + /merchant/user/member`；
`今天天气不错 → CLARIFY`（相似度 < 0.75，不硬猜）。

## 五、10 个 UI 探针

| # | 探针 | 结果 |
|---:|---|---:|
| 1 | `probe-chat-tour.mjs` | 26/26 |
| 2 | `probe-chat-goods-query.mjs` | 6/6 |
| 3 | `probe-chat-goods-tour.mjs` | 12/12 |
| 4 | `probe-chat-guide-vs-jump.mjs` | 14 项 / 0 失败 |
| 5 | `probe-chat-llm.mjs`（online） | 4/4 |
| 6 | `probe-chat-nav-graph.mjs` | 36/36 |
| 7 | `probe-chat-product-aftersales.mjs` | 12/12 |
| 8 | `probe-chat-semantic-router.mjs` | 14 项 / 0 失败 |
| 9 | `probe-chat-tour-advance.mjs` | 12/12 |
| 10 | `probe-chat-tour-flow.mjs` | 23/23 |

10 个脚本均 exit 0、合计 0 失败。`guide-vs-jump` 的 stdout 28 行 PASS
同样是「即时打印 + 末尾汇总」各一遍，脚本口径为 14 项。

## 六、真实链路 10 次

`probe-chat-real-chain.py`，商家 `chenguang_parts`（role=2），语义路由开启，顺序 10 次：

| 指标 | 实测 |
|---|---:|
| HTTP 200 | 10/10 |
| `intent=MERCHANT_DASHBOARD` | 10/10 |
| `targetPath=/merchant/user` | 10/10 |
| LLM 超时 | 0/10 |
| 墙钟 | 3–11 ms（语义路由命中，未出网） |

原始数据：`real-chain-10.json`（`summary.pass=true`）。

## 七、单测

- 全量：`Tests run: 889, Failures: 0, Errors: 0, Skipped: 26`，BUILD SUCCESS（21:02）。
- 意图分类器：22/22，含新增的 3 条专用意图路径保留用例。
