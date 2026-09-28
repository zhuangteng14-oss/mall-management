package com.minke.mallmanagement_controller;

import com.minke.mallmanagement_service.GoodsCacheService;
import com.minke.mallmanagement_service.GoodsTypeCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
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
    //    永不更新）。五点必读：
    //   1. 四条 evict 分两种精度，各自成立：
    //      · goodsHome 用 allEntries=true —— 在本 cacheName 上是**零精度损失**：只有 'all' 一个键，
    //        清整片 == 清那一个键；且结构上不会出现「改了 key 忘了改 evict」的失配。
    //      · goodsByMerchant 用 key="#MerchantId" **精确清** —— 本方法的写入集合由 MerchantId 限定
    //        （INSERT 的 MerchantId 列 = 入参）⇒ 改动集合 ⊆ 该键的集合，**不存在漏清**；反过来
    //        多清一次是保守无害的。⚠️ 已知边界：MerchantId 是 char(20) 且 MySQL 比较**忽略尾空格
    //        与大小写**，而缓存键是字符串精确比较 ⇒ 传 "1 " 能改到 "1" 的行，却清不到
    //        goodsByMerchant::1（陈旧到 TTL 自愈）。要对此免疫就只能 allEntries，代价是别家列表
    //        被顺带清。见 cache-layer.md §11。
    //      · goodsCodes / goodsPrice 各用 allEntries=true —— **不是「顺手统一」**：两者的键都含
    //        GoodName，而 goodsUp 会改 GoodName 且它的 WHERE 是 (MerchantId, ProductCode)
    //        ⇒ 旧 GoodName 既不在入参、也无处可查 ⇒ 旧键构造不出。更要紧的是**形态**：精确清在
    //        「未改名」时正确、改名时**静默**漏清（条件性正确）—— 测试里全绿、只在改名时错，
    //        比整片清危险。判据：写接口的 WHERE/入参**是否含键的每个分量的旧值**，缺一个即不可精确。
    //        ⚠️ 代价与前两者不同：这两个 cacheName 的键数随 商家 × 商品名（× 型号）增长，每次写
    //        整片清（L2 侧按前缀 SCAN 删），不像 goodsHome 那样零损失。见 §11。
    //   2. 为什么是 @Caching 而不是四个 @CacheEvict：Spring 的 @CacheEvict **不是 @Repeatable**
    //      （spring-context 6.2.6 实证：常量池无 Repeatable，且该包下**没有** CacheEvicts /
    //      Cacheables 容器类 —— 可自行 `unzip -l spring-context-*.jar | grep cache/annotation/` 复核）
    //      ⇒ 同一方法上写两个同名注解**不可编译**。单个 @CacheEvict(cacheNames={A,B}) 只能表达
    //      **同精度**的多命名空间；本例四条分两种精度，故 @Caching 是唯一写法（它也更能兼容
    //      将来混 evict + put 的场景）。
    //   3. beforeInvocation=false（默认，**调用后**清）+ 本方法内部 try/catch 吞异常、永远正常
    //      返回 ⇒ 等价于「每次调用都清」。多清一次是保守且无害的（代价 = 下次冷读回填）。
    //      （提前 return 的路径 —— 如 G:83 图片为空 —— 也会清：注解是挂在方法上的，与代码位置无关。）
    //   4. ⚠️ 本注解使本方法所在的 bean 被 **CGLIB 代理**（此前 Controller 无代理）。
    //      goodsAdd / goodsUp / goodsDe **不在 A3 的接口覆盖面内** ⇒ A3 全绿证明不了这三个
    //      接口的路由没被代理打坏，必须另打真实请求验证（本轮验证 b 组）。
    //   5. 为什么**本方法**也要清 goodsPrice：它会改变一个**已存在**三元组的答案 —— 0 行→1 行
    //      （失败变价格）、1 行→2 行（价格变失败：goods 表无唯一约束、本方法不查重，而
    //      queryForMap 遇 >1 行抛异常 ⇒ 响应变 {success:false}）。docs §2 该行原把失效面写成
    //      goodsUp / goodsDe 两处，**漏了本方法**（本轮订正）。
    @Caching(evict = {
            @CacheEvict(cacheNames = "goodsHome", allEntries = true),
            @CacheEvict(cacheNames = "goodsByMerchant", key = "#MerchantId"),
            @CacheEvict(cacheNames = "goodsCodes", allEntries = true),
            @CacheEvict(cacheNames = "goodsPrice", allEntries = true)
    })
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

        return result;
    }

    //这是一个接口（用于显示商家端查看商品页面外部内容）
    // 方法体搬到 GoodsCacheService.listByMerchant()（逐字搬运，含 SQL / try-catch / 返回结构）。
    // 本方法只保留路由与委托 —— 无参数分支（只有一个必填 @RequestParam），故不需要像
    // goodstype 那样在这里保留分支判定。⚠️ 形参名 MerchantId 必须保留：@RequestParam 未写显式名
    // 依赖它解析，Service 侧 key="#MerchantId" 也依赖同一个形参名（-parameters 已开）。
    // 缓存：cacheName = goodsByMerchant、key = #MerchantId、sync = true（单飞防击穿）；
    //      失效面 = goodsAdd / goodsUp / goodsDe，**按 MerchantId 精确清**（见那三个方法的 @Caching）。
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
    //      失效面 = goodsAdd / goodsUp / goodsDe（三者都挂 @CacheEvict）。
    @PostMapping("/goodsSelectHome")
    public Map<String, Object> goodsSelectHome() {
        return goodsCacheService.listHome();
    }

    // 这是一个接口（用于下架商品）
    // ⚠️ @Caching 四条 evict 的五点理由同 goodsAdd（零损失 / 精确清 / 两个 allEntries 的理由 /
    //    注解不可重复 / CGLIB 代理）。
    //    · goodsByMerchant：本方法删除集合由 MerchantId 限定（WHERE 的首个条件）⇒ 精确清不漏。
    //    · goodsCodes / goodsPrice：⚠️ 本条**单看是能精确清的** —— 它们的键分别由
    //      (MerchantId, GoodName) 与 (MerchantId, GoodName, ProductCode) 组成，而本方法的 WHERE
    //      恰好就是这三段、全在入参。但同一个 cacheName 上不能既精确又整片清（goodsUp 那条因改名
    //      做不到精确）⇒ 三个写接口取**一致的 allEntries**，避免「哪条是哪种精度」的记忆负担。
    @Caching(evict = {
            @CacheEvict(cacheNames = "goodsHome", allEntries = true),
            @CacheEvict(cacheNames = "goodsByMerchant", key = "#MerchantId"),
            @CacheEvict(cacheNames = "goodsCodes", allEntries = true),
            @CacheEvict(cacheNames = "goodsPrice", allEntries = true)
    })
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
    // ⚠️ @Caching 四条 evict 的五点理由同 goodsAdd（零损失 / 精确清 / 两个 allEntries 的理由 /
    //    注解不可重复 / CGLIB 代理）。
    //    · goodsByMerchant：本方法更新集合由 MerchantId 限定（WHERE 首个条件）⇒ 精确清不漏。
    //      本方法**会改 GoodName**，但该 cacheName 的键只含 MerchantId、不含 GoodName
    //      ⇒ 不存在「旧键变垃圾」的问题，精确清依然成立。
    //    · goodsCodes / goodsPrice：**正是本方法的改名**使这两个投影只能 allEntries —— 两者的键都
    //      含 GoodName，而本方法的 WHERE 是 (MerchantId, ProductCode)，**旧 GoodName 既不在入参、
    //      也无处可查**（方法体内无任何 SELECT）⇒ 旧键 `M::旧G[::P]` 构造不出。而且改名的同时
    //      新键也要清（同一行从旧三元组搬到新三元组）⇒ 精确清需要清两个、其中一个无解。
    //      ⚠️ 若硬写精确清，它在**未改名**时看着完全正确（`M::G::P` 里的 G 就是入参），只在改名时
    //      静默漏清 —— 这种「条件性正确」比整片清危险得多（测试全绿）。本方法还改 Price / GoodImg
    //      ⇒ goodsPrice 的值本就该整片清。见 §11「精确 evict 的边界」。
    @Caching(evict = {
            @CacheEvict(cacheNames = "goodsHome", allEntries = true),
            @CacheEvict(cacheNames = "goodsByMerchant", key = "#MerchantId"),
            @CacheEvict(cacheNames = "goodsCodes", allEntries = true),
            @CacheEvict(cacheNames = "goodsPrice", allEntries = true)
    })
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
        Map<String, Object> result = new HashMap<>();
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
    @PostMapping("/orderPayIfUp")
    public Map<String, Object> orderPayIfUp(@RequestParam("orderId") String orderId) {
        Map<String, Object> result = new HashMap<>();
        String sql = "UPDATE mall_management_sql.`order` SET PayIf = 1, SettlementDate = NOW() WHERE OrderId = ? AND PayIf = 0";
        try {
            int rows = GoodjdbcTemplate.update(sql, orderId);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "付款成功");
            } else {
                result.put("success", false);
                result.put("msg", "订单不存在或已付款");
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "数据库错误：" + e.getMessage());
        }
        return result;
    }

    //收货按钮更新信息
    @PostMapping("/orderReceiveIfUp")
    public Map<String, Object> orderReceiveIfUp(@RequestParam("orderId") String orderId) {
        Map<String, Object> result = new HashMap<>();
        String sql = "UPDATE mall_management_sql.`order` SET ReceiveIf = 1, SettlementDate = NOW() WHERE OrderId = ? AND PayIf = 1 AND ReceiveIf = 0";
        try {
            int rows = GoodjdbcTemplate.update(sql, orderId);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "收货成功");
            } else {
                result.put("success", false);
                result.put("msg", "订单未付款或已收货");
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "数据库错误：" + e.getMessage());
        }
        return result;
    }

    //发货按钮更新信息
    @PostMapping("/orderDeliveryIfUp")
    public Map<String, Object> orderDeliveryIfUp(@RequestParam("orderId") String orderId) {
        Map<String, Object> result = new HashMap<>();
        String sql = "UPDATE mall_management_sql.`order` SET DeliveryIf = 1, SettlementDate = NOW() WHERE OrderId = ? AND PayIf = 1 AND DeliveryIf = 0 AND ReceiveIf = 0";
        try {
            int rows = GoodjdbcTemplate.update(sql, orderId);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "发货成功");
            } else {
                result.put("success", false);
                result.put("msg", "订单不存在或未付款或已发货/已收货");
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "数据库错误：" + e.getMessage());
        }
        return result;
    }
}
