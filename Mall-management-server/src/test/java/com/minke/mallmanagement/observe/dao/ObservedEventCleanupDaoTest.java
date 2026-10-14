package com.minke.mallmanagement.observe.dao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ObservedEventCleanupDao} 的**纯替身单测**（块 7 · commit 8b）—— 钉两件事：
 * ① 清理**真的同时下发两种谓词**（用户 2026-10-14 批文的判据原文：「清完**这两种谓词**的数据全消失」，
 * 而「只按一种」是本笔最可能的失效形态）；② 保留期是**两档 OR**（只写一档会让另一档永不清理）。
 *
 * <h2>【为什么这两件事只能在这一层验】</h2>
 * 谓词那一半在真库上验得到（本笔的验收就是跑一次端点、看两种谓词的行全没），
 * 但**保留期那一半在真库上验不出**：{@code observed_event} 的写入方要到 8c（RabbitMQ consumer）
 * 才接 ⇒ 表是空的、job 每轮删 0 行（见 {@code ObservedEventCleanupJob} 类注释第二节）。
 * 把「两档 OR 的字面与两个绑定参数」钉在这里，是 8c 接线前唯一能证它形状正确的地方。
 *
 * <h2>【⚠️ 桩法的前提是两条**实测出来的** JdbcClient 事实（2026-10-14，spring-jdbc 6.2.6）】</h2>
 * 逐字读 {@code DefaultJdbcClient} 源码得到，**别照猜**：
 * <ul>
 *   <li><b>写（位置参数）</b>：{@code .param(x)} / {@code .params(a, b, …)} 的 {@code update()}
 *       落到 {@code JdbcTemplate.update(PreparedStatementCreator)} **单参**重载
 *       （6.2.6 源码 {@code DefaultJdbcClient#update()} 的 else 支）。</li>
 *   <li><b>读（位置参数）</b>：{@code .query(T.class).list()} 落到
 *       {@code JdbcTemplate.query(String, RowMapper, Object...)} —— **不是** {@code (pss, rowMapper)}。
 *       ⚠️ 这里正是本文件第一版的坑：桩了 {@code query(PreparedStatementCreator, RowMapper)}，
 *       于是两次 {@code information_schema} 现查**静默拿到空表** ⇒ 一条 DELETE 都不下发，
 *       而用例只在「条数不对」上红（若恰好断言写得松，就会全绿）。{@code single()} 也走这条路
 *       （{@code MappedQuerySpec#single()} = {@code requiredSingleResult(list())}）。</li>
 * </ul>
 * 同一条事实在 {@code CommentDaoSourceColumnTest} 的类注释里有另一份记录（那里只有写）。
 * ⚠️ <b>桩错重载 ⇒ 静默全绿</b>，故每个用例都先断言「返回值真是我桩的那个数」，那几句不是装饰。
 *
 * <h2>【⚠️ 本文件验不到的】</h2>
 * ① MySQL 收不收得下（要真库，由本笔的端点实测兜）；② 「动态发现」这件事本身 ——
 * 本文件里 {@code information_schema} 的返回值是我喂的，故它证的是「拿到列清单**之后**怎么做」；
 * 列清单怎么来（那条 SQL 的列名与库名口径）在下面用例里连带钉住。
 */
class ObservedEventCleanupDaoTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObservedEventCleanupDao dao = new ObservedEventCleanupDao(jdbcTemplate);
    private final Connection connection = mock(Connection.class);

    /** 每次下发的语句的 SQL 原文，按调用顺序（与 {@link #preparedBinds} 对齐）。 */
    private final List<String> preparedSql = new ArrayList<>();

    /** 每条语句绑定的值（形如 {@code "1=SIM-%"}，按占位符序）。 */
    private final List<List<String>> preparedBinds = new ArrayList<>();

    /**
     * 让每次 {@code prepareStatement} 都返回一个**全新的**替身语句，并把它收到的 {@code setXxx}
     * 记进 {@link #preparedBinds}。
     *
     * <p>⚠️ 用一个自定义 {@code Answer} 而不是逐个 {@code when(ps).setString(...)}：本 DAO 的
     * 占位符里既有 {@code String}（{@code 'SIM-%'} / {@code 'attack'}）又有 {@code Integer}
     * （两档保留期天数），而 Spring 对「未知 sqlType」的不同类型走的 setter 不同 ⇒ 写死某一个
     * setter 会让另一类**静默不记录**（那正是本仓最怕的那种坏测试）。
     */
    @BeforeEach
    void wireConnection() throws Exception {
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            preparedSql.add(sql);
            List<String> bound = new ArrayList<>();
            preparedBinds.add(bound);
            return mock(PreparedStatement.class, invocation -> {
                Object[] args = invocation.getArguments();
                if (invocation.getMethod().getName().startsWith("set")
                        && args.length == 2 && args[0] instanceof Integer idx) {
                    bound.add(idx + "=" + args[1]);
                }
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
        });
    }

    // ==================== ① 两种谓词 ====================

    @Test
    @DisplayName("两种谓词都下发：有 OrderId 列的表按 SIM- 前缀删、有 Source 列的表按 attack 删")
    void bothPredicatesAreIssued() {
        // deleteAttackData 的顺序：先 columnsOf("OrderId")、后 columnsOf("Source")。
        stubColumns(List.of("comment", "order", "order_item"), List.of("comment", "drill_record", "order"));
        // 删除顺序：子表优先 ⇒ order_item(3) → comment(0) → order(3)；再 comment(0) → [drill_record 被排除] → order(3)
        stubDeletes(3, 0, 3, 0, 3);

        Map<String, Integer> deleted = dao.deleteAttackData();

        List<Integer> orderIdDeletes = indicesOfSqlContaining("`OrderId` LIKE ?");
        List<Integer> sourceDeletes = indicesOfSqlContaining("`Source` = ?");
        assertThat(orderIdDeletes).as("三张有 OrderId 列的表各一条（子表优先只改顺序、不改条数）").hasSize(3);
        assertThat(sourceDeletes).as("三张有 Source 列的表里 drill_record 被排除 ⇒ 只两条").hasSize(2);
        assertThat(orderIdDeletes).allSatisfy(i -> assertThat(preparedBinds.get(i))
                .as("谓词一的绑定值就是 AttackEngine 的那个前缀字面")
                .containsExactly("1=SIM-%"));
        assertThat(sourceDeletes).allSatisfy(i -> assertThat(preparedBinds.get(i))
                .as("谓词二的绑定值是 AttackEngine 的那个标记字面")
                .containsExactly("1=attack"));

        assertThat(deleted).as("只含真删了行的表：comment 两种谓词都删 0 行 ⇒ 不出现；"
                        + "order 被两种谓词各删一次 ⇒ 3 + 3 相加")
                // ⚠️ 用 **any-order** 版：`Map.of(...)` 的迭代序**不保证**，而 `containsExactlyEntriesOf`
                //    是**逐位比较**的 ⇒ 第一版就是这样在整仓跑里**随机红**的（报错原文「same elements but
                //    not in the same order, at index 0」⇒ 同一份代码两次运行可以一次绿一次红）。
                //    要断言的是**计数**，不是 DAO 内部那张 map 的遍历序 —— 那是实现细节。
                .containsExactlyInAnyOrderEntriesOf(Map.of("order_item", 3, "order", 6));

        // 顺带把「作用面从哪来」钉住：两次现查各按列名问一次（写死表清单就永远查不到第 19 张表）。
        List<Integer> discoveries = indicesOfSqlContaining("information_schema.COLUMNS");
        assertThat(discoveries).as("两次现查：先 OrderId、后 Source").hasSize(2);
        assertThat(preparedBinds.get(discoveries.get(0))).containsExactly("1=OrderId");
        assertThat(preparedBinds.get(discoveries.get(1))).containsExactly("1=Source");
        assertThat(preparedSql.get(discoveries.get(0)))
                .as("库名用 DATABASE()（与 .sql 迁移段同口径），不是写死 mall_management_sql")
                .contains("TABLE_SCHEMA = DATABASE()");
    }

    @Test
    @DisplayName("子表优先：order_item 的 DELETE 下在 order 之前（今天无外键、不影响结果，是给将来留的）")
    void childrenAreDeletedBeforeParent() {
        stubColumns(List.of("order", "order_item"), List.of());
        stubDeletes(3, 3);

        dao.deleteAttackData();

        int child = firstIndexOfSqlContaining("mall_management_sql.`order_item`");
        int parent = firstIndexOfSqlContaining("mall_management_sql.`order`");
        assertThat(child).as("两条都真的下发了（否则下面的比较是在比 -1）").isNotNegative();
        assertThat(parent).isNotNegative();
        assertThat(child).as("明细先删 —— 将来谁给子表补了 ON DELETE RESTRICT 的外键，顺序反了会当场报错")
                .isLessThan(parent);
    }

    @Test
    @DisplayName("排除清单：drill_record 既不被 OrderId 谓词碰、也不被 Source 谓词碰")
    void drillRecordIsNeverSwept() {
        // 故意把它两列都喂进去：它是「演练这件事本身」的记录，与「演练污染出来的数据」语义相反。
        stubColumns(List.of("drill_record"), List.of("drill_record"));
        stubDeletes();

        Map<String, Integer> deleted = dao.deleteAttackData();

        assertThat(indicesOfSqlContaining("DELETE")).as("一条 DELETE 都不该下发（含 drill_record 的那条）")
                .isEmpty();
        assertThat(deleted).as("全部被排除 ⇒ 空结果（不是「删了 0 行」）").isEmpty();
    }

    // ==================== ② 保留期 ====================

    @Test
    @DisplayName("保留期是**一条语句两档 OR**，两个天数都走绑定（不拼字符串）")
    void pruneCoversBothRetentionTiers() {
        stubUpdateReturns(7);

        int n = dao.pruneObservedEvents(3, 30);

        assertThat(n).as("删掉的行数原样透传（返回 0 会让「本笔正常删 0 行」与「桩错」无法区分）")
                .isEqualTo(7);
        List<Integer> deletes = indicesOfSqlContaining("mall_management_sql.observed_event");
        assertThat(deletes).as("两档合成一条语句（拆两条会产生「一条成一条败」的中间态）").hasSize(1);
        int i = deletes.get(0);
        assertThat(preparedSql.get(i))
                .as("攻击批次一档（BatchId 非 NULL）")
                .contains("`BatchId` IS NOT NULL AND `OccurredAt` < NOW() - INTERVAL ? DAY")
                .as("日常一档（BatchId 为 NULL）—— 只写一档会让另一档永不清理，表无限长")
                .contains("`BatchId` IS NULL AND `OccurredAt` < NOW() - INTERVAL ? DAY")
                .as("两档是 OR，不是 AND（AND 会让两档互相抵消、永远命中 0 行，且没有任何报错）")
                .contains(" OR ");
        assertThat(preparedBinds.get(i))
                .as("按调用方给的顺序绑：先攻击、后日常（反了就是把 3 天与 30 天对调，静默删错行）")
                .containsExactly("1=3", "2=30");
    }

    @Test
    @DisplayName("countObservedEvents：读一行 COUNT 并透传出来")
    void countReadsSingleValue() {
        stubQueryReturns(List.of(List.of(42L)));

        assertThat(dao.countObservedEvents()).as("清前/清后要读的就是这个数").isEqualTo(42L);
        assertThat(firstIndexOfSqlContaining("SELECT COUNT(*) FROM mall_management_sql.observed_event"))
                .as("读的是新表本表").isNotNegative();
    }

    // ==================== 桩与执行 ====================

    /** 第一次出现「SQL 含 {@code needle}」的下标（-1 = 没下发）。 */
    private int firstIndexOfSqlContaining(String needle) {
        List<Integer> all = indicesOfSqlContaining(needle);
        return all.isEmpty() ? -1 : all.get(0);
    }

    /** 所有「SQL 含 {@code needle}」的下标，按下发顺序。 */
    private List<Integer> indicesOfSqlContaining(String needle) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < preparedSql.size(); i++) {
            if (preparedSql.get(i).contains(needle)) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * 喂 {@code information_schema} 那两次现查的结果（按 {@link ObservedEventCleanupDao#deleteAttackData}
     * 的调用顺序：先 {@code OrderId}、后 {@code Source}）。
     */
    private void stubColumns(List<String> forOrderId, List<String> forSource) {
        stubQueryReturns(List.of(forOrderId, forSource));
    }

    /** 按调用顺序喂查询结果（用完之后返回空表 —— 那正是 Mockito 的默认返回值，别让它悄悄生效）。 */
    private void stubQueryReturns(List<?> perCall) {
        // ⚠️ 桩的是 varargs 重载（理由见类注释）：查询走的是
        //    `classicOps.query(sql, rowMapper, indexedParams.toArray())`。
        Deque<?> queue = new ArrayDeque<>(perCall);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
            recordSqlAndParams(inv);
            Object next = queue.poll();
            return next == null ? new ArrayList<>() : next;
        });
    }

    /** 按调用顺序喂每条 DELETE 的返回行数（用完之后一律 0）。 */
    private void stubDeletes(Integer... rows) {
        Deque<Integer> queue = new ArrayDeque<>(Arrays.asList(rows));
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenAnswer(inv -> {
            ((PreparedStatementCreator) inv.getArgument(0)).createPreparedStatement(connection);
            Integer n = queue.poll();
            return n == null ? 0 : n;
        });
    }

    private void stubUpdateReturns(int rows) {
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenAnswer(inv -> {
            ((PreparedStatementCreator) inv.getArgument(0)).createPreparedStatement(connection);
            return rows;
        });
    }

    /**
     * 把一次查询的 SQL 与参数记进台账。
     *
     * <p>⚠️ 与写那一支不同：这里**不能**执行 creator —— 这个重载的第一个实参就是 SQL 字符串本身，
     * 参数跟在它后面（varargs），没有 {@code PreparedStatementCreator} 可执行。
     * <p>⚠️ 又一条实测：{@code InvocationOnMock.getArguments()} 把 varargs **摊平**了
     * （不是「第三个实参是一个数组」）—— 本文件第一版按「{@code getArgument(2)} 是 Object[]」写，
     * 直接 {@code String cannot be cast to Object[]}（参数为空时则是下标越界）。
     * ⇒ 从**下标 2 起**逐个取，正好对应第 1、2… 个占位符。
     */
    private void recordSqlAndParams(InvocationOnMock inv) {
        preparedSql.add(inv.getArgument(0));
        Object[] args = inv.getArguments();
        List<String> bound = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            bound.add((i - 1) + "=" + args[i]);
        }
        preparedBinds.add(bound);
    }
}
