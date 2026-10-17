package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.dao.ObservedEventDao;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演练回放服务（块 7 收尾）—— 把**一次演练当时记下来的事件**按批次读回来，交给前端按原时间戳播。
 *
 * <h2>【它读的是台账，不是日志页】</h2>
 * 读的是 {@code observed_event}（⑲-1 那张落库台账，由 MQ 消费者写），**不是**日志页那条 SSE 的
 * 帧。两者是同一批事件的两种去处，但**留存期完全不同**：SSE 只有当下这一拍（读走就没了），
 * 台账留到被清理任务删掉为止（攻击批次 3 天、日常 30 天 —— 见
 * {@code ObservedEventCleanupJob} 的两个 {@code @Value}）。
 *
 * <p>⚠️ <b>但台账也不是「全程录像」，页面上必须说清这件事</b>：台账由
 * {@code EventBroadcaster.poll()} 驱动（那条是**流线程**在拉），而流是「谁开着日志页谁在拉」
 * —— 块 7 期间实测过（TD-61 一族）：<b>当时没人开着日志页的那段演练，事件攒在环里但不会落库</b>。
 * ⇒ 回放出来的那条时间轴**可能只是那一轮的一部分**，前端那句话（「回放数据来自「当时开着
 * 日志页的演练」」）不是免责声明，是这个数据源的**准确描述**。
 *
 * <h2>【{@code limit} 的两条口径】</h2>
 * <ol>
 *   <li><b>默认 {@value #DEFAULT_LIMIT}</b>（用户 2026-10-15 定）：一次演练最多能泼出十万量级
 *       的事件，一次性 JSON 化的代价（内存 + 序列化 + 前端一次收完）不该由「看一眼」来承担。
 *       5000 条够看出「那一轮长什么样」（实测某一天的批次共 20,374 条 ⇒ 5000 条约是四分之一；
 *       要全量把 {@code limit} 显式传大）。</li>
 *   <li><b>上限 {@value #MAX_LIMIT}</b>：只防「把整张表拉出来」，不是产品口径 —— 所以它比现实
 *       中任何一轮演练都大得多（留出两个数量级）。</li>
 * </ol>
 * ⚠️ 与 {@code DrillRecordService.records} **同一套**「空白/0/负数 = 没给、不是整数 = 400」
 * 的处置（那里写了完整理由）；两处各写一份私有 helper 是**有意的**：跨服务共用一个
 * 「怎么读分页参数」的工具类，会把两个端点的入参口径绑在一起（它们没有任何理由一起变）。
 */
@Service
public class ReplayService {

    /** 缺省条数（用户 2026-10-15 定的 5000 —— 理由见类注释）。 */
    public static final int DEFAULT_LIMIT = 5000;

    /** 上限（只防「拉全表」；见类注释第 2 条）。 */
    public static final int MAX_LIMIT = 50000;

    private final ObservedEventDao observedEventDao;

    public ReplayService(ObservedEventDao observedEventDao) {
        this.observedEventDao = observedEventDao;
    }

    /**
     * 读一个批次的事件（{@code POST /admin/observe/replay}）。
     *
     * @param batchId 批次 UUID（**必填** —— 没有批次就无从谈起「哪一轮」；空白 ⇒ 400，
     *                不回落成「读全部」：那会把一次填错参数变成一次全表读）
     * @param limit   最多几条（可选；空白/0/负数 ⇒ {@value #DEFAULT_LIMIT}，
     *                超过 {@value #MAX_LIMIT} 收进上限，不是整数 ⇒ 400）
     * @return {@code {success, batchId, list, total, limit}}。
     *         ⚠️ 键名**故意不统一**：{@code list/total/limit} 小写（照
     *         {@code DrillRecordService.records} 的信封形状），{@code batchId} 驼峰
     *         （它是「值」不是「信封」—— 与响应里每行的 {@code BatchId} 同一个东西，
     *         写成 {@code batchid} 会让同一次响应里出现两种拼法）。前端两处都要照抄，别猜。
     */
    public Map<String, Object> replay(String batchId, String limit) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId 必填：回放的是**某一次**演练（批次 UUID 见演练记录那一行的 BatchId）");
        }
        String key = batchId.trim();
        int effectiveLimit = clampLimit(intOr(limit));

        List<Map<String, Object>> rows = observedEventDao.selectByBatch(key, effectiveLimit);
        long total = observedEventDao.countByBatch(key);

        // ⚠️ LinkedHashMap 保序仅为可读（本响应没有靠字段顺序渲染的地方）—— 与
        //    DrillRecordService.split 那条「表格列顺序靠它稳定」不是一回事，别照抄理由。
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("batchId", key);
        ok.put("list", rows);
        // ⚠️ **多这一条 COUNT 是有意的**（用户 2026-10-15 的裁决只说了 limit，没说 total）：
        //    没有它，前端只知道自己收到了 M 条，**说不出**「是不是全部」—— 那就会把一次
        //    被 limit 截断的回放画成「这一轮就这么多」（TD-20：不静默）。多一次走索引的
        //    COUNT，换的是「播完之后那句话是真是假」。
        //
        // ⚠️ ⚠️ **两条查询不是一个快照**（实测 2026-10-15，如实登记）：那次探针里
        //    `selectByBatch` 读到 **2749** 行、`countByBatch` 读到 **2799** 行 —— 差值
        //    **正好 50**，就是 `ObservedEventConsumer.BATCH_SIZE`（MQ 消费者一次落一批 50 条）：
        //    演练刚结束、MQ 那几批还在排空时发起的回放，SELECT 与 COUNT 之间又落了一整批。
        //    ⇒ **判据给前端定死**：只有 `list.length >= limit` 才算「被 limit 截断」，
        //    `list.length < total` **不构成**截断的证据（那可能只是排空尾巴）。
        //    不为此加事务/一致性快照：回放是个只读的展示面，为一个「多 50 条」的尾部
        //    去开 REPEATABLE READ 不划算，说清楚比修掉更值。
        ok.put("total", total);
        ok.put("limit", effectiveLimit);
        return ok;
    }

    // ==================== 内部 ====================

    /** 字符串参数 → 整数。空白 ⇒ 0（= 没给）；**不是整数 ⇒ 抛**（控制器转 400）。理由同 {@code records}。 */
    private static int intOr(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit 必须是整数，收到：" + raw.trim());
        }
    }

    /** 收进区间：{@code <= 0} 当「没给」看（分页控件不会发 0，但 curl 会）。 */
    private static int clampLimit(int wanted) {
        if (wanted <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(wanted, MAX_LIMIT);
    }
}
