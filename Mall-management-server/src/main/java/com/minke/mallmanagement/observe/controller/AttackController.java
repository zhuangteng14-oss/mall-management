package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.DrillRecordService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 攻击模拟器的三个出口（块 7 · commit 4）：场景清单 / 跑一轮 / 演练记录。
 *
 * <h2>【端点口径：全 POST、不加角色闸、不在白名单】</h2>
 * 三条与本批 SSE 三条**逐字同款**（{@code MetricsStreamController} 写了完整理由）：
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

    public AttackController(DrillRecordService drillRecordService) {
        this.drillRecordService = drillRecordService;
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
     * @param concurrency     总并发数（可选，缺省 100、上限 500）
     * @param durationSeconds 持续秒数（可选，缺省 60、上限 600）
     * @param scenarios       勾选与权重，JSON 数组串：
     *                        {@code [{"key":"seckill","weight":80},{"key":"cacheCold","weight":20}]}。
     *                        缺省 = 全 8 个场景按各自的缺省权重
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
            @RequestParam(value = "scenarios", required = false) String scenarios,
            HttpServletRequest request) {
        AuthSession.Identity who = AuthSession.identify(request);
        try {
            Map<String, Object> payload = drillRecordService.run(name, concurrency, durationSeconds,
                    scenarios,
                    who == null || who.userId() == null ? "" : who.userId(),
                    who == null || who.role() == null ? 0 : who.role(),
                    baseUrlOf(request));
            return ResponseEntity.ok(Result.of(payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
        }
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
}
