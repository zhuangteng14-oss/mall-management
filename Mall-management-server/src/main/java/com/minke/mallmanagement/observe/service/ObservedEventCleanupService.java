package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.dao.ObservedEventCleanupDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可观测域的**清理编排**（块 7 · commit 8b）—— 两个入口，一个 service：
 * ① 管理端点「清掉演练污染」；② 定时 job「清掉过期事件」。
 *
 * <h2>【⚠️ 为什么两件事共一个 service，而不是各写各的】</h2>
 * 它们删的是**两张不同的表、两套不同的判据**（谓词 vs 保留期），没有任何共用 SQL ——
 * 合并的理由不在代码复用，在**保留期这两个数是同一份配置**：`3 天 / 30 天` 是「攻击数据留多久、
 * 日常数据留多久」这一件事的一体两面，裂成两个类会让它们各有各的默认值、各改各的。
 * 而用户批文里那句「3 天攻击 / 30 天日常」正是**一条**口径。
 *
 * <h2>【⚠️ 本 service 不吞异常，与 {@code AuditService} 相反】</h2>
 * 审计是 fail-open（业务已成，不能让审计把它翻红）。本类是**破坏性动作的发起方**：
 * 删失败必须让调用方**知道**（端点回 500 / job 记 error 日志），静默成功比失败坏得多
 * —— 那是 TD-20 的口径。DAO 层的异常原样上抛，本类不 catch。
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
     * 攻击批次行（{@code observed_event.BatchId IS NOT NULL}）的保留期。
     * ⚠️ 默认值写在**注解里**（不是只写在 properties）：本仓既有 job 一律如此
     * （见 {@code OrderTimeoutCancelJob} 的 {@code timeoutMs}），好处是漏配一个 profile 时
     * 行为仍是确定的、可读的。
     */
    @Value("${mall.observe.cleanup.attack-retention-days:3}")
    private int attackRetentionDays;

    /** 日常事件行（{@code BatchId IS NULL}）的保留期。 */
    @Value("${mall.observe.cleanup.normal-retention-days:30}")
    private int normalRetentionDays;

    public ObservedEventCleanupService(ObservedEventCleanupDao cleanupDao) {
        this.cleanupDao = cleanupDao;
    }

    // ==================== ① 端点：按谓词清演练污染 ====================

    /**
     * 删掉两种谓词命中的业务行，回一张**可分字段显示**的结果表。
     *
     * <p>响应形状随全仓写端点惯例：{@code {"success":true, "deleted":{"order":3,…}, "total":N}}。
     * ⚠️ {@code deleted} 里**只含真删了行的表**（全 0 的表不出现）—— 一次「什么都没删」的清理
     * 与一次「删了 7 行」的清理，响应体形状上就该看得出区别。
     * <p>⚠️ {@code total} 是各表行数之和：同一张表可能被两种谓词各删一次（计数已相加），
     * 故它是「总删除行数」而不是「总表数」。
     */
    public Map<String, Object> cleanupAttackData() {
        Map<String, Integer> deleted = cleanupDao.deleteAttackData();
        int total = deleted.values().stream().mapToInt(Integer::intValue).sum();
        log.info("攻击数据清理完成（OBSERVE-CLEANUP）：共删 {} 行，明细={}", total, deleted);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("deleted", deleted);
        payload.put("total", total);
        payload.put("summary", summarize(deleted, total));
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
            log.info("事件台账保留期清理（OBSERVE-CLEANUP）：删 {} 行（攻击 {} 天 / 日常 {} 天）",
                    n, attackRetentionDays, normalRetentionDays);
        } else {
            log.debug("事件台账保留期清理（OBSERVE-CLEANUP）：本轮无可删行（攻击 {} 天 / 日常 {} 天）",
                    attackRetentionDays, normalRetentionDays);
        }
        return n;
    }

    /** 事件台账当前行数（只读；给手验 / 探针做「清前清后」的读数）。 */
    public long countEvents() {
        return cleanupDao.countObservedEvents();
    }
}
