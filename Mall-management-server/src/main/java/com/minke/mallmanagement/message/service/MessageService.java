package com.minke.mallmanagement.message.service;

import com.minke.mallmanagement.message.dao.MessageDao;
import com.minke.mallmanagement.shared.lock.DistributedLockService;
import com.minke.mallmanagement.shared.lock.DistributedLockService.LockName;
import com.minke.mallmanagement.user.service.UserCacheService;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

import java.util.*;
import java.time.LocalDateTime;

/**
 * message 域的服务层（三层重构第四笔：message 域）。**业务规则 + 锁编排 + 响应组装**；
 * 路由与入参绑定留在 {@link com.minke.mallmanagement.message.controller.MessageController}，
 * 数据访问在 {@link MessageDao}（**但本域不是「数据出口收成一处」** —— 见下）。
 *
 * <h2>【迁自哪里 —— 逐字搬运，不是重写】</h2>
 * 10 个方法体整段搬自 {@code MessageController}（三层前的 417 行版），含 SQL → DAO 调用、
 * try-catch、分支与**每一条文案**。搬运的验收判据是「响应逐字段与改前一致」。
 * <p>⚠️ <b>方法名与本域每个端点一一对应</b>（{@code getConversations} / {@code getMessageHistory} /
 * {@code sendMessage} / {@code getOrCreateConversation} / {@code userSelect} / {@code friendAdd} /
 * {@code selectFriend} / {@code friendUp} / {@code friendDe} / {@code friendSelect}）——
 * 与 {@code OrderService} / {@code GoodsService} / {@code UserService} 同一命名法，
 * 使「一个端点 → 一个 Service 方法」可用名字直接对上，无需查表。
 *
 * <h2>【三族端点：本节是 message 域的地图】</h2>
 * <table border="1">
 *   <caption>端点 → Service 方法 → 数据出口</caption>
 *   <tr><th>族</th><th>端点（8 个，共 10 个方法）</th><th>数据出口</th></tr>
 *   <tr><td><b>消息侧</b></td><td>{@code conversations} / {@code history} / {@code send} /
 *       {@code getOrCreateConversation}</td><td>{@link MessageDao}</td></tr>
 *   <tr><td><b>userSelect</b></td><td>{@code userSelect}</td>
 *       <td>{@link UserCacheService#publicInfo(String)}（<b>user 域</b>的缓存读路径）</td></tr>
 *   <tr><td><b>好友侧</b></td><td>{@code friendAdd} / {@code selectFriend} / {@code friendUp} /
 *       {@code friendDe} / {@code friendSelect}</td>
 *       <td>写路径 {@link MessageDao}；读路径 {@link FriendCacheService}；失效 {@code evictPair}</td></tr>
 * </table>
 * <p>⚠️ <b>「不单开 friend 域」是**已裁**的决定</b>（{@code ddd-package-plan.md:42} §3）：好友侧
 * 只有 1 张表、5 个端点，且与消息侧共用 {@code friend}/{@code message} 两张表里的同一族语义，
 * 单开一域会造出「一个 60 行的 friend 域」+ 跨域调用。故好友侧的 SQL 也进 {@link MessageDao}
 * <b>同一个类</b>，本类也承载好友侧的业务 —— 代价见下方 {@code @Transactional} 一节。
 *
 * <h2>【本域**不是**「数据出口收成一处」—— 与 order / goods / user 三域的形状差】</h2>
 * 那三域是「域内所有 SQL 都收进唯一 DAO」；本域**不是**，因为三层前本域就有 3 条读路径
 * **不在 Controller 里**（在缓存 Service 里），按「DAO 只收 Controller 内联语句」的裁决
 * （{@code three-layers.md:40}，商品笔确立），它们<b>照旧不进 DAO</b>：
 * <ul>
 *   <li>{@link FriendCacheService} 的 2 个 {@code @Cacheable} 读路径 + 1 个私有 enrich
 *       （{@code listFriends} / {@code listRequests} / {@code userInfoById}）—— 5 条语句；</li>
 *   <li>{@link UserCacheService#publicInfo(String)} —— 1 条语句。</li>
 * </ul>
 * ⇒ <b>本域的数据出口总数 = {@link MessageDao} + FriendCacheService + UserCacheService。</b>
 * 知道这一点才能读懂下方「为什么本类只是<b>转发</b>而非改写缓存路径」。
 *
 * <h2>【与 user 域的跨域调用：两处，形状不同，别混】</h2>
 * <ol>
 *   <li><b>{@code userSelect} → {@link UserCacheService#publicInfo(String)}</b>（<b>经缓存</b>）。
 *       这是 user 域**有缓存注解**的那条读路径，路由却挂在 {@code /message/userSelect}
 *       ——「路由在 message、Service 归 user」。裁决①（{@code ddd-package-plan.md:318-322}）
 *       已定 <b>{@code userSelect} 不拆</b>：它服务的是「消息页选人」的交互，路由归属 message；
 *       而它的读路径属于 user 域的能力（{@code userPublic} 缓存 + 负缓存），故归
 *       {@code UserCacheService}。本类只做**响应组装**。</li>
 *   <li><b>{@code getOrCreateConversation} → {@link MessageDao#selectUserPublicInfo(String)}
 *       + 头像两查</b>（<b>不经缓存</b>，直读 {@code user}/{@code customerimage}/
 *       {@code merchantimage}）。这是三层前就写在 Controller 里的跨域直读，本笔只挪位置、
 *       <b>不引缓存</b>。{@code ddd-package-plan.md:185} 已裁「message 引 user：**引**，
 *       方向正确、无环，保留」。</li>
 * </ol>
 * ⚠️ 注意 ①② 对**同一段用户信息投影**用了两种取法（缓存 / 直读）—— 这是**既有的**不一致，
 * <b>不是本笔造的</b>，其归并属另一笔（{@code cache-layer.md §11} 已登记）；本笔是纯搬运。
 *
 * <h2>⚠️ 【{@code @Transactional} 禁令：只管被锁的临界区，但结构断言扫全类 —— 已接受的代价】</h2>
 * 订单笔立下的禁令（{@code three-layers.md §D.1}）只约束<b>被打锁的方法体</b>。本域只有一个
 * 临界区：{@code friendAdd} → {@link #friendAddLocked}（经
 * {@link DistributedLockService#runLocked(LockName, String, java.util.function.Supplier)}）。
 * 它与订单的 {@code addLocked} 是同一状态：<b>三点都与 {@code OrderService} 逐条对应</b>：
 * <ol>
 *   <li>锁在 {@code runLocked} 的 {@code finally} 里放，事务提交在方法返回**之后** ⇒
 *       理由①（锁外事务）**永远成立**；</li>
 *   <li>三层前临界区是传给锁的 lambda、内部调 Controller 私有方法 —— 理由②（跨 bean
 *       「想加也加不上」）当时成立；<b>分层后链变成
 *       {@code lambda → MessageService.friendAddLocked → MessageDao.insertFriendRequest /
 *       selectFriendPair}** ⇒ 第一次真的跨了 bean，理由②<b>失效</b> ⇒ 改由
 *       {@code DistributedLockServiceTest} 的结构断言接手。</li>
 * </ol>
 * ⚠️ <b>但本域与订单域有一条**形状差**</b>：那个结构断言按「扫<b>整类</b>」实现，故扫
 * {@code MessageService} 时会把<b>非临界区</b>的方法（尤其 {@link #friendUp}）也一起扫进去。
 * 这是「不单开 friend 域」的直接代价，<b>已被明确接受</b>：
 * <ul>
 *   <li>对 {@code friendUp} 而言，它**没有**打锁，那笔禁令**在语义上不适用于它** ——
 *       {@code friendUp} 的「两条写语句无事务」是 C#2 那一类（{@code goodsAdd} 同族）；
 *       ⚠️ <b>本笔**刻意不动它**</b>（纯搬运：改它就是改行为，超出本笔边界），故它仍无事务、
 *       仍靠「唯一出口无条件清缓存」自保（见 {@link #friendUp} 的注释）。</li>
 *   <li>⇒ 结构断言<b>读作</b>「本类目前整体无事务」而不是「本类禁止事务」。将来若有人要给
 *       {@code friendUp} 补事务（那是**另一笔**），他撞到的会是这条断言 —— 那时应把它改成
 *       「按方法白名单」而不是绕过它。<b>本笔已知此张力，故意保留</b>。</li>
 * </ul>
 *
 * <h2>【本类**不被代理** —— {@code messageControllerIsNotAopProxied} 仍绿的前提】</h2>
 * 三个可能与 AOP 有关的注解（{@code @Cacheable} / {@code @Caching(evict)} / {@code @Transactional}）
 * 全都不在本类身上：缓存那两个在 {@link FriendCacheService} / {@link UserCacheService} 身上，
 * 事务那个被上面一条禁令挡着。⇒ {@code MessageService} 是普通 bean、{@code MessageController}
 * 注入它之后仍**不是** CGLIB 代理 ⇒ {@code MallManagementApplicationTests.messageControllerIsNotAopProxied}
 * 保持绿。<b>本类一旦挂上任何一个缓存注解，那条断言立刻变红</b> —— 这是它存在的一半理由。
 *
 * <h2>【谁保护本笔】</h2>
 * ⚠️ <b>本笔的护栏极薄，必须如实知道</b>：A3 只打本域的 {@code /message/friendAdd} 一处，
 * 且其门禁 {@code A-B2-1} 是三路析取（另两路来自 goodstype / order），<b>{@code friendAdd}
 * 单独坏了也仍可能绿</b>；加之 CI 的 {@code concurrency} job 是
 * {@code continue-on-error: true}（{@code ci.yml:117}）。⇒ <b>本笔没有行为级的回归网</b>，
 * 正确性靠「逐字搬运 + 13 条 SQL 字面量逐字比对」人工保证。补行为断言的提议见 {@code progress.md}
 * 的本笔记录。
 */
@Service
public class MessageService {

    private final MessageDao messageDao;
    private final UserCacheService userCacheService;
    private final FriendCacheService friendCacheService;
    private final DistributedLockService distributedLockService;

    public MessageService(MessageDao messageDao,
                          UserCacheService userCacheService,
                          FriendCacheService friendCacheService,
                          DistributedLockService distributedLockService) {
        this.messageDao = messageDao;
        this.userCacheService = userCacheService;
        this.friendCacheService = friendCacheService;
        this.distributedLockService = distributedLockService;
    }

    // ===================== 消息相关接口（基于会话表） =====================

    /**
     * 会话列表：每个对方一条，附 `unread`。
     *
     * <p>⚠️ 三层前这里是**内层 N+1**（对每个会话再查一次已读时间）—— 本笔**原样保留**，
     * 不合并成一条 JOIN：合并会同时改列别名与 `unread` 的算法，超出「逐字搬运」。
     * <p>⚠️ `conv.put("unread", …)` 就地改 DAO 返回的行 Map ⇒ **依赖返回的 Map 可变**。
     * 这正是探针验过 `LinkedCaseInsensitiveMap` 可变性的原因（见 {@link MessageDao} 类注释）。
     */
    public Map<String, Object> getConversations(String currentId, String currentType) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = messageDao.selectConversations(currentId);

        for (Map<String, Object> conv : list) {
            String otherId = (String) conv.get("other_id");
            String otherType = (String) conv.get("other_type");
            Object lastTimeObj = conv.get("last_time");
            LocalDateTime lastMsgTime = null;
            if (lastTimeObj != null) {
                if (lastTimeObj instanceof Timestamp) {
                    lastMsgTime = ((Timestamp) lastTimeObj).toLocalDateTime();
                } else if (lastTimeObj instanceof LocalDateTime) {
                    lastMsgTime = (LocalDateTime) lastTimeObj;
                }
            }

            List<Map<String, Object>> readList =
                    messageDao.selectLastReadTime(currentId, currentType, otherId, otherType);

            boolean unread = false;
            if (readList.isEmpty()) {
                unread = lastMsgTime != null;
            } else {
                Object lastReadObj = readList.get(0).get("last_read_time");
                LocalDateTime lastRead = null;
                if (lastReadObj instanceof Timestamp) {
                    lastRead = ((Timestamp) lastReadObj).toLocalDateTime();
                } else if (lastReadObj instanceof LocalDateTime) {
                    lastRead = (LocalDateTime) lastReadObj;
                }
                unread = lastMsgTime != null && (lastRead == null || lastMsgTime.isAfter(lastRead));
            }
            conv.put("unread", unread);
        }

        result.put("success", true);
        result.put("list", list);
        return result;
    }

    /**
     * 历史消息 + **写副作用**（更新最后阅读时间）。
     *
     * <p>⚠️ 名字是「查询」、实际有副作用 —— 既有形态（{@code api-contract.md §1} 已登记
     * 「读接口 ≠ 无副作用」），本笔不动。两条语句之间**没有事务**，顺序不可颠倒：
     * 原实现先 SELECT（快照 list）后 UPSERT；若颠倒，本次请求自己新写的已读时间会改变
     * `unread` 的判据（虽然本方法不返回 unread，但并发下另一请求的 conversations 会看到）。
     * 逐字搬运即保持原顺序。
     */
    public Map<String, Object> getMessageHistory(String currentId, String currentType,
                                                 String otherId, String otherType) {
        // 查询历史消息
        List<Map<String, Object>> list = messageDao.selectMessagesBetween(currentId, otherId);

        // 更新最后阅读时间（使用 MySQL 的 NOW()，避免类型转换）
        messageDao.upsertLastReadTime(currentId, currentType, otherId, otherType);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", list);
        return result;
    }

    /**
     * 发消息。⚠️ 与其他写接口不同，本方法**没有**缓存失效出口 —— 它写的是 {@code message} 表，
     * 而本域的两族缓存（{@code friendList} / {@code friendRequests}）都不含 message 表的内容。
     * <b>别顺手加 evictPair</b>：那会白打一次 Redis（下游也没有任何断言要求它）。
     */
    public Map<String, Object> sendMessage(String fromId, String fromType, String toId, String toType,
                                           String content) {
        Map<String, Object> result = new HashMap<>();
        try {
            int rows = messageDao.insertMessage(fromId, fromType, toId, toType, content);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "发送成功");
            } else {
                result.put("success", false);
                result.put("msg", "发送失败");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 打开与某人的会话：对方信息 + 双方历史消息。⚠️ <b>三层前就无前端调用方、无测试、无 A3 覆盖</b>
     * （{@code selection-status.md} 口径）⇒ 本笔搬运时它**完全无观测**，故 {@code @RequestParam}
     * 四参数的名字/顺序与 {@link #getMessageHistory} 一致这件事只能人工比对，没有断言会替你发现。
     *
     * <p>⚠️ 与 {@link #getMessageHistory} 共用同一段「取双方消息」SQL（{@link MessageDao#selectMessagesBetween}
     * 是合并后的唯一实现）—— 本笔唯一的 SQL 合并点，理由见 {@link MessageDao} 类注释。
     */
    public Map<String, Object> getOrCreateConversation(String currentId, String currentType,
                                                       String otherId, String otherType) {
        Map<String, Object> result = new HashMap<>();

        // 获取对方用户信息（用于前端显示用户名）
        Map<String, Object> otherUser = getUserInfoById(otherId);
        if (otherUser == null) {
            result.put("success", false);
            result.put("msg", "对方用户不存在");
            return result;
        }

        // 查询双方的历史消息（按时间升序）
        List<Map<String, Object>> messages;
        try {
            messages = messageDao.selectMessagesBetween(currentId, otherId);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
            return result;
        }

        result.put("success", true);
        result.put("otherUser", otherUser);   // 包含 id, username, usertype, gender, image
        result.put("messages", messages);
        return result;
    }

    // ===================== 好友相关接口 =====================

    //搜索好友时显示数据
    // 读路径（SELECT + 取首行）在 UserCacheService.publicInfo()，本方法只保留路由 + 响应组装。
    // 【本方法在 userSelect 上缓存这件事里的角色】只组装响应，**不挂任何缓存注解** ——
    //   · Service 返回 null（业务 0 行）→ 本方法构造 marker ⇒ 该 null 已被缓存（负缓存，防 ID 枚举穿透）
    //   · Service 抛异常（技术失败）→ 本方法同样构造 marker ⇒ 但**异常不进缓存**（@Cacheable 遇异常不写）
    // 两条路径的响应体与引入 UserCacheService 之前**逐字段相同**（零契约变更）。
    // ⚠️ 代价：技术异常与「查无此人」在响应**文案**上仍不可区分（既有瑕疵，见 §11，本笔不动）。
    // ⚠️ 缓存体是 Service 返回的裸行 Map，形状耦合**具体列名**（§10 边界第 4 条形态②）。
    public Map<String, Object> userSelect(String userId) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> user;
        try {
            user = userCacheService.publicInfo(userId);
        } catch (Exception e) {
            e.printStackTrace();
            user = null;
        }
        if (user != null) {
            String usertype = (String) user.get("usertype");
            String id;
            if ("客户".equals(usertype)) {           // 改为中文
                id = (String) user.get("customerid");
            } else if ("商家".equals(usertype)) {     // 改为中文
                id = (String) user.get("merchantid");
            } else {
                id = (String) user.get("customerid");
                if (id == null) id = (String) user.get("merchantid");
            }
            Map<String, Object> resultUser = new HashMap<>();
            resultUser.put("id", id);
            resultUser.put("username", user.get("username"));
            resultUser.put("usertype", usertype);
            resultUser.put("gender", user.get("gender"));
            resultUser.put("image", null);   // 无 image 字段，设为 null
            result.put("success", true);
            result.put("user", resultUser);
            return result;
        }
        result.put("success", false);
        result.put("msg", "用户不存在");
        return result;
    }

    //为好友表添加待处理的数据
    // ⚠️ B2：方法体在 Redisson 锁下执行，锁 key = pairKey(customerId, friendId)（**无序对**，
    //    理由见 DistributedLockService.pairKey 的 javadoc —— 本接口的查重 SQL 是 OR 双向的，
    //    按入参顺序拼会让 A→B 与 B→A 各持一把锁），指标 tag = friend-add。
    //    锁在这里只把「查 + 插」合成原子（既有查重已正确，锁补的是 TOCTOU）；
    //    拿不到锁 ⇒ fail-open 直行原路径 —— 与下面「唯一出口」注释的约定 ① 一致（该分支不改库，
    //    故天然不必动 evictPair）。
    // 三层后：锁的调用点从 Controller 搬进本方法 —— **行为零变化**（同一个模板方法、同一个 key
    // 构造、同一个 action）。⚠️ 但这一搬让临界区第一次跨了 bean（理由②失效，见类注释）。
    public Map<String, Object> friendAdd(String customerId, String friendId) {
        return distributedLockService.runLocked(LockName.FRIEND_ADD,
                DistributedLockService.pairKey(customerId, friendId),
                () -> friendAddLocked(customerId, friendId));
    }

    /**
     * {@code friendAdd} 的临界区：**原方法体逐字未动**（B2 的边界），含唯一出口处的 evictPair。
     *
     * <p>⚠️ 本方法**不得**加 {@code @Transactional} —— 理由见类注释的两条（锁外事务 / 跨 bean 调用）。
     * 一句话：事务的提交发生在方法返回**之后**，而锁在 {@code runLocked} 的 {@code finally} 里、
     * 即方法返回**之前**已释放 —— 后进者拿到锁后的查重（{@link MessageDao#selectFriendPair}）
     * 在 RR 下读的是自己事务开始时的快照、**看不到前一个未提交的行** ⇒ 仍插 2 行。不加事务时
     * autocommit 让 INSERT 在释放锁**之前**就提交，这正是查重成立的前提。
     *
     * <p>⚠️ 该临界区靠**数据库唯一约束兜底**是**不成立**的：{@code friend} 表只有主键、
     * **无** {@code (customerid, friendid)} 上的唯一键 —— 建表语句与实际列见
     * {@link MessageDao#selectFriendPair} 的说明。这正是 B2 必须上锁、且 A3 的 {@code A-B2-1}
     * 会观察到重复行的原因（order 域的 {@code uk_order_orderId} 那条兜底在本域**没有对应物**）。
     */
    private Map<String, Object> friendAddLocked(String customerId, String friendId) {
        Map<String, Object> result = new HashMap<>();
        if (customerId.equals(friendId)) {
            result.put("success", false);
            result.put("msg", "不能添加自己为好友");
            return result;
        }
        List<Map<String, Object>> existing = messageDao.selectFriendPair(customerId, friendId);
        if (!existing.isEmpty()) {
            int ifFriend = (Integer) existing.get(0).get("if_friend");
            if (ifFriend == 1) {
                result.put("success", false);
                result.put("msg", "已经是好友");
            } else {
                result.put("success", false);
                result.put("msg", "已发送过好友申请，请等待对方处理");
            }
            return result;
        }
        try {
            int rows = messageDao.insertFriendRequest(customerId, friendId);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "好友申请已发送");
            } else {
                result.put("success", false);
                result.put("msg", "添加失败");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        // ⚠️ 本方法的唯一出口 —— 三个写接口共用同一模式（最完整的注释在 friendUp 的唯一出口上方），
        //    若新增 return 请确认：① 该分支不改库，或 ② 在该分支前也调 evictPair()。
        // 本方法只有 1 条写语句、在 try 内、catch 吞异常（该语句抛错时未改库）⇒ 唯一出口覆盖
        // 100% 的可能改库路径。⚠️ 上面**两处**提前 return（`customerId.equals(friendId)` 与
        // 「已存在」分支）都只跑 SELECT、未改库 ⇒ 不必清。
        friendCacheService.evictPair(customerId, friendId);
        return result;
    }

    //查询好友表内对应申请人的待处理信息
    // 读路径在 FriendCacheService.listRequests()，本方法只保留路由 + 响应组装（同 friendSelect 那套）。
    // 不挂缓存注解；0 行同样是 `{success:true, list:[]}`（**成功空值**，不上负缓存）。
    public Map<String, Object> selectFriend(String friendId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> applicants = friendCacheService.listRequests(friendId);
            result.put("success", true);
            result.put("list", applicants);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 更新好友表内的数据，让两个用户互为好友。
     *
     * <p>⚠️ 本方法是**纯搬运**，其中包含一处**既有缺陷的形态**：两条写语句、**无
     * {@code @Transactional}** ⇒ UPDATE 提交后 INSERT 抛错时库已被部分修改，但返回
     * {@code {success:false}}。本笔**刻意不修**（修它就是改行为，属另一笔）—— 只保证缓存失效
     * 出口**无条件**覆盖，使那条路径的缓存不脏。完整推演见方法内注释。
     * <p>⚠️ 第二条写语句的实参是**反序**的（{@code friendId, customerId}）——
     * 见 {@link MessageDao#insertMutualFriend} 的警告：写正了也编译、也执行，只是静默造出错的边。
     */
    public Map<String, Object> friendUp(String customerId, String friendId) {
        Map<String, Object> result = new HashMap<>();
        try {
            int updated = messageDao.acceptFriendRequest(customerId, friendId);
            if (updated > 0) {
                messageDao.insertMutualFriend(friendId, customerId);
                result.put("success", true);
                result.put("msg", "已添加为好友");
            } else {
                result.put("success", false);
                result.put("msg", "申请不存在或已处理");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        // ⚠️ 本方法的唯一出口 —— **无条件**放在这里，**不能**塞进上面的 if (updated > 0) 分支里。
        //    理由：本方法**没有 @Transactional**，却有**两条**写语句。若 UPDATE 提交后 INSERT 抛错
        //    ⇒ 走 catch ⇒ 返回 {success:false}，**但库已经被改了**（(customerId, friendId) 已成
        //    if_friend = 1）⇒ 把清缓存挂在「成功分支」上会让这条路径漏清。
        //    ⚠️ 这与 UserCacheService.evictPublic() 的论证**方向相反**：那里靠「loginAdd 是
        //    @Transactional、异常路径回滚 ⇒ 不改库」证明唯一出口覆盖 100%；这里是「无事务 ⇒
        //    异常路径**可能已经改库**」⇒ 出口必须无条件覆盖。
        //    三个写接口共用同一模式，若新增 return 请确认：① 该分支不改库，或 ② 在该分支前也调它。
        friendCacheService.evictPair(customerId, friendId);
        return result;
    }

    /**
     * 删除好友申请。⚠️ 同 {@link #friendUp}：唯一出口无条件清缓存。
     * 只删 {@code if_friend = 0} 的行（{@link MessageDao#deleteFriendRequest}），已是好友的行不动。
     */
    public Map<String, Object> friendDe(String customerId, String friendId) {
        Map<String, Object> result = new HashMap<>();
        try {
            int rows = messageDao.deleteFriendRequest(customerId, friendId);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "已拒绝申请");
            } else {
                result.put("success", false);
                result.put("msg", "申请不存在");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        // ⚠️ 本方法的唯一出口 —— 三个写接口共用同一模式（最完整的注释在 friendUp 的唯一出口上方），
        //    若新增 return 请确认：① 该分支不改库，或 ② 在该分支前也调 evictPair()。
        // 本方法只有 1 条写语句、在 try 内、catch 吞异常（该语句抛错时未改库）⇒ 唯一出口覆盖 100%。
        friendCacheService.evictPair(customerId, friendId);
        return result;
    }

    //打印好友界面的好友信息
    // 读路径（外层 SELECT + 逐行 enrich）在 FriendCacheService.listFriends()，本方法只保留路由 + 响应组装。
    // 【本方法在 friendSelect 上缓存这件事里的角色】只组装响应，**不挂任何缓存注解** ——
    //   · 正常路径：Service 返回列表（**含空列表 `[]`**）⇒ 该列表已被缓存（cacheName = friendList）
    //   · 技术异常：Service **上抛** ⇒ 缓存不写入 ⇒ 由下面**既有**的 catch 构造 marker
    // ⚠️ 与 userSelect 的差异：这里 0 行返回的是 `{success:true, list:[]}`（**成功空值**），
    //   不是失败 marker ⇒ **不需要**（也不应该）翻译成 null 去套负缓存。空列表照样被缓存 ⇒
    //   同 key 再读不再打 DB，防穿透在「重复打同一键」这一层已经成立。
    // ⚠️ 缓存体是裸 List<Map>，形状耦合**具体列名**（§10 边界第 4 条形态②）。
    public Map<String, Object> friendSelect(String customerId) {
        Map<String, Object> result = new HashMap<>();
        // 增加 AND friendid != ? 防止返回自己（该条件在 Service 的 SQL 里，逐字未动）
        try {
            List<Map<String, Object>> friendList = friendCacheService.listFriends(customerId);
            result.put("success", true);
            result.put("list", friendList);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    // ===================== 私有辅助 =====================

    /**
     * 根据 id 取用户信息（**直读** user 表 + 一次头像查，不经缓存）。
     *
     * <p>⚠️ 只被 {@link #getOrCreateConversation} 调用（三层前亦同）。它是**同一段用户信息投影的
     * 第三份**（另两份在 {@link UserCacheService#publicInfo(String)} 与 {@link FriendCacheService}
     * 的私有 {@code userInfoById}）—— 三份并存是既有的、本笔不合并（§11 已登记）。
     * <p>⚠️ 失败语义**原样保留**：整体 try-catch 吞异常 → 返回 {@code null}（调用方翻成
     * 「对方用户不存在」）⇒ 技术异常与业务 0 行在文案上仍不可区分（既有瑕疵）。
     */
    private Map<String, Object> getUserInfoById(String userId) {
        try {
            List<Map<String, Object>> list = messageDao.selectUserPublicInfo(userId);
            if (!list.isEmpty()) {
                Map<String, Object> user = list.get(0);
                String usertype = (String) user.get("usertype");
                String id;
                if ("客户".equals(usertype)) {
                    id = (String) user.get("customerid");
                } else if ("商家".equals(usertype)) {
                    id = (String) user.get("merchantid");
                } else {
                    id = (String) user.get("customerid");
                    if (id == null) id = (String) user.get("merchantid");
                }
                // 获取头像
                String image = null;
                if ("客户".equals(usertype) && id != null) {
                    List<Map<String, Object>> imgList = messageDao.selectCustomerImageByCustomerId(id);
                    if (!imgList.isEmpty()) image = (String) imgList.get(0).get("image");
                } else if ("商家".equals(usertype) && id != null) {
                    List<Map<String, Object>> imgList = messageDao.selectMerchantImageByMerchantId(id);
                    if (!imgList.isEmpty()) image = (String) imgList.get(0).get("image");
                }
                Map<String, Object> result = new HashMap<>();
                result.put("id", id);
                result.put("username", user.get("username"));
                result.put("usertype", usertype);
                result.put("gender", user.get("gender"));
                result.put("image", image); // 可能为null，前端会使用默认头像
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
