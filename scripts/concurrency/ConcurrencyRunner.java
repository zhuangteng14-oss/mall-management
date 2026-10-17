/**
 * A3 并发测试执行器 —— Java 17，两步编译后运行（run.sh 封装）。
 *
 * 运行形态（H3 定稿，后经字符集缺陷修正为两步编译）：
 *   javac -encoding UTF-8 -d <out> scripts/concurrency/ConcurrencyRunner.java
 *   java -cp <out>:<mysql-connector-j-8.0.33.jar> ConcurrencyRunner \
 *        --base-url http://localhost:8088 \
 *        --jdbc-url "jdbc:mysql://192.168.229.129:3306/mall_management_sql?..." \
 *        --expect-db-host 192.168.229.129
 *
 * 为什么不能用单文件源码模式（java ConcurrencyRunner.java，H3 原写法）：
 *   启动器在运行期就地编译源文件，此时不使用任何编译期 -encoding，而按宿主平台
 *   默认字符集读源码。Windows 上 file.encoding=GBK，源里中文字面量全部失真，
 *   依赖中文响应体的断言静默落空 —— 同一脚本、同一机器，只因启动方式不同结论相反，
 *   且不报任何错。实测：源码模式 + GBK 下 S3 由「异常单=0 / PASS」变成「异常单=10 /
 *   FAIL」。故改为编译期用 -encoding UTF-8 钉死编码。charsetSelfCheck() 是这条
 *   教训的守卫：任何人改回源码模式，会在 preflight 之前被 exit 2 挡下。
 *
 * 硬约束：
 *   - 不引任何新依赖：classpath 上只有 mysql-connector 一个 jar，
 *     故 JSON 一律手写 / 手解析，不得使用 Jackson（它不在本 classpath 上）
 *   - 不修改 pom.xml；不进 surefire（mvn test 不会跑到它）
 *   - 只走 HTTP + JDBC
 *
 * 分阶段落地（锁 4，每阶段一个 commit）：
 *   阶段 A  参数解析 + 退出码 + 空壳场景        已完成
 *   阶段 B  P0 静态长度校验 + P1–P4（JDBC 侧）  已完成
 *   阶段 C  P5–P7（HTTP 侧前置校验）           已完成
 *   阶段 D  七段（S1 / S2a / S2b / S3 / S4a / S4b / B2）  已完成
 *   阶段 E  run.sh 完整版 + dependency:build-classpath 回退 + JSON 报告  已完成
 *   余下    CI 集成（ci.yml 加 concurrency job，见 H5）
 */

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.function.Predicate;

public class ConcurrencyRunner {

    // ===================== 命名空间 =====================
    // 前缀固定为字面量，不接受调用方任意指定（H3 cleanup.safety_rails）
    static final String NS_PREFIX = "A3TEST-";

    // 【旧版哨兵前缀 —— 只为清理历史残留而保留】
    // 2026-09-27 起 P6 不再是「哨兵往返」，改为库身份对比（GET /health/db vs 脚本 JDBC），
    // 本脚本【不再写入】A3P- 行。常量与 cleanup 里的 A3P-% 清扫语句保留：旧版脚本若在
    // 写入与删除之间被 SIGKILL，会留下 A3P- 行；该前缀是代码字面量、正常数据不可能命中，
    // 故不带 runId 全局清扫，任何后续 run 都能自愈。
    static final String PROBE_PREFIX = "A3P-";

    // P1 读到的服务端版本（S0-5 第 3 步判据）。
    // 2026-09-27 起 P7 不再用它：P7 改为 server_uuid 对比。原 version 辅判据随
    // 本机升级到 8.4 即自动失效（本机 2026-09-27 实测 8.0.32），本就靠不住。
    static String vmVersion = null;

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
        // P5 就绪探针等待上限（秒）。后端冷启动要跑 Hibernate ddl-auto=validate 的校验（2026-10-04 起为 validate，此前 update；两者都在启动期建池，耗时不因此改变），非瞬时。
        int readyTimeoutSec = 60;
        // 场景选择（阶段 D 可逐个跑）；H3 的六段负载即前六项，B2 是澄清 1 的 B 组（check-then-insert TOCTOU），
        // C 是 B3 的时间线核对（C-1/C-2 为门禁，进退出码；见 c() 的 javadoc）
        String scenarios = "S1,S2a,S2b,S3,S4a,S4b,B2,C";
        // S4b 时长。H3 定 60s；OD-8（D2）尚未裁决，故做成可覆盖项，默认随 H3。
        int s4bDurationSec = 60;
        String reportDir = "scripts/concurrency/reports";
        // 本进程的 stdout 日志落点。脚本自己不写日志（那是 run.sh 的 tee 干的），
        // 只把它记进报告，好让「报告 → 同一次运行的完整日志」这条追溯链不断。
        String logFile = null;
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
        "A3 并发测试执行器（Java 17）",
        "",
        "用法（必须两步，不要用单文件源码模式 java Foo.java）:",
        "  javac -encoding UTF-8 -d <out> scripts/concurrency/ConcurrencyRunner.java",
        "  java -cp <out>:<mysql-connector.jar> ConcurrencyRunner [选项]",
        "",
        "  为什么不能用 java Foo.java：单文件源码模式由启动器在运行期就地编译，不采用编译期",
        "  -encoding，而按平台默认字符集读源文件。Windows 上 file.encoding=GBK 时源里中文串",
        "  全部失真，依赖中文响应体的断言会静默落空、结论与 CI 相反且不报错。运行器有自检，",
        "  命中即拒绝运行（exit 2）。run.sh 已封装两步。",
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
        "  --log-file <path>      本次运行的 stdout 日志落点（run.sh 传入；只被记进报告）",
        "",
        "清理:",
        "  --keep-data            跳过 finally 清理，保留数据以供人工查验",
        "  --cleanup-only         仅按 --run-id 执行清理，不跑场景",
        "  --reset-db             （未实现，TD-16）重跑 mall_management_sql.sql（DROP 11 张 —— 12 张中除 order_state_transition 外；仅限一次性实例）",
        "  --skip-negative-control  跳过 P7 阴性对照（本机库不可达时会自动 skip，无需手动指定）",
        "  --ready-timeout-sec N  P5 就绪探针等待上限，默认 60",
        "  --scenarios A,B         只跑指定负载段，默认 S1,S2a,S2b,S3,S4a,S4b,B2,C",
        "  --s4b-duration-sec N    S4b 混合负载时长，默认 60（H3 值；OD-8/ D2 未裁决）",
        "  --help                 显示本帮助",
        "",
        "退出码: 0=全部门禁通过  1=有门禁断言失败  2=前置校验失败");

    // ===================== 输出编码 =====================
    // 编译期已用 -encoding UTF-8 钉死字面量（判定因此可信），但 System.out 默认按
    // 平台字符集编码字节 —— Windows+GBK 下中文日志落成 GBK 字节，本机终端与 CI 都按
    // UTF-8 读，看到的是乱码，将来的 JSON 报告更是直接读不出来。
    // 所以这里把两路输出显式接成 UTF-8，而不是靠 -Dfile.encoding 去改 JVM 全局默认：
    // 后者会连 JDBC 侧、文件读取一起动，属于掩盖症状；只约束「本程序写出去的字节」
    // 才是根治。终端显示与判定因此一致，且与平台无关。
    static void forceUtf8Output() {
        System.setOut(new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, StandardCharsets.UTF_8));
    }

    // ===================== main =====================
    public static void main(String[] args) {
        forceUtf8Output();

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

        // --cleanup-only 必须在 preflight 之前执行：清理是运维动作，不是测试轮次。
        // 曾经的顺序是「先 preflight 再 cleanup」，结果是 P4 基线行数检查会把清理挡在门外 ——
        // 库脏（正是需要清理时）→ P4 FAIL → exit 2 → 清理永远跑不到。要清脏库先得有干净库，
        // 这是个死循环。本脚本自己刚踩过：一次 curl 探针留下 1 行 friend 就锁死了清理。
        if (o.cleanupOnly) {
            cleanup(o);
            System.out.println("--cleanup-only：未产生任何业务流量。退出码 0。");
            System.exit(EXIT_PASS);
            return;
        }

        // 字符集自检在 cleanup-only 之后、preflight 之前：清理是无害的运维动作，
        // 字符集坏了也照样该让它跑（同 P4 挡清理那个教训），但任何**判定**都不许在
        // 字符集已失真的前提下产生。
        if (!charsetSelfCheck()) {
            System.err.println("前置校验失败，未产生任何业务流量。退出码 2。");
            System.exit(EXIT_PREFLIGHT_FAILED);
            return;
        }

        // H3 phase_0_preflight.purpose：产生任何业务流量之前，先证明「脚本连的是期望库」。
        // 任一前置失败一律 exit 2，不进入场景。
        // 注意：P1–P4 只覆盖「脚本自身」这一侧；「后端是否连在同一个库实例上」由阶段 C 的
        //       P6 库身份对比给出（GET /health/db 报的 @@server_uuid vs 脚本 JDBC 查的）
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

        // 报告在 printAssertions 之后写：它要读 ASS 的最终 verdict，而 verdict 是
        // 各场景跑完才定下来的。
        String reportPath = writeReport(o, pre, rc);

        System.out.println();
        System.out.println("runId      : " + o.runId);
        System.out.println("target     : " + o.baseUrl + "  →  " + o.jdbcUrl);
        System.out.println("preflight  : " + pre.size() + " 项，全部通过");
        System.out.println("gates      : " + (rc == 0 ? "全部通过" : "失败 " + rc + " 项（见上表）"));
        // 「未落盘」必须显式可见：报告缺失时，stdout 摘要仍然像一次成功的运行，
        // 只看这一行而不看 stderr 的人会以为证据齐了。
        System.out.println("report     : " + (reportPath == null ? "<<未落盘>>（见 stderr；判定与退出码不受影响）" : reportPath));
        System.out.println("exitCode   : " + rc);
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
                case "--log-file": o.logFile = value(a, ++i, v, k); break;
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
        // C4 要求的复现率字段。null = 本断言未做多轮计数（报告里落 null，不拿别的数凑）。
        // 目前只有 A-B2-1 会填：B-2 场景自己跑 --rounds 轮，逐轮判定后在场景内计数，
        // 不靠外层循环、也不借别的断言的数来凑（reproRounds = 各轮 verdict 为
        // EXPECTED_DEFECT_PRESENT 的轮数）。
        Integer rounds = null;
        Integer reproRounds = null;

        Ass(String id, String desc, boolean gate) {
            this.id = id;
            this.desc = desc;
            this.gate = gate;
        }
    }

    static final List<Ass> ASS = new ArrayList<>();

    /** 期望 verdict 表。默认 PASS；下列例外 —— H3 明示其「当前应看到缺陷存在」。 */
    static final Map<String, String> EXPECT = new LinkedHashMap<>();

    static {
            // 2026-09-30 S1a：A-S2a-1 进一步拆为 A-S2a-1a / 1b / 1c（锁指标增量 / rows==1 / 查重文案），
        //   三条同为正向断言（期望 PASS），全部留在本表之外。
        // A-S2a-1 已于 B2（2026-09-29）移出本表：Redisson 锁 + 临界区内先查后插落地后，
        // 「20 并发同 OrderId 插 20 行」成为历史，该断言已翻成正向断言（期望 rows == 1），
        // 不再属于「缺陷存在型」。只删登记项而不改断言体的话，verdict 会落 DEFECT_FIXED，
        // 而本表的默认期望是 PASS ⇒ 门禁仍然红（两处必须同时改）。
        // A-B2-1 同理：只有 --b-group-gate=true 时它才进门禁，此时「缺陷存在」才是通过。
        // 不登记的话，门禁模式下缺陷真的复现了反而会被判失败（倒挂）。
        EXPECT.put("A-B2-1", "EXPECTED_DEFECT_PRESENT");
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
     * 必须同步改断言（H3 对 A-S2a-1 的原话；该断言 2026-09-30 已拆为 1a/1b/1c，三条均为正向断言）。
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

    // ===================== 场景数值记录（供 JSON 报告取数） =====================

    /**
     * 场景 → 键值。报告只从这里取数值，不去解析 stdout 文本
     * （解析自己的打印串等于把「给人看的格式」变成契约，以后改一个字就悄悄失效）。
     * 值一律存字符串，JSON 写出时按「像不像数」决定加不加引号，见 jval()。
     */
    static final Map<String, Map<String, String>> REC = new LinkedHashMap<>();

    static void rec(String scen, String k, Object v) {
        REC.computeIfAbsent(scen, x -> new LinkedHashMap<>()).put(k, String.valueOf(v));
    }

    /** 一档负载的全部计数与分位，一次记全，免得各场景各记一部分、口径不一。 */
    static void recLoad(String scen, Load ld) {
        rec(scen, "offeredQps", ld.offeredQps);
        rec(scen, "achievedQps", ld.qpsOf());
        rec(scen, "wallSec", String.format("%.1f", ld.elapsedMs() / 1000.0));
        rec(scen, "n", ld.total());
        rec(scen, "ok", ld.ok.sum());
        rec(scen, "unexpected", ld.unexpected.sum());
        rec(scen, "http5xx", ld.http5xx.sum());
        rec(scen, "connErr", ld.connErr.sum());
        rec(scen, "p50ms", String.format("%.1f", ld.msOf(0.50)));
        rec(scen, "p95ms", String.format("%.1f", ld.msOf(0.95)));
        rec(scen, "p99ms", String.format("%.1f", ld.msOf(0.99)));
        rec(scen, "maxMs", String.format("%.1f", ld.maxMs()));
        if (ld.offeredQps > 0 && ld.qpsNum() < ld.offeredQps * 0.95) {
            rec(scen, "note", "未达速（只记录，不门禁）");
        }
    }

    // ===================== JSON 报告 =====================

    /** schema 版本。字段有增删或语义变更时递增，供下游判断能不能读。 */
    static final int REPORT_SCHEMA = 1;

    /**
     * 「像不像数」——决定 JSON 里写 `96.2` 还是 `"96.2"`。
     *
     * 不靠调用方额外声明类型：REC 的值一律存字符串（见 rec），若再让每个调用点带一个
     * 类型标记，就多出一处会与值本身不一致的地方。判据取严，只认十进制整数与小数；
     * `8.4.11-0ubuntu0.26.04.1` 这类版本号**不**匹配（它本来也不是数），仍按字符串写。
     *
     * ⚠️ 反过来，纯数字的**标识符**（如全数字的 runId）会被误判成数。故报告里由
     * writeReport 直接写出的元数据一律走 jstr 强制加引号，自动判定只用于 REC 的取值。
     */
    static boolean looksNumeric(String s) {
        return s != null && s.matches("-?\\d+(\\.\\d+)?");
    }

    /** JSON 字符串字面量。转义 " 与 \\ 及控制字符；非 ASCII 原样保留（报告按 UTF-8 写出）。 */
    static String jstr(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    // 其余控制字符（含 0x00–0x1F）必须转义，否则 JSON 非法；
                    // 中文等非 ASCII 不转 —— 报告落盘为 UTF-8，转成「反斜杠 u 加四位
                    // 十六进制」只会让人读不了。（不能把这个形式直接写进注释：javac 的
                    // Unicode 转义预处理在词法分析之前，注释里同样会触发「非法的 Unicode
                    // 转义」编译错误 —— 本行代码刚踩过一次。）
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    /**
     * 自动判定型的 JSON 值（用于 REC）：形如数字的按数字、true/false 按布尔，
     * 其余（含 null）按字符串。
     *
     * 布尔也要判，否则同一个报告里会出现两种约定 —— options.bGroupGate 是运行器直接写的
     * 裸 true，而 REC 里的 recordOnly 会写成字符串 "true"。机器读的报告不容这种不一致。
     * 与数字同理，代价是「恰好等于 true/false 的字符串」会被当成布尔；REC 的键都是计数与
     * 短标记，没有这种值。
     */
    static String jval(String s) {
        if (s == null) {
            return "null";
        }
        if (s.equals("true") || s.equals("false")) {
            return s;
        }
        return looksNumeric(s) ? s : jstr(s);
    }

    /** 缩进工具，只为让报告人能读；缩进不参与语义。 */
    static String ind(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append("  ");
        }
        return b.toString();
    }

    /** 写一个 `"key": value` 行。comma=false 时该行是所在对象的最后一项。 */
    static void jline(StringBuilder sb, int depth, String key, String rawValue, boolean comma) {
        sb.append(ind(depth)).append(jstr(key)).append(": ").append(rawValue).append(comma ? "," : "").append('\n');
    }

    /** 扁平字符串表 → JSON 对象（按 REC 的插入序，便于与 stdout 对照）。 */
    static void jobject(StringBuilder sb, int depth, Map<String, String> m) {
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : m.entrySet()) {
            jline(sb, depth + 1, e.getKey(), jval(e.getValue()), ++i < m.size());
        }
        sb.append(ind(depth)).append("}");
    }

    /**
     * 写报告。**数据来源是 REC / ASS / B2_ROUNDS / pre，不解析 stdout 文本** ——
     * 解析自己的打印串等于把「给人看的格式」变成契约，以后改一个字就静默失效。
     *
     * 返回落盘路径；写失败返回 null（且只在 stderr 报警，不改变退出码）。
     * 理由：报告是**证据**，不是判定的一部分。判定已经由门禁断言与退出码给出；
     * 若因为磁盘满/无权限就把一次语义上通过的运行改成失败，是把两件事混为一谈。
     * 但绝不能静默 —— 故 stderr 明确报错，且 stdout 摘要行会落到「未落盘」。
     */
    static String writeReport(Opt o, Map<String, String> pre, int rc) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        jline(sb, 1, "schema", String.valueOf(REPORT_SCHEMA), true);
        jline(sb, 1, "tool", jstr("a3-concurrency"), true);
        jline(sb, 1, "runId", jstr(o.runId), true);
        jline(sb, 1, "generatedAt", jstr(java.time.OffsetDateTime.now().toString()), true);
        jline(sb, 1, "exitCode", String.valueOf(rc), true);

        // launch：本次到底是哪种启动方式，事后必须能从报告里看出来。
        // 此前日志不记录启动方式，导致「某次运行是不是受字符集缺陷影响」只能靠结果反推。
        sb.append(ind(1)).append(jstr("launch")).append(": {\n");
        jline(sb, 2, "mode", jstr("two-step-compile（javac -encoding UTF-8 + java -cp <out>）"), true);
        jline(sb, 2, "fileEncoding", jstr(System.getProperty("file.encoding")), true);
        jline(sb, 2, "nativeEncoding", jstr(System.getProperty("native.encoding")), true);
        jline(sb, 2, "stdoutEncoding", jstr("UTF-8（forceUtf8Output 强制）"), true);
        jline(sb, 2, "charsetSelfCheck", jstr("PASS"), false);
        sb.append(ind(1)).append("},\n");

        sb.append(ind(1)).append(jstr("target")).append(": {\n");
        jline(sb, 2, "baseUrl", jstr(o.baseUrl), true);
        jline(sb, 2, "jdbcUrl", jstr(o.jdbcUrl), true);
        jline(sb, 2, "jdbcUser", jstr(o.jdbcUser), true);
        jline(sb, 2, "jdbcPassword", jstr(mask(o.jdbcPassword)), true);
        jline(sb, 2, "expectDbHost", jstr(o.expectDbHost), false);
        sb.append(ind(1)).append("},\n");

        sb.append(ind(1)).append(jstr("options")).append(": {\n");
        jline(sb, 2, "qps", String.valueOf(o.qps), true);
        jline(sb, 2, "durationSec", String.valueOf(o.durationSec), true);
        jline(sb, 2, "rounds", String.valueOf(o.rounds), true);
        jline(sb, 2, "bGroupGate", String.valueOf(o.bGroupGate), true);
        jline(sb, 2, "keepData", String.valueOf(o.keepData), true);
        jline(sb, 2, "resetDb", String.valueOf(o.resetDb), true);
        jline(sb, 2, "s4bDurationSec", String.valueOf(o.s4bDurationSec), true);
        jline(sb, 2, "scenarios", jstr(o.scenarios), false);
        sb.append(ind(1)).append("},\n");

        sb.append(ind(1)).append(jstr("preflight")).append(": ");
        jobject(sb, 1, pre);
        sb.append(",\n");

        // assertions：一条断言一项，含 C4 要求的 rounds / reproRounds。
        // 未做多轮计数的断言落 null —— 报告里宁可缺，不拿别的数凑。
        sb.append(ind(1)).append(jstr("assertions")).append(": [\n");
        for (int i = 0; i < ASS.size(); i++) {
            Ass a = ASS.get(i);
            sb.append(ind(2)).append("{\n");
            jline(sb, 3, "id", jstr(a.id), true);
            jline(sb, 3, "desc", jstr(a.desc), true);
            jline(sb, 3, "gate", String.valueOf(a.gate), true);
            jline(sb, 3, "verdict", jstr(a.verdict), true);
            jline(sb, 3, "detail", jstr(a.detail), true);
            jline(sb, 3, "rounds", a.rounds == null ? "null" : String.valueOf(a.rounds), true);
            jline(sb, 3, "reproRounds", a.reproRounds == null ? "null" : String.valueOf(a.reproRounds), false);
            sb.append(ind(2)).append(i + 1 < ASS.size() ? "},\n" : "}\n");
        }
        sb.append(ind(1)).append("],\n");

        sb.append(ind(1)).append(jstr("gates")).append(": {\n");
        int gateCount = 0;
        for (Ass a : ASS) {
            if (a.gate) {
                gateCount++;
            }
        }
        jline(sb, 2, "count", String.valueOf(gateCount), true);
        jline(sb, 2, "failed", String.valueOf(gateFailures()), false);
        sb.append(ind(1)).append("},\n");

        sb.append(ind(1)).append(jstr("scenarios")).append(": {\n");
        int i = 0;
        for (Map.Entry<String, Map<String, String>> e : REC.entrySet()) {
            sb.append(ind(2)).append(jstr(e.getKey())).append(": ");
            jobject(sb, 2, e.getValue());
            sb.append(++i < REC.size() ? ",\n" : "\n");
        }
        sb.append(ind(1)).append("},\n");

        // B-2 逐轮明细：结构化数组，不从文本反解。
        // A-B2-1 的 detail 里写「逐轮明细见报告 perRound」，指的就是这里。
        sb.append(ind(1)).append(jstr("b2PerRound")).append(": [\n");
        for (int k = 0; k < B2_ROUNDS.size(); k++) {
            B2Round b = B2_ROUNDS.get(k);
            sb.append(ind(2)).append("{\n");
            jline(sb, 3, "round", String.valueOf(b.round), true);
            jline(sb, 3, "login5xx", String.valueOf(b.login5xx), true);
            jline(sb, 3, "loginOk", String.valueOf(b.loginOk), true);
            jline(sb, 3, "loginExisted", String.valueOf(b.loginExisted), true);
            jline(sb, 3, "loginUnclear", String.valueOf(b.loginUnclear), true);
            jline(sb, 3, "goodstypeDupRows", String.valueOf(b.gtRows), true);
            jline(sb, 3, "goodstypeOk", String.valueOf(b.gtOk), true);
            jline(sb, 3, "friendDupRows", String.valueOf(b.frRows), true);
            jline(sb, 3, "friendOk", String.valueOf(b.frOk), true);
            jline(sb, 3, "verdict", jstr(b.verdict), false);
            sb.append(ind(2)).append(k + 1 < B2_ROUNDS.size() ? "},\n" : "}\n");
        }
        sb.append(ind(1)).append("],\n");

        // 追溯链：报告 → 同一次运行的完整 stdout 日志
        jline(sb, 1, "logFile", jstr(o.logFile), false);
        sb.append("}\n");

        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        java.io.File dir = new java.io.File(o.reportDir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            System.err.println("[report] 无法创建目录 " + o.reportDir + "，报告未落盘（判定与退出码不受影响）");
            return null;
        }
        java.io.File out = new java.io.File(dir, "a3-report-" + o.runId + "-" + ts + ".json");
        try {
            Files.writeString(out.toPath(), sb.toString(), StandardCharsets.UTF_8);
            return out.getPath();
        } catch (IOException e) {
            System.err.println("[report] 写入失败: " + e.getMessage() + "，报告未落盘（判定与退出码不受影响）");
            return null;
        }
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
            List<Long> v = sortedLat();
            if (v.isEmpty()) {
                return "n=0";
            }
            return String.format("n=%d p50=%.1f p95=%.1f p99=%.1f max=%.1f(ms)",
                    v.size(), ms(v, 0.50), ms(v, 0.95), ms(v, 0.99), v.get(v.size() - 1) / 1e6);
        }

        /** 报告要的是数值而不是拼好的串；pct() 与本方法共用同一份排序，避免两条口径。 */
        List<Long> sortedLat() {
            List<Long> v = new ArrayList<>(lat);
            Collections.sort(v);
            return v;
        }

        double msOf(double q) {
            List<Long> v = sortedLat();
            return v.isEmpty() ? -1 : ms(v, q);
        }

        double maxMs() {
            List<Long> v = sortedLat();
            return v.isEmpty() ? -1 : v.get(v.size() - 1) / 1e6;
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

    /**
     * 非 ASCII 转成「反斜杠 u 加四位十六进制」的转义形式。
     * （不能把这个形式直接写进注释：javac 在词法分析之前先做 Unicode 转义预处理，
     *   注释里的反斜杠 u 同样会触发「非法的 Unicode 转义」编译错误。）
     * 起因：宿主 JVM 是 GBK 时，中文经 System.out 打到 UTF-8 终端会显示成乱码，
     * 于是「响应体里到底有没有那几个字」这件事在终端上无法判断 —— 判定看着像坏了，
     * 也可能只是打印坏了。转义后与终端字符集无关，可逐字节对照。
     */
    static String esc(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                b.append(c);
            } else {
                b.append(String.format("\\u%04x", (int) c));
            }
        }
        return b.toString();
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

    /**
     * 等入站限流（{@code InboundRateLimitInterceptor}：每 1 秒放行 200 条）翻到新一拍再发请求。
     *
     * <p>为什么必须等：限流是**固定刷新窗口**，一个 burst 是 20~40 条同栅栏齐发。若整批
     * 落在窗口尾部、余量不足，会**整批**收到 429「请求过于频繁」——不是部分失败。
     * CI run 39057108728 的 A-S4a-4：{@code succ=0} 符合期望，但 20 条业务 msg 一条都
     * 没命中，而相邻的 A-S4a-1/-2/-3 全绿；同一轮 B2 的 loginAdd 实测到 3 条 429。
     * ⇒ 最合理的解释是这一批落在窗口尾部被整批打回（**是推断，非该批响应的实测**；
     * 本笔新增的 {@code codes=} 会在复发时直接打出状态码分布）。同理，C 组紧跟 S4b 的
     * 120 QPS 负载后会撞上尚未刷新的桶 —— 那一条有直接证据（A-C6-1 的 6 条探针全 429）。
     *
     * <p>1.2s &gt; {@code REFRESH_PERIOD=1s} ⇒ 一定跨过刷新点；等待期间没有请求
     * ⇒ 起跑时是满桶 200，足够一个 burst。
     */
    static void freshLimitWindow() throws InterruptedException {
        Thread.sleep(1200);
    }

    /** 一批响应的 HTTP 状态分布（如 {@code {200=20}} / {@code {429=20}}）——把「业务拒绝」与「被限流」分开。 */
    static String codeHist(List<Call> cs) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (Call c : cs) {
            m.merge(c.r.code, 1, Integer::sum);
        }
        return m.toString();
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

    /** 通用计数。loginAdd 返回的是裸字符串（"添加成功！" / "0"）而非 JSON，successTrue() 对它无效。 */
    static int countIf(List<Call> cs, Predicate<Resp> p) {
        int n = 0;
        for (Call c : cs) {
            if (p.test(c.r)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 打印前 cap 条响应原文（码 + 体）。
     * 起因：B-2 用 burst 而非 paced，没有 Load.dumpSamples 那层兜底 —— 结果是「计数对不上时
     * 只能看到数字、看不到原因」。计数类断言旁边必须能打出原文，否则异常响应体无从取证。
     */
    static void dumpSamples(String scen, List<Call> cs, int cap) {
        int shown = 0;
        for (Call c : cs) {
            if (shown >= cap) {
                return;
            }
            System.out.println("      · " + scen + " HTTP " + c.r.code + " body=" + trunc(c.r.body)
                    + "  [esc] " + trunc(esc(c.r.body)));
            shown++;
        }
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

    /**
     * 多行查询：每行取成 Object[]（按 SELECT 的列序）。异常返回 null —— 与 jdbcCount 的 -1 同义。
     * 返回值刻意不在这里折叠成空表：调用方要能区分「查不到行」与「没查成」，否则一次 JDBC 失败
     * 会被读成「时间线一行都没有」。
     */
    static List<Object[]> jdbcRows(Opt o, String sql, Object... args) {
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet r = ps.executeQuery()) {
                int n = r.getMetaData().getColumnCount();
                List<Object[]> out = new ArrayList<>();
                while (r.next()) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) {
                        row[i] = r.getObject(i + 1);
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            System.err.println("[JDBC] 查询失败: " + e.getMessage() + "  ← " + sql);
            return null;
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

    /**
     * 读 {@code mall.lock.acquires} 的某个 (lock,result) 组合的**当前累计值**（Micrometer 计数器是
     * 进程级累计，故只能比增量）。返回 -1 = 读不到 —— 调用方必须把 -1 当「无法判定」而不是 0，
     * 否则指标侧一坏，断言反而会假绿。
     *
     * <p>用 {@code contains("value":)} 取数而非解析 JSON：本脚本 classpath 上只有 mysql-connector
     * （类头硬约束），与既有风格一致。
     */
    static double lockAcquires(Opt o, String lock, String result) {
        Resp r = send(HTTP, o.baseUrl + "/actuator/metrics/mall.lock.acquires?tag=lock:" + lock
                + "&tag=result:" + result, null);
        if (r.code == 404) {
            return 0;   // 计数器尚未注册（本进程从未取过该锁）—— 语义上就是 0
        }
        if (r.code != 200) {
            return -1;
        }
        String key = "\"value\":";
        int i = r.body.indexOf(key);
        if (i < 0) {
            return -1;
        }
        int j = i + key.length();
        while (j < r.body.length() && r.body.charAt(j) == ' ') {
            j++;
        }
        int k = j;
        while (k < r.body.length()) {
            char ch = r.body.charAt(k);
            if (Character.isDigit(ch) || ch == '.' || ch == '-' || ch == '+' || ch == 'e' || ch == 'E') {
                k++;
            } else {
                break;
            }
        }
        if (k == j) {
            return -1;
        }
        try {
            return Double.parseDouble(r.body.substring(j, k));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 数值展示：整数值去掉尾部 .0，便于与 --help / 报告里的整数口径对齐。 */
    static String numStr(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return String.valueOf(d);
        }
        return (d == Math.rint(d)) ? String.valueOf((long) d) : String.valueOf(d);
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
        if (o.has("B2")) {
            guard("B2", () -> b2(o));
        }
        // C 组放最后：它的取数一律带自己的命名空间前缀（<ns>-C%），与前面各段互不干扰；
        // 排在最后还让它的排空等待落在一个安静的进程上（不受前段在途任务影响）。
        if (o.has("C")) {
            guardSoft("C", () -> c(o));
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
        // 锁计数是 record-only，不进任何断言 —— 若不单独记，报告里就完全没有它，
        // 而「并发下有没有死锁/锁等待超时」正是这份报告该回答的问题之一。
        rec(scene, "lockCountersBefore", before);
        rec(scene, "lockCountersAfter", after);
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

    /**
     * 与 {@link #guard} 同理，但异常断言的 gate 落 **false**。
     *
     * <p>现状（2026-09-30 C 组升门禁起）：C-1/C-2 已是门禁，C 场景若抛异常，它们会停在
     * 默认 verdict {@code SKIP} ⇒ SKIP ≠ PASS ⇒ 本组照样红（见 {@link #c} 的 javadoc）。
     * 故这条 gate=false 的异常断言如今**只是多留一行显式的异常记录**，它红不红已不改判定。
     *
     * <p>保留 gate=false 的**原有理由已随升门禁失效**：曾担心「DDL 未落盘时 C 组必然报错，
     * 那是『没跑成』不是『跑出来有问题』，若用 {@link #guard} 的 gate=true 就会让一个非门禁
     * 场景把 CI 弄红」。该情形现由 P3 在负载段之前拦下（表数 ≠ 11 + 已知追加表 ⇒ 退出码 2）。
     */
    static void guardSoft(String name, Body b) {
        try {
            b.run();
        } catch (Throwable e) {
            Ass x = ass(name + "-异常", "负载段 " + name + " 执行抛异常（record-only，不进退出码）", false);
            x.verdict = "FAIL";
            x.detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[" + name + "] 执行异常（record-only）: ");
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
                        ? call("goodstype", send(HTTP, o.baseUrl + "/goods/goodstype", null))
                        : call("loginSelect", send(HTTP, o.baseUrl + "/user/loginSelect", login)),
                (i, r) -> r.code == 200 && ((i & 1) == 0
                        ? r.successTrue()
                        : r.body != null && "1".equals(r.body.trim())));
        settle(a1, ld.http5xx.sum() == 0 && ld.connErr.sum() == 0,
                "5xx=" + ld.http5xx.sum() + " connErr=" + ld.connErr.sum());
        settle(a2, ld.bad("goodstype") == 0, "goodstype 非预期=" + ld.bad("goodstype"));
        settle(a3, ld.bad("loginSelect") == 0 && ld.good("loginSelect") > 0,
                "loginSelect 返回非 1 的次数=" + ld.bad("loginSelect") + "，返回 1 的次数=" + ld.good("loginSelect"));
        System.out.println("[S1] 只读 50% goodstype + 50% loginSelect  " + ld.line());
        recLoad("S1", ld);
        ld.dumpSamples();
    }

    // ---------- S2a 下单（同参同 OrderId） ----------

    /**
     * S2a —— 20 线程同栅栏、同 OrderId 下单。三条断言各证一层（2026-09-30 S1a 拆分）：
     * <ul>
     *   <li><b>1a</b> 锁指标增量 == 20 —— 证「锁真的被取到 20 次」。用场景前后增量而非字面 == 20：
     *       {@code mall.lock.acquires} 是**进程级累计**计数器（{@code MallLockMetrics:109}
     *       {@code registry.counter(...).increment()}），且 S1 等更早的场景也打过同一把锁。</li>
     *   <li><b>1b</b> rows == 1 —— DB 层兜底。⚠️ 唯一索引落地后它**恒真**，故单靠它已不足以证修复；
     *       它的价值退化为「没插重复行」的必要条件。</li>
     *   <li><b>1c</b> msg 里「数据库错误」== 0 —— 证「临界区内的查重分支真的执行了」。
     *       ⚠️ 这里**不能**数 5xx：唯一索引落地后，锁若失效，并发 INSERT 会撞
     *       {@code uk_order_orderId}，而 INSERT 的 catch 把它吞成 **200 + msg="数据库错误：Duplicate
     *       entry..."**（不是 5xx）⇒ {@code count5xx} 在本场景恒为 0，可观测差异只在 msg 里。</li>
     * </ul>
     */
    static void s2a(Opt o) throws InterruptedException {
        Ass a1a = ass("A-S2a-1a", "锁指标增量 mall.lock.acquires{lock=order-add,result=acquired} == 20", true);
        Ass a1b = ass("A-S2a-1b", "JDBC count(order WHERE OrderId=?) == 1", true);
        Ass a1c = ass("A-S2a-1c", "20 个响应里含「数据库错误」的 == 0", true);
        Ass a2 = ass("A-S2a-2", "该 OrderId 行数 == 本轮 success:true 响应数", true);
        String orderId = ns() + "-DUP";
        final Map<String, String> f = orderForm(orderId, 0, 0, 0);

        // 1a 的读数窗口：burst 前后各读一次（沿用 lockDelta 的「每场景前后增量」形状）。
        // 场景是顺序执行的（runScenarios 一串 if），且 S2a 排在 S4b / B2 / C 之前 ⇒ 窗口内只有本 burst。
        double b = lockAcquires(o, "order-add", "acquired");
        List<Call> cs = burst(20, i -> call("orderAdd", send(HTTP, o.baseUrl + "/order/orderAdd", f)));
        double af = lockAcquires(o, "order-add", "acquired");

        int succ = countSuccess(cs);
        int dbErrs = countDbError(cs);   // 既有 helper（:1121），语义即 says("数据库错误")
        int rows = jdbcCount(o, "SELECT COUNT(*) FROM mall_management_sql.`order` WHERE OrderId = ?", orderId);

        // --- 1a 锁生效（直接证据）---
        if (b < 0 || af < 0) {
            // 指标读不到时**恒失败**：把「读不到」当 0 会让断言假绿
            settle(a1a, false, "指标不可读（before=" + numStr(b) + " after=" + numStr(af)
                    + "）—— 这是环境问题，不是「锁没生效」；查 /actuator/metrics 是否可达");
        } else {
            double d = af - b;
            settle(a1a, d == 20, "增量=" + numStr(d) + "（20 线程同锁 key ⇒ 预期 20）before="
                    + numStr(b) + " after=" + numStr(af));
        }

        // --- 1b DB 兜底 ---
        if (rows < 0) {
            // 查询失败不能被当成「修复生效」—— -1 与 1 一样都不说明「没插重复行」，那是假红
            settle(a1b, false, "JDBC 行数查询失败（-1），无法判定是否仍插重复行");
        } else {
            // B2 之前本断言是 settleDefect(rows >= 2)（缺陷存在型）。修复 = 「锁 + 临界区内先查后插」，
            // 两条缺一不可：锁单独只把 20 个并发变成串行、每个照旧裸 INSERT，仍插 20 行。
            settle(a1b, rows == 1,
                    "行数=" + rows + "（== 1 才是修复生效；>= 2 说明查重窗口仍在）"
                    + " 5xx=" + count5xx(cs) + "（⚠️ 本场景 5xx 恒为 0，理由见 1c）");
        }

        // --- 1c 查重分支真的执行 ---
        settle(a1c, dbErrs == 0,
                "含「数据库错误」的响应=" + dbErrs + " 条（>0 = 撞 uk_order_orderId ⇒ 锁/查重未生效）");

        settle(a2, rows == succ, "行数=" + rows + " == success 数=" + succ);
        String delta = (b < 0 || af < 0) ? "不可读" : numStr(af - b);
        System.out.println("[S2a] 20 线程同栅栏同 OrderId=" + orderId + " success=" + succ
                + " 行数=" + rows + " 锁增量=" + delta + " 数据库错误=" + dbErrs);
        // 用 cs.size() 而不是再写一个 20：这个 20 在源码里是裸字面量（S2a 与 S4a 的六个
        // burst 共 7 处），报告里再抄一遍就等于多一处会与代码不一致的地方。
        rec("S2a", "burstCalls", cs.size());
        rec("S2a", "successCount", succ);
        rec("S2a", "rowsForSameOrderId", rows);
        rec("S2a", "lockAcquiresDelta", delta);
        rec("S2a", "dbErrorResponses", dbErrs);
    }

    // ---------- S2b 下单（各 OrderId 唯一） ----------

    static void s2b(Opt o) throws InterruptedException {
        Ass a1 = ass("A-S2b-1", "success:true 比率 == 100%，无 5xx", true);
        Ass a2 = ass("A-S2b-2", "JDBC count(OrderId LIKE '<ns>-N%') == 成功响应数", true);
        Load ld = paced(o.qps, o.qps * o.durationSec,
                i -> call("orderAdd", send(HTTP, o.baseUrl + "/order/orderAdd",
                        orderForm(ns() + "-N" + i, 0, 0, 0))),
                (i, r) -> r.successTrue());
        int rows = jdbcCount(o, "SELECT COUNT(*) FROM mall_management_sql.`order` WHERE OrderId LIKE ?",
                ns() + "-N%");
        settle(a1, ld.ok.sum() == ld.total() && ld.http5xx.sum() == 0 && ld.connErr.sum() == 0,
                "ok=" + ld.ok.sum() + "/" + ld.total() + " unexpected=" + ld.unexpected.sum()
                + " 5xx=" + ld.http5xx.sum() + " connErr=" + ld.connErr.sum());
        settle(a2, rows == ld.ok.sum(), "行数=" + rows + " == success 数=" + ld.ok.sum());
        System.out.println("[S2b] 下单(独参) " + ld.line());
        recLoad("S2b", ld);
        rec("S2b", "orderRows", rows);
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
            Resp r = send(HTTP, o.baseUrl + "/order/orderAdd", orderForm(id, 0, 0, 0));
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
            List<Call> cs = burst(S3_T, i -> call("orderPayIfUp", send(HTTP, o.baseUrl + "/order/orderPayIfUp", f)));
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
        rec("S3", "orders", S3_M);
        rec("S3", "threadsPerOrder", S3_T);
        rec("S3", "prebuiltOk", preBuilt);
        rec("S3", "totalSuccess", totalSucc);
        rec("S3", "inconsistentOrders", badUnits);
        rec("S3", "dbErrorResponses", dbErr);
        rec("S3", "stateBadOrders", stateBad);
    }

    // ---------- S4a 状态机非法流转 + 跨接口竞态 ----------

    static void s4a(Opt o) throws InterruptedException {
        String lock0 = lockCounters(o);
        Ass a0 = ass("A-S4a-0", "五个前置态全部建成且**末态正确**（C#6 后 3 个改由合法流转推进）", true);
        Ass a1 = ass("A-S4a-1", "orderDeliveryIfUp on U ×20 → success == 0，msg 正确", true);
        Ass a2 = ass("A-S4a-2", "orderDeliveryIfUp on P ×20 → success == 1（重复发货被拒）", true);
        Ass a3 = ass("A-S4a-3", "orderDeliveryIfUp on PDR ×20 → success == 0", true);
        Ass a4 = ass("A-S4a-4", "orderReceiveIfUp on U ×20 → success == 0（未付款收货被拒）", true);
        Ass a5 = ass("A-S4a-5", "orderReceiveIfUp on PR ×20 → success == 0（重复收货被拒）", true);
        Ass a6 = ass("A-S4a-6", "orderReceiveIfUp on PD ×20 → success == 1", true);
        Ass a7 = ass("A-S4a-7", "同单 20 pay + 20 delivery 同栅栏：paySucc==1 且 deliverySucc<=1", true);
        Ass a8 = ass("A-S4a-8", "全命名空间不变式 NOT(PayIf=0 AND (DeliveryIf=1 OR ReceiveIf=1))", true);

        // setup：三个状态位是 orderAdd 的入参。**C#6（2026-10-04）之后 orderAdd 只收初始态
        // {(0,0,0),(1,0,0)}** ⇒ PD / PDR / PR 这三个前置态**不能再靠直传伪造**，必须走合法流转推进
        // （`add(1,0,0)` → 发货 → 收货）。这不是「绕过拦截」，恰恰是拦截落地后唯一的正常造法 ——
        // 前端造出这些态走的也是这三步。⚠️ 5 个前置态本身**全是合法态**（A-S4a-8 的不变式全满足），
        // 所以本段的断言语义**一条都没变**，变的只是前置构造方式。
        String[] keys = {"U", "P", "PD", "PDR", "PR"};
        int[][] birth = {{0, 0, 0}, {1, 0, 0}, {1, 0, 0}, {1, 0, 0}, {1, 0, 0}};
        String[][] advance = {{}, {}, {"orderDeliveryIfUp"}, {"orderDeliveryIfUp", "orderReceiveIfUp"},
                              {"orderReceiveIfUp"}};
        int[][] want = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}};
        Map<String, String> idOf = new LinkedHashMap<>();
        StringBuilder preStates = new StringBuilder();
        boolean preBuilt = true;
        // S4a 紧跟在 S3（10 单 × 20 线程 = 200 请求）之后；先等一拍，别让 setup 的
        // 9 条写请求撞上 S3 尾部的空桶（症状会是 A-S4a-0「前置态没建成」）。
        freshLimitWindow();
        for (int k = 0; k < keys.length; k++) {
            String id = ns() + "-S4a" + keys[k];
            idOf.put(keys[k], id);
            StringBuilder path = new StringBuilder(
                    "add(" + birth[k][0] + birth[k][1] + birth[k][2] + ")");
            Resp r = send(HTTP, o.baseUrl + "/order/orderAdd",
                    orderForm(id, birth[k][0], birth[k][1], birth[k][2]));
            // ⚠️ 每一步都同时卡 **HTTP 200** 与 success:true。只卡 success:true 是不够的：
            // successTrue() 是裸子串匹配（"\"success\":true"），路由打错时的 404 响应体里没有它，
            // 于是「路由错」与「业务拒绝」在旧判据下**长得一模一样**——2026-10-04 首次 CI 复跑
            // 就栽在这里：推进步骤少写了 "/order/" 前缀打成 404，被当成「状态机拒绝」读了一轮。
            // 卡上 code==200 后，路由类失败会带着 URL 与状态码直接喊出来。
            boolean ok = r.code == 200 && r.successTrue();
            String bad = ok ? null : "add HTTP " + r.code + " " + trunc(r.body);
            for (int i = 0; ok && i < advance[k].length; i++) {
                // ⚠️ 路径必须带 "/order/" 前缀（OrderController 是 @RequestMapping("/order")）。
                // 这里曾写成 o.baseUrl + "/" + advance[k][i] ⇒ /orderDeliveryIfUp ⇒ 404。
                String url = o.baseUrl + "/order/" + advance[k][i];
                Resp step = send(HTTP, url, form("orderId", id));
                boolean stepOk = step.code == 200 && step.successTrue();
                if (stepOk) {
                    path.append('+').append(advance[k][i].replace("order", "").replace("IfUp", ""));
                } else {
                    bad = path + " → " + url + " HTTP " + step.code + " " + trunc(step.body);
                }
                ok = stepOk;
            }
            // 前置态的判据取**库里那三位的实际值**，不是「每一步都返回了 success」——
            // 两者是不同的命题，而下面的 A-S4a-3/-5 完全押在「这单真的在 PDR / PR 态」上。
            int[] got = jdbcOrderState(o, id);
            boolean stateOk = got != null
                    && got[0] == want[k][0] && got[1] == want[k][1] && got[2] == want[k][2];
            preBuilt &= ok && stateOk;
            // ⚠️ 失败时打的是**那一步**的响应体（bad），不是 add 的。原先写 trunc(r.body) 而 r 在循环里
            // 从未重绑 ⇒ 推进步骤失败时仍打印 add 的 {"msg":"订单添加成功","success":true}，
            // 与「FAILED」并排出现，把人往反方向带（看着像成功了却被判失败）。
            preStates.append(keys[k]).append('=')
                    .append(stateOk ? "ok"
                            : ok ? "态不符" + (got == null ? "无行" : got[0] + "/" + got[1] + "/" + got[2])
                                 : "FAILED " + bad)
                    .append('(').append(path).append(") ");
        }
        // ⚠️ A-S4a-0 是**新增门禁**，把原先只打在 println 里的一句话升成判据。
        // 它上线后第一次 CI 复跑就抓到了东西，同时**订正了本笔最初写在这段里的预言**：
        // 本笔原写「前置态塌掉 ⇒ 订单不存在 ⇒ A-S4a-3/-5 真空转绿」。实测**不是**——
        // 那次 404 让 PD/PDR/PR 停在 (1,0,0)，delivery/receive 的 succ 反而是 1，
        // A-S4a-3/-5 **自己就红了**（A-S4a-6 倒是撞对期望值假绿：RECEIVE 守卫只卡
        // PayIf=1 AND ReceiveIf=0，(1,0,0) 本就收得动）。
        // 所以这条门禁的真实收益是**定位**而非「唯一的告警」：它的 detail 直接写「前置态没建成」，
        // 把责任从「拦截逻辑」指回「测试夹具」，否则人会对着两条红的 A-S4a 去怀疑状态机。
        settle(a0, preBuilt, "前置态 " + preStates);

        String dlyMsg = "订单不存在或未付款或已发货/已收货";
        String rcvMsg = "订单未付款或已收货";

        // c1 可以不等：它和 setup 共用同一拍（9 + 20 = 29 ≪ 200）。c2 起每个 burst 前等一拍，
        // 否则整批可能落在窗口尾部，被 429 整批打回（见 freshLimitWindow 的注释）。
        List<Call> c1 = burst(20, i -> call("dly-U", send(HTTP, o.baseUrl + "/order/orderDeliveryIfUp", form("orderId", idOf.get("U")))));
        settle(a1, countSuccess(c1) == 0 && countMsg(c1, dlyMsg) == 20,
                "succ=" + countSuccess(c1) + " msg 命中=" + countMsg(c1, dlyMsg) + " codes=" + codeHist(c1));

        freshLimitWindow();
        List<Call> c2 = burst(20, i -> call("dly-P", send(HTTP, o.baseUrl + "/order/orderDeliveryIfUp", form("orderId", idOf.get("P")))));
        settle(a2, countSuccess(c2) == 1, "succ=" + countSuccess(c2) + " codes=" + codeHist(c2));

        freshLimitWindow();
        List<Call> c3 = burst(20, i -> call("dly-PDR", send(HTTP, o.baseUrl + "/order/orderDeliveryIfUp", form("orderId", idOf.get("PDR")))));
        settle(a3, countSuccess(c3) == 0, "succ=" + countSuccess(c3) + " codes=" + codeHist(c3));

        freshLimitWindow();
        List<Call> c4 = burst(20, i -> call("rcv-U", send(HTTP, o.baseUrl + "/order/orderReceiveIfUp", form("orderId", idOf.get("U")))));
        settle(a4, countSuccess(c4) == 0 && countMsg(c4, rcvMsg) == 20,
                "succ=" + countSuccess(c4) + " msg 命中=" + countMsg(c4, rcvMsg) + " codes=" + codeHist(c4));

        freshLimitWindow();
        List<Call> c5 = burst(20, i -> call("rcv-PR", send(HTTP, o.baseUrl + "/order/orderReceiveIfUp", form("orderId", idOf.get("PR")))));
        settle(a5, countSuccess(c5) == 0, "succ=" + countSuccess(c5) + " codes=" + codeHist(c5));

        freshLimitWindow();
        List<Call> c6 = burst(20, i -> call("rcv-PD", send(HTTP, o.baseUrl + "/order/orderReceiveIfUp", form("orderId", idOf.get("PD")))));
        settle(a6, countSuccess(c6) == 1, "succ=" + countSuccess(c6) + " codes=" + codeHist(c6));

        // A-S4a-7：同一未付款单上 pay 与 delivery 同时释放。delivery 的 WHERE 要求 PayIf=1，
        // 故它最多在 pay 提交之后成功一次；不变式 NOT(DeliveryIf=1 AND PayIf=0) 是此项的真正判据。
        String raceId = ns() + "-S4aRACE";
        freshLimitWindow();
        Resp rr = send(HTTP, o.baseUrl + "/order/orderAdd", orderForm(raceId, 0, 0, 0));
        final Map<String, String> payF = form("orderId", raceId);
        List<Call> c7 = burst(40, i -> call(i % 2 == 0 ? "pay-race" : "dly-race",
                send(HTTP, o.baseUrl + (i % 2 == 0 ? "/order/orderPayIfUp" : "/order/orderDeliveryIfUp"),
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

        System.out.println("[S4a] 预置态 " + preStates + "| 8 个 burst 完成"
                + " payRace=" + paySucc + " dlyRace=" + dlySucc + " 不变式违反=" + viol);
        // S2a/S3/S4a 是栅栏型（burst），没有 Load，故不走 recLoad；但取数与 Load 型
        // 同等重要，否则报告里这三段只剩断言 detail 里的散文，读不出结构化数字。
        rec("S4a", "burstCount", 8);
        rec("S4a", "payRaceSuccess", paySucc);
        rec("S4a", "deliveryRaceSuccess", dlySucc);
        rec("S4a", "invariantViolations", viol);
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
                return call("orderAdd", send(HTTP, o.baseUrl + "/order/orderAdd",
                        orderForm(ns() + "-S4bN" + iA.getAndIncrement(), 0, 0, 0)));
            }
            if (slot < 12) {
                return call("orderPayIfUp", send(HTTP, o.baseUrl + "/order/orderPayIfUp",
                        form("orderId", ns() + "-S4bPay" + iP.getAndIncrement())));
            }
            if (slot < 15) {
                return call("orderDeliveryIfUp", send(HTTP, o.baseUrl + "/order/orderDeliveryIfUp",
                        form("orderId", ns() + "-S4bDlv" + iD.getAndIncrement())));
            }
            if (slot < 18) {
                return call("orderReceiveIfUp", send(HTTP, o.baseUrl + "/order/orderReceiveIfUp",
                        form("orderId", ns() + "-S4bRcv" + iR.getAndIncrement())));
            }
            return call("goodstype", send(HTTP, o.baseUrl + "/goods/goodstype", null));
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
        recLoad("S4b", ld);
        rec("S4b", "okAdd", gAdd);
        rec("S4b", "okPay", gPay);
        rec("S4b", "okDelivery", gDly);
        rec("S4b", "okReceive", gRcv);
        rec("S4b", "okGoodstype", gGs);
        // 这个 rows 是「本档 orderAdd 落库行数」（WHERE OrderId LIKE '<ns>-S4bN%'），
        // 不是 order 表总行数 —— 别叫 orderRowsAtEnd，那会让人以为能直接读它比较表大小。
        rec("S4b", "orderRowsFromLoad", rows);
        rec("S4b", "invariantViolations", viol);
        rec("S4b", "s4bDurationSec", o.s4bDurationSec);
        ld.dumpSamples();
        lockDelta(o, "S4b", lock0);
    }

    // ---------- C 订单状态流转时间线（B3 新增；C-1/C-2 为门禁）----------

    /**
     * 出生闸（C#6）**拒收**的 6 个三位组合：3 个「合法但非初始」在前、3 个非法在后。
     *
     * <p>⚠️ 本数组在 C#6（2026-10-04）之前是 {@code C_BITS}（全部 8 个组合 —— 那时
     * {@code orderAdd} 直收三位，8 个都能出生，含 3 个伪造态，用来让时间线记下「从非法态来」）。
     * 拦截落地后 8 个里只有 2 个（{@code 000}/{@code 100}）能出生，剩下 6 个的职责从
     * 「造出前置态」翻转成「**断言造不出来**」（A-C6-1）。三个「合法但非初始」必须留在名单里：
     * 它们是不变式拦不住的，正是 TD-17 正文举的那类（{@code (1,1,1)} 已付款+已发货+已收货）。
     *
     * <p>顺序刻意与 {@code OrderStateMachine.rejectionShape} 的标签分组一致
     * （非初始在前、非法形状在后），便于与本文件外的那份词表逐字对照。
     */
    static final String[] C_REFUSED = {"110", "111", "101", "010", "001", "011"};

    /**
     * 排空窗口。⚠️ 必须远大于本场景 burst 的墙钟（8 次 burst × 20 + 3 次下单 ≈ 百毫秒量级），
     * 这是 B1 的教训：窗口与 burst 墙钟同量级时，「还没写完」与「写丢了」分不开。
     * 达到期望行数即提前返回，故它不是固定等待。
     */
    static final long C_DRAIN_TIMEOUT_MS = 10_000;

    /**
     * 收敛后的静默窗口。两个窗口抓的不是同一类错，故不能只留一个：
     *   · 收敛（行数到达期望值）抓**漏写的行**；
     *   · 静默后复读抓**多写的行**（重复投递 / 重复流转 —— 多出来的行只会让行数超过期望值，
     *     收敛条件 `>=` 已提前满足，不复读就永远看不见）。
     * 300ms 约为单条 INSERT 的百倍量级，不至于靠运气。
     */
    static final long C_QUIESCE_MS = 300;

    /**
     * C 组：B3 新增的订单状态流转时间线（{@code order_state_transition}）的可核对性 ——
     * 也就是 C3「时间线视图」的数据源到底可不可信。
     *
     * <h2>【四条断言各验什么】</h2>
     * C-1 落库行数 == 四个端点的 success 之和；C-2 逐单重放时间线，末态必须等于
     * {@code order} 表的实际三位。前者证「没漏没多」，后者证「顺序与内容自洽、且与权威状态一致」。
     *
     * <p><b>C#6（2026-10-04）新增两条</b>，它们把「出生闸」这件事本身纳入证据链：
     * <ul>
     *   <li><b>A-C6-1</b>：6 个非白名单三位组合（3 个「合法但非初始」+ 3 个非法）逐个打
     *       {@code orderAdd}，每条都必须「{@code success=false} + 文案逐字 + <b>库里查无此行</b>」。
     *       ⚠️ 三条一起才算 —— 只看 {@code success} 会漏掉最坏的那种「返回了 false 却已经插进去」。
     *       在本笔之前这 6 个组合是**合法输入**（8 个都能出生），职责从「造前置态」翻转成
     *       「断言造不出来」；</li>
     *   <li><b>A-C6-2</b>：{@code mall.order.state.birth.rejected} 的 shape 必须覆盖 4 个取值，
     *       且 {@code non_initial} 这条序列**真的存在**。拦下了却记不下来，C3 的「今日拦截 N 次」
     *       会系统性偏小且不报错 —— 这正是本笔要防的失败形态。</li>
     * </ul>
     * ⇒ 本组由「时间线不坏」扩为「时间线不坏 <b>且</b> 伪造入口确实关着、且拦截被如实记下」。
     *
     * <h2>【为什么期望成功数是先按状态机写死的，不是从响应里读出来的】</h2>
     * 「行数 == 成功数」这个等式在守卫整体失灵时**两边一起错**、等式照样成立
     * （20 个并发支付被全放行 ⇒ 20 行、20 个 success）。故每条 burst 的期望成功数先按
     * 状态机写死、再与响应比：预测先红，等式才有资格当证据。
     *
     * <h2>【门禁】</h2>
     * 四条断言全部 gate=true，进退出码 —— 本组是「时间线不坏」的唯一自动护栏。
     *
     * <p>曾为 record-only（gate=false），理由是「时间线表由人工落盘的 {@code mall_management_sql.sql}
     * 追加，DDL 未落盘时本组必然 FAIL —— 那是『没跑成』，不是『跑出来有问题』」。2026-09-30 升为
     * 门禁，因为该理由的两半都已不成立：
     * <ul>
     *   <li>P3 已断言「表数 = 11 + 已知追加表数」，表缺失在<b>负载段之前</b>就以退出码 2 拦下
     *       ⇒ 「DDL 未落盘」不可能再伪装成 C 组 FAIL；</li>
     *   <li>本组已在 VM 上首次真跑并全绿（run-id pre3l-b），不再有「从未验证过」的未知。</li>
     * </ul>
     * 故升门禁后的 FAIL 只剩一种含义：时间线真的不对。
     *
     * <p>⚠️ verdict 照落 PASS/FAIL（不落 RECORD_ONLY）：若落 RECORD_ONLY，就分不出「数据对」
     * 与「压根没读成」了 —— 而 C3 恰恰需要知道前者。
     *
     * <p>⚠️ 本场景若自身抛异常、或走了下面「表不可读」的提前 return，C-1/C-2 会停在默认
     * verdict {@code SKIP}，而 SKIP ≠ PASS ⇒ <b>照样算门禁失败</b>。故不存在「本组悄悄没跑成、
     * 退出码却是 0」的组合。
     */
    static void c(Opt o) throws InterruptedException {
        freshLimitWindow();   // CI 39056399205：C/A-C6 探针 6/6 被入站限流打成 429
        String lock0 = lockCounters(o);
        Ass a1 = ass("C-1", "时间线行数 == 四端点 success 之和（各端点 success 另与状态机预测值比对）", true);
        Ass a2 = ass("C-2", "时间线可重放：首行 from=NONE、逐行首尾相接、末行 to == order 表现值", true);
        Ass a3c6 = ass("A-C6-1", "出生闸：6 个非白名单组合全部被拒（success=false + 文案 + 查无此行）", true);
        Ass a4c6 = ass("A-C6-2", "出生闸的观测在场：birth.rejected 的 shape 覆盖 4 值（含 non_initial）", true);

        final String pre = ns() + "-C";
        final String like = pre + "%";
        final String countSql =
                "SELECT COUNT(*) FROM mall_management_sql.order_state_transition WHERE OrderId LIKE ?";

        // 基线取增量：--keep-data 且复用 --run-id 时上一次运行的行还在库里，
        // 直接比绝对值会把上轮的残留算成这轮写的。
        int base = jdbcCount(o, countSql, like);
        if (base < 0) {
            settle(a1, false, "时间线表不可读（DDL 未落盘？）—— 见上方 [JDBC] 行");
            settle(a2, false, "同上：表都不存在，重放无从谈起");
            rec("C", "tableReadable", false);
            System.out.println("[C] order_state_transition 不可读，C 组跳过 —— C-1/C-2 已 settle 为 FAIL，"
                    + "退出码=1（本组已是门禁）");
            return;
        }
        rec("C", "tableReadable", true);

        // ---- 1) 出生：白名单里的两个组合各来一单；PR 单另走合法流转造（见下）----
        // ⚠️ C#6（2026-10-04）之前这里造 8 个组合（含 3 个伪造态），因为 orderAdd 直收三位。
        // 拦截落地后只有白名单 {@code {(0,0,0),(1,0,0)}} 能出生，剩下 6 个组合改由 A-C6-1
        // 断言「被拒且**一行都没落**」。⇒ 副作用：时间线里**不再出现**「从非法态来」的行，
        // 那部分覆盖是有意放弃的，已登记给
        // {@code OrderStateMachineTest.onlyPayGuardAdmitsIllegalStates} 与
        // {@code OrderAddStateGuardSmokeTest}（见 docs/agent/progress.md §5-G 的 D1 登记）。
        String chain = pre + "000";     // CREATED → PAID → SHIPPED → COMPLETED（同单三连）
        String paid = pre + "100";      // 已付款：PAY 必被拒
        String pr = pre + "PR";         // 未发货即收货：DELIVER 与 RECEIVE 必被拒

        String[] bornIds = {chain, paid, pr};
        int[][] bornBits = {{0, 0, 0}, {1, 0, 0}, {0, 0, 0}};
        int addOk = 0;
        for (int i = 0; i < bornIds.length; i++) {
            Resp r = send(HTTP, o.baseUrl + "/order/orderAdd",
                    orderForm(bornIds[i], bornBits[i][0], bornBits[i][1], bornBits[i][2]));
            if (r.successTrue()) {
                addOk++;
            }
        }

        // A-C6-1：6 个非白名单组合，每一条都必须「success=false + 文案逐字 + 库里**查无此行**」。
        // ⚠️ 三条一起才算 —— 只看 success 会漏掉最坏的那种「返回了 false 却已经插进去」。
        int refusedOk = 0;
        StringBuilder refusedDetail = new StringBuilder();
        for (String bits : C_REFUSED) {
            String id = pre + bits;
            Resp r = send(HTTP, o.baseUrl + "/order/orderAdd",
                    orderForm(id, bits.charAt(0) - '0', bits.charAt(1) - '0', bits.charAt(2) - '0'));
            if (!r.successTrue() && r.says("订单状态不合法") && jdbcOrderState(o, id) == null) {
                refusedOk++;
            } else {
                refusedDetail.append(bits).append("→HTTP").append(r.code).append(' ')
                        .append(trunc(r.body)).append("; ");
            }
        }
        settle(a3c6, refusedOk == C_REFUSED.length && addOk == bornIds.length,
                "放行 " + addOk + "/" + bornIds.length + "（白名单）  被拒 " + refusedOk + "/"
                        + C_REFUSED.length + "（3 个合法但非初始 + 3 个非法，均须查无此行）"
                        + (refusedDetail.length() == 0 ? "" : "；例外：" + refusedDetail));

        // ---- 2) 八条 burst：一条三连链、PR 的两步合法推进、四次必被守卫拒 ----
        // PR（未发货即收货）**只能**走合法流转造：`000 → pay → receive`（后端本就放行未发货收货）。
        // C#6 之前它是直传 `(1,0,1)` 伪造的 —— 那条路已封。这两步各写 1 行时间线，算进期望值。
        int prSetupPay = burstSuccess(o, "C-prSetupPay", "/order/orderPayIfUp", pr);
        int prSetupRcv = burstSuccess(o, "C-prSetupRcv", "/order/orderReceiveIfUp", pr);

        int chainPay = burstSuccess(o, "C-pay", "/order/orderPayIfUp", chain);
        int chainDly = burstSuccess(o, "C-dly", "/order/orderDeliveryIfUp", chain);
        int chainRcv = burstSuccess(o, "C-rcv", "/order/orderReceiveIfUp", chain);
        int paidAgain = burstSuccess(o, "C-paidAgain", "/order/orderPayIfUp", paid);
        int prDly = burstSuccess(o, "C-prDly", "/order/orderDeliveryIfUp", pr);
        int prRcv = burstSuccess(o, "C-prRcv", "/order/orderReceiveIfUp", pr);

        int payOk = chainPay + prSetupPay;
        int dlyOk = chainDly;
        int rcvOk = chainRcv + prSetupRcv;
        boolean predicted = addOk == bornIds.length
                && chainPay == 1 && chainDly == 1 && chainRcv == 1
                && prSetupPay == 1 && prSetupRcv == 1
                && paidAgain == 0 && prDly == 0 && prRcv == 0;
        int expectedNew = addOk + payOk + dlyOk + rcvOk;

        // ---- 3) 排空：有界轮询等收敛，再静默窗口复读 ----
        int converged = waitForRows(o, like, base + expectedNew);
        Thread.sleep(C_QUIESCE_MS);
        int delta = jdbcCount(o, countSql, like) - base;

        // 被拒的三次 burst 必须一行都不写：拒绝不是转移，时间线里没有它的位置。
        // 三次 burst 各 20 条，只要混进去一条，这两单的行数就会超过各自的出生/流转行数。
        int paidRows = jdbcCount(o, countSql, paid);
        int prRows = jdbcCount(o, countSql, pr);

        boolean a1ok = predicted && delta == expectedNew && paidRows == 1 && prRows == 3;
        settle(a1, a1ok, "add=" + addOk + "/3 pay=" + payOk + "(预测 2) dly=" + dlyOk + "(1) rcv=" + rcvOk
                + "(2) PR 预置 pay/rcv=" + prSetupPay + "/" + prSetupRcv + "(1/1)"
                + " 被拒三次=" + paidAgain + "/" + prDly + "/" + prRcv
                + "(0/0/0) 时间线增量=" + delta + "/" + expectedNew
                + " 被拒单行数=" + paidRows + "/" + prRows + "(1/3 —— PR 的 3 行 = 出生+付款+收货) 收敛读数=" + converged);

        // A-C6-2：出生闸的**观测**必须在场。拦截本身是 A-C6-1 的证据，但 C3 的
        // 「今日拦截 N 次」读的是 mall.order.state.birth.rejected{shape} —— 拦下了却记不下来，
        // C3 的显示就会系统性偏小，而且**不报错**。故这条独立成门禁。
        // 判据取「4 个 shape 都在 + non_initial 这条序列真的存在」：后者尤其重要，
        // 它正是「合法但非初始」那 3 个组合的标号 —— 少写那条支路时，本组会红。
        Resp br = send(HTTP, o.baseUrl + "/actuator/metrics/mall.order.state.birth.rejected", null);
        Resp brNi = send(HTTP, o.baseUrl
                + "/actuator/metrics/mall.order.state.birth.rejected?tag=shape:non_initial", null);
        boolean shapesCovered = br.code == 200
                && br.says("010") && br.says("001") && br.says("011") && br.says("non_initial");
        boolean nonInitialCounted = brNi.code == 200 && brNi.says("COUNT");
        settle(a4c6, shapesCovered && nonInitialCounted,
                "HTTP " + br.code + " shape 覆盖 4 值=" + shapesCovered
                        + "；non_initial 序列存在（HTTP " + brNi.code + "）=" + nonInitialCounted
                        + (shapesCovered ? "" : "  ← 原文：" + trunc(br.body)));
        if (!a1ok) {
            // 只在**对不上**时读进程内指标：运行器的证据链是「接口响应 + 数据库」两层，
            // 都是权威状态；计数器只在权威状态对不上时才有资格当旁证。
            // 正常路径上「async.rejected==0」其实由 C-1 蕴含（丢一条任务必使行数 < 成功数），
            // 故不单列一条断言 —— 可派生的值不重复取（同埋点规范第 6 条）。
            dumpStateMetrics(o);
        }

        // ---- 4) 逐单重放 ----
        // 排序键是 (OccurredAt, Id)：OccurredAt 在**锁的临界区内**取，同单的转移被锁串行化
        // ⇒ 它才是发生顺序。Id 在 INSERT 时才由自增分配，而落库是 4 条线程并发做的 ——
        // 同单两次转移的 INSERT 理论上可以倒序落库，故 Id **单排**不足以为序，只作破平。
        Map<String, int[]> bitsOf = new LinkedHashMap<>();
        for (Object[] r : rowsOrEmpty(o,
                "SELECT OrderId, PayIf, DeliveryIf, ReceiveIf FROM mall_management_sql.`order` "
              + "WHERE OrderId LIKE ? ORDER BY OrderId", like)) {
            bitsOf.put(str(r[0]), new int[]{
                    ((Number) r[1]).intValue(), ((Number) r[2]).intValue(), ((Number) r[3]).intValue()});
        }
        Map<String, List<Object[]>> tlOf = new LinkedHashMap<>();
        for (Object[] r : rowsOrEmpty(o,
                "SELECT OrderId, FromState, ToState, Id FROM mall_management_sql.order_state_transition "
              + "WHERE OrderId LIKE ? ORDER BY OrderId, OccurredAt, Id", like)) {
            tlOf.computeIfAbsent(str(r[0]), x -> new ArrayList<>()).add(r);
        }

        CReplay rp = replay(bitsOf, tlOf);

        settle(a2, rp.clean(), "重放单数=" + rp.checked + " 无时间线的单=" + rp.missing
                + " 首行非 NONE=" + rp.firstNotNone + " 链断=" + rp.chainBroken
                + " 末态不符=" + rp.lastMismatch
                + (rp.example.isEmpty() ? "" : "；例：" + rp.example)
                + "；Id 序与 OccurredAt 序不一致的单=" + rp.idOrderDiffers
                + "（此数是为让上面那条排序判断有实测依据，不靠推断）");

        System.out.println("[C] 时间线增量 " + delta + " 行（期望 " + expectedNew + "）"
                + " 重放 " + rp.checked + " 单：无时间线=" + rp.missing + " 链断=" + rp.chainBroken
                + " 末态不符=" + rp.lastMismatch + " Id 序异常单=" + rp.idOrderDiffers);
        rec("C", "addOk", addOk);
        rec("C", "addRefusedExpect6", refusedOk);
        rec("C", "payOk", payOk);
        rec("C", "deliveryOk", dlyOk);
        rec("C", "receiveOk", rcvOk);
        rec("C", "rejectedTransitions", paidAgain + prDly + prRcv);
        rec("C", "timelineRowsDelta", delta);
        rec("C", "timelineRowsExpected", expectedNew);
        rec("C", "convergedRows", converged);
        rec("C", "ordersReplayed", rp.checked);
        rec("C", "ordersWithoutTimeline", rp.missing);
        rec("C", "chainBreaks", rp.chainBroken);
        rec("C", "lastStateMismatches", rp.lastMismatch);
        rec("C", "idOrderDiffersFromOccurredAt", rp.idOrderDiffers);
        rec("C", "recordOnly", true);
        lockDelta(o, "C", lock0);
    }

    /** 逐单重放的结果。**分开计数**而不是一个 boolean：每一项对应一类不同的坏法，混成一个数就分不出坏在哪。 */
    static final class CReplay {
        int checked;
        int missing;
        int firstNotNone;
        int chainBroken;
        int lastMismatch;
        int idOrderDiffers;
        String example = "";

        /** `checked > 0` 也在判据里：一单都没读到（查询失败）不能被当成「没有坏单」。 */
        boolean clean() {
            return checked > 0 && missing == 0 && firstNotNone == 0 && chainBroken == 0 && lastMismatch == 0;
        }
    }

    /**
     * C-2 的**全部**判断都在这里：首行必须 NONE（出生占位），相邻两行必须首尾相接，
     * 末行必须等于按三位**独立算出**的状态名。
     *
     * <p>抽成静态方法不是为了复用，是为了它能被直接驱动 —— 本场景其余部分都是 I/O
     * （起后端、连库），只有这一段是纯判断，而它恰好是 C-2 的全部内容。
     * 抽出来才能用假表做突变对照（见开发期探针），不必等 CI。
     *
     * <p>入参行格式是本方法的契约（与 {@link #c} 里的两条 SELECT 列序一致）：
     * {@code bitsOf} 的值是 {@code [PayIf, DeliveryIf, ReceiveIf]}；
     * {@code tlOf} 的行是 {@code [OrderId, FromState, ToState, Id]}，且**已按 (OccurredAt, Id) 排好序**
     * —— 排序由 SQL 负责（见 {@link #c} 里那段排序键的说明），本方法不再排。
     */
    static CReplay replay(Map<String, int[]> bitsOf, Map<String, List<Object[]>> tlOf) {
        CReplay out = new CReplay();
        for (Map.Entry<String, int[]> e : bitsOf.entrySet()) {
            String id = e.getKey();
            int[] b = e.getValue();
            List<Object[]> tl = tlOf.get(id);
            out.checked++;
            if (tl == null || tl.isEmpty()) {
                out.missing++;
                continue;
            }
            if (!"NONE".equals(str(tl.get(0)[1]))) {
                out.firstNotNone++;
            }
            long prevId = Long.MIN_VALUE;
            boolean ascending = true;
            for (int i = 0; i < tl.size(); i++) {
                long rid = ((Number) tl.get(i)[3]).longValue();
                if (rid < prevId) {
                    ascending = false;
                }
                prevId = rid;
                if (i > 0 && !str(tl.get(i)[1]).equals(str(tl.get(i - 1)[2]))) {
                    out.chainBroken++;
                }
            }
            if (!ascending) {
                out.idOrderDiffers++;
            }
            String wantLast = stateName(b[0], b[1], b[2]);
            String gotLast = str(tl.get(tl.size() - 1)[2]);
            if (!wantLast.equals(gotLast)) {
                out.lastMismatch++;
                if (out.example.isEmpty()) {
                    out.example = id + "：时间线末态=" + gotLast + "，实际三位=" + b[0] + b[1] + b[2]
                            + " → 独立算得 " + wantLast;
                }
            }
        }
        return out;
    }

    /** 对单个 orderId 打 20 并发，返回 success:true 的条数。 */
    static int burstSuccess(Opt o, String tag, String path, String orderId) throws InterruptedException {
        List<Call> cs = burst(20, i -> call(tag, send(HTTP, o.baseUrl + path, form("orderId", orderId))));
        return countSuccess(cs);
    }

    /**
     * 排空判据：有界轮询**等行数收敛到期望值**，不睡一个猜出来的时长。
     * 返回最后一次读数（未收敛时它 < want，调用方的等值断言据此判红）。
     */
    static int waitForRows(Opt o, String like, int want) {
        String sql = "SELECT COUNT(*) FROM mall_management_sql.order_state_transition WHERE OrderId LIKE ?";
        long t0 = System.currentTimeMillis();
        int polls = 0;
        int got = -1;
        while (System.currentTimeMillis() - t0 < C_DRAIN_TIMEOUT_MS) {
            got = jdbcCount(o, sql, like);
            polls++;
            if (got >= want) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        rec("C", "drainPolls", polls);
        rec("C", "drainElapsedMs", System.currentTimeMillis() - t0);
        rec("C", "drainTimeoutMs", C_DRAIN_TIMEOUT_MS);
        return got;
    }

    /**
     * 三位 → 状态名的**独立副本**（从 {@code OrderState} 枚举抄来）。
     * 刻意不共享代码：运行器与应用分属两个编译单元、不共享类路径，而这里要证的正是
     * 「应用写进时间线的名字，与我们独立算出的名字一致」—— 共用同一份映射就成了自证。
     * 应用里改了枚举名，这里会红，那是有意的提醒。
     *
     * <p>⚠️ <b>C#6（2026-10-04）之后，末尾那三个 {@code ILLEGAL_*} 分支在 C 组里已成为
     * <b>死代码</b></b>：出生闸关掉伪造入口后，本组造的每一单都是从白名单组合出生的合法单，
     * 时间线里不会再有「末态是非法态」的行。它们**保留不删** —— 理由与
     * {@code mall.order.state.illegal} 保留不删相同：① 存量脏数据若还在库里，C-2 的重放
     * 仍会读到它；② 删掉等于抹掉 TD-17 的历史口径。伪造态的形状分类覆盖已改由
     * {@code OrderStateMachineTest} 与 {@code OrderAddStateGuardSmokeTest} 承担（见
     * {@code C_REFUSED} 的 javadoc）。
     */
    static String stateName(int pay, int dly, int rcv) {
        if (pay == 0 && dly == 0 && rcv == 0) {
            return "CREATED";
        }
        if (pay == 1 && dly == 0 && rcv == 0) {
            return "PAID";
        }
        if (pay == 1 && dly == 1 && rcv == 0) {
            return "SHIPPED";
        }
        if (pay == 1 && dly == 0 && rcv == 1) {
            return "RECEIVED_NODELIVERY";
        }
        if (pay == 1 && dly == 1 && rcv == 1) {
            return "COMPLETED";
        }
        if (pay == 0 && dly == 1 && rcv == 0) {
            return "ILLEGAL_DELIVERED_UNPAID";
        }
        if (pay == 0 && dly == 0 && rcv == 1) {
            return "ILLEGAL_RECEIVED_UNPAID";
        }
        if (pay == 0 && dly == 1 && rcv == 1) {
            return "ILLEGAL_DELIVERED_RECEIVED_UNPAID";
        }
        return "UNCLASSIFIED";
    }

    /**
     * 只在 C-1 对不上时调用的旁证：把 B3 的计数与队列深度原文打出来。
     * 见 {@link #c} 里「为什么正常路径不读 metrics」。
     */
    static void dumpStateMetrics(Opt o) {
        System.out.println("[C] 时间线对不上，B3 指标原文（旁证，不判定）:");
        for (String m : new String[]{"mall.order.state.transitions", "mall.order.state.timeline.writes",
                "mall.order.state.async.rejected", "mall.order.state.async.queue.depth",
                "mall.order.state.illegal", "mall.order.state.birth.rejected"}) {
            Resp r = send(HTTP, o.baseUrl + "/actuator/metrics/" + m, null);
            System.out.println("  HTTP " + r.code + "  " + m + "  → " + trunc(r.body));
        }
    }

    static List<Object[]> rowsOrEmpty(Opt o, String sql, Object... args) {
        List<Object[]> rows = jdbcRows(o, sql, args);
        return rows == null ? List.of() : rows;
    }

    static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** B-2 逐轮明细。报告要的是嵌套数组，塞进 REC 的扁平字符串会丢结构。 */
    static class B2Round {
        int round, login5xx, loginOk, loginExisted, loginUnclear;
        int gtRows, gtOk, frRows, frOk;
        String verdict;

        String line() {
            return "第 " + round + " 轮  loginAdd(5xx=" + login5xx + " 成功=" + loginOk + " 已存在=" + loginExisted
                    + " 未判定=" + loginUnclear + ")  goodstype(重复行=" + fmtRows(gtRows) + " 新增成功=" + gtOk + ")"
                    + "  friendAdd(重复行=" + fmtRows(frRows) + " 申请成功=" + frOk + ")  → " + verdict;
        }

        static String fmtRows(int n) {
            return n < 0 ? "未能核对" : String.valueOf(n);
        }
    }

    static final List<B2Round> B2_ROUNDS = new ArrayList<>();

    /**
     * B-2：三处 check-then-insert（TOCTOU），各跑 o.rounds 轮（默认 5，H3 澄清 1 的 B 组轮数）。
     *
     * 三处端点的**表约束不同，故缺陷形态不同** —— 这是本场景的要点，不是细节：
     *   · /user/loginAdd → 写 login / user / customerimage，三表**均有主键**
     *     （login.UserName、user.username、customerimage.UserName）。所以并发同参**插不出重复行**：
     *     check 与 insert 之间无锁 → 20 个请求全部穿过 check → 主键把第 2 个起的 INSERT 挡成
     *     ERROR 1062 → 未捕获 → Spring 默认 500。**信号是 5xx，不是重复行。**
     *   · /goods/goodstype（POST）→ 写 goodstype，只有自增主键 → **能插出重复行**。
     *   · /message/friendAdd → 写 friend，只有自增主键 → **能插出重复行**。
     *
     * 因此本轮 verdict 取「三处任一出现信号」：5xx>0 或任一表重复行≥2。
     * 注意极性：B-2 里 **5xx 本身就是缺陷在显形**，与其它场景「5xx==0 才算过」相反。
     * 故按裁决默认 record-only（--b-group-gate=false），要门禁须显式打开。
     *
     * 每轮必须换全新的 username / GoodType / 好友对：否则第 2 轮起 check 会命中第 1 轮写入的行，
     * 请求直接走「已存在」分支、根本不进并发窗口，复现率会被自己伪造出来。
     */
    static void b2(Opt o) throws Exception {
        final int TH = 20;
        String pre = NS_PREFIX + o.runId;
        int repro = 0;
        int gtDupRounds = 0, frDupRounds = 0, login5xxRounds = 0;

        B2_ROUNDS.clear();
        for (int r = 1; r <= o.rounds; r++) {
            B2Round b = new B2Round();
            b.round = r;
            String u = pre + "-U" + r;
            String gt = pre + "-GT" + r;
            String fA = pre + "-F" + r + "-A";
            String fB = pre + "-F" + r + "-B";

            // ---- 1) loginAdd 同 username ×20 ----
            List<Call> a = burst(TH, i -> call("loginAdd", send(HTTP, o.baseUrl + "/user/loginAdd",
                    form("username", u, "usertype", "客户", "userid", u,
                         "password", "a3pass", "age", 20, "gender", "男"))));
            b.login5xx = count5xx(a);
            b.loginOk = countIf(a, x -> x.code == 200 && x.body.contains("添加成功"));
            b.loginExisted = countIf(a, x -> x.code == 200 && x.body.trim().equals("0"));
            // HTTP 200 但既不是「添加成功」也不是「0」→ 判不了，单列出来，不往任一格里硬塞
            b.loginUnclear = TH - b.login5xx - b.loginOk - b.loginExisted;
            if (b.login5xx > 0) {
                login5xxRounds++;
            }
            if (b.loginUnclear > 0) {
                System.out.println("[B-2] 第 " + r + " 轮 loginAdd 有 " + b.loginUnclear + " 条既非 5xx、"
                        + "也非「添加成功」/「0」——原文如下（未判定数不为 0 时必看）:");
                // 同一轮里把「期望字面量」与「实际响应体」都按字节打出来，
                // 才能分清是响应不对还是判定不对（终端乱码时分不清）。
                System.out.println("      期望字面量 esc: 添加成功=" + esc("添加成功")
                        + "  单独0=" + esc("0"));
                dumpSamples("loginAdd", a, 3);
            }

            // ---- 2) goodstype 同 GoodType ×20（GoodTypeNew 必须给：列是 NOT NULL，缺参会 NULL→失败）----
            List<Call> g = burst(TH, i -> call("goodstype", send(HTTP, o.baseUrl + "/goods/goodstype",
                    form("GoodType", gt, "GoodTypeNew", 229121))));
            b.gtOk = countMsg(g, "新增成功");
            int gtRows = jdbcCount(o, "SELECT COUNT(*) FROM mall_management_sql.goodstype WHERE GoodType = ?", gt);
            if (gtRows < 0) {
                System.err.println("[B-2] goodstype 重复行数未能核对（JDBC 失败），本轮按未能核对记，不计入复现");
            }
            b.gtRows = gtRows;
            if (gtRows >= 2) {
                gtDupRounds++;
            }
            // 插了行却一条「新增成功」都没有 = 计数与响应体互相矛盾，必须打原文
            if (gtRows >= 2 && b.gtOk == 0) {
                System.out.println("[B-2] 第 " + r + " 轮 goodstype 行数=" + gtRows + " 但「新增成功」=0，"
                        + "两者矛盾，响应原文如下:");
                dumpSamples("goodstype", g, 3);
            }

            // ---- 3) friendAdd 同好友对 ×20 ----
            List<Call> f = burst(TH, i -> call("friendAdd", send(HTTP, o.baseUrl + "/message/friendAdd",
                    form("customerId", fA, "friendId", fB))));
            b.frOk = countMsg(f, "好友申请已发送");
            int frRows = jdbcCount(o, "SELECT COUNT(*) FROM mall_management_sql.friend "
                    + "WHERE customerid = ? AND friendid = ?", fA, fB);
            if (frRows < 0) {
                System.err.println("[B-2] friend 重复行数未能核对（JDBC 失败），本轮按未能核对记，不计入复现");
            }
            b.frRows = frRows;
            if (frRows >= 2) {
                frDupRounds++;
            }

            boolean signal = b.login5xx > 0 || gtRows >= 2 || frRows >= 2;
            b.verdict = signal ? "EXPECTED_DEFECT_PRESENT" : "DEFECT_FIXED";
            if (signal) {
                repro++;
            }
            B2_ROUNDS.add(b);
            System.out.println("[B-2] " + b.line());
        }

        int n = B2_ROUNDS.size();
        Ass summary = ass("A-B2-1", "B-2 三处 TOCTOU（loginAdd / goodstype / friendAdd）逐轮复现：rounds=" + n
                + " reproRounds=" + repro + "；复现轮 < 全部轮 即为弱证据（可能只是竞态未重叠，不等于缺陷已修）",
                o.bGroupGate);
        summary.rounds = n;
        summary.reproRounds = repro;
        if (o.bGroupGate) {
            // 门禁模式：verdict 按缺陷语义落，repro=0 即 FAIL
            if (repro > 0) {
                settleDefect(summary, true, "reproRounds=" + repro + "/" + n
                        + "（loginAdd 出 5xx 的轮=" + login5xxRounds + "，goodstype 出重复行的轮=" + gtDupRounds
                        + "，friendAdd 出重复行的轮=" + frDupRounds + "）");
            } else {
                // 缺陷未复现有两种可能，裁决要求两种都写出来，不能只报「已修复」
                summary.verdict = "DEFECT_FIXED";
                summary.detail = "reproRounds=0/" + n + " —— 两种可能：(a) 缺陷已被修复（check 与 insert 已原子化）；"
                        + "(b) 本轮竞态未重叠（20 线程没同时穿过 check-and-insert 窗口）。"
                        + "逐轮明细见上方 [B-2] 行与报告的 perRound，勿只看这一条下结论";
            }
        } else {
            // record-only 模式：本场景 5xx 是缺陷显形信号，与其它场景「5xx==0 才算过」极性相反，
            // 故默认不进退出码。verdict 落 RECORD_ONLY 而不是 DEFECT_FIXED —— 后者会谎称「缺陷已修」。
            summary.verdict = "RECORD_ONLY";
            summary.detail = "reproRounds=" + repro + "/" + n + "（--b-group-gate=false，不计入退出码）"
                    + "；分类计数 login5xx轮=" + login5xxRounds + " goodstype重复行轮=" + gtDupRounds
                    + " friend重复行轮=" + frDupRounds;
        }

        // 逐轮明细不落进 REC：REC 只存「聚合取数」用的标量，逐轮是结构化的，
        // 由报告直接读 B2_ROUNDS 写成数组（见 writeReport 的 b2PerRound）。
        // 早先把每轮的 line() 塞成 round1/round2… 的扁平字符串，等于把「给人看的
        // 打印格式」变成报告的契约 —— 改一个字就静默失效。
        rec("B2", "rounds", n);
        rec("B2", "reproRounds", repro);
        rec("B2", "login5xxRounds", login5xxRounds);
        rec("B2", "goodstypeDupRounds", gtDupRounds);
        rec("B2", "friendDupRounds", frDupRounds);
        rec("B2", "recordOnly", !o.bGroupGate);
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
        // B3 新增的时间线表。紧挨 order 放：它是 order 的 1:N 派生行，两者一起读才看得出
        // 「订单清了、它的转移记录也必须一起清」——否则下一轮的 P4 种子计数虽不受影响
        // （它只数 order），但按 OrderId LIKE 的核对会读到上一轮的残留。
        CLEANUP_COLS.put("order_state_transition", new String[]{"OrderId"});
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
     * 按命名空间清理 + 全局清扫旧版哨兵残留（G1 的兜底）。
     * A3 从不写 message / user_conversation_read，故这两表不纳入 —— 不是遗漏，是范围。
     */
    static void cleanup(Opt o) {
        String p = ns() + "%";
        int grand = 0;
        rec("cleanup", "namespace", p);
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
                rec("cleanup", "table." + tbl, del);
            }
            // 旧版哨兵残留全局清扫。2026-09-27 起 P6 不再写 A3P- 行，本语句只为清理
            // 旧版脚本（及被 SIGKILL 的进程）留下的残留而保留。前缀是代码字面量，
            // 正常数据不可能命中，故不带 runId 清 —— 任何后续 run 都能自愈。
            int probe = s.executeUpdate("DELETE FROM mall_management_sql.goodstype WHERE GoodType LIKE '"
                    + PROBE_PREFIX + "%'");
            grand += probe;
            System.out.println("  " + pad("goodstype(旧哨兵残余)", 16) + "删除=" + probe
                    + "（A3P-% 残留清扫，" + (probe == 0 ? "无残留" : "清掉了残留") + "）");
            System.out.println("[cleanup] 删除语句命中合计 " + grand + " 行（同一行可能被多列命中，故非去重行数）");
            rec("cleanup", "probeRowsDeleted", probe);
            rec("cleanup", "rowsDeletedTotal", grand);
            rec("cleanup", "status", "OK");
        } catch (SQLException e) {
            System.err.println("[cleanup] 失败: " + e.getMessage()
                    + "  ← 残留需人工处理；命名空间 " + p + " 与 " + PROBE_PREFIX + "% 是查残留的两个前缀");
            // 清理失败必须进报告：报告说「跑完了」而库里有残留，是最容易骗过下一位读者的组合
            rec("cleanup", "status", "FAILED");
            rec("cleanup", "error", e.getMessage());
        }
    }

    // ===================== 源码字符集自检 =====================

    /**
     * 源码字符集自检 —— 在产生任何判定之前拒绝「静默错」。
     *
     * 起因（A3 级别缺陷，非 B-2 级别）：单文件源码启动 `java Foo.java` 由启动器**在运行期就地编译**，
     * 此时**不使用**任何编译期 -encoding，而按宿主平台默认字符集解读源文件。Windows 上
     * file.encoding=GBK，于是源里所有中文串被按 GBK 解成乱码：跑起来不报任何错，
     * 但一切依赖中文响应体的断言（msg == 「订单不存在或已付款」、「数据库错误」前缀、
     * loginAdd 的「添加成功！」…）全部静默落空。实测：源码模式 + GBK 下 S3 由
     * 「异常单=0 / A-S3-1..2 PASS」变成「异常单=10 / FAIL」；加 -Dfile.encoding=UTF-8 后恢复。
     * 即：**同一个脚本、同一台机器，只因启动方式不同，结论相反且不报错。**
     *
     * 检测办法：把期望码点写成数字字面量（数字不受字符集影响），与源里的中文字面量比较。
     * 不一致 ⇒ 本次运行的源被按非 UTF-8 解读 ⇒ 中文断言不可信，直接拒绝运行。
     * 不用「加 -Dfile.encoding=UTF-8」来兜：那是掩盖症状，且只在本地有效；
     * 根治是改启动方式为两步（javac -encoding UTF-8 → java 跑 class），见 run.sh 与 HELP。
     */
    static boolean charsetSelfCheck() {
        String literal = "添加成功！";
        String byCodePoint = new String(new char[]{0x6DFB, 0x52A0, 0x6210, 0x529F, 0xFF01});
        boolean ok = literal.equals(byCodePoint);
        // 这行同时钉住历史缺口：此前日志不记录启动方式，事后无法判断某次运行是源码模式
        // 还是两步编译。现在每次运行都把平台字符集与自检结果写进日志，可追溯。
        System.out.println("charset        : file.encoding=" + System.getProperty("file.encoding")
                + "  native.encoding=" + System.getProperty("native.encoding")
                + "  stdout/stderr=强制 UTF-8（见 forceUtf8Output）");
        System.out.println("                 启动方式=两步编译（javac -encoding UTF-8 + java -cp <out>…）"
                + "  源码字面量自检=" + (ok ? "PASS（中文串未被字符集损坏）" : "FAIL（中文串已失真）"));
        if (ok) {
            return true;
        }
        System.err.println("【拒绝运行】源码被按非 UTF-8 字符集解读，中文串已失真：");
        System.err.println("  源里的字面量 esc: " + esc(literal));
        System.err.println("  期望码点   esc: " + esc(byCodePoint));
        System.err.println("  影响：所有依赖中文响应体的断言（msg 比对、「数据库错误」前缀等）会静默落空，"
                + "跑出来的 PASS/FAIL 不可信。");
        System.err.println("  原因：单文件源码启动（java Foo.java）由启动器在运行期就地编译，"
                + "不采用编译期 -encoding，而按平台默认字符集解读源文件。");
        System.err.println("  处理：改用两步 —— javac -encoding UTF-8 -d <out> <本文件> 后 "
                + "java -cp <out>:<mysql-connector.jar> ConcurrencyRunner（run.sh 即此做法）。");
        System.err.println("        临时绕过可加 -Dfile.encoding=UTF-8，但那是掩盖症状，仅本地有效。");
        return false;
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

            // P3 表数（兼 TD-01 校验：启动后未反向改表）
            // ⚠️ 2026-10-04 起 ddl-auto 已由 update 改为 validate（TD-01 B 方案），
            // 本校验的判据不变 —— 从「查 update 没乱改表」变成「查 validate 没乱改表」。
            // 期望 = 11 张基线表 + **已知追加表**；已知追加表在涨：B3 的 order_state_transition（1 张）、
            // C3 块 1（2026-10-09）admin_audit / notification（2 张）、
            // C3 块 2（2026-10-09）merchant_application（1 张）、C3 块 3（2026-10-10）goods_audit（1 张）、
            // C3 块 3 就地改造（2026-10-10）template（1 张）、
            // C3 块 4（2026-10-10）cart（1 张）、C3 块 4 补丁（2026-10-10）order_shipping_track（1 张）、可视化段 commit 0（2026-10-11）order_item（1 张）、可视化段 commit 2（2026-10-11）stats_daily（1 张）、可视化段 commit 3 补丁（2026-10-11）goods_sku（1 张）
            // —— 十一张同在 mall_management_sql.sql 末尾的 Migration 块、人工落盘（见 db-schema.md「表数量：29」）。
            // ⚠️ 就地改造那笔把 notification_template 物理删了、goods_template 物理删了、新增 template ⇒ 7 → 6。
            // ⚠️ 块 4 新增 cart ⇒ 6 → 7、块 4 补丁（2026-10-10）新增 order_shipping_track ⇒ 7 → 8、可视化段 commit 0 新增 order_item ⇒ 8 → 9、可视化段 commit 2 新增 stats_daily ⇒ 9 → 10、可视化段 commit 3 补丁（2026-10-11）新增 goods_sku ⇒ 10 → 11、C3 块 5（2026-10-11）新增 coupon / coupon_grant / subsidy_rule / subsidy_goods / seckill_invitation / seckill_application / seckill_activity ⇒ 11 → 18、C3 块 5 B 组（2026-10-12）新增 shop_follow ⇒ 18 → 19、C3 块 6（2026-10-12）新增 comment / comment_mention / comment_image / comment_like / report / sensitive_word ⇒ 19 → 25、C3 块 7（2026-10-13）新增 drill_record ⇒ 25 → 26、C3 块 7 commit 8b（2026-10-14）新增 observed_event ⇒ 26 → 27
            //    （**本清单、KNOWN_TABLES、上面那句表数说明、下面 P3 的 PASS 文案串 —— 四处同改**；少改一处 A3 的 P3 必红 ——
            //    而 concurrency job 是 continue-on-error，红在 job 颜色上看不出来，只能读 artifact 的 gates.count）。
            // 刻意不写死总数：运行器与那份 SQL 是两笔改动，谁先落都要能跑。但**多出来的每一张都必须点过名**
            // （下面的 IN 清单），且清单里那几张必须**真在**（`known == KNOWN_TABLES` 那一半）—— 否则
            // 「表数对上」只是「多了几张没登记的表」，正是 TD-01 要抓的反向改表（update 模式下按实体
            // 反向建出来的表；现已是 validate，此判据保留为回归防线 —— 将来谁再改回 update 就会命中）。
            // 名字比较套 LOWER()：Linux 上表名区分大小写（lower_case_table_names=0），
            // 而 information_schema 的排序规则各版本不一致，归一化后两端都成立。
            int tables = scalarInt(c,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()");
            int known = scalarInt(c,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                  + "AND LOWER(table_name) IN ('order_state_transition','admin_audit','notification',"
                  + "'merchant_application','goods_audit','template','cart','order_shipping_track','order_item','stats_daily','goods_sku',"
                  + "'coupon','coupon_grant','subsidy_rule','subsidy_goods','seckill_invitation','seckill_application','seckill_activity','shop_follow',"
                  + "'comment','comment_mention','comment_image','comment_like','report','sensitive_word','drill_record','observed_event')");
            final int KNOWN_TABLES = 27;   // = 上面 IN 清单的长度（加表时**四处同改**，见上方注释块）
            boolean p3 = tables == 11 + known && known == KNOWN_TABLES;
            pre.put("P3", (p3 ? "PASS" : "FAIL") + " 表数=" + tables + "；已知追加表 order_state_transition /"
                    + " admin_audit / notification / merchant_application /"
                    + " goods_audit / template / cart / order_shipping_track / order_item / stats_daily / goods_sku /"
                    + " coupon / coupon_grant / subsidy_rule / subsidy_goods / seckill_invitation / seckill_application / seckill_activity / shop_follow /"
                    + " comment / comment_mention / comment_image / comment_like / report / sensitive_word / drill_record / observed_event 在 " + known + "/" + KNOWN_TABLES
                    + " 张（期望表数 = 11 + " + KNOWN_TABLES + " = " + (11 + KNOWN_TABLES) + "）");

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
     * 【2026-09-27 整体改版：从「哨兵往返」到「库身份对比」】
     * 原 P5/P6 共享一个前提：「HTTP 读回来的内容 == 库里的内容」。B1 给
     * GET /goods/goodstype 加缓存后该前提破裂，两条断言同时受伤：
     *   - P5 缓存命中时一次 DB 都不碰 → 退化成「Web 就绪」，Hikari 挂了也能绿（静默）；
     *   - P6 裸 JDBC 直写哨兵行再经 HTTP 读回 → 缓存返回填充时刻的旧列表 →
     *     哨兵行必然读不到 → 结构性 FAIL（重跑多少次都一样，非偶发）。
     * 病根是同一个假设，故 P5 与 P6 同源同修：改为让后端自报库身份。
     *
     * P5 联合就绪探针 —— 轮询 GET /health/db 直到响应含 "success":true。
     *   端口通不等于就绪：该端点会真落一次 DB（SELECT @@server_uuid, …），且【永不被缓存】
     *   （HealthController 无 @Cacheable，且只 @Profile("a3") 生效），
     *   故它返回成功才同时证明 Web 层与 Hikari 连接池都可用。
     *   等待上限 --ready-timeout-sec（默认 60s），超时即 FAIL（不视为 skip）。
     *
     * P6 库身份对比 —— 脚本 JDBC 查 @@server_uuid / @@hostname / DATABASE() / @@port，
     *   与 /health/db 报的逐字段比。证明「后端连的库实例」与「脚本连的库实例」是同一个。
     *   本方案不依赖任何被缓存的数据，故与缓存【永久正交】。
     *   判定：主判据 server_uuid 一致 → PASS；hostname 不一致只告警（克隆 VM 可能保留
     *   原主机名）；database / port / version 只作参考，不参与判定 —— 实测 database 与
     *   port 在两台机器上必然相同（判别力为零），version 随本机升级到 8.4 即失效。
     *
     * P7 阴性对照 —— 本机库（--negative-control-url）的 @@server_uuid 必须 ≠ /health/db
     *   报的 server_uuid。两者相同即说明后端连到了开发机上的同名库（TD-13：两库同名，
     *   单看库名区分不出）。本机不可达，或本机没有同名库（驱动抛 Unknown database，
     *   走同一个 SQLException 分支）→ 记 skip，不阻断（CI 上本就没有本机库）。
     *   本机库全程只读：A3 未获授权改动本机库，阴性对照不需要写。
     *
     * 【P5/P6/P7 共用一次 HTTP】P5 轮询循环最后一次成功的响应体就是 /health/db 的 body，
     *   P6/P7 直接复用它，不再另发请求 —— 既省一次调用，也天然避免两次调用之间状态漂移。
     */
    static boolean preflightHttp(Opt o, Map<String, String> pre) {
        HttpClient http = HTTP;
        String url = o.baseUrl + "/health/db";

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
                + (p5 ? "，含 \"success\":true（Web+DB 联合就绪；该端点不被缓存）"
                      : "（" + (lastErr == null ? "超时" : lastErr) + "）"));
        if (!p5) {
            // 显式记 SKIP 而不是直接少两行：前置表缺行与「没跑到」在报告里无法区分
            pre.put("P6", "SKIP 后端未就绪，未做库身份对比");
            pre.put("P7", "SKIP 同上");
            return false;
        }

        // ---------- P6 库身份对比 / P7 阴性对照；共用上面那一次 /health/db 的响应体 ----------
        if (!dbIdentityCheck(o, body, pre)) {
            pre.put("P7", "SKIP 库身份未验证，阴性对照无从对照");
            return false;
        }
        return negativeControl(o, body, pre);
    }

    /**
     * P6：库身份对比（原「哨兵往返」，2026-09-27 改）。
     *
     * 判定：主判据 server_uuid 一致 → PASS；不一致 → FAIL。
     *   辅判据 hostname 不一致 → 只告警（克隆 VM 可能保留原主机名），仍 PASS。
     *   参考项 database / port / version → 只报告，不参与判定（理由见方法内注释）。
     *
     * 用 contains 而非解析 JSON：与既有风格一致，且脚本无 JSON 库、不引新依赖。
     * 逐字段而非整串比对，是为了 FAIL 时能指出【是哪个字段不一致】。
     * 本方法不写库：原方案每轮消耗 1 个 goodstype 自增值，「P6 不留痕迹」不成立；
     * 改版后 P6 全程只读，自增差额这一项已消失。
     */
    static boolean dbIdentityCheck(Opt o, String body, Map<String, String> pre) {
        String uuid, host, db, port, ver;
        try (Connection c = DriverManager.getConnection(o.jdbcUrl, o.jdbcUser, o.jdbcPassword)) {
            uuid = scalarString(c, "SELECT @@server_uuid");
            host = scalarString(c, "SELECT @@hostname");
            db   = scalarString(c, "SELECT DATABASE()");
            port = scalarString(c, "SELECT @@port");
            ver  = scalarString(c, "SELECT VERSION()");
        } catch (SQLException e) {
            pre.put("P6", "FAIL 库身份对比失败（脚本侧 JDBC）: " + e.getMessage());
            return false;
        }
        if (body == null) {
            pre.put("P6", "FAIL 库身份对比失败：/health/db 响应体为空");
            return false;
        }

        boolean uuidOk = uuid != null && body.contains("\"serverUuid\":\"" + uuid + "\"");
        boolean hostOk = host != null && body.contains("\"hostname\":\"" + host + "\"");
        // 参考项：仅报告。实测 database 与 port 在 VM 与本机上必然相同（两库同名 TD-13、
        // 同为 3306），判别力为零；version 会随本机升级到 8.4 而失效。三者都不作判据。
        boolean dbOk   = db   != null && body.contains("\"database\":\"" + db   + "\"");
        boolean portOk = port != null && body.contains("\"port\":"       + port);
        boolean verOk  = ver  != null && body.contains("\"version\":\""  + ver  + "\"");

        pre.put("P6", (uuidOk ? "PASS " : "FAIL ")
                + "库身份对比（GET /health/db vs 脚本 JDBC）"
                + " server_uuid=" + uuid + "→" + (uuidOk ? "一致" : "**不一致**")
                + "；hostname=" + host + "→" + (hostOk ? "一致"
                        : "不一致（⚠️ 克隆 VM 可能保留原主机名，不据此判定）")
                + "；参考项 database=" + (dbOk ? "一致" : "不一致")
                + " port=" + (portOk ? "一致" : "不一致")
                + " version=" + (verOk ? "一致" : "不一致"));
        if (!uuidOk) {
            System.err.println("后端报的 server_uuid 与脚本连的库不一致 —— 后端不在同一个库实例上。"
                    + "\n  /health/db 原始响应：" + body);
        }
        return uuidOk;
    }

    /**
     * P7：阴性对照（2026-09-27 改）。
     *
     * 判据：本机库的 @@server_uuid 必须 ≠ /health/db 报的 server_uuid。
     *   两者相同 → 后端连的就是开发机上的同名库（TD-13：两库同名，单看库名区分不出）→ FAIL。
     *   本机不可达，或本机没有同名库（驱动抛 Unknown database，走同一个 SQLException 分支）
     *   → SKIP 不阻断（CI 上本就没有本机库）。
     *   本机库全程只读：A3 未获授权改动本机库，阴性对照不需要写。
     *
     * 原方案的两条判据都已被替换：主判据「哨兵行不在本机」随哨兵机制一起删除；
     * 辅判据「本机 VERSION() ≠ 目标」随本机升级到 8.4 即自动失效。server_uuid 不会烂掉 ——
     * 它在 MySQL 初始化数据目录时生成、全局唯一，克隆虚拟机也不会重复。
     */
    static boolean negativeControl(Opt o, String healthBody, Map<String, String> pre) {
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
            String localUuid = scalarString(c, "SELECT @@server_uuid");
            String localHost = scalarString(c, "SELECT @@hostname");
            // 本机的 uuid 出现在 /health/db 响应里 → 后端连的就是本机库 → 对照失败
            boolean backendIsLocal = localUuid != null && healthBody != null
                    && healthBody.contains("\"serverUuid\":\"" + localUuid + "\"");
            boolean p7 = !backendIsLocal;
            pre.put("P7", (p7 ? "PASS" : "FAIL")
                    + " 本机(" + ncHost + ") server_uuid=" + localUuid + " / hostname=" + localHost
                    + " → 后端报的是本机库=" + backendIsLocal
                    + (p7 ? "（两库可区分）" : "（后端连到了开发机上的同名库，TD-13）"));
            if (!p7) {
                System.err.println("后端连的就是本机的开发库 —— 目标库与开发库未能区分。");
            }
            return p7;
        } catch (SQLException e) {
            pre.put("P7", "SKIP 本机库不可达或本机无同名库，阴性对照跳过（不阻断）: " + e.getMessage());
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
