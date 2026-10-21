package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatIntentRecognizer} 的判据测试 —— 重点锁住评审 P0-1 的三条单号提取规则。
 *
 * <p>种子里的真实单号形如 {@code r2vfwK6z3E}（10 位、大小写混合字母 + 数字），
 * 既满足「长度 ≥ 10」，也满足「同时含字母和数字」；而 {@code 20261020} 这类纯数字日期
 * 必须**不**被当成单号。
 */
class ChatIntentRecognizerTest {

    @Test
    @DisplayName("显式「单号」前缀优先，哪怕同句里还有更长的隐式 token")
    void explicitPrefixWins() {
        ChatIntentRecognizer.Match m = ChatIntentRecognizer.recognize(
                "帮我查一下单号 r2vfwK6z3E，另外订单号旁边的 ABCD123456789 不用管");

        assertThat(m.intent()).isEqualTo(ChatIntent.ORDER_QUERY);
        assertThat(m.orderId()).isEqualTo("r2vfwk6z3e");
        assertThat(m.explicitOrderId()).isTrue();
    }

    @Test
    @DisplayName("纯数字日期不算单号：它不算订单关键词时整句落 UNKNOWN")
    void pureNumericDateIsNotAnOrderId() {
        ChatIntentRecognizer.Match m = ChatIntentRecognizer.recognize("20261020 是什么日子");

        assertThat(m.intent()).isEqualTo(ChatIntent.UNKNOWN);
        assertThat(m.hasOrderId()).isFalse();
    }

    @Test
    @DisplayName("纯数字长串（如种子里的 18 位订单号）隐式出现时也不提取")
    void pureNumericLongTokenIsNotExtracted() {
        ChatIntentRecognizer.Match m = ChatIntentRecognizer.recognize("913123193206498399");

        assertThat(m.hasOrderId()).isFalse();
        assertThat(m.intent()).isEqualTo(ChatIntent.UNKNOWN);
    }

    @Test
    @DisplayName("只贴一串字母数字混合的单号 ⇒ 意图仍是查订单")
    void bareOrderNoMeansOrderQuery() {
        ChatIntentRecognizer.Match m = ChatIntentRecognizer.recognize("r2vfwK6z3E");

        assertThat(m.intent()).isEqualTo(ChatIntent.ORDER_QUERY);
        assertThat(m.orderId()).isEqualTo("r2vfwk6z3e");
        assertThat(m.explicitOrderId()).isFalse();
    }

    @Test
    @DisplayName("多个隐式 token 取最长；不足 10 位的不要")
    void multipleImplicitTokensTakeLongest() {
        ChatIntentRecognizer.Match m = ChatIntentRecognizer.recognize(
                "订单 abc1234567 和 zz9k8j7h6g5f 哪个是");

        assertThat(m.orderId()).isEqualTo("zz9k8j7h6g5f");
    }

    @Test
    @DisplayName("售后优先于订单：「订单的退款进度」问的是退款")
    void refundBeatsOrder() {
        assertThat(ChatIntentRecognizer.recognize("我订单的退款进度到哪了").intent())
                .isEqualTo(ChatIntent.REFUND_QUERY);
    }

    @Test
    @DisplayName("问候只在整句匹配时成立：「你好，怎么开店」要继续往下走")
    void greetingOnlyWhenWholeSentence() {
        assertThat(ChatIntentRecognizer.recognize("你好！").intent()).isEqualTo(ChatIntent.GREETING);
        assertThat(ChatIntentRecognizer.recognize("你好，怎么开店").intent())
                .isEqualTo(ChatIntent.OPEN_STORE);
    }

    @Test
    @DisplayName("帮助与兜底")
    void helpAndUnknown() {
        assertThat(ChatIntentRecognizer.recognize("你能做什么？").intent()).isEqualTo(ChatIntent.HELP);
        assertThat(ChatIntentRecognizer.recognize("今天天气不错").intent()).isEqualTo(ChatIntent.UNKNOWN);
        assertThat(ChatIntentRecognizer.recognize("").intent()).isEqualTo(ChatIntent.UNKNOWN);
        assertThat(ChatIntentRecognizer.recognize(null).intent()).isEqualTo(ChatIntent.UNKNOWN);
    }

    @Test
    @DisplayName("全角冒号与空格不能挡住显式单号")
    void fullWidthNormalization() {
        ChatIntentRecognizer.Match m = ChatIntentRecognizer.recognize("单号：r2vfwK6z3E");

        assertThat(m.explicitOrderId()).isTrue();
        assertThat(m.orderId()).isEqualTo("r2vfwk6z3e");
    }
}
