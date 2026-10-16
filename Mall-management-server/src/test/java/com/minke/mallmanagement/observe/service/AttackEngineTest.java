package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.observe.dao.DrillTargetDao;
import com.minke.mallmanagement.protect.ProtectionGate;
import com.minke.mallmanagement.shared.cache.L2HealthProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link AttackEngine} 的四处**不碰库、不发网络、不起 Spring** 的面：
 * 分档 {@link AttackEngine#classify} / 参数校验 {@link AttackEngine#parse} /
 * 权重→线程 {@link AttackEngine#threadPlan}（+ 配置 JSON 与场景清单两条边）/ 空闲态 {@link AttackEngine#progress}。
 *
 * <p>⚠️ 为什么只测这三处：其余部分（{@code fireOnce} 的八个分支）**必须真发 HTTP** 才有意义
 * —— 拿假 HttpClient 测「我拼的字符串等于我以为的字符串」是自证，价值接近零。那八个分支的
 * 真判据在**演练本身**（跑一轮看 Result），不在单测里。三处分档/分配函数则相反：它们错了
 * 会让**整套读数**一起错，且不跑网络就能钉死。
 */
class AttackEngineTest {

    /**
     * 开关配置用**真件**（3a.1 乙案）：它是无依赖、无 I/O 的一份进程内状态，而本类的开关用例
     * 要测的恰恰是「引擎从它读到的是什么」——换成替身就把要测的东西替掉了。
     * ⚠️ 防护那 5 个位真值在 {@link ProtectionGate}（本类不复制，见 {@code AttackSwitchConfig} 类注释）。
     */
    private final ProtectionGate gate = new ProtectionGate();
    private final AttackSwitchConfig switchConfig = new AttackSwitchConfig(gate, new ObjectMapper());

    private final AttackEngine engine = new AttackEngine(
            mock(DrillTargetDao.class), mock(L2HealthProbe.class), new ObjectMapper(), switchConfig);

    // ==================== 一、分档（口径见 AttackEngine 类注释三）====================

    /** 401 / 403 = 闸拦下（封禁闸、身份守卫）。 */
    @Test
    void gateStatusesCountAsBlocked() {
        assertEquals(AttackEngine.Outcome.BLOCKED, engine.classify(401, "{}"));
        assertEquals(AttackEngine.Outcome.BLOCKED, engine.classify(403, "{\"success\":false}"));
    }

    /** ⚠️ 本仓最重要的一条：**业务失败是 200 + success:false**，只看状态码会一条防护都数不到。 */
    @Test
    void businessFailureIsBlockedEvenThoughStatusIsTwoHundred() {
        assertEquals(AttackEngine.Outcome.BLOCKED,
                engine.classify(200, "{\"success\":false,\"msg\":\"该商家已暂停接单\"}"));
    }

    /** 200 + success:true = 真放行。 */
    @Test
    void successIsPassed() {
        assertEquals(AttackEngine.Outcome.PASSED, engine.classify(200, "{\"success\":true}"));
    }

    /** 5xx / 404 = 真失败（404 归这里：路径写错是引擎自己的 bug，算「被拦下」= 用自己的错误刷高防护数）。 */
    @Test
    void serverErrorAndNotFoundCountAsFailed() {
        assertEquals(AttackEngine.Outcome.FAILED, engine.classify(500, ""));
        assertEquals(AttackEngine.Outcome.FAILED, engine.classify(503, "boom"));
        assertEquals(AttackEngine.Outcome.FAILED, engine.classify(404, "{\"success\":false}"));
    }

    /** 其余 4xx（入参形状被拒）= 拦下。 */
    @Test
    void otherClientErrorsCountAsBlocked() {
        assertEquals(AttackEngine.Outcome.BLOCKED, engine.classify(400, "{\"success\":false}"));
    }

    /** 响应体不是本仓形状（非 JSON / 没有 success 键 / 空）= 真失败，**不**静默算通过。 */
    @Test
    void unreadableBodyCountsAsFailed() {
        assertEquals(AttackEngine.Outcome.FAILED, engine.classify(200, "not json at all"));
        assertEquals(AttackEngine.Outcome.FAILED, engine.classify(200, "{\"data\":1}"));
        assertEquals(AttackEngine.Outcome.FAILED, engine.classify(200, ""));
        assertEquals(AttackEngine.Outcome.FAILED, engine.classify(200, null));
    }

    // ==================== 二、参数校验与缺省 ====================

    /** 空报文 = 全 8 个场景按缺省权重、并发 100 / 时长 60、名字「自定义」。 */
    @Test
    void emptyBodyFallsBackToAllEightScenarios() {
        AttackEngine.Config cfg = engine.parse(Map.of());
        assertEquals(100, cfg.concurrency());
        assertEquals(60, cfg.durationSeconds());
        assertEquals("自定义", cfg.name());
        assertEquals(8, cfg.chosen().size());
        int sum = cfg.chosen().stream().mapToInt(AttackEngine.Chosen::weight).sum();
        assertEquals(100, sum, "缺省权重合计必须是 100（《块7参考》§1.2 的界面分布）");
    }

    /** 勾选与权重按报文走；权重为 0 的场景**等于没勾**（不是「跑 0 个线程」）。 */
    @Test
    void zerosAreTreatedAsNotSelected() {
        AttackEngine.Config cfg = engine.parse(Map.of("scenarios", List.of(
                Map.of("key", "seckill", "weight", 80),
                Map.of("key", "cacheCold", "weight", 0))));
        assertEquals(1, cfg.chosen().size());
        assertEquals("seckill", cfg.chosen().get(0).key());
    }

    /** 同一个 key 写两次 ⇒ 取后者（不许变成两份流量）。 */
    @Test
    void duplicateKeysKeepTheLastOne() {
        AttackEngine.Config cfg = engine.parse(Map.of("scenarios", List.of(
                Map.of("key", "seckill", "weight", 10),
                Map.of("key", "seckill", "weight", 30))));
        assertEquals(1, cfg.chosen().size());
        assertEquals(30, cfg.chosen().get(0).weight());
    }

    /** 非法入参一律抛（控制器转 400）—— 逐条钉住，端点是可以被 curl 直接打的。 */
    @Test
    void rejectsInvalidInput() {
        // 未知 key
        assertThrows(IllegalArgumentException.class, () -> engine.parse(
                Map.of("scenarios", List.of(Map.of("key", "nope", "weight", 10)))));
        // 全 0 权重 = 没勾任何场景
        assertThrows(IllegalArgumentException.class, () -> engine.parse(
                Map.of("scenarios", List.of(Map.of("key", "seckill", "weight", 0)))));
        // scenarios 不是数组
        assertThrows(IllegalArgumentException.class,
                () -> engine.parse(Map.of("scenarios", "seckill")));
        // 并发数越界（0 / 4001）—— 上限 2026-10-15 由 500 抬到 4000（10c-2 提在途量做瓶颈定位）
        assertThrows(IllegalArgumentException.class,
                () -> engine.parse(Map.of("concurrency", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> engine.parse(Map.of("concurrency", 4001)));
        // 持续秒数越界
        assertThrows(IllegalArgumentException.class,
                () -> engine.parse(Map.of("durationSeconds", 601)));
        // 目标 QPS 越界（负数 / 天文数字）—— 3a.1 起从「开关」那组用例搬来这里（那组已随乙案删掉，
        // 但这条**与开关无关**，是报文数值校验，不能跟着一起丢）
        assertThrows(IllegalArgumentException.class, () -> engine.parse(
                Map.of("targetQps", -1)));
        assertThrows(IllegalArgumentException.class, () -> engine.parse(
                Map.of("targetQps", AttackEngine.MAX_TARGET_QPS + 1)));
        // 权重是负数
        assertThrows(IllegalArgumentException.class, () -> engine.parse(
                Map.of("scenarios", List.of(Map.of("key", "seckill", "weight", -1)))));
        // ⚠️「每个勾选的场景都要有流量」⇒ 并发数不够分就直接拒，不悄悄少跑几个
        assertThrows(IllegalArgumentException.class, () -> engine.parse(
                Map.of("concurrency", 2)));
    }

    /** 名字超 64 字符按列宽截断（`drill_record.Name` 是 varchar(64)）。 */
    @Test
    void longNameIsTruncatedToColumnWidth() {
        AttackEngine.Config cfg = engine.parse(Map.of("name", "场".repeat(100)));
        assertEquals(AttackEngine.NAME_MAX, cfg.name().length());
    }

    // ==================== 三、权重 → 线程计划 ====================

    /** 返回值长度**恒等于**并发数（每个线程恰好跑一个场景）。 */
    @Test
    void planLengthAlwaysEqualsConcurrency() {
        List<AttackEngine.Chosen> chosen = List.of(new AttackEngine.Chosen("seckill", 80),
                new AttackEngine.Chosen("cacheCold", 20));
        assertEquals(100, AttackEngine.threadPlan(chosen, 100).size());
        assertEquals(7, AttackEngine.threadPlan(chosen, 7).size());
    }

    /**
     * ⚠️ 本方法存在的**全部理由**：混合权重下不允许出现「勾了却 0 个线程」。
     * 这一组（并发 8、权重 4/4/1×6）正是「按比例四舍五入 + 给最大的找平」那种写法会归零的形状。
     */
    @Test
    void everySelectedScenarioGetsAtLeastOneThread() {
        List<AttackEngine.Chosen> chosen = new ArrayList<>();
        chosen.add(new AttackEngine.Chosen("seckill", 4));
        chosen.add(new AttackEngine.Chosen("orderBurst", 4));
        for (int i = 0; i < 6; i++) {
            chosen.add(new AttackEngine.Chosen("s" + i, 1));
        }
        List<String> plan = AttackEngine.threadPlan(chosen, 8);
        assertEquals(8, plan.size());
        for (AttackEngine.Chosen c : chosen) {
            assertTrue(plan.contains(c.key()), "勾选了的场景必须有线程：" + c.key());
        }
    }

    /**
     * 权重与线程数成正比（大权重不被小权重挤掉）。算一遍这次分配好让下面两条断言不是猜的：
     * 并发 100 / 两场景 ⇒ 保底 1+1、余量 98。{@code 98×75/100 = 73.5 → 73}、
     * {@code 98×25/100 = 24.5 → 24}，用掉 97 ⇒ 剩 1 个；两个余数都是 0.5 ⇒ 平手 ⇒ 权重大的先得。
     * 故 seckill = 73+1(保底)+1(余数) = 75、cacheCold = 24+1(保底) = 25。
     */
    @Test
    void threadsFollowWeights() {
        List<AttackEngine.Chosen> chosen = List.of(new AttackEngine.Chosen("seckill", 75),
                new AttackEngine.Chosen("cacheCold", 25));
        List<String> plan = AttackEngine.threadPlan(chosen, 100);
        assertEquals(75, plan.stream().filter("seckill"::equals).count());
        assertEquals(25, plan.stream().filter("cacheCold"::equals).count());
    }

    /** 场景数多于并发数是调用方的错（{@link AttackEngine#parse} 已拦；这里再钉一次）。 */
    @Test
    void planRejectsConcurrencyBelowScenarioCount() {
        List<AttackEngine.Chosen> chosen = List.of(new AttackEngine.Chosen("a", 1),
                new AttackEngine.Chosen("b", 1));
        assertThrows(IllegalArgumentException.class, () -> AttackEngine.threadPlan(chosen, 1));
    }

    // ==================== 四、配置 JSON 与场景清单 ====================

    /** 配置 JSON 必须落在 {@code varchar(500)} 之内（勾满 8 个场景是最长的一档）。 */
    @Test
    void scenarioJsonFitsTheColumn() {
        String json = AttackEngine.buildScenarioJson(engine.parse(Map.of()));
        assertTrue(json.length() <= AttackEngine.SCENARIO_JSON_MAX, "实际长度 " + json.length());
        for (String key : List.of("seckill", "orderBurst", "repeatSubmit", "unpaidTimeout",
                "cacheCold", "redisDown", "bannedUser", "sensitiveWord")) {
            assertTrue(json.contains(key), "配置 JSON 里少了 " + key);
        }
        assertFalse(json.contains("…"), "8 个场景不该触发截断");
    }

    /** 场景清单：8 条、key 稳定有序、缺省权重合计 100、每条都写了「同时演示的防护」与「人工前提」。 */
    @Test
    void scenarioListIsStable() {
        List<Map<String, Object>> list = engine.scenarios();
        assertEquals(8, list.size());
        List<String> keys = new ArrayList<>();
        int sum = 0;
        for (Map<String, Object> m : list) {
            keys.add((String) m.get("Key"));
            sum += (Integer) m.get("DefaultWeight");
            assertFalse(((String) m.get("Protections")).isBlank());
            assertFalse(((String) m.get("Needs")).isBlank());
            assertFalse(((String) m.get("Name")).isBlank());
        }
        assertEquals(List.of("seckill", "orderBurst", "repeatSubmit", "unpaidTimeout", "cacheCold",
                "redisDown", "bannedUser", "sensitiveWord"), keys);
        assertEquals(100, sum);
    }

    /** 报文用的场景 key 与 {@code INDEX} 对得上（写错 key = 那条分支永远选不中）。 */
    @Test
    void everyScenarioKeyIsAddressable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("concurrency", 10);
        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : List.of("seckill", "orderBurst", "repeatSubmit", "unpaidTimeout",
                "cacheCold", "redisDown", "bannedUser", "sensitiveWord")) {
            items.add(Map.of("key", key, "weight", 1));
        }
        body.put("scenarios", items);
        AttackEngine.Config cfg = engine.parse(body);
        assertEquals(8, cfg.chosen().size());
        List<String> plan = AttackEngine.threadPlan(cfg.chosen(), 10);
        assertEquals(10, plan.size());
    }

    // ==================== 五、甲：限速器（Pacer）====================

    /**
     * 「拉满」档 = 不限速：{@code reserve()} 恒 0（于是引擎的实际上限交给在途许可，
     * 读出来的 QPS 是这台机器＋后端真实的上限，不是我们设的一个数）。
     */
    @Test
    void unlimitedPacerNeverWaits() {
        AttackEngine.Pacer pacer = new AttackEngine.Pacer(0);
        assertFalse(pacer.limited());
        for (int i = 0; i < 100; i++) {
            assertEquals(0L, pacer.reserve());
        }
        // 负数也当不限速（parse 会先把负的挡掉，但构造器自己也得是安全的）
        assertFalse(new AttackEngine.Pacer(-1).limited());
    }

    /**
     * 限速档**真的隔开**：第二个空位要等**约一个间隔**（这是「120 与 400 不再打出来是同一个数」的
     * 全部依据），而且这个间隔**跟着目标 QPS 走**（不是一个写死的常数）。
     *
     * <h2>【⚠️ 为什么间隔取**秒级** —— 一次红换来的】</h2>
     * 本测试量的是**墙钟**，而「取第一个空位」这个窗口里必然含两笔**一次性**开销
     * （本机 surefire 的 JVM 里各测 3 次，原值记在下面）：
     * <ul>
     *   <li>首次 {@code reserve()} 自身 —— 它的 lambda 调用点要做 {@code invokedynamic} 引导：
     *       <b>0.44 / 0.57 / 0.80 ms</b>，之后同一句只剩 <b>~2µs</b>；</li>
     *   <li>紧随其后的首次 {@code assertEquals(long,long,String)} —— 首调同样付类加载：
     *       <b>0.47 / 0.52 / 0.73 ms</b>，第二次 2–26µs、第三次 &lt;3µs。</li>
     * </ul>
     * 两者相加 = <b>0.91 / 1.09 / 1.53 ms</b>，而原来的间隔是 1ms ⇒ 窗口与间隔**同量级**，
     * 第二格经常被整段吃掉而退化成 {@code 0}：整仓跑红过一次，把那句单独跑 3 次则 **3 次全红**。
     * ⚠️ 裸 JVM 里试不出来（冷态窗口 0.53–0.90ms，8 次全绿）—— 探针见
     * {@code _work/b10c-pacer-probe.java}。⇒ 间隔放大到**秒级**后，那几笔一次性开销只剩千分之一。
     */
    @Test
    void limitedPacerHandsOutSpacedSlots() {
        AttackEngine.Pacer one = new AttackEngine.Pacer(1);          // 间隔 1 秒
        assertTrue(one.limited());
        assertEquals(0L, one.reserve(), "第一个空位就是当下（空闲期不该先欠一笔）");

        long second = one.reserve();
        assertTrue(second > 100_000_000L,
                "第二个空位要等**约一个间隔**（1s），实际 " + second + "ns —— 0 附近就等于没限速");
        assertTrue(second <= 1_000_000_000L,
                "等的是**一个间隔**的量级，不该超出一个间隔，实际 " + second + "ns");

        // 间隔跟着 QPS 走：10 QPS ⇒ 100ms（写死成一个常数的话，120 与 400 会打出同一个数）
        AttackEngine.Pacer ten = new AttackEngine.Pacer(10);
        ten.reserve();                                               // 吃掉第一个空位（上面那两笔一次性开销这时已付过）
        long gapped = ten.reserve();
        assertTrue(gapped > 10_000_000L && gapped <= 100_000_000L,
                "10 QPS 的第二个空位该等约 100ms，实际 " + gapped + "ns");
    }

    // ==================== 六、甲：失败记账环（ReqLog）====================

    /** ⚠️ 本环存在的全部意义：**只记非 PASSED**（全量记录会把 Result 长文本打肥）。 */
    @Test
    void reqLogDropsPassedAndKeepsTheRest() {
        AttackEngine.ReqLog log = new AttackEngine.ReqLog();
        log.record("b-seckill-1", "PASSED", 200, 3);
        log.record("b-seckill-2", "BLOCKED", 403, 4);
        log.record("b-seckill-3", "FAILED", 500, 5);
        log.record("b-seckill-4", "ABANDONED", 0, 900);

        assertEquals(3, log.count(), "PASSED 不入账");
        List<Map<String, Object>> snap = log.snapshot();
        assertEquals(3, snap.size());
        assertTrue(snap.stream().noneMatch(m -> "PASSED".equals(m.get("Outcome"))));
        assertEquals(403, snap.get(0).get("Status"));
        assertEquals(900L, snap.get(2).get("Millis"));
    }

    /** 环满**覆盖最旧**、不炸、不阻塞（同事件环的拒收策略），而「累计发生过多少条」照样数得清。 */
    @Test
    void reqLogWrapsAroundWithoutBlowingUp() {
        AttackEngine.ReqLog log = new AttackEngine.ReqLog();
        int over = AttackEngine.REQ_LOG_CAPACITY + 7;
        for (int i = 0; i < over; i++) {
            log.record("b-cacheCold-" + i, "FAILED", 500, i);
        }
        assertEquals(over, log.count(), "累计条数是「发生过多少条」，不封顶");
        assertEquals(AttackEngine.REQ_LOG_CAPACITY, log.snapshot().size(), "快照封顶在环容量");
    }

    // ==================== 七、丁：开关（乙案 —— 读共享配置 + 拒收旧入参）====================

    /**
     * 引擎的开关**不再来自报文**，而是来自共享配置（{@code AttackSwitchConfig}）。
     *
     * <p>⚠️ 本用例钉的是**接线的方向**：改了配置面 ⇒ 引擎下一次 {@code parse} 读到的是新的那份。
     * 缺省是「防护全开（5 个）、注入全关（0 个）」—— 用户 2026-10-14 定稿的面板缺省，
     * 而 3a.1 起那个缺省的真值在 {@code AttackSwitchConfig} / {@code ProtectionGate} 里。
     */
    @Test
    void switchesComeFromTheSharedConfigNotFromTheBody() {
        AttackEngine.Config before = engine.parse(Map.of());
        assertEquals(AttackEngine.PROTECTION_KEYS, before.protections());
        assertEquals(5, before.protections().size());
        assertTrue(before.injections().isEmpty());
        assertEquals(0, before.targetQps(), "缺省不限速 = 加 targetQps 之前的行为");

        // ⚠️ 写配置面（模拟「日志页拨了一枚开关」）⇒ 引擎起跑那一刻的快照跟着变
        switchConfig.update(List.of("rateLimit"), List.of("redisDown"));
        AttackEngine.Config after = engine.parse(Map.of());
        assertEquals(List.of("rateLimit"), after.protections());
        assertEquals(List.of("redisDown"), after.injections());

        // 关光也是一个**有意义的状态**（对照轮），且与「没给」在配置面上是两件事：
        // 这里 `[]` 是整体替换该项 ⇒ 落到 Config 上就是空清单。
        switchConfig.update(List.of(), null);
        assertTrue(engine.parse(Map.of()).protections().isEmpty(), "显式空清单 = 一个都不开（对照轮）");
        assertEquals(List.of("redisDown"), engine.parse(Map.of()).injections(), "null = 不动注入那一项");
    }

    /**
     * ⚠️ 报文里若仍带 {@code protections} / {@code injections}，**拒收并指路**，不静默忽略。
     *
     * <p>为什么必须拒：静默忽略的表现是「我拨了开关、怎么没反应」—— 调用方以为改了这一轮的开关，
     * 而实际改的是一个**没人读的字符串**。这条契约在两层各守一次（{@code AttackController} 守
     * HTTP 参数面、这里守报文契约），本用例钉的是后者 —— 少了它，程序化调用方（测试 / 将来的 job）
     * 就能悄悄塞进来两个死键。
     */
    @Test
    void oldSwitchParamsAreRejectedNotIgnored() {
        IllegalArgumentException byProtections = assertThrows(IllegalArgumentException.class,
                () -> engine.parse(Map.of("protections", List.of("rateLimit"))));
        assertEquals(AttackSwitchConfig.PARAMS_MOVED, byProtections.getMessage(),
                "拒收必须**指路**（消息就是那份共享文案），而不是一句「不认识这个键」");

        assertThrows(IllegalArgumentException.class,
                () -> engine.parse(Map.of("injections", List.of("redisDown"))));
        // 空数组同样拒（形状对 ≠ 合法：这两个键**整个**已经从报文契约里退出去了）
        assertThrows(IllegalArgumentException.class,
                () -> engine.parse(Map.of("protections", List.of())));
        // 拒的是**这两个键**，不是整个报文：其余字段照常解析
        assertEquals("压测", engine.parse(Map.of("name", "压测")).name());
    }

    /** 配置 JSON 带上甲/丁的键（可复现性）：{@code q} 只在限速时出现，{@code p}/{@code i} 恒在。 */
    @Test
    void scenarioJsonCarriesSwitchesAndOnlyCarriesQpsWhenLimited() {
        AttackEngine.Config unlimited = engine.parse(Map.of());
        String a = AttackEngine.buildScenarioJson(unlimited);
        assertFalse(a.contains("\"q\""), "拉满档不写 q（缺省，省字符）");
        assertTrue(a.contains("\"p\":[\"rateLimit\",\"circuitBreak\",\"degrade\",\"retry\","
                + "\"poolIsolation\"]"), "防护全开要写全：" + a);
        assertTrue(a.contains("\"i\":[]"), "注入默认全关：" + a);

        // ⚠️ 3a.1 起开关从**配置面**拨（不再是报文里那两个键）⇒ 这里先写配置再序列化
        switchConfig.update(null, List.of("redisDown"));
        String b = AttackEngine.buildScenarioJson(engine.parse(Map.of("targetQps", 400)));
        assertTrue(b.contains("\"q\":400"), b);
        assertTrue(b.contains("\"i\":[\"redisDown\"]"), b);
        // 加了这两组键之后仍然塞得进 varchar(500)（8 个场景是最长的一档）
        assertTrue(b.length() <= AttackEngine.SCENARIO_JSON_MAX, "实际长度 " + b.length());
    }

    // ==================== 四、实时进度（块 7 收尾 · `/attack/status`）====================

    /**
     * 空闲态：{@code running=false} + **全零**（不是缺键、更不是抛异常）。
     *
     * <p>⚠️ 这一条钉的是**轮询的收尾那一拍**：前端 1 秒一次地打，演练跑完的下一拍必然落在
     * 「没有在跑」上 —— 这里若抛或若返回 {@code null}，面板会把「跑完了」画成一次假报错。
     */
    @Test
    void progressIsAllZeroAndNotRunningWhenIdle() {
        AttackEngine.Progress p = engine.progress();
        assertFalse(p.running(), "从没跑过 ⇒ 不在跑");
        assertEquals("", p.batchId(), "不在跑时批次号是空串（不是 null）");
        assertEquals(0L, p.elapsedMs());
        assertEquals(0, p.sent());
        assertEquals(0, p.completed());
        assertEquals(0, p.blocked());
        assertEquals(0, p.failed());
        assertEquals(0, p.shed());
        assertEquals(0, p.abandoned());
    }

    /** 反复取是幂等的、不回填陈旧值：这一次的读数不依赖上一次（字段被摘干净过）。 */
    @Test
    void progressIsStableAcrossRepeatedIdleCalls() {
        AttackEngine.Progress a = engine.progress();
        AttackEngine.Progress b = engine.progress();
        assertEquals(a, b, "同一状态下两次读数必须逐字段相同（record 的 equals）");
        assertFalse(engine.isRunning(), "只读 progress() 不该把 running 翻成 true");
    }

    /**
     * 空闲时叫停：返回 {@code false}，且**不留下任何痕迹**（{@code running} 不会被点亮、
     * {@code progress()} 也不会变成「在跑但零进度」）。
     *
     * <p>⚠️ 与上一条合起来钉的是「没在跑」这一支的三个出口（{@code stop} / {@code isRunning} /
     * {@code progress}）**互相不矛盾** —— 旧版只有前两个，第三个是本笔新加的，
     * 新加一个出口最容易出的错就是它和前两个说的不是一件事。
     *
     * <p>⚠️ <b>「真在跑」那一支本类测不了</b>：要把 {@code liveMeters} 挂上必须走
     * {@code run()}，而它**真发 HTTP**。拿假 HttpClient 测「我读到的等于我刚写进去的」是自证
     * （与本文件头那条理由同源）。那一支的判据在**真跑一轮时打一次 {@code /status}**，
     * 不在单测里。
     */
    @Test
    void stopOnIdleLeavesNoTraceInProgress() {
        assertFalse(engine.stop(), "没有在跑的轮次 ⇒ stop 如实答 false");
        assertFalse(engine.isRunning());
        assertFalse(engine.progress().running(), "叫停失败不该把 progress 说成在跑");
        assertEquals(0, engine.progress().sent());
    }
}
