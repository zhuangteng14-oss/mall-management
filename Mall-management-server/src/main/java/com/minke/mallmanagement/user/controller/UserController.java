package com.minke.mallmanagement.user.controller;

import com.minke.mallmanagement.shared.web.AuthInterceptor;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * user 域的 Web 层（三层重构第三笔：user 域）。**只剩路由 + 入参绑定 + 一行委托**；
 * 业务规则、上传编排、响应组装全在 {@link UserService}，SQL 全在
 * {@code com.minke.mallmanagement.user.dao.UserDao}。
 *
 * <h2>【本类收 7 个端点 —— 不含 {@code userSelect}】</h2>
 * {@code loginSelect} / {@code loginAdd} / {@code getUserInfo} / {@code updateAvatar} /
 * {@code updateUserInfo}，加鉴权笔 2 的 {@code me} / {@code logout}。⚠️ {@code /message/userSelect}
 * <b>不归本笔</b>：它的路由在 {@code MessageController}、服务在 {@code UserCacheService}，
 * 只是碰巧读 {@code user} 域的表 —— 归 message 域那一笔。
 *
 * <h2>【鉴权笔 2：{@code loginSelect} 写会话，{@code me} / {@code logout} 读会话】</h2>
 * 会话的读写全部经 {@link AuthSession}，本类**不出现**属性名字面量
 * （{@link AuthInterceptor#SESSION_USER_ID} 只有它一处引用）。三条纪律：
 * <ul>
 *   <li>{@code loginSelect} <b>只在认证成功且拿到业务号时</b>才 {@code request.getSession()} ——
 *       失败的登录**不发会话**（故形参是 {@link HttpServletRequest} 而**不是**
 *       {@link jakarta.servlet.http.HttpSession}：后者由 MVC 注入，会无条件创建）；
 *       HTTP 契约一字未动（返回仍是裸 {@code int}，见下「例外 1/5」）。</li>
 *   <li>{@code /user/me} 无会话 ⇒ <b>401 + 与闸逐字节相同的响应体</b>。用的是
 *       {@link AuthInterceptor#BODY_NOT_LOGGED_IN} 这个**共享常量**，不是
 *       {@code Result.fail("未登录")} —— 后者实测序列化出 {@code msg} 在前，字节不同
 *       （理由写在那个常量的 javadoc 里）。</li>
 *   <li>{@code /user/logout} **不进白名单**、也不需要登录：无会话时是 no-op（幂等），
 *       由 {@code AuthSession.logout} 的 {@code getSession(false)} 保证「不创建再销毁」。</li>
 * </ul>
 * ⚠️ 开关 {@code app.auth.enabled} 的默认值**仍是 {@code false}**（笔 2 不翻开关），
 * 故 {@code /user/me} 的 401 分支在默认配置下**确实可达** —— 它不依赖闸。
 *
 * <h2>【新增 2 端点对「33 个端点」口径的影响 —— 只登记，不改 {@code Result}}】</h2>
 * 全仓端点 33 → 35。{@code Result} 类注释里「33 个端点里 30 个返回 Map」与「33 处响应 Map」
 * 两处口径**在本笔之后都已过时**（无论按哪个口径数）。本笔**不改 {@code Result}**，
 * 只在 {@code progress.md §5-Q} 登记 —— 理由与「本笔不改更新记录行」同：避免夹带。
 *
 * <h2>【为什么本类现在**没有**任何注解式行为（{@code @Transactional} / 缓存）】</h2>
 * 三层前 {@code loginAdd} 上挂着全仓<b>唯一一处真 {@code @Transactional}</b>
 * （{@code UserController:57}），Boot 的 {@code TransactionAutoConfiguration} 为它建了事务代理
 * ⇒ 本 bean 当时<b>是 CGLIB 代理</b>。注解随方法体下沉到 {@link UserService#loginAdd} 后，
 * 本类恢复无代理 —— 这个**状态翻转**由
 * {@code MallManagementApplicationTests.userControllerIsNotAopProxied} 钉住
 * （该断言的 javadoc 记了翻转前后的两次实测）。
 *
 * <p>⚠️ 别图省事把 {@code @Transactional} 或缓存注解挂回本类：本类的职责边界是
 * 「把 HTTP 参数交给 Service」，事务边界 = 一次业务操作，属于 Service。
 *
 * <h2>【{@code @RequestParam} 的五个默认值逐字未改】</h2>
 * {@code image} 的 {@code http://localhost:8088/img/default_user.png}、
 * {@code customerid} / {@code merchantid} 的 {@code 暂无数据}，
 * 参数名 {@code userid}（对应 Java 变量 {@code id}）、{@code age}（{@code Integer}）等
 * 全部照搬。⚠️ 调用点按 P2.6（2026-10-05）实测重列：{@code loginSelect}/{@code loginAdd} 在
 * {@code LoginPage.vue}、{@code updateAvatar}/{@code updateUserInfo} 在 {@code UserInfoPage.vue}
 * —— {@code getUserInfo} 的 3 个前端调用点已全部改走 {@code /user/me}（本笔后为 0）。
 *
 * <h2>【响应体：{@code updateAvatar} / {@code updateUserInfo} 已换成 {@link Result}】</h2>
 * 只换**返回类型**：{@code Map<String,Object>} → {@code Result<Map<String,Object>>}，
 * 方法体由 {@code return userService.x(…)} 改为 {@code return Result.of(userService.x(…))}。
 * Service 返回值仍是 {@code Map}，URL 与参数一字未动；因 §「字节等价」由
 * {@code Result.of} 逐条 put 保证，线上响应体逐字节不变。
 * ⚠️ 本类另 3 个端点**故意不换**（{@code loginSelect} / {@code loginAdd} / {@code getUserInfo}）——
 * 它们是 {@link Result} 类注释里「例外 5 项」中的 3 项，理由逐条写在那里。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    //这是一个接口（用于返回登录界面对应的数据，决定了此次登录去往哪个端【客户端or商家端】）
    // ⚠️ 例外 1/5：返回裸 int，A3 的 A-S1-3 断言 "1".equals(body.trim()) 逐字；
    //    前端 res.text() 后比 "0"/"1"/"2"。包成 JSON ⇒ 门禁红 + 前端断。见 Result 类注释。
    @PostMapping("/loginSelect")
    public int loginSelect(
            @RequestParam("username") String username,       // 对应数据库 login 表的 UserName
            @RequestParam("password") String password,       // 对应数据库 login 表的 PassWord
            HttpServletRequest request                       // 鉴权笔 2：认证成功时在这里写会话
    ) {
        int role = userService.loginSelect(username, password);

        // ⚠️ 只有认证成功（1 = 客户 / 2 = 商家）才继续；role = 0 时下面两步都不执行，
        //    也就**不会**有会话被建出来（这正是形参取 HttpServletRequest 而非 HttpSession 的理由）。
        if (role == 1 || role == 2) {
            String businessId = userService.businessId(username, role);
            if (businessId != null) {
                // 会话里存的是**业务号**（customerid / merchantid），不是登录名 —— 见 AuthSession。
                AuthSession.login(request.getSession(), businessId);
            }
        }
        return role;
    }

    //这是一个接口（用于最早期的注册，只更新最基础的用户信息）
    // ⚠️ 例外 2/5：返回裸 String，前端 text === "添加成功！" / === "0" 逐字。见 Result 类注释。
    @PostMapping("/loginAdd")
    public String loginAdd(
            @RequestParam(value = "image", defaultValue = "http://localhost:8088/img/default_user.png") String imageAdd,
            @RequestParam("username") String usernameAdd,
            @RequestParam("usertype") String usertypeAdd,
            @RequestParam("userid") String id,
            @RequestParam(value = "customerid", defaultValue = "暂无数据") String customeridAdd,
            @RequestParam(value = "merchantid", defaultValue = "暂无数据") String merchantidAdd,
            @RequestParam("password") String password,
            @RequestParam("age") Integer ageAdd,
            @RequestParam("gender") String genderAdd
    ) {
        // ⚠️ 三层前挂在本方法上的 @Transactional 已随方法体下沉到 UserService.loginAdd ——
        // 不在这里补回来。事务边界 = 一次业务操作，属于 Service。
        return userService.loginAdd(imageAdd, usernameAdd, usertypeAdd, id,
                customeridAdd, merchantidAdd, password, ageAdd, genderAdd);
    }

    //这是一个接口（用于返回对应前端页面的用户信息）
    // ⚠️ 例外 3/5：失败时返回 null（HTTP 200 **空体**）；P2.6（2026-10-05）前三个前端调用点
    //    靠外层 catch 兜 SyntaxError —— 三处**已全部改走 /user/me**（失败也是可解析 JSON），
    //    故今日 **0 个调用方**。保留它、不改 Result 的理由见 service 层 getUserInfo。见 Result 类注释。
    @PostMapping("/getUserInfo")
    public Map<String, Object> getUserInfo(
            @RequestParam("username") String username,
            @RequestParam("password") String password
    ) {
        return userService.getUserInfo(username, password);
    }

    // 这是一个接口（用于更改前端用户的头像）
    @PostMapping("/updateAvatar")
    public Result<Map<String, Object>> updateAvatar(
            @RequestParam("username") String username,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        return Result.of(userService.updateAvatar(username, file));
    }

    //这是一个接口（用于更新个人信息）
    @PostMapping("/updateUserInfo")
    public Result<Map<String, Object>> updateUserInfo(
            @RequestParam("username") String username,
            @RequestParam("email") String email,
            @RequestParam("address") String address,
            @RequestParam("selfintroduction") String selfintroduction
    ) {
        return Result.of(userService.updateUserInfo(username, email, address, selfintroduction));
    }

    // ==================== 鉴权笔 2 新增：会话侧的两个端点 ====================

    /**
     * 「我是谁」：按**会话**返回当前登录用户的**完整资料**（P2.6 扩列，见下）。
     *
     * <p>与 {@link #getUserInfo} **并存、不替代**：那个是**凭据驱动**（每次都传
     * username+password），本端点是**会话驱动**（凭 cookie 识别身份）—— P2.6（2026-10-05）
     * 起前端三处调用点（登录页 + 商家上架页 + 资料页）**已全部改走本端点**，{@code getUserInfo}
     * 在前端**再无调用方**（端点保留的理由见那里）。
     *
     * <h3>三种出口</h3>
     * <ul>
     *   <li><b>无会话 / 会话里没有 userId ⇒ 401</b>，响应体 = {@link AuthInterceptor#BODY_NOT_LOGGED_IN}
     *       （**共享常量**，与闸的 401 逐字节相同 —— 客户端分不出这个 401 是闸发的还是端点发的，
     *       这是有意的）。⚠️ 用字面量而**不是** {@code Result.fail("未登录")}：后者的键序由
     *       HashMap 桶序决定，实测得到 {@code {"msg":…,"success":…}}，字节不同。</li>
     *   <li><b>有会话、但查不到这个业务号 ⇒ HTTP 200 + {@code {success:false,msg:"用户不存在"}}</b>。
     *       注意这是 **200**，不是 404：会话是有效的，只是它指向的用户在库里没了。
     *       ⚠️ 与 {@code getUserInfo} 失败时的「200 空体」**形状不同** —— 本端点答的是**可解析**的
     *       JSON（前端若照 {@code getUserInfo} 的写法 {@code await res.json()} 不会炸）。</li>
     *   <li><b>有会话且查得到 ⇒ HTTP 200 + {@code {success:true, …}}</b>（顶层平铺，键集 = {@code user}
     *       表整行 + 头像 {@code image}，即 {@link UserService#getUserInfo} 的同一张 Map）。</li>
     * </ul>
     *
     * <p>⚠️ 用 {@link HttpServletRequest} 取会话（{@code AuthSession.currentUserId}）——
     * 无会话的请求**不会**因为打这个端点而获得一个会话。
     */
    @PostMapping("/me")
    public ResponseEntity<Object> me(HttpServletRequest request) {
        String userId = AuthSession.currentUserId(request);
        if (userId == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                    .body(AuthInterceptor.BODY_NOT_LOGGED_IN);
        }

        Map<String, Object> userInfo = userService.meInfo(userId);
        if (userInfo == null) {
            return ResponseEntity.ok(Result.fail("用户不存在"));
        }
        return ResponseEntity.ok(Result.ok(userInfo));
    }

    /**
     * 退出登录。**幂等**：没有会话时也答成功（前端可以无脑调）。
     *
     * <p>⚠️ <b>刻意不在白名单里</b>（{@code WebAuthConfig.AUTH_WHITELIST} 一行不动）：
     * 它不需要「未登录也能打」这条豁免 —— 无会话时它本来就是 no-op，而放它进白名单
     * 等于给未来留一个「不需要身份就能触达」的写面。开关打开时的已知接缝（下表）。
     *
     * <p>⚠️ <b>已知接缝（登记，不修）</b>：开关 {@code app.auth.enabled=true} 时，
     * 一个**会话已过期**的用户点「退出」会被闸先拦成 401（拿不到本方法那个 200）。
     * 实践上无害（客户端对 401 与 200 都应清本地态），但要写下来：这是「闸在 Controller 之前」
     * 的必然结果，不是本方法的缺陷。
     *
     * <p>响应体是 {@code {success:true, msg:"已退出"}} —— 键序由 HashMap 决定
     * （实测为 {@code msg} 在前），本端点**不承诺**字节序，前端按 {@code data.success} 取值。
     */
    @PostMapping("/logout")
    public Result<Map<String, Object>> logout(HttpServletRequest request) {
        AuthSession.logout(request);

        Map<String, Object> payload = new HashMap<>();
        payload.put("msg", "已退出");
        return Result.ok(payload);
    }
}
