package com.minke.mallmanagement.comment.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code comment.Source} 这列**真的被写进 INSERT 了** —— C3 块 7 · commit B · ①（新发现 ①）。
 *
 * <p>被测的是 {@link CommentDao#insertComment} 的一件事实：它拼出来的那条 INSERT
 * **列清单里有 {@code `Source`}、占位符是 13 个**，且末位绑的值就是调用方给的那个。
 * 加这一列之前，「①（{@code Source='attack'}）」在**写入侧**是空的 ——
 * 攻击引擎就算传了 {@code Source} 也无处落。
 *
 * <h2>【为什么能在这个粒度上验 —— 一条实测出来的 JdbcClient 事实】</h2>
 * {@code CommentDao} 用的是 {@code JdbcClient.create(jdbcTemplate)}（{@code CommentDao:99}）。
 * <b>位置参数</b>那一支（{@code .params(a, b, …)}，本方法用的就是它）落到的是
 * {@code JdbcTemplate.update(PreparedStatementCreator)} **单参**重载 ——
 * 逐字见 spring-jdbc 6.2.6 {@code DefaultJdbcClient.StatementSpec#update()}：
 * {@code classicOps.update(statementCreatorForIndexedParams())}。
 * ⇒ 桩住这一个重载，就能把creator接出来执行，从 {@code Connection.prepareStatement(sql)} 上
 * **读到 SQL 原文**、从 {@code PreparedStatement.setXxx(i, v)} 上**读到每个绑的值**。
 *
 * <p>⚠️ <b>别把这一层换成「读字段 / 反射拿私有 SQL」</b>：那样验的是「我们写了什么字面量」，
 * 而这条测试要证的是「JDBC 真的收到了什么」——两者在参数归一化（{@code null → ''}）这类
 * 地方会**分岔**，那正是本文件第二个用例要钉的。
 *
 * <h2>【为什么纯替身、不起 Spring、不连库】</h2>
 * 与 {@code CommentServiceReviewLockTest} 同一分层口径：本文件证的是**这条语句的形状**，
 * 「MySQL 收不收得下」是另一件事（由 {@code CommentSmokeTest} / 真库探针管）。
 * 纯替身还让「空串归一」这个分支**必然**走到 —— 真库上它被 {@code NOT NULL DEFAULT ''} 兜着，
 * 反而看不出归一有没有生效。
 *
 * <p>⚠️ <b>所有形参都传非空值</b>（{@code ParentId}/{@code ReplyToId}/{@code ProductCode} 也是）：
 * 这样每个值都走 {@code setValue} 而不是 {@code setNull} —— 后者会去问
 * {@code ps.getConnection().getMetaData()}，把测试拖进与断言无关的驱动元数据里。
 * 唯一被有意留空的只有 {@code source}（本来就是被测的那一档，且它在 DAO 里已被归一成空串）。
 */
class CommentDaoSourceColumnTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CommentDao dao = new CommentDao(jdbcTemplate);

    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);

    /** 13 个形参的**全非空**取值（理由见类注释末段）。 */
    private static final String TARGET_TYPE = "goods";
    private static final String TARGET_ID = "76";
    private static final long PARENT_ID = 5L;
    private static final long REPLY_TO_ID = 6L;
    private static final String KIND = "review";
    private static final String AUTHOR = "1111";
    private static final String CONTENT = "这条是测试内容";
    private static final String PRODUCT_CODE = "A1";

    // ==================== 一、SQL 形状：列真的在、占位符真的够 ====================

    @Test
    @DisplayName("拼出来的 INSERT **列清单里有 `Source`**，且占位符恰为 13 个（列数 = 值数）")
    void insertSqlCarriesTheSourceColumn() throws Exception {
        String sql = runAndCaptureSql("attack");

        assertThat(sql)
                .as("表名带反引号（comment 与关键字同形，全类口径）")
                .startsWith("INSERT INTO `comment` ")
                .contains("`Source`")
                .as("列清单的收尾就是 Source —— 它加在**表尾**（本仓加列的规矩）")
                .contains("`IsMerchantFirst`, `Source`)");
        assertThat(sql.chars().filter(c -> c == '?').count())
                .as("13 列必须配 13 个占位符：少一个就在真库上炸，多一个绑不上")
                .isEqualTo(13);
    }

    // ==================== 二、末位绑的值：原样传 / 空与 null 归一成空串 ====================

    @Test
    @DisplayName("Source='attack' ⇒ 第 13 个参数就是 \"attack\"（原样，不加工）")
    void sourceIsBoundVerbatim() throws Exception {
        runAndCaptureSql("attack");

        verify(statement).setString(13, "attack");
    }

    @Test
    @DisplayName("Source=null ⇒ 第 13 个参数是**空串**而不是 NULL（列是 NOT NULL DEFAULT ''）")
    void nullSourceIsNormalisedToEmptyString() throws Exception {
        runAndCaptureSql(null);

        verify(statement).setString(13, "");
    }

    @Test
    @DisplayName("Source=\"  \"（纯空白）⇒ 同样归成空串，且**前后空白被 trim**")
    void blankSourceIsNormalisedAndTrimmed() throws Exception {
        runAndCaptureSql("   ");

        verify(statement).setString(13, "");
    }

    @Test
    @DisplayName("Source=\" attack \" ⇒ trim 后落库（存的不是带空白的那个串）")
    void surroundingWhitespaceIsTrimmed() throws Exception {
        runAndCaptureSql(" attack ");

        verify(statement).setString(13, "attack");
    }

    // ==================== 桩与执行 ====================

    /**
     * 跑一次 {@code insertComment} 并把 SQL 原文接出来。
     *
     * <p>⚠️ 桩的是 {@code update(PreparedStatementCreator)} **单参**重载（理由见类注释）——
     * 换成 {@code update(String, Object...)} 那条桩，本文件会**静默全绿**（真调用落到别的重载、
     * 没人接，Mockito 默认返回 0），所以下面 {@link #insertIsReached} 那两条断言不是装饰：
     * 它们钉住「确实走到了这个重载、且返回值真的透传出来」。
     */
    private String runAndCaptureSql(String source) throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenAnswer(inv -> {
            PreparedStatementCreator creator = inv.getArgument(0);
            creator.createPreparedStatement(connection);
            return 1;
        });

        int rows = dao.insertComment(TARGET_TYPE, TARGET_ID, PARENT_ID, REPLY_TO_ID, KIND, AUTHOR,
                /* authorRole */ 1, /* anonymous */ false, /* rating */ 5, CONTENT,
                PRODUCT_CODE, /* merchantFirst */ false, source);

        insertIsReached(rows);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        return sql.getValue();
    }

    /** 「这次调用真的走到了被测的那条重载、返回值也真的透传」——上面每个用例的共同前提。 */
    private void insertIsReached(int rows) {
        assertThat(rows).as("返回行数原样透传（0 会让本文件所有断言变成空跑）").isEqualTo(1);
        verify(jdbcTemplate).update(any(PreparedStatementCreator.class));
    }
}
