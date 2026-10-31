package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图形验证码闸（2026-10-31「上线前扫雷」第 ① 条）—— 开关**打开**时的三条。
 *
 * <p>与 {@link LoginSelectSmokeTest} 同一个端点、同一个切片，差别只有类上的
 * {@code @TestPropertySource} 把 {@code app.auth.captcha.enabled} 改成 {@code true}
 * （smoke profile 默认是 {@code false}，否则该测试类每一条都会被判 {@code -1}）。
 *
 * <p>三条合起来才是判据：
 * <ol>
 *   <li>不带 {@code captchaId/captchaCode} ⇒ {@code -1}（而不是照常走到凭据校验）；</li>
 *   <li>带了但服务端判假 ⇒ {@code -1}；</li>
 *   <li>服务端判真 ⇒ {@code 2} —— <b>缺这一条，把闸写死成「永远返回 -1」同样能全绿</b>。</li>
 * </ol>
 * {@code CaptchaService} 本身的行为（一次性 / 过期 / 内存兜底）在 {@code CaptchaServiceTest}。
 */
@TestPropertySource(properties = "app.auth.captcha.enabled=true")
class CaptchaGateSmokeTest extends AbstractSmokeTest {

    /** ① 不带验证码参数 ⇒ -1。桩照常备好，用来证明「不是凭据那边拦的」。 */
    @Test
    void loginSelect_withoutCaptchaParams_isRejectedWithMinusOne() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(2));

        mockMvc.perform(post("/user/loginSelect")
                        .param("username", "smoke_user")
                        .param("password", "smoke_password"))
                .andExpect(status().isOk())
                .andExpect(content().string("-1"));
    }

    /** ② 填了但校验不过 ⇒ -1。 */
    @Test
    void loginSelect_withWrongCaptcha_isRejected() throws Exception {
        when(captchaService.verifyAndConsume("cid", "WRONG")).thenReturn(false);

        mockMvc.perform(post("/user/loginSelect")
                        .param("username", "smoke_user")
                        .param("password", "smoke_password")
                        .param("captchaId", "cid")
                        .param("captchaCode", "WRONG"))
                .andExpect(status().isOk())
                .andExpect(content().string("-1"));
    }

    /** ③ 校验通过 ⇒ 照旧放行到凭据校验（这里 = 商家端 2）。 */
    @Test
    void loginSelect_withValidCaptcha_proceedsToCredentialCheck() throws Exception {
        when(captchaService.verifyAndConsume("cid", "7K3P")).thenReturn(true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(2));

        mockMvc.perform(post("/user/loginSelect")
                        .param("username", "smoke_user")
                        .param("password", "smoke_password")
                        .param("captchaId", "cid")
                        .param("captchaCode", "7K3P"))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));
    }
}
