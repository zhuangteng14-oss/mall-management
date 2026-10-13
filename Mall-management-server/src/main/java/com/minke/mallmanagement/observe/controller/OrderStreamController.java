package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.OrderStreamService;
import com.minke.mallmanagement.observe.service.SseStreamWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
    private final SseStreamWriter writer;

    public OrderStreamController(OrderStreamService orders, SseStreamWriter writer) {
        this.orders = orders;
        this.writer = writer;
    }

    /**
     * 订单流：五态计数 + 最近订单（含进度步数）+ 本拍流转。
     *
     * <p>⚠️ {@code Content-Type} 必须手动设（{@code produces} 管不了 {@code StreamingResponseBody}
     * 的响应头）—— 理由与实测见 {@link MetricsStreamController#stream}。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody stream(HttpServletResponse response) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        return writer.stream("orders", orders::frame);
    }
}
