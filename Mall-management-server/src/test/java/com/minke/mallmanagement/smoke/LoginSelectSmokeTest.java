package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 只读接口 smoke test：POST /user/loginSelect
 *
 * <p>该接口是全项目入口，前端所有页面都先经过它。只读（仅 SELECT），无副作用。
 *
 * <h2>⚠️ 【三层第三笔改了这个 stub 的**匹配器**——不改会红，但红得不像「stub 不中」】</h2>
 * 三层前：{@code UserController} 直接 {@code jdbcTemplate.queryForObject(sql, Integer.class, args)}
 * ⇒ 匹配器是 {@code queryForObject(anyString(), eq(Integer.class), any(Object[].class))}。
 *
 * <p>三层后：SQL 下沉到 {@code UserDao}，它走 {@code JdbcClient}，而
 * {@code jdbcClient.sql(sql).params(..).query(Integer.class).single()} 的底层是
 * {@code query(Class).list()} → {@code DataAccessUtils.requiredSingleResult(list())} →
 * {@code JdbcTemplate.query(String, RowMapper, Object...)}（实证见
 * {@code DefaultJdbcClient.ResultQuerySpec#list} 与 {@code #single}）。
 * <b>它不再经过 {@code queryForObject} 那个重载</b> ⇒ 旧匹配器永不命中。
 *
 * <p>⚠️ <b>这个失配是「静默但可见」的，不是静默变绿</b>：桩不中 ⇒ Mockito 返回 {@code null}
 * ⇒ {@code .single()} 里 {@code requiredSingleResult(null)} 抛
 * {@code EmptyResultDataAccessException} ⇒ Dao 层正常抛出 ⇒
 * {@code UserService.loginSelect} 的 catch 把它翻成 {@code 0} ⇒ 本断言的
 * {@code content().string("2")} <b>变红</b>。所以这条断言本身仍然承担
 * 「SQL 调用形状与桩一致」的守门职责 —— 前提是有人去看它为什么红，
 * 而不是把期望值从 {@code "2"} 改成 {@code "0"}。
 *
 * <p>⚠️ 另一条**必须记住的不变量**：{@code .single()} 抛异常（而非返回 {@code null}）
 * 正是 {@code loginSelect} 返回 {@code 0} 的成因之一。若将来有人把 Dao 里的
 * {@code .single()} 换成 {@code .optional()}，「查不到」就变成
 * {@code Optional.empty()}、不再走 catch，本测试的失败形态会跟着变。
 */
class LoginSelectSmokeTest extends AbstractSmokeTest {

    /**
     * 断言链：HTTP 200 + 响应体等于 2（商家端）。
     *
     * <p>若桩未命中，{@code JdbcClient} 的 {@code .single()} 会抛
     * {@code EmptyResultDataAccessException}，{@code UserService.loginSelect} 的 catch
     * 把它翻成 0，断言的 "2" 失败 ——
     * 因此该断言同时能暴露「SQL 调用形状与桩不匹配」（见类注释）。
     */
    @Test
    void loginSelect_returns_int_on_valid_credentials() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(2));

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
