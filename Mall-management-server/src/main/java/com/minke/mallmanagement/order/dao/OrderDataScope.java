package com.minke.mallmanagement.order.dao;

/**
 * 订单域「演练数据」判据的**唯一**出处。
 *
 * <h2>【两个载体，两半都认】</h2>
 * <ul>
 *   <li>{@code OrderId LIKE 'SIM-%'} —— 攻击引擎下单链路写的业务串前缀
 *       （{@code AttackEngine.ATTACK_PREFIX} 的逐字同源值）；</li>
 *   <li>{@code Source = 'attack'} —— 结构化写入标记（{@code order.Source} 列，C3 甲三档
 *       {@code seed / real / attack}）。</li>
 * </ul>
 * ⚠️ 只认前缀会漏掉「人肉把单号改成别的形状」的行；只认列会漏掉列出现之前的历史演练单。
 * 两半**都写**、不是二选一 —— 这也是超时取消 job 与清场端点一直用的口径。
 *
 * <h2>【为什么从 job 里搬到这里】</h2>
 * 2026-10-17 状态流转页的「剔除已取消 / 仅看真实数据」开关要用**同一条**判据。
 * 若 DAO 再抄一份 SQL、job 留一份 Java 谓词，两边迟早走岔（旧注释已经反复警告过一次）。
 * 本类把「前缀 / 来源 / SQL 片段 / Java 谓词」收在一处：job 和状态流转页都从这里取。
 *
 * <p>⚠️ 本类**不依赖** {@code observe} 域（订单域不该反向依赖演练域），字面量在这里是**源**，
 * {@code AttackEngine} 那边仍是它自己的源；两处各有反向 ⚠️ 互相提醒。
 */
public final class OrderDataScope {

    /** 攻击引擎下单链路的单号前缀（与 {@code AttackEngine.ATTACK_PREFIX} 逐字一致）。 */
    public static final String ATTACK_ORDER_PREFIX = "SIM-";

    /** 演练写入标记（与 {@code AttackEngine.ATTACK_SOURCE} 逐字一致）。 */
    public static final String ATTACK_SOURCE = "attack";

    /**
     * SQL：排除演练数据。⚠️ 调用方自己拼 {@code WHERE} / {@code AND}，本常量**不带**前导谓词。
     * {@code Source} 列在 {@code order} 表上恒存在（Migration ⑱ 之后）；历史行默认空串。
     */
    public static final String NOT_ATTACK_SQL =
            "NOT (OrderId LIKE '" + ATTACK_ORDER_PREFIX + "%' OR Source = '" + ATTACK_SOURCE + "')";

    /**
     * SQL：「健康业务分布」——排除演练数据**且**排除已取消单。
     *
     * <p>为什么要连取消一起排：压测的单大量在 60 秒后被超时 job 取消，环形图会被
     * 「已取消」压到 99%，其它状态挤成一条线。开关打开时用户要的是「正常在途/完成」的
     * 业务形状，而不是「演练真的造了很多废单」这件事 —— 后者由开关默认态（全量）负责呈现。
     */
    public static final String HEALTHY_SCOPE_SQL = "CancelledAt IS NULL AND " + NOT_ATTACK_SQL;

    private OrderDataScope() {
    }

    /**
     * Java 侧判据（超时取消 job 逐行读取时用；SQL 侧见 {@link #NOT_ATTACK_SQL}）。
     *
     * <p>⚠️ {@code source} 用 {@code trim()} 后比、{@code equalsIgnoreCase}：列没有 collation
     * 约定，宁松不严。空串 / {@code null} 都**不是**演练数据（空串是列默认值）。
     */
    public static boolean isAttackData(String orderId, String source) {
        if (orderId != null && orderId.startsWith(ATTACK_ORDER_PREFIX)) {
            return true;
        }
        return source != null && source.trim().equalsIgnoreCase(ATTACK_SOURCE);
    }
}
