package com.minke.mallmanagement.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 登录闸的装配类（鉴权笔 1）—— <b>只做两件事：持有排除表、注册拦截器</b>。
 *
 * <p>判定逻辑全在 {@link AuthInterceptor}；本类不判任何一条请求。
 *
 * <h2>【为什么白名单是这里的常量，而不是 properties 里的列表】</h2>
 * <ol>
 *   <li><b>单一出处</b>：拦截器实例由本类 {@code new} 出来并把它作为构造参数传入 ⇒
 *       「代码里一份、注解/配置里又一份」的双写不存在（{@code app.auth.enabled} 那条开关
 *       走 properties 是因为它需要**按环境**变；白名单不该按环境变 —— 它对所有 profile 是同一个集合）；</li>
 *   <li><b>改白名单必须过代码评审</b>：这是唯一会放宽闸的入口，落成常量后每次变动都出现在 diff 里。</li>
 * </ol>
 *
 * <h2>【本类新增一个 {@code WebMvcConfigurer}，会影响谁】</h2>
 * 全仓此前只有 {@code shared/upload/ImgPath} 一个实现。新增本类**不动** {@code ImgPath}：
 * {@code addInterceptors} 与 {@code addResourceHandlers} 是互不相干的扩展点，两个实现各自被调用。
 *
 * <p><b>2026-10-04 实测：{@code @WebMvcTest} 切片会扫到本类</b>（切片按类型过滤，
 * {@code WebMvcConfigurer} 在名单内）⇒ <b>既有 8 个 smoke 类同样经过这道闸</b>，
 * 无需也不应给它们加 {@code @Import}。证据是一条**全量突变对照**：把开关写死成常开
 * （丢掉 {@code app.auth.enabled}）后跑 {@code ./mvnw -B test} ——
 * 8 个既有类里打非白名单端点的 **7** 个当场红 **27** 条
 * （{@code OrderAddStateGuardSmokeTest} 10、{@code OrderAddPriceGuardSmokeTest} 8、
 * {@code OrderPayIfUpSmokeTest} 3、{@code GoodsAddUploadFailSmokeTest} 2、
 * {@code ValidationProbeTest} 2、{@code LoginSelectSmokeTest} 1、
 * {@code PriceTripleFailSmokeTest} 1）；第 8 个是只打 {@code /img/**} 的
 * {@code ImgMappingSmokeTest}，全绿。连本笔自己那层共 29 条。
 *
 * <p>⚠️ <b>这正是开关必须默认 false 的最硬理由</b>（比「生产会锁死」更早暴露）：闸对**所有**
 * {@code @WebMvcTest} 生效，而这些既有用例打的都是**未登录的非白名单**端点 ——
 * 默认值一旦改成 {@code true}，整个测试套件在第一秒就由绿转红。生产 / {@code @SpringBootTest} /
 * A3（真启动 jar）同样会加载本类。
 *
 * <h2>【为什么默认关：闸的认证侧还没落地】</h2>
 * 本笔只做「拦」不做「认证」—— 没有任何端点会把 {@link AuthInterceptor#SESSION_USER_ID}
 * 写进会话（那是鉴权笔 2）。故开关一旦打开，**全站非白名单接口立即不可用**（下单、发消息、
 * 上下架、改资料……）。默认 {@code false} ⇒ 拦截器 {@code preHandle} 第一句就 {@code return true}，
 * 生产与 A3 的行为与本笔之前**逐字节相同**。
 */
@Configuration
public class WebAuthConfig implements WebMvcConfigurer {

    /**
     * 登录闸排除表（白名单）—— <b>唯一出处</b>，{@link AuthInterceptor} 用同一个 list 判定。
     *
     * <p>判据是<b>路径</b>，与 HTTP 方法无关（{@code /goods/goodstype} 是 GET/POST 双形态，
     * 一条即覆盖两者）。{@code /**} 由 {@code AntPathMatcher} 展开。
     *
     * <h3>业务 8 条</h3>
     * <ul>
     *   <li><b>登录链 3 条</b>（{@code /user/loginSelect}、{@code /user/loginAdd}、
     *       {@code /user/getUserInfo}）—— 登录页在**未登录**状态下就打这三个，白名单少了它们
     *       等于闸一开就再也登不进去。⚠️ {@code getUserInfo} 的消费点见
     *       {@code LoginPage.vue:141}（登录成功后立刻用它取身份）。</li>
     *   <li><b>登录前后台页面 5 条</b>（{@code /goods/goodstype}、{@code /goods/goodsSelectHome}、
     *       {@code /goods/GoodsProductCodeSelect}、{@code /goods/productCodePrice}、
     *       {@code /goods/productSearch}）—— 首页 {@code HomePage.vue:186-193} 与商品页
     *       {@code ProductPage.vue:462-465} 的 {@code onMounted} **没有任何登录态判断**
     *       （2026-10-04 逐行核实），且全仓无路由守卫 ⇒ 这两页在未登录时照常发这些请求。
     *       ⚠️ 其中 {@code productSearch} / {@code GoodsProductCodeSelect} / {@code productCodePrice}
     *       属**「同类公开」**（与首页同为商品浏览面），不是「未登录必经」—— 这条区分见下。</li>
     * </ul>
     *
     * <h3>基础设施 4 条</h3>
     * <ul>
     *   <li>{@code /health/db} —— ⚠️ <b>归基础设施，不是业务</b>（用户 2026-10-04 裁决，
     *       本笔原先错放在业务组第 9 条）：它是 A3 的 {@code @Profile("a3")} P5 就绪探针，
     *       <b>不是前端必经的调用点</b>（它在前端审计里本就属「无调用方」的 2 条之一）。
     *       A3 跑在 {@code app.auth.enabled=false} 下时轮不到白名单；列进来是**为开关翻 true 后
     *       留的运维通道** —— 那个准备是对的，但它**不是给用户用的公开面**。</li>
     *   <li>{@code /error} —— 容器把未处理异常转发到它（ERROR dispatch），拦它等于让「出错」
     *       再叠一层 401，把真错误盖掉；</li>
     *   <li>{@code /img/**} —— 商品图与头像都是公开静态资源（{@code ImgPath} 注册），
     *       未登录的首页也要显示图片；</li>
     *   <li>{@code /favicon.ico} —— 浏览器自动请求，401 会在控制台刷噪音，且它不对应任何业务动作。</li>
     * </ul>
     *
     * <h3>⚠️ 白名单是「公开面」，不是「未登录可达面」</h3>
     * 2026-10-04 全量核实：未登录可达的非白名单端点还有 6 个 —— {@code /goods/goodsAdd}、
     * {@code /goods/goodsDe}、{@code /message/friendAdd}、{@code /message/userSelect}、
     * {@code /user/updateAvatar}、{@code /user/updateUserInfo}。它们可达是**前端缺守卫**（那是
     * {@code P2.6} 笔的活），**不是**把它们放进白名单的理由。本表**刻意不含**它们：
     * 这也是「默认关」之外的第二条防线 —— 将来开关打开时，这些端点会被闸挡下。
     */
    public static final List<String> AUTH_WHITELIST = List.of(
            // ===== 业务：登录链 =====
            "/user/loginSelect",
            "/user/loginAdd",
            "/user/getUserInfo",
            // ===== 业务：登录前后台页面（首页 / 商品页 / 搜索页）=====
            "/goods/goodstype",
            "/goods/goodsSelectHome",
            "/goods/GoodsProductCodeSelect",
            "/goods/productCodePrice",
            "/goods/productSearch",
            // ===== 基础设施 =====
            // 运维探针（⚠️ 仅 a3 profile 存在）。**归基础设施，不是业务** —— 理由见上。
            "/health/db",
            "/error",
            "/img/**",
            "/favicon.ico");

    /** 全局唯一的拦截器实例。开关在**构造期**读入（改开关需重启，与其它 @Value 一致）。 */
    private final AuthInterceptor authInterceptor;

    public WebAuthConfig(@Value("${app.auth.enabled:false}") boolean authEnabled) {
        this.authInterceptor = new AuthInterceptor(authEnabled, AUTH_WHITELIST);
    }

    /**
     * 注册到 {@code /**}，白名单**不**走 {@code excludePathPatterns}。
     *
     * <p>⚠️ 这是刻意的，别「顺手改成 excludePathPatterns」：排除掉的路径**根本进不了
     * {@code preHandle}**，于是白名单无法在拦截器这一层被断言（测试只能去看下游控制器答什么），
     * 而白名单的契约恰恰是「闸不拦它」。当前写法让
     * {@code AuthGateSmokeTest} 能对 12 条条目逐条直调 {@code preHandle} 钉死 ——
     * 「被排除」与「不存在」在下游都是 404，用 MockMvc 分不开这两者。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/**");
    }
}
