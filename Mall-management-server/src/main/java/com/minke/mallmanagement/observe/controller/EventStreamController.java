package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.EventStreamService;
import com.minke.mallmanagement.observe.service.SseConnectionRegistry;
import com.minke.mallmanagement.observe.service.SseStreamWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 实时日志页的 SSE 出口（块 7 commit 3）—— 1 秒 1 帧，帧的内容见 {@link EventStreamService}。
 *
 * <p>动词选 {@code POST}、不加角色闸、不在白名单 —— 三条口径与
 * {@link MetricsStreamController} **逐条相同**（那里写了完整理由，本文不重抄）。
 *
 * <p>⚠️ <b>本流读的是八环合并后的公共流</b>（{@code EventBroadcaster}）—— 环是**共享**的，
 * 但**读它的游标不是**：每条连接一份（块 7 · commit 10 的「丙」）⇒ 两条流各读各的、互不吃数据。
 * 改前共用一份游标，两条连接各只看到一半（实测交集 420 条，见 {@link SseStreamWriter#stream}）。
 */
@RestController
@RequestMapping("/admin/observe/events")
public class EventStreamController {

    private final EventStreamService events;
    private final SseStreamWriter writer;

    /** 连接名额表（D3-6），口径同 {@link MetricsStreamController}。 */
    private final SseConnectionRegistry connections;

    public EventStreamController(EventStreamService events, SseStreamWriter writer,
                                 SseConnectionRegistry connections) {
        this.events = events;
        this.writer = writer;
        this.connections = connections;
    }

    /**
     * 事件流：本拍新事件（8 类归一化）+ 按类计数 + 按严重度计数 + 速率。
     *
     * <p>⚠️ {@code Content-Type} 必须手动设（{@code produces} 管不了 {@code StreamingResponseBody}
     * 的响应头）—— 理由与实测见 {@link MetricsStreamController#stream}。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody stream(HttpServletRequest request, HttpServletResponse response) {
        SseConnectionRegistry.Permit permit = connections.tryAcquire(request);
        if (permit == null) {
            return SseRejection.of(response, connections, request);
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        return writer.stream("events", events::newFrameSource, permit::release);
    }
}
