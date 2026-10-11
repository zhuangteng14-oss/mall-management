package com.minke.mallmanagement.template.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code template}（通用模板库，C3 块 3 就地改造新增）的 SQL 出口。
 *
 * <h2>【为什么单开 {@code template} 域，而不是留在 {@code catalog/}】</h2>
 * 合并后这张表**跨域**：商品（详情/规格/售后）、管理端理由（驳回/封禁）、通知（{@code notification.*}）、
 * 将来评论回复。继续塞 {@code catalog/} 会让那个包变成杂物间。类注释与
 * {@code GoodsTemplateDao}（本类的前身，已删）的区别就在这一条。
 *
 * <h2>【⚠️ 本域**不带** {@code mall_management_sql.} 前缀】</h2>
 * 判据是「本域既有写法」（见 {@code MerchantApplicationDao} / {@code CategoryDao}）：{@code template}
 * 是**全新域**（全仓此前无该包）⇒ 取 message 域那一支（**不带**）。
 *
 * <h2>【规则 v2：删除 = 逻辑废弃（{@code Enabled=0}），不物理 DELETE】</h2>
 *
 * <h2>【排序：{@code Sort ASC, Id ASC}】</h2>
 * 前身 {@code GoodsTemplateDao.selectByType} 是 {@code ORDER BY Id DESC}；通用表引入了
 * {@code Sort} 列 ⇒ 口径改为**按 Sort 升序、同 Sort 按 Id 升序**（稳定序，弹窗列表不会跳动）。
 */
@Repository
public class TemplateDao {

    private static final String COLUMNS = "Id, Type, Kind, Name, Title, Body, Enabled, Sort";

    /** 列表用：多一列格式化后的 {@code UpdatedAt}（照 {@code NotificationDao.selectTemplates} —— 管理端两页都显示「最后更新」）。 */
    private static final String COLUMNS_WITH_UPDATED =
            COLUMNS + ", DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS UpdatedAt";

    private final JdbcClient jdbcClient;

    public TemplateDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写 ====================

    /**
     * 新增模板（{@code Enabled=1} 走列默认值之外显式给，与读侧同源）。
     *
     * <p>⚠️ 唯一键 {@code uk_template_type_name (Type, Name)}：同 type 下重名会抛
     * {@code DuplicateKeyException}，由 Service 翻译成「该类型下已有同名模板」。
     */
    public int insert(String type, String kind, String name, String title, String body, Integer sort) {
        String sql = "INSERT INTO template (Type, Kind, Name, Title, Body, Enabled, Sort) "
                + "VALUES (?, ?, ?, ?, ?, 1, ?)";
        return jdbcClient.sql(sql)
                .params(type, kind, name, title, body, sort == null ? 0 : sort)
                .update();
    }

    /**
     * 更新模板（改名 / 改标题 / 改正文 / 启停 / 改排序）。**动态列集**：只改传入的非 {@code null} 字段。
     *
     * <p>⚠️ {@code Type} **刻意不可改**：它决定这条模板「属于哪个输入框」，改了等于把它搬到另一个
     * 语义下，而引用方（已存的 {@code goods.Detail} 快照文本 / 通知事件 code）不会跟着走。
     * 要换类就新建一条、把旧的停用（规则 v2）。
     *
     * <p>⚠️ 先把 SQL 文本定稿、再按 {@code ?} 出现顺序绑参（同 {@code CategoryDao.update}）—— 改本方法时成对改。
     *
     * @return 受影响行数（{@code 0} = Id 不存在，或一个字段都没传）
     */
    public int update(long id, String name, String title, String body, Integer enabled, Integer sort, String kind) {
        StringBuilder sets = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (kind != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("Kind = ?");
            params.add(kind);
        }
        if (name != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("Name = ?");
            params.add(name);
        }
        if (title != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("Title = ?");
            params.add(title);
        }
        if (body != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("Body = ?");
            params.add(body);
        }
        if (enabled != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("Enabled = ?");
            params.add(enabled);
        }
        if (sort != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("Sort = ?");
            params.add(sort);
        }
        if (sets.isEmpty()) {
            return 0;   // 无字段可改（Service 侧应已拦，这里兜底）
        }
        String sql = "UPDATE template SET " + sets + " WHERE Id = ?";
        params.add(id);
        return jdbcClient.sql(sql).params(params).update();
    }

    /** 逻辑废弃（规则 v2）：{@code Enabled = 0}，不物理 DELETE。 */
    public int softDelete(long id) {
        String sql = "UPDATE template SET Enabled = 0 WHERE Id = ?";
        return jdbcClient.sql(sql).param(id).update();
    }

    // ==================== 读 ====================

    /**
     * 按**精确 type**列模板。{@code type} 空 ⇒ 不筛（管理端「全部」用）。
     *
     * <p>商品侧弹窗（「选模板」按钮）走这里，只回启用中的。
     */
    public List<Map<String, Object>> selectByType(String type, boolean includeDisabled) {
        boolean filtered = notBlank(type);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS_WITH_UPDATED + " FROM template WHERE 1=1 ");
        if (filtered) {
            sql.append("AND Type = ? ");
        }
        if (!includeDisabled) {
            sql.append("AND Enabled = 1 ");
        }
        sql.append("ORDER BY Sort ASC, Id ASC");
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString());
        if (filtered) {
            spec = spec.param(type.trim());
        }
        return spec.query().listOfRows();
    }

    /**
     * 按 **type 前缀**列模板 —— {@code NotifyPage.vue} 拉 {@code 'notification.'} 那一支用
     * （通知类的 type 是动态的，逐个精确查不现实）。
     *
     * <p>⚠️ 前缀走 {@code LIKE ?}，实参是「前缀 + {@code '%'}」。前缀字面量里**不含**
     * {@code %} / {@code _}（{@code 'notification.'} / {@code 'goods.'}）⇒ 不需要转义；
     * 若将来有人传带通配符的前缀，这条会变宽匹配 —— 调用点只有本文档记的两处。
     */
    public List<Map<String, Object>> selectByTypePrefix(String prefix, boolean includeDisabled) {
        boolean filtered = notBlank(prefix);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS_WITH_UPDATED + " FROM template WHERE 1=1 ");
        if (filtered) {
            sql.append("AND Type LIKE ? ");
        }
        if (!includeDisabled) {
            sql.append("AND Enabled = 1 ");
        }
        sql.append("ORDER BY Sort ASC, Id ASC");
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString());
        if (filtered) {
            spec = spec.param(prefix.trim() + "%");
        }
        return spec.query().listOfRows();
    }

    /**
     * 某 type 下**第一条启用中的**模板（按 {@code Sort, Id}）—— 「取一条」公共读端点
     * {@code POST /template/get} 的出口。查不到 ⇒ {@code null}。
     */
    public Map<String, Object> selectFirstEnabled(String type) {
        if (!notBlank(type)) {
            return null;
        }
        String sql = "SELECT " + COLUMNS + " FROM template "
                + "WHERE Type = ? AND Enabled = 1 ORDER BY Sort ASC, Id ASC LIMIT 1";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(type.trim()).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按主键取一条（改 / 删前拿现状，也是审计 {@code before} 的来源）。查不到 ⇒ {@code null}。 */
    public Map<String, Object> selectById(long id) {
        String sql = "SELECT " + COLUMNS + " FROM template WHERE Id = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
