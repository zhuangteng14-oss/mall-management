package com.minke.mallmanagement.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.shared.web.AuthInterceptor;
import com.minke.mallmanagement.shared.web.AuthTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不透明 token 的**端到端**面（多账户隔离笔，2026-10-06）—— 闸开（{@code app.auth.enabled=true}），
 * 走真注册的拦截器 + MockMvc，被测判定只有 {@code AuthSession} 的两条口径一处。
 *
 * <h2>【与 {@code AuthTokenStoreTest} 的分工】</h2>
 * 那一份是 {@code AuthTokenStore} 的**纯单测**（铸/解析/过期/滑动/销票，用注入时钟）。
 * 本类**不重复**那些 —— 它管的是单测管不到的两件事：
 * <ol>
 *   <li><b>票真的能替代 cookie</b>：从 {@code POST /user/token} 拿到的那串字符
 *       （不是直接调 {@code mint}）放进 {@code Authorization} 头，业务端点就认。</li>
 *   <li><b>两条口径的分叉在端到端可见</b>：{@code role=3} 的 cookie 在业务端点是 401、
 *       在 {@code /user/me} 是 200 降级形状。这一条是整笔里最容易被「顺手统一掉」的地方。</li>
 * </ol>
 *
 * <h2>【为什么本类自带 {@code @TestPropertySource}】</h2>
 * 它必须**闸开**才测得到凭据判定；而 smoke profile 显式 pin 了 {@code false}
 * （{@code application-smoke.properties}，见 {@code WebAuthConfig} 类注释）。
 * 代价与 {@code AuthGateSwitchOnTest} 一样：本类与别的 smoke 类**不共享上下文**，各缓存一份。
 *
 * <h2>【{@code @BeforeEach} 清表，且必须清】</h2>
 * {@code AuthTokenStore.LIVE} 是 {@code static} ⇒ 整条 JVM 生命周期共享，本类里铸的票会活到
 * 别的测试类跑（甚至 A3）。清表还有第二个作用：{@code liveCount()} 那几条计数断言才有意义。
 */
@TestPropertySource(properties = "app.auth.enabled=true")
class AuthTokenSmokeTest extends AbstractSmokeTest {

    /** 客户业务号 / 商家业务号 / 管理员业务号（值本身无意义，只要前后一致）。 */
    private static final String CUSTOMER = "C-1";
    private static final String MERCHANT = "M-9";
    private static final String ADMIN = "ADMIN-1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.auth.enabled:false}")
    private boolean switchOn;

    @BeforeEach
    void switchOn_andClearTheSharedTokenTable() {
        assertThat(switchOn)
                .as("⚠️ 本类前提是 app.auth.enabled=true。若为 false，说明 @TestPropertySource 没生效 —— "
                        + "下面每条 401/403 断言都会先红在这里的原因上")
                .isTrue();
        AuthTokenStore.clearForTest();
    }

    // ==================== 桩 ====================

    /**
     * 桩一次 {@code /user/me} 的资料路径（两跳）：{@code publicInfo}(业务号 → 用户名) +
     * {@code user} 表整行 + 头像。
     *
     * <p>⚠️ 图片查询与别的查询共用 {@code query(String, RowMapper, Object...)} 这一个重载 ⇒
     * 用 SQL 子串把图片那条**互斥**地钉住（手法同 {@code AuthSessionSmokeTest}），不靠多桩优先级。
     */
    private void stubProfile(String idColumn, String id, String usertype, String gender) {
        when(userCacheService.publicInfo(id)).thenReturn(Map.<String, Object>of("username", "smoke_user"));
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(AuthSessionSmokeTest.userRow(idColumn, id, usertype, gender));
        when(jdbcTemplate.query(contains("image"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("avatar.png"));
    }

    /** 造一个「浏览器里的」cookie 会话（登录态），角色可带可不带。 */
    private static MockHttpSession cookieSession(String businessId, Integer role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthInterceptor.SESSION_USER_ID, businessId);
        session.setAttribute(AuthInterceptor.SESSION_ROLE, role);
        return session;
    }

    /** 带上 {@code Authorization: Bearer <token>} 那一发。 */
    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(AuthTokenStore.HEADER, AuthTokenStore.BEARER + token);
    }

    /** 打一发 {@code POST /user/token}（带 cookie 会话）并取出响应体里的票。 */
    private String mintViaEndpoint(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/user/token").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return (String) MAPPER.readValue(result.getResponse().getContentAsString(), Map.class).get("token");
    }

    // ==================== 一、铸票 ====================

    /**
     * 无凭据打 {@code /user/token} ⇒ **401 + 与闸逐字节相同的响应体**。
     *
     * <p>⚠️ 断言不建立会话：铸票端点**不是**白名单面（{@code WebAuthConfig.AUTH_WHITELIST}），
     * 无凭据时它连 Controller 都到不了（闸先答）—— 这一条同时钉住「没被顺手加进白名单」。
     */
    @Test
    @DisplayName("铸票：无凭据 ⇒ 401（与闸同字节）")
    void token_withoutAnyCredential_is401() throws Exception {
        MvcResult result = mockMvc.perform(post("/user/token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().bytes(
                        AuthGateSmokeTest.BODY_NOT_LOGGED_IN.getBytes(StandardCharsets.UTF_8)))
                .andReturn();

        assertThat(result.getRequest().getSession(false))
                .as("铸票失败不得给匿名请求发一个会话（getSession(false) 纪律）")
                .isNull();
        assertThat(AuthTokenStore.liveCount()).as("401 那一发不铸票").isZero();
    }

    /**
     * ⚠️ <b>管理员 cookie **换不到**票</b>：会话里 {@code role=3} 时 {@code /user/token} 答 401。
     *
     * <p>判据用的是**授权**口径（{@code AuthSession.currentUserId}）：管理员身份只能由 token 承载
     * ⇒ 一张管理员 cookie 不能拿去续一张新票。若有人把这里改成识别口径
     * （{@code currentUserId} → {@code identify}），本条红 —— 那正是「管理员票可无限续期」的口子。
     */
    @Test
    @DisplayName("铸票：管理员 cookie ⇒ 401（不能拿 cookie 续票）")
    void token_withAdminCookie_is401() throws Exception {
        mockMvc.perform(post("/user/token").session(cookieSession(ADMIN, 3)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().bytes(
                        AuthGateSmokeTest.BODY_NOT_LOGGED_IN.getBytes(StandardCharsets.UTF_8)));

        assertThat(AuthTokenStore.liveCount()).isZero();
    }

    /**
     * 有效 cookie 会话 ⇒ 200 + 43 字符的票，且这张票**真的能替代 cookie**：拿它单独打
     * {@code /user/me}（**不带**会话）照样答出同一个身份。
     *
     * <p>这是本笔端到端链路的**主干用例**：{@code loginSelect} 写会话 → {@code /user/token} 铸票
     * → 之后所有请求只带票。少了后半个断言，本类就只证明了「端点会返回一个字符串」。
     */
    @Test
    @DisplayName("铸票：有效会话 ⇒ 43 字符的票，且该票可替代 cookie 独立认身份")
    void token_fromCookieSession_mintsAWorkingToken() throws Exception {
        stubProfile("customerid", CUSTOMER, "客户", "男");

        String token = mintViaEndpoint(cookieSession(CUSTOMER, 1));
        assertThat(token).as("Base64URL 无填充的 32 字节").hasSize(43);

        mockMvc.perform(withToken(post("/user/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.customerid").value(CUSTOMER))
                .andExpect(jsonPath("$.role").value(1));
    }

    /**
     * ⚠️ <b>票优先于 cookie</b>（隔离的核心）：请求同时带着 C-1 的票与 **M-9 的 cookie 会话**时，
     * 身份取 **C-1**。
     *
     * <p>这是「同一浏览器两个标签页」的形状：标签 2 登录把 cookie 覆盖成 M-9，标签 1 手里那张票
     * 不受影响。断言取 {@code $.customerid} = C-1 而**不是**「不是 M-9」：后者在应答为空时也成立。
     */
    @Test
    @DisplayName("票优先于 cookie：标签 2 覆盖了 cookie，标签 1 的票仍指自己")
    void token_takesPrecedenceOverTheCookieSession() throws Exception {
        stubProfile("customerid", CUSTOMER, "客户", "男");
        String tokenForTabOne = mintViaEndpoint(cookieSession(CUSTOMER, 1));

        mockMvc.perform(withToken(post("/user/me"), tokenForTabOne).session(cookieSession(MERCHANT, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerid").value(CUSTOMER))
                .andExpect(jsonPath("$.role").value(1));
    }

    // ==================== 二、不认识的票：不回落 cookie ====================

    /**
     * ⚠️ <b>本笔的 D4 裁定</b>：带了 {@code Authorization} 但票不认识 ⇒ <b>401</b>，
     * **即便此时 cookie 会话是好的**。
     *
     * <p>回落等于把隔离**静默降级**回「谁有 cookie 谁是身份」—— 正是本笔要治的病。
     * 两个断言缺一不可：① 状态是 401；② 响应体是闸那一串字节（说明这一发**没进 Controller**，
     * 是凭据判定拦的，不是 {@code me()} 自己的 401 分支）。
     */
    @Test
    @DisplayName("不认识的票 + 有效 cookie ⇒ /user/me 仍 401（D4：不回落）")
    void unknownToken_is401_evenWithAValidCookie() throws Exception {
        mockMvc.perform(withToken(post("/user/me"), "not-a-real-token").session(cookieSession(CUSTOMER, 1)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().bytes(
                        AuthGateSmokeTest.BODY_NOT_LOGGED_IN.getBytes(StandardCharsets.UTF_8)));

        verify(userCacheService, never()).publicInfo(anyString());
    }

    /**
     * 同上，换**业务端点**再钉一遍：这条不是重复 —— {@code /user/me} 走的是闸的第 ④ 档
     * （身份识别例外），而业务端点走第 ③ 档，两条判定分支都得实测。
     */
    @Test
    @DisplayName("不认识的票 + 有效 cookie ⇒ 业务端点也 401（走的是另一档判定）")
    void unknownToken_is401_onBusinessEndpointsToo() throws Exception {
        mockMvc.perform(withToken(post("/goods/goodsDe"), "not-a-real-token")
                        .session(cookieSession(MERCHANT, 2))
                        .param("MerchantId", MERCHANT)
                        .param("GoodName", "smoke-goods")
                        .param("ProductCode", "SMOKE-P1"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 三、管理员：cookie 与票的分叉 ====================

    /**
     * ⚠️ <b>管理员 cookie 在业务端点上一律 401</b>（{@code AuthSession.authorize} 把它折成未登录）。
     *
     * <p>这是「管理员身份只能由 token 承载」的可观测后果，也是前端「新标签页退回登录页」那条
     * 分支的**根据** —— 若这里放行，前端那个退回动作就成了无谓的打扰。
     */
    @Test
    @DisplayName("管理员 cookie 打业务端点 ⇒ 401")
    void adminCookie_onBusinessEndpoint_is401() throws Exception {
        mockMvc.perform(post("/goods/goodsSelect")
                        .session(cookieSession(ADMIN, 3))
                        .param("MerchantId", MERCHANT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().bytes(
                        AuthGateSmokeTest.BODY_NOT_LOGGED_IN.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 但**同一个管理员 cookie** 打 {@code /user/me} ⇒ <b>200 + 降级形状</b>：
     * 认出「你是管理员」**和你是哪个登录名**，但**不给资料**。
     *
     * <p>⚠️ <b>2026-10-11 用户裁决「甲」改写了本用例的判据</b>（原来只答 {@code role}）：
     * 前端 {@code api/auth.js} 的 {@code isLoggedIn()} 判据是 {@code !!(username||merchantid)}
     * ⇒ 只答 {@code role} 时管理端在**新标签页 / 重开浏览器**恒判「未登录」、被守卫弹回
     * {@code /login}（实测「管理端进不去」）。故本支改为补答 {@code username}，条件与边界
     * 见 {@code UserController#me} 第 2 种出口的 javadoc。
     *
     * <p>⚠️ <b>因此「没给资料」的判据不能再是「{@code publicInfo} 一次都没被调用」</b> ——
     * 那一跳（业务号 → 登录名）现在**必须**走。判据换成三条更贴的：
     * <ol>
     *   <li>响应键集里 {@code customerid} / {@code merchantid} / {@code usertype} / {@code gender}
     *       一个都不出现 —— {@code publicInfo} 那 5 列里除 {@code username} 外**一列都不许外泄**；
     *       头像 {@code image} 也不许出现。</li>
     *   <li>资料那一跳（{@code meInfo} 的第二跳：{@code user} 表整行 {@code queryForMap}）
     *       <b>一次都没走</b>。</li>
     *   <li>头像那一条查询（{@code query(contains("image"), …)}）<b>一次都没走</b>。</li>
     * </ol>
     * ②③ 是**强判据**：只断言键缺失的话，「查了整行但没查到」也能让它绿。
     */
    @Test
    @DisplayName("管理员 cookie 打 /user/me ⇒ 200 降级形状（只回 role + 登录名，不查资料）")
    void adminCookie_onMe_getsDegradedAnswer() throws Exception {
        mockMvc.perform(post("/user/me").session(cookieSession(ADMIN, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.role").value(3))
                .andExpect(jsonPath("$.customerid").doesNotExist())
                .andExpect(jsonPath("$.merchantid").doesNotExist())
                .andExpect(jsonPath("$.usertype").doesNotExist())
                .andExpect(jsonPath("$.gender").doesNotExist())
                .andExpect(jsonPath("$.image").doesNotExist());

        // ① 只走了「业务号 → 登录名」这一跳：恰好一次，且查的就是这个管理员
        verify(userCacheService, times(1)).publicInfo(ADMIN);
        // ② 资料那一跳（user 表整行）没走；③ 头像那条查询没走
        verify(jdbcTemplate, never()).queryForMap(anyString(), any(Object[].class));
        verify(jdbcTemplate, never()).query(contains("image"), any(RowMapper.class), any(Object[].class));
    }

    /**
     * 管理员**票**与管理员 cookie 的分叉：带着 {@code role=3} 的票打 {@code /user/me}
     * ⇒ **完整资料**（含 {@code role:3}）。
     *
     * <p>票在表里是直接铸的（{@code AuthTokenStore.mint}，不走 {@code /user/token} ——
     * 那条路刻意铸不出管理员票）。这正是「token 机制支持造 role=3 记录」这条测试面的用法。
     */
    @Test
    @DisplayName("管理员票打 /user/me ⇒ 完整资料（降级只对 cookie 生效）")
    void adminToken_onMe_getsTheFullProfile() throws Exception {
        stubProfile("merchantid", ADMIN, "商家", "女");
        String adminToken = AuthTokenStore.mint(ADMIN, 3);

        mockMvc.perform(withToken(post("/user/me"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.merchantid").value(ADMIN))
                .andExpect(jsonPath("$.role").value(3));
    }

    // ==================== 四、登出销票 ====================

    /**
     * 登出**销票**：把票带在 {@code /user/logout} 上打一发，那张票此后不认。
     *
     * <p>⚠️ 这一条钉的是前端 {@code UserCenter.vue} 里那句顺序（销票必须在 POST **之后**）：
     * 票要在这一发里送出去，后端才销得掉。
     * <p>⚠️ 判据取「再打一次 {@code /user/me} ⇒ 401」而不是 {@code liveCount()}：端到端面要看行为。
     */
    @Test
    @DisplayName("登出：本请求带来的那张票被销掉（再打 ⇒ 401）")
    void logout_revokesTheTokenCarriedByThatRequest() throws Exception {
        stubProfile("customerid", CUSTOMER, "客户", "男");
        String token = mintViaEndpoint(cookieSession(CUSTOMER, 1));
        assertThat(AuthTokenStore.resolve(token)).as("前提：登出前这张票是好的").isNotNull();

        mockMvc.perform(withToken(post("/user/logout"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(AuthTokenStore.resolve(token)).as("登出即失效 —— 不承诺字节序的那个 200 不影响本判据").isNull();
        mockMvc.perform(withToken(post("/user/me"), token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ⚠️ <b>登出只销本请求那一张票</b>—— 另一个标签页手里的票**不受影响**。
     *
     * <p>「一个标签页登出，全家掉线」正是隔离的反面，而它有一个很自然的误实现
     * （销 {@code userId} 名下的所有票）。本条就是钉住这个误实现的。
     */
    @Test
    @DisplayName("登出：不误伤别的标签页的票")
    void logout_doesNotRevokeOtherTabsTokens() throws Exception {
        stubProfile("customerid", CUSTOMER, "客户", "男");
        String tabOne = mintViaEndpoint(cookieSession(CUSTOMER, 1));
        String tabTwo = mintViaEndpoint(cookieSession(CUSTOMER, 1));
        assertThat(tabTwo).as("两张票必须是不同的（每次 mint 都新铸）").isNotEqualTo(tabOne);

        mockMvc.perform(withToken(post("/user/logout"), tabOne)).andExpect(status().isOk());

        mockMvc.perform(withToken(post("/user/me"), tabTwo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerid").value(CUSTOMER));
    }
}
