package com.minke.mallmanagement.shared.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * 全仓此前只有 {@code shared/upload/ImgPath} 一个实现。新增本类**不动** {@code ImgPath}：{@code addInterceptors} 与 {@code addResourceHandlers} 是互不相干的扩展点，两个实现各自被调用。
 *
 * <p><b>2026-10-04 实测：{@code @WebMvcTest} 切片会扫到本类</b>（切片按类型过滤，
 * {@code WebMvcConfigurer} 在名单内）⇒ <b>既有 smoke 类同样经过这道闸</b>，无需也不应加 {@code @Import}。
 * 证据是一条**全量突变对照**：把开关写死成常开（丢掉 {@code app.auth.enabled}）后跑
 * {@code ./mvnw -B test} —— 打非白名单端点的 **7** 个类当场红 **27** 条
 * （{@code OrderAddStateGuardSmokeTest} 10、{@code OrderAddPriceGuardSmokeTest} 8、
 * {@code OrderPayIfUpSmokeTest} 3、{@code GoodsAddUploadFailSmokeTest} 2、
 * {@code ValidationProbeTest} 2、{@code LoginSelectSmokeTest} 1、
 * {@code PriceTripleFailSmokeTest} 1）；只打 {@code /img/**} 的 {@code ImgMappingSmokeTest} 全绿。
 * 连闸自己那层共 29 条。
 *
 * <p>⚠️ <b>这条实测的结论在 2026-10-05 翻开关后**改成**：smoke profile 必须钉 {@code false}</b> ——
 * 主配置的默认值已由 {@code false} 翻成 {@code true}，是 {@code application-smoke.properties}
 * 那一行显式 pin 让这套件保持全绿（不钉 = 第一秒由绿转红）。同一道理，A3 由
 * {@code application-a3.properties} 钉 {@code false}。见 {@code progress.md} §5-AD。
 *
 * <h2>【开关现值：2026-10-05 起为 true（翻开关笔）】</h2>
 * ⚠️ 本类此前长期默认 {@code false}；<b>2026-10-05 已翻 true</b>（{@code application.properties} + §5-AD）。
 * 翻的前置条件（会话写入方 = 鉴权笔 2；前端 credentials + 401 出口 = 笔 4 / 会话恢复笔）已齐备。
 * 生效后：白名单外的请求必须带「会话 + {@code userId}」，否则 401（下单/发消息/上下架/改资料全在此列）。
 * ⚠️ 两处 profile 各有一行显式 {@code false} pin（smoke / a3），理由见上节 —— 它们才是
 * 测试与 A3 至今全绿的原因，别再靠「默认关」。
 */
@Configuration
public class WebAuthConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebAuthConfig.class);

    /**
     * 登录闸排除表（白名单）—— <b>唯一出处</b>，{@link AuthInterceptor} 用同一个 list 判定。
     *
     * <p>判据是<b>路径</b>，与 HTTP 方法无关（{@code /goods/goodstype} 是 GET/POST 双形态，
     * 一条即覆盖两者）。{@code /**} 由 {@code AntPathMatcher} 展开。
     *
     * <h3>业务 10 条</h3>
     * <ul>
     *   <li><b>登录链 3 条</b>（{@code /user/loginSelect}、{@code /user/loginAdd}、
     *       {@code /user/getUserInfo}）—— 登录页在**未登录**状态下就打这三个，白名单少了它们
     *       等于闸一开就再也登不进去（⚠️ 其中 {@code /user/getUserInfo} 的前端消费点自 P2.6 起已归零、
     *       不再在登录链上 —— 仍留白名单属「端点只保留不删」，见 {@code progress.md} §5-AC）。</li>
     *   <li><b>登录前后台页面 7 条</b>（{@code /goods/goodstype}、{@code /goods/goodsSelectHome}、
     *       {@code /goods/GoodsProductCodeSelect}、{@code /goods/productCodePrice}、
     *       {@code /goods/productSearch}、{@code /shop/profile}）—— 首页 {@code HomePage.vue:201-208}
     *       与商品页 {@code ProductPage.vue:991} 的 {@code onMounted} **没有任何登录态判断**
     *       （2026-10-04 逐行核实），且全仓无路由守卫 ⇒ 这两页在未登录时照常发这些请求。
     *       ⚠️ 其中 {@code productSearch} / {@code GoodsProductCodeSelect} / {@code productCodePrice}
     *       属**「同类公开」**（与首页同为商品浏览面），不是「未登录必经」—— 这条区分见下。
     *       <p>⚠️ {@code /shop/profile}（C3 块 5 · B 组续笔，2026-10-12）是**店铺主页**的公开档案：
     *       店铺主页是商品详情页「进店逛逛」的落点，而详情页本身是公开面
     *       （{@code router/index.js} 里 {@code /product} 与 {@code /shop} 都挂 {@code meta.public}）
     *       ⇒ 未登录点进店得看到店名 / 开店时长，否则那一格是空的。**同族另三条**
     *       （{@code /shop/follow} / {@code /shop/unfollow} / {@code /shop/followState}）**仍在身份面**，
     *       别顺手一起搬进来。
     *       <p>⚠️ 第 7 条 {@code /comment/list}（C3 块 6 评论列表，2026-10-12）同为**公开面**：
     *       商品详情页 {@code ProductPage.vue} 的「累计评价」区在未登录时照常加载，而该页挂
     *       {@code meta.public} ⇒ 少这一条，未登录用户看到的是一格空评价区（外加 401 噪音）。
     *       <b>同域另 4 个写端点（add / delete / like / myOrders）仍在身份面</b>，别顺手一起搬。</li>
     * </ul>
     *
     * <h3>基础设施 4 条</h3>
     * <ul>
     *   <li>{@code /health/db} —— ⚠️ <b>归基础设施，不是业务</b>（用户 2026-10-04 裁决，
     *       本笔原先错放在业务组第 9 条）：它是 A3 的 {@code @Profile("a3")} P5 就绪探针，
     *       <b>不是前端必经的调用点</b>（它在前端审计里本就属「无调用方」的 2 条之一）。
     *       A3 由 {@code application-a3.properties} 钉 {@code false} ⇒ 轮不到白名单；列进来是**为开关翻 true 后
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
     * {@code /user/updateAvatar}、{@code /user/updateUserInfo}。它们当年可达是**前端缺守卫**、**不是**
     * 把它们放进白名单的理由（§5-AD D3；开关 2026-10-05 翻 true ⇒ 已被闸挡下）。⚠️ **已结清**
     * （前端路由守卫笔 {@code feat/router-guard}，2026-10-06，§5-AG）：调用页全在守卫受保护一侧；**仍不进本表**。
     */
    public static final List<String> AUTH_WHITELIST = List.of(
            // ===== 业务：登录链 =====
            "/user/loginSelect",
            "/user/loginAdd",
            "/user/getUserInfo",
            // ===== 业务：登录前后台页面（首页 / 商品页 / 搜索页 / 店铺主页）=====
            "/goods/goodstype",
            "/goods/goodsSelectHome",
            "/goods/GoodsProductCodeSelect",
            "/goods/productCodePrice",
            "/goods/productSearch",
            // 店铺主页存档（公开档案：登录名 / 注册时刻 / 入驻审核状态）。⚠️ 同族 follow 三条**不在此**。
            "/shop/profile",
            // 商品评论列表（C3 块 6，2026-10-12）—— 商品详情页评价区的读，⭐ **只放读**：
            // 同域的 add / delete / like / myOrders 四个写端点仍在身份面（见上面那段 ⚠️）。
            "/comment/list",
            // ===== 基础设施 =====
            // 运维探针（⚠️ 仅 a3 profile 存在）。**归基础设施，不是业务** —— 理由见上。
            "/health/db",
            "/error",
            "/img/**",
            "/favicon.ico");

    /**
     * 「身份识别例外」表（多账户隔离笔，2026-10-06）—— 与 {@link #AUTH_WHITELIST} **不是一回事**，
     * 别合并、也别互相搬条目：
     * <ul>
     *   <li>白名单 = <b>公开面</b>：不要身份也让你进（登录页、浏览面、静态资源）；</li>
     *   <li>本表 = <b>识别面</b>：<b>要**有**身份</b>才放行，且只放行「说出你是谁」这一件事 ——
     *       授权判据（{@link AuthSession#authorize}）在这一档是**不通过**的。</li>
     * </ul>
     * 今日只有一条 {@code /user/me}，它的语义本就是「告诉我我是谁」：管理员在**新标签页**里
     * 没有本地 token、只有浏览器共享的 cookie 会话时，本表让他仍能问出「你是管理员」，
     * 端点据此**降级作答**（只回 {@code role}，不回资料），前端便走「退回登录页」而不是
     * 静默当匿名。⚠️ 业务端点**不在本表**、也不该进来 —— 它们对 {@code role=3} 的 cookie 一律 401。
     *
     * <p>⚠️ 不进 {@link #AUTH_WHITELIST} 是**刻意的**：白名单的判据是「公开面」，
     * 而 {@code /user/me} 恰恰要身份；放进去会让那条语义破产，且会撞上
     * {@code AuthGateSmokeTest.whitelist_allEntries_passTheGate} 的「条数 == 12」钉子。
     */
    public static final List<String> AUTH_IDENTITY_PATHS = List.of("/user/me");

    /** 全局唯一的拦截器实例。开关在**构造期**读入（改开关需重启，与其它 @Value 一致）。 */
    private final AuthInterceptor authInterceptor;

    /**
     * ⚠️ <b>第 4 个构造参数刻意是 {@link ObjectProvider}，不是裸的 {@code UserStateLookup}</b>
     * （C3 块 2 用户块）。
     *
     * <p>理由是一条**block-1 实测过的切片地雷**：{@code @WebMvcTest} 会扫到本类
     * （{@code WebMvcConfigurer} 在切片名单内，见类注释），但**不扫** {@code @Service}
     * ——裸依赖会让每一个 {@code @WebMvcTest} 切片在启动期就因「找不到 bean」集体失败
     * （block 1 的同型事故：237 条测试 / 77 条错误）。{@code ObjectProvider.getIfAvailable()}
     * 在「没有该 bean」时返回 {@code null}（不抛），于是切片里封禁闸整体静默不生效，
     * 而**默认 profile 里它照常生效**。
     *
     * <p>❗ 这是本类唯一一处「按上下文静默降级」的依赖，别照着把它改成构造器直注入。
     */
    public WebAuthConfig(@Value("${app.auth.enabled:false}") boolean authEnabled,
                         ObjectProvider<UserStateLookup> userStateLookupProvider) {
        this.authInterceptor = new AuthInterceptor(authEnabled, AUTH_WHITELIST, AUTH_IDENTITY_PATHS,
                userStateLookupProvider.getIfAvailable());
        // 把生效值打进启动日志（惯例同 app.lock.* / app.cache.* / app.order.state.*）：此前本开关没有
        // 任何启动期取证口，闸开没开只能从行为反推（progress.md §5-R）。ASCII 片段 "AUTH" 供 GBK 检索。
        log.info("登录闸已装配（AUTH）：app.auth.enabled={} 白名单 {} 条 —— 关闭时全部放行", authEnabled, AUTH_WHITELIST.size());
    }

    /**
     * 注册到 {@code /**}，白名单**不**走 {@code excludePathPatterns} —— ⚠️ 刻意的，别「顺手改成
     * excludePathPatterns」：被排除的路径**根本进不了 {@code preHandle}**，白名单就无法在拦截器
     * 这一层被断言。当前写法让 {@code AuthGateSmokeTest} 能对 14 条逐条直调 {@code preHandle}
     * 钉死（「被排除」与「不存在」在下游都是 404，MockMvc 分不开；白名单的契约恰是「闸不拦它」）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/**");
    }
}
