package com.minke.mallmanagement.shared.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3-4 读侧掩码：形态、短串边界、以及「复制后改、不动入参」。
 *
 * <p>⚠️ 本测试同时钉住**不掩的字段**（{@code username} / {@code ShopName}）—— 掩码最容易出的
 * 事故不是「掩得不够」，而是「顺手多掩了一列」，把管理员判断所需的非敏感信息也抹掉。
 */
class DataMaskingTest {

    // ==================== 邮箱 ====================

    @Test
    @DisplayName("邮箱：本地部分只留首字符，域名原样")
    void masksEmail() {
        assertThat(DataMasking.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(DataMasking.maskEmail("a@x.com")).isEqualTo("a***@x.com");
        assertThat(DataMasking.maskEmail("bob.smith@mail.example.cn"))
                .isEqualTo("b***@mail.example.cn");
    }

    @Test
    @DisplayName("邮箱异常形态：没有 @ / 以 @ 结尾 / 单字符 —— 仍至少掩掉一个字符")
    void masksMalformedEmail() {
        assertThat(DataMasking.maskEmail("no-at-sign")).isEqualTo("n***");
        assertThat(DataMasking.maskEmail("alice@")).isEqualTo("a***");
        assertThat(DataMasking.maskEmail("x")).isEqualTo("***");
    }

    // ==================== 电话 ====================

    @Test
    @DisplayName("11 位手机号：前 3 + **** + 后 4")
    void masksElevenDigitPhone() {
        assertThat(DataMasking.maskPhone("13800138000")).isEqualTo("138****8000");
    }

    @Test
    @DisplayName("非 11 位：8 位走前 2 后 2；3–5 位只留首字符；1–2 位全掩")
    void masksShortPhone() {
        assertThat(DataMasking.maskPhone("12345678")).isEqualTo("12****78");
        assertThat(DataMasking.maskPhone("12345")).isEqualTo("1***");
        assertThat(DataMasking.maskPhone("12")).isEqualTo("****");
        assertThat(DataMasking.maskPhone("1")).isEqualTo("****");
    }

    // ==================== 地址 ====================

    @Test
    @DisplayName("地址 / 店铺地址：保留前 4 个字符")
    void masksAddress() {
        String address = "北京市朝阳区建国路1号";
        assertThat(DataMasking.maskAddress(address)).isEqualTo("北京市朝***");
        assertThat(DataMasking.maskShopAddress(address)).isEqualTo("北京市朝***");
    }

    @Test
    @DisplayName("短地址：长度不足 4 时保留到倒数第二个字符，至少掩掉一个")
    void masksShortAddress() {
        assertThat(DataMasking.maskAddress("北京")).isEqualTo("北***");
        assertThat(DataMasking.maskAddress("京")).isEqualTo("***");
    }

    // ==================== null / 空白 ====================

    @Test
    @DisplayName("null 与空白原样返回 —— 不凭空造出一个 ***")
    void keepsNullAndBlank() {
        assertThat(DataMasking.maskEmail(null)).isNull();
        assertThat(DataMasking.maskPhone(null)).isNull();
        assertThat(DataMasking.maskAddress(null)).isNull();
        assertThat(DataMasking.maskShopAddress(null)).isNull();

        assertThat(DataMasking.maskEmail("   ")).isEqualTo("   ");
        assertThat(DataMasking.maskPhone("")).isEmpty();
        assertThat(DataMasking.maskAddress("  ")).isEqualTo("  ");
    }

    // ==================== 行：只掩指定字段、不改入参 ====================

    @Test
    @DisplayName("用户行：只掩 email；username / banned 原样；入参不被改写")
    void masksUserRowWithoutMutatingSource() {
        Map<String, Object> row = new HashMap<>();
        row.put("username", "alice");
        row.put("email", "alice@example.com");
        row.put("banned", 1);

        Map<String, Object> masked = DataMasking.maskUserRow(row);

        assertThat(masked.get("email")).isEqualTo("a***@example.com");
        assertThat(masked.get("username")).isEqualTo("alice");
        assertThat(masked.get("banned")).isEqualTo(1);
        assertThat(row.get("email")).as("源行必须保持明文").isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("申请行：掩 ContactPhone / ShopAddress；ShopName 原样；入参不被改写")
    void masksApplicationRowWithoutMutatingSource() {
        Map<String, Object> row = new HashMap<>();
        row.put("ShopName", "小铺");
        row.put("ContactPhone", "13800138000");
        row.put("ShopAddress", "北京市朝阳区建国路1号");

        Map<String, Object> masked = DataMasking.maskApplicationRow(row);

        assertThat(masked.get("ContactPhone")).isEqualTo("138****8000");
        assertThat(masked.get("ShopAddress")).isEqualTo("北京市朝***");
        assertThat(masked.get("ShopName")).isEqualTo("小铺");
        assertThat(row.get("ContactPhone")).as("源行必须保持明文").isEqualTo("13800138000");
        assertThat(row.get("ShopAddress")).as("源行必须保持明文").isEqualTo("北京市朝阳区建国路1号");
    }

    @Test
    @DisplayName("批量版保持顺序；null 列表原样返回")
    void masksRowsInOrder() {
        Map<String, Object> a = new HashMap<>();
        a.put("email", "a@x.com");
        Map<String, Object> b = new HashMap<>();
        b.put("email", "b@x.com");

        List<Map<String, Object>> masked = DataMasking.maskUserRows(List.of(a, b));

        assertThat(masked).extracting(r -> r.get("email"))
                .containsExactly("a***@x.com", "b***@x.com");
        assertThat(DataMasking.maskUserRows(null)).isNull();
        assertThat(DataMasking.maskApplicationRows(null)).isNull();
    }
}
