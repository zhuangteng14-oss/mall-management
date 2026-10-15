# context-pack:「防护加固」段（**D 段**）—— 补救层 + 边界六项 + 收口 {#pack-c3-block-d-hardening}

> 索引：`docs/agent/c3-blocks-index.md`。**只精读下文列出的区间，不要整份读任何文件。**
> ⚠️ **本段不是 8 块之一** —— 块表（块 1–8）里没有它。它是**块 7 与块 8 之间**插入的一段加固，
> 由用户 2026-10-15 的**重排期批文**定名「**D 段**」；它没有独立分支，工作继续落在
> `feat/c3-admin-block7` 上（块 7 收尾 commit 1 之后接着 commit 2/3/4）。
> ⚠️ **状态（2026-10-15 更新）：规划文档；①②③⑤⑥ 已由 D 段 commit 2 落码**（④ 重试**原**只登记 **TD-74** —— ⚠️ **2026-10-15 阶段 2b 已把它落码并关闭 TD-74**，接闸口径由「**3 键接闸 + 1 键登记 TD + 1 键记录位**」变为「**4 键接闸 + 1 键记录位**」〔`retry` 接上〕，详见尾部〈阶段 2b 追加〉；`degrade` 仍只作**记录位**；边界四项与演示联动归 commit 3）—— 批文 ⑥ 逐字：「D 段 pack（规划，**不写代码**）出
> `docs/agent/context-packs/c3-block-D-hardening.md` · 行号实测」。本 pack 即该产物。
> ⚠️ **「现状」全是快照**：取点 = `4d75eec`（块 7 收尾 commit 1 落码前的 HEAD）+ 本笔工作区实测。
> 行号随代码增删会漂 ⇒ 回指旧行号按「取点当时实测」理解。**引用活文档一律写 `文件.md#锚点`；
> 引用代码写 `文件:行号`（快照，漂了不追 —— 见 `agent.md`「文档三层治理」）。**

## 一句话定位 {#pack-c3-block-d-hardening-s2}

把块 7 造出来的「攻击模拟器」从**只会打分**变成**真的挨了打还会接住**：块 7 已经能让后端被打
（8 个预置场景 + 自定义权重、真发并发流量、真把库压出计数），但**被打了之后没有任何补救** ——
限流、熔断、降级、重试、分池五个开关**在取点当天只记录不实现**（`AttackEngine.java:325` 起那段类注释、
`:1652` 起那句回显文案）。D 段做的就是把那五个名字**接上真闸**，外加四件「边界」活
（SSE 连接保护 / MQ 幂等与 DLQ 出口 / 无上限列表 / 敏感字段输出），最后收口出一份**块 7 + D 段合并**的手验清单。

**一句话判据**：`120 稳 · 400 吃紧 · 拉满饱和` 这条曲线**今天就走得出**（块 7 已验）；
D 段要的是**它的下半场** ——「关掉防护 + 注入故障 ⇒ 崩；打开防护 + 同一故障 ⇒ 恢复」也能走出来。

## 本段的来处与切分 {#pack-c3-block-d-hardening-s3}

用户的**重排期批文**（2026-10-15）把「块 7 欠账 + D 段」一起排成 **4 笔 commit**，手验**压成一轮**（commit 4 之后）：

| # | 一笔是什么 | 批文要点（逐字压缩） | 估时 |
|---|---|---|---|
| **1** | 块 7 **收尾**（演示完整化 + D pack） | ① 回放 ② 模拟日常 ③ 进度 6 数（`/attack/status`）④ 三块预览（**留 TODO 登记**）⑤ 3 txt 入库 ⑥ **D pack（本文件）** ⑦ 文档补 ⑧ refs 重指 | 2–3 天 |
| **2** | D 段 **后端防护** | ① 事件环骨架（**第 9 类「防护」**）② 熔断（Resilience4j · 错误率 50% / 30s 窗口 · 半开 30s 后放 5 个）③ 限流（Resilience4j · **阈值 200 QPS**）④ 重试（Spring Retry · **只对死锁类** · 2–3 次）⑤ 线程池分池（订单 / 事件 / SSE / MQ）⑥ 单元测试 | 3–4 天 |
| **3** | D 段 **边界 + 演示联动** | ① SSE 连接保护（连接上限 + 空闲超时）② MQ 幂等 + DLQ ③ 大表 LIMIT 默认 100 ④ 数据脱敏（手机号 / 邮箱 / 地址）⑤ 攻击模拟器更新（场景判据 + 开关面板接功能）⑥ 攻防联动演示（120 / 400 / 拉满 · 关防护崩 / 开防护恢复）⑦ ~~多进程实验~~**已移出本笔** | 2–3 天 |
| **4** | **收口 + 块 8 前置** | ① 文档统一补 ② refs 重指 ③ **手验清单（块 7 + D 段合并一份，三列式，profile 标清）** ④ 假数据（演示用种子）⑤ 块 8 前置清单（Nginx / 启动脚本 / 部署文档的「输入清单」+ JVM `-Xms/-Xmx` 建议） | 1–2 天 |

> ⚠️ **⑦ 多进程实验为什么被移出**（批文逐字理由）：本地化后 DB 从 VM 迁本机 ⇒ QPS 会变 ⇒ 现在做，
> 结论会被本地化**推翻** ⇒ 等本地化后并入 **QPS 验证阶段**一起做。
> ⚠️ **块 7 收尾那一笔（commit 1）的登记不在本 pack** —— 见 `progress.md` 末节的
> `#### 7-B7-收尾`（含 ④ 三块预览的 TODO 挂账）。

## 四条硬边界（**先读这一节，再读别的**）{#pack-c3-block-d-hardening-s4}

| # | 边界 | 逐字口径 | 为什么（写在明处的理由） |
|---|---|---|---|
| 1 | **只加「补救」，不加「加固」** | 限流 / 熔断 / 降级 / 重试 / 分池 = 补救；扩池 / 扩线程 / 优化慢 SQL = 加固 | 演示需要一条**有上坡的曲线**。后端被顺手加固了，`拉满饱和` 就压不出来 |
| 2 | **不动 HikariCP / 不动 Tomcat 线程 / 不优化慢 SQL** | 与 10b/10c 的「边界 A」**同一条**（`AttackEngine.java:188` 那句回显： 「**不是加固后端**（硬边界 A：连接池 / Tomcat 线程 / 慢 SQL 一律不动）」） | 后端慢的地方照旧慢 —— 那是 10c-1「瓶颈定位」的**观察对象**，不是待修项 |
| 3 | **保留瓶颈** | 瓶颈是演示的**触发源** | 同上：把触发源修掉，攻防联动就没戏可演 |
| 4 | **不改既有响应的语义** | 唯一例外 = ③④ 两项（见下），且**必须先裁** | 本仓向后兼容是硬线；脱敏与「默认 100」都会改**用户看得见的行为** |

## 要读 {#pack-c3-block-d-hardening-s5}

| 读什么 | 区间 / 标识 | 一句话 |
|---|---|---|
| **批文本体（唯一权威）** | 用户 2026-10-15 重排期批文（commit 2/3/4 三段逐字）；工作副本 `_work/t-replan.txt` | 本 pack 的**唯一方案来源** —— D 段没有第二份设计档 |
| 块 7 的攻击引擎现状 | `context-packs/c3-block-7-observe.md`（`pack-c3-block-7-observe-s12` 起「要新建」） | 引擎 v2 的形状（异步、32 派发线程、在途许可、`Pacer`） |
| 攻击面契约 | `api-contract.md#api-app-h`（附录 H） | `/admin/observe/attack/*` 五出口 + 三条 SSE + 出站头 `X-Drill-Batch` |
| 台账表契约 | `db-schema.md#db-16-1`（`observed_event` 逐列口径）· `mall_management_sql.sql:1866`（它的 DDL）· `db-schema.md#db-13-8`（演示种子口径） | 幂等键要落在哪张表、今天有哪些列 |
| MQ 链路 | `observe/mq/RabbitObserveConfig.java`（类注释 + `:113-139`）· `observe/mq/ObservedEventConsumer.java`（类注释 + `:155-180`） | 拓扑、DLQ、重试**都已经有**；缺的是另外两件 |
| 池的先例（**照抄它，别自创**） | `order/model/OrderStateExecutor.java`（读类注释「为什么是显式 ThreadPoolExecutor，不是 `@Async`」）· `application.properties:95-112` | 本仓**唯一**一处自建池：显式 `ThreadPoolExecutor` + 有界队列 + 自定义丢弃策略 + 启动打印生效值 |
| 指标埋点范式 | `shared/lock/MallLockMetrics.java`（类注释） | tag 只用有界取值、结果维度统一叫 `result`、可派生的不重复埋点 |
| 既有挂账（D 段接手的） | `tech-debt.md` 的 **TD-18** / **TD-64** / **TD-71** | 见下「待裁项」 |
| 演示剧本 | `context-packs/块7参考.txt`（§一 攻击模拟器 / §十 手验） | 「120 / 400 / 拉满」三档的原始口径 |

## 现状实测 · 八项 {#pack-c3-block-d-hardening-s6}

> 全部坐标为**实测快照**（取点见文首）。⚠️ 这一节是 D 段落码时**唯一**要信的现状表 ——
> 批文里与它冲突的地方，看「前提更正」一节（`#pack-c3-block-d-hardening-corr`）。

### ① 依赖面 —— 三个新依赖**一个都没有** {#pack-c3-block-d-hardening-s7}

- `Mall-management-server/pom.xml` 实测：**无** `resilience4j-*`、**无** `spring-retry`、
  **无** `spring-boot-starter-aop`（`grep -n "aop\|retry\|resilience" pom.xml` **零命中**）。
- `src/` 实测：`CircuitBreaker` / `RateLimiter` / `EnableRetry` / `Retryable` **零命中**。
- ⇒ 批文的 ②③④ 三项**都要先加依赖**（Resilience4j 两项 + Spring Retry 一项 + AOP 底座）
  —— 这是**一条 A 档**（批文已点：「A 档 新依赖（Resilience4j / Spring Retry / AOP）」）。
- ⚠️ 依赖要按本仓惯例**追加在 `pom.xml` 依赖段末尾**：该文件被全仓以 `file:行号` 互引
  （`:40` / `:51` / `:72` 等），**插在中间会让其后所有引用集体偏移**（`pom.xml:150-159` 的注释逐字记过这条）。
- ⚠️ 现成拓扑先例：MQ 那一族（`spring-boot-starter-amqp`，`pom.xml:159-162`）就是「只引 starter、
  不单独引 `spring-rabbit`」的写法，且**刻意追加在段末** —— 照办。

### ② 线程与池 —— 全仓只有**一处**自建池 {#pack-c3-block-d-hardening-s8}

| 是什么 | 坐标 | 形态 |
|---|---|---|
| **唯一自建池**：订单状态异步出口 | `order/model/OrderStateExecutor.java:79`（字段）· `:100`（`new ThreadPoolExecutor`）· `:203`（自定义丢弃策略） | 显式 `ThreadPoolExecutor` + `ArrayBlockingQueue`（`:99`）+ 核心/最大/队列全**可配**（`:90-93` 读 `app.order.state.async.*`） |
| 就地的临时池（锁场景演示） | `shared/lock/LockSceneService.java:181` | `Executors.newFixedThreadPool(tasks.size(), recorder)`，**用完即关**（`:426 shutdownAndDetectLeak`） |
| 常驻探测线程（MQ 发布侧） | `observe/mq/RabbitEventPublisher.java:140`（起）· `:157`（daemon）· `:165`（interrupt） | `new Thread`，**不是池** |
| 攻击引擎派发线程 | `observe/service/AttackEngine.java:930` | 每个派发键 `new Thread`，线程数 = `WORKER_THREADS`（`:215` = 32）；**不是池** |
| **Tomcat 工作线程** | **未配置** —— 三个 `application*.properties` 里**没有任何** `server.tomcat.*` | 吃 Spring Boot 默认（200）；只在注释里被引用（`AttackEngine.java:155` / `:188` / `:621`） |
| **Rabbit 消费并发** | **未配置** —— 无 `spring.rabbitmq.listener.*`；工厂也**没有** `setConcurrentConsumers` | 吃容器默认（**单**消费者线程），见 `RabbitObserveConfig.java:206-221` |

⚠️ **`@Async` / `@EnableAsync` 全仓零命中**（只在注释里出现：`OrderStateExecutor.java:25,28,32`、
`application.properties:96`）⇒ 批文 ⑤「线程池分池」在本仓是**新建池**，不是「把 `@Async` 分池」。

### ③ SSE —— 没有连接表、没有上限、没有心跳 {#pack-c3-block-d-hardening-s9}

- **推流循环只有一个**：`observe/service/SseStreamWriter.java:116`（`stream(tag, frames)`）
  → `:123` `while (maxTicks <= 0 || tick < maxTicks)` → `:127 writeFrame` → `:150 out.flush()`。
- **没有连接表**：三条流各自「每条连接现建一份私有 `Session`」，唯一的共享状态是内存环 ——
  设计口径逐字写在 `observe/controller/MetricsStreamController.java:28-31`（「服务端**没有**独立的
  『连接表』要清理」）。⇒ **没有东西可以「反注册」**。
  - 三处工厂：`EventStreamService.java:68` / `OrderStreamService.java:80` / `MetricsSnapshotService.java:153`。
- **连接数上限：无**。**空闲超时：无**（`mall.observe.sse.max-ticks` 是「推满即结束」的**测试**口子，
  默认 0 = 无限，`SseStreamWriter.java:42-44` 逐字否认它是限流/降级）。
- **心跳：无** —— 只写 `data:` 帧（`SseStreamWriter.java:68-69`），无 ping / 注释帧 / keep-alive。
- **超时的现状态**：`application.properties:228` `spring.mvc.async.request-timeout=-1`
  （= 本层**不设**超时；注释块 `:203-227` 是 commit 10b 写的，逐字解释「Tomcat 的 `asyncTimeout`
  默认 30s」与为什么长连接要关掉它）。
- ⚠️ **因此批文 ① 与 10b 直接冲突** —— 见「前提更正」第 4 条。

### ④ MQ —— 拓扑与 DLQ **都已经有**，缺的是另外两件 {#pack-c3-block-d-hardening-s10}

- **已有的**（`observe/mq/RabbitObserveConfig.java`）：topic exchange `mall.observe.events`（`:77`）·
  主队列 `observed_event.queue` **durable + dead-letter 指向 DLX**（`:113-119`）· DLX + DLQ（`:127-139`）·
  prefetch **500** + 批大小 **50** + **手工 ACK** + `defaultRequeueRejected=false`（`:214-219`）。
- **已有的应用层重试**：`ObservedEventConsumer.java:94` `MAX_ATTEMPTS = 3`（`:96` 注释「批文点名 3」）、
  递增退避 `:98`、失败 ⇒ **整批逐条 `basicNack(requeue=false)`** ⇒ 由队列属性送进 DLQ（`:174-178`）。
- **缺的两件**（批文 ② 的点）：
  1. **幂等键** —— 今天**没有**：`insertBatch` 直接 INSERT（`ObservedEventDao.java:110-112`），
     **表上无唯一键**（`mall_management_sql.sql:1866-1883` 只有 `PRIMARY KEY(Id)` + 4 个普通索引）。
  2. **DLQ 的出口** —— 今天**没有任何**查看/重投/计数面：既无端点、也无指标；
     `ObservedEventConsumer.java:171-173` 的告警只说「DLQ 里躺着等人查」，而「怎么查」本仓没写。
     ⚠️ 可用的现成手段只有 RabbitMQ 自己的 management（CI 起的是 `rabbitmq:3-management`，
     `application-a3.properties:187` 提过）—— **要不要引它、还是自建一个只读端点，属待裁**。
- ⚠️ **dev 才有 MQ**：`spring.rabbitmq.host=192.168.229.129` 只在 `application-dev.properties:172`；
  **a3 刻意一行不配**（`application-a3.properties:184`），消费者 bean 因此**在 a3 不存在**
  （`@ConditionalOnProperty(prefix = "spring.rabbitmq", name = "host")`，`ObservedEventConsumer.java:81`）。
  ⇒ **D 段的 MQ 项只能在 dev 上验**，手验清单里必须标 profile。
- ⚠️ 密码走环境变量：`application-dev.properties:175` 那一键**只从环境变量 `MALL_MQ_PASSWORD` 读**（缺省为空，仓里**不落任何默认口令**）。
  —— **永远不要把口令写进任何文件 / 报告 / 终端**（本仓纪律，`_work/mq-pass` 只以 `$(cat …)` 读入）。

### ⑤ 事件类 —— 8 类的位置，加第 9 类要动 **19 处** {#pack-c3-block-d-hardening-s11}

**唯一出处**：`observe/service/EventBroadcaster.java:132-140` 的 `enum Kind`
（`CACHE` / `LOCK` / `ORDER` / `STOCK` / `AUDIT` / `NOTIFY` / `SENSITIVE` / `IDENTITY`，
每项带 `tag` + `label`，`:144-147`）。实测**正是这 8 个**（第 9 类 `PROTECTION` 见尾部〈落地登记〉）。

| 层 | 要动的地方（实测坐标） |
|---|---|
| 后端枚举 | `EventBroadcaster.java:132-140`（加项）· `:199-206`（源 metrics 字段）· `:233-253`（构造器形参）· `:264-276`（`poll()` 里加调用）· **新增一个 `ingestXxx` + 源环**（既有 8 段 `:330-471`，每段末都有 `droppedIngest += missed(...)`；第 9 段 `ingestProtection` 在 `:474-495`） |
| 后端落库 | `ObservedEventDao.java:47`（`KIND_MAX = 16`）· `:99`（`params.add(clip(e.kind(), KIND_MAX))`）· INSERT 列清单 `:110-112` —— ⚠️ 三处**都不需要改**（`KIND_MAX` 16 ≥ `"protection"` 的 10 字符，其余两处与 kind 取值无关） |
| 数据 / 文档 | `mall_management_sql.sql:1868`（列注释里**写死**了 8 个名）· `db-schema.md#db-16-1`（同一句 8 名清单）—— 两处均已由 commit 2 补上 `protection`（同行替换） |
| 前端 | `config/labels/observe-labels.js`：`KINDS`（`:27`）· `KIND_LABEL`（`:37-47`）· `KIND_COLOR`（`:63-73`）· `KIND_ICON`（`:98-135`）· `OBJECT_FIELD_LABEL`（`:168-180`）。⚠️ 色 token **已存在**（`assets/tokens.css:201` / `:301` 的 `--c9: #ec4899`）⇒ **不用新增** |
| 测试 / 探针 | `EventBroadcasterTest.java:68`（`containsExactly` 9 个 tag）· `EventStreamServiceTest.java:81` · `smoke/ObserveStreamSmokeTest.java:69` · `scripts/probes/probe-b7c8-event-log.mjs:69`（`[…'identity'][i % 8]`）—— ⚠️ **后两处实测不必改**：前者用 `contains(...)` 子集断言（枚举多一项**不会红**，与本节所写不同，已实测定正）；后者只是从 8 个名字里轮流取，多一类**不影响它跑** |

⚠️ 帧里的 `counts` 由 `Kind.values()` **自动生成**（`EventStreamService.java:104-107`）⇒ 加枚举项后
「最新一拍有第 9 个类」**自动生效**，但那四处测试/探针的**期望值**要一起改（否则一改就红）。

### ⑥ 防护 5 键 —— 三处「只记录不实现」的原话 {#pack-c3-block-d-hardening-s12}

**5 个键的定义与校验（唯一出处）**：`observe/service/AttackEngine.java:330-331`
```java
static final List<String> PROTECTION_KEYS =
        List.of("rateLimit", "circuitBreak", "degrade", "retry", "poolIsolation");
```
- 校验：`parseKeys(...)` `AttackEngine.java:1495-1510`（`:1509` 未知键 ⇒ `IllegalArgumentException`
  ⇒ 控制器侧 400）；调用点 `:1480-1481`。
- 回显：`AttackEngine.java:1635` `engine.put("Protections", cfg.protections())`。
- 落进演练记录：`DrillRecordService.java:109`（`decodeKeys(protections, "protections")` —— commit 2 起先收进局部量 `onProtections`，再由 `ProtectionGate` 消费）。
- 前端开关面板：`views/admin/observe/AttackConsole.vue` —— 模板 `:122-145`（`v-for="k in PROTECTIONS"` `:128`）·
  状态 `:624`（`ref(new Set(PROTECTIONS))`，**默认全开**）· 拨动 `:674-692` · 提交 `:1005`。
- 中文标签：`config/labels/observe-labels.js:274`（`PROTECTIONS`）· `:277-283`（`PROTECTION_LABEL`：
  限流 / 熔断 / 降级 / 重试 / 分池）· `:360-364`（`protectionLabel`）。

**三处「只记录不实现」的原话**（D 段做完要**逐处改**，改的时候别只改一处）：
1. `AttackEngine.java:325`（类注释）**原话**：「⚠️ **本笔只负责把它们「收下来、记进演练记录、打进日志」，
   一条防护逻辑都不实现** …… 硬边界 A 逐字是「加固（扩池 / 扩线程 / 优化慢 SQL）与补救
   （限流 / 熔断 / 降级 / 重试）**都在 D 段**」」—— ⚠️ **commit 2 已改**（`:325-328` 现址不变，见尾部〈落地登记〉）。
2. `AttackEngine.java:1652`（回显文案）**原话**：「Protections 五个开关本笔**只记录不实现**（防护在 D 段）」—— ⚠️ **commit 2 已改**（现为「五枚里三枚真生效…」，同占 `:1652-1653`；⚠️ **阶段 2b 又改成「四枚真生效 / `degrade` 只作记录位」**，仍占 `:1652-1653`）。
3. 前端 `AttackConsole.vue:127`（原 **124** 行；原文「默认全开 · 本笔只记录、不改读数（接闸在 D 段）」）与 `:137`（原 **134** 行）—— ⚠️ **两处均已由 commit 2 改**（新文案 + `:aria-disabled` 守卫；**别再按原话 grep**）。⚠️ **阶段 2b 又整体 +3**（该文件上方副标题块净插 3 行，故原 124 / 134 行为现 127 / 137 行）；**阶段 2b 把副标题再改成**「限流 / 熔断 / 重试 / 分池已接闸，改读数；降级不可开关」。
4. 契约侧另有：`api-contract.md#api-h-1` 的载体表注（原「两者都先落成契约面 …… 语义落地归 D 段
   —— 这一条是**如实登记**」）—— ⚠️ **commit 2 已同笔改**（改为「✅ 已接闸（3 键 + 1 键 TD + 1 键记录位）」（⚠️ **阶段 2b 起再改为「4 键 + 1 键记录位」**）
   + 附录 `#api-i-3` 指针）；原引的**快照行号**（`AttackController:110`）随之删去，不再需要重指。

⚠️ **`degrade`（降级）在本仓已有半个实体**：`Mall-management-server` 里有一族「降级」行为
（缓存 L1/L2 回落、锁取超时 fail-open、MQ 缺席只 WARN、审计 fail-open）—— 它们**不是**由一个开关管的。
⇒ 「`degrade` 接上闸」的落点要**先定「管什么」**（管哪几条降级路径？还是只加一条新路径？），属待裁。

### ⑦ 重试 —— 全仓**零**死锁处理，Spring Retry 是从零加 {#pack-c3-block-d-hardening-s13}

实测（`src/main` 全量 grep）：`Deadlock` / `deadlock` / `CannotAcquireLockException` /
`DeadlockLoserDataAccessException` **零命中**；`@Retryable` / `RetryTemplate` **零命中**；
**没有任何一处 catch 死锁异常**。

- 现存唯一「重试」是 **Redisson 客户端级**（不是业务重试）：`DistributedLockService.java:117-120`
  （`RETRY_ATTEMPTS = 1` / `RETRY_INTERVAL_MILLIS = 200`，注释逐字与 Redisson 默认值对照）。
- 「死锁」在本仓**今天的形状**是 `LockSceneService` 里的一个**演示场景**
  （`SceneKind.DEADLOCK`，`LockSceneService.java:100-108`），不是线上会自然发生的那条路。
- 锁**取锁超时一律 fail-open、无重试**：`DistributedLockService.java:270-273`（`if (!held) { … return action.get(); }`）。
- 订单关键路径**无重试**：`OrderService.java:379`（`ORDER_ADD`）/ `:1179` / `:1198` / `:1235` 四个
  `runLocked` 调用点，正文里没有重试循环。

⚠️ ⇒ 批文 ④「Spring Retry 只对**死锁类**异常 2–3 次」落地时，**要先回答两个问题**（本 pack 无法单方面定）：
(a) 重试挂在**哪一层**（`@Retryable` 要 AOP 代理 ⇒ 同类自调用无效，本仓已在
`OrderStateExecutor` 类注释与 `application.properties:96` 记过这个坑）；
(b) MySQL 死锁在本仓的**真实触发点**是什么（今天没有任何实测证据说它会发生在哪几个 SQL 上）
—— 「重试一个从不发生的东西」等于加了一层没人验过的代码（TD-20 那一族）。

### ⑧ 大表 LIMIT 与脱敏 —— 两条批文前提都与仓不符 {#pack-c3-block-d-hardening-s14}

**(a) `limit` 夹取：多数端点**已经**有（不是「全都没有」）**

- 现成范式：**默认 100 / 上限 500** 的 `normalizeLimit` 已在至少 20 处生效
  （`AuditService:157-161` · `UserGovernanceService:110-114` · `ReportService:489-493` ·
  `SubsidyService:381-385` · `CouponService:862-867` · `SeckillService:596-600` …）；
  攻击记录是 **默认 20 / 上限 200**（`DrillRecordService.java:213-215`，常量 `:38-39`）；
  回放是 **默认 5000 / 上限 50000**（`ReplayService.java:115-119`）。
- **真正无上限的列表**（实测逐条 —— 这些才是批文 ③ 的靶子）：

| 域 | 坐标 | 为什么危险 / 备注 |
|---|---|---|
| order | `OrderDao.java:195`（`selectActiveByCustomer`）· `:202`（`selectActiveByMerchant`）· `:215`（`selectByMerchant`）· `:228`（`selectHistoryByCustomer`） | 单客户/单商家的**全部**未收货或历史单 |
| order | `OrderDao.java:325`（`selectShippableForTrack`） | **调度器每 tick 全表扫**「已发货未收货」—— 打 LIMIT 会**改变调度语义** |
| order | `AdminOrderDao.java:138`（`selectTimeline`）· `:201`（`selectShippingTrack`）· `ShippingTrackDao.java:124` | 单 OrderId 内**天然有界**（低优先） |
| goods | `GoodsDao.java:257`（`searchByKeyword`） | 该文件**自己写着**「`SELECT *` 无 schema 前缀、无 `LIMIT`、无分页」（`:248` / `:272`）—— 既有账 |
| goods | `GoodsDao.java:307`（`selectGoodsForAudit`）· `:331`（`selectByTypeId`） | 按商家/类型全量 |
| message | `MessageDao.java:111`（`selectConversations`）· **`:148`（`selectMessagesBetween`）** | `:148` 是**两个人之间的全部消息** ⇒ 一刀切 100 = **聊天记录丢历史** |
| message | `MessageDao.java:173`（`selectUserPublicInfo`）· `:183` / `:189`（两张头像） | 按主键，天然有界 |
| comment | `CommentDao.java:447`（`selectReplies`）· `:579`（`selectImages`） | 受 `IN (...)` 行数约束，SQL 本身无上限 |
| 其它 | `CartDao.java:183` / `:194` · `CategoryDao.java:102` · `CouponDao.java:447` · `SubsidyDao.java:149` · `TemplateDao.java:119` / `:144` · `NotificationDao.java:259` / `:266`（**群发受众 = 全表用户**） | 未逐条判过语义 |

⚠️ ⇒ 批文「所有 list 端点默认 100」**不能一刀切**：对**时间序列表**（记录 / 审计 / 举报）是安全的，
对**按业务键收窄的查询**（两个人之间的消息、某单的轨迹、调度器的扫描）是**行为变更**
—— 会「悄悄少给数据」。⇒ 属**待裁**（逐条给名单 + 判据，比一次性全打默认值靠谱）。

**(b) 脱敏：批文点名的三类里，**两类在本仓根本不存在** {

| 批文点名的 | 实测结论 | 证据 |
|---|---|---|
| 手机号 | **无此字段** | `user` 表 9 列（`mall_management_sql.sql:182-193`）无 phone/tel/mobile；`login` 表也没有（`views/LoginPage.vue:134` 自述） |
| 收货地址 | **无此字段** | `order` 表 12 列无 address / receiver；前端只有一枚 `disabled` 占位按钮（`views/UserInfoPage.vue:346`） |
| 邮箱 | **有**：`user.email` | DDL `mall_management_sql.sql:189` |
| （实际还有）常用地址 | **有**：`user.address` | DDL `:191` |
| （实际还有）商家联系电话 / 店铺地址 | **有**：`merchant_application.ContactPhone` / `ShopAddress` | DDL `:375` / `:376` |

**真正的出口**（脱敏要动的地方）：
- ① 本人资料：`UserDao.java:141`（`SELECT *`）→ `UserService.profileOf:330`（**整行 Map**）
  → `/user/me`（`UserController.java:233-235`）与 `/user/getUserInfo`（`:158-163`）。
- ② 管理端用户列表/详情/审计快照：`UserGovernanceDao.java:63`（列清单含 `u.email`）
  → `AdminAccountsController.java:73-78`（`/admin/accounts/users`）。
  ⚠️ 但**页面不展示 email**（`views/admin/AccountsPage.vue` grep `email` 零命中）⇒ 这是**响应体暴露**，不是页面暴露。
- ③ 管理端入驻申请：`MerchantApplicationDao.java:33`（含 `ContactPhone, ShopAddress`）
  → `AdminAccountsController.java:121-127` → 页面 `views/admin/AccountsPage.vue:89-90` **真的展示**。
- 对照（证明确有隔离）：公开信息面只选 5 列（`MessageDao.java:174-175` / `UserCacheService.java:81`）。

⚠️ **脱敏最危险的一处**：`/user/me` 的响应是**本人资料编辑页**的回填源
（`views/UserInfoPage.vue:362-367` 输入框直接 `v-model="userInfo.email"`）。
把邮箱掩码回给**本人** ⇒ 页面把掩码值当原值提交（`:582-583`）⇒ **数据被掩码污染**。
⇒ 脱敏必须区分「**给本人**」与「**给第三方/管理端**」，且**写接口要拒绝掩码值** —— 这一条**属 A 档，要先裁**。

## 要新建 · A 档（**开工前必批**）{#pack-c3-block-d-hardening-a}

| # | 项 | 为什么是 A 档 | 批文是否已批 |
|---|---|---|---|
| **A1** | **新依赖**：Resilience4j（`circuitbreaker` + `ratelimiter`）· Spring Retry · AOP | 新依赖 = A 档（批文逐字已点） | ✅ 已点（「A 档 新依赖（Resilience4j / Spring Retry / AOP）」）；落点 `pom.xml` **段末** |
| **A2** | **第 9 类事件 `protection`**（枚举 + 源环 + 落库 + 前端 5 处 + 4 处测试/探针） | 扩充**事件 kind 集合** = 契约面变更（帧的 `counts` 会自动多一个键，页面图例多一项） | ⚠️ 批文只说「第 9 类『防护』（ProtectionMetrics）」，**没批落点**（19 处清单见 §⑤） |
| **A3** | **`observed_event` 加 `Seq` 列 + 唯一索引**（若采「TraceId + Seq」幂等口径） | 新字段 + 新 DDL | ⚠️ **批文前提与仓不符**（表上没有可用的 `Seq`，见「前提更正」第 2 条）⇒ 必须先裁口径 |
| **A4** | **DLQ 出口**（只读列表 / 计数 / 重投三选几） | 新端点 | ⚠️ 批文写「DLQ 监控 + 重投」但**没说新端点**；该句自带「A 档 DLQ 端点（1 条，**若有**）」⇒ 待批 |
| **A5** | **脱敏的落点与口径**（掩给谁 / 写接口怎么挡掩码值） | 改既有响应的**值语义** | ❌ 未批（批文点名的三类里两类不存在 ⇒ 靶子变了） |
| **A6** | **大表 LIMIT 的名单与默认值**（是否一刀切 100 / 是否含按业务键收窄的查询） | 改既有端点的**默认行为**（可能悄悄少给数据） | ❌ 未逐条批 |
| **A7** | **SSE 连接上限 + 空闲超时**（数值；以及是否动 `spring.mvc.async.request-timeout=-1`） | 改长连接的**既有语义**（10b 刻意设的） | ⚠️ 未批数值（见「前提更正」第 4 条） |

⚠️ **A 档未批之前：不动任何代码 / 表 / 契约**（沿用块 7 pack 的同一条纪律）。

## B 档 / C 档（自裁，报备 —— 不落硬线，可落码）{#pack-c3-block-d-hardening-b}

- **B1** 五个防护开关键**在页面上的可见后果**：接闸后，`AttackConsole.vue:127` 那句
  「本笔只记录、不改读数（接闸在 D 段）」必须**当笔改**（否则页面在说谎 —— TD-20 那一族）—— ✅ commit 2 已改；⚠️ **阶段 2b 又改一次**（`retry` 接闸 ⇒ 副标题现为「限流 / 熔断 / 重试 / 分池已接闸，改读数；降级不可开关」）——**同一条纪律，同笔改**。
- **B2** 熔断 / 限流的**参数键名**：照本仓惯例走 `mall.*` + `@Value` 默认值（先例：
  `mall.observe.sse.interval-ms`、`mall.drill.engine.in-flight-cap:512`）⇒ 若只是默认值、不落 properties，不算 A 档。
- **B3** 单元测试**照既有分层写**：纯逻辑进 `*Test`（不起 Spring），需要真 bean 的进切片
  （`@WebMvcTest` 切片**不加载** `RabbitObserveConfig` 这类普通 `@Configuration`，见其类注释
  `:65-69`；切片里 `EventPublisher` 只能靠 `@MockitoBean` 顶上）。
- **B4** 攻防联动演示的**剧本**（120 / 400 / 拉满 · 关防护崩 / 开防护恢复）照 `块7参考.txt` §一 的档位，
  不新造档位。
- **C1** 事件类新增后，`observed_event.Kind` 的列注释（`mall_management_sql.sql:1868`）与
  `db-schema.md#db-16-1` 的 8 名清单**同笔改**（不新开一笔记账）。
- **C2** commit 4 的 ⑤ 块 8 前置清单**只写「输入清单」，不写实现**（批文：不写实现）。

## 前提更正（批文与仓不符，**逐条**）{#pack-c3-block-d-hardening-corr}

> 这一节是 D 段最该先看的地方 —— 五条里有三条会让「照着批文直接落码」当场落错。

1. **「指标帧里的 `protections` 就是那 5 个开关」—— 不成立。**
   后端帧里 `protections` 是 **8 条命中计数**，且与 5 个开关键**不是一个字符串空间**：
   `cacheHit` / `lockTimeout` / `orderRejected` / `stockInsufficient` / `auditFailed` /
   `notifyFailed` / `sensitiveHit` / `identityRejected`（硬编码在 `MetricsSnapshotService.java:354-376`，
   `{tag,label,value}` 构造器 `:579-585`，全部取自 Micrometer 本拍增量、**不读配置**）。
   ⇒ 「开防护后指标帧要反映防护」需要**新增**一组读数，不能复用这 8 条。
   ⚠️ 另注：演示模式（块 7 收尾 ②）里那 5 条 `protections` 是**前端假数据**造的
   （`src/api/demo-stream.js:422-426`）—— 两处别混。
2. **「MQ 幂等（TraceId + Seq 唯一键）」—— 表上**没有**可用的 `Seq`。**
   - `observed_event` 的列实测：`Id, Kind, Severity, Title, ObjectType, ObjectId, TraceId, BatchId,
     OccurredAt, created_at, updated_at`（`mall_management_sql.sql:1866-1883`）—— **没有 `Seq`**，
     也没有任何唯一键（只有 `PRIMARY KEY(Id)` + 4 个普通索引）。
   - 内存里**确实有**一个 `seq`，但它**不能**当幂等键：`ObservedEvent.seq` 是**合并环游标**
     （`EventBroadcaster.java:774` `cursor.getAndIncrement()`〔原 **767** 行〕；record 定义 `:194-196` 未动）——
     进程内单调、**重启归零、跨实例不可比、且 2048 回绕**。
     它能安全使用的地方只有一个：**同一条消息字节被重投**时那个数**不变**（这正是幂等需要的那半）。
   - ⇒ 口径二选一（**待裁**）：**(甲)** 加列 `Seq` + `UNIQUE (TraceId, Seq)`（A 档：新字段 + 新 DDL）；
     **(乙)** 不加列，用 `(TraceId, OccurredAt, Kind, Title)` 之类做唯一键（**弱**：同毫秒同 trace 的
     两条同类同题事件会被当重复**丢掉**）。
   - ⚠️ 不论甲乙，`(TraceId, …)` 对**无 traceId 的事件恒不生效**：`TraceId` 可为 `NULL`
     （定时任务 / SSE 流线程上产生的事件，`EventBroadcaster.java:186-188` 逐字），
     而 MySQL 的 UNIQUE **不把两个 `NULL` 当相等** ⇒ 「那批事件**天生**去不了重」。这一条必须写进验收口径。
3. **「数据脱敏（手机号 / 邮箱 / 地址）」—— 靶子变了。**
   手机号与收货地址**在本仓不存在**（见 §⑧(b)）；真实靶子是 `user.email` / `user.address` /
   `merchant_application.ContactPhone` / `ShopAddress` 四处出口，其中 `/user/me` 那一处
   **是本人编辑页的回填源**，掩码会污染数据 ⇒ 口径必须先裁（A5）。
4. **「SSE 空闲超时」与 commit 10b 直接冲突。**
   10b 把 `spring.mvc.async.request-timeout` 设成 **`-1`**（`application.properties:228`，
   注释块 `:203-227`）—— 那是**刻意为长连接关掉超时**的。批文要的「空闲超时」与它是**同一层**：
   ⇒ 要么在 SSE 自己那层实现（`SseStreamWriter` 加「连续 N 拍写失败/无帧即收工」），
   要么把这一项改回正值（**等于撤 10b 的一部分**）。**两条路都不能偷偷做**，属待裁。
5. **「线程池分池（订单 / 事件 / SSE / MQ）」—— 四个里只有「订单」今天真的是池。**
   事件环并入跑在 **SSE 流线程**上（`EventBroadcaster.add` 只由 `poll()` 调，
   `poll()` 由流线程驱动 ⇒ **没人开日志页，事件就不落库**，`EventBroadcaster.java:221` 逐字）；
   SSE 的推流线程是**容器的异步线程**；MQ 是**监听容器线程**（今天单并发）。
   ⇒ 「分池」在 SSE / MQ 两处其实是「**新建池或限并发**」，与「订单池（已存在）」不是同一种活。

## 验收 {#pack-c3-block-d-hardening-acc}

分两档 profile（**与块 7 手验同一轮**，批文：手验压成一轮、commit 4 之后）：

| 档 | profile | 能验什么 | 验不出什么 |
|---|---|---|---|
| **业务 + 防护** | **dev** | 五闸的真实行为、MQ 幂等与 DLQ、SSE 上限、攻防联动演示、`/attack/*` 全链 | **鉴权**（dev 的 `app.auth.enabled=false`） |
| **鉴权** | **默认 profile** | 未登录 / 越权（含 TD-64 那类破坏性端点的角色闸） | 调度器 / MQ（默认 profile 缺 broker、总闸另说） |

⚠️ 起后端前后**按端口找 PID 强杀**（`TaskStop` 不停 JVM，见块 7 的既有教训），且**重启后三查**：端口 / profile / VM 库。

**逐项判据（D 段）**：

| 项 | 判据（可复核的口径） |
|---|---|
| 熔断 | 关防护 + 抬高错误率 ⇒ 断路器**真的开**（有一处可观察的状态：帧 / 日志 / 端点），半开探测 30s 后**只放 5 个** |
| 限流 | 阈值 200 QPS：`targetQps=120` 时**几乎不拒**；`targetQps` 拉高到 400+ 时**大量被拒** —— 「大量被拒」是**验收要的现象**，不是故障 |
| 重试 | 只对死锁类异常；**非死锁异常不重试**（反向对照：造一个非死锁失败，确认重试计数**没涨**） |
| 分池 | 订单池回到既有口径（`app.order.state.async.*`）；新增的池**有名字、有界、有丢弃计数**（照 `OrderStateExecutor`） |
| 第 9 类 | 日志页出现「防护」筛选类；`observed_event.Kind` 出现第 9 个值；四处测试/探针期望值同步 |
| SSE 保护 | 连接上限：第 N+1 条连接被**明确拒绝**（不是一个说不清的白屏）；空闲超时：空闲到阈值后**连接自己收工**且**不刷 WARN** |
| MQ 幂等 | **同一条批次内两条同键消息只落一行**（判据要能给出「重投了、行数没涨」的账） |
| DLQ | 能**看见** DLQ 里有多少条（端点或 management 面）；重投路径要有**明确不重投**的情形（例如毒消息） |
| LIMIT | 无上限的那批**逐条**给出「加了没有 / 加了多少 / 语义变没变」 |
| 脱敏 | 只对**第三方/管理端**出口生效；**本人出口不掩**（或掩了也能正确回写）—— 反向对照：本人提交一次资料，确认存下来的不是掩码 |
| 攻防联动 | `120 稳 · 400 吃紧 · 拉满饱和`；**关防护 + 故障 ⇒ 崩**；**开防护 + 同一故障 ⇒ 恢复**（同一场景、同一注入，只有开关不同） |

## 估时与 commit 拆分 {#pack-c3-block-d-hardening-split}

批文原估：commit 2 **3–4 天** · commit 3 **2–3 天**（已去掉多进程实验） · commit 4 **1–2 天**；
块 7 收尾（commit 1）**2–3 天**；手验**1–2 天**；到块 8 前合计 **≈2 周**。
⚠️ 估时的**前提**是 A 档（尤其 A2/A3/A5/A6）在 commit 2 开工前批完 —— A 档拖一天，后面**逐日顺延**。

## 待裁项 {#pack-c3-block-d-hardening-pend}

| # | 待裁 | 关联 |
|---|---|---|
| 1 | **A5 脱敏口径**：掩给谁？本人出口掩不掩？写接口怎么拒绝掩码值？（最危险的一处是 `/user/me` 回填） | 批文 ④；与 `db-schema.md#db-13-8` 的种子数据无关 |
| 2 | **A3 幂等口径**：甲（加 `Seq` 列 + UNIQUE）还是乙（不加列、弱键）？**无 traceId 的事件怎么办**？ | 批文 ②；`observed_event` DDL 改动 = 新 DDL |
| 3 | **A4 DLQ 出口形态**：自建只读端点 / 走 RabbitMQ management / 只加指标？ | 批文 ②（自带「若有」） |
| 4 | **A6 LIMIT 名单**：一刀切 100，还是「只对时间序列表打默认、按业务键收窄的除外」？ | 批文 ③；会影响聊天记录、调度器扫描 |
| 5 | **A7 空闲超时的层**：SSE 自己实现 / 改 `spring.mvc.async.request-timeout=-1`（= 部分撤 10b）？ | 批文 ①；与 10b 冲突 |
| 6 | **`degrade` 开关管什么**：今天一族降级行为**都不由开关管**（缓存回落 / 锁 fail-open / MQ 缺席 / 审计 fail-open） | 批文 ②；`AttackEngine.java:325` |
| 7 | **重试挂哪一层、针对哪个真实死锁**：今天全仓零死锁处理，没有实测证据说死锁发生在哪几个 SQL | 批文 ④ |
| 8 | **既有挂账接手范围**：`TD-18`（管理员铸票路径缺失）与 `TD-64`（块 7 的破坏性 / 长连端点**没有角色闸**）的「处理时机」都写着 **D 段** —— 要不要在本段结清？（`TD-64` 的破坏面是**真 DELETE**） | `tech-debt.md` TD-18 / TD-64；`TD-71`（JVM `-Xms/-Xmx`）归 commit 4 ⑤ |

## 扩展阅读（不够时按指针读）{#pack-c3-block-d-hardening-s15}

- 块 7 的攻击引擎 v2 拆分与实测：`progress.md` 的 `#### 7-B7-续`（commit 10 系列，逐笔表）。
- 块 7 的演示面（回放 / 模拟日常 / 实时进度）：`progress.md` 的 `#### 7-B7-收尾`（含 ④ 三块预览的 TODO）。
- 观测流三改（每连接一份帧源 / 环 2048 / 异步超时）：`context-packs/c3-block-7-observe.md`
  与 `progress.md` 同节；帧字段清单见 `api-contract.md#api-h-2` 与 `#api-h-3`。
- 攻击面契约原文：`api-contract.md#api-h-1`（五出口）· `#api-h-6`（`X-Drill-Batch` 出站头）。
- MQ 链路的**为什么**（每一处拓扑取舍）：`observe/mq/RabbitObserveConfig.java` 类注释（**逐行读它**）。

## 落地登记（2026-10-15 · D 段 commit 2〈后端防护接闸〉） {#pack-c3-block-d-hardening-landed}

⚠️ 本节**不改本 pack 的任何结论**，只做两件事：① 把**被本笔顶偏**的行号重指到现址（本节以下的行号
一律是**本笔落定后**实测）；② 把**被本笔弄失真**的原文逐处标注（改的时候别只改一处）。

### 落码面 —— pack 预测 vs 实际

| pack 里说要动的 | 实际 |
|---|---|
| ⑤ 第 9 类事件环 | 枚举 `EventBroadcaster.java:132-142`（第 9 项 `PROTECTION`）· 新源环 `observe/metrics/ProtectionMetrics.java` · 并入段 `ingestProtection` `:474-495`（`poll()` 里的调用 `:274`） |
| ⑤ 落库 | `ObservedEventDao` **一行没改**（`KIND_MAX = 16` ≥ `"protection"` 的 10 字符，另两处与 kind 取值无关） |
| ⑤ 数据 / 文档 | `mall_management_sql.sql:1868` 与 `db-schema.md` §16-1 的 8 名清单**各补上 `protection`**（同行替换，净 0 行） |
| ⑥ 五键 | `AttackEngine.PROTECTION_KEYS` **未动**（只读）；接闸口径 = **3 键接闸 + 1 键登记 TD-74 + 1 键记录位**（⚠️ **阶段 2b 起 = 4 键接闸 + 1 键记录位**，`WIRED_KEYS` 3 → 4），判据落在新建的 `protect/ProtectionGate`（`KEYS` / `WIRED_KEYS`） |
| ⑥ 面板 | 三枚可拨（⚠️ **阶段 2b 起四枚可拨**）；`retry` / `degrade` **标灰 + `disabled` + `aria-disabled` + 逐枚 tooltip**（禁止死控件）—— ⚠️ `retry` 已移出该组，现只剩 `degrade` |
| ⑦ 重试 | **未落码** —— 只登记 **TD-74**（本笔没有 `@Retry` / retry 依赖）⚠️ **阶段 2b 已落码**（`MerchantDowngradeService.relist` 的编程式 `RetryTemplate`，**不是** `@Retryable`；见 `tech-debt.md#td-74-correction`） |
| ⑧ 熔断 | `mallMysqlRead` / `mallMysqlWrite` 两个实例；Redis / MQ 不加（手写熔断已在，登记 **TD-75**） |
| ⑨ 3「帧读数要反映防护」 | **未做** ⇒ 帧里的 `protections` 读数仍是 8 条（见下「未还项」） |

### 行号重指表（**本笔顶偏的 33 条引用**，同行替换、净 0 行）

⚠️ 数法：本表**逐条列引用**（不是列行）⇒ 合计 **33 条**。其中**扫描器看得见 22 条**（`refs-diff.mjs` 的
LOST∩NEW 逐条可指认，本节以下给的就是它们的旧 → 新）· **扫描器看不见 11 条** —— 两处：
`observe-labels.js` 五处 + 原 `:315-319`（**6 条**，见 `:142` / `:161` 两行）与 `AttackConsole.vue` 五处
（**5 条**，见 `:159-160` 行）；判据同一条：那些行里只有**裸续引**、没有「有名 token」，`refs-scan.sh`
按「无主裸续引」丢弃 ⇒ **那 11 条是手工重指的**（22 + 11 = 33）。

| 本 pack 行 | 引用 | 旧 → 新 |
|---|---|---|
| `:133` `:139` | `EventBroadcaster.java` 枚举 | `111-119` → **`132-140`** |
| `:135` | 同上（`tag` / `label` 两项） | `121-124` → **`144-147`** |
| `:139` | 源 metrics 字段 | `173-180` → **`199-206`** |
| `:139` | 构造器形参 | `200-218` → **`233-253`** |
| `:139` | `poll()` 里加调用 | `229-240` → **`264-276`** |
| `:139` | 既有 8 段 `ingestXxx` | `294-435` → **`330-471`** |
| `:142` | `observe-labels.js` 五处（**扫描器看不见**：同行无「有名 token」，`refs-scan.sh` 把它当无主裸续引丢掉） | `24` / `34-43` / `56-65` / `90-122` / `154-163` → **`27` / `37-47` / `63-73` / `98-135` / `168-180`** |
| `:143` | `EventBroadcasterTest.java` / `EventStreamServiceTest.java` | `63` / `78` → **`68`** / **`81`** |
| `:158` | `DrillRecordService.java` 落库点 | `100` → **`109`** |
| `:161` `:162` | `observe-labels.js` 三处（`:315-319` 同样**扫描器看不见**） | `257` / `260-266` / `315-319` → **`274`** / **`277-283`** / **`360-364`** |
| `:159-160` | `AttackConsole.vue` 面板五处（⚠️ **同 `:142`：这五条也是裸续引 ⇒ 扫描器看不见、手工重指**） | `117-136` / `123` / `613` / `663-669` / `982` → **`122-145`** / **`128`** / **`624`** / **`674-692`** / **`1005`** |
| `:169` `:266` | `AttackConsole.vue` 两处文案 | `119` → **`124`**；`:129` → **`134`** |
| `:189` | `OrderService.java` 四个 `runLocked` | `369` / `1169` / `1188` / `1225` → **`379`** / **`1179`** / **`1198`** / **`1235`** |
| `:205` | `DrillRecordService.java` 两处 | `187-189` / `37-38` → **`213-215`** / **`38-39`** |
| `:296` | record 定义 | `168-170` → **`194-196`** |
| `:303` | `@param traceId` 那句 | `160-162` → **`186-188`** |

### 两处「原值在取点当天就已偏」的引用（本笔**订正**，不是平移）

| 本 pack 行 | 引用 | 取点当天真值 | 现址 |
|---|---|---|---|
| `:296` | `EventBroadcaster.java` 的**原 652 行**（所述内容 = `cursor.getAndIncrement()`） | 实为 **654**（原值差 2 行） | **`767`** |
| `:316` | `EventBroadcaster.java` 的**原 176-182 行**（所述内容 = 「`poll()` 由流线程驱动…」那句） | 实为 **188**（原值那一带是 metrics 字段，不是那句话） | **`221`** |

⚠️ 为什么这两条**订正**而不是「只登记」：本笔把它们的偏差从 2 行 / 6-12 行放大到 **113 行 / 43 行**
（目标整体下移）⇒ 保留原样已经没有「快照」的意义；而两处的所指由上下文逐字可定（一条是代码、
一条是原句），故按实测真值改。

### 本笔**只登记、不改**的两条（本笔没让它们更差）

| 本 pack 行 | 引用 | 实测真值 |
|---|---|---|
| `:76` | `pom.xml` 的**原 150-159 行**（MQ 依赖那段注释块） | **`149-157`**（原值起点 +1、终点 +2） |
| `:77` | `pom.xml` 的**原 159-162 行**（`spring-boot-starter-amqp`） | **`158-161`**（原值整体 +1） |

⚠️ 本笔的 pom 改动是**纯尾插**（`@@ -161,0 +162,27 @@`）⇒ 上面这两段的行号**没有移动**，
故按 `agent.md`「代码引用是快照，漂了不追」与全仓同批口径（其余 8 个文件里的陈旧引用一律只登记）
处理。**要改的话请单独一笔**，别混进功能笔。

### 被本笔弄失真的原文（共 6 处代码 + 3 处本 pack 自身）

| 文件 | 旧话 | 现话 |
|---|---|---|
| `AttackEngine.java:160`（§八 标题） | 「本笔只收下、只记录，不实现」 | 「D 段 commit 2 起：三键真生效 / 一登记 TD / 一记录位」—— ⚠️ **阶段 2b 再改**：「D 段 commit 2 起三键、**阶段 2b 起四键真生效** / 一记录位」（`:160` 现址不变） |
| `AttackEngine.java:163-164` | 「本笔一条防护逻辑都不实现」 | 「本类一条防护逻辑都不实现 …… 真闸在 D 段 commit 2 落地」—— ⚠️ **阶段 2b 再改**：句尾补「`retry` 于阶段 2b 接上，口径是 **4 键接闸 + 1 键记录位**」（`:163-164` 现址不变） |
| `AttackEngine.java:320`（分节横幅） | 「本笔只收下、只记录」 | 「本类只收下、只记录；真闸在 `ProtectionGate`」（⚠️ **阶段 2b 未动**：它没写死键数，**不需要改**） |
| `AttackEngine.java:325-328`（`PROTECTION_KEYS` 类注释） | 「本笔只负责把它们收下来 …… 一条防护逻辑都不实现」 | 「本类只负责收下来 …… 口径 3 键接闸 + 1 键登记 TD + 1 键记录位」—— ⚠️ **阶段 2b 再改**（现落在 `:327-328`）：「接闸口径自阶段 2b 起是 **4 键接闸 + 1 键记录位** …… **别读成「五枚都接上了」**」 |
| `AttackEngine.java:1652-1653`（`/attack/run` 响应里的 `Engine.Notes`） | 「Protections 五个开关本笔只记录不实现（防护在 D 段）」 | 「五枚里三枚真生效 …… `retry` 只登记 TD-74、`degrade` 只作记录位 ⇒ 面板上不可开关」—— ⚠️ **阶段 2b 再改**（**用户可见的回显文案**，`:1652-1653` 现址不变）：「五枚里**四枚真生效**（`rateLimit` / `circuitBreak` / **`retry`** / `poolIsolation`，阶段 2b）；`degrade` 只作记录位」 |
| `AttackController.java:111`（`@param protections`） | 「本笔只记录不实现（防护逻辑在 D 段）」 | 「D 段 commit 2 起真生效：三键接闸；`retry` 只登记 TD-74、`degrade` 只作记录位」—— ⚠️ **阶段 2b 再改**（`:111` 现址不变）：**四键**（`rateLimit` / `circuitBreak` / `retry` / `poolIsolation`） |
| `AttackConsole.vue:513-515`（SFC 文档块；原 **510-512** 行） | 「5 个防护开关 …… 本笔只收下、只记录、不实现」 | 「防护那组自 D 段 commit 2 起三键真生效 …… 另两枚画成不可开关」—— ⚠️ **阶段 2b 再改**：「自 **D 段 commit 2** 起有真闸、**阶段 2b 起四键**（限流 / 熔断 / 重试 / 分池真生效），只剩 `degrade`（记录位）画成不可开关」 |
| 本 pack `:17`（一句话定位） | 「五个开关**今天只记录不实现**」 | 「**在取点当天**只记录不实现」 |
| 本 pack `:164-169`（三处原话） | 三条原话 + 「D 段做完要逐处改」 | 原文保留为**原话**，各条后面标「**commit 2 已改**」 |
| 本 pack `:266-267`（B1） | 「必须当笔改」 | 同上，尾注「✅ commit 2 已改」 |

⚠️ `api-contract.md#api-h-1` 的载体表注（§⑥ 第 4 条点名的第四处）**已同笔改**（「⚠️ 只记录不实现」→
「✅ 已接闸（3 键 + 1 键 TD + 1 键记录位）」，并接上新附录 `#api-i-3`）；`progress.md` 里那句
「（丁：只记录不实现）」属**commit 10c 那一笔的史料**，按「史料不追」**保持原样**。

### 未还项（**不属本笔，别当成做完了**）

1. **帧读数第 9 条**：§⑨ 第 1 条要的「指标帧要反映防护」**没有做** —— `MetricsSnapshotService`
   的 `protections(...)` 仍返回**8 条**防护命中（`cacheHit` / `lockTimeout` / … / `identityRejected`），
   本笔新增的防护动作**只**从帧的 `counts`（第 9 键 `protection`）与 `mall.protection.events` 指标回答。
   ⇒ 归 **commit 3**（攻防联动那笔要在页面上给出「改读数」的可见后果）。
2. **`retry`** 见 `tech-debt.md` **TD-74**（⚠️ **2026-10-15 阶段 2b 已落码并关闭**，订正表见该文件的 `#td-74-correction` 锚点）；**Redis / MQ 不加熔断**的理由见 **TD-75**。
3. §⑧ 的四条（SSE 连接保护 / MQ 幂等与 DLQ / 无上限列表 / 脱敏）**本笔一条没动** ⇒ 归 commit 3。

---

## 阶段 2b 追加（2026-10-15）—— **口径：3 键接闸 → 4 键接闸 + 1 键记录位** {#pack-c3-block-d-hardening-s2b}

> ⚠️ **本节是上面 commit 2 登记（〈落地登记〉/〈未还项〉）的续笔，不重写它。** 上面凡是写
> 「`retry` 只登记 TD-74 / 三枚可拨 / 3 键接闸」的句子，都是 **commit 2 那一刻的真状态**（保留）；
> 本节只登记**这一笔把它们改成了什么**。
> ⚠️ **判据永远读 `ProtectionGate.WIRED_KEYS`**（`KEYS` 5 个 / `WIRED_KEYS` **4** 个 / 记录位 `degrade` 1 个）。

**这一段做什么** ⇒ 落顾问 2026-10-15 裁定「一 死锁修复（阶段 2b，独立一笔）」的**

| 治则 | 落点 |
|---|---|
| **治因（丙）** 加索引 | Migration ⑳ `goods` 上的 `idx_goods_merchant (MerchantId)`（`mall_management_sql.sql` 段⑳ · `db-schema.md §18` · `db-migration.md` 注意 24）。⚠️ `user.merchantid` 的同类索引 **实测后决定不加** —— `user` 侧那条 `UPDATE … WHERE username = ?` 走的是 **PRIMARY**（`user` 主键**就是** `username`），本来就只锁 1 行；INNODB STATUS 里那 **49 row lock(s)** 全部来自 `goods` 侧 |
| **治果（甲）** 落重试 | `MerchantDowngradeService.relist` —— 装配**编程式** `RetryTemplate`（`RELIST_MAX_ATTEMPTS = 3`、退避 40ms 起 ×2 封顶 200ms、只认 `CannotAcquireLockException`），**不是**注解 `@Retryable`（要读运行时开关） |
| **可视化（要）** | `ProtectionMetrics.retry(...)` → 第 9 环新增两档 `retry_success` / `retry_failed`（`result` 五档 → **七档**） |

**⚠️ 判据怎么读 —— 这一条最容易读反 ⇒ 结论是「死锁次数不变、`5xx` 归零」**

| 轮次文件（`docs/agent/evidence/c3-block7/`） | `goods` 索引 | `relist` 重试 | 1213 增量 | **HTTP 5xx** |
|---|:--:|:--:|---:|---:|
| `d2-deadlock-scout-48x4.txt` | 无 | 无 | 149 | **139** |
| `d2-deadlock-scout-after-index.txt` | 有 | 无 | 139 | **139** |
| `d2-deadlock-scout-with-retry.txt` | 有 | 有 | 141 | **0** |

- **索引确实生效，但 5xx 一个都没少**（139 → 139）：锁粒度由「持 49 row lock(s)」压到「持 `idx_goods_merchant`
  上的**单条** record lock」；**可环没拆掉** —— 环的成因是**两个事务访问顺序相反**（`MerchantApplicationService.audit`
  写序 user→goods × `MerchantDowngradeService.relist` 写序 goods→user），与各自锁住几行无关。
  ⚠️ **既别读成「索引没用」，也别读成「索引能替代重试」。**
- **加**重试**之后 5xx = 0**，而 1213 增量 141 与上一份 139 同量级 ⇒ **死锁照旧在发生，只是每次都被第 2 次尝试兜住**。
  因果闭合的那一环：探针跑完立刻读同进程 actuator ⇒ `mall.protection.events{result=retry_success}` = **141**（与 1213 增量逐一对上）、
  `result=retry_failed` **0 条**（没有一次用满 3 次尝试）。

**⚠️ 落点被实测推翻的那一条（与上面 commit 2 登记相反）**：TD-74 原判落点 = `GoodsService` 的**三处写方法**；
实测真落点 = `MerchantDowngradeService.relist` —— 全仓**唯一**的反向锁序对在上面那一对，
`GoodsService` 那三处**不在任何一个环上**（它们是多表写入，但**没有**任何一个事务按相反顺序碰同一对表）。
⇒ 「多表事务 ⇒ 死锁只可能在这类地方」这条**推理链没错**，错的是「本仓的多表事务里恰好有反向对的那一对在哪」。

**⚠️ 本笔的 code 行号重指（同行替换、净 0 行；只列本 pack 内被顶偏的）**

| 引用处 | 旧（写「原 N 行」不写 token） | 新 | 成因 |
|---|---|---|---|
| 本 pack 原 169 行（三处原话 · 第 3 条） | `AttackConsole.vue` 原 **124** / 原 **134** 行 | **`AttackConsole.vue:127`** / **`:137`** | 该文件上方副标题块净插 3 行 |
| 本 pack 原 266 行（B1） | `AttackConsole.vue` 原 **124** 行 | **`AttackConsole.vue:127`** | 同上 |
| 本 pack 原 296 行（§⑧ 幂等键那一段） | `EventBroadcaster.java` 原 **767** 行 | **`EventBroadcaster.java:774`** | 该文件净增 7 行（第 9 环并入段 + `poll()` 调用） |
| 本 pack 原 452 行（失真原文表 · 末行） | `AttackConsole.vue` 原 **510-512** 行 | **`AttackConsole.vue:513-515`** | 同上副标题块 +3 行 |

⚠️ **旧值一律写「原 N 行」、不写 `` `文件:N` ``** —— 重指记录本身也是一条引用，写成 token 会往引用集合里
注入一个**指向旧位置**的陈旧 token（本仓 §26-3 记过这条坑）。

⚠️ 本 pack 自身声明「引用代码写 `文件:行号`（快照，漂了不追）」—— 这里之所以**追**，是因为本节正在改同批引用，
**不追会留下一条自相矛盾的指路**；**其余未触及的快照行号一律不追**（那是纪律，不是遗漏）。

**未还项（本笔新增，别当成做完了）**

1. **帧读数第 9 条仍未做**（同上面〈未还项〉第 1 条）⇒ 仍归 **commit 3**。
2. **重试的次数与退避是实验值**（3 次 / 40ms 起 / ×2 / 封顶 200ms）：若下游延迟或 `goods` 数据量显著变化，
   应重跑 `scripts/probes/d2-deadlock-scout.mjs` 复核（判据 = `HTTP 5xx` 应为 **0**）。

⚠️ **顺带核实掉的一条（别再照旧说法去加启动参数）**：曾有一条说法是「`ObserveMetricsConfig` 构造注入 +
定义 `MeterFilter` ⇒ 循环依赖、`a3` / `dev` **整台起不来**，要加 `--spring.main.allow-circular-references=true`」。
**2026-10-15 实测该环已自断** —— 那个 `@Bean` 方法已改成 **`static`**（理由逐字写在 `ObserveMetricsConfig`
类注释里：静态 `@Bean` 不需要实例化配置类 ⇒ 环自断）。本笔的 dev 启动**没有**加任何
`allow-circular-references`，后端正常起来并跑完 48 轮死锁侦查（`_work/d2/boot-2b.sh` 头部逐字记着「**刻意不带**」）。
⇒ **那条旧说法已过期**。
