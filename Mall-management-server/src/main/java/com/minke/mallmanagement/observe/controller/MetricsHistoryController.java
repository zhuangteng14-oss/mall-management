package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.MetricsHistoryService;
import com.minke.mallmanagement.shared.web.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指标页首屏历史的读口（块 7 · 2026-10-17）—— {@code POST /admin/observe/metrics/history}。
 *
 * <h2>【为什么要它：SSE 只答「从现在起」】</h2>
 * {@code /metrics/stream} 的首帧是差分预热拍（全 0），页面一进入就必然从 0 起。
 * 前端挂载时先打本端点拿最近 60 拍铺满图表，再接着消费 SSE —— 用户要求的那句
 * 「路由挂载时向后端请求当前 60 拍的历史数据做首屏渲染」就是这一条。
 *
 * <h2>【端点口径与本域其余各条逐条相同】</h2>
 * 全 POST（管理端读端点在本仓全部是 POST）、入参 {@code @RequestParam}（**不用**
 * {@code @RequestBody}）、不加角色闸、不在 {@code AUTH_WHITELIST}（未登录由
 * {@code AuthInterceptor} 拦成 401）。入参非法（{@code limit} 不是整数）走 400，
 * 处置与 {@link ReplayController} 逐字相同：本类显式构 {@link ResponseEntity#badRequest()}，
 * 不把 {@link IllegalArgumentException} 抛给默认错误页。
 */
@RestController
@RequestMapping("/admin/observe/metrics")
public class MetricsHistoryController {

    private final MetricsHistoryService history;

    public MetricsHistoryController(MetricsHistoryService history) {
        this.history = history;
    }

    /**
     * 最近 N 拍（升序，含刚采到的那一拍）。
     *
     * @param limit 最多几拍（可选；空白/0/负数 = 窗口里全部，缺省窗口 60 拍）
     * @return {@code {success:true, list:[…], size:N}}。⚠️ {@code list} 里每一帧与 SSE
     *         帧**逐字同形**，前端直接按流帧处理，不必写第二套解析。
     *         ⚠️ 服务刚启动时窗口还没攒满 ⇒ {@code size} 小于 {@code limit} 是**正常**的，
     *         不是错误，也不是「历史丢了」。
     */
    @PostMapping("/history")
    public ResponseEntity<Object> history(
            @RequestParam(value = "limit", required = false) String limit) {
        try {
            return ResponseEntity.ok(Result.of(history.history(limit)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
        }
    }
}
