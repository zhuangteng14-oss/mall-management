package com.minke.mallmanagement.order.service;

import com.minke.mallmanagement.shared.lock.DistributedLockService.LockName;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.order.model.OrderAction;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.dao.ShippingTrackDao;
import com.minke.mallmanagement.order.model.OrderState;
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
 *
 * <h2>【C#6：出生状态闸 —— 关掉 TD-17 的伪造入口（2026-10-04，{@code fix/order-state-guard}）】</h2>
 * 病源是 {@code tech-debt.md} TD-17：{@code orderAdd} 的三个状态位由客户端直传，
 * 「调用方可直接插入一条 {@code (1,1,1)} 的『已付款+已发货+已收货』订单，全程不经过付款/发货/收货
 * ⇒ 状态机可以从入口被绕过」。本笔在**同一处**（锁外、价格闸之后）加一道白名单闸，五条落定：
 *
 * <ol>
 *   <li><b>规则 = 初始态白名单 {@code {(0,0,0), (1,0,0)}}，不是「只收合法态」</b>：
 *       {@code (1,1,1)} 是**合法态**（满足 A-S4a-8 的不变式），「只收合法态」拦不住它
 *       —— 那样落笔只能写「不变式前移」，不能写「TD-17 已修」。判据见
 *       {@link OrderState#isInitial}。</li>
 *   <li><b>⚠️ {@code (1,0,0)} 必须收</b>：生产前端「是否现在付款？」点「是」直传 {@code PayIf=1}
 *       （{@code ProductPage.vue:354}，{@code DeliveryIf}/{@code ReceiveIf} 恒 0）
 *       ⇒ 生产**实际只产生这两个组合**，「初始态」是两个不是一个。拒它会当场断掉「下单即付款」。
 *       <b>后代若以为「初始态只该有 (0,0,0)」而删掉这一半，测试会先红</b>（3 条用例钉死）。</li>
 *   <li><b>为什么也在锁外</b>：与 B#1 同位置 ⇒ {@link #addLocked} 的临界区**继续一字不动**
 *       （B2 / D#1 的登记处），且非法请求**不取锁**（不耗 Redis、不污染锁指标
 *       —— A3 的 {@code A-S2a-1a} 按 tag 读锁计数器）。本闸**不是事务边界**，
 *       与 D#1 的 {@code @Transactional} 禁令无关；但**别搬进 {@code addLocked}**。</li>
 *   <li><b>顺序：价格闸在前，本闸在后</b> —— 理由见 {@link #stateRejection} 的 javadoc
 *       （B#1 已把「价格非法 ⇒ 价格不合法」写成对外契约，对调即静默收窄契约）。</li>
 *   <li><b>失败仍走 200 + {@code {success:false,msg}}</b>：与 B#1、与全仓业务失败同通道。
 *       文案 {@code "订单状态不合法"}，不复用 {@code "参数校验失败："} 前缀（那是入参**形状**）。</li>
 * </ol>
 *
 * <p><b>⚠️ 本闸的验收证据在单测，不在 A3</b>：A3 的 {@code concurrency} job 是
 * {@code continue-on-error: true}（{@code ci.yml}），不拦 merge；本闸的门禁是
 * {@code OrderAddStateGuardSmokeTest}（{@code @WebMvcTest} 切片、**无 {@code A3_ENABLED} 门控**
 * ⇒ 在 {@code backend} 阻塞 job 里真跑）。A3 侧只证**零回归** + 「非法态确实造不出来了」。
 *
 * <p>⚠️ <b>本闸不改变任何合法请求的行为</b>（白名单 ⟺ 生产前端本来就只发的那两个组合）
 * ⇒ 对外契约的变更只落在**失败模式新增**上一类，与 B#1 同性质。
 */
@Service
public class OrderService {

    private final OrderDao orderDao;
    private final DistributedLockService lockService;
    private final OrderStateMachine orderStateMachine;
    private final OrderTransitionRecorder orderTransitionRecorder;

    /**
     * 跨域「商家是否处于降级冻结态」探针（C3 块 2 用户块新增）。<b>可为 {@code null}</b>。
     *
     * <p>⚠️ 与 {@code WebAuthConfig} / {@code UserService} 同一条切片论证：{@code @Service}
     * 不进 {@code @WebMvcTest}，而 {@code AbstractSmokeTest} 用 {@code @Import(OrderService.class)}
     * 拉了**真 bean** ⇒ 裸注入会让全部 smoke 类在上下文加载期就红。取不到 ⇒ 冻结闸不生效
     * （见 {@link #freezeRejection}），默认 profile 里它照常生效。
     */
    private final UserStateLookup userStateLookup;

    /**
     * 通知原语（C3 块 4 订单块新增）。发货 → 通知买家、收货 → 通知商家（用户 2026-10-10 裁决）。
     *
     * <p>⚠️ 与 {@link UserStateLookup} 不同，这里<b>直接注入</b>而不是 {@code ObjectProvider}：
     * {@code NotificationService} 与 {@code NotificationDao} 早在 C3 块 1 就进了
     * {@code AbstractSmokeTest} 的 {@code @Import}（是真 bean）⇒ 切片里取得到，不需要「可为 null」。
     * 若哪天它被移出 {@code @Import}，本节会在**上下文加载期**红 —— 那时再改成 provider 形态。
     */
    private final NotificationService notificationService;

    /** 物流轨迹出口（C3 块4 补丁 A2）。发货写首行、调度器补后续行、收货兜末行 —— 三处全 fail-soft。 */
    private final ShippingTrackDao shippingTrackDao;

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 支付流水号的日期段格式（{@code PAY} + {@code yyyyMMdd} + 6 位随机）。 */
    private static final DateTimeFormatter TRADE_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    public OrderService(OrderDao orderDao,
                        DistributedLockService lockService,
                        OrderStateMachine orderStateMachine,
                        OrderTransitionRecorder orderTransitionRecorder,
                        ObjectProvider<UserStateLookup> userStateLookupProvider,
                        NotificationService notificationService,
                        ShippingTrackDao shippingTrackDao) {
        this.orderDao = orderDao;
        this.lockService = lockService;
        this.orderStateMachine = orderStateMachine;
        this.orderTransitionRecorder = orderTransitionRecorder;
        this.userStateLookup = userStateLookupProvider.getIfAvailable();
        this.notificationService = notificationService;
        this.shippingTrackDao = shippingTrackDao;
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
     * <p>⚠️ <b>C3 块 4（A2 甲）在<b>末尾</b>加了 {@code Quantity}</b>：加在末尾而不是插在
     * {@code Price} 旁边，是为了让「既有 9 个形参的相对顺序」一字不动（本类的禁令是
     * 「形参名与顺序必须逐字保留」）。{@code Quantity} 与库存/数量有关但**不参与**任何闸：
     * 出生闸只看三个状态位，价格闸只看 {@code Price} ⇒ 它对既有请求的行为**零影响**。
     * <p>⚠️ Controller 侧该形参是 {@code required = false, defaultValue = "1"} ——
     * 老调用方（不传 {@code Quantity}）与 A3 压测脚本走的都是「1 件」。
     *
     * <p>⚠️ <b>B#1 起，{@code Price} 在这里先过一道 {@link #priceRejection} —— 且这道闸在
     * {@code runLocked} <b>之前</b></b>。理由见类注释的 B#1 段：非法请求不取锁、不触库。
     * 这道校验**不改变**合法请求的任何行为（合法 ⇒ 返回 {@code null} ⇒ 这一段完全透明）。
     *
     * <p>⚠️ <b>C#6 起，三个状态位再过一道出生闸（{@link OrderState#isInitial}）——
     * 同样在 {@code runLocked} <b>之前</b>，且<b>排在价格闸之后</b></b>。
     * 接受 ⟺ {@code (PayIf,DeliveryIf,ReceiveIf) ∈ {(0,0,0),(1,0,0)}}；不通过 ⇒
     * 记一次 {@code mall.order.state.birth.rejected{shape}} + 一行 WARN，返回
     * {@code {success:false,msg:"订单状态不合法"}}。理由见类注释的 C#6 段。
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
            int ReceiveIf,
            int Quantity
    ) {
        // B#1：价格闸。放在锁外 —— 一是让非法请求不耗 Redis、不污染锁指标；二是让 addLocked
        // 的临界区保持逐字不动（B2 / D#1 的登记处就在那儿）。
        Map<String, Object> rejection = priceRejection(Price);
        if (rejection != null) {
            return rejection;
        }
        // C#6：出生状态闸。**必须排在价格闸之后**（理由见 stateRejection 的 javadoc）。
        // 同样在锁外：非法请求不取锁、不触库，addLocked 的临界区依旧逐字不动。
        if (!OrderState.isInitial(PayIf, DeliveryIf, ReceiveIf)) {
            // 观测与拦截同点：这一调用内部会记 mall.order.state.birth.rejected{shape} 与一行 WARN。
            // ⚠️ 本方法在锁外调用 —— 是全类唯一不落在临界区里的观测调用点（见 OrderTransitionRecorder）。
            orderTransitionRecorder.rejectedAtBirth(PayIf, DeliveryIf, ReceiveIf, OrderId);
            return stateRejection();
        }
        // C3 块 2：商家降级冻结闸（阶段 ③ 之后的「冻结新单」）。**排在前两闸之后** ——
        // 它不参与 B#1/C#6 那两条已发布契约的措辞，故放在最后，避免扰动「价格 vs 状态」的顺序。
        // 同样在锁外：不可服务的请求不取锁、不触库，addLocked 依旧逐字不动。
        Map<String, Object> frozen = freezeRejection(MerchantId);
        if (frozen != null) {
            return frozen;
        }
        return lockService.runLocked(LockName.ORDER_ADD, OrderId, () -> addLocked(
                CustomerId, MerchantId, OrderId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf, Quantity));
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
     * 出生状态闸的失败响应（C#6，2026-10-04 {@code fix/order-state-guard}）。
     * 与 {@link #priceRejection} **同通道**：HTTP 200 + {@code {success:false, msg}}，且
     * **不取锁、不触库**。文案 {@code "订单状态不合法"}：不含 A3 的两条逐字红线
     * （{@code 数据库错误} / {@code "success":true}），也**不复用**笔 3 的
     * {@code "参数校验失败："} 前缀（那个前缀的语义是「入参**形状**」，本条是**业务规则**）。
     *
     * <h2>【判据为什么是白名单 {@code {(0,0,0),(1,0,0)}}，而不是「合法态」】</h2>
     * 判据在 {@link com.minke.mallmanagement.order.model.OrderState#isInitial}，
     * 那里写了完整理由。一句话版本：{@code (1,1,1)}（已付款+已发货+已收货）**是合法态**，
     * 只挡「非法态」的规则拦不住它 —— 而它正是 TD-17 正文举的例子。故必须从严。
     * ⚠️ 而 {@code (1,0,0)} 必须**收**：生产前端「下单即付款」直传 {@code PayIf=1}
     * （{@code ProductPage.vue:354}），拒它等于当场弄坏主流程。这两条都不是可选项。
     *
     * <h2>【⚠️ 为什么必须排在 {@link #priceRejection} <b>之后</b>】</h2>
     * 不是"哪个更便宜"，是**契约**：{@code api-contract.md} 的 B#1 那条已经把
     * 「价格不通过 ⇒ {@code msg="价格不合法"}」**写成对外承诺**。若状态闸在前，
     * 一条「价格非法 **且** 状态非法」的请求会改返回 {@code "订单状态不合法"}
     * ⇒ **静默收窄一条已发布的契约**。⇒ 顺序不许对调，由
     * {@code OrderAddStateGuardSmokeTest} 的一条用例钉死。
     *
     * <h2>【它不是 D#1 的反面】</h2>
     * 本闸是**纯分支 + 提前 return**：不是事务边界、不在临界区内。D#1 的
     * {@code @Transactional} 禁令只约束被打上 {@code runLocked} 的临界区，与本闸无关。
     * ⚠️ 但**别把它搬进 {@link #addLocked}** —— 那会让非法请求也过锁（与 B#1 同一条禁令）。
     */
    private static Map<String, Object> stateRejection() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", "订单状态不合法");
        return result;
    }

    /**
     * 商家降级冻结闸（C3 块 2 用户块）—— 可服务 ⇒ {@code null}；冻结 ⇒ 既有形状的失败响应。
     *
     * <h2>【「冻结新单、允许收尾旧单」的另一半在哪】</h2>
     * 本闸只拦 {@code orderAdd}（**新单**）。旧单的支付 / 发货 / 收货走的是
     * {@code OrderStateMachine} 那几条状态迁移，**不经过本方法** ⇒ 自然照常可用，
     * 且 {@code MerchantDowngradeService} 刻意不碰 {@code order} 表的任何行。
     * ⚠️ 所以「降级期间商家还能把在手订单做完」这条，靠的不是某个开关，而是
     * <b>本闸只长在下单这一个入口上</b> —— 别把它顺手复制到状态迁移上去。
     *
     * <h2>【为什么返回 200 + 文案，而不是 4xx】</h2>
     * 同 {@link #priceRejection}：全仓业务失败一律 200 + {@code {success:false,msg}}。
     * 文案 {@code "该商家已暂停接单"} 不含 A3 的两条逐字红线（{@code 数据库错误} /
     * {@code "success":true}）。
     *
     * <h2>【fail-open，且这是刻意的】</h2>
     * {@code userStateLookup == null}（切片 / 未装配）或探针内部读库失败 ⇒ 一律**放行**。
     * 理由见 {@code UserStateLookup} 类注释：冻结是**治理状态**，不是安全边界；
     * 「查不到冻结态」时把整条下单链路挡死，代价远大于「少拦一个已被冻结的商家」。
     *
     * <p>⚠️ <b>代价（登记）</b>：每次成功下单多一次按 {@code merchantid} 的
     * {@code SELECT COUNT(*)}，且它与其余两闸一样**在锁外**（故 20 并发下单 = 20 次探针查询）。
     * 这是裁决 A 档 5「乙 = 冻结新单」的直接成本，不是疏漏。
     *
     * <p>⚠️ <b>与其余两闸的一处不对称，别当漏了</b>：本闸**不记观测**
     * （{@code OrderTransitionRecorder} 只管订单状态迁移，冻结拒单不是状态迁移）。
     * 「被冻结的商家还在被下单」这件事目前只在 {@code msg} 里可见，没有指标 —— 已挂账。
     */
    private Map<String, Object> freezeRejection(String merchantId) {
        if (userStateLookup == null || merchantId == null || merchantId.trim().isEmpty()) {
            return null;
        }
        if (!userStateLookup.isMerchantFrozen(merchantId)) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", "该商家已暂停接单");
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
            String ProductInformation, String Images, int PayIf, int DeliveryIf, int ReceiveIf, int Quantity) {
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
                    OrderId, CustomerId, MerchantId, Price, Quantity, ProductInformation, Images,
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
        return payIfUp(orderId, null);      // 兼容入口（管理员介入 intervene 走这条）—— 不带支付方式
    }

    /**
     * 付款（带支付方式）。{@code paymentMethod} 可空 —— 空 ⇒ 明细列 {@code PaymentMethod = NULL}，
     * 但仍生成并落 {@code TradeNo} / {@code PaidAt}（管理员代付即走 1 参重载 → 此路）。
     *
     * <p>⚠️ 付款**刻意不通知**：用户 2026-10-10 裁决只点了三条（发货→买家 / 收货→商家 /
     *    管理员介入→双方）。付款通知是**顺手能加但没被要求**的第四条 ⇒ 不夹带，登记待办。
     *
     * <p>⚠️ <b>C3 块4 补丁新增 {@code paymentMethod}</b>：生成 {@code TradeNo}
     * （{@code PAY + yyyyMMdd + 6 位随机}），明细列写（{@code TradeNo / PaymentMethod / PaidAt}）
     * 在 {@code casTransition} 内、**同一把订单锁内**完成。成功响应**多一个 {@code tradeNo}** 字段
     * （收银台成功页显示「单号 PAYxxx」）。
     */
    public Map<String, Object> payIfUp(String orderId, String paymentMethod) {
        return lockService.runLocked(LockName.ORDER_PAY, orderId, () -> {
            String tradeNo = newTradeNo();
            Map<String, Object> result = casTransition(OrderAction.PAY, orderId,
                    "付款成功", "订单不存在或已付款",
                    Arrays.asList(tradeNo, paymentMethod));
            if (Boolean.TRUE.equals(result.get("success"))) {
                result.put("tradeNo", tradeNo);
            }
            return result;
        });
    }

    /**
     * 收货按钮更新信息。理由同 {@link #payIfUp}。
     *
     * <p>⚠️ <b>块 4 起，成功后<b>在锁外</b>通知卖家</b>（用户 2026-10-10 裁决的「用户收货 → 通知商家」）。
     * 通知的调用点在 {@code runLocked} <b>返回之后</b> —— 锁已释放，通知的 INSERT 不占订单锁。
     */
    public Map<String, Object> receiveIfUp(String orderId) {
        Map<String, Object> result = lockService.runLocked(LockName.ORDER_RECEIVE, orderId, () -> {
            Map<String, Object> r = casTransition(OrderAction.RECEIVE, orderId,
                    "收货成功", "订单未付款或已收货", List.of());
            if (Boolean.TRUE.equals(r.get("success"))) {
                // 收货 = **订单**侧「确认收货」；顺带兜末条物流轨迹（若调度器还没越过 4min 阈值）。
                // ⚠️ 两者语义不同（见 ShippingTrackDao 类注释）：本行**只补轨迹行**，不改状态位。
                ensureDeliveredTrack(orderId);
            }
            return r;
        });
        if (Boolean.TRUE.equals(result.get("success"))) {
            notifyCounterparty(orderId, true, "买家已确认收货",
                    "订单 " + orderId + " 的买家已确认收货。");
        }
        return result;
    }

    /**
     * 发货按钮更新信息。理由同 {@link #payIfUp}。
     *
     * <p>⚠️ <b>块 4 起，成功后<b>在锁外</b>通知买家</b>（用户 2026-10-10 裁决的「商家发货 → 通知买家」）。
     * 位置与 fail-soft 口径同 {@link #receiveIfUp}。
     */
    public Map<String, Object> deliveryIfUp(String orderId) {
        return deliveryIfUp(orderId, null, null);   // 兼容入口（管理员介入 intervene 走这条）—— 不带快递信息
    }

    /**
     * 发货（带快递公司 + 运单号）。两者可空（管理员代发走 1 参重载 → 此路）。
     *
     * <p>⚠️ <b>C3 块4 补丁新增</b>：明细列写 {@code ExpressCompany / TrackingNo / ShippedAt}
     * （{@code casTransition} 内、同锁内）。
     * ⚠️ <b>2026-10-10 改判（用户手验「揽收不该在发货时立即生成」）：本方法<em>不再</em>写轨迹</b>
     * —— 揽收节点 {@code picked_up} 移交 {@code ShippingTrackAdvanceJob}（⚠️ 2026-10-11 起它是**第 2** 个节点，
     * 首条是「运输中」{@code in_transit}）。理由与后果（a3 里新发的单一条轨迹都没有）见那个类注释；**别在这里加回来**。
     */
    public Map<String, Object> deliveryIfUp(String orderId, String expressCompany, String trackingNo) {
        Map<String, Object> result = lockService.runLocked(LockName.ORDER_DELIVERY, orderId, () -> casTransition(OrderAction.DELIVER, orderId,
                "发货成功", "订单不存在或未付款或已发货/已收货",
                Arrays.asList(expressCompany, trackingNo)));
        if (Boolean.TRUE.equals(result.get("success"))) {
            notifyCounterparty(orderId, false, "商家已发货",
                    "订单 " + orderId + " 已由商家发货，请注意查收。");
        }
        return result;
    }

    /**
     * 订单流转后的**对端通知**（C3 块 4 —— 通知管道的第三次实战）。
     *
     * <p>三条硬口径，缺一条都会把「通知」变成会咬人的东西：
     * <ol>
     *   <li><b>只在 {@code success == true} 时调用</b>：守卫没命中（重复流转 / 单不存在）
     *       并没有产生任何新事件，此时发通知就是造谣。调用点因此写在 {@code runLocked}
     *       返回**之后**，而不是塞进 {@code casTransition}。</li>
     *   <li><b>在锁外</b>：{@code runLocked} 已经返回（锁已释放）。通知是一次 INSERT，
     *       放进临界区只会让订单锁的持有时长多一次外部 IO。</li>
     *   <li><b>全程 fail-soft</b>：{@link NotificationService#send} 自己不抛；本方法再兜一层
     *       （回查归属失败 / 收件人取不到 / 任何异常 ⇒ 静默返回）。
     *       ⚠️ <b>代价必须说清</b>：fail-soft 让「通知丢了」与「通知成功」在**响应体上同形**。
     *       ⇒ 验收要**回库读 {@code notification} 表**（新增行可指认），不能看接口的 {@code success}
     *       —— 这是块 3 已经踩过一次的课。</li>
     * </ol>
     *
     * @param toMerchant {@code true} = 发给卖家（{@code MerchantId}）；{@code false} = 发给买家（{@code CustomerId}）
     */
    private void notifyCounterparty(String orderId, boolean toMerchant, String title, String content) {
        try {
            OrderDao.OrderOwner owner = orderDao.selectOwner(orderId);
            if (owner == null) {
                return;
            }
            String toId = toMerchant ? owner.merchantId() : owner.customerId();
            if (toId == null || toId.isBlank()) {
                return;
            }
            notificationService.send(UUID.randomUUID().toString(), toId,
                    toMerchant ? NotificationService.TO_TYPE_MERCHANT : NotificationService.TO_TYPE_CUSTOMER,
                    NotificationEvents.ORDER_NOTICE, title, content, "order", orderId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==================== 读：归属（C3 块 4 · A3 连带） ====================

    /**
     * 该单的**归属者业务号**；单不存在 ⇒ {@code null}。{@code asMerchant = true} 取卖家，
     * 否则取买家。给 {@link OrderController} 的三个 CAS 端点做归属校验用。
     *
     * <p>⚠️ <b>本方法自己也是 fail-open 的</b>：回查抛异常（库连不上）⇒ 返回 {@code null}
     * ⇒ 调用方放行到 CAS。理由与 {@code addLocked} 里那条查重的 fail-open 一致 ——
     * 归属校验是**加固**，不是流转的前置条件；让它把一次本来能成功的转账翻成 403
     * 是拿加固当门禁。⚠️ 这个取舍的**代价**是：库不可达时归属校验整体失效（登记待办）。
     */
    public String ownerIdOf(String orderId, boolean asMerchant) {
        try {
            OrderDao.OrderOwner owner = orderDao.selectOwner(orderId);
            if (owner == null) {
                return null;
            }
            return asMerchant ? owner.merchantId() : owner.customerId();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
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
                                              String okMsg, String noRowsMsg, List<Object> detailParams) {
        Map<String, Object> result = new HashMap<>();
        // SQL 由状态机生成，与 B2 之前硬编码的字面量逐字符相同 ——
        // 由 OrderStateMachineTest.updateSqlIsByteForByteTheLegacyLiteral 钉死。
        String sql = orderStateMachine.updateSql(action);
        try {
            int rows = orderDao.casUpdate(sql, orderId);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", okMsg);
                // ⚠️ C3 块4 补丁：状态位已置、**仍在同一把锁内** ⇒ 紧接着写明细列
                //    （TradeNo / PaidAt / ExpressCompany / …，见 OrderStateMachine.detailUpdateSql）。
                //    CAS 的 golden 串一字未动；非原子性由订单锁消除竞态。
                writeDetails(action, orderId, detailParams);
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

    // ==================== 明细列 / 物流轨迹（C3 块4 补丁） ====================

    /**
     * CAS 命中后写明细列（{@code TradeNo / PaymentMethod / PaidAt} 等），**在同一把订单锁内**。
     *
     * <p><b>fail-soft</b>：明细写失败**不**把一次成功转移报成失败 —— 状态位（{@code order} 表）才是真值，
     * 明细是过程增强。⚠️ 代价如实说：极少数情况下会出现「位已置、明细缺失」（{@code TradeNo} 为空），
     * 前端显示 {@code —}；登记边界（见 `_work/c3-block4-patch-landing-plan.md §七`）。
     *
     * @param detailParams {@link OrderStateMachine#detailColumns} 顺序的非 OrderId 绑定值
     *                     （PAY：{@code [tradeNo, paymentMethod]}；DELIVER：{@code [expressCompany, trackingNo]}；
     *                     RECEIVE：空）；{@code null} 视同空
     */
    private void writeDetails(OrderAction action, String orderId, List<Object> detailParams) {
        try {
            List<Object> bind = new ArrayList<>(detailParams == null ? List.of() : detailParams);
            bind.add(orderId);
            orderDao.applyDetails(orderStateMachine.detailUpdateSql(action), bind.toArray());
        } catch (Exception e) {
            log.warn("订单 {} 的 {} 明细列写失败（状态位已置、明细缺失）：{}", orderId, action, e.toString());
        }
    }

    /**
     * 写一条物流轨迹（fail-soft）。今日**只剩收货兜末行 {@code delivered}** 走它
     * —— 揽收节点 {@code picked_up} 已移交调度器（2026-10-10，见 {@link #deliveryIfUp(String, String, String)}）。
     *
     * <p>⚠️ 时刻口径：{@code TrackedAt} 由库时钟出（{@code NOW(3)}），本方法**不接收**时间参数
     * —— 改前那个 {@code long trackedAtMillis} 走 {@code new Timestamp} 会被驱动按
     * {@code serverTimezone=Asia/Shanghai} 折算，落库 +8 小时（详见 {@code ShippingTrackDao} 类注释）。
     */
    private void writeTrackRow(String orderId, String status, String description) {
        try {
            shippingTrackDao.write(orderId, status, description);
        } catch (Exception e) {
            log.warn("订单 {} 的物流轨迹 {} 写失败：{}", orderId, status, e.toString());
        }
    }

    /**
     * 收货时兜末条轨迹 {@code delivered}（若尚未存在）。
     *
     * <p>为什么收货要动轨迹：买家可能在调度器越过 4min 阈值**之前**就点收货 ⇒ 不兜的话，
     * 时间线会停在 {@code out_for_delivery} 而没有终点。⚠️ 本方法**只补轨迹行**，
     * **不**改任何状态位（用户红线：调度器/收货都不碰状态位；收货的 {@code ReceivedAt} 走明细列写）。
     */
    private void ensureDeliveredTrack(String orderId) {
        try {
            if (!shippingTrackDao.exists(orderId, ShippingTrackDao.DELIVERED)) {
                writeTrackRow(orderId, ShippingTrackDao.DELIVERED, "已签收");
            }
        } catch (Exception e) {
            log.warn("订单 {} 的兜末条轨迹写失败：{}", orderId, e.toString());
        }
    }

    /** 生成支付流水号：{@code PAY + yyyyMMdd + 6 位随机}（模拟网关）。 */
    private static String newTradeNo() {
        return "PAY" + LocalDate.now().format(TRADE_NO_DATE)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    // ==================== 读 ====================

    /**
     * 买家订单详情（C3 块4 补丁 A5）：订单行 + 物流轨迹。
     *
     * <p>归属校验在 {@code OrderController.orderDetail} 第一行（{@code isOwner(orderId, false)}，管理员豁免）
     * —— 本方法只做数据组装。单不存在 ⇒ {@code success:false}（与「读空」区分开）。
     */
    public Map<String, Object> orderDetail(String orderId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> row = orderDao.selectByOrderId(orderId);
            if (row == null) {
                result.put("success", false);
                result.put("msg", "订单不存在");
                return result;
            }
            result.put("success", true);
            result.put("row", row);
            result.put("shippingTrack", shippingTrackDao.selectByOrderId(orderId));
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败：" + e.getMessage());
        }
        return result;
    }

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
