package com.minke.mallmanagement.order.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.order.service.OrderService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单域的 HTTP 适配器 —— 从 {@code GoodsController} 拆出的 8 个订单端点。
 *
 * <p>2026-10-02 拆分：原类一个类干两件事（商品 9 + 订单 8），是当时最重的架构债。
 * 拆分的<b>唯一目的</b>是让「类所在域」与「URL 前缀」一致 ——
 * 本笔同时把前缀从 {@code /mysqlGoods} 规范化为 {@code /order}
 * （另 8 条商品 URL 与 5 条用户 URL 同笔规范化，见 docs/agent/api-contract.md）。
 *
 * <p><b>方法体一字未动</b>：8 个方法各自仍是一行 {@code return orderService.x(...)}，
 * SQL / 分支 / 文案早在订单域三层重构（PR #25）时就下沉到 {@link OrderService} 了。
 * 本类只保留路由与入参绑定 —— ⚠️ 形参名与顺序必须逐字保留（{@code @RequestParam} 未写显式名，
 * 全靠形参名解析，{@code -parameters} 已开）。
 *
 * <h2>【响应体：8 个端点全部换成 {@link Result}，{@code orderAdd} 只换返回类型】</h2>
 * 只换**返回类型**：{@code Map<String,Object>} → {@code Result<Map<String,Object>>}，
 * 方法体由 {@code return orderService.x(…)} 改为 {@code return Result.of(orderService.x(…))}。
 * Service 返回值仍是 {@code Map}，URL 与参数一字未动；因 {@code Result.of} 逐条 put 保字节等价，
 * 线上响应体逐字节不变。
 * <p>⚠️ <b>{@code orderAdd} 的锁编排一行未碰</b>（B2 锁 tag 与 D#1 禁令）：取锁、临界区内查重、
 * SELECT/INSERT 三段都在 {@link OrderService#orderAdd}，本类只是把它的返回值多包一层 ——
 * 「不得碰」针对的是编排，不是返回类型。本类**仍然**不持有 {@code DistributedLockService}。
 *
 * <h2>【多账户隔离笔（2026-10-06）：5 个端点加**入参身份校验**】</h2>
 * {@code orderAdd}（判 {@code CustomerId}）/ {@code orderSelect} / {@code orderDeliveryIfSelect} /
 * {@code orderDeliverySelect} / {@code historicalOrdersSelect}（各判自己那个
 * {@code customerId} / {@code merchantId}）⇒ 方法体第一行过
 * {@link IdentityGuard#requireSelfOrAdmin}，拒则 403（返回类型随之改成
 * {@code ResponseEntity<Object>}）。
 * <p>⚠️ <b>{@code orderAdd} 只判 {@code CustomerId}，**绝不判 {@code MerchantId}</b>：
 * 下单时那个 {@code MerchantId} 是**对端方**（卖给我东西的商家），客户当然要填别人的 ——
 * 判它等于谁都下不了单。
 * <p>⚠️ <b>3 个 {@code orderPayIfUp} / {@code orderReceiveIfUp} / {@code orderDeliveryIfUp}
 * 本笔不加</b>：它们的入参只有 {@code orderId}，判「这张单是不是你的」得**回查资源归属**
 * （拿 orderId 查订单行、比对 customer/merchant），是另一种判据形态，不在本笔的 13 条清单里
 * —— 缺口登记在 {@code progress.md §5-AF}（**不修**，避免本笔夹带新主题）。
 *
 * <h2>【C3 块 4（2026-10-10）：上面那条缺口<b>在此关闭</b>】</h2>
 * 用户裁决「补」⇒ 三条 CAS 端点各自加归属校验，落地为私有件 {@link #isOwner}
 * （回查归属走 {@code OrderService.ownerIdOf}，SQL 在 {@code OrderDao.selectOwner}）——
 * 即上面那句「另一种判据形态」正是本笔做的事：**入参判不了就回查资源**。
 * <p>⚠️ <b>返回类型随之从 {@code Result<Map>} 变回 {@code ResponseEntity<Object>}</b>
 * （与另外 5 个端点一致）：403 是一个「不是 200 的响应」，包不进 {@code Result}。
 * <p>⚠️ <b>三条 CAS 的文案、锁 tag、SQL 一行未动</b> —— 只有「进方法体之后、取锁之前」多了一道闸。
 * 这与 A3 的 {@code A-S3-2} 不冲突的前提写死在 {@link #isOwner} 的注释里（单不存在必须放行）。
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    // ===== 订单域（三层重构第一笔，2026-10-02）=====
    // 订单域的全部 SQL / 分支 / 文案已下沉到 OrderService（数据访问再下沉到 OrderDao）。
    // ⚠️ 本类**不再**持有 DistributedLockService / OrderStateMachine / OrderTransitionRecorder：
    // 三者的编排（取锁、生成 CAS 语句、观测投递）都已随方法体进 Service。
    // 切片侧配套见 AbstractSmokeTest 的 @Import —— OrderService / OrderDao 用**真 bean**，
    // 因为 OrderPayIfUpSmokeTest 要钉的是「rows → 文案」这段映射，mock 掉就只剩一句空命题。
    @Autowired
    private OrderService orderService;

    /**
     * 审计（C3 块 4 新增）。只服务于**发货 / 收货**两条流转 —— 它们是「谁把这张单推进了一步」
     * 的可追责点，且 A3 的时间线（{@code order_state_transition}）只记**状态怎么变**、
     * 不记**谁点的按钮**（actor 在 {@code admin_audit} 里）。
     * <p>⚠️ 付款**不挂审计**：它是买家自己的动作、不改「谁欠谁」，且 A3 的 {@code A-S3-2}
     * 拿 20 并发支付压这条路径 —— 每次成功都多写一行 {@code admin_audit}，是给压测添噪声。
     * <p>⚠️ {@code AdminAuditDao} / {@code AuditService} 早在本类的 smoke 切片里（块 1 起）
     * ⇒ 直接 {@code @Autowired} 取得到，不需要 {@code ObjectProvider} 形态。
     */
    @Autowired
    private AuditService auditService;

    //创建订单时要录入数据库的信息
    // 方法体（锁编排 + 临界区内查重 + INSERT + 出生观测 + 全部文案）搬到 OrderService.orderAdd()。
    // 本方法只保留路由与入参绑定 —— ⚠️ 形参名与顺序**必须**逐字保留：@RequestParam 未写显式名，
    // 全靠形参名解析（-parameters 已开）。
    // ⚠️ 取锁、查重、SELECT/INSERT 三段现在都在 Service 侧：本类**不再**引 DistributedLockService，
    //    也**不再**直接引 JdbcTemplate 做订单 SQL —— 这是「数据出口收成一处」在本域的落地。
    // ⚠️ 笔 2（统一响应体）只把返回类型换成 Result：锁编排、CAS 语句、观测投递**一行未碰**
    //    （A3 的 A-S2a-1a 按 tag 读锁指标，B2 锁 tag = order-add 不变）。
    @PostMapping("/orderAdd")
    public ResponseEntity<Object> orderAdd(
            @RequestParam("CustomerId") String CustomerId,
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("OrderId") String OrderId,
            @RequestParam("Price") float Price,
            @RequestParam("ProductInformation") String ProductInformation,
            @RequestParam("Images") String Images,
            @RequestParam("PayIf") int PayIf,
            @RequestParam("DeliveryIf") int DeliveryIf,
            @RequestParam("ReceiveIf") int ReceiveIf,
            @RequestParam(value = "Quantity", required = false, defaultValue = "1") int Quantity,
            // 可视化段 commit 0：可选的**显式商品明细**（JSON 数组串，见 OrderService.resolveItems）。
            // ⚠️ 也加在末尾（同 Quantity 的先例）：既有的 10 个形参一名不动、一位不挪。
            // ⚠️ `defaultValue = ""` 与前端 `build()` 把 undefined 转成空串（order.js:42）**同效** ——
            //    两条路都落成「存在但为空」，服务端一律按 `isBlank` 判空 ⇒ 走回落口径。
            @RequestParam(value = "Items", required = false, defaultValue = "") String Items,
            // C3 块 5 commit 5：可选的**结算用券**（券的业务串 `coupon.CouponId`）。
            // ⚠️ 加在末尾（同 `Quantity` / `Items` 的先例）：既有 11 个形参一名不动、一位不挪。
            // ⚠️ `required = false` 是硬要求：老前端 / 购物车不选券时**不发**它 ⇒ 走「没用券」，
            //    加它之前的行为**逐字节不变**。⚠️ 券的**归属**不在这里判：入参里没有「这是谁的券」，
            //    判据是 `CouponService.redeem` 拿**会话翻出来的登录名**去 `coupon_grant` 查那一行 ——
            //    别人拿我的券号来，查不到就是「不在你的券包里」，不是先查到再比一次。
            @RequestParam(value = "CouponId", required = false, defaultValue = "") String CouponId,
            HttpServletRequest request
    ) {
        // ⚠️ 第一行：订单只能下在**自己**名下（管理员豁免）—— 必须在取锁之前（否则白取一把锁）。
        // ⚠️ 判的是 CustomerId，**不是** MerchantId（那是对端方，见类注释）—— 别「顺手」加。
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        Map<String, Object> result = orderService.orderAdd(
                CustomerId, MerchantId, OrderId, Price, ProductInformation, Images,
                PayIf, DeliveryIf, ReceiveIf, Quantity, Items, CouponId);
        // C3 块 5 commit 5：**券真的核销掉了**才留痕（`Discount` 只在核销成功且大于 0 时才被
        // `orderAdd` 放进回体 ⇒ 用它当判据，不重算一遍）。
        // ⚠️ 这是**下单人自己的动作**落在 `admin_audit` 里（同收货那条：actor = 当前调用者）——
        //    本仓的留痕表就这一张，用户侧动作也没有第二处可写。
        // ⚠️ TargetId 是**订单业务串**（不是券号）：`coupon_grant.OrderId` 反查得到券，
        //    而券号是一串看不出上下文的业务码 —— 与 `AuditActions` 那条挂钩点注释同款说法。
        Object discount = result.get("Discount");
        if (Boolean.TRUE.equals(result.get("success")) && discount != null && CouponId != null && !CouponId.isBlank()) {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("Status", "unused");
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("Status", "used");
            after.put("CouponId", CouponId);
            after.put("Discount", discount);
            after.put("OrderId", OrderId);
            auditService.record(AuthSession.identify(request), AuditActions.COUPON_REDEEM,
                    "coupon", OrderId, before, after);
        }
        return ResponseEntity.ok(Result.of(result));
    }

    //购物车搜索订单
    // 方法体（SQL / try-catch / 返回结构）逐字搬到 OrderService.orderSelect()，本方法只保留路由与委托
    // —— 与 goodsSelect / goodsSelectHome / GoodsProductCodeSelect 同款（「方法体搬到…本方法只保留路由与委托」）。
    @PostMapping("/orderSelect")
    public ResponseEntity<Object> orderSelect(
            @RequestParam("customerId") String customerId,
            HttpServletRequest request
    ) {
        // ⚠️ 第一行：购物车只能查自己的订单（管理员豁免）。
        if (!IdentityGuard.requireSelfOrAdmin(request, customerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(orderService.orderSelect(customerId)));
    }

    //订单中心搜索订单
    @PostMapping("/orderDeliveryIfSelect")
    public ResponseEntity<Object> orderDeliveryIfSelect(
            @RequestParam("merchantId") String merchantId,
            HttpServletRequest request
    ) {
        // ⚠️ 第一行：商家端订单中心只能查自己的（管理员豁免）。
        if (!IdentityGuard.requireSelfOrAdmin(request, merchantId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(orderService.orderDeliveryIfSelect(merchantId)));
    }

    //商家端已经完成订单信息
    // ⚠️ status 的默认值 "active" 留在本方法：它是**入参绑定**（@RequestParam 的 defaultValue），
    //    不是业务规则 —— 搬进 Service 就得在 Service 里再写一遍默认值，两处会漂移。
    @PostMapping("/orderDeliverySelect")
    public ResponseEntity<Object> orderDeliverySelect(
            @RequestParam("merchantId") String merchantId,
            @RequestParam(value = "status", defaultValue = "active") String status,
            HttpServletRequest request
    ) {
        // ⚠️ 第一行：商家端「已完成订单」只能看自己的（管理员豁免）。status 不是身份，不判。
        if (!IdentityGuard.requireSelfOrAdmin(request, merchantId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(orderService.orderDeliverySelect(merchantId, status)));
    }


    //历史订单信息页面信息
    // 方法体（含「用户ID不能为空」那条先于查库的分支）逐字搬到 OrderService.historicalOrdersSelect()。
    @PostMapping("/historicalOrdersSelect")
    public ResponseEntity<Object> historicalOrdersSelect(
            @RequestParam("customerId") String customerId,
            HttpServletRequest request
    ) {
        // ⚠️ 第一行：历史订单只能看自己的（管理员豁免）。
        if (!IdentityGuard.requireSelfOrAdmin(request, customerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(orderService.historicalOrdersSelect(customerId)));
    }



    //付款按钮更新信息
    // 方法体（取锁 + 状态机 SQL + 分支 + 文案 + 观测）逐字搬到 OrderService.payIfUp()。
    // ⚠️ 锁 tag = order-pay 随之进 Service：A3 的 A-S2a-1a 是按 tag 读锁指标的，不许换。
    // ⚠️ 两条文案「付款成功」/「订单不存在或已付款」也被 A-S3-2 逐字断言，且已随方法下沉
    //    （不在本类留参数）—— 理由是文案与它对应的分支不可分，拆开会让同一处改动要改两处。
    @PostMapping("/orderPayIfUp")
    public ResponseEntity<Object> orderPayIfUp(@RequestParam("orderId") String orderId,
                                               @RequestParam(value = "paymentMethod", required = false) String paymentMethod,
                                               HttpServletRequest request) {
        // ⚠️ 第一行：只能付**自己**的单（管理员豁免）—— 必须在取锁之前。判据是**回查归属**
        //（入参只有 orderId），见类注释的「块 4 A3 连带」段。
        if (!isOwner(request, orderId, false)) {
            return IdentityGuard.forbidden();
        }
        // ⚠️ C3 块4 补丁：paymentMethod 可空（老调用方 / 管理员代付）；Service 生成 TradeNo 并回传。
        return ResponseEntity.ok(Result.of(orderService.payIfUp(orderId, paymentMethod)));
    }

    //收货按钮更新信息
    // ⚠️ B2：锁 tag = order-receive。净正确性收益为 0（理由同付款）。
    @PostMapping("/orderReceiveIfUp")
    public ResponseEntity<Object> orderReceiveIfUp(@RequestParam("orderId") String orderId,
                                                   HttpServletRequest request) {
        // ⚠️ 第一行：只能收**自己**买的单（管理员豁免）—— 归属者是买家。
        if (!isOwner(request, orderId, false)) {
            return IdentityGuard.forbidden();
        }
        Map<String, Object> result = orderService.receiveIfUp(orderId);
        // 块 4：收货是**商家侧能看见的事件** ⇒ 成功即留痕（actor = 当前调用者，可能是管理员代收）。
        if (Boolean.TRUE.equals(result.get("success"))) {
            auditService.record(AuthSession.identify(request), AuditActions.ORDER_RECEIVE,
                    "order", orderId, null, orderActionPayload("receive"));
        }
        return ResponseEntity.ok(Result.of(result));
    }

    //发货按钮更新信息
    // ⚠️ B2：锁 tag = order-delivery。净正确性收益为 0（理由同付款）。
    @PostMapping("/orderDeliveryIfUp")
    public ResponseEntity<Object> orderDeliveryIfUp(@RequestParam("orderId") String orderId,
                                                    @RequestParam(value = "expressCompany", required = false) String expressCompany,
                                                    @RequestParam(value = "trackingNo", required = false) String trackingNo,
                                                    HttpServletRequest request) {
        // ⚠️ 第一行：只能发**自己卖**的单（管理员豁免）—— 这一条的归属者是**卖家**，
        //    与付款/收货两条判的不是同一个人 ⇒ 那个 `true` 是这里唯一容易写错的地方。
        if (!isOwner(request, orderId, true)) {
            return IdentityGuard.forbidden();
        }
        // ⚠️ C3 块4 补丁：快递公司 / 运单号可空（管理员代发走 1 参重载）。⚠️ **本端点不再写轨迹行**（2026-10-10 用户判）：四节点（`in_transit` 运输中 / `picked_up` 驿站已揽收 / `out_for_delivery` 驿站派送中 / `delivered` 已签收）已搬进 `ShippingTrackAdvanceJob`、按「ShippedAt + 阈值」由调度补（⚠️ 2026-10-11 起首条是 `in_transit`，`picked_up` 退为**第 2** 个）；这里只改状态位。
        Map<String, Object> result = orderService.deliveryIfUp(orderId, expressCompany, trackingNo);
        if (Boolean.TRUE.equals(result.get("success"))) {
            auditService.record(AuthSession.identify(request), AuditActions.ORDER_DELIVER,
                    "order", orderId, null, orderActionPayload("deliver"));
        }
        return ResponseEntity.ok(Result.of(result));
    }

    // ==================== 块4 补丁新增：买家订单详情 ====================

    /**
     * 买家订单详情（C3 块4 补丁 A5）：订单行 + 物流轨迹（{@code order_shipping_track}）。
     *
     * <p>⚠️ 第一行归属校验（管理员豁免）：买家的单只有买家自己能看 —— 与三条 CAS 端点同一形态
     * （{@link #isOwner} + {@code asMerchant = false}）。单不存在 ⇒ {@code isOwner} 放行、
     * 由 Service 出 {@code success:false}{@code "订单不存在"}（**不是** 403，同 {@link #isOwner} 的三条回退口径）。
     */
    @PostMapping("/orderDetail")
    public ResponseEntity<Object> orderDetail(@RequestParam("orderId") String orderId,
                                              HttpServletRequest request) {
        if (!isOwner(request, orderId, false)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(orderService.orderDetail(orderId)));
    }

    // ==================== 块 4 新增的内部件 ====================

    /**
     * 「这张单是不是你的」——三个 CAS 端点的**归属校验**（C3 块 4 · A3 连带）。
     *
     * <p>这三条端点的入参只有 {@code orderId}，判不了入参身份 ⇒ 必须**按单回查归属**
     * （拿 orderId 查订单行、比对 customer / merchant）。缺口原先登记在 {@code progress.md §5-AF}
     * （「本笔不修，避免夹带新主题」），块 4 用户裁「补」⇒ 在此关闭。
     *
     * <p>⚠️ <b>三条回退路径全部<b>放行</b>，一条都不许改成 403</b>：
     * <ol>
     *   <li><b>单不存在</b>（{@code ownerIdOf} 回 {@code null}）⇒ 放行到 CAS，
     *       由 {@code rows == 0} 出既有的那条文案。⚠️ 若在此改判 403，
     *       A3 的 {@code A-S3-2}（20 并发支付里恰好 19 个返回逐字「订单不存在或已付款」）
     *       会当场变红 —— 那条断言读的就是这条路径。</li>
     *   <li><b>auth 关（{@code AuthSession.identify() == null}）</b> ⇒
     *       {@link IdentityGuard#requireSelfOrAdmin} 自己就 fail-open（A3 与 smoke 切片都是这个态），
     *       本方法无需另判。</li>
     *   <li><b>回查抛异常</b> ⇒ {@code ownerIdOf} 返回 {@code null} ⇒ 走第 1 条放行。
     *       代价：库不可达时归属校验整体失效（登记待办）——加固不许把正常流转翻成 403。</li>
     * </ol>
     *
     * @param asMerchant {@code true} = 这笔的归属者应是**卖家**（发货）；{@code false} = **买家**（付款 / 收货）
     */
    private boolean isOwner(HttpServletRequest request, String orderId, boolean asMerchant) {
        String owner = orderService.ownerIdOf(orderId, asMerchant);
        return owner == null || IdentityGuard.requireSelfOrAdmin(request, owner);
    }

    /** 订单流转的审计 {@code AfterJson}：动作 + 结果。{@code BeforeJson} 恒 NULL（流转前的三位不另查一次库）。 */
    private static Map<String, Object> orderActionPayload(String action) {
        Map<String, Object> after = new HashMap<>();
        after.put("action", action);
        after.put("success", true);
        return after;
    }
}
