package com.minke.mallmanagement.order.job;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.order.dao.OrderDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超时释放的<b>前置判据</b>（{@code order.StockDeducted}）实测 —— C3 块 7 · commit 2d。
 *
 * <p>被测的只有一件：{@link OrderTimeoutCancelJob#releaseStock} <b>在什么条件下才去加库存</b>。
 * 它修掉的是 2d 之前那个偏账 —— 那时 {@code GoodsDao.releaseStock} 无条件加回，
 * 「下单时库存不足 ⇒ 扣减 0 行、但单照样落库」的单超时取消时会被**凭空补出一份库存**。
 *
 * <h2>【为什么是纯替身单测，而不是真库 / 切片】</h2>
 * <ul>
 *   <li>判据是「**读那一列的值 → 调不调 DAO**」这条分支，与 SQL 文本无关（SQL 不出本文件）；</li>
 *   <li>{@code OrderTimeoutCancelJob} 是 {@code @Component} + {@code @Scheduled}，
 *       <b>不在</b> {@code @WebMvcTest} 切片里（切片不扫组件、也没有调度器）
 *       ⇒ 它在 smoke 里**零覆盖**，这条分支此前**没有任何测试**；</li>
 *   <li>真库路线还要等一个 60 秒的 tick，跑不出「读失败 ⇒ 不回补」这一支。
 *       ⇒ 用替身把三分支（0 / 1 / 读抛）逐一钉死，各一次 {@code advance()}。</li>
 * </ul>
 *
 * <p>⚠️ {@code timeoutMs} 走 {@code @Value} 注入，替身实例上没有 Spring 容器 ⇒ 用
 * {@link ReflectionTestUtils} 直接置 60000（= 演示默认，切出 60 秒阈值）。
 */
class OrderTimeoutCancelJobStockGateTest {

    private static final String ORDER = "SIM-ORDER-1";

    private final OrderDao orderDao = mock(OrderDao.class);
    private final GoodsDao goodsDao = mock(GoodsDao.class);
    private final StockMetrics stockMetrics = mock(StockMetrics.class);
    private final OrderTimeoutCancelJob job = new OrderTimeoutCancelJob(orderDao, goodsDao, stockMetrics);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(job, "timeoutMs", 60000L);
        // 共同前提：扫到一张超时未付款单，且本轮**真的把它取消了**（只有这一支才走到释放）。
        when(orderDao.selectUnpaidExpired(60L)).thenReturn(List.of(row(Map.of("OrderId", ORDER))));
        when(orderDao.cancelIfUnpaid(ORDER)).thenReturn(1);
    }

    @Test
    @DisplayName("StockDeducted = 0（当初一行也没扣成）⇒ 一件都不回补")
    void stockDeductedZero_neverReleases() {
        when(orderDao.selectStockDeducted(ORDER)).thenReturn(0);

        job.advance();

        // ⚠️ 断言两件事：既没有加库存，也**没有去读明细** —— 后者是「判据排在最前」的证据
        //    （若判据被挪到读明细之后，本行会红，而不是静默照补）。
        verify(goodsDao, never()).releaseStock(anyString(), anyInt());
        verify(orderDao, never()).selectItemStocksByOrder(anyString());
    }

    @Test
    @DisplayName("StockDeducted = 1 ⇒ 逐条明细回补（扣谁回谁）")
    void stockDeductedOne_releasesEachItem() {
        when(orderDao.selectStockDeducted(ORDER)).thenReturn(1);
        when(orderDao.selectItemStocksByOrder(ORDER))
                .thenReturn(List.of(row(Map.of("ProductCode", "A1", "Quantity", 2))));
        when(goodsDao.releaseStock("A1", 2)).thenReturn(1);

        job.advance();

        verify(goodsDao).releaseStock("A1", 2);
    }

    @Test
    @DisplayName("⚠️ 那一列读不出来 ⇒ **fail-closed**：不回补（偏账选「少还一件」，不选「凭空补一件」）")
    void stockDeductedReadFails_closedNoRelease() {
        when(orderDao.selectStockDeducted(ORDER)).thenThrow(new IllegalStateException("库抖了一下"));

        job.advance();

        verify(goodsDao, never()).releaseStock(anyString(), anyInt());
        // 取消本身是既成事实：读标记失败**不回滚**取消（顺序上取消先发生）。
        verify(orderDao).cancelIfUnpaid(ORDER);
    }

    /** 造一行 {@code ResultSet} 风格的单元格（可变 Map，与生产里 DAO 给的那份同型）。 */
    private static Map<String, Object> row(Map<String, Object> cells) {
        return new java.util.HashMap<>(cells);
    }
}
