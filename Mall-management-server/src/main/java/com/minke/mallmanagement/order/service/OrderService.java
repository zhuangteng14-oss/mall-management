package com.minke.mallmanagement.order.service;

import com.minke.mallmanagement.shared.lock.DistributedLockService.LockName;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.minke.mallmanagement.order.model.OrderAction;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.model.OrderStateMachine;
import com.minke.mallmanagement.order.model.OrderTransitionRecorder;
import com.minke.mallmanagement.shared.lock.DistributedLockService;

/**
 * 订单域的服务层（三层重构第一笔：订单域）。**业务规则 + 锁编排 + 响应组装**；
 * 路由与入参绑定留在 {@link OrderController}，数据访问全在 {@link OrderDao}。
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
 *
 * <h2>【B#1：价格校验落在本类、且落在锁<b>之前</b>（2026-10-04，{@code fix/price-validation}）】</h2>
 * 病源是 {@code defect-survey.md:107} 病 B：「**服务端零价格校验** —— {@code orderAdd} 的
 * {@code Price} 客户端直传直落库」。本笔只做 {@link #priceRejection} 一处**边界收窄**，四条落定：
 *
 * <ol>
 *   <li><b>为什么不「换真值源」</b>：按 {@code goods.Price} 重算属 **C 档**（{@code three-layers.md:94}
 *       / {@code :181} 都写着「那是换真值源，不是加校验」），且**用现行契约做不到** —— 下单
 *       payload 里**没有商品 ID、也没有数量**（{@code ProductInformation} 只记「买了哪几个」，
 *       见 {@code ProductPage.vue:315-317} 的拼接），服务端拿不到数量就**原则上**算不出总价。
 *       故本笔只走 (a) 档「加校验」；(b) 档要改接口契约，是**另一笔**。</li>
 *   <li><b>⚠️ 本笔不消除资损，只是把「任意值」收窄成「有界值」</b>：{@code defect-survey.md:107}
 *       自述「仓内无支付网关 ⇒『可伪造价格』成立、『资损』是**条件后果**」。本笔**挡不住
 *       「1 元的电视」** —— 那需要支付侧的真值源，已作为独立条目挂账。</li>
 *   <li><b>为什么在锁外</b>：{@link #addLocked} 的临界区因此**一字未动**（B2 / D#1 的登记处），
 *       且非法请求**不取锁** ⇒ 不耗 Redis 往返、**不污染锁指标**（A3 的 {@code A-S2a-1a} 按 tag
 *       读锁计数器）。⚠️ 校验**不是事务边界**，故它与 D#1 的 {@code @Transactional} 禁令
 *       **无关**；但**别把它搬进 {@code addLocked}** —— 那会让非法请求也过锁。</li>
 *   <li><b>为什么 200 而不是 400</b>：见 {@link #priceRejection} 的注释（{@code GlobalExceptionHandler}
 *       的**类级白名单**刻意排除 {@code OrderController}，挂约束注解会改它全部 8 个端点的错误体）。</li>
 * </ol>
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
     *
     * <p>⚠️ <b>B#1 起，{@code Price} 在这里先过一道 {@link #priceRejection} —— 且这道闸在
     * {@code runLocked} <b>之前</b></b>。理由见类注释的 B#1 段：非法请求不取锁、不触库。
     * 这道校验**不改变**合法请求的任何行为（合法 ⇒ 返回 {@code null} ⇒ 这一段完全透明）。
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
        // B#1：价格闸。放在锁外 —— 一是让非法请求不耗 Redis、不污染锁指标；二是让 addLocked
        // 的临界区保持逐字不动（B2 / D#1 的登记处就在那儿）。
        Map<String, Object> rejection = priceRejection(Price);
        if (rejection != null) {
            return rejection;
        }
        return lockService.runLocked(LockName.ORDER_ADD, OrderId, () -> addLocked(
                CustomerId, MerchantId, OrderId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf));
    }

    /**
     * 订单价格的**上界**。**两条依据，不是一个拍出来的数字**：
     *
     * <ol>
     *   <li><b>技术锚</b>：{@code order.Price} 的列类型是 {@code float}
     *       （{@code mall_management_sql.sql:146}）。float 的**整数精确上限是 2^24 = 16_777_216**
     *       —— 超过它，相邻可表示值的间隔就 &gt; 1，金额会**静默失真**（存进去的已经不是传进来的数）。
     *       ⇒ 上界必须**严格小于** 2^24。</li>
     *   <li><b>不误杀既有数据</b>：种子 6 条 {@code order} 的最大 {@code Price} 是 22328
     *       （{@code mall_management_sql.sql:159}）⇒ 10^7 约为它的 448 倍，留了充足余量。</li>
     * </ol>
     *
     * <p>⚠️ <b>真正的业务上界需要业务输入，本笔不自证</b> —— 它作为**待裁项**挂账
     * （{@code progress.md §5-F}）。本笔只保证「上界存在且不误杀既有数据」，不声称 10^7 就是对的。
     */
    private static final float MAX_ORDER_PRICE = 10_000_000f;

    /**
     * 价格守卫。**可接受 ⇒ 返回 {@code null}；否则返回既有的失败响应**（{@code {success:false, msg}}）。
     *
     * <p><b>判据写成正面形式（白名单），不是负面形式</b>：
     * {@code 接受 ⟺ Float.isFinite(price) && price >= 0 && price <= MAX_ORDER_PRICE}。
     * ⚠️ <b>不要把它改写成 {@code if (price < 0) reject}</b> —— 那是本笔最容易犯的错
     * （机制上与 C#2 那条「只记住半边」同类）：{@code NaN < 0} 是 {@code false}，
     * 负面写法会**把 NaN 放行**。而 {@code NaN} / {@code Infinity} / {@code -Infinity}
     * 都是 {@code Float.valueOf} 的**合法输入**（Spring 的 {@code @RequestParam float} 绑定
     * 正是走 {@code Float.valueOf}）⇒ **不能**假设「绑定成功 ⇒ 一定是有限数」。
     *
     * <p>⚠️ <b>{@code Float.isFinite} 是刻意的显式冗余，别误当成 NaN 的唯一防线</b>：
     * 在**链式正面判据**下它逻辑上可去（{@code NaN >= 0f} 已为 {@code false}）。
     * 2026-10-04 的突变对照实测（判据 = {@code OrderAddPriceGuardSmokeTest} 全类 8 条）：
     * <ul>
     *   <li>删掉 {@code Float.isFinite}、保留链式比较 ⇒ <b>0 条红</b>；</li>
     *   <li>把判据改写成负面形式 {@code !(price < 0f) && !(price > MAX_ORDER_PRICE)} ⇒
     *       <b>恰 1 条红</b>（{@code nan_isRejected}）—— NaN 被放行。</li>
     * </ul>
     * 即：<b>挡住 NaN 的是「判据取正面形式」这件事本身，不是 {@code isFinite}</b>。
     * 两者仍一起保留 —— {@code isFinite} 让「有限性」逐字可见（读者不必自己推 NaN 的比较语义），
     * 正面形式则保证无论从哪条路径进来，NaN/±Inf 都落不进「接受」分支。
     *
     * <p><b>下界为什么是 {@code >= 0} 而不是 {@code > 0}</b>：{@code three-layers.md:94} 与
     * {@code :181} 两处都写「只做 {@code Price >= 0} + 上界」；且 {@code goods.Price} 无下界约束
     * ⇒ 商家可以合法把商品设成 0 元，此时前端 {@code popupTotal} 会算出 0
     * （{@code ProductPage.vue:297-299}）⇒ {@code > 0} 会**误杀免费商品订单**。
     *
     * <p><b>精度不校验</b>：{@code popupTotal} 是 JS 浮点求和 ⇒ 真实订单本来就可能带
     * {@code 9.999999999999998} 这类尾数，加「≤2 位小数」会**误杀真实订单**；且存储是
     * {@code float}，「小数位」在二进制浮点下**没有良定义**。金额精度是**独立债**
     * （{@code order.Price} 是 float、{@code goods.Price} 是 double，同一个量两种精度），已挂账。
     *
     * <p><b>为什么返回 200 + 文案，而不是 4xx</b>：全仓的**业务失败**一律是 200 +
     * {@code {success:false,msg}}（「订单已存在，请勿重复提交」与三条 CAS 文案皆然；
     * {@code api-contract.md} 亦记 {@code orderAdd} 的 DB 拒绝是 200）。走 4xx 只有两条路：
     * 在 {@code OrderController} 的参数上挂约束注解 —— 那条路被 {@code GlobalExceptionHandler}
     * 的**类级白名单**挡住（它**刻意排除**了 {@code OrderController}，加进去会改该 Controller
     * 全部 8 个端点的错误体）；或另立异常类型并扩白名单 —— 两者**都属未被裁决的契约变更**。
     *
     * <p>文案 {@code "价格不合法"}：不含 A3 逐字数的两条红线（{@code 数据库错误} /
     * {@code "success":true}），且**不复用**笔 3 的 {@code "参数校验失败："} 前缀 ——
     * 那个前缀的语义是「入参**形状**」，本笔是**业务规则**，两者刻意分开。
     */
    private static Map<String, Object> priceRejection(float price) {
        if (Float.isFinite(price) && price >= 0f && price <= MAX_ORDER_PRICE) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", "价格不合法");
        return result;
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
