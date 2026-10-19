package com.minke.mallmanagement.order.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.minke.mallmanagement.goods.dao.GoodsDao;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import com.minke.mallmanagement.marketing.coupon.service.CouponService;
import com.minke.mallmanagement.marketing.seckill.dao.SeckillDao;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics.Deduct;
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
 *   <li><b>为什么不「换真值源」</b>：按 {@code goods.Price} 重算属 **C 档**（{@code three-layers.md#pack-three-layers-b}
 *       / {@code :181} 都写着「那是换真值源，不是加校验」），且**用现行契约做不到** —— 下单
 *       payload 里**没有商品 ID、也没有数量**（{@code ProductInformation} 只记「买了哪几个」，
 *       见 {@code ProductPage.vue:339-341} 的拼接），服务端拿不到数量就**原则上**算不出总价。
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

    /**
     * 解析显式明细入参 {@code Items}（可视化段 commit 0）。**直接注入**：与 {@link #notificationService}
     * 同一条切片论证 —— 线上用的就是 Spring Boot 自动配置的那个 {@code ObjectMapper}
     * （{@code @WebMvcTest} 切片含 {@code JacksonAutoConfiguration}，全仓既有注入先例是
     * {@code AuditService.java}）⇒ 「取不到 ⇒ 上下文加载期红」的风险不成立。
     */
    private final ObjectMapper objectMapper;

    /**
     * 券域的核销出口（C3 块 5 · commit 5 新增）。结算带 {@code CouponId} 时用它「校验 + 核销 + 算减免」。
     *
     * <p>⚠️ <b>直接注入</b>（不是 {@code ObjectProvider}）：{@code CouponDao} / {@code CouponService}
     * 早在块 5 commit 1 就进了 {@code AbstractSmokeTest} 的 {@code @Import}（是真 bean）⇒
     * 切片里取得到，同 {@link #notificationService} 那条论证。若哪天它被移出 {@code @Import}，
     * 本节会在**上下文加载期**红 —— 那时再改成 provider 形态。
     * <p>⚠️ 依赖方向是「订单 → 券」，**不是**反过来：券域对订单域一无所知（它只认
     * {@code couponId + merchantId + 金额 + orderId} 四个值）⇒ 没有环。
     */
    private final CouponService couponService;

    /**
     * 商品域的库存出口（C3 块 7 · 库存治理 TD-36 新增）。下单即扣走 {@link GoodsDao#deductStock}、
     * 超时取消回补走 `OrderTimeoutCancelJob` 侧的 {@link GoodsDao#releaseStock}。
     *
     * <p>⚠️ <b>直接注入</b>（不是 {@code ObjectProvider}）：{@code GoodsDao} 早在三层第二笔就进了
     * {@code AbstractSmokeTest} 的 {@code @Import}（是真 bean）⇒ 切片里取得到，同
     * {@link #notificationService} / {@link #couponService} 那条论证。
     * <p>⚠️ 依赖方向是「订单 → 商品」，**不是**反过来：商品域对订单域一无所知
     * （{@code GoodsDao} 只认 {@code skuCode + n} 两个值）⇒ 没有环。
     */
    private final GoodsDao goodsDao;

    /**
     * 秒杀域的活动出口（C3 块 7 · commit 2c 新增，2026-10-14）。可选入参 {@code ActivityId} 非空时：
     * 先 {@link SeckillDao#selectActivityByActivityId} 把业务串换成自增 {@code Id}，
     * 再 {@link SeckillDao#deductSoldStock} 抢名额（CAS）；建单失败时用
     * {@link SeckillDao#releaseSoldStock} 把名额退回去。
     *
     * <p>⚠️ <b>直接注入</b>（不是 {@code ObjectProvider}）：{@code SeckillDao} 早在块 5 commit 1
     * 就进了 {@code AbstractSmokeTest} 的 {@code @Import}（是真 bean）⇒ 切片里取得到，同
     * {@link #notificationService} / {@link #couponService} / {@link #goodsDao} 那条论证。
     * <p>⚠️ 依赖方向是「订单 → 秒杀」，**不是**反过来：秒杀域对订单域一无所知
     * （{@code SeckillDao} 只认 {@code activityId + id + n} 三个值）⇒ 没有环。
     */
    private final SeckillDao seckillDao;

    /**
     * 库存面的观测出口（C3 块 7 · 可观测域新增，{@code observe/metrics/StockMetrics.java}）。
     * 下单即扣的每条明细各记一次 —— 扣没扣到、扣了几件。
     *
     * <p>⚠️ <b>只记事实、不定结论</b>：{@link StockMetrics#deduct} 的入参是<b>枚举</b>（不是 throwable），
     * DAO 的异常在上面的 {@link #deductStocks} 里先被接住 ⇒ 埋点不会改变 {@code orderAdd} 的返回值，
     * 也不会把异常从方法里带出去。这与既有三环（缓存 / 锁 / 订单）的口径一致。
     * <p>⚠️ 依赖方向是「订单 → 观测」，而观测域<b>对订单域一无所知</b>（{@code StockMetrics}
     * 只认 {@code skuCode + qty + 枚举} 三个值）⇒ 没有环。
     */
    private final StockMetrics stockMetrics;

    /**
     * 防护面的观测出口（块 7 · 2026-10-17 用户口径新增）。
     *
     * <p>只用于一处：{@link #addLocked} 里幂等查重挡下「同一 OrderId 的重复提交」时，
     * 发一条 {@code blocked("duplicateSubmit", ...)} —— 这属于<b>防护层成功防御</b>，
     * 事件严重度是 PROTECT（紫），<b>不进业务失败率</b>。
     *
     * <p>⚠️ 与 {@link #stockMetrics} 同一条依赖方向（订单 → 观测），观测域对订单域一无所知 ⇒ 无环。
     */
    private final ProtectionMetrics protectionMetrics;

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 支付流水号的日期段格式（{@code PAY} + {@code yyyyMMdd} + 6 位随机）。 */
    private static final DateTimeFormatter TRADE_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    public OrderService(OrderDao orderDao,
                        DistributedLockService lockService,
                        OrderStateMachine orderStateMachine,
                        OrderTransitionRecorder orderTransitionRecorder,
                        ObjectProvider<UserStateLookup> userStateLookupProvider,
                        NotificationService notificationService,
                        ShippingTrackDao shippingTrackDao,
                        ObjectMapper objectMapper,
                        CouponService couponService,
                        GoodsDao goodsDao,
                        SeckillDao seckillDao,
                        StockMetrics stockMetrics,
                        ProtectionMetrics protectionMetrics) {
        this.orderDao = orderDao;
        this.lockService = lockService;
        this.orderStateMachine = orderStateMachine;
        this.orderTransitionRecorder = orderTransitionRecorder;
        this.userStateLookup = userStateLookupProvider.getIfAvailable();
        this.notificationService = notificationService;
        this.shippingTrackDao = shippingTrackDao;
        this.objectMapper = objectMapper;
        this.couponService = couponService;
        this.goodsDao = goodsDao;
        this.seckillDao = seckillDao;
        this.stockMetrics = stockMetrics;
        this.protectionMetrics = protectionMetrics;
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
     *
     * <p>⚠️ <b>可视化段 commit 0 又在<b>末尾</b>加了 {@code Items}</b>（同 {@code Quantity} 的理由：
     * 既有 10 个形参的相对顺序一字不动）。它是**可选的显式商品明细**，只影响
     * {@code order_item} 写不写、写几行 —— <b>不参与任何闸</b>（价格闸只看 {@code Price}、
     * 出生闸只看三个状态位、冻结闸只看 {@code MerchantId}）⇒ 对既有请求的**行为零影响**。
     * 格式与回落口径见 {@link #resolveItems}。
     *
     * <p>⚠️ <b>C3 块 5 commit 5 又在<b>末尾</b>加了 {@code CouponId}</b>（同 {@code Quantity} /
     * {@code Items} 的理由：既有 11 个形参的相对顺序一字不动）。空 / 缺省 ⇒ 与加它之前**逐字节同行为**
     * （老前端不发它、结算页面没选券时也不发）。非空 ⇒ 走 {@link #addLocked} 的用券段：
     * 先校验、再核销，最后把 <b>折后价</b>写进 {@code order.Price}（那时入参 {@code Price} 是**原价**）。
     *
     * <p>⚠️ <b>C3 块 7 · commit 2c 又在<b>末尾</b>加了 {@code ActivityId}</b>（同 {@code Quantity} /
     * {@code Items} / {@code CouponId} 的理由：既有 12 个形参的相对顺序一字不动）。
     * 空 / 缺省 ⇒ 与加它之前**逐字节同行为**（不读活动表、不碰名额）。非空 ⇒ 走
     * {@link #addLocked} 的**秒杀段**：抢名额（CAS），抢不到 ⇒ <b>整单不落</b>
     * —— 那是本类唯一一条 fail-closed 的下单路径，理由见秒杀段内注释。
     * <p>⚠️ <b>它是「本单是秒杀单」的唯一声明</b>：{@code order} / {@code order_item} 都没有活动列，
     * {@code ProductInformation} / {@code Items} 也不带活动信息 ⇒ 不传就等于「不是秒杀单」，
     * 服务端**不猜**（猜错的方向是「普通单被当成秒杀单、白白吃掉一个名额」，比漏扣更坏）。
     *
     * <p>⚠️ <b>C3 块 7 · 边界补笔（2026-10-14）又在<b>末尾</b>加了 {@code Source}</b>（同
     * {@code Quantity} / {@code Items} / {@code CouponId} / {@code ActivityId} 的理由：既有 13 个
     * 形参的相对顺序一字不动）。它是**可选的写入来源标记**、落 {@code order.Source} 列
     * （块 5 可视化段给 11 张表加的那一列），让演练写入的行**在库里可辨认**：
     * 不传 / 空白 ⇒ {@code insertOrder} 归一成 {@code ""}，与加它之前**逐字节同行为**；
     * 传 ⇒ 原样落库（{@code AttackEngine} 下演练单时传 {@code 'attack'}）。
     * <p>⚠️ <b>超长**拒**而不是截断</b>（判据 = {@link #MAX_SOURCE}，见 {@link #sourceRejection}）：
     * 它和 {@code /comment/add} 的 {@code Source} 是**同一口径**，理由也同一句 ——
     * 悄悄截断会让 {@code attack} 悄悄变成别的东西而**不报错**（标错来源比没有来源坏）。
     * <p>⚠️ <b>{@code Source} 不是身份声明、不参与鉴权</b>：谁能下单仍**只看**
     * {@code IdentityGuard}，本参数的存在理由**唯一**是让行可辨认（同 {@code /comment/add} 的那句）。
     */
    // ⚠️ 【D 段 · commit 2：这里加的是**熔断**，不是事务 —— 两者不相干，别读混】
    //    本类「不得加 @Transactional」那条禁令（类注释与 addLocked 的 javadoc 各写了一遍）
    //    **一字不动、依然有效**：熔断是「下游已经不行了就先别发请求」，
    //    而事务是「这一段要么全成要么全不成」，前者不碰后者的语义。
    //    ⚠️ 而且两者在 AOP 层的先后是**实测**的：CircuitBreakerAspect 的 order 是 2147483643
    //    < 事务切面的 Integer.MAX_VALUE ⇒ 熔断在外。本类没有事务切面，这条对本类无影响，
    //    写在这里是因为「订单链上一出现注解」最容易被读成「禁令破了个口子」。
    //    落点跨域的理由（为什么不能只挂订单/支付）见 `GoodsService` 里那段同款注释。
    @CircuitBreaker(name = "mallMysqlWrite")
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
            int Quantity,
            String Items,
            String CouponId,
            String ActivityId,
            String Source
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
        // 边界补笔：Source 闸。**排在三闸之后**、{@code runLocked} **之前** ——
        // 位置理由与前三条同款（非法请求不取锁、不触库），且排在**最后**，
        // 不扰动「价格 vs 状态 vs 冻结」那三条已发布契约的措辞与顺序（与 C3 块 2 的冻结闸同一条纪律）。
        Map<String, Object> badSource = sourceRejection(Source);
        if (badSource != null) {
            return badSource;
        }
        return lockService.runLocked(LockName.ORDER_ADD, OrderId, () -> addLocked(
                CustomerId, MerchantId, OrderId, Price, ProductInformation, Images,
                PayIf, DeliveryIf, ReceiveIf, Quantity, Items, CouponId, ActivityId, Source));
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
     * <p><b>下界为什么是 {@code >= 0} 而不是 {@code > 0}</b>：{@code three-layers.md#pack-three-layers-b} 与
     * {@code :181} 两处都写「只做 {@code Price >= 0} + 上界」；且 {@code goods.Price} 无下界约束
     * ⇒ 商家可以合法把商品设成 0 元，此时前端 {@code popupTotal} 会算出 0
     * （{@code ProductPage.vue:321-323}）⇒ {@code > 0} 会**误杀免费商品订单**。
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
     * {@code order.Source} 列是 {@code varchar(16)}（块 5 可视化段给 11 张表加的那一列，
     * {@code mall_management_sql.sql:1167}）。C3 块 7 · 边界补笔起由 {@code /order/orderAdd}
     * 的**可选**入参写入 —— 与 {@code CommentService.MAX_SOURCE} 是**同一个数、同一句话**
     * （两处各自持有，刻意不抽公共常量：一个是订单域、一个是内容域）。
     */
    private static final int MAX_SOURCE = 16;

    /**
     * {@code Source} 闸。**可接受 ⇒ {@code null}；超长 ⇒ 既有形状的失败响应**。
     *
     * <p>判据是**正面形式**：{@code 拒 ⟺ 非空白 && trim().length() > MAX_SOURCE}。
     * 空白 / {@code null} 一律**放行** —— 它们是「不标来源」，由 {@code OrderDao.insertOrder}
     * 归一成 {@code ""}（与列默认值同一个值），不是错误。
     *
     * <h2>【为什么是「拒」不是「截断」】</h2>
     * 与 {@code CommentService} 的 {@code Source} 那条**逐字同源**：本列是写入方自己声明的标记，
     * 悄悄截断会让一个 17 字符的标记**静默变成**它的前 16 个字符、且**不报错** ——
     * 标错来源比没有来源坏。故宁可拒。
     *
     * <h2>【为什么在锁外、且排在三闸之后】</h2>
     * 同 {@link #priceRejection} / {@link #freezeRejection}：非法请求不取锁、不触库。
     * ⚠️ 排在**最后**是为了不动「价格 → 状态 → 冻结」那三条已发布契约的措辞与顺序。
     */
    private Map<String, Object> sourceRejection(String source) {
        if (source == null || source.isBlank() || source.trim().length() <= MAX_SOURCE) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", "Source 最多 " + MAX_SOURCE + " 字符");
        return result;
    }

    /**
     * {@code orderAdd} 的临界区。**原方法体逐字未动**（B2 的边界：只加一层锁，不动既有分支），
     * 仅在开头**插入**了查重；本笔只把两条 SQL 换成 {@link OrderDao} 调用。
     *
     * <p>⚠️ <b>块 7 在本临界区内加了三段</b>（都是**追加**，既有分支逐字未动）：
     * commit 1 的「下单即扣库存」（{@link #deductStocks}）、commit 2c 的「秒杀段」、
     * commit 2d 的「扣减成功标记」（{@code OrderDao.markStockDeducted}）。
     * 三段的相对顺序**不是随意的**：秒杀段排在 INSERT 之前（抢不到就不建单）、
     * 扣减段与标记排在 INSERT 之后（`order_item` 与 `goods_sku` 是同一批 specs 的两个落点）。
     *
     * <p>⚠️ <b>边界补笔（2026-10-14）的 {@code Source} 不是第四段</b>：它**没有分支**，只是从
     * 形参一路透传到 {@link OrderDao.OrderRow}（超长那条闸在 {@code addLocked} **之前**，
     * 见 {@code sourceRejection}）⇒ 这里的临界区**依旧只多了那三段的语义**，既有分支逐字未动。
     *
     * <p>⚠️ 本方法**不得**加 {@code @Transactional} —— 理由见类注释的两条（锁外事务 / 跨 bean 调用）。
     * 一句话：事务的提交发生在方法返回**之后**，而锁在 {@code runLocked} 的 {@code finally} 里、
     * 即方法返回**之前**已释放 —— 后进者拿到锁后的 {@code COUNT(*)} 在 RR 下读的是自己事务
     * 开始时的快照、**看不到前一个未提交的行** ⇒ 仍插 2 行。不加事务时 autocommit 让 INSERT
     * 在释放锁**之前**就提交，这正是查重成立的前提。
     */
    private Map<String, Object> addLocked(
            String CustomerId, String MerchantId, String OrderId, float Price,
            String ProductInformation, String Images, int PayIf, int DeliveryIf, int ReceiveIf, int Quantity,
            String Items, String CouponId, String ActivityId, String Source) {
        Map<String, Object> result = new HashMap<>();
        // ⚠️ 临界区内先查后插 —— 这一段是 B2 对「同一 OrderId 落库 2 行」的真修复（A-S2a-1）：
        //    · 只有锁：并发变串行，但每个请求照旧裸 INSERT ⇒ 仍插 20 行；
        //    · 只有查重：查与插之间的 TOCTOU 窗口仍在 ⇒ 两个请求可以同时查到 0 行。
        //    OrderId 由前端每次下单生成（ProductPage.vue 的 generateOrderId()）、**不复用** ⇒
        //    查重不会挡掉正常下单，只会挡「同一 OrderId 的重复提交」。
        try {
            int sameOrderId = orderDao.countByOrderId(OrderId);
            if (sameOrderId > 0) {
                // 块 7 · 2026-10-17 用户最终口径：这是**防护层成功防御**（同一 OrderId 的重复
                // 提交被幂等查重挡下，业务只执行了一次）⇒ 事件必须是 PROTECT（紫），
                // 不能算进「业务失败率」。埋点放在早退**之前**，只记事实、不改返回值。
                String oid = OrderId == null ? "" : OrderId;
                protectionMetrics.blocked("duplicateSubmit", "order",
                        "orderId=" + (oid.length() > 64 ? oid.substring(0, 64) + "…" : oid) + " 已存在",
                        null, "幂等查重命中：同一 OrderId 重复提交");
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

        // ── 会员段（批四第二批 commit 3，2026-10-19）──────────────────────────────────
        // 位置：查重**之后**、用券**之前**。三项理由：
        //  ① 会员价是券门槛与减免的**基数**（用户裁决「券与会员价可叠加」）⇒ 必须先算出来；
        //  ② 排在 INSERT 之前：它只影响本单金额，没有任何「占名额」的写，失败面天然小；
        //  ③ 查等级读失败按普通用户放行（fail-open，见 UserStateLookup.memberLevel）——
        //     绝不能因为库抖一下就让全场下单失败。
        int memberLevel = 0;
        if (userStateLookup != null) {
            try {
                memberLevel = userStateLookup.memberLevel(CustomerId);
            } catch (Exception e) {
                log.warn("会员等级读取失败（订单 {}）：customerId={} —— 本次按普通用户计价 {}", OrderId, CustomerId, e.toString());
            }
        }
        double memberPrice = memberLevel > 0
                ? com.minke.mallmanagement.shared.MemberPricing.priceOf(Price)
                : Price;
        double memberDiscount = memberLevel > 0 ? Price - memberPrice : 0d;

        // ── 用券段（C3 块 5 · commit 5）───────────────────────────────────────────────
        // 位置是**刻意**的，三条理由：
        //  ① 排在查重**之后**：重复提交该走的还是那条早退（`订单已存在`），不该先把券核销掉；
        //  ② 排在 INSERT **之前**：核销是 CAS（`unused → used`），抢不到就**一张订单都不建** ——
        //     反过来先建单再核销，抢不到时那张单已经落库且带着折后价，等于白送一次折扣；
        //  ③ 排在**临界区内**：它是「本单」的一部分，而锁的 key 就是本 OrderId ⇒ 同一单的
        //     重复提交在这里是串行的（跨单的同券竞态由 CAS 自己挡，与锁无关）。
        double discount = 0d;
        String couponAccount = null;
        if (CouponId != null && !CouponId.isBlank()) {
            // ⚠️ **折扣商品闸**（C3 块 5〈折扣商品用券规则〉，2026-10-12 用户 A 档批准）——
            //    位置理由同上面 ②：排在核销之**前** ⇒ 拒了就是「一张订单都不建、券一次都不核销」。
            //    判据（本单含任意一件 `goods.OriginalPrice > 0` 的商品 ⇒ 满减 / 折扣券整单拒）与
            //    「为什么整单拒」写在 `CouponService.redeem` 的 javadoc 里，此处不重复。
            Boolean hasDiscountItem = hasDiscountItem(MerchantId, Items, ProductInformation, Price, Quantity);
            if (hasDiscountItem == null) {
                // ⚠️ **查不出来就不放行**（fail-closed）—— 与 `writeItems` / 查重那两处的 fail-open
                //    **刻意相反**，理由：那两处是「附加物 / 加固」，坏了不该挡住一次结算；这里是
                //    **钱**的规则（多减一笔就真的少收一笔），而且它**只在这一单带券时才走到**
                //    ⇒ 影响面收得住（不带券的下单一个字节都不受影响），代价是库抖时用券要重试。
                result.put("success", false);
                result.put("msg", "折扣商品校验失败，请稍后重试");
                return result;
            }
            // ⚠️ 券包的键是**登录名**，本方法的入参是**业务号** —— 这里必须翻一次
            //    （`coupon_grant.ToAccount` 的口径见 `CouponDao.insertGrant`）。
            couponAccount = usernameOf(CustomerId);
            // ⚠️ 门槛与减免的基数 = **memberPrice**（先 9 折、再算券）—— 用户裁决的叠加顺序。
            CouponService.Redeem redeemed = couponService.redeem(
                    couponAccount, CouponId.trim(), MerchantId, memberPrice, hasDiscountItem, OrderId);
            if (!redeemed.ok()) {
                result.put("success", false);
                result.put("msg", redeemed.msg());
                return result;
            }
            discount = redeemed.discount();
        }
        // 折后价：`Price` 是**原价**（前端 / `CartService` 传的都是合计），实付价落库。
        // `Math.max(0, …)` 防负数（券的减免上界已在 `CouponService.redeem` 里锁到 `orderTotal`，
        // 这里再兜一层：金额为负的订单在 `order.Price` 的语义里没有解释）。
        float payPrice = (float) Math.max(0d, memberPrice - discount);

        // ── 秒杀段（C3 块 7 · commit 2c，2026-10-14）────────────────────────────────────
        // 位置是**刻意**的，三条理由（前两条与上面用券段逐字同源）：
        //  ① 排在**查重 / 用券之后**：那两条该走的早退还走它们自己的文案，不先动活动名额；
        //  ② 排在 **INSERT 之前**：名额没抢到 ⇒ **一张单一都不建** ——「超卖被拦住」只有排在
        //     这里才成立（排到 insertOrder 之后就只能写「先超卖、再补救」）；
        //  ③ 排在**临界区内**（锁 key = 本 OrderId）：同一单的重复提交在这里串行；
        //     跨单的抢名额竞态由 CAS 自己挡（与锁无关，同用券段的 CAS）。
        // ⚠️ 与 goods_sku 侧的 {@link #deductStocks} **刻意相反**：那边 0 行是 **fail-open**
        //    （非矩阵商品没有 goods_sku 行 ⇒ 0 行是常态，判失败会让调用方重复下单）；
        //    本段 0 行是 **fail-closed** —— 走到这里就说明调用方**显式声明**了「这是一张秒杀单」，
        //    名额没抢到 ⇒ 这单不该成立。这是本类唯一一条 fail-closed 的下单路径。
        // ⚠️ **不传 ActivityId ⇒ 这一整段一次库读都不发**（逐字节同加它之前的行为）。
        long seckillId = -1L;
        int seckillQty = 0;
        String seckillReject = null;
        if (ActivityId != null && !ActivityId.isBlank()) {
            // 件数口径 = **本单声明的总件数**（`order.Quantity`，与 resolveItems 的 `Math.max(1, Quantity)` 同源）：
            // 活动挂的是**一个 GoodsId**、名额也按「件」计，订单级没有第二个「件数」可言。
            seckillQty = Math.max(1, Quantity);
            try {
                Map<String, Object> activity = seckillDao.selectActivityByActivityId(ActivityId.trim());
                if (activity == null) {
                    // 业务串打错 / 活动行被删 —— 同样**不建单**：宁可给一句明确的拒绝，
                    // 也不给一张「名义上是秒杀单、其实没占名额」的单（那会同时骗过人和统计）。
                    seckillReject = "秒杀活动不存在";
                } else {
                    seckillId = ((Number) activity.get("Id")).longValue();
                    if (seckillDao.deductSoldStock(seckillId, seckillQty) == 0) {
                        seckillId = -1L;   // 没抢到名额 ⇒ 下面没有补偿腿要跑
                        seckillReject = "秒杀活动已售完";
                    }
                }
            } catch (Exception e) {
                // 读活动表 / 抢名额抛异常 ⇒ **不放行**（fail-closed，与用券段「查不出来就不放行」同款）。
                // ⚠️ 这一支**刻意不留补偿腿**：异常时「名额到底动了没有」**未知**，
                //    退一次可能把没加上的减掉（SQL 里 `AND SoldStock >= :n` 只能防负、防不了错）——
                //    宁可留一行带 ActivityId 的 WARN 让偏账可查，也不做一次猜的写。
                seckillId = -1L;
                seckillReject = "秒杀活动校验失败，请稍后重试";
                log.warn("订单 {} 的秒杀段异常（不建单；名额是否已动未知，故不补偿）：activityId={} {}",
                        OrderId, ActivityId, e.toString());
            }
            if (seckillReject != null) {
                result.put("success", false);
                result.put("msg", seckillReject);
                return result;
            }
        }

        // 块 7 · 阶段 3a.3：耗时紧包这一行 INSERT。CREATE **不是 CAS**（无守卫、无前态）⇒
        // 测的是 insertOrder 本身，与其余动作的 CAS 并列成同一个「落库耗时」（口径见
        // OrderTransitionRecorder#created）。⚠️ catch 那一路不调 created（只回滚券/秒杀）⇒ 用不到它
        long createStartedNanos = System.nanoTime();
        try {
            int rows = orderDao.insertOrder(new OrderDao.OrderRow(
                    OrderId, CustomerId, MerchantId, payPrice, Quantity, ProductInformation, Images,
                    PayIf, DeliveryIf, ReceiveIf, Source, com.minke.mallmanagement.shared.web.TraceIdFilter.currentBatch()));   // 块 7 · commit 10：批次号**只从 MDC 取**（头通道 `X-Drill-Batch`，白名单 `[A-Za-z0-9_-]{8,64}`，日常请求为 null）；本方法**没有** batchId 形参 ⇒ 请求体/参数传不进来，影响面限于本次请求自己写的行。写全限定名而非 import，理由同 {@code StockMetrics#event}（保本文件 `:688` 的引用不下移）
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "订单添加成功");
                // B3：只在**真的插进去之后**观测出生。放在这里而不是方法开头，是因为
                // 「订单已存在」那条早退分支并没有产生一个新状态，不该记成一次出生。
                orderTransitionRecorder.created(OrderId, PayIf, DeliveryIf, ReceiveIf,
                        System.nanoTime() - createStartedNanos);
                // 可视化段 commit 0：订单明细（商品维度统计的数据源）。
                // ⚠️ 排在「订单已落库」这个结论**之后**、且自己 fail-open（见 writeItems）——
                //    明细的成败**不改变**本方法的返回值，否则调用方会把「订单其实落了」
                //    当成失败而**重复下单**。
                // ⚠️ 传的仍是**原价** `Price`：`resolveItems` 的回落口径要拿它反推单价
                //    （`一单多品` 时 `Price / Quantity`），传折后价会把单价一起算错 ——
                //    明细记的是「卖了哪几件、各卖多少钱」，与实付金额是两回事。
                List<ItemSpec> itemSpecs = resolveItems(Items, ProductInformation, Price, Quantity);
                writeItems(OrderId, MerchantId, itemSpecs);
                // 块 7 · 库存治理（TD-36）：**下单即扣**（《块 7 参考》§5.2 B5 丙的第一拍）。
                // ⚠️ 排在「订单已落库 + 明细已写」之后、吃**同一批 itemSpecs** ——
                //    扣谁、扣多少，与「这单卖了什么」本来就是同一个事实，不另解析一次。
                // ⚠️ 与 writeItems 同款 **fail-open**：扣减 0 行（库存不足 / 非矩阵商品无
                //    goods_sku 行）都不改本方法的返回值 —— 判成失败会让调用方重复下单。
                // 块 7 · commit 2d：把「这批扣减**真的动过库存**」记下来（{@code order.StockDeducted}，
                // Migration ⑱）—— 超时释放的前置判据**只有这一列**。
                // ⚠️ fail-soft：标记写不进去**不改订单结论**（订单已落库是既成事实，改成失败会让
                //    调用方重复下单），代价是那单超时取消时**不回补**（偏账方向选「少还一件」，不选「虚增」）。
                if (deductStocks(MerchantId, itemSpecs)) {
                    markStockDeducted(OrderId);
                }
                if (discount > 0d) {
                    // 只读给调用方（Controller 的留痕 / 前端回显）；**不是**契约的一部分 ——
                    // 不选券的老请求永远不会看到这两个键（`Result.of` 逐条 put）。
                    result.put("CouponId", CouponId.trim());
                    result.put("Discount", discount);
                }
                if (memberDiscount > 0d) {
                    // 批四第二批 commit 3：会员单才回这两个键（**严格加性** —— 非会员的响应形状
                    // 与改动前逐字相同）。前端收银弹窗据此画「会员优惠 -X」一行。
                    result.put("MemberPrice", memberPrice);
                    result.put("MemberDiscount", memberDiscount);
                }
            } else {
                result.put("success", false);
                result.put("msg", "订单添加失败");
                releaseCoupon(couponAccount, CouponId, OrderId, discount);
                releaseSeckill(seckillId, seckillQty, OrderId);
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "数据库错误：" + e.getMessage());
            releaseCoupon(couponAccount, CouponId, OrderId, discount);
            releaseSeckill(seckillId, seckillQty, OrderId);
        }
        return result;
    }

    /**
     * 建单失败时把券放回未使用（{@link #addLocked} 用券段的补偿腿）。**fail-soft**。
     *
     * <p>⚠️ 不这么做的话：券核销了、订单没落 ⇒ 用户手里平白少一张券，而系统里没有任何一条记录
     * 说它去哪了（{@code coupon_grant.OrderId} 指向一个不存在的单）。
     *
     * <p>⚠️ 回退失败**不改变返回值**：那时用户已经要看到「订单添加失败」了，再抛异常只会盖掉它。
     * 记一行 WARN 就够 —— 库里 `Status='used'` 且 `OrderId` 无对应订单行，是**可查的**痕迹
     * （同 {@code writeItems} 的 fail-open 口径）。
     */
    private void releaseCoupon(String account, String couponId, String orderId, double discount) {
        if (discount <= 0d || couponId == null || couponId.isBlank()) {
            return;
        }
        try {
            if (!couponService.release(account, couponId.trim(), orderId)) {
                log.warn("订单未建成，但优惠券回退未命中任何行（可能已被并发改动）：coupon={} order={}", couponId, orderId);
            }
        } catch (Exception e) {
            log.warn("订单未建成，且优惠券回退失败（需人工核对）：coupon={} order={} —— {}", couponId, orderId, e.toString());
        }
    }

    /**
     * 业务号 → 登录名（券包的键）。查不到 ⇒ 原样回传，交给 {@code CouponService.redeem} 判缺参
     * （它会回「无法识别领券账户」）—— 本方法**不**在这里编一个身份出来。
     */
    private String usernameOf(String customerId) {
        try {
            String username = couponService.usernameOf(customerId);
            return username == null ? customerId : username;
        } catch (Exception e) {
            log.warn("业务号翻登录名失败（券核销将按原值判，多半会被拒）：customer={} —— {}", customerId, e.toString());
            return customerId;
        }
    }

    /**
     * 本单**含不含折扣商品**（{@code goods.OriginalPrice > 0} 的任一件）。
     *
     * @return {@code TRUE} 含 / {@code FALSE} 不含 / {@code null} **查不出来** —— 三态是刻意的：
     *         调用方（用券段）要按 {@code null} 走 fail-closed（见那里的注释），
     *         把「查不出来」折叠成 {@code false} 就等于**静默放行**。
     *
     * <p>码从 {@link #resolveItems} 取 —— 与落明细（{@code writeItems}）**同一个来源**，
     * 不另造一份解析（那份解析有两条路：显式 {@code Items} 与回落 {@code ProductInformation}，
     * 抄第二份必定在某一条上分叉）。
     * <p>⚠️ 传的 {@code Price} 仍是**原价合计**（此处 {@code payPrice} 还没算）——
     * {@code resolveItems} 的回落口径要拿它反推单价，传折后价会把单价一起算错。
     */
    private Boolean hasDiscountItem(String merchantId, String Items, String ProductInformation,
                                    float Price, int Quantity) {
        List<String> codes = new ArrayList<>();
        for (ItemSpec s : resolveItems(Items, ProductInformation, Price, Quantity)) {
            if (s.code() != null && !s.code().isBlank()) {
                codes.add(s.code());
            }
        }
        if (codes.isEmpty()) {
            // 一单里一个码都没解析出来：按「不含折扣商品」判 —— 这不是「查不出来」，
            // 是**确实没有商品可判**（真到那一步，下单本身也会在别处被拦）。
            return Boolean.FALSE;
        }
        try {
            return !orderDao.selectDiscountCodes(merchantId, codes).isEmpty();
        } catch (Exception e) {
            log.warn("折扣商品判定失败（本单 {} 个码，用券将按「查不出来」拒）—— {}", codes.size(), e.toString());
            return null;
        }
    }

    // ==================== 订单明细（可视化段 commit 0） ====================

    /**
     * 一条订单明细的**规格**（还没绑订单号 / 商家 —— 那两样是整单级的，由 {@code addLocked} 现填）。
     *
     * <p>⚠️ 组件名 {@code qty} 是**线格式的一部分**（{@link #encodeItems} 用 Jackson 直接序列化本
     * record）⇒ 改它等于改契约，必须与 {@link #parseExplicitItems} 那边的键名同改。
     *
     * @param code  商品编码（对齐 {@code goods.ProductCode}）。**必填**，空 ⇒ 这条被丢
     * @param name  商品名快照。可缺（{@code goods} **无软删列**，商品会被物理删掉 ⇒
     *              快照是「货没了、历史销量还在榜上」的唯一依据；缺时记 {@code null}，
     *              榜单回落 LEFT JOIN {@code goods}）
     * @param price 成交**单价**
     * @param qty   数量（≥ 1）
     */
    public record ItemSpec(String code, String name, double price, int qty) { }

    /**
     * 解析本次下单的商品明细 —— <b>显式 {@code Items} 优先，解析不出才回落到既有格式</b>。
     *
     * <p>⚠️ 顺序是刻意的，不是「先看哪个都一样」：显式入参带着**单价与名**（调用方知道），
     * 回落口径最多只推得出一件商品的「价 ÷ 量」。
     *
     * <p>⚠️ 两路都失败 ⇒ **空表** ⇒ 明细不写，而**订单照常落库** —— 这是硬性约束
     * 「不能阻碍旧功能运作」在本笔的落点。
     */
    private List<ItemSpec> resolveItems(String Items, String ProductInformation, float Price, int Quantity) {
        List<ItemSpec> items = parseExplicitItems(Items);
        if (items.isEmpty()) {
            items = inferItemsFromProductInformation(ProductInformation, Price, Quantity);
        }
        return mergeByCode(items);
    }

    /**
     * 解析显式明细 {@code Items} —— **JSON 数组**，一行一件：
     * {@code [{"code":"IJ-555","name":"222","price":5555,"qty":2}, ...]}。
     *
     * <p>⚠️ 字段口径：{@code code} 必填（缺失 ⇒ **丢这一条**，不猜）；{@code name} 可缺
     * （缺失 ⇒ {@code null}）；{@code qty} 缺失或 &lt; 1 ⇒ 记 1；{@code price} 缺失 ⇒ 记 0。
     *
     * <p>⚠️ <b>为什么是 JSON，而不是 {@code 码:量:价} 这种自造分隔符串</b>：商品名
     * （{@code goods.GoodName}）**没有禁用 {@code :} 与 {@code ；}**，自造分隔符要么错位、
     * 要么得再造一套转义规则 —— 而错位的结果是**脏数据**，比没数据更坏。JSON 的转义
     * 是现成的，Jackson 在本仓又已是注入式 bean（{@code AuditService}）。
     *
     * <p>⚠️ <b>整个入参不是合法 JSON</b>（或是个 JSON 对象而非数组）⇒ 记一行 WARN、
     * 返回**空表** ⇒ 回落口径接手 ⇒ 再解析不出就只写订单、不写明细。**不抛异常**。
     */
    private List<ItemSpec> parseExplicitItems(String Items) {
        if (Items == null || Items.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> raw;
        try {
            raw = objectMapper.readValue(Items, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            log.warn("orderAdd 的 Items 不是合法的 JSON 数组，本次不写明细（订单照常落库）：{}", e.toString());
            return List.of();
        }
        List<ItemSpec> items = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            if (row == null) {
                continue;
            }
            String code = trimmed(row.get("code"));
            if (code == null) {
                // 没有码就没法把这一件挂到商品维度上 —— 宁可少一行，不写一行对不上货的。
                log.warn("orderAdd 的 Items 里有一条缺 code，已丢弃：{}", row);
                continue;
            }
            items.add(new ItemSpec(clip(code, 40), clip(trimmed(row.get("name")), 255),
                    number(row.get("price")), Math.max(1, (int) number(row.get("qty")))));
        }
        return items;
    }

    /**
     * 回落：按 {@code ProductInformation} 的**既有格式**（{@code 名:码}、多品以「；」分隔，
     * 见 {@code ProductPage.vue} 的 {@code buildProductInformation}）推**一条**明细。
     *
     * <p>⚠️ <b>只在恰有一段时才推。</b>判据是可推导性，不是保守：
     * {@code order.Price} 是这一单的**小计**、{@code order.Quantity} 是**总件数** ⇒
     * 单品时 {@code 单价 = Price ÷ Quantity} 是恒等式；<b>多品时这个商只是加权均价，
     * 不是任何一件的真实单价</b> —— 推出来的行是脏数据，宁可不写。
     *
     * <p>⚠️ 码取**最后一个 {@code :} 之后**（商品名里可能自带 {@code :}）；没有 {@code :}、
     * 或 {@code :} 结尾、或码为空 ⇒ 推不出 ⇒ 不写。
     *
     * <p>这一段专为**老调用方**而留（A3 压测脚本、以及本笔之前的前端）：它们只发
     * {@code ProductInformation}。购物车那条路会在 commit 0b 改成发显式 {@code Items}。
     */
    private List<ItemSpec> inferItemsFromProductInformation(String ProductInformation, float Price, int Quantity) {
        if (ProductInformation == null || ProductInformation.isBlank()) {
            return List.of();
        }
        String[] parts = ProductInformation.split("；");
        if (parts.length != 1) {
            log.info("orderAdd 未收到 Items，且 ProductInformation 含 {} 段（一单多品）⇒ 单价不可反推，本次不写明细",
                    parts.length);
            return List.of();
        }
        int colon = ProductInformation.lastIndexOf(':');
        if (colon < 0 || colon == ProductInformation.length() - 1) {
            return List.of();
        }
        String code = ProductInformation.substring(colon + 1).trim();
        if (code.isEmpty()) {
            return List.of();
        }
        int qty = Math.max(1, Quantity);
        // ⚠️ float ÷ int 仍是 float（先窄化再赋给 double 形参）—— 与 order.Price 的列类型同域，
        //    不引入 double 精度错觉。
        return List.of(new ItemSpec(clip(code, 40),
                clip(ProductInformation.substring(0, colon).trim(), 255), Price / qty, qty));
    }

    /**
     * 把明细编成 {@code Items} 的线格式 —— <b>本类是全仓唯一知道这个格式的地方</b>
     * （与 {@link #parseExplicitItems} 成对：编码、解码、字段名三样都只在这里。
     * 调用方不必知道字段名，将来换格式只改这一对）。
     *
     * <p>⚠️ 序列化失败（理论上不会：{@code ItemSpec} 就是四个简单字段）⇒ 记 WARN、返回
     * {@code null} ⇒ 调用方发出去的 {@code Items} 为空 ⇒ <b>服务端回落到
     * {@code ProductInformation} 口径</b>。**不抛异常**：明细是附加物，
     * 不该因为它的编码失败而挡住一次结算。
     */
    public String encodeItems(List<ItemSpec> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            log.warn("明细编码失败（{} 行），本次不发 Items、由服务端回落 ProductInformation 口径：{}",
                    items.size(), e.toString());
            return null;
        }
    }

    /**
     * 同码合并。**必须有**：{@code order_item} 的唯一键是 {@code (OrderId, ProductCode)}，
     * 同一个码进来两行会在库里直接撞键（然后被 {@link #writeItems} 的 fail-open 吞掉 ⇒
     * 表现为「明细整批丢失」）。数量相加，名取首个非空的。
     */
    private static List<ItemSpec> mergeByCode(List<ItemSpec> items) {
        Map<String, ItemSpec> byCode = new LinkedHashMap<>();
        for (ItemSpec it : items) {
            ItemSpec prev = byCode.get(it.code());
            if (prev == null) {
                byCode.put(it.code(), it);
            } else {
                byCode.put(it.code(), new ItemSpec(it.code(),
                        prev.name() != null ? prev.name() : it.name(),
                        prev.price(), prev.qty() + it.qty()));
            }
        }
        return new ArrayList<>(byCode.values());
    }

    /**
     * 落明细。**只 INSERT，不做 upsert** —— 唯一调用点在同一把 {@code order-add} 锁内，
     * 且前面刚过完「同 OrderId 查重」，重复进来会在更早的分支被挡掉。
     *
     * <p>⚠️ <b>fail-open</b>：写失败只记 WARN，**不改变订单的成败结论**。这是刻意的 ——
     * 「订单已落库」是对外已成立的契约，为明细把它改成失败会让调用方**重复下单**
     * （同 {@code order_shipping_track} 三处出口的 fail-soft 口径）。代价是这一单不进商品榜。
     */
    private void writeItems(String OrderId, String MerchantId, List<ItemSpec> specs) {
        if (specs.isEmpty()) {
            return;
        }
        String merchantId = MerchantId == null ? "" : clip(MerchantId, 40);
        List<OrderDao.ItemRow> rows = new ArrayList<>(specs.size());
        for (ItemSpec s : specs) {
            rows.add(new OrderDao.ItemRow(OrderId, merchantId, s.code(), s.name(),
                    s.price(), s.qty(), s.price() * s.qty()));
        }
        try {
            orderDao.insertItems(rows);
        } catch (Exception e) {
            log.warn("订单 {} 的 {} 行明细写失败（订单已落库、明细缺失）：{}", OrderId, rows.size(), e.toString());
        }
    }

    /**
     * <b>下单即扣库存</b>（C3 块 7 · 库存治理 TD-36 的**第一拍**，《块 7 参考》§5.2 B5 丙）。
     *
     * <p>按 {@link ItemSpec#code()} 逐条 CAS 扣 {@code goods_sku.Stock}
     * （{@link GoodsDao#deductStock}）。与 {@link #writeItems} **吃同一批 specs**：扣谁、扣多少，
     * 与「这单卖了什么」是同一个事实。
     *
     * <p>⚠️ <b>「0 行」不是失败</b> —— 两种 0 行都**不挡单**、**不改 {@code orderAdd} 的返回值**：
     * <ol>
     *   <li><b>库存不足</b>（防超卖命中）—— 本方法的主要目的；</li>
     *   <li><b>非矩阵商品根本没有 {@code goods_sku} 行</b> —— 订单装的是 {@code ProductCode}，
     *       只有矩阵商品的 {@code SkuCode} 才与之相等。这是本次核对新增的边界
     *       （用户 2026-10-13 批的 A 档 4 第二条）。</li>
     * </ol>
     * 把 0 行判成失败会让调用方**重复下单** —— 与 {@link #writeItems} 把明细失败吞掉是同一条理由。
     *
     * <p>⚠️ <b>单条失败只记 WARN、继续下一条</b>：一条 SKU 的意外不该吞掉整单其它 SKU 的扣减
     * （与 {@code writeItems} 的「整批 fail-open」略不同 —— 那里是批量 INSERT、这里逐条独立）。
     *
     * <p>⚠️ {@code code} 空 / {@code qty < 1} 的明细**直接跳过**：{@code resolveItems} 已把缺失的
     * {@code qty} 规整成 1，这里只管显式给了 0 / 负数的情况 —— 那种行不该去「加库存」。
     *
     * <p>⚠️ <b>本方法在订单锁的临界区内</b>（调用方 = {@link #addLocked}）⇒ 与
     * {@code OrderDao.insertOrder} 同一条禁令：不得加 {@code @Transactional}
     * （{@code DistributedLockServiceTest.orderChainIsNotTransactional} 有结构断言兜底）。
     *
     * <p>⚠️ <b>本方法同时是库存环（{@code StockMetrics}）唯一的写入点之一</b>（块 7 · commit 2）：
     * 每条明细各记一次 {@code mall.stock.deduct} + 一条事件（另一处在 {@code OrderTimeoutCancelJob}
     * 的 {@code mall.stock.release}）。埋点是**观察者**：入参只有枚举、异常那一支也先接住再记，
     * 故「记不记」不影响本方法的控制流。
     *
     * <p>⚠️ <b>返回值（块 7 · commit 2d 新增）</b>：{@code true} = 这批扣减里**至少有一条真的
     * 动过库存**（CAS 命中 ≥1 行）。调用方 {@link #addLocked} 拿它当 {@code order.StockDeducted}
     * 的唯一判据 —— 超时释放靠那一列决定「该不该回补」。注意它与上面埋点用的 {@code rows == 1}
     * **刻意不是同一个判据**，理由见方法内注释（{@code SkuCode} 非唯一键）。
     *
     * @return 是否至少有一条明细真的扣成了库存
     */
    private boolean deductStocks(String merchantId, List<ItemSpec> specs) {
        boolean anyDeducted = false;
        for (ItemSpec s : specs) {
            if (s.code() == null || s.code().isBlank() || s.qty() < 1) {
                continue;
            }
            // 块 7 · 阶段 3a.3：耗时**紧包那一行 CAS**（口径 (a)）—— 不含循环外的组装，也不含
            // 多 sku 循环。选 (a) 而非 (b)：多 sku 订单下 (b) 会把 N 次写库摊成一个数，而事件是
            // **逐 sku** 发的，两者对不上。⚠️ 计时变量必须声明在 try **外**：catch 里也要用（编译期作用域）
            long deductStartedNanos = System.nanoTime();
            try {
                // 块 7 · 阶段 3a.3 第二步：**先算好落库用的那一串**，两处共用同一个值 ——
                // ⚠️ 下面那条 SELECT 必须用它（列宽 40 是 `goods_sku.SkuCode` 的硬约束，
                //    超长的码落库时已被截断 ⇒ 拿未截断的原串去查必定查无此行，静默给出 null 剩余）。
                String sku = clip(s.code(), 40);
                int rows = goodsDao.deductStock(sku, s.qty());
                long deductNanos = System.nanoTime() - deductStartedNanos;
                // 块 7 · 阶段 3a.3 第二步：**扣完那一刻的剩余**（口径全文见
                // `StockMetrics.StockEvent#remaining()`）——必须在 CAS **之后**读、而在
                // 发事件**之前**读，中间不夹别的事。⚠️ 它**不进 deductNanos 的计时窗**：
                // 计时口径（a）钉的是「紧包那一行 CAS」那一条 SQL，加进来会把两个操作的
                // 耗时摊成一个数（本仓「一个数说两件事」的老毛病）。
                // ⚠️ 读它与扣减**失败也要能分开**：stockRemaining 自己吞异常 ⇒ 读不到只是
                //    少一个展示字段，**绝不把一次成功的 CAS 记成 error**（那是反向误报）。
                Integer remaining = stockRemaining(sku);
                // 批四第二批：扣减后只剩 <10 件 ⇒ 给商家一条站内信（每 SKU 每日一条）。
                // ⚠️ 只在 rows>0（真的扣成了）且读得到剩余时发；alertLowStock 自身 fail-soft
                //    （去重查询失败照发、send 失败吞掉），**不影响下单结论**。
                if (rows > 0 && remaining != null) {
                    notificationService.alertLowStock(merchantId, sku, null, s.name(), remaining);
                }
                // 块 7 · 观测（commit 2）：**两条通道在同一调用点**发（计数 + 事件流）——
                // 与既有三环同款。0 行记 INSUFFICIENT 而**不是** ERROR：CAS 没命中是正常分支
                // （库存不足 / 非矩阵商品无行，见上面的 javadoc），记成错误会让告警误报。
                //
                // ⚠️ 埋点判据与下面的返回值判据**刻意不同**（{@code rows == 1} vs {@code rows > 0}）：
                //    指标口径是既有契约，本笔不动；返回值的语义是「这批库存**被动过**吗」，
                //    只要有一行命中就成立 —— {@code SkuCode} 不是唯一键，理论上一条明细可以命中多行。
                // ⚠️ 事件里的 skuCode 仍用**未截断**的 {@code s.code()}（既有口径，本笔不动）：
                //    展示串要还原商家填的原值，而查库用的才是截断后的那一串。
                // 块 7 · 阶段 3a.3 后续：失败原因（人话）——`result` 只回答「哪一档」，
                // 这里回答「为什么」。insufficient 的两种成因（库存不足 / 无 SKU 行）就靠它分开。
                // ⚠️ remaining 为 null 时**不猜**是哪一种：stockRemaining 刻意 fail-soft，
                //    文案如实并列两种可能（新增一次查询只为分辨文案不值得）。
                String deductReason = null;
                if (rows == 0) {
                    deductReason = remaining == null
                            ? "防超卖拦截：扣减未命中（库存不足或 goods_sku 无此 SKU，需 " + s.qty() + " 件）"
                            : "防超卖拦截：库存不足（需 " + s.qty() + " 件，剩余 " + remaining + " 件）";
                } else if (rows > 1) {
                    deductReason = "扣减异常：影响行数为 " + rows + "（SkuCode 可能非唯一）";
                }
                stockMetrics.deduct(s.code(), s.qty(),
                        rows == 1 ? Deduct.OK : Deduct.INSUFFICIENT, deductNanos, remaining, deductReason);
                if (rows > 0) {
                    anyDeducted = true;
                }
            } catch (Exception e) {
                // 异常这一路也埋点：否则「扣减根本没发出去」在指标上与「一次都没下单」无法区分。
                // ⚠️ remaining 传 null：UPDATE 到底生效没有都不知道，剩余更无从谈起（口径 ②）。
                stockMetrics.deduct(s.code(), s.qty(), Deduct.ERROR, System.nanoTime() - deductStartedNanos, null,
                        com.minke.mallmanagement.shared.text.EventReason.of("库存扣减失败", e));
                log.warn("订单明细 {} 的库存扣减失败（跳过该条，不动订单结论）：{}", s.code(), e.toString());
            }
        }
        return anyDeducted;
    }

    /**
     * 读某个 SKU 的**当前**库存，**任何失败都吞掉并返回 {@code null}**（块 7 · 阶段 3a.3 第二步）。
     * **fail-soft，与 {@link #markStockDeducted} 同款**。
     *
     * <p>⚠️ <b>为什么必须单独一层 try</b>：这是「给事件补一个展示字段」，而它下面紧邻的是
     * {@link GoodsDao#deductStock} 的 {@code catch (Exception e)} —— 那条路会把异常记成
     * {@code Deduct.ERROR}（一个用来告警的取值）。若本方法的异常漏到那一层，就会发生
     * <b>「库存已经真的扣成了，事件却报 error」</b> 的反向误报：告警响了、人去查、发现账是对的。
     * 为一个展示字段造出一次假告警，代价远大于这个字段本身。
     *
     * <p>⚠️ 返回值语义：{@code null} = 读不到（DAO 抛异常 / 该 {@code SkuCode} 在
     * {@code goods_sku} 里没有行 —— 非矩阵商品属这一档，是**正常**）。两种成因不区分：
     * 对「页面上这一格有没有数」而言它们是同一件事，且区分它们要靠把异常分类，
     * 那会引出本类不该有的耦合。
     *
     * @param skuCode <b>已经截断到列宽</b>的那一串（调用方 {@link #deductStocks} 算好的
     *                —— 拿未截断的原串来查，长码会静默查无此行）
     */
    private Integer stockRemaining(String skuCode) {
        try {
            return goodsDao.selectStockBySku(skuCode);
        } catch (Exception e) {
            log.warn("订单明细 {} 的剩余库存读取失败（只影响这一条事件的 remaining，扣减结论不受影响）：{}",
                    skuCode, e.toString());
            return null;
        }
    }

    /**
     * 把「本单库存真扣成过」落到 {@code order.StockDeducted = 1}（Migration ⑱）。**fail-soft**。
     *
     * <p>⚠️ 写失败**不改订单结论**（订单已落库是既成事实）：记一行 WARN 就往下走。代价是那单
     * 超时取消时不会回补库存 —— 偏账方向刻意选「少还一件」而不是「无中生有补一件」
     * （{@code OrderTimeoutCancelJob} 的前置判据读这一列，读不到 = 没扣过 = 不回补）。
     */
    private void markStockDeducted(String orderId) {
        try {
            int rows = orderDao.markStockDeducted(orderId);
            if (rows == 0) {
                // `AND StockDeducted = 0` 下的 0 行只有两种可能：订单行不见了，或已被标记过（幂等命中）。
                // 前者意味着「扣了库存但订单没了」—— 值得一行 WARN。
                log.warn("订单 {} 的库存扣减标记未写入（订单行不存在或已标记过）：库存已动，超时取消时可能不回补", orderId);
            }
        } catch (Exception e) {
            log.warn("订单 {} 的库存扣减标记写入失败（订单照常成立；该单超时取消时不会回补库存）：{}", orderId, e.toString());
        }
    }

    /**
     * 建单失败时把秒杀名额退回（{@link #addLocked} 秒杀段的补偿腿）。**fail-soft**。
     *
     * <p>⚠️ 与券补偿同款：秒杀名额是**先扣后建单**，建单失败不退 ⇒ 一个名额被**永久吞掉**
     * （活动少卖一件，且账面上「已售」比真实成交多）。
     *
     * <p>⚠️ {@code seckillId <= 0} 直接 return —— 没走到扣减那一步（活动不存在 / 已售完 /
     * 校验异常 / 本单压根没带 {@code ActivityId}）时都不该退，退了就是**凭空加库存**。
     *
     * <p>⚠️ 退失败不改返回值，只记 WARN（同 {@link #releaseCoupon}）——
     * {@code seckill_activity.SoldStock} 与真实成交的差额是**可查**的痕迹。
     */
    private void releaseSeckill(long seckillId, int qty, String orderId) {
        if (seckillId <= 0L || qty < 1) {
            return;
        }
        try {
            if (seckillDao.releaseSoldStock(seckillId, qty) == 0) {
                log.warn("订单未建成，但秒杀名额退回未命中任何行（名额可能已被并发改动）：seckillId={} qty={} order={}",
                        seckillId, qty, orderId);
            }
        } catch (Exception e) {
            log.warn("订单未建成，且秒杀名额退回失败（需人工核对）：seckillId={} qty={} order={} —— {}",
                    seckillId, qty, orderId, e.toString());
        }
    }

    /** 去空白；空串一律规整成 {@code null}（「没给」与「给了个空值」在明细里是同一件事）。 */
    private static String trimmed(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 宽松取数：JSON 里可能是数字，也可能是带引号的数字串；取不出记 0。 */
    private static double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** 截到列宽。**必须有**：超长的名 / 码会让整批 INSERT 报错 ⇒ 被 fail-open 吞成「明细整批丢失」。 */
    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
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
        // 块 7 · 阶段 3a.3：耗时**从 CAS 前到 CAS 后**（裁定 ⑤「测真 CAS 前到后」）。⚠️ 声明在
        // try **外**：catch 里也要用（编译期作用域）。⚠️ 本段仍在订单锁的临界区内 ⇒ 这个数含
        // **持锁等待 + 真 UPDATE 往返**，不是纯 SQL 耗时（口径见 MallOrderStateMetrics#transition）
        long casStartedNanos = System.nanoTime();
        try {
            int rows = orderDao.casUpdate(sql, orderId);
            long casNanos = System.nanoTime() - casStartedNanos;
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", okMsg);
                // ⚠️ C3 块4 补丁：状态位已置、**仍在同一把锁内** ⇒ 紧接着写明细列
                //    （TradeNo / PaidAt / ExpressCompany / …，见 OrderStateMachine.detailUpdateSql）。
                //    CAS 的 golden 串一字未动；非原子性由订单锁消除竞态。
                writeDetails(action, orderId, detailParams);
                orderTransitionRecorder.applied(action, orderId, casNanos);
            } else {
                result.put("success", false);
                result.put("msg", noRowsMsg);
                // 守卫没命中：重复流转 / 单不存在。**既有行为**，本笔不新增任何拒绝文案。
                // 原因直接复用响应文案（noRowsMsg）—— 页面看到的拒绝理由与接口返回的是同一句
                orderTransitionRecorder.rejected(action, orderId, casNanos, noRowsMsg);
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "数据库错误：" + e.getMessage());
            // 异常路径：量的是「卡了多久才失败」，故现算（casNanos 那一行没执行到）
            orderTransitionRecorder.errored(action, orderId, System.nanoTime() - casStartedNanos,
                    com.minke.mallmanagement.shared.text.EventReason.of("数据库错误", e));
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
