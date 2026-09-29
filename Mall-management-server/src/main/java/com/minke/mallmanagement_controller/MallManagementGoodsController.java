package com.minke.mallmanagement_controller;

import com.minke.mallmanagement_service.DistributedLockService;
import com.minke.mallmanagement_service.DistributedLockService.LockName;
import com.minke.mallmanagement_service.GoodsCacheService;
import com.minke.mallmanagement_service.GoodsTypeCacheService;
import com.minke.mallmanagement_service.OrderAction;
import com.minke.mallmanagement_service.OrderStateMachine;
import com.minke.mallmanagement_service.OrderTransitionRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
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

    @Autowired
    private DistributedLockService distributedLockService;

    // ===== B3：订单状态机与它的观测出口 =====
    // 两个都是 @Service/@Component ⇒ **不在 @WebMvcTest 切片里**，故 AbstractSmokeTest 必须
    // 提供替身，否则全部 smoke 用例在**上下文加载期**就红（该基类里这个坑已记录过五次）。
    // 其中 OrderStateMachine 用的是**真 bean**（@Import）：它无依赖，且让 golden copy 钉住的
    // 那条 UPDATE 语句真的流经 smoke 用例 —— 比给它一个返回 null 的替身强得多。

    @Autowired
    private OrderStateMachine orderStateMachine;

    /** 观测编排（读回三位 / 推前后态 / 投递时间线）。本类只管分支与文案，观测全权交给它。 */
    @Autowired
    private OrderTransitionRecorder orderTransitionRecorder;

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

        // 获取图片文件名
        String originalFilename = file.getOriginalFilename();
        File saveFile = uploadPath.resolve(originalFilename).toFile();

        // 保存图片（已存在则不重复保存，不报错）
        try {
            if (!saveFile.exists()) {
                file.transferTo(saveFile);
            }
        } catch (Exception e) {
            System.out.println("商品图片已存在，继续上架");
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
    // 方法体搬到 GoodsCacheService.priceByTriple()（逐字搬运，含 SQL / queryForMap / try-catch /
    // 返回结构）。本方法只保留路由与委托 —— 无形参分支。
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
        return goodsCacheService.priceByTriple(MerchantId, GoodName, ProductCode);
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
            String fileName = file.getOriginalFilename();
            File saveFile = uploadPath.resolve(fileName).toFile();
            // 保存图片
            if (!saveFile.exists()) {
                file.transferTo(saveFile);
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
    // ⚠️ B2：整段临界区在 Redisson 锁下执行（锁 key = OrderId，指标 tag = order-add）。
    //    锁**单独**拦不住重复行 —— 20 个请求排队进场、每个照样裸 INSERT，只是把并发变成串行、
    //    仍插 20 行；本笔的修复是「锁 + 临界区内先查后插」**两条，缺一不可**（见 orderAddLocked）。
    //    拿不到锁（Redis 不可达 / 争用超时）⇒ fail-open 直行；对 orderAdd 的完整推演见
    //    DistributedLockService.runLocked 的 javadoc。
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
        return distributedLockService.runLocked(LockName.ORDER_ADD, OrderId, () -> orderAddLocked(
                CustomerId, MerchantId, OrderId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf));
    }

    /**
     * orderAdd 的临界区。**原方法体逐字未动**（B2 的边界：只加一层锁，不动既有分支），
     * 仅在开头**插入**了查重。
     *
     * <p>⚠️ 本方法**不得**加 {@code @Transactional}（加了则本笔的修复立刻失效）：事务的提交发生在
     * 方法返回**之后**，而锁在 {@code runLocked} 的 {@code finally} 里、即方法返回**之前**已释放 ——
     * 后进者拿到锁后的 {@code COUNT(*)} 在 RR 下读的是自己事务开始时的快照、**看不到前一个未提交的行**
     * ⇒ 仍插 2 行。不加事务时 autocommit 让 INSERT 在释放锁**之前**就提交，这正是查重成立的前提。
     */
    private Map<String, Object> orderAddLocked(
            String CustomerId, String MerchantId, String OrderId, float Price,
            String ProductInformation, String Images, int PayIf, int DeliveryIf, int ReceiveIf) {
        Map<String, Object> result = new HashMap<>();
        // ⚠️ 临界区内先查后插 —— 这一段是 B2 对「同一 OrderId 落库 2 行」的真修复（A-S2a-1）：
        //    · 只有锁：并发变串行，但每个请求照旧裸 INSERT ⇒ 仍插 20 行；
        //    · 只有查重：查与插之间的 TOCTOU 窗口仍在 ⇒ 两个请求可以同时查到 0 行。
        //    OrderId 由前端每次下单生成（ProductPage.vue 的 generateOrderId()）、**不复用** ⇒
        //    查重不会挡掉正常下单，只会挡「同一 OrderId 的重复提交」。
        try {
            Integer sameOrderId = GoodjdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM mall_management_sql.`order` WHERE OrderId = ?",
                    Integer.class, OrderId);
            if (sameOrderId != null && sameOrderId > 0) {
                result.put("success", false);
                result.put("msg", "订单已存在，请勿重复提交");
                return result;
            }
        } catch (Exception e) {
            // 查重本身失败（例如库连不上）：**不阻断下单**，继续走原来的 INSERT 路径 ——
            // 与本次改动之前的行为一致（原先没有这一步，照样 INSERT，由下面的 catch 兜住）。
            // 这条 fail-open 与本方法的锁一样是刻意的：查重是「防重复」的加固，不是下单的前置条件。
            e.printStackTrace();
        }

        String sql = "INSERT INTO mall_management_sql.order " +
                "(OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf, CreateTime) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        try {
            int rows = GoodjdbcTemplate.update(sql,
                    OrderId,
                    CustomerId,
                    MerchantId,
                    Price,
                    ProductInformation,
                    Images,
                    PayIf,
                    DeliveryIf,
                    ReceiveIf
            );
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "订单添加成功");
                // B3：只在**真的插进去之后**观测出生。放在这里而不是方法开头，是因为
                // 「订单已存在」那条早退分支并没有产生一个新状态，不该记成一次出生。
                orderTransitionRecorder.created(OrderId, PayIf, DeliveryIf, ReceiveIf);
            } else {
                result.put("success", false);
                result.put("msg", "订单添加失败");
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "数据库错误：" + e.getMessage());
        }
        return result;
    }

    //购物车搜索订单
    @PostMapping("/orderSelect")
    public Map<String, Object> orderSelect(
            @RequestParam("customerId") String customerId
    ) {
        Map<String, Object> result = new HashMap<>();
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,CreateTime,SettlementDate " +
                "FROM mall_management_sql.`order` WHERE CustomerId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        try {
            List<Map<String, Object>> list = GoodjdbcTemplate.queryForList(sql, customerId);
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

    //订单中心搜索订单
    @PostMapping("/orderDeliveryIfSelect")
    public Map<String, Object> orderDeliveryIfSelect(@RequestParam("merchantId") String merchantId) {
        Map<String, Object> result = new HashMap<>();
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,CreateTime,SettlementDate " +
                "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        try {
            List<Map<String, Object>> list = GoodjdbcTemplate.queryForList(sql, merchantId);
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

    //商家端已经完成订单信息
    @PostMapping("/orderDeliverySelect")
    public Map<String, Object> orderDeliverySelect(
            @RequestParam("merchantId") String merchantId,
            @RequestParam(value = "status", defaultValue = "active") String status
    ) {
        Map<String, Object> result = new HashMap<>();
        String sql;
        if ("completed".equals(status)) {
            sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,SettlementDate " +
                    "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf = 1 ORDER BY CreateTime DESC";
        } else {
            sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,SettlementDate " +
                    "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        }
        try {
            List<Map<String, Object>> list = GoodjdbcTemplate.queryForList(sql, merchantId);
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }


    //历史订单信息页面信息
    @PostMapping("/historicalOrdersSelect")
    public Map<String, Object> historicalOrdersSelect(@RequestParam("customerId") String customerId) {
        Map<String, Object> result = new HashMap<>();
        if (customerId == null || customerId.trim().isEmpty()) {
            result.put("success", false);
            result.put("msg", "用户ID不能为空");
            return result;
        }
        String sql = "SELECT OrderId, MerchantId, ProductInformation, Images, SettlementDate " +
                "FROM mall_management_sql.`order` " +
                "WHERE CustomerId = ? AND ReceiveIf = 1 " +
                "ORDER BY SettlementDate DESC";
        try {
            List<Map<String, Object>> list = GoodjdbcTemplate.queryForList(sql, customerId);
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }



    //付款按钮更新信息
    // ⚠️ B2：方法体在 Redisson 锁下执行（锁 key = orderId，指标 tag = order-pay）。
    //    条件 UPDATE（AND PayIf = 0）本来就是正确的并发控制 ⇒ **锁在这里净正确性收益为 0**；
    //    买到的是「同单状态流转串行化 + 统一的取锁观测口径」，代价是每请求多一次 Redis 往返。
    //    拿不到锁 ⇒ fail-open 直行 ⇒ 争用下仍恰好一个成功、其余 19 个返回**原**文案 ——
    //    A-S3-2 的守门对象正是这条：锁不许新发任何拒绝文案。
    @PostMapping("/orderPayIfUp")
    public Map<String, Object> orderPayIfUp(@RequestParam("orderId") String orderId) {
        return distributedLockService.runLocked(LockName.ORDER_PAY, orderId, () -> orderPayIfUpLocked(orderId));
    }

    /** orderPayIfUp 的临界区：**响应文案与分支逐字未变**（B3 只把 SQL 字面量收敛进状态机，并加上观测）。 */
    private Map<String, Object> orderPayIfUpLocked(String orderId) {
        return casTransition(OrderAction.PAY, orderId, "付款成功", "订单不存在或已付款");
    }

    //收货按钮更新信息
    // ⚠️ B2：锁 key = orderId，指标 tag = order-receive。净正确性收益为 0（理由同 orderPayIfUp）。
    @PostMapping("/orderReceiveIfUp")
    public Map<String, Object> orderReceiveIfUp(@RequestParam("orderId") String orderId) {
        return distributedLockService.runLocked(LockName.ORDER_RECEIVE, orderId, () -> orderReceiveIfUpLocked(orderId));
    }

    /** orderReceiveIfUp 的临界区：**响应文案与分支逐字未变**（B3 只把 SQL 字面量收敛进状态机，并加上观测）。 */
    private Map<String, Object> orderReceiveIfUpLocked(String orderId) {
        return casTransition(OrderAction.RECEIVE, orderId, "收货成功", "订单未付款或已收货");
    }

    //发货按钮更新信息
    // ⚠️ B2：锁 key = orderId，指标 tag = order-delivery。净正确性收益为 0（理由同 orderPayIfUp）。
    @PostMapping("/orderDeliveryIfUp")
    public Map<String, Object> orderDeliveryIfUp(@RequestParam("orderId") String orderId) {
        return distributedLockService.runLocked(LockName.ORDER_DELIVERY, orderId, () -> orderDeliveryIfUpLocked(orderId));
    }

    /** orderDeliveryIfUp 的临界区：**响应文案与分支逐字未变**（B3 只把 SQL 字面量收敛进状态机，并加上观测）。 */
    private Map<String, Object> orderDeliveryIfUpLocked(String orderId) {
        return casTransition(OrderAction.DELIVER, orderId, "发货成功", "订单不存在或未付款或已发货/已收货");
    }

    // ==================== B3：状态转移的观测出口 ====================

    /**
     * 三个 CAS 端点的**共同临界区**。B2 之前它们是三段逐字重复的方法体；B3 把 SQL 收敛进
     * {@link OrderStateMachine} 后，三段之间只剩「动作」与「两条文案」不同，故合成一处 ——
     * 不是为省行数，而是为了让「同一处改动要同时改三遍、漏一遍就静默漂移」这件事不再可能。
     *
     * <p>⚠️ **分支与文案一个字节都没变**：{@code rows > 0} → 成功文案；{@code rows == 0} →
     * 原样的失败文案（三条各不相同，故由参数传入）；异常 → {@code "数据库错误：" + e.getMessage()}。
     * 这是 A3 的 A-S3-2（20 并发支付里恰好 19 个返回逐字「订单不存在或已付款」）保持绿的前提。
     *
     * <p>观测全部委托给 {@link OrderTransitionRecorder}，**在 UPDATE 返回的这一处立刻调用**
     * （即仍在临界区内）—— 理由见该类注释。本方法因此只剩「执行 + 分支 + 文案」。
     *
     * <p>观测路径**全程 fail-soft**：读回失败、投递失败，都不影响响应体。
     * 状态位已经在 {@code order} 表里改完了，这才是真值；时间线与指标是过程记录。
     */
    private Map<String, Object> casTransition(OrderAction action, String orderId,
                                              String okMsg, String noRowsMsg) {
        Map<String, Object> result = new HashMap<>();
        // SQL 由状态机生成，与 B2 之前硬编码的字面量逐字符相同 ——
        // 由 OrderStateMachineTest.updateSqlIsByteForByteTheLegacyLiteral 钉死。
        String sql = orderStateMachine.updateSql(action);
        try {
            int rows = GoodjdbcTemplate.update(sql, orderId);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", okMsg);
                orderTransitionRecorder.applied(action, orderId);
            } else {
                result.put("success", false);
                result.put("msg", noRowsMsg);
                // 守卫没命中：重复流转 / 单不存在。**既有行为**，本笔不新增任何拒绝文案。
                orderTransitionRecorder.rejected(action, orderId);
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "数据库错误：" + e.getMessage());
            orderTransitionRecorder.errored(action, orderId);
        }
        return result;
    }
}
