package com.minke.mallmanagement.notification.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * notification 域的**内联 SQL 唯一出口**（C3 块 1 · I2）—— 只碰本域的两张表
 * {@code notification}，外加一处**只读**的 {@code user} 投影
 * （⚠️ 块 1 时还有 {@code notification_template} 的两个方法 —— 2026-10-10 就地改造后表已物理删、
 * 方法已删，模板读写归 {@code template.dao.TemplateDao}）
 * （见 {@link #selectCustomerIds} / {@link #selectMerchantIds}）。
 *
 * <p>范式照 {@link com.minke.mallmanagement.admin.dao.AdminAuditDao}：{@code @Repository} +
 * 构造器**收 {@code JdbcTemplate} 而不是 {@code JdbcClient}**（让 {@code @WebMvcTest} 切片里既有的
 * {@code @MockitoBean JdbcTemplate} 继续喂给本类）+ 语句以 Java 字符串内联。不用 MyBatis、无 mapper XML。
 *
 * <h2>【{@code mall_management_sql.} 前缀：本类**带**前缀（跟随 {@code admin_audit}）】</h2>
 * 本仓对这个前缀有**两种既有口径**，本条是**显式选边**，不是随手抄：
 * <ul>
 *   <li>message 域**不带**（{@code MessageDao.java:36} 明写「本域 SQL 一律不带前缀，实测 0 处」）；</li>
 *   <li>其余各域**都带**（user / goods / order / admin …）。</li>
 * </ul>
 * 本类选择**带**。两条理由：
 * <ol>
 *   <li><b>同块同风格</b>：本笔（I2）与 {@code AdminAuditDao}（批 A）同属 C3 块 1，且**同住
 *       {@code mall_management_sql.sql} 的 Migration 段**。两者一个带一个不带，就是
 *       {@code AdminAuditDao} 注释里点名要避免的那处「**看不见的**风格分叉」（TD-12：库名与开发库
 *       同名 ⇒ 带不带前缀在 a3 上行为一致，分叉不会自己暴露）。</li>
 *   <li><b>与 pack 的 I2 记录一致</b> —— {@code c3-block-1-infra.md} 的「要读 → 表」把
 *       {@code notification} 的迁移块与 {@code admin_audit} 的并列。</li>
 * </ol>
 * ⚠️ 代价写下来：前缀基线（全仓硬编码处数）**再 +1**，须随本笔登记
 * （{@code docs/agent/db-migration.md} / {@code progress.md}）。将来做「清除 schema 前缀」那一笔时，
 * **本类也在清除名单里**。
 *
 * <h2>【{@code ReadAt} 读出为什么要 {@code DATE_FORMAT}，而不是直接读】</h2>
 * 全仓读时间列的既有口径就是 {@code DATE_FORMAT(…, '%Y-%m-%d %H:%i:%s')}（见 {@code AdminAuditDao:41}）
 * —— 它把 {@code datetime} 交回**无时区的**字符串，由**前端**按浏览器时区转。
 * 本类照抄这一口径，**别有例外**：库里存的是 UTC，若哪天这里改成直接读 {@code Timestamp}，
 * 就会绕开前端的转换、把 UTC 当本地时间显示（差一个时区）。
 * <p>⚠️ 副作用（**正是我们需要的**）：{@code DATE_FORMAT(NULL)} 仍是 {@code NULL}
 * ⇒ 「未读」判据（{@code ReadAt IS NULL}）在**读出的 Map 里也成立**，
 * {@code NotificationService} 据此在行上补 {@code unread} 布尔值，不必为它多加一列投影。
 */
@Repository
public class NotificationDao {

    /** 收件箱共用列清单 —— 列表端点只有这一处投影，故只需自洽。 */
    private static final String COLUMNS =
            "Id, BatchId, ToId, ToType, EventType, Title, Content, TargetType, TargetId, "
                    + "DATE_FORMAT(ReadAt, '%Y-%m-%d %H:%i:%s') AS ReadAt, "
                    + "DATE_FORMAT(CreatedAt, '%Y-%m-%d %H:%i:%s') AS CreatedAt ";

    private final JdbcClient jdbcClient;

    public NotificationDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：投递一行 ====================

    /**
     * 追加一行投递记录。**本域唯一的写入语句** —— 扇出 N 个收件人就调 N 次
     * （{@code ReadAt} 落在行上 ⇒ 一行只能服务一个收件人，见建表块注释）。
     *
     * <p>⚠️ {@code BatchId} 由调用方生成并**原样透传**（单发也要给一个，列是 {@code NOT NULL}）。
     * 本类**不生成 UUID** —— 生成是「一次投递」的语义，属 Service 的决策，DAO 只管落库。
     *
     * @return 受影响行数（正常恒为 1）
     */
    public int insertOne(String batchId, String toId, String toType, String eventType,
                         String title, String content, String targetType, String targetId) {
        String sql = "INSERT INTO mall_management_sql.notification "
                + "(BatchId, ToId, ToType, EventType, Title, Content, TargetType, TargetId) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(batchId, toId, toType, eventType, title, content, targetType, targetId)
                .update();
    }

    // ==================== 读：收件箱（三端共用） ====================

    /**
     * 某收件人的收件箱（时间倒序）。
     *
     * <p>{@code onlyUnread=true} 时只回未读。⚠️ 参数**必须按 {@code sql} 拼接顺序**逐个追加
     * （同 {@code AdminAuditDao.selectAudits} 的警告）—— 改本方法时**成对改**。
     */
    public List<Map<String, Object>> selectInbox(String toId, String toType, boolean onlyUnread, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append("FROM mall_management_sql.notification WHERE ToId = ? AND ToType = ?");
        List<Object> params = new ArrayList<>();
        params.add(toId);
        params.add(toType);
        if (onlyUnread) {
            sql.append(" AND ReadAt IS NULL");
        }
        // Id 作平局判据：CreatedAt 是 datetime(3)，同毫秒可能并列，只按它排序**不稳定**。
        sql.append(" ORDER BY CreatedAt DESC, Id DESC LIMIT ?");
        params.add(limit);
        return bind(sql.toString(), params).query().listOfRows();
    }

    /**
     * 收件箱总条数（不带 LIMIT）—— 谓词必须与 {@link #selectInbox} **逐字一致**，
     * 含 {@code onlyUnread} 那一支。
     *
     * <p>⚠️ 曾经漏了 {@code onlyUnread}（{@code total} 恒为全量），后果是前端那句
     * 「已截断：共 N 条」的判据（{@code total > rows.length}）在「只看未读」时会**误报**
     * —— 列表空、total 却是 3。不是显示小瑕疵：它会让用户以为「筛掉了 3 条」而去翻页。
     * 「总数」的口径与 {@code AuditService.countAudits} 一致 = **同筛选条件下**的总数。
     */
    public long countInbox(String toId, String toType, boolean onlyUnread) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM mall_management_sql.notification WHERE ToId = ? AND ToType = ?");
        if (onlyUnread) {
            sql.append(" AND ReadAt IS NULL");
        }
        return jdbcClient.sql(sql.toString()).params(toId, toType).query(Long.class).single();
    }

    /** 未读数 —— 三端 5s 轮询的**最热查询**，走 {@code idx_notification_unread} 可做 covering count。 */
    public long countUnread(String toId, String toType) {
        return jdbcClient.sql("SELECT COUNT(*) FROM mall_management_sql.notification "
                        + "WHERE ToId = ? AND ToType = ? AND ReadAt IS NULL")
                .params(toId, toType).query(Long.class).single();
    }

    // ==================== 写：标记已读 ====================

    /**
     * 标记**一条**已读。
     *
     * <p>三处细节都是刻意的：
     * <ul>
     *   <li>{@code AND ToId = ? AND ToType = ?} —— **只认自己的行**，让「拿到别人的 Id 能不能标」
     *       在 SQL 层就无解（Guard 是接口层的第一道，这是第二道）。</li>
     *   <li>{@code AND ReadAt IS NULL} —— **幂等**：重复点同一条不会把已读时刻刷新成「刚刚」。
     *       代价：重复调用返回 {@code updated=0}，这是**正常**结果不是失败。</li>
     *   <li>{@code NOW(3)} —— 列是 {@code datetime(3)}，写秒级精度会丢毫秒（与全仓 {@code NOW()} 的
     *       秒级口径不同，是**本表建表时就定死的**精度，见建表块）。</li>
     * </ul>
     */
    public int markRead(String toId, String toType, long id) {
        return jdbcClient.sql("UPDATE mall_management_sql.notification SET ReadAt = NOW(3) "
                        + "WHERE Id = ? AND ToId = ? AND ToType = ? AND ReadAt IS NULL")
                .params(id, toId, toType).update();
    }

    /** 把该收件人的**所有**未读标为已读。同 {@link #markRead} 的幂等与精度口径。 */
    public int markAllRead(String toId, String toType) {
        return jdbcClient.sql("UPDATE mall_management_sql.notification SET ReadAt = NOW(3) "
                        + "WHERE ToId = ? AND ToType = ? AND ReadAt IS NULL")
                .params(toId, toType).update();
    }

    // ==================== 读：管理端「通知历史」（按批归组） ====================

    /**
     * 按 {@code BatchId} 归组的投递历史（时间倒序）—— 管理端「发过什么」那一眼。
     *
     * <p>⚠️ <b>归组口径是「批次」，不是「行」</b>：一次群发扇出 N 行，若逐行平铺，
     * 一次公告就能把历史页刷屏。故按 {@code BatchId} 收成一行，带 {@code RecipientCount}
     * （投给了几人）/ {@code UnreadCount}（几人还没读）。
     *
     * <p>⚠️ {@code MIN(...)} 而非裸列：同一批次的 {@code EventType} / {@code Title} / {@code Content}
     * 按构造就是同一个值，但 MySQL 8 默认 {@code ONLY_FULL_GROUP_BY} 无法从 {@code BatchId}
     * 推出这个函数依赖（{@code BatchId} 非唯一键）⇒ 不包聚合函数会直接被拒。
     * 用 {@code MIN()} 而不是把它们都塞进 {@code GROUP BY}：后者让 1024 字节的正文参与分组键，
     * 既慢又表达不出「批次决定这些列」。
     */
    public List<Map<String, Object>> selectHistory(String eventType, String from, String to, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT BatchId, MIN(EventType) AS EventType, MIN(Title) AS Title, MIN(Content) AS Content, "
                        // ⚠️ 时间列**必须** DATE_FORMAT 成 'YYYY-MM-DD HH:MM:SS' 文本，与收件箱/模板一致。
                        //    裸 MIN(CreatedAt) 会被 Jackson 序列化成 'YYYY-MM-DDTHH:MM:SS.mmm'（ISO 带毫秒）
                        //    —— 前端 toLocal() 虽也能解析，但同一域两种时间形状是纯粹的认知负担。
                        + "DATE_FORMAT(MIN(CreatedAt), '%Y-%m-%d %H:%i:%s') AS CreatedAt, COUNT(*) AS RecipientCount, "
                        + "SUM(ReadAt IS NULL) AS UnreadCount "
                        + "FROM mall_management_sql.notification WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendHistoryFilters(sql, params, eventType, from, to);
        sql.append(" GROUP BY BatchId ORDER BY MIN(CreatedAt) DESC LIMIT ?");
        params.add(limit);
        return bind(sql.toString(), params).query().listOfRows();
    }

    /** 同一筛选条件下的**批次数**（{@code COUNT(DISTINCT BatchId)}）—— 不是行数。 */
    public long countHistory(String eventType, String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(DISTINCT BatchId) FROM mall_management_sql.notification WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendHistoryFilters(sql, params, eventType, from, to);
        return bind(sql.toString(), params).query(Long.class).single();
    }

    /** 历史与计数的共用筛选谓词 —— 两处必须**逐字一致**（同 {@code AdminAuditDao} 的成对改要求）。 */
    private static void appendHistoryFilters(StringBuilder sql, List<Object> params,
                                             String eventType, String from, String to) {
        if (notBlank(eventType)) {
            sql.append(" AND EventType = ?");
            params.add(eventType);
        }
        if (notBlank(from)) {
            sql.append(" AND CreatedAt >= ?");
            params.add(from);
        }
        if (notBlank(to)) {
            sql.append(" AND CreatedAt <= ?");
            params.add(to);
        }
    }

    // ==================== 读：投递幂等判据（定时任务用） ====================

    /**
     * 「这条通知是不是已经发给过这个人了？」—— 定时提醒类任务（{@code CouponExpiryJob}）的**幂等闸**。
     *
     * <p>判据是四元组 {@code (ToId, EventType, TargetType, TargetId)}。⚠️ 四列都要给：
     * 只按 {@code ToId + EventType} 会让「同一个人收到过任意一条同 code 的通知」把所有
     * 后续提醒都挡掉（那张券再也不会被提醒）；只按 {@code TargetId} 则会跨收件人串味。
     *
     * <p>⚠️ 本方法是**跨块新加的**（C3 块 5 · commit 1）：块 5 需要一个「不往 {@code coupon} 表
     * 加列就能做幂等」的读法，而通知表本来就记着「谁、因为哪个对象、收过什么」。
     * 加在**本域**而不是让 coupon 域去读 {@code notification} 表 —— 那张表的 SQL 只该在本类里。
     *
     * <p>走 {@code idx_notification_...} 未必能覆盖（本判据带了 {@code TargetType}/{@code TargetId}），
     * 但调用方是小批量定时任务，不是热路径。
     */
    public boolean existsDelivery(String toId, String eventType, String targetType, String targetId) {
        String sql = "SELECT COUNT(*) FROM mall_management_sql.notification "
                + "WHERE ToId = ? AND EventType = ? AND TargetType = ? AND TargetId = ?";
        return jdbcClient.sql(sql).params(toId, eventType, targetType, targetId)
                .query(Long.class).single() > 0;
    }

    // ==================== 读：受众（跨表只读投影） ====================

    /**
     * 群发受众：全部**客户**业务号（{@code user.customerid}）。
     *
     * <p>⚠️ <b>这是本域唯一一处读别人的表</b>，且**只读**。它有先例（{@code MessageDao.java:174}
     * 同样 {@code FROM user}），不是新开的口子；之所以不塞进 {@code UserDao}：受众解析是**通知的**
     * 工作流概念，本笔不该为它去改 user 域的公开面（那会把改动面从本域漏到 user 域）。
     *
     * <p>⚠️ {@code usertype} 是**中文魔法串**（{@code "客户"} / {@code "商家"}，
     * 见 {@code FriendCacheService.java:159}）。取值**由调用方传入**而不是在这里写死：
     * 魔法串只该在**一个类**里出现一次（{@code NotificationBroadcastService} 的私有常量），
     * 否则将来加第三类 {@code usertype} 时，漏改一处就**静默少发**（通知发少了不会报错）。
     *
     * <p>两个 {@code <> ''} / {@code IS NOT NULL} 谓词是防御：库里存在 {@code customerid} 为
     * 空串或 NULL 的行（历史数据），若照单全收会产出**收件人为空**的通知 —— 那是永远无人能读的行。
     */
    public List<String> selectCustomerIds(String usertypeCustomer) {
        String sql = "SELECT customerid FROM mall_management_sql.user "
                + "WHERE usertype = ? AND customerid IS NOT NULL AND customerid <> ''";
        return jdbcClient.sql(sql).param(usertypeCustomer).query(String.class).list();
    }

    /** 群发受众：全部**商家**业务号。口径同 {@link #selectCustomerIds}。 */
    public List<String> selectMerchantIds(String usertypeMerchant) {
        String sql = "SELECT merchantid FROM mall_management_sql.user "
                + "WHERE usertype = ? AND merchantid IS NOT NULL AND merchantid <> ''";
        return jdbcClient.sql(sql).param(usertypeMerchant).query(String.class).list();
    }

    // ==================== 内部 ====================

    /** 按顺序逐个绑定 —— 与 {@code AdminAuditDao} 同写法，避免参数错配。 */
    private JdbcClient.StatementSpec bind(String sql, List<Object> params) {
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
