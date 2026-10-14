package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.shared.web.TraceIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 身份观测 —— 身份校验面的埋点出口（Micrometer 指标 + 有界事件流）。
 *
 * <p>《块 7 参考》§4.5 五套新事件环之一（IdentityMetrics —— 身份拒绝 / 放行），
 * 与 {@code MallCacheMetrics} / {@code MallLockMetrics} / {@code MallOrderStateMetrics} 同款：
 * <b>512 槽事件环</b> + 计数，两条通道在<b>同一调用点</b>一起发。抽环与否的论证同
 * {@link StockMetrics} 类注释；「只有一个 kind ⇒ 事件里不设 kind 字段」的理由同
 * {@link AuditMetrics} 类注释（都不重抄）。
 *
 * <h2>【本环记的是<b>结论</b>：放行 / 拒绝】</h2>
 * 两路互斥且完备，且<b>两路都记</b>（这是本环与 {@code SensitiveMetrics} 的口径差别）：
 * 「放行数」是本环要回答的一半 —— 只有把分母一起记下来，才有可能看出「今天的拒绝率是不是异常」。
 * ⚠️ 本环<b>不</b>记「为什么被拒」之外的任何判定过程（那是调用侧的事），
 * 也<b>不</b>替代块 1 的鉴权链做决策：本类只记「已发生的事实」。
 *
 * <h2>【命名规范】</h2>
 * 前缀 {@code mall.}、<b>基数铁律</b>（tag 只用有界取值、业务键绝不进 tag）、
 * 结果类维度统一叫 {@code result}、可派生的值不重复埋点。
 * ⚠️ {@code subject}（业务号 / 登录名）与 {@code reason}（拒绝原因文本）<b>都不进 tag</b>：
 * 前者基数无界；后者是自由文本。两者都在事件的同名字段里。
 * 「拒绝原因」<b>不做</b>成 tag 是有意的取舍：不同拒绝点的原因词表由各自的守卫拼，
 * 本环不假定它封闭（规范第 1 条：tag 取值必须可枚举）。
 *
 * <h2>【指标清单】</h2>
 * <pre>
 * mall.identity.checks{result=passed|rejected}
 *        一次身份校验的结果，两路互斥且完备：
 *        passed   = 放行；
 *        rejected = 拒绝（令牌无效 / 被封禁 / 身份与目标不符…）。
 * </pre>
 * 不埋「拒绝率」：它是 {@code rejected / (passed + rejected)} 的派生量（规范第 6 条）；
 * README/面板要展示时按这条式子现算，别落成第三个计数器。
 *
 * <h2>【事件语法】</h2>
 * <pre>
 *   result=passed|rejected   subject=被校验的业务号   reason=拒绝原因（passed 时为 null）
 * </pre>
 *
 * <h2>【本环的写入点（已被 commit 2b 接上）：{@code IdentityGuard} 的 8 个出口】</h2>
 * commit 2 当时只建出口；**commit 2b 改走真观测点**：{@code shared/web/IdentityGuard} 的
 * 两条判据（业务号版 / 登录名版）在放行与拒绝两路各记一条 —— 拒绝率的分母由此才成立。
 * ⚠️ 该守卫是静态工具、拿不到构造注入 ⇒ 走 {@code bind(...)} 静态口，由
 * {@code ObserveMetricsConfig} 在启动期装配；未装配（切片 / 单测）一律不记。
 * ⇒ 观测失败不影响判据本身（fail-soft，同该守卫其余 fail-open 的纪律）。
 *
 * <h2>【热路径代价】</h2>
 * 同既有三环：registry 查表 + LongAdder 自增 + 一次数组赋值 + 一个小对象；无锁、无 I/O、
 * <b>无日志</b>（agent.md 第 7 条）。身份校验本身在最热的那条路上（每个受保护请求一次）
 * ⇒ 上面这条代价约束在这里同样是硬要求。
 */
@Component
public class IdentityMetrics {

    // ===== 指标名：身份校验面唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String CHECKS = "mall.identity.checks";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。与既有三环同值。 */
    private static final int EVENT_CAPACITY = 512;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    private final MeterRegistry registry;

    /** 事件环形缓冲与单调游标；写者只做一次数组赋值，读者靠 {@code seq} 自校验。 */
    private final IdentityEvent[] events = new IdentityEvent[EVENT_CAPACITY];
    private final AtomicLong eventCursor = new AtomicLong();

    public IdentityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次<b>放行</b>的身份校验。
     *
     * @param subject 被校验的业务号。⚠️ 只进事件流，绝不进 tag（基数无界）
     * @param path    被校验的那个**请求路径**（块 7 · commit 8 新增）—— 一次鉴权闸判的是「这个端点」，
     *                只有业务号看不出判的是哪个端点；实时日志页要用它标出「这条身份事件挡在哪」。
     *                拿不到请求时传 null（切片 / 非 web 调用）
     */
    public void passed(String subject, String path) {
        registry.counter(CHECKS, "result", Result.PASSED.tag).increment();
        event(Result.PASSED.tag, subject, null, path);
    }

    /**
     * 一次<b>拒绝</b>的身份校验。
     *
     * @param reason 拒绝原因（简短文本，由发起校验的那一处给）。只进事件流 —— 自由文本、基数无界
     * @param path   ／同 {@link #passed}
     */
    public void rejected(String subject, String reason, String path) {
        registry.counter(CHECKS, "result", Result.REJECTED.tag).increment();
        event(Result.REJECTED.tag, subject, reason, path);
    }

    // ================= 事件流 =================

    /**
     * 最近的事件快照，按 {@code seq} 升序。并发口径同 {@link StockMetrics#recentEvents()}：
     * 每个槽位用 {@code e.seq() == seq} 自校验，对不上就跳过。
     *
     * <p>本笔<b>不</b>把它对外暴露（事件流端点属下一笔）；目前只被单测调用。
     */
    public List<IdentityEvent> recentEvents() {
        long next = eventCursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        List<IdentityEvent> snapshot = new ArrayList<>((int) (next - oldest));
        for (long seq = oldest; seq < next; seq++) {
            IdentityEvent candidate = events[(int) (seq & EVENT_MASK)];
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    /** 追加一条事件。{@code subject} / {@code reason} 只在事件里以字符串形式出现 —— tag 里绝不能有它们。 */
    private void event(String result, String subject, String reason, String path) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new IdentityEvent(
                seq, System.currentTimeMillis(), result,
                subject == null ? null : String.valueOf(subject), reason,
                path == null ? null : String.valueOf(path),
                TraceIdFilter.current(), TraceIdFilter.currentBatch());
    }

    /**
     * 一条身份校验事件。
     *
     * @param seq        单调序号（缓冲被覆盖后靠它自校验与排序）
     * @param epochMilli 墙钟时间戳 —— 事件是给人看的过程记录，故用墙钟而非 nanoTime
     * @param result     passed | rejected
     * @param subject    被校验的业务号
     * @param reason     拒绝原因；{@code passed} 时为 null
     * @param path       被校验的请求路径（块 7 · commit 8 新增）
     * @param traceId    产生本条事件的那次请求的号（块 7 · commit 8 新增）；不在请求线程上时为 null
     * @param batchId    产生本条事件的那次请求**所属的演练批次**（块 7 · commit 8c，来自
     *                   {@code X-Drill-Batch} 头）；日常流量为 {@code null}。同 {@code traceId} 现读
     */
    public record IdentityEvent(long seq, long epochMilli, String result,
                                String subject, String reason, String path, String traceId, String batchId) {
    }

    /** {@code mall.identity.checks} 的 result 取值。tag 字段避免每次调用拼字符串。 */
    public enum Result {
        /** 放行。 */
        PASSED("passed"),
        /** 拒绝（令牌无效 / 被封禁 / 身份与目标不符…）。 */
        REJECTED("rejected");

        final String tag;

        Result(String tag) {
            this.tag = tag;
        }

        /** 事件流 / 指标 tag 的取值（有界）。公开的理由同 {@code MallLockMetrics.Outcome#tag()}。 */
        public String tag() {
            return tag;
        }
    }
}
