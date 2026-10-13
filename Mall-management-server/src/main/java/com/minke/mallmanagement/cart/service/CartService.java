package com.minke.mallmanagement.cart.service;

import com.minke.mallmanagement.cart.dao.CartDao;
import com.minke.mallmanagement.order.service.OrderService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 绕过它自己写一行 = 把这五道闸全部作废。⇒ 本类只做**两件事**：算总价、**按商家分组**调用它。
 *
 * <h2>⚠️ 【结算**不是一笔原子操作**，这一点必须写在明面上】</h2>
 * 本车的**商家数 M**（★ 可视化段 0b 改：改前是**行数 N**）⇒ M 次 {@code orderAdd}，
 * 每次各自取锁、各自提交。中途第 M 个商家失败时，前 M−1 笔**已经变成真订单**（且已从车里清掉）
 * —— 这是**订单链禁 {@code @Transactional}**（{@code DistributedLockServiceTest.orderChainIsNotTransactional}
 * 结构断言兜底）的**直接代价**，不是本类的疏忽。故 {@link #checkout} 的失败响应里**必须**带上
 * 「已生成哪几笔」，让用户看得见已经发生的事 —— 把部分成功报成整体失败，是让用户重复下单。
 *
 * <h2>⚠️ 【0b 的代价：价格闸现在量的是**整组合计**】</h2>
 * 改前每行一张订单 ⇒ 闸只量单行（{@code 单价 × 数量}）；改后量 {@code Σ(单价 × 数量)}。
 * ⇒ 一辆车若**合计**超过 {@code MAX_ORDER_PRICE = 10^7}，这一组会被价格闸**整组挡下**
 * （改前不会）。这不是缺陷：{@code order.Price} 是 {@code float}，那道闸的存在正是为了不让金额
 * 越过 float 的整数精确区，而「立即购买」支**一直**是这个口径（它传的就是 {@code popupTotal}）。
 * ⚠️ 但它是一个**新出现的失败模式**，故明写在此：见「价格不合法」时要能想到「这一组合计超了」。
 */
@Service
public class CartService {

    /**
     * 单行数量上界。**两条依据**：
     * <ol>
     *   <li><b>技术锚</b>：结算时每组的 {@code Σ(单价 × 数量)} 会被放进 {@code order.Price}，而那是
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
     * 结算 ⇒ **按商家分组，一个商家一笔订单**；整组落库成功后才清这一组的购物车行。
     *
     * <p>{@code productCode} 为空 ⇒ 结算**整车**；非空 ⇒ 只结算那一行（那一行自然只属一个商家）。
     *
     * <h2>【⚠️ 可视化段 commit 0b：改前是「逐行下单」】</h2>
     * 改前每行各生成一个 {@code OrderId}、各调一次 {@code orderAdd} ⇒ 一辆 3 行的车生成
     * **3 张互相独立的订单**。那是**权宜实现、不是设计**：同一个前端的「立即购买」支**本来就是
     * 一单多品**（{@code ProductPage.vue} 把多个型号拼成一段 {@code ProductInformation}、
     * {@code Price} 传合计）⇒「一单多品」既已有之，且**全部消费者都已按它工作**（订单列表、
     * 管理端按 {@code OrderId} 的介入）。本笔把购物车**对齐到既有形状**，不发明新形状。
     *
     * <h2>【为什么分组键是「商家」而不是「整车一笔」】</h2>
     * 购物车**可以跨商家**（{@code cart} 的唯一键是 {@code (CustomerId, ProductCode)}，
     * {@code MerchantId} 只是普通列；前端 {@code ShoppingCart.vue} 已经按它画店铺分组），
     * 而 {@code order.MerchantId} 是**单值** ⇒ 「整车一笔」在跨店时不成立。
     * ⇒ 分组数 = 本车的**商家数 M** ∈ [1, 行数]。**单商家车（常态）就是「一单多行」**。
     *
     * <p><b>总价口径</b>：{@code order.Price} 在本仓历来是**总价**（前端 {@code ProductPage.vue}
     * 传的是 {@code popupTotal} = Σ 单价×数量）⇒ 本方法写
     * {@code Price = Σ(单价快照 × 数量)}、{@code Quantity = Σ数量}，与既有语义**一致**。
     * 单价取 {@code PriceSnapshot}（加入时的价 = 成交价），快照为 {@code null} 时才回 {@code PriceNow}。
     * ⚠️ 由此价格闸改为量**整组合计** —— 见类注释末条。
     *
     * <p>⚠️ <b>两段式</b>：先把整批读成 {@link CheckoutLine} 并校验，**校验期一笔订单都不建**；
     * 再逐组建单。改前是「边校验边建单」⇒ 第 3 行商品下架时前 2 行**已经成单**。
     * 本笔收成「要么全验过、要么一笔没建」—— 部分失败此后**只**来自 {@code orderAdd} 本身
     * （并发 / 库错），而那条回不去（订单链禁 {@code @Transactional}），仍按类注释如实回报。
     * ⚠️ 校验期那两条失败**仍走 {@link #partialFailure}**（此时它必然空表 ⇒ 文案与改前**逐字相同**）：
     * 「结算失败：…」这个串前端是直接 alert 出去的，换一种写法就是一次可见的对外变更。
     *
     * <h2>【C3 块 5 commit 5：可选用券 {@code couponId}】</h2>
     * 非空 ⇒ 只允许**单商家结算**（分组数 M = 1），并把券号透传给那一次 {@code orderAdd}；
     * M &gt; 1 且带了券 ⇒ **一条 SQL 都不发**，直接回「优惠券请分单使用」。
     * <p>⚠️ 为什么不在多商家时「随便挑一单用掉」：一张券只能抵一笔订单，而这一车会生成 M 笔，
     * 挑哪一笔都没有依据 —— 那是**发明**一条用户没说过的规则。让用户自己分单，规则才是确定的。
     * <p>⚠️ 前端（{@code ShoppingCart.vue}）按同一判据禁用券选择器（同 {@code MAX_QTY} 那条
     * 「两处都留，免得只在一边拦」），但**真判据在这里** —— 前端可绕过。
     */
    public Map<String, Object> checkout(String customerId, String productCode, String couponId) {
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

        // 第一段：整批读成 CheckoutLine 并校验。**任何一行不合格 ⇒ 一笔订单都不建。**
        // ⚠️ `created` 此刻**必然是空的**，仍走 `partialFailure` 而不是 `fail`：两条分支对空表
        //    产出的是同一个串（`"结算失败：" + reason`，见 partialFailure），**逐字保持改前文案**
        //    —— 前端 `ShoppingCart.vue` 直接 alert 这个 msg，改一个字都是可见的对外变更。
        List<Map<String, Object>> created = new ArrayList<>();
        List<CheckoutLine> lines = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String code = str(row.get("ProductCode"));
            String goodName = str(row.get("GoodName"));
            if (isBlank(goodName)) {
                // 商品已被删 / 下架（LEFT JOIN 没配上）—— **不动这一行**，如实报告并中止。
                return partialFailure(created, "商品已不存在，无法结算：" + code);
            }
            Double unit = toDouble(row.get("PriceSnapshot"));
            if (unit == null) {
                unit = toDouble(row.get("PriceNow"));
            }
            if (unit == null) {
                return partialFailure(created, "商品没有可用价格：" + code);
            }
            lines.add(new CheckoutLine(str(row.get("MerchantId")), code, goodName,
                    unit, toInt(row.get("Quantity"), 1), str(row.get("GoodImg"))));
        }

        // 第二段：分组建单。**一组 = 一笔订单 = 一次 orderAdd**（锁、五道闸、查重全在它里面）。
        Map<String, List<CheckoutLine>> groups = groupByMerchant(lines);
        // 用券闸：一张券只抵一笔订单，而本车会生成 M 笔 ⇒ 只有 M = 1 时「用这张券」才有确定含义。
        // ⚠️ 排在**建任何订单之前**（此时 `created` 必然空表 ⇒ 一条 SQL 都没发出去）。
        String coupon = couponId == null ? "" : couponId.trim();
        if (!coupon.isEmpty() && groups.size() > 1) {
            return fail("这一车含 " + groups.size() + " 个商家，优惠券只能用在单个商家的结算上（请分单结算）");
        }
        for (Map.Entry<String, List<CheckoutLine>> group : groups.entrySet()) {
            String merchantId = group.getKey();
            List<CheckoutLine> groupLines = group.getValue();
            OrderDraft draft = draftOf(groupLines);
            String orderId = newOrderId();
            // ⚠️ `Items` 交给 OrderService 编码（全仓唯一知道该格式的地方）。编码失败它返回 null
            //    ⇒ 服务端回落到 ProductInformation 口径 ⇒ 明细可能少几行，但**结算照常**。
            // ⚠️ `coupon` 只在 M = 1 时才可能非空（上面那道闸），它减的是 `order.Price`（落库总价），
            //    而 `Items` 里各行的单价仍是**原价** —— 明细记的是「卖了哪几件」，与实付是两回事。
            Map<String, Object> add = orderService.orderAdd(customerId, merchantId, orderId, draft.price(),
                    draft.productInformation(), draft.images(), 0, 0, 0, draft.quantity(),
                    orderService.encodeItems(draft.items()), coupon, "", "");   // 末两个形参 = `ActivityId`（C3 块 7 · commit 2c 新增的第 13 参）与 `Source`（边界补笔新增的第 14 参）：**购物车结算不是秒杀单、也不是演练写入** —— 活动串只有秒杀页那一路带得过来（`SeckillPage` → `/product` 的 query → `ProductPage` 的 `orderAdd`）；`Source` 只有攻击引擎传。本路径**各传空串**，后端 `required=false` + `isBlank` 判空 ⇒ 秒杀段整段跳过、`Source` 归一成空串，与加这两参之前**逐字同行为**。⚠️ **同行追加、不新增行**（本文件行号被文档按行号引）。
            if (!Boolean.TRUE.equals(add.get("success"))) {
                return partialFailure(created, String.valueOf(add.get("msg")));
            }
            // 订单真的落库了才清行 —— 顺序反了就是「钱没了、车空了」。
            for (CheckoutLine line : groupLines) {
                cartDao.remove(customerId, line.code());
            }
            Map<String, Object> one = new HashMap<>();
            one.put("OrderId", orderId);
            one.put("MerchantId", merchantId);
            // ⚠️ 改前那四个键**一个不少**（硬性约束：不删旧字段）。**单行组时它们与改前逐字同值**
            //    （`ProductCode` 就是一个码、`Quantity` 就是那个数量、`Price` 就是那一行的小计），
            //    多行组时才变成聚合值 —— 而那时「一个 OrderId 覆盖多件」正是本笔的目的。
            one.put("ProductCode", draft.codesJoined());
            one.put("Quantity", draft.quantity());
            one.put("Price", draft.price());
            one.put("Items", draft.items());   // 新增：这一笔订单覆盖了哪几件
            created.add(one);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "已生成 " + created.size() + " 笔订单");
        result.put("list", created);
        return result;
    }

    // ==================== 结算的内部（**纯函数**，可被普通 JUnit 直接钉住） ====================
    // ⚠️ 为什么单拎出来：`checkout` 的建单路径在 smoke 切片里**测不了** —— `JdbcClient` 打到
    //    被 mock 的 `JdbcTemplate` 上只会抛 NPE，断言它等于断言 Mockito 的默认行为
    //    （见 `CartSmokeTest` 类注释的同一条边界）。而分组与聚合是本笔**唯一的新逻辑**，
    //    抽成不碰库的纯函数后就能被 `CartCheckoutDraftTest` 直接钉住。

    /** 一次结算里的一行（**已解析、已校验**）。{@code unit} 是**成交单价**。 */
    record CheckoutLine(String merchantId, String code, String goodName, double unit, int qty, String goodImg) { }

    /** 一个商家的**一笔订单**（结算的产物：聚合值 + 明细 + 给响应体用的码串）。 */
    record OrderDraft(float price, int quantity, String productInformation, String images,
                      String codesJoined, List<OrderService.ItemSpec> items) { }

    /**
     * 按商家分组，**保持首次出现顺序**（{@code LinkedHashMap}）—— 建单顺序 = 用户看到的分组顺序。
     *
     * <p>⚠️ 商家号为 {@code null} 时归入空串这一组，**不会**被并进别的商家：{@code cart.MerchantId}
     * 是 {@code NOT NULL}，理论到不了这里；真到了，也不该把两件来路不明的商品并成一笔订单。
     */
    static Map<String, List<CheckoutLine>> groupByMerchant(List<CheckoutLine> lines) {
        Map<String, List<CheckoutLine>> groups = new LinkedHashMap<>();
        for (CheckoutLine line : lines) {
            groups.computeIfAbsent(line.merchantId() == null ? "" : line.merchantId(),
                    k -> new ArrayList<>()).add(line);
        }
        return groups;
    }

    /**
     * 把一个商家的若干行聚合成**一次 {@code orderAdd} 的入参**。
     *
     * <p>三项聚合口径与前端「立即购买」支**同源**（不是本笔发明的）：
     * <ul>
     *   <li>{@code price} = Σ(单价 × 数量) —— {@code order.Price} 历来是**总价**；</li>
     *   <li>{@code quantity} = Σ数量 —— {@code order.Quantity} 是**总件数**；</li>
     *   <li>{@code productInformation} = 各行 {@code 名:码} 以「；」相连 —— 与
     *       {@code ProductPage.vue} 的 {@code buildProductInformation} **同形同分隔符**。
     *       这一条**不只是显示口径**：它同时是 {@code OrderService} 的**回落口径**，
     *       两边必须是同一种形状，否则「Items 没发出去」时明细会静默丢失。</li>
     * </ul>
     * {@code images} 取**第一行**的图（与立即购买支「取第一件的图」同口径）。
     */
    static OrderDraft draftOf(List<CheckoutLine> groupLines) {
        double total = 0d;
        int count = 0;
        StringBuilder codes = new StringBuilder();
        StringBuilder info = new StringBuilder();
        List<OrderService.ItemSpec> items = new ArrayList<>(groupLines.size());
        String images = "";
        for (int i = 0; i < groupLines.size(); i++) {
            CheckoutLine line = groupLines.get(i);
            total += line.unit() * line.qty();
            count += line.qty();
            if (i > 0) {
                codes.append('；');
                info.append('；');
            }
            codes.append(line.code());
            info.append(line.goodName()).append(':').append(line.code());
            if (i == 0) {
                images = line.goodImg() == null ? "" : line.goodImg();
            }
            items.add(new OrderService.ItemSpec(line.code(), line.goodName(), line.unit(), line.qty()));
        }
        // ⚠️ 累加用 double、**只在最后窄化一次**成 float（`order.Price` 的列类型）：逐行
        //    `(float)` 再相加会让舍入误差随行数累积，而整组是一个金额，只该舍入一次。
        return new OrderDraft((float) total, count, info.toString(), images, codes.toString(), items);
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
