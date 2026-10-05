package com.minke.mallmanagement.smoke;

import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.AuthTokenStore;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.WebAuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入参身份校验（多账户隔离笔，2026-10-06）的**三档验收** —— 13 个受保护端点各跑一遍，
 * 判据只有 {@link IdentityGuard#requireSelfOrAdmin} 一处。
 *
 * <h2>【三档 = 本笔的验收口径】</h2>
 * <ol>
 *   <li>入参 == 自己的业务号 ⇒ 放行（**所有角色**）；</li>
 *   <li>入参 == 别人的业务号 + 调用者是管理员（{@code role=3}）⇒ 放行（管理员改别人是**职责**）；</li>
 *   <li>入参 == 别人的业务号 + 非管理员 ⇒ **403**（不是 401 —— 有身份，只是无权）。</li>
 * </ol>
 * 三档各一条 {@code @Test}，每条**遍历全表**并把 13 个状态码收进一个 Map 一起断言 ——
 * 逐端点写 39 个用例只会让人看不出「表里少了一个端点」，而 {@code hasSize(13)} 会让它当场红。
 *
 * <h2>【为什么「放行」档的判据是「不是 403 / 不是 401」而不是某个具体状态码】</h2>
 * 因为这三档问的**只是守卫**：放行档下各端点的业务实现**本来就跑不通**（真 bean 的 DAO 打在替身
 * {@code JdbcTemplate} 上，未 stub 的查询返回 {@code null} ⇒ 遍历 null 就 NPE）。这些状态码
 * 是各端点**自己的**契约，由它们各自的 smoke 类与 A3 守；本类把它们当噪声容忍，只钉死两件事：
 * 403（守卫拒了）与 401（闸没认出票）都**不许**出现。
 * ⚠️ 代价写清：{@code THREW}（请求在业务代码里炸）在放行档下算**通过** —— 它恰恰是
 * 「守卫放行了、控制流进了业务代码」的证据（守卫是方法体第一行，它一拦，后面一行都不执行）。
 * 反过来说，**拦下那一档不许出现 {@code THREW}**，断言写的是 {@code isEqualTo(403)}。
 *
 * <h2>【票怎么造】</h2>
 * 用 {@link AuthTokenStore#mint} 直接铸（不走 {@code POST /user/token}）：管理员票**只**能这样造
 * —— 那条端点刻意不认管理员 cookie（见 {@code AuthTokenSmokeTest}）。
 * 每铸一张用完即 {@code revoke}，加上 {@code @BeforeEach} 清表，避免污染别的测试类（表是 static）。
 *
 * <h2>【为什么钉 {@code app.upload-dir}】</h2>
 * {@code goodsAdd} 的放行档会真的走到落盘那一步，而 smoke profile 的
 * {@code app.upload-dir=src/main/resources/static/img/} 指向**仓库内的真实目录** ⇒
 * 不钉的话这个用例会往工作树里写图片文件（本仓已有一个来历不明的 untracked 图片，
 * 正是这类用例留下的）。钉成不可写路径后，落盘必败、返回失败体，而本类只关心 403 与否。
 */
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "app.upload-dir=pom.xml/nope-img/"
})
class IdentityGuardSmokeTest extends AbstractSmokeTest {

    /** 本笔受保护的端点共 13 个（4 goods + 5 order + 4 message）—— 见 {@code progress.md §5-AF}。 */
    private static final int PROTECTED_ENDPOINTS = 13;

    /** 两个**刻意不保护**的端点：它们的 {@code MerchantId} 是「被浏览的商家」，不是调用者。 */
    private static final String[] BROWSE_SIDE_ENDPOINTS = {"/goods/productCodePrice", "/goods/GoodsProductCodeSelect"};

    private static final String CUSTOMER = "C-1";
    private static final String OTHER_CUSTOMER = "C-2";
    private static final String MERCHANT = "M-1";
    private static final String OTHER_MERCHANT = "M-2";

    /** 对端方（不进判据）：订单里的商家、好友里的 {@code friendId}。 */
    private static final String COUNTERPARTY_MERCHANT = "M-9";
    private static final String COUNTERPARTY_CUSTOMER = "C-9";

    private static final String ADMIN = "ADMIN-1";

    /** 见类注释：请求在业务代码里炸了（放行档容忍、拦下档不容忍）。 */
    private static final int THREW = -1;

    /**
     * 一个受保护端点。{@code idParam} = 代表**调用者自己**的那个入参；
     * {@code otherParams} = 其余入参（含**对端方**，如订单的 {@code MerchantId}、好友的 {@code friendId}）。
     */
    private record Endpoint(String name, String path, String idParam, Map<String, String> otherParams,
                            String selfId, String otherId, int selfRole, boolean multipart) {
    }

    /** ⚠️ 加端点/改入参必须**同时**改这里与 {@code IdentityGuard} 的调用点（两份清单不许漂移）。 */
    private static final List<Endpoint> ENDPOINTS = List.of(
            // ---- goods 域 4 个 ----
            new Endpoint("goodsAdd", "/goods/goodsAdd", "MerchantId",
                    Map.of("GoodName", "smoke-goods", "Price", "1.0", "GoodType", "smoke-type",
                            "ProductCode", "SMOKE-P1", "GoodTypeNew", "0"),
                    MERCHANT, OTHER_MERCHANT, 2, true),
            new Endpoint("goodsSelect", "/goods/goodsSelect", "MerchantId",
                    Map.of(), MERCHANT, OTHER_MERCHANT, 2, false),
            new Endpoint("goodsDe", "/goods/goodsDe", "MerchantId",
                    Map.of("GoodName", "smoke-goods", "ProductCode", "SMOKE-P1"),
                    MERCHANT, OTHER_MERCHANT, 2, false),
            new Endpoint("goodsUp", "/goods/goodsUp", "MerchantId",
                    Map.of("GoodName", "smoke-goods", "Price", "1.0", "GoodType", "smoke-type",
                            "ProductCode", "SMOKE-P1"),
                    MERCHANT, OTHER_MERCHANT, 2, false),
            // ---- order 域 5 个（⚠️ orderAdd 只判 CustomerId：MerchantId 是对端）----
            new Endpoint("orderAdd", "/order/orderAdd", "CustomerId",
                    Map.of("MerchantId", COUNTERPARTY_MERCHANT, "OrderId", "O-1", "Price", "1.0",
                            "ProductInformation", "smoke", "Images", "smoke.png",
                            "PayIf", "0", "DeliveryIf", "0", "ReceiveIf", "0"),
                    CUSTOMER, OTHER_CUSTOMER, 1, false),
            new Endpoint("orderSelect", "/order/orderSelect", "customerId",
                    Map.of(), CUSTOMER, OTHER_CUSTOMER, 1, false),
            new Endpoint("orderDeliveryIfSelect", "/order/orderDeliveryIfSelect", "merchantId",
                    Map.of(), MERCHANT, OTHER_MERCHANT, 2, false),
            new Endpoint("orderDeliverySelect", "/order/orderDeliverySelect", "merchantId",
                    Map.of(), MERCHANT, OTHER_MERCHANT, 2, false),
            new Endpoint("historicalOrdersSelect", "/order/historicalOrdersSelect", "customerId",
                    Map.of(), CUSTOMER, OTHER_CUSTOMER, 1, false),
            // ---- message 域 4 个（⚠️ 只判 customerId：friendId 是对端）----
            new Endpoint("friendAdd", "/message/friendAdd", "customerId",
                    Map.of("friendId", COUNTERPARTY_CUSTOMER), CUSTOMER, OTHER_CUSTOMER, 1, false),
            new Endpoint("friendUp", "/message/friendUp", "customerId",
                    Map.of("friendId", COUNTERPARTY_CUSTOMER), CUSTOMER, OTHER_CUSTOMER, 1, false),
            new Endpoint("friendDe", "/message/friendDe", "customerId",
                    Map.of("friendId", COUNTERPARTY_CUSTOMER), CUSTOMER, OTHER_CUSTOMER, 1, false),
            new Endpoint("friendSelect", "/message/friendSelect", "customerId",
                    Map.of(), CUSTOMER, OTHER_CUSTOMER, 1, false));

    /** 本次请求要带的票（由 {@link #sweep} 每端点换一张）。基类唯一出口 {@code request(...)} 读它。 */
    private String token;

    @BeforeEach
    void clearTheSharedTokenTable() {
        AuthTokenStore.clearForTest();
    }

    /**
     * 走基类的唯一出口附加凭据 —— 这就是基类里那个「预留的鉴权口子」
     * （{@code AbstractSmokeTest.authHeaders()}）本笔起真正被用上的地方。
     */
    @Override
    protected Map<String, String> authHeaders() {
        return token == null
                ? Map.of()
                : Map.of(AuthTokenStore.HEADER, AuthTokenStore.BEARER + token);
    }

    // ==================== 三档：13 个端点各跑一遍 ====================

    /**
     * 第 1 档：{@code idValue} 填**自己**⇒ 放行（判据与角色无关 —— 这一档跑的是商家票与客户票各一半）。
     */
    @Test
    @DisplayName("第 1 档：入参 = 自己 ⇒ 13 个端点全放行")
    void idValueEqualToSelf_isAllowedOnEveryEndpoint() throws Exception {
        assertNotBlocked(sweep(Endpoint::selfId, Endpoint::selfId, e -> e.selfRole()), "入参填自己");
    }

    /**
     * 第 3 档：{@code idValue} 填**别人**且调用者是普通用户 ⇒ **13 个端点全 403**。
     *
     * <p>⚠️ 断言是 {@code isEqualTo(403)} 而不是「不是 200」：403 是本笔的**契约**
     * （有身份但无权）。若有人把它改成 401，本条红 —— 那个改动会让前端把它当登录态失效处理
     * （401 出口清态跳登录），用户看到的是「莫名其妙被登出」而不是「你没这个权限」。
     */
    @Test
    @DisplayName("第 3 档：入参 = 别人 + 非管理员 ⇒ 13 个端点全 403")
    void idValueOfSomeoneElse_fromNonAdmin_is403OnEveryEndpoint() throws Exception {
        Map<String, Integer> statuses = sweep(Endpoint::selfId, Endpoint::otherId, e -> e.selfRole());

        assertThat(statuses).as("受保护端点数 —— 少一个说明清单漏了").hasSize(PROTECTED_ENDPOINTS);
        assertThat(statuses).allSatisfy((name, code) ->
                assertThat(code).as("端点 %s：填别人的业务号必须 403（不是 401，不是 200）", name)
                        .isEqualTo(403));
    }

    /**
     * 第 2 档：{@code idValue} 填**别人**、但调用者是管理员 ⇒ 13 个端点全放行。
     *
     * <p>管理员改别人的东西是**职责**（驳回商家异常更新、处理异常订单、处理不合法描述），
     * 不是越权 —— 细粒度管理员权限（能改哪些字段 / 审计 / 二次确认）**归 C3 笔**，本笔只开这扇门。
     */
    @Test
    @DisplayName("第 2 档：入参 = 别人 + 管理员票 ⇒ 13 个端点全放行")
    void idValueOfSomeoneElse_fromAdmin_isAllowedOnEveryEndpoint() throws Exception {
        assertNotBlocked(sweep(e -> ADMIN, Endpoint::otherId, e -> AuthSession.ROLE_ADMIN), "管理员替别人操作");
    }

    // ==================== 对端方不入判据（本笔最容易搞错的一处）====================

    /**
     * ⚠️ <b>只有「代表调用者自己」的那个入参进判据</b>：订单的 {@code MerchantId}、
     * 好友的 {@code friendId} 都是**对端方**，填别人的**不该** 403。
     *
     * <p>这条与第 3 档是**一对**：少了它，「把每一个业务号入参都判一遍」这种误实现
     * 会让第 3 档照样全绿（它们至少会红一个），而这条会在**正常业务**上当场红。
     */
    @Test
    @DisplayName("对端方不入判据：orderAdd 的 MerchantId / friendAdd 的 friendId 填别人 ⇒ 不 403")
    void counterpartyParams_areNotJudged() throws Exception {
        // ① orderAdd：以 C-1 的名义下单，买 M-9（别人的）商品 —— 完全正常的业务，必须放行。
        token = AuthTokenStore.mint(CUSTOMER, 1);
        int orderAdd = perform("/order/orderAdd", Map.of(
                "CustomerId", CUSTOMER,                       // ← 判的是这个
                "MerchantId", COUNTERPARTY_MERCHANT,          // ← 对端方，不判
                "OrderId", "O-1", "Price", "1.0", "ProductInformation", "smoke",
                "Images", "smoke.png", "PayIf", "0", "DeliveryIf", "0", "ReceiveIf", "0"));

        // ② friendAdd：以 C-1 的名义加 C-9 为好友 —— 同上。
        AuthTokenStore.revoke(token);
        token = AuthTokenStore.mint(CUSTOMER, 1);
        int friendAdd = perform("/message/friendAdd", Map.of(
                "customerId", CUSTOMER,                       // ← 判的是这个
                "friendId", COUNTERPARTY_CUSTOMER));          // ← 对端方，不判

        assertThat(Map.of("orderAdd", orderAdd, "friendAdd", friendAdd))
                .as("对端方填别人是**正常业务**，被 403 就说明判错入参了")
                .allSatisfy((name, code) -> assertThat(code).as(name).isNotEqualTo(403).isNotEqualTo(401));
    }

    /**
     * 两个**刻意不保护**的端点：{@code productCodePrice} 与 {@code GoodsProductCodeSelect}。
     * 它们的 {@code MerchantId} 是「**被浏览的**商家」（商品页 / 首页在看谁家的货），不是调用者
     * ⇒ 填别人的商家**正是它们的用途**，加了守卫会把整个商品浏览面打死。
     *
     * <p>本条的第二个断言（两条都在白名单里）是**理由**而不是重复：它们连未登录用户都要能打
     * （首页/商品页的 {@code onMounted} 没有登录态判断），这条性质与「不挂守卫」是同一个来源。
     */
    @Test
    @DisplayName("两个浏览面端点不挂守卫：别人的 MerchantId 照过，且在白名单里")
    void browseSideEndpoints_areDeliberatelyUnguarded() throws Exception {
        token = AuthTokenStore.mint(CUSTOMER, 1);

        int price = perform("/goods/productCodePrice", Map.of(
                "MerchantId", OTHER_MERCHANT, "GoodName", "smoke-goods", "ProductCode", "SMOKE-P1"));
        int select = perform("/goods/GoodsProductCodeSelect", Map.of(
                "MerchantId", OTHER_MERCHANT, "GoodName", "smoke-goods"));

        assertThat(Map.of("productCodePrice", price, "GoodsProductCodeSelect", select))
                .as("「被浏览的商家」填别人是正常业务")
                .allSatisfy((name, code) -> assertThat(code).as(name).isNotEqualTo(403));

        assertThat(WebAuthConfig.AUTH_WHITELIST)
                .as("它们同时是公开面（首页/商品页在未登录时照常发这些请求）—— 与「不挂守卫」同源")
                .contains(BROWSE_SIDE_ENDPOINTS);
    }

    // ==================== 403 的响应体形状 ====================

    /**
     * 403 的响应体：{@code {success:false, msg:"无权操作该账户"}} —— 前端按 {@code data.msg} 取。
     *
     * <p>⚠️ 只钉**键与值**，不钉字节序：403 是本笔新增的状态码，没有历史字节契约
     * （与 401 那条「两处逐字节相同」恰好相反，见 {@code IdentityGuard.forbidden()} 的注释）。
     */
    @Test
    @DisplayName("403 响应体：success:false + msg（前端按 msg 取值）")
    void forbiddenBody_carriesSuccessFalseAndMsg() throws Exception {
        // 票是客户的（C-1），入参是**别人家的商家号**（M-2）⇒ 第 3 档。
        token = AuthTokenStore.mint(CUSTOMER, 1);

        mockMvc.perform(build(endpoint("goodsUp"), OTHER_MERCHANT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("无权操作该账户"));
    }

    // ==================== 单元面：三档之外的两条判据 ====================

    /**
     * ⚠️ <b>解析不出身份 ⇒ 放行</b> —— 本笔对既有行为「零影响」的**唯一**支点。
     *
     * <p>直调（不走 MockMvc），因为这一支在闸开着的端到端里**根本不可达**：13 个端点都不在白名单，
     * 闸已把无凭据的请求答成 401。它真正服务的场景是**闸关着**：smoke 切片（10 个类）与 A3
     * 都钉 {@code app.auth.enabled=false}，它们打这些端点时没有会话 —— 这一行判 403 就会当场
     * 弄红 27+ 条既有用例与整个 A3。
     */
    @Test
    @DisplayName("身份解析不出 ⇒ 放行（闸关着时对既有行为零影响的支点）")
    void nullIdentity_isAllowed() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/goods/goodsSelect");

        assertThat(IdentityGuard.requireSelfOrAdmin(request, OTHER_MERCHANT))
                .as("无凭据（闸关着 = smoke / A3 的常态）⇒ 不能拦")
                .isTrue();
    }

    /** 入参本身是 {@code null}（可选参数没传）⇒ 没什么可判的，放行 —— 缺参由 {@code @RequestParam} 管。 */
    @Test
    @DisplayName("入参为 null ⇒ 放行（缺参不归守卫管）")
    void nullParamId_isAllowed() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/goods/goodsSelect");

        assertThat(IdentityGuard.requireSelfOrAdmin(request, null)).isTrue();
    }

    // ==================== 工具 ====================

    /**
     * 全表扫一遍，收集每个端点的 HTTP 状态码。
     *
     * <p>⚠️ <b>三档的差别全在这两个函数上</b>，写错一个整档就废（实测踩过）：
     * {@code tokenIdOf} 是**调用者是谁**（写进票里），{@code paramIdOf} 是**入参填了谁**
     * （写进该端点的 {@code idParam}）。两档的差别**不在票上，在入参上**：
     * 「填自己」= 两个都取 {@code selfId}；「填别人」= 票仍是 {@code selfId}、入参换成 {@code otherId}。
     * 若把「填别人」那档写成「票也换成 {@code otherId}」，就等于**自己填自己**，
     * 守卫第 1 档直接放行 —— 整档退化成空命题，13 个 200 全绿（本笔第一次跑就是这样红的）。
     *
     * <p>每端点**换一张新票**：同一张票不能既是 M-1 又是 C-1，而本表里两种端点都有。
     */
    private Map<String, Integer> sweep(Function<Endpoint, String> tokenIdOf,
                                       Function<Endpoint, String> paramIdOf,
                                       Function<Endpoint, Integer> roleOf) throws Exception {
        Map<String, Integer> statuses = new LinkedHashMap<>();
        for (Endpoint endpoint : ENDPOINTS) {
            AuthTokenStore.revoke(token);
            token = AuthTokenStore.mint(tokenIdOf.apply(endpoint), roleOf.apply(endpoint));
            statuses.put(endpoint.name(), perform(build(endpoint, paramIdOf.apply(endpoint))));
        }
        return statuses;
    }

    /** 放行档的统一判据：既不是守卫拒的（403），也不是闸拒的（401）。见类注释。 */
    private void assertNotBlocked(Map<String, Integer> statuses, String what) {
        assertThat(statuses).as("受保护端点数 —— 少一个说明清单漏了").hasSize(PROTECTED_ENDPOINTS);
        assertThat(statuses).allSatisfy((name, code) ->
                assertThat(code).as("端点 %s：%s ⇒ 不该被拦（403 = 守卫拒了，401 = 闸没认出票）", name, what)
                        .isNotEqualTo(403).isNotEqualTo(401));
    }

    /** 发一发并取状态码；请求在业务代码里炸了则返回 {@link #THREW}（见类注释的容忍理由）。 */
    private int perform(MockHttpServletRequestBuilder builder) throws Exception {
        try {
            return mockMvc.perform(builder).andReturn().getResponse().getStatus();
        } catch (Exception ex) {
            return THREW;
        }
    }

    /** 便捷版：按路径与固定入参自己搭一发（不走 {@link #build} 那张表）。 */
    private int perform(String path, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(path);
        params.forEach(builder::param);
        return perform(request(builder));
    }

    private MockHttpServletRequestBuilder build(Endpoint endpoint, String idValue) {
        MockHttpServletRequestBuilder builder = endpoint.multipart()
                ? multipart(endpoint.path())
                        .file(new MockMultipartFile("file", "1.jpg", "image/jpeg", "IMG".getBytes()))
                : MockMvcRequestBuilders.post(endpoint.path());
        endpoint.otherParams().forEach(builder::param);
        builder.param(endpoint.idParam(), idValue);
        return request(builder);
    }

    private static Endpoint endpoint(String name) {
        return ENDPOINTS.stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("表里没有这个端点：" + name));
    }
}
