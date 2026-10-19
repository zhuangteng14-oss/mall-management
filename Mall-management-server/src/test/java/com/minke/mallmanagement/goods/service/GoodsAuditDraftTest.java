package com.minke.mallmanagement.goods.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.goods.dao.GoodsAuditDao;
import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.marketing.coupon.dao.CouponDao;
import com.minke.mallmanagement.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 批四第二批（2026-10-19）：草稿态 {@code draft → pending} 的 CAS 语义。
 *
 * <h2>【为什么判据落在「DAO 调用形状」而不是返回值】</h2>
 * 返回值只有成功 / 失败两档，而这条链上真正要防的是
 * <b>「CAS 有没有落到正确的 WHERE 条件上」</b>与
 * <b>「CAS 落空后还会不会继续读行 / 发通知」</b> —— 前者由 {@link GoodsAuditDao#casSubmitDraft}
 * 的 SQL 字面量钉住（{@code Status='draft'} + {@code MerchantId}），后者由本类的
 * {@code verify(never())} 钉住。只看返回值时，把 CAS 写成「无条件 UPDATE」也会全绿。
 *
 * <p>⚠️ 本类**不**证明数据库里真的只动了一行 —— 那需要真库（{@code GoodsAuditTxAtomicityTest}
 * 的 A3 档）。本类证明的是「服务层把哪些参数交给了 CAS、落空后有没有继续走」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoodsAuditDraftTest {

    @Mock private GoodsAuditDao auditDao;
    @Mock private GoodsDao goodsDao;
    @Mock private GoodsCacheService goodsCacheService;
    @Mock private CouponDao couponDao;
    @Mock private NotificationService notificationService;

    private GoodsAuditService service;

    @BeforeEach
    void setUp() {
        service = new GoodsAuditService(auditDao, goodsDao, goodsCacheService, couponDao,
                new ObjectMapper(), notificationService);
    }

    @Test
    @DisplayName("CAS 命中：draft→pending，回 batchId / goodName")
    void casHitMarksPending() {
        Map<String, Object> row = new HashMap<>();
        row.put("BatchId", "B-1");
        row.put("GoodName", "声谷耳机");
        when(auditDao.casSubmitDraft(7L, "M1")).thenReturn(1);
        when(auditDao.selectById(7L)).thenReturn(row);

        Map<String, Object> result = service.submitDraft("M1", 7L);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("status")).isEqualTo(GoodsAuditService.STATUS_PENDING);
        assertThat(result.get("batchId")).isEqualTo("B-1");
        assertThat(result.get("goodName")).isEqualTo("声谷耳机");
        // CAS 的参数就是身份判据：商家号必须跟着下去，否则会改到别人的草稿
        verify(auditDao).casSubmitDraft(7L, "M1");
    }

    @Test
    @DisplayName("CAS 落空（不存在 / 别人的 / 已提交过）：只回文案，不再读行")
    void casMissDoesNotReadRow() {
        when(auditDao.casSubmitDraft(7L, "M1")).thenReturn(0);

        Map<String, Object> result = service.submitDraft("M1", 7L);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("msg"))).contains("草稿不存在");
        // 关键：落空后 if 分支直接 return —— 没有再发一条 SELECT
        verify(auditDao, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("参数缺失：一次 DAO 都不发")
    void missingParamsShortCircuit() {
        Map<String, Object> noMerchant = service.submitDraft("  ", 7L);
        Map<String, Object> noId = service.submitDraft("M1", null);
        Map<String, Object> badId = service.submitDraft("M1", 0L);

        assertThat(noMerchant.get("success")).isEqualTo(false);
        assertThat(noId.get("success")).isEqualTo(false);
        assertThat(badId.get("success")).isEqualTo(false);
        verifyNoInteractions(auditDao);
    }

    @Test
    @DisplayName("CAS 命中但读不回行：仍是成功（状态已改），只是少了回显字段")
    void casHitWithoutRowStillSucceeds() {
        when(auditDao.casSubmitDraft(7L, "M1")).thenReturn(1);
        when(auditDao.selectById(7L)).thenReturn(null);

        Map<String, Object> result = service.submitDraft("M1", 7L);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("status")).isEqualTo(GoodsAuditService.STATUS_PENDING);
        assertThat(result).doesNotContainKeys("batchId", "goodName");
        verify(auditDao).casSubmitDraft(anyLong(), anyString());
    }
}
