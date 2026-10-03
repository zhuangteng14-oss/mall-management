package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 笔 3（校验层 + {@link com.minke.mallmanagement.shared.web.GlobalExceptionHandler}）的**唯一验收判据**。
 *
 * <h2>【为什么本类必须存在 —— A3 对本笔是 vacuous pass（空证据）】</h2>
 * 这是本笔最容易被误读的一点，写在这里以免后人再把 A3 全绿当成校验层的验收：
 * <ul>
 *   <li>A3 的 9 个真实端点，入参**清一色非空**：{@code orderForm} 的字段全是
 *       {@code "<前缀>-<runId>"} 形状（{@code ConcurrencyRunner.java:1157-1167}），
 *       {@code loghiAdd} / {@code friendAdd} / {@code loginSelect} 同样（{@code :1396/:2156/:2197}）；</li>
 *   <li>传输层 {@code encodeForm} 虽把 {@code null} 渲染成空串（{@code :824}），但**没有任何调用点传 null**；</li>
 *   <li>⇒ {@code @NotBlank} / {@code @NotNull} 在 A3 里**零触发**。A3 跑全绿
 *       **不能**证明校验层生效 —— 它只是没被踩到。</li>
 * </ul>
 * 这与 {@code defect-survey.md} 里「{@code continue-on-error} 让 job 颜色系统性无信息量」
 * 是**同一族的元发现**：一份证据看起来在证某件事，实际上对那件事不敏感。
 *
 * <p>故校验层的验收只能靠本类：**在切片里主动构造空串**，断言它确实被拦。
 *
 * <h2>【阴性 + 阳性，缺一不可】</h2>
 * 只写阴性例（空串 → 400）的话，一个「把所有请求都判 400」的错误实现照样全绿。故
 * {@link #validParamsStillPassValidation()} 是必需的对照：同一端点、同一 stub，合法入参必须 200。
 * 两条合起来才等价于「校验器真的在区分合法与非法」。
 *
 * <h2>【为什么进 Backend job 而不是 A3 job】</h2>
 * 继承 {@link AbstractSmokeTest}（{@code @WebMvcTest} 切片 + 替身）⇒ 不连 MySQL、不连 Redis，
 * 与其它 smoke 用例一起进 {@code ci.yml} 里那条不加过滤的 {@code ./mvnw -B test}。
 * ⚠️ 这也意味着：**本类若变红，是 Backend job 红**，不是 A3 job。
 *
 * <p>⚠️ 本类断言变红 = 校验层或异常处理器坏了，**不是**测试需要更新。
 */
class ValidationProbeTest extends AbstractSmokeTest {

    private static final String PRICE_URL = "/goods/productCodePrice";
    private static final String SEND_URL = "/message/send";

    // ===================== 阴性例：空串必须被拦，且响应体是 Result 形状 =====================

    @Test
    @DisplayName("阳性/阴性对照之一（阴性）：productCodePrice 传空串 MerchantId → 400 + {success:false,msg:…}")
    void blankMerchantIdIsRejectedWithUnifiedBody() throws Exception {
        mockMvc.perform(post(PRICE_URL)
                        .param("MerchantId", "")     // ← 被 @NotBlank 约束，且是空串
                        .param("GoodName", "smoke-goods")
                        .param("ProductCode", "SMOKE-P1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                // 前端各处是 alert('失败：' + data.msg) —— msg 必须可读，不能是 undefined。
                // 文案由 GlobalExceptionHandler 组装，不含「数据库错误」/「"success":true」两条 A3 红线。
                .andExpect(jsonPath("$.msg", containsString("参数校验失败")))
                .andExpect(jsonPath("$.msg", containsString("MerchantId")));
    }

    @Test
    @DisplayName("message/send 传空串 fromId → 400 + {success:false,msg:…}（未被任何前端调用点触发的防御路径）")
    void blankFromIdInSendIsRejected() throws Exception {
        mockMvc.perform(post(SEND_URL)
                        .param("fromId", "")
                        .param("fromType", "1")
                        .param("toId", "SMOKE-TARGET")
                        .param("toType", "1")
                        .param("content", "smoke"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg", containsString("参数校验失败")))
                .andExpect(jsonPath("$.msg", containsString("fromId")));
    }

    @Test
    @DisplayName("goods/goodsAdd 传空串 MerchantId → 400（MultipartFile 入参不参与非空校验）")
    void blankMerchantIdInGoodsAddIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "1.jpg", "image/jpeg", "IMG".getBytes());

        mockMvc.perform(multipart("/goods/goodsAdd")
                        .file(file)
                        .param("MerchantId", "")     // ← 空串
                        .param("GoodName", "smoke-goods")
                        .param("Price", "1.0")
                        .param("GoodType", "smoke-type")
                        .param("ProductCode", "SMOKE-P1")
                        .param("GoodTypeNew", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg", containsString("MerchantId")));
    }

    // ===================== 阳性例：合法入参不得被误拒 =====================

    /**
     * 对照例 —— 防「一律拒绝」的假绿。
     *
     * <p>与 {@link #blankMerchantIdIsRejectedWithUnifiedBody()} 打**同一个端点、同一套 stub**，
     * 唯一差别是 MerchantId 非空 ⇒ 必须 200 且 {@code success:true}。
     * 若哪天有人把校验写成了无条件拒绝，阴性例仍绿、本条变红，差异立现。
     *
     * <p>stub 抄自 {@code PriceTripleFailSmokeTest:51}：替身缓存 Service 返回一行裸行，
     * 使「合法入参」这条路径不依赖任何真实数据库。
     */
    @Test
    @DisplayName("阳性对照：productCodePrice 合法入参 → 200 + success:true（证明校验器在区分，而不是一律拒绝）")
    void validParamsStillPassValidation() throws Exception {
        when(goodsCacheService.priceByTriple(anyString(), anyString(), anyString()))
                .thenReturn(row("Price", 9.9, "GoodImg", "/img/a.jpg"));

        mockMvc.perform(post(PRICE_URL)
                        .param("MerchantId", "SMOKE-M1")
                        .param("GoodName", "smoke-goods")
                        .param("ProductCode", "SMOKE-P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.price").value(9.9));
    }

    /** 与 {@code PriceTripleFailSmokeTest.row} 同形状：替身缓存返回的裸行。 */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }
}
