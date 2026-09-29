package com.minke.mallmanagement_service;

import com.minke.mallmanagement_service.OrderStateMachine.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.minke.mallmanagement_service.OrderAction.CREATE;
import static com.minke.mallmanagement_service.OrderAction.DELIVER;
import static com.minke.mallmanagement_service.OrderAction.PAY;
import static com.minke.mallmanagement_service.OrderAction.RECEIVE;
import static com.minke.mallmanagement_service.OrderState.COMPLETED;
import static com.minke.mallmanagement_service.OrderState.CREATED;
import static com.minke.mallmanagement_service.OrderState.ILLEGAL_DELIVERED_RECEIVED_UNPAID;
import static com.minke.mallmanagement_service.OrderState.ILLEGAL_DELIVERED_UNPAID;
import static com.minke.mallmanagement_service.OrderState.ILLEGAL_RECEIVED_UNPAID;
import static com.minke.mallmanagement_service.OrderState.PAID;
import static com.minke.mallmanagement_service.OrderState.RECEIVED_NODELIVERY;
import static com.minke.mallmanagement_service.OrderState.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OrderStateMachine} 的**核心回归闸**。
 *
 * <h2>【本文件里最值钱的一条】</h2>
 * {@link #updateSqlIsByteForByteTheLegacyLiteral}：本笔把三处硬编码 SQL 换成了「生成」。
 * 那笔交易的风险全部集中在一处 —— 生成的串与原来那个串**不再相同**，而行为随之静默改变。
 * 所以这里放的是**从改动前的源码逐字抄下来的 golden copy**（原
 * {@code MallManagementGoodsController:556 / 584 / 612}），断言逐字符相等。
 * 它变红 = 生成逻辑动了线上行为，**不是**测试需要更新。
 *
 * <p>其余各条守的是「状态机这张表本身自洽」，其中最要紧的是
 * {@link #everyGuardRequiresItsOwnBitToBeZero} —— 时间线「不需要额外 SELECT 旧态」
 * 这个性能取舍，全部押在它身上。
 */
class OrderStateMachineTest {

    private final OrderStateMachine machine = new OrderStateMachine();

    // ===================== 一、golden copy =====================

    @Test
    @DisplayName("生成的 UPDATE 与改动前三处硬编码**逐字符相同** —— 本笔全部风险的收敛点")
    void updateSqlIsByteForByteTheLegacyLiteral() {
        // 下面三个字面量抄自改动前的 MallManagementGoodsController（B2 之后的行号），
        // 抄的时候一个空格都没动。改它们之前先想清楚：这不是测试，这是外部契约的副本。
        assertThat(machine.updateSql(PAY))
                .as("原 G:556 —— orderPayIfUp")
                .isEqualTo("UPDATE mall_management_sql.`order` SET PayIf = 1, "
                        + "SettlementDate = NOW() WHERE OrderId = ? AND PayIf = 0");

        assertThat(machine.updateSql(RECEIVE))
                .as("原 G:584 —— orderReceiveIfUp")
                .isEqualTo("UPDATE mall_management_sql.`order` SET ReceiveIf = 1, "
                        + "SettlementDate = NOW() WHERE OrderId = ? AND PayIf = 1 AND ReceiveIf = 0");

        assertThat(machine.updateSql(DELIVER))
                .as("原 G:612 —— orderDeliveryIfUp")
                .isEqualTo("UPDATE mall_management_sql.`order` SET DeliveryIf = 1, "
                        + "SettlementDate = NOW() WHERE OrderId = ? "
                        + "AND PayIf = 1 AND DeliveryIf = 0 AND ReceiveIf = 0");
    }

    @Test
    @DisplayName("读回三位的语句只查三列、只按 OrderId 定位（B3 新增，无历史包袱可比对，故钉形状）")
    void selectBitsSqlIsStable() {
        assertThat(machine.selectBitsSql())
                .isEqualTo("SELECT PayIf, DeliveryIf, ReceiveIf FROM mall_management_sql.`order` WHERE OrderId = ?");
    }

    @Test
    @DisplayName("三个 SQL 构造器都拒绝 CREATE：订单是 INSERT 出来的，没有守卫、没有前态可言")
    void createHasNoTransitionSql() {
        assertThatThrownBy(() -> machine.updateSql(CREATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.setClause(CREATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.guardWhere(CREATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.apply(CREATE, CREATED)).isInstanceOf(IllegalArgumentException.class);
        assertThat(machine.guard(CREATE)).as("数据访问器不抛，返回空表").isEmpty();
    }

    // ===================== 二、守卫层：谓词，不是精确状态 =====================

    @Test
    @DisplayName("守卫是**谓词**：PAY 只约束一位，DELIVER 约束三位，RECEIVE 约束两位")
    void guardIsTheLegacyPredicateNotExactStateEquality() {
        assertThat(machine.guard(PAY)).containsExactly(new Predicate("PayIf", 0));
        assertThat(machine.guard(RECEIVE)).containsExactly(
                new Predicate("PayIf", 1), new Predicate("ReceiveIf", 0));
        assertThat(machine.guard(DELIVER)).containsExactly(
                new Predicate("PayIf", 1), new Predicate("DeliveryIf", 0), new Predicate("ReceiveIf", 0));

        assertThat(machine.guardWhere(PAY)).isEqualTo("PayIf = 0");
        assertThat(machine.guardWhere(RECEIVE)).isEqualTo("PayIf = 1 AND ReceiveIf = 0");
        assertThat(machine.guardWhere(DELIVER)).isEqualTo("PayIf = 1 AND DeliveryIf = 0 AND ReceiveIf = 0");

        assertThat(machine.guardAdmits(PAY))
                .as("PAY 的守卫接纳 4 个状态 —— 只卡一位，所以 3 个伪造态也进得来")
                .containsExactly(CREATED, ILLEGAL_DELIVERED_UNPAID, ILLEGAL_RECEIVED_UNPAID,
                        ILLEGAL_DELIVERED_RECEIVED_UNPAID);
        assertThat(machine.guardAdmits(DELIVER)).containsExactly(PAID);
        assertThat(machine.guardAdmits(RECEIVE)).containsExactly(PAID, SHIPPED);
    }

    @Test
    @DisplayName("**只有 PAY 的守卫接纳非法态** —— 另两条守卫都要求 PayIf=1，而三个非法态的 PayIf 都是 0")
    void onlyPayGuardAdmitsIllegalStates() {
        for (OrderAction action : new OrderAction[]{DELIVER, RECEIVE}) {
            assertThat(machine.guardAdmits(action))
                    .as("%s 的守卫不该放任何非法态进来", action)
                    .allMatch(OrderState::legal);
        }
        assertThat(machine.guardAdmits(PAY)).as("PAY 是唯一的例外").anyMatch(s -> !s.legal());
    }

    // ===================== 三、语义层 =====================

    @Test
    @DisplayName("语义前提集合（由「守卫 ∧ 合法」算出，不手写第二份）")
    void legalFromIsTheSemanticTable() {
        assertThat(machine.legalFrom(PAY)).containsExactly(CREATED);
        assertThat(machine.legalFrom(DELIVER)).containsExactly(PAID);
        assertThat(machine.legalFrom(RECEIVE))
                .as("两条前提 —— 这是 RECEIVE 与另两个动作的本质区别，也是「未发货即收货」的来源")
                .containsExactly(PAID, SHIPPED);
        assertThat(machine.legalFrom(CREATE)).as("出生没有前态").isEmpty();
    }

    @Test
    @DisplayName("在合法集上闭合：每条转移的**合法前提**都推出**合法结果**")
    void transitionsAreClosedOnTheLegalSet() {
        for (OrderAction action : new OrderAction[]{PAY, DELIVER, RECEIVE}) {
            for (OrderState from : machine.legalFrom(action)) {
                OrderState to = machine.apply(action, from);
                assertThat(to.legal())
                        .as("%s: %s → %s 的结果必须仍是合法态", action, from, to)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("apply：只置本位、不碰另两位")
    void applySetsExactlyOneBit() {
        assertThat(machine.apply(PAY, CREATED)).isEqualTo(PAID);
        assertThat(machine.apply(DELIVER, PAID)).isEqualTo(SHIPPED);
        assertThat(machine.apply(RECEIVE, SHIPPED)).isEqualTo(COMPLETED);
        assertThat(machine.apply(RECEIVE, PAID)).isEqualTo(RECEIVED_NODELIVERY);
        assertThat(machine.apply(PAY, ILLEGAL_DELIVERED_UNPAID))
                .as("伪造态被 PAY 洗成 SHIPPED（合法）—— TD-17 的后果，不是设计意图")
                .isEqualTo(SHIPPED);
    }

    // ===================== 四、时间线所依赖的那条前提 =====================

    @Test
    @DisplayName("⚠️ 每条守卫都要求「本动作那一位 = 0」—— 时间线按「to 清位」反推 from 的全部依据")
    void everyGuardRequiresItsOwnBitToBeZero() {
        for (OrderAction action : new OrderAction[]{PAY, DELIVER, RECEIVE}) {
            assertThat(machine.guard(action))
                    .as("%s 的守卫必须含 (%s, 0)：否则 previousStateName 的反推不成立，"
                            + "时间线里的 from 会静默失真", action, action.column())
                    .contains(new Predicate(action.column(), 0));
        }
    }

    @Test
    @DisplayName("from 反推：逐条转移都能从**转移后**的三位精确还原出前态")
    void previousStateIsToWithTheBitCleared() {
        assertThat(machine.previousStateName(PAY, 1, 0, 0)).isEqualTo(CREATED.name());
        assertThat(machine.previousStateName(DELIVER, 1, 1, 0)).isEqualTo(PAID.name());
        assertThat(machine.previousStateName(RECEIVE, 1, 1, 1)).isEqualTo(SHIPPED.name());
        assertThat(machine.previousStateName(RECEIVE, 1, 0, 1))
                .as("未发货即收货：反推回 PAID，不是 SHIPPED")
                .isEqualTo(PAID.name());
        assertThat(machine.previousStateName(PAY, 1, 1, 0))
                .as("伪造行的反推结果老老实实是非法态 —— 不粉饰")
                .isEqualTo(ILLEGAL_DELIVERED_UNPAID.name());
    }

    @Test
    @DisplayName("反推与 apply 互为逆运算（在守卫接纳的范围内）")
    void previousStateInvertsApply() {
        for (OrderAction action : new OrderAction[]{PAY, DELIVER, RECEIVE}) {
            for (OrderState from : machine.guardAdmits(action)) {
                OrderState to = machine.apply(action, from);
                assertThat(machine.previousStateName(action, to.payIf(), to.deliveryIf(), to.receiveIf()))
                        .as("%s: %s → %s → 反推", action, from, to)
                        .isEqualTo(from.name());
            }
        }
    }

    // ===================== 五、观测路径不许炸 =====================

    @Test
    @DisplayName("stateName 永不抛：越界取值返回 UNCLASSIFIED（伪造数据不该让埋点/时间线写失败）")
    void stateNameNeverThrowsOnForgedBits() {
        assertThat(machine.stateName(2, 2, 2)).isEqualTo(OrderStateMachine.UNCLASSIFIED);
        assertThat(machine.stateName(1, 7, 0)).isEqualTo(OrderStateMachine.UNCLASSIFIED);
        assertThat(machine.stateName(-1, 0, 0)).isEqualTo(OrderStateMachine.UNCLASSIFIED);
        assertThat(machine.stateName(0, 1, 0))
                .as("{0,1} 内的伪造组合仍有名有姓 —— 不归一化、也不丢进 UNCLASSIFIED")
                .isEqualTo(ILLEGAL_DELIVERED_UNPAID.name());
        assertThat(machine.previousStateName(PAY, 1, 9, 0)).isEqualTo(OrderStateMachine.UNCLASSIFIED);
    }

    @Test
    @DisplayName("OrderState.of 反过来**拒绝**越界取值 —— 观测路径要靠 isCanonical 先判，或用 stateName")
    void ofRejectsNonCanonicalBits() {
        assertThatThrownBy(() -> OrderState.of(2, 2, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PayIf=2");
        assertThat(OrderState.isCanonical(0, 1, 1)).isTrue();
        assertThat(OrderState.isCanonical(0, 1, 2)).isFalse();
    }

    // ===================== 六、结构性约束 =====================

    @Test
    @DisplayName("所有状态名都塞得进建表语句的 varchar —— 改枚举名会先在这里红")
    void allStateNamesFitTheColumn() {
        int max = 0;
        for (OrderState s : OrderState.values()) {
            max = Math.max(max, s.name().length());
            assertThat(s.name().length()).isLessThanOrEqualTo(OrderStateMachine.STATE_NAME_MAX_LEN);
        }
        assertThat(OrderStateMachine.UNCLASSIFIED.length())
                .isLessThanOrEqualTo(OrderStateMachine.STATE_NAME_MAX_LEN);
        assertThat(max).as("当前最长的是 ILLEGAL_DELIVERED_RECEIVED_UNPAID")
                .isEqualTo(ILLEGAL_DELIVERED_RECEIVED_UNPAID.name().length());
    }

    @Test
    @DisplayName("合法态恰好 5 个、非法态恰好 3 个，且与 A-S4a-8 的不变式一一对应")
    void legalSplitMatchesTheInvariant() {
        List<OrderState> legal = List.of(CREATED, PAID, SHIPPED, RECEIVED_NODELIVERY, COMPLETED);
        for (OrderState s : OrderState.values()) {
            boolean byInvariant = !(s.payIf() == 0 && (s.deliveryIf() == 1 || s.receiveIf() == 1));
            assertThat(s.legal())
                    .as("%s：legal() 必须与 A-S4a-8 的 NOT(PayIf=0 AND (DeliveryIf=1 OR ReceiveIf=1)) 同判",
                            s)
                    .isEqualTo(byInvariant);
            assertThat(s.legal()).isEqualTo(legal.contains(s));
        }
        assertThat(legal).hasSize(5);
    }
}
