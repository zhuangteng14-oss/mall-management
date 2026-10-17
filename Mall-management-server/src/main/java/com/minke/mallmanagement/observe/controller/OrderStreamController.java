package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.AttackEngine;
import com.minke.mallmanagement.observe.service.OrderInsightService;
import com.minke.mallmanagement.observe.service.OrderStreamService;
import com.minke.mallmanagement.observe.service.SseStreamWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

/**
 * 状态流转页的 SSE 出口（块 7 commit 3）—— 1 秒 1 帧，帧的内容见 {@link OrderStreamService}。
 *
 * <p>动词选 {@code POST}、不加角色闸、不在白名单 —— 三条口径与
 * {@link MetricsStreamController} **逐条相同**（那里写了完整理由，本文不重抄）。
 *
 * <p>⚠️ <b>本流是「管理端观测面」里唯一会读库的一条</b>：每拍两次只读查询
 * （五态计数聚合 + 最近订单列表），见 {@link OrderStreamService} 类注释。它**不改任何库**。
 */
@RestController
@RequestMapping("/admin/observe/orders")
public class OrderStreamController {

    private final OrderStreamService orders;
    private final OrderInsightService insight;
    private final AttackEngine attackEngine;
    private final SseStreamWriter writer;

    public OrderStreamController(OrderStreamService orders, OrderInsightService insight,
                                 AttackEngine attackEngine, SseStreamWriter writer) {
        this.orders = orders;
        this.insight = insight;
        this.attackEngine = attackEngine;
        this.writer = writer;
    }

    /**
     * 订单流：五态计数 + 最近订单（含进度步数）+ 本拍流转 + 本批次五态计数。
     *
     * <p>⚠️ <b>帧源是**工厂**，不是一份共享的</b>（块 7 · commit 10 的「丙」）：本方法被调一次
     * 只登记一条连接，真正的帧源在 {@link SseStreamWriter#stream} 里**由那条连接自己**建
     * （每条连接一份游标，理由见那两个类注释）。
     *
     * <p>⚠️ <b>批次号用 {@code Supplier} 现问，而不是在这里取一次值</b>：演示的常见次序是
     * 「先开着页面、再点开始」，在这里取的话这一整条连接会永远停在「没有批次」。
     *
     * <p>⚠️ {@code Content-Type} 必须手动设（{@code produces} 管不了 {@code StreamingResponseBody}
     * 的响应头）—— 理由与实测见 {@link MetricsStreamController#stream}。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody stream(HttpServletResponse response) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        return writer.stream("orders", () -> orders.newFrameSource(attackEngine::currentBatch));
    }

    /**
     * 状态流转页的分析面（2026-10-17）—— 24 小时趋势 / 耗时直方图 / 账龄分桶 /
     * 健康度 / 最慢 Top N / 全局明细。与 {@link #stream} **并列**：流负责「每秒变的那几个数」，
     * 本端点负责「打开页面时要铺满屏的聚合」，低频刷新即可。
     *
     * <p>⚠️ 它**只读**（同 {@link #stream}），但走普通 POST 而不是 SSE：一次性 JSON，
     * 没有「拍」也没有重连语义。
     */
    @PostMapping("/insight")
    public Map<String, Object> insight() {
        return insight.insight();
    }
}
