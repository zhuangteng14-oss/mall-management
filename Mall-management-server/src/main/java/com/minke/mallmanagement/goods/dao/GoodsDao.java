package com.minke.mallmanagement.goods.dao;

import com.minke.mallmanagement.marketing.MarketingGoodsFlags;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 *       已明确把它判给单独一笔（跨四域 39 处，字面量口径，2026-10-02 实测），本笔只清商品域这 6 条
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
 * 两张表）的事务边界**应当**加 {@code @Transactional} —— ✅ **该裁决已于 2026-10-04 下达并落地**
 * （{@code fix/goods-write-tx}），且范围**扩到三处**（{@code goodsAdd} + {@code goodsUp}
 * + {@code goodsDe}）。⚠️ **落点在 {@code GoodsService} 的三个写方法上，不在本类** ——
 * 本类的边界仍是「逐字搬运 SQL」，事务边界的宿主是 Service（见 {@code GoodsService} 类注释
 * 的「C#2」段）；**本类照旧不加任何注解**。谨记 D#1 的那句话：只记住半边就会踩另一边
 * —— 这里是**另一边**。
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
     * （{@code MerchantId, GoodName, Price, GoodImg, GoodType, ProductCode, GoodTypeNew, TypeId, Detail, Spec, AfterSale}）
     * —— 两处顺序一旦错位，没有任何断言会报错，只会有行数据静默错位。
     */
    public int insertGoods(GoodsRow r) {
        String sql = "INSERT INTO mall_management_sql.goods "
                + "(MerchantId, GoodName, Price, OriginalPrice, GoodImg, GoodType, ProductCode, GoodTypeNew, TypeId, Detail, Spec, AfterSale, "
                + " PromiseGenuine, PromiseReturn7, PromiseFastRefund, PromiseFreightIns, ImgList, Status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(r.merchantId(), r.goodName(), r.price(), r.originalPrice(), r.goodImg(), r.goodType(),
                        r.productCode(), r.goodTypeNew(), r.typeId(), r.detail(), r.spec(), r.afterSale(),
                        r.promiseGenuine(), r.promiseReturn7(), r.promiseFastRefund(), r.promiseFreightIns(),
                        r.imgList(), r.status())
                .update();
    }

    /**
     * 上架入参的载体（11 个位置参数，用 record 免掉一个 11 参方法签名）。嵌套在此，不另立文件。
     *
     * <p>⚠️ {@code typeId} 是 C3 块 3 新增的第 8 个分量（{@code goods.TypeId}，块 3 迁移加的列）
     * —— 供买家二级筛选按 Id 走（唯一键换代连带）。提审制下它来自 {@code goods_audit.TypeId}
     * （提审时快照，通过时原样写进这里）。**可能为 {@code null}**（老调用方不传 / 名字解析不出），
     * 故用包装类型 {@code Integer} 而非 {@code int}。⚠️ 后 3 个分量 {@code detail / spec / afterSale}（C3 块 3 就地改造 2026-10-10 加的第 9-11 个）是详情 / 规格 / 售后的**快照文本**（{@code goods.Detail / Spec / AfterSale}，mediumtext，可空）—— 选模板 → 填内容 → **可改** ⇒ 落库的是商品自己那段文本、**不是**模板 Id（改模板不影响已上架商品）；提审制下来自 {@code goods_audit} 同名列。
     */
    public record GoodsRow(String merchantId, String goodName, Double price, Double originalPrice, String goodImg,
                           String goodType, String productCode, int goodTypeNew, Integer typeId, String detail, String spec, String afterSale,
                           boolean promiseGenuine, boolean promiseReturn7, boolean promiseFastRefund, boolean promiseFreightIns,
                           String imgList, int status) {
    }

    /**
     * 第三批㉖：审核通过后把提审单的折扣模板快照搬进 {@code goods}（5 列）。
     *
     * <p>⚠️ 刻意不扩 {@link GoodsRow} / {@link #insertGoods} 的签名：那两个形状被既有测试
     * 与 {@code GoodsAuditService.writeGoodsFromAudit} 钉死，扩 record 会让 18 处构造调用一起改。
     * 本方法与 {@code insertGoods} 同处审核通过的 {@code @Transactional} 里 ⇒ 原子性不变。
     *
     * <p>未选模板（{@code discountTemplateId == null}）⇒ 只有 {@code PriceSnapshotAt} 可能为
     * {@code null}，四列保持 DEFAULT（{@code DiscountAmount=0.00}，其余 {@code NULL}）。
     */
    public int updatePriceSnapshot(long goodsId, Long discountTemplateId, String discountType,
                                   BigDecimal discountRate, BigDecimal discountAmount,
                                   LocalDateTime priceSnapshotAt) {
        String sql = "UPDATE mall_management_sql.goods SET DiscountTemplateId = ?, DiscountType = ?, "
                + "DiscountRate = ?, DiscountAmount = ?, PriceSnapshotAt = ? WHERE Id = ?";
        return jdbcClient.sql(sql)
                .params(discountTemplateId, discountType, discountRate,
                        discountAmount == null ? BigDecimal.ZERO : discountAmount,
                        priceSnapshotAt, goodsId)
                .update();
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

    // ==================== 写 / 读：多规格矩阵（goods_sku，可视化段补丁）====================

    /**
     * 多规格矩阵的一行（{@code goods_sku}）。用 record 做载体，避免一个 5 参方法签名。
     *
     * <p>⚠️ {@code skuCode} 是商家填的**编码**，也是详情页加入购物车时上报的 {@code ProductCode}
     * —— 故下单 / 购物车链路**不需要改**（{@code cartAdd({…, ProductCode})} 收的就是这一串）。
     * {@code price} / {@code stock} 允许为空（商家可能只填了组合名）⇒ 用包装类型。
     */
    public record SkuRow(String axisName, Double price, Integer stock, String skuCode, Integer sort) {
    }

    /**
     * 落多规格矩阵若干行（逐条 INSERT，不批量 —— 同 {@code OrderDao.insertItems}）。
     *
     * <p>⚠️ 挂在**商品域**（无锁）⇒ 可以放进审核通过那个 {@code @Transactional} 里，
     * 与 {@code insertGoods} 同事务（{@code order} 域那条「禁事务」不适用这里）。
     *
     * @return 成功插入的行数
     */
    public int insertSkus(long goodsId, String MerchantId, String GoodName, java.util.List<SkuRow> skus) {
        String sql = "INSERT INTO mall_management_sql.goods_sku "
                + "(GoodsId, MerchantId, GoodName, AxisName, Price, Stock, SkuCode, Sort) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        int n = 0;
        for (SkuRow s : skus) {
            n += jdbcClient.sql(sql)
                    .params(goodsId, MerchantId, GoodName, s.axisName(),
                            s.price() == null ? 0d : s.price(),
                            s.stock() == null ? 0 : s.stock(),
                            s.skuCode() == null ? "" : s.skuCode(),
                            s.sort() == null ? 0 : s.sort())
                    .update();
        }
        return n;
    }

    /**
     * 按「商家号 + 商品名」读多规格矩阵（详情页 / 上架页读回用）—— 与 {@code goods} 列表同维度键
     * （商品详情本来就按 {@code MerchantId + GoodName} 定位）。
     *
     * <p>⚠️ 空结果 = 该商品**没有多规格** ⇒ 调用方回落到既有的「单型号 {@code ProductCode}」路径。
     */
    public java.util.List<Map<String, Object>> selectSkusByMerchantGoodName(String MerchantId, String GoodName) {
        // 批四第二批：只回「父商品可售（Status=2）」的 SKU —— 复审被驳回（Status=3）的商品
        // 不能继续被加入购物车。goods_sku 只在审核通过时写入，故这层 join 不会误伤正常商品。
        String sql = "SELECT s.Id, s.GoodsId, s.AxisName, s.Price, s.Stock, s.SkuCode, s.Sort "
                + "FROM mall_management_sql.goods_sku s "
                + "JOIN mall_management_sql.goods g ON g.Id = s.GoodsId "
                + "WHERE s.MerchantId = ? AND s.GoodName = ? "
                + "  AND g.Status = 2 AND g.ShelfState = 1 AND g.DeletedAt IS NULL "
                + "ORDER BY s.Sort ASC, s.Id ASC";
        return jdbcClient.sql(sql).params(MerchantId, GoodName).query().listOfRows();
    }

    /**
     * 商家改某个规格组合的库存（批四第二批）—— 谓词带 {@code MerchantId}，防改别家 SKU。
     * 兼容 {@code AxisName} 里的单引号（参数绑定，不拼 SQL）。
     */
    public int updateSkuStock(int goodsId, String merchantId, String axisName, int stock) {
        String sql = "UPDATE mall_management_sql.goods_sku SET Stock = ? "
                + "WHERE GoodsId = ? AND MerchantId = ? AND AxisName = ?";
        return jdbcClient.sql(sql).params(stock, goodsId, merchantId, axisName).update();
    }

    /** 按父商品 Id 读 SKU（商家改库存弹窗回读；不筛 Status，自己的商品都要能看到）。 */
    public java.util.List<Map<String, Object>> selectSkusByGoodsId(int goodsId, String merchantId) {
        String sql = "SELECT Id, GoodsId, MerchantId, GoodName, AxisName, Price, Stock, SkuCode, Sort "
                + "FROM mall_management_sql.goods_sku WHERE GoodsId = ? AND MerchantId = ? "
                + "ORDER BY Sort ASC, Id ASC";
        return jdbcClient.sql(sql).params(goodsId, merchantId).query().listOfRows();
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

    /**
     * 复审驳回时把已有 goods 行置 {@code Status=3}（批四第二批）。
     * 首次提审被驳回时 {@code GoodsId} 为 NULL，调用方不会走到这里 —— 不凭空插 goods 行。
     */
    public int updateGoodsStatus(int goodsId, int status) {
        String sql = "UPDATE mall_management_sql.goods SET Status = ? WHERE Id = ?";
        return jdbcClient.sql(sql).params(status, goodsId).update();
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
    public List<Map<String, Object>> searchByKeyword(String keyword, int limit, int offset) {
        String[] keys = keyword.trim().split("");
        StringBuilder nameSql = new StringBuilder();
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(keyword.trim()); String kwTrim = keyword.trim(); boolean tagSeckill = kwTrim.equals("秒杀") || kwTrim.equals("秒杀商品"); boolean tagSubsidy = kwTrim.equals("国补") || kwTrim.equals("国补商品") || kwTrim.equals("国家补贴"); boolean tagBrand = kwTrim.equals("品牌") || kwTrim.equals("品牌商品");
        for (String s : keys) {
            if (!s.isEmpty()) {
                // ⚠️ 块 7 收尾：逐字符 LIKE 从**字符串拼接**改成**绑定参数**（既有缺陷结清）。
                //    原写法把用户输入直接拼进 SQL：单引号会造语法错误（500），`%` / `_` 会当通配符
                //    放大成全表。现在字符是参数，`%` / `_` / `\` 另做转义 ⇒ 它们只匹配字面量。
                nameSql.append(" OR g.GoodName LIKE CONCAT('%', ?, '%') ESCAPE '\\\\' ");
                args.add(likeEscape(s));
            }
        } if (tagSeckill) nameSql.append(" OR ").append(MarketingGoodsFlags.existsIsSeckill("g")); if (tagSubsidy) nameSql.append(" OR ").append(MarketingGoodsFlags.existsIsSubsidy("g")); if (tagBrand) nameSql.append(" OR EXISTS (SELECT 1 FROM mall_management_sql.user ub WHERE ub.merchantid = g.MerchantId AND ub.IsBrand = 1) ");

        // ⚠️ 2026-10-12〈Bug 2/3〉起本条的**列清单不再逐字等于三层前的 productSearch**：
        //    SELECT * → SELECT g.* + 三个营销态布尔（IsSeckill / IsSoldOut / IsSubsidy，
        //    谓词取自 `MarketingGoodsFlags`）。同批给表起了别名 `g`（三个 EXISTS 要引 `g.Id`）
        //    ⇒ 连带的 `ShelfState`/`DeletedAt`/`GoodType`/`GoodName` 全加 `g.` 前缀。
        //    ⚠️ 类注释那句「SQL 字面量逐字未变」说的是**三层重构那一笔的历史**；本笔的验收判据
        //    改为「三个标志列逐字来自 MarketingGoodsFlags」+ a3 实测（两条列表端点同源）。
        //    ⚠️ 仍然**没有** schema 前缀、仍然**没有** LIMIT / 分页 —— 那两条是既有账，本笔不动。
        String sql = "SELECT g.*, "
                + MarketingGoodsFlags.existsIsSeckill("g") + " AS IsSeckill, "
                + MarketingGoodsFlags.existsIsSoldOut("g") + " AS IsSoldOut, "
                + MarketingGoodsFlags.existsIsSubsidy("g") + " AS IsSubsidy, " +
                // 批四第二批 commit 2：品牌标（同一个 EXISTS 谓词，见 GoodsCacheService.listHome）。
                "EXISTS(SELECT 1 FROM user u WHERE u.merchantid = g.MerchantId AND u.IsBrand = 1) AS IsBrand " +
                "FROM goods g WHERE 1=1 " +
                "AND g.ShelfState = 1 AND g.DeletedAt IS NULL AND g.Status = 2 " +
                "AND (g.GoodType = ? " +
                nameSql + ") " +
                // ⚠️ 分页要求确定性排序：无 ORDER BY 的 LIMIT/OFFSET 在 InnoDB 里可能重复/漏行。
                "ORDER BY g.Id DESC LIMIT ? OFFSET ?";

        args.add(limit);
        args.add(offset);
        return jdbcClient.sql(sql).params(args.toArray()).query().listOfRows();
    }

    /** LIKE 参数里的通配符转义（`\` → `\\`，`%` / `_` 加反斜杠），配合 SQL 的 `ESCAPE '\\'`。 */
    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ==================== 读：审计快照（C3 批 A 新增，追加在类尾） ====================

    /**
     * 审计用：按 {@code (MerchantId, ProductCode)} 取 {@code goods} 行 —— 商品更新的
     * **改前 / 改后快照**（C3 批 A「权限与审计」的样板挂钩，见 {@code GoodsController.goodsUp}）。
     *
     * <p>⚠️ <b>本方法是本类第一条不是从三层前的 Controller 搬来的语句</b> —— 类注释里那句
     * 「N 条语句全部整段搬来」说的是三层重构那一笔的**历史**，不是「本类只能有 N 条」的禁令。
     * 本条的来源是 C3 批 A 的审计需求。
     *
     * <p>⚠️ <b>谓词必须与 {@link #updateGoods} 的 {@code WHERE} 逐字相同</b>
     * （{@code MerchantId=? AND ProductCode=?}）：它就是「将被改动的那几行」的定义。
     * 谓词一宽一窄，快照与实际改写集就对不上，审计里的「改前」就成了假的。**改一处必须改两处。**
     *
     * <p>⚠️ <b>可返回多行</b>（同一商家同一型号可以挂多个商品名 —— {@code updateGoods} 也会
     * 一次改多行）⇒ 返回 {@code List}、**不取单行**。取单行会在多行时抛
     * {@code IncorrectResultSizeDataAccessException}，把一次成功的商品更新变成 500。
     *
     * <p>⚠️ 用 {@code SELECT *} 而非列清单：改前 / 改后是**同一张表同一批列**，列清单写死两遍
     * 反而给了「两处不一致」的机会。这条与 {@code searchByKeyword} 的 {@code SELECT *} 同形，
     * 但理由不同（那里是照搬既有形状，这里是刻意求「两次读同构」）。
     */
    public List<Map<String, Object>> selectGoodsForAudit(String MerchantId, String ProductCode) {
        String sql = "SELECT * FROM mall_management_sql.goods "
                + "WHERE MerchantId = ? AND ProductCode = ?";
        return jdbcClient.sql(sql).params(MerchantId, ProductCode).query().listOfRows();
    }

    // ==================== 读：二级类目筛选（C3 块 3 新增，追加在类尾） ====================

    /**
     * 按类目 Id（+ 可选关键词）列出**买家可见**的商品 —— 新端点 {@code /goods/categoryGoods}
     * 的唯一数据出口（C3 块 3「浏览/搜索增强」）。
     *
     * <p>⚠️ <b>不复用 {@link #searchByKeyword}</b>：那条是按**类目名**筛的（{@code GoodType = ?}），
     * 而唯一键换代后「电子用品&gt;配件」与「服装&gt;配件」同名 ⇒ 名字不再唯一。本方法按
     * {@code TypeId}（{@code goods.TypeId}，块 3 迁移回填）筛，**无歧义**。
     *
     * <p>⚠️ 可见性过滤 {@code ShelfState = 1 AND DeletedAt IS NULL} 与另两条读路径同口径
     * （C3 块 2 挂账 + 块 3 违规处置的落点）：下架 / 违规删除的商品不出现在这里。
     *
     * <p>⚠️ 关键词用**参数化** {@code LIKE CONCAT('%', ?, '%')}，**不是** {@code searchByKeyword}
     * 那种「逐字符拼 {@code OR GoodName LIKE}」的既有形态 —— 新语句不继承那个已知缺陷
     * （单引号注入 / {@code %} 未转义，见 {@link #searchByKeyword} 的 ⚠️）。{@code keyword}
     * 空 ⇒ 不加这一条谓词。
     */
    public List<Map<String, Object>> selectByTypeId(Integer TypeId, String keyword, int limit, int offset) {
        boolean filtered = keyword != null && !keyword.trim().isEmpty();
        String sql = "SELECT g.*, "
                // 批四第二批 commit 2：品牌标（同 searchByKeyword / GoodsCacheService.listHome 谓词）。
                + "EXISTS(SELECT 1 FROM mall_management_sql.user u WHERE u.merchantid = g.MerchantId AND u.IsBrand = 1) AS IsBrand "
                + "FROM mall_management_sql.goods g "
                + "WHERE TypeId = ? AND ShelfState = 1 AND DeletedAt IS NULL AND Status = 2 "
                + (filtered ? "AND GoodName LIKE CONCAT('%', ?, '%') " : "")
                + "ORDER BY Id DESC LIMIT ? OFFSET ?";
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql).param(TypeId);
        if (filtered) {
            spec = spec.param(keyword.trim());
        }
        return spec.params(limit, offset).query().listOfRows();
    }

    /**
     * 按类目**名**反查 {@code goodstype.Id} —— 提审制下 {@code goodsAdd} 走老前端（只传类目名）
     * 时的**回退解析**（新前端传 {@code TypeId} 时不查）。
     *
     * <p>⚠️ 名字可能不唯一（唯一键换代后允许不同父级下同名）⇒ 本方法**可能多行**，
     * 调用方取第一行（{@code ORDER BY Id} 保证确定性）并**接受歧义**：这是「老前端只给名字」
     * 这一既有缺口的下界，不是本块要修的东西（正确通路是前端传 {@code TypeId}）。
     * 查不到 ⇒ {@code null}（不是 0 —— {@code Id} 从 1 起）。
     */
    public Integer selectTypeIdByName(String GoodType) {
        if (GoodType == null || GoodType.trim().isEmpty()) {
            return null;
        }
        String sql = "SELECT Id FROM mall_management_sql.goodstype "
                + "WHERE GoodType = ? ORDER BY Id LIMIT 1";
        List<Integer> ids = jdbcClient.sql(sql).param(GoodType).query(Integer.class).list();
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ==================== 写：违规处置（C3 块 3 新增，逻辑废弃，不物理删） ====================

    /**
     * 违规**下架**（{@code ShelfState = 0}）—— 谓词与 {@link #deleteGoods} / {@link #updateGoods}
     * **逐字相同**（{@code MerchantId/GoodName/ProductCode}，里氏代换上就是那一组行），
     * 但**不删行**（规则 v2：逻辑废弃）。返回影响行数。
     *
     * <p>⚠️ 与「降级冻结下架」（块 2 的 {@code GoodsShelfDao}）共用同一列 {@code ShelfState}
     * ⇒ 「违规下架 vs 降级下架」的**区分**留 {@code slot-registry.md §十九} 待办（OffShelfReason），
     * 本块不做（同 §一.1 的边界）。
     */
    public int delistByTriple(String MerchantId, String GoodName, String ProductCode) {
        String sql = "UPDATE mall_management_sql.goods SET ShelfState = 0 "
                + "WHERE MerchantId=? AND GoodName=? AND ProductCode=? AND DeletedAt IS NULL";
        return jdbcClient.sql(sql).params(MerchantId, GoodName, ProductCode).update();
    }

    /**
     * 违规**删除**（{@code DeletedAt = now}）—— 逻辑删除（规则 v2），行仍在表里、买家侧查不到。
     * 谓词同 {@link #delistByTriple}，多一个 {@code AND DeletedAt IS NULL} 防重复打点覆盖首次时刻。
     */
    public int softDeleteByTriple(String MerchantId, String GoodName, String ProductCode,
                                  LocalDateTime deletedAt) {
        String sql = "UPDATE mall_management_sql.goods SET DeletedAt = ? "
                + "WHERE MerchantId=? AND GoodName=? AND ProductCode=? AND DeletedAt IS NULL";
        return jdbcClient.sql(sql).params(deletedAt, MerchantId, GoodName, ProductCode).update();
    }

    // ==================== 写：多规格库存的扣减 / 释放（C3 块 7 · 库存治理 TD-36 的落地）====================

    /**
     * <b>CAS 扣减</b>一条多规格的库存，返回**影响行数**（C3 块 7 · 库存治理 TD-36）。
     *
     * <p>语句逐字照《块 7 参考》§5.1，与订单状态机同款：<b>把判据写进 {@code WHERE}、由数据库一把定胜负</b>，
     * 不先 {@code SELECT} 再 {@code UPDATE}（那样两个并发请求会读到同一个旧值）。
     *
     * <p>⚠️ <b>{@code rows == 0} 是正常分支、不是错误</b>（同 {@code OrderDao.casUpdate} 的口径）。
     * 它由两种**完全不同的**原因产生，调用方必须都按「什么也没发生」处理：
     * <ol>
     *   <li><b>库存不足</b>（{@code Stock < n}）—— 防超卖命中，这是本方法的**主要目的**；</li>
     *   <li><b>该 {@code SkuCode} 没有行</b> —— 即 <b>非矩阵商品</b>。订单链路装的是
     *       {@code order_item.ProductCode} / {@code OrderService.ItemSpec.code()}，而
     *       {@code goods_sku.SkuCode} 只在**矩阵商品**下与之相等（加购时把 {@code SkuCode}
     *       作为 {@code ProductCode} 上报）⇒ 非矩阵商品**必然查无此行**。</li>
     * </ol>
     * ⚠️ <b>两种 0 行都不得被调用方判成「订单失败」</b> —— 那样会让调用方重复下单，
     * 与 {@code OrderService.writeItems} 的 fail-open 是同一条理由。
     */
    public int deductStock(String skuCode, int n) {
        String sql = "UPDATE mall_management_sql.goods_sku SET Stock = Stock - ? "
                + "WHERE SkuCode = ? AND Stock >= ?";
        return jdbcClient.sql(sql).params(n, skuCode, n).update();
    }

    /**
     * <b>释放（回补）</b>一条多规格的库存 —— 订单**超时取消**时把下单时扣掉的量加回去
     * （C3 块 7 · TD-36 的第三条腿，C5 甲：复用 {@code OrderTimeoutCancelJob}，不新加 job）。
     *
     * <p>⚠️ <b>前置判据不在本方法里，在调用方</b>（块 7 · commit 2d 补，2026-10-14）：本方法只管
     * 「按码加回 {@code n}」；「这单当初到底扣成了没有」已记进 {@code order.StockDeducted}
     * （Migration ⑱），由 {@code OrderTimeoutCancelJob.releaseStock} **先读它、只有 1 才调本方法**。
     * 非矩阵商品查无此行 ⇒ {@code 0} 行 = no-op（与 {@link #deductStock} 的第二条边界同源）。
     *
     * <p>⚠️ <b>历史边界（2d 之前）—— 留档，别按它理解现在的行为</b>：那时本方法是**无条件加回**
     * ⇒ 「下单时因库存不足扣减 0 行、单照样落库」的那种单，超时取消会**凭空补出一份库存**（虚增）。
     * <p>⚠️ <b>2d 之后的精度到「单」不到「行」</b>：一单里既有真扣成的行、又有 0 行的行（混合单）
     * 时，{@code StockDeducted = 1} ⇒ 拿 0 行的那几行明细仍会被回补。这是**已知取舍**，
     * 不是疏漏 —— 要根治得把判据下沉到 {@code order_item} 粒度，那是**新列 + 新契约**，须另批。
     * 块 7 的演示另有 {@code goods_sku.BaselineStock}（Migration ⑰-2）一键复位兜底。
     */
    public int releaseStock(String skuCode, int n) {
        String sql = "UPDATE mall_management_sql.goods_sku SET Stock = Stock + ? WHERE SkuCode = ?";
        return jdbcClient.sql(sql).params(n, skuCode).update();
    }

    // ==================== 读：库存水位（C3 块 7 · 指标面板的「库存水位进度条」）====================

    /**
     * 最靠前的 {@code limit} 条多规格库存 —— 指标面板「库存水位」段的**唯一**输入
     * （C3 块 7 · commit 3，调用方 {@code MetricsSnapshotService#stock}）。
     *
     * <p><b>排序 = 水位低的先出</b>（{@code Stock ASC}，同值按 {@code Id} 保证稳定）——
     * 这块面板要回答的是「**哪些快没了**」，按 Id 排会先给出一屏水位健康、看不出问题的行。
     *
     * <p>⚠️ <b>只读、不改任何行</b>：与同段落的 {@link #deductStock} / {@link #releaseStock}
     * 是两回事（那两个是写）。本方法不参与库存治理，它只是把现状读出来给人看。
     *
     * <p>⚠️ {@code BaselineStock} 可以是 {@code NULL}（Migration ⑰-2 加的列，演示前才
     * {@code UPDATE … SET BaselineStock = Stock}）：它为 {@code NULL} 时调用方
     * 把「水位占比」那一格留空 —— 本方法不替它算（{@code Stock / NULL} 没有意义，
     * 用 {@code Stock} 自己当分母会画出「永远 100%」的假水位条）。
     *
     * <p>⚠️ <b>不含 {@code goods} 表</b>：商品名就在 {@code goods_sku.GoodName} 里
     * （加购时写入，见 {@code selectSkusByMerchantGoodName} 的既有读法）⇒ 不做 join
     * ——多一个 join 就多一处将来会漂的口径。
     */
    public java.util.List<Map<String, Object>> selectStockLevels(int limit) {
        String sql = "SELECT SkuCode, GoodName, AxisName, Stock, BaselineStock "
                + "FROM mall_management_sql.goods_sku ORDER BY Stock ASC, Id ASC LIMIT ?";
        return jdbcClient.sql(sql).param(limit).query().listOfRows();
    }

    /**
     * 某个 {@code SkuCode} 的**当前**库存 —— 给「**扣完那一刻还剩多少**」取数
     * （块 7 · 阶段 3a.3 第二步，唯一调用方 {@code OrderService#deductStocks} 的 {@code stockRemaining}）。
     *
     * <p>⚠️ <b>本方法答的是「此刻剩多少」，不是「扣减那一刻剩多少」</b>：后者只能由调用方在
     * <b>扣减那一行 SQL 之后紧接着</b>调本方法取得（口径全文见
     * {@code StockMetrics.StockEvent#remaining()}）。本方法自身**没有时间语义** ——
     * 别拿它事后回填一条历史事件：那会往台账里写下一个**当时并不成立**的事实。
     *
     * <p>⚠️ {@code null} = 该码在 {@code goods_sku} 里**没有行**（非矩阵商品：订单链路装的是
     * {@code order_item.ProductCode}，只有矩阵商品才与 {@code SkuCode} 相等 ⇒ 与
     * {@link #deductStock} 第二条边界同源）。那是**正常分支** ⇒ 用 {@code optional().orElse(null)}
     * 而**不是** {@code single()}（后者查无此行会抛，把「正常查无」变成异常 ——
     * 调用方那一层最怕的正是「把正常分支记成 error」）。
     *
     * <p>⚠️ <b>{@code SkuCode} 不是唯一键</b>（本表唯一键是 {@code (GoodsId, AxisName)}，见 ④ 段 DDL），
     * 而 {@link #deductStock} 的 CAS 一次**可能命中多行**。同码多行时「剩余」本来就没有唯一答案，
     * 本方法不假装有：取 {@code Id} 最小那一行的值。{@code ORDER BY} 是必须的 ——
     * 光一个 {@code LIMIT 1} 由哪一行胜出**不确定**，同一份数据两次查可以给出不同的数
     * （本仓在「不确定序」上栽过：{@code ObservedEventDao#selectByBatch} 的
     * {@code ORDER BY OccurredAt, Id} 是同一条理由）。
     */
    public Integer selectStockBySku(String skuCode) {
        String sql = "SELECT Stock FROM mall_management_sql.goods_sku "
                + "WHERE SkuCode = ? ORDER BY Id ASC LIMIT 1";
        return jdbcClient.sql(sql).param(skuCode).query(Integer.class).optional().orElse(null);
    }
}
