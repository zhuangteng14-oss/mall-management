package com.minke.mallmanagement.merchant.service;

import com.minke.mallmanagement.goods.dao.GoodsShelfDao;
import com.minke.mallmanagement.merchant.DowngradeStates;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.protect.ProtectionGate;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import com.minke.mallmanagement.user.service.UserCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家降级的**状态机执行体**（C3 块 2 用户块）—— 五阶段里的 ①③④⑤ 四步落在这里，
 * ②（通知期）是「什么都不做、等一天」的被动阶段，没有代码。
 *
 * <h2>【五阶段与本类的方法对映】</h2>
 * <ol>
 *   <li><b>管理员发起</b> ⇒ {@link #schedule}：写 {@code scheduled} + {@code ScheduledDowngradeAt=now+1d}。
 *       通知由 Controller 在事务提交后发。</li>
 *   <li><b>通知期（1 天）</b> ⇒ 无代码。⚠️ 此阶段商家**仍可正常接单**（下单闸只认
 *       {@code DowngradeStates.FROZEN}），这是刻意留的收尾窗口。</li>
 *   <li><b>到期执行</b> ⇒ {@link #runDue}（由 {@code @Scheduled} 任务调用）：
 *       {@code login.Role 2→1} + 商品全下架（{@code ShelfState=0}，**不删行**）+ 写 {@code frozen}
 *       + {@code FreezeUntil=now+30d}。</li>
 *   <li><b>30 天内重新申请通过 ⇒ 自动恢复</b> ⇒ 落在
 *       {@link MerchantApplicationService#audit} 的批准路径（它读 {@code FreezeUntil} 判窗口）。</li>
 *   <li><b>30 天后通过 ⇒ 需人工确认</b> ⇒ {@link #relist}（管理员显式把商品重新上架）。</li>
 * </ol>
 *
 * <h2>【⚠️ 降级期间「冻结新单、允许收尾旧单」怎么实现】</h2>
 * 「冻结新单」= 下单链路（{@code OrderService.orderAdd}）上的冻结闸，见
 * {@code UserStateLookup#isMerchantFrozen}。
 * 「允许收尾旧单」= <b>本类不碰 {@code order} 表的任何行</b> —— 已有的订单照常可支付/发货/收货
 * （那三个状态迁移不经过下单链路）。这是「冻结」二字的精确含义：不接新活，不砸旧活。
 *
 * <h2>【⚠️ 本类**不删任何行**（规则 v2：回滚 = 逻辑废弃）】</h2>
 * 降级只改状态列。30 天窗口末端 {@code FreezeUntil} 到了也**不物理删** —— 它只是
 * 「此后恢复需要人工确认」的时间判据，不是 TTL。
 */
@Service
public class MerchantDowngradeService {

    private static final Logger log = LoggerFactory.getLogger(MerchantDowngradeService.class);

    /** 通知期天数（阶段 ②的窗口）—— 用户裁决：1 天。 */
    public static final int NOTICE_DAYS = 1;

    /** 冻结期天数（{@code FreezeUntil} = 执行时刻 + 它）—— 用户裁决：30 天。 */
    public static final int FREEZE_DAYS = 30;

    /** {@code login.Role} 取值（与 {@code UserService.businessId} 同源）。 */
    private static final int ROLE_CUSTOMER = 1;
    private static final int ROLE_MERCHANT = 2;

    /** 重试对应的防护开关 key（{@code ProtectionGate.KEYS} 之一；D 段 · 阶段 2b 起已接闸）。 */
    private static final String RETRY_KEY = "retry";

    /** 重试事件的 {@code scope} —— 「重试发生在哪一段」，即本落点。 */
    private static final String RELIST_SCOPE = "relist";

    /** 最多尝试次数（**含**第一次）。用户 2026-10-15 裁定「Spring Retry 2-3 次」⇒ 取 3。 */
    private static final int RELIST_MAX_ATTEMPTS = 3;

    private final UserGovernanceDao userGovernanceDao;
    private final GoodsShelfDao goodsShelfDao;
    private final UserCacheService userCacheService;
    private final ProtectionGate protectionGate;
    private final ProtectionMetrics protectionMetrics;

    /** 事务边界：**显式**开，理由见 {@link #relist} 的「重试在事务外层」。 */
    private final TransactionTemplate transactionTemplate;

    /** {@link #relist} 的重试模板（只对死锁类重试，见 {@link #buildRelistRetry()}）。 */
    private final RetryTemplate relistRetry;

    public MerchantDowngradeService(UserGovernanceDao userGovernanceDao,
                                    GoodsShelfDao goodsShelfDao,
                                    UserCacheService userCacheService,
                                    ProtectionGate protectionGate,
                                    ProtectionMetrics protectionMetrics,
                                    PlatformTransactionManager transactionManager) {
        this.userGovernanceDao = userGovernanceDao;
        this.goodsShelfDao = goodsShelfDao;
        this.userCacheService = userCacheService;
        this.protectionGate = protectionGate;
        this.protectionMetrics = protectionMetrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.relistRetry = buildRelistRetry();
    }

    // ==================== 阶段 ①：发起 ====================

    /**
     * 发起降级（{@code scheduled}）。{@code reason} **必填** —— 它会写进
     * {@code DowngradeReason}，而那一列是**给商家看的**（降级通知正文里就带着它，
     * 见用户裁决「DowngradeReason：保留，不剥」）。故不许留空。
     *
     * <h2>【⚠️ 补丁 A3：同一账号同一次降级**只排定一次**（CAS）】</h2>
     * CAS 落在 {@code UserGovernanceDao.markScheduled} 的 {@code AND DowngradeState = 'none'} 上
     * —— 判据必须**在数据库里**（先 SELECT 再 UPDATE 会让两个并发的管理员都看到 {@code none}，
     * 同 {@code MerchantApplicationDao} 的 CAS 论证）。{@code 0} 行**不等于失败**，也不等于
     * 「用户不存在」：要再读一次状态才说得清（账号不存在 / 已排定 / 已冻结）。
     *
     * <p>⚠️ 这一条同时是**补丁 A4 的根因修法**：重复点击旧写法会 N 次写库 + N 条通知
     * （实测一次降级刷了 14 条）。CAS 之后，重复点击**根本走不到 Controller 的通知那一步**
     * （见 {@link #scheduleMiss} 为什么一律回 {@code success:false}）。
     */
    public Map<String, Object> schedule(String targetUsername, String reason) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(targetUsername)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        if (isBlank(reason)) {
            result.put("success", false);
            result.put("msg", "降级必须填写原因（该原因会展示给商家）");
            return result;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dueAt = now.plusDays(NOTICE_DAYS);
        int affected = userGovernanceDao.markScheduled(targetUsername, reason, dueAt);
        if (affected == 0) {
            return scheduleMiss(targetUsername);   // 见方法注释：0 行有三种原因
        }
        result.put("success", true);
        result.put("username", targetUsername);
        result.put("scheduledAt", dueAt.toString());
        return result;
    }

    /**
     * CAS 未命中（{@code markScheduled} 影响 0 行）时的**分诊** —— 补丁 A3。
     * 三种原因必须**读一次状态**才分得开，靠猜会把「已排定」错报成「用户不存在」。
     *
     * <p>⚠️ <b>三个分支一律 {@code success:false}，与 {@code MerchantApplicationService.audit}
     * 的 {@code already-handled}（那里答 {@code success:true}）有意不同</b>：
     * {@code AdminAccountsController.scheduleDowngrade} 在成功分支里**发通知 + 写审计**，
     * 而它只认 {@code success:true} —— 用 {@code false} 就**结构性地**不可能重复发通知
     * （那正是补丁 A4 要的），不必再在 Controller 里加一条 {@code outcome} 白名单
     * （白名单是将来最容易漏的地方）。代价：与 audit 那处不一致，故**写在这里**。
     *
     * <p>{@code outcome} 仍然给，供前端把「已排定」与「已冻结」分开说（前端按它挑文案，
     * 不按 {@code msg} 做字符串匹配）。
     */
    private Map<String, Object> scheduleMiss(String targetUsername) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> row = userGovernanceDao.selectDowngradeRow(targetUsername);
        if (row == null) {
            result.put("success", false);
            result.put("msg", "用户不存在");
            return result;
        }
        String state = str(row.get("DowngradeState"));
        result.put("username", targetUsername);
        result.put("state", state);
        if (DowngradeStates.FROZEN.equals(state)) {
            result.put("success", false);
            result.put("outcome", "already-frozen");
            result.put("msg", "该账号已处于降级冻结状态，无需再次排定");
            return result;
        }
        // 余下：scheduled（同一账号重复点击，A3 的主场景）/ 状态落在两者之外（不猜，按已排定说）
        result.put("success", false);
        result.put("outcome", "already-scheduled");
        result.put("msg", "已发送过降级请求，请于用户治理模块查看");
        return result;
    }

    /** 取消已排定的降级（仅 {@code scheduled} 有意义；对 {@code none} 是幂等空操作）。
     *  ⚠️ <b>补丁 A3 附带（新加的守卫）</b>：对 {@code frozen} 的账号**拒绝** —— 那条路叫
     * 恢复（{@link #relist}），不是撤销。若在这里放行，会把「冻结」这个状态清掉而**商品仍在下架、
     * 角色仍是客户**，留下一个既不冻结也不算正常的半成品态（且没有任何界面能把它捞回来）。
     * ⚠️ 判定用「先读状态再写」而不是 CAS：本操作**不产生通知 / 审计副作用**，
     * 并发下的最坏结果是两个管理员同时点、两次都是空操作，与 CAS 的收益不成比例。 */
    public Map<String, Object> cancel(String targetUsername) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(targetUsername)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        Map<String, Object> row = userGovernanceDao.selectDowngradeRow(targetUsername);
        if (row != null && DowngradeStates.FROZEN.equals(str(row.get("DowngradeState")))) {
            result.put("success", false);
            result.put("outcome", "already-frozen");
            result.put("msg", "该账号已处于降级冻结状态，不能用「撤销排定」—— 请走「人工恢复」");
            return result;
        }
        int affected = userGovernanceDao.clearDowngrade(targetUsername);
        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "用户不存在");
            return result;
        }
        result.put("success", true);
        result.put("username", targetUsername);
        return result;
    }

    // ==================== 阶段 ③：到期执行（定时任务调它） ====================

    /**
     * 执行所有「通知期已过」的降级。**逐条 fail-soft**：一条失败不拖累其余，该条保持
     * {@code scheduled} 等下一轮重试（见 {@link #applyFreeze} 的「顺序不能改」）。
     *
     * <p>⚠️ <b>本方法刻意**不加** {@code @Transactional}</b>：批内一条失败若回滚全部，
     * 就会让「另一条本来能成功的」跟着白做；而逐条自动提交 + 末位翻状态，得到的是
     * **至少一次 + 幂等收敛**，正是定时任务想要的。代价：不保证批的原子性（不需要）。
     *
     * <p>⚠️ 返回的是「这次真执行了哪些商家」—— 调用方（定时任务）据此发通知。
     * 通知**不在这里发**：本方法可能被事务内的调用方复用，而通知与审计都必须在
     * **提交之后**（见 {@code MerchantApplicationService} 的同类论证）。
     *
     * @return 每个元素含 {@code username} / {@code merchantId} / {@code reason} / {@code delisted}
     */
    public List<Map<String, Object>> runDue() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> due = userGovernanceDao.selectDueDowngrades(now);
        List<Map<String, Object>> done = new ArrayList<>();

        for (Map<String, Object> row : due) {
            String username = str(row.get("username"));
            String merchantId = str(row.get("merchantid"));
            String reason = str(row.get("DowngradeReason"));
            if (isBlank(username)) {
                continue;
            }
            try {
                int delisted = applyFreeze(username, merchantId, now);
                Map<String, Object> one = new HashMap<>();
                one.put("username", username);
                one.put("merchantId", merchantId);
                one.put("reason", reason);
                one.put("delisted", delisted);
                done.add(one);
            } catch (Exception e) {
                // 单条失败不拖累其余 —— 下一轮扫描（fixedDelay）会再试这条（DowngradeState 仍是 scheduled）。
                log.warn("降级执行失败（DOWNGRADE）：username={} —— 保持 scheduled，下一轮重试", username, e);
            }
        }
        return done;
    }

    /**
     * 单条冻结：{@code role 2→1} + 商品下架 + 写 {@code frozen} + 清缓存。
     *
     * <p>⚠️ <b>三条写语句的**顺序不能改**：状态翻转（{@code markFrozen}）必须**最后**做</b>。
     * 这样一次中断（部分成功）留下的状态是 {@code DowngradeState='scheduled'} ⇒ 下一轮扫描
     * <b>还会捞到这条</b>、把前两条幂等地重做一遍，最终收敛。反过来先写 {@code frozen}，
     * 中断后的那条就再也进不了扫描集合 —— 会永久停在「角色变了但商品还在架上」的半成品态。
     */
    private int applyFreeze(String username, String merchantId, LocalDateTime now) {
        String effectiveMerchantId = isBlank(merchantId)
                ? userGovernanceDao.selectMerchantId(username) : merchantId;
        String customerId = userGovernanceDao.selectCustomerId(username);

        // 先：降级后进不去商家壳（login.Role 2→1，login.Id 换回 customerid，与业务号口径一致）。
        userGovernanceDao.updateLoginRole(username, ROLE_CUSTOMER,
                isBlank(customerId) ? effectiveMerchantId : customerId);

        // 次：商品全下架（逻辑下架，不删行）。
        int delisted = 0;
        if (!isBlank(effectiveMerchantId)) {
            delisted = goodsShelfDao.delistAllByMerchant(effectiveMerchantId, now);
            userCacheService.evictPublic(effectiveMerchantId);
        }

        // 末：翻状态（见方法注释「顺序不能改」）。
        userGovernanceDao.markFrozen(username, now, now.plusDays(FREEZE_DAYS));
        return delisted;
    }

    // ==================== 阶段 ⑤：人工确认重新上架 ====================

    /**
    /**
     * 管理员**人工确认**恢复上架（阶段 ⑤）。⚠️ 它**只重新上架商品**：
     * 账号身份在「30 天后重新申请通过」那一步已经由
     * {@link MerchantApplicationService#audit} 升回商家了（那一步是 human-in-the-loop 的确认点），
     * 只剩「商品要不要重新上架」这一个残留决定 —— 30 天后默认**不自动**上架，故需要这一下。
     *
     * <p>若账号当前**不在**商家态（{@code login.Role != 2}），本方法仍只上架商品、不改身份
     * —— 「改身份」永远只由审核路径做，避免出现「绕过审核直接变商家」的第二条路。
     *
     * <h2>【D 段 · 阶段 2b（甲）：死锁重试 —— 本方法是全仓唯一那条反向锁序环的受害者侧】</h2>
     * 2026-10-15 实测：本方法（写序 goods→user）与 {@link MerchantApplicationService#audit}
     * （写序 user→goods）**反向**，并发时 MySQL 报 {@code ER_LOCK_DEADLOCK}（48 轮 ×5 请求
     * **48/48 轮复现**、共 149 次；167 条 Spring 异常全落在 relist 这一侧）。
     * 已加 {@code goods.MerchantId} 索引把锁范围从 49 行压到 1 行（**丙**），但**环没拆掉**
     * —— 加索引后重跑仍 48/48 轮、139 次。环的成因是**访问顺序相反**，与锁住几行无关，
     * 故这里补**治果**：重试（**甲**）。
     *
     * <p>⚠️ <b>为什么不用 {@code @Retryable} 注解</b>（用户原话是「Spring Retry 2-3 次」）：
     * 注解是**编译期**的，而本轮要把重试接进 {@code ProtectionGate} 的 {@code retry} 开关 ——
     * 开关关掉时必须**一次都不重试**（演练要演的就是「不重试就 500」这组对照）。
     * 运行时才定的开关，只有编程式 {@code RetryTemplate} 读得到。
     *
     * <p>⚠️ <b>「重试必须在事务外层」是**结构性**保证，不是靠切面 order</b>：本方法上**没有**
     * {@code @Transactional}，事务由内层 {@code transactionTemplate} 显式开 ——
     * {@code RetryTemplate}（外层）与 {@code TransactionTemplate}（内层）分属两层、调的是不同对象，
     * 顺序不可能反。反过来（注解 retry + 注解 tx 同方法）要靠 advisor order 契约成立，
     * 那是「配置对了才对」，不是「结构上不可能错」。
     *
     * <p>⚠️ 两个早退分支（缺参数 / 查无商家号）**在重试之外**：它们属于「输入不对」，
     * 重试一万次也还是不对。
     */
    public Map<String, Object> relist(String targetUsername) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(targetUsername)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        String merchantId = userGovernanceDao.selectMerchantId(targetUsername);
        if (isBlank(merchantId)) {
            result.put("success", false);
            result.put("msg", "该账号没有商家号（从未入驻）");
            return result;
        }
        RetryCallback<Map<String, Object>, CannotAcquireLockException> once =
                ctx -> relistOnce(targetUsername, merchantId, ctx);
        return relistRetry.execute(once);
    }

    /**
     * 一次尝试：显式开一个事务做完三条写操作，并**就本次尝试**发一条 {@code protection} 事件。
     *
     * <p>⚠️ 只有**第 2 次及以后**的尝试才发事件：第 1 次不是「重试」，把它算进去会把
     * 「重试成功率」的分母从「重试过的次数」污染成「调用过的次数」。
     *
     * @param ctx 当前重试上下文；{@code getRetryCount()} 是**已重试次数**（首次尝试时为 0），
     *            故本次的 {@code attempt = 它 + 1}
     * @throws CannotAcquireLockException 本次尝试撞上死锁 —— 刻意不吞，交给外层重试
     */
    private Map<String, Object> relistOnce(String targetUsername, String merchantId, RetryContext ctx) {
        int attempt = ctx.getRetryCount() + 1;
        try {
            Map<String, Object> done = transactionTemplate.execute(status -> {
                int relisted = goodsShelfDao.relistAllByMerchant(merchantId);
                userGovernanceDao.clearDowngrade(targetUsername);
                userCacheService.evictPublic(merchantId);

                Map<String, Object> inner = new HashMap<>();
                inner.put("success", true);
                inner.put("username", targetUsername);
                inner.put("relisted", relisted);
                return inner;
            });
            if (attempt > 1) {
                protectionMetrics.retry(ProtectionMetrics.Result.RETRY_SUCCESS, RETRY_KEY, RELIST_SCOPE,
                        "action=retry attempt=" + attempt + " result=success", null);
            }
            return done;
        } catch (CannotAcquireLockException e) {
            if (attempt > 1) {
                protectionMetrics.retry(ProtectionMetrics.Result.RETRY_FAILED, RETRY_KEY, RELIST_SCOPE,
                        "action=retry attempt=" + attempt + " result=deadlock",
                        "死锁重试失败：第 " + attempt + " 次尝试仍撞上死锁");
            }
            throw e;
        }
    }

    /**
     * 建 {@link #relist} 用的重试模板：**只对死锁类**重试、最多
     * {@value #RELIST_MAX_ATTEMPTS} 次尝试、退避 40ms 起 ×2（上限 200ms）。
     *
     * <p>⚠️ 只把 {@link CannotAcquireLockException} 放进 map：其余异常（参数错 / 查无此商家 /
     * 连接断了）**一次都不重试** —— 重试它们只会把「确定的失败」拖成「慢一点的确定失败」。
     * ⚠️ 为什么认它而不是 {@code DeadlockLoserDataAccessException}：本仓 2026-10-15 的 167 条
     * 死锁现场里后者出现 **0 次**、前者 167 次（MySQL 1213 经 connector 翻成
     * {@code MySQLTransactionRollbackException}，再被 Spring 翻成它）。
     */
    private RetryTemplate buildRelistRetry() {
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(new GatedRetryPolicy(new SimpleRetryPolicy(
                RELIST_MAX_ATTEMPTS, Map.of(CannotAcquireLockException.class, true))));
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(40L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(200L);
        template.setBackOffPolicy(backOff);
        return template;
    }

    /**
     * 「{@code retry}」开关的门：把 {@link ProtectionGate} 接进重试策略（阶段 2b 的「接闸」）。
     *
     * <p>只包一层 {@link RetryPolicy#canRetry(RetryContext)}：开关**关掉** ⇒ 第一次失败之后
     * 不再重试（**不是**「连第一次都不执行」，那一条有个实测过的坑，见 {@link #canRetry}）。
     * 这正是演练要演的那组对照：同一个死锁，开关开着答 200、关着答 500。
     *
     * <p>⚠️ 开关是**每个 key 一个进程内布尔位**，读它只是查表 —— 不会因读不到而阻塞业务线程。
     */
    private final class GatedRetryPolicy implements RetryPolicy {

        private final RetryPolicy delegate;

        private GatedRetryPolicy(RetryPolicy delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean canRetry(RetryContext context) {
            // ⚠️ **第一次尝试无条件放行**（2026-10-15 实测，别删这一段判空）：
            //    RetryTemplate 是「**先问 canRetry、再执行**」的循环，不是「先执行、失败后再问」。
            //    把开关直接短路在 canRetry 上 ⇒ 开关关掉时循环体一次都不进 ⇒ 业务方法**压根没跑**
            //    （实测表现为抛 RetryException("Exception in retry")，且它**没有 cause** ——
            //    原始死锁在异常链上就丢了，页面上只剩一句「Exception in retry」）。
            //    开关管的是**重试**（第 2 次及以后），故只在「已经失败过一次」之后才看它。
            if (context.getLastThrowable() == null) {
                return true;
            }
            return protectionGate.isOn(RETRY_KEY) && delegate.canRetry(context);
        }

        @Override
        public RetryContext open(RetryContext parent) {
            return delegate.open(parent);
        }

        @Override
        public void close(RetryContext context) {
            delegate.close(context);
        }

        @Override
        public void registerThrowable(RetryContext context, Throwable throwable) {
            delegate.registerThrowable(context, throwable);
        }
    }
    // ==================== 内部 ====================

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
