# 切换 glm-4-air 验收报告（含回滚）

- 日期：2026-10-24
- 环境：dev profile，8088；前端 Vite 8080；商家账号 2222
- 前置：`chat-prereq-fix` 两个前置项已验收通过（Air 完整生产解析 180/180，Flash 177/180；工作台 6 条 Air 全对）
- 结论：**60 条 golden 通过；真实链路 10 次出现 1 次 3.013s 超时（10%），触发预设回滚门禁；已回滚到 `glm-4-flash`。**

## 一、执行顺序

1. 改 `chat.llm.model` 默认值 → `glm-4-air`（同时改 `LlmIntentClassifier` / `LlmReplyComposer` 的 `DEFAULT_MODEL` 与 `@Value` 兜底）。
2. 单测：`SemanticRouterTest` 6/6、`LlmIntentClassifierTest` 19/19、`ChatAgentServiceTest` 73/73 全绿。
3. 离线 60 条 × 2 模型 × 3 次 → `golden-60/`。
4. 重启后端（Air，`CHAT_EMBEDDING_ENABLED=false`，让「怎么前往商家工作台」真正走 LLM），真实链路 10 次 → `llm-latency/`。
5. 发现 1 次超时后，按预设门禁回滚；修复探针 `--log` 未生效的问题，复测 10 次 → `llm-latency-retest/`。
6. 回滚后重启后端（Flash，语义路由开启），跑 10 个聊天 UI 探针脚本 → `rollback-probes/`。

## 二、60 条 golden（Air 切换后）

| 指标 | Air | Flash | A/B 报告改判口径（Air/Flash） | Air 差值 |
|---|---:|---:|---:|---:|
| intent | 52/60 86.67% | 41/60 68.33% | 86.67% / 68.33% | 0.00pp |
| tool | 56/60 93.33% | 41/60 68.33% | 93.33% / 68.33% | 0.00pp |
| target_all | 54/60 90.00% | 43/60 71.67% | 90.00% / 70.00% | 0.00pp |
| target_navigate | 32/34 94.12% | 17/34 50.00% | 94.12% / 47.06% | 0.00pp |
| 完整生产解析（attempt） | **180/180 100%** | **177/180 98.33%** | 100% / 98.33% | 0.00pp |

- Air 四项与 A/B 报告（按 `/merchant/user` 改判口径）完全一致，满足 ±2pp。
- Flash target_all/target_navigate 各多对 1 条（1 条 ≈ 1.67/2.94pp），属于模型单次波动，不影响 Air 判定。
- 说明：`probe-chat-air-ab.py` 里模型是显式写死的（同时跑 Flash/Air），所以这份 60 条验证的是「模型输出稳定性」，不是「部署属性是否已切 Air」；部署属性由后端日志 `LLM_CALL model=glm-4-air` 与真实链路证明。

原始数据：`golden-60/ab-summary.json`、`ab-results-60.csv`、`ab-results-60.json`、`test-set-60.csv`。

> G49/C04（「今天天气不错」）的残留未变：两条都已通过生产解析，但 Air 原始意图是 `GREETING`、Flash 是 `FAQ`，与 golden 的 `UNKNOWN` 不一致。默认语义路由会先在 0.75 阈值处 `CLARIFY`，只有关闭 embedding 的纯 LLM 路径才会走到这个分支；详见 `chat-prereq-fix/report.md` 2.2 节。

## 三、真实链路 10 次（关键失败项）

固定句：`怎么前往商家工作台`；商家 2222；`CurrentPath=/merchant`；`CHAT_EMBEDDING_ENABLED=false`；超时 3s。

### 3.1 第一组 10 次（`llm-latency/`）

| 指标 | 结果 |
|---|---|
| 成功 | 9/10（wall 1089–1467ms） |
| 超时 | **1/10（10.00%）** |
| `targetPath=/merchant/user` | 9/10（超时那次为 `UNKNOWN`，无 action） |
| 超时细节 | run 6：wall 3045ms；后端日志 `LLM_CALL model=glm-4-air outcome=timeout latencyMs=3013 cause=HttpTimeoutException`；`CHAT_TRACE llmFallback=llm_error:LlmException llmTimeoutType=request_timeout finalBranch=switch:UNKNOWN` |

> 第一组探针的 `--log` 参数没有生效（`find_lines` 读的是全局默认日志），所以 CSV 里 `timeout` 列全空。上表是用**后端 `mall-management.log` 按 traceId 逐条对账**得到的；探针已修复并在第二组生效。

### 3.2 第二组 10 次（`llm-latency-retest/`，修复探针后）

| 指标 | 结果 |
|---|---|
| 成功 | 10/10 |
| 超时 | 0/10（0.00%） |
| `targetPath=/merchant/user` | 10/10 |
| `llmFallback=none` | 10/10 |
| LLM 延迟 | 中位 1233.5ms，均值 1241.8ms，范围 1170–1328ms |

### 3.3 合并判定

| 口径 | 结果 | 门禁 | 判定 |
|---|---:|---:|---|
| 第一组 10 次 | 1/10 = 10.00% | 超时 0/10 | ❌ |
| 两组合并 | 1/20 = **5.00%** | 降级率 **<5%** | ❌（等于 5%，未低于 5%） |

即使第二组全绿，第一组的尾延迟仍说明 Air 在 3s 硬上限下存在非零超时；合并 5% 也未达到 `<5%`。按用户预设的「超时 >5% 立即回滚」，执行回滚。

### 3.4 对 3013ms 的再定性（事后复核）

- 那次超时 wall=3045ms、后端记录 3013ms，**恰好压在 3s 边界上**（超出 13ms）；
  第二组 10 次全部 1170–1328ms。单次边界离群不足以证明「Air 固有延迟更差」。
- **回滚是规则执行，不是能力判定**：第一组 1/10 已触发「超时 0/10」门禁，
  按预设必须回滚；这只说明「10 次样本里出现了一次边界超时」，不回答
  「Air 在 3s 上限下到底行不行」。
- 该问题由更大样本测量回答：`docs/agent/evidence/chat-air-tail-latency/report.md`
  （Air / Flash 各 50 次，直接打 GLM 接口，不改线上模型与超时）。

## 四、回滚

已改回：

- `application.properties`：`chat.llm.model=${CHAT_LLM_MODEL:glm-4-flash}`，段注释回到 GLM-4-Flash。
- `LlmIntentClassifier` / `LlmReplyComposer`：`DEFAULT_MODEL` 与 `@Value` 兜底回到 `glm-4-flash`。
- 主代码/资源中已无 `glm-4-air`。

未回滚（保留）：前置项 1 的白名单修复、前置项 2 的 `/merchant/user` 落点。

## 五、回滚后的健康检查

后端已重启为 **Flash + 语义路由开启**，实测：

- 「怎么前往商家工作台」→ `MERCHANT_DASHBOARD /merchant/user`，`llmUsed=false`，`llmFallback=semantic_confident`，252ms。
- 10 个聊天 UI 探针脚本（对应 9 个证据目录）全部 exit 0、0 失败：

| 脚本 | 结果 |
|---|---|
| `probe-chat-tour.mjs` | 26/26 |
| `probe-chat-goods-query.mjs` | 6/6 |
| `probe-chat-goods-tour.mjs` | 12/12 |
| `probe-chat-guide-vs-jump.mjs` | 14/14 |
| `probe-chat-llm.mjs`（online） | 4/4 |
| `probe-chat-nav-graph.mjs` | 36/36 |
| `probe-chat-product-aftersales.mjs` | 12/12 |
| `probe-chat-semantic-router.mjs` | 14/14（含工作台 → `/merchant/user`） |
| `probe-chat-tour-advance.mjs` | 12/12 |
| `probe-chat-tour-flow.mjs` | 23/23 |

说明：Air 的 9 个 UI 探针**没有继续跑**——真实链路门禁已经失败并按令回滚，避免在已否决的配置上扩大验证面。回滚后跑同一套，证明前置项改动没有破坏 UI/漫游。

## 六、结论与后续建议

1. Air 的准确率优势在 60 条上稳定复现（intent/tool/target 全一致，生产解析 100%），但 3s 硬上限下的尾部延迟没有达到「0/10」与「<5%」。
2. 本轮不擅自放宽超时、不改 Prompt、不改阈值、不加写工具、不接 OTel、不 push。
3. 若还要继续评估 Air，建议单独做一轮「更大样本的尾延迟」测量（至少 50–100 次，看 p95/p99 与超时类型），再决定是接受尾延迟、还是放宽超时；本轮结论只到「按现有门禁回滚」。

## 七、证据索引

- `golden-60/`：60 条 × 2 模型 × 3 次
- `llm-latency/`：第一组真实链路 10 次（含 1 次超时）
- `llm-latency-retest/`：修复探针后复测 10 次
- `rollback-probes/`：回滚后 10 个 UI 探针脚本的截图与报告
- `_work/air-switch/logs/`：Air 后端日志（含超时 trace）
- `_work/air-switch/logs-rollback/`：Flash 后端日志
