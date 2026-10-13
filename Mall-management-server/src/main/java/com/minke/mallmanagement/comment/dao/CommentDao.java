package com.minke.mallmanagement.comment.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 内容域（块 6）的数据访问出口 —— {@code comment} / {@code comment_mention} /
 * {@code comment_image} / {@code comment_like} 四张表（{@code report} / {@code sensitive_word}
 * 归各自 DAO）。
 *
 * <p>⚠️ 唯一例外是 {@link #selectTimedOutQuestions}：它额外读了一次 {@code notification}
 * （判「这条提问报过管理端没有」），理由与边界写在那个方法的注释里 —— **只此一处、只读**。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 同 {@code CartDao} / {@code TemplateDao} / {@code CouponDao}：新域一律**裸表名**。
 * ⚠️ 老域（{@code OrderDao} / {@code GoodsDao}）里的 {@code mall_management_sql.} 前缀是存量债，别照抄。
 *
 * <h2>【{@code comment} 是关键字 ⇒ 一律反引号】</h2>
 * 表名 {@code comment} 与 MySQL 的 {@code COMMENT} 关键字同形（本版本非保留字，但别赌）
 * ⇒ 本类所有语句里的表名/列名都带反引号。{@code order} 表同理（{@code OrderDao} 的既有做法）。
 *
 * <h2>【时间列一律 {@code DATE_FORMAT} 成无时区文本 —— 全仓读侧口径】</h2>
 * 与 {@code NotificationDao} / {@code CouponDao} 同款：库里存 UTC，读出来是
 * {@code 'YYYY-MM-DD HH:MM:SS'} 文本，由**前端**按浏览器时区转。别在 SQL 里做时间比较。
 *
 * <h2>【⚠️ 「买过」判据的键是 {@code goods.Id}，不是 {@code ProductCode} 文本】</h2>
 * 判据落在 {@link #countPurchasedGoods}。三条实测（VM 库 2026-10-12，{@code order_item} 207 行）：
 * <ol>
 *   <li>{@code order_item} 里有**两种**码：单型号商品存 {@code goods.ProductCode}（206/207 命中），
 *       多型号商品存 {@code goods_sku.SkuCode}（5/207 命中）⇒ 判据写成
 *       {@code 主码 = ProductCode OR 主码 IN (该商品的 sku 码)}（并集覆盖恰为 207/207）。</li>
 *   <li>{@code order_item} **没有** {@code GoodsId} 列 ⇒ 只能经 {@code (MerchantId, 码)}
 *       回联 {@code goods}（{@code goods.Id} 正是前端商品页 {@code route.query.Id} 的那个值，
 *       也是本域 {@code comment.TargetId} 的取值）。</li>
 *   <li>❌ <b>不能拿 {@code order.ProductInformation} 做 {@code LIKE}</b>：那是
 *       {@code '商品名:码；商品名:码'} 的自由文本，短码会**误命中**长码
 *       （码 {@code '01'} 命中 {@code '011'}）⇒ 「买过 A 的码碰巧是 B 的前缀」就白送一条评论资格。</li>
 * </ol>
 *
 * <h2>【三个「账号」不是同一个东西 —— 本类里同时出现，别混】</h2>
 * <ul>
 *   <li>{@code login.UserName} = {@code user.username}：**登录名**。{@code comment.AuthorId} /
 *       {@code comment_like.UserId} 存的是它（两处 DDL 注释都写「对齐 {@code login.UserName}」）。</li>
 *   <li>{@code user.customerid} / {@code user.merchantid}：**业务号**。会话里存的是它
 *       （{@code AuthSession}），{@code order.CustomerId} 也是它（实测 186/190 命中
 *       {@code user.customerid}，命中 {@code username} 的是 0/190）。⚠️ 本库的
 *       {@code login.UserName} 多数是 {@code '1111'} / {@code '2222'} 这种**数字串** ——
 *       看着像 ID，但那是**登录名**，别据「长得像」把两者当成同一个键。</li>
 *   <li>{@code goods.MerchantId}：**商家的业务号**（{@code user.merchantid}）。</li>
 * </ul>
 * ⇒ 两个方向的换算各一个方法：{@link #selectCustomerIdByUsername}（登录名 → 客户业务号）与
 * {@link #selectUsernameByUserId}（业务号 → 登录名）。
 */
@Repository
public class CommentDao {

    /**
     * {@code comment} 读投影 —— 本域只有这一处投影（同 {@code CouponDao}）。
     * {@code CreatedAt} 用 {@code DATE_FORMAT} 折成文本（见类注释）。
     * ⚠️ 改这里就是改**所有**读路径的输出形状（单行 / 顶层列表 / 回复三处共用）。
     */
    private static final String COMMENT_COLUMNS =
            "`Id`, `TargetType`, `TargetId`, `ParentId`, `ReplyToId`, `Kind`, `AuthorId`, `AuthorRole`, "
                    + "`IsAnonymous`, `Rating`, `Content`, `Status`, `IsSolved`, `AcceptedReplyId`, `IsMerchantFirst`, "
                    + "`ProductCode`, `LikeCount`, `DislikeCount`, "
                    + "DATE_FORMAT(`created_at`, '%Y-%m-%d %H:%i:%s') AS `CreatedAt`";

    private static final String IMAGE_COLUMNS =
            "`Id`, `CommentId`, `ImageUrl`, `Status`, `SortOrder`, "
                    + "DATE_FORMAT(`created_at`, '%Y-%m-%d %H:%i:%s') AS `CreatedAt`";

    private final JdbcClient jdbcClient;

    public CommentDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：评论 ====================

    /**
     * 落一条评论 / 回复 / 提问 / 回答。
     *
     * <p>⚠️ 参数顺序与 INSERT 的列顺序**逐字对应** —— 顺序一旦错位，没有任何断言会报错，
     * 只会有行数据静默错位（同 {@code GoodsDao.insertGoods} 的 ⚠️）。
     *
     * <p>{@code Status} 走默认 {@code 'normal'}、{@code IsSolved} 走默认 0、计数字段走默认 0
     * —— 不显式写，免得列清单与 {@code mall_management_sql.sql} 的 DDL 两处漂开。
     * {@code IsMerchantFirst} 显式传入：它由**同一楼层里有没有别的商家回复**决定，
     * 不是列默认值能表达的（见 {@link #countMerchantFirstUnder}）。
     */
    public int insertComment(String targetType, String targetId, Long parentId, Long replyToId, String kind,
                             String authorId, int authorRole, boolean anonymous, int rating, String content,
                             String productCode, boolean merchantFirst) {
        String sql = "INSERT INTO `comment` "
                + "(`TargetType`, `TargetId`, `ParentId`, `ReplyToId`, `Kind`, `AuthorId`, `AuthorRole`, "
                + "`IsAnonymous`, `Rating`, `Content`, `ProductCode`, `IsMerchantFirst`) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(targetType, targetId, parentId, replyToId, kind, authorId, authorRole,
                        anonymous ? 1 : 0, rating, content, productCode, merchantFirst ? 1 : 0)
                .update();
    }

    /**
     * {@code SELECT LAST_INSERT_ID()} —— 原样搬来（{@code GoodsDao.lastInsertId}）。
     *
     * <p>⚠️ <b>只在 {@code @Transactional} 里可信</b>：无事务时两次调用可能落在不同连接上，
     * 得到的就不是刚插那行的 Id。调用方（{@code CommentService.add}）已包事务
     * （同 {@code GoodsAuditService.writeGoodsFromAudit} 的口径）。
     */
    public Long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /** 逻辑删除一条评论（{@code normal → deleted}）。返回受影响行数（0 = 本来就不在 / 已删）。 */
    public int softDelete(long id) {
        return jdbcClient.sql("UPDATE `comment` SET `Status` = 'deleted' "
                        + "WHERE `Id` = ? AND `Status` <> 'deleted'")
                .param(id).update();
    }

    /**
     * 逻辑删除某顶层评论下的**全部**回复（作者删顶层 ⇒ 连带回复，方案 §4.1「作者可删（连带回复一起删）」）。
     *
     * <p>⚠️ 与本方法成对的 {@link #softDelete} 只管**那一行** —— 方案 §4.1 另一句是
     * 「回复被删只删那条」。两句是一对，别只实现一半。
     */
    public int softDeleteRepliesUnder(long parentId) {
        return jdbcClient.sql("UPDATE `comment` SET `Status` = 'deleted' "
                        + "WHERE `ParentId` = ? AND `Status` <> 'deleted'")
                .param(parentId).update();
    }

    // ==================== 读：单行 ====================

    /** 按主键取一行（含已删除行 —— 调用方自行看 {@code Status}）。查无 ⇒ {@code null}。 */
    public Map<String, Object> selectComment(long id) {
        List<Map<String, Object>> rows = jdbcClient
                .sql("SELECT " + COMMENT_COLUMNS + " FROM `comment` WHERE `Id` = ? LIMIT 1")
                .param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 读：资格 / 唯一性 ====================

    /**
     * 该用户对该对象是否已有**顶层**评论（方案 §4.1「一商品一用户只能评一条」）。
     *
     * <p>只数 {@code ParentId IS NULL} 的顶层行：回复不限条数 —— 唯一性那条规则只管**评价**。
     * ⚠️ 计数**不含**已删除行：作者删掉自己那条后应当能重评（否则「删了也评不了」是个死结）。
     */
    public int countTopLevelByAuthor(String targetType, String targetId, String kind, String authorId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM `comment` "
                        + "WHERE `TargetType` = ? AND `TargetId` = ? AND `Kind` = ? "
                        + "AND `ParentId` IS NULL AND `AuthorId` = ? AND `Status` <> 'deleted'")
                .params(targetType, targetId, kind, authorId).query(Integer.class).single();
    }

    /**
     * 买过判据：该客户是否有**已结单**（{@code ReceiveIf=1}）的订单含这个商品（{@code goods.Id}）。
     *
     * <p>方案 §4.1 发评论资格 = 「必须买过该商品 + {@code ReceiveIf=1}（已结单）」。
     * 判据形状与三条理由见类注释那段；要点是**码走 {@code goods.ProductCode} 与本商品 sku 码的并集**，
     * 而不是任何一段自由文本。
     */
    public int countPurchasedGoods(String customerId, long goodsId) {
        String sql = "SELECT COUNT(*) "
                + "FROM `goods` g "
                + "JOIN `order_item` oi ON oi.`MerchantId` = g.`MerchantId` "
                + "JOIN `order` o ON o.`OrderId` = oi.`OrderId` "
                + "WHERE g.`Id` = ? AND o.`CustomerId` = ? AND o.`ReceiveIf` = 1 "
                + "  AND (oi.`ProductCode` = g.`ProductCode` "
                + "       OR oi.`ProductCode` IN (SELECT s.`SkuCode` FROM `goods_sku` s WHERE s.`GoodsId` = g.`Id`))";
        return jdbcClient.sql(sql).params(goodsId, customerId).query(Integer.class).single();
    }

    /**
     * 可评论的**商品**清单（前端「评论时选对应商品」那个下拉，方案 §4.1）。
     *
     * <p>该客户所有**已结单**订单里的商品，按商品去重、按最近一次购买时间倒序。
     * 回 {@code GoodsId / MerchantId / GoodName / ProductCode / OrderedAt}。
     *
     * <p>⚠️ 回的是**商品级**（{@code goods.Id}）而不是订单行 —— 评论的 {@code TargetId} 就是它，
     * 且「同一商品买过三单」只该出一个选项（去重靠 {@code GROUP BY g.Id}）。
     *
     * <p>⚠️ {@code OrderedAt} 走 {@code DATE_FORMAT}（不是裸的 {@code MAX(...)}）—— 本仓的既有口径
     * 是「时间列一律 `YYYY-MM-DD HH:MM:SS` 字符串」（见 {@code COMMENT_COLUMNS}）。裸的
     * {@code datetime} 会被 Jackson 序列化成 ISO 的 {@code 2026-10-05T21:41:34}（带 `T`），
     * 与同一次响应里其它时间字段**两种写法** —— 时区/格式的坑就是这么埋的。
     */
    public List<Map<String, Object>> selectCommentableGoods(String customerId, int limit) {
        String sql = "SELECT g.`Id` AS `GoodsId`, g.`MerchantId`, g.`GoodName`, g.`ProductCode`, "
                + "DATE_FORMAT(MAX(oi.`OrderedAt`), '%Y-%m-%d %H:%i:%s') AS `OrderedAt` "
                + "FROM `order_item` oi "
                + "JOIN `order` o ON o.`OrderId` = oi.`OrderId` "
                + "JOIN `goods` g ON g.`MerchantId` = oi.`MerchantId` "
                + "  AND (g.`ProductCode` = oi.`ProductCode` "
                + "       OR EXISTS (SELECT 1 FROM `goods_sku` s WHERE s.`GoodsId` = g.`Id` "
                + "                  AND s.`SkuCode` = oi.`ProductCode`)) "
                + "WHERE o.`CustomerId` = ? AND o.`ReceiveIf` = 1 "
                + "GROUP BY g.`Id`, g.`MerchantId`, g.`GoodName`, g.`ProductCode` "
                + "ORDER BY MAX(oi.`OrderedAt`) DESC LIMIT ?";
        return jdbcClient.sql(sql).params(customerId, limit).query().listOfRows();
    }

    /**
     * 同一楼层里已经有过几条**商家**回复（{@code ParentId} 相同的那些）。
     *
     * <p>用来判 {@code IsMerchantFirst}：方案 §4.1「商家**首次**回复 → 置顶；商家**后续**回复 → 混排」
     * ⇒ 插这一条之前先数一次，为 0 才置 1。
     * <p>⚠️ 这里有 TOCTOU（两条商家回复同刻进来可能都读到 0）—— 代价是同一楼出现**两条**置顶，
     * 而 {@link #selectReplies} 的排序键带了 {@code created_at ASC}，两条仍有序。
     * 不为此加锁：置顶标记是展示优化，不是不变量（同 {@code CouponService} 的双语句取舍）。
     */
    public int countMerchantFirstUnder(long parentId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM `comment` "
                        + "WHERE `ParentId` = ? AND `IsMerchantFirst` = 1 AND `Status` <> 'deleted'")
                .param(parentId).query(Integer.class).single();
    }

    // ==================== 读：账号换算 ====================

    /** 登录名 → 角色（{@code login.Role}：1 客户 / 2 商家 / 3 管理员）。查无此人 ⇒ {@code null}。 */
    public Integer selectRoleByUsername(String username) {
        List<Integer> rows = jdbcClient.sql("SELECT `Role` FROM `login` WHERE `UserName` = ? LIMIT 1")
                .param(username).query(Integer.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 登录名 → **客户业务号**（{@code user.customerid}）。查无此人 / 该列为 NULL ⇒ {@code null}。 */
    public String selectCustomerIdByUsername(String username) {
        List<String> rows = jdbcClient.sql("SELECT `customerid` FROM `user` WHERE `username` = ? LIMIT 1")
                .param(username).query(String.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * **业务号** → 登录名（{@code customerid} / {@code merchantid} 两个方向都试，与
     * {@code UserService.businessId} 的选列口径同源）。查无 ⇒ {@code null}。
     *
     * <p>调用方是列表端点：它手里只有会话里的**业务号**，而 {@code comment.AuthorId} /
     * {@code comment_like.UserId} 存的是**登录名** ⇒ 算 {@code Mine} / {@code MyLike} 前必须翻这一次。
     */
    public String selectUsernameByUserId(String userId) {
        List<String> rows = jdbcClient.sql("SELECT `username` FROM `user` "
                        + "WHERE `customerid` = ? OR `merchantid` = ? LIMIT 1")
                .params(userId, userId).query(String.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 读：列表 ====================

    /**
     * 顶层评论（{@code ParentId IS NULL}）。
     *
     * @param kind   评价 / 提问（{@code null} / 空白 = 不筛）
     * @param solved {@code "1"} / {@code "0"} ⇒ 只回已 / 未解决的**提问**（{@code null} = 不筛）
     * @param sort   {@code "hot"} ⇒ 按 {@code LikeCount} 倒序；其余 / 空 ⇒ 时间倒序（方案 §4.1 排序）
     */
    public List<Map<String, Object>> selectTopLevel(String targetType, String targetId, String kind,
                                                    String solved, String sort, int limit, int offset) {
        List<Object> params = new ArrayList<>();
        String where = topLevelWhere(targetType, targetId, kind, solved, params);
        // ⚠️ sort 是**白名单二选一**（拼串前已收敛成字面量），不是把入参拼进 SQL。
        String orderBy = "hot".equals(sort) ? "`LikeCount` DESC, `created_at` DESC" : "`created_at` DESC";
        String sql = "SELECT " + COMMENT_COLUMNS + " FROM `comment` " + where
                + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        return jdbcClient.sql(sql).params(params).query().listOfRows();
    }

    /**
     * 顶层评论总数。谓词与 {@link #selectTopLevel} **共用 {@link #topLevelWhere}**
     * —— 两处手抄一遍必然漂开，而「{@code total} 与页数对不上」是最难看出来的一种分页 bug。
     */
    public long countTopLevel(String targetType, String targetId, String kind, String solved) {
        List<Object> params = new ArrayList<>();
        String where = topLevelWhere(targetType, targetId, kind, solved, params);
        return jdbcClient.sql("SELECT COUNT(*) FROM `comment` " + where)
                .params(params).query(Long.class).single();
    }

    /** {@link #selectTopLevel} / {@link #countTopLevel} 的**唯一**谓词构造处（见后者的 ⚠️）。 */
    private static String topLevelWhere(String targetType, String targetId, String kind, String solved,
                                        List<Object> params) {
        StringBuilder sb = new StringBuilder("WHERE `TargetType` = ? AND `TargetId` = ? "
                + "AND `ParentId` IS NULL AND `Status` <> 'deleted'");
        params.add(targetType);
        params.add(targetId);
        if (kind != null && !kind.trim().isEmpty()) {
            sb.append(" AND `Kind` = ?");
            params.add(kind.trim());
        }
        if ("1".equals(solved)) {
            sb.append(" AND `IsSolved` = 1");
        } else if ("0".equals(solved)) {
            sb.append(" AND `IsSolved` = 0");
        }
        return sb.toString();
    }

    /**
     * 一批顶层评论下的**全部**回复（两层扁平，方案 §4.1 的「回复区（缩进）」）。
     *
     * <p>排序：{@code IsMerchantFirst DESC, created_at ASC} —— 商家**首次**回复置顶，其余按时间正序
     * （楼中楼的自然读序）。⚠️ 置顶维度**只有** {@code IsMerchantFirst} 一个：
     * 商家**后续**回复按时间混排（方案逐字），不因为它也是商家就再往上提。
     *
     * <p>额外带一列 {@code ReplyToName} —— 方案 §4.1 的展示元素写着
     * 「用户B **回复 @用户A**：内容」，前端要那个被回复人的显示名。
     * <ul>
     *   <li>用**相关子查询**而不是 JOIN 自己：{@link #COMMENT_COLUMNS} 是**不带表别名**的投影，
     *       {@code JOIN comment p} 会让 {@code Id} / {@code ParentId} / {@code created_at} 等
     *       撞成 ambiguous。子查询让外层仍只有一张表（别名 {@code c} 只为子查询里引用它而给），
     *       投影一个字都不用改。</li>
     *   <li>⚠️ <b>被回复那条若是匿名，这里回 {@code NULL}</b>（不是 {@code ReplyToId} 空）。
     *       直接回它的 {@code AuthorId} 会把一条匿名回复**经由「回复 @X」这四个字实名暴露**
     *       —— 与 {@code CommentService.decorate} 抹 {@code AuthorId}、通知正文写「有人」
     *       是同一条口径。⇒ 判据留在 SQL 这一处，前端拿到 {@code null} 就不画那个前缀。</li>
     *   <li>普通回复（{@code ReplyToId IS NULL}）天然取不到 ⇒ 也是 {@code NULL}，
     *       前端只在「回复回复」那一种上画出前缀。</li>
     * </ul>
     */
    public List<Map<String, Object>> selectReplies(List<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(parentIds.size(), "?"));
        String sql = "SELECT " + COMMENT_COLUMNS + ", "
                + "(SELECT CASE WHEN p.`IsAnonymous` = 1 THEN NULL ELSE p.`AuthorId` END "
                + "FROM `comment` p WHERE p.`Id` = c.`ReplyToId`) AS `ReplyToName` "
                + "FROM `comment` c "
                + "WHERE c.`ParentId` IN (" + placeholders + ") AND c.`Status` <> 'deleted' "
                + "ORDER BY c.`ParentId`, c.`IsMerchantFirst` DESC, c.`created_at` ASC";
        return jdbcClient.sql(sql).params(parentIds).query().listOfRows();
    }

    /**
     * 评价统计卡（商品详情「累计评价」顶部）：总数 / 均分 / 5→1 星分布。
     *
     * <p>只统计**顶层评价**（{@code Kind='review'、ParentId IS NULL、未删}）—— 回复不带评分。
     * 匿名行的 {@code Rating} 在写侧已被强制 5（方案 §4.1），故分布天然把匿名计入满分那一格。
     *
     * <p>⚠️ {@code SUM(条件)} 在空集上回 {@code NULL}（不是 0）⇒ 每格都包 {@code COALESCE}，
     * 否则「一条评价都没有」时前端拿到 {@code null} 去画条形图会炸。
     */
    public Map<String, Object> selectReviewStats(String targetType, String targetId) {
        String sql = "SELECT COUNT(*) AS `Total`, "
                + "COALESCE(ROUND(AVG(`Rating`), 1), 0) AS `AvgRating`, "
                + "COALESCE(SUM(`Rating` = 5), 0) AS `S5`, "
                + "COALESCE(SUM(`Rating` = 4), 0) AS `S4`, "
                + "COALESCE(SUM(`Rating` = 3), 0) AS `S3`, "
                + "COALESCE(SUM(`Rating` = 2), 0) AS `S2`, "
                + "COALESCE(SUM(`Rating` = 1), 0) AS `S1` "
                + "FROM `comment` "
                + "WHERE `TargetType` = ? AND `TargetId` = ? AND `Kind` = 'review' "
                + "AND `ParentId` IS NULL AND `Status` <> 'deleted'";
        return jdbcClient.sql(sql).params(targetType, targetId).query().singleRow();
    }

    /** 问答统计（已解决 / 未解决）—— 问答区左圆比例图 + 管理端问答 tab。同样全包 {@code COALESCE}。 */
    public Map<String, Object> selectQuestionStats(String targetType, String targetId) {
        String sql = "SELECT COUNT(*) AS `Total`, "
                + "COALESCE(SUM(`IsSolved` = 1), 0) AS `Solved`, "
                + "COALESCE(SUM(`IsSolved` = 0), 0) AS `Unsolved` "
                + "FROM `comment` "
                + "WHERE `TargetType` = ? AND `TargetId` = ? AND `Kind` = 'question' "
                + "AND `ParentId` IS NULL AND `Status` <> 'deleted'";
        return jdbcClient.sql(sql).params(targetType, targetId).query().singleRow();
    }

    // ==================== 写：采纳答案（问答，块 6 commit 2） ====================

    /**
     * 采纳一条答案（方案 §4.2「提问者选一个答案 → {@code AcceptedReplyId} + {@code IsSolved=1} + 置顶」）。
     *
     * <p>⚠️ <b>「采纳后不可改」这个闸在 {@code WHERE} 里，不在调用方</b>：
     * {@code IsSolved = 0 AND AcceptedReplyId IS NULL} ⇒ 第二次调用影响 **0 行**。
     * 若写成「先 {@code SELECT} 看 {@code IsSolved} 再 {@code UPDATE}」，两条同刻的采纳请求
     * 会**双双读到 0** 然后都写下去（read-then-write 的经典 TOCTOU）—— 采纳是**不可逆**动作，
     * 值一道真闸。（{@link #countMerchantFirstUnder} 那处 TOCTOU 是**故意留的**，
     * 因为它只影响展示次序；这一处不是。）
     *
     * <p>⚠️ 「这条回复确实挂在这个提问下」**不在本方法里判** —— 它是调用方
     * （{@code CommentService.accept}）已经读过的父行信息，在这里再查一次等于同一件事做两遍。
     * 本方法只管「一条**未被采纳过**的提问行」这一个谓词。
     *
     * @return 1 = 采纳成功；0 = 该提问已被采纳过（或已删 / 不存在）
     */
    public int acceptAnswer(long questionId, long replyId) {
        return jdbcClient.sql("UPDATE `comment` SET `AcceptedReplyId` = ?, `IsSolved` = 1 "
                        + "WHERE `Id` = ? AND `Status` <> 'deleted' "
                        + "AND `IsSolved` = 0 AND `AcceptedReplyId` IS NULL")
                .params(replyId, questionId).update();
    }

    // ==================== 读：超时未解决的提问（QuestionTimeoutJob） ====================

    /**
     * 超过 {@code minutes} 分钟仍未采纳、且**还没报过管理端**的顶层提问 ——
     * {@code QuestionTimeoutJob} 的扫描目标（方案 §4.2「30 分钟未解决 → Job 报管理端」）。
     *
     * <p>投影是**通知正文要用的最小集**，不是整行：{@code Id}（幂等键与 {@code TargetId}）、
     * {@code TargetType}/{@code TargetId}（点进去看哪件商品的问答）、{@code AuthorId}（提问人）、
     * 以及**截断到 60 字的正文**。
     *
     * <p>⚠️ 正文在 **SQL 里截断**（{@code LEFT}）而不是搬整列回 Java 再截：{@code Content} 是
     * {@code varchar(1000)}，一次扫几百条就是几百 KB 只为了往标题里塞一句话。
     * {@code LEFT} 按**字符**数（不是字节），utf8mb4 下中英一致。
     *
     * <p>⚠️ {@code NOW() - INTERVAL ? MINUTE} 是**扫描窗口**，属于 {@code CouponDao}
     * 「{@code EXPIRING_THRESHOLD} 单一表达式」那一类用法 —— 类注释说的「别在 SQL 里做时间比较」
     * 针对的是**读给前端的展示列**（那一侧一律 {@code DATE_FORMAT} 成无时区文本，由浏览器转），
     * 不是筛选取值。{@code CreatedAt} 这一列仍按要求 {@code DATE_FORMAT} 出去。
     *
     * <h2>【⚠️ 「已报过的不再入队」为什么必须在 SQL 里，不能放在 Java 里跳】</h2>
     * 直觉写法是「查 {@code LIMIT N} 条，回到 Java 用 {@code NotificationDao.existsDelivery}
     * 把报过的跳掉」。那样会**饥饿**：排序是 {@code created_at ASC}，已报过的那批恰好永远占据
     * 前 N 个位置 ⇒ 第 N+1 条起**永远扫不到**，而它才是最该被报的。改成 {@code LIMIT} 拿大一点
     * 只是把这个悬崖推远，没有消除。
     * <p>故判据进 {@code WHERE}（{@code NOT EXISTS}），让「已报过的」**根本不占名额**：
     * 每轮的前 N 个位置恒为「还没报过的最老那几条」。
     *
     * <h2>【⚠️ 本方法是本类唯一一处读**别人的表**】</h2>
     * {@code notification} 归 {@code NotificationDao}。这里读它是为了上面那条饥饿修复
     * —— 判据不参与 {@code LIMIT} 就修不掉。先例是 {@code NotificationDao.existsDelivery}
     * 自己也是跨表谓词，且 {@code NotificationDao} 已有一处读 {@code user} 表并写明理由；
     * 本条按同一档处理：**只读、只此一处**（本类别的查询一律只碰 {@code comment*} 四张表）。
     * <p>⚠️ {@code TargetId} 是 {@code varchar(128)}、{@code comment.Id} 是数字 ⇒ 必须
     * {@code CAST(... AS CHAR)} 对齐：不转的话 MySQL 会把 {@code varchar} 侧**转成数字**比较
     * （{@code TargetId='abc'} 变 0），既可能误命中又用不上索引。
     *
     * <p>排序 {@code created_at ASC} ⇒ 最急（最老）的先处理；{@code LIMIT} 给这一轮的写量封顶
     * （同 {@code CouponExpiryJob.MAX_COUPONS_PER_ROUND} 的「爆炸半径上界」口径）。
     *
     * @param notifyTo    管理端共享收件箱的 {@code ToId}（{@code "*"}）
     * @param notifyEvent 本条报文的 {@code EventType}（{@code question.timeout}）
     */
    public List<Map<String, Object>> selectTimedOutQuestions(int minutes, int limit,
                                                             String notifyTo, String notifyEvent) {
        String sql = "SELECT `Id`, `TargetType`, `TargetId`, `AuthorId`, "
                + "LEFT(`Content`, 60) AS `Content`, "
                + "DATE_FORMAT(`created_at`, '%Y-%m-%d %H:%i:%s') AS `CreatedAt` "
                + "FROM `comment` "
                + "WHERE `Kind` = 'question' AND `ParentId` IS NULL AND `Status` <> 'deleted' "
                + "AND `IsSolved` = 0 "
                + "AND `created_at` <= NOW() - INTERVAL ? MINUTE "
                + "AND NOT EXISTS (SELECT 1 FROM `notification` n "
                + "  WHERE n.`ToId` = ? AND n.`EventType` = ? AND n.`TargetType` = 'comment' "
                + "    AND n.`TargetId` = CAST(`comment`.`Id` AS CHAR)) "
                + "ORDER BY `created_at` ASC LIMIT ?";
        return jdbcClient.sql(sql).params(minutes, notifyTo, notifyEvent, limit).query().listOfRows();
    }

    // ==================== 读 / 写：图片 ====================

    /** 一批评论的图片（按评论、按 {@code SortOrder} 升序）。 */
    public List<Map<String, Object>> selectImages(List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(commentIds.size(), "?"));
        String sql = "SELECT " + IMAGE_COLUMNS + " FROM `comment_image` "
                + "WHERE `CommentId` IN (" + placeholders + ") ORDER BY `CommentId`, `SortOrder`, `Id`";
        return jdbcClient.sql(sql).params(commentIds).query().listOfRows();
    }

    /**
     * 落一条图片。{@code Status} 走列默认 {@code 'pending'} —— 方案 §4.3「先审后发」：
     * 评论文本立即可见，图**等管理端通过**才显示（故写侧不许传状态进来）。
     */
    public int insertImage(long commentId, String imageUrl, int sortOrder) {
        return jdbcClient.sql("INSERT INTO `comment_image` (`CommentId`, `ImageUrl`, `SortOrder`) "
                        + "VALUES (?, ?, ?)")
                .params(commentId, imageUrl, sortOrder).update();
    }

    // ==================== 读 / 写：图片审核（块 6 · commit 4，方案 §4.3） ====================

    /**
     * 管理端「待审图片」的读投影 —— {@code comment_image} 与它所属 {@code comment} 的
     * <b>限定（带别名）</b>投影。
     *
     * <p>⚠️ <b>不能复用 {@link #IMAGE_COLUMNS}</b>：那是不带别名的，一旦 {@code JOIN comment}
     * 就有 {@code Id} / {@code Status} / {@code created_at} 三个列名两边重名 ⇒ ambiguous。
     * 与 {@code ReportDao.TARGET_COLUMNS} 是同一个理由、同一种写法。
     *
     * <p>带出 {@code AuthorId} / {@code Kind} / {@code CommentContent} 是让管理员
     * <b>看着上下文判图</b> —— 一张图合不合规常常取决于它挂在什么评论下。
     */
    private static final String IMAGE_REVIEW_COLUMNS =
            "i.`Id`, i.`CommentId`, i.`ImageUrl`, i.`Status`, i.`SortOrder`, "
                    + "DATE_FORMAT(i.`created_at`, '%Y-%m-%d %H:%i:%s') AS `CreatedAt`, "
                    + "c.`AuthorId` AS `AuthorId`, c.`Kind` AS `Kind`, c.`TargetType` AS `TargetType`, "
                    + "c.`TargetId` AS `TargetId`, c.`Content` AS `CommentContent`";

    /**
     * 待审 / 已审图片列表（管理端）。{@code status} 空白 = 不筛。时间倒序 ——
     * 新传的在前（待审的队列要按提交先后看）。
     */
    public List<Map<String, Object>> selectImagesByStatus(String status, int limit, int offset) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT " + IMAGE_REVIEW_COLUMNS + " FROM `comment_image` i "
                + "LEFT JOIN `comment` c ON c.`Id` = i.`CommentId` " + imageWhere(status, params)
                + " ORDER BY i.`created_at` DESC, i.`Id` DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        return jdbcClient.sql(sql).params(params).query().listOfRows();
    }

    /** 同筛选下的图数（口径同 {@link #countTopLevel}：与列表**共用** {@link #imageWhere}）。 */
    public long countImagesByStatus(String status) {
        List<Object> params = new ArrayList<>();
        String where = imageWhere(status, params);
        Long n = jdbcClient.sql("SELECT COUNT(*) FROM `comment_image` i " + where)
                .params(params).query(Long.class).single();
        return n == null ? 0L : n;
    }

    /** {@link #selectImagesByStatus} / {@link #countImagesByStatus} 的**唯一**谓词构造处。 */
    private static String imageWhere(String status, List<Object> params) {
        StringBuilder sb = new StringBuilder("WHERE 1 = 1");
        if (status != null && !status.trim().isEmpty()) {
            sb.append(" AND i.`Status` = ?");
            params.add(status.trim());
        }
        return sb.toString();
    }

    /** 单张图 + 它的上下文（审核前取「这是谁的图 / 挂在哪条评论下」）。查无 ⇒ {@code null}。 */
    public Map<String, Object> selectImage(long id) {
        List<Map<String, Object>> rows = jdbcClient
                .sql("SELECT " + IMAGE_REVIEW_COLUMNS + " FROM `comment_image` i "
                        + "LEFT JOIN `comment` c ON c.`Id` = i.`CommentId` WHERE i.`Id` = ? LIMIT 1")
                .param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 改一张图的状态（通过 / 驳回）。
     *
     * <p>⚠️ <b>CAS</b>：谓词带 {@code AND Status = 'pending'} —— 两个管理员同时点「驳回」，
     * 第二个的 {@code affected} 是 0（Service 翻成「该图片已被审核」），于是
     * <b>删仓库文件只发生一次</b>。与 {@code ReportDao.updateHandle} 同一手法、同一个理由。
     *
     * @return 受影响行数（{@code 0} = 已被审过 / 不存在）
     */
    public int updateImageStatus(long id, String status) {
        return jdbcClient.sql("UPDATE `comment_image` SET `Status` = ? "
                        + "WHERE `Id` = ? AND `Status` = 'pending'")
                .params(status, id).update();
    }

    // ==================== 读 / 写：点赞踩 ====================

    /** 某用户对一批评论的既有态度（{@code CommentId → like / dislike}），供前端把按钮点亮。 */
    public List<Map<String, Object>> selectMyLikes(List<Long> commentIds, String userId) {
        if (commentIds == null || commentIds.isEmpty() || userId == null || userId.trim().isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(commentIds.size(), "?"));
        String sql = "SELECT `CommentId`, `Type` FROM `comment_like` "
                + "WHERE `UserId` = ? AND `CommentId` IN (" + placeholders + ")";
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.addAll(commentIds);
        return jdbcClient.sql(sql).params(params).query().listOfRows();
    }

    /** 某用户对某评论的既有态度（{@code null} = 没点过）。{@link #upsertLike} 的增量判据。 */
    public String selectLikeType(long commentId, String userId) {
        List<String> rows = jdbcClient.sql("SELECT `Type` FROM `comment_like` "
                        + "WHERE `CommentId` = ? AND `UserId` = ? LIMIT 1")
                .params(commentId, userId).query(String.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 落 / 改一条态度（方案 §一.4 的 {@code UNIQUE(CommentId,UserId)} = 一人一条）。
     *
     * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE}：没有就插，有就**覆盖**。
     * ⚠️ 本方法**不碰** {@code LikeCount} / {@code DislikeCount}：那两个冗余计数由调用方按
     * {@link #selectLikeType} 的**旧值**算出增减量，再走 {@link #incrLike} / {@link #incrDislike}
     * 单列原子累加 —— 一条语句里同时改状态与计数，会把「旧值是多少」这条信息吃掉。
     */
    public int upsertLike(long commentId, String userId, String type) {
        return jdbcClient.sql("INSERT INTO `comment_like` (`CommentId`, `UserId`, `Type`) "
                        + "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE `Type` = VALUES(`Type`)")
                .params(commentId, userId, type).update();
    }

    /** 撤销态度（用户再点一次同一种 = 取消）。 */
    public int deleteLike(long commentId, String userId) {
        return jdbcClient.sql("DELETE FROM `comment_like` WHERE `CommentId` = ? AND `UserId` = ?")
                .params(commentId, userId).update();
    }

    /**
     * {@code LikeCount} 原子累加（{@code DislikeCount} 见 {@link #incrDislike}）。
     *
     * <p>⚠️ {@code GREATEST(…, 0)} 兜住「并发下减成负数」：冗余计数只是展示值，
     * 宁可停在 0 也不让它变负（真值以 {@code comment_like} 行数为准，随时可重建）。
     */
    public int incrLike(long commentId, int delta) {
        return jdbcClient.sql("UPDATE `comment` SET `LikeCount` = GREATEST(`LikeCount` + ?, 0) WHERE `Id` = ?")
                .params(delta, commentId).update();
    }

    /** 同 {@link #incrLike}，列是 {@code DislikeCount}。 */
    public int incrDislike(long commentId, int delta) {
        return jdbcClient.sql("UPDATE `comment` SET `DislikeCount` = GREATEST(`DislikeCount` + ?, 0) WHERE `Id` = ?")
                .params(delta, commentId).update();
    }

    // ==================== 读 / 写：@ 关系（comment_mention，块 6 commit 3） ====================

    /**
     * 落一条 @ 关系（方案 §4.1「@ 由后端自动生成」）—— 一行 = 「这条评论 @ 了这个人」。
     *
     * <p>⚠️ 本表**没有唯一键**（DDL 只有两个普通索引）⇒ 同一 {@code (CommentId, MentionedId)} 可以出现
     * 多次。本域**不会**造出重复：{@link com.minke.mallmanagement.comment.service.CommentService}
     * 对一条评论只解析**一个**被 @ 人、只插**一次**。故这里不加 {@code INSERT IGNORE} / {@code ON DUPLICATE}
     * —— 那是为「允许重复」准备的写法，用在这里反而会掩盖调用方真的插了两次。
     *
     * <p>⚠️ {@code MentionedId} 是**登录名**（对齐 {@code login.UserName}，DDL 注释逐字），
     * 不是业务号 —— 与调用方解析出的那一列同一个键。
     */
    public int insertMention(long commentId, String mentionedId) {
        return jdbcClient.sql("INSERT INTO `comment_mention` (`CommentId`, `MentionedId`) VALUES (?, ?)")
                .params(commentId, mentionedId).update();
    }

    /**
     * 商品的商家**登录名**（{@code comment.TargetType='goods'} 时「@ 该商品商家」的解析）。
     *
     * <p>路径 = {@code goods.Id → goods.MerchantId（业务号）→ user.merchantid → user.username（登录名）}。
     * ⚠️ 中间那一跳**不能省**：{@code goods.MerchantId} 存的是**商家的业务号**
     * （本类注释「三个账号」一节逐字），与 {@code comment.AuthorId} 要写的**登录名**不是同一个键。
     *
     * <p>商品不存在 / 该商品没挂到任何商家 ⇒ {@code null}（调用方据此**不 @ 任何人**，
     * 而不是 @ 一个空串）。
     */
    public String selectMerchantUsernameByGoodsId(long goodsId) {
        List<String> rows = jdbcClient.sql("SELECT u.`username` FROM `goods` g "
                        + "JOIN `user` u ON u.`merchantid` = g.`MerchantId` "
                        + "WHERE g.`Id` = ? LIMIT 1")
                .param(goodsId).query(String.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }
}
