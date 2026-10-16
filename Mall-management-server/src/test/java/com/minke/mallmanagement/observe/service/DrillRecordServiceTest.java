package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.observe.dao.DrillRecordDao;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DrillRecordService}：**跑完落库**与**读回来拆字段**两条边（库用替身，不起 Spring）。
 *
 * <p>⚠️ 本类测的是「长文本 → 分字段」与「口令与上限」——这两条是**契约**（方案 §1.5：后端解析后
 * 分字段给前端，不是为了展示原始长文本）。真正的演练不在这里测（它要真发 HTTP）。
 */
class DrillRecordServiceTest {

    private final DrillRecordDao dao = mock(DrillRecordDao.class);
    private final AttackEngine engine = mock(AttackEngine.class);
    /**
     * ⚠️ 3a.1（乙案）起本类**不再持防护门**（D 段那两个「写入 / 复位的**时机**」用例随之删除，
     * 见下面「二·补」那节的说明）。
     */
    private final DrillRecordService service = new DrillRecordService(dao, engine, new ObjectMapper());

    /** 一行 {@code drill_record}（列名与**顺序**都与 {@code DrillRecordDao.COLUMNS} 一致）。 */
    private static Map<String, Object> row(String result, String scenario) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Id", 7L);
        r.put("OperatorId", "c-1");
        r.put("OperatorRole", 1);
        r.put("Name", "自定义");
        r.put("Scenario", scenario);
        r.put("TotalRequests", 100);
        r.put("BlockedRequests", 30);
        r.put("FailedRequests", 1);
        r.put("DurationMs", 60000L);
        r.put("Result", result);
        r.put("CreatedAt", "2026-10-13 20:00:00");
        // 块 7 收尾：回放的入口键（用户 2026-10-15 裁「甲」）—— 顺序与 `DrillRecordDao.COLUMNS` 一致（尾部）
        r.put("BatchId", "b-1");
        return r;
    }

    // ==================== 一、读回来：拆字段 ====================

    /** 正常行：{@code Result} / {@code Scenario} 都变成**对象**（不是长文本）。 */
    @Test
    void splitsLongTextColumnsIntoObjects() {
        when(dao.selectRecords(anyInt(), anyInt())).thenReturn(List.of(
                row("{\"Scenarios\":[{\"Key\":\"seckill\"}]}", "{\"c\":100,\"d\":60}")));
        when(dao.countRecords()).thenReturn(1L);

        Map<String, Object> out = service.records(null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("list");
        assertEquals(1, list.size());
        assertTrue(list.get(0).get("Result") instanceof Map, "Result 必须是对象");
        assertTrue(list.get(0).get("Scenario") instanceof Map, "Scenario 必须是对象");
        assertFalse(list.get(0).containsKey("ResultRaw"), "解析成功不该留原串");
        // 列顺序保序（前端表格列顺序靠它稳定）
        // ⚠️ `BatchId` 在 `CreatedAt` 之后、`Result` 之前：前者是 COLUMNS 的追加位，后者是
        //    `split()` 把 Result 摘下来最后再放回去的结果（块 7 收尾补 BatchId 时才显出来）。
        assertEquals(List.of("Id", "OperatorId", "OperatorRole", "Name", "Scenario",
                        "TotalRequests", "BlockedRequests", "FailedRequests", "DurationMs",
                        "CreatedAt", "BatchId", "Result"),
                List.copyOf(list.get(0).keySet()));
        // ⚠️ 这一个键是**回放能不能用**的全部：没有它，页面那个〔回放〕按钮无从知道该回放哪一批
        //    （块 7 收尾实测：它此前**没有**出现在任何响应里 —— 库里两处都有这个值）。
        assertEquals("b-1", list.get(0).get("BatchId"));
        assertEquals(1L, out.get("total"));
        assertEquals(DrillRecordService.DEFAULT_LIMIT, out.get("limit"));
        assertEquals(0, out.get("offset"));
    }

    /** 坏行：解析失败 ⇒ {@code Result} 为 null + 原串挂 {@code ResultRaw} + 标错（**不静默**）。 */
    @Test
    void keepsRawTextWhenResultIsNotJson() {
        when(dao.selectRecords(anyInt(), anyInt())).thenReturn(List.of(
                row("这不是 JSON", "")));
        when(dao.countRecords()).thenReturn(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list =
                (List<Map<String, Object>>) service.records(null, null).get("list");

        assertNull(list.get(0).get("Result"));
        assertEquals("这不是 JSON", list.get(0).get("ResultRaw"));
        assertNotNull(list.get(0).get("ResultParseError"));
        // 空的 Scenario 列不算「坏」：null 直接回 null，不落 Raw（否则每一行都会挂一个解析错误）
        assertNull(list.get(0).get("Scenario"));
        assertFalse(list.get(0).containsKey("ScenarioRaw"));
    }

    /** 口令与上限：缺省 20、超上限收到 200、负数 offset 归 0、非整数报 400（抛）。 */
    @Test
    void clampsLimitAndOffset() {
        when(dao.selectRecords(anyInt(), anyInt())).thenReturn(List.of());
        when(dao.countRecords()).thenReturn(0L);

        Map<String, Object> out = service.records("9999", "-5");
        verify(dao).selectRecords(eq(DrillRecordService.MAX_LIMIT), eq(0));
        assertEquals(DrillRecordService.MAX_LIMIT, out.get("limit"));
        assertEquals(0, out.get("offset"));

        Map<String, Object> def = service.records("0", null);
        verify(dao).selectRecords(eq(DrillRecordService.DEFAULT_LIMIT), eq(0));
        assertEquals(DrillRecordService.DEFAULT_LIMIT, def.get("limit"));

        // ⚠️ 形状错（不是整数）走 400 —— 与「数值越界收进区间」是两条不同的路
        assertThrows(IllegalArgumentException.class, () -> service.records("abc", null));
    }

    // ==================== 二、跑一轮：落库 + 回体 ====================

    /** 跑完 ⇒ 落一行并把那一轮的字段化结果**同时**回给调用方（前端不必再拉一次列表）。 */
    @Test
    void runPersistsRowAndEchoesResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("Scenarios", List.of(Map.of("Key", "seckill")));
        when(engine.run(any(), any())).thenReturn(new AttackEngine.DrillRun("自定义", "{\"c\":100}",
                100, 30, 1, 2, 3, 60000L, result, "batch-8c"));
        when(dao.insert(anyString(), anyInt(), anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                anyLong(), anyString(), anyString())).thenReturn(7L);

        Map<String, Object> out = service.run("自定义", null, null, null, null, "c-1", 1,
                "http://localhost:8088");

        assertEquals(7L, out.get("Id"));
        assertEquals(100, out.get("TotalRequests"));
        assertEquals(30, out.get("BlockedRequests"));
        assertEquals(1, out.get("FailedRequests"));
        // 甲的两个数**单列**（不进上面三档）：背压弃投 2 / 被取消 3
        assertEquals(2, out.get("ShedRequests"));
        assertEquals(3, out.get("AbandonedRequests"));
        assertEquals(60000L, out.get("DurationMs"));
        assertEquals(result, out.get("Result"));
        assertTrue(Boolean.TRUE.equals(out.get("success")));
        // 落库那一行的 Result 是**序列化后的文本**（列是 mediumtext）
        verify(dao).insert(eq("c-1"), eq(1), eq("自定义"), eq("{\"c\":100}"), eq(100), eq(30), eq(1),
                eq(60000L), anyString(), eq("batch-8c"));
    }

    /**
     * ⚠️ {@code scenarios} 到服务层还是**表单里的 JSON 数组串**（端点收 {@code @RequestParam}）：
     * 本类负责把它解成结构再交给引擎（{@code AttackEngine.parse} 只认结构），数值参数**保持字符串**
     * —— 「必须是整数」那句话由引擎的 {@code clampInt} 说（那里是唯一校验出口）。
     *
     * <p>⚠️ 3a.1（乙案）起丁的两个开关**不再走这条路**：它们的传输形状（JSON 数组串 → 结构）
     * 搬去了 {@code AttackSwitchConfig.updateFromJson}，本类一个字都不碰（见类注释那节）。
     */
    @Test
    void decodesScenariosJsonStringBeforeHandingToTheEngine() {
        when(engine.run(any(), any())).thenReturn(new AttackEngine.DrillRun("压测", "{}", 0, 0, 0, 0, 0,
                0L, Map.of(), "batch-8c"));

        service.run("压测", "200", "30", "400",
                "[{\"key\":\"seckill\",\"weight\":80},{\"key\":\"cacheCold\",\"weight\":20}]",
                "c-1", 1, "http://localhost:8088");

        // 空白 scenarios = 没勾 = **null**（引擎按全 8 个场景跑），不是空数组（空数组是「一个都没勾」⇒ 抛）
        service.run(null, null, null, null, "  ", "c-1", 1, "http://localhost:8088");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(engine, times(2)).run(captor.capture(), eq("http://localhost:8088"));
        Map<String, Object> body = captor.getAllValues().get(0);
        assertEquals("压测", body.get("name"));
        assertEquals("200", body.get("concurrency"), "数值保持字符串，校验归引擎");
        assertEquals("30", body.get("durationSeconds"));
        assertEquals("400", body.get("targetQps"), "甲的目标 QPS 同样保持字符串");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) body.get("scenarios");
        assertEquals(2, scenarios.size());
        assertEquals("seckill", scenarios.get(0).get("key"));
        assertEquals(80, scenarios.get(0).get("weight"));
        assertNull(captor.getAllValues().get(1).get("scenarios"));

        // ⚠️ 串不是 JSON 数组 ⇒ 抛（400）。**不**回落到「全 8 个场景」：那会让页面显示一串没人勾的场景
        assertThrows(IllegalArgumentException.class, () -> service.run(null, null, null, null,
                "seckill", "c-1", 1, "http://localhost:8088"));
    }

    /**
     * ⚠️ 报文里**不许**再出现那两个开关键 —— 它们是引擎明确拒收的（{@code AttackEngine.parse}
     * 见到就抛 {@code PARAMS_MOVED}）。本用例是这条契约在**服务层**的哨兵：谁把
     * {@code body.put("protections", ...)} 加回来，这里立刻红，而不是等到某次真演练被引擎拒掉。
     *
     * <p>⚠️ 本类测不到的那一半（如实登记）：{@code AttackEngine} 是替身，所以「引擎收到这个报文会
     * 不会抛」不在本类的证明范围内 —— 那是 {@code AttackEngineTest.oldSwitchParamsAreRejectedNotIgnored}
     * 的活。两条合起来才是完整的。
     */
    @Test
    void runHandsTheEngineABodyWithoutTheSwitchKeys() {
        when(engine.run(any(), any())).thenReturn(new AttackEngine.DrillRun("压测", "{}", 0, 0, 0, 0, 0,
                0L, Map.of(), "batch-8c"));

        service.run("压测", null, null, null, null, "c-1", 1, "http://localhost:8088");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(engine).run(captor.capture(), eq("http://localhost:8088"));
        Map<String, Object> body = captor.getValue();
        assertFalse(body.containsKey("protections"), "开关不再是报文的一部分（乙案）：" + body.keySet());
        assertFalse(body.containsKey("injections"), "开关不再是报文的一部分（乙案）：" + body.keySet());
    }

    // ==================== 二·补、3a.1：那两个「时机」用例去哪了 ====================
    //
    // D 段（commit 2）在这里还钉过两条：`appliesGateBeforeRunningAndResetsItEvenWhenTheEngineThrows`
    // 与 `missingProtectionsMeansAllOnNotAllOff`。它们测的是「写入必须早于 engine.run」「复位必须
    // 落在 finally」以及「没给 protections ⇒ 全开」。3a.1（乙案）之后这三件事**都不在本类的职责里**：
    //   · 本类不再持 ProtectionGate、不再收那两个形参、也不再 apply/reset（见类注释那节）；
    //   · 「没给 = 全开」这条换算搬去了 AttackSwitchConfig（缺省 = KEYS 全开）；
    //   · 「跑完不复位」是**新裁定**（用户 2026-10-16）—— 于是「复位时机」这个被测对象本身没了。
    // ⇒ 两条用例整体删除，**不是**因为不再需要，而是因为它们要测的东西已经不在这个类里。
    //    一个必须如实说的后果：**「跑完不复位」在本类里没有替代用例**。它的哨兵只能是
    //    AttackSwitchConfigTest（配置面自己不自动复位）+ 一次真演练（阶段 4 手验）——
    //    因为「谁调了 resetToDefault」在替身引擎下根本观察不到。

    /** [暂停]：一层转发（令旗在引擎里）—— 后端只要如实把引擎答的 true/false 返回去。 */
    @Test
    void stopIsForwardedToTheEngine() {
        when(engine.stop()).thenReturn(true);
        assertTrue(service.stop());

        when(engine.stop()).thenReturn(false);
        assertFalse(service.stop());
        verify(engine, times(2)).stop();
    }

    /** 场景清单透传（控制器那条端点只做一层转发）。 */
    @Test
    void scenariosComeFromTheEngine() {
        when(engine.scenarios()).thenReturn(List.of(Map.of("Key", "seckill")));
        Map<String, Object> out = service.scenarios();
        assertTrue(Boolean.TRUE.equals(out.get("success")));
        assertEquals(1, ((List<?>) out.get("list")).size());
    }
}
