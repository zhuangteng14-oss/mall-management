package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.dao.DrillTargetDao;
import com.minke.mallmanagement.observe.dao.ObservedEventCleanupDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ObservedEventCleanupService#enforceHighWatermark()} 的纯替身单测（2026-10-19）。
 *
 * <h2>【为什么补这一笔】</h2>
 * 水位熔断是用户批文第二节的核心（「超过 30 万行就按时间淘汰最旧，牺牲历史、保全当下」），
 * 但 DAO 测试只钉住了单批 SQL，**「循环到安全线」「未超水位不删」「删不动时不会转死圈」
 * 这三条编排判据一条都没有覆盖**。熔断一旦写错，失效形态是静默的：要么不淘汰（表继续涨），
 * 要么在 n=0 时无限循环（`/run` 永远不回）。
 *
 * <p>⚠️ 这里**不是**在验 SQL —— {@code deleteOldestBatch} 的语句/绑定在
 * {@code ObservedEventCleanupDaoTest} 里；本文件只验「什么时候调、调几次、什么时候停」。
 * ⚠️ `countObservedEvents()` 的返回值是按调用顺序喂的：循环每一批都**重读真实行数**，
 * 所以序列里既有「删前」也有「删后」，别把它当常数桩。
 */
class ObservedEventCleanupServiceTest {

    private final ObservedEventCleanupDao cleanupDao = mock(ObservedEventCleanupDao.class);
    private final ObservedEventCleanupService service =
            new ObservedEventCleanupService(cleanupDao, mock(DrillTargetDao.class));

    private void configure(int high, int target, int batch) {
        ReflectionTestUtils.setField(service, "highWatermark", high);
        ReflectionTestUtils.setField(service, "targetWatermark", target);
        ReflectionTestUtils.setField(service, "evictionBatchSize", batch);
        ReflectionTestUtils.setField(service, "evictionPauseMs", 0L);
    }

    @Test
    @DisplayName("未超高水位：只读一次行数，不淘汰任何一批")
    void belowHighWatermarkDeletesNothing() {
        configure(20, 10, 5);
        when(cleanupDao.countObservedEvents()).thenReturn(8L);

        var result = service.enforceHighWatermark();

        assertThat(result.before()).isEqualTo(8L);
        assertThat(result.after()).isEqualTo(8L);
        assertThat(result.deleted()).isZero();
        assertThat(result.triggered()).isFalse();
        verify(cleanupDao, never()).deleteOldestBatch(5);
    }

    @Test
    @DisplayName("超高水位：每批重读行数，淘汰到安全线即停")
    void evictsUntilTargetWatermark() {
        configure(20, 10, 5);
        // before=30；循环重读 30 / 25 / 20 / 15 / 10（<= 安全线，停）；收口再读 10。
        when(cleanupDao.countObservedEvents()).thenReturn(30L, 30L, 25L, 20L, 15L, 10L, 10L);
        when(cleanupDao.deleteOldestBatch(5)).thenReturn(5);

        var result = service.enforceHighWatermark();

        assertThat(result.before()).isEqualTo(30L);
        assertThat(result.after()).isEqualTo(10L);
        assertThat(result.deleted()).isEqualTo(20L);
        assertThat(result.triggered()).isTrue();
        verify(cleanupDao, times(4)).deleteOldestBatch(5);
    }

    @Test
    @DisplayName("删不动（n=0）：立刻收口，不在超水位上转死圈")
    void stopsWhenDeleteReturnsZero() {
        configure(20, 10, 5);
        when(cleanupDao.countObservedEvents()).thenReturn(30L, 30L, 30L);
        when(cleanupDao.deleteOldestBatch(5)).thenReturn(0);

        var result = service.enforceHighWatermark();

        assertThat(result.deleted()).isZero();
        assertThat(result.triggered()).isFalse();
        assertThat(result.after()).isEqualTo(30L);
        verify(cleanupDao, times(1)).deleteOldestBatch(5);
    }

    @Test
    @DisplayName("熔断提示是用户批文原文（UI 与 job 日志共用同一条常量）")
    void warningTextIsTheApprovedWording() {
        assertThat(ObservedEventCleanupService.EVICTION_WARNING)
                .isEqualTo("⚠️ 历史事件过多，系统已自动清理最旧数据以保障本次演练性能。");
    }
}
