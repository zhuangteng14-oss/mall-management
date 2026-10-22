package com.minke.mallmanagement.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FaqKnowledgeBase} 的命中判据（增量：轻量知识库）。
 *
 * <p>重点不只是「能命中」，还要「**不误命中**」：售后/订单短句里出现「退货」不代表
 * 在问政策，若 FAQ 把它截走，客服会答非所问。
 */
class FaqKnowledgeBaseTest {

    private static FaqKnowledgeBase.Answer match(String raw) {
        return FaqKnowledgeBase.match(ChatIntentRecognizer.normalize(raw));
    }

    @Test
    @DisplayName("拆封退货：命中政策条目，答案是用户给的原文")
    void unboxingPolicy() {
        FaqKnowledgeBase.Answer answer = match("拆封了能退货吗？");

        assertThat(answer).isNotNull();
        assertThat(answer.policy()).isTrue();
        assertThat(answer.text()).isEqualTo("支持7天无理由，但需保证不影响二次销售哦。");
    }

    @Test
    @DisplayName("运费：命中政策条目")
    void shippingPolicy() {
        FaqKnowledgeBase.Answer answer = match("运费怎么算");

        assertThat(answer).isNotNull();
        assertThat(answer.policy()).isTrue();
        assertThat(answer.text()).isEqualTo("本店满99包邮，偏远地区除外。");
    }

    @Test
    @DisplayName("「我的退货进度」不命中政策 FAQ —— 它是查售后，不是问政策")
    void refundProgressIsNotFaq() {
        assertThat(match("我的退货进度")).isNull();
        assertThat(match("我订单的退款到哪了")).isNull();
    }

    @Test
    @DisplayName("人工客服：命中非政策条目（只在 UNKNOWN 分支使用）")
    void humanAgentEntry() {
        FaqKnowledgeBase.Answer answer = match("转人工");

        assertThat(answer).isNotNull();
        assertThat(answer.policy()).isFalse();
        assertThat(answer.text()).contains("自助客服");
    }

    @Test
    @DisplayName("无关输入 / 空输入：不命中")
    void miss() {
        assertThat(match("今天天气不错")).isNull();
        assertThat(match("")).isNull();
        assertThat(FaqKnowledgeBase.match(null)).isNull();
    }
}
