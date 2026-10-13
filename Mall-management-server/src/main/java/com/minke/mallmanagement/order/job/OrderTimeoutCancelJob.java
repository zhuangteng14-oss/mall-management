package com.minke.mallmanagement.order.job;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics.Release;
import com.minke.mallmanagement.order.dao.OrderDao;
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
            try {
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
     * <p>⚠️ <b>已知边界（本次核对新增 · 用户 2026-10-13 已批 A 档第 4 项）</b>：
     * {@link GoodsDao#releaseStock} 是**无条件加回**，它无从知道这单当初 {@link GoodsDao#deductStock}
     * 是否真的扣成过。于是「下单时库存不足 ⇒ 扣减 0 行、但单照样落库」这种单，超时取消时会被
     * **凭空补出一份库存**（偏账）。取口径：演示用 {@code goods_sku.BaselineStock} 复位兜住
     * （《块 7 参考》§4 的 4 步）。要根治得记「本单扣减是否成功」—— 那是**新契约**，须另批，本笔不动。
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
            try {
                int rows = goodsDao.releaseStock(skuCode, qty);
                // 块 7 · 观测（commit 2）：与 OrderService.deductStocks 对称，两条通道同一调用点一起发。
                // 0 行记 MISSING（该码在 goods_sku 里没有行 ⇒ 无处可放），**不是** ERROR ——
                // 非矩阵商品本来就没有 goods_sku 行，这类明细每单都会走到这里。
                stockMetrics.release(skuCode, qty,
                        rows == 1 ? Release.OK : Release.MISSING);
            } catch (Exception e) {
                stockMetrics.release(skuCode, qty, Release.ERROR);
                log.warn("超时释放：订单 {} 的 SKU {} 回补失败（跳过该条）：{}", orderId, skuCode, e.toString());
            }
        }
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
