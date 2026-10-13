package com.minke.mallmanagement.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.admin.dao.AdminAuditDao;
import com.minke.mallmanagement.observe.metrics.AuditMetrics;
import com.minke.mallmanagement.shared.web.AuthSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 块 7 · commit 2b —— 审计环的**真观测点**（{@code AuditService.record}）钉三件事：
 *
 * <ol>
 *   <li><b>成功与失败各记一条，且分得开</b>：INSERT 没抛 ⇒ {@code written}；抛了 ⇒ {@code failed}。
 *       本方法是 fail-open 的（吞异常）⇒「审计失败率」这个数**只有**本环读得到，别处没有；</li>
 *   <li><b>失败照旧不外抛</b>：埋点不能把「业务已完成」翻成失败 —— 这条比计数本身更要紧；</li>
 *   <li><b>actor 取会话、不取入参</b>（{@code AuditMetrics#written} 的 @param）：认不出身份时
 *       记 {@code null}，**不编造**。</li>
 * </ol>
 *
 * <p>不起 Spring、不起 MySQL：{@code SimpleMeterRegistry} 每方法一份 ⇒ 计数天然隔离；
 * DAO 是替身 ⇒ 成功 / 失败两路都由本类自己摆。
 */
class AuditServiceMetricsTest {

    /** ⚠️ 与 {@code AuditMetrics.WRITES} 同一个字面量 —— 那边是**包私有**常量，本包取不到。 */
    private static final String WRITES = "mall.audit.writes";

    private static final AuthSession.Identity ACTOR = new AuthSession.Identity("u-1", 1, false);

    private SimpleMeterRegistry registry;
    private AuditMetrics metrics;
    private AdminAuditDao dao;
    private AuditService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AuditMetrics(registry);
        dao = mock(AdminAuditDao.class);
        service = new AuditService(dao, new ObjectMapper(), metrics);
    }

    @Test
    @DisplayName("写成功 ⇒ written +1，事件里是**会话**里的业务号")
    void writtenOnSuccess() {
        service.record(ACTOR, "goodsUp", "goods", "M:1", null, null);

        assertThat(registry.counter(WRITES, "result", "written").count()).isEqualTo(1.0d);
        assertThat(registry.counter(WRITES, "result", "failed").count()).isZero();
        assertThat(metrics.recentEvents()).singleElement().satisfies(e -> {
            assertThat(e.result()).isEqualTo("written");
            assertThat(e.actor()).isEqualTo("u-1");
            assertThat(e.action()).isEqualTo("goodsUp");
            assertThat(e.reason()).isNull();
        });
    }

    @Test
    @DisplayName("写失败 ⇒ failed +1 且**不外抛**（fail-open 的原样保留）")
    void failedOnExceptionAndStillSwallows() {
        doThrow(new IllegalStateException("boom"))
                .when(dao).insert(any(), any(), any(), any(), any(), any(), any());

        assertThatCode(() -> service.record(ACTOR, "goodsUp", "goods", "M:1", null, null))
                .as("埋点绝不能把「业务已完成」翻成失败")
                .doesNotThrowAnyException();

        assertThat(registry.counter(WRITES, "result", "failed").count()).isEqualTo(1.0d);
        assertThat(registry.counter(WRITES, "result", "written").count()).isZero();
        assertThat(metrics.recentEvents()).singleElement()
                .satisfies(e -> assertThat(e.reason()).contains("boom"));
    }

    @Test
    @DisplayName("认不出身份 ⇒ actor 记 null（不编造），计数照记")
    void nullActorIsRecordedAsNull() {
        service.record(null, "goodsDe", "goods", "M:1", null, null);

        assertThat(metrics.recentEvents()).singleElement()
                .satisfies(e -> assertThat(e.actor()).isNull());
        assertThat(registry.counter(WRITES, "result", "written").count()).isEqualTo(1.0d);
    }
}
