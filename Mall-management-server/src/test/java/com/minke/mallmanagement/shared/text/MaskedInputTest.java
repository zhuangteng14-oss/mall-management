package com.minke.mallmanagement.shared.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3-4 写侧闸：掩码字段清单、命中顺序、拒绝文案。
 *
 * <p>⚠️ 这里钉的是 {@link MaskedInput} 的**纯判定**；「真的没落库」由
 * {@code MerchantApplicationMaskedSubmitTest} 用控制器替身验证 —— 两半合起来才是
 * 「提交带 {@code *} 必须失败」。
 */
class MaskedInputTest {

    @Test
    @DisplayName("guardedFields 恰好是裁决的四个字段（多一个少一个都红）")
    void guardedFieldsAreExactlyTheFourRuledFields() {
        assertThat(MaskedInput.guardedFields())
                .containsExactlyInAnyOrder("email", "address", "contactPhone", "shopAddress");
    }

    @Test
    @DisplayName("firstMasked：按传入顺序报第一个命中字段")
    void firstMaskedReportsFirstHit() {
        assertThat(MaskedInput.firstMasked("email", "a***@x.com")).isEqualTo("email");
        assertThat(MaskedInput.firstMasked(
                "contactPhone", "13800138000",
                "shopAddress", "北京市朝***")).isEqualTo("shopAddress");
        assertThat(MaskedInput.firstMasked(
                "contactPhone", "138****8000",
                "shopAddress", "北京市朝阳区建国路1号")).isEqualTo("contactPhone");
    }

    @Test
    @DisplayName("没有命中 / null / 奇数长度：返回 null，不误伤合法值")
    void firstMaskedReturnsNullWhenClean() {
        assertThat(MaskedInput.firstMasked("email", "alice@example.com")).isNull();
        assertThat(MaskedInput.firstMasked("email", (String) null)).isNull();
        assertThat(MaskedInput.firstMasked((String[]) null)).isNull();
        // 奇数长度是调用方笔误：多出的那一项忽略，已配对的仍要判。
        assertThat(MaskedInput.firstMasked("email", "a***@x.com", "dangling")).isEqualTo("email");
    }

    @Test
    @DisplayName("拒绝文案：店铺地址有中文名，且说清「* 是掩码不是真值」")
    void rejectMessageNamesShopAddress() {
        assertThat(MaskedInput.rejectMessage("shopAddress"))
                .contains("店铺地址")
                .contains("* 号")
                .contains("掩码");
        assertThat(MaskedInput.rejectMessage("contactPhone")).contains("联系电话");
    }

    @Test
    @DisplayName("isMasked：只看 *，null ⇒ false；空串 ⇒ false")
    void isMaskedOnlyLooksForStar() {
        assertThat(MaskedInput.isMasked(null)).isFalse();
        assertThat(MaskedInput.isMasked("")).isFalse();
        assertThat(MaskedInput.isMasked("正常值")).isFalse();
        assertThat(MaskedInput.isMasked("138****8000")).isTrue();
    }
}
