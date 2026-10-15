package com.minke.mallmanagement.order.job;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.order.dao.OrderDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「跳过演练带标记的行」实测 —— C3 块 7 · commit B · ②（《块 7 参考》§1.4 C2 甲）。
 *
 * <p>被测的只有两件：{@code mall.order.cancel.skip-attack-data} 这道闸**开不开**
 * （{@link OrderTimeoutCancelJob#advance} 里的跳过分支），以及判据本身
 * （{@link OrderTimeoutCancelJob#isAttackData}）。
 *
 * <h2>【用户逐字给的验收判据（本文件就是它的最小版）】</h2>
 * 「{@code false} ⇒ SIM- 未付款单 60 秒后真被取消，{@code true} ⇒ 60 秒后仍在。」
 * 真库那个「60 秒后」的等待在这里被压掉：候选集是**桩**（{@code selectUnpaidExpired} 直接
 * 返回名单），阈值仍按 {@code timeoutMs=60000} 切出来 —— 本文件证的是「名单到手之后的分岔」，
 * 「超时判定那条 SQL 对不对」不在本文件射程内（它由 {@code OrderDao} 的投影决定，
 * 本文件只吃它的返回值）。
 *
 * <h2>【为什么是纯替身单测，而不是真库 / 切片】</h2>
 * 同 {@link OrderTimeoutCancelJobStockGateTest}：{@code OrderTimeoutCancelJob} 是
 * {@code @Component} + {@code @Scheduled}，**不在** {@code @WebMvcTest} 切片里
 * （切片不扫组件、也没有调度器）⇒ 它在 smoke 里零覆盖；而 {@code application-smoke.properties}
 * 里那条 {@code skip-attack-data=false} 更是**双重空转**（连 bean 都不创建）
 * ⇒ 这条分支的测试只能在这里，不能指望 profile 文件替它作证。
 *
 * <p>⚠️ {@code skipAttackData} / {@code timeoutMs} 都走 {@code @Value} 注入，替身实例上没有
 * Spring 容器 ⇒ 用 {@link ReflectionTestUtils} 直接置。⚠️ <b>基本类型 boolean 的 Java 默认值是
 * {@code false}</b> ⇒ 「不置」这一态本身就是 {@code false} 那一档，不用手写
 * （{@link #gateOffByDefault_behavesExactlyAsBefore()} 正是钉这一点）。
 */
class OrderTimeoutCancelJobSkipAttackDataTest {

    private static final String ATTACK_ORDER = "SIM-ORDER-1";
    private static final String NORMAL_ORDER = "ORDER-2026-1";

    private final OrderDao orderDao = mock(OrderDao.class);
    private final GoodsDao goodsDao = mock(GoodsDao.class);
    private final StockMetrics stockMetrics = mock(StockMetrics.class);
    private final OrderTimeoutCancelJob job = new OrderTimeoutCancelJob(orderDao, goodsDao, stockMetrics);

    // ==================== 一、闸关着（缺省）：与加这个开关之前逐字节同行为 ====================

    @Test
    @DisplayName("缺省（不置 skipAttackData）⇒ SIM- 单**照样被取消** —— 关着的开关不改变任何东西")
    void gateOffByDefault_behavesExactlyAsBefore() {
        // ⚠️ 这一行故意**不调** setSkipAttackData：钉的就是「Java 默认 false = 旧行为」。
        ReflectionTestUtils.setField(job, "timeoutMs", 60000L);
        givenCandidates(List.of(row(ATTACK_ORDER, "")));

        job.advance();

        verify(orderDao).cancelIfUnpaid(ATTACK_ORDER);
    }

    @Test
    @DisplayName("闸关着 + 这行 Source='attack' ⇒ **仍然被取消**（证闸真的在闸，不是被谓词偷偷放行）")
    void gateOffWithAttackSource_stillCancels() {
        ReflectionTestUtils.setField(job, "timeoutMs", 60000L);
        ReflectionTestUtils.setField(job, "skipAttackData", false);
        givenCandidates(List.of(row(NORMAL_ORDER, "attack")));

        job.advance();

        verify(orderDao).cancelIfUnpaid(NORMAL_ORDER);
    }

    // ==================== 二、闸开着：带标记的整条不进本轮 ====================

    @Test
    @DisplayName("闸开着 + SIM- 前缀 ⇒ **完全不碰**：不取消、也不进入释放路径（连标记列都不读）")
    void gateOn_skipsPrefixedOrderEntirely() {
        givenGateOn();
        givenCandidates(List.of(row(ATTACK_ORDER, "")));

        job.advance();

        // ⚠️ 三条一起断言才是「整个跳过」：只断 cancelIfUnpaid 的话，跳过点被挪到它之后
        //    （取消已落库、库存已回补）本行仍然绿 —— 那正是要防的失效模式。
        verify(orderDao, never()).cancelIfUnpaid(anyString());
        verify(orderDao, never()).selectStockDeducted(anyString());
        verifyNoInteractions(goodsDao);
    }

    @Test
    @DisplayName("闸开着 + 无前缀但 Source='attack' ⇒ 同样跳过（谓词的第二半单独生效）")
    void gateOn_skipsBySourceColumnAlone() {
        givenGateOn();
        givenCandidates(List.of(row(NORMAL_ORDER, "attack")));

        job.advance();

        verify(orderDao, never()).cancelIfUnpaid(anyString());
        verifyNoInteractions(goodsDao);
    }

    @Test
    @DisplayName("闸开着 + 普通单 ⇒ **照取消**（跳过不是无差别停摆）")
    void gateOn_stillCancelsNormalOrders() {
        givenGateOn();
        givenCandidates(List.of(row(NORMAL_ORDER, "")));

        job.advance();

        verify(orderDao).cancelIfUnpaid(NORMAL_ORDER);
    }

    // ==================== 三、同一轮里混着来：跳过的是「这一条」不是「这一轮」 ====================

    @Test
    @DisplayName("⚠️ 一轮里 SIM- 与普通单并存 ⇒ 只跳前者 —— 钉的是 `continue` 而不是 `return`")
    void gateOn_mixedRound_skipsOnlyTheMarkedOne() {
        givenGateOn();
        // ⚠️ 顺序有意义：带标记的排**前**。若跳过分支写成 return，后面的普通单也会一起丢，
        //    本用例就会红 —— 这正是它要钉的那件事。
        givenCandidates(List.of(row(ATTACK_ORDER, ""), row(NORMAL_ORDER, "")));

        job.advance();

        verify(orderDao).cancelIfUnpaid(NORMAL_ORDER);
        verify(orderDao, never()).cancelIfUnpaid(ATTACK_ORDER);
    }

    // ==================== 四、判据本身：纯函数，边界逐条钉 ====================

    @Test
    @DisplayName("isAttackData：前缀命中 / Source 命中（忽略大小写与空白）/ 都不是 ⇒ false")
    void predicate_recognisesBothCarriers() {
        assertThat(OrderTimeoutCancelJob.isAttackData(ATTACK_ORDER, null))
                .as("载体①：OrderId 前缀").isTrue();
        assertThat(OrderTimeoutCancelJob.isAttackData("SIM-X", ""))
                .as("载体①：Source 是列默认空串也算").isTrue();
        assertThat(OrderTimeoutCancelJob.isAttackData(NORMAL_ORDER, "attack"))
                .as("载体②：Source 相等").isTrue();
        assertThat(OrderTimeoutCancelJob.isAttackData(NORMAL_ORDER, "  ATTACK  "))
                .as("载体②：大小写与前后空白都不计较").isTrue();

        assertThat(OrderTimeoutCancelJob.isAttackData(NORMAL_ORDER, "")).as("空串 = 列默认值，不是标记").isFalse();
        assertThat(OrderTimeoutCancelJob.isAttackData(NORMAL_ORDER, null)).as("null 不是标记").isFalse();
        assertThat(OrderTimeoutCancelJob.isAttackData(NORMAL_ORDER, "seed")).as("种子数据不是演练数据").isFalse();
        assertThat(OrderTimeoutCancelJob.isAttackData(null, null)).as("两半都缺 ⇒ 不是").isFalse();
    }

    @Test
    @DisplayName("isAttackData：前缀必须**从左起** —— 串中间出现 SIM- 不算（钉 startsWith 而不是 contains）")
    void predicate_prefixMustBeAtTheStart() {
        assertThat(OrderTimeoutCancelJob.isAttackData("ORDER-SIM-1", null))
                .as("真正的演练单是 `SIM-` **开头**；把 contains 写进来的话，正常单里带这三个字符的也会被跳过")
                .isFalse();
    }

    // ==================== 五、与写入链路合拢（边界补笔，2026-10-14）====================

    /**
     * ⚠️ <b>本用例是「补写入通道」这一笔与② 的**接缝**</b>：commit B 落地时，
     * {@code Source='attack'} 那一半谓词**匹配不到任何行** —— {@code order.Source} 列在，
     * 却没有任何路径往里写它。补笔给订单域补了写入通道（{@code /order/orderAdd} 可选入参 +
     * {@code OrderDao.insertOrder} 写该列 + {@code AttackEngine} 下单场景传 {@code 'attack'}）。
     *
     * <p>这里用**真 {@code OrderDao}**（{@code JdbcTemplate} 替身）从 JDBC 收口**读回**「库里那一列
     * 会是什么」，再拿它喂给 {@link OrderTimeoutCancelJob#isAttackData} —— 订单号
     * <b>不带 {@code SIM-} 前缀</b>，所以命中只可能来自 {@code Source} 那一半。
     * 断言的是「写入侧真的产出了跳行谓词认得的那个值」，而不是「谓词字面量对」。
     */
    @Test
    @DisplayName("与写入链路合拢：insertOrder 落的 Source 值，喂给 isAttackData ⇒ true（订单号无前缀）")
    void sourceWrittenByTheDao_aloneIsEnoughForThePredicate() throws Exception {
        String written = insertAndCaptureSource("attack");

        assertThat(OrderTimeoutCancelJob.isAttackData(NORMAL_ORDER, written))
                .as("订单号 " + NORMAL_ORDER + " 无 SIM- 前缀 ⇒ 命中只可能来自 Source 列").isTrue();
        assertThat(OrderTimeoutCancelJob.isAttackData(NORMAL_ORDER, ""))
                .as("阴性对照：不传 Source 的真实下单落空串 ⇒ 不该被跳过").isFalse();
    }

    /** 跑一次 {@code OrderDao.insertOrder}，从 JDBC 上读回绑到 {@code Source} 位的值（第 11 个 {@code ?}）。 */
    private static String insertAndCaptureSource(String source) throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        // OrderDao 走 JdbcClient ⇒ 落到 JdbcTemplate.update(PreparedStatementCreator) 单参重载（见 CommentDaoSourceColumnTest 的说明）。
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenAnswer(inv -> {
            PreparedStatementCreator creator = inv.getArgument(0);
            creator.createPreparedStatement(connection);
            return 1;
        });

        // ⚠️ 第 12 参 = BatchId（commit 10 新增）。这里**必须给非空值**，不能图省事传 null：
        //    null 会让 Spring 的 StatementCreatorUtils 走 setNull 分支，而那条分支要去问
        //    `ps.getConnection().getMetaData()` 才能定 SQL 类型 —— 本用例的 PreparedStatement
        //    是替身，getConnection() 回 null ⇒ 直接把测试打成 NPE（与断言无关的驱动元数据）。
        //    同一坑见 `OrderAddSourceColumnSmokeTest` 类注释「所有入参都传非空值」那一段。
        //    本用例只关心 Source 位，批次号取什么都行，但它**不能是 null**。
        new OrderDao(jdbcTemplate).insertOrder(new OrderDao.OrderRow(
                "ORDER-WRITE-1", "CUST-1", "MERCH-1", 5f, 1, "可乐", "", 0, 0, 0, source, "BATCH-WRITE-1"));

        ArgumentCaptor<String> bound = ArgumentCaptor.forClass(String.class);
        verify(statement).setString(eq(11), bound.capture());   // `Source` 是列清单第 11 列 ⇒ 第 11 个 ?
        return bound.getValue();
    }

    // ==================== 桩 ====================

    /** 闸开着 —— ② 的那一档。 */
    private void givenGateOn() {
        ReflectionTestUtils.setField(job, "timeoutMs", 60000L);
        ReflectionTestUtils.setField(job, "skipAttackData", true);
    }

    /** 把候选名单钉成这几行（{@code selectUnpaidExpired(60L)} —— 阈值由 timeoutMs 切出）。 */
    private void givenCandidates(List<Map<String, Object>> rows) {
        when(orderDao.selectUnpaidExpired(60L)).thenReturn(rows);
        for (Map<String, Object> r : rows) {
            // 取消会命中（=1）；本文件只关心「调没调」，返回值在跳过的用例里用不上。
            when(orderDao.cancelIfUnpaid(String.valueOf(r.get("OrderId")))).thenReturn(1);
        }
        // 默认口径：StockDeducted 回落 0 ⇒ 即便走到释放也是「跳过回补」，不会去碰 goodsDao。
        when(orderDao.selectStockDeducted(anyString())).thenReturn(0);
    }

    /** 一行候选：{@code OrderId} + {@code Source} —— 正好是 {@code selectUnpaidExpired} 的投影两列。 */
    private static Map<String, Object> row(String orderId, String source) {
        Map<String, Object> cells = new HashMap<>();
        cells.put("OrderId", orderId);
        cells.put("Source", source);
        return cells;
    }
}
