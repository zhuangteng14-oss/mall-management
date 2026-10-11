# context-pack: C3 块 5 —— 营销块（三端：券 / 国补 / 折扣模板 / 秒杀双向制） {#pack-c3-block-5-marketing-context-pack}

> 索引：`docs/agent/c3-blocks-index.md`。**只精读下文列出的区间，不要整份读任何文件**。
> ⚠️ **状态（2026-10-11）：pack 新出，未落码。** 本 pack 的「现状」坐标 = 块 5 开工前（PR#89 合并后 `e44d922`）**实测值**，
> 属**快照**；行号随代码增删会漂 ⇒ 回指旧行号写「原 N 行」不带冒号语义、按「开工前实测」理解。
> 方案见 `_work/c3-block5-landing-plan.md`（**A 档 4 项待裁**）。

## 一句话定位 {#pack-c3-block-5-marketing-s2}

管理端「营销运营」块（`docs/agent/c3-modules.md:75-84`）的**四个子域**（券 / 国补 / 折扣模板 / 秒杀），三端一起设计、一起落：

- **用户端** —— **领券 / 用券** + **秒杀参与**（`HomePage.vue` 的「今日秒杀」「领券中心」占位转真；商品页 / 结算用券）；
- **商家端** —— **用折扣**（折扣模板选用 → 商品「划线原价 / 折扣」）+ **报名秒杀**（新面）；
- **管理端** —— **券创建 + 发放** · **国补规则** · **折扣模板**（供商家选用）· **秒杀设置**。

⚠️ **本块最硬的一条业务口径 = 秒杀「双向制」**（`docs/agent/c3-modules.md §9.1:274-290`）：
管理端**只能「邀请」+「审核」，不能单向指定**。两条路径、发起方与决定方**相反**、**落两张表**（刻意不合一）：

- 路径 A 管理端**邀请**商家 → 商家 同意 / 拒绝（`seckill_invitation`）；
- 路径 B 商家**申请** → 管理员**审核**（`seckill_application`）。

⚠️ **秒杀「演示」≠「活动管理」**（`c3-modules.md:82` `:84`）：演示（限流 / 扣减）挂**攻击模拟器下（归块 7）**；
本块只做**活动管理**（邀请 / 审核 / 场次 / 限量声明），**不做库存扣减**（见「约束」8）。

## 依赖块 {#pack-c3-block-5-marketing-s3}

**块 1（通知）· 块 2（审核 CAS）· 块 3（模板）**（`c3-blocks-index.md:17` 依赖列记 `3`；3 传递依赖 2、1）。三者都是**照抄 / 消费**，不扩展接口：

- **块 1** —— 通知 `NotificationService.send(...)`（fail-soft）+ 审计 `AuditService.record(...)`（fail-open）。
  ✅ **秒杀的三条 code 块 1 已为本块预留**，直接引用（`.../notification/NotificationEvents.java`）：
  `:98` `SECKILL_INVITED` / `:101` `SECKILL_APPLICATION_APPROVED` / `:104` `SECKILL_APPLICATION_REJECTED`。
  ⚠️ **券 / 国补 / 折扣的 code 未预留**（21 条清单里无 `coupon.*`）⇒ 本块若发券通知，需在 `NotificationEvents`
  **文件尾加常量**（登记簿只加常量、不改表、不改 `send` —— 同块 4 加 `ORDER_*`；前端标签同步 `notify-events.js`）。
- **块 2** —— **审核 CAS 工作流**（`WHERE 旧态` 条件 UPDATE → `rows>0 / rows==0` 两分支）：**秒杀邀请的应答 / 申请的审核**照它；
  `admin` 共享收件箱（`ToType='admin' / ToId='*'`）用于「商家秒杀申请 → 触达管理端」。
- **块 3** —— **模板机制**（`TemplateTypes` / `TemplateDao` / `admin/TemplateController`）+ 「新表 + 尾部 Migration 块 +
  `information_schema` 守卫」建表手法。⚠️ **折扣模板 = 块 3 `template` 表的一个新 kind**（老表优先加枚举），**不新建表** —— 见「要新建 A2」。

## 要读 {#pack-c3-block-5-marketing-s4}

### 设计意图（读区间） {#pack-c3-block-5-marketing-s5}

- `docs/agent/c3-modules.md:75-84` —— §二 `### 4. 营销运营`：四视图 + 四管理员动作（券创建发放 / 国补规则 / 折扣模板 / 秒杀邀请·审核）。
- `docs/agent/c3-modules.md:274-290` —— §9.1 **秒杀双向制**（两张表 / 三路径 / 「为什么不合成一张」）。
- `docs/agent/c3-modules.md:82` · `:84` —— 秒杀**演示**归块 7、**活动管理**归本块。
- `docs/agent/c3-modules.md:196-219` —— §5.1 **页内 tab 规则**（2–4 个 tab；样式基准 `AccountsPage.vue` 的 `.a-tabs`）⇒ 管理端四子域**一页四 tab**。
- `docs/agent/c3-modules.md:261-268` —— §八边界（不做细粒度权限 ⇒ admin 端点不加角色闸，同 TD-18）。
- `docs/agent/c3-blocks-index.md:17` · `:34` —— 块 5 在 8 块路线里的位置与依赖。

### 块 1 已落的基建 —— 本块要用的现成接口（**与块 2/3/4 相同，直接照抄**） {#pack-c3-block-5-marketing-s6}

- `.../notification/service/NotificationService.java` —— `send(batchId, toId, toType, eventType, title, content, targetType, targetId)：boolean`（fail-soft）。
- `.../notification/NotificationEvents.java:98/:101/:104` —— **本块 3 条秒杀 code 已预留**（见「依赖块」）。
- `.../admin/service/AuditService.java` —— `record(actor, action, targetType, targetId, before, after)：void`（fail-open）。
  ⚠️ `TargetId` 是 **NOT NULL** —— 留痕一律传**业务标识串**（`CouponId` / 活动 Id），**别重蹈 TD-20**（块 3 曾把创建类留痕 100% 丢光）。
- `.../admin/AuditActions.java` —— 常量**追加在文件尾**（零漂移）；本块加 `MARKETING_* / COUPON_* / SECKILL_*` 常量。
- `.../shared/web/IdentityGuard.java` —— `requireSelfOrAdmin(request, paramId)`（方法体**第一行**调）。
- `.../shared/web/Result.java` —— `of/ok/fail`；**禁 `putAll`**。
- `.../shared/web/GlobalExceptionHandler.java` —— ⚠️ `@RestControllerAdvice(assignableTypes={...})` **白名单**：**新 Controller 不在白名单 ⇒ 一个参数约束都不要加**（`@NotBlank` 会逃逸成 400 空体、类级 `@Validated` 撞 CGLIB）。
- `.../smoke/AbstractSmokeTest.java:163-206` —— `@Import({...})` 真 bean 名单：⚠️ **块 1/2/3/4 + 可视化段五次踩同一颗雷**（漏 import ⇒ 整个 `@WebMvcTest` 切片起不来）⇒ **本块新 Controller 依赖 bean 必须同步加进这里**。

### 现状：营销域后端 —— **零实体、零表、零端点**（本块是首个真实实现） {#pack-c3-block-5-marketing-s7}

- ⚠️ 全 `Mall-management-server/src` grep `coupon|subsidy|discount|seckill|营销|优惠券|秒杀|国补|折扣` ⇒ **唯一命中 = `NotificationEvents.java`**（上面那 3 条 code + `:95` 段头）。
  ⇒ **无表、无 DAO、无 Service、无 Controller、无端点**。管理端 `/admin/business/marketing` 今天**零业务**。
- 建表模板见块 3 / 块 4（尾部 Migration 块 + `information_schema` 守卫 + `IF NOT EXISTS`）。⚠️ 表数增长 ⇒ A3 P3 门禁同笔改（见「约束」/「验收」）。

### 现状：管理端「营销运营」面 —— **`AdminStub` 占位** {#pack-c3-block-5-marketing-s8}

- `.../src/router/index.js:454` —— `business/marketing`（`title:'营销运营'`）今天 `component: AdminStub` ⇒ **本块替换点**（**同行替换** ⇒ 零漂移）。
- `.../src/config/admin-nav.js:46` —— 侧栏项 `marketing`（`to:'/admin/business/marketing'`, `glyph:'marketing'`）。**入口已在** ⇒ 导航表**本块不动**。
- `.../src/views/admin/StubPage.vue` —— 占位组件（零请求）。
- ⚠️ **`docs/美化/pages/` 里没有管理端营销稿**（只有 8 个客户 / 商家页）⇒ 管理端这一页**无稿可对**，照 `AccountsPage.vue`(1331 行) / `CatalogPage.vue`(1584 行) / `StatsPage.vue`(687 行) **真页样板**自建。

### 现状：前端占位清单（营销块相关 —— 「占位四件套」= `disabled` + `data-slot` + 角标 + `title`） {#pack-c3-block-5-marketing-docs}

- `.../src/views/HomePage.vue` —— 用户端营销落点最集中：
  · `:31` 「今日秒杀」`disabled`（`title` 注「预留 · 无落点」）/ `:32` 「领券中心」`disabled`；
  · `:102-104` 「优惠券 / 我的券包」快捷项 `disabled`（`title`「待 C3〈促销〉」）；
  · `:116-128` `<section class="flash">` **限时秒杀预留位**（`data-slot`「预留 · 秒杀位」）；
  · `:132-136` `<section class="tickets">` **券包预留位**；CSS 注释 `:698` / `:757-758`。
- `.../src/views/ProductPage.vue` —— `:84-117` 原价 / 折扣 + 促销 / 优惠券预留块（2 枚券 chip `disabled`，`title`「领取优惠券（无端点）」）；弹窗副本 `:353-370`；CSS `.coupon*` `:890-907` / `:1124` / `:1148-1151` / `:1225-1240`。
- `.../src/views/GoodsAdd.vue:143-156` —— 「划线原价 / 折扣」预留块（`disabled`；注「动现有代码 5 处 · 超档」）。
- `.../src/views/UserInfoPage.vue:146` `:149` —— 「积分 / 优惠券 / 余额」KPI 恒 `—`（注「无聚合端点」）。
- 登记出处：`docs/agent/slot-registry.md:932`（#44 促销 / 优惠券）/ `:1172`（`.subnav` 六项含「今日秒杀 / 领券中心」）/ `:1174`（#「全部秒杀 →」）/ `:1409`（`.coupon .get` 2 件「领取」）/ `:618`（#28 划线原价折扣）/ `:931`（#43）/ `:107-110`（#5–#8 首页预留位）。
  ⚠️ 归属列用 **`C3〈促销〉` / `C3〈待定〉`**（该文件**无字面「营销」**）—— grep 归属时按这两个词找。
- ⚠️ **真功能锚点，勿误判为占位**：以上全为占位；营销域今天**没有**任何真功能点（后端零端点）。

### 现状：可复用件（**「三件齐」要用的可视化件** —— 可视化段 2026-10-11 落） {#pack-c3-block-5-marketing-viz}

- **图表** `.../src/components/charts/` —— `BaseChart.vue`(191 行，底座：init / setOption / resize / **三态浮层** / dispose) + `{Line,Bar,Pie,Donut,Heatmap}Chart.vue` + `chartTokens.js`(157 行，颜色**现读 CSS 变量**) + `echarts.js`(44 行，**唯一 `echarts.use(...)` 注册点**)。
- **指标卡** `.../src/components/ui/KpiCard.vue`(197 行) —— 大数字 + 环比（`value` 收**已格式化字符串**、`delta` 收**带符号百分数**）。
- **图标** `.../src/components/ui/IconGlyph.vue`(63 行) + `glyphs.js`(67 行) —— 字形图标 **19 枚**（`marketing:'✦'` 在 `:52`）；**不引 icon 库**。
- **口径文档** `docs/美化/看板规范.md` —— 五条硬规矩（颜色只从 token 现读 / 三态是不透明浮层 / 数字格式化归调用方 / 热力图 `max` 显式给 / 图标用字形）。
- ⚠️ **加图不动结构**（`agent.md`「项目级规则」3）：**只插容器**，不重排 / 不改间距 / 不动 `tokens.css`。

### 样板（照抄真页，别自创风格） {#pack-c3-block-5-marketing-s12}

- **管理端真页 + 页内 tab** `.../src/views/admin/AccountsPage.vue`（`.a-tabs` / `.a-tab` / `.a-tabn`，下划线选中态）—— 本块管理端四子域照它的 tab；**审核动作要求填理由**（驳回理由必填）⇒ 秒杀**驳回**照它。
- **三态回执** `.../src/views/admin/NotifyPage.vue` 的 `doSend()`（`try → if(!data.success) 记 msg → catch 记网络错误 → finally`）。
- **看板页** `.../src/views/admin/StatsPage.vue`(687 行) / `.../src/views/merchant/StatsPage.vue`(547 行) —— **本块页面「图表」那件**的现成样板（`KpiCard` + charts 组件的挂法）。
- **列表取数** `.../src/views/admin/AuditPage.vue` 的 `load()`（错误走 `err.value = …HTTP ${status}`）。
- **弹窗骨架** `.../src/components/TemplatePicker.vue`（块 3 抽出的弹窗形状）。
- **模板选用面** `.../src/views/GoodsAdd.vue`（块 3 的 `TemplatePicker` 接入点）—— 商家端「用折扣模板」照它。

### 表 / 前端入口 / 登记 {#pack-c3-block-5-marketing-s13}

- `.../src/api/endpoints.js`（现 **213 行**，头注释 `:7` 记「现为 83 条」）—— ⚠️ **新增一律追加文件尾**；本块新加 `marketing` / `coupon` / `seckill` 组。
- `.../src/router/index.js` —— 管理端 children `:447-471`，**营销占位 `:454`**；⚠️ **新增路由同行替换或追加**（被引行，见「约束」净零）。
- `.../src/config/admin-nav.js:46` · `.../src/components/ui/glyphs.js:52` —— 入口与字形**已在，本块不动**。
- `docs/agent/db-schema.md` —— 本块新表登记（收口时加 §，走 `#锚点`）。`docs/agent/api-contract.md` —— 本块新端点走**新附录**（成例 = 附录 C/D/E；⚠️ 标题「共 36 个」只含五域）。
- `docs/agent/slot-registry.md` 最高现为 **§二十一 @ `:4558`**（可视化段）⇒ **本块收口开 §二十二**（结构照 §十八–§二十一）。

## 要新建 {#pack-c3-block-5-marketing-s14}

> ### ⚠️ A 档待裁（4 项 —— 落在「建表 / 改契约」硬线上，**用户批了才落**；`agent.md`「项目级规则」5）
>
> 完整论证与备选代价见 `_work/c3-block5-landing-plan.md §一`。
>
> **A1〈券载体〉** —— 新建 **`coupon`（券定义）+ `coupon_grant`（领取 / 发放记录）** 两表（推荐）**vs** 单表塞状态。
> · 甲（**推荐**）两表：定义与领取**两个粒度**（一张券多人领 ⇒ 一行定义对多行领取）。
> · 乙 单表：领取行冗余券面值 —— 券面改一次要回填全部领取行，**不推荐**。
>
> **A2〈折扣模板载体〉** —— **复用块 3 `template` 表加一个 kind**（推荐，**老表优先**）**vs** 新建 `discount_template`。
> · 甲（**推荐**）：块 3 的 `template` 已是「平台方独有、供商家选用」的通用模板表；折扣模板**同形** ⇒ 加 kind。
> · 乙 新表：与块 3 模板机制**平行两套**、商家选用面写两个源 —— **不推荐**（违背 `agent.md`「老表优先」）。
> · ⚠️ 甲乙都要动 `TemplateTypes`（可能连带前端 kind 下拉契约）⇒ **逐条列全影响处数**。
>
> **A3〈国补载体〉** —— 新建 **`subsidy_rule`**（国补规则）一表（推荐）**vs** 复用券表加 `kind=国补`。
> · 甲（**推荐**）独立表：国补是**平台级规则**（按类目 / 比例 / 生效区间），与「一张张券」不同粒度。
> · 乙 复用 `coupon`：语义分叉（国补无「领取」、按类目算）⇒ 列大量空置，**不推荐**。
>
> **A4〈秒杀载体〉** —— 新建 **`seckill_invitation` + `seckill_application`**（§9.1 明定两表）+（可选）`seckill_activity` 场次表，
> 且 **秒杀「限量」存秒杀表、不给 `goods` 加库存列**（推荐）**vs** `goods` 加 `Stock` 列。
> · 甲（**推荐**）：§9.1 **白纸黑字两张表**（`c3-modules.md:283-284`）⇒ 必建；秒杀限量 = **活动属性**，存活动表；
>   ⚠️ **不给 `goods` 加列** —— `selection-status.md §9` 记「秒杀演示前提不存在：`goods` 无库存列、无扣减逻辑」，
>   若为秒杀给 `goods` 加 `Stock` 列 = **改契约**（连带 `GoodsDao` / `Goods*SmokeTest` / A3 P4 种子），**超本块**。
> · 乙 `goods` 加 `Stock`：真库存 + 分布式扣减（需 B1/B2）→ 属「秒杀**演示**」（归**块 7**）⇒ **不推荐**（超范围）。
>
> 附带（**归 B 档**，报备）：本块若发**券通知**需在 `NotificationEvents` **文件尾加 `coupon.*` 常量**（登记簿加常量，不改表 / 不改 `send`）——
> **A 档只在「要不要发券通知」这一业务判断上**；若要，加常量按 B 落。

### B 档（自裁，已报备 —— 不落硬线，可落码） {#pack-c3-block-5-marketing-b}

1. **后端新包** `marketing/`（与 `order/` 平级）：`coupon/` · `subsidy/` · `discount/` · `seckill/` 四子域（各 `dao / service / controller`）。
2. **管理端新页** `.../src/views/admin/MarketingPage.vue`（**新建**），**同行替换** `router/index.js:454`；**四子域 = 一页四 tab**（券 / 国补 / 折扣模板 / 秒杀，§5.1）。
3. **用户端**：`HomePage.vue` 秒杀位 / 券包位占位转真（领券 / 秒杀入口）；`ProductPage.vue` 券 chip「领取」接通；**用券**落结算（与块 4 订单侧联动 —— 只加「券」入参，**不动订单状态机**）。
4. **商家端**：`GoodsAdd.vue` 折扣模板选用（接块 3 `TemplatePicker`）；**秒杀报名**新面（申请 + 看邀请）。
5. **通知接线**：秒杀三条 code（**已预留**）+ （可选）`coupon.*` 常量；`AuditActions` 加 `MARKETING_*` 常量（追加文件尾）；前端标签补 `coupon.*`（若加）。
6. **前端 API 分层**：**新建** `.../src/api/marketing.js`；`endpoints.js` **文件尾追加**（`marketing` / `coupon` / `seckill` 组）。

### C 档（自决 —— 落码时按此，不单独问） {#pack-c3-block-5-marketing-c}

- **秒杀「限量」只声明、不扣减**（扣减属「秒杀演示」，归块 7）；场次「状态」用 CAS 推进（照块 2）。
- **国补** = 规则表 + 命中计算（读侧现算），**不接外部数据源**。
- **用券口径**：券折抵在**下单金额**上现算（快照落订单，同 `GoodsService` 快照口径），**不改 `order` 状态机**。

### 表（**待 A 档裁后定稿** ⇒ 追加 `mall_management_sql.sql` **末尾**，`IF NOT EXISTS`，`information_schema` 守卫，规则 7 / 8） {#pack-c3-block-5-marketing-sql}

- **`coupon`**（A1 裁甲）：券定义（`Id` / `CouponId`(业务串) / `Name` / `Type`(满减 / 折扣) / `FaceValue` / `Threshold` / `TotalQty` / `ClaimedQty` / `MerchantAccount` / `Status` / 时刻列 / UNIQUE(`CouponId`)）。
- **`coupon_grant`**（A1 裁甲）：领取 / 发放记录（`Id` / `CouponId` / `ToAccount` / `Status`(未用 / 已用 / 过期) / `UsedAt` / `OrderId` / 时刻列）。
- **`seckill_invitation` / `seckill_application`**（A4）：§9.1 两张**镜子表**（`Id` / `MerchantAccount` / `GoodsId` / `Quantity`(声明限量) / `Status` / `Reason` / 时刻列）。
- **`subsidy_rule`**（A3 裁甲）：国补规则（`Id` / `RuleId` / `CategoryMatch` / `Ratio` / `StartAt` / `EndAt` / `Status` / 时刻列）。
- 每表满足规则 8（InnoDB / utf8mb4 / 主键 / `created_at`+`updated_at` / 并发查询字段加索引）；**不设 FK**（基线表全量重导会 `DROP`）。
- ⚠️ **表数变化** ⇒ 同笔更新 `scripts/concurrency/ConcurrencyRunner.java` P3（**当前 `KNOWN_TABLES = 11` @ `:2662`、期望表数 22 @ `:2663`；`IN` 清单 + `KNOWN_TABLES` + 计数注释 + PASS 文案四处同改**）+ `db-schema.md` 表数。

### 后端（**建议**分层 —— B 档，报备） {#pack-c3-block-5-marketing-s18}

- 新包 `marketing/`（四子域）：`dao/*` · `service/*` · `controller/*`。写路径挂钩照块 2/3/4（`AuditService.record` + `NotificationService.send`）。
- 端点（**新增**）：管理端 `/admin/marketing/{coupon,subsidy,discount,seckill}/*`（券 CRUD + 发放 / 国补规则 / 折扣模板 / 秒杀邀请·审核）；用户端 `/marketing/{coupons,claim,seckill-join}`；商家端 `/merchant/marketing/{discounts,seckill-apply,seckill-invitations}`。
- ⚠️ **测试面必改**：新 Controller 依赖 bean 同步进 `AbstractSmokeTest.java:163-206` 的 `@Import`（**第六次同一雷**）；新 Controller **不得**带类级 `@Validated`。

### 前端（三端 —— 每页**三件齐**） {#pack-c3-block-5-marketing-s19}

- **管理端**：**新建** `MarketingPage.vue`（四 tab）；每 tab **三件齐** = 表格（列表 / 明细）+ 图表（`charts/` 一张）+ 图标（`IconGlyph`）。
- **用户端**：`HomePage.vue` 占位转真（领券 / 秒杀入口）+ 券包；`ProductPage.vue` 券 chip 接通；结算用券。
- **商家端**：`GoodsAdd.vue` 折扣模板选用；秒杀报名（新面）。
- `.../src/api/marketing.js` **新建**；`endpoints.js` **文件尾追加**。

## 约束 {#pack-c3-block-5-marketing-s20}

1. **fail-open / fail-soft**：审计与通知**永不抛**（同块 1/2/3/4）。
2. **逻辑废弃，不物理删**（规则 v2）：券 / 秒杀记录一律**停用**，不 `DELETE` / `DROP`。
3. **admin 端点不加角色闸**（TD-18）。
4. ⚠️ **加图不动结构**（`agent.md`「项目级规则」3）：往页面加图表 / `KpiCard` **只插容器**，**不重排 / 不改间距 / 不动 `tokens.css`**；颜色一律走 token（`docs/美化/看板规范.md`）。
5. **既有端点原则不动**：本块**动既有契约的处数**在 A 档裁后逐条列全（预期 = `router:454` 同行替换；`endpoints.js` / `TemplateTypes` 追加；**A4 裁甲则不动 `goods`**）；**其余一律「新增」**。
6. **净零行数**：`router/index.js`（被引 ≈`462`）/ `endpoints.js`（213 行）/ `admin-nav.js`（`:43-45` 被引）⇒ 新增**追加文件尾**或**同行替换**；动被引行前后重跑 `refs-scan.sh`。
7. **时区**：新表时刻列一律 `CURRENT_TIMESTAMP(3)`（DB 存 UTC），前端渲染再转浏览器时区。
8. ⚠️ **秒杀扣减不在本块**（归块 7「秒杀演示」）；本块**不碰 `OrderStateMachine`**、**不给 `goods` 加减库存列**。
9. ⚠️ **手验提醒（每次）**：日常手验挂 **`dev`** profile；`dev` 鉴权闸**也是关的**（闸关，手验绿 ≠ 默认 profile 绿）⇒ 身份敏感端点（券归属 / 秒杀报名归属）**必须在默认 profile 再验一遍**。
10. **快照坐标口径**：本 pack「现状」行号 = 开工前实测，属快照；回指旧行号写「原 N 行」不带冒号。

## 验收 {#pack-c3-block-5-marketing-s21}

> **验收归用户**（`agent.md`「项目级规则」1）：以下由**用户**跑并裁决；agent **不主动**写大型验证探针。

**核心项**：

1. **建表 / 迁移**（按 A 裁决）：新表应用到 **VM 库**（**不整份重导**）；`information_schema` 见新表；起点计数为 0。
   ⚠️ **CI 连带**：表数变化 ⇒ `ConcurrencyRunner.java` P3 门禁**会变红**，**必须同笔更新** `KNOWN_TABLES` + `IN` 清单 + 计数注释 + PASS 文案（**四处同改**）。
2. **编译**：`cd Mall-management-server && ./mvnw -q -DskipTests compile` 退出 0。
3. **主链路**（真后端 + VM 库，**不 mock**）：
   · **券**：管理端创建券 → 用户**领取** ⇒ `coupon_grant` 有行 → 下单**用券** ⇒ 金额折抵 + 券置「已用」；
   · **折扣模板**：管理端建折扣模板 → 商家**选用** ⇒ 商品页显示「划线原价 / 折扣」；
   · **秒杀双向制（本块验收核心）**：① 管理端**邀请**商家 ⇒ 商家收 `seckill.invited` → 商家**接受** ⇒ 活动成立；② 商家**申请** ⇒ 管理端**收件箱**收到 → 管理端**审核**（通过 ⇒ 商家收 `seckill.application.approved` / 驳回 ⇒ 收 `seckill.application.rejected`）；
   · **国补**：管理端建国补规则 ⇒ 命中类目的商品 / 下单按规则折抵。
4. ⭐ **三件齐（表格 + 图表 + 图标）** —— **三端营销相关页面逐一目视核**，每页**三样俱全**：
   · **表格** —— 列表 / 明细（数据表本体）；
   · **图表** —— 至少一张 `charts/` 组件（`BaseChart` 族，`chartTokens` 取色）；
   · **图标** —— `IconGlyph`（`glyphs.js` 19 枚字形之一，**不是裸字符**，`docs/美化/看板规范.md §2 规矩 5`）。
   ⚠️ 判据 = **页面上真出现这三件**（图表要**画布上有像素**，不是有 `<canvas>` 标签）；**缺一即不通过**。
5. **不回归**：`./mvnw -B test` **全绿**（新 Controller 同步 `AbstractSmokeTest.java:163-206` 的 `@Import`）+ 既有 A3 全绿；`npm run build` DONE。
6. **通知三态**：正例有行 / 负例无行 / **`notification` 表新增行可指认**（**回库读**，不看接口返回 —— 块 3 的教训）。
7. **权限三档**：非本人传**别人的** `MerchantAccount` / `Account` → 403 / 本人 → 放行 / 管理员 → 放行。

**抽查**：`bash scripts/doc-anchor-check.sh`（本 pack 悬空引用 0）；`refs-scan.sh`（动被引行时）；审计三态（有改前态的动作 `BeforeJson` 非空）。

## 扩展阅读（不够时按指针读） {#pack-c3-block-5-marketing-s22}

- `docs/agent/context-packs/c3-block-4-order.md` —— **本 pack 的模板**（六段结构 + 「块 1 基建」照抄手法 + A/B/C 档）。
- `docs/agent/context-packs/c3-block-3-goods.md` —— **模板机制**（`template` 表 / `TemplateTypes` / 提审制）出处；折扣模板照它。
- `docs/agent/context-packs/c3-block-2-user.md` —— **审核 CAS 工作流 + admin 收件箱**出处；秒杀审核照它。
- `docs/agent/context-packs/c3-block-1-infra.md` —— 挂钩四步与两条管子（通知 / 审计）完整口径。
- `docs/agent/c3-modules.md:75-84` · `:274-290` · `:196-219` —— 范围 / 秒杀双向制 / 页内 tab。
- `docs/美化/看板规范.md` —— **三件齐**里「图表 / 图标」两件的落码口径。
- `_work/c3-block5-landing-plan.md` —— **本块的落地方案**（A 档 4 项备选与代价 / B·C 档 / 表·后端·前端 / 验收 / 登记 / 零漂移）。
- `docs/agent/slot-registry.md §二十二`（**待开**，照 §十八–§二十一）。
