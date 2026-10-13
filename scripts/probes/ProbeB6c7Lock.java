import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 块 6 整改 · 补笔 —— Migration ⑯ 唯一锁（{@code uk_comment_review_once}）的**探针**。
 *
 * <p>验两件事，一件都不能省：
 * <ol>
 *   <li><b>锁在</b>：同一用户对同一商品的**顶层评价**第二条被 DB 拒（{@code 1062}）——
 *       且是**绕过 Service** 的前提下（直连插；Service 那道预检在并发之外拦得住，
 *       这里要证的正是「预检被绕过时 DB 还拦不拦」）；</li>
 *   <li><b>锁不外溢</b>：回复 / 提问 / 回答 / 留言板（{@code TargetType='user'}）四类
 *       **同作者同对象插两条都必须成功** —— 这是本锁最容易被做错的地方（裸三列键会把回复也锁死，
 *       那是一次降级），故逐类给判据，不靠「应该没事」。</li>
 * </ol>
 *
 * <p>另验两条口径：并发（两连接同刻）恰好一行落库；已删行解除锁定（「作者删了自己那条后能重评」）。
 *
 * <p><b>测试靶</b>：{@code TargetType='goods' / 'user'} + {@code TargetId='999901' / '999902'}
 * —— <b>刻意用不存在的商品 Id</b>：① DB 唯一键不关心对象是否存在；② 不污染证据档
 * {@code b6-c7-fake-data.txt} §4.1 那批读数（它按 goods 76 列行）。跑完**自清**，
 * 判据是「残留 0 行」。
 *
 * <p><b>凭据只从环境变量读</b>（{@code A3_DB_URL} / {@code A3_DB_USER} / {@code A3_DB_PASSWORD}）。
 *
 * <p>用法：{@code java -cp <mysql-connector-j>.jar scripts/probes/ProbeB6c7Lock.java [--exec]}
 * <br>不带 {@code --exec} ⇒ <b>只读</b>：自查 ⑯ 是否在 + 现存重复组 + 被锁行清单，一行都不写。
 */
public class ProbeB6c7Lock {

    /** 测试靶：两个**不存在**的商品 Id（先断言不存在，避免真商品被写脏）。 */
    private static final long TARGET_A = 999901L;
    private static final long TARGET_B = 999902L;
    /** 矩阵里那个「同一用户」——四类豁免都拿它跟同一对象比。 */
    private static final String AUTHOR = "b6lock_probe";
    /** 回复的作者另起一个：豁免与「是不是同一个人」无关，换个作者能顺带盖住这一层。 */
    private static final String REPLIER = "b6lock_replier";
    private static final String TT_GOODS = "goods";
    private static final String TT_USER = "user";
    private static final String KIND_REVIEW = "review";
    private static final String KIND_QUESTION = "question";
    private static final String KIND_ANSWER = "answer";

    private static int pass = 0;
    private static int fail = 0;
    private static long written = 0;

    public static void main(String[] args) throws Exception {
        // ⚠️ 变量名**不许**叫 `pass`：本类有个静态计数器也叫 pass（和 fail 配对），局部名一遮，
        //    「PASS + pass」那行汇总就会把**库口令**打到 stdout —— 本探针第一次跑就踩了（实测印出的是口令）。
        //    凡本类里出现的口令变量，一律叫 `dbPass`。
        String url = env("A3_DB_URL"), user = env("A3_DB_USER"), dbPass = env("A3_DB_PASSWORD");
        boolean exec = args.length > 0 && "--exec".equals(args[0]);

        try (Connection c = DriverManager.getConnection(url, user, dbPass)) {
            System.out.println("=== ⑯ 唯一锁现状自查 ===");
            printMigration(c);
            printDuplicates(c);
            printLocked(c);

            if (!exec) {
                System.out.println();
                System.out.println("-- 只读模式：一行都没写。要跑矩阵 + 并发就加 --exec。");
                return;
            }

            long dirty = queryLong(c, "SELECT COUNT(*) FROM `goods` WHERE `Id` IN (?,?)",
                    String.valueOf(TARGET_A), String.valueOf(TARGET_B));
            if (dirty > 0) {
                throw new IllegalStateException("测试靶商品 " + TARGET_A + "/" + TARGET_B + " 在 goods 里真实存在（"
                        + dirty + " 行）—— 换靶再跑，别拿真数据当地。");
            }

            System.out.println();
            System.out.println("=== 一、锁在：同作者同商品第二条顶层评价必须被 DB 拒 ===");
            long first = insertTop(c, TT_GOODS, String.valueOf(TARGET_A), KIND_REVIEW, AUTHOR, 1, 5, "锁-第一条");
            expectPassed("顶层评价 #1（合法）", first > 0);
            expectDuplicate("顶层评价 #2（同作者同商品，直连插，绕过 Service）", () ->
                    insertTop(c, TT_GOODS, String.valueOf(TARGET_A), KIND_REVIEW, AUTHOR, 1, 5, "锁-第二条"));
            long live = queryLong(c, "SELECT COUNT(*) FROM `comment` WHERE `TargetType`='goods' AND `TargetId`=? "
                    + "AND `Kind`='review' AND `ParentId` IS NULL AND `AuthorId`=? AND `Status`='normal'",
                    String.valueOf(TARGET_A), AUTHOR);
            expectPassed("落库行数恰好 1（实测 " + live + "）", live == 1);

            System.out.println();
            System.out.println("=== 二、锁不外溢：四类同作者同对象两条都必须成功 ===");
            long reply1 = insertReply(c, TT_GOODS, String.valueOf(TARGET_A), first, REPLIER, "回复-1");
            long reply2 = insertReply(c, TT_GOODS, String.valueOf(TARGET_A), first, REPLIER, "回复-2");
            expectPassed("回复 ×2（ParentId != NULL）", reply1 > 0 && reply2 > 0);

            long q1 = insertTop(c, TT_GOODS, String.valueOf(TARGET_A), KIND_QUESTION, AUTHOR, 0, 0, "提问-1");
            long q2 = insertTop(c, TT_GOODS, String.valueOf(TARGET_A), KIND_QUESTION, AUTHOR, 0, 0, "提问-2");
            expectPassed("提问 ×2（Kind=question）", q1 > 0 && q2 > 0);

            long a1 = insertAnswer(c, TT_GOODS, String.valueOf(TARGET_A), q1, AUTHOR, "回答-1");
            long a2 = insertAnswer(c, TT_GOODS, String.valueOf(TARGET_A), q1, AUTHOR, "回答-2");
            expectPassed("回答 ×2（Kind=answer）", a1 > 0 && a2 > 0);

            long m1 = insertTop(c, TT_USER, String.valueOf(TARGET_A), KIND_REVIEW, AUTHOR, 1, 5, "留言板-1");
            long m2 = insertTop(c, TT_USER, String.valueOf(TARGET_A), KIND_REVIEW, AUTHOR, 1, 5, "留言板-2");
            expectPassed("留言板 ×2（TargetType=user，无「买过」约束）", m1 > 0 && m2 > 0);

            System.out.println();
            System.out.println("=== 三、已删行解除锁定（作者删了自己那条后能重评）===");
            int deleted = softDelete(c, first);
            expectPassed("逻辑删第一条（affected=" + deleted + "）", deleted == 1);
            long again = insertTop(c, TT_GOODS, String.valueOf(TARGET_A), KIND_REVIEW, AUTHOR, 1, 4, "锁-删后重评");
            expectPassed("删后重评成功", again > 0);

            System.out.println();
            System.out.println("=== 四、并发：两连接同刻插同一 (goods," + TARGET_B + "," + AUTHOR + ") 顶层评价 ===");
            race(url, user, dbPass);

            System.out.println();
            System.out.println("=== 五、清理（自清，靶上残留必须 0）===");
            long left = clean(c);
            expectPassed("靶上残留 0 行（实测 " + left + "）", left == 0);
        }

        System.out.println();
        System.out.println("=== 汇总：PASS " + pass + " / FAIL " + fail + "（本轮共写 " + written + " 行，已全部清理）===");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ==================== 并发档 ====================

    /** 两个连接、同一时刻（{@code CyclicBarrier}）各插一条 ⇒ 期望恰一条成功、另一条 1062。 */
    private static void race(String url, String user, String dbPass) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(2);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger dup = new AtomicInteger();
        StringBuilder bad = new StringBuilder();
        Thread[] ts = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int n = i;
            ts[i] = new Thread(() -> {
                try (Connection c = DriverManager.getConnection(url, user, dbPass)) {
                    gate.await();
                    insertTop(c, TT_GOODS, String.valueOf(TARGET_B), KIND_REVIEW, AUTHOR, 1, 5, "并发-" + n);
                    ok.incrementAndGet();
                } catch (SQLIntegrityConstraintViolationException e) {
                    if (e.getErrorCode() == 1062) {
                        dup.incrementAndGet();
                    } else {
                        synchronized (bad) { bad.append("非 1062 的约束错：").append(e.getMessage()).append("; "); }
                    }
                } catch (Exception e) {
                    synchronized (bad) { bad.append(e.getClass().getSimpleName()).append(":").append(e.getMessage()).append("; "); }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        try (Connection c = DriverManager.getConnection(url, user, dbPass)) {
            long live = queryLong(c, "SELECT COUNT(*) FROM `comment` WHERE `TargetType`='goods' AND `TargetId`=? "
                    + "AND `Kind`='review' AND `ParentId` IS NULL AND `AuthorId`=? AND `Status`='normal'",
                    String.valueOf(TARGET_B), AUTHOR);
            System.out.println("  成功=" + ok.get() + " 被唯一键拒(1062)=" + dup.get() + " 落库行数=" + live
                    + (bad.length() > 0 ? " 异常：" + bad : ""));
            expectPassed("并发后恰好 1 行落库", live == 1);
            expectPassed("恰一条走成功、一条走 1062（成功 1 / 1062 1）", ok.get() == 1 && dup.get() == 1);
        }
    }

    // ==================== 写入/断言 ====================

    private static long insertTop(Connection c, String tt, String tid, String kind, String author,
                                  int role, int rating, String content) throws Exception {
        return insert(c, tt, tid, null, kind, author, role, rating, content, "normal");
    }

    private static long insertReply(Connection c, String tt, String tid, long parentId, String author,
                                    String content) throws Exception {
        return insert(c, tt, tid, parentId, KIND_REVIEW, author, 1, 5, content, "normal");
    }

    private static long insertAnswer(Connection c, String tt, String tid, long parentId, String author,
                                     String content) throws Exception {
        return insert(c, tt, tid, parentId, KIND_ANSWER, author, 1, 0, content, "normal");
    }

    /** 直连插一行（**刻意不走 Service** —— 本探针要验的正是 Service 之外那一道）。 */
    private static long insert(Connection c, String tt, String tid, Long parentId, String kind, String author,
                               int role, int rating, String content, String status) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO `comment` (`TargetType`,`TargetId`,`ParentId`,`Kind`,`AuthorId`,`AuthorRole`,"
                        + "`Rating`,`Content`,`Status`) VALUES (?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tt);
            ps.setString(2, tid);
            if (parentId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, parentId);
            }
            ps.setString(4, kind);
            ps.setString(5, author);
            ps.setInt(6, role);
            ps.setInt(7, rating);
            ps.setString(8, content);
            ps.setString(9, status);
            ps.executeUpdate();
            written++;
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static int softDelete(Connection c, long id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE `comment` SET `Status`='deleted' WHERE `Id`=? AND `Status`<>'deleted'")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    /** 自清：靶上所有行（含回复/问答/留言板，它们共用同一个 TargetId 字符串）。 */
    private static long clean(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM `comment` WHERE `TargetId` IN (?,?)")) {
            ps.setString(1, String.valueOf(TARGET_A));
            ps.setString(2, String.valueOf(TARGET_B));
            int n = ps.executeUpdate();
            System.out.println("  DELETE " + n + " 行");
        }
        return queryLong(c, "SELECT COUNT(*) FROM `comment` WHERE `TargetId` IN (?,?)",
                String.valueOf(TARGET_A), String.valueOf(TARGET_B));
    }

    /** 反向判据：这条插入**必须**被唯一键拒绝（1062）。 */
    private static void expectDuplicate(String what, SqlCall call) {
        try {
            long id = call.run();
            fail(what + " —— ⚠️ 竟然插成功了（Id=" + id + "）：锁没生效");
        } catch (SQLIntegrityConstraintViolationException e) {
            if (e.getErrorCode() == 1062) {
                ok(what + " —— 1062 被拒：" + shortMsg(e.getMessage()));
            } else {
                fail(what + " —— 报的是别的约束错（errorCode=" + e.getErrorCode() + "）：" + e.getMessage());
            }
        } catch (Exception e) {
            fail(what + " —— 报的是别的异常：" + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }

    private static void expectPassed(String what, boolean good) {
        if (good) {
            ok(what);
        } else {
            fail(what);
        }
    }

    private static void ok(String what) {
        pass++;
        System.out.println("  [PASS] " + what);
    }

    private static void fail(String what) {
        fail++;
        System.out.println("  [FAIL] " + what);
    }

    private static String shortMsg(String m) {
        return m == null ? "" : m.length() > 90 ? m.substring(0, 90) + "…" : m;
    }

    // ==================== 只读自查 ====================

    private static void printMigration(Connection c) throws Exception {
        long col = queryLong(c, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='comment' AND COLUMN_NAME='ReviewLockKey' AND EXTRA LIKE '%STORED GENERATED%'");
        long idx = queryLong(c, "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='comment' AND INDEX_NAME='uk_comment_review_once' AND NON_UNIQUE=0");
        System.out.println("  ⑯-1 生成列 ReviewLockKey（STORED）：" + (col == 1 ? "在" : "❌ 不在")
                + " · ⑯-2 唯一键 uk_comment_review_once：" + (idx == 1 ? "在" : "❌ 不在"));
        if (col != 1 || idx != 1) {
            System.out.println("  ⚠️ ⑯ 没跑完就跑本探针 = 假绿（矩阵会全 PASS 而锁其实不在）⇒ 先应用迁移。");
        }
    }

    /** ⑯ 的**建键前自查** SQL —— 有重复 ⇒ ALTER 会 1062 失败，故它同时是「上锁前的前置判据」。 */
    private static void printDuplicates(Connection c) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT TargetType,TargetId,AuthorId,COUNT(*) AS n FROM `comment` "
                     + "WHERE ParentId IS NULL AND Kind='review' AND Status='normal' "
                     + "GROUP BY TargetType,TargetId,AuthorId HAVING n>1")) {
            int n = 0;
            while (rs.next()) {
                n++;
                System.out.println("  ⚠️ 重复组：" + rs.getString(1) + " / " + rs.getString(2) + " / "
                        + rs.getString(3) + " × " + rs.getInt(4));
            }
            System.out.println("  现存重复组：" + n + " 组");
        }
    }

    /** 被锁行清单（非 NULL 的那些）—— 一眼看出锁只落在「顶层商品评价」上。 */
    private static void printLocked(Connection c) throws Exception {
        long locked = queryLong(c, "SELECT COUNT(*) FROM `comment` WHERE `ReviewLockKey` IS NOT NULL");
        long total = queryLong(c, "SELECT COUNT(*) FROM `comment`");
        System.out.println("  当前被锁行：" + locked + " / 全表 " + total + " 行（其余 " + (total - locked)
                + " 行键为 NULL ⇒ 不在锁的作用域内）");
    }

    // ==================== 小工具 ====================

    private static long queryLong(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static String env(String k) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺环境变量 " + k + " —— 本探针不接受写死的凭据");
        }
        return v;
    }

    @FunctionalInterface
    private interface SqlCall {
        long run() throws Exception;
    }
}
