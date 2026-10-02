package com.minke.mallmanagement.goods.controller;

import com.minke.mallmanagement.goods.service.GoodsService;
import com.minke.mallmanagement.goods.service.GoodsTypeCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;


/**
 * 商品域的 HTTP 适配器 —— 9 个商品端点。
 *
 * <p>2026-10-02（笔 2）：本类此前同时承载商品 9 + 订单 8 共 17 个端点，订单那 8 个已拆到
 * {@link com.minke.mallmanagement.order.controller.OrderController}；类级前缀同时从
 * {@code /mysqlGoods} 规范化为 {@code /goods}，旧名 {@code MallManagementGoodsController}
 * 一并弃用。三件事（拆、换前缀、改名）同源 —— 任一处单独进 main 都会造成可观测断裂
 * （前端硬编码 URL 404 / 类名引用悬空），故同笔完成。契约见 docs/agent/api-contract.md。
 *
 * <p>2026-10-02（三层重构第二笔，商品域）：<b>8 个方法体整段搬进
 * {@link GoodsService}</b>（数据访问再下沉 {@link com.minke.mallmanagement.goods.dao.GoodsDao}）。
 * 本类只保留**路由与入参绑定** —— 形参名与顺序**逐字保留**（{@code @RequestParam} 多数未写显式名，
 * 全靠形参名解析，{@code -parameters} 已开），方法签名连 {@code throws IOException} 也原样不动。
 *
 * <p>⚠️ <b>本类不再持有 {@code JdbcTemplate} / {@code GoodsCacheService} / {@code app.upload-dir}</b>：
 * 8 条内联 SQL 进了 {@code GoodsDao}，缓存失效进 {@code GoodsService}，上传目录与落盘也在那里。
 * 剩下的两个协作者各司其职：
 * <ul>
 *   <li>{@link GoodsService} —— 8 个端点的业务入口；</li>
 *   <li>{@link GoodsTypeCacheService} —— **只服务 {@code goodstype} 一个端点**，
 *       因为它的 GET/POST 分支判定按裁决留在本类（见 {@link #goodstype}）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/goods")
@CrossOrigin
public class GoodsController {

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private GoodsTypeCacheService goodsTypeCacheService;

    //这是一个接口（用于提供和更新上架商品的可选类型）
    // ⚠️ 本方法是全类**唯一**没有随三层重构下沉的方法 —— 裁决③：GET/POST 的分支判定权
    //    留在 Controller（路由与「GET 带 GoodType 参数走写分支」等边角行为均不变）。
    //    缓存注解在 Service 上（@Cacheable 只覆盖 listTypes()，写分支不在其方法体内）。
    @RequestMapping(value = "/goodstype", method = {RequestMethod.GET, RequestMethod.POST})
    public Object goodstype(
            @RequestParam(required = false) String GoodType,
            @RequestParam(required = false) Integer GoodTypeNew
    ) {
        // GET 请求：查询所有类型
        // 分支判定权保留在此处 —— 路由与「GET 带 GoodType 参数走写分支」等边角行为均不变。
        // 缓存注解在 Service 上（@Cacheable 只覆盖 listTypes()，写分支不在其方法体内）。
        if (GoodType == null) {
            return goodsTypeCacheService.listTypes();
        }

        // POST 请求：新增类型
        return goodsTypeCacheService.addType(GoodType, GoodTypeNew);
    }

    //这是一个接口用于上架商品
    // 方法体（上传编排 + 两条早退 + 三条语句 + 全部文案 + 唯一出口前的 evict）逐字搬到
    // GoodsService.goodsAdd()，SQL 再下沉 GoodsDao。本方法只保留路由与入参绑定。
    // ⚠️ 提前 return 分支（图片为空 / 落盘失败）**不再清缓存** —— 该路径一行未改库，
    //    清它是纯浪费。这是**设计选择，非缺陷**，见 §11；该不变量随方法体进了 Service。
    @PostMapping("/goodsAdd")
    public Map<String, Object> goodsAdd(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("Price") Double Price,
            @RequestParam("file") MultipartFile file,  // 图片用 MultipartFile
            @RequestParam("GoodType") String GoodType,
            @RequestParam("ProductCode") String ProductCode,
            @RequestParam("GoodTypeNew") int GoodTypeNew
    ) throws IOException {
        return goodsService.goodsAdd(MerchantId, GoodName, Price, file, GoodType, ProductCode, GoodTypeNew);
    }

    //这是一个接口（用于显示商家端查看商品页面外部内容）
    // 三层前方法体已搬到 GoodsCacheService.listByMerchant()；本笔再上移一层：
    // Controller → GoodsService（纯委托）→ GoodsCacheService（缓存注解与 SQL 的落点）。
    // 为什么多这一跳：本域对「Controller 只认 Service」取齐，且缓存读与写共用一条 evict 面 ——
    // 见 GoodsService 类注释。
    // ⚠️ 形参名 MerchantId 必须保留：@RequestParam 未写显式名依赖它解析，
    //    Service 侧 key="#MerchantId" 也依赖同一个形参名（-parameters 已开）。
    @PostMapping("/goodsSelect")
    public Map<String, Object> goodsSelect(
            @RequestParam String MerchantId
    ) {
        return goodsService.goodsSelect(MerchantId);
    }

    //这是一个接口（用于返回商品对应的所有信息）
    // 方法体与缓存注解在 GoodsCacheService.listHome()；本笔再上移一层（纯委托）。
    @PostMapping("/goodsSelectHome")
    public Map<String, Object> goodsSelectHome() {
        return goodsService.goodsSelectHome();
    }

    // 这是一个接口（用于下架商品）
    // 方法体（两条 DELETE、无条件 success、唯一出口前 evict）逐字搬到 GoodsService.goodsDe()。
    // ⚠️ 四条 evict 的理由已收敛到 GoodsCacheService.evictByMerchant() 的 javadoc（D 笔，2026-09-28）。
    @PostMapping("/goodsDe")
    public Map<String, Object> goodsDe(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("ProductCode") String ProductCode
    ) {
        return goodsService.goodsDe(MerchantId, GoodName, ProductCode);
    }

    //这是一个接口（用于根据商品的不同型号切换显示对应的价格）
    // 【2026-09-30】try/catch 与响应组装曾**搬回本方法**（让 Service 侧能技术异常上抛）；
    // 【2026-10-02 本笔】再搬进 GoodsService.productCodePrice() —— 判据是「catch 不能与
    // @Cacheable **同一个 bean**」，而 GoodsService 与 GoodsCacheService 是两个 bean，
    // 异常照常逃出被缓存的方法、缓存拦截器照常不写入 ⇒ 不违反那条决定。
    // ⚠️ 该推理写在 GoodsService 类注释里：**谁把 catch 挪回 GoodsCacheService 类内
    //    （哪怕另起一个方法再自调用），缓存就会静默失效**（cache-layer.md §11 ㊸③）。
    // ⚠️ 形参名必须保留：@RequestParam 与 Service 侧 SpEL 的三段都依赖它们（-parameters 已开，
    //    javap 该方法的 MethodParameters 可见 MerchantId / GoodName / ProductCode）。
    @PostMapping("/productCodePrice")
    public Map<String, Object> productCodePrice(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("ProductCode") String ProductCode
    ) {
        return goodsService.productCodePrice(MerchantId, GoodName, ProductCode);
    }

    //这是一个接口（用于更新商品数据）
    // 方法体（上传编排 + 两条语句 + 全部文案 + 唯一出口前 evict）逐字搬到 GoodsService.goodsUp()。
    // ⚠️ 正是本方法的**改名**使 goodsCodes / goodsPrice 的失效只能 allEntries（键含 GoodName，
    //    而 WHERE 是 (MerchantId, ProductCode)，旧名构造不出）。见 §11「精确 evict 的边界」。
    // ⚠️ 形参 GoodType 是既有入参、方法体内未使用 —— 签名必须原样保留（前端在传，去掉即 400）。
    // ⚠️ 签名里的 throws IOException 与三层前一致（落盘异常已在 Service 侧 catch，本类不会抛）。
    @PostMapping("/goodsUp")
    public Map<String, Object> goodsUp(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("Price") Double Price,
            @RequestParam("GoodType") String GoodType,
            @RequestParam("ProductCode") String ProductCode,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) throws IOException {
        return goodsService.goodsUp(MerchantId, GoodName, Price, GoodType, ProductCode, file);
    }

    // 这是一个接口（根据商家ID和商品名称，获取所有不同的 ProductCode 型号列表）
    // 方法体与缓存注解在 GoodsCacheService.listCodes()；本笔再上移一层（纯委托）。
    // ⚠️ 形参名必须保留：@RequestParam 未写显式名依赖它解析，Service 侧
    //    key="#MerchantId + '::' + #GoodName" 也依赖同一组形参名（-parameters 已开）。
    @PostMapping("/GoodsProductCodeSelect")
    public Map<String, Object> GoodsProductCodeSelect(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName
    ) {
        return goodsService.GoodsProductCodeSelect(MerchantId, GoodName);
    }


    //这是一个接口（用于商品搜索页的内容搜索信息返回）
    // 方法体（含 keyword 的 null 归一）搬到 GoodsService.productSearch()，
    // 动态 SQL 的构造随之下沉 GoodsDao.searchByKeyword()。
    // ⚠️ 该 SQL 的两个已知问题（逐字 LIKE 拼串 + 无 schema 前缀）**本笔不修** —— 见 GoodsDao。
    @PostMapping("/productSearch")
    public Map<String, Object> productSearch(String keyword) {
        return goodsService.productSearch(keyword);
    }

}
