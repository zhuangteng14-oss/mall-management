package com.minke.mallmanagement.message.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * friend 的**承载层** —— 两个读方法（{@code @Cacheable}）+ 写路径的失效面
 * （{@code @CacheEvict}，见 {@link #evictPair(String, String)}）。B1 第四个 Service。
 *
 * <p>与 {@link GoodsCacheService} / {@link UserCacheService} 同一套路：只抽被缓存的读路径
 * 所需的极薄 Service，不全面重构分层（cache-layer.md §11「新增 Service 层」）；写方法体仍留在
 * Controller（friendAdd / friendUp / friendDe 的入参校验、分支与兜底都不动）。
 *
 * <h2>【两个 cacheName，且**不能**合并】</h2>
 *
 * <ul>
 *   <li>{@code friendList}（{@link #listFriends(String)}）—— 键 = 列 {@code customerid}，
 *       过滤 {@code if_friend = 1} = 「我的好友」。</li>
 *   <li>{@code friendRequests}（{@link #listRequests(String)}）—— 键 = 列 {@code friendid}，
 *       过滤 {@code if_friend = 0} = 「我的待处理申请」。</li>
 * </ul>
 *
 * <p>合并成一个 cacheName 的后果**比 goods 族更危险**：goods 族两个投影的值形状不同，合并后
 * {@code Cache#get(key, type)} 会抛 {@code IllegalStateException}（**响亮**）；而本处两个投影的
 * 值形状**完全相同**（都是 {@code List<Map>}）⇒ 同一个键串（例如 {@code "222"}）先缓存了
 * 「222 的好友」再读「222 的申请」，会**直接把好友列表当作申请列表返回**，**不抛异常、静默串味**。
 * 已登记 cache-layer.md §11。
 *
 * <h2>【不变量：形参角色必须与写接口一致 —— 这是 evict 能闭合的前提】</h2>
 *
 * 三个写接口的入参角色统一为：{@code customerId} = 关系行左列（{@code customerid}，申请人侧）、
 * {@code friendId} = 右列（{@code friendid}，被申请人/接受者侧）。本类两个读方法的形参**同名同角色**：
 * {@code listFriends(customerId)} 的键就是列 {@code customerid}、{@code listRequests(friendId)}
 * 的键就是列 {@code friendid}。若将来把某个读方法的形参换边（例如把 {@code listFriends} 的形参
 * 改叫 {@code friendId}），{@link #evictPair(String, String)} 的键映射会**静默反向** ——
 * 清的是别人的键，本键陈旧到 TTL。
 *
 * <h2>【⚠️ 缓存值是**跨表投影**，失效面只对 friend 表闭合】</h2>
 *
 * 值由 {@link #userInfoById(String)} 组装，读了**三张表**：{@code friend}（决定成员）+
 * {@code user}（username / usertype / gender / id）+ {@code customerimage} / {@code merchantimage}
 * （image）。{@link #evictPair(String, String)} 只覆盖 **friend 表**的写；对 {@code user} 与
 * 两张 image 表的写**不闭合**（写者入参构造不出受影响的 viewer 键，实例：{@code updateAvatar}
 * 的 {@code WHERE username = ?}）⇒ 那部分只能 TTL 自愈。详见 §11。
 */
@Service
public class FriendCacheService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * {@code friendSelect} 的读路径。SQL 与取值逻辑逐字搬运自原 Controller
     * （读接口 {@code M:316-338}、内层 {@code M:341-380}），**唯一的行为改动**是内层不再吞异常
     * （见下）。cacheName = {@code friendList}、key = {@code #customerId}。
     *
     * <p>⚠️ {@code sync = true} 是单飞的**开关**（不加则 {@code TwoLevelCache.get(key, Callable)}
     * 是死代码，20 并发冷读会打 20 次 DB —— 实测见 {@link GoodsTypeCacheService#listTypes()}）。
     * 它与 {@code unless} **不可同用**，且只允许单 {@code cacheNames}（此处均满足）。
     *
     * <p>⚠️ **本方法不上负缓存，也不该上**：0 行在这里返回的是 {@code list: []}（**成功空值**，
     * 原 Controller 即如此），不是 {@link UserCacheService} 那种「失败 marker」。它虽然非 null，
     * 但**照样被 @Cacheable 缓存** ⇒ 同 key 的第二次读不再打 DB ⇒ 防穿透在「重复打同一键」这一层
     * 已经成立，只是值形状不是 null。故 cache-layer.md §9.1「穿透」行的 null 路径**不由本接口触发**
     * （与 goodsSelect / goodsCodes / productCodePrice 同一类），该行需据此订正。
     *
     * <p>⚠️ **技术异常上抛（不 catch）** —— 沿用 {@code UserCacheService.publicInfo()} 的 C′ 形态。
     * 原 {@code getUserInfoById} 是「catch 吞异常 → 返回 null」（{@code M:376-378}），照搬到被缓存的
     * 方法里会被**放大**：某一行 enrich 失败 ⇒ 该好友被静默跳过 ⇒ 这份「少一个人」的列表被固化到
     * TTL。故本类只保留「0 行 → null → 跳过该行」（**业务**事实），SQL 异常原样上抛（**技术**失败），
     * 由 Controller 既有的 {@code catch} 构造 marker。代价是一条可观测的行为变更（原先「少一个好友
     * 的 success:true」变「整个请求 success:false」），已登记 §11。
     *
     * <p>⚠️ 值仍是**裸 {@code List<Map>}**（内层 {@code SELECT} 显式 5 列）—— 与 §10「不缓存裸 Map」
     * 同一笔欠账；它耦合**具体列名**（列改名/删除后 {@code map.get("列名")} 静默返回 null）。
     *
     * <p>⚠️ **N+1 未修**：本接口是 1 + 2N（外层 1 条 + 每个好友 2 条 —— user 一条 + 按 usertype 走
     * customerimage 或 merchantimage 一条）。缓存把它摊薄为「每 TTL 一次」，是**掩盖而非修复**；
     * 改正需改 SQL（JOIN / IN 批量），超出本笔边界。
     */
    @Cacheable(cacheNames = "friendList", key = "#customerId", sync = true)
    public List<Map<String, Object>> listFriends(String customerId) {
        String sql = "SELECT friendid FROM friend WHERE customerid = ? AND if_friend = 1 AND friendid != ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, customerId, customerId);
        List<Map<String, Object>> friendList = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String friendId = (String) row.get("friendid");
            Map<String, Object> userInfo = userInfoById(friendId);
            if (userInfo != null) {
                // 该 id 查不到 user 行 ⇒ 跳过（业务事实：对方尚未注册）。
                // ⚠️ 这里**没有** catch —— 技术异常必须上抛，见方法注释。
                friendList.add(userInfo);
            }
        }
        return friendList;
    }

    /**
     * {@code selectFriend} 的读路径。SQL 与取值逻辑逐字搬运自原 Controller
     * （读接口 {@code M:246-267}），**唯一的行为改动**同 {@link #listFriends(String)}（内层不吞异常）。
     * cacheName = {@code friendRequests}、key = {@code #friendId}。
     *
     * <p>键用列 {@code friendid}（「谁向我发了申请」的「我」），与 {@link #listFriends(String)}
     * 的键列**不同** —— 这正是两个 cacheName 不能合并的第二个理由（同一个字符串在两族里语义不同）。
     *
     * <p>其余边界（{@code sync = true}、不上负缓存、技术异常上抛、裸 {@code List<Map>}、1 + 2N
     * 未修）与 {@link #listFriends(String)} 逐条相同。
     */
    @Cacheable(cacheNames = "friendRequests", key = "#friendId", sync = true)
    public List<Map<String, Object>> listRequests(String friendId) {
        String sql = "SELECT customerid FROM friend WHERE friendid = ? AND if_friend = 0";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, friendId);
        List<Map<String, Object>> applicants = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String customerId = (String) row.get("customerid");
            Map<String, Object> userInfo = userInfoById(customerId);
            if (userInfo != null) {
                applicants.add(userInfo);
            }
        }
        return applicants;
    }

    /**
     * 原 Controller 的私有 {@code getUserInfoById}（{@code M:341-380}）逐字搬运，
     * **唯一改动是去掉 {@code catch}**（见 {@link #listFriends(String)} 的注释）。
     *
     * <p>⚠️ 本方法**不得**加任何缓存注解，且**必须保持 private**：
     * <ul>
     *   <li>它是 private ⇒ 被上面两个读方法**自调用** ⇒ 即使加了 {@code @Cacheable} 也**静默失效**
     *       （不经过代理），是「机制已写 ≠ 机制生效」的典型入口。</li>
     *   <li>它无独立缓存价值：每行一次查询是 N+1 的本质，只有「多行共享同一 ID」时缓存才有命中。
     *       ⚠️ 但它与 {@code UserCacheService.publicInfo()} 的 SQL **逐字相同**，而好友 id 跨请求
     *       大量重复 ⇒ 未来可让 enrich 改走 {@code userPublic} 缓存把 1 + 2N 摊薄（本笔不做，
     *       已登记 §11 待办）。</li>
     * </ul>
     *
     * <p>⚠️ {@code MessageService} 里仍保留一份**同名同逻辑但保留 catch 的**私有 {@code getUserInfoById}：它还被
     * {@code MessageService.getOrCreateConversation}（未上缓存、本笔不动）使用。两份并存是**有意的**，不是漏删。
     */
    private Map<String, Object> userInfoById(String userId) {
        String sql = "SELECT customerid, merchantid, username, usertype, gender FROM user WHERE customerid = ? OR merchantid = ?";
        List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, userId, userId);
        if (list.isEmpty()) {
            return null;   // 业务：查无此人 ⇒ 调用方跳过该行
        }
        Map<String, Object> user = list.get(0);
        String usertype = (String) user.get("usertype");
        String id;
        if ("客户".equals(usertype)) {
            id = (String) user.get("customerid");
        } else if ("商家".equals(usertype)) {
            id = (String) user.get("merchantid");
        } else {
            id = (String) user.get("customerid");
            if (id == null) {
                id = (String) user.get("merchantid");
            }
        }
        // 获取头像
        String image = null;
        if ("客户".equals(usertype) && id != null) {
            String imgSql = "SELECT image FROM customerimage WHERE customerid = ?";
            List<Map<String, Object>> imgList = jdbcTemplate.queryForList(imgSql, id);
            if (!imgList.isEmpty()) {
                image = (String) imgList.get(0).get("image");
            }
        } else if ("商家".equals(usertype) && id != null) {
            String imgSql = "SELECT image FROM merchantimage WHERE merchantid = ?";
            List<Map<String, Object>> imgList = jdbcTemplate.queryForList(imgSql, id);
            if (!imgList.isEmpty()) {
                image = (String) imgList.get(0).get("image");
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("username", user.get("username"));
        result.put("usertype", usertype);
        result.put("gender", user.get("gender"));
        result.put("image", image);   // 可能为 null，前端会使用默认头像
        return result;
    }

    // ==================== 写路径：两个 cacheName 的唯一失效入口 ====================

    /**
     * {@code friendList} 与 {@code friendRequests} 的**唯一失效入口** —— 显式调用
     * （模板见 cache-layer.md §10「写路径 evict 的单入口」，本类是它的**第三个**实例）。
     *
     * <p>【调用方】三个写接口各一处：{@code friendAdd} / {@code friendUp} / {@code friendDe}
     * （全在 {@code MessageController}）。全仓对 {@code friend} 表的写**只有这 4 条语句**
     * （两处 INSERT、一处 UPDATE、一处 DELETE），无 XML mapper、无触发器/存储过程 ⇒ 写面闭合。
     *
     * <h3>【为什么是三条，以及「双向」是什么】</h3>
     * <ol>
     *   <li>{@code friendList::#customerId} —— {@code friendUp} 的 UPDATE 把行 {@code (A,B)} 从
     *       {@code 0} 改成 {@code 1} ⇒ **A 的好友列表多了 B**。这是「双向」的第一侧。</li>
     *   <li>{@code friendList::#friendId} —— {@code friendUp} 的第二条语句 INSERT {@code (B,A,1)}
     *       ⇒ **B 的好友列表多了 A**。这是「双向」的第二侧。</li>
     *   <li>{@code friendRequests::#friendId} —— {@code friendAdd}（INSERT {@code (A,B,0)}）、
     *       {@code friendUp}（该行 0→1 后不再是待处理）、{@code friendDe}（DELETE 该行）
     *       三者都改 **B 的待处理申请**。</li>
     * </ol>
     * <p>⚠️ 与之相对的两个**易错点**（实测确认，与 §2 原表相反）：{@code friendAdd} 与
     * {@code friendDe} **不影响** {@code friendList} —— 它们只写 {@code if_friend = 0} 的行，
     * 而 {@code friendSelect} 的 SQL 带 {@code AND if_friend = 1}。此处仍统一清该键：多清一次是
     * 保守且无害的（代价 = 下次冷读回填一次 1 + 2N），换来「三个调用点同名同形一行、可 grep 计数」。
     *
     * <h3>【为什么能精确清，而不是 allEntries】</h3>
     * 两个 cacheName 的键**都只有一个分量**，且三个写接口的 WHERE / VALUES **都含它**
     * （{@code friendList} ← {@code customerid}，{@code friendRequests} ← {@code friendid}）
     * ⇒ 写入集合 ⊆ 键集合，闭合（§10 evict 规则第 3 条）。
     * ⚠️ 该判据用的是 userSelect 那笔**收紧后**的版本：问的不是「键分量能否从入参构造」，而是
     * 「写者能否在**该键不在其入参里**的情况下改掉该键的答案」—— 对 friend 表的写：不能（闭合）；
     * 对 user / image 表的写：能（不闭合，故那部分无 evict，只能 TTL 自愈，见类注释）。
     *
     * <h3>【⚠️ 调用点判据：无条件放在唯一出口之前 —— 本笔最锋利的一条】</h3>
     * 三个写接口都只有一个 {@code return result;}，必须把调用放在它**之前且无条件**，尤其
     * **不能**塞进 {@code friendUp} 的 {@code if (updated > 0)} 分支里。原因：
     * <ul>
     *   <li>{@code friendUp} **没有 {@code @Transactional}**（实测 {@code M:270-291}），而它有
     *       **两条**写语句。若 UPDATE 提交后 INSERT 抛错 ⇒ 走 {@code catch} ⇒ 返回
     *       {@code {success:false}}，**但库已经被改了**（{@code (A,B)} 已成 {@code if_friend = 1}）。
     *       把清缓存挂在「成功分支」上会让这条路径漏清。</li>
     *   <li>⚠️ 这与 {@code UserCacheService.evictPublic()} 的论证**方向相反** —— 那里靠
     *       「{@code loginAdd} 是 {@code @Transactional}，异常路径回滚 ⇒ 不改库」证明唯一出口
     *       覆盖 100%；这里是「无事务 ⇒ 异常路径**可能已经改库**」⇒ 出口必须**无条件**覆盖。</li>
     * </ul>
     *
     * <h3>【三条约束 —— 少一条即静默失效，与前两个实例同款】</h3>
     * <ol>
     *   <li>必须 **{@code public}**：Spring 代理只拦 public 方法。</li>
     *   <li>形参名必须叫 **{@code customerId}** / **{@code friendId}**：{@code key="#…"} 按名求值
     *       （{@code -parameters} 已开）。改名会报 {@code IllegalArgumentException: Null key returned
     *       for cache operation}（本笔实测过这一报文）。</li>
     *   <li>**不得在本类内部调用**：自调用不走代理 ⇒ 注解完全不执行且不报错。</li>
     * </ol>
     *
     * <p>【为什么是 {@code @Caching} 而不是三条 {@code @CacheEvict}】Spring 的 {@code @CacheEvict}
     * **不是 {@code @Repeatable}**，同一方法上写两个同名注解**不可编译**；而本处需要**同一 cacheName
     * 的两条不同 key**（{@code friendList} 出现两次）⇒ 只能靠 {@code @Caching} 容器。
     * ⚠️「同一 cacheName 在同一 {@code @Caching} 里出现两次、两条 evict 是否都执行」是**本仓首个实例**，
     * 已用独立 scratch 实测通过（真实 {@code CacheInterceptor} + {@code ConcurrentMapCacheManager}：
     * 两条 key 均被清、无关键未误伤），并在运行时于真实两级缓存上复验。
     *
     * <p>【为什么 {@code friendAdd} / {@code friendDe} 也清 {@code friendList}】见上文「易错点」：
     * 保持三点同形可 grep，代价可忽略。若将来把 {@code friendUp} 改成**同时更新反向行**
     * （今天它靠 INSERT 新增一行，故 {@code friendRequests::#customerId} 不必清），需回来扩清单。
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "friendList", key = "#customerId"),
            @CacheEvict(cacheNames = "friendList", key = "#friendId"),
            @CacheEvict(cacheNames = "friendRequests", key = "#friendId")
    })
    public void evictPair(String customerId, String friendId) {
        // 方法体刻意留空：失效面全部由注解声明，本方法只负责「被调到」。
        // ⚠️ 不要在这里写业务逻辑 —— 它会在**任何**调用路径上执行，且抛异常会打断调用方的写接口。
    }
}
