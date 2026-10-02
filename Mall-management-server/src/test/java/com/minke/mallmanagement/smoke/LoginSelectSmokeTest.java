package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 只读接口 smoke test：POST /user/loginSelect
 *
 * <p>该接口是全项目入口，前端所有页面都先经过它。只读（仅 SELECT），无副作用。
 */
class LoginSelectSmokeTest extends AbstractSmokeTest {

    /**
     * 断言链：HTTP 200 + 响应体等于 2（商家端）。
     *
     * <p>若桩未命中，JdbcTemplate 返回 null，controller 的
     * {@code role != null ? role : 0} 会返回 0，断言的 "2" 失败——
     * 因此该断言同时能暴露「SQL 调用形状与桩不匹配」。
     */
    @Test
    void loginSelect_returns_int_on_valid_credentials() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);

        mockMvc.perform(post("/user/loginSelect")
                        .param("username", "smoke_user")
                        .param("password", "smoke_password"))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));
    }
    /**
     * URL 契约防回归（2026-10-02 笔 2）：旧路径必须**不再有 handler**。
     *
     * <p>为什么不能只断言「新路径通」：那样把旧映射一并留着也照样绿 —— 「换了前缀」这件事就
     * 没有可观测证据。两个断言合起来才等价于「旧的不在、新的在」。
     */
    @Test
    void oldPrefixHasNoHandlerAnyMore() throws Exception {
        mockMvc.perform(post("/mysqlLogin/loginSelect")
                        .param("username", "smoke_user")
                        .param("password", "smoke_password"))
                .andExpect(status().isNotFound());
    }

}
