# context-pack: C3 块 7 —— 可观测域（攻击模拟器 / 指标面板 / 状态流转 / 实时日志）+ 库存治理 {#pack-c3-block-7-observe-context-pack}

> 索引：`docs/agent/c3-blocks-index.md`。**只精读下文列出的区间，不要整份读任何文件**。
> ⚠️ **状态（2026-10-13）：pack 新出，未落码。** 本 pack 的「现状」坐标 = 块 7 开工前
> （块 6 收口、`feat/c3-admin-block6` HEAD `7c3e49f`，PR #91 待用户 merge）**实测值**，属**快照**；
> 行号随代码增删会漂 ⇒ 回指旧行号写「原 N 行」不带冒号语义、按「开工前实测」理解。
> **方案 = `docs/agent/context-packs/块7参考.txt`（588 行，用户 2026-10-13 v1.0 定稿）**。
> ⚠️ **该报告即攻击模拟器的设计档**（§十三 第 3 条逐字：「无独立档，本报告即设计档」）⇒ 本 pack
> **原样引用**它，不重写方案正文。
> **A 档 = 5 项，待用户最终批**（§「要新建 · A 档」）；**B/C 档已自裁**，落码时按此、不单独问。
> ⚠️ **本笔是 A 档批完之后的活**——A 档未批前**不动任何代码 / 表 / 契约**。

## 一句话定位 {#pack-c3-block-7-observe-s2}

管理端「可观测域」（`docs/agent/c3-modules.md`；侧边栏域键 `observe`）的 **4 个子项**
（攻击模拟器 / 指标面板 / 状态流转 / 实时日志）+ **库存治理 2 项**（TD-36 / TD-38）。三件事：

1. **看得见** —— 4 个新页把「系统正在怎么被打、怎么扛住」摊开：`AttackConsole.vue`（编排攻击）、
   `MetricsPanel.vue`（7 图实时指标）、`OrderFlow.vue`（状态流转 5 图）、`EventLog.vue`（8 类事件流）。
2. **打得动** —— `AttackEngine` **真发并发流量**（不是跑脚本、不是只显结果）：8 个预置场景，
   每个同时触发 5–6 条防护；两种编排（固定一键 / 自定义权重滑条）。
3. **扣得准** —— 把 TD-36（`goods_sku.Stock` 只存不扣）与 TD-38（`seckill_activity.SoldStock`
   只存不扣）**真做**：CAS 扣减 + 下单即扣 + 超时释放。⚠️ 这两条与「攻击模拟器」**同块**
   正是设计：攻击场景 1（秒杀风暴）与 4（超时不付款）**演示的就是这两个扣减**。

⚠️ **可观测域第 11 项「权限与审计」不属块 7** —— 已在块 1 完成（侧边栏 `audit` 子项落
`AuditPage.vue`，现成页、块 7 不动）。块 7 = 5 项里的其余 4 项。

## 依赖块 {#pack-c3-block-7-observe-s3}

- **块 4（订单块）** —— ✅ 已收口。TD-36 / TD-38 的挂账（`tech-debt.md:586` / `:604` 「与 SKU 扣减
  一起进块 7（可观测 / 治理块）」）在此结清。`OrderService.orderAdd` / `OrderTimeoutCancelJob` /
  `order_state_transition` 三处是库存治理的挂点。
- **块 5（营销块）** —— ✅ 已收口。秒杀扣减的载体 `seckill_activity`（块 5 建的表）在此第一次被**写数值**。
- **块 1（基础设施）** —— 通知 `NotificationService.send(...)`（`:120`，fail-soft）+ 审计
  `admin/service/AuditService`（fail-open），**照抄不扩展**。
- ⚠️ **块 7 不碰块 6**（`块7参考.txt:544`）：「不碰块 6 的表 / 端点 / 代码（只读它们的指标 / 事件）」
  —— 唯一的交叉是**给块 6 六表补 `Source` 列**（那是加列、不改块 6 任何一行业务代码，见 A 档 2）。

## 要读 {#pack-c3-block-7-observe-s4}

### 设计意图（读区间） {#pack-c3-block-7-observe-s5}

| 读什么 | 区间 | 一句话 |
|---|---|---|
| **方案本体（= 攻击模拟器设计档）** | `docs/agent/context-packs/块7参考.txt` **全文 588 行** | 本 pack 的**唯一权威方案**；§一 攻击模拟器 / §二 指标面板 / §三 状态流转 / §四 实时日志 / §五 库存治理 / §六 后端结构 / §七 前端结构 / §八 图表口径 / §九 契约（A/B 档）/ §十 手验 / §十一 估时 / §十二 commit 拆分 / §十三 前置 / §十四 关系 / §十五 待裁 / §十六 下一步 |
| C3 模块定义（可观测域） | `docs/agent/c3-modules.md`（可观测域一节） | 5 子项的**中文名与定义**；侧边栏项的出处 |
| 块索引 | `docs/agent/c3-blocks-index.md`（75 行） | 各块状态；块 7 那一行 |
| TD-36 | `docs/agent/tech-debt.md:580-587` | `goods_sku.Stock` 只存不扣的**全貌**（现状 / 后果 / 「未引入新的不一致」的论证） |
| TD-38 | `docs/agent/tech-debt.md:598-605` | 秒杀 `SoldStock` 同款；「与 TD-36 同源」那句 |
| 库存治理的**表契约** | `docs/agent/db-schema.md §13.3`（`SkuCode` 当 `ProductCode` 上报）· `§14.1`（`SoldStock` 手动置满） | ⚠️ **扣减的 join 键**就写在 §13.3 —— 不读这段会把 `SkuCode` 与 `ProductCode` 的换算搞错 |

### 现成接口（**照抄 / 消费，不扩展**） {#pack-c3-block-7-observe-s6}

- **指标埋点范式（唯一出处）** —— `shared/lock/MallLockMetrics.java`（**逐行读它的类注释**）：
  基数字段铁律（tag 只用**有界取值**、业务键绝不进 tag）、结果类维度统一叫 `result`、
  可派生的值不重复埋点、`EVENT_CAPACITY = 512`（`:77`，2 的幂、位与定位）。
  ⚠️ **三条「不抽共享环」的理由**（`:18-25`）**本块继续照办**——5 套新环各自独立，
  **别**去抽公共事件环（那会动已被单测钉死的 `MallCacheMetrics`）。
  另两份同构：`shared/cache/MallCacheMetrics.java`（`EVENT_CAPACITY` `:115`）、
  `order/metrics/MallOrderStateMetrics.java`（`:113`）。
- **通知** —— `notification/service/NotificationService.java:120`
  `send(batchId, toId, toType, eventType, title, content, targetType, targetId)`，fail-soft。
  ⚠️ `notification/NotificationEvents.java` 是**登记簿**（32–33 常量），**后端不校验**它 ——
  加通知类型**只加常量、不改表、不改后端**；前端标签同步 `src/config/notify-events.js`。
- **审计** —— `admin/service/AuditService`（`Action` 常量在 `admin/AuditActions.java`，276 行）。
  ⚠️ 挂钩点一律在 `success == true` **之后**；`ActorId` 取**会话**不是入参。
- **登录闸** —— `shared/web/WebAuthConfig.java`：`AUTH_WHITELIST`（`:108` 起，现 **14** 条）。
  ⚠️ **块 7 的 6 个新端点一条都不进白名单**（判据 = 「公开面」，管理端端点没有公开面）⇒
  靠 `AuthInterceptor`（注册在 `/**`）拦。**白名单维持 14，不动。**

### 现状：图表层 —— **ECharts 已在，第二层是自研薄封装**（块 7 只往下写）<br>⚠️ 下表**行数与导出名已按 3a.2 主题笔（2026-10-16）刷新过一次**（原先那份是开工前快照）；`theme.js` 那一行是**新文件** {#pack-c3-block-7-observe-s7}

| 文件 | 行数 | 状态 |
|---|---|---|
| `src/components/charts/echarts.js` | 54 | **唯一** `echarts.use(...)` 处；已注册 Line/Bar/Pie/Heatmap + Grid/Tooltip/Legend/VisualMap/AxisPointer/**MarkPoint** + CanvasRenderer；**刻意不注册** TitleComponent / UniversalTransition / LabelLayout；`NODE_ENV !== 'production'` 时挂 `window.__echarts`（**探针出口**，3a.2 加） |
| `src/components/charts/BaseChart.vue` | 195 | 底座：生命周期 + `ResizeObserver` + **三态浮层**（error > loading > empty，canvas **恒挂载**、状态是**不透明浮层**不是 `v-if`）；`init` 第二个入参 = 主题名（3a.2 起不是 `null`） |
| `src/components/charts/theme.js` | 153 | **图表面唯一的取值点**（3a.2）：`buildTheme(t)` / `registerChartTheme()` / `CHART_THEME`。承载「整站一个样」的键（色板兜底 / 文字档 / 轴与分割线 / tooltip / 图例 / `line`·`pie`·`heatmap`·`visualMap` 的默认）。⚠️ 合并序 **option > 主题 > 内置默认** ⇒ 主题只在 option 没写到的键上生效；**主题不能声明组件**（`legend`/`tooltip` 必须出现在 option 里）。3a.2b 的甲/乙差异面就落在这里 |
| `src/components/charts/chartTokens.js` | 145 | **只剩取值与参数**：色值现读 CSS 变量的 `chartTokens()` + `TEXT_SIZE` + `TOOLTIP_AXIS`/`TOOLTIP_ITEM` + `LEGEND_SHOWN`/`LEGEND_HIDDEN` + `categoryAxis(data,opts)`/`valueAxis(opts)`/`mixHex`。⚠️ `baseOption`/`axisTooltip`/`legendStyle` 已**删除**（样式搬进了 `theme.js`） |
| `LineChart.vue` | 189 | 折线（`categories` `series` `height` `smooth` `showLegend` `showXAxis` `boundaryGap` `gridLeft` `gridRight` `gridTop` `gridBottom` `yFormatter` `yMin` `yMax` `y2Formatter` `y2Min` `y2Max` `symbolLimit` `tooltipFormatter` `animation`）；`series` 支持 `area` / `dashed` / `axis:'right'`（双轴）/ `type:'bar'`（柱+线组合）/ `colorIndex` / `markPoints` |
| `BarChart.vue` | 141 | 柱状（`horizontal` `stacked` `showXAxis` `gridLeft` `gridRight` `gridTop` `gridBottom` `valueFormatter` `maxBarWidth` `labelTruncate` `colorIndex` `markPoints` `tooltipFormatter` `animation`） |
| `PieChart.vue` | 68 | 饼图 |
| `DonutChart.vue` | 103 | 环图（`centerValue` `centerTitle` `innerRatio` `outerRatio`） |
| `HeatmapChart.vue` | 123 | 热力图（`xLabels` `yLabels` `data` `max` `valueName` `legendText` `xLabelInterval` `gridLeft` `gridRight` `tooltipFormatter` `animation`）—— 指标面板「锁竞争热力图」是它的调用点（`MetricsPanel.vue:128`） |

- `package.json:13` `echarts ^5.6.0`（实装 5.6.0）；`@lucide/vue ^1.55.0`。
- **现有用法**：9 页 / **24 实例**（Line 6 · Bar 9 · Donut 8 · Pie 1 · Heatmap 0）。
  页：`admin/AccountsPage` `admin/AuditPage` `admin/CatalogPage` `admin/MarketingPage`
  `admin/ModerationPage`(8) `admin/OrderPage` `admin/StatsPage` `merchant/StatsPage` `ProductPage`。
- ⚠️ **三条硬约束**（`块7参考.txt:418-428`）：① 页面里**不许** `import echarts`；② 色值**走**
  `chartTokens.js`，不写 `#hex`；③ 新图**仍走** `BaseChart` 底座，组件只写「数据 → option」。

### 现状：可观测域前端 —— **4 页全 `AdminStub` 占位、后端零包** {#pack-c3-block-7-observe-s8}

- 侧边栏 `src/config/admin-nav.js:51-60`：`observe` 域已挂 **5** 子项
  （`attack-sim` `metrics` `timeline` `logs` `audit`），**域名 / URL / 中文名都已定稿**（不新增项）。
- 路由 `src/router/index.js:458-463`：`observe` 域壳 `:458`；**4 条占位在同处**——
  `:459` `observe/attack-sim` · `:460` `observe/metrics` · `:461` `observe/timeline` ·
  `:462` `observe/logs`，**四条 `component: AdminStub`**；`:463` `observe/audit` 已是真页
  （`AuditPage.vue`，**不动**）。块 7 = 把 `:459-462` 四条**同行替换**成真页
  （照 `:451` / `:455` 块 2–6 同一手法：**只换 `component`、不插行**）。
- ⚠️ **`views/admin/observe/` 目录不存在**（实测 `ls` = No such file）；`components/observe/` 同。
- ⚠️ **全仓 `SseEmitter` / `text/event-stream` / `WebFlux` = 0 命中**（`progress.md:95` 记 C2 未开始）
  ⇒ **块 7 是本仓第一例 SSE**（见「约束」第 1 条，这是本块最大的新面孔）。

### 现状：库存治理的挂点 {#pack-c3-block-7-observe-s9}

| 挂点 | 坐标（2026-10-13 实测） | 说明 |
|---|---|---|
| `goods_sku` 读写 | `goods/dao/GoodsDao.java`（390 行）—— 多规格矩阵段 **:135-181**（`SkuRow` `:145` · `insertSkus` `:156` · `selectSkusByMerchantGoodName` `:179`） | ⚠️ **没有** `GoodsSkuDao.java`（方案 §5.4 写错，见「要新建 · 事实更正」） |
| `seckill_activity` 读写 | `marketing/seckill/dao/SeckillDao.java`（374 行；`ACTIVITY_COLUMNS` `:95` · `ACTIVITY_TAIL` `:100`） | ⚠️ **没有** `SeckillActivityDao.java`（同上） |
| 下单挂点 | `order/service/OrderService.java`（1205 行）—— `orderAdd` `:256`；订单落库 `:517`；`writeItems` **:533**；`ItemSpec` `:640`；`resolveItems` `:651` | 扣减排在 `writeItems` **之后**（明细已解析出 `code` + `qty`） |
| 超时释放挂点 | `order/job/OrderTimeoutCancelJob.java`（97 行）—— 构造函数 `:63`（**只注 `OrderDao`**）；`@Scheduled` `:71`；`candidates` `:81`；`cancelIfUnpaid` **:89** | 释放排在 `:89` 那个 `== 1` 分支**之内**；⚠️ 构造函数要**加注两个 DAO** |
| 表列 | `goods_sku.Stock` `.sql:1070`（注释「本轮只存不扣」）· `seckill_activity.TotalStock/SoldStock` `.sql:1377-1378` | 两列都在，语义待激活 |
| 订单明细列 | `order_item`（`.sql`，`ProductCode` 列注「对齐 `goods.ProductCode`」） | ⚠️ 矩阵商品下 `ProductCode` **装的就是 `SkuCode`**（`db-schema.md §13.3`） |

### 样板（照抄真页，别自创风格） {#pack-c3-block-7-observe-s10}

- **管理端真页**：`src/views/admin/AuditPage.vue`（33.9 KB，**同域、最近邻**，块 7 的 4 页照它的
  壳 / 卡片 / 表格风格）；`src/views/admin/StatsPage.vue`（27.9 KB，**图表页样板**——7 图照它排）。
- **事件环类**：`shared/lock/MallLockMetrics.java`（逐行读，5 套新环**结构照抄、内容换域**）。
- **`api/*.js` 薄封装**：`src/api/stats.js` / `src/api/notify.js`（新 `api/observe.js` 照它们）。
- **建表手法**：`mall_management_sql.sql` 尾部 Migration 块（⑮ `:1508-1634` 是 6 张表的样板；
  `information_schema` 守卫 + `PREPARE`/`EXECUTE`，照 ⑬ `:1471-1477` / ⑯ `:1641-1686`）。

### 表 / 前端入口 / 登记 {#pack-c3-block-7-observe-s11}

| 面 | 坐标 | 块 7 动作 |
|---|---|---|
| 建表脚本 | `mall_management_sql.sql`（**1686 行**，尾 = ⑯-2 到文件末） | **追加** Migration **⑰**（见「要新建 · 表」） |
| A3 门禁 P3 | `scripts/concurrency/ConcurrencyRunner.java`：P3 段 `:2637-2671`、IN 清单 `:2660-2663`、`KNOWN_TABLES` **:2664**、期望串 `:2666-2671` | **四处同改**：清单 + `KNOWN_TABLES` + 表数说明（`:2647` 那句）+ PASS 串 ⇒ **25 → 26**、期望 **36 → 37** |
| 后端契约 | `docs/agent/api-contract.md`（733 行；附录 G `:617` 起，尾在 `:733`） | **追加附录 H**（可观测域 6 端点）；正文 §2 矩阵**不重测**（同附录 G 口径） |
| 前端端点表 | `src/api/endpoints.js`（379 行，尾 = `sensitive` 组） | **追加** `observe` 组（6 条）；头注「现为 N 条」同步 |
| 表结构文档 | `docs/agent/db-schema.md`（928 行） | 加 `drill_record` 一节 + `goods_sku.BaselineStock` / `seckill_activity.BaselineSoldStock` 加列说明 |
| 迁移登记 | `docs/agent/db-migration.md`（221 行；登记表尾行 = **37**） | **追加行 38–41**（⑰-1 drill_record 建表；⑰-2/3 加基线列；⑰-4..9 Source 补列，可合并登记为 1–3 行，按最终笔数） |
| 进度登记 | `docs/agent/progress.md`（2278 行） | 追加 `§7-B7`（+ 各 commit 子节） |
| 槽位登记 | `docs/agent/slot-registry.md`（4887 行） | 追加「§二十四 块 7」 |
| 技术债 | `docs/agent/tech-debt.md`（817 行；TD-36 `:580`、TD-38 `:598`） | **TD-36 / TD-38 状态改判**（`⏸ 登记不修` → 落实）；新增 TD（SSE 首例的已知边界等） |

## 要新建 {#pack-c3-block-7-observe-s12}

### A 档（**开工前必批 —— 5 项，待用户最终批**） {#pack-c3-block-7-observe-a}

> 出处 = `块7参考.txt §九` + §十五；本 pack 已把 §十五 1–5 逐条核过（见各项「核对」）。
> ⚠️ 第 3 项**已核出与台账不符**，按用户 2026-10-13 批文更正。

1. **新表 `drill_record`（演练记录）** —— 逐字照 `块7参考.txt:86-103` 的 12 列：
   `Id bigint PK AI` · `OperatorId varchar(40)`（业务号）· `OperatorRole tinyint` ·
   `Name varchar(64)` · `Scenario varchar(500)`（场景配置 JSON）· `TotalRequests int` ·
   `BlockedRequests int` · `FailedRequests int` · `DurationMs bigint` ·
   `Result mediumtext`（长文本，**后端拆接给前端**）· `created_at` · `updated_at`。
   **落点** = Migration **⑰-1**，追加在 `mall_management_sql.sql:1686` 之后。
   **P3 门禁连带** = `KNOWN_TABLES` **25 → 26**、期望表数 **36 → 37**（四处同改，见上表）。
   ✅ **核对**：与既有建表模板（尾部 Migration 块 + `IF NOT EXISTS` + `information_schema` 守卫
   + 规则 7/8 的 `created_at`/`updated_at`）一致；`OperatorId varchar(40)` 与本仓业务号列宽一致。
   ⚠️ **建议（可并入本项）**：`Result` 用 `mediumtext` 长文本、**后端解析**分字段——这条是方案
   明确要求（`块7参考.txt:102` 「不是为了展示原始长文本，是为了分字段显示」）⇒ 不加列、不分表。

2. **补列（两组，一次批清）** ——
   - **(a) 块 6 六表补 `Source`**（口径同块 5 的 11 表）：`comment` / `comment_image` /
     `comment_like` / `comment_mention` / `report` / `sensitive_word`。
     ✅ **核对（实测）**：块 5 可视化段**只**给 11 张表加过 `Source`
     （`login`/`user`/`goods`/`goods_audit`/`order`/`order_item`/`cart`/`notification`/`admin_audit`/
     `merchant_application`/`stats_daily`，见 `.sql:1102-1218`）；**块 6 六表全缺** ⇒ 缺口坐实。
     用途：攻击场景 8（敏感词攻击）会写 `comment` / `sensitive_word` ⇒ 需要按 `Source='attack'` 区分。
   - **(b) 两张库存表加「基线列」**（**不是** `Source` —— 语义错位：行不是攻击创建的，只是**数值
     被改**）：`goods_sku` 加 `BaselineStock int NULL`；`seckill_activity` 加 `BaselineSoldStock int NULL`。
     **用法（演示三段 + 收尾）**：
     ```
     ① 演示前：UPDATE goods_sku SET BaselineStock = Stock WHERE BaselineStock IS NULL
     ② 演示中：攻击改 Stock，BaselineStock 不动
     ③ 演示后：UPDATE goods_sku SET Stock = BaselineStock
     ④ 演示结束：UPDATE goods_sku SET BaselineStock = NULL
     ```
     秒杀表同款（`BaselineSoldStock`）。**好处**：恢复只需一行 SQL、不用算「这次演练扣了多少」；
     跑十次攻击也能一键恢复，演示前后库存完全一致。
     **落点** = Migration **⑰-2 / ⑰-3**（`information_schema.COLUMNS` 守卫 + `PREPARE`，照 ⑬）。
     ⚠️ **回滚口径**（规则 v2）：`BaselineStock`/`BaselineSoldStock` 的**合规则回滚 = 保留列 +
     停用**，`DROP COLUMN` 只在本地库重建时用、执行前须人工确认（同 ⑬ / ⑯ 口径）。
   ⚠️ 两组列**不改任何业务代码**（块 6 六表加列不动其 DAO；两张库存表加列不动其读写 SQL）⇒
   属「加列」而非「改契约语义」。

3. **端点计数 `+6`（计数已更正）** —— 新增 **6** 个端点（3 SSE + 3 攻击）。
   ⚠️ **方案原文写「14 → 20」是笔误**（用户 2026-10-13 批「选 1」更正）。按**真实台账**记：
   | 台账 | 现 | 块 7 后 | 判据 |
   |---|---|---|---|
   | 后端 method-level | **134** | **140**（+6） | `grep -rhE "^\s*@(Post\|Get\|Put\|Delete)Mapping" --include=*Controller.java src/main/java \| wc -l`（实测工作区 **134**；提交态 HEAD 仍 133，差的那 1 条是块 6 的 `adminList`） |
   | 前端 `endpoints.js` | **133** | **139**（+6） | `grep -cE "^[[:space:]]*[A-Za-z0-9_]+:[[:space:]]*'/" endpoints.js` = 131 行 + 2 处同行两条 |
   | `AUTH_WHITELIST` | **14** | **14**（不变） | 白名单判据 = 「公开面」；6 个新端点无一是公开面 |
   出处同 `api-contract.md:627-629`（块 6 那一行）。

4. **库存扣减时机 = 下单即扣（B5 丙）** —— 三拍：① 下单锁库存（`orderAdd` 进来就扣）；
   ② 支付确认（**保留现状**）；③ 超时释放（`OrderTimeoutCancelJob` 里加「释放库存」；C5 甲：
   **复用现有 job，不新加 `StockReleaseJob`**）。CAS 语句照 `块7参考.txt:296-297`。
   ⚠️ **必须写进代码注释的边界（本条核对新增）**：扣减按 `goods_sku.SkuCode`，
   而订单链路装的是 `order_item.ProductCode` / `ItemSpec.code` —— 二者在**矩阵商品**下相等
   （`db-schema.md:732`：加购时 `SkuCode` 作为 `ProductCode` 上报），但**非矩阵商品没有 `goods_sku` 行**
   ⇒ CAS **影响 0 行**，此情形 = **no-op，不是失败、不挡单**（同 `writeItems` 的 fail-open 精神）。

5. **8 场景清单（含每场景的防护组合）** —— 逐字照 `块7参考.txt:62-75`：
   ① 秒杀抢购（库存扣减 + CAS + 唯一索引 + 锁）② 订单并发（出生闸 + 价格闸 + 锁 + CAS）
   ③ 重复提交（幂等 + 唯一索引 + 锁）④ 超时不付款（超时取消 job + 库存释放）
   ⑤ 缓存冷启（单飞 `sync=true` + 负缓存）⑥ Redis 断开（冷却窗口 + fail-open）
   ⑦ 封禁用户攻击（封禁闸 + 身份校验）⑧ 敏感词攻击（两阈值 <40% 打码 / ≥40% 拒收 + 图片先审）。

### B 档（自裁，已报备 —— 不落硬线，可落码） {#pack-c3-block-7-observe-b}

| 项 | 自裁 | 理由 |
|---|---|---|
| **SSE 客户端** | **不用 `EventSource`，改 `fetch` + `ReadableStream` 手工解析 `text/event-stream`** | ⚠️ 本仓**管理员身份只能由 token 承载**（`AuthInterceptor.java:178-180`：`role=3` 的 cookie 会话被折成未登录）⇒ 必须发 `Authorization: Bearer` 头；而 `EventSource` **不能带自定义头** ⇒ `EventSource` 在默认 profile 下**必然 401**。**这不是口味选择，是被鉴权锁死。** |
| **SSE 方法** | 三端点一律 **`POST`** | 顺本仓管理端读端点惯例（`块7参考.txt:357`）。因客户端已是 `fetch`，「GET vs POST」不再受 `EventSource` 约束 ⇒ 自由，取 POST |
| **SSE 频率 / 环容量** | **1 秒 / 次** · **2048 槽** | 沿用方案建议（`块7参考.txt:460-461`）；2048 = 既有三环同值（**原 512**；⚠️ **2026-10-15 块 7 commit 10b 按裁决 2 改**，且改的是**九个**容量常量〔八源环 + 合并环〕，不止这三环） |
| **攻击数据标记** | **`OrderId` 前缀 `SIM-`**（C2 甲） | 沿用（`块7参考.txt:79`）：一半数据被 job 拦截、一半跳过 job 注入 |

### C 档（自决 —— 落码时按此，不单独问） {#pack-c3-block-7-observe-c}

- 新包 `com.minke.mallmanagement.observe`（controller / service / dao / metrics 四子包）。
- 4 个新页的**页内布局**照 `AuditPage.vue` / `StatsPage.vue`；卡片 / 表格复用既有 CSS 令牌。
- 5 套新事件环**各自独立**（不抽公共环，理由同 `MallLockMetrics` 类注释）。
- `drill_record.Result` 的**拆字段格式**由 agent 定（JSON 字符串，后端 `Result` 解析后回扁平行给前端）。

### 事实更正（方案里两处与仓不符 —— 落码时按此，不按方案原文） {#pack-c3-block-7-observe-corr}

1. **§5.4「改造点」的两个类名不存在**（实测 `find` = 0 命中）：
   - `goods/dao/GoodsSkuDao.java` ⇒ 实为 **`goods/dao/GoodsDao.java`**（多规格矩阵段 `:135-181`）。
   - `marketing/seckill/dao/SeckillActivityDao.java` ⇒ 实为 **`marketing/seckill/dao/SeckillDao.java`**。
   ⇒ `deductStock` / `releaseStock` 追加到 `GoodsDao.java` **文件尾**（390 行后）；
   `deductSoldStock` 追加到 `SeckillDao.java` **文件尾**（374 行后）——
   ⚠️ 追加在**最大被引用行之后**以压住行号漂移（本仓既有纪律）。
2. **§5.1 CAS 的 join 键边界**（见 A 档 4 的 ⚠️）：非矩阵商品 = 0 行 no-op。

### 表（**待 A 档批后定稿** ⇒ 追加 `mall_management_sql.sql` 末尾，`IF NOT EXISTS`，`information_schema` 守卫，规则 7 / 8） {#pack-c3-block-7-observe-sql}

Migration **⑰**（追加在 `.sql:1686` 之后）：
- **⑰-1** `drill_record` 建表（12 列，见 A 档 1）。
- **⑰-2** `goods_sku` 加 `BaselineStock int NULL`（守卫 + `PREPARE`，照 ⑬ `:1471-1477`）。
- **⑰-3** `seckill_activity` 加 `BaselineSoldStock int NULL`（同上）。
- **⑰-4 … ⑰-9** 六表各补 `Source varchar(16) NOT NULL DEFAULT ''`（**逐表一个守卫**，
  照 `.sql:1135-1218` 那 11 张的手法逐条复制；不写循环——守卫必须能单独重跑）。

### 后端（**建议**分层 —— B 档，报备） {#pack-c3-block-7-observe-s18}

```
observe/
  controller/  AttackController · MetricsStreamController · OrdersStreamController · EventsStreamController
  service/     AttackEngine（8 场景 + 权重 + 并发 + 汇总）· DrillRecordService · EventBroadcaster
  dao/         DrillRecordDao（drill_record 表）
  metrics/     StockMetrics · AuditMetrics · NotifyMetrics · SensitiveMetrics · IdentityMetrics
```
**库存改造（改现有文件）**：
- `goods/dao/GoodsDao.java` —— 尾部加 `deductStock(skuCode, n)`（CAS）+ `releaseStock(skuCode, n)`。
- `marketing/seckill/dao/SeckillDao.java` —— 尾部加 `deductSoldStock(id, n)`。
- `order/service/OrderService.java` —— `orderAdd` 内、`writeItems`（`:533`）**之后**调 `deductStock` 逐项。
- `order/job/OrderTimeoutCancelJob.java` —— `:89` 的 `== 1` 分支内调 `releaseStock`；⚠️ **构造函数加注 DAO**。

### 前端（三端 —— 每页**三件齐**） {#pack-c3-block-7-observe-s19}

**新建**：
- `src/views/admin/observe/`（新目录）：`AttackConsole.vue`（编排 + 跑 + 演练记录）·
  `MetricsPanel.vue`（7 图）· `OrderFlow.vue`（5 图）· `EventLog.vue`（5 图 + 卡片流）。
- `src/components/observe/`（新目录）：`KpiCard.vue`（大数字 + 趋势箭头）· `WeightSlider.vue`（权重滑条）·
  `StatusFlow.vue`（订单进度条，**非 ECharts**，自绘）· `EventCard.vue`（事件卡片）。
- `src/api/observe.js`（薄封装，照 `api/stats.js`）。
- 图表组件**复用** `components/charts/*`（见「要读 · 现状：图表层」）——**不新建图组件**，
  除非真需要新图型（仪表盘 / 雷达 / 漏斗 / 散点）⇒ 那时**只**在 `echarts.js` 加一行 `use` + 一张新图。

**改现有**：
- `src/router/index.js:459-462` —— 四条 `AdminStub` **同行替换**为真页（净零行数）。
- `src/api/endpoints.js` —— 尾加 `observe` 组 6 条 + 头注计数同步。
- ⚠️ **侧边栏 `admin-nav.js` 不动**（5 子项已定稿，`块7参考.txt:384`「保持 4 项」指**落页的是 4 项**，
  审计项已在）。

## 约束 {#pack-c3-block-7-observe-s20}

1. **SSE 是本仓第一例**（`SseEmitter` / `text/event-stream` = 0 命中）。三条必须写清：
   - **客户端 = `fetch` 流**（不能用 `EventSource`，见 B 档）⇒ **无内置自动重连**，断线重连要自己写。
   - **无 dev proxy**（`vue.config.js` 无 `proxy`，前端走 `VUE_APP_API_BASE` 直连 8088 + CORS）
     ⇒ 少了代理缓冲风险，多一条 **CORS 预检**（POST + `Authorization` 头 = 非简单请求）。
   - **TD-20（静默失败路径）本块顺手补**（`块7参考.txt:549-550`）：SSE 断连 → 前端有提示 /
     推流停止 → 有告警 / 指标拉不到 → **不静默**。
2. **`AUTH_WHITELIST` 维持 14 不动**（6 个新端点全在身份面，靠 `AuthInterceptor` 拦）。
3. **不降级任何防护**（本块是「演示防护」，任何场景不得为了「好看」把闸关掉；闸的表现要**真**）。
4. **库存 CAS 必须真并发安全**：`Stock >= ?` / `SoldStock + ? <= TotalStock` 是防超卖的**唯一**判据；
   `rows == 0` 在**超卖**情形 = 拒单（真失败），在**非矩阵商品**情形 = no-op（不挡单）—— 两者**必须分开判**，
   不许统一成「0 行就失败」或「0 行就成功」。
5. **图表三约束**：页面不 `import echarts`；色值走 `chartTokens.js`；新图走 `BaseChart`。
6. **块 7 不改部署配置**（`块7参考.txt:548`）；演示环境 = **dev profile**（job 全开，
   TD-35 已拆 a3 / dev）。⚠️ **dev 下 `app.auth.enabled=false` ⇒ 鉴权三档验不了，需默认 profile**
   （`块7参考.txt:25`）—— 手验记录里按既有边界标注。
7. **建表 / 改契约 / 删文件**是硬线 ⇒ 只走 A 档批过的范围；A 档未批前不动代码。

## 验收 {#pack-c3-block-7-observe-s21}

⚠️ **手验 = 用户亲自跑，判定权在用户手里**（本仓既定口径）。agent 只交**最小收验**（编译 / 测试 /
构建 / eslint / 接口能返回 / 库有变化），**不写「手验全部通过」**。用户手验清单见 `块7参考.txt §十`：

- **攻击模拟器**：固定一键跑 8 场景各看防护生效 · 自定义编排拖权重再跑 · `drill_record` 有行。
- **指标面板**：QPS 涨 / 命中率跌 / 图实时动 · 锁竞争热力图**有颜色** · 库存水位进度条随扣减变化。
- **状态流转**：订单列表实时跳 · 状态机图对应边亮 · 点单看时间线。
- **实时日志**：8 类事件都在刷、颜色区分 · 过滤条可用 · 卡片不是纯文本。
- **库存治理**：SKU 扣减（下单后 `Stock` 减）· 秒杀扣减（抢购后 `SoldStock` 增）·
  超时释放（取消后 `Stock` 加回）· **防超卖（并发抢购只 M 人成功）**。
- **全站联动**：跑攻击时四面板同时活（**分页**看，不是同屏）。

**agent 最小收验（每笔 commit 后）**：`./mvnw -B test` 绿 + 前端 `vue-cli-service build` DONE +
`npx eslint src` exit 0 + 相关探针 EXIT 0 + （触库的）只读 SQL 读数。
**A3 门禁**：`continue-on-error` ⇒ job 颜色**无信息量**，必须**拉产物**读 `gates.count` / P3 表数
（期望 **37**）。

## 十二 · commit 拆分（**9 笔**，逐字照 `块7参考.txt:517-526`） {#pack-c3-block-7-observe-commits}

| # | 内容 |
|---|---|
| 0 | 建表（`drill_record`）+ `Source` 补六表 + 两张库存表加基线列 + **门禁表数 +1**（P3 四处同改 25→26 / 36→37） |
| 1 | 库存治理（TD-36 / TD-38：CAS 扣减 + 下单即扣 + 超时释放） |
| 2 | 5 套新事件环（Stock / Audit / Notify / Sensitive / Identity） |
| 3 | 3 个 SSE 端点 + 事件广播器（`EventBroadcaster`） |
| 4 | 攻击引擎（8 场景 + 权重 + 演练记录 `DrillRecordService` / `DrillRecordDao`） |
| 5 | 攻击模拟器页（`AttackConsole.vue` 编排） |
| 6 | 指标面板页（`MetricsPanel.vue` 7 图，含 `HeatmapChart` 首次启用） |
| 7 | 状态流转页（`OrderFlow.vue` 5 图） |
| 8 | 实时日志页（`EventLog.vue` 5 图 + 卡片流） |
| 9 | 收口（文档 + 假数据 + 手验清单） |

⚠️ **提交卫生**：`commit -F` 传文件、`git add` 具体文件（不用 `-A`）、提交后 `cat -n` 抽查重复段。
⚠️ **行号漂移**：任何增删行都先摸该文件**最大被引行**，把新代码追加在其后（本仓既有纪律）。

## 十五 · 待裁项（**A 档 5 项，报用户批**） {#pack-c3-block-7-observe-pending}

1. `drill_record` 表结构（§1.5 / A 档 1）—— ✅ 核对通过。
2. **补列两组**：块 6 六表补 `Source` + 两张库存表加基线列（A 档 2）—— ✅ 缺口坐实；基线列用法已定。
3. 端点 `+6`（**计数更正为 134 → 140 / 133 → 139 / 白名单不变**，A 档 3）—— ✅ 已更正。
4. 库存扣减时机 = 下单即扣（B5 丙）（A 档 4）—— ✅ 核对通过 + 补 0 行 no-op 边界。
5. 8 场景清单（§1.3 / A 档 5）—— ✅ 逐字照。
6. ~~SSE 端点用 GET 还是 POST~~ —— **已自裁 POST**（B 档，见 B 档表）。

## 十六 · agent 下一步 {#pack-c3-block-7-observe-next}

1. **本 pack 出毕** ⇒ 把 A 档 5 项**报用户最终批**。
2. A 档批完 → 按 §十二 的 **9 笔 commit** 顺序落地。
3. ⚠️ **别跳过 pack；别擅自开工**（A 档未批前不动代码）。
4. ⚠️ 图表库已在（ECharts 5.6.0 + 自研薄封装），**不要再提「引 ECharts」**。

## 扩展阅读（不够时按指针读） {#pack-c3-block-7-observe-s22}

- 方案全文：`docs/agent/context-packs/块7参考.txt`。
- 指标埋点范式：`shared/lock/MallLockMetrics.java` 类注释（全仓唯一出处）。
- 建表 / 加列手法：`mall_management_sql.sql` 的 ⑬ / ⑮ / ⑯ 三段。
- A3 门禁：`scripts/concurrency/ConcurrencyRunner.java:2637-2671` + `context-packs/concurrency-test.md`（P0–P7）。
- 块 4（订单，库存治理的宿主）：`context-packs/c3-block-4-order.md`。
- 块 5（秒杀表）：`context-packs/c3-block-5-marketing.md`。
