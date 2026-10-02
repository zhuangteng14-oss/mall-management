package com.minke.mallmanagement.goods.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 商品域**内联 SQL 的唯一出口**（三层重构第二笔：商品域）。
 *
 * <h2>【本类收什么、不收什么】</h2>
 * 只收三层前**内联在 {@code GoodsController} 方法体里**的 8 条语句 —— 即
 * {@code goodsAdd}（3 条）/ {@code goodsDe}（2 条）/ {@code goodsUp}（2 条）/
 * {@code productSearch}（1 条）。这正是本域「DAO 边界」的判据（裁决②）。
 *
 * <p>⚠️ <b>四个走缓存的读路径（{@code goodsSelect} / {@code goodsSelectHome} /
 * {@code productCodePrice} / {@code GoodsProductCodeSelect}）的 SQL 留在
 * {@link com.minke.mallmanagement.goods.service.GoodsCacheService}，
 * {@code goodstype} 的两条留在 {@link com.minke.mallmanagement.goods.service.GoodsTypeCacheService}
 * —— 那是 {@code @Cacheable} 注解的落点，把它们搬进本类等于让注解与语句分家。</b>
 *
 * <p><b>该裁决的代价，明写在这里</b>：本域因此有**两条**数据访问路径（本类 + 两个缓存
 * Service），**不是**订单域 {@link com.minke.mallmanagement.order.dao.OrderDao} 那种
 * 「唯一数据出口」。{@code three-layers.md §F#1} 的「同进程脑裂」（硬编码 schema 名散落）
 * 在商品域只被**部分**收敛 —— 抽 DAO 时清掉的是本类的 6 处，缓存 Service 里那 8 处
 * 不在本笔范围内（见「schema 前缀」一节）。
 *
 * <h2>【SQL 字面量逐字未变 —— 这是本笔的验收判据，不是修辞】</h2>
 * 8 条语句全部**从原方法体整段搬来**，包括：
 * <ul>
 *   <li>{@code mall_management_sql.} 前缀（6 处）—— ⚠️ 本笔**不消灭**它。判据沿用订单笔：
 *       {@code three-layers.md §F#1} 说它「抽 DAO 层时自然改完」，但 {@code OrderDao}
 *       已明确把它判给单独一笔（跨四域 45 处，2026-10-02 实测），本笔只清商品域这 6 条
 *       会造出「一半有前缀、一半没有」的中间态。⚠️ <b>这与 §F#1 的口径是一处已知矛盾，
 *       不是疏漏</b> —— 已在文档里标出。</li>
 *   <li>⚠️ {@code productsales} 的 INSERT 写的是 {@code (MerchantId, GoodName, ProductCode,OrderVolume)}
 *       —— {@code ProductCode} 与 {@code OrderVolume} 之间**没有空格**。这是既有形态，照抄不改。</li>
 *   <li>⚠️ {@code productSearch} 的 SELECT **本来就没有** schema 前缀（本类 8 条里唯一一条没有的）
 *       —— 别顺手补上。</li>
 * </ul>
 * 保留原字面量的收益是可复核：逐条与三层前的 {@code GoodsController} 做字符串比对即可，
 * 不必重推语义。
 *
 * <h2>【为什么是 {@code JdbcClient}】</h2>
 * 与 {@code OrderDao} 同款：D#4 已定「短期 {@code JdbcClient}（零新依赖），长期 jOOQ」。
 * 构造器**收 {@code JdbcTemplate} 而不是 {@code JdbcClient}**，是为了让
 * {@code @WebMvcTest} 切片里既有的 {@code @MockitoBean JdbcTemplate} 继续喂给本类
 * —— 切片不加载 Boot 的 {@code JdbcClient} 自动配置。
 *
 * <h2>⚠️【与 {@code OrderDao} 相反：本类**可以**加 {@code @Transactional}】</h2>
 * 订单域的 {@code @Transactional} 禁令（{@code three-layers.md §D.1}）**只管被打锁的临界区**，
 * 商品域**无锁** ⇒ 本类不受它约束。反面是 **C#2**：{@code goodsAdd}（写 goods + productsales
 * 两张表）的事务边界**应当**加 {@code @Transactional} —— 那是 C 档裁决、**未裁**，
 * **本笔不加**（本笔是纯搬运，改事务边界是行为变更）。谨记 D#1 的那句话：只记住半边
 * 就会踩另一边 —— 这里是**另一边**。
 */
@Repository
public class GoodsDao {

    private final JdbcClient jdbcClient;

    public GoodsDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：上架 ====================

    /**
     * 上架落库：{@code goods} 表插一行。
     *
     * <p>⚠️ 参数顺序与 INSERT 的列顺序**逐字对应**
     * （{@code MerchantId, GoodName, Price, GoodImg, GoodType, ProductCode, GoodTypeNew}）
     * —— 两处顺序一旦错位，没有任何断言会报错，只会有行数据静默错位。
     */
    public int insertGoods(GoodsRow r) {
        String sql = "INSERT INTO mall_management_sql.goods "
                + "(MerchantId, GoodName, Price, GoodImg, GoodType, ProductCode, GoodTypeNew) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(r.merchantId(), r.goodName(), r.price(), r.goodImg(),
                        r.goodType(), r.productCode(), r.goodTypeNew())
                .update();
    }

    /** 上架入参的载体（7 个位置参数，用 record 免掉一个 7 参方法签名）。嵌套在此，不另立文件。 */
    public record GoodsRow(String merchantId, String goodName, Double price, String goodImg,
                           String goodType, String productCode, int goodTypeNew) {
    }

    /**
     * {@code SELECT LAST_INSERT_ID()} —— 原样搬来。
     *
     * <p>⚠️ <b>返回值在本域无人使用</b>（三层前的 {@code int goodsId = …} 就是死变量），
     * 本笔**照搬不删** —— 删掉它等于悄悄去掉一次数据库往返，那是行为变更，不属纯搬运。
     *
     * <p>⚠️ 另记一条**既有性质**（本笔不修）：{@code LAST_INSERT_ID()} 是 **session 级**的，
     * 而本方法与前一条 INSERT 之间没有事务（Spring 每条语句各自取还连接）⇒ 两次调用可能落在
     * **不同的连接**上，值随之失真。因调用方丢弃该值，今天无可观测后果；**一旦有人要用它**，
     * 必须先让它与 INSERT 同处一个连接（事务或 {@code PreparedStatementCreator} 回调内）。
     */
    public Integer lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Integer.class).single();
    }

    /**
     * 上架时同步 {@code productsales} 一行的销量基线。
     *
     * <p>{@code OrderVolume} 硬编码传 {@code 0} —— 与三层前逐字一致（那个表的该列恒为 0，
     * 全项目从未更新过，见 {@code db-schema.md} §1）。
     */
    public int insertProductsales(String MerchantId, String GoodName, String ProductCode) {
        String sql = "INSERT INTO mall_management_sql.productsales "
                + "(MerchantId, GoodName, ProductCode,OrderVolume) "
                + "VALUES (?, ?, ?, ?)";
        return jdbcClient.sql(sql).params(MerchantId, GoodName, ProductCode, 0).update();
    }

    // ==================== 写：下架 ====================

    /**
     * 下架：删 {@code goods} 行。返回影响行数。
     *
     * <p>⚠️ 调用方**不捕获**返回值（三层前如此）—— {@code :224} 无条件 {@code success=true}
     * 是 {@code defect-survey.md} 记过的形状，本笔照搬，不改判定。
     */
    public int deleteGoods(String MerchantId, String GoodName, String ProductCode) {
        String sql = "DELETE FROM mall_management_sql.goods "
                + "WHERE MerchantId=? AND GoodName=? AND ProductCode=?";
        return jdbcClient.sql(sql).params(MerchantId, GoodName, ProductCode).update();
    }

    /** 下架：删 {@code productsales} 行。返回值同样被调用方丢弃（同上）。 */
    public int deleteProductsales(String MerchantId, String GoodName, String ProductCode) {
        String sql = "DELETE FROM mall_management_sql.productsales "
                + "WHERE MerchantId=? AND GoodName=? AND ProductCode=?";
        return jdbcClient.sql(sql).params(MerchantId, GoodName, ProductCode).update();
    }

    // ==================== 写：更新 ====================

    /**
     * 更新 {@code goods} 行。
     *
     * <p>⚠️ <b>{@code withImage} 的判据是「调用方传了 file 对象」，**不是**「file 非空」</b>
     * —— 三层前的写法是 {@code file != null ? ", GoodImg=?" : ""}，于是「{@code file} 存在但
     * 是空文件」这条边角路径会带上 {@code GoodImg=?} 而值为空串（调用方传 {@code ""}）。
     * 这是**既有行为**，本笔逐字保留，不「修」。
     *
     * <p>参数顺序与两个分支的绑定顺序**逐字对应**三层前那两行
     * （{@code GoodName, Price[, GoodImg], MerchantId, ProductCode}）。
     */
    public int updateGoods(String GoodName, Double Price, String goodImgUrl,
                           String MerchantId, String ProductCode, boolean withImage) {
        String sql = "UPDATE mall_management_sql.goods "
                + "SET GoodName=?, Price=?" + (withImage ? ", GoodImg=?" : "")
                + " WHERE MerchantId=? AND ProductCode=?";
        if (withImage) {
            return jdbcClient.sql(sql)
                    .params(GoodName, Price, goodImgUrl, MerchantId, ProductCode).update();
        }
        return jdbcClient.sql(sql)
                .params(GoodName, Price, MerchantId, ProductCode).update();
    }

    /** 更新 {@code productsales} 里的商品名（改名时保持两表一致）。 */
    public int updateProductsalesGoodName(String GoodName, String MerchantId, String ProductCode) {
        String sql = "UPDATE mall_management_sql.productsales "
                + "SET GoodName=? WHERE MerchantId=? AND ProductCode=?";
        return jdbcClient.sql(sql).params(GoodName, MerchantId, ProductCode).update();
    }

    // ==================== 读：搜索 ====================

    /**
     * 商品搜索。SQL **由本方法自己构造**（与 {@code OrderDao} 把语句内联在方法里一致）。
     *
     * <p>⚠️ 构造方式**逐字搬自**三层前的 {@code productSearch}，包括它那两个已知问题
     * （{@code defect-survey.md} 病 C、B-1）：
     * <ul>
     *   <li>逐字拆关键词、每字符一个 {@code OR GoodName LIKE '%x%'} ⇒ 单引号即 SQL 语法错误，
     *       且 {@code %} / {@code _} 未转义（关键词含它们时返回全表）；</li>
     *   <li>{@code SELECT *} 无 schema 前缀、无 {@code LIMIT}、无分页。</li>
     * </ul>
     * **本笔不修**：{@code three-layers.md §G} 把这些判给「三层期间（DAO）」，但本笔与订单笔
     * 同口径 —— 先纯搬运（可逐字比对），参数化与 schema 前缀另笔。⚠️ 该口径与 §G 的
     * 期望不一致，属已知矛盾。
     *
     * <p>⚠️ {@code keyword} 必须非空 —— 三层的 null 归一（{@code keyword == null ⇒ ""}）
     * 留在 {@code GoodsService}，那是入参规则不是 SQL。
     */
    public List<Map<String, Object>> searchByKeyword(String keyword) {
        String[] keys = keyword.trim().split("");
        StringBuilder nameSql = new StringBuilder();
        for (String s : keys) {
            if (!s.isEmpty()) {
                nameSql.append(" OR GoodName LIKE '%").append(s).append("%' ");
            }
        }

        String sql = "SELECT * FROM goods WHERE 1=1 " +
                "AND (GoodType = ? " +
                nameSql + ")";

        return jdbcClient.sql(sql).param(keyword.trim()).query().listOfRows();
    }
}
