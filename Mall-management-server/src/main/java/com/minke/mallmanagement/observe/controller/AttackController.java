package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.observe.service.DrillRecordService;
import com.minke.mallmanagement.observe.service.ObservedEventCleanupService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 攻击模拟器的五个出口（块 7 · commit 4 建前三条、commit 8b 加第四条、commit 10 加第五条）：
 * 场景清单 / 跑一轮 / **叫停** / 演练记录 / **清理演练数据**。
 *
 * <h2>【端点口径：全 POST、不加角色闸、不在白名单】</h2>
 * 五条与本批 SSE 三条**逐字同款**（{@code MetricsStreamController} 写了完整理由）：
 * 管理端读端点在本仓一律 POST；{@code AUTH_WHITELIST} 维持 14 不动（管理端端点没有公开面，
 * 靠 {@code AuthInterceptor} 拦）；不给 {@code /admin/**} 加角色判据（TD-18 —— 加了之后默认 profile
 * 上管理员自己也进不来）。
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

    public AttackController(DrillRecordService drillRecordService,
                            ObservedEventCleanupService cleanupService,
                            AuditService auditService) {
        this.drillRecordService = drillRecordService;
        this.cleanupService = cleanupService;
        this.auditService = auditService;
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
     * @param concurrency     总并发数（可选，缺省 100、上限 500）。⚠️ 块 7 · commit 10 起它的含义
     *                        收紧成「**在途上限**（且不超过引擎侧的硬顶 512）」—— 不再等于线程数
     *                        （派发线程固定 32 条），理由见 {@code AttackEngine} 类注释七
     * @param durationSeconds 持续秒数（可选，缺省 60、上限 600）
     * @param targetQps       目标 QPS（可选，缺省 {@code 0} = **不限速**；档位 120 / 400 / 拉满 的载体）
     * @param scenarios       勾选与权重，JSON 数组串：
     *                        {@code [{"key":"seckill","weight":80},{"key":"cacheCold","weight":20}]}。
     *                        缺省 = 全 8 个场景按各自的缺省权重
     * @param protections     防护开关（丁），JSON 数组串：缺省 = 全开
     *                        （{@code rateLimit / circuitBreak / degrade / retry / poolIsolation}）。
     *                        ⚠️ 本笔**只记录不实现**（防护逻辑在 D 段）
     * @param injections      注入开关（丁），JSON 数组串：缺省 = 全关
     *                        （{@code redisDown / mqDown / dbSlow}）。⚠️ 只有 {@code redisDown} 真接通
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
        AuthSession.Identity who = AuthSession.identify(request);
        try {
            Map<String, Object> payload = drillRecordService.run(name, concurrency, durationSeconds,
                    targetQps, scenarios, protections, injections,
                    who == null || who.userId() == null ? "" : who.userId(),
                    who == null || who.role() == null ? 0 : who.role(),
                    baseUrlOf(request));
            return ResponseEntity.ok(Result.of(payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
        }
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
