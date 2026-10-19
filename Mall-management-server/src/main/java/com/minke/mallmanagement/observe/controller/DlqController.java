package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.dao.ObservedEventDlqDao;
import com.minke.mallmanagement.shared.web.PageLimits;
import com.minke.mallmanagement.shared.web.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 死信落库副本的**只读**出口（D3-2）—— {@code POST /admin/observe/dlq/list}。
 *
 * <h2>【它答的是「页面为什么说丢了」】</h2>
 * 指标面板的 {@code dlq.persisted} 只给一个数字；本端点给出具体行：
 * 哪一批、卡在哪个阶段、最后一次异常是什么。没有它，「死信 3 条」只能去 broker 管理台看，
 * 而 broker 在 a3 / dev 上常常根本没有。
 *
 * <h2>【端点口径与本域其余各条逐条相同】</h2>
 * POST、{@code @RequestParam}（不用 {@code @RequestBody}）、不加角色闸、不在
 * {@code AUTH_WHITELIST}（未登录由 {@code AuthInterceptor} 拦成 401）。
 * 入参非法（{@code limit} / {@code offset} 不是整数）走 **400**，处置同
 * {@link ReplayController}：显式构 {@link ResponseEntity#badRequest()}，不抛给默认错误页。
 *
 * <h2>【{@code limit} 的归一】</h2>
 * 走 D3-3 的统一口径 {@link PageLimits}：缺省 / 非正数 ⇒ 100，超限 ⇒ 100。
 * {@code offset} 负数归 0（与 {@code OrderStreamService.detail} 同款）。
 */
@RestController
@RequestMapping("/admin/observe")
public class DlqController {

    private final ObservedEventDlqDao dlqDao;

    public DlqController(ObservedEventDlqDao dlqDao) {
        this.dlqDao = dlqDao;
    }

    /**
     * 最近死信（按落库时刻倒序）。
     *
     * @param limit  最多几行（可选；缺省 / 非正数 ⇒ {@value PageLimits#DEFAULT_PAGE_SIZE}，
     *               上限 {@value PageLimits#MAX_PAGE_SIZE}）
     * @param offset 跳过几行（可选；缺省 / 负数 ⇒ 0）
     * @return {@code {success:true, list:[…], total:N, limit:M, offset:K}}。
     *         ⚠️ {@code total} 是**全表**条数（不是本页行数）—— 页面才能写「共 N 条」。
     *         表为空 ⇒ {@code list=[]}、{@code total=0}，**仍是 200**。
     */
    @PostMapping("/dlq/list")
    public ResponseEntity<Object> list(
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "offset", required = false) String offset) {
        try {
            int wanted = parse(limit, "limit", 0);
            int skip = Math.max(0, parse(offset, "offset", 0));
            int effective = PageLimits.clamp(wanted);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("list", dlqDao.select(effective, skip));
            out.put("total", dlqDao.count());
            out.put("limit", effective);
            out.put("offset", skip);
            return ResponseEntity.ok(Result.of(out));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
        }
    }

    /**
     * 空白 ⇒ {@code fallback}；非整数 ⇒ {@link IllegalArgumentException}（→ 400）。
     * ⚠️ 不把 {@code NumberFormatException} 直接抛出去：它的 message 是英文的
     * 「For input string: …」，页面看不出是哪个参数错了。
     */
    private static int parse(String raw, String name, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是整数：" + raw);
        }
    }
}
