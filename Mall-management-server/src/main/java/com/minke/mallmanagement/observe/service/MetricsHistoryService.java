package com.minke.mallmanagement.observe.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标页的**首屏历史窗口**（块 7 · 2026-10-17）—— 最近 N 拍已经算好的帧，给
 * {@code POST /admin/observe/metrics/history} 读。
 *
 * <h2>【它治的是什么：指标流只有「未来」，一进页面就必然从 0 起】</h2>
 * {@code /admin/observe/metrics/stream} 每连接现建一份 {@link MetricsSnapshotService.Session}，
 * 首帧是差分预热拍（全 0），之后才有真增量。于是「刚打开指标页」与「切走再回来」
 * 看到的都是一条从 0 开始爬的曲线 —— 用户 2026-10-17 报的「数据不实时、切页从 0 开始」。
 * 本类在**服务端**维持一个滚动窗口：一个独立于任何连接的帧源每秒采一拍，只留最近 N 拍。
 * 页面挂载时先读这份历史铺满图表，再接着消费 SSE 的实时增量。
 *
 * <h2>【为什么历史帧与流帧可以拼在一起】</h2>
 * 两者形状<b>逐字相同</b>（同一个 {@link MetricsSnapshotService#newFrameSource()} 产出），
 * 且都是「本拍增量」而不是累计值 —— 拼接不会出现「累计值接增量值」那种台阶。
 * 两份帧源各算各的差分（读 Micrometer 累计值<b>没有副作用</b>；与本类注释里那条
 * 「每条连接一份」的限制不冲突：被读走的只有 Session 自己的 {@code lastCounts}）。
 *
 * <h2>【采样是常驻的，代价与「有人看」无关】</h2>
 * 每秒一次 {@code stock()} 只读查询（{@code LIMIT 20}）+ 若干次 registry 查表 ——
 * 与指标流开着时同一量级，故页面关掉后这 1 次/秒仍会发生。这是首屏能拿到历史的代价：
 * 历史必须在<b>页面打开之前</b>就存在，否则第一次进入仍然从 0 开始。
 * 间隔与窗口长度可调：{@code mall.observe.metrics.history.interval-ms}（默认 1000）、
 * {@code mall.observe.metrics.history.size}（默认 60 = 页面那个 60 拍窗口）。
 *
 * <h2>【为什么不用 {@code @Scheduled}】</h2>
 * 同 {@link EventPersistPoller}：本仓调度器总闸挂在 {@code mall.downgrade.job.enabled} 上，
 * a3 下整台调度器不存在。指标历史是展示面的基础数据，不能押在那枚开关上。
 */
@Component
public class MetricsHistoryService {

    private static final Logger log = LoggerFactory.getLogger(MetricsHistoryService.class);

    private final MetricsSnapshotService snapshots;

    /** 窗口长度（拍）。 */
    private final int size;

    /** 采样间隔（毫秒）。 */
    private final long intervalMs;

    /** 滚动窗口本体；读写都在 {@code synchronized (history)} 里。 */
    private final List<Map<String, Object>> history = new ArrayList<>();

    private volatile boolean running = true;
    private Thread thread;

    public MetricsHistoryService(
            MetricsSnapshotService snapshots,
            @Value("${mall.observe.metrics.history.size:60}") int size,
            @Value("${mall.observe.metrics.history.interval-ms:1000}") long intervalMs) {
        this.snapshots = snapshots;
        this.size = Math.max(1, size);
        this.intervalMs = Math.max(100L, intervalMs);
    }

    /**
     * 起 daemon 采样线程。⚠️ 帧源建在**线程内部**（与 SSE 的「每连接一份」同一手法）：
     * 它只被这条线程调用，差分状态天然不与任何页面连接共享。
     */
    @PostConstruct
    void start() {
        thread = new Thread(() -> {
            SseStreamWriter.FrameSource source = snapshots.newFrameSource();
            long tick = 0;
            while (running) {
                try {
                    Map<String, Object> frame = source.frame(tick++);
                    // ⚠️ 首帧是差分预热拍（intervalMs=0、全 0），仍收进窗口 —— 页面按
                    //    intervalMs>0 过滤即可；留着它能让「刚启动的 60 拍窗口」如实短一截，
                    //    而不是把预热拍伪装成一拍真实数据。
                    synchronized (history) {
                        history.add(frame);
                        while (history.size() > this.size) {
                            history.remove(0);
                        }
                    }
                } catch (Throwable t) {
                    // 一次采样失败不能让窗口停住（停了 = 页面首屏永远空）。
                    log.warn("[observe] 指标历史采样异常（忽略，下轮继续）：{}", t.toString());
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "observe-metrics-history");
        thread.setDaemon(true);
        thread.start();
        log.info("[observe] 指标历史窗口已启动：每 {} ms 采一拍，保留最近 {} 拍"
                + "（mall.observe.metrics.history.*）", intervalMs, size);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 最近 {@code limit} 拍（按时间升序；{@code limit<=0} = 全部）。
     *
     * @param rawLimit 字符串入参（空白/0/负数 = 全部；不是整数 ⇒ 400，理由同
     *                 {@code ReplayService#intOr}：本仓管理端读端点的入参口径不各写一套）
     * @return {@code {success, list, size}} —— {@code list} 里每一帧与 SSE 帧逐字同形
     */
    public Map<String, Object> history(String rawLimit) {
        int wanted = intOr(rawLimit);
        List<Map<String, Object>> rows;
        synchronized (history) {
            int n = wanted <= 0 ? history.size() : Math.min(wanted, history.size());
            rows = new ArrayList<>(history.subList(history.size() - n, history.size()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // ⚠️ 与 `ReplayService.replay()` 同口径：`Result.of(...)` **不会**替你补 success（见
        //    `Result#of` 的注释），本域一次性 JSON 的 `success` 由 payload 自己写。
        out.put("success", true);
        out.put("list", rows);
        out.put("size", rows.size());
        return out;
    }

    /** 字符串参数 → 整数。空白 ⇒ 0（= 全部）；不是整数 ⇒ 抛（控制器转 400）。 */
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
}
