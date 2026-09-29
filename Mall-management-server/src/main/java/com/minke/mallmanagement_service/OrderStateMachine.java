package com.minke.mallmanagement_service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 订单状态机 —— 三个 CAS 端点背后的转移表，也是本笔的**唯一真值源**。
 *
 * <h2>【两张表，不是一个：语义层 vs 守卫层】</h2>
 * 二者**不重合**，把它们当成一回事是本笔最容易犯的错：
 * <pre>
 *   语义层  合法前提：PAY: CREATED → PAID
 *                    DELIVER: PAID → SHIPPED
 *                    RECEIVE: PAID → RECEIVED_NODELIVERY / SHIPPED → COMPLETED
 *   守卫层  现状 WHERE：PAY:     PayIf = 0
 *                    DELIVER: PayIf = 1 AND DeliveryIf = 0 AND ReceiveIf = 0
 *                    RECEIVE: PayIf = 1 AND ReceiveIf = 0
 * </pre>
 * 守卫层**比语义层宽**，宽度恰好等于 TD-17 的入口伪造能力：{@code PAY} 的守卫只卡一位，
 * 于是伪造出来的 {@code (0,1,0)} 也能被 pay 推到 {@code (1,1,0)}（一个**合法**态 —— 伪造
 * 数据被"洗白"了）。这不是我推断的，它就是并发地图说的「条件 UPDATE 是谓词守卫、
 * 不是精确状态相等」（{@code concurrency-map.md §3}）。
 *
 * <p>所以本类**同时提供两层**：{@link #guardWhere} 给 SQL（与现状逐字等价），
 * {@link #legalFrom} 给语义与观测。两者的差集是显式的、可测的（见
 * {@code OrderStateMachineTest.onlyPayGuardAdmitsIllegalStates}），不会被藏起来。
 *
 * <h2>【姿态：描述性，不是强制性】</h2>
 * {@link #updateSql} 生成的语句与 B2 之前三个端点的硬编码字面量**逐字符相同** ⇒ 零行为变更
 * ⇒ 向后兼容（{@code agent.md} 规则 1）且 A3 的 A 组恒绿。
 * <p>**不**在本笔新增任何拒绝：拒绝 {@code PAID → RECEIVED_NODELIVERY}（未发货即收货）
 * 或卡死 {@code orderAdd} 的三位入参，都会改行为、断前端「下单即付款」的流程、
 * 并使 A3 的 S4a 全部 8 条断言失去前置（它正是靠 {@code orderAdd} 伪造前置态——
 * {@code ConcurrencyRunner:1429-1430}）。强制化是单独一笔，需业务裁决。
 * <p>描述性 ≠ 没用：三处散落 SQL 收敛到一处；非法态从「没人知道」变成可观测；
 * 强制化只需改这一张表。
 *
 * <h2>【SettlementDate 为什么在 UPDATE 语句里】</h2>
 * {@link #updateSql} 带着 {@code SettlementDate = NOW()} 的尾巴，那是 BD-01 的遗留
 * （付款 / 发货 / 收货三处覆写同一列，该列无法稳定表示任何一个业务时间点）。
 * 它**语义上不属于状态机**，但把整条语句收在本类，golden copy 才能钉住「发到 MySQL 的
 * 那个串一个字都没变」—— 这是本笔真正要守的东西。BD-01 本身不动（规则 2 禁删改字段），
 * 它在事实上被本笔新增的时间线表取代了数据源，但那是 C3 的事。
 */
@Component
public class OrderStateMachine {

    /**
     * 状态名写进 {@code order_state_transition.ToState} 的最大长度。
     * <p>与建表语句里的 {@code varchar(40)} 同步 —— 最长的是
     * {@code ILLEGAL_DELIVERED_RECEIVED_UNPAID}（33 字符）。由
     * {@code OrderStateMachineTest.allStateNamesFitTheColumn} 钉住，改枚举名会先红。
     */
    public static final int STATE_NAME_MAX_LEN = 40;

    /** 三位里出现 {0,1} 之外取值时的状态名。只有 {@code orderAdd} 的伪造路径能造出来。 */
    public static final String UNCLASSIFIED = "UNCLASSIFIED";

    /**
     * 时间线里「没有前态」的占位名 —— 只用于 {@link OrderAction#CREATE}。
     *
     * <p>下单**不是转移**（{@link #updateSql} 拒收 CREATE），所以它没有 from 可言；
     * 但时间线若从第一次转移才开始记，那么「只下过单、还没付款」的订单在表里**一行都没有**，
     * 而它的初始三位**在别处不可恢复**（{@code order} 表只存当前值）。
     * 故出生这一笔照记，用本占位名诚实地表示「前面没有状态」，而不是留空让人猜是「没读到」。
     */
    public static final String NO_PREVIOUS_STATE = "NONE";

    private static final String TABLE = "mall_management_sql.`order`";

    /** 一条守卫谓词：`列 = 值`。 */
    public record Predicate(String column, int value) {
    }

    /** 一条转移：动作 + 守卫谓词（有序 —— 拼出来的 SQL 才稳定）。 */
    public record Transition(OrderAction action, List<Predicate> guard) {
    }

    private static final Map<OrderAction, Transition> TRANSITIONS = new EnumMap<>(OrderAction.class);

    static {
        // ⚠️⚠️ 这三段守卫是本笔的**唯一真值源**，必须与 B2 之前三个端点的 WHERE 逐字一致 ——
        //      由 OrderStateMachineTest 的 golden copy 钉死（字面量从 MallManagementGoodsController
        //      抄下来）。改这里 = 改线上行为，且会让那条测试先红。
        //      顺序也有意义：谓词按此顺序拼进 SQL。
        TRANSITIONS.put(OrderAction.PAY, new Transition(OrderAction.PAY, List.of(
                new Predicate("PayIf", 0))));

        TRANSITIONS.put(OrderAction.DELIVER, new Transition(OrderAction.DELIVER, List.of(
                new Predicate("PayIf", 1),
                new Predicate("DeliveryIf", 0),
                new Predicate("ReceiveIf", 0))));

        TRANSITIONS.put(OrderAction.RECEIVE, new Transition(OrderAction.RECEIVE, List.of(
                new Predicate("PayIf", 1),
                new Predicate("ReceiveIf", 0))));

        // CREATE 不是转移：它没有前态、没有守卫（订单是 INSERT 出来的，不是 UPDATE 出来的）。
        TRANSITIONS.put(OrderAction.CREATE, new Transition(OrderAction.CREATE, List.of()));
    }

    // ===================== 守卫层：生成 SQL =====================

    /**
     * 三个 CAS 端点的完整 UPDATE 语句。
     *
     * <p>与 B2 之前硬编码在 {@code MallManagementGoodsController} 里的字面量**逐字符相同**：
     * <pre>
     *   PAY     → "UPDATE mall_management_sql.`order` SET PayIf = 1, SettlementDate = NOW()
     *              WHERE OrderId = ? AND PayIf = 0"                                  (原 G:556)
     *   RECEIVE → "… SET ReceiveIf = 1, SettlementDate = NOW()
     *              WHERE OrderId = ? AND PayIf = 1 AND ReceiveIf = 0"                (原 G:584)
     *   DELIVER → "… SET DeliveryIf = 1, SettlementDate = NOW()
     *              WHERE OrderId = ? AND PayIf = 1 AND DeliveryIf = 0 AND ReceiveIf = 0" (原 G:612)
     * </pre>
     *
     * @throws IllegalArgumentException {@code action} 不是转移（{@link OrderAction#CREATE}）
     */
    public String updateSql(OrderAction action) {
        requireTransition(action);
        return "UPDATE " + TABLE + " SET " + setClause(action)
                + ", SettlementDate = NOW() WHERE OrderId = ? AND " + guardWhere(action);
    }

    /** {@code SET} 子句里置位那一段，如 {@code PayIf = 1}。 */
    public String setClause(OrderAction action) {
        requireTransition(action);
        return action.column() + " = 1";
    }

    /** {@code WHERE} 里守卫那一段，如 {@code PayIf = 1 AND ReceiveIf = 0}。 */
    public String guardWhere(OrderAction action) {
        requireTransition(action);
        StringBuilder sb = new StringBuilder();
        for (Predicate p : guard(action)) {
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append(p.column()).append(" = ").append(p.value());
        }
        return sb.toString();
    }

    /**
     * 读回转移后的三位 —— **只在 UPDATE 命中 1 行后执行**。
     *
     * <p>为什么需要它：守卫只约束它自己那几位，另几位是自由的（{@code PAY} 只约束 PayIf，
     * 所以转移后 {@code DeliveryIf/ReceiveIf} 必须从库里读）。被拒绝的路径（A3 的重复转移
     * 冲击下占多数）**一次都不用读** —— 那里没有「转移后」可言。
     */
    public String selectBitsSql() {
        return "SELECT PayIf, DeliveryIf, ReceiveIf FROM " + TABLE + " WHERE OrderId = ?";
    }

    // ===================== 语义层与位运算 =====================

    /** 某动作的守卫谓词表（有序、不可变）。{@link OrderAction#CREATE} 为空表。 */
    public List<Predicate> guard(OrderAction action) {
        return transition(action).guard();
    }

    /**
     * 守卫层**接纳的全部状态**，含非法态 —— 即「WHERE 会命中哪些行」。
     *
     * <p>与 {@link #legalFrom} 的差集，就是守卫比语义宽出来的那一块（TD-17 的入口伪造）。
     */
    public List<OrderState> guardAdmits(OrderAction action) {
        if (!action.isTransition()) {
            // CREATE 的空守卫「对什么都成立」是空真，不是「什么都接纳」—— 别让它冒充全集。
            return List.of();
        }
        List<OrderState> out = new ArrayList<>();
        for (OrderState s : OrderState.values()) {
            if (admits(action, s)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 语义层：本动作的**合法前提**状态集合。
     *
     * <p>由「守卫 ∧ 合法」**算出来**，不手写第二份 —— 手写的第二份必然与守卫漂移。
     * <pre>
     *   PAY     → [CREATED]
     *   DELIVER → [PAID]
     *   RECEIVE → [PAID, SHIPPED]      ← 两条前提，是 RECEIVE 与另两个动作的本质区别
     *   CREATE  → []                   （出生没有前态）
     * </pre>
     */
    public List<OrderState> legalFrom(OrderAction action) {
        if (!action.isTransition()) {
            return List.of();       // 出生没有前态
        }
        List<OrderState> out = new ArrayList<>();
        for (OrderState s : OrderState.values()) {
            if (s.legal() && admits(action, s)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 施加一次转移：把本动作那一位置 1，另两位不变。
     *
     * @return 转移后的状态
     * @throws IllegalArgumentException {@code action} 不是转移，或 {@code from} 与置位结果
     *                                  无法映射到 {@link OrderState}（理论上不会：另两位来自枚举）
     */
    public OrderState apply(OrderAction action, OrderState from) {
        requireTransition(action);
        int[] bits = bitsOf(from);
        bits[action.bitIndex()] = 1;
        return OrderState.of(bits[0], bits[1], bits[2]);
    }

    /**
     * 由**观察到的转移后三位**反推转移前状态名。
     *
     * <p>反推依据：守卫要求「本动作那一位 = 0」，而本动作只置这一位、不碰另两位
     * ⇒ {@code from} 恒等于 {@code to} 把这一位清零。**这是精确反推，不是近似**
     * —— 前提是每条守卫都真的约束了本位（由测试
     * {@code everyGuardRequiresItsOwnBitToBeZero} 钉住）。
     *
     * <p>永不抛：另两位若是伪造的越界值，返回 {@link #UNCLASSIFIED} 而不是炸掉观测路径。
     *
     * @param payIf 转移后从库里读回的 PayIf（见 {@link #selectBitsSql()}）
     */
    public String previousStateName(OrderAction action, int payIf, int deliveryIf, int receiveIf) {
        requireTransition(action);
        int[] bits = {payIf, deliveryIf, receiveIf};
        bits[action.bitIndex()] = 0;
        return stateName(bits[0], bits[1], bits[2]);
    }

    /**
     * 三位 → 状态名，**永不抛**。越界取值返回 {@link #UNCLASSIFIED}。
     * <p>观测路径专用：伪造数据（TD-17）不该让埋点或时间线写失败。
     */
    public String stateName(int payIf, int deliveryIf, int receiveIf) {
        return OrderState.isCanonical(payIf, deliveryIf, receiveIf)
                ? OrderState.of(payIf, deliveryIf, receiveIf).name()
                : UNCLASSIFIED;
    }

    /**
     * 三位组合的**非法形状**；合法时返回 {@code null}。
     *
     * <p>这是 TD-17 的探测口径：{@code orderAdd} 的三个状态位由客户端直传，是伪造态唯一的门
     * （{@code ConcurrencyRunner} 的 S4a 正是从这个门造前置条件）。**只分类、不拦截**
     * —— 拦截是另一笔、需业务裁决（见类注释的「姿态」段），本类只负责把「是什么形状」说清楚。
     *
     * @return {@code "010"} / {@code "001"} / {@code "011"}（{0,1} 内的非法组合，按
     *         PayIf-DeliveryIf-ReceiveIf 顺序拼）、{@code "non_binary"}（越界取值，
     *         例如 {@code orderAdd} 收了 2）；合法组合 ⇒ {@code null}
     */
    public String illegalShape(int payIf, int deliveryIf, int receiveIf) {
        if (!OrderState.isCanonical(payIf, deliveryIf, receiveIf)) {
            // 越界取值先归一：否则 shape 的取值会随客户端传什么都变，tag 基数失去上界。
            return "non_binary";
        }
        return OrderState.of(payIf, deliveryIf, receiveIf).legal()
                ? null
                : "" + payIf + deliveryIf + receiveIf;
    }

    // ===================== 内部 =====================

    private boolean admits(OrderAction action, OrderState s) {
        int[] bits = bitsOf(s);
        for (Predicate p : guard(action)) {
            if (bits[bitIndexOf(p.column())] != p.value()) {
                return false;
            }
        }
        return true;
    }

    private static int[] bitsOf(OrderState s) {
        return new int[]{s.payIf(), s.deliveryIf(), s.receiveIf()};
    }

    /** 列名 → 位下标。列名来自本类的守卫表，不是外部输入。 */
    private static int bitIndexOf(String column) {
        for (OrderAction a : OrderAction.values()) {
            if (column.equals(a.column())) {
                return a.bitIndex();
            }
        }
        throw new IllegalArgumentException("未知状态位列名：" + column);
    }

    private static Transition transition(OrderAction action) {
        Transition t = TRANSITIONS.get(action);
        if (t == null) {
            throw new IllegalArgumentException("未登记的订单动作：" + action);
        }
        return t;
    }

    private static void requireTransition(OrderAction action) {
        if (action == null || !action.isTransition()) {
            throw new IllegalArgumentException("不是状态转移（无前态、无守卫）：" + action);
        }
    }
}
