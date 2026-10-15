package com.minke.mallmanagement.smoke;

import com.minke.mallmanagement.shared.web.TraceIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code order.Source} 这列**真的被写进 INSERT 了** —— C3 块 7 · 边界补笔（2026-10-14）。
 *
 * <p>被测的是「{@code /order/orderAdd} 的可选 {@code Source} 入参 → {@code OrderService} →
 * {@code OrderDao.insertOrder} → JDBC」这**一条链路**的四件事：不传 / 传 / 空白 / 超长。
 * 补这一笔之前，{@code order.Source} 列存在（块 5 给 11 张表加的那一列，
 * {@code mall_management_sql.sql:1167}）却**没有任何写入点** —— {@code OrderTimeoutCancelJob}
 * 的 {@code skip-attack-data} 谓词里 {@code Source='attack'} 那一半匹配不到行。
 *
 * <p>⚠️ <b>C3 块 7 · commit 10（2026-10-15）在同一列清单里又加了一列 {@code BatchId}</b>
 * （第 12 列，值取自 {@code TraceIdFilter.currentBatch()}）—— 本类因此多了两件事：
 * ① 「占位符 11 个」那条断言改成 12、{@code Source} 的后面多一个 {@code BatchId}；
 * ② 多一个用例钉「头 → 第 12 个 ?」这条链路。本类的名字没改（它仍以 {@code Source} 为主角），
 * 但**批次号那一列的存在让「所有入参都传非空值」这条纪律从「方便」变成了「必须」**
 * —— 理由见 {@link #BATCH} 的注释（不带头的 {@code null} 会走 {@code setNull}，撞上替身连接）。
 *
 * <h2>【怎么在这个切片里「读到 SQL 与绑值」】</h2>
 * 切片里 {@code JdbcTemplate} 是替身（{@code AbstractSmokeTest} 上那个 {@code @MockitoBean}），
 * 而 {@code OrderDao} 的写侧走 {@code JdbcClient.update()} ⇒ 落到
 * {@code JdbcTemplate.update(PreparedStatementCreator)} <b>单参重载</b>。于是把这个重载桩成
 * 「接出 creator、拿真 {@code Connection} 执行」即可：从 {@code Connection.prepareStatement(sql)}
 * 上**读 SQL 原文**、从 {@code PreparedStatement.setString(i, v)} 上**读绑进去的值**。
 * 这与 {@code CommentDaoSourceColumnTest} 是**同一手法**（那里用的是纯替身，这里用的是切片
 * ——因为本笔要的是**整条链路**通，不只是 DAO 那一句的形状）。
 *
 * <p>⚠️ <b>为什么按 SQL 前缀挑那一条语句</b>：一次成功的下单在切片里会发 <b>4 条</b> update
 * （订单行 + 明细行 + 该件 {@code goods_sku.Stock} 扣减 + 2d 的整单 {@code StockDeducted} 标记），
 * 只有**第一条**是订单行。{@code insertOrder} 的 SQL 以
 * {@code "INSERT INTO mall_management_sql.order "}（表名后跟空格）开头，而明细那条是
 * {@code "INSERT INTO mall_management_sql.`order_item` "}（表名后跟反引号）⇒ 前缀区分得开。
 *
 * <p>⚠️ <b>所有入参都传非空值</b>（{@code CustomerId} / {@code MerchantId} / {@code ProductInformation} …）：
 * 这样每个值都走 {@code setValue} 而不是 {@code setNull} —— 后者会去问
 * {@code ps.getConnection().getMetaData()}，把测试拖进与断言无关的驱动元数据里。
 * 唯一被有意留空的只有 {@code Source} 那一档（它在 DAO 里已被归一成空串）。
 */
class OrderAddSourceColumnSmokeTest extends AbstractSmokeTest {

    /** {@code insertOrder} 的 SQL 前缀 —— 一次下单共 4 条 update，只有订单行以它为头（理由见类注释）。 */
    private static final String ORDER_INSERT_PREFIX = "INSERT INTO mall_management_sql.order ";

    /** {@code Source} 是订单列清单里的第 11 列 ⇒ 它绑在第 11 个 {@code ?}（{@code CreateTime} 是 {@code NOW()}、不占位）。⚠️ commit 10 的 {@code BatchId} 排在 {@code Source} **之后** ⇒ 这个下标**不受影响**，变的是总占位符数（11 → 12）。 */
    private static final int SOURCE_PARAM_INDEX = 11;

    /** 常量值 = 列宽 {@code varchar(16)}；16 放行 / 17 拒，两条边界各钉一次。 */
    private static final int MAX_SOURCE = 16;

    /** 固定订单号（与其余 order smoke 同款：{@code @MockitoBean} 每个用例后重置，用例内只发一次请求）。 */
    private static final String ORDER = "SMOKE-SRC-ORDER-1";

    /** {@code BatchId} 是列清单里的第 12 列 ⇒ 它绑在第 12 个 {@code ?}（排在 {@code Source} **之后**）。 */
    private static final int BATCH_PARAM_INDEX = 12;

    /**
     * 演练批次号 —— 本类每个用例都**带上** {@code X-Drill-Batch} 头发请求（C3 块 7 · commit 10 起必带）。
     *
     * <p>⚠️ <b>它不是可选项，是本类能不能跑起来的前提</b>：commit 10 把 {@code BatchId} 接进了
     * INSERT（{@link com.minke.mallmanagement.order.dao.OrderDao.OrderRow} 的第 12 参，值取自
     * {@link TraceIdFilter#currentBatch()}）。**不带这个头时它恒为 {@code null}** ⇒ Spring 的
     * {@code StatementCreatorUtils} 走 {@code setNull} 分支 ⇒ 那条分支得先问
     * {@code ps.getConnection().getMetaData()} 才定得出 SQL 类型，而本切片的
     * {@code PreparedStatement} 是**替身**、{@code getConnection()} 回 {@code null} ⇒
     * <b>整条链路 NPE、6 个用例一起红</b>（正是类注释「所有入参都传非空值」那条纪律要躲的坑，
     * 2026-10-15 实测踩过一次）。带上真实头通道既躲开它，又顺带把这条链路测穿了。
     *
     * <p>⚠️ 取值必须落进 {@link TraceIdFilter} 的白名单 {@code [A-Za-z0-9_-]{8,64}}，否则头会被丢掉
     * —— 那时又回到 {@code null}，症状与不带头一模一样。
     */
    private static final String BATCH = "SMOKE-BATCH-0001";

    // ==================== 一、SQL 形状：列真的在、占位符真的够 ====================

    @Test
    @DisplayName("订单 INSERT **列清单里有 `Source`**，占位符 12 个，且排在 CreateTime 之前")
    void orderInsertCarriesTheSourceColumn() throws Exception {
        Captured capture = runAndCaptureSource(Optional.empty());

        assertThat(capture.sql())
                .as("表名沿用既有形态（无引号，见 OrderDao 类注释），但新加的列带反引号")
                .startsWith(ORDER_INSERT_PREFIX)
                .contains("`Source`")
                .as("Source 排在 BatchId 之前、BatchId 又排在 CreateTime **之前** —— CreateTime 是 VALUES 里唯一写死 NOW() 的非参列")
                .contains("`Source`, BatchId, CreateTime)");
        assertThat(capture.sql().chars().filter(c -> c == '?').count())
                .as("12 列带参（CreateTime 是 NOW()）⇒ 必须恰 12 个占位符：少一个真库上炸，多一个绑不上")
                .isEqualTo(12);
    }

    @Test
    @DisplayName("带 `X-Drill-Batch` 头 ⇒ 批次号**原样**绑在第 12 个 ? 上（commit 10 接上的那条链路）")
    void drillBatchHeader_landsOnTheBatchParam() throws Exception {
        Captured capture = runAndCaptureSource(Optional.empty());

        assertThat(capture.boundBatch())
                .as("这条断言穿的是整条链：头 → TraceIdFilter 的 MDC → OrderService → OrderRow.batchId → 第 12 个 ?")
                .isEqualTo(BATCH);
    }

    // ==================== 二、四档入参：不传 / 传 / 空白 / 超长 ====================

    @Test
    @DisplayName("不传 Source（真实前端的样子）⇒ 落**空串**，与加这一参之前逐字节同行为")
    void notPassed_landsAsEmptyString() throws Exception {
        Captured capture = runAndCaptureSource(Optional.empty());

        assertThat(capture.boundSource())
                .as("列是 NOT NULL DEFAULT '' ⇒ 不传就是空串，不是 NULL")
                .isEmpty();
    }

    @Test
    @DisplayName("Source='attack' ⇒ 原样落库（这正是攻击引擎的四个下单场景发的值）")
    void attackSource_landsVerbatim() throws Exception {
        Captured capture = runAndCaptureSource(Optional.of("attack"));

        assertThat(capture.boundSource()).isEqualTo("attack");
    }

    @Test
    @DisplayName("Source=纯空白 ⇒ 归一成空串（不是三个空格）")
    void blankSource_normalisedToEmptyString() throws Exception {
        Captured capture = runAndCaptureSource(Optional.of("   "));

        assertThat(capture.boundSource()).isEmpty();
    }

    @Test
    @DisplayName("Source=\" attack \" ⇒ trim 后落库（存的不是带空白的那个串）")
    void surroundingWhitespace_trimmed() throws Exception {
        Captured capture = runAndCaptureSource(Optional.of(" attack "));

        assertThat(capture.boundSource()).isEqualTo("attack");
    }

    @Test
    @DisplayName("Source=16 字符（恰等于列宽）⇒ **放行**，且整串落库 —— 边界上不许少放一格")
    void exactlyMaxLength_isAccepted() throws Exception {
        String sixteen = "a".repeat(MAX_SOURCE);

        Captured capture = runAndCaptureSource(Optional.of(sixteen));

        assertThat(capture.boundSource()).isEqualTo(sixteen);
    }

    @Test
    @DisplayName("Source=17 字符 ⇒ **拒**（200 + success:false + 文案），且**一条库都不写、锁都不取**")
    void overMaxLength_isRejectedBeforeAnyWrite() throws Exception {
        mockMvc.perform(baseRequest(ORDER).param("Source", "a".repeat(MAX_SOURCE + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("Source 最多 " + MAX_SOURCE + " 字符"));
        // ⚠️ 判据是「拒」不是「截断」：截断的话这一档会 success:true 且落 16 个字符 —— 本条钉死它不是。
        verify(jdbcTemplate, never()).update(any(PreparedStatementCreator.class));
        verify(distributedLockService, never()).runLocked(any(), any(), any());
    }

    // ==================== 桩与执行 ====================

    /** 一条订单行语句的「捕获物」：SQL 原文 + 真正绑到 {@code Source} / {@code BatchId} 位上的那两个值。 */
    private record Captured(String sql, String boundSource, String boundBatch) {
    }

    /**
     * 发一次 {@code orderAdd}、把订单 INSERT 的 SQL 与 {@code Source} 绑值接出来。
     *
     * @param source {@code Optional.empty()} = **不发**这个参数（真实前端）；否则按其值发。
     */
    private Captured runAndCaptureSource(Optional<String> source) throws Exception {
        Map<String, PreparedStatement> bySql = new LinkedHashMap<>();
        Connection connection = mock(Connection.class);
        // 每条语句一个独立替身，靠 SQL 记账 —— 这样断言只落在**订单行**那一条上，
        // 不会被同一轮里明细 / 扣减 / 标记三条 update 的绑值串扰。
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            bySql.put(sql, ps);
            return ps;
        });
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenAnswer(inv -> {
            PreparedStatementCreator creator = inv.getArgument(0);
            creator.createPreparedStatement(connection);
            return 1;
        });

        MockHttpServletRequestBuilder builder = baseRequest(ORDER);
        source.ifPresent(v -> builder.param("Source", v));
        mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("订单添加成功"));

        String sql = bySql.keySet().stream()
                .filter(s -> s.startsWith(ORDER_INSERT_PREFIX))
                .findFirst()
                .orElseThrow(() -> new AssertionError("这一轮里没有发订单行的 INSERT —— 整条链路没走到写库"));
        PreparedStatement orderStatement = bySql.get(sql);

        // ⚠️ 两条「真的走到了」的断言不是装饰：桩错重载（比如桩成 update(String, Object...)）
        //    会静默全绿 —— 真调用落到别的重载、没人接，bySql 会空，上面的 orElseThrow 兜住；
        //    而「绑值真的落在第 11 位上」这一条只能靠 ArgumentCaptor 式的读回。
        return new Captured(sql,
                boundStringAt(orderStatement, SOURCE_PARAM_INDEX),
                boundStringAt(orderStatement, BATCH_PARAM_INDEX));
    }

    /** 读回第 {@code index} 个 {@code ?} 上绑的字符串（非字符串位一律返回 {@code null}，本测试不关心）。 */
    private static String boundStringAt(PreparedStatement statement, int index) {
        try {
            org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(statement).setString(org.mockito.ArgumentMatchers.eq(index), captor.capture());
            return captor.getValue();
        } catch (Exception e) {
            throw new AssertionError("第 " + index + " 个 ? 上没有 setString —— 该位的列没被绑进这条语句", e);
        }
    }

    /** 除 {@code Source} 外全部固定；形参名逐字对齐 {@code OrderController.orderAdd} 的 {@code @RequestParam}。
     *  ⚠️ {@code X-Drill-Batch} 头**必带**（理由见 {@link #BATCH}）—— 它走的是**头通道**，不是
     *  {@code @RequestParam}，所以下面这一串 `.param(...)` 里找不到它，别以为漏了。 */
    private MockHttpServletRequestBuilder baseRequest(String orderId) {
        return post("/order/orderAdd")
                .header(TraceIdFilter.BATCH_HEADER, BATCH)
                .param("CustomerId", "SMOKE-CUST-1")
                .param("MerchantId", "SMOKE-MERCH-1")
                .param("OrderId", orderId)
                .param("Price", "5")
                .param("ProductInformation", "可乐:SMOKE-SRC-PC-1")
                .param("Images", "")
                .param("PayIf", "0")
                .param("DeliveryIf", "0")
                .param("ReceiveIf", "0")
                .param("Quantity", "1");
    }
}
