package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.observe.service.AttackEngine;
import com.minke.mallmanagement.observe.service.AttackSwitchConfig;
import com.minke.mallmanagement.observe.service.DrillRecordService;
import com.minke.mallmanagement.observe.service.ObservedEventCleanupService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 攻击模拟器的**八个**出口（块 7 · commit 4 建前三条、commit 8b 加第四条、commit 10 加第五条、
 * 块 7 收尾加第六条、**3a.1 加第七条与第八条 = 开关配置**）：场景清单 / 跑一轮 / **叫停** /
 * 演练记录 / **清理演练数据** / **实时进度** / **读开关配置** / **写开关配置**。
 *
 * <h2>【端点口径：不加角色闸、不在白名单】</h2>
 * 八条与本批 SSE 三条**逐字同款**（{@code MetricsStreamController} 写了完整理由）：
 * {@code AUTH_WHITELIST} 维持 14 不动（管理端端点没有公开面，靠 {@code AuthInterceptor} 拦）；
 * 不给 {@code /admin/**} 加角色判据（TD-18 —— 加了之后默认 profile 上管理员自己也进不来）。
 *
 * <h2>【⚠️ 3a.1 的第八条是本域第一条 {@code GET}：破例的代价明写在明处】</h2>
 * 旧版这条注释写的是「管理端读端点在本仓一律 POST」，六条也确实**全 POST**。{@code GET /config}
 * 是**破例**的那一条（用户 2026-10-16 裁定：加 {@code /config} 的 POST + GET）。
 * 两件真实代价，都当场处理掉、不留推论：
 * <ol>
 *   <li><b>读一条 GET 会被浏览器按 HTTP 语义缓存</b>（POST 从来不会）—— 而这条端点恰恰是
 *       「拨了开关之后要立刻读到新值」的读法之一。⇒ 响应**显式**带
 *       {@code Cache-Control: no-store}（见 {@link #config()}），不靠「本仓没有中间缓存」这种
 *       需要额外证明的前提。</li>
 *   <li><b>本域自此有两种 HTTP 方法</b>，前端「按方法找端点」多一个形状 —— 这一条**认下**，
 *       换到的是「读用 GET、写用 POST」在代码里一眼分得开（{@code http.get} 已存在，
 *       见 {@code http.js}）。</li>
 * </ol>
 * ⚠️ 其余七条**一条都没动**：{@code /run} 仍收 {@code @RequestParam}、仍然 POST。
 *
 * <h2>【入参一律 {@code @RequestParam}（+ 一个 JSON 串参数），不用 {@code @RequestBody}】</h2>
 * 三条理由，任一条都足够：
 * <ol>
 *   <li><b>全仓口径</b>：本仓管理端写入端点**全部**收 {@code @RequestParam}，前端一律
 *       {@code URLSearchParams} 字符串（见 {@code http.js#buildInit}：POST 一律带
 *       {@code application/x-www-form-urlencoded}）；本笔之前 {@code @RequestBody} 在全仓
 *       **一次都没出现过**。</li>
 *   <li><b>否则前端发不出去</b>：{@code http.post} 把 POST 的 Content-Type 钉成 urlencoded，
 *       且**没有**留「自定义头」的口子 ⇒ JSON body 到不了 {@code @RequestBody Map}。</li>
 *   <li>「勾选 + 权重」这种**嵌套结构**用 {@code orderAdd} 的既有先例承载：一个 JSON **数组串**
 *       参数（{@code Items} 就是那么干的）。</li>
 * </ol>
 * <p>⚠️ 数值参数也收 {@code String}（不是 {@code Integer}）：交给 {@code AttackEngine.parse}
 * 里的 {@code clampInt} 校验并给出**统一形状**的 400（{@code Result.fail}）。若声明成
 * {@code Integer}，「abc」会在参数绑定阶段就抛 {@code MethodArgumentTypeMismatchException}，
 * 而 {@code GlobalExceptionHandler} 的类级白名单只覆盖 Message / Goods ⇒ 那会变成默认错误页。
 *
 * <h2>【⚠️ {@code /run} 会**阻塞**到演练跑完（几十秒）】</h2>
 * 引擎是同步跑完才回（这样「跑完就能看到数字」，不需要前端轮询 + 拼状态机）。
 * 代价写在明处：<b>调用方必须给这条请求一个远大于默认值的超时</b>
 * （{@code http.post} 的默认 10s 会把 60 秒的演练当场掐断）—— 前端侧在
 * {@code api/observe.js#runAttack} 里带 {@code timeout}。⚠️ 这也是本笔**不**选
 * 「异步起任务 + 轮询」的理由：那要多一个「跑到哪一步了」的状态面，而本仓没有任务表。
 *
 * <h2>【⚠️ 打哪个地址：从**当前请求**推，不引配置项】</h2>
 * 引擎要真发 HTTP（否则演示不出任何一条防护），就得知道自己的地址。两条路：
 * ① 加一个 {@code app.attack.base-url} 配置；② 从这次请求的 {@code scheme / serverName /
 * serverPort} 现推。取 ②——块 7 的边界是「不改部署配置」，且现推出来的地址**永远指回打进来的
 * 那台**（多实例时不会打到别人身上）。
 *
 * <h2>【参数非法走 4xx，不是 200 + {@code success:false}】</h2>
 * 全仓口径是「**业务**失败 200 + {@code {success:false,msg}}，入参**形状**失败 400」
 * （见 {@code http.js} 的 P1.1 那条）。权重写成负数 / 场景 key 不认识 / 并发数越界都是后者
 * ⇒ 400 + {@link Result#fail}。⚠️ 用 {@code ResponseEntity} 显式构造，**不**抛异常 ——
 * 理由同上（{@code GlobalExceptionHandler} 的类白名单不覆盖本类）。
 */
@RestController
@RequestMapping("/admin/observe/attack")
public class AttackController {

    private final DrillRecordService drillRecordService;

    /**
     * 清理服务（块 7 · commit 8b 新增的本类**第二个**依赖）。
     * ⚠️ 它一进构造器，{@code AbstractSmokeTest} 的 {@code @Import} 就**必须**跟着加
     * （{@code ObservedEventCleanupService} + {@code ObservedEventCleanupDao}）——
     * 否则整个 smoke 切片上下文启动失败。这是本仓**第十九次**同一处地雷，见那个文件里的登记。
     */
    private final ObservedEventCleanupService cleanupService;

    /** 审计（块 7 · commit 8b 新增的本类**第三个**依赖）—— 清理是破坏性动作，必须留痕。 */
    private final AuditService auditService;

    /**
     * 攻击开关的**共享配置**（3a.1 新增的本类**第四个**依赖）。
     * ⚠️ 它一进构造器，{@code AbstractSmokeTest} 的 {@code @Import} 就必须跟着加
     * （{@code AttackSwitchConfig} 是 {@code @Component}、切片不扫）—— 这是本仓**第二十三次**
     * 同一处地雷（{@code AttackController} 多一个依赖 + {@code AttackEngine} 也多一个，
     * 两个都是「既有 bean 多吃一个 bean」，形状同 D 段 commit 2 那笔）。
     */
    private final AttackSwitchConfig switchConfig;

    public AttackController(DrillRecordService drillRecordService,
                            ObservedEventCleanupService cleanupService,
                            AuditService auditService,
                            AttackSwitchConfig switchConfig) {
        this.drillRecordService = drillRecordService;
        this.cleanupService = cleanupService;
        this.auditService = auditService;
        this.switchConfig = switchConfig;
    }

    /** 8 个预置场景（key / 中文名 / 同时演示的防护 / 需要的人工动作 / 默认权重）。无入参。 */
    @PostMapping("/scenarios")
    public Result<Map<String, Object>> scenarios() {
        return Result.of(drillRecordService.scenarios());
    }

    /**
     * 跑一轮演练（**同步跑到结束**，见类注释）。
     *
     * @param name            场景名（可选，缺省「自定义」；超 64 字符按列宽截断）
     * @param concurrency     总并发数（可选，缺省 100、上限 4000）。⚠️ 块 7 · commit 10 起它的含义
     *                        收紧成「**在途上限**（且不超过引擎侧的 `in-flight-cap`，缺省 512）」—— 不再等于线程数
     *                        （派发线程固定 32 条），理由见 {@code AttackEngine} 类注释七
     * @param durationSeconds 持续秒数（可选，缺省 60、上限 600）
     * @param targetQps       目标 QPS（可选，缺省 {@code 0} = **不限速**；档位 120 / 400 / 拉满 的载体）
     * @param scenarios       勾选与权重，JSON 数组串：
     *                        {@code [{"key":"seckill","weight":80},{"key":"cacheCold","weight":20}]}。
     *                        缺省 = 全 8 个场景按各自的缺省权重
     * @param protections     ⚠️ **已搬走**：3a.1 起它属于共享配置（{@code /attack/config}），
     *                        本端点**只用来拒收** —— 给了（非空白）就 400 并指路，不静默忽略
     * @param injections      ⚠️ 同上（{@code /attack/config}）
     *
     * <p>⚠️ 身份**取会话**（{@code AuthSession.identify}）：记进 {@code drill_record.OperatorId} /
     * {@code OperatorRole} 的是**谁点的这一下**（认不出 ⇒ {@code ""} / {@code 0}，
     * 不编造 —— 闸关着的 dev / a3 就是这一档）。
     *
     * <p>⚠️ <b>只把「谁来点的」记下来，不把「他的凭据」传下去</b>：引擎自己给演练账号铸票。
     * 转发发起人的 {@code Authorization} / {@code Cookie} 会让身份守卫走管理员豁免（场景 7 的 403
     * 永远出不来）、并让「故意不带票」的那一路被管理员会话认成已登录 —— 理由全文在
     * {@code AttackEngine} 类注释第二节。
     */
    @PostMapping("/run")
    public ResponseEntity<Object> run(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "concurrency", required = false) String concurrency,
            @RequestParam(value = "durationSeconds", required = false) String durationSeconds,
            @RequestParam(value = "targetQps", required = false) String targetQps,
            @RequestParam(value = "scenarios", required = false) String scenarios,
            @RequestParam(value = "protections", required = false) String protections,
            @RequestParam(value = "injections", required = false) String injections,
            HttpServletRequest request) {
        // ⚠️ 3a.1（乙案）：这两个入参**只用来拒收**，不再往下传（形参留着是为了能**当场**说
        //    「你该去哪儿改」，而不是让 Spring 把它们悄悄丢掉 —— 静默丢弃的表现是「开关拨了没反应」）。
        //    ⚠️ 判据是「**给了**」而不是「给了非空数组」：`[]` 在这里也是一个必须被拒的值
        //    （它表达的是「这一轮一个防护都不开」，而这句话现在属于 /config）。
        if (given(protections) || given(injections)) {
            return ResponseEntity.badRequest().body(Result.fail(AttackSwitchConfig.PARAMS_MOVED));
        }
        AuthSession.Identity who = AuthSession.identify(request);
        try {
            Map<String, Object> payload = drillRecordService.run(name, concurrency, durationSeconds,
                    targetQps, scenarios,
                    who == null || who.userId() == null ? "" : who.userId(),
                    who == null || who.role() == null ? 0 : who.role(),
                    baseUrlOf(request));
            return ResponseEntity.ok(Result.of(payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
        }
    }

    // ==================== 3a.1（乙案）：开关配置（第七条 / 第八条出口）====================

    /**
     * 写开关配置 —— 防护 5 键 / 注入 3 键，**进程内一份**（{@code AttackSwitchConfig}）。
     *
     * <h2>【为什么要有这一条：甲案的开关只活在一次请求里】</h2>
     * 甲案把两个开关挂在 {@code /run} 的入参上 ⇒ 另一个页面（实时日志页）既看不到、也改不了；
     * 想在某一轮中间改一个开关，只能重发一次 {@code /run}（而那会再跑一轮）。本端点把它变成
     * **共享配置**：两个页面读的是同一份、写的是同一份，日志页在演练**跑的过程中**也能改，
     * 且改动对**正在跑的那一轮**立即生效（闸逐发请求读、阶段 B 判据实时读）。
     *
     * <h2>【写入语义：{@code null} / 空白 = 不动它，{@code []} = 关光】</h2>
     * 两个参数都是可选的，**没给的那一项不动**（日志页只拨一枚防护开关时，不必把注入那一行
     * 也发过来）。而 {@code []} 是一个**有意义的值**：「一个防护都不开」是演示要用的对照轮 ——
     * 与「没给」必须分开（甲案那条口径逐字保留，见 {@code AttackSwitchConfig} 类注释）。
     *
     * <h2>【⚠️ 跑完不复位，所以这条端点必须与 [恢复默认] 配套出现】</h2>
     * 用户 2026-10-16 裁定：共享配置在一轮演练结束后**不复位**（重启才回默认）。⇒ 拨了忘了拨回来
     * 会**留在进程里**，而真实流量的防护闸读的正是这些位。兜底有三条（{@code AttackSwitchConfig}
     * 类注释第二节目）：响应里的 {@code IsDefault} 让「当前不是默认」**可读**；两页据此常驻提示 +
     * 一键恢复默认（前端落地在 3a.6）；本端点在配置非默认时记一行 **WARN**。
     *
     * <h2>【⚠️ 不写审计：与 {@code /stop} 同一档，代价如实登记】</h2>
     * 它只翻进程内的几个布尔位：不删数据、不改库、不留持久状态 ⇒ 与 {@code /cleanup}
     * （删真行、不可撤销、必须留痕）不是一档。代价明写：**「谁把限流关掉的」在本笔只有日志**
     * （{@code AttackSwitchConfig.update} 那行 WARN 不含操作者 —— 控制器这一层根本没把
     * {@code AuthSession.identify} 的结果传下去）。将来若要追责，得先给本端点加审计动作常量。
     *
     * @return {@code {Protections:[…], Injections:[…], IsDefault:bool, Version:n, Catalog:{…}}}
     *         —— **写入后的**那份快照（不写回来让调用方再读一次：两个页面都会紧接着重绘）
     *         ⚠️ 形状与 {@link #readConfig()} **逐字相同**（同一份 {@code view(...)}）：
     *         轮询那条读到的与写回来的要是两种形状，前端就得写两套解析
     */
    @PostMapping("/config")
    public ResponseEntity<Object> updateConfig(
            @RequestParam(value = "protections", required = false) String protections,
            @RequestParam(value = "injections", required = false) String injections) {
        try {
            return ResponseEntity.ok(Result.of(
                    view(switchConfig.updateFromJson(protections, injections))));
        } catch (IllegalArgumentException e) {
            // 形状错（不是 JSON 数组串）/ key 不认识 ⇒ 400（与本类其余参数校验同一条口径）
            return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
        }
    }

    /**
     * 读开关配置（第八条出口，**本域第一条 {@code GET}** —— 破例的理由见类注释）。
     *
     * <h2>【为什么需要一个「读」】</h2>
     * 乙案里两个页面都是**消费者**：攻击页开场要知道「现在的开关是什么」（不再是自己那份缺省），
     * 日志页要能显示、能一键恢复默认。没有这条读，页面只能各自编一份缺省值 —— 那正是甲案
     * 的老毛病（页面上显示的与实际生效的不是同一件事）。
     *
     * <h2>【⚠️ 无副作用、不写审计】（与 {@code /status} 同一档）</h2>
     * 只读一个进程内快照。
     *
     * <h2>【⚠️ ⚠️ {@code Cache-Control: no-store} 不是装饰】</h2>
     * 这是本域唯一的 GET ⇒ 它会被浏览器按 HTTP 语义**缓存**，而 POST 从来不会。这条端点读的是
     * 「刚刚拨完的那份配置」，缓存一份旧的就是「拨了没反应」的另一种演法。Spring 对本响应不带
     * 任何缓存头 ⇒ 是否命中启发式缓存**取决于浏览器**，不能靠「大概不会」⇒ 显式禁掉。
     *
     * @return {@code {Protections:[…], Injections:[…], IsDefault:bool, Version:n, Catalog:{…}}}
     *         —— {@code Protections} / {@code Injections} 只列**开着的**键（「缺省 = 全开」这件事
     *         已经落成具体清单，读的人不必再知道缺省规则）；{@code IsDefault} 给「⚠️ 当前非默认
     *         配置」那条常驻提示用；{@code Version} 让轮询方判断「这份和我上次看到的是不是同一份」；
     *         {@code Catalog} 的每个键带 {@code Wired} —— {@code false} 的那几枚面板上**不可开关**
     */
    @GetMapping("/config")
    public ResponseEntity<Object> readConfig() {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(Result.of(view(switchConfig.state())));
    }

    /** 两个出口共用的响应形状（见 {@link #updateConfig()} 的 {@code @return} 那条 ⚠️）。 */
    private Map<String, Object> view(AttackSwitchConfig.State st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Protections", st.protections());
        m.put("Injections", st.injections());
        m.put("IsDefault", st.isDefault());
        m.put("Version", st.version());
        m.put("Catalog", switchConfig.catalog());
        return m;
    }

    /** 这个表单参数**给了没**（空串按「没给」处理 —— 表单控件不发空值，同 {@code decodeScenarios}）。 */
    private static boolean given(String raw) {
        return raw != null && !raw.isBlank();
    }

    // ==================== 块 7 · commit 10：叫停（第五条出口）====================

    /**
     * [暂停] —— 叫停**正在跑**的那一轮（A 档清单第 5 条，用户 2026-10-14 选 (a)：加端点）。
     *
     * <h2>【⚠️ 为什么必须是一个端点】</h2>
     * {@code /run} 是**同步跑到结束**的（见类注释：几十秒到几分钟），而在本笔之前全仓没有任何
     * 停止手段 —— 用户 2026-10-14 实测踩到：探针崩了但 {@code /run} 已经起飞，只能干等它跑完。
     * 前端那个 [暂停] 按钮若只做「置灰 + 提示」，就是个**假按钮**。
     *
     * <h2>【⚠️ 语义：停止派发 + 排空在途，不是立即掐断】</h2>
     * 在途请求走引擎的宽限排空（{@code GRACE_SECONDS}），宽限用尽才取消并记 {@code Abandoned}。
     * 硬掐已经真发出去的请求会把它们记成**假失败**（引擎里那条口径的原文见 {@code AttackEngine}）。
     *
     * <h2>【⚠️ 不写审计，理由明写在明处】</h2>
     * 清理端点（{@code /cleanup}）写审计，是因为它**删真行、不可撤销**。本端点只翻一个进程内的
     * 标志位：不删数据、不改库、不改任何持久状态 ⇒ 它不属于「谁动了数据」那一类。
     * 代价如实登记：**「谁叫停的」在本笔只有日志**（引擎侧一行 INFO）。
     *
     * @return {@code {"success":true,"stopped":true/false,"summary":"…"}} —— ⚠️ {@code stopped}
     *         是**如实**的：没在跑就是 {@code false}，并说清「没有在跑的演练」，
     *         不编造成一个 success 了事
     */
    @PostMapping("/stop")
    public Result<Map<String, Object>> stop() {
        boolean stopped = drillRecordService.stop();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("stopped", stopped);
        payload.put("summary", stopped
                ? "已发出停止令：不再投递新请求，在途请求走 3 秒宽限排空（宽限用尽记 Abandoned）"
                : "当时没有正在跑的演练（跑完了 / 从没跑过），未做任何事");
        return Result.of(payload);
    }

    // ==================== 块 7 收尾：实时进度（第六条出口）====================

    /**
     * [暂停] 面板的**实时读数** —— 让那 6 个数里的「已发 M 条」「完成多少次拦截」变成真数。
     *
     * <h2>【⚠️ 为什么必须是一个端点】</h2>
     * {@code /run} 是**同步跑到结束**的（见类注释），跑完才回 —— 页面在它返回之前拿不到任何后端
     * 读数。用户 2026-10-14 点名的 6 个数里，「已跑 N 秒」前端能自己按墙钟估（且页面**如实标注**
     * 了那是估的），但「**已发多少条**」「**完成多少次拦截**」这两条**估不出来** —— 编一个出来就是
     * 假数据。本出口是它们唯一的真源。
     *
     * <h2>【⚠️ 为什么不是 GET】</h2>
     * 与本域其余五条**同形**（全 {@code POST}，见 {@link #scenarios()} 起的那几条）：
     * 本仓前端 {@code api/observe.js} 一律走 {@code POST}，混入一条 {@code GET} 只会给
     * CORS 预检与「按方法找端点」两处各添一个特例。
     *
     * <h2>【⚠️ 无入参、无副作用、不写审计】</h2>
     * 不接收任何参数（没有 runId：{@code AttackEngine} 同一时刻只认真实跑着的那一轮，
     * 见 {@code run()} 里那条「已有演练在跑」的告警）⇒ **没有可越权的入参面**。只读一个进程内
     * 快照：不删数据、不改库、不改任何持久状态 ⇒ 与 {@link #stop()} 同一档，**不写审计**。
     *
     * @return {@code {"success":true, "Running":bool, "BatchId":"…", "ElapsedMs":n, "Sent":n,
     *         "Completed":n, "Blocked":n, "Failed":n, "Shed":n, "Abandoned":n}}。
     *         ⚠️ {@code Running=false} 时其余**全为 0 而不是 {@code null}**（1 秒一次的轮询在演练
     *         收尾那一拍必然撞上它，缺键会让前端把「跑完了」画成「读数丢了」）。
     *         ⚠️ 这几个数是**跑的过程中**的近似快照，**不是**最终账 —— 最终数以
     *         {@link #records} 里那条 {@code drill_record} 为准。
     */
    @PostMapping("/status")
    public Result<Map<String, Object>> status() {
        AttackEngine.Progress p = drillRecordService.status();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Running", p.running());
        payload.put("BatchId", p.batchId());
        payload.put("ElapsedMs", p.elapsedMs());
        payload.put("Sent", p.sent());
        payload.put("Completed", p.completed());
        payload.put("Blocked", p.blocked());
        payload.put("Failed", p.failed());
        payload.put("Shed", p.shed());
        payload.put("Abandoned", p.abandoned());
        return Result.ok(payload);
    }

    /**
     * 演练记录（时间倒序）。每条记录里的 {@code Result} 长文本已由服务层**拆成字段**
     * （方案 §1.5：不是为了展示原始长文本，是为了分字段显示）。
     *
     * @param limit  每页条数（可选，缺省 20、上限 200）
     * @param offset 偏移（可选，负数归 0）
     */
    @PostMapping("/records")
    public ResponseEntity<Object> records(
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "offset", required = false) String offset) {
        try {
            return ResponseEntity.ok(Result.of(drillRecordService.records(limit, offset)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
        }
    }

    // ==================== 内部 ====================

    /** 本服务这次被访问的地址（理由见类注释）。⚠️ 只支持 http —— 本仓没有 TLS 终结那一层。 */
    private static String baseUrlOf(HttpServletRequest request) {
        int port = request.getServerPort();
        String authority = request.getServerName() + ((port == 80 || port == 443) ? "" : ":" + port);
        return request.getScheme() + "://" + authority;
    }

    // ==================== 块 7 · commit 8b：清理（第四条出口）====================

    /**
     * 清掉**攻击演练污染出来的数据**（无入参，一次清干净）。
     *
     * <h2>【⚠️ 本仓第一个破坏性管理端点，所以理由要写足】</h2>
     * <ol>
     *   <li><b>为什么是端点、不是让运维手敲 SQL</b>：块 7 · commit 4 当时的口径是
     *       「清理属运维动作……**不给端点**」（{@code DrillRecordDao} 类注释逐字），清理 SQL 只以
     *       文本形式躺在 {@code AttackEngine} 的演练结果里。演示现场改口径的理由是**答辩场景**：
     *       跑一轮演示泼出去的数据，第二次演示前要能**当场一键恢复**（用户 2026-10-14 裁决
     *       「练完清场」）。让演示人在答辩台上打开 SQL 客户端敲 DELETE，是把一次演示赌在
     *       「他记得住那张表清单」上 —— 而那张清单正是本笔要固化的东西。</li>
     *   <li><b>为什么敢做成无参（一次全清）</b>：判据是**标记**不是**时间**——
     *       `SIM-%` 与 `Source='attack'` 这两样东西，本仓**只有攻击引擎会写**（实测 2026-10-14：
     *       全库只有 {@code order}/{@code order_item}/{@code order_state_transition}/{@code comment}
     *       四张表有，共 10 行）。⇒ 「清掉攻击数据」与「清掉带标记的行」是**同一件事**，
     *       不需要再让人填参数（多一个参数就多一种填错的方式）。</li>
     *   <li><b>⚠️ 但它仍然是破坏性的</b>：删的是**真行**、不可撤销。⇒ 三条护栏：
     *       ① 真删之前不由任何定时任务调用（job 只碰 {@code observed_event}，见其类注释）；
     *       ② 每次调用**写一条审计**（{@code OBSERVE_ATTACK_CLEANUP}，含按表计数）；
     *       ③ 响应把**删了哪几张表各多少行**原样回给前端，不是只回一个 true。</li>
     * </ol>
     *
     * <p>⚠️ 身份**取识别口径**（{@code AuthSession.identify}）：cookie 登录的管理员不该被记成匿名
     * （同 {@code CommentController} 那两处补挂的处置）。闸关着的 dev / a3 上它会返回
     * {@code null} ⇒ 审计行的 {@code ActorId} 记 NULL，**不编造**（同 {@code /run} 的处置）。
     */
    @PostMapping("/cleanup")
    public Result<Map<String, Object>> cleanup(HttpServletRequest request) {
        Map<String, Object> payload = cleanupService.cleanupAttackData();
        auditService.record(AuthSession.identify(request), AuditActions.OBSERVE_ATTACK_CLEANUP, "attack",
                String.valueOf(payload.get("summary")), null, payload.get("deleted"));
        return Result.of(payload);
    }
}
