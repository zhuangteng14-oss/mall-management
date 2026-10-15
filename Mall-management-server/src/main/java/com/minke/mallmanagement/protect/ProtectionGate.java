package com.minke.mallmanagement.protect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 防护开关的**运行时状态**（D 段 · commit 2）—— 五个 key 各自一个进程内的布尔位，
 * 由演练（{@code /admin/observe/attack/run} 的 {@code protections} 入参）在**开跑那一刻**整体写入，
 * 演练结束时复位成默认值。
 *
 * <h2>【本段开关的真实口径：4 键接闸 + 1 键记录位】</h2>
 * <pre>
 *   key             本段状态      闸在哪                        读它的地方
 *   ──────────────  ───────────  ────────────────────────────  ────────────────────────────
 *   rateLimit       接闸          入站限流闸（200 QPS + 白名单） InboundRateLimitInterceptor
 *   circuitBreak    接闸          关键下游熔断（2 个实例）        @CircuitBreaker(mallMysqlRead/mallMysqlWrite)
 *   poolIsolation   接闸          观测池分池（有界 ↔ 无界）       ObservePoolConfig
 *   retry           接闸          死锁重试（relist，最多 3 次尝试）  MerchantDowngradeService 的 GatedRetryPolicy
 *   degrade         只记录        无（系统固有 fail-open）       无 —— 只回给前端做展示
 * </pre>
 * ⚠️ 这张表是**本条口径的唯一出处**（报告里那张「裁定落实表」与它逐行对应）。
 * {@link #WIRED_KEYS} / {@link #RECORD_ONLY_KEY} 把它落成了代码，
 * 于是「哪几个键真的有闸」这件事**可以断言**（{@code ProtectionGateTest}），不靠文档自觉。
 *
 * <h2>【retry 从「登记 TD」改成「接闸」的经过（2026-10-15，D 段 · 阶段 2b）】</h2>
 * 本类此前写的是「3 键接闸 + 1 键登记 TD（{@code TD_ONLY_KEY = "retry"}）+ 1 键记录位」。
 * 用户 2026-10-15 在拿到死锁实测（48/48 轮复现、149 次 {@code ER_LOCK_DEADLOCK}）之后裁定
 * **接闸 3 → 4**：{@code retry} 现在真有闸 —— {@code MerchantDowngradeService.relist} 的重试
 * 读本开关（关掉 ⇒ 一次失败即抛、不再试）。⇒ {@code TD_ONLY_KEY} 常量随之删除、
 * {@link #WIRED_KEYS} 由 3 个键变 4 个键。
 * ⚠️ **不变式本身没变、反而更强了**：WIRED_KEYS ∪ {RECORD_ONLY_KEY} = KEYS，且两者不交
 * （4 + 1 = 5）。这正是当初把口径落成常量的用意 —— 挪动一个键必须同时改一条会红的测试。
 *
 * <h2>【为什么 degrade 还留在这五个位里，而不是从表里删掉】</h2>
 * 三件事把这一枚钉在这里：
 * <ol>
 *   <li><b>它是演练入参的白名单</b>：{@code AttackEngine.PROTECTION_KEYS} 认这五个 key，
 *       拨了不认识的 key 当场 400。删掉它 ⇒ 前端现有的那一枚开关会变成 400。</li>
 *   <li><b>它要能「如实回答自己没生效」</b>：{@link #wired(String)} 为 {@code false} 的键，
 *       面板必须画成**不可开关**（标灰 + tooltip），而不是画成一个能拨却什么也不发生的死控件。</li>
 *   <li><b>它不是「没实现」，是「不由这个开关管」</b>：{@code degrade} 对应的行为**本来就存在**
 *       （缓存 L2 回落 / 锁 fail-open / MQ 缺席 / 审计 fail-open），只是不由这个开关管 ——
 *       所以它的 UI 文案必须是「记录位」，不是「待实现」。</li>
 * </ol>
 *
 * <h2>【默认值 = 全开，以及「为什么演练结束必须复位」】</h2>
 * 五个位的初值是 {@code true}（与演练入参缺省「全开」、与前端面板缺省一致）。⚠️ <b>但演练跑完必须
 * 复位成全开</b>：否则「上一轮把限流关掉」这件事会**越过那一轮**留在进程里 —— 下一次日常请求
 * （哪怕没有演练在跑）走的就是一个没有限流闸的服务，而页面上看不到任何迹象。
 * 复位点写在 {@code DrillRecordService} 的 {@code finally} 里（逐字理由见那一行）。
 *
 * <h2>【它不进任何临界区】</h2>
 * 读是 {@code ConcurrentHashMap} 查表 + 一次 {@code AtomicBoolean.get()}；写只在演练开跑/收尾各一次。
 * 本类是**只读旁路**（同 {@code observe/metrics/*} 那一族的定位）：读它的闸永远不会因为读不到而
 * 阻塞业务线程 —— 认不出 key 才抛，而那是代码 bug、不是运行时状态。
 */
@Component
public class ProtectionGate {

    private static final Logger log = LoggerFactory.getLogger(ProtectionGate.class);

    /**
     * 五个 key 的**声明序**。⚠️ 与后端 {@code AttackEngine.PROTECTION_KEYS}、
     * 前端 {@code config/labels/observe-labels.js} 的 {@code PROTECTIONS} **三方逐字同序**
     * （顺序不是审美：面板上第 N 枚开关与第 N 个 key 必须指同一件事，错了两边都不报错）。
     * 三方一致里**后端的这两份**由 {@code ProtectionKeysAlignmentTest} 钉住（顺序 + 取值）；
     * 前端那份跨语言、Java 侧读不到，走 {@code scripts/probes/} 的探针（同那个类的类注释所述）。
     */
    public static final List<String> KEYS = List.of(
            "rateLimit", "circuitBreak", "degrade", "retry", "poolIsolation");

    /** 本段**真的接了闸**的四个 key（口径表见类注释）。 */
    public static final List<String> WIRED_KEYS = List.of(
            "rateLimit", "circuitBreak", "retry", "poolIsolation");

    /** 本段**只记录、不接闸**的 key（系统固有 fail-open 设计，不由开关管）。 */
    public static final String RECORD_ONLY_KEY = "degrade";

    /** 单调的「第几次写入」—— 让抓取快照的人能判断「这一份和我上次看到的是不是同一份」。 */
    private final Map<String, AtomicBoolean> flags = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public ProtectionGate() {
        for (String key : KEYS) {
            flags.put(key, new AtomicBoolean(true));
        }
    }

    /**
     * 整体写入（演练开跑那一刻调一次）：{@code onKeys} 里的 key 置 {@code true}，
     * 其余**一律置 {@code false}**（「关掉没勾的」是这条的语义，不是「只打开勾了的」——
     * 后者会让上一轮的残留永远消不掉）。
     *
     * @param onKeys 勾选的 key；{@code null} 按「空清单」处理（= 全关，与演练侧的口径一致）
     * @throws IllegalArgumentException 出现不认识的 key（它只可能来自代码 bug；演练侧在更早的一层
     *         就已经把表外的 key 挡成 400 了，见 {@code AttackEngine} 的入参校验）
     */
    public void apply(List<String> onKeys) {
        List<String> on = onKeys == null ? List.of() : onKeys;
        // ⚠️ 两轮写法的顺序是**有意的**：先把入参整个校验完，再开始改状态 ⇒ 这次写入是
        //    「**全有或全无**」的。反过来写（边校验边 set）会让一个拼错的 key 留下**半套开关**
        //    —— 五个位照样都各有一个值，而在页面上、日志里、快照里都看不出「这次写入只落了一半」。
        // ⚠️ 校验的是**入参**、不只是 KEYS：`require(KEYS)` 那一路永远过（它们本来就是本类的表），
        //    真正会拼错的是调用方递进来的清单。一个被静默忽略的拼错 key，在页面上的表现是
        //    「勾了、但没有闸」—— 与 `isOn` 那条注释里拒绝兜底的是同一种病。
        for (String key : on) {
            require(key);
        }
        for (String key : KEYS) {
            flags.get(key).set(on.contains(key));
        }
        generation.incrementAndGet();
        log.info("[PROTECT] 防护开关已写入（PROTECT）：{}", snapshot());
    }

    /** 复位成**默认值（全开）**。⚠️ 演练收尾必须调（理由见类注释末节）。 */
    public void resetToDefault() {
        for (String key : KEYS) {
            flags.get(key).set(true);
        }
        generation.incrementAndGet();
    }

    /**
     * 这个 key 对应的闸**现在开没开**。闸的 read 点全是热路径上的一个 {@code if}。
     *
     * @throws IllegalArgumentException 不认识的 key —— 刻意抛而不是返回一个兜底布尔：
     *         兜底 false 会让「键写错了」表现为「防护静默失效」（演练里最坏的一种），
     *         兜底 true 会让「键写错了」表现为「关不掉」。两种都比当场炸掉难查。
     */
    public boolean isOn(String key) {
        return require(key).get();
    }

    /**
     * 这个 key **在本段有没有真闸**（口径表见类注释）。
     *
     * <p>给前端与报告用：{@code false} 的那个键（{@code degrade}）面板上必须
     * 画成不可开关 —— <b>能点但没效果的开关一律不允许</b>（用户 2026-10-15 裁定逐字）。
     */
    public static boolean wired(String key) {
        return WIRED_KEYS.contains(key);
    }

    /** 快照（按 {@link #KEYS} 声明序），给端点 / 启动日志 / 测试用。 */
    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> snapshot = new LinkedHashMap<>();
        for (String key : KEYS) {
            snapshot.put(key, flags.get(key).get());
        }
        return snapshot;
    }

    /** 写入代数：每次 {@link #apply} / {@link #resetToDefault} 自增一次（初值 0 = 从没被演练写过）。 */
    public long generation() {
        return generation.get();
    }

    private AtomicBoolean require(String key) {
        AtomicBoolean flag = key == null ? null : flags.get(key);
        if (flag == null) {
            throw new IllegalArgumentException("未知的防护开关 key：" + key + "（本仓认这五个：" + KEYS + "）");
        }
        return flag;
    }
}
