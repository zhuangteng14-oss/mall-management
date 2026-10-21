package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.MetricsSnapshotService;
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
 * 指标面板的 SSE 出口（块 7 commit 3）—— 1 秒 1 帧，帧的内容见 {@link MetricsSnapshotService}。
 *
 * <h2>【⚠️ 读端点为什么是 {@code POST}】</h2>
 * SSE 的惯例是 {@code GET}（{@code EventSource} 只支持 GET）。本仓选 {@code POST}，理由与
 * {@code AdminStatsController} 那两条读端点**同一条**：管理端的读端点**全部**是 POST，
 * 单为这三条开一个 GET 面会让「读端点的动词」在本仓出现两种。⚠️ 前端**不**用 {@code EventSource}
 * （它不能带 {@code Authorization} 头，见 {@link SseStreamWriter} 类注释），改用
 * {@code fetch} + {@code ReadableStream} 自己解析 ⇒ **POST 不构成任何实现障碍**。
 *
 * <h2>【⚠️ 不加角色闸（TD-18 口径）】</h2>
 * 同 {@code AdminOrderController} / {@code AdminStatsController}：管理员 cookie 铸不出 token
 * ⇒ 加「须 role=3」会让**默认 profile 上管理员自己也 401**。真正把端点挡在门外的是
 * {@code app.auth.enabled} + {@code AuthInterceptor}（**本端点不在 {@code AUTH_WHITELIST} 里**，
 * 现 15 条里没有它）。⇒ 未登录打过来就是 401（**流还没开就 401**，不是「开了流再断」）。
 *
 * <h2>【断连】</h2>
 * 客户端关页面 / 刷新 ⇒ 写已关闭的输出流抛 IOException ⇒ {@link SseStreamWriter} 的 catch
 * 收工（不重试、不刷 WARN）。服务端**没有**独立的「连接表」要清理：每条连接的差分状态都在它自己
 * 那份帧源里（{@link MetricsSnapshotService#newFrameSource} 现建，流一结束就没人再引用它）。
 */
@RestController
@RequestMapping("/admin/observe/metrics")
public class MetricsStreamController {

    private final MetricsSnapshotService snapshots;
    private final SseStreamWriter writer;

    /** 连接名额表（D3-6）：单用户 50 / 全局 200，超限 429 + 提示。 */
    private final SseConnectionRegistry connections;

    public MetricsStreamController(MetricsSnapshotService snapshots, SseStreamWriter writer,
                                   SseConnectionRegistry connections) {
        this.snapshots = snapshots;
        this.writer = writer;
        this.connections = connections;
    }

    /**
     * 指标流：KPI（QPS / P99 / 错误率 / 命中率）+ 锁竞争 + 防护命中 + 库存水位。
     *
     * <p>{@code Cache-Control: no-cache} 是 SSE 的常规要求（中间层不许把这条响应缓存成一份静态体）。
     *
     * <p>⚠️ <b>{@code Content-Type} 必须在这里手动设</b> —— 上面 {@code @PostMapping} 的
     * {@code produces = text/event-stream} **不会**替 {@link StreamingResponseBody} 写上响应头：
     * {@code produces} 只在基于消息转换器的返回路径上被消费，而 {@code StreamingResponseBody}
     * 走的是异步返回路径（实测取证：不加这一行时响应头只有 {@code Cache-Control}，
     * 没有 {@code Content-Type}；见 {@code ObserveStreamSmokeTest} 对媒体类型的断言）。
     * 两条都留着：{@code produces} 管**路由与 Accept 协商**，这一行管**真实响应头**。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody stream(HttpServletRequest request, HttpServletResponse response) {
        // D3-6：先占名额，占不到就 429（不再开流）。释放点在 SseStreamWriter 的 finally。
        SseConnectionRegistry.Permit permit = connections.tryAcquire(request);
        if (permit == null) {
            return SseRejection.of(response, connections, request);
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        return writer.stream("metrics", snapshots::newFrameSource, permit::release);
    }
}
