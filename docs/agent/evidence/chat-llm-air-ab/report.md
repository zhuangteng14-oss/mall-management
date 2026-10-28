# 智能客服 LLM 路由 A/B 报告：glm-4-air vs glm-4-flash

- 日期：2026-10-24
- 范围：LLM 意图分类层（`LlmIntentClassifier`），商家角色，当前页 `/merchant`
- 约束：不切线上模型、不改 3s 超时、不压缩 Prompt、不 push
- 结论先行：**Air 四项准确率全部不低于 Flash，target_path 优势最大；Air 按生产格式口径通过本次门槛，具备切换资格。但「怎么前往商家工作台」暴露的是路由定义冲突，不是模型准确率问题，换 Air 不会自动修好它。**

---

## 0. 一句话结论

| 问题 | 结论 |
|---|---|
| Air 是否全面差于 Flash？ | 否。intent +18.34pp、tool +25.00pp、target_path 全部 +15.00pp、导航子集 +38.24pp，四项全胜。 |
| Air 的 JSON 是否更容易坏？ | 否。生产提取口径 180/180 = 100%；严格裸 JSON 也 87.78% vs Flash 51.11%。 |
| Air 有没有不如 Flash 的地方？ | 有，7 条。但其中 5 条被生产侧的确定性守卫/FAQ 抵消，真正保留到用户侧的只有 G48、G50 两条语义差异。 |
| 「怎么前往商家工作台」谁对？ | Air 的 `/merchant/user` 与现行 `RouteCatalog` 的「商家后台」说法一致；Flash 的 `/merchant/after-sales` 在任何口径下都错。但 golden/语义路由写的是 `/merchant`，三处口径冲突。 |
| 今天切不切？ | 不切。先处理两个前置项：`ALLOWED` 的 GREETING/HELP 缺口、「商家工作台」落点定义。 |

---

## 1. 测试集与方法

### 1.1 测试集

60 条，去重后 56 条；重复项保留并参与统计（与用户要求的「约 60 条」一致）。

| 来源 | 条数 | 说明 |
|---|---:|---|
| golden-set-50 | 50 | `docs/agent/evidence/chat-baseline/golden-set-50.csv`，G01–G50 |
| 7 句冷问题 | 7 | C01 怎么看上架商品、C02 怎么前往商家工作台、C03 钱去哪了、C04 天气、C05 怎么处理退款、C06 退款进度、C07 直接带我去管理商品 |
| 边界输入 | 3 | B01 `222:ij-555`、B02 `XPEM63624472`、B03 `怎么前往商家工作台`（= C02 重复） |

重复关系：C03=G27、C04=G49、C05=G34、B03=C02。逐条期望值见 `test-set-60.csv`；`acceptable_intents` / `expected_tool` 支持多选（如 `open_store|navigate`），避免把等价实现判错。

### 1.2 调用方式

- 模型：`glm-4-flash`、`glm-4-air`，同一 Prompt、同一温度 0.1、同一 `currentPath=/merchant`、商家角色。
- 每条问题每个模型跑 **3 次**；共 60×2×3 = **360 次调用**，`call_errors=0`。
- 逐条取 3 次的 `(intent, tool, target)` 众数作为该条结果；同时报告逐次（attempt）准确率与去重 56 条敏感度，避免众数掩盖抖动。
- Prompt：`_work/chat-baseline/prompt-merchant.txt`，`sha256=23e903079d819c739bb3a4dd667aa904c7fa1110e4a6a81da23f21d993dba3ad`，8566 bytes；两模型逐字相同。
- 密钥只从 `_work/llm-key` 读取，报告和证据文件不含任何密钥/Authorization。

### 1.3 口径边界（必须看）

1. **这是 LLM 分类层的条件准确率，不是端到端准确率。** 探针直接调用 `LlmIntentClassifier`，绕过了 `FaqKnowledgeBase`、`SemanticRouter` 和 `ChatAgentService` 的确定性纠偏。线上若语义路由命中（余弦 ≥ 0.75），LLM 根本不会被调用。
2. **Prompt 抓取时有 2 类字符损坏**：`⇒` 变成 `?`，`⚠️` 变成 `??`；可见文本其余部分一致。两模型看到的是同一份损坏 Prompt，横向比较有效；绝对值要以线上未损坏 Prompt 复跑为准。
3. **JSON 三个口径分开报**：
   - `syntax_valid`：对原始输出直接 `json.loads`，有 markdown fence 即失败（严格裸 JSON）。
   - `json_valid`：按生产 `extractContent` 去 fence、取首尾 `{}` 后解析，且 `intent` 非空（生产格式口径）。
   - `production_parse_valid`：在 `json_valid` 之上再加 `ALLOWED` 白名单、商家工具集、target_path 可解析（生产完整解析口径）。

---

## 2. 四项准确率（Q1，给数字）

### 2.1 主表：逐条众数（n=60）

| 维度 | Flash | Air | 差值（Air−Flash） |
|---|---:|---:|---:|
| intent | 41/60 = **68.33%** | 52/60 = **86.67%** | **+18.34pp** |
| tool（null/navigate/具体工具名） | 41/60 = **68.33%** | 56/60 = **93.33%** | **+25.00pp** |
| target_path（全部 60 条） | 39/60 = **65.00%** | 48/60 = **80.00%** | **+15.00pp** |
| target_path（navigate 子集 34 条） | 13/34 = **38.24%** | 26/34 = **76.47%** | **+38.24pp** |
| JSON 合法（生产格式口径） | 177/180 = **98.33%** | 180/180 = **100.00%** | **+1.67pp** |
| JSON 合法（严格裸 JSON） | 92/180 = **51.11%** | 158/180 = **87.78%** | **+36.67pp** |
| 完整生产解析 | 171/180 = **95.00%** | 171/180 = **95.00%** | **0.00pp** |

补充：
- JSON 逐条「3 次全合法」（生产格式口径）：Flash 59/60，Air **60/60**。Flash 唯一失败是 G50「帮我写一首诗」，3 次都直接输出中文诗、没有 JSON。
- 严格裸 JSON 逐条「3 次全合法」：Flash 28/60，Air 50/60。Air 仍有 10 条至少一次带 markdown fence，所以「严格无 fence」口径下不是 100%。
- 有 markdown fence 的条目：Flash 31/60 条出现过，Air 仅 10/60 条；fence 次数 85/180 vs 22/180。
- 完整生产解析双方都是 171/180，失败项不是格式，而是既有白名单缺口：`ALLOWED` 不含 `GREETING`/`HELP`。
  - Flash：G47 `GREETING`×3、G48 `HELP`×3、G50 无 JSON×3。
  - Air：G47 `GREETING`×3、G49 `GREETING`×3、C04 `GREETING`×3。
  - 修 `ALLOWED` 后：Air 180/180 = 100%，Flash 177/180 = 98.33%（只剩 G50 无 JSON）。

### 2.2 稳健性：逐次与去重

| 口径 | Flash intent | Air intent | Flash tool | Air tool | Flash target_all | Air target_all | Flash target_nav | Air target_nav |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 逐条众数 n=60 | 68.33% | 86.67% | 68.33% | 93.33% | 65.00% | 80.00% | 38.24% | 76.47% |
| 逐次 attempt n=180 | 67.78% | 87.22% | 67.22% | 92.78% | 66.11% | 80.00% | 41.18% | 76.47% |
| 去重 n=56 | 69.64% | 87.50% | 67.86% | 92.86% | 67.86% | 80.36% | 41.94% | 77.42% |

三种口径方向一致，Air 的领先不是众数或重复样本造成的。

### 2.3 两模型一致率（「是否一致」维度）

| 维度 | 一致条数 | 一致率 |
|---|---:|---:|
| intent | 36/60 | 60.00% |
| tool | 39/60 | 65.00% |
| target_path | 40/60 | 66.67% |

即有 24 条在 intent 上分歧、21 条在 tool 上分歧、20 条在 target 上分歧；逐条明细见 `ab-results-60.csv` 的 `*_agree` 列。

### 2.4 延迟与 token（次要，不作为本轮门槛）

- 首次调用延迟中位数：Flash **3261.5ms**，Air **1044.5ms**；最大值 7161ms vs 1624ms。
- completion_tokens（180 次）：Flash 中位数 39、合计 6689；Air 中位数 44、合计 7772，约 **1.16×**。
- 本次 60 条**没有复现**「Air 输出 token 是 Flash 的 1.8×」；格式风险也没有出现，Air 的裸 JSON 合法率反而高 36.67pp。

---

## 3. 差异最大的 5 条（Q2）

排序规则：先按「三维正确数（intent/tool/target，0–3）差值」的绝对值降序；同分时按业务影响挑选。完整的 |差值|≥2 列表见 3.2。

### 3.1 Top 5

| # | ID | 问题 | 期望 | Flash 众数 | Air 众数 | 差值 | 错在哪 |
|---|---|---|---|---:|---:|---|
| 1 | G20 | 帮我找一下商品 | `GOODS_QUERY / query_goods / —` | ✅ `GOODS_QUERY / query_goods / —` | ❌ `GUIDE_NAVIGATION / navigate / /merchant/search` | **−3** | Air 把「找商品」理解成「去搜索页」，三维全丢。生产侧 `looksLikeGoodsQuery` 会命中「找 + 商品」并改回 `GOODS_QUERY`，所以用户侧被抵消；这是纯 LLM 层的回归。 |
| 2 | G02 | 怎么看我的商品 | `GUIDE_NAVIGATION / navigate / /merchant/user/list` | ❌ `GOODS_QUERY / null / —` | ✅ 完全正确 | **+3** | Flash 把「管理/查看商品」当成商品数据查询，不进商品管理页；三维全丢。生产侧不会纠偏（该句会被 `looksLikeGoodsQuery` 判为商品查询），是真实用户侧错误。 |
| 3 | G27 | 我花出去的钱去哪了 | `GUIDE_NAVIGATION / navigate / /merchant/user/member` | ❌ `FAQ / null / —` | ✅ 完全正确 | **+3** | Flash 直接掉进 FAQ，没导航到会员中心；三维全丢。无生产纠偏，真实错误。 |
| 4 | G33 | 发布新商品 | `GUIDE_NAVIGATION / navigate / /merchant/user/add` | ❌ `OPEN_STORE / null / —`（raw=`/merchant/user/add`，但 `OPEN_STORE` 下被忽略） | ✅ 完全正确 | **+3** | Flash 违反 Prompt 规则 2「上架/发布商品不是开店」，把发布页问题答成开店流程。`RouteCatalog.guess("发布新商品")` 实测返回 `null`，生产不会纠偏，真实且影响大。 |
| 5 | G17 | 买家留言在哪 | `COMMENT_QUERY / merchant_comments / —` | ⚠️ `COMMENT_QUERY / null / —`（intent 对、tool 缺） | ❌ `GUIDE_NAVIGATION / navigate / /merchant/msg` | **−2** | Air 把「留言」理解成消息中心，三维全丢；Flash 只是没显式选工具（生产会按 intent 补 `merchant_comments`）。生产侧 `looksLikeCommentQuery` 命中「留言」后改回 `COMMENT_QUERY`，用户侧被抵消。 |

稳定性：G20/G02/G27/G17 的众数元组 3/3 一致；G33 Flash 的 tool 在 `null` 与 `open_store` 之间抖动（2:1），但两种 `intent` 都是 `OPEN_STORE`，结论不变。

### 3.2 所有 |差值|≥2 的条目

| 方向 | 条目 |
|---|---|
| Air 好 3 维 | G02、G03、G04、G27、G28、G29、G31、G33、C01、C03 |
| Air 差 3 维 | G20 |
| Air 好 2 维 | G14、B02 |
| Air 差 2 维 | G17、G44 |

其余 46 条差值在 −1…+1。Air 的 +3 条目集中在三类：**商品管理页**（G02/G03/G04/C01）、**资产/会员中心**（G27/G28/G29/C03）、**经营数据/发布页**（G31/G33）；Flash 在这些句子上反复把「去办事」误判成「查数据」或「开店」。

---

## 4. target_path 专项（Q3）

### 4.1 结论：是，Air 明显更好，且不是单条样本造成

**按 navigate 子集，Air 76.47% vs Flash 38.24%，+38.24pp；按全部 60 条，Air 80.00% vs Flash 65.00%，+15.00pp。** 这比延迟优势更值得作为切换依据。

### 4.2 拆解：优势来自「愿不愿意导航」和「导航后选对没」

| 指标 | Flash | Air |
|---|---:|---:|
| 34 条应导航中，实际给出导航/专用工具 | 24/34 = **70.59%** | 34/34 = **100.00%** |
| 给出导航的条目中，target 正确 | 13/24 = **54.17%** | 26/34 = **76.47%** |
| 34 条整体 target 正确 | 13/34 = **38.24%** | 26/34 = **76.47%** |

- Flash 有 **10 条**该导航却判成数据查询/FAQ，根本没有 target：G02、G03、G04、G27、G28、G29、G31、G33、C01、C03。
- 在各自实际给出导航的条目中，Air 的路径正确率仍高 **+22.30pp**（54.17% → 76.47%）。
- 双方共同选错的 8 条：G05、G06、G07、G08、G36、G37、C02、B03。
- Flash 单独选错、Air 没选错的：G34、C05（生产 `AFTER_SALES` 守卫会把 Flash 改到 `/merchant/after-sales`）、G21（Flash 在 `OPEN_STORE` 下多带 raw path，生产不把该 path 当落点）。
- **在 34 条应导航条目里，Air 单独选错、Flash 没选错的：0 条。** 另有 4 条非导航题（G09/G17/G20/G44）Air 多带了 raw target_path，按 Prompt 要求算违规，但生产不会把数据查询的 target 当落点：G09 直接忽略，G17/G20 被守卫改成数据查询，G44 被 policy FAQ 提前接住。
- 也就是说，按生产实际生效的 target_path 看，Air 没有一条比 Flash 差；Flash 的部分错误还被生产守卫兜住。

### 4.3 三种口径的敏感度（防止「golden 标签本身有争议」影响结论）

| 口径 | Flash target_all | Air target_all | Flash target_nav | Air target_nav |
|---|---:|---:|---:|---:|
| golden 原标签 | 65.00% | 80.00% | 38.24% | 76.47% |
| 6 条「工作台/后台」按现行 RouteCatalog 改判 `/merchant/user` | 70.00% | 90.00% | 47.06% | 94.12% |
| 剔除 6 条有争议的「工作台/后台」 | 72.22% (39/54) | 88.89% (48/54) | 46.43% (13/28) | 92.86% (26/28) |

三种口径下 Air 都领先，navigate 子集分别 **+38.24 / +47.06 / +46.43pp**。结论不依赖「商家工作台」这 6 条怎么标。

---

## 5. Air 不如 Flash 的具体条目（Q4）

### 5.1 原始 LLM 输出层面

| ID | 问题 | Flash | Air | 期望 | Air 错在哪 | 生产是否抵消 |
|---|---|---|---|---|---|---|
| G20 | 帮我找一下商品 | ✅ `GOODS_QUERY / query_goods` | ❌ `GUIDE_NAVIGATION / navigate / /merchant/search` | `GOODS_QUERY / query_goods` | 把「找商品」当「去搜索页」 | ✅ `looksLikeGoodsQuery` 改回 `GOODS_QUERY` |
| G17 | 买家留言在哪 | ⚠️ `COMMENT_QUERY / null` | ❌ `GUIDE_NAVIGATION / navigate / /merchant/msg` | `COMMENT_QUERY / merchant_comments` | 把「留言」当消息中心 | ✅ `looksLikeCommentQuery` 改回 `COMMENT_QUERY` |
| G44 | 运费怎么算 | ❌ `GOODS_QUERY / null` | ❌ `GUIDE_NAVIGATION / navigate / /merchant/user` | `FAQ / null` | 把运费问题导航到个人中心 | ✅ `FaqKnowledgeBase` 的「运费」是 policy，LLM 前就返回 FAQ |
| G45 | 我要转人工客服 | ✅ `FAQ / null` | ❌ `UNKNOWN / null` | `FAQ / null` | 判成未知 | ✅ `UNKNOWN` 无工具 → `byRules` 命中 FAQ |
| G48 | 你能做什么 | ✅ `HELP`（raw 被白名单拒，但规则层补回 `HELP`） | ❌ `FAQ / null` | `HELP / null` | 把帮助请求答成 FAQ | ❌ 真实差异：Flash 最终 `HELP`，Air 最终 `FAQ` |
| G50 | 帮我写一首诗 | ❌ 无 JSON（3/3 直接写诗），规则层落到 `UNKNOWN` | ⚠️ `FAQ / null` | `UNKNOWN / null` | 把写诗判成 FAQ | ❌ 真实差异：Flash 的格式失败反而落到正确的 `UNKNOWN`，Air 语义错 |
| G09 | 查我的订单 | ⚠️ `ORDER_QUERY / null / —`（tool 缺） | ⚠️ `ORDER_QUERY / null / raw=/merchant/orders` | `ORDER_QUERY / merchant_orders / —` | 数据查询多带 target_path（Prompt 明确要求数据查询不要给） | ✅ 生产忽略数据查询的 target_path |

> 注：G44 的 Flash 原始输出也不对（`GOODS_QUERY`），但 Air 把 intent/tool/target 三维都丢了，所以按三维差值计为 Air 更差；生产侧两者都被 policy FAQ 提前接住，用户侧无差异。

### 5.2 把生产纠偏算进去后，真正保留的差异

按 `ChatAgentService` 当前分支人工核对（不是自动指标）：

| 类别 | 条目 | 说明 |
|---|---|---|
| Air 真实优势（生产纠偏后仍保留） | G02、G03、G04、C01、G27、C03、G31、G33、B02、G14 | 商品管理页、资产页、经营数据、发布页、商品编号；Flash 的错误不会被现有守卫修好 |
| Air 原始优势但被生产抵消 | G28、G29（`RouteCatalog.guess` 把 Flash 改到会员中心）、G34、C05（`AFTER_SALES` 守卫改 Flash）、C07（`isDirectNavigation` 把 Flash 的 GUIDE 改成 DIRECT）、G10/G11/G12/G15/C06（Flash tool=null，但 `forIntent` 会补工具）、G41/G43（policy FAQ 提前接住，两者最终都答 FAQ） | 这些是「raw 数字上有差、用户侧同效」 |
| Air 原始劣势但被生产抵消 | G20、G17、G44、G45、G09 | 同上 |
| Air 真实劣势（生产纠偏后仍保留） | G48（HELP→FAQ）、G50（诗→FAQ） | 只剩两条语义差异，不影响「Air 整体更准」的结论 |
| 双方同错 | G05–G08、C02、B03（工作台落点争议）、G36、G37（都选 `/merchant/after-sales`，golden 要 `/merchant/orders`）、G47（`GREETING` 白名单缺口） | 需要单独修路由/白名单，不是模型选择能解决的 |

**净效果**：Air 在约 10 条真实用户侧错误上更好（G02、G03、G04、C01、G27、C03、G31、G33、B02、G14），只在 2 条（G48/G50）上更差；其余差异被生产守卫抹平或双方同错。

---

## 6. 「怎么前往商家工作台」专项：这不是模型准确率问题

用户特别点名 C02/B03（以及同族 G05–G08）。A/B 结果：

| 句子 | Flash | Air | golden 期望 |
|---|---|---|---|
| 怎么前往商家工作台（C02/B03） | `/merchant/after-sales` | `/merchant/user` | `/merchant` |
| 怎么去商家工作台（G05） | `/merchant/order` | `/merchant/user` | `/merchant` |
| 我要进后台（G06） | `/merchant/user` | `/merchant/user` | `/merchant` |
| 商家控制台在哪（G07） | `/merchant/user` | `/merchant/user` | `/merchant` |
| 商家后台怎么进（G08） | `/merchant/user` | `/merchant/user` | `/merchant` |

### 6.1 三套口径互相打架

1. **golden / 语义路由 / ChatIntent 说 `/merchant`**
   - `SemanticRouteRegistry.java:47`：`MERCHANT_DASHBOARD → "/merchant"`。
   - `ChatIntent.java:52-53` 注释：「落点是商家首页，而不是个人中心」。
   - golden-set G05–G08 的 `expected_answer_or_action` 都是 `/merchant`。
2. **给 LLM 的 RouteCatalog 说 `/merchant/user`**
   - `RouteCatalog.java:61-62`：`/merchant` 是「商家首页 / 商城主页」，用户说「回首页 / 逛商城 / 看看商品」时选它。
   - `RouteCatalog.java:73-74`：`/merchant/user` 是「个人中心」，明确写着「用户说『个人中心 / 用户中心 / 我的主页 / **商家后台**』时选它」。
   - 所以 Air 输出 `/merchant/user` 是**严格照 Prompt 作答**，不是模型跑偏。
3. **前端 UI 说 `/merchant` 不是工作台**
   - `SideRail.vue:208-211`：「`/merchant` 是**公域商城主页**（与 `/customer` 同一个 `HomePage`），该页不挂身份树 ⇒ 不能把『商家工作台』指过去」。
   - 同一文件 `:188` 还留着一句旧注释「商家壳的壳首页就是商家工作台」；`:208-211` 是同日修正，且与 `RouteCatalog`、`UserInfoPage` 的实际结构一致，应以修正后的说法为准。
   - `UserInfoPage.vue:186-188`：真正的「今日工作台」面板在 `/merchant/user` 页内（`id="today-workbench"`）；`:967-969` 的「进入商家工作台」按钮就是滚到这个面板。
   - `UserInfoPage.vue:220` 的「查看商家工作台」又跳到 `/merchant/order`（`:581` 的 `orderCenterPath`）。

### 6.2 判断

- **Flash 的 `/merchant/after-sales` 在任何口径下都错**：用户问工作台，不是售后队列。
- **Air 的 `/merchant/user` 在「现行 Prompt + 前端个人中心承载工作台」口径下是对的**；在 golden/SemanticRouteRegistry 口径下是错的。
- 因此这句话**不能作为「Air 也不对」的证据**，它证明的是「商家工作台到底指哪一页」没有单一事实源。换模型不会修好它：Air 会稳定地去 `/merchant/user`，Flash 在不同同族句子上分别落到 `/merchant/order`、`/merchant/user`、`/merchant/after-sales`。

### 6.3 需要用户裁决的落点（三选一）

| 方案 | 落点 | 要改哪里 | 评价 |
|---|---|---|---|
| A（推荐） | `/merchant/user` | 改 golden G05–G08/C02/B03、`SemanticRouteRegistry` 的 `MERCHANT_DASHBOARD`、`ChatIntent` 注释；Prompt 不动 | 与现有 RouteCatalog + UserInfoPage 的「今日工作台」一致，改动最小 |
| B | `/merchant` | 改 `RouteCatalog`：把「商家后台」从 `/merchant/user` 移到 `/merchant`；同步 SideRail/UserInfoPage 文案；改前端首页语义 | 与 `SideRail.vue:208-211` 的「公域商城主页、不挂身份树」冲突，风险最大 |
| C | `/merchant/order` | 三处都改；`UserInfoPage.vue:220` 的「查看商家工作台」已经指向这里 | 与「工作台」的广义入口不一致，但和订单中心的实际动作最接近 |

**建议 A**，因为它是唯一同时满足「Prompt 现行文本、前端今日工作台面板、用户点击『进入商家工作台』的实际落点」的选项。选 A 后，Air 在这 6 条上从「golden 错」变成「全对」，Flash 仍会在 G05/C02/B03 上错。

---

## 7. 验收门槛核对

| 门槛 | 结果 | 依据 |
|---|---|---|
| 1. Air target_path ≥ Flash | **PASS** | 全部 +15.00pp；navigate 子集 +38.24pp；三种敏感度口径均领先。 |
| 2. Air JSON 合法率 = 100% | **PASS（生产格式口径）** | `json_valid` 180/180、60/60 = 100%。<br>**注意**：严格裸 JSON 是 87.78%（有 fence），完整生产解析是 95.00%（`GREETING`/`HELP` 白名单缺口）；后两者不是 Air 独有，Flash 分别为 51.11% / 95.00%。 |
| 3. Air intent / tool ≥ Flash | **PASS** | intent +18.34pp（86.67% vs 68.33%）；tool +25.00pp（93.33% vs 68.33%）。 |

**如果门槛 2 的定义是「完整生产解析必须 100%」，则 Air 不通过（95%）；但 Flash 同样 95%，且失败原因是既有 `ALLOWED` 缺口。** 修 `ALLOWED`（加入 `GREETING`、`HELP`）后 Air 即 100%，Flash 98.33%。这不是 Air 的格式回归，不能据此否决 Air。

---

## 8. 建议

1. **Air 通过本次 A/B，具备切换资格**：四项全部不低于 Flash，target_path 优势在三种口径下都成立，JSON 生产格式 100%。
2. **切换前先做两件事，不要在切换当天顺手改**：
   - 修 `ALLOWED`：把 `GREETING`、`HELP` 加进去（或从 Prompt 里删掉这两个意图，二选一，不能继续「Prompt 教了、解析器不认」）。这是既有 bug，Air/Flash 都受影响；不修的话 Air 会有 3/60 条被降级。
   - 裁决「商家工作台」落点（建议方案 A：`/merchant/user`），并同步 golden、`SemanticRouteRegistry`、`ChatIntent` 注释。否则换 Air 后这句话仍会稳定去个人中心，看起来像「换了模型还是错」。
3. **本轮不改**：线上模型、3s 超时、Prompt 压缩都不动。Air 的延迟中位数 1.04s vs Flash 3.26s 是额外收益，但本轮不以延迟为决策依据。
4. **切换前建议用未损坏 Prompt 再跑一次 60 条**：本次 Prompt 抓取把 `⇒`/`⚠️` 写成了 `?`/`??`；横向结论不受影响，但线上绝对值应以干净 Prompt 复核。
5. **不 push**。

---

## 9. 证据文件

| 文件 | 内容 |
|---|---|
| `docs/agent/evidence/chat-llm-air-ab/test-set-60.csv` | 60 条测试集：期望 intent / 可接受 intent / 期望 tool / 期望 path / 行为 / 备注 |
| `docs/agent/evidence/chat-llm-air-ab/ab-results-60.csv` | 逐条对比：两模型众数、逐维是否命中、JSON/生产解析率、一致率 |
| `docs/agent/evidence/chat-llm-air-ab/ab-results-60.json` | 原始输出、3 次 eval、usage、latency、requestId、逐条判定 |
| `docs/agent/evidence/chat-llm-air-ab/ab-summary.json` | 汇总：四项准确率、逐次、去重 56、一致率、JSON 三口径 |
| `scripts/probes/probe-chat-air-ab.py` | 可复现探针；`python scripts/probes/probe-chat-air-ab.py --runs 3`（密钥从 `_work/llm-key` 读取） |

> 复现说明：C07 的 Air 原始 `intent` 是 `DIRECT_NAVIGATION`。初版评估器让 `tool=navigate` 覆盖了它，已按生产 `withToolIntent` 的优先级修正，并只对 C07 的 3 次已存原始输出重算（未重新调用模型）；修改后的结果与重新调用应一致。
