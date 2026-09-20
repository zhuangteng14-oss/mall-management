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
 *   阶段 A  参数解析 + 退出码 + 空壳场景        ← 本文件当前所处阶段
 *   阶段 B  P1–P4（JDBC 侧前置校验）
 *   阶段 C  P5–P7（HTTP 侧前置校验）
 *   阶段 D  六场景（S1 / S2a / S2b / S3 / S4a / S4b）
 *   阶段 E  run.sh 完整版 + dependency:build-classpath 解析
 */

import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class ConcurrencyRunner {

    // ===================== 命名空间 =====================
    // 前缀固定为字面量，不接受调用方任意指定（H3 cleanup.safety_rails）
    static final String NS_PREFIX = "A3TEST-";

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

        // 阶段 A：仅做参数与退出码，场景为空壳。
        // 阶段 B 起在 preflight() 前插入 P1–P4，阶段 C 插入 P5–P7，
        // 任一前置失败一律 exit 2，不进入场景（H3 phase_0_preflight.purpose）。
        System.out.println("[阶段 A] 骨架就绪：参数解析与退出码已实现，preflight 与场景待后续阶段。");

        int rc = 0;
        rc = Math.max(rc, runScenarioStubs(o));

        System.out.println();
        System.out.println("runId      : " + o.runId);
        System.out.println("target     : " + o.baseUrl + "  →  " + o.jdbcUrl);
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

    // ===================== 工具 =====================
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
