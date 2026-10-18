package com.minke.mallmanagement.shared.web;

import com.minke.mallmanagement.admin.dao.AdminOrderDao;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.admin.service.UserGovernanceService;
import com.minke.mallmanagement.comment.service.CommentService;
import com.minke.mallmanagement.comment.service.ReportService;
import com.minke.mallmanagement.comment.service.SensitiveWordService;
import com.minke.mallmanagement.goods.service.GoodsAuditService;
import com.minke.mallmanagement.goods.service.GoodsService;
import com.minke.mallmanagement.marketing.coupon.service.CouponService;
import com.minke.mallmanagement.marketing.seckill.service.SeckillService;
import com.minke.mallmanagement.marketing.subsidy.service.SubsidyService;
import com.minke.mallmanagement.merchant.service.MerchantApplicationService;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.observe.service.DrillRecordService;
import com.minke.mallmanagement.observe.service.OrderStreamService;
import com.minke.mallmanagement.observe.service.ReplayService;
import com.minke.mallmanagement.stats.dao.StatsDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3-3 分页收口：{@link PageLimits} 的语义 + **13 处调用点真的接到了同一个数**。
 *
 * <h2>【为什么用反射打私有 {@code normalizeLimit}】</h2>
 * 这些 Service 的「生效 limit」是在返回值里回显的（{@code result.put("limit", effective)}），
 * 但真要构造它们得为每个域准备一套 DAO 替身 —— 十来个 Service 的构造函数就是本笔没动过的东西。
 * 收口要钉的**只有一件事**：超限入参经过各自的归一函数后是不是 100。直接调那个归一函数
 * 是**唯一不掺入业务分支**的判据；构造整套替身反而会把「DAO 返回什么」混进来。
 *
 * <p>⚠️ 反射打的是**静态私有方法**（{@code private static int normalizeLimit(Integer)}）——
 * 改签名会让本测试立刻红，这正是想要的：常量改回 500 而某处归一没跟上的漂移，只有这种
 * 「逐个调用点点名」的测试能发现。
 */
class PageLimitsTest {

    /** 十个「缺省 100 / 上限曾为 500」的 Service —— 归一函数同名同签名。 */
    private static final List<Class<?>> CLAMPED_SERVICES = List.of(
            AuditService.class,
            UserGovernanceService.class,
            ReportService.class,
            SensitiveWordService.class,
            GoodsAuditService.class,
            CouponService.class,
            SeckillService.class,
            SubsidyService.class,
            MerchantApplicationService.class,
            NotificationService.class,
            CommentService.class);

    @Test
    @DisplayName("clamp：null / 0 / 负数 ⇒ 100；1 / 100 / 101 / 99999 ⇒ 1 / 100 / 100 / 100")
    void clampSemantics() {
        assertThat(PageLimits.clamp((Integer) null)).isEqualTo(100);
        assertThat(PageLimits.clamp(0)).isEqualTo(100);
        assertThat(PageLimits.clamp(-1)).isEqualTo(100);
        assertThat(PageLimits.clamp(1)).isEqualTo(1);
        assertThat(PageLimits.clamp(100)).isEqualTo(100);
        assertThat(PageLimits.clamp(101)).isEqualTo(100);
        assertThat(PageLimits.clamp(99999)).isEqualTo(100);
    }

    @Test
    @DisplayName("十一个 Service：超限入参 9999 ⇒ 归一函数回 100（不是 500）")
    void everyServiceNormalizesToHundred() throws Exception {
        for (Class<?> type : CLAMPED_SERVICES) {
            Method normalize = type.getDeclaredMethod("normalizeLimit", Integer.class);
            normalize.setAccessible(true);
            Object effective = normalize.invoke(null, 9999);
            assertThat(effective).as(type.getSimpleName() + " 的生效 limit").isEqualTo(100);
        }
    }

    @Test
    @DisplayName("GoodsService.normalizePageSize / AdminOrderDao.clampLimit（公开静态）：9999 ⇒ 100")
    void publicClampsAlsoReturnHundred() {
        assertThat(GoodsService.normalizePageSize(9999)).isEqualTo(100);
        assertThat(AdminOrderDao.clampLimit(9999)).isEqualTo(100);
        assertThat(AdminOrderDao.clampLimit(0)).as("缺省也必须是 100（不能缺省 200 却把上限写 100）")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("常量对齐：13 处上限都是 100；两个例外原样保留（Replay 50000 / Stats 50）")
    void constantsAreUnified() throws Exception {
        assertThat(constant(AdminOrderDao.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(AuditService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(UserGovernanceService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(ReportService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(SensitiveWordService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(GoodsAuditService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(CouponService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(SeckillService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(SubsidyService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(MerchantApplicationService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(NotificationService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(GoodsService.class, "MAX_PAGE_SIZE")).isEqualTo(100);
        assertThat(constant(CommentService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(DrillRecordService.class, "MAX_LIMIT")).isEqualTo(100);
        assertThat(constant(OrderStreamService.class, "MAX_DETAIL_LIMIT")).isEqualTo(100);

        assertThat(constant(ReplayService.class, "MAX_LIMIT")).as("回放是离线批量读，保留 50000")
                .isEqualTo(50000);
        assertThat(constant(StatsDao.class, "MAX_LIMIT")).as("Top-N 看板本来就低于 100，不参与收口")
                .isEqualTo(50);
    }

    private static int constant(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
