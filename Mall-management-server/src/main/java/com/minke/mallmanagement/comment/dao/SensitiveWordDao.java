package com.minke.mallmanagement.comment.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 敏感词库（C3 块 6 · 整改 commit 5）的数据访问出口 —— 只管 {@code sensitive_word}
 * <b>一张表</b>。契约与规则见 {@code docs/agent/context-packs/块6参考.md} §4.5（下称「方案」）。
 *
 * <h2>【⚠️ 本类**不是** {@code comment} 表的第二个出口】</h2>
 * 「加词后扫老评论」（方案 §4.5）要**读 / 写** {@code comment.Content}，但那一跳
 * **不在这里** —— 本类只回答「库里有哪些词」，扫描与覆写走 {@link CommentDao}
 * （{@code selectContentsContaining} / {@code updateContent}）。
 * 一个表一个出口是本域从 {@code CommentDao} 建起来就守的分工：{@code comment} 的写侧若有两个
 * 出口，「谁能改 {@code Content}」这件事就没有单一答案了。
 *
 * <h2>【SQL 不带 schema 前缀、时间列折文本 —— 两条都是新域口径】</h2>
 * 同 {@link CommentDao} / {@code ReportDao}：新域一律**裸表名**（老域的
 * {@code mall_management_sql.} 前缀是存量债，别照抄）；{@code created_at} 走
 * {@code DATE_FORMAT} 折成无时区文本，由前端按浏览器时区转。
 *
 * <h2>【⚠️ {@code Word} 有唯一键】</h2>
 * DDL 是 {@code UNIQUE KEY uk_sensitive_word (Word)} ⇒ 重复插入不会留下两行，而是**报错**
 * （{@code insertWord} 会抛，不是回 0）。所以「重复添加」这道闸必须在
 * {@link #selectByWord} 那一层先判，靠异常兜底只是第二道。
 */
@Repository
public class SensitiveWordDao {

    /**
     * 读投影 —— 只此一处（同 {@link CommentDao#COMMENT_COLUMNS} 的做法）。
     * ⚠️ 本表**没有** {@code Status} / {@code Enabled} 之类的列（DDL 就四列）⇒
     * 「删词」是真 {@code DELETE}，没有软删的一档。
     */
    private static final String WORD_COLUMNS =
            "`Id`, `Word`, DATE_FORMAT(`created_at`, '%Y-%m-%d %H:%i:%s') AS `CreatedAt`";

    private final JdbcClient jdbcClient;

    public SensitiveWordDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 读 ====================

    /** 分页取词（按 {@code Id} 升序 —— 词表是人工维护的小表，稳定序比「按时间」更好认）。 */
    public List<Map<String, Object>> selectWords(int limit, int offset) {
        return jdbcClient.sql("SELECT " + WORD_COLUMNS + " FROM `sensitive_word` "
                        + "ORDER BY `Id` ASC LIMIT ? OFFSET ?")
                .params(limit, offset).query().listOfRows();
    }

    /** 词条总数（列表端点回 {@code total} 用）。 */
    public long countWords() {
        Long n = jdbcClient.sql("SELECT COUNT(*) FROM `sensitive_word`").query(Long.class).single();
        return n == null ? 0L : n;
    }

    /**
     * 全表词条 —— <b>不过滤、不分页</b>，给「每条评论发表时跑一次命中」用。
     *
     * <p>⚠️ 这里刻意**不做缓存**：词表是小表（预置 10 条这个量级），一次 {@code SELECT Word}
     * 比一套「缓存 + 失效」的失效面便宜得多 —— 缓存一旦做错（加了词没生效），
     * 表现为「管理端加的词对**新**评论不生效」，而那正是本笔要修的功能本身。
     * 真有性能诉求时改的应该是这里（加缓存 + add/delete 里失效），不是绕过它。
     *
     * <p>只取 {@code Word} 一列、不取 {@code Id} —— 调用方（命中扫描）不关心主键，
     * 多回一列等于给它一个可以误用的锚。
     */
    public List<String> selectAllWords() {
        return jdbcClient.sql("SELECT `Word` FROM `sensitive_word`").query(String.class).list();
    }

    /** 按词面查（「重复添加」的判据）。查无 ⇒ {@code null}。 */
    public Map<String, Object> selectByWord(String word) {
        List<Map<String, Object>> rows = jdbcClient
                .sql("SELECT " + WORD_COLUMNS + " FROM `sensitive_word` WHERE `Word` = ? LIMIT 1")
                .param(word).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按主键查（删除前确认存在 —— 与 {@code ReportDao.selectReport} 同一手法）。查无 ⇒ {@code null}。 */
    public Map<String, Object> selectById(long id) {
        List<Map<String, Object>> rows = jdbcClient
                .sql("SELECT " + WORD_COLUMNS + " FROM `sensitive_word` WHERE `Id` = ? LIMIT 1")
                .param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 写 ====================

    /**
     * 落一个词。返回受影响行数。
     *
     * <p>⚠️ {@code Word} 上有唯一键 ⇒ 重复插入会**抛**（{@code DuplicateKeyException}）
     * 而不是回 0（见类注释）。调用方必须先 {@link #selectByWord} 判重。
     */
    public int insertWord(String word) {
        return jdbcClient.sql("INSERT INTO `sensitive_word` (`Word`) VALUES (?)")
                .param(word).update();
    }

    /**
     * {@code SELECT LAST_INSERT_ID()} —— 原样搬来（{@code CommentDao.lastInsertId} /
     * {@code GoodsDao.lastInsertId}）。
     *
     * <p>⚠️ <b>只在 {@code @Transactional} 里可信</b>：无事务时两次调用可能落在不同连接上，
     * 拿到的就不是刚插那行的 Id。调用方（{@code SensitiveWordService.add}）已包事务。
     */
    public Long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /**
     * 真删一个词。返回受影响行数（0 = 本来就不在 —— 并发下两个人同时删同一条会撞上）。
     *
     * <p>⚠️ <b>删词不会把历史评论里的 {@code ***} 还原</b>：原文没有第二份副本
     * （打码是就地覆写 {@code comment.Content}）⇒ 这一步是**单向**的。
     * 该口径写在 {@code SensitiveWordService.delete} 的注释里。
     */
    public int deleteById(long id) {
        return jdbcClient.sql("DELETE FROM `sensitive_word` WHERE `Id` = ?")
                .param(id).update();
    }
}
