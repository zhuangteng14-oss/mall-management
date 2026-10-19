package com.minke.mallmanagement.observe.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测域的**清理 SQL 唯一出口**（块 7 · commit 8b）—— 只碰两类事：
 * ① 按谓词删「攻击演练污染的业务行」（commit 10 起连带**演练批次的事件台账行**，见
 * {@link #deleteAttackData} 的第三段）；② 按龄删 {@code observed_event} 的过期行。
 *
 * <p>范式照 {@link DrillRecordDao}：{@code @Repository} + 构造器**收 {@code JdbcTemplate}**
 * （让 {@code @WebMvcTest} 切片里既有的 {@code @MockitoBean JdbcTemplate} 继续喂给本类）+
 * 语句以 Java 字符串内联。表名带 {@code mall_management_sql.} 前缀（理由同 {@code DrillRecordDao}）。
 *
 * <h2>【⚠️ 本类是**全仓第一个破坏性 DAO**，所以它把口径写在三个地方】</h2>
 * 其余 DAO 的 DELETE 都是 {@code WHERE Id=?} 或 CAS（一行）；本类的 DELETE 一删就是一批。
 * 三条口径分别写在：谓词怎么定（下面 {@link #deleteAttackData}）、作用面怎么定（同处）、
 * 顺序为什么重要（同处）。**别把任何一条挪去调用方** —— 调用方（Service / Job）
 * 只该决定「什么时候清」，不该决定「清什么」。
 *
 * <h2>【{@code observed_event} 本笔只建表、不接写入】</h2>
 * 它由 8c 的 RabbitMQ consumer 写入（用户 2026-10-14 裁决）。⇒ {@link #pruneObservedEvents}
 * 在本笔跑起来会**稳定删 0 行**（表是空的），这不是缺陷 —— 它是「保留期」这件基础设施的
 * 提前就位，照 {@code ObservedEventCleanupJob} 类注释第二节。
 */
@Repository
public class ObservedEventCleanupDao {

    /**
     * 谓词一：攻击下单链路的**单号前缀**（{@code AttackEngine.ATTACK_PREFIX}）。
     * ⚠️ 与 {@code OrderTimeoutCancelJob.ATTACK_ORDER_PREFIX} **同一字面**（那里是「跳过攻击单」的判据，
     * 这里是「删攻击单」的判据）—— 两处必须一致，一处改了另一处就是漏洞。
     */
    private static final String ATTACK_ORDER_PREFIX = "SIM-";

    /**
     * 谓词二：攻击写入标记（{@code AttackEngine.ATTACK_SOURCE}）。
     * ⚠️ 同 {@code OrderTimeoutCancelJob.ATTACK_SOURCE} / {@code OrderDao} 写入时的字面。
     */
    private static final String ATTACK_SOURCE = "attack";

    /**
     * **排除清单**：即使这些表带了 {@code Source} 列，也**不**参与按标记清理。
     *
     * <p>今天只有一张：{@code drill_record} —— 它记的是「**演练这件事本身**」（谁、什么时候、
     * 跑了什么、拦了多少），不是「演练污染出来的数据」。一次清理如果把演练史也抹了，
     * 「刚才那轮到底跑成什么样」就再也答不出来 ⇒ 它与被清的对象**语义相反**。
     * （今天 {@code drill_record} 连 {@code Source} 列都没有，这条是**防**将来有人给它加上。）
     */
    private static final List<String> NEVER_SWEEP = List.of("drill_record");

    /**
     * **子表优先**顺序：按单号前缀删除时，明细 / 轨迹 / 状态流转**先于**主单 {@code order}。
     *
     * <p>⚠️ 今天全库**没有外键**（本仓口径是软引用，见 {@code db-schema.md §8}）⇒ 顺序在功能上
     * 不影响结果。写它的理由是**将来**：谁哪天给子表补了 {@code ON DELETE RESTRICT} 的外键，
     * 先删主单就会当场报错。这一行是给那一刻留的，不是今天的需要。
     */
    private static final List<String> CHILD_FIRST =
            List.of("order_item", "order_state_transition", "coupon_grant", "order_shipping_track");

    /**
     * 种子 SKU 的**来源标记** —— {@code goods_sku} 没有 {@code Source} 列（Migration ⑰-2 只加了
     * {@code BaselineStock}），所以这一段种子的「是谁写的」只能按码前缀认，同 {@code seed-demo.sql}
     * ⑤-b 段的口径。
     */
    private static final String SEED_SKU_PREFIX = "SEED-%";

    /**
     * 复位后**至少**给到多少库存（2026-10-18 用户批文「如 1000 或种子初始值」）。
     * ⚠️ 优先用人工拍的 {@code BaselineStock}；没有基线时用 1000 —— 但两者都要过一道
     * {@link #MIN_RESET_STOCK}：基线可能是在**上一轮已经打空之后**才拍的 0，
     * 照抄它会让「复位」复位成仍然跑不起来。
     */
    private static final int RESET_STOCK_FLOOR = 1000;

    /**
     * 「可演练」的库存下限 —— **复位后也必须达到它**，否则自检会立刻认为还需要复位。
     * 服务层 {@code AUTO_RESET_STOCK_THRESHOLD} 引用的就是本常量（两处必须同值）。
     */
    public static final int MIN_RESET_STOCK = 100;

    /**
     * 删除批大小（2026-10-19）：每次 {@code DELETE … LIMIT 5000}，批间 {@value #DELETE_PAUSE_MS}ms。
     * ⚠️ 这是「不锁表 / 不让一个事务背十几万行 undo」的取舍：总时长变长，但每批都是短事务，
     * 压测期间也不会把业务写请求挡在行锁后面。
     */
    public static final int DELETE_BATCH_SIZE = 5000;

    private static final long DELETE_PAUSE_MS = 100L;

    private final JdbcClient jdbcClient;

    public ObservedEventCleanupDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== ① 按谓词清「攻击污染的业务行」 ====================

    /**
     * 把两种谓词命中的行**真删掉**（外加一段：演练批次的事件台账行），返回「哪张表删了几行」
     * （只含非零项）。
     *
     * <h2>【谓词（判据来自用户 2026-10-14 批文，两种缺一不可）】</h2>
     * <ul>
     *   <li><b>① {@code OrderId LIKE 'SIM-%'}</b> —— 攻击下单链路。⚠️ 只按它一种会**漏掉**评论链路。</li>
     *   <li><b>② {@code Source='attack'}</b> —— 攻击写入标记。⚠️ 只按它一种会**漏掉**订单链路里
     *       没有被标 Source 的子表（{@code order_item} / {@code order_state_transition} 就没有
     *       {@code Source} 列）。</li>
     * </ul>
     * ⇒ 两种都在，且**各按各能作用的列**下发（对同一张表可能各删一次，计数相加）。
     *
     * <h2>【⚠️ 作用面是**动态发现**的，不是写死一张表清单】</h2>
     * 判据原文是「清完**这两种谓词**的数据全消失」——那这句话的作用面**由列决定**：
     * 有 {@code Source} 列的表就该被谓词 ② 覆盖，有 {@code OrderId} 列的表就该被谓词 ① 覆盖。
     * 写死清单等于把下面的坑留给后人：本仓 {@code Source} 列**还在长**
     * （块 5 给 11 张表加过、块 6 给 6 张表加过、块 7 又给 2 张库存表**刻意不加**）——
     * 将来谁再给第 19 张表加上 {@code Source}，写死的清单会让那上面的攻击数据**永远清不掉**，
     * 而且**没有任何报错**（这正是 TD-20 要抓的静默失败）。⇒ 从 {@code information_schema} 现取。
     *
     * <p>⚠️ <b>实测（2026-10-14，本笔落地前跑的只读 SQL）</b>：19 张表有 {@code Source} 列，
     * 但**只有 2 张**真有 {@code Source='attack'} 的行（{@code comment}=1、{@code order}=3）；
     * 5 张表有 {@code OrderId} 列，**只有 3 张**真有 {@code SIM-} 前缀的行
     * （{@code order}=3、{@code order_item}=3、{@code order_state_transition}=3）。
     * ⇒ 动态发现今天命中{@code coupon_grant} 等空表时删 0 行，代价可忽略。
     */
    public Map<String, Integer> deleteAttackData() {
        Map<String, Integer> deleted = new LinkedHashMap<>();

        // ① 按单号前缀：含 OrderId 列的表，子表优先（理由见 CHILD_FIRST）。
        for (String table : orderByChildFirst(columnsOf("OrderId"))) {
            if (NEVER_SWEEP.contains(table)) {
                continue;
            }
            int n = jdbcClient.sql("DELETE FROM mall_management_sql.`" + table + "` "
                            + "WHERE `OrderId` LIKE ?")
                    .param(ATTACK_ORDER_PREFIX + "%").update();
            if (n > 0) {
                deleted.merge(table, n, Integer::sum);
            }
        }

        // ② 按写入标记：含 Source 列的表。
        for (String table : columnsOf("Source")) {
            if (NEVER_SWEEP.contains(table)) {
                continue;
            }
            int n = jdbcClient.sql("DELETE FROM mall_management_sql.`" + table + "` "
                            + "WHERE `Source` = ?")
                    .param(ATTACK_SOURCE).update();
            if (n > 0) {
                deleted.merge(table, n, Integer::sum);
            }
        }

        // ③ 演练批次的**事件台账**行（块 7 · commit 10：清场按钮清全套）。
        //    ⚠️ 上面两种谓词**一个都碰不到** observed_event：它没有 OrderId 列、也没有 Source 列
        //       ——因为它是事件不是业务行。⇒ 只按谓词清，演练留下的十几万行台账会**原地不动**，
        //       而那是页面「实时日志」那三块最容易看出来的脏数据（BatchId 还在，界面会以为
        //       那一轮还在跑）。
        //    ⚠️ **判据是三条 OR，不是一条**（2026-10-15 块 7 · commit 10c-2，实测后扩）：
        //       只按 `BatchId IS NOT NULL` 会**留下 1742 条**演练副产物 —— 它们由「非请求上下文」
        //       的线程写出（库存扣减 / 审计），拿不到当轮的 BatchId。三类构成（对备份 152184 行实测）：
        //         · 库存事件 1740 条 —— `ObjectType='sku'` 且 ObjectId **全是种子 SKU**（`SEED-%`）：
        //           种子 SKU 只有演练驱动脚本 `restore()` 会去复原，日常没有人动它们；
        //         · 审计 2 条 —— `ObjectType='attack'`，正文是**清场动作自己**的汇总
        //           （「共 30148 行（order_item …）」）⇒ 不清它，**每按一次清场就多一条**，
        //           页面永远留着上一轮的痕迹。
        //       这三条都是**资源域**判据（前缀 / 对象类型），**不含时间窗**：时间窗要另找一个
        //       「本轮是哪一段」的标记，而事件表自己没有；且时间窗判据会随时间推移开始吃真日常证据。
        //    ⚠️ 保留期那个 job（下面的 {@code pruneObservedEvents}）**有意不同步**这三条：
        //       它按龄、语义是「到点清一切」；与「清场 = 现在回到干净态」目的不同，混一起会两边都说不清。
        //    ⚠️ 这条是本类里唯一**不看时间**的整批删：它不参与上面的动态发现，因为
        //       observed_event 是一张固定的表（不是「有某列的表」之一）。
        //    ⚠️ 2026-10-19：这一段改**分批**（`DELETE … ORDER BY Id LIMIT ?` + 100ms 间歇）。
        //       旧写法是一条无界 DELETE：积压十几万行时是一个大事务，清理按钮实测超时；
        //       在压测期间它还会长时间持有行锁 / undo，正是「清理越清越卡」的来源。
        int events = 0;
        while (true) {
            int n = deleteAttackEventsBatch(DELETE_BATCH_SIZE);
            if (n <= 0) {
                break;
            }
            events += n;
            if (n < DELETE_BATCH_SIZE || !pauseBetweenBatches()) {
                break;
            }
        }
        if (events > 0) {
            deleted.put("observed_event", events);
        }
        return deleted;
    }

    /**
     * 删**一批**演练台账行（第 ③ 段的三条 OR），最多 {@code limit} 行。
     * 供 {@link #deleteAttackData()} 与「彻底清空」之外的清理路径复用。
     */
    public int deleteAttackEventsBatch(int limit) {
        Integer n = jdbcClient.sql("DELETE FROM mall_management_sql.observed_event "
                        + "WHERE `BatchId` IS NOT NULL "
                        + "OR (`ObjectType` = 'sku' AND `ObjectId` LIKE 'SEED-%') "
                        + "OR `ObjectType` = 'attack' "
                        + "ORDER BY Id LIMIT ?")
                .param(limit).update();
        return n == null ? 0 : n;
    }

    // ==================== ①-b 复位「演练环境」（2026-10-18 用户批文） ====================

    /**
     * 把**演示种子**被压测改变的三件事复位，返回「哪张表命中了几行」（只含非零项）。
     *
     * <h2>【为什么清场不够】</h2>
     * {@link #deleteAttackData()} 只删**攻击写出来的行**（订单 / 评论 / 台账），但压测还会
     * **改既有种子行的数值**：{@code goods_sku.Stock} 被扣到 0、父 {@code goods} 被下架、
     * 商家被冻结。下一轮 {@code AttackEngine.resolveTarget()} 因此挑不到靶子，
     * 页面只能报「库里挑不到可演练的商品」—— 删行删不回库存，这是两类不同的动作。
     *
     * <h2>【作用面：只碰种子，判据写在 SQL 里】</h2>
     * <ul>
     *   <li>{@code goods_sku}：{@code SkuCode LIKE 'SEED-%'}（该表没有 {@code Source} 列，
     *       前缀就是它的来源标记，同 {@code seed-demo.sql} ⑤-b）；</li>
     *   <li>{@code goods}：{@code Source='seed'} <b>且</b>是种子 SKU 的父商品
     *       （不碰 {@code Source='seed'} 但不在演练靶子里的商品 —— 那些商品的上下架
     *       可能是演示脚本故意摆的状态）；</li>
     *   <li>{@code user}：{@code Source='seed'} 且是种子 SKU 所属商家；</li>
     *   <li>{@code seckill_activity}：父商品是 {@code Source='seed'} 的活动。</li>
     * </ul>
     * ⇒ 真实业务行（{@code Source} 为空的存量数据 / 真实商家）**一个字节都不动**。
     *
     * <h2>【库存取什么值】</h2>
     * {@code GREATEST(COALESCE(BaselineStock, 1000), 100)}：
     * 优先人工拍的基线，没有基线给 1000（用户批文点名的值），两者都不低于
     * {@link #MIN_RESET_STOCK} —— 理由见 {@link #RESET_STOCK_FLOOR}。
     * 秒杀侧同款：{@code SoldStock = COALESCE(BaselineSoldStock, 0)}。
     *
     * <p>⚠️ 本方法**不删任何行**，也不碰 {@code observed_event}（复位是写业务表，不是写台账）；
     * 调用方可以先 {@link #deleteAttackData()} 再复位，两件事互不依赖。
     */
    public Map<String, Integer> restoreSeedEnvironment() {
        Map<String, Integer> restored = new LinkedHashMap<>();

        int skus = jdbcClient.sql(
                        "UPDATE mall_management_sql.goods_sku "
                                + "SET Stock = GREATEST(COALESCE(BaselineStock, ?), ?) "
                                + "WHERE SkuCode LIKE ?")
                .params(RESET_STOCK_FLOOR, MIN_RESET_STOCK, SEED_SKU_PREFIX).update();
        if (skus > 0) {
            restored.put("goods_sku", skus);
        }

        int goods = jdbcClient.sql(
                        "UPDATE mall_management_sql.goods g "
                                + "SET g.ShelfState = 1, g.DelistedAt = NULL, g.DeletedAt = NULL "
                                + "WHERE g.Source = 'seed' AND g.Id IN ("
                                + "SELECT GoodsId FROM mall_management_sql.goods_sku WHERE SkuCode LIKE ?)")
                .param(SEED_SKU_PREFIX).update();
        if (goods > 0) {
            restored.put("goods", goods);
        }

        int merchants = jdbcClient.sql(
                        "UPDATE mall_management_sql.`user` u "
                                + "SET u.DowngradeState = 'none', u.ScheduledDowngradeAt = NULL, "
                                + "u.DowngradeReason = NULL, u.FrozenAt = NULL, u.FreezeUntil = NULL "
                                + "WHERE u.Source = 'seed' AND u.merchantid IN ("
                                + "SELECT DISTINCT MerchantId FROM mall_management_sql.goods_sku "
                                + "WHERE SkuCode LIKE ?)")
                .param(SEED_SKU_PREFIX).update();
        if (merchants > 0) {
            restored.put("user", merchants);
        }

        int seckill = jdbcClient.sql(
                        "UPDATE mall_management_sql.seckill_activity sa "
                                + "JOIN mall_management_sql.goods g ON g.Id = sa.GoodsId "
                                + "SET sa.SoldStock = COALESCE(sa.BaselineSoldStock, 0) "
                                + "WHERE g.Source = 'seed'").update();
        if (seckill > 0) {
            restored.put("seckill_activity", seckill);
        }
        return restored;
    }

    // ==================== ② 按龄清「事件落库台账」 ====================

    /**
     * 删 {@code observed_event} 里超过保留期的行，返回**删掉的行数**。
     *
     * <h2>【两档保留期，判据是「这一行属不属于某次演练」而不是「表里哪一列」】</h2>
     * <ul>
     *   <li><b>攻击批次</b>（{@code BatchId IS NOT NULL}）：{@code attackRetentionDays} 天
     *       —— 演练数据是**演示用的**，留着只为了事后回看一轮，短。</li>
     *   <li><b>日常事件</b>（{@code BatchId IS NULL}）：{@code normalRetentionDays} 天
     *       —— 真实流量的事件是**运维证据**，长。</li>
     * </ul>
     * ⚠️ 两档合起来才是「保留期」：只写一档会让另一档**永不清理**（表无限长）。
     *
     * <p>⚠️ <b>按 {@code OccurredAt} 算，不按 {@code created_at}</b>：这两个值在本笔的写入路径里
     * 几乎相等（consumer 收到即落），但它们的**语义**不同 —— {@code OccurredAt} 是「事件发生在何时」
     * （对齐 {@code ObservedEvent.ts}），{@code created_at} 是「这行何时落库」。
     * 保留期问的是「多久之前的事我不要了」⇒ 用前者。队列积压时两者会拉开，那时口径必须已经是对的。
     *
     * <p>本条用**一条语句删两档**（{@code OR}）：两条语句会让「一条成功一条失败」变成一个
     * 无信息量的中间态，而这里没有任何理由需要它。{@code INTERVAL ? DAY} 的两个占位符都走绑定，
     * 不拼字符串。
     *
     * <p>⚠️ 2026-10-19：本方法改为**循环分批**（每批 {@value #DELETE_BATCH_SIZE} 行 + 100ms 间歇，
     * 见 {@link #deleteExpiredBatch}）。返回的是**本次调用删掉的总行数**；调用方（job / 端点）
     * 拿到的语义没变，变的只是它不再是一个大事务。
     */
    public int pruneObservedEvents(int attackRetentionDays, int normalRetentionDays) {
        int total = 0;
        while (true) {
            int n = deleteExpiredBatch(attackRetentionDays, normalRetentionDays, DELETE_BATCH_SIZE);
            if (n <= 0) {
                break;
            }
            total += n;
            if (n < DELETE_BATCH_SIZE || !pauseBetweenBatches()) {
                break;
            }
        }
        return total;
    }

    /**
     * 删**一批**过期行（最多 {@code limit} 行），返回本批行数。
     *
     * <p>⚠️ 2026-10-19：从「一条无界 DELETE」拆成「一批 + {@code ORDER BY Id LIMIT ?}」。
     * 两档判据（{@code BatchId} 非 NULL / NULL）一个字没改 —— 变的只是每批的边界；
     * 全程仍是同一条 OR 语句，不拆成两条（拆开会留下「一条成一条败」的中间态）。
     * {@code ORDER BY Id} 让最老的行先走，也避免每批都从头扫。
     */
    public int deleteExpiredBatch(int attackRetentionDays, int normalRetentionDays, int limit) {
        Integer n = jdbcClient.sql("DELETE FROM mall_management_sql.observed_event "
                        + "WHERE (`BatchId` IS NOT NULL AND `OccurredAt` < NOW() - INTERVAL ? DAY) "
                        + "   OR (`BatchId` IS NULL AND `OccurredAt` < NOW() - INTERVAL ? DAY) "
                        + "ORDER BY Id LIMIT ?")
                .params(attackRetentionDays, normalRetentionDays, limit).update();
        return n == null ? 0 : n;
    }

    /**
     * 按 {@code Id}（≈ 时间）删最老的 {@code limit} 行 —— **容量水位熔断**的落点
     * （2026-10-19：超过高水位时「牺牲历史，保全当下」）。
     *
     * <p>⚠️ 不按 {@code OccurredAt} 排序的理由：{@code Id} 是聚簇主键，{@code ORDER BY Id LIMIT}
     * 直接沿主键顺序删，代价与「删多少」成正比；按二级索引排序会多一次回表/排序。
     * 两者的时间序在写入路径上一致（消费者按发生顺序批写），差一秒不改变「淘汰最老」的语义。
     */
    public int deleteOldestBatch(int limit) {
        Integer n = jdbcClient.sql("DELETE FROM mall_management_sql.observed_event "
                        + "ORDER BY Id LIMIT ?")
                .param(limit).update();
        return n == null ? 0 : n;
    }

    /**
     * {@code observed_event} 当前行数（给清理结果做「清前 / 清后」的读数，不埋点：它是行数不是事件）。
     *
     * <p>⚠️ 用 {@code list()} 取首行而**不是** {@code single()}：真 MySQL 上 {@code COUNT(*)}
     * 恒回一行，但 {@code @WebMvcTest} / smoke 切片的替身 {@code JdbcTemplate} 对未桩的查询
     * 返回空表 ⇒ {@code single()} 会抛 {@code EmptyResultDataAccessException}，把一次
     * 「水位读数没桩」升级成整个 {@code /run} 失败。空表按 0 处理，与「表是空的」同义。
     */
    public long countObservedEvents() {
        List<Long> rows = jdbcClient.sql("SELECT COUNT(*) FROM mall_management_sql.observed_event")
                .query(Long.class).list();
        return rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
    }

    // ==================== 内部 ====================

    /**
     * 批间让出一拍（2026-10-19）。返回 {@code false} = 线程被中断，调用方应停止后续批次。
     * ⚠️ 中断标志**必须还原**（{@link Thread#interrupt()}）—— 吞掉它会让上层（job / 请求线程）
     * 失去「该收尾了」的信号。
     */
    private static boolean pauseBetweenBatches() {
        try {
            Thread.sleep(DELETE_PAUSE_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 现查「本库里有这一列的表」。
     *
     * <p>⚠️ 用 {@code TABLE_SCHEMA = DATABASE()}（不是写死库名）：{@code .sql} 迁移段里用的也是这个口径，
     * 而连接串里的默认库就是 {@code mall_management_sql} ⇒ 两边同源。
     * ⚠️ 结果**按表名排序** ⇒ 每次调用顺序一致（清理日志可比对）。
     */
    private List<String> columnsOf(String columnName) {
        return jdbcClient.sql("SELECT TABLE_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = ? ORDER BY TABLE_NAME")
                .param(columnName).query(String.class).list();
    }

    /** 子表优先排序（理由见 {@link #CHILD_FIRST}）：清单内的按清单序在前，其余按表名在后。 */
    private static List<String> orderByChildFirst(List<String> tables) {
        List<String> out = new ArrayList<>();
        for (String t : CHILD_FIRST) {
            if (tables.contains(t)) {
                out.add(t);
            }
        }
        for (String t : tables) {
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
