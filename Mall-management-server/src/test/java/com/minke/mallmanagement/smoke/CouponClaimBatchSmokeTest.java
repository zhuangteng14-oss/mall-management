package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C3 块 5 · 券域「券的信息架构重排」（2026-10-11）的 smoke test：
 * 新端点 {@code POST /marketing/coupon/claimBatch}（一键领取本周全部）
 * 与 {@code POST /marketing/coupons} 新加的 {@code thisWeek} 参数。
 *
 * <h2>【本类钉的是什么 —— 以及**不**钉什么】</h2>
 * 钉的是**进库之前**就能判定的参数闸：缺 {@code Account} 这一条。
 * 它在两处返回：① Spring 的必填参数校验（HTTP 4xx，压根没进方法体）；
 * ② {@code CouponService} 的 {@code isBlank} 首段（HTTP 200 + {@code success:false}）。
 * 两条都在**一次库都不碰**的地方返回 ⇒ 用切片（{@code @MockitoBean JdbcTemplate}）就能真跑
 * （同 {@code CartSmokeTest} 那条边界）。
 *
 * <p>⚠️ <b>本类**不**覆盖任何真读写的路径</b>：{@code claimBatch} 的集合怎么算、
 * 逐张的成败、{@code items[]} 的形状，全部要真库（{@code JdbcClient} 打到被 mock 的
 * {@code JdbcTemplate} 上只会抛 NPE，落到 Service 那个 {@code catch} 里变成一句
 * 不确定的「查询失败：…」文案）。断言它们只会写出**看起来在测、其实在测 Mockito
 * 默认行为**的用例。那几件事的验收归：VM 真库上的只读 SQL 实测（本周边界 / {@code daysLeft}）
 * 与 a3 后端的真端点手打。这条边界**写在这里**，免得下一个人以为
 * 「本类绿了 ⇒ 一键领取是好的」。
 *
 * <p>⚠️ 服务端「定集合」那条口径（前端不传 CouponId 列表）**在本类里没有对应断言** ——
 * 它的判据是「请求体里根本没有那个参数」，而那是端点签名决定的，不是运行时行为。
 */
class CouponClaimBatchSmokeTest extends AbstractSmokeTest {

    /**
     * {@code Account} **空串**（传了但为空）⇒ 走 Service 的 {@code isBlank} 闸，
     * 回 HTTP 200 + {@code success:false} —— 不是 4xx。
     * ⚠️ 这条与下面那条「完全不传」是**两个不同的闸**，都要钉：空串能过 Spring 的必填校验，
     * 只有 Service 挡得住它。
     */
    @Test
    void claimBatchWithBlankAccountIsRejected() throws Exception {
        mockMvc.perform(post("/marketing/coupon/claimBatch").param("Account", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：Account"));
    }

    /**
     * {@code Account} **完全不传** ⇒ 被 Spring 的必填参数校验挡在方法体之外（4xx）。
     * ⚠️ 断言用 {@code is4xxClientError} 而不是 {@code isBadRequest}：本域**没有**进
     * {@code GlobalExceptionHandler} 的 {@code assignableTypes} 白名单（那份只覆盖
     * Message / Goods 两个 Controller）⇒ 现状是 Spring 默认的 400，但这里要守的契约是
     * 「**客户端错误**，不是 200 的 {@code success:false}」—— 把 400 这个具体码钉死会让
     * 「日后给它加一个 advice 改成 422」变成一次假红。
     */
    @Test
    void claimBatchWithoutAccountIsClientError() throws Exception {
        mockMvc.perform(post("/marketing/coupon/claimBatch"))
                .andExpect(status().is4xxClientError());
    }

    /**
     * {@code /marketing/coupons} 新加的第三参 {@code thisWeek} **不**改变缺参那条闸 ——
     * 传了它、但 {@code Account} 为空，仍是同一句「缺少参数：Account」。
     * （这是 2026-10-11 那一笔唯一动到既有端点的**签名**改动，钉一条防它把闸带歪。）
     */
    @Test
    void couponsWithThisWeekStillChecksAccount() throws Exception {
        mockMvc.perform(post("/marketing/coupons").param("Account", "").param("thisWeek", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：Account"));
    }

    /** 券包同理 —— 它是这一笔之后**唯一**还被「用户中心弹窗」直接调的读端点。 */
    @Test
    void myCouponsWithBlankAccountIsRejected() throws Exception {
        mockMvc.perform(post("/marketing/myCoupons").param("Account", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：Account"));
    }
}
