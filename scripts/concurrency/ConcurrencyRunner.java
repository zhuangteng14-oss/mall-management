/**
 * A3 并发测试执行器 —— Java 17 单文件源码启动。
 *
 * 运行形态（H3）：
 *   java -cp <mysql-connector-j-8.0.33.jar> scripts/concurrency/ConcurrencyRunner.java \
 *        --base-url http://localhost:8088 \
 *        --jdbc-url "jdbc:mysql://192.168.229.129:3306/mall_management_sql?..." \
 *        --expect-db-host 192.168.229.129
 *
 * 硬约束：
 *   - 不引任何新依赖：classpath 上只有 mysql-connector 一个 jar，
 *     故 JSON 一律手写 / 手解析，不得使用 Jackson（它不在本 classpath 上）
 *   - 不修改 pom.xml；不进 surefire（mvn test 不会跑到它）
 *   - 只走 HTTP + JDBC
 *
 * 分阶段落地（锁 4，每阶段一个 commit）：
 *   阶段 A  参数解析 + 退出码 + 空壳场景        已完成
 *   阶段 B  P0 静态长度校验 + P1–P4（JDBC 侧） ← 本文件当前所处阶段
 *   阶段 C  P5–P7（HTTP 侧前置校验）
 *   阶段 D  六场景（S1 / S2a / S2b / S3 / S4a / S4b）
 *   阶段 E  run.sh 完整版 + dependency:build-classpath 解析
 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConcurrencyRunner {

    // ===================== 命名空间 =====================
    // 前缀固定为字面量，不接受调用方任意指定（H3 cleanup.safety_rails）
    static final String NS_PREFIX = "A3TEST-";

    // P6 哨兵行前缀。刻意与 NS_PREFIX 分开：哨兵是「一次性写入即刻删除」的探针，
    // 但若进程在写入与删除之间被 SIGKILL，它必须仍能被 cleanup 覆盖（见报告 cleanup 一节）。
    static final String PROBE_PREFIX = "A3P-";

    // P1 读到的服务端版本，供 P7 的版本比对使用（S0-5 第 3 步判据）
    static String vmVersion = null;

    // P6 本次使用的哨兵值，供阶段 D 的 cleanup 精确删除
    static String probeGoodType = null;

    // ===================== 退出码（H3 report.exit_codes） =====================
    static final int EXIT_PASS = 0;             // 全部门禁通过
    static final int EXIT_GATE_FAILED = 1;      // 有门禁断言失败
    static final int EXIT_PREFLIGHT_FAILED = 2; // 前置校验失败（含「后端不在期望库上」）

    // ===================== 选项 =====================
    static final class Opt {
        String baseUrl = "http://localhost:8088";
        String jdbcUrl = null;
        String jdbcUser = "test";
        String jdbcPassword = "test123";
        String expectDbHost = null;   // OD-4：强制门禁，不设即拒绝运行
        String runId = null;          // 缺省自动生成 8 位 hex
        int qps = 120;
        int durationSec = 30;
        int rounds = 5;               // 澄清 1/4：B 组轮数，同轮内线程共享同一 <round>
        boolean bGroupGate = false;   // 澄清 1：默认 false，且只影响 B-2
        boolean keepData = false;
        boolean cleanupOnly = false;
        boolean resetDb = false;
        // P7 阴性对照：本机开发库与 VM 库同名（TD-13），只有主机名能区分，故必须反证一次
        String negativeControlUrl = "jdbc:mysql://127.0.0.1:3306/mall_management_sql"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        String negativeControlUser = "root";
        String negativeControlPassword = "123456";
        boolean skipNegativeControl = false;
        // P5 就绪探针等待上限（秒）。后端冷启动要跑 Hibernate ddl-auto=update 的校验，非瞬时。
        int readyTimeoutSec = 60;
        String reportDir = "scripts/concurrency/reports";
        boolean help = false;
    }

    static final String HELP = String.join("\n",
        "A3 并发测试执行器（Java 17 单文件源码启动）",
        "",
        "用法:",
        "  java -cp <mysql-connector.jar> scripts/concurrency/ConcurrencyRunner.java [选项]",
        "",
        "必需:",
        "  --jdbc-url <url>       脚本自身 JDBC 连接串（读侧与清理用）",
        "  --expect-db-host <h>   期望的数据库主机；不符即 exit 2（OD-4 强制门禁）",
        "",
        "常用:",
        "  --base-url <url>       被测后端地址（默认 http://localhost:8088）",
        "  --jdbc-user <u>        JDBC 用户（默认 test）",
        "  --jdbc-password <p>    JDBC 口令（默认 test123）",
        "  --run-id <id>          命名空间标识（默认随机 8 位 hex）",
        "  --qps <n>              目标 QPS（默认 120，仅记录、不设门禁）",
        "  --duration <sec>       paced 场景时长（默认 30；S4b 混合场景用 60）",
        "  --rounds <n>           B 组轮数（默认 5，同轮内线程共享同一 <round>）",
        "  --b-group-gate         将 B-2 提升为门禁（默认 false，仅影响 B-2）",
        "  --report-dir <dir>     报告输出目录（默认 scripts/concurrency/reports）",
        "",
        "清理:",
        "  --keep-data            跳过 finally 清理，保留数据以供人工查验",
        "  --cleanup-only         仅按 --run-id 执行清理，不跑场景",
        "  --reset-db             重跑 mall_management_sql.sql（DROP 全部 11 表！仅限一次性实例）",
        "  --skip-negative-control  跳过 P7 阴性对照（本机库不可达时会自动 skip，无需手动指定）",
        "  --ready-timeout-sec N  P5 就绪探针等待上限，默认 60",
        "  --help                 显示本帮助",
        "",
        "退出码: 0=全部门禁通过  1=有门禁断言失败  2=前置校验失败");

    // ===================== main =====================
    public static void main(String[] args) {
        Opt o;
        try {
            o = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println("用 --help 查看用法。");
            System.exit(EXIT_PREFLIGHT_FAILED);
            return;
        }

        if (o.help) {
            System.out.println(HELP);
            System.exit(EXIT_PASS);
            return;
        }

        if (o.runId == null) {
            o.runId = randomHex(8);
        }

        banner(o);

        // H3 phase_0_preflight.purpose：产生任何业务流量之前，先证明「脚本连的是期望库」。
        // 任一前置失败一律 exit 2，不进入场景。
        // 注意：P1–P4 只覆盖「脚本自身」这一侧；「后端是否在同一个库上」由阶段 C 的 P6 哨兵往返给出
        //       —— 因为本机开发库与 VM 库同名（TD-13），单看库名无法区分。
        Map<String, String> pre = new LinkedHashMap<>();
        boolean preOk = preflightStatic(o, pre) & preflightJdbc(o, pre);
        if (preOk) {
            preOk = preflightHttp(o, pre);   // P5–P7：需要后端在跑
        } else {
            pre.put("P5", "SKIP JDBC 侧前置未过，未做 HTTP 侧校验");
            pre.put("P6", "SKIP 同上");
            pre.put("P7", "SKIP 同上");
        }
        printPreflight(pre);
        if (!preOk) {
            System.err.println("前置校验失败，未产生任何业务流量。退出码 2。");
            System.exit(EXIT_PREFLIGHT_FAILED);
            return;
        }

        System.out.println("[阶段 C] preflight P0–P7 已实现；六场景待阶段 D。");

        int rc = 0;
        rc = Math.max(rc, runScenarioStubs(o));

        System.out.println();
        System.out.println("runId      : " + o.runId);
        System.out.println("target     : " + o.baseUrl + "  →  " + o.jdbcUrl);
        System.out.println("preflight  : " + pre.size() + " 项，全部通过");
        System.out.println("gates      : 未实现（阶段 D）");
        System.out.println("report     : <未实现>（阶段 E）");
        System.exit(rc);
    }

    // ===================== 参数解析 =====================
    static Opt parseArgs(String[] args) {
        Opt o = new Opt();
        List<String> a = new ArrayList<>();
        for (String s : args) {
            if (s != null && !s.isEmpty()) {
                a.add(s);
            }
        }
        for (int i = 0; i < a.size(); i++) {
            String k = a.get(i);
            String v = null;
            int eq = k.indexOf('=');
            if (k.startsWith("--") && eq > 0) {
                v = k.substring(eq + 1);
                k = k.substring(0, eq);
            }
            switch (k) {
                case "--help": case "-h": o.help = true; break;
                case "--base-url": o.baseUrl = value(a, ++i, v, k); break;
                case "--jdbc-url": o.jdbcUrl = value(a, ++i, v, k); break;
                case "--jdbc-user": o.jdbcUser = value(a, ++i, v, k); break;
                case "--jdbc-password": o.jdbcPassword = value(a, ++i, v, k); break;
                case "--expect-db-host": o.expectDbHost = value(a, ++i, v, k); break;
                case "--run-id": o.runId = value(a, ++i, v, k); break;
                case "--report-dir": o.reportDir = value(a, ++i, v, k); break;
                case "--qps": o.qps = intValue(a, ++i, v, k); break;
                case "--duration": o.durationSec = intValue(a, ++i, v, k); break;
                case "--rounds": o.rounds = intValue(a, ++i, v, k); break;
                case "--b-group-gate": o.bGroupGate = true; break;
                case "--keep-data": o.keepData = true; break;
                case "--cleanup-only": o.cleanupOnly = true; break;
                case "--reset-db": o.resetDb = true; break;
                case "--negative-control-url": o.negativeControlUrl = value(a, ++i, v, k); break;
                case "--negative-control-user": o.negativeControlUser = value(a, ++i, v, k); break;
                case "--negative-control-password": o.negativeControlPassword = value(a, ++i, v, k); break;
                case "--skip-negative-control": o.skipNegativeControl = true; break;
                case "--ready-timeout-sec": o.readyTimeoutSec = intValue(a, ++i, v, k); break;
                default:
                    throw new IllegalArgumentException("未知选项 " + k);
            }
        }
        if (!o.help) {
            if (o.jdbcUrl == null || o.jdbcUrl.isEmpty()) {
                throw new IllegalArgumentException("缺少 --jdbc-url");
            }
            if (o.expectDbHost == null || o.expectDbHost.isEmpty()) {
                throw new IllegalArgumentException("缺少 --expect-db-host（OD-4 强制门禁，不允许省略）");
            }
            if (o.qps <= 0) {
                throw new IllegalArgumentException("--qps 必须为正");
            }
            if (o.durationSec <= 0) {
                throw new IllegalArgumentException("--duration 必须为正");
            }
            if (o.rounds <= 0) {
                throw new IllegalArgumentException("--rounds 必须为正");
            }
        }
        return o;
    }

    /** 取选项值：支持 `--k v` 与 `--k=v` 两种写法；两种都没给即报错。 */
    static String value(List<String> a, int i, String inline, String key) {
        if (inline != null && !inline.isEmpty()) {
            return inline;
        }
        if (i >= a.size()) {
            throw new IllegalArgumentException(key + " 缺少取值");
        }
        return a.get(i);
    }

    static int intValue(List<String> a, int i, String inline, String key) {
        String s = value(a, i, inline, key);
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 不是整数: " + s);
        }
    }

    // ===================== 输出 =====================
    static void banner(Opt o) {
        System.out.println("=== A3 并发测试 ===");
        System.out.println("baseUrl        : " + o.baseUrl);
        System.out.println("jdbcUrl        : " + o.jdbcUrl);
        System.out.println("jdbcUser       : " + o.jdbcUser);
        System.out.println("jdbcPassword   : " + mask(o.jdbcPassword));
        System.out.println("expectDbHost   : " + o.expectDbHost);
        System.out.println("runId          : " + o.runId);
        System.out.println("namespace      : " + NS_PREFIX + o.runId + "…");
        System.out.println("qps / duration : " + o.qps + " / " + o.durationSec + "s");
        System.out.println("rounds         : " + o.rounds);
        System.out.println("bGroupGate     : " + o.bGroupGate + "  (仅影响 B-2)");
        System.out.println("keepData       : " + o.keepData);
        System.out.println("cleanupOnly    : " + o.cleanupOnly);
        System.out.println("resetDb        : " + o.resetDb);
        System.out.println("negControl     : " + (o.skipNegativeControl
                ? "(已关闭)"
                : hostOf(o.negativeControlUrl) + "  (P7 阴性对照)"));
        System.out.println("readyTimeout   : " + o.readyTimeoutSec + "s  (P5)");
        System.out.println("reportDir      : " + o.reportDir);
        System.out.println();
    }

    static String mask(String s) {
        if (s == null || s.isEmpty()) {
            return "(空)";
        }
        return "*".repeat(s.length());
    }

    // ===================== 场景（空壳，阶段 D 填充） =====================
    static int runScenarioStubs(Opt o) {
        if (o.cleanupOnly) {
            System.out.println("[场景] --cleanup-only 已指定：阶段 D 实现清理后此分支才有效。");
            return EXIT_PASS;
        }
        // 六场景：S1 只读 / S2a 下单-同参 / S2b 下单-独参 / S3 支付 / S4a 非法流转 / S4b 混合
        String[] stubs = {"S1 只读", "S2a 下单(同参)", "S2b 下单(独参)", "S3 支付", "S4a 状态机非法流转", "S4b 混合负载"};
        for (String s : stubs) {
            System.out.println("[场景] " + s + " —— 阶段 D 未实现，跳过");
        }
        return EXIT_PASS;
    }

    // ===================== 前置校验 =====================

    /**
     * P0（H3 之外的追加项 —— 静态，无需连库）：
     * 命名空间串长度断言。
     *
     * 起因：goodstype.GoodType / login.UserName / user.username / customerimage.UserName
     * 均为 char(20)，而 B 组的 A3TEST-&lt;runId8&gt;-GT&lt;round&gt; 已达 19 字符，只剩 1 字符余量。
     * MySQL 8.4 默认 strict mode 下超长直接 ERROR 1406，若不前移到这里，会在压测跑到一半才炸。
     * 上限取自 mall_management_sql.sql 的建表语句，逐列核对（agent.md 规则 9.1）。
     */
    static boolean preflightStatic(Opt o, Map<String, String> pre) {
        // {列, 上限, 该列会实际写入的最长取值}
        String[][] checks = {
            {"goodstype.GoodType",         "20", NS_PREFIX + o.runId + "-GT" + o.rounds},
            {"login.UserName",             "20", NS_PREFIX + o.runId + "-U" + o.rounds},
            {"user.username",              "20", NS_PREFIX + o.runId + "-U" + o.rounds},
            {"customerimage.UserName",     "20", NS_PREFIX + o.runId + "-U" + o.rounds},
            {"customerimage.CustomerId",   "20", "A3C-" + o.runId},
            {"goods.MerchantId",           "20", "A3M-" + o.runId},
            {"P6 探针 goodstype.GoodType", "20", "A3P-" + o.runId},
            {"order.OrderId",              "40", NS_PREFIX + o.runId + "-N99999"},
            {"order.CustomerId",           "40", "A3C-" + o.runId},
            {"order.MerchantId",           "40", "A3M-" + o.runId},
            {"friend.customerid",          "50", NS_PREFIX + o.runId + "-F1"},
            {"friend.friendid",            "50", NS_PREFIX + o.runId + "-F2"},
        };
        // 逐列与「该列自己的上限」比对；再挑出余量最小的一列报告出来。
        // 注意不要把各列的最长串直接和 char(20) 比 —— order.OrderId 是 varchar(40)，
        // 它的 22 字符是合规的，混在一起讲会误导读者以为 P0 失效。
        int minMargin = Integer.MAX_VALUE;
        String tightCol = null;
        int tightLen = 0;
        int tightLimit = 0;
        StringBuilder over = new StringBuilder();
        boolean ok = true;
        for (String[] c : checks) {
            int limit = Integer.parseInt(c[1]);
            int len = c[2].length();
            int margin = limit - len;
            if (margin < minMargin) {
                minMargin = margin;
                tightCol = c[0];
                tightLen = len;
                tightLimit = limit;
            }
            if (margin < 0) {
                ok = false;
                over.append("\n      [超长] ").append(c[0])
                    .append(" 长度 ").append(len).append(" > 上限 ").append(limit)
                    .append("  值=").append(c[2]);
            }
        }
        pre.put("P0", (ok ? "PASS " : "FAIL ")
                + checks.length + " 列逐列比对"
                + (ok ? "通过；" : "，有超长；")
                + "余量最小 " + tightCol + " " + tightLen + "/" + tightLimit
                + "（余 " + minMargin + "）" + over);
        return ok;
    }

    /**
     * P1–P4（H3）：JDBC 侧前置校验。
     * 顺序上先判 P2（主机）再判 P1（库名）—— 因本机开发库与 VM 库同名（TD-13），
     * 只看到 DATABASE()=mall_management_sql 无法区分是哪台机器，必须先过主机门禁。
     */
    static boolean preflightJdbc(Opt o, Map<String, String> pre) {
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword)) {

            // P2 主机门禁（OD-4：不符即 exit 2）
            String host = hostOf(o.jdbcUrl);
            boolean p2 = o.expectDbHost.equals(host);
            pre.put("P2", (p2 ? "PASS" : "FAIL")
                    + " 实际主机=" + host + " 期望主机=" + o.expectDbHost);
            if (!p2) {
                System.err.println("脚本连的不是期望主机，拒绝继续"
                        + "（防「脚本连了 VM、后端连了本机」这一半错位）。");
                return false;
            }

            // P1 连通 + 库名 + 服务端版本
            // 版本这项是 S0-5 第 3 步的判据：只验「能连上」不够 —— 本机开发库与 VM 库同名
            // （TD-13），8.0.32 同样连得上，若不比对版本，跑在 8.0.32 上会静默通过，
            // 而 A-S4a-7 依赖的是 8.4 实测的隔离级别行为（锁 3）。
            String db = scalarString(c, "SELECT DATABASE()");
            vmVersion = scalarString(c, "SELECT VERSION()");
            boolean dbOk = "mall_management_sql".equals(db);
            boolean verOk = vmVersion != null && vmVersion.startsWith("8.4.");
            boolean p1 = dbOk && verOk;
            pre.put("P1", (p1 ? "PASS " : "FAIL ")
                    + "DATABASE()=" + db + "（TD-13 要求 mall_management_sql " + (dbOk ? "✓" : "✗")
                    + "）；VERSION()=" + vmVersion + "（S0-5 要求 8.4.x " + (verOk ? "✓" : "✗") + "）");
            if (!p1) {
                pre.put("P3", "SKIP P1 未过，未查表数");
                pre.put("P4", "SKIP 同上");
                return false;
            }

            // P3 表数 11（兼 TD-01 校验：ddl-auto=update 启动后未反向改表）
            int tables = scalarInt(c,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()");
            boolean p3 = tables == 11;
            pre.put("P3", (p3 ? "PASS" : "FAIL") + " 表数=" + tables + "（期望 11）");

            // P4 种子计数：确认是刚导入过的干净实例，而非被上一轮污染过的库
            String seedSql = "SELECT "
                    + "(SELECT COUNT(*) FROM mall_management_sql.`order`),"
                    + "(SELECT COUNT(*) FROM mall_management_sql.goods),"
                    + "(SELECT COUNT(*) FROM mall_management_sql.login),"
                    + "(SELECT COUNT(*) FROM mall_management_sql.user),"
                    + "(SELECT COUNT(*) FROM mall_management_sql.friend)";
            int[] got = new int[5];
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(seedSql)) {
                if (r.next()) {
                    for (int i = 0; i < 5; i++) {
                        got[i] = r.getInt(i + 1);
                    }
                }
            }
            // 期望 order=6 / goods=5 / login=5 / user=5 / friend=4（mall_management_sql.sql 逐行计数）
            int[] want = {6, 5, 5, 5, 4};
            String[] names = {"order", "goods", "login", "user", "friend"};
            boolean p4 = true;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                boolean e = got[i] == want[i];
                p4 &= e;
                sb.append(names[i]).append('=').append(got[i])
                  .append(e ? "" : "(期望 " + want[i] + ")").append(' ');
            }
            pre.put("P4", (p4 ? "PASS" : "FAIL") + " " + sb.toString().trim());

            return p1 && p3 && p4;

        } catch (SQLException e) {
            pre.put("P1", "FAIL JDBC 连接失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * P5–P7（H3）：HTTP 侧前置校验。
     *
     * P5 联合就绪探针 —— 轮询 GET /mysqlGoods/goodstype 直到响应含 "success":true。
     *   端口通不等于就绪：该端点 GET 会真落一次 DB（SELECT goodstype），
     *   只有它返回成功才同时证明 Web 层与 Hikari 连接池都可用。
     *   等待上限 --ready-timeout-sec（默认 60s），超时即 FAIL（不视为 skip）。
     *
     * P6 哨兵往返 —— JDBC 写一行 → HTTP 读回来 → 删除。证明「脚本写的」与「后端读的」是同一个库。
     *   探针串已按 goodstype.GoodType 的 char(20) 上限重设计为 A3P-&lt;runId8&gt;（12 字符）：
     *   H3 原式 A3PROBE-&lt;runId8&gt;-&lt;host&gt; 是 32 字符，在严格模式下必然 ERROR 1406。
     *   主机判别码随行放在 GoodTypeNew（int）列：IPv4 第三段*1000 + 第四段
     *   （VM 192.168.229.129 → 229129，本机 127.0.0.1 → 1）。
     *
     * P7 阴性对照（锁 1：host 为主，version 为辅 —— 任一个成立即通过）：
     *   主判据 host —— P6 写进目标库的那行，在本机库中查不到（探针行的读取窗口留在删除之前，
     *   否则「查不到」对两台机器都成立，判据会退化成恒真）；
     *   辅判据 version —— 本机可达且本机 VERSION() ≠ 目标 VERSION()。
     *   本机不可达 → 记 skip，不阻断（CI 上本就没有本机库）。
     *   本机库全程只读：A3 未获授权改动本机库，阴性对照不需要写。
     */
    static boolean preflightHttp(Opt o, Map<String, String> pre) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        String url = o.baseUrl + "/mysqlGoods/goodstype";

        // ---------- P5 联合就绪探针 ----------
        long t0 = System.currentTimeMillis();
        int code = -1;
        String body = null;
        String lastErr = null;
        while (System.currentTimeMillis() - t0 < o.readyTimeoutSec * 1000L) {
            try {
                HttpResponse<String> r = httpGet(http, url);
                code = r.statusCode();
                body = r.body();
                if (code == 200 && body != null && body.contains("\"success\":true")) {
                    break;
                }
                lastErr = "HTTP " + code + "，响应中不含 \"success\":true";
            } catch (Exception e) {
                // ConnectException 的 getMessage() 常为 null，直接拼会出现「ConnectException: null」
                lastErr = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "（无附加信息）" : ": " + e.getMessage());
                code = -1;
                body = null;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long waited = (System.currentTimeMillis() - t0) / 1000;
        boolean p5 = code == 200 && body != null && body.contains("\"success\":true");
        pre.put("P5", (p5 ? "PASS" : "FAIL")
                + " GET " + url + " → HTTP " + code + "，等待 " + waited + "s"
                + (p5 ? "，含 \"success\":true（Web+DB 联合就绪）"
                      : "（" + (lastErr == null ? "超时" : lastErr) + "）"));
        if (!p5) {
            // 显式记 SKIP 而不是直接少两行：前置表缺行与「没跑到」在报告里无法区分
            pre.put("P6", "SKIP 后端未就绪，未做哨兵往返");
            pre.put("P7", "SKIP 同上");
            return false;
        }

        // ---------- P6 哨兵往返；P7 之后才删除 ----------
        probeGoodType = PROBE_PREFIX + o.runId;
        try {
            boolean p6 = probeRoundTrip(o, http, url, pre);
            if (!p6) {
                pre.put("P7", "SKIP 哨兵未验证，阴性对照无从对照");
                return false;
            }
            return negativeControl(o, pre);
        } finally {
            deleteProbeRow(o);
        }
    }

    /** P6：写入哨兵行并确认后端经 HTTP 读得到。 */
    static boolean probeRoundTrip(Opt o, HttpClient http, String url, Map<String, String> pre) {
        int hostCode = hostCodeOf(o.expectDbHost);
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mall_management_sql.goodstype (GoodType, GoodTypeNew) VALUES (?, ?)")) {
                ps.setString(1, probeGoodType);
                ps.setInt(2, hostCode);
                ps.executeUpdate();
            }
            String b = httpGet(http, url).body();
            boolean seen = b != null && b.contains(probeGoodType);
            // 相邻字段一并比对：列顺序与 Jackson 序列化顺序一致（ColumnMapRowMapper 用 LinkedHashMap），
            // 因此 "GoodType":"A3P-xxx","GoodTypeNew":229129 应当紧邻出现。仅作强化证据，不作门禁。
            boolean strict = b != null && b.contains(
                    "\"GoodType\":\"" + probeGoodType + "\",\"GoodTypeNew\":" + hostCode);
            pre.put("P6", (seen ? "PASS" : "FAIL")
                    + " 哨兵 " + probeGoodType + "（" + probeGoodType.length() + " 字符，上限 20）"
                    + " 经 HTTP 读回=" + seen + "，主机码 " + hostCode + " 随行=" + strict);
            if (!seen) {
                System.err.println("后端读不到脚本刚写入的哨兵行 —— 后端连的库与脚本连的库不是同一个。");
            }
            return seen;
        } catch (SQLException e) {
            pre.put("P6", "FAIL 哨兵往返失败: " + e.getMessage());
            return false;
        } catch (Exception e) {
            pre.put("P6", "FAIL 哨兵往返失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /** 删除哨兵行。best-effort：删不掉只告警，不让 finally 覆盖掉真正的失败原因。 */
    static void deleteProbeRow(Opt o) {
        if (probeGoodType == null) {
            return;
        }
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword);
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM mall_management_sql.goodstype WHERE GoodType = ?")) {
            ps.setString(1, probeGoodType);
            int n = ps.executeUpdate();
            System.out.println("[P6] 哨兵行清理：删除 " + n + " 行（" + probeGoodType + "）");
        } catch (SQLException e) {
            System.err.println("[P6] 哨兵行清理失败（cleanup 的 A3P-% 一项会兜底）: " + e.getMessage());
        }
    }

    /** P7：阴性对照。锁 1 —— host 判据与 version 判据任一成立即通过；本机不可达记 skip。 */
    static boolean negativeControl(Opt o, Map<String, String> pre) {
        if (o.skipNegativeControl) {
            pre.put("P7", "SKIP 已用 --skip-negative-control 显式关闭");
            return true;
        }
        String ncHost = hostOf(o.negativeControlUrl);
        if (o.expectDbHost.equals(ncHost)) {
            pre.put("P7", "FAIL 阴性对照库与目标库是同一主机（" + ncHost + "），对照无意义");
            return false;
        }
        try (Connection c = DriverManager.getConnection(
                o.negativeControlUrl, o.negativeControlUser, o.negativeControlPassword)) {
            // 主判据：探针行不在本机（此刻该行在目标库中仍存在）
            int localProbeRows = scalarInt(c,
                    "SELECT COUNT(*) FROM mall_management_sql.goodstype WHERE GoodType = '" + probeGoodType + "'");
            boolean rowAbsent = localProbeRows == 0;
            // 辅判据：本机版本 ≠ 目标版本（锁 1 —— 本机将来升级到 8.4 时此判据失效，故不单独作数）
            String localVer = scalarString(c, "SELECT VERSION()");
            boolean verDiff = localVer != null && !localVer.equals(vmVersion);
            boolean p7 = rowAbsent || verDiff;
            pre.put("P7", (p7 ? "PASS" : "FAIL")
                    + " 本机(" + ncHost + ") 探针行数=" + localProbeRows + "→不在本机=" + rowAbsent
                    + "；本机 VERSION()=" + localVer + " ≠ 目标 " + vmVersion + "→" + verDiff);
            if (!p7) {
                System.err.println("本机库与目标库未能区分 —— 可能本机 127.0.0.1 就是目标实例（隧道/端口转发）。");
            }
            return p7;
        } catch (SQLException e) {
            pre.put("P7", "SKIP 本机库不可达，阴性对照跳过（不阻断）: " + e.getMessage());
            return true;
        }
    }

    static HttpResponse<String> httpGet(HttpClient http, String url)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 主机判别码（放进 goodstype.GoodTypeNew 这个 int 列）。
     * IPv4 → 第三段*1000 + 第四段（VM 192.168.229.129 → 229129，本机 127.0.0.1 → 1）。
     * 非 IPv4（主机名等）退回 hashCode 截断；只要求「不同主机取到不同值」，不要求可逆。
     */
    static int hostCodeOf(String host) {
        if (host == null) {
            return 0;
        }
        String[] p = host.split("[.]");
        if (p.length == 4) {
            try {
                int a = Integer.parseInt(p[2].trim());
                int b = Integer.parseInt(p[3].trim());
                return a * 1000 + b;
            } catch (NumberFormatException ignore) {
                // 落到下面的兜底
            }
        }
        return Math.abs(host.hashCode() % 1000000);
    }

    static void printPreflight(Map<String, String> pre) {
        System.out.println("--- 前置校验 ---");
        for (Map.Entry<String, String> e : pre.entrySet()) {
            System.out.println("  " + e.getKey() + "  " + e.getValue());
        }
        System.out.println();
    }

    // ===================== 工具 =====================
    static String scalarString(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    static int scalarInt(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getInt(1) : -1;
        }
    }

    static String randomHex(int n) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(Character.forDigit(r.nextInt(16), 16));
        }
        return sb.toString();
    }

    /** 供后续阶段使用：解析 jdbcUrl 中的主机名（P2 门禁与 P6/P7 主机判别码都依赖它）。 */
    static String hostOf(String jdbcUrl) {
        int s = jdbcUrl.indexOf("//");
        if (s < 0) {
            return null;
        }
        int from = s + 2;
        int e = jdbcUrl.indexOf(':', from);
        if (e < 0) {
            e = jdbcUrl.indexOf('/', from);
        }
        if (e < 0) {
            e = jdbcUrl.length();
        }
        return jdbcUrl.substring(from, e);
    }
}
