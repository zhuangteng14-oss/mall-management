package com.minke.mallmanagement.goods.controller;

import com.minke.mallmanagement.goods.service.GoodsCacheService;
import com.minke.mallmanagement.goods.service.GoodsTypeCacheService;
import com.minke.mallmanagement.order.service.OrderService;
import com.minke.mallmanagement.shared.upload.UploadStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/mysqlGoods")
@CrossOrigin
public class MallManagementGoodsController {

    @Autowired
    private JdbcTemplate GoodjdbcTemplate;

    @Autowired
    private GoodsTypeCacheService goodsTypeCacheService;

    @Autowired
    private GoodsCacheService goodsCacheService;

    // ===== 订单域（三层重构第一笔，2026-10-02）=====
    // 订单域的全部 SQL / 分支 / 文案已下沉到 OrderService（数据访问再下沉到 OrderDao）。
    // ⚠️ 本类**不再**持有 DistributedLockService / OrderStateMachine / OrderTransitionRecorder：
    // 三者的编排（取锁、生成 CAS 语句、观测投递）都已随方法体进 Service。
    // 切片侧配套见 AbstractSmokeTest 的 @Import —— OrderService / OrderDao 用**真 bean**，
    // 因为 OrderPayIfUpSmokeTest 要钉的是「rows → 文案」这段映射，mock 掉就只剩一句空命题。
    @Autowired
    private OrderService orderService;

    @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}")
    private String uploadDir;

    //这是一个接口（用于提供和更新上架商品的可选类型）
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
    // ⚠️ 写商品必须清**四个** cacheName（否则首页列表 / 该商家的商品列表 / 型号列表 / 单点价格
    //    永不更新）。失效面与全部理由已收敛到 GoodsCacheService.evictByMerchant() 的 javadoc
    //    （D 笔，2026-09-28）：注解一处、理由一处，本方法只负责**在唯一出口前调用它**。
    // ⚠️ 提前 return 分支（图片为空）**不再清缓存** —— 该路径一行未改库，清它是纯浪费
    //    （goodsCodes / goodsPrice 的整片清要 SCAN 删）。这是**设计选择，非缺陷**，见 §11。
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

        Map<String, Object> result = new HashMap<>();
        int OrderVolume = 0;

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

        // 插入商品表 goods
        String sqlGoods = "INSERT INTO mall_management_sql.goods "
                + "(MerchantId, GoodName, Price, GoodImg, GoodType, ProductCode, GoodTypeNew) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try {
            GoodjdbcTemplate.update(sqlGoods,
                    MerchantId,
                    GoodName,
                    Price,
                    GoodImg,       // 存入后端图片地址
                    GoodType,
                    ProductCode,
                    GoodTypeNew
            );

            // 获取刚插入的商品自增ID
            int goodsId = GoodjdbcTemplate.queryForObject(
                    "SELECT LAST_INSERT_ID()", Integer.class
            );

            // 插入 productsales表
            String sqlSales = "INSERT INTO mall_management_sql.productsales "
                    + "(MerchantId, GoodName, ProductCode,OrderVolume) "
                    + "VALUES (?, ?, ?, ?)";

            GoodjdbcTemplate.update(sqlSales,
                    MerchantId,
                    GoodName,
                    ProductCode,
                    0
            );

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
        // 现有全部 JDBC 都在 try 内、catch 吞异常 ⇒ 唯一出口覆盖 100% 的可能改库路径。
        goodsCacheService.evictByMerchant(MerchantId);
        return result;
    }

    //这是一个接口（用于显示商家端查看商品页面外部内容）
    // 方法体搬到 GoodsCacheService.listByMerchant()（逐字搬运，含 SQL / try-catch / 返回结构）。
    // 本方法只保留路由与委托 —— 无参数分支（只有一个必填 @RequestParam），故不需要像
    // goodstype 那样在这里保留分支判定。⚠️ 形参名 MerchantId 必须保留：@RequestParam 未写显式名
    // 依赖它解析，Service 侧 key="#MerchantId" 也依赖同一个形参名（-parameters 已开）。
    // 缓存：cacheName = goodsByMerchant、key = #MerchantId、sync = true（单飞防击穿）；
    //      失效面 = goodsAdd / goodsUp / goodsDe 在各自唯一出口前**显式调用**
    //      GoodsCacheService.evictByMerchant()（D 笔，2026-09-28；注解已搬到该 Service 方法上，
    //      控制器不再被代理）—— 其中**本 cacheName 那一条按 MerchantId 精确清**。
    @PostMapping("/goodsSelect")
    public Map<String, Object> goodsSelect(
            @RequestParam String MerchantId
    ) {
        return goodsCacheService.listByMerchant(MerchantId);
    }

    //这是一个接口（用于返回商品对应的所有信息）
    // 方法体与缓存注解都搬到 GoodsCacheService.listHome()（逐字搬运，含 SQL / per-row 兜底 /
    // 异常处理）。本方法只保留路由与委托 —— 原分支结构为空（无参数、无 GET/POST 分支），
    // 故不需要像 goodstype 那样在这里保留分支判定。
    // 缓存：cacheName = goodsHome、key = 'all'（全局单键）、sync = true（单飞防击穿）；
    //      失效面 = goodsAdd / goodsUp / goodsDe 在各自唯一出口前**显式调用**
    //      GoodsCacheService.evictByMerchant()（D 笔，2026-09-28；注解在 Service 上）。
    @PostMapping("/goodsSelectHome")
    public Map<String, Object> goodsSelectHome() {
        return goodsCacheService.listHome();
    }

    // 这是一个接口（用于下架商品）
    // ⚠️ 四条 evict 的理由同 goodsAdd，已收敛到 GoodsCacheService.evictByMerchant() 的 javadoc
    //    （D 笔，2026-09-28）。本条只需知道一件事：**本方法单看是能精确清的** —— 它的 WHERE
    //    恰好就是键的那三段、全在入参；取 allEntries 是为了与 goodsUp（因改名做不到精确）保持
    //    **同一精度**，免去「哪条是哪种精度」的记忆负担。
    @PostMapping("/goodsDe")
    public Map<String, Object> goodsDe(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("ProductCode") String ProductCode
    ) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 删除 goods 表
            String sqlDelGoods = "DELETE FROM mall_management_sql.goods "
                    + "WHERE MerchantId=? AND GoodName=? AND ProductCode=?";
            GoodjdbcTemplate.update(sqlDelGoods, MerchantId, GoodName, ProductCode);

            // 删除 productsales 表
            String sqlDelSales = "DELETE FROM mall_management_sql.productsales "
                    + "WHERE MerchantId=? AND GoodName=? AND ProductCode=?";
            GoodjdbcTemplate.update(sqlDelSales, MerchantId, GoodName, ProductCode);

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
        // 现有全部 JDBC 都在 try 内、catch 吞异常 ⇒ 唯一出口覆盖 100% 的可能改库路径。
        goodsCacheService.evictByMerchant(MerchantId);
        return result;
    }

    //这是一个接口（用于根据商品的不同型号切换显示对应的价格）
    // 【2026-09-30 改】SQL 仍在 GoodsCacheService.priceByTriple()（缓存注解的落点），但
    // **try/catch 与响应组装搬回了本方法** —— 那是让 Service 侧能「技术异常上抛」的前提，
    // 而上抛是「不写入缓存」的**唯一**表达方式（sync=true 禁用 unless）。与
    // UserCacheService.publicInfo() / MallManagementUserController.userSelect 同款。
    // ⚠️ 平行地：不得把 catch 挪回 Service 的**同类内另一个**方法 —— 自调用不走代理，
    //    @Cacheable 会静默失效（cache-layer.md §11 ㊸③）。要那样做就必须拆成第二个 bean。
    // 缓存：cacheName = goodsPrice、key = #MerchantId + '::' + #GoodName + '::' + #ProductCode
    //      （**三维复合键**，本项目首个）、sync = true（单飞防击穿）；
    //      失效面 = goodsAdd / goodsUp / goodsDe **三者，全部 allEntries** —— 理由
    //      （goodsUp 改 GoodName ⇒ 旧键构造不出，且精确清会呈现「条件性正确」）见
    //      GoodsCacheService.priceByTriple() 的注释与本文件 goodsAdd 的注释第 1 点。
    // ⚠️ 形参名必须保留：@RequestParam 与 Service 侧 SpEL 的三段都依赖它们（-parameters 已开，
    //    javap 该方法的 MethodParameters 可见 MerchantId / GoodName / ProductCode）。
    @PostMapping("/productCodePrice")
    public Map<String, Object> productCodePrice(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("ProductCode") String ProductCode
    ) {
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
            // 技术异常（含 Service 侧「>1 行」抛出的 IncorrectResultSizeDataAccessException）：
            // 这里接住 ⇒ 仍是 200 + {success:false}，**不是 500**（对调用方零契约变更）。
            e.printStackTrace();
            result.put("success", false);
        }
        return result;
    }

    //这是一个接口（用于更新商品数据）
    // ⚠️ 四条 evict 的理由同 goodsAdd，已收敛到 GoodsCacheService.evictByMerchant() 的 javadoc
    //    （D 笔，2026-09-28）。本条只需知道一件事：**正是本方法的改名**使 goodsCodes / goodsPrice
    //    只能 allEntries —— 两者的键都含 GoodName，而本方法的 WHERE 是 (MerchantId, ProductCode)，
    //    旧 GoodName 既不在入参、也无处可查（方法体内无任何 SELECT）⇒ 旧键 `M::旧G[::P]` 构造不出；
    //    而改名的同时新键也要清 ⇒ 精确清需要清两个、其中一个无解。见 §11「精确 evict 的边界」。
    @PostMapping("/goodsUp")
    public Map<String, Object> goodsUp(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("Price") Double Price,
            @RequestParam("GoodType") String GoodType,
            @RequestParam("ProductCode") String ProductCode,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) throws IOException {
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
            String sqlUpdateGoods = "UPDATE mall_management_sql.goods "
                    + "SET GoodName=?, Price=?" + (file!=null?", GoodImg=?":"")
                    + " WHERE MerchantId=? AND ProductCode=?";

            if(file != null){
                GoodjdbcTemplate.update(sqlUpdateGoods,
                        GoodName, Price, goodImgUrl, MerchantId, ProductCode);
            }else{
                GoodjdbcTemplate.update(sqlUpdateGoods,
                        GoodName, Price, MerchantId, ProductCode);
            }

            // 2. 更新 productsales 表商品名称
            String sqlUpdateSales = "UPDATE mall_management_sql.productsales "
                    + "SET GoodName=? WHERE MerchantId=? AND ProductCode=?";
            GoodjdbcTemplate.update(sqlUpdateSales, GoodName, MerchantId, ProductCode);

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
        // 现有全部 JDBC 都在 try 内、catch 吞异常 ⇒ 唯一出口覆盖 100% 的可能改库路径。
        goodsCacheService.evictByMerchant(MerchantId);
        return result;
    }

    // 这是一个接口（根据商家ID和商品名称，获取所有不同的 ProductCode 型号列表）
    // 方法体与缓存注解都搬到 GoodsCacheService.listCodes()（逐字搬运，含 SQL / try/catch /
    // 返回结构）。本方法只保留路由与委托。
    // ⚠️ 形参名必须保留：@RequestParam 未写显式名依赖它解析，Service 侧
    // key="#MerchantId + '::' + #GoodName" 也依赖同一组形参名（-parameters 已开）。
    // 缓存：cacheName = goodsCodes、key = #MerchantId + '::' + #GoodName、sync = true（单飞防击穿）；
    //      失效面 = goodsAdd / goodsUp / goodsDe，三个都 allEntries —— 键含 GoodName，而 goodsUp
    //      会改 GoodName（其 WHERE 是 (MerchantId, ProductCode)，旧 GoodName 构造不出）。
    //      ⚠️ 这三条 evict 在本方法存在之前就挂上了（2026-09-28，随 goodsPrice 那笔），
    //      即「evict 清单领先于读接口落地」—— 落地前每次写都在清一个空片。见 §11「先加 evict 再加读」。
    @PostMapping("/GoodsProductCodeSelect")
    public Map<String, Object> GoodsProductCodeSelect(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName
    ) {
        return goodsCacheService.listCodes(MerchantId, GoodName);
    }


    //这是一个接口（用于商品搜索页的内容搜索信息返回）
    @PostMapping("/productSearch")
    public Map<String, Object> productSearch(String keyword) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (keyword == null) keyword = "";

            // 拆分关键词为单个字符（用于 50% 相似匹配）
            String[] keys = keyword.trim().split("");
            StringBuilder nameSql = new StringBuilder();
            for (String s : keys) {
                if (!s.isEmpty()) {
                    nameSql.append(" OR GoodName LIKE '%").append(s).append("%' ");
                }
            }

            // 最终查询 SQL
            String sql = "SELECT * FROM goods WHERE 1=1 " +
                    "AND (GoodType = ? " +  // 商品类型完全匹配
                    nameSql + ")";         // 商品名包含任意一个字

            // 执行查询
            List<Map<String, Object>> goodsList = GoodjdbcTemplate.queryForList(sql, keyword.trim());

            result.put("success", true);
            result.put("list", goodsList);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", "搜索失败");
            e.printStackTrace();
        }
        return result;
    }

    //创建订单时要录入数据库的信息
    // 方法体（锁编排 + 临界区内查重 + INSERT + 出生观测 + 全部文案）搬到 OrderService.orderAdd()。
    // 本方法只保留路由与入参绑定 —— ⚠️ 形参名与顺序**必须**逐字保留：@RequestParam 未写显式名，
    // 全靠形参名解析（-parameters 已开）。
    // ⚠️ 取锁、查重、SELECT/INSERT 三段现在都在 Service 侧：本类**不再**引 DistributedLockService，
    //    也**不再**直接引 JdbcTemplate 做订单 SQL —— 这是「数据出口收成一处」在本域的落地。
    @PostMapping("/orderAdd")
    public Map<String, Object> orderAdd(
            @RequestParam("CustomerId") String CustomerId,
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("OrderId") String OrderId,
            @RequestParam("Price") float Price,
            @RequestParam("ProductInformation") String ProductInformation,
            @RequestParam("Images") String Images,
            @RequestParam("PayIf") int PayIf,
            @RequestParam("DeliveryIf") int DeliveryIf,
            @RequestParam("ReceiveIf") int ReceiveIf
    ) {
        return orderService.orderAdd(
                CustomerId, MerchantId, OrderId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf);
    }

    //购物车搜索订单
    // 方法体（SQL / try-catch / 返回结构）逐字搬到 OrderService.orderSelect()，本方法只保留路由与委托
    // —— 与 goodsSelect / goodsSelectHome / GoodsProductCodeSelect 同款（「方法体搬到…本方法只保留路由与委托」）。
    @PostMapping("/orderSelect")
    public Map<String, Object> orderSelect(
            @RequestParam("customerId") String customerId
    ) {
        return orderService.orderSelect(customerId);
    }

    //订单中心搜索订单
    @PostMapping("/orderDeliveryIfSelect")
    public Map<String, Object> orderDeliveryIfSelect(@RequestParam("merchantId") String merchantId) {
        return orderService.orderDeliveryIfSelect(merchantId);
    }

    //商家端已经完成订单信息
    // ⚠️ status 的默认值 "active" 留在本方法：它是**入参绑定**（@RequestParam 的 defaultValue），
    //    不是业务规则 —— 搬进 Service 就得在 Service 里再写一遍默认值，两处会漂移。
    @PostMapping("/orderDeliverySelect")
    public Map<String, Object> orderDeliverySelect(
            @RequestParam("merchantId") String merchantId,
            @RequestParam(value = "status", defaultValue = "active") String status
    ) {
        return orderService.orderDeliverySelect(merchantId, status);
    }


    //历史订单信息页面信息
    // 方法体（含「用户ID不能为空」那条先于查库的分支）逐字搬到 OrderService.historicalOrdersSelect()。
    @PostMapping("/historicalOrdersSelect")
    public Map<String, Object> historicalOrdersSelect(@RequestParam("customerId") String customerId) {
        return orderService.historicalOrdersSelect(customerId);
    }



    //付款按钮更新信息
    // 方法体（取锁 + 状态机 SQL + 分支 + 文案 + 观测）逐字搬到 OrderService.payIfUp()。
    // ⚠️ 锁 tag = order-pay 随之进 Service：A3 的 A-S2a-1a 是按 tag 读锁指标的，不许换。
    // ⚠️ 两条文案「付款成功」/「订单不存在或已付款」也被 A-S3-2 逐字断言，且已随方法下沉
    //    （不在本类留参数）—— 理由是文案与它对应的分支不可分，拆开会让同一处改动要改两处。
    @PostMapping("/orderPayIfUp")
    public Map<String, Object> orderPayIfUp(@RequestParam("orderId") String orderId) {
        return orderService.payIfUp(orderId);
    }

    //收货按钮更新信息
    // ⚠️ B2：锁 tag = order-receive。净正确性收益为 0（理由同付款）。
    @PostMapping("/orderReceiveIfUp")
    public Map<String, Object> orderReceiveIfUp(@RequestParam("orderId") String orderId) {
        return orderService.receiveIfUp(orderId);
    }

    //发货按钮更新信息
    // ⚠️ B2：锁 tag = order-delivery。净正确性收益为 0（理由同付款）。
    @PostMapping("/orderDeliveryIfUp")
    public Map<String, Object> orderDeliveryIfUp(@RequestParam("orderId") String orderId) {
        return orderService.deliveryIfUp(orderId);
    }
}
