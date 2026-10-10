package com.minke.mallmanagement.catalog.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * {@code goodstype}（类目）表的**管理端写入** SQL 出口（C3 块 3 商品块，**新域 {@code catalog}**）。
 *
 * <h2>【读不在这里 —— 类目树读取沿用既有端点】</h2>
 * 买家 / 商家读整棵树走**既有** {@code POST /goods/goodstype}（其 SQL 在
 * {@code GoodsTypeCacheService.listTypes}，带 {@code @Cacheable}）—— 本块**不新开读端点**
 * （裁决：树读取沿用既有端点，见 pack「要新建」）。本类只收**管理端**的写与按 Id 的细节读
 * （改 / 删前要先拿到现状）。
 *
 * <h2>【⚠️ 本域**不带** {@code mall_management_sql.} 前缀】</h2>
 * 口径见 {@code MerchantApplicationDao}：判据是「本域既有写法」。{@code catalog/} 是
 * **全新域**（全仓此前无该包）⇒ 取 message 域那一支（**不带**）。两处相反的决定是同一判据的结果。
 *
 * <h2>【规则 v2：删除 = 逻辑废弃（{@code State=0}），不物理 DELETE】</h2>
 */
@Repository
public class CategoryDao {

    private static final String COLUMNS = "Id, GoodType, GoodTypeNew, ParentId, Sort, State";

    private final JdbcClient jdbcClient;

    public CategoryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写 ====================

    /**
     * 新增类目。{@code ParentId=0} = 一级；{@code Sort=0} / {@code State=1} 走列默认值
     * （此处显式给，与读侧同源 —— 不让「写时留 NULL、读时按 DEFAULT 补」的裂缝存在）。
     *
     * <p>⚠️ 唯一键 {@code (ParentId, GoodType)}：同父同名会抛
     * {@code DuplicateKeyException}，由 Service 翻译成「该父级下已有同名类目」。
     */
    public int insert(String GoodType, Integer GoodTypeNew, int ParentId, int Sort) {
        String sql = "INSERT INTO goodstype (GoodType, GoodTypeNew, ParentId, Sort, State) "
                + "VALUES (?, ?, ?, ?, 1)";
        return jdbcClient.sql(sql).params(GoodType, GoodTypeNew, ParentId, Sort).update();
    }

    /**
     * 更新类目（改名 / 排序 / 启停 / 换父级）。**动态列集**：只改传入的非 {@code null} 字段。
     *
     * <p>⚠️ 先把 SQL 文本定稿、再按 {@code ?} 出现顺序绑参（同 {@code MerchantApplicationDao}
     * 的警告）—— 改本方法时成对改。
     *
     * @return 受影响行数（{@code 0} = Id 不存在）
     */
    public int update(long id, String GoodType, Integer Sort, Integer State, Integer ParentId) {
        StringBuilder sets = new StringBuilder();
        List<Object> params = new java.util.ArrayList<>();
        if (GoodType != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("GoodType = ?");
            params.add(GoodType);
        }
        if (Sort != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("Sort = ?");
            params.add(Sort);
        }
        if (State != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("State = ?");
            params.add(State);
        }
        if (ParentId != null) {
            sets.append(sets.isEmpty() ? "" : ", ").append("ParentId = ?");
            params.add(ParentId);
        }
        if (sets.isEmpty()) {
            return 0;   // 无字段可改（Service 侧应已拦，这里兜底）
        }
        String sql = "UPDATE goodstype SET " + sets + " WHERE Id = ?";
        params.add(id);
        return jdbcClient.sql(sql).params(params).update();
    }

    /** 逻辑废弃（规则 v2）：{@code State = 0}，不物理 DELETE。 */
    public int softDelete(long id) {
        String sql = "UPDATE goodstype SET State = 0 WHERE Id = ?";
        return jdbcClient.sql(sql).param(id).update();
    }

    // ==================== 读（管理端细节读） ====================

    /** 按主键取一条（改 / 删前拿现状，也是审计 {@code before} 的来源）。查不到 ⇒ {@code null}。 */
    public Map<String, Object> selectById(long id) {
        String sql = "SELECT " + COLUMNS + " FROM goodstype WHERE Id = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 管理端全量列表（含停用），按 (ParentId, Sort, Id) 排序 —— 前端拼树用。 */
    public List<Map<String, Object>> selectAll() {
        String sql = "SELECT " + COLUMNS + " FROM goodstype ORDER BY ParentId, Sort, Id";
        return jdbcClient.sql(sql).query().listOfRows();
    }

    /** 某父级下的子类目数（删一级类目前的提示用；不拦，只报数）。 */
    public long countChildren(int parentId) {
        String sql = "SELECT COUNT(*) FROM goodstype WHERE ParentId = ?";
        return jdbcClient.sql(sql).param(parentId).query(Long.class).single();
    }
}
