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
    @DisplayName("买家行为五连：在哪购买 / 想买 / 搜索 / 多少钱 都归 GOODS_QUERY（商家同样适用）")
    void buyerShoppingPhrasesMapToGoodsQuery() {
        String[] phrases = {
                "oppo12这商品在哪购买？",
                "我想买个护膝",
                "怎么搜索蓝牙耳机",
                "这手机多少钱",
                "帮我找一个蓝牙耳机",
        };
        for (String phrase : phrases) {
            assertThat(ChatIntentRecognizer.recognize(phrase).intent())
                    .as(phrase)
                    .isEqualTo(ChatIntent.GOODS_QUERY);
        }
    }

    @Test
    @DisplayName("购物关键词：型号与中文名都能抽出来，泛称不当商品名")
    void extractsShoppingKeyword() {
        assertThat(ChatIntentRecognizer.extractShoppingKeyword(
                ChatIntentRecognizer.normalize("oppo12这商品在哪购买？"))).isEqualTo("oppo12");
        assertThat(ChatIntentRecognizer.extractShoppingKeyword(
                ChatIntentRecognizer.normalize("我想买个护膝"))).isEqualTo("护膝");
        assertThat(ChatIntentRecognizer.extractShoppingKeyword(
                ChatIntentRecognizer.normalize("怎么搜索蓝牙耳机"))).isEqualTo("蓝牙耳机");
        assertThat(ChatIntentRecognizer.extractShoppingKeyword(
                ChatIntentRecognizer.normalize("这手机多少钱"))).isEqualTo("手机");
        assertThat(ChatIntentRecognizer.extractShoppingKeyword(
                ChatIntentRecognizer.normalize("帮我找一个蓝牙耳机"))).isEqualTo("蓝牙耳机");
        assertThat(ChatIntentRecognizer.extractShoppingKeyword(
                ChatIntentRecognizer.normalize("这个商品多少钱"))).isNull();
    }

    @Test
    @DisplayName("商家管理语句不会被购物规则抢走")
    void merchantManagementIsNotShopping() {
        assertThat(ChatIntentRecognizer.looksLikeShopping(
                ChatIntentRecognizer.normalize("我要管理商品"))).isFalse();
        assertThat(ChatIntentRecognizer.looksLikeShopping(
                ChatIntentRecognizer.normalize("怎么上架商品"))).isFalse();
    }

    @Test
    @DisplayName("商品查询：带冒号的编号原样进商品槽位，不当订单号")
    void goodsQueryWithPrefixedCode() {
        String text = ChatIntentRecognizer.normalize("怎么看 222:ij-555 商品的详细数据");
        ChatIntentRecognizer.Match m = ChatIntentRecognizer.recognize("怎么看 222:ij-555 商品的详细数据");

        assertThat(m.intent()).isEqualTo(ChatIntent.GOODS_QUERY);
        assertThat(m.hasOrderId()).isFalse();
        assertThat(ChatIntentRecognizer.extractGoodsKeyword(text)).isEqualTo("222:ij-555");
    }

    @Test
    @DisplayName("商品查询：型号 token（字母+数字）与「多少钱」也能识别")
    void goodsQueryWithModelToken() {
        ChatIntentRecognizer.Match m = ChatIntentRecognizer.recognize("oppo12 多少钱");

        assertThat(m.intent()).isEqualTo(ChatIntent.GOODS_QUERY);
        assertThat(ChatIntentRecognizer.extractGoodsKeyword(ChatIntentRecognizer.normalize("oppo12 多少钱")))
                .isEqualTo("oppo12");
    }

    @Test
    @DisplayName("商品边界：上架 / 改价仍是办事，订单里的商品仍归订单，评论仍归评论")
    void goodsQueryDoesNotStealOtherIntents() {
        assertThat(ChatIntentRecognizer.recognize("怎么上架商品").intent())
                .isEqualTo(ChatIntent.OPEN_STORE);
        assertThat(ChatIntentRecognizer.recognize("怎么修改商品价格").intent())
                .isNotEqualTo(ChatIntent.GOODS_QUERY);
        assertThat(ChatIntentRecognizer.recognize("我的订单里的商品详情").intent())
                .isEqualTo(ChatIntent.ORDER_QUERY);
        assertThat(ChatIntentRecognizer.recognize("我要看商品评论").intent())
                .isEqualTo(ChatIntent.COMMENT_QUERY);
    }

    @Test
    @DisplayName("商品边界：逛 / 看商品列表是浏览，不是查某个商品的详情")
    void browsingPhrasesAreNotGoodsQuery() {
        assertThat(ChatIntentRecognizer.recognize("看看商品").intent())
                .isNotEqualTo(ChatIntent.GOODS_QUERY);
        assertThat(ChatIntentRecognizer.recognize("逛逛商品").intent())
                .isNotEqualTo(ChatIntent.GOODS_QUERY);
        assertThat(ChatIntentRecognizer.recognize("去首页看看商品").intent())
                .isNotEqualTo(ChatIntent.GOODS_QUERY);
        // 反过来：带了具体商品 token 时，即使句中有「看看」也仍然是查商品。
        assertThat(ChatIntentRecognizer.recognize("看看 oppo12 商品详情").intent())
                .isEqualTo(ChatIntent.GOODS_QUERY);
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

    @Test
    @DisplayName("N3 秒杀：报名/限时抢购/上架到秒杀都走 ADD_TO_SECKILL")
    void seckillIntent() {
        assertThat(ChatIntentRecognizer.recognize("我要报名秒杀").intent())
                .isEqualTo(ChatIntent.ADD_TO_SECKILL);
        assertThat(ChatIntentRecognizer.recognize("限时抢购怎么参加").intent())
                .isEqualTo(ChatIntent.ADD_TO_SECKILL);
        assertThat(ChatIntentRecognizer.recognize("把商品上架到秒杀").intent())
                .isEqualTo(ChatIntent.ADD_TO_SECKILL);
        assertThat(ChatIntentRecognizer.recognize("报名活动").intent())
                .isEqualTo(ChatIntent.ADD_TO_SECKILL);
    }

    @Test
    @DisplayName("N3 秒杀优先于开店：「上架到秒杀」不是入驻")
    void seckillBeatsOpenStore() {
        assertThat(ChatIntentRecognizer.recognize("上架").intent())
                .isEqualTo(ChatIntent.OPEN_STORE);
        assertThat(ChatIntentRecognizer.recognize("上架到秒杀").intent())
                .isEqualTo(ChatIntent.ADD_TO_SECKILL);
    }

    @Test
    @DisplayName("增量-A 规则 2：自述身份单独成意图；带开店关键词仍走开店")
    void identityClaim() {
        assertThat(ChatIntentRecognizer.recognize("我是商家").intent())
                .isEqualTo(ChatIntent.IDENTITY_CLAIM);
        assertThat(ChatIntentRecognizer.recognize("我是品牌商家").intent())
                .isEqualTo(ChatIntent.IDENTITY_CLAIM);
        assertThat(ChatIntentRecognizer.recognize("我是普通用户").intent())
                .isEqualTo(ChatIntent.IDENTITY_CLAIM);
        assertThat(ChatIntentRecognizer.recognize("我是商家，怎么开店").intent())
                .isEqualTo(ChatIntent.OPEN_STORE);
    }
}
