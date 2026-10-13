package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.observe.dao.DrillTargetDao;
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
 * {@link AttackEngine} 的三处**纯函数**（不碰库、不发网络、不起 Spring）：
 * 分档 {@link AttackEngine#classify} / 参数校验 {@link AttackEngine#parse} /
 * 权重→线程 {@link AttackEngine#threadPlan}（+ 配置 JSON 与场景清单两条边）。
 *
 * <p>⚠️ 为什么只测这三处：其余部分（{@code fireOnce} 的八个分支）**必须真发 HTTP** 才有意义
 * —— 拿假 HttpClient 测「我拼的字符串等于我以为的字符串」是自证，价值接近零。那八个分支的
 * 真判据在**演练本身**（跑一轮看 Result），不在单测里。三处分档/分配函数则相反：它们错了
 * 会让**整套读数**一起错，且不跑网络就能钉死。
 */
class AttackEngineTest {

    private final AttackEngine engine = new AttackEngine(
            mock(DrillTargetDao.class), mock(L2HealthProbe.class), new ObjectMapper());

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
        AttackEngine.Config cfg = AttackEngine.parse(Map.of());
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
        AttackEngine.Config cfg = AttackEngine.parse(Map.of("scenarios", List.of(
                Map.of("key", "seckill", "weight", 80),
                Map.of("key", "cacheCold", "weight", 0))));
        assertEquals(1, cfg.chosen().size());
        assertEquals("seckill", cfg.chosen().get(0).key());
    }

    /** 同一个 key 写两次 ⇒ 取后者（不许变成两份流量）。 */
    @Test
    void duplicateKeysKeepTheLastOne() {
        AttackEngine.Config cfg = AttackEngine.parse(Map.of("scenarios", List.of(
                Map.of("key", "seckill", "weight", 10),
                Map.of("key", "seckill", "weight", 30))));
        assertEquals(1, cfg.chosen().size());
        assertEquals(30, cfg.chosen().get(0).weight());
    }

    /** 非法入参一律抛（控制器转 400）—— 逐条钉住，端点是可以被 curl 直接打的。 */
    @Test
    void rejectsInvalidInput() {
        // 未知 key
        assertThrows(IllegalArgumentException.class, () -> AttackEngine.parse(
                Map.of("scenarios", List.of(Map.of("key", "nope", "weight", 10)))));
        // 全 0 权重 = 没勾任何场景
        assertThrows(IllegalArgumentException.class, () -> AttackEngine.parse(
                Map.of("scenarios", List.of(Map.of("key", "seckill", "weight", 0)))));
        // scenarios 不是数组
        assertThrows(IllegalArgumentException.class,
                () -> AttackEngine.parse(Map.of("scenarios", "seckill")));
        // 并发数越界（0 / 501）
        assertThrows(IllegalArgumentException.class,
                () -> AttackEngine.parse(Map.of("concurrency", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> AttackEngine.parse(Map.of("concurrency", 501)));
        // 持续秒数越界
        assertThrows(IllegalArgumentException.class,
                () -> AttackEngine.parse(Map.of("durationSeconds", 601)));
        // 权重是负数
        assertThrows(IllegalArgumentException.class, () -> AttackEngine.parse(
                Map.of("scenarios", List.of(Map.of("key", "seckill", "weight", -1)))));
        // ⚠️「每个勾选的场景都要有流量」⇒ 并发数不够分就直接拒，不悄悄少跑几个
        assertThrows(IllegalArgumentException.class, () -> AttackEngine.parse(
                Map.of("concurrency", 2)));
    }

    /** 名字超 64 字符按列宽截断（`drill_record.Name` 是 varchar(64)）。 */
    @Test
    void longNameIsTruncatedToColumnWidth() {
        AttackEngine.Config cfg = AttackEngine.parse(Map.of("name", "场".repeat(100)));
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
        String json = AttackEngine.buildScenarioJson(AttackEngine.parse(Map.of()));
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
        AttackEngine.Config cfg = AttackEngine.parse(body);
        assertEquals(8, cfg.chosen().size());
        List<String> plan = AttackEngine.threadPlan(cfg.chosen(), 10);
        assertEquals(10, plan.size());
    }
}
