package com.minke.mallmanagement.message.controller;

import com.minke.mallmanagement.message.service.MessageService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * message 域的 Web 层（三层重构第四笔：message 域）。**只剩路由 + 入参绑定 + 一行委托**；
 * 业务规则、锁编排、响应组装全在 {@link MessageService}，SQL 全在
 * {@code com.minke.mallmanagement.message.dao.MessageDao}（但本域**不是**「数据出口收成一处」
 * —— 另有两个缓存读路径在 {@code FriendCacheService} / {@code UserCacheService}，见 Service 类注释）。
 *
 * <h2>【本类收 10 个端点，三族】</h2>
 * <ul>
 *   <li><b>消息侧 4</b>：{@code conversations} / {@code history} / {@code send} /
 *       {@code getOrCreateConversation}</li>
 *   <li><b>userSelect 1</b>：{@code userSelect} —— 路由在本类、服务在 {@code UserCacheService}
 *       （user 域）。裁决①（{@code ddd-package-plan.md:318-322}）已定<b>不拆</b>，理由见 Service 类注释。</li>
 *   <li><b>好友侧 5</b>：{@code friendAdd} / {@code selectFriend} / {@code friendUp} /
 *       {@code friendDe} / {@code friendSelect}</li>
 * </ul>
 *
 * <h2>【本类现在**不持有任何依赖**，除 MessageService】</h2>
 * 三层前本类持 4 个字段：{@code JdbcTemplate MessagejdbcTemplate}、
 * {@code UserCacheService}、{@code FriendCacheService}、{@code DistributedLockService}。
 * 本笔把前三个的**使用点**随方法体下沉到 {@link MessageService}，{@code JdbcTemplate} 更是
 * 直接消失（SQL 全在 {@code MessageDao}）⇒ 本类只剩一个构造器注入的 {@code MessageService}。
 * <p>⚠️ <b>本类仍**不被 CGLIB 代理**（{@code messageControllerIsNotAopProxied} 需保持绿）</b>：
 * 三个可能与 AOP 相关的注解（{@code @Cacheable} / {@code @Caching(evict)} / {@code @Transactional}）
 * 一个都不在本类、也一个都不在 {@link MessageService} 身上，故注入链上没有代理来源。
 * <b>别图省事把缓存注解挂回本类</b>。
 *
 * <h2>【{@code @RequestParam} 的名字与顺序逐字未改】</h2>
 * 10 个端点的参数名（{@code currentId} / {@code currentType} / {@code otherId} / {@code otherType} /
 * {@code fromId} / {@code fromType} / {@code toId} / {@code toType} / {@code content} /
 * {@code userId} / {@code customerId} / {@code friendId}）全部照搬 —— 前端按这些名字传参，
 * 改一个就断一个。⚠️ 本域**没有任何默认值**（与 user 域那五个 {@code defaultValue} 不同）。
 * <p>⚠️ 尤其：{@code getOrCreateConversation} 与 {@code history} 的四参数名/序必须一致 ——
 * 该端点三层前就<b>无前端调用方、无测试、无 A3 覆盖</b>，改错了没有任何断言会报。
 */
@RestController
@RequestMapping("/message")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    // ===================== 消息相关接口（基于会话表） =====================

    @PostMapping("/conversations")
    public Map<String, Object> getConversations(@RequestParam("currentId") String currentId,
                                                @RequestParam("currentType") String currentType) {
        return messageService.getConversations(currentId, currentType);
    }

    @PostMapping("/history")
    public Map<String, Object> getMessageHistory(@RequestParam("currentId") String currentId,
                                                 @RequestParam("currentType") String currentType,
                                                 @RequestParam("otherId") String otherId,
                                                 @RequestParam("otherType") String otherType) {
        return messageService.getMessageHistory(currentId, currentType, otherId, otherType);
    }

    @PostMapping("/send")
    public Map<String, Object> sendMessage(@RequestParam("fromId") String fromId,
                                           @RequestParam("fromType") String fromType,
                                           @RequestParam("toId") String toId,
                                           @RequestParam("toType") String toType,
                                           @RequestParam("content") String content) {
        return messageService.sendMessage(fromId, fromType, toId, toType, content);
    }

    @PostMapping("/getOrCreateConversation")
    public Map<String, Object> getOrCreateConversation(
            @RequestParam("currentId") String currentId,
            @RequestParam("currentType") String currentType,
            @RequestParam("otherId") String otherId,
            @RequestParam("otherType") String otherType) {
        return messageService.getOrCreateConversation(currentId, currentType, otherId, otherType);
    }

    // ===================== 好友相关接口 =====================

    //搜索好友时显示数据（读路径在 UserCacheService.publicInfo()，见 Service 的 userSelect）
    @PostMapping("/userSelect")
    public Map<String, Object> userSelect(@RequestParam("userId") String userId) {
        return messageService.userSelect(userId);
    }

    //为好友表添加待处理的数据（B2 的锁在 Service.friendAdd 里）
    @PostMapping("/friendAdd")
    public Map<String, Object> friendAdd(@RequestParam("customerId") String customerId,
                                         @RequestParam("friendId") String friendId) {
        return messageService.friendAdd(customerId, friendId);
    }

    //查询好友表内对应申请人的待处理信息（读路径在 FriendCacheService.listRequests()）
    @PostMapping("/selectFriend")
    public Map<String, Object> selectFriend(@RequestParam("friendId") String friendId) {
        return messageService.selectFriend(friendId);
    }

    //更新好友表内的数据，让两个用户互为好友
    @PostMapping("/friendUp")
    public Map<String, Object> friendUp(@RequestParam("customerId") String customerId,
                                        @RequestParam("friendId") String friendId) {
        return messageService.friendUp(customerId, friendId);
    }

    //删除掉不通过好友申请时。对应的待处理的好友申请数据
    @PostMapping("/friendDe")
    public Map<String, Object> friendDe(@RequestParam("customerId") String customerId,
                                        @RequestParam("friendId") String friendId) {
        return messageService.friendDe(customerId, friendId);
    }

    //打印好友界面的好友信息（读路径在 FriendCacheService.listFriends()）
    @PostMapping("/friendSelect")
    public Map<String, Object> friendSelect(@RequestParam("customerId") String customerId) {
        return messageService.friendSelect(customerId);
    }
}
