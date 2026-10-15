package com.minke.mallmanagement.merchant.service;

import com.minke.mallmanagement.goods.dao.GoodsShelfDao;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.protect.ProtectionGate;
import com.minke.mallmanagement.shared.web.TraceIdFilter;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import com.minke.mallmanagement.user.service.UserCacheService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D 段 · 阶段 2b（甲）：{@code MerchantDowngradeService.relist} 的<b>死锁重试</b>与它跟
 * {@code ProtectionGate.retry} 开关的接线。
 *
 * <h2>【被测的那条链】</h2>
 * <pre>
 *   relist（无事务）
 *     └─ RetryTemplate.execute          ← 外层
 *          └─ GatedRetryPolicy.canRetry  ← 读 ProtectionGate.isOn("retry")
 *          └─ relistOnce
 *               └─ TransactionTemplate.execute   ← 内层（每次尝试各一个事务）
 * </pre>
 *
 * <h2>【为什么是纯单测，不是 @SpringBootTest + 真库】</h2>
 * 判据要的是「<b>第几次</b>尝试抛死锁」这个精确序列 —— 真库只能告诉你「有时候会撞」，
 * 撞不撞、撞在第几次都不受控 ⇒ 用例会随机假绿（同 {@code GoodsWriteTxAtomicityTest} 类注释里
 * 拒用「真失败向量」当主判据的那条理由）。故这里用 Mockito 把死锁序列<b>摆出来</b>，
 * 「真库上确实会死锁」由 {@code docs/agent/evidence/c3-block7/} 的两份侦查报告回答。
 *
 * <p>⚠️ 同理，{@code PlatformTransactionManager} 是个<b>计数替身</b>而不是真事务管理器：
 * 纯单测里没有 DataSource、开不了真事务，但只要 {@code TransactionTemplate} 的<b>调用形状</b>
 * 可数，「每次尝试各开一个事务」这条结构断言就成立（用例 7）。
 *
 * <h2>【用户 2026-10-15 裁定里点名要的那些钉】</h2>
 * <ol>
 *   <li>「重试动作发 protection 事件（{@code action=retry} / {@code attempt=N} / {@code result}）」
 *       ⇒ 用例 1 逐字钉住 {@code detail}；</li>
 *   <li>「多次重试事件<b>同 traceId</b>」⇒ 用例 2（这是页面能把多跳归进同一条链路的前提）；</li>
 *   <li>「只对死锁类」⇒ 用例 4；</li>
 *   <li>「{@code @Retryable} 必须在事务外层」⇒ 用例 6 把这条从「配置对了才对」变成「结构上不可能错」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RelistDeadlockRetryTest {

    private static final String USERNAME = "shopowner1";
    private static final String MERCHANT_ID = "M0001";
    /** 形状同 {@code TraceIdFilter} 认的那一族（{@code [A-Za-z0-9_-]{8,64}}）。 */
    private static final String TRACE = "trace-retry-0001";

    @Mock
    private UserGovernanceDao userGovernanceDao;
    @Mock
    private GoodsShelfDao goodsShelfDao;
    @Mock
    private UserCacheService userCacheService;

    /** 真对象：本类要验的正是「开关真能拦住重试」，打桩它就等于把被测物换掉了。 */
    private final ProtectionGate gate = new ProtectionGate();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ProtectionMetrics metrics;
    private CountingTxManager txManager;
    private MerchantDowngradeService service;

    @BeforeEach
    void setUp() {
        metrics = new ProtectionMetrics(registry);
        txManager = new CountingTxManager();
        service = new MerchantDowngradeService(userGovernanceDao, goodsShelfDao, userCacheService,
                gate, metrics, txManager);
        // 事件里的 traceId 取自 MDC（本类唯一的写入点是 TraceIdFilter）—— 用例 2 靠它。
        TraceIdFilter.bind(TRACE, null);
    }

    @AfterEach
    void tearDown() {
        TraceIdFilter.bind(null, null);
        registry.close();
    }

    // ==================== 用例 1：重试成功 ====================

    @Test
    @DisplayName("用例 1：第 1 次撞死锁、第 2 次成功 ⇒ 返回 200 语义，并发一条 retry_success(attempt=2)")
    void secondAttemptSucceedsAfterOneDeadlock() {
        when(userGovernanceDao.selectMerchantId(USERNAME)).thenReturn(MERCHANT_ID);
        when(goodsShelfDao.relistAllByMerchant(MERCHANT_ID))
                .thenThrow(new CannotAcquireLockException("用例注入：第 1 次撞 1213"))
                .thenReturn(7);

        Map<String, Object> r = service.relist(USERNAME);

        assertThat(r).containsEntry("success", true).containsEntry("relisted", 7);
        verify(goodsShelfDao, times(2)).relistAllByMerchant(MERCHANT_ID);

        assertThat(retryEvents()).singleElement().satisfies(e -> {
            assertThat(e.result()).isEqualTo("retry_success");
            assertThat(e.key()).isEqualTo("retry");
            assertThat(e.scope()).isEqualTo("relist");
            // 用户裁定逐字：action=retry / attempt=N / result
            assertThat(e.detail())
                    .as("⚠️ attempt 从 2 起：第 1 次尝试不是「重试」（RetryContext.getRetryCount() 首次为 0）")
                    .isEqualTo("action=retry attempt=2 result=success");
            assertThat(e.traceId()).isEqualTo(TRACE);
        });
    }

    // ==================== 用例 2：一路死锁 ⇒ 试满 3 次仍是 500 ====================

    @Test
    @DisplayName("用例 2：三次尝试全撞死锁 ⇒ 抛出原异常、只试 3 次；两条 retry_failed **同 traceId**")
    void alwaysDeadlockRetriesThreeTimesThenThrows() {
        when(userGovernanceDao.selectMerchantId(USERNAME)).thenReturn(MERCHANT_ID);
        when(goodsShelfDao.relistAllByMerchant(MERCHANT_ID))
                .thenThrow(new CannotAcquireLockException("用例注入：每次都撞 1213"));

        assertThatThrownBy(() -> service.relist(USERNAME))
                .as("试满 3 次仍失败 ⇒ 如实抛出（吞掉它会把 500 变成「静默什么都没做」）")
                .isInstanceOf(CannotAcquireLockException.class);

        verify(goodsShelfDao, times(3)).relistAllByMerchant(MERCHANT_ID);

        List<ProtectionMetrics.ProtectionEvent> events = retryEvents();
        assertThat(events).as("3 次尝试 = 1 次原始 + 2 次重试 ⇒ 只记那 2 次重试")
                .hasSize(2);
        assertThat(events).extracting(ProtectionMetrics.ProtectionEvent::result)
                .containsExactly("retry_failed", "retry_failed");
        assertThat(events).extracting(ProtectionMetrics.ProtectionEvent::detail)
                .containsExactly("action=retry attempt=2 result=deadlock",
                        "action=retry attempt=3 result=deadlock");
        assertThat(events).extracting(ProtectionMetrics.ProtectionEvent::traceId)
                .as("用户 2026-10-15 裁定点名的钉：多次重试事件必须**同 traceId** —— "
                        + "页面上要靠它把同一跳的 2 次重试归进同一条链路")
                .containsOnly(TRACE);
    }

    // ==================== 用例 3：开关关掉 = 一次都不重试 ====================

    @Test
    @DisplayName("用例 3：retry 开关关掉 ⇒ 一次都不重试（dao 只调 1 次、0 事件），死锁直接冒泡成 500")
    void gateOffMeansNotEvenOneRetry() {
        gate.apply(List.of("rateLimit", "circuitBreak", "poolIsolation"));
        assertThat(gate.isOn("retry")).as("前置：这一轮 retry 是关的").isFalse();
        when(userGovernanceDao.selectMerchantId(USERNAME)).thenReturn(MERCHANT_ID);
        when(goodsShelfDao.relistAllByMerchant(MERCHANT_ID))
                .thenThrow(new CannotAcquireLockException("用例注入：1213"));

        assertThatThrownBy(() -> service.relist(USERNAME))
                .isInstanceOf(CannotAcquireLockException.class);

        verify(goodsShelfDao, times(1)).relistAllByMerchant(MERCHANT_ID);
        assertThat(retryEvents()).as("开关关着 ⇒ 连一次重试都没有，自然也没有重试事件").isEmpty();
        assertThat(txManager.started).as("只开了一个事务（那一次尝试的）").isEqualTo(1);
    }

    // ==================== 用例 4：只对死锁类 ====================

    @Test
    @DisplayName("用例 4：非死锁异常（连不上库）一次都不重试 —— 重试它只是把失败拖慢")
    void otherExceptionsAreNotRetried() {
        when(userGovernanceDao.selectMerchantId(USERNAME)).thenReturn(MERCHANT_ID);
        when(goodsShelfDao.relistAllByMerchant(MERCHANT_ID))
                .thenThrow(new DataAccessResourceFailureException("用例注入：连不上库"));

        assertThatThrownBy(() -> service.relist(USERNAME))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(goodsShelfDao, times(1)).relistAllByMerchant(MERCHANT_ID);
        assertThat(retryEvents()).isEmpty();
    }

    // ==================== 用例 5：两个早退分支在重试之外 ====================

    @Test
    @DisplayName("用例 5：参数校验类早退在重试**之外** —— 连事务都不开（输入不对，重试一万次也不对）")
    void earlyReturnsAreOutsideRetryAndOutsideTransaction() {
        assertThat(service.relist("   ")).containsEntry("success", false)
                .containsEntry("msg", "缺少参数：username");
        verify(userGovernanceDao, times(0)).selectMerchantId(anyString());

        when(userGovernanceDao.selectMerchantId(USERNAME)).thenReturn(null);

        assertThat(service.relist(USERNAME)).containsEntry("success", false)
                .containsEntry("msg", "该账号没有商家号（从未入驻）");

        verify(goodsShelfDao, times(0)).relistAllByMerchant(anyString());
        assertThat(txManager.started).as("两个早退分支都不该开事务").isZero();
        assertThat(retryEvents()).isEmpty();
    }

    // ==================== 用例 6 / 7：结构钉 ====================

    /**
     * ⚠️ 这条是「重试在事务外层」那句话的<b>可执行形式</b>。
     *
     * <p>若 {@code @Transactional} 回到 {@code relist} 上，重试就发生在<b>同一个事务里</b>：
     * 第 1 次尝试撞死锁 ⇒ 事务已被 MySQL 回滚 ⇒ 第 2 次尝试是在一个已死的连接/事务上重放，
     * 而且「谁先谁后」变成 {@code @Retryable} 与 {@code @Transactional} 两个 advisor 的 order 契约
     * —— 「配置对了才对」。删掉它则顺序由对象调用关系决定：「结构上不可能错」。
     */
    @Test
    @DisplayName("结构钉：relist 上**没有** @Transactional（重试在事务外层不靠切面 order，靠结构）")
    void relistCarriesNoTransactionalAnnotation() throws Exception {
        assertThat(MerchantDowngradeService.class.getMethod("relist", String.class)
                .getAnnotation(Transactional.class))
                .as("有人把 @Transactional 加回 relist ⇒ 重试退化成「在已回滚的事务里重试」")
                .isNull();
    }

    @Test
    @DisplayName("结构钉：每次尝试各开一个事务（失败那次的半个事务要回滚掉，不能跨尝试共用）")
    void everyAttemptOpensItsOwnTransaction() {
        when(userGovernanceDao.selectMerchantId(USERNAME)).thenReturn(MERCHANT_ID);
        when(goodsShelfDao.relistAllByMerchant(MERCHANT_ID))
                .thenThrow(new CannotAcquireLockException("用例注入：第 1 次"))
                .thenReturn(3);

        service.relist(USERNAME);

        assertThat(txManager.started).as("2 次尝试 ⇒ 2 个事务").isEqualTo(2);
        assertThat(txManager.rolledBack).as("第 1 次尝试的半个事务必须回滚").isEqualTo(1);
        assertThat(txManager.committed).as("只有成功那次提交").isEqualTo(1);
    }

    // ==================== 夹具 ====================

    /** 只记 {@code retry} 那个 key 的事件（本类不该有别的 key 进来，那条由用例自身断言）。 */
    private List<ProtectionMetrics.ProtectionEvent> retryEvents() {
        return metrics.recentEvents().stream().filter(e -> "retry".equals(e.key())).toList();
    }

    /**
     * 事务管理器的<b>计数替身</b>：不起真事务，只数「开 / 交 / 滚」各几次。
     *
     * <p>⚠️ 它<b>不能</b>用来断言「真的事务语义」（那要真 MySQL，见 {@code GoodsWriteTxAtomicityTest}）；
     * 它只能断言<b>调用形状</b> —— 而那正是本笔要钉的那一条（每次尝试一个事务）。
     */
    private static final class CountingTxManager implements PlatformTransactionManager {

        private int started;
        private int committed;
        private int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            started++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }
}
