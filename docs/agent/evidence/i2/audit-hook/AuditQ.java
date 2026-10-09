import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/** 一次性只读探针：把 VM 库 admin_audit 里 notification.send 的行倒成 UTF-8 文本（Windows 控制台是 GBK，别直接打屏）。
 *  ⚠️ **2026-10-09 入库时脱敏**：DB 账号口令改为下面的占位符常量 —— 原值就是 `application-a3.properties` 里
 *     `${A3_DB_USER:…}` / `${A3_DB_PASSWORD:…}` 的实际值（同 `application.properties` 的 root 口令）。除这一处，
 *     本文件（含 SQL、IP、列清单）与当时跑的**逐字相同**；要让它可以跑，把两个常量填回环境值即可。 */
public class AuditQ {
    /** 入库时脱敏的占位符（见类注释）；跑之前填环境值。 */
    private static final String DB_USER = "<a3-db-user>";
    private static final String DB_PWD = "<a3-db-password>";

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://192.168.229.129:3306/mall_management_sql"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf-8";
        try (PrintWriter w = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(args[0]), StandardCharsets.UTF_8))) {
            try (Connection c = DriverManager.getConnection(url, DB_USER, DB_PWD)) {
                String sql = "SELECT Id, ActorId, ActorRole, Action, TargetType, TargetId, BeforeJson, AfterJson, OccurredAt "
                        + "FROM admin_audit WHERE Action='notification.send' ORDER BY Id DESC LIMIT 5";
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                    ResultSetMetaData m = r.getMetaData();
                    int n = 0;
                    while (r.next()) {
                        n++;
                        for (int i = 1; i <= m.getColumnCount(); i++) {
                            w.println(m.getColumnLabel(i) + " = " + r.getString(i));
                        }
                        w.println("----------------");
                    }
                    w.println("RETURNED_ROWS = " + n);
                }
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("SELECT COUNT(*) AS c, SUM(BeforeJson IS NULL) AS nulls, "
                             + "SUM(ActorId IS NULL) AS actor_null FROM admin_audit WHERE Action='notification.send'")) {
                    r.next();
                    w.println("TOTAL_ROWS = " + r.getInt("c")
                            + "  BEFORE_NULL = " + r.getInt("nulls")
                            + "  ACTORID_NULL = " + r.getInt("actor_null"));
                }
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("SELECT COUNT(*) AS c FROM notification")) {
                    r.next();
                    w.println("notification_TOTAL_ROWS = " + r.getInt("c"));
                }
            }
        }
    }
}
