package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.observe.dao.DrillTargetDao;
import com.minke.mallmanagement.shared.cache.L2HealthProbe;
import com.minke.mallmanagement.shared.web.AuthTokenStore;
import com.minke.mallmanagement.shared.web.ObserveContext;
import com.minke.mallmanagement.shared.web.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 攻击引擎（块 7 · commit 4）—— <b>八个预置场景 / 按权重分线程 / 真发并发 HTTP / 判定三档 / 汇总</b>。
 * 只做这些：不落库（落库在 {@link DrillRecordService}）、不建端点（在 {@code AttackController}）。
 *
 * <h2>【一、它打谁：自己】</h2>
 * 地址由调用方（{@code AttackController}）从**当前请求**推出来传进来（理由见那里的注释：块 7 的
 * 边界是「不改部署配置」）。于是「演练」= 这台服务用真 HTTP 打这台服务自己 —— 走真正的
 * {@code AuthInterceptor} / {@code IdentityGuard} / 锁 / 缓存 / 闸，**不是**进程内直调 Service。
 * ⚠️ 这一点是硬要求：绕过 HTTP 的「演练」演示不出任何一条防护（它们长在 web 层）。
 *
 * <h2>【二、它带什么身份：自己铸的票，**不**转发调用者的凭据】</h2>
 * 引擎在进程内 {@link AuthTokenStore#mint} 一张**真票**（演练账号 = 库里挑出的那个客户的业务号，
 * role=1）。⚠️ <b>刻意不转发发起人（管理员）的 {@code Authorization} 与 {@code Cookie}</b>：
 * <ul>
 *   <li>管理员票会让 {@code IdentityGuard} 走「管理员豁免」那档 ⇒ 场景 7 的 <b>403 永远出不来</b>
 *       （判据被自己的身份绕过了）；</li>
 *   <li>转发 Cookie 更糟：{@code AuthSession.identify} 是 <b>token 优先、其次 cookie</b>，
 *       于是「故意不带票」的那一路会被管理员会话**悄悄认成已登录**（要演示的 401 就消失了）。</li>
 * </ul>
 * ⇒ 一律只带自己铸的票。⚠️ 注意 {@code identify()} **自己**就会解析 Bearer（不看闸的开关），
 * 故 dev / a3 下闸关着也照样能演示出 403（路径：{@code AuthInterceptor} 闸关时提前
 * {@code return true}，但控制器里那次 {@code identify()} 仍认得票）。
 *
 * <h2>【三、三档判定 —— 全仓口径的直接后果】</h2>
 * <pre>
 *   5xx / 404 / 连不上 / 超时 / 响应体不是本仓形状 ......... FAILED（真失败）
 *   401 / 403 / 其余 4xx ................................ BLOCKED（被闸拦下）
 *   2xx 且 body.success = false ........................ BLOCKED（**业务失败是 200**）
 *   2xx 且 body.success = true ......................... PASSED
 * </pre>
 * ⚠️ 「{@code 2xx} 但 {@code success:false} 算拦下」这条是本仓的**必要**口径：全仓业务失败一律
 * 200 + {@code {success:false,msg}}（价格闸 / 出生闸 / 冻结闸 / 幂等 / 敏感词拒收全是这一档）。
 * 只看状态码的话，这些防护**一条都数不出来**。
 * ⚠️ 404 归 FAILED 而不是 BLOCKED：路径写错是**引擎自己的 bug**，把它记成「防护拦下了」等于
 * 用自己的错误刷高防护数。
 *
 * <h2>【四、两阶段：redisDown 必须与其余七个隔开】</h2>
 * 场景 6 要 {@code L2HealthProbe.markFailed(...)} **真注入**一次 L2 故障（否则演示不出冷却窗口）。
 * 而 L2 是全应用**共享**状态 ⇒ 与其余场景同时跑会把「缓存冷启 / 单飞 / 负缓存」的读数一起污染
 * （全线降级成 L1+DB 时，那些场景测的就不是缓存了）。故：
 * <ul>
 *   <li>阶段 A：其余场景按权重并发跑满 {@code durationSeconds}；</li>
 *   <li>阶段 B：{@link #REDIS_DOWN_SECONDS} 秒单独跑场景 6，**跑完即恢复**；</li>
 *   <li>恢复用 {@code markOk()}，且**只在注入前本来是健康态时**才恢复 —— 若 Redis 本来就挂着，
 *       那是真故障，把它的冷却窗口清掉才是错的（清掉 = 伪造一次恢复）。</li>
 * </ul>
 * ⚠️ 如实说明：阶段 B 会让 {@code mall.cache.l2.up} 真的翻转一次、真的记一笔降级。
 * **那不是误报** —— 演练确实制造了一次降级，指标就该动。
 *
 * <h2>【五、靶子与数据标记】</h2>
 * 靶子（SKU / 账号 / 敏感词）由 {@link DrillTargetDao} 在**跑之前**从库里挑一次（见那里的判据）。
 * 演练写下的数据带两个**互补**的标记（C2 甲 + C3 甲，见参考文 §1.4）：
 * <ul>
 *   <li><b>业务串 / 正文前缀 {@link #ATTACK_PREFIX}（{@code SIM-}）</b> —— 订单业务串、评论正文。
 *       它扛的是「人肉看得见」与「{@code LIKE 'SIM-%'} 一把清掉」；</li>
 *   <li><b>写入来源列 {@link #ATTACK_SOURCE}（{@code Source='attack'}）</b> —— 两张表在写它：
 *       {@code comment}（{@code /comment/add} 的可选入参）与 {@code order}
 *       （{@code /order/orderAdd} 的可选入参，边界补笔 2026-10-14 补齐）。</li>
 * </ul>
 *
 * <p>⚠️ <b>块 7 · commit B（2026-10-14）把参考文 §1.4 的两条兑现了，逐条对照（此前本段登记的是
 * 「已知偏离 / 未实现」）</b>：
 * <ol>
 *   <li><b>⑰-4 的用途成立了</b>：Migration ⑰-4 给 {@code comment} 加 {@code Source} 时写的用途
 *       是「攻击场景 8 会写本表 ⇒ 需按 {@code Source='attack'} 与真实数据区分」，而
 *       {@code CommentDao.insertComment} 的列清单里**当时没有 {@code Source}**、{@code /comment/add}
 *       也没有这个入参 ⇒ 演练写下的评论落在 {@code Source=''}，与真实写入不可区分。
 *       本笔给写入链路补上了那条通道：{@code /comment/add} 加**可选** {@code Source} 入参
 *       （{@code required=false}，真实前端一个都不改、落 {@code ''}）；场景 8 发它。</li>
 *   <li><b>「job 扫描时跳过带标记的行」实现了</b>：{@code OrderTimeoutCancelJob} 加了
 *       {@code mall.order.cancel.skip-attack-data}（**dev 默认 false**）。
 *       {@code false} ⇒ 与加它之前逐字节同行为（演练造的未付款 {@code SIM-} 单 60 秒后被真取消，
 *       并触发 commit 1 的库存回补）；{@code true} ⇒ 带标记的行**根本不进候选**
 *       （谓词 = {@code SIM-} 前缀 OR {@code Source='attack'}）⇒ 那一半演练数据逃过 job 注入。
 *       ⚠️ 这正是参考文要的「**一半数据被 job 拦截、一半跳过 job 注入**，能同时看 job 生效 +
 *       其它防护怎么处理」—— 演示时先用 {@code false} 跑一轮看取消、再用 {@code true} 跑一轮看留存。
 *   </li>
 * </ol>
 *
 * <p>⚠️ <b>块 7 · 边界补笔（2026-10-14）把上面那条边界消除了</b>：{@code order.Source} 这一列
 * （块 5 可视化段加的 11 张表之一）此前**没有任何写入点写 {@code 'attack'}** —— 演练订单只带
 * {@code SIM-} 前缀 ⇒ {@code OrderTimeoutCancelJob} 谓词里 {@code Source='attack'} 那一半
 * **匹配不到行**。补笔给订单域也补上了写入通道：{@code /order/orderAdd} 加**可选** {@code Source}
 * 入参（{@code required=false}，真实前端一个都不改、落 {@code ''}）、{@code OrderDao.insertOrder}
 * 写 {@code order.Source} 列，而**本类的四个下单场景（1/2/3/4）经 {@link #orderForm} 统一传
 * {@code Source='attack'}** ⇒ 谓词的两半**都**有真实数据可命中了。
 * ⚠️ 前缀 {@code SIM-} 与列 {@code Source='attack'} **两条都写、不是二选一**（理由见上方两条）。
 *
 * <p>清理口径（两条标记各一份，见 {@code Result.Notes} 与演示手册）：
 * {@code DELETE FROM comment WHERE Source = 'attack';}（结构化，首选）/
 * {@code DELETE FROM comment WHERE Content LIKE 'SIM-%';}（按前缀）/ 订单按业务串前缀删。
 *
 * <h2>【六、不自动还原库存基线】</h2>
 * Migration ⑰-2 给 {@code goods_sku} 加的 {@code BaselineStock} 是**人工四步**的载体
 * （演示前拍快照 / 演示中不动 / 演示后还原 / 结束清空）。本引擎**只读**它当参考值，
 * **不写、不还原** —— 自动还原会把「库存被扣到 0」这条当场证据抹掉，而那正是秒杀场景要看的东西。
 * 演示后的人工还原语句写在 {@code Result.Notes} 里。
 */
@Component
public class AttackEngine {

    private static final Logger log = LoggerFactory.getLogger(AttackEngine.class);

    // ==================== 口径常量 ====================

    /** 总并发缺省值（《块7参考》§1.2 的界面缺省 = 100 并发 / 60 秒）。 */
    public static final int DEFAULT_CONCURRENCY = 100;
    public static final int DEFAULT_DURATION_SECONDS = 60;
    /** 并发上限。⚠️ 每个线程 = 一条真连接（HTTP/1.1 复用有上限）⇒ 无上限等于给了个「一键打挂自己」的按钮。 */
    public static final int MAX_CONCURRENCY = 500;
    public static final int MAX_DURATION_SECONDS = 600;
    /** 单条权重上限（只用来防报文把权重写成天文数字；与线程数分配无关）。 */
    static final int MAX_WEIGHT = 1000;
    /** 阶段 B（Redis 断开）固定秒数 —— 它**不计入** {@code durationSeconds}（见类注释四）。 */
    static final int REDIS_DOWN_SECONDS = 5;
    /** 阶段 B 的线程数上限（L2 降级的观察不需要 100 并发，压太狠只会把 DB 打满）。 */
    static final int REDIS_DOWN_THREADS = 8;
    /** 收口宽限：deadline 之后再等这么久，然后**不打断**在途请求直接返回（见 {@link #runPhase}）。 */
    static final long GRACE_SECONDS = 3;
    /** L2 故障注入用的假异常文案（只用来填 {@code markFailed} 的日志位，**不**代表真连过 Redis）。 */
    static final String REDIS_DOWN_CAUSE = "SIM：演练注入的 L2 故障（阶段 B，跑完即恢复）";

    /** 演练数据标记前缀（C2 甲：订单业务串前缀）。评论正文复用同一个前缀便于统一清理。 */
    static final String ATTACK_PREFIX = "SIM-";

    /**
     * 演练写入的 {@code Source} 列值（C3 甲：`seed / real / attack` 三档里的第三档）。
     *
     * <p>⚠️ <b>与 {@link #ATTACK_PREFIX} 是**同一件事的两种载体，都要写、不是二选一</b>：
     * 前缀扛「人肉看得见 / {@code LIKE 'SIM-%'} 可清」，本列扛「结构化可查」。
     * <p>⚠️ <b>{@code OrderTimeoutCancelJob} 的 {@code skip-attack-data} 谓词按这两个标记跳行</b>
     * —— 它那边有一份**逐字相同的字面量**（{@code "SIM-"} / {@code "attack"}，见该类的
     * {@code isAttackData}）。跨域 import 一个常量不值当（订单域不该依赖演练域），
     * 故这里登记成「**同源字面量**」：谁改这个前缀，必须同时改那个类 ——
     * 它那边也写了一条反向的 ⚠️ 指回本处。
     */
    static final String ATTACK_SOURCE = "attack";

    /** {@code drill_record.Name} 是 {@code varchar(64)}、{@code Scenario} 是 {@code varchar(500)}。 */
    static final int NAME_MAX = 64;
    static final int SCENARIO_JSON_MAX = 500;

    /** 冷键池与波宽（场景 5 / 6）—— 见 {@link #coldCode} 的注释。 */
    static final int COLD_POOL = 64;
    static final int COLD_WAVE = 25;

    // ==================== 八个场景 ====================

    static final String SECKILL = "seckill";
    static final String ORDER_BURST = "orderBurst";
    static final String REPEAT_SUBMIT = "repeatSubmit";
    static final String UNPAID_TIMEOUT = "unpaidTimeout";
    static final String CACHE_COLD = "cacheCold";
    static final String REDIS_DOWN = "redisDown";
    static final String BANNED_USER = "bannedUser";
    static final String SENSITIVE_WORD = "sensitiveWord";

    /**
     * 一个预置场景的**声明**（不是实现）—— 实现是 {@link #fireOnce} 里按 key 分的那几支。
     *
     * @param protections 同时演示的防护（文案对照《块7参考》§1.3 那张表，逐条与代码对应）
     * @param needs       要看到效果所需的**人工前提**（profile / 开关 / 等 job 时钟）
     */
    public record ScenarioSpec(String key, String name, String protections, String needs,
                               int defaultWeight) {
    }

    /**
     * 八个场景，权重缺省合计 100（=《块7参考》§1.2 界面的缺省分布：答辩时「把秒杀调到 80%」
     * 就是在改这里的权重）。⚠️ 顺序即界面顺序，**别随手排序**。
     */
    private static final List<ScenarioSpec> SCENARIOS = List.of(
            new ScenarioSpec(SECKILL, "秒杀抢购", "库存扣减 + CAS + 唯一索引 + 锁",
                    "无。看 Result.Stock：库存只减不增、停在 0 不为负", 15),
            new ScenarioSpec(ORDER_BURST, "订单并发", "出生闸 + 价格闸 + 冻结闸 + 锁 + CAS",
                    "无。四档轮转（合法 / 价格非法 / 出生态非法 / 换一件商品）", 15),
            new ScenarioSpec(REPEAT_SUBMIT, "重复提交", "幂等 + 唯一索引 + 锁",
                    "无。同一个 OrderId 并发打 ⇒ 只有一单落库", 10),
            new ScenarioSpec(UNPAID_TIMEOUT, "超时不付款", "超时取消 job + 库存释放",
                    "要看到取消：dev profile（mall.order.cancel.enabled=true）+ 等 60 秒后的下一拍",
                    10),
            new ScenarioSpec(CACHE_COLD, "缓存冷启", "单飞（sync=true）+ 负缓存",
                    "无。同一波并发撞同一个未缓存键", 10),
            new ScenarioSpec(REDIS_DOWN, "Redis 断开", "冷却窗口 + fail-open",
                    "无（本引擎自己注入并恢复）。阶段 B 单独跑 5 秒", 10),
            new ScenarioSpec(BANNED_USER, "封禁用户攻击", "封禁闸 + 身份校验",
                    "身份校验那路恒可看（403；带票即可，与 profile 无关）；封禁那路的 401 只在默认"
                            + " profile（app.auth.enabled=true）且账号被 banned 时出现", 15),
            new ScenarioSpec(SENSITIVE_WORD, "敏感词攻击", "两阈值（<40% 打码 / ≥40% 拒收）+ 图片先审",
                    "无。会真写评论行（正文带 SIM- 前缀，可按下条 SQL 清理）", 15));

    /** key → 在 {@link #SCENARIOS} 里的下标（计数器按它索引）。 */
    private static final Map<String, Integer> INDEX = new HashMap<>();

    static {
        for (int i = 0; i < SCENARIOS.size(); i++) {
            INDEX.put(SCENARIOS.get(i).key(), i);
        }
    }

    // ==================== 一次跑的产物 ====================

    /**
     * 一轮演练的**汇总结果**（{@link DrillRecordService} 把它拆两半：前四项进 {@code drill_record}
     * 的列，{@code result} 序列化成 {@code Result} 长文本）。
     */
    public record DrillRun(String name, String scenarioJson, int total, int blocked, int failed,
                           long durationMs, Map<String, Object> result, String batchId) {
    }

    /** 判定档位（类注释三）。 */
    enum Outcome { PASSED, BLOCKED, FAILED }

    /**
     * 一个被勾选的场景（+ 它那一轮的权重）。
     * ⚠️ 与 {@link Config}、{@link Outcome} 一样是 **package 可见**（不是 {@code private}）：
     * 它们是 {@link #parse} / {@link #threadPlan} / {@link #classify} 的入参出参，而那三个方法
     * 正是「错一格则整套读数都错」的地方 ⇒ 必须能被同包的单测直接钉住。收成 {@code private}
     * 就等于「这三个方法测不了」，而它们比请求拼装更需要测。
     */
    record Chosen(String key, int weight) {
    }

    /** 一轮演练的入参配置（{@link #parse} 的产物）。 */
    record Config(String name, int concurrency, int durationSeconds, List<Chosen> chosen) {
    }

    /** 一件 SKU 的字段（{@code Price} 原样留字符串：入参是 form 字段，不在这里转数）。 */
    private record Sku(String code, String merchantId, String goodName, String goodsId, String price) {
    }

    /** 本轮的所有靶子（{@code other} 可为 null：库里只有一件可演练 SKU 时）。 */
    private record Target(Sku main, Sku other, String customerId, String account, String sensitiveWord,
                          String victimId) {
    }

    /** 一个场景的三个计数器。⚠️ 三档互斥且求和恒等于 {@code total}（{@link #classify} 保证）。 */
    private static final class Meter {
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger blocked = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();

        void add(Outcome o) {
            total.incrementAndGet();
            if (o == Outcome.BLOCKED) {
                blocked.incrementAndGet();
            } else if (o == Outcome.FAILED) {
                failed.incrementAndGet();
            }
        }

        int passed() {
            return total.get() - blocked.get() - failed.get();
        }
    }

    // ==================== 依赖 ====================

    private final DrillTargetDao drillTargetDao;
    private final L2HealthProbe l2HealthProbe;
    private final ObjectMapper objectMapper;

    /**
     * 真发 HTTP 的客户端。**一个实例复用全程**：{@code HttpClient} 不可变且线程安全、自带连接池；
     * 每轮新建会白白丢掉连接池（100 线程 × 每请求一次 TCP 握手）。
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 本轮用的票。⚠️ 用一个字段而不是到处穿参：{@link #post} 在**每个工作线程**里被调用，
     * 穿参要穿过 {@link #fireOnce} 的八个分支。它在一轮内**只写一次**（{@link #resolveTarget}）、
     * 之后只读，服务是单例 ⇒ 用 {@code volatile} 保证跨线程可见。
     * ⚠️ 代价如实登记：两轮演练**并发**发起时，后发起那轮会把票覆盖掉，先那轮的匿名分支可能带上
     * 后那轮的票。本轮不做「每轮一个 token 上下文」（那要么穿透八个分支、要么 ThreadLocal 跨线程
     * 传递），因为本仓的演示口径是「一次点一个开始」。
     */
    private volatile String currentToken = "";

    /**
     * 本轮演练的**批次号**（块 7 · commit 8c）。一轮一个 {@code UUID.randomUUID()}，
     * 由 {@link #run} 生成、写进两处：
     * <ol>
     *   <li>{@link DrillRun#batchId()} —— 落进 {@code drill_record.BatchId}（这一轮**这件事**）；</li>
     *   <li>每个请求的 {@code X-Drill-Batch} 头（见 {@link #post}）—— 服务端那一侧由
     *       {@code TraceIdFilter} 收进 MDC，于是**这轮打出来的事件**都带上它，
     *       落进 {@code observed_event.BatchId}。</li>
     * </ol>
     * ⚠️ 与 {@link #currentToken} 是同一族的写法（工作线程靠 volatile 读），也**登记同一个代价**：
     * 两轮演练**并发**发起时，后发起那轮会把批次覆盖掉，先那轮工作线程发出去的请求可能带上
     * 后那轮的批次 —— 本仓的演示口径是「一次点一个开始」（见 {@link #currentToken} 的注释）。
     */
    private volatile String currentBatch = "";

    public AttackEngine(DrillTargetDao drillTargetDao, L2HealthProbe l2HealthProbe,
                        ObjectMapper objectMapper) {
        this.drillTargetDao = drillTargetDao;
        this.l2HealthProbe = l2HealthProbe;
        this.objectMapper = objectMapper;
    }

    // ==================== 面 1：场景清单 ====================

    /** 八个场景的清单（形状即前端权重滑条要的东西）。 */
    public List<Map<String, Object>> scenarios() {
        List<Map<String, Object>> out = new ArrayList<>(SCENARIOS.size());
        for (ScenarioSpec s : SCENARIOS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("Key", s.key());
            m.put("Name", s.name());
            m.put("Protections", s.protections());
            m.put("Needs", s.needs());
            m.put("DefaultWeight", s.defaultWeight());
            out.add(m);
        }
        return out;
    }

    // ==================== 面 2：跑一轮 ====================

    /**
     * 跑一轮。**同步**（跑完才回）—— 理由见 {@code AttackController} 的类注释。
     *
     * @param body    前端报文（{@code name} / {@code concurrency} / {@code durationSeconds} /
     *                {@code scenarios:[{key,weight}]}）；非法一律抛 {@link IllegalArgumentException}
     * @param baseUrl 打哪儿（由控制器从当前请求推出）
     */
    public DrillRun run(Map<String, Object> body, String baseUrl) {
        Config cfg = parse(body);
        long startedNanos = System.nanoTime();
        long runSeq = System.currentTimeMillis() % 100000L;   // 只用于造可辨识的业务串
        // 块 7 · commit 8c：一轮一个批次号（varchar(36)，与 drill_record / observed_event 的列宽一致）。
        // ⚠️ 在**发流量之前**就定下来：它要同时出现在「请求头」「记录那一行」「事件台账」三处，
        //    跑完再生成会让前两处没法回填。
        String batchId = UUID.randomUUID().toString();
        currentBatch = batchId;
        Target target = resolveTarget();

        Meter[] meters = new Meter[SCENARIOS.size()];
        Map<String, AtomicInteger> seqs = new HashMap<>();
        for (Chosen c : cfg.chosen()) {
            meters[INDEX.get(c.key())] = new Meter();
            seqs.put(c.key(), new AtomicInteger());
        }
        ConcurrentLinkedQueue<String> orderIds = new ConcurrentLinkedQueue<>();

        int stockBefore = measureStock(target.main().code(), -1);
        Integer baseline = readBaseline(target.main().code());

        // ---- 阶段 A：其余场景按权重并发跑满 durationSeconds ----
        List<Chosen> phaseA = new ArrayList<>();
        Chosen redisDown = null;
        for (Chosen c : cfg.chosen()) {
            if (REDIS_DOWN.equals(c.key())) {
                redisDown = c;
            } else {
                phaseA.add(c);
            }
        }
        if (!phaseA.isEmpty()) {
            runPhase(phaseA, cfg.concurrency(), cfg.durationSeconds(), target, baseUrl, meters, seqs,
                    orderIds, runSeq);
        }

        // ---- 阶段 B：redisDown 单独跑（注入 → 压 → 恢复）----
        Map<String, Object> redisDownInfo = new LinkedHashMap<>();
        redisDownInfo.put("Ran", redisDown != null);
        if (redisDown != null) {
            redisDownInfo.put("Seconds", REDIS_DOWN_SECONDS);
            runRedisDownPhase(target, baseUrl, meters[INDEX.get(REDIS_DOWN)],
                    seqs.get(REDIS_DOWN), Math.min(cfg.concurrency(), REDIS_DOWN_THREADS),
                    redisDownInfo);
        }

        // ---- 汇总 ----
        int stockAfter = measureStock(target.main().code(), stockBefore);
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        Map<String, Object> result = buildResult(target, meters, redisDownInfo, orderIds, stockBefore,
                stockAfter, baseline, runSeq);
        return new DrillRun(cfg.name(), buildScenarioJson(cfg), sum(meters, 0), sum(meters, 1),
                sum(meters, 2), durationMs, result, batchId);
    }

    /** 一个阶段：把 {@link #threadPlan} 排出的线程计划跑起来，到截止时刻收口。 */
    private void runPhase(List<Chosen> chosen, int concurrency, int seconds, Target target,
                          String baseUrl, Meter[] meters, Map<String, AtomicInteger> seqs,
                          ConcurrentLinkedQueue<String> orderIds, long runSeq) {
        List<String> plan = threadPlan(chosen, concurrency);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        CountDownLatch done = new CountDownLatch(plan.size());
        // 块 7 · commit 8c：把 (traceId, batchId) 绑到演练自己的线程上。⚠️ 这里绑的是
        // **批次的来源**那一侧：本线程是「发请求的人」，服务端那一侧靠请求头
        // （X-Drill-Batch，见 post）自己收 —— 两条路都要走，缺一条就有一半事件不带号。
        ObserveContext ctx = new ObserveContext(TraceIdFilter.current(), currentBatch);
        for (String key : plan) {
            // ⚠️ 守护线程：演练是「可被进程关闭打断」的长动作。非守护线程会让一次演练把 JVM 的
            //    关闭拖到整整 durationSeconds 之后（容器刷下去时最难受）。
            Thread t = new Thread(() -> ctx.run(() -> {
                try {
                    Meter meter = meters[INDEX.get(key)];
                    AtomicInteger seq = seqs.get(key);
                    // ⚠️ 一次性场景（超时不付款）发完那一单就**退出**，不空转到截止时刻：
                    //    它等的是 job 的时钟（60 秒），不是本引擎的时钟 —— 占着线程不干活只是浪费。
                    boolean oneshot = UNPAID_TIMEOUT.equals(key);
                    do {
                        meter.add(fireOnce(key, target, baseUrl, seq.getAndIncrement(), orderIds,
                                runSeq));
                    } while (!oneshot && System.nanoTime() < deadline
                            && !Thread.currentThread().isInterrupted());
                } catch (RuntimeException e) {
                    // 单条线程里的意外（例如 URL 拼错）不该带走整轮：记一行、让这个线程退出，
                    // 其余线程照跑。⚠️ 不计入 failed —— 它没发出请求，不是「请求失败」。
                    log.warn("演练线程异常退出：scenario={} cause={}", key, e.toString());
                } finally {
                    done.countDown();
                }
            }), "drill-" + key);
            t.setDaemon(true);
            t.start();
        }
        // 收口 = deadline + 宽限；**刻意不 interrupt 在途请求** —— 一条已经真发出去的请求被
        // interrupt 掉会被记成 FAILED（假失败），比多等几毫秒坏得多。还没醒来的线程在下一个
        // 循环判断处自己退出；它的计数若在快照之后才落，也只是「晚了一拍」，不会算错档位。
        long waitMillis = Math.max(1, (deadline + TimeUnit.SECONDS.toNanos(GRACE_SECONDS)
                - System.nanoTime()) / 1_000_000L);
        try {
            if (!done.await(waitMillis, TimeUnit.MILLISECONDS)) {
                log.warn("演练收口超时（宽限 {}s 用尽），按**已发生**的计数汇总：scenario={}",
                        GRACE_SECONDS, chosen);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("演练收口被中断，按已发生的计数汇总");
        }
    }

    /** 阶段 B：注入 L2 故障 → 压缓存读 → **只在注入前健康时**恢复。 */
    private void runRedisDownPhase(Target target, String baseUrl, Meter meter, AtomicInteger seq,
                                   int threads, Map<String, Object> info) {
        boolean wasHealthy = l2HealthProbe.isHealthy();
        info.put("L2WasHealthyBefore", wasHealthy);
        if (!wasHealthy) {
            // Redis 本来就不可用 ⇒ 不注入（多余），也**不**在收尾时 markOk —— 那会清掉一个真实
            // 故障的冷却窗口，等于伪造一次恢复。
            info.put("Injected", false);
            info.put("Note", "本引擎开跑前 L2 已不可用 ⇒ 未注入、也未恢复（真故障不该被演练清掉）");
        } else {
            info.put("Injected", true);
            l2HealthProbe.markFailed(new IOException(REDIS_DOWN_CAUSE));
        }
        try {
            runPhase(List.of(new Chosen(REDIS_DOWN, 1)), threads, REDIS_DOWN_SECONDS, target, baseUrl,
                    metersOf(meter), Map.of(REDIS_DOWN, seq), new ConcurrentLinkedQueue<>(), 0L);
        } finally {
            if (wasHealthy) {
                l2HealthProbe.markOk();
                info.put("Restored", true);
            }
        }
    }

    /** 只为了让阶段 B 复用 {@link #runPhase} 的签名（它按下标取 {@code Meter}）。 */
    private static Meter[] metersOf(Meter m) {
        Meter[] array = new Meter[SCENARIOS.size()];
        Arrays.fill(array, m);
        return array;
    }

    // ==================== 发一条 ====================

    /**
     * 按场景发一条请求并判定。
     *
     * <p>⚠️ 每个分支只负责**拼参数 + 调 {@link #post}**；「什么算被拦下」一律由 {@link #classify}
     * 判 —— 三档口径只有一处，别在各分支里各写一遍（那必然漂）。
     */
    private Outcome fireOnce(String key, Target t, String baseUrl, int seq,
                             ConcurrentLinkedQueue<String> orderIds, long runSeq) {
        switch (key) {
            case SECKILL: {
                // 同一件 SKU、每次一个新 OrderId、每单 1 件 ⇒ 库存是唯一的稀缺资源（CAS 的地盘）
                String orderId = ATTACK_PREFIX + "SECKILL-" + runSeq + "-" + seq;
                offer(orderIds, orderId);
                return post(baseUrl, "/order/orderAdd",
                        orderForm(t, t.main(), orderId, t.main().price(), 0, 0, 0, 1), true);
            }
            case ORDER_BURST: {
                // 四档轮转：合法 / 价格非法（价格闸）/ 出生态非法（出生闸）/ 换一件 SKU（多商品并发）
                int variant = seq % 4;
                Sku sku = (variant == 3 && t.other() != null) ? t.other() : t.main();
                String orderId = ATTACK_PREFIX + "BURST-" + runSeq + "-" + seq;
                offer(orderIds, orderId);
                String price = variant == 1 ? "999999999" : sku.price();
                int payIf = variant == 2 ? 1 : 0;
                int deliveryIf = variant == 2 ? 1 : 0;
                int receiveIf = variant == 2 ? 1 : 0;
                return post(baseUrl, "/order/orderAdd",
                        orderForm(t, sku, orderId, price, payIf, deliveryIf, receiveIf, 1), true);
            }
            case REPEAT_SUBMIT: {
                // 同一个 OrderId 反复打：唯一索引 + 锁 + 幂等查重 ⇒ 只有第一单落库，其余 success:false
                String orderId = ATTACK_PREFIX + "REPEAT-" + runSeq;
                offer(orderIds, orderId);
                return post(baseUrl, "/order/orderAdd",
                        orderForm(t, t.main(), orderId, t.main().price(), 0, 0, 0, 1), true);
            }
            case UNPAID_TIMEOUT: {
                // PayIf=0 且此后不付款 ⇒ 交给 dev 的超时取消 job（60 秒）去取消并回补库存
                String orderId = ATTACK_PREFIX + "UNPAID-" + runSeq + "-" + seq;
                offer(orderIds, orderId);
                return post(baseUrl, "/order/orderAdd",
                        orderForm(t, t.main(), orderId, t.main().price(), 0, 0, 0, 1), true);
            }
            case CACHE_COLD: {
                // 冷键：@Cacheable(sync=true) 的 priceByTriple —— 同一波（约 25 个并发）撞同一个
                // 从未缓存过的键 ⇒ 只有一条真查库、其余等它（单飞）；查不到 = 0 行 ⇒ 负缓存
                return post(baseUrl, "/goods/productCodePrice",
                        priceForm(t.main(), coldCode(key, seq)));
            }
            case REDIS_DOWN: {
                // 每 4 条夹 1 条冷键（真去碰 L2，现已不可用 ⇒ 走降级路径），其余打固定键的首页列表
                return (seq % 4 == 0)
                        ? post(baseUrl, "/goods/productCodePrice",
                                priceForm(t.main(), coldCode(key, seq)))
                        : post(baseUrl, "/goods/goodsSelectHome", Map.of());
            }
            case BANNED_USER: {
                // 两路交替：
                //  ① 带票，但把「代表自己」的那个入参填成**别人的业务号** ⇒ IdentityGuard 403
                //     （⚠️ 与 profile 无关：identify() 自己解析 Bearer，闸关也拦得住）
                //  ② 不带票 ⇒ 默认 profile 下 AuthInterceptor 答 401；闸关的 dev / a3 下由
                //     IdentityGuard 的 fail-open 放行（**这正是要被看见的现状**，不是 bug）
                if (seq % 2 == 0) {
                    return post(baseUrl, "/cart/list", Map.of("CustomerId", t.victimId()), true);
                }
                return post(baseUrl, "/cart/list", Map.of("CustomerId", t.customerId()), false);
            }
            case SENSITIVE_WORD: {
                // 两档交替：<40% 打码（**回成功**、真写一行）/ ≥40% 拒收（success:false）
                // ⚠️ TargetType 取 user（留言板）而不是 goods：goods 的 review 要过
                //    checkReviewEligible（买过 + 没评过），那两条与敏感词无关的拒绝会把
                //    「敏感词生效」和「你没买过」混成同一个 blocked。
                String content = (seq % 2 == 0) ? maskedContent(t) : rejectedContent(t);
                Map<String, String> p = new LinkedHashMap<>();
                p.put("Account", t.account());
                p.put("TargetType", "user");
                p.put("TargetId", t.victimId());
                p.put("Kind", "review");
                p.put("Content", content);
                p.put("Rating", "5");
                p.put("Anonymous", "0");
                p.put("ProductCode", t.main().code());
                // 块 7 · commit B（2026-10-14）：把「这一行是演练写的」落成**库里的列**，
                // 而不是只靠正文前缀 `SIM-`。⑰-4 给 comment 加 Source 时写的用途逐字是
                // 「攻击场景 8 会写本表 ⇒ 需按 Source='attack' 与真实数据区分」—— 这一行就是它的兑现。
                // ⚠️ 与正文前缀**两条都要**（不是二选一）：前缀扛「人肉看得见 / LIKE 可清」，
                //    本列扛「结构化可查」（前缀是自由文本，`SIM-` 恰好出现在正文中段就会误命中）。
                // ⚠️ `/comment/add` 的 `Source` 是 `required=false` ⇒ 真实前端**不发**它、
                //    落 `''`，「真实写入」与「演练写入」由此分得开。
                p.put("Source", ATTACK_SOURCE);
                return post(baseUrl, "/comment/add", p);
            }
            default:
                throw new IllegalArgumentException("未知的场景 key：" + key);
        }
    }

    /**
     * {@code /order/orderAdd} 的表单（服务端按名取参，顺序无关）。
     *
     * <p>⚠️ <b>四个会下单的场景（1 秒杀 / 2 突发 / 3 重复提交 / 4 未付款超时）全部走本方法</b>
     * ⇒ {@code Source} 只需在这里加一处，四个场景一起具备（别在各分支里再各加一遍）。
     */
    private static Map<String, String> orderForm(Target t, Sku sku, String orderId, String price,
                                                 int payIf, int deliveryIf, int receiveIf,
                                                 int quantity) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("CustomerId", t.customerId());
        p.put("MerchantId", sku.merchantId());
        p.put("OrderId", orderId);
        p.put("Price", price);
        p.put("ProductInformation", sku.goodName());
        p.put("Images", "");
        p.put("PayIf", String.valueOf(payIf));
        p.put("DeliveryIf", String.valueOf(deliveryIf));
        p.put("ReceiveIf", String.valueOf(receiveIf));
        p.put("Quantity", String.valueOf(quantity));
        // 块 7 · 边界补笔（2026-10-14）：把「这一单是演练写的」落成**库里的列**（`order.Source`），
        // 与 `OrderId` 前缀 `SIM-` 是**两条都要**的互补标记（理由见 ①② 与类注释五）。
        // ⚠️ 接口侧是 `required=false` ⇒ 真实前端 / 购物车 / 秒杀页**都不发**它、落 `''`，
        //    「真实下单」与「演练下单」由此在**结构化**维度上分得开（前缀是自由文本，会误命中）。
        // ⚠️ 这也是 `OrderTimeoutCancelJob.skip-attack-data` 谓词 `Source='attack'` 那一半
        //    真正的写入方 —— 在本次补笔之前，那一半匹配不到任何行。
        p.put("Source", ATTACK_SOURCE);
        // ⚠️ 键名 `code` / `name` / `price` / `qty` 取自 OrderService.resolveItems 的解析
        //    （record ItemSpec(code,name,price,qty)）；也**必须**给 Items —— 不给的话它会回落到
        //    「按 ProductInformation 猜」，明细就不可控了。
        p.put("Items", "[{\"code\":\"" + jsonEscape(sku.code()) + "\",\"name\":\""
                + jsonEscape(sku.goodName()) + "\",\"price\":" + price + ",\"qty\":" + quantity + "}]");
        return p;
    }

    /** {@code /goods/productCodePrice} 的表单（三元组；Service 侧 SpEL 依赖这三个形参名）。 */
    private static Map<String, String> priceForm(Sku sku, String productCode) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MerchantId", sku.merchantId());
        p.put("GoodName", sku.goodName());
        p.put("ProductCode", productCode);
        return p;
    }

    /**
     * 冷键的码。**刻意用有界的池 + 有界的波宽**：
     * <ul>
     *   <li>{@code seq / COLD_WAVE} ⇒ 同一波（约 {@value #COLD_WAVE} 个并发）撞**同一个**键 ——
     *       单飞（{@code sync=true}）要被看见，就得「同时有一群人问同一个没人问过的问题」；</li>
     *   <li>{@code % COLD_POOL} ⇒ 整个池子只有 {@value #COLD_POOL} 个键 ⇒ 真落库的查询有上界。
     *       ⚠️ 不这样做的话「冷键」= 每个请求一个新键 = 把缓存演示变成打库（每请求多一次
     *       {@code productCodePrice} 的库读），那不是这个场景要证明的东西。</li>
     * </ul>
     */
    private static String coldCode(String key, int seq) {
        return ATTACK_PREFIX + "COLD-" + key + "-" + ((seq / COLD_WAVE) % COLD_POOL);
    }

    /**
     * 打码档正文：敏感词**出现一次**、其余是足够长的正常字（占比 &lt; 40% ⇒ 打码且**写库**）。
     * 前缀 {@link #ATTACK_PREFIX} 是为了可清理（敏感词只替换命中的那一段，前缀留着）。
     */
    private static String maskedContent(Target t) {
        String word = t.sensitiveWord();
        // 正常字长取词的 4 倍 ⇒ 占比 ≈ len/(5·len + 6) < 20%，稳稳低于 40% 那条线
        int padding = Math.max(8, word.length() * 4);
        return ATTACK_PREFIX + "演练正文 " + word + " " + "正".repeat(padding);
    }

    /** 拒收档正文：全篇都是敏感词（占比 ≈ 100% ≥ 40% ⇒ 拒收、**不写库**）。 */
    private static String rejectedContent(Target t) {
        String word = t.sensitiveWord();
        return ATTACK_PREFIX + word.repeat(Math.max(5, 60 / Math.max(1, word.length())));
    }

    /** 只留最近 5 个订单业务串（{@code Result} 里给演示人一个能直接去库里查的例子）。 */
    private static void offer(ConcurrentLinkedQueue<String> q, String orderId) {
        if (q.size() < 5) {
            q.offer(orderId);
        }
    }

    // ==================== HTTP ====================

    /**
     * 带票的那一版（绝大多数场景走这里）。与下面那个四参方法只差「带不带票」，
     * 拆一个重载是为了让「带票」成为**缺省**：谁要匿名，得显式写 {@code false}（看得见）。
     */
    private Outcome post(String baseUrl, String path, Map<String, String> params) {
        return post(baseUrl, path, params, true);
    }

    /**
     * 发一条 form POST 并判定。
     *
     * @param withToken {@code true} = 带演练账号的票（场景 7 的匿名那一路传 {@code false}）
     */
    private Outcome post(String baseUrl, String path, Map<String, String> params, boolean withToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(params), StandardCharsets.UTF_8));
        if (withToken) {
            builder.header(AuthTokenStore.HEADER, AuthTokenStore.BEARER + currentToken);
        }
        // 块 7 · commit 8c：把本轮批次告诉服务端。⚠️ 它是**服务端那一半**的唯一入口 ——
        // TraceIdFilter 收到它才写进 MDC，之后这轮打出来的事件才带 batchId（见 currentBatch）。
        // ⚠️ 刻意**无条件**带（含匿名那一路）：批次是「这是演练造的」的标记，与带不带票无关。
        if (!currentBatch.isEmpty()) {
            builder.header(TraceIdFilter.BATCH_HEADER, currentBatch);
        }
        try {
            HttpResponse<String> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return classify(resp.statusCode(), resp.body());
        } catch (InterruptedException e) {
            // 这一条还没拿到响应线程就被打断 ⇒ 如实算「真失败」，并复原中断位
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        } catch (IOException e) {
            return Outcome.FAILED;
        }
    }

    /**
     * 三档判定的**唯一出处**（口径与理由见类注释三）。
     * {@code package} 可见是为了能被单测直接钉住 —— 这是本类唯一「错一格就整套读数都错」的函数。
     */
    Outcome classify(int status, String body) {
        if (status == 401 || status == 403) {
            return Outcome.BLOCKED;
        }
        if (status >= 500 || status == 404) {
            return Outcome.FAILED;
        }
        if (status >= 400) {
            return Outcome.BLOCKED;
        }
        Boolean success = readSuccess(body);
        if (success == null) {
            return Outcome.FAILED;
        }
        return success ? Outcome.PASSED : Outcome.BLOCKED;
    }

    /** 从响应体里读 {@code success}：读不出来（不是 JSON / 没有这个键）⇒ {@code null}。 */
    private Boolean readSuccess(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject() || !node.has("success")) {
                return null;
            }
            return node.get("success").asBoolean();
        } catch (Exception e) {
            return null;
        }
    }

    /** form 编码。{@code URLEncoder} 把空格编成 {@code +}（form 的口径，正确）。 */
    private static String formEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(),
                            StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== 参数解析与校验 ====================

    /**
     * 报文 → 配置。**所有非法输入在这里抛 {@link IllegalArgumentException}**（控制器转 400）。
     * ⚠️ 校验口径（缺省 / 上下限 / 勾选为空）写在一处，别拿「前端已经拦过」当理由省掉 ——
     * 端点是可以被 curl 直接打的。
     */
    static Config parse(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String name = truncate(str(b.get("name")), NAME_MAX);
        if (name.isEmpty()) {
            name = "自定义";
        }
        int concurrency = clampInt(b.get("concurrency"), DEFAULT_CONCURRENCY, 1, MAX_CONCURRENCY,
                "并发数");
        int duration = clampInt(b.get("durationSeconds"), DEFAULT_DURATION_SECONDS, 1,
                MAX_DURATION_SECONDS, "持续秒数");

        List<Chosen> chosen = new ArrayList<>();
        Object raw = b.get("scenarios");
        if (raw == null) {
            for (ScenarioSpec s : SCENARIOS) {
                chosen.add(new Chosen(s.key(), s.defaultWeight()));
            }
        } else {
            if (!(raw instanceof List<?> list)) {
                throw new IllegalArgumentException("scenarios 必须是数组");
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException("scenarios 的每一项必须是 {key,weight}");
                }
                String key = str(m.get("key")).trim();
                if (!INDEX.containsKey(key)) {
                    throw new IllegalArgumentException("未知的场景 key：" + key);
                }
                int weight = clampInt(m.get("weight"), 0, 0, MAX_WEIGHT, "权重（" + key + "）");
                // 同一个 key 出现两次 ⇒ 取后者（前端不会这么发，但别让重复项变成两份流量）
                chosen.removeIf(c -> c.key().equals(key));
                if (weight > 0) {
                    chosen.add(new Chosen(key, weight));
                }
            }
        }
        if (chosen.isEmpty()) {
            throw new IllegalArgumentException("至少要勾选一个权重大于 0 的场景");
        }
        // ⚠️「每个勾选的场景都要有流量」是验收第一条 ⇒ 并发数不够分就直接说不，
        //    不悄悄少跑几个（那种「跑完了但少了一个场景」的演示最坑）。
        if (chosen.size() > concurrency) {
            throw new IllegalArgumentException("并发数至少要等于勾选的场景数（勾了 " + chosen.size()
                    + " 个，并发数 " + concurrency + "）");
        }
        return new Config(name, concurrency, duration, chosen);
    }

    /**
     * 权重 → 线程计划：**每个场景先留 1 个线程**，余量按最大余数法分。
     *
     * <p>⚠️ 为什么不「按比例四舍五入、最后给权重最大的找平」：那种写法在混合权重下会把某个场景
     * 算成 **0 个线程**（例：并发 8、权重 4/4/1/1/1/1/1/1 ⇒ 2+2+1×6 = 10 &gt; 8，找平后某一个
     * 归零）—— 而「勾了就一定有流量」是硬要求。先保底 1 个，就不可能出现 0。
     * 返回的列表长度**恒等于** {@code concurrency}，每一项是一个线程要跑的场景 key。
     */
    static List<String> threadPlan(List<Chosen> chosen, int concurrency) {
        int k = chosen.size();
        if (k == 0) {
            throw new IllegalArgumentException("threadPlan：没有场景");
        }
        if (k > concurrency) {
            throw new IllegalArgumentException("并发数 " + concurrency + " 少于场景数 " + k);
        }
        int totalWeight = 0;
        for (Chosen c : chosen) {
            totalWeight += c.weight();
        }
        int spare = concurrency - k;                        // 每场景已保底 1 个
        double[] quota = new double[k];
        int[] extra = new int[k];
        int used = 0;
        for (int i = 0; i < k; i++) {
            quota[i] = totalWeight == 0 ? 0d : (double) spare * chosen.get(i).weight() / totalWeight;
            extra[i] = (int) Math.floor(quota[i]);
            used += extra[i];
        }
        Integer[] order = new Integer[k];
        for (int i = 0; i < k; i++) {
            order[i] = i;
        }
        // 余数大的先补；平手时权重大的先；再平手按声明顺序（结果**确定**、可复算）
        Arrays.sort(order, Comparator
                .comparingDouble((Integer i) -> quota[i] - Math.floor(quota[i])).reversed()
                .thenComparing(Comparator.comparingInt((Integer i) -> chosen.get(i).weight()).reversed())
                .thenComparingInt(i -> i));
        for (int j = 0; j < spare - used; j++) {
            extra[order[j]]++;
        }
        List<String> plan = new ArrayList<>(concurrency);
        for (int i = 0; i < k; i++) {
            for (int n = 0; n <= extra[i]; n++) {           // extra[i] + 1（那保底的一个）
                plan.add(chosen.get(i).key());
            }
        }
        return plan;
    }

    // ==================== 结果 ====================

    private Map<String, Object> buildResult(Target t, Meter[] meters,
                                            Map<String, Object> redisDownInfo,
                                            ConcurrentLinkedQueue<String> orderIds, int stockBefore,
                                            int stockAfter, Integer baseline, long runSeq) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> perScenario = new ArrayList<>();
        for (ScenarioSpec s : SCENARIOS) {
            Meter m = meters[INDEX.get(s.key())];
            if (m == null) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("Key", s.key());
            one.put("Name", s.name());
            one.put("Protections", s.protections());
            one.put("Requests", m.total.get());
            one.put("Passed", m.passed());
            one.put("Blocked", m.blocked.get());
            one.put("Failed", m.failed.get());
            perScenario.add(one);
        }
        out.put("Scenarios", perScenario);

        Map<String, Object> stock = new LinkedHashMap<>();
        stock.put("SkuCode", t.main().code());
        stock.put("Before", stockBefore < 0 ? null : stockBefore);
        stock.put("After", stockAfter < 0 ? null : stockAfter);
        stock.put("Baseline", baseline);
        if (stockBefore >= 0 && stockAfter >= 0) {
            stock.put("Deducted", stockBefore - stockAfter);
        }
        // ⚠️ 秒杀的判据是**库存的两条**（单调不增 + 不为负），不是 blocked 上涨 ——
        //    库存不足时 orderAdd 仍回 success:true（`OrderService.deductStocks` 刻意 fail-open，
        //    注释原话「判成失败会让调用方重复下单」）⇒ 超出的那部分单会照常落库。
        stock.put("SeckillOrders", passedOf(meters, SECKILL));
        out.put("Stock", stock);

        Map<String, Object> markers = new LinkedHashMap<>();
        markers.put("OrderIdPrefix", ATTACK_PREFIX);
        markers.put("CommentContentPrefix", ATTACK_PREFIX);
        markers.put("RunSeq", runSeq);
        markers.put("SampleOrderIds", new ArrayList<>(orderIds));
        out.put("Markers", markers);
        out.put("RedisDown", redisDownInfo);

        List<String> notes = new ArrayList<>();
        notes.add("判据：401/403 与 200+success:false 记 Blocked；5xx/404/超时/响应体不合本仓形状记 Failed");
        notes.add("攻击数据标记 " + ATTACK_PREFIX + "（订单业务串 / 评论正文）+ `Source`='"
                + ATTACK_SOURCE + "'（`order` 与 `comment` **两张表**都写它）。清理："
                + "DELETE FROM `order` WHERE OrderId LIKE '" + ATTACK_PREFIX + "%'；"
                + "DELETE FROM `order` WHERE `Source` = '" + ATTACK_SOURCE + "'；"
                + "DELETE FROM comment WHERE Source = '" + ATTACK_SOURCE + "' 或 Content LIKE '"
                + ATTACK_PREFIX + "%'");
        notes.add("库存基线是人工四步：演示前 UPDATE goods_sku SET BaselineStock = Stock "
                + "WHERE BaselineStock IS NULL；演示后 UPDATE goods_sku SET Stock = BaselineStock；"
                + "收尾 UPDATE goods_sku SET BaselineStock = NULL");
        notes.add("超时取消 job：dev 默认（mall.order.cancel.skip-attack-data=false）会在 60 秒后取消未付款的 "
                + ATTACK_PREFIX + " 单并回补库存 ⇒ 秒杀场景「库存归零」这条证据要当场看；"
                + "置 true 则带标记的行**整个不进候选**（演示「一半被拦截、一半跳过」的另一半）");
        notes.add("封禁闸那一路的 401 只在默认 profile（app.auth.enabled=true）出现；闸关的 dev / a3 下"
                + "IdentityGuard 三处 fail-open ⇒ 匿名请求照常放行（现状，非缺陷）");
        if (t.other() == null) {
            notes.add("库里只有一件可演练 SKU ⇒ 订单并发场景少一档（换商品的对照）");
        }
        out.put("Notes", notes);
        return out;
    }

    private static int passedOf(Meter[] meters, String key) {
        Meter m = meters[INDEX.get(key)];
        return m == null ? 0 : m.passed();
    }

    /** {@code which}：0=总数 / 1=被拦 / 2=真失败。没勾的场景不计。 */
    private static int sum(Meter[] meters, int which) {
        int sum = 0;
        for (Meter m : meters) {
            if (m == null) {
                continue;
            }
            sum += which == 0 ? m.total.get() : (which == 1 ? m.blocked.get() : m.failed.get());
        }
        return sum;
    }

    /**
     * 配置 JSON（进 {@code drill_record.Scenario}，{@code varchar(500)}）。
     * **手写而不是用 Jackson**：要**稳定**的键序与**最短**的字符数（列只有 500 字符，勾满 8 个
     * 场景时长键名会把余量吃掉一半）。超长时截断并附省略号 —— 截断后的串已不是合法 JSON，
     * 读回时会落进 {@code ScenarioRaw} + {@code ScenarioParseError}（记录坏了要看得见，不许静默）。
     */
    static String buildScenarioJson(Config cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"c\":").append(cfg.concurrency())
                .append(",\"d\":").append(cfg.durationSeconds())
                .append(",\"s\":[");
        for (int i = 0; i < cfg.chosen().size(); i++) {
            Chosen c = cfg.chosen().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"k\":\"").append(c.key()).append("\",\"w\":").append(c.weight()).append('}');
        }
        sb.append("]}");
        return truncate(sb.toString(), SCENARIO_JSON_MAX);
    }

    // ==================== 靶子 ====================

    /** 挑靶子 + 铸票。挑不到**抛**（不静默空跑，理由见 {@link DrillTargetDao} 类注释）。 */
    private Target resolveTarget() {
        Map<String, Object> skuRow = drillTargetDao.pickSku();
        if (skuRow == null) {
            throw new IllegalArgumentException("库里挑不到可演练的商品：需要一件「有库存、有规格码、"
                    + "父商品在架未删、商家未冻结」的 SKU —— 演示种子数据导入了吗？");
        }
        Sku main = toSku(skuRow);
        Map<String, Object> otherRow = drillTargetDao.pickOtherSku(main.code());
        Sku other = otherRow == null ? null : toSku(otherRow);

        Map<String, Object> user = drillTargetDao.pickCustomer();
        if (user == null) {
            throw new IllegalArgumentException("库里挑不到可演练的账号：需要一条「有 customerid、"
                    + "未封禁」的用户 —— 演示种子数据导入了吗？");
        }
        String customerId = str(user.get("CustomerId"));
        String account = str(user.get("Account"));
        if (account.isEmpty()) {
            throw new IllegalArgumentException("演练账号缺少登录名（user.username 为空）："
                    + "评论那几条端点按登录名判身份，拿不到就没法演示");
        }
        String word = drillTargetDao.pickSensitiveWord();
        if (word == null) {
            throw new IllegalArgumentException("敏感词表是空的：场景 8（敏感词攻击）没有词可命中");
        }
        currentToken = AuthTokenStore.mint(customerId, 1);
        // 「别人的业务号」：**刻意用派生串**（`<自己>-drill-other`）而不是去库里挑第二个客户 ——
        // 派生串永远不会等于任何真实账号 ⇒ 既保证 403 必然触发，又保证万一路径写错也动不到真人数据。
        return new Target(main, other, customerId, account, word, customerId + "-drill-other");
    }

    private static Sku toSku(Map<String, Object> row) {
        return new Sku(str(row.get("SkuCode")), str(row.get("MerchantId")), str(row.get("GoodName")),
                str(row.get("GoodsId")), str(row.get("Price")));
    }

    // ==================== 小工具 ====================

    /** 库存读数：失败退 {@code fallback}（读库存失败不该让整轮演练失败 —— 它只是结果里的一个数）。 */
    private int measureStock(String skuCode, int fallback) {
        try {
            Integer s = drillTargetDao.stockOf(skuCode);
            return s == null ? fallback : s;
        } catch (RuntimeException e) {
            log.warn("读库存失败（演练不因此中断）：sku={} cause={}", skuCode, e.toString());
            return fallback;
        }
    }

    /** 人工设的基线（没设 ⇒ {@code null}）。同上：读失败只是少一个参考值。 */
    private Integer readBaseline(String skuCode) {
        try {
            return drillTargetDao.baselineStockOf(skuCode);
        } catch (RuntimeException e) {
            log.warn("读库存基线失败（演练不因此中断）：sku={} cause={}", skuCode, e.toString());
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int clampInt(Object v, int fallback, int min, int max, String what) {
        if (v == null) {
            return fallback;
        }
        int n;
        if (v instanceof Number num) {
            n = num.intValue();
        } else {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return fallback;
            }
            try {
                n = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(what + "必须是整数，收到：" + s);
            }
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(what + " 必须在 " + min + " ~ " + max
                    + " 之间，收到：" + n);
        }
        return n;
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * 当前（最近一次发起的）演练批次号；没有在跑的演练时是**空串**。
     *
     * <p><b>为什么要有这个只读出口（块 7 · commit 10 · Q3 路线 i）</b>：状态流转页要显示
     * 「本批次」的五态计数，而批次号**只在服务端当场生成**（{@link #run} 里的
     * {@code UUID.randomUUID()}），页面上没有任何别的途径知道它 ——
     * {@code ORDER BY CreateTime DESC LIMIT 1} 查订单表在演练中会变成自己制造的慢查询
     * （每秒一次全表排序），而 {@code drill_record} 那一行是**跑完之后**才插的
     * （见 {@link DrillRecordService}），跑的过程中读它永远是上一轮。
     *
     * <p>⚠️ 语义边界（与 {@link #currentBatch} 同一条，**不许当成「本轮有效」来读**）：
     * <ul>
     *   <li>它是「**最近一次发起**的批次」，不是「正在跑的批次」—— 一轮跑完后本方法**照样**答
     *       刚才那个批次号（不自动清空）。原因是清空时机需要一个「跑完了」的钩子，而演示要的是
     *       「停下来之后还能看着刚才那批的数字回味」——所以刻意不回空。</li>
     *   <li>没跑过任何一轮时答空串 ⇒ 调用方按空串处理（页面的口径是「没有批次画 {@code —}」）。</li>
     *   <li>两轮**并发**发起时是后发起那轮的批次（同 {@link #currentToken} 登记过的代价）。</li>
     * </ul>
     */
    public String currentBatch() {
        return currentBatch;
    }
}
