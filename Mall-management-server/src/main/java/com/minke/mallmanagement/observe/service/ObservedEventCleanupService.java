package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.dao.DrillTargetDao;
import com.minke.mallmanagement.observe.dao.ObservedEventCleanupDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可观测域的**清理 / 复位编排**（块 7 · commit 8b；2026-10-18 加复位）—— 三个入口，一个 service：
 * ① 管理端点「清掉演练污染并恢复种子环境」；② 定时 job「清掉过期事件」；
 * ③ 演练前自检「库存打空时静默复位」。
 *
 * <h2>【⚠️ 为什么这几件事共一个 service，而不是各写各的】</h2>
 * 它们删 / 改的是**不同的表、不同的判据**（谓词 vs 保留期 vs 种子前缀），共用 SQL 很少 ——
 * 合并的理由不在代码复用，在**「演练环境」这一件事的一体两面**：
 * 「清掉演练写出的行」与「把演练改过的种子值恢复」都是**一次演示结束后回到干净态**，
 * 拆成两个 service 会让「清理」按钮只做一半（删了订单、库存仍然是 0，下一轮还是跑不起来）。
 * 另一条合并理由是**保留期这两个数是同一份配置**：`3 天 / 3 天` 是「攻击数据留多久、
 * 日常数据留多久」这一件事的两面，裂开会让它们各有各的默认值。2026-10-19 用户裁定
 * 「默认保留最近 3 天」⇒ 两档缺省都改成 3（旧值是 3 天攻击 / 30 天日常）。
 *
 * <h2>【⚠️ 本 service 不吞异常，与 {@code AuditService} 相反 —— 一个例外写明】</h2>
 * 审计是 fail-open（业务已成，不能让审计把它翻红）。本类是**破坏性动作的发起方**：
 * 删 / 复位失败必须让调用方**知道**（端点回 500 / job 记 error 日志），静默成功比失败坏得多
 * —— 那是 TD-20 的口径。DAO 层的异常原样上抛，本类不 catch。
 * <p>⚠️ **唯一例外是 {@link #autoResetIfNeeded()}**：它是 {@code /run} 之前的**尽力而为**自检，
 * 失败不能让「本来还能跑」的一轮演练因为复位出错而变成 500（复位的出发点就是「能跑起来」）。
 * 那条路径改为记 WARN 并放行，让引擎按原样报「挑不到靶子」；显式复位端点仍原样上抛。
 *
 * <h2>【⚠️ 不埋点】</h2>
 * 清理是**运维动作**（几分钟一次、行数不是事件），照 {@code DrillRecordDao.countRecords}
 * 的既有处置：不进任何事件环、不进任何指标。⚠️ 唯一**例外**是端点那一次会写一条审计
 * （{@code AuditActions.OBSERVE_ATTACK_CLEANUP}）—— 那是「谁删的」的问题，不是指标。
 */
@Service
public class ObservedEventCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ObservedEventCleanupService.class);

    private final ObservedEventCleanupDao cleanupDao;

    /**
     * 靶子取数（只读，2026-10-18 加）—— 只为一件事：{@code /run} 之前问一句
     * 「现在可演练的最大库存是多少」。判据必须与 {@code DrillTargetDao.pickSku} 同源，
     * 否则自检说「够」而引擎挑不到，等于把报错留给用户。
     */
    private final DrillTargetDao drillTargetDao;

    /**
     * 攻击批次行（{@code observed_event.BatchId IS NOT NULL}）的保留期。
     * ⚠️ 默认值写在**注解里**（不是只写在 properties）：本仓既有 job 一律如此
     * （见 {@code OrderTimeoutCancelJob} 的 {@code timeoutMs}），好处是漏配一个 profile 时
     * 行为仍是确定的、可读的。
     */
    @Value("${mall.observe.cleanup.attack-retention-days:3}")
    private int attackRetentionDays;

    /**
     * 日常事件行（{@code BatchId IS NULL}）的保留期。
     * ⚠️ 2026-10-19 用户裁定：**默认保留最近 3 天**（旧缺省 30 天）—— 演示环境的事件台账
     * 只服务于「最近几轮演练 / 最近几天排障」，30 天会让表在几次压测后就到百万行。
     */
    @Value("${mall.observe.cleanup.normal-retention-days:3}")
    private int normalRetentionDays;

    /**
     * 容量高水位（2026-10-19）。超过它在**每轮演练开始前 / 每次定时清理时**强制淘汰最老的行，
     * 直到回到 {@link #targetWatermark} 以下 —— 用户批文：「牺牲历史，保全当下」。
     * 30 万行是实测出来的安全线：当前单行 ≈ 780B（571.6MB / 729,885 行），30 万行约 230MB，
     * 远小于 512MB buffer pool；再高就会开始把业务页挤出内存。
     */
    @Value("${mall.observe.cleanup.high-watermark:300000}")
    private int highWatermark;

    /** 熔断后的安全线（低于高水位，留 5 万行滞回，避免每轮都在 30 万附近反复触发）。 */
    @Value("${mall.observe.cleanup.target-watermark:250000}")
    private int targetWatermark;

    /** 水位熔断 / 彻底清空的批大小（默认 5000，与 DAO 的保留期批大小一致）。 */
    @Value("${mall.observe.cleanup.batch-size:5000}")
    private int evictionBatchSize;

    /** 批间间歇（毫秒）。 */
    @Value("${mall.observe.cleanup.batch-pause-ms:100}")
    private long evictionPauseMs;

    /** UI / `/run` 响应里原样显示的熔断提示（用户批文逐字）。 */
    public static final String EVICTION_WARNING =
            "⚠️ 历史事件过多，系统已自动清理最旧数据以保障本次演练性能。";

    public ObservedEventCleanupService(ObservedEventCleanupDao cleanupDao, DrillTargetDao drillTargetDao) {
        this.cleanupDao = cleanupDao;
        this.drillTargetDao = drillTargetDao;
    }

    // ==================== ① 端点：按谓词清演练污染 ====================

    /**
     * 删掉两种谓词命中的业务行（+ 演练批次的事件台账行），回一张**可分字段显示**的结果表。
     *
     * <p>响应形状随全仓写端点惯例：{@code {"success":true, "deleted":{"order":3,…}, "total":N}}。
     * ⚠️ {@code deleted} 里**只含真删了行的表**（全 0 的表不出现）—— 一次「什么都没删」的清理
     * 与一次「删了 7 行」的清理，响应体形状上就该看得出区别。
     * <p>⚠️ {@code total} 是各表行数之和：同一张表可能被两种谓词各删一次（计数已相加），
     * 故它是「总删除行数」而不是「总表数」。
     *
     * <p><b>块 7 · commit 10 扩大了本端点的作用面</b>（用户 2026-10-14 裁决「清场按钮清全套」）：
     * 除了两种谓词命中的业务行，现在还删 {@code observed_event} 里的**演练台账行**。
     * ⚠️ <b>判据在 10c-2（2026-10-15）由一条扩到三条</b> ——
     * {@code BatchId IS NOT NULL} <b>OR</b>（{@code ObjectType='sku' AND ObjectId LIKE 'SEED-%'}）
     * <b>OR</b> {@code ObjectType='attack'}。理由：只按第一条实测会**留下 1742 条**非请求上下文
     * 写出的演练副产物（种子 SKU 的库存事件 + 清场动作自己的审计行），
     * 详见 {@code ObservedEventCleanupDao} 第 ③ 段的实测构成。两条独立的理由：
     * <ol>
     *   <li>那两种谓词**一个都碰不到**它（它既没有 {@code OrderId} 也没有 {@code Source} 列，
     *       因为它是事件不是业务行）⇒ 不清它，「清场」之后页面上的实时日志仍挂着上一轮的内容；</li>
     *   <li><b>指望不上保留期 job</b>：a3 / dev 下调度器总闸是关的（{@code @EnableScheduling}
     *       挂在一个被 {@code mall.downgrade.job.enabled} 关着的类上）⇒ 保留期那个 job 根本不会醒。
     *       在这套环境里，手动入口是**唯一**能把台账清掉的途径。</li>
     * </ol>
     * ⚠️ 这是一处**破坏性作用面的扩大**（用户批文里按「非 A 档但需报备」登记过）。
     * 三条判据全是**资源域**的（演练批次号 / 种子 SKU / 攻击域对象），**不含时间窗**；
     * 日常事件行**不动** —— 那是运维证据，归保留期管，不归清场按钮管。
     */
    public Map<String, Object> cleanupAttackData() {
        Map<String, Integer> deleted = cleanupDao.deleteAttackData();
        int total = deleted.values().stream().mapToInt(Integer::intValue).sum();
        // 2026-10-18：清场与复位合并成一次动作（用户批文第三节「清理并恢复环境」）。
        // ⚠️ 顺序是**先删后复位**：复位只写种子行，不依赖被删的行；反过来也一样，
        //    但先删能把台账里的旧库存事件清掉，复位产生的新值不会混进「上一轮」的残留里。
        Map<String, Integer> restored = cleanupDao.restoreSeedEnvironment();
        int restoredTotal = restored.values().stream().mapToInt(Integer::intValue).sum();
        log.info("攻击数据清理 / 演练环境复位完成（OBSERVE-CLEANUP）：共删 {} 行，明细={}；复位 {} 行，明细={}",
                total, deleted, restoredTotal, restored);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("deleted", deleted);
        payload.put("total", total);
        payload.put("restored", restored);
        payload.put("restoredTotal", restoredTotal);
        payload.put("summary", summarize(deleted, total) + "；" + summarizeRestored(restored, restoredTotal));
        return payload;
    }

    /** 本次清理的一句话摘要 —— 端点回给前端显示，同时用作审计行的 {@code TargetId}。 */
    public static String summarize(Map<String, Integer> deleted, int total) {
        if (total == 0) {
            return "共 0 行（库里没有待清的攻击数据）";
        }
        StringBuilder sb = new StringBuilder("共 ").append(total).append(" 行（");
        boolean first = true;
        for (Map.Entry<String, Integer> e : deleted.entrySet()) {
            if (!first) {
                sb.append(" / ");
            }
            sb.append(e.getKey()).append(' ').append(e.getValue());
            first = false;
        }
        return sb.append('）').toString();
    }

    /** 复位动作的一句话摘要（与 {@link #summarize} 同一形状，用在合并后的 {@code summary} 里）。 */
    public static String summarizeRestored(Map<String, Integer> restored, int total) {
        if (total == 0) {
            return "演练环境无需复位（没有命中的种子行）";
        }
        StringBuilder sb = new StringBuilder("演练环境已复位：共 ").append(total).append(" 行（");
        boolean first = true;
        for (Map.Entry<String, Integer> e : restored.entrySet()) {
            if (!first) {
                sb.append(" / ");
            }
            sb.append(e.getKey()).append(' ').append(e.getValue());
            first = false;
        }
        return sb.append('）').toString();
    }

    // ==================== ①-c 复位演练环境（2026-10-18 用户批文） ====================

    /**
     * 把演示种子被压测改变的状态复位（库存 / 父商品在架 / 商家未冻结 / 秒杀已售），
     * 回一张**可分字段显示**的结果表。
     *
     * <p>响应形状与 {@link #cleanupAttackData()} 同款：
     * {@code {"success":true, "restored":{"goods_sku":4,…}, "total":N, "summary":"…"}}。
     * ⚠️ {@code restored} 只含**命中过**的表 —— 与 {@code deleted} 同口径，别让前端把
     * 「没这张表」读成「这张表复位成 0 行」。
     *
     * <p>⚠️ 显式调用（{@code POST /admin/observe/attack/reset}）**不吞异常**：复位失败就 500 +
     * 审计记不下来，让操作者知道环境没有回到干净态。自动自检那条路见
     * {@link #autoResetIfNeeded()}。
     */
    public Map<String, Object> resetDrillEnvironment() {
        Map<String, Integer> restored = cleanupDao.restoreSeedEnvironment();
        int total = restored.values().stream().mapToInt(Integer::intValue).sum();
        log.info("演练环境复位（OBSERVE-RESET）：共命中 {} 行，明细={}", total, restored);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("restored", restored);
        payload.put("total", total);
        payload.put("summary", summarizeRestored(restored, total));
        return payload;
    }

    /**
     * 演练前自检：**可演练的最大库存低于 {@code MIN_RESET_STOCK}** 时静默复位种子环境。
     *
     * <p>用户批文第二节「可选进阶」：让用户无感 —— 压测把库存打空后，下一次点「开始」不该先
     * 吃一个「挑不到商品」的报错再点重置。
     *
     * @return {@code true} = 这一拍确实做了复位；{@code false} = 库存够（没动）或复位失败
     *         （已记 WARN，交给 {@code /run} 按原样报错）
     *
     * <p>⚠️ 判据取的是 {@link DrillTargetDao#maxPickableStock()}（**全部**可演练商品），
     * 不是种子 SKU 的库存：真实商品还有货时，复位种子环境是多余的写操作。
     * ⚠️ 复位失败在这里**不抛**：自检的职责是「锦上添花」，不能把一轮本来能跑的演练翻成 500
     * （异常口径的总说明见类注释）。
     */
    public boolean autoResetIfNeeded() {
        int maxStock = drillTargetDao.maxPickableStock();
        if (maxStock >= ObservedEventCleanupDao.MIN_RESET_STOCK) {
            return false;
        }
        try {
            Map<String, Object> payload = resetDrillEnvironment();
            log.warn("演练前自检（OBSERVE-RESET）：可演练最大库存 {} < {}，已自动复位种子环境 —— {}",
                    maxStock, ObservedEventCleanupDao.MIN_RESET_STOCK, payload.get("summary"));
            return true;
        } catch (RuntimeException e) {
            log.warn("演练前自检（OBSERVE-RESET）：可演练最大库存 {} < {}，自动复位失败；"
                    + "/run 将按原样报「挑不到靶子」", maxStock, ObservedEventCleanupDao.MIN_RESET_STOCK, e);
            return false;
        }
    }

    // ==================== ② job：按龄清事件台账 ====================

    /**
     * 删掉过期的 {@code observed_event} 行，返回删掉的行数（供 job 记日志）。
     *
     * <p>⚠️ 本笔跑起来**稳定返回 0**：{@code observed_event} 的写入方要到 8c 才接
     * （用户 2026-10-14 裁决）⇒ 表是空的。这不是缺陷，见 {@code ObservedEventCleanupJob} 类注释。
     */
    public int pruneExpiredEvents() {
        int n = cleanupDao.pruneObservedEvents(attackRetentionDays, normalRetentionDays);
        if (n > 0) {
            log.info("事件台账保留期清理（OBSERVE-CLEANUP）：删 {} 行（攻击 {} 天 / 日常 {} 天，"
                            + "每批 {} 行 + 100ms 间歇）",
                    n, attackRetentionDays, normalRetentionDays,
                    ObservedEventCleanupDao.DELETE_BATCH_SIZE);
        } else {
            log.debug("事件台账保留期清理（OBSERVE-CLEANUP）：本轮无可删行（攻击 {} 天 / 日常 {} 天）",
                    attackRetentionDays, normalRetentionDays);
        }
        return n;
    }

    // ==================== ③ 容量水位熔断（2026-10-19 用户批文）====================

    /**
     * 容量水位熔断的结果（给 `/run` 响应 / job 日志用）。
     *
     * @param before    触发前的行数
     * @param after     收口后的行数
     * @param deleted   本次淘汰的行数（0 = 没触发）
     * @param triggered 是否真的发生了淘汰
     */
    public record EvictionResult(long before, long after, long deleted, boolean triggered) {
    }

    /**
     * **超过高水位就按时间淘汰最老的行**，直到回到安全线以下。返回这次淘汰的读数。
     *
     * <p>调用点有两处（用户批文）：① {@code POST /attack/run} 开始演练前；② 定时清理 job。
     * 判据是「事件台账行数 > {@code high-watermark}」—— 与保留期（按龄）是**两个独立的闸**：
     * 保留期管「太旧」，水位管「太多」。只靠保留期时，一场 500 并发演练能在几分钟内写进
     * 十万行，3 天保留期根本来不及兜住。
     *
     * <p>⚠️ 淘汰顺序是 {@code ORDER BY Id}（≈ 写入时间），**不是**随机 / 按大小：
     * 「牺牲历史，保全当下」要求先走最老的那些；且主键顺序删除不需要额外排序。
     * <p>⚠️ 每批 {@code batch-size}（默认 5000）行、批间 {@code batch-pause-ms}（默认 100ms）：
     * 不让一次熔断变成另一个大事务。每批之后**重读真实行数**再判一次 —— 不用「before 减删除量」
     * 估算：熔断发生在演练开始前，但上一轮的消费者可能仍在落库，估算会把并发写入漏掉，
     * 从而在「以为到安全线」时其实还在线上。{@code COUNT(*)} 只在**超水位**这条罕见路径上跑，
     * 几十万行的主键计数代价可接受。
     *
     * @return 淘汰读数；未超水位 ⇒ {@code deleted=0, triggered=false}
     */
    public EvictionResult enforceHighWatermark() {
        long before = cleanupDao.countObservedEvents();
        if (before <= highWatermark) {
            return new EvictionResult(before, before, 0, false);
        }

        long deleted = 0;
        while (true) {
            long remaining = cleanupDao.countObservedEvents();
            if (remaining <= targetWatermark) {
                break;
            }
            int n = cleanupDao.deleteOldestBatch(evictionBatchSize);
            if (n <= 0) {
                break;
            }
            deleted += n;
            if (!pauseBetweenEvictions()) {
                break;
            }
        }
        long after = cleanupDao.countObservedEvents();
        boolean triggered = deleted > 0;
        if (triggered) {
            log.warn("事件台账水位熔断（OBSERVE-CLEANUP）：{} 行 > 高水位 {}，"
                            + "已按时间淘汰最旧 {} 行 ⇒ 现 {} 行（安全线 {}）。{}",
                    before, highWatermark, deleted, after, targetWatermark, EVICTION_WARNING);
        }
        return new EvictionResult(before, after, deleted, triggered);
    }

    /**
     * 当前存储水位（UI 展示用）：行数 / 高水位 / 安全线 / 保留天数。
     * ⚠️ 只读，不删任何行；调用点应把它当作「快照」，不要拿它做计数对账。
     */
    public Map<String, Object> storageStats() {
        long count = cleanupDao.countObservedEvents();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("count", count);
        payload.put("highWatermark", highWatermark);
        payload.put("targetWatermark", targetWatermark);
        payload.put("retentionDays", attackRetentionDays);
        payload.put("batchSize", evictionBatchSize);
        payload.put("aboveHighWatermark", count > highWatermark);
        return payload;
    }

    // ==================== ④ 彻底清空事件台账（2026-10-19 用户批文）====================

    /**
     * 清空**整个** {@code observed_event}（不只演练批次），返回清前 / 清后读数。
     *
     * <p>它与「清理并恢复环境」的区别：后者按**资源域判据**（批次号 / 种子 SKU / 攻击对象）
     * 删演练污染，日常事件行不动；本方法按「**整张表**」删，是用户点名的
     * 【彻底清空可观测历史数据】。⚠️ 破坏性更强，端点层必须两段式确认 + 写审计。
     *
     * <p>同样分批（{@code ORDER BY Id LIMIT batch-size} + 批间间歇），绝不用一条无界 DELETE。
     */
    public Map<String, Object> purgeAllEvents() {
        long before = cleanupDao.countObservedEvents();
        long deleted = 0;
        while (true) {
            int n = cleanupDao.deleteOldestBatch(evictionBatchSize);
            if (n <= 0) {
                break;
            }
            deleted += n;
            if (n < evictionBatchSize || !pauseBetweenEvictions()) {
                break;
            }
        }
        long after = cleanupDao.countObservedEvents();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("before", before);
        payload.put("deleted", deleted);
        payload.put("total", deleted);
        payload.put("after", after);
        payload.put("summary", deleted == 0
                ? "事件台账本来就是空的（0 行）"
                : "已彻底清空事件台账：共删 " + deleted + " 行（清前 " + before
                        + " 行 / 清后 " + after + " 行）");
        log.info("事件台账彻底清空（OBSERVE-CLEANUP）：清前 {} 行，删 {} 行，清后 {} 行",
                before, deleted, after);
        return payload;
    }

    /** 批间间歇；中断 ⇒ 返回 {@code false} 并还原中断标志（同 DAO 的处置）。 */
    private boolean pauseBetweenEvictions() {
        try {
            Thread.sleep(evictionPauseMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 事件台账当前行数（只读；给手验 / 探针做「清前清后」的读数）。 */
    public long countEvents() {
        return cleanupDao.countObservedEvents();
    }
}
