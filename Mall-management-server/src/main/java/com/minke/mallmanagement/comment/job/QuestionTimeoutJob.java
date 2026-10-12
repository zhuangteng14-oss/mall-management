package com.minke.mallmanagement.comment.job;

import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「提问超过 30 分钟还没被采纳」的**报管理端**任务（C3 块 6 · commit 2「问答」）。
 * 依据：{@code docs/agent/context-packs/块6参考.md} §4.2「30 分钟未解决 → Job 报管理端」。
 *
 * <h2>【它是「待办投递」，不是「超时判决」】</h2>
 * 提问**不会**因为超时被关掉、改状态或被删 —— 本任务只往管理端共享收件箱投一条通知，
 * 由人进问答 tab 处置（方案 §五「[问答管理]（超时未解决 / 手动删）」）。
 * ⇒ 库里 {@code comment} 一行都不动，**唯一的写**是 {@code notification} 的一行。
 * 这也意味着本任务**没有任何不可逆动作**：漏一轮、重一轮都只是「晚知道一会儿」。
 *
 * <h2>【⚠️ 幂等：靠通知表自己判，且判据在 SQL 里（见 {@code CommentDao.selectTimedOutQuestions}）】</h2>
 * {@code fixedDelay} 每轮醒来，「超过 30 分钟」这个状态**一旦成立就永远成立** ⇒ 不幂等就是
 * 每 10 分钟把**同一条提问**再报一次，报到管理端把这一栏当噪声。
 * <p>判据 {@code NOT EXISTS (notification … EventType='question.timeout' AND TargetId=提问Id)}。
 * ⚠️ <b>它必须在 SQL 里参与 {@code LIMIT}，不能放在 Java 里跳</b> —— 否则已报过的那批
 * 永远占据前 {@code N} 个名额，第 {@code N+1} 条起永远扫不到（饥饿）。完整论证写在
 * {@link CommentDao#selectTimedOutQuestions} 的类注释那一节，改这个方法前先读那段。
 *
 * <h2>【⚠️ 为什么整类挂 {@code @ConditionalOnProperty}，且 a3/smoke 两个 profile 置 false】</h2>
 * 与 {@code MerchantDowngradeJob} / {@code StatsAggregateJob} / {@code CouponExpiryJob}
 * **逐字同款**：定时器一旦跑起来就会自己去连库。A3 的并发门禁要在**毫秒级**度量，一个自己醒来的
 * 后台线程会往被测路径里注入**无法从门禁输出里分辨**的噪声；smoke 切片则根本没有 DataSource。
 * ⇒ 本任务在 a3 / smoke 显式关闭，默认 profile（开发机 8088）保持开启（{@code matchIfMissing=true}）。
 * <p>⚠️ {@code @EnableScheduling} 已在 {@code MerchantDowngradeSchedulingConfig}，**本类不要重复加**。
 *
 * <h2>【{@code fixedDelay} 而不是 {@code fixedRate}】</h2>
 * 同前三个任务：上一轮**跑完**再等（库慢时拉长间隔，而不是并发跑两轮）。
 */
@Component
@ConditionalOnProperty(name = "mall.comment.question.timeout.enabled", havingValue = "true", matchIfMissing = true)
public class QuestionTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(QuestionTimeoutJob.class);

    /**
     * 「超时」的界 —— 方案 §4.2 写死的 30 分钟。
     *
     * <p>⚠️ 与 {@code CouponExpiryJob.MAX_COUPONS_PER_ROUND} 那类可调参数不同，**这个数不该随手调**：
     * 它是**用户可见口径**（方案逐字），调大调小都是需求变更，不是调优。
     */
    private static final int TIMEOUT_MINUTES = 30;

    /**
     * 每轮最多报几条。**不是性能调优，是爆炸半径的上界**：一轮的落库行数 ≤ 这个数
     * （同 {@code CouponExpiryJob.MAX_COUPONS_PER_ROUND} 的口径）。剩下的下一轮接着报
     * —— 配合 SQL 的 {@code ORDER BY created_at ASC}，「最老的先报」是它的正确性的一部分。
     */
    private static final int MAX_PER_ROUND = 50;

    /** 管理端共享收件箱的哨兵值 —— 与 {@code GoodsController} / {@code SeckillController} 逐字一致。 */
    private static final String ADMIN_INBOX_ID = "*";

    /** 收件人类型 = 管理端（同 {@code GoodsController.TO_TYPE_ADMIN}）。 */
    private static final String TO_TYPE_ADMIN = "admin";

    /**
     * 通知的 {@code TargetType} —— 与 {@code CommentService.NOTIFY_TARGET_COMMENT} 同值。
     * ⚠️ 必须是 {@code 'comment'}：{@link CommentDao#selectTimedOutQuestions} 的
     * {@code NOT EXISTS} 判据里写的就是它，两处不一致 ⇒ 幂等失效（每轮重复报）。
     */
    private static final String NOTIFY_TARGET_COMMENT = "comment";

    private final CommentDao commentDao;
    private final NotificationService notificationService;

    public QuestionTimeoutJob(CommentDao commentDao, NotificationService notificationService) {
        this.commentDao = commentDao;
        this.notificationService = notificationService;
    }

    /**
     * 扫描 + 报管理端。整方法 catch 一切：**一个定时任务抛异常会让 Spring 把后续调度也一起停掉**
     * （同前三个任务的处置）。
     */
    @Scheduled(fixedDelayString = "${mall.comment.question.timeout.fixed-delay-ms:600000}")
    public void scanTimedOutQuestions() {
        try {
            List<Map<String, Object>> timedOut = commentDao.selectTimedOutQuestions(
                    TIMEOUT_MINUTES, MAX_PER_ROUND, ADMIN_INBOX_ID, NotificationEvents.QUESTION_TIMEOUT);
            if (timedOut.isEmpty()) {
                return;   // 静默：绝大多数轮次都是空扫（存量提问都已报过 / 都已采纳），不该刷日志
            }
            // ⚠️ 一个 BatchId 覆盖「这一轮报的全部提问」—— 同 NotificationBroadcastService 的
            //    「一次投递 = 一个 BatchId + N 行」，管理端「通知历史」据此把这 N 行收成一行。
            String batchId = UUID.randomUUID().toString();
            String title = "提问超时未解决";
            String content = "有提问已超过 " + TIMEOUT_MINUTES + " 分钟未被采纳，请前往「问答管理」处理。";
            int sent = 0;
            for (Map<String, Object> row : timedOut) {
                String targetId = str(row.get("Id"));
                if (targetId == null) {
                    continue;   // 投影里 Id 不该缺；真缺了就没法当幂等键，跳过比乱报好
                }
                if (notificationService.send(batchId, ADMIN_INBOX_ID, TO_TYPE_ADMIN,
                        NotificationEvents.QUESTION_TIMEOUT, title,
                        content + "（提问：" + brief(row.get("Content")) + "）",
                        NOTIFY_TARGET_COMMENT, targetId)) {
                    sent++;
                }
            }
            if (sent > 0) {
                log.info("问答超时提醒（NOTIFY）：本轮命中 {} 条，落库 {} 条", timedOut.size(), sent);
            }
        } catch (Exception e) {
            log.warn("问答超时提醒整轮失败（NOTIFY）—— 下一轮重试", e);
        }
    }

    /**
     * 把提问正文摘一句进通知 —— 截到 30 字。
     *
     * <p>⚠️ 与 {@code CommentService.brief} 是**同形复制、不是共享**（本仓没有跨域的工具类，
     * 且这两处的截断长度是各自的呈现选择）。真正必须一致的是**为什么必须截**：
     * {@code notification.Content} 是 {@code varchar(1024)}，而 {@code comment.Content} 是
     * {@code varchar(1000)} ⇒ 整段搬过去会顶穿列宽被 MySQL 拒插，而
     * {@code NotificationService.send} 是 fail-soft ⇒ **外面连异常都看不见**，只是这条待办
     * 悄悄没发出去。
     */
    private static String brief(Object content) {
        String s = str(content);
        if (s == null) {
            return "";
        }
        return s.length() <= 30 ? s : s.substring(0, 30) + "…";
    }

    /** 取值用的 null 安全小工具（本类只用到这一处）。 */
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
