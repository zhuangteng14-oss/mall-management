package com.minke.mallmanagement.admin.service;

import com.minke.mallmanagement.admin.dao.AdminOrderDao;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.order.service.OrderService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理端订单治理的服务层（C3 块 4）。三件事：**全量视图 / 异常监控 / 介入**。
 *
 * <h2>【介入 = 沿既有三条 CAS 边，不新状态位、不动守卫表（用户 2026-10-10 裁决 A3 甲）】</h2>
 * 「改状态」在本类里**不写一条 UPDATE** —— 它调的是 {@link OrderService#payIfUp} /
 * {@link OrderService#deliveryIfUp} / {@link OrderService#receiveIfUp}，即**商家端与买家端
 * 点按钮时走的那三条**。理由是把「介入」定义成**同一套合法边上的另一个 actor**，
 * 而不是**另一套写规则**：
 * <ul>
 *   <li>守卫（{@code OrderStateMachine} 的 {@code PayIf = 0} 等）仍然唯一把守 ——
 *       介入**推不动**一个已完成的单，与用户端的行为完全一致；</li>
 *   <li>{@code order_state_transition} 的因果链仍然成立 —— 介入产生的转移与用户自己产生的
 *       在时间线上**同形**，只有 {@code admin_audit} 里的 actor 不同；</li>
 *   <li>**不碰** {@code OrderStateMachine}（用户块 4 明令）。</li>
 * </ul>
 *
 * <h2>【「强制退款」= **只登记不实现**，且拒绝是**明说的**】</h2>
 * {@code order} 表**没有资金态**（{@code mall_management_sql.sql:141-155} 逐列可查：只有三个
 * 状态位 + Price/SettlementDate，没有任何「已退款 / 退款中」的列），{@code PayIf} 回退会
 * **破三位单调性**（状态机是单向的）⇒ 本块不做。故 {@code action=refund} **不是**被当成
 * 「未知动作」含糊地拒掉，而是回一条**说清楚为什么不做**的失败文案 ——
 * 「不认识」与「知道但不做」是两件事，混成一条会让下一个人以为只是漏了个 case。
 *
 * <h2>【⚠️ 不新建 {@code @Transactional}】</h2>
 * 介入链最终落到 {@code OrderService} 的锁方法上，那条链的禁令见那个类的类注释。
 */
@Service
public class AdminOrderService {

    /** {@code status} 的合法取值白名单（含「不筛」的 {@code all}）。见 {@link #list}。 */
    // 批四第二批 commit 4 追加 `refunding`（RefundStatus=1）/ `refund_platform`（RefundStatus=3）——
    // 售后是旁路状态域，与三位状态机并列筛，不是它的新状态。
    private static final Set<String> KNOWN_STATUS = Set.of("", "all", "unpaid", "paid", "shipped", "completed", "abnormal", "refunding", "refund_platform");

    /** 介入的三个**实现了的**动作。 */
    private static final Set<String> KNOWN_ACTIONS = Set.of("pay", "deliver", "receive");

    private final AdminOrderDao adminOrderDao;
    private final OrderService orderService;
    private final NotificationService notificationService;

    public AdminOrderService(AdminOrderDao adminOrderDao, OrderService orderService,
                             NotificationService notificationService) {
        this.adminOrderDao = adminOrderDao;
        this.orderService = orderService;
        this.notificationService = notificationService;
    }

    // ==================== 读 ====================

    /**
     * 全量视图（按用户 / 商家 / 状态 / 时间筛）。
     *
     * <p>⚠️ {@code status} **在本方法先过白名单**，再交给 DAO —— 未知值回
     * {@code {success:false, msg:"未知的状态筛选值…"}}，**不**让它落成「空列表」。
     * 理由：「筛错了一个词」与「这个状态确实没有数据」在画面上**一模一样**（都是空表），
     * 而对管理端来说前者是**用错**、后者是**事实** —— 把用错说成事实，比报错糟得多。
     *
     * <p>两个回报字段**刻意分流、互不推导**（2026-10-10 修：曾有「筛 status 后被误报已截断」的缺陷）：
     * <ul>
     *   <li>{@code truncated} = {@code rows.size() >= 生效上限}（上限见 {@link AdminOrderDao#clampLimit(int)}，
     *       默认 100 / 上限 {@link AdminOrderDao#MAX_LIMIT}）—— **只表「本次已达上限、后面可能还有」**，
     *       与筛选无关；如实回报、不静默截断。</li>
     *   <li>{@code total} = **全表** {@code COUNT(*)}（供「库内共 N 单」显示），**不随筛**——
     *       它**不是**截断判据（筛 `paid` 只回 1 条时，`total` 仍是全表的 9，但那 1 条**没被截断**）。</li>
     * </ul>
     */
    public Map<String, Object> list(String customerId, String merchantId, String status,
                                    String from, String to, Integer limit) {
        String s = status == null ? "" : status.trim().toLowerCase();
        if (!KNOWN_STATUS.contains(s)) {
            return fail("未知的状态筛选值：" + status + "（只认 unpaid / paid / shipped / completed / abnormal / all）");
        }
        Map<String, Object> result = new HashMap<>();
        try {
            int effLimit = AdminOrderDao.clampLimit(limit == null ? 0 : limit);
            List<Map<String, Object>> rows = adminOrderDao.selectOrders(
                    customerId, merchantId, s, from, to, limit == null ? 0 : limit);
            int total = adminOrderDao.countAll();
            result.put("success", true);
            result.put("list", rows);
            result.put("total", total);
            result.put("truncated", rows.size() >= effLimit);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

    /** 单条详情：订单行 + 状态流转时间线（{@code order_state_transition}）+ 物流轨迹（{@code order_shipping_track}，C3 块4 补丁）。*/
    public Map<String, Object> detail(String orderId) {
        if (isBlank(orderId)) {
            return fail("缺少参数：OrderId");
        }
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> row = adminOrderDao.selectByOrderId(orderId);
            if (row == null) {
                return fail("订单不存在：" + orderId);
            }
            result.put("success", true);
            result.put("row", row);
            // ⚠️ 两条时间线**并存**（不合并）：timeline = 状态流转（CAS 副产品）；shippingTrack = 模拟物流轨迹。
            result.put("timeline", adminOrderDao.selectTimeline(orderId));
            result.put("shippingTrack", adminOrderDao.selectShippingTrack(orderId));
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

    /** 异常监控（**只读识别**）：超时两项 + 非法态一项 + 未完成总数 + 总单数。 */
    public Map<String, Object> monitor() {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("success", true);
            result.put("row", adminOrderDao.selectMonitor());
            result.put("unpaidHours", AdminOrderDao.UNPAID_HOURS);
            result.put("undeliveredHours", AdminOrderDao.UNDELIVERED_HOURS);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== 写：介入 ====================

    /**
     * 介入：沿既有 CAS 边强制推进 + 成功后**通知双方**（用户 2026-10-10 裁决的第三条通知）。
     *
     * <p>⚠️ <b>通知在锁外发</b>（{@code orderService.payIfUp} 等已经把锁取了又放），
     * 且**只在 {@code success == true} 时发** —— 守卫没命中（例如推一个已完成的单）不算事件。
     * <p>⚠️ <b>发货 / 收货两条会各自再触发一次「对端通知」</b>（那是 {@code OrderService} 里
     * 用户自己操作时也走的那条）⇒ 强制发货时买家会收到两条：一条「商家已发货」（对端通知）、
     * 一条「管理员介入订单」（本方法）。**这是有意的**：两条来自不同事件、由不同 actor 触发，
     * 合并会丢掉「管理员介入了」这个事实。已登记（{@code tech-debt}）。
     */
    public Map<String, Object> intervene(String orderId, String action) {
        if (isBlank(orderId)) {
            return fail("缺少参数：OrderId");
        }
        String a = action == null ? "" : action.trim().toLowerCase();
        if ("refund".equals(a) || "dispute".equals(a)) {
            // 「知道但不做」要说得像「知道但不做」—— 见类注释。
            return fail("强制退款本块未实现：order 表没有资金态，PayIf 回退会破三位单调性（登记待办）");
        }
        if (!KNOWN_ACTIONS.contains(a)) {
            return fail("未知的介入动作：" + action + "（只认 pay / deliver / receive）");
        }
        Map<String, Object> result = switch (a) {
            case "pay" -> orderService.payIfUp(orderId);
            case "deliver" -> orderService.deliveryIfUp(orderId);
            default -> orderService.receiveIfUp(orderId);
        };
        if (Boolean.TRUE.equals(result.get("success"))) {
            notifyBoth(orderId, a);
        }
        return result;
    }

    // ==================== 内部 ====================

    /**
     * 通知**双方**（买家 + 卖家）。一个 {@code batchId} 记两次投递 —— 一次介入是**一个批次**，
     * 两个收件人是它的两个投递（{@code notification.BatchId} 就是为这个粒度设的）。
     *
     * <p>fail-soft 同 {@code OrderService.notifyCounterparty}：任何异常静默返回。
     * ⚠️ 代价一样 —— 「通知丢了」与「通知成功」在响应体上同形 ⇒ 验收要**回库读
     * {@code notification} 表**，不能看接口的 {@code success}。
     */
    private void notifyBoth(String orderId, String action) {
        try {
            String buyer = orderService.ownerIdOf(orderId, false);
            String seller = orderService.ownerIdOf(orderId, true);
            String batchId = UUID.randomUUID().toString();
            String title = "管理员介入订单";
            String content = "订单 " + orderId + " 已被管理员介入（" + actionLabel(action) + "）。";
            if (notBlank(buyer)) {
                notificationService.send(batchId, buyer, NotificationService.TO_TYPE_CUSTOMER,
                        NotificationEvents.ORDER_INTERVENED, title, content, "order", orderId);
            }
            if (notBlank(seller)) {
                notificationService.send(batchId, seller, NotificationService.TO_TYPE_MERCHANT,
                        NotificationEvents.ORDER_INTERVENED, title, content, "order", orderId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 动作 tag → 中文标签（**只用于通知正文**，不参与任何判据 —— 别拿它当白名单）。 */
    private static String actionLabel(String action) {
        return switch (action) {
            case "pay" -> "强制付款";
            case "deliver" -> "强制发货";
            default -> "强制完成";
        };
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

    private static boolean notBlank(String s) {
        return !isBlank(s);
    }
}
