package com.minke.mallmanagement.goods.service;

import com.minke.mallmanagement.shared.web.PageLimits;
import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.shared.upload.UploadStorage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品域的服务层（三层重构第二笔：商品域）。**业务规则 + 文件落盘编排 + 响应组装**；
 * 路由与入参绑定留在 {@link com.minke.mallmanagement.goods.controller.GoodsController}，
 * 本域的内联 SQL 全部在 {@link GoodsDao}，四个走缓存的读仍直接委托
 * {@link GoodsCacheService}（裁决②）。
 *
 * <h2>【迁自哪里 —— 逐字搬运，不是重写】</h2>
 * 8 个方法体整段搬自 {@code GoodsController}，含 SQL 调用顺序、try-catch、早退分支与
 * **每一条文案**（{@code "商品上架成功！"} / {@code "商品图片保存失败"} / {@code "下架失败"} …）。
 * 搬运的验收判据是「响应逐字段与改前一致」—— 前端 31 个调用点读的是 {@code success} /
 * {@code msg} / {@code list} / {@code GoodImg} / {@code price} / {@code goodImg}，
 * 少一个键或多一个键都是可观测的契约变化。
 *
 * <p>⚠️ <b>方法名与端点名逐字相同</b>（含首字母大写的 {@link #GoodsProductCodeSelect}）
 * —— 不是为了好看，是为了让「哪个端点搬到哪个方法」可以 grep 直查，搬运的可复核性
 * 优先于命名美观。
 *
 * <h2>【三个「照搬不修」的既有形态】</h2>
 * <ol>
 *   <li>{@code goodsAdd} 里的 {@code int OrderVolume = 0;} 与 {@code int goodsId = goodsDao.lastInsertId();}
 *       都是**死局部变量**（声明后从未被读）—— 照搬。后者尤其不能删：它是一次真实的
 *       数据库往返。</li>
 *   <li>{@code goodsUp} 的列集判据是 {@code file != null}（**不是** {@code !file.isEmpty()}）
 *       —— 见 {@link GoodsDao#updateGoods}。</li>
 *   <li>{@code goodsUp} 的形参 {@code GoodType} 是**既有入参、方法体内未使用**：签名必须原样保留
 *       （前端在传，去掉即 400）。</li>
 * </ol>
 *
 * <h2>【为什么 {@code productCodePrice} 的 catch 能在本类】</h2>
 * 2026-09-30 有一条文档化的决定：{@code productCodePrice} 的 try/catch 必须留在 Controller 一侧
 * —— 因为「异常上抛」是「不写入缓存」的唯一表达方式（{@code @Cacheable(sync=true)} 禁用
 * {@code unless}），而 catch 若与被缓存的注解**同处一个 bean**，自调用不走代理 ⇒ 注解静默失效。
 *
 * <p>本笔把 catch 挪进本类而**不违反**那条决定：判据是「catch 不能与 {@code @Cacheable}
 * **同一个 bean**」，而 {@link GoodsCacheService#priceByTriple} 是**另一个 bean** ——
 * 异常照常逃出被缓存的方法，缓存拦截器照常不写入，本类再把它接住。
 * ⚠️ 但这条推理必须随代码留档：**将来谁把 catch 挪回 {@code GoodsCacheService} 的类内
 * （哪怕是另一个方法再自调用），缓存就会静默失效。**
 *
 * <h2>【缓存失效面为什么在本类】</h2>
 * 两个写方法（{@link #goodsDe} / {@link #goodsUp}）各自在**唯一出口前**调用
 * {@link GoodsCacheService#evictByMerchant}。该调用随方法体一起从 Controller 搬来 ——
 * 逻辑在哪，它保护的失效就在哪。「早退分支不清缓存」（{@code goodsAdd} 的两条早退）也一并保留：
 * 那些路径一行未改库，清它是纯浪费。
 *
 * <p>⚠️ <b>C3 块 3 起 {@link #goodsAdd} 退出这个清单</b>：它改为**提审制**、一行不写 {@code goods}
 * 表 ⇒ 无缓存可失效，原「唯一出口前 evict」**搬到审核通过路径**
 * （{@link GoodsAuditService#audit}）。这是有意的搬家，不是漏写。
 *
 * <h2>【C#2：三个写方法的事务边界（2026-10-04，{@code fix/goods-write-tx}）】</h2>
 * {@link #goodsAdd} / {@link #goodsDe} / {@link #goodsUp} 各要写 {@code goods} + {@code productsales}
 * **两张表**，改前无事务 ⇒ 第二条语句失败时第一条已提交，留下「半成品」（有商品行、无销量行）。
 * 本笔给三者加 {@link Transactional}。落点与 D#1 的禁令**不冲突**，但有四处必须写清：
 *
 * <ol>
 *   <li><b>D#1 不适用于本域</b>：那条禁令（{@code three-layers.md §D.1}）只管「被打上
 *       {@code DistributedLockService.runLocked} 的临界区」（{@code orderAddLocked} /
 *       {@code friendAddLocked}），理由是「锁在 {@code finally} 里先释放、事务提交在方法返回之后
 *       ⇒ 后进者查重看到的是自己事务开始时的快照」。本域**无锁**（三个写方法都不走 {@code runLocked}）
 *       ⇒ 不存在该竞争，事务在这里是安全的。{@code three-layers.md#pack-three-layers-constraints} 的「**不可外推**」正是为此：
 *       {@code goodsAdd} 是 D#1 的**反面**，只记住半边就会踩另一边。</li>
 *   <li>⚠️ <b>代理生效 ≠ 会回滚</b>：三个方法都**自己 catch 掉了** DAO 异常、**不 rethrow**
 *       ⇒ Spring 看到的是正常返回 ⇒ 照常提交。故 catch 内必须显式标记回滚
 *       （{@link #markRollbackOnlyIfActive()}）。少了这一步，「加了 {@code @Transactional}」是**假绿**
 *       —— 这正是本笔最容易漏、也最难在代码评审里看出来的一处。</li>
 *   <li>⚠️ <b>已知代价（本笔接受）</b>：{@code @Transactional} 覆盖**整个方法**，含
 *       {@code UploadStorage.store()} 的磁盘 I/O ⇒ 方法执行期间持有 HikariCP 连接（默认池 10）。
 *       当前单实例、低流量，接受；若将来出现连接池压力，升级到方案 B（{@code TransactionTemplate}
 *       把事务边界收窄到只包两次 DAO 调用 —— 异常逃出回调即自动回滚、catch 天然落在事务外，
 *       连 {@code setRollbackOnly} 都不需要）。</li>
 *   <li>⚠️ <b>覆盖缺口，一并登记</b>：本域无锁，故 {@code DistributedLockServiceTest.orderChainIsNotTransactional}
 *       那条 D#1 结构断言**不覆盖商品域**（它的扫描列表只有 order + message 两条链的 5 个 bean）。
 *       将来若有人给 {@code goodsAdd} 加锁，**D#1 会适用而那处断言抓不到** ——
 *       届时须同步把 {@code GoodsService} 扩进它的扫描列表。</li>
 * </ol>
 */
@Service
public class GoodsService {

    private final GoodsDao goodsDao;
    private final GoodsCacheService goodsCacheService;
    private final GoodsAuditService goodsAuditService;
    /** 折扣上下限校验（营销域，块 5〈折扣〉）—— 只被 {@link #goodsAdd} 消费。 */
    private final com.minke.mallmanagement.marketing.discount.service.DiscountLimitService discountLimitService;
    private final String uploadDir;

    public GoodsService(GoodsDao goodsDao,
                        GoodsCacheService goodsCacheService,
                        GoodsAuditService goodsAuditService,
                        com.minke.mallmanagement.marketing.discount.service.DiscountLimitService discountLimitService,
                        @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}") String uploadDir) {
        this.goodsDao = goodsDao;
        this.goodsCacheService = goodsCacheService;
        this.goodsAuditService = goodsAuditService;
        this.discountLimitService = discountLimitService;
        this.uploadDir = uploadDir;
    }

    // ==================== 写：上架（C3 块 3 起 = 提审） ====================

    /**
     * 上架商品 —— <b>C3 块 3 起改为**提审制**：本方法**不再写 {@code goods} 行**</b>，
     * 只落一条 {@code goods_audit(pending)}。买家侧「看不到待审商品」是因为**压根没上架**。
     *
     * <p>保留的部分：图片落盘编排 + 两条早退（图片为空 / 落盘失败）+ 全部文案 —— 与改前提审制的
     * 差别只在「写什么」：{@code insertGoods} + {@code insertProductsales} 换成
     * {@link GoodsAuditService#submit}。
     *
     * <p>⚠️ <b>本方法**不再**调 {@code evictByMerchant}</b> —— 提审一行未动 {@code goods} 表，
     * 没有缓存要失效。失效**搬到审核通过路径**（{@link GoodsAuditService#audit}，那里才真正新增
     * 一行 {@code goods}）。这与原「唯一出口前 evict」的形状相反，是本块**有意的搬家**，
     * 不是漏写：在无改库的路径上清缓存是纯浪费（与两条早退分支不清缓存同一理由）。
     *
     * <p>⚠️ <b>两条早退仍是「先于任何写库」</b>：图片为空 / 落盘失败都在 {@code submit} 之前返回
     * ⇒ 失败路径**一条 JDBC 都不执行**（{@code GoodsAddUploadFailSmokeTest} 的断言据此成立）。
     *
     * <p>⚠️ <b>C#2 的事务边界随之搬家</b>：原「写 {@code goods} + {@code productsales} 两表同事务」
     * 的原子性判据**不平移到一个不存在的写口上**，而是移到审核通过路径（那里才写这两张表）。
     * 本方法保留 {@link Transactional}（覆盖「落盘 + 写提审单」），catch 内仍须
     * {@link #markRollbackOnlyIfActive()}。
     */
    // ==================== D 段 · commit 2：熔断（本类 5 处，读 2 / 写 3）====================
    //
    // 下面是本仓**唯一**一条「看见 MySQL 状态」的防线。三条口径写在明处：
    //  ① 分读 / 写两个实例（`mallMysqlRead` / `mallMysqlWrite`）：演练灌的是读，而写要保住 ——
    //     「读被熔断打开时写仍能过」是可演示的收益，合成一个实例就没了。
    //  ② ⚠️ **不能挂 Controller**：本仓有结构断言钉死「Controller 不得被 AOP 代理」
    //     （`MallManagementApplicationTests#goodsControllerIsNotAopProxied`），而且裁定也明禁
    //     「把熔断挂到入站层」。这两条指向同一件事：熔断必须挂在**能看见下游**的那一层。
    //  ③ ⚠️ 注解**写在 `@Transactional` 上面**只是可读性；真正的先后由切面 order 决定，而它是
    //     **实测**过的：CircuitBreakerAspect 默认 order 2147483643（MAX-4）< 事务切面的 MAX
    //     ⇒ 熔断在外、事务在内，**开路时连事务都不会开**。别把这一行当成「顺序由书写决定」。
    // 参数（错误率 50% / 30s 窗口 / 半开 30s 放 5 个 / 最小请求数 20 / 慢调用 1s）全在
    // `application.properties` 的 `resilience4j.circuitbreaker.configs.default.*`，不在本类。
    @CircuitBreaker(name = "mallMysqlWrite")
    @Transactional
    public Map<String, Object> goodsAdd(
            String MerchantId,
            String GoodName,
            Double Price,
            Double OriginalPrice,
            MultipartFile file,
            String GoodType,
            String ProductCode,
            int GoodTypeNew,
            Integer TypeId, String Detail, String Spec, String AfterSale, String SkuSpec,
            Boolean PromiseGenuine, Boolean PromiseReturn7, Boolean PromiseFastRefund, Boolean PromiseFreightIns
    ) {
        Map<String, Object> result = new HashMap<>();

        // ═══ 块 5〈折扣〉：划线原价的**权威校验**（前端那处实时提示只是助手，不是判据）═══
        // ⚠️ 位置是刻意的：**在任何 I/O 之前**（图片落盘也不做）—— 与两条既有早退同口径，
        //    失败路径一条 JDBC、一次磁盘写都不执行。
        String discountError = discountLimitService.validate(GoodType, Price, OriginalPrice);
        if (discountError != null) {
            result.put("success", false);
            result.put("msg", discountError);
            return result;
        }

        // 图片保存路径
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();

        // 图片为空判断
        if (file.isEmpty()) {
            result.put("success", false);
            result.put("msg", "请选择商品图片");
            return result;
        }

        // 保存图片，并拿到**实际落盘**的文件名（同名同内容→复用；同名不同内容→改名另存）。
        // 必须用返回值拼 URL —— 改名时它和上传时的文件名不一致。
        String originalFilename;
        try {
            originalFilename = UploadStorage.store(uploadPath, file);
        } catch (Exception e) {
            // 显式失败：以前这里吞掉异常继续写库，结果是 DB 记下 URL、盘上没有那个文件
            // —— 静默脏数据，比 500 更难查。
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "商品图片保存失败");
            return result;
        }

        // 最终存入数据库的图片 URL
        String GoodImg = "http://localhost:8088/img/" + originalFilename;

        try {
            // 提审：只写 goods_audit(pending)，**不写 goods 行**（提审制的定义，见类注释）。
            String batchId = GoodsAuditService.newBatchId();
            goodsAuditService.submit(MerchantId, batchId, GoodName, Price, OriginalPrice, GoodType, GoodTypeNew, TypeId,
                    ProductCode, GoodImg, Detail, Spec, AfterSale, SkuSpec,
                    PromiseGenuine, PromiseReturn7, PromiseFastRefund, PromiseFreightIns);

            result.put("success", true);
            // ⚠️ 文案已改（C3 块 3）：原「商品上架成功！」⇒「已提交审核」。
            //    连带更新 shared/web/ResultByteEquivalenceTest.java 的标签⑦字符串（键形状不变）。
            result.put("msg", "已提交审核");
            result.put("GoodImg", GoodImg);
            result.put("batchId", batchId);
        } catch (Exception e) {
            e.printStackTrace();
            // ⚠️ 吞掉异常就必须配这一步（同改前）：本方法**不 rethrow** ⇒ 不标记回滚的话，
            //    @Transactional 看到的是正常返回、照常提交，提审单会留下 —— 那正是不想要的。
            markRollbackOnlyIfActive();
            result.put("success", false);
            result.put("msg", "商品提交失败");
        }

        // ⚠️ 本方法**不调** evictByMerchant：一行未动 goods 表（见方法 javadoc）。
        return result;
    }

    // ==================== 读：四个走缓存的路径 ====================

    /**
     * 商家端商品列表。**委托 + 响应组装** —— 语句与 {@code @Cacheable} 都在
     * {@link GoodsCacheService#listByMerchant}（裁决②）；try/catch 于 2026-10-03（② 笔）
     * 从缓存侧**搬来本类**，理由见 {@link #cacheFailureMarker}。
     *
     * <p>⚠️ 形参名 {@code MerchantId} 必须保留：{@code @RequestParam} 未写显式名依赖它解析，
     * 缓存侧 {@code key="#MerchantId"} 也依赖同一个形参名（{@code -parameters} 已开）。
     */
    public Map<String, Object> goodsSelect(String MerchantId) {
        try {
            return goodsCacheService.listByMerchant(MerchantId);
        } catch (Exception e) {
            e.printStackTrace();
            return cacheFailureMarker("查询失败");
        }
    }

    /** 商城端列表的默认页大小（首页 / 猜你喜欢）—— 取 24：4 列栅格整 6 行，避免末行只挂 1 张卡。 */
    public static final int DEFAULT_PAGE_SIZE = 24;

    /** 商城端列表的**硬上限**（安全兜底：`limit=99999` 一律夹到 100）。 */
    public static final int MAX_PAGE_SIZE = PageLimits.MAX_PAGE_SIZE;

    /**
     * 客户端首页商品列表。**委托 + 响应组装** —— 注解与 {@code SELECT *} 都在 {@link GoodsCacheService#listHome}。
     *
     * <p>⚠️ 块 7 收尾 · P1：本端点从「一次给全量」改为**真分页**。
     * {@code limit} 缺省 24、上限 100（{@link #normalizePageSize}）；{@code offset} 缺省 0。
     * {@code merchantId} 可选：店铺页按本店过滤时必须传 —— 分页后不能再「拉全量再前端过滤」，
     * 否则本店商品不在第一页时整页空白。
     */
    @CircuitBreaker(name = "mallMysqlRead")
    public Map<String, Object> goodsSelectHome(Integer limit, Integer offset, String merchantId) {
        try {
            return goodsCacheService.listHome(normalizePageSize(limit), normalizeOffset(offset),
                    isBlank(merchantId) ? null : merchantId.trim());
        } catch (Exception e) {
            e.printStackTrace();
            return cacheFailureMarker("查询失败");
        }
    }

    /** 页大小归一：缺省 {@link #DEFAULT_PAGE_SIZE}，负 / 0 同缺省，超上限夹到 {@link #MAX_PAGE_SIZE}。 */
    public static int normalizePageSize(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    /** 偏移归一：缺省 / 负数一律 0（`LIMIT ? OFFSET ?` 不接受负偏移）。 */
    public static int normalizeOffset(Integer offset) {
        return offset == null || offset < 0 ? 0 : offset;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 某商家某商品的全部型号列表。**委托 + 响应组装** —— 见 {@link GoodsCacheService#listCodes}。
     *
     * <p>⚠️ 形参名必须保留，理由同 {@link #goodsSelect}。
     */
    public Map<String, Object> GoodsProductCodeSelect(String MerchantId, String GoodName) {
        try {
            return goodsCacheService.listCodes(MerchantId, GoodName);
        } catch (Exception e) {
            e.printStackTrace();
            // ⚠️ 传 null ⇒ marker 里**没有 `msg` 键**。这不是省略，是与改前逐字相同：
            //    缓存侧原来的 catch 就只 put 了 success（另两个投影有 msg）—— 别「补全」它。
            return cacheFailureMarker(null);
        }
    }

    /**
     * 某商家某商品的多规格矩阵（{@code goods_sku}）—— 详情页 / 上架页读回用（可视化段补丁）。
     *
     * <p>⚠️ <b>直接查库、不走缓存</b>：SKU 是低频读、且只在「商品详情」与「上架页回显」两处用，
     * 为它加一层缓存（含失效点）不值当；查询按 {@code (MerchantId, GoodName)} 走索引，开销可忽略。
     * 这与 {@code goods} 列表走 {@link GoodsCacheService} 是**两条不同的路**，不是漏接。
     *
     * <p>返回 {@code {success:true, list:[…], total:n}}；空列表 = 该商品没有多规格。
     */
    public Map<String, Object> skusByMerchantGoodName(String MerchantId, String GoodName) {
        Map<String, Object> result = new HashMap<>();
        if (MerchantId == null || MerchantId.trim().isEmpty()
                || GoodName == null || GoodName.trim().isEmpty()) {
            result.put("success", false);
            result.put("msg", "缺少参数：MerchantId / GoodName");
            return result;
        }
        List<Map<String, Object>> rows = goodsDao.selectSkusByMerchantGoodName(MerchantId, GoodName);
        result.put("success", true);
        result.put("list", rows);
        result.put("total", rows.size());
        return result;
    }

    /**
     * 技术异常的响应 marker —— 与 2026-10-03（② 笔）之前缓存侧那三个 catch 分支**逐字段相同**。
     *
     * <p><b>为什么把 catch 从 {@code GoodsCacheService} 搬到本类</b>：那两个 catch 把技术异常吞成
     * 一个**正常返回的非 null Map**，于是它被 {@code @Cacheable} 缓存满 TTL（a3 为 5m）——
     * 失败的生命周期从「瞬时」变成「TTL」，且数据修好后调用方在 TTL 内看不到。
     * 修法是让缓存方法**原样上抛**（{@code @Cacheable} 遇异常不写入，L1 / L2 都不写），
     * 由本类接住并组装响应 ⇒ <b>对外零契约变更</b>（仍是 200 + 同一个 marker，**不是** 500）。
     * 这与 {@link #productCodePrice} 的形状一致（那边也是 catch 在 Service、不在缓存方法）。
     *
     * <p><b>本类三个调用方共用它，但传参不同</b>：{@code goodsSelect} / {@code goodsSelectHome}
     * 传 {@code "查询失败"}（改前有 msg），{@code GoodsProductCodeSelect} 传 {@code null}
     * （改前无 msg）。两者的差别是**契约**，不是笔误。
     *
     * @param msg 为 {@code null} 时 marker 里不含 {@code msg} 键（与改前一致），不是写入一个 null
     */
    private static Map<String, Object> cacheFailureMarker(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        if (msg != null) {
            result.put("msg", msg);
        }
        return result;
    }

    /**
     * 按型号查价格。try/catch 与响应组装随本笔搬进本类（**安全性推理见类注释**）。
     *
     * <p>业务事实与失败 marker 都落同一个形状：三元组不存在（Service 侧 0 行 ⇒ null，
     * 且该 null 会被负缓存）与技术异常一律 {@code {success:false}}，**不是** 500。
     * 响应键 {@code goodImg} 与列名 {@code GoodImg} 不同名 —— 这是契约，别「改对」。
     */
    public Map<String, Object> productCodePrice(String MerchantId, String GoodName, String ProductCode) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> row =
                    goodsCacheService.priceByTriple(MerchantId, GoodName, ProductCode);
            if (row == null) {
                // 业务事实：该三元组不存在（Service 侧 0 行 ⇒ null，且该 null 会被负缓存）。
                // 与下面的 catch 分支落**同一个** marker —— 响应逐字段与改前一致，
                // 变的只有缓存行为（改前缓存的是失败 marker，现在缓存的是 null）。
                result.put("success", false);
                return result;
            }
            result.put("success", true);
            result.put("price", row.get("Price"));
            result.put("goodImg", row.get("GoodImg")); // 响应键与列名 GoodImg 不同名
            // ⚠️ 块 5〈折扣〉加：**加性**键（老前端不读它，逐字节等价性不受影响）。
            //    0 / null ⇒ 详情页不画划线价那一行 —— 判据在**前端**，这里原样透出。
            result.put("originalPrice", row.get("OriginalPrice"));
        } catch (Exception e) {
            // 技术异常（2026-10-12〈Bug 1〉起「>1 行」不再从这里来 —— 那条路改走 MIN(Id)）：
            // 这里接住 ⇒ 仍是 200 + {success:false}，**不是 500**（对调用方零契约变更）。
            e.printStackTrace();
            result.put("success", false);
        }
        return result;
    }

    // ==================== 写：下架 / 更新 ====================

    /**
     * 下架商品。方法体逐字搬自原 Controller（两条 DELETE、无条件 success、唯一出口前 evict）。
     *
     * <p>⚠️ <b>C#2（本笔）</b>：本方法删 {@code goods} + {@code productsales} 两表，已加
     * {@link Transactional}；DB 块的 catch 内**必须**调 {@link #markRollbackOnlyIfActive()}，
     * 否则「删了 goods、没删 productsales」会留在库里。
     */
    @CircuitBreaker(name = "mallMysqlWrite")
    @Transactional
    public Map<String, Object> goodsDe(
            String MerchantId,
            String GoodName,
            String ProductCode
    ) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 删除 goods 表
            goodsDao.deleteGoods(MerchantId, GoodName, ProductCode);

            // 删除 productsales 表
            goodsDao.deleteProductsales(MerchantId, GoodName, ProductCode);

            result.put("success", true);
            result.put("msg", "商品下架成功");
        } catch (Exception e) {
            e.printStackTrace();
            // ⚠️ 吞掉异常就必须配这一步（同 goodsAdd）：见类注释「C#2」。
            markRollbackOnlyIfActive();
            result.put("success", false);
            result.put("msg", "下架失败");
        }
        // ⚠️ 本方法的唯一出口 —— 三个写接口共用同一模式，若新增 return 请确认：
        //    ① 该分支不改库，或
        //    ② 在该分支前也调 evictByMerchant()
        // 现有全部 DAO 调用都在 try 内、catch 吞异常**且标记回滚**（C#2 起）
        // ⇒ 唯一出口覆盖 100% 的可能改库路径；失败分支不留半成品。
        goodsCacheService.evictByMerchant(MerchantId);
        return result;
    }

    /**
     * 更新商品。方法体逐字搬自原 Controller。
     *
     * <p>⚠️ 列集判据是 {@code file != null}（**不是** {@code !file.isEmpty()}）：file 非空但空文件时
     * 走带 {@code GoodImg=?} 的那条 SQL、值为 {@code ""}。见 {@link GoodsDao#updateGoods}。
     *
     * <p>⚠️ 形参 {@code GoodType} 方法体内**未使用**，但必须保留 —— 它是既有入参（前端在传）。
     *
     * <p>⚠️ 本方法的**改名**正是使 {@code goodsCodes} / {@code goodsPrice} 的失效只能
     * {@code allEntries} 的原因：两者的键都含 {@code GoodName}，而本方法的 WHERE 是
     * {@code (MerchantId, ProductCode)}，旧名既不在入参、也无处可查（方法体内无任何 SELECT）。
     *
     * <p>⚠️ <b>C#2（本笔）</b>：本方法更新 {@code goods} + {@code productsales} 两表，已加
     * {@link Transactional}；DB 块的 catch 内**必须**调 {@link #markRollbackOnlyIfActive()}，
     * 否则「改了 goods 名、没改 productsales 名」会留在库里（两表名不一致）。
     */
    @CircuitBreaker(name = "mallMysqlWrite")
    @Transactional
    public Map<String, Object> goodsUp(
            String MerchantId,
            String GoodName,
            Double Price,
            String GoodType,
            String ProductCode,
            MultipartFile file
    ) {
        Map<String, Object> result = new HashMap<>();

        // 图片默认沿用旧地址
        String goodImgUrl = "";
        // 图片保存路径
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();

        // 如果上传了新图片
        if (file != null && !file.isEmpty()) {
            // 保存图片，并用**实际落盘**的文件名拼 URL。
            String fileName;
            try {
                fileName = UploadStorage.store(uploadPath, file);
            } catch (Exception e) {
                // TD-07 的「三处不一致」到此统一：三处落盘失败一律显式失败。
                // 本处原先是让 IOException 冒泡 → 500，前端只能看到「更新失败」；
                // 且此前已在别处写过盘，返回 500 让人以为整单没动。现在改成在写库前就返回。
                e.printStackTrace();
                result.put("success", false);
                result.put("msg", "商品图片保存失败");
                return result;
            }
            // 新图片访问地址
            goodImgUrl = "http://localhost:8088/img/" + fileName;
        }

        try {
            // 1. 更新 goods 表：商品名、价格、图片地址
            goodsDao.updateGoods(GoodName, Price, goodImgUrl, MerchantId, ProductCode, file != null);

            // 2. 更新 productsales 表商品名称
            goodsDao.updateProductsalesGoodName(GoodName, MerchantId, ProductCode);

            result.put("success", true);
            result.put("msg", "商品更新成功");
        } catch (Exception e) {
            e.printStackTrace();
            // ⚠️ 吞掉异常就必须配这一步（同 goodsAdd）：见类注释「C#2」。
            markRollbackOnlyIfActive();
            result.put("success", false);
            result.put("msg", "商品更新失败");
        }
        // ⚠️ 本方法的唯一出口 —— 三个写接口共用同一模式，若新增 return 请确认：
        //    ① 该分支不改库，或
        //    ② 在该分支前也调 evictByMerchant()
        // 现有全部 DAO 调用都在 try 内、catch 吞异常**且标记回滚**（C#2 起）
        // ⇒ 唯一出口覆盖 100% 的可能改库路径；失败分支不留半成品。
        goodsCacheService.evictByMerchant(MerchantId);
        return result;
    }

    // ==================== 读：搜索 ====================

    /**
     * 商品搜索。方法体逐字搬自原 Controller —— SQL 构造在 {@link GoodsDao#searchByKeyword}。
     *
     * <p>⚠️ {@code keyword == null ⇒ ""} 这条归一**留在本类**：它是入参规则，不是 SQL
     * （DAO 侧要求非空）。原方法体就是这么分的。
     */
    @CircuitBreaker(name = "mallMysqlRead")
    public Map<String, Object> productSearch(String keyword, Integer limit, Integer offset) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (keyword == null) keyword = "";
            int size = normalizePageSize(limit);
            int skip = normalizeOffset(offset);

            List<Map<String, Object>> goodsList = goodsDao.searchByKeyword(keyword, size, skip);

            result.put("success", true);
            result.put("list", goodsList);
            result.put("limit", size);
            result.put("offset", skip);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", "搜索失败");
            e.printStackTrace();
        }
        return result;
    }

    // ==================== 读：二级类目筛选（C3 块 3 新增） ====================

    /**
     * 按类目 Id（+ 可选关键词）列买家可见商品 —— 新端点 {@code /goods/categoryGoods}。
     *
     * <p>⚠️ <b>不经缓存</b>（同 {@link #productSearch}）：键空间是 {@code TypeId × keyword}，
     * keyword 无界 ⇒ 缓存键会随任意输入膨胀。SQL 在 {@link GoodsDao#selectByTypeId}。
     */
    public Map<String, Object> categoryGoods(Integer TypeId, String keyword, Integer limit, Integer offset) {
        Map<String, Object> result = new HashMap<>();
        if (TypeId == null) {
            result.put("success", false);
            result.put("msg", "缺少参数：TypeId");
            return result;
        }
        try {
            int size = normalizePageSize(limit);
            int skip = normalizeOffset(offset);
            result.put("success", true);
            result.put("list", goodsDao.selectByTypeId(TypeId, keyword, size, skip));
            result.put("limit", size);
            result.put("offset", skip);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败");
        }
        return result;
    }

    /**
     * 商家查自己的提审历史 / 状态 —— 新端点 {@code /goods/myApplications}。
     *
     * <p>纯委托 {@link GoodsAuditService#myApplications}（业务在那边：参数校验 + limit 归一）。
     */
    public Map<String, Object> myApplications(String MerchantId, Integer limit) {
        return goodsAuditService.myApplications(MerchantId, limit);
    }

    // ==================== C#2：事务回滚标记 ====================

    /**
     * 三个写方法的 catch **共用**这一句：既然吞掉了异常、不 rethrow，就必须显式把当前事务
     * 标成 rollback-only，否则 {@code @Transactional} 看到的是「正常返回」、照常提交，
     * 第一步的写会留下 —— 加事务等于白加。
     *
     * <p><b>为什么必须有 {@code isActualTransactionActive()} 这道判断</b>：本方法要在
     * **没有事务的上下文**里也安全。{@code @WebMvcTest} 切片（{@code AbstractSmokeTest}）
     * 不加载 DataSource ⇒ 没有 {@code PlatformTransactionManager} ⇒
     * {@code TransactionAutoConfiguration} 的 {@code @EnableTransactionManagement} 不生效
     * ⇒ 既不建代理、也不开事务。此时若直接调
     * {@link TransactionAspectSupport#currentTransactionStatus()} 会抛
     * {@code NoTransactionException} —— 那会把切片的失败路径从「返回失败 map」变成「抛异常」，
     * 是一次静默的行为回归。加了判断后，该上下文下本方法退化为**空操作**，行为与改前逐字相同。
     *
     * <p>⚠️ 回滚标记只对「当前事务」有效：三个写方法都是事务的最外层（调用方
     * {@code GoodsController} 无事务），故标记落在自己这条新事务上，提交时静默回滚
     * （{@code isLocalRollbackOnly()} 路径，**不抛** {@code UnexpectedRollbackException}）。
     */
    private static void markRollbackOnlyIfActive() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    // ==================== 读：审计快照（C3 批 A 新增） ====================

    /**
     * 审计用的商品快照（纯委托 {@link GoodsDao#selectGoodsForAudit}）—— C3 批 A「权限与审计」
     * 样板挂钩的**读**的一半，调用方是 {@code GoodsController.goodsUp}。
     *
     * <p>⚠️ <b>为什么是「读」被提到公共服务上，而不是让 Service 自己写审计</b>：审计写入
     * （{@code AuditService.record}）刻意留在 Controller —— 因为 {@code goodsUp} 带
     * {@code @Transactional}，若在里面写审计，审计行会**加入业务事务**：业务回滚时被一起抹掉，
     * 且「审计失败」会与「业务失败」共用同一条连接状态。放在 Controller 则天然是
     * **业务提交之后**的另一条自动提交语句。详见 {@code AuditService} 类注释。
     *
     * <p>⚠️ 本方法**不加** {@code @Transactional}：它是一次纯读，且被调用两次（改前 / 改后）
     * —— 两次各取一条连接正是想要的（「改后」必须看到**已提交**的新值）。
     */
    public List<Map<String, Object>> snapshotGoodsForAudit(String MerchantId, String ProductCode) {
        return goodsDao.selectGoodsForAudit(MerchantId, ProductCode);
    }
}
