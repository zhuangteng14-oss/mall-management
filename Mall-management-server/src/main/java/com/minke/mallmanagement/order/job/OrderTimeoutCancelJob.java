package com.minke.mallmanagement.order.job;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics.Release;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.dao.OrderDataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 订单超时未付款自动取消任务（C3 块4 补丁 · 第 2 笔）—— 按「创建时刻 + 阈值」把未付款的单置为已取消。
 *
 * <h2>【职责：给<b>未付款</b>的单写 {@code order.CancelledAt}；块 7 起<b>兼</b>回补库存】</h2>
 * 本类**不改 {@code PayIf}**（改了就与「已付款」混同）、不删行、不发通知。
 * {@code CancelledAt} 是「第 4 态」的**唯一**载体 —— 取消的单三位仍是 {@code (0,0,0)}，
 * 与「未付款」**完全相同**，故前端必须**先判 {@code CancelledAt != null}**、再落到三位表上。
 *
 * <p>⚠️ <b>块 7（2026-10-13）动了本类原有的职责边界</b>：原文写的是「本类<b>不做别的</b>」——
 * 现在**多一件**：取消成功的当口顺带回补 {@code goods_sku.Stock}（C3 块 7 · 库存治理 TD-36，
 * 《块 7 参考》§5.2 B5 丙的**第三拍：超时释放** / §5.3 C5 甲「复用本类、<b>不新加</b>
 * {@code StockReleaseJob}」）。回补排在下文 {@code cancelIfUnpaid == 1} 分支**之内** ——
 * 只有「本轮真的把这单取消了」才回补，扫到但没取消（他处已取消 / 已付款）的那类**不碰库存**。
 *
 * <h2>【⚠️ 阈值：演示默认 60 秒，生产改配置】</h2>
 * <table border="1">
 *   <tr><th>项</th><th>默认</th><th>阈值 key</th></tr>
 *   <tr><td>扫描间隔</td><td>15 秒</td><td>{@code fixed-delay-ms}（15000）</td></tr>
 *   <tr><td>超时阈值</td><td>60 秒</td><td>{@code timeout-ms}（60000），生产口径 30 分钟改这里</td></tr>
 * </table>
 * ⚠️ 用户 2026-10-10 裁「批全档」时一并把演示节奏定成「间隔 15 秒」：最坏等待 = 阈值 + 一个 tick。
 *
 * <h2>【⚠️ 时刻：本类不构造任何时间点，全部由库时钟算】</h2>
 * 与 {@link ShippingTrackAdvanceJob} 同款口径（2026-10-10 时区统一）。候选判据是
 * {@code CreateTime < DATE_FORMAT(DATE_SUB(NOW(), INTERVAL n SECOND), …)}、写入是 {@code NOW(3)}，
 * **整条路上没有任何 Java 时间对象**（改前那种 {@code System.currentTimeMillis()} +
 * {@code new Timestamp} 会在 {@code serverTimezone=Asia/Shanghai} 下掺进一次折算 ⇒ 落库 ±8 小时）。
 *
 * <h2>【幂等与并发：靠 {@code OrderDao.cancelIfUnpaid} 的 CAS】</h2>
 * 候选查询带 {@code CancelledAt IS NULL}，写入是
 * {@code UPDATE … WHERE OrderId=? AND PayIf=0 AND CancelledAt IS NULL} ⇒
 * 同一张单被反复扫到也只会取消一次（{@code rows == 0} 即跳过）。
 * ⚠️ <b>反向的竞态由付款那条 UPDATE 兜</b>：取消**不改</b> {@code PayIf}，
 * 故「已取消的单被付款」光靠付款守卫拦不住 —— 那一半在
 * {@code OrderStateMachine.updateSql(PAY)} 尾部的 {@code AND CancelledAt IS NULL}。两条各管一边。
 *
 * <h2>【⚠️ 与 {@code MerchantDowngradeJob} / {@code ShippingTrackAdvanceJob} 同族：整类挂 {@code @ConditionalOnProperty}】</h2>
 * A3 是并发压测，要求「同一份输入跑出同一份结果」；一个每 15 秒自己醒来写库的后台线程会注入
 * 与压测无关的变量 ⇒ a3 profile 显式置 false。默认（开发机）保持开启（{@code matchIfMissing=true}）。
 * <p>⚠️ <b>调度器总闸不在本类手里</b>（{@code @EnableScheduling} 挂在
 * {@code MerchantDowngradeSchedulingConfig}、由 {@code mall.downgrade.job.enabled} 把关）
 * ⇒ <b>a3 下调度器根本不存在</b>，本类即便 flag 忘了关也不会跑。要看超时取消必须用**默认 profile**。
 *
 * <h2>【块 7 · commit B（②）：跳过「演练带标记的行」—— 默认关闭】</h2>
 * 《块 7 参考》§1.4 的 C2 甲：「攻击数据带标记（如 {@code OrderId} 前缀 {@code SIM-}），
 * job 扫描时**跳过带标记的行**」。落地为 {@link #skipAttackData}
 * （{@code mall.order.cancel.skip-attack-data}，**dev 默认 {@code false}**）+ {@link #isAttackData}。
 *
 * <table border="1">
 *   <tr><th>开关</th><th>演练造的 {@code SIM-} 未付款单</th><th>普通未付款单</th></tr>
 *   <tr><td>{@code false}（缺省）</td><td>照取消（= 加开关前行为）</td><td>照取消</td></tr>
 *   <tr><td>{@code true}</td><td><b>跳过</b>，不取消、不回补库存</td><td>照取消</td></tr>
 * </table>
 *
 * <p><b>判据（用户逐字给的验收口径）</b>：{@code false} ⇒ {@code SIM-} 未付款单 **60 秒后真被取消**；
 * {@code true} ⇒ **60 秒后仍在**（{@code CancelledAt} 仍为 {@code NULL}、{@code PayIf} 仍 0）。
 *
 * <p>⚠️ <b>标记有两个载体，谓词两半都认</b>（{@link #isAttackData}）：
 * ①{@code OrderId} 前缀 {@link OrderDataScope#ATTACK_ORDER_PREFIX}（{@code SIM-}）；
 * ②{@code order.Source} 列 == {@link OrderDataScope#ATTACK_SOURCE}（{@code attack}）。
 * <p>⚠️ <b>边界已由「边界补笔」（2026-10-14）消除</b>：本条①（{@code Source='attack'}）此前
 * **没有任何写入方** —— {@code order.Source} 列虽在（块 5 建表，{@code varchar(16) NOT NULL DEFAULT ''}），
 * 但从没有路径往里写 {@code 'attack'}（{@code seed-demo.sql} 写的是 {@code 'seed'}）⇒ 谓词里
 * {@code Source='attack'} 那一半**匹配不到行**。补笔给订单域补上了写入通道
 * （{@code /order/orderAdd} 的可选 {@code Source} 入参 + {@code OrderDao.insertOrder} 写该列 +
 * {@code AttackEngine} 的四个下单场景传 {@code 'attack'}）⇒ 这一半**现在有真实数据可命中**。
 * ⚠️ 两半**照样都留着**（不是二选一）：前缀扛「人肉看得见 / {@code LIKE 'SIM-%'} 一把清」，
 * 列扛「结构化可查」—— 删掉任何一半都是把参考文的口径砍半。
 */
@Component
@ConditionalOnProperty(name = "mall.order.cancel.enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeoutCancelJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutCancelJob.class);

    private final OrderDao orderDao;

    /**
     * 块 7 起注进来的第二个 DAO（{@code c3-block-7-observe.md} §超时释放挂点「构造函数要加注两个 DAO」）：
     * 取消成功时回补 {@code goods_sku.Stock} —— TD-36 的释放腿（{@link #releaseStock}）。
     */
    private final GoodsDao goodsDao;

    /**
     * 库存面的观测出口（C3 块 7 · 可观测域 · commit 2）：回补的每条明细各记一次 ——
     * 回没回上、回了几件（{@code mall.stock.release} + 一条事件）。
     *
     * <p>⚠️ <b>只记事实、不定结论</b>：入参是枚举（不是 throwable），DAO 异常在上面的
     * {@code releaseStock} 里先被接住 ⇒ 埋点不会改变「取消已落库」这个既成事实，
     * 也不会把异常带出逐条循环。
     */
    private final StockMetrics stockMetrics;

    /** 超时阈值（创建后多久未付款即取消）。**演示默认 60 秒**；生产口径 30 分钟改这里。 */
    @Value("${mall.order.cancel.timeout-ms:60000}")
    private long timeoutMs;

    /**
     * 是否跳过「演练带标记的行」（C3 块 7 · commit B，2026-10-14；参考文 §1.4 的
     * C2 甲「攻击数据带标记，job 扫描时跳过带标记的行」）。
     *
     * <p>⚠️ <b>{@code false}（缺省）= 与加这个开关之前**逐字节同行为**</b>：所有未付款超时的单
     * 一律照取消（含演练造的 {@code SIM-} 单）。{@code true} ⇒ 带标记的候选**在这一轮直接跳过**，
     * 不取消、也不回补库存。
     *
     * <p>⚠️ <b>为什么缺省是 false（而不是 true）</b>：本类的主职责是「超时未付款就取消」，
     * {@code true} 会让这个职责对有标记的单**不成立**。参考文要的是「**一半数据被 job 拦截、
     * 一半跳过 job 注入**」这个**对照**（看 job 生效 vs 看其它防护怎么办），
     * 对照要手工开 —— 故它是个**演示开关**，不是新常态。
     *
     * <p>⚠️ <b>与 {@code mall.order.cancel.enabled} 不是一回事</b>：那一个是「有没有这个 job」
     * （整类 {@code @ConditionalOnProperty}），本开关是「这个 job 挑不挑食」。两个都关才是
     * 「什么都不发生」。
     */
    @Value("${mall.order.cancel.skip-attack-data:false}")
    private boolean skipAttackData;

    public OrderTimeoutCancelJob(OrderDao orderDao, GoodsDao goodsDao, StockMetrics stockMetrics) {
        this.orderDao = orderDao;
        this.goodsDao = goodsDao;
        this.stockMetrics = stockMetrics;
    }

    /**
     * 扫描 + 取消。整方法 catch 一切（同 {@code ShippingTrackAdvanceJob.advance}：
     * 一个定时任务抛异常会让 Spring 把后续调度一起停掉）。单条失败只跳过该条，不拖垮整轮。
     */
    @Scheduled(fixedDelayString = "${mall.order.cancel.fixed-delay-ms:15000}")
    public void advance() {
        long timeoutSeconds = timeoutMs / 1000L;
        if (timeoutSeconds <= 0) {
            // 阈值 <= 0 会让「刚下的单立刻被取消」——那是配置事故，不是业务行为。宁可整轮不动。
            log.warn("超时取消：阈值 {} ms 非正，本轮跳过（检查 mall.order.cancel.timeout-ms）", timeoutMs);
            return;
        }
        List<Map<String, Object>> candidates;
        try {
            candidates = orderDao.selectUnpaidExpired(timeoutSeconds);
        } catch (Exception e) {
            log.warn("超时取消：查候选整轮失败 —— 下一轮重试", e);
            return;
        }
        for (Map<String, Object> one : candidates) {
            String orderId = String.valueOf(one.get("OrderId"));
            String source = asString(one.get("Source"));
            try {
                // 块 7 · commit B（② /《块 7 参考》§1.4 C2 甲）：带演练标记的候选**整条不进本轮处置**。
                // ⚠️ 跳过点**必须排在 cancelIfUnpaid 之前**：那是唯一的写点，排到后面「取消」这个副作用
                //    已经发生（CancelledAt 已写、库存已回补），再判就没意义了。
                // ⚠️ 用 continue 而**不是 return**：跳过的只是**这一条**，同轮其余正常单照取消。
                // ⚠️ 开关为 false 时这一行的结果与加它之前**逐字节同行为**（谓词都不求值）。
                if (skipAttackData && isAttackData(orderId, source)) {
                    log.info("超时取消：订单 {} 带演练标记（Source={}），按 skip-attack-data=true 跳过（不取消、不回补）",
                            orderId, source);
                    continue;
                }
                if (orderDao.cancelIfUnpaid(orderId) == 1) {
                    log.info("超时取消：订单 {} 超过 {} 秒未付款，已置 CancelledAt", orderId, timeoutSeconds);
                    // 块 7 · 库存治理 TD-36 第三拍：**取消成功**才回补。
                    // ⚠️ 放进这个 try 只是**位置**上顺理成章（取消与回补同属「本单收尾」）；失败隔离
                    //    在 releaseStock 自己的逐条 try/catch 里 —— 回补失败**不回滚取消**（已落库，
                    //    且超时取消本就不该因库存回补失败而放弃）。
                    releaseStock(orderId);
                }
            } catch (Exception e) {
                log.warn("超时取消：订单 {} 失败（跳过，下一轮重试）：{}", orderId, e.toString());
            }
        }
    }

    /**
     * <b>超时释放库存</b>（C3 块 7 · 库存治理 TD-36 的**第三拍**，《块 7 参考》§5.2 B5 丙 / §5.3 C5 甲）。
     *
     * <p>只在本轮 <b>真的把这单取消了</b>（{@code cancelIfUnpaid == 1}）之后被调用 ——
     * 与 {@code OrderService.deductStocks} 对称：**下单即扣 ⇒ 超时即回**。
     *
     * <p>按 `order_item` 逐条回补：{@link OrderDao#selectItemStocksByOrder} 取本单的
     * {@code (ProductCode, Quantity)}，再 {@link GoodsDao#releaseStock} 加回。**不另解析一遍明细**
     * ——扣谁回谁本来就该是同一批行。
     *
     * <p>⚠️ <b>前置判据（块 7 · commit 2d · 用户 2026-10-13 批 A 档第 4 项）</b>：本方法**不再无条件回补** ——
     * 先读 {@code order.StockDeducted}（{@link OrderDao#selectStockDeducted}），**只有 1 才继续**。
     * 该列由 {@code OrderService.markStockDeducted} 在「本轮 CAS 真的扣成过 ≥1 行」时写入
     * （Migration ⑱；它是「本单库存被动过」的**唯一**载体）。
     *
     * <p>⚠️ <b>它修掉的正是 2d 之前那个偏账</b>：那时 {@link GoodsDao#releaseStock} 无条件加回，
     * 「下单时库存不足 ⇒ 扣减 0 行、但单照样落库」的单超时取消时会被**凭空补出一份库存**。
     * ⚠️ 存量单（2d 之前落的）这一列默认 0 = 「没扣过」⇒ 一律不回补 —— 这正是**要的口径**：
     * 那些单走的本来就是 2d 之前的下单路径，无从回判，宁可不补也不虚增。
     *
     * <p>⚠️ <b>读不出来时**不回补**（fail-closed）</b>：与 {@code OrderService.deductStocks} 的
     * fail-open 刻意相反 —— 偏账方向选「少还一件」而不是「凭空补一件」。⚠️ 剩余边界：精度到
     * **单**不到**行** —— 混合单（一行真扣成、一行当时库存不足）只要有一行扣成过就整单回补；
     * 要根治得把标记下沉到 {@code order_item} 粒度（新列 + 新契约），本笔不动。
     *
     * <p>⚠️ <b>逐条 try/catch</b>：一条 SKU 回补失败不拖垮整轮，也不与其他条互相牵连
     * （同 {@code advance} 的单条守卫）。明细读不出来则**整单不回补**并留 WARN —— 宁可偏账留痕，
     * 也不在「明细未知」时瞎猜数量。
     *
     * <p>⚠️ <b>本方法同时是库存环（{@code StockMetrics}）释放腿的唯一写入点</b>（块 7 · commit 2）：
     * 每条明细各记一次 {@code mall.stock.release} + 一条事件。埋点是**观察者** ——
     * 0 行记 {@code MISSING}（无处可放）、异常记 {@code ERROR}，两者都不改变本方法的控制流。
     */
    private void releaseStock(String orderId) {
        // 块 7 · commit 2d：**只有当初真的扣成过库存的单才回补**（order.StockDeducted = 1）。
        int deducted;
        try {
            deducted = orderDao.selectStockDeducted(orderId);
        } catch (Exception e) {
            // 读不出来 ⇒ 不回补（fail-closed）：宁可漏回（偏账 = 少还一件），也不在
            // 「这单扣没扣过未知」时凭空补一份出来。异常被吞在这里，取消是既成事实、不回滚。
            log.warn("超时释放：订单 {} 的扣减标记读取失败（保守起见本轮不回补）：{}", orderId, e.toString());
            return;
        }
        if (deducted != 1) {
            // 0 = 当初 CAS 一行没命中（库存不足 / 非矩阵商品无 goods_sku 行）/ 存量单；查无此行也回落 0。
            log.info("超时释放：订单 {} 当初未真正扣减库存（StockDeducted={}），跳过回补", orderId, deducted);
            return;
        }
        List<Map<String, Object>> items;
        try {
            items = orderDao.selectItemStocksByOrder(orderId);
        } catch (Exception e) {
            log.warn("超时释放：订单 {} 的明细读取失败（本轮不回补）：{}", orderId, e.toString());
            return;
        }
        for (Map<String, Object> one : items) {
            String skuCode = asString(one.get("ProductCode"));
            int qty = asInt(one.get("Quantity"));
            if (skuCode == null || skuCode.isBlank() || qty < 1) {
                // 空 code / 非正数量 = 这行明细没装可回补的量（同 deductStocks 的跳过口径）。
                continue;
            }
            // 块 7 · 阶段 3a.3：耗时紧包那一行回补 SQL（与 deductStocks 同一口径 (a)）。
            // ⚠️ 声明在 try 外：catch 里也要用（编译期作用域）；本任务跑在定时线程 ⇒ actor 为 null，耗时照记
            long releaseStartedNanos = System.nanoTime();
            try {
                int rows = goodsDao.releaseStock(skuCode, qty);
                long releaseNanos = System.nanoTime() - releaseStartedNanos;
                // 块 7 · 观测（commit 2）：与 OrderService.deductStocks 对称，两条通道同一调用点一起发。
                // 0 行记 MISSING（该码在 goods_sku 里没有行 ⇒ 无处可放），**不是** ERROR ——
                // 非矩阵商品本来就没有 goods_sku 行，这类明细每单都会走到这里。
                // 块 7 · 阶段 3a.3 后续：0 行的人话原因 ——「无处可放」是事实，
                // 与「回补失败」分开（result=missing / error 已分开，reason 把话说全）。
                stockMetrics.release(skuCode, qty,
                        rows == 1 ? Release.OK : Release.MISSING, releaseNanos,
                        rows == 1 ? null : "释放未命中：goods_sku 中不存在该 SKU");
            } catch (Exception e) {
                stockMetrics.release(skuCode, qty, Release.ERROR, System.nanoTime() - releaseStartedNanos,
                        com.minke.mallmanagement.shared.text.EventReason.of("库存释放失败", e));
                log.warn("超时释放：订单 {} 的 SKU {} 回补失败（跳过该条）：{}", orderId, skuCode, e.toString());
            }
        }
    }

    /**
     * 「这行是不是演练造的」的**唯一判据**（C3 块 7 · commit B · ②）。两个载体**任一**命中即算：
     *
     * <ol>
     *   <li>{@code OrderId} 以 {@link OrderDataScope#ATTACK_ORDER_PREFIX}（{@code SIM-}）开头 ——
     *       演练造单时贴的业务串前缀；</li>
     *   <li>{@code Source} 列等于 {@link OrderDataScope#ATTACK_SOURCE}（{@code attack}，
     *       忽略大小写、前后空白已去）。</li>
     * </ol>
     *
     * <p>⚠️ 判据的**唯一源**已搬到 {@link OrderDataScope}（2026-10-17）：状态流转页的
     * 「剔除已取消 / 仅看真实数据」开关要用同一条。本方法保留成薄壳，单测与既有调用点不漂移。
     */
    static boolean isAttackData(String orderId, String source) {
        return OrderDataScope.isAttackData(orderId, source);
    }

    /** {@code ResultSet} 里列的取值：{@code null} 原样返回，其余 {@code String.valueOf}（char 列已去尾空格）。 */
    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** {@code Quantity} 的取值：JDBC 可能给 Integer / Long / BigInteger ⇒ 统一 {@code intValue}；认不出当 0（= 跳过）。 */
    private static int asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
