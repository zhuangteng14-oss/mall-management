# 智能客服 LLM 路径超时根因排查报告

- 日期：2026-10-24
- 环境：dev profile，端口 8088；`CHAT_EMBEDDING_ENABLED=false`（语义路由关闭，确保真正走 LLM）
- 固定句：`怎么前往商家工作台`（CurrentPath=/merchant，商家 2222）
- 模型：`glm-4-flash`，temperature 0.1，超时 3s（连接与请求同值）
- 约束：未改任何决策逻辑、未调语义阈值 0.75、未加兜底、未扩 RAGAS、未加写工具、未 push

## 一、诊断埋点（只读）

改动两个文件，仅增加观测字段：

- `LlmIntentClassifier`
  - 记录 `LLM_CALL`：单次出网耗时 `latencyMs`、结果 `ok/timeout/error`、HTTP 状态、`inputTokens/outputTokens`、`x-request-id`、异常类名。
  - 记录 `LLM_PROMPT`：System Prompt 总字符数与分段字符数、估算 token（DEBUG 级别才输出全文）。
  - 计时区间：`httpClient.send()` 前后；超时异常保留原始 cause。
- `ChatAgentService`
  - `CHAT_TRACE` 新增 `llmLatencyMs`（进入 classify 到返回/抛出的整段耗时）与 `llmTimeoutType`（`none / connect_timeout / request_timeout / other`）。
  - 判定顺序：先 `HttpConnectTimeoutException`，再 `HttpTimeoutException`，避免子类被父类吞掉。
  - 未改动任何分支条件、意图选择、工具调用与降级路径。

## 二、真实链路 10 次（配置 B）

| # | 端到端 wall | llmLatencyMs | 结果 | 超时类型 | 意图 / 分支 | 输入/输出 token | x-request-id（前 8 位） |
|---|---|---|---|---|---|---|---|
| 1 | 3050 | 3030 | 超时 | request_timeout | UNKNOWN / switch:UNKNOWN | - / - | - |
| 2 | 3026 | 3009 | 超时 | request_timeout | UNKNOWN / switch:UNKNOWN | - / - | - |
| 3 | 2047 | 2046 | 成功 | none | GUIDE_NAVIGATION / tool:navigate | 2157 / 27 | fc830b57 |
| 4 | 2276 | 2263 | 成功 | none | GUIDE_NAVIGATION / tool:navigate | 2157 / 27 | 6a51b797 |
| 5 | 2001 | 1991 | 成功 | none | GUIDE_NAVIGATION / tool:navigate | 2157 / 27 | 3fca8249 |
| 6 | 2369 | 2356 | 成功 | none | GUIDE_NAVIGATION / tool:navigate | 2157 / 27 | 9e44f107 |
| 7 | 1841 | 1827 | 成功 | none | GUIDE_NAVIGATION / tool:navigate | 2157 / 27 | 0fbd4eb5 |
| 8 | 2276 | 2260 | 成功 | none | GUIDE_NAVIGATION / tool:navigate | 2157 / 27 | 3b8992b1 |
| 9 | 3019 | 3002 | 超时 | request_timeout | UNKNOWN / switch:UNKNOWN | - / - | - |
| 10 | 3031 | 3008 | 超时 | request_timeout | UNKNOWN / switch:UNKNOWN | - / - | - |

汇总：

- 超时 **4/10（40%）**；成功 6 次：中位 **2153ms**，均值 2124ms，范围 1827–2356ms。
- 4 次超时全部落在 **3002–3030ms**，类型全部为 **request_timeout**，**没有一次 connect_timeout**。
- 成功请求的 `x-request-id` 全部存在；超时请求因客户端先行断开，拿不到响应头与 usage。
- `llmLatencyMs` 比 `LLM_CALL.latencyMs` 多 9–16ms，差额是 Prompt 组装与埋点本身，可忽略。

与基线 76% 的差异：本次 10 连跑为 40%，但形态一致——成功集中在 1.8–2.4s，超时全部卡在 3.0s。超时率随 GLM 端当时负载波动，不是固定 76%。

## 三、System Prompt 体积

真实 Prompt 总长 **4414 字符**，GLM 实际返回 `prompt_tokens=2157`（成功请求稳定复现）。
按“中文 0.779 token/字、其他 0.241 token/字符”折算（系数由 4414 字符→2157 token、730 字符→249 token 两组实测反解）：

| 分段 | 字符数 | 估算 token | 占比 |
|---|---:|---:|---:|
| 角色/输出约束行 | 48 | 34 | 1.6% |
| 意图清单（GUIDE/DIRECT/…） | 334 | 143 | 6.6% |
| 规则（6 条） | 385 | 196 | 9.1% |
| RouteCatalog（17 条页面） | 1385 | 733 | 34.0% |
| ToolRegistry（7 个工具 + 调用规则） | 2104 | 1004 | 46.6% |
| 当前页面行 | 18 | 9 | 0.4% |
| 输出格式行 | 130 | 34 | 1.6% |

结论：**八成 token 花在“页面字典 + 工具字典”上（80.6%）**，意图清单与规则合计仅 15.7%。

压缩空间（按贡献度排序，均需保持现有行为）：

1. **ToolRegistry（约 1000 token，最大头）**
   - 7 行 JSON Schema 里的 `"additionalProperties":false`、`"type":"object"` 对意图选择贡献很低，删掉可省约 280 字符（≈70 token），执行层校验不受影响。
   - 无参工具（`merchant_comments`、`merchant_refunds`、`open_store`）的 schema 可写成 `{}`，再省约 100 字符。
   - 工具描述里的身份分流细节（开店/入驻的五种身份、秒杀的身份指引）由后端真正执行，模型只需知道“何时选它”，可压缩一半以上。
   - `navigate` 的描述与 RouteCatalog 标题、调用规则 2 三处重复，保留一处即可。
   - 保守估计：2104 字符 → 900–1100 字符（≈570–770 token 省下）。
2. **RouteCatalog（约 730 token）**
   - 17 行都带“标题 + 完整说明 + 用户说法”。标题与路径重复；说明里的散文对匹配贡献低于“用户说「…」”里的关键词。
   - 改成 `路径 — 关键词1 / 关键词2 / …` 可压到 600–700 字符（≈350–400 token 省下），关键词逐字保留，风险可控。
3. **规则（196 token）**：规则 1/2/3 与页面说明、工具描述存在重复，可去重 30% 左右，绝对收益小。
4. 角色行、当前页面行、输出格式行合计约 77 token，不建议动。

按上述方案可落到 **约 1000–1200 token**；要稳定低于 1000，需要同时压缩规则并用 50 条 golden set 回归意图准确率。**本次未改 Prompt**。

## 四、补充实验（离线复现，同一 JDK HttpClient / 同一 Prompt / 同一 temperature）

### 4.1 完整 Prompt + Flash

| 实验 | 样本 | 中位 | 均值 | >3s | >5s |
|---|---:|---:|---:|---:|---:|
| 30s 上限 | 12 | 2462 | 3015 | 4/12 | 1/12（5009） |
| 5s 上限 | 12 | 2764 | 2993 | 5/12 | 0/12（全部返回） |
| 交错慢窗口 | 12 | 3894 | 4027 | 10/12 | 2/12（5654、5948） |
| 汇总 | 36 | — | — | 19/36 | 3/36 |

加上 Flash 对 Air 的 8 次同窗口样本，完整 Prompt + Flash 共 **44 次**：>3s 为 **27/44（61%）**，>5s 为 **3/44（6.8%，95% Wilson 区间 2.3%–18.2%）**。

### 4.2 压缩 Prompt（249 token）

手工压缩版 730 字符 / 249 token：

| 实验 | 样本 | 中位 | 均值 | >3s | 备注 |
|---|---:|---:|---:|---:|---|
| 30s 上限 | 12 | 1780 | 2098 | 2/12 | 全部成功 |
| 3s 上限 | 12 | — | — | 2/12 超时 | 全部 request_timeout |
| 与完整版交错配对 | 12 对 | 2668 vs 3894 | 2887 vs 4027 | — | 压缩版 10/12 更快 |

配对差（完整 − 压缩）：均值 **1140ms**，95% CI 148–2133ms；中位差 1040ms；t≈2.53。
即压缩后延迟下降约 **28%–32%**，但 3s 上限下仍会超时（2/12），**不能单独解决问题**。

重要限制：压缩版 Prompt 是为了测延迟手工写的，**不是等价 Prompt**。它在该句上返回 `tool:null, target_path:/merchant`，而完整版返回 `tool:navigate`；因此 4.2 的延迟只能当作“压缩能带来多少收益”的上界参考，不能直接上线。

### 4.3 Flash vs Air（同一窗口、同一 Prompt、交错执行）

| 模型 | 样本 | 延迟范围 | 中位 | 输入/输出 token |
|---|---:|---:|---:|---:|
| glm-4-flash | 8 | 3118–4845ms | 4022 | 2146 / 27 |
| glm-4-air | 8 | 1110–1436ms | 1271 | 2150 / 48 |

同窗口下 **Air 8/8 全部快于 Flash**，且波动明显更小。单次内容对照：

- Flash：`{"intent":"GUIDE_NAVIGATION","tool":"navigate","target_path":"/merchant/after-sales"}`（“商家工作台”被导向售后页，可疑）
- Air：`{"intent":"GUIDE_NAVIGATION","tool":"navigate","target_path":"/merchant/user","slots":{...}}`（更符合“商家后台”）
- 两者都会包 ```` ```json ```` 代码块；Air 输出 48 token，Flash 27 token。

单样本不足以判定准确率优劣，但说明**换模型必须先做意图/路由准确率回归**，不能只看延迟。

## 五、四个问题的结论

### Q1. 主因是哪一个？

**主因是 (a) GLM-4-Flash 服务端延迟 + (b) 3s 上限过紧；Prompt 过长是放大器，不是根因；网络不是原因。**

依据：

1. **排除网络**：真实链路 4 次超时全部是 `request_timeout`，0 次 `connect_timeout`；连接阶段（DNS/TCP/TLS）从未触顶。离线 44 次也没有一次连接超时。
2. **排除“只是 3s 太紧”**：慢窗口下完整 Prompt 中位 3894ms、p90 5860ms，即使给 5s 也有 2/12 超过；3s 只是把本来就要 3–6s 的请求截断。
3. **排除“只是 Prompt 太长”**：249 token 的压缩版在 3s 下仍 2/12 超时；说明 fixed overhead 已占 1.2–1.5s，Prompt 不是唯一矛盾。
4. **确认模型端波动**：同一 Prompt、同一机器，不同窗口的 Flash 中位从 2462ms 漂到 3894ms；同窗口 Air 稳定在 1.1–1.4s。这是服务端排队/负载特征，不是本地网络。
5. 基线 76% 与今天 40% 的差异，也符合“Flash 延迟分布随负载跨在 3s 两侧”的解释。

### Q2. 放宽到 5 秒，超时率能降到多少？

**约 5%–10%；本批 44 次完整 Prompt + Flash 的实测是 3/44 = 6.8%（95% CI 2.3%–18.2%）。**

- 5s 上限那组 12/12 全部返回，最慢 4186ms。
- 慢窗口仍出现 5654、5948ms 两次，说明坏窗口下 5s 也可能有 15% 左右超时。
- 因此：**3s→5s 能把 40%–76% 降到个位数到十几个百分点，但不能保证零超时**。
- 代价：约 7% 的请求会让用户多等 2 秒（3–5s 区间）才拿到答案或降级。

### Q3. 压缩到 1000 token 以内，延迟能降多少？

**可测，但只能给出区间：约降 0.5–1.1 秒（中位约 0.9 秒，−28%–32%）；无法保证 3s 内全部返回。**

- 249 token 压缩版（远低于 1000）配对实测：中位 3894→2668ms，均值 4027→2887ms，10/12 更快，均值省 1140ms（95% CI 148–2133ms）。
- 但压缩版在 3s 上限下仍 2/12 超时。
- 而且这版 Prompt 不等价（丢了 `tool:navigate`），真实生产压缩到 <1000 token 的收益会小于 1140ms；保守按 **0.5–0.9 秒**预估。
- 结论：压缩值得做（省 token 成本 + 降延迟），但**不能替代放宽超时或换模型**。

### Q4. 建议

**首选：先做 glm-4-air 的准确率 A/B，通过后切换 Flash→Air；其次把超时放到 5s；Prompt 压缩作为第二阶段的成本/延迟优化；不建议“接受现状”。**

理由与顺序：

1. **Flash→Air（收益最大）**：同窗口 8/8 次 1.1–1.4s，3s 上限下预计接近 0 超时；输入 token 基本不变（2150 vs 2146）。但输出 token 27→48，且单样本路由结果不同，**必须用现有 50 条 golden set 对比意图、工具、target_path 三项准确率**，并确认配额/费用后再切。
2. **3s→5s（最便宜）**：如果暂不换模型，这是唯一能把超时率从 40%+ 压到约 7% 的单点改动；副作用是尾部请求更慢。可先改配置，不改代码逻辑。
3. **Prompt 压缩（中期）**：按第三节方案，先压 ToolRegistry 和 RouteCatalog，目标 1000–1200 token；每步用 golden set 回归，重点看 `tool=navigate` 是否还稳定出现。不要直接采用本次 249 token 的手工版。
4. **接受现状：不建议**。3s 上限下 40% 的请求拿不到 LLM 结果，且基线曾到 76%，用户体验不可接受。

建议实施顺序：**Air 准确率 A/B（不改线上）→ 通过则切 Air 并保留 3s → 观察一周 → 再做 Prompt 压缩 → 5s 仅作为不换模型时的兜底方案**。

## 六、误差与限制

- 真实链路只有 10 次、离线每组 8–12 次，置信区间较宽；GLM 端负载随时间变化明显，跨窗口绝对值不可直接比较，故采用**同窗口交错配对**。
- 离线探针复用了同一 JDK HttpClient 与同一 Prompt，但绕过了 Spring 编排；真实链路表以端到端 10 次为准。
- 离线完整 Prompt 从 DEBUG 日志提取，14 个特殊符号（⇒/⚠️ 等）在 GBK 转码时变成 `?`，重放 `prompt_tokens=2146`，比生产 `2157` 少 11（0.5%），不影响结论。
- 超时请求拿不到服务端 usage 与 x-request-id；其真实完成时间未知，只能由离线同 Prompt 分布推断。
- 诊断期开启了 `LlmIntentClassifier` DEBUG（仅影响 Prompt 全文落盘），`LLM_PROMPT` 的 INFO 行只含字符数；对 HTTP 耗时影响远小于 1ms。
- 本次只加观测字段，未改决策逻辑、未调 0.75、未加兜底、未加写工具；代码未提交、未 push。

## 七、数据文件

均在 `docs/agent/evidence/chat-llm-latency/`：

- `runs.csv` / `runs.json` / `runs.md`：真实链路 10 次全字段
- `offline-full-flash-30s.csv`、`offline-full-flash-5s.csv`：完整 Prompt + Flash 离线重放
- `offline-compressed-flash-30s.csv`、`offline-compressed-flash-3s.csv`：压缩 Prompt 重放
- `paired-full-vs-compressed.csv`：完整 vs 压缩交错配对
- `paired-flash-vs-air.csv`：Flash vs Air 交错配对
- `prompt-breakdown.csv`：Prompt 分段字符/token
- `content-compare.json`：Flash/Air 单次返回内容对照
