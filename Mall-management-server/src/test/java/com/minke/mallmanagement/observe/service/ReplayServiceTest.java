package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.dao.ObservedEventDao;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReplayService}：**入参口径**与**信封形状**两条边（库用替身，不起 Spring、不出 SQL）。
 *
 * <p>⚠️ 本类**测不到**的两件事，别以为它们被盖住了：
 * <ol>
 *   <li><b>「按 {@code OccurredAt} 升序 + 同毫秒按 {@code Id}」</b>是 SQL 的 {@code ORDER BY}，
 *       替身 DAO 只会把给它的表原样吐回来 ⇒ 排序判据在
 *       {@code ObservedEventDao.selectByBatch} 的语句里，本类只能测「顺序不被服务层打乱」。</li>
 *   <li><b>{@code CAST(OccurredAt AS CHAR)} 出来到底是不是 3 位小数</b>——这是 MySQL 的行为，
 *       要真库才知道（实测定论：{@code 2026-10-15 11:30:22.123}）。</li>
 * </ol>
 */
class ReplayServiceTest {

    private final ObservedEventDao dao = mock(ObservedEventDao.class);
    private final ReplayService service = new ReplayService(dao);

    /** 一行 {@code observed_event}（列名与 {@code ObservedEventDao.COLUMNS} 一致）。 */
    private static Map<String, Object> row(long id, String occurredAt) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Id", id);
        r.put("Kind", "lock");
        r.put("Severity", "warn");
        r.put("Title", "锁等待超时");
        r.put("ObjectType", "lock");
        r.put("ObjectId", "order:1");
        r.put("TraceId", "t-1");
        r.put("BatchId", "b-1");
        r.put("OccurredAt", occurredAt);
        return r;
    }

    // ==================== 一、缺省与收口 ====================

    /** 只给 {@code batchId}：缺省 5000 条，信封四个键齐全。 */
    @Test
    void defaultsToFiveThousandAndReturnsTheEnvelope() {
        when(dao.selectByBatch(anyString(), anyInt())).thenReturn(List.of(row(1L, "2026-10-15 11:30:22.123")));
        when(dao.countByBatch(anyString())).thenReturn(1L);

        Map<String, Object> out = service.replay("b-1", null);

        assertEquals(Boolean.TRUE, out.get("success"));
        assertEquals("b-1", out.get("batchId"));
        assertEquals(ReplayService.DEFAULT_LIMIT, out.get("limit"));
        assertEquals(1L, out.get("total"));
        assertEquals(1, ((List<?>) out.get("list")).size());
        verify(dao).selectByBatch("b-1", ReplayService.DEFAULT_LIMIT);
    }

    /** 空白 / 0 / 负数都当「没给」看（分页控件不会发这种值，但 curl 会）。 */
    @Test
    void blankZeroAndNegativeLimitAllFallBackToDefault() {
        String[] rawValues = {"", "   ", "0", "-5"};
        when(dao.selectByBatch(anyString(), anyInt())).thenReturn(List.of());
        for (String raw : rawValues) {
            service.replay("b-1", raw);
        }
        // ⚠️ 四次要**一起**核（一次 `verify(dao)` 只认「一次调用」—— 放在循环里第二次就炸
        //    TooManyActualInvocations，那是测试写错、不是被测代码错）。
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(dao, times(rawValues.length)).selectByBatch(eq("b-1"), limits.capture());
        for (Integer limit : limits.getAllValues()) {
            assertEquals(ReplayService.DEFAULT_LIMIT, limit, "四个入参（含空白/0/负数）都该落回缺省");
        }
    }

    /** 正常值原样透传（别顺手也压成缺省 —— 那样「要看全量」这个动作就没了）。 */
    @Test
    void explicitLimitIsPassedThrough() {
        when(dao.selectByBatch(anyString(), anyInt())).thenReturn(List.of());

        service.replay("b-1", " 100 ");

        verify(dao).selectByBatch("b-1", 100);
    }

    /** 上限是**收进去**而不是拒绝（同 {@code records} 的「越界回一页正常数据比回 400 有用」）。 */
    @Test
    void limitAboveMaxIsClampedNotRejected() {
        when(dao.selectByBatch(anyString(), anyInt())).thenReturn(List.of());

        Map<String, Object> out = service.replay("b-1", "999999");

        verify(dao).selectByBatch("b-1", ReplayService.MAX_LIMIT);
        assertEquals(ReplayService.MAX_LIMIT, out.get("limit"), "回给前端的必须是被收到的那一个值");
    }

    // ==================== 二、非法入参：400 ====================

    /** {@code batchId} 必填：空白 ⇒ 抛（控制器转 400），且**一次库都不碰**。 */
    @Test
    void blankBatchIdIsRejectedWithoutTouchingTheDatabase() {
        for (String bad : new String[] {null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> service.replay(bad, null), "入参：" + bad);
        }
        verifyNoMoreInteractions(dao);
    }

    /** {@code limit} 不是整数 ⇒ 抛（**不**回落到缺省：那会把一次填错变成一个看起来正常的响应）。 */
    @Test
    void nonNumericLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.replay("b-1", "abc"));
        verify(dao, never()).selectByBatch(anyString(), anyInt());
    }

    // ==================== 三、批次有无 ====================

    /** 批次不认识 / 已被清理 ⇒ 200 + 空表 + total 0（「没有事件」是正常答案，不是 404）。 */
    @Test
    void unknownBatchYieldsEmptyListNotAnError() {
        when(dao.selectByBatch(anyString(), anyInt())).thenReturn(List.of());
        when(dao.countByBatch(anyString())).thenReturn(0L);

        Map<String, Object> out = service.replay("nope", null);

        assertEquals(Boolean.TRUE, out.get("success"));
        assertTrue(((List<?>) out.get("list")).isEmpty());
        assertEquals(0L, out.get("total"));
    }

    /**
     * ⚠️ 本类**唯一一条容易写错的契约**：{@code total} 是**整批**的条数，不是「返回了几条」。
     * 两者混掉的话，前端那句「共 N 条，本次回放前 M 条」就会永远显示成「全放完了」
     * —— 一次被 {@code limit} 截断的回放会画得跟完整回放一模一样（TD-20：不静默）。
     */
    @Test
    void totalIsBatchWideNotPageWide() {
        when(dao.selectByBatch("b-1", ReplayService.DEFAULT_LIMIT)).thenReturn(List.of(row(1L, "2026-10-15 11:30:22.100")));
        when(dao.countByBatch("b-1")).thenReturn(20374L);

        Map<String, Object> out = service.replay("b-1", null);

        assertEquals(20374L, out.get("total"), "total 必须是整批，不是 list.size()");
        assertEquals(1, ((List<?>) out.get("list")).size());
    }

    /** 批次串两端空白先去掉再查（表单控件不会带空白，但手敲的 curl 会）。 */
    @Test
    void batchIdIsTrimmedBeforeQuerying() {
        when(dao.selectByBatch(anyString(), anyInt())).thenReturn(List.of());

        Map<String, Object> out = service.replay("  b-1  ", null);

        verify(dao).selectByBatch("b-1", ReplayService.DEFAULT_LIMIT);
        verify(dao).countByBatch("b-1");
        assertEquals("b-1", out.get("batchId"), "回给前端的也必须是去掉空白的那一个");
    }

    // ==================== 四、顺序不打乱 ====================

    /** DAO 给什么顺序就吐什么顺序（排序判据在 SQL 里，服务层只负责不重排）。 */
    @Test
    void preservesRowOrderFromTheDao() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(3L, "2026-10-15 11:30:22.100"));
        rows.add(row(9L, "2026-10-15 11:30:22.100"));
        rows.add(row(11L, "2026-10-15 11:30:23.900"));
        when(dao.selectByBatch(anyString(), anyInt())).thenReturn(rows);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) service.replay("b-1", null).get("list");

        assertSame(rows, list, "服务层不该复制/重排（复制一份只会让两处列表各自漂）");
        assertEquals(List.of(3L, 9L, 11L), List.of(list.get(0).get("Id"), list.get(1).get("Id"), list.get(2).get("Id")));
    }
}
