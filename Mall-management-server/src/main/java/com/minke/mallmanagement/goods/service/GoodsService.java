package com.minke.mallmanagement.goods.service;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.shared.upload.UploadStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
 * 三个写方法（{@link #goodsAdd} / {@link #goodsDe} / {@link #goodsUp}）各自在**唯一出口前**
 * 调用 {@link GoodsCacheService#evictByMerchant}。该调用随方法体一起从 Controller 搬来 ——
 * 逻辑在哪，它保护的失效就在哪。「早退分支不清缓存」（{@code goodsAdd} 的两条早退）也一并保留：
 * 那些路径一行未改库，清它是纯浪费。
 */
@Service
public class GoodsService {

    private final GoodsDao goodsDao;
    private final GoodsCacheService goodsCacheService;
    private final String uploadDir;

    public GoodsService(GoodsDao goodsDao,
                        GoodsCacheService goodsCacheService,
                        @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}") String uploadDir) {
        this.goodsDao = goodsDao;
        this.goodsCacheService = goodsCacheService;
        this.uploadDir = uploadDir;
    }

    // ==================== 写：上架 ====================

    /**
     * 上架商品。方法体逐字搬自原 Controller（含两条早退、三条语句的顺序、唯一的 evict 出口）。
     *
     * <p>⚠️ 两条早退（图片为空 / 落盘失败）都**不调** {@code evictByMerchant} —— 它们没有改库。
     * 唯一出口前的那次 evict 覆盖了 100% 可能改库的路径。
     */
    public Map<String, Object> goodsAdd(
            String MerchantId,
            String GoodName,
            Double Price,
            MultipartFile file,
            String GoodType,
            String ProductCode,
            int GoodTypeNew
    ) {
        Map<String, Object> result = new HashMap<>();
        int OrderVolume = 0;   // ⚠️ 死变量，照搬（原 goodsAdd 同款）

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
            // 插入商品表 goods
            goodsDao.insertGoods(new GoodsDao.GoodsRow(
                    MerchantId, GoodName, Price, GoodImg, GoodType, ProductCode, GoodTypeNew));

            // 获取刚插入的商品自增ID（⚠️ 死变量，见类注释第 1 条）
            int goodsId = goodsDao.lastInsertId();

            // 插入 productsales表
            goodsDao.insertProductsales(MerchantId, GoodName, ProductCode);

            result.put("success", true);
            result.put("msg", "商品上架成功！");
            result.put("GoodImg", GoodImg);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "商品上架失败");
        }

        // ⚠️ 本方法的唯一出口 —— 三个写接口共用同一模式，若新增 return 请确认：
        //    ① 该分支不改库，或
        //    ② 在该分支前也调 evictByMerchant()
        // 现有全部 DAO 调用都在 try 内、catch 吞异常 ⇒ 唯一出口覆盖 100% 的可能改库路径。
        goodsCacheService.evictByMerchant(MerchantId);
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

    /** 客户端首页商品列表。**委托 + 响应组装** —— 注解与 {@code SELECT *} 都在 {@link GoodsCacheService#listHome}。 */
    public Map<String, Object> goodsSelectHome() {
        try {
            return goodsCacheService.listHome();
        } catch (Exception e) {
            e.printStackTrace();
            return cacheFailureMarker("查询失败");
        }
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
        } catch (Exception e) {
            // 技术异常（含缓存 Service 侧「>1 行」抛出的 IncorrectResultSizeDataAccessException）：
            // 这里接住 ⇒ 仍是 200 + {success:false}，**不是 500**（对调用方零契约变更）。
            e.printStackTrace();
            result.put("success", false);
        }
        return result;
    }

    // ==================== 写：下架 / 更新 ====================

    /** 下架商品。方法体逐字搬自原 Controller（两条 DELETE、无条件 success、唯一出口前 evict）。 */
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
            result.put("success", false);
            result.put("msg", "下架失败");
        }
        // ⚠️ 本方法的唯一出口 —— 三个写接口共用同一模式，若新增 return 请确认：
        //    ① 该分支不改库，或
        //    ② 在该分支前也调 evictByMerchant()
        // 现有全部 DAO 调用都在 try 内、catch 吞异常 ⇒ 唯一出口覆盖 100% 的可能改库路径。
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
     */
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
            result.put("success", false);
            result.put("msg", "商品更新失败");
        }
        // ⚠️ 本方法的唯一出口 —— 三个写接口共用同一模式，若新增 return 请确认：
        //    ① 该分支不改库，或
        //    ② 在该分支前也调 evictByMerchant()
        // 现有全部 DAO 调用都在 try 内、catch 吞异常 ⇒ 唯一出口覆盖 100% 的可能改库路径。
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
    public Map<String, Object> productSearch(String keyword) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (keyword == null) keyword = "";

            List<Map<String, Object>> goodsList = goodsDao.searchByKeyword(keyword);

            result.put("success", true);
            result.put("list", goodsList);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", "搜索失败");
            e.printStackTrace();
        }
        return result;
    }
}
