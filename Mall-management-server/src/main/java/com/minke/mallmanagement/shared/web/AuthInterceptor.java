package com.minke.mallmanagement.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;

/**
 * 登录闸（鉴权笔 **1**）—— <b>只判「放不放行」，不做认证本身</b>。
 *
 * <h2>【它判五件事，顺序固定】</h2>
 * <ol>
 *   <li><b>总开关</b>（{@code app.auth.enabled}）—— 关着就 {@code return true}，全部放行。
 *       默认**关**，故本笔对现有行为**零影响**。</li>
 *   <li><b>白名单</b>（{@link WebAuthConfig#AUTH_WHITELIST}，唯一出处）—— 命中即放行。
 *       判据是<b>路径</b>（{@code AntPathMatcher}），与 HTTP 方法无关。</li>
 *   <li><b>凭据</b>（**授权**口径，{@link AuthSession#authorize}）—— token（{@code Authorization:
 *       Bearer}）优先；无 token 时看服务端会话，且会话里要带 {@link #SESSION_USER_ID}。
 *       ⚠️ {@code role=3}（管理员）的 cookie 在**这一档就被折成未登录** —— 管理员身份只能由
 *       token 承载，理由见 {@link AuthSession#authorize}。
 *       <p>⚠️ <b>C3 块 2（用户块）在 ③ 之内插了第 ⑤ 档：封禁闸</b>（见 {@link #isBanned}）
 *       —— 凭据有效但账号被封 ⇒ 同样 401。它必须**在 ③ 之后**（要先认出是谁）且**在 ④ 之前**
 *       （被封的账号连「说出你是谁」也不给）。</li>
 *   <li><b>身份识别例外</b>（{@link WebAuthConfig#AUTH_IDENTITY_PATHS}，今日只有 {@code /user/me}）
 *       —— 第 ③ 档不通过、但**识别**口径（{@link AuthSession#identify}）拿得到身份时放行。
 *       目的是让前端能问出「你是管理员，但需要显式登录」并据此**退回登录页**，而不是静默当匿名。
 *       ⚠️ 这条例外**只放宽「说出你是谁」**：端点必须自行降级作答（{@code /user/me} 对
 *       cookie 来的 {@code role=3} 只回 {@code role}、不回资料），业务端点仍走 ③ 的 401。</li>
 *   <li><b>封禁闸</b>（C3 块 2 新增；挂在 ③ 之内判定，编号放末是为了不打乱上面四档的既有叙述）
 *       —— 身份有效但账号 {@code banned} ⇒ 401。⚠️ **只判 role 1/2，管理员豁免**，
 *       理由见 {@link #isBanned}。</li>
 * </ol>
 * 五档都不通过 ⇒ 写 401 + {@code {"success":false,"msg":"未登录"}} 并 {@code return false}，
 * **请求不进 Controller**。
 *
 * <p>⚠️ <b>封禁档为什么答「未登录」而不是另造一个 body</b>：401 的形状（状态码 + 逐字节固定的
 * {@link #BODY_NOT_LOGGED_IN}）是一份**既有对外契约**，前端 {@code api/http.js} 的 401 出口
 * 据此清态跳登录页。再造一个 403 / 新 body 等于要求前端多认一种失败，而收益只是「更早说出被封」
 * —— 那句话由**登录那一步**说得更好：{@code loginSelect} 答 {@code 4}，前端有专门的文案与出口
 * （见 {@code UserService.loginSelect}）。故本档**复用** 401 与那条字面量，不加新契约。
 *
 * <h2>【为什么「有会话」不等于「已登录」—— 这是本类最容易被放宽的一处】</h2>
 * 判据刻意是「会话**且**带 {@code userId} 属性」，不是「有会话即可」。会话在 Servlet 语义里只是
 * 一块服务端存储，任何一个 {@code getSession()} 调用都能创建它 —— 拿「存在会话」当登录态，
 * 等于把闸的判定权交给「谁碰过 session」。故逐条判属性，并把属性名固化成公开常量
 * {@link #SESSION_USER_ID}：<b>写入方（鉴权笔 2 的登录端点）引用本常量，不另抄字面量</b>
 * ——该要求已于 2026-10-04（笔 2）落地：写入方是 {@link AuthSession}，它只引用本常量。
 *
 * <h2>【为什么本类不是 {@code @Component}】</h2>
 * 由 {@link WebAuthConfig} 显式 {@code new} 出来（开关与两张表都是构造参数）。两个好处：
 * ① 白名单只有一处出处（配置类里的常量），不存在「注解里一份、代码里一份」的双写；
 * ② 本类**无需 Spring 上下文即可单测** —— 见 {@code AuthGateSmokeTest} 的白名单全表扫描用例
 * （{@code /health/db} 在 smoke profile 下无 handler，走 MockMvc 扫不到它，只能直调）。
 * ⚠️ 2026-10-06（多账户隔离笔）：构造参数从 2 个变 3 个（多 {@code identityPaths}）⇒
 * 调用点共 2 处，改动清单见 {@code progress.md §5-AF}。
 *
 * <h2>【为什么 401 响应体是字面量，而不是 {@code Result.fail("未登录")}】</h2>
 * {@link Result} 继承 {@code HashMap}，键序由 hash 桶决定 ⇒ 序列化出来可能是
 * {@code {"msg":…,"success":…}}。本闸的响应形状是对外承诺（{@code {"success":false,"msg":"未登录"}}），
 * 故写成字面量把字节钉死。⚠️ 同理它**刻意不抛异常**、也不走
 * {@link GlobalExceptionHandler} —— 后者的 {@code assignableTypes} 只覆盖
 * {@code MessageController} / {@code GoodsController} 两个类，拦截器拿不到「该走哪条 advice」
 * 这层信息，且让「未登录」借异常通道返回会把 401 与 200 两族失败混成一种。
 *
 * <h2>【本笔不做】</h2>
 * 不写会话（登录端点尚未改造 —— 那是鉴权笔 2）、不做 RBAC、不做密码 hash、不引
 * Spring Security / JWT / 新依赖。⚠️ 故<b>开关打开时全站非白名单接口都会被拒</b> ——
 * 这正是默认 {@code false} 的理由。
 *
 * <p>⚠️ <b>2026-10-04（笔 2）后本句的「不写会话」已过时</b>：写入方已落地
 * （{@link AuthSession}，由 {@code UserController.loginSelect} 在认证成功时调用）。
 * 其余三条（RBAC / 密码 hash / 不引新依赖）仍然成立；开关的默认值**仍是 {@code false}**
 * —— 笔 2 只把「认证侧」建起来，没有翻开关，故上面那两条 401 断言的适用面不变。
 */
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * 会话里标记「已登录」的属性名。
     *
     * <p>⚠️ <b>本笔只读、不写</b>：写入方是鉴权笔 2 的登录端点，且它<b>应当引用本常量</b>
     * （{@code AuthInterceptor.SESSION_USER_ID}）而不是另抄一个 {@code "userId"} 字面量 ——
     * 两处字面量一旦不一致，闸会**静默**把已登录用户判成未登录（或反之），两边都不报错。
     */
    public static final String SESSION_USER_ID = "userId";

    /**
     * 会话里标记**角色**的属性名（1 = 客户 / 2 = 商家 / 3 = 管理员）。
     *
     * <p>⚠️ <b>2026-10-06（多账户隔离笔）新增 —— 这是对 {@link AuthSession} 类注释里
     * 「不存角色」的**有意破**，不是误改</b>。消费者有两个，缺任一个这条就白加：
     * <ol>
     *   <li>{@code POST /user/token} 要往 token 记录里写 {@code role}，而 {@code role} 的唯一来源是
     *       登录时 {@code login} 表的 {@code Role} 列 —— 会话是它此刻唯一的载体；</li>
     *   <li>{@link AuthSession#authorize} 的「cookie 回落对 {@code role=3} 不授权」这条判据
     *       本身就要读它（否则只能每次请求回查 {@code login} 表）。</li>
     * </ol>
     * ⚠️ 与 {@link #SESSION_USER_ID} 同一写入方（{@link AuthSession#login}）、同一读取方
     * （{@link AuthSession}）—— 别在第二处抄 {@code "role"} 字面量。
     */
    public static final String SESSION_ROLE = "role";

    /**
     * 401 响应体（逐字节固定）。键序 {@code success} → {@code msg}，与
     * {@link Result#fail(String)} 的写入序一致，但**不依赖** HashMap 的桶序来维持。
     *
     * <p>⚠️ <b>2026-10-04（笔 2）由 {@code private} 改为 {@code public}</b>：{@code /user/me}
     * 在「开关关着、但端点自身要求登录」时也要答同一个 401，而**不能**用
     * {@link Result#fail(String)} 拼 —— 实测（本机 JDK 17）：{@code new HashMap<>()} 里
     * {@code success} 落 6 号桶、{@code msg} 落 0 号桶 ⇒ 迭代序是 {@code msg} 在前，
     * 序列化得到 {@code {"msg":"未登录","success":false}}，与本字面量**不同字节**。
     * 故改为共享常量：两处 401 的字节相同**由构造保证**，不再靠「两个字面量恰好一样」。
     *
     * <p>⚠️ {@code AuthGateSmokeTest.BODY_NOT_LOGGED_IN} 是**另一份独立副本**（不是引用本常量）——
     * 那是刻意的：它是钉住本常量的**变更探测器**，把本常量改坏了它会红。
     */
    public static final String BODY_NOT_LOGGED_IN = "{\"success\":false,\"msg\":\"未登录\"}";

    /**
     * 已登录但角色不够时的 403 响应体（TD-64 · 块 7 收尾）。与 401 一样是手写 JSON ——
     * 拦截器在 Controller 之前返回，{@code Result} 序列化不经过这条链路。
     */
    public static final String BODY_FORBIDDEN = "{\"success\":false,\"msg\":\"需要管理员权限\"}";

    /**
     * 只允许管理员（{@code login.Role=3}）调用的路径（TD-64 · 块 7 收尾）。
     *
     * <p>⚠️ 只在**总开关开着**时生效（{@code app.auth.enabled=true}）：dev / a3 两个 profile
     * 关着闸，本表整段跳过 —— 这是既有语义，不是新漏洞（那两个 profile 本来就是本机调试面）。
     *
     * <p>为什么在拦截器里做而不是在 Controller 里逐个判：这是「路径前缀 + 身份」的判据，
     * 与 401 同一层；放进 Controller 会让每个危险端点各抄一遍，两处一旦漂移就没人说得清。
     * 目前唯一一条是清场（真 {@code DELETE}、不可逆）。将来新增破坏性端点应登记到这里。
     */
    private static final List<String> ADMIN_ONLY_PATHS = List.of(
            "/admin/observe/attack/cleanup");

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 总开关的取值（{@code app.auth.enabled}）。由 {@link WebAuthConfig} 在构造期读入。 */
    private final boolean enabled;

    /** 白名单（排除表）。副本，避免外部持有者事后改动而绕过闸。 */
    private final List<String> whitelist;

    /** 身份识别例外表（第 ④ 档）。同样取副本，理由同上。 */
    private final List<String> identityPaths;

    /**
     * 跨域「封禁态」探针（C3 块 2 用户块新增，第 ⑤ 档）。⚠️ <b>可为 {@code null}</b>。
     *
     * <p>为 {@code null} 的两种情形都不是缺陷：① 测试直接 {@code new} 的 3 参构造
     * （{@code AuthGateSmokeTest} 的白名单全表扫描就是这么造的）；② 切片里没有该 bean
     * （{@code UserStateLookupService} 是 {@code @Service}，{@code @WebMvcTest} 不扫）——
     * 故 {@link WebAuthConfig} 用 {@code ObjectProvider} 取它，取不到就给 {@code null}。
     * 这两种情形下第 ⑤ 档**整体不生效**（等价于封禁闸关着），这正是想要的：切片不该因为
     * 「多了一张表要查」而全红。
     */
    private final UserStateLookup userStateLookup;

    /** 3 参构造 —— **保留**，供测试直接 {@code new}（{@code AuthGateSmokeTest:185}）。等价于「无封禁闸」。 */
    public AuthInterceptor(boolean enabled, List<String> whitelist, List<String> identityPaths) {
        this(enabled, whitelist, identityPaths, null);
    }

    public AuthInterceptor(boolean enabled, List<String> whitelist, List<String> identityPaths,
                           UserStateLookup userStateLookup) {
        this.enabled = enabled;
        this.whitelist = List.copyOf(whitelist);
        this.identityPaths = List.copyOf(identityPaths);
        this.userStateLookup = userStateLookup;
    }

    /** 总开关当前取值。仅供测试与启动期自检读取，不参与判定。 */
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // ① 总开关。关着 ⇒ 不判白名单、不碰会话，直接放行。
        if (!enabled) {
            return true;
        }

        // ② 白名单。⚠️ 用 URI 减去 context-path，而不是 getServletPath() —— 后者在
        //    「按后缀/路径匹配」的配置下会被改写过，而排除表按完整业务路径书写。
        //    ⚠️ uri 在第 ④ 档还要用，故在这里就先算出来（不是为 ② 一个人算的）。
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        if (isWhitelisted(uri)) {
            return true;
        }

        // ③ 凭据（**授权**口径）。Token 优先；无 token 时看 cookie 会话，但 role=3 的 cookie
        //    在这一档就被折成未登录 —— 管理员身份只能由 token 承载（理由见 AuthSession.authorize）。
        //    ⚠️ 判据只有 AuthSession 一处：本闸不再自己 getSession()/读属性（那是本笔之前的写法），
        //    否则「闸认的会话」与「端点认的会话」会成为两处可能分歧的判据。
        AuthSession.Identity identity = AuthSession.authorize(request);
        if (identity != null) {
            // ⑤ 封禁闸（C3 块 2 用户块新增）：身份有效，但**这个账号已被封** ⇒ 拒。
            //    位置刻意在 ③ 之内、④ 之前：封禁的账号连「说出你是谁」（/user/me）也不该拿到
            //    ⇒ 前端 401 出口把他踢回登录页，登录那一步再由 loginSelect 答 4 告诉他为什么。
            if (isBanned(identity)) {
                writeNotLoggedIn(response);
                return false;
            }
            // ⑥ 角色闸（TD-64 · 块 7 收尾）：只对 ADMIN_ONLY_PATHS 生效。
            //    未登录在上面 ③ 已经 401；走到这里说明身份有效但没有管理员角色 ⇒ 403。
            if (isAdminOnly(uri) && !isAdmin(identity)) {
                writeForbidden(response);
                return false;
            }
            return true;
        }

        // ④ 身份识别例外：授权口径不通过，但识别口径拿得到身份时放行 —— 只对 /user/me 那条路径
        //    （见类注释第 ④ 条与 WebAuthConfig.AUTH_IDENTITY_PATHS）。
        if (isIdentityPath(uri) && AuthSession.identify(request) != null) {
            return true;
        }

        writeNotLoggedIn(response);
        return false;
    }

    /**
     * 第 ⑤ 档判据：这个身份对应的账号是否被封。
     *
     * <p>⚠️ <b>只判 1（客户）/ 2（商家）两档，{@code role=3}（管理员）豁免</b> —— 与
     * {@link IdentityGuard} 对管理员的豁免同一姿态。理由是一条具体的失效模式：本闸一旦把
     * 管理员也纳进来，而 {@code /admin/accounts/user/ban} 的管理端用户列表里**管理员行也在**
     * （{@code UserGovernanceDao.selectUsers} 不筛 {@code usertype}），一次误点就会把
     * **整个管理面**锁死，而默认 profile 下 {@code /admin/**} 无角色闸（TD-18）、
     * 也没有第二条能解封的路 —— 只能手改 SQL。封禁的语义是「治理平台上的使用者」，
     * 不是「管理员互相封」。
     *
     * <p>⚠️ 取号口径与 {@code UserService.businessId} 同源（商家取 {@code merchantid}，
     * 其余取 {@code customerid}）—— ⚠️ **选列这件事不在本类做**，{@link UserStateLookup#isBanned}
     * 的第二个参数就是那条判据的唯一落点（role 2 ⇒ {@code merchantid}，其余 ⇒ {@code customerid}）。
     * {@code userStateLookup == null}（切片 / 测试直构）⇒ 一律放行。
     */
    private boolean isBanned(AuthSession.Identity identity) {
        if (userStateLookup == null) {
            return false;
        }
        Integer role = identity.role();
        if (role == null || role == AuthSession.ROLE_ADMIN) {
            return false;
        }
        return userStateLookup.isBanned(identity.userId(), role);
    }

    /**
     * 路径是否命中白名单。包级可见，供测试直接调用（{@code /health/db} 在 smoke profile 下
     * 无 handler，MockMvc 送不进去，见类注释）。
     */
    boolean isWhitelisted(String uri) {
        if (uri == null) {
            return false;
        }
        for (String pattern : whitelist) {
            if (MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 路径是否命中「身份识别例外」表（第 ④ 档）。与 {@link #isWhitelisted} 同一手法、同一可见性 ——
     * 供测试直接调用（那几条用例要逐条钉「例外只放宽识别、不放宽业务」）。
     */
    boolean isIdentityPath(String uri) {
        if (uri == null) {
            return false;
        }
        for (String pattern : identityPaths) {
            if (MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写 401。⚠️ 显式设 charset：{@code msg} 含中文，不声明 charset 时容器可能按平台默认编码
     * 输出（本机为 GBK），前端按 UTF-8 解会得到乱码 —— 且只在某些机器上复现。
     */
    private void writeNotLoggedIn(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(BODY_NOT_LOGGED_IN);
    }

    /** 写 403（已登录、角色不够）。编码口径同 {@link #writeNotLoggedIn}。 */
    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(BODY_FORBIDDEN);
    }

    /** 路径是否只允许管理员（见 {@link #ADMIN_ONLY_PATHS}）。 */
    private boolean isAdminOnly(String uri) {
        if (uri == null) {
            return false;
        }
        for (String pattern : ADMIN_ONLY_PATHS) {
            if (MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /** 管理员判据：角色号必须显式等于 {@link AuthSession#ROLE_ADMIN}（{@code null} 不算）。 */
    private static boolean isAdmin(AuthSession.Identity identity) {
        return identity.role() != null && identity.role() == AuthSession.ROLE_ADMIN;
    }
}
