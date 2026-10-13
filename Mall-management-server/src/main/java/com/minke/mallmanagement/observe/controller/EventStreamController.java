package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.EventStreamService;
import com.minke.mallmanagement.observe.service.SseStreamWriter;
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
 * <p>⚠️ <b>本流读的是八环合并后的公共流</b>（{@code EventBroadcaster}）—— 它的「一拍只算一次」
 * 语义对本流尤其重要：日志页开着的时候，攻击模拟器页并不读事件（它只发流量），
 * 但**同一个页面的两次刷新**会短暂重叠 ⇒ 没有那层保护就会有一条流看着像死机。
 */
@RestController
@RequestMapping("/admin/observe/events")
public class EventStreamController {

    private final EventStreamService events;
    private final SseStreamWriter writer;

    public EventStreamController(EventStreamService events, SseStreamWriter writer) {
        this.events = events;
        this.writer = writer;
    }

    /**
     * 事件流：本拍新事件（8 类归一化）+ 按类计数 + 按严重度计数 + 速率。
     *
     * <p>⚠️ {@code Content-Type} 必须手动设（{@code produces} 管不了 {@code StreamingResponseBody}
     * 的响应头）—— 理由与实测见 {@link MetricsStreamController#stream}。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody stream(HttpServletResponse response) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        return writer.stream("events", events::frame);
    }
}
