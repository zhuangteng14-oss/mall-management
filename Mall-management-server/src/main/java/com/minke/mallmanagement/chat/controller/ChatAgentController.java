package com.minke.mallmanagement.chat.controller;

import com.minke.mallmanagement.chat.service.ChatAgentService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能客服的唯一端点：{@code POST /chat/ask}。
 *
 * <h2>【入参只有一句话】</h2>
 * {@code Message} 走表单编码（与全站 41 处 {@code http.post} 同形 —— 前端
 * {@code buildInit} 给字符串 body 设的是 {@code application/x-www-form-urlencoded}）。
 * **没有 CustomerId / MerchantId 入参**：身份只从会话取，见
 * {@link ChatAgentService} 的类注释。
 *
 * <h2>【鉴权】</h2>
 * 本端点**不在白名单**里 ⇒ 默认 profile（闸开）下匿名请求在
 * {@code AuthInterceptor} 处直接 401，前端按 {@code allowUnauthorized} 旁路成
 * 「请先登录」。dev / a3 / smoke 的闸是关的 ⇒ 匿名也能进本方法，此时
 * {@code currentUserId} 为 {@code null}，Service 走「登录后才能查」的分支。
 * <b>不把 /chat/ask 加白名单</b>：那会连封禁校验一起放过。
 *
 * <h2>【越权面】</h2>
 * 端点没有任何「代表谁」的入参；DAO 查询全部带 {@code CustomerId = ?} /
 * {@code MerchantId = ?} 的行级条件 ⇒ 即便知道别人的单号，也查不出别人的订单。
 */
@RestController
@RequestMapping("/chat")
public class ChatAgentController {

    private final ChatAgentService chatAgentService;

    public ChatAgentController(ChatAgentService chatAgentService) {
        this.chatAgentService = chatAgentService;
    }

    @PostMapping("/ask")
    public Result<Object> ask(@RequestParam(value = "Message", required = false) String message,
                              HttpServletRequest request) {
        String userId = AuthSession.currentUserId(request);
        Integer role = AuthSession.currentRole(request);
        return Result.of(chatAgentService.ask(message, userId, role));
    }
}
