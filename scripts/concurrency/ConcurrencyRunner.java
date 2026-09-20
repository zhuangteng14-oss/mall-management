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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;

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

    // 本轮 runId 与全局 HttpClient（场景与前置校验共用同一个连接池）
    static String RUN_ID = null;
    static HttpClient HTTP = null;

    static String ns() {
        return NS_PREFIX + RUN_ID;
    }

    static String cid() {
        return "A3C-" + RUN_ID;
    }

    static String mid() {
        return "A3M-" + RUN_ID;
    }

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
        // 场景选择（阶段 D 可逐个跑）；H3 的六段负载即此六项
        String scenarios = "S1,S2a,S2b,S3,S4a,S4b";
        // S4b 时长。H3 定 60s；OD-8（D2）尚未裁决，故做成可覆盖项，默认随 H3。
        int s4bDurationSec = 60;
        String reportDir = "scripts/concurrency/reports";
        boolean help = false;

        boolean has(String s) {
            for (String x : scenarios.split(",")) {
                if (x.trim().equalsIgnoreCase(s)) {
                    return true;
                }
            }
            return false;
        }
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
        "  --scenarios A,B         只跑指定负载段，默认 S1,S2a,S2b,S3,S4a,S4b",
        "  --s4b-duration-sec N    S4b 混合负载时长，默认 60（H3 值；OD-8/ D2 未裁决）",
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
        RUN_ID = o.runId;
        HTTP = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

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

        if (o.cleanupOnly) {
            cleanup(o);
            System.out.println("--cleanup-only：未产生任何业务流量。退出码 0。");
            System.exit(EXIT_PASS);
            return;
        }

        System.out.println("[阶段 D] preflight P0–P7 通过，开始跑负载段：" + o.scenarios);

        int rc = 0;
        try {
            rc = Math.max(rc, runScenarios(o));
        } finally {
            if (!o.keepData) {
                cleanup(o);
            } else {
                System.out.println("[cleanup] --keep-data：跳过清理，命名空间 " + ns() + "% 的行保留以供人工查验。");
            }
        }
        printAssertions();

        System.out.println();
        System.out.println("runId      : " + o.runId);
        System.out.println("target     : " + o.baseUrl + "  →  " + o.jdbcUrl);
        System.out.println("preflight  : " + pre.size() + " 项，全部通过");
        System.out.println("gates      : " + (rc == 0 ? "全部通过" : "失败 " + rc + " 项（见上表）"));
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
                case "--scenarios": o.scenarios = value(a, ++i, v, k); break;
                case "--s4b-duration-sec": o.s4bDurationSec = intValue(a, ++i, v, k); break;
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
        System.out.println("scenarios      : " + o.scenarios + "  (S4b " + o.s4bDurationSec + "s)");
        System.out.println("reportDir      : " + o.reportDir);
        System.out.println();
    }

    static String mask(String s) {
        if (s == null || s.isEmpty()) {
            return "(空)";
        }
        return "*".repeat(s.length());
    }

    // ===================== 断言记录（H3 verdict 枚举，锁 C3 用下划线形态） =====================

    static class Ass {
        final String id;
        final String desc;
        final boolean gate;      // gate=true 进退出码；false 仅记录
        String verdict = "SKIP";
        String detail = "";

        Ass(String id, String desc, boolean gate) {
            this.id = id;
            this.desc = desc;
            this.gate = gate;
        }
    }

    static final List<Ass> ASS = new ArrayList<>();

    /** 期望 verdict 表。默认 PASS；A-S2a-1 例外 —— H3 明示其「当前应看到缺陷存在」。 */
    static final Map<String, String> EXPECT = new LinkedHashMap<>();

    static {
        EXPECT.put("A-S2a-1", "EXPECTED_DEFECT_PRESENT");
    }

    static Ass ass(String id, String desc, boolean gate) {
        Ass x = new Ass(id, desc, gate);
        ASS.add(x);
        return x;
    }

    static void settle(Ass x, boolean pass, String detail) {
        x.verdict = pass ? "PASS" : "FAIL";
        x.detail = detail;
    }

    /**
     * 缺陷存在型断言的结算。DEFECT_FIXED 是「红」：不是被测对象变好，而是断言已与现状脱节，
     * 必须同步改断言（H3 对 A-S2a-1 的原话）。
     */
    static void settleDefect(Ass x, boolean defectPresent, String detail) {
        x.verdict = defectPresent ? "EXPECTED_DEFECT_PRESENT" : "DEFECT_FIXED";
        x.detail = detail;
    }

    static int gateFailures() {
        int n = 0;
        for (Ass a : ASS) {
            if (a.gate && !a.verdict.equals(EXPECT.getOrDefault(a.id, "PASS"))) {
                n++;
            }
        }
        return n;
    }

    static void printAssertions() {
        System.out.println();
        System.out.println("--- 断言 ---");
        for (Ass a : ASS) {
            String exp = EXPECT.getOrDefault(a.id, "PASS");
            boolean bad = a.gate && !a.verdict.equals(exp);
            System.out.println("  " + pad(a.id, 11) + (a.gate ? "gate " : "     ")
                    + pad(a.verdict, 24) + (bad ? "✗ " : "  ") + a.desc
                    + (a.detail.isEmpty() ? "" : "  | " + a.detail));
        }
        int g = 0;
        for (Ass a : ASS) {
            if (a.gate) {
                g++;
            }
        }
        System.out.println("  门禁断言 " + g + " 项，失败 " + gateFailures() + " 项");
    }

    static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s == null ? "" : s);
        while (b.length() < n) {
            b.append(' ');
        }
        return b.toString();
    }

    // ===================== HTTP 调用与负载 =====================

    static class Resp {
        final int code;
        final String body;
        final long ns;

        Resp(int code, String body, long ns) {
            this.code = code;
            this.body = body;
            this.ns = ns;
        }

        boolean successTrue() {
            return body != null && body.contains("\"success\":true");
        }

        boolean says(String s) {
            return body != null && body.contains(s);
        }
    }

    static class Call {
        final String tag;
        final Resp r;

        Call(String tag, Resp r) {
            this.tag = tag;
            this.r = r;
        }
    }

    interface Task {
        Call run(int idx);
    }

    interface Body {
        void run() throws Exception;
    }

    static Call call(String tag, Resp r) {
        return new Call(tag, r);
    }

    static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** form == null → GET；否则 POST application/x-www-form-urlencoded。异常折叠成 code=-1。 */
    static Resp send(HttpClient http, String url, Map<String, String> form) {
        long t0 = System.nanoTime();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
            if (form == null) {
                b.GET();
            } else {
                b.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                 .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Resp(r.statusCode(), r.body(), System.nanoTime() - t0);
        } catch (Exception e) {
            return new Resp(-1, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), System.nanoTime() - t0);
        }
    }

    /**
     * 一档负载的计数与延迟。
     * tag 维度是必需的：S1/S4b 混跑多端点，只统计总数会把一个端点的错判记到另一个头上。
     */
    static class Load {
        final LongAdder ok = new LongAdder();
        final LongAdder http5xx = new LongAdder();
        final LongAdder connErr = new LongAdder();
        final LongAdder unexpected = new LongAdder();
        final Map<String, LongAdder> tagBad = new ConcurrentHashMap<>();
        final Map<String, LongAdder> tagOk = new ConcurrentHashMap<>();
        final List<String> samples = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentLinkedQueue<Long> lat = new ConcurrentLinkedQueue<>();
        final long t0 = System.nanoTime();

        long elapsedMs() {
            return (System.nanoTime() - t0) / 1000000L;
        }

        void add(Call c, boolean expected) {
            lat.add(c.r.ns);
            boolean good = false;
            if (c.r.code == -1) {
                connErr.increment();
                note("connErr[" + c.tag + "] " + c.r.body);
            } else if (c.r.code >= 500) {
                http5xx.increment();
                note("HTTP " + c.r.code + "[" + c.tag + "] " + trunc(c.r.body));
            } else if (c.r.code == 200 && expected) {
                ok.increment();
                good = true;
            } else {
                unexpected.increment();
                note("HTTP " + c.r.code + "[" + c.tag + "] " + trunc(c.r.body));
            }
            LongAdder a = (good ? tagOk : tagBad).computeIfAbsent(c.tag, k -> new LongAdder());
            a.increment();
        }

        void note(String s) {
            synchronized (samples) {
                if (samples.size() < 5) {
                    samples.add(s);
                }
            }
        }

        long bad(String tag) {
            LongAdder a = tagBad.get(tag);
            return a == null ? 0 : a.sum();
        }

        long good(String tag) {
            LongAdder a = tagOk.get(tag);
            return a == null ? 0 : a.sum();
        }

        long total() {
            return ok.sum() + http5xx.sum() + connErr.sum() + unexpected.sum();
        }

        /** 仅 paced 档位设置：把「目标速率」与「真实达速率」并排打印，免得只报一个数。 */
        int offeredQps = 0;

        double qpsNum() {
            return total() * 1000.0 / Math.max(1, elapsedMs());
        }

        String qpsOf() {
            return String.format("%.1f", qpsNum());
        }

        /**
         * S4b 满负载档的教训：背压之前（开环提交 + 无界队列），achieved 是「排空期均值」——
         * 7200 条全发出去了、却是在 105s 里跑完的，读起来像「按 68 QPS 跑」，
         * 而真相是「按 120 QPS 提交、被测端只能吃 68」。背压修好后 achieved 即可持续速率。
         * 是否达速只记录、不门禁（H3 note_on_120qps_ceiling 只要求记 QPS）。
         */
        String line() {
            String head = (offeredQps > 0 ? "offered=" + offeredQps + " " : "") + "achieved=" + qpsOf() + "QPS";
            String tail = "";
            if (offeredQps > 0) {
                tail = String.format(" wall=%.1fs", elapsedMs() / 1000.0);
                if (qpsNum() < offeredQps * 0.95) {
                    tail += " ←未达速（只记录，不门禁）";
                }
            }
            return head + " ok=" + ok.sum() + " unexpected=" + unexpected.sum()
                    + " 5xx=" + http5xx.sum() + " connErr=" + connErr.sum() + tail + " " + pct();
        }

        String pct() {
            List<Long> v = new ArrayList<>(lat);
            Collections.sort(v);
            if (v.isEmpty()) {
                return "n=0";
            }
            return String.format("n=%d p50=%.1f p95=%.1f p99=%.1f max=%.1f(ms)",
                    v.size(), ms(v, 0.50), ms(v, 0.95), ms(v, 0.99), v.get(v.size() - 1) / 1e6);
        }

        void dumpSamples() {
            synchronized (samples) {
                for (String s : samples) {
                    System.out.println("      · " + s);
                }
            }
        }
    }

    static double ms(List<Long> sorted, double q) {
        int i = (int) Math.min(sorted.size() - 1L, Math.round(q * (sorted.size() - 1)));
        return sorted.get(Math.max(0, i)) / 1e6;
    }

    static String trunc(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 160 ? s : s.substring(0, 160) + "…";
    }

    static final int PACE_THREADS = 32;

    /**
     * 定速负载：按「已流逝毫秒 × QPS」算应发条数，而不是 sleep(1000/qps)。
     * 后者在 Windows 上受 ~15ms 时钟粒度影响会严重漂移；前者只受粒度影响「批次形状」，
     * 不影响总条数与平均 QPS（真实达速记在 achieved 里）。
     *
     * total 由调用方给而不是 qps×dur 现算：S4b 的条数必须恰为 20 的整数倍
     * （每 20 条一循环、按槽位分工），否则会越出预建单池、把「池外单」算成 unexpected。
     *
     * 背压（Semaphore）不是可选项：没有它，提交按计划走完 60s、被测端只吃了多少就堆多少，
     * 队列在提交结束后继续排空，于是 ① achieved 变成「排空期均值」，低报成降速跑；
     * ② 墙钟远超名义时长。有了它，提交速率被压到可持续速率，achieved 才等于真实达速率。
     */
    static Load paced(int qps, int total, Task task, BiPredicate<Integer, Resp> expect)
            throws InterruptedException {
        Load ld = new Load();
        ld.offeredQps = qps;
        ExecutorService pool = Executors.newFixedThreadPool(PACE_THREADS);
        Semaphore slots = new Semaphore(PACE_THREADS);
        long t0 = System.nanoTime();
        int submitted = 0;
        while (submitted < total) {
            long elapsedMs = (System.nanoTime() - t0) / 1000000L;
            int due = (int) Math.min(total, elapsedMs * (long) qps / 1000L);
            while (submitted < due) {
                final int i = submitted++;
                slots.acquire();
                pool.submit(() -> {
                    try {
                        Call c = task.run(i);
                        ld.add(c, expect.test(i, c.r));
                    } finally {
                        slots.release();
                    }
                });
            }
            Thread.sleep(1);
        }
        pool.shutdown();
        if (!pool.awaitTermination(600, TimeUnit.SECONDS)) {
            System.err.println("[负载] 600s 内未排空，计数不完整（各端点数放行断言会因此失败）");
        }
        return ld;
    }

    /** 同栅栏齐发：CyclicBarrier 保证 n 个线程尽量同时进入被测端点。 */
    static List<Call> burst(int threads, Task task) throws InterruptedException {
        List<Call> out = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    gate.await(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return;
                }
                out.add(task.run(idx));
            });
        }
        pool.shutdown();
        pool.awaitTermination(180, TimeUnit.SECONDS);
        return out;
    }

    static int countSuccess(List<Call> cs) {
        int n = 0;
        for (Call c : cs) {
            if (c.r.successTrue()) {
                n++;
            }
        }
        return n;
    }

    static int countMsg(List<Call> cs, String msg) {
        int n = 0;
        for (Call c : cs) {
            if (c.r.says(msg)) {
                n++;
            }
        }
        return n;
    }

    static int count5xx(List<Call> cs) {
        int n = 0;
        for (Call c : cs) {
            if (c.r.code >= 500 || c.r.code == -1) {
                n++;
            }
        }
        return n;
    }

    static int countDbError(List<Call> cs) {
        int n = 0;
        for (Call c : cs) {
            if (c.r.says("数据库错误")) {
                n++;
            }
        }
        return n;
    }

    static Map<String, String> form(Object... kv) {
        Map<String, String> f = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            f.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return f;
    }

    /**
     * orderAdd 的入参。Images 是字符串入参、不落盘 —— pack 明示这是规避 TD-08 污染的关键，
     * 故全程只用 orderAdd 系列，绝不走 goodsAdd 的 MultipartFile 分支。
     */
    static Map<String, String> orderForm(String orderId, int payIf, int deliveryIf, int receiveIf) {
        return form("CustomerId", cid(),
                "MerchantId", mid(),
                "OrderId", orderId,
                "Price", "1.5",
                "ProductInformation", "A3-" + RUN_ID + "-PI",
                "Images", "A3-" + RUN_ID + "-IMG",
                "PayIf", payIf,
                "DeliveryIf", deliveryIf,
                "ReceiveIf", receiveIf);
    }

    // ===================== JDBC 侧核对 =====================

    static int jdbcCount(Opt o, String sql, Object... args) {
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? r.getInt(1) : -1;
            }
        } catch (SQLException e) {
            System.err.println("[JDBC] 查询失败: " + e.getMessage() + "  ← " + sql);
            return -1;
        }
    }

    /** 单行订单的状态三元组；不存在或异常返回 null。 */
    static int[] jdbcOrderState(Opt o, String orderId) {
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT PayIf, DeliveryIf, ReceiveIf, (SettlementDate IS NOT NULL) "
                   + "FROM mall_management_sql.`order` WHERE OrderId = ?")) {
            ps.setString(1, orderId);
            try (ResultSet r = ps.executeQuery()) {
                if (r.next()) {
                    return new int[]{r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4)};
                }
                return null;
            }
        } catch (SQLException e) {
            System.err.println("[JDBC] 查状态失败 " + orderId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * record_only：死锁 / 锁等待累计计数快照。best-effort，读不到不影响门禁。
     * 列名经 information_schema 核实：该视图是 ERROR_NUMBER / ERROR_NAME / SQL_STATE /
     * SUM_ERROR_RAISED / SUM_ERROR_HANDLED / FIRST_SEEN / LAST_SEEN —— **没有** ERROR_COUNT
     * （首版按印象写成 ERROR_COUNT，被运行时的 Unknown column 打回）。
     */
    static String lockCounters(Opt o) {
        String sql = "SELECT IFNULL(SUM(CASE WHEN ERROR_NUMBER = 1213 THEN SUM_ERROR_RAISED END),0),"
                   + "IFNULL(SUM(CASE WHEN ERROR_NUMBER = 1205 THEN SUM_ERROR_RAISED END),0) "
                   + "FROM performance_schema.events_errors_summary_global_by_error";
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword);
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            if (r.next()) {
                return "1213=" + r.getInt(1) + " 1205=" + r.getInt(2);
            }
            return "读不到";
        } catch (SQLException e) {
            return "不可读(" + e.getMessage() + ")";
        }
    }

    // ===================== 负载段 =====================

    static int runScenarios(Opt o) {
        if (o.has("S1")) {
            guard("S1", () -> s1(o));
        }
        if (o.has("S2a")) {
            guard("S2a", () -> s2a(o));
        }
        if (o.has("S2b")) {
            guard("S2b", () -> s2b(o));
        }
        if (o.has("S3")) {
            guard("S3", () -> s3(o));
        }
        if (o.has("S4a")) {
            guard("S4a", () -> s4a(o));
        }
        if (o.has("S4b")) {
            guard("S4b", () -> s4b(o));
        }
        return gateFailures();
    }

    /**
     * 锁计数的场景前后增量。pack 明示「死锁在小表上绿色不构成有意义证据」，
     * 故只记录、不作门禁；H3 要求的是**每场景**的前后增量，不是整轮一次。
     */
    static void lockDelta(Opt o, String scene, String before) {
        String after = lockCounters(o);
        System.out.println("[记录] " + scene + " 锁计数 前=" + before + " 后=" + after
                + "（1213=ER_LOCK_DEADLOCK 1205=ER_LOCK_WAIT_TIMEOUT；record-only，非门禁）");
    }

    static void guard(String name, Body b) {
        try {
            b.run();
        } catch (Throwable e) {
            Ass x = ass(name + "-异常", "负载段 " + name + " 执行抛异常（该段断言按 SKIP 记，门禁判失败）", true);
            x.verdict = "FAIL";
            x.detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[" + name + "] 执行异常: ");
            e.printStackTrace(System.err);
        }
    }

    // ---------- S1 只读 ----------

    static void s1(Opt o) throws InterruptedException {
        Ass a1 = ass("A-S1-1", "HTTP 5xx == 0 且连接错误 == 0", true);
        Ass a2 = ass("A-S1-2", "goodstype 响应全部 success:true", true);
        Ass a3 = ass("A-S1-3", "loginSelect('123','123456') 每次返回 1（0 即读侧错库或数据缺失）", true);
        Map<String, String> login = form("username", "123", "password", "123456");
        Load ld = paced(o.qps, o.qps * o.durationSec,
                i -> (i & 1) == 0
                        ? call("goodstype", send(HTTP, o.baseUrl + "/mysqlGoods/goodstype", null))
                        : call("loginSelect", send(HTTP, o.baseUrl + "/mysqlLogin/loginSelect", login)),
                (i, r) -> r.code == 200 && ((i & 1) == 0
                        ? r.successTrue()
                        : r.body != null && "1".equals(r.body.trim())));
        settle(a1, ld.http5xx.sum() == 0 && ld.connErr.sum() == 0,
                "5xx=" + ld.http5xx.sum() + " connErr=" + ld.connErr.sum());
        settle(a2, ld.bad("goodstype") == 0, "goodstype 非预期=" + ld.bad("goodstype"));
        settle(a3, ld.bad("loginSelect") == 0 && ld.good("loginSelect") > 0,
                "loginSelect 返回非 1 的次数=" + ld.bad("loginSelect") + "，返回 1 的次数=" + ld.good("loginSelect"));
        System.out.println("[S1] 只读 50% goodstype + 50% loginSelect  " + ld.line());
        ld.dumpSamples();
    }

    // ---------- S2a 下单（同参同 OrderId） ----------

    static void s2a(Opt o) throws InterruptedException {
        Ass a1 = ass("A-S2a-1", "JDBC count(order WHERE OrderId=?) >= 2", true);
        Ass a2 = ass("A-S2a-2", "该 OrderId 行数 == 本轮 success:true 响应数", true);
        String orderId = ns() + "-DUP";
        final Map<String, String> f = orderForm(orderId, 0, 0, 0);
        List<Call> cs = burst(20, i -> call("orderAdd", send(HTTP, o.baseUrl + "/mysqlGoods/orderAdd", f)));
        int succ = countSuccess(cs);
        int rows = jdbcCount(o, "SELECT COUNT(*) FROM mall_management_sql.`order` WHERE OrderId = ?", orderId);
        if (rows < 0) {
            // 查询失败不能被当成「缺陷已修复」—— -1 < 2 会走到 DEFECT_FIXED，那是假红
            settle(a1, false, "JDBC 行数查询失败（-1），无法判定缺陷是否仍存在");
        } else {
            settleDefect(a1, rows >= 2,
                    "行数=" + rows + "（>=2 即缺陷存在；若为 1 说明已被唯一索引修复，需同步改断言）"
                    + " 5xx=" + count5xx(cs));
        }
        settle(a2, rows == succ, "行数=" + rows + " == success 数=" + succ);
        System.out.println("[S2a] 20 线程同栅栏同 OrderId=" + orderId + " success=" + succ + " 行数=" + rows);
    }

    // ---------- S2b 下单（各 OrderId 唯一） ----------

    static void s2b(Opt o) throws InterruptedException {
        Ass a1 = ass("A-S2b-1", "success:true 比率 == 100%，无 5xx", true);
        Ass a2 = ass("A-S2b-2", "JDBC count(OrderId LIKE '<ns>-N%') == 成功响应数", true);
        Load ld = paced(o.qps, o.qps * o.durationSec,
                i -> call("orderAdd", send(HTTP, o.baseUrl + "/mysqlGoods/orderAdd",
                        orderForm(ns() + "-N" + i, 0, 0, 0))),
                (i, r) -> r.successTrue());
        int rows = jdbcCount(o, "SELECT COUNT(*) FROM mall_management_sql.`order` WHERE OrderId LIKE ?",
                ns() + "-N%");
        settle(a1, ld.ok.sum() == ld.total() && ld.http5xx.sum() == 0 && ld.connErr.sum() == 0,
                "ok=" + ld.ok.sum() + "/" + ld.total() + " unexpected=" + ld.unexpected.sum()
                + " 5xx=" + ld.http5xx.sum() + " connErr=" + ld.connErr.sum());
        settle(a2, rows == ld.ok.sum(), "行数=" + rows + " == success 数=" + ld.ok.sum());
        System.out.println("[S2b] 下单(独参) " + ld.line());
        ld.dumpSamples();
    }

    // ---------- S3 支付 ----------

    static final int S3_M = 10;   // 预建单数
    static final int S3_T = 20;   // 每单并发线程数

    static void s3(Opt o) throws InterruptedException {
        Ass a1 = ass("A-S3-1", "每单恰好 1 个 success:true；全场景合计 == M", true);
        Ass a2 = ass("A-S3-2", "每单恰好 " + (S3_T - 1) + " 个 success:false 且 msg == 「订单不存在或已付款」", true);
        Ass a3 = ass("A-S3-3", "无任何响应 msg 以「数据库错误」开头", true);
        Ass a4 = ass("A-S3-4", "JDBC：每单 PayIf=1；SettlementDate IS NOT NULL", true);

        // 预建：必须自建 —— 种子 6 单全是 (1,1,1) 终态，在它们身上「恰好 1 次成功」必不成立（F4）
        List<String> ids = new ArrayList<>();
        int preBuilt = 0;
        for (int i = 0; i < S3_M; i++) {
            String id = ns() + "-P" + i;
            Resp r = send(HTTP, o.baseUrl + "/mysqlGoods/orderAdd", orderForm(id, 0, 0, 0));
            if (r.successTrue()) {
                preBuilt++;
            } else {
                System.err.println("[S3] 预建失败 " + id + " → " + trunc(r.body));
            }
            ids.add(id);
        }

        int totalSucc = 0;
        int badUnits = 0;
        int dbErr = 0;
        int stateBad = 0;
        StringBuilder per = new StringBuilder();
        for (String id : ids) {
            final Map<String, String> f = form("orderId", id);
            List<Call> cs = burst(S3_T, i -> call("orderPayIfUp", send(HTTP, o.baseUrl + "/mysqlGoods/orderPayIfUp", f)));
            int s = countSuccess(cs);
            int m = countMsg(cs, "订单不存在或已付款");
            totalSucc += s;
            dbErr += countDbError(cs);
            if (s != 1 || m != S3_T - 1) {
                badUnits++;
                per.append(id).append("(succ=").append(s).append(",msg=").append(m).append(") ");
            }
            int[] st = jdbcOrderState(o, id);
            if (st == null || st[0] != 1 || st[3] != 1) {
                stateBad++;
                per.append(id).append("(状态=").append(st == null ? "无行" : (st[0] + "/" + st[1] + "/" + st[2] + ",sd=" + st[3])).append(") ");
            }
        }
        settle(a1, totalSucc == S3_M && badUnits == 0,
                "预建成功=" + preBuilt + "/" + S3_M + "；合计 success=" + totalSucc + "/" + S3_M
                + "；异常单=" + badUnits + (per.length() == 0 ? "" : " " + per));
        settle(a2, badUnits == 0, "不符合「1 成功 + " + (S3_T - 1) + " 失败且 msg 正确」的单数=" + badUnits);
        settle(a3, dbErr == 0, "含「数据库错误」的响应数=" + dbErr);
        settle(a4, stateBad == 0, "PayIf/SettlementDate 不符的单数=" + stateBad);
        System.out.println("[S3] 支付 M=" + S3_M + " 单 × " + S3_T + " 线程同栅栏  合计 success=" + totalSucc
                + " 异常单=" + badUnits + " 5xx=" + dbErr);
    }

    // ---------- S4a 状态机非法流转 + 跨接口竞态 ----------

    static void s4a(Opt o) throws InterruptedException {
        String lock0 = lockCounters(o);
        Ass a1 = ass("A-S4a-1", "orderDeliveryIfUp on U ×20 → success == 0，msg 正确", true);
        Ass a2 = ass("A-S4a-2", "orderDeliveryIfUp on P ×20 → success == 1（重复发货被拒）", true);
        Ass a3 = ass("A-S4a-3", "orderDeliveryIfUp on PDR ×20 → success == 0", true);
        Ass a4 = ass("A-S4a-4", "orderReceiveIfUp on U ×20 → success == 0（未付款收货被拒）", true);
        Ass a5 = ass("A-S4a-5", "orderReceiveIfUp on PR ×20 → success == 0（重复收货被拒）", true);
        Ass a6 = ass("A-S4a-6", "orderReceiveIfUp on PD ×20 → success == 1", true);
        Ass a7 = ass("A-S4a-7", "同单 20 pay + 20 delivery 同栅栏：paySucc==1 且 deliverySucc<=1", true);
        Ass a8 = ass("A-S4a-8", "全命名空间不变式 NOT(PayIf=0 AND (DeliveryIf=1 OR ReceiveIf=1))", true);

        // setup：三个状态位是 orderAdd 的入参，可任意置位 → 前置态直接构造（该「可伪造」本身是 D5 候选）
        String[] keys = {"U", "P", "PD", "PDR", "PR"};
        int[][] flags = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}};
        Map<String, String> idOf = new LinkedHashMap<>();
        StringBuilder forged = new StringBuilder();
        for (int k = 0; k < keys.length; k++) {
            String id = ns() + "-S4a" + keys[k];
            idOf.put(keys[k], id);
            Resp r = send(HTTP, o.baseUrl + "/mysqlGoods/orderAdd",
                    orderForm(id, flags[k][0], flags[k][1], flags[k][2]));
            forged.append(keys[k]).append('=').append(r.successTrue() ? "ok" : trunc(r.body)).append(' ');
        }

        String dlyMsg = "订单不存在或未付款或已发货/已收货";
        String rcvMsg = "订单未付款或已收货";

        List<Call> c1 = burst(20, i -> call("dly-U", send(HTTP, o.baseUrl + "/mysqlGoods/orderDeliveryIfUp", form("orderId", idOf.get("U")))));
        settle(a1, countSuccess(c1) == 0 && countMsg(c1, dlyMsg) == 20, "succ=" + countSuccess(c1) + " msg 命中=" + countMsg(c1, dlyMsg));

        List<Call> c2 = burst(20, i -> call("dly-P", send(HTTP, o.baseUrl + "/mysqlGoods/orderDeliveryIfUp", form("orderId", idOf.get("P")))));
        settle(a2, countSuccess(c2) == 1, "succ=" + countSuccess(c2));

        List<Call> c3 = burst(20, i -> call("dly-PDR", send(HTTP, o.baseUrl + "/mysqlGoods/orderDeliveryIfUp", form("orderId", idOf.get("PDR")))));
        settle(a3, countSuccess(c3) == 0, "succ=" + countSuccess(c3));

        List<Call> c4 = burst(20, i -> call("rcv-U", send(HTTP, o.baseUrl + "/mysqlGoods/orderReceiveIfUp", form("orderId", idOf.get("U")))));
        settle(a4, countSuccess(c4) == 0 && countMsg(c4, rcvMsg) == 20, "succ=" + countSuccess(c4) + " msg 命中=" + countMsg(c4, rcvMsg));

        List<Call> c5 = burst(20, i -> call("rcv-PR", send(HTTP, o.baseUrl + "/mysqlGoods/orderReceiveIfUp", form("orderId", idOf.get("PR")))));
        settle(a5, countSuccess(c5) == 0, "succ=" + countSuccess(c5));

        List<Call> c6 = burst(20, i -> call("rcv-PD", send(HTTP, o.baseUrl + "/mysqlGoods/orderReceiveIfUp", form("orderId", idOf.get("PD")))));
        settle(a6, countSuccess(c6) == 1, "succ=" + countSuccess(c6));

        // A-S4a-7：同一未付款单上 pay 与 delivery 同时释放。delivery 的 WHERE 要求 PayIf=1，
        // 故它最多在 pay 提交之后成功一次；不变式 NOT(DeliveryIf=1 AND PayIf=0) 是此项的真正判据。
        String raceId = ns() + "-S4aRACE";
        Resp rr = send(HTTP, o.baseUrl + "/mysqlGoods/orderAdd", orderForm(raceId, 0, 0, 0));
        final Map<String, String> payF = form("orderId", raceId);
        List<Call> c7 = burst(40, i -> call(i % 2 == 0 ? "pay-race" : "dly-race",
                send(HTTP, o.baseUrl + (i % 2 == 0 ? "/mysqlGoods/orderPayIfUp" : "/mysqlGoods/orderDeliveryIfUp"),
                        payF)));
        int paySucc = 0;
        int dlySucc = 0;
        for (Call c : c7) {
            if (!c.r.successTrue()) {
                continue;
            }
            if (c.tag.equals("pay-race")) {
                paySucc++;
            } else {
                dlySucc++;
            }
        }
        int[] st = jdbcOrderState(o, raceId);
        boolean inv7 = st != null && !(st[1] == 1 && st[0] == 0);
        settle(a7, rr.successTrue() && paySucc == 1 && dlySucc <= 1 && inv7,
                "预建=" + (rr.successTrue() ? "ok" : trunc(rr.body)) + " paySucc=" + paySucc + " deliverySucc=" + dlySucc
                + " 终态=" + (st == null ? "无行" : (st[0] + "/" + st[1] + "/" + st[2]))
                + " 不变式=" + (inv7 ? "成立" : "被破坏"));

        int viol = jdbcCount(o,
                "SELECT COUNT(*) FROM mall_management_sql.`order` WHERE OrderId LIKE ? "
              + "AND PayIf = 0 AND (DeliveryIf = 1 OR ReceiveIf = 1)", ns() + "%");
        settle(a8, viol == 0, "违反行数=" + viol
                + "（H3 原写「PR 型预置单排除在外」；此处未排除 —— PR 的 PayIf=1，本就不满足谓词，排除与否等价）");

        System.out.println("[S4a] 预置态 " + forged + "| 8 个 burst 完成"
                + " payRace=" + paySucc + " dlyRace=" + dlySucc + " 不变式违反=" + viol);
        lockDelta(o, "S4a", lock0);
    }

    // ---------- S4b 120 QPS 混合负载 ----------

    static void s4b(Opt o) throws InterruptedException {
        String lock0 = lockCounters(o);
        Ass a1 = ass("A-S4b-1", "5xx == 0，连接错误 == 0", true);
        Ass a2 = ass("A-S4b-2", "各端点 success 数 == 分区计算出的期望值", true);
        Ass a3 = ass("A-S4b-3", "结束后全命名空间不变式检查（同 A-S4a-8）", true);
        Ass a4 = ass("A-S4b-4", "JDBC count(OrderId LIKE '<ns>-S4bN%') == orderAdd 成功数", true);

        int dur = o.s4bDurationSec;
        int qps = o.qps;
        int cycles = qps * dur / 20;           // 每 20 条一循环：8 add + 4 pay + 3 dly + 3 rcv + 2 goodstype
        int total = cycles * 20;               // 恰好为 20 的整数倍，否则分工槽位会越出池
        int addWant = cycles * 8;
        int payWant = cycles * 4;
        int dlyWant = cycles * 3;
        int rcvWant = cycles * 3;
        int gsWant = cycles * 2;

        // 预建单池：每单在整轮内只被调用一次 → 每个端点的期望成功数可精确算出（H3 determinism_device）
        long t0 = System.currentTimeMillis();
        int bPay = prebuildPool(o, ns() + "-S4bPay", payWant, 0, 0, 0);
        int bDly = prebuildPool(o, ns() + "-S4bDlv", dlyWant, 1, 0, 0);
        int bRcv = prebuildPool(o, ns() + "-S4bRcv", rcvWant, 1, 0, 0);
        System.out.println("[S4b] 预建单池（JDBC 批量，setup 不计入负载）：pay=" + bPay + " dly=" + bDly + " rcv=" + bRcv
                + "，耗时 " + (System.currentTimeMillis() - t0) + "ms"
                + "；负载 " + qps + " QPS × " + dur + "s = " + total + " 条（" + cycles + " 循环 × 20 槽）");

        final java.util.concurrent.atomic.AtomicInteger iP = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger iD = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger iR = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger iA = new java.util.concurrent.atomic.AtomicInteger();

        Load ld = paced(qps, total, i -> {
            int slot = i % 20;
            if (slot < 8) {
                return call("orderAdd", send(HTTP, o.baseUrl + "/mysqlGoods/orderAdd",
                        orderForm(ns() + "-S4bN" + iA.getAndIncrement(), 0, 0, 0)));
            }
            if (slot < 12) {
                return call("orderPayIfUp", send(HTTP, o.baseUrl + "/mysqlGoods/orderPayIfUp",
                        form("orderId", ns() + "-S4bPay" + iP.getAndIncrement())));
            }
            if (slot < 15) {
                return call("orderDeliveryIfUp", send(HTTP, o.baseUrl + "/mysqlGoods/orderDeliveryIfUp",
                        form("orderId", ns() + "-S4bDlv" + iD.getAndIncrement())));
            }
            if (slot < 18) {
                return call("orderReceiveIfUp", send(HTTP, o.baseUrl + "/mysqlGoods/orderReceiveIfUp",
                        form("orderId", ns() + "-S4bRcv" + iR.getAndIncrement())));
            }
            return call("goodstype", send(HTTP, o.baseUrl + "/mysqlGoods/goodstype", null));
        }, (i, r) -> r.code == 200 && r.successTrue());

        settle(a1, ld.http5xx.sum() == 0 && ld.connErr.sum() == 0,
                "5xx=" + ld.http5xx.sum() + " connErr=" + ld.connErr.sum() + " unexpected=" + ld.unexpected.sum());
        long gAdd = ld.good("orderAdd");
        long gPay = ld.good("orderPayIfUp");
        long gDly = ld.good("orderDeliveryIfUp");
        long gRcv = ld.good("orderReceiveIfUp");
        long gGs = ld.good("goodstype");
        boolean per = gAdd == addWant && gPay == payWant && gDly == dlyWant && gRcv == rcvWant && gGs == gsWant;
        settle(a2, per, "add=" + gAdd + "/" + addWant + " pay=" + gPay + "/" + payWant
                + " dly=" + gDly + "/" + dlyWant + " rcv=" + gRcv + "/" + rcvWant + " goodstype=" + gGs + "/" + gsWant
                + "（任何多出来的成功都是真实的状态机重复流转）");

        int viol = jdbcCount(o,
                "SELECT COUNT(*) FROM mall_management_sql.`order` WHERE OrderId LIKE ? "
              + "AND PayIf = 0 AND (DeliveryIf = 1 OR ReceiveIf = 1)", ns() + "%");
        settle(a3, viol == 0, "违反行数=" + viol);

        int rows = jdbcCount(o, "SELECT COUNT(*) FROM mall_management_sql.`order` WHERE OrderId LIKE ?",
                ns() + "-S4bN%");
        settle(a4, rows == gAdd, "行数=" + rows + " == orderAdd 成功数=" + gAdd
                + "（H3 原文为「count LIKE 命名空间」；此处收窄到 -S4bN% 子集，"
                + "否则会把 3 个预建单池也算进去而必然不等 —— 收窄是加强，不是放宽）");

        System.out.println("[S4b] 混合 " + ld.line() + " 各端点=add" + gAdd + "/pay" + gPay
                + "/dly" + gDly + "/rcv" + gRcv + "/gs" + gGs + " 不变式违反=" + viol);
        ld.dumpSamples();
        lockDelta(o, "S4b", lock0);
    }

    /** S4b 预建单池：JDBC 批量插入。setup 不经端点，不影响 A-S4b-* 的判定。 */
    static int prebuildPool(Opt o, String idPrefix, int n, int pay, int dly, int rcv) {
        String sql = "INSERT INTO mall_management_sql.`order` "
                   + "(OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, "
                   + "PayIf, DeliveryIf, ReceiveIf, CreateTime) VALUES (?,?,?,?,?,?,?,?,?,NOW())";
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < n; i++) {
                ps.setString(1, idPrefix + i);
                ps.setString(2, cid());
                ps.setString(3, mid());
                ps.setDouble(4, 1.5);
                ps.setString(5, "A3-" + RUN_ID + "-PI");
                ps.setString(6, "A3-" + RUN_ID + "-IMG");
                ps.setInt(7, pay);
                ps.setInt(8, dly);
                ps.setInt(9, rcv);
                ps.addBatch();
            }
            return ps.executeBatch().length;
        } catch (SQLException e) {
            System.err.println("[S4b] 预建池失败 " + idPrefix + ": " + e.getMessage());
            return -1;
        }
    }

    // ===================== 清理 =====================

    /** 表 → 命名空间列（全部经 DDL 核对，不放猜测的列名）。 */
    static final Map<String, String[]> CLEANUP_COLS = new LinkedHashMap<>();

    static {
        CLEANUP_COLS.put("order", new String[]{"OrderId", "CustomerId", "MerchantId"});
        CLEANUP_COLS.put("goods", new String[]{"MerchantId", "GoodName", "ProductCode", "GoodType"});
        CLEANUP_COLS.put("goodstype", new String[]{"GoodType"});
        CLEANUP_COLS.put("login", new String[]{"UserName", "Id"});
        CLEANUP_COLS.put("user", new String[]{"username", "customerid", "merchantid"});
        CLEANUP_COLS.put("customerimage", new String[]{"UserName", "CustomerId"});
        CLEANUP_COLS.put("merchantimage", new String[]{"UserName", "MerchantId"});
        CLEANUP_COLS.put("friend", new String[]{"customerid", "friendid"});
        CLEANUP_COLS.put("productsales", new String[]{"ProductCode", "MerchantId", "GoodName"});
    }

    /**
     * 按命名空间清理 + 全局清扫哨兵（G1 的兜底）。
     * A3 从不写 message / user_conversation_read，故这两表不纳入 —— 不是遗漏，是范围。
     */
    static void cleanup(Opt o) {
        String p = ns() + "%";
        int grand = 0;
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword);
             Statement s = c.createStatement()) {
            System.out.println("[cleanup] 命名空间 " + p);
            for (Map.Entry<String, String[]> e : CLEANUP_COLS.entrySet()) {
                String tbl = e.getKey();
                String[] cols = e.getValue();
                int rows = 0;
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM mall_management_sql.`" + tbl
                        + "` WHERE `" + cols[0] + "` LIKE '" + p + "'")) {
                    if (r.next()) {
                        rows = r.getInt(1);
                    }
                }
                int del = 0;
                for (String col : cols) {
                    del += s.executeUpdate("DELETE FROM mall_management_sql.`" + tbl
                            + "` WHERE `" + col + "` LIKE '" + p + "'");
                }
                grand += del;
                if (rows > 0 || del > 0) {
                    System.out.println("  " + pad(tbl, 16) + "首列命中行数=" + rows + " 删除=" + del);
                }
            }
            // 哨兵全局清扫：前缀是代码字面量，正常数据不可能命中；探针按构造即一次性，
            // 故这里可以不带 runId 清 —— 任何后续 run 都能自愈先前被 SIGKILL 留下的哨兵。
            int probe = s.executeUpdate("DELETE FROM mall_management_sql.goodstype WHERE GoodType LIKE '"
                    + PROBE_PREFIX + "%'");
            grand += probe;
            System.out.println("  " + pad("goodstype(哨兵)", 16) + "删除=" + probe
                    + "（A3P-% 全局清扫，" + (probe == 0 ? "无残留" : "清掉了残留") + "）");
            System.out.println("[cleanup] 删除语句命中合计 " + grand + " 行（同一行可能被多列命中，故非去重行数）");
        } catch (SQLException e) {
            System.err.println("[cleanup] 失败: " + e.getMessage()
                    + "  ← 残留需人工处理；命名空间 " + p + " 与 " + PROBE_PREFIX + "% 是查残留的两个前缀");
        }
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
            // 阶段 D 补入：这两个列此前不在表内，而 orderAdd 的入参正是它们（漏检即「12 列」的缺口）
            {"order.ProductInformation",   "500", "A3-" + o.runId + "-PI"},
            {"order.Images",               "100", "A3-" + o.runId + "-IMG"},
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
        HttpClient http = HTTP;
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
