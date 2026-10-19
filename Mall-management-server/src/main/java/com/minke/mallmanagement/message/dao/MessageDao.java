package com.minke.mallmanagement.message.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * message 域的**内联 SQL 出口**（三层重构第四笔：message 域）。
 *
 * <h2>【收什么、不收什么】</h2>
 * 只收三层前**内联在 {@code MessageController} 方法体里**的语句 —— 14 处调用点、13 个方法。
 * 本条判据与 {@link com.minke.mallmanagement.goods.dao.GoodsDao} / {@link com.minke.mallmanagement.user.dao.UserDao}
 * 一致（裁决②）。
 *
 * <p>⚠️ <b>本域与 user / order 两域**不同**：本类**不是**本域唯一的数据访问点。</b>
 * 另外两个数据访问点**不进本类**：
 * <ul>
 *   <li>{@link com.minke.mallmanagement.message.service.FriendCacheService}（{@code listFriends} /
 *       {@code listRequests} / 其私有 {@code userInfoById}）—— 5 条语句；</li>
 *   <li>{@link com.minke.mallmanagement.user.service.UserCacheService#publicInfo(String)} —— 1 条语句。</li>
 * </ul>
 * 二者都是**缓存读路径**，按商品笔的裁决「DAO 只收 Controller 内联的语句、缓存读路径不进 DAO」
 * （{@code three-layers.md#pack-three-layers-must-read}，理由见 {@code GoodsDao} 类 javadoc）留在原处。
 * 故本类的覆盖范围是「{@code MessageController} 的 10 个端点」，不含那两条缓存路径。
 *
 * <h2>【SQL 字面量逐字未变 —— 这是本笔的验收判据，不是修辞】</h2>
 * 14 处调用点**全部从原方法体整段搬来**。三处必须注意：
 * <ul>
 *   <li>⚠️ <b>本笔合并了一对同文本字面量</b>：三层前 {@code MessageController:95-97}
 *       （{@code history}）与 {@code :155-157}（{@code getOrCreateConversation}）各写了一遍
 *       <b>逐字相同</b>的「取双方之间的消息」。本笔合并为
 *       {@link #selectMessagesBetween(String, String)} <b>一个方法</b>。</li>
 *   <li>⚠️ <b>本域的 SQL 一律不带 {@code mall_management_sql.} 前缀</b>（实测 <b>0 处</b>，
 *       见 {@code three-layers.md §F#1}）⇒ 本笔<b>不动</b>「全仓硬编码 schema 前缀 39 处」
 *       这个口径。见下节。</li>
 *   <li>{@link #insertMutualFriend(String, String)} 的两列值来自 {@code friendUp} 的
 *       <b>反向</b>入参 —— 见该方法的警告。</li>
 * </ul>
 *
 * <h2>【为什么这次合并是允许的 —— 与 {@code UserDao} 的正相反】</h2>
 * {@link com.minke.mallmanagement.user.dao.UserDao} 类 javadoc 有一条相反的裁决：那里 {@code selectRoleByCredentials} 与
 * {@code selectRoleForProfile} 的 SQL 文本**也**完全相同，却**刻意保留两份**，理由是
 * 「{@code UserDao} 的 16 处 schema 前缀是『全仓 39 处』这个被多处文档引用的验收基线的一部分，
 * 合并会让 39 变 38，当场把刚对齐的 {@code GoodsDao:35} 与 {@code OrderDao:22} 重新弄矛盾」。
 *
 * <p>⚠️ <b>那条理由在本域不成立</b>：本域 schema 前缀 = <b>0</b> ⇒ 合并<b>不动 39</b>。
 * 且此处的两个调用点**语义也相同**（都是「取 A 与 B 双方之间的消息」），参数顺序亦逐字相同
 * （{@code currentId, otherId, otherId, currentId}）—— 与 {@code UserDao} 那对
 * 「调用点语义不同（登录判别 / 资料页校验）」也不同。
 * ⇒ <b>合并是净收益；保留才是无理由的冗余。</b>
 * <p>📌 反过来提醒：<b>将来若有谁把「跨四域清除 schema 前缀」那一笔做了</b>（39 → 0），
 * {@code UserDao} 那对冗余就失去了存在理由 —— 那时才是合并它们的时候。本类的合并
 * **不需要**任何配套改动。
 *
 * <h2>【为什么是 {@code JdbcClient}】</h2>
 * 与 {@code OrderDao} / {@code GoodsDao} / {@code UserDao} 同款：D#4 已定「短期 {@code JdbcClient}
 * （零新依赖），长期 jOOQ」。构造器**收 {@code JdbcTemplate} 而不是 {@code JdbcClient}**，
 * 是为了让 {@code @WebMvcTest} 切片里既有的 {@code @MockitoBean JdbcTemplate} 继续喂给本类
 * —— 切片不加载 Boot 的 {@code JdbcClient} 自动配置。
 *
 * <p>⚠️ <b>本域的 {@code queryForList → listOfRows} 替换经过独立探针实测</b>（2026-10-02，
 * 真 MySQL + Spring 6.2.6 的 {@code JdbcTemplate} / {@code JdbcClient} 对跑），逐条成立：
 * <ul>
 *   <li>两者返回的行 Map **同一个实现类** {@code org.springframework.util.LinkedCaseInsensitiveMap}
 *       ⇒ 大小写不敏感这一性质<b>两边一致</b>；</li>
 *   <li>{@code keySet()} 与 {@code entrySet()} 的键**逐字相同**（含别名列 {@code other_id} /
 *       {@code other_type} / {@code last_time}，与混合大小写的 {@code UserName} / {@code PassWord}）
 *       ⇒ Jackson 序列化出的 JSON 字段名逐字不变；</li>
 *   <li>行 Map **可变**（{@code put} / {@code get} / {@code remove} 均通过）—— 这是
 *       {@code conversations} 就地 {@code conv.put("unread", …)} 的前提；</li>
 *   <li>空结果两边都返回**空 List**（非 null），{@code VALUES} 含 NULL 的列两边都取到 {@code null}。</li>
 * </ul>
 * 探针**未覆盖**写路径（那需要真的改库，而本库带 P4 种子行数基线）—— 写侧沿用
 * {@code OrderDao} / {@code GoodsDao} 已有的 {@code JdbcClient} 先例。
 *
 * <h2>⚠️ 【本类**不受** {@code @Transactional} 禁令约束，但临界区**受**】</h2>
 * 订单域的禁令（{@code three-layers.md §D.1}）只管**被打锁的临界区**。本域只有一个临界区
 * ——{@code friendAdd}（{@code runLocked} 在 {@code MessageService} 上）—— 它的调用链是
 * {@code lambda → MessageService.friendAddLocked → 本类的 insertFriendRequest / selectFriendPair}。
 * ⚠️ <b>三层之后这条链第一次真的跨了 bean</b>（三层前临界区是传给
 * {@code DistributedLockService} 的 lambda、内部调 Controller 的私有方法）⇒
 * 「想加也加不上」这条理由失效 ⇒ 由 {@code DistributedLockServiceTest} 的
 * 结构断言（扫描清单含 {@code MessageService}）接手，理由与订单笔逐字相同。
 * <p>本类本身**不加也不该加** {@code @Transactional}（纯搬运），但**不属于禁令的扫描目标**
 * —— 扫描目标是 {@code MessageService}。
 */
@Repository
public class MessageDao {

    private final JdbcClient jdbcClient;

    public MessageDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 读：消息与会话 ====================

    /**
     * {@code conversations} 的**外层**查询：按对方聚合出会话列表（含最后一条消息时间）。
     *
     * <p>⚠️ 四个参数都是同一个 {@code currentId} —— 原 SQL 里 {@code CASE WHEN from_id = ?}
     * 与 {@code WHERE from_id = ? OR to_id = ?} 各占一个占位符。整段逐字搬运，**不简化**：
     * 改写这条 SQL 会同时动 {@code other_id} / {@code other_type} 两个别名，而
     * {@code MessageService} 按名取值、{@code conversations} 还会就地 {@code put("unread")}。
     *
     * <p>⚠️ 结果**不含** {@code unread} 字段 —— 那是 {@code MessageService} 逐行算出来再 {@code put} 的。
     */
    public List<Map<String, Object>> selectConversations(String currentId) {
        // 2026-10-19 用户令（消息中心左栏要「摘要」）：在**不调用 `/message/history`** 的前提下
        // 多回一列 `last_content`。⚠️ 不能用 history 取摘要 —— 那个读接口会 UPSERT 已读时间
        // （`MessageService.getMessageHistory` 的既有副作用），列表里逐个调它 = 一进页面
        // 把所有会话都标成已读。窗口函数只读不写，且内容不截断（GROUP_CONCAT 有长度上限）。
        String sql = "SELECT other_id, other_type, last_time, last_content FROM ( " +
                "  SELECT other_id, other_type, create_time AS last_time, content AS last_content, " +
                "    ROW_NUMBER() OVER (PARTITION BY other_id, other_type ORDER BY create_time DESC, id DESC) AS rn " +
                "  FROM ( " +
                "    SELECT " +
                "      CASE WHEN from_id = ? THEN to_id ELSE from_id END AS other_id, " +
                "      CASE WHEN from_id = ? THEN to_type ELSE from_type END AS other_type, " +
                "      create_time, content, id " +
                "    FROM message WHERE from_id = ? OR to_id = ?" +
                "  ) t " +
                ") x WHERE rn = 1 ORDER BY last_time DESC";
        return jdbcClient.sql(sql).params(currentId, currentId, currentId, currentId).query().listOfRows();
    }

    /**
     * {@code conversations} 的**内层**查询（N+1 的 N）：取「我对某个会话」的最后已读时间。
     *
     * <p>⚠️ 0 行是**正常**情形（从未读过）⇒ 调用方 {@code readList.isEmpty()} 分支，不是异常。
     * 本方法照原样返回空 List，**不**把它翻译成异常或 null。
     */
    public List<Map<String, Object>> selectLastReadTime(String currentId, String currentType,
                                                        String otherId, String otherType) {
        String readSql = "SELECT last_read_time FROM user_conversation_read " +
                "WHERE user_id = ? AND user_type = ? AND other_id = ? AND other_type = ?";
        return jdbcClient.sql(readSql).params(currentId, currentType, otherId, otherType).query().listOfRows();
    }

    /**
     * 取「双方之间的消息」，按时间升序。
     *
     * <p>⚠️ <b>本方法是本笔唯一一对同文本字面量的合并结果</b>：三层前
     * {@code MessageController:95-97}（{@code history}）与 {@code :155-157}
     * （{@code getOrCreateConversation}）是**逐字相同**的 SQL 与**相同**的参数顺序。
     * 合并的理由与「为什么这次允许合并不动 39 口径」见类注释。
     *
     * <p>⚠️ 参数顺序是 {@code (currentId, otherId, otherId, currentId)} —— 原 SQL 的
     * OR 两个方向各要一对。写成 {@code (currentId, otherId, currentId, otherId)} 会
     * <b>静默</b>漏掉一个方向（不回错、只是少行），没有任何断言会报。
     */
    public List<Map<String, Object>> selectMessagesBetween(String currentId, String otherId) {
        String sql = "SELECT id, from_id, from_type, to_id, to_type, content, create_time " +
                "FROM message WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?) " +
                "ORDER BY create_time ASC";
        return jdbcClient.sql(sql).params(currentId, otherId, otherId, currentId).query().listOfRows();
    }

    // ==================== 读：用户公开信息（{@code getOrCreateConversation} 用）====================

    /**
     * 按 {@code customerid} 或 {@code merchantid} 取用户行（5 列，**不含 image**）。
     *
     * <p>⚠️ <b>本域读 {@code user} 表是<b>既有</b>事实，不是本笔新造的跨域耦合</b> ——
     * {@code ddd-package-plan.md:185} 已裁「message 引 user：**引** …… 方向正确、无环，保留」。
     *
     * <p>⚠️ <b>这是同一段用户信息投影的<b>第三</b>份</b>（另两份：{@link com.minke.mallmanagement.user.service.UserCacheService#publicInfo(String)}
     * 与 {@link com.minke.mallmanagement.message.service.FriendCacheService} 的私有
     * {@code userInfoById}）。三份**并存是有意的**，归并属另一笔（{@code cache-layer.md §11} 已登记
     * 「让 enrich 改走 {@code userPublic} 缓存」那条待办）—— 本笔只做**纯搬运**，不引缓存。
     *
     * <p>⚠️ 0 行返回**空 List**；调用方 {@code getUserInfoById} 用 {@code !list.isEmpty()} 判，
     * 并把 0 行翻成 {@code null}（业务事实：对方不存在），再由 {@code getOrCreateConversation}
     * 翻成「对方用户不存在」。<b>不要</b>在这里改成 {@code .singleRow()} —— 那会把 0 行变成异常，
     * 改变响应文案。
     */
    public List<Map<String, Object>> selectUserPublicInfo(String userId) {
        String sql = "SELECT customerid, merchantid, username, usertype, gender FROM user "
                + "WHERE customerid = ? OR merchantid = ?";
        return jdbcClient.sql(sql).params(userId, userId).query().listOfRows();
    }

    /**
     * 取**客户**头像。⚠️ 按列 {@code customerid} 查（不是 {@code username}）——
     * 与 {@code UserDao.selectCustomerImage}（按 {@code username}）**是不同的查询**，别互换。
     */
    public List<Map<String, Object>> selectCustomerImageByCustomerId(String customerId) {
        String imgSql = "SELECT image FROM customerimage WHERE customerid = ?";
        return jdbcClient.sql(imgSql).param(customerId).query().listOfRows();
    }

    /** 取**商家**头像。0 行同 {@link #selectCustomerImageByCustomerId}。 */
    public List<Map<String, Object>> selectMerchantImageByMerchantId(String merchantId) {
        String imgSql = "SELECT image FROM merchantimage WHERE merchantid = ?";
        return jdbcClient.sql(imgSql).param(merchantId).query().listOfRows();
    }

    // ==================== 写：会话已读 ====================

    /**
     * {@code history} 的**写副作用**：把「我读到某会话的此刻」写进 {@code user_conversation_read}。
     *
     * <p>⚠️ 用 MySQL 的 {@code NOW()}（原注释：「避免类型转换」）—— 时刻由**数据库**决定，
     * 不是应用进程的时间。照搬。
     *
     * <p>⚠️ {@code history} 名字是「查询」，**实际有副作用**（{@code api-contract.md §1} 已登记：
     * 「读接口 ≠ 无副作用」）。这是既有形态，本笔不动。
     */
    public int upsertLastReadTime(String currentId, String currentType, String otherId, String otherType) {
        String upsertSql = "INSERT INTO user_conversation_read (user_id, user_type, other_id, other_type, last_read_time) "
                + "VALUES (?, ?, ?, ?, NOW()) "
                + "ON DUPLICATE KEY UPDATE last_read_time = NOW()";
        return jdbcClient.sql(upsertSql).params(currentId, currentType, otherId, otherType).update();
    }

    /** {@code send}：插一条消息。{@code create_time} 用 {@code NOW()}（同上，时刻由库决定）。 */
    public int insertMessage(String fromId, String fromType, String toId, String toType, String content) {
        String sql = "INSERT INTO message (from_id, from_type, to_id, to_type, content, create_time) "
                + "VALUES (?, ?, ?, ?, ?, NOW())";
        return jdbcClient.sql(sql).params(fromId, fromType, toId, toType, content).update();
    }

    // ==================== 写：好友（⚠️ 只在 {@code friendAdd} 的锁临界区内或其后）====================

    /**
     * {@code friendAdd} 的查重：按 {@code (customerid, friendid)} <b>双向</b>查。
     *
     * <p>⚠️ <b>这是 §5 #5 / concurrency-map #6 的 TOCTOU 竞态点</b>：查重与 INSERT 分属两条语句，
     * 而 {@code friend} 表<b>只有主键、无唯一约束</b>（{@code mall_management_sql.sql:34-44}）⇒
     * 无数据库层兜底。三层前 B2 已给它套 {@code runLocked}（本笔把这个锁搬进
     * {@code MessageService.friendAdd}）—— 本方法**只提供语句**，原子性由调用方在锁内保证。
     *
     * <p>⚠️ 参数顺序 {@code (customerId, friendId, friendId, customerId)} —— 两个 OR 方向各一对，
     * 写成同序会**静默**漏掉一个方向（A→B 已存在但 B→A 查不到 ⇒ 会重复插行）。
     */
    public List<Map<String, Object>> selectFriendPair(String customerId, String friendId) {
        String checkSql = "SELECT id, if_friend FROM friend WHERE (customerid = ? AND friendid = ?) OR (customerid = ? AND friendid = ?)";
        return jdbcClient.sql(checkSql).params(customerId, friendId, friendId, customerId).query().listOfRows();
    }

    /**
     * {@code friendAdd}：插一条**待处理**申请（{@code if_friend = 0}）。
     *
     * <p>形参即列名（{@code customerid, friendid}）—— 见 {@link #insertMutualFriend(String, String)} 的警告。
     */
    public int insertFriendRequest(String customerid, String friendid) {
        String sql = "INSERT INTO friend (customerid, friendid, if_friend) VALUES (?, ?, 0)";
        return jdbcClient.sql(sql).params(customerid, friendid).update();
    }

    /**
     * {@code friendUp} 第 1 条：把 {@code (A,B)} 的待处理申请置为已接受（{@code 0 → 1}）。
     *
     * <p>⚠️ WHERE 里的 {@code AND if_friend = 0} 是**幂等闸**：返回 0 行 = 「申请不存在或已处理」，
     * 调用方据此走失败分支。删掉它会让重复请求把已是好友的行走一遍，响应文案随之改变。
     */
    public int acceptFriendRequest(String customerid, String friendid) {
        String updateSql = "UPDATE friend SET if_friend = 1 WHERE customerid = ? AND friendid = ? AND if_friend = 0";
        return jdbcClient.sql(updateSql).params(customerid, friendid).update();
    }

    /**
     * {@code friendUp} 第 2 条：插入**反向**行 {@code (B, A, 1)}，使两人互为好友。
     *
     * <p>⚠️⚠️ <b>形参名是列名（{@code customerid} / {@code friendid}），调用点必须显式换位</b>：
     * 三层前的调用是 {@code MessagejdbcTemplate.update(insertSql, friendId, customerId)}
     * —— 第 1 个实参落进列 {@code customerid}。故 {@code MessageService.friendUp} 里写作
     * {@code insertMutualFriend(friendId, customerId)}（**反的**）。
     *
     * <p>⚠️ 两个形参同型（{@code String}）⇒ <b>写反了照样编译、照样执行</b>，只会静默造出
     * 一张单向边（本方法的 OUTER 行归了对方）。这与
     * {@link com.minke.mallmanagement.user.dao.UserDao#insertUserCustomer}
     * 的警告是同一类：<b>没有任何断言会替你发现</b>。
     *
     * <p>⚠️ 本方法与 {@link #insertFriendRequest} <b>不可合并</b>：{@code if_friend} 字面量不同
     * （{@code 0} vs {@code 1}），且语义相反（申请 / 成好友）。
     */
    public int insertMutualFriend(String customerid, String friendid) {
        String insertSql = "INSERT INTO friend (customerid, friendid, if_friend) VALUES (?, ?, 1)";
        return jdbcClient.sql(insertSql).params(customerid, friendid).update();
    }

    /** {@code friendDe}：删掉待处理申请（{@code if_friend = 0}）。{@code if_friend = 1} 的行**不动**。 */
    public int deleteFriendRequest(String customerid, String friendid) {
        String sql = "DELETE FROM friend WHERE customerid = ? AND friendid = ? AND if_friend = 0";
        return jdbcClient.sql(sql).params(customerid, friendid).update();
    }
}
