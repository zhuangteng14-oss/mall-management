/*
 * scripts/probes/ProbeB5Migration.java —— C3 块 5（营销块）**只读**探针：
 * 「commit 0 的 7 表 + 4 加列，落到目标库了没有？」
 *
 * 为什么要它：commit 0（a1b845d）是**人工落库**的迁移（`mall_management_sql.sql` 末尾的 Migration 块），
 * 与代码是两笔 —— 代码绿不代表库里有表。券端点一启动就会打这些表，库没落就是**运行时**才炸
 * （没有集成测试覆盖它：本仓切片全是 @WebMvcTest，不碰真库）。
 *
 * 口径：**只读**。一条 DDL/DML 都不发；只查 information_schema。
 *      ✅ 所以它可以在手验前随便跑，跑几次都不留痕。
 *
 * 连接参数走**环境变量**（与 `application-dev.properties` 同名的三个），**不在本文件里写死**：
 *   DEV_DB_URL / DEV_DB_USER / DEV_DB_PASSWORD
 * ⚠️ 这是有意的：本仓往远端推的 diff 里出现明文口令会被 [Credential Leakage] 拒推。
 *    所以本文件一个口令字面量都不带，缺env 就报错退出。
 *
 * 跑法（驱动 jar 用本机 m2 里那个 8.0.33）：
 *   DEV_DB_URL='jdbc:mysql://192.168.229.129:3306/mall_management_sql?...' \
 *   DEV_DB_USER=... DEV_DB_PASSWORD=... \
 *   java -cp "$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar" \
 *        scripts/probes/ProbeB5Migration.java
 */
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ProbeB5Migration {

    /** commit 0 的 7 张新表（顺序 = 迁移文件里的顺序）。 */
    private static final List<String> NEW_TABLES = Arrays.asList(
            "coupon", "coupon_grant", "subsidy_rule", "subsidy_goods",
            "seckill_invitation", "seckill_application", "seckill_activity");

    /**
     * commit 0 的 4 个加列 —— 逐条照抄迁移文件 :1229 那行的口径
     * （{@code goods.OriginalPrice} · {@code order.CouponId} · {@code order.SubsidyAmount} · {@code order.PayAmount}）。
     * ⚠️ 用 List&lt;String[]&gt; 而不是 {@code Map}：`order` 上有**三**列，Map 会把前两列挤掉
     * （第一版就是这么写错的，实测才发现）。
     */
    private static final List<String[]> NEW_COLUMNS = new ArrayList<>();

    static {
        NEW_COLUMNS.add(new String[]{"goods", "OriginalPrice"});
        NEW_COLUMNS.add(new String[]{"order", "CouponId"});
        NEW_COLUMNS.add(new String[]{"order", "SubsidyAmount"});
        NEW_COLUMNS.add(new String[]{"order", "PayAmount"});
    }

    public static void main(String[] args) throws Exception {
        String url = env("DEV_DB_URL");
        String user = env("DEV_DB_USER");
        String pass = env("DEV_DB_PASSWORD");

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            String schema = scalar(c, "SELECT DATABASE()");
            System.out.println("库        : " + schema);

            System.out.println();
            System.out.println("── 7 张新表（commit 0）──");
            int have = 0;
            for (String t : NEW_TABLES) {
                boolean ok = count(c,
                        "SELECT COUNT(*) FROM information_schema.tables "
                      + "WHERE table_schema = DATABASE() AND LOWER(table_name) = ?", t) > 0;
                if (ok) {
                    have++;
                }
                System.out.printf("  %-20s %s%n", t, ok ? "有" : "❌ 缺");
            }

            System.out.println();
            System.out.println("── 4 个加列（commit 0）──");
            int haveCol = 0;
            for (String[] e : NEW_COLUMNS) {
                boolean ok = count(c,
                        "SELECT COUNT(*) FROM information_schema.columns "
                      + "WHERE table_schema = DATABASE() AND LOWER(table_name) = ? "
                      + "AND LOWER(column_name) = ?", e[0], e[1]) > 0;
                if (ok) {
                    haveCol++;
                }
                System.out.printf("  %-15s . %-15s %s%n", e[0], e[1], ok ? "有" : "❌ 缺");
            }

            System.out.println();
            // P3 同款判据（`ConcurrencyRunner` 的 P3）：表数 = 11 基线 + 18 已知追加。
            int total = count(c, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'");
            System.out.println("全部表数  : " + total
                    + "（P3 期望 = 11 + 18 = 29；P3 还要求 18 张已知追加表**逐个点名在**）");

            System.out.println();
            // A3 的 P4 种子计数是「干净实例」判据；顺手回报，供对照（只读）。
            System.out.println("种子计数  : order=" + count(c, "SELECT COUNT(*) FROM `order`")
                    + " goods=" + count(c, "SELECT COUNT(*) FROM goods")
                    + " login=" + count(c, "SELECT COUNT(*) FROM login"));

            System.out.println();
            boolean done = have == NEW_TABLES.size() && haveCol == NEW_COLUMNS.size();
            System.out.println(done ? "⇒ 结论：commit 0 迁移【已落库】，券端点具备运行前提。"
                                    : "⇒ 结论：迁移【未落全】—— 先落 `mall_management_sql.sql` 的 Migration 块再手验。");
            if (!done) {
                System.exit(2);
            }
        }
    }

    private static String env(String k) {
        String v = System.getenv(k);
        if (v == null || v.trim().isEmpty()) {
            System.err.println("缺环境变量 " + k + "（本文件刻意不写死连接串/口令）");
            System.exit(64);
        }
        return v;
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int count(Connection c, String sql, String... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private ProbeB5Migration() {
        // 探针，无实例
    }
}
