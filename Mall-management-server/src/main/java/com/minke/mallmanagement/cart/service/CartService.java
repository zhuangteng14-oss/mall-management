package com.minke.mallmanagement.cart.service;

import com.minke.mallmanagement.cart.dao.CartDao;
import com.minke.mallmanagement.order.service.OrderService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 购物车域的服务层（C3 块 4 · A1 甲）。**业务规则 + 响应组装**；
 * 路由与入参绑定在 {@code CartController}，数据访问全在 {@link CartDao}。
 *
 * <h2>【购物车块 = 容器，两个 tab（用户 2026-10-10 裁决）】</h2>
 * 「购物车」tab 的数据源是本类（{@code cart} 表）；「我的订单」tab 的数据源是订单域
 * （{@code OrderService.orderSelect}，即 {@code order} 表里 {@code CustomerId = 我} 的行）。
 * **两者刻意不做成同一张表**：购物车行是**可删的瞬时状态**，订单行是**单调不可逆的业务记录**
 * （三位状态机 + 出生闸），混在一张表里两边都会被污染 —— 这是 A1 选「新建 cart 表」的核心理由。
 *
 * <h2>【结算 = 建订单，**唯一**的写入口是 {@code OrderService.orderAdd}】</h2>
 * 结算**不自己拼 INSERT**：价格闸（{@code MAX_ORDER_PRICE}）/ 出生状态闸（{@code isInitial}）/
 * 商家冻结闸 / 订单锁（{@code order-add}）/ 临界区内查重 五道全都长在 {@code orderAdd} 里。
 * 绕过它自己写一行 = 把这五道闸全部作废。⇒ 本类只做**两件事**：算总价、按行调用它。
 *
 * <h2>⚠️ 【结算**不是一笔原子操作**，这一点必须写在明面上】</h2>
 * 一辆车里 3 行 ⇒ 3 次 {@code orderAdd}，每次各自取锁、各自提交。中途第 3 行失败时，
 * 前 2 行**已经变成真订单**（且已从车里清掉）—— 这是**订单链禁 {@code @Transactional}**
 * （{@code DistributedLockServiceTest.orderChainIsNotTransactional} 结构断言兜底）的**直接代价**，
 * 不是本类的疏忽。故 {@link #checkout} 的失败响应里**必须**带上「已生成哪几笔」，
 * 让用户看得见已经发生的事 —— 把部分成功报成整体失败，是让用户重复下单。
 */
@Service
public class CartService {

    /**
     * 单行数量上界。**两条依据**：
     * <ol>
     *   <li><b>技术锚</b>：结算时 {@code Price × Quantity} 会被放进 {@code order.Price}，而那是
     *       {@code float}（{@code mall_management_sql.sql:146}）⇒ 必须让乘积留在 float 的整数精确区
     *       （2^24）以内。**本数只是 SQL 层的兜底上界**，真正的乘积上界由 {@code orderAdd} 的
     *       价格闸（{@code MAX_ORDER_PRICE = 10^7}）把守 —— 两道闸各管一段，不互相替代。</li>
     *   <li><b>UX 锚</b>：与商品详情弹窗的数量钳位同值（{@code ProductPage.vue} 的
     *       {@code Math.min(100, ...)} 是 100，本处更宽）—— 取 999 是为了不当「第二个 100」，
     *       免得两处钳位不一致时前端先截断、后端看不出。</li>
     * </ol>
     * ⚠️ 本判据**不声称 999 是业务上界**（与 {@code MAX_ORDER_PRICE} 同性质：上界存在、不误杀）。
     */
    public static final int MAX_QUANTITY = 999;

    private final CartDao cartDao;
    private final OrderService orderService;

    public CartService(CartDao cartDao, OrderService orderService) {
        this.cartDao = cartDao;
        this.orderService = orderService;
    }

    // ==================== 写 ====================

    /**
     * 加入购物车。已有同商品 ⇒ **合并数量**（撞唯一键 ⇒ 改走 UPDATE）。
     *
     * <p>⚠️ 单价**由服务端现取**（{@link CartDao#selectGoodsSnapshot}），不信调用方传来的价 ——
     * 理由见那个方法的注释。
     * <p>⚠️ 合并时**刷新** {@code PriceSnapshot} 为当前价：用户此刻看到的就是现价，
     * 把旧快照留着会让「购物车里 ¥10、点进去 ¥12」的争议反向发生。
     */
    public Map<String, Object> add(String customerId, String merchantId, String productCode, Integer quantity) {
        if (isBlank(customerId) || isBlank(merchantId) || isBlank(productCode)) {
            return fail("缺少参数：CustomerId / MerchantId / ProductCode");
        }
        Integer qty = quantity == null ? 1 : quantity;
        Map<String, Object> bad = quantityRejection(qty);
        if (bad != null) {
            return bad;
        }
        try {
            CartDao.GoodsSnapshot goods = cartDao.selectGoodsSnapshot(merchantId, productCode);
            if (goods == null || isBlank(goods.goodName())) {
                return fail("商品不存在或已下架");
            }
            try {
                cartDao.insert(customerId, merchantId, productCode, qty, goods.price());
            } catch (DuplicateKeyException e) {
                // 已在车里 ⇒ 合并数量。**这不是异常路径，是正常的一条路**（唯一键就是为此设的）。
                cartDao.addQuantity(customerId, productCode, qty, merchantId, goods.price());
            }
        } catch (Exception e) {
            // ⚠️ 取商品快照也在 try 里 —— 它是一条**真查库**（`selectGoodsSnapshot`），
            //    库不可达时它会抛。本仓的口径是「业务失败 = 200 + success:false」，
            //    让它逃成 500 会把「库连不上」伪装成「服务器崩了」。
            e.printStackTrace();
            return fail("加入购物车失败：" + e.getMessage());
        }
        return ok("已加入购物车");
    }

    /** 改量（绝对赋值）。数量 < 1 ⇒ 拒绝（移除请走 {@link #remove}，两条语义不混）。 */
    public Map<String, Object> update(String customerId, String productCode, Integer quantity) {
        if (isBlank(customerId) || isBlank(productCode)) {
            return fail("缺少参数：CustomerId / ProductCode");
        }
        Map<String, Object> bad = quantityRejection(quantity);
        if (bad != null) {
            return bad;
        }
        try {
            int rows = cartDao.updateQuantity(customerId, productCode, quantity);
            return rows > 0 ? ok("已更新数量") : fail("购物车里没有这件商品");
        } catch (Exception e) {
            e.printStackTrace();
            return fail("更新数量失败：" + e.getMessage());
        }
    }

    /** 删一行（**物理 DELETE** —— 这是全仓对规则 v2 的**唯一**破例处，理由见 {@code CartDao} 类注释）。 */
    public Map<String, Object> remove(String customerId, String productCode) {
        if (isBlank(customerId) || isBlank(productCode)) {
            return fail("缺少参数：CustomerId / ProductCode");
        }
        try {
            int rows = cartDao.remove(customerId, productCode);
            return rows > 0 ? ok("已移除") : fail("购物车里没有这件商品");
        } catch (Exception e) {
            e.printStackTrace();
            return fail("移除失败：" + e.getMessage());
        }
    }

    /**
     * 结算 ⇒ 逐行建订单，成功一行就清一行。
     *
     * <p>{@code productCode} 为空 ⇒ 结算**整车**；非空 ⇒ 只结算那一行。
     *
     * <p><b>总价口径</b>：{@code order.Price} 在本仓历来是**总价**（前端 {@code ProductPage.vue}
     * 传的是 {@code popupTotal} = Σ 单价×数量），不是单价 ⇒ 本方法写
     * {@code Price = 单价快照 × 数量}、{@code Quantity = 数量}，与既有语义**一致**。
     * 单价取 {@code PriceSnapshot}（加入时的价 = 成交价），快照为 {@code null} 时才回 {@code PriceNow}。
     *
     * <p>⚠️ 部分失败的回报口径见类注释：失败的 {@code msg} 里必须带上**已生成哪几笔**。
     */
    public Map<String, Object> checkout(String customerId, String productCode) {
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        List<Map<String, Object>> rows;
        try {
            rows = cartDao.selectForCheckout(customerId, productCode);
        } catch (Exception e) {
            // 同 add：查库失败要回 200 + success:false，别逃成 500。
            e.printStackTrace();
            return fail("结算失败：" + e.getMessage());
        }
        if (rows.isEmpty()) {
            return fail("购物车是空的");
        }
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String merchantId = str(row.get("MerchantId"));
            String code = str(row.get("ProductCode"));
            String goodName = str(row.get("GoodName"));
            if (isBlank(goodName)) {
                // 商品已被删 / 下架（LEFT JOIN 没配上）—— **不动这一行**，如实报告并中止。
                return partialFailure(created, "商品已不存在，无法结算：" + code);
            }
            int qty = toInt(row.get("Quantity"), 1);
            Double unit = toDouble(row.get("PriceSnapshot"));
            if (unit == null) {
                unit = toDouble(row.get("PriceNow"));
            }
            if (unit == null) {
                return partialFailure(created, "商品没有可用价格：" + code);
            }
            float total = (float) (unit * qty);
            String orderId = newOrderId();
            Map<String, Object> add = orderService.orderAdd(customerId, merchantId, orderId, total,
                    goodName + ":" + code, str(row.get("GoodImg")) == null ? "" : str(row.get("GoodImg")),
                    0, 0, 0, qty, null);   // ⚠️ 可视化段 commit 0：第 11 个形参是**显式商品明细** `Items`，此处**刻意传 null** —— 单车单一商品的 `名:码` 恰好落在 `OrderService` 的回落口径里（推得码、数量 = qty、单价 = total ÷ qty，是恒等式），故**本行改前改后行为逐字相同**；commit 0b 把整车改成「一单多行」时才改传显式 JSON。
            if (!Boolean.TRUE.equals(add.get("success"))) {
                return partialFailure(created, String.valueOf(add.get("msg")));
            }
            // 订单真的落库了才清行 —— 顺序反了就是「钱没了、车空了」。
            cartDao.remove(customerId, code);
            Map<String, Object> one = new HashMap<>();
            one.put("OrderId", orderId);
            one.put("ProductCode", code);
            one.put("Quantity", qty);
            one.put("Price", total);
            created.add(one);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "已生成 " + created.size() + " 笔订单");
        result.put("list", created);
        return result;
    }

    // ==================== 读 ====================

    /** 整车（展示用）：带商品名 / 图 / 当前价，供「购物车」tab 渲染。 */
    public Map<String, Object> list(String customerId) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(customerId)) {
            result.put("success", false);
            result.put("msg", "缺少参数：CustomerId");
            return result;
        }
        try {
            result.put("success", true);
            result.put("list", cartDao.selectByCustomer(customerId));
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== 内部 ====================

    /** 数量闸：{@code 1 ≤ qty ≤ MAX_QUANTITY}。可接受 ⇒ {@code null}。 */
    private static Map<String, Object> quantityRejection(Integer quantity) {
        if (quantity == null || quantity < 1) {
            return fail("数量必须大于 0");
        }
        if (quantity > MAX_QUANTITY) {
            return fail("数量最多 " + MAX_QUANTITY + " 件");
        }
        return null;
    }

    /**
     * 部分成功的**如实回报**：{@code success:false} + 已生成的那几笔放在 {@code list} 里。
     *
     * <p>⚠️ 不许写成「结算失败」了事 —— 前几笔**真的已经是订单了**（会出现在「我的订单」里、
     * 能被付款）。把它们藏起来会让用户重复下单。这是订单链零事务的必然结果，不是 bug。
     */
    private static Map<String, Object> partialFailure(List<Map<String, Object>> created, String reason) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", created.isEmpty() ? ("结算失败：" + reason)
                : ("结算中断，已生成 " + created.size() + " 笔订单：" + reason));
        result.put("list", created);
        return result;
    }

    /**
     * 服务端生成 {@code OrderId}。格式对齐前端 {@code ProductPage.generateOrderId()} 的
     * 「字母段 + 数字段」（4 个大写字母 + 12 位数字），但熵更大 —— 服务端一次结算可能连造多笔，
     * 撞号的代价（{@code orderAdd} 的查重直接把它判成「订单已存在，请勿重复提交」）在这里
     * 比在前端更难被用户理解。
     */
    private static String newOrderId() {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(16);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 4; i++) {
            sb.append(letters.charAt(random.nextInt(letters.length())));
        }
        for (int i = 0; i < 12; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", msg);
        return result;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int toInt(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static Double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
