package com.minke.mallmanagement.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.shared.web.AuthInterceptor;
import com.minke.mallmanagement.shared.web.WebAuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话建立与登出（鉴权笔 **2**）的 13 条矩阵 —— 被测判定分落三处：
 * {@code UserController.loginSelect}（写会话）、{@code /user/me}（读会话）、
 * {@code /user/logout}（销会话），三者的读写都只经 {@code AuthSession} 一处。
 *
 * <h2>【为什么一个文件里有两个测试类】</h2>
 * 与 {@link AuthGateSmokeTest} 同一个理由（那里写得更细）：{@code app.auth.enabled} 在
 * {@code @TestPropertySource} 里**无法按用例改**，而「开关 OFF 对照」需要两份不同 property
 * 的上下文；{@code @Nested} 内部类会让 {@code @MockitoBean} 的替身不为内部类 reset。
 * 故同一文件两个**顶层**包级类：{@link AuthSessionSmokeTest}（profile 钉 OFF，**10** 条）
 * + {@link AuthSessionSwitchOnTest}（{@code app.auth.enabled=true}，**3** 条）。
 *
 * <p>⚠️ 副作用（已知，可接受，与 {@code AuthGateSmokeTest} 同款）：第二个类名不等于本文件名，
 * 故 {@code ./mvnw -B test -Dtest=AuthSessionSmokeTest} <b>只会跑 10 条</b>。跑全 13 条须
 * {@code -Dtest=AuthSessionSmokeTest,AuthSessionSwitchOnTest}，或**不带 {@code -Dtest}**。
 *
 * <h2>【401 响应体：为什么必须**逐字节**比，而不是只比状态码】</h2>
 * 本笔有**两个**地方会答 401：{@code AuthInterceptor}（开关打开时挡在前面）与
 * {@code UserController.me}（开关关着、但端点自身要求登录）。二者**必须是同一串字节** ——
 * 否则「前端只需认 401」这条简化就会破，客户端得先分辨这个 401 是谁发的。
 *
 * <p>⚠️ 计划里原本打算用 {@code Result.fail("未登录")} 产出端点侧那一份，**实跑前实测推翻了**：
 * {@code Result} 继承 {@code HashMap}，而这两个键的桶号实测为
 * {@code success → 6}、{@code msg → 0} ⇒ 迭代序是 {@code msg} 在前，序列化得到
 * {@code {"msg":"未登录","success":false}}，与闸的字面量<b>不同字节</b>。故改成在
 * {@code AuthInterceptor} 上抽一个 {@code public} 常量
 * （{@link AuthInterceptor#BODY_NOT_LOGGED_IN}）给两处共用 —— 字节相同**由构造保证**。
 *
 * <p>本文件的断言取**字节**（{@code content().bytes(...)}）而不是字符串：字符串比较要经过
 * 一次「按响应 charset 解码」，而 charset 本身是这族缺陷的常见来源（中文字面量 + 平台默认
 * GBK，见 {@code AuthInterceptor.writeNotLoggedIn} 的注释）。
 * 期望值取自 {@link AuthGateSmokeTest#BODY_NOT_LOGGED_IN} —— 那是**独立的第二份副本**
 * （不是引用生产常量），它的存在使「生产常量被改坏」会被另一处先发现。
 *
 * <h2>【突变对照：四条误改各自会弄红谁（2026-10-04 实跑，非推断）】</h2>
 * 判据取**套件整体**：每条突变都跑**全量** {@code ./mvnw -B test}（209 条），
 * 记的是「全套里红了哪几条」，不是「我预期的那个用例红没红」。
 * <ul>
 *   <li><b>M1</b> 删掉 {@code loginSelect} 里的写会话 ⇒ **2** 红，恰好是那 2 条
 *       {@code loginSelect_withXxxCredentials_writesXxxIdIntoSession}；</li>
 *   <li><b>M2</b> 让 {@code /user/me} 不读会话（{@code userId} 恒为 {@code null}）⇒ **3** 红：
 *       端点侧 2 条阳性用例（{@code me_withSession_…}、{@code me_whenPublicInfoIsNull_…}，
 *       后者期望 200 实得 401）+ 开关层 1 条阳性用例；</li>
 *   <li><b>M3</b> {@code logout} 用 {@code removeAttribute} 代替 {@code invalidate()} ⇒
 *       **1** 红：{@code logout_reallyInvalidatesTheSession}（判据是 {@code isInvalid()}，
 *       不是「属性没了」—— 这正是本笔选 {@code invalidate} 的可观测后果）；</li>
 *   <li><b>M4</b> 写会话时存**登录名**而不是**业务号** ⇒ **2** 红，与 M1 同一对用例
 *       （同一条断言的第二半：值不符）。</li>
 * </ul>
 * ⚠️ M1 与 M4 弄红的是**同一对**用例，这是刻意的：那 2 条各自钉住「会话被建出来」与
 * 「会话里的值是业务号」两件事，缺任何一件事它们都红。**四条突变没有一个能被现有断言静默吞掉。**
 */
class AuthSessionSmokeTest extends AbstractSmokeTest {

    /** 桩用的凭据。真假无关 —— {@code jdbcTemplate} 是替身，不会真查库。 */
    static final String SMOKE_USER = "smoke_user";
    static final String SMOKE_PWD = "smoke_password";

    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    @Autowired(required = false)
    private WebAuthConfig webAuthConfigBean;

    @BeforeEach
    void switchOff_andTheGateIsReallyLoaded() {
        assertThat(authEnabled)
                .as("⚠️ 本层前提是 app.auth.enabled=false —— 来源是 application-smoke.properties 的"
                        + "**显式 pin**（翻开关笔起）。若为 true，说明 pin 被删了")
                .isFalse();
        assertThat(webAuthConfigBean)
                .as("WebAuthConfig 没进上下文 ⇒ 本层「未登录也打得到 /user/me」是空命题（闸压根不在）")
                .isNotNull();
    }

    // ==================== 桩工具 ====================

    /**
     * 桩：{@code loginSelect} 的凭据查询返回给定 {@code role}。
     *
     * <p>走的是 {@code JdbcClient} 的 {@code query(Integer.class).single()} ⇒ 落到
     * {@code JdbcTemplate.query(String, RowMapper, Object...)}，与
     * {@link LoginSelectSmokeTest} 那条 stub 同一条重载。
     */
    private void stubRole(int role) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(role));
    }

    /**
     * 桩：业务号查询（{@code UserDao.selectUserByUsername}）返回一行。
     *
     * <p>⚠️ 这条 stub 打的是 {@code queryForMap(String, Object...)} —— 已核实（javap
     * spring-jdbc 6.2.6 {@code DefaultJdbcClient$IndexedParamResultQuerySpec.singleRow}）：
     * {@code query().singleRow()} 的实现就是 {@code classicOps.queryForMap(sql, params.toArray())}。
     * 若这条 stub 不中，Mockito 返回 null ⇒ {@code businessId} 返回 null ⇒ **会话不会被写**
     * ⇒ 下面「登录成功写会话」两条当场红。**红在值上，不红在静默里**。
     */
    private void stubUserRow(String customerid, String merchantid) {
        Map<String, Object> row = new HashMap<>();
        row.put("customerid", customerid);
        row.put("merchantid", merchantid);
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenReturn(row);
    }

    /**
     * 桩：P2.6 第二跳「user 表整行」（{@code UserDao.selectUserByUsername} → {@code queryForMap}）。
     *
     * <p>⚠️ <b>必须是**可变** Map</b>（{@code HashMap}，不能图省事写 {@code Map.of}）：
     * {@code profileOf} 会往这一行里 {@code put("image", …)}；不可变 Map 抛
     * {@code UnsupportedOperationException}，而这个 put 恰在 profileOf **自己的 catch 里**
     * ⇒ 图片被**静默丢弃**、{@code image} 键整个消失（本条第一次跑红就是踩的这个坑，
     * 生产里 {@code queryForMap} 给的是可变行，故只有测试会踩）。
     *
     * <p>⚠️ {@code static} 是**必需**的：同文件里 {@link AuthSessionSwitchOnTest}（另一个**顶层**
     * 类，不继承本类）也要用 —— 故由它 {@code AuthSessionSmokeTest.userRow(...)} 显式调用。
     * 放基类 {@code AbstractSmokeTest} 也能过，但那是 5 个 smoke 类共用的切片接线文件，
     * 为一条用例的桩去改它不划算（见 progress.md §5-AC 的「基类不改」裁定）。
     */
    static Map<String, Object> userRow(String idColumn, String id, String usertype, String gender) {
        Map<String, Object> row = new HashMap<>();
        row.put(idColumn, id);
        row.put("username", SMOKE_USER);
        row.put("usertype", usertype);
        row.put("gender", gender);
        return row;
    }

    /** 打一次 loginSelect，返回整个请求（用于从 request 里取会话）。 */
    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/user/loginSelect")
                        .param("username", SMOKE_USER)
                        .param("password", SMOKE_PWD))
                .andReturn();
    }

    /** 造一个「已登录」的会话（客户 C-1）。角色留空（老形状）。 */
    private static MockHttpSession loggedInSession(String businessId) {
        return loggedInSession(businessId, null);
    }

    /**
     * 同上，但**带上角色** —— 多账户隔离笔（2026-10-06）之后才有意义：
     * {@code /user/me} 会把角色一起答出来，而 {@code role=3} 那一档还改变它的出口形状。
     * 判据取 {@code Integer}（不是 {@code int}）：{@code SESSION_ROLE} 可以合法地不存在。
     */
    private static MockHttpSession loggedInSession(String businessId, Integer role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthInterceptor.SESSION_USER_ID, businessId);
        session.setAttribute(AuthInterceptor.SESSION_ROLE, role);
        return session;
    }

    // ==================== 一、登录写会话（4 条）====================

    /**
     * 客户登录（{@code role=1}）⇒ 会话里是 {@code customerid}。
     *
     * <p>三条断言各堵一个口子：① 会话**被建出来了**；② 会话里**有** {@code userId} 属性
     * （闸的判据，缺了等于没登录）；③ 这个属性的值是**业务号**而不是登录名 —— 少了它，
     * 「写错值」这种改法会静默通过（登录名也非空、也能过闸）。
     */
    @Test
    void loginSelect_withCustomerCredentials_writesCustomerIdIntoSession() throws Exception {
        stubRole(1);
        stubUserRow("C-1", null);

        MvcResult result = login();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("1");

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("认证成功必须建立会话").isNotNull();
        assertThat(session.getAttribute(AuthInterceptor.SESSION_USER_ID))
                .as("会话里存的必须是**业务号**（客户 = customerid），不是登录名")
                .isEqualTo("C-1");
        assertThat(session.getAttribute(AuthInterceptor.SESSION_ROLE))
                .as("多账户隔离笔（2026-10-06）起会话里还要有**角色**（loginSelect 的返回值原样带入）"
                        + "—— 它是 POST /user/token 里 role 的唯一来源")
                .isEqualTo(1);
    }

    /** 商家登录（{@code role=2}）⇒ 会话里是 {@code merchantid}（同一列位的另一支）。 */
    @Test
    void loginSelect_withMerchantCredentials_writesMerchantIdIntoSession() throws Exception {
        stubRole(2);
        stubUserRow(null, "M-9");

        MvcResult result = login();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("认证成功必须建立会话").isNotNull();
        assertThat(session.getAttribute(AuthInterceptor.SESSION_USER_ID))
                .as("会话里存的必须是**业务号**（商家 = merchantid）")
                .isEqualTo("M-9");
        assertThat(session.getAttribute(AuthInterceptor.SESSION_ROLE))
                .as("同上一支：角色一并写进会话（这一支是 2）")
                .isEqualTo(2);
    }

    /**
     * ⚠️ 响应体**仍是裸 int**（例外 1/5，见 {@code Result} 类注释）。
     *
     * <p>本笔给 {@code loginSelect} 加了一个**基础设施形参**（{@code HttpServletRequest}），
     * 这是它唯一的签名改动；这条断言钉住「HTTP 契约一字未动」—— A3 的 {@code A-S1-3}
     * 断言 {@code "1".equals(body.trim())} 逐字，前端 {@code res.text()} 后比 {@code "0"/"1"/"2"}。
     */
    @Test
    void loginSelect_responseBodyIsStillBareInt_notJson() throws Exception {
        stubRole(2);
        stubUserRow(null, "M-9");

        MvcResult result = login();
        String body = result.getResponse().getContentAsString();
        assertThat(body).as("必须是裸 '2'（A3 A-S1-3 与前端逐字依赖）").isEqualTo("2");
        assertThat(body).as("不能是 JSON —— 出现花括号即说明被包成了响应体").doesNotContain("{");
    }

    /**
     * 认证**失败**（{@code role=0}）⇒ 不写会话、**而且不产生会话**。
     *
     * <p>⚠️ 这是形参取 {@code HttpServletRequest} 而**不是** {@code HttpSession} 的直接理由：
     * {@code HttpSession} 由 MVC 注入时**无条件创建**会话，于是每一次失败的登录尝试
     * 都会在服务端留下一块存储。这里连「查业务号」这一步都不该发生，故同时断言
     * {@code queryForMap} **一次都没被调用**。
     */
    @Test
    void loginSelect_withUnknownCredentials_createsNoSessionAndDoesNotLookUpBusinessId() throws Exception {
        // 0 行 ⇒ .single() 抛 EmptyResultDataAccessException ⇒ loginSelect 的 catch 翻成 0
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        MvcResult result = login();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("0");
        assertThat(result.getRequest().getSession(false))
                .as("登录失败不应建立会话")
                .isNull();
        verify(jdbcTemplate, never()).queryForMap(anyString(), any(Object[].class));
    }

    /**
     * 凭据有效、但 {@code user} 行里业务号列为 NULL ⇒ 写不进会话，且**不建空会话**。
     *
     * <p>{@code user.customerid} 在库里可空，而「会话里有一个 null 的 userId」比
     * 「没有会话」更坏：闸判的是 {@code getAttribute != null}，故 null 属性等于未登录；
     * 但它会让「已经建过会话」这个事实为真。故这里的判据是**没有会话**。
     */
    @Test
    void loginSelect_whenBusinessIdColumnIsNull_createsNoSession() throws Exception {
        stubRole(1);
        stubUserRow(null, null);

        MvcResult result = login();
        assertThat(result.getResponse().getContentAsString())
                .as("登录本身仍是成功的 —— 只是没有可写进会话的身份")
                .isEqualTo("1");
        assertThat(result.getRequest().getSession(false))
                .as("业务号为 NULL 时不应建立会话")
                .isNull();
    }

    // ==================== 二、/user/me 读会话（3 条）====================

    /**
     * 有会话 ⇒ 按会话返回**完整资料**，**顶层平铺**（键集 = {@code user} 表整行 + {@code image}
     * + {@code success} + {@code role}）。
     *
     * <p>⚠️ 键必须**平铺**而不是塞进 {@code data}:前端全部按顶层键取值（见 {@code Result} 类注释），
     * 故这里逐键断言，尤其 {@code customerid} —— 前端要靠它去调商家端接口。本条**兼钉映射一致性**：
     * {@code /user/me} 去掉 {@code Result.ok} 加的 {@code success} 与身份侧加的 {@code role} 后，
     * 须逐键等于 {@code /user/getUserInfo} 的响应。
     */
    @Test
    void me_withSession_returnsIdentityFlattened() throws Exception {
        // 两跳都要桩：① publicInfo（业务号 → 用户名）② user 表整行（用户名 → 资料行）
        when(userCacheService.publicInfo("C-1"))
                .thenReturn(Map.<String, Object>of("username", "smoke_user"));
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(userRow("customerid", "C-1", "客户", "男"));
        // 图片查询与凭据校验（role）共用 query(String, RowMapper, Object...) 这一个重载 ⇒ 用 SQL 子串把
        // 两条**互斥**地钉住（role 那条 SQL 含 `login`、图片这条含 `customerimage`），不靠多桩的优先级。
        when(jdbcTemplate.query(contains("login"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(1));
        when(jdbcTemplate.query(contains("customerimage"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("avatar.png"));

        MvcResult meResult = mockMvc.perform(post("/user/me").session(loggedInSession("C-1", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.customerid").value("C-1"))
                .andExpect(jsonPath("$.username").value("smoke_user"))
                .andExpect(jsonPath("$.usertype").value("客户"))
                .andExpect(jsonPath("$.role").value(1))
                .andReturn();
        // ⚠️ P2.6 映射一致性（本条的第二个钉子）：同一用户下 /user/me 去掉 Result.ok 补的 success 后，
        //    必须**逐键等于** /user/getUserInfo 的响应 —— 这正是三个前端调用点「只换地址、不改模板」的依据。
        //    ⚠️ 多账户隔离笔后 `role` 是 /user/me **独有**的键（loginSelect 的返回值，不是 DAO 行的列）
        //    ⇒ 逐键比较前必须连同 success 一起摘掉这两个「信封键」。摘的是**本笔明知多出来的**键，
        //    不是「比不过就删」—— 若哪天 DAO 行里真长出 role 列，这条会因 getUserInfo 侧也有它而继续绿，
        //    故另有一条 jsonPath($.role) 在前面钉值。
        String me = meResult.getResponse().getContentAsString();
        assertThat(me).as("两跳的第二跳必须走通（image 来自 userDao.selectCustomerImage）").contains("avatar.png");
        String cred = mockMvc.perform(post("/user/getUserInfo")
                        .param("username", "smoke_user").param("password", "pw"))
                .andReturn().getResponse().getContentAsString();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> mePayload = mapper.readValue(me, Map.class);
        mePayload.remove("success");
        mePayload.remove("role");
        assertThat(mePayload).as("/user/me 去 success 与 role 后必须逐键等于 /user/getUserInfo 的响应")
                .isEqualTo(mapper.readValue(cred, Map.class));
    }

    /**
     * ⚠️ 无会话 ⇒ **401 + 与闸逐字节相同的响应体**。本条是端点侧 401 的字节钉。
     *
     * <p>顺带钉住 charset：响应体含中文，不声明 charset 时容器可能按平台默认编码输出
     * （本机 GBK），前端 {@code res.json()} 会得到乱码 —— 且只在部分机器复现。
     */
    @Test
    void me_withoutSession_is401_withGateByteIdenticalBody() throws Exception {
        MvcResult result = mockMvc.perform(post("/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().bytes(
                        AuthGateSmokeTest.BODY_NOT_LOGGED_IN.getBytes(StandardCharsets.UTF_8)))
                .andReturn();

        assertThat(result.getResponse().getCharacterEncoding())
                .as("401 响应体含中文，charset 必须是 UTF-8")
                .isEqualToIgnoringCase("UTF-8");
        assertThat(result.getRequest().getSession(false))
                .as("打 /user/me 不得给匿名请求发一个会话（getSession(false) 纪律）")
                .isNull();
    }

    /**
     * 会话有效、但业务号在库里查不到 ⇒ **HTTP 200 + {@code {success:false,msg:"用户不存在"}}**。
     *
     * <p>⚠️ 这一条真正要钉的是**形状**：与 {@code getUserInfo} 失败时的「200 **空体**」不同，
     * 本端点答的是**可解析**的 JSON。若有人把它改成「查不到就返回 401」，前端会把它与
     * 「未登录」混为一谈（会话有效却被登出）。
     */
    @Test
    void me_whenPublicInfoIsNull_is200WithParsableFailBody_notEmptyBody() throws Exception {
        when(userCacheService.publicInfo("GONE")).thenReturn(null);

        MvcResult result = mockMvc.perform(post("/user/me").session(loggedInSession("GONE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("用户不存在"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("必须是**非空**的可解析 JSON（对照 getUserInfo 的 200 空体）")
                .contains("用户不存在");
    }

    // ==================== 三、/user/logout 销会话（2 条）====================

    /**
     * 退出登录**真的** {@code invalidate()} 会话 —— 判据是 {@code isInvalid()}，不是
     * 「属性被清了」。
     *
     * <p>⚠️ 为什么不用「再打一次 {@code /user/me}」当判据：{@code MockHttpSession} 被
     * invalidate 之后，**任何属性访问都会抛 {@code IllegalStateException}**，把它再塞进
     * {@code .session(...)} 只会得到 500 —— 那是测试脚手架的噪声，不是被测行为。
     * 客户端的真实形状是「cookie 没了」，等价于**不带会话**再打一次，故本条最后补了那一发。
     */
    @Test
    void logout_reallyInvalidatesTheSession() throws Exception {
        MockHttpSession session = loggedInSession("C-1");

        mockMvc.perform(post("/user/logout").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("已退出"));

        assertThat(session.isInvalid())
                .as("logout 必须 invalidate 会话本身 —— 只 removeAttribute 会让它仍然存在")
                .isTrue();

        mockMvc.perform(post("/user/me"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 无会话时 {@code logout} 也答成功（**幂等**），且**不创建**会话 ——
     * 「先 getSession() 再 invalidate()」会让每个登出请求 mint 一个空会话。
     */
    @Test
    void logout_withoutSession_isIdempotentAndCreatesNoSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/user/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("已退出"))
                .andReturn();

        assertThat(result.getRequest().getSession(false))
                .as("无会话时 logout 不得创建会话 —— 形参若是 HttpSession 这里会非 null")
                .isNull();
    }
}

/**
 * 开关打开时的 3 条。与 {@link AuthSessionSmokeTest} 同文件（理由见该类注释），
 * 差别只有一处：类上的 {@code @TestPropertySource} 把 {@code app.auth.enabled} 改成 {@code true}。
 *
 * <p>这一层测的是**两个 401 的合并**：端点侧（{@link AuthSessionSmokeTest} 里那 3 条）
 * 与闸侧（本类）必须答同一串字节。开关打开后，{@code /user/me} 与 {@code /user/logout}
 * 都是**非白名单**端点 ⇒ 无会话时请求**到不了 Controller**，401 由闸发出 ——
 * 那正是「客户端分不出谁发的」这一承诺的实证。
 */
@TestPropertySource(properties = "app.auth.enabled=true")
class AuthSessionSwitchOnTest extends AbstractSmokeTest {

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
                .as("WebAuthConfig 没进上下文 ⇒ 拦截器压根没注册，401 断言会退化成「期望 401 实得 200」")
                .isNotNull();
    }

    /**
     * 会话里带了 {@code userId} ⇒ 穿过闸，{@code /user/me} 正常回答身份。
     *
     * <p>本条是 {@link #me_withoutSession_is401_withGateProducedBody} 的阳性对照：
     * 只有阴性例而没有阳性例时，「一律 401」也能让阴性例绿。
     */
    @Test
    void me_withSession_passesGateAndReturnsIdentity() throws Exception {
        when(userCacheService.publicInfo("M-9")).thenReturn(Map.<String, Object>of(
                "merchantid", "M-9",
                "username", "smoke_user",
                "usertype", "商家",
                "gender", "女"));
        // 第二跳：publicInfo 只给到 username，P2.6 起还要查 user 表整行（否则应答里没有 merchantid）
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(AuthSessionSmokeTest.userRow("merchantid", "M-9", "商家", "女"));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthInterceptor.SESSION_USER_ID, "M-9");

        mockMvc.perform(post("/user/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.merchantid").value("M-9"));
    }

    /**
     * 无会话 ⇒ 401，响应体与{@link AuthSessionSmokeTest#me_withoutSession_is401_withGateByteIdenticalBody
     * 端点侧那一发}**同一串字节**（两处都以 {@link AuthGateSmokeTest#BODY_NOT_LOGGED_IN} 为期望值）。
     *
     * <p>额外断言 {@code publicInfo} **一次都没被调用**：这才证明这个 401 是**闸**发的
     * （请求没到 Controller），而不是端点自己判出来的 —— 否则「两处 401 相同」这件事
     * 可能只是碰巧，本类就退化成端点侧那 3 条的重复。
     */
    @Test
    void me_withoutSession_is401_withGateProducedBody() throws Exception {
        mockMvc.perform(post("/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().bytes(
                        AuthGateSmokeTest.BODY_NOT_LOGGED_IN.getBytes(StandardCharsets.UTF_8)));

        verify(userCacheService, never()).publicInfo(anyString());
    }

    /**
     * {@code /user/logout} **刻意不在白名单**里 —— 两条断言合起来才有意义：
     * ① 表里确实没有它（防「有人顺手加进去」）；② 行为上无会话时被闸拒（防「表里有但判据不生效」）。
     *
     * <p>⚠️ 反过来说：本端点**不需要**白名单豁免 —— 无会话时它本来就是 no-op。
     * 放它进去只会给未来留一个「不需要身份就能触达」的写面。
     */
    @Test
    void logout_isNotWhitelisted_andIsRejectedByTheGate() throws Exception {
        assertThat(WebAuthConfig.AUTH_WHITELIST)
                .as("logout / me 必须不在白名单 —— 加了它等于把「销会话」变成匿名可达")
                .doesNotContain("/user/logout", "/user/me");

        mockMvc.perform(post("/user/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().bytes(
                        AuthGateSmokeTest.BODY_NOT_LOGGED_IN.getBytes(StandardCharsets.UTF_8)));
    }
}
