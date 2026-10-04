package com.minke.mallmanagement.smoke;

import com.minke.mallmanagement.shared.web.AuthInterceptor;
import com.minke.mallmanagement.shared.web.WebAuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录闸（鉴权笔 1）的 10 条矩阵 —— 被测判定只有 {@link AuthInterceptor} 一处。
 *
 * <h2>【为什么一个文件里有两个测试类】</h2>
 * 「开关 OFF 对照」需要**两份不同 property 的上下文**（{@code app.auth.enabled} 在
 * {@code @TestPropertySource} 里无法按用例改）。{@code @Nested} 内部类 +
 * {@code @NestedTestConfiguration(OVERRIDE)} 试过：{@code @TestPropertySource} 确实生效，
 * 但 {@code @MockitoBean} 的替身**不为内部类 reset** ⇒ 基类的
 * {@code AbstractSmokeTest.stubLockAsDirectRun} 第二次 {@code when(...)} 会执行上一条用例留下的
 * 旧 answer（{@code getArgument(2)} 为 null）⇒ NPE。要绕开就得在本类里重写基类那个包级
 * {@code @BeforeEach} 再补一句 {@code reset(...)} —— 绕行代码比它绕开的问题更难懂。
 * 故改用最朴素的做法：同一文件两个**顶层**包级类，各拿一份上下文 ——
 * {@link AuthGateSmokeTest}（默认 OFF，2 条）+ {@link AuthGateSwitchOnTest}
 * （{@code app.auth.enabled=true}，8 条）。两层**都是真集成**：真注册的拦截器 + MockMvc。
 *
 * <p>⚠️ 副作用（已知，可接受）：第二个类名不等于本文件名，故
 * {@code ./mvnw -B test -Dtest=AuthGateSmokeTest} **只会跑 2 条**。跑全闸必须
 * {@code -Dtest=AuthGateSmokeTest,AuthGateSwitchOnTest}，或者不带 {@code -Dtest}。
 *
 * <h2>【闸到底有没有被加载：两个类各钉一条 {@code @BeforeEach}}】</h2>
 * {@code @WebMvcTest} 切片按类型过滤，**会**扫到 {@code WebAuthConfig}
 * （{@code WebMvcConfigurer} 在名单内）⇒ 本文件**不需要** {@code @Import}
 * （2026-10-04 实测：摘掉后仍 10/10 绿）。但这件事**不靠约定、靠断言**：
 * 两个类各有一条 {@code webAuthConfigBean != null} 的 {@code @BeforeEach}。少了它，
 * 「放行」类断言会退化成**空命题**（闸不在 ⇒ 什么都拦不住 ⇒ 期望 400 照样绿），
 * 而「拦截」类断言会红得莫名其妙（实测形状：「期望 401 实得 400」）。
 *
 * <p>⚠️ <b>留档一处被推翻的中间结论</b>：本笔一度在 {@code @Nested} 内部类里看到
 * {@code WebAuthConfig} 注入为 {@code null}，据此推出「切片不扫 {@code WebMvcConfigurer}」
 * 并给本文件加了 {@code @Import}。改成两个**顶层**类后摘掉 {@code @Import}，10/10 仍绿 ⇒
 * 那个 {@code null} 是 {@code @Nested} 上下文特有的假象，与切片机制无关。
 * 教训：单点观测别直接推广成机制结论 —— 这也是本文件最终不用 {@code @Nested} 的第二个理由。
 *
 * <h2>【判据为什么是「400」而不是「不是 401」】</h2>
 * 放行路径的统一判据取 **400**：打的都是缺参请求，缺参由 {@code GlobalExceptionHandler} 转成
 * 400（{@code api-contract.md} 钉住「形参缺失：仍是 400」）。400 比「不是 401」强得多 ——
 * 它证明请求**确实穿过了闸并到达 Controller 层**；而「不是 401」在闸压根没注册时同样成立。
 *
 * <h2>【白名单 12 条为什么直调拦截器，而不是走 MockMvc】</h2>
 * {@code /health/db} 带 {@code @Profile("a3")}，在 smoke profile 下**没有 handler**
 * ⇒ MockMvc 送不进去（无 handler 则拦截器不执行）；白名单里还有 {@code /error}、
 * {@code /favicon.ico} 这类「下游答什么不由闸决定」的路径，用 MockMvc 只能断言「不是 401」，
 * 分不开「被白名单放过」与「压根不存在」。故
 * {@link AuthGateSwitchOnTest#whitelist_allEntries_passTheGate()} 直调 {@code preHandle} 逐条钉死。
 * ⚠️ 这之所以可能，是因为 {@code AuthInterceptor} **不是** {@code @Component}
 * 而由 {@link WebAuthConfig} 显式构造（见该类注释）—— 那一点是刻意留的。
 *
 * <h2>【突变对照：三条误改各自会弄红谁（2026-10-04 实跑，非推断）】</h2>
 * <ul>
 *   <li>把开关写死成「常开」（丢掉 {@code app.auth.enabled}）⇒ 本文件 OFF 层 **2/2** 红；
 *       <b>全量跑</b>另有既有的 8 个 smoke 类里的 7 个、共 **27** 条红（逐类见
 *       {@code WebAuthConfig} 的注释）—— 这是「开关必须默认 false」的直接证据；</li>
 *   <li>把会话判据放宽成「有会话即可」（丢掉 {@code SESSION_USER_ID} 属性判断）⇒
 *       **只** {@code withSessionButNoUserId_is401} 红（1/10）；</li>
 *   <li>丢掉白名单判定（对一切路径都要求登录）⇒ **4** 条红：
 *       {@code whitelist_allEntries_passTheGate} + 3 条放行用例（goodstype / loginSelect / img）。</li>
 * </ul>
 *
 * <p>⚠️ 本文件**不**验证闸的「认证侧」—— 本笔没有任何端点会把 userId 写进会话（那是鉴权笔 2）。
 */
class AuthGateSmokeTest extends AbstractSmokeTest {

    /** 401 响应体（逐字节）。与 {@code AuthInterceptor} 里的字面量必须一致。 */
    static final String BODY_NOT_LOGGED_IN = "{\"success\":false,\"msg\":\"未登录\"}";

    /** 与 {@code ImgMappingSmokeTest} 同一个夹具：static/img 下唯一被业务语义引用的文件。 */
    static final String IMG_FIXTURE = "default_user.png";

    /**
     * 「未登录可达」但**刻意不在白名单**里的端点。
     *
     * <p>它们能被未登录用户打到，是**前端缺守卫**（`P2.6` 笔的活），不是把它们公开的理由。
     * 本常量把这层区分钉成回归线：将来谁要放宽其中任何一条，必须**显式改这里并说明理由**。
     * 来源：2026-10-04 前端全量核实（16 处未登录可达调用点）。
     */
    static final String[] REACHABLE_BUT_NOT_PUBLIC = {
            "/goods/goodsAdd",
            "/goods/goodsDe",
            "/message/userSelect",
            "/message/friendAdd",
            "/user/updateAvatar",
            "/user/updateUserInfo",
    };

    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    @Autowired(required = false)
    private WebAuthConfig webAuthConfigBean;

    @BeforeEach
    void switchOff_andTheGateIsReallyLoaded() {
        assertThat(authEnabled)
                .as("⚠️ 本层前提是 app.auth.enabled=false（application.properties 的默认值）。"
                        + "若为 true，说明有人改了默认值 —— 那会让本笔从「零影响」变成「全套件转红」")
                .isFalse();
        assertThat(webAuthConfigBean)
                .as("WebAuthConfig 没进上下文 ⇒ 本层「没被拦」是空命题（闸压根不在）")
                .isNotNull();
    }

    // ==================== 开关 OFF 对照（2 条）====================

    /**
     * 开关关着时，非白名单端点**必须照常穿过** —— 这是「本笔对既有行为零影响」的直接证据。
     *
     * <p>判据取 400（缺参）而不是 200：它比「不是 401」强，证明请求走到了 Controller 层。
     * ⚠️ 本条就是「开关被写死成常开」的突变对照。
     */
    @Test
    void goodsAdd_withoutSession_isNotGated_whenSwitchOff() throws Exception {
        mockMvc.perform(post("/goods/goodsAdd"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 同上的第二个端点，覆盖**另一个 Controller**：证明「开关关着 ⇒ 全站放行」不是碰巧只对
     * goods 域成立（若拦截器只在某个 Controller 上生效，本条会红而上面那条照样绿）。
     */
    @Test
    void messageConversations_withoutSession_isNotGated_whenSwitchOff() throws Exception {
        mockMvc.perform(post("/message/conversations"))
                .andExpect(status().isBadRequest());
    }
}

/**
 * 开关打开时的 8 条。与 {@link AuthGateSmokeTest} 同文件（理由见该类注释），
 * 差别只有一处：类上的 {@code @TestPropertySource} 把 {@code app.auth.enabled} 改成 {@code true}。
 */
@TestPropertySource(properties = "app.auth.enabled=true")
class AuthGateSwitchOnTest extends AbstractSmokeTest {

    @Value("${app.auth.enabled:false}")
    private boolean switchOn;

    @Autowired(required = false)
    private WebAuthConfig webAuthConfigBean;

    @BeforeEach
    void switchOn_andTheGateIsReallyLoaded() {
        assertThat(switchOn)
                .as("⚠️ 本层前提是 app.auth.enabled=true。若为 false，说明 @TestPropertySource 没生效 —— "
                        + "下面每条 401 断言都会失败，但**原因在这里**，先修配置")
                .isTrue();
        assertThat(webAuthConfigBean)
                .as("WebAuthConfig 没进上下文 ⇒ 拦截器压根没注册，401 断言会退化成"
                        + "「期望 401 实得 400」")
                .isNotNull();
    }

    /**
     * 白名单全表扫描：12 条逐条直调 {@code preHandle}，**无会话**也必须放行。
     *
     * <p>三条断言各堵一个口子：① 条数 == 12（防有人删条却不改注释）；② 逐条放行；
     * ③ <b>反断言</b> —— 表外路径必须被拦（否则「全都放行」也能让 ② 全绿）。
     */
    @Test
    void whitelist_allEntries_passTheGate() throws Exception {
        assertThat(WebAuthConfig.AUTH_WHITELIST)
                .as("白名单条数 = 业务 8 + 基础设施 4。改这个数必须同时改 WebAuthConfig 的注释")
                .hasSize(12);

        AuthInterceptor gate = new AuthInterceptor(true, WebAuthConfig.AUTH_WHITELIST);

        for (String path : WebAuthConfig.AUTH_WHITELIST) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(gate.preHandle(new MockHttpServletRequest("POST", path), response, new Object()))
                    .as("白名单条目 %s 被闸拦下了（status=%s）", path, response.getStatus())
                    .isTrue();
        }

        for (String path : AuthGateSmokeTest.REACHABLE_BUT_NOT_PUBLIC) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(gate.preHandle(new MockHttpServletRequest("POST", path), response, new Object()))
                    .as("%s 未登录可达，但**刻意不是公开面** —— 它被放行说明白名单被放宽了"
                            + "（要么真有裁决，要么把「可达」误当成了「公开」）", path)
                    .isFalse();
            assertThat(response.getStatus())
                    .as("拦截器拒绝时必须写 401")
                    .isEqualTo(401);
        }
    }

    /**
     * 未登录打非白名单端点 ⇒ 401 + **逐字节**响应体。
     *
     * <p>顺带钉住 charset：{@code msg} 是中文，不显式声明 UTF-8 时容器可能按平台默认编码输出
     * （本机 GBK），前端 {@code res.json()} 会得到乱码 —— 且只在部分机器复现。
     */
    @Test
    void goodsAdd_withoutSession_is401_withPinnedBody() throws Exception {
        MvcResult result = mockMvc.perform(post("/goods/goodsAdd"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(AuthGateSmokeTest.BODY_NOT_LOGGED_IN))
                .andReturn();
        assertThat(result.getResponse().getCharacterEncoding())
                .as("401 响应体含中文，charset 必须是 UTF-8")
                .isEqualToIgnoringCase("UTF-8");
    }

    /** 同上的第二个端点（message 域）—— 证明闸是**全局**注册，不是挂在某一个 Controller 上。 */
    @Test
    void messageConversations_withoutSession_is401() throws Exception {
        mockMvc.perform(post("/message/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(AuthGateSmokeTest.BODY_NOT_LOGGED_IN));
    }

    /**
     * ⚠️ <b>本闸最容易被放宽的一处</b>：请求**带着一个会话**，但会话里没有
     * {@link AuthInterceptor#SESSION_USER_ID} —— 必须仍判未登录。
     *
     * <p>若把判据改成「有会话即可」，本用例红。这不是吹毛求疵：会话在 Servlet 语义里只是一块
     * 服务端存储，任何一处 {@code getSession()} 都能创建它 ⇒ 「有会话」等于把登录态判定权
     * 交给「谁碰过 session」。
     */
    @Test
    void withSessionButNoUserId_is401() throws Exception {
        mockMvc.perform(post("/goods/goodsAdd").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(AuthGateSmokeTest.BODY_NOT_LOGGED_IN));
    }

    /**
     * 会话里带了 {@code userId} ⇒ 放行。判据取 400（缺参）⇒ 请求**确实到了 Controller 层**。
     *
     * <p>本条同时是 {@link #withSessionButNoUserId_is401} 的阳性对照：只有阴性例而没有阳性例时，
     * 「一律拒绝」也能让阴性例绿。
     */
    @Test
    void withSessionAndUserId_passesTheGate() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthInterceptor.SESSION_USER_ID, "SMOKE-USER-1");

        mockMvc.perform(post("/goods/goodsAdd").session(session))
                .andExpect(status().isBadRequest());
    }

    /**
     * 白名单里的 {@code goodstype} 是 **GET/POST 双形态**（同一个 {@code @RequestMapping}），
     * 而白名单判据是路径、与 HTTP 方法无关 ⇒ 两个方法都必须穿过去。
     *
     * <p>这条不是多余的：前端两种都打（首页 {@code HomePage.vue:153} 用 POST、上架页
     * {@code GoodsAdd.vue:138} 用 GET），只放行一种就是隐性故障。
     */
    @Test
    void whitelisted_goodstype_passesThrough_bothGetAndPost() throws Exception {
        mockMvc.perform(post("/goods/goodstype")).andExpect(status().isOk());
        mockMvc.perform(get("/goods/goodstype")).andExpect(status().isOk());
    }

    /**
     * 白名单里的登录链端点：登录页在**未登录**状态下就打它（{@code LoginPage.vue:134}）
     * ⇒ 白名单少了它，闸一开就再也登不进去。
     *
     * <p>响应体断言 {@code "0"} 是**精确值**：无桩时 UserDao 查不到角色 ⇒
     * {@code UserController.loginSelect} 返回 0（既有行为，见 {@code LoginSelectSmokeTest}）。
     */
    @Test
    void whitelisted_loginSelect_passesThrough() throws Exception {
        mockMvc.perform(post("/user/loginSelect")
                        .param("username", "SMOKE-USER")
                        .param("password", "SMOKE-PWD"))
                .andExpect(status().isOk())
                .andExpect(content().string("0"));
    }

    /**
     * 白名单里的静态资源：商品图与头像在未登录的首页也要显示
     * （{@code /img/**} 的映射坏掉时 {@code ImgMappingSmokeTest} 会先红）。
     */
    @Test
    void whitelisted_img_passesThrough() throws Exception {
        MvcResult served = mockMvc.perform(get("/img/" + AuthGateSmokeTest.IMG_FIXTURE))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isNotEmpty();
    }
}
