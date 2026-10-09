package com.minke.mallmanagement.notification.controller;

import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统通知的**接收面**（C3 块 1 · I2）—— 三端（用户端 / 商家端 / 管理端）共用的收件箱端点。
 *
 * <h2>【为什么接收面在**公共层**，而发送面在 {@code admin/}】</h2>
 * 收件箱是**每个登录者都要读自己的**，跟角色无关；发送是**平台方独有的权力**（{@code c3-modules.md} §零）。
 * ⇒ 本类住 {@code notification/}（公共层），发送端点住
 * {@code admin/controller/AdminNotificationController}。方向单向：admin → notification，
 * **不能反过来**（那会让公共层依赖 admin，块 6 的触发点就引不动了）。
 *
 * <h2>【与 {@code message} 的分工：通知单向，消息双向】</h2>
 * <ul>
 *   <li>{@code message} —— **两个人之间的会话**：{@code from_id} / {@code to_id} 都有意义，
 *       列表要按「对端」分组，已读时刻落在**会话**上（{@code user_conversation_read}）。</li>
 *   <li>{@code notification} —— **平台 → 个人的单向投递**：没有 {@code from}，已读时刻落在**行**上，
 *       列表就是一条时间线。两者混一张表会让收件箱被公告淹没，且公告场景下 {@code from_id} 无意义。</li>
 * </ul>
 * ⚠️ 前端两个入口也**分开**（顶栏「消息」与「通知」是两个按钮、两个红点），别合并。
 *
 * <h2>【参数一律 {@code required=false} + Service 内手判 —— 这是绕坑，不是偷懒】</h2>
 * 本类**刻意**不加任何参数约束（{@code @NotBlank} / {@code @Size}），也**不加**类级 {@code @Validated}。
 * 两条既有坑：
 * <ol>
 *   <li>{@code GlobalExceptionHandler} 的 {@code assignableTypes} 只白名单
 *       {@code MessageController} / {@code GoodsController} —— 约束异常在本类会**逃逸成 400 空体**
 *       （前端拿不到 msg）。缺参改由 Service 答 {@code {success:false,msg:"缺少参数：…"}}。</li>
 *   <li>类级 {@code @Validated} 会让 Controller 被 CGLIB 代理，撞
 *       {@code MallManagementApplicationTests} 的 {@code xxxControllerIsNotAopProxied} 断言族。</li>
 * </ol>
 *
 * <h2>【权限：{@link IdentityGuard}，且「解析不出身份 = 放行」不许改】</h2>
 * 四个端点都在**方法体第一行**判 {@code requireSelfOrAdmin(request, toId)} ——
 * 挡在一切业务动作之前。它同时承担「闸关着（smoke / A3）时零影响」这条属性，
 * 理由见 {@link IdentityGuard} 类注释；改那一行 = 换契约，不是修 bug。
 *
 * <p>⚠️ <b>一处**刻意继承**的边界，写在这里以免被当成遗漏</b>：{@code toId} 与
 * {@code identity.userId()} 相同即放行，而 {@code toId} 是「业务号」——库里 {@code customerid}
 * 与 {@code merchantid} 分属两个序列。于是「客户号 == 某商家号」时，理论上可以用自己的
 * {@code toId} 配 {@code toType=merchant} 读到那一位商家的收件箱。既有 {@code MessageController}
 * 的 {@code currentId/currentType} 是**同一形状**（同样不判 type 与角色的对应），故本笔**照抄不擅改**
 * —— 单独给通知加一条「toType 必须与角色一致」会造出两个同类域之间的分叉，且属鉴权面变更。
 * 登记为观察项，等与 TD-18 一同裁决。
 */
@RestController
@RequestMapping("/notification")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 收件箱列表（时间倒序）。{@code onlyUnread=true} 时只回未读。 */
    @PostMapping("/list")
    public ResponseEntity<Object> list(
            @RequestParam(value = "toId", required = false) String toId,
            @RequestParam(value = "toType", required = false) String toType,
            @RequestParam(value = "onlyUnread", required = false) Boolean onlyUnread,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        // ⚠️ 第一行：收件箱只能看**自己**的（管理员豁免）。
        if (!IdentityGuard.requireSelfOrAdmin(request, toId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(notificationService.list(toId, toType, onlyUnread, limit)));
    }

    /** 未读数（顶栏红点轮询用，5s 一次 ⇒ 走 covering index）。 */
    @PostMapping("/unreadCount")
    public ResponseEntity<Object> unreadCount(
            @RequestParam(value = "toId", required = false) String toId,
            @RequestParam(value = "toType", required = false) String toType,
            HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, toId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(notificationService.unreadCount(toId, toType)));
    }

    /** 标记一条已读。幂等 —— 重复调用回 {@code updated:0} 且 {@code success:true}。 */
    @PostMapping("/read")
    public ResponseEntity<Object> read(
            @RequestParam(value = "toId", required = false) String toId,
            @RequestParam(value = "toType", required = false) String toType,
            @RequestParam(value = "id", required = false) Long id,
            HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, toId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(notificationService.markRead(toId, toType, id)));
    }

    /** 全部标记已读。 */
    @PostMapping("/readAll")
    public ResponseEntity<Object> readAll(
            @RequestParam(value = "toId", required = false) String toId,
            @RequestParam(value = "toType", required = false) String toType,
            HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, toId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(notificationService.markAllRead(toId, toType)));
    }
}
