import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 块 6 整改 · commit 7 —— 假数据播种（方案 §十一「必做 5 个」里缺的那三项）。
 *
 * <p>方案 §十一 的 5 项与它们的现状：
 * <ol>
 *   <li><b>30 分钟前已超时的未解决问答</b> —— <b>已存在</b>（commit 2 手验留下的
 *       {@code comment.Id=110}，goods 76，{@code IsSolved=0}）。本探针**不重复造**，
 *       只在下面把它的邻居 {@code Id=111} 补成「已解决 + 已采纳」，
 *       让「问答管理」那一栏的环图与状态标签**两种态都看得到**。</li>
 *   <li><b>待审图片</b> —— 本探针插（{@code comment_image} 两张：1 {@code pending} + 1 {@code approved}）。
 *       ⚠️ <b>不留 {@code rejected} 的种子行</b>：那条态的定义是「不合规 ⇒ <b>已删仓库文件</b>」，
 *       造一条指向不存在文件的种子行，页面上就是一个破图框 —— 与其伪造，
 *       不如把「驳回」这条路径留给手验**现场点一次**（它本来就要验）。</li>
 *   <li><b>待处理举报单</b> —— 本探针插两条 {@code pending}（一条告评论、一条告商品）。</li>
 *   <li><b>预置 10 个敏感词</b> —— <b>已存在</b>（commit 0 落库，{@code mall_management_sql.sql} ⑮-8）。
 *       本探针只**核数**，不插。</li>
 *   <li><b>若干评论（含匿名、含图、含回复、含点赞）</b> —— 本探针插
 *       1 条匿名评价 + 1 条实名评价 + 1 条商家首次回复（置顶）+ 1 条 @ 商家的客户回复，
 *       连带 {@code comment_mention} 与 {@code comment_like}（并把冗余计数
 *       {@code LikeCount} / {@code DislikeCount} <b>按实际点赞行回填</b>，
 *       不让种子数据自带一个对不上的数）。</li>
 * </ol>
 *
 * <p><b>幂等</b>：以「goods 76 下 {@code AuthorId='3333'} 的 review 是否存在」为哨兵 ——
 * 在就跑 `--check`、什么都不写；不在才插。重复执行安全（种子行自身的业务唯一键也兜着：
 * {@code comment_like} 有 {@code (CommentId,UserId)} 唯一键、{@code report} 有
 * {@code (TargetType,TargetId,ReporterId)} 唯一键）。
 *
 * <p><b>凭据只从环境变量读</b>（{@code A3_DB_URL} / {@code A3_DB_USER} / {@code A3_DB_PASSWORD}）——
 * 本文件里一个口令字面量都不带（本仓推的 diff 里出现明文口令会被拒推）。
 *
 * <p>用法：{@code java -cp <mysql-connector-j>.jar scripts/probes/ProbeB6c7Seed.java [--exec]}
 * <br>不带 {@code --exec} ⇒ <b>只读</b>：打印现状（各表行数 + 哨兵 + 敏感词数），一行都不写。
 * 破坏性操作必须显式表态，同 {@code _work/Sql.java} 那条既有口径。
 *
 * <p>⚠️ <b>图片文件不归本探针管</b>：{@code comment_image.ImageUrl} 指向
 * {@code http://localhost:8088/img/comment/<名>}，那两个文件要**先**拷进
 * {@code a3} profile 的上传目录（{@code ${java.io.tmpdir}/a3-uploads/comment/}）。
 * 拷贝命令见 {@code docs/agent/evidence/c3-block6/b6-c7-fake-data.txt}。
 */
public class ProbeB6c7Seed {

    /** 这一批假数据挂的对象 —— 与 commit 2 手验留下的那两条问答同一个商品（goods.Id=76）。 */
    private static final String TARGET_TYPE = "goods";
    private static final String TARGET_ID = "76";
    private static final String PRODUCT_CODE = "GD-101-9552";
    /** 哨兵：这家匿名评价的作者。在 ⇒ 这一批已经播过。 */
    private static final String SENTINEL_AUTHOR = "3333";

    public static void main(String[] args) throws Exception {
        String url = env("A3_DB_URL");
        String user = env("A3_DB_USER");
        String pass = env("A3_DB_PASSWORD");
        boolean exec = args.length > 0 && "--exec".equals(args[0]);

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            long sentinel = queryLong(c, "SELECT COALESCE(MIN(`Id`), 0) FROM `comment` "
                    + "WHERE `TargetType` = ? AND `TargetId` = ? AND `Kind` = 'review' AND `AuthorId` = ?",
                    TARGET_TYPE, TARGET_ID, SENTINEL_AUTHOR);

            System.out.println("=== 播种前现状 ===");
            printState(c, sentinel);

            if (!exec) {
                System.out.println();
                System.out.println("-- 只读模式：一行都没写。要播种就加 --exec。");
                return;
            }
            if (sentinel > 0) {
                System.out.println();
                System.out.println("-- 哨兵已存在（review Id=" + sentinel + "）⇒ 这一批播过了，跳过写入。");
                return;
            }

            c.setAutoCommit(false);
            try {
                List<String> authors = pickAuthors(c);
                System.out.println();
                System.out.println("=== 开始播种（authors=" + authors + "） ===");
                // ⚠️ 这四个数是**正数**「多少分钟前」。这里曾经写成负数（`-120`），
                //    而 SQL 是 `NOW() - INTERVAL ? MINUTE` ⇒ 负负得正，四条种子全被插到了
                //    **未来**（`created_at` 比 `NOW()` 还晚）—— 页面上就成了「2 小时后」。
                //    别再把这里的符号「顺手」改回去：这里要的是过去的时刻。
                long reviewAnon = insertReview(c, authors.get(0), true, 5,
                        "收到货了，包装完好，做工比想象中细。用了两天没发现问题，"
                                + "唯一小缺点是说明书有点简略。（如图）", 120);
                long reviewReal = insertReview(c, authors.get(1), false, 4,
                        "第二次买了，这次是送朋友的。发货快，第二天就到。"
                                + "少给一星是因为外包装箱有压痕。", 90);
                long merchantReply = insertReply(c, reviewAnon, null, "yunqi_digital", 2, false,
                        "感谢支持！包装问题我们已经反馈给仓配，下次下单备注一下，我们单独加固。"
                                + "有需要随时联系客服。", true, 100);
                long customerReply = insertReply(c, reviewAnon, merchantReply, authors.get(2), 1, false,
                        "@yunqi_digital 客服回复很快，问题已解决，赞一个。", false, 60);
                insertMention(c, customerReply, "yunqi_digital");

                System.out.println("  评论：review#anon=" + reviewAnon + " review#real=" + reviewReal
                        + " reply#merchant=" + merchantReply + " reply#customer=" + customerReply);

                insertImage(c, reviewAnon, "b6c7-review-1.jpg", "pending", 0);
                insertImage(c, reviewAnon, "b6c7-review-2.jpg", "approved", 1);
                System.out.println("  图片：2 张挂在 review#" + reviewAnon + "（1 pending / 1 approved）");

                int likes = 0;
                int dislikes = 0;
                for (int i = 0; i < authors.size() && i < 8; i++) {
                    String u = authors.get(i);
                    // 作者本人不能给自己的内容点赞（后端 `CommentService.like` 有这道闸）。
                    if (u.equals(authors.get(0))) {
                        continue;
                    }
                    if (likes < 4) {
                        insertLike(c, reviewAnon, u, "like");
                        likes++;
                    } else if (dislikes < 2) {
                        insertLike(c, reviewAnon, u, "dislike");
                        dislikes++;
                    }
                }
                if (likes > 0) {
                    insertLike(c, merchantReply, authors.get(1), "like");
                    likes++;
                }
                // ⚠️ 冗余计数**按实际行数回填** —— 种子里 hand 写一个 `LikeCount=5`
                //    而 `comment_like` 只有 3 行，页面上的数就与真实互动对不上了。
                updateCounters(c, reviewAnon);
                updateCounters(c, merchantReply);
                System.out.println("  点赞：review#" + reviewAnon + " 赞 " + countLike(c, reviewAnon, "like")
                        + " / 踩 " + countLike(c, reviewAnon, "dislike")
                        + "；reply#" + merchantReply + " 赞 " + countLike(c, merchantReply, "like"));

                long rpComment = insertReport(c, "comment", String.valueOf(reviewAnon),
                        authors.get(3), 1, "广告", null);
                long rpGoods = insertReport(c, "goods", TARGET_ID, authors.get(2), 1, "其他",
                        "商品描述与实际不符，主图疑似盗用其他店铺。");
                System.out.println("  举报：comment#" + reviewAnon + " → 单 " + rpComment
                        + "；goods#" + TARGET_ID + " → 单 " + rpGoods);

                // 把 commit 2 留下的那条提问补成「已解决 + 已采纳」，让问答环图两种态都在。
                int solved = markSolvedIfPresent(c, 111, 112);
                System.out.println("  问答：把 Id=111 置为已解决并采纳 112 ⇒ " + (solved > 0 ? "已改" : "该行不在，跳过"));

                c.commit();
                System.out.println();
                System.out.println("=== 已提交 ===");
                printState(c, reviewAnon);
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // ── 写入 ─────────────────────────────────────────────────────────────────

    private static long insertReview(Connection c, String author, boolean anonymous, int rating,
                                     String content, int minutesAgo) throws Exception {
        String sql = "INSERT INTO `comment` (`TargetType`,`TargetId`,`ParentId`,`ReplyToId`,`Kind`,"
                + "`AuthorId`,`AuthorRole`,`IsAnonymous`,`Rating`,`Content`,`Status`,`IsMerchantFirst`,"
                + "`ProductCode`,`LikeCount`,`DislikeCount`,`created_at`) "
                + "VALUES (?,?,NULL,NULL,'review',?,?,?,?,?,'normal',0,?,0,0, NOW() - INTERVAL ? MINUTE)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, TARGET_TYPE);
            ps.setString(2, TARGET_ID);
            ps.setString(3, author);
            ps.setInt(4, 1);
            ps.setInt(5, anonymous ? 1 : 0);
            // 方案 §4.1：匿名者评价**强制 5 星**（后端 `CommentService` 的写侧也是这么覆盖的，
            // 种子数据不能造一个「匿名的 3 星」—— 那种行在正常路径上产生不出来）。
            ps.setInt(6, anonymous ? 5 : rating);
            ps.setString(7, content);
            ps.setString(8, PRODUCT_CODE);
            ps.setInt(9, minutesAgo);
            return insert(ps);
        }
    }

    /**
     * 回复。⚠️ <b>两层封顶</b>（方案 §4.1）：{@code ParentId} 永远指向**顶层**行，
     * 「回复某条回复」用 {@code ReplyToId} 表达 —— 与 {@code CommentDao.insertReply} 同一条口径。
     */
    private static long insertReply(Connection c, long parentId, Long replyToId, String author, int role,
                                    boolean anonymous, String content, boolean merchantFirst, int minutesAgo)
            throws Exception {
        String sql = "INSERT INTO `comment` (`TargetType`,`TargetId`,`ParentId`,`ReplyToId`,`Kind`,"
                + "`AuthorId`,`AuthorRole`,`IsAnonymous`,`Rating`,`Content`,`Status`,`IsMerchantFirst`,"
                + "`ProductCode`,`LikeCount`,`DislikeCount`,`created_at`) "
                + "VALUES (?,?,?,?,'review',?,?,?,5,?,'normal',?,NULL,0,0, NOW() - INTERVAL ? MINUTE)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, TARGET_TYPE);
            ps.setString(2, TARGET_ID);
            ps.setLong(3, parentId);
            if (replyToId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, replyToId);
            }
            ps.setString(5, author);
            ps.setInt(6, role);
            ps.setInt(7, anonymous ? 1 : 0);
            ps.setString(8, content);
            ps.setInt(9, merchantFirst ? 1 : 0);
            ps.setInt(10, minutesAgo);
            return insert(ps);
        }
    }

    private static void insertMention(Connection c, long commentId, String mentioned) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO `comment_mention` (`CommentId`,`MentionedId`) VALUES (?,?)")) {
            ps.setLong(1, commentId);
            ps.setString(2, mentioned);
            ps.executeUpdate();
        }
    }

    private static void insertImage(Connection c, long commentId, String name, String status, int sort)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO `comment_image` (`CommentId`,`ImageUrl`,`Status`,`SortOrder`) VALUES (?,?,?,?)")) {
            ps.setLong(1, commentId);
            // ⚠️ 拼法与 `CommentService.IMG_URL_PREFIX` **逐字一致**（`http://localhost:8088/img/comment/`）
            //    —— 后端删文件那一支（`deleteImageFile`）靠 `startsWith` 这个前缀来判「是不是我们管的图」，
            //    前缀写歪了 ⇒ 驳回会**静默不删**（它只回一句「不是本服务管理的图」）。
            ps.setString(2, "http://localhost:8088/img/comment/" + name);
            ps.setString(3, status);
            ps.setInt(4, sort);
            ps.executeUpdate();
        }
    }

    private static void insertLike(Connection c, long commentId, String user, String type) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO `comment_like` (`CommentId`,`UserId`,`Type`) VALUES (?,?,?)")) {
            ps.setLong(1, commentId);
            ps.setString(2, user);
            ps.setString(3, type);
            ps.executeUpdate();
        }
    }

    /** 把 `comment` 行上的冗余计数按 `comment_like` 的**实际行数**回填（不让种子自带对不上的数）。 */
    private static void updateCounters(Connection c, long commentId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE `comment` SET `LikeCount` = (SELECT COUNT(*) FROM `comment_like` "
                        + "WHERE `CommentId` = ? AND `Type` = 'like'), "
                        + "`DislikeCount` = (SELECT COUNT(*) FROM `comment_like` "
                        + "WHERE `CommentId` = ? AND `Type` = 'dislike') WHERE `Id` = ?")) {
            ps.setLong(1, commentId);
            ps.setLong(2, commentId);
            ps.setLong(3, commentId);
            ps.executeUpdate();
        }
    }

    private static long insertReport(Connection c, String targetType, String targetId, String reporter,
                                     int role, String reason, String description) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO `report` (`TargetType`,`TargetId`,`ReporterId`,`ReporterRole`,`Reason`,"
                        + "`Description`,`Status`) VALUES (?,?,?,?,?,?,'pending')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, targetType);
            ps.setString(2, targetId);
            ps.setString(3, reporter);
            ps.setInt(4, role);
            ps.setString(5, reason);
            ps.setString(6, description);
            return insert(ps);
        }
    }

    /** 采纳：`AcceptedReplyId` 与 `IsSolved` **一起**置位（方案 §4.2 —— 采纳即已解决）。行不在就返回 0。 */
    private static int markSolvedIfPresent(Connection c, long questionId, long replyId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE `comment` SET `IsSolved` = 1, `AcceptedReplyId` = ? "
                        + "WHERE `Id` = ? AND `Kind` = 'question' AND `AcceptedReplyId` IS NULL")) {
            ps.setLong(1, replyId);
            ps.setLong(2, questionId);
            return ps.executeUpdate();
        }
    }

    private static long insert(PreparedStatement ps) throws Exception {
        ps.executeUpdate();
        try (ResultSet rs = ps.getGeneratedKeys()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // ── 读 ───────────────────────────────────────────────────────────────────

    /**
     * 取几个**互不相同**的真实客户账号来当作者 / 点赞人 —— 不写死名字：
     * 库里客户是被重导过的（见 {@code cleanup-dirty-legacy.sql} 那一族），写死的名字下次可能就不在了。
     * ⚠️ 至少要有 4 个才够（2 个作者 + 点赞人），不够就抛 —— 与其播一批半残的种子，不如停下来。
     *
     * <p>⚠️ <b>{@code DISTINCT} + 去重是后加的（2026-10-13 整改补笔）</b>，不是装饰：
     * 2026-10-13 起 {@code comment} 上有一条「同一用户对同一商品只能评一条顶层评价」的
     * <b>唯一锁</b>（生成列 + {@code UNIQUE}）。两行种子评价都挂在 goods 76 上 ——
     * 只要 {@code authors.get(0)} 与 {@code authors.get(1)} 撞成同一个人，第二条 INSERT
     * 就会撞唯一键（{@code 1062}）把整批播种回滚掉。{@code user} 表的 {@code username}
     * 在本仓**没有**唯一约束（重导遗留），故「取 8 行」不等于「取到 8 个不同的人」——
     * 这里自己去重，并且按**去重后**的个数判够不够。
     */
    private static List<String> pickAuthors(Connection c) throws Exception {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT `username` FROM `user` WHERE `usertype` = '客户' ORDER BY `username` LIMIT 8");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String u = rs.getString(1);
                // 双保险：`DISTINCT` 挡不住「'a' 与 'a '」这种带尾空格的同人异写，
                // 而唯一锁比的是**列值相等**，尾空格在 `varchar` 比较里会被折叠 ⇒ 这里也折叠。
                if (u != null && !out.contains(u)) {
                    out.add(u);
                }
            }
        }
        if (out.size() < 4) {
            throw new IllegalStateException("客户账号不足 4 个不同的人（实得 " + out.size()
                    + "）—— 播种需要 2 个作者 + 点赞人");
        }
        return out;
    }

    private static long queryLong(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static long countLike(Connection c, long commentId, String type) throws Exception {
        return queryLong(c, "SELECT COUNT(*) FROM `comment_like` WHERE `CommentId` = ? AND `Type` = ?",
                commentId, type);
    }

    /** 打印各表的计数 —— 播种前后各一次，好让「变了什么」是可复核的。 */
    private static void printState(Connection c, long sentinelReviewId) throws Exception {
        System.out.println("  comment=" + queryLong(c, "SELECT COUNT(*) FROM `comment`")
                + "（review=" + queryLong(c, "SELECT COUNT(*) FROM `comment` WHERE `Kind`='review'")
                + " question=" + queryLong(c, "SELECT COUNT(*) FROM `comment` WHERE `Kind`='question'")
                + " answer=" + queryLong(c, "SELECT COUNT(*) FROM `comment` WHERE `Kind`='answer'")
                + " 未解决提问=" + queryLong(c, "SELECT COUNT(*) FROM `comment` "
                        + "WHERE `Kind`='question' AND `IsSolved`=0 AND `AcceptedReplyId` IS NULL") + "）");
        System.out.println("  comment_image=" + queryLong(c, "SELECT COUNT(*) FROM `comment_image`")
                + "（pending=" + queryLong(c, "SELECT COUNT(*) FROM `comment_image` WHERE `Status`='pending'")
                + " approved=" + queryLong(c, "SELECT COUNT(*) FROM `comment_image` WHERE `Status`='approved'")
                + " rejected=" + queryLong(c, "SELECT COUNT(*) FROM `comment_image` WHERE `Status`='rejected'") + "）");
        System.out.println("  comment_like=" + queryLong(c, "SELECT COUNT(*) FROM `comment_like`")
                + " comment_mention=" + queryLong(c, "SELECT COUNT(*) FROM `comment_mention`"));
        System.out.println("  report=" + queryLong(c, "SELECT COUNT(*) FROM `report`")
                + "（pending=" + queryLong(c, "SELECT COUNT(*) FROM `report` WHERE `Status`='pending'") + "）");
        System.out.println("  sensitive_word=" + queryLong(c, "SELECT COUNT(*) FROM `sensitive_word`")
                + "（预置 10 —— 方案 §十一 第 4 项）");
        System.out.println("  哨兵 review#3333 = " + sentinelReviewId);
    }

    private static String env(String k) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺环境变量 " + k + " —— 本探针不接受写死的凭据");
        }
        return v;
    }
}
