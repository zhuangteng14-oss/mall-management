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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * **不写、不还原** —— 自动还原会把「库存被扣到 0」那条当场证据抹掉，而那正是秒杀场景要看的东西。
 * 演示后的人工还原语句写在 {@code Result.Notes} 里。
 *
 * <h2>【七、甲：异步引擎（块 7 · commit 10）—— 把「线程数 / 在途数 / 栈内存」三件事拆开】</h2>
 * 改之前：一条线程 = 一个在途请求（{@code new Thread} 按 {@code threadPlan} 逐个起，线程内
 * {@code do { fireOnce } while(<deadline)}），发请求是**同步阻塞**的 {@code http.send} ⇒
 * 「并发数」这一个旋钮**同时**决定了在途请求数、平台线程数、栈内存占用（200 并发 = 200 MB 栈）。
 * 改之后：
 * <ul>
 *   <li><b>派发线程钉死 {@value #WORKER_THREADS} 条</b>（{@link #WORKER_THREADS}）—— 与并发数无关；</li>
 *   <li><b>在途数由信号量管</b>（{@link #inFlightCap}，许可数 = {@code min(并发数, 硬顶)}）；
 *       一条线程可同时挂多条在途（{@code http.sendAsync} 返回即转下一拍）；</li>
 *   <li><b>背压显式化</b>：拿不到许可最多等 {@link #shedWaitMs} 毫秒 ⇒ 弃投并计数
 *       （{@code Shed}），**不**记 failed（那条请求根本没发出去，记 failed 是撒谎）。</li>
 * </ul>
 * ⚠️ <b>本笔只提升引擎侧的能力，一个字都不动后端的性能</b>（用户 2026-10-14 的边界 A）：
 * 池仍 10、Tomcat 仍 200、慢 SQL 照旧慢 —— 甲只是让引擎能更狠地**撞**那个瓶颈，
 * 而那正是演示要的触发条件（瓶颈定位是 10c-1，另外一笔，只观察不解决）。
 * ⚠️ 因此登记一条**行为变更**：{@code concurrency} 这个入参的含义从「线程数」收紧成
 * 「在途上限（且不超过 {@link #inFlightCap}）」—— 同一个 500 并发不再意味着 500 条线程。
 *
 * <h2>【八、丁：防护 / 注入开关（本笔只收下、只记录，不实现）】</h2>
 * {@code /attack/run} 新增两个入参（{@code protections} / {@code injections}），它们成为
 * {@code drill_record.Scenario} 的一部分 ⇒「这一轮是按哪套开关跑的」**可复现**。
 * ⚠️ 但<b>本笔一条防护逻辑都不实现</b>：{@code protections} 五个开关后端只读、只记、只打日志，
 * 「限流 / 熔断 / 降级 / 重试 / 分池」的落地属 D 段（硬边界 A：加固与补救都在 D 段）。
 * 三个注入里**只有 {@link #INJECTION_REDIS_DOWN} 真接通**（复用 {@code L2HealthProbe} 那套现成原语，
 * 见类注释四）；{@code mqDown} / {@code dbSlow} 收得下、记得住，但没有任何实现
 * —— 结果里会把它们列进 {@code NotWired}，免得看数字的人以为它们生效了。
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
    /**
     * 收口宽限：deadline 之后再等这么久让在途请求**自然收尾**；宽限用尽才取消并记
     * {@code Abandoned}（见 {@link #runPhase} 与 {@link #drain}）。
     * ⚠️ 与改动前的差别只有一处：旧版宽限用尽就「按已发生的计数汇总」直接返回，
     * 未完成的那些请求**不落任何账**；现在它们落 {@code Abandoned}（一条读数，不是一条失败）。
     */
    static final long GRACE_SECONDS = 3;

    /**
     * 阶段 A 的**固定派发线程数**（本笔最要紧的一个数）。
     *
     * <p>取值理由：它是「派发」不是「执行」—— 每条线程只做「限速 → 取许可 → 拼请求 →
     * {@code sendAsync} 返回即转下一拍」，单条线程能推的派发速率远高于真实 QPS
     * （实测档位见 {@code _work/c10-200x300.json}）。32 条足够推满 854 QPS 一档，
     * 而栈占用从「并发数 × 1 MB」降到恒定 32 MB。
     */
    static final int WORKER_THREADS = 32;

    /** 非 PASSED 请求的记账环容量（见 {@link ReqLog}）。 */
    static final int REQ_LOG_CAPACITY = 512;

    /** 结果里随环带出的样例条数 —— 全量带出会把 {@code drill_record.Result} 与报文一起打肥。 */
    static final int REQ_LOG_SAMPLE = 20;

    /** 取消之后给「记账」落定的时间（取消是异步生效的，见 {@link #drain}）。 */
    static final long CANCEL_SETTLE_MS = 500;

    /** 目标 QPS：缺省 {@code 0} = **不限速**（= 加这个参数之前逐字节同行为），上限只防手滑写天文数字。 */
    static final int DEFAULT_TARGET_QPS = 0;
    static final int MAX_TARGET_QPS = 100_000;

    /**
     * 出站请求号头（A 档清单第 2 条，用户已批）。
     *
     * <p>⚠️ <b>如实说明它的作用面：服务端**不消费**这个头</b> —— {@code TraceIdFilter} 只认
     * {@code X-Drill-Batch}，{@code observed_event} 也没有能放它的列。它的用处是「同一个 id
     * 既出现在引擎侧的失败环里、又出现在 Tomcat 侧的访问日志里」，用来回答「刚才那一条 500
     * 到底是哪一发」。⇒ 它**只做出站标记，不做服务端落地**（落地要动表结构，不在本笔）。
     */
    static final String REQ_HEADER = "X-Drill-Req";
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
     * 一个预置场景的**声明**（不是实现）—— 实现是 {@link #buildCall} 里按 key 分的那几支。
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

    // ==================== 丁：防护 / 注入开关（本笔只收下、只记录）====================

    /**
     * 五个**防护开关**的 key（丁的开关面板，用户 2026-10-14 定稿：默认全开）。
     *
     * <p>⚠️ <b>本笔只负责把它们「收下来、记进演练记录、打进日志」，一条防护逻辑都不实现</b>
     * —— 硬边界 A 逐字是「加固（扩池 / 扩线程 / 优化慢 SQL）与补救（限流 / 熔断 / 降级 / 重试）
     * 都在 D 段」。这里存在的作用是**接口位**：开关先成为 {@code drill_record.Scenario} 的一部分
     * （于是「这一轮是按哪套开关跑的」可复现），D 段再让它们真的接上闸。
     */
    static final List<String> PROTECTION_KEYS =
            List.of("rateLimit", "circuitBreak", "degrade", "retry", "poolIsolation");

    /**
     * 三个**故障注入开关**的 key（默认全关）。
     *
     * <p>⚠️ 本笔只有 {@link #INJECTION_REDIS_DOWN} 一个**真接通**（走 {@code L2HealthProbe} 那套
     * 现成原语，见类注释四）；{@code mqDown} / {@code dbSlow} 收得下、记得住，但**没有实现**
     * —— 前者在 {@code RabbitEventPublisher} 里没有任何 down 开关，后者要做就得在查询路径上
     * 注入 sleep（那是**改后端性能**，正撞边界 A/B）。UI 上这两个标「未接通」，
     * 结果里把它们列进 {@code Engine.InjectionsNotWired}。
     */
    static final List<String> INJECTION_KEYS = List.of("redisDown", "mqDown", "dbSlow");

    /** 唯一真接通的注入 key（其余两个只是占位，见 {@link #INJECTION_KEYS}）。 */
    static final String INJECTION_REDIS_DOWN = "redisDown";

    /** 本笔**未接通**的注入 —— 结果里如实标出来，免得看数字的人以为它们生效了。 */
    static final List<String> INJECTIONS_NOT_WIRED = List.of("mqDown", "dbSlow");

    // ==================== 一次跑的产物 ====================

    /**
     * 一轮演练的**汇总结果**（{@link DrillRecordService} 把它拆两半：前四项进 {@code drill_record}
     * 的列，{@code result} 序列化成 {@code Result} 长文本）。
     *
     * <p>⚠️ {@code shed} / {@code abandoned} 是甲新增的两个数（{@link Meter}），**故意不并进
     * {@code total}**：{@code total} 的语义是「真的发出去了多少条」，弃投的那些根本没发出去、
     * 被取消的那些已经发出去了。三者混在一起，{@code passed = total - blocked - failed}
     * 这个恒等式当场失真，而整套读数都挂在它上面。
     */
    public record DrillRun(String name, String scenarioJson, int total, int blocked, int failed,
                           int shed, int abandoned, long durationMs, Map<String, Object> result,
                           String batchId) {
    }

    /**
     * 判定档位（类注释三）。
     *
     * <p>⚠️ <b>第四档 {@code ABANDONED} 不由 {@link #classify} 产出</b> —— {@code classify} 的
     * 返回值永远只是前三档（那是「响应到手之后怎么判」）。{@code ABANDONED} 只有一个产出点：
     * {@link #drain} 取消在途 future 时，完成钩子里那条 {@code CancellationException} 分支。
     * 放进同一个枚举是为了让 {@link Meter#add} 成为**唯一**的记账出口（否则取消那笔账会
     * 变成第二处记账逻辑，两处必然漂）。
     */
    enum Outcome { PASSED, BLOCKED, FAILED, ABANDONED }

    /**
     * 一个被勾选的场景（+ 它那一轮的权重）。
     * ⚠️ 与 {@link Config}、{@link Outcome} 一样是 **package 可见**（不是 {@code private}）：
     * 它们是 {@link #parse} / {@link #threadPlan} / {@link #classify} 的入参出参，而那三个方法
     * 正是「错一格则整套读数都错」的地方 ⇒ 必须能被同包的单测直接钉住。收成 {@code private}
     * 就等于「这三个方法测不了」，而它们比请求拼装更需要测。
     */
    record Chosen(String key, int weight) {
    }

    /**
     * 一轮演练的入参配置（{@link #parse} 的产物）。
     *
     * @param targetQps    目标 QPS，{@code 0} = 不限速（见 {@link Pacer}）。⚠️ 它**不是**在途上限
     *                     —— 在途由 {@code concurrency}（且不超过 {@link #inFlightCap}）管，
     *                     两个旋钮各管一件事：一个管「每秒发几条」，一个管「同时挂着几条」。
     * @param protections  防护开关（丁，默认全开）—— **本笔只记录**
     * @param injections   注入开关（丁，默认全关）—— 只有 redisDown 真接通
     */
    record Config(String name, int concurrency, int durationSeconds, int targetQps,
                  List<Chosen> chosen, List<String> protections, List<String> injections) {
    }
    /** 一件 SKU 的字段（{@code Price} 原样留字符串：入参是 form 字段，不在这里转数）。 */
    private record Sku(String code, String merchantId, String goodName, String goodsId, String price) {
    }

    /** 本轮的所有靶子（{@code other} 可为 null：库里只有一件可演练 SKU 时）。 */
    private record Target(Sku main, Sku other, String customerId, String account, String sensitiveWord,
                          String victimId) {
    }

    /**
     * 一个场景的计数器。⚠️ 三档互斥且求和恒等于 {@code total}（{@link #classify} 保证）。
     *
     * <p>块 7 · commit 10（甲）另加两个**不参与那条恒等式**的计数器：
     * <ul>
     *   <li>{@code shed} —— 拿不到在途许可而**根本没发出去**的那一拍（背压）。算进 {@code total}
     *       会让「发出去了多少条」这个语义当场错掉。</li>
     *   <li>{@code abandoned} —— 宽限用尽被**取消**的在途请求。它**不**算 failed：那条请求可能
     *       已经在服务端跑完了，只是响应没等到 —— 记 failed 是撒谎（旧代码逐字写着
     *       「刻意不 interrupt 在途请求 —— 被 interrupt 会被记成 FAILED（假失败）」）。</li>
     * </ul>
     */
    private static final class Meter {
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger blocked = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger shed = new AtomicInteger();
        final AtomicInteger abandoned = new AtomicInteger();

        void add(Outcome o) {
            if (o == Outcome.ABANDONED) {
                abandoned.incrementAndGet();
                return;
            }
            total.incrementAndGet();
            if (o == Outcome.BLOCKED) {
                blocked.incrementAndGet();
            } else if (o == Outcome.FAILED) {
                failed.incrementAndGet();
            }
        }

        /** 背压弃投一次（这一拍**没发出去**，理由见类注释）。 */
        void shed() {
            shed.incrementAndGet();
        }

        int passed() {
            return total.get() - blocked.get() - failed.get();
        }
    }

    /**
     * 非 PASSED 请求的**有界记账环**（甲 · 细节 4）。
     *
     * <p>为什么是环、为什么只记非 PASSED：全量记录会把 {@code drill_record.Result}（mediumtext）
     * 与 {@code /run} 的报文一起打肥，而真实需求只有一个 ——「失败的那几条到底怎么了」。⇒ 只记
     * BLOCKED / FAILED / ABANDONED，容量 {@value AttackEngine#REQ_LOG_CAPACITY} 条，满了**覆盖最旧**
     * （与事件环同一条拒收策略：观测路径不阻塞生产路径）。读取时只带 {@value AttackEngine#REQ_LOG_SAMPLE}
     * 条样例 + 总数。
     *
     * <p>⚠️ 环满之后 {@link #snapshot()} 返回的是**槽序**不是时间序（被覆盖过的槽排在新数据前面）。
     * 这是刻意接受的不精确：它的用途是「挑几条看看长什么样」，不是因果分析 —— 要时间序得再存一列
     * 序号，不值当。
     *
     * <p>package 可见（不是 {@code private}）：本类没有网络也能钉住的两件事就是它（只记非 PASSED、
     * 满了不炸），而那正是最容易写错的两处。
     */
    static final class ReqLog {

        private final String[] ids = new String[REQ_LOG_CAPACITY];
        private final String[] outcomes = new String[REQ_LOG_CAPACITY];
        private final int[] statuses = new int[REQ_LOG_CAPACITY];
        private final long[] millis = new long[REQ_LOG_CAPACITY];
        private final AtomicInteger cursor = new AtomicInteger();
        private final AtomicInteger recorded = new AtomicInteger();

        /**
         * 记一条（PASSED 会被**静默丢掉** —— 那是本类的全部意义，见类注释）。
         *
         * @param outcome 三档名或 {@code "ABANDONED"}（用字符串而不是枚举：取消与三档判定
         *                本来就不是一回事，共用枚举会让人以为它是第四档判据）
         */
        void record(String reqId, String outcome, int status, long ms) {
            if (Outcome.PASSED.name().equals(outcome)) {
                return;
            }
            int slot = Math.floorMod(cursor.getAndIncrement(), REQ_LOG_CAPACITY);
            ids[slot] = reqId;
            outcomes[slot] = outcome;
            statuses[slot] = status;
            millis[slot] = ms;
            recorded.incrementAndGet();
        }

        /** 累计记下的条数（可以超过容量 —— 它是「发生过多少条非 PASSED」）。 */
        int count() {
            return recorded.get();
        }

        /** 槽序快照（满了不保证时间序，见类注释）。 */
        List<Map<String, Object>> snapshot() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < REQ_LOG_CAPACITY; i++) {
                if (ids[i] == null) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("ReqId", ids[i]);
                one.put("Outcome", outcomes[i]);
                one.put("Status", statuses[i]);
                one.put("Millis", millis[i]);
                out.add(one);
            }
            return out;
        }
    }

    /**
     * 限速器（令牌桶的「下一个空位」写法）—— 让档位 120/400 是**真的** 120/400。
     *
     * <p>为什么必须有它（而不是只靠信号量限在途）：只限在途的话，实际 QPS = 在途上限 ÷ 响应耗时
     * —— 那是一个**由后端速度决定的**数。后端一慢，档位自己就掉下来，于是「120」与「400」
     * 打出来是同一个数（都是「有多快打多快」）。那种档位是个**假旋钮**，而本块的口径是
     * 「丢了多少要说」⇒ 这里照同一条口径：**说了限速就真限速**。
     *
     * <p>写法：一个 {@code AtomicLong} 存「下一个空位的时刻」，{@code getAndUpdate} 原子地把它
     * 推后一个间隔、返回**推之前**的值 ⇒ 等待时长 = 返回值 − 现在。空闲期用 {@code Math.max(p, now)}
     * 把空位拉到当下（不攒「欠着的」令牌然后瞬间打出去）。
     *
     * <p>⚠️ {@code targetQps <= 0} = **不限速**（「拉满」档）：{@link #reserve()} 恒返 0，实际上限交给
     * 在途许可 —— 那时读出来的 QPS 是这台机器＋后端真实的上限，**不是我们设的一个数**
     * （验收判据里的「≥ 854」量的正是这个）。
     *
     * <p>package 可见：{@link #reserve()} 是纯计算，不跑网络就能钉住「限速档真的隔开、拉满档恒 0」。
     */
    static final class Pacer {

        private final long intervalNanos;
        private final AtomicLong nextSlot = new AtomicLong();

        Pacer(int targetQps) {
            this.intervalNanos = targetQps <= 0 ? 0L : 1_000_000_000L / targetQps;
        }

        boolean limited() {
            return intervalNanos > 0L;
        }

        /** 返回到下一个空位还要等多少纳秒（不限速恒 {@code 0}）。 */
        long reserve() {
            if (intervalNanos == 0L) {
                return 0L;
            }
            long now = System.nanoTime();
            long prev = nextSlot.getAndUpdate(p -> (p < now ? now : p) + intervalNanos);
            return Math.max(0L, prev - now);
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
     * 本轮用的票。⚠️ 用一个字段而不是到处穿参：{@link #send} 在**每条派发线程**里被调用，
     * 穿参要穿过 {@link #buildCall} 的八个分支。它在一轮内**只写一次**（{@link #resolveTarget}）、
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
     *   <li>每个请求的 {@code X-Drill-Batch} 头（见 {@link #send}）—— 服务端那一侧由
     *       {@code TraceIdFilter} 收进 MDC，于是**这轮打出来的事件**都带上它，
     *       落进 {@code observed_event.BatchId}。</li>
     * </ol>
     * ⚠️ 与 {@link #currentToken} 是同一族的写法（工作线程靠 volatile 读），也**登记同一个代价**：
     * 两轮演练**并发**发起时，后发起那轮会把批次覆盖掉，先那轮工作线程发出去的请求可能带上
     * 后那轮的批次 —— 本仓的演示口径是「一次点一个开始」（见 {@link #currentToken} 的注释）。
     */
    private volatile String currentBatch = "";

    /**
     * 在途请求许可数的**硬顶**（甲 · 细节 1）。有效许可数 = {@code min(concurrency, 本值)}。
     *
     * <p>⚠️ 走 {@code @Value} 而**不入参**（A 档清单第 3 条，用户已批）：避免入参爆炸。
     * 取值理由：512 已经远超过这套部署口径下任何真实的并发（单实例、Tomcat 200 工作线程、
     * 连接池 10），再往上只是把请求堆在引擎侧 —— 那不是并发，是把服务端饱和藏进队列。
     *
     * <p>⚠️ 字段初始化式与注解里的缺省值**刻意写成同一个数**：{@code new AttackEngine(...)}
     * 的测试拿不到 Spring 注入，初始化式让它在那种场合也是个确定值（照本仓既有写法）。
     */
    @Value("${mall.drill.engine.in-flight-cap:512}")
    private int inFlightCap = 512;

    /** 背压等待上限（毫秒）：拿不到许可最多等这么久，还拿不到就弃投（{@code Shed}）。0 = 立即弃投。 */
    @Value("${mall.drill.engine.shed-wait-ms:50}")
    private int shedWaitMs = 50;

    /**
     * 现在有没有一轮在跑（[暂停] 出口的一半，见 {@link #stop()}）。
     * ⚠️ 与 {@link #stopRequested} **不是一回事，别合并**：{@code running} 答「叫停的是不是空气」，
     * {@code stopRequested} 是「给正在跑的那一轮下的令」。
     */
    private final AtomicBoolean running = new AtomicBoolean();

    /** [暂停] 的令旗。{@link #run} 起跑前清掉，派发循环每一拍读一次。 */
    private volatile boolean stopRequested;

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
     * <p>⚠️ 本方法只做两件事：把 {@link #running} / {@link #stopRequested} 这对开关摆好，
     * 然后交给 {@link #runOnce}。**真活儿不在这一层** —— 拆开是因为「跑完必须把状态放下」
     * 只能靠 {@code finally}，而把整段几十秒的流程塞进一个 {@code try} 里会让那个方法没法读。
     *
     * @param body    前端报文（{@code name} / {@code concurrency} / {@code durationSeconds} /
     *                {@code targetQps} / {@code scenarios:[{key,weight}]} /
     *                {@code protections:[key]} / {@code injections:[key]}）；
     *                非法一律抛 {@link IllegalArgumentException}
     * @param baseUrl 打哪儿（由控制器从当前请求推出）
     */
    public DrillRun run(Map<String, Object> body, String baseUrl) {
        Config cfg = parse(body);
        // ⚠️ 令旗必须在**起跑前**清掉：上一次 [暂停] 留下的 true 会让这一轮第一拍就停工，
        //    而症状是「界面上跑完了、一行流量都没有」—— 最难查的那一类。放在置 running 之前。
        stopRequested = false;
        if (running.getAndSet(true)) {
            // 两轮并发发起：旧行为允许（本仓注明过代价），本笔不改成拒绝 —— 但**登记**一行，
            // 因为 [暂停] 只叫得停「最近那一轮」（令旗是单例的）。
            log.warn("已有演练在跑，又发起了一轮：/attack/stop 只叫得停最后一轮");
        }
        try {
            return runOnce(cfg, baseUrl);
        } finally {
            running.set(false);
            stopRequested = false;
        }
    }

    /**
     * 叫停**正在跑**的那一轮（[暂停]，{@code POST /admin/observe/attack/stop}）。
     *
     * <p>⚠️ 语义是「**停止派发 + 排空在途**」，不是「立即掐断」：在途请求走
     * {@link #drain} 的宽限排空，宽限用尽才取消。理由与旧代码同源 ——
     * 硬掐已经真发出去的请求会把它们记成假失败。
     *
     * @return {@code true} = 确实叫停了一轮正在跑的；{@code false} = 当时没有在跑的轮次
     *         （跑完了 / 从没跑过）。⚠️ 这个布尔是**给界面用的**：它决定按钮该不该提示
     *         「没有在跑的演练」—— 不编造成一个 success:true 了事。
     */
    public boolean stop() {
        if (!running.get()) {
            return false;
        }
        stopRequested = true;
        log.info("收到 /attack/stop：停止派发（在途请求走 {}s 宽限排空，宽限用尽记 Abandoned）",
                GRACE_SECONDS);
        return true;
    }

    /** 现在有没有一轮在跑（只读；给 {@code /stop} 的响应与日志用）。 */
    public boolean isRunning() {
        return running.get();
    }

    /** 真活儿（{@link #run} 的正文）。⚠️ 状态开关不在这里摆 —— 那一层要保证 {@code finally} 一定跑到。 */
    private DrillRun runOnce(Config cfg, String baseUrl) {
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
        ReqLog reqLog = new ReqLog();

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
            runPhase(phaseA, cfg.concurrency(), cfg.durationSeconds(), cfg.targetQps(), target,
                    baseUrl, meters, seqs, orderIds, runSeq, reqLog);
        }

        // ---- 阶段 B：redisDown 单独跑（注入 → 压 → 恢复）----
        // ⚠️ 触发条件由「勾了 redisDown 场景」**扩成 OR**（丁）：勾了场景 **或** 打开了 redisDown
        //    注入开关。这是本笔的一处**行为变更**，登记在这里 —— 旧口径下「只注入了、没勾场景」
        //    是跑不出阶段 B 的，而开关面板让「只注入」成了一个能表达的组合。
        boolean byInjection = cfg.injections().contains(INJECTION_REDIS_DOWN);
        Map<String, Object> redisDownInfo = new LinkedHashMap<>();
        redisDownInfo.put("Ran", false);
        redisDownInfo.put("ByInjection", byInjection);
        boolean stopped = stopRequested;
        if (stopped) {
            // ⚠️ [暂停] 之后**不再注入 L2 故障** —— 演练已经被人叫停，再制造一次真降级是越权。
            redisDownInfo.put("Note", "被 /attack/stop 叫停 ⇒ 阶段 B 未跑，也未注入 L2 故障");
        } else if (redisDown != null || byInjection) {
            if (meters[INDEX.get(REDIS_DOWN)] == null) {
                // 只打开了注入、没勾场景 —— 计数位得现补，否则这一阶段的读数没有落点
                meters[INDEX.get(REDIS_DOWN)] = new Meter();
                seqs.put(REDIS_DOWN, new AtomicInteger());
            }
            redisDownInfo.put("Ran", true);
            redisDownInfo.put("Seconds", REDIS_DOWN_SECONDS);
            runRedisDownPhase(target, baseUrl, meters[INDEX.get(REDIS_DOWN)],
                    seqs.get(REDIS_DOWN), Math.min(cfg.concurrency(), REDIS_DOWN_THREADS),
                    cfg.targetQps(), redisDownInfo, reqLog);
        } else {
            redisDownInfo.put("Note", "既没勾 redisDown 场景，也没打开 redisDown 注入 ⇒ 阶段 B 未跑");
        }

        // ---- 汇总 ----
        int stockAfter = measureStock(target.main().code(), stockBefore);
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        Map<String, Object> result = buildResult(target, meters, redisDownInfo, orderIds, stockBefore,
                stockAfter, baseline, runSeq, cfg, reqLog, stopped);
        return new DrillRun(cfg.name(), buildScenarioJson(cfg), sum(meters, 0), sum(meters, 1),
                sum(meters, 2), sum(meters, 3), sum(meters, 4), durationMs, result, batchId);
    }

    /**
     * 一个阶段：**固定 {@value #WORKER_THREADS} 条派发线程 + 异步在途 + 有界许可**（甲）。
     *
     * <h2>【四条口径（改之前 → 改之后）】</h2>
     * <ol>
     *   <li><b>线程数不再等于并发数</b>：派发线程 = {@code min(并发数, }{@value #WORKER_THREADS}{@code )}，
     *       在途数由信号量管（许可 = {@code min(并发数, }{@link #inFlightCap}{@code )}）。
     *       旧模型下 200 并发就是 200 条真线程（200 MB 栈）。</li>
     *   <li><b>背压是显式的</b>：拿不到许可最多等 {@link #shedWaitMs} 毫秒，还拿不到就**弃投**并
     *       计数（{@code Shed}）—— 不记 failed（那条请求根本没发出去），也不无限等
     *       （无限等会把服务端的饱和藏进队列，而「打不进去」正是要看见的东西）。</li>
     *   <li><b>超时三层</b>：① 每请求 10 秒（{@link #send}，保留不动）② 到 {@code deadline} 停止投递
     *       ③ 之后宽限 {@value #GRACE_SECONDS} 秒等在途自然收尾，用尽才 {@link #drain 取消}并记
     *       {@code Abandoned}。</li>
     *   <li><b>「刻意不 interrupt 在途请求」这条口径原样保留</b>：宽限内不主动取消 ——
     *       「响应没等到」与「请求失败」是两件事，前者记 Abandoned、后者记 Failed。</li>
     * </ol>
     * <p>⚠️ 阶段 A 与阶段 B **共用本方法**（阶段 B 传 {@code threads = 8} 与自己的场景清单）：
     * 两阶段的收口逻辑必须逐字同款，否则阶段 B 的读数与阶段 A 不同源、没法并排看。
     */
    private void runPhase(List<Chosen> chosen, int concurrency, int seconds, int targetQps,
                          Target target, String baseUrl, Meter[] meters,
                          Map<String, AtomicInteger> seqs, ConcurrentLinkedQueue<String> orderIds,
                          long runSeq, ReqLog reqLog) {
        List<String> plan = threadPlan(chosen, Math.min(concurrency, WORKER_THREADS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        int permits = Math.max(1, Math.min(concurrency, inFlightCap));
        Semaphore slots = new Semaphore(permits);
        Pacer pacer = new Pacer(targetQps);
        CountDownLatch done = new CountDownLatch(plan.size());
        Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
        log.info("演练阶段开跑：场景={} 并发={} 派发线程={} 在途上限={} 目标QPS={} 秒数={}",
                chosen.size(), concurrency, plan.size(), permits,
                pacer.limited() ? String.valueOf(targetQps) : "不限速", seconds);
        // 块 7 · commit 8c：把 (traceId, batchId) 绑到演练自己的线程上。⚠️ 这里绑的是
        // **批次的来源**那一侧：本线程是「发请求的人」，服务端那一侧靠请求头
        // （X-Drill-Batch，见 send）自己收 —— 两条路都要走，缺一条就有一半事件不带号。
        ObserveContext ctx = new ObserveContext(TraceIdFilter.current(), currentBatch);
        for (String key : plan) {
            // ⚠️ 守护线程：演练是「可被进程关闭打断」的长动作。非守护线程会让一次演练把 JVM 的
            //    关闭拖到整整 durationSeconds 之后（容器刷下去时最难受）。
            Thread t = new Thread(() -> ctx.run(() -> {
                try {
                    dispatch(key, target, baseUrl, deadline, pacer, slots, inFlight, meters, seqs,
                            orderIds, runSeq, reqLog);
                } catch (RuntimeException e) {
                    // 单条派发线程里的意外（URL 拼错 / 请求头非法）不该带走整轮：记一行、让这条
                    // 线程退出，其余照跑。⚠️ 不计入 failed —— 它没发出请求，不是「请求失败」。
                    log.warn("演练派发线程异常退出：scenario={} cause={}", key, e.toString());
                } finally {
                    done.countDown();
                }
            }), "drill-" + key);
            t.setDaemon(true);
            t.start();
        }
        // 收口 = deadline + 宽限。派发线程到点（或收到 [暂停]）自己退出；**在途的那些不在这儿等**,
        // 它们在下面 drain 里走宽限 —— 两者的预算都出自同一个 GRACE_SECONDS。
        long waitMillis = Math.max(1, (deadline + TimeUnit.SECONDS.toNanos(GRACE_SECONDS)
                - System.nanoTime()) / 1_000_000L);
        try {
            if (!done.await(waitMillis, TimeUnit.MILLISECONDS)) {
                log.warn("派发线程未按时收工（宽限 {}s 用尽），按**已发生**的计数汇总：scenario={}",
                        GRACE_SECONDS, chosen);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("演练收口被中断，按已发生的计数汇总");
        }
        drain(inFlight);
    }

    /**
     * 一条派发线程的主循环：限速 → 取在途许可 → 拼请求 → **异步发出** → 转下一拍。
     *
     * <p>⚠️ 甲的全部差别就在最后两步之间**不留等**：{@code send} 返回的是未完成的 future，
     * 判定与记账挂在它的完成钩子上 —— 于是「一条线程 = 一条在途」这条链在这里断开。
     */
    private void dispatch(String key, Target target, String baseUrl, long deadline, Pacer pacer,
                          Semaphore slots, Set<CompletableFuture<?>> inFlight, Meter[] meters,
                          Map<String, AtomicInteger> seqs, ConcurrentLinkedQueue<String> orderIds,
                          long runSeq, ReqLog reqLog) {
        Meter meter = meters[INDEX.get(key)];
        AtomicInteger seq = seqs.get(key);
        // ⚠️ 一次性场景（超时不付款）发完那一单就**退出**，不空转到截止时刻：
        //    它等的是 job 的时钟（60 秒），不是本引擎的时钟 —— 占着线程不干活只是浪费。
        boolean oneshot = UNPAID_TIMEOUT.equals(key);
        do {
            if (stopRequested) {
                return;                       // [暂停]：不再投递，在途的交给 drain
            }
            // ⚠️ 单次等待封顶 200ms：限速档下一条线程等的是「所有线程分完一个间隔」（可能几百
            //    毫秒），不封顶会让 [暂停] 与截止时刻晚好几拍才被看见。
            long waitMs = Math.min(200L, TimeUnit.NANOSECONDS.toMillis(pacer.reserve()));
            if (waitMs > 0) {
                sleepQuietly(waitMs);
            }
            if (System.nanoTime() >= deadline) {
                return;
            }
            if (!acquireOrShed(slots, meter)) {
                continue;                     // ⚠️ 弃投也转下一拍：下一拍也许就有名额了
            }
            int n = seq.getAndIncrement();
            String reqId = reqIdOf(key, n);
            long t0 = System.nanoTime();
            CompletableFuture<HttpResponse<String>> f;
            try {
                f = send(baseUrl, buildCall(key, target, n, orderIds, runSeq), reqId);
            } catch (RuntimeException e) {
                slots.release();              // 发都没发出去 ⇒ 许可必须还回去，否则并发上限自己缩水
                throw e;
            }
            // ⚠️ 顺序不能反：**先入集合、后挂钩子**。钩子里会 remove，反过来的话「已完成的 future」
            //    会在 remove 之后才被 add，那条请求就永远留在在途集合里（drain 会白等到宽限用尽）。
            inFlight.add(f);
            f.whenComplete((resp, err) -> {
                slots.release();
                inFlight.remove(f);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (err != null) {
                    // 取消记 Abandoned、其余（连不上 / 超时 / IO）记 Failed —— 理由见 Meter 注释。
                    boolean cancelled = isCancellation(err);
                    meter.add(cancelled ? Outcome.ABANDONED : Outcome.FAILED);
                    reqLog.record(reqId, cancelled ? "ABANDONED" : Outcome.FAILED.name(), 0, ms);
                    return;
                }
                Outcome o = classify(resp.statusCode(), resp.body());
                meter.add(o);
                reqLog.record(reqId, o.name(), resp.statusCode(), ms);
            });
        } while (!oneshot && System.nanoTime() < deadline);
    }

    /**
     * 排空：给在途请求 {@value #GRACE_SECONDS} 秒**自然收尾**；用尽则取消还没完成的那些。
     *
     * <p>⚠️ 取消的**记账不在本方法里** —— 它在每个 future 的完成钩子上（{@link #dispatch}）。
     * 记账只能有一处，否则同一条请求会被数两遍。本方法只负责「叫停」。
     *
     * <p>⚠️ 取消之后要**再等一小会儿**：{@code cancel} 是异步生效的，真正写计数器的完成钩子
     * 在它返回之后才跑。不等就接着读计数，{@code Abandoned} 会少几条 ——
     * 读数与事实不符是这个模块最坏的一种错。
     */
    private void drain(Set<CompletableFuture<?>> inFlight) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS);
        while (!inFlight.isEmpty() && System.nanoTime() < until) {
            sleepQuietly(5);
        }
        if (inFlight.isEmpty()) {
            return;
        }
        int cancelled = 0;
        for (CompletableFuture<?> f : inFlight) {
            if (f.cancel(true)) {
                cancelled++;
            }
        }
        log.warn("排空宽限 {}s 用尽，取消 {} 条在途请求（记 Abandoned，**不**记 Failed）："
                + "取消只是不再等它，底层连接由 HttpClient 自己收尾", GRACE_SECONDS, cancelled);
        long settleUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CANCEL_SETTLE_MS);
        while (!inFlight.isEmpty() && System.nanoTime() < settleUntil) {
            sleepQuietly(2);
        }
    }

    /**
     * 取一个在途许可，最多等 {@link #shedWaitMs} 毫秒；拿不到则记一次 {@code Shed}。
     *
     * <p>⚠️ 拿不到**不记 failed**：那条请求根本没发出去（failed 的语义是「发出去了但失败」）。
     * 也**不无限等** —— 无限等会把引擎变成一个无限队列，把服务端的饱和藏起来；弃投本身
     * 就是「系统已饱和」这条读数。
     */
    private boolean acquireOrShed(Semaphore slots, Meter meter) {
        boolean got;
        try {
            got = slots.tryAcquire(Math.max(0, shedWaitMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;                     // 被打断 ⇒ 当弃投（不记 failed，理由同上）
        }
        if (!got) {
            meter.shed();
        }
        return got;
    }

    /** 完成钩子里那个异常是不是「**我们取消的**」（{@code cancel} 的两种包装形状）。 */
    private static boolean isCancellation(Throwable err) {
        return err instanceof CancellationException
                || (err instanceof CompletionException
                        && err.getCause() instanceof CancellationException);
    }

    /** 睡一小会儿；被打断就复原中断位（本方法不抛）。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 请求号（甲 · 细节 4）：{@code 批次前 8 位-场景-序号}。
     * ⚠️ 与方案里那句「批次前 8 位 + '-' + seq」有一处**刻意的加强**：加上了场景 key。
     * 不加的话两个场景的第 7 发会拿到同一个请求号，而本号存在的唯一理由就是「在失败环里认出
     * 是哪一发」—— 认不出等于没记。前 8 位仍足以回指到轮次（同批的前缀相同）。
     */
    private String reqIdOf(String key, int seq) {
        String b = currentBatch;
        return (b.length() > 8 ? b.substring(0, 8) : b) + "-" + key + "-" + seq;
    }

    /** 阶段 B：注入 L2 故障 → 压缓存读 → **只在注入前健康时**恢复。 */
    private void runRedisDownPhase(Target target, String baseUrl, Meter meter, AtomicInteger seq,
                                   int threads, int targetQps, Map<String, Object> info,
                                   ReqLog reqLog) {
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
            runPhase(List.of(new Chosen(REDIS_DOWN, 1)), threads, REDIS_DOWN_SECONDS, targetQps,
                    target, baseUrl, metersOf(meter), Map.of(REDIS_DOWN, seq),
                    new ConcurrentLinkedQueue<>(), 0L, reqLog);
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

    // ==================== 拼一条（甲：拼与发拆开）====================

    /**
     * 一条**待发**的请求：路径 + 表单 + 带不带票。
     * ⚠️ 甲把「拼参数」与「发出去」拆成两半（旧的 {@code fireOnce} 是发完才回）—— 于是
     * 「一条线程 = 一条在途」那条链在 {@link #buildCall} 与 {@link #send} 之间断开。
     */
    private record Call(String path, Map<String, String> params, boolean withToken) {
    }

    /**
     * 按场景拼一条请求（**只拼不发**）。
     *
     * <p>⚠️ 每个分支只负责**拼路径与参数**；「什么算被拦下」一律由 {@link #classify} 判 ——
     * 三档口径只有一处，别在各分支里各写一遍（那必然漂）。
     */
    private Call buildCall(String key, Target t, int seq, ConcurrentLinkedQueue<String> orderIds,
                           long runSeq) {
        switch (key) {
            case SECKILL: {
                // 同一件 SKU、每次一个新 OrderId、每单 1 件 ⇒ 库存是唯一的稀缺资源（CAS 的地盘）
                String orderId = ATTACK_PREFIX + "SECKILL-" + runSeq + "-" + seq;
                offer(orderIds, orderId);
                return new Call("/order/orderAdd",
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
                return new Call("/order/orderAdd",
                        orderForm(t, sku, orderId, price, payIf, deliveryIf, receiveIf, 1), true);
            }
            case REPEAT_SUBMIT: {
                // 同一个 OrderId 反复打：唯一索引 + 锁 + 幂等查重 ⇒ 只有第一单落库，其余 success:false
                String orderId = ATTACK_PREFIX + "REPEAT-" + runSeq;
                offer(orderIds, orderId);
                return new Call("/order/orderAdd",
                        orderForm(t, t.main(), orderId, t.main().price(), 0, 0, 0, 1), true);
            }
            case UNPAID_TIMEOUT: {
                // PayIf=0 且此后不付款 ⇒ 交给 dev 的超时取消 job（60 秒）去取消并回补库存
                String orderId = ATTACK_PREFIX + "UNPAID-" + runSeq + "-" + seq;
                offer(orderIds, orderId);
                return new Call("/order/orderAdd",
                        orderForm(t, t.main(), orderId, t.main().price(), 0, 0, 0, 1), true);
            }
            case CACHE_COLD: {
                // 冷键：@Cacheable(sync=true) 的 priceByTriple —— 同一波（约 25 个并发）撞同一个
                // 从未缓存过的键 ⇒ 只有一条真查库、其余等它（单飞）；查不到 = 0 行 ⇒ 负缓存
                return new Call("/goods/productCodePrice",
                        priceForm(t.main(), coldCode(key, seq)), true);
            }
            case REDIS_DOWN: {
                // 每 4 条夹 1 条冷键（真去碰 L2，现已不可用 ⇒ 走降级路径），其余打固定键的首页列表
                return (seq % 4 == 0)
                        ? new Call("/goods/productCodePrice",
                                priceForm(t.main(), coldCode(key, seq)), true)
                        : new Call("/goods/goodsSelectHome", Map.of(), true);
            }
            case BANNED_USER: {
                // 两路交替：
                //  ① 带票，但把「代表自己」的那个入参填成**别人的业务号** ⇒ IdentityGuard 403
                //     （⚠️ 与 profile 无关：identify() 自己解析 Bearer，闸关也拦得住）
                //  ② 不带票 ⇒ 默认 profile 下 AuthInterceptor 答 401；闸关的 dev / a3 下由
                //     IdentityGuard 的 fail-open 放行（**这正是要被看见的现状**，不是 bug）
                if (seq % 2 == 0) {
                    return new Call("/cart/list", Map.of("CustomerId", t.victimId()), true);
                }
                return new Call("/cart/list", Map.of("CustomerId", t.customerId()), false);
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
                return new Call("/comment/add", p, true);
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
     * 发一条 form POST，**不等响应**（甲）。
     *
     * <p>⚠️ 返回值是**未完成的** future：判定与记账都在调用方挂的完成钩子里
     * （{@link #dispatch}）—— 那一层才知道请求是什么时候发的（要算耗时）。
     * 于是旧版那个 try/catch 里的两档失败（{@code InterruptedException} / {@code IOException}）
     * 现在**在钩子里**以 {@code err != null} 的形状出现：连不上、超时、被取消，一律走那一条路。
     *
     * @param reqId 出站请求号（{@link #REQ_HEADER}）—— 只做出站标记，服务端不消费（见该常量注释）
     */
    private CompletableFuture<HttpResponse<String>> send(String baseUrl, Call call, String reqId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + call.path()))
                // 超时第一层：**保留不动**（每请求 10 秒）。另外两层在 runPhase / drain。
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header(REQ_HEADER, reqId)
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(call.params()),
                        StandardCharsets.UTF_8));
        if (call.withToken()) {
            builder.header(AuthTokenStore.HEADER, AuthTokenStore.BEARER + currentToken);
        }
        // 块 7 · commit 8c：把本轮批次告诉服务端。⚠️ 它是**服务端那一半**的唯一入口 ——
        // TraceIdFilter 收到它才写进 MDC，之后这轮打出来的事件才带 batchId（见 currentBatch）。
        // ⚠️ 刻意**无条件**带（含匿名那一路）：批次是「这是演练造的」的标记，与带不带票无关。
        if (!currentBatch.isEmpty()) {
            builder.header(TraceIdFilter.BATCH_HEADER, currentBatch);
        }
        return http.sendAsync(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
     * ⚠️ 校验口径（缺省 / 上下限 / 勾选为空 / 未知开关）写在一处，别拿「前端已经拦过」当理由省掉
     * —— 端点是可以被 curl 直接打的。
     *
     * <p>报文键：{@code name} / {@code concurrency} / {@code durationSeconds} / {@code targetQps}
     * （甲）/ {@code scenarios:[{key,weight}]} / {@code protections:[key]} / {@code injections:[key]}
     * （丁，后两个是数组**结构** —— 传输层的 JSON 串在 {@code DrillRecordService} 里先解成结构）。
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
        // 目标 QPS（甲，A 档第 1 条）：0 = 不限速 = **加这个参数之前的行为**（见 Pacer）。
        int targetQps = clampInt(b.get("targetQps"), DEFAULT_TARGET_QPS, 0, MAX_TARGET_QPS,
                "目标 QPS（0 = 不限速）");

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
        // 丁的两个开关：缺省 = 防护全开 / 注入全关（用户 2026-10-14 定稿的面板缺省）。
        List<String> protections = parseKeys(b.get("protections"), PROTECTION_KEYS,
                PROTECTION_KEYS, "protections");
        List<String> injections = parseKeys(b.get("injections"), INJECTION_KEYS,
                List.of(), "injections");
        return new Config(name, concurrency, duration, targetQps, chosen, protections, injections);
    }

    /**
     * 开关列表校验：{@code null}（= 没给）⇒ {@code fallback}；给了就逐项认 key，
     * <b>不认识就抛</b>（形状错误 ⇒ 控制器 400，与本类其余校验同一条口径）。
     *
     * <p>⚠️ 为什么「没给」与「给了空数组」必须分开：前者是缺省（防护全开 / 注入全关），
     * 后者是一个**有意义的状态**（「这一轮一个防护都不开」）—— 把两者并成一个，
     * 演示「无防护对照轮」就表达不出来了。
     */
    static List<String> parseKeys(Object raw, List<String> allowed, List<String> fallback,
                                  String what) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(what + " 必须是字符串数组");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String key = str(item).trim();
            if (key.isEmpty()) {
                continue;
            }
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(what + " 里有未知的开关：" + key
                        + "（可选：" + String.join(" / ", allowed) + "）");
            }
            if (!out.contains(key)) {                 // 去重保序（同一个开关勾两次不是两份）
                out.add(key);
            }
        }
        return out;
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
                                            int stockAfter, Integer baseline, long runSeq,
                                            Config cfg, ReqLog reqLog, boolean stopped) {
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
            // 甲的两个数**单列**：它们不进 Requests/Passed/Blocked/Failed 的任一档
            // （理由见 Meter 的注释 —— 混进去那条恒等式就失真了）
            one.put("Shed", m.shed.get());
            one.put("Abandoned", m.abandoned.get());
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

        // ---- 引擎侧读数（甲）：一节专门回答「这台引擎这一轮是怎么跑的」----
        int shed = sum(meters, 3);
        int abandoned = sum(meters, 4);
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("Workers", WORKER_THREADS);
        engine.put("InFlightCap", Math.min(cfg.concurrency(), inFlightCap));
        engine.put("Concurrency", cfg.concurrency());
        engine.put("TargetQps", cfg.targetQps());
        engine.put("Limited", cfg.targetQps() > 0);
        engine.put("Shed", shed);
        engine.put("Abandoned", abandoned);
        engine.put("Stopped", stopped);
        // 丁：开关原样回显（这一轮到底是按哪套开关跑的）—— 「收了但不实现」也要明说
        engine.put("Protections", cfg.protections());
        engine.put("Injections", cfg.injections());
        engine.put("InjectionsNotWired", INJECTIONS_NOT_WIRED.stream()
                .filter(cfg.injections()::contains).toList());
        engine.put("RejectedTotal", reqLog.count());
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("Total", reqLog.count());
        rejected.put("Capacity", REQ_LOG_CAPACITY);
        rejected.put("SampleNote", "最多 " + REQ_LOG_SAMPLE + " 条、**槽序**（环满后被覆盖过的槽排在新数据前）");
        List<Map<String, Object>> all = reqLog.snapshot();
        rejected.put("Sample", all.subList(0, Math.min(REQ_LOG_SAMPLE, all.size())));
        engine.put("Rejected", rejected);
        engine.put("Notes", List.of(
                "Shed = 拿不到在途许可、**根本没发出去**的那些拍（背压）。它不计入 "
                        + "Requests/Passed/Blocked/Failed 任何一档 —— 记 failed 是撒谎",
                "Abandoned = 收尾宽限 " + GRACE_SECONDS + "s 用尽后被**取消**的在途请求。"
                        + "同样不并入任何一档：那条请求可能已经在服务端跑完了，只是响应没等到",
                "Protections 五个开关本笔**只记录不实现**（防护在 D 段）；"
                        + "Injections 里只有 redisDown 真接通"));
        out.put("Engine", engine);

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
        if (stopped) {
            notes.add("⚠️ 本轮被 /attack/stop **叫停**（停止派发 + 排空在途，不是立即掐断）："
                    + "阶段 B 未跑、也未注入 L2 故障；所有读数只覆盖叫停之前那一截");
        }
        out.put("Notes", notes);
        return out;
    }

    private static int passedOf(Meter[] meters, String key) {
        Meter m = meters[INDEX.get(key)];
        return m == null ? 0 : m.passed();
    }

    /** {@code which}：0=总数 / 1=被拦 / 2=真失败 / 3=背压弃投 / 4=被取消。没勾的场景不计。 */
    private static int sum(Meter[] meters, int which) {
        int sum = 0;
        for (Meter m : meters) {
            if (m == null) {
                continue;
            }
            sum += switch (which) {
                case 0 -> m.total.get();
                case 1 -> m.blocked.get();
                case 2 -> m.failed.get();
                case 3 -> m.shed.get();
                default -> m.abandoned.get();
            };
        }
        return sum;
    }

    /**
     * 配置 JSON（进 {@code drill_record.Scenario}，{@code varchar(500)}）。
     * **手写而不是用 Jackson**：要**稳定**的键序与**最短**的字符数（列只有 500 字符，勾满 8 个
     * 场景时长键名会把余量吃掉一半）。超长时截断并附省略号 —— 截断后的串已不是合法 JSON，
     * 读回时会落进 {@code ScenarioRaw} + {@code ScenarioParseError}（记录坏了要看得见，不许静默）。
     *
     * <p>键：{@code c} 并发 / {@code d} 秒数 / {@code q} 目标 QPS（**只在限速时出现** ——
     * 「拉满」是缺省，不写省字符）/ {@code s} 场景权重 / {@code p} 防护开关 / {@code i} 注入开关。
     * ⚠️ 后两组是**丁要的可复现性**：事后翻记录时要能答出「这一轮开没开熔断」。
     */
    static String buildScenarioJson(Config cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"c\":").append(cfg.concurrency())
                .append(",\"d\":").append(cfg.durationSeconds());
        if (cfg.targetQps() > 0) {
            sb.append(",\"q\":").append(cfg.targetQps());
        }
        sb.append(",\"s\":[");
        for (int i = 0; i < cfg.chosen().size(); i++) {
            Chosen c = cfg.chosen().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"k\":\"").append(c.key()).append("\",\"w\":").append(c.weight()).append('}');
        }
        sb.append("],\"p\":[");
        appendKeys(sb, cfg.protections());
        sb.append("],\"i\":[");
        appendKeys(sb, cfg.injections());
        sb.append("]}");
        return truncate(sb.toString(), SCENARIO_JSON_MAX);
    }

    /** 往 {@code [...} 里追加一串 key（不带引号转义 —— 它们全是本类自己声明的字面量）。 */
    private static void appendKeys(StringBuilder sb, List<String> keys) {
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(keys.get(i)).append('"');
        }
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
