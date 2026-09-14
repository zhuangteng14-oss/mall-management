package com.minke.mallmanagement_controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/mysqlGoods")
@CrossOrigin
public class MallManagementGoodsController {

    @Autowired
    private JdbcTemplate GoodjdbcTemplate;

    //这是一个接口（用于提供和更新上架商品的可选类型）
    @RequestMapping(value = "/goodstype", method = {RequestMethod.GET, RequestMethod.POST})
    public Object goodstype(
            @RequestParam(required = false) String GoodType,
            @RequestParam(required = false) Integer GoodTypeNew
    ) {
        // GET 请求：查询所有类型
        if (GoodType == null) {
            String sql = "SELECT Id, GoodType, GoodTypeNew FROM mall_management_sql.goodstype";
            List<Map<String, Object>> list = GoodjdbcTemplate.queryForList(sql);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", list);
            return result;
        }

        // POST 请求：新增类型
        try {
            String checkSql = "SELECT COUNT(*) FROM mall_management_sql.goodstype WHERE GoodType = ?";
            Integer count = GoodjdbcTemplate.queryForObject(checkSql, Integer.class, GoodType);
            if (count > 0) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("msg", "类型已存在");
                return result;
            }

            String insertSql = "INSERT INTO mall_management_sql.goodstype (GoodType, GoodTypeNew) VALUES (?, ?)";
            GoodjdbcTemplate.update(insertSql, GoodType, GoodTypeNew);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("msg", "新增成功");
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("msg", "新增失败");
            return result;
        }
    }

    //这是一个接口用于上架商品
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
        String uploadPath = System.getProperty("user.dir") + "\\src\\main\\resources\\static\\img\\";

        // 图片为空判断
        if (file.isEmpty()) {
            result.put("success", false);
            result.put("msg", "请选择商品图片");
            return result;
        }

        // 获取图片文件名
        String originalFilename = file.getOriginalFilename();
        File saveFile = new File(uploadPath + originalFilename);

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
    @PostMapping("/goodsSelect")
    public Map<String, Object> goodsSelect(
            @RequestParam String MerchantId
    ) {
        Map<String, Object> result = new HashMap<>();
        try {
            String sql = "SELECT * FROM mall_management_sql.goods WHERE MerchantId = ?";
            List<Map<String, Object>> list = GoodjdbcTemplate.queryForList(sql, MerchantId);
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败");
        }
        return result;
    }

    //这是一个接口（用于返回商品对应的所有信息）
    @PostMapping("/goodsSelectHome")
    public Map<String, Object> goodsSelectHome() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 1. 先查所有商品
            String sql = "SELECT * FROM mall_management_sql.goods";
            List<Map<String, Object>> goodsList = GoodjdbcTemplate.queryForList(sql);

            // 2. 循环根据商品里的 MerchantId 查 login 表的 UserName
            for (Map<String, Object> goods : goodsList) {
                // 拿到商品里的商家ID
                Object merchantIdObj = goods.get("MerchantId");

                try {
                    // 重点：login 表用 id = ? 匹配！！！
                    String userSql = "SELECT UserName FROM mall_management_sql.login WHERE id = ?";

                    String userName = GoodjdbcTemplate.queryForObject(
                            userSql,
                            new Object[]{merchantIdObj},
                            String.class
                    );

                    // 把用户名放进商品数据
                    goods.put("UserName", userName);
                } catch (Exception e) {
                    // 查不到就为空
                    goods.put("UserName", "");
                }
            }

            result.put("success", true);
            result.put("list", goodsList);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败");
        }
        return result;
    }

    // 这是一个接口（用于下架商品）
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
    @PostMapping("/productCodePrice")
    public Map<String, Object> productCodePrice(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("ProductCode") String ProductCode
    ) {
        Map<String, Object> result = new HashMap<>();
        String sql = "SELECT Price, GoodImg FROM mall_management_sql.goods "
                + "WHERE MerchantId=? AND GoodName=? AND ProductCode=?";

        try {
            Map<String, Object> map = GoodjdbcTemplate.queryForMap(sql,
                    MerchantId, GoodName, ProductCode);
            result.put("success", true);
            result.put("price", map.get("Price"));
            result.put("goodImg", map.get("GoodImg")); // 🔥 新增：返回图片
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
        }
        return result;
    }

    //这是一个接口（用于更新商品数据）
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
        String uploadPath = System.getProperty("user.dir") + "\\src\\main\\resources\\static\\img\\";

        // 如果上传了新图片
        if (file != null && !file.isEmpty()) {
            String fileName = file.getOriginalFilename();
            File saveFile = new File(uploadPath + fileName);
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
    @PostMapping("/GoodsProductCodeSelect")
    public Map<String, Object> GoodsProductCodeSelect(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName
    ) {
        Map<String, Object> result = new HashMap<>();
        String sql = "SELECT DISTINCT ProductCode FROM mall_management_sql.goods "
                + "WHERE MerchantId=? AND GoodName=?";

        try {
            List<String> productCodeList = GoodjdbcTemplate.queryForList(sql,
                    String.class, MerchantId, GoodName);
            result.put("success", true);
            result.put("list", productCodeList);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
        }
        return result;
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
