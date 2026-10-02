package com.minke.mallmanagement_service;

import com.minke.mallmanagement_service.DistributedLockService.LockName;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单域的服务层（三层重构第一笔：订单域）。**业务规则 + 锁编排 + 响应组装**；
 * 路由与入参绑定留在 {@code MallManagementGoodsController}，数据访问全在 {@link OrderDao}。
 *
 * <h2>【迁自哪里 —— 逐字搬运，不是重写】</h2>
 * 7 个方法体整段搬自 {@code MallManagementGoodsController}（{@code orderAddLocked} /
 * {@code casTransition} / 四个读），含 SQL、try-catch、分支与**每一条文案**。
 * 搬运的验收判据是「响应逐字段与改前一致」，其中三条 CAS 的文案被 A3 的 {@code A-S3-2}
 * **逐字断言**（「每单恰好 19 个 {@code success:false} 且 msg == 「订单不存在或已付款」」）——
 * 所以这三对文案不是可以顺手润色的字符串。
 *
 * <h2>⚠️ 【本类（及其调用链）不得加 {@code @Transactional}】—— 全项目唯一的禁令处</h2>
 * 源头是 {@code three-layers.md §D.1}（2026-09-30 登记）+ {@code DistributedLockService}
 * 类 javadoc 的「调用点的两条硬要求」。禁令的**理由有两条，本笔把它们的名字点明**，
 * 因为它们在分层后**一条被消解、一条被削弱**：
 *
 * <ol>
 *   <li><b>锁外事务</b>（原理由①）—— 加了事务，提交就发生在方法返回**之后**，而锁在
 *       {@code runLocked} 的 {@code finally} 里、即方法返回**之前**已释放 ⇒ 后进者拿到锁后的
 *       {@code COUNT(*)} 在 RR 下读的是自己事务开始时的快照、**看不到前一个未提交的行** ⇒
 *       仍插 2 行。<b>本条与分层无关，永远成立。</b></li>
 *   <li><b>跨 bean 调用</b>（原理由②）—— 三层前，临界区是传给 {@code DistributedLockService}
 *       的 lambda、内部调**本类私有方法**，没有 bean 边界可挂事务（"想加也加不上"）。
 *       ⚠️ <b>分层后这句话不再成立</b>：现在的调用链是 lambda → {@link #addLocked} →
 *       {@link OrderDao#insertOrder}，**第一次真的出现了跨 bean 调用** ⇒ 想加就加得上了。
 *       所以本笔不靠"结构上加不上"，只靠**登记 + 一条结构性断言**
 *       （{@code DistributedLockServiceTest.orderChainIsNotTransactional} 反射扫
 *       {@code OrderService} / {@code OrderDao} 上有没有 {@code @Transactional}）。</li>
 * </ol>
 *
 * <h2>【为什么本笔的做法是"临界区里零事务边界"，而不是"把事务挪到锁内"】</h2>
 * 临界区里只有两条语句：一次读（{@code countByOrderId}）+ 一次**单条写**
 * （{@code insertOrder}）。单条 INSERT 在 autocommit 下**本来就在锁释放之前提交** ——
 * 这正是查重成立的前提。给它加一层事务**不增加任何正确性**，只把"提交点"从「语句结束」
 * 挪到「方法返回」，也就是把一个当前**不可能出错**的性质，换成一个**依赖将来没人往上挪**
 * 的性质。故本笔选择保持零事务边界，并明确登记。
 *
 * <h2>【谁保护它】</h2>
 * <b>行为判据</b>：{@code A-S2a-1c}（20 并发同 OrderId，响应里含「数据库错误」的 == 0）。
 * 一旦有人给本链加上事务，查重失效 ⇒ 撞 {@code uk_order_orderId} ⇒ INSERT 的 catch 把它吞成
 * 200 + {@code msg="数据库错误：Duplicate entry..."} ⇒ 该断言变红。
 * ⚠️ 但该断言在当前 CI 里是 {@code continue-on-error: true}（{@code ci.yml:117}），
 * <b>不是拦 merge 的红</b> —— 所以结构断言是本笔补上的另一半。
 *
 * <h2>【与 C#2 的关系：本禁令不可外推】</h2>
 * 本条**只管被打锁的方法体**。它的反面是 C#2 —— {@code goodsAdd}（**没有**打锁）的事务边界
 * **应当**加 {@code @Transactional}。只记住半边就会踩另一边。
 *
 * <h2>【观测为什么仍在锁内】</h2>
 * {@link OrderTransitionRecorder#created} / {@code applied} / {@code rejected} / {@code errored}
 * 的调用点**与三层前同位置**（{@code addLocked} 内、{@code casTransition} 的 UPDATE 之后），
 * 没有搬出锁 —— 读回三位必须在锁内，否则并发的另一次转移会挤进来、把时间线的 {@code from}
 * 记成别人造成的状态（见该类注释）。A3 的 C-1 / C-2 是这条的门禁。
 */
@Service
public class OrderService {

    private final OrderDao orderDao;
    private final DistributedLockService lockService;
    private final OrderStateMachine orderStateMachine;
    private final OrderTransitionRecorder orderTransitionRecorder;

    public OrderService(OrderDao orderDao,
                        DistributedLockService lockService,
                        OrderStateMachine orderStateMachine,
                        OrderTransitionRecorder orderTransitionRecorder) {
        this.orderDao = orderDao;
        this.lockService = lockService;
        this.orderStateMachine = orderStateMachine;
        this.orderTransitionRecorder = orderTransitionRecorder;
    }

    // ==================== 下单 ====================

    /**
     * 创建订单。整段临界区在 Redisson 锁下执行（锁 key = OrderId，指标 tag = {@code order-add}）。
     *
     * <p>⚠️ 锁**单独**拦不住重复行 —— 20 个请求排队进场、每个照样裸 INSERT，只是把并发变成
     * 串行、仍插 20 行；修复是「锁 + 临界区内先查后插」**两条，缺一不可**。拿不到锁
     * （Redis 不可达 / 争用超时）⇒ fail-open 直行。完整推演见
     * {@code DistributedLockService.runLocked} 的 javadoc。
     *
     * <p>形参名与顺序**必须**与三层前逐字一致：{@code @RequestParam} 依赖它们解析。
     */
    public Map<String, Object> orderAdd(
            String CustomerId,
            String MerchantId,
            String OrderId,
            float Price,
            String ProductInformation,
            String Images,
            int PayIf,
            int DeliveryIf,
            int ReceiveIf
    ) {
        return lockService.runLocked(LockName.ORDER_ADD, OrderId, () -> addLocked(
                CustomerId, MerchantId, OrderId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf));
    }

    /**
     * {@code orderAdd} 的临界区。**原方法体逐字未动**（B2 的边界：只加一层锁，不动既有分支），
     * 仅在开头**插入**了查重；本笔只把两条 SQL 换成 {@link OrderDao} 调用。
     *
     * <p>⚠️ 本方法**不得**加 {@code @Transactional} —— 理由见类注释的两条（锁外事务 / 跨 bean 调用）。
     * 一句话：事务的提交发生在方法返回**之后**，而锁在 {@code runLocked} 的 {@code finally} 里、
     * 即方法返回**之前**已释放 —— 后进者拿到锁后的 {@code COUNT(*)} 在 RR 下读的是自己事务
     * 开始时的快照、**看不到前一个未提交的行** ⇒ 仍插 2 行。不加事务时 autocommit 让 INSERT
     * 在释放锁**之前**就提交，这正是查重成立的前提。
     */
    private Map<String, Object> addLocked(
            String CustomerId, String MerchantId, String OrderId, float Price,
            String ProductInformation, String Images, int PayIf, int DeliveryIf, int ReceiveIf) {
        Map<String, Object> result = new HashMap<>();
        // ⚠️ 临界区内先查后插 —— 这一段是 B2 对「同一 OrderId 落库 2 行」的真修复（A-S2a-1）：
        //    · 只有锁：并发变串行，但每个请求照旧裸 INSERT ⇒ 仍插 20 行；
        //    · 只有查重：查与插之间的 TOCTOU 窗口仍在 ⇒ 两个请求可以同时查到 0 行。
        //    OrderId 由前端每次下单生成（ProductPage.vue 的 generateOrderId()）、**不复用** ⇒
        //    查重不会挡掉正常下单，只会挡「同一 OrderId 的重复提交」。
        try {
            int sameOrderId = orderDao.countByOrderId(OrderId);
            if (sameOrderId > 0) {
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

        try {
            int rows = orderDao.insertOrder(new OrderDao.OrderRow(
                    OrderId, CustomerId, MerchantId, Price, ProductInformation, Images,
                    PayIf, DeliveryIf, ReceiveIf));
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

    // ==================== 状态转移（三个 CAS 端点） ====================
    // ⚠️ 三条的锁 tag 各不相同（order-pay / order-receive / order-delivery），
    //    A3 的 A-S2a-1a 是按 tag 读锁指标的，换 tag 会被读到。

    /**
     * 付款按钮更新信息。条件 UPDATE（{@code AND PayIf = 0}）本来就是正确的并发控制 ⇒
     * **锁在这里净正确性收益为 0**；买到的是「同单状态流转串行化 + 统一的取锁观测口径」，
     * 代价是每请求多一次 Redis 往返。拿不到锁 ⇒ fail-open 直行 ⇒ 争用下仍恰好一个成功、
     * 其余 19 个返回**原**文案 —— {@code A-S3-2} 的守门对象正是这条：锁不许新发任何拒绝文案。
     */
    public Map<String, Object> payIfUp(String orderId) {
        return lockService.runLocked(LockName.ORDER_PAY, orderId,
                () -> casTransition(OrderAction.PAY, orderId, "付款成功", "订单不存在或已付款"));
    }

    /** 收货按钮更新信息。理由同 {@link #payIfUp}。 */
    public Map<String, Object> receiveIfUp(String orderId) {
        return lockService.runLocked(LockName.ORDER_RECEIVE, orderId,
                () -> casTransition(OrderAction.RECEIVE, orderId, "收货成功", "订单未付款或已收货"));
    }

    /** 发货按钮更新信息。理由同 {@link #payIfUp}。 */
    public Map<String, Object> deliveryIfUp(String orderId) {
        return lockService.runLocked(LockName.ORDER_DELIVERY, orderId,
                () -> casTransition(OrderAction.DELIVER, orderId, "发货成功", "订单不存在或未付款或已发货/已收货"));
    }

    /**
     * 三个 CAS 端点的**共同临界区**。B2 之前它们是三段逐字重复的方法体；B3 把 SQL 收敛进
     * {@link OrderStateMachine} 后，三段之间只剩「动作」与「两条文案」不同，故合成一处 ——
     * 不是为省行数，而是为了让「同一处改动要同时改三遍、漏一遍就静默漂移」这件事不再可能。
     *
     * <p>⚠️ **分支与文案一个字节都没变**：{@code rows > 0} → 成功文案；{@code rows == 0} →
     * 原样的失败文案（三条各不相同，故由参数传入）；异常 → {@code "数据库错误：" + e.getMessage()}。
     * 这是 A3 的 A-S3-2（20 并发支付里恰好 19 个返回逐字「订单不存在或已付款」）保持绿的前提。
     *
     * <p>⚠️ 本方法同样**不得**加 {@code @Transactional}：它与 {@code addLocked} 同处一个临界区，
     * 理由完全一样（见类注释两条）。CAS 本身的守卫是 SQL 谓词，不靠事务。
     *
     * <p>观测全部委托给 {@link OrderTransitionRecorder}，**在 UPDATE 返回的这一处立刻调用**
     * （即仍在临界区内）。观测路径**全程 fail-soft**：读回失败、投递失败，都不影响响应体。
     * 状态位已经在 {@code order} 表里改完了，这才是真值；时间线与指标是过程记录。
     */
    private Map<String, Object> casTransition(OrderAction action, String orderId,
                                              String okMsg, String noRowsMsg) {
        Map<String, Object> result = new HashMap<>();
        // SQL 由状态机生成，与 B2 之前硬编码的字面量逐字符相同 ——
        // 由 OrderStateMachineTest.updateSqlIsByteForByteTheLegacyLiteral 钉死。
        String sql = orderStateMachine.updateSql(action);
        try {
            int rows = orderDao.casUpdate(sql, orderId);
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

    // ==================== 读 ====================

    /** 购物车搜索订单。方法体逐字搬自原 Controller（含 SQL / try-catch / 返回结构）。 */
    public Map<String, Object> orderSelect(String customerId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> list = orderDao.selectActiveByCustomer(customerId);
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

    /** 订单中心搜索订单。方法体逐字搬自原 Controller。 */
    public Map<String, Object> orderDeliveryIfSelect(String merchantId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> list = orderDao.selectActiveByMerchant(merchantId);
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 商家端已经完成订单信息。方法体逐字搬自原 Controller —— 包括 {@code "completed".equals(status)}
     * 这个**先判 null 安全**的写法（原样保留，不改成 {@code switch}）。
     * {@code status} 的默认值 {@code "active"} 仍由 Controller 的 {@code @RequestParam} 提供。
     */
    public Map<String, Object> orderDeliverySelect(String merchantId, String status) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> list = orderDao.selectByMerchant(merchantId, "completed".equals(status));
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 历史订单信息页面信息。方法体逐字搬自原 Controller，**含那条先于查库的入参分支** ——
     * 它现在落在 Service：理由是它与其余四个读保持同一形状（Controller 只剩路由与委托），
     * 且它的文案 {@code "用户ID不能为空"} 是响应契约的一部分，跟着响应组装走更不易漂移。
     */
    public Map<String, Object> historicalOrdersSelect(String customerId) {
        Map<String, Object> result = new HashMap<>();
        if (customerId == null || customerId.trim().isEmpty()) {
            result.put("success", false);
            result.put("msg", "用户ID不能为空");
            return result;
        }
        try {
            List<Map<String, Object>> list = orderDao.selectHistoryByCustomer(customerId);
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }
}
