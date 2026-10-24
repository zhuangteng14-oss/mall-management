# 切换 GLM-4-Air 回归验收（2026-10-24，未通过 → 已回滚）

## 结论

**回归未通过，按预设门禁回滚到 `glm-4-flash`。** 失败原因不是模型输出质量，而是
**Air 的账户配额耗尽**：golden 跑到第 52 条（累计约 153 次 Air 调用）后，Air 全部返回
`HTTP 429 / code 1113`「余额不足或无可用资源包,请充值。」，导致最后 9 条 ×3 次全部无内容。
同一时刻 Flash 仍正常 200（`quota-429.txt`）。

因此本轮未执行 Step 2 的三笔 commit，也未开始 Step 3；等配额恢复后从 Step 1 重跑。

## 一、执行时间线

1. 改模型默认值 → `glm-4-air`（`application.properties`、`LlmIntentClassifier`、
   `LlmReplyComposer` 的 `DEFAULT_MODEL` 与 `@Value` 兜底），超时仍 3s，Prompt /
   决策逻辑 / 语义阈值 0.75 未动。
2. 单测：`LlmIntentClassifierTest` 19/19、`SemanticRouterTest` 6/6、
   `ChatAgentServiceTest` 73/73 全绿（surefire 新时间戳核对）。
3. 启动 dev 后端（Air + 语义路由开启），跑 10 个 UI 探针 → **10/10 exit 0，0 FAIL**。
4. 后台跑 60 条 golden × 2 模型 × 3 次；前 51 条正常，后 9 条 Air 全部 429。
5. 复核 Air 配额 → 直接调用仍 429；Flash 200。按门禁立即回滚并重启 Flash。

## 二、60 条 golden（`golden-60/`）

| 指标 | Air | Flash | Air 门槛 | 结果 |
|---|---:|---:|---:|---|
| intent | 45/60 75.00% | 40/60 66.67% | ≥86.67%（±2pp） | ❌ |
| tool | 47/60 78.33% | 41/60 68.33% | ≥93.33% | ❌ |
| target_all | 49/60 81.67% | 42/60 70.00% | ≥90% | ❌ |
| target_navigate | 27/34 79.41% | 16/34 47.06% | ≥94.12% | ❌ |
| 生产解析（attempt） | 153/180 85.00% | 177/180 98.33% | — | — |
| 调用错误条数 | **9** | 0 | 0 | ❌ |

**失败条目全部是最后 9 条**：C02、C03、C04、C05、C06、C07、B01、B02、B03。
每条 3 次都是同一个 429，不是模型答错：

```
HTTP 429: {"error":{"code":"1113","message":"余额不足或无可用资源包,请充值。"}}
```

因此上表 Air 的 75%/78.33%/81.67%/79.41% 是「配额截断后的分母」，
**不能用来评价 Air 的准确率**。把最后 9 条剔除、只看配额耗尽前的 51 条：

| 指标（前 51 条） | Air | Flash |
|---|---:|---:|
| intent | 45/51 88.24% | 35/51 68.63% |
| tool | 46/51 90.20% | 35/51 68.63% |
| target_all | 45/51 88.24% | 37/51 72.55% |
| target_navigate | 27/29 93.10% | 15/29 51.72% |

即使不算配额，Air 的 tool 90.20% 也低于 93.33%（−2pp 容差下限 91.33%），
target_all / nav 在 ±2pp 容差内。也就是说本轮 Air 至少有两项没达标：
一项是配额截断（4 项指标），一项是 tool 的抽样波动（前 51 条即已偏低）。
**不能把失败全归因于配额，也不能据此否定 Air 的准确率**；配额恢复后需全量重跑才能判定。

## 三、10 个 UI 探针（Air 配置，`ui-probes.out`）

| 脚本 | 结果 |
|---|---|
| `probe-chat-tour.mjs` | exit 0，26 PASS |
| `probe-chat-goods-query.mjs` | exit 0，6 PASS |
| `probe-chat-goods-tour.mjs` | exit 0，12 PASS |
| `probe-chat-guide-vs-jump.mjs` | exit 0，28 PASS |
| `probe-chat-llm.mjs`（online） | exit 0，4 PASS |
| `probe-chat-nav-graph.mjs` | exit 0，36 PASS |
| `probe-chat-product-aftersales.mjs` | exit 0，12 PASS |
| `probe-chat-semantic-router.mjs` | exit 0，28 PASS |
| `probe-chat-tour-advance.mjs` | exit 0，12 PASS |
| `probe-chat-tour-flow.mjs` | exit 0，23 PASS |

合计 0 FAIL。说明前端 / 漫游 / 语义路由在前置项改动下没有回归；
但多数探针走语义路由或工具，**不能单独证明 Air 部署属性**（部署属性以 `LLM_CALL model=` 为准）。

## 四、default profile 4 条链路

**未执行**：golden 门禁已失败，按「任何一项不过 → 立即回滚」不再在已否决的配置上扩大验证面。
上一轮同代码、Flash 配置下 4 条已全部通过（见 `chat-default-auth/report.md`）。

## 五、真实链路 10 次

**未执行**：原因同上；且 Air 当前 429，跑了也只会得到全部降级，不能作为延迟证据。

## 六、配额证据

`quota-429.txt`（2026-10-24 20:4x 实测）：

```
glm-4-air  HTTP=429  {"error": {"code": "1113", "message": "余额不足或无可用资源包,请充值。"}}
glm-4-flash HTTP=200 ...
```

## 七、回滚

- `chat.llm.model` → `glm-4-flash`；`LlmIntentClassifier` / `LlmReplyComposer`
  的默认值与 `@Value` 兜底同步回 `glm-4-flash`。
- 主代码中 `glm-4-air` 已清零（`grep -rn` 为空）。
- dev 后端已重启为 Flash。
- 未 commit、未 push；工作区代码改动回到本轮开始前的状态（前缀修复 + 诊断日志仍在）。

## 八、下一步

1. 给 Air 充值 / 等资源包恢复，确认单次调用 200。
2. 从 Step 1 重新执行：60 条 golden（可先只补跑失败条目，但为满足「完整回归」建议全量重跑）、
   10 个 UI 探针、default 4 条、真实链路 10 次。
3. 全过后再按 Step 2 三笔 commit。
